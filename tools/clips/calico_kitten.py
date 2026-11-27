"""Calico Kitten: all the shared cat body language (clips/_cats.py) at kitten scale and tempo (quicker, bigger,
bouncier), and kitten play: the arched sideways crab hop, rearing up to bat at nothing, rolling on its back to
bicycle its paws, and bigger pounces. It kneads its crouching owner (its Regeneration II perk) with biscuits."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS)
PROCEDURAL = {'tail_sway': 9, 'ear_twitch': 1.6, 'look_yaw': 65, 'look_pitch': 40}


def style(m):
    return C.CatStyle(m, s=0.6, k=0.85, a=1.25, tail=1.2)


def clips(m):
    st = style(m)
    out = C.common(st, kitten=True)
    extra = [C.pet(st), C.happy(st), C.eat(st), C.tame(st), C.level_up(st, hops=3, twist=36), C.biscuits(st),
             C.kitten_crab(st), C.kitten_bat(st), C.kitten_roll(st)]
    for c in extra:
        m.put(c)
    return out + extra


def IDLE(m):
    return C.idle_tables(style(m), extra_standing=[
        {'clip': 'kitten_crab', 'w': 1.2, 'cd': 30, 'when': '!night', 'boost': {'happy': 2, 'player_near': 1.5}},
        {'clip': 'kitten_bat', 'w': 1.2, 'cd': 30, 'when': '!night', 'boost': {'happy': 2}},
        {'clip': 'kitten_roll', 'w': 1.0, 'cd': 35, 'when': '!night', 'boost': {'happy': 2}},
        {'clip': 'biscuits', 'w': 0.6, 'cd': 25, 'boost': {'owner_crouching': 8, 'owner_near': 1.5}},
    ], lazy=0.6, playful=2.0)
