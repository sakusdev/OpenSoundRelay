# Android PCM Prototype

This document describes the v0.2 Android-to-Android prototype.

## Current behavior

The Android app can run in two modes:

- **Sender**: captures microphone audio with `AudioRecord`, wraps PCM S16LE frames in OSR `Audio` packets, and sends them over UDP.
- **Receiver**: receives OSR packets over UDP, applies parent-controlled OSR stream gain, and plays PCM S16LE through `AudioTrack`.

The prototype uses:

| Item | Value |
|---|---|
| sample rate | 48 kHz |
| channels | mono |
| sample format | PCM S16LE |
| frame duration | 10 ms |
| transport | UDP |
| default port | 40124 |

## Test setup

1. Install the app on two Android devices connected to the same Wi-Fi network.
2. On the receiver device, press **Start Receiver**.
3. On the sender device, enter the receiver device IP address.
4. Press **Start Sender**.
5. Move the parent volume slider on the sender.
6. Confirm that receiver output volume follows the parent stream gain.

## Current limitations

- No Opus yet; PCM is intentionally used for easy debugging.
- No jitter buffer yet; packet timing depends directly on Wi-Fi behavior.
- No LAN discovery yet; enter the receiver IP manually.
- No pairing/authentication yet; do not test on untrusted networks.
- No echo cancellation/noise suppression pipeline yet.
- No background service yet; the prototype is foreground-only.

## Next steps

1. Add a small jitter buffer before `AudioTrack.write`.
2. Add LAN discovery or QR pairing.
3. Add Opus encode/decode while keeping the same OSR `AudioFrame` envelope.
4. Add Android CI and real-device test notes.
