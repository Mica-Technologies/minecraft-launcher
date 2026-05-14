#!/usr/bin/env python3
"""
Generate the WiX MSI installer's banner + dialog bitmaps from the launcher icon
and a brand-matched palette. Overwrites:

    deploy/windows/installer-resources/installer-banner.bmp     (493x58)
    deploy/windows/installer-resources/installer-background.bmp (493x312)

WiX UI flow (jpackage uses WixUI_InstallDir):

* The banner is shown at the top of every step dialog (License, InstallDir,
  ShortcutPrompt, VerifyReady, Progress). WiX overlays the dialog title (~X 20px
  → 287px, Y 8px) and description text (~X 33px → 406px, Y 30px) in default
  BLACK on top of the banner, so the LEFT portion of the banner must be light
  enough for black text to be readable. The remaining RIGHT ~85 pixels are a
  safe branding zone.

* The dialog bitmap is the upper background of the Welcome / Exit screens. WiX
  overlays the title and body text on the right side of the bitmap (~X 180px
  onward) again in default black. So the LEFT ~180 pixels are the safe art zone
  and the RIGHT must be a light background.

The layout we produce:

* Banner: light cream/white left panel with a thin brand-color accent stripe at
  the bottom, and a small dark right panel showing the launcher icon plus a
  subtle pixel-block motif (Minecraft nod).

* Dialog: a dark left panel (about 190px wide) with the launcher icon, the
  product wordmark and tagline stacked vertically, a vertical brand accent
  rule dividing the two panels, and a light right panel with a very faint
  pixel pattern so WiX's text reads cleanly while still feeling on-brand.

Both BMPs are written as 24-bit uncompressed BMP (PIL's default for RGB mode).
WiX rejects 32-bit / indexed / RLE variants.

Run from the project root:
    python tools/scripts/generate_installer_bitmaps.py
"""
from __future__ import annotations

import os
import random
from PIL import Image, ImageDraw, ImageFont

# Brand palette — pulled from ui-tokens-light.css + ui-tokens-dark.css. We use
# the LIGHT token primary because the text-overlay panel is light, so the
# accent stripe needs to read on a light background.
COLOR_PRIMARY        = (2, 122, 242)     # -color-primary (light)   #027AF2
COLOR_PRIMARY_DEEP   = (0, 89, 178)      # -color-primary-pressed   #0059B2
COLOR_SECONDARY      = (57, 147, 57)     # -color-secondary (light) #399339
COLOR_DARK_BG        = (12, 16, 23)      # -color-bg (dark)         #0C1017
COLOR_DARK_BG_SOFT   = (16, 21, 30)      # -color-bg-soft (dark)    #10151E
COLOR_DARK_SURFACE   = (24, 32, 48)      # extended dark blue       (custom)
COLOR_LIGHT_BG       = (255, 255, 255)   # banner/dialog text panel
COLOR_LIGHT_BG_SOFT  = (245, 247, 251)   # very subtle off-white
COLOR_LIGHT_BG_TINT  = (235, 241, 250)   # faint blue-tinted cream
COLOR_DIVIDER        = (210, 218, 230)   # subtle divider on light side
COLOR_TEXT_LIGHT     = (245, 248, 252)   # near-white text on dark side
COLOR_TEXT_DIM       = (170, 182, 204)   # tagline on dark side

PROJECT_ROOT     = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON_PATH        = os.path.join(PROJECT_ROOT, "src/main/resources/micaminecraftlauncher.png")
BANNER_PATH      = os.path.join(PROJECT_ROOT, "deploy/windows/installer-resources/installer-banner.bmp")
DIALOG_PATH      = os.path.join(PROJECT_ROOT, "deploy/windows/installer-resources/installer-background.bmp")


def lerp(a, b, t):
    return int(a + (b - a) * t)


def lerp_color(c1, c2, t):
    return (lerp(c1[0], c2[0], t), lerp(c1[1], c2[1], t), lerp(c1[2], c2[2], t))


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", size, top)
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        color = lerp_color(top, bottom, t)
        for x in range(w):
            px[x, y] = color
    return img


def diagonal_gradient(size, top_left, bottom_right, mid=None):
    """Linear gradient along the top-left → bottom-right diagonal."""
    w, h = size
    img = Image.new("RGB", size, top_left)
    px = img.load()
    diag = w + h
    for y in range(h):
        for x in range(w):
            t = (x + y) / diag
            if mid is not None:
                if t < 0.5:
                    px[x, y] = lerp_color(top_left, mid, t * 2.0)
                else:
                    px[x, y] = lerp_color(mid, bottom_right, (t - 0.5) * 2.0)
            else:
                px[x, y] = lerp_color(top_left, bottom_right, t)
    return img


def horizontal_gradient(size, left, right):
    w, h = size
    img = Image.new("RGB", size, left)
    px = img.load()
    for x in range(w):
        t = x / max(1, w - 1)
        color = lerp_color(left, right, t)
        for y in range(h):
            px[x, y] = color
    return img


def soft_glow(size, center, radius, color, max_alpha=120):
    """Returns an RGBA layer with a radial soft glow at `center`."""
    w, h = size
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    px = layer.load()
    cx, cy = center
    r2 = radius * radius
    for y in range(h):
        for x in range(w):
            dx = x - cx
            dy = y - cy
            d2 = dx * dx + dy * dy
            if d2 >= r2:
                continue
            t = 1.0 - (d2 ** 0.5) / radius
            t = t * t
            px[x, y] = (color[0], color[1], color[2], int(max_alpha * t))
    return layer


def voxel_motif(size, rect, color, alpha=30, cell=8, density=0.5, seed=0):
    """Sparse pixelated voxel grid inside `rect` — a quiet Minecraft nod.
    `rect` is (x0, y0, x1, y1)."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    rng = random.Random(seed)
    x0, y0, x1, y1 = rect
    for gy in range(y0, y1, cell):
        for gx in range(x0, x1, cell):
            if rng.random() > density:
                continue
            shade = rng.uniform(0.5, 1.0)
            r = int(color[0] * shade)
            g = int(color[1] * shade)
            b = int(color[2] * shade)
            draw.rectangle((gx, gy, gx + cell - 1, gy + cell - 1),
                           fill=(r, g, b, alpha))
    return layer


def load_icon():
    return Image.open(ICON_PATH).convert("RGBA")


def find_font(preferred_names, size):
    candidates = [
        "C:/Windows/Fonts/" + name for name in preferred_names
    ] + [
        "/Library/Fonts/" + name for name in preferred_names
    ] + [
        "/usr/share/fonts/truetype/dejavu/" + name for name in preferred_names
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def make_banner():
    """493x58 banner. Light surface end-to-end so WiX's default dark title +
    description text reads cleanly across the whole banner. A small icon
    floats on the right with a faint brand-color glow underneath. We do NOT
    paint a bottom accent stripe — WiX draws its own BannerLine at the bottom
    of the banner area, and stacking our own line on top of it looks muddy."""
    W, H = 493, 58

    # Whole-banner gradient: white → very subtle blue tint. Keeps the text
    # zone consistent left-to-right (no hard panel transition for the title
    # or description to cross).
    img = vertical_gradient((W, H), COLOR_LIGHT_BG, COLOR_LIGHT_BG_TINT).convert("RGBA")

    # Soft brand glow behind where the icon will sit — gives the icon presence
    # without introducing a dark panel.
    glow = soft_glow((W, H), (W - 30, H // 2), 32, COLOR_PRIMARY, max_alpha=55)
    img = Image.alpha_composite(img, glow)

    # Icon on the right — sized to clear the WiX description text zone (which
    # ends at ~X=406 px) so the icon sits in a safe area with breathing room.
    ICON_SIZE = 36
    icon = load_icon().resize((ICON_SIZE, ICON_SIZE), Image.LANCZOS)
    icon_x = W - ICON_SIZE - 12
    icon_y = (H - ICON_SIZE) // 2
    img.paste(icon, (icon_x, icon_y), icon)

    return img.convert("RGB")


def make_dialog():
    """493x312 dialog background. Dark left brand panel + light right text panel.

    WixUI_InstallDir's WelcomeDlg and ExitDialog overlay their title at
    X=135 IU (≈180 px) and body text at the same X with 220 IU (≈293 px) of
    width. The dark panel therefore MUST end before X=180 px so the first
    pixels of each text glyph land on the light side. We give a small
    safety buffer and clamp the panel at 165 px so even bold strokes have
    clean light space behind them."""
    W, H = 493, 312
    LEFT_W = 165

    # Dark left panel — diagonal brand gradient
    dark_panel = diagonal_gradient(
        (LEFT_W, H),
        (10, 14, 24),
        (8, 30, 56),
        mid=(14, 22, 40),
    )

    # Light right panel — vertical gradient white → very subtle cream
    right_panel = vertical_gradient((W - LEFT_W, H), COLOR_LIGHT_BG, COLOR_LIGHT_BG_SOFT)

    img = Image.new("RGB", (W, H), COLOR_LIGHT_BG)
    img.paste(dark_panel, (0, 0))
    img.paste(right_panel, (LEFT_W, 0))

    img = img.convert("RGBA")

    # Brand glows on the dark panel — primary blue top, secondary green bottom
    glow_a = soft_glow((W, H), (LEFT_W * 0.35, H * 0.25), 180, COLOR_PRIMARY, max_alpha=85)
    glow_b = soft_glow((W, H), (LEFT_W * 0.85, H * 0.85), 160, COLOR_SECONDARY, max_alpha=55)
    img = Image.alpha_composite(img, glow_a)
    img = Image.alpha_composite(img, glow_b)

    # Sparse voxel pattern on the dark panel — quiet Minecraft texture
    voxels_dark = voxel_motif(
        (W, H),
        rect=(0, 0, LEFT_W, H),
        color=COLOR_PRIMARY,
        alpha=18,
        cell=10,
        density=0.18,
        seed=42,
    )
    img = Image.alpha_composite(img, voxels_dark)

    # Even fainter voxels on the bottom-right of the light panel — for warmth
    voxels_light = voxel_motif(
        (W, H),
        rect=(LEFT_W + 40, H - 120, W, H),
        color=COLOR_PRIMARY,
        alpha=12,
        cell=10,
        density=0.10,
        seed=99,
    )
    img = Image.alpha_composite(img, voxels_light)

    # Vertical divider — soft outer line + brand-color inner stripe
    divider = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ddraw = ImageDraw.Draw(divider)
    ddraw.line((LEFT_W,     0, LEFT_W,     H - 1), fill=COLOR_PRIMARY + (230,))
    ddraw.line((LEFT_W + 1, 0, LEFT_W + 1, H - 1), fill=COLOR_DIVIDER + (180,))
    img = Image.alpha_composite(img, divider)

    # Icon on the dark left panel. The launcher icon is itself the "MICA"
    # wordmark (four colored pillars), so the panel uses the icon as the
    # primary brand statement. Render it large enough to read at a glance —
    # 120x120 centered horizontally, biased toward the upper third so the
    # caption beneath has room to breathe.
    icon = load_icon().resize((120, 120), Image.LANCZOS)
    icon_x = (LEFT_W - 120) // 2
    icon_y = 60
    img.paste(icon, (icon_x, icon_y), icon)

    draw = ImageDraw.Draw(img)

    # Product name + tagline beneath the wordmark. The MICA icon glyph
    # carries the brand mark, but the full product name still reads
    # "Mica Minecraft Launcher" everywhere else (bundle name, ARP entry,
    # window title), so we repeat the full name here for consistency.
    # The name wraps onto two lines because "Mica Minecraft Launcher"
    # doesn't fit in the 165px-wide dark panel at a comfortable weight.
    name_font = find_font(["seguisb.ttf", "segoeuib.ttf", "Segoe UI.ttf",
                           "DejaVuSans-Bold.ttf"], 14)
    tag_font  = find_font(["segoeui.ttf", "Segoe UI.ttf",
                           "DejaVuSans.ttf"], 9)

    def centered_text(text, font, y, fill):
        bbox = draw.textbbox((0, 0), text, font=font)
        w = bbox[2] - bbox[0]
        draw.text(((LEFT_W - w) // 2, y), text, font=font, fill=fill)

    base_y = icon_y + 120 + 12
    centered_text("Mica Minecraft",  name_font, base_y,      COLOR_TEXT_LIGHT)
    centered_text("Launcher",        name_font, base_y + 18, COLOR_TEXT_LIGHT)
    centered_text("Forge modpacks,", tag_font,  base_y + 42, COLOR_TEXT_DIM)
    centered_text("made simple.",    tag_font,  base_y + 55, COLOR_TEXT_DIM)

    return img.convert("RGB")


def save_bmp(image, path):
    image.save(path, "BMP")
    print(f"  wrote {os.path.relpath(path, PROJECT_ROOT)} "
          f"({image.size[0]}x{image.size[1]}, {os.path.getsize(path)} bytes)")


def main():
    print("Generating installer bitmaps from launcher icon + brand palette...")
    save_bmp(make_banner(), BANNER_PATH)
    save_bmp(make_dialog(), DIALOG_PATH)
    print("Done. Run a Windows packaging build to pick up the new bitmaps.")


if __name__ == "__main__":
    main()
