#!/usr/bin/env python3
"""
FXML i18n sweep tool. Reads a JavaFX FXML file, finds every
user-visible `text="..."` attribute, generates a stable
{prefix}.<purpose> key per unique string, and (with --apply)
rewrites the FXML in place using text="%key" form. Writes a
companion .properties addition file with key=value pairs ready to
append to DisplayStrings.properties.

Usage:
    python tools/i18n/fxml-sweep.py <fxml-path> <key-prefix> [--apply]

Example:
    python tools/i18n/fxml-sweep.py \\
        src/main/resources/gui/settingsGUI.fxml \\
        settings.fxml --apply

The key map is heuristic — readable but not perfect. Designed for
quick conversion of large FXML files; manually rename keys after
the fact if the auto-generated names read weirdly.
"""
from __future__ import annotations
import argparse
import io
import re
import sys
from pathlib import Path

# Stick stdout into UTF-8 mode so Unicode chars in printed key=value
# pairs don't trip Windows cp1252 default.
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

ATTR = re.compile(r'text="([A-Z][^"]+)"')


def slugify_short(text: str) -> str:
    """Turn a user-visible string into a short, readable key suffix.
    Use the first 1-3 words for any single-line string; for
    multi-word or sentence-like content cap at 32 chars."""
    s = text.lower()
    s = re.sub(r"[^\w\s]+", " ", s)
    words = [w for w in s.split() if w]
    # Single short word — use as-is.
    if len(words) == 1:
        return words[0]
    # Two words — join them as camelCase-ish.
    if len(words) == 2:
        return words[0] + words[1].capitalize()
    # First three meaningful words for longer text.
    head = words[0] + "".join(w.capitalize() for w in words[1:3])
    return head[:48]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("fxml")
    ap.add_argument("prefix")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    path = Path(args.fxml)
    text = path.read_text(encoding="utf-8")
    seen: dict[str, str] = {}
    used_keys: set[str] = set()
    for match in ATTR.finditer(text):
        value = match.group(1)
        if value in seen:
            continue
        slug = slugify_short(value)
        key = f"{args.prefix}.{slug}"
        # Disambiguate collisions by appending a counter.
        n = 2
        while key in used_keys:
            key = f"{args.prefix}.{slug}{n}"
            n += 1
        used_keys.add(key)
        seen[value] = key

    for value, key in seen.items():
        escaped = value.replace("\\", "\\\\")
        print(f"{key}={escaped}")

    if args.apply:
        result = text
        for value, key in seen.items():
            result = result.replace(f'text="{value}"', f'text="%{key}"')
        path.write_text(result, encoding="utf-8")
        print(f"\nApplied {len(seen)} substitutions to {path.name}.",
              file=sys.stderr)


if __name__ == "__main__":
    main()
