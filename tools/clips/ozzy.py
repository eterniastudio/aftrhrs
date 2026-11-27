"""Ozzy: 82 hand-made clips (the Ankle Biter stalk-and-pounce, ankle ambushes with bunny-kicks, rude greetings that end
in a bite, murder mittens, sprawling belly-up in the sun, fupa swings, feather-toy murder, glares, flops, failing to
lick his own fupa ...) plus the shared cat body language (clips/_cats.py) played as a very fat little menace: a heavy,
slow saunter, small idle moves, a giant fupa that jiggles (chonk), a lot of loafing.

Source: Documents/Blockbench/models/arcana/work/ozzy/ozzy.py + ozzy_anims.py (the baked clips are already turn-free,
so nothing needs unspinning except the shared spin). Extra bones: 'belly' (the fupa) and 'toy' (his feather toy, only
out in toy_murder / sunbeam_sprawl). New here: sitting loops with his loaf, three sleeping loops, a head-rub `pet_sit`
while staying, a bond level-up that is one heavy hop."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

# petting reactions that sit / roll over are still-only base clips; his lying-type loops are tagged lying
TAGS = dict(C.TAGS, grudge_pet={'layer': 'base', 'move': False, 'p': 'sitting'},
            bunny_kick={'layer': 'base', 'move': False}, murder_mittens={'layer': 'base', 'move': False},
            idle_sunbeam={'p': 'lying'}, idle_beg={'p': 'sitting'},
            idle_sleepy={'p': 'lying'}, idle_cold={'p': 'lying'}, idle_scared={'p': 'lying'})
PROCEDURAL = {'tail_sway': 5, 'ear_twitch': 0.8, 'breath': 0.022}


def style(m):
    return C.CatStyle(m, s=1.0, k=1.22, a=0.72, tail=0.85, chonk=1.3)


def clips(m):
    fixed = [C.unspin(m, n) for n in ('spin',) if m.has(n)]
    for c in fixed:
        m.put(c)
    st = style(m)
    out = C.common(st, sit_loaf_pose=m.pose('loaf', 2.5))
    lv = C.CatStyle(m, s=1.0, k=1.35, a=0.32, chonk=1.4)
    extra = [C.happy(st), C.eat(st), C.tame(st), C.level_up(lv, hops=1, twist=10)]
    for c in extra:
        m.put(c)
    return fixed + out + extra


def IDLE(m):
    t = C.idle_tables(style(m), lazy=1.8, playful=0.6)
    for e in t['sitting']:
        if e['clip'] == 'sit_loaf':
            e['w'] = 4
    return t
