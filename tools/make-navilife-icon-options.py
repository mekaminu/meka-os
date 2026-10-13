"""Navilife logo options, round 4 (refining A3: watch dial + compass = time + navigation).

Run from the repo root:  python3 tools/make-navilife-icon-options.py
Writes docs/design/icons/navilife/: navilife-options.png (sheet) and option-n1..n4.png.
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

OUT = 'docs/design/icons/navilife'
S = 1024; C = S / 2
BR = (0xC9, 0xB3, 0x7E); BRIGHT = (0xEC, 0xD7, 0x9E); MID = (0xA8, 0x93, 0x5E)
DIM = (0x7A, 0x6A, 0x45); DEEP = (0x4E, 0x43, 0x2B); BG = (8, 8, 8)
BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
REG = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'


def base(glow=True):
    im = Image.new('RGBA', (S, S), BG + (255,))
    if glow:
        g = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(g)
        for r in range(380, 0, -6):
            a = int(38 * (1 - r / 380) ** 1.6); d.ellipse([C - r, C - r, C + r, C + r], fill=BR + (a,))
        im = Image.alpha_composite(im, g)
    return im


def P(a, r):  # degrees clockwise from north
    t = math.radians(a); return (C + r * math.sin(t), C - r * math.cos(t))


def facet_needle(d, ang, L, w, tail, mono=None):
    """Two-tone faceted needle: each half split along its spine, so it reads as a 3-D blade."""
    tip, back, l, r = P(ang, L), P(ang + 180, tail), P(ang - 90, w), P(ang + 90, w)
    d.polygon([tip, (C, C), l], fill=mono or BRIGHT)
    d.polygon([tip, (C, C), r], fill=mono or MID)
    d.polygon([back, (C, C), l], fill=mono or DIM)
    d.polygon([back, (C, C), r], fill=mono or DEEP)


def cap(d, mono, r1=26, r2=9):
    d.ellipse([C - r1, C - r1, C + r1, C + r1], fill=mono or BR)
    d.ellipse([C - r2, C - r2, C + r2, C + r2], fill=BG)


def n1(mono=None):  # refined A3·2: fine ring, 4 cardinal ticks, N at 12, faceted needle
    im = base(mono is None); d = ImageDraw.Draw(im)
    R = 405; d.ellipse([C - R, C - R, C + R, C + R], outline=mono or BR, width=6)
    for a in range(30, 360, 30):
        major = a % 90 == 0
        d.line([P(a, 378), P(a, 378 - (40 if major else 20))], fill=mono or (BR if major else DIM), width=12 if major else 7)
    d.text((C, C - 322), 'N', font=ImageFont.truetype(BOLD, 80), fill=mono or BRIGHT, anchor='mm')
    facet_needle(d, 28, 268, 40, 230, mono); cap(d, mono); return im


def n2(mono=None):  # ring broken at the top, N sits in the gap like a bezel badge
    im = base(mono is None); d = ImageDraw.Draw(im)
    R = 395; gap = 17
    d.arc([C - R, C - R, C + R, C + R], start=-90 + gap, end=270 - gap, fill=mono or BR, width=16)
    d.text((C, C - R), 'N', font=ImageFont.truetype(BOLD, 96), fill=mono or BRIGHT, anchor='mm')
    for a in (90, 180, 270):
        d.line([P(a, 352), P(a, 312)], fill=mono or BR, width=12)
    facet_needle(d, 28, 280, 44, 230, mono); cap(d, mono); return im


def n3(mono=None):  # watch hands that are the needle: minute hand points north, hour hand short and dark
    im = base(mono is None); d = ImageDraw.Draw(im)
    R = 405; d.ellipse([C - R, C - R, C + R, C + R], outline=mono or BR, width=6)
    for a in range(30, 360, 30):
        major = a % 90 == 0
        d.line([P(a, 378), P(a, 378 - (40 if major else 20))], fill=mono or (BR if major else DIM), width=12 if major else 7)
    d.text((C, C - 322), 'N', font=ImageFont.truetype(BOLD, 80), fill=mono or BRIGHT, anchor='mm')
    # hour hand ~ 10 o'clock, short, dim
    a = 300; tip = P(a, 170); l = P(a - 90, 22); r = P(a + 90, 22)
    d.polygon([tip, r, (C, C), l], fill=mono or DIM)
    # minute hand = faceted needle toward north-north-east
    tip = P(18, 290); l = P(18 - 90, 34); r = P(18 + 90, 34)
    d.polygon([tip, (C, C), l], fill=mono or BRIGHT); d.polygon([tip, (C, C), r], fill=mono or MID)
    # thin glowing seconds hand, like Today's watch face
    d.line([P(160, 60), P(160 + 180, 330)], fill=mono or BRIGHT, width=5)
    cap(d, mono); return im


def n4(mono=None):  # minimal: ring + faceted needle, nothing else (crispest at small sizes)
    im = base(mono is None); d = ImageDraw.Draw(im)
    R = 392; d.ellipse([C - R, C - R, C + R, C + R], outline=mono or BR, width=20)
    d.ellipse([C - 14, C - R - 2 - 14 + 44, C + 14, C - R - 2 + 14 + 44], fill=mono or BRIGHT)  # north dot
    facet_needle(d, 28, 300, 50, 250, mono); cap(d, mono, 30, 11); return im


def mask(im, shape):
    m = Image.new('L', (S, S), 0); d = ImageDraw.Draw(m)
    if shape == 'circle': d.ellipse([0, 0, S, S], fill=255)
    else: d.rounded_rectangle([0, 0, S, S], radius=230, fill=255)
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0)); out.paste(im, (0, 0), m); return out


def mono_icon(fn):
    ink = (0x2B, 0x27, 0x1F)
    im = fn(mono=ink).convert('RGB')
    cream = Image.new('RGB', (S, S), (0xEF, 0xE6, 0xCF))
    m = Image.eval(im.convert('L'), lambda v: 255 if v > 12 else 0)
    cream.paste(Image.new('RGB', (S, S), ink), (0, 0), m)
    return cream.convert('RGBA')


opts = [('N1 · Refined dial', n1), ('N2 · N in the bezel', n2),
        ('N3 · Hands as needle', n3), ('N4 · Minimal needle', n4)]
os.makedirs(OUT, exist_ok=True)
for i, (n, fn) in enumerate(opts):
    fn().resize((512, 512), Image.LANCZOS).save(f'{OUT}/option-n{i + 1}.png')

W, H = 1230, 40 + 200 * len(opts); sheet = Image.new('RGB', (W, H), (0, 0, 0)); d = ImageDraw.Draw(sheet)
fb = ImageFont.truetype(BOLD, 22); fm = ImageFont.truetype(REG, 17); fs = ImageFont.truetype(REG, 12)
for row, (name, fn) in enumerate(opts):
    y0 = 30 + row * 200
    l1, l2 = name.split(' · ', 1)
    d.text((30, y0 + 60), l1, font=fb, fill=BR); d.text((30, y0 + 92), l2, font=fm, fill=BR)
    icon = fn()
    for k, (bgc, txt) in enumerate([((18, 18, 22), (220, 220, 220)), ((228, 226, 220), (40, 40, 40))]):
        x0 = 200 + k * 430; d.rounded_rectangle([x0, y0, x0 + 410, y0 + 180], radius=18, fill=bgc)
        for j, (sz, shape) in enumerate([(112, 'circle'), (64, 'circle'), (64, 'squircle'), (40, 'circle')]):
            ic = mask(icon, shape).resize((sz, sz), Image.LANCZOS)
            xs = [x0 + 20, x0 + 150, x0 + 235, x0 + 330][j]; ys = y0 + 20 + (112 - sz) // 2
            sheet.paste(ic, (xs, ys), ic)
            d.text((xs + sz // 2, y0 + 150), 'Navilife', font=fs, fill=txt, anchor='mm')
    m = mask(mono_icon(fn), 'circle').resize((96, 96), Image.LANCZOS)
    d.rounded_rectangle([1070, y0 + 30, 1190, y0 + 150], radius=18, fill=(44, 44, 50)); sheet.paste(m, (1082, y0 + 42), m)
d.text((1130, 20), 'Themed', font=fs, fill=(150, 150, 150), anchor='mm')
sheet.save(f'{OUT}/navilife-options.png')
print('ok')
