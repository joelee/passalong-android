---
title: "Delivery Plan 00002: Passalong Server Backend"
aliases:
  - "Plan 00002"
tags:
  - delivery-plan
  - implementation
  - opencode
type: delivery-plan
plan_id: "PLAN-00002"
plan_status: approved                 # draft | approved | cancelled
plan_kind: initial                 # initial | superseding
created_at: "2026-09-24T23:19:01Z"
approved_at: "2026-09-24T23:27:02Z"
planner_agent: "Claude Code"
planner_model: "anthropic/claude-fable-5-1"
triggered_by: user                 # user | agent:<agent-name>
request_kind: direct               # idea | review | idea-and-review | direct | unplanned-query
repository: "joelee/passalong-android"
baseline_branch: "main"
baseline_commit: "62e2e21339bfb2f7c2830eaa7add45dfae2ef5bf"
source_ideas: []
source_reviews:
  - "docs/reviews/00001-Android_Client_Bootstrap_And_Choose.md"
previous_plan: null
requirements_count: 7
steps_count: 8
acceptance_criteria_count: 11
blocking_decisions: 0
build_ready: true
web_research_used: false
confidence: medium                # high | medium | low

# Builder-maintained front matter. Builder may update only these keys after
# explicit user approval; Delivery Planner initializes them.
implementation_status: in-progress # not-started | in-progress | blocked | completed | abandoned
builder_agent: "Claude Code"
builder_model: "anthropic/claude-fable-5-1"
execution_branch: "feature/00002-passalong-server"
execution_started_at: "2026-09-24T23:32:29Z"
execution_updated_at: "2026-09-24T23:53:09Z"
execution_completed_at: null
current_step: "PLAN-00002-STEP-05"
---

# Delivery Plan 00002: Passalong Server Backend

> [!abstract] Plan status: `approved`
> Give the Android client a second kind of server: a workspace on a
> [passalong-server](https://github.com/joelee/passalong-server) v0.1.0,
> reached over HTTPS with an API key, exactly as the passalong CLI v0.3.0
> reaches one, beside the SSH server it has today. The setup screen gains
> the CLI's `init` flow for a server (URL, certificate pin, API key,
> workspace claim), and the choose screen works over it unchanged. Before
> that, the two Major findings of Review 00001 are fixed, since the
> feature runs through the very path one of them breaks. Waits on the
> user's approval; no blocking decisions.

## 1. Objective and outcome

1. Review 00001's findings are fixed with regression tests, so the
   feature builds on a sound base (REV-00001-MAJ-01, MAJ-02, MED-01,
   LOW-01).
2. The Rust bridge is built on passalong-core, -ssh, and the new
   passalong-https at v0.3.0, taken from the client repository's git
   branch until v0.3.0 is on crates.io, and registers the `https` kind.
3. TLS to a server works on Android in both of the CLI's modes: a pinned
   public key (self-signed servers), and the operating system's trust
   store (publicly trusted certificates), which on Android needs
   `rustls-platform-verifier`'s Kotlin component and a JNI initialisation.
4. The setup screen asks the kind of server first, as `init` does, and
   for a passalong-server does what `init` does: probes the certificate
   without sending a request, shows its pin, has the user confirm it (or
   trust the system when it vouches), takes the API key without showing
   it, asks the server about the key and the workspace, has the user
   confirm a "not encrypted" claim, and only then saves the key and the
   config.
5. The choose screen lists, loads, shows, and deletes items of a
   workspace, with the list cache, and reports a refused, expired, or
   revoked key as the CLI does.
6. Integration tests run the bridge against a real passalong-server,
   started per test, locally and in CI.

## 2. Source traceability

| Requirement | Source | Source location | Interpretation |
|---|---|---|---|
| PLAN-00002-REQ-01 | Review | `docs/reviews/00001-Android_Client_Bootstrap_And_Choose.md` MAJ-01, MAJ-02, MED-01, LOW-01 | Fix all four before building on the code (the review's hand-off) |
| PLAN-00002-REQ-02 | User; Repository | Request of 2026-09-25; `../passalong` `9ed0d4d` on `feature/v0.3.0-passalong-server`: `crates/passalong-https/src/lib.rs` (`register`), `Cargo.toml` (version 0.3.0), `CHANGELOG.md` Unreleased | The bridge takes the 0.3.0 crates from git (D-01) and adds the `https` backend |
| PLAN-00002-REQ-03 | User; Repository | D-02; `../passalong/crates/passalong-https/src/tls.rs` (`client_config`, `presented`), `rustls-platform-verifier` 0.7.0 README "Android" | Both trust modes on Android |
| PLAN-00002-REQ-04 | User; Repository | `../passalong/crates/passalong-cli/src/commands/init.rs` `run_https`, `confirm_pin`, `ask_api_key`; `docs/usage.md` "init for a passalong-server" | The app's setup does what `run_https` does, screen for prompt |
| PLAN-00002-REQ-05 | User; Repository | `../passalong/docs/usage.md` `choose`, `check` (api key line); `passalong-https/src/store.rs` `store_error` (`StoreError::Denied`); `passalong-core/src/cache.rs` `store_identity` for `https` | Choose over a server; `Denied` shown as a failure that setup fixes; cache as the CLI's |
| PLAN-00002-REQ-06 | User; Repository | D-04; `../passalong-server/docs/handover-notes-v0.3.0-client.md` "A server to build against"; `../passalong/crates/passalong-https/tests/support/mod.rs` (`PASSALONG_SERVER_BIN`) | Spawn the server binary per test when `PASSALONG_SERVER_BIN` is set; CI builds v0.1.0 |
| PLAN-00002-REQ-07 | Repository | `AGENTS.md` "Docs to maintain", "Release workflow" | Docs, CHANGELOG, backlog, versions |

## 3. Repository baseline

| Field | Value |
|---|---|
| Branch | `main` |
| HEAD | `62e2e21339bfb2f7c2830eaa7add45dfae2ef5bf` ("Initial Commit") |
| Worktree | clean; Review 00001 committed as the second commit on `main` |
| Client baseline | `joelee/passalong` `9ed0d4defca5558c85388e73b89fc777e9c06a13` on `feature/v0.3.0-passalong-server` (workspace version 0.3.0, PLAN-00010 completed; not merged, not tagged, not on crates.io, which holds 0.2.0 and 0.2.1) |
| Server baseline | `joelee/passalong-server` tag `v0.1.0` (`8c75056`), API v1, binary at `../passalong-server/target/release/passalong-server` |
| Gates at baseline | `just check` green: 38 JVM tests, 21 Rust tests, Kover 98.9 % |

## 4. Scope

### In scope

- Review 00001 fixes and their tests.
- `rust/`: crates at 0.3.0 from git; `passalong_https::register`; new
  exports for the server setup; `Denied` as its own error kind; the JNI
  entry point that initialises the platform verifier; Android build.
- Gradle: the `rustls-platform-verifier` Kotlin component located
  through `cargo metadata`, as its README prescribes; a keep rule.
- Kotlin: server kind in `SetupState`/`SetupController`/`SetupScreen`;
  `Core` and fakes extended; `ChooseController` shows a server's
  description and key expiry; `Denied` handling.
- Integration tests over a spawned `passalong-server`; `just test-https`;
  CI builds the server from its tag.
- Docs, CHANGELOG, backlog, version 0.2.0 of app and bridge.

### Out of scope

- Encrypting or joining an encrypted workspace from the app (the CLI's
  `encrypt`, `--join`, `--rotate`, `--recover`); a sealed workspace is
  reported with the core's message as before. Stays on the backlog.
- Sending items to a server (uploads); the app still only reads and
  deletes.
- `check`'s other lines (probe write, quota) as a screen of their own.
- Switching the bridge back to crates.io `=0.3.0`: a one-line follow-up
  once the user publishes, on the backlog.

## 5. Constraints and preserved decisions

- No code from passalong-server enters this repository: it is
  AGPL-3.0-or-later; the app is Apache-2.0. Only the contract is used, and
  only through `passalong-https` (Apache-2.0), which was written from the
  contract alone. Tests may run the server binary; they may not link it.
- The bridge pins exact commits or versions; Kotlin touches the bindings
  in `FfiCore.kt` alone; every store call stays a `suspend` function.
- TLS verification has no off switch, on the phone as in the CLI.
- The API key is typed into the app and lives in `api.key` in the app's
  files, owner-only, never in the config, logs, or state that is backed
  up (`allowBackup="false"` stays).
- `unsafe` stays denied in Rust except where a safety comment justifies
  it: the logcat call, and now the one JNI entry point (REQ-03).
- The picker, its statuses, and the choose flow do not change.

## 6. Assumptions

- A-01: `passalong-https` cross-compiles for `aarch64-linux-android` and
  `x86_64-linux-android` with `ring`, as the client's own CI checks for
  aarch64 (`just android-check` in `../passalong`). Verified in STEP-02
  before anything depends on it.
- A-02: `rustls-platform-verifier` 0.7.0's Android component is found
  through `cargo metadata` as its README says, and its `init_hosted`
  takes the `jni` 0.22 `Env` and a `Context`; the bridge adds `jni =
  "0.22"` for that one function.
- A-03: A test server started by the bridge's tests behaves as
  `../passalong/crates/passalong-https/tests/support/mod.rs` shows: `init`
  with `--data-dir`, `tls self-signed`, `workspace create`, `key create`,
  `serve`, under a private `HOME` and `PASSALONG_SERVER_CONFIG_FILE`.
- A-04: The API key is pasted or typed by the user; there is no QR or
  link flow in this milestone.

## 7. Decisions and blockers

| ID | Decision | Options | Recommendation | Status |
|---|---|---|---|---|
| D-01 | Source of the 0.3.0 crates | git dependency on the branch at `9ed0d4d`; wait for crates.io; path to `../passalong` | git, pinned by `rev`; back to crates.io `=0.3.0` once published | Resolved by the user, 2026-09-25 |
| D-02 | TLS modes on Android | pin and OS trust store; pin only | both, with the platform verifier's Kotlin component | Resolved by the user, 2026-09-25 |
| D-03 | Review 00001 | first step of this plan; separate plan; defer | first step | Resolved by the user, 2026-09-25 |
| D-04 | Server for tests | spawn the binary per test (`PASSALONG_SERVER_BIN`); Docker in CI only; none | spawn per test; CI builds v0.1.0 | Resolved by the user, 2026-09-25 |
| D-05 | Where `api.key` lives | beside the config in the files dir (`api.key`), as `init` places it beside `config.toml`; elsewhere | `<files>/api.key`, named in `server.https.api_key_file` | Resolved by the planner |
| D-06 | Server kind control | segmented buttons SSH / passalong-server at the top of setup; a separate first screen | segmented buttons, defaulting to SSH as `init` defaults | Resolved by the planner |
| D-07 | Retrying the pin | the app shows the presented pin and has the user paste the operator's, up to three times (`confirm_pin`); a "matches" yes/no as for SSH | paste and compare, as the CLI: a pin is easy to paste on a phone and a yes/no would let a spoofed server through on a glance | Resolved by the planner |

No blocking decisions.

## 8. Affected architecture and components

| Area | Components |
|---|---|
| Rust bridge | `rust/Cargo.toml` (git deps, `jni`, `passalong-https`), `rust/src/error.rs` (`Denied`), `rust/src/setup.rs` (server exports), `rust/src/store.rs` (`register`), new `rust/src/android_tls.rs` (JNI init), `rust/tests/server.rs` + `rust/tests/support/mod.rs` (spawned server) |
| Gradle | `app/build.gradle.kts` (platform-verifier classes from `cargo metadata`, keep rule, version), `gradle/libs.versions.toml` |
| Kotlin core/data | `core/Core.kt` (`ServerKind`, `PresentedCertificate`, `ServerInfo`, `HttpsSetupAnswers`, new functions), `data/AppFiles.kt` (`apiKey`), `data/ItemActions.kt` (non-core failures) |
| Controllers | `setup/SetupController.kt` (kind, https flow), `choose/ChooseController.kt` (reconnect, failure handling, description, key expiry) |
| Android glue | `android/FfiCore.kt`, `android/AppContainer.kt` (`PassalongApp` JNI init, reconnect hook), `android/NativeTls.kt` (the JNI declaration), `ui/Root.kt`, `ui/setup/SetupScreen.kt`, `ui/choose/ChooseScreen.kt` |
| Tests | `support/Fakes.kt`, `SetupControllerTest`, `ChooseControllerTest`, `ItemActionsTest`; Rust unit and integration tests |
| Build and CI | `justfile` (`test-https`), `.github/workflows/ci.yml` (server build, `PASSALONG_SERVER_BIN`), `scripts/rust-android.sh` |
| Docs | `README.md`, `docs/{architecture,configuration,usage,developer-guide,backlog}.md`, `CHANGELOG.md`, `NOTICE` |

## 9. Requirement catalogue

### PLAN-00002-REQ-01 — Review 00001 fixed

- **Requirement:** Non-`CoreException` failures in actions, connecting,
  and writing the config reach the status line or the error field, never
  crash (MAJ-01); the choose screen reconnects after setup completes
  (MAJ-02); config and cache reads, key generation, and the Downloads
  copy run off the main thread (MED-01); the two misleading comments are
  corrected (LOW-01).
- **Rationale:** The review's verdict is `request-changes`; MAJ-02 is on
  the path this feature adds to.
- **Source:** Review 00001.
- **Acceptance evidence:** New cases in `ChooseControllerTest`
  (`FakeDownloads.fail = IOException`, reconnect after a config change)
  and `SetupControllerTest` (`writeConfig` fails); `just check` green.

### PLAN-00002-REQ-02 — The bridge on 0.3.0 with the `https` backend

- **Requirement:** `rust/Cargo.toml` takes `passalong-core`,
  `passalong-ssh`, and `passalong-https` from
  `git = "https://github.com/joelee/passalong", rev = "9ed0d4d…"`;
  `open_store` registers `https`; `StoreError::Denied` crosses as
  `PassalongError::Denied`; the `KeyFileError` message changes are
  absorbed; the crate builds for both Android ABIs.
- **Rationale:** REQ-04 to REQ-06 need the backend; D-01.
- **Source:** `../passalong` at `9ed0d4d`.
- **Acceptance evidence:** `cargo test` in `rust/`; `just rust`; the
  version test says `0.3.0`.

### PLAN-00002-REQ-03 — TLS on Android in both modes

- **Requirement:** The app initialises `rustls-platform-verifier` at
  start (JNI `init_hosted` through one exported native function called
  from `PassalongApp.onCreate`), so `tls::presented` and an unpinned
  `Client` work on a phone; the Kotlin component
  (`rustls-platform-verifier-android`'s `classes.jar`) is on the
  classpath, located through `cargo metadata`; a keep rule protects it
  from shrinking. With a pin, the pinned verifier alone is used.
- **Rationale:** D-02; without the initialisation the platform verifier
  fails on Android and even pin discovery would fail.
- **Source:** `rustls-platform-verifier` 0.7.0 README "Android";
  `passalong-https/src/tls.rs`.
- **Acceptance evidence:** On a device or emulator, the setup screen
  probes a self-signed test server (`trusted = false`, pin shown) and a
  publicly trusted URL (`trusted = true`); `adb logcat` shows no JNI
  error. Recorded in the work log with the device.

### PLAN-00002-REQ-04 — Setup for a passalong-server as `init`

- **Requirement:** The setup screen offers SSH or passalong-server. For
  a server: URL (validated by rendering and parsing the config before any
  connection, as `run_https` does); *Connect* probes the certificate and
  shows "The server at … presented a certificate whose public key has the
  pin …"; when the system trusts it, the user may go without a pin or pin
  it; otherwise the user pastes the operator's pin and it must equal the
  presented one (three tries); the API key is typed with hidden text and
  validated by shape (`pal_<id>_<secret>`), or a saved `api.key` is used;
  the app asks the server (`getViewer`, `getWorkspace`) and shows
  "connected to passalong-server <version>: API key <label> (<role>),
  expires <date>"; when the workspace is plaintext and the device holds
  no store key, the user confirms "Is it meant to be unencrypted for
  now?"; declining anywhere writes nothing; confirming saves `api.key`
  (owner-only) then `config.toml` rendered by `render_https`.
- **Rationale:** The stated feature; the CLI's flow is the specification.
- **Source:** `init.rs` `run_https`; `docs/usage.md`.
- **Acceptance evidence:** `SetupControllerTest` cases mirroring
  `init.rs`'s https tests (`https_args…`, trusted certificate needs no
  pin, mismatch refused, declining the claim writes nothing, saved key
  reused); the Rust tests of the new exports against the spawned server.

### PLAN-00002-REQ-05 — Choose over a server

- **Requirement:** With a server config, the choose screen connects,
  shows `https <url>` as the store, uses the list cache under the CLI's
  identity, and lists, loads, shows, and deletes; a `Denied` error
  (expired, revoked, or wrong key) shows the core's message with *Set up
  again*; a read-only key's refused delete lands in the status line; the
  connection status names the key's expiry when it is within 14 days,
  as `check` warns.
- **Rationale:** The milestone is `choose` over both server kinds.
- **Source:** `docs/usage.md` `choose`, `check`; `store_error`.
- **Acceptance evidence:** Rust integration tests through `open_store`
  with `kind = "https"`; `ChooseControllerTest` for `Denied` and the
  expiry line.

### PLAN-00002-REQ-06 — Integration tests against a real server

- **Requirement:** `rust/tests/server.rs` starts `passalong-server` from
  `PASSALONG_SERVER_BIN` per test on a free port with a self-signed pair,
  a workspace, and a key, and exercises the bridge's exports and store
  handle; the tests are `#[ignore]` without the variable; `just
  test-https` runs them; CI builds the server from tag `v0.1.0` with
  `cargo install --git … --tag v0.1.0 --locked` (cached) and runs them.
- **Rationale:** D-04; the hand-over notes' advice that a mock agrees
  with whatever one believes.
- **Source:** Hand-over notes; `passalong-https/tests/support/mod.rs`.
- **Acceptance evidence:** `just test-https` green locally with
  `PASSALONG_SERVER_BIN=../passalong-server/target/release/passalong-server`;
  the CI job green.

### PLAN-00002-REQ-07 — Docs and records

- **Requirement:** README, architecture, configuration (the server's
  files: `api.key`; `[server.https]` keys), usage (the server setup
  flow, screen by screen), developer guide (`just test-https`, the git
  dependency, the platform-verifier component), backlog (crates.io
  switch, encryption over a server, uploads), CHANGELOG Unreleased,
  NOTICE (rustls-platform-verifier, MIT/Apache-2.0), app `versionName`
  0.2.0 / `versionCode` 2, bridge 0.2.0.
- **Rationale:** AGENTS.md.
- **Source:** `AGENTS.md`.
- **Acceptance evidence:** The files; `just check`.

## 10. Delivery strategy

Bottom up again, each layer proven before the next depends on it, and
the two unknowns first: STEP-01 clears the review so the base is sound;
STEP-02 proves the 0.3.0 crates build for Android; STEP-03 proves TLS on
a device early, since it is the one part no host test can cover. Then
the bridge exports (STEP-04) with tests over a real server, the Kotlin
flow with fakes (STEP-05), choose (STEP-06), the CI integration
(STEP-07), and the records (STEP-08). Every step ends with `just check`
green; STEP-04 and STEP-06 also with `just test-https`.

## 11. Detailed implementation steps

### PLAN-00002-STEP-01 — Review 00001 fixes

- **Objective:** REQ-01.
- **Requirements:** `PLAN-00002-REQ-01`
- **Depends on:** None
- **Affected components:** `choose/ChooseController.kt`,
  `setup/SetupController.kt`, `data/ItemActions.kt`, `android/Sinks.kt`,
  `android/AppContainer.kt`, `ui/Root.kt`, their tests
- **Preconditions:** Review 00001 committed; clean worktree; branch
  `feature/00002-passalong-server`.
- **Test or evidence first:** `ChooseControllerTest`: a load whose
  `FakeDownloads.fail = IOException` ends in `cannot load <id>: …` with
  `busy` false; `reconnect()` after `files.writeConfig` of another server
  lists the new store (a second `FakeStore` with another description).
  `SetupControllerTest`: `writeConfig` throwing lands in `error`.
- **Implementation tasks:**
  1. `ChooseController`: catch `Exception` beside `CoreException` in
     `act`, `connect`, `details`, `delete`, `reload`; `busy {}` reports
     an escaped failure in the status line; a `CoroutineExceptionHandler`
     on both view-model scopes in `AppContainer`.
  2. `ChooseController.reconnect()`: resets picker, viewer, message, and
     store, then `connect()`; `Root` calls it when returning from setup
     with `done`.
  3. `withContext(Dispatchers.IO)` (an injectable dispatcher, `Unconfined`
     in tests) around `files.readConfig`, `store.cached()`, the publish
     copy and staged deletion, and `SetupController`'s key generation,
     which moves out of the constructor into a `start()` with a
     "generating…" state.
  4. `MediaStoreDownloads.publish` deletes the pending row on any failure.
  5. Reword the two comments.
- **Documentation/configuration/operations:** `docs/architecture.md`
  (reconnect, dispatcher); the review's `status` stays `open` until a
  re-review (immutable report).
- **Verification:** `just check`.
- **Completion criteria:** The four findings' tests pass; coverage held.
- **Rollback or recovery:** Revert the step's commit.
- **Builder stop conditions:** A finding needs a design change beyond
  the review's recommendation.

### PLAN-00002-STEP-02 — The bridge on 0.3.0 with `https`

- **Objective:** REQ-02.
- **Requirements:** `PLAN-00002-REQ-02`
- **Depends on:** STEP-01
- **Affected components:** `rust/Cargo.toml`, `rust/Cargo.lock`,
  `rust/src/{lib,error,store,setup}.rs`, `scripts/rust-android.sh`
- **Preconditions:** `9ed0d4d` reachable on GitHub (it is pushed).
- **Test or evidence first:** `versions_are_the_pinned_ones` expects
  `0.3.0` and `passalong_https::VERSION`; an `error.rs` case maps
  `StoreError::Denied` to `PassalongError::Denied`; a `store.rs` case
  opens a `kind = "https"` config without a key file and gets `Config`
  ("no API key in …").
- **Implementation tasks:**
  1. `passalong-core = { git = "https://github.com/joelee/passalong", rev = "9ed0d4defca5558c85388e73b89fc777e9c06a13", default-features = false }`, same for `-ssh` and `-https`; `russh` requirement kept aligned.
  2. `passalong_https::register(&mut registry)` in `open_store`;
     `describe` for `https`.
  3. `PassalongError::Denied`; `From<StoreError>`.
  4. Fix what the `KeyFileError`/`ApiKeyError` changes break.
  5. `just rust`: both ABIs build (A-01).
- **Documentation/configuration/operations:** `docs/developer-guide.md`
  "Hacking on the core" now describes the git dependency and the
  `rev` bump procedure; backlog: switch to crates.io.
- **Verification:** `cargo test`, `cargo clippy -D warnings`, `just rust`,
  `just build`.
- **Completion criteria:** Green; APK holds the new libraries.
- **Rollback or recovery:** Revert to the crates.io pins.
- **Builder stop conditions:** `passalong-https` or a dependency does not
  cross-compile (A-01 false): stop and report; the fallback is a client
  fix upstream.

### PLAN-00002-STEP-03 — TLS on Android: the platform verifier

- **Objective:** REQ-03; the spike that de-risks the plan.
- **Requirements:** `PLAN-00002-REQ-03`
- **Depends on:** STEP-02
- **Affected components:** `rust/Cargo.toml` (`jni = "0.22"`, Android
  only), new `rust/src/android_tls.rs`, `rust/src/lib.rs`,
  `app/build.gradle.kts`, new `android/NativeTls.kt`,
  `android/AppContainer.kt` (`PassalongApp.onCreate`), `proguard-rules.pro`
- **Preconditions:** STEP-02 builds for Android.
- **Test or evidence first:** A host unit test of a new export
  `probe_server(url)` against the spawned server (pinned, `trusted =
  false`) proves the Rust side; the Android side has no host test: the
  evidence is the on-device check below, recorded in the work log.
- **Implementation tasks:**
  1. `#[unsafe(no_mangle)] pub extern "system" fn Java_io_github_joelee_passalong_android_NativeTls_init(env: Env, _class: JClass, context: JObject) -> jboolean`
     calling `rustls_platform_verifier::android::init_hosted`, under
     `#[cfg(target_os = "android")]`, `#[allow(unsafe_code)]` with a
     safety comment; returns whether it succeeded.
  2. `NativeTls` Kotlin object with `external fun init(context: Context): Boolean`
     and `System.loadLibrary("passalong_ffi")` (JNA loads it too; the
     second load is a no-op); `PassalongApp.onCreate` calls it after
     `initLogging` and logs a failure.
  3. Gradle: a `findRustlsPlatformVerifierClasses()` in
     `app/build.gradle.kts` running `cargo metadata --filter-platform
     aarch64-linux-android --manifest-path rust/Cargo.toml`, and
     `implementation(files(...))`; the `-keep` rule; a note that the
     component is Apache-2.0/MIT in `NOTICE`.
  4. On the emulator (`just install`): probe a local self-signed
     test server through `10.0.2.2` and a public HTTPS site; both must
     answer, the first with `trusted = false`.
- **Documentation/configuration/operations:** `docs/architecture.md`
  "TLS on Android"; `docs/developer-guide.md` (emulator steps).
- **Verification:** `just check`; the on-device probes, with `adb logcat
  -s passalong` clean of JNI errors.
- **Completion criteria:** Both probes answer on the emulator.
- **Rollback or recovery:** Remove the JNI function and the Gradle
  lookup; the pinned mode does not need them, but `presented` does, so
  the fallback is a bridge-side `presented` that skips the system
  verifier (`trusted = false` always), which the user decides on.
- **Builder stop conditions:** The platform verifier cannot be
  initialised through UniFFI's library (a second `JNI_OnLoad` conflict,
  or `jni` 0.22 incompatible with the NDK): stop, report, and offer the
  fallback above.

### PLAN-00002-STEP-04 — Bridge exports for the server setup

- **Objective:** REQ-04's Rust side; the exports `run_https` needs.
- **Requirements:** `PLAN-00002-REQ-04`, `PLAN-00002-REQ-06`
- **Depends on:** STEP-02 (STEP-03 for the on-device part only)
- **Affected components:** `rust/src/setup.rs`, `rust/src/error.rs`,
  new `rust/tests/server.rs`, `rust/tests/support/mod.rs`, `justfile`
- **Preconditions:** `PASSALONG_SERVER_BIN` set locally to the built
  v0.1.0 binary.
- **Test or evidence first:** Host tests: `parse_tls_pin` accepts
  `sha256/` and `sha256//` forms and rejects others; `parse_api_key`
  rejects a key of the wrong shape; `render_https_config` validates back
  with `~`-free absolute paths. Integration tests (ignored without the
  binary): `probe_server` returns the server's pin and `trusted = false`;
  `inspect_server` with the created key answers version `0.1.0`, API 1,
  the label, `readWrite`, no expiry, workspace name, 0 items, `plaintext`;
  a wrong key is `Denied`; a wrong pin is `Connect` naming the presented
  pin; `save_api_key` then `load` round-trips owner-only.
- **Implementation tasks:**
  1. Records: `PresentedCertificate { pin, trusted }`; `ServerInfo {
     server_version, api_version, key_id, key_label, key_role
     ("readWrite"|"readOnly"), key_expires_at: Option<i64 epoch>,
     workspace, item_count, quota_bytes, used_bytes, encryption_state
     ("plaintext"|"encrypted"|"rewriting"), encryption_key_id: Option }`;
     `HttpsSetupAnswers { device_name, url, tls_pin: Option<String>,
     api_key_file }`.
  2. Exports: `probe_server(url) async`; `parse_tls_pin(text) ->
     String` (canonical form); `parse_api_key(text) -> String` (the id,
     for display); `has_api_key(path) -> bool`; `save_api_key(path,
     key)`; `render_https_config(answers)`; `inspect_server(config_toml,
     home, hostname, api_key: Option<String>) async -> ServerInfo` (the
     typed key, or the saved one); `device_has_store_key(config_toml,
     home, hostname) -> bool` for the claim rule.
  3. The test server harness, written afresh from the hand-over notes
     and the server's `--help` (no code from either repository): a
     private `HOME`, `init --data-dir`, `tls self-signed --host localhost
     --ip 127.0.0.1` (the pin parsed from its output), `workspace
     create`, `key create`, `serve` on a free port from the config, wait
     for `/readyz`, kill on drop.
  4. `just test-https`: `cargo test --manifest-path rust/Cargo.toml --
     --ignored` with the variable required.
- **Documentation/configuration/operations:** `docs/architecture.md`
  bridge table; `docs/developer-guide.md` `just test-https` and how to
  build the server.
- **Verification:** `cargo test`; `just test-https`; `just check`.
- **Completion criteria:** All listed tests green.
- **Rollback or recovery:** Revert the step.
- **Builder stop conditions:** The server's `tls self-signed` output has
  no parsable pin, or `serve` needs a setting the harness cannot give.

### PLAN-00002-STEP-05 — The setup flow in Kotlin

- **Objective:** REQ-04's Kotlin side and screen.
- **Requirements:** `PLAN-00002-REQ-04`
- **Depends on:** STEP-01, STEP-04
- **Affected components:** `core/Core.kt`, `data/AppFiles.kt`
  (`apiKey = File(home, "api.key")`), `setup/SetupController.kt`,
  `setup/SetupState.kt` (split out), `ui/setup/SetupScreen.kt`,
  `android/FfiCore.kt`, `support/Fakes.kt`, `SetupControllerTest`
- **Preconditions:** STEP-04's exports generated into the bindings.
- **Test or evidence first:** `SetupControllerTest`, the https cases of
  `init.rs` translated: the kind defaults to SSH and the SSH flow is
  unchanged; a server flow with an untrusted certificate shows the pin
  and refuses a wrong paste three times then stops with "not confirmed;
  nothing was written"; a trusted certificate offers "without a pin";
  the typed key of the wrong shape is refused before any request; a
  saved `api.key` is used without asking; a plaintext claim without a
  device key asks and declining writes nothing; confirming saves the key
  then the config, in that order, and `done` is set; a `Denied` from
  `inspect` shows the message and writes nothing; changing the URL
  forgets the probe.
- **Implementation tasks:**
  1. `Core`: `probeServer`, `parseTlsPin`, `parseApiKey`, `hasApiKey`,
     `saveApiKey`, `renderHttpsConfig`, `inspectServer`,
     `deviceHasStoreKey`; `CoreException.Kind.DENIED`; `FfiCore` and
     `FakeCore` (scripted `presented`, `serverInfo`, failures).
  2. `SetupState`: `kind: ServerKind`, `url`, `presented`, `pinChoice`
     (`Pin(typed)`/`TrustSystem`), `apiKey` (in memory, cleared on
     done), `serverInfo`, `claim` (`Unasked`/`Asked`), `stage`; the
     existing SSH fields untouched.
  3. `SetupController`: the `run_https` decision tree as events:
     `connect()` → probe → `pinDialog`; `pinTyped(text)`/`trustSystem()`
     → key entry; `apiKeyTyped(text)` → `inspect` → `serverInfo` shown →
     claim dialog when plaintext and no device key → `claimConfirmed(bool)`
     → save key, write config, `done`. Every failure to `error`, nothing
     written.
  4. `SetupScreen`: segmented SSH / passalong-server; server fields;
     dialogs: pin (presented pin shown, paste field, "trust the system"
     when offered), API key (password field), server summary + claim.
- **Documentation/configuration/operations:** `docs/usage.md` "Setup
  for a passalong-server"; `docs/configuration.md` (`api.key`,
  `[server.https]`).
- **Verification:** `just check`; on the emulator against the test
  server: complete a setup and reach the choose screen.
- **Completion criteria:** Tests green; coverage held.
- **Rollback or recovery:** Revert the step.
- **Builder stop conditions:** None foreseen.

### PLAN-00002-STEP-06 — Choose over a server

- **Objective:** REQ-05.
- **Requirements:** `PLAN-00002-REQ-05`
- **Depends on:** STEP-04, STEP-05
- **Affected components:** `choose/ChooseController.kt`,
  `ui/choose/ChooseScreen.kt`, `rust/tests/server.rs`,
  `ChooseControllerTest`
- **Preconditions:** A server config can be written by STEP-05.
- **Test or evidence first:** Rust integration: `open_store` over the
  test server lists nothing, then items the harness uploads with
  `passalong-https`'s `Store::put` through the same store handle
  (allowed: the client crate), `get_meta`, `read_text`, `download`,
  `delete`, `cached()` after `list(true)` names the URL and key id; a
  revoked key (`key revoke`) makes the next call `Denied`; a read-only
  key's `delete` is `Store` with the server's reason. Kotlin:
  `Connection.Failed` with `DENIED` shows *Set up again* first; a key
  expiring within 14 days puts "API key expires in N days" in the
  status at open.
- **Implementation tasks:**
  1. `ChooseController.connect` calls `inspectServer` for `https`
     configs after opening, to get the key's expiry (one request), and
     sets the expiry status when within 14 days; failures of that call
     are ignored (the store is open).
  2. `Denied` handling in `connect` and actions.
  3. `ChooseScreen`: the failed-connection panel orders the buttons by
     kind.
- **Documentation/configuration/operations:** `docs/usage.md` choose
  (the store line and the expiry warning).
- **Verification:** `just test-https`; `just check`; on the emulator:
  list, load, show, delete over the server.
- **Completion criteria:** Green; the emulator run recorded.
- **Rollback or recovery:** Revert the step.
- **Builder stop conditions:** None foreseen.

### PLAN-00002-STEP-07 — CI runs the server tests

- **Objective:** REQ-06's CI part.
- **Requirements:** `PLAN-00002-REQ-06`
- **Depends on:** STEP-04, STEP-06
- **Affected components:** `.github/workflows/ci.yml`, `justfile`
- **Preconditions:** `just test-https` green locally.
- **Test or evidence first:** The workflow itself.
- **Implementation tasks:**
  1. A job step `cargo install --locked --git
     https://github.com/joelee/passalong-server --tag v0.1.0
     passalong-server` with `Swatinem/rust-cache` covering
     `~/.cargo/bin`, then `PASSALONG_SERVER_BIN=$HOME/.cargo/bin/passalong-server just test-https`.
  2. `just check` unchanged (the server tests stay opt-in).
- **Documentation/configuration/operations:** `docs/developer-guide.md`.
- **Verification:** The CI run on the branch.
- **Completion criteria:** Green.
- **Rollback or recovery:** Remove the step.
- **Builder stop conditions:** The server does not build on the runner.

### PLAN-00002-STEP-08 — Docs, versions, records

- **Objective:** REQ-07.
- **Requirements:** `PLAN-00002-REQ-07`
- **Depends on:** STEP-01 to STEP-07
- **Affected components:** `README.md`, `docs/*.md`, `CHANGELOG.md`,
  `NOTICE`, `app/build.gradle.kts`, `rust/Cargo.toml`
- **Preconditions:** Everything green.
- **Test or evidence first:** `versions_are_the_pinned_ones` for the
  bridge; `just check`.
- **Implementation tasks:** As REQ-07 lists; the backlog gains "switch
  the bridge to crates.io `=0.3.0`", "encryption over a server",
  "uploads", "QR or link import of the API key".
- **Documentation/configuration/operations:** This step.
- **Verification:** `just check`.
- **Completion criteria:** Green; work log complete.
- **Rollback or recovery:** Not applicable.
- **Builder stop conditions:** None.

## 12. Cross-cutting concerns

- **Security:** no trust-on-first-use in either mode: the pin is
  compared with what the user pastes, or the system's verifier decides;
  no verification switch; the API key never appears in logs, config,
  or the UI after entry (password field, cleared from state), and lives
  owner-only in `api.key`; a plaintext claim from the server is
  confirmed by the user, as the hand-over notes require of clients; a
  device holding a store key never sends plaintext (inherited from the
  core; the app does not send yet).
- **Licensing:** no passalong-server code; the test harness is written
  from the documents and `--help`; `rustls-platform-verifier`'s Kotlin
  component (Apache-2.0/MIT) is listed in `NOTICE`.
- **Observability:** the core's records to logcat as before; the JNI
  initialisation logs success or failure once; nothing of the key.
- **Configuration:** `config.toml` in the core's `https` format;
  `api.key` beside it; `docs/configuration.md`.
- **Performance:** one extra request at open for the expiry line;
  release-built libraries grow by reqwest and rustls (expected ~2 MiB
  per ABI).
- **Compatibility:** the SSH flow and configs are untouched; the list
  cache identity matches the CLI's for the same server and key.

## 13. Verification strategy

`just check` after every step (ktlint, detekt, clippy, rustfmt, JVM
tests, Rust host tests, Kover >= 80 %, `assembleDebug`); `just
test-https` after STEP-04 and STEP-06 with the local server binary, and
in CI (STEP-07). Emulator checks at STEP-03, STEP-05, and STEP-06
against a test server on the host (`10.0.2.2`), recorded in the work
log with the device and the logcat evidence.

## 14. Acceptance criteria

- [ ] `PLAN-00002-AC-01` `just check` exits 0 at the end of every step.
- [ ] `PLAN-00002-AC-02` The four Review 00001 findings have regression
      tests that fail on `62e2e21` and pass afterwards.
- [ ] `PLAN-00002-AC-03` `core_version()` is `0.3.0` and the bridge
      builds for `arm64-v8a` and `x86_64` with `passalong-https`.
- [x] `PLAN-00002-AC-04` On the emulator, `probe_server` answers for a
      self-signed test server (`trusted = false`, its pin) and for a
      publicly trusted URL (`trusted = true`), with no JNI error.
- [ ] `PLAN-00002-AC-05` `SetupControllerTest` covers the `run_https`
      decisions: untrusted certificate needs the pasted pin (three
      tries), trusted offers no pin, wrong-shape key refused, saved key
      reused, plaintext claim asked and declining writes nothing,
      confirming writes `api.key` then `config.toml`.
- [ ] `PLAN-00002-AC-06` `just test-https` passes against
      passalong-server v0.1.0: probe, inspect, wrong pin, wrong key,
      list/get/read/download/delete, cache identity, revoked and
      read-only keys.
- [ ] `PLAN-00002-AC-07` On the emulator, a setup against the test
      server completes and the choose screen lists, loads, shows, and
      deletes items of the workspace.
- [ ] `PLAN-00002-AC-08` A `Denied` connection shows the core's message
      and *Set up again*; an expiry within 14 days shows in the status.
- [ ] `PLAN-00002-AC-09` The CI job builds the server from `v0.1.0` and
      runs `just test-https` green.
- [ ] `PLAN-00002-AC-10` No file of this repository contains code from
      passalong-server (`NOTICE` and the harness say so).
- [ ] `PLAN-00002-AC-11` Docs, CHANGELOG, backlog, and versions updated
      as REQ-07 lists.

## 15. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation or test | Owner/step |
|---|---|---|---|---|
| `rustls-platform-verifier` cannot be initialised beside UniFFI/JNA (second native init, `jni` 0.22 vs NDK) | medium | high | STEP-03 is early and small; fallback: a bridge-side probe without the system verifier, pin-only, decided by the user | STEP-03 |
| `passalong-https`'s dependencies (reqwest, rustls, ring) fail to cross-compile | low | high | STEP-02 verifies first; the client's own CI checks aarch64 | STEP-02 |
| A git dependency makes CI depend on GitHub availability and a branch that may be rebased | medium | medium | Pinned by full `rev`; switch to crates.io `=0.3.0` once published (backlog) | STEP-02 |
| The server binary's build in CI is slow | medium | low | `cargo install` cached by `rust-cache`; the tests stay opt-in for `just check` | STEP-07 |
| The 0.3.0 breaking changes in `passalong-core` reach the bridge in more places than the changelog lists | medium | low | The compiler finds them; STEP-02's tests | STEP-02 |
| The app's API key entry is mistyped or pasted with whitespace | high | low | Trim; shape check before any request; the server's 401 names the problem and is never retried (rate limiting) | STEP-05 |
| A spoofed server claims plaintext | low | high | The claim dialog, as the CLI; a device key blocks it outright | STEP-05 |

## 16. Builder hand-off

Branch `feature/00002-passalong-server` from a clean `main` after Review
00001 is committed. Start at STEP-01; run `just check` at each step's
end and `just test-https` where named; record emulator evidence in the
work log. Report coverage and the `just check` result. Never tag or
publish. For the emulator, follow `docs/developer-guide.md` (to be
extended in STEP-03) and the test server recipe from the hand-over notes.

## 17. Builder Work Log

> [!warning] Builder-maintained section
> Delivery Planner creates this section. After approval, Builder may update only
> this delimited section and the Builder-maintained front-matter fields. Builder
> must preserve prior entries and use UTC timestamps.

### Step status

| Step | Status | Started (UTC) | Completed (UTC) | Evidence | Builder notes |
|---|---|---|---|---|---|
| PLAN-00002-STEP-01 | completed | 2026-09-24T23:32:29Z | 2026-09-24T23:32:29Z | `just check` exit 0: 42 JVM tests, 21 Rust tests, coverage 99.2 % | `attempt` describes any non-cancellation failure; `reconnect()` from `Root`; `ActionTargets.io` and `SetupController.io` for blocking work; `SetupController.start()` replaces constructor I/O |
| PLAN-00002-STEP-02 | completed | 2026-09-24T23:38:47Z | 2026-09-24T23:38:47Z | `cargo test` 22 passed; `scripts/rust-android.sh` exit 0 for both ABIs; `just check` exit 0, coverage 99.2 % | git deps at `9ed0d4d`; `Denied` error kind; `describe` for `https`; docs for the pin bump |
| PLAN-00002-STEP-03 | completed | 2026-09-24T23:48:48Z | 2026-09-24T23:48:48Z | `just test-android https://10.0.2.2:8443 <pin>`: 3 instrumented tests passed on emulator-5554 (Android 16, API 36); logcat `passalong_ffi::android_tls Android TLS verifier initialised`; `just check` exit 0 | `rust/src/android_tls.rs` (JNI `init_with_env`), `NativeTls.kt`, the verifier's Kotlin component from the GitHub-hosted Maven repository at the version in `rust/Cargo.lock`; `probe_server` export with a host test; instrumented tests wired (`androidx.test`) |
| PLAN-00002-STEP-04 | completed | 2026-09-24T23:53:09Z | 2026-09-24T23:53:09Z | `just test-https` 5 passed against passalong-server 0.1.0; `cargo test` 28 passed; `just check` exit 0 | Exports `parse_tls_pin`, `parse_api_key`, `has_api_key`, `save_api_key`, `render_https_config`, `inspect_server`, `device_has_store_key`; harness `rust/tests/support/mod.rs` written from the server's docs and `--help` |
| PLAN-00002-STEP-05 | not-started | — | — | — | — |
| PLAN-00002-STEP-06 | not-started | — | — | — | — |
| PLAN-00002-STEP-07 | not-started | — | — | — | — |
| PLAN-00002-STEP-08 | not-started | — | — | — | — |

### Execution log

| Timestamp (UTC) | Step | Event | Evidence or reference | Next action |
|---|---|---|---|---|
| 2026-09-24T23:32:29Z | STEP-01 | Review 00001 fixes with regression tests | `ChooseControllerTest` (failed publish, reconnect, unreadable config), `SetupControllerTest` (unwritable config, key made off the main thread) | STEP-02 |
| 2026-09-24T23:38:47Z | STEP-02 | Bridge on the 0.3.0 git crates with `passalong-https` registered | `rust/Cargo.toml`, `rust/src/{error,store,lib}.rs`; `libpassalong_ffi.so` 7.1 MiB arm64 / 8.3 MiB x86_64 | STEP-03 |
| 2026-09-24T23:48:48Z | STEP-03 | Platform verifier on Android: JNI init, Kotlin component, emulator checks | `NativeTlsTest`: dl.google.com trusted, self-signed test server pinned and untrusted, closed port is `CONNECT` | STEP-04 |
| 2026-09-24T23:53:09Z | STEP-04 | Bridge exports for the server setup and a per-test server harness | `rust/src/setup.rs`, `rust/tests/{server.rs,support/mod.rs}`, `just test-https` | STEP-05 |

### Deviations and blockers

| Timestamp (UTC) | Step | Deviation or blocker | Impact | Decision required from |
|---|---|---|---|---|

None

### Verification results

| Timestamp (UTC) | Step | Command or check | Result | Evidence |
|---|---|---|---|---|
| 2026-09-24T23:32:29Z | STEP-01 | `just check` | pass | 42 JVM tests, 21 Rust tests, Kover 99.2 %, `assembleDebug` |
| 2026-09-24T23:38:47Z | STEP-02 | `cargo test`, `cargo clippy -D warnings`, `just rust`, `just check` | pass | 22 Rust tests, 42 JVM tests, Kover 99.2 % |
| 2026-09-24T23:48:48Z | STEP-03 | `just check`; `just test-android https://10.0.2.2:8443 <pin>` on emulator-5554 | pass | 23 Rust tests, 42 JVM tests, Kover 99.2 %; 3 instrumented tests |
| 2026-09-24T23:53:09Z | STEP-04 | `just test-https` (PASSALONG_SERVER_BIN=../passalong-server/target/release/passalong-server), `just check` | pass | 5 server tests, 28 Rust tests, 42 JVM tests, Kover 99.2 % |

### Completion summary

- **Implementation status:** `in-progress`
- **Completed requirements:** REQ-01, REQ-02, REQ-03
- **Incomplete requirements:** REQ-04 to REQ-07
- **Outstanding blockers:** None
- **Review request:** Not ready
<!-- BUILDER_WORK_LOG_END -->

## 18. Planning change log

| Timestamp (UTC) | Change | Reason |
|---|---|---|
| 2026-09-24T23:19:01Z | Draft written | User request of 2026-09-25; D-01 to D-04 answered by the user the same day |
| 2026-09-24T23:27:02Z | Approved | The user approved the plan as drafted and said no release follows until the APK is tested on a phone |

## 19. External references

- passalong client v0.3.0 branch: https://github.com/joelee/passalong/tree/feature/v0.3.0-passalong-server
- passalong-server v0.1.0: https://github.com/joelee/passalong-server/releases/tag/v0.1.0
- Hand-over notes for the client's `https` backend: `../passalong-server/docs/handover-notes-v0.3.0-client.md`
- rustls-platform-verifier 0.7.0, Android section of its README

## 20. Confidence

`medium`: the client and server sides are read from source and
documents, and the bridge changes are small; the one unknown is the
platform verifier on Android beside UniFFI, which STEP-03 tests first.
