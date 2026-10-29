#!/usr/bin/env python3
"""Puts a mockup's render beside the app's shot, both at the same dp scale, with a column-guide overlay.

    python3 tools/design-render/beside.py DESIGN.png DESIGN_PX_PER_DP APP.png APP_PX_PER_DP OUT.png \
        [--gutter 16] [--design-cols 24,140] [--app-cols 24,140] [--stack] [--crop-dp 900]

- render.mjs draws mockups at 2 px per CSS px (= dp); Robolectric shots (ScreenShots.INNER / COVER) are 2.625 px per dp.
- Both images are scaled to 2 px per dp, cropped to the same height (--crop-dp, default the shorter one), and put side
  by side (or one above the other with --stack, for the wide open-Fold layout).
- Guides: a faint line every 8 dp (the spacing rhythm), the gutter's two edges in cyan, and any column edges given
  (in dp from the image's left) in magenta, so a misaligned card edge shows at a glance.

Pure local image work (Pillow); nothing is fetched. Used for docs/screens/week-board/beside-*.png.
"""
import argparse
import sys

from PIL import Image, ImageDraw

SCALE = 2.0  # px per dp in the output


def load(path, px_per_dp):
    img = Image.open(path).convert("RGB")
    f = SCALE / px_per_dp
    return img.resize((max(1, round(img.width * f)), max(1, round(img.height * f))), Image.LANCZOS)


def guides(img, gutter_dp, cols_dp):
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    w, h = img.size
    step = int(8 * SCALE)
    for x in range(0, w, step):
        d.line([(x, 0), (x, h)], fill=(255, 255, 255, 18))
    for x in (gutter_dp * SCALE, w - gutter_dp * SCALE):
        d.line([(x, 0), (x, h)], fill=(0, 220, 255, 200), width=2)
    for c in cols_dp:
        x = c * SCALE
        d.line([(x, 0), (x, h)], fill=(255, 60, 200, 200), width=2)
    return Image.alpha_composite(img.convert("RGBA"), over).convert("RGB")


def cols(arg):
    return [float(v) for v in arg.split(",") if v.strip()] if arg else []


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("design")
    p.add_argument("design_scale", type=float)
    p.add_argument("app")
    p.add_argument("app_scale", type=float)
    p.add_argument("out")
    p.add_argument("--gutter", type=float, default=16)
    p.add_argument("--design-cols", default="")
    p.add_argument("--app-cols", default="")
    p.add_argument("--stack", action="store_true")
    p.add_argument("--crop-dp", type=float, default=0)
    a = p.parse_args(argv)
    if a.design_scale <= 0 or a.app_scale <= 0:
        p.error("scales must be positive")
    d = guides(load(a.design, a.design_scale), a.gutter, cols(a.design_cols))
    s = guides(load(a.app, a.app_scale), a.gutter, cols(a.app_cols))
    h = round(a.crop_dp * SCALE) if a.crop_dp > 0 else min(d.height, s.height)
    d, s = d.crop((0, 0, d.width, min(h, d.height))), s.crop((0, 0, s.width, min(h, s.height)))
    gap = 24
    if a.stack:
        out = Image.new("RGB", (max(d.width, s.width), d.height + gap + s.height), (255, 255, 255))
        out.paste(d, (0, 0))
        out.paste(s, (0, d.height + gap))
    else:
        out = Image.new("RGB", (d.width + gap + s.width, max(d.height, s.height)), (255, 255, 255))
        out.paste(d, (0, 0))
        out.paste(s, (d.width + gap, 0))
    out.save(a.out, optimize=True)
    print(f"{a.out}: {out.width}x{out.height}")


if __name__ == "__main__":
    main(sys.argv[1:])
