# Audio Quality and Delay Correction

OSR separates codec quality, network jitter, device-output latency, clock/rate drift, and tone shaping. These are different sources of delay and should not be hidden behind one large buffer.

## Android low-latency path

Android sender frames are 5 ms at 48 kHz mono. Android receivers prefer a native AAudio output stream configured for:

- low-latency performance mode
- exclusive sharing when the device grants it, with shared-mode fallback
- a real-time data callback instead of blocking Java writes
- a two-burst AAudio device buffer
- a small non-blocking native ring buffer

The UDP receiver, Opus decoder, and output callback remain separate. If the native ring is full, new input is rejected instead of allowing output latency to grow. On Android versions or devices where AAudio cannot be opened, OSR falls back to a low-latency `AudioTrack` using non-blocking writes.

Bluetooth can still add substantial latency outside OSR. Wired, USB, or the device speaker is recommended for latency testing.

## Opus bitrate

Android senders use Opus in restricted-low-delay mode. The bitrate is continuously adjustable from **8 kbps to 512 kbps** while a session is active.

| Bitrate | Suggested use |
|---:|---|
| 8–24 kbps | speech or severely constrained networks |
| 32–64 kbps | voice and casual listening |
| 96–160 kbps | recommended music range |
| 192–320 kbps | high-quality local-network streaming |
| 512 kbps | maximum quality; little benefit for mono on many sources |

Bitrate changes do not restart capture or the network session. If the native Opus library cannot initialize, the Android sender reports a PCM fallback; that fallback is approximately 768 kbps for 48 kHz mono S16LE.

## Freshness-first jitter buffer

Each frame carries a monotonically increasing sequence. The receiver tracks gaps and out-of-order arrivals.

- stale and duplicate frames are ignored
- an excessive queue is trimmed instead of allowing latency to grow without bound
- underruns can increase the target one frame at a time
- one second or more of stable playback gradually lowers the target again
- old audio is sacrificed when necessary so live playback catches up to “now”

Android exposes three starting points for 5 ms frames:

| Profile | Initial target | Maximum queue |
|---|---:|---:|
| Ultra low | 5 ms | 30 ms |
| Balanced | 10 ms | 50 ms |
| Stable | 20 ms | 80 ms |

These numbers describe the OSR network queue. The device audio HAL, speaker path, USB device, or Bluetooth route may add more latency.

## Desktop drift correction

A sender and receiver rarely run at exactly the same hardware clock rate. The desktop output queue therefore applies sparse corrections:

- if the queue is above its target, it occasionally consumes one extra sample
- if the queue is below its target, it occasionally repeats the previous sample
- if the queue grows far beyond the target, old samples are trimmed
- after an underrun, playback re-enters prebuffering

Desktop Opus decoding is not yet implemented; desktop clients currently accept PCM frames only.

## Tone controls

The receiver splits the signal into low- and high-frequency components using a one-pole low-pass filter around 250 Hz. Bass and treble gains are applied independently from -12 dB to +12 dB. A soft limiter constrains boosted output without hard digital clipping.

## Metrics

Android reports:

- OSR queue and target milliseconds
- estimated packet loss and stale-frame drops
- Opus decode failures
- output backpressure drops
- selected output backend
- AAudio hardware-ring depth, callback underruns, and rejected input frames

These values make it possible to distinguish network buffering from device/HAL latency.
