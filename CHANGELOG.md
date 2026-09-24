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
  has a "not encrypted" claim confirmed before anything is written
  (PLAN-00002).

### Fixed
- Failures that were not the core's own crashed the app instead of
  reaching the status line; the choose screen kept the previous server's
  store after "Set up again"; file copies and config reads ran on the
  main thread (Review 00001, PLAN-00002 STEP-01).

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
