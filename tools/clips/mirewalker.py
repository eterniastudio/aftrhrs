"""Mirewalker: a swamp zombie caked in dripping mud. Heavy and waterlogged: it sways where it stands, slumps, jerks
with a wet twitch, sniffs the air when someone comes near and drags one mud-stuck leg (or lurches) when it walks;
chasing, it lunges forward with its arms out."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, body_shake, slump, undead_tags  # noqa: E402

TAGS = undead_tags(base_once=('twitch', 'look_around', 'shake_off'), walks=('walk_drag', 'walk_lurch'))


def clips(m):
    w = Walker(m, crack=('minecraft:block.mud.step', 0.5, 0.7))
    return [w.idle_sway(), slump(w), w.twitch(), w.sniff(), w.look_around(),
            body_shake(w, sound=('minecraft:block.mud.hit', 0.4, 0.8)),
            w.walk_variant('walk_drag', 1.3, stride=26, drag='r', lurch=9, bob=1.0, lean=10),
            w.walk_variant('walk_lurch', 1.2, stride=30, lurch=12, bob=1.8, lean=14, sway=7, head_bob=8),
            w.run(0.8), hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'idle_sway', 'w': 3, 'min': 6, 'max': 14},
        {'clip': 'slump', 'w': 2, 'min': 6, 'max': 12},
        {'clip': 'twitch', 'w': 2, 'cd': 8},
        {'clip': 'look_around', 'w': 1.5, 'cd': 12},
        {'clip': 'shake_off', 'w': 1, 'cd': 25, 'boost': {'rain': 3}},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'walk_drag', 'w': 2, 'min': 8, 'max': 20},
        {'clip': 'walk_lurch', 'w': 1.5, 'min': 8, 'max': 20},
    ],
    'notice': [{'clip': 'sniff', 'w': 1}],
}
