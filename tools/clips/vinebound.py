"""Vinebound: a jungle zombie overgrown with vines. Dormant it is a still leafy mound that only breathes and rustles
(and shivers when a player comes near, before it rises at 4 blocks). Risen, it sways, twitches, shakes its leaves out,
sniffs and drags a root-bound leg."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, body_shake, decay, env, loop, once, undead_tags, wave  # noqa: E402

TAGS = undead_tags(base_once=('twitch', 'look_around', 'leaf_shake'), walks=('walk_drag', 'walk_lurch'),
                   lying=('dormant_rustle', 'dormant_stir'))
LEAVES = ('leaves', 'leaf_l', 'leaf_r')


def dormant_rustle(m, L=6.0):
    """The mound breathes: the leaves lift and settle, a slow ripple runs through them."""
    c = loop(m, 'dormant_rustle', L, 'dormant')
    c.scl('body', lambda t: (1 + 0.015 * wave(t, L, 2), 1 + 0.03 * wave(t, L, 2), 1), add=True)
    for i, b in enumerate(LEAVES):
        c.rot(b, (lambda i: lambda t: (4 * wave(t, L, 2, 0.15 * i), 0, 5 * wave(t, L, 3, 0.2 * i)))(i), add=True)
    c.sound(1.0, 'minecraft:block.azalea_leaves.step', 0.15, 0.8)
    return c


def dormant_stir(m, L=1.8):
    """Notice while dormant: a shudder runs through the mound, the head shifts under the leaves."""
    c = once(m, 'dormant_stir', L, 'dormant')
    sh = lambda t: decay(t, 0.1, 5.0, 2.5)
    for b in LEAVES:
        c.rot(b, lambda t: (6 * sh(t), 0, 10 * sh(t - 0.05)), add=True)
    c.rot('body', lambda t: (0, 0, 3 * sh(t)), add=True)
    c.rot('h_head', lambda t: (8 * env(t, 0.3, L, 0.3, 0.6), 10 * env(t, 0.3, L, 0.3, 0.6), 0), add=True)
    c.sound(0.1, 'minecraft:block.azalea_leaves.break', 0.35, 0.7)
    return c


def clips(m):
    w = Walker(m, crack=('minecraft:block.azalea_leaves.break', 0.3, 1.2))
    return [w.idle_sway(), w.twitch(), w.sniff(), w.look_around(),
            body_shake(w, 'leaf_shake', sound=('minecraft:block.azalea_leaves.step', 0.5, 1.0), extra=LEAVES),
            w.walk_variant('walk_drag', 1.3, stride=26, drag='l', lurch=9, bob=1.0, lean=10),
            w.walk_variant('walk_lurch', 1.2, stride=30, lurch=11, bob=1.6, lean=13, sway=7),
            w.run(0.8), dormant_rustle(m), dormant_stir(m), hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'idle_sway', 'w': 3, 'min': 6, 'max': 14},
        {'clip': 'twitch', 'w': 2, 'cd': 8},
        {'clip': 'look_around', 'w': 1.5, 'cd': 12},
        {'clip': 'leaf_shake', 'w': 1.5, 'cd': 20},
    ],
    'dormant': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'dormant_rustle', 'w': 3, 'min': 8, 'max': 20},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'walk_drag', 'w': 2, 'min': 8, 'max': 20},
        {'clip': 'walk_lurch', 'w': 1.5, 'min': 8, 'max': 20},
    ],
    'notice': [
        {'clip': 'dormant_stir', 'w': 1, 'when': 'var:dormant'},
        {'clip': 'sniff', 'w': 1, 'when': '!var:dormant'},
    ],
}
