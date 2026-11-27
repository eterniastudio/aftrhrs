"""Kitty: 86 hand-made clips (bed buddy pillow hug, the camera boop, paw licks, the fang smirk, lion lounges, belly-up
pillow hugs, mane shakes, Maine Coon chirps, bunny kicks ...) plus the shared cat body language (clips/_cats.py)
played as a big gentle floof: a little slow, soft moves, a belly that jiggles, plenty of loafing.

Source: Documents/Blockbench/models/arcana/work/kitty/kitty.py + kitty_anims.py (the baked clips are already turn-free,
so nothing needs unspinning except the shared spin). New here: sitting loops with his lion-lounge sphinx, three
sleeping loops, a head-rub `pet_sit` while staying, a bond level-up that is one big fluffy hop."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS, paw_hug={'layer': 'base', 'move': False, 'p': 'sitting'},
            chin_up={'layer': 'base', 'move': False, 'p': 'sitting'},
            bed_hug={'p': 'lying'}, idle_lounge={'p': 'lying'}, idle_sunbeam={'p': 'lying'},
            idle_sleepy={'p': 'lying'}, idle_cold={'p': 'lying'}, idle_scared={'p': 'lying'})
PROCEDURAL = {'tail_sway': 6, 'ear_twitch': 1.1, 'breath': 0.02}


def style(m):
    return C.CatStyle(m, s=1.0, k=1.15, a=0.85, tail=1.1, chonk=0.8)


def clips(m):
    fixed = [C.unspin(m, n) for n in ('spin',) if m.has(n)]
    for c in fixed:
        m.put(c)
    st = style(m)
    out = C.common(st, sit_loaf_pose=m.pose('lion_lounge', 2.5))
    lv = C.CatStyle(m, s=1.0, k=1.2, a=0.5, chonk=0.8)
    extra = [C.happy(st), C.eat(st), C.tame(st), C.level_up(lv, hops=1, twist=20)]
    for c in extra:
        m.put(c)
    return fixed + out + extra


def IDLE(m):
    t = C.idle_tables(style(m), lazy=1.5, playful=0.45)
    for e in t['sitting']:
        if e['clip'] == 'sit_loaf':
            e['w'] = 3
    return t
