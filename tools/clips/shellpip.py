"""Shellpip (hermit crab in a spiral seashell): claw clacks, retreating into its shell and peeking out, sideways
shuffles, sand sifting, shell adjusting; in water it floats and tumbles. Its sit is already the retreat into the shell.
Phase 2 (posture transitions authored from its own poses, as before)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403
from animkit import posture_set  # noqa: E402

LEGS_L = ('leg_l1', 'leg_l2')
LEGS_R = ('leg_r1', 'leg_r2')
LEGS = LEGS_L + LEGS_R
CLACK = 'minecraft:block.bone_block.hit'


def toward_sit(c, m, fn, bones=None):
    """Pulls into the shell (its own sit pose) by fn(t) 0..1."""
    idle0, sit = m.pose('idle', 0), m.pose('sit', 'end')
    keys_ = {k for k in sit} | {k for k in idle0}
    for (b, ch) in sorted(keys_):
        if ch == 'scale' or (bones and b not in bones):
            continue
        a, z = idle0.get((b, ch), (0, 0, 0)), sit.get((b, ch), (0, 0, 0))
        if a != z:
            d = tuple(y - x for x, y in zip(a, z))
            c._add(b, ch, (lambda d: lambda t: tuple(v * fn(t) for v in d))(d), True)


def clacks(c, times, amp=30, bone='claw_l_tip', vol=0.45):
    c.rot(bone, lambda t: (amp * sum(pulse(t, h - 0.12, h) for h in times), 0, 0), add=True)
    for h in times:
        c.sound(h, CLACK, vol, 1.9)


def clips(m):
    out = posture_set(m)
    for c in out:
        m.put(c)

    # --- claw clack: rears the big claw up and clacks it, the little claw waving along
    L = 2.0
    c = one_shot(m, 'claw_clack', L)
    up = lambda t: keys(t, [(0, 0), (0.3, 1), (1.6, 1), (2.0, 0)])
    c.rot('claw_l', lambda t: (40 * up(t), 15 * up(t), -10 * up(t)), add=True)
    c.rot('claw_r', lambda t: (25 * up(t) + 10 * wave(t, L, 4) * up(t), 0, 0), add=True)
    clacks(c, [0.55, 0.8, 1.05, 1.3])
    c.rot('body', lambda t: (-5 * up(t), 0, 3 * wave(t, L, 2) * up(t)), add=True)
    c.rot('h_head', lambda t: (8 * up(t), 0, 0), add=True)
    out.append(keep(m, c))

    # --- shell retreat: startles, whips into its shell, a long beat, the eye stalks peek out, then it pops back out
    L = 3.6
    c = one_shot(m, 'shell_retreat', L)
    hide = lambda t: keys(t, [(0, 0), (0.12, 1), (2.6, 1), (3.1, 0), (3.6, 0)], 'out')
    toward_sit(c, m, hide)
    c.pos('h_head', lambda t: (0, 1.4 * pulse(t, 1.4, 2.6), 0), add=True)
    c.rot('h_head', lambda t: (0, 25 * pulse(t, 1.6, 2.0) - 25 * pulse(t, 2.0, 2.4), 0), add=True)
    c.rot('shell', lambda t: (0, 0, 4 * decay(t, 0.12, 4, 6)), add=True)
    c.pos('root', lambda t: (0, -0.4 * pulse(t, 0.08, 0.3) + 0.6 * pulse(t, 3.0, 3.4), 0), add=True)
    c.sound(0.1, 'minecraft:block.decorated_pot.insert', 0.5, 1.5)
    c.sound(3.0, 'minecraft:entity.turtle.ambient_land', 0.3, 2.0)
    out.append(keep(m, c))

    # --- sidestep (standing loop): the crab shuffle, scuttling sideways one way and back
    L = 4.0
    c = Clip(m, 'idle_sidestep', L, 'loop')
    loop_base(c, m)
    c.pos('root', lambda t: (1.6 * wave(t, L, 1), 0, 0), add=True)
    for i, leg in enumerate(LEGS):
        c.rot(leg, (lambda i: lambda t: (0, 0, 14 * math.sin(2 * math.pi * 6 * t / L + i * math.pi / 2)))(i), add=True)
    c.rot('body', lambda t: (0, 0, 3 * math.sin(2 * math.pi * 6 * t / L)), add=True)
    c.rot('claw_l', lambda t: (10 + 6 * wave(t, L, 2), 0, 0), add=True)
    c.rot('h_head', lambda t: (0, 15 * wave(t, L, 1, 0.25), 0), add=True)
    for k in range(6):
        c.sound(k * L / 6, 'step', 0.35, 1.4)
    out.append(keep(m, c))

    # --- sand sift: digs at the sand with both claws in turns, a little treasure-hunter
    L = 2.8
    c = one_shot(m, 'sand_sift', L)
    e = lambda t: ramp(t, L, 0.3)
    c.rot('body', lambda t: (-8 * e(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-10 * e(t), 0, 0), add=True)
    for i, claw in enumerate(('claw_l', 'claw_r')):
        c.rot(claw, (lambda i: lambda t: (-25 * max(0.0, math.sin(2 * math.pi * 4 * t / 2.0 + i * math.pi)) * pulse(t, 0.3, 2.5), 0, 0))(i), add=True)
    for h in (0.6, 1.1, 1.6, 2.1):
        c.sound(h, 'minecraft:item.brush.brushing.sand', 0.35, 1.4)
    c.particle(1.2, 'minecraft:white_ash', 4, 0.3, 0.02, 0.2)
    out.append(keep(m, c))

    # --- shell adjust: shrugs the shell up, wiggles it into place, settles
    L = 2.0
    c = one_shot(m, 'shell_adjust', L)
    c.pos('shell', lambda t: (0, 1.0 * pulse(t, 0.2, 0.7), 0), add=True)
    c.rot('shell', lambda t: (0, 0, 10 * math.sin(2 * math.pi * 3 * t / 1.0) * pulse(t, 0.5, 1.6)), add=True)
    c.rot('glow_sea_glass', lambda t: (0, 0, 0), add=True)
    c.pos('body', lambda t: (0, 0.5 * pulse(t, 0.2, 0.7), 0), add=True)
    for leg in LEGS:
        c.rot(leg, lambda t: (0, 0, 6 * pulse(t, 0.2, 0.7)), add=True)
    c.sound(0.3, 'minecraft:block.decorated_pot.insert', 0.4, 1.3)
    out.append(keep(m, c))

    # --- in water: drifts up with its legs splayed and claws waving, then sinks back
    L = 2.6
    c = Clip(m, 'swim_float', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.pos('root', lambda t: (0, 3 * pulse(t, 0.2, 2.4), 0), add=True)
    for i, leg in enumerate(LEGS):
        c.rot(leg, (lambda s: lambda t: (0, 0, s * 18 * pulse(t, 0.3, 2.3)))(1 if i < 2 else -1), add=True)
    c.rot('claw_l', lambda t: (20 * math.sin(2 * math.pi * 3 * t / L) * pulse(t, 0.3, 2.3), 0, 0), add=True)
    c.rot('claw_r', lambda t: (20 * math.sin(2 * math.pi * 3 * t / L + 1.5) * pulse(t, 0.3, 2.3), 0, 0), add=True)
    c.particle(0.5, 'minecraft:bubble', 3, 0.3, 0.02, 0.6)
    c.sound(0.5, 'minecraft:block.bubble_column.bubble_pop', 0.3, 1.6)
    out.append(keep(m, c))

    L = 2.4   # in water: tumbles head over shell in the current, legs flailing
    c = Clip(m, 'swim_tumble', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.rot('root', lambda t: (-360 * step(t, 0.3, 1.9), 0, 0), add=True)
    c.pos('root', lambda t: (0, 2 * pulse(t, 0.2, 2.1), 0), add=True)
    for i, leg in enumerate(LEGS):
        c.rot(leg, (lambda i: lambda t: (0, 0, 20 * math.sin(2 * math.pi * 5 * t / L + i) * pulse(t, 0.2, 2.1)))(i), add=True)
    c.particle(1.0, 'minecraft:bubble', 4, 0.3, 0.03, 0.5)
    out.append(keep(m, c))

    # --- sitting loops (tucked into its shell)
    L = 5.0
    c = sit_loop(m, 'sit_peek', L)
    pk = lambda t: pulse(t, 1.0, 3.8)
    c.pos('h_head', lambda t: (0, 1.4 * pk(t), -0.4 * pk(t)), add=True)
    c.rot('h_head', lambda t: (8 * pk(t), 30 * wave(t, L, 1) * pk(t), 0), add=True)
    c.rot('claw_l_tip', lambda t: (20 * pulse(t, 2.6, 3.0), 0, 0), add=True)
    c.sound(2.95, CLACK, 0.3, 1.9)
    out.append(keep(m, c))

    L = 6.0
    c = sit_loop(m, 'sit_shell_rock', L)
    c.rot('shell', lambda t: (0, 0, 4 * wave(t, L, 1)), add=True)
    c.rot('body', lambda t: (0, 0, 3 * wave(t, L, 1)), add=True)
    c.pos('h_head', lambda t: (0, 0.5 * pulse(t, 2.0, 4.0), 0), add=True)
    out.append(keep(m, c))

    # --- lying loops
    L = 6.0
    c = sleep_loop(m, 'sleep_shell_breath', L)
    c.pos('shell', lambda t: (0, 0.3 * wave(t, L, 2), 0), add=True)
    c.scl('glow_sea_glass', lambda t: tuple([1 + 0.12 * (0.5 + 0.5 * wave(t, L, 2))] * 3), add=True)
    out.append(keep(m, c))

    L = 7.0
    c = sleep_loop(m, 'sleep_claw_twitch', L)
    c.rot('claw_l_tip', lambda t: (14 * (pulse(t, 2.0, 2.2) + pulse(t, 2.3, 2.5)), 0, 0), add=True)
    c.rot('leg_r1', lambda t: (0, 0, -10 * pulse(t, 4.5, 4.9)), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 2.0   # petting: the shell bobs happily, claws up, a little clack of delight
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    c.pos('root', lambda t: (0, 0.6 * abs(math.sin(2 * math.pi * 3 * t / L)) * e(t), 0), add=True)
    c.rot('claw_l', lambda t: (35 * e(t), 0, 0), add=True)
    c.rot('claw_r', lambda t: (30 * e(t), 0, 0), add=True)
    clacks(c, [0.7, 1.1], vol=0.35)
    c.rot('h_head', lambda t: (10 * e(t), 0, 10 * wave(t, L, 2) * e(t)), add=True)
    c.rot('shell', lambda t: (0, 0, 5 * wave(t, L, 3) * e(t)), add=True)
    out.append(keep(m, c))

    L = 1.6   # pet while tucked in: the eye stalks pop up and a claw clacks
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'claw_l_tip')})
    c.pos('h_head', lambda t: (0, 1.5 * ramp(t, L, 0.3), 0), add=True)
    c.rot('h_head', lambda t: (10 * ramp(t, L, 0.3), 0, 0), add=True)
    clacks(c, [0.7], 25, vol=0.3)
    out.append(keep(m, c))

    L = 2.6   # bond level-up: a hop, a full shell spin, both claws up clacking, bubbles
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.3, 0.9, 4, 'body')
    c.rot('root', lambda t: (0, 360 * step(t, 0.3, 1.0), 0), add=True)
    c.rot('claw_l', lambda t: (50 * pulse(t, 0.2, 2.4), 0, 0), add=True)
    c.rot('claw_r', lambda t: (45 * pulse(t, 0.2, 2.4), 0, 0), add=True)
    clacks(c, [1.1, 1.35, 1.6, 1.85, 2.05])
    c.scl('glow_sea_glass', lambda t: tuple([1 + 0.4 * pulse(t, 0.6, 2.4)] * 3), add=True)
    c.particle(1.0, 'minecraft:bubble_pop', 8, 0.5, 0.03, 0.6)
    out.append(keep(m, c))
    return out


TAGS = {
    'claw_clack': {'layer': 'base', 'move': False}, 'shell_retreat': {'layer': 'base', 'move': False},
    'sand_sift': {'layer': 'base', 'move': False}, 'shell_adjust': {'layer': 'base', 'move': False},
    'idle_sidestep': {'p': 'standing'},
    'swim_float': {'p': 'swimming', 'layer': 'base', 'move': True}, 'swim_tumble': {'p': 'swimming', 'layer': 'base', 'move': True},
    'sit_peek': {'p': 'sitting'}, 'sit_shell_rock': {'p': 'sitting'},
    'sleep_shell_breath': {'p': 'lying'}, 'sleep_claw_twitch': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False},
}

IDLE = {
    'standing': [
        # on land
        idle_entry('claw_clack', 3, cd=18, when='!in_water'),
        idle_entry('shell_retreat', 2, cd=35, when='!in_water', boost={'monster_near': 4, 'player_near:3': 2}),
        idle_entry('idle_sidestep', 3, mn=4, mx=8, cd=15, when='!in_water'),
        idle_entry('sand_sift', 2, cd=25, when='!in_water'),
        idle_entry('shell_adjust', 1, cd=30, when='!in_water'),
        # in water (the swim idle mood)
        idle_entry('swim_float', 3, cd=12, when='mood:idle_swim'),
        idle_entry('swim_tumble', 2, cd=18, when='mood:idle_swim'),
        idle_entry('claw_clack', 1, cd=20, when='mood:idle_swim'),
    ],
    'sitting': [
        idle_entry('sit_peek', 3, mn=5, mx=10, cd=10),
        idle_entry('sit_shell_rock', 2, mn=6, mx=12, cd=15),
    ],
    'lying': [
        idle_entry('sleep_shell_breath', 2, mn=20, mx=50),
        idle_entry('sleep_claw_twitch', 1, mn=7, mx=14, cd=30),
    ],
}

PROCEDURAL = {'breath': 0.01}
