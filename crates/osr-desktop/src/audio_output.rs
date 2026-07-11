// SPDX-License-Identifier: MPL-2.0

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, Stream, StreamConfig};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

const INPUT_SAMPLE_RATE: u32 = 48_000;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AudioOutputConfig {
    pub target_latency_ms: u32,
    pub adaptive_latency: bool,
    pub bass_db: f32,
    pub treble_db: f32,
    pub limiter: bool,
}

impl Default for AudioOutputConfig {
    fn default() -> Self {
        Self {
            target_latency_ms: 40,
            adaptive_latency: true,
            bass_db: 0.0,
            treble_db: 0.0,
            limiter: true,
        }
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct AudioOutputStats {
    pub buffered_ms: u32,
    pub underruns: u64,
    pub dropped_samples: u64,
    pub timing_corrections: u64,
    pub output_sample_rate: u32,
}

struct SharedAudioState {
    queue: VecDeque<i16>,
    config: AudioOutputConfig,
    sample_rate: u32,
    started: bool,
    last_sample: i16,
    low_pass: f32,
    correction_counter: u32,
    underruns: u64,
    dropped_samples: u64,
    timing_corrections: u64,
}

impl SharedAudioState {
    fn target_samples(&self) -> usize {
        ((self.sample_rate as u64 * self.config.target_latency_ms as u64) / 1_000).max(1) as usize
    }

    fn render_next(&mut self) -> i16 {
        let target = self.target_samples();
        if !self.started {
            if self.queue.len() < target {
                return 0;
            }
            self.started = true;
        }

        if self.queue.is_empty() {
            self.started = false;
            self.underruns = self.underruns.saturating_add(1);
            self.last_sample = 0;
            return 0;
        }

        self.correction_counter = self.correction_counter.wrapping_add(1);
        if self.config.adaptive_latency {
            let high_water = target.saturating_add((self.sample_rate / 50) as usize);
            let low_water = target.saturating_sub((self.sample_rate / 100) as usize);

            if self.queue.len() > high_water && self.correction_counter.is_multiple_of(64) {
                if self.queue.pop_front().is_some() {
                    self.dropped_samples = self.dropped_samples.saturating_add(1);
                    self.timing_corrections = self.timing_corrections.saturating_add(1);
                }
            } else if self.queue.len() < low_water
                && self.queue.len() > 1
                && self.correction_counter.is_multiple_of(192)
            {
                self.timing_corrections = self.timing_corrections.saturating_add(1);
                return self.last_sample;
            }
        }

        let sample = self.queue.pop_front().unwrap_or(0);
        self.last_sample = sample;
        sample
    }

    fn push_input(&mut self, data: &[u8]) {
        let input: Vec<i16> = data
            .chunks_exact(2)
            .map(|chunk| i16::from_le_bytes([chunk[0], chunk[1]]))
            .collect();
        if input.is_empty() {
            return;
        }

        let output_len = ((input.len() as u64 * self.sample_rate as u64)
            .div_ceil(INPUT_SAMPLE_RATE as u64)) as usize;
        let bass_gain = db_to_gain(self.config.bass_db);
        let treble_gain = db_to_gain(self.config.treble_db);
        let alpha = (std::f32::consts::TAU * 250.0 / self.sample_rate as f32).clamp(0.001, 0.5);

        for output_index in 0..output_len.max(1) {
            let source_position = if output_len <= 1 || input.len() <= 1 {
                0.0
            } else {
                output_index as f32 * (input.len() - 1) as f32 / (output_len - 1) as f32
            };
            let source_index = source_position.floor() as usize;
            let fraction = source_position - source_index as f32;
            let a = input[source_index] as f32;
            let b = input
                .get(source_index + 1)
                .copied()
                .unwrap_or(input[source_index]) as f32;
            let input_sample = a + (b - a) * fraction;

            self.low_pass += alpha * (input_sample - self.low_pass);
            let low = self.low_pass;
            let high = input_sample - low;
            let mut output = low * bass_gain + high * treble_gain;
            if self.config.limiter {
                let normalized = output / i16::MAX as f32;
                output = normalized.tanh() * i16::MAX as f32;
            }
            self.queue
                .push_back(output.clamp(i16::MIN as f32, i16::MAX as f32) as i16);
        }

        let target = self.target_samples();
        let soft_max = target.saturating_add((self.sample_rate * 80 / 1_000) as usize);
        if self.config.adaptive_latency && self.queue.len() > soft_max {
            let desired = target.saturating_add((self.sample_rate * 10 / 1_000) as usize);
            let drop_count = self.queue.len().saturating_sub(desired);
            self.queue.drain(..drop_count);
            self.dropped_samples = self.dropped_samples.saturating_add(drop_count as u64);
            self.timing_corrections = self.timing_corrections.saturating_add(1);
        }

        let hard_max = (self.sample_rate / 2) as usize;
        if self.queue.len() > hard_max {
            let drop_count = self.queue.len() - hard_max;
            self.queue.drain(..drop_count);
            self.dropped_samples = self.dropped_samples.saturating_add(drop_count as u64);
        }
    }
}

pub struct PcmAudioOutput {
    state: Arc<Mutex<SharedAudioState>>,
    device_name: String,
    _stream: Stream,
}

impl PcmAudioOutput {
    pub fn open_default(audio_config: AudioOutputConfig) -> Result<Self, String> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or_else(|| "no default output device".to_owned())?;
        let device_name = device
            .name()
            .unwrap_or_else(|_| "Default output".to_owned());
        let supported_config = device
            .default_output_config()
            .map_err(|err| format!("failed to read default output config: {err}"))?;
        let sample_format = supported_config.sample_format();
        let config: StreamConfig = supported_config.into();
        let channels = config.channels.max(1) as usize;
        let sample_rate = config.sample_rate.0;
        let state = Arc::new(Mutex::new(SharedAudioState {
            queue: VecDeque::with_capacity(sample_rate as usize / 2),
            config: audio_config,
            sample_rate,
            started: false,
            last_sample: 0,
            low_pass: 0.0,
            correction_counter: 0,
            underruns: 0,
            dropped_samples: 0,
            timing_corrections: 0,
        }));
        let err_fn = |err| eprintln!("desktop audio output error: {err}");

        let stream = match sample_format {
            SampleFormat::F32 => {
                let callback_state = Arc::clone(&state);
                device
                    .build_output_stream(
                        &config,
                        move |data: &mut [f32], _| {
                            write_output_f32(data, channels, &callback_state)
                        },
                        err_fn,
                        None,
                    )
                    .map_err(|err| format!("failed to build f32 output stream: {err}"))?
            }
            SampleFormat::I16 => {
                let callback_state = Arc::clone(&state);
                device
                    .build_output_stream(
                        &config,
                        move |data: &mut [i16], _| {
                            write_output_i16(data, channels, &callback_state)
                        },
                        err_fn,
                        None,
                    )
                    .map_err(|err| format!("failed to build i16 output stream: {err}"))?
            }
            SampleFormat::U16 => {
                let callback_state = Arc::clone(&state);
                device
                    .build_output_stream(
                        &config,
                        move |data: &mut [u16], _| {
                            write_output_u16(data, channels, &callback_state)
                        },
                        err_fn,
                        None,
                    )
                    .map_err(|err| format!("failed to build u16 output stream: {err}"))?
            }
            other => return Err(format!("unsupported output sample format: {other:?}")),
        };

        stream
            .play()
            .map_err(|err| format!("failed to start output stream: {err}"))?;

        Ok(Self {
            state,
            device_name,
            _stream: stream,
        })
    }

    pub fn device_name(&self) -> &str {
        &self.device_name
    }

    pub fn push_pcm_s16le_mono(&self, data: &[u8]) {
        if let Ok(mut state) = self.state.lock() {
            state.push_input(data);
        }
    }

    pub fn update_config(&self, config: AudioOutputConfig) {
        if let Ok(mut state) = self.state.lock() {
            let latency_changed = state.config.target_latency_ms != config.target_latency_ms;
            state.config = config;
            if latency_changed {
                state.started = false;
            }
        }
    }

    pub fn stats(&self) -> AudioOutputStats {
        match self.state.lock() {
            Ok(state) => AudioOutputStats {
                buffered_ms: ((state.queue.len() as u64 * 1_000) / state.sample_rate as u64) as u32,
                underruns: state.underruns,
                dropped_samples: state.dropped_samples,
                timing_corrections: state.timing_corrections,
                output_sample_rate: state.sample_rate,
            },
            Err(_) => AudioOutputStats::default(),
        }
    }
}

fn db_to_gain(db: f32) -> f32 {
    10.0f32.powf(db.clamp(-12.0, 12.0) / 20.0)
}

fn write_output_f32(data: &mut [f32], channels: usize, state: &Arc<Mutex<SharedAudioState>>) {
    let Ok(mut state) = state.lock() else {
        data.fill(0.0);
        return;
    };
    for frame in data.chunks_mut(channels) {
        let sample = state.render_next() as f32 / i16::MAX as f32;
        frame.fill(sample);
    }
}

fn write_output_i16(data: &mut [i16], channels: usize, state: &Arc<Mutex<SharedAudioState>>) {
    let Ok(mut state) = state.lock() else {
        data.fill(0);
        return;
    };
    for frame in data.chunks_mut(channels) {
        let sample = state.render_next();
        frame.fill(sample);
    }
}

fn write_output_u16(data: &mut [u16], channels: usize, state: &Arc<Mutex<SharedAudioState>>) {
    let Ok(mut state) = state.lock() else {
        data.fill(32_768);
        return;
    };
    for frame in data.chunks_mut(channels) {
        let sample = (state.render_next() as i32 + 32_768).clamp(0, u16::MAX as i32) as u16;
        frame.fill(sample);
    }
}
