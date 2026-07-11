# OpenSoundRelay (OSR)

OpenSoundRelay (OSR) is an open-source, cross-platform audio relay protocol and app project focused on high-quality, low-latency device-to-device audio.

The first product target is **Android-to-Android live audio relay**, but the protocol and core library are designed so Android, iOS, Windows, macOS, Linux, and Web clients can interoperate.

## Current status

OSR is now a functional prototype with:

- Rust protocol core
- shared UDP transport crate
- CLI tools
- Android-to-Android PCM sender/receiver app
- foreground-service-backed Android device playback capture
- desktop GUI for Linux/Windows/macOS protocol testing and PCM playback
- multi-device UDP unicast fan-out
- parent-authoritative stream volume synchronization
- cross-platform GitHub Actions build workflows

It is still pre-release software. The next major quality step is Opus audio, LAN discovery, and real-device latency tuning.

## Key idea

OSR separates the project into three layers:

1. **OSR Core**: portable protocol, packets, volume synchronization, timing, and audio-frame rules.
2. **OSR Net**: cross-platform UDP transport, target lists, fan-out, and packet routing.
3. **Platform Apps**: Android, iOS, desktop, and Web implementations that connect to the same protocol.

## Multi-device fan-out

The parent can send the same audio and volume commands to multiple child devices by UDP unicast fan-out.

```text
Parent sender
  -> child A 192.168.1.10:40124
  -> child B 192.168.1.11:40124
  -> child C 192.168.1.12:40124
```

See [docs/multi-device.md](./docs/multi-device.md).

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
- Multi-device output by unicast fan-out
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
android/app/         Android PCM prototype app
crates/osr-core/     Portable protocol and volume sync core
crates/osr-net/      Cross-platform UDP transport and fan-out
crates/osr-cli/      CLI receiver, volume sender, and tone sender
crates/osr-desktop/  Cross-platform desktop GUI
docs/                Architecture, protocol, networking, and build docs
```

## Try the Android PCM prototype

The Android app currently supports manual Android-to-Android testing over Wi-Fi, including multiple child receivers:

1. Install the app on two or more Android devices.
2. Start receiver mode on each child device.
3. Enter each child address on the sender device, one per line or comma-separated.
4. Start sender fan-out mode.
5. Move the parent volume slider and confirm that all receivers follow it.

See [docs/android-prototype.md](./docs/android-prototype.md).

## Try the desktop GUI

```bash
cargo run -p osr-desktop
```

The GUI can run as a UDP receiver with PCM playback or a multi-target tone sender.

See [docs/desktop-gui.md](./docs/desktop-gui.md).

## Try the CLI

Receiver:

```bash
cargo run -p osr-cli -- child --bind 0.0.0.0:40124
```

Tone sender to multiple targets:

```bash
cargo run -p osr-cli -- tone --target 127.0.0.1:40124,127.0.0.1:40125
```

Volume sender to multiple targets:

```bash
cargo run -p osr-cli -- host --target 127.0.0.1:40124 --target 127.0.0.1:40125 --volume 0.35
```

## Build

See [docs/build.md](./docs/build.md).

## License

OpenSoundRelay is licensed under the Mozilla Public License Version 2.0.

See [LICENSE](./LICENSE) for details.
