# Android low-latency receive path

The Android receiver prioritizes freshness over perfect packet retention.

- UDP receive and `AudioTrack` playback run on separate threads.
- The playback thread requests urgent-audio scheduling priority.
- `AudioTrack` uses its minimum supported buffer instead of adding six extra 10 ms frames.
- A small ordered live queue starts at 10–40 ms depending on the selected preset.
- Underruns grow the target gradually; one second of stable playback shrinks it again.
- When the queue exceeds its hard limit, old frames are dropped so playback catches up to live audio.

The status line reports queue depth, adaptive target, estimated loss, stale drops, and underruns. Bluetooth output and device-specific Android audio HAL buffering can still add substantial latency outside OpenSoundRelay.
