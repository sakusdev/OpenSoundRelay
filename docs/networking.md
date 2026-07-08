# Networking

OSR currently uses UDP for the low-latency prototype path.

## Crates

- `osr-core`: packet formats and deterministic state logic
- `osr-net`: UDP endpoint and packet routing
- `osr-cli`: command-line receiver, volume sender, and tone sender
- `osr-desktop`: GUI wrapper around the UDP endpoint

## UDP endpoint

`osr-net::UdpEndpoint` provides:

- bind to a local socket address
- send OSR audio frames
- send OSR volume commands
- receive and classify incoming OSR packets
- ignore malformed packets without crashing the caller

## Packet size

The current maximum UDP receive buffer is 1500 bytes. This is chosen to fit common MTU behavior and is enough for the v0.2 10ms mono PCM prototype.

For stereo PCM or larger frames, Opus should be used instead of increasing packet size too much.

## Recommended prototype settings

| Setting | Value |
|---|---|
| transport | UDP |
| sample rate | 48 kHz |
| channels | mono |
| PCM frame duration | 10 ms |
| default port | 40124 |
| receiver jitter buffer | 3 frames target |

## Security warning

The prototype does not authenticate peers yet. Do not use it on untrusted networks.

The v0.4 pairing milestone should add peer identity and reject unauthorized volume/audio packets.
