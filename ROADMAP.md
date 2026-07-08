# OpenSoundRelay Roadmap

## v0.1: Protocol foundation

- [x] MPL-2.0 license
- [x] Rust workspace
- [x] OSR v1 packet header
- [x] Parent-authoritative volume command
- [x] Deterministic fixed-point gain math
- [x] UDP volume sync CLI demo
- [x] CI for Rust tests

## v0.2: Android-to-Android PCM prototype

- [x] Android sender mode
- [x] Android receiver mode
- [x] AudioRecord PCM capture
- [x] AudioTrack playback
- [x] UDP audio packet prototype
- [x] Parent volume slider
- [x] Child read-only synced volume display
- [x] receiver jitter buffer
- [x] Android build CI
- [ ] real-device latency notes

## v0.2.5: Functional cross-platform prototype

- [x] shared UDP transport crate
- [x] CLI receiver
- [x] CLI volume sender
- [x] CLI tone sender
- [x] desktop GUI receiver
- [x] desktop GUI tone sender
- [x] desktop parent volume slider
- [x] desktop audio playback
- [x] Linux/Windows/macOS Rust CI
- [x] manual release build workflow
- [ ] desktop microphone capture

## v0.3: High-quality low-latency audio

- [ ] Opus encode/decode
- [ ] 48kHz stream format
- [ ] 5ms / 10ms / 20ms frame modes
- [ ] Jitter buffer tuning
- [ ] latency target modes
- [ ] packet loss statistics

## v0.4: Pairing and discovery

- [ ] LAN discovery
- [ ] QR manual pairing
- [ ] peer identity
- [ ] reject unauthenticated volume commands
- [ ] session resumption

## v0.5: Cross-platform receivers

- [x] Linux GUI protocol receiver
- [x] Windows GUI protocol receiver
- [x] macOS GUI protocol receiver
- [x] Linux PCM audio receiver
- [x] Windows PCM audio receiver
- [x] macOS PCM audio receiver
- [ ] Web receiver prototype
- [ ] shared protocol conformance tests

## v1.0 target

- [ ] Android-to-Android stable release
- [ ] Cross-platform receiver compatibility
- [ ] Opus audio stable
- [ ] parent volume sync stable
- [ ] documented OSR v1 protocol
- [ ] reproducible builds where practical
