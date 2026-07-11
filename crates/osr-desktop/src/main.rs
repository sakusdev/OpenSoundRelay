// SPDX-License-Identifier: MPL-2.0

mod audio_output;

use audio_output::PcmAudioOutput;
use eframe::egui;
use osr_core::{AudioCodec, AudioFrameHeader, SampleFormat, VolumeState};
use osr_net::{IncomingPacket, TargetList, UdpEndpoint, UdpEndpointConfig};
use std::net::SocketAddr;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread;
use std::time::{Duration, Instant};

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions::default();
    eframe::run_native(
        "OpenSoundRelay Desktop",
        options,
        Box::new(|_cc| Ok(Box::<OsrDesktopApp>::default())),
    )
}

#[derive(Debug, Clone)]
enum WorkerCommand {
    Stop,
    SetVolume(u32),
}

#[derive(Debug, Clone)]
enum WorkerEvent {
    Status(String),
    Packet(String),
    Error(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Mode {
    Idle,
    Receiver,
    ToneSender,
}

struct OsrDesktopApp {
    mode: Mode,
    bind_addr: String,
    target_addr: String,
    volume_percent: u32,
    status: String,
    log: Vec<String>,
    command_tx: Option<Sender<WorkerCommand>>,
    event_rx: Option<Receiver<WorkerEvent>>,
}

impl Default for OsrDesktopApp {
    fn default() -> Self {
        Self {
            mode: Mode::Idle,
            bind_addr: "0.0.0.0:40124".to_owned(),
            target_addr: "127.0.0.1:40124\n127.0.0.1:40125".to_owned(),
            volume_percent: 100,
            status: "Idle".to_owned(),
            log: Vec::new(),
            command_tx: None,
            event_rx: None,
        }
    }
}

impl eframe::App for OsrDesktopApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.drain_events();

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("OpenSoundRelay Desktop");
            ui.label("Cross-platform UDP GUI for OSR audio, volume sync, PCM playback, and multi-device fan-out.");
            ui.separator();

            ui.horizontal(|ui| {
                ui.label("Bind:");
                ui.text_edit_singleline(&mut self.bind_addr);
            });
            ui.label("Targets, one per line or comma-separated:");
            ui.add(
                egui::TextEdit::multiline(&mut self.target_addr)
                    .desired_rows(3)
                    .hint_text("192.168.1.10:40124\n192.168.1.11:40124"),
            );

            let old_volume = self.volume_percent;
            ui.add(egui::Slider::new(&mut self.volume_percent, 0..=200).text("Parent volume %"));
            if old_volume != self.volume_percent {
                if let Some(tx) = &self.command_tx {
                    let _ = tx.send(WorkerCommand::SetVolume(self.volume_percent * 10_000));
                }
            }

            ui.horizontal(|ui| {
                if ui.button("Start Receiver + Playback").clicked() {
                    self.start_receiver();
                }
                if ui.button("Start Tone Sender Fan-out").clicked() {
                    self.start_tone_sender();
                }
                if ui.button("Stop").clicked() {
                    self.stop_worker();
                }
            });

            ui.separator();
            ui.label(format!("Mode: {:?}", self.mode));
            ui.label(format!("Status: {}", self.status));
            ui.separator();
            ui.label("Log:");
            egui::ScrollArea::vertical().max_height(300.0).show(ui, |ui| {
                for line in self.log.iter().rev().take(200).rev() {
                    ui.monospace(line);
                }
            });
        });

        ctx.request_repaint_after(Duration::from_millis(100));
    }
}

impl OsrDesktopApp {
    fn drain_events(&mut self) {
        let Some(rx) = &self.event_rx else {
            return;
        };
        let mut events = Vec::new();
        while let Ok(event) = rx.try_recv() {
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
            }
        }
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
        let (event_tx, event_rx) = mpsc::channel();
        thread::spawn(move || run_receiver_worker(bind, command_rx, event_tx));
        self.command_tx = Some(command_tx);
        self.event_rx = Some(event_rx);
        self.mode = Mode::Receiver;
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
        let initial_gain = self.volume_percent * 10_000;
        let (command_tx, command_rx) = mpsc::channel();
        let (event_tx, event_rx) = mpsc::channel();
        thread::spawn(move || {
            run_tone_sender_worker(bind, targets, initial_gain, command_rx, event_tx)
        });
        self.command_tx = Some(command_tx);
        self.event_rx = Some(event_rx);
        self.mode = Mode::ToneSender;
    }

    fn stop_worker(&mut self) {
        if let Some(tx) = self.command_tx.take() {
            let _ = tx.send(WorkerCommand::Stop);
        }
        self.event_rx = None;
        self.mode = Mode::Idle;
        self.status = "Idle".to_owned();
    }

    fn push_log(&mut self, line: String) {
        self.log.push(line);
        if self.log.len() > 500 {
            self.log.drain(0..100);
        }
    }
}

fn run_receiver_worker(
    bind: SocketAddr,
    command_rx: Receiver<WorkerCommand>,
    event_tx: Sender<WorkerEvent>,
) {
    let _ = event_tx.send(WorkerEvent::Status(format!("Binding receiver on {bind}")));
    let mut endpoint = match UdpEndpoint::bind(UdpEndpointConfig {
        bind_addr: bind,
        ..Default::default()
    }) {
        Ok(value) => value,
        Err(error) => {
            let _ = event_tx.send(WorkerEvent::Error(format!("bind failed: {error}")));
            return;
        }
    };

    let output = match PcmAudioOutput::open_default() {
        Ok(value) => value,
        Err(error) => {
            let _ = event_tx.send(WorkerEvent::Error(format!("audio output failed: {error}")));
            return;
        }
    };
    let _ = event_tx.send(WorkerEvent::Status(format!("Receiver listening on {bind}")));

    loop {
        while let Ok(command) = command_rx.try_recv() {
            if matches!(command, WorkerCommand::Stop) {
                let _ = event_tx.send(WorkerEvent::Status("Receiver stopped".to_owned()));
                return;
            }
        }

        match endpoint.recv() {
            Ok(Some(IncomingPacket::Audio { from, frame, .. })) => {
                if frame.header.codec == AudioCodec::Pcm
                    && frame.header.sample_format == SampleFormat::S16Le
                    && frame.header.channels == 1
                {
                    output.push_pcm_s16le_mono(&frame.payload);
                }
                let _ = event_tx.send(WorkerEvent::Packet(format!(
                    "audio from={from} seq={} bytes={} codec={:?}",
                    frame.header.frame_sequence,
                    frame.payload.len(),
                    frame.header.codec
                )));
            }
            Ok(Some(IncomingPacket::VolumeCommand { from, command, .. })) => {
                let _ = event_tx.send(WorkerEvent::Packet(format!(
                    "volume from={from} stream={} seq={} gain={}ppm",
                    command.stream_id, command.sequence, command.gain_ppm
                )));
            }
            Ok(Some(IncomingPacket::Other { from, kind, .. })) => {
                let _ = event_tx.send(WorkerEvent::Packet(format!(
                    "other from={from} kind={kind:?}"
                )));
            }
            Ok(None) => {}
            Err(error) => {
                let _ = event_tx.send(WorkerEvent::Error(format!("receive failed: {error}")));
                return;
            }
        }
    }
}

fn run_tone_sender_worker(
    bind: SocketAddr,
    targets: TargetList,
    initial_gain_ppm: u32,
    command_rx: Receiver<WorkerCommand>,
    event_tx: Sender<WorkerEvent>,
) {
    let mut endpoint = match UdpEndpoint::bind(UdpEndpointConfig {
        bind_addr: bind,
        ..Default::default()
    }) {
        Ok(value) => value,
        Err(error) => {
            let _ = event_tx.send(WorkerEvent::Error(format!("bind failed: {error}")));
            return;
        }
    };

    let mut gain_ppm = initial_gain_ppm;
    let mut frame_sequence = 1u64;
    let mut volume_sequence = 1u64;
    let started = Instant::now();
    let stream_id = 1u32;
    let sample_rate = 48_000u32;
    let samples_per_frame = (sample_rate / 100) as usize;
    let _ = event_tx.send(WorkerEvent::Status(format!(
        "Tone sender fan-out -> {} target(s)",
        targets.len()
    )));

    loop {
        while let Ok(command) = command_rx.try_recv() {
            match command {
                WorkerCommand::Stop => {
                    let _ = event_tx.send(WorkerEvent::Status("Tone sender stopped".to_owned()));
                    return;
                }
                WorkerCommand::SetVolume(value) => gain_ppm = value,
            }
        }

        let volume = VolumeState::new(stream_id, 1, volume_sequence, gain_ppm);
        let volume_report = endpoint.send_volume_command_to_targets(&targets, volume);
        volume_sequence = volume_sequence.wrapping_add(1).max(1);

        let mut payload = Vec::with_capacity(samples_per_frame * 2);
        for i in 0..samples_per_frame {
            let absolute_sample = ((frame_sequence - 1) as usize * samples_per_frame) + i;
            let phase = absolute_sample as f32 * 440.0 * std::f32::consts::TAU / sample_rate as f32;
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
        if frame_sequence.is_multiple_of(100)
            || !audio_report.all_sent()
            || !volume_report.all_sent()
        {
            let _ = event_tx.send(WorkerEvent::Packet(format!(
                "fanout seq={} audio={}/{} volume={}/{} failed_audio={:?} failed_volume={:?}",
                frame_sequence,
                audio_report.sent,
                audio_report.attempted,
                volume_report.sent,
                volume_report.attempted,
                audio_report.failed,
                volume_report.failed
            )));
        }
        frame_sequence = frame_sequence.wrapping_add(1).max(1);
        thread::sleep(Duration::from_millis(10));
    }
}
