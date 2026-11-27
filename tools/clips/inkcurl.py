"""Inkcurl (reef octopus, Creatures 2 ocean): every clip authored here (the source bbmodel has none).

Character: curious and shy. At rest its arms ripple one after another and the mantle breathes; it jets with a
squeeze-and-open of the whole body, crawls on its arm tips, forages with its front arms, peeks up tall at divers,
fiddles with its brass ring, and curls up small to rest. Startled (a hit, a brush), it squeezes and squirts ink.
Eyes close only in death."""
import math

from animkit import Clip, wave, pulse, step, keys, transition

ARMS = ('arm_fl', 'arm_fr', 'arm_ml', 'arm_mr', 'arm_bl', 'arm_br')
PHASE = {'arm_fl': 0.0, 'arm_mr': 0.17, 'arm_bl': 0.33, 'arm_fr': 0.5, 'arm_ml': 0.67, 'arm_br': 0.83}
SIDE = {a: (1 if a.endswith('l') else -1) for a in ARMS}


def hold(c, pose):
    for (b, ch), v in pose.items():
        c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)


def ripple(c, L, amp=6, tip=10, cycles=1, base=0.0, tip_base=0.0):
    """each arm lifts and curls a beat after its neighbour (a wave running round the body)"""
    for a in ARMS:
        ph = PHASE[a]
        c.rot(a, (lambda ph: lambda t: (base + amp * wave(t, L, cycles, ph), 0, 0))(ph))
        c.rot(a + '_tip', (lambda ph: lambda t: (tip_base + tip * wave(t, L, cycles, ph - 0.12), 0, 0))(ph))


CURL = {('body', 'position'): (0, -0.8, 0), ('mantle', 'rotation'): (-12, 0, 0), ('mantle', 'scale'): (1.05, 0.88, 1.05),
        ('h_head', 'rotation'): (-6, 0, 0)}
for _a in ARMS:
    CURL[(_a, 'rotation')] = (18, 0, 0)
    CURL[(_a + '_tip', 'rotation')] = (60, 0, 0)


def clips(m):
    out = []

    def put(c):
        m.put(c)
        out.append(c)
        return c

    L = 4.0
    c = Clip(m, 'idle', L, 'loop')
    c.scl('mantle', lambda t: (1 + 0.04 * wave(t, L, 2), 1 + 0.06 * wave(t, L, 2), 1 + 0.04 * wave(t, L, 2)))
    c.rot('mantle', lambda t: (3 * wave(t, L, 1, 0.3), 0, 2 * wave(t, L)))
    c.rot('h_head', lambda t: (2 * wave(t, L, 1, 0.1), 8 * wave(t, L, 1, 0.4), 0))
    c.pos('body', lambda t: (0, 0.15 * wave(t, L, 2), 0))
    ripple(c, L)
    put(c)

    # jet: squeeze (arms close up and in, mantle contracts), then open and glide
    L = 1.2
    c = Clip(m, 'swim', L, 'loop')
    sq = lambda t: pulse(t, 0.0, 0.5)
    c.scl('mantle', lambda t: (1 - 0.14 * sq(t), 1 + 0.08 * sq(t), 1 - 0.14 * sq(t)))
    c.rot('mantle', lambda t: (-20 + 6 * sq(t), 0, 0))
    c.rot('body', lambda t: (-8, 0, 0))
    c.pos('body', lambda t: (0, 0.8 + 0.4 * wave(t, L), 0))
    for a in ARMS:
        c.rot(a, lambda t: (-30 * sq(t) + 8 * pulse(t, 0.5, 1.2), 0, 0))
        c.rot(a + '_tip', lambda t: (-10 - 20 * pulse(t, 0.1, 0.7), 0, 0))
    put(c)

    # crawl on the floor: arms step one after another, the head sways
    L = 1.6
    c = Clip(m, 'crawl', L, 'loop')
    c.rot('h_head', lambda t: (0, 0, 3 * wave(t, L)))
    c.pos('body', lambda t: (0, 0.2 * abs(wave(t, L, 2)), 0))
    ripple(c, L, amp=-12, tip=14, cycles=1)
    c.rot('mantle', lambda t: (2 * wave(t, L, 2), 0, 3 * wave(t, L)))
    put(c)

    L = 2.0
    c = Clip(m, 'forage', L, 'loop')
    c.rot('h_head', lambda t: (-10 + 2 * wave(t, L), 6 * wave(t, L), 0))
    c.rot('mantle', lambda t: (6, 0, 2 * wave(t, L)))
    ripple(c, L, amp=3, tip=6)
    c.rot('arm_fl', lambda t: (-8 + 12 * abs(wave(t, L, 2)), 10 * wave(t, L), 0))
    c.rot('arm_fl_tip', lambda t: (-20 + 30 * abs(wave(t, L, 2, 0.1)), 0, 0))
    c.rot('arm_fr', lambda t: (-8 + 12 * abs(wave(t, L, 2, 0.25)), -10 * wave(t, L, 1, 0.3), 0))
    c.rot('arm_fr_tip', lambda t: (-20 + 30 * abs(wave(t, L, 2, 0.35)), 0, 0))
    put(c)

    L = 4.0
    c = Clip(m, 'curl', L, 'loop')
    hold(c, CURL)
    c.scl('mantle', lambda t: (1.05 + 0.03 * wave(t, L), 0.88 + 0.04 * wave(t, L), 1.05 + 0.03 * wave(t, L)))
    put(c)

    put(transition(m, 'lie_down', 'idle', 0, 'curl', 0, 1.0, 0.1))
    put(transition(m, 'stand_up', 'curl', 0, 'idle', 0, 0.9, 0.05))

    def squirt(name, L):
        c = Clip(m, name, L)
        sq = lambda t: pulse(t, 0.0, 0.45)
        c.scl('mantle', lambda t: (1 - 0.2 * sq(t) + 0.1 * pulse(t, 0.45, 0.9), 1 + 0.12 * sq(t), 1 - 0.2 * sq(t)))
        c.pos('body', lambda t: (0, 0.6 * pulse(t, 0.3, L), 1.5 * pulse(t, 0.35, L)))
        c.rot('h_head', lambda t: (-8 * sq(t), 0, 0))
        for a in ARMS:
            c.rot(a, lambda t: (-28 * sq(t), 0, 0))
            c.rot(a + '_tip', lambda t: (-20 * sq(t) + 10 * pulse(t, 0.4, L), 0, 0))
        c.particle(0.35, 'minecraft:squid_ink', 10, 0.35, 0.04, 0.4)
        return put(c)

    squirt('harvest', 1.2)
    squirt('ink_jet', 1.0)

    c = Clip(m, 'hurt', 0.4)
    c.scl('mantle', lambda t: (1 - 0.1 * pulse(t, 0, 0.4), 1 + 0.08 * pulse(t, 0, 0.4), 1 - 0.1 * pulse(t, 0, 0.4)))
    c.rot('h_head', lambda t: (-8 * pulse(t, 0, 0.4), 0, 0))
    put(c)

    L = 1.8
    c = Clip(m, 'death', L, 'hold')
    c.scl('mantle', lambda t: (1 + 0.1 * step(t, 0.2, 1.4), 1 - 0.3 * step(t, 0.2, 1.4), 1 + 0.1 * step(t, 0.2, 1.4)))
    c.rot('mantle', lambda t: (-20 * step(t, 0.2, 1.4), 0, 0))
    c.pos('body', lambda t: (0, -0.8 * step(t, 0.2, 1.2), 0))
    for a in ARMS:
        c.rot(a, lambda t: (6 * step(t, 0.2, 1.2), 0, 0))
        c.rot(a + '_tip', lambda t: (-40 * step(t, 0.2, 1.2), 0, 0))
    c.scl('h_eyes', lambda t: (1, 1 - 0.94 * step(t, 0.3, 0.9), 1))
    put(c)

    L = 3.0
    c = Clip(m, 'look_around', L)
    c.copy('idle')
    c.rot('h_head', lambda t: (4 * pulse(t, 0, L), keys(t, [(0, 0), (0.6, 30), (1.3, 30), (2.0, -28), (2.6, -28), (3.0, 0)]), 0))
    put(c)

    # peeks up tall on its arms, head tilted
    L = 2.2
    c = Clip(m, 'peek', L)
    up = lambda t: step(t, 0, 0.5) * (1 - step(t, 1.7, 2.2))
    c.pos('body', lambda t: (0, 2.0 * up(t), 0))
    c.rot('h_head', lambda t: (8 * up(t), 0, 14 * pulse(t, 0.6, 1.6)))
    for a in ARMS:
        c.rot(a, lambda t: (-30 * up(t), 0, 0))
        c.rot(a + '_tip', lambda t: (-34 * up(t), 0, 0))
    put(c)

    # turns its brass ring over in the front arm's curled tip
    L = 3.0
    c = Clip(m, 'ring_fiddle', L)
    c.copy('idle')
    lift = lambda t: step(t, 0.1, 0.7) * (1 - step(t, 2.3, 2.9))
    c.rot('arm_fl', lambda t: (-30 * lift(t), 0, 0))
    c.rot('arm_fl_tip', lambda t: (-10 * lift(t), 0, 40 * math.sin(2 * math.pi * t / 1.0) * lift(t)))
    c.rot('h_head', lambda t: (-8 * lift(t), 18 * lift(t), 0))
    put(c)

    L = 2.0
    c = Clip(m, 'arm_wave', L)
    c.copy('idle')
    ripple(c, L, amp=12, tip=22, cycles=2)
    put(c)
    return out


TAGS = {
    'curl': {'p': 'lying'}, 'crawl': {'move': True, 'gait': 'walk'},
    'forage': {'p': 'standing'}, 'peek': {'move': False},
}
IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 5, 'min': 5, 'max': 12},
        {'clip': 'look_around', 'w': 2, 'cd': 12},
        {'clip': 'arm_wave', 'w': 2, 'cd': 10},
        {'clip': 'ring_fiddle', 'w': 1.5, 'cd': 25},
        {'clip': 'peek', 'w': 1, 'cd': 20, 'boost': {'player_near': 3}},
    ],
    'forage': [
        {'clip': '@mood', 'w': 4, 'min': 3, 'max': 6},
        {'clip': 'arm_wave', 'w': 1, 'cd': 10},
    ],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 25},
    ],
    'notice': [
        {'clip': 'peek', 'w': 3},
        {'clip': 'look_around', 'w': 1},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 4, 'max': 10},
        {'clip': 'crawl', 'w': 2, 'min': 4, 'max': 10},
    ],
}
PROCEDURAL = {'tail_sway': 0, 'breath': 0.0}
