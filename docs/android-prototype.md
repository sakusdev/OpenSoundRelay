# Android PCM Prototype

This document describes the v0.2 Android-to-Android prototype.

## Current behavior

The Android app can run in two modes:

- **Sender**: captures microphone audio with `AudioRecord`, wraps PCM S16LE frames in OSR `Audio` packets, and sends them over UDP to one or more targets.
- **Receiver**: receives OSR packets over UDP, applies parent-controlled OSR stream gain, and plays PCM S16LE through `AudioTrack`.

The prototype uses:

| Item | Value |
|---|---|
| sample rate | 48 kHz |
| channels | mono |
| sample format | PCM S16LE |
| frame duration | 10 ms |
| transport | UDP unicast fan-out |
| default port | 40124 |

## Single-child test setup

1. Install the app on two Android devices connected to the same Wi-Fi network.
2. On the receiver device, press **Start Receiver**.
3. On the sender device, enter the receiver device IP address.
4. Press **Start Sender Fan-out**.
5. Move the parent volume slider on the sender.
6. Confirm that receiver output volume follows the parent stream gain.

## Multi-child test setup

1. Start receiver mode on each child device.
2. Enter every child address in the sender target field, one per line or comma-separated.
3. Press **Start Sender Fan-out**.
4. Move the parent volume slider.
5. Confirm that all children follow the same parent stream volume.

Example target field:

```text
192.168.1.10:40124
192.168.1.11:40124
192.168.1.12:40124
```

If the port is omitted, the app uses the default target port field.

## Current limitations

- No Opus yet; PCM is intentionally used for easy debugging.
- The receiver jitter buffer is intentionally simple and still needs tuning.
- No LAN discovery yet; enter receiver IPs manually.
- No pairing/authentication yet; do not test on untrusted networks.
- No echo cancellation/noise suppression pipeline yet.
- No background service yet; the prototype is foreground-only.

## Next steps

1. Add LAN discovery or QR pairing.
2. Add Opus encode/decode while keeping the same OSR `AudioFrame` envelope.
3. Add per-child packet loss/latency stats.
4. Add Android real-device latency notes.
