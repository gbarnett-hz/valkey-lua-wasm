"""Summarize footprint.sh output: per config, median [min..max] of every metric across forks, in MiB."""
import statistics
import sys
from collections import defaultdict

runs = defaultdict(list)
for line in open(sys.argv[1]):
    kv = dict(p.split("=", 1) for p in line.split()[1:])
    runs[(kv.pop("backend"), kv.pop("scenario"), int(kv.pop("instances")))].append(kv)

MIB = 1024 * 1024
for key in sorted(runs, key=lambda k: (k[1], k[2], k[0])):
    rs = runs[key]
    print(f"\n{key[0]} {key[1]} x{key[2]}  (n={len(rs)})")
    for m in rs[0]:
        xs = [float(r[m]) for r in rs]
        scale = 1 if m.endswith("wasmPages") or m == "luaHeapKiB" else MIB
        med, lo, hi = statistics.median(xs) / scale, min(xs) / scale, max(xs) / scale
        print(f"  {m:20s} {med:10.2f}  [{lo:.2f} .. {hi:.2f}]")
