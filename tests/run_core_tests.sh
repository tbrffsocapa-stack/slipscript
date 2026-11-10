#!/bin/sh
# Testes do núcleo da linguagem. Requer apenas um JDK (Termux: pkg install openjdk-21) — não precisa de NDK, g++ nem Android.
set -e
cd "$(dirname "$0")/.."
OUT=build-host; mkdir -p "$OUT/core-classes"
SRC=app/src/main/java/com/slipscript/ide
javac -encoding UTF-8 -d "$OUT/core-classes" $SRC/SlipInterpreter.java $SRC/SlipUi.java $SRC/SlipJson.java $SRC/SlipSys.java $SRC/NativeBridge.java $SRC/NativeLibraryManager.java tests/SlipCoreTests.java
java -Dfile.encoding=UTF-8 -cp "$OUT/core-classes" SlipCoreTests
