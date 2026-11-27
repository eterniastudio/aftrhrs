"""Shared cat body language for the six cats (tabby_cat, black_cat, maine_coon, calico_kitten, luna, kevin).

Not a model module (gen_creatures only loads clips/<model>.py); each cat's module imports it and calls the clip
builders with its own proportions and temperament (CatStyle). All six share one rig: root > body > h_head (jaw, eyes,
ears), four legs, tail > tail_mid > tail_tip (Luna / Kevin add a tongue, Kevin a belly).

Rules kept here:
- Eyes are never keyed: everything awake stays wide-eyed (no blinking, no slow blinks); the sleep_* loops start from
  the species' own sleep pose and keep its closed eyes (sleep-type clips may).
- Posture loops (sit_*, sleep_*) start and end exactly on the species' sit / sleep pose, so the idle director can swap
  them in and out of the held pose with a plain cross-fade; standing one-shots start and end on the rest pose.
- Every non-locomotion clip is still-only (base clips are move: false; the head / ear / tail-only clips are overlays).

Signs (Blockbench): rot x+ pitches the front up (head up, leg's foot forward, ears tip back, tail down), x- tail up;
y+ turns towards -x (the cat's right); z+ lifts the +x (left) side. Positions in px, scaled by the cat's leg length.
"""
import math

from animkit import Clip, keys, pulse, smooth, step, wave

H, JAW = 'h_head', 'jaw'
EAR_R, EAR_L = 'right_ear', 'left_ear'
LF, RF, LB, RB = 'left_front_leg', 'right_front_leg', 'left_back_leg', 'right_back_leg'
TAIL, MID, TIP = 'tail', 'tail_mid', 'tail_tip'
LEGS = (LF, RF, LB, RB)
REST = (0.0, 0.0, 0.0)


class CatStyle:
    """Proportions and temperament. s: position scale (leg length / 2.5 px); k: tempo (time multiplier, >1 slower);
    a: motion amplitude; tail: tail amplitude; chonk: belly jiggle (Kevin)."""

    def __init__(self, m, s=1.0, k=1.0, a=1.0, tail=1.0, chonk=0.0, voice='ambient'):
        self.m, self.s, self.k, self.a, self.tail, self.chonk, self.voice = m, s, k, a, tail, chonk, voice
        self.S = m.pose('sit', 'end')
        self.Z = m.pose('sleep', 0)
        self.belly = m.has_bone('belly')


def env(t, a, b, c, d):
    """0 -> 1 between a and b, 1 until c, back to 0 between c and d (smooth)."""
    return step(t, a, b) * (1 - step(t, c, d))


def osc(t, f, t0=0.0):
    return math.sin(2 * math.pi * f * (t - t0))


class K(Clip):
    """A Clip authored in base time: every track and sound time is stretched by the cat's tempo k."""

    def __init__(self, st, name, length, loop='once'):
        super().__init__(st.m, name, length * st.k, loop)
        self.k = st.k
        self.st = st

    def _add(self, bone, ch, fn, add):
        k = self.k
        return super()._add(bone, ch, (lambda f: lambda t: f(t / k))(fn), add)

    def sound(self, t, event, volume=1.0, pitch=1.0):
        return super().sound(t * self.k, event, volume, pitch)

    def base(self, pose, skip=()):
        """Every (bone, channel) of a pose held constant (the posture this clip lives in)."""
        for (bone, ch), v in pose.items():
            if bone in skip or (bone, ch) in skip:
                continue
            self.tracks[(bone, ch)] = (lambda v: lambda t: v)(v)
        return self

    def lag(self, bone, src, lag_s, gain=(1, 1, 1), extra=None):
        """bone rotation follows src's (lag in base seconds); for tail chains."""
        f = self.tracks[(src, 'rotation')]
        L, k, loop = self.L, self.k, self.loop

        def g(t):
            ts = t - lag_s * k
            ts = ts % L if loop == 'loop' else max(0.0, ts)
            v = f(ts)
            e = extra(t / k) if extra else (0, 0, 0)
            return tuple(v[i] * gain[i] + e[i] for i in range(3))
        self.tracks[(bone, 'rotation')] = g
        return self


def P(st, key, default=REST):
    return st.S.get(key, default)


def tail_chain(c, base_fn, lag=0.08, gain=(1.15, 1.25, 1), rest_mid=REST, rest_tip=REST, tip_extra=None):
    """tail = base_fn(t); mid and tip follow with lag (overlapping action); rests added after."""
    c.rot(TAIL, base_fn)
    f = c.tracks[(TAIL, 'rotation')]
    L, loop = c.L, c.loop

    def mk(lag_s, g, rest, extra):
        def h(t):
            ts = t - lag_s
            ts = ts % L if loop == 'loop' else max(0.0, ts)
            v = f(ts)
            e = extra(t / c.k) if extra else (0, 0, 0)
            return tuple(rest[i] + (v[i] * g[i] if i != 0 else v[i] * 0.35) + e[i] for i in range(3))
        return h
    c.tracks[(MID, 'rotation')] = mk(lag * c.k, (gain[0], gain[0], 1), rest_mid, None)
    c.tracks[(TIP, 'rotation')] = mk(2 * lag * c.k, (gain[1], gain[1], 1), rest_tip, tip_extra)
    return c


# =============================================================================================== overlays (partial)
def hurt(st):
    """Flinch: ears pinned flat and out, head ducks, body recoils, tail lashes low. Overlay (plays while moving)."""
    s, a = st.s, st.a
    c = K(st, 'hurt', 0.55)
    e = lambda t: keys(t, [(0, 0), (0.04, 1), (0.3, 1), (0.55, 0)])
    r = lambda t: keys(t, [(0, 0), (0.05, 1), (0.16, 0.6), (0.5, 0)])
    c.pos('root', lambda t: (0, -0.5 * s * r(t), 0.7 * s * r(t)))
    c.rot('body', lambda t: (-7 * a * r(t), 0, 3 * r(t) * wave(t, 0.55)))
    c.rot(H, lambda t: (-12 * a * r(t), 0, 6 * r(t)))
    c.rot(EAR_R, lambda t: (62 * e(t), 0, 26 * e(t)))
    c.rot(EAR_L, lambda t: (62 * e(t), 0, -26 * e(t)))
    tail_chain(c, lambda t: (22 * r(t), 28 * st.tail * r(t) * math.sin(2 * math.pi * 3 * t), 0), lag=0.05)
    return c


def tail_flick(st):
    """Two sharp sideways lashes of the tail: irritated / alert. Tail-only overlay."""
    c = K(st, 'tail_flick', 0.9)
    A = 30 * st.tail
    tail_chain(c, lambda t: (-8 * pulse(t, 0, 0.9), keys(t, [(0, 0), (0.12, A), (0.27, -A * 0.85), (0.42, A * 0.6),
                                                            (0.6, -A * 0.2), (0.9, 0)]), 0), lag=0.06,
               gain=(1.25, 1.4, 1))
    return c


def tail_quiver(st):
    """Tail straight up with the tip hooked and quivering: the happy 'hello' signal. Tail-only overlay."""
    c = K(st, 'tail_quiver', 1.8)
    up = lambda t: keys(t, [(0, 0), (0.3, 1), (1.45, 1), (1.8, 0)])
    q = lambda t: math.sin(2 * math.pi * 9 * t) * env(t, 0.35, 0.5, 1.3, 1.45)
    c.rot(TAIL, lambda t: (-92 * up(t), 4 * q(t) * st.tail, 0))
    c.rot(MID, lambda t: (6 * up(t), 7 * q(t) * st.tail, 0))
    c.rot(TIP, lambda t: (38 * up(t), 10 * q(t) * st.tail, 0))
    return c


def ear_swivel(st):
    """One ear swivels back to listen, then the other, then both forward. Ears-only overlay."""
    c = K(st, 'ear_swivel', 1.7)
    c.rot(EAR_R, lambda t: (14 * env(t, 0.1, 0.25, 0.8, 1.0), 55 * env(t, 0.1, 0.25, 0.8, 1.0), 0))
    c.rot(EAR_L, lambda t: (14 * env(t, 0.55, 0.7, 1.3, 1.5), -55 * env(t, 0.55, 0.7, 1.3, 1.5), 0))
    return c


def watch_bug(st, name='watch_bug'):
    """Tracks something flying past: head follows a looping path, ears pricked, a little jaw chatter at the end."""
    c = K(st, name, 3.4)
    e = lambda t: env(t, 0, 0.35, 2.9, 3.4)
    c.rot(H, lambda t: ((14 + 10 * math.sin(2 * math.pi * 0.9 * t)) * e(t),
                        (34 * math.sin(2 * math.pi * 0.45 * t)) * e(t), 6 * math.sin(2 * math.pi * 0.45 * t) * e(t)))
    c.rot(EAR_R, lambda t: (-12 * e(t), -10 * e(t), 0))
    c.rot(EAR_L, lambda t: (-12 * e(t), 10 * e(t), 0))
    c.rot(JAW, lambda t: (-9 * (0.5 + 0.5 * math.sin(2 * math.pi * 11 * t)) * env(t, 2.2, 2.25, 2.7, 2.8), 0, 0))
    c.sound(2.25, st.voice, 0.22, 1.5)
    return c


def pet_sit(st):
    """Owner pets it while it sits: pushes its head up into the hand, cheek rubs side to side, ears relaxed out.
    Head + ears only, over the held sit pose."""
    c = K(st, 'pet_sit', 2.0)
    hx, hy, hz = P(st, (H, 'rotation'))
    erx = P(st, (EAR_R, 'rotation'))[0]
    elx = P(st, (EAR_L, 'rotation'))[0]
    e = lambda t: keys(t, [(0, 0), (0.25, 1), (1.6, 1), (2.0, 0)])
    rub = lambda t: math.sin(2 * math.pi * 1.4 * (t - 0.25)) * env(t, 0.25, 0.45, 1.4, 1.6)
    c.rot(H, lambda t: (hx + 24 * e(t), hy + 10 * rub(t), hz + 16 * rub(t)))
    c.rot(EAR_R, lambda t: (erx + 18 * e(t), 0, 14 * e(t)))
    c.rot(EAR_L, lambda t: (elx + 18 * e(t), 0, -14 * e(t)))
    return c


# =============================================================================================== reactions
def pet(st):
    """Owner pets it standing: rises onto its toes into the hand, rubs its cheeks left and right, treads a paw,
    tail straight up with the tip quivering."""
    s, a = st.s, st.a
    c = K(st, 'pet', 2.3)
    e = lambda t: keys(t, [(0, 0), (0.3, 1), (1.8, 1), (2.3, 0)])
    rub = lambda t: math.sin(2 * math.pi * 1.3 * (t - 0.3)) * env(t, 0.3, 0.5, 1.6, 1.85)
    c.pos('root', lambda t: (0.25 * s * rub(t), 0.35 * s * e(t), 0))
    c.rot('body', lambda t: (3 * e(t), 0, -5 * a * rub(t)))
    c.rot(H, lambda t: (22 * e(t), 12 * rub(t), 16 * a * rub(t)))
    c.rot(EAR_R, lambda t: (16 * e(t), 0, 12 * e(t)))
    c.rot(EAR_L, lambda t: (16 * e(t), 0, -12 * e(t)))
    c.rot(LF, lambda t: (22 * pulse(t, 0.55, 0.95), 0, 0))
    c.pos(LF, lambda t: (0, 0.6 * s * pulse(t, 0.55, 0.95), 0))
    c.rot(RF, lambda t: (22 * pulse(t, 1.05, 1.45), 0, 0))
    c.pos(RF, lambda t: (0, 0.6 * s * pulse(t, 1.05, 1.45), 0))
    q = lambda t: math.sin(2 * math.pi * 9 * t) * env(t, 0.4, 0.5, 1.7, 1.8)
    c.rot(TAIL, lambda t: (-90 * e(t), 4 * q(t), 0))
    c.rot(MID, lambda t: (6 * e(t), 7 * q(t), 0))
    c.rot(TIP, lambda t: (36 * e(t), 10 * q(t), 0))
    if st.belly:
        c.scl('belly', lambda t: (1 + 0.05 * rub(t) * st.chonk, 1, 1))
    return c


def happy(st, name='happy'):
    """A delighted trill: two bouncy front-paw hops, head tilts side to side, ears forward, tail up and quivering."""
    s, a = st.s, st.a
    c = K(st, name, 1.8)
    e = lambda t: keys(t, [(0, 0), (0.2, 1), (1.45, 1), (1.8, 0)])
    hop = lambda t: pulse(t, 0.3, 0.62) + 0.8 * pulse(t, 0.75, 1.07)
    c.pos('root', lambda t: (0, 0.9 * s * a * hop(t) - 0.3 * s * pulse(t, 0.2, 0.32), 0))
    c.rot('body', lambda t: (10 * a * hop(t), 0, 0))
    c.rot(H, lambda t: (8 * e(t) - 6 * hop(t), 0, 18 * a * math.sin(2 * math.pi * 1.1 * (t - 0.2)) * e(t)))
    for leg in (LF, RF):
        c.rot(leg, lambda t: (30 * hop(t), 0, 0))
    for leg in (LB, RB):
        c.rot(leg, lambda t: (-10 * hop(t), 0, 0))
    c.rot(EAR_R, lambda t: (-10 * e(t), 0, 0))
    c.rot(EAR_L, lambda t: (-10 * e(t), 0, 0))
    q = lambda t: math.sin(2 * math.pi * 9 * t) * env(t, 0.25, 0.35, 1.35, 1.45)
    c.rot(TAIL, lambda t: (-92 * e(t), 5 * q(t), 0))
    c.rot(MID, lambda t: (6 * e(t), 7 * q(t), 0))
    c.rot(TIP, lambda t: (34 * e(t), 10 * q(t), 0))
    if st.belly:
        c.scl('belly', lambda t: (1, 1 + 0.1 * st.chonk * hop(t), 1))
    return c


def eat(st):
    """Crouches over the food, head down; chews with the sideways head tilts cats do, ears flicking, tail low."""
    s = st.s
    c = K(st, 'eat', 2.2)
    e = lambda t: keys(t, [(0, 0), (0.3, 1), (1.85, 1), (2.2, 0)])
    chew = lambda t: env(t, 0.45, 0.55, 1.75, 1.85)
    c.pos('root', lambda t: (0, -0.6 * s * e(t), 0))
    c.rot('body', lambda t: (-7 * e(t), 0, 0))
    for leg in (LF, RF):
        c.rot(leg, lambda t: (-8 * e(t), 0, 0))
    for leg in (LB, RB):
        c.rot(leg, lambda t: (10 * e(t), 0, 0))
    c.rot(H, lambda t: (-38 * e(t) + 5 * math.sin(2 * math.pi * 3 * t) * chew(t), 0,
                        16 * math.sin(2 * math.pi * 1.5 * t) * chew(t)))
    c.rot(JAW, lambda t: (-7 * (0.5 + 0.5 * math.sin(2 * math.pi * 3 * t)) * chew(t), 0, 0))
    c.rot(EAR_R, lambda t: (10 * e(t) + 18 * pulse(t, 0.9, 1.15), 0, 0))
    c.rot(EAR_L, lambda t: (10 * e(t) + 18 * pulse(t, 1.3, 1.55), 0, 0))
    tail_chain(c, lambda t: (14 * e(t), 10 * math.sin(2 * math.pi * 0.9 * t) * e(t), 0))
    return c


def tame(st):
    """Won over: a cautious stretch-sniff forward, ears swivel from wary to forward, a head bonk into the air in
    front of it, then sits back proud with the tail going up."""
    s, a = st.s, st.a
    c = K(st, 'tame', 2.6)
    reach = lambda t: env(t, 0.1, 0.55, 0.9, 1.2)
    bonk = lambda t: pulse(t, 1.15, 1.6)
    proud = lambda t: keys(t, [(0, 0), (1.5, 0), (1.8, 1), (2.25, 1), (2.6, 0)])
    c.pos('root', lambda t: (0, -0.4 * s * reach(t) + 0.3 * s * proud(t), -0.6 * s * reach(t)))
    c.rot('body', lambda t: (-6 * reach(t) + 6 * bonk(t) + 4 * proud(t), 0, 0))
    c.rot(H, lambda t: (-6 * reach(t) + 22 * bonk(t) + 14 * proud(t), 0, 14 * bonk(t)))
    c.rot(EAR_R, lambda t: (30 * env(t, 0, 0.2, 0.5, 0.8) - 10 * proud(t), 0, 0))
    c.rot(EAR_L, lambda t: (30 * env(t, 0, 0.2, 0.5, 0.8) - 10 * proud(t), 0, 0))
    for leg in (LF, RF):
        c.rot(leg, lambda t: (14 * reach(t), 0, 0))
    c.rot(TAIL, lambda t: (12 * reach(t) - 90 * proud(t), 0, 0))
    c.rot(MID, lambda t: (4 * proud(t), 0, 0))
    c.rot(TIP, lambda t: (34 * proud(t), 0, 0))
    c.sound(1.25, 'happy', 0.6, 1.0)
    return c


def level_up(st, hops=2, twist=30, name='level_up'):
    """Bond level-up: a crouch then joyful pronks straight up with a twist in the air, landing in a proud stance with
    the tail high."""
    s, a = st.s, st.a
    span = 0.55
    L = 0.45 + hops * span + 0.75
    c = K(st, name, L)
    crouch = lambda t: pulse(t, 0.0, 0.5) * 0.9
    def air(t):
        v = 0.0
        for i in range(hops):
            t0 = 0.4 + i * span
            v += pulse(t, t0, t0 + span * 0.9) * (1.0 - 0.15 * i)
        return v
    def tw(t):
        v = 0.0
        for i in range(hops):
            t0 = 0.4 + i * span
            v += (1 if i % 2 == 0 else -1) * pulse(t, t0, t0 + span * 0.9)
        return v
    end = 0.4 + hops * span
    proud = lambda t: keys(t, [(0, 0), (end - 0.1, 0), (end + 0.15, 1), (L - 0.3, 1), (L, 0)])
    c.pos('root', lambda t: (0, -0.9 * s * crouch(t) + 3.2 * s * a * air(t), 0))
    c.rot('root', lambda t: (0, twist * a * tw(t), 0))
    c.rot('body', lambda t: (6 * air(t), 0, 0))
    for leg in (LF, RF):
        c.rot(leg, lambda t: (-20 * crouch(t) + 38 * air(t), 0, 0))
    for leg in (LB, RB):
        c.rot(leg, lambda t: (20 * crouch(t) - 34 * air(t), 0, 0))
    c.rot(H, lambda t: (10 * air(t) + 16 * proud(t), 0, 0))
    c.rot(EAR_R, lambda t: (-12 * proud(t) + 20 * air(t), 0, 0))
    c.rot(EAR_L, lambda t: (-12 * proud(t) + 20 * air(t), 0, 0))
    up = lambda t: keys(t, [(0, 0), (0.3, 1), (L - 0.3, 1), (L, 0)])
    q = lambda t: math.sin(2 * math.pi * 9 * t) * proud(t)
    c.rot(TAIL, lambda t: (-90 * up(t), 5 * q(t), 0))
    c.rot(MID, lambda t: (8 * up(t), 7 * q(t), 0))
    c.rot(TIP, lambda t: (34 * up(t), 9 * q(t), 0))
    if st.belly:
        c.scl('belly', lambda t: (1, 1 + 0.12 * st.chonk * math.sin(2 * math.pi * 4 * t) * step(t, 0.3, 0.5), 1))
    return c


# =============================================================================================== standing flourishes
def idle_shift(st):
    """A standing idle loop: slow weight shifts leg to leg, the head drifting about, a lazy tail swish, and once per
    loop a front paw lifted and set down."""
    s, a = st.s, st.a
    L = 6.4
    c = K(st, 'idle_shift', L, 'loop')
    c.pos('root', lambda t: (0.3 * s * wave(t, L, 2), 0, 0))
    c.rot('body', lambda t: (0, 0, 2.5 * a * wave(t, L, 2)))
    for leg, sg in ((LF, -1), (RF, -1), (LB, -1), (RB, -1)):
        c.rot(leg, lambda t, sg=sg: (0, 0, sg * 2.5 * a * wave(t, L, 2)))
    c.rot(RF, lambda t: (24 * pulse(t, 3.8, 4.5), 0, 0), add=True)
    c.pos(RF, lambda t: (0, 0.5 * s * pulse(t, 3.8, 4.5), 0))
    c.rot(H, lambda t: (4 * wave(t, L, 2, 0.1), 18 * wave(t, L, 1, 0.05), 4 * wave(t, L, 1, 0.3)))
    c.rot(EAR_R, lambda t: (0, 22 * pulse(t, 1.2, 2.1), 0))
    c.rot(EAR_L, lambda t: (0, -22 * pulse(t, 4.6, 5.4), 0))
    tail_chain(c, lambda t: (0, 16 * st.tail * wave(t, L, 2), 0), lag=0.25, gain=(1.2, 1.3, 1),
               rest_mid=(0, 0, 0), rest_tip=(10, 0, 0))
    return c


def groom_flank(st):
    """Twists round to lick its flank: head swings back along the side, quick licking bobs, a back paw shifts for
    balance, tail curls the same way."""
    s, a = st.s, st.a
    c = K(st, 'groom_flank', 3.4)
    e = lambda t: keys(t, [(0, 0), (0.55, 1), (2.75, 1), (3.4, 0)])
    lick = lambda t: math.sin(2 * math.pi * 3.2 * t) * env(t, 0.6, 0.7, 2.6, 2.7)
    c.pos('root', lambda t: (-0.3 * s * e(t), -0.3 * s * e(t), 0))
    c.rot('body', lambda t: (0, 22 * a * e(t), -5 * e(t)))
    c.rot(H, lambda t: (-24 * e(t) + 8 * lick(t), 92 * e(t), 22 * e(t) + 4 * lick(t)))
    c.rot(JAW, lambda t: (-6 * max(0, lick(t)), 0, 0))
    c.rot(RB, lambda t: (-14 * e(t), 0, -6 * e(t)))
    c.rot(LB, lambda t: (8 * e(t), 0, 0))
    c.rot(EAR_R, lambda t: (12 * e(t), 0, 0))
    c.rot(EAR_L, lambda t: (12 * e(t), 0, 0))
    tail_chain(c, lambda t: (6 * e(t), -38 * st.tail * e(t), 0), lag=0.1)
    return c


def biscuits(st, name='biscuits'):
    """Makes biscuits: settles its front end low and kneads, left paw, right paw, pressing in and pulling back,
    tail up with a happy hook, ears relaxed, purring. Eyes stay open."""
    s, a = st.s, st.a
    c = K(st, name, 4.6)
    e = lambda t: keys(t, [(0, 0), (0.5, 1), (4.0, 1), (4.6, 0)])
    kn = lambda t, ph: max(0.0, math.sin(2 * math.pi * 1.5 * (t - 0.5) + ph)) * env(t, 0.45, 0.6, 3.85, 4.0)
    c.pos('root', lambda t: (0, -0.5 * s * e(t), 0))
    c.rot('body', lambda t: (-7 * e(t), 0, 2.5 * math.sin(2 * math.pi * 1.5 * (t - 0.5)) * env(t, 0.5, 0.7, 3.8, 4.0)))
    c.rot(LF, lambda t: (-6 * e(t) + 30 * kn(t, 0), 0, 0))
    c.pos(LF, lambda t: (0, 0.7 * s * kn(t, 0), -0.3 * s * kn(t, 0)))
    c.rot(RF, lambda t: (-6 * e(t) + 30 * kn(t, math.pi), 0, 0))
    c.pos(RF, lambda t: (0, 0.7 * s * kn(t, math.pi), -0.3 * s * kn(t, math.pi)))
    for leg in (LB, RB):
        c.rot(leg, lambda t: (8 * e(t), 0, 0))
    c.rot(H, lambda t: (10 * e(t), 0, 5 * math.sin(2 * math.pi * 0.4 * t) * e(t)))
    c.rot(EAR_R, lambda t: (14 * e(t), 0, 16 * e(t)))
    c.rot(EAR_L, lambda t: (14 * e(t), 0, -16 * e(t)))
    q = lambda t: math.sin(2 * math.pi * 7 * t) * env(t, 0.6, 0.8, 3.8, 4.0)
    c.rot(TAIL, lambda t: (-80 * e(t), 4 * q(t), 0))
    c.rot(MID, lambda t: (10 * e(t), 6 * q(t), 0))
    c.rot(TIP, lambda t: (36 * e(t), 8 * q(t), 0))
    c.sound(0.7, 'heal', 0.55, 1.0)
    c.sound(2.4, 'heal', 0.5, 0.97)
    return c


def pounce_play(st, name='pounce_play', big=1.0):
    """Pounces at nothing: freezes on a sound, sinks into a crouch, wiggles its rear, springs, slams both front paws
    down, peers under them... nothing. Shakes it off and stands back up."""
    s, a = st.s, st.a * big
    c = K(st, name, 3.2)
    notice = lambda t: env(t, 0.0, 0.15, 1.5, 1.6)
    crouch = lambda t: env(t, 0.3, 0.65, 1.45, 1.55)
    wig = lambda t: env(t, 0.7, 0.8, 1.4, 1.5)
    jump = lambda t: pulse(t, 1.5, 1.95)
    land = lambda t: env(t, 1.85, 1.95, 2.4, 2.75)
    fwd = lambda t: keys(t, [(0, 0), (1.5, 0), (1.85, 1), (2.45, 1), (3.0, 0)])
    def root_p(t):
        return (0.3 * s * math.sin(2 * math.pi * 5 * t) * wig(t),
                -1.1 * s * crouch(t) + 2.6 * s * a * jump(t) - 0.5 * s * land(t),
                -1.6 * s * big * fwd(t))
    c.pos('root', root_p)
    c.rot('body', lambda t: (-3 * crouch(t) + 18 * a * jump(t) - 12 * land(t), 0, 6 * math.sin(2 * math.pi * 5 * t) * wig(t)))
    c.rot(LF, lambda t: (34 * crouch(t) + 60 * jump(t) + 18 * land(t), 0, 0))
    c.rot(RF, lambda t: (34 * crouch(t) + 60 * jump(t) + 18 * land(t), 0, 0))
    c.rot(LB, lambda t: (-32 * crouch(t) - 10 * math.sin(2 * math.pi * 5 * t) * wig(t) - 55 * jump(t), 0, 0))
    c.rot(RB, lambda t: (-32 * crouch(t) + 10 * math.sin(2 * math.pi * 5 * t) * wig(t) - 55 * jump(t), 0, 0))
    # step back into place while recovering
    c.rot(LF, lambda t: (-14 * pulse(t, 2.55, 2.8), 0, 0), add=True)
    c.rot(RF, lambda t: (-14 * pulse(t, 2.75, 3.0), 0, 0), add=True)
    c.rot(H, lambda t: (-10 * notice(t) + 6 * crouch(t) - 34 * land(t), 16 * notice(t) * (1 - crouch(t)) + 14 * land(t),
                        16 * land(t)))
    c.rot(EAR_R, lambda t: (-14 * notice(t), 0, 0))
    c.rot(EAR_L, lambda t: (-14 * notice(t), 0, 0))
    tail_chain(c, lambda t: (10 * crouch(t) - 30 * jump(t), 26 * st.tail * math.sin(2 * math.pi * 4 * t) * wig(t), 0),
               lag=0.07)
    c.sound(1.5, st.voice, 0.3, 1.45)
    return c


def stretch_back(st):
    """The hind-leg stretch: body forward, one back leg pushed straight out behind and trembling, then the other."""
    s, a = st.s, st.a
    c = K(st, 'stretch_back', 3.0)
    e = lambda t: keys(t, [(0, 0), (0.4, 1), (2.6, 1), (3.0, 0)])
    l1 = lambda t: env(t, 0.3, 0.7, 1.3, 1.6)
    l2 = lambda t: env(t, 1.5, 1.9, 2.4, 2.75)
    tr = lambda t: 2.5 * math.sin(2 * math.pi * 9 * t)
    c.pos('root', lambda t: (0, -0.2 * s * e(t), -0.7 * s * e(t)))
    c.rot('body', lambda t: (5 * e(t), 0, 0))
    c.rot(LF, lambda t: (-6 * e(t), 0, 0))
    c.rot(RF, lambda t: (-6 * e(t), 0, 0))
    c.rot(LB, lambda t: (-62 * a * l1(t) + tr(t) * l1(t), 0, 0))
    c.pos(LB, lambda t: (0, 0.5 * s * l1(t), 0))
    c.rot(RB, lambda t: (-62 * a * l2(t) + tr(t) * l2(t), 0, 0))
    c.pos(RB, lambda t: (0, 0.5 * s * l2(t), 0))
    c.rot(H, lambda t: (14 * e(t), 0, 0))
    c.rot(JAW, lambda t: (-14 * pulse(t, 0.6, 1.4), 0, 0))
    c.rot(EAR_R, lambda t: (20 * e(t), 0, 0))
    c.rot(EAR_L, lambda t: (20 * e(t), 0, 0))
    tail_chain(c, lambda t: (-55 * e(t), 0, 0), gain=(1, 1, 1), rest_tip=(0, 0, 0))
    c.sound(0.8, 'ambient_sleepy', 0.35, 1.0)
    return c


def claw_scratch(st):
    """Sharpens its claws: rears up and drags both front paws down an imaginary post, alternating, tail swishing."""
    s, a = st.s, st.a
    c = K(st, 'claw_scratch', 3.6)
    e = lambda t: keys(t, [(0, 0), (0.6, 1), (3.0, 1), (3.6, 0)])
    sc = lambda t, ph: math.sin(2 * math.pi * 1.4 * (t - 0.6) + ph) * env(t, 0.6, 0.8, 2.8, 3.0)
    c.pos('root', lambda t: (0, 1.4 * s * e(t), 0.8 * s * e(t)))
    c.rot('body', lambda t: (34 * a * e(t), 0, 3 * sc(t, 0)))
    c.rot(LB, lambda t: (-30 * e(t), 0, 0))
    c.rot(RB, lambda t: (-30 * e(t), 0, 0))
    c.rot(LF, lambda t: (60 * e(t) + 26 * sc(t, 0), 0, 0))
    c.rot(RF, lambda t: (60 * e(t) + 26 * sc(t, math.pi), 0, 0))
    c.rot(H, lambda t: (-26 * e(t), 0, 4 * sc(t, 0.5)))
    c.rot(EAR_R, lambda t: (8 * e(t), 0, 0))
    c.rot(EAR_L, lambda t: (8 * e(t), 0, 0))
    tail_chain(c, lambda t: (-20 * e(t), 22 * st.tail * sc(t, 1.0), 0), lag=0.12)
    return c


# =============================================================================================== sitting loops
def _sit(st, name, L):
    c = K(st, name, L, 'loop')
    c.base(st.S)
    return c


def _breath(c, st, L, amp=0.018):
    n = max(1, round(L / 3.2))
    c.scl('body', lambda t: (1 + amp * 0.5 * wave(t, L, n), 1 + amp * wave(t, L, n), 1 + amp * 0.5 * wave(t, L, n)))


def sit_wash(st):
    """Sitting: licks a front paw, wipes it over its face and ear, licks again. Starts and ends on the sit pose."""
    s = st.s
    L = 4.4
    c = _sit(st, 'sit_wash', L)
    up = lambda t: env(t, 0.3, 0.75, 3.55, 4.0)
    lick = lambda t: math.sin(2 * math.pi * 4 * t) * (env(t, 0.75, 0.85, 1.7, 1.8) + env(t, 2.75, 2.85, 3.45, 3.55))
    wipe = lambda t: env(t, 1.8, 2.05, 2.45, 2.7)
    sweep = lambda t: math.sin(2 * math.pi * 2 * (t - 1.9)) * env(t, 1.9, 2.0, 2.55, 2.65)
    c.rot(RF, lambda t: (72 * up(t) + 38 * wipe(t), 0, 14 * up(t) + 10 * sweep(t)), add=True)
    c.pos(RF, lambda t: (0.3 * s * up(t), 0.8 * s * up(t), 0), add=True)
    c.rot(H, lambda t: (-16 * up(t) + 6 * lick(t) + 22 * wipe(t), 18 * up(t) - 30 * wipe(t), 8 * up(t) + 16 * wipe(t)),
          add=True)
    c.rot(JAW, lambda t: (-7 * max(0.0, lick(t)), 0, 0))
    c.rot(EAR_R, lambda t: (36 * wipe(t), 0, 0), add=True)
    c.rot(LF, lambda t: (0, 0, -4 * up(t)), add=True)
    _breath(c, st, L)
    return c


def sit_tailwrap(st):
    """Sitting contentedly, tail wrapped round its paws; the tip flicks now and then, the head drifts to look about,
    an ear swivels. Eyes open the whole time."""
    L = 7.2
    c = _sit(st, 'sit_tailwrap', L)
    c.rot(H, lambda t: (3 * wave(t, L, 2), 26 * wave(t, L, 1, 0.1), 5 * wave(t, L, 1, 0.35)), add=True)
    c.rot(TIP, lambda t: (-18 * pulse(t, 1.6, 2.1) - 14 * pulse(t, 5.0, 5.4), 22 * pulse(t, 1.6, 2.1) + 16 * pulse(t, 5.0, 5.4), 0),
          add=True)
    c.rot(MID, lambda t: (0, 8 * pulse(t, 1.55, 2.15), 0), add=True)
    c.rot(EAR_L, lambda t: (8 * pulse(t, 3.4, 4.3), -40 * pulse(t, 3.4, 4.3), 0), add=True)
    c.rot(EAR_R, lambda t: (0, 12 * pulse(t, 6.0, 6.7), 0), add=True)
    _breath(c, st, L)
    return c


def sit_look(st):
    """Sitting sentry: head turns to something on one side, holds, swings to the other, ears pricking each way."""
    L = 5.6
    c = _sit(st, 'sit_look', L)
    yaw = lambda t: keys(t, [(0, 0), (0.5, 38), (1.9, 38), (2.5, -34), (4.2, -34), (4.9, 0), (5.6, 0)])
    pit = lambda t: keys(t, [(0, 0), (0.5, 8), (1.9, 6), (2.5, 2), (4.2, 10), (4.9, 0), (5.6, 0)])
    c.rot(H, lambda t: (pit(t), yaw(t), 0), add=True)
    c.rot(EAR_R, lambda t: (-8 * pulse(t, 0.3, 2.0), 0.5 * yaw(t), 0), add=True)
    c.rot(EAR_L, lambda t: (-8 * pulse(t, 2.3, 4.4), 0.5 * yaw(t), 0), add=True)
    c.rot(TIP, lambda t: (0, 18 * pulse(t, 2.4, 2.9), 0), add=True)
    _breath(c, st, L)
    return c


def loaf_pose(st):
    """The loaf from the sleep pose: legs tucked under, body flat and level, head up, eyes open."""
    out = {}
    for (bone, ch), v in st.Z.items():
        if bone == 'eyes':
            continue
        out[(bone, ch)] = v
    out[('body', 'rotation')] = REST
    out[(H, 'rotation')] = (-4.0, 0.0, 0.0)
    out[(H, 'position')] = (0.0, -0.2 * st.s, 0.3 * st.s)
    out[(EAR_R, 'rotation')] = (6.0, 0.0, 0.0)
    out[(EAR_L, 'rotation')] = (6.0, 0.0, 0.0)
    return out


def sit_loaf(st, loaf=None, L=18.0, name='sit_loaf'):
    """From the sit it settles into a loaf (paws tucked), dozes there wide-eyed with slow breaths and the odd ear
    flick, then sits back up: one loop = one loaf session."""
    loaf = loaf or loaf_pose(st)
    k = st.k
    c = Clip(st.m, name, L * k, 'loop')
    keys_ = sorted(set(st.S) | set(loaf))
    T0, T1, T2, T3 = 0.0, 1.3 * k, L * k - 1.3 * k, L * k

    def mix(key):
        rest = (1.0, 1.0, 1.0) if key[1] == 'scale' else REST
        a, b = st.S.get(key, rest), loaf.get(key, rest)

        def f(t):
            u = step(t, T0, T1) * (1 - step(t, T2, T3))
            return tuple(a[i] + (b[i] - a[i]) * u for i in range(3))
        return f
    for key in keys_:
        c.tracks[key] = mix(key)
    lo = lambda t: step(t, T0, T1) * (1 - step(t, T2, T3))
    c._add(EAR_R, 'rotation', (lambda f: lambda t: tuple(x + y for x, y in zip(f(t), (14 * pulse(t, 6 * k, 6.6 * k), 30 * pulse(t, 6 * k, 6.6 * k), 0)))
                               )(c.tracks[(EAR_R, 'rotation')]), False)
    c._add(H, 'rotation', (lambda f: lambda t: tuple(x + y for x, y in zip(f(t), (0, 20 * lo(t) * wave(t, L * k, 1, 0.25), 0)))
                           )(c.tracks[(H, 'rotation')]), False)
    n = max(1, round(L / 4.0))
    c._add('body', 'scale', lambda t: (1 + 0.01 * wave(t, L * k, n), 1 + 0.022 * wave(t, L * k, n), 1 + 0.01 * wave(t, L * k, n)), False)
    return c


# =============================================================================================== sleeping loops
def _sleep(st, name, L):
    c = K(st, name, L, 'loop')
    c.base(st.Z)
    return c


def sleep_curl(st):
    """Asleep, curling tighter: the nose tucks under the tail, then relaxes; slow deep breaths."""
    L = 12.0
    c = _sleep(st, 'sleep_curl', L)
    tuck = lambda t: env(t, 1.0, 2.5, 8.5, 10.0)
    c.rot(H, lambda t: (-8 * tuck(t), 14 * tuck(t), -6 * tuck(t)), add=True)
    c.rot(TAIL, lambda t: (0, 14 * tuck(t), 0), add=True)
    c.rot(MID, lambda t: (0, 16 * tuck(t), 0), add=True)
    c.rot(TIP, lambda t: (0, 18 * tuck(t), 0), add=True)
    c.rot(EAR_L, lambda t: (10 * tuck(t), 0, 0), add=True)
    c.rot(EAR_R, lambda t: (10 * tuck(t), 0, 0), add=True)
    _breath(c, st, L, 0.03)
    return c


def sleep_dream(st):
    """Dreaming: paws twitch in little runs, an ear flicks, the tail tip taps, a tiny silent mew."""
    L = 8.0
    c = _sleep(st, 'sleep_dream', L)
    tw = lambda t, t0: pulse(t, t0, t0 + 0.18)
    c.rot(LF, lambda t: (22 * (tw(t, 1.0) + tw(t, 1.35) + tw(t, 1.7)), 0, 0), add=True)
    c.rot(RF, lambda t: (22 * (tw(t, 1.17) + tw(t, 1.52) + tw(t, 5.4)), 0, 0), add=True)
    c.rot(LB, lambda t: (-18 * (tw(t, 1.1) + tw(t, 1.45)), 0, 0), add=True)
    c.rot(EAR_R, lambda t: (0, 0, 22 * tw(t, 3.2)), add=True)
    c.rot(EAR_L, lambda t: (0, 0, -22 * tw(t, 6.3)), add=True)
    c.rot(TIP, lambda t: (0, 20 * (tw(t, 4.0) + tw(t, 4.3)), 0), add=True)
    c.rot(JAW, lambda t: (-8 * pulse(t, 5.9, 6.3), 0, 0))
    c.rot(H, lambda t: (3 * pulse(t, 5.85, 6.4), 0, 0), add=True)
    _breath(c, st, L, 0.028)
    return c


def sleep_sprawl(st):
    """Too warm: rolls further onto its side and stretches its front legs out long, then curls back in."""
    s = st.s
    L = 16.0
    c = _sleep(st, 'sleep_sprawl', L)
    sp = lambda t: env(t, 1.0, 3.0, 12.5, 14.5)
    c.rot('body', lambda t: (0, 0, 38 * sp(t)), add=True)
    c.pos('root', lambda t: (0.6 * s * sp(t), 0, 0), add=True)
    for leg in (LF, RF):
        c.rot(leg, lambda t: (-60 * sp(t), 0, 0), add=True)
        c.pos(leg, lambda t: (0, -1.2 * s * sp(t), 0), add=True)
    for leg in (LB, RB):
        c.rot(leg, lambda t: (35 * sp(t), 0, 0), add=True)
        c.pos(leg, lambda t: (0, -1.0 * s * sp(t), 0), add=True)
    c.rot(H, lambda t: (6 * sp(t), -10 * sp(t), 22 * sp(t)), add=True)
    c.rot(TAIL, lambda t: (0, -50 * sp(t), 0), add=True)
    c.rot(MID, lambda t: (0, -30 * sp(t), 0), add=True)
    c.rot(TIP, lambda t: (0, -20 * sp(t), 0), add=True)
    _breath(c, st, L, 0.03)
    return c


# =============================================================================================== kitten play
def kitten_crab(st):
    """The kitten crab-walk: back arched high, legs stiff, tail up and bottle-brushed, two sideways hops out and
    two hops back."""
    s, a = st.s, st.a
    c = K(st, 'kitten_crab', 2.4)
    arch = lambda t: keys(t, [(0, 0), (0.3, 1), (2.05, 1), (2.4, 0)])
    hop = lambda t: sum(pulse(t, t0, t0 + 0.3) for t0 in (0.4, 0.75, 1.25, 1.6))
    side = lambda t: keys(t, [(0, 0), (0.4, 0), (0.7, 1.4), (1.05, 2.8), (1.25, 2.8), (1.55, 1.4), (1.9, 0), (2.4, 0)])
    c.pos('root', lambda t: (side(t) * s, 1.4 * s * a * hop(t), 0))
    c.rot('root', lambda t: (0, 0, -6 * hop(t)))
    c.pos('body', lambda t: (0, 1.0 * s * arch(t), 0))
    c.scl('body', lambda t: (1, 1 + 0.1 * arch(t), 1))
    for leg in (LF, RF, LB, RB):
        c.rot(leg, lambda t: (0, 0, 8 * hop(t)))
        c.pos(leg, lambda t: (0, -1.0 * s * arch(t), 0))
    c.rot(H, lambda t: (-12 * arch(t), -30 * arch(t), 8 * hop(t)))
    c.rot(EAR_R, lambda t: (34 * arch(t), 0, 16 * arch(t)))
    c.rot(EAR_L, lambda t: (34 * arch(t), 0, -16 * arch(t)))
    c.rot(TAIL, lambda t: (-95 * arch(t), 0, 0))
    c.rot(MID, lambda t: (-8 * arch(t), 0, 0))
    c.rot(TIP, lambda t: (-6 * arch(t), 0, 0))
    for b in (TAIL, MID, TIP):
        c.scl(b, lambda t: (1 + 0.45 * arch(t), 1 + 0.45 * arch(t), 1))
    c.sound(0.45, 'ambient_scared', 0.2, 1.4)
    return c


def kitten_bat(st):
    """Rears up on its hind legs batting at something dangling in the air, left, right, both, then drops."""
    s, a = st.s, st.a
    c = K(st, 'kitten_bat', 2.8)
    up = lambda t: keys(t, [(0, 0), (0.4, 1), (2.35, 1), (2.8, 0)])
    sw = lambda t, t0: pulse(t, t0, t0 + 0.28)
    c.pos('root', lambda t: (0, 1.3 * s * up(t), 1.0 * s * up(t)))
    c.rot('body', lambda t: (42 * a * up(t), 0, 4 * (sw(t, 0.6) - sw(t, 1.0))))
    c.rot(LB, lambda t: (-40 * up(t), 0, 0))
    c.rot(RB, lambda t: (-40 * up(t), 0, 0))
    c.rot(LF, lambda t: (40 * up(t) + 70 * (sw(t, 0.6) + sw(t, 1.4) + sw(t, 1.75)), 0, -20 * sw(t, 0.6)))
    c.rot(RF, lambda t: (40 * up(t) + 70 * (sw(t, 1.0) + sw(t, 1.4) + sw(t, 2.0)), 0, 20 * sw(t, 1.0)))
    c.rot(H, lambda t: (-30 * up(t) + 16 * (sw(t, 0.6) + sw(t, 1.0) + sw(t, 1.4)), 0, 10 * (sw(t, 0.6) - sw(t, 1.0))))
    c.rot(EAR_R, lambda t: (-12 * up(t), 0, 0))
    c.rot(EAR_L, lambda t: (-12 * up(t), 0, 0))
    tail_chain(c, lambda t: (-10 * up(t), 30 * math.sin(2 * math.pi * 2.5 * t) * up(t), 0), lag=0.06)
    c.sound(1.4, 'ambient', 0.25, 1.3)
    return c


def kitten_roll(st):
    """Flops onto its back and bicycles all four paws at the air, wriggling, then rolls back onto its feet."""
    s, a = st.s, st.a
    c = K(st, 'kitten_roll', 3.4)
    roll = lambda t: keys(t, [(0, 0), (0.35, 0.35), (0.6, 1), (2.7, 1), (3.0, 0.4), (3.4, 0)])
    play = lambda t: env(t, 0.65, 0.8, 2.55, 2.7)
    c.pos('root', lambda t: (0, -0.9 * s * roll(t) + 0.8 * s * roll(t) * roll(t), 0))
    c.rot('body', lambda t: (0, 0, 170 * roll(t) + 10 * math.sin(2 * math.pi * 1.5 * t) * play(t)))
    for leg, ph in ((LF, 0), (RF, math.pi), (LB, 0.5 * math.pi), (RB, 1.5 * math.pi)):
        c.rot(leg, lambda t, ph=ph, leg=leg: (40 * math.sin(2 * math.pi * 3 * t + ph) * play(t) + (30 if leg in (LF, RF) else -30) * roll(t),
                                     0, 0))
    c.rot(H, lambda t: (10 * roll(t), 20 * math.sin(2 * math.pi * 0.8 * t) * play(t), -30 * roll(t)))
    c.rot(EAR_R, lambda t: (20 * roll(t), 0, 0))
    c.rot(EAR_L, lambda t: (20 * roll(t), 0, 0))
    tail_chain(c, lambda t: (0, 40 * math.sin(2 * math.pi * 2 * t) * play(t), 0), lag=0.07)
    c.sound(0.7, 'ambient_happy', 0.3, 1.2)
    return c


def chirp(st):
    """Maine Coon chirp: chin up, a quick double trill (jaw), ears forward, tail tip hooks. Head / jaw / ears / tail."""
    c = K(st, 'chirp', 1.4)
    e = lambda t: keys(t, [(0, 0), (0.2, 1), (1.0, 1), (1.4, 0)])
    c.rot(H, lambda t: (16 * e(t), 0, 8 * pulse(t, 0.25, 0.95)))
    c.rot(JAW, lambda t: (-10 * (pulse(t, 0.25, 0.45) + pulse(t, 0.5, 0.7)), 0, 0))
    c.rot(EAR_R, lambda t: (-10 * e(t), 0, 0))
    c.rot(EAR_L, lambda t: (-10 * e(t), 0, 0))
    c.rot(TIP, lambda t: (30 * e(t), 0, 0))
    c.sound(0.25, 'ambient', 0.6, 1.05)
    return c


# =============================================================================================== fixes for old clips
def unspin(m, src):
    """A re-baked copy of an existing clip whose root makes whole turns (zoomies, circling, tail chase, spin): animkit
    bakes rotations wrapped into (-180, 180] with pre / post keys, so the clip ends numerically where it started and
    the cross-fade into the next clip no longer unwinds a full reverse spin."""
    c0 = m.clips[src]
    loop = {True: 'loop', 'hold_on_last_frame': 'hold'}.get(c0.get('loop'), 'once')
    c = Clip(m, src, float(c0.get('animation_length', 1)), loop)
    return c.copy(src)


def resound(m, src, sounds, length=None):
    """A copy of an existing clip with sound keyframes added: sounds = [(t, event, volume, pitch)]."""
    c0 = m.clips[src]
    loop = {True: 'loop', 'hold_on_last_frame': 'hold'}.get(c0.get('loop'), 'once')
    c = Clip(m, src, float(c0.get('animation_length', 1)), loop)
    c.copy(src)
    for s in sounds:
        c.sound(*s)
    return c


# =============================================================================================== tables
def common(st, sit_loaf_pose=None, kitten=False):
    """The clips every cat gets (the caller adds species signatures)."""
    m = st.m
    out = [hurt(st), tail_flick(st), tail_quiver(st), ear_swivel(st), watch_bug(st), pet_sit(st),
           idle_shift(st), groom_flank(st), pounce_play(st, big=1.3 if kitten else 1.0), stretch_back(st),
           sit_wash(st), sit_tailwrap(st), sit_look(st), sit_loaf(st, sit_loaf_pose),
           sleep_curl(st), sleep_dream(st), sleep_sprawl(st)]
    for c in out:
        m.put(c)
    return out


TAGS = {
    'tail_flick': {'layer': 'overlay', 'move': True}, 'tail_quiver': {'layer': 'overlay', 'move': True},
    'ear_swivel': {'layer': 'overlay', 'move': True}, 'watch_bug': {'layer': 'overlay', 'move': True},
    'pet_sit': {'layer': 'overlay', 'move': True}, 'chirp': {'layer': 'overlay', 'move': True},
    'sit_wash': {'p': 'sitting'}, 'sit_tailwrap': {'p': 'sitting'}, 'sit_look': {'p': 'sitting'}, 'sit_loaf': {'p': 'sitting'},
    'sleep_curl': {'p': 'lying'}, 'sleep_dream': {'p': 'lying'}, 'sleep_sprawl': {'p': 'lying'},
    'kitten_roll': {'p': 'belly'}, 'pounce_play': {'p': 'standing'}, 'level_up': {'p': 'standing'},
    'kitten_crab': {'p': 'standing'}, 'kitten_bat': {'p': 'standing'}, 'claw_scratch': {'p': 'standing'},
}


def idle_tables(st, extra_standing=(), extra_sitting=(), extra_lying=(), lazy=1.0, playful=1.0):
    """Idle pools. Standing one-shots get cooldowns long enough that nothing repeats back to back; tail / ear
    overlays carry the mood (flick when alert or a monster is near, quiver when the owner is close)."""
    sit_L = lambda n: round(st.m.length(n), 2)
    standing = [
        {'clip': 'idle_shift', 'w': 3 * lazy, 'min': 6.4 * st.k, 'max': 19.2 * st.k, 'cd': 20},
        {'clip': 'groom_flank', 'w': 1.2, 'cd': 40},
        {'clip': 'stretch_back', 'w': 0.8, 'cd': 60, 'boost': {'dawn': 4}},
        {'clip': 'pounce_play', 'w': 0.8 * playful, 'cd': 45, 'when': '!night', 'boost': {'owner_near': 1.5}},
        {'clip': 'watch_bug', 'w': 1.0, 'cd': 30, 'when': 'day'},
        {'clip': 'ear_swivel', 'w': 1.2, 'cd': 14},
        {'clip': 'tail_flick', 'w': 0.6, 'cd': 16, 'when': '!happy'},
        {'clip': 'tail_flick', 'w': 2.5, 'cd': 8, 'when': 'mood:idle_alert'},
        {'clip': 'tail_quiver', 'w': 0.4, 'cd': 25, 'when': '!happy', 'boost': {'owner_near': 3}},
    ] + list(extra_standing)
    sitting = [
        {'clip': 'sit_tailwrap', 'w': 3, 'min': sit_L('sit_tailwrap'), 'max': 2 * sit_L('sit_tailwrap'), 'cd': 20},
        {'clip': 'sit_wash', 'w': 2, 'min': sit_L('sit_wash'), 'max': sit_L('sit_wash'), 'cd': 30},
        {'clip': 'sit_look', 'w': 2, 'min': sit_L('sit_look'), 'max': sit_L('sit_look'), 'cd': 20, 'boost': {'night': 1.5}},
        {'clip': 'sit_loaf', 'w': 1.5 * lazy, 'min': sit_L('sit_loaf'), 'max': sit_L('sit_loaf'), 'cd': 60,
         'boost': {'cold': 2, 'night': 1.5}},
        {'clip': 'ear_swivel', 'w': 1, 'cd': 16},
        # tail / head overlays would unwrap the sitting tail or tilt the seated head: not while sitting
        {'clip': 'tail_flick', 'w': 0}, {'clip': 'tail_quiver', 'w': 0}, {'clip': 'watch_bug', 'w': 0},
        {'clip': 'pet_sit', 'w': 0},
    ] + list(extra_sitting)
    lying = [
        {'clip': 'sleep_curl', 'w': 2, 'min': sit_L('sleep_curl'), 'max': sit_L('sleep_curl'), 'cd': 30, 'boost': {'cold': 3}},
        {'clip': 'sleep_dream', 'w': 1.5, 'min': sit_L('sleep_dream'), 'max': 2 * sit_L('sleep_dream'), 'cd': 40},
        {'clip': 'sleep_sprawl', 'w': 1, 'min': sit_L('sleep_sprawl'), 'max': sit_L('sleep_sprawl'), 'cd': 90,
         'when': '!cold', 'boost': {'hot': 4}},
    ] + list(extra_lying)
    return {'standing': standing, 'sitting': sitting, 'lying': lying}
