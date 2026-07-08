// SPDX-License-Identifier: MPL-2.0

/// Audio codec identifier used in OSR audio frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum AudioCodec {
    Pcm = 1,
    Opus = 2,
}

impl AudioCodec {
    pub fn from_u8(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::Pcm),
            2 => Some(Self::Opus),
            _ => None,
        }
    }
}

/// PCM/sample payload format.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum SampleFormat {
    S16Le = 1,
    F32Le = 2,
    Encoded = 255,
}

impl SampleFormat {
    pub fn from_u8(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::S16Le),
            2 => Some(Self::F32Le),
            255 => Some(Self::Encoded),
            _ => None,
        }
    }
}

/// Fixed audio frame metadata.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AudioFrameHeader {
    pub stream_id: u32,
    pub media_time_us: u64,
    pub frame_sequence: u64,
    pub sample_rate_hz: u32,
    pub channels: u8,
    pub codec: AudioCodec,
    pub sample_format: SampleFormat,
    pub frame_duration_us: u32,
    pub payload_len: u32,
}

/// Borrowed decoded audio frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AudioFrame<'a> {
    pub header: AudioFrameHeader,
    pub payload: &'a [u8],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AudioFrameDecodeError {
    TooShort,
    UnknownCodec(u8),
    UnknownSampleFormat(u8),
    LengthMismatch { declared: usize, actual: usize },
}

impl AudioFrameHeader {
    pub const WIRE_LEN: usize = 36;

    pub fn pcm_s16le(
        stream_id: u32,
        media_time_us: u64,
        frame_sequence: u64,
        sample_rate_hz: u32,
        channels: u8,
        frame_duration_us: u32,
        payload_len: u32,
    ) -> Self {
        Self {
            stream_id,
            media_time_us,
            frame_sequence,
            sample_rate_hz,
            channels,
            codec: AudioCodec::Pcm,
            sample_format: SampleFormat::S16Le,
            frame_duration_us,
            payload_len,
        }
    }

    pub fn opus(
        stream_id: u32,
        media_time_us: u64,
        frame_sequence: u64,
        sample_rate_hz: u32,
        channels: u8,
        frame_duration_us: u32,
        payload_len: u32,
    ) -> Self {
        Self {
            stream_id,
            media_time_us,
            frame_sequence,
            sample_rate_hz,
            channels,
            codec: AudioCodec::Opus,
            sample_format: SampleFormat::Encoded,
            frame_duration_us,
            payload_len,
        }
    }

    pub fn encode(self) -> [u8; Self::WIRE_LEN] {
        let mut out = [0u8; Self::WIRE_LEN];
        out[0..4].copy_from_slice(&self.stream_id.to_be_bytes());
        out[4..12].copy_from_slice(&self.media_time_us.to_be_bytes());
        out[12..20].copy_from_slice(&self.frame_sequence.to_be_bytes());
        out[20..24].copy_from_slice(&self.sample_rate_hz.to_be_bytes());
        out[24] = self.channels;
        out[25] = self.codec as u8;
        out[26] = self.sample_format as u8;
        // byte 27 is reserved and must be zero in v1
        out[28..32].copy_from_slice(&self.frame_duration_us.to_be_bytes());
        out[32..36].copy_from_slice(&self.payload_len.to_be_bytes());
        out
    }

    pub fn decode(input: &[u8]) -> Result<Self, AudioFrameDecodeError> {
        if input.len() < Self::WIRE_LEN {
            return Err(AudioFrameDecodeError::TooShort);
        }

        let codec_raw = input[25];
        let Some(codec) = AudioCodec::from_u8(codec_raw) else {
            return Err(AudioFrameDecodeError::UnknownCodec(codec_raw));
        };

        let format_raw = input[26];
        let Some(sample_format) = SampleFormat::from_u8(format_raw) else {
            return Err(AudioFrameDecodeError::UnknownSampleFormat(format_raw));
        };

        Ok(Self {
            stream_id: u32::from_be_bytes([input[0], input[1], input[2], input[3]]),
            media_time_us: u64::from_be_bytes([
                input[4], input[5], input[6], input[7], input[8], input[9], input[10], input[11],
            ]),
            frame_sequence: u64::from_be_bytes([
                input[12], input[13], input[14], input[15], input[16], input[17], input[18],
                input[19],
            ]),
            sample_rate_hz: u32::from_be_bytes([input[20], input[21], input[22], input[23]]),
            channels: input[24],
            codec,
            sample_format,
            frame_duration_us: u32::from_be_bytes([input[28], input[29], input[30], input[31]]),
            payload_len: u32::from_be_bytes([input[32], input[33], input[34], input[35]]),
        })
    }
}

pub fn encode_audio_frame(header: AudioFrameHeader, payload: &[u8]) -> Vec<u8> {
    let header = AudioFrameHeader {
        payload_len: payload.len() as u32,
        ..header
    };

    let mut out = Vec::with_capacity(AudioFrameHeader::WIRE_LEN + payload.len());
    out.extend_from_slice(&header.encode());
    out.extend_from_slice(payload);
    out
}

pub fn decode_audio_frame(input: &[u8]) -> Result<AudioFrame<'_>, AudioFrameDecodeError> {
    let header = AudioFrameHeader::decode(input)?;
    let declared = header.payload_len as usize;
    let actual = input.len().saturating_sub(AudioFrameHeader::WIRE_LEN);

    if declared != actual {
        return Err(AudioFrameDecodeError::LengthMismatch { declared, actual });
    }

    Ok(AudioFrame {
        header,
        payload: &input[AudioFrameHeader::WIRE_LEN..],
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pcm_audio_frame_round_trip() {
        let payload = [1, 2, 3, 4, 5, 6, 7, 8];
        let header = AudioFrameHeader::pcm_s16le(1, 12_000, 3, 48_000, 1, 10_000, 0);
        let encoded = encode_audio_frame(header, &payload);
        let decoded = decode_audio_frame(&encoded).unwrap();

        assert_eq!(decoded.header.stream_id, 1);
        assert_eq!(decoded.header.media_time_us, 12_000);
        assert_eq!(decoded.header.frame_sequence, 3);
        assert_eq!(decoded.header.sample_rate_hz, 48_000);
        assert_eq!(decoded.header.channels, 1);
        assert_eq!(decoded.header.codec, AudioCodec::Pcm);
        assert_eq!(decoded.header.sample_format, SampleFormat::S16Le);
        assert_eq!(decoded.header.frame_duration_us, 10_000);
        assert_eq!(decoded.header.payload_len, payload.len() as u32);
        assert_eq!(decoded.payload, payload);
    }
}
