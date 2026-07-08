# OpenSoundRelay (OSR)

OpenSoundRelay (OSR) is an open-source, cross-platform audio relay protocol and app project focused on high-quality, low-latency device-to-device audio.

The first product target is **Android-to-Android live audio relay**, but the protocol and core library are designed so Android, iOS, Windows, macOS, Linux, and Web clients can interoperate.

## Key idea

OSR separates the project into two layers:

1. **OSR Core**: portable protocol, packets, volume synchronization, timing, and audio-frame rules.
2. **Platform Apps**: Android, iOS, desktop, and Web implementations that connect to the same protocol.

This repository currently contains the first cross-platform core pieces:

- MPL-2.0 license setup
- Rust workspace
- `osr-core` protocol library
- fixed-point parent/child volume synchronization
- CLI demo for volume sync packets
- protocol and architecture docs

## Volume synchronization model

OSR treats one device as the **parent** for a stream. The parent publishes an authoritative stream gain value. Child devices do not invent their own volume state; they follow the newest parent volume command by epoch and sequence number.

Important limitation: OSR can fully synchronize the **OSR stream volume** across platforms. It cannot reliably force every operating system's global master volume to the same value because Android, iOS, Windows, macOS, browsers, and Linux desktops expose different permissions and audio policies.

So OSR syncs this exactly:

```text
parent stream gain -> OSR protocol -> child stream gain -> audio samples
```

Not this:

```text
parent OS master volume -> every child OS master volume
```

## Design goals

- Android-to-Android first
- Cross-platform protocol from day one
- High-quality Opus audio target
- Low-latency UDP/QUIC-friendly packet design
- Parent-authoritative volume synchronization
- Deterministic fixed-point gain math
- LAN-first operation
- No cloud dependency
- MPL-2.0 licensed

## Non-goals

- VRChat-specific features
- Bluetooth audio replacement
- Cloud audio relay
- Forcing system master volume on platforms that do not allow it

## Repository layout

```text
crates/osr-core/   Portable protocol and volume sync core
crates/osr-cli/    Small CLI demo for cross-platform volume sync packets
docs/              Architecture and protocol specs
```

## Try the volume sync demo

Terminal 1:

```bash
cargo run -p osr-cli -- child --bind 127.0.0.1:40124
```

Terminal 2:

```bash
cargo run -p osr-cli -- host --target 127.0.0.1:40124 --volume 0.35
```

The child should print the parent-controlled volume as a deterministic fixed-point value.

## License

OpenSoundRelay is licensed under the Mozilla Public License Version 2.0.

See [LICENSE](./LICENSE) for details.
