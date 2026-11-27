"""Bubblefin (little seahorse with a glowing bubble-pearl on its crest): fin flutters and bubble bursts. On land it
bounces on its curled tail; in water it streams bubbles and twirls. Phase 2 (spin re-baked so it does not unwind;
posture transitions from its own poses, as before)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403
from animkit import posture_set  # noqa: E402

TAIL = ['tail1', 'tail2', 'tail3']
POP = 'minecraft:block.bubble_column.bubble_pop'


def flutter(c, fn, amp=30, freq=14):
    """Side fins buzz (and the dorsal fin ripples) with amplitude fn(t)."""
    c.rot('fin_l', lambda t: (0, amp * math.sin(2 * math.pi * freq * t / 2.0) * fn(t), 0), add=True)
    c.rot('fin_r', lambda t: (0, -amp * math.sin(2 * math.pi * freq * t / 2.0) * fn(t), 0), add=True)
    c.rot('fin', lambda t: (0, 0.6 * amp * math.sin(2 * math.pi * freq * t / 2.0 + 1.0) * fn(t), 0), add=True)


def curl(c, fn):
    """The tail curls tighter (fn > 0) or uncurls."""
    c.rot('tail2', lambda t: (14 * fn(t), 0, 0), add=True)
    c.rot('tail3', lambda t: (24 * fn(t), 0, 0), add=True)


def clips(m):
    out = posture_set(m)
    for c in out:
        m.put(c)

    c = Clip(m, 'spin', m.length('spin'), 'once')
    c.copy('spin')
    c.particle(0.5, 'minecraft:bubble_pop', 4, 0.3, 0.02, 0.8)
    out.append(keep(m, c))

    # --- tail bounce (standing loop): boings along on its curled tail like a spring
    L = 2.4
    c = Clip(m, 'idle_tail_bounce', L, 'loop')
    loop_base(c, m)
    for k in range(3):
        t0 = k * L / 3 + 0.12
        hop(c, 'root', t0, t0 + 0.5, 2.0)
        c.sound(t0 + 0.5, 'minecraft:entity.slime.squish_small', 0.25, 1.9)
    sq = lambda t: sum(pulse(t, k * L / 3 - 0.05, k * L / 3 + 0.2) + pulse(t, k * L / 3 + L / 3 - 0.05, k * L / 3 + L / 3 + 0.2) for k in range(3)) * 0.5
    curl(c, lambda t: sq(t))
    flutter(c, lambda t: 0.5, 20, 12)
    c.rot('h_head', lambda t: (6 * wave(t, L, 3, 0.25), 10 * wave(t, L, 1), 0), add=True)
    out.append(keep(m, c))

    # --- fin flutter (overlay: fins + head, also sitting / in water): a happy buzz of every fin
    L = 1.4
    c = Clip(m, 'fin_flutter', L, 'once')
    e = lambda t: ramp(t, L, 0.25)
    base(c, {k: v for k, v in m.pose('idle', 0).items() if k[0] in ('fin_l', 'fin_r', 'fin')})
    flutter(c, e, 35, 16)
    c.rot('h_head', lambda t: (8 * e(t), 0, 6 * wave(t, L, 2) * e(t)))
    out.append(keep(m, c))

    # --- bubble burst: the crest bubble swells, wobbles ... POP, then a fresh one grows back
    L = 2.6
    c = one_shot(m, 'bubble_burst', L)
    size = lambda t: keys(t, [(0, 1), (0.2, 1), (1.2, 1.8), (1.35, 1.95), (1.42, 0.05), (1.9, 0.05), (2.4, 1.0), (2.6, 1.0)])
    c.scl('bubble', lambda t: (size(t) * (1 + 0.08 * math.sin(2 * math.pi * 6 * t) * pulse(t, 0.6, 1.35)),
                               size(t) * (1 - 0.08 * math.sin(2 * math.pi * 6 * t) * pulse(t, 0.6, 1.35)), size(t)), add=False)
    c.pos('bubble', lambda t: (0, 1.2 * pulse(t, 0.2, 1.42), 0), add=True)
    c.rot('h_head', lambda t: (10 * pulse(t, 0.3, 1.4) - 8 * pulse(t, 1.4, 1.8), 0, 0), add=True)
    c.pos('root', lambda t: (0, -0.5 * pulse(t, 1.38, 1.7), 0), add=True)
    flutter(c, lambda t: pulse(t, 1.4, 2.4), 30, 16)
    curl(c, lambda t: -0.5 * pulse(t, 0.4, 1.4) + 0.6 * pulse(t, 1.4, 1.9))
    c.sound(1.4, POP, 0.8, 1.2)
    c.sound(2.3, POP, 0.3, 1.9)
    c.particle(1.42, 'minecraft:bubble_pop', 8, 0.3, 0.04, 1.3)
    out.append(keep(m, c))

    # --- curious bob: leans in snout-first at something, bobs twice, tail tightening
    L = 2.4
    c = one_shot(m, 'curious_bob', L)
    lean = lambda t: keys(t, [(0, 0), (0.4, 1), (1.9, 1), (2.4, 0)])
    c.rot('body', lambda t: (-12 * lean(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-14 * lean(t) + 8 * (pulse(t, 0.7, 1.0) + pulse(t, 1.15, 1.45)), 0, 14 * pulse(t, 1.4, 2.0)), add=True)
    curl(c, lambda t: 0.6 * lean(t))
    flutter(c, lambda t: 0.6 * lean(t), 25, 12)
    out.append(keep(m, c))

    # --- in water: blows a stream of bubbles upward, head tipped back
    L = 2.6
    c = Clip(m, 'swim_bubble_stream', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.rot('h_head', lambda t: (18 * pulse(t, 0.2, 2.4), 0, 0), add=True)
    flutter(c, lambda t: ramp(t, L, 0.3), 20, 12)
    for k in range(5):
        c.particle(0.5 + 0.3 * k, 'minecraft:bubble', 2, 0.1, 0.06, 1.2)
        c.sound(0.5 + 0.3 * k, POP, 0.25, 1.4 + 0.1 * k)
    out.append(keep(m, c))

    L = 2.2   # in water: a twirl with its fins spread, a trail of bubbles
    c = Clip(m, 'swim_twirl', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.rot('root', lambda t: (0, 360 * step(t, 0.3, 1.7), 0), add=True)
    c.pos('root', lambda t: (0, 1.5 * pulse(t, 0.2, 2.0), 0), add=True)
    flutter(c, lambda t: ramp(t, L, 0.3), 30, 16)
    curl(c, lambda t: -0.6 * pulse(t, 0.3, 1.8))
    c.particle(0.8, 'minecraft:bubble', 5, 0.4, 0.02, 0.6)
    out.append(keep(m, c))

    # --- sitting loops
    L = 4.0
    c = sit_loop(m, 'sit_fin_fan', L)
    flutter(c, lambda t: 0.4 + 0.3 * wave(t, L, 2), 20, 12)
    c.rot('h_head', lambda t: (4 * wave(t, L, 1), 14 * wave(t, L, 1, 0.25), 0), add=True)
    out.append(keep(m, c))

    L = 5.0
    c = sit_loop(m, 'sit_bubble_bob', L)
    c.pos('bubble', lambda t: (0, 0.8 * (0.5 + 0.5 * wave(t, L, 2)), 0), add=True)
    c.scl('bubble', lambda t: tuple([1 + 0.12 * wave(t, L, 4)] * 3), add=True)
    c.rot('h_head', lambda t: (8 * (0.5 + 0.5 * wave(t, L, 2, -0.1)), 0, 5 * wave(t, L, 1)), add=True)
    curl(c, lambda t: 0.3 * wave(t, L, 1))
    out.append(keep(m, c))

    # --- lying loops
    L = 6.0
    c = sleep_loop(m, 'sleep_drift', L)
    c.rot('body', lambda t: (0, 0, 3 * wave(t, L, 1)), add=True)
    c.scl('bubble', lambda t: tuple([1 + 0.1 * wave(t, L, 2)] * 3), add=True)
    curl(c, lambda t: 0.2 * wave(t, L, 2))
    out.append(keep(m, c))

    L = 7.0
    c = sleep_loop(m, 'sleep_fin_twitch', L)
    flutter(c, lambda t: pulse(t, 2.0, 2.6) + pulse(t, 4.8, 5.2), 18, 14)
    c.particle(3.5, 'minecraft:bubble', 1, 0.1, 0.02, 1.2)
    out.append(keep(m, c))

    # --- reactions
    L = 1.8   # petting: every fin buzzes, nuzzles into the hand, the bubble jiggles
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    flutter(c, e, 35, 16)
    c.rot('h_head', lambda t: (12 * e(t), 0, 16 * e(t)), add=True)
    c.scl('bubble', lambda t: (1 + 0.12 * math.sin(2 * math.pi * 5 * t) * e(t), 1 - 0.12 * math.sin(2 * math.pi * 5 * t) * e(t), 1), add=True)
    curl(c, lambda t: 0.5 * e(t))
    c.particle(0.6, 'minecraft:bubble_pop', 3, 0.3, 0.02, 1.2)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'fin', 'fin_l', 'fin_r')})
    e = lambda t: ramp(t, L, 0.3)
    flutter(c, e, 30, 16)
    c.rot('h_head', lambda t: (10 * e(t), 0, 14 * e(t)), add=True)
    out.append(keep(m, c))

    L = 2.8   # bond level-up: a spring-loaded twirl high into the air and a big bubble burst at the top
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.35, 1.25, 7)
    c.rot('root', lambda t: (0, 720 * step(t, 0.35, 1.25), 0), add=True)
    flutter(c, lambda t: pulse(t, 0.2, 2.6), 35, 16)
    size = lambda t: keys(t, [(0, 1), (0.3, 1), (0.8, 1.7), (0.9, 0.05), (1.6, 0.05), (2.3, 1), (2.8, 1)])
    c.scl('bubble', lambda t: tuple([size(t)] * 3), add=False)
    curl(c, lambda t: -0.6 * pulse(t, 0.35, 1.25) + 0.5 * pulse(t, 1.2, 1.6))
    c.sound(0.9, POP, 0.9, 1.0)
    c.sound(1.0, 'minecraft:block.conduit.ambient.short', 0.5, 1.6)
    c.particle(0.9, 'minecraft:bubble_pop', 14, 0.6, 0.05, 1.4)
    c.particle(1.3, 'minecraft:nautilus', 8, 0.5, 0.04, 0.8)
    out.append(keep(m, c))
    return out


TAGS = {
    'spin': {'layer': 'base', 'move': False, 'p': 'standing'}, 'idle_tail_bounce': {'p': 'standing'},
    'fin_flutter': {'layer': 'overlay', 'move': True}, 'bubble_burst': {'layer': 'base', 'move': False},
    'curious_bob': {'layer': 'base', 'move': False},
    'swim_bubble_stream': {'p': 'swimming', 'layer': 'base', 'move': True}, 'swim_twirl': {'p': 'swimming', 'layer': 'base', 'move': True},
    'sit_fin_fan': {'p': 'sitting'}, 'sit_bubble_bob': {'p': 'sitting'},
    'sleep_drift': {'p': 'lying'}, 'sleep_fin_twitch': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False, 'p': 'standing'},
}

IDLE = {
    'standing': [
        # on land: bounces along on its curled tail
        idle_entry('idle_tail_bounce', 3, mn=4, mx=9, cd=15, when='!in_water'),
        idle_entry('bubble_burst', 2, cd=25, when='!in_water'),
        idle_entry('curious_bob', 2, cd=20, when='!in_water', boost={'player_near': 1.5}),
        idle_entry('fin_flutter', 2, cd=12),
        idle_entry('spin', 1, cd=30, when='!in_water', boost={'happy': 2}),
        # in water
        idle_entry('swim_bubble_stream', 3, cd=14, when='mood:idle_swim'),
        idle_entry('swim_twirl', 2, cd=18, when='mood:idle_swim'),
        idle_entry('bubble_burst', 1, cd=25, when='mood:idle_swim'),
    ],
    'sitting': [
        idle_entry('sit_fin_fan', 3, mn=5, mx=10, cd=10),
        idle_entry('sit_bubble_bob', 2, mn=6, mx=12, cd=15),
    ],
    'lying': [
        idle_entry('sleep_drift', 2, mn=20, mx=50),
        idle_entry('sleep_fin_twitch', 1, mn=7, mx=14, cd=30),
    ],
}

# the tail is a curled spring under it: no sideways sway
PROCEDURAL = {'tail_sway': 0, 'breath': 0.012}
