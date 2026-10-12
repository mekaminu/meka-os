#!/usr/bin/env python3
"""Three app-icon options that read as MEKA, not as a clock (Fold review 2026-10-12, polish 8).

Meka found the watch-face icon reads as a clock app on the home screen. The watch face stays Today's signature;
the icon becomes one of these, all in the design tokens' brass (#C9B37E) on the same near-black glow:

  a  Bezel M      the brass rim and four markers, an M inside whose centre point is the hands' pivot
  b  Hands M      no rim: the two hands at ten past ten are the M's middle V, two fine stems finish the letter
  c  Monogram     a double brass ring around a high-contrast (thick/thin) M with serifs

Writes docs/design/icons/option-{a,b,c}.svg (108-unit adaptive-icon viewport: the art stays inside the 66-unit
safe circle), option-{a,b,c}.png (1024 px, the Mac's rounded square) and options-sheet.png (each option at
home-screen sizes, in a circle and a rounded square, on a dark and a light wallpaper, and as Android's themed
monochrome). Once Meka picks one, tools/make-app-icon.py takes that geometry and regenerates both apps' icons.

Run from the repo root: python3 tools/make-icon-options.py   (needs Python Playwright and its Chromium)
"""
import math
import os

from playwright.sync_api import sync_playwright

BRASS = "#C9B37E"
OUT = "docs/design/icons"
C = 54.0  # centre of the 108-unit viewport


def at(cx, cy, deg, r):
    a = math.radians(deg)
    return (cx + r * math.sin(a), cy - r * math.cos(a))


def pts(ps):
    return " ".join(f"{x:.2f},{y:.2f}" for x, y in ps)


def taper(cx, cy, deg, length, base, tip, tail=0.0):
    a = math.radians(deg)
    d = (math.sin(a), -math.cos(a))
    n = (math.cos(a), math.sin(a))

    def p(along, half):
        return (cx + d[0] * along + n[0] * half, cy + d[1] * along + n[1] * half)

    return [p(-tail, base / 2), p(length, tip / 2), p(length, -tip / 2), p(-tail, -base / 2)]


def option_a(ink):
    """Bezel M: rim, four markers, an M whose middle vertex sits on the pivot."""
    ticks = "".join(
        f'<line x1="{at(C, C, d, 18.6)[0]:.2f}" y1="{at(C, C, d, 18.6)[1]:.2f}" '
        f'x2="{at(C, C, d, 22.0)[0]:.2f}" y2="{at(C, C, d, 22.0)[1]:.2f}" />' for d in (0, 90, 180, 270))
    m = pts([(42.2, 65.0), (42.2, 41.2), (54, 54), (65.8, 41.2), (65.8, 65.0)])
    return (f'<circle cx="{C}" cy="{C}" r="26" fill="none" stroke="{ink}" stroke-width="3" />'
            f'<g stroke="{ink}" stroke-width="2.2" stroke-linecap="round">{ticks}</g>'
            f'<polyline points="{m}" fill="none" stroke="{ink}" stroke-width="3.6" '
            f'stroke-linejoin="miter" stroke-miterlimit="4" stroke-linecap="butt" />'
            f'<circle cx="{C}" cy="{C}" r="3.3" fill="{ink}" />'
            f'<circle cx="{C}" cy="{C}" r="1.05" fill="var(--hole)" />')


def option_b(ink):
    """Hands M: the hands at ten past ten are the V; fine stems drop from their tips."""
    px, py, spread, length = C, 63.0, 38.0, 26.0
    left_tip = at(px, py, -spread, length)
    right_tip = at(px, py, spread, length)
    base_y = 71.0
    hour = taper(px, py, -spread, length, 4.6, 1.8)
    minute = taper(px, py, spread, length, 4.6, 1.8)
    stems = (f'<line x1="{left_tip[0]:.2f}" y1="{left_tip[1]:.2f}" x2="{left_tip[0]:.2f}" y2="{base_y}" />'
             f'<line x1="{right_tip[0]:.2f}" y1="{right_tip[1]:.2f}" x2="{right_tip[0]:.2f}" y2="{base_y}" />')
    return (f'<g stroke="{ink}" stroke-width="1.8" stroke-linecap="round">{stems}</g>'
            f'<polygon points="{pts(hour)}" fill="{ink}" stroke="{ink}" stroke-width="0.4" stroke-linejoin="round" />'
            f'<polygon points="{pts(minute)}" fill="{ink}" stroke="{ink}" stroke-width="0.4" stroke-linejoin="round" />'
            f'<circle cx="{px}" cy="{py}" r="3.0" fill="{ink}" />'
            f'<circle cx="{px}" cy="{py}" r="1.0" fill="var(--hole)" />')


def option_c(ink):
    """Monogram: a double ring around a thick/thin M with serifs."""
    top, bottom = 40.5, 67.5
    lx, rx = 42.0, 66.0
    thin, thick = 1.3, 3.6
    vx, vy = 54.0, 66.0  # the M's centre point
    left_stem = [(lx - thin / 2, top), (lx + thin / 2, top), (lx + thin / 2, bottom), (lx - thin / 2, bottom)]
    left_diag = [(lx - thin / 2, top), (lx - thin / 2 + thick, top), (vx + 0.9, vy), (vx - 0.9, vy - 2.2)]
    right_diag = [(rx - thick / 2 - thin / 2, top), (rx - thick / 2 + thin / 2, top), (vx + 0.5, vy), (vx - 0.4, vy)]
    right_stem = [(rx - thick / 2, top), (rx + thick / 2, top), (rx + thick / 2, bottom), (rx - thick / 2, bottom)]
    serifs = [(lx - 3.6, bottom - 0.9, 7.2), (rx - 4.6, bottom - 0.9, 9.2),
              (lx - 3.6, top - 0.1, 4.0), (rx + thick / 2 - 0.2, top - 0.1, 3.0)]
    serif_rects = "".join(f'<rect x="{x:.2f}" y="{y:.2f}" width="{w:.2f}" height="1.0" fill="{ink}" />'
                          for x, y, w in serifs)
    return (f'<circle cx="{C}" cy="{C}" r="27" fill="none" stroke="{ink}" stroke-width="1.0" />'
            f'<circle cx="{C}" cy="{C}" r="24.6" fill="none" stroke="{ink}" stroke-width="2.4" />'
            + "".join(f'<polygon points="{pts(p)}" fill="{ink}" />'
                      for p in (left_stem, left_diag, right_diag, right_stem))
            + serif_rects)


OPTIONS = {"a": ("Bezel M", option_a), "b": ("Hands M", option_b), "c": ("Monogram", option_c)}

BACKGROUND = ('<defs><radialGradient id="g" cx="54" cy="54" r="62" gradientUnits="userSpaceOnUse">'
              '<stop offset="0" stop-color="#1C1A14" /><stop offset="1" stop-color="#000000" />'
              '</radialGradient></defs><rect width="108" height="108" fill="url(#g)" />')


def svg(key, ink=BRASS, background=True, hole="#000000"):
    art = OPTIONS[key][1](ink)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" style="--hole:{hole}">'
            f'{BACKGROUND if background else ""}{art}</svg>')


def icon(key, size, shape, themed=False):
    """One icon as Android or macOS would show it. Adaptive icons show the middle 72 of 108 units."""
    radius = "50%" if shape == "circle" else "22.5%"
    if themed:  # Android 13 themed icons: the monochrome layer tinted on a pale tonal plate
        inner = svg(key, ink="#3B3523", background=False, hole="#E9E2CF")
        plate = "#E9E2CF"
    else:
        inner = svg(key)
        plate = "#000"
    scale = 108 / 72 * size
    return (f'<div style="width:{size}px;height:{size}px;border-radius:{radius};overflow:hidden;background:{plate};'
            f'position:relative;box-shadow:0 1px 3px rgba(0,0,0,.35)">'
            f'<div style="position:absolute;left:{-(scale - size) / 2}px;top:{-(scale - size) / 2}px;'
            f'width:{scale}px;height:{scale}px">{inner}</div></div>')


def sheet():
    rows = []
    for key, (name, _) in OPTIONS.items():
        cells = []
        for wall, fg in (("#101014", "#ddd"), ("#e8e6e1", "#222")):
            group = "".join(
                f'<div style="display:flex;flex-direction:column;align-items:center;gap:8px">{icon(key, s, shape)}'
                f'<span style="font-size:{12 if s > 60 else 10}px;color:{fg}">MEKA</span></div>'
                for s, shape in ((112, "circle"), (64, "circle"), (64, "square"), (40, "circle")))
            cells.append(f'<div style="background:{wall};padding:22px 26px;border-radius:14px;display:flex;'
                         f'gap:26px;align-items:flex-end">{group}</div>')
        themed = (f'<div style="background:#2b2d33;padding:22px 26px;border-radius:14px;display:flex;'
                  f'gap:26px;align-items:flex-end">{icon(key, 64, "circle", themed=True)}</div>')
        rows.append(f'<div style="display:flex;gap:18px;align-items:center"><div style="width:150px;color:#C9B37E;'
                    f'font:600 22px/1.2 system-ui">{key.upper()} · {name}</div>{"".join(cells)}{themed}</div>')
    return ('<html><body style="margin:0;background:#000;padding:30px;display:flex;flex-direction:column;gap:22px;'
            'font-family:system-ui">' + "".join(rows) + '</body></html>')


def main():
    os.makedirs(OUT, exist_ok=True)
    for key in OPTIONS:
        with open(f"{OUT}/option-{key}.svg", "w") as f:
            f.write(svg(key) + "\n")
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(device_scale_factor=1)
        for key in OPTIONS:
            page.set_viewport_size({"width": 1024, "height": 1024})
            # The Mac's icon: an 824 px rounded square on a transparent 1024 canvas, the art at the adaptive scale.
            page.set_content('<html><body style="margin:0;background:transparent;display:flex;'
                             'align-items:center;justify-content:center;width:1024px;height:1024px">'
                             f'{icon(key, 824, "square")}</body></html>')
            page.screenshot(path=f"{OUT}/option-{key}.png", omit_background=True)
        page.set_viewport_size({"width": 1500, "height": 600})
        page.set_content(sheet())
        page.screenshot(path=f"{OUT}/options-sheet.png", full_page=True)
        browser.close()


if __name__ == "__main__":
    main()
