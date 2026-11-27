"""Christmas set textures (original 16x16 pixel art, painted procedurally so they can be re-made and tuned).

Every texture uses a small palette (4-7 tones), structured patterns instead of noise, and light from the top left like
vanilla. Written to mod/content/pack/textures/arcana/christmas/ by gen.py (paint_all()).
"""
import json
import math
import os
import random

import numpy as np
from PIL import Image


def hexc(s):
    s = s.lstrip('#')
    return np.array([int(s[i:i + 2], 16) for i in (0, 2, 4)] + [255], dtype=np.float32)


def canvas(w=16, h=16, fill=None):
    a = np.zeros((h, w, 4), dtype=np.float32)
    if fill is not None:
        a[:, :] = hexc(fill) if isinstance(fill, str) else fill
    return a


def put(a, x, y, c, wrap=True):
    h, w = a.shape[:2]
    if wrap:
        x, y = x % w, y % h
    elif not (0 <= x < w and 0 <= y < h):
        return
    a[y, x] = hexc(c) if isinstance(c, str) else c


def save(a, path, frames=None, frametime=None, interpolate=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), 'RGBA').save(path)
    meta = path + '.mcmeta'
    if frames:
        anim = {'frametime': frametime or 4}
        if interpolate:
            anim['interpolate'] = True
        if isinstance(frames, list):
            anim['frames'] = frames
        json.dump({'animation': anim}, open(meta, 'w'))
    elif os.path.exists(meta):
        os.remove(meta)


def ramp(cols):
    return [hexc(c) for c in cols]


def mix(c1, c2, t):
    return c1 * (1 - t) + c2 * t


# ---------------------------------------------------------------- foliage
NEEDLES = ['#14301c', '#1c4125', '#25512d', '#2f6236', '#3b7540', '#4c8a4a', '#64a05a']


def needles(seed=3, snow=0, top=False):
    """Fir needles: sprigs on a jittered grid, each a downward V of needles with a light tip and a dark gap below.
    snow: rows of snow on top (side textures) or coverage 0..1 (top=True)."""
    rnd = random.Random(seed)
    P = ramp(NEEDLES)
    a = canvas(fill=P[2])
    # soft vertical shading: lighter at the top of each tier face
    for y in range(16):
        for x in range(16):
            if (x * 7 + y * 3) % 11 == 0:
                a[y, x] = P[1]
    cells = []
    for gy in range(4):
        for gx in range(4):
            cells.append((gx * 4 + (2 if gy % 2 else 0) + rnd.randint(-1, 1), gy * 4 + rnd.randint(0, 1)))
    for cx, cy in cells:
        # dark gap under the sprig
        put(a, cx, cy + 2, P[0])
        put(a, cx - 1, cy + 3, P[1])
        put(a, cx + 1, cy + 3, P[1])
        # needles: a V opening downwards
        put(a, cx, cy, P[5])
        put(a, cx, cy + 1, P[4])
        put(a, cx - 1, cy + 1, P[3])
        put(a, cx + 1, cy + 1, P[3])
        put(a, cx - 2, cy + 2, P[3])
        put(a, cx + 2, cy + 2, P[2])
        if rnd.random() < 0.5:
            put(a, cx - 1, cy, P[6] if rnd.random() < 0.4 else P[5])
    if top:
        S = ramp(['#c9d6e2', '#dde7ef', '#eef4f8', '#ffffff'])
        if snow:
            for y in range(16):
                for x in range(16):
                    v = math.sin(x * 0.9 + y * 0.35) + math.cos(y * 0.8 - x * 0.3)
                    if v > 1.6 - 2.6 * snow:
                        t = min(3, int((v - (1.6 - 2.6 * snow)) * 2.2))
                        a[y, x] = S[t]
    elif snow:
        S = ramp(['#b9c9d8', '#d8e3ec', '#eef4f8', '#ffffff'])
        for x in range(16):
            depth = snow + (1 if (x * 5) % 7 < 3 else 0) + (1 if x in (3, 4, 11) else 0)
            for y in range(depth):
                a[y, x] = S[3] if y < depth - 2 else S[2] if y < depth - 1 else S[1]
            if x % 5 == 2:  # a little drip of snow over the needles
                put(a, x, depth, S[0])
    return a


def holly():
    P = ramp(['#0f2a17', '#173d21', '#21512b', '#2e6a37', '#4a8a4b', '#7cb36a'])
    a = canvas(fill=P[2])
    for y in range(16):
        for x in range(16):
            # glossy leaf: lighter upper left, central vein, darker edge rows
            d = (x - 8) ** 2 / 64 + (y - 8) ** 2 / 64
            a[y, x] = P[3] if d < 0.35 else P[2] if d < 0.8 else P[1]
    for i in range(16):
        put(a, i, 8, P[4] if 3 < i < 13 else P[3])
    for x, y in ((4, 5), (5, 4), (6, 5), (10, 4), (11, 5)):
        put(a, x, y, P[5])
    return a


def mistletoe_leaf():
    P = ramp(['#4a5a1c', '#5e7024', '#748a2f', '#8ca23e', '#a9bd5c'])
    a = canvas(fill=P[2])
    for y in range(16):
        for x in range(16):
            if (x + y) % 6 == 0:
                a[y, x] = P[1]
            if y < 5 and (x * 3 + y) % 7 == 1:
                a[y, x] = P[3]
    for i in range(3, 13):
        put(a, i, 8, P[3])
    put(a, 5, 6, P[4]), put(a, 6, 5, P[4])
    return a


# ---------------------------------------------------------------- lights
BULB_COLS = ['#ff3b30', '#ffb02e', '#ffe14d', '#46e35a', '#3d9bff', '#ff5ccd', '#fff0c4', '#dff1ff']


def bulbs(frames=8):
    """Animated bulb palette: column c = colour (8 colours, twice), row r = twinkle phase. Each frame brightens and
    dims bulbs at different phases, so bulbs that share a colour still twinkle apart."""
    out = canvas(16, 16 * frames)
    for f in range(frames):
        for r in range(16):
            ph = (r * 5 + f * 2 * math.pi / frames * 3) % (2 * math.pi)
            for c in range(16):
                base = hexc(BULB_COLS[c % 8])
                tw = 0.5 + 0.5 * math.sin(f * 2 * math.pi / frames + r * 1.7 + c * 0.9)
                k = 0.72 + 0.28 * tw
                col = base * k + np.array([255, 255, 255, 0], dtype=np.float32) * (0.22 * tw)
                col[3] = 255
                out[f * 16 + r, c] = col
    return out


def bulbs_unlit():
    a = canvas()
    for r in range(16):
        for c in range(16):
            base = hexc(BULB_COLS[c % 8])
            g = np.array([70, 70, 74, 255], dtype=np.float32)
            col = mix(g, base, 0.42) * 0.8
            col[3] = 255
            a[r, c] = col
    return a


def flame(frames=4):
    """Candle flame (drawn on small crossed planes): white-yellow core, orange edge, flickers between frames."""
    C = ramp(['#ff7a1a', '#ffad2e', '#ffd95a', '#fff6cf'])
    out = canvas(16, 16 * frames)
    for f in range(frames):
        sway = [0, 0.6, 0, -0.6][f % 4]
        hgt = [1.0, 0.92, 0.97, 0.9][f % 4]
        for y in range(16):
            for x in range(16):
                t = y / 15.0                      # 0 top .. 1 bottom
                if t < 1 - hgt:
                    continue
                tt = (t - (1 - hgt)) / hgt
                w = 6.5 * math.sin(math.pi * min(1.0, tt * 0.95 + 0.08)) ** 0.8 * (0.35 + 0.65 * tt)
                cx = 7.5 + sway * (1 - tt)
                d = abs(x - cx)
                if d > w:
                    continue
                k = d / max(w, 0.01)
                level = 3 if k < 0.35 and tt > 0.35 else 2 if k < 0.65 else 1 if k < 0.88 else 0
                out[f * 16 + y, x] = C[level]
    return out


def glow(seed, unlit=False):
    """Warm lantern glass: soft amber with a bright middle; unlit = smoky dim copy."""
    a = canvas()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 8.5) / 9
            t = max(0.0, 1 - d)
            col = mix(hexc('#e07a22'), hexc('#fff2b8'), t ** 1.4)
            if unlit:
                col = mix(hexc('#3d342c'), hexc('#7a6650'), t)
            a[y, x] = col
    for i in range(16):  # frame shadow lines
        a[0, i] = a[0, i] * 0.8
        a[15, i] = a[15, i] * 0.8
        a[0, i][3] = a[15, i][3] = 255
    return a


# ---------------------------------------------------------------- metal / shine
def shine(cols, seed=1):
    P = ramp(cols)
    a = canvas(fill=P[2])
    for y in range(16):
        for x in range(16):
            v = (x + 2 * y) % 8
            a[y, x] = P[1] if v in (0, 1) else P[2] if v < 5 else P[3]
    for x, y in ((3, 2), (11, 6), (6, 11), (14, 13), (1, 9)):
        put(a, x, y, P[4])
    return a


GOLD = ['#8a5a12', '#c48a1d', '#e8b630', '#ffd95a', '#fff6c2']
SILVER = ['#5c6670', '#8e98a3', '#b9c2cb', '#dfe6ec', '#ffffff']


def star():
    P = ramp(['#9a5a0c', '#d88a14', '#f2b62a', '#ffd84a', '#fff1a0'])
    a = canvas()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5) / 10.6
            k = min(3.999, d * 4.6)
            a[y, x] = mix(P[4 - int(k)], P[max(0, 3 - int(k))], k - int(k))
            a[y, x][3] = 255
    for i in range(16):
        a[i, 0] = a[i, 15] = a[0, i] = a[15, i] = P[1]
    return a


def bauble(col):
    """A bauble face: soft sphere shading (highlight top left, darker rim) so cube faces read as round glass."""
    base = hexc(col)
    hi = mix(base, hexc('#ffffff'), 0.55)
    lo = base * 0.55
    lo[3] = 255
    a = canvas()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5) / 7.8
            t = min(1.0, d)
            c = mix(base, lo, max(0, t - 0.45) / 0.55) if t > 0.45 else base
            hd = math.hypot(x - 5, y - 5)
            if hd < 2.6:
                c = mix(c, hi, 1 - hd / 2.6)
            a[y, x] = c
            a[y, x][3] = 255
    put(a, 4, 4, '#ffffff')
    return a


BAUBLE_COLS = {'red': '#d42a2a', 'gold': '#e2a826', 'silver': '#b8c3cc', 'blue': '#2f63d6', 'green': '#2f9a45',
               'purple': '#8a3fc7', 'pink': '#f06aa6', 'white': '#f2f4f6'}


def bauble_palette():
    """2x2 swatches for tiny baked baubles (x = 2*index): [highlight, base / base, shadow]; row 2..4 matte."""
    a = canvas()
    for i, (k, col) in enumerate(BAUBLE_COLS.items()):
        base = hexc(col)
        hi = mix(base, hexc('#ffffff'), 0.5)
        lo = base * 0.62
        lo[3] = 255
        x = 2 * i
        for y0, (c00, c10, c01, c11) in ((0, (hi, base, base, lo)), (2, (base, base, base, lo))):
            a[y0, x], a[y0, x + 1], a[y0 + 1, x], a[y0 + 1, x + 1] = c00, c10, c01, c11
    return a


# ---------------------------------------------------------------- fabric / paper
def felt(cols, seed=5):
    P = ramp(cols)
    a = canvas(fill=P[2])
    rnd = random.Random(seed)
    for y in range(16):
        for x in range(16):
            v = (x * 3 + y * 5 + (x * y) % 3) % 9
            a[y, x] = P[1] if v == 0 else P[3] if v == 4 else P[2]
    for _ in range(5):
        put(a, rnd.randrange(16), rnd.randrange(16), P[0])
    return a


def fur():
    P = ramp(['#b9c0c8', '#d3d9df', '#e8ecf0', '#f8fafc', '#ffffff'])
    a = canvas(fill=P[3])
    for y in range(16):
        for x in range(16):
            v = math.sin(x * 1.3 + math.sin(y * 0.9) * 2) + math.cos(y * 1.1 + x * 0.4)
            a[y, x] = P[1] if v < -1.3 else P[2] if v < -0.4 else P[3] if v < 0.9 else P[4]
    return a


def wrap(base, dark, motif, accent):
    """Wrapping paper: base colour with a gentle diagonal sheen and a small repeating motif."""
    B, D, A = hexc(base), hexc(dark), hexc(accent)
    a = canvas()
    for y in range(16):
        for x in range(16):
            a[y, x] = mix(B, D, 0.35) if (x + y) % 16 in (0, 1) else B
    if motif == 'dots':
        for y in range(0, 16, 4):
            for x in range(0, 16, 4):
                put(a, x + (2 if (y // 4) % 2 else 0), y + 1, A)
    elif motif == 'stars':
        for (x, y) in ((3, 3), (11, 11)):
            for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                put(a, x + dx, y + dy, A)
        for (x, y) in ((11, 3), (3, 11)):
            put(a, x, y, A)
    elif motif == 'flakes':
        for (x, y) in ((4, 4), (12, 12)):
            for dx, dy in ((0, 0), (1, 1), (-1, -1), (1, -1), (-1, 1), (0, 2), (0, -2), (2, 0), (-2, 0)):
                put(a, x + dx, y + dy, A)
        put(a, 12, 4, A), put(a, 4, 12, A)
    elif motif == 'stripes':
        for y in range(16):
            for x in range(16):
                if (x - y) % 8 in (0, 1):
                    a[y, x] = A
    elif motif == 'holly':
        for (x, y) in ((4, 4), (12, 12)):
            put(a, x, y, '#c8262a'), put(a, x + 1, y, '#c8262a')
            put(a, x - 1, y + 1, A), put(a, x + 2, y + 1, A)
        for (x, y) in ((12, 4), (4, 12)):
            put(a, x, y, A)
    return a


def ribbon(cols):
    P = ramp(cols)
    a = canvas(fill=P[2])
    for y in range(16):
        for x in range(16):
            v = (x + y) % 6
            a[y, x] = P[3] if v == 0 else P[1] if v == 3 else P[2]
    return a


def tissue():
    P = ramp(['#d6dde3', '#e6ecf0', '#f3f6f8', '#ffffff'])
    a = canvas(fill=P[2])
    for y in range(16):
        for x in range(16):
            v = (x * 2 + y * 3) % 7
            a[y, x] = P[1] if v == 0 else P[3] if v == 3 else P[2]
    return a


def tag():
    a = canvas(fill='#efe3c4')
    for i in range(16):
        put(a, i, 0, '#cbb88f'), put(a, i, 15, '#cbb88f'), put(a, 0, i, '#cbb88f'), put(a, 15, i, '#cbb88f')
    for x in range(3, 13):
        put(a, x, 6, '#9b3b32')
        if x < 10:
            put(a, x, 9, '#8a7a5c')
    return a


# ---------------------------------------------------------------- food blocks
DOUGH = ['#6e3a17', '#844820', '#985729', '#a86431', '#b8743c', '#c8874c']
ICING = ['#c9c3b6', '#dcd7cc', '#ece9e2', '#f8f6f1', '#ffffff']


def gingerbread():
    P = ramp(DOUGH)
    a = canvas(fill=P[3])
    for y in range(16):
        for x in range(16):
            v = math.sin(x * 0.7 + y * 0.3) + math.cos(y * 0.6 - x * 0.2)
            a[y, x] = P[2] if v < -1.0 else P[4] if v > 1.2 else P[3]
    for x, y in ((3, 4), (11, 2), (7, 9), (13, 12), (2, 13), (9, 14)):
        put(a, x, y, P[1])
    for x, y in ((4, 4), (12, 2), (8, 9)):
        put(a, x, y, P[5])
    return a


def gingerbread_bricks():
    P, I = ramp(DOUGH), ramp(ICING)
    a = canvas()
    for y in range(16):
        row = y // 4
        for x in range(16):
            xo = (x + (4 if row % 2 else 0)) % 16
            if y % 4 == 3 or xo % 8 == 7:
                a[y, x] = I[3] if y % 4 == 3 else I[2]
                continue
            lx, ly = xo % 8, y % 4
            c = P[3]
            if ly == 0 or lx == 0:
                c = P[4]
            if ly == 2 or lx == 6:
                c = P[2]
            if (lx + ly * 3 + row) % 9 == 4:
                c = P[1]
            a[y, x] = c
    return a


def frosted_side():
    a = gingerbread()
    I = ramp(ICING)
    drips = [3, 4, 3, 5, 6, 4, 3, 3, 4, 7, 5, 3, 3, 4, 6, 4]
    for x in range(16):
        for y in range(drips[x]):
            a[y, x] = I[4] if y == 0 else I[3] if y < drips[x] - 1 else I[1]
    return a


def frosted_top():
    I = ramp(ICING)
    a = canvas(fill=I[3])
    for y in range(16):
        for x in range(16):
            v = math.sin(x * 0.8 + y * 0.5) + math.sin(y * 0.9 - x * 0.4)
            a[y, x] = I[2] if v < -1.1 else I[4] if v > 1.3 else I[3]
    for (x, y), c in (((3, 3), '#d8323a'), ((11, 5), '#2f9a45'), ((6, 10), '#e8b630'), ((13, 12), '#d8323a'),
                      ((2, 13), '#2f9a45'), ((9, 1), '#3d7be0'), ((8, 7), '#d8323a')):
        put(a, x, y, c)
        d = hexc(c) * 0.78
        d[3] = 255
        put(a, x + 1, y, d)
    return a


def frosting():
    I = ramp(ICING)
    a = canvas(fill=I[3])
    for y in range(16):
        for x in range(16):
            v = math.sin((x + y * 0.5) * 0.7) + math.cos((y - x * 0.3) * 0.55)
            a[y, x] = I[1] if v < -1.45 else I[2] if v < -0.6 else I[4] if v > 1.35 else I[3]
    return a


CANE_R = ['#8f1414', '#b51d1d', '#d62b2b', '#ef5a50']
CANE_W = ['#d9dde0', '#eceff1', '#fbfbfb', '#ffffff']


def candy_stripes(period=8, red=4):
    """Diagonal candy-cane stripes (tileable: the period divides 16) with a soft gloss on each stripe."""
    R, W = ramp(CANE_R), ramp(CANE_W)
    a = canvas()
    for y in range(16):
        for x in range(16):
            p = (x + y) % period
            if p < red:
                a[y, x] = R[3] if p == 0 else R[2] if p < red - 1 else R[1]
            else:
                a[y, x] = W[3] if p == red else W[2] if p < period - 1 else W[1]
    return a


def peppermint_top():
    R, W = ramp(CANE_R), ramp(CANE_W)
    a = canvas(fill=W[2])
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            ang = math.atan2(dy, dx) + r * 0.18
            wedge = int((ang + math.pi) / (2 * math.pi) * 8) % 2
            if r > 7.6:
                a[y, x] = W[1]
            elif r < 1.6:
                a[y, x] = W[3]
            else:
                a[y, x] = (R[2] if r < 6 else R[1]) if wedge else (W[2] if r < 6.5 else W[1])
    return a


def peppermint_side():
    R, W = ramp(CANE_R), ramp(CANE_W)
    a = canvas()
    for y in range(16):
        for x in range(16):
            p = x % 4
            red = p < 2
            a[y, x] = (R[2] if p == 0 else R[1]) if red else (W[2] if p == 2 else W[1])
        if y in (0, 15):
            a[y, :] = a[y, :] * 0.88
            a[y, :, 3] = 255
    return a


# ---------------------------------------------------------------- paint / wax / glass
PAINT = {'red': '#c42b2b', 'blue': '#2d4fb0', 'green': '#2f7d3c', 'black': '#26242a', 'white': '#eef0f2',
         'gold': '#e0a92a', 'skin': '#f2c7a0', 'cream': '#efe1c0', 'brown': '#6b4325', 'orange': '#ee7d1f',
         'pink': '#f07aa8', 'purple': '#7d3fb3', 'grey': '#8a8f96', 'wire': '#1f3a24'}


def paint(col):
    """Painted wood: flat colour, faint vertical grain, slightly lighter top rows."""
    base = hexc(col)
    a = canvas()
    for y in range(16):
        for x in range(16):
            c = base.copy()
            if x % 5 == 1 and (y // 3 + x) % 2 == 0:
                c = c * 0.93
            if y < 2:
                c = mix(c, hexc('#ffffff'), 0.08)
            if y > 13:
                c = c * 0.94
            c[3] = 255
            a[y, x] = c
    return a


def wax(col):
    base = hexc(col)
    a = canvas()
    for y in range(16):
        for x in range(16):
            c = base.copy()
            if x in (5, 6, 7):
                c = mix(c, hexc('#ffffff'), 0.12)
            if x in (0, 15):
                c = c * 0.88
            if y < 3 and x % 4 == 2:
                c = mix(c, hexc('#ffffff'), 0.18)
            c[3] = 255
            a[y, x] = c
    return a


def globe_glass():
    a = canvas()
    for y in range(16):
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            al = 70 if edge > 0 else 120
            a[y, x] = [214, 236, 246, al]
    for i in range(3, 8):
        a[i, 3] = [255, 255, 255, 170]
    a[3, 4] = a[3, 5] = [255, 255, 255, 170]
    a[12, 12] = [255, 255, 255, 130]
    return a


def snow_ground():
    P = ramp(['#d4dfe8', '#e8eff4', '#f6f9fb', '#ffffff'])
    a = canvas(fill=P[2])
    for y in range(16):
        for x in range(16):
            v = (x * 5 + y * 3) % 13
            a[y, x] = P[1] if v == 0 else P[3] if v in (6, 7) else P[2]
    return a


# ---------------------------------------------------------------- all
def paint_all(out_dir):
    T = {}
    T['fir_needles'] = needles(3)
    T['fir_needles_alt'] = needles(11)
    T['fir_snow_side'] = needles(3, snow=3)
    T['fir_snow_top'] = needles(5, snow=0.55, top=True)
    T['holly'] = holly()
    T['mistletoe_leaf'] = mistletoe_leaf()
    T['bulbs_unlit'] = bulbs_unlit()
    T['gold_shine'] = shine(GOLD)
    T['silver_shine'] = shine(SILVER)
    T['star_gold'] = star()
    T['baubles'] = bauble_palette()
    for k, col in BAUBLE_COLS.items():
        T['bauble_' + k] = bauble(col)
    T['felt_red'] = felt(['#6a1414', '#8e1d1d', '#b02727', '#c73a36'])
    T['felt_green'] = felt(['#163d22', '#1f5430', '#2a6a3c', '#3a7f4b'])
    T['fur_white'] = fur()
    papers = {'red': ('#c22b2b', '#8f1c1c', 'dots', '#f3e9d8'), 'green': ('#2a7a3c', '#1b5228', 'stars', '#f0c64a'),
              'blue': ('#2f58b8', '#1f3d85', 'flakes', '#e8f2ff'), 'gold': ('#e0a92a', '#a8741a', 'stripes', '#f6d870'),
              'white': ('#eef1f3', '#c9d0d6', 'holly', '#2f8a44')}
    for k, v in papers.items():
        T['wrap_' + k] = wrap(*v)
    T['ribbon_gold'] = ribbon(GOLD[:4])
    T['ribbon_red'] = ribbon(['#7c1212', '#a81d1d', '#cf2c2c', '#ea5a52'])
    T['ribbon_silver'] = ribbon(SILVER[:4])
    T['ribbon_green'] = ribbon(['#123d1e', '#1c5a2c', '#2a7a3c', '#4a9a58'])
    T['tissue'] = tissue()
    T['gift_tag'] = tag()
    T['gingerbread'] = gingerbread()
    T['gingerbread_bricks'] = gingerbread_bricks()
    T['frosted_gingerbread_side'] = frosted_side()
    T['frosted_gingerbread_top'] = frosted_top()
    T['frosting'] = frosting()
    T['candy_cane_block'] = candy_stripes(8, 4)
    T['candy_stripe_fine'] = candy_stripes(4, 2)
    T['peppermint_top'] = peppermint_top()
    T['peppermint_side'] = peppermint_side()
    for k, col in PAINT.items():
        T['paint_' + k] = paint(col)
    for k, col in (('red', '#b8262a'), ('white', '#f1ede2'), ('purple', '#6e3aa3'), ('pink', '#e889b4')):
        T['wax_' + k] = wax(col)
    T['lantern_glow'] = glow(1)
    T['lantern_glow_unlit'] = glow(1, unlit=True)
    T['globe_glass'] = globe_glass()
    T['snow_ground'] = snow_ground()
    for k, a in T.items():
        save(a, os.path.join(out_dir, k + '.png'))
    save(bulbs(8), os.path.join(out_dir, 'bulbs.png'), frames=True, frametime=6, interpolate=True)
    save(flame(4), os.path.join(out_dir, 'candle_flame.png'), frames=True, frametime=3)
    # a strip of every texture for review
    names = sorted(T) + ['bulbs', 'candle_flame']
    sheet = Image.new('RGBA', (len(names) * 18, 18), (40, 40, 48, 255))
    for i, n in enumerate(names):
        im = Image.open(os.path.join(out_dir, n + '.png')).convert('RGBA').crop((0, 0, 16, 16))
        sheet.paste(im, (i * 18 + 1, 1), im)
    return names, sheet
