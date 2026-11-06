#!/bin/sh
set -eu

# Build an Android arm64 wheel for libloot's PyO3 module.
#
# Requirements:
#   - Rust with the aarch64-linux-android target
#     (rustup target add aarch64-linux-android)
#   - Android SDK (ANDROID_HOME) and JDK 17 (JAVA_HOME)
#   - Python 3.13-compatible cibuildwheel environment
#
# The first run downloads the Android NDK, CPython for Android, and the
# libloot source. Later runs reuse the cibuildwheel cache.

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WHEEL_DIR="$ROOT_DIR/app/src/main/wheels"
BUILD_DIR="${TMPDIR:-/tmp}/loot-android-libloot"
LIBLOOT_VERSION="${LIBLOOT_VERSION:-0.29.7}"
PYTHON_VERSION="${PYTHON_VERSION:-3.13}"
CIBW_VENV="${CIBW_VENV:-${TMPDIR:-/tmp}/loot-android-cibw}"

: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK directory}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"

mkdir -p "$WHEEL_DIR" "$BUILD_DIR"

if [ ! -d "$CIBW_VENV" ]; then
    python3 -m venv "$CIBW_VENV"
    "$CIBW_VENV/bin/pip" install --upgrade pip cibuildwheel
fi

if [ ! -d "$BUILD_DIR/libloot-$LIBLOOT_VERSION" ]; then
    curl -L --fail --retry 3 \
        "https://files.pythonhosted.org/packages/source/l/libloot/libloot-$LIBLOOT_VERSION.tar.gz" \
        -o "$BUILD_DIR/libloot-$LIBLOOT_VERSION.tar.gz"
    tar xf "$BUILD_DIR/libloot-$LIBLOOT_VERSION.tar.gz" -C "$BUILD_DIR"
fi

cd "$BUILD_DIR/libloot-$LIBLOOT_VERSION/python"

PATH="$HOME/.cargo/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH" \
ANDROID_HOME="$ANDROID_HOME" \
ANDROID_SDK_ROOT="$ANDROID_HOME" \
JAVA_HOME="$JAVA_HOME" \
CIBW_PLATFORM=android \
CIBW_ARCHS=arm64_v8a \
CIBW_BUILD="cp${PYTHON_VERSION/./}-android_arm64_v8a" \
CIBW_TEST_SKIP='*' \
CIBW_CACHE_PATH="$BUILD_DIR/cache" \
"$CIBW_VENV/bin/cibuildwheel" \
    --platform android \
    --archs arm64_v8a \
    --output-dir "$WHEEL_DIR" \
    .

echo
echo "Wheel written to $WHEEL_DIR"
