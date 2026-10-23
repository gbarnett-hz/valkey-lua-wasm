#!/bin/bash
# Usage: jmh.sh <out.json> [extra JMH args]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$1
shift
java --enable-native-access=ALL-UNNAMED -cp "$here/target/membench-0.jar:$here/target/lib/*" \
  org.openjdk.jmh.Main EvalAllocBench -prof gc -jvmArgsAppend "--enable-native-access=ALL-UNNAMED" \
  -rf json -rff "$out" "$@"
