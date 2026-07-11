# Desktop GUI

`osr-desktop` is a cross-platform Rust/egui client for Linux, Windows, and macOS.

## Features

- modern dark two-column interface
- UDP PCM receiver and multi-target tone sender
- LAN receiver discovery
- one-click discovered-device target addition
- separate OSR stream-gain and native output-volume controls
- optional native-volume following on receivers
- adaptive target latency from 10 to 200 ms
- bass and treble adjustment from -12 dB to +12 dB
- soft limiter
- 48 kHz input conversion to the actual output-device sample rate
- gentle clock-drift correction
- packet-loss, queue, underrun, correction, and sample-rate metrics
- bounded activity log

## Run

```bash
cargo run -p osr-desktop
```

## Receiver

1. Set the bind address, normally `0.0.0.0:40124`.
2. Choose the latency and tone settings.
3. Enable or disable native-volume following.
4. Press **Start receiver**.
5. The receiver becomes discoverable on UDP port `40125` while active.

## Sender

1. Start receiver mode on one or more Android or desktop devices.
2. Press **Scan local network**.
3. Press **Add target** beside each receiver.
4. Press **Start test tone**.
5. Adjust OSR stream gain independently from native output volume.

The desktop sender polls the current default-output volume while native sync is enabled. Changes made through the OS mixer or hardware controls are published to receivers.

## Native-volume backends

| Platform | Backend |
|---|---|
| Linux | `wpctl`, then `pactl` fallback |
| macOS | `osascript` output-volume API |
| Windows | Core Audio endpoint API loaded through built-in PowerShell |

If a backend is unavailable, audio relay and OSR stream gain continue to work. The error appears in the activity log.

## Adaptive delay behavior

The output queue prebuffers to the selected target. It then applies sparse sample-level corrections when the sender and receiver hardware clocks drift. Large queue growth is trimmed, and an underrun returns playback to prebuffering. See [audio-quality.md](./audio-quality.md).

## Test combinations

- Android sender -> desktop receiver
- desktop tone sender -> multiple Android receivers
- desktop tone sender -> multiple desktop receivers
- CLI tone sender -> desktop receiver

## Current limitations

- desktop microphone/system-audio capture is not implemented
- desktop playback expects PCM S16LE mono OSR frames
- Opus is not implemented
- discovery and audio are unauthenticated
- some Linux installations expose neither `wpctl` nor `pactl`
