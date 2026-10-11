#!/usr/bin/env python3
"""Print the latest stable version of every dependency in gradle/libs.versions.toml.

Reads maven-metadata.xml from Google Maven, Maven Central, the Gradle plugin
portal and JitPack. Used by the "Dependency report" workflow; network access
happens only when this script is run explicitly.
"""
import re
import sys
import tomllib
import urllib.request
import xml.etree.ElementTree as ET

REPOS = [
    "https://dl.google.com/android/maven2",
    "https://repo1.maven.org/maven2",
    "https://plugins.gradle.org/m2",
    "https://jitpack.io",
]
UNSTABLE = re.compile(r"(alpha|beta|rc|dev|eap|snapshot|m\d|preview|cr\d)", re.I)

PLUGIN_COORDS = {
    "com.android.application": "com.android.tools.build:gradle",
    "com.android.test": "com.android.tools.build:gradle",
    "org.jetbrains.kotlin.android": "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "org.jetbrains.kotlin.plugin.compose": "org.jetbrains.kotlin:compose-compiler-gradle-plugin",
    "androidx.baselineprofile": "androidx.benchmark:benchmark-baseline-profile-gradle-plugin",
}


def versions(coord: str) -> list[str]:
    group, artifact = coord.split(":")
    for repo in REPOS:
        url = f"{repo}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                root = ET.fromstring(r.read())
            return [v.text for v in root.iter("version") if v.text]
        except Exception:
            continue
    return []


def key(v: str):
    return [int(p) if p.isdigit() else p for p in re.split(r"[.\-]", v)]


def latest_stable(coord: str) -> str:
    vs = [v for v in versions(coord) if not UNSTABLE.search(v)]
    if not vs:
        return "?"
    try:
        return max(vs, key=key)
    except TypeError:
        return vs[-1]


def main(path: str) -> None:
    data = tomllib.load(open(path, "rb"))
    ver = data["versions"]
    rows = {}
    for lib in data.get("libraries", {}).values():
        coord = lib.get("module") or f"{lib['group']}:{lib['name']}"
        ref = lib.get("version", {}).get("ref") if isinstance(lib.get("version"), dict) else None
        if ref:
            rows.setdefault(ref, coord)
    for plugin in data.get("plugins", {}).values():
        ref = plugin["version"]["ref"]
        coord = PLUGIN_COORDS.get(plugin["id"])
        if coord:
            rows.setdefault(ref, coord)
    print(f"{'version key':32} {'current':14} {'latest stable':14} coordinate")
    for ref, coord in sorted(rows.items()):
        cur = ver.get(ref, "?")
        new = latest_stable(coord)
        mark = "" if new in (cur, "?") else "  <-- update"
        print(f"{ref:32} {cur:14} {new:14} {coord}{mark}")
    try:
        with urllib.request.urlopen("https://services.gradle.org/versions/current", timeout=20) as r:
            print("\nGradle latest:", re.search(r'"version"\s*:\s*"([^"]+)"', r.read().decode()).group(1))
    except Exception as e:
        print("\nGradle latest: ?", e)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "gradle/libs.versions.toml")
