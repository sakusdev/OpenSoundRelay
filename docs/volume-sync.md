# Parent Volume Synchronization

OSR supports parent-authoritative stream volume synchronization.

## What is synchronized?

OSR synchronizes the audio stream gain applied inside OSR.

This means every child can render the same stream at the same OSR-controlled gain, independent of local UI state.

## What is not synchronized?

OSR does not promise to force the operating system's global master volume.

Reasons:

- Android may restrict global volume changes depending on stream type and permissions.
- iOS does not allow arbitrary apps to force the user's hardware volume in the same way.
- Web browsers do not expose system volume control.
- Desktop platforms expose different device/session volume models.

OSR can still display a volume slider on the parent and make all children follow that stream gain exactly.

## Recommended app behavior

Child apps should expose two volume concepts:

1. **Parent volume**: read-only, synchronized from the parent.
2. **Local safety volume**: optional local limiter, never sent back as authoritative parent volume.

Effective output gain can be:

```text
effective_gain = parent_gain * local_safety_gain
```

For a strict sync mode, set local safety gain to 100% and hide child volume controls.

## Smooth changes

The protocol state is exact, but audio renderers should avoid clicks by ramping gain changes over a short duration.

Recommended ramp:

```text
5ms to 20ms linear ramp
```

The ramp must not change the accepted parent state. It is only an audio rendering detail.

## Stale command handling

Children must ignore old volume commands. This prevents delayed UDP packets from rolling the volume backward.

Accepted:

```text
epoch 1 seq 10 -> epoch 1 seq 11
```

Ignored:

```text
epoch 1 seq 10 -> epoch 1 seq 9
```

Accepted:

```text
epoch 1 seq 999 -> epoch 2 seq 1
```

## Multi-parent prevention

A production app should bind each stream to a parent peer identity. If another peer tries to send volume commands for the same stream, the child should reject them unless a new pairing/session is established.

The initial Rust core only handles stream state ordering. Authentication and pairing are planned later.
