"""Turns the iPhone screenshots printed into a GitHub Actions log back into JPEG files.

The iOS workflow prints each picture as base64 between "=====BEGIN name" and "=====END name"
lines, because the account's artifact storage is full. Usage:

    python3 tools/ios/screenshots.py job-log.txt out-dir/
"""
import base64
import os
import re
import sys

log, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
name, parts = None, []
for line in open(log, encoding="utf-8", errors="replace"):
    # Each log line starts with a timestamp: "2026-11-13T03:26:30.7065000Z ..."
    text = re.sub(r"^\S+Z ", "", line.rstrip("\n"))
    if text.startswith("=====BEGIN "):
        name, parts = text[len("=====BEGIN "):].strip(), []
    elif text.startswith("=====END ") and name:
        path = os.path.join(out, name + ".jpg")
        with open(path, "wb") as f:
            f.write(base64.b64decode("".join(parts)))
        print(path)
        name = None
    elif name:
        parts.append(text.strip())
