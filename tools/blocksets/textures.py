"""The block-set textures, one function per texture. Each returns a paint.Tex.

Families share layouts so a set reads as one: polished = one smooth slab with a bevelled rim, bricks = a running
bond (three soft courses of cut snow, four crisp courses of ice, two courses of calcite ashlar), tiles = four square
tiles, chiseled = a framed panel with a carved motif (a white snowflake on ice, an engraved star on snow, a quatrefoil
on calcite). Materials add their own surface: faint wind streaks on snow, diagonal glints on ice, soft grey mottling
on calcite. Neighbouring bricks and tiles differ a little in tone instead of carrying noise.
"""
from paint import Tex, face, glint, brick_rows, N

LIFT, SINK = (4, 0.3), (1, 0.3)
TINTS = [None, LIFT, SINK, None, (4, 0.15), (1, 0.15)]


# ------------------------------------------------------------------------------------------------- surface marks
def streaks(t, cells, amount=0.4):
    """Faint diagonal wind streaks for snow (shade with a highlight above), like vanilla snow but sparser."""
    for x, y, n in cells:
        for i in range(n):
            t.mix(x + i, y - i, 1, amount)
            t.mix(x + i, y - i - 1, 4, 0.8)


def mottle(t, cells, amount=0.5):
    """Calcite's soft grey veining: small clusters of shade."""
    for x, y, shape in cells:
        for dx, dy in shape:
            t.mix(x + dx, y + dy, 1, amount)


def clouds(t, cells, rect=None):
    """Calcite's soft grey clouding: low-contrast patches (a darker core, a fainter rim), never single glyphs."""
    for cx, cy in cells:
        # a rounded 4x4 patch: a 2x2 core, a fainter rim without the corners
        for dx in range(-1, 3):
            for dy in range(-1, 3):
                x, y = cx + dx, cy + dy
                if rect and not (rect[0] < x < rect[0] + rect[2] - 1 and rect[1] < y < rect[1] + rect[3] - 1):
                    continue
                core = dx in (0, 1) and dy in (0, 1)
                corner = dx in (-1, 2) and dy in (-1, 2)
                if core:
                    t.mix(x, y, 1, 0.38)
                elif not corner:
                    t.mix(x, y, 1, 0.16)


BLOB_A = ((0, 0), (1, 0), (1, 1))
BLOB_B = ((0, 0), (0, 1), (1, 1), (2, 1))
BLOB_C = ((0, 0), (1, 0))
BLOB_D = ((0, 0), (1, 1), (2, 1))


def ice_glints(t, cells):
    """A short glint (highlight) with a softer pixel under it."""
    for x, y, n in cells:
        glint(t, x, y, n)
        t.set(x + 1, y, 3)


def ice_sheen(t, rects):
    """A faint wide diagonal band inside each face, so ice faces aren't flat."""
    for x0, y0, w, h in rects:
        for y in range(y0 + 1, y0 + h - 1):
            for x in range(x0 + 1, x0 + w - 1):
                d = (x - x0) + (y - y0) - (w + h) // 2
                if -1 <= d <= 0:
                    t.mix(x, y, 3, 0.45)


# ------------------------------------------------------------------------------------------------- polished
def polished(mat):
    t = Tex(mat)
    face(t, 0, 0, N, N)
    if mat == 'snow':
        streaks(t, [(3, 12, 5), (8, 7, 4), (10, 13, 3)])
    elif mat in ('ice', 'blue_ice'):
        # a soft wide sheen and two long parallel streaks: polished ice reads as glossy
        for y in range(1, N - 1):
            for x in range(1, N - 1):
                if 13 <= x + y <= 16:
                    t.mix(x, y, 3, 0.35)
        glint(t, 2, 12, 9)
        glint(t, 3, 12, 8, 3)
        glint(t, 7, 13, 6)
        glint(t, 8, 13, 5, 3)
        glint(t, 11, 5, 2)
    else:
        # polished calcite: a gentle light falloff from the top-left and a few soft clouds
        for y in range(1, N - 1):
            for x in range(1, N - 1):
                if x + y < 12:
                    t.mix(x, y, 3, 0.5)
        clouds(t, [(5, 5), (11, 11), (4, 12), (12, 4)], (0, 0, N, N))
    return t


# ------------------------------------------------------------------------------------------------- bricks
def bricks(mat):
    t = Tex(mat)
    if mat == 'snow':
        # cut snow blocks: three courses, soft corners, staggered by thirds
        brick_rows(t, [(0, 5, [7, 15]), (5, 5, [3, 11]), (10, 6, [5, 13])], soft=True,
                   tints=[None, (4, 0.5), (1, 0.25), (4, 0.3), None, (1, 0.2)])
        streaks(t, [(2, 3, 2), (9, 8, 2), (8, 13, 3)], 0.3)
    elif mat in ('ice', 'blue_ice'):
        # crisp four-course bond, a sheen band and a glint in most bricks
        rects = brick_rows(t, [(0, 4, [7, 15]), (4, 4, [3, 11]), (8, 4, [7, 15]), (12, 4, [3, 11])],
                           tints=[None, LIFT, SINK, (4, 0.15), LIFT, None, (1, 0.2), None])
        ice_glints(t, [(2, 2, 2), (11, 2, 2), (6, 6, 2), (13, 6, 2), (1, 10, 2), (9, 10, 3), (5, 14, 2), (13, 14, 2)])
    else:
        # calcite ashlar: one long block over two short ones, the long one with an engraved panel
        brick_rows(t, [(0, 8, [15]), (8, 8, [3, 11])], tints=[None, LIFT, (1, 0.2)])
        face(t, 3, 2, 9, 4, 2, 3, 1, inset=True)
        t.fill(4, 3, 7, 2, 2)
        clouds(t, [(13, 4), (1, 4)], (12, 0, 20, 8))
        clouds(t, [(7, 12)], (4, 8, 7, 7))
        clouds(t, [(14, 11)], (12, 8, 7, 7))
    return t


def cracked_bricks(mat):
    t = bricks(mat)
    # two hairline cracks that run from a joint into the face, thinning out (darker near the joint)
    for path in ([(9, 6), (9, 5), (10, 4), (10, 3), (11, 2)], [(6, 14), (6, 13), (7, 12), (8, 11), (8, 10)]):
        for i, (x, y) in enumerate(path):
            t.mix(x, y, 0, 0.75 - i * 0.1)
            t.mix(x + 1, y, 4, 0.5)
    # a chipped corner on one block
    for x, y in ((12, 8), (13, 8), (12, 9)):
        t.set(x, y, 0)
    t.mix(14, 8, 0, 0.5)
    t.mix(12, 10, 0, 0.5)
    return t


# ------------------------------------------------------------------------------------------------- tiles
def tiles(mat):
    t = Tex(mat)
    t.fill(0, 0, N, N, 0)
    tones = [None, (4, 0.35), (1, 0.25), None] if mat != 'ice' and mat != 'blue_ice' else [None, LIFT, SINK, (4, 0.15)]
    rects = []
    for i, (tx, ty) in enumerate(((0, 0), (8, 0), (0, 8), (8, 8))):
        face(t, tx, ty, 7, 7, soft=(mat == 'snow'), tint=tones[[0, 2, 1, 3][i]])
        rects.append((tx, ty, 7, 7))
    if mat == 'snow':
        streaks(t, [(2, 4, 2), (10, 5, 2), (3, 12, 3), (10, 13, 2)], 0.3)
    elif mat in ('ice', 'blue_ice'):
        ice_sheen(t, rects)
        ice_glints(t, [(1, 5, 3), (9, 5, 3), (1, 13, 3), (9, 13, 3)])
    else:
        clouds(t, [(3, 3)], rects[0])
        clouds(t, [(12, 4)], rects[1])
        clouds(t, [(11, 12)], rects[3])
    return t


# ------------------------------------------------------------------------------------------------- chiseled
SNOWFLAKE = [
    ".....X.....",
    "...X.X.X...",
    "....XXX....",
    ".X...X...X.",
    "..X.XXX.X..",
    "XXXXX.XXXXX",
    "..X.XXX.X..",
    ".X...X...X.",
    "....XXX....",
    "...X.X.X...",
    ".....X.....",
]
STAR = [
    "....X....",
    "...X.X...",
    "...X.X...",
    ".XX...XX.",
    "X.......X",
    ".XX...XX.",
    "...X.X...",
    "...X.X...",
    "....X....",
]
MEDALLION = [
    "..XXXX..",
    ".X....X.",
    "X......X",
    "X......X",
    "X......X",
    "X......X",
    ".X....X.",
    "..XXXX..",
]
QUATREFOIL = [
    "....XX....",
    "...X..X...",
    "...X..X...",
    ".XX.XX.XX.",
    "X..X..X..X",
    "X..X..X..X",
    ".XX.XX.XX.",
    "...X..X...",
    "...X..X...",
    "....XX....",
]


def pattern(t, rows, x0, y0, c, rim=None, rim_dir=(1, 1)):
    """Stamps an ASCII pattern; rim draws a one-pixel offset copy first (shadow or carved highlight)."""
    pts = {(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch == 'X'}
    if rim is not None:
        for x, y in pts:
            q = (x + rim_dir[0], y + rim_dir[1])
            if q not in pts:
                t.set(x0 + q[0], y0 + q[1], rim)
    for x, y in pts:
        t.set(x0 + x, y0 + y, c)


def chiseled(mat):
    t = Tex(mat)
    face(t, 0, 0, N, N)
    if mat in ('ice', 'blue_ice'):
        # a deep panel with a white snowflake
        face(t, 1, 1, 14, 14, 1, 0, 3, inset=True)
        t.fill(2, 2, 12, 12, 1)
        pattern(t, SNOWFLAKE, 2, 2, 4, rim=0)
    elif mat == 'snow':
        # a carved frame with an engraved four-point star (shade line, highlight below-right)
        face(t, 2, 2, 12, 12, 2, 4, 1, inset=True)
        face(t, 3, 3, 10, 10, 3, 4, 2)
        pattern(t, STAR, 3, 3, 1, rim=4)
        t.set(7, 7, 4)
    else:
        # calcite: a frame around a carved medallion (a ring with a raised boss)
        face(t, 2, 2, 12, 12, 2, 4, 1, inset=True)
        t.fill(3, 3, 10, 10, 3)
        pattern(t, MEDALLION, 4, 4, 1, rim=4)
        t.fill(7, 7, 2, 2, 4)
        t.set(8, 8, 2)
        clouds(t, [(5, 11)], (2, 2, 12, 12))
    return t


TEXTURES = {}
for _m in ('snow', 'ice', 'blue_ice', 'calcite'):
    TEXTURES['polished_' + _m] = (polished, _m)
    TEXTURES[_m + '_bricks'] = (bricks, _m)
    TEXTURES[_m + '_tiles'] = (tiles, _m)
    TEXTURES['chiseled_' + _m] = (chiseled, _m)
TEXTURES['cracked_calcite_bricks'] = (cracked_bricks, 'calcite')


def make(name):
    fn, mat = TEXTURES[name]
    return fn(mat)
