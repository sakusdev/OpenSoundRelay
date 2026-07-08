# Multi-device Fan-out

OSR supports multi-device output through UDP unicast fan-out.

## Model

The parent sends the same OSR packets to multiple child addresses:

```text
Parent sender
  -> child A 192.168.1.10:40124
  -> child B 192.168.1.11:40124
  -> child C 192.168.1.12:40124
```

This is intentionally unicast instead of multicast for the prototype. Unicast is easier to debug and works more predictably across typical Wi-Fi routers and Android devices.

## What is synchronized?

The parent sends the same `AudioFrame` and the same `VolumeCommand` to every target.

Every child receives:

- the same stream id
- the same audio frame sequence
- the same parent gain value
- the same volume epoch/sequence ordering

This means the parent volume slider can control all children at once.

## Current target syntax

Targets can be separated by commas, semicolons, or newlines.

```text
192.168.1.10:40124
192.168.1.11:40124
192.168.1.12:40124
```

or:

```text
192.168.1.10:40124,192.168.1.11:40124,192.168.1.12:40124
```

Duplicate targets are ignored.

## CLI examples

Send volume commands to two children:

```bash
cargo run -p osr-cli -- host \
  --target 192.168.1.10:40124 \
  --target 192.168.1.11:40124 \
  --volume 0.5
```

Send test tone to two children:

```bash
cargo run -p osr-cli -- tone --target 192.168.1.10:40124,192.168.1.11:40124
```

## Android behavior

The Android sender target field accepts multiple lines or comma-separated targets.

If a target omits the port, the default target port field is used.

```text
192.168.1.10
192.168.1.11:40124
```

## Desktop behavior

The desktop GUI target field is multi-line. Press **Start Tone Sender Fan-out** to send to all listed targets.

## Bandwidth note

The current PCM prototype is heavier than the future Opus path.

Approximate mono PCM 48kHz 16-bit bandwidth:

```text
768 kbps per child, before UDP/IP overhead
```

So five children are roughly:

```text
3.8 Mbps plus overhead
```

This is usually acceptable on a local Wi-Fi network, but Opus will be much lighter.

## Future work

- LAN discovery to add children automatically.
- Per-child packet loss/latency stats.
- Per-child enable/disable.
- Group sessions with peer identity.
- Opus fan-out for lower bandwidth.
