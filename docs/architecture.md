# Architecture

## Layers

```text
ui.setup.SetupScreen / ui.choose.ChooseScreen   Compose; draws state, sends events
        │
setup.SetupController / choose.ChooseController StateFlow<...State>; the TUI's run_picker and init
        │
domain.Picker                                   pure state machine: modes, filter, selection, status
data.ItemActions, data.AppFiles                 load/cat over the store; where files live
core.Core, core.StoreHandle                     Kotlin interfaces; tests use fakes
        │
android.FfiCore                                 the only file that imports the UniFFI bindings
        │ JNA
rust/ passalong-ffi                             UniFFI exports; tokio; logcat
        │
passalong-core, passalong-ssh (crates.io =0.2.0) → SSH/SFTP server
```

The app never reimplements store logic. Every call that reads or changes
the store is a Kotlin `suspend` function backed by an async Rust function
(`#[uniffi::export(async_runtime = "tokio")]`), so nothing blocks the main
thread and the bridge needs no threads of its own beyond tokio's. The
few blocking calls the app makes itself, reading the config and the list
cache, making a key, copying a download into MediaStore, run on
`Dispatchers.IO` (`ActionTargets.io`, `SetupController.io`; the tests
pass `Dispatchers.Unconfined`).

## The bridge surface (`rust/src`)

| Export | What it wraps |
|---|---|
| `core_version`, `bridge_version` | The pinned core version, and this crate's |
| `init_logging(level)` | `passalong_core::telemetry::init` with a writer that sends each record to logcat (tag `passalong`), or to standard error on other hosts. Idempotent |
| `fetch_host_key(host, port, timeout)` | `passalong_ssh::fetch_host_key`: the server's key and fingerprint, not yet trusted |
| `probe_server(url)` | `passalong_https::tls::presented`: the pin of the certificate a passalong-server presents, and whether the system trusts it, from a handshake that stops before any request |
| `generate_identity(path, comment, replace)` | An ed25519 key in OpenSSH format, mode 0600, with `.pub` beside it; refuses to replace an existing key unless told |
| `render_config(answers)` | `passalong_core::config::render`, the file `passalong init` writes |
| `validate_config(text, home, hostname)` | `passalong_core::config::parse` under the app's environment |
| `open_store(text, home, hostname, cache_file)` | Parses the config, opens the backend through the core's `BackendRegistry` with `ssh` registered, and the store as its header says (plaintext or encrypted) |
| `PassalongStore` | `description`, `has_cache`, `cached`, `list(reuse)`, `get_meta`, `read_text`, `download(id, dir)`, `delete` |

`AppEnv` is the core's `EnvProvider` for the app: `HOME` is the app's
private files directory and nothing else from the process environment is
read, so `~/Downloads`, `~/PassAlong`, and the key file all expand inside
the app's storage, and the device name defaults to what the app passes
(`Build.MODEL`, reduced to safe characters).

Errors cross the bridge as one flat enum, `PassalongError`, grouped by
what the app can do: `Config`, `Connect`, `NotFound`, `Encryption`,
`Store`, `Io`. Messages are the core's own, which say how to fix things.

### The list cache

The CLI's `choose` opens from `serve`'s list cache when it was checked
within twice `serve.list_cache_check_secs`. The app has no `serve`, so the
bridge keeps the cache itself with the core's `ListCache`, in
`list-cache.json` in the app's files: `cached()` returns it with its age
while it is fresh; `list(true)` refreshes it (one id listing plus the
metadata of new ids, `r`); `list(false)` reads every item again and
rewrites it (`R`); `delete` drops the item from it. As in the CLI, only
`ssh` stores with `serve.list_cache` on are cached, under the same
identity string, so a cache written by the CLI for the same store and key
would be usable.

### TLS on Android

`passalong-https` verifies a server's certificate in one of two ways, as
the CLI does: against the `tls_pin` when the config has one (that public
key and nothing else, which is how a self-signed server is reached), or
through the operating system's trust store when it has none, using
`rustls-platform-verifier`. On Android that crate calls into the JVM, so
it must be handed the app's `Context` once, before the first connection.
That is the bridge's one JNI entry point, `rust/src/android_tls.rs`
(`NativeTls.init(context)` in Kotlin, called by `PassalongApp` at
start), and the verifier's Kotlin half, `org.rustls:rustls-platform-verifier`,
is on the app's classpath at exactly the version of the
`rustls-platform-verifier-android` crate in `rust/Cargo.lock`, which
`app/build.gradle.kts` reads. The probe `init` makes before any request
(`probe_server`) uses the same verifier to say whether the system trusts
the certificate. Without the initialisation, pinned servers still work
and the probe fails; `PassalongApp` logs that once.

## The picker

`domain.Picker` is the TUI's `choose/state.rs` translated: the same
modes (`Browse`, `Filter`, `ConfirmDelete`, `Help`, `Details`), the same
outcomes (`Continue`, `Quit`, `Act(Load|Cat)`, `Delete`, `Details`,
`Reload`, `ReloadServer`), the same filter (id, name or preview, device,
kind, ignoring case), and the same status texts. Keys become events:

| TUI key | `PickerEvent` | Screen |
|---|---|---|
| Up, Down | `Select(index)` | tap or long press on a row |
| `/`, typing, Enter, Esc | `StartFilter`, `FilterChanged`, `KeepFilter`, `ClearFilter` | search icon, the filter field, its Done key and clear button |
| Enter | `Load` | tap, or *Load* in the row menu |
| `c` | `Cat` | *Show content* in the row menu |
| `g` | `Details` | *Details* in the row menu |
| `d`, `y` | `RequestDelete`, `ConfirmDelete`, `CancelDelete` | *Delete* in the row menu, then the dialog |
| `r`, `R` | `Reload`, `ReloadServer` | refresh icon; *Read from the server* in the menu |
| `?` | `Help` | *Help* in the menu |
| Esc | `Back` | the back gesture: closes what is open, or quits |
| `q`, Ctrl-C | `Quit` | *Quit* in the menu |

`ChooseController` is `run_picker`: it connects first (as the TUI does,
since the actions need the store), shows a fresh cache with its age or
`Loading...` then the list, and handles outcomes: `Details` reads
`get_meta` and shows it as `passalong get` prints it; `Delete` shows
`Deleting <id>...`, deletes, reads the list again with `Reloading...`, and
says `deleted <id>`; `Reload` shows the cache while it is fresh, otherwise
reads with reuse; `ReloadServer` reads everything. Failures land in the
status line (`cannot delete <id>: ...`) and the list stays open; so do
failures that are not the core's, such as Downloads refusing a file
(`attempt` in `ChooseController` describes any exception but a
cancellation), and a view model's scope carries a
`CoroutineExceptionHandler` as the last line, so nothing ends the
process. Events are dropped while the store is busy, as keys wait in the
TUI: a touch during a load does nothing.

Two things differ because a phone has no terminal. `Load` and `Cat` do
not close the list: loading reports in a snackbar, and the content opens
in a full-screen view over the list. And a clipboard image is loaded as a
file into Downloads rather than onto the clipboard.

`ItemActions` is `load` and `cat`: text is read verified and put on the
clipboard; a file is downloaded, verified, by the core into the app's
`~/Downloads` staging folder under a free name, then published to the
device's Downloads through MediaStore (no storage permission on API 29+,
never replacing a file), and the staged copy removed.

## Setup

`SetupController` is `init`: it makes the device a key at once (kept on
later visits), takes the server fields with `init`'s defaults, fetches the
host key, and shows the fingerprint with the same words as the CLI. Only
confirming renders the config, validates it back, and writes it; declining
writes nothing. The choose screen then connects, which is `init`'s
connection test; a failure shows the core's message with *Try again* and
*Set up again*. A setup that completes from *Set up again* makes the
retained choose controller `reconnect()`: it forgets the store, the list,
the filter, and any open view, and connects to whatever the config now
names, so nothing of the previous server is shown or acted on.

## Build pipeline

`scripts/rust-android.sh` builds the crate on the host, generates the
Kotlin bindings from that library with the crate's own `uniffi-bindgen`
binary (`cargo run --features cli --bin uniffi-bindgen`, so the bindgen
and the library never disagree on the uniffi version), then builds release
libraries for `arm64-v8a` and `x86_64` with `cargo-ndk` into
`app/src/main/jniLibs/`. Both outputs are git-ignored; Gradle's `preBuild`
runs the script with the Rust sources as inputs, so an unchanged bridge is
not rebuilt.

The upstream design references live in
[passalong's architecture doc](https://github.com/joelee/passalong/blob/main/docs/architecture.md).
