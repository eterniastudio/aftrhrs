"""Pondwaddle (+ ducklings): a pond duck. Fussy and busy: preens its back feathers, does the one-sided wing-and-leg
stretch, wags its tail, quacks to the flock, cranes its neck up tall when someone comes close. On the water it dabbles
(tail straight up, head under), preens and rises to flap. Sleeps with its bill tucked into its back."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Quad, env, decay, keys, loop, once, pulse, step, transition, wave  # noqa: E402
from animkit import Clip  # noqa: E402

TAGS = {'lie_preen': {'p': 'lying'}, 'sleep': {'p': 'lying'}, 'swim_dabble': {'p': 'swimming'},
        'swim_preen': {'p': 'swimming'}, 'swim_flap': {'p': 'swimming'}, 'swim_wag': {'p': 'swimming'}}
PROCEDURAL = {'tail_sway': 6, 'breath': 0.016}


def preen(m, name='preen', L=2.6, src='idle'):
    """Head swings round onto its back and nibbles through the feathers, wings fluffed a little."""
    c = once(m, name, L, src)
    e = lambda t: env(t, 0.05, L - 0.1, 0.45, 0.5)
    nib = lambda t: 6 * wave(t, 0.3) * env(t, 0.6, L - 0.6, 0.1, 0.1)
    c.rot('h_head', lambda t: (-28 * e(t) + nib(t), 120 * e(t), 18 * e(t)), add=True)
    c.pos('h_head', lambda t: (0, -0.6 * e(t), 0.8 * e(t)), add=True)
    c.rot('right_wing', lambda t: (0, 0, 10 * e(t)), add=True)
    c.rot('left_wing', lambda t: (0, 0, -10 * e(t)), add=True)
    c.rot('body', lambda t: (0, 6 * e(t), -3 * e(t)), add=True)
    c.rot('tail', lambda t: (0, 10 * decay(t, L - 0.5, 4, 5), 0), add=True)
    for t0 in (0.8, 1.6):
        c.sound(t0, 'minecraft:entity.parrot.fly', 0.12, 1.6)
    return c


def wing_stretch(m, L=2.4):
    """One wing swept back and out with the same-side leg stretched behind; then a full flap and a settle."""
    c = once(m, 'wing_stretch', L)
    e = lambda t: env(t, 0.05, 1.5, 0.4, 0.35)
    flap = lambda t: pulse(t, 1.55, 1.85) + 0.8 * pulse(t, 1.85, 2.15)
    c.rot('right_wing', lambda t: (-20 * e(t), -25 * e(t), 70 * e(t) + 80 * flap(t)), add=True)
    c.rot('left_wing', lambda t: (0, 0, -80 * flap(t)), add=True)
    c.rot('right_leg', lambda t: (-50 * e(t), 0, 10 * e(t)), add=True)
    c.pos('right_leg', lambda t: (0, 0.8 * e(t), 0.6 * e(t)), add=True)
    c.rot('body', lambda t: (4 * e(t) + 6 * flap(t), 0, 6 * e(t)), add=True)
    c.pos('body', lambda t: (0.5 * e(t), 0.6 * flap(t), 0), add=True)
    c.rot('h_head', lambda t: (8 * e(t), -10 * e(t), -6 * e(t)), add=True)
    c.rot('tail', lambda t: (-10 * e(t), 0, 0), add=True)
    c.sound(1.6, 'minecraft:entity.parrot.fly', 0.4, 0.9)
    return c


def tail_wag(m, L=0.9, src='idle', name='tail_wag'):
    c = once(m, name, L, src)
    c.rot('tail', lambda t: (-6 * pulse(t, 0, L), 28 * decay(t, 0.05, 4.5, 4.0), 0), add=True)
    c.rot('body', lambda t: (0, 3 * decay(t, 0.05, 4.5, 4.5), 0), add=True)
    return c


def quack(m, L=0.9, name='quack'):
    """Head only (overlay): a bobbing double quack."""
    c = Clip(m, name, L, 'once')
    c.rot('h_head', lambda t: (10 * pulse(t, 0.0, 0.3) - 8 * pulse(t, 0.25, 0.45) + 9 * pulse(t, 0.4, 0.65) - 6 * pulse(t, 0.6, 0.8), 0, 0))
    c.pos('h_head', lambda t: (0, 0, -0.5 * pulse(t, 0.2, 0.7)))
    c.sound(0.25, 'ambient', 0.7, 1.0)
    return c


def crane(m, L=2.4):
    """Notice: neck stretched up tall, head tilted to eye the newcomer, wings pressed in, then it relaxes."""
    c = once(m, 'crane', L)
    e = lambda t: env(t, 0.0, L, 0.25, 0.6)
    c.pos('h_head', lambda t: (0, 1.4 * e(t), 0.3 * e(t)), add=True)
    c.rot('h_head', lambda t: (8 * e(t), 0, 14 * pulse(t, 0.6, 1.6)), add=True)
    c.scl('h_head', lambda t: (1, 1 + 0.1 * e(t), 1), add=True)
    c.rot('body', lambda t: (8 * e(t), 0, 0), add=True)
    c.pos('body', lambda t: (0, 0.4 * e(t), 0), add=True)
    c.rot('tail', lambda t: (-8 * e(t), 0, 0), add=True)
    return c


def graze_peck(m, L=1.2):
    c = once(m, 'graze_peck', L, 'graze')
    c.rot('h_head', lambda t: (-12 * (pulse(t, 0.05, 0.3) + pulse(t, 0.4, 0.65) + pulse(t, 0.75, 1.0)), 6 * wave(t, L, 1), 0), add=True)
    c.rot('tail', lambda t: (0, 14 * wave(t, 0.4) * pulse(t, 0, L), 0), add=True)
    c.sound(0.3, 'minecraft:block.grass.hit', 0.2, 1.5)
    c.sound(0.95, 'minecraft:block.grass.hit', 0.2, 1.6)
    return c


def lie_preen(m, L=6.0):
    c = loop(m, 'lie_preen', L, 'lie')
    turn = lambda t: 0.5 - 0.5 * wave(t, L, 1, 0.25)
    c.rot('h_head', lambda t: (-20 * turn(t) + 4 * wave(t, 0.4) * turn(t), 100 * turn(t), 14 * turn(t)), add=True)
    c.rot('right_wing', lambda t: (0, 0, 6 * turn(t)), add=True)
    return c


def sleep(m, L=6.0):
    """Bill tucked into the back feathers, eyes shut, slow breathing (sleep-type clip)."""
    c = loop(m, 'sleep', L, 'lie')
    c.rot('h_head', lambda t: (-38, 150, 20), add=True)
    c.pos('h_head', lambda t: (0, -2.4, 1.6), add=True)
    c.scl('body', lambda t: (1 + 0.015 * wave(t, L, 1), 1 + 0.03 * wave(t, L, 1), 1))
    c.scl('eyes', lambda t: (1, 0.12, 1))
    c.rot('tail', lambda t: (0, 0, 0))
    return c


def swim_dabble(m, L=2.8):
    """Upends: tail straight up, head under, feet paddling in the air, then bobs back up with a shake."""
    c = once(m, 'swim_dabble', L, 'swim')
    tip = lambda t: keys(t, [(0, 0), (0.45, 1), (1.9, 1), (2.3, 0), (L, 0)])
    c.rot('body', lambda t: (-68 * tip(t), 0, 4 * decay(t, 2.3, 4, 4)), add=True)
    c.pos('body', lambda t: (0, -1.2 * tip(t), -1.0 * tip(t)), add=True)
    c.rot('h_head', lambda t: (-30 * tip(t), 0, 18 * decay(t, 2.3, 5, 4)), add=True)
    for leg, ph in (('right_leg', 0.0), ('left_leg', 0.5)):
        c.rot(leg, (lambda ph: lambda t: (40 * wave(t, 0.4, 1, ph) * tip(t) - 30 * tip(t), 0, 0))(ph), add=True)
    c.rot('tail', lambda t: (-20 * tip(t), 20 * wave(t, 0.5) * tip(t), 0), add=True)
    c.sound(0.45, 'splash', 0.6, 1.2)
    c.sound(2.3, 'splash', 0.5, 1.3)
    return c


def swim_flap(m, L=1.8):
    """Rises up out of the water on its tail and flaps hard, then settles back."""
    c = once(m, 'swim_flap', L, 'swim')
    up = lambda t: env(t, 0.0, L, 0.3, 0.45)
    flap = lambda t: wave(t, 0.28) * env(t, 0.25, 1.35, 0.1, 0.15)
    c.rot('body', lambda t: (34 * up(t), 0, 0), add=True)
    c.pos('body', lambda t: (0, 1.4 * up(t), 0), add=True)
    c.rot('right_wing', lambda t: (0, 0, (70 + 40 * flap(t)) * up(t)), add=True)
    c.rot('left_wing', lambda t: (0, 0, -(70 + 40 * flap(t)) * up(t)), add=True)
    c.rot('h_head', lambda t: (-26 * up(t), 0, 0), add=True)
    c.rot('tail', lambda t: (16 * up(t), 0, 0), add=True)
    for t0 in (0.35, 0.65, 0.95):
        c.sound(t0, 'minecraft:entity.parrot.fly', 0.4, 0.85)
    c.sound(1.4, 'splash', 0.5, 1.1)
    return c


def hop(m, L=0.8):
    """Duckling: a tiny flappy hop."""
    c = once(m, 'hop', L)
    y = lambda t: keys(t, [(0, 0), (0.12, -0.6), (0.35, 2.6), (0.58, 0), (0.66, -0.4), (0.8, 0)])
    c.pos('body', lambda t: (0, y(t), 0), add=True)
    c.rot('right_wing', lambda t: (0, 0, 40 * pulse(t, 0.15, 0.55)), add=True)
    c.rot('left_wing', lambda t: (0, 0, -40 * pulse(t, 0.15, 0.55)), add=True)
    c.rot('h_head', lambda t: (10 * pulse(t, 0.1, 0.5), 0, 0), add=True)
    for leg in ('right_leg', 'left_leg'):
        c.rot(leg, lambda t: (20 * pulse(t, 0.2, 0.55), 0, 0), add=True)
    c.sound(0.58, 'step', 0.6, 1.4)
    return c


def clips(m):
    q = Quad(m, size=1.0)
    lie_down = transition(m, 'lie_down', 'idle', 0, 'lie', 0, 0.9, 0.1)
    lie_down.sound(0.75, 'minecraft:entity.parrot.fly', 0.15, 1.3)
    stand_up = transition(m, 'stand_up', 'lie', 0, 'idle', 0, 0.8, 0.05)
    stand_up.rot('right_wing', lambda t: (0, 0, 22 * pulse(t, 0.35, 0.75)), add=True)
    stand_up.rot('left_wing', lambda t: (0, 0, -22 * pulse(t, 0.35, 0.75)), add=True)
    return [q.look_around(L=3.0), q.herd_glance(), preen(m), wing_stretch(m), tail_wag(m), quack(m), crane(m), hop(m),
            graze_peck(m), q.graze_look(), lie_down, stand_up, lie_preen(m), sleep(m),
            swim_dabble(m), preen(m, 'swim_preen', 2.4, 'swim'), swim_flap(m), tail_wag(m, 0.9, 'swim', 'swim_wag'), hurt(m, extra=('tail',))]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 4, 'min': 4, 'max': 10},
        {'clip': 'look_around', 'w': 2, 'cd': 12},
        {'clip': 'preen', 'w': 3, 'cd': 10},
        {'clip': 'wing_stretch', 'w': 1.5, 'cd': 25},
        {'clip': 'tail_wag', 'w': 2.5, 'cd': 6},
        {'clip': 'quack', 'w': 1.5, 'cd': 10},
        {'clip': 'herd_glance', 'w': 2, 'cd': 12, 'when': 'herd_near:10'},
        {'clip': 'hop', 'w': 3, 'cd': 5, 'when': 'young'},
    ],
    'graze': [
        {'clip': '@mood', 'w': 4, 'min': 2, 'max': 5},
        {'clip': 'graze_peck', 'w': 3, 'cd': 4},
        {'clip': 'graze_look', 'w': 2, 'cd': 8},
        {'clip': 'quack', 'w': 0.6, 'cd': 10},
    ],
    'notice': [
        {'clip': 'crane', 'w': 3},
        {'clip': 'quack', 'w': 1},
    ],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 25},
        {'clip': 'lie_preen', 'w': 2, 'min': 6, 'max': 12},
        {'clip': 'sleep', 'w': 0.6, 'min': 20, 'max': 60, 'boost': {'night': 10}},
        {'clip': 'quack', 'w': 0.5, 'cd': 20, 'when': '!night'},
    ],
    'swim': [
        {'clip': '@mood', 'w': 3, 'min': 3, 'max': 8},
        {'clip': 'swim_dabble', 'w': 3, 'cd': 8},
        {'clip': 'swim_preen', 'w': 2, 'cd': 10},
        {'clip': 'swim_flap', 'w': 1, 'cd': 20},
        {'clip': 'swim_wag', 'w': 2, 'cd': 6},
        {'clip': 'quack', 'w': 1.5, 'cd': 10},
    ],
}
