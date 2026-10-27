#!/bin/bash
# Usage: [BENCH=CompileBench] jmh.sh <out.json> [extra JMH args]; BENCH defaults to EvalAllocBench
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$1
shift
java --enable-native-access=ALL-UNNAMED -cp "$here/target/membench-0.jar:$here/target/lib/*" \
  org.openjdk.jmh.Main "${BENCH:-EvalAllocBench}" -prof gc -jvmArgsAppend "--enable-native-access=ALL-UNNAMED" \
  -rf json -rff "$out" "$@"
