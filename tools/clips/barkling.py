"""Barkling (pale-oak bark sprite with hanging moss, twig antlers and a glowing resin heart): a little tree.
Rooting stance, leaf rustles, reaching for the sun, twig scratching, swaying in the breeze. Phase 2."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403

ARMS = ('left_arm', 'right_arm')
LEGS = ('left_leg', 'right_leg')
LEAVES = 'minecraft:pale_oak_leaves'


def arms_out(c, fn, lift=0.0):
    """Arms out sideways like branches (left z+, right z- as its own root_call has them)."""
    c.rot('left_arm', lambda t: (lift * fn(t), 0, fn(t)), add=True)
    c.rot('right_arm', lambda t: (lift * fn(t), 0, -fn(t)), add=True)


def twigs(c, fn):
    """Twig antlers spread (fn > 0) or fold (left z-, right z+ spreads)."""
    c.rot('h_left_twig', lambda t: (0, 0, -fn(t)), add=True)
    c.rot('h_right_twig', lambda t: (0, 0, fn(t)), add=True)


def glow(c, fn):
    c.scl('resin', lambda t: tuple([1 + fn(t)] * 3), add=True)


def clips(m):
    out = []

    # --- rooting stance (standing loop): feet planted wide, sinks into its roots, branches out, breathing slow
    L = 6.0
    c = Clip(m, 'idle_rooted', L, 'loop')
    loop_base(c, m)
    for i, leg in enumerate(LEGS):
        c.rot(leg, (lambda s: lambda t: (0, 0, s * 10))((-1) ** (i + 1) * -1), add=True)
    c.pos('body', lambda t: (0, -0.8, 0), add=True)
    for leg in LEGS:
        c.pos(leg, lambda t: (0, 0.8, 0), add=True)
    arms_out(c, lambda t: 28 + 6 * wave(t, L, 2), 0.2)
    twigs(c, lambda t: 6 * wave(t, L, 2, 0.2))
    c.rot('h_head', lambda t: (4 * wave(t, L, 1), 10 * wave(t, L, 1, 0.25), 0), add=True)
    c.rot('tail', lambda t: (3 * wave(t, L, 2, 0.1), 0, 4 * wave(t, L, 1)), add=True)
    glow(c, lambda t: 0.12 * (0.5 + 0.5 * wave(t, L, 2)))
    c.sound(0.2, 'minecraft:block.rooted_dirt.place', 0.3, 0.9)
    out.append(keep(m, c))

    # --- leaf rustle: shakes itself like a sapling in a gust; moss swings, twigs quiver, leaves drift off
    L = 1.8
    c = one_shot(m, 'leaf_rustle', L)
    rus = lambda t: pulse(t, 0.1, 1.5)
    c.rot('body', lambda t: (0, 5 * math.sin(2 * math.pi * 9 * t / 1.0) * rus(t), 4 * math.sin(2 * math.pi * 7 * t / 1.0) * rus(t)), add=True)
    c.rot('h_head', lambda t: (0, 0, 6 * math.sin(2 * math.pi * 8 * t / 1.0 + 0.7) * rus(t)), add=True)
    for b, s in (('h_left_twig', 1), ('h_right_twig', -1)):
        c.rot(b, (lambda s: lambda t: (6 * math.sin(2 * math.pi * 11 * t / 1.0) * rus(t), 0, s * 12 * math.sin(2 * math.pi * 10 * t / 1.0) * rus(t)))(s), add=True)
    c.rot('tail', lambda t: (10 * math.sin(2 * math.pi * 5 * t / 1.0) * rus(t), 0, 8 * math.sin(2 * math.pi * 4 * t / 1.0) * rus(t)), add=True)
    arms_out(c, lambda t: 10 * rus(t) + 8 * math.sin(2 * math.pi * 9 * t / 1.0) * rus(t))
    c.sound(0.15, 'minecraft:block.azalea_leaves.break', 0.5, 1.2)
    c.sound(0.7, 'minecraft:block.azalea_leaves.step', 0.6, 1.0)
    c.particle(0.4, LEAVES, 5, 0.5, 0.02, 1.0)
    out.append(keep(m, c))

    # --- reach for the sun: arms stretch up like branches, head tilts back, twigs spread, the resin glows
    L = 4.0
    c = one_shot(m, 'sun_reach', L)
    up = lambda t: keys(t, [(0, 0), (0.4, 0), (1.3, 1), (2.9, 1), (3.6, 0), (4.0, 0)])
    for i, arm in enumerate(ARMS):
        c.rot(arm, (lambda s: lambda t: (165 * up(t), 0, s * 20 * up(t) + s * 6 * wave(t, L, 3) * up(t)))((-1) ** i), add=True)
    c.rot('h_head', lambda t: (22 * up(t), 0, 0), add=True)
    c.rot('body', lambda t: (6 * up(t), 0, 3 * wave(t, L, 2) * up(t)), add=True)
    c.pos('body', lambda t: (0, 0.6 * up(t), 0), add=True)
    for leg in LEGS:
        c.pos(leg, lambda t: (0, -0.6 * up(t), 0), add=True)
    twigs(c, lambda t: 12 * up(t))
    glow(c, lambda t: 0.3 * up(t))
    c.sound(1.2, 'minecraft:entity.creaking.twitch', 0.4, 1.6)
    c.sound(3.4, 'minecraft:block.wood.step', 0.4, 1.3)
    out.append(keep(m, c))

    # --- twig scratch: reaches up and scratches between its twig antlers
    L = 2.4
    c = one_shot(m, 'twig_scratch', L)
    sc = lambda t: keys(t, [(0, 0), (0.4, 1), (1.9, 1), (2.4, 0)])
    c.rot('left_arm', lambda t: (150 * sc(t), 0, -25 * sc(t) + 8 * math.sin(2 * math.pi * 10 * t / 1.5) * pulse(t, 0.4, 1.9)), add=True)
    c.rot('h_head', lambda t: (-8 * sc(t), 0, -14 * sc(t)), add=True)
    c.rot('h_left_twig', lambda t: (0, 0, 8 * math.sin(2 * math.pi * 10 * t / 1.5) * pulse(t, 0.4, 1.9)), add=True)
    c.rot('body', lambda t: (0, 0, 5 * sc(t)), add=True)
    c.sound(0.7, 'minecraft:block.wood.hit', 0.3, 1.6)
    c.sound(1.3, 'minecraft:block.wood.hit', 0.3, 1.7)
    out.append(keep(m, c))

    # --- breeze sway (standing loop): sways from the ankles like a sapling, moss trailing
    L = 6.0
    c = Clip(m, 'idle_breeze_sway', L, 'loop')
    loop_base(c, m)
    c.rot('body', lambda t: (3 * wave(t, L, 2, 0.25), 0, 7 * wave(t, L, 1)), add=True)
    c.rot('h_head', lambda t: (0, 8 * wave(t, L, 1, 0.3), -5 * wave(t, L, 1, -0.06)), add=True)
    for leg in LEGS:
        c.rot(leg, lambda t: (0, 0, -7 * wave(t, L, 1)), add=True)
    c.rot('tail', lambda t: (0, 0, -9 * wave(t, L, 1, -0.1)), add=True)
    arms_out(c, lambda t: 8 + 6 * wave(t, L, 1, -0.08))
    twigs(c, lambda t: 5 * wave(t, L, 2, -0.1))
    c.sound(1.4, 'minecraft:block.azalea_leaves.step', 0.25, 1.1)
    out.append(keep(m, c))

    # --- sitting loops
    L = 6.0
    c = sit_loop(m, 'sit_breeze', L)
    c.rot('body', lambda t: (0, 0, 4 * wave(t, L, 1)), add=True)
    c.rot('h_head', lambda t: (4 * wave(t, L, 2), 12 * wave(t, L, 1, 0.25), -4 * wave(t, L, 1, -0.06)), add=True)
    twigs(c, lambda t: 5 * wave(t, L, 2))
    c.rot('tail', lambda t: (0, 0, -6 * wave(t, L, 1, -0.1)), add=True)
    glow(c, lambda t: 0.08 * (0.5 + 0.5 * wave(t, L, 2)))
    out.append(keep(m, c))

    L = 5.0
    c = sit_loop(m, 'sit_moss_groom', L)
    g = lambda t: pulse(t, 0.5, 3.5)
    c.rot('right_arm', lambda t: (60 * g(t), 0, 30 * g(t) + 10 * math.sin(2 * math.pi * 6 * t / L) * g(t)), add=True)
    c.rot('h_head', lambda t: (-12 * g(t), -25 * g(t), 0), add=True)
    c.rot('tail', lambda t: (6 * math.sin(2 * math.pi * 6 * t / L) * g(t), 0, 0), add=True)
    out.append(keep(m, c))

    # --- lying loops
    L = 7.0
    c = sleep_loop(m, 'sleep_resin_glow', L)
    glow(c, lambda t: 0.2 * (0.5 + 0.5 * wave(t, L, 2)))
    c.rot('h_left_twig', lambda t: (0, 0, 3 * wave(t, L, 1)), add=True)
    c.rot('h_right_twig', lambda t: (0, 0, -3 * wave(t, L, 1)), add=True)
    out.append(keep(m, c))

    L = 8.0
    c = sleep_loop(m, 'sleep_creak', L)
    c.rot('body', lambda t: (0, 0, 3 * pulse(t, 2.0, 5.0)), add=True)
    c.rot('h_head', lambda t: (0, 0, 5 * pulse(t, 2.0, 5.0)), add=True)
    c.rot('tail', lambda t: (0, 0, 5 * pulse(t, 2.5, 5.5)), add=True)
    c.sound(2.2, 'minecraft:block.creaking_heart.idle', 0.2, 1.3)
    out.append(keep(m, c))

    # --- reactions
    L = 2.0   # petting: rustles happily, twigs perk up, the resin heart glows
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (12 * e(t), 0, 14 * e(t)), add=True)
    twigs(c, lambda t: 14 * e(t))
    glow(c, lambda t: 0.35 * e(t))
    c.rot('body', lambda t: (0, 3 * math.sin(2 * math.pi * 8 * t / 1.0) * pulse(t, 0.3, 1.4), 0), add=True)
    c.rot('tail', lambda t: (6 * math.sin(2 * math.pi * 5 * t / 1.0) * e(t), 0, 0), add=True)
    arms_out(c, lambda t: 18 * e(t))
    c.particle(0.5, LEAVES, 3, 0.4, 0.02, 1.0)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'h_left_twig', 'h_right_twig', 'resin')})
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (10 * e(t), 0, 12 * e(t)), add=True)
    twigs(c, lambda t: 12 * e(t))
    glow(c, lambda t: 0.3 * e(t))
    out.append(keep(m, c))

    L = 3.0   # bond level-up: stomps its roots down, arms flung up, the resin flares, a shower of pale leaves
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.35, 0.85, 5, 'body')
    arms_out(c, lambda t: 40 * pulse(t, 0.3, 2.8), 3.5)
    twigs(c, lambda t: 18 * pulse(t, 0.3, 2.8))
    glow(c, lambda t: 0.6 * pulse(t, 0.8, 2.6))
    c.rot('h_head', lambda t: (20 * pulse(t, 0.8, 2.4), 0, 0), add=True)
    shake_body(c, 'body', 1.0, 1.8, 6, 6, axis=1)
    c.sound(0.85, 'minecraft:block.rooted_dirt.break', 0.8, 0.8)
    c.sound(1.0, 'minecraft:entity.creaking.activate', 0.4, 1.6)
    c.particle(0.9, LEAVES, 12, 0.8, 0.03, 1.0)
    c.particle(1.2, 'minecraft:happy_villager', 6, 0.6, 0.02, 1.1)
    out.append(keep(m, c))
    return out


TAGS = {
    'leaf_rustle': {'layer': 'base', 'move': False}, 'sun_reach': {'layer': 'base', 'move': False},
    'twig_scratch': {'layer': 'base', 'move': False}, 'idle_rooted': {'p': 'standing'}, 'idle_breeze_sway': {'p': 'standing'},
    'sit_breeze': {'p': 'sitting'}, 'sit_moss_groom': {'p': 'sitting'},
    'sleep_resin_glow': {'p': 'lying'}, 'sleep_creak': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False},
}

IDLE = {
    'standing': [
        idle_entry('idle_rooted', 3, mn=8, mx=16, cd=15, boost={'night': 2}),
        idle_entry('idle_breeze_sway', 3, mn=6, mx=12, cd=12, boost={'rain': 2}),
        idle_entry('leaf_rustle', 2, cd=20, boost={'rain': 2}),
        idle_entry('sun_reach', 2, cd=35, when='day,!rain'),
        idle_entry('twig_scratch', 1, cd=30),
    ],
    'sitting': [
        idle_entry('sit_breeze', 3, mn=6, mx=14, cd=10),
        idle_entry('sit_moss_groom', 2, mn=5, mx=5, cd=20),
    ],
    'lying': [
        idle_entry('sleep_resin_glow', 2, mn=20, mx=50),
        idle_entry('sleep_creak', 1, mn=8, mx=16, cd=30),
    ],
}

# the "tail" is a hanging moss curtain: a gentle sway only
PROCEDURAL = {'tail_sway': 2}
