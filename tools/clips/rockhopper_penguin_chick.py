"""Rockhopper Penguin Chick (winter update): every clip authored by the shared penguin kit (clips/_penguin.py); the source bbmodel has
none. Clumsy: a wobbly waddle, topples over, begs with flapping flippers."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _penguin as K  # noqa: E402

PARAMS = {'S': 0.5, 'lift': 1.9, 'tall': 3.8}


def clips(m):
    return K.chick_clips(m, PARAMS)


TAGS = K.chick_tags()
IDLE = K.chick_idle()
PROCEDURAL = {'tail_sway': 3, 'breath': 0.016}
