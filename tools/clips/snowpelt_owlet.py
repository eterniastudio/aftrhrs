"""Snowpelt Owlet (phase 2): a frost owlet on night watch. Signature moves: the owl head swivel (looks right behind
itself and back), the curious owl head-bob, fluffing its feathers into a snowball, wing stretches, two-footed hops,
a watchful scan at night. Sitting: swivels, dozes upright (head droops, snaps up; eyes stay open), preens under a
wing. Sleeping: head tucked back into its feathers, shivery fluffs."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step  # noqa: E402

FLAP = 'minecraft:entity.parrot.fly'
SNOW = 'minecraft:block.powder_snow.step'


def wings(c, z, fore=None, tip=None):
    """Both wings out by z(t) degrees (left +, right -), forearm / tip follow-through."""
    R(c, 'left_wing', lambda t: (0, 0, z(t)))
    R(c, 'right_wing', lambda t: (0, 0, -z(t)))
    if fore:
        R(c, 'left_wing_fore', lambda t: (0, 0, fore(t)))
        R(c, 'right_wing_fore', lambda t: (0, 0, -fore(t)))
    if tip:
        R(c, 'left_wing_tip', lambda t: (0, 0, tip(t)))
        R(c, 'right_wing_tip', lambda t: (0, 0, -tip(t)))


def clips(m):
    out = Out(m)

    # --- standing ----------------------------------------------------------------------------------------------
    # the owl swivel: head turns right round to look behind, holds, sweeps through to the other side, snaps home
    c = start(m, 'head_swivel', 3.4)
    yaw = lambda t: (150 * step(t, 0.2, 0.9) - 250 * step(t, 1.5, 2.3) + 100 * step(t, 2.6, 2.85)
                     + 10 * (bump(t, 2.85, 3.1)))
    R(c, 'h_head', lambda t: (4 * env(t, 0.2, 3.0), yaw(t), 6 * bump(t, 0.9, 1.5)))
    for e, s in (('left_ear_tuft', 1), ('right_ear_tuft', -1)):
        R(c, e, lambda t, s=s: (0, 0, s * 10 * env(t, 0.2, 3.0)))
    c.sound(1.1, 'ambient', 0.5, 1.0)
    out.append(c)

    # curious head-bob: the owl's side-to-side parallax bob and deep tilts
    c = start(m, 'head_bob', 2.4)
    e = lambda t: env(t, 0.15, 2.25, 0.3)
    P(c, 'h_head', lambda t: (1.4 * osc(t, 1.25) * e(t), 0.8 * osc(t, 2.5, 0.25) * e(t), 0))
    R(c, 'h_head', lambda t: (8 * e(t), 0, 30 * bump(t, 0.3, 1.0) - 34 * bump(t, 1.2, 2.0)))
    for ee, s in (('left_ear_tuft', 1), ('right_ear_tuft', -1)):
        S(c, ee, lambda t: (0, 0.25 * e(t), 0))
    out.append(c)

    # fluff up: puffs into a snowball, shivers the snow off, settles slim again
    c = start(m, 'fluff_up', 2.6)
    puff = lambda t: step(t, 0.2, 0.6) * (1 - step(t, 1.7, 2.4))
    S(c, 'body', lambda t: (0.2 * puff(t), 0.14 * puff(t), 0.2 * puff(t)))
    R(c, 'body', lambda t: (0, 0, 7 * osc(t, 11) * env(t, 0.7, 1.5, 0.1)))
    R(c, 'h_head', lambda t: (-5 * puff(t), 0, -6 * osc(t, 11) * env(t, 0.7, 1.5, 0.1)))
    P(c, 'h_head', lambda t: (0, -0.8 * puff(t), 0))
    wings(c, lambda t: 10 * puff(t) + 8 * osc(t, 11, 0.25) * env(t, 0.7, 1.5, 0.1))
    for ee, s in (('left_ear_tuft', 1), ('right_ear_tuft', -1)):
        S(c, ee, lambda t: (0, 0.3 * puff(t), 0))
    R(c, 'tail', lambda t: (-12 * puff(t), 0, 0))
    c.sound(0.3, FLAP, 0.4, 0.9)
    c.sound(0.75, SNOW, 0.6, 1.3)
    c.sound(1.15, SNOW, 0.5, 1.5)
    out.append(c)

    # wing stretch: one wing down and out, the other, then both high over its head with a little shake
    c = start(m, 'wing_stretch', 3.4)
    lw = lambda t: 85 * env(t, 0.2, 1.1, 0.3) + 120 * env(t, 2.1, 2.9, 0.25)
    rw = lambda t: 85 * env(t, 1.0, 1.9, 0.3) + 120 * env(t, 2.1, 2.9, 0.25)
    R(c, 'left_wing', lambda t: (-20 * env(t, 0.2, 1.1, 0.3), 0, lw(t) + 4 * osc(t, 9) * env(t, 2.3, 2.8, 0.1)))
    R(c, 'right_wing', lambda t: (-20 * env(t, 1.0, 1.9, 0.3), 0, -rw(t) - 4 * osc(t, 9) * env(t, 2.3, 2.8, 0.1)))
    R(c, 'left_wing_fore', lambda t: (0, 0, -25 * env(t, 0.3, 1.0, 0.3) - 30 * env(t, 2.15, 2.85, 0.25)))
    R(c, 'right_wing_fore', lambda t: (0, 0, 25 * env(t, 1.1, 1.8, 0.3) + 30 * env(t, 2.15, 2.85, 0.25)))
    R(c, 'body', lambda t: (0, 0, -8 * env(t, 0.2, 1.1, 0.3) + 8 * env(t, 1.0, 1.9, 0.3)))
    R(c, 'h_head', lambda t: (10 * env(t, 2.1, 2.9), 25 * env(t, 0.25, 1.05) - 25 * env(t, 1.05, 1.85), 0))
    R(c, 'left_leg', lambda t: (-25 * env(t, 0.3, 1.0, 0.3), 0, 0))
    R(c, 'right_leg', lambda t: (-25 * env(t, 1.1, 1.8, 0.3), 0, 0))
    c.sound(0.35, FLAP, 0.3, 1.1)
    c.sound(1.15, FLAP, 0.3, 1.0)
    c.sound(2.25, FLAP, 0.45, 0.9)
    out.append(c)

    # two-footed hops with wing-flaps for balance
    c = start(m, 'hop_hop', 2.0)
    hop = lambda t: bump(t, 0.25, 0.7) + 0.8 * bump(t, 0.95, 1.4)
    squash = lambda t: bump(t, 0.12, 0.3) + bump(t, 0.68, 0.98) + bump(t, 1.38, 1.6)
    P(c, 'root', lambda t: (0, 4 * hop(t), 0))
    S(c, 'body', lambda t: (0.08 * squash(t), -0.1 * squash(t) + 0.06 * hop(t), 0.08 * squash(t)))
    wings(c, lambda t: 55 * (bump(t, 0.3, 0.55) + bump(t, 1.0, 1.25)), tip=lambda t: -20 * (bump(t, 0.35, 0.6) + bump(t, 1.05, 1.3)))
    for leg in ('left_leg', 'right_leg'):
        R(c, leg, lambda t: (-20 * hop(t), 0, 0))
    R(c, 'h_head', lambda t: (-8 * squash(t), 0, 0))
    R(c, 'tail', lambda t: (12 * hop(t), 0, 0))
    c.sound(0.3, FLAP, 0.3, 1.2)
    c.sound(0.7, SNOW, 0.5, 1.4)
    c.sound(1.4, SNOW, 0.5, 1.5)
    out.append(c)

    # night watch: stands tall and scans the dark with long slow head sweeps (loop variant)
    c = start(m, 'night_watch', 6.0, 'loop')
    P(c, 'body', lambda t: (0, 0.6, 0))
    S(c, 'body', lambda t: (-0.04, 0.06, -0.04))
    R(c, 'h_head', lambda t: (6, 70 * (env(t, 0.5, 2.4, 0.5) - env(t, 3.3, 5.4, 0.5)), 0))
    for ee, s in (('left_ear_tuft', 1), ('right_ear_tuft', -1)):
        S(c, ee, lambda t: (0, 0.25, 0))
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    c = start(m, 'pet', 1.8)
    e = lambda t: env(t, 0.1, 1.65, 0.3)
    S(c, 'body', lambda t: (0.12 * e(t), 0.08 * e(t), 0.12 * e(t)))
    R(c, 'h_head', lambda t: (14 * e(t), 0, 18 * osc(t, 0.8) * e(t)))
    P(c, 'h_head', lambda t: (0, -0.5 * e(t), 0))
    for ee, s in (('left_ear_tuft', 1), ('right_ear_tuft', -1)):
        R(c, ee, lambda t, s=s: (25 * e(t), 0, -s * 15 * e(t)))
    R(c, 'tail_feather_l', lambda t: (0, 25 * e(t), 0))
    R(c, 'tail_feather_r', lambda t: (0, -25 * e(t), 0))
    wings(c, lambda t: 10 * e(t))
    out.append(c)

    c = overlay_from(m, 'pet_sit', 1.6, 'sit', 'end', ('h_head', 'left_ear_tuft', 'right_ear_tuft', 'tail_feather_l', 'tail_feather_r'))
    e2 = lambda t: env(t, 0.1, 1.45, 0.3)
    R(c, 'h_head', lambda t: (12 * e2(t), 0, 18 * osc(t, 0.8) * e2(t)))
    for ee, s in (('left_ear_tuft', 1), ('right_ear_tuft', -1)):
        R(c, ee, lambda t, s=s: (25 * e2(t), 0, -s * 15 * e2(t)))
    R(c, 'tail_feather_l', lambda t: (0, 25 * e2(t), 0))
    R(c, 'tail_feather_r', lambda t: (0, -25 * e2(t), 0))
    out.append(c)

    # bond level-up: a flutter-leap with wings fully spread, a proud hoot, ruffle
    c = start(m, 'level_up', 2.8)
    air = lambda t: bump(t, 0.35, 1.5)
    P(c, 'root', lambda t: (0, 7 * air(t), 0))
    wings(c, lambda t: 70 * env(t, 0.3, 1.55, 0.15) + 35 * osc(t, 5) * env(t, 0.35, 1.5, 0.1),
          fore=lambda t: -15 * osc(t, 5, -0.15) * env(t, 0.35, 1.5, 0.1),
          tip=lambda t: -25 * osc(t, 5, -0.3) * env(t, 0.35, 1.5, 0.1))
    for leg in ('left_leg', 'right_leg'):
        R(c, leg, lambda t: (-25 * air(t), 0, 0))
    R(c, 'h_head', lambda t: (22 * bump(t, 1.6, 2.4), 0, 0))
    S(c, 'body', lambda t: (0.15 * bump(t, 1.6, 2.6),) * 3)
    R(c, 'tail_feather_l', lambda t: (0, 30 * env(t, 0.3, 2.5), 0))
    R(c, 'tail_feather_r', lambda t: (0, -30 * env(t, 0.3, 2.5), 0))
    for t0 in (0.4, 0.6, 0.8, 1.0, 1.2):
        c.sound(t0, FLAP, 0.35, 1.0)
    c.sound(1.7, 'ambient', 0.9, 1.1)
    out.append(c)

    # --- sitting variants ----------------------------------------------------------------------------------------
    c = start(m, 'sit_swivel', 6.0, 'loop', src='sit', at='end')
    R(c, 'h_head', lambda t: (0, 120 * env(t, 0.4, 2.3, 0.5) - 110 * env(t, 3.2, 5.4, 0.5), 0))
    out.append(c)

    c = start(m, 'sit_doze', 6.0, 'loop', src='sit', at='end')
    droop = lambda t: step(t, 0.5, 2.6) * (1 - step(t, 2.75, 2.9)) + 0.6 * step(t, 3.6, 5.2) * (1 - step(t, 5.35, 5.5))
    R(c, 'h_head', lambda t: (-24 * droop(t), 0, 10 * droop(t)))
    P(c, 'h_head', lambda t: (0, -0.8 * droop(t), 0))
    S(c, 'body', lambda t: (0.04 * droop(t),) * 3)
    for ee, s in (('left_ear_tuft', 1), ('right_ear_tuft', -1)):
        R(c, ee, lambda t, s=s: (12 * droop(t), 0, -s * 10 * droop(t)))
    out.append(c)

    c = start(m, 'sit_preen', 4.0, 'loop', src='sit', at='end')
    pr = lambda t: env(t, 0.4, 3.4, 0.45)
    R(c, 'h_head', lambda t: (-28 * pr(t) + 6 * osc(t, 4) * env(t, 0.9, 3.0, 0.15), 75 * pr(t), 0))
    R(c, 'left_wing', lambda t: (0, 0, 28 * pr(t)))
    R(c, 'left_wing_tip', lambda t: (0, 0, -10 * pr(t)))
    R(c, 'body', lambda t: (0, 10 * pr(t), -6 * pr(t)))
    out.append(c)

    # --- sleeping variants ---------------------------------------------------------------------------------------
    c = start(m, 'sleep_tuck', 6.4, 'loop', src='sleep')
    R(c, 'h_head', lambda t: (6, 0, -10))
    R(c, 'h_head', lambda t: (0, 145, 0))
    S(c, 'body', lambda t: (0.03 * osc(t, 1 / 3.2),) * 3)
    out.append(c)

    c = start(m, 'sleep_puff', 6.4, 'loop', src='sleep')
    sh = lambda t: env(t, 3.0, 3.8, 0.15)
    S(c, 'body', lambda t: (0.08 * bump(t, 2.8, 4.4),) * 3)
    R(c, 'body', lambda t: (0, 0, 3 * osc(t, 10) * sh(t)))
    R(c, 'left_ear_tuft', lambda t: (0, 0, -14 * bump(t, 1.2, 1.5)))
    R(c, 'right_ear_tuft', lambda t: (0, 0, 14 * bump(t, 5.0, 5.3)))
    out.append(c)
    return out


TAGS = {
    'pet_sit': {'p': 'sitting'},
    'sit_swivel': {'p': 'sitting'}, 'sit_doze': {'p': 'sitting'}, 'sit_preen': {'p': 'sitting'},
    'sleep_tuck': {'p': 'lying'}, 'sleep_puff': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'head_swivel', 'w': 3, 'cd': 25, 'boost': {'monster_near': 2, 'night': 1.5}},
        {'clip': 'head_bob', 'w': 3, 'cd': 18, 'boost': {'owner_near': 1.5}},
        {'clip': 'fluff_up', 'w': 2, 'cd': 30, 'boost': {'cold': 2, 'rain': 2}},
        {'clip': 'wing_stretch', 'w': 2, 'cd': 40, 'boost': {'dawn': 3}},
        {'clip': 'hop_hop', 'w': 2, 'cd': 25, 'boost': {'happy': 2}},
        {'clip': 'night_watch', 'w': 2, 'min': 6, 'max': 18, 'cd': 30, 'boost': {'night': 3}},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},
        {'clip': 'sit_swivel', 'w': 3, 'min': 6, 'max': 12, 'cd': 18, 'boost': {'night': 2}},
        {'clip': 'sit_doze', 'w': 2, 'min': 6, 'max': 12, 'cd': 30, 'when': '!night'},
        {'clip': 'sit_preen', 'w': 2, 'min': 4, 'max': 8, 'cd': 25},
    ],
    'lying': [
        {'clip': 'sleep_tuck', 'w': 2, 'min': 20, 'max': 60},
        {'clip': 'sleep_puff', 'w': 1, 'min': 6.4, 'max': 12.8, 'cd': 40, 'boost': {'cold': 2}},
    ],
}

# owls turn their heads far: a wider look range; small tail, the ear tufts twitch
PROCEDURAL = {'look_yaw': 100, 'look_pitch': 40, 'tail_sway': 3, 'ear_twitch': 1.2, 'breath': 0.016}
