"""Emberkit (phase 2): a fiery, curious fox kit. Its tail tip is a flame (tail_tip): it flares when the kit is excited,
dims when it sleeps. Signature moves: the fox mousing pounce, sniffing along an ember trail, batting at a floating
ember, flaring its tail flame and admiring it. Sitting: flame-curl, ear scratch, looking around. Sleeping: curled with
the flame low, dreaming twitches."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step  # noqa: E402

LEGS = ('left_front_leg', 'right_front_leg', 'left_back_leg', 'right_back_leg')


def clips(m):
    out = Out(m)

    # --- standing ----------------------------------------------------------------------------------------------
    # mousing pounce: crouch + rear wiggle, spring straight up, nose-dive onto the "mouse", pop up proud
    c = start(m, 'pounce', 2.5)
    crouch = lambda t: step(t, 0.05, 0.45) * (1 - step(t, 0.85, 0.98))
    air = lambda t: bump(t, 0.9, 1.45)
    dive = lambda t: step(t, 1.12, 1.4) * (1 - step(t, 1.55, 2.0))
    P(c, 'root', lambda t: (0, -1.3 * crouch(t) + 7.5 * air(t) + 2.6 * dive(t), -1.5 * air(t)))
    R(c, 'body', lambda t: (-7 * crouch(t) + 30 * bump(t, 0.85, 1.2) - 30 * dive(t),
                            7 * osc(t, 4.0) * env(t, 0.35, 0.85, 0.12), 0))
    R(c, 'h_head', lambda t: (-12 * crouch(t) - 14 * bump(t, 0.85, 1.2) - 10 * dive(t) + 22 * bump(t, 1.85, 2.4),
                              14 * osc(t, 1.4) * env(t, 1.85, 2.45, 0.2), 0))
    for leg, s in (('left_front_leg', 1), ('right_front_leg', 1)):
        R(c, leg, lambda t, s=s: (12 * crouch(t) - 25 * bump(t, 0.85, 1.15) + 55 * dive(t), 0, 0))
    for leg in ('left_back_leg', 'right_back_leg'):
        R(c, leg, lambda t: (-10 * crouch(t) - 50 * bump(t, 0.95, 1.4) + 20 * dive(t), 0, 0))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t: (-18 * crouch(t) + 25 * air(t), 0, s * 6 * dive(t)))
    R(c, 'tail', lambda t: (-20 * air(t) + 8 * crouch(t), 22 * osc(t, 4.0) * env(t, 0.35, 0.85, 0.12) + 25 * osc(t, 3.0) * env(t, 1.6, 2.4, 0.2), 0))
    S(c, 'tail_tip', lambda t: (0.45 * jolt(t, 1.35, 2.3),) * 3)
    R(c, 'jaw', lambda t: (-20 * bump(t, 1.3, 1.55), 0, 0))
    c.sound(0.4, 'minecraft:entity.fox.sniff', 0.5, 1.2)
    c.sound(0.95, 'minecraft:item.firecharge.use', 0.18, 1.9)
    c.sound(1.4, 'minecraft:entity.fox.bite', 0.6, 1.25)
    c.sound(1.95, 'minecraft:block.campfire.crackle', 0.9, 1.3)
    out.append(c)

    # sniffing along an ember trail (loop variant): nose down, sweeping, snuffling in bursts
    c = start(m, 'sniff_trail', 5.0, 'loop')
    R(c, 'body', lambda t: (-6, 4 * osc(t, 1 / 2.5), 0))
    P(c, 'body', lambda t: (0, -0.5, 0))
    R(c, 'h_head', lambda t: (-30 + 5 * osc(t, 5.0) * (env(t, 0.4, 1.2, 0.1) + env(t, 2.8, 3.6, 0.1)),
                              22 * osc(t, 1 / 2.5), 6 * osc(t, 1 / 2.5, 0.25)))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t, s=s: (-12 + 8 * osc(t, 1 / 1.2, 0.25 * s), 0, 0))
    R(c, 'tail', lambda t: (-6, 12 * osc(t, 1 / 1.2), 0))
    for t0 in (0.5, 0.85, 3.0, 3.35):
        c.sound(t0, 'minecraft:entity.fox.sniff', 0.45, 1.25)
    out.append(c)

    # tail flame flare: lifts its tail, the flame whooshes up, it turns to admire it, wags
    c = start(m, 'flame_flare', 2.6)
    up = lambda t: step(t, 0.2, 0.55) * (1 - step(t, 2.0, 2.5))
    R(c, 'tail', lambda t: (-28 * up(t), 20 * osc(t, 2.5) * env(t, 1.0, 2.2, 0.25), 0))
    S(c, 'tail_tip', lambda t: ((0.65 * jolt(t, 0.45, 1.6) + 0.12 * osc(t, 7) * env(t, 0.5, 2.0, 0.2)),) * 3)
    R(c, 'h_head', lambda t: (8 * bump(t, 0.5, 1.0), 70 * env(t, 0.6, 1.9, 0.35), 18 * env(t, 0.7, 1.8, 0.3)))
    R(c, 'body', lambda t: (0, 16 * env(t, 0.6, 1.9, 0.35), 0))
    P(c, 'root', lambda t: (0, 1.5 * bump(t, 0.4, 0.7), 0))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t: (-14 * env(t, 0.5, 1.9, 0.2), 0, 0))
    c.sound(0.5, 'minecraft:item.firecharge.use', 0.3, 1.6)
    c.sound(0.6, 'minecraft:block.fire.ambient', 0.5, 1.4)
    c.sound(1.5, 'minecraft:entity.fox.ambient', 0.5, 1.3)
    out.append(c)

    # batting at a floating ember: watches it drift down, rears up and swats left, right
    c = start(m, 'ember_bat', 2.6)
    rear = lambda t: step(t, 0.7, 0.95) * (1 - step(t, 1.8, 2.2))
    R(c, 'body', lambda t: (26 * rear(t), 0, 0))
    P(c, 'root', lambda t: (0, 1.4 * rear(t), 0))
    R(c, 'h_head', lambda t: (35 * (1 - step(t, 0.1, 0.7)) * step(t, 0.0, 0.25) + 18 * env(t, 0.1, 2.1, 0.3) - 18 * rear(t) * 0.5,
                              25 * osc(t, 0.8) * env(t, 0.0, 0.8, 0.2), 0))
    R(c, 'left_front_leg', lambda t: (60 * bump(t, 0.95, 1.3) + 15 * rear(t), 0, -10 * bump(t, 0.95, 1.3)))
    R(c, 'right_front_leg', lambda t: (60 * bump(t, 1.25, 1.6) + 15 * rear(t), 0, 10 * bump(t, 1.25, 1.6)))
    for leg in ('left_back_leg', 'right_back_leg'):
        R(c, leg, lambda t: (-22 * rear(t), 0, 0))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t: (-15 * env(t, 0.1, 2.0, 0.3), 0, 0))
    R(c, 'tail', lambda t: (10 * rear(t), 18 * osc(t, 2) * env(t, 0.6, 2.2, 0.2), 0))
    c.sound(1.1, 'minecraft:block.campfire.crackle', 0.7, 1.5)
    c.sound(1.4, 'minecraft:block.campfire.crackle', 0.7, 1.7)
    c.sound(2.15, 'minecraft:block.sand.step', 0.4, 1.3)
    out.append(c)

    # curious head tilts (one, then the other way), ears perked
    c = start(m, 'head_tilt', 2.2)
    R(c, 'h_head', lambda t: (6 * env(t, 0.2, 1.9), 0, 26 * bump(t, 0.2, 0.95) - 26 * bump(t, 1.0, 1.8)))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t, s=s: (-16 * env(t, 0.15, 1.95), 0, s * 8 * bump(t, 0.2, 0.95)))
    R(c, 'tail', lambda t: (0, 10 * osc(t, 1.5) * env(t, 0.3, 2.0), 0))
    c.sound(0.35, 'minecraft:entity.fox.ambient', 0.35, 1.5)
    out.append(c)

    # tail flicks: the flame tail swishes in big lazy arcs and crackles (loop variant)
    c = start(m, 'tail_flick', 5.0, 'loop')
    R(c, 'tail', lambda t: (-10 - 6 * osc(t, 0.4, 0.25), 32 * osc(t, 0.4), 0))
    R(c, 'tail_tip', lambda t: (0, 26 * osc(t, 0.4, -0.12), 0))
    S(c, 'tail_tip', lambda t: (0.1 * osc(t, 3.0) + 0.08,) * 3)
    R(c, 'left_ear', lambda t: (14 * bump(t, 1.1, 1.35), 0, 0))
    R(c, 'right_ear', lambda t: (14 * bump(t, 3.1, 3.35), 0, 0))
    c.sound(1.0, 'minecraft:block.campfire.crackle', 0.5, 1.3)
    c.sound(3.0, 'minecraft:block.campfire.crackle', 0.5, 1.45)
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    # petting: pushes its head up into the hand, ears flattened in bliss, tail wags, the flame glows up
    c = start(m, 'pet', 1.8)
    R(c, 'h_head', lambda t: (18 * env(t, 0.1, 1.6, 0.3), 0, 14 * osc(t, 0.9) * env(t, 0.2, 1.6, 0.3)))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t, s=s: (40 * env(t, 0.1, 1.6, 0.25), 0, s * 10 * env(t, 0.1, 1.6, 0.25)))
    P(c, 'root', lambda t: (0, 0.5 * env(t, 0.1, 1.6, 0.3), 0))
    R(c, 'tail', lambda t: (-8 * env(t, 0.1, 1.7), 28 * osc(t, 3.0) * env(t, 0.1, 1.7, 0.2), 0))
    S(c, 'tail_tip', lambda t: (0.3 * env(t, 0.2, 1.6, 0.3),) * 3)
    out.append(c)

    # petting while it stays: head and ears over the sit pose (overlay)
    c = overlay_from(m, 'pet_sit', 1.6, 'sit', 'end', ('h_head', 'left_ear', 'right_ear', 'tail_tip'))
    R(c, 'h_head', lambda t: (16 * env(t, 0.1, 1.45, 0.3), 0, 12 * osc(t, 0.9) * env(t, 0.15, 1.45, 0.3)))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t, s=s: (38 * env(t, 0.1, 1.45, 0.25), 0, s * 10 * env(t, 0.1, 1.45, 0.25)))
    S(c, 'tail_tip', lambda t: (0.3 * env(t, 0.2, 1.45, 0.3),) * 3)
    out.append(c)

    # bond level-up: a spinning leap, a big flame burst and a happy yip, two bounces
    c = start(m, 'level_up', 2.8)
    P(c, 'root', lambda t: (0, 6 * bump(t, 0.35, 1.05) + 2.5 * bump(t, 1.9, 2.2) + 2 * bump(t, 2.25, 2.5), 0))
    R(c, 'root', lambda t: (0, 360 * step(t, 0.4, 1.0), 0))
    R(c, 'body', lambda t: (-6 * bump(t, 0.2, 0.4) + 10 * bump(t, 0.4, 0.9), 0, 0))
    R(c, 'h_head', lambda t: (30 * bump(t, 1.15, 1.75), 0, 0))
    R(c, 'jaw', lambda t: (-24 * bump(t, 1.25, 1.65), 0, 0))
    for leg in LEGS:
        s = 1 if 'front' in leg else -1
        R(c, leg, lambda t, s=s: (s * 30 * bump(t, 0.4, 1.0) + s * 20 * (bump(t, 1.9, 2.2) + bump(t, 2.25, 2.5)), 0, 0))
    S(c, 'tail_tip', lambda t: (0.8 * jolt(t, 1.1, 2.6),) * 3)
    R(c, 'tail', lambda t: (-25 * env(t, 1.0, 2.6), 25 * osc(t, 3.0) * env(t, 1.6, 2.7, 0.2), 0))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t: (30 * bump(t, 0.4, 1.0) - 12 * env(t, 1.1, 2.6), 0, 0))
    c.sound(0.4, 'minecraft:item.firecharge.use', 0.25, 1.8)
    c.sound(1.2, 'minecraft:entity.blaze.shoot', 0.25, 1.9)
    c.sound(1.3, 'minecraft:entity.fox.screech', 0.35, 1.5)
    c.sound(1.9, 'minecraft:block.sand.step', 0.5, 1.3)
    out.append(c)

    # --- sitting variants (loops on the sit pose) ---------------------------------------------------------------
    c = start(m, 'sit_flame_curl', 4.0, 'loop', src='sit', at='end')
    S(c, 'tail_tip', lambda t: (0.12 * osc(t, 1.5) + 0.05,) * 3)
    R(c, 'tail_tip', lambda t: (0, 10 * osc(t, 0.5), 0))
    R(c, 'h_head', lambda t: (-6 - 6 * osc(t, 0.25, 0.25), -18 * osc(t, 0.25), 0))
    R(c, 'left_ear', lambda t: (10 * bump(t, 2.2, 2.5), 0, 0))
    out.append(c)

    c = start(m, 'sit_scratch', 3.2, 'loop', src='sit', at='end')
    sc = lambda t: env(t, 0.4, 2.6, 0.35)
    R(c, 'right_back_leg', lambda t: (35 * sc(t) + 14 * osc(t, 6.0) * env(t, 0.7, 2.3, 0.15), 0, 22 * sc(t)))
    R(c, 'h_head', lambda t: (-12 * sc(t), -18 * sc(t), -22 * sc(t) + 3 * osc(t, 6.0) * env(t, 0.7, 2.3, 0.15)))
    R(c, 'right_ear', lambda t: (20 * sc(t), 0, 0))
    R(c, 'body', lambda t: (0, 0, -6 * sc(t)))
    out.append(c)

    c = start(m, 'sit_look', 5.0, 'loop', src='sit', at='end')
    look = lambda t: 40 * (env(t, 0.4, 2.0, 0.45) - env(t, 2.7, 4.5, 0.45))
    R(c, 'h_head', lambda t: (6 * env(t, 0.4, 4.5, 0.5), look(t), 0))
    for ear, s in (('left_ear', 1), ('right_ear', -1)):
        R(c, ear, lambda t, s=s: (-10 * env(t, 0.4, 4.5, 0.5), 0, s * 6 * osc(t, 0.4) * env(t, 0.4, 4.5)))
    out.append(c)

    # --- sleeping variants (loops on the sleep pose; eyes stay shut) --------------------------------------------
    c = start(m, 'sleep_curl', 6.0, 'loop', src='sleep')
    S(c, 'body', lambda t: (0.025 * osc(t, 1 / 3), 0.035 * osc(t, 1 / 3), 0.025 * osc(t, 1 / 3)))
    S(c, 'tail_tip', lambda t: (-0.12 + 0.06 * osc(t, 1 / 6),) * 3)
    R(c, 'h_head', lambda t: (-3 + 2 * osc(t, 1 / 3, -0.1), 0, 0))
    out.append(c)

    c = start(m, 'sleep_dream', 6.0, 'loop', src='sleep')
    tw = lambda t: osc(t, 9.0) * (env(t, 0.8, 1.4, 0.1) + env(t, 2.6, 3.0, 0.1))
    R(c, 'left_front_leg', lambda t: (8 * tw(t), 0, 0))
    R(c, 'right_front_leg', lambda t: (-8 * tw(t), 0, 0))
    R(c, 'left_ear', lambda t: (-18 * bump(t, 1.9, 2.15), 0, 0))
    S(c, 'tail_tip', lambda t: (0.12 * bump(t, 2.6, 3.2) - 0.05,) * 3)
    R(c, 'jaw', lambda t: (-6 * bump(t, 3.2, 3.6), 0, 0))
    out.append(c)
    return out


TAGS = {
    'pet_sit': {'p': 'sitting'},
    'sit_flame_curl': {'p': 'sitting'}, 'sit_scratch': {'p': 'sitting'}, 'sit_look': {'p': 'sitting'},
    'sleep_curl': {'p': 'lying'}, 'sleep_dream': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'pounce', 'w': 3, 'cd': 30, 'boost': {'happy': 2, 'night': 0.5}},
        {'clip': 'sniff_trail', 'w': 3, 'min': 5, 'max': 10, 'cd': 25},
        {'clip': 'flame_flare', 'w': 2, 'cd': 35, 'boost': {'cold': 2, 'night': 1.5}},
        {'clip': 'ember_bat', 'w': 2, 'cd': 40, 'boost': {'happy': 1.5}},
        {'clip': 'head_tilt', 'w': 2, 'cd': 20, 'boost': {'owner_near': 2}},
        {'clip': 'tail_flick', 'w': 2, 'min': 5, 'max': 10, 'cd': 20},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},   # a reaction, not an idle
        {'clip': 'sit_flame_curl', 'w': 3, 'min': 8, 'max': 16, 'cd': 20, 'boost': {'cold': 2, 'night': 1.5}},
        {'clip': 'sit_scratch', 'w': 2, 'min': 3.2, 'max': 3.2, 'cd': 30},
        {'clip': 'sit_look', 'w': 3, 'min': 5, 'max': 10, 'cd': 18, 'boost': {'monster_near': 3}},
    ],
    'lying': [
        {'clip': 'sleep_curl', 'w': 2, 'min': 20, 'max': 60, 'boost': {'cold': 2}},
        {'clip': 'sleep_dream', 'w': 1, 'min': 8, 'max': 16, 'cd': 40},
    ],
}

PROCEDURAL = {'tail_sway': 7, 'ear_twitch': 1.3, 'breath': 0.014}
