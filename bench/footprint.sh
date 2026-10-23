#!/bin/bash
# Usage: footprint.sh <forks> <evals> <out> <config>...   config = backend:scenario:instances
# Runs every config <forks> times, each in a fresh JVM, in shuffled order so drift doesn't favour one config.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
forks=$1 evals=$2 out=$3
shift 3
runs=()
for c in "$@"; do for ((i = 0; i < forks; i++)); do runs+=("$c"); done; done
printf '%s\n' "${runs[@]}" | sort -R | while IFS=: read -r b s n; do
  java -Xmx4g -XX:NativeMemoryTracking=summary --enable-native-access=ALL-UNNAMED \
    -cp "$here/target/membench-0.jar:$here/target/lib/*" membench.Footprint "$b" "$s" "$n" "$evals" 2>/dev/null \
    | grep '^RESULT' | tee -a "$out"
done
