# OpenSoundRelay Architecture

OpenSoundRelay is designed as a cross-platform protocol first, then platform apps second.

## Layers

```text
+---------------------------------------------------------------+
| Platform UI                                                    |
| Android / iOS / Windows / macOS / Linux / Web                  |
+---------------------------------------------------------------+
| Platform audio I/O                                             |
| AudioRecord/AAudio, AVAudioEngine, WASAPI, CoreAudio, PipeWire |
+---------------------------------------------------------------+
| OSR Core                                                       |
| protocol, packets, volume sync, timing, jitter policy          |
+---------------------------------------------------------------+
| Transport                                                      |
| UDP first, QUIC Datagram later                                 |
+---------------------------------------------------------------+
```

## Parent and child model

For each stream, exactly one peer is the parent authority.

The parent is responsible for:

- stream identity
- capture settings
- codec parameters
- authoritative stream volume
- media timeline

The child is responsible for:

- receiving packets
- ignoring stale commands
- jitter buffering
- decoding audio
- applying OSR stream gain
- rendering audio through the local platform API

## Volume synchronization

OSR synchronizes the stream gain, not the platform master volume.

This is intentional. Every supported platform has different system volume APIs and permission rules. Forcing OS-level volume would be unreliable and sometimes impossible.

Instead, OSR makes this deterministic:

```text
parent gain_ppm -> VolumeCommand -> child VolumeSynchronizer -> PCM gain
```

`gain_ppm` is integer fixed-point:

```text
0         = 0%
500000    = 50%
1000000   = 100%
2000000   = 200% maximum boost
```

The same audio sample and gain value must produce the same result on every platform.

## Cross-platform implementation plan

| Platform | Core language | Audio I/O target |
|---|---|---|
| Android | Kotlin + Rust/JNI | AudioRecord + AAudio/Oboe |
| iOS | Swift + Rust FFI | AVAudioEngine / AudioUnit |
| Windows | Rust / C# shell | WASAPI |
| macOS | Swift/Rust shell | CoreAudio |
| Linux | Rust / GTK shell | PipeWire / ALSA fallback |
| Web | TypeScript + WASM | AudioWorklet |

The protocol must remain simple enough that a clean implementation can be written without Rust.

## First milestones

1. Protocol and volume sync core
2. Android sender and receiver prototype
3. Opus encode/decode
4. Jitter buffer and latency modes
5. Cross-platform desktop receiver
6. QUIC Datagram transport
7. Web receiver through WebRTC/WebTransport or UDP-compatible local helper
