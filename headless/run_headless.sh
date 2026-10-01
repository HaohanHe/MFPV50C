#!/usr/bin/env bash
#
# Headless verification of the REAL flight controller shipped on main
# (FlightController / RealDynamics / PropwashModel). No Minecraft, no display.
#
# Pipeline: Gradle compileKotlin -> javac the Java harness against the real
# compiled classes + dependency jars -> run it.
#
# Override tool locations with environment variables:
#   JDK25, JDK21, GRADLE_BIN, GRADLE_USER_HOME
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK25="${JDK25:-/home/user/jdks/jdk-25.0.4.1+1}"
JDK21="${JDK21:-/home/user/jdks/jdk-21.0.12.1+1}"
GRADLE_BIN="${GRADLE_BIN:-/home/user/tools/gradle-9.8.0/bin/gradle}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-home}"
export JAVA_HOME="$JDK25"

echo ">> compileKotlin"
"$GRADLE_BIN" --no-daemon -q -p "$ROOT" compileKotlin \
  -Dorg.gradle.java.installations.paths="$JDK21"

CLS="$ROOT/build/classes/kotlin/main"
JOML=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/org.joml/joml" -name 'joml-*.jar' 2>/dev/null | head -1)
GSON=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/com.google.code.gson/gson" -name 'gson-*.jar' 2>/dev/null | head -1)
KSTD=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.4.20.jar' 2>/dev/null | head -1)
if [ -z "$JOML" ] || [ -z "$KSTD" ]; then
  echo "missing dependency jars; point GRADLE_USER_HOME at a populated cache" >&2
  exit 1
fi
CP="$CLS:$JOML:$KSTD"
[ -n "$GSON" ] && CP="$CP:$GSON"

OUT="$ROOT/headless/out"
mkdir -p "$OUT"
echo ">> javac harness"
"$JDK21/bin/javac" -cp "$CP" -d "$OUT" "$ROOT/headless/FlightControlCheck.java"

echo ">> run"
"$JDK21/bin/java" -cp "$OUT:$CP" FlightControlCheck
