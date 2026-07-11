# Build and Test

## Rust core, UDP, CLI, and desktop GUI

Format:

```bash
cargo fmt --all
```

Test:

```bash
cargo test --workspace --all-targets
```

Build everything:

```bash
cargo build --workspace --all-targets
```

Build desktop GUI only:

```bash
cargo build -p osr-desktop --release
```

Run desktop GUI:

```bash
cargo run -p osr-desktop
```

Run CLI receiver:

```bash
cargo run -p osr-cli -- child --bind 0.0.0.0:40124
```

Run CLI tone sender to multiple targets:

```bash
cargo run -p osr-cli -- tone --target 127.0.0.1:40124,127.0.0.1:40125
```

Run CLI volume sender to multiple targets:

```bash
cargo run -p osr-cli -- host \
  --target 127.0.0.1:40124 \
  --target 127.0.0.1:40125 \
  --volume 0.5
```

## Android

The Android app now includes native AAudio and libopus code. Install:

- Android SDK 35
- NDK `27.0.12077973`
- CMake `3.22.1`
- JDK 17
- Gradle 8.10.2 or a compatible wrapper

Build debug APK:

```bash
sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
gradle :android:app:assembleDebug
```

CMake fetches the official Xiph Opus `v1.5.2` source during the first native configure and builds it into the APK. Subsequent Gradle builds reuse the native build cache.

The configured APK ABIs are `arm64-v8a` and `x86_64`.

## GitHub Actions

- `Rust Cross Platform`: Linux, Windows, macOS Rust formatting, Clippy, tests, and builds.
- `Android`: installs the pinned NDK/CMake toolchain, builds libopus and the AAudio bridge, then uploads a debug APK.
- `Release Builds`: manual/tag-triggered desktop binaries and Android APK artifacts.
