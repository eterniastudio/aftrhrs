"""Emperor Penguin (winter update): every clip authored by the shared penguin kit (clips/_penguin.py); the source bbmodel has
none. Slow and dignified: a heavy side-to-side waddle, the bow-and-trumpet call."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _penguin as K  # noqa: E402

PARAMS = {'S': 1.0, 'lift': 2.0, 'tall': 9.5, 'call': 'emperor'}


def clips(m):
    return K.adult_clips(m, PARAMS)


TAGS = K.adult_tags()
IDLE = K.adult_idle()
PROCEDURAL = {'tail_sway': 3, 'breath': 0.01}
