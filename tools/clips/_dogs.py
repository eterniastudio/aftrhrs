"""Shared dog kit (phase 2): every dog breed's clips/<model>.py builds its clips from here, then adds its own signature
moves. Not a model module itself (gen_creatures only loads clips/<model>.py).

All values are Blockbench convention (see animkit): rotation x+ pitches the front of a bone up (head up, leg forward,
a hanging tail's tip back), y+ turns towards -x, z+ raises the +x side; positions in model px, y up.

Poses are dicts {(bone, channel): (x, y, z)}. `Dog.at(base, **deltas)` adds deltas to a base pose (the idle, sit or
sleep pose sampled from the model's own clips), `Dog.seq(clip, [(t, pose), ...])` eases through key poses, and
oscillations are layered on top with `clip.rot(bone, fn, add=True)`. Every clip starts and ends on the pose of the
posture it belongs to (idle at 0 for standing one-shots, the sit hold for sitting loops, the sleep loop for lying
loops), so the cross-fades in game stay short and clean.
"""
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from animkit import Clip, decay, keys, pulse, smooth, step, wave  # noqa: E402,F401

LEGS = ('left_front_leg', 'right_front_leg', 'left_back_leg', 'right_back_leg')
REST = {'rotation': (0.0, 0.0, 0.0), 'position': (0.0, 0.0, 0.0), 'scale': (1.0, 1.0, 1.0)}


def env(t, t0, t1, ramp=0.15):
    """1 between t0 and t1 with smooth ramps (fades an oscillation in and out)."""
    if t <= t0 or t >= t1:
        return 0.0
    return min(step(t, t0, t0 + ramp), 1 - step(t, t1 - ramp, t1))


def osc(t, hz, t0=0.0, phase=0.0):
    return math.sin(2 * math.pi * (hz * (t - t0) + phase))


class Dog:
    """One dog model: its bones, rest poses and breed parameters.

    voice   vanilla wolf sound set prefix for clip sounds ('entity.wolf', 'entity.wolf_cute' ...); vp its pitch
    wag     tail wag axis: 'y' (a tail that points back) or 'z' (a tail that stands up)
    ears    'up' (pricked) or 'drop' (hanging)
    tempo   >1 = slower, heavier timing (Saint Bernard)
    """

    def __init__(self, m, voice='entity.wolf', vp=1.0, wag='y', ears='up', tempo=1.0, wag_amp=1.0):
        self.m = m
        self.voice, self.vp, self.wag_axis, self.ears, self.tempo, self.wag_amp = voice, vp, wag, ears, tempo, wag_amp
        self.B = tuple(m.pivot['body'])
        self.s = self.B[1] / 8.5                                   # size relative to the husky
        self.lower = {leg: leg.replace('_leg', '_lower') for leg in LEGS if m.has_bone(leg.replace('_leg', '_lower'))}
        self.tails = [b for b in ('tail', 'tail_tip') if m.has_bone(b)]
        self.tongue = m.has_bone('tongue')
        self.rest = self.full(m.pose('idle', 0))
        self.sit = self.full(m.pose('sit', 'end'))
        self.sleep_len = m.length('sleep')
        self.sleep = self.full(m.pose('sleep', 0))

    # ------------------------------------------------------------------------------------------- poses
    def full(self, pose):
        """A pose with every core bone present (absent = rest), so blends always cover the same bones."""
        out = dict(pose)
        for b in ('root', 'body', 'h_head', 'jaw', 'left_ear', 'right_ear') + LEGS + tuple(self.lower.values()) + tuple(self.tails):
            if self.m.has_bone(b):
                out.setdefault((b, 'rotation'), (0.0, 0.0, 0.0))
        for b in ('root', 'body'):
            out.setdefault((b, 'position'), (0.0, 0.0, 0.0))
        if self.tongue:
            out.setdefault(('tongue', 'rotation'), (0.0, 0.0, 0.0))
            out.setdefault(('tongue', 'position'), (0.0, 0.0, 0.0))
        out.pop(('eyes', 'scale'), None)
        return out

    def at(self, base, *ds, **kw):
        """base + deltas (dicts and / or keywords). bone=(x, y, z) adds rotation, bone_p=(x, y, z) adds position,
        bone_s multiplies scale; dict keys may also be (bone, channel)."""
        out = dict(base)
        items = [kv for d in ds for kv in (d or {}).items()] + list(kw.items())
        for k, v in items:
            if isinstance(k, tuple):
                bone, ch = k
            elif k.endswith('_p'):
                bone, ch = k[:-2], 'position'
            elif k.endswith('_s'):
                bone, ch = k[:-2], 'scale'
            else:
                bone, ch = k, 'rotation'
            if not self.m.has_bone(bone):
                continue
            cur = out.get((bone, ch), REST[ch])
            if ch == 'scale':
                out[(bone, ch)] = tuple(c * x for c, x in zip(cur, v))
            else:
                out[(bone, ch)] = tuple(c + x for c, x in zip(cur, v))
        return out

    def seq(self, c, frames, ease='smooth'):
        """Eases every bone through [(t, pose)] (pose-to-pose, smoothstep between key poses)."""
        keyset = set()
        for _, p in frames:
            keyset |= set(p)
        for key in sorted(keyset):
            pts = [(t, p.get(key, REST[key[1]])) for t, p in frames]
            c.tracks[key] = (lambda pts: lambda t: keys(t, pts, ease))(pts)
        self.hang_tag(c)
        return c

    def hang_tag(self, c):
        """The collar rune tag hangs: it counter-rotates the body's pitch / roll (as the model's own sit does)."""
        if not self.m.has_bone('rune_tag'):
            return

        def fn(t):
            f = c.tracks.get(('body', 'rotation'))
            b = f(t) if f else (0, 0, 0)
            return (-0.85 * b[0], 0, -0.85 * b[2])
        c.tracks[('rune_tag', 'rotation')] = fn

    def loop_from(self, c, clip, speed=1.0, bones=None, t0=0.0):
        """Tracks of a looping clip of the model, wrapped in time (run legs under zoomies, the sleep loop under dreams)."""
        from animkit import sample
        src = self.m.clips[clip]
        L = self.m.length(clip)
        for bone, chs in (src.get('bones') or {}).items():
            if bones is not None and bone not in bones:
                continue
            for ch, data in chs.items():
                c.tracks[(bone, ch)] = (lambda data, ch: lambda t: sample(data, (t0 + t * speed) % L, REST[ch]))(data, ch)
        return c

    def T(self, x):
        """Timing scaled by the breed's tempo (on the 20 fps key grid, so loops close exactly)."""
        return round(x * self.tempo * 20) / 20

    # ------------------------------------------------------------------------------------------- body helpers
    def world(self, pitch, dy, py, pz):
        """World (y, z) of a body-attached point after the body pitches by `pitch` deg about its pivot and drops dy."""
        by, bz = self.B[1], self.B[2]
        ry, rz = py - by, pz - bz
        th = math.radians(pitch)
        return by + ry * math.cos(th) - rz * math.sin(th) + dy, bz + ry * math.sin(th) + rz * math.cos(th)

    def leg_len(self, leg):
        return self.m.pivot[leg][1]

    def planted(self, pitch, back='vertical', front='reach', dy=None):
        """Leg rotations (deltas) that keep the feet on the ground for a body pitch: back legs vertical in the world
        (dy chosen so they just touch) and front legs angled forward to reach the ground (a bow) or the reverse.
        Returns (deltas, dy)."""
        out = {}
        if dy is None:
            ref = 'left_back_leg' if back == 'vertical' else 'left_front_leg'
            y, _ = self.world(pitch, 0, *self.m.pivot[ref][1:])
            dy = self.leg_len(ref) - y
        for leg in LEGS:
            y, _ = self.world(pitch, dy, *self.m.pivot[leg][1:])
            L = self.leg_len(leg)
            is_back = 'back' in leg
            mode = back if is_back else front
            if mode == 'vertical' or y >= L:
                phi = 0.0
            else:
                phi = math.degrees(math.acos(max(-1.0, min(1.0, y / L))))
                if mode == 'reach_back':
                    phi = -phi
            out[leg] = (phi - pitch, 0, 0)
        return out, dy

    def wag(self, a):
        """A tail wag rotation delta of amplitude a (deg) on the breed's wag axis."""
        a *= self.wag_amp
        return (0, a, a * 0.25) if self.wag_axis == 'y' else (0, a * 0.25, a)

    def add_wag(self, c, hz, amp, t0=0.0, t1=None, ramp=0.2, lift=0.0):
        """Layers a tail wag (tip lags the base) over whatever the tail does; for loops pick hz so hz * L is whole."""
        t1 = c.L if t1 is None else t1
        whole = t0 <= 0 and t1 >= c.L
        for i, b in enumerate(self.tails):
            g = 1.0 + 0.5 * i
            lag = 0.12 * i

            def fn(t, g=g, lag=lag):
                e = 1.0 if whole else env(t, t0, t1, ramp)
                w = self.wag(amp * g * e * osc(t, hz, 0, -lag))
                return (w[0] - lift * e * (1 - 0.5 * (b == 'tail_tip')), w[1], w[2])
            c.rot(b, fn, add=True)
        return c

    def ear(self, side, flat=0.0, perk=0.0, out=0.0):
        """Rotation delta for one ear: flat (pinned back, happy / pet), perk (forward, alert), out (lift sideways)."""
        sgn = 1 if side == 'left' else -1
        if self.ears == 'up':
            return (flat * 30 - perk * 12, 0, sgn * out * 14)
        return (-flat * 14 + perk * 10, 0, -sgn * out * 18)

    def ears_d(self, flat=0.0, perk=0.0, out=0.0):
        return {'left_ear': self.ear('left', flat, perk, out), 'right_ear': self.ear('right', flat, perk, out)}

    def pant(self, amt=1.0):
        d = {'jaw': (-13 * amt, 0, 0)}
        if self.tongue:
            d['tongue'] = (-16 * amt, 0, 0)
            d['tongue_p'] = (0, -0.2 * amt, -0.8 * amt)
        return d

    def snd(self, c, t, event, v=1.0, p=1.0):
        """A clip sound: bare names are the species' own sfx events, 'voice.<x>' the breed's wolf set (pitched)."""
        if event.startswith('voice.'):
            c.sound(t, 'minecraft:%s.%s' % (self.voice, event[6:]), v, round(p * self.vp, 3))
        else:
            c.sound(t, event, v, p)
        return c


# =============================================================================================== shared clips
def idle_wag(d, L=2.0, hz=None, body=0.0, name='idle_wag'):
    """Standing loop with the owner near: a quick happy wag, panting, ears soft; `body` > 0 makes the hips swing with
    the tail (the golden retriever wags with its whole body)."""
    L = d.T(L)
    hz = hz or round(4.5 / d.tempo * L) / L
    c = Clip(d.m, name, L, 'loop')
    base = d.at(d.rest, d.pant(1.0), d.ears_d(flat=0.35))
    d.seq(c, [(0, base), (L, base)])
    d.add_wag(c, hz, 26, lift=6)
    c.rot('body', lambda t: (0, -body * 7 * osc(t, hz, 0, 0.05), -body * 2.5 * osc(t, hz, 0, 0.05)), add=True)
    c.rot('h_head', lambda t: (3 + 2 * osc(t, 2 * hz / 2, 0, 0.25), body * 6 * osc(t, hz, 0, 0.1), 3 * wave(t, L)), add=True)
    c.scl('body', lambda t: (1, 1 + 0.018 * osc(t, round(3 * L) / L), 1), add=True)
    # a front paw shuffle halfway (weight shift)
    c.rot('left_front_leg', lambda t: (16 * pulse(t, 0.42 * L, 0.62 * L), 0, 0), add=True)
    return c


def head_tilt(d, name='head_tilt', base=None, loop='once'):
    """The quizzical dog head tilt, one side then the other, ears pricked (a base clip from the standing pose; the
    sitting version `sit_tilt` starts from the sit pose)."""
    base = d.rest if base is None else base
    L = d.T(2.4)
    c = Clip(d.m, name, L, loop)
    d.seq(c, [(0, base), (L, base)])
    z = lambda t: 24 * (overshoot01(t, 0.05 * L, 0.3 * L) - step(t, 0.42 * L, 0.55 * L)) - 20 * (
        step(t, 0.55 * L, 0.68 * L) - step(t, 0.86 * L, L))
    c.rot('h_head', lambda t: (7 * env(t, 0, L, 0.3 * L), 4 * wave(t, L) * env(t, 0, L, 0.2), z(t)), add=True)
    perk = lambda t: env(t, 0.02, L - 0.02, 0.25 * L)
    for side in ('left', 'right'):
        e = d.ear(side, perk=1.0, out=0.4)
        c.rot(side + '_ear', (lambda e: lambda t: tuple(x * perk(t) for x in e))(e), add=True)
    return c


def sit_tilt(d, name='sit_tilt'):
    return head_tilt(d, name, d.sit, 'loop')


def yawn(d, name='yawn', base=None, loop='once'):
    """A big jaw-cracking yawn (mouth only: the eyes stay open), ears back, head up, a lip smack and a shake of the
    ears to finish."""
    base = d.rest if base is None else base
    L = d.T(2.4)
    c = Clip(d.m, name, L, loop)
    wide = d.at(base, d.ears_d(flat=0.8), h_head=(22, 0, 4), jaw=(-34, 0, 0), body_p=(0, 0.3 * d.s, 0))
    if d.tongue:
        wide = d.at(wide, tongue=(-8, 0, 0), tongue_p=(0, 0.1, -0.4))
    d.seq(c, [(0, base), (0.2 * L, d.at(base, h_head=(6, 0, 0), jaw=(-6, 0, 0))), (0.42 * L, wide), (0.6 * L, wide),
              (0.72 * L, d.at(base, h_head=(-4, 0, 0))), (L, base)])
    c.rot('jaw', lambda t: (-5 * (pulse(t, 0.74 * L, 0.8 * L) + pulse(t, 0.81 * L, 0.87 * L)), 0, 0), add=True)
    c.rot('h_head', lambda t: (0, 0, 8 * osc(t, 5) * env(t, 0.86 * L, L, 0.04)), add=True)
    d.snd(c, 0.4 * L, 'ambient_sleepy', 0.35, 1.05)
    return c


def sit_yawn(d, name='sit_yawn'):
    return yawn(d, name, d.sit, 'loop')


def overshoot01(t, t0, t1, amt=0.12):
    from animkit import overshoot
    return overshoot(t, t0, t1, amt)


def sniff(d, name='sniff', L=2.6, sweep=14, low=1.0):
    """Nose to the ground: three sniff bursts while the head sweeps along a scent, one paw step forward."""
    L = d.T(L)
    c = Clip(d.m, name, L)
    pitch = -7 * low
    legs, dy = d.planted(pitch, back='vertical', front='reach_back')
    down = d.at(d.rest, legs, body=(pitch, 0, 0), body_p=(0, dy, 0), h_head=(-36 * low - pitch, 0, 0),
                **d.ears_d(perk=0.6, out=0.2))
    d.seq(c, [(0, d.rest), (0.16 * L, down), (0.78 * L, down), (L, d.rest)])
    c.rot('h_head', lambda t: (0, keys(t, [(0.16 * L, -sweep), (0.45 * L, sweep), (0.75 * L, -sweep * 0.4), (L, 0)]) * env(t, 0.1 * L, L, 0.15 * L), 0), add=True)
    for k, (a, b) in enumerate(((0.2, 0.36), (0.44, 0.58), (0.64, 0.76))):
        c.rot('h_head', (lambda a, b: lambda t: (3.5 * osc(t, 7.5) * env(t, a * L, b * L, 0.05), 0, 0))(a, b), add=True)
        c.rot('jaw', (lambda a, b: lambda t: (-2.5 * pulse(t, a * L, b * L), 0, 0))(a, b), add=True)
        d.snd(c, a * L + 0.03, 'minecraft:entity.fox.sniff', 0.45, round(1.25 * d.vp, 2))
    c.rot('left_front_leg', lambda t: (18 * pulse(t, 0.38 * L, 0.56 * L), 0, 0), add=True)
    d.add_wag(c, 1.6 / d.tempo, 12, 0.1 * L, 0.95 * L)
    return c


def shake(d, name='shake', L=1.9, amp=1.0):
    """The wet-dog shake-off: braced legs, the shake rolls from the head down the body to the tail, ears flapping,
    a settle wobble at the end."""
    L = d.T(L)
    c = Clip(d.m, name, L)
    brace = d.at(d.rest, body_p=(0, -0.5 * d.s, 0), h_head=(-8, 0, 0),
                 left_front_leg=(0, 0, -6), right_front_leg=(0, 0, 6), left_back_leg=(0, 0, -6), right_back_leg=(0, 0, 6))
    s0, s1 = 0.18 * L, 0.78 * L
    d.seq(c, [(0, d.rest), (s0, brace), (s1, brace), (L, d.rest)])
    hz = 4.6 / d.tempo
    e = lambda t, lag: env(t, s0 + lag, s1 + lag * 0.5, 0.12)
    c.rot('h_head', lambda t: (0, 12 * amp * osc(t, hz, s0) * e(t, 0), 30 * amp * osc(t, hz, s0) * e(t, 0)), add=True)
    c.rot('body', lambda t: (0, 4 * amp * osc(t, hz, s0, -0.15) * e(t, 0.05), 20 * amp * osc(t, hz, s0, -0.18) * e(t, 0.05)
                             + 4 * decay(t, s1, 2.2 / d.tempo, 6)), add=True)
    c.pos('body', lambda t: (0, 0.35 * d.s * abs(osc(t, hz, s0)) * e(t, 0.05), 0), add=True)
    for side, sg in (('left', 1), ('right', -1)):
        base_out = 1 if d.ears == 'up' else -1
        c.rot(side + '_ear', (lambda sg: lambda t: (6 * osc(t, hz * 2, s0) * e(t, 0),
                                                    0, base_out * sg * (26 * osc(t, hz, s0, -0.3) * e(t, 0) + 14 * decay(t, s1, 3, 5))))(sg), add=True)
    for i, b in enumerate(d.tails):
        c.rot(b, (lambda i: lambda t: d.wag(30 * (1 + 0.4 * i) * osc(t, hz, s0, -0.3 - 0.1 * i) * e(t, 0.1)))(i), add=True)
    c.rot('jaw', lambda t: (-6 * e(t, 0) * abs(osc(t, hz, s0)), 0, 0), add=True)
    d.snd(c, s0, 'minecraft:entity.wolf.shake', 0.8, round(min(1.4, 0.85 + 0.25 * d.vp), 2))
    return c


def stretch(d, name='stretch', L=3.6):
    """Big morning stretch: front stretch (chest down, rear up, a yawn), then the back stretch (hind legs out behind),
    a little shake of the head to finish."""
    L = d.T(L)
    c = Clip(d.m, name, L)
    p1 = -20
    legs1, dy1 = d.planted(p1, back='vertical', front='reach')
    bow = d.at(d.rest, legs1, body=(p1, 0, 0), body_p=(0, dy1, 0), h_head=(26 - p1 * 0.4, 0, 0),
               **d.ears_d(flat=0.6))
    yawn = d.at(bow, jaw=(-30, 0, 0), h_head=(10, 0, 0))
    p2 = 7
    rear = d.at(d.rest, body=(p2, 0, 0), body_p=(0, 0.2 * d.s, 0), h_head=(14 - p2, 0, 0),
                left_front_leg=(-p2 - 4, 0, 0), right_front_leg=(-p2 - 4, 0, 0),
                left_back_leg=(-42, 0, 0), right_back_leg=(-26, 0, -4), **d.ears_d(flat=0.3))
    for leg, lo in d.lower.items():
        if 'back' in leg:
            rear = d.at(rear, {lo: (20, 0, 0)})
    tl = {(b, 'rotation'): (-18 * (i + 1) * 0.7, 0, 0) for i, b in enumerate(d.tails)}
    bow, yawn, rear = d.at(bow, tl), d.at(yawn, tl), d.at(rear, tl)
    d.seq(c, [(0, d.rest), (0.16 * L, bow), (0.24 * L, yawn), (0.4 * L, yawn), (0.47 * L, bow), (0.6 * L, rear),
              (0.76 * L, rear), (0.88 * L, d.rest), (L, d.rest)])
    c.rot('left_back_leg', lambda t: (-4 * osc(t, 9) * env(t, 0.62 * L, 0.76 * L, 0.05), 0, 0), add=True)   # trembling stretch
    c.rot('h_head', lambda t: (0, 0, 14 * osc(t, 4.5 / d.tempo, 0.86 * L) * env(t, 0.86 * L, L, 0.08)), add=True)
    d.snd(c, 0.26 * L, 'ambient_sleepy', 0.5, 1.0)
    return c


def bow_pose(d, depth=24):
    legs, dy = d.planted(-depth, back='vertical', front='reach')
    bow = d.at(d.rest, legs, body=(-depth, 0, 0), body_p=(0, dy, 0), h_head=(depth * 0.9 + 6, 0, 0),
               **d.ears_d(perk=1.0))
    bow = d.at(bow, d.pant(0.8), {(b, 'rotation'): (-14 * (i + 1), 0, 0) for i, b in enumerate(d.tails)})
    return bow


def play_bow(d, name='play_bow', L=2.0, depth=24, bounces=2):
    """Play bow: chest down, rear up, tail going like mad, a couple of springy front-paw bounces and a playful bark."""
    L = d.T(L)
    c = Clip(d.m, name, L)
    bow = bow_pose(d, depth)
    d.seq(c, [(0, d.rest), (0.18 * L, bow), (0.8 * L, bow), (L, d.rest)])
    for k in range(bounces):
        a = 0.26 + k * 0.5 / max(1, bounces)
        b = a + 0.2
        c.pos('body', (lambda a, b: lambda t: (0, 1.3 * d.s * pulse(t, a * L, b * L), 0))(a, b), add=True)
        c.rot('body', (lambda a, b: lambda t: (4 * pulse(t, a * L, b * L), 0, 0))(a, b), add=True)
    d.add_wag(c, 5.5 / d.tempo, 30, 0.1 * L, 0.95 * L)
    c.rot('h_head', lambda t: (0, 0, 8 * math.sin(math.pi * step(t, 0.3 * L, 0.75 * L))), add=True)
    d.snd(c, 0.3 * L, 'voice.ambient', 0.7, 1.12)
    return c


def scratch_pose(d, base, side='right', reach=1.0):
    """From a sitting pose: one hind leg swung out and up to the side of the neck, the head turned hard into it and
    tilted down to meet the paw, the body leaning away on the other haunch."""
    sg = 1 if side == 'right' else -1          # right = +x
    leg = side + '_back_leg'
    sitleg = base[(leg, 'rotation')]
    pose = d.at(base, {leg: (128 * reach - sitleg[0], 0, sg * 40),
                       'h_head': (-22, -sg * 48, -sg * 30), 'body': (0, sg * 6, 7 * sg)},
                **{side + '_ear': d.ear(side, flat=0.4, out=0.3)})
    if leg in d.lower:
        pose[(d.lower[leg], 'rotation')] = (-12, 0, 0)
    return pose


def scratch_frames(d, c, base, t0, t1, side='right', hz=7.0, sounds=True):
    """Fast scratching strokes between t0 and t1 (leg strokes, head bobbing into the scratch)."""
    leg = side + '_back_leg'
    sg = 1 if side == 'right' else -1
    c.rot(leg, lambda t: (13 * osc(t, hz, t0) * env(t, t0, t1, 0.08), 0, 0), add=True)
    if leg in d.lower:
        c.rot(d.lower[leg], lambda t: (-10 * osc(t, hz, t0, 0.15) * env(t, t0, t1, 0.08), 0, 0), add=True)
    c.rot('h_head', lambda t: (2 * osc(t, hz, t0, 0.3) * env(t, t0, t1, 0.08), 0, -sg * 3 * osc(t, hz, t0, 0.3) * env(t, t0, t1, 0.08)), add=True)
    c.rot('body', lambda t: (0, 0, 1.5 * osc(t, hz, t0) * env(t, t0, t1, 0.08)), add=True)
    if sounds:
        n = int((t1 - t0) * hz / 2)
        for k in range(n):
            d.snd(c, t0 + (2 * k + 0.5) / hz, 'minecraft:entity.wolf.step', 0.18, 1.7)


def scratch(d, name='scratch', L=3.0):
    """Standing: drops onto its haunches, scratches behind the ear with a hind leg, and gets back up."""
    L = d.T(L)
    c = Clip(d.m, name, L)
    sp = scratch_pose(d, d.sit)
    d.seq(c, [(0, d.rest), (0.16 * L, d.sit), (0.24 * L, sp), (0.76 * L, sp), (0.84 * L, d.sit), (L, d.rest)])
    scratch_frames(d, c, d.sit, 0.25 * L, 0.75 * L)
    return c


def sit_scratch(d, name='sit_scratch', L=2.6):
    L = d.T(L)
    c = Clip(d.m, name, L, 'loop')
    sp = scratch_pose(d, d.sit, 'left')
    d.seq(c, [(0, d.sit), (0.14 * L, sp), (0.82 * L, sp), (L, d.sit)])
    scratch_frames(d, c, d.sit, 0.15 * L, 0.8 * L, 'left')
    return c


def sit_pant(d, name='sit_pant', L=2.4):
    """Sitting, panting happily: open mouth, quick breaths, the tail sweeping the floor slowly."""
    L = d.T(L)
    c = Clip(d.m, name, L, 'loop')
    base = d.at(d.sit, d.pant(1.0), d.ears_d(flat=0.25))
    d.seq(c, [(0, base), (L, base)])
    n = round(L * 2.5 / d.tempo)
    c.scl('body', lambda t: (1, 1 + 0.025 * wave(t, L, n), 1 + 0.012 * wave(t, L, n)), add=True)
    c.rot('h_head', lambda t: (1.6 * wave(t, L, n, 0.2), 6 * wave(t, L), 0), add=True)
    c.rot('jaw', lambda t: (-3 * wave(t, L, n, 0.1), 0, 0), add=True)
    d.add_wag(c, 1 / L, 14)
    return c


def sit_wag(d, name='sit_wag', L=2.4):
    """Sitting with its person right there: tail sweeping fast across the floor, a hopeful paw lift, a head tilt."""
    L = d.T(L)
    c = Clip(d.m, name, L, 'loop')
    base = d.at(d.sit, d.pant(0.6), d.ears_d(perk=0.6))
    paw = d.at(base, left_front_leg=(48, 0, 4), h_head=(6, 0, 12))
    if 'left_front_leg' in d.lower:
        paw = d.at(paw, {d.lower['left_front_leg']: (-40, 0, 0)})
    d.seq(c, [(0, base), (0.3 * L, base), (0.42 * L, paw), (0.62 * L, paw), (0.75 * L, base), (L, base)])
    d.add_wag(c, round(L * 4 / d.tempo) / L, 24)
    c.rot('body', lambda t: (0, 0, 1.5 * wave(t, L, round(L * 4 / d.tempo))), add=True)
    return c


def sit_look(d, name='sit_look', L=5.0):
    """Sitting watch: looks one way, then the other, ears following what it hears."""
    L = d.T(L)
    c = Clip(d.m, name, L, 'loop')
    left = d.at(d.sit, h_head=(4, 34, 4), **{'left_ear': d.ear('left', perk=1.0), 'right_ear': d.ear('right', perk=0.3)})
    right = d.at(d.sit, h_head=(-2, -30, -5), **{'left_ear': d.ear('left', perk=0.3), 'right_ear': d.ear('right', perk=1.0)})
    d.seq(c, [(0, d.sit), (0.14 * L, left), (0.4 * L, left), (0.56 * L, right), (0.82 * L, right), (L, d.sit)])
    d.add_wag(c, 1 / L, 8)
    return c


def sleep_dream(d, name='sleep_dream'):
    """Asleep and dreaming: paws paddling in bursts, a twitching ear and tail, muffled dream woofs."""
    L = d.sleep_len * (2 if d.sleep_len < 4 else 1)
    c = Clip(d.m, name, L, 'loop')
    d.loop_from(c, 'sleep')
    for leg, (a, b), hz in (('left_front_leg', (0.18, 0.4), 6.0), ('right_front_leg', (0.18, 0.4), 6.0),
                            ('left_back_leg', (0.55, 0.72), 5.0), ('right_back_leg', (0.55, 0.72), 5.0)):
        ph = 0.5 if leg.startswith('right') else 0.0
        c.rot(leg, (lambda a, b, hz, ph: lambda t: (9 * osc(t, hz, 0, ph) * env(t, a * L, b * L, 0.1), 0, 0))(a, b, hz, ph), add=True)
    c.rot('jaw', lambda t: (-5 * (pulse(t, 0.3 * L, 0.34 * L) + pulse(t, 0.36 * L, 0.4 * L)), 0, 0), add=True)
    c.rot('left_ear', lambda t: (0, 0, 10 * pulse(t, 0.62 * L, 0.66 * L)), add=True)
    for i, b in enumerate(d.tails):
        c.rot(b, (lambda i: lambda t: d.wag(6 * pulse(t, 0.8 * L, 0.86 * L)))(i), add=True)
    d.snd(c, 0.31 * L, 'snore', 0.6, 1.15)          # a muffled dream huff (sleepers never call)
    return c


def sleep_stretch(d, name='sleep_stretch'):
    """Asleep: a long, trembling full-body stretch and a contented sigh, then sinks back into the nap."""
    L = d.sleep_len * (2 if d.sleep_len < 4 else 1.25)
    L = round(L / d.sleep_len) * d.sleep_len
    c = Clip(d.m, name, L, 'loop')
    d.loop_from(c, 'sleep')
    s = lambda t: keys(t, [(0.12 * L, 0.0), (0.3 * L, 1.0), (0.48 * L, 1.0), (0.62 * L, 0.0)])
    for leg in LEGS:
        amt = (16, 0, 0) if 'front' in leg else (-26, 0, 0)
        c.rot(leg, (lambda amt: lambda t: tuple(x * s(t) + (1.5 * osc(t, 8) * s(t) if i == 0 else 0) for i, x in enumerate(amt)))(amt), add=True)
    c.scl('body', lambda t: (1, 1 + 0.05 * pulse(t, 0.62 * L, 0.82 * L), 1 + 0.04 * s(t)), add=True)
    c.rot('h_head', lambda t: (6 * s(t), 0, 0), add=True)
    c.rot('jaw', lambda t: (-12 * pulse(t, 0.3 * L, 0.46 * L), 0, 0), add=True)
    for i, b in enumerate(d.tails):
        c.rot(b, (lambda i: lambda t: (-10 * s(t), 0, 0))(i), add=True)
    d.snd(c, 0.64 * L, 'snore', 0.6, 0.85)
    return c


def pet(d, name='pet', L=2.2, wiggle=1.0):
    """Petted: leans its head up into the hand, ears pinned back happy, tail going, the sweet-spot hind leg thump."""
    L = d.T(L)
    c = Clip(d.m, name, L)
    lean = d.at(d.rest, d.pant(0.9), d.ears_d(flat=1.0), h_head=(20, 0, 14), body=(0, 0, 4), body_p=(0, -0.3 * d.s, 0))
    d.seq(c, [(0, d.rest), (0.16 * L, lean), (0.82 * L, lean), (L, d.rest)])
    c.rot('h_head', lambda t: (0, 0, 6 * wave(t, L, 2) * env(t, 0.16 * L, 0.82 * L, 0.1)), add=True)
    c.rot('right_back_leg', lambda t: (16 * max(0.0, osc(t, 6.0)) * env(t, 0.3 * L, 0.72 * L, 0.06), 0, 6 * env(t, 0.28 * L, 0.74 * L, 0.06)), add=True)
    c.rot('body', lambda t: (0, 4 * wiggle * osc(t, 3.0) * env(t, 0.1 * L, 0.9 * L), 0), add=True)
    d.add_wag(c, 5.5 / d.tempo, 28, 0.05 * L, 0.95 * L, lift=8)
    return c


def sit_pet(d, name='sit_pet', L=2.0):
    """Petted while sitting: head up into the hand, ears back, the tail thumping the floor, one hind leg kicking."""
    L = d.T(L)
    c = Clip(d.m, name, L, 'loop')
    lean = d.at(d.sit, d.pant(0.9), d.ears_d(flat=1.0), h_head=(18, 0, 12))
    d.seq(c, [(0, lean), (L, lean)])
    n = round(L * 5 / d.tempo)
    d.add_wag(c, n / L, 22)
    c.rot('h_head', lambda t: (0, 0, 5 * wave(t, L)), add=True)
    c.rot('right_back_leg', lambda t: (12 * max(0.0, wave(t, L, n)) * pulse(t, 0.2 * L, 0.8 * L), 0, 0), add=True)
    return c


def bond_level(d, name='bond_level', L=2.8, spin=True, height=7.0):
    """Bond level up: a crouch, a leap with a full spin, a springy landing into a play bow and a happy bark."""
    L = d.T(L)
    c = Clip(d.m, name, L)
    crouch = d.at(d.rest, body_p=(0, -1.0 * d.s, 0), h_head=(8, 0, 0), **d.ears_d(perk=1.0))
    tuck = d.at(d.rest, d.pant(1.0), body=(10, 0, 0), h_head=(14, 0, 0), left_front_leg=(50, 0, 0), right_front_leg=(50, 0, 0),
                left_back_leg=(-40, 0, 0), right_back_leg=(-40, 0, 0), **d.ears_d(flat=0.8))
    land = d.at(d.rest, d.pant(1.0), body_p=(0, -0.8 * d.s, 0))
    bow = bow_pose(d, 20)
    t0, t1 = 0.12 * L, 0.42 * L
    d.seq(c, [(0, d.rest), (t0, crouch), (t0 + 0.08 * L, tuck), (t1 - 0.04 * L, tuck), (t1, land), (0.5 * L, d.at(d.rest, d.pant(1))),
              (0.58 * L, bow), (0.8 * L, bow), (L, d.rest)])
    H = height * d.s
    c.pos('root', lambda t: (0, H * math.sin(math.pi * min(1, max(0, (t - t0) / (t1 - t0)))) if t0 < t < t1 else 0, 0), add=True)
    if spin:
        c.rot('root', lambda t: (0, 360 * step(t, t0 + 0.02 * L, t1 - 0.01 * L), 0), add=True)
    c.pos('body', lambda t: (0, 0.6 * d.s * decay(t, t1, 3.0, 5.0), 0), add=True)
    d.add_wag(c, 5.5 / d.tempo, 30, 0.0, L, lift=8)
    d.snd(c, t1, 'step', 0.9, 0.9)
    d.snd(c, 0.6 * L, 'voice.ambient', 0.8, 1.15)
    return c


SHARED = ('idle_wag', 'head_tilt', 'sit_tilt', 'yawn', 'sit_yawn', 'sniff', 'shake', 'stretch', 'play_bow', 'scratch', 'sit_scratch', 'sit_pant',
          'sit_wag', 'sit_look', 'sleep_dream', 'sleep_stretch', 'pet', 'sit_pet', 'bond_level')


def shared(d, skip=(), **over):
    """Every shared dog clip for this dog (put into the model so breed clips can sample them). over: per-clip kwargs."""
    out = []
    for name in SHARED:
        if name in skip:
            continue
        c = globals()[name](d, **over.get(name, {}))
        d.m.put(c)
        out.append(c)
    return out


# =============================================================================================== tables
TAGS = {
    'sit_scratch': {'p': 'sitting'}, 'sit_pant': {'p': 'sitting'}, 'sit_wag': {'p': 'sitting'}, 'sit_look': {'p': 'sitting'},
    'sit_pet': {'p': 'sitting'}, 'sleep_dream': {'p': 'lying'}, 'sleep_stretch': {'p': 'lying'},
    'sit_tilt': {'p': 'sitting'}, 'sit_yawn': {'p': 'sitting'},
    'head_tilt': {'p': 'standing', 'layer': 'base', 'move': False}, 'yawn': {'p': 'standing', 'layer': 'base', 'move': False},
    'scratch': {'p': 'standing'}, 'bond_level': {'p': 'standing'}, 'pet': {'p': 'standing'},
}


def idle_tables(m, extra_standing=(), extra_sitting=(), extra_lying=()):
    """The dog idle pools: @mood stays the backbone; the owner nearby boosts the wag loops; nothing repeats back to
    back (every flourish has a cooldown longer than the director's 3 - 8 s beat). Loop variants of the sit / sleep
    poses run whole cycles (min / max from the clip lengths)."""
    L = lambda clip, n=1: round(m.length(clip) * n, 2)
    standing = [
        # the mood loop stays the backbone (the flourish pools add ~10 more one-shots on top of these)
        {'clip': '@mood', 'w': 9, 'min': 6, 'max': 16},
        {'clip': 'idle_wag', 'w': 1, 'min': 5, 'max': 10, 'cd': 20, 'when': 'owner_near:6', 'boost': {'happy': 2}},
        {'clip': 'head_tilt', 'w': 1.2, 'cd': 25, 'boost': {'owner_near:5': 2}},
        {'clip': 'sniff', 'w': 1.5, 'cd': 18, 'boost': {'alone': 1.5}},
        {'clip': 'scratch', 'w': 0.8, 'cd': 40},
        {'clip': 'shake', 'w': 0.6, 'cd': 45, 'boost': {'rain': 4}},
        {'clip': 'stretch', 'w': 0.7, 'cd': 60, 'boost': {'dawn': 4}},
        {'clip': 'play_bow', 'w': 0.6, 'cd': 35, 'when': 'owner_near:8,!night', 'boost': {'happy': 3}},
        {'clip': 'roll_over', 'w': 0.35, 'cd': 70, 'when': '!rain', 'boost': {'happy': 3}},
        {'clip': 'look_around', 'w': 0.8, 'cd': 20},
        {'clip': 'yawn', 'w': 0.5, 'cd': 40, 'boost': {'night': 3}},
    ] + list(extra_standing)
    sitting = [
        {'clip': '@sit', 'w': 5, 'min': 8, 'max': 20},
        {'clip': 'sit_pant', 'w': 2, 'min': 6, 'max': 12, 'cd': 15, 'boost': {'hot': 3}},
        {'clip': 'sit_scratch', 'w': 1, 'min': L('sit_scratch'), 'max': L('sit_scratch'), 'cd': 35},
        {'clip': 'sit_wag', 'w': 1, 'min': L('sit_wag', 2), 'max': L('sit_wag', 3), 'cd': 20, 'when': 'owner_near:6', 'boost': {'happy': 2}},
        {'clip': 'sit_look', 'w': 1.5, 'min': L('sit_look'), 'max': L('sit_look'), 'cd': 22, 'boost': {'alone': 2}},
        {'clip': 'sit_tilt', 'w': 0.8, 'min': L('sit_tilt'), 'max': L('sit_tilt'), 'cd': 25, 'boost': {'owner_near:5': 2}},
        {'clip': 'sit_yawn', 'w': 0.6, 'min': L('sit_yawn'), 'max': L('sit_yawn'), 'cd': 30, 'boost': {'night': 3}},
    ] + list(extra_sitting)
    lying = [
        {'clip': '@sleep', 'w': 4, 'min': 25, 'max': 70},
        {'clip': 'sleep_dream', 'w': 1.2, 'min': L('sleep_dream'), 'max': L('sleep_dream', 2), 'cd': 60},
        {'clip': 'sleep_stretch', 'w': 0.8, 'min': L('sleep_stretch'), 'max': L('sleep_stretch'), 'cd': 90},
    ] + list(extra_lying)
    return {'standing': standing, 'sitting': sitting, 'lying': lying}
