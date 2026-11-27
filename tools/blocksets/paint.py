"""Pixel painter for the snow, ice and calcite block sets (16x16, tileable).

Every texture is drawn from a five-step ramp per material (0 = joint/mortar, 1 = shade, 2 = base, 3 = light,
4 = highlight) with the mod's block style: flat faces, a one-pixel bevel (light top/left, shade bottom/right),
a few hand-placed surface marks instead of noise. Positions are deterministic, so a rerun gives the same PNGs.
"""
from PIL import Image

N = 16

RAMPS = {
    # snow: vanilla snow is (240,253,253)..(255,255,255); joints are a cool pale blue-grey
    'snow': [(202, 220, 233), (230, 240, 246), (243, 250, 252), (250, 253, 254), (255, 255, 255)],
    # packed ice: vanilla (124,165,244)..(200,220,255)
    'ice': [(82, 118, 201), (120, 159, 236), (139, 178, 248), (161, 196, 255), (201, 222, 255)],
    # blue ice: vanilla (107,157,251)..(169,200,255), deeper joints
    'blue_ice': [(54, 92, 192), (92, 140, 240), (109, 161, 252), (138, 184, 255), (182, 210, 255)],
    # calcite: vanilla (179,179,179)..(255,255,255), slightly warm grey-white
    'calcite': [(170, 170, 166), (208, 209, 204), (228, 228, 223), (240, 240, 235), (251, 251, 248)],
}


class Tex:
    def __init__(self, ramp):
        self.r = RAMPS[ramp]
        self.px = [[self.r[2]] * N for _ in range(N)]

    def set(self, x, y, c):
        self.px[y % N][x % N] = self.r[c] if isinstance(c, int) else c

    def get(self, x, y):
        return self.px[y % N][x % N]

    def fill(self, x0, y0, w, h, c):
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                self.set(x, y, c)

    def hline(self, x0, y, w, c):
        self.fill(x0, y, w, 1, c)

    def vline(self, x, y0, h, c):
        self.fill(x, y0, 1, h, c)

    def mix(self, x, y, c, t):
        a = self.get(x, y)
        b = self.r[c] if isinstance(c, int) else c
        self.set(x, y, tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)))

    def image(self):
        im = Image.new('RGBA', (N, N))
        for y in range(N):
            for x in range(N):
                im.putpixel((x, y), self.px[y][x] + (255,))
        return im


def face(t, x0, y0, w, h, base=2, light=3, shade=1, soft=False, inset=False, tint=None):
    """A brick / tile face with a one-pixel bevel. soft = rounded corners (packed snow), inset = recessed panel
    (shade on top/left instead), tint = (ramp step, amount) to lift or sink the whole face a little."""
    t.fill(x0, y0, w, h, base)
    if tint:
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                t.mix(x, y, tint[0], tint[1])
    lo, hi = (light, shade) if not inset else (shade, light)
    t.hline(x0, y0, w, lo)
    t.vline(x0, y0, h, lo)
    t.hline(x0, y0 + h - 1, w, hi)
    t.vline(x0 + w - 1, y0, h, hi)
    if soft:
        t.set(x0, y0, t.get(x0 + 1, y0 + 1))
        t.mix(x0 + w - 1, y0, 0, 0.35)
        t.mix(x0, y0 + h - 1, 0, 0.35)


def glint(t, x, y, n, c=4, step=1):
    """A diagonal ice glint going up-right from (x, y), n pixels long."""
    for i in range(n):
        t.set(x + i, y - i * step, c)


def brick_rows(t, rows, base=2, light=3, shade=1, soft=False, joint=0, tints=()):
    """rows: [(y0, height incl. the joint row below, [joint x positions])]; tints cycle over the bricks in order
    (None or (ramp step, amount)) so neighbouring bricks differ slightly in tone. Returns the brick rectangles."""
    out = []
    n = 0
    for y0, h, joints in rows:
        t.hline(0, y0 + h - 1, N, joint)
        js = sorted(j % N for j in joints)
        for k, j in enumerate(js):
            nxt = js[(k + 1) % len(js)] + (N if k + 1 == len(js) else 0)
            w = nxt - j - 1
            face(t, j + 1, y0, w, h - 1, base, light, shade, soft, tint=tints[n % len(tints)] if tints else None)
            t.vline(j, y0, h - 1, joint)
            out.append((j + 1, y0, w, h - 1))
            n += 1
    return out


def save(t, path):
    t.image().save(path)
