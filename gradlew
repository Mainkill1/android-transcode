#!/usr/bin/env bash
# Text-only bootstrap for the official, checksum-pinned Gradle wrapper.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
HASH=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
hash_file() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d ' ' -f1; else shasum -a 256 "$1" | cut -d ' ' -f1; fi; }
if [[ ! -f "$JAR" ]]; then
    TMP="$(mktemp "$JAR.XXXXXX")"
    trap 'rm -f "$TMP"' EXIT
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 \
        https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar -o "$TMP"
    [[ "$(hash_file "$TMP")" == "$HASH" ]] || { echo 'Gradle wrapper checksum mismatch.' >&2; exit 1; }
    mv "$TMP" "$JAR"
fi
[[ "$(hash_file "$JAR")" == "$HASH" ]] || { echo 'Gradle wrapper checksum mismatch.' >&2; exit 1; }
JAVA=java
[[ -z "${JAVA_HOME:-}" ]] || JAVA="$JAVA_HOME/bin/java"
exec "$JAVA" -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
