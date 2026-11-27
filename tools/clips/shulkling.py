"""Shulkling (Creatures 2, the End): a young shulker in a purpur shell, always half hiding. It hops like a shulker box
would (crouch, spring, the lid flapping open in the air, a squashy landing), peeks out under its lid with both hands
on the rim, and snaps shut when startled. Its own antics: a long peek (lid high, head stretched up), drumming on the
rim, humming to its frost crystal (it glows), hiding and peeking back out. Sitting = settled half-closed with only
its eyes over the rim; sleeping = the shell shut tight. Eyes close only in sleep-type clips (and stay hidden inside a
shut shell). Clips authored from scratch on the new rig."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _endkit import (Clip, Out, R, P, S, bump, const, decay, env, eyes_closed, hold_pose, jolt, keys, osc,  # noqa: E402
                     overshoot, pulse, step, wave)
from animkit import posture_set  # noqa: E402

TAU = 2 * math.pi
LAND = 'minecraft:entity.shulker.close'
OPEN = 'minecraft:entity.shulker.open'
CHIME = 'minecraft:block.amethyst_block.chime'
HANDS = ('left_arm', 'right_arm')

SIT = {
    ('lid', 'position'): (0, -1.5, 0), ('h_head', 'position'): (0, -1.0, 0),
    ('left_arm', 'position'): (0, -0.25, 0.25), ('right_arm', 'position'): (0, -0.25, 0.25),
}
SLEEP = {
    ('lid', 'position'): (0, -3.25, 0), ('h_head', 'position'): (0, -3.0, 0),
    ('left_arm', 'position'): (0, -1.0, 1.75), ('right_arm', 'position'): (0, -1.0, 1.75),
    ('body', 'position'): (0, -0.25, 0),
}

TAGS = {
    'peek': {'p': 'standing', 'move': False}, 'rim_drum': {'p': 'standing', 'move': False},
    'crystal_hum': {'p': 'standing', 'move': False}, 'hide': {'p': 'standing', 'move': False},
    'sit_peek': {'p': 'sitting', 'move': False, 'layer': 'base'}, 'idle_beg': {'p': 'sitting', 'move': False},
    'walk_scared': {'p': 'standing', 'move': True, 'gait': 'run'},
}
IDLE = {
    'standing': [{'clip': 'peek', 'w': 2, 'cd': 20}, {'clip': 'rim_drum', 'w': 2, 'cd': 25},
                 {'clip': 'crystal_hum', 'w': 1, 'cd': 35, 'boost': {'owner_near': 2}}, {'clip': 'hide', 'w': 1, 'cd': 40},
                 {'clip': 'groom', 'w': 1, 'cd': 30}],
    'sitting': [{'clip': 'sit_peek', 'w': 2, 'min': 4, 'max': 10}],
}


def hops(c, L, height, n=1, lid=8.0, arms=24.0, squash=0.08, lean=0.0, sound=0.45):
    """n hops per loop of length L: crouch, spring, lid flaps open in the air, squash on landing."""
    per = L / n

    def u(t):
        return (t % per) / per
    air = lambda t: bump(u(t), 0.12, 0.78)  # noqa: E731
    land = lambda t: bump(u(t), 0.74, 1.0)  # noqa: E731
    crouch = lambda t: bump(u(t), 0.0, 0.16)  # noqa: E731
    P(c, 'body', lambda t: (0, height * air(t) - 0.35 * crouch(t), 0))
    S(c, 'body', lambda t: (squash * land(t) + 0.04 * crouch(t), -squash * land(t) + 0.05 * air(t) - 0.05 * crouch(t),
                            squash * land(t) + 0.04 * crouch(t)))
    R(c, 'lid', lambda t: (lid * air(t), 0, 0))
    P(c, 'lid', lambda t: (0, -0.4 * land(t), 0))
    R(c, 'left_arm', lambda t: (0, 0, arms * air(t)))
    R(c, 'right_arm', lambda t: (0, 0, -arms * air(t)))
    P(c, 'h_head', lambda t: (0, 0.3 * air(t) - 0.3 * land(t), 0))
    if lean:
        R(c, 'body', const((lean, 0, 0)))
    R(c, 'crystal', lambda t: (0, 0, 6 * decay(u(t), 0.78, 3.0, 4.0)))
    if sound is not None:
        for k in range(n):
            c.sound(per * (k + 0.8), LAND, sound, 1.7)


def clips(m):
    out = Out(m)

    # ---------------------------------------------------------------- loops
    c = Clip(m, 'idle', 4.0, 'loop')                 # the lid breathes up and down, the hands tap now and then
    P(c, 'lid', lambda t: (0, 0.25 * wave(t, 4.0), 0))
    P(c, 'h_head', lambda t: (0, 0.15 * wave(t, 4.0, 1, -0.05), 0))
    R(c, 'left_arm', lambda t: (8 * bump(t, 1.2, 1.5) + 8 * bump(t, 1.5, 1.8), 0, 0))
    R(c, 'right_arm', lambda t: (8 * bump(t, 3.0, 3.3), 0, 0))
    S(c, 'crystal', lambda t: (0.03 * wave(t, 4.0, 2),) * 3)
    out.append(c)

    for name, L, h, n, kw in (('walk', 0.6, 2.2, 1, {}), ('run', 0.4, 2.8, 1, {'lean': -6, 'sound': 0.5}),
                              ('walk_happy', 0.5, 3.0, 1, {'lid': 16, 'arms': 34}),
                              ('walk_sneak', 0.9, 0.7, 1, {'lid': 0, 'arms': 6, 'sound': 0.2}),
                              ('walk_sleepy', 0.9, 1.2, 1, {'lid': 2, 'arms': 8, 'sound': 0.3}),
                              ('walk_limp', 0.8, 1.6, 1, {'lid': 4, 'arms': 12}),
                              ('walk_scared', 0.35, 2.4, 1, {'lid': 0, 'arms': 0, 'sound': 0.55})):
        c = Clip(m, name, L, 'loop')
        hops(c, L, h, n, **kw)
        if name == 'walk_sneak':
            hold = {('lid', 'position'): (0, -1.2, 0), ('h_head', 'position'): (0, -0.7, 0)}
            for (b, ch), v in hold.items():
                P(c, b, const(v))
        if name == 'walk_sleepy':
            P(c, 'lid', const((0, -1.0, 0)))
        if name == 'walk_limp':
            R(c, 'body', lambda t: (0, 0, 6 * wave(t, 0.8)))
        if name == 'walk_scared':                    # shut tight, hands tucked in
            P(c, 'lid', const((0, -3.0, 0)))
            P(c, 'h_head', const((0, -3.0, 0)))
            for hnd in HANDS:
                P(c, hnd, const((0, -1.0, 1.75)))
        out.append(c)

    c = Clip(m, 'swim', 1.0, 'loop')                 # bobs, hands paddling
    P(c, 'body', lambda t: (0, 0.4 * wave(t, 1.0), 0))
    R(c, 'body', lambda t: (-6, 0, 3 * wave(t, 1.0)))
    R(c, 'left_arm', lambda t: (30 * wave(t, 1.0, 2), 0, 20))
    R(c, 'right_arm', lambda t: (-30 * wave(t, 1.0, 2), 0, -20))
    R(c, 'lid', const((6, 0, 0)))
    out.append(c)
    c = Clip(m, 'idle_swim', 2.0, 'loop')
    P(c, 'body', lambda t: (0, 0.5 * wave(t, 2.0), 0))
    R(c, 'left_arm', lambda t: (16 * wave(t, 2.0, 2), 0, 16))
    R(c, 'right_arm', lambda t: (-16 * wave(t, 2.0, 2), 0, -16))
    out.append(c)

    c = Clip(m, 'idle_sleepy', 4.0, 'loop')          # the lid sags shut and jerks back up (eyes stay open)
    d = lambda t: keys(t, [(0, 0.2), (1.8, 1.0), (2.3, 1.0), (2.5, 0.1), (4.0, 0.2)])  # noqa: E731
    P(c, 'lid', lambda t: (0, -1.6 * d(t), 0))
    P(c, 'h_head', lambda t: (0, -0.8 * d(t), 0))
    out.append(c)

    c = Clip(m, 'idle_happy', 2.0, 'loop')           # little bounces in place, lid clapping
    hops(c, 2.0, 1.0, 4, lid=14, arms=20, sound=None)
    out.append(c)

    c = Clip(m, 'idle_alert', 2.0, 'loop')           # lid high, head up, looking round
    P(c, 'lid', const((0, 1.0, 0)))
    P(c, 'h_head', const((0, 0.8, 0)))
    R(c, 'h_head', lambda t: (0, 20 * wave(t, 2.0), 0))
    out.append(c)

    c = Clip(m, 'idle_hurt', 3.0, 'loop')            # half shut, tilted
    P(c, 'lid', const((0, -1.2, 0)))
    R(c, 'body', lambda t: (0, 0, -4 + 1 * wave(t, 3.0)))
    R(c, 'h_head', const((-6, 0, 6)))
    out.append(c)

    c = Clip(m, 'cower', 2.0, 'loop')                # shut tight, trembling
    hold_pose(c, {('lid', 'position'): (0, -3.0, 0), ('h_head', 'position'): (0, -3.0, 0),
                  ('left_arm', 'position'): (0, -1.0, 1.75), ('right_arm', 'position'): (0, -1.0, 1.75)})
    R(c, 'body', lambda t: (0, 0, 1.2 * osc(t, 7)))
    out.append(c)

    # ---------------------------------------------------------------- postures
    out.append(hold_pose(Clip(m, 'sit', 1.0, 'hold'), SIT))
    c = hold_pose(Clip(m, 'sleep', 4.0, 'loop'), SLEEP)
    S(c, 'body', lambda t: (0.012 * wave(t, 4.0), 0.02 * wave(t, 4.0), 0.012 * wave(t, 4.0)))
    P(c, 'lid', lambda t: (0, -3.25 + 0.12 * wave(t, 4.0), 0))
    eyes_closed(c)
    out.append(c)
    for c in posture_set(m, lengths={'sit_down': 0.6, 'stand_up': 0.6, 'lie_down': 0.9, 'wake_up': 1.0}):
        if c.name in ('lie_down', 'wake_up'):
            eyes_closed(c, spans=[(0.5, c.L)] if c.name == 'lie_down' else [(0, 0.45)])
            c.sound(0.7 if c.name == 'lie_down' else 0.2, LAND if c.name == 'lie_down' else OPEN, 0.4, 1.6)
        out.append(c)
    c = hold_pose(Clip(m, 'sit_peek', 4.0, 'loop'), SIT)   # settled, eyes over the rim, looking about
    R(c, 'h_head', lambda t: (0, 22 * wave(t, 4.0), 0))
    P(c, 'lid', lambda t: (0, -1.5 + 0.3 * bump(t, 1.4, 2.4), 0))
    out.append(c)
    c = hold_pose(Clip(m, 'idle_beg', 2.0, 'loop'), {('lid', 'position'): (0, 1.2, 0), ('h_head', 'position'): (0, 0.9, 0)})
    R(c, 'left_arm', lambda t: (-20, 0, 40 + 8 * wave(t, 2.0, 2)))
    R(c, 'right_arm', lambda t: (-20, 0, -40 - 8 * wave(t, 2.0, 2)))
    R(c, 'h_head', const((14, 0, 0)))
    out.append(c)

    # ---------------------------------------------------------------- one-shots
    c = Clip(m, 'death', 1.6, 'hold')                # the lid pops open and falls back, the shell tips over
    w = lambda t: step(t, 0.1, 0.6)  # noqa: E731
    R(c, 'lid', lambda t: (-55 * w(t) + 8 * decay(t, 0.6, 2.5, 4), 0, 0))
    P(c, 'h_head', lambda t: (0, -1.6 * step(t, 0.4, 1.0), 0))
    R(c, 'h_head', lambda t: (-20 * step(t, 0.4, 1.0), 0, 10 * step(t, 0.4, 1.0)))
    R(c, 'root', lambda t: (0, 0, 70 * step(t, 0.6, 1.2)))
    P(c, 'root', lambda t: (0, 4.0 * step(t, 0.6, 1.2), 0))
    eyes_closed(c, spans=[(0.9, 1.6)])
    out.append(c)

    c = Clip(m, 'hurt', 0.45, 'once')                # snaps shut and springs open again
    h = lambda t: jolt(t, 0.0, 0.45)  # noqa: E731
    P(c, 'lid', lambda t: (0, -2.6 * h(t), 0))
    P(c, 'h_head', lambda t: (0, -2.4 * h(t), 0))
    S(c, 'body', lambda t: (0.06 * h(t), -0.08 * h(t), 0.06 * h(t)))
    out.append(c)

    c = Clip(m, 'eat', 1.4, 'once')                  # lid up, head out, three nibbles
    up = lambda t: env(t, 0.05, 1.25, 0.2)  # noqa: E731
    nib = lambda t: sum(bump(t, a, a + 0.18) for a in (0.35, 0.65, 0.95))  # noqa: E731
    P(c, 'lid', lambda t: (0, 1.2 * up(t), 0))
    P(c, 'h_head', lambda t: (0, 0.9 * up(t) + 0.4 * nib(t), -0.4 * nib(t)))
    R(c, 'h_head', lambda t: (-8 * nib(t), 0, 0))
    for hnd, sgn in (('left_arm', 1), ('right_arm', -1)):
        R(c, hnd, lambda t, s=sgn: (-30 * up(t), 0, s * 10 * up(t)))
    c.sound(0.4, 'minecraft:entity.generic.eat', 0.4, 1.6)
    c.sound(0.95, 'minecraft:entity.generic.eat', 0.4, 1.7)
    out.append(c)

    c = Clip(m, 'happy', 1.4, 'once')                # two happy hops, lid flipping up
    hops(c, 1.4, 3.0, 2, lid=24, arms=40, sound=0.4)
    out.append(c)

    c = Clip(m, 'tame', 2.0, 'once')                 # the lid opens wide, it rises to look at you, the crystal flares
    w = lambda t: env(t, 0.1, 1.7, 0.35)  # noqa: E731
    R(c, 'lid', lambda t: (-28 * w(t), 0, 0))
    P(c, 'h_head', lambda t: (0, 1.4 * w(t), 0))
    R(c, 'h_head', lambda t: (12 * w(t), 0, 5 * math.sin(TAU * t / 1.0) * w(t)))
    S(c, 'crystal', lambda t: (0.3 * w(t),) * 3)
    S(c, 'crystal_b', lambda t: (0.3 * w(t),) * 3)
    for hnd, sgn in (('left_arm', 1), ('right_arm', -1)):
        R(c, hnd, lambda t, s=sgn: (0, 0, s * 40 * w(t)))
    c.sound(0.2, OPEN, 0.5, 1.6)
    c.sound(0.6, CHIME, 0.5, 1.5)
    c.particle(0.7, 'minecraft:end_rod', 4, 0.3, 0.02, 1.1)
    out.append(c)

    c = Clip(m, 'ability', 1.4, 'once')              # hums in shulker: lid lifts, the crystal pulses twice
    w = lambda t: env(t, 0.05, 1.3, 0.25)  # noqa: E731
    P(c, 'lid', lambda t: (0, 1.4 * w(t), 0))
    P(c, 'h_head', lambda t: (0, 1.0 * w(t), 0))
    R(c, 'h_head', lambda t: (10 * w(t), 0, 0))
    for cr in ('crystal', 'crystal_b'):
        S(c, cr, lambda t: (0.3 * (bump(t, 0.25, 0.65) + bump(t, 0.7, 1.1)),) * 3)
    c.particle(0.5, 'minecraft:portal', 6, 0.3, 0.05, 0.8)
    out.append(c)

    c = Clip(m, 'look_around', 2.4, 'once')
    yaw = lambda t: keys(t, [(0, 0), (0.5, 40), (1.0, 36), (1.5, -40), (2.0, -36), (2.4, 0)])  # noqa: E731
    P(c, 'lid', lambda t: (0, 0.8 * env(t, 0.1, 2.2), 0))
    P(c, 'h_head', lambda t: (0, 0.6 * env(t, 0.1, 2.2), 0))
    R(c, 'h_head', lambda t: (0, yaw(t), 0))
    out.append(c)

    c = Clip(m, 'spawn', 1.2, 'once')                # pops open out of a shut shell
    P(c, 'lid', lambda t: (0, -3.25 * (1 - overshoot(t, 0.3, 0.9, 0.2)), 0))
    P(c, 'h_head', lambda t: (0, -3.5 * (1 - overshoot(t, 0.4, 1.0, 0.2)), 0))
    c.sound(0.3, OPEN, 0.5, 1.5)
    out.append(c)

    c = Clip(m, 'greet', 1.4, 'once')                # flips the lid up and waves
    P(c, 'lid', lambda t: (0, 1.2 * env(t, 0.05, 1.25, 0.2), 0))
    R(c, 'lid', lambda t: (-12 * bump(t, 0.1, 0.5), 0, 0))
    R(c, 'left_arm', lambda t: (-20 * env(t, 0.1, 1.2), 0, 60 * env(t, 0.1, 1.2, 0.2) + 16 * osc(t, 3) * env(t, 0.3, 1.1, 0.1)))
    P(c, 'h_head', lambda t: (0, 0.8 * env(t, 0.05, 1.25), 0))
    out.append(c)

    c = Clip(m, 'notice', 0.9, 'once')               # half shut, then peeks at it
    n = lambda t: keys(t, [(0, 0), (0.08, 1.0), (0.35, 1.0), (0.6, -0.4), (0.9, 0)])  # noqa: E731
    P(c, 'lid', lambda t: (0, -1.8 * n(t), 0))
    P(c, 'h_head', lambda t: (0, -1.4 * n(t), 0))
    R(c, 'h_head', lambda t: (0, 18 * env(t, 0.4, 0.9, 0.15), 0))
    out.append(c)

    c = Clip(m, 'curious', 1.6, 'once')
    k = lambda t: env(t, 0.1, 1.4, 0.3)  # noqa: E731
    P(c, 'lid', lambda t: (0, 1.0 * k(t), 0))
    P(c, 'h_head', lambda t: (0, 0.8 * k(t), -0.6 * k(t)))
    R(c, 'h_head', lambda t: (0, 0, keys(t, [(0, 0), (0.4, 16), (0.8, 14), (1.0, -12), (1.3, -10), (1.6, 0)])))
    out.append(c)

    c = Clip(m, 'stretch', 2.0, 'once')              # lid wide open on its hinge, hands up
    s = lambda t: env(t, 0.2, 1.6, 0.45)  # noqa: E731
    R(c, 'lid', lambda t: (-30 * s(t), 0, 0))
    P(c, 'h_head', lambda t: (0, 1.4 * s(t), 0))
    for hnd, sgn in (('left_arm', 1), ('right_arm', -1)):
        R(c, hnd, lambda t, sg=sgn: (-30 * s(t), 0, sg * 70 * s(t)))
    out.append(c)

    c = Clip(m, 'yawn', 2.0, 'once')                 # the lid opens slowly like a big yawn (eyes stay open)
    y = lambda t: env(t, 0.2, 1.7, 0.5)  # noqa: E731
    R(c, 'lid', lambda t: (-22 * y(t), 0, 0))
    R(c, 'h_head', lambda t: (10 * y(t), 0, -5 * y(t)))
    out.append(c)

    c = Clip(m, 'shake', 1.2, 'once')                # rattles its shell
    k = lambda t: env(t, 0.1, 1.0, 0.15)  # noqa: E731
    R(c, 'body', lambda t: (0, 12 * osc(t, 6) * k(t), 0))
    R(c, 'lid', lambda t: (3 * abs(osc(t, 6)) * k(t), -6 * osc(t - 0.03, 6) * k(t), 0))
    c.sound(0.3, LAND, 0.25, 1.9)
    c.sound(0.7, LAND, 0.25, 1.9)
    out.append(c)

    c = Clip(m, 'wiggle', 1.2, 'once')
    k = lambda t: env(t, 0.05, 1.1, 0.15)  # noqa: E731
    R(c, 'body', lambda t: (0, 0, 6 * osc(t, 3.3) * k(t)))
    R(c, 'lid', lambda t: (0, 0, -6 * osc(t - 0.05, 3.3) * k(t)))
    R(c, 'crystal', lambda t: (0, 0, 12 * osc(t - 0.1, 3.3) * k(t)))
    out.append(c)

    c = Clip(m, 'spin', 1.2, 'once')                 # one hop, a full turn in the air
    P(c, 'body', lambda t: (0, 3.2 * bump(t, 0.1, 0.9), 0))
    R(c, 'root', lambda t: (0, keys(t, [(0, 0), (0.15, -10), (0.85, 365), (1.0, 360), (1.2, 360)]), 0))
    R(c, 'lid', lambda t: (16 * bump(t, 0.15, 0.85), 0, 0))
    c.sound(0.92, LAND, 0.4, 1.7)
    out.append(c)

    c = Clip(m, 'hop', 0.8, 'once')
    hops(c, 0.8, 3.2, 1, lid=18, arms=30, sound=0.4)
    out.append(c)

    c = Clip(m, 'sigh', 1.6, 'once')
    s = lambda t: bump(t, 0.2, 1.4)  # noqa: E731
    P(c, 'lid', lambda t: (0, -1.0 * s(t), 0))
    P(c, 'h_head', lambda t: (0, -0.6 * s(t), 0))
    S(c, 'body', lambda t: (0.02 * s(t), -0.04 * s(t), 0.02 * s(t)))
    out.append(c)

    c = Clip(m, 'dance', 2.4, 'once')                # bobs side to side, lid clapping to the beat
    k = lambda t: env(t, 0.1, 2.3, 0.2)  # noqa: E731
    R(c, 'body', lambda t: (0, 0, 8 * osc(t, 1.25) * k(t)))
    P(c, 'body', lambda t: (0, 0.8 * abs(osc(t, 2.5)) * k(t), 0))
    R(c, 'lid', lambda t: (10 * max(0, osc(t, 2.5)) * k(t), 0, 0))
    for hnd, sgn in (('left_arm', 1), ('right_arm', -1)):
        R(c, hnd, lambda t, sg=sgn: (0, 0, sg * (30 + 20 * osc(t, 2.5)) * k(t)))
    out.append(c)

    c = Clip(m, 'groom', 2.0, 'once')                # reaches up and polishes the frost crystal
    k = lambda t: env(t, 0.15, 1.8, 0.3)  # noqa: E731
    P(c, 'lid', lambda t: (0, 0.6 * k(t), 0))
    R(c, 'left_arm', lambda t: (-60 * k(t), 0, 80 * k(t) + 10 * osc(t, 4) * env(t, 0.5, 1.5, 0.1)))
    R(c, 'h_head', lambda t: (16 * k(t), -10 * k(t), 0))
    c.sound(0.7, CHIME, 0.3, 1.8)
    out.append(c)

    # ---------------------------------------------------------------- its own antics
    c = Clip(m, 'peek', 3.0, 'once')                 # lid high, head stretched up, a long careful look
    up = lambda t: env(t, 0.3, 2.6, 0.45)  # noqa: E731
    P(c, 'lid', lambda t: (0, 2.0 * up(t), 0))
    P(c, 'h_head', lambda t: (0, 1.6 * up(t), 0))
    R(c, 'h_head', lambda t: (6 * up(t), 30 * math.sin(TAU * (t - 0.3) / 2.3) * up(t), 0))
    for hnd in HANDS:
        P(c, hnd, lambda t: (0, 0.3 * up(t), 0))
    c.sound(0.3, OPEN, 0.3, 1.8)
    out.append(c)

    c = Clip(m, 'rim_drum', 2.0, 'once')             # drums its hands on the rim
    k = lambda t: env(t, 0.1, 1.85, 0.15)  # noqa: E731
    R(c, 'left_arm', lambda t: (22 * max(0, osc(t, 4)) * k(t), 0, 0))
    R(c, 'right_arm', lambda t: (22 * max(0, osc(t, 4, 0.5)) * k(t), 0, 0))
    R(c, 'h_head', lambda t: (0, 0, 4 * osc(t, 2) * k(t)))
    for i in range(7):
        c.sound(0.2 + i * 0.25, 'minecraft:block.stone_button.click_on', 0.2, 1.8)
    out.append(c)

    c = Clip(m, 'crystal_hum', 2.8, 'once')          # hums to its crystal: it swells and rings
    g = lambda t: env(t, 0.3, 2.4, 0.5)  # noqa: E731
    for cr in ('crystal', 'crystal_b'):
        S(c, cr, lambda t: ((0.3 + 0.06 * osc(t, 3)) * g(t),) * 3)
    P(c, 'lid', lambda t: (0, 0.6 * g(t), 0))
    R(c, 'h_head', lambda t: (0, 0, 6 * osc(t, 1) * g(t)))
    c.sound(0.5, CHIME, 0.45, 1.5)
    c.sound(1.4, CHIME, 0.35, 1.8)
    c.particle(1.0, 'minecraft:end_rod', 3, 0.2, 0.02, 1.2)
    out.append(c)

    c = Clip(m, 'hide', 2.6, 'once')                 # snaps shut, waits, peeks back out slowly
    shut = lambda t: keys(t, [(0, 0), (0.1, 1.0), (1.4, 1.0), (1.9, 0.55), (2.2, 0.55), (2.6, 0)])  # noqa: E731
    P(c, 'lid', lambda t: (0, -3.0 * shut(t), 0))
    P(c, 'h_head', lambda t: (0, -3.0 * shut(t), 0))
    for hnd in HANDS:
        P(c, hnd, lambda t: (0, -1.0 * min(1.0, shut(t) * 1.5), 1.75 * min(1.0, shut(t) * 1.5)))
    c.sound(0.05, LAND, 0.4, 1.7)
    c.sound(1.5, OPEN, 0.3, 1.8)
    out.append(c)
    return out
