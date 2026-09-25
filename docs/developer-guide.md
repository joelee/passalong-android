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
| `just test-https` | the bridge against a real passalong-server, one per test, from the binary `PASSALONG_SERVER_BIN` names |
| `just build` | `assembleDebug`, which runs `scripts/rust-android.sh` first |
| `just rust` | the Rust library and bindings alone |
| `just install` | the debug APK onto a connected device or emulator |
| `just test-android` | instrumented tests on a connected device or emulator: TLS through the device's trust store; with a test server (`just test-android <url> <pin> <key> <text>`), also the whole setup-then-choose flow against it |
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

`rust/tests/server.rs` runs the bridge against a real passalong-server:
each test starts one from the binary `PASSALONG_SERVER_BIN` names, under
a home of its own, with a self-signed certificate, a workspace, and a key
(`rust/tests/support/mod.rs`, written from the server's documentation and
`--help` alone, since the server is AGPL and shares no code with this
repository). They are ignored without the variable:

```sh
PASSALONG_SERVER_BIN=../passalong-server/target/release/passalong-server just test-https
```

The Compose screens, the Android sinks, and `FfiCore` have no JVM tests;
they are excluded from the coverage figure and are on the backlog for
instrumented tests against passalong's Docker SSH server.

## The core's version

The bridge pins `passalong-core`, `passalong-ssh`, and `passalong-https`
exactly. Until v0.3.0 of the client is on crates.io, they come from the
client repository at one commit of its v0.3.0 branch (`git = …, rev = …`
in `rust/Cargo.toml`); to move the pin, change the three `rev` values
together and run `cargo update --manifest-path rust/Cargo.toml -p
passalong-core -p passalong-ssh -p passalong-https`, then `just check`.
Once published, replace the three with `version = "=0.3.0"`.

To build against a checkout at `../passalong` instead, add a git-ignored
`rust/.cargo/config.toml`:

```toml
[patch."https://github.com/joelee/passalong"]
passalong-core = { path = "../../passalong/crates/passalong-core" }
passalong-ssh = { path = "../../passalong/crates/passalong-ssh" }
passalong-https = { path = "../../passalong/crates/passalong-https" }
```

and never commit it or the `Cargo.lock` changes it causes.

## The emulator

```sh
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
sdkmanager --install emulator "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n passalong -k "system-images;android-36;google_apis;x86_64" -d pixel_7
emulator -avd passalong &          # add -no-window for a headless one
adb wait-for-device && just install
```

`avdmanager` may put the AVD under `$XDG_CONFIG_HOME/.android/avd`; when
`emulator` then says it is unknown, point it there with
`ANDROID_AVD_HOME`. From the emulator, this machine is `10.0.2.2`.

## Running it

`just install` puts the debug APK on the device `adb` sees. Point the
setup screen at passalong's Docker SSH server
([docs/docker-ssh-server-setup.md](https://github.com/joelee/passalong/blob/main/docs/docker-ssh-server-setup.md)
in the main repo), or at a passalong-server started as below, to try it
without a real server. Logs: `adb logcat -s passalong`.

### A passalong-server to try against

Build it from its repository (`cargo build --release --locked -p
passalong-server` in a clone of
[passalong-server](https://github.com/joelee/passalong-server)), then
give it a home of its own, so it never touches a server you run for real:

```sh
export HOME=$(mktemp -d); unset XDG_CONFIG_HOME XDG_DATA_HOME
P=/path/to/passalong-server
$P init
$P tls self-signed --host localhost --ip 127.0.0.1 --ip 10.0.2.2   # prints the pin
$P workspace create home
$P key create --workspace home --label phone                     # prints the key, once
$P serve                                                         # https://<this machine>:8443
```

In the app, the server is `https://10.0.2.2:8443` from the emulator, or
this machine's LAN address from a phone; paste the pin and the key.

The passalong CLI v0.3.0 can put items there from this machine, which is
how the instrumented end-to-end test gets something to list:

```sh
passalong init --url https://127.0.0.1:8443 --tls-pin <pin> --api-key-file <a file holding the key> --yes
printf 'hello' | passalong clipboard --stdin
passalong file report.pdf
just test-android https://10.0.2.2:8443 <pin> <key> hello   # ServerFlowTest on the emulator
```
