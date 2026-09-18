# AGENTS.md - Kotlin/Gradle/Jetpack Compose over a Rust bridge

## Scope
Applies to this repo unless a deeper `AGENTS.md` overrides it.
`docs/ideas/`, `docs/plans/`, and `docs/reviews/` have their own
`AGENTS.md` contracts. Follow explicit user instructions first.

## Stack
- Kotlin Android app managed by Gradle (wrapper and version catalog);
  Jetpack Compose with Material 3; one `:app` module until that hurts.
- Storage, SSH, host-key pinning, the item schema, verification, and
  encryption come from the Rust core: `rust/` holds `passalong-ffi`, a
  UniFFI bridge over `passalong-core` and `passalong-ssh`, pinned exactly
  from crates.io. The app never reimplements store logic.
- Kotlin touches the generated bindings only in
  `app/src/main/kotlin/io/github/joelee/passalong/android/FfiCore.kt`;
  everything else depends on the `Core` and `StoreHandle` interfaces.
- `scripts/rust-android.sh` builds the library (cargo-ndk, `arm64-v8a` and
  `x86_64`, API 29) and generates the bindings with the crate's own
  `uniffi-bindgen` binary; Gradle's `preBuild` runs it. Commit
  `rust/Cargo.lock`.
- Format Kotlin with ktlint and Rust with rustfmt; lint with detekt and
  clippy.

## Non-negotiables
- TDD: write or update a failing unit test before production code; then
  implement; then refactor.
- Unit tests mock external interfaces: the core (`Core`, `StoreHandle`),
  the clipboard, the downloads, the filesystem, and time. The Rust bridge
  is tested on the host with `cargo test` over the `local` backend.
- Every feature includes integration-test coverage planned up front and
  implemented before completion.
- Coverage gate: Kotlin line coverage >= 80 % (Kover, `just coverage`),
  measured over the domain, data, core, choose, and setup packages; the
  generated bindings, the `android` and `ui` packages, and `MainActivity`
  are excluded because only instrumented tests can run them. Report
  coverage in completion notes.
- KDoc for public APIs and non-obvious logic; rustdoc likewise; avoid
  comments that restate code.
- Avoid `unsafe` in Rust; the one logcat call in `rust/src/logging.rs` is
  the justified exception, with a safety comment.
- No secrets in code, tests, docs, logs, or VCS.

## Commands
Use these unless project docs define stricter commands:
- Format: `just fmt`; check: `just fmt-check`
- Lint: `just lint`
- Tests: `just test`
- Coverage: `just coverage`
- Build: `just build` (`assembleDebug`, builds the Rust library first)
- All checks: `just check` (fmt-check, lint, test, coverage, build)
- Pre-commit must enforce the same checks; keep `.pre-commit-config.yaml`
  current and verify with `pre-commit run --all-files` when installed.

## Config and secrets
- The device's config is the core's `config.toml`, written by the setup
  screen into the app's private files directory; the identity key and the
  list cache live beside it. See `docs/configuration.md`.
- The app generates its own passphrase-less ed25519 key; no passphrase is
  ever stored or prompted.
- Signing material and `.env` stay out of VCS (git-ignored).
- Local core hacking uses a git-ignored `rust/.cargo/config.toml` path
  patch toward `../passalong`; never commit it.

## Observability
- The core logs through `tracing`; the bridge sends every record to logcat
  under the tag `passalong` with the core's syslog-like format and levels
  (`init_logging`). Kotlin logs through `android.util.Log` with the same
  tag when it must.
- Never log clipboard text, file contents, keys, or secrets.

## Docs to maintain
Update when behavior, commands, config, architecture, or user workflow
changes: `README.md`, `docs/architecture.md`, `docs/configuration.md`,
`docs/usage.md`, `docs/developer-guide.md`, `docs/backlog.md`.

## Backlog rules
- Track future work in `docs/backlog.md`; remove completed items; add
  obvious follow-ups under `Agent suggested next steps`.

## New feature workflow
1. Run `git status --short`. If non-empty, stop and report dirty files;
   do not edit.
2. Create branch: `git switch -c feature/<feature_name>`.
3. Add a `CHANGELOG.md` entry under `Unreleased`.
4. Create a plan in `docs/plans/` per its `AGENTS.md` contract, allocating
   the number with `.agents/skills/allocating-report-numbers/`.
5. Plan must include scope, TDD unit tests, integration tests,
   config/secrets impact, observability, docs, coverage target, risks.
6. Write failing unit tests first; mock external interfaces.
7. Implement minimal code; add integration tests; update docs and backlog.
8. Run `just check` and ensure coverage >= 80 %.
9. Report changed files, tests run, coverage result, docs updated, and
   backlog updates.

## Release workflow
1. SemVer `vMAJOR.MINOR.PATCH`; default PATCH. `versionName` follows the
   tag; `versionCode` increases by one per release.
2. All checks pass, coverage >= 80 %, branch CI passes; agent hands off
   with the evidence; user reviews and approves.
3. One finalising commit `release: vX.Y.Z - <top feature>`: rename
   `Unreleased` in `CHANGELOG.md`, bump the Gradle version fields, refresh
   the release notes.
4. Signing config lives outside VCS; the user builds the signed artifact.
   The pipeline is defined in a release plan before the first release.
