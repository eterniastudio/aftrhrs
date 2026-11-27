"""Bilgebones: a seaweed-draped drowned sailor skeleton with a bow. Still on deck in its bones: it sways to a swell
that isn't there, shades its eye socket to scan the horizon, chatters its jaw, rattles; walks with a sailor's rolling
gait."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, env, keys, loop, once, pulse, undead_tags, wave  # noqa: E402

TAGS = undead_tags(base_once=('twitch', 'spyglass', 'look_around'), walks=('walk_roll',))


def sea_sway(m, L=5.0):
    """Loop: riding a swell: the whole frame rolls side to side from spread feet, head counter-rolling to stay level."""
    c = loop(m, 'sea_sway', L)
    r = lambda t: 7 * wave(t, L, 1) + 1.5 * wave(t, L, 2, 0.25)
    c.rot('body', lambda t: (2 * wave(t, L, 2), 0, r(t)), add=True)
    c.rot('h_head', lambda t: (0, 4 * wave(t, L, 1, 0.3), -r(t - 0.4) * 1.1), add=True)
    c.rot('leg_l', lambda t: (0, 0, -6 - r(t) * 0.6), add=True)
    c.rot('leg_r', lambda t: (0, 0, 6 - r(t) * 0.6), add=True)
    c.rot('arm_r', lambda t: (0, 0, -r(t - 0.3) * 0.8), add=True)
    c.rot('arm_l', lambda t: (0, 0, -r(t - 0.3) * 0.5), add=True)
    c.sound(1.2, 'minecraft:block.bamboo_wood.step', 0.2, 0.6)
    c.sound(3.7, 'minecraft:block.bamboo_wood.step', 0.2, 0.55)
    return c


def jaw_clack(m, L=1.4):
    """Jaw only (overlay): a dry chatter of teeth."""
    from animkit import Clip
    c = Clip(m, 'jaw_clack', L, 'once')
    c.rot('jaw', lambda t: (-14 * max(0, wave(t, 0.14)) * env(t, 0.0, L, 0.1, 0.3), 0, 0))
    c.rot('h_head', lambda t: (4 * env(t, 0.0, L, 0.2, 0.4), 0, 3 * wave(t, 0.28) * env(t, 0, L)))
    for t0 in (0.1, 0.4, 0.7):
        c.sound(t0, 'minecraft:entity.skeleton.step', 0.35, 1.8)
    return c


def spyglass(m, L=3.6):
    """The free hand goes up to shade the eye sockets; it scans the horizon slowly, left to right."""
    c = once(m, 'spyglass', L)
    up = lambda t: env(t, 0.0, L, 0.45, 0.5)
    c.rot('arm_r', lambda t: (145 * up(t), 0, -8 * up(t)), add=True)
    c.rot('forearm_r', lambda t: (72 * up(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (6 * up(t), keys(t, [(0, 0), (0.5, 0), (1.3, 30), (2.3, -30), (3.0, -30), (L, 0)]), 0), add=True)
    c.rot('body', lambda t: (3 * up(t), 0, 0), add=True)
    return c


def clips(m):
    w = Walker(m, crack=('minecraft:entity.skeleton.step', 0.5, 0.8))
    return [sea_sway(m), w.idle_sway(amt=0.6), w.twitch(), w.sniff(), w.look_around(), jaw_clack(m), spyglass(m),
            w.walk_variant('walk_roll', 1.1, stride=30, lurch=10, bob=1.0, lean=0, arms=None, sway=6, head_bob=3), hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'sea_sway', 'w': 3, 'min': 6, 'max': 14},
        {'clip': 'idle_sway', 'w': 1, 'min': 6, 'max': 12},
        {'clip': 'spyglass', 'w': 2, 'cd': 15},
        {'clip': 'jaw_clack', 'w': 2, 'cd': 8},
        {'clip': 'twitch', 'w': 1.5, 'cd': 10},
        {'clip': 'look_around', 'w': 1, 'cd': 12},
    ],
    'walk': [
        {'clip': '@mood', 'w': 1.5, 'min': 6, 'max': 16},
        {'clip': 'walk_roll', 'w': 2.5, 'min': 8, 'max': 20},
    ],
    'notice': [{'clip': 'sniff', 'w': 1}, {'clip': 'jaw_clack', 'w': 1}],
}
