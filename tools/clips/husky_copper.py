"""Copper Husky: the same rig and the same husky personality as the Husky (talking, night howls, zoomies), voiced
with the grumpy wolf set."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dogs as K  # noqa: E402
import husky as H  # noqa: E402


def clips(m):
    d = K.Dog(m, voice='entity.wolf_grumpy', vp=0.95, wag='y', ears='up')
    out = K.shared(d)
    for fn in (H.talk, H.sit_howl, H.zoom_laps):
        c = fn(d)
        m.put(c)
        out.append(c)
    return out


TAGS = H.TAGS
IDLE = H.IDLE
PROCEDURAL = H.PROCEDURAL
