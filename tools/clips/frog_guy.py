"""Frog Guy (v2, on all fours): every clip authored here (the source bbmodel has none). Source model:
Documents/Blockbench/models/arcana/work/frogguy/frogguy.py.

Character: a stumpy four-legged frog in the Arcana pet style, in an orange scout sash. Astute and aloof, and a bit of
a derp: chin up, half-lidded side-eye (the right lid droops lower), the tip of his tongue always poking out of his
smile (the blep), a big rolling waddle.
  * the blink: the lids drop fast, hold a beat and roll back up with a little wide-eyed overshoot while the eyeballs
    sink into his head; the right eye always lags a frame behind the left (derp). Lids are scale-y caps pivoted at their
    top edge (scale > 1 grows them down over the eye), so animtags' strip_blinks (scale < 0.55) leaves them alone.
  * the waddle: diagonal pairs of stumps (left front + right back), the whole bean rolling side to side over them, head
    counter-rolling a beat late, the blep flapping, the googly eyes jiggling.
  * aloof: nose in the air, turns his head away, unimpressed half-lids, a slow smug blink.
  * astute: fly_snap: freezes, locks onto a fly with narrowed eyes, butt-wiggles, and snaps it out of the air.
  * derp: derp_tilt (slow head tilt, mismatched eyes, blep wiggle), splat (legs slide out, flat on his belly).
Rig (+x = his left, front -z; Blockbench convention: rot x+ pitches the front UP (a hanging leg swings its foot forward),
y+ turns toward -x, z+ raises the +x side): root > body > h_head > jaw > tongue > tongue_tip, jaw > throat,
jaw > blep, h_head > eye_l > lid_l, eye_r > lid_r; body > left_front_leg / right_front_leg / left_back_leg /
right_back_leg (the sash is plain body cubes). The tongue rests back inside the jaw (pos z -N slides it out)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, wave, pulse, step, keys, decay, overshoot, posture_set  # noqa: E402

# lid scale y that shuts each eye fully (the lids are different heights at rest: the right one droops)
LID_FULL = {'l': 3.1, 'r': 2.15}
LID_WIDE = 0.6
TONGUE_OUT = 5.5
LF, RF, LB, RB = 'left_front_leg', 'right_front_leg', 'left_back_leg', 'right_back_leg'
LEGS = (LF, RF, LB, RB)


def hold(c, pose, skip=()):
    for (b, ch), v in pose.items():
        if (b, ch) not in skip:
            c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)
    return c


def add(pose, bone, ch, fn):
    base = pose.get((bone, ch), (1, 1, 1) if ch == 'scale' else (0, 0, 0))
    return lambda t: tuple(a + b for a, b in zip(base, fn(t)))


def blink(t, t0, close=0.1, shut=0.05, opn=0.2, wide=0.25):
    """one blink starting at t0: 0 open, 1 shut, a little negative (wide-eyed) on the rebound."""
    if t <= t0:
        return 0.0
    u = t - t0
    if u < close:
        return step(u, 0, close)
    u -= close
    if u < shut:
        return 1.0
    u -= shut
    if u < opn:
        return 1 - step(u, 0, opn)
    u -= opn
    if u < 0.25:
        return -wide * pulse(u, 0, 0.25)
    return 0.0


def eyes(c, base=lambda t: 0.0, blinks=(), lag=0.05, sink=0.7, right=None, slow=1.0):
    """Lids for both eyes. base(t): shut fraction (1 shut, 0 rest, -1 wide open); blinks: start times; the right eye
    blinks `lag` s after the left. right(t): extra shut fraction for the right eye only. Eye sink adds onto any eye
    position track already in the clip."""
    def s(side):
        d = lag if side == 'r' else 0.0
        extra = right if (right and side == 'r') else (lambda t: 0.0)
        return lambda t: max(-1.0, min(1.0, base(t) + extra(t) + sum(
            blink(t, t0 + d, 0.1 * slow, 0.05 * slow, 0.2 * slow) for t0 in blinks)))
    for side in ('l', 'r'):
        f = s(side)
        full = LID_FULL[side]
        c.scl(f'lid_{side}', (lambda f, full: lambda t: (
            1, 1 + f(t) * (full - 1) if f(t) >= 0 else 1 + f(t) * (1 - LID_WIDE), 1))(f, full))
        c.pos(f'eye_{side}', (lambda f: lambda t: (0, -sink * max(0.0, f(t)) + 0.35 * max(0.0, -f(t)), 0))(f), add=True)


def legs_pose(c, fn):
    """fn(leg, t) -> rotation for each leg."""
    for lg in LEGS:
        c.rot(lg, (lambda lg: lambda t: fn(lg, t))(lg))


def tongue(c, out_fn, rot_fn=None, tip_fn=None, hide_blep=None):
    c.pos('tongue', lambda t: (0, 0, -TONGUE_OUT * out_fn(t)))
    if rot_fn:
        c.rot('tongue', rot_fn)
    if tip_fn:
        c.rot('tongue_tip', tip_fn)
    hb = hide_blep or out_fn
    c.pos('blep', lambda t: (0, 0, 1.0 * min(1.0, 3 * hb(t))))


def croak(c, spans, size=1.9):
    """the throat sac balloons out under his chin for each (t0, t1)."""
    puff = lambda t: sum(pulse(t, a, b) for a, b in spans)
    c.scl('throat', lambda t: (1 + 0.3 * (size - 1) * puff(t), 1 + 3.0 * (size - 1) * puff(t), 1 + 0.4 * (size - 1) * puff(t)))
    c.pos('throat', lambda t: (0, -0.6 * puff(t), -0.3 * puff(t)))


def blep_wiggle(c, L, amt=12, rate=None, length=0.0):
    """the blep flaps / wiggles; length > 0 lets more tongue droop out (scale y)."""
    r = rate or L
    c.rot('blep', lambda t: (amt * wave(t, r), 0, 0.5 * amt * wave(t, r, 1, 0.25)))
    if length:
        c.scl('blep', lambda t: (1, 1 + length * (0.5 + 0.5 * wave(t, L)), 1))


def clips(m):
    out = []

    def put(c):
        m.put(c)
        out.append(c)
        return c

    # poses -------------------------------------------------------------------------------------------------------
    # the frog sit: front legs straight, rump down, body tipped nose-up, chin held very high (aloof)
    SIT = {('body', 'position'): (0, -1.3, 0.6), ('body', 'rotation'): (16, 0, 0), ('h_head', 'rotation'): (-4, 0, 0),
           (LF, 'rotation'): (-16, 0, 0), (RF, 'rotation'): (-16, 0, 0), (LF, 'position'): (0, -0.9, 0),
           (RF, 'position'): (0, -0.9, 0),
           (LB, 'rotation'): (-16, 0, 14), (RB, 'rotation'): (-16, 0, -14), (LB, 'position'): (0, 1.5, 0.4),
           (RB, 'position'): (0, 1.5, 0.4)}
    # sleep: the splat. Belly flat on the ground, legs splayed out front and back, chin down, lids shut, blep out
    SPLAT = {('body', 'position'): (0, -2.1, 0), ('h_head', 'rotation'): (-3, 0, 0),
             (LF, 'rotation'): (70, 0, 28), (RF, 'rotation'): (70, 0, -28),
             (LB, 'rotation'): (-75, 0, 30), (RB, 'rotation'): (-75, 0, -30),
             (LF, 'position'): (0, 0.4, 0), (RF, 'position'): (0, 0.4, 0),
             (LB, 'position'): (0, 0.6, 0), (RB, 'position'): (0, 0.6, 0)}
    SWIM = {('body', 'position'): (0, 1.0, 0), ('h_head', 'rotation'): (8, 0, 0),
            (LF, 'rotation'): (-70, 0, 20), (RF, 'rotation'): (-70, 0, -20)}

    # ------------------------------------------------------------------ standing loops
    # idle: chin up, breathing, the blep twitching, a slow look one way, a blink (the right eye a frame late)
    L = 4.0
    c = Clip(m, 'idle', L, 'loop')
    c.rot('body', lambda t: (0.8 * wave(t, L, 2), 0, 1.0 * wave(t, L)))
    c.pos('body', lambda t: (0, 0.12 * wave(t, L, 2), 0))
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L, 2), 1 - 0.01 * wave(t, L, 2), 1 + 0.015 * wave(t, L, 2)))
    c.rot('h_head', lambda t: (6 + 1.5 * wave(t, L, 1, 0.2), 6 * wave(t, L, 1, 0.35), -1.5 * wave(t, L)))
    blep_wiggle(c, L, 6, L / 2)
    croak(c, [])
    eyes(c, blinks=[1.2, 3.1])
    put(c)

    # idle_look: astute little head snaps, like he is tracking something no one else can see; eyes narrow on it
    L = 5.0
    c = Clip(m, 'idle_look', L, 'loop')
    c.rot('body', lambda t: (0, 0, 1.0 * wave(t, L)))
    yaw = lambda t: keys(t, [(0, 0), (0.45, 0), (0.6, 28), (1.9, 28), (2.05, 12), (2.9, 12), (3.05, -26), (4.3, -26),
                             (4.6, 0), (5.0, 0)], 'out')
    pitch = lambda t: keys(t, [(0, 6), (0.45, 6), (0.6, 12), (1.9, 12), (2.05, 4), (2.9, 4), (3.05, 9), (4.3, 9),
                               (4.6, 6), (5.0, 6)], 'out')
    c.rot('h_head', lambda t: (pitch(t), yaw(t), -0.15 * yaw(t)))
    eyes(c, base=lambda t: 0.3 * pulse(t, 0.6, 1.9) + 0.35 * pulse(t, 3.05, 4.3), blinks=[2.2])
    blep_wiggle(c, L, 5, L / 3)
    put(c)

    # the waddle: diagonal stumps, the whole bean rolling over them, the head counter-rolling a beat late
    def waddle(name, L, stride, roll, lift, bob, chin, yaw=5, blep=14, n=4):
        """n strides per loop (a long loop, so a blink in it does not come round every step)"""
        c = Clip(m, name, L, 'loop')
        W = lambda t, k=1, p=0.0: wave(t, L, k * n, p)
        # phase 0: left front + right back swing forward; phase 0.5: the other pair
        ph = {LF: 0.0, RB: 0.0, RF: 0.5, LB: 0.5}
        for lg, p in ph.items():
            back = lg in (LB, RB)
            c.rot(lg, (lambda p, back: lambda t: (
                stride * (0.8 if back else 1.0) * W(t, 1, p), 0,
                -roll * W(t, 1, 0.25)))(p, back))
            c.pos(lg, (lambda p: lambda t: (0, lift * max(0.0, W(t, 1, p + 0.25)), 0))(p))
        # the left side rises while the left front stump lifts
        c.rot('body', lambda t: (1.5 * W(t, 2, 0.1), yaw * W(t), roll * W(t, 1, 0.25)))
        c.pos('body', lambda t: (0.35 * W(t, 1, 0.25), bob * abs(W(t)), 0))
        c.rot('h_head', lambda t: (chin + 2 * W(t, 2, 0.3), -yaw * 0.8 * W(t, 1, 0.12),
                                   -roll * 0.7 * W(t, 1, 0.37)))
        c.rot('jaw', lambda t: (-1.5 * max(0.0, W(t, 2, 0.4)), 0, 0))
        c.rot('blep', lambda t: (blep * W(t, 2, 0.5), 0, 0.6 * blep * W(t, 1, 0.45)))
        for s in ('l', 'r'):     # googly eyes jiggle on every step
            c.pos(f'eye_{s}', (lambda s: lambda t: (0, 0.25 * W(t, 2, 0.6 if s == 'l' else 0.75), 0))(s))
        return c

    c = waddle('walk', 3.2, 26, 8, 1.2, 0.35, 6)
    eyes(c, base=lambda t: 0.1, blinks=[1.9])
    put(c)
    c = waddle('walk_happy', 2.6, 30, 11, 1.5, 0.9, 10, yaw=7, blep=20)
    c.scl('blep', lambda t: (1, 1.35, 1))
    eyes(c, base=lambda t: 0.3, blinks=[1.1])
    put(c)
    # run: a stumpy scramble: head low and forward, everything bouncing, eyes wide
    c = waddle('run', 1.7, 40, 7, 2.0, 0.9, -2, yaw=4, blep=26)
    c.scl('body', lambda t: (1 + 0.04 * wave(t, 1.7, 8, 0.1), 1 - 0.04 * wave(t, 1.7, 8, 0.1), 1 + 0.03 * wave(t, 1.7, 8)))
    c.scl('blep', lambda t: (1, 1.6, 1))
    eyes(c, base=lambda t: -0.5)
    put(c)

    # ------------------------------------------------------------------ jumps: a proper frog hop
    c = Clip(m, 'jump_start', 0.3)
    k = lambda t: pulse(t, 0, 0.3)
    c.pos('body', lambda t: (0, -1.4 * k(t), 0.4 * k(t)))
    c.rot('body', lambda t: (8 * k(t), 0, 0))
    c.scl('body', lambda t: (1 + 0.1 * k(t), 1 - 0.12 * k(t), 1 + 0.06 * k(t)))
    for lg in (LB, RB):
        c.pos(lg, lambda t: (0, 1.4 * k(t), 0))
    for lg in (LF, RF):
        c.rot(lg, lambda t: (-8 * k(t), 0, 0))
        c.pos(lg, lambda t: (0, 0.8 * k(t), 0))
    eyes(c, base=lambda t: 0.5 * k(t))
    put(c)

    L = 0.4
    c = Clip(m, 'jump', L, 'loop')
    c.rot('body', lambda t: (14, 0, 0))
    c.scl('body', lambda t: (0.95, 1.05, 1.08))
    c.rot(LB, lambda t: (-70 + 4 * wave(t, L), 0, 16))
    c.rot(RB, lambda t: (-70 + 4 * wave(t, L), 0, -16))
    c.rot(LF, lambda t: (50 + 5 * wave(t, L), 0, 10))
    c.rot(RF, lambda t: (50 + 5 * wave(t, L), 0, -10))
    c.rot('h_head', lambda t: (-6, 0, 0))
    c.rot('blep', lambda t: (-40, 0, 0))
    c.scl('blep', lambda t: (1, 1.5, 1))
    eyes(c, base=lambda t: -0.8)
    put(c)

    L = 0.5
    c = Clip(m, 'jump_end', L)
    k = lambda t: pulse(t, 0, 0.32)
    c.pos('body', lambda t: (0, -1.5 * k(t), 0))
    c.scl('body', lambda t: (1 + 0.16 * k(t), 1 - 0.18 * k(t), 1 + 0.08 * k(t)))
    c.rot('h_head', lambda t: (-8 * k(t) + 4 * decay(t, 0.25, 3, 6), 0, 0))
    for lg in LEGS:
        c.pos(lg, lambda t: (0, 1.4 * k(t), 0))
    c.rot('blep', lambda t: (30 * decay(t, 0.05, 4, 5), 0, 0))
    for s in ('l', 'r'):
        c.pos(f'eye_{s}', lambda t: (0, -0.8 * k(t) + 0.4 * decay(t, 0.25, 3, 6), 0))
    put(c)

    # ------------------------------------------------------------------ water: frog kicks
    L = 0.9
    c = Clip(m, 'swim', L, 'loop')
    hold(c, SWIM, skip={('body', 'position')})
    kick = lambda t: pulse(t, 0, L * 0.45)
    c.pos('body', add(SWIM, 'body', 'position', lambda t: (0, 0.3 * wave(t, L), 0.6 * kick(t))))
    c.rot(LB, lambda t: (-85 + 55 * kick(t), 0, 40 - 30 * kick(t)))
    c.rot(RB, lambda t: (-85 + 55 * kick(t), 0, -40 + 30 * kick(t)))
    c.rot('blep', lambda t: (-20 * kick(t), 0, 0))
    eyes(c, blinks=[0.6])
    put(c)

    L = 3.0
    c = Clip(m, 'idle_swim', L, 'loop')
    c.pos('body', lambda t: (0, 0.8 + 0.4 * wave(t, L), 0))
    c.rot('body', lambda t: (-4, 0, 2 * wave(t, L)))
    c.rot(LB, lambda t: (-50 + 14 * wave(t, L, 2), 0, 34))
    c.rot(RB, lambda t: (-50 - 14 * wave(t, L, 2), 0, -34))
    c.rot(LF, lambda t: (-20 + 10 * wave(t, L, 2, 0.5), 0, 30))
    c.rot(RF, lambda t: (-20 - 10 * wave(t, L, 2, 0.5), 0, -30))
    c.rot('h_head', lambda t: (8, 5 * wave(t, L), 0))
    eyes(c, base=lambda t: 0.2, blinks=[1.5])
    put(c)

    # ------------------------------------------------------------------ postures
    L = 3.0
    c = Clip(m, 'sit', L, 'loop')
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (1.5 * wave(t, L), 4 * wave(t, L, 1, 0.3), 0)))
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L), 1, 1 + 0.02 * wave(t, L)))
    blep_wiggle(c, L, 5, L / 2)
    eyes(c, base=lambda t: 0.15, blinks=[1.8])
    put(c)

    L = 4.0
    c = Clip(m, 'sleep', L, 'loop')
    hold(c, SPLAT)
    c.scl('body', lambda t: (1 + 0.025 * wave(t, L), 1 - 0.015 * wave(t, L), 1 + 0.02 * wave(t, L)))
    c.rot('h_head', lambda t: (-3 + 1.5 * wave(t, L), 0, 3))
    croak(c, [(1.0, 2.6)], 1.5)                                     # snoring into his throat sac
    c.scl('blep', lambda t: (1, 1.5 + 0.3 * wave(t, L), 1))
    c.rot('blep', lambda t: (-6 + 6 * wave(t, L), 0, 0))
    c.rot('jaw', lambda t: (-3 - 2 * wave(t, L), 0, 0))
    eyes(c, base=lambda t: 1.0, sink=0.5)
    put(c)

    L = 3.0
    c = Clip(m, 'sit_look', L, 'loop')
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (
        4 * pulse(t, 0, L), keys(t, [(0, 0), (0.25, 0), (0.4, 30), (1.4, 30), (1.55, -24), (2.6, -24), (3.0, 0)], 'out'), 0)))
    eyes(c, base=lambda t: 0.3 * pulse(t, 0.4, 2.6), blinks=[1.0])
    put(c)

    # ------------------------------------------------------------------ his signature bits
    # the slow smug blink: two long, satisfied blinks, eyes sinking deep, chin rising between them
    L = 1.9
    c = Clip(m, 'frog_blink', L)
    eyes(c, blinks=[0.2, 0.95], lag=0.08, sink=1.2, slow=2.4)
    c.pos('h_head', lambda t: (0, -0.3 * (pulse(t, 0.2, 0.9) + pulse(t, 0.95, 1.65)), 0))
    c.rot('h_head', lambda t: (8 * pulse(t, 0.05, 1.85), 0, 3 * pulse(t, 0.6, 1.85)))
    put(c)

    # licks his own right eyeball like a gecko: head tips, mouth opens, the tongue shoots out and curls up over the
    # right eye (which squints shut under it), two wipes, slurp, a pleased blink
    L = 2.6
    c = Clip(m, 'eye_lick', L)
    up = lambda t: step(t, 0.2, 0.45) * (1 - step(t, 1.85, 2.1))
    c.rot('h_head', lambda t: (10 * up(t), 10 * up(t), 14 * up(t)))
    c.rot('jaw', lambda t: (-18 * up(t), 0, 0))
    lick = lambda t: wave(t, 0.45) * pulse(t, 0.65, 1.75)
    reach = lambda t: step(t, 0.3, 0.55) * (1 - step(t, 1.8, 2.0))
    out_ = lambda t: step(t, 0.3, 0.5) * (1 - step(t, 1.85, 2.05))
    tongue(c, out_, lambda t: (68 * reach(t) + 6 * lick(t), 26 * reach(t), 32 * reach(t)),
           lambda t: (78 * reach(t) + 14 * lick(t), 0, 0), hide_blep=up)
    c.scl('tongue_tip', lambda t: (1.2, 1, 1 + 2.4 * reach(t)))
    eyes(c, base=lambda t: 0.15 * up(t), right=lambda t: 0.85 * pulse(t, 0.5, 1.9), blinks=[2.15])
    c.rot(LF, lambda t: (-6 * up(t), 0, 4 * up(t)))
    c.sound(1.9, 'minecraft:entity.frog.eat', 0.5, 1.5)
    put(c)

    # croak: chin up, the throat sac balloons twice
    L = 1.8
    c = Clip(m, 'croak', L)
    c.rot('h_head', lambda t: (14 * pulse(t, 0.05, 1.75), 0, 0))
    croak(c, [(0.25, 0.75), (0.85, 1.45)], 2.0)
    eyes(c, base=lambda t: 0.4 * pulse(t, 0.05, 1.75))
    c.sound(0.3, 'ambient')
    put(c)

    # aloof: lifts his chin, turns his head right away from you, unimpressed half-lids, one slow blink, turns back
    L = 3.4
    c = Clip(m, 'aloof', L)
    turn = lambda t: keys(t, [(0, 0), (0.35, 0), (0.75, 1), (2.6, 1), (3.1, 0), (3.4, 0)])
    c.rot('h_head', lambda t: (16 * turn(t), -38 * turn(t), -6 * turn(t)))
    c.rot('body', lambda t: (2 * turn(t), -6 * turn(t), 0))
    eyes(c, base=lambda t: 0.55 * turn(t), blinks=[1.5], lag=0.1, slow=2.2)
    c.rot('blep', lambda t: (-10 * turn(t), 0, 0))
    put(c)

    # derp_tilt: slowly tilts his whole head over, eyes go mismatched (left wide, right half shut), blep wiggles out,
    # holds the stare, then snaps upright with a wobble
    L = 2.8
    c = Clip(m, 'derp_tilt', L)
    tilt = lambda t: step(t, 0.1, 0.9) * (1 - step(t, 2.0, 2.15))
    c.rot('h_head', lambda t: (4 * tilt(t), 6 * tilt(t), 30 * tilt(t) - 10 * decay(t, 2.15, 3.5, 4)))
    c.rot('body', lambda t: (0, 0, 4 * tilt(t)))
    eyes(c, base=lambda t: -0.7 * tilt(t), right=lambda t: 1.15 * tilt(t), blinks=[2.3])
    c.scl('blep', lambda t: (1, 1 + 0.8 * tilt(t), 1))
    c.rot('blep', lambda t: (14 * wave(t, 0.35) * tilt(t), 0, 10 * tilt(t)))
    c.rot('jaw', lambda t: (-4 * tilt(t), 0, 0))
    put(c)

    # fly_snap: freezes and locks on to a fly (up and to his left), eyes narrowed, a little butt wiggle, then SNAP:
    # the tongue zips out and back, gulp, a smug slow blink
    L = 2.6
    c = Clip(m, 'fly_snap', L)
    lock = lambda t: step(t, 0.1, 0.25) * (1 - step(t, 2.0, 2.4))
    track = lambda t: keys(t, [(0, 0), (0.25, 22), (0.8, 30), (1.2, 26), (1.4, 26)])
    c.rot('h_head', lambda t: (18 * lock(t), -track(t) * lock(t), 0))
    wig = lambda t: wave(t, 0.22) * pulse(t, 0.7, 1.35)
    c.rot('body', lambda t: (-3 * lock(t), 6 * wig(t), 2 * wig(t)))
    for lg in (LB, RB):
        c.pos(lg, lambda t: (0, 0.7 * pulse(t, 0.6, 1.4), 0))
    snap = lambda t: pulse(t, 1.4, 1.62) ** 0.5
    c.rot('jaw', lambda t: (-22 * pulse(t, 1.35, 1.7), 0, 0))
    tongue(c, snap, lambda t: (40 * snap(t), -18 * snap(t), 0), hide_blep=lambda t: pulse(t, 1.3, 1.9))
    c.scl('throat', lambda t: (1 + 0.3 * pulse(t, 1.75, 2.1), 1 + 1.6 * pulse(t, 1.75, 2.1), 1))
    eyes(c, base=lambda t: 0.4 * lock(t) * (1 - pulse(t, 1.38, 1.5)), blinks=[2.05], slow=2.0, lag=0.08)
    c.sound(1.45, 'minecraft:entity.frog.tongue', 0.6, 1.3)
    c.sound(1.8, 'minecraft:entity.frog.eat', 0.5, 1.4)
    put(c)

    # splat: legs slide out to the sides and he goes flat on his belly, lies there blinking with the blep out, then
    # shoves himself back up
    L = 3.2
    c = Clip(m, 'splat', L)
    dn = lambda t: overshoot(t, 0.1, 0.45, 0.08) * (1 - step(t, 2.5, 3.0))
    for (b, ch), v in SPLAT.items():
        c.tracks[(b, ch)] = (lambda v: lambda t: tuple(x * dn(t) for x in v))(v)
    c.scl('body', lambda t: (1 + 0.06 * pulse(t, 0.4, 0.7), 1 - 0.08 * pulse(t, 0.4, 0.7), 1))
    c.scl('blep', lambda t: (1, 1 + 0.6 * dn(t), 1))
    c.rot('blep', lambda t: (8 * wave(t, 0.6) * dn(t), 0, 0))
    c.rot('jaw', lambda t: (-3 * dn(t), 0, 0))
    eyes(c, base=lambda t: 0.2 * dn(t), blinks=[1.2, 1.9], slow=1.6)
    put(c)

    # the land-shark butt wiggle: the rump swings side to side while the head stays put, very pleased with himself
    L = 1.6
    c = Clip(m, 'wiggle', L)
    sh = lambda t: wave(t, 0.32) * pulse(t, 0.05, 1.55)
    c.rot('body', lambda t: (0, 12 * sh(t), 5 * sh(t)))
    c.rot('h_head', lambda t: (6 * pulse(t, 0.05, 1.55), -12 * sh(t), -5 * sh(t)))
    for lg in (LB, RB):
        c.rot(lg, lambda t: (0, 0, -5 * sh(t)))
    c.rot('blep', lambda t: (18 * sh(t), 0, 0))
    eyes(c, base=lambda t: 0.45 * pulse(t, 0.05, 1.55))
    put(c)

    # ------------------------------------------------------------------ reactions
    c = Clip(m, 'hurt', 0.4)
    c.rot('h_head', lambda t: (14 * pulse(t, 0, 0.4), 0, 0))
    c.scl('body', lambda t: (1 - 0.06 * pulse(t, 0, 0.3), 1 + 0.08 * pulse(t, 0, 0.3), 1 - 0.06 * pulse(t, 0, 0.3)))
    eyes(c, base=lambda t: -1.0 * pulse(t, 0, 0.4))
    c.rot('blep', lambda t: (-30 * pulse(t, 0, 0.4), 0, 0))
    put(c)

    # death: flops over on his side, legs stick out stiff, one twitch, tongue all the way out
    L = 1.4
    c = Clip(m, 'death', L, 'hold')
    fl = lambda t: step(t, 0.1, 0.6)
    c.rot('root', lambda t: (0, 0, 90 * fl(t)))
    c.pos('root', lambda t: (1.0 * fl(t), 6.5 * fl(t), 0))
    legs_pose(c, lambda lg, t: ((30 if 'front' in lg else -30) * step(t, 0.5, 0.8) +
                                (8 * decay(t, 0.9, 4, 5) if 'left' in lg else 0), 0,
                                (25 if 'left' in lg else -25) * step(t, 0.5, 0.8)))
    c.scl('blep', lambda t: (1, 1 + 1.2 * step(t, 0.4, 0.8), 1))
    c.rot('jaw', lambda t: (-8 * step(t, 0.3, 0.7), 0, 0))
    eyes(c, base=lambda t: step(t, 0.3, 0.6))
    put(c)

    # eats: tongue zips out straight ahead and back, gulp (eyes squeeze down, throat bulges)
    L = 1.3
    c = Clip(m, 'eat', L)
    c.rot('jaw', lambda t: (-18 * pulse(t, 0.05, 0.6), 0, 0))
    tongue(c, lambda t: pulse(t, 0.1, 0.5) ** 0.5, hide_blep=lambda t: pulse(t, 0.05, 0.7))
    eyes(c, blinks=[0.65], sink=1.3, slow=1.5)
    c.scl('throat', lambda t: (1 + 0.3 * pulse(t, 0.6, 1.0), 1 + 1.8 * pulse(t, 0.6, 1.0), 1))
    c.rot('h_head', lambda t: (-8 * pulse(t, 0.0, 0.5) + 6 * pulse(t, 0.6, 1.1), 0, 0))
    put(c)

    # happy: two stumpy bounces on all fours, chin up, blep flapping
    L = 1.6
    c = Clip(m, 'happy', L)
    hop = lambda t: pulse(t, 0.15, 0.55) + pulse(t, 0.8, 1.2)
    land = lambda t: pulse(t, 0.5, 0.72) + pulse(t, 1.15, 1.37)
    c.pos('body', lambda t: (0, 2.4 * hop(t) - 0.8 * land(t), 0))
    c.scl('body', lambda t: (1 + 0.1 * land(t), 1 - 0.1 * land(t) + 0.06 * hop(t), 1))
    c.rot('h_head', lambda t: (14 * pulse(t, 0.1, 1.45), 0, 0))
    legs_pose(c, lambda lg, t: ((25 if 'front' in lg else -30) * hop(t), 0, 0))
    c.rot('blep', lambda t: (-35 * hop(t) + 20 * land(t), 0, 0))
    eyes(c, base=lambda t: -0.6 * hop(t) + 0.3 * land(t))
    put(c)

    L = 2.4
    c = Clip(m, 'tame', L)
    c.rot('h_head', lambda t: (-8 * pulse(t, 0.1, 0.6) + 16 * pulse(t, 0.7, 2.0), 0, 10 * wave(t, 0.8) * pulse(t, 0.7, 2.0)))
    c.pos('body', lambda t: (0, 2.2 * pulse(t, 0.8, 1.2), 0))
    legs_pose(c, lambda lg, t: ((25 if 'front' in lg else -30) * pulse(t, 0.8, 1.2), 0, 0))
    croak(c, [(1.4, 2.0)])
    eyes(c, blinks=[0.3, 2.05], slow=1.5)
    put(c)

    # greet: chin WAY up, a slow acknowledging blink and a single croak. That is all you get.
    L = 1.8
    c = Clip(m, 'greet', L)
    c.rot('h_head', lambda t: (18 * pulse(t, 0.05, 1.75), 0, 6 * pulse(t, 0.2, 1.6)))
    c.rot('body', lambda t: (4 * pulse(t, 0.05, 1.75), 0, 0))
    croak(c, [(0.55, 0.95)], 1.5)
    eyes(c, base=lambda t: 0.4 * pulse(t, 0.05, 1.75), blinks=[1.0], slow=2.0)
    put(c)

    L = 1.0
    c = Clip(m, 'notice', L)
    c.rot('h_head', lambda t: (14 * pulse(t, 0, L), 0, 0))
    c.scl('body', lambda t: (1, 1 + 0.05 * pulse(t, 0, L), 1))
    eyes(c, base=lambda t: -1.0 * pulse(t, 0, L))
    put(c)

    L = 3.0
    c = Clip(m, 'look_around', L)
    c.rot('h_head', lambda t: (5 * pulse(t, 0, L), keys(t, [(0, 0), (0.4, 0), (0.55, 34), (1.3, 34), (1.45, -30),
                                                            (2.4, -30), (2.8, 0), (3.0, 0)], 'out'), 0))
    eyes(c, base=lambda t: 0.25 * pulse(t, 0.4, 2.6), blinks=[1.35])
    put(c)

    L = 1.1
    c = Clip(m, 'spawn', L)
    c.scl('body', lambda t: (lambda k: (k, k, k))(0.6 + 0.4 * step(t, 0, 0.45) + 0.06 * pulse(t, 0.35, 0.6)))
    c.rot('body', lambda t: (0, 0, 8 * wave(t, 0.2) * pulse(t, 0.5, 1.05)))
    eyes(c, blinks=[0.7])
    put(c)

    # petted: tolerates it. Leans in very slightly, eyes shut in smug bliss, a soft croak, then pretends it never happened
    L = 2.2
    c = Clip(m, 'pet', L)
    lean = lambda t: pulse(t, 0.1, 1.8)
    c.rot('h_head', lambda t: (10 * lean(t), 0, 12 * lean(t)))
    c.rot('body', lambda t: (0, 0, 4 * lean(t)))
    eyes(c, base=lambda t: 1.0 * pulse(t, 0.2, 1.8), sink=0.9)
    croak(c, [(0.8, 1.4)], 1.4)
    c.rot('blep', lambda t: (10 * wave(t, 0.5) * lean(t), 0, 0))
    put(c)

    L = 4.0
    c = Clip(m, 'idle_sleepy', L, 'loop')
    c.rot('h_head', lambda t: (-4 + 3 * wave(t, L), 0, 3 * wave(t, L, 1, 0.3)))
    c.rot('body', lambda t: (0, 0, 1.5 * wave(t, L)))
    c.pos('body', lambda t: (0, -0.4, 0))
    c.scl('blep', lambda t: (1, 1.4, 1))
    eyes(c, base=lambda t: 0.6 + 0.15 * wave(t, L) + 0.3 * pulse(t, 2.4, 3.2), sink=0.4)
    put(c)

    L = 2.0
    c = Clip(m, 'idle_happy', L, 'loop')
    c.rot('body', lambda t: (0, 3 * wave(t, L), 4 * wave(t, L, 1, 0.25)))
    c.pos('body', lambda t: (0, 0.4 * abs(wave(t, L)), 0))
    c.rot('h_head', lambda t: (10, -4 * wave(t, L), -3 * wave(t, L, 1, 0.3)))
    blep_wiggle(c, L, 14, L / 2)
    eyes(c, base=lambda t: 0.35, blinks=[0.6])
    put(c)

    # begging for the treat in your hand: stares at it intently (astute), bounces his front stumps, little croaks
    L = 1.6
    c = Clip(m, 'idle_beg', L, 'loop')
    c.rot('h_head', lambda t: (22 + 3 * wave(t, L, 2), 4 * wave(t, L), 0))
    c.rot('body', lambda t: (5 + 2 * wave(t, L, 2), 0, 0))
    c.rot(LF, lambda t: (-5, 0, 0))
    c.rot(RF, lambda t: (-5, 0, 0))
    c.pos(LF, lambda t: (0, 0.8 * max(0.0, wave(t, L, 2)), 0))
    c.pos(RF, lambda t: (0, 0.8 * max(0.0, -wave(t, L, 2)), 0))
    croak(c, [(0.2, 0.6), (1.0, 1.4)], 1.4)
    blep_wiggle(c, L, 16, L / 2)
    eyes(c, base=lambda t: -0.4, blinks=[0.8])
    put(c)

    L = 1.2
    c = Clip(m, 'shake', L)
    c.rot('body', lambda t: (0, 8 * wave(t, 0.16) * pulse(t, 0.05, 1.0), 10 * wave(t, 0.16, 1, 0.25) * pulse(t, 0.05, 1.0)))
    c.rot('h_head', lambda t: (0, -16 * wave(t, 0.16, 1, 0.1) * pulse(t, 0.0, 0.9), 0))
    c.rot('blep', lambda t: (30 * wave(t, 0.16, 1, 0.3) * pulse(t, 0.05, 1.0), 0, 0))
    eyes(c, base=lambda t: 0.8 * pulse(t, 0.05, 1.0))
    c.particle(0.3, 'minecraft:splash', 10, 0.5, 0.1, 0.6)
    put(c)

    # ability (hop buddy): a big happy hop and croak beside his owner
    L = 2.0
    c = Clip(m, 'ability', L)
    c.copy('happy')
    croak(c, [(1.3, 1.9)], 1.9)
    c.sound(1.35, 'ambient')
    put(c)

    return out + posture_set(m)


TAGS = {
    'idle_look': {'p': 'standing'}, 'idle_beg': {'p': 'standing'}, 'idle_happy': {'p': 'standing'},
    'idle_sleepy': {'p': 'standing'}, 'sit_look': {'p': 'sitting'}, 'sleep': {'p': 'lying'},
    'frog_blink': {'p': 'standing', 'layer': 'base'}, 'eye_lick': {'p': 'standing'}, 'croak': {'p': 'standing'},
    'aloof': {'p': 'standing'}, 'derp_tilt': {'p': 'standing'}, 'fly_snap': {'p': 'standing'},
    'splat': {'p': 'standing', 'layer': 'base', 'move': False},
    'wiggle': {'p': 'standing'}, 'happy': {'p': 'standing'}, 'tame': {'p': 'standing'},
    'greet': {'p': 'standing'}, 'eat': {'p': 'standing'}, 'ability': {'p': 'standing'}, 'shake': {'p': 'standing'},
}
IDLE = {
    'standing': [
        {'clip': 'idle_look', 'w': 3, 'min': 5, 'max': 10},
        {'clip': 'frog_blink', 'w': 3, 'cd': 8},
        {'clip': 'aloof', 'w': 3, 'cd': 12, 'boost': {'owner_near': 2}},
        {'clip': 'derp_tilt', 'w': 2.5, 'cd': 14},
        {'clip': 'fly_snap', 'w': 2.5, 'cd': 14, 'when': '!in_water', 'boost': {'night': 1.5}},
        {'clip': 'eye_lick', 'w': 2.5, 'cd': 14, 'when': '!in_water'},
        {'clip': 'croak', 'w': 2, 'cd': 14, 'boost': {'rain': 2, 'night': 1.5}},
        {'clip': 'splat', 'w': 1.5, 'cd': 24, 'when': '!in_water'},
        {'clip': 'wiggle', 'w': 1.5, 'cd': 16, 'when': '!in_water', 'boost': {'owner_near': 2}},
        {'clip': 'look_around', 'w': 1.5, 'cd': 12},
    ],
    'sitting': [
        {'clip': 'sit_look', 'w': 2, 'cd': 14},
        {'clip': 'frog_blink', 'w': 2, 'cd': 10},
        {'clip': 'croak', 'w': 1, 'cd': 18},
    ],
}
PROCEDURAL = {'breath': 0.015}
