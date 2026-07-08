// SPDX-License-Identifier: MPL-2.0

/// ASCII `OSR1`.
///
/// This is intentionally simple so non-Rust implementations can reject packets
/// quickly before parsing the rest of the header.
pub const OSR_MAGIC: [u8; 4] = *b"OSR1";

/// Initial protocol version.
pub const PROTOCOL_VERSION: u16 = 1;

/// Packet type used in the OSR wire protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
pub enum PacketKind {
    Hello = 1,
    Audio = 2,
    VolumeCommand = 3,
    TimeSync = 4,
}

impl PacketKind {
    pub fn from_u16(value: u16) -> Option<Self> {
        match value {
            1 => Some(Self::Hello),
            2 => Some(Self::Audio),
            3 => Some(Self::VolumeCommand),
            4 => Some(Self::TimeSync),
            _ => None,
        }
    }
}
