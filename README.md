# OpenSoundRelay (OSR)

OpenSoundRelay (OSR) is an open-source, cross-platform audio relay protocol and app project focused on high-quality, low-latency device-to-device audio on a local network.

The first product target is **Android-to-Android live audio relay**, while the protocol and Rust core are designed so Android, iOS, Windows, macOS, Linux, and Web clients can interoperate.

## Current status

OSR is a functional pre-release prototype with:

- Rust protocol core and shared UDP transport
- Android microphone and device-playback senders
- Android and desktop PCM receivers
- foreground-service-backed Android playback capture
- multi-device UDP unicast fan-out
- **LAN receiver discovery over UDP broadcast**
- separate OSR stream-gain synchronization
- **native media/output-volume synchronization**
- **adaptive receiver latency correction and jitter buffering**
- receiver bass, treble, and soft-limiter controls
- desktop output-rate conversion and live receiver metrics
- redesigned dark desktop and Android interfaces
- cross-platform GitHub Actions build workflows

It is still pre-release software. The next major quality step is Opus audio, authenticated pairing, real-device latency measurement, and broader hardware testing.

## Key idea

OSR separates the project into three layers:

1. **OSR Core**: portable protocol, packets, stream volume, native device volume, timing, and audio-frame rules.
2. **OSR Net**: cross-platform UDP transport, LAN discovery, target lists, fan-out, and packet routing.
3. **Platform Apps**: Android, iOS, desktop, and Web implementations that connect the protocol to native audio and volume APIs.

## Find receivers on the LAN

Start receiver mode on another OSR device, then press **Scan LAN** or **Scan local network**. Receivers answer a short UDP broadcast probe with their name, audio port, role, and capabilities. Select a result to add it as a target.

Discovery uses UDP port `40125`; audio uses `40124` by default. It does not require an account, cloud service, or internet connection.

See [docs/lan-discovery.md](./docs/lan-discovery.md).

## Two independent volume controls

OSR now distinguishes between:

```text
OSR stream gain
  -> changes only samples inside the relayed OSR stream

Native device volume
  -> changes Android media volume or the desktop default output volume
```

A sender can monitor its native media/output volume and publish it to receivers. Receivers may opt in before applying it. This means hardware volume buttons on an Android sender can control the receiving devices as well.

Desktop native-volume integration uses:

- Linux: `wpctl`, with `pactl` fallback
- macOS: `osascript`
- Windows: the built-in Core Audio API through an inline PowerShell bridge

Native volume synchronization is best-effort because operating-system permissions and audio policies differ. OSR stream gain remains the portable fallback.

## Audio quality and automatic delay correction

Receivers expose low-latency, balanced, and stable presets plus manual bass and treble controls. The adaptive jitter buffer increases its target after packet gaps and slowly reduces it after a stable period. Desktop playback also performs gentle sample-level drift correction, resamples 48 kHz input to the actual output-device rate, and reports buffer, underrun, packet-loss, and correction statistics.

See [docs/audio-quality.md](./docs/audio-quality.md).

## Multi-device fan-out

The parent sends the same audio and control commands to multiple children by UDP unicast fan-out.

```text
Parent sender
  -> child A 192.168.1.10:40124
  -> child B 192.168.1.11:40124
  -> child C 192.168.1.12:40124
```

See [docs/multi-device.md](./docs/multi-device.md).

## Design goals

- Android-to-Android first
- cross-platform protocol from day one
- multi-device output by unicast fan-out
- high-quality Opus target
- low-latency UDP/QUIC-friendly packet design
- separate stream and native-device volume synchronization
- deterministic fixed-point stream gain math
- adaptive receiver buffering
- LAN-first operation
- no cloud dependency
- MPL-2.0 licensed

## Security note

LAN discovery and audio packets are currently unauthenticated. Use the prototype only on networks you trust. Pairing codes, peer authentication, and encrypted transport remain planned work.

## Repository layout

```text
android/app/         Android sender/receiver app
crates/osr-core/     Portable protocol and synchronization core
crates/osr-net/      UDP transport, discovery, and fan-out
crates/osr-cli/      CLI receiver, volume sender, and tone sender
crates/osr-desktop/  Cross-platform desktop GUI
docs/                Architecture, protocol, networking, and build docs
```

## Try the Android prototype

1. Install the app on two or more Android devices connected to the same Wi-Fi network.
2. Press **Start receiver** on each child.
3. On the sender, press **Scan LAN** and add the receivers.
4. Start **Mic sender** or **Device audio**.
5. Use the Android media-volume buttons and confirm that opted-in receivers follow the native volume.
6. Change the latency profile or tone controls on each receiver as needed.

See [docs/android-prototype.md](./docs/android-prototype.md).

## Try the desktop GUI

```bash
cargo run -p osr-desktop
```

The desktop GUI can discover receivers, run as a PCM receiver, send a multi-target test tone, synchronize native output volume, tune audio, and display live receiver statistics.

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
