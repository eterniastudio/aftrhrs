"""Fumetail (Creatures 2, Nether): a warped-forest skunk with a warped fungus on its plume. Curious, a little
pompous; the plume is its flag. Signature moves: the skunk handstand warning (front paws planted, rump and plume up),
the plume fluff (spores puff off the fungus), the forager's dig in the nylium. Sitting: nose twitch, plume sway.
Sleeping: tucked under the plume."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _smalla_kit import Out, P, R, S, bump, env, osc, start  # noqa: E402


def clips(m):
    out = Out(m)

    # handstand warning: stamps, then rocks onto its front paws with the rump and plume high
    c = start(m, 'handstand', 3.0)
    st = lambda t: env(t, 0.9, 2.6, 0.35)  # noqa: E731
    R(c, 'left_front_leg', lambda t: (-14 * bump(t, 0.15, 0.4) + 38 * st(t), 0, 0))
    R(c, 'right_front_leg', lambda t: (-14 * bump(t, 0.45, 0.7) + 38 * st(t), 0, 0))
    R(c, 'body', lambda t: (-38 * st(t), 0, 0))
    P(c, 'body', lambda t: (0, 3.0 * st(t), -1.2 * st(t)))
    for leg in ('left_back_leg', 'right_back_leg'):
        R(c, leg, lambda t: (20 * st(t), 0, 0))
    R(c, 'h_head', lambda t: (38 * st(t), 0, 0))
    R(c, 'tail', lambda t: (-20 * st(t), 0, 0))
    R(c, 'tail_mid', lambda t: (0, 14 * osc(t, 2.0) * st(t), 0))
    c.sound(0.2, 'minecraft:block.wool.step', 0.5, 1.4)
    c.sound(0.5, 'minecraft:block.wool.step', 0.5, 1.4)
    c.sound(1.3, 'minecraft:entity.cat.hiss', 0.35, 1.5)
    out.append(c)

    # plume fluff: shakes the plume, spores puff off the fungus
    c = start(m, 'plume_fluff', 2.2)
    sh = lambda t: env(t, 0.3, 1.8, 0.2)  # noqa: E731
    R(c, 'tail', lambda t: (-8 * sh(t), 10 * osc(t, 5.0) * sh(t), 0))
    R(c, 'tail_mid', lambda t: (0, 16 * osc(t, 5.0, 0.15) * sh(t), 6 * osc(t, 5.0) * sh(t)))
    R(c, 'tail_tip', lambda t: (0, 20 * osc(t, 5.0, 0.3) * sh(t), 0))
    R(c, 'fungus', lambda t: (0, 0, 14 * osc(t, 5.0, 0.4) * sh(t)))
    R(c, 'h_head', lambda t: (0, 60 * env(t, 0.1, 2.0, 0.3), 0))
    c.particle(1.0, 'minecraft:warped_spore', 10, 0.4, 0.02, 1.2)
    c.sound(0.5, 'minecraft:block.fungus.step', 0.5, 1.3)
    out.append(c)

    # forage: noses into the nylium and digs with a front paw
    c = start(m, 'forage', 3.0)
    dn = lambda t: env(t, 0.2, 2.8, 0.3)  # noqa: E731
    R(c, 'h_head', lambda t: (-30 * dn(t) + 4 * osc(t, 3.0) * dn(t), 0, 0))
    R(c, 'body', lambda t: (-8 * dn(t), 0, 0))
    R(c, 'left_front_leg', lambda t: (24 * (bump(t, 0.7, 1.0) + bump(t, 1.1, 1.4) + bump(t, 1.5, 1.8)), 0, 0))
    R(c, 'tail', lambda t: (-6 * dn(t), 10 * osc(t, 1.0) * dn(t), 0))
    for t0 in (0.8, 1.2, 1.6):
        c.sound(t0, 'minecraft:block.nylium.hit', 0.4, 1.3)
    c.sound(2.2, 'minecraft:entity.fox.sniff', 0.4, 1.4)
    out.append(c)

    # sitting: nose twitch, plume sway
    c = start(m, 'sit_twitch', 4.0, 'loop', src='sit', at='end')
    S(c, 'h_head', lambda t: (0, 0, 0.04 * osc(t, 6.0) * (env(t, 0.5, 1.2, 0.1) + env(t, 2.5, 3.0, 0.1))))
    R(c, 'tail_tip', lambda t: (0, 8 * osc(t, 0.25), 0))
    R(c, 'h_head', lambda t: (0, 18 * osc(t, 0.25, 0.25), 0))
    out.append(c)

    # sleeping: tucked under the plume, breathing slow
    c = start(m, 'sleep_tucked', 8.0, 'loop', src='sleep')
    S(c, 'body', lambda t: (0.02 * osc(t, 1 / 4), 0.03 * osc(t, 1 / 4), 0.02 * osc(t, 1 / 4)))
    R(c, 'tail_mid', lambda t: (0, 4 * osc(t, 1 / 8), 0))
    R(c, 'left_ear', lambda t: (0, 0, 14 * bump(t, 3.0, 3.25)))
    out.append(c)
    return out


TAGS = {'sit_twitch': {'p': 'sitting'}, 'sleep_tucked': {'p': 'lying'}}

IDLE = {
    'standing': [
        {'clip': 'handstand', 'w': 2, 'cd': 40, 'boost': {'monster_near': 4, 'player_near:6': 1.5}},
        {'clip': 'plume_fluff', 'w': 3, 'cd': 20, 'boost': {'rain': 3}},
        {'clip': 'forage', 'w': 3, 'cd': 25},
    ],
    'sitting': [
        {'clip': 'sit_twitch', 'w': 3, 'min': 8, 'max': 16, 'cd': 15},
    ],
    'lying': [
        {'clip': 'sleep_tucked', 'w': 2, 'min': 20, 'max': 60},
    ],
}

PROCEDURAL = {'tail_sway': 4, 'ear_twitch': 1.2, 'breath': 0.014}
