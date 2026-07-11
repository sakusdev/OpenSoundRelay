# Android PCM Prototype

This document describes the v0.2 Android-to-Android prototype.

## Current behavior

The Android app can run in three modes:

- **Mic Sender**: captures microphone audio with `AudioRecord`, wraps PCM S16LE frames in OSR `Audio` packets, and sends them over UDP to one or more targets.
- **Device Audio Sender**: asks for Android MediaProjection screen-share consent, starts a `mediaProjection` foreground service, captures capturable device playback audio through `AudioPlaybackCaptureConfiguration`, wraps PCM S16LE frames in OSR `Audio` packets, and sends them over UDP to one or more targets.
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
4. Press **Start Mic Sender Fan-out** or **Start Device Audio Sender Fan-out**.
5. If using device audio mode, approve the Android screen/audio capture prompt.
6. Move the parent volume slider on the sender.
7. Confirm that receiver output volume follows the parent stream gain.

## Multi-child test setup

1. Start receiver mode on each child device.
2. Enter every child address in the sender target field, one per line or comma-separated.
3. Press **Start Mic Sender Fan-out** or **Start Device Audio Sender Fan-out**.
4. If using device audio mode, approve the Android screen/audio capture prompt.
5. Move the parent volume slider.
6. Confirm that all children follow the same parent stream volume.

Example target field:

```text
192.168.1.10:40124
192.168.1.11:40124
192.168.1.12:40124
```

If the port is omitted, the app uses the default target port field.

## Device playback capture notes

Android playback capture requires:

- Android 10 or later.
- `RECORD_AUDIO` permission.
- User approval through the MediaProjection screen capture prompt.
- The captured app must allow playback capture.
- The captured audio usage must be capturable, such as media, game, or unknown usage.

Some apps, protected content, DRM content, calls, and apps that opt out of playback capture may produce silence.

After consent, device playback capture is owned by `AudioRelayService`. The session continues when the activity is backgrounded or removed from Recents. Android displays an ongoing notification with a **Stop** action. The service also stops and releases its audio resources when the user ends projection from Android's system UI or locks the screen on versions that automatically end projection.

## Current limitations

- No Opus yet; PCM is intentionally used for easy debugging.
- The receiver jitter buffer is intentionally simple and still needs tuning.
- No LAN discovery yet; enter receiver IPs manually.
- No pairing/authentication yet; do not test on untrusted networks.
- No echo cancellation/noise suppression pipeline yet.
- Microphone sender and receiver modes still follow the activity lifecycle; only device playback capture currently runs in a foreground service.

## Next steps

1. Add LAN discovery or QR pairing.
2. Add Opus encode/decode while keeping the same OSR `AudioFrame` envelope.
3. Add per-child packet loss/latency stats.
4. Add Android real-device latency notes.
