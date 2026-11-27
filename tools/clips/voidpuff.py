"""Voidpuff (Creatures 2, the End, wildlife): a little balloon puffer drifting on the End's still air. Everything it
does is slow and buoyant: it bobs on a hover, sculls with its fin-wings, swings its fan tail and breathes into its
glowing throat sac. Hurt, it puffs up round (and drifts up out of reach); bottled, it breathes into the bottle.
Idle antics: a lazy barrel turn, a slow gulping yawn (eyes stay open), a sac breath with a little purple puff, a tail
flick. Eyes close only in the death clip. Clips authored from scratch on the new rig."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _endkit import Clip, Out, R, P, S, bump, const, decay, env, eyes_closed, jolt, keys, osc, pulse, step, wave  # noqa: E402

TAU = 2 * math.pi
FINS = (('left_fin', 'left_fin_tip', 1), ('right_fin', 'right_fin_tip', -1))
PUFF = 'minecraft:entity.puffer_fish.blow_up'
BLOW = 'minecraft:entity.puffer_fish.blow_out'

TAGS = {
    'puff': {'layer': 'overlay', 'move': True}, 'breathe': {'layer': 'overlay', 'move': True},
    'harvest': {'layer': 'base', 'move': False}, 'barrel_turn': {'move': False}, 'gulp': {'move': False},
    'tail_flick': {'layer': 'overlay', 'move': True}, 'notice_puff': {'layer': 'overlay', 'move': True},
}
IDLE = {
    'hover': [{'clip': '@mood', 'w': 4, 'min': 4, 'max': 10}, {'clip': 'barrel_turn', 'w': 1, 'cd': 25},
              {'clip': 'gulp', 'w': 1.5, 'cd': 18}, {'clip': 'breathe', 'w': 2, 'cd': 12}, {'clip': 'tail_flick', 'w': 2, 'cd': 8}],
    'standing': [{'clip': 'barrel_turn', 'w': 1, 'cd': 25}, {'clip': 'gulp', 'w': 1.5, 'cd': 18},
                 {'clip': 'breathe', 'w': 2, 'cd': 12}, {'clip': 'tail_flick', 'w': 2, 'cd': 8}],
    'fly': [{'clip': 'tail_flick', 'w': 1, 'cd': 10}],
    'notice': [{'clip': 'notice_puff', 'w': 1}],
}
PROCEDURAL = {'tail_sway': 6, 'tail': ['tail', 'tail_tip']}


def fins(c, amp, L, cycles=1, base=0.0, tip=1.4, lag=0.06):
    """Fin-wings scull up and down (z), tips following."""
    for fin, tipb, sg in FINS:
        R(c, fin, lambda t, sg=sg: (0, 0, sg * (base + amp * wave(t, L, cycles))))
        R(c, tipb, lambda t, sg=sg: (0, 0, sg * tip * amp * wave(t - lag, L, cycles)))


def inflate(c, fn):
    """Puffs the round body up by fn(t) (0..1): wider, rounder; fins flare out, the crest stands up."""
    S(c, 'body', lambda t: (0.32 * fn(t), 0.3 * fn(t), 0.32 * fn(t)))
    for fin, tipb, sg in FINS:
        R(c, fin, lambda t, sg=sg: (0, 0, sg * 22 * fn(t)))
    R(c, 'crest', lambda t: (14 * fn(t), 0, 0))


def about_center(deg, c=6.5):
    """Root offset that keeps the body's centre (c px up) in place while the root rolls by deg about z."""
    a = math.radians(deg)
    return (c * math.sin(a), 2 * c * math.sin(a / 2) ** 2, 0)


def clips(m):
    out = Out(m)
    c = Clip(m, 'hover', 4.0, 'loop')                # bobs on the air, slow sculls, sac breathing
    P(c, 'body', lambda t: (0, 0.8 * wave(t, 4.0), 0))
    R(c, 'body', lambda t: (2 * wave(t, 4.0, 1, 0.25), 0, 1.5 * wave(t, 4.0, 1, 0.1)))
    fins(c, 10, 2.0 * 2, 2)
    S(c, 'sac', lambda t: (0.06 * wave(t, 4.0, 1, -0.1),) * 3)
    R(c, 'tail', lambda t: (0, 8 * wave(t, 4.0, 1, -0.15), 0))
    R(c, 'crest', lambda t: (3 * wave(t, 4.0, 1, -0.2), 0, 0))
    out.append(c)

    c = Clip(m, 'fly', 1.2, 'loop')                  # leans into the drift, quicker strokes, tail swishing
    R(c, 'body', lambda t: (-10, 0, 3 * wave(t, 1.2)))
    P(c, 'body', lambda t: (0, 0.4 * wave(t, 1.2, 2), 0))
    fins(c, 18, 1.2, 2, base=4)
    R(c, 'tail', lambda t: (0, 16 * wave(t - 0.1, 1.2), 0))
    R(c, 'tail_tip', lambda t: (0, 14 * wave(t - 0.2, 1.2), 0))
    R(c, 'crest', const((-8, 0, 0)))
    out.append(c)

    c = Clip(m, 'puff', 2.6, 'once')                 # hurt / scared: puffs up round at once, deflates slowly
    p = lambda t: keys(t, [(0, 0), (0.12, 1.0), (1.4, 1.0), (2.6, 0)], 'out')  # noqa: E731
    inflate(c, p)
    R(c, 'tail', lambda t: (-20 * p(t), 0, 0))
    c.sound(0.02, PUFF, 0.7, 1.1)
    c.sound(1.6, BLOW, 0.5, 1.0)
    out.append(c)

    c = Clip(m, 'breathe', 1.6, 'once')              # the sac swells, a slow purple puff out of its mouth
    b = lambda t: env(t, 0.1, 1.2, 0.35)  # noqa: E731
    S(c, 'sac', lambda t: (0.35 * b(t), 0.3 * b(t), 0.35 * b(t)))
    S(c, 'body', lambda t: (0.04 * b(t), 0.04 * b(t), 0.04 * b(t)))
    R(c, 'h_head', lambda t: (6 * b(t), 0, 0))
    c.particle(0.9, 'minecraft:reverse_portal', 4, 0.15, 0.01, 0.4)
    out.append(c)

    c = Clip(m, 'harvest', 1.8, 'once')              # breathes into the bottle: sac swells, a long exhale
    b = lambda t: keys(t, [(0, 0), (0.5, 1.0), (0.9, 1.0), (1.5, -0.2), (1.8, 0)])  # noqa: E731
    S(c, 'sac', lambda t: (0.4 * b(t),) * 3)
    S(c, 'body', lambda t: (0.06 * b(t),) * 3)
    R(c, 'body', lambda t: (8 * env(t, 0.8, 1.7, 0.2), 0, 0))
    fins(c, 6, 1.8, 2)
    c.sound(0.9, BLOW, 0.5, 1.2)
    c.particle(1.0, 'minecraft:reverse_portal', 6, 0.2, 0.01, 0.4)
    out.append(c)

    c = Clip(m, 'hurt', 0.4, 'once')
    h = lambda t: jolt(t, 0.0, 0.4)  # noqa: E731
    R(c, 'body', lambda t: (14 * h(t), 0, -8 * h(t)))
    P(c, 'body', lambda t: (0, 0.8 * h(t), 0))
    out.append(c)

    c = Clip(m, 'death', 1.8, 'hold')                # deflates, rolls belly-up and sinks
    w = lambda t: step(t, 0.1, 1.2)  # noqa: E731
    S(c, 'body', lambda t: (0.08 * w(t), -0.3 * w(t), 0.08 * w(t)))
    R(c, 'root', lambda t: (0, 0, 165 * step(t, 0.3, 1.3)))
    P(c, 'root', lambda t: tuple(a + b for a, b in zip(about_center(165 * step(t, 0.3, 1.3)), (0, 1.5 * w(t), 0))))
    for fin, tipb, sg in FINS:
        R(c, fin, lambda t, sg=sg: (0, 0, -sg * 30 * w(t)))
    S(c, 'sac', lambda t: (-0.4 * w(t),) * 3)
    eyes_closed(c, spans=[(0.9, 1.8)])
    out.append(c)

    # ---------------------------------------------------------------- idle antics
    c = Clip(m, 'barrel_turn', 2.4, 'once')          # a lazy roll all the way round
    roll = lambda t: keys(t, [(0, 0), (0.2, -8), (1.9, 365), (2.15, 360), (2.4, 360)])  # noqa: E731
    R(c, 'root', lambda t: (0, 0, roll(t)))
    P(c, 'root', lambda t: about_center(roll(t)))
    fins(c, 14, 2.4, 3)
    out.append(c)

    c = Clip(m, 'gulp', 2.2, 'once')                 # a slow gulping yawn: the body stretches tall, sac drops (eyes open)
    g = lambda t: env(t, 0.2, 1.8, 0.5)  # noqa: E731
    S(c, 'body', lambda t: (-0.05 * g(t), 0.1 * g(t), -0.05 * g(t)))
    S(c, 'sac', lambda t: (0.25 * g(t), 0.4 * g(t), 0.25 * g(t)))
    P(c, 'sac', lambda t: (0, -0.6 * g(t), 0))
    R(c, 'crest', lambda t: (-12 * g(t), 0, 0))
    c.sound(1.0, BLOW, 0.3, 0.8)
    out.append(c)

    c = Clip(m, 'tail_flick', 1.0, 'once')
    R(c, 'tail', lambda t: (0, 26 * decay(t, 0.05, 2.5, 3.5), 0))
    R(c, 'tail_tip', lambda t: (0, 30 * decay(t, 0.12, 2.5, 3.5), 0))
    out.append(c)

    c = Clip(m, 'notice_puff', 1.4, 'once')          # sees you: a little half-puff, fins flare, then it relaxes
    p = lambda t: 0.45 * keys(t, [(0, 0), (0.15, 1.0), (0.6, 1.0), (1.4, 0)])  # noqa: E731
    inflate(c, p)
    R(c, 'h_head', lambda t: (8 * env(t, 0.1, 1.2), 0, 0))
    out.append(c)
    return out
