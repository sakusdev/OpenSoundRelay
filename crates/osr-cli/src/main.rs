// SPDX-License-Identifier: MPL-2.0

use osr_core::{
    decode_packet, encode_packet, PacketKind, VolumeState, VolumeSynchronizer, UNITY_GAIN_PPM,
};
use std::env;
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::Duration;

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
        _ => Err("missing mode".to_owned()),
    }
}

fn run_host(args: &[String]) -> Result<(), String> {
    let target: SocketAddr = parse_flag(args, "--target")?
        .ok_or_else(|| "missing --target".to_owned())?
        .parse()
        .map_err(|_| "invalid --target".to_owned())?;

    let volume = parse_flag(args, "--volume")?
        .unwrap_or_else(|| "1.0".to_owned());
    let gain_ppm = parse_volume_to_ppm(&volume)?;

    let bind = parse_flag(args, "--bind")?.unwrap_or_else(|| "0.0.0.0:0".to_owned());
    let socket = UdpSocket::bind(&bind).map_err(|err| format!("failed to bind {bind}: {err}"))?;

    let mut sequence = 1u64;
    let epoch = 1u64;
    let stream_id = 1u32;

    println!("OSR host sending volume={gain_ppm}ppm to {target}");

    loop {
        let command = VolumeState::new(stream_id, epoch, sequence, gain_ppm);
        let payload = command.encode();
        let packet = encode_packet(PacketKind::VolumeCommand, sequence, 0, &payload);
        socket
            .send_to(&packet, target)
            .map_err(|err| format!("failed to send packet: {err}"))?;

        println!("sent volume command seq={sequence} gain_ppm={gain_ppm}");
        sequence = sequence.wrapping_add(1);
        thread::sleep(Duration::from_millis(500));
    }
}

fn run_child(args: &[String]) -> Result<(), String> {
    let bind = parse_flag(args, "--bind")?.unwrap_or_else(|| "0.0.0.0:40124".to_owned());
    let socket = UdpSocket::bind(&bind).map_err(|err| format!("failed to bind {bind}: {err}"))?;
    let mut sync = VolumeSynchronizer::new(VolumeState::new(1, 0, 0, UNITY_GAIN_PPM));
    let mut buf = [0u8; 1500];

    println!("OSR child listening on {bind}");

    loop {
        let (len, from) = socket
            .recv_from(&mut buf)
            .map_err(|err| format!("failed to receive packet: {err}"))?;
        let packet = match decode_packet(&buf[..len]) {
            Ok(packet) => packet,
            Err(error) => {
                eprintln!("ignored malformed packet from {from}: {error:?}");
                continue;
            }
        };

        if packet.header.kind != PacketKind::VolumeCommand {
            eprintln!("ignored non-volume packet from {from}: {:?}", packet.header.kind);
            continue;
        }

        let Some(command) = VolumeState::decode(packet.payload) else {
            eprintln!("ignored bad volume command from {from}");
            continue;
        };

        let accepted = sync.apply_parent_command(command);
        let current = sync.current();
        println!(
            "from={from} accepted={accepted} stream={} epoch={} seq={} gain_ppm={} muted={}",
            current.stream_id, current.epoch, current.sequence, current.gain_ppm, current.muted
        );
    }
}

fn parse_flag(args: &[String], name: &str) -> Result<Option<String>, String> {
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        if arg == name {
            return iter
                .next()
                .cloned()
                .map(Some)
                .ok_or_else(|| format!("missing value for {name}"));
        }
    }
    Ok(None)
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
        "usage:\n  osr-cli child --bind 0.0.0.0:40124\n  osr-cli host --target 127.0.0.1:40124 --volume 0.5"
    );
}
