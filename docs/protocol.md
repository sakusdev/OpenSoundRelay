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
| 2 | Audio | audio frame |
| 3 | VolumeCommand | parent-authoritative stream volume |
| 4 | TimeSync | clock/media timeline synchronization |

## Audio payload

The `Audio` packet payload is an `AudioFrame`.

AudioFrame header length: 36 bytes.

```text
0..4    stream_id: u32
4..12   media_time_us: u64
12..20  frame_sequence: u64
20..24  sample_rate_hz: u32
24      channels: u8
25      codec: u8
26      sample_format: u8
27      reserved, must be zero in v1
28..32  frame_duration_us: u32
32..36  payload_len: u32
36..    audio payload bytes
```

### Codec values

| Value | Name | Purpose |
|---:|---|---|
| 1 | PCM | raw PCM prototype frames |
| 2 | Opus | future high-quality low-latency frames |

### Sample format values

| Value | Name | Purpose |
|---:|---|---|
| 1 | S16LE | signed 16-bit little-endian PCM |
| 2 | F32LE | 32-bit little-endian float PCM |
| 255 | Encoded | compressed codec payload, for example Opus |

v0.2 uses `codec=1` and `sample_format=1` for Android-to-Android PCM S16LE prototyping.

v0.3 should keep the same `AudioFrame` envelope and switch to `codec=2` and `sample_format=255` for Opus payloads.

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
candidate.stream_id == current.stream_id
```

and:

```text
candidate.epoch > current.epoch
```

or:

```text
candidate.epoch == current.epoch && candidate.sequence > current.sequence
```

Otherwise, it must ignore the command as stale or unrelated to the current stream.

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
