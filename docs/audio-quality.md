# Audio Quality and Delay Correction

OSR receivers now separate three related concerns: network jitter, clock/rate drift, and tone shaping.

## Adaptive jitter buffer

Each PCM frame carries a monotonically increasing frame sequence. The receiver tracks gaps and out-of-order arrivals.

- after a sequence gap, the target buffer grows by one frame, up to the configured maximum
- after a long stable period, the target shrinks gradually
- stale and duplicate frames are ignored
- an excessive queue is trimmed instead of allowing latency to grow without bound

Android exposes three starting points:

| Profile | Initial target |
|---|---:|
| Low latency | about 20 ms |
| Balanced | about 40 ms |
| Stable | about 80 ms |

The target can move after startup when adaptive mode is enabled.

## Desktop drift correction

A sender and receiver rarely run at exactly the same hardware clock rate. The desktop output queue therefore applies sparse corrections:

- if the queue is above its target, it occasionally consumes one extra sample
- if the queue is below its target, it occasionally repeats the previous sample
- if the queue grows far beyond the target, old samples are trimmed
- after an underrun, playback re-enters prebuffering

These corrections are intentionally small and spread out to avoid an obvious click or a full-frame discontinuity.

## Output-rate conversion

OSR PCM prototype frames are 48 kHz. Desktop output devices may run at 44.1, 48, 96, or another rate. The desktop receiver linearly interpolates incoming mono PCM to the actual default-output sample rate before queueing it.

## Tone controls

The receiver splits the signal into low- and high-frequency components using a one-pole low-pass filter around 250 Hz. Bass and treble gains are then applied independently from -12 dB to +12 dB. A soft limiter can constrain boosted output without hard digital clipping.

These controls are receiver-local and are not currently synchronized between devices.

## Metrics

The desktop GUI reports:

- received frames
- estimated lost frames
- buffered milliseconds
- output underruns
- timing corrections
- output sample rate

Android reports the current/target jitter-buffer depth, estimated loss, and correction count in its session status.
