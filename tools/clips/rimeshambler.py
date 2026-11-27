"""Rimeshambler: a frost-rimed zombie of the snowfields. Its joints are frozen: it shivers in place, cracks with a
sharp icy twitch, walks stiff-legged with short steps (or drags a frozen leg), sniffs for warm blood and lunges when
it chases."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, shiver, slump, undead_tags  # noqa: E402

TAGS = undead_tags(base_once=('twitch', 'look_around'), walks=('walk_stiff', 'walk_drag'))


def clips(m):
    w = Walker(m, crack=('minecraft:block.glass.hit', 0.35, 1.6))
    return [w.idle_sway(amt=0.7), shiver(w), slump(w), w.twitch(), w.sniff(), w.look_around(),
            w.walk_variant('walk_stiff', 0.9, stride=16, lurch=11, bob=0.5, lean=4, sway=2, head_bob=2),
            w.walk_variant('walk_drag', 1.3, stride=24, drag='l', lurch=8, bob=1.0, lean=8),
            w.run(0.85, stride=34), hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'shiver', 'w': 3, 'min': 4, 'max': 10, 'boost': {'night': 1.5}},
        {'clip': 'idle_sway', 'w': 2, 'min': 6, 'max': 12},
        {'clip': 'slump', 'w': 1, 'min': 6, 'max': 12},
        {'clip': 'twitch', 'w': 2.5, 'cd': 7},
        {'clip': 'look_around', 'w': 1.5, 'cd': 12},
    ],
    'walk': [
        {'clip': '@mood', 'w': 1.5, 'min': 6, 'max': 14},
        {'clip': 'walk_stiff', 'w': 2.5, 'min': 8, 'max': 20},
        {'clip': 'walk_drag', 'w': 1.5, 'min': 8, 'max': 18},
    ],
    'notice': [{'clip': 'sniff', 'w': 1}],
}
