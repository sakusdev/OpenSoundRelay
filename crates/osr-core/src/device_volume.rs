// SPDX-License-Identifier: MPL-2.0

/// Native media/output volume percentage used by device-volume synchronization.
pub type DeviceVolumePercent = u16;

pub const MAX_DEVICE_VOLUME_PERCENT: DeviceVolumePercent = 100;

/// Parent-authoritative native device volume.
///
/// This is deliberately separate from [`crate::VolumeState`]. `VolumeState`
/// controls the gain applied inside the OSR audio stream, while this command
/// asks a platform client to update its native media/output volume.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeviceVolumeState {
    pub epoch: u64,
    pub sequence: u64,
    pub volume_percent: DeviceVolumePercent,
    pub muted: bool,
}

impl Default for DeviceVolumeState {
    fn default() -> Self {
        Self {
            epoch: 0,
            sequence: 0,
            volume_percent: MAX_DEVICE_VOLUME_PERCENT,
            muted: false,
        }
    }
}

impl DeviceVolumeState {
    pub const WIRE_LEN: usize = 24;

    pub fn new(epoch: u64, sequence: u64, volume_percent: DeviceVolumePercent) -> Self {
        Self {
            epoch,
            sequence,
            volume_percent: volume_percent.min(MAX_DEVICE_VOLUME_PERCENT),
            muted: false,
        }
    }

    pub fn encode(self) -> [u8; Self::WIRE_LEN] {
        let mut out = [0u8; Self::WIRE_LEN];
        out[0..8].copy_from_slice(&self.epoch.to_be_bytes());
        out[8..16].copy_from_slice(&self.sequence.to_be_bytes());
        out[16..18].copy_from_slice(
            &self
                .volume_percent
                .min(MAX_DEVICE_VOLUME_PERCENT)
                .to_be_bytes(),
        );
        out[18] = u8::from(self.muted);
        // bytes 19..24 are reserved for future channel/zone selection.
        out
    }

    pub fn decode(input: &[u8]) -> Option<Self> {
        if input.len() != Self::WIRE_LEN {
            return None;
        }

        Some(Self {
            epoch: u64::from_be_bytes([
                input[0], input[1], input[2], input[3], input[4], input[5], input[6], input[7],
            ]),
            sequence: u64::from_be_bytes([
                input[8], input[9], input[10], input[11], input[12], input[13], input[14],
                input[15],
            ]),
            volume_percent: u16::from_be_bytes([input[16], input[17]])
                .min(MAX_DEVICE_VOLUME_PERCENT),
            muted: input[18] != 0,
        })
    }
}

#[derive(Debug, Clone)]
pub struct DeviceVolumeSynchronizer {
    current: DeviceVolumeState,
}

impl DeviceVolumeSynchronizer {
    pub fn new(initial: DeviceVolumeState) -> Self {
        Self { current: initial }
    }

    pub fn current(&self) -> DeviceVolumeState {
        self.current
    }

    pub fn apply_parent_command(&mut self, command: DeviceVolumeState) -> bool {
        if command.epoch > self.current.epoch
            || (command.epoch == self.current.epoch && command.sequence > self.current.sequence)
        {
            self.current = command;
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_volume_round_trip() {
        let mut state = DeviceVolumeState::new(4, 10, 73);
        state.muted = true;
        assert_eq!(DeviceVolumeState::decode(&state.encode()), Some(state));
    }

    #[test]
    fn synchronizer_rejects_stale_commands() {
        let mut sync = DeviceVolumeSynchronizer::new(DeviceVolumeState::new(2, 7, 50));
        assert!(!sync.apply_parent_command(DeviceVolumeState::new(2, 6, 90)));
        assert_eq!(sync.current().volume_percent, 50);
    }
}
