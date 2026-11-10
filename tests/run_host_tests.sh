#!/bin/sh
# Valida a FFI numa JVM de desktop/Termux (sem Android). Requer JDK (javac) e g++/clang++.
# Termux: pkg install openjdk-21 clang   (JAVA_HOME costuma ser $PREFIX/lib/jvm/java-21-openjdk)
set -e
cd "$(dirname "$0")/.."
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which javac)")")")}"
CXX="${CXX:-c++}"
OUT=build-host; mkdir -p "$OUT/classes"
SRC=app/src/main/java/com/slipscript/ide
$CXX -shared -fPIC -std=c++17 -O1 -I"$JH/include" -I"$JH/include/linux" app/src/main/cpp/slipffi.cpp -o "$OUT/libslipffi.so"
$CXX -shared -fPIC -std=c++17 -O1 app/src/main/cpp/demo/slipdemo.cpp -o "$OUT/libslipdemo.so"
javac -encoding UTF-8 -d "$OUT/classes" $SRC/SlipInterpreter.java $SRC/SlipUi.java $SRC/NativeBridge.java $SRC/NativeLibraryManager.java $SRC/SlipCli.java tests/NativeFfiTests.java
java -Dslip.ffi.lib="$PWD/$OUT/libslipffi.so" -cp "$OUT/classes" NativeFfiTests "$OUT/libslipdemo.so"
