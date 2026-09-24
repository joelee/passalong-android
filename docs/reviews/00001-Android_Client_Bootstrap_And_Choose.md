---
title: "Code Review 00001: Android Client Bootstrap And Choose"
aliases:
  - "Review 00001"
tags:
  - code-review
  - software-quality
  - opencode
type: code-review
status: open
review_id: "00001"
reviewed_at: "2026-09-18T23:07:04Z"
reviewer_agent: review
review_model: "zai-coding-plan/glm-5.3"
triggered_by: "user"
review_kind: initial
previous_review: null
repository: "joelee/passalong-android"
branch: "main"
review_mode: repository
pr_reference: null
commit: "62e2e21339bfb2f7c2830eaa7add45dfae2ef5bf"
base_ref: null
base_commit: null
head_ref: "main"
head_commit: "62e2e21339bfb2f7c2830eaa7add45dfae2ef5bf"
scope: "whole repository at the initial commit 62e2e21 (73 files, the implementation of PLAN-00001); docs/reviews excluded"
related_plan: "docs/plans/00001-Android_Client_Bootstrap_And_Choose.md"
files_changed: 73
files_reviewed: 45
diff_additions: 10959
diff_deletions: 0
blocking_issues: 2
issues:
  critical: 0
  major: 2
  medium: 1
  low: 1
  info: 0
  total: 4
categories:
  correctness: 2
  performance: 1
  style: 1
verdict: request-changes
review_complete: true
web_research_used: false
confidence: high
sources: []
---

# Code Review 00001: Android Client Bootstrap And Choose

> [!abstract] Verdict: `request-changes`
> The bootstrap is architecturally sound and well tested, but two Major defects — a crash on any non-`CoreException` action failure, and a stale store connection after "Set up again" that can act on the wrong server — must be fixed before this code base is built upon.

## Review target

| Field | Value |
|---|---|
| Review mode | Repository (single initial commit, the implementation of Plan 00001) |
| PR or commit | `62e2e21339bfb2f7c2830eaa7add45dfae2ef5bf` ("Initial Commit", root commit, no parent) |
| Base | None (repository bootstrap; `baseline_commit: null` in the plan) |
| Head | `62e2e21` on `main`, clean worktree |
| Branch | `main` |
| Triggered by | user |
| Related plan | `docs/plans/00001-Android_Client_Bootstrap_And_Choose.md` (REQ-01…REQ-06, AC-01…AC-08) |

## Executive summary

The initial commit delivers the whole Plan 00001 bootstrap: the `passalong-ffi` UniFFI bridge over the pinned `=0.2.0` core, the Kotlin domain/data/controllers with the TUI-parity picker, the Compose screens, and the full quality-gate tooling (ktlint, detekt, Kover ≥ 80 %, clippy, CI, pre-commit, justfile). The Rust bridge and the pure Kotlin layers are clean: exact version pins verified by test, host-key pinning without trust-on-first-use, a 0600 identity key, `allowBackup="false"`, pending-then-publish MediaStore writes, and an unusually thorough JVM/Rust test suite with honest fakes.

Two Major defects block: (1) `ChooseController` catches only `CoreException`, while `ItemActions.load` deliberately propagates the sinks' `IOException` (verified by its own test), so a failed Downloads publish crashes the app instead of the status line REQ-04 requires; (2) after "Set up again" and confirming a new server, the retained `ChooseViewModel` keeps the previous store connection, so the list shown — and `load`/`cat`/`delete` — silently act on the old server. A Medium finding adds that several blocking file operations (including the full staged-file copy in `MediaStoreDownloads.publish`) run on the main thread, contrary to the plan's "nothing blocks the main thread" constraint.

## Issue summary

| Severity | Count | Merge impact |
|---|---:|---|
| Critical | 0 | Blocks merge/release |
| Major | 2 | Blocks merge/release |
| Medium | 1 | Changes requested |
| Low | 1 | Non-blocking |
| **Total** | **4** | |

## Findings

### Critical

None.

### Major

#### REV-00001-MAJ-01 — Action failures that are not `CoreException` crash the app instead of reaching the status line

> [!warning] Blocking
> - **Confidence:** High
> - **Category:** Correctness (error handling) / Reliability
> - **Location:** `app/src/main/kotlin/io/github/joelee/passalong/choose/ChooseController.kt:147-160` — `act` (catch at 156); `app/src/main/kotlin/io/github/joelee/passalong/data/ItemActions.kt:50-57` — `load`; `app/src/main/kotlin/io/github/joelee/passalong/android/Sinks.kt:40-49` — `MediaStoreDownloads.publish`
> - **Evidence:** `MediaStoreDownloads.publish` throws `IOException("cannot create $name in Downloads")` when `resolver.insert` returns null (Sinks.kt:42) and `IOException` when `openOutputStream` returns null (Sinks.kt:44). `ItemActions.load` calls it unguarded and its own test asserts the exception propagates (`app/src/test/kotlin/io/github/joelee/passalong/data/ItemActionsTest.kt:50-56`, `assertThrows(IllegalStateException…)` through `downloads.fail`). `ChooseController.act` catches only `CoreException`:
>   ```kotlin
>   } catch (err: CoreException) {
>       update { it.withStatus("cannot ${verb(outcome.action)} ${item.id}: ${err.message}") }
>   }
>   ```
>   and `busy {}` (ChooseController.kt:228-237) is `try/finally` without a catch, launching in `viewModelScope` (`AppContainer.kt:62-70`) with no `CoroutineExceptionHandler` — an uncaught exception in a `scope.launch` coroutine crashes the process on Android. The same pattern exists on the setup path: `SetupController.fingerprintMatches` catches only `CoreException` while `files.writeConfig(text)` (SetupController.kt:137 → `AppFiles.writeConfig`, plain `File.writeText`) can throw `IOException` on a full disk, and `ChooseController.connect` calls `files.readConfig()` (`readText`) unguarded (ChooseController.kt:81).
> - **Failure or attack scenario:** The device's Downloads volume is transiently unavailable or the disk is full; the user taps a file item to load it. `resolver.insert` returns null, `publish` throws `IOException`, it escapes `act`'s `CoreException`-only catch and the `busy` coroutine, and the app crashes — no status line, no retry. The TUI-parity contract ("failures stay in the status line", PLAN-00001-REQ-04) is broken for every failure the sinks raise.
> - **Impact:** Process crash on a plausible user path (loading a file when Downloads cannot accept it), plus crash-on-write during setup on disk-full. Availability and the plan's acceptance evidence (`ChooseControllerTest` failure cases) are only valid for `CoreException` failures.
> - **Recommendation:** Catch `IOException` (or `Exception`) alongside `CoreException` in `ChooseController.act` and map it to the same status line; add a `CoroutineExceptionHandler` to both view-model launches as a defence in depth so any future non-core failure degrades to a status/error instead of a crash. Consider having `MediaStoreDownloads.publish` delete the pending row in a `finally`-style guard for non-`IOException` failures too, so no invisible `IS_PENDING` row is leaked.
> - **Suggested verification:** A `ChooseControllerTest` case with `FakeDownloads.fail = IOException("cannot create report.pdf in Downloads")` asserting the status becomes `cannot load <id>: …` and `busy` returns to false; a `SetupControllerTest` case where `writeConfig` fails.
> - **References:** Repository evidence.

#### REV-00001-MAJ-02 — After "Set up again", the choose screen keeps acting on the previous server's store

> [!warning] Blocking
> - **Confidence:** High
> - **Category:** Correctness / Data integrity
> - **Location:** `app/src/main/kotlin/io/github/joelee/passalong/ui/Root.kt:23-42` — `Root`; `app/src/main/kotlin/io/github/joelee/passalong/android/AppContainer.kt:62-71` — `ChooseViewModel`; `app/src/main/kotlin/io/github/joelee/passalong/choose/ChooseController.kt:74-98` — `store`/`connect`
> - **Evidence:** `Root` keeps both view models in the activity's `ViewModelStore`; switching `screen` only changes composition. `ChooseViewModel` connects exactly once, at creation:
>   ```kotlin
>   val controller = ChooseController(…).also { it.connect() }
>   ```
>   and `ChooseController.connect` is documented "Call it once to start" (ChooseController.kt:77); nothing re-invokes it when the config changes. Flow: menu → *Set up again* → user enters a different host, confirms the new fingerprint, `files.writeConfig` writes the new config, `done` → `screen = CHOOSE` → the *retained* `ChooseViewModel` and its already-open `store` resurface, still `Connection.Ready` with the old server's description and the old list.
> - **Failure or attack scenario:** A user reconfigures from server A to server B (moved storage, different host). Back on the choose screen they see A's items, believe they are B's, and delete one — the delete executes against server A and removes a real item from the wrong server. `delete` cannot be undone.
> - **Impact:** Silent wrong-target operations, including destructive deletes, contradicting `docs/usage.md:30` ("The choose screen then connects, which proves the settings") and the setup flow's purpose (PLAN-00001-REQ-03). The stale window lasts until process death.
> - **Recommendation:** Reconnect when returning from setup: e.g. have `Root` observe setup completion and call a new `ChooseController.reconnect()` (reset picker/viewer, then `connect()`), or key/clear the `ChooseViewModel` when the config's mtime or `ConfigInfo` changes. Keep the rotation-retention behaviour intact.
> - **Suggested verification:** A test (or instrumented case) that opens choose, changes the config through `SetupController`, returns to choose, and asserts `store.list`/`description` were read again from the new store.
> - **References:** Repository evidence.

### Medium

#### REV-00001-MED-01 — Blocking file I/O and the full Downloads copy run on the main thread

- **Location:** `app/src/main/kotlin/io/github/joelee/passalong/android/Sinks.kt:45` — `MediaStoreDownloads.publish`; `app/src/main/kotlin/io/github/joelee/passalong/choose/ChooseController.kt:81,108`; `app/src/main/kotlin/io/github/joelee/passalong/setup/SetupController.kt:62-94`; `app/src/main/kotlin/io/github/joelee/passalong/android/FfiCore.kt:96` and `rust/src/store.rs:79-86` — `cached()`
- **What:** All controller coroutines run on `viewModelScope` (`Dispatchers.Main.immediate`; no `withContext`/`Dispatchers` anywhere in `app/src/main` — verified by search). On that thread: (a) `publish` copies the entire staged file to MediaStore (`File(path).inputStream().use { it.copyTo(sink) }`) — for a large item (hundreds of MB) this blocks the main thread for the whole copy; (b) `ChooseController.connect` reads `config.toml`; (c) `open`/`reloadCached` call `store.cached()`, a synchronous UniFFI export that reads and parses `list-cache.json` on the calling thread; (d) `SetupController`'s `init` block reads `readPublicKey()` and may run `generateIdentity` — a synchronous bridge call that generates a key and writes two files — during view-model construction.
- **Why it matters:** The plan's constraint "Every store call is a `suspend` function …; nothing blocks the main thread" (Plan 00001 §5, also `docs/architecture.md:21-24`) is met for the store calls but violated by these Kotlin/bridge I/O paths. Realistic impact: jank or ANR (5 s input-dispatch threshold) when loading a large file; StrictMode disk violations in debug builds; key generation stalls first frame of the setup screen on slow flash.
- **Evidence:**
  ```kotlin
  out.use { sink -> File(path).inputStream().use { it.copyTo(sink) } }   // Sinks.kt:45, main thread
  ```
  ```rust
  pub fn cached(&self) -> Option<CachedItems> {   // store.rs:79, sync export, file read
  ```
- **Suggested fix:** Wrap the blocking parts in `withContext(Dispatchers.IO)` (config/cache reads, `publish` and the staged-file deletion — e.g. make `DownloadsSink.publish` `suspend`, or wrap inside `ItemActions.load`), and move `SetupController`'s constructor I/O into `viewModelScope` with a "generating…" state. The `Core`/`StoreHandle` interfaces need not change.

### Low

#### REV-00001-LOW-01 — KDoc/comments contradict the behaviour they describe

- **Location:** `app/src/main/kotlin/io/github/joelee/passalong/choose/ChooseController.kt:124-126` — `on`; `app/src/main/kotlin/io/github/joelee/passalong/android/AppContainer.kt:27-28` — `AppContainer`
- **What / Why:** `on`'s KDoc says "Events wait while the store is busy", but the implementation drops events (`if (_state.value.busy) return`), and the test is named `events are ignored while the store is busy` (`ChooseControllerTest.kt:233-245`) — the dropping is intended; the doc is wrong and will mislead the next maintainer. `AppContainer`'s doc says "built once per process", but `MainActivity.onCreate` constructs a new one on every activity recreation (`MainActivity.kt:16`); harmless today (all fields are cheap and `init_logging` is idempotent) but the claim is false.
- **Suggested fix:** Rephrase: "Events are ignored while the store is busy, as the TUI drops keys during a load" and "built per activity creation; cheap and stateless" (or actually hold it in `PassalongApp`).

## Open questions

1. **TUI parity "key for key" (REQ-04)** — this review could not read `../passalong`'s `choose/{state,mod}.rs` (external-directory permission denied), so `PickerTest`/`ChooseControllerTest` were checked for internal consistency and coverage, not for literal parity with `state.rs`/`run_picker`. A diff against `../passalong` at `884831c` would resolve it.
2. **Verification claims** — the plan records `just check` green with 98.86 % coverage and 38 JVM + 21 Rust tests; this read-only review did not execute them. Rerun `just check` after fixing MAJ-01/MAJ-02 to re-establish AC-01/AC-07.
3. **MediaStore with parameterized MIME types** — file items such as `notes.txt` carry `text/plain; charset=utf-8` into `MediaStore.Downloads.MIME_TYPE`; whether insert normalizes or rejects parameterized values should be confirmed on a device (part of the pending AC-08).

## Review coverage

### Files and areas reviewed

- Rust bridge in full: `rust/src/{lib,env,error,model,setup,store,logging}.rs`, `rust/Cargo.toml`, `rust/uniffi.toml`, `rust/src/bin/uniffi-bindgen.rs` existence, `scripts/rust-android.sh`
- Kotlin main in full: `domain/{Item,Picker,Formats}.kt`, `core/Core.kt`, `data/{AppFiles,ItemActions}.kt`, `choose/ChooseController.kt`, `setup/SetupController.kt`, `android/{FfiCore,AppContainer,Sinks}.kt`, `MainActivity.kt`, `ui/Root.kt`, `ui/choose/ChooseScreen.kt`, `ui/setup/SetupScreen.kt`
- Tests: `ChooseControllerTest`, `SetupControllerTest`, `PickerTest`, `ItemTest`, `FormatsTest` (partial), `ItemActionsTest`, `AppFilesTest`, `AppContainerTest`, `support/Fakes.kt`
- Tooling: `app/build.gradle.kts` (Kover exclusions, buildRust wiring), `gradle/libs.versions.toml`, `.github/workflows/ci.yml`, `.pre-commit-config.yaml`, `justfile`, `AndroidManifest.xml`, `uniffi.toml`, `.agents/skills/allocating-report-numbers/allocate-report.sh`
- Docs: Plan 00001, `docs/architecture.md` (partial), `docs/usage.md` (partial), `docs/backlog.md` (partial)
- Not inspected: `ui/theme/Theme.kt`, `res/values/*`, `config/detekt/detekt.yml`, `docs/configuration.md`, `docs/developer-guide.md`, `README.md`, `CHANGELOG.md`, Gradle root files, `Cargo.lock` contents

### Checks performed

- Target resolution: single root commit, clean worktree, plan's `execution_branch: main` confirmed
- Full-file reads of every Rust and Kotlin main-source file; cross-layer tracing of the load/cat/delete/reload/setup flows; ViewModel lifetime analysis for the re-setup path
- Repository searches: no `withContext`/`Dispatchers` in main sources; no TODO/FIXME/STOPSHIP; error-mapping audit of every `catch`
- Consistency of Kover exclusions, CI steps, and pre-commit against AGENTS.md commands

### Checks not performed

- No tests, builds, linters, formatters, coverage, or the app were run by this read-only agent; all green-gate claims come from the plan's Builder Work Log
- No device/emulator execution (AC-08 remains open, honestly recorded as unchecked in the plan)
- No diff against the `../passalong` TUI sources (permission denied on the sibling checkout)
- `Cargo.lock` dependency audit not performed (supply-chain risk rests on the exact `=0.2.0` pins and crates.io)

## Positive notes

- Security posture is unusually careful for a bootstrap: no trust-on-first-use (fetch → fingerprint → explicit pin), identity written mode 0600 with an existence guard against silent replacement, `allowBackup="false"` keeping the key out of cloud backups, `INTERNET` as the only permission, and an `AppEnv` that leaks nothing from the process environment.
- The single-file FFI boundary (`FfiCore.kt` alone imports bindings) is respected everywhere; errors cross as one flat enum with the core's own messages.
- The test suite is genuinely behavioural: `PickerTest` exercises the state machine, `ChooseControllerTest` records statuses at store calls (mirroring the TUI's frame-recording technique), and the Rust host tests cover the cache lifecycle, including the unwritable-cache and foreign-identity cases.
- Tooling is coherent: `just check` gates match AGENTS.md, CI replicates them with an NDK, the bindgen runs from the crate's own `uniffi-bindgen` binary (D-03), and Kover exclusions match the documented instrumented-test backlog.

## External references

None.

## Recommended next actions

1. Fix REV-00001-MAJ-01: broaden error handling in `ChooseController.act` (and `busy`/`connect`, `SetupController.fingerprintMatches`) to non-`CoreException` failures; add the regression tests listed there.
2. Fix REV-00001-MAJ-02: reconnect the choose screen after a completed re-setup; add a test proving the new store is used.
3. Address REV-00001-MED-01: move blocking I/O (publish copy, config/cache reads, key generation) off the main thread.
4. Correct the two misleading docs (REV-00001-LOW-01).
5. Re-run `just check` (coverage ≥ 80 %) and, when a device is available, close AC-08.

## Handoff

The repository's bootstrap should not be extended until the two Major findings are fixed — both are small, localized changes in `ChooseController`/`Root`/`AppContainer` plus regression tests. After fixes, request a re-review linking this report; the re-review should re-verify MAJ-01/MAJ-02 against the new tests and re-check the `just check`/coverage evidence. AC-08 (real device against the Docker SSH server) remains the user's outstanding verification from the plan.

## Confidence

**High.** Every Rust and Kotlin main-source file was read in full and both Major findings were verified by tracing concrete code paths (exception flow through `act`/`busy`/`viewModelScope`; view-model retention across `Root`'s screen switch), each corroborated by the repository's own tests. The main residual uncertainties are the things this review could not execute or read: runtime behaviour on a device, the green-gate/coverage figures, and literal parity with the TUI sources in the sibling checkout.
