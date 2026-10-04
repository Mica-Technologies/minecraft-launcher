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
"""Builds the launcher's icon set into LauncherIcons.java.

The set follows Mica's design guidelines: Google's Material Symbols, Rounded, filled, and no other
icon families on the same screen. Most icons are Material Symbols as published
(material-rounded-source.json, fetched from github.com/google/material-design-icons, Apache 2.0),
normalised from Material's 960-unit box to a 24 px box. A few categories get Minecraft-flavoured
glyphs drawn here in the same language (filled shapes, softened corners, detail cut as gaps):
a grass block for Game, a pickaxe for Advanced, a redstone torch for RGB lighting, and a blocky
player head for Account.

    python3 tools/launcher-icons/build_icons.py
"""
import json
import math
import re
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE.parent.parent / 'src/main/java/com/micatechnologies/minecraft/launcher/gui/LauncherIcons.java'

# ---------------------------------------------------------------- Material path normalisation

TOK = re.compile(r'[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?')
ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}


def fmt(v):
    s = f'{v:.3f}'.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def normalise(d, scale=24 / 960, ty=960):
    """Rescales a Material Symbols path (box 0,-960 960x960) to a 24 px box at the origin."""
    toks = TOK.findall(d)
    if toks and toks[0] == 'm':  # SVG: a path's first moveto is absolute even when lowercase
        toks[0] = 'M'
        if len(toks) > 3 and not re.match(r'[A-Za-z]', toks[3]):
            toks.insert(3, 'l')
    out, i, cmd = [], 0, None
    while i < len(toks):
        t = toks[i]
        if re.match(r'[A-Za-z]', t):
            cmd = t
            i += 1
            out.append(cmd)
            if cmd in 'Zz':
                continue
        n = ARGS[cmd.upper()]
        vals = [float(x) for x in toks[i:i + n]]
        i += n
        rel, c = cmd.islower(), cmd.upper()
        if c == 'H':
            vals = [vals[0] * scale]
        elif c == 'V':
            vals = [vals[0] * scale if rel else (vals[0] + ty) * scale]
        elif c == 'A':
            rx, ry, rot, la, sw, x, y = vals
            vals = [rx * scale, ry * scale, rot, la, sw, x * scale, y * scale if rel else (y + ty) * scale]
        else:
            vals = [v * scale if (k % 2 == 0 or rel) else (v + ty) * scale for k, v in enumerate(vals)]
        out.append(' '.join(str(int(v)) if c == 'A' and k in (3, 4) else fmt(v) for k, v in enumerate(vals)))
        if i < len(toks) and not re.match(r'[A-Za-z]', toks[i]):
            cmd = {'M': 'L', 'm': 'l'}.get(cmd, cmd)
            out.append(cmd)
    return ' '.join(out)

# ---------------------------------------------------------------- custom glyph geometry


def area(pts):
    """Signed area; positive is clockwise on screen (y down)."""
    return sum(pts[i][0] * pts[(i + 1) % len(pts)][1] - pts[(i + 1) % len(pts)][0] * pts[i][1]
               for i in range(len(pts))) / 2


def rounded(pts, r, hole=False):
    """A closed polygon with every corner softened by radius r (clamped to half each edge).
    Outlines wind clockwise and holes counter-clockwise, so non-zero filling cuts the holes."""
    if (area(pts) > 0) == hole:
        pts = pts[::-1]
    n, parts = len(pts), []
    for i in range(n):
        p, v, q = pts[i - 1], pts[i], pts[(i + 1) % n]
        def toward(a, b, dist):
            dx, dy = b[0] - a[0], b[1] - a[1]
            length = math.hypot(dx, dy)
            k = min(dist, length / 2) / length
            return a[0] + dx * k, a[1] + dy * k
        a, b = toward(v, p, r), toward(v, q, r)
        parts.append(('M' if i == 0 else 'L') + f' {fmt(a[0])} {fmt(a[1])} Q {fmt(v[0])} {fmt(v[1])} {fmt(b[0])} {fmt(b[1])}')
    return ' '.join(parts) + ' Z'


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def pill(cx, cy, length, width, angle_deg):
    """A rounded bar centred at (cx, cy), `length` long along angle_deg."""
    a = math.radians(angle_deg)
    ux, uy = math.cos(a) * length / 2, math.sin(a) * length / 2
    nx, ny = -math.sin(a) * width / 2, math.cos(a) * width / 2
    pts = [(cx - ux + nx, cy - uy + ny), (cx + ux + nx, cy + uy + ny), (cx + ux - nx, cy + uy - ny), (cx - ux - nx, cy - uy - ny)]
    return rounded(pts, width / 2)


def grass_block():
    """An isometric grass block: the top face, then each side face cut into a grass band and the
    dirt below it, the cut stepping down once in the middle like grass hanging over the edge."""
    g, k = 1.3, 4.5 / 8.5               # gap between faces; slope of the iso edges
    parts = [rounded([(12, 2.2), (20.6, 6.75), (12, 11.3 - g * 0.6), (3.4, 6.75)], 1.1)]

    def side(x_outer, x_inner):
        def top(x):
            return 6.75 + g + abs(x - x_outer) * k

        def bottom(x):
            return 16.9 + abs(x - x_outer) * k
        mid = (x_outer + x_inner) / 2
        band, step, cut = 2.6, 1.1, 1.0
        # The grass's lower edge: band deep on the outer half, a step deeper on the inner half.
        edge = [(x_outer, top(x_outer) + band), (mid, top(mid) + band),
                (mid, top(mid) + band + step), (x_inner, top(x_inner) + band + step)]
        grass = [(x_outer, top(x_outer)), (x_inner, top(x_inner))] + edge[::-1]
        dirt = [(x, y + cut) for x, y in edge] + [(x_inner, bottom(x_inner)), (x_outer, bottom(x_outer))]
        return [rounded(grass, 0.5), rounded(dirt, 0.7)]
    parts += side(3.4, 12 - g / 2)
    parts += side(20.6, 12 + g / 2)
    return ' '.join(parts)


def pickaxe():
    """A pickaxe: a crescent head with a point at each end, set across a rounded diagonal handle."""
    # Head centred on the handle's line near the top right; picks run perpendicular to the handle.
    cx, cy, half = 15.4, 8.6, 7.6
    ux, uy = math.sqrt(0.5), math.sqrt(0.5)          # along the head
    ox, oy = math.sqrt(0.5), -math.sqrt(0.5)         # outward, away from the handle
    p1 = (cx - ux * half, cy - uy * half)
    p2 = (cx + ux * half, cy + uy * half)
    outer = (cx + ox * 8.4, cy + oy * 8.4)
    inner = (cx + ox * 0.6, cy + oy * 0.6)
    head = (f'M {fmt(p1[0])} {fmt(p1[1])} Q {fmt(outer[0])} {fmt(outer[1])} {fmt(p2[0])} {fmt(p2[1])} '
            f'Q {fmt(inner[0])} {fmt(inner[1])} {fmt(p1[0])} {fmt(p1[1])} Z')
    # The handle runs through the head and pokes out a little past it, as a real pick's does.
    handle = pill(10.6, 13.4, 19.2, 2.8, -45)
    return head + ' ' + handle


def redstone_torch():
    """A redstone torch: a stick, a glowing square head and five rays of light."""
    parts = [rounded(rect(10.6, 12.2, 13.4, 21.8), 1.4),
             rounded(rect(8.9, 5.3, 15.1, 11.3), 1.8),
             pill(12, 2.1, 2.6, 1.7, 90),
             pill(6.2, 8.3, 2.6, 1.7, 0),
             pill(17.8, 8.3, 2.6, 1.7, 0),
             pill(7.4, 4.0, 2.6, 1.7, 45),
             pill(16.6, 4.0, 2.6, 1.7, -45)]
    return ' '.join(parts)


def player_head():
    """A blocky player head with cut-out eyes, over rounded shoulders."""
    parts = [rounded(rect(6, 2.4, 18, 14.4), 2.6),
             rounded(rect(8.3, 7.0, 10.7, 9.4), 0.5, hole=True),
             rounded(rect(13.3, 7.0, 15.7, 9.4), 0.5, hole=True),
             rounded(rect(10.4, 11.0, 13.6, 12.3), 0.45, hole=True),
             rounded([(3.4, 21.6), (3.4, 19.6), (6.6, 16.4), (17.4, 16.4), (20.6, 19.6), (20.6, 21.6)], 2.2)]
    return ' '.join(parts)

# ---------------------------------------------------------------- the set

def build():
    src = json.load(open(HERE / 'material-rounded-source.json'))
    m = lambda name: normalise(src[name]['d'])
    icons = [
        ('ACCOUNT', player_head(), 'Account: a blocky player head (custom).'),
        ('GAME', grass_block(), 'Game: a grass block (custom).'),
        ('APPEARANCE', m('palette'), 'Appearance: Material Symbols "palette".'),
        ('ADVANCED', pickaxe(), 'Advanced: a pickaxe (custom).'),
        ('NETWORK', m('wifi'), 'Network: Material Symbols "wifi".'),
        ('SECURITY', m('shield'), 'Security: Material Symbols "shield".'),
        ('SYSTEM', m('settings'), 'System: Material Symbols "settings".'),
        ('DISCORD', m('forum'), 'Discord: Material Symbols "forum".'),
        ('RGB', redstone_torch(), 'RGB lighting: a redstone torch (custom).'),
        ('ABOUT', m('info'), 'About: Material Symbols "info".'),
        ('REFRESH', m('refresh'), 'Refresh: Material Symbols "refresh".'),
        ('DOWNLOAD', m('download'), 'Download / update: Material Symbols "download".'),
        ('CHECK', m('check'), 'Check: Material Symbols "check".'),
        ('CLOSE', m('close'), 'Close / failed: Material Symbols "close".'),
        ('REMOVE', m('remove'), 'Remove / skipped: Material Symbols "remove".'),
        ('DOT', rounded(rect(9, 9, 15, 15), 3), 'A 6 px dot, centred (custom).'),
        ('LOCK', m('lock'), 'Lock: Material Symbols "lock".'),
        ('PEOPLE', m('group'), 'People: Material Symbols "group".'),
    ]
    header = (HERE.parent.parent / 'src/main/java/com/micatechnologies/minecraft/launcher/gui/ShapeScale.java').read_text()
    header = header[:header.index('package ')]
    body = [header + 'package com.micatechnologies.minecraft.launcher.gui;\n',
            '/**',
            ' * The launcher\'s icons, as SVG path data in a 24 px box. Generated by',
            ' * {@code tools/launcher-icons/build_icons.py}; don\'t edit by hand.',
            ' *',
            ' * <p>Following Mica\'s design guidelines, they are Google\'s Material Symbols, Rounded and filled',
            ' * (Apache 2.0), with a few Minecraft-flavoured glyphs drawn in the same language: a grass block,',
            ' * a pickaxe, a redstone torch and a blocky player head. Use these rather than icons from any',
            ' * other family. Draw them with an {@code SVGPath}, scaled from 24 px to the size you need.',
            ' *',
            ' * @since 2026.10',
            ' */',
            'public final class LauncherIcons',
            '{']
    for name, d, doc in icons:
        body += [f'    /** {doc} */', f'    public static final String {name} =', f'            "{d}";', '']
    body += ['    private LauncherIcons()', '    {', '    }', '}', '']
    OUT.write_text('\n'.join(body))
    print(f'wrote {OUT.relative_to(HERE.parent.parent)} ({len(icons)} icons)')


if __name__ == '__main__':
    build()
