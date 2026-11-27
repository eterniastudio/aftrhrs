"""Gloomweaver: a dark-forest spider with a mushroom on its back and a silk bundle. It moves like the vanilla spider
it replaces (skittering eight-legged gait, quick and low), and between hunts it grooms its fangs with its front legs,
drums the ground listening for prey, crouches low and still, and rears up with its forelegs raised when someone
comes near. `spin` (re-spinning the silk bundle) plays when the bundle grows back; `snipped` when shears take it.

Rig (Blockbench convention): hip_<s><k> yaws a leg forward / back (y), leg_<s><k> lifts it (z: + raises the +x side,
so the right legs lift with z+, the left with z-), knee_<s><k> bends the tibia (z, the opposite sign straightens it).
k = 1..4 front to back."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, env, keys, once, loop, pulse, decay, wave  # noqa: E402
from animkit import Clip  # noqa: E402

SIDES = (('l', -1), ('r', 1))
LEGS = [(sn, s, k) for sn, s in SIDES for k in range(1, 5)]

TAGS = {
    'groom': {'layer': 'base', 'move': False},
    'tap': {'layer': 'base', 'move': False},
    'rear': {'layer': 'base', 'move': False},
    'spin': {'layer': 'base', 'move': False},
    'crouch': {'p': 'standing', 'move': False},
    'skitter': {'p': 'standing', 'move': True, 'gait': 'walk', 'layer': 'base'},
    'run': {'p': 'standing', 'move': True, 'gait': 'run', 'layer': 'base'},
}


def group_a(sn, k):
    return (sn == 'l') == (k % 2 == 1)


def gait(c, L, swing, lift, bob, pause=0.0):
    """The alternating-tetrapod spider gait (same as the model's walk) at another speed; pause = share of each half
    cycle the legs hold still (a skitter: dash, freeze, dash)."""
    def phase(t):
        u = (t / L) % 1.0
        if pause <= 0:
            return u
        # dash through each half in (0.5 - pause) of the time, then hold
        h = u % 0.5
        k = min(1.0, h / (0.5 - pause))
        return (u - h) + 0.5 * k

    for sn, s, k in LEGS:
        off = 0.0 if group_a(sn, k) else 0.5

        def yaw_up(t, off=off):
            p = (phase(t) + off) % 1.0
            if p < 0.5:
                q = p / 0.5
                return -swing + 2 * swing * (0.5 - 0.5 * math.cos(math.pi * q)), lift * math.sin(math.pi * q)
            q = (p - 0.5) / 0.5
            return swing - 2 * swing * q, 0.0
        c.rot(f'hip_{sn}{k}', (lambda s, f: lambda t: (0, s * f(t)[0], 0))(s, yaw_up))
        c.rot(f'leg_{sn}{k}', (lambda s, f: lambda t: (0, 0, s * f(t)[1]))(s, yaw_up))
        c.rot(f'knee_{sn}{k}', (lambda s, f: lambda t: (0, 0, -s * 0.35 * f(t)[1]))(s, yaw_up))
    c.pos('body', lambda t: (0, bob * (0.5 - 0.5 * math.cos(4 * math.pi * phase(t))) - bob, 0))
    c.rot('body', lambda t: (0, 1.5 * math.sin(2 * math.pi * phase(t)), 1.2 * math.sin(2 * math.pi * phase(t))))
    c.rot('abdomen', lambda t: (1.0 * math.sin(4 * math.pi * phase(t)), -2.5 * math.sin(2 * math.pi * phase(t)), 0))
    return c


def run(m):
    c = Clip(m, 'run', 0.45, 'loop')
    gait(c, 0.45, swing=16, lift=20, bob=0.5)
    c.rot('h_head', lambda t: (-4, 0, 0))
    c.rot('body', lambda t: (-3, 0, 0), add=True)
    return c


def skitter(m):
    """Walk variant: quick dashes with a freeze between them, the way spiders stop and start."""
    c = Clip(m, 'skitter', 1.4, 'loop')
    gait(c, 1.4, swing=14, lift=17, bob=0.4, pause=0.22)
    return c


def front_legs(c, fn, knee=None):
    """fn(t) -> (forward yaw, lift) for both front legs (mirrored); knee(t) bends the tibia in (+) toward the face."""
    for sn, s in SIDES:
        c.rot(f'hip_{sn}1', (lambda s: lambda t: (0, s * fn(t)[0], 0))(s), add=True)
        c.rot(f'leg_{sn}1', (lambda s: lambda t: (0, 0, s * fn(t)[1]))(s), add=True)
        if knee:
            c.rot(f'knee_{sn}1', (lambda s: lambda t: (0, 0, s * knee(t)))(s), add=True)


def groom(m, L=2.6):
    """Front legs come up and in to the fangs and rub (three strokes), head bowed into them."""
    c = once(m, 'groom', L)
    e = lambda t: env(t, 0.0, L, 0.4, 0.5)
    rub = lambda t: e(t) * (0.5 + 0.5 * math.sin(2 * math.pi * 2.2 * t))
    front_legs(c, lambda t: (22 * e(t) - 6 * rub(t), 20 * e(t) + 5 * rub(t)), knee=lambda t: -(46 * e(t) + 10 * rub(t)))
    c.rot('h_head', lambda t: (-10 * e(t), 0, 3 * wave(t, L, 3) * e(t)), add=True)
    c.rot('fangs', lambda t: (-8 * rub(t), 0, 0), add=True)
    c.rot('body', lambda t: (4 * e(t), 0, 0), add=True)
    return c


def tap(m, L=2.2):
    """Drums the ground with alternate front legs (listening for prey), head low, then goes still."""
    c = once(m, 'tap', L)
    e = lambda t: env(t, 0.0, L, 0.3, 0.4)
    taps = [(0.4, 'l'), (0.6, 'r'), (0.8, 'l'), (1.0, 'r'), (1.35, 'l'), (1.5, 'r')]
    for sn, s in SIDES:
        up = (lambda sn: lambda t: sum(pulse(t, t0 - 0.08, t0 + 0.06) for t0, side in taps if side == sn))(sn)
        c.rot(f'leg_{sn}1', (lambda s, up: lambda t: (0, 0, s * (10 * up(t) + 4 * e(t))))(s, up), add=True)
        c.rot(f'hip_{sn}1', (lambda s: lambda t: (0, s * 10 * e(t), 0))(s), add=True)
    c.rot('body', lambda t: (-3 * e(t), 0, 0), add=True)
    c.pos('body', lambda t: (0, -0.6 * e(t), 0), add=True)
    c.rot('h_head', lambda t: (-6 * e(t), 4 * wave(t, L, 1) * e(t), 0), add=True)
    for t0, _ in taps:
        c.sound(t0, 'minecraft:entity.spider.step', 0.25, 1.4)
    return c


def rear(m, L=1.8):
    """Notice: rears up on the back legs, forelegs raised and spread, fangs open: a warning, then settles."""
    c = once(m, 'rear', L)
    e = lambda t: keys(t, [(0, 0), (0.3, 1), (1.2, 1), (L, 0)])
    sh = lambda t: e(t) * 2 * wave(t, L, 6)
    c.rot('body', lambda t: (16 * e(t), 0, 0), add=True)
    c.pos('body', lambda t: (0, 1.4 * e(t), 0.8 * e(t)), add=True)
    c.rot('abdomen', lambda t: (-12 * e(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (4 * e(t) + sh(t), 0, 0), add=True)
    c.rot('fangs', lambda t: (-16 * e(t), 0, 0), add=True)
    front_legs(c, lambda t: (16 * e(t), 46 * e(t) + sh(t) * 2), knee=lambda t: -24 * e(t))
    for sn, s in SIDES:
        c.rot(f'leg_{sn}2', (lambda s: lambda t: (0, 0, s * 18 * e(t)))(s), add=True)
        c.rot(f'leg_{sn}4', (lambda s: lambda t: (0, 0, -s * 8 * e(t)))(s), add=True)
    c.sound(0.3, 'minecraft:entity.spider.ambient', 0.6, 0.75)
    return c


def crouch(m, L=6.0):
    """Loop: lurking low and still, legs flexed, only the fangs and the abdomen breathing."""
    c = loop(m, 'crouch', L)
    c.pos('body', lambda t: (0, -1.6, 0))
    for sn, s, k in LEGS:
        c.rot(f'leg_{sn}{k}', (lambda s: lambda t: (0, 0, s * 9))(s), add=True)
        c.rot(f'knee_{sn}{k}', (lambda s: lambda t: (0, 0, -s * 4))(s), add=True)
    c.rot('h_head', lambda t: (-4, 3 * wave(t, L, 1), 0), add=True)
    c.rot('fangs', lambda t: (3 * max(0.0, wave(t, L, 3)), 0, 0), add=True)
    c.scl('abdomen', lambda t: (1 + 0.02 * (0.5 + 0.5 * wave(t, L, 2)),) * 3)
    return c


def spin(m, L=2.4):
    """Re-spins its silk bundle: the abdomen arches up and works, the back legs comb the silk forward."""
    c = once(m, 'spin', L)
    e = lambda t: env(t, 0.0, L, 0.35, 0.5)
    work = lambda t: e(t) * math.sin(2 * math.pi * 3 * t)
    c.rot('abdomen', lambda t: (10 * e(t) + 3 * work(t), 0, 2 * work(t)), add=True)
    for sn, s in SIDES:
        c.rot(f'hip_{sn}4', (lambda s: lambda t: (0, -s * (18 * e(t) + 8 * work(t)), 0))(s), add=True)
        c.rot(f'leg_{sn}4', (lambda s: lambda t: (0, 0, s * 26 * e(t)))(s), add=True)
    c.scl('silk', lambda t: (1, 1, 1))
    c.sound(0.5, 'minecraft:block.wool.place', 0.4, 1.3)
    c.sound(1.3, 'minecraft:block.wool.place', 0.4, 1.4)
    return c


def snipped(m, L=0.7):
    """Shears take the bundle: a startled hop of the abdomen and a twitch of every leg (overlay)."""
    c = Clip(m, 'snipped', L, 'once')
    j = lambda t: decay(t, 0.0, 3.0, 6.0)
    c.rot('abdomen', lambda t: (-10 * j(t), 0, 4 * j(t)))
    c.rot('body', lambda t: (3 * j(t), 0, 0))
    return c


def clips(m):
    return [run(m), skitter(m), groom(m), tap(m), rear(m), crouch(m), spin(m), snipped(m), hurt(m, amt=0.7)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'crouch', 'w': 2.5, 'min': 6, 'max': 14},
        {'clip': 'groom', 'w': 2, 'cd': 10},
        {'clip': 'tap', 'w': 2, 'cd': 10, 'boost': {'night': 2}},
        {'clip': 'spin', 'w': 0.8, 'cd': 30},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'skitter', 'w': 1.5, 'min': 5, 'max': 12},
    ],
    'notice': [{'clip': 'rear', 'w': 1}],
}
