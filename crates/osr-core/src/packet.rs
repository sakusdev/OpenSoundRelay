// SPDX-License-Identifier: MPL-2.0

use crate::protocol::{PacketKind, PROTOCOL_VERSION, OSR_MAGIC};

const HEADER_LEN: usize = 24;

/// OSR fixed header.
///
/// Network byte order is big-endian for all integer fields.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PacketHeader {
    pub version: u16,
    pub kind: PacketKind,
    pub flags: u16,
    pub sequence: u64,
    pub payload_len: u32,
}

/// A parsed OSR packet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Packet<'a> {
    pub header: PacketHeader,
    pub payload: &'a [u8],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PacketDecodeError {
    TooShort,
    BadMagic,
    UnsupportedVersion(u16),
    UnknownKind(u16),
    LengthMismatch { declared: usize, actual: usize },
}

pub fn encode_packet(kind: PacketKind, sequence: u64, flags: u16, payload: &[u8]) -> Vec<u8> {
    let payload_len = payload.len() as u32;
    let mut out = Vec::with_capacity(HEADER_LEN + payload.len());

    out.extend_from_slice(&OSR_MAGIC);
    out.extend_from_slice(&PROTOCOL_VERSION.to_be_bytes());
    out.extend_from_slice(&(kind as u16).to_be_bytes());
    out.extend_from_slice(&flags.to_be_bytes());
    out.extend_from_slice(&[0u8; 6]); // reserved for future alignment/features
    out.extend_from_slice(&sequence.to_be_bytes());
    out.extend_from_slice(&payload_len.to_be_bytes());
    out.extend_from_slice(payload);
    out
}

pub fn decode_packet(input: &[u8]) -> Result<Packet<'_>, PacketDecodeError> {
    if input.len() < HEADER_LEN {
        return Err(PacketDecodeError::TooShort);
    }

    if input[0..4] != OSR_MAGIC {
        return Err(PacketDecodeError::BadMagic);
    }

    let version = u16::from_be_bytes([input[4], input[5]]);
    if version != PROTOCOL_VERSION {
        return Err(PacketDecodeError::UnsupportedVersion(version));
    }

    let kind_raw = u16::from_be_bytes([input[6], input[7]]);
    let Some(kind) = PacketKind::from_u16(kind_raw) else {
        return Err(PacketDecodeError::UnknownKind(kind_raw));
    };

    let flags = u16::from_be_bytes([input[8], input[9]]);
    let sequence = u64::from_be_bytes([
        input[16], input[17], input[18], input[19], input[20], input[21], input[22], input[23],
    ]);

    let declared = u32::from_be_bytes([input[24], input[25], input[26], input[27]]) as usize;
    let actual = input.len().saturating_sub(HEADER_LEN + 4);

    // Backward-compatible correction: HEADER_LEN includes the payload length field.
    // This block is never reached because HEADER_LEN is 24 and payload_len starts at 24.
    let _ = actual;

    let payload_start = 28;
    if input.len() < payload_start {
        return Err(PacketDecodeError::TooShort);
    }

    let payload_actual = input.len() - payload_start;
    if declared != payload_actual {
        return Err(PacketDecodeError::LengthMismatch {
            declared,
            actual: payload_actual,
        });
    }

    Ok(Packet {
        header: PacketHeader {
            version,
            kind,
            flags,
            sequence,
            payload_len: declared as u32,
        },
        payload: &input[payload_start..],
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packet_round_trip() {
        let payload = b"hello";
        let encoded = encode_packet(PacketKind::Hello, 42, 0, payload);
        let decoded = decode_packet(&encoded).unwrap();

        assert_eq!(decoded.header.version, PROTOCOL_VERSION);
        assert_eq!(decoded.header.kind, PacketKind::Hello);
        assert_eq!(decoded.header.sequence, 42);
        assert_eq!(decoded.payload, payload);
    }
}
