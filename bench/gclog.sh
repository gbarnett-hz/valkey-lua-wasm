#!/bin/bash
# Usage: gclog.sh <backend> <scenario>: one short JMH fork with GC logging, to see which pauses make up gc.time.
here=$(cd "$(dirname "$0")" && pwd)
java -cp "$here/target/membench-0.jar:$here/target/lib/*" org.openjdk.jmh.Main EvalAllocBench -p backend="$1" -p scenario="$2" \
  -f 1 -wi 2 -i 3 -jvmArgsAppend "--enable-native-access=ALL-UNNAMED -Xlog:gc" 2>&1 | grep -E 'GC\(|^Iteration'
