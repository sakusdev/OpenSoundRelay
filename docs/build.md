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

Run CLI tone sender:

```bash
cargo run -p osr-cli -- tone --target 127.0.0.1:40124
```

Run CLI volume sender:

```bash
cargo run -p osr-cli -- host --target 127.0.0.1:40124 --volume 0.5
```

## Android

Build debug APK:

```bash
gradle :android:app:assembleDebug
```

The CI workflow installs a Gradle distribution, so a Gradle wrapper is not required for GitHub Actions. For local builds, installing Gradle or adding a wrapper is recommended.

## GitHub Actions

- `Rust Cross Platform`: Linux, Windows, macOS Rust formatting, clippy, tests, and builds.
- `Android`: Android debug APK build.
- `Release Builds`: manual/tag-triggered desktop binaries and Android APK artifacts.
