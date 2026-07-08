// SPDX-License-Identifier: MPL-2.0

/// Fixed-point gain in parts per million.
///
/// `1_000_000` means 100%, `500_000` means 50%, and `0` means silence.
/// This avoids cross-language floating-point differences.
pub type GainPpm = u32;

pub const UNITY_GAIN_PPM: GainPpm = 1_000_000;
pub const MAX_GAIN_PPM: GainPpm = 2_000_000;

/// Parent-authoritative stream volume.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VolumeState {
    pub stream_id: u32,
    pub epoch: u64,
    pub sequence: u64,
    pub gain_ppm: GainPpm,
    pub muted: bool,
    /// Media timeline timestamp where this value should become active.
    /// `0` means apply as soon as possible.
    pub target_media_time_us: u64,
}

impl Default for VolumeState {
    fn default() -> Self {
        Self {
            stream_id: 0,
            epoch: 0,
            sequence: 0,
            gain_ppm: UNITY_GAIN_PPM,
            muted: false,
            target_media_time_us: 0,
        }
    }
}

/// A wire-format volume command sent by the parent device.
pub type VolumeCommand = VolumeState;

impl VolumeState {
    pub const WIRE_LEN: usize = 40;

    pub fn new(stream_id: u32, epoch: u64, sequence: u64, gain_ppm: GainPpm) -> Self {
        Self {
            stream_id,
            epoch,
            sequence,
            gain_ppm: gain_ppm.min(MAX_GAIN_PPM),
            muted: false,
            target_media_time_us: 0,
        }
    }

    pub fn encode(self) -> [u8; Self::WIRE_LEN] {
        let mut out = [0u8; Self::WIRE_LEN];
        out[0..4].copy_from_slice(&self.stream_id.to_be_bytes());
        out[4..12].copy_from_slice(&self.epoch.to_be_bytes());
        out[12..20].copy_from_slice(&self.sequence.to_be_bytes());
        out[20..24].copy_from_slice(&self.gain_ppm.min(MAX_GAIN_PPM).to_be_bytes());
        out[24] = u8::from(self.muted);
        // bytes 25..32 are reserved and must be zero for v1
        out[32..40].copy_from_slice(&self.target_media_time_us.to_be_bytes());
        out
    }

    pub fn decode(input: &[u8]) -> Option<Self> {
        if input.len() != Self::WIRE_LEN {
            return None;
        }

        Some(Self {
            stream_id: u32::from_be_bytes([input[0], input[1], input[2], input[3]]),
            epoch: u64::from_be_bytes([
                input[4], input[5], input[6], input[7], input[8], input[9], input[10], input[11],
            ]),
            sequence: u64::from_be_bytes([
                input[12], input[13], input[14], input[15], input[16], input[17], input[18],
                input[19],
            ]),
            gain_ppm: u32::from_be_bytes([input[20], input[21], input[22], input[23]])
                .min(MAX_GAIN_PPM),
            muted: input[24] != 0,
            target_media_time_us: u64::from_be_bytes([
                input[32], input[33], input[34], input[35], input[36], input[37], input[38],
                input[39],
            ]),
        })
    }
}

/// Child-side volume state tracker.
///
/// Rules:
/// - a different non-zero stream id is ignored
/// - a newer epoch always wins
/// - inside the same epoch, a higher sequence wins
/// - stale commands are ignored
/// - gain math is fixed-point for cross-platform determinism
#[derive(Debug, Clone)]
pub struct VolumeSynchronizer {
    current: VolumeState,
}

impl VolumeSynchronizer {
    pub fn new(initial: VolumeState) -> Self {
        Self { current: initial }
    }

    pub fn current(&self) -> VolumeState {
        self.current
    }

    pub fn apply_parent_command(&mut self, command: VolumeCommand) -> bool {
        if self.current.stream_id != 0 && command.stream_id != self.current.stream_id {
            return false;
        }

        if is_newer(command, self.current) {
            self.current = command;
            true
        } else {
            false
        }
    }
}

fn is_newer(candidate: VolumeState, current: VolumeState) -> bool {
    candidate.epoch > current.epoch
        || (candidate.epoch == current.epoch && candidate.sequence > current.sequence)
}

/// Apply OSR stream gain to a signed 16-bit PCM sample.
///
/// This intentionally uses integer math so Android, iOS, desktop, and Web
/// implementations can match it exactly.
pub fn apply_gain_i16(sample: i16, gain_ppm: GainPpm, muted: bool) -> i16 {
    if muted || gain_ppm == 0 {
        return 0;
    }

    let scaled = (sample as i64 * gain_ppm.min(MAX_GAIN_PPM) as i64) / UNITY_GAIN_PPM as i64;
    scaled.clamp(i16::MIN as i64, i16::MAX as i64) as i16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_newer_sequence() {
        let mut sync = VolumeSynchronizer::new(VolumeState::new(7, 1, 1, UNITY_GAIN_PPM));
        let accepted = sync.apply_parent_command(VolumeState::new(7, 1, 2, 250_000));
        assert!(accepted);
        assert_eq!(sync.current().gain_ppm, 250_000);
    }

    #[test]
    fn ignores_stale_sequence() {
        let mut sync = VolumeSynchronizer::new(VolumeState::new(7, 1, 10, UNITY_GAIN_PPM));
        let accepted = sync.apply_parent_command(VolumeState::new(7, 1, 9, 250_000));
        assert!(!accepted);
        assert_eq!(sync.current().gain_ppm, UNITY_GAIN_PPM);
    }

    #[test]
    fn ignores_different_stream_id() {
        let mut sync = VolumeSynchronizer::new(VolumeState::new(7, 1, 10, UNITY_GAIN_PPM));
        let accepted = sync.apply_parent_command(VolumeState::new(8, 2, 1, 250_000));
        assert!(!accepted);
        assert_eq!(sync.current().stream_id, 7);
        assert_eq!(sync.current().gain_ppm, UNITY_GAIN_PPM);
    }

    #[test]
    fn newer_epoch_wins_even_with_lower_sequence() {
        let mut sync = VolumeSynchronizer::new(VolumeState::new(7, 1, 10, UNITY_GAIN_PPM));
        let accepted = sync.apply_parent_command(VolumeState::new(7, 2, 1, 300_000));
        assert!(accepted);
        assert_eq!(sync.current().epoch, 2);
        assert_eq!(sync.current().gain_ppm, 300_000);
    }

    #[test]
    fn volume_command_round_trip() {
        let mut state = VolumeState::new(7, 3, 9, 777_000);
        state.muted = true;
        state.target_media_time_us = 123_456;

        let encoded = state.encode();
        let decoded = VolumeState::decode(&encoded).unwrap();
        assert_eq!(decoded, state);
    }

    #[test]
    fn gain_math_is_deterministic() {
        assert_eq!(apply_gain_i16(10_000, 500_000, false), 5_000);
        assert_eq!(apply_gain_i16(-10_000, 500_000, false), -5_000);
        assert_eq!(apply_gain_i16(10_000, 0, false), 0);
        assert_eq!(apply_gain_i16(10_000, UNITY_GAIN_PPM, true), 0);
        assert_eq!(apply_gain_i16(30_000, 2_000_000, false), i16::MAX);
    }
}
