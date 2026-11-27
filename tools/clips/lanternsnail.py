"""Lanternsnail (+ young): a glowing cave snail. Everything happens in its eye stalks: they wander independently,
stretch out to peek, snap back into the head when someone comes close (then creep back out one at a time). It stretches
its soft body out long, rocks its shell, and sleeps with its stalks drawn in."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Quad, decay, env, keys, loop, once, pulse, transition, wave  # noqa: E402

TAGS = {'sleep': {'p': 'lying'}}
PROCEDURAL = {'breath': 0.02, 'look_yaw': 40}
STALKS = ('left_stalk', 'right_stalk')


def stalk_wave(m, L=6.0):
    c = loop(m, 'stalk_wave', L)
    c.rot('left_stalk', lambda t: (10 * wave(t, L, 2), 8 * wave(t, L, 1, 0.2), 12 * wave(t, L, 3, 0.1)), add=True)
    c.rot('right_stalk', lambda t: (10 * wave(t, L, 3, 0.4), -8 * wave(t, L, 1, 0.6), -12 * wave(t, L, 2, 0.3)), add=True)
    c.rot('h_head', lambda t: (2 * wave(t, L, 1), 6 * wave(t, L, 1, 0.3), 0), add=True)
    return c


def stalk_peek(m, L=2.4):
    c = once(m, 'stalk_peek', L)
    e = lambda t: env(t, 0.0, L, 0.4, 0.5)
    for s, sg in (('left_stalk', 1), ('right_stalk', -1)):
        c.scl(s, lambda t: (1, 1 + 0.4 * e(t), 1), add=True)
        c.rot(s, (lambda sg: lambda t: (-18 * e(t), sg * 10 * wave(t, 1.2) * e(t), 0))(sg), add=True)
    c.rot('h_head', lambda t: (8 * e(t), 0, 0), add=True)
    c.pos('h_head', lambda t: (0, 0.3 * e(t), -0.4 * e(t)), add=True)
    return c


def stretch(m, L=3.4):
    c = once(m, 'stretch', L)
    e = lambda t: env(t, 0.0, L, 1.0, 0.9)
    c.pos('h_head', lambda t: (0, 0.2 * e(t), -1.6 * e(t)), add=True)
    c.rot('h_head', lambda t: (10 * e(t), 0, 0), add=True)
    c.scl('body', lambda t: (1 - 0.05 * e(t), 1 - 0.05 * e(t), 1 + 0.16 * e(t)), add=True)
    c.pos('tail', lambda t: (0, 0, 0.8 * e(t)), add=True)
    for s in STALKS:
        c.rot(s, lambda t: (-14 * e(t), 0, 0), add=True)
    return c


def shell_wiggle(m, L=1.6):
    c = once(m, 'shell_wiggle', L)
    c.rot('shell', lambda t: (2 * pulse(t, 0, 0.6), 0, 10 * decay(t, 0.05, 2.6, 3)), add=True)
    c.rot('body', lambda t: (0, 0, -2 * decay(t, 0.1, 2.6, 3)), add=True)
    c.sound(0.15, 'minecraft:entity.shulker.ambient', 0.15, 1.8)
    return c


def stalk_retract(m, L=3.4):
    """Notice: both stalks snap into the head and the head pulls under the shell; then it peeks out, stalk by stalk."""
    c = once(m, 'stalk_retract', L)
    inn = lambda t: keys(t, [(0, 0), (0.15, 1), (1.6, 1), (2.1, 0.4), (L, 0)])
    right = lambda t: keys(t, [(0, 0), (0.15, 1), (2.0, 1), (2.5, 0), (L, 0)])
    left = lambda t: keys(t, [(0, 0), (0.15, 1), (2.6, 1), (3.1, 0), (L, 0)])
    for s, f in (('left_stalk', left), ('right_stalk', right)):
        c.scl(s, (lambda f: lambda t: (1, 1 - 0.75 * f(t), 1))(f), add=True)
        c.rot(s, (lambda f: lambda t: (-20 * f(t), 0, 0))(f), add=True)
    c.pos('h_head', lambda t: (0, -0.3 * inn(t), 1.4 * inn(t)), add=True)
    c.rot('h_head', lambda t: (-8 * inn(t), 0, 0), add=True)
    c.rot('shell', lambda t: (-3 * inn(t), 0, 0), add=True)
    c.sound(0.1, 'minecraft:entity.shulker.close', 0.3, 1.9)
    return c


def graze_munch(m, L=1.4):
    c = once(m, 'graze_munch', L, 'graze')
    c.rot('h_head', lambda t: (-5 * max(0, wave(t, 0.25)) * env(t, 0, L, 0.1, 0.2), 0, 0), add=True)
    c.sound(0.4, 'minecraft:block.moss.step', 0.2, 1.4)
    return c


def sleep(m, L=6.0):
    c = loop(m, 'sleep', L, 'lie')
    for s in STALKS:
        c.scl(s, lambda t: (1, 0.3, 1))
        c.rot(s, lambda t: (-25, 0, 0), add=True)
    c.pos('h_head', lambda t: (0, -0.2, 1.2), add=True)
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L, 1), 1 + 0.04 * wave(t, L, 1), 1))
    return c


def clips(m):
    q = Quad(m)
    return [q.look_around(L=3.4), stalk_wave(m), stalk_peek(m), stretch(m), shell_wiggle(m), stalk_retract(m),
            graze_munch(m), sleep(m),
            transition(m, 'lie_down', 'idle', 0, 'lie', 0, 1.2, 0.1), transition(m, 'stand_up', 'lie', 0, 'idle', 0, 1.1, 0.05), hurt(m, amt=0.6)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 5, 'max': 12},
        {'clip': 'stalk_wave', 'w': 3, 'min': 6, 'max': 14},
        {'clip': 'look_around', 'w': 1.5, 'cd': 14},
        {'clip': 'stalk_peek', 'w': 2, 'cd': 10},
        {'clip': 'stretch', 'w': 1.5, 'cd': 14},
        {'clip': 'shell_wiggle', 'w': 1.5, 'cd': 12},
    ],
    'graze': [
        {'clip': '@mood', 'w': 4, 'min': 3, 'max': 6},
        {'clip': 'graze_munch', 'w': 3, 'cd': 3},
    ],
    'notice': [{'clip': 'stalk_retract', 'w': 3}],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 30},
        {'clip': 'sleep', 'w': 1, 'min': 20, 'max': 60, 'boost': {'night': 4}},
    ],
}
