#!/usr/bin/env python3
"""Condense an Android logcat for CI annotations: test boundaries and mpv messages, no stacks."""
import re
import sys

KEEP_MPV = re.compile(r"screenshot|hwdec|Using|Could not|reconfig|Opening|vo/|error|fail|timed out|Selected", re.I)
out = []
for line in open(sys.argv[1], errors="replace"):
    if "\tat " in line or "THREAD_STATE" in line:
        continue
    if "TestRunner" in line and re.search(r"started:|failed:|run finished|TestTimedOut|AssertionError", line):
        out.append(line.rstrip())
    elif re.search(r" [EWF] mpv\s*:", line):
        out.append(line.rstrip())
    elif re.search(r" I mpv\s*:", line) and KEEP_MPV.search(line):
        out.append(line.rstrip())
    elif re.search(r"ShaderVisualTest|AndroidRuntime|FATAL|ANR in", line):
        out.append(line.rstrip())
# collapse consecutive duplicates
dedup = [l for i, l in enumerate(out) if i == 0 or l[33:] != out[i - 1][33:]]
print("\n".join(dedup[:400]))
