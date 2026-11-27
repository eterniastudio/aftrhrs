"""Glimmermoth (phase 2): a glowing moth. Its sit is `rest` (wings folded back tent-wise); the posture transitions are
re-authored to join `rest` and sleep exactly (posture_set). Life: wing flutters, antenna twitches and cleaning, glimmer
dust shaken off its wings, a dreamy shimmer hover in the air, resting on the ground with its wings folded and fanning
them slowly. Flourishes that would stop the wings mid-air are on-ground only (`when: on_ground`); air-only ones
`in_air`."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import posture_set  # noqa: E402
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step  # noqa: E402

FLAP = 'minecraft:entity.parrot.fly'
CHIME = 'minecraft:block.note_block.chime'
SHIMMER = 'minecraft:block.amethyst_block.chime'
DUST = 'minecraft:white_ash'
SPARK = 'minecraft:end_rod'
WINGS = (('right_forewing', 1), ('left_forewing', -1), ('right_hindwing', 1), ('left_hindwing', -1))


def flutter(c, amp, freq, e, fore_only=False):
    """Wing beat added on top of the base pose: amp(t) deg at freq Hz inside envelope e(t)."""
    for w, s in WINGS:
        if fore_only and 'hind' in w:
            continue
        lag = 0.12 if 'hind' in w else 0.0
        R(c, w, lambda t, s=s, lag=lag: (0, 0, s * amp(t) * osc(t, freq, -lag) * e(t)))


def clips(m):
    out = Out(m, posture_set(m, sit='rest'))

    # --- ground (standing) ---------------------------------------------------------------------------------------
    # resting on the ground: wings fold back over its body, antennae sway; every few seconds the wings fan open
    c = start(m, 'ground_rest', 6.4, 'loop', src='idle')
    rest = m.pose('rest', 0)
    idle = m.pose('idle', 0)
    for w, s in WINGS:
        a, b = idle.get((w, 'rotation'), (0, 0, 0)), rest.get((w, 'rotation'), (0, 0, 0))
        R(c, w, lambda t, a=a, b=b: tuple((b[i] - a[i]) * (1 - 0.6 * bump(t, 3.0, 4.4)) for i in range(3)))
    P(c, 'body', lambda t: (0, -0.4, 0))
    R(c, 'right_antenna', lambda t: (8 * osc(t, 1 / 3.2), 0, -6 * osc(t, 1 / 6.4)))
    R(c, 'left_antenna', lambda t: (8 * osc(t, 1 / 3.2, 0.2), 0, 6 * osc(t, 1 / 6.4, 0.2)))
    R(c, 'abdomen', lambda t: (3 * osc(t, 1 / 3.2), 0, 0))
    out.append(c)

    # wing flutter on the ground: a quick buzz, a pause, another buzz (overlay: wings only)
    c = start(m, 'wing_flutter', 1.8, src='idle', at=0, bones=[w for w, _ in WINGS])
    flutter(c, lambda t: 38, 9.0, lambda t: env(t, 0.1, 0.6, 0.08) + env(t, 0.95, 1.55, 0.08))
    c.sound(0.12, FLAP, 0.35, 1.6)
    c.sound(0.98, FLAP, 0.35, 1.7)
    out.append(c)

    # antenna twitches: one flicks, the other, both sweep forward to taste the air, head tilts (overlay)
    c = start(m, 'antenna_twitch', 2.2, src='idle', at=0, bones=['h_head', 'right_antenna', 'left_antenna'])
    R(c, 'right_antenna', lambda t: (-22 * jolt(t, 0.15, 0.6) + 30 * bump(t, 1.2, 1.9), 0, -14 * jolt(t, 0.15, 0.6)))
    R(c, 'left_antenna', lambda t: (-22 * jolt(t, 0.6, 1.05) + 30 * bump(t, 1.2, 1.9), 0, 14 * jolt(t, 0.6, 1.05)))
    R(c, 'h_head', lambda t: (8 * bump(t, 1.2, 1.9), 0, 12 * bump(t, 0.1, 0.65) - 12 * bump(t, 0.55, 1.1)))
    out.append(c)

    # antenna cleaning: front legs draw each antenna down and comb it (base, legs move)
    c = start(m, 'antenna_clean', 3.2, src='idle', at=0)
    l1 = lambda t: env(t, 0.3, 1.5, 0.25)
    l2 = lambda t: env(t, 1.7, 2.9, 0.25)
    R(c, 'h_head', lambda t: (-18 * (l1(t) + l2(t)), 0, 10 * l1(t) - 10 * l2(t)))
    R(c, 'right_antenna', lambda t: (-45 * l1(t), 0, 14 * l1(t) + 4 * osc(t, 5) * l1(t)))
    R(c, 'left_antenna', lambda t: (-45 * l2(t), 0, -14 * l2(t) + 4 * osc(t, 5) * l2(t)))
    R(c, 'right_leg_0_r', lambda t: (-70 * l1(t) + 12 * osc(t, 5) * l1(t), 0, 0))
    R(c, 'left_leg_0_r', lambda t: (-70 * l2(t) + 12 * osc(t, 5) * l2(t), 0, 0))
    R(c, 'body', lambda t: (8 * (l1(t) + l2(t)), 0, 0))
    out.append(c)

    # glimmer dust: it shivers its wings and a puff of glittering dust falls off (on the ground)
    c = start(m, 'dust_shake', 2.2, src='idle', at=0)
    sh = lambda t: env(t, 0.3, 1.4, 0.12)
    flutter(c, lambda t: 10, 14.0, sh)
    R(c, 'body', lambda t: (0, 0, 5 * osc(t, 14.0, 0.25) * sh(t)))
    R(c, 'abdomen', lambda t: (-10 * bump(t, 0.2, 0.6) + 6 * bump(t, 1.4, 1.9), 0, 0))
    S(c, 'glow_antenna_right', lambda t: (0.5 * jolt(t, 0.35, 1.8),) * 3)
    S(c, 'glow_antenna_left', lambda t: (0.5 * jolt(t, 0.35, 1.8),) * 3)
    c.particle(0.5, DUST, 6, 0.35, 0.01, 0.6)
    c.particle(0.9, SPARK, 2, 0.3, 0.02, 0.6)
    c.particle(1.2, DUST, 5, 0.35, 0.01, 0.6)
    c.sound(0.4, FLAP, 0.3, 1.9)
    c.sound(0.9, SHIMMER, 0.35, 1.6)
    out.append(c)

    # --- air (hovering) --------------------------------------------------------------------------------------------
    # shimmer hover: slower dreamy beats, a lazy figure-eight bob, glimmer trailing off the wings
    c = start(m, 'hover_shimmer', 4.8, 'loop', src='hover')
    P(c, 'body', lambda t: (1.2 * osc(t, 1 / 4.8), 0.9 * osc(t, 2 / 4.8), 0))
    R(c, 'body', lambda t: (4 * osc(t, 2 / 4.8, 0.25), 0, -10 * osc(t, 1 / 4.8, 0.1)))
    R(c, 'h_head', lambda t: (0, 0, 8 * osc(t, 1 / 4.8, 0.2)))
    S(c, 'glow_antenna_right', lambda t: (0.25 * (1 + osc(t, 1 / 2.4)) / 2,) * 3)
    S(c, 'glow_antenna_left', lambda t: (0.25 * (1 + osc(t, 1 / 2.4, 0.5)) / 2,) * 3)
    for t0 in (0.6, 2.0, 3.4):
        c.particle(t0, SPARK, 1, 0.4, 0.0, 0.4)
    c.particle(1.3, DUST, 3, 0.4, 0.0, 0.4)
    c.particle(3.7, DUST, 3, 0.4, 0.0, 0.4)
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    # petting: a happy flutter, antennae curl back, a chime (overlay: wings / head / antennae; flaps in the air too)
    c = start(m, 'pet', 1.8, src='idle', at=0,
              bones=['h_head', 'right_antenna', 'left_antenna'] + [w for w, _ in WINGS])
    e = lambda t: env(t, 0.1, 1.65, 0.25)
    flutter(c, lambda t: 40, 7.0, e)
    R(c, 'right_antenna', lambda t: (30 * e(t), 0, -10 * e(t)))
    R(c, 'left_antenna', lambda t: (30 * e(t), 0, 10 * e(t)))
    R(c, 'h_head', lambda t: (10 * e(t), 0, 10 * osc(t, 1.0) * e(t)))
    c.particle(0.5, SPARK, 2, 0.3, 0.02, 0.7)
    out.append(c)

    c = overlay_from(m, 'pet_sit', 1.6, 'rest', 0, ('h_head', 'right_antenna', 'left_antenna'))
    e2 = lambda t: env(t, 0.1, 1.45, 0.25)
    R(c, 'right_antenna', lambda t: (28 * e2(t), 0, -10 * e2(t)))
    R(c, 'left_antenna', lambda t: (28 * e2(t), 0, 10 * e2(t)))
    R(c, 'h_head', lambda t: (10 * e2(t), 0, 10 * osc(t, 1.0) * e2(t)))
    out.append(c)

    # bond level-up: spirals up in a burst of glimmer, wings blazing, settles back
    c = start(m, 'level_up', 2.8, src='hover')
    up = lambda t: bump(t, 0.2, 2.5)
    P(c, 'root', lambda t: (3 * osc(t, 1.0) * up(t), 9 * up(t), 3 * osc(t, 1.0, 0.25) * up(t)))
    R(c, 'root', lambda t: (0, 720 * step(t, 0.2, 2.4), 0))
    flutter(c, lambda t: 20, 5.0, lambda t: env(t, 0.2, 2.5))
    S(c, 'glow_antenna_right', lambda t: (0.8 * jolt(t, 0.8, 2.7),) * 3)
    S(c, 'glow_antenna_left', lambda t: (0.8 * jolt(t, 0.8, 2.7),) * 3)
    c.particle(0.9, SPARK, 8, 0.5, 0.05, 0.6)
    c.particle(1.3, DUST, 10, 0.6, 0.02, 0.6)
    c.sound(0.3, FLAP, 0.4, 1.4)
    c.sound(0.9, CHIME, 0.4, 1.33)
    c.sound(1.1, CHIME, 0.4, 1.68)
    c.sound(1.3, CHIME, 0.4, 2.0)
    out.append(c)

    # --- resting (sitting) variants ---------------------------------------------------------------------------------
    c = start(m, 'rest_fan', 6.0, 'loop', src='rest', at=0)
    for w, s in WINGS:
        a, b = rest.get((w, 'rotation'), (0, 0, 0)), idle.get((w, 'rotation'), (0, 0, 0))
        R(c, w, lambda t, a=a, b=b: tuple((b[i] - a[i]) * 0.55 * (bump(t, 1.0, 2.4) + bump(t, 3.6, 5.0)) for i in range(3)))
    R(c, 'right_antenna', lambda t: (6 * osc(t, 1 / 3.0), 0, 0))
    R(c, 'left_antenna', lambda t: (6 * osc(t, 1 / 3.0, 0.3), 0, 0))
    out.append(c)

    c = start(m, 'rest_antenna', 4.0, 'loop', src='rest', at=0)
    R(c, 'right_antenna', lambda t: (-16 * bump(t, 0.4, 0.8) + 10 * osc(t, 0.5) * env(t, 1.5, 3.6), 0, -8 * bump(t, 0.4, 0.8)))
    R(c, 'left_antenna', lambda t: (-16 * bump(t, 2.4, 2.8) + 10 * osc(t, 0.5, 0.3) * env(t, 0.2, 2.2), 0, 8 * bump(t, 2.4, 2.8)))
    R(c, 'h_head', lambda t: (0, 14 * osc(t, 0.25), 0))
    out.append(c)

    # --- sleeping variants ---------------------------------------------------------------------------------------
    c = start(m, 'sleep_glow', 8.0, 'loop', src='sleep')
    S(c, 'glow_antenna_right', lambda t: (-0.2 + 0.2 * osc(t, 0.25),) * 3)
    S(c, 'glow_antenna_left', lambda t: (-0.2 + 0.2 * osc(t, 0.25),) * 3)
    S(c, 'body', lambda t: (0.02 * osc(t, 0.25),) * 3)
    out.append(c)

    c = start(m, 'sleep_quiver', 8.0, 'loop', src='sleep')
    q = lambda t: env(t, 3.0, 3.8, 0.12)
    for w, s in WINGS[:2]:
        R(c, w, lambda t, s=s: (0, 0, s * 4 * osc(t, 12) * q(t)))
    R(c, 'right_antenna', lambda t: (8 * bump(t, 6.0, 6.4), 0, 0))
    out.append(c)
    return out


TAGS = {
    'ground_rest': {'p': 'standing'}, 'hover_shimmer': {'p': 'standing'},
    'pet_sit': {'p': 'sitting'},
    'rest_fan': {'p': 'sitting'}, 'rest_antenna': {'p': 'sitting'},
    'sleep_glow': {'p': 'lying'}, 'sleep_quiver': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'ground_rest', 'w': 4, 'min': 6.4, 'max': 19.2, 'cd': 15, 'when': 'on_ground'},
        {'clip': 'wing_flutter', 'w': 3, 'cd': 14, 'when': 'on_ground'},
        {'clip': 'antenna_twitch', 'w': 3, 'cd': 12},
        {'clip': 'antenna_clean', 'w': 2, 'cd': 30, 'when': 'on_ground'},
        {'clip': 'dust_shake', 'w': 2, 'cd': 30, 'when': 'on_ground', 'boost': {'rain': 3}},
        {'clip': 'hover_shimmer', 'w': 4, 'min': 4.8, 'max': 14.4, 'cd': 15, 'when': 'in_air', 'boost': {'night': 2}},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},
        {'clip': 'rest_fan', 'w': 3, 'min': 6, 'max': 12, 'cd': 20, 'boost': {'day': 1.5}},
        {'clip': 'rest_antenna', 'w': 3, 'min': 4, 'max': 12, 'cd': 15},
    ],
    'lying': [
        {'clip': 'sleep_glow', 'w': 2, 'min': 24, 'max': 64},
        {'clip': 'sleep_quiver', 'w': 1, 'min': 8, 'max': 16, 'cd': 40},
    ],
}

# antennae twitch like ears; no tail
PROCEDURAL = {'ears': ['right_antenna', 'left_antenna'], 'ear_twitch': 1.5, 'breath': 0.01}
