"""Tufthorn (+ young): a shaggy highland cow. Unhurried, heavy, a herd animal: chews its cud, dozes standing on three
legs, swats flies with its tufted tail, rubs its shag on posts, tosses its horns. Lies down front first and gets up
rear first like real cattle; sleeps at night with its head turned along its flank. Calves bounce, buck and prance at
the grown-ups."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Quad  # noqa: E402

TAGS = {'lie_chew': {'p': 'lying'}, 'lie_look': {'p': 'lying'}, 'sleep': {'p': 'lying'}}
PROCEDURAL = {'breath': 0.014, 'tail_sway': 4}


def clips(m):
    q = Quad(m, size=1.0, wobble=('fur',), snort=('minecraft:entity.cow.ambient', 0.25, 0.55))
    out = [q.look_around(), q.head_shake(), q.chew_cud(), q.rest_stand(), q.groom(), q.rub(), q.stamp(), q.tail_swish(),
           q.ear_flick(), q.herd_glance(), q.alert_up(), q.hop(height=4.0), q.frolic(), q.follow_prance(),
           q.graze_tear(), q.graze_step(), q.graze_chew(), q.graze_look(),
           q.lie_down(), q.stand_up(), q.lie_chew(), q.lie_look(), q.sleep(), hurt(m, extra=('tail',))]
    return out


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 4, 'min': 5, 'max': 14},
        {'clip': 'look_around', 'w': 2, 'cd': 14},
        {'clip': 'head_shake', 'w': 1.5, 'cd': 18, 'boost': {'day': 1.5}},
        {'clip': 'chew_cud', 'w': 3, 'min': 6, 'max': 14, 'when': '!young'},
        {'clip': 'rest_stand', 'w': 1.5, 'min': 8, 'max': 20, 'when': '!young', 'boost': {'night': 2.5, 'rain': 2}},
        {'clip': 'groom', 'w': 1, 'cd': 25},
        {'clip': 'rub', 'w': 4, 'cd': 30, 'when': 'rub_near'},
        {'clip': 'stamp', 'w': 0.6, 'cd': 30},
        {'clip': 'tail_swish', 'w': 1.5, 'cd': 8, 'boost': {'day': 1.5}},
        {'clip': 'ear_flick', 'w': 1, 'cd': 8},
        {'clip': 'herd_glance', 'w': 2, 'cd': 15, 'when': 'herd_near:12'},
        {'clip': 'shake', 'w': 0.6, 'cd': 40, 'boost': {'rain': 4}},
        {'clip': 'hop', 'w': 3, 'cd': 6, 'when': 'young'},
        {'clip': 'frolic', 'w': 2, 'cd': 10, 'when': 'young,!night'},
        {'clip': 'prance', 'w': 3, 'cd': 12, 'when': 'young,adult_near:8'},
    ],
    'graze': [
        {'clip': '@mood', 'w': 5, 'min': 3, 'max': 6},
        {'clip': 'graze_tear', 'w': 2, 'cd': 5},
        {'clip': 'graze_step', 'w': 2, 'cd': 6},
        {'clip': 'graze_chew', 'w': 1.5, 'min': 3, 'max': 6},
        {'clip': 'graze_look', 'w': 1.5, 'cd': 10},
        {'clip': 'tail_swish', 'w': 1.5, 'cd': 6},
        {'clip': 'ear_flick', 'w': 1, 'cd': 6},
    ],
    'notice': [
        {'clip': 'alert_up', 'w': 3},
        {'clip': 'hop', 'w': 2, 'when': 'young'},
    ],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 30},
        {'clip': 'lie_chew', 'w': 3, 'min': 8, 'max': 20},
        {'clip': 'lie_look', 'w': 2, 'min': 6, 'max': 14},
        {'clip': 'sleep', 'w': 0.5, 'min': 20, 'max': 60, 'boost': {'night': 10, 'rain': 2}},
        {'clip': 'tail_swish', 'w': 1, 'cd': 10},
        {'clip': 'ear_flick', 'w': 1, 'cd': 8},
    ],
}
