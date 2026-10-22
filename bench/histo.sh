#!/bin/bash
# Usage: histo.sh <backend> <scenario> <evals> [jvm args]
here=$(cd "$(dirname "$0")" && pwd)
wasm=$here/../lua/lua.wasm
b=$1 s=$2 n=$3
shift 3
java -Xmx4g "$@" -Dwasm="$wasm" --enable-native-access=ALL-UNNAMED -cp "$here/target/membench-0.jar:$here/target/lib/*" \
  membench.Histo "$b" "$s" "$n" 2>/dev/null
