"""Luna But The Second One: 85 hand-made clips (the unimpressed glare, loafing among your shoes, lounging with one white
leg out, hat shakes and pompom bats, judging, sighing, shoe sniffs, biscuits with a grumpy face, turning her back on you,
jingle-bell ear scratches, creeper hisses with her hat askew ...) plus the shared cat body language (clips/_cats.py)
played as a round, unhurried grump: slow, small movements, heavy-lidded.

Source: Documents/Blockbench/models/arcana/work/luna2/luna2.py + luna2_anims.py (the baked clips are already turn-free,
so nothing needs unspinning except the shared spin and turn_back's single full turn). New here: sitting loops with her
judging stare, three sleeping loops, a head-rub `pet_sit` while staying, a bond level-up that is one grudging hop."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS, tolerate={'layer': 'base', 'move': False, 'p': 'sitting'},
            idle_shoes={'p': 'lying'}, idle_couch={'p': 'lying'},
            idle_sleepy={'p': 'lying'}, idle_cold={'p': 'lying'}, idle_scared={'p': 'lying'})
PROCEDURAL = {'tail_sway': 6, 'ear_twitch': 0.9, 'breath': 0.018}


def style(m):
    return C.CatStyle(m, s=1.0, k=1.12, a=0.85, tail=0.9)


def clips(m):
    fixed = [C.unspin(m, n) for n in ('spin', 'turn_back') if m.has(n)]
    for c in fixed:
        m.put(c)
    st = style(m)
    out = C.common(st, sit_loaf_pose=m.pose('idle_shoes', 0))
    extra = [C.happy(st), C.eat(st), C.tame(st), C.level_up(st, hops=1, twist=20)]
    for c in extra:
        m.put(c)
    return fixed + out + extra


def IDLE(m):
    return C.idle_tables(style(m), playful=0.45)
