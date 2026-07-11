# LAN Discovery

OSR LAN discovery lets a sender find connectable receivers without manually entering an IP address.

## Transport

- IPv4 UDP broadcast
- discovery port: `40125`
- default audio port: `40124`
- OSR packet kind: `Hello` (`1`)
- no cloud service or account

A scanner sends a probe to `255.255.255.255:40125`. A receiver replies directly to the scanner's source address and port.

## Discovery payload

All multi-byte values are big-endian.

```text
0       wire version = 1: u8
1       message type: u8 (1=probe, 2=announcement)
2       role: u8 (0=idle, 1=sender, 2=receiver, 3=duplex)
3       reserved
4..8    capability flags: u32
8..10   audio UDP port: u16
10..12  UTF-8 device-name length: u16
12..20  scan nonce: u64
20..    UTF-8 device name, at most 96 bytes
```

Announcements echo the probe nonce. This prevents a scanner from mixing delayed responses from an older scan into the current result list.

## Capability flags

| Bit | Capability |
|---:|---|
| 0 | receives audio |
| 1 | sends audio |
| 2 | OSR stream-gain synchronization |
| 3 | native media/output-volume synchronization |
| 4 | adaptive latency correction |
| 5 | receiver tone controls |

## Behavior

- The scanner repeats its small probe during the scan window because some mobile Wi-Fi chipsets drop the first broadcast while waking.
- Results are deduplicated by advertised audio address.
- A receiver only advertises while receiver mode is active.
- Discovery does not prove peer identity.

## Network limitations

Broadcast discovery may be blocked by guest Wi-Fi isolation, enterprise access-point policy, a host firewall, VPN routing, or a network that separates clients into different VLANs. Manual target entry remains available as a fallback.

## Security

Discovery announcements are unauthenticated in the current prototype. Do not treat the displayed device name as a verified identity. Authenticated pairing is planned before a stable release.
