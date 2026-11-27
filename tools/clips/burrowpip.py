"""Burrowpip (phase 2): a goggled mole with big pink digging paws and an amethyst nose. Mole life: furious digging
(paws scrabbling, dirt flying), sinking into the ground and popping back up, nose twitches with the crystal nose
glinting, pushing its goggles up and down, a sniffing patrol. Sitting: up on its haunches; sleeping: curled, or
half-burrowed with only its back showing."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step  # noqa: E402

DIG = 'minecraft:block.rooted_dirt.break'
DIRT = 'minecraft:block.rooted_dirt.step'
BRUSH = 'minecraft:item.brush.brushing.generic'
POP = 'minecraft:entity.armadillo.unroll_finish'
ASH = 'minecraft:ash'
SNIFF = 'minecraft:entity.sniffer.sniffing'
PAWS = (('left_paw', 1), ('right_paw', -1))


def twitch(c, e):
    """Nose twitch: the head quivers, the crystal nose throbs."""
    R(c, 'h_head', lambda t: (4 * osc(t, 8.0) * e(t), 0, 0))
    S(c, 'glow_nose_crystal', lambda t: (0.15 * (osc(t, 8.0) * 0.5 + 0.5) * e(t),) * 3)


def clips(m):
    out = Out(m)

    # --- standing ----------------------------------------------------------------------------------------------
    # digging: nose down, the big paws scrabble alternately, dirt flies back, a peek at the hole
    c = start(m, 'dig', 3.2)
    d = lambda t: env(t, 0.25, 2.4, 0.25)
    R(c, 'body', lambda t: (-14 * d(t), 0, 3 * osc(t, 3.5) * d(t)))
    P(c, 'body', lambda t: (0, -0.6 * d(t), 0))
    for paw, s in PAWS:
        R(c, paw, lambda t, s=s: (55 * d(t) * (0.5 + 0.5 * osc(t, 3.5, 0.5 * (s > 0))), 0, -s * 10 * d(t)))
    R(c, 'h_head', lambda t: (-10 * d(t) + 12 * bump(t, 2.4, 3.1), 0, 0))
    R(c, 'tail', lambda t: (25 * d(t), 18 * osc(t, 3.5) * d(t), 0))
    for t0 in (0.5, 0.95, 1.4, 1.85):
        c.sound(t0, DIG, 0.35, 1.3)
        c.particle(t0 + 0.05, ASH, 4, 0.3, 0.05, 0.2)
    out.append(c)

    # pop-up: digs straight down until only its back shows, waits, then pops out of the ground with a hop
    c = start(m, 'pop_up', 3.6)
    sink = lambda t: step(t, 0.3, 1.0) * (1 - step(t, 2.2, 2.45))
    P(c, 'root', lambda t: (0, -6.0 * sink(t) + 4.5 * bump(t, 2.25, 2.75), 0))
    R(c, 'body', lambda t: (-10 * bump(t, 0.2, 1.0) + 10 * bump(t, 2.25, 2.7), 0, 0))
    for paw, s in PAWS:
        R(c, paw, lambda t, s=s: (40 * env(t, 0.2, 1.0, 0.15) * (0.5 + 0.5 * osc(t, 5, 0.5 * (s > 0)))
                                  - 30 * bump(t, 2.25, 2.7), 0, -s * 15 * bump(t, 2.25, 2.7)))
    R(c, 'h_head', lambda t: (8 * bump(t, 2.3, 3.0), 30 * osc(t, 1.4) * env(t, 2.7, 3.5, 0.2), 0))
    S(c, 'body', lambda t: (0.08 * bump(t, 2.72, 2.95), -0.1 * bump(t, 2.72, 2.95), 0.08 * bump(t, 2.72, 2.95)))
    c.sound(0.35, DIG, 0.5, 1.1)
    c.sound(0.75, DIG, 0.5, 1.2)
    c.particle(0.5, ASH, 6, 0.35, 0.05, 0.1)
    c.sound(2.25, POP, 0.5, 1.5)
    c.sound(2.3, DIRT, 0.7, 1.2)
    c.particle(2.3, ASH, 8, 0.4, 0.08, 0.1)
    out.append(c)

    # nose twitch: sniff sniff, the amethyst nose glints (overlay)
    c = start(m, 'nose_twitch', 1.6, src='idle', at=0, bones=['h_head', 'glow_nose_crystal'])
    twitch(c, lambda t: env(t, 0.1, 0.6, 0.06) + env(t, 0.85, 1.45, 0.06))
    c.sound(0.15, SNIFF, 0.25, 1.6)
    out.append(c)

    # goggle adjust: a paw comes up, the goggles go up onto its brow, it blinks at the world (no blink: looks), back down
    c = start(m, 'goggle_adjust', 3.0)
    up = lambda t: step(t, 0.5, 0.8) * (1 - step(t, 2.1, 2.4))
    R(c, 'right_paw', lambda t: (-70 * (bump(t, 0.2, 0.95) + bump(t, 1.85, 2.6)), 0, 25 * (bump(t, 0.2, 0.95) + bump(t, 1.85, 2.6))))
    P(c, 'h_goggles', lambda t: (0, 1.6 * up(t), 0.5 * up(t)))
    R(c, 'h_goggles', lambda t: (-15 * up(t), 0, 0))
    R(c, 'h_head', lambda t: (6 * up(t), 30 * env(t, 0.9, 1.9, 0.3), 0))
    R(c, 'body', lambda t: (8 * (bump(t, 0.2, 0.95) + bump(t, 1.85, 2.6)), 0, 0))
    c.sound(0.7, 'minecraft:item.armor.equip_leather', 0.4, 1.4)
    c.sound(2.25, 'minecraft:item.armor.equip_leather', 0.4, 1.2)
    out.append(c)

    # sniff patrol (loop variant): nose to the ground, sweeping, twitching
    c = start(m, 'sniff_patrol', 6.0, 'loop')
    R(c, 'h_head', lambda t: (-14 + 6 * osc(t, 1 / 3.0, 0.25), 25 * osc(t, 1 / 6.0), 0))
    twitch(c, lambda t: env(t, 0.4, 1.4, 0.1) + env(t, 3.4, 4.4, 0.1))
    R(c, 'body', lambda t: (-5, 6 * osc(t, 1 / 6.0), 0))
    c.sound(0.5, SNIFF, 0.2, 1.5)
    c.sound(3.5, SNIFF, 0.2, 1.6)
    out.append(c)

    # paw clean: sits back and scrubs its big paws together (dirt off)
    c = start(m, 'paw_clean', 2.8)
    sb = lambda t: env(t, 0.25, 2.5, 0.3)
    R(c, 'body', lambda t: (16 * sb(t), 0, 0))
    P(c, 'body', lambda t: (0, 0.6 * sb(t), 0))
    for paw, s in PAWS:
        R(c, paw, lambda t, s=s: (-45 * sb(t), s * 25 * sb(t) + s * 12 * osc(t, 3.0) * env(t, 0.6, 2.2, 0.15), 0))
    R(c, 'h_head', lambda t: (-14 * sb(t), 0, 0))
    c.particle(1.2, ASH, 3, 0.25, 0.02, 0.4)
    c.sound(0.8, BRUSH, 0.3, 1.4)
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    c = start(m, 'pet', 2.0)
    e = lambda t: env(t, 0.1, 1.85, 0.3)
    R(c, 'h_head', lambda t: (10 * e(t), 0, 12 * osc(t, 0.8) * e(t)))
    S(c, 'body', lambda t: (0.06 * e(t), -0.05 * e(t), 0.06 * e(t)))
    R(c, 'tail', lambda t: (10 * e(t), 25 * osc(t, 3.0) * e(t), 0))
    S(c, 'glow_nose_crystal', lambda t: (0.25 * e(t),) * 3)
    for paw, s in PAWS:
        R(c, paw, lambda t, s=s: (-15 * e(t), 0, -s * 8 * e(t)))
    out.append(c)

    c = overlay_from(m, 'pet_sit', 1.8, 'sit', 'end', ('h_head', 'tail', 'glow_nose_crystal'))
    e2 = lambda t: env(t, 0.1, 1.65, 0.3)
    R(c, 'h_head', lambda t: (10 * e2(t), 0, 12 * osc(t, 0.8) * e2(t)))
    R(c, 'tail', lambda t: (10 * e2(t), 25 * osc(t, 3.0) * e2(t), 0))
    S(c, 'glow_nose_crystal', lambda t: (0.25 * e2(t),) * 3)
    out.append(c)

    # bond level-up: dives into the ground and bursts out in a shower of dirt, paws up, goggles flying up
    c = start(m, 'level_up', 3.0)
    sink = lambda t: step(t, 0.15, 0.6) * (1 - step(t, 1.0, 1.15))
    P(c, 'root', lambda t: (0, -5.5 * sink(t) + 7 * bump(t, 1.05, 1.75), 0))
    R(c, 'root', lambda t: (0, 360 * step(t, 1.05, 1.75), 0))
    for paw, s in PAWS:
        R(c, paw, lambda t, s=s: (-60 * env(t, 1.0, 2.6, 0.25), 0, -s * 30 * env(t, 1.0, 2.6, 0.25)))
    P(c, 'h_goggles', lambda t: (0, 1.6 * env(t, 1.0, 2.5, 0.2), 0.4 * env(t, 1.0, 2.5, 0.2)))
    S(c, 'glow_nose_crystal', lambda t: (0.5 * jolt(t, 1.05, 2.8),) * 3)
    R(c, 'tail', lambda t: (0, 30 * osc(t, 3.0) * env(t, 1.7, 2.9), 0))
    c.sound(0.2, DIG, 0.6, 1.0)
    c.sound(1.05, POP, 0.7, 1.4)
    c.sound(1.1, 'minecraft:block.amethyst_block.chime', 0.8, 1.2)
    c.particle(1.1, ASH, 12, 0.5, 0.1, 0.1)
    c.particle(1.2, 'minecraft:end_rod', 4, 0.4, 0.05, 0.5)
    out.append(c)

    # --- sitting (haunches) variants ------------------------------------------------------------------------------
    c = start(m, 'sit_twitch', 4.0, 'loop', src='sit', at='end')
    twitch(c, lambda t: env(t, 0.3, 1.1, 0.08) + env(t, 2.3, 3.1, 0.08))
    R(c, 'h_head', lambda t: (0, 25 * osc(t, 0.25), 0))
    out.append(c)

    c = start(m, 'sit_paws', 4.0, 'loop', src='sit', at='end')
    g = lambda t: env(t, 0.4, 3.6, 0.4)
    for paw, s in PAWS:
        R(c, paw, lambda t, s=s: (-30 * g(t), s * 18 * g(t) + s * 10 * osc(t, 2.5) * g(t), 0))
    R(c, 'h_head', lambda t: (-10 * g(t), 0, 0))
    out.append(c)

    c = start(m, 'sit_goggles', 6.0, 'loop', src='sit', at='end')
    gg = lambda t: env(t, 0.6, 5.0, 0.4)
    P(c, 'h_goggles', lambda t: (0, 1.6 * gg(t), 0.5 * gg(t)))
    R(c, 'h_goggles', lambda t: (-15 * gg(t), 0, 0))
    R(c, 'h_head', lambda t: (6 * gg(t), 20 * osc(t, 1 / 6.0, 0.25) * gg(t), 0))
    out.append(c)

    # --- sleeping variants (eyes shut) -----------------------------------------------------------------------------
    c = start(m, 'sleep_curl', 6.0, 'loop', src='sleep')
    S(c, 'body', lambda t: (0.02 * osc(t, 1 / 3.0),) * 3)
    S(c, 'glow_nose_crystal', lambda t: (-0.1 + 0.08 * osc(t, 1 / 6.0),) * 3)
    out.append(c)

    c = start(m, 'sleep_burrowed', 6.0, 'loop', src='sleep')   # snug, half dug in
    P(c, 'root', lambda t: (0, -2.5, 0))
    R(c, 'h_head', lambda t: (-6, 0, 0))
    twitch(c, lambda t: env(t, 3.0, 3.6, 0.08))
    out.append(c)
    return out


TAGS = {
    'pet_sit': {'p': 'sitting'},
    'sit_twitch': {'p': 'sitting'}, 'sit_paws': {'p': 'sitting'}, 'sit_goggles': {'p': 'sitting'},
    'sleep_curl': {'p': 'lying'}, 'sleep_burrowed': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'dig', 'w': 3, 'cd': 25, 'boost': {'owner_near': 1.5}},
        {'clip': 'pop_up', 'w': 2, 'cd': 40, 'boost': {'happy': 2}},
        {'clip': 'nose_twitch', 'w': 4, 'cd': 8},
        {'clip': 'goggle_adjust', 'w': 2, 'cd': 35},
        {'clip': 'paw_clean', 'w': 2, 'cd': 30, 'boost': {'rain': 2}},
        {'clip': 'sniff_patrol', 'w': 3, 'min': 6, 'max': 18, 'cd': 15},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},
        {'clip': 'sit_twitch', 'w': 3, 'min': 4, 'max': 12, 'cd': 12},
        {'clip': 'sit_paws', 'w': 2, 'min': 4, 'max': 8, 'cd': 25},
        {'clip': 'sit_goggles', 'w': 2, 'min': 6, 'max': 12, 'cd': 30, 'boost': {'day': 1.5}},
    ],
    'lying': [
        {'clip': 'sleep_curl', 'w': 2, 'min': 18, 'max': 48},
        {'clip': 'sleep_burrowed', 'w': 2, 'min': 18, 'max': 48, 'boost': {'cold': 2}},
    ],
}

PROCEDURAL = {'tail_sway': 6, 'breath': 0.016, 'look_yaw': 45}
