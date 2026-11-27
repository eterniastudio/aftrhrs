"""Block models and textures for the Anvil & Ember smithing stations (SMITHING.md section 3).

Run by hand after changing it:  python mod/tools/smithing_blocks.py   (Pillow; reads a few vanilla textures from the
26.3 client jar as grain/noise sources; everything is recolored and redrawn, so the results are our own textures)

Deletes and rewrites
  assets/aftrhrs/models/block/smithing/*.json    Java block models: explicit per-face UVs, vanilla block.json display
  assets/aftrhrs/textures/block/smithing/*.png   16x16 textures
and writes a contact sheet of the textures to tools/out/smithing_blocks_textures.png.

Blockstates, item definitions and lang are written elsewhere. Every station's FRONT faces NORTH (z = 0); blockstates
rotate it (east y=90, south y=180, west y=270). The block entity renderers and collision shapes depend on the geometry
below (model pixels 0..16), so keep these numbers in sync with the Java code:

  forge_hearth(_cold/_lit)  full cube; firebox x3..13 y3..10 recessed to z11; coal bed x3..13 z1..11 y3..5 (the only
                            texture that changes, plus the lit firebox glow); grate on top; heat dial at x8 y13 r2.5
  bellows(_pressed)         body x2..14 z3..16, top board top at y9 (y5 pressed), nozzle x7..9 y3..5 out to z0
  crucible                  outer x2..14 z2..14 y0..12, interior x4..12 z4..12 floor y2, bands y3..4 / y9..10,
                            pour notch + lip on the north rim at x7..9
  casting_bench             top x0..16 z0..16 y6..8, 4 legs, groove on the north edge
  smithing_anvil            foot x2..14 z3..13 y0..3, waist x5..11 z5..11 y3..8, face x2..14 z4..12 y8..12,
                            horn x0..2 y9..11 z6..10 (west), hardy hole at x12 z8 (texture)
  quench_trough             outer x1..15 z3..13 y0..8, 1 px walls, inside floor y1, corner feet
  carving_bench             top board x1..15 z2..14 y10..12 on a log base x3..13 z4..12 y0..10, knife notch east
  assembly_bench            top x0..16 z0..16 y12..15, 4 legs, shelf y3..4
  whetstone_wheel           frame only (base, uprights x1..3 / x13..15, tray, crank on the east upright);
  whetstone_wheel_spin      the wheel: disc x5..11 about (8, 8, 8), r 5.5, axle x3..13 y7..9 z7..9
  runesmith_table(_lit)     top x0..16 z0..16 y11..14, pillar x3..13 z3..13 y0..11; catalyst circles at
                            (3.5,3.5) (12.5,3.5) (3.5,12.5) (12.5,12.5); _lit adds emissive groove overlays
  smithing_spout            channel x5..11 z8..16 y6..10 (open top), nozzle x6.5..9.5 z4..8 y4..8 (stream at x8 z6)
  fire_bricks               full cube
  great_crucible_drain      full cube of fire bricks, drain grate + a small tap sticking out of the north face

The *_glow textures (runesmith_table_lit) have alpha: the block needs a cutout render layer for the overlay to show
only the grooves (their transparent pixels carry the base colors, so a solid layer degrades to a fully lit top).
"""
import io
import json
import math
import os
import random
import shutil
import zipfile

from PIL import Image, ImageDraw

MOD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(MOD, 'src', 'main', 'resources', 'assets', 'aftrhrs')
MODEL_DIR = os.path.join(ASSETS, 'models', 'block', 'smithing')
TEX_DIR = os.path.join(ASSETS, 'textures', 'block', 'smithing')
SHEET = os.path.join(MOD, 'tools', 'out', 'smithing_blocks_textures.png')
NS = 'aftrhrs:block/smithing/'
JAR_PATH = os.environ.get('MC_CLIENT_JAR') or os.path.expanduser(
    '~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/'
    'minecraft-clientonly-deobf-26.3.jar')

# Copied from vanilla block/block.json (the parent every block item uses), so the items look like vanilla blocks.
DISPLAY = {
    'gui': {'rotation': [30, 225, 0], 'translation': [0, 0, 0], 'scale': [0.625, 0.625, 0.625]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.25, 0.25, 0.25]},
    'fixed': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [0.5, 0.5, 0.5]},
    'on_shelf': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375, 0.375, 0.375]},
    'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [0.4, 0.4, 0.4]},
    'firstperson_lefthand': {'rotation': [0, 225, 0], 'translation': [0, 0, 0], 'scale': [0.4, 0.4, 0.4]},
}


# ---------------------------------------------------------------------------------------------------------- colors

def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, 255)


def ramp(*hexes):
    return [rgb(h) for h in hexes]


def pick(r, i):
    return r[max(0, min(len(r) - 1, int(i)))]


def shade(c, f):
    return tuple(max(0, min(255, int(round(v * f)))) for v in c[:3]) + (255,)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def lum(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


HEARTH = ramp(0x1b191b, 0x262325, 0x322e30, 0x3f3a3b, 0x4d4747, 0x5d5655, 0x6f6865, 0x837b76)
HEARTH_STONE = ramp(0x3e3c3c, 0x4d4a49, 0x5d5a58, 0x6e6a67, 0x807b77, 0x928d88, 0xa49f99)
HEARTH_MORTAR = [rgb(0x161415), rgb(0x1b191a), rgb(0x121011)]
IRON = ramp(0x17171b, 0x212126, 0x2b2b32, 0x37373f, 0x45454e, 0x55555f, 0x686873, 0x7f7f8a, 0x9c9ca6, 0xc0c0c8)
BRASS = ramp(0x3e2a10, 0x5a4016, 0x7a5a22, 0x9a7630, 0xb8923e, 0xd0ac52, 0xe6c878)
FIRE = ramp(0x7e4020, 0x9c532a, 0xb86734, 0xcd7c42, 0xdd9454, 0xe9ad6c, 0xf3c78e)
FIRE_MORTAR = [rgb(0xd6c6a6), rgb(0xcbbb9a), rgb(0xbfae8d), rgb(0xd0c09e)]
SOOT = ramp(0x150c08, 0x1e120c, 0x281810, 0x331f15, 0x40281a, 0x4f3220)
TERRA = ramp(0x55291a, 0x6e361f, 0x864427, 0x9c5331, 0xb2643d, 0xc5784f, 0xd58f66)
SCORCH = ramp(0x110a07, 0x1a100b, 0x24150e, 0x2f1b12, 0x3c2317, 0x4d2d1d)
CAST = ramp(0x3a3c40, 0x47494e, 0x55585c, 0x64676b, 0x74777a, 0x85888a, 0x989a9b, 0xadafae)
STEEL = ramp(0x121419, 0x1b1e24, 0x24282f, 0x2e333b, 0x3a4049, 0x484f5a, 0x5a626e, 0x717b88, 0x8f99a6, 0xb5bec8,
             0xdfe6ee)
TWOOD = ramp(0x1f140c, 0x2a1b10, 0x362315, 0x432c1b, 0x513521, 0x613f28, 0x724a30)
OAK = ramp(0x5a391b, 0x6d4623, 0x82552c, 0x966535, 0xa9763f, 0xbb874c, 0xcb995c)
LEATHER = ramp(0x24120c, 0x341a10, 0x452416, 0x572e1c, 0x6a3a24, 0x7c472c, 0x8e5536)
CWOOD = ramp(0x6a4220, 0x7f5129, 0x956133, 0xa9723f, 0xbb834c, 0xcb945b, 0xdaa76e)
BARK = ramp(0x221510, 0x2f1d14, 0x3d2619, 0x4c301f, 0x5c3b26, 0x6d472e)
AWOOD = ramp(0x1c130d, 0x271a11, 0x332216, 0x402b1c, 0x4e3422, 0x5d3e29, 0x6e4a32)
WWOOD = ramp(0x4e331b, 0x613f21, 0x754d28, 0x895c30, 0x9c6b3a, 0xae7c46, 0xbf8e55)
SAND = ramp(0x7a6646, 0x8f7a56, 0xa48e66, 0xb8a277, 0xc9b488, 0xd8c59a, 0xe6d6b0)
BLACK = ramp(0x0d0c10, 0x141318, 0x1c1a21, 0x24222a, 0x2d2a34, 0x37333f, 0x423d4b, 0x4f4959, 0x5e5769)
COAL = ramp(0x0f0f13, 0x17171c, 0x202027, 0x2b2b34, 0x393945, 0x4d4d5c, 0x6b6b7f)
ASH = ramp(0x48423e, 0x5a534e, 0x6c645e, 0x7e766e, 0x90877e, 0xa29990, 0xb6ada3)
EMBER = ramp(0x2a0d07, 0x4a1409, 0x7a200b, 0xb0340e, 0xdc5414, 0xfa8022, 0xffae3c, 0xffd96e, 0xfff0b0)
WATER = ramp(0x203c74, 0x2a4c8c, 0x3460a6, 0x4274bc, 0x5a8ad0, 0x7aa6e0)
CYAN, CYAN_DEEP, CYAN_HOT = rgb(0x5ef2ff), rgb(0x1fb4d6), rgb(0xd2fdff)
GLOW = rgb(0xff7a26)
CHALK = rgb(0xe8e2d4)
HOLE = rgb(0x0a0a0c)


# --------------------------------------------------------------------------------------------------- texture kit

def blank(c=(0, 0, 0, 0)):
    return Image.new('RGBA', (16, 16), c)


class Vanilla:
    """Reads vanilla block textures from the client jar; only used as grain/noise sources."""

    def __init__(self, path):
        self.jar = zipfile.ZipFile(path)

    def img(self, name):
        data = self.jar.read('assets/minecraft/textures/block/%s.png' % name)
        return Image.open(io.BytesIO(data)).convert('RGBA').crop((0, 0, 16, 16))

    def noise(self, name):
        """Luminance of a vanilla texture as a 16x16 field, mean 0, roughly -1..1 (1 = two std devs)."""
        px = self.img(name).load()
        ls = [[lum(px[x, y]) for x in range(16)] for y in range(16)]
        flat = [v for row in ls for v in row]
        m = sum(flat) / 256
        sd = max(1e-6, math.sqrt(sum((v - m) ** 2 for v in flat) / 256))
        return [[clamp((v - m) / (2 * sd), -1, 1) for v in row] for row in ls]

    def gmap(self, name, r, lo=0, hi=None):
        """Gradient-maps a vanilla texture onto ramp indices lo..hi (keeps its shapes, not its colors)."""
        hi = len(r) - 1 if hi is None else hi
        px = self.img(name).load()
        ls = [[lum(px[x, y]) for x in range(16)] for y in range(16)]
        a, b = min(min(row) for row in ls), max(max(row) for row in ls)
        cv = Canvas(r)
        for y in range(16):
            for x in range(16):
                t = (ls[y][x] - a) / max(1e-6, b - a)
                cv.i[y][x] = lo + min(hi - lo, int(t * (hi - lo + 1)))
        return cv


class Canvas:
    """A 16x16 grid of ramp indices (pixel-art friendly: every pixel lands on a palette step)."""

    def __init__(self, r, base=0):
        self.r = r
        self.i = [[base] * 16 for _ in range(16)]

    def add(self, x, y, d):
        if 0 <= x < 16 and 0 <= y < 16:
            self.i[y][x] += d

    def put(self, x, y, v):
        if 0 <= x < 16 and 0 <= y < 16:
            self.i[y][x] = v

    def noise(self, nz, thresh=0.5, amount=1):
        for y in range(16):
            for x in range(16):
                v = nz[y][x]
                self.i[y][x] += amount if v > thresh else -amount if v < -thresh else 0
        return self

    def speckle(self, seed, p=0.3, d=1):
        rng = random.Random(seed)
        for y in range(16):
            for x in range(16):
                if rng.random() < p:
                    self.i[y][x] += rng.choice((-d, d))
        return self

    def img(self):
        im = blank()
        px = im.load()
        for y in range(16):
            for x in range(16):
                px[x, y] = pick(self.r, self.i[y][x])
        return im


def put(px, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        px[x, y] = c if len(c) == 4 else tuple(c) + (255,)


def rect(px, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(px, x, y, c)


def darken(px, x, y, f):
    if 0 <= x < 16 and 0 <= y < 16:
        px[x, y] = shade(px[x, y], f)


def wood(r, seed, horizontal=True, size=4, base=3, seams=True, offset=0, streaks=22, joints=True):
    """Planks `size` px wide running along x (horizontal) or y, with grain streaks, dark seams and butt joints."""
    rng = random.Random(seed)
    cv = Canvas(r, base)
    tone = {}
    for a in range(16):
        k, pos = divmod(a + offset, size)
        if k not in tone:
            tone[k] = base + rng.choice((-1, 0, 0, 1))
        for b in range(16):
            x, y = (b, a) if horizontal else (a, b)
            cv.i[y][x] = tone[k] - (2 if seams and pos == size - 1 else 0)
    for _ in range(streaks):
        a = rng.randrange(16)
        b0, n, d = rng.randrange(16), rng.randint(2, 6), rng.choice((-1, -1, 1))
        if seams and (a + offset) % size == size - 1:
            continue
        for b in range(b0, b0 + n):
            x, y = (b % 16, a) if horizontal else (a, b % 16)
            cv.i[y][x] += d
    if seams and joints:
        for k in list(tone):
            b = rng.randrange(16)
            for pos in range(size - 1):
                a = k * size + pos - offset
                if 0 <= a < 16:
                    x, y = (b, a) if horizontal else (a, b)
                    cv.i[y][x] = tone[k] - 2
    return cv


def bricks(seed, r, mortar, y0=0, row_h=4, brick_w=8, shift=4, tones=(2, 3, 3, 4), nz=None, accent=None,
           accent_p=0.0):
    """A running-bond brick wall, tiling seamlessly; rows start at y0. Returns an image."""
    rng = random.Random(seed)
    im = blank()
    px = im.load()
    info = {}
    for y in range(16):
        row, iy = divmod(y - y0, row_h)
        for x in range(16):
            bx = (x + (shift if row % 2 else 0)) % 16
            if iy == row_h - 1 or bx % brick_w == brick_w - 1:
                px[x, y] = mortar[rng.randrange(len(mortar))]
                continue
            key = (row, bx // brick_w)
            if key not in info:
                info[key] = (rng.choice(tones), accent is not None and rng.random() < accent_p)
            t, acc = info[key]
            i = t + (1 if iy == 0 else 0) - (1 if iy == row_h - 2 and row_h > 2 else 0)
            if nz is not None:
                v = nz[y][x]
                i += 1 if v > 0.55 else -1 if v < -0.55 else 0
            px[x, y] = pick(accent if acc else r, i)
    return im


def voronoi(seed, n):
    """Lumps: for each pixel (lump index, distance to the lump edge, up-left lighting -1..1, distance to center)."""
    rng = random.Random(seed)
    pts = [(rng.uniform(0.5, 15.5), rng.uniform(0.5, 15.5)) for _ in range(n)]
    out = {}
    for y in range(16):
        for x in range(16):
            cx, cy = x + 0.5, y + 0.5
            ds = sorted((math.hypot(cx - px_, cy - py_), i) for i, (px_, py_) in enumerate(pts))
            (d1, i1), (d2, _) = ds[0], ds[1]
            dx, dy = cx - pts[i1][0], cy - pts[i1][1]
            light = -(dx + dy) / (math.hypot(dx, dy) + 1e-6) * min(1.0, d1 / 1.6) / 1.41
            out[(x, y)] = (i1, d2 - d1, light, d1)
    return out


def rivet(px, x, y, r=IRON, hi=8, lo=2):
    put(px, x, y, pick(r, hi))
    if 0 <= x + 1 < 16 and 0 <= y + 1 < 16:
        px[x + 1, y + 1] = shade(px[x + 1, y + 1], 0.7)


def mask_outline(mask):
    """Pixels of a mask that touch a non-mask pixel (4-neighbourhood)."""
    out = set()
    for (x, y) in mask:
        if any((x + dx, y + dy) not in mask for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            out.add((x, y))
    return out


def ascii_mask(rows, x0=0, y0=0):
    return {(x0 + x, y0 + y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch == '#'}


def iron_tex(seed, base=4):
    return Canvas(IRON, base).speckle(seed, 0.35).img()


# ---------------------------------------------------------------------------------------------------- textures

def tex_forge(V, T):
    nz = V.noise('deepslate')

    def wall(seed):
        im = bricks(seed, HEARTH, HEARTH_MORTAR, y0=1, nz=nz, accent=HEARTH_STONE, accent_p=0.16)
        px = im.load()
        for y in range(1, 5):  # soot creeping down from the top
            for x in range(16):
                darken(px, x, y, 0.74 + 0.06 * y)
        for y in range(13, 16):  # plinth course (the y0..3 base reads as a heavier stone footing)
            for x in range(16):
                v = nz[y][x]
                i = (6 if y == 13 else 4 if y == 14 else 3) + (1 if v > 0.6 else -1 if v < -0.6 else 0)
                px[x, y] = pick(HEARTH_STONE, i - 2) if (x in (3, 11) and y > 13) else pick(HEARTH, i)
        for x in range(16):  # iron band along the top
            px[x, 0] = pick(IRON, 5 if x % 2 else 4)
        for y in range(0, 13):  # angle-iron straps on the corners
            px[0, y] = pick(IRON, 5)
            px[15, y] = pick(IRON, 4)
        for y in (2, 6, 10):
            put(px, 0, y, pick(IRON, 8))
            put(px, 15, y, pick(IRON, 7))
        for x in (4, 11):
            put(px, x, 0, pick(IRON, 8))
        return im

    T['forge_side'] = wall('forge_side')

    front = wall('forge_front')
    px = front.load()
    rect(px, 3, 6, 12, 12, SOOT[0])  # the opening (never visible; the firebox faces show instead)
    for y in range(6, 13):  # fire-brick jambs lining the mouth
        for x in (2, 13):
            px[x, y] = pick(FIRE, 2 if (y % 3) else 0) if y > 7 else shade(pick(FIRE, 2), 0.55)
    for y in range(1, 5):  # soot plume above the mouth
        for x in range(3, 13):
            darken(px, x, y, 0.55 + 0.1 * (4 - y) + 0.04 * abs(x - 7.5))
    for x in range(2, 14):  # iron lintel over the mouth (y 10..11)
        px[x, 5] = pick(IRON, 6 if x in (2, 13) else 5)
    for x in (3, 8, 12):
        put(px, x, 5, pick(IRON, 9))
    for x in range(3, 13):  # worn stone sill under the mouth (y 2..3)
        px[x, 13] = pick(HEARTH_STONE, 4 + (x % 3 == 0))
    # Heat dial: a half disc centered (x8, y13) = (u8, v3), radius 2.5, bands dark gray -> white left to right
    # (u grows to the viewer's right on the north face), a brass bezel and base. The needle is drawn in Java.
    bands = [rgb(0x4c4c56), rgb(0xc0281c), rgb(0xf07c1c), rgb(0xfad63c), rgb(0xfffaee)]
    for v in range(0, 4):
        for u in range(3, 13):
            dx, dy = u + 0.5 - 8, 3 - (v + 0.5)
            r = math.hypot(dx, dy)
            if v == 3:
                if 4 <= u <= 11:
                    px[u, v] = pick(IRON, 5 if u in (4, 11) else 6)
            elif r <= 2.6:
                ang = math.degrees(math.atan2(dy, dx))
                px[u, v] = bands[min(4, int((180 - ang) / 36))]
            elif r <= 3.6:
                px[u, v] = pick(IRON, 8 if dx < 0 else 7)
    T['forge_front'] = front

    def top(lit):
        cv = Canvas(HEARTH, 4).noise(V.noise('polished_deepslate'), 0.6)
        for k in range(16):
            cv.put(k, 0, 6), cv.put(0, k, 6), cv.put(k, 15, 3), cv.put(15, k, 3)
        for k in range(1, 15):
            cv.put(k, 1, 3) if 1 < k < 14 else None
            cv.put(1, k, 3) if 1 < k < 14 else None
        im = cv.img()
        px = im.load()
        rng = random.Random('forge_top')
        for cx, cy in ((0, 0), (14, 0), (0, 14), (14, 14)):  # iron corner caps
            rect(px, cx, cy, cx + 1, cy + 1, pick(IRON, 5))
            put(px, cx + (cx == 0), cy + (cy == 0), pick(IRON, 8))
        for v in range(3, 13):
            for u in range(3, 13):
                if lit:
                    d = max(abs(u - 7.5), abs(v - 7.5)) / 5.0
                    c = pick(EMBER, 7 - d * 3.5 + rng.choice((0, 0, -1, 1)))
                else:
                    c = rgb(0x3a3532) if rng.random() < 0.14 else rgb(0x16110f)
                px[u, v] = c
        for k in range(2, 14):  # grate frame
            px[k, 2], px[2, k] = pick(IRON, 6), pick(IRON, 6)
            px[k, 13], px[13, k] = pick(IRON, 4), pick(IRON, 4)
        for u in (4, 6, 9, 11):  # bars front to back
            for v in range(3, 13):
                px[u, v] = pick(IRON, 5 if v % 2 else 4)
        for v in (5, 10):  # cross bars
            for u in range(3, 13):
                px[u, v] = pick(IRON, 6)
        return im

    T['forge_top'] = top(False)
    T['forge_top_lit'] = top(True)

    def inner(lit):
        im = bricks('forge_inner', SOOT, [rgb(0x0c0806), rgb(0x100a07)], y0=6, row_h=3, brick_w=4, shift=2,
                    tones=(1, 2, 2, 3))
        px = im.load()
        rng = random.Random('forge_inner_soot')
        for _ in range(26):
            darken(px, rng.randrange(16), rng.randrange(16), 0.7)
        if lit:
            for y in range(16):
                g = clamp((y - 5) / 8.0)
                for x in range(16):
                    c = px[x, y]
                    m = lum(c) < 16
                    px[x, y] = mix(c, rgb(0xffb040) if m else GLOW, (0.35 + 0.6 * g) if m else (0.12 + 0.42 * g))
        return im

    T['forge_inner'] = inner(False)
    T['forge_inner_lit'] = inner(True)
    ceil = Canvas(SOOT, 1).speckle('forge_ceiling', 0.4).img()
    T['forge_ceiling'] = ceil
    T['forge_ceiling_lit'] = Image.blend(ceil, blank(GLOW), 0.22)
    T['forge_bottom'] = Canvas(HEARTH, 2).noise(nz, 0.5).img()

    # Coal bed: the up face shows u3..12 v1..10, its front (z1, y3..5) shows u3..12 v11..12.
    ash = Canvas(ASH, 3).noise(V.noise('gravel'), 0.35).speckle('ash', 0.25).img()
    px = ash.load()
    vo = voronoi('ash_char', 9)
    for (x, y), (i, edge, light, d1) in vo.items():
        if i in (1, 4, 7) and d1 < 1.25:
            px[x, y] = pick(COAL, 3 + round(light * 1.5))
    rng = random.Random('ash_fleck')
    for _ in range(9):
        put(px, rng.randrange(3, 13), rng.randrange(1, 11), rgb(0xd4cdc2))
    for x in range(16):
        darken(px, x, 11, 0.82)
        darken(px, x, 12, 0.62)
    T['coal_ash'] = ash

    vo = voronoi('coal_cold', 19)
    cold = blank()
    px = cold.load()
    tone = random.Random('coal_tone')
    lump_t = {i: tone.choice((-1, 0, 0, 1)) for i in range(19)}
    best = {}
    for (x, y), (i, edge, light, d1) in vo.items():
        if edge < 0.55:
            px[x, y] = COAL[0]
        else:
            px[x, y] = pick(COAL, 2 + lump_t[i] + round(light * 1.6))
            if light > best.get(i, (-9, None))[0]:
                best[i] = (light, (x, y))
    for i, (l, (x, y)) in sorted(best.items()):
        if i % 2 == 0 and l > 0.4:
            px[x, y] = rgb(0x8c8ca6)
    for x in range(16):
        darken(px, x, 12, 0.6)
    T['coal_cold'] = cold

    vo = voronoi('coal_lit', 18)
    lit = blank()
    px = lit.load()
    heat = random.Random('coal_heat')
    lump_h = {i: heat.uniform(0.25, 1.0) for i in range(18)}
    for (x, y), (i, edge, light, d1) in vo.items():
        h = lump_h[i]
        if edge < 0.6:
            px[x, y] = pick(EMBER, 8 if (x + y) % 3 else 7)
        else:
            t = clamp(1 - edge / 2.6)
            idx = round(1.5 + 5.5 * (0.4 * h + 0.6 * t)) - (2 if light > 0.75 and h < 0.6 else 0)
            px[x, y] = pick(EMBER, idx)
            if h < 0.45 and light > 0.85:
                px[x, y] = rgb(0x4a3a36)  # a little gray crust on the cooler lumps
    for x in range(16):
        darken(px, x, 12, 0.8)
    T['coal_lit'] = lit


def tex_bellows(V, T):
    cv = wood(OAK, 'bellows_board', horizontal=False, size=4, base=3, offset=2)
    for k in range(16):
        cv.add(k, 3, -1), cv.add(k, 15, -1), cv.add(2, k, -1)
    im = cv.img()
    px = im.load()
    for v in (6, 12):  # iron straps across the planks
        for u in range(2, 14):
            px[u, v] = pick(IRON, 5 if u % 2 else 4)
        for u in (3, 7, 11):
            put(px, u, v, pick(BRASS, 6))
    T['bellows_board'] = im
    T['bellows_edge'] = wood(OAK, 'bellows_edge', horizontal=True, size=16, base=4, seams=False).img()
    T['handle_wood'] = wood(OAK, 'handle_wood', horizontal=False, size=16, base=5, seams=False, streaks=10).img()
    cv = Canvas(LEATHER, 4).speckle('leather', 0.22)
    rng = random.Random('leather_crease')
    for _ in range(7):  # a few creases where the folds flex
        x, y = rng.randrange(16), rng.randrange(16)
        cv.add(x, y, -2)
        cv.add(x + 1, y, -1)
    T['bellows_leather'] = cv.img()
    T['bellows_fold'] = Canvas(LEATHER, 2).speckle('leather_fold', 0.3).img()
    noz = iron_tex('bellows_nozzle', 6)
    px = noz.load()
    rect(px, 7, 11, 8, 12, HOLE)
    put(px, 8, 12, rgb(0x24242a))
    T['bellows_nozzle'] = noz
    T['brass'] = Canvas(BRASS, 3).speckle('brass', 0.35).img()
    T['iron'] = iron_tex('iron', 4)


def tex_crucible(V, T):
    nz = V.noise('terracotta')
    cv = Canvas(TERRA, 4).noise(nz, 0.45)
    for x in range(16):
        cv.add(x, 4, 1)  # rim highlight (y 11..12)
        cv.add(x, 14, -1)
        cv.add(x, 15, -2)  # soot where the pot sits in the fire
    rng = random.Random('crucible_soot')
    for _ in range(10):
        cv.add(rng.randrange(16), rng.randrange(11, 15), -1)
    im = cv.img()
    px = im.load()
    for v in (6, 12):  # iron bands, y 9..10 and y 3..4, with shadow under and a lit edge over
        for u in range(16):
            px[u, v] = pick(IRON, 5 if u % 2 else 6)
            darken(px, u, v + 1, 0.72)
            px[u, v - 1] = shade(px[u, v - 1], 1.08)
        for u in (3, 7, 11, 15):
            put(px, u, v, pick(IRON, 9))
    T['crucible_side'] = im

    cv = Canvas(SCORCH, 2).noise(V.noise('blackstone'), 0.5)
    for x in range(16):
        cv.add(x, 4, 2)  # just below the rim it is still clay-colored
        cv.add(x, 5, 1)
    im = cv.img()
    px = im.load()
    rng = random.Random('crucible_slag')
    for _ in range(7):  # glossy slag runs
        x, y0, n = rng.randrange(16), rng.randrange(4, 9), rng.randint(2, 5)
        for y in range(y0, y0 + n):
            put(px, x, y, rgb(0x5e2c12) if y < y0 + n - 1 else rgb(0x7c3a16))
    T['crucible_inner'] = im

    cv = Canvas(TERRA, 4).noise(nz, 0.6)
    for k in range(2, 14):
        for (x, y) in ((k, 2), (2, k)):
            cv.put(x, y, 6)
        for (x, y) in ((k, 13), (13, k)):
            cv.put(x, y, 5)
    for k in range(3, 13):
        for (x, y) in ((k, 3), (3, k), (k, 12), (12, k)):
            cv.put(x, y, 2 if (x + y) % 3 else 1)
    T['crucible_rim'] = cv.img()
    T['crucible_bottom'] = Canvas(TERRA, 1).noise(nz, 0.5).img()


def tex_casting(V, T):
    nz = V.noise('stone')
    cv = Canvas(CAST, 3).noise(nz, 0.55)
    for k in range(16):
        cv.put(k, 0, 5), cv.put(0, k, 5), cv.put(k, 15, 2), cv.put(15, k, 2)
    for k in range(2, 14):  # faint seat for the mold (x 3..13)
        cv.put(k, 2, 4) if k > 2 else None
        cv.add(k, 13, -1), cv.add(2, k, -1), cv.add(13, k, -1)
    im = cv.img()
    px = im.load()
    for u in range(2, 14):  # pour-off groove along the north edge, draining out at the middle
        px[u, 1] = pick(CAST, 0)
        px[u, 2] = pick(CAST, 5)
    rect(px, 7, 0, 8, 0, pick(CAST, 0))
    rng = random.Random('casting_scorch')
    for _ in range(14):
        a = rng.uniform(0, 2 * math.pi)
        r = rng.uniform(3.5, 5.6)
        darken(px, int(8 + math.cos(a) * r), int(8 + math.sin(a) * r), 0.72)
    for (x, y, c) in ((4, 11, 0xc6cace), (12, 5, 0xd08040), (11, 12, 0xe8c050), (5, 4, 0x9ea4aa)):
        put(px, x, y, rgb(c))  # old splashes of metal
    T['casting_top'] = im

    def side(front):
        cv = Canvas(CAST, 3).noise(nz, 0.6)
        for x in range(16):
            cv.put(x, 8, 5)
            cv.put(x, 9, 3)
        im = cv.img()
        px = im.load()
        if front:
            rect(px, 7, 8, 8, 8, pick(CAST, 0))
            rect(px, 7, 9, 8, 9, pick(CAST, 1))
        return im

    T['casting_side'] = side(False)
    T['casting_front'] = side(True)
    cv = Canvas(CAST, 3).noise(nz, 0.55)
    for x in range(16):
        cv.put(x, 10, 1)
        cv.put(x, 13, 2)
        cv.add(x, 15, -1)
    T['casting_leg'] = cv.img()
    T['casting_under'] = Canvas(CAST, 1).noise(nz, 0.6).img()


def tex_anvil(V, T):
    rng = random.Random('anvil')
    # Rows line up with the elements: face y8..12 = v4..7, waist y3..8 = v8..12, foot y0..3 = v13..15.
    rows = {4: 7, 5: 5, 6: 5, 7: 3, 8: 2, 9: 4, 10: 4, 11: 4, 12: 3, 13: 6, 14: 4, 15: 3}
    cv = Canvas(STEEL, 4)
    for y in range(16):
        for x in range(16):
            cv.i[y][x] = rows.get(y, 4)
    T['anvil_side'] = cv.img()

    cv = Canvas(STEEL, 6)
    for y in range(4, 12):
        for x in range(2, 14):
            cv.i[y][x] = 8 if 6 <= y <= 9 else 7
    for _ in range(9):  # polished streaks along the face
        y = rng.randrange(5, 11)
        x0, n = rng.randrange(3, 11), rng.randint(2, 4)
        for x in range(x0, min(13, x0 + n)):
            cv.add(x, y, rng.choice((1, -1)))
    for x in range(2, 14):
        cv.put(x, 4, 6), cv.put(x, 11, 5)
    for y in range(4, 12):
        cv.put(2, y, 6), cv.put(13, y, 5)
    for y in range(6, 10):  # horn top
        cv.put(0, y, 6), cv.put(1, y, 7)
    cv.put(0, 6, 5), cv.put(0, 9, 5)
    im = cv.img()
    px = im.load()
    rect(px, 11, 7, 12, 8, HOLE)  # hardy hole, centered x12 z8
    for x in (11, 12):
        px[x, 6] = pick(STEEL, 4)
        px[x, 9] = pick(STEEL, 8)
    px[10, 7], px[10, 8] = pick(STEEL, 4), pick(STEEL, 4)
    px[13, 7], px[13, 8] = pick(STEEL, 8), pick(STEEL, 8)
    put(px, 9, 8, pick(STEEL, 0))  # pritchel hole
    put(px, 9, 7, pick(STEEL, 4))
    for (x, y) in ((4, 7), (6, 9), (7, 6), (5, 5)):
        put(px, x, y, pick(STEEL, 10))  # glints on the worn face
    T['anvil_top'] = im

    cv = Canvas(STEEL, 4).speckle('anvil_base', 0.3)
    for k in range(2, 14):
        cv.put(k, 3, 6), cv.put(k, 12, 3)
    for k in range(3, 13):
        cv.put(2, k, 6), cv.put(13, k, 3)
    for k in range(4, 12):
        cv.put(k, 4, 2), cv.put(k, 11, 2), cv.put(4, k, 2), cv.put(11, k, 2)
    T['anvil_base_top'] = cv.img()
    T['anvil_under'] = Canvas(STEEL, 1).speckle('anvil_under', 0.3).img()


def tex_trough(V, T):
    rows = {8: 5, 9: 0, 10: 4, 11: 3, 12: 1, 13: 4, 14: 0, 15: 2}

    def outer(seed, c0, c1):
        rng = random.Random(seed)
        cv = Canvas(TWOOD, 3)
        for y in range(16):
            for x in range(16):
                cv.i[y][x] = rows.get(y, 3)
        for _ in range(14):
            y = rng.choice((10, 11, 13, 15))
            x0, n = rng.randrange(16), rng.randint(2, 5)
            for x in range(x0, x0 + n):
                cv.add(x % 16, y, rng.choice((-1, 1)))
        for y in range(8, 16):
            cv.add(c0, y, -1)
            cv.add(c1, y, -1)
        im = cv.img()
        px = im.load()
        for v in (9, 14):  # two iron hoops (y 6..7 and y 1..2)
            for u in range(16):
                px[u, v] = pick(IRON, 5 if u % 2 else 4)
            for u in (c0 + 1, (c0 + c1) // 2, c1 - 1):
                put(px, u, v, pick(IRON, 9))
        return im

    T['trough_long'] = outer('trough_long', 1, 14)
    T['trough_short'] = outer('trough_short', 3, 12)
    cv = wood(TWOOD, 'trough_inner', horizontal=False, size=3, base=2, streaks=10)
    for x in range(16):
        cv.add(x, 8, 2)
        for y in range(12, 16):
            cv.add(x, y, -1)
    T['trough_inner'] = cv.img()
    cv = wood(TWOOD, 'trough_rim', horizontal=True, size=16, base=5, seams=False)
    im = cv.img()
    px = im.load()
    for (x, y) in ((1, 3), (14, 3), (1, 12), (14, 12)):
        put(px, x, y, pick(IRON, 7))
    T['trough_rim'] = im
    T['trough_floor'] = wood(TWOOD, 'trough_floor', horizontal=True, size=4, base=1).img()
    T['trough_feet'] = Canvas(TWOOD, 2).speckle('trough_feet', 0.3).img()


def tex_carving(V, T):
    rng = random.Random('carving')
    cols = [(1, 3), (4, 7), (8, 11), (12, 14)]
    rws = [(2, 5), (6, 9), (10, 13)]
    cv = Canvas(CWOOD, 4)
    for ri, (y0, y1) in enumerate(rws):  # end-grain blocks, alternating tone, each with a growth ring arc
        for ci, (x0, x1) in enumerate(cols):
            base = 5 if (ri + ci) % 2 else 3
            corner = ((x0 - 0.5 if ci % 2 else x1 + 1.5), (y0 - 0.5 if ri % 2 else y1 + 1.5))
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    d = math.hypot(x + 0.5 - corner[0], y + 0.5 - corner[1])
                    cv.i[y][x] = base - (1 if 2.6 <= d < 3.5 else 0)
    for x in range(16):
        cv.put(x, 1, 1), cv.put(x, 14, 1)
    for y in range(16):
        cv.put(0, y, 1), cv.put(15, y, 1)
    for k in (3, 7, 11):  # glue lines
        for y in range(2, 14):
            cv.put(k, y, cv.i[y][k] - 2) if (y % 4) else None
    for k in (5, 9):
        for x in range(1, 15):
            cv.put(x, k, cv.i[k][x] - 1)
    for (x, y, n) in ((3, 4, 3), (8, 9, 4)):  # knife scores
        for s in range(n):
            cv.put(x + s, y + s, 6)
    im = cv.img()
    px = im.load()
    rect(px, 13, 7, 14, 8, rgb(0x2a170a))  # the knife notch, cut into the east edge
    px[12, 7], px[12, 8] = pick(CWOOD, 1), pick(CWOOD, 1)
    px[13, 9], px[14, 9] = pick(CWOOD, 6), pick(CWOOD, 6)
    T['carving_top'] = im

    def side(east):
        cv = Canvas(CWOOD, 3)
        for x in range(16):
            t = 4 if (x // 4) % 2 else 3
            for y in range(16):
                cv.i[y][x] = t
            cv.add(x, 4, 1)
            cv.add(x, 5, -1 if x % 4 == 0 else 0)
        im = cv.img()
        px = im.load()
        if east:
            rect(px, 7, 4, 8, 5, rgb(0x2a170a))
            px[9, 4], px[9, 5] = pick(CWOOD, 1), pick(CWOOD, 1)
        return im

    T['carving_side'] = side(False)
    T['carving_side_east'] = side(True)
    cv = V.gmap('spruce_log', BARK, 1, 5)
    for x in range(16):
        cv.put(x, 6, 0)
        cv.add(x, 15, -1)
    T['carving_log'] = cv.img()
    cv = Canvas(CWOOD, 3)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            cv.i[y][x] = (3 if int(d) % 2 else 2) if d < 4.6 else 0
    T['carving_log_end'] = cv.img()
    T['carving_under'] = Canvas(CWOOD, 2).speckle('carving_under', 0.3).img()


def tex_assembly(V, T):
    cv = wood(AWOOD, 'assembly_top', horizontal=True, size=4, base=3)
    for k in range(16):
        cv.add(0, k, -1), cv.add(15, k, -1)
    im = cv.img()
    px = im.load()
    # Chalk outline of a pickaxe (head arc + handle) for the parts to snap onto.
    head = {(x, y) for x in range(1, 15) for y in range(16)
            if 3.2 + 0.11 * (x + 0.5 - 8) ** 2 <= y + 0.5 <= 5.6 + 0.11 * (x + 0.5 - 8) ** 2 - 0.0 and 1 <= x <= 14}
    handle = {(x, y) for x in (7, 8) for y in range(4, 14)}
    for (x, y) in sorted(mask_outline(head | handle)):
        px[x, y] = mix(px[x, y], CHALK, 0.42)
    for (x, y) in ((7, 12), (8, 13)):
        px[x, y] = mix(px[x, y], CHALK, 0.25)
    for (x, y) in ((1, 1), (13, 1), (1, 13), (13, 13)):  # rivet heads
        put(px, x, y, pick(IRON, 8))
        put(px, x + 1, y, pick(IRON, 5))
        put(px, x, y + 1, pick(IRON, 5))
        put(px, x + 1, y + 1, pick(IRON, 2))
    T['assembly_top'] = im
    cv = wood(AWOOD, 'assembly_edge', horizontal=True, size=16, base=3, seams=False)
    for x in range(16):
        cv.put(x, 1, 5), cv.add(x, 3, -1)
    im = cv.img()
    px = im.load()
    for y in (1, 2, 3):
        px[0, y], px[15, y] = pick(IRON, 5), pick(IRON, 4)
    put(px, 0, 2, pick(IRON, 8))
    put(px, 15, 2, pick(IRON, 7))
    T['assembly_edge'] = im
    cv = wood(AWOOD, 'assembly_leg', horizontal=False, size=16, base=3, seams=False)
    for x in range(16):
        cv.put(x, 4, 1), cv.put(x, 15, 1)
    T['assembly_leg'] = cv.img()
    T['assembly_shelf'] = wood(AWOOD, 'assembly_shelf', horizontal=True, size=4, base=4, offset=2).img()
    T['assembly_under'] = wood(AWOOD, 'assembly_under', horizontal=True, size=4, base=1).img()


def tex_whetstone(V, T):
    T['whet_wood'] = wood(WWOOD, 'whet_wood', horizontal=False, size=16, base=3, seams=False).img()
    cv = wood(WWOOD, 'whet_base', horizontal=True, size=16, base=3, seams=False)
    for x in range(16):
        cv.add(x, 14, 1), cv.add(x, 15, -1)
    T['whet_base'] = cv.img()
    T['whet_base_top'] = wood(WWOOD, 'whet_base_top', horizontal=True, size=4, base=3, offset=0).img()
    cv = Canvas(WWOOD, 4)
    for y in range(16):
        for x in range(16):
            cv.i[y][x] = 4 if (x + y) % 3 else 3
    im = cv.img()
    px = im.load()
    for (x, y) in ((1, 7), (2, 8), (13, 7), (14, 8)):
        put(px, x, y, pick(IRON, 6))  # pegs through the uprights' tops
    T['whet_wood_end'] = im
    im = T['whet_wood'].copy()
    px = im.load()
    for k in range(6, 10):  # iron bearing around the axle (z7..9, y7..9)
        px[k, 6], px[6, k] = pick(IRON, 6), pick(IRON, 6)
        px[k, 9], px[9, k] = pick(IRON, 3), pick(IRON, 3)
    rect(px, 7, 7, 8, 8, HOLE)
    T['whet_upright_inner'] = im
    im = blank()
    px = im.load()
    for v in range(16):
        for u in range(16):
            if u in (4, 11) or v in (5, 10):
                px[u, v] = pick(WWOOD, 5 if (u == 4 or v == 5) else 3)
            else:
                ripple = (u + 2 * v) % 7 == 0
                px[u, v] = pick(WATER, 4 if ripple else 2 + ((u * 3 + v) % 5 == 0))
    for (x, y) in ((5, 9), (10, 6), (10, 9)):
        put(px, x, y, rgb(0x6c6e6a))  # grit settled in the corners
    T['whet_tray'] = im
    cv = Canvas(WWOOD, 3)
    for x in range(16):
        cv.put(x, 13, 4)
    T['whet_tray_side'] = cv.img()

    nz = V.noise('sandstone')
    cv = Canvas(SAND, 5).noise(nz, 0.6).speckle('whet_face', 0.15)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if r > 4.7:
                cv.i[y][x] = 3  # worn outer edge
            elif 3.9 < r <= 4.7:
                cv.i[y][x] += 1
            elif 2.4 < r <= 3.1:
                cv.i[y][x] -= 2  # a turned ring
            elif r <= 1.9:
                cv.i[y][x] = 3
    im = cv.img()
    px = im.load()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if r <= 1.9:
                px[x, y] = pick(IRON, 6 if x + y < 15 else 4)  # washer around the axle
    T['whet_face'] = im
    # The grinding face: darker and gritty, a worn groove down the middle where blades meet it (u = x 5..11).
    cv = Canvas(SAND, 2).speckle('whet_rim', 0.45)
    for y in range(16):
        cv.put(5, y, 4), cv.put(10, y, 4)
        cv.add(7, y, -1), cv.add(8, y, -1)
    T['whet_rim'] = cv.img()


def tex_runesmith(V, T):
    nz = V.noise('polished_blackstone')

    def grooves_top():
        g = set()
        for y in range(16):
            for x in range(16):
                cx, cy = x + 0.5, y + 0.5
                if abs(math.hypot(cx - 8, cy - 8) - 4.1) < 0.5:  # the tool's circle
                    g.add((x, y))
                for (sx, sy) in ((3.5, 3.5), (12.5, 3.5), (3.5, 12.5), (12.5, 12.5)):
                    if abs(math.hypot(cx - sx, cy - sy) - 2.0) < 0.36:  # catalyst circles
                        g.add((x, y))
        for k in range(6, 10):  # joining lines between the catalyst circles, along the edges
            g |= {(k, 3), (k, 12), (3, k), (12, k)}
        return g  # (the tool's circle meets each catalyst circle on the diagonals)

    def pillar_glyph():
        return ascii_mask(['#....#',
                           '.#..#.',
                           '..##..',
                           '#.##.#',
                           '.#..#.',
                           '..##..'], 5, 7)

    def top_base():
        cv = Canvas(BLACK, 6).noise(nz, 0.8)
        for k in range(16):
            cv.put(k, 0, 7), cv.put(0, k, 7), cv.put(k, 15, 4), cv.put(15, k, 4)
        return cv

    def carve(px, grooves, glow):
        for (x, y) in grooves:
            if glow:
                px[x, y] = CYAN
            else:  # a carved groove: dark, with a trace of old cyan dust
                px[x, y] = rgb(0x0a1317) if (x * 3 + y) % 5 else rgb(0x1a4048)

    g = grooves_top()
    top = top_base()
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        top.put(x, y, 2)  # catalyst sockets, slightly sunk
    for y in range(5, 11):
        for x in range(5, 11):
            if math.hypot(x + 0.5 - 8, y + 0.5 - 8) < 3.4:
                top.add(x, y, 1)  # the tool's seat is polished smooth
    base = top.img()
    unlit = base.copy()
    carve(unlit.load(), g, False)
    T['rune_top'] = unlit
    glow = unlit.copy()
    px = glow.load()
    for y in range(16):
        for x in range(16):
            c = px[x, y]
            px[x, y] = (c[0], c[1], c[2], 0)
    carve(px, g, True)
    for (x, y) in g:
        if (x + y) % 4 == 0:
            px[x, y] = CYAN_HOT
    T['rune_top_glow'] = glow

    cv = Canvas(BLACK, 5).noise(nz, 0.7)
    for x in range(16):
        cv.put(x, 2, 7), cv.put(x, 3, 5), cv.put(x, 4, 3)
    im = cv.img()
    px = im.load()
    for x in range(1, 16, 3):
        put(px, x, 3, rgb(0x0a1216))  # carved tick runes along the edge
    T['rune_side'] = im

    cv = V.gmap('deepslate_tiles', BLACK, 3, 7)
    for x in range(16):
        cv.put(x, 5, 1)
        cv.put(x, 13, 2)
        cv.put(x, 14, 6)
        cv.put(x, 15, 4)
    pillar = cv.img()
    glyph = pillar_glyph()
    unlit = pillar.copy()
    carve(unlit.load(), glyph, False)
    T['rune_pillar'] = unlit
    glow = unlit.copy()
    px = glow.load()
    for y in range(16):
        for x in range(16):
            c = px[x, y]
            px[x, y] = (c[0], c[1], c[2], 0)
    carve(px, glyph, True)
    T['rune_pillar_glow'] = glow
    T['rune_under'] = Canvas(BLACK, 2).noise(nz, 0.6).img()


def tex_spout(V, T):
    # Walls y7..10 = v6..8 (rim on v6), floor y6..7 = v9.
    cv = Canvas(IRON, 4).speckle('spout_iron', 0.25)
    for x in range(16):
        cv.put(x, 6, 6)
        cv.put(x, 9, 3)
    for x in range(1, 16, 4):
        cv.put(x, 7, 6)
    T['spout_iron'] = cv.img()
    # Nozzle y4..8 = v8..11: a collar band, then the scorched lip it pours from.
    cv = Canvas(IRON, 4).speckle('spout_nozzle', 0.25)
    for x in range(16):
        cv.put(x, 8, 6)
        cv.put(x, 9, 3)
        cv.put(x, 10, 5)
        cv.put(x, 11, 2)
    im = cv.img()
    px = im.load()
    for x in range(16):
        px[x, 11] = mix(px[x, 11], rgb(0x7a4a2a), 0.22)  # a little metal baked onto the lip
    T['spout_nozzle'] = im
    lining = bricks('spout_lining', FIRE, [rgb(0x7a6a58), rgb(0x6e5e4e)], row_h=3, brick_w=4, shift=2,
                    tones=(1, 2, 2, 3))
    px = lining.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = shade(px[x, y], 0.8)
    T['spout_lining'] = lining
    floor = lining.copy()
    px = floor.load()
    for y in range(16):
        for x in (7, 8):
            px[x, y] = mix(px[x, y], rgb(0x9a8a7e), 0.75 if (y % 3) else 0.5)  # old metal run down the channel
    T['spout_floor'] = floor
    im = iron_tex('spout_hole', 4)
    px = im.load()
    for (x, y) in ((6, 8), (9, 8), (6, 11), (9, 11)):
        put(px, x, y, pick(IRON, 6))
    for k in range(6, 10):
        put(px, k, 8, pick(IRON, 3)), put(px, k, 11, pick(IRON, 5))
    rect(px, 7, 9, 8, 10, HOLE)  # the open bottom, centered x8 z6
    put(px, 8, 10, rgb(0x2a0c06))
    T['spout_hole'] = im


def tex_fire_bricks(V, T):
    im = bricks('fire_bricks', FIRE, FIRE_MORTAR, nz=V.noise('sandstone'))
    px = im.load()
    rng = random.Random('fire_speckle')
    for _ in range(10):
        x, y = rng.randrange(16), rng.randrange(16)
        if y % 4 != 3:
            px[x, y] = rgb(0x6a381a) if rng.random() < 0.7 else rgb(0xf6d2a0)
    T['fire_bricks'] = im
    drain = im.copy()
    px = drain.load()
    rect(px, 4, 2, 11, 7, pick(IRON, 4))  # drain grate frame
    for k in range(4, 12):
        px[k, 2] = pick(IRON, 6)
        px[k, 7] = pick(IRON, 3)
    for y in range(3, 7):
        for x in range(5, 11):
            if x not in (6, 9):
                px[x, y] = rgb(0x120a06) if y < 6 else rgb(0x3c1408)
            else:
                px[x, y] = pick(IRON, 5)
    for (x, y) in ((4, 2), (11, 2), (4, 7), (11, 7)):
        put(px, x, y, pick(IRON, 8))
    rect(px, 6, 9, 9, 12, pick(IRON, 4))  # flange for the tap (x7..9, y4..6)
    for (x, y) in ((6, 9), (9, 9), (6, 12), (9, 12)):
        put(px, x, y, pick(IRON, 8))
    T['drain_front'] = drain


def build_textures(V):
    T = {}
    for fn in (tex_forge, tex_bellows, tex_crucible, tex_casting, tex_anvil, tex_trough, tex_carving, tex_assembly,
               tex_whetstone, tex_runesmith, tex_spout, tex_fire_bricks):
        fn(V, T)
    return T


# ------------------------------------------------------------------------------------------------------ models

FACES = ('north', 'east', 'south', 'west', 'up', 'down')


def num(v):
    v = round(float(v), 4)
    return int(v) if v == int(v) else v


def fit(a, b):
    if a < 0:
        a, b = 0, b - a
    if b > 16:
        a, b = a - (b - 16), 16
    return a, b


def auto_uv(face, f, t):
    """Vanilla-style UVs: the texture is projected by world position, so neighbouring elements line up."""
    x1, y1, z1 = f
    x2, y2, z2 = t
    u1, v1, u2, v2 = {
        'north': (16 - x2, 16 - y2, 16 - x1, 16 - y1),
        'south': (x1, 16 - y2, x2, 16 - y1),
        'west': (z1, 16 - y2, z2, 16 - y1),
        'east': (16 - z2, 16 - y2, 16 - z1, 16 - y1),
        'up': (x1, z1, x2, z2),
        'down': (x1, 16 - z2, x2, 16 - z1),
    }[face]
    u1, u2 = fit(u1, u2)
    v1, v2 = fit(v1, v2)
    return [u1, v1, u2, v2]


def on_boundary(face, f, t):
    return {'north': f[2] == 0, 'south': t[2] == 16, 'west': f[0] == 0, 'east': t[0] == 16,
            'up': t[1] == 16, 'down': f[1] == 0}[face]


def el(f, t, faces, rot=None, emit=0, name=None):
    e = {}
    if name:
        e['name'] = name
    e['from'] = [num(v) for v in f]
    e['to'] = [num(v) for v in t]
    if rot:
        e['rotation'] = {'angle': rot[0], 'axis': rot[1], 'origin': [num(v) for v in rot[2]]}
    if emit:
        e['light_emission'] = emit
    fj = {}
    for face in FACES:
        if face not in faces:
            continue
        spec = faces[face]
        tex, uv = (spec if isinstance(spec, tuple) else (spec, None))
        d = {'uv': [num(v) for v in (uv or auto_uv(face, f, t))], 'texture': '#' + tex}
        if not rot and on_boundary(face, f, t):
            d['cullface'] = face
        fj[face] = d
    e['faces'] = fj
    return e


def box(f, t, tex, skip='', rot=None, emit=0, name=None, **over):
    """An element with `tex` on every face except those whose initial is in `skip`; keyword args override faces."""
    return el(f, t, {face: over.get(face, tex) for face in FACES if face[0] not in skip}, rot, emit, name)


def m_forge(variant):
    lit = variant == 'lit'
    S, F = 'forge_side', 'forge_front'
    inner = 'forge_inner_lit' if lit else 'forge_inner'
    coal = {'': 'coal_ash', 'cold': 'coal_cold', 'lit': 'coal_lit'}[variant]
    return [
        box([0, 0, 0], [16, 3, 16], S, name='base', north=F, up='forge_top', down='forge_bottom'),
        box([0, 3, 0], [3, 10, 16], S, 'ud', name='jamb_east', north=F, east=inner),
        box([13, 3, 0], [16, 10, 16], S, 'ud', name='jamb_west', north=F, west=inner),
        box([3, 3, 11], [13, 10, 16], S, 'udew', name='firebox_back', north=inner),
        box([0, 10, 0], [16, 16, 16], S, name='top', north=F, up='forge_top_lit' if lit else 'forge_top',
            down='forge_ceiling_lit' if lit else 'forge_ceiling'),
        el([3, 3, 1], [13, 5, 11], {'up': coal, 'north': coal}, emit=15 if lit else 0, name='coal_bed'),
    ]


def m_bellows(pressed):
    ty = 5 if pressed else 9  # top surface of the top board
    els = [box([2, 0, 3], [14, 1, 16], 'bellows_edge', name='bottom_board', up='bellows_board',
               down='bellows_board')]
    IN = (4, 5, 12, 14.5)
    if pressed:
        OUT = (2.25, 3.25, 13.75, 15.75)
        layers = [(1, 1.5, IN), (1.5, 2.25, OUT), (2.25, 2.75, IN), (2.75, 3.5, OUT), (3.5, 4, IN)]
    else:
        OUT = (2.5, 3.5, 13.5, 15.5)
        layers = [(1, 2, IN), (2, 3, OUT), (3, 4, IN), (4, 5, OUT), (5, 6, IN), (6, 7, OUT), (7, 8, IN)]
    for y0, y1, (x0, z0, x1, z1) in layers:
        if (x0, z0, x1, z1) == IN:
            els.append(box([x0, y0, z0], [x1, y1, z1], 'bellows_fold', 'ud', name='fold'))
        else:
            els.append(box([x0, y0, z0], [x1, y1, z1], 'bellows_leather', name='pleat'))
    els += [
        box([2, ty - 1, 3], [14, ty, 16], 'bellows_edge', name='top_board', up='bellows_board',
            down='bellows_board'),
        box([7, 3, 0], [9, 5, 3], 'bellows_nozzle', 's', name='nozzle'),
        box([6.5, 2.5, 2], [9.5, 5.5, 3], 'brass', 's', name='nozzle_collar'),
        box([7, ty, 12.5], [9, ty + 3.5, 14.5], 'bellows_edge', 'd', rot=(22.5, 'x', [8, ty, 13.5]),
            name='handle_post'),
        box([5.5, ty + 2.5, 12.75], [10.5, ty + 4, 14.25], 'handle_wood', rot=(22.5, 'x', [8, ty, 13.5]),
            name='handle_grip'),
    ]
    return els


def m_crucible():
    S, I, R = 'crucible_side', 'crucible_inner', 'crucible_rim'
    return [
        box([2, 0, 2], [14, 2, 14], S, name='base', up=I, down='crucible_bottom'),
        box([2, 2, 2], [7, 12, 4], S, 'd', name='wall_n_west', south=I, east=I, up=R),
        box([9, 2, 2], [14, 12, 4], S, 'd', name='wall_n_east', south=I, west=I, up=R),
        box([7, 2, 2], [9, 11, 4], S, 'dew', name='pour_notch', south=I, up=I),
        box([2, 2, 12], [14, 12, 14], S, 'd', name='wall_s', north=I, up=R),
        box([2, 2, 4], [4, 12, 12], S, 'dns', name='wall_w', east=I, up=R),
        box([12, 2, 4], [14, 12, 12], S, 'dns', name='wall_e', west=I, up=R),
        box([7, 10, 1], [9, 11, 2], S, 's', name='pour_lip', up=I),
    ]


def m_casting():
    els = [box([0, 6, 0], [16, 8, 16], 'casting_side', name='top', north='casting_front', up='casting_top',
               down='casting_under')]
    for x, z in ((1, 1), (12, 1), (1, 12), (12, 12)):
        els.append(box([x, 0, z], [x + 3, 6, z + 3], 'casting_leg', 'u', name='leg', down='casting_under'))
    return els


def m_anvil():
    A, T = 'anvil_side', 'anvil_top'
    return [
        box([2, 0, 3], [14, 3, 13], A, name='foot', up='anvil_base_top', down='anvil_under'),
        box([5, 3, 5], [11, 8, 11], A, 'ud', name='waist'),
        box([2, 8, 4], [14, 12, 12], A, name='face', up=T, down='anvil_under'),
        box([1, 9, 6], [2, 11, 10], A, 'e', name='horn', up=T, down='anvil_under'),
        box([0, 9.5, 7], [1, 10.5, 9], A, 'e', name='horn_tip', up=T, down='anvil_under'),
    ]


def m_trough():
    L, S, I, R, U = 'trough_long', 'trough_short', 'trough_inner', 'trough_rim', 'trough_feet'
    els = [
        box([1, 1, 3], [15, 8, 4], L, name='wall_n', south=I, east=S, west=S, up=R, down=U),
        box([1, 1, 12], [15, 8, 13], L, name='wall_s', north=I, east=S, west=S, up=R, down=U),
        box([1, 1, 4], [2, 8, 12], S, 'ns', name='wall_w', east=I, up=R, down=U),
        box([14, 1, 4], [15, 8, 12], S, 'ns', name='wall_e', west=I, up=R, down=U),
        el([2, 1, 4], [14, 1, 12], {'up': 'trough_floor', 'down': U}, name='floor'),
    ]
    for x, z in ((1, 3), (13, 3), (1, 11), (13, 11)):
        els.append(box([x, 0, z], [x + 2, 1, z + 2], U, 'u', name='foot'))
    return els


def m_carving():
    return [
        box([3, 0, 4], [13, 10, 12], 'carving_log', 'u', name='base', down='carving_log_end'),
        box([1, 10, 2], [15, 12, 14], 'carving_side', name='board', east='carving_side_east', up='carving_top',
            down='carving_under'),
    ]


def m_assembly():
    els = [box([0, 12, 0], [16, 15, 16], 'assembly_edge', name='top', up='assembly_top', down='assembly_under')]
    for x, z in ((1, 1), (12, 1), (1, 12), (12, 12)):
        els.append(box([x, 0, z], [x + 3, 12, z + 3], 'assembly_leg', 'u', name='leg'))
    els.append(box([2, 3, 2], [14, 4, 14], 'assembly_edge', name='shelf', up='assembly_shelf',
                   down='assembly_under'))
    els += [  # aprons under the top, between the legs
        box([4, 10, 2], [12, 12, 3], 'assembly_leg', 'ud', name='apron'),
        box([4, 10, 13], [12, 12, 14], 'assembly_leg', 'ud', name='apron'),
        box([2, 10, 4], [3, 12, 12], 'assembly_leg', 'udns', name='apron'),
        box([13, 10, 4], [14, 12, 12], 'assembly_leg', 'udns', name='apron'),
    ]
    return els


def m_whetstone():
    W = 'whet_wood'
    return [
        box([1, 0, 4], [15, 2, 12], 'whet_base', name='base', up='whet_base_top', down='whet_base_top'),
        box([1, 0, 6], [3, 13, 10], W, 'd', name='upright_west', east='whet_upright_inner', up='whet_wood_end'),
        box([13, 0, 6], [15, 13, 10], W, 'd', name='upright_east', west='whet_upright_inner', up='whet_wood_end'),
        box([4, 2, 5], [12, 3, 11], 'whet_tray_side', 'd', name='water_tray', up='whet_tray'),
        box([15, 6.5, 6.5], [16, 9.5, 9.5], 'iron', 'w', name='crank_hub'),
        box([15, 3, 7.25], [16, 7, 8.75], 'iron', 'w', rot=(45, 'x', [15.5, 8, 8]), name='crank_arm'),
        box([15, 1.5, 6.75], [16, 3.5, 9.25], 'handle_wood', 'w', rot=(45, 'x', [15.5, 8, 8]), name='crank_grip'),
    ]


def m_wheel():
    face = {'east': 'whet_face', 'west': 'whet_face'}
    return [
        box([5, 2.5, 5], [11, 13.5, 11], 'whet_rim', name='disc', **face),
        box([5, 5, 2.5], [11, 11, 13.5], 'whet_rim', name='disc', **face),
        box([5, 4, 4], [11, 12, 12], 'whet_rim', name='disc', **face),
        box([3, 7, 7], [13, 9, 9], 'iron', name='axle'),
    ]


def m_runesmith(lit):
    els = [
        box([3, 0, 3], [13, 11, 13], 'rune_pillar', 'u', name='pillar', down='rune_under'),
        box([0, 11, 0], [16, 14, 16], 'rune_side', name='top', up='rune_top', down='rune_under'),
    ]
    if lit:  # same geometry again, emissive, with only the grooves opaque (like vanilla's *_emissive models)
        els.append(el([0, 11, 0], [16, 14, 16], {'up': 'rune_top_glow'}, emit=15, name='glow_top'))
        els.append(el([3, 0, 3], [13, 11, 13], {f: 'rune_pillar_glow' for f in ('north', 'east', 'south', 'west')},
                      emit=15, name='glow_pillar'))
    return els


def m_spout():
    I, L = 'spout_iron', 'spout_lining'
    return [
        box([5, 6, 8], [11, 7, 16], I, name='channel_floor', up='spout_floor'),
        box([5, 7, 8], [6, 10, 16], I, 'd', name='channel_wall_w', east=L),
        box([10, 7, 8], [11, 10, 16], I, 'd', name='channel_wall_e', west=L),
        box([6.5, 4, 4], [9.5, 8, 8], 'spout_nozzle', name='nozzle', up=I, down='spout_hole'),
    ]


def m_fire_bricks():
    return [box([0, 0, 0], [16, 16, 16], 'fire_bricks')]


def m_drain():
    return [
        box([0, 0, 0], [16, 16, 16], 'fire_bricks', north='drain_front', name='block'),
        box([7, 4, -2.5], [9, 6, 0], 'iron', 's', name='tap_pipe'),
        box([7, 2.5, -2.5], [9, 4, -1], 'iron', 'u', name='tap_nose', down='spout_hole'),
        box([7.5, 6, -2], [8.5, 7, -1], 'iron', 'd', name='tap_stem'),
        box([6, 7, -2], [10, 7.75, -1], 'brass', name='tap_handle'),
    ]


def model(elements, particle, ao):
    used = []
    for e in elements:
        for f in e['faces'].values():
            if f['texture'][1:] not in used:
                used.append(f['texture'][1:])
    m = {}
    if not ao:
        m['ambientocclusion'] = False
    m['gui_light'] = 'side'
    m['textures'] = dict([('particle', NS + particle)] + [(t, NS + t) for t in sorted(used)])
    m['elements'] = elements
    m['display'] = DISPLAY
    return m


def build_models():
    M = {}
    for v in ('', 'cold', 'lit'):
        M['forge_hearth' + ('_' + v if v else '')] = model(m_forge(v), 'forge_side', True)
    M['bellows'] = model(m_bellows(False), 'bellows_board', False)
    M['bellows_pressed'] = model(m_bellows(True), 'bellows_board', False)
    M['crucible'] = model(m_crucible(), 'crucible_side', False)
    M['casting_bench'] = model(m_casting(), 'casting_side', False)
    M['smithing_anvil'] = model(m_anvil(), 'anvil_side', False)
    M['quench_trough'] = model(m_trough(), 'trough_long', False)
    M['carving_bench'] = model(m_carving(), 'carving_top', False)
    M['assembly_bench'] = model(m_assembly(), 'assembly_top', False)
    M['whetstone_wheel'] = model(m_whetstone(), 'whet_wood', False)
    M['whetstone_wheel_spin'] = model(m_wheel(), 'whet_face', False)
    M['runesmith_table'] = model(m_runesmith(False), 'rune_side', False)
    M['runesmith_table_lit'] = model(m_runesmith(True), 'rune_side', False)
    M['smithing_spout'] = model(m_spout(), 'spout_iron', False)
    M['fire_bricks'] = model(m_fire_bricks(), 'fire_bricks', True)
    M['great_crucible_drain'] = model(m_drain(), 'fire_bricks', True)
    return M


def rotated_bounds(e):
    """Axis-aligned bounds of an element after its rotation (for the sanity check)."""
    f, t = e['from'], e['to']
    pts = [[x, y, z] for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])]
    if 'rotation' in e:
        r = e['rotation']
        a = math.radians(r['angle'])
        o = r['origin']
        ax = 'xyz'.index(r['axis'])
        i, j = [k for k in range(3) if k != ax]
        if ax == 1:
            i, j = j, i  # right-handed order for y: (z, x)
        for p in pts:
            di, dj = p[i] - o[i], p[j] - o[j]
            p[i], p[j] = o[i] + di * math.cos(a) - dj * math.sin(a), o[j] + di * math.sin(a) + dj * math.cos(a)
    return [min(p[k] for p in pts) for k in range(3)], [max(p[k] for p in pts) for k in range(3)]


def check(M, T):
    for name, m in M.items():
        for k, ref in m['textures'].items():
            assert ref.startswith(NS) and ref[len(NS):] in T, (name, ref)
        for e in m['elements']:
            assert all(-16 <= v <= 32 for v in e['from'] + e['to']), (name, e)
            assert all(e['from'][k] <= e['to'][k] for k in range(3)), (name, e)
            if 'rotation' in e:
                assert e['rotation']['angle'] in (-45, -22.5, 0, 22.5, 45), (name, e)
            for face, f in e['faces'].items():
                assert all(0 <= u <= 16 for u in f['uv']), (name, face, f)
            lo, hi = rotated_bounds(e)
            if name != 'great_crucible_drain' and (min(lo) < -1e-6 or max(hi) > 16 + 1e-6):
                raise AssertionError('%s: element %s leaves the block (%s..%s)' % (name, e.get('name'), lo, hi))


def dump_model(path, m):
    parts = []
    for k, v in m.items():
        if k == 'elements':
            body = ',\n'.join('  ' + json.dumps(e, separators=(', ', ': ')) for e in v)
            parts.append(' "elements": [\n%s\n ]' % body)
        elif k == 'display':
            body = ',\n'.join('  %s: %s' % (json.dumps(dk), json.dumps(dv, separators=(', ', ': ')))
                              for dk, dv in v.items())
            parts.append(' "display": {\n%s\n }' % body)
        else:
            parts.append(' %s: %s' % (json.dumps(k), json.dumps(v, separators=(', ', ': '))))
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('{\n' + ',\n'.join(parts) + '\n}\n')


def write_sheet(T, path, scale=6, cols=8):
    names = sorted(T)
    cell_w, cell_h = 16 * scale + 16, 16 * scale + 22
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * cell_w + 8, rows * cell_h + 8), (34, 32, 36, 255))
    draw = ImageDraw.Draw(sheet)
    for n, name in enumerate(names):
        cx, cy = 8 + (n % cols) * cell_w, 8 + (n // cols) * cell_h
        checker = Image.new('RGBA', (16, 16), (70, 70, 74, 255))
        cp = checker.load()
        for y in range(16):
            for x in range(16):
                if (x + y) % 2:
                    cp[x, y] = (90, 90, 96, 255)
        checker.alpha_composite(T[name])
        sheet.paste(checker.resize((16 * scale, 16 * scale), Image.NEAREST), (cx, cy))
        draw.text((cx, cy + 16 * scale + 4), name[:18], fill=(220, 216, 206, 255))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sheet.save(path)


def main():
    V = Vanilla(JAR_PATH)
    T = build_textures(V)
    M = build_models()
    check(M, T)
    for d in (MODEL_DIR, TEX_DIR):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    for name in sorted(T):
        T[name].save(os.path.join(TEX_DIR, name + '.png'))
    for name in sorted(M):
        dump_model(os.path.join(MODEL_DIR, name + '.json'), M[name])
    write_sheet(T, SHEET)
    print('smithing blocks: %d models, %d textures; sheet %s' % (len(M), len(T), os.path.relpath(SHEET, MOD)))


if __name__ == '__main__':
    main()
