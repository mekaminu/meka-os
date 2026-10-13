"""Navilife logo, round 5: variants of N3 (the watch hands are the compass needle).

Run from the repo root:  python3 tools/make-navilife-icon-n3-variants.py
Writes docs/design/icons/navilife/: navilife-n3-variants.png and option-n3-1..4.png.
Goal: keep N3's idea but make it read cleanly at home-screen size.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont

src = open('tools/make-navilife-icon-options.py').read().split('\nopts = [')[0]
exec(src)


def hands(d, mono, minute_w=40, minute_L=300, hour_w=28, hour_L=190, hour_ang=300, minute_ang=18):
    # hour hand: short, two-tone dark brass
    tip, l, r = P(hour_ang, hour_L), P(hour_ang - 90, hour_w), P(hour_ang + 90, hour_w)
    d.polygon([tip, (C, C), l], fill=mono or DIM); d.polygon([tip, (C, C), r], fill=mono or DEEP)
    # minute hand = the compass needle, pointing (almost) north
    tip, l, r = P(minute_ang, minute_L), P(minute_ang - 90, minute_w), P(minute_ang + 90, minute_w)
    d.polygon([tip, (C, C), l], fill=mono or BRIGHT); d.polygon([tip, (C, C), r], fill=mono or MID)


def glow_seconds(im, ang, L, tail, mono, w=7):
    """Thin seconds hand with a soft glow (as on Today's watch face)."""
    if mono:
        ImageDraw.Draw(im).line([P(ang + 180, tail), P(ang, L)], fill=mono, width=w + 3); return im
    g = Image.new('RGBA', (S, S), (0, 0, 0, 0)); gd = ImageDraw.Draw(g)
    gd.line([P(ang + 180, tail), P(ang, L)], fill=BRIGHT + (200,), width=w * 4)
    g = g.filter(ImageFilter.GaussianBlur(14))
    im = Image.alpha_composite(im, g)
    ImageDraw.Draw(im).line([P(ang + 180, tail), P(ang, L)], fill=(0xFF, 0xF1, 0xC8), width=w)
    return im


def ring(d, mono, R=405, w=6):
    d.ellipse([C - R, C - R, C + R, C + R], outline=mono or BR, width=w)


def ticks4(d, mono, R=375, L=46, w=16):
    for a in (90, 180, 270):
        d.line([P(a, R), P(a, R - L)], fill=mono or BR, width=w)


def v1(mono=None):  # bolder, simpler: thicker ring, 3 big ticks + N, bigger hands, no seconds hand
    im = base(mono is None); d = ImageDraw.Draw(im)
    ring(d, mono, 400, 14); ticks4(d, mono)
    d.text((C, C - 318), 'N', font=ImageFont.truetype(BOLD, 96), fill=mono or BRIGHT, anchor='mm')
    hands(d, mono, minute_w=50, minute_L=310, hour_w=36, hour_L=200)
    cap(d, mono, 32, 11); return im


def v2(mono=None):  # v1 + the thin glowing seconds hand
    im = base(mono is None); d = ImageDraw.Draw(im)
    ring(d, mono, 400, 14); ticks4(d, mono)
    d.text((C, C - 318), 'N', font=ImageFont.truetype(BOLD, 96), fill=mono or BRIGHT, anchor='mm')
    hands(d, mono, minute_w=50, minute_L=310, hour_w=36, hour_L=200)
    im = glow_seconds(im, 150, 330, 70, mono)
    cap(ImageDraw.Draw(im), mono, 32, 11); return im


def v3(mono=None):  # N3 hands inside N2's bezel (ring breaks for the N)
    im = base(mono is None); d = ImageDraw.Draw(im)
    R = 395; gap = 17
    d.arc([C - R, C - R, C + R, C + R], start=-90 + gap, end=270 - gap, fill=mono or BR, width=16)
    d.text((C, C - R), 'N', font=ImageFont.truetype(BOLD, 96), fill=mono or BRIGHT, anchor='mm')
    ticks4(d, mono, R=352, L=40, w=12)
    hands(d, mono, minute_w=48, minute_L=300, hour_w=34, hour_L=195)
    im = glow_seconds(im, 150, 320, 65, mono, w=6)
    cap(ImageDraw.Draw(im), mono, 32, 11); return im


def v4(mono=None):  # original N3, cleaned: 12 ticks kept, hands bolder, glowing seconds hand
    im = base(mono is None); d = ImageDraw.Draw(im)
    ring(d, mono, 405, 8)
    for a in range(30, 360, 30):
        major = a % 90 == 0
        d.line([P(a, 378), P(a, 378 - (44 if major else 22))], fill=mono or (BR if major else DIM), width=14 if major else 8)
    d.text((C, C - 318), 'N', font=ImageFont.truetype(BOLD, 86), fill=mono or BRIGHT, anchor='mm')
    hands(d, mono, minute_w=44, minute_L=300, hour_w=32, hour_L=190)
    im = glow_seconds(im, 150, 325, 70, mono, w=6)
    cap(ImageDraw.Draw(im), mono, 30, 10); return im


opts = [('N3·1 · Bold and simple', v1), ('N3·2 · + glowing seconds', v2),
        ('N3·3 · Hands in N2 bezel', v3), ('N3·4 · Full dial, cleaned', v4)]
for i, (n, fn) in enumerate(opts):
    fn().resize((512, 512), Image.LANCZOS).save(f'{OUT}/option-n3-{i + 1}.png')

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
sheet.save(f'{OUT}/navilife-n3-variants.png')
print('ok')
