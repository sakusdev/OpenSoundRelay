// SPDX-License-Identifier: MPL-2.0

//! LAN discovery for connectable OpenSoundRelay devices.
//!
//! Discovery uses a small OSR `Hello` packet over IPv4 UDP broadcast. A
//! scanner sends a probe to port 40125 and receivers reply directly to the
//! scanner with their display name, audio port, role, and capability flags.

use osr_core::{decode_packet, encode_packet, PacketKind};
use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::process;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub const DISCOVERY_PORT: u16 = 40_125;
pub const MAX_DEVICE_NAME_BYTES: usize = 96;
const DISCOVERY_WIRE_VERSION: u8 = 1;
const DISCOVERY_HEADER_LEN: usize = 20;
const MESSAGE_PROBE: u8 = 1;
const MESSAGE_ANNOUNCEMENT: u8 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum DiscoveryRole {
    Idle = 0,
    Sender = 1,
    Receiver = 2,
    Duplex = 3,
}

impl DiscoveryRole {
    fn from_u8(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::Idle),
            1 => Some(Self::Sender),
            2 => Some(Self::Receiver),
            3 => Some(Self::Duplex),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct DiscoveryCapabilities(pub u32);

impl DiscoveryCapabilities {
    pub const AUDIO_RECEIVE: Self = Self(1 << 0);
    pub const AUDIO_SEND: Self = Self(1 << 1);
    pub const STREAM_VOLUME: Self = Self(1 << 2);
    pub const NATIVE_VOLUME: Self = Self(1 << 3);
    pub const ADAPTIVE_LATENCY: Self = Self(1 << 4);
    pub const TONE_CONTROLS: Self = Self(1 << 5);

    pub const fn union(self, other: Self) -> Self {
        Self(self.0 | other.0)
    }

    pub const fn contains(self, other: Self) -> bool {
        self.0 & other.0 == other.0
    }
}

#[derive(Debug, Clone)]
pub struct DiscoveryConfig {
    pub device_name: String,
    pub role: DiscoveryRole,
    pub audio_port: u16,
    pub capabilities: DiscoveryCapabilities,
}

impl DiscoveryConfig {
    pub fn receiver(device_name: impl Into<String>, audio_port: u16) -> Self {
        Self {
            device_name: device_name.into(),
            role: DiscoveryRole::Receiver,
            audio_port,
            capabilities: DiscoveryCapabilities::AUDIO_RECEIVE
                .union(DiscoveryCapabilities::STREAM_VOLUME)
                .union(DiscoveryCapabilities::NATIVE_VOLUME)
                .union(DiscoveryCapabilities::ADAPTIVE_LATENCY)
                .union(DiscoveryCapabilities::TONE_CONTROLS),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    pub name: String,
    pub source_addr: SocketAddr,
    pub audio_addr: SocketAddr,
    pub role: DiscoveryRole,
    pub capabilities: DiscoveryCapabilities,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DiscoveryMessage {
    message_type: u8,
    role: DiscoveryRole,
    capabilities: DiscoveryCapabilities,
    audio_port: u16,
    nonce: u64,
    name: String,
}

impl DiscoveryMessage {
    fn probe(name: &str, nonce: u64) -> Self {
        Self {
            message_type: MESSAGE_PROBE,
            role: DiscoveryRole::Idle,
            capabilities: DiscoveryCapabilities::default(),
            audio_port: 0,
            nonce,
            name: name.to_owned(),
        }
    }

    fn announcement(config: &DiscoveryConfig, nonce: u64) -> Self {
        Self {
            message_type: MESSAGE_ANNOUNCEMENT,
            role: config.role,
            capabilities: config.capabilities,
            audio_port: config.audio_port,
            nonce,
            name: config.device_name.clone(),
        }
    }

    fn encode(&self) -> Vec<u8> {
        let name = truncate_utf8(&self.name, MAX_DEVICE_NAME_BYTES);
        let name_bytes = name.as_bytes();
        let mut out = vec![0u8; DISCOVERY_HEADER_LEN + name_bytes.len()];
        out[0] = DISCOVERY_WIRE_VERSION;
        out[1] = self.message_type;
        out[2] = self.role as u8;
        out[3] = 0;
        out[4..8].copy_from_slice(&self.capabilities.0.to_be_bytes());
        out[8..10].copy_from_slice(&self.audio_port.to_be_bytes());
        out[10..12].copy_from_slice(&(name_bytes.len() as u16).to_be_bytes());
        out[12..20].copy_from_slice(&self.nonce.to_be_bytes());
        out[20..].copy_from_slice(name_bytes);
        out
    }

    fn decode(input: &[u8]) -> Option<Self> {
        if input.len() < DISCOVERY_HEADER_LEN || input[0] != DISCOVERY_WIRE_VERSION {
            return None;
        }
        let message_type = input[1];
        if message_type != MESSAGE_PROBE && message_type != MESSAGE_ANNOUNCEMENT {
            return None;
        }
        let role = DiscoveryRole::from_u8(input[2])?;
        let capabilities = DiscoveryCapabilities(u32::from_be_bytes([
            input[4], input[5], input[6], input[7],
        ]));
        let audio_port = u16::from_be_bytes([input[8], input[9]]);
        let name_len = u16::from_be_bytes([input[10], input[11]]) as usize;
        if name_len > MAX_DEVICE_NAME_BYTES || input.len() != DISCOVERY_HEADER_LEN + name_len {
            return None;
        }
        let nonce = u64::from_be_bytes([
            input[12], input[13], input[14], input[15], input[16], input[17], input[18],
            input[19],
        ]);
        let name = std::str::from_utf8(&input[20..]).ok()?.trim().to_owned();
        Some(Self {
            message_type,
            role,
            capabilities,
            audio_port,
            nonce,
            name,
        })
    }
}

pub struct DiscoveryResponder {
    socket: UdpSocket,
    config: DiscoveryConfig,
    packet_sequence: u64,
    buffer: [u8; 512],
}

impl DiscoveryResponder {
    pub fn bind(config: DiscoveryConfig) -> io::Result<Self> {
        let socket = UdpSocket::bind(SocketAddr::from(([0, 0, 0, 0], DISCOVERY_PORT)))?;
        socket.set_broadcast(true)?;
        socket.set_read_timeout(Some(Duration::from_millis(200)))?;
        Ok(Self {
            socket,
            config,
            packet_sequence: 1,
            buffer: [0u8; 512],
        })
    }

    /// Poll once and reply to a valid discovery probe.
    ///
    /// `Ok(None)` is returned for a timeout or an unrelated/malformed packet.
    pub fn poll(&mut self) -> io::Result<Option<SocketAddr>> {
        let (len, from) = match self.socket.recv_from(&mut self.buffer) {
            Ok(value) => value,
            Err(error)
                if error.kind() == io::ErrorKind::WouldBlock
                    || error.kind() == io::ErrorKind::TimedOut =>
            {
                return Ok(None);
            }
            Err(error) => return Err(error),
        };

        let packet = match decode_packet(&self.buffer[..len]) {
            Ok(value) if value.header.kind == PacketKind::Hello => value,
            _ => return Ok(None),
        };
        let probe = match DiscoveryMessage::decode(packet.payload) {
            Some(value) if value.message_type == MESSAGE_PROBE => value,
            _ => return Ok(None),
        };

        let payload = DiscoveryMessage::announcement(&self.config, probe.nonce).encode();
        let encoded = encode_packet(PacketKind::Hello, self.packet_sequence, 0, &payload);
        self.packet_sequence = self.packet_sequence.wrapping_add(1).max(1);
        self.socket.send_to(&encoded, from)?;
        Ok(Some(from))
    }
}

/// Scan the local IPv4 broadcast domain for OSR devices.
pub fn discover_devices(
    scanner_name: &str,
    timeout: Duration,
) -> io::Result<Vec<DiscoveredDevice>> {
    let socket = UdpSocket::bind(SocketAddr::from(([0, 0, 0, 0], 0)))?;
    socket.set_broadcast(true)?;
    socket.set_read_timeout(Some(Duration::from_millis(75)))?;

    let nonce = discovery_nonce();
    let probe_payload = DiscoveryMessage::probe(scanner_name, nonce).encode();
    let probe = encode_packet(PacketKind::Hello, 1, 0, &probe_payload);
    let broadcast = SocketAddr::from(([255, 255, 255, 255], DISCOVERY_PORT));

    let started = Instant::now();
    let mut last_probe = started.checked_sub(Duration::from_secs(1)).unwrap_or(started);
    let mut buffer = [0u8; 512];
    let mut devices = HashMap::<SocketAddr, DiscoveredDevice>::new();

    while started.elapsed() < timeout {
        if last_probe.elapsed() >= Duration::from_millis(250) {
            // Some Wi-Fi stacks drop the first broadcast while waking the radio,
            // so repeat the small probe during the scan window.
            let _ = socket.send_to(&probe, broadcast);
            last_probe = Instant::now();
        }

        let (len, from) = match socket.recv_from(&mut buffer) {
            Ok(value) => value,
            Err(error)
                if error.kind() == io::ErrorKind::WouldBlock
                    || error.kind() == io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(error) => return Err(error),
        };

        let packet = match decode_packet(&buffer[..len]) {
            Ok(value) if value.header.kind == PacketKind::Hello => value,
            _ => continue,
        };
        let announcement = match DiscoveryMessage::decode(packet.payload) {
            Some(value)
                if value.message_type == MESSAGE_ANNOUNCEMENT
                    && value.nonce == nonce
                    && value.audio_port != 0 =>
            {
                value
            }
            _ => continue,
        };
        let audio_addr = SocketAddr::new(from.ip(), announcement.audio_port);
        let fallback_name = match from.ip() {
            IpAddr::V4(ip) if ip == Ipv4Addr::LOCALHOST => "This device".to_owned(),
            ip => ip.to_string(),
        };
        devices.insert(
            audio_addr,
            DiscoveredDevice {
                name: if announcement.name.is_empty() {
                    fallback_name
                } else {
                    announcement.name
                },
                source_addr: from,
                audio_addr,
                role: announcement.role,
                capabilities: announcement.capabilities,
            },
        );
    }

    let mut devices: Vec<_> = devices.into_values().collect();
    devices.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    Ok(devices)
}

fn truncate_utf8(value: &str, max_bytes: usize) -> &str {
    if value.len() <= max_bytes {
        return value;
    }
    let mut end = max_bytes;
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    &value[..end]
}

fn discovery_nonce() -> u64 {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    now ^ ((process::id() as u64) << 32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discovery_message_round_trip() {
        let config = DiscoveryConfig::receiver("Living room", 40_124);
        let message = DiscoveryMessage::announcement(&config, 42);
        assert_eq!(DiscoveryMessage::decode(&message.encode()), Some(message));
    }

    #[test]
    fn truncates_on_a_utf8_boundary() {
        let value = "あいうえお";
        assert_eq!(truncate_utf8(value, 7), "あい");
    }
}
