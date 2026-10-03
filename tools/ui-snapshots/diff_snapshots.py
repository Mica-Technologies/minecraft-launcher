#!/usr/bin/env python3
# Copyright (c) 2026 Mica Technologies
#
# This program is free software: you can redistribute it and/or modify it
# under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License,
# or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty
# of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
# See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
"""Compare two trees of UI snapshots written by ThemeSnapshotFxTest.

Usage:
    python3 tools/ui-snapshots/diff_snapshots.py BEFORE_DIR AFTER_DIR [--out DIFF_DIR] [--tolerance N]

Prints the share of changed pixels per image, worst first, and writes a diff PNG for every changed
image: the after image dimmed, with changed pixels painted magenta. A pixel counts as changed when any
channel differs by more than --tolerance (default 8, which absorbs antialiasing noise).
Requires Pillow (pip install pillow).
"""
import argparse
import sys
from pathlib import Path

from PIL import Image, ImageChops


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('--out', type=Path, default=Path('build/target/snapshots/diff'))
    parser.add_argument('--tolerance', type=int, default=8)
    args = parser.parse_args()

    rows = []
    for before in sorted(args.before.rglob('*.png')):
        rel = before.relative_to(args.before)
        after = args.after / rel
        if not after.exists():
            rows.append((100.0, str(rel), 'missing in after'))
            continue
        a = Image.open(before).convert('RGB')
        b = Image.open(after).convert('RGB')
        if a.size != b.size:
            rows.append((100.0, str(rel), f'size {a.size} -> {b.size}'))
            continue
        delta = ImageChops.difference(a, b).convert('L').point(lambda v: 255 if v > args.tolerance else 0)
        changed = sum(1 for v in delta.getdata() if v)
        share = 100.0 * changed / (a.size[0] * a.size[1])
        if changed:
            out = args.out / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            dimmed = Image.blend(b, Image.new('RGB', b.size, (0, 0, 0)), 0.6)
            dimmed.paste(Image.new('RGB', b.size, (255, 0, 255)), mask=delta)
            dimmed.save(out)
        rows.append((share, str(rel), ''))
    for path in sorted(args.after.rglob('*.png')):
        if not (args.before / path.relative_to(args.after)).exists():
            rows.append((100.0, str(path.relative_to(args.after)), 'new in after'))

    rows.sort(key=lambda r: -r[0])
    changed_rows = [r for r in rows if r[0] > 0]
    for share, rel, note in changed_rows:
        print(f'{share:7.3f}%  {rel}  {note}'.rstrip())
    print(f'{len(changed_rows)} of {len(rows)} images changed; diffs in {args.out}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
