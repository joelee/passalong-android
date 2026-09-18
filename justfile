# passalong-android task runner. Every quality gate here is a command
# AGENTS.md names; pre-commit runs `just check`.
#
# Gradle runs through `mise exec`, which pins JDK 21 and Gradle 8.14.3
# (.mise.toml); the wrapper then fetches the pinned Gradle itself.

set shell := ["bash", "-euo", "pipefail", "-c"]

gradle := "mise exec -- ./gradlew"

# List available recipes
default:
    @just --list

# Install developer tooling: JDK and Gradle through mise, Rust targets, cargo-ndk
setup:
    mise install
    rustup target add aarch64-linux-android x86_64-linux-android
    command -v cargo-ndk >/dev/null || cargo install --locked cargo-ndk

# Format Kotlin and Rust in place
fmt:
    {{gradle}} ktlintFormat
    cargo fmt --manifest-path rust/Cargo.toml --all

# Check formatting
fmt-check:
    {{gradle}} ktlintCheck
    cargo fmt --manifest-path rust/Cargo.toml --all -- --check

# Lint: detekt for Kotlin, clippy for Rust
lint:
    {{gradle}} detekt
    cargo clippy --manifest-path rust/Cargo.toml --all-targets --all-features -- -D warnings

# Unit tests: Gradle JVM tests and the Rust bridge's host tests
test:
    {{gradle}} testDebugUnitTest
    cargo test --manifest-path rust/Cargo.toml --all-features

# Kotlin line coverage gate (>= 80 %, Kover) with the figure printed
coverage:
    {{gradle}} koverLog koverVerify

# Build the debug APK, which builds the Rust library for every ABI first
build:
    {{gradle}} assembleDebug

# Build the Rust library and the Kotlin bindings alone
rust:
    ./scripts/rust-android.sh

# Install the debug APK on the connected device or emulator
install:
    {{gradle}} installDebug

# Instrumented tests on the connected device or emulator
test-android:
    {{gradle}} connectedDebugAndroidTest

# Every mandated check: format, lint, tests, coverage, build
check: fmt-check lint test coverage build

# Remove build outputs
clean:
    {{gradle}} clean
    cargo clean --manifest-path rust/Cargo.toml
    rm -rf rust/generated app/src/main/jniLibs
