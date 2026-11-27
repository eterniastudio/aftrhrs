"""Soulmander (Creatures 2, Nether): a soul-fire newt of the soul sand valley. Slinky and watchful; its three crest
flames, crown flame and tail flame are its moods. Signature moves: basking with the crest fanned, tasting the air with
quick head bobs, stoking its tail flame by curling it round to its nose. Sitting: crest flicker, slow look. Sleeping:
flames burn low, a dreaming tail twitch."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _smalla_kit import Out, P, R, S, bump, env, osc, start  # noqa: E402

FLAMES = ('crest', 'crown_flame', 'tail_flame')


def clips(m):
    out = Out(m)

    # bask: flattens onto the warm soul sand, fans the crest, flames breathe up and down
    c = start(m, 'bask', 6.0, 'loop')
    P(c, 'body', lambda t: (0, -0.6, 0))
    R(c, 'h_head', lambda t: (4 + 3 * osc(t, 1 / 3), 0, 0))
    for i, b in enumerate(FLAMES):
        S(c, b, lambda t, i=i: (0.12 + 0.06 * osc(t, 1 / 3, 0.1 * i), 0.3 + 0.15 * osc(t, 1 / 3, 0.1 * i),
                                0.12 + 0.06 * osc(t, 1 / 3, 0.1 * i)))
    R(c, 'tail', lambda t: (0, 10 * osc(t, 1 / 6), 0))
    c.sound(1.0, 'minecraft:block.fire.ambient', 0.4, 1.5)
    c.sound(4.0, 'minecraft:particle.soul_escape', 0.6, 1.2)
    out.append(c)

    # taste the air: quick head bobs with the jaw ajar, side to side
    c = start(m, 'taste_air', 2.4)
    e = lambda t: env(t, 0.2, 2.2, 0.25)  # noqa: E731
    R(c, 'h_head', lambda t: (10 * e(t) + 6 * osc(t, 4.0) * e(t), 25 * osc(t, 0.5) * e(t), 0))
    R(c, 'jaw', lambda t: (-8 * (bump(t, 0.5, 0.8) + bump(t, 1.2, 1.5) + bump(t, 1.8, 2.1)), 0, 0))
    R(c, 'body', lambda t: (3 * e(t), 6 * osc(t, 0.5) * e(t), 0))
    c.sound(0.6, 'minecraft:entity.frog.ambient', 0.3, 1.7)
    out.append(c)

    # stoke the tail flame: curls the tail round to its nose and puffs on it; the flame flares
    c = start(m, 'stoke_flame', 3.0)
    cu = lambda t: env(t, 0.2, 2.7, 0.45)  # noqa: E731
    R(c, 'body', lambda t: (0, -16 * cu(t), 0))
    R(c, 'tail', lambda t: (0, -40 * cu(t), 0))
    R(c, 'tail_mid', lambda t: (0, -45 * cu(t), 0))
    R(c, 'tail_tip', lambda t: (0, -40 * cu(t), 0))
    R(c, 'h_head', lambda t: (-6 * cu(t), 48 * cu(t), 0))
    R(c, 'jaw', lambda t: (-10 * bump(t, 1.1, 1.4), 0, 0))
    S(c, 'tail_flame', lambda t: (0.4 * bump(t, 1.2, 2.2), 0.9 * bump(t, 1.2, 2.2), 0.4 * bump(t, 1.2, 2.2)))
    c.sound(1.2, 'minecraft:item.firecharge.use', 0.25, 1.8)
    c.sound(1.3, 'minecraft:particle.soul_escape', 0.8, 1.0)
    out.append(c)

    # sitting: the crest flickers in turn, slow look round
    c = start(m, 'sit_flicker', 4.0, 'loop', src='sit', at='end')
    for i, b in enumerate(FLAMES):
        S(c, b, lambda t, i=i: (0, 0.25 * bump((t + 0.6 * i) % 4.0, 0.5, 1.5), 0))
    R(c, 'h_head', lambda t: (0, -22 * osc(t, 0.25), 0))
    out.append(c)

    # sleeping: a dreaming tail twitch, flames pulse low
    c = start(m, 'sleep_dream', 8.0, 'loop', src='sleep')
    R(c, 'tail_tip', lambda t: (0, 16 * osc(t, 7.0) * env(t, 2.0, 2.8, 0.1), 0))
    R(c, 'left_front_leg', lambda t: (6 * osc(t, 8.0) * env(t, 4.0, 4.6, 0.1), 0, 0))
    for b in FLAMES:
        S(c, b, lambda t: (0, 0.15 * bump(t, 2.0, 3.0), 0))
    out.append(c)
    return out


TAGS = {'bask': {'p': 'standing'}, 'sit_flicker': {'p': 'sitting'}, 'sleep_dream': {'p': 'lying'}}

IDLE = {
    'standing': [
        {'clip': 'bask', 'w': 3, 'min': 8, 'max': 18, 'cd': 25, 'boost': {'hot': 2, 'cold': 2}},
        {'clip': 'taste_air', 'w': 3, 'cd': 14, 'boost': {'monster_near': 2}},
        {'clip': 'stoke_flame', 'w': 2, 'cd': 35, 'boost': {'cold': 2, 'rain': 2}},
    ],
    'sitting': [
        {'clip': 'sit_flicker', 'w': 3, 'min': 8, 'max': 16, 'cd': 15},
    ],
    'lying': [
        {'clip': 'sleep_dream', 'w': 1, 'min': 8, 'max': 16, 'cd': 40},
    ],
}

PROCEDURAL = {'tail_sway': 6, 'breath': 0.014}
