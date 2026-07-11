impl OsrDesktopApp {
    fn render_header(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.vertical(|ui| {
                ui.label(
                    egui::RichText::new("OpenSoundRelay")
                        .size(27.0)
                        .strong()
                        .color(egui::Color32::WHITE),
                );
                ui.label(
                    egui::RichText::new(
                        "Local-first, low-latency audio relay with native volume synchronization",
                    )
                    .size(13.0)
                    .color(MUTED_TEXT),
                );
            });
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                status_badge(ui, self.mode, &self.status);
            });
        });
    }

    fn render_connection_card(&mut self, ui: &mut egui::Ui) {
        card(ui, |ui| {
            section_title(ui, "Network", "Find receivers on the current LAN or enter an address manually.");

            ui.label(egui::RichText::new("Device name").color(MUTED_TEXT));
            ui.text_edit_singleline(&mut self.device_name);
            ui.label(egui::RichText::new("Local bind address").color(MUTED_TEXT));
            ui.text_edit_singleline(&mut self.bind_addr);

            ui.horizontal(|ui| {
                let scan_text = if self.scanning { "Scanning…" } else { "Scan local network" };
                if primary_button(ui, scan_text).clicked() && !self.scanning {
                    self.start_discovery();
                }
                if ui.button("Clear").clicked() {
                    self.discovered.clear();
                }
            });

            ui.separator();
            ui.label(
                egui::RichText::new(format!("Discovered devices ({})", self.discovered.len()))
                    .strong(),
            );

            let mut selected = None;
            egui::ScrollArea::vertical()
                .id_salt("discovered_devices")
                .max_height(145.0)
                .show(ui, |ui| {
                    if self.discovered.is_empty() {
                        ui.label(
                            egui::RichText::new("No devices found yet")
                                .italics()
                                .color(MUTED_TEXT),
                        );
                    }
                    for device in &self.discovered {
                        egui::Frame::none()
                            .fill(egui::Color32::from_rgb(20, 23, 32))
                            .rounding(8.0)
                            .inner_margin(8.0)
                            .show(ui, |ui| {
                                ui.horizontal(|ui| {
                                    ui.vertical(|ui| {
                                        ui.label(egui::RichText::new(&device.name).strong());
                                        ui.label(
                                            egui::RichText::new(device.audio_addr.to_string())
                                                .small()
                                                .color(MUTED_TEXT),
                                        );
                                    });
                                    ui.with_layout(
                                        egui::Layout::right_to_left(egui::Align::Center),
                                        |ui| {
                                            if ui.small_button("Add target").clicked() {
                                                selected = Some(device.audio_addr);
                                            }
                                        },
                                    );
                                });
                            });
                    }
                });
            if let Some(addr) = selected {
                self.add_target(addr);
            }

            ui.label(egui::RichText::new("Targets").color(MUTED_TEXT));
            ui.add(
                egui::TextEdit::multiline(&mut self.target_addr)
                    .desired_rows(3)
                    .hint_text("192.168.1.10:40124\n192.168.1.11:40124"),
            );
        });
    }

    fn render_session_card(&mut self, ui: &mut egui::Ui) {
        let old_stream = self.stream_volume_percent;
        let old_native = self.native_volume;
        let old_sync = self.sync_native_volume;

        card(ui, |ui| {
            section_title(
                ui,
                "Session",
                "Stream gain and native device volume are synchronized independently.",
            );

            ui.horizontal(|ui| {
                ui.label(egui::RichText::new("OSR stream gain").color(MUTED_TEXT));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(format!("{}%", self.stream_volume_percent));
                });
            });
            ui.add(
                egui::Slider::new(&mut self.stream_volume_percent, 0..=200)
                    .show_value(false),
            );

            ui.add_space(4.0);
            ui.horizontal(|ui| {
                ui.label(egui::RichText::new("Native media/output volume").color(MUTED_TEXT));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(format!("{}%", self.native_volume.percent));
                });
            });
            ui.add(
                egui::Slider::new(&mut self.native_volume.percent, 0..=100)
                    .show_value(false),
            );
            ui.horizontal(|ui| {
                ui.checkbox(&mut self.native_volume.muted, "Mute");
                ui.checkbox(
                    &mut self.sync_native_volume,
                    "Follow this device's native volume on receivers",
                );
            });

            ui.separator();
            ui.horizontal_wrapped(|ui| {
                if primary_button(ui, "Start receiver").clicked() {
                    self.start_receiver();
                }
                if primary_button(ui, "Start test tone").clicked() {
                    self.start_tone_sender();
                }
                if ui
                    .add(egui::Button::new("Stop").fill(egui::Color32::from_rgb(68, 39, 49)))
                    .clicked()
                {
                    self.stop_worker();
                }
            });

            ui.label(
                egui::RichText::new(
                    "Native sync changes the OS media/output volume. Stream gain only changes OSR audio.",
                )
                .small()
                .color(MUTED_TEXT),
            );
        });

        if old_stream != self.stream_volume_percent {
            self.send_command(WorkerCommand::SetStreamVolume(
                self.stream_volume_percent * 10_000,
            ));
        }
        if old_sync != self.sync_native_volume {
            self.send_command(WorkerCommand::SetNativeSync(self.sync_native_volume));
        }
        if old_native != self.native_volume {
            self.apply_local_native_volume(self.native_volume);
            self.send_command(WorkerCommand::SetDeviceVolume(self.native_volume));
        }
    }

    fn render_audio_card(&mut self, ui: &mut egui::Ui) {
        let previous = self.audio_config;
        card(ui, |ui| {
            section_title(
                ui,
                "Audio quality & automatic delay correction",
                "The receiver resamples to the output device, shapes tone, and keeps the jitter buffer near the selected latency.",
            );

            ui.horizontal_wrapped(|ui| {
                if ui.small_button("Low latency · 20 ms").clicked() {
                    self.audio_config.target_latency_ms = 20;
                }
                if ui.small_button("Balanced · 40 ms").clicked() {
                    self.audio_config.target_latency_ms = 40;
                }
                if ui.small_button("Stable · 80 ms").clicked() {
                    self.audio_config.target_latency_ms = 80;
                }
                ui.checkbox(&mut self.audio_config.adaptive_latency, "Auto-correct drift");
                ui.checkbox(&mut self.audio_config.limiter, "Soft limiter");
            });

            ui.columns(3, |columns| {
                columns[0].label(egui::RichText::new("Target latency").color(MUTED_TEXT));
                columns[0].add(
                    egui::Slider::new(&mut self.audio_config.target_latency_ms, 10..=200)
                        .suffix(" ms"),
                );
                columns[1].label(egui::RichText::new("Bass").color(MUTED_TEXT));
                columns[1].add(
                    egui::Slider::new(&mut self.audio_config.bass_db, -12.0..=12.0)
                        .suffix(" dB"),
                );
                columns[2].label(egui::RichText::new("Treble").color(MUTED_TEXT));
                columns[2].add(
                    egui::Slider::new(&mut self.audio_config.treble_db, -12.0..=12.0)
                        .suffix(" dB"),
                );
            });
        });

        if previous != self.audio_config {
            self.send_command(WorkerCommand::UpdateAudioConfig(self.audio_config));
        }
    }

    fn render_metrics_and_log(&mut self, ui: &mut egui::Ui) {
        ui.columns(2, |columns| {
            card(&mut columns[0], |ui| {
                section_title(ui, "Live metrics", "Receiver health and adaptive-buffer state.");
                metric_row(ui, "Received frames", self.metrics.received_frames.to_string());
                metric_row(ui, "Estimated lost frames", self.metrics.dropped_frames.to_string());
                metric_row(ui, "Buffered audio", format!("{} ms", self.metrics.buffered_ms));
                metric_row(ui, "Output underruns", self.metrics.underruns.to_string());
                metric_row(
                    ui,
                    "Timing corrections",
                    self.metrics.timing_corrections.to_string(),
                );
                metric_row(
                    ui,
                    "Output sample rate",
                    if self.metrics.output_sample_rate == 0 {
                        "—".to_owned()
                    } else {
                        format!("{} Hz", self.metrics.output_sample_rate)
                    },
                );
            });

            card(&mut columns[1], |ui| {
                section_title(ui, "Activity", "Recent network and audio events.");
                egui::ScrollArea::vertical()
                    .id_salt("activity_log")
                    .max_height(190.0)
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                        for line in self.log.iter().rev().take(180).rev() {
                            ui.label(
                                egui::RichText::new(line)
                                    .monospace()
                                    .small()
                                    .color(egui::Color32::from_rgb(194, 201, 222)),
                            );
                        }
                    });
            });
        });
    }

    fn drain_events(&mut self) {
        let mut events = Vec::new();
        while let Ok(event) = self.event_rx.try_recv() {
            events.push(event);
        }
        for event in events {
            match event {
                WorkerEvent::Status(value) => self.status = value,
                WorkerEvent::Packet(value) => self.push_log(value),
                WorkerEvent::Error(value) => {
                    self.status = value.clone();
                    self.push_log(format!("ERROR: {value}"));
                }
                WorkerEvent::DiscoveryFinished(result) => {
                    self.scanning = false;
                    match result {
                        Ok(devices) => {
                            self.status = format!("Found {} OSR device(s)", devices.len());
                            self.discovered = devices;
                        }
                        Err(error) => {
                            self.status = "LAN scan failed".to_owned();
                            self.push_log(format!("Discovery error: {error}"));
                        }
                    }
                }
                WorkerEvent::Metrics(value) => self.metrics = value,
                WorkerEvent::NativeVolumeRead(result) => {
                    self.native_poll_pending = false;
                    match result {
                        Ok(value) => {
                            if value != self.native_volume {
                                self.native_volume = value;
                                if self.sync_native_volume && self.mode == Mode::ToneSender {
                                    self.send_command(WorkerCommand::SetDeviceVolume(value));
                                }
                            }
                        }
                        Err(error) => self.push_log(format!("Native volume unavailable: {error}")),
                    }
                }
            }
        }
    }

    fn start_discovery(&mut self) {
        self.scanning = true;
        self.status = "Scanning the local network…".to_owned();
        let tx = self.event_tx.clone();
        let name = self.device_name.clone();
        thread::spawn(move || {
            let result = discover_devices(&name, Duration::from_millis(1_400))
                .map_err(|error| error.to_string());
            let _ = tx.send(WorkerEvent::DiscoveryFinished(result));
        });
    }

    fn start_receiver(&mut self) {
        let bind = match self.bind_addr.parse::<SocketAddr>() {
            Ok(value) => value,
            Err(_) => {
                self.status = "Invalid bind address".to_owned();
                return;
            }
        };
        self.stop_worker();
        let (command_tx, command_rx) = mpsc::channel();
        let event_tx = self.event_tx.clone();
        let audio_config = self.audio_config;
        let sync_native = self.sync_native_volume;
        let device_name = self.device_name.clone();
        thread::spawn(move || {
            run_receiver_worker(
                bind,
                device_name,
                audio_config,
                sync_native,
                command_rx,
                event_tx,
            )
        });
        self.command_tx = Some(command_tx);
        self.mode = Mode::Receiver;
        self.status = "Starting receiver…".to_owned();
    }

    fn start_tone_sender(&mut self) {
        let bind = match self.bind_addr.parse::<SocketAddr>() {
            Ok(value) => value,
            Err(_) => {
                self.status = "Invalid bind address".to_owned();
                return;
            }
        };
        let targets = match TargetList::parse(&self.target_addr) {
            Ok(value) => value,
            Err(error) => {
                self.status = format!("Invalid targets: {error:?}");
                return;
            }
        };
        self.stop_worker();
        let (command_tx, command_rx) = mpsc::channel();
        let event_tx = self.event_tx.clone();
        let initial_gain = self.stream_volume_percent * 10_000;
        let native_volume = self.native_volume;
        let sync_native = self.sync_native_volume;
        thread::spawn(move || {
            run_tone_sender_worker(
                bind,
                targets,
                initial_gain,
                native_volume,
                sync_native,
                command_rx,
                event_tx,
            )
        });
        self.command_tx = Some(command_tx);
        self.mode = Mode::ToneSender;
        self.status = "Starting sender…".to_owned();
        self.last_native_poll = Instant::now() - Duration::from_secs(10);
    }

    fn stop_worker(&mut self) {
        if let Some(tx) = self.command_tx.take() {
            let _ = tx.send(WorkerCommand::Stop);
        }
        self.mode = Mode::Idle;
        self.status = "Ready".to_owned();
        self.metrics = ReceiverMetrics::default();
    }

    fn send_command(&self, command: WorkerCommand) {
        if let Some(tx) = &self.command_tx {
            let _ = tx.send(command);
        }
    }

    fn add_target(&mut self, addr: SocketAddr) {
        let value = addr.to_string();
        let exists = self
            .target_addr
            .split([',', '\n', ';'])
            .any(|item| item.trim() == value);
        if !exists {
            if !self.target_addr.trim().is_empty() {
                self.target_addr.push('\n');
            }
            self.target_addr.push_str(&value);
            self.status = format!("Added {value}");
        }
    }

    fn push_log(&mut self, line: String) {
        self.log.push(line);
        if self.log.len() > 600 {
            self.log.drain(0..100);
        }
    }

    fn apply_local_native_volume(&mut self, value: NativeVolumeState) {
        let tx = self.event_tx.clone();
        thread::spawn(move || {
            if let Err(error) = NativeVolume::set(value) {
                let _ = tx.send(WorkerEvent::Packet(format!(
                    "Could not set native volume: {error}"
                )));
            }
        });
    }

    fn poll_native_volume_if_needed(&mut self) {
        if self.mode != Mode::ToneSender
            || !self.sync_native_volume
            || self.native_poll_pending
            || self.last_native_poll.elapsed() < Duration::from_millis(900)
        {
            return;
        }
        self.last_native_poll = Instant::now();
        self.native_poll_pending = true;
        let tx = self.event_tx.clone();
        thread::spawn(move || {
            let _ = tx.send(WorkerEvent::NativeVolumeRead(NativeVolume::read()));
        });
    }
}
