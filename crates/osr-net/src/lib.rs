// SPDX-License-Identifier: MPL-2.0

//! Cross-platform UDP helpers for OpenSoundRelay.
//!
//! This crate intentionally uses only `std::net` so it can compile on Linux,
//! Windows, macOS, and Android without platform-specific networking dependencies.

pub mod discovery;

pub use discovery::{
    discover_devices, DiscoveredDevice, DiscoveryCapabilities, DiscoveryConfig, DiscoveryResponder,
    DiscoveryRole, DISCOVERY_PORT,
};

use osr_core::{
    decode_audio_frame, decode_packet, encode_audio_frame, encode_packet, AudioFrame,
    AudioFrameHeader, DeviceVolumeState, PacketKind, VolumeState,
};
use std::io;
use std::net::{SocketAddr, ToSocketAddrs, UdpSocket};
use std::str::FromStr;
use std::time::Duration;

pub const DEFAULT_OSR_PORT: u16 = 40124;
pub const MAX_UDP_PACKET_SIZE: usize = 1500;

#[derive(Debug, Clone)]
pub struct UdpEndpointConfig {
    pub bind_addr: SocketAddr,
    pub read_timeout: Option<Duration>,
    pub nonblocking: bool,
}

impl Default for UdpEndpointConfig {
    fn default() -> Self {
        Self {
            bind_addr: SocketAddr::from(([0, 0, 0, 0], DEFAULT_OSR_PORT)),
            read_timeout: Some(Duration::from_millis(250)),
            nonblocking: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerTarget {
    pub addr: SocketAddr,
    pub label: Option<String>,
}

impl PeerTarget {
    pub fn new(addr: SocketAddr) -> Self {
        Self { addr, label: None }
    }

    pub fn with_label(addr: SocketAddr, label: impl Into<String>) -> Self {
        Self {
            addr,
            label: Some(label.into()),
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct TargetList {
    targets: Vec<PeerTarget>,
}

impl TargetList {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn from_targets(targets: Vec<PeerTarget>) -> Self {
        let mut list = Self::new();
        for target in targets {
            list.add(target);
        }
        list
    }

    pub fn parse(input: &str) -> Result<Self, TargetParseError> {
        let mut list = Self::new();
        for raw in input.split([',', '\n', ';']) {
            let value = raw.trim();
            if value.is_empty() {
                continue;
            }
            let addr = SocketAddr::from_str(value)
                .map_err(|_| TargetParseError::InvalidAddress(value.to_owned()))?;
            list.add(PeerTarget::new(addr));
        }

        if list.is_empty() {
            return Err(TargetParseError::Empty);
        }
        Ok(list)
    }

    pub fn add(&mut self, target: PeerTarget) {
        if self
            .targets
            .iter()
            .any(|existing| existing.addr == target.addr)
        {
            return;
        }
        self.targets.push(target);
    }

    pub fn remove(&mut self, addr: SocketAddr) -> bool {
        let old_len = self.targets.len();
        self.targets.retain(|target| target.addr != addr);
        self.targets.len() != old_len
    }

    pub fn is_empty(&self) -> bool {
        self.targets.is_empty()
    }

    pub fn len(&self) -> usize {
        self.targets.len()
    }

    pub fn iter(&self) -> impl Iterator<Item = &PeerTarget> {
        self.targets.iter()
    }

    pub fn addresses(&self) -> impl Iterator<Item = SocketAddr> + '_ {
        self.targets.iter().map(|target| target.addr)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TargetParseError {
    Empty,
    InvalidAddress(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FanoutReport {
    pub attempted: usize,
    pub sent: usize,
    pub failed: Vec<(SocketAddr, String)>,
}

impl FanoutReport {
    pub fn all_sent(&self) -> bool {
        self.failed.is_empty() && self.sent == self.attempted
    }
}

#[derive(Debug, Clone)]
pub enum IncomingPacket {
    Audio {
        from: SocketAddr,
        packet_sequence: u64,
        frame: OwnedAudioFrame,
    },
    VolumeCommand {
        from: SocketAddr,
        packet_sequence: u64,
        command: VolumeState,
    },
    DeviceVolume {
        from: SocketAddr,
        packet_sequence: u64,
        command: DeviceVolumeState,
    },
    Other {
        from: SocketAddr,
        kind: PacketKind,
        packet_sequence: u64,
    },
}

#[derive(Debug, Clone)]
pub struct OwnedAudioFrame {
    pub header: AudioFrameHeader,
    pub payload: Vec<u8>,
}

impl<'a> From<AudioFrame<'a>> for OwnedAudioFrame {
    fn from(value: AudioFrame<'a>) -> Self {
        Self {
            header: value.header,
            payload: value.payload.to_vec(),
        }
    }
}

pub struct UdpEndpoint {
    socket: UdpSocket,
    buffer: [u8; MAX_UDP_PACKET_SIZE],
    packet_sequence: u64,
}

impl UdpEndpoint {
    pub fn bind(config: UdpEndpointConfig) -> io::Result<Self> {
        let socket = UdpSocket::bind(config.bind_addr)?;
        socket.set_nonblocking(config.nonblocking)?;
        socket.set_read_timeout(config.read_timeout)?;
        Ok(Self {
            socket,
            buffer: [0u8; MAX_UDP_PACKET_SIZE],
            packet_sequence: 1,
        })
    }

    pub fn local_addr(&self) -> io::Result<SocketAddr> {
        self.socket.local_addr()
    }

    pub fn send_audio<A: ToSocketAddrs>(
        &mut self,
        target: A,
        header: AudioFrameHeader,
        payload: &[u8],
    ) -> io::Result<usize> {
        let frame = encode_audio_frame(header, payload);
        self.send_packet(target, PacketKind::Audio, &frame)
    }

    pub fn send_audio_to_targets(
        &mut self,
        targets: &TargetList,
        header: AudioFrameHeader,
        payload: &[u8],
    ) -> FanoutReport {
        let frame = encode_audio_frame(header, payload);
        self.send_packet_to_targets(targets, PacketKind::Audio, &frame)
    }

    pub fn send_volume_command<A: ToSocketAddrs>(
        &mut self,
        target: A,
        command: VolumeState,
    ) -> io::Result<usize> {
        self.send_packet(target, PacketKind::VolumeCommand, &command.encode())
    }

    pub fn send_volume_command_to_targets(
        &mut self,
        targets: &TargetList,
        command: VolumeState,
    ) -> FanoutReport {
        self.send_packet_to_targets(targets, PacketKind::VolumeCommand, &command.encode())
    }

    pub fn send_device_volume<A: ToSocketAddrs>(
        &mut self,
        target: A,
        command: DeviceVolumeState,
    ) -> io::Result<usize> {
        self.send_packet(target, PacketKind::DeviceVolume, &command.encode())
    }

    pub fn send_device_volume_to_targets(
        &mut self,
        targets: &TargetList,
        command: DeviceVolumeState,
    ) -> FanoutReport {
        self.send_packet_to_targets(targets, PacketKind::DeviceVolume, &command.encode())
    }

    pub fn send_packet<A: ToSocketAddrs>(
        &mut self,
        target: A,
        kind: PacketKind,
        payload: &[u8],
    ) -> io::Result<usize> {
        let encoded = encode_packet(kind, self.packet_sequence, 0, payload);
        self.packet_sequence = self.packet_sequence.wrapping_add(1).max(1);
        self.socket.send_to(&encoded, target)
    }

    pub fn send_packet_to_targets(
        &mut self,
        targets: &TargetList,
        kind: PacketKind,
        payload: &[u8],
    ) -> FanoutReport {
        let encoded = encode_packet(kind, self.packet_sequence, 0, payload);
        self.packet_sequence = self.packet_sequence.wrapping_add(1).max(1);

        let mut report = FanoutReport {
            attempted: targets.len(),
            sent: 0,
            failed: Vec::new(),
        };

        for target in targets.iter() {
            match self.socket.send_to(&encoded, target.addr) {
                Ok(_) => report.sent += 1,
                Err(error) => report.failed.push((target.addr, error.to_string())),
            }
        }

        report
    }

    pub fn recv(&mut self) -> io::Result<Option<IncomingPacket>> {
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
            Ok(packet) => packet,
            Err(_) => return Ok(None),
        };

        match packet.header.kind {
            PacketKind::Audio => {
                let frame = match decode_audio_frame(packet.payload) {
                    Ok(frame) => frame,
                    Err(_) => return Ok(None),
                };
                Ok(Some(IncomingPacket::Audio {
                    from,
                    packet_sequence: packet.header.sequence,
                    frame: frame.into(),
                }))
            }
            PacketKind::VolumeCommand => {
                let Some(command) = VolumeState::decode(packet.payload) else {
                    return Ok(None);
                };
                Ok(Some(IncomingPacket::VolumeCommand {
                    from,
                    packet_sequence: packet.header.sequence,
                    command,
                }))
            }
            PacketKind::DeviceVolume => {
                let Some(command) = DeviceVolumeState::decode(packet.payload) else {
                    return Ok(None);
                };
                Ok(Some(IncomingPacket::DeviceVolume {
                    from,
                    packet_sequence: packet.header.sequence,
                    command,
                }))
            }
            kind => Ok(Some(IncomingPacket::Other {
                from,
                kind,
                packet_sequence: packet.header.sequence,
            })),
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct StreamStats {
    pub received_audio_frames: u64,
    pub received_volume_commands: u64,
    pub received_device_volume_commands: u64,
    pub dropped_audio_frames: u64,
    pub last_audio_sequence: u64,
}

impl StreamStats {
    pub fn observe(&mut self, packet: &IncomingPacket) {
        match packet {
            IncomingPacket::Audio { frame, .. } => {
                self.received_audio_frames += 1;
                if self.last_audio_sequence != 0
                    && frame.header.frame_sequence <= self.last_audio_sequence
                {
                    self.dropped_audio_frames += 1;
                } else {
                    if self.last_audio_sequence != 0
                        && frame.header.frame_sequence > self.last_audio_sequence + 1
                    {
                        self.dropped_audio_frames +=
                            frame.header.frame_sequence - self.last_audio_sequence - 1;
                    }
                    self.last_audio_sequence = frame.header.frame_sequence;
                }
            }
            IncomingPacket::VolumeCommand { .. } => {
                self.received_volume_commands += 1;
            }
            IncomingPacket::DeviceVolume { .. } => {
                self.received_device_volume_commands += 1;
            }
            IncomingPacket::Other { .. } => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_multiple_targets() {
        let targets =
            TargetList::parse("127.0.0.1:40124, 127.0.0.1:40125\n127.0.0.1:40126").unwrap();
        assert_eq!(targets.len(), 3);
    }

    #[test]
    fn deduplicates_targets() {
        let targets = TargetList::parse("127.0.0.1:40124,127.0.0.1:40124").unwrap();
        assert_eq!(targets.len(), 1);
    }

    #[test]
    fn rejects_empty_target_list() {
        assert!(matches!(
            TargetList::parse("\n, ;"),
            Err(TargetParseError::Empty)
        ));
    }
}
