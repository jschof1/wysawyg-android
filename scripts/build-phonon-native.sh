#!/usr/bin/env bash
# Build on a machine with NDK r27c; copy only output/arm64-v8a back to the APK builder.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c (27.2.12479018)}
out=${1:-"$root/app/build/phonon-jni"}
grep -Eq 'Pkg.Revision *= *27\.2\.12479018' "$ndk/source.properties" || { echo 'NDK r27c required' >&2; exit 1; }
case $(uname -s) in Darwin) host=darwin-x86_64;; Linux) host=linux-x86_64;; *) exit 1;; esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
cc="$toolchain/aarch64-linux-android26-clang"
mkdir -p "$out/arm64-v8a"
common=(-std=c11 -O3 -ffast-math -funroll-loops -fPIC -fvisibility=hidden -D_GNU_SOURCE
  -include "$root/app/src/main/cpp/android_compat.h" -I"$root/vendor/phonon2/csrc"
  -shared -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--no-undefined -Wl,-Bsymbolic)
sources=("$root"/vendor/phonon2/csrc/*.c "$root/app/src/main/cpp/phonon_jni.c")
"$cc" "${common[@]}" -march=armv8-a "$root/app/src/main/cpp/probe.c" -o "$out/arm64-v8a/libphonon_probe.so"
for variant in baseline dotprod; do
  arch=armv8-a
  if [ "$variant" = dotprod ]; then arch=armv8.2-a+dotprod; fi
  "$cc" "${common[@]}" -march="$arch" "${sources[@]}" -landroid -lm -o "$out/arm64-v8a/libphonon_${variant}.so"
  "$toolchain/llvm-strip" --strip-unneeded "$out/arm64-v8a/libphonon_${variant}.so"
done
"$toolchain/llvm-strip" --strip-unneeded "$out/arm64-v8a/libphonon_probe.so"
# Record provenance for externally supplied binaries; Gradle verifies hashes and source fingerprint.
python3 "$root/scripts/native-manifest.py" write "$out"
echo "JNI libraries: $out/arm64-v8a (API 26, ARM64, 16 KiB ELF alignment)"
