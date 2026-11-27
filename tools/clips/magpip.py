"""Magpip (trinket magpie): shiny-hunting head tilts, bird hops, wing flicks, preening, trinket tosses, tail-flicking
struts. Phase 2. Its old sit_down / lie_down started on the hover pose (wings up) and popped from the ground idle:
the posture transitions are re-authored from idle / sit / sleep."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403
from animkit import lerp3, posture_set  # noqa: E402

WING = ('right_wing', 'left_wing', 'right_wing_fore', 'left_wing_fore', 'right_wing_tip', 'left_wing_tip')


def wings_open(c, m, fn):
    """Wings lift towards the hover pose (wings up) by fn(t) in 0..1 (lerp of the bird's own poses)."""
    idle0, hov = m.pose('idle', 0), m.pose('hover', 0)
    for b in WING:
        for ch in ('rotation', 'position'):
            a = idle0.get((b, ch), (0, 0, 0))
            h = hov.get((b, ch), (0, 0, 0))
            if a == h:
                continue
            d = tuple(y - x for x, y in zip(a, h))
            c._add(b, ch, (lambda d: lambda t: tuple(v * fn(t) for v in d))(d), True)


def toward(c, m, clip, t_ref, bones, fn):
    """Bones move towards another clip's pose (at t_ref) by fn(t)."""
    idle0, ref = m.pose('idle', 0), m.pose(clip, t_ref)
    for b in bones:
        for ch in ('rotation', 'position'):
            a = idle0.get((b, ch), (0, 0, 0))
            r = ref.get((b, ch), (0, 0, 0))
            if a == r:
                continue
            d = tuple(y - x for x, y in zip(a, r))
            c._add(b, ch, (lambda d: lambda t: tuple(v * fn(t) for v in d))(d), True)


def clips(m):
    out = posture_set(m)
    for c in out:
        m.put(c)

    # --- shiny tilt: something glints on the ground; sharp head cocks one way, the other, a peer, a test peck
    L = 3.0
    c = one_shot(m, 'shiny_tilt', L)
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (0.3, 0), (0.5, -10), (1.1, -12), (1.3, -28), (1.8, -30), (2.0, -45), (2.15, -20), (2.6, 0), (3.0, 0)]),
                               keys(t, [(0, 0), (0.3, 10), (1.1, -10), (1.6, 0), (3.0, 0)]),
                               keys(t, [(0, 0), (0.25, 0), (0.4, 32), (0.9, 30), (1.05, -34), (1.6, -30), (1.8, 0), (3.0, 0)])), add=True)
    c.rot('body', lambda t: (-10 * pulse(t, 1.0, 2.6), 0, 0), add=True)
    c.pos('body', lambda t: (0, 0, -0.8 * pulse(t, 1.0, 2.6)), add=True)
    c.rot('tail', lambda t: (16 * pulse(t, 1.0, 2.6), 0, 0), add=True)
    c.rot('tail_fan', lambda t: (10 * pulse(t, 2.0, 2.4), 0, 0), add=True)
    c.sound(2.0, 'minecraft:block.amethyst_block.hit', 0.3, 1.8)
    out.append(keep(m, c))

    # --- hop hop: two quick bird hops, each with a wing flick, tail bounce on landing
    L = 1.9
    c = one_shot(m, 'hop_hop', L)
    for t0, t1 in ((0.25, 0.55), (0.85, 1.15)):
        hop(c, 'root', t0, t1, 2.6, 'body')
        for leg in ('right_leg', 'left_leg'):
            c.rot(leg, (lambda t0, t1: lambda t: (-30 * pulse(t, t0, t1), 0, 0))(t0, t1), add=True)
        c.rot('tail', (lambda t1: lambda t: (-14 * pulse(t, t1 - 0.05, t1 + 0.25), 0, 0))(t1), add=True)
        c.sound(t1, 'step', 0.6, 1.3)
    wings_open(c, m, lambda t: 0.35 * (pulse(t, 0.2, 0.5) + pulse(t, 0.8, 1.1)))
    c.rot('h_head', lambda t: (8 * pulse(t, 0.2, 1.4), 20 * pulse(t, 1.2, 1.9), 0), add=True)
    out.append(keep(m, c))

    # --- wing flick (overlay: wings + tail, works sitting too): two snappy half-opens
    L = 1.1
    c = Clip(m, 'wing_flick', L, 'once')
    hov = m.pose('hover', 0)
    for b in WING:
        for ch in ('rotation', 'position'):
            h = hov.get((b, ch))
            if h:
                c._add(b, ch, (lambda h: lambda t: tuple(v * 0.4 * (pulse(t, 0.05, 0.35) + pulse(t, 0.4, 0.75)) for v in h))(h), False)
    c.rot('tail', lambda t: (-18 * pulse(t, 0.1, 0.4) - 14 * pulse(t, 0.45, 0.8), 0, 0))
    c.rot('tail_fan', lambda t: (0, 0, 0))
    c.scl('tail_fan', lambda t: (1 + 0.25 * pulse(t, 0.1, 0.8), 1, 1))
    c.sound(0.1, 'minecraft:entity.parrot.fly', 0.3, 1.5)
    out.append(keep(m, c))

    # --- preen (standing loop): head buried under one wing, then the other; feathers lifted
    L = 5.0
    c = Clip(m, 'idle_preen', L, 'loop')
    loop_base(c, m)
    side = lambda t: wave(t, L, 1)
    dig = lambda t: abs(wave(t, L, 2, 0.25))
    toward(c, m, 'groom', 0.5, ['body', 'right_wing', 'left_wing', 'right_leg', 'left_leg'], lambda t: 0.7 * dig(t))
    c.rot('h_head', lambda t: (-40 * dig(t) + 5 * math.sin(2 * math.pi * 10 * t / L) * dig(t), 55 * side(t), 18 * side(t)), add=True)
    c.rot('tail', lambda t: (0, -10 * side(t), 0), add=True)
    c.scl('body', lambda t: (1 + 0.06 * dig(t), 1 + 0.05 * dig(t), 1 + 0.06 * dig(t)), add=True)
    out.append(keep(m, c))

    # --- trinket toss: flips its shiny up off the beak and catches it
    L = 2.2
    c = one_shot(m, 'trinket_toss', L)
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (0.3, -12), (0.5, 30), (0.9, 35), (1.15, 12), (1.3, -8), (1.6, 6), (2.2, 0)]), 0, 0), add=True)
    c.pos('trinket', lambda t: (0, keys(t, [(0, 0), (0.45, 0), (0.85, 5.0), (1.15, 0.2), (1.25, 0), (2.2, 0)], 'smooth'),
                                keys(t, [(0, 0), (0.45, 0), (0.85, 1.2), (1.15, 0), (2.2, 0)])), add=True)
    c.rot('trinket', lambda t: (360 * step(t, 0.45, 1.15), 0, 0), add=True)
    c.pos('body', lambda t: (0, -0.5 * pulse(t, 0.2, 0.5) + 0.4 * pulse(t, 0.45, 0.9), 0), add=True)
    wings_open(c, m, lambda t: 0.25 * pulse(t, 1.2, 1.7))
    c.rot('tail', lambda t: (-12 * pulse(t, 1.2, 1.7), 0, 0), add=True)
    c.sound(0.5, 'minecraft:entity.item.pickup', 0.3, 1.8)
    c.sound(1.2, 'minecraft:block.chain.hit', 0.35, 1.9)
    out.append(keep(m, c))

    # --- strut (standing loop): head-bobbing strut on the spot, the magpie tail flicking up
    L = 4.0
    c = Clip(m, 'idle_strut', L, 'loop')
    loop_base(c, m)
    c.pos('h_head', lambda t: (0, 0, -0.8 * (0.5 + 0.5 * wave(t, L, 8))), add=True)
    c.rot('h_head', lambda t: (4 * wave(t, L, 8), 20 * wave(t, L, 1), 0), add=True)
    for i, leg in enumerate(('right_leg', 'left_leg')):
        c.rot(leg, (lambda i: lambda t: (16 * max(0.0, wave(t, L, 4, 0.5 * i)), 0, 0))(i), add=True)
    c.rot('body', lambda t: (0, 10 * wave(t, L, 1), 3 * wave(t, L, 4)), add=True)
    c.rot('tail', lambda t: (-16 * pulse(t % 2.0, 0.6, 0.9), 0, 0), add=True)
    c.rot('tail_fan', lambda t: (0, 0, 0), add=True)
    c.sound(0.0, 'step', 0.4, 1.3)
    c.sound(1.0, 'step', 0.4, 1.4)
    c.sound(2.0, 'step', 0.4, 1.3)
    c.sound(3.0, 'step', 0.4, 1.4)
    out.append(keep(m, c))

    # --- sitting loops (it sits on its belly)
    L = 5.0
    c = sit_loop(m, 'sit_preen', L)
    side = lambda t: wave(t, L, 1)
    dig = lambda t: abs(wave(t, L, 2, 0.25))
    c.rot('h_head', lambda t: (-35 * dig(t), 55 * side(t), 15 * side(t)), add=True)
    wings_open(c, m, lambda t: 0.12 * dig(t))
    c.scl('body', lambda t: (1 + 0.05 * dig(t), 1 + 0.05 * dig(t), 1), add=True)
    out.append(keep(m, c))

    L = 4.0
    c = sit_loop(m, 'sit_tail_flick', L)
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (1.0, 6), (2.0, 0), (3.0, 6), (4.0, 0)]), keys(t, [(0, 0), (0.6, 40), (1.6, 40), (2.2, -35), (3.4, -35), (4.0, 0)]),
                               keys(t, [(0, 0), (0.6, 18), (1.6, 18), (2.2, -18), (3.4, -18), (4.0, 0)])), add=True)
    c.rot('tail', lambda t: (-18 * (pulse(t, 0.9, 1.15) + pulse(t, 2.9, 3.15)), 0, 0), add=True)
    out.append(keep(m, c))

    # --- lying loops
    L = 6.0
    c = sleep_loop(m, 'sleep_puffed', L)
    c.scl('body', lambda t: (1 + 0.06 * (0.5 + 0.5 * wave(t, L, 2)), 1 + 0.07 * (0.5 + 0.5 * wave(t, L, 2)), 1 + 0.06 * (0.5 + 0.5 * wave(t, L, 2))), add=True)
    c.rot('h_head', lambda t: (0, 0, 3 * wave(t, L, 1)), add=True)
    out.append(keep(m, c))

    L = 7.0
    c = sleep_loop(m, 'sleep_ruffle', L)
    c.scl('body', lambda t: (1 + 0.12 * pulse(t, 2.0, 2.8), 1 + 0.1 * pulse(t, 2.0, 2.8), 1 + 0.12 * pulse(t, 2.0, 2.8)), add=True)
    wings_open(c, m, lambda t: 0.08 * pulse(t, 2.0, 2.8))
    c.rot('tail', lambda t: (-10 * pulse(t, 2.3, 2.9), 0, 0), add=True)
    c.rot('h_head', lambda t: (0, 10 * pulse(t, 3.0, 6.0), 0), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 1.8   # petting: puffs up, rubs its head into the hand, a happy half-flutter
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    c.scl('body', lambda t: (1 + 0.1 * e(t), 1 + 0.08 * e(t), 1 + 0.1 * e(t)), add=True)
    c.rot('h_head', lambda t: (14 * e(t), 12 * wave(t, L, 2) * e(t), 22 * e(t)), add=True)
    wings_open(c, m, lambda t: 0.2 * pulse(t, 0.6, 1.4))
    c.rot('tail', lambda t: (-10 * e(t), 14 * wave(t, L, 4) * e(t), 0), add=True)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'tail')})
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (12 * e(t), 10 * wave(t, L, 2) * e(t), 20 * e(t)), add=True)
    c.rot('tail', lambda t: (-12 * e(t), 0, 0), add=True)
    out.append(keep(m, c))

    L = 2.6   # bond level-up: leaps, wings blaze open, the trinket spins over its head, it lands with a flourish
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.35, 1.25, 6, 'body')
    wings_open(c, m, lambda t: keys(t, [(0, 0), (0.3, 0), (0.45, 1), (0.6, 0.5), (0.75, 1), (0.9, 0.5), (1.05, 1), (1.3, 0.6), (1.7, 0), (2.6, 0)]))
    c.rot('root', lambda t: (0, 360 * step(t, 0.4, 1.2), 0), add=True)
    c.pos('trinket', lambda t: (0, 4 * pulse(t, 0.5, 1.4), 0), add=True)
    c.rot('trinket', lambda t: (0, 720 * step(t, 0.5, 1.4), 0), add=True)
    c.rot('h_head', lambda t: (20 * pulse(t, 0.3, 1.4), 0, 0), add=True)
    c.rot('tail', lambda t: (-20 * pulse(t, 1.2, 1.8), 0, 0), add=True)
    for tb in (0.45, 0.75, 1.05):
        c.sound(tb, 'minecraft:entity.parrot.fly', 0.4, 1.4)
    c.sound(1.3, 'minecraft:entity.allay.item_taken', 0.5, 1.5)
    out.append(keep(m, c))
    return out


TAGS = {
    'shiny_tilt': {'layer': 'base', 'move': False}, 'hop_hop': {'layer': 'base', 'move': False},
    'trinket_toss': {'layer': 'base', 'move': False}, 'wing_flick': {'layer': 'overlay', 'move': True},
    'idle_preen': {'p': 'standing'}, 'idle_strut': {'p': 'standing'},
    'sit_preen': {'p': 'sitting'}, 'sit_tail_flick': {'p': 'sitting'},
    'sleep_puffed': {'p': 'lying'}, 'sleep_ruffle': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False},
}

IDLE = {
    'standing': [
        idle_entry('shiny_tilt', 3, cd=20),
        idle_entry('hop_hop', 2, cd=20, boost={'owner_near': 1.5}),
        idle_entry('trinket_toss', 2, cd=30, boost={'happy': 2}),
        idle_entry('wing_flick', 1, cd=10),
        idle_entry('idle_preen', 2, mn=5, mx=10, cd=25, boost={'rain': 3}),
        idle_entry('idle_strut', 2, mn=4, mx=8, cd=20),
    ],
    'sitting': [
        idle_entry('sit_preen', 2, mn=5, mx=10, cd=15),
        idle_entry('sit_tail_flick', 3, mn=4, mx=12, cd=10),
    ],
    'lying': [
        idle_entry('sleep_puffed', 2, mn=20, mx=50),
        idle_entry('sleep_ruffle', 1, mn=7, mx=14, cd=30),
    ],
}

PROCEDURAL = {'tail': ['tail'], 'tail_sway': 4}
