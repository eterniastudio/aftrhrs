"""Fish-building kit for the fishing species models: the helpers of the Arcana items3d fish batch
(arcana_items/items3d/batch_fish.py, which built the first 22 fish) copied here unchanged so the new species build
from the repo alone. Fish are modelled side-on, horizontal, swimming toward +x (head right), centred on z=8; the
GUI/hand poses tilt them like the old sprites (and vanilla cod), the item frame / plaque / aquarium (`fixed`) shows them
flat, side-on and swimming.
"""
import math

import ik
from ik import hx, mix, shade, display, GEN

PKG = 'fishing'
BLACK = hx('#111111')
WHITE = hx('#f6f4ec')

def C(c):
    return hx(c) if isinstance(c, str) else c


def alpha(c, a):
    c = C(c)
    return (c[0], c[1], c[2], a)


# ------------------------------------------------------------------------------------------------ display poses

def _rx(a):
    a = math.radians(a)
    return [[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]]


def _ry(a):
    a = math.radians(a)
    return [[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]]


def _rz(a):
    a = math.radians(a)
    return [[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]]


def _mm(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def pose(yaw=0.0, tilt=0.0, roll=0.0):
    """Display rotation (vanilla order: M = Rx * Ry * Rz) for: turn the model about its own y axis by `yaw`, tip it
    toward the viewer by `tilt` (shows the top), then roll it in the screen plane by `roll` (counter-clockwise)."""
    M = _mm(_rz(roll), _mm(_rx(tilt), _ry(yaw)))
    b = math.asin(max(-1.0, min(1.0, M[0][2])))
    c = math.atan2(-M[0][1], M[0][0])
    a = math.atan2(-M[1][2], M[2][2])
    return [round(math.degrees(v), 2) for v in (a, b, c)]


def fish_disp(gui_scale=0.85, roll=38, yaw=205, tilt=6, fixed_scale=0.85, gui_move=(0, 0, 0), fixed_move=(0, 0, 0)):
    """Head down-left diagonal in GUI and hands (north side toward the viewer), flat and side-on in the frame."""
    return display(GEN,
                   gui={'rotation': pose(yaw, tilt, roll), 'translation': list(gui_move),
                        'scale': [gui_scale] * 3},
                   firstperson_righthand={'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13],
                                          'scale': [0.6, 0.6, 0.6]},
                   thirdperson_righthand={'rotation': [0, 0, -35], 'translation': [0, 3, 1],
                                          'scale': [0.55, 0.55, 0.55]},
                   ground={'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
                   head={'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
                   fixed={'rotation': [0, 180, 0], 'translation': list(fixed_move), 'scale': [fixed_scale] * 3})


# ------------------------------------------------------------------------------------------------ helpers

def interp(st, x):
    """st: [(x, bot, top, halfwidth)] sorted by x -> (bot, top, hw) at x."""
    if x <= st[0][0]:
        return st[0][1:]
    for a, b in zip(st, st[1:]):
        if a[0] <= x <= b[0]:
            t = (x - a[0]) / max(1e-6, b[0] - a[0])
            return tuple(a[i] + (b[i] - a[i]) * t for i in (1, 2, 3))
    return st[-1][1:]


def q2(v):
    return round(v * 2) / 2


def fin(it, x0, y0, rows, pal, z=8.0, rot=None, shade_=True, s=1.0):
    """ASCII fin card in the x/y plane at depth z. rows[0] is the top row; s = units per texel."""
    h, w = len(rows), len(rows[0])
    return it.card((x0, y0, z), (x0 + w * s, y0 + h * s, z), ik.p_rows(rows, pal), rot=rot, shade=shade_)


def finpal(fc, tip=None, accent=None, a=255):
    fc = C(fc)
    p = {'#': alpha(fc, a), '+': alpha(shade(fc, 0.78), a), '-': alpha(shade(fc, 1.15), a),
         'o': alpha(C(tip) if tip else shade(fc, 1.25), a), '*': C(accent) if accent else shade(fc, 0.6),
         'k': BLACK, 'w': WHITE}
    return p


def shimmer_fn(x_head, x_tail, color=(255, 255, 255, 255), k=0.5, width=1.1, slant=0.55):
    """Light band that sweeps from head to tail over frames 2..6 (frame 0, 1 and 7 are plain, like the sprites)."""
    L = x_head - x_tail

    def f(c, x, y, fr):
        if fr < 2 or fr > 6:
            return c
        pos = x_head + 1.0 - (fr - 2) * (L + 2.0) / 4.0
        d = abs((x + slant * (y - 8)) - pos)
        if d < width:
            return mix(c, color, k * (1 - d / (width + 0.4)))
        return c
    return f


class Body:
    """A fish body from stations: segments between `cuts`, each box sized from the interpolated profile.
    Paints back/side/belly bands per segment height, plus mouth, gill line, pattern and shimmer."""

    def __init__(self, it, st, cuts, pal, bands=(0.3, 0.76), pattern=None, mouth=None, gill=None, shimmer=None,
                 scales=0.0, ymid=None):
        self.it, self.st, self.pal = it, st, pal
        self.bands, self.pattern, self.mouth, self.gill, self.shimmer = bands, pattern, mouth, gill, shimmer
        self.scales = scales
        self.segs = []
        for x0, x1 in zip(cuts, cuts[1:]):
            bot, top, hw = interp(st, (x0 + x1) / 2)
            self.segs.append([x0, x1, q2(bot), q2(top), q2(hw * 2) / 2])
        self.nose = cuts[-1]
        self.tail = cuts[0]

    def seg_at(self, x):
        for s in self.segs:
            if s[0] <= x <= s[1]:
                return s
        return self.segs[0] if x < self.tail else self.segs[-1]

    def top(self, x):
        return self.seg_at(x)[3]

    def bot(self, x):
        return self.seg_at(x)[2]

    def hw(self, x):
        return self.seg_at(x)[4]

    def band(self, t):
        p = self.pal
        b1, b2 = self.bands
        if t < b1 - 0.06:
            return p['belly']
        if t < b1 + 0.06:
            return mix(p['belly'], p['side'], 0.5)
        if t < b2 - 0.06:
            return p['side']
        if t < b2 + 0.06:
            return mix(p['side'], p['back'], 0.5)
        return p['back']

    def mat(self, bot, top):
        p = self.pal

        def m(face, x, y, z, f):
            if face == 'up':
                t = 1.0
            elif face == 'down':
                t = 0.0
            else:
                t = (y - bot) / max(0.01, top - bot)
            c = self.band(t)
            if face == 'down':
                c = shade(c, 0.92)
            if self.scales and face in ('north', 'south') and 0.2 < t < 0.9:
                if (int(math.floor(x)) + int(math.floor(y * 1.0))) % 2 == 0:
                    c = shade(c, 1 - self.scales)
            if self.gill is not None and face in ('north', 'south', 'up', 'down'):
                gx = self.gill
                if gx <= x < gx + 1.0 and 0.15 < t < 0.92 and face in ('north', 'south'):
                    c = mix(c, p.get('dark', shade(p['back'], 0.6)), 0.4)
            if self.pattern:
                c = self.pattern(face, x, y, z, t, c, f) or c
            if self.mouth is not None:
                my, ml = self.mouth
                if x > self.nose - ml and my - 0.5 <= y < my + 0.01 and face in ('north', 'south', 'east'):
                    c = p.get('mouth', shade(p['back'], 0.45))
            if self.shimmer:
                c = self.shimmer(c, x, y, f)
            return c
        return m

    def build(self):
        n = len(self.segs)
        for i, (x0, x1, bot, top, hw) in enumerate(self.segs):
            skip = []
            # hide end faces fully covered by the neighbour
            if i + 1 < n:
                nb = self.segs[i + 1]
                if nb[2] <= bot and nb[3] >= top and nb[4] >= hw:
                    skip.append('east')
            if i > 0:
                pb = self.segs[i - 1]
                if pb[2] <= bot and pb[3] >= top and pb[4] >= hw:
                    skip.append('west')
            self.it.wbox((x0, bot, 8 - hw), (x1, top, 8 + hw), self.mat(bot, top), skip=skip, name=f'body{i}')
        return self


def eye(it, x, y, hw, ring, size=1.0, pupil=BLACK, glint=True, shade_=True, out=0.12):
    """Through-box eye (shows on both sides): ring colour with a pupil toward the front-bottom and a glint."""
    x0, y0 = x - size / 2, y - size / 2

    def m(face, px, py, pz, f):
        if face in ('north', 'south'):
            i, j = int((px - x0) / size * 3), int((py - y0) / size * 3)
            i, j = min(2, max(0, i)), min(2, max(0, j))
            if pupil is None:
                return ring
            if glint and (i, j) == (1, 1):
                return hx('#f4f4ee')
            if i >= 1 and j <= 1:
                return pupil
            return ring
        return shade(ring, 0.7)
    return it.wbox((x0, y0, 8 - hw - out), (x0 + size, y0 + size, 8 + hw + out), m, density=3, frame=False,
                   shade=shade_, name='eye')


def pectorals(it, x, y, hw, rows, pal, ang=28):
    """A pair of pectoral fins, attached at their front edge (x) and swept back + out."""
    w = len(rows[0])
    for sgn in (1, -1):
        z = 8 + sgn * (hw + 0.02)
        fin(it, x - w, y, rows, pal, z=z, rot={'origin': (x, y, z), 'y': sgn * ang})


# ------------------------------------------------------------------------------------------------ tail shapes
# Drawn head-right: the right-most column attaches to the peduncle (it overlaps one unit into it).
TAILS = {
    'fork': ['o#...',
             'o##..',
             '.o#+.',
             '..#++',
             '...++',
             '..#++',
             '.o#+.',
             'o##..',
             'o#...'],
    'fork_s': ['o#..',
               '.o#+',
               '..++',
               '..++',
               '.o#+',
               'o#..'],
    'lunate': ['o....',
               'o#...',
               '.o#+.',
               '..#++',
               '..#++',
               '.o#+.',
               'o#...',
               'o....'],
    'round': ['.oo.',
              'o##.',
              'o#++',
              'o#++',
              'o#++',
              'o##.',
              '.oo.'],
    'square': ['oo..',
               'o#+.',
               'o#++',
               '.#++',
               'o#++',
               'o#+.',
               'oo..'],
    'fan': ['oo#...',
            'o##+..',
            '.o##+.',
            '..o#++',
            '...#++',
            '..o#++',
            '.o##+.',
            'o##+..',
            'oo#...'],
}


def tail(it, kind_or_rows, attach_x, yc, pal, s=1.0, rot=None):
    rows = TAILS[kind_or_rows] if isinstance(kind_or_rows, str) else kind_or_rows
    h, w = len(rows), len(rows[0])
    x0 = attach_x + 1.0 - w * s
    return fin(it, x0, yc - h * s / 2, rows, pal, s=s, rot=rot)



# ------------------------------------------------------------------------------------------------ eels

def eel(it, x0, x1, n, prof, amp, wl, pal, pattern=None, phase=0.0, shimmer=None, bands=(0.35, 0.62)):
    """A snaky eel along x with a gentle S-curve in y: n segments, each rotated to follow the curve.
    prof(u) -> (height, width) with u = 0 at the tail, 1 at the nose. Returns centre-line fn yc(x)."""
    def yc(x):
        return 8 + amp * math.sin(2 * math.pi * (x - x0) / wl + phase)

    def slope(x):
        return amp * 2 * math.pi / wl * math.cos(2 * math.pi * (x - x0) / wl + phase)
    xs = [x0 + (x1 - x0) * i / n for i in range(n + 1)]
    for i, (a, b) in enumerate(zip(xs, xs[1:])):
        xm = (a + b) / 2
        h, w = prof((xm - x0) / (x1 - x0))
        w += 0.08 * (i % 2)                                     # no coplanar overlaps at the joints
        y = yc(xm)
        ang = math.degrees(math.atan(slope(xm)))
        bot, top = y - h / 2, y + h / 2

        def m(face, x, yy, z, f, bot=bot, top=top):
            t = 1.0 if face == 'up' else (0.0 if face == 'down' else (yy - bot) / max(0.01, top - bot))
            b1, b2 = bands
            c = pal['belly'] if t < b1 else (pal['side'] if t < b2 else pal['back'])
            if pattern:
                c = pattern(face, x, yy, z, t, c, f) or c
            if shimmer:
                c = shimmer(c, x, yy, f)
            return c
        ext0 = 0.0 if i == 0 else 0.3
        ext1 = 0.0 if i == n - 1 else 0.3
        it.wbox((a - ext0, bot, 8 - w / 2), (b + ext1, top, 8 + w / 2), m,
                rot={'origin': (xm, y, 8), 'z': ang} if abs(ang) > 0.5 else None, name=f'eel{i}')
    return yc, slope


def crab_disp(gui_scale=0.8, yaw=-22, tilt=22):
    return display(GEN,
                   gui={'rotation': pose(yaw, tilt, 0), 'translation': [0, 0, 0], 'scale': [gui_scale] * 3},
                   firstperson_righthand={'rotation': [0, 135, 0], 'translation': [1.13, 3.2, 1.13],
                                          'scale': [0.5, 0.5, 0.5]},
                   thirdperson_righthand={'rotation': [10, 0, 0], 'translation': [0, 3, 1.5],
                                          'scale': [0.5, 0.5, 0.5]},
                   ground={'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
                   head={'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
                   fixed={'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.82] * 3})
