// SPDX-License-Identifier: MPL-2.0

use osr_core::{AudioFrameHeader, PacketKind, VolumeState, VolumeSynchronizer, UNITY_GAIN_PPM};
use osr_net::{IncomingPacket, TargetList, UdpEndpoint, UdpEndpointConfig};
use std::env;
use std::net::SocketAddr;
use std::thread;
use std::time::{Duration, Instant};

fn main() {
    if let Err(error) = run() {
        eprintln!("error: {error}");
        print_usage();
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let args: Vec<String> = env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("host") => run_host(&args[2..]),
        Some("child") => run_child(&args[2..]),
        Some("tone") => run_tone(&args[2..]),
        _ => Err("missing mode".to_owned()),
    }
}

fn run_host(args: &[String]) -> Result<(), String> {
    let targets = parse_targets(args)?;
    let volume = parse_flag(args, "--volume")?.unwrap_or_else(|| "1.0".to_owned());
    let gain_ppm = parse_volume_to_ppm(&volume)?;
    let bind: SocketAddr = parse_flag(args, "--bind")?
        .unwrap_or_else(|| "0.0.0.0:0".to_owned())
        .parse()
        .map_err(|_| "invalid --bind".to_owned())?;

    let mut endpoint = UdpEndpoint::bind(UdpEndpointConfig {
        bind_addr: bind,
        ..Default::default()
    })
    .map_err(|err| format!("failed to bind {bind}: {err}"))?;

    let mut sequence = 1u64;
    let epoch = 1u64;
    let stream_id = 1u32;

    println!(
        "OSR host sending volume={gain_ppm}ppm to {} target(s)",
        targets.len()
    );

    loop {
        let command = VolumeState::new(stream_id, epoch, sequence, gain_ppm);
        let report = endpoint.send_volume_command_to_targets(&targets, command);
        if report.all_sent() {
            println!("sent volume seq={sequence} gain_ppm={gain_ppm} targets={}", report.sent);
        } else {
            eprintln!(
                "sent volume seq={sequence} gain_ppm={gain_ppm} ok={} failed={:?}",
                report.sent, report.failed
            );
        }
        sequence = sequence.wrapping_add(1).max(1);
        thread::sleep(Duration::from_millis(500));
    }
}

fn run_child(args: &[String]) -> Result<(), String> {
    let bind: SocketAddr = parse_flag(args, "--bind")?
        .unwrap_or_else(|| "0.0.0.0:40124".to_owned())
        .parse()
        .map_err(|_| "invalid --bind".to_owned())?;

    let mut endpoint = UdpEndpoint::bind(UdpEndpointConfig {
        bind_addr: bind,
        ..Default::default()
    })
    .map_err(|err| format!("failed to bind {bind}: {err}"))?;

    let mut sync = VolumeSynchronizer::new(VolumeState::new(1, 0, 0, UNITY_GAIN_PPM));
    println!("OSR child listening on {bind}");

    loop {
        let Some(packet) = endpoint
            .recv()
            .map_err(|err| format!("failed to receive packet: {err}"))?
        else {
            continue;
        };

        match packet {
            IncomingPacket::VolumeCommand { from, command, .. } => {
                let accepted = sync.apply_parent_command(command);
                let current = sync.current();
                println!(
                    "volume from={from} accepted={accepted} stream={} epoch={} seq={} gain_ppm={} muted={}",
                    current.stream_id, current.epoch, current.sequence, current.gain_ppm, current.muted
                );
            }
            IncomingPacket::Audio { from, frame, .. } => {
                println!(
                    "audio from={from} frame_seq={} payload={} codec={:?}",
                    frame.header.frame_sequence,
                    frame.payload.len(),
                    frame.header.codec
                );
            }
            IncomingPacket::Other { from, kind, .. } => {
                eprintln!("ignored packet from={from} kind={kind:?}");
            }
        }
    }
}

fn run_tone(args: &[String]) -> Result<(), String> {
    let targets = parse_targets(args)?;
    let bind: SocketAddr = parse_flag(args, "--bind")?
        .unwrap_or_else(|| "0.0.0.0:0".to_owned())
        .parse()
        .map_err(|_| "invalid --bind".to_owned())?;

    let mut endpoint = UdpEndpoint::bind(UdpEndpointConfig {
        bind_addr: bind,
        ..Default::default()
    })
    .map_err(|err| format!("failed to bind {bind}: {err}"))?;

    let sample_rate = 48_000u32;
    let frame_duration_us = 10_000u32;
    let samples_per_frame = (sample_rate / 100) as usize;
    let mut frame_sequence = 1u64;
    let stream_id = 1u32;
    let started = Instant::now();

    println!("OSR tone sender targets={}", targets.len());
    loop {
        let mut payload = Vec::with_capacity(samples_per_frame * 2);
        for i in 0..samples_per_frame {
            let absolute_sample = ((frame_sequence - 1) as usize * samples_per_frame) + i;
            let phase = absolute_sample as f32 * 440.0 * std::f32::consts::TAU / sample_rate as f32;
            let sample = (phase.sin() * i16::MAX as f32 * 0.15) as i16;
            payload.extend_from_slice(&sample.to_le_bytes());
        }

        let header = AudioFrameHeader::pcm_s16le(
            stream_id,
            started.elapsed().as_micros() as u64,
            frame_sequence,
            sample_rate,
            1,
            frame_duration_us,
            payload.len() as u32,
        );
        let report = endpoint.send_audio_to_targets(&targets, header, &payload);
        if !report.all_sent() {
            eprintln!(
                "tone frame={} ok={} failed={:?}",
                frame_sequence, report.sent, report.failed
            );
        }
        frame_sequence = frame_sequence.wrapping_add(1).max(1);
        thread::sleep(Duration::from_millis(10));
    }
}

fn parse_targets(args: &[String]) -> Result<TargetList, String> {
    let values = parse_flags(args, "--target")?;
    if values.is_empty() {
        return Err("missing --target".to_owned());
    }
    TargetList::parse(&values.join(",")).map_err(|error| format!("invalid targets: {error:?}"))
}

fn parse_flag(args: &[String], name: &str) -> Result<Option<String>, String> {
    Ok(parse_flags(args, name)?.into_iter().next())
}

fn parse_flags(args: &[String], name: &str) -> Result<Vec<String>, String> {
    let mut values = Vec::new();
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        if arg == name {
            values.push(
                iter.next()
                    .cloned()
                    .ok_or_else(|| format!("missing value for {name}"))?,
            );
        }
    }
    Ok(values)
}

fn parse_volume_to_ppm(input: &str) -> Result<u32, String> {
    let value: f64 = input
        .parse()
        .map_err(|_| "--volume must be a number between 0.0 and 2.0".to_owned())?;
    if !(0.0..=2.0).contains(&value) {
        return Err("--volume must be between 0.0 and 2.0".to_owned());
    }
    Ok((value * 1_000_000.0).round() as u32)
}

fn print_usage() {
    eprintln!(
        "usage:\n  osr-cli child --bind 0.0.0.0:40124\n  osr-cli host --target 127.0.0.1:40124 --target 127.0.0.1:40125 --volume 0.5\n  osr-cli tone --target 127.0.0.1:40124,127.0.0.1:40125"
    );
}
