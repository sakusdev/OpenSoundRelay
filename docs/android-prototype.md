# Android PCM Prototype

The Android app can run in three modes:

- **Mic sender**: captures microphone audio with `AudioRecord` and sends PCM S16LE frames to one or more targets.
- **Device audio sender**: uses Android playback capture through a MediaProjection foreground service.
- **Receiver**: receives PCM, applies OSR stream gain, optionally follows the sender's native media volume, adapts its jitter buffer, applies tone controls, and plays through `AudioTrack`.

## Current format

| Item | Value |
|---|---|
| sample rate | 48 kHz |
| channels | mono |
| sample format | PCM S16LE |
| frame duration | 10 ms |
| transport | UDP unicast fan-out |
| audio port | 40124 by default |
| discovery port | 40125 |

## LAN discovery setup

1. Connect the devices to the same Wi-Fi/LAN.
2. Press **Start receiver** on each child.
3. On the sender, press **Scan LAN**.
4. Tap each discovered receiver to add it as a target.
5. Start the microphone or device-audio sender.

Manual target entry remains available when Wi-Fi client isolation or a firewall blocks broadcast discovery.

## Native media-volume synchronization

The sender samples Android's `STREAM_MUSIC` state every control interval. When native sync is enabled it sends a separate `DeviceVolume` command to every receiver. This includes changes made with hardware volume buttons.

A receiver applies the command through `AudioManager` only when its native-sync option was enabled before receiver startup. OSR stream gain remains independent and is always applied inside the received PCM stream.

## Audio quality and delay

The receiver provides:

- low-latency, balanced, and stable starting profiles
- adaptive jitter target after packet gaps
- gradual latency reduction after a stable period
- bass and treble controls from -12 dB to +12 dB
- soft limiting after EQ
- low-latency `AudioTrack` performance mode
- status text with buffered time, estimated loss, and correction count

## Device playback capture notes

Android playback capture requires:

- Android 10 or later
- `RECORD_AUDIO` permission
- user approval through the MediaProjection prompt
- a captured app that allows playback capture
- capturable audio usage such as media, game, or unknown

Protected content, DRM media, calls, and apps that opt out may produce silence.

The foreground service keeps device playback capture alive when the activity is backgrounded or removed from Recents. Android displays an ongoing notification with a **Stop** action.

## Current limitations

- no Opus yet
- mono PCM only
- no pairing or authentication
- broadcast discovery may be blocked on guest/enterprise Wi-Fi
- microphone sender and receiver still follow the activity lifecycle
- audio focus and route changes need broader device testing
