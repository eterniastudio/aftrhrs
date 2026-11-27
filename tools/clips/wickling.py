"""Wickling (chubby candle-newt, a living lantern): the flame is its heart. Flicker sways, wax drips, shielding the
flame from the wind with its tail, flares of joy, lizard head-bobs. Phase 2."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403

LEGS_F = ('left_front_leg', 'right_front_leg')
LEGS_B = ('left_back_leg', 'right_back_leg')
LEGS = LEGS_F + LEGS_B


def flicker(c, amp=1.0, env_fn=lambda t: 1.0, size=0.0):
    """A living flame: two incommensurate-looking (but loop-safe) sways and a stretching pulse."""
    L = c.L
    k = max(1, int(round(L / 0.75)))
    if c.loop != 'loop':
        env0 = env_fn
        env_fn = lambda t: env0(t) * ramp(t, L, 0.25)
    c.rot('flame', lambda t: (7 * amp * wave(t, L, k) * env_fn(t), 0, 9 * amp * wave(t, L, k + 1, 0.3) * env_fn(t)), add=True)
    c.scl('flame', lambda t: (1 + (size - 0.06 * amp * wave(t, L, 2 * k + 1)) * env_fn(t),
                              1 + (size + 0.14 * amp * wave(t, L, 2 * k + 1, 0.2)) * env_fn(t),
                              1 + (size - 0.06 * amp * wave(t, L, 2 * k + 1)) * env_fn(t)), add=True)


def flare(c, t0, t1, big):
    """The flame swells to `big` between t0 and t1 (eased in, a wobble, eased out)."""
    c.scl('flame', lambda t: (1 + 0.6 * big * pulse(t, t0, t1), 1 + big * pulse(t, t0, t1), 1 + 0.6 * big * pulse(t, t0, t1)), add=True)


def clips(m):
    out = []

    # --- flame flicker sway (standing loop): the whole candle sways with its flame, it watches the light dance
    L = 6.0
    c = Clip(m, 'idle_flicker', L, 'loop')
    loop_base(c, m)
    flicker(c, 1.8)
    c.rot('candle', lambda t: (5 * wave(t, L, 2), 0, 6 * wave(t, L, 3, 0.2)), add=True)
    c.rot('body', lambda t: (0, 0, 2.5 * wave(t, L, 2)), add=True)
    c.rot('h_head', lambda t: (8 * wave(t, L, 1, 0.25), 18 * wave(t, L, 1), 6 * wave(t, L, 2)), add=True)
    tail_wag(c, ['tail', 'tail_tip'], 8, 2)
    c.sound(1.0, 'minecraft:block.candle.ambient', 0.5, 1.3)
    c.sound(4.0, 'minecraft:block.candle.ambient', 0.5, 1.1)
    out.append(keep(m, c))

    # --- wax drip: it melts a little (sags, legs splay, candle droops, a drip), then perks back up with a shiver
    L = 3.4
    c = one_shot(m, 'wax_drip', L)
    sag = lambda t: keys(t, [(0, 0), (0.3, 0), (1.5, 1), (2.2, 1), (2.6, -0.15), (3.0, 0)])
    c.pos('body', lambda t: (0, -1.4 * sag(t), 0), add=True)
    c.scl('body', lambda t: (1 + 0.08 * sag(t), 1 - 0.1 * sag(t), 1 + 0.05 * sag(t)), add=True)
    c.rot('h_head', lambda t: (-14 * sag(t), 0, 10 * sag(t)), add=True)
    c.rot('candle', lambda t: (-12 * sag(t), 0, 9 * sag(t)), add=True)
    for leg, s in zip(LEGS, (-1, 1, -1, 1)):
        c.rot(leg, (lambda s: lambda t: (0, 0, s * 22 * sag(t)))(s), add=True)
        c.pos(leg, lambda t: (0, 1.2 * sag(t), 0), add=True)
    c.rot('tail', lambda t: (8 * sag(t), 0, 0), add=True)
    c.scl('flame', lambda t: (1, 1 - 0.3 * sag(t) + 0.5 * pulse(t, 2.3, 3.1), 1), add=True)
    flicker(c, 1.0)
    shake_body(c, 'body', 2.3, 3.0, 9, 3)
    c.sound(1.6, 'minecraft:block.pointed_dripstone.drip_lava', 0.5, 1.4)
    c.sound(2.35, 'minecraft:item.honeycomb.wax_on', 0.5, 1.4)
    out.append(keep(m, c))

    # --- shield the flame: a gust, the flame leans and shrinks, the tail curls up and over it, head turned back
    L = 3.6
    c = one_shot(m, 'shield_flame', L)
    cup = lambda t: keys(t, [(0, 0), (0.5, 0), (1.0, 1), (2.6, 1), (3.2, 0), (3.6, 0)])
    gust = lambda t: keys(t, [(0, 0), (0.2, 1), (0.9, 0.7), (1.4, 0.15), (2.6, 0.1), (3.0, 0)])
    c.rot('flame', lambda t: (-16 * gust(t), 0, 26 * gust(t)), add=True)
    c.scl('flame', lambda t: (1 - 0.25 * gust(t), 1 - 0.35 * gust(t) + 0.35 * pulse(t, 2.7, 3.5), 1 - 0.25 * gust(t)), add=True)
    c.rot('candle', lambda t: (0, 0, 7 * gust(t)), add=True)
    c.rot('tail', lambda t: (-58 * cup(t), 12 * cup(t), 0), add=True)
    c.rot('tail_tip', lambda t: (-72 * cup(t), 10 * cup(t), 0), add=True)
    c.rot('h_head', lambda t: (-4 * gust(t) + 6 * cup(t), 70 * cup(t), -10 * cup(t)), add=True)
    c.rot('body', lambda t: (0, 10 * cup(t), -5 * gust(t)), add=True)
    c.pos('body', lambda t: (0, -0.4 * cup(t), 0), add=True)
    for leg in LEGS_B:
        c.rot(leg, lambda t: (10 * cup(t), 0, 0), add=True)
    flicker(c, 0.7)
    c.sound(0.15, 'minecraft:block.candle.extinguish', 0.25, 1.7)
    c.sound(2.8, 'minecraft:block.candle.ambient', 0.9, 1.3)
    out.append(keep(m, c))

    # --- flare: it puffs itself up, the flame sputters small then whooshes tall, a proud little hop
    L = 2.0
    c = one_shot(m, 'flame_flare', L)
    c.scl('flame', lambda t: (1 - 0.35 * pulse(t, 0.1, 0.6) + 0.5 * pulse(t, 0.6, 1.7),
                              1 - 0.45 * pulse(t, 0.1, 0.6) + 1.0 * pulse(t, 0.6, 1.7),
                              1 - 0.35 * pulse(t, 0.1, 0.6) + 0.5 * pulse(t, 0.6, 1.7)), add=True)
    c.scl('body', lambda t: (1 + 0.08 * pulse(t, 0.1, 0.65), 1 + 0.06 * pulse(t, 0.1, 0.65), 1 + 0.08 * pulse(t, 0.1, 0.65)), add=True)
    hop(c, 'root', 0.7, 1.05, 2.5, 'body')
    c.rot('h_head', lambda t: (18 * pulse(t, 0.5, 1.6), 0, 0), add=True)
    c.rot('candle', lambda t: (6 * decay(t, 1.05, 2.5, 4), 0, 0), add=True)
    tail_wag(c, ['tail', 'tail_tip'], 16, 5, env_fn=lambda t: pulse(t, 0.5, 1.9))
    flicker(c, 1.2)
    c.sound(0.62, 'minecraft:item.firecharge.use', 0.25, 1.9)
    out.append(keep(m, c))

    # --- lizard bob: newt push-up display, head bobbing, throat proud
    L = 2.2
    c = one_shot(m, 'lizard_bob', L)
    bobs = [0.35, 0.75, 1.1, 1.45]
    up = lambda t: sum(pulse(t, b - 0.18, b + 0.18) for b in bobs)
    c.pos('body', lambda t: (0, 1.3 * up(t), 0), add=True)
    for leg in LEGS_F:
        c.pos(leg, lambda t: (0, -1.3 * up(t), 0), add=True)
        c.rot(leg, lambda t: (-10 * up(t), 0, 0), add=True)
    c.rot('body', lambda t: (8 * up(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (10 * up(t) + 8 * ramp(t, L, 0.3), 0, 0), add=True)
    c.rot('tail', lambda t: (0, 12 * wave(t, L, 3) * ramp(t, L, 0.3), 0), add=True)
    flicker(c, 1.3)
    out.append(keep(m, c))

    # --- sitting loops
    L = 6.0
    c = sit_loop(m, 'sit_flame_watch', L)
    flicker(c, 1.6)
    c.rot('h_head', lambda t: (10 * pulse(t, 1.0, 3.2), 55 * pulse(t, 1.0, 3.2), -8 * pulse(t, 1.0, 3.2)), add=True)
    c.rot('tail', lambda t: (-30 * pulse(t, 0.8, 3.6), 0, 0), add=True)
    c.rot('tail_tip', lambda t: (-30 * pulse(t, 1.0, 3.6), 10 * wave(t, L, 3), 0), add=True)
    c.rot('candle', lambda t: (3 * wave(t, L, 2), 0, 4 * wave(t, L, 1)), add=True)
    out.append(keep(m, c))

    L = 6.0
    c = sit_loop(m, 'sit_wax_melt', L)
    sag = lambda t: 0.5 - 0.5 * math.cos(2 * math.pi * t / L)
    c.pos('body', lambda t: (0, -0.7 * sag(t), 0), add=True)
    c.scl('body', lambda t: (1 + 0.05 * sag(t), 1 - 0.06 * sag(t), 1), add=True)
    c.rot('h_head', lambda t: (-10 * sag(t), 0, 8 * sag(t)), add=True)
    c.rot('candle', lambda t: (-6 * sag(t), 0, 5 * sag(t)), add=True)
    flicker(c, 0.8, size=0.0)
    c.scl('flame', lambda t: (1, 1 - 0.2 * sag(t), 1), add=True)
    out.append(keep(m, c))

    # --- lying loops: the flame burns low and breathes with it
    L = 6.0
    c = sleep_loop(m, 'sleep_ember', L)
    c.scl('flame', lambda t: (1 + 0.1 * wave(t, L, 2), 1 + 0.2 * wave(t, L, 2), 1 + 0.1 * wave(t, L, 2)), add=True)
    c.rot('flame', lambda t: (0, 0, 5 * wave(t, L, 3)), add=True)
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L, 2), 1 + 0.03 * wave(t, L, 2), 1), add=True)
    c.sound(2.0, 'minecraft:block.candle.ambient', 0.25, 0.8)
    out.append(keep(m, c))

    L = 8.0
    c = sleep_loop(m, 'sleep_tail_curl', L)
    curl = lambda t: pulse(t, 1.0, 6.5)
    c.rot('tail', lambda t: (0, 25 * curl(t), 0), add=True)
    c.rot('tail_tip', lambda t: (0, 35 * curl(t) + 8 * wave(t, L, 4) * curl(t), 0), add=True)
    c.rot('h_head', lambda t: (0, -8 * curl(t), 4 * curl(t)), add=True)
    c.scl('flame', lambda t: (1, 1 + 0.12 * wave(t, L, 4), 1), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 1.9   # petting: leans into the hand, flame glows up big and warm, tail wiggles
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (18 * e(t), 0, 16 * e(t)), add=True)
    c.rot('body', lambda t: (4 * e(t), 0, 8 * e(t)), add=True)
    c.rot('candle', lambda t: (0, 0, -6 * e(t)), add=True)
    flare(c, 0.15, 1.7, 0.6)
    flicker(c, 1.2)
    tail_wag(c, ['tail', 'tail_tip'], 22, 6, env_fn=e)
    out.append(keep(m, c))

    L = 1.6   # pet while sitting: overlay (head, candle, flame, tail)
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'candle', 'tail', 'tail_tip')})
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (16 * e(t), 0, 12 * e(t)), add=True)
    flare(c, 0.1, 1.5, 0.5)
    c.rot('tail', lambda t: (0, 18 * wave(t, L, 5) * e(t), 0), add=True)
    out.append(keep(m, c))

    L = 2.6   # bond level-up: the flame roars into a pillar, a twirling hop, a spark-shower shimmy
    c = one_shot(m, 'level_up', L)
    flare(c, 0.1, 2.4, 1.6)
    hop(c, 'root', 0.45, 1.0, 5, 'body')
    c.rot('root', lambda t: (0, -360 * step(t, 0.45, 1.0), 0), add=True)
    c.rot('h_head', lambda t: (22 * pulse(t, 0.3, 1.2), 0, 0), add=True)
    shake_body(c, 'body', 1.15, 2.1, 10, 3)
    tail_wag(c, ['tail', 'tail_tip'], 24, 8, env_fn=lambda t: ramp(t, L, 0.3))
    flicker(c, 1.5)
    c.sound(0.4, 'minecraft:item.flintandsteel.use', 0.6, 1.3)
    c.sound(0.5, 'minecraft:item.firecharge.use', 0.35, 1.6)
    out.append(keep(m, c))
    return out


TAGS = {
    'wax_drip': {'layer': 'base', 'move': False}, 'shield_flame': {'layer': 'base', 'move': False},
    'flame_flare': {'layer': 'base', 'move': False}, 'lizard_bob': {'layer': 'base', 'move': False},
    'idle_flicker': {'p': 'standing'},
    'sit_flame_watch': {'p': 'sitting'}, 'sit_wax_melt': {'p': 'sitting'},
    'sleep_ember': {'p': 'lying'}, 'sleep_tail_curl': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False},
}

IDLE = {
    'standing': [
        idle_entry('idle_flicker', 3, mn=6, mx=14, cd=16),
        idle_entry('wax_drip', 1, cd=40, boost={'hot': 3}),
        idle_entry('shield_flame', 2, cd=35, boost={'rain': 4, 'thunder': 4}),
        idle_entry('flame_flare', 1, cd=30, boost={'owner_near': 2, 'night': 1.5}),
        idle_entry('lizard_bob', 2, cd=25),
    ],
    'sitting': [
        idle_entry('sit_flame_watch', 3, mn=6, mx=12, cd=12),
        idle_entry('sit_wax_melt', 2, mn=6, mx=12, cd=20, boost={'night': 2}),
    ],
    'lying': [
        idle_entry('sleep_ember', 2, mn=20, mx=50),
        idle_entry('sleep_tail_curl', 1, mn=8, mx=16, cd=30),
    ],
}

PROCEDURAL = {'tail': ['tail', 'tail_tip'], 'tail_sway': 6, 'breath': 0.014}
