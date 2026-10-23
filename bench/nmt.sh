#!/bin/bash
# Usage: nmt.sh <backend> <scenario>: one probe fork with NMT's per-category summary printed at exit.
here=$(cd "$(dirname "$0")" && pwd)
java -Xmx4g -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics \
  --enable-native-access=ALL-UNNAMED -cp "$here/target/membench-0.jar:$here/target/lib/*" membench.Footprint "$1" "$2" 1 20 2>/dev/null \
  | grep -E '^-|committed=' | grep -v '^ *(' | head -40
