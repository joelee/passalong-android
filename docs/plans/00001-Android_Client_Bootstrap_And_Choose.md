---
title: "Delivery Plan 00001: Android Client Bootstrap And Choose"
aliases:
  - "Plan 00001"
tags:
  - delivery-plan
  - implementation
  - opencode
type: delivery-plan
plan_id: "PLAN-00001"
plan_status: approved              # draft | approved | cancelled
plan_kind: initial                 # initial | superseding
created_at: "2026-09-15T22:33:27Z"
approved_at: "2026-09-15T22:33:27Z"
planner_agent: "Claude Code"
planner_model: "anthropic/claude-fable-5-1"
triggered_by: user                 # user | agent:<agent-name>
request_kind: direct               # idea | review | idea-and-review | direct | unplanned-query
repository: "joelee/passalong-android"
baseline_branch: "main"
baseline_commit: null
source_ideas: []
source_reviews: []
previous_plan: null
requirements_count: 6
steps_count: 5
acceptance_criteria_count: 8
blocking_decisions: 0
build_ready: true
web_research_used: false
confidence: medium                # high | medium | low

# Builder-maintained front matter. Builder may update only these keys after
# explicit user approval; Delivery Planner initializes them.
implementation_status: completed # not-started | in-progress | blocked | completed | abandoned
builder_agent: "Claude Code"
builder_model: "anthropic/claude-fable-5-1"
execution_branch: "main"
execution_started_at: "2026-09-15T21:07:00Z"
execution_updated_at: "2026-09-15T22:33:27Z"
execution_completed_at: "2026-09-15T22:33:27Z"
current_step: null
---

# Delivery Plan 00001: Android Client Bootstrap And Choose

> [!abstract] Plan status: `approved`
> Start the Android client of passalong from scratch, in this new
> repository, as a Kotlin and Jetpack Compose app over the passalong Rust
> core through a UniFFI bridge, and deliver the first milestone: what the
> TUI's `passalong choose` does, plus the in-app setup that replaces
> `passalong init`. Recorded as the plan of the bootstrap the user asked
> for directly on 2026-09-15 ("Start a new fresh Android client here from
> scratch ... This will initially emulate the Passalong TUI `passalong
> choose`"); `baseline_commit` is null because the repository had no
> commit yet.

## 1. Objective and outcome

1. A repository with the same quality gates and agent contracts as
   passalong: format, lint, tests, coverage of 80 %, build, pre-commit,
   CI, and the `docs/{ideas,plans,reviews}` contracts.
2. A Rust bridge crate, `rust/passalong-ffi`, over `passalong-core` and
   `passalong-ssh` pinned to `=0.2.0`, exporting through UniFFI what the
   setup and choose screens need, with async functions on tokio.
3. A setup screen doing what `passalong init` does on a phone.
4. A choose screen doing what `passalong choose` does, with the same
   state machine, statuses, filter, and actions.

## 2. Source traceability

| Requirement | Source | Source location | Interpretation |
|---|---|---|---|
| PLAN-00001-REQ-01 | User | The request of 2026-09-15 | A fresh repository, not the `passalong-android-glm43` scaffold, with passalong's conventions |
| PLAN-00001-REQ-02 | Repository | `../passalong/docs/architecture.md`, "Future GUI and Android front-ends depend on `passalong-core` and `passalong-ssh` only" | The app reuses the core through a bridge and reimplements no store logic |
| PLAN-00001-REQ-03 | User; Repository | `../passalong/docs/usage.md` § `passalong init` | Setup on the device: identity, host key fingerprint confirmation, config |
| PLAN-00001-REQ-04 | User | "initially emulate the Passalong TUI `passalong choose`"; `../passalong/crates/passalong-cli/src/commands/choose/{mod,state}.rs` | The picker's modes, outcomes, statuses, and actions, key for key |
| PLAN-00001-REQ-05 | Repository | `../passalong/docs/usage.md` § `passalong choose`, list cache | Cache-first open with age, `r` and `R` |
| PLAN-00001-REQ-06 | Repository | `../passalong/AGENTS.md` non-negotiables | TDD, mocked interfaces, coverage 80 %, docs maintained |

## 3. Repository baseline

An empty directory, `git init` on `main`, no commits. Sibling
checkouts consulted: `../passalong` at `884831c` (workspace version
0.2.0, PLAN-00009 in progress) and `../passalong-android-glm43` at
`3745a27` (an earlier scaffold, used only as a reference for the Gradle
and UniFFI wiring). crates.io holds `passalong-core` and `passalong-ssh`
0.2.0.

## 4. Scope

### In scope

- Repository scaffold: Gradle (AGP 8.13, Kotlin 2.2.20, Compose BOM
  2025.09), version catalog, ktlint, detekt, Kover, justfile, pre-commit,
  CI workflow, `.mise.toml`, docs, agent contracts.
- `rust/passalong-ffi`: env, errors, model, setup (host key, identity,
  config), store handle with the list cache, logcat logging, host tests.
- Kotlin: domain (`Item`, `Picker`, `Formats`), core interfaces, data
  (`AppFiles`, `ItemActions`), controllers, Android glue, Compose screens,
  JVM unit tests.

### Out of scope

- Sending items, encrypted-store join, instrumented tests, release
  signing, `armeabi-v7a`, launcher icon: on the backlog.

## 5. Constraints and preserved decisions

- The core is pinned exactly from crates.io; a git-ignored path patch is
  the only way to build against a checkout.
- Kotlin touches the generated bindings in one file.
- Every store call is a `suspend` function over an async Rust export;
  nothing blocks the main thread.
- minSdk 29, so downloads go through MediaStore without a permission.
- `unsafe` is denied in Rust except the one logcat call.

## 6. Assumptions

- A-01: the user wants the same agent workflow as passalong (contracts
  copied verbatim from `../passalong`).
- A-02: a phone has no terminal, so `load` and `cat` do not close the
  list; they report over it.
- A-03: clipboard images load as files into Downloads for now.

## 7. Decisions and blockers

| ID | Decision | Options | Recommendation | Status |
|---|---|---|---|---|
| D-01 | Core reuse | UniFFI bridge over the crates; reimplement in Kotlin | Bridge, as passalong's architecture intends | settled (planner) |
| D-02 | Async style | UniFFI async on tokio; blocking calls on `Dispatchers.IO` | Async: Kotlin `suspend` functions, no bridge threads | settled (planner) |
| D-03 | Bindgen | Separate `uniffi-bindgen` install; a `[[bin]]` of the crate | The crate's own binary, so versions never drift | settled (planner) |
| D-04 | Coverage scope | All Kotlin; exclude Compose and Android glue | Exclude what only instrumented tests can run, and say so | settled (planner) |

No blocking decisions.

## 8. Affected architecture and components

New: everything under `rust/`, `app/`, `scripts/`, `docs/`, `config/`,
`.github/`. See `docs/architecture.md`.

## 9. Requirement catalogue

### PLAN-00001-REQ-01 — A fresh repository with passalong's conventions

- **Requirement:** The repository has AGENTS.md, the docs set, the
  `docs/{ideas,plans,reviews}` contracts and the numbering skill, a
  justfile with fmt-check, lint, test, coverage, build, `check`,
  pre-commit, and a CI workflow.
- **Rationale:** The user's other repositories work this way.
- **Source:** User request; `../passalong/AGENTS.md`.
- **Acceptance evidence:** `just check` exits 0; the files exist.

### PLAN-00001-REQ-02 — A Rust bridge over the pinned core

- **Requirement:** `rust/passalong-ffi` depends on `passalong-core`
  (`default-features = false`) and `passalong-ssh` `=0.2.0`, exports
  through UniFFI the setup functions and a store handle, builds for
  `arm64-v8a` and `x86_64`, and is tested on the host.
- **Rationale:** Host-key pinning, the item schema, verification, and
  encryption must behave exactly like the CLI.
- **Source:** `../passalong/docs/architecture.md`.
- **Acceptance evidence:** `cargo test` passes; the APK holds
  `libpassalong_ffi.so` for both ABIs.

### PLAN-00001-REQ-03 — Setup as `passalong init`

- **Requirement:** The app generates an ed25519 identity and shows the
  public key; takes host, port, user, remote path, device name with
  `init`'s defaults; fetches the host key; shows the fingerprint with the
  CLI's words; writes the config only on confirmation; declining writes
  nothing.
- **Rationale:** The app cannot connect without a config and a key.
- **Source:** `../passalong/docs/usage.md` § `passalong init`.
- **Acceptance evidence:** `SetupControllerTest`; `rust/src/setup.rs`
  tests.

### PLAN-00001-REQ-04 — Choose as `passalong choose`

- **Requirement:** The picker has the TUI's modes, outcomes, filter, and
  status texts; the screen maps touches to its events; `Load` puts text
  on the clipboard and files in Downloads, verified; `Cat` shows text;
  `Details` shows `passalong get`'s lines; `Delete` confirms, deletes,
  and reads the list again; failures stay in the status line.
- **Rationale:** The user's stated first milestone.
- **Source:** `choose/state.rs`, `choose/mod.rs`.
- **Acceptance evidence:** `PickerTest` mirrors `state.rs` tests;
  `ChooseControllerTest` mirrors `run_picker` tests.

### PLAN-00001-REQ-05 — The list cache

- **Requirement:** The list opens from a fresh cache with `cached N s
  ago`; `r` shows the cache while fresh, else refreshes; `R` reads every
  item; deletion drops the item from the cache; only `ssh` stores with
  `serve.list_cache` on are cached, under the CLI's identity.
- **Rationale:** `choose` opens at once on desktops; the phone should too.
- **Source:** `../passalong/docs/usage.md`, `list_cache.rs`.
- **Acceptance evidence:** `rust/src/store.rs` cache tests;
  `ChooseControllerTest` cache cases.

### PLAN-00001-REQ-06 — Quality gates

- **Requirement:** ktlint, detekt, rustfmt, clippy `-D warnings`, JVM
  unit tests with fakes, Rust host tests, Kover line coverage >= 80 %,
  docs maintained.
- **Rationale:** AGENTS.md non-negotiables.
- **Source:** `../passalong/AGENTS.md`.
- **Acceptance evidence:** `just check` output.

## 10. Delivery strategy

Bottom up, so each layer is verified before the next depends on it: the
bridge first (host tests over the `local` backend need no server), then
the domain and controllers with fakes, then the screens and glue, then
the docs and gates. Every layer has its own tests; the screens are
exercised by building the APK.

## 11. Detailed implementation steps

### PLAN-00001-STEP-01 — The Rust bridge

- **Objective:** `rust/passalong-ffi` with its exports and host tests.
- **Requirements:** `PLAN-00001-REQ-02`, `PLAN-00001-REQ-05`
- **Depends on:** None
- **Affected components:** `rust/Cargo.toml`, `rust/uniffi.toml`,
  `rust/src/{lib,env,error,model,setup,store,logging}.rs`,
  `rust/src/bin/uniffi-bindgen.rs`, `scripts/rust-android.sh`
- **Preconditions:** crates.io reachable; NDK installed.
- **Test or evidence first:** Unit tests per module over a local store in
  a temporary folder; a closed-port host-key fetch.
- **Implementation tasks:**
  1. `AppEnv`, `PassalongError`, `Item`.
  2. `fetch_host_key`, `generate_identity`, `render_config`,
     `validate_config`.
  3. `open_store`, `PassalongStore`, the `Cache`.
  4. `init_logging` to logcat.
  5. The build script: host build, bindgen, cargo-ndk release build.
- **Documentation/configuration/operations:** `docs/architecture.md`.
- **Verification:** `cargo fmt --check`, `cargo clippy -D warnings`,
  `cargo test`, `scripts/rust-android.sh`.
- **Completion criteria:** All pass; bindings and libraries generated.
- **Rollback or recovery:** Delete `rust/`.
- **Builder stop conditions:** The core does not build for Android.

### PLAN-00001-STEP-02 — Domain and data

- **Objective:** `Item`, `Picker`, `Formats`, `Core`/`StoreHandle`,
  `AppFiles`, `ItemActions`, with tests and fakes.
- **Requirements:** `PLAN-00001-REQ-04`
- **Depends on:** None
- **Affected components:** `app/src/main/kotlin/.../{domain,core,data}`,
  `app/src/test/kotlin/.../{domain,data,support}`
- **Preconditions:** Gradle project configured.
- **Test or evidence first:** `PickerTest` translated from `state.rs`;
  `FormatsTest` from `output.rs`; `ItemActionsTest`; `AppFilesTest`.
- **Implementation tasks:** as listed.
- **Documentation/configuration/operations:** `docs/architecture.md`.
- **Verification:** `just test`.
- **Completion criteria:** Tests green.
- **Rollback or recovery:** Not applicable, new code.
- **Builder stop conditions:** None.

### PLAN-00001-STEP-03 — Controllers

- **Objective:** `ChooseController` (`run_picker`) and
  `SetupController` (`init`) over `StateFlow`.
- **Requirements:** `PLAN-00001-REQ-03`, `PLAN-00001-REQ-04`,
  `PLAN-00001-REQ-05`
- **Depends on:** STEP-02
- **Affected components:** `app/src/main/kotlin/.../{choose,setup}`,
  their tests
- **Preconditions:** STEP-02 green.
- **Test or evidence first:** `ChooseControllerTest` mirrors the
  `run_picker` tests (statuses recorded at each store call);
  `SetupControllerTest` mirrors `init`'s.
- **Implementation tasks:** as listed.
- **Documentation/configuration/operations:** `docs/usage.md`.
- **Verification:** `just test`, `just coverage`.
- **Completion criteria:** Tests green, coverage >= 80 %.
- **Rollback or recovery:** Not applicable.
- **Builder stop conditions:** None.

### PLAN-00001-STEP-04 — Screens and Android glue

- **Objective:** `FfiCore`, the sinks, `AppContainer`, view models,
  `MainActivity`, `Root`, `SetupScreen`, `ChooseScreen`, theme, manifest.
- **Requirements:** `PLAN-00001-REQ-03`, `PLAN-00001-REQ-04`
- **Depends on:** STEP-01, STEP-03
- **Affected components:** `app/src/main/kotlin/.../{android,ui}`,
  `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`
- **Preconditions:** Bindings generated.
- **Test or evidence first:** `AppContainerTest` for the device name; the
  screens are verified by compiling and building the APK (instrumented
  tests are on the backlog).
- **Implementation tasks:** as listed.
- **Documentation/configuration/operations:** `docs/usage.md`,
  `docs/configuration.md`.
- **Verification:** `just build`; `unzip -l` shows both libraries.
- **Completion criteria:** APK built.
- **Rollback or recovery:** Not applicable.
- **Builder stop conditions:** None.

### PLAN-00001-STEP-05 — Gates, docs, and records

- **Objective:** justfile, pre-commit, CI, AGENTS.md, README, docs,
  CHANGELOG, NOTICE, backlog, this plan.
- **Requirements:** `PLAN-00001-REQ-01`, `PLAN-00001-REQ-06`
- **Depends on:** STEP-01 to STEP-04
- **Affected components:** repository root, `docs/`, `.github/`
- **Preconditions:** Everything builds.
- **Test or evidence first:** `just check`.
- **Implementation tasks:** as listed.
- **Documentation/configuration/operations:** This step is the docs.
- **Verification:** `just check` exits 0.
- **Completion criteria:** Exit 0; coverage figure recorded below.
- **Rollback or recovery:** Not applicable.
- **Builder stop conditions:** None.

## 12. Cross-cutting concerns

- **Security:** no trust-on-first-use; the identity has no passphrase and
  stays app-private (mode 0600); MediaStore never replaces a file; the
  core sanitises server file names.
- **Observability:** the core's records to logcat, tag `passalong`; no
  content or secrets logged.
- **Configuration:** the core's `config.toml` in the files directory.
- **Performance:** async bridge; release-built libraries (4–5 MiB each).
- **Compatibility:** minSdk 29; the list cache identity matches the CLI's.

## 13. Verification strategy

`just check`: ktlint and rustfmt checks, detekt and clippy, 38 JVM tests
and 21 Rust tests, Kover >= 80 %, `assembleDebug`. Manual: install on a
device and set up against passalong's Docker SSH server (not done here;
no device or emulator was attached).

## 14. Acceptance criteria

- [x] `PLAN-00001-AC-01` `just check` exits 0.
- [x] `PLAN-00001-AC-02` `cargo test` in `rust/` passes on the host.
- [x] `PLAN-00001-AC-03` The debug APK holds `libpassalong_ffi.so` for
      `arm64-v8a` and `x86_64`.
- [x] `PLAN-00001-AC-04` `PickerTest` covers every `state.rs` test case.
- [x] `PLAN-00001-AC-05` `ChooseControllerTest` shows the TUI's statuses
      for cache open, `r`, `R`, `g`, `d`/`y`, and failures.
- [x] `PLAN-00001-AC-06` `SetupControllerTest` shows that declining the
      fingerprint writes nothing and confirming writes a config the core
      validates.
- [x] `PLAN-00001-AC-07` Kotlin line coverage >= 80 % over the measured
      packages.
- [ ] `PLAN-00001-AC-08` The app lists, loads, shows, and deletes items
      against a real SSH server on a device. Not verified: no device was
      attached in this session; left for the user.

## 15. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation or test | Owner/step |
|---|---|---|---|---|
| UniFFI async futures misbehave on a device | low | high | The bridge follows UniFFI's documented tokio mode; AC-08 on a device | user |
| JNA fails to load the library on some devices | low | high | JNA AAR with `libjnidispatch.so` per ABI, as the earlier scaffold verified | STEP-04 |
| A core update changes behaviour | medium | medium | Exact pins; bump deliberately with a plan | later |
| Compose UI untested on the JVM | certain | medium | Excluded from coverage and stated; instrumented tests on the backlog | backlog |

## 16. Builder hand-off

Start at STEP-01; each step's verification is its exit gate. Report the
coverage figure and `just check` result.

## 17. Builder Work Log

> [!warning] Builder-maintained section
> Delivery Planner creates this section. After approval, Builder may update only
> this delimited section and the Builder-maintained front-matter fields. Builder
> must preserve prior entries and use UTC timestamps.

### Step status

| Step | Status | Started (UTC) | Completed (UTC) | Evidence | Builder notes |
|---|---|---|---|---|---|
| PLAN-00001-STEP-01 | completed | 2026-09-15T21:07:00Z | 2026-09-15T21:20:00Z | `cargo test`: 21 passed; `scripts/rust-android.sh` exit 0 | `unsafe_code` changed from forbid to deny for the logcat call |
| PLAN-00001-STEP-02 | completed | 2026-09-15T21:20:00Z | 2026-09-15T21:40:00Z | `testDebugUnitTest` | — |
| PLAN-00001-STEP-03 | completed | 2026-09-15T21:40:00Z | 2026-09-15T22:20:00Z | `testDebugUnitTest`: 38 passed | `connect()` is called after construction, not in `init`, so tests can observe statuses |
| PLAN-00001-STEP-04 | completed | 2026-09-15T21:50:00Z | 2026-09-15T22:30:00Z | `assembleDebug`; APK holds both libraries | Compose lint tasks ordered after `buildRust` |
| PLAN-00001-STEP-05 | completed | 2026-09-15T22:30:00Z | 2026-09-15T22:33:27Z | `just check` exit 0 | — |

### Execution log

| Timestamp (UTC) | Step | Event | Evidence or reference | Next action |
|---|---|---|---|---|
| 2026-09-15T21:07:00Z | STEP-01 | Bridge written and built on the host | `cargo build`, `cargo test` | Android build |
| 2026-09-15T21:18:00Z | STEP-01 | Android build failed: `allow(unsafe_code)` under `forbid` | `rust/Cargo.toml` lints | Switched to `deny` |
| 2026-09-15T22:25:00Z | STEP-03 | Controller tests could not see transient statuses through `StateFlow` | `ChooseControllerTest` | Record statuses at store calls |
| 2026-09-15T22:33:27Z | STEP-05 | `just check` green | log in session | Hand off |

### Deviations and blockers

| Timestamp (UTC) | Step | Deviation or blocker | Impact | Decision required from |
|---|---|---|---|---|
| 2026-09-15T22:33:27Z | STEP-04 | AC-08 not run: no device or emulator attached | The screens are unverified on a device | user |

### Verification results

| Timestamp (UTC) | Step | Command or check | Result | Evidence |
|---|---|---|---|---|
| 2026-09-15T22:33:27Z | STEP-05 | `just check` | pass | 38 JVM tests, 21 Rust tests, coverage 98.86 %, `assembleDebug` |

### Completion summary

- **Implementation status:** `completed`
- **Completed requirements:** REQ-01 to REQ-06
- **Incomplete requirements:** None; AC-08 awaits a device
- **Outstanding blockers:** None
- **Review request:** Ready for the user's review and first commit
<!-- BUILDER_WORK_LOG_END -->

## 18. Planning change log

| Timestamp (UTC) | Change | Reason |
|---|---|---|
| 2026-09-15T22:33:27Z | Plan written after the fact, at approval | The user asked for the bootstrap directly; the record keeps the repository's workflow traceable |

## 19. External references

- passalong: https://github.com/joelee/passalong
- UniFFI: https://mozilla.github.io/uniffi-rs/
- cargo-ndk: https://github.com/bbqsrc/cargo-ndk

## 20. Confidence

`medium`: every gate passes on the development machine, but the screens
and the bridge have not run on a device in this session.
