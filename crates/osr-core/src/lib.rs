// SPDX-License-Identifier: MPL-2.0

//! Portable OpenSoundRelay protocol core.
//!
//! This crate intentionally avoids platform audio APIs. Android, iOS, desktop,
//! and Web clients should all use the same wire format and synchronization
//! rules, then connect them to native audio I/O on each platform.

pub mod audio;
pub mod packet;
pub mod protocol;
pub mod volume;

pub use audio::{
    decode_audio_frame, encode_audio_frame, AudioCodec, AudioFrame, AudioFrameDecodeError,
    AudioFrameHeader, SampleFormat,
};
pub use packet::{decode_packet, encode_packet, Packet, PacketDecodeError, PacketHeader};
pub use protocol::{PacketKind, PROTOCOL_VERSION, OSR_MAGIC};
pub use volume::{
    apply_gain_i16, GainPpm, VolumeCommand, VolumeState, VolumeSynchronizer, MAX_GAIN_PPM,
    UNITY_GAIN_PPM,
};
