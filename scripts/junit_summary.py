#!/usr/bin/env python3
"""Print a compact summary of JUnit XML reports: totals, then one line per test."""
import glob
import sys
import xml.etree.ElementTree as ET

files = [f for pattern in sys.argv[1:] for f in glob.glob(pattern, recursive=True)]
total = failed = skipped = 0
lines = []
for path in sorted(set(files)):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        total += 1
        name = case.get("name")
        time = float(case.get("time") or 0)
        failure = case.find("failure") if case.find("failure") is not None else case.find("error")
        if failure is not None:
            failed += 1
            msg = (failure.get("message") or failure.text or "").strip().splitlines()[0][:300]
            lines.append(f"FAIL {name} ({time:.1f}s): {msg}")
        elif case.find("skipped") is not None:
            skipped += 1
            lines.append(f"SKIP {name}")
        else:
            lines.append(f"ok   {name} ({time:.1f}s)")
print(f"{total} tests, {failed} failed, {skipped} skipped")
print("\n".join(lines))
