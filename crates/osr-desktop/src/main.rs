// SPDX-License-Identifier: MPL-2.0

mod audio_output;
mod native_volume;

use audio_output::{AudioOutputConfig, AudioOutputStats, PcmAudioOutput};
use eframe::egui;
use native_volume::{NativeVolume, NativeVolumeState};
use osr_core::{
    apply_gain_i16, AudioCodec, AudioFrameHeader, DeviceVolumeState,
    DeviceVolumeSynchronizer, SampleFormat, VolumeState, VolumeSynchronizer,
};
use osr_net::{
    discover_devices, DiscoveredDevice, DiscoveryConfig, DiscoveryResponder, IncomingPacket,
    StreamStats, TargetList, UdpEndpoint, UdpEndpointConfig,
};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const ACCENT: egui::Color32 = egui::Color32::from_rgb(103, 92, 255);
const ACCENT_HOVER: egui::Color32 = egui::Color32::from_rgb(126, 116, 255);
const PANEL: egui::Color32 = egui::Color32::from_rgb(24, 27, 38);
const PANEL_ALT: egui::Color32 = egui::Color32::from_rgb(31, 35, 48);
const BORDER: egui::Color32 = egui::Color32::from_rgb(52, 58, 77);
const MUTED_TEXT: egui::Color32 = egui::Color32::from_rgb(157, 164, 187);

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1120.0, 780.0])
            .with_min_inner_size([900.0, 650.0]),
        ..Default::default()
    };
    eframe::run_native(
        "OpenSoundRelay",
        options,
        Box::new(|cc| {
            configure_style(&cc.egui_ctx);
            Ok(Box::<OsrDesktopApp>::default())
        }),
    )
}

fn configure_style(ctx: &egui::Context) {
    let mut visuals = egui::Visuals::dark();
    visuals.panel_fill = egui::Color32::from_rgb(15, 17, 24);
    visuals.window_fill = PANEL;
    visuals.extreme_bg_color = egui::Color32::from_rgb(12, 14, 20);
    visuals.widgets.inactive.bg_fill = PANEL_ALT;
    visuals.widgets.inactive.weak_bg_fill = PANEL_ALT;
    visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(42, 47, 65);
    visuals.widgets.active.bg_fill = ACCENT;
    visuals.selection.bg_fill = ACCENT;
    visuals.hyperlink_color = egui::Color32::from_rgb(133, 197, 255);
    ctx.set_visuals(visuals);

    let mut style = (*ctx.style()).clone();
    style.spacing.item_spacing = egui::vec2(9.0, 9.0);
    style.spacing.button_padding = egui::vec2(12.0, 7.0);
    ctx.set_style(style);
}

#[derive(Debug, Clone)]
enum WorkerCommand {
    Stop,
    SetStreamVolume(u32),
    SetDeviceVolume(NativeVolumeState),
    SetNativeSync(bool),
    UpdateAudioConfig(AudioOutputConfig),
}

#[derive(Debug, Clone)]
enum WorkerEvent {
    Status(String),
    Packet(String),
    Error(String),
    DiscoveryFinished(Result<Vec<DiscoveredDevice>, String>),
    Metrics(ReceiverMetrics),
    NativeVolumeRead(Result<NativeVolumeState, String>),
}

#[derive(Debug, Clone, Copy, Default)]
struct ReceiverMetrics {
    received_frames: u64,
    dropped_frames: u64,
    buffered_ms: u32,
    underruns: u64,
    timing_corrections: u64,
    output_sample_rate: u32,
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
    device_name: String,
    stream_volume_percent: u32,
    native_volume: NativeVolumeState,
    sync_native_volume: bool,
    audio_config: AudioOutputConfig,
    status: String,
    log: Vec<String>,
    discovered: Vec<DiscoveredDevice>,
    scanning: bool,
    metrics: ReceiverMetrics,
    command_tx: Option<Sender<WorkerCommand>>,
    event_tx: Sender<WorkerEvent>,
    event_rx: Receiver<WorkerEvent>,
    last_native_poll: Instant,
    native_poll_pending: bool,
}

impl Default for OsrDesktopApp {
    fn default() -> Self {
        let (event_tx, event_rx) = mpsc::channel();
        Self {
            mode: Mode::Idle,
            bind_addr: "0.0.0.0:40124".to_owned(),
            target_addr: String::new(),
            device_name: default_device_name(),
            stream_volume_percent: 100,
            native_volume: NativeVolumeState::new(100, false),
            sync_native_volume: true,
            audio_config: AudioOutputConfig::default(),
            status: "Ready".to_owned(),
            log: vec!["OpenSoundRelay desktop initialized".to_owned()],
            discovered: Vec::new(),
            scanning: false,
            metrics: ReceiverMetrics::default(),
            command_tx: None,
            event_tx,
            event_rx,
            last_native_poll: Instant::now() - Duration::from_secs(10),
            native_poll_pending: false,
        }
    }
}

impl eframe::App for OsrDesktopApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.drain_events();
        self.poll_native_volume_if_needed();

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(4.0);
            self.render_header(ui);
            ui.add_space(10.0);

            ui.columns(2, |columns| {
                self.render_connection_card(&mut columns[0]);
                self.render_session_card(&mut columns[1]);
            });

            ui.add_space(10.0);
            self.render_audio_card(ui);
            ui.add_space(10.0);
            self.render_metrics_and_log(ui);
        });

        ctx.request_repaint_after(Duration::from_millis(100));
    }
}

include!("ui.rs");
include!("workers.rs");
