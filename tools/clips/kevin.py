"""Kevin: 82 hand-made clips (paw fishing, the judgy stare, crossed-paw loafs, belly-up sunbathing, flops, failed
zoomies ...) audited after the rotation-sign fix, plus the shared cat body language (clips/_cats.py) played as a
40-pound chonk: slower, smaller moves, a belly that jiggles, a lot more loafing.

Fixes: `spin` finished a whole turn of the root (re-baked: no reverse spin on the blend out); `love_bite` sits down,
so it is a still-only base clip (it was an overlay that could play while walking); his loaf-type mood loops
(idle_sleepy / idle_cold, the crouched idle_scared) are tagged lying. New: sitting loops with his crossed-paw loaf,
three sleeping loops, a head-rub `pet_sit` while staying, a bond level-up that is one heroic tiny hop."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS, love_bite={'layer': 'base', 'move': False, 'p': 'sitting'},
            idle_sleepy={'p': 'lying'}, idle_cold={'p': 'lying'}, idle_scared={'p': 'lying'})
PROCEDURAL = {'tail_sway': 5, 'ear_twitch': 0.9, 'breath': 0.02}


def style(m):
    return C.CatStyle(m, s=1.0, k=1.2, a=0.75, tail=0.8, chonk=1.0)


def clips(m):
    fixed = [C.unspin(m, n) for n in ('spin',) if m.has(n)]
    for c in fixed:
        m.put(c)
    st = style(m)
    out = C.common(st, sit_loaf_pose=m.pose('paws_crossed', 2.5))
    lv = C.CatStyle(m, s=1.0, k=1.3, a=0.35, chonk=1.0)
    extra = [C.happy(st), C.eat(st), C.tame(st), C.level_up(lv, hops=1, twist=10)]
    for c in extra:
        m.put(c)
    return fixed + out + extra


def IDLE(m):
    t = C.idle_tables(style(m), lazy=1.8, playful=0.35)
    for e in t['sitting']:
        if e['clip'] == 'sit_loaf':
            e['w'] = 4
    return t
