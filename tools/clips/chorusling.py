"""Chorusling (Creatures 2, the End): a chorus sprout that pulled up its roots and walked off its plant. Calm, a
little stiff, curious the way a plant turns to the light. It moves like something rooted: deliberate steps on root
feet, branch arms that sway a beat behind the body, the seed on its crown bobbing on its stalk. Its own antics are
plant things: turning its face up to the light, wiggling its roots into the ground, reaching its branches up,
polishing a bud, making its seed glow. Sitting = it plants itself (legs out in front, branches relaxed); sleeping =
it droops like a flower at night. Eyes close only in sleep-type clips. Clips authored from scratch on the new rig."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _endkit import (Clip, Out, R, P, S, bump, const, decay, env, eyes_closed, hold_pose, jolt, keys, osc,  # noqa: E402
                     overshoot, pulse, step, wave)
from animkit import posture_set  # noqa: E402

TAU = 2 * math.pi
STEP = 'minecraft:block.wood.step'
GROW = 'minecraft:block.chorus_flower.grow'
CHIME = 'minecraft:block.amethyst_block.chime'
TELEPORT = 'minecraft:item.chorus_fruit.teleport'

SIT = {
    ('body', 'position'): (0, -2.25, 0), ('body', 'rotation'): (4, 0, 0),
    ('left_leg', 'rotation'): (82, 0, -6), ('right_leg', 'rotation'): (82, 0, 6),
    ('left_arm', 'rotation'): (0, 0, -18), ('right_arm', 'rotation'): (0, 0, 18),
    ('h_head', 'rotation'): (-4, 0, 0), ('tail', 'rotation'): (-12, 0, 0),
}
SLEEP = {
    ('body', 'position'): (0, -2.25, 0), ('body', 'rotation'): (-6, 0, 0),
    ('left_leg', 'rotation'): (82, 0, -6), ('right_leg', 'rotation'): (82, 0, 6),
    ('left_arm', 'rotation'): (10, 0, -42), ('right_arm', 'rotation'): (10, 0, 42),
    ('h_head', 'rotation'): (-24, 0, 7), ('tail', 'rotation'): (-12, 0, 0),
    ('seed', 'rotation'): (-18, 0, 10), ('seed', 'scale'): (0.9, 0.9, 0.9),
}

TAGS = {
    'sun_turn': {'p': 'standing', 'move': False}, 'root_wiggle': {'p': 'standing', 'move': False},
    'branch_reach': {'p': 'standing', 'move': False}, 'seed_glow': {'p': 'standing', 'move': False},
    'idle_beg': {'p': 'sitting', 'move': False}, 'sit_sway': {'p': 'sitting', 'move': False, 'layer': 'base'},
}
IDLE = {
    'standing': [{'clip': 'sun_turn', 'w': 2, 'cd': 25}, {'clip': 'root_wiggle', 'w': 2, 'cd': 30},
                 {'clip': 'branch_reach', 'w': 1, 'cd': 30}, {'clip': 'seed_glow', 'w': 1, 'cd': 40, 'boost': {'owner_near': 2}},
                 {'clip': 'groom', 'w': 1, 'cd': 30}],
    'sitting': [{'clip': 'sit_sway', 'w': 2, 'min': 4, 'max': 10}],
}
PROCEDURAL = {'tail_sway': 4, 'tail': ['tail', 'tail_tip']}


def legs(c, amp, L, cycles=1):
    """Alternating leg swing (left leads)."""
    R(c, 'left_leg', lambda t: (amp * wave(t, L, cycles), 0, 0))
    R(c, 'right_leg', lambda t: (-amp * wave(t, L, cycles), 0, 0))


def arms(c, amp, L, cycles=1, lag=0.08):
    """Branch arms swing opposite their legs, a beat behind (they are branches, not arms)."""
    R(c, 'left_arm', lambda t: (-amp * wave(t - lag, L, cycles), 0, 0))
    R(c, 'right_arm', lambda t: (amp * wave(t - lag, L, cycles), 0, 0))


def seed_lag(c, amp, L, cycles=1, lag=0.12):
    R(c, 'seed', lambda t: (0, 0, amp * wave(t - lag, L, cycles)))


def gait(m, name, L, leg, arm, bob, roll=3.0, lean=0.0, extra=None, sound=STEP, vol=0.5):
    c = Clip(m, name, L, 'loop')
    legs(c, leg, L)
    arms(c, arm, L)
    P(c, 'body', lambda t: (0, bob * (0.5 - 0.5 * math.cos(2 * TAU * t / L)), 0))
    R(c, 'body', lambda t: (lean, 0, roll * wave(t, L)))
    R(c, 'h_head', lambda t: (-lean * 0.5, 0, -0.6 * roll * wave(t - 0.05, L)))
    seed_lag(c, 2.2 * roll, L)
    R(c, 'tail', lambda t: (0, 10 * wave(t, L), 0))
    if extra:
        extra(c)
    if sound:
        c.sound(L * 0.02, sound, vol, 1.4)
        c.sound(L * 0.52, sound, vol, 1.5)
    return c


def clips(m):
    out = Out(m)

    # ---------------------------------------------------------------- loops
    c = Clip(m, 'idle', 4.0, 'loop')                 # branches sway in a breeze only it feels; the seed bobs
    R(c, 'left_arm', lambda t: (0, 0, 3 * wave(t, 4.0)))
    R(c, 'right_arm', lambda t: (0, 0, -3 * wave(t, 4.0, 1, 0.25)))
    R(c, 'h_head', lambda t: (0, 0, 2 * wave(t, 4.0, 1, 0.1)))
    R(c, 'seed', lambda t: (0, 0, 4 * wave(t, 4.0, 1, 0.3)))
    S(c, 'seed', lambda t: (0.04 * wave(t, 4.0, 2),) * 3)
    out.append(c)

    out.append(gait(m, 'walk', 0.8, 26, 9, 0.45))
    out.append(gait(m, 'run', 0.5, 38, 16, 0.8, roll=4, lean=-8, vol=0.6))
    out.append(gait(m, 'walk_happy', 0.7, 30, 14, 0.9, roll=5, extra=lambda c: R(c, 'h_head', lambda t: (0, 8 * wave(t, 0.7), 0))))
    out.append(gait(m, 'walk_sneak', 1.2, 16, 4, 0.15, roll=1.5, lean=-6,
                    extra=lambda c: P(c, 'body', const((0, -0.8, 0))), vol=0.25))
    out.append(gait(m, 'walk_sleepy', 1.2, 16, 4, 0.2, roll=2,
                    extra=lambda c: (R(c, 'h_head', lambda t: (-14, 0, 5 * wave(t, 1.2))),
                                     R(c, 'left_arm', const((0, 0, -16))), R(c, 'right_arm', const((0, 0, 16))))))
    out.append(gait(m, 'walk_limp', 1.0, 20, 6, 0.6, roll=6,
                    extra=lambda c: R(c, 'right_leg', lambda t: (-8 * wave(t, 1.0), 0, 0)), vol=0.4))
    out.append(gait(m, 'walk_scared', 0.45, 36, 22, 0.7, roll=3, lean=-10,
                    extra=lambda c: (R(c, 'left_arm', const((0, 0, 30))), R(c, 'right_arm', const((0, 0, -30)))), vol=0.6))

    c = Clip(m, 'swim', 1.0, 'loop')                 # paddles with its roots, branches up out of the water
    legs(c, 34, 1.0, 2)
    R(c, 'body', lambda t: (-12, 0, 3 * wave(t, 1.0)))
    R(c, 'left_arm', lambda t: (0, 0, 40 + 6 * wave(t, 1.0)))
    R(c, 'right_arm', lambda t: (0, 0, -40 - 6 * wave(t, 1.0)))
    R(c, 'h_head', const((10, 0, 0)))
    P(c, 'body', lambda t: (0, 0.3 * wave(t, 1.0, 2), 0))
    out.append(c)
    c = Clip(m, 'idle_swim', 2.0, 'loop')
    legs(c, 16, 2.0, 2)
    R(c, 'left_arm', lambda t: (0, 0, 36 + 4 * wave(t, 2.0)))
    R(c, 'right_arm', lambda t: (0, 0, -36 - 4 * wave(t, 2.0)))
    R(c, 'h_head', lambda t: (8, 10 * wave(t, 2.0), 0))
    out.append(c)

    c = Clip(m, 'idle_sleepy', 4.0, 'loop')          # droops between slow upright moments (eyes stay open)
    d = lambda t: keys(t, [(0, 0.3), (1.6, 1.0), (2.4, 1.0), (3.2, 0.2), (4.0, 0.3)])  # noqa: E731
    R(c, 'h_head', lambda t: (-16 * d(t), 0, 5 * d(t)))
    R(c, 'left_arm', lambda t: (0, 0, -22 * d(t)))
    R(c, 'right_arm', lambda t: (0, 0, 22 * d(t)))
    R(c, 'seed', lambda t: (-14 * d(t), 0, 8 * d(t)))
    out.append(c)

    c = Clip(m, 'idle_happy', 2.0, 'loop')           # little bounces, branches up, seed bobbing
    P(c, 'body', lambda t: (0, 0.6 * bump(t % 0.5, 0.0, 0.5), 0))
    R(c, 'left_arm', lambda t: (0, 0, 24 + 8 * wave(t, 2.0, 4)))
    R(c, 'right_arm', lambda t: (0, 0, -24 - 8 * wave(t, 2.0, 4)))
    R(c, 'h_head', lambda t: (4, 0, 6 * wave(t, 2.0, 2)))
    R(c, 'seed', lambda t: (0, 0, 10 * wave(t - 0.1, 2.0, 4)))
    out.append(c)

    c = Clip(m, 'idle_alert', 2.0, 'loop')           # stands tall, face to the sky, branches stiff
    P(c, 'body', const((0, 0.4, 0)))
    R(c, 'h_head', lambda t: (8, 14 * wave(t, 2.0), 0))
    R(c, 'left_arm', const((0, 0, 12)))
    R(c, 'right_arm', const((0, 0, -12)))
    out.append(c)

    c = Clip(m, 'idle_hurt', 3.0, 'loop')            # leans, the right root held off the ground
    R(c, 'body', lambda t: (0, 0, -5 + 1 * wave(t, 3.0)))
    R(c, 'right_leg', const((-12, 0, 0)))
    R(c, 'h_head', const((-10, 0, 4)))
    R(c, 'left_arm', const((0, 0, -20)))
    R(c, 'right_arm', const((0, 0, 24)))
    out.append(c)

    c = Clip(m, 'cower', 2.0, 'loop')                # branches over its head, trembling
    P(c, 'body', const((0, -0.8, 0)))
    R(c, 'body', lambda t: (-10, 0, 1.2 * osc(t, 6)))
    R(c, 'left_arm', const((-10, 0, 62)))
    R(c, 'right_arm', const((-10, 0, -62)))
    R(c, 'h_head', const((-18, 0, 0)))
    out.append(c)

    # ---------------------------------------------------------------- postures
    sit = hold_pose(Clip(m, 'sit', 1.0, 'hold'), SIT)
    out.append(sit)
    c = hold_pose(Clip(m, 'sleep', 4.0, 'loop'), SLEEP)
    R(c, 'h_head', lambda t: (1.5 * wave(t, 4.0), 0, 0))
    S(c, 'body', lambda t: (0.015 * wave(t, 4.0), 0.02 * wave(t, 4.0), 0.015 * wave(t, 4.0)))
    eyes_closed(c)
    out.append(c)
    for c in posture_set(m, lengths={'sit_down': 0.9, 'stand_up': 0.8, 'lie_down': 1.4, 'wake_up': 1.3}):
        if c.name == 'sit_down':
            c.sound(0.75, 'minecraft:block.wood.place', 0.4, 1.4)
        if c.name in ('lie_down', 'wake_up'):
            eyes_closed(c, spans=[(0.7, c.L)] if c.name == 'lie_down' else [(0, 0.5)])
        out.append(c)
    c = hold_pose(Clip(m, 'sit_sway', 4.0, 'loop'), SIT)   # planted: it sways a little, turning to the light
    R(c, 'h_head', lambda t: (-4 + 3 * wave(t, 4.0, 1, 0.25), 12 * wave(t, 4.0), 0))
    R(c, 'left_arm', lambda t: (0, 0, 3 * wave(t, 4.0)))
    R(c, 'right_arm', lambda t: (0, 0, -3 * wave(t, 4.0)))
    out.append(c)
    c = hold_pose(Clip(m, 'idle_beg', 2.0, 'loop'), SIT)   # sitting up, both branches raised, face up
    R(c, 'left_arm', const((0, 0, 42)))
    R(c, 'right_arm', const((0, 0, -42)))
    R(c, 'h_head', lambda t: (16, 0, 5 * wave(t, 2.0)))
    R(c, 'body', const((8, 0, 0)))
    out.append(c)

    # ---------------------------------------------------------------- one-shots
    c = Clip(m, 'death', 1.6, 'hold')                # wilts and tips over, the seed dims
    w = lambda t: step(t, 0.15, 1.1)  # noqa: E731
    R(c, 'root', lambda t: (0, 0, 88 * step(t, 0.5, 1.1)))
    P(c, 'root', lambda t: (0, 3.0 * step(t, 0.5, 1.1), 0))
    R(c, 'h_head', lambda t: (-20 * w(t), 0, 0))
    R(c, 'left_arm', lambda t: (0, 0, -40 * w(t)))
    R(c, 'right_arm', lambda t: (0, 0, 40 * w(t)))
    S(c, 'seed', lambda t: (-0.3 * w(t),) * 3)
    eyes_closed(c, spans=[(0.9, 1.6)])
    out.append(c)

    c = Clip(m, 'hurt', 0.4, 'once')
    h = lambda t: jolt(t, 0.0, 0.4)  # noqa: E731
    R(c, 'body', lambda t: (10 * h(t), 0, -6 * h(t)))
    R(c, 'h_head', lambda t: (12 * h(t), 0, 6 * h(t)))
    R(c, 'left_arm', lambda t: (0, 0, 20 * h(t)))
    R(c, 'right_arm', lambda t: (0, 0, -20 * h(t)))
    out.append(c)

    c = Clip(m, 'eat', 1.6, 'once')                  # holds the fruit up on a branch and nibbles it
    up = lambda t: env(t, 0.1, 1.4, 0.3)  # noqa: E731
    nib = lambda t: sum(bump(t, a, a + 0.2) for a in (0.45, 0.75, 1.05))  # noqa: E731
    R(c, 'left_arm', lambda t: (-40 * up(t), 30 * up(t), 35 * up(t)))
    R(c, 'h_head', lambda t: (-8 * up(t) - 8 * nib(t), -14 * up(t), 0))
    c.sound(0.5, 'minecraft:entity.generic.eat', 0.4, 1.5)
    c.sound(0.95, 'minecraft:entity.generic.eat', 0.4, 1.6)
    out.append(c)

    c = Clip(m, 'happy', 1.4, 'once')                # two hops, branches flung up, the seed flicks
    hop = lambda t: bump(t, 0.1, 0.55) + bump(t, 0.65, 1.1)  # noqa: E731
    P(c, 'body', lambda t: (0, 2.2 * hop(t), 0))
    R(c, 'left_arm', lambda t: (0, 0, 50 * env(t, 0.05, 1.25, 0.15)))
    R(c, 'right_arm', lambda t: (0, 0, -50 * env(t, 0.05, 1.25, 0.15)))
    legs_tuck = lambda t: 20 * hop(t)  # noqa: E731
    R(c, 'left_leg', lambda t: (legs_tuck(t), 0, 0))
    R(c, 'right_leg', lambda t: (-legs_tuck(t), 0, 0))
    R(c, 'seed', lambda t: (0, 0, 14 * decay(t, 0.3, 2.5, 3.0)))
    c.sound(0.55, STEP, 0.5, 1.5)
    c.sound(1.1, STEP, 0.5, 1.6)
    out.append(c)

    c = Clip(m, 'tame', 2.0, 'once')                 # turns its face up to you, the seed blooms bright
    w = lambda t: env(t, 0.1, 1.7, 0.4)  # noqa: E731
    R(c, 'h_head', lambda t: (16 * w(t), 0, 6 * math.sin(TAU * t / 1.0) * w(t)))
    S(c, 'seed', lambda t: ((0.35 * w(t) + 0.1 * bump(t, 0.6, 1.2)),) * 3)
    R(c, 'left_arm', lambda t: (0, 0, 36 * w(t)))
    R(c, 'right_arm', lambda t: (0, 0, -36 * w(t)))
    P(c, 'body', lambda t: (0, 0.8 * w(t), 0))
    c.sound(0.4, CHIME, 0.5, 1.4)
    c.particle(0.6, 'minecraft:end_rod', 4, 0.3, 0.02, 1.1)
    out.append(c)

    c = Clip(m, 'ability', 1.4, 'once')              # pulls: plants its feet, branches whip back, the seed flares
    pull = lambda t: keys(t, [(0, 0), (0.2, -0.3), (0.45, 1.0), (0.9, 0.9), (1.4, 0)])  # noqa: E731
    R(c, 'body', lambda t: (10 * pull(t), 0, 0))
    R(c, 'left_arm', lambda t: (-50 * pull(t), 0, 30 * pull(t)))
    R(c, 'right_arm', lambda t: (-50 * pull(t), 0, -30 * pull(t)))
    R(c, 'h_head', lambda t: (12 * pull(t), 0, 0))
    S(c, 'seed', lambda t: (0.45 * bump(t, 0.3, 1.0),) * 3)
    c.particle(0.45, 'minecraft:reverse_portal', 8, 0.4, 0.05, 0.8)
    out.append(c)

    c = Clip(m, 'look_around', 2.4, 'once')
    yaw = lambda t: keys(t, [(0, 0), (0.5, 40), (1.0, 38), (1.5, -40), (2.0, -36), (2.4, 0)])  # noqa: E731
    R(c, 'h_head', lambda t: (4 * env(t, 0.2, 2.2), yaw(t), 0))
    R(c, 'seed', lambda t: (0, 0, -0.15 * (yaw(t - 0.15) if t > 0.15 else 0)))
    out.append(c)

    c = Clip(m, 'spawn', 1.2, 'once')                # sprouts up out of the ground
    g = lambda t: overshoot(t, 0.0, 0.8, 0.12)  # noqa: E731
    S(c, 'root', lambda t: (g(t) - 1, g(t) - 1, g(t) - 1))
    R(c, 'seed', lambda t: (0, 0, 12 * decay(t, 0.6, 2.5, 3.5)))
    out.append(c)

    c = Clip(m, 'greet', 1.4, 'once')                # waves a branch, a small bow
    R(c, 'left_arm', lambda t: (0, 0, 70 * env(t, 0.05, 1.2, 0.2) + 14 * osc(t, 3.0) * env(t, 0.25, 1.1, 0.15)))
    R(c, 'h_head', lambda t: (-12 * bump(t, 0.9, 1.35), 0, 6 * env(t, 0.1, 1.0)))
    out.append(c)

    c = Clip(m, 'notice', 0.9, 'once')
    n = lambda t: overshoot(t, 0.0, 0.25, 0.2) * (1 - step(t, 0.55, 0.9))  # noqa: E731
    R(c, 'h_head', lambda t: (10 * n(t), 18 * n(t), 0))
    R(c, 'left_arm', lambda t: (0, 0, 14 * n(t)))
    R(c, 'right_arm', lambda t: (0, 0, -14 * n(t)))
    out.append(c)

    c = Clip(m, 'curious', 1.6, 'once')              # tilts its flower head, leans in
    tilt = lambda t: env(t, 0.1, 1.4, 0.3)  # noqa: E731
    R(c, 'h_head', lambda t: (-6 * tilt(t), 0, keys(t, [(0, 0), (0.4, 18), (0.8, 16), (1.0, -12), (1.3, -10), (1.6, 0)])))
    R(c, 'body', lambda t: (-6 * tilt(t), 0, 0))
    out.append(c)

    c = Clip(m, 'stretch', 2.0, 'once')
    s = lambda t: env(t, 0.2, 1.6, 0.45)  # noqa: E731
    R(c, 'left_arm', lambda t: (0, 0, 60 * s(t)))
    R(c, 'right_arm', lambda t: (0, 0, -60 * s(t)))
    S(c, 'body', lambda t: (-0.04 * s(t), 0.08 * s(t), -0.04 * s(t)))
    R(c, 'h_head', lambda t: (14 * s(t), 0, 0))
    out.append(c)

    c = Clip(m, 'yawn', 2.0, 'once')                 # a slow droop and recover (eyes stay open)
    y = lambda t: env(t, 0.2, 1.7, 0.5)  # noqa: E731
    R(c, 'h_head', lambda t: (16 * y(t), 0, -6 * y(t)))
    R(c, 'left_arm', lambda t: (0, 0, 28 * y(t)))
    R(c, 'right_arm', lambda t: (0, 0, -28 * y(t)))
    S(c, 'h_head', lambda t: (0.03 * y(t), -0.03 * y(t), 0.03 * y(t)))
    out.append(c)

    c = Clip(m, 'shake', 1.2, 'once')                # shakes end-stone dust out of its branches
    k = lambda t: env(t, 0.1, 1.0, 0.15)  # noqa: E731
    R(c, 'body', lambda t: (0, 14 * osc(t, 5) * k(t), 0))
    R(c, 'h_head', lambda t: (0, -10 * osc(t - 0.04, 5) * k(t), 0))
    R(c, 'left_arm', lambda t: (0, 0, 16 * osc(t - 0.06, 5) * k(t)))
    R(c, 'right_arm', lambda t: (0, 0, 16 * osc(t - 0.06, 5) * k(t)))
    c.particle(0.5, 'minecraft:white_ash', 6, 0.4, 0.02, 0.6)
    out.append(c)

    c = Clip(m, 'wiggle', 1.2, 'once')
    k = lambda t: env(t, 0.05, 1.1, 0.15)  # noqa: E731
    R(c, 'body', lambda t: (0, 0, 7 * osc(t, 3.3) * k(t)))
    R(c, 'h_head', lambda t: (0, 0, -9 * osc(t - 0.05, 3.3) * k(t)))
    R(c, 'seed', lambda t: (0, 0, 14 * osc(t - 0.12, 3.3) * k(t)))
    out.append(c)

    c = Clip(m, 'spin', 1.2, 'once')                 # a whole turn on one root
    R(c, 'root', lambda t: (0, keys(t, [(0, 0), (0.15, -12), (0.9, 365), (1.05, 360), (1.2, 360)]), 0))
    R(c, 'left_arm', lambda t: (0, 0, 40 * pulse(t, 0.1, 1.1)))
    R(c, 'right_arm', lambda t: (0, 0, -40 * pulse(t, 0.1, 1.1)))
    R(c, 'right_leg', lambda t: (30 * pulse(t, 0.15, 1.0), 0, 0))
    out.append(c)

    c = Clip(m, 'hop', 0.8, 'once')
    P(c, 'body', lambda t: (0, 2.6 * bump(t, 0.1, 0.65), 0))
    R(c, 'left_leg', lambda t: (18 * bump(t, 0.1, 0.65), 0, 0))
    R(c, 'right_leg', lambda t: (18 * bump(t, 0.1, 0.65), 0, 0))
    R(c, 'seed', lambda t: (0, 0, 10 * decay(t, 0.6, 3, 4)))
    c.sound(0.66, STEP, 0.5, 1.4)
    out.append(c)

    c = Clip(m, 'sigh', 1.6, 'once')
    s = lambda t: bump(t, 0.2, 1.4)  # noqa: E731
    R(c, 'h_head', lambda t: (-12 * s(t), 0, 0))
    R(c, 'left_arm', lambda t: (0, 0, -18 * s(t)))
    R(c, 'right_arm', lambda t: (0, 0, 18 * s(t)))
    S(c, 'body', lambda t: (0.02 * s(t), -0.04 * s(t), 0.02 * s(t)))
    out.append(c)

    c = Clip(m, 'dance', 2.4, 'once')
    k = lambda t: env(t, 0.1, 2.3, 0.2)  # noqa: E731
    P(c, 'body', lambda t: (0, 0.6 * abs(osc(t, 2.5)) * k(t), 0))
    R(c, 'body', lambda t: (0, 20 * osc(t, 1.25) * k(t), 6 * osc(t, 2.5) * k(t)))
    R(c, 'left_arm', lambda t: (0, 0, (40 + 20 * osc(t, 2.5)) * k(t)))
    R(c, 'right_arm', lambda t: (0, 0, (-40 + 20 * osc(t, 2.5)) * k(t)))
    R(c, 'left_leg', lambda t: (20 * max(0, osc(t, 1.25)) * k(t), 0, 0))
    R(c, 'right_leg', lambda t: (20 * max(0, -osc(t, 1.25)) * k(t), 0, 0))
    out.append(c)

    c = Clip(m, 'groom', 2.0, 'once')                # polishes the bud on its left branch with the right one
    k = lambda t: env(t, 0.15, 1.8, 0.3)  # noqa: E731
    R(c, 'left_arm', lambda t: (-20 * k(t), 0, 30 * k(t)))
    R(c, 'right_arm', lambda t: (-30 * k(t), -30 * k(t), -10 * k(t) + 12 * osc(t, 4) * env(t, 0.5, 1.5, 0.1)))
    R(c, 'h_head', lambda t: (-6 * k(t), -18 * k(t), 6 * k(t)))
    out.append(c)

    # ---------------------------------------------------------------- its own antics
    c = Clip(m, 'sun_turn', 3.6, 'once')             # turns its face up to the light, follows it across the sky
    up = lambda t: env(t, 0.3, 3.2, 0.6)  # noqa: E731
    R(c, 'h_head', lambda t: (24 * up(t), 30 * math.sin(TAU * (t - 0.3) / 5.8) * up(t), 0))
    R(c, 'body', lambda t: (6 * up(t), 0, 0))
    R(c, 'left_arm', lambda t: (0, 0, 22 * up(t)))
    R(c, 'right_arm', lambda t: (0, 0, -22 * up(t)))
    S(c, 'seed', lambda t: (0.15 * up(t),) * 3)
    c.sound(0.6, GROW, 0.35, 1.6)
    out.append(c)

    c = Clip(m, 'root_wiggle', 2.8, 'once')          # wiggles its roots into the ground, settles, pulls them out
    dig = lambda t: env(t, 0.2, 2.4, 0.4)  # noqa: E731
    P(c, 'body', lambda t: (0, -0.6 * dig(t), 0))
    R(c, 'left_leg', lambda t: (0, 0, 8 * osc(t, 4) * env(t, 0.3, 1.2, 0.15)))
    R(c, 'right_leg', lambda t: (0, 0, -8 * osc(t, 4, 0.25) * env(t, 0.3, 1.2, 0.15)))
    R(c, 'body', lambda t: (0, 0, 3 * osc(t, 2) * env(t, 0.3, 1.3, 0.2)))
    R(c, 'h_head', lambda t: (10 * env(t, 1.3, 2.3, 0.3), 0, 0))
    S(c, 'body', lambda t: (0.03 * env(t, 1.3, 2.3, 0.3), -0.03 * env(t, 1.3, 2.3, 0.3), 0.03 * env(t, 1.3, 2.3, 0.3)))
    c.particle(0.6, 'minecraft:white_ash', 5, 0.3, 0.01, 0.05)
    c.sound(0.4, 'minecraft:block.rooted_dirt.step', 0.4, 1.3)
    c.sound(2.45, 'minecraft:block.rooted_dirt.break', 0.3, 1.5)
    out.append(c)

    c = Clip(m, 'branch_reach', 2.4, 'once')         # reaches both branches up, one after the other, like growing
    a = lambda t: env(t, 0.2, 1.9, 0.4)  # noqa: E731
    b = lambda t: env(t, 0.5, 2.1, 0.4)  # noqa: E731
    R(c, 'left_arm', lambda t: (0, 0, 55 * a(t)))
    R(c, 'right_arm', lambda t: (0, 0, -55 * b(t)))
    P(c, 'body', lambda t: (0, 0.5 * a(t), 0))
    R(c, 'h_head', lambda t: (12 * a(t), 0, 4 * (a(t) - b(t))))
    c.sound(0.5, GROW, 0.3, 1.7)
    out.append(c)

    c = Clip(m, 'seed_glow', 2.6, 'once')            # the seed swells and rings; it crosses its eyes up at it
    g = lambda t: env(t, 0.3, 2.2, 0.5)  # noqa: E731
    S(c, 'seed', lambda t: ((0.4 + 0.08 * osc(t, 3)) * g(t),) * 3)
    R(c, 'h_head', lambda t: (18 * g(t), 0, 0))
    R(c, 'seed', lambda t: (0, 0, 6 * osc(t, 1.5) * g(t)))
    c.sound(0.5, CHIME, 0.45, 1.6)
    c.sound(1.3, CHIME, 0.35, 1.9)
    c.particle(1.0, 'minecraft:end_rod', 3, 0.15, 0.02, 1.15)
    out.append(c)
    return out
