"""Maine Coon: its old `sit` was a loaf (legs tucked, body flat). It gets a real cat sit like the other cats (front up,
haunches down, front legs straight, tail wrapped); the old loaf stays as `loaf` (held pose) and is the pose of its
`sit_loaf` loop. On top: the shared cat body language (clips/_cats.py) at a big-cat tempo, and its signatures: the
chirp (Maine Coons are chirpers) and the night warden's sitting lookout (it never naps: no_nap)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402
from animkit import Clip, held_pose, posture_set  # noqa: E402

TAGS = dict(C.TAGS, loaf={'p': 'lying'}, idle_cold={'p': 'lying'}, idle_scared={'p': 'lying'})
PROCEDURAL = {'tail_sway': 6, 'ear_twitch': 1.2, 'look_yaw': 62}


def style(m):
    return C.CatStyle(m, s=2.0, k=1.15, a=0.9, tail=0.9)


def clips(m):
    loaf = held_pose(m, 'loaf', m.pose('sit', 'end'), 1.0)
    m.put(loaf)
    sit = Clip(m, 'sit', 1.0, 'hold')
    sit.rot('body', lambda t: (26, 0, 0))
    sit.pos('body', lambda t: (0, -2.1, 0))
    sit.rot('h_head', lambda t: (-22, 0, 0))
    sit.rot('left_front_leg', lambda t: (-26, 0, 0))
    sit.rot('right_front_leg', lambda t: (-26, 0, 0))
    sit.pos('left_front_leg', lambda t: (0, -0.3, 0.2))
    sit.pos('right_front_leg', lambda t: (0, -0.3, 0.2))
    sit.rot('left_back_leg', lambda t: (72, 0, 0))
    sit.rot('right_back_leg', lambda t: (72, 0, 0))
    sit.pos('left_back_leg', lambda t: (0, 0.4, 0))
    sit.pos('right_back_leg', lambda t: (0, 0.4, 0))
    sit.rot('left_ear', lambda t: (-4, 0, 0))
    sit.rot('right_ear', lambda t: (-4, 0, 0))
    sit.rot('tail', lambda t: (14, 80, 0))
    sit.rot('tail_mid', lambda t: (18, 55, 0))
    sit.rot('tail_tip', lambda t: (40, 45, 0))
    m.put(sit)
    out = [loaf, sit] + posture_set(m)
    for c in out[2:]:
        m.put(c)
    st = style(m)
    life = C.common(st, sit_loaf_pose=m.pose('loaf', 0))
    extra = [C.pet(st), C.happy(st), C.eat(st), C.tame(st), C.level_up(st, twist=20), C.biscuits(st),
             C.claw_scratch(st), C.chirp(st)]
    for c in extra:
        m.put(c)
    return out + life + extra


def IDLE(m):
    t = C.idle_tables(style(m), extra_standing=[
        {'clip': 'chirp', 'w': 2.0, 'cd': 14, 'boost': {'owner_near': 1.5}},
        {'clip': 'claw_scratch', 'w': 0.8, 'cd': 50},
        {'clip': 'biscuits', 'w': 0.5, 'cd': 70, 'boost': {'owner_near': 2}},
    ], extra_sitting=[
        {'clip': 'chirp', 'w': 0},
        # the night warden: on watch at night rather than loafing
        {'clip': 'sit_look', 'w': 2, 'min': 5.6 * 1.15, 'max': 11.2 * 1.15, 'cd': 10, 'boost': {'night': 3}},
    ])
    return t
