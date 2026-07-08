// SPDX-License-Identifier: MPL-2.0

//! Cross-platform UDP helpers for OpenSoundRelay.
//!
//! This crate intentionally uses only `std::net` so it can compile on Linux,
//! Windows, and macOS without platform-specific networking dependencies.

use osr_core::{
    decode_audio_frame, decode_packet, encode_audio_frame, encode_packet, AudioFrame,
    AudioFrameHeader, PacketKind, VolumeState,
};
use std::io;
use std::net::{SocketAddr, ToSocketAddrs, UdpSocket};
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

    pub fn send_volume_command<A: ToSocketAddrs>(
        &mut self,
        target: A,
        command: VolumeState,
    ) -> io::Result<usize> {
        self.send_packet(target, PacketKind::VolumeCommand, &command.encode())
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
            kind => Ok(Some(IncomingPacket::Other {
                from,
                kind,
                packet_sequence: packet.header.sequence,
            })),
        }
    }
}

#[derive(Debug, Clone)]
pub struct StreamStats {
    pub received_audio_frames: u64,
    pub received_volume_commands: u64,
    pub dropped_audio_frames: u64,
    pub last_audio_sequence: u64,
}

impl StreamStats {
    pub fn observe(&mut self, packet: &IncomingPacket) {
        match packet {
            IncomingPacket::Audio { frame, .. } => {
                self.received_audio_frames += 1;
                if self.last_audio_sequence != 0 && frame.header.frame_sequence <= self.last_audio_sequence {
                    self.dropped_audio_frames += 1;
                } else {
                    self.last_audio_sequence = frame.header.frame_sequence;
                }
            }
            IncomingPacket::VolumeCommand { .. } => {
                self.received_volume_commands += 1;
            }
            IncomingPacket::Other { .. } => {}
        }
    }
}
