// SPDX-License-Identifier: MPL-2.0

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, Stream, StreamConfig};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

pub struct PcmAudioOutput {
    queue: Arc<Mutex<VecDeque<i16>>>,
    output_sample_rate: u32,
    _stream: Stream,
}

impl PcmAudioOutput {
    pub fn open_default() -> Result<Self, String> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or_else(|| "no default output device".to_owned())?;
        let supported_config = device
            .default_output_config()
            .map_err(|err| format!("failed to read default output config: {err}"))?;
        let sample_format = supported_config.sample_format();
        let config: StreamConfig = supported_config.into();
        let channels = config.channels.max(1) as usize;
        let queue = Arc::new(Mutex::new(VecDeque::<i16>::with_capacity(48_000)));
        let queue_for_callback = Arc::clone(&queue);
        let err_fn = |err| eprintln!("desktop audio output error: {err}");

        let stream = match sample_format {
            SampleFormat::F32 => device
                .build_output_stream(
                    &config,
                    move |data: &mut [f32], _| {
                        write_output_f32(data, channels, &queue_for_callback)
                    },
                    err_fn,
                    None,
                )
                .map_err(|err| format!("failed to build f32 output stream: {err}"))?,
            SampleFormat::I16 => device
                .build_output_stream(
                    &config,
                    move |data: &mut [i16], _| {
                        write_output_i16(data, channels, &queue_for_callback)
                    },
                    err_fn,
                    None,
                )
                .map_err(|err| format!("failed to build i16 output stream: {err}"))?,
            SampleFormat::U16 => device
                .build_output_stream(
                    &config,
                    move |data: &mut [u16], _| {
                        write_output_u16(data, channels, &queue_for_callback)
                    },
                    err_fn,
                    None,
                )
                .map_err(|err| format!("failed to build u16 output stream: {err}"))?,
            other => return Err(format!("unsupported output sample format: {other:?}")),
        };

        stream
            .play()
            .map_err(|err| format!("failed to start output stream: {err}"))?;

        Ok(Self {
            queue,
            output_sample_rate: config.sample_rate.0,
            _stream: stream,
        })
    }

    pub fn push_pcm_s16le(&self, data: &[u8], input_sample_rate: u32, input_channels: u8) {
        if input_sample_rate == 0 || !(input_channels == 1 || input_channels == 2) {
            return;
        }

        let channels = input_channels as usize;
        let samples: Vec<i16> = data
            .chunks_exact(2)
            .map(|chunk| i16::from_le_bytes([chunk[0], chunk[1]]))
            .collect();
        let mono: Vec<i16> = samples
            .chunks_exact(channels)
            .map(|frame| {
                if channels == 1 {
                    frame[0]
                } else {
                    ((frame[0] as i32 + frame[1] as i32) / 2) as i16
                }
            })
            .collect();
        if mono.is_empty() {
            return;
        }

        let output_frames = ((mono.len() as u64 * self.output_sample_rate as u64)
            / input_sample_rate as u64)
            .max(1) as usize;
        let mut queue = match self.queue.lock() {
            Ok(value) => value,
            Err(_) => return,
        };

        for output_index in 0..output_frames {
            let source_index = ((output_index as u64 * input_sample_rate as u64)
                / self.output_sample_rate as u64) as usize;
            queue.push_back(mono[source_index.min(mono.len() - 1)]);
        }

        let max_samples = 48_000usize;
        while queue.len() > max_samples {
            queue.pop_front();
        }
    }
}

fn pop_sample(queue: &Arc<Mutex<VecDeque<i16>>>) -> i16 {
    match queue.lock() {
        Ok(mut queue) => queue.pop_front().unwrap_or(0),
        Err(_) => 0,
    }
}

fn write_output_f32(data: &mut [f32], channels: usize, queue: &Arc<Mutex<VecDeque<i16>>>) {
    for frame in data.chunks_mut(channels) {
        let sample = pop_sample(queue) as f32 / i16::MAX as f32;
        for output in frame {
            *output = sample;
        }
    }
}

fn write_output_i16(data: &mut [i16], channels: usize, queue: &Arc<Mutex<VecDeque<i16>>>) {
    for frame in data.chunks_mut(channels) {
        let sample = pop_sample(queue);
        for output in frame {
            *output = sample;
        }
    }
}

fn write_output_u16(data: &mut [u16], channels: usize, queue: &Arc<Mutex<VecDeque<i16>>>) {
    for frame in data.chunks_mut(channels) {
        let sample = (pop_sample(queue) as i32 + 32_768).clamp(0, u16::MAX as i32) as u16;
        for output in frame {
            *output = sample;
        }
    }
}
