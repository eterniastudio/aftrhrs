"""Hollow Pilgrim: a hooded skeleton walking the soul sand valleys with a soul lantern. Solemn rather than twitchy:
it stands with its head bowed and swaying, lifts its lantern high to peer into the dark, turns its hooded head slowly
toward whoever approaches, and walks in a measured procession step."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, env, keys, loop, once, pulse, undead_tags, wave  # noqa: E402

TAGS = undead_tags(base_once=('twitch', 'lantern_raise', 'look_around'), walks=('walk_procession',))


def bow_head(m, L=8.0):
    """Loop: head bowed low in mourning, the body swaying very slowly, lantern hanging still."""
    c = loop(m, 'bow_head', L)
    c.rot('h_head', lambda t: (-22 + 2 * wave(t, L, 1), 3 * wave(t, L, 1, 0.3), 2 * wave(t, L, 1)), add=True)
    c.rot('body', lambda t: (-4, 0, 2.5 * wave(t, L, 1)), add=True)
    c.rot('arm_l', lambda t: (10, 0, 6), add=True)
    c.rot('forearm_l', lambda t: (40, 0, 0), add=True)
    c.rot('lantern', lambda t: (0, 0, -2.5 * wave(t, L, 1, 0.1)), add=True)
    return c


def lantern_raise(m, L=3.6):
    """Lifts the soul lantern up before its face and peers into the dark, then lowers it."""
    c = once(m, 'lantern_raise', L)
    up = lambda t: keys(t, [(0, 0), (0.7, 1), (2.7, 1), (L, 0)])
    c.rot('arm_r', lambda t: (75 * up(t), 0, -10 * up(t)), add=True)
    c.rot('forearm_r', lambda t: (25 * up(t), 0, 0), add=True)
    c.rot('lantern', lambda t: (-100 * up(t) + 6 * wave(t, 1.2) * up(t), 0, 4 * wave(t, 1.7) * up(t)), add=True)
    c.rot('h_head', lambda t: (10 * up(t), keys(t, [(0, 0), (0.8, 0), (1.5, 18), (2.3, -14), (L, 0)]), 0), add=True)
    c.rot('body', lambda t: (4 * up(t), 0, 0), add=True)
    c.sound(0.4, 'minecraft:block.chain.step', 0.35, 0.9)
    c.sound(1.0, 'minecraft:particle.soul_escape', 0.5, 0.8)
    return c


def turn_gaze(m, L=3.0):
    """Notice (overlay): the hooded head turns slowly to the newcomer (the look follows) and tilts."""
    from animkit import Clip
    c = Clip(m, 'turn_gaze', L, 'once')
    e = lambda t: env(t, 0.0, L, 0.9, 0.8)
    c.rot('h_head', lambda t: (2 * e(t), 0, 12 * e(t)))
    c.sound(0.3, 'minecraft:ambient.soul_sand_valley.mood', 0.15, 1.3)
    return c


def clips(m):
    w = Walker(m, crack=('minecraft:entity.skeleton.step', 0.4, 0.7))
    return [w.idle_sway(L=8.0, amt=0.5), bow_head(m), lantern_raise(m), turn_gaze(m), w.twitch(), w.look_around(),
            w.walk_variant('walk_procession', 1.6, stride=14, lurch=2, bob=0.4, lean=8, arms=None, sway=2, head_bob=1.5), hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 5, 'max': 12},
        {'clip': 'bow_head', 'w': 3, 'min': 8, 'max': 18},
        {'clip': 'idle_sway', 'w': 2, 'min': 8, 'max': 16},
        {'clip': 'lantern_raise', 'w': 2, 'cd': 15},
        {'clip': 'look_around', 'w': 1, 'cd': 14},
        {'clip': 'twitch', 'w': 0.5, 'cd': 20},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'walk_procession', 'w': 2, 'min': 8, 'max': 20},
    ],
    'notice': [{'clip': 'turn_gaze', 'w': 1}],
}
