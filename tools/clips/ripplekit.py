"""Ripplekit (river otter with a kelp collar and a glowing pearl): floats on its back in water cracking clams on
its chest, grooming and rolling; on land it periscopes up tall, juggles a pebble on its back, grooms. Phase 2
(posture transitions from its own poses, as before)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403
from animkit import posture_set  # noqa: E402

FRONT = ('leg_fl', 'leg_fr')
HIND = ('leg_hl', 'leg_hr')
TAIL = ['tail', 'tail_tip']
KNOCK = 'minecraft:block.stone.hit'


def to_pose(c, m, clip, t_ref, fn, src=('idle', 0)):
    """Every bone goes from `src` towards clip@t_ref by fn(t) 0..1."""
    a_pose, b_pose = m.pose(*src), m.pose(clip, t_ref)
    for (b, ch) in sorted(set(a_pose) | set(b_pose)):
        if ch == 'scale':
            continue
        a, z = a_pose.get((b, ch), (0, 0, 0)), b_pose.get((b, ch), (0, 0, 0))
        if a != z:
            d = tuple(y - x for x, y in zip(a, z))
            c._add(b, ch, (lambda d: lambda t: tuple(v * fn(t) for v in d))(d), True)


def clips(m):
    out = posture_set(m)
    for c in out:
        m.put(c)

    # --- shake: its own shake, with the spray
    c = Clip(m, 'shake', m.length('shake'), 'once')
    c.copy('shake')
    c.sound(0.15, 'minecraft:entity.wolf.shake', 0.45, 1.6)
    c.particle(0.3, 'minecraft:splash', 10, 0.5, 0.05, 0.6)
    out.append(keep(m, c))

    # --- in water, on its back: knocks a clam on its chest (paws bang, head watching), crack!
    L = 3.0
    c = Clip(m, 'swim_clam_crack', L, 'once')
    loop_base(c, m, 'idle_swim')
    hits = [0.6, 0.95, 1.3, 1.65, 2.0]
    for leg in FRONT:
        c.rot(leg, lambda t: (sum(-30 * pulse(t, h - 0.2, h) for h in hits), 0, 0), add=True)
    c.rot('h_head', lambda t: (-14 * ramp(t, L, 0.4) + 3 * sum(pulse(t, h, h + 0.1) for h in hits), 0, 0), add=True)
    c.rot('body', lambda t: (0, 0, 3 * sum(pulse(t, h - 0.05, h + 0.15) for h in hits)), add=True)
    tail_wag(c, TAIL, 6, 4, env_fn=lambda t: ramp(t, L, 0.4))
    for i, h in enumerate(hits):
        c.sound(h, KNOCK, 0.4, 1.6 + 0.05 * i)
    c.sound(2.1, 'minecraft:entity.turtle.egg_crack', 0.5, 1.4)
    c.particle(2.1, 'minecraft:splash', 4, 0.3, 0.03, 0.6)
    out.append(keep(m, c))

    # --- in water: a playful barrel roll all the way round and back onto its back
    L = 2.2
    c = Clip(m, 'swim_roll', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.rot('body', lambda t: (0, 0, 360 * step(t, 0.3, 1.6)), add=True)
    tail_wag(c, TAIL, 18, 3, env_fn=lambda t: ramp(t, L, 0.3))
    c.sound(0.5, 'splash', 0.5, 1.4)
    c.particle(0.9, 'minecraft:bubble', 5, 0.4, 0.03, 0.5)
    out.append(keep(m, c))

    # --- in water, on its back: rubs its face with both paws, whiskers clean
    L = 2.8
    c = Clip(m, 'swim_face_rub', L, 'once')
    loop_base(c, m, 'idle_swim')
    rub = lambda t: pulse(t, 0.3, 2.5)
    for i, leg in enumerate(FRONT):
        c.rot(leg, (lambda i: lambda t: (30 * rub(t) + 14 * math.sin(2 * math.pi * 5 * t / L + i * math.pi) * rub(t), 0, 0))(i), add=True)
    c.rot('h_head', lambda t: (-16 * rub(t), 0, 8 * math.sin(2 * math.pi * 5 * t / L) * rub(t)), add=True)
    out.append(keep(m, c))

    # --- on land: periscope (rears up tall on its hind legs to look around, otter style)
    L = 3.2
    c = one_shot(m, 'periscope', L)
    up = lambda t: keys(t, [(0, 0), (0.4, 1), (2.7, 1), (3.2, 0)])
    to_pose(c, m, 'sit', 'end', up)
    c.rot('body', lambda t: (18 * up(t), 0, 0), add=True)
    c.pos('body', lambda t: (0, 1.2 * up(t), 0), add=True)
    c.rot('h_head', lambda t: (-16 * up(t), keys(t, [(0, 0), (0.6, 0), (1.0, 40), (1.6, 40), (2.0, -40), (2.5, -40), (2.8, 0), (3.2, 0)]), 0), add=True)
    for leg in FRONT:
        c.rot(leg, lambda t: (-20 * up(t), 0, 0), add=True)
    c.rot('ear_l', lambda t: (0, 0, -10 * pulse(t, 0.9, 1.4)), add=True)
    c.rot('ear_r', lambda t: (0, 0, 10 * pulse(t, 1.9, 2.4)), add=True)
    out.append(keep(m, c))

    # --- on land: flops onto its back and juggles a pebble between its paws, then rolls back up
    L = 4.0
    c = one_shot(m, 'pebble_juggle', L)
    back = lambda t: keys(t, [(0, 0), (0.5, 1), (3.4, 1), (4.0, 0)])
    c.rot('body', lambda t: (0, 0, 180 * back(t)), add=True)
    c.pos('body', lambda t: (0, 2.2 * back(t), 0), add=True)
    jug = lambda t: pulse(t, 0.7, 3.2)
    for i, leg in enumerate(FRONT):
        c.rot(leg, (lambda i: lambda t: (60 * back(t) + 25 * math.sin(2 * math.pi * 5 * t / 2.5 + i * math.pi) * jug(t), 0, 0))(i), add=True)
    for leg in HIND:
        c.rot(leg, lambda t: (30 * back(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-20 * back(t) + 8 * math.sin(2 * math.pi * 5 * t / 2.5) * jug(t), 0, 0), add=True)
    tail_wag(c, TAIL, 12, 4, env_fn=back)
    for h in (1.0, 1.5, 2.0, 2.5, 3.0):
        c.sound(h, 'minecraft:block.stone.step', 0.25, 1.8)
    out.append(keep(m, c))

    # --- on land: whisker groom (stands, sits back on its haunches to scrub its face) - its own groom + a beat
    L = 3.0
    c = one_shot(m, 'whisker_groom', L)
    up = lambda t: keys(t, [(0, 0), (0.4, 1), (2.5, 1), (3.0, 0)])
    to_pose(c, m, 'groom', 0.5, up)
    for i, leg in enumerate(FRONT):
        c.rot(leg, (lambda i: lambda t: (16 * math.sin(2 * math.pi * 6 * t / L + i * math.pi) * pulse(t, 0.4, 2.6), 0, 0))(i), add=True)
    c.rot('h_head', lambda t: (0, 0, 10 * math.sin(2 * math.pi * 3 * t / L) * pulse(t, 0.4, 2.6)), add=True)
    out.append(keep(m, c))

    # --- sitting loops (it sits up tall)
    L = 5.0
    c = sit_loop(m, 'sit_paw_rub', L)
    rub = lambda t: pulse(t, 0.5, 3.0)
    for i, leg in enumerate(FRONT):
        c.rot(leg, (lambda i: lambda t: (25 * rub(t) + 12 * math.sin(2 * math.pi * 6 * t / L + i * math.pi) * rub(t), 0, 0))(i), add=True)
    c.rot('h_head', lambda t: (-10 * rub(t), 0, 0), add=True)
    c.rot('tail_tip', lambda t: (0, 12 * wave(t, L, 2), 0), add=True)
    out.append(keep(m, c))

    L = 6.0
    c = sit_loop(m, 'sit_look_around', L)
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (1.5, 6), (3.0, 0), (4.5, 6), (6.0, 0)]),
                               keys(t, [(0, 0), (0.8, 45), (2.2, 45), (3.0, 0), (3.8, -45), (5.2, -45), (6.0, 0)]), 0), add=True)
    c.rot('body', lambda t: (3 * wave(t, L, 2), 0, 0), add=True)
    c.rot('ear_l', lambda t: (0, 0, -8 * pulse(t, 1.0, 1.4)), add=True)
    c.rot('ear_r', lambda t: (0, 0, 8 * pulse(t, 4.0, 4.4)), add=True)
    out.append(keep(m, c))

    # --- lying loops
    L = 6.0
    c = sleep_loop(m, 'sleep_tail_wrap', L)
    c.rot('tail', lambda t: (0, -10 * (0.5 + 0.5 * wave(t, L, 1)), 0), add=True)
    c.rot('tail_tip', lambda t: (0, -12 * (0.5 + 0.5 * wave(t, L, 1)), 0), add=True)
    c.scl('glow_pearl', lambda t: tuple([1 + 0.15 * (0.5 + 0.5 * wave(t, L, 2))] * 3), add=True)
    out.append(keep(m, c))

    L = 7.0
    c = sleep_loop(m, 'sleep_paddle', L)
    pad = lambda t: pulse(t, 1.5, 4.5)
    for i, leg in enumerate(FRONT + HIND):
        c.rot(leg, (lambda i: lambda t: (14 * math.sin(2 * math.pi * 4 * t / 3.0 + i * math.pi / 2) * pad(t), 0, 0))(i), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 2.0   # petting: wriggles, rubs its head into the hand, tail thumps
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (14 * e(t), 14 * wave(t, L, 2) * e(t), 18 * e(t)), add=True)
    c.rot('body', lambda t: (0, 8 * wave(t, L, 3) * e(t), 6 * e(t)), add=True)
    tail_wag(c, TAIL, 16, 6, env_fn=e)
    c.rot('ear_l', lambda t: (-12 * e(t), 0, 0), add=True)
    c.rot('ear_r', lambda t: (-12 * e(t), 0, 0), add=True)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'ear_l', 'ear_r', 'leg_fl', 'leg_fr')})
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (12 * e(t), 0, 16 * e(t)), add=True)
    for i, leg in enumerate(FRONT):
        c.rot(leg, (lambda i: lambda t: (15 * e(t) + 10 * math.sin(2 * math.pi * 3 * t / L + i * math.pi) * e(t), 0, 0))(i), add=True)
    out.append(keep(m, c))

    L = 2.8   # bond level-up: a leaping dive-roll, pearl blazing, a spray of water
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.35, 1.05, 6, 'body')
    c.rot('body', lambda t: (0, 0, 360 * step(t, 0.35, 1.05)), add=True)
    for leg in FRONT:
        c.rot(leg, lambda t: (-50 * pulse(t, 0.35, 1.05), 0, 0), add=True)
    for leg in HIND:
        c.rot(leg, lambda t: (45 * pulse(t, 0.35, 1.05), 0, 0), add=True)
    c.scl('glow_pearl', lambda t: tuple([1 + 0.8 * pulse(t, 0.3, 2.4)] * 3), add=True)
    tail_wag(c, TAIL, 20, 6, env_fn=lambda t: ramp(t, L, 0.3))
    c.sound(1.05, 'splash', 0.7, 1.2)
    c.particle(1.05, 'minecraft:splash', 14, 0.6, 0.06, 0.4)
    out.append(keep(m, c))
    return out


TAGS = {
    'shake': {'layer': 'base', 'move': False, 'p': 'standing'},
    'swim_clam_crack': {'p': 'swimming', 'layer': 'base', 'move': True}, 'swim_roll': {'p': 'swimming', 'layer': 'base', 'move': True},
    'swim_face_rub': {'p': 'swimming', 'layer': 'base', 'move': True},
    'periscope': {'layer': 'base', 'move': False, 'p': 'standing'}, 'pebble_juggle': {'layer': 'base', 'move': False, 'p': 'standing'},
    'whisker_groom': {'layer': 'base', 'move': False, 'p': 'standing'},
    'sit_paw_rub': {'p': 'sitting'}, 'sit_look_around': {'p': 'sitting'},
    'sleep_tail_wrap': {'p': 'lying'}, 'sleep_paddle': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False, 'p': 'standing'},
}

IDLE = {
    'standing': [
        # on land
        idle_entry('periscope', 3, cd=20, when='!in_water', boost={'player_near': 1.5}),
        idle_entry('pebble_juggle', 2, cd=35, when='!in_water'),
        idle_entry('whisker_groom', 2, cd=25, when='!in_water'),
        idle_entry('shake', 1, cd=40, when='!in_water', boost={'rain': 4}),
        # floating on its back in water
        idle_entry('swim_clam_crack', 3, cd=15, when='mood:idle_swim'),
        idle_entry('swim_roll', 2, cd=15, when='mood:idle_swim'),
        idle_entry('swim_face_rub', 2, cd=15, when='mood:idle_swim'),
    ],
    'sitting': [
        idle_entry('sit_paw_rub', 2, mn=5, mx=5, cd=15),
        idle_entry('sit_look_around', 3, mn=6, mx=12, cd=10),
    ],
    'lying': [
        idle_entry('sleep_tail_wrap', 2, mn=20, mx=50),
        idle_entry('sleep_paddle', 1, mn=7, mx=14, cd=30),
    ],
}

PROCEDURAL = {'tail_sway': 7, 'ear_twitch': 3}
