"""Sporeling (mushroom critter with a big red cap and glowing spots): jelly cap wobbles, spore puffs with real
spores, cap bobbing, polite cap tips, rooting its toes, rain basking. Phase 2 (cap_wobble / spore_puff were tiny:
re-authored bigger)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403

ARMS = ('left_arm', 'right_arm')
LEGS = ('left_leg', 'right_leg')
SPORES = 'minecraft:spore_blossom_air'


def arms_out(c, fn, lift=0.0):
    """Arms swing out sideways (left z+, right z- as its own spore_puff has them) by fn(t) degrees, lift = forward x."""
    c.rot('left_arm', lambda t: (lift * fn(t) / 40.0, 0, fn(t)), add=True)
    c.rot('right_arm', lambda t: (lift * fn(t) / 40.0, 0, -fn(t)), add=True)


def clips(m):
    out = []

    # --- cap wobble: something jostles it; the cap wobbles like jelly and settles, arms out for balance
    L = 2.0
    c = one_shot(m, 'cap_wobble', L)
    c.rot('h_cap', lambda t: (11 * decay(t, 0.15, 2.6, 2.6), 0, 16 * decay(t, 0.1, 2.2, 2.4)), add=True)
    c.rot('h_head', lambda t: (0, 0, -5 * decay(t, 0.1, 2.2, 3.0)), add=True)
    c.scl('h_cap', lambda t: (1 + 0.08 * decay(t, 0.1, 3.0, 3.0), 1 - 0.08 * decay(t, 0.1, 3.0, 3.0), 1 + 0.08 * decay(t, 0.1, 3.0, 3.0)), add=True)
    arms_out(c, lambda t: 30 * pulse(t, 0.1, 1.4) * (1 + 0.3 * math.sin(2 * math.pi * 3 * t)))
    c.rot('body', lambda t: (0, 0, -4 * decay(t, 0.15, 2.2, 3.0)), add=True)
    c.sound(0.12, 'minecraft:entity.slime.squish_small', 0.5, 1.6)
    out.append(keep(m, c))

    # --- spore puff: squats, the cap pumps up and POOF, a cloud of spores, arms flung up
    L = 2.0
    c = one_shot(m, 'spore_puff', L)
    pump = lambda t: keys(t, [(0, 0), (0.25, 0), (0.7, 1), (0.8, -0.3), (1.05, 0.1), (1.3, 0), (2.0, 0)])
    c.scl('h_cap', lambda t: (1 + 0.18 * pump(t), 1 + 0.12 * pump(t), 1 + 0.18 * pump(t)), add=True)
    c.scl('body', lambda t: (1 + 0.1 * pulse(t, 0.2, 0.75), 1 - 0.12 * pulse(t, 0.2, 0.75), 1 + 0.1 * pulse(t, 0.2, 0.75)), add=True)
    c.pos('body', lambda t: (0, -0.6 * pulse(t, 0.2, 0.75) + 0.8 * pulse(t, 0.7, 1.1), 0), add=True)
    arms_out(c, lambda t: 60 * pulse(t, 0.65, 1.6), lift=25)
    c.rot('h_head', lambda t: (-8 * pulse(t, 0.2, 0.7) + 12 * pulse(t, 0.7, 1.5), 0, 0), add=True)
    c.rot('h_cap', lambda t: (6 * decay(t, 0.78, 3, 3), 0, 0), add=True)
    c.scl('glow_spots', lambda t: (1 + 0.4 * pulse(t, 0.6, 1.2), 1, 1 + 0.4 * pulse(t, 0.6, 1.2)), add=True)
    c.sound(0.78, 'minecraft:entity.puffer_fish.blow_out', 0.5, 1.7)
    c.particle(0.8, SPORES, 14, 0.6, 0.03, 1.1)
    c.particle(0.95, SPORES, 8, 0.9, 0.02, 1.0)
    out.append(keep(m, c))

    # --- cap bob (standing loop): bobs its whole cap to a beat only it can hear, swaying side to side
    L = 4.0
    c = Clip(m, 'idle_cap_bob', L, 'loop')
    loop_base(c, m)
    beat = lambda t: abs(math.sin(2 * math.pi * 4 * t / L))
    c.rot('h_head', lambda t: (-8 * beat(t), 0, 8 * wave(t, L, 2)), add=True)
    c.rot('h_cap', lambda t: (6 * math.sin(2 * math.pi * 4 * t / L - 0.6), 0, -5 * wave(t, L, 2, -0.08)), add=True)
    c.pos('body', lambda t: (0, -0.4 * beat(t), 0), add=True)
    c.rot('body', lambda t: (0, 0, 5 * wave(t, L, 2)), add=True)
    for i, arm in enumerate(ARMS):
        c.rot(arm, (lambda i: lambda t: (18 * wave(t, L, 2, 0.5 * i), 0, 0))(i), add=True)
    for i, leg in enumerate(LEGS):
        c.pos(leg, (lambda i: lambda t: (0, 0.5 * max(0.0, wave(t, L, 2, 0.5 * i)), 0))(i), add=True)
    out.append(keep(m, c))

    # --- cap tip: a polite little bow, one hand lifting the cap like a hat
    L = 2.4
    c = one_shot(m, 'cap_tip', L)
    bow = lambda t: keys(t, [(0, 0), (0.5, 0), (0.9, 1), (1.4, 1), (1.9, 0), (2.4, 0)])
    c.rot('right_arm', lambda t: (keys(t, [(0, 0), (0.4, 150), (1.6, 150), (2.1, 0), (2.4, 0)]), 0, 20 * pulse(t, 0.2, 2.2)), add=True)
    c.pos('h_cap', lambda t: (0, 1.8 * pulse(t, 0.45, 1.6), 0), add=True)
    c.rot('h_cap', lambda t: (-14 * pulse(t, 0.45, 1.6), 0, -10 * pulse(t, 0.45, 1.6)), add=True)
    c.rot('h_head', lambda t: (-22 * bow(t), 0, 0), add=True)
    c.rot('body', lambda t: (-10 * bow(t), 0, 0), add=True)
    c.rot('left_arm', lambda t: (-20 * bow(t), 0, 0), add=True)
    c.particle(1.0, SPORES, 4, 0.3, 0.01, 1.2)
    out.append(keep(m, c))

    # --- root wiggle: plants its feet and wiggles its toes into the soil, a contented shiver
    L = 2.6
    c = one_shot(m, 'root_wiggle', L)
    dig = lambda t: pulse(t, 0.3, 2.2)
    for i, leg in enumerate(LEGS):
        c.rot(leg, (lambda i: lambda t: (0, 22 * math.sin(2 * math.pi * 6 * t / 1.3 + i * math.pi) * dig(t), 0))(i), add=True)
    c.pos('body', lambda t: (0, -0.7 * dig(t), 0), add=True)
    c.scl('body', lambda t: (1 + 0.05 * dig(t), 1 - 0.05 * dig(t), 1 + 0.05 * dig(t)), add=True)
    c.rot('h_head', lambda t: (10 * dig(t), 0, 4 * math.sin(2 * math.pi * 6 * t / 1.3) * dig(t)), add=True)
    c.rot('h_cap', lambda t: (0, 0, 5 * math.sin(2 * math.pi * 6 * t / 1.3 + 0.8) * dig(t)), add=True)
    arms_out(c, lambda t: 12 * dig(t))
    c.sound(0.5, 'minecraft:block.rooted_dirt.step', 0.5, 1.3)
    c.sound(1.3, 'minecraft:block.rooted_dirt.step', 0.5, 1.5)
    out.append(keep(m, c))

    # --- rain bask (in the rain): arms out, cap tilted up to catch the drops, swaying happily
    L = 5.0
    c = Clip(m, 'idle_rain_bask', L, 'loop')
    loop_base(c, m)
    arms_out(c, lambda t: 50 + 10 * wave(t, L, 2), lift=10)
    c.rot('h_head', lambda t: (16, 0, 10 * wave(t, L, 1)), add=True)
    c.rot('h_cap', lambda t: (4 * wave(t, L, 3), 0, -6 * wave(t, L, 1, -0.05)), add=True)
    c.rot('body', lambda t: (0, 10 * wave(t, L, 1), 4 * wave(t, L, 1)), add=True)
    c.scl('glow_spots', lambda t: (1.15 + 0.1 * wave(t, L, 4), 1, 1.15 + 0.1 * wave(t, L, 4)), add=True)
    out.append(keep(m, c))

    # --- sitting loops
    L = 4.0
    c = sit_loop(m, 'sit_cap_bob', L)
    c.rot('h_head', lambda t: (-6 * abs(math.sin(2 * math.pi * 4 * t / L)), 0, 6 * wave(t, L, 2)), add=True)
    c.rot('h_cap', lambda t: (5 * math.sin(2 * math.pi * 4 * t / L - 0.6), 0, 0), add=True)
    for i, leg in enumerate(LEGS):
        c.rot(leg, (lambda i: lambda t: (15 * wave(t, L, 2, 0.5 * i), 0, 0))(i), add=True)
    out.append(keep(m, c))

    L = 6.0
    c = sit_loop(m, 'sit_spore_doze', L)
    c.rot('h_head', lambda t: (-12 * pulse(t, 1.0, 4.0), 0, 6 * pulse(t, 1.0, 4.0)), add=True)
    c.scl('h_cap', lambda t: (1 + 0.06 * pulse(t, 4.0, 4.8), 1 + 0.04 * pulse(t, 4.0, 4.8), 1 + 0.06 * pulse(t, 4.0, 4.8)), add=True)
    c.particle(4.5, SPORES, 5, 0.4, 0.01, 1.0)
    c.sound(4.5, 'minecraft:entity.puffer_fish.blow_out', 0.2, 1.9)
    out.append(keep(m, c))

    # --- lying loops: the cap breathes, the glow spots pulse, a few spores drift up
    L = 6.0
    c = sleep_loop(m, 'sleep_glow', L)
    c.scl('h_cap', lambda t: (1 + 0.04 * wave(t, L, 2), 1 + 0.03 * wave(t, L, 2), 1 + 0.04 * wave(t, L, 2)), add=True)
    c.scl('glow_spots', lambda t: (1 + 0.25 * (0.5 + 0.5 * wave(t, L, 2)), 1, 1 + 0.25 * (0.5 + 0.5 * wave(t, L, 2))), add=True)
    c.particle(2.0, SPORES, 2, 0.3, 0.005, 1.0)
    out.append(keep(m, c))

    L = 7.0
    c = sleep_loop(m, 'sleep_snore_puff', L)
    c.scl('h_cap', lambda t: (1 + 0.1 * pulse(t, 2.0, 3.0), 1 + 0.06 * pulse(t, 2.0, 3.0), 1 + 0.1 * pulse(t, 2.0, 3.0)), add=True)
    c.rot('h_head', lambda t: (4 * pulse(t, 2.0, 3.0), 0, 0), add=True)
    c.particle(2.9, SPORES, 4, 0.3, 0.01, 1.0)
    c.sound(2.9, 'minecraft:entity.puffer_fish.blow_out', 0.12, 1.4)
    out.append(keep(m, c))

    # --- reactions
    L = 1.9   # petting: the cap squishes under the hand, boings back up, a happy spore sprinkle
    c = one_shot(m, 'pet', L)
    pat = lambda t: pulse(t, 0.15, 0.7)
    c.scl('h_cap', lambda t: (1 + 0.14 * pat(t), 1 - 0.2 * pat(t), 1 + 0.14 * pat(t)), add=True)
    c.pos('h_cap', lambda t: (0, -0.8 * pat(t), 0), add=True)
    c.rot('h_cap', lambda t: (8 * decay(t, 0.7, 3, 3), 0, 6 * decay(t, 0.7, 2.4, 3)), add=True)
    c.rot('h_head', lambda t: (10 * pulse(t, 0.5, 1.7), 0, 12 * pulse(t, 0.5, 1.7)), add=True)
    arms_out(c, lambda t: 35 * pulse(t, 0.6, 1.7))
    c.pos('body', lambda t: (0, 0.8 * pulse(t, 0.7, 1.1), 0), add=True)
    c.particle(0.8, SPORES, 6, 0.4, 0.02, 1.1)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'h_cap', 'left_arm', 'right_arm')})
    pat = lambda t: pulse(t, 0.1, 0.6)
    c.scl('h_cap', lambda t: (1 + 0.12 * pat(t), 1 - 0.18 * pat(t), 1 + 0.12 * pat(t)), add=True)
    c.rot('h_cap', lambda t: (7 * decay(t, 0.6, 3, 3), 0, 0), add=True)
    c.rot('h_head', lambda t: (8 * pulse(t, 0.4, 1.5), 0, 10 * pulse(t, 0.4, 1.5)), add=True)
    arms_out(c, lambda t: 30 * pulse(t, 0.5, 1.5))
    out.append(keep(m, c))

    L = 2.8   # bond level-up: a springy leap, the cap twirls, a huge spore fountain on landing
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.4, 1.1, 8, 'body')
    c.rot('h_cap', lambda t: (0, 720 * step(t, 0.4, 1.3), 0), add=True)
    arms_out(c, lambda t: 70 * pulse(t, 0.3, 1.4) + 40 * pulse(t, 1.2, 2.6), lift=30)
    c.scl('h_cap', lambda t: (1 + 0.2 * pulse(t, 1.1, 1.6), 1 + 0.15 * pulse(t, 1.1, 1.6), 1 + 0.2 * pulse(t, 1.1, 1.6)), add=True)
    c.scl('glow_spots', lambda t: (1 + 0.5 * pulse(t, 0.4, 2.4), 1, 1 + 0.5 * pulse(t, 0.4, 2.4)), add=True)
    c.rot('h_head', lambda t: (18 * pulse(t, 0.3, 1.3), 0, 0), add=True)
    c.sound(0.45, 'minecraft:entity.slime.jump_small', 0.6, 1.4)
    c.sound(1.15, 'minecraft:entity.puffer_fish.blow_out', 0.7, 1.5)
    c.particle(1.2, SPORES, 24, 0.9, 0.05, 1.0)
    c.particle(1.45, 'minecraft:happy_villager', 6, 0.6, 0.02, 1.2)
    out.append(keep(m, c))
    return out


TAGS = {
    'cap_wobble': {'layer': 'base', 'move': False}, 'spore_puff': {'layer': 'base', 'move': False},
    'cap_tip': {'layer': 'base', 'move': False}, 'root_wiggle': {'layer': 'base', 'move': False},
    'idle_cap_bob': {'p': 'standing'}, 'idle_rain_bask': {'p': 'standing'},
    'sit_cap_bob': {'p': 'sitting'}, 'sit_spore_doze': {'p': 'sitting'},
    'sleep_glow': {'p': 'lying'}, 'sleep_snore_puff': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False},
}

IDLE = {
    'standing': [
        idle_entry('idle_cap_bob', 3, mn=5, mx=12, cd=15),
        idle_entry('cap_tip', 2, cd=30, boost={'owner_near': 2}),
        idle_entry('root_wiggle', 2, cd=30),
        idle_entry('spore_puff', 1, cd=25),
        idle_entry('idle_rain_bask', 4, mn=8, mx=16, cd=8, when='mood:idle_rain'),
        idle_entry('spore_puff', 1, cd=20, when='mood:idle_rain'),
    ],
    'sitting': [
        idle_entry('sit_cap_bob', 3, mn=5, mx=12, cd=12),
        idle_entry('sit_spore_doze', 2, mn=6, mx=12, cd=20, boost={'night': 2}),
    ],
    'lying': [
        idle_entry('sleep_glow', 2, mn=20, mx=50),
        idle_entry('sleep_snore_puff', 1, mn=7, mx=14, cd=30),
    ],
}

PROCEDURAL = {'tail_sway': 5}
