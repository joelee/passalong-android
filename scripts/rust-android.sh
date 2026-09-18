#!/usr/bin/env bash
# Builds passalong-ffi for Android and generates its Kotlin bindings.
# Gradle's preBuild runs it; see docs/developer-guide.md.
#
# Outputs (both git-ignored):
#   rust/generated/kotlin/        the UniFFI Kotlin bindings
#   app/src/main/jniLibs/<abi>/   libpassalong_ffi.so per ABI
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/rust"

# The Android ABIs built, and the minimum API level (matches app minSdk).
abis=(arm64-v8a x86_64)
platform=29
profile="${PASSALONG_RUST_PROFILE:-release}"

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    ndk="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    echo "error: no Android NDK found; set ANDROID_NDK_HOME or install one under \$ANDROID_HOME/ndk (docs/developer-guide.md)" >&2
    exit 1
fi
export ANDROID_NDK_HOME="$ndk"

# 1. A host build, whose metadata uniffi-bindgen reads. The bindgen is a
#    binary of this crate, so it always matches the uniffi version used.
cargo build --locked
case "$(uname -s)" in
    Darwin) host_lib=target/debug/libpassalong_ffi.dylib ;;
    *) host_lib=target/debug/libpassalong_ffi.so ;;
esac
rm -rf generated/kotlin
cargo run --locked --quiet --features cli --bin uniffi-bindgen -- generate --no-format \
    --library "$host_lib" \
    --language kotlin \
    --config uniffi.toml \
    --out-dir generated/kotlin

# 2. The Android libraries, one per ABI.
targets=()
for abi in "${abis[@]}"; do targets+=(--target "$abi"); done
rm -rf ../app/src/main/jniLibs
cargo ndk "${targets[@]}" --platform "$platform" \
    --output-dir ../app/src/main/jniLibs build --locked --profile "$profile"
