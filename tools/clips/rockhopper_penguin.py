"""Rockhopper Penguin (winter update): every clip authored by the shared penguin kit (clips/_penguin.py); the source bbmodel has
none. Bouncy and brash: hops with both feet together, crest plumes trail every move, a crest-shaking bray."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _penguin as K  # noqa: E402

PARAMS = {'S': 0.75, 'lift': 1.5, 'tall': 6.5, 'call': 'rockhopper', 'hop': True}


def clips(m):
    return K.adult_clips(m, PARAMS)


TAGS = K.adult_tags()
IDLE = K.adult_idle()
PROCEDURAL = {'tail_sway': 3, 'breath': 0.012}
