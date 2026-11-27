"""Fairy lights textures (Pillow). Original pixel art for Aftr Hrs (not copied from Fairy Lights).

  assets/aftrhrs/textures/entity/fairy_lights.png      64x64 sheet for the in-world strands: white (vertex colored
                                                       geometry), pine sprigs, tinsel strip, three pennant cloths, a ribbon
                                                       bow, the soft glow
  assets/aftrhrs/textures/item/fairylights/palette.png  32x32: 4x4 cells, one per material of the item models
python tools/fairylights_art.py [--preview out.png]
"""
import math
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_fairylights as G  # noqa: E402

ASSETS = G.ASSETS


def hx(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255, 255)


def shade(c, f):
    return tuple(max(0, min(255, int(c[i] * f))) for i in range(3)) + (c[3],)


def put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), c)


# ------------------------------------------------------------------------------------------------ entity sheet
def pine(img, ox, oy, variant):
    """A fir sprig: a twig with separate needles in a fishbone (up on the lit side, right on the shaded side)."""
    dark, mid, light, tip = hx(0x1E472A), hx(0x2B6338), hx(0x42834A), hx(0x63A05C)
    if variant:
        dark, mid, light, tip = hx(0x1B4128), hx(0x285B34), hx(0x3B7843), hx(0x5A9354)
    twig = hx(0x5B4631)
    for k in range(14):
        x, y = 1 + k, 14 - k
        L = 5 if 3 <= k <= 9 else (4 if 1 <= k <= 11 else 2)
        if k % 2 == (1 if variant else 0):
            for s_ in range(1, L + 1):  # lit side: needles point up
                c = mid if s_ == 1 else (light if s_ < L else tip)
                if 0 <= y - s_ < 16:
                    put(img, ox + x, oy + y - s_, c)
        else:
            for s_ in range(1, L + 1):  # shaded side: needles point right
                c = dark if s_ <= 2 else (mid if s_ < L else light)
                if x + s_ < 16:
                    put(img, ox + x + s_, oy + y, c)
        put(img, ox + x, oy + y, twig)


def pennant(img, ox, oy, shape):
    base, light, hem, edge = hx(0xF4F2EE), hx(0xFFFFFF), hx(0xC8C4BC), hx(0xDEDAD2)
    for y in range(16):
        for x in range(16):
            cx = x + 0.5
            if shape == 'triangle':
                half = 8 * (1 - (y + 0.5) / 16.5)
                inside = abs(cx - 8) <= half + 0.01
                border = inside and (abs(cx - 8) > half - 1.2)
            elif shape == 'swallowtail':
                notch = y >= 10 and abs(cx - 8) < (y - 9) * 1.15
                inside = x >= 1 and x <= 14 and not notch
                border = inside and (x in (1, 14) or (y >= 10 and abs(cx - 8) < (y - 9) * 1.15 + 1.2) or y == 15)
            else:
                inside = 1 <= x <= 14 and y <= 14
                border = inside and (x in (1, 14) or y == 14)
            if not inside:
                continue
            c = base
            if x < 6:
                c = shade(base, 1.0)
            if x > 10:
                c = shade(base, 0.96)
            if border:
                c = edge
            if y <= 1:
                c = hem  # the folded top hem the cord runs through
            if y == 2:
                c = light
            put(img, ox + x, oy + y, c)
    if shape == 'square':  # a stitched line inside the edge
        for x in range(3, 13, 2):
            put(img, ox + x, oy + 12, hx(0xD6D2CA))
        for y in range(4, 12, 2):
            put(img, ox + 3, oy + y, hx(0xD6D2CA))
            put(img, ox + 12, oy + y, hx(0xD6D2CA))


def bow(img, ox, oy):
    base, hi, lo, knot = hx(0xC0222C), hx(0xE24A50), hx(0x861620), hx(0xA21B25)
    # two loops (left, right), a knot, two tails
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5, y + 0.5
            for cx in (4.5, 11.5):
                d = ((px - cx) / 3.6) ** 2 + ((py - 5.2) / 2.9) ** 2
                if d <= 1:
                    inner = ((px - cx) / 1.6) ** 2 + ((py - 5.4) / 1.1) ** 2 <= 1
                    c = lo if inner else (hi if py < 4.6 else base)
                    put(img, ox + x, oy + y, c)
    for (ax, ay, bx, by) in ((7.0, 7.0, 3.5, 14.5), (9.0, 7.0, 12.5, 14.5)):
        for t in range(0, 41):
            u = t / 40
            x, y = ax + (bx - ax) * u, ay + (by - ay) * u
            for w in (-0.8, 0, 0.8):
                put(img, ox + int(x + w), oy + int(y), base if w <= 0 else lo)
        put(img, ox + int(bx) - 1, oy + 15, lo)
    for y in range(4, 8):
        for x in range(6, 10):
            put(img, ox + x, oy + y, knot if (x + y) % 3 else hi)


def glow(img, ox, oy):
    for y in range(16):
        for x in range(16):
            r = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
            a = max(0.0, 1 - r) ** 2
            if a > 0.01:
                put(img, ox + x, oy + y, (255, 255, 255, int(255 * a)))


def tinsel(img, ox, oy):
    cols = [hx(0xFFFFFF), hx(0xE6E6E6), hx(0xFFFFFF), hx(0xCFCFCF), hx(0xF2F2F2), hx(0xFFFFFF), hx(0xD9D9D9), hx(0xBDBDBD)]
    for y in range(8):
        put(img, ox, oy + y, cols[y])
        put(img, ox + 1, oy + y, shade(cols[(y + 3) % 8], 0.94))


def entity_sheet():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    for y in range(4):
        for x in range(4):
            put(img, x, y, (255, 255, 255, 255))
    pine(img, 16, 0, False)
    pine(img, 32, 0, True)
    tinsel(img, 48, 0)
    pennant(img, 0, 16, 'triangle')
    pennant(img, 16, 16, 'swallowtail')
    pennant(img, 32, 16, 'square')
    bow(img, 48, 16)
    glow(img, 0, 32)
    return img


# ------------------------------------------------------------------------------------------------ item palette
PAL = {'pine': 0x2C6639, 'berry': 0xC8242B, 'tinsel': 0xEDEDED, 'fabric': 0xF2F2F2, 'cord': 0xE6DDC9, 'wire': 0x2E2B29,
       'gold': 0xD9A93F, 'glass': 0xF6F6F6, 'clear': 0xF2E9D2, 'paper': 0xF2F2F2, 'rib': 0xB8B8B8}


def palette():
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    for i, mat in enumerate(G.PALETTE):
        ox, oy = (i % 8) * 4, (i // 8) * 4
        base = hx(PAL.get(mat, G.COLORS.get(mat, 0xFF00FF)))
        if mat == 'face':
            base = hx(0xFFC23A)
        for y in range(4):
            for x in range(4):
                c = base
                if y == 0:
                    c = shade(base, 1.08)
                elif y == 3:
                    c = shade(base, 0.86)
                if mat == 'glass' and (x, y) == (1, 1):
                    c = (255, 255, 255, 255)
                if mat == 'pine' and (x + y) % 3 == 0:
                    c = hx(0x45874A)
                if mat == 'tinsel' and (x * 3 + y) % 4 == 0:
                    c = (255, 255, 255, 255)
                put(img, ox + x, oy + y, c)
    return img


def main():
    ent = entity_sheet()
    p = os.path.join(ASSETS, 'textures', 'entity', 'fairy_lights.png')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    ent.save(p)
    pal = palette()
    p2 = os.path.join(ASSETS, 'textures', 'item', 'fairylights', 'palette.png')
    os.makedirs(os.path.dirname(p2), exist_ok=True)
    pal.save(p2)
    if '--preview' in sys.argv:
        out = sys.argv[sys.argv.index('--preview') + 1]
        bg = Image.new('RGBA', (64, 64), (70, 80, 110, 255))
        bg.alpha_composite(ent)
        bg.resize((512, 512), Image.NEAREST).save(out)
    print('fairylights_art: wrote', p, 'and', p2)


if __name__ == '__main__':
    main()
