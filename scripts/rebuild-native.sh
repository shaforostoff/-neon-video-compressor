#!/usr/bin/env bash
# Force-rebuild the native library and repackage the APK.
#
# Gradle's assemble task has been seen packaging a stale libnativeconverter.so
# after edits to .c sources only (new JNI symbols missing from the APK).
# Running the externalNativeBuild task explicitly and then reassembling fixes
# it; this script does that, after deleting the packaged native-lib
# intermediates so a stale .so cannot be reused, and then proves the requested
# symbols actually landed in the APK.
#
# Usage: scripts/rebuild-native.sh [debug|release] [jni-symbol-substring ...]
# Example:
#   scripts/rebuild-native.sh debug nativeTranscodeMux nativeRequestStop

set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT="${1:-debug}"
[ $# -gt 0 ] && shift
SYMBOLS=("$@")
CAP="${VARIANT^}" # Debug / Release

# Gradle must run on a full JDK: the VS Code redhat.java JRE it otherwise picks
# up has no jlink, which breaks the Android toolchain's JdkImageTransform.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
GRADLE=(./gradlew "-Dorg.gradle.java.home=$JAVA_HOME")

rm -rf "app/build/intermediates/cmake/$VARIANT" \
       "app/build/intermediates/merged_native_libs/$VARIANT" \
       "app/build/intermediates/stripped_native_libs/$VARIANT"

"${GRADLE[@]}" ":app:externalNativeBuild$CAP"
"${GRADLE[@]}" ":app:assemble$CAP"

APK=$(ls "app/build/outputs/apk/$VARIANT"/*.apk | head -1)
NM=$(ls "$HOME"/Android/Sdk/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm | tail -1)

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -qo "$APK" 'lib/arm64-v8a/libnativeconverter.so' -d "$TMP"
SO="$TMP/lib/arm64-v8a/libnativeconverter.so"

# Read the symbol table once; `grep -q` in a pipe would SIGPIPE nm under
# pipefail and read as a false MISSING.
DEFINED=$("$NM" -D --defined-only "$SO")

echo "APK: $APK"
echo "JNI symbols packaged: $(grep -c ' Java_' <<<"$DEFINED")"

status=0
for sym in ${SYMBOLS[@]+"${SYMBOLS[@]}"}; do
    if grep -q "$sym" <<<"$DEFINED"; then
        echo "OK: $sym"
    else
        echo "MISSING: $sym" >&2
        status=1
    fi
done
exit $status
