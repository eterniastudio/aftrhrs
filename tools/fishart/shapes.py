"""Shared shape builders for the new fishing species (on top of kit.py): a parametric fish body with its fins, sharks,
flatfish, crabs and crayfish, turtles. Every species file (species_*.py) calls these with its own proportions, colours
and markings, drawn from the real animal.
"""
import math

import ik
from ik import hx, mix, shade
from kit import (BLACK, WHITE, C, alpha, Body, eye, fin, finpal, pectorals, tail, eel, interp, q2, fish_disp,
                 crab_disp, pose, display, GEN)


def stations(x0, x1, depth, hw, peak=0.55, tailf=0.24, nosef=0.45, ymid=8.0, belly=0.5, n=9, hump=0.0):
    """Body profile from tail (x0) to nose (x1): depth rises from tailf to 1 at `peak` (0..1 along the body) and
    falls to nosef at the nose; `belly` is the share of the depth below ymid; `hump` lifts the back near the head."""
    st = []
    for i in range(n):
        u = i / (n - 1)
        if u <= peak:
            f = tailf + (1 - tailf) * math.sin(math.pi / 2 * u / peak)
        else:
            f = nosef + (1 - nosef) * math.cos(math.pi / 2 * (u - peak) / (1 - peak))
        d = depth * f
        bot = ymid - d * belly
        top = ymid + d * (1 - belly)
        if hump and u > peak:
            top += hump * math.sin(math.pi * (u - peak) / (1 - peak))
        st.append((x0 + (x1 - x0) * u, bot, top, hw * (0.35 + 0.65 * f)))
    return st


def cuts_for(x0, x1, n=8):
    xs = [q2(x0 + (x1 - x0) * i / n) for i in range(n + 1)]
    out = []
    for x in xs:
        if not out or x > out[-1]:
            out.append(x)
    out[0], out[-1] = x0, x1
    return out


class Fish:
    """A side-on fish: body plus helpers placing fins along it by `u` (0 = tail end, 1 = nose)."""

    def __init__(self, it, x0, x1, depth, hw, pal, pattern=None, peak=0.55, tailf=0.24, nosef=0.45, belly=0.5,
                 ymid=8.0, bands=(0.3, 0.72), mouth=0.9, mouth_t=0.4, gill=True, scales=0.0, segs=8, hump=0.0,
                 shimmer=None):
        self.it, self.x0, self.x1, self.pal = it, x0, x1, pal
        st = stations(x0, x1, depth, hw, peak, tailf, nosef, ymid, belly, hump=hump)
        cuts = cuts_for(x0, x1, segs)
        b, t, _ = interp(st, x1 - 0.5)
        mouth_arg = (q2(b + (t - b) * mouth_t) + 0.01, mouth) if mouth else None
        self.B = Body(it, st, cuts, pal, bands=bands, pattern=pattern, mouth=mouth_arg,
                      gill=(x0 + (x1 - x0) * 0.78) if gill else None, scales=scales, shimmer=shimmer).build()

    def x(self, u):
        return self.x0 + (self.x1 - self.x0) * u

    def top(self, u):
        return self.B.top(self.x(u))

    def bot(self, u):
        return self.B.bot(self.x(u))

    def mid(self, u):
        return (self.top(u) + self.bot(u)) / 2

    def hw(self, u):
        return self.B.hw(self.x(u))

    def dorsal(self, u, rows, pal, dy=-0.5):
        fin(self.it, self.x(u), self.top(u) + dy, rows, pal)

    def anal(self, u, rows, pal, dy=0.5):
        """rows drawn as they hang: rows[0] is the row next to the body."""
        h = len(rows)
        fin(self.it, self.x(u), self.bot(u) - h + dy, rows[::-1], pal)

    def tail(self, kind, pal, s=1.0, dy=0.0):
        tail(self.it, kind, self.x0 + 0.5, self.mid(0.0) + dy, pal, s=s)

    def pect(self, u, rows, pal, t=0.35, ang=28):
        x = self.x(u)
        y = self.bot(u) + (self.top(u) - self.bot(u)) * t
        pectorals(self.it, x, y, self.hw(u), rows, pal, ang=ang)

    def eye(self, ring, u=None, t=0.62, size=0.9, pupil=BLACK, glint=True, dx=1.3):
        x = self.x1 - dx if u is None else self.x(u)
        y = self.B.bot(x) + (self.B.top(x) - self.B.bot(x)) * t
        eye(self.it, x, y, self.B.hw(x), C(ring), size=size, pupil=pupil, glint=glint)
        return x, y

    def barbels(self, color, n=1, length=1.4, droop=35, t=0.35):
        bc = C(color)
        y = self.B.bot(self.x1 - 0.4) + (self.B.top(self.x1 - 0.4) - self.B.bot(self.x1 - 0.4)) * t
        for k in range(n):
            for sgn in (1, -1):
                z = 8 + sgn * (0.45 + 0.35 * k)
                self.it.wbox((self.x1 - 0.4, y - 0.15 - 0.3 * k, z - 0.15), (self.x1 - 0.4 + length, y + 0.15 - 0.3 * k, z + 0.15),
                             lambda *a: bc, rot={'origin': (self.x1 - 0.3, y - 0.3 * k, z), 'z': -droop - 10 * k})


def bands_pattern(xs, color, width=1.0, tmin=0.0, tmax=1.01, faces=('north', 'south', 'up')):
    """Vertical bars at the given x positions."""
    col = C(color)

    def p(face, x, y, z, t, c, f):
        if face in faces and tmin <= t < tmax:
            for bx in xs:
                if bx <= x < bx + width:
                    return col
    return p


def spots_pattern(color, density=7, tmin=0.35, tmax=0.95, seed=1, faces=('north', 'south', 'up'), x_max=99):
    col = C(color)

    def p(face, x, y, z, t, c, f):
        if face in faces and tmin <= t < tmax and x < x_max:
            ix, iy = int(math.floor(x * 1.0)), int(math.floor(y if face != 'up' else z * 1.3))
            if (ix * 7 + iy * 13 + seed * 5) % density == 0:
                return col
    return p


def combine(*pats):
    def p(face, x, y, z, t, c, f):
        out = None
        for q in pats:
            if q is None:
                continue
            r = q(face, x, y, z, t, out or c, f)
            if r is not None:
                out = r
        return out
    return p


def stripe(color, t0, t1, x_max=99, x_min=-99, faces=('north', 'south')):
    col = C(color)

    def p(face, x, y, z, t, c, f):
        if face in faces and t0 <= t < t1 and x_min <= x < x_max:
            return col
    return p


# ------------------------------------------------------------------------------------------------ sharks

def shark(it, x0, x1, depth, hw, back, belly, line=0.45, pattern=None, snout=0.4, dorsal_h=4, eye_c='#101418',
          gill_c=None, fins=None, belly_t=None):
    """A shark side-on: countershaded torpedo body, tall first dorsal, small second, heterocercal tail (long upper
    lobe), wide pectorals, gill slits."""
    back, belly = C(back), C(belly)
    pal = {'back': back, 'side': mix(back, belly, 0.35), 'belly': belly, 'dark': shade(back, 0.6),
           'mouth': shade(back, 0.4)}
    gc = C(gill_c) if gill_c else shade(back, 0.55)
    gx = [x0 + (x1 - x0) * u for u in (0.70, 0.73, 0.76, 0.79, 0.82)]

    def pat(face, xx, y, z, t, c, f):
        if face in ('north', 'south') and any(g <= xx < g + 0.5 for g in gx) and 0.35 < t < 0.8:
            return gc
        if belly_t is not None and face in ('north', 'south'):
            return belly if t < belly_t else (pal['side'] if t < belly_t + 0.1 else back)
        if pattern:
            return pattern(face, xx, y, z, t, c, f)
    F = Fish(it, x0, x1, depth, hw, pal, pattern=pat, peak=0.6, tailf=0.18, nosef=snout, belly=0.5,
             bands=(line - 0.08, line + 0.08), mouth=1.6, mouth_t=0.25, gill=False, segs=9)
    fp = finpal(shade(back, 0.95), tip=shade(back, 1.12))
    fins = fins or {}
    h = dorsal_h
    rows = [('.' * (h - 1 - i)) + 'o' + '#' * (i + 1) for i in range(h)]
    rows = [r + '+' * 0 for r in rows]
    fin(it, F.x(0.5) - h * 0.5, F.top(0.5) - 0.5, [r.ljust(h + 1, '.')[:h + 1] for r in rows], fp)       # first dorsal
    fin(it, F.x(0.2), F.top(0.2) - 0.4, ['.o', 'o#'], fp)                                                # second dorsal
    fin(it, F.x(0.22), F.bot(0.22) - 1.6, ['o#', '.o'], fp)                                              # anal
    fin(it, F.x(0.45), F.bot(0.45) - 1.4, ['#+', 'o.'], fp)                                              # pelvic
    # heterocercal tail: long upper lobe swept up, short lower lobe
    tm = F.mid(0.0)
    tail_rows = fins.get('tail') or ['o.....',
                                     '.o#...',
                                     '..##..',
                                     '...##.',
                                     '....##',
                                     '...+##',
                                     '..o#+.',
                                     '.o#...']
    tail(it, tail_rows, x0 + 0.6, tm + 1.6, fp)
    pectorals(it, F.x(0.68), F.bot(0.68) + 0.8, F.hw(0.68), ['oo##', '.o##', '..o#'], fp, ang=30)
    F.eye(eye_c, t=0.68, size=0.8, pupil=hx('#05080a'), glint=True, dx=(x1 - x0) * 0.1)
    return F


# ------------------------------------------------------------------------------------------------ flatfish

def flatfish(it, body, edge, spots=None, eyes_left=True, spot_c='#3a2a1c', length=13.0, depth=8.0, fin_c=None,
             seed=3):
    """A flatfish seen from its eyed side (the side the GUI and frames show): a thin oval disc in the x/y plane with
    a fringe of dorsal and anal fin all round, both eyes on top, a small tail. eyes_left: the head points to -x
    (olive flounder), else +x (dab, a right-eyed flatfish)."""
    body, edge = C(body), C(edge)
    fc = C(fin_c) if fin_c else shade(body, 0.85)
    x0, x1 = 8 - length / 2, 8 + length / 2
    cx, cy = 8.0, 8.0
    rx, ry = length / 2 - 1.0, depth / 2

    def inside(x, y, grow=0.0):
        return ((x - cx) / (rx + grow)) ** 2 + ((y - cy) / (ry + grow)) ** 2 <= 1

    def disc(face, x, y, z, f):
        if face == 'north':
            return hx('#f0ece0')                                 # the blind side is white
        if face in ('east', 'west', 'up', 'down'):
            return edge
        if spots and (int(math.floor(x * 1.3)) * 7 + int(math.floor(y * 1.3)) * 11 + seed) % spots == 0:
            return C(spot_c)
        return body if (int(x) + int(y)) % 5 else shade(body, 0.92)
    # disc as stacked boxes following the ellipse
    n = 8
    for i in range(n):
        y0 = cy - ry + 2 * ry * i / n
        y1 = cy - ry + 2 * ry * (i + 1) / n
        ym = (y0 + y1) / 2
        half = rx * math.sqrt(max(0.0, 1 - ((ym - cy) / ry) ** 2))
        it.wbox((cx - half, y0, 7.55), (cx + half, y1, 8.45), disc, name='disc')
    # fin fringe above and below
    fp = finpal(fc, tip=shade(fc, 1.2))
    for sgn in (1, -1):
        rows = ['o#o#o#o#o#', '#+#+#+#+#+'] if sgn > 0 else ['#+#+#+#+#+', 'o#o#o#o#o#']
        fin(it, cx - 5.0, cy + sgn * (ry - 0.3) - (0 if sgn > 0 else 2), rows, fp)
    # tail
    tx = x1 - 0.5 if eyes_left else x0 + 0.5
    rows = ['oo', '#+', '#+', 'oo']
    fin(it, tx - (0 if eyes_left else 2), cy - 2, [r if eyes_left else r[::-1] for r in rows], fp)
    # eyes on the visible side, near the head
    hx_ = x0 + 2.2 if eyes_left else x1 - 2.2
    for dy in (0.9, -0.6):
        ex = hx_ + (0.6 if dy < 0 else 0) * (1 if eyes_left else -1)
        it.wbox((ex - 0.45, cy + dy - 0.45, 8.3), (ex + 0.45, cy + dy + 0.45, 8.9),
                lambda face, x, y, z, f: (BLACK if face == 'south' and (x - ex) * (1 if eyes_left else -1) < 0.1 else hx('#d8cca0'))
                if face != 'north' else edge, density=3, frame=False, name='eye')
    # mouth
    mx = x0 + 0.8 if eyes_left else x1 - 1.6
    it.wbox((mx, cy - 0.4, 8.3), (mx + 0.8, cy, 8.6), lambda *a: shade(body, 0.6), name='mouth')


def flat_disp(gui_scale=0.9):
    """Flat things shown from their top side (rays, flatfish): face-on in the GUI with a slight turn."""
    return display(GEN,
                   gui={'rotation': pose(-20, 18, 30), 'translation': [0, 0, 0], 'scale': [gui_scale] * 3},
                   firstperson_righthand={'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.6] * 3},
                   thirdperson_righthand={'rotation': [0, 0, -35], 'translation': [0, 3, 1], 'scale': [0.55] * 3},
                   ground={'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5] * 3},
                   head={'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
                   fixed={'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.85] * 3})


def upright_disp(gui_scale=0.85, yaw=200, tilt=6):
    """Upright things (sea horse, frog): standing in the GUI, flat and side-on in frames."""
    return display(GEN,
                   gui={'rotation': pose(yaw, tilt, 0), 'translation': [0, 0, 0], 'scale': [gui_scale] * 3},
                   firstperson_righthand={'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.6] * 3},
                   thirdperson_righthand={'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55] * 3},
                   ground={'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5] * 3},
                   head={'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
                   fixed={'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.85] * 3})


def top_disp(gui_scale=0.85, yaw=205, tilt=48, roll=30):
    """A fish whose shape reads from above (hammerhead, saw shark): the GUI looks down on its back."""
    return fish_disp(gui_scale=gui_scale, yaw=yaw, tilt=tilt, roll=roll)


# ------------------------------------------------------------------------------------------------ crustaceans

def crayfish(it, shell, dark, light, claw, claw_l, eye_c=BLACK, hairy=None):
    """Side profile, head toward +x: carapace, segmented tail and fan, two big claws, legs, antennae (the crystal
    crawdad's build, natural colours). hairy: colour of mitten-crab style fur on the claws."""
    shell, dark, light, claw, claw_l = (C(v) for v in (shell, dark, light, claw, claw_l))

    def sh(face, x, y, z, f):
        if face == 'up':
            return light if abs(z - 8) < 0.6 else shell
        if face == 'down':
            return dark
        return shell if y > 7.2 else shade(shell, 0.85)
    it.wbox((8.0, 6.6, 6.5), (12.5, 9.4, 9.5), sh, name='carapace')
    it.wbox((12.5, 7.0, 7.0), (14.0, 8.9, 9.0), sh, name='head')
    it.wbox((14.0, 7.9, 7.7), (15.0, 8.4, 8.3), lambda *a: light, name='rostrum')
    for x0, x1, h, w, dy in ((6.3, 8.0, 2.4, 2.8, 0.0), (4.8, 6.3, 2.1, 2.5, -0.25), (3.4, 4.8, 1.8, 2.2, -0.6),
                             (2.2, 3.4, 1.5, 1.9, -1.0)):
        it.wbox((x0, 7.0 + dy, 8 - w / 2), (x1, 7.0 + dy + h, 8 + w / 2),
                lambda face, x, y, z, f, x0=x0: (dark if face == 'up' and x - x0 < 0.4 else sh(face, x, y, z, f)), name='tail')
    fan = lambda face, x, y, z, f: (light if x < 1.0 else shell) if face in ('up', 'down') else dark
    it.wbox((0.4, 5.9, 7.2), (2.6, 6.4, 8.8), fan, rot={'origin': (2.4, 6.1, 8), 'z': -12}, name='fan')
    for sgn in (1, -1):
        it.wbox((0.7, 5.9, 8 + sgn * 1.1 - 0.6), (2.6, 6.4, 8 + sgn * 1.1 + 0.6), fan,
                rot={'origin': (2.4, 6.1, 8 + sgn * 1.1), 'z': -12, 'y': sgn * 28}, name='fan')
    fur = C(hairy) if hairy else None
    cm = lambda face, x, y, z, f: claw_l if face == 'up' else (claw if face in ('south', 'north', 'east') else shade(claw, 0.8))

    def hand(face, x, y, z, f):
        if fur is not None and face != 'down' and (int(math.floor(x * 2)) + int(math.floor(y * 2))) % 3 != 0:
            return fur
        return cm(face, x, y, z, f)
    for sgn in (1, -1):
        z = 8 + sgn * 1.6
        m0 = it.mark()
        it.wbox((11.5, 6.8, z - 0.45), (13.8, 7.6, z + 0.45), cm, name='arm')
        it.wbox((13.6, 6.3, z - 1.0), (15.6, 8.0, z + 1.0), hand, name='hand')
        zo = sorted((z + sgn * 0.25, z + sgn * 1.0))
        zi = sorted((z - sgn * 1.0, z - sgn * 0.4))
        it.wbox((15.6, 6.5, zo[0]), (17.4, 7.8, zo[1]), cm, name='finger')
        it.wbox((15.6, 6.6, zi[0]), (16.9, 7.6, zi[1]), cm, name='thumb')
        it.transform(it.since(m0), rot=(0, -sgn * 30, 0), origin=(11.5, 7.0, z))
    lm = lambda face, x, y, z, f: shell if face != 'down' else dark
    for lx in (9.0, 10.3, 11.6):
        for sgn in (1, -1):
            z = 8 + sgn * 1.3
            it.wbox((lx - 0.25, 4.4, z - 0.25), (lx + 0.25, 6.8, z + 0.25), lm,
                    rot={'origin': (lx, 6.8, z), 'x': -sgn * 45, 'z': -12})
    for sgn in (1, -1):
        z = 8 + sgn * 0.5
        it.wbox((13.8, 8.5, z - 0.15), (17.0, 8.8, z + 0.15), lambda *a: claw,
                rot={'origin': (13.8, 8.65, z), 'z': 32, 'y': -sgn * 10})
        it.wbox((13.3, 8.8, 8 + sgn * 0.8 - 0.3), (13.9, 9.4, 8 + sgn * 0.8 + 0.3),
                lambda face, x, y, z, f: C(eye_c) if face != 'down' else dark, frame=False, name='eye')


def crab(it, shell, dark, pale, claw, fur=None, legs=None, width=8.0):
    """Front view (faces +z): wide carapace, raised claws (furry 'mittens' with fur=), stalk eyes, walking legs."""
    shell, dark, pale, claw = C(shell), C(dark), C(pale), C(claw)
    fc = C(fur) if fur else None
    lc = C(legs) if legs else shade(shell, 0.85)
    hw = width / 2

    def cara(face, x, y, z, f):
        if face == 'down':
            return dark
        if face == 'up':
            return shade(shell, 1.1) if (int(x) + int(z)) % 3 == 0 else shell
        if face == 'south' and y < 6.4:
            return pale
        return shell if (int(x * 1.5) + int(y * 1.5)) % 4 else shade(shell, 0.9)
    it.wbox((8 - hw, 5.5, 5.5), (8 + hw, 8.8, 10.5), cara, name='carapace')
    it.wbox((8 - hw + 1, 8.8, 6.2), (8 + hw - 1, 9.6, 9.8), cara, name='dome')
    for ex in (7.0, 9.0):
        it.wbox((ex - 0.25, 8.8, 10.2), (ex + 0.25, 10.0, 10.7), lambda *a: shell)
        it.wbox((ex - 0.45, 9.9, 10.0), (ex + 0.45, 10.7, 10.9), lambda face, x, y, z, f: BLACK if face != 'down' else dark,
                density=2, frame=False, name='eye')

    def clawm(face, x, y, z, f):
        if fc is not None and face in ('south', 'north', 'east', 'west') and y < 11.6 and \
                (int(math.floor(x * 2)) + int(math.floor(y * 2))) % 3 != 0:
            return fc
        if face == 'up':
            return shade(claw, 1.12)
        if face == 'down':
            return dark
        return claw if y < 12.6 else shade(claw, 0.55)
    for sgn in (1, -1):
        X = (lambda v: v) if sgn > 0 else (lambda v: 16 - v)

        def bx(x0, y0, z0, x1, y1, z1, m=clawm, **kw):
            a, b = sorted((X(x0), X(x1)))
            it.wbox((a, y0, z0), (b, y1, z1), m, **kw)
        bx(8 + hw - 0.5, 7.0, 8.0, 8 + hw + 1.2, 8.2, 9.4)
        bx(8 + hw - 0.2, 8.2, 8.2, 8 + hw + 1.2, 10.4, 9.4)
        bx(8 + hw - 1.0, 10.4, 7.8, 8 + hw + 1.8, 12.8, 10.0)
        bx(8 + hw - 1.0, 12.8, 8.2, 8 + hw, 14.0, 9.6)
        bx(8 + hw + 0.6, 12.8, 8.2, 8 + hw + 1.8, 14.4, 9.6)
        for lz in (5.8, 7.4, 9.0, 10.4):
            m0 = it.mark()
            bx(8 + hw - 0.3, 5.8, lz - 0.3, 8 + hw + 3.2, 6.4, lz + 0.3,
               m=lambda face, x, y, z, f: dark if face == 'down' else lc)
            it.transform(it.since(m0), rot=(0, 0, -sgn * 30), origin=(X(8 + hw - 0.3), 6.1, lz))


# ------------------------------------------------------------------------------------------------ turtles

def turtle(it, shell, shell_dark, skin, skin_dark, belly, head_len=2.6, snout=None, ridges=False, soft=False,
           tail_len=3.0, beak=None):
    """A turtle walking toward +x, seen from the side and above: domed carapace (soft: flat and leathery with a
    pale rim), head on a neck, four legs, a tail (ridges: the snapping turtle's keeled shell and saw-toothed tail)."""
    shell, shell_dark, skin, skin_dark, belly = (C(v) for v in (shell, shell_dark, skin, skin_dark, belly))

    def sh(face, x, y, z, f):
        if face == 'down':
            return belly
        if soft:
            if face == 'up':
                r = ((x - 7.5) / 5.6) ** 2 + ((z - 8) / 4.6) ** 2
                if r > 0.75:
                    return shade(shell, 1.18)                       # the soft pale rim
                return shell_dark if (int(x * 1.2) * 3 + int(z * 1.2) * 5) % 9 == 0 else shell
            return shade(shell, 0.9)
        if face == 'up':
            ix, iz = int(math.floor(x / 1.6)), int(math.floor(z / 1.6))
            c = shell if (ix + iz) % 2 else shade(shell, 0.88)
            if ridges and (abs(z - 8) < 0.4 or abs(z - 6.2) < 0.3 or abs(z - 9.8) < 0.3):
                return shell_dark
            return c
        return shell_dark if y < 7.0 else shell
    if soft:
        it.wbox((2.0, 6.0, 3.4), (13.0, 7.2, 12.6), sh, name='carapace')
        it.wbox((3.5, 7.2, 4.6), (11.5, 7.9, 11.4), sh, name='carapace_top')
    else:
        it.wbox((2.5, 6.0, 3.8), (12.5, 8.4, 12.2), sh, name='carapace')
        it.wbox((3.8, 8.4, 5.0), (11.2, 9.8, 11.0), sh, name='dome')
        if ridges:
            for zz in (6.2, 8.0, 9.8):
                for xx in (4.5, 6.8, 9.1):
                    it.wbox((xx, 9.7, zz - 0.35), (xx + 1.6, 10.3, zz + 0.35), lambda *a: shell_dark, name='keel')
    sk = lambda face, x, y, z, f: skin_dark if face in ('up',) and (int(x * 2) + int(z * 2)) % 3 == 0 else (
        skin if face != 'down' else shade(skin, 0.8))
    hx0 = 12.5 if not soft else 12.8
    it.wbox((hx0, 6.4, 7.0), (hx0 + 1.6, 7.8, 9.0), sk, name='neck')
    it.wbox((hx0 + 1.4, 6.2, 6.7), (hx0 + 1.4 + head_len, 8.2, 9.3), sk, name='head')
    if snout:
        it.wbox((hx0 + 1.4 + head_len, 6.9, 7.6), (hx0 + 2.6 + head_len, 7.5, 8.4), lambda *a: C(snout), name='snout')
    if beak:
        it.wbox((hx0 + 1.4 + head_len - 0.4, 6.0, 7.2), (hx0 + 1.8 + head_len, 7.0, 8.8), lambda *a: C(beak), name='beak')
    for sgn in (1, -1):
        it.wbox((hx0 + 1.4 + head_len - 1.2, 7.4, 8 + sgn * 1.25 - 0.3), (hx0 + 1.4 + head_len - 0.6, 8.0, 8 + sgn * 1.25 + 0.3),
                lambda face, x, y, z, f: BLACK if face != 'down' else skin_dark, frame=False, name='eye')
    for lx, sgn in ((11.0, 1), (11.0, -1), (4.0, 1), (4.0, -1)):
        z = 8 + sgn * 4.0
        it.wbox((lx - 0.9, 4.6, z - 0.7), (lx + 0.9, 6.6, z + 0.7), sk, rot={'origin': (lx, 6.4, z), 'x': -sgn * 25}, name='leg')
    tm = (lambda face, x, y, z, f: skin_dark if face == 'up' and int(x * 2) % 2 == 0 else skin) if ridges else sk
    it.wbox((2.5 - tail_len, 6.4, 7.6), (2.6, 7.2, 8.4), tm, rot={'origin': (2.5, 6.8, 8), 'z': 10}, name='tail')


def turtle_disp(gui_scale=0.85):
    return crab_disp(gui_scale=gui_scale, yaw=-35, tilt=28)
