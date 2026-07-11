fn run_receiver_worker(
    bind: SocketAddr,
    device_name: String,
    initial_audio_config: AudioOutputConfig,
    mut sync_native_volume: bool,
    command_rx: Receiver<WorkerCommand>,
    event_tx: Sender<WorkerEvent>,
) {
    let _ = event_tx.send(WorkerEvent::Status(format!("Binding receiver on {bind}")));
    let mut endpoint = match UdpEndpoint::bind(UdpEndpointConfig {
        bind_addr: bind,
        read_timeout: Some(Duration::from_millis(50)),
        ..Default::default()
    }) {
        Ok(value) => value,
        Err(error) => {
            let _ = event_tx.send(WorkerEvent::Error(format!("bind failed: {error}")));
            return;
        }
    };

    let output = match PcmAudioOutput::open_default(initial_audio_config) {
        Ok(value) => value,
        Err(error) => {
            let _ = event_tx.send(WorkerEvent::Error(format!("audio output failed: {error}")));
            return;
        }
    };
    let output_device_name = output.device_name().to_owned();

    let discovery_stop = Arc::new(AtomicBool::new(false));
    spawn_discovery_responder(
        device_name,
        bind.port(),
        Arc::clone(&discovery_stop),
        event_tx.clone(),
    );

    let mut stream_volume = VolumeSynchronizer::new(VolumeState::default());
    let mut device_volume = DeviceVolumeSynchronizer::new(DeviceVolumeState::default());
    let mut stream_stats = StreamStats::default();
    let mut last_native_applied = None::<NativeVolumeState>;
    let mut last_metrics = Instant::now();
    let _ = event_tx.send(WorkerEvent::Status(format!(
        "Receiver listening on {bind} · output {output_device_name}"
    )));

    'receiver: loop {
        while let Ok(command) = command_rx.try_recv() {
            match command {
                WorkerCommand::Stop => break 'receiver,
                WorkerCommand::SetNativeSync(value) => sync_native_volume = value,
                WorkerCommand::UpdateAudioConfig(value) => output.update_config(value),
                WorkerCommand::SetStreamVolume(_) | WorkerCommand::SetDeviceVolume(_) => {}
            }
        }

        match endpoint.recv() {
            Ok(Some(packet)) => {
                stream_stats.observe(&packet);
                match packet {
                    IncomingPacket::Audio { from, frame, .. } => {
                        let payload_len = frame.payload.len();
                        if frame.header.codec == AudioCodec::Pcm
                            && frame.header.sample_format == SampleFormat::S16Le
                            && frame.header.channels == 1
                        {
                            let state = stream_volume.current();
                            if state.gain_ppm == 1_000_000 && !state.muted {
                                output.push_pcm_s16le_mono(&frame.payload);
                            } else {
                                let mut payload = frame.payload;
                                for sample in payload.chunks_exact_mut(2) {
                                    let value = i16::from_le_bytes([sample[0], sample[1]]);
                                    let adjusted = apply_gain_i16(value, state.gain_ppm, state.muted);
                                    sample.copy_from_slice(&adjusted.to_le_bytes());
                                }
                                output.push_pcm_s16le_mono(&payload);
                            }
                        }
                        if frame.header.frame_sequence.is_multiple_of(200) {
                            let _ = event_tx.send(WorkerEvent::Packet(format!(
                                "audio {from} · seq {} · {} bytes",
                                frame.header.frame_sequence,
                                payload_len
                            )));
                        }
                    }
                    IncomingPacket::VolumeCommand { from, command, .. } => {
                        if stream_volume.apply_parent_command(command) {
                            let _ = event_tx.send(WorkerEvent::Packet(format!(
                                "stream gain {from} · {}%{}",
                                command.gain_ppm / 10_000,
                                if command.muted { " muted" } else { "" }
                            )));
                        }
                    }
                    IncomingPacket::DeviceVolume { from, command, .. } => {
                        if device_volume.apply_parent_command(command) {
                            let requested = NativeVolumeState::new(
                                command.volume_percent,
                                command.muted,
                            );
                            if sync_native_volume && last_native_applied != Some(requested) {
                                match NativeVolume::set(requested) {
                                    Ok(()) => {
                                        last_native_applied = Some(requested);
                                        let _ = event_tx.send(WorkerEvent::Packet(format!(
                                            "native volume {from} · {}%{}",
                                            requested.percent,
                                            if requested.muted { " muted" } else { "" }
                                        )));
                                    }
                                    Err(error) => {
                                        let _ = event_tx.send(WorkerEvent::Packet(format!(
                                            "native volume command unsupported: {error}"
                                        )));
                                    }
                                }
                            }
                        }
                    }
                    IncomingPacket::Other { from, kind, .. } => {
                        let _ = event_tx.send(WorkerEvent::Packet(format!(
                            "control packet {kind:?} from {from}"
                        )));
                    }
                }
            }
            Ok(None) => {}
            Err(error) => {
                let _ = event_tx.send(WorkerEvent::Error(format!("receive failed: {error}")));
                break;
            }
        }

        if last_metrics.elapsed() >= Duration::from_secs(1) {
            let audio = output.stats();
            let _ = event_tx.send(WorkerEvent::Metrics(receiver_metrics(&stream_stats, audio)));
            last_metrics = Instant::now();
        }
    }

    discovery_stop.store(true, Ordering::Relaxed);
    let _ = event_tx.send(WorkerEvent::Status("Receiver stopped".to_owned()));
}

fn spawn_discovery_responder(
    device_name: String,
    audio_port: u16,
    stop: Arc<AtomicBool>,
    event_tx: Sender<WorkerEvent>,
) {
    thread::spawn(move || {
        let mut responder = match DiscoveryResponder::bind(DiscoveryConfig::receiver(
            device_name,
            audio_port,
        )) {
            Ok(value) => value,
            Err(error) => {
                let _ = event_tx.send(WorkerEvent::Packet(format!(
                    "LAN discovery responder unavailable: {error}"
                )));
                return;
            }
        };
        while !stop.load(Ordering::Relaxed) {
            match responder.poll() {
                Ok(Some(from)) => {
                    let _ = event_tx.send(WorkerEvent::Packet(format!(
                        "Answered LAN discovery probe from {from}"
                    )));
                }
                Ok(None) => {}
                Err(error) => {
                    let _ = event_tx.send(WorkerEvent::Packet(format!(
                        "LAN discovery responder error: {error}"
                    )));
                    return;
                }
            }
        }
    });
}

fn receiver_metrics(stream: &StreamStats, audio: AudioOutputStats) -> ReceiverMetrics {
    ReceiverMetrics {
        received_frames: stream.received_audio_frames,
        dropped_frames: stream.dropped_audio_frames,
        buffered_ms: audio.buffered_ms,
        underruns: audio.underruns,
        timing_corrections: audio.timing_corrections,
        output_sample_rate: audio.output_sample_rate,
    }
}

fn run_tone_sender_worker(
    bind: SocketAddr,
    targets: TargetList,
    initial_gain_ppm: u32,
    initial_native_volume: NativeVolumeState,
    mut sync_native_volume: bool,
    command_rx: Receiver<WorkerCommand>,
    event_tx: Sender<WorkerEvent>,
) {
    let mut endpoint = match UdpEndpoint::bind(UdpEndpointConfig {
        bind_addr: bind,
        read_timeout: Some(Duration::from_millis(20)),
        ..Default::default()
    }) {
        Ok(value) => value,
        Err(error) => {
            let _ = event_tx.send(WorkerEvent::Error(format!("bind failed: {error}")));
            return;
        }
    };

    let mut gain_ppm = initial_gain_ppm;
    let mut native_volume = initial_native_volume;
    let mut frame_sequence = 1u64;
    let mut volume_sequence = 1u64;
    let mut device_volume_sequence = 1u64;
    let started = Instant::now();
    let epoch = session_epoch();
    let stream_id = 1u32;
    let sample_rate = 48_000u32;
    let samples_per_frame = (sample_rate / 100) as usize;
    let _ = event_tx.send(WorkerEvent::Status(format!(
        "Test tone → {} target(s)",
        targets.len()
    )));

    loop {
        while let Ok(command) = command_rx.try_recv() {
            match command {
                WorkerCommand::Stop => {
                    let _ = event_tx.send(WorkerEvent::Status("Sender stopped".to_owned()));
                    return;
                }
                WorkerCommand::SetStreamVolume(value) => gain_ppm = value,
                WorkerCommand::SetDeviceVolume(value) => native_volume = value,
                WorkerCommand::SetNativeSync(value) => sync_native_volume = value,
                WorkerCommand::UpdateAudioConfig(_) => {}
            }
        }

        let mut payload = Vec::with_capacity(samples_per_frame * 2);
        for i in 0..samples_per_frame {
            let absolute_sample = ((frame_sequence - 1) as usize * samples_per_frame) + i;
            let phase = absolute_sample as f32 * 440.0 * std::f32::consts::TAU
                / sample_rate as f32;
            let sample = (phase.sin() * i16::MAX as f32 * 0.12) as i16;
            payload.extend_from_slice(&sample.to_le_bytes());
        }

        let header = AudioFrameHeader::pcm_s16le(
            stream_id,
            started.elapsed().as_micros() as u64,
            frame_sequence,
            sample_rate,
            1,
            10_000,
            payload.len() as u32,
        );
        let audio_report = endpoint.send_audio_to_targets(&targets, header, &payload);

        let mut control_failure = false;
        if frame_sequence.is_multiple_of(10) {
            let volume = VolumeState::new(stream_id, epoch, volume_sequence, gain_ppm);
            let report = endpoint.send_volume_command_to_targets(&targets, volume);
            control_failure |= !report.all_sent();
            volume_sequence = volume_sequence.wrapping_add(1).max(1);

            if sync_native_volume {
                let mut command = DeviceVolumeState::new(
                    epoch,
                    device_volume_sequence,
                    native_volume.percent,
                );
                command.muted = native_volume.muted;
                let report = endpoint.send_device_volume_to_targets(&targets, command);
                control_failure |= !report.all_sent();
                device_volume_sequence = device_volume_sequence.wrapping_add(1).max(1);
            }
        }

        if frame_sequence.is_multiple_of(100) || !audio_report.all_sent() || control_failure {
            let _ = event_tx.send(WorkerEvent::Packet(format!(
                "fan-out seq={} audio={}/{} stream={}%, native={}{}",
                frame_sequence,
                audio_report.sent,
                audio_report.attempted,
                gain_ppm / 10_000,
                native_volume.percent,
                if native_volume.muted { "% muted" } else { "%" }
            )));
        }
        frame_sequence = frame_sequence.wrapping_add(1).max(1);
        thread::sleep(Duration::from_millis(10));
    }
}

fn card(ui: &mut egui::Ui, add_contents: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(PANEL)
        .stroke(egui::Stroke::new(1.0, BORDER))
        .rounding(12.0)
        .inner_margin(14.0)
        .show(ui, add_contents);
}

fn section_title(ui: &mut egui::Ui, title: &str, subtitle: &str) {
    ui.label(egui::RichText::new(title).size(17.0).strong());
    ui.label(egui::RichText::new(subtitle).small().color(MUTED_TEXT));
    ui.add_space(3.0);
}

fn primary_button(ui: &mut egui::Ui, text: &str) -> egui::Response {
    ui.add(
        egui::Button::new(egui::RichText::new(text).strong())
            .fill(ACCENT)
            .stroke(egui::Stroke::new(1.0, ACCENT_HOVER)),
    )
}

fn status_badge(ui: &mut egui::Ui, mode: Mode, status: &str) {
    let (label, color) = match mode {
        Mode::Idle => ("READY", egui::Color32::from_rgb(59, 193, 137)),
        Mode::Receiver => ("RECEIVING", egui::Color32::from_rgb(72, 166, 255)),
        Mode::ToneSender => ("SENDING", egui::Color32::from_rgb(174, 122, 255)),
    };
    egui::Frame::none()
        .fill(color.gamma_multiply(0.18))
        .stroke(egui::Stroke::new(1.0, color.gamma_multiply(0.65)))
        .rounding(999.0)
        .inner_margin(egui::Margin::symmetric(11.0, 6.0))
        .show(ui, |ui| {
            ui.label(
                egui::RichText::new(format!("● {label}  {status}"))
                    .small()
                    .strong()
                    .color(color),
            );
        });
}

fn metric_row(ui: &mut egui::Ui, label: &str, value: String) {
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new(label).color(MUTED_TEXT));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.label(egui::RichText::new(value).strong());
        });
    });
}

fn session_epoch() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_micros() as u64
}

fn default_device_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "OSR Desktop".to_owned())
}
