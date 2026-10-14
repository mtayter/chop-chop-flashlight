#!/bin/bash
# Headless tuner/detector tests (no Android needed).
# Usage: bash run-tests.sh
set -e
cd "$(dirname "$0")"
JH=~/workspace/tooling/jdk/jdk-17.0.20.1+1
D=/tmp/dgtest-$$
mkdir -p "$D/classes"
"$JH/bin/javac" -encoding UTF-8 -d "$D/classes" \
  manual/java/com/tayter/chopchopflashlight/ChopDetector.java \
  manual/java/com/tayter/chopchopflashlight/ChopTuner.java \
  manual/test/com/tayter/chopchopflashlight/DirGateTest.java
"$JH/bin/java" -cp "$D/classes" com.tayter.chopchopflashlight.DirGateTest
rm -rf "$D"
