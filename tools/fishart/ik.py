"""(Copy of arcana_items/items3d/ik.py for the in-repo fish models: output goes to mod/content/pack, the Blockbench
sources to mod/build/fishart/bbmodel; otherwise unchanged.)

items3d kit: turns Arcana's 2D item sprites into real 3D item models (Java 26.x block/item model JSON + texture
atlas + a Blockbench .bbmodel source for every item).

Two ways to build an item:
- relief(name, sprite): the item is modelled from its sprite as a rounded solid: every opaque pixel gets a thickness
  from its distance to the silhouette edge (thin fins, tails, lines and leaves; a fat belly), the pixel art stays on the
  front and back, and every side wall is painted from the pixel it belongs to. Animated sprites stay animated.
- Hand(name): hand-built from boxes with painted faces (bowls, bottles, cups, pies, rods...), see the helpers below.

Model space: the item sits in the XY plane like its sprite (x right, y up, front = +z = south, which is the side the
GUI shows), 1 unit = 1 sprite pixel, centred on (8, 8, 8). That keeps the vanilla held/ground/frame poses working.
Output (build/):
  models/arcana/<pkg>/<name>.json        replaces the old item/generated model in the pack
  textures/arcana/<pkg>/3d/<name>.png    the model's atlas (+ .mcmeta when animated)
  bbmodel/<pkg>/<name>.bbmodel           Blockbench source (open it in Blockbench to edit by hand)
"""
import base64
import io
import json
import math
import os
import random
import shutil
import uuid as _uuid

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(os.path.dirname(HERE))
PACK = os.path.join(MOD, 'content', 'pack')
BUILD = PACK                                   # models/ and textures/ land straight in the in-repo content pack
BBMODEL = os.path.join(MOD, 'build', 'fishart', 'bbmodel')
BACKUP = os.path.join(MOD, 'build', 'fishart', 'backup_2d')


# ------------------------------------------------------------------------------------------------ colour helpers

def hx(s):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def shade(c, f):
    return _shade(c, f)


def _shade(c, f):
    return tuple(max(0, min(255, int(round(c[i] * f)))) for i in range(3)) + (c[3] if len(c) > 3 else 255,)


def ramp(base, n=5, lo=0.62, hi=1.22):
    """dark..light ramp around a base colour (index n//2 = base)."""
    c = hx(base) if isinstance(base, str) else base
    out = []
    for i in range(n):
        t = i / (n - 1)
        f = lo + (hi - lo) * t
        out.append(shade(c, f))
    out[n // 2] = c
    return out


# ------------------------------------------------------------------------------------------------ display presets
# Rotation/translation/scale per context, vanilla semantics (translation in pixels, rotation Euler XYZ degrees).

GEN = {  # vanilla item/generated (flat items: fish, food sprites)
    'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55, 0.55, 0.55]},
    'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55, 0.55, 0.55]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
    'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
    'gui': {'rotation': [8, -24, 0], 'translation': [0, 0, 0], 'scale': [0.92, 0.92, 0.92]},
}
HANDHELD = dict(GEN, **{  # vanilla item/handheld (sticks, joints, shards held like a tool)
    'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'thirdperson_lefthand': {'rotation': [0, 90, -55], 'translation': [0, 4, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
})
ROD = dict(GEN, **{  # vanilla item/handheld_rod
    'thirdperson_righthand': {'rotation': [0, 90, 55], 'translation': [0, 4, 2.5], 'scale': [0.85, 0.85, 0.85]},
    'thirdperson_lefthand': {'rotation': [0, -90, -55], 'translation': [0, 4, 2.5], 'scale': [0.85, 0.85, 0.85]},
    'firstperson_righthand': {'rotation': [0, 90, 25], 'translation': [0, 1.6, 0.8], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, -90, -25], 'translation': [0, 1.6, 0.8], 'scale': [0.68, 0.68, 0.68]},
})
OBJECT = {  # small upright objects (bowls, cups, bottles, cheese): modelled standing on y=0..., centred on x/z 8
    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 1.5], 'scale': [0.5, 0.5, 0.5]},
    'thirdperson_lefthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 1.5], 'scale': [0.5, 0.5, 0.5]},
    'firstperson_righthand': {'rotation': [28, 45, 0], 'translation': [0, -0.5, 0], 'scale': [0.55, 0.55, 0.55]},
    'firstperson_lefthand': {'rotation': [28, 45, 0], 'translation': [0, -0.5, 0], 'scale': [0.55, 0.55, 0.55]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.45, 0.45, 0.45]},
    'head': {'rotation': [0, 0, 0], 'translation': [0, 13, 0], 'scale': [0.9, 0.9, 0.9]},
    'fixed': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [0.85, 0.85, 0.85]},
    'gui': {'rotation': [28, 225, 0], 'translation': [0, 0, 0], 'scale': [0.85, 0.85, 0.85]},
}


def display(base, **over):
    """Copy of a preset with per-context overrides: display(OBJECT, gui={'scale': [1, 1, 1]})."""
    d = json.loads(json.dumps(base))
    for k, v in over.items():
        d.setdefault(k, {}).update(v)
        if k.endswith('_righthand'):
            lk = k.replace('right', 'left')
            if lk not in over:
                d.setdefault(lk, {}).update(v)
    return d


# ------------------------------------------------------------------------------------------------ atlas

class Atlas:
    """Shelf-packed square atlas. Regions are painted per animation frame: painter(put, w, h, frame)."""

    def __init__(self, size=32, frames=1, frametime=None, mcmeta=None):
        self.size = size
        self.frames = frames
        self.mcmeta = mcmeta
        self.regions = []              # (rect, painter)
        self.x = self.y = self.sh = 0
        self.cache = {}

    def alloc(self, w, h):
        w, h = max(1, int(math.ceil(w))), max(1, int(math.ceil(h)))
        if w > self.size:
            raise RuntimeError(f'region {w}x{h} wider than atlas {self.size}')
        if self.x + w > self.size:
            self.x, self.y, self.sh = 0, self.y + self.sh, 0
        if self.y + h > self.size:
            raise AtlasFull()
        r = (self.x, self.y, w, h)
        self.x += w
        self.sh = max(self.sh, h)
        return r

    def region(self, key, w, h, painter):
        if key is not None and key in self.cache:
            return self.cache[key]
        r = self.alloc(w, h)
        self.regions.append((r, painter))
        if key is not None:
            self.cache[key] = r
        return r

    def uv(self, rect, flip=False, flipv=False):
        x, y, w, h = rect
        s = 16 / self.size
        u0, v0, u1, v1 = x * s, y * s, (x + w) * s, (y + h) * s
        if flip:
            u0, u1 = u1, u0
        if flipv:
            v0, v1 = v1, v0
        return [round(u0, 4), round(v0, 4), round(u1, 4), round(v1, 4)]

    def image(self):
        img = Image.new('RGBA', (self.size, self.size * self.frames), (0, 0, 0, 0))
        for f in range(self.frames):
            for (x, y, w, h), painter in self.regions:
                oy = f * self.size

                def put(px, py, c, x=x, y=y, w=w, h=h, oy=oy):
                    if c is not None and 0 <= px < w and 0 <= py < h and c[3] > 0:
                        img.putpixel((x + int(px), oy + y + int(py)), tuple(int(v) for v in c[:4]))
                painter(put, w, h, f)
        return img


class AtlasFull(Exception):
    pass


# ------------------------------------------------------------------------------------------------ painters
# painter(put, w, h, frame)

def p_flat(c):
    def p(put, w, h, f):
        for y in range(h):
            for x in range(w):
                put(x, y, c)
    return p


def p_pillow(r, seed=None, noise=0.0):
    """Style-bible face framing on a ramp (dark..light): lit top row, darker side columns and bottom row,
    gentle per-pixel variation."""
    d, m, l = r[1], r[len(r) // 2], r[-2]

    def p(put, w, h, f):
        rnd = random.Random(seed if seed is not None else (w * 31 + h))
        for y in range(h):
            for x in range(w):
                c = m
                if h > 1 and y == 0:
                    c = l
                elif h > 2 and y == h - 1:
                    c = d
                elif w > 2 and (x == 0 or x == w - 1):
                    c = mix(m, d, 0.45)
                if noise and rnd.random() < noise:
                    c = mix(c, r[0] if rnd.random() < 0.5 else r[-1], 0.35)
                put(x, y, c)
    return p


def p_speckle(r, density=0.25, seed=0, spots=None, spot_density=0.0):
    """Base mid tone with light/dark speckles; optional accent spots (e.g. herbs on a stew, seeds on bread)."""
    def p(put, w, h, f):
        rnd = random.Random(seed * 977 + w * 31 + h)
        for y in range(h):
            for x in range(w):
                c = r[len(r) // 2]
                q = rnd.random()
                if q < density / 2:
                    c = r[len(r) // 2 - 1]
                elif q < density:
                    c = r[len(r) // 2 + 1]
                if spots and rnd.random() < spot_density:
                    c = rnd.choice(spots)
                put(x, y, c)
    return p


def p_rows(rows, pal):
    """ASCII art, '.' = clear; repeats when the region is larger."""
    def p(put, w, h, f):
        th, tw = len(rows), len(rows[0])
        for y in range(h):
            for x in range(w):
                ch = rows[y % th][x % tw]
                if ch != '.' and ch != ' ':
                    put(x, y, pal[ch] if not callable(pal[ch]) else pal[ch](f))
    return p


def p_func(fn):
    """fn(x, y, w, h, frame) -> colour or None."""
    def p(put, w, h, f):
        for y in range(h):
            for x in range(w):
                put(x, y, fn(x, y, w, h, f))
    return p


# ------------------------------------------------------------------------------------------------ model

def _r(v):
    return round(float(v), 4)


def _euler(x, y, z):
    """Rz*Ry*Rx (degrees) as a 3x3 list."""
    x, y, z = (math.radians(v) for v in (x, y, z))
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _mm(rz, _mm(ry, rx))


def _mm(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _mv(a, v):
    return [sum(a[i][k] * v[k] for k in range(3)) for i in range(3)]


def _deuler(R):
    """Inverse of _euler -> (x, y, z) degrees."""
    sy = max(-1.0, min(1.0, -R[2][0]))
    y = math.asin(sy)
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(R[2][1], R[2][2])
        z = math.atan2(R[1][0], R[0][0])
    else:
        x = math.atan2(-R[1][2], R[1][1])
        z = 0.0
    return tuple(math.degrees(v) for v in (x, y, z))


class Item:
    def __init__(self, pkg, name, atlas=None, disp=None, gui_light='side', particle=None):
        self.pkg, self.name = pkg, name
        self.atlas = atlas or Atlas(32)
        self.disp = disp or GEN
        self.gui_light = gui_light
        self.els = []
        self.particle = particle or f'arcana/{pkg}/{name}'      # the old sprite: break particles / eat crumbs

    @property
    def tex_ref(self):
        return f'arcana/{self.pkg}/3d/{self.name}'

    # ---- raw element
    def el(self, f, t, faces, rot=None, shade=True, name=None):
        e = {'from': [_r(v) for v in f], 'to': [_r(v) for v in t], 'faces': faces}
        if name:
            e['name'] = name
        if rot:
            r = {'origin': [_r(v) for v in rot['origin']]}
            for k in ('x', 'y', 'z'):
                if rot.get(k):
                    r[k] = _r(rot[k])
            if 'axis' in rot:
                r = {'origin': r['origin'], 'axis': rot['axis'], 'angle': _r(rot['angle'])}
            if len(r) > 1:
                e['rotation'] = r
        if not shade:
            e['shade'] = False
        self.els.append(e)
        return e

    # ---- painted box: painter per face group; each face gets its own region sized to the face
    def box(self, f, t, side, top=None, bottom=None, front=None, back=None, rot=None, skip=(), shade=True,
            key=None, name=None, uvflip=()):
        """side/top/bottom/front/back are painters (put, w, h, frame). front = south (+z), back = north.
        Faces of equal size and painter share one region when `key` is given."""
        f, t = list(f), list(t)
        sx, sy, sz = t[0] - f[0], t[1] - f[1], t[2] - f[2]
        size = {'north': (sx, sy), 'south': (sx, sy), 'east': (sz, sy), 'west': (sz, sy),
                'up': (sx, sz), 'down': (sx, sz)}
        pick = {'north': back or front or side, 'south': front or side, 'east': side, 'west': side,
                'up': top or side, 'down': bottom or top or side}
        faces = {}
        for face in ('north', 'south', 'east', 'west', 'up', 'down'):
            if face in skip:
                continue
            w, h = size[face]
            if w <= 0 or h <= 0:
                continue
            pw, ph = max(1, round(w)), max(1, round(h))
            k = None if key is None else (key, face in ('up', 'down'), face in ('north', 'south'), pw, ph, id(pick[face]))
            rect = self.atlas.region(k, pw, ph, pick[face])
            faces[face] = {'uv': self.atlas.uv(rect, flip=face in uvflip), 'texture': '#0'}
        return self.el(f, t, faces, rot=rot, shade=shade, name=name)

    def cbox(self, c, s, side, **kw):
        """Box by centre (cx, cy, cz) and size (sx, sy, sz)."""
        return self.box([c[i] - s[i] / 2 for i in range(3)], [c[i] + s[i] / 2 for i in range(3)], side, **kw)

    def cyl(self, cx, y0, cz, r, h, side, top=None, bottom=None, sides=8, key=None, rot_axis='y', **kw):
        """Round-ish column: two crossed boxes (octagon) when sides=8, one box when sides=4."""
        out = [self.box((cx - r, y0, cz - r * 0.42), (cx + r, y0 + h, cz + r * 0.42), side, top=top,
                        bottom=bottom, key=key, **kw),
               self.box((cx - r * 0.42, y0, cz - r), (cx + r * 0.42, y0 + h, cz + r), side, top=top,
                        bottom=bottom, key=key, **kw)]
        if sides == 8:
            out.append(self.box((cx - r * 0.83, y0 + 0.001, cz - r * 0.83), (cx + r * 0.83, y0 + h - 0.001, cz + r * 0.83),
                                side, top=top, bottom=bottom, key=key,
                                rot={'origin': (cx, y0, cz), 'y': 45}, **kw))
        return out

    def card(self, f, t, painter, rot=None, axis='z', shade=True, name=None):
        """Zero-thickness two-sided plane (leaf, flame, steam, line). axis = the flat axis."""
        f, t = list(f), list(t)
        if axis == 'z':
            w, h = t[0] - f[0], t[1] - f[1]
            fa, fb = 'south', 'north'
        elif axis == 'x':
            w, h = t[2] - f[2], t[1] - f[1]
            fa, fb = 'east', 'west'
        else:
            w, h = t[0] - f[0], t[2] - f[2]
            fa, fb = 'up', 'down'
        rect = self.atlas.region(None, max(1, round(w)), max(1, round(h)), painter)
        faces = {fa: {'uv': self.atlas.uv(rect), 'texture': '#0'},
                 fb: {'uv': self.atlas.uv(rect, flip=True), 'texture': '#0'}}
        return self.el(f, t, faces, rot=rot, shade=shade, name=name)

    # ---- world-space painted box: mat(face, x, y, z, frame) -> colour, sampled at each texel centre
    def wbox(self, f, t, mat, rot=None, skip=(), name=None, shade=True, frame=True, density=1.0):
        """Box whose faces are painted from a world-space material, so patterns (stripes, spots, belly gradients)
        run continuously across neighbouring boxes. frame=True adds the style-bible framing (lit top row, darker
        bottom row) to the side faces. density = texels per unit."""
        f, t = [float(v) for v in f], [float(v) for v in t]
        x0, y0, z0 = f
        x1, y1, z1 = t
        geo = {  # face: (u extent, v extent, fn(u, v) -> world point)
            'south': (x1 - x0, y1 - y0, lambda u, v: (x0 + u, y1 - v, z1)),
            'north': (x1 - x0, y1 - y0, lambda u, v: (x1 - u, y1 - v, z0)),
            'east': (z1 - z0, y1 - y0, lambda u, v: (x1, y1 - v, z1 - u)),
            'west': (z1 - z0, y1 - y0, lambda u, v: (x0, y1 - v, z0 + u)),
            'up': (x1 - x0, z1 - z0, lambda u, v: (x0 + u, y1, z0 + v)),
            'down': (x1 - x0, z1 - z0, lambda u, v: (x0 + u, y0, z1 - v)),
        }
        faces = {}
        for face, (ue, ve, fn) in geo.items():
            if face in skip or ue <= 1e-6 or ve <= 1e-6:
                continue
            pw, ph = max(1, round(ue * density)), max(1, round(ve * density))

            def painter(put, w, h, fr, fn=fn, ue=ue, ve=ve, face=face):
                for j in range(h):
                    for i in range(w):
                        p = fn((i + 0.5) * ue / w, (j + 0.5) * ve / h)
                        c = mat(face, p[0], p[1], p[2], fr)
                        if c is None:
                            continue
                        if frame and face not in ('up', 'down') and h > 2:
                            if j == 0:
                                c = _shade(c, 1.08)
                            elif j == h - 1:
                                c = _shade(c, 0.86)
                        put(i, j, c)
            rect = self.atlas.region(None, pw, ph, painter)
            faces[face] = {'uv': self.atlas.uv(rect), 'texture': '#0'}
        return self.el(f, t, faces, rot=rot, shade=shade, name=name)

    # ---- group transform: rotate (Euler XYZ degrees, applied Rz*Ry*Rx like 26.x) + translate existing elements
    def transform(self, els=None, rot=(0, 0, 0), origin=(8, 8, 8), move=(0, 0, 0)):
        els = self.els if els is None else els
        R2 = _euler(*rot)
        o2 = list(origin)
        for e in els:
            r = e.get('rotation')
            if r and 'axis' in r:
                a = [0, 0, 0]
                a['xyz'.index(r['axis'])] = r['angle']
                R1, o1 = _euler(*a), r['origin']
            elif r:
                R1, o1 = _euler(r.get('x', 0), r.get('y', 0), r.get('z', 0)), r['origin']
            else:
                R1, o1 = _euler(0, 0, 0), [8, 8, 8]
            R = _mm(R2, R1)
            # world map: p -> R2 (R1 (p - o1) + o1 - o2) + o2 + move = R (p - o1) + o1 + delta
            q = [o1[i] - o2[i] for i in range(3)]
            Rq = _mv(R2, q)
            delta = [Rq[i] + o2[i] + move[i] - o1[i] for i in range(3)]
            e['from'] = [_r(e['from'][i] + delta[i]) for i in range(3)]
            e['to'] = [_r(e['to'][i] + delta[i]) for i in range(3)]
            ox, oy, oz = (o1[i] + delta[i] for i in range(3))
            ex, ey, ez = _deuler(R)
            nr = {'origin': [_r(ox), _r(oy), _r(oz)]}
            for k, v in zip('xyz', (ex, ey, ez)):
                if abs(v) > 1e-4:
                    nr[k] = _r(v)
            if len(nr) > 1:
                e['rotation'] = nr
            elif 'rotation' in e:
                del e['rotation']
        return els

    def mark(self):
        """Index to pass to since(): elements added after this point."""
        return len(self.els)

    def since(self, m):
        return self.els[m:]

    # ---- relief from a sprite
    def relief(self, sprite, z=8.0, tmax=4, slope=1.0, base=1.0, thick=None, offset=(0, 0), scale=1.0,
               thin=None, clip=None):
        """sprite: RGBA PIL image (one frame) or list of frames. thick(x, y, d) -> thickness override.
        thin: set of (r, g, b) colours forced to thickness 1 (fins, lines, steam). clip=(x0, y0, x1, y1) uses only
        part of the sprite. Returns the thickness map."""
        frames = sprite if isinstance(sprite, list) else [sprite]
        im0 = frames[0]
        W, H = im0.size
        x0c, y0c, x1c, y1c = clip or (0, 0, W, H)
        alpha = [[im0.getpixel((x, y))[3] >= 26 and x0c <= x < x1c and y0c <= y < y1c for x in range(W)]
                 for y in range(H)]
        # 4-neighbour distance to the outside
        INF = 99
        d = [[INF if alpha[y][x] else 0 for x in range(W)] for y in range(H)]
        from collections import deque
        q = deque()
        for y in range(H):
            for x in range(W):
                if alpha[y][x]:
                    edge = any(not (0 <= x + dx < W and 0 <= y + dy < H) or not alpha[y + dy][x + dx]
                               for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                    if edge:
                        d[y][x] = 1
                        q.append((x, y))
        while q:
            x, y = q.popleft()
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < W and 0 <= ny < H and d[ny][nx] > d[y][x] + 1:
                    d[ny][nx] = d[y][x] + 1
                    q.append((nx, ny))
        T = [[0] * W for _ in range(H)]
        for y in range(H):
            for x in range(W):
                if not alpha[y][x]:
                    continue
                t = min(tmax, base + slope * (d[y][x] - 1))
                if thin and im0.getpixel((x, y))[:3] in thin:
                    t = 1
                if thick:
                    o = thick(x, y, d[y][x])
                    if o is not None:
                        t = o
                T[y][x] = max(0.5, round(t * 2) / 2)
        # frames as atlas source: copy the sprite (every frame) into one region
        A = self.atlas
        spr_rect = A.region(('sprite', id(frames)), W, H, lambda put, w, h, f: [
            put(x, y, frames[min(f, len(frames) - 1)].getpixel((x, y))) for y in range(h) for x in range(w)
            if alpha[y][x]])

        def px(x, y, f=0):
            return frames[min(f, len(frames) - 1)].getpixel((x, y))

        # greedy rectangles of equal thickness
        used = [[False] * W for _ in range(H)]
        ox, oy = offset
        s = scale
        for y in range(H):
            for x in range(W):
                if not alpha[y][x] or used[y][x]:
                    continue
                t = T[y][x]
                w = 1
                while x + w < W and alpha[y][x + w] and not used[y][x + w] and T[y][x + w] == t:
                    w += 1
                h = 1
                while y + h < H and all(alpha[y + h][x + i] and not used[y + h][x + i] and T[y + h][x + i] == t
                                        for i in range(w)):
                    h += 1
                for yy in range(y, y + h):
                    for xx in range(x, x + w):
                        used[yy][xx] = True
                # model coords: sprite row 0 = top (y 16)
                mx0, mx1 = ox + x * s, ox + (x + w) * s
                my1, my0 = oy + (H - y) * s, oy + (H - y - h) * s
                tz = t * s
                faces = {}
                fx, fy = spr_rect[0], spr_rect[1]
                srect = (fx + x, fy + y, w, h)
                faces['south'] = {'uv': A.uv(srect), 'texture': '#0'}
                faces['north'] = {'uv': A.uv(srect, flip=True), 'texture': '#0'}

                def nb(xx, yy):
                    return T[yy][xx] if 0 <= xx < W and 0 <= yy < H and alpha[yy][xx] else 0
                # walls: skip a wall only when every neighbour along it is at least as thick
                if any(nb(x + i, y - 1) < t for i in range(w)):
                    r = A.region(None, w, max(1, round(t)), (lambda X, Y, W_: lambda put, ww, hh, f: [
                        put(i, j, shade(px(X + i, Y, f), 1.0)) for i in range(ww) for j in range(hh)])(x, y, w))
                    faces['up'] = {'uv': A.uv(r), 'texture': '#0'}
                if any(nb(x + i, y + h) < t for i in range(w)):
                    r = A.region(None, w, max(1, round(t)), (lambda X, Y: lambda put, ww, hh, f: [
                        put(i, j, shade(px(X + i, Y, f), 0.92)) for i in range(ww) for j in range(hh)])(x, y + h - 1))
                    faces['down'] = {'uv': A.uv(r), 'texture': '#0'}
                if any(nb(x - 1, y + j) < t for j in range(h)):
                    r = A.region(None, max(1, round(t)), h, (lambda X, Y: lambda put, ww, hh, f: [
                        put(i, j, shade(px(X, Y + j, f), 0.96)) for i in range(ww) for j in range(hh)])(x, y))
                    faces['west'] = {'uv': A.uv(r), 'texture': '#0'}
                if any(nb(x + w, y + j) < t for j in range(h)):
                    r = A.region(None, max(1, round(t)), h, (lambda X, Y: lambda put, ww, hh, f: [
                        put(i, j, shade(px(X, Y + j, f), 0.96)) for i in range(ww) for j in range(hh)])(x + w - 1, y))
                    faces['east'] = {'uv': A.uv(r), 'texture': '#0'}
                self.el((mx0, my0, z - tz / 2), (mx1, my1, z + tz / 2), faces)
        return T

    # ---- output
    def model_json(self):
        m = {'credit': 'Arcana items3d (arcana_items/items3d)', 'textures': {'0': self.tex_ref, 'particle': self.particle},
             'gui_light': self.gui_light, 'elements': self.els, 'display': self.disp}
        return m

    def save(self, build=BUILD):
        if not self.els:
            raise RuntimeError(f'{self.name}: no elements')
        mp = os.path.join(build, 'models', 'arcana', self.pkg, self.name + '.json')
        tp = os.path.join(build, 'textures', 'arcana', self.pkg, '3d', self.name + '.png')
        os.makedirs(os.path.dirname(mp), exist_ok=True)
        os.makedirs(os.path.dirname(tp), exist_ok=True)
        img = self.atlas.image()
        img.save(tp)
        meta = tp + '.mcmeta'
        if self.atlas.frames > 1:
            with open(meta, 'w', encoding='utf-8') as f:
                json.dump(self.atlas.mcmeta or {'animation': {'frametime': 2}}, f, indent=1)
        elif os.path.exists(meta):
            os.remove(meta)
        with open(mp, 'w', encoding='utf-8') as f:
            json.dump(self.model_json(), f, separators=(',', ':'))
        bp = os.path.join(BBMODEL, self.pkg, self.name + '.bbmodel')
        write_bbmodel(bp, self.name, self.model_json(), img, self.atlas)
        return mp


def build(fn, pkg, name, sizes=(32, 48, 64, 96, 128), **kw):
    """Run fn(item) with growing atlases until it fits. kw go to Item()."""
    last = None
    for s in sizes:
        frames = kw.get('frames', 1)
        it = Item(pkg, name, atlas=Atlas(s, frames=frames, mcmeta=kw.get('mcmeta')),
                  **{k: v for k, v in kw.items() if k not in ('frames', 'mcmeta')})
        try:
            fn(it)
            return it.save()
        except AtlasFull as e:
            last = e
    raise RuntimeError(f'{name}: atlas full even at {sizes[-1]}')


# ------------------------------------------------------------------------------------------------ sprites

def sprite(pkg, name):
    """-> (frames list, mcmeta or None) of the current 2D sprite in the pack (or its 2D backup)."""
    p = os.path.join(PACK, 'textures', 'arcana', pkg, name + '.png')
    im = Image.open(p).convert('RGBA')
    w, h = im.size
    meta = None
    if os.path.exists(p + '.mcmeta'):
        meta = json.load(open(p + '.mcmeta', encoding='utf-8'))
    frames = [im.crop((0, i * w, w, (i + 1) * w)) for i in range(max(1, h // w))]
    return frames, meta


# ------------------------------------------------------------------------------------------------ bbmodel

def write_bbmodel(path, name, model, img, atlas):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    S = atlas.size
    k = S / 16
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    tex_uuid = str(_uuid.uuid4())
    els = []
    for i, e in enumerate(model['elements']):
        rot = e.get('rotation')
        r3 = [0, 0, 0]
        origin = [8, 8, 8]
        if rot:
            origin = rot['origin']
            if 'axis' in rot:
                r3['xyz'.index(rot['axis'])] = rot['angle']
            else:
                r3 = [rot.get('x', 0), rot.get('y', 0), rot.get('z', 0)]
        faces = {}
        for fn in ('north', 'east', 'south', 'west', 'up', 'down'):
            fd = e['faces'].get(fn)
            if fd:
                faces[fn] = {'uv': [round(v * k, 4) for v in fd['uv']], 'texture': 0}
            else:
                faces[fn] = {'uv': [0, 0, 0, 0], 'texture': None}
        els.append({'name': e.get('name', f'part{i}'), 'box_uv': False, 'rescale': False, 'locked': False,
                    'render_order': 'default', 'allow_mirror_modeling': True, 'from': e['from'], 'to': e['to'],
                    'autouv': 0, 'color': i % 8, 'origin': origin, 'rotation': r3, 'shade': e.get('shade', True),
                    'faces': faces, 'type': 'cube', 'uuid': str(_uuid.uuid4())})
    tex = {'path': '', 'name': name + '.png', 'folder': '', 'namespace': '', 'id': '0', 'group': '',
           'width': img.size[0], 'height': img.size[1], 'uv_width': S, 'uv_height': S, 'particle': False,
           'use_as_default': False, 'layers_enabled': False, 'sync_to_project': '', 'render_mode': 'default',
           'render_sides': 'auto', 'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '',
           'frame_interpolate': False, 'visible': True, 'internal': True, 'saved': True, 'uuid': tex_uuid,
           'relative_path': '', 'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}
    if atlas.frames > 1 and atlas.mcmeta:
        tex['frame_time'] = atlas.mcmeta.get('animation', {}).get('frametime', 1)
    bb = {'meta': {'format_version': '5.0', 'model_format': 'java_block', 'box_uv': False},
          'name': name, 'parent': '', 'ambientocclusion': True, 'front_gui_light': model.get('gui_light') == 'front',
          'visible_box': [1, 1, 0], 'variable_placeholders': '', 'variable_placeholder_buttons': [],
          'unhandled_root_fields': {}, 'resolution': {'width': S, 'height': S},
          'elements': els, 'outliner': [e['uuid'] for e in els], 'textures': [tex],
          'display': model['display']}
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(bb, f)


# ------------------------------------------------------------------------------------------------ install

def install(subs=None):
    """Copy build/models + build/textures into the pack; back up each replaced 2D model once."""
    n = b = 0
    for kind in ('models', 'textures'):
        root = os.path.join(BUILD, kind)
        for dp, _, files in os.walk(root):
            for fn in files:
                s = os.path.join(dp, fn)
                rel = os.path.relpath(s, BUILD)
                if subs and not any(rel.replace('\\', '/').startswith(f'{kind}/{x}') for x in subs):
                    continue
                dst = os.path.join(PACK, rel)
                bk = os.path.join(BACKUP, rel)
                if os.path.exists(dst) and not os.path.exists(bk) and os.sep + '3d' + os.sep not in rel:
                    os.makedirs(os.path.dirname(bk), exist_ok=True)
                    shutil.copy2(dst, bk)
                    b += 1
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(s, dst)
                n += 1
    return n, b
