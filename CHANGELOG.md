# Changelog

All notable changes to passalong-android are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project uses [Semantic Versioning](https://semver.org/).

## Unreleased

### Added
- A second kind of server: a workspace on a
  [passalong-server](https://github.com/joelee/passalong-server) v0.1.0,
  reached over HTTPS with an API key, as the passalong CLI v0.3.0 reaches
  one. The setup screen asks the kind of server first and, for a server,
  probes its certificate, has the pin confirmed (or the system trusted),
  takes the API key, asks the server about the key and the workspace, and
  has a "not encrypted" claim confirmed before anything is written. The
  choose screen works over the workspace unchanged, warns about an API key
  that expires within 14 days as `passalong check` does, and offers setup
  first when the server refuses the device for good (PLAN-00002).
- TLS to a server on Android in both of the CLI's modes: a pinned public
  key, or Android's own trust store through `rustls-platform-verifier`,
  initialised by the bridge's one JNI entry point at start (PLAN-00002
  STEP-03).
- Tests against a real passalong-server v0.1.0, started per test from
  `PASSALONG_SERVER_BIN` (`just test-https`, also in CI), and instrumented
  tests on a device or emulator (`just test-android`): TLS through the
  device's trust store, and the whole setup-then-choose flow against a
  server (PLAN-00002 STEP-04, STEP-06).

### Changed
- The bridge is built on passalong v0.3.0, taken from the client
  repository's branch at one commit until it is on crates.io
  (PLAN-00002 STEP-02).
- The app's version is 0.2.0; the bridge's is 0.2.0.

### Fixed
- Failures that were not the core's own crashed the app instead of
  reaching the status line; the choose screen kept the previous server's
  store after "Set up again"; file copies and config reads ran on the
  main thread (Review 00001, PLAN-00002 STEP-01).
- CI failed before its first check: the workflow's stand-in for `mise`
  passed `--` to `exec`, which `/bin/sh` on the runner runs as a command.

### Added, earlier
- The app, from scratch: a Kotlin and Jetpack Compose client over the
  passalong Rust core (`passalong-core` and `passalong-ssh` 0.2.0) through a
  UniFFI bridge, `rust/passalong-ffi` (PLAN-00001).
- Setup screen, what `passalong init` does: an ed25519 identity generated
  on the device, the server's host key fetched and its fingerprint
  confirmed before it is pinned, and the core's `config.toml` written to
  the app's private storage.
- Choose screen, what `passalong choose` does: the items newest first,
  opened from a fresh list cache with its age or from the server; filter by
  id, name or preview, device, or kind; tap loads (text to the clipboard,
  files and clipboard images to Downloads, verified against their SHA-256);
  long press shows the content, the metadata as `passalong get` prints it,
  or deletes after confirming; reload from the cache or the server; help;
  the same status messages as the TUI.
- Quality gates: ktlint, detekt, clippy, rustfmt, JVM unit tests, Rust
  host tests, a Kover line-coverage gate of 80 %, and a GitHub Actions
  workflow running `just check`.
