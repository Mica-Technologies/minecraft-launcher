#!/usr/bin/env python3
"""
Visual sanity-check helper: composite the WiX text overlays on top of the
generated installer banner + dialog bitmaps so we can see exactly what users
will read in the wizard.

This is not used by the build — it's a one-off preview tool for iterating on
the BMPs without having to install the .msi every time. Writes two PNGs to
the working directory:

    banner_preview.png      banner + simulated InstallDir/License title text
    welcome_preview.png     dialog + simulated Welcome title + body text
    exit_preview.png        dialog + simulated Finish title + body text

The text positions come from the standard WixUI_InstallDir dialog definitions
(WelcomeDlg / LicenseAgreementDlg / InstallDirDlg) — see the comments in
scripts/generate_installer_bitmaps.py for the coordinate math.
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFont

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BANNER_PATH  = os.path.join(PROJECT_ROOT, "deploy/windows/installer-resources/installer-banner.bmp")
DIALOG_PATH  = os.path.join(PROJECT_ROOT, "deploy/windows/installer-resources/installer-background.bmp")
OUT_DIR      = os.environ.get("TEMP", os.path.dirname(os.path.abspath(__file__)))


# Dialog unit to pixel ratio for the standard WixUI dialog (370x270 IU mapped
# onto a 493x312 px area).
IU_X = 493 / 370.0
IU_Y = 312 / 270.0


def iu(x, y):
    return int(x * IU_X), int(y * IU_Y)


def find_font(names, size):
    for name in names:
        for path in (f"C:/Windows/Fonts/{name}", f"/Library/Fonts/{name}",
                     f"/usr/share/fonts/truetype/dejavu/{name}"):
            if os.path.exists(path):
                try:
                    return ImageFont.truetype(path, size)
                except Exception:
                    continue
    return ImageFont.load_default()


def banner_preview():
    img = Image.open(BANNER_PATH).convert("RGB")
    draw = ImageDraw.Draw(img)

    # WiX banner title:  X=15 IU, Y=6 IU,  W=200 IU  (Tahoma 8pt Bold-ish)
    # WiX banner descr:  X=25 IU, Y=23 IU, W=280 IU
    title_font = find_font(["tahomabd.ttf", "tahoma.ttf", "Segoe UI.ttf"], 13)
    desc_font  = find_font(["tahoma.ttf", "Segoe UI.ttf", "DejaVuSans.ttf"], 11)
    tx, ty = iu(15, 6)
    dx, dy = iu(25, 23)
    draw.text((tx, ty), "Choose Install Location", fill=(0, 0, 0), font=title_font)
    draw.text((dx, dy), "Accept the default folder, or click Change to pick another.",
              fill=(0, 0, 0), font=desc_font)

    img.save(os.path.join(OUT_DIR, "banner_preview.png"))
    print("wrote", os.path.join(OUT_DIR, "banner_preview.png"))


def dialog_preview(out_name, title, body):
    img = Image.open(DIALOG_PATH).convert("RGB")
    draw = ImageDraw.Draw(img)

    # WelcomeDlg / ExitDialog text rectangles:
    #   Title:  X=135 IU, Y=20 IU, W=220 IU  (WixUI_Font_Bigger ~12pt bold)
    #   Body:   X=135 IU, Y=80 IU, W=220 IU
    title_font = find_font(["segoeuib.ttf", "tahomabd.ttf", "Segoe UI.ttf"], 18)
    body_font  = find_font(["segoeui.ttf", "tahoma.ttf", "DejaVuSans.ttf"], 12)
    tx, ty = iu(135, 20)
    bx, by = iu(135, 80)
    width  = iu(220, 0)[0]

    def wrapped(text, font, max_w):
        words = text.split()
        lines = []
        cur = ""
        for w in words:
            test = (cur + " " + w).strip()
            if draw.textlength(test, font=font) <= max_w:
                cur = test
            else:
                lines.append(cur)
                cur = w
        if cur:
            lines.append(cur)
        return lines

    # Title may contain newlines (we use the plain string here, no markup)
    title_lines = []
    for line in title.split("\n"):
        title_lines.extend(wrapped(line, title_font, width))
    body_lines = []
    for line in body.split("\n"):
        if not line.strip():
            body_lines.append("")
            continue
        body_lines.extend(wrapped(line, body_font, width))

    cur_y = ty
    for line in title_lines:
        draw.text((tx, cur_y), line, fill=(0, 0, 0), font=title_font)
        cur_y += int(title_font.size * 1.25)
    cur_y = by
    for line in body_lines:
        draw.text((bx, cur_y), line, fill=(0, 0, 0), font=body_font)
        cur_y += int(body_font.size * 1.35)

    img.save(os.path.join(OUT_DIR, out_name))
    print("wrote", os.path.join(OUT_DIR, out_name))


def main():
    banner_preview()
    dialog_preview(
        "welcome_preview.png",
        "Install Mica Minecraft Launcher",
        "This wizard will install Mica Minecraft Launcher on your "
        "computer. The launcher manages Minecraft Forge modpacks, "
        "downloads Java runtimes on demand, and signs you in with your "
        "Microsoft account.\n\nClick Next to continue, or Cancel to exit.",
    )
    dialog_preview(
        "exit_preview.png",
        "Installation Complete",
        "Mica Minecraft Launcher is ready to launch. Click Finish to "
        "close this wizard.\n\nOn first run you will be asked to sign in "
        "with your Microsoft account so the launcher can authenticate "
        "with Mojang's session servers.",
    )


if __name__ == "__main__":
    main()
