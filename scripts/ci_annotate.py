#!/usr/bin/env python3
"""Turn a log file into GitHub Actions annotations.

Usage:
  ci_annotate.py errors <gradle.log>   # Kotlin/Java/AAPT/Gradle errors -> ::error::
  ci_annotate.py notice <file> [title] # whole file -> one ::notice::

Annotations are readable through the REST API (check-runs/{id}/annotations),
so build results can be inspected without downloading raw logs.
"""
import re
import sys

MAX_CHARS = 60000  # stay well below the annotation size limit


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def errors(path: str) -> None:
    lines = open(path, errors="replace").read().splitlines()
    patterns = [
        re.compile(r"^e: "),                    # Kotlin compiler
        re.compile(r"error:", re.I),             # javac / aapt / generic
        re.compile(r"^\* What went wrong"),
        re.compile(r"FAILED$"),
        re.compile(r"^> "),
        re.compile(r"Exception|AssertionError|expected:<"),
    ]
    picked = []
    for i, line in enumerate(lines):
        if any(p.search(line) for p in patterns):
            picked.append(line)
            if line.startswith("* What went wrong"):
                picked.extend(lines[i + 1:i + 15])
    seen, out = set(), []
    for line in picked:
        if line not in seen:
            seen.add(line)
            out.append(line)
    body = "\n".join(out)[:MAX_CHARS] or "\n".join(lines[-80:])
    # Split into a few annotations (each is capped by GitHub)
    chunk = 4000  # GitHub truncates annotation messages at ~4 KB
    for n, i in enumerate(range(0, len(body), chunk)):
        if n >= 8:
            break
        print(f"::error title=build-errors-{n}::{esc(body[i:i + chunk])}")


def notice(path: str, title: str) -> None:
    body = open(path, errors="replace").read()[:MAX_CHARS]
    lines, parts, cur = body.splitlines(), [], ""
    for line in lines:
        if len(cur) + len(line) + 1 > 4000 and cur:
            parts.append(cur)
            cur = ""
        cur += line[:4000] + "\n"
    if cur:
        parts.append(cur)
    for n, part in enumerate(parts[:10]):
        print(f"::notice title={title}-{n}::{esc(part)}")


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "errors":
        errors(sys.argv[2])
    else:
        notice(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "report")
