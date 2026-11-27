"""Shared clip builders for the herd animals, wildlife and undead (phase 2, p2-wild). Not a model module itself
(gen_creatures only loads clips/<model>.py); the species modules import it.

Conventions (Blockbench, see animkit): rot x+ pitches the front of a bone up (head up, a hanging leg swings forward,
a hanging tail tucks forward, a jaw closes), y+ turns towards -x, z+ raises the +x side; positions in px.
Ears stick out sideways: the left ear (at -x) perks up with z+ and swings forward with y+ (checked in renders: a bone
on the -x side lifts its tip with z+, e.g. the gull's right wing flares out with z+); the right ear mirrored.

Every builder starts from one of the model's own clips (idle / graze / lie / dormant) and adds motion on top
(add=True), so the mod's own idle life (ear twitches, breathing, jaw) keeps running under the flourish.
"""
import math

from animkit import Clip, decay, keys, lerp3, overshoot, pulse, step, transition, wave

ZERO = (0.0, 0.0, 0.0)


def env(t, t0, t1, a=0.25, b=0.35):
    """0 -> 1 (ease in over a s from t0) ... 1 -> 0 (ease out over b s to t1): hold envelope of a flourish."""
    return step(t, t0, t0 + a) * (1 - step(t, t1 - b, t1))


def has(m, *bones):
    return all(b and m.has_bone(b) for b in bones)


def ears(m):
    l, r = m.find('left_ear'), m.find('right_ear')
    return (l, r) if l and r else (None, None)


def add_ears(c, m, fn):
    """fn(t) -> (perk, forward, flop): perk raises both ears (deg), forward swings the tips forward, flop drops them."""
    l, r = ears(m)
    if not l:
        return
    c.rot(l, lambda t: (0, fn(t)[1], fn(t)[0] - fn(t)[2]), add=True)
    c.rot(r, lambda t: (0, -fn(t)[1], -fn(t)[0] + fn(t)[2]), add=True)


def tails(m):
    return [b for b in ('tail', 'tail_tuft', 'tail_mid', 'tail_tip') if m.has_bone(b)]


def add_tail(c, m, fn, lag=0.12, gain=1.35):
    """fn(t) -> (x, y, z) on the first tail bone; later segments follow with lag and more swing."""
    ts = tails(m)
    for i, b in enumerate(ts):
        g = gain ** i
        c.rot(b, (lambda i, g: lambda t: tuple(v * g for v in fn(t - lag * i)))(i, g), add=True)


def legs(m):
    names = ('left_front_leg', 'right_front_leg', 'left_back_leg', 'right_back_leg')
    return [n if m.has_bone(n) else None for n in names]


def once(m, name, L, src='idle', t0=0.0):
    c = Clip(m, name, L, 'once')
    if src:
        c.copy(src, t0=t0)
    return c


def loop(m, name, L, src='idle'):
    c = Clip(m, name, L, 'loop')
    if src:
        c.copy(src)
    return c


def chew(t, rate=1.6, amt=1.0):
    """Side-to-side cud chewing: (open, side) of the jaw."""
    ph = 2 * math.pi * rate * t
    return (-3.5 * amt * max(0.0, math.sin(ph)), 5 * amt * math.sin(ph + 0.6))


# =============================================================================================== quadrupeds


class Quad:
    """A four-legged grazer: tufthorn, wartgrazer, bristleboar, velvetstag (neck + antlers)."""

    def __init__(self, m, size=1.0, head='h_head', neck=None, wobble=(), step_sound='step', snort=None):
        self.m = m
        self.s = size                  # motion scale (bigger, slower animals swing a bit less)
        self.head = head
        self.neck = neck
        self.jaw = m.find('jaw')
        self.lf, self.rf, self.lb, self.rb = legs(m)
        self.wobble = [w for w in wobble if m.has_bone(w)]
        self.step_sound = step_sound
        self.snort = snort             # (sound id, volume, pitch) for head shakes / alerts

    # head motion split over neck + head where there is a neck
    def head_rot(self, c, fn):
        if self.neck:
            c.rot(self.neck, lambda t: tuple(v * 0.55 for v in fn(t)), add=True)
            c.rot(self.head, lambda t: tuple(v * 0.5 for v in fn(t)), add=True)
        else:
            c.rot(self.head, fn, add=True)

    def jiggle(self, c, fn):
        for w in self.wobble:
            c.rot(w, fn, add=True)

    def look_around(self, name='look_around', L=3.6):
        c = once(self.m, name, L)
        yaw = lambda t: keys(t, [(0, 0), (0.45, 0), (1.0, 40), (1.65, 40), (2.25, -36), (2.95, -36), (3.6, 0)])
        self.head_rot(c, lambda t: (7 * env(t, 0.2, L - 0.2, 0.4, 0.5), yaw(t) * self.s, yaw(t) * 0.12))
        add_ears(c, self.m, lambda t: (6 * env(t, 0.3, L - 0.3), 8 * env(t, 0.3, L - 0.3), 0))
        c.rot('body', lambda t: (0, yaw(t) * 0.06, -yaw(t) * 0.03), add=True)
        add_tail(c, self.m, lambda t: (0, 10 * decay(t, 1.0, 1.4, 3.0) - 9 * decay(t, 2.25, 1.4, 3.0), 0))
        return c

    def head_shake(self, name='head_shake', L=1.5):
        """A fly-shaking toss: head rolls hard side to side and settles, ears flap, the coat ripples."""
        c = once(self.m, name, L)
        sh = lambda t: 26 * self.s * decay(t, 0.18, 3.2, 3.3)
        self.head_rot(c, lambda t: (4 * pulse(t, 0.05, 0.4) - 3 * pulse(t, 0.4, 1.2), 0.35 * sh(t), sh(t)))
        add_ears(c, self.m, lambda t: (0, 0, 22 * decay(t, 0.25, 3.2, 3.0)))
        c.rot('body', lambda t: (0, 0, -3 * decay(t, 0.25, 3.2, 3.5)), add=True)
        self.jiggle(c, lambda t: (0, 0, 6 * decay(t, 0.3, 3.2, 3.0)))
        add_tail(c, self.m, lambda t: (-8 * pulse(t, 0.2, 1.0), 16 * decay(t, 0.3, 2.4, 2.5), 0))
        if self.snort:
            c.sound(0.2, *self.snort)
        return c

    def chew_cud(self, name='chew_cud', L=4.0, src='idle'):
        """Loop: head level and a little low, jaw grinding side to side, ears relaxed and drooped."""
        c = loop(self.m, name, L, src)
        if self.jaw:
            c.rot(self.jaw, lambda t: (chew(t, 1.5)[0], chew(t, 1.5)[1], 0))
        self.head_rot(c, lambda t: (-6 + 1.2 * wave(t, L, 6), 3 * wave(t, L, 1), 1.5 * wave(t, L, 6, 0.15)))
        add_ears(c, self.m, lambda t: (0, 0, 9 + 2 * wave(t, L, 2)))
        c.sound(0.4, 'minecraft:entity.generic.eat', 0.12, 0.7)
        c.sound(2.4, 'minecraft:entity.generic.eat', 0.1, 0.65)
        return c

    def rest_stand(self, name='rest_stand', L=6.0):
        """Loop: dozing on its feet: weight on three legs, a hind hoof cocked, head low, ears drooped, slow breath."""
        c = loop(self.m, name, L)
        c.rot('body', lambda t: (-1.2, 0, 1.6 + 0.6 * wave(t, L, 1)), add=True)
        c.pos('body', lambda t: (0.25, -0.2 + 0.12 * wave(t, L, 2), 0), add=True)
        c.scl('body', lambda t: (1 + 0.008 * wave(t, L, 2), 1 + 0.016 * wave(t, L, 2), 1), add=True)
        if self.lb:
            c.rot(self.lb, lambda t: (8, 0, -2), add=True)
            c.pos(self.lb, lambda t: (0, 0.6, 0), add=True)
        self.head_rot(c, lambda t: (-12 + 1.5 * wave(t, L, 1, 0.25), 5 * wave(t, L, 1), 3))
        add_ears(c, self.m, lambda t: (0, -4, 14 + 2 * wave(t, L, 3)))
        return c

    def groom(self, name='groom', L=2.8):
        """Turns its head back along its flank and licks / nibbles an itch, then shakes it off."""
        c = once(self.m, name, L)
        e = lambda t: env(t, 0.1, L - 0.35, 0.55, 0.55)
        self.head_rot(c, lambda t: (-14 * e(t) + 4 * wave(t, 0.4) * e(t), 62 * self.s * e(t), -10 * e(t)))
        if self.jaw:
            c.rot(self.jaw, lambda t: (-6 * max(0, wave(t, 0.4)) * env(t, 0.7, 2.2, 0.1, 0.1), 0, 0), add=True)
        c.rot('body', lambda t: (0, 8 * e(t), 2.5 * e(t)), add=True)
        add_ears(c, self.m, lambda t: (0, 0, 8 * e(t)))
        add_tail(c, self.m, lambda t: (-6 * e(t), -18 * e(t) + 10 * wave(t, 0.6) * e(t), 0))
        if self.lf:
            c.rot(self.lf, lambda t: (0, 0, -4 * e(t)), add=True)
        return c

    def rub(self, name='rub', L=3.4):
        """Leans a flank into a post / tree and rubs up and down, head tipped up in bliss, tail flicking."""
        c = once(self.m, name, L)
        e = lambda t: env(t, 0.1, L - 0.2, 0.5, 0.6)
        c.rot('body', lambda t: (0, 0, (5 + 3.5 * wave(t, 0.85)) * e(t)), add=True)
        c.pos('body', lambda t: (0.9 * e(t), (0.8 * wave(t, 0.85, 1, 0.25)) * e(t), (0.6 * wave(t, 1.7)) * e(t)), add=True)
        self.head_rot(c, lambda t: (14 * e(t), -10 * e(t), -8 * e(t)))
        add_ears(c, self.m, lambda t: (0, -6 * e(t), 12 * e(t)))
        add_tail(c, self.m, lambda t: (-10 * e(t), 14 * wave(t, 0.6) * e(t), 0))
        for lg in (self.lf, self.lb):
            if lg:
                c.rot(lg, lambda t: (0, 0, -6 * e(t)), add=True)
        self.jiggle(c, lambda t: (0, 0, 2.5 * wave(t, 0.85) * e(t)))
        c.sound(0.9, 'minecraft:block.wool.hit', 0.35, 0.6)
        c.sound(2.2, 'minecraft:block.wool.hit', 0.3, 0.55)
        return c

    def stamp(self, name='stamp', L=1.3, leg=None):
        """A front hoof lifts and stamps down hard (impatience / warning); the body jolts."""
        c = once(self.m, name, L)
        lg = leg or self.rf
        up = lambda t: keys(t, [(0, 0), (0.25, 1), (0.42, 1), (0.52, 0), (1.3, 0)], 'smooth')
        if lg:
            c.rot(lg, lambda t: (38 * up(t), 0, 0), add=True)
            c.pos(lg, lambda t: (0, 1.4 * up(t), 0), add=True)
        c.pos('body', lambda t: (0, -0.5 * decay(t, 0.52, 3.0, 6.0) * -1 - 0.35 * pulse(t, 0.48, 0.7), 0), add=True)
        self.head_rot(c, lambda t: (-8 * pulse(t, 0.1, 0.55) + 4 * decay(t, 0.52, 2.5, 4), 0, 0))
        add_ears(c, self.m, lambda t: (10 * env(t, 0.0, 1.1), 10 * env(t, 0.0, 1.1), 0))
        self.jiggle(c, lambda t: (2 * decay(t, 0.52, 3.0, 4), 0, 0))
        c.sound(0.52, self.step_sound, 1.0, 0.8)
        return c

    def tail_swish(self, name='tail_swish', L=1.4):
        """Tail only (overlay over anything): two hard swats at a fly."""
        c = Clip(self.m, name, L, 'once')
        add_tail(c, self.m, lambda t: (-12 * pulse(t, 0.0, L), 30 * (pulse(t, 0.05, 0.45) - pulse(t, 0.45, 0.85) + 0.6 * pulse(t, 0.85, 1.3)), 0), lag=0.06)
        return c

    def ear_flick(self, name='ear_flick', L=0.9):
        """Ears only (overlay): one flicks back, the other swivels."""
        c = Clip(self.m, name, L, 'once')
        l, r = ears(self.m)
        if l:
            c.rot(l, lambda t: (-12 * pulse(t, 0.05, 0.3), 26 * pulse(t, 0.05, 0.35), 0))
            c.rot(r, lambda t: (0, 18 * pulse(t, 0.3, 0.8), -8 * pulse(t, 0.3, 0.8)))
        return c

    def herd_glance(self, name='herd_glance', L=2.4):
        """The head comes up to check on the herd (the director turns it to the herd mate); ears perk, a soft breath."""
        c = once(self.m, name, L)
        e = lambda t: env(t, 0.05, L - 0.1, 0.35, 0.6)
        self.head_rot(c, lambda t: (12 * e(t) + 2 * pulse(t, 0.9, 1.3), 0, 0))
        add_ears(c, self.m, lambda t: (8 * e(t), 14 * e(t), 0))
        c.rot('body', lambda t: (1.5 * e(t), 0, 0), add=True)
        add_tail(c, self.m, lambda t: (0, 8 * decay(t, 0.4, 1.2, 2.5), 0))
        return c

    def alert_up(self, name='alert_up', L=2.8):
        """Notice: the head snaps up high, ears forward, frozen stare at the newcomer, then a slow relax."""
        c = once(self.m, name, L)
        e = lambda t: overshoot(t, 0.0, 0.32, 0.25) * (1 - step(t, L - 0.9, L))
        self.head_rot(c, lambda t: (24 * e(t), 0, 0))
        add_ears(c, self.m, lambda t: (14 * e(t), 22 * e(t), 0))
        c.rot('body', lambda t: (3.5 * e(t), 0, 0), add=True)
        c.pos('body', lambda t: (0, 0.35 * e(t), 0.4 * e(t)), add=True)
        for lg in (self.lf, self.rf):
            if lg:
                c.rot(lg, lambda t: (-5 * e(t), 0, 0), add=True)
        add_tail(c, self.m, lambda t: (-22 * e(t), 0, 0), lag=0.05, gain=1.0)
        self.jiggle(c, lambda t: (0, 0, 2 * decay(t, 0.3, 3, 4)))
        if self.snort:
            c.sound(0.25, *self.snort)
        return c

    def body_shake(self, name='body_shake', L=1.6, sound=None):
        """A dog-style shake from head to tail: the coat whips side to side and settles."""
        c = once(self.m, name, L)
        sh = lambda t: decay(t, 0.15, 3.4, 2.6)
        c.rot('body', lambda t: (0, 0, 9 * sh(t)), add=True)
        c.pos('body', lambda t: (0, -0.6 * pulse(t, 0.0, 0.25) + 0.3 * pulse(t, 0.25, 1.2), 0), add=True)
        self.head_rot(c, lambda t: (-4 * pulse(t, 0.0, 1.0), 10 * sh(t - 0.05), -20 * sh(t - 0.05)))
        add_ears(c, self.m, lambda t: (0, 0, 26 * sh(t - 0.08)))
        self.jiggle(c, lambda t: (0, 0, 14 * sh(t - 0.06)))
        add_tail(c, self.m, lambda t: (-10 * pulse(t, 0.1, 1.2), 24 * sh(t - 0.12), 0))
        for lg, sgn in ((self.lf, 1), (self.rf, -1), (self.lb, 1), (self.rb, -1)):
            if lg:
                c.rot(lg, (lambda sgn: lambda t: (0, 0, sgn * 5 * pulse(t, 0.1, 1.2)))(sgn), add=True)
        if sound:
            c.sound(0.25, *sound)
            c.sound(0.6, *sound)
        return c

    def call(self, name='call', L=1.6, sound='ambient', open_=14):
        """Head and jaw only (overlay, may walk on): the head lifts, the mouth opens for a call."""
        c = Clip(self.m, name, L, 'once')
        e = lambda t: env(t, 0.05, L - 0.1, 0.25, 0.45)
        c.rot(self.neck or self.head, lambda t: (16 * e(t), 0, 3 * wave(t, L, 2) * e(t)))
        if self.neck:
            c.rot(self.head, lambda t: (8 * e(t), 0, 0))
        if self.jaw:
            c.rot(self.jaw, lambda t: (-open_ * pulse(t, 0.25, 1.1) * (0.8 + 0.2 * wave(t, 0.3)), 0, 0))
        if sound:
            c.sound(0.25, sound, 0.8, 1.0)
        return c

    def snuffle(self, name='snuffle', L=2.4, sound=None):
        """Nose to the ground, quick snuffling bobs while it sweeps left and right (truffle hunting)."""
        c = once(self.m, name, L)
        e = lambda t: env(t, 0.0, L, 0.35, 0.45)
        self.head_rot(c, lambda t: ((-34 + 5 * wave(t, 0.22)) * e(t), 20 * wave(t, L, 1) * e(t), 0))
        if self.jaw:
            c.rot(self.jaw, lambda t: (-3 * max(0, wave(t, 0.22)) * e(t), 0, 0), add=True)
        c.rot('body', lambda t: (-4 * e(t), 4 * wave(t, L, 1) * e(t), 0), add=True)
        add_ears(c, self.m, lambda t: (-6 * e(t), 10 * e(t), 0))
        add_tail(c, self.m, lambda t: (-8 * e(t), 14 * wave(t, 0.5) * e(t), 0))
        if sound:
            for t0 in (0.5, 1.2, 1.8):
                c.sound(t0, *sound)
        return c

    # ---- young
    def hop(self, name='hop', L=1.0, height=4.5):
        """Young: a springy straight-legged bounce (pronk) and a squashy landing."""
        c = once(self.m, name, L)
        y = lambda t: keys(t, [(0, 0), (0.18, -1.2), (0.45, height), (0.7, 0), (0.8, -0.8), (1.0, 0)], 'smooth')
        c.pos('body', lambda t: (0, y(t), 0), add=True)
        c.rot('body', lambda t: (6 * pulse(t, 0.2, 0.55) - 4 * pulse(t, 0.55, 0.8), 0, 0), add=True)
        tuck = lambda t: pulse(t, 0.25, 0.68)
        for lg, s in ((self.lf, -1), (self.rf, -1), (self.lb, 1), (self.rb, 1)):
            if lg:
                c.rot(lg, (lambda s: lambda t: (s * 22 * tuck(t), 0, 0))(s), add=True)
        self.head_rot(c, lambda t: (10 * pulse(t, 0.2, 0.6) - 8 * pulse(t, 0.65, 0.9), 0, 0))
        add_ears(c, self.m, lambda t: (0, 0, -16 * pulse(t, 0.25, 0.6) + 18 * pulse(t, 0.66, 0.95)))
        add_tail(c, self.m, lambda t: (-20 * pulse(t, 0.2, 0.8), 0, 0))
        c.sound(0.7, self.step_sound, 0.8, 1.3)
        return c

    def frolic(self, name='frolic', L=1.9):
        """Young: two bucking hops with a kick of the hind legs and a twist, head tossed."""
        c = once(self.m, name, L)
        y = lambda t: keys(t, [(0, 0), (0.12, -1), (0.38, 4), (0.62, 0), (0.75, -0.8), (1.0, 3.2), (1.25, 0), (1.4, -0.6), (1.9, 0)], 'smooth')
        c.pos('body', lambda t: (0, y(t), 0), add=True)
        c.rot('body', lambda t: (-14 * pulse(t, 0.3, 0.7) - 12 * pulse(t, 0.92, 1.3), 18 * pulse(t, 0.2, 0.75) - 22 * pulse(t, 0.8, 1.35), -6 * pulse(t, 0.3, 0.7) + 6 * pulse(t, 0.92, 1.3)), add=True)
        kick = lambda t: pulse(t, 0.32, 0.66) + pulse(t, 0.95, 1.28)
        for lg in (self.lb, self.rb):
            if lg:
                c.rot(lg, lambda t: (-48 * kick(t), 0, 0), add=True)
        for lg in (self.lf, self.rf):
            if lg:
                c.rot(lg, lambda t: (-18 * kick(t), 0, 0), add=True)
        self.head_rot(c, lambda t: (16 * pulse(t, 0.2, 0.6) + 12 * pulse(t, 0.85, 1.2), 20 * pulse(t, 0.3, 0.8) - 24 * pulse(t, 0.9, 1.4), 12 * decay(t, 0.4, 2.5, 3)))
        add_ears(c, self.m, lambda t: (0, 0, 20 * decay(t, 0.35, 2.8, 2.5)))
        add_tail(c, self.m, lambda t: (-24 * pulse(t, 0.2, 1.5), 18 * wave(t, 0.5) * pulse(t, 0.2, 1.5), 0))
        c.sound(0.62, self.step_sound, 0.7, 1.3)
        c.sound(1.25, self.step_sound, 0.7, 1.35)
        return c

    def follow_prance(self, name='prance', L=2.0):
        """Young: a playful bow-and-bounce at a grown-up (the director turns it to the adult)."""
        c = once(self.m, name, L)
        bow = lambda t: env(t, 0.05, 0.9, 0.25, 0.25)
        c.rot('body', lambda t: (-10 * bow(t), 0, 0), add=True)
        c.pos('body', lambda t: (0, -1.4 * bow(t) + 3 * pulse(t, 0.95, 1.45), 0), add=True)
        for lg in (self.lf, self.rf):
            if lg:
                c.rot(lg, lambda t: (-30 * bow(t), 0, 0), add=True)
        self.head_rot(c, lambda t: (10 * bow(t) + 8 * pulse(t, 0.95, 1.45), 0, 8 * wave(t, 0.5) * bow(t)))
        add_tail(c, self.m, lambda t: (-18 * bow(t), 22 * wave(t, 0.35) * env(t, 0.0, 1.6), 0))
        add_ears(c, self.m, lambda t: (10 * bow(t), 0, 0))
        c.sound(1.45, self.step_sound, 0.6, 1.4)
        return c

    # ---- grazing (variants of the graze activity; start and end on the graze loop)
    def graze_tear(self, name='graze_tear', L=1.3):
        c = once(self.m, name, L, 'graze')
        yank = lambda t: keys(t, [(0, 0), (0.25, -0.35), (0.42, 1), (0.85, 0.6), (1.3, 0)])
        self.head_rot(c, lambda t: (14 * yank(t), 12 * pulse(t, 0.3, 0.8), -10 * pulse(t, 0.3, 0.8)))
        if self.jaw:
            c.rot(self.jaw, lambda t: (-8 * pulse(t, 0.05, 0.35), 0, 0), add=True)
        c.rot('body', lambda t: (1.5 * pulse(t, 0.3, 0.8), 0, 0), add=True)
        add_ears(c, self.m, lambda t: (0, 0, 10 * decay(t, 0.42, 3, 4)))
        c.sound(0.42, 'minecraft:block.grass.break', 0.3, 1.1)
        return c

    def graze_step(self, name='graze_step', L=1.6):
        """Head stays down; one front leg then the other shuffles forward to the next tuft (no actual move)."""
        c = once(self.m, name, L, 'graze')
        for lg, t0 in ((self.lf, 0.15), (self.rf, 0.75)):
            if lg:
                c.rot(lg, (lambda t0: lambda t: (16 * pulse(t, t0, t0 + 0.55), 0, 0))(t0), add=True)
                c.pos(lg, (lambda t0: lambda t: (0, 1.0 * pulse(t, t0, t0 + 0.55), 0))(t0), add=True)
        c.rot('body', lambda t: (0, 0, 1.5 * pulse(t, 0.15, 0.7) - 1.5 * pulse(t, 0.75, 1.3)), add=True)
        self.head_rot(c, lambda t: (3 * pulse(t, 0.1, 1.4), 8 * pulse(t, 0.2, 1.4), 0))
        c.sound(0.7, self.step_sound, 0.45, 0.9)
        c.sound(1.3, self.step_sound, 0.45, 0.95)
        return c

    def graze_chew(self, name='graze_chew', L=4.0):
        """Loop: head half up from the grass, munching the mouthful, gazing about."""
        c = loop(self.m, name, L, 'idle')
        if self.jaw:
            c.rot(self.jaw, lambda t: (chew(t, 1.7, 1.2)[0], chew(t, 1.7, 1.2)[1], 0))
        self.head_rot(c, lambda t: (-16 + 2 * wave(t, L, 2), 14 * wave(t, L, 1), 2 * wave(t, L, 1)))
        add_ears(c, self.m, lambda t: (0, 6 * wave(t, L, 1, 0.3), 4))
        c.sound(0.3, 'minecraft:entity.generic.eat', 0.12, 0.75)
        c.sound(2.3, 'minecraft:entity.generic.eat', 0.1, 0.7)
        return c

    def graze_look(self, name='graze_look', L=2.8):
        """Lifts its head from the grass to look around (still chewing), then dips back down."""
        c = once(self.m, name, L, 'graze')
        g = self.m.pose('graze', 0)
        i = self.m.pose('idle', 0)
        up = lambda t: keys(t, [(0, 0), (0.45, 1), (2.2, 1), (2.8, 0)])
        bones = [self.head] + ([self.neck] if self.neck else [])
        for b in bones:
            ga, ia = g.get((b, 'rotation'), ZERO), i.get((b, 'rotation'), ZERO)
            gp, ip = g.get((b, 'position'), ZERO), i.get((b, 'position'), ZERO)
            c.rot(b, (lambda ga, ia: lambda t: lerp3(ga, ia, up(t)))(ga, ia))
            c.pos(b, (lambda gp, ip: lambda t: lerp3(gp, ip, up(t)))(gp, ip))
        self.head_rot(c, lambda t: (-6 * up(t), 26 * wave(t, 2.8, 1) * env(t, 0.5, 2.2), 0))
        if self.jaw:
            c.rot(self.jaw, lambda t: (chew(t, 1.8)[0] * up(t), chew(t, 1.8)[1] * up(t), 0))
        add_ears(c, self.m, lambda t: (8 * up(t), 10 * up(t), 0))
        return c

    # ---- lying
    def lie_down(self, L=1.7, lie='lie'):
        """Front first: kneels onto its front legs, then the rear folds down (how cattle and deer lie down)."""
        m = self.m
        a, b = m.pose('idle', 0), m.pose(lie, 0)
        c = Clip(m, 'lie_down', L, 'once')
        timing = {self.lf: (0.12, 0.8), self.rf: (0.16, 0.85), self.lb: (0.6, 1.45), self.rb: (0.65, 1.5)}
        for bone in sorted({k[0] for k in a} | {k[0] for k in b}):
            t0, t1 = timing.get(bone, (0.15, L - 0.15))
            c.blend(bone, a, b, t0, t1)
        dy = b.get(('body', 'position'), ZERO)[1]
        rx = b.get(('body', 'rotation'), ZERO)
        c.pos('body', lambda t: (0, keys(t, [(0, 0), (0.75, dy * 0.45), (1.5, dy), (L, dy)]), 0))
        c.rot('body', lambda t: (keys(t, [(0, 0), (0.5, -14 * self.s), (0.95, -10), (1.5, rx[0]), (L, rx[0])]), rx[1] * step(t, 0.8, 1.5),
                                 rx[2] * step(t, 0.8, 1.5)))
        self.head_rot(c, lambda t: (10 * pulse(t, 0.3, 1.1) - 4 * pulse(t, 1.2, 1.6), 0, 0))
        c.sound(0.75, self.step_sound, 0.6, 0.75)
        c.sound(1.5, 'minecraft:block.wool.fall', 0.4, 0.7)
        return c

    def stand_up(self, L=1.6, lie='lie', name='stand_up'):
        """Rear first: the hind end heaves up, then the front legs unfold (cattle rise back end first)."""
        m = self.m
        a, b = m.pose(lie, 0), m.pose('idle', 0)
        c = Clip(m, name, L, 'once')
        timing = {self.lb: (0.0, 0.6), self.rb: (0.0, 0.65), self.lf: (0.6, 1.3), self.rf: (0.65, 1.35)}
        for bone in sorted({k[0] for k in a} | {k[0] for k in b}):
            t0, t1 = timing.get(bone, (0.1, L - 0.15))
            c.blend(bone, a, b, t0, t1)
        dy = a.get(('body', 'position'), ZERO)[1]
        rx = a.get(('body', 'rotation'), ZERO)
        c.pos('body', lambda t: (0, keys(t, [(0, dy), (0.6, dy * 0.45), (1.35, 0), (L, 0)]), 0))
        c.rot('body', lambda t: (keys(t, [(0, rx[0]), (0.55, -16 * self.s), (0.95, -10), (1.35, 0), (L, 0)]), rx[1] * (1 - step(t, 0, 0.6)),
                                 rx[2] * (1 - step(t, 0, 0.6))))
        self.head_rot(c, lambda t: (-10 * pulse(t, 0.0, 0.7) + 8 * pulse(t, 0.7, 1.4), 0, 0))
        self.jiggle(c, lambda t: (0, 0, 3 * decay(t, 1.35, 2.5, 4)))
        c.sound(0.55, self.step_sound, 0.6, 0.8)
        c.sound(1.3, self.step_sound, 0.6, 0.85)
        return c

    def lie_chew(self, name='lie_chew', L=5.0, lie='lie'):
        c = loop(self.m, name, L, lie)
        if self.jaw:
            c.rot(self.jaw, lambda t: (chew(t, 1.4)[0], chew(t, 1.4)[1], 0))
        self.head_rot(c, lambda t: (2 * wave(t, L, 2), 10 * wave(t, L, 1), 2 * wave(t, L, 1, 0.25)))
        add_ears(c, self.m, lambda t: (0, 0, 10))
        c.sound(0.5, 'minecraft:entity.generic.eat', 0.08, 0.7)
        return c

    def lie_look(self, name='lie_look', L=7.0, lie='lie'):
        c = loop(self.m, name, L, lie)
        yaw = lambda t: 30 * math.sin(2 * math.pi * t / L) + 8 * math.sin(4 * math.pi * t / L)
        self.head_rot(c, lambda t: (6 + 3 * wave(t, L, 2), yaw(t), yaw(t) * 0.1))
        add_ears(c, self.m, lambda t: (6, 8 * wave(t, L, 1, 0.1), 0))
        return c

    def sleep(self, name='sleep', L=6.0, lie='lie', curl=34, drop=2.2):
        """Loop: head lowered and turned along the flank, eyes shut, slow deep breathing (sleep-type: eyes may close)."""
        c = loop(self.m, name, L, lie)
        if self.neck:
            c.rot(self.neck, lambda t: (-38, curl * 0.6, 0), add=True)
            c.rot(self.head, lambda t: (10 + 1.5 * wave(t, L, 1), curl * 0.5, -12), add=True)
        else:
            c.rot(self.head, lambda t: (-14 + 1.5 * wave(t, L, 1), curl, -10), add=True)
            c.pos(self.head, lambda t: (0, -drop, 0), add=True)
        if self.jaw:
            c.rot(self.jaw, lambda t: (0, 0, 0))
        c.scl('body', lambda t: (1 + 0.012 * wave(t, L, 1), 1 + 0.028 * wave(t, L, 1), 1 + 0.008 * wave(t, L, 1)))
        eyes = self.m.find('eyes')
        if eyes:
            c.scl(eyes, lambda t: (1, 0.12, 1))
        add_ears(c, self.m, lambda t: (0, -6, 16))
        add_tail(c, self.m, lambda t: (0, 0, 0))
        return c


# =============================================================================================== undead


class Walker:
    """A humanoid undead (zombie / skeleton rig): leg_l / leg_r, body, h_head, arm_l + forearm_l, arm_r + forearm_r.
    Hanging limbs swing forward with x+ (a zombie's raised arms are about +84), the body leans forward with x-."""

    def __init__(self, m, crack=None, size=1.0):
        self.m = m
        self.crack = crack             # (sound id, volume, pitch) for twitches: bone / ice / wood
        self.s = size

    def arms(self, c, fn_l, fn_r, fore_l=None, fore_r=None):
        if fn_l:
            c.rot('arm_l', fn_l, add=True)
        if fn_r:
            c.rot('arm_r', fn_r, add=True)
        if fore_l:
            c.rot('forearm_l', fore_l, add=True)
        if fore_r:
            c.rot('forearm_r', fore_r, add=True)

    def idle_sway(self, name='idle_sway', L=6.4, amt=1.0, src='idle'):
        """Loop: an eerie slow sway from the ankles, head lolling a beat behind, arms dangling with the swing."""
        c = loop(self.m, name, L, src)
        sw = lambda t: amt * (6 * wave(t, L, 1) + 1.5 * wave(t, L, 3, 0.2))
        c.rot('body', lambda t: (1.5 * wave(t, L, 2, 0.1), 2 * wave(t, L, 1, 0.3), sw(t)), add=True)
        c.rot('h_head', lambda t: (-3 + 4 * wave(t, L, 2, 0.35), 8 * wave(t, L, 1, 0.55), -sw(t - 0.6) * 1.6), add=True)
        self.arms(c, lambda t: (2 * wave(t, L, 2), 0, -sw(t - 0.3) * 0.9), lambda t: (2 * wave(t, L, 2, 0.5), 0, -sw(t - 0.3) * 0.9),
                  lambda t: (3 * wave(t, L, 1, 0.4), 0, 0), lambda t: (3 * wave(t, L, 1, 0.7), 0, 0))
        c.rot('leg_l', lambda t: (0, 0, -sw(t) * 0.5), add=True)
        c.rot('leg_r', lambda t: (0, 0, -sw(t) * 0.5), add=True)
        return c

    def twitch(self, name='twitch', L=1.6):
        """A sudden jerk: the head snaps sideways, a shoulder spasms, then it slowly lolls back."""
        c = once(self.m, name, L)
        snap = lambda t: keys(t, [(0, 0), (0.12, 0), (0.18, 1), (0.5, 0.85), (1.6, 0)], 'out')
        c.rot('h_head', lambda t: (8 * snap(t), -14 * snap(t), 30 * snap(t) + 6 * decay(t, 0.18, 6, 7)), add=True)
        c.rot('body', lambda t: (0, 0, -4 * snap(t) + 2 * decay(t, 0.2, 5, 6)), add=True)
        c.rot('arm_r', lambda t: (-24 * pulse(t, 0.15, 0.45) + 6 * decay(t, 0.45, 5, 6), 0, 18 * pulse(t, 0.15, 0.55)), add=True)
        c.rot('forearm_r', lambda t: (-30 * pulse(t, 0.18, 0.5), 0, 0), add=True)
        c.rot('arm_l', lambda t: (8 * decay(t, 0.2, 4, 5), 0, 0), add=True)
        if self.crack:
            c.sound(0.18, *self.crack)
        return c

    def sniff(self, name='sniff', L=2.2):
        """Notice (overlay, may walk on): the head rises and pushes forward, three sharp sniffs, a slow turn."""
        c = Clip(self.m, name, L, 'once')
        e = lambda t: env(t, 0.0, L, 0.35, 0.5)
        sn = lambda t: sum(pulse(t, t0, t0 + 0.14) for t0 in (0.45, 0.7, 0.95))
        c.rot('h_head', lambda t: (14 * e(t) + 5 * sn(t), 16 * wave(t, L, 1) * e(t), 4 * e(t)))
        c.pos('h_head', lambda t: (0, 0, -0.8 * e(t)))
        c.rot('body', lambda t: (-4 - 6 * e(t), 0, 0))
        c.sound(0.45, 'minecraft:entity.fox.sniff', 0.35, 0.55)
        c.sound(0.95, 'minecraft:entity.fox.sniff', 0.3, 0.5)
        return c

    def look_around(self, name='look_around', L=3.4):
        c = once(self.m, name, L)
        yaw = lambda t: keys(t, [(0, 0), (0.5, 0), (1.0, 42), (1.7, 42), (2.2, -40), (2.9, -40), (3.4, 0)], 'out')
        c.rot('h_head', lambda t: (4 * env(t, 0.3, 3.1), yaw(t), -yaw(t) * 0.15), add=True)
        c.rot('body', lambda t: (0, yaw(t) * 0.2, 0), add=True)
        return c

    def walk_variant(self, name, L, stride=28, drag=None, lurch=8, bob=1.2, lean=10, arms=84, sway=5, head_bob=6, src='walk'):
        """A walk-cycle variant: two steps per loop. drag = 'r' / 'l' drags that leg (barely lifts, toes scrape)."""
        c = Clip(self.m, name, L, 'loop')
        keep = ['root', 'arrow', 'bow', 'bow_top', 'bow_bot', 'string_top', 'string_bot', 'lamp', 'pickaxe', 'lantern', 'jaw',
                'drip_a', 'drip_b', 'leaves', 'leaf_l', 'leaf_r', 'skirt']
        if arms is None:
            keep += ['arm_l', 'arm_r', 'forearm_l', 'forearm_r']
        c.copy(src, speed=self.m.length(src) / L, bones=keep)
        ph = lambda t: 2 * math.pi * t / L
        sl = lambda t: math.sin(ph(t))
        dl = (0.45 if drag == 'l' else 1.0)
        dr = (0.45 if drag == 'r' else 1.0)
        c.rot('leg_l', lambda t: (stride * dl * sl(t), 0, 2 if drag == 'l' else 0))
        c.rot('leg_r', lambda t: (-stride * dr * sl(t), 0, -2 if drag == 'r' else 0))
        c.pos('body', lambda t: (0, -bob * abs(math.cos(ph(t))) + (0.6 * bob if drag else 0) * max(0, math.sin(ph(t) + (0 if drag == 'r' else math.pi))), 0))
        c.rot('body', lambda t: (-lean + 3 * math.cos(2 * ph(t)), sway * sl(t), lurch * (sl(t) if not drag else (0.6 + 0.4 * sl(t)) * (1 if drag == 'r' else -1))))
        c.rot('h_head', lambda t: (lean * 0.6 + head_bob * math.cos(2 * ph(t) + 0.6), -sway * 1.2 * sl(t - 0.1), -lurch * 1.3 * sl(t - L * 0.12)))
        if arms is not None:
            c.rot('arm_l', lambda t: (arms + 6 * math.sin(ph(t) + 0.5), 0, -4 - 3 * sl(t)))
            c.rot('arm_r', lambda t: (arms - 6 * math.sin(ph(t) + 0.5), 0, 4 - 3 * sl(t)))
            c.rot('forearm_l', lambda t: (-8 + 6 * math.sin(ph(t) + 1.2), 0, 0))
            c.rot('forearm_r', lambda t: (-8 - 6 * math.sin(ph(t) + 1.2), 0, 0))
        return c

    def run(self, L=0.8, stride=40, lean=18, reach=95):
        """Chase: a lunging, lurching shamble, arms reaching for the prey (reach None keeps the walk's arms)."""
        c = self.walk_variant('run', L, stride=stride, lurch=7, bob=1.6, lean=lean, arms=reach, sway=8, head_bob=5)
        c.rot('h_head', lambda t: (lean * 0.9, 0, 0), add=True)
        return c


def undead_tags(base_once=(), walks=(), lying=()):
    """Undead clip tags: whole-body idle one-shots play on the base layer and stop if it moves (undead one-shots are
    overlays by default), walk variants are walk gaits, dormant variants lie."""
    out = {}
    for n in base_once:
        out[n] = {'layer': 'base', 'move': False}
    for n in walks:
        out[n] = {'p': 'standing', 'move': True, 'gait': 'walk', 'layer': 'base'}
    for n in lying:
        out[n] = {'p': 'lying'}
    return out


def slump(w, name='slump', L=7.0):
    """Loop: hunched and spent, head hanging, arms sagging, knees soft; an occasional heave of breath."""
    c = loop(w.m, name, L)
    c.rot('body', lambda t: (-12 + 1.5 * wave(t, L, 1), 0, 2 * wave(t, L, 1, 0.3)), add=True)
    c.rot('h_head', lambda t: (-18 + 3 * wave(t, L, 2, 0.2), 6 * wave(t, L, 1), 6), add=True)
    c.rot('arm_l', lambda t: (-26 + 3 * wave(t, L, 1, 0.1), 0, 2), add=True)
    c.rot('arm_r', lambda t: (-22 + 3 * wave(t, L, 1, 0.4), 0, -2), add=True)
    c.rot('forearm_l', lambda t: (-6, 0, 0), add=True)
    c.rot('forearm_r', lambda t: (-8, 0, 0), add=True)
    c.rot('leg_l', lambda t: (6, 0, -1), add=True)
    c.rot('leg_r', lambda t: (3, 0, 1), add=True)
    c.pos('body', lambda t: (0, -0.5 + 0.3 * pulse(t, 4.5, 6.0), 0), add=True)
    return c


def shiver(w, name='shiver', L=3.0, amt=1.0):
    """Loop: a fast fine tremor through the whole frame (frozen / cold)."""
    c = loop(w.m, name, L)
    tr = lambda t, ph=0.0: amt * (1.4 * wave(t, L, 24, ph) + 0.6 * wave(t, L, 37, ph + 0.3))
    c.rot('body', lambda t: (-6, 0, tr(t)), add=True)
    c.rot('h_head', lambda t: (-6 + tr(t, 0.2), 0, tr(t, 0.5) * 1.5), add=True)
    c.rot('arm_l', lambda t: (-14 + tr(t, 0.1) * 2, 0, 10), add=True)
    c.rot('arm_r', lambda t: (-14 + tr(t, 0.4) * 2, 0, -10), add=True)
    c.rot('forearm_l', lambda t: (20, 0, 0), add=True)
    c.rot('forearm_r', lambda t: (20, 0, 0), add=True)
    return c


def body_shake(w, name='shake_off', L=1.6, sound=None, extra=()):
    """A dog-style shake from the shoulders (dirt / leaves / water flung off)."""
    c = once(w.m, name, L)
    sh = lambda t: decay(t, 0.15, 3.6, 2.8)
    c.rot('body', lambda t: (0, 10 * sh(t), 6 * sh(t)), add=True)
    c.rot('h_head', lambda t: (0, -14 * sh(t - 0.05), -18 * sh(t - 0.05)), add=True)
    c.rot('arm_l', lambda t: (0, 0, -16 * sh(t - 0.08)), add=True)
    c.rot('arm_r', lambda t: (0, 0, -16 * sh(t - 0.08)), add=True)
    for b in extra:
        if w.m.has_bone(b):
            c.rot(b, lambda t: (10 * sh(t - 0.1), 0, 14 * sh(t - 0.1)), add=True)
    if sound:
        c.sound(0.2, *sound)
        c.sound(0.55, *sound)
    return c


def hurt(m, head='h_head', extra=(), L=0.5, amt=1.0):
    """Hit flinch (overlay, plays over walking / fleeing): the body jolts, the head snaps aside and settles. Only body
    and head rotation (+ ears / tail) are keyed, so the legs keep their gait."""
    c = Clip(m, 'hurt', L, 'once')
    rest = m.pose('idle', 0)
    j = lambda t: decay(t, 0.0, 2.6, 6.0)
    base = lambda b: rest.get((b, 'rotation'), ZERO)
    c.rot('body', lambda t: tuple(v + d for v, d in zip(base('body'), (4 * amt * j(t), 0, 6 * amt * j(t)))))
    hb = head if m.has_bone(head) else None
    if hb:
        c.rot(hb, lambda t: tuple(v + d for v, d in zip(base(hb), (10 * amt * j(t), 14 * amt * j(t), -8 * amt * j(t)))))
    l, r = ears(m)
    if l:
        c.rot(l, lambda t: tuple(v + d for v, d in zip(base(l), (0, 0, -20 * j(t)))))
        c.rot(r, lambda t: tuple(v + d for v, d in zip(base(r), (0, 0, 20 * j(t)))))
    for b in extra:
        if m.has_bone(b):
            c.rot(b, (lambda b: lambda t: tuple(v + d for v, d in zip(base(b), (-14 * j(t), 0, 0))))(b))
    return c
