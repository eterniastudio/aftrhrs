"""Canopip (jungle monkey with a glowing seed pod on its curly tail): monkey antics. Copies its owner's crouch,
backflips, chest drums with real drum beats, vine swings, glow-pod inspection, excited ground slaps. Phase 2.
The old backflip ended at 360 deg (the blend out unwound it): re-baked (animkit wraps whole turns)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403

ARMS = ('left_arm', 'right_arm')
LEGS = ('left_leg', 'right_leg')
TAIL = ['tail', 'tail_mid', 'tail_tip']


def rear(c, m, fn, ref=('chest_drum', 0.4)):
    """Rears up onto the hind legs (its own chest_drum stance) by fn(t) 0..1."""
    idle0, r = m.pose('idle', 0), m.pose(*ref)
    for b in ('body', 'left_leg', 'right_leg', 'h_head', 'tail', 'tail_mid'):
        for ch in ('rotation', 'position'):
            a = idle0.get((b, ch), (0, 0, 0))
            z = r.get((b, ch), (0, 0, 0))
            if a != z:
                d = tuple(y - x for x, y in zip(a, z))
                c._add(b, ch, (lambda d: lambda t: tuple(v * fn(t) for v in d))(d), True)


def clips(m):
    out = []

    # --- backflip: the source clip re-baked (ends on 0, not 360) + a whoosh and a landing thump
    c = Clip(m, 'backflip', m.length('backflip'), 'once')
    c.copy('backflip')
    c.sound(0.3, 'minecraft:entity.player.attack.sweep', 0.25, 1.9)
    c.sound(1.05, 'minecraft:block.vine.step', 0.6, 1.2)
    out.append(keep(m, c))

    # --- vine swing: the source clip + creaking vine sounds at the ends of the swing
    c = Clip(m, 'swing', m.length('swing'), 'once')
    c.copy('swing')
    for tb in (0.5, 1.0, 1.5):
        c.sound(tb, 'minecraft:block.vine.step', 0.45, 1.1 + 0.1 * tb)
    out.append(keep(m, c))

    # --- chest drum: rears up, arms pound the chest in a rising rhythm, head thrown back, a hoot at the end
    L = 2.4
    c = one_shot(m, 'chest_drum', L)
    up = lambda t: keys(t, [(0, 0), (0.3, 1), (2.0, 1), (2.4, 0)])
    rear(c, m, up)
    beats = [0.5, 0.75, 1.0, 1.2, 1.4, 1.55, 1.7]
    for i, arm in enumerate(ARMS):
        side = -1 if i == 0 else 1
        hits = beats[i::2]
        c.rot(arm, (lambda side: lambda t: (75 * up(t), 0, side * 14 * up(t)))(side), add=True)
        c.rot(arm, (lambda hits, side: lambda t: (sum(-28 * pulse(t, h - 0.14, h) for h in hits), 0,
                                                 side * sum(-14 * pulse(t, h - 0.14, h) for h in hits)))(hits, side), add=True)
    c.scl('body', lambda t: (1 + 0.05 * sum(pulse(t, b - 0.02, b + 0.1) for b in beats), 1, 1 + 0.05 * sum(pulse(t, b - 0.02, b + 0.1) for b in beats)), add=True)
    c.rot('h_head', lambda t: (18 * pulse(t, 1.6, 2.2) + 3 * sum(pulse(t, b, b + 0.1) for b in beats), 0, 0), add=True)
    for i, b in enumerate(beats):
        c.sound(b, 'minecraft:block.note_block.basedrum', 0.55, 1.1 + 0.05 * i)
    c.sound(1.8, 'ambient_happy', 0.7, 1.0)
    out.append(keep(m, c))

    # --- copy crouch (standing loop, when the owner sneaks): low and sneaky, peeking left and right
    L = 4.0
    c = Clip(m, 'idle_crouch_copy', L, 'loop')
    loop_base(c, m)
    c.pos('body', lambda t: (0, -1.6, 0), add=True)
    for i, b in enumerate(ARMS + LEGS):
        c.pos(b, lambda t: (0, 1.5, 0), add=True)
        c.rot(b, (lambda s: lambda t: (0, 0, s * 12))((-1) ** i), add=True)
    c.rot('h_head', lambda t: (-8, keys(t, [(0, 0), (0.5, 35), (1.5, 35), (2.0, 0), (2.5, -35), (3.5, -35), (4.0, 0)]), 0), add=True)
    c.rot('tail', lambda t: (25, 0, 0), add=True)
    c.rot('tail_tip', lambda t: (0, 20 * wave(t, L, 4), 0), add=True)
    c.rot('h_left_ear', lambda t: (0, 0, -8), add=True)
    c.rot('h_right_ear', lambda t: (0, 0, 8), add=True)
    out.append(keep(m, c))

    # --- glow-pod inspect: curls the tail up over its back to bring the glowing pod in front of its face, pokes it
    L = 3.4
    c = one_shot(m, 'pod_inspect', L)
    curl = lambda t: keys(t, [(0, 0), (0.6, 1), (2.6, 1), (3.2, 0), (3.4, 0)])
    # the tail swings round to its right (+x) side and curls forward so the pod hangs beside its shoulder
    c.rot('tail', lambda t: (-10 * curl(t), 115 * curl(t), 0), add=True)
    c.rot('tail_mid', lambda t: (-55 * curl(t), 0, 0), add=True)
    c.rot('tail_tip', lambda t: (-25 * curl(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (10 * curl(t), -85 * curl(t), -12 * curl(t) + 10 * pulse(t, 1.0, 2.0) - 10 * pulse(t, 1.8, 2.7)), add=True)
    c.rot('body', lambda t: (0, -22 * curl(t), 0), add=True)
    rear(c, m, lambda t: 0.3 * curl(t))
    c.rot('right_arm', lambda t: (45 * curl(t) + 25 * pulse(t, 1.3, 1.6) + 25 * pulse(t, 1.9, 2.2), -30 * curl(t), 20 * curl(t)), add=True)
    c.scl('glow_pod', lambda t: tuple([1 + 0.25 * (pulse(t, 1.45, 1.75) + pulse(t, 2.05, 2.35))] * 3), add=True)
    c.sound(1.5, 'minecraft:block.amethyst_block.chime', 0.6, 1.4)
    c.sound(2.1, 'minecraft:block.amethyst_block.chime', 0.6, 1.7)
    out.append(keep(m, c))

    # --- monkey bounce: excited bouncing on all fours, slapping the ground left-right, chattering
    L = 2.2
    c = one_shot(m, 'monkey_bounce', L)
    slaps = [0.35, 0.6, 0.85, 1.1, 1.35, 1.6]
    for i, tb in enumerate(slaps):
        arm = ARMS[i % 2]
        c.rot(arm, (lambda tb: lambda t: (55 * pulse(t, tb - 0.25, tb), 0, 0))(tb), add=True)
        c.pos(arm, (lambda tb: lambda t: (0, 1.2 * pulse(t, tb - 0.25, tb), 0))(tb), add=True)
        c.sound(tb, 'minecraft:block.rooted_dirt.step', 0.4, 1.4)
    c.pos('body', lambda t: (0, 0.9 * abs(math.sin(2 * math.pi * 4 * t / 1.6)) * pulse(t, 0.2, 1.9), 0), add=True)
    c.rot('body', lambda t: (0, 0, 6 * wave(t, 1.0, 2) * ramp(t, L, 0.3)), add=True)
    c.rot('h_head', lambda t: (12 * ramp(t, L, 0.3), 0, 10 * wave(t, 1.1, 2) * ramp(t, L, 0.3)), add=True)
    tail_wag(c, TAIL, 10, 5, env_fn=lambda t: ramp(t, L, 0.3))
    c.sound(0.3, 'ambient_happy', 0.6, 1.0)
    out.append(keep(m, c))

    # --- fidget (standing loop): scratches its side with one hand, looks around, tail curls and uncurls
    L = 6.0
    c = Clip(m, 'idle_fidget', L, 'loop')
    loop_base(c, m)
    sc = lambda t: pulse(t, 0.6, 2.6)
    c.rot('right_arm', lambda t: (20 * sc(t) + 12 * math.sin(2 * math.pi * 12 * t / L) * sc(t), 0, 45 * sc(t)), add=True)
    c.rot('body', lambda t: (0, 0, -8 * sc(t)), add=True)
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (3.0, 0), (3.4, 10), (5.4, 10), (6.0, 0)]),
                               keys(t, [(0, 0), (0.6, -25), (2.6, -25), (3.2, 40), (4.2, 40), (4.6, -30), (5.4, -30), (6.0, 0)]), 0), add=True)
    c.rot('tail_mid', lambda t: (-25 * pulse(t, 3.0, 5.6), 0, 0), add=True)
    c.rot('tail_tip', lambda t: (-35 * pulse(t, 3.0, 5.6), 15 * wave(t, L, 3), 0), add=True)
    c.rot('h_left_ear', lambda t: (0, 0, -12 * pulse(t, 4.4, 4.7)), add=True)
    out.append(keep(m, c))

    # --- sitting loops (it sits up like a little person)
    L = 4.0
    c = sit_loop(m, 'sit_nibble', L)
    for i, arm in enumerate(ARMS):
        c.rot(arm, (lambda s: lambda t: (70 + 8 * math.sin(2 * math.pi * 4 * t / L), 0, s * 20))((-1) ** (i + 1)), add=True)
    c.rot('h_head', lambda t: (-6 + 6 * abs(math.sin(2 * math.pi * 6 * t / L)), 10 * wave(t, L, 1), 0), add=True)
    c.rot('tail_tip', lambda t: (0, 18 * wave(t, L, 2), 0), add=True)
    c.sound(0.5, 'minecraft:entity.generic.eat', 0.25, 1.7)
    c.sound(2.5, 'minecraft:entity.generic.eat', 0.25, 1.8)
    out.append(keep(m, c))

    L = 5.0
    c = sit_loop(m, 'sit_head_scratch', L)
    sc = lambda t: pulse(t, 0.5, 3.5)
    c.rot('right_arm', lambda t: (130 * sc(t), 0, 25 * sc(t) + 6 * math.sin(2 * math.pi * 10 * t / L) * sc(t)), add=True)
    c.rot('h_head', lambda t: (8 * sc(t), 0, 16 * sc(t)), add=True)
    c.rot('tail', lambda t: (0, 10 * wave(t, L, 2), 0), add=True)
    c.rot('h_left_ear', lambda t: (0, 0, -10 * pulse(t, 3.6, 4.0)), add=True)
    out.append(keep(m, c))

    # --- lying loops
    L = 7.0
    c = sleep_loop(m, 'sleep_sprawl', L)
    fl = lambda t: pulse(t, 1.0, 5.5)
    c.rot('right_arm', lambda t: (-50 * fl(t), 0, 30 * fl(t)), add=True)
    c.rot('h_head', lambda t: (0, -10 * fl(t), -6 * fl(t)), add=True)
    c.rot('tail_tip', lambda t: (0, 20 * pulse(t, 5.6, 6.2), 0), add=True)
    out.append(keep(m, c))

    L = 6.0
    c = sleep_loop(m, 'sleep_twitch', L)
    c.rot('left_leg', lambda t: (-14 * (pulse(t, 1.0, 1.2) + pulse(t, 1.3, 1.5)), 0, 0), add=True)
    c.rot('right_arm', lambda t: (-12 * pulse(t, 3.4, 3.7), 0, 0), add=True)
    c.rot('h_right_ear', lambda t: (0, 0, 12 * pulse(t, 2.2, 2.5)), add=True)
    c.scl('glow_pod', lambda t: tuple([1 + 0.12 * wave(t, L, 2)] * 3), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 2.0   # petting: grabs at the hand with both arms, giddy wiggle, tail wraps
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    rear(c, m, lambda t: 0.7 * e(t))
    for i, arm in enumerate(ARMS):
        c.rot(arm, (lambda s: lambda t: (90 * e(t) + 10 * wave(t, L, 4) * e(t), 0, s * 10 * e(t)))((-1) ** (i + 1)), add=True)
    c.rot('h_head', lambda t: (16 * e(t), 0, 18 * wave(t, L, 2) * e(t)), add=True)
    c.rot('body', lambda t: (0, 0, 6 * wave(t, L, 3) * e(t)), add=True)
    tail_wag(c, TAIL, 10, 4, env_fn=e)
    out.append(keep(m, c))

    L = 1.6   # pet while sitting: overlay (arms clap, head tilt)
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'left_arm', 'right_arm', 'tail_tip')})
    e = lambda t: ramp(t, L, 0.25)
    claps = lambda t: sum(pulse(t, b - 0.12, b + 0.08) for b in (0.45, 0.75, 1.05))
    c.rot('left_arm', lambda t: (70 * e(t), 0, (-26 + 22 * claps(t)) * e(t)), add=True)
    c.rot('right_arm', lambda t: (70 * e(t), 0, (26 - 22 * claps(t)) * e(t)), add=True)
    c.rot('h_head', lambda t: (14 * e(t), 0, 14 * e(t)), add=True)
    for b in (0.45, 0.75, 1.05):
        c.sound(b, 'minecraft:block.note_block.hat', 0.4, 1.5)
    out.append(keep(m, c))

    L = 3.2   # bond level-up: a sky-high backflip into a victory chest drum
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.3, 1.0, 13, 'body')
    c.rot('root', lambda t: (360 * step(t, 0.3, 1.0), 0, 0), add=True)
    for b in ARMS + LEGS:
        c.rot(b, lambda t: (-40 * pulse(t, 0.3, 1.0), 0, 0), add=True)
    up = lambda t: keys(t, [(0, 0), (1.1, 0), (1.4, 1), (2.8, 1), (3.2, 0)])
    rear(c, m, up)
    beats = [1.5, 1.7, 1.9, 2.1, 2.25, 2.4]
    for i, arm in enumerate(ARMS):
        side = -1 if i == 0 else 1
        hits = beats[i::2]
        c.rot(arm, (lambda side, hits: lambda t: (75 * up(t) - sum(28 * pulse(t, h - 0.14, h) for h in hits), 0, side * 14 * up(t)))(side, hits), add=True)
    c.rot('h_head', lambda t: (20 * pulse(t, 2.4, 3.0), 0, 0), add=True)
    c.sound(0.3, 'minecraft:entity.player.attack.sweep', 0.3, 1.8)
    for i, b in enumerate(beats):
        c.sound(b, 'minecraft:block.note_block.basedrum', 0.6, 1.1 + 0.06 * i)
    c.sound(2.5, 'ambient_happy', 0.8, 1.0)
    out.append(keep(m, c))
    return out


TAGS = {
    'backflip': {'layer': 'base', 'move': False, 'p': 'standing'}, 'swing': {'layer': 'base', 'move': False, 'p': 'standing'},
    'chest_drum': {'layer': 'base', 'move': False, 'p': 'standing'}, 'pod_inspect': {'layer': 'base', 'move': False},
    'monkey_bounce': {'layer': 'base', 'move': False}, 'idle_crouch_copy': {'p': 'standing'}, 'idle_fidget': {'p': 'standing'},
    'sit_nibble': {'p': 'sitting'}, 'sit_head_scratch': {'p': 'sitting'},
    'sleep_sprawl': {'p': 'lying'}, 'sleep_twitch': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False, 'p': 'standing'},
}

IDLE = {
    'standing': [
        # copies its owner's crouch: almost certain while they sneak nearby, never otherwise
        idle_entry('idle_crouch_copy', 30, mn=3, mx=6, cd=2, when='owner_crouching'),
        idle_entry('pod_inspect', 2, cd=30, boost={'night': 2}),
        idle_entry('monkey_bounce', 2, cd=25, boost={'owner_near': 2, 'happy': 2}),
        idle_entry('idle_fidget', 3, mn=6, mx=12, cd=18),
        idle_entry('chest_drum', 1, cd=35, boost={'owner_near': 1.5}),
        idle_entry('backflip', 1, cd=40, boost={'happy': 2}),
    ],
    'sitting': [
        idle_entry('sit_nibble', 2, mn=4, mx=8, cd=20),
        idle_entry('sit_head_scratch', 2, mn=5, mx=5, cd=18),
    ],
    'lying': [
        idle_entry('sleep_sprawl', 1, mn=14, mx=35),
        idle_entry('sleep_twitch', 1, mn=6, mx=12, cd=30),
    ],
}

PROCEDURAL = {'tail_sway': 7, 'ear_twitch': 4}

