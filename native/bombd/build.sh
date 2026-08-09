#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ndk_root=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$ndk_root" ]; then
    sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    if [ -z "$sdk_root" ] && [ -f "$repo_root/local.properties" ]; then
        sdk_root=$(sed -n 's/^sdk.dir=//p' "$repo_root/local.properties" | head -n 1)
    fi
    if [ -n "$sdk_root" ]; then
        for candidate in "$sdk_root"/ndk/*; do
            [ -f "$candidate/build/cmake/android.toolchain.cmake" ] && ndk_root=$candidate
        done
    fi
fi
if [ -z "$ndk_root" ] || [ ! -f "$ndk_root/build/cmake/android.toolchain.cmake" ]; then
    echo "Set ANDROID_NDK_HOME to an installed Android NDK" >&2
    exit 2
fi

build_dir="$repo_root/build/bombd-arm64"
if command -v cmake >/dev/null 2>&1; then
    cmake -S "$repo_root/native/bombd" -B "$build_dir" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-33
    cmake --build "$build_dir"
else
    # The NDK compiler is sufficient for this single-file daemon. Keeping this
    # fallback makes the checked-in build command work on Android Studio setups
    # that have an NDK but no standalone CMake package installed.
    mkdir -p "$build_dir"
    "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang" \
        -std=c17 -O3 -DNDEBUG -Wall -Wextra -Werror -fstack-protector-strong \
        -Wl,-z,relro,-z,now \
        "$repo_root/native/bombd/bombd.c" -o "$build_dir/bombd"
fi
mkdir -p "$repo_root/rom/system_ext/bin"
cp "$build_dir/bombd" "$repo_root/rom/system_ext/bin/bombd"
"$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
    "$repo_root/rom/system_ext/bin/bombd"
