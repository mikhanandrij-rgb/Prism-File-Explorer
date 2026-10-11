#!/usr/bin/env python3
"""List open issues of the upstream Prism File Explorer (for triage). Run in CI only."""
import json
import os
import urllib.request

REPO = "Raival-e/Prism-File-Explorer"
headers = {"Accept": "application/vnd.github+json", "User-Agent": "facet-files-ci"}
if os.environ.get("GITHUB_TOKEN"):
    headers["Authorization"] = f"Bearer {os.environ['GITHUB_TOKEN']}"

issues = []
for page in range(1, 6):
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/issues?state=open&per_page=100&page={page}",
        headers=headers,
    )
    batch = json.load(urllib.request.urlopen(req, timeout=30))
    if not batch:
        break
    issues += [i for i in batch if "pull_request" not in i]

out = [f"{len(issues)} open issues"]
for i in issues:
    body = " ".join((i.get("body") or "").split())[:110]
    labels = ",".join(l["name"] for l in i.get("labels", []))
    out.append(f"#{i['number']} [{labels}] (+{i['reactions']['total_count']}, {i['comments']}c) {i['title']} :: {body}")
open("issues.txt", "w").write("\n".join(out))
print("\n".join(out))
