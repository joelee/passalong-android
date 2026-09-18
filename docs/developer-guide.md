# Developer guide

## Toolchain

- JDK 21 and Gradle 8.14.3, pinned in `.mise.toml` for
  [mise](https://mise.jdx.dev); every Gradle recipe runs through
  `mise exec`, so the system JDK does not matter.
- The Android SDK with `platforms;android-36`, `build-tools;36.0.0`,
  `platform-tools`, and an NDK (27 is known to work) under
  `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, or `~/Android/Sdk`, with
  `local.properties` pointing Gradle at it (`sdk.dir=...`; never
  committed). `scripts/rust-android.sh` finds the newest NDK there unless
  `ANDROID_NDK_HOME` names one.
- Rust stable (1.98 or later) with the targets `aarch64-linux-android`
  and `x86_64-linux-android`, and `cargo-ndk`. `uniffi-bindgen` is a
  binary of the bridge crate itself, so nothing else is installed.
- [`just`](https://github.com/casey/just) runs every task.

One-time setup:

```sh
just setup        # mise install, rustup targets, cargo-ndk
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

## Repository layout

```text
app/                  Compose UI, controllers, domain; Gradle module :app
  src/main/kotlin/io/github/joelee/passalong/
    domain/           Item, Picker (the TUI's state machine), Formats
    core/             Core and StoreHandle interfaces, CoreException
    data/             AppFiles, ItemActions, the clipboard and downloads sinks
    choose/, setup/   the controllers, one StateFlow each
    android/          FfiCore (the only user of the bindings), sinks, AppContainer, view models
    ui/               Root, the two screens, the theme
  src/test/kotlin/    JVM unit tests with fakes (support/Fakes.kt)
rust/                 passalong-ffi: the UniFFI bridge over passalong-core and passalong-ssh
scripts/              rust-android.sh: host build, bindings, per-ABI Android build
config/detekt/        detekt adjustments for Compose
docs/                 user and developer documentation; ideas, plans, reviews contracts
.agents/skills/       the report-number allocation skill the contracts use
```

## Recipes

| Recipe | Runs |
|---|---|
| `just fmt` / `just fmt-check` | ktlint and rustfmt, format or check |
| `just lint` | detekt and clippy (`-D warnings`) |
| `just test` | Gradle JVM unit tests, then `cargo test` in `rust/` |
| `just coverage` | Kover: prints the Kotlin line coverage and fails under 80 % |
| `just build` | `assembleDebug`, which runs `scripts/rust-android.sh` first |
| `just rust` | the Rust library and bindings alone |
| `just install` | the debug APK onto a connected device or emulator |
| `just test-android` | instrumented tests on a connected device (none yet) |
| `just check` | fmt-check, lint, test, coverage, build |

`.pre-commit-config.yaml` runs `just check` before every commit once
`pre-commit install` has been run; expect a cold run to take a few
minutes, as it builds the APK.

## Test-driven workflow

1. Write a failing test for the next behaviour; confirm it fails for the
   right reason.
2. Write the smallest code that passes it.
3. Refactor with the test green.
4. Run `just check` before committing.

Kotlin unit tests run on the JVM with fakes for every external interface:
`FakeCore` and `FakeStore` stand in for the bridge, `FakeClipboard` and
`FakeDownloads` for the device, a `TemporaryFolder` for the files
directory. `ChooseControllerTest` mirrors the TUI's `run_picker` tests:
the fake store records the status the list showed each time it was
called, as the TUI's fake screen records frames. The Rust bridge is
tested on the host with `cargo test` over the core's `local` backend in a
temporary folder, so no SSH server is needed; the one network test
connects to a closed local port.

The Compose screens, the Android sinks, and `FfiCore` have no JVM tests;
they are excluded from the coverage figure and are on the backlog for
instrumented tests against passalong's Docker SSH server.

## Hacking on the core

The bridge pins `passalong-core` and `passalong-ssh` exactly from
crates.io. To build against a checkout at `../passalong`, add a
git-ignored `rust/.cargo/config.toml`:

```toml
[patch.crates-io]
passalong-core = { path = "../../passalong/crates/passalong-core" }
passalong-ssh = { path = "../../passalong/crates/passalong-ssh" }
```

and never commit it or the `Cargo.lock` changes it causes.

## Running it

`just install` puts the debug APK on the device `adb` sees. Point the
setup screen at passalong's Docker SSH server
([docs/docker-ssh-server-setup.md](https://github.com/joelee/passalong/blob/main/docs/docker-ssh-server-setup.md)
in the main repo) to try it without a real server; from the emulator the
host machine is `10.0.2.2`. Logs: `adb logcat -s passalong`.
