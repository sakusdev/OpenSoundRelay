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
| 1 | Hello | LAN discovery probe/announcement |
| 2 | Audio | audio frame |
| 3 | VolumeCommand | parent-authoritative OSR stream gain |
| 4 | TimeSync | clock/media timeline synchronization |
| 5 | DeviceVolume | parent-authoritative native media/output volume |

Unknown packet kinds must be ignored safely by a receiver.

## Hello discovery payload

Length: 20 bytes plus UTF-8 name.

```text
0       discovery wire version: u8, currently 1
1       message type: u8, 1=probe, 2=announcement
2       role: u8, 0=idle, 1=sender, 2=receiver, 3=duplex
3       reserved
4..8    capability flags: u32
8..10   advertised audio UDP port: u16
10..12  device name byte length: u16
12..20  scan nonce: u64
20..    UTF-8 device name, maximum 96 bytes
```

An announcement echoes the probe nonce. See [lan-discovery.md](./lan-discovery.md).

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

| Value | Name | Payload |
|---:|---|---|
| 1 | PCM S16LE | raw signed 16-bit little-endian samples |
| 2 | Opus | one self-contained Opus packet |

### Sample format values

| Value | Name | Purpose |
|---:|---|---|
| 0 | None/encoded | sample format is defined by the compressed codec |
| 1 | S16LE | signed 16-bit little-endian PCM |
| 2 | F32LE | 32-bit little-endian float PCM |

Android low-latency sessions use 48 kHz mono, `codec=2`, `sample_format=0`, and 5,000 µs frames. The Opus packet is encoded with `OPUS_APPLICATION_RESTRICTED_LOWDELAY`; bitrate may change between 8,000 and 512,000 bits/s without changing the envelope.

Legacy PCM sessions use `codec=1` and `sample_format=1`. Receivers must ignore codecs they do not implement.

## VolumeCommand payload

`VolumeCommand` affects only the gain inside the OSR stream.

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

## DeviceVolume payload

`DeviceVolume` asks a platform client to update its native media/output volume. Receivers may refuse or disable this operation because platform policy and user preference take precedence.

Length: 24 bytes.

```text
0..8    epoch: u64
8..16   sequence: u64
16..18  volume_percent: u16, clamped to 0..100
18      muted: u8, 0=false, non-zero=true
19..24  reserved, must be zero in v1
```

Stream gain and native volume are deliberately different packet kinds. A client can support either or both.

## Conflict rules

A child accepts a newer command when:

```text
candidate.epoch > current.epoch
```

or:

```text
candidate.epoch == current.epoch && candidate.sequence > current.sequence
```

For `VolumeCommand`, a different non-zero `stream_id` is also rejected. Stale commands must be ignored.

A sender should choose a new non-zero epoch for each session so sequence numbers can restart safely.

## Gain math

The reference operation for signed 16-bit PCM is:

```text
if muted or gain_ppm == 0:
    output = 0
else:
    output = clamp_i16(sample * min(gain_ppm, 2_000_000) / 1_000_000)
```

This integer operation is the normative behavior for exact cross-platform OSR stream-gain synchronization.

## Why fixed-point?

Floating-point behavior is usually close across platforms, but OSR wants stream-gain state and sample math to be reproducible in Android, iOS, desktop, and Web implementations. Integer fixed-point values avoid unnecessary differences.
