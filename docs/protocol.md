# OSR Protocol v1

All multi-byte integer values are big-endian.

## Packet header

```text
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         magic `OSR1`                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| version = 1                   | kind                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| flags                         | reserved                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| reserved                                                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| sequence high                                                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| sequence low                                                   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| payload_len                                                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| payload ...                                                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

Header length: 28 bytes.

## Packet kinds

| Value | Name | Purpose |
|---:|---|---|
| 1 | Hello | peer capability announcement |
| 2 | Audio | encoded audio frame |
| 3 | VolumeCommand | parent-authoritative stream volume |
| 4 | TimeSync | clock/media timeline synchronization |

## VolumeCommand payload

Length: 40 bytes.

```text
0..4    stream_id: u32
4..12   epoch: u64
12..20  sequence: u64
20..24  gain_ppm: u32
24      muted: u8, 0=false, non-zero=true
25..32  reserved, must be zero in v1
32..40  target_media_time_us: u64
```

## Volume conflict rules

A child accepts a new `VolumeCommand` when:

```text
candidate.epoch > current.epoch
```

or:

```text
candidate.epoch == current.epoch && candidate.sequence > current.sequence
```

Otherwise, it must ignore the command as stale.

## Gain math

The reference operation for signed 16-bit PCM is:

```text
if muted or gain_ppm == 0:
    output = 0
else:
    output = clamp_i16(sample * min(gain_ppm, 2_000_000) / 1_000_000)
```

This integer operation is the normative behavior for exact cross-platform OSR stream volume synchronization.

## Why fixed-point?

Floating-point behavior is usually close across platforms, but OSR wants the volume state and gain operation to be reproducible in Android, iOS, desktop, and Web implementations. Integer fixed-point values avoid unnecessary differences.

## Future audio packet payload

The v1 `Audio` packet is reserved for Opus frames. The planned fields are:

```text
stream_id: u32
media_time_us: u64
sample_rate: u32
channels: u8
codec: u8
frame_duration_us: u16
encoded_payload: bytes
```

This will be finalized after the Android prototype proves latency behavior.
