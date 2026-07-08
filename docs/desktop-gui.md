# Desktop GUI

`osr-desktop` is a cross-platform desktop GUI for OpenSoundRelay.

It is built with Rust and egui/eframe so the same source can run on:

- Linux
- Windows
- macOS

## Current features

- UDP receiver mode
- PCM tone sender mode
- multi-target tone sender fan-out
- parent volume slider
- volume command sending
- OSR audio packet logging
- OSR volume command logging
- PCM S16LE mono playback through the default desktop output device

The desktop GUI can now be used as a basic desktop receiver for OSR PCM prototype streams, or as a desktop parent that sends a test tone to multiple children.

## Run

Receiver with playback:

```bash
cargo run -p osr-desktop
```

Then press **Start Receiver + Playback**.

Tone sender fan-out:

1. Enter one or more receiver addresses, one per line or comma-separated.
2. Press **Start Tone Sender Fan-out**.
3. Move the parent volume slider.

Example targets:

```text
192.168.1.50:40124
192.168.1.51:40124
192.168.1.52:40124
```

## Test combinations

- Android sender -> desktop receiver
- desktop tone sender -> multiple Android receivers
- desktop tone sender -> multiple desktop receivers
- CLI tone sender -> desktop receiver

## Current limitations

- Desktop microphone capture is not implemented yet.
- Desktop playback currently expects PCM S16LE mono OSR frames.
- Opus is not implemented yet.
- LAN discovery is not implemented yet.

## Next steps

- Add desktop microphone capture.
- Add Opus encode/decode.
- Add LAN discovery.
- Add pairing/authentication.
