# Desktop GUI

`osr-desktop` is a cross-platform desktop test GUI for OpenSoundRelay.

It is built with Rust and egui/eframe so the same source can run on:

- Linux
- Windows
- macOS

## Current features

- UDP receiver mode
- PCM tone sender mode
- parent volume slider
- volume command sending
- OSR audio packet logging
- OSR volume command logging

The desktop GUI is currently a protocol and transport validation tool. It does not play received audio yet.

## Run

Receiver:

```bash
cargo run -p osr-desktop
```

Then press **Start Receiver**.

Tone sender:

1. Enter the receiver address, for example `192.168.1.50:40124`.
2. Press **Start Tone Sender**.
3. Move the parent volume slider.

## Why this exists

The Android app is the first real audio target. The desktop GUI exists so OSR packets, UDP behavior, and parent volume synchronization can be tested from Windows/macOS/Linux without needing two Android devices.

## Next steps

- Add desktop audio output using a cross-platform audio backend.
- Add microphone capture.
- Add Opus encode/decode.
- Add LAN discovery.
