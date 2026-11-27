"""Wartgrazer (+ lambs): a warped-forest sheep in a fleece of nether wart. Placid flock animal: chews, bleats to
the flock, shakes its heavy fleece, flicks its ears; lambs pronk straight-legged and buck about. Lies down front first,
sleeps with its head tucked along its fleece."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Quad  # noqa: E402

TAGS = {'lie_chew': {'p': 'lying'}, 'lie_look': {'p': 'lying'}, 'sleep': {'p': 'lying'}}
PROCEDURAL = {'breath': 0.014, 'tail_sway': 7}


def clips(m):
    q = Quad(m, size=1.0, wobble=('fleece',), snort=('minecraft:entity.sheep.ambient', 0.2, 0.7))
    return [q.look_around(), q.head_shake(), q.chew_cud(), q.rest_stand(), q.groom(), q.rub(), q.stamp(), q.tail_swish(),
            q.ear_flick(), q.herd_glance(), q.alert_up(), q.hop(height=5.0), q.frolic(), q.follow_prance(),
            q.body_shake('fleece_shake', sound=('minecraft:block.wool.hit', 0.45, 0.8)), q.call('bleat', 1.5, 'ambient', 16),
            q.graze_tear(), q.graze_step(), q.graze_chew(), q.graze_look(),
            q.lie_down(), q.stand_up(), q.lie_chew(), q.lie_look(), q.sleep(curl=30, drop=1.8), hurt(m, extra=('tail',))]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 4, 'min': 5, 'max': 14},
        {'clip': 'look_around', 'w': 2, 'cd': 14},
        {'clip': 'bleat', 'w': 1.5, 'cd': 14, 'boost': {'herd_near:16': 1.5}},
        {'clip': 'head_shake', 'w': 1, 'cd': 18},
        {'clip': 'fleece_shake', 'w': 1, 'cd': 30, 'boost': {'rain': 4}},
        {'clip': 'chew_cud', 'w': 3, 'min': 6, 'max': 14, 'when': '!young'},
        {'clip': 'rest_stand', 'w': 1.5, 'min': 8, 'max': 18, 'when': '!young', 'boost': {'night': 2.5}},
        {'clip': 'groom', 'w': 1, 'cd': 25},
        {'clip': 'rub', 'w': 4, 'cd': 30, 'when': 'rub_near'},
        {'clip': 'stamp', 'w': 0.5, 'cd': 30},
        {'clip': 'tail_swish', 'w': 1.5, 'cd': 8},
        {'clip': 'ear_flick', 'w': 1.5, 'cd': 8},
        {'clip': 'herd_glance', 'w': 2, 'cd': 15, 'when': 'herd_near:12'},
        {'clip': 'hop', 'w': 3, 'cd': 5, 'when': 'young'},
        {'clip': 'frolic', 'w': 2, 'cd': 9, 'when': 'young'},
        {'clip': 'prance', 'w': 3, 'cd': 12, 'when': 'young,adult_near:8'},
    ],
    'graze': [
        {'clip': '@mood', 'w': 5, 'min': 3, 'max': 6},
        {'clip': 'graze_tear', 'w': 2, 'cd': 5},
        {'clip': 'graze_step', 'w': 2, 'cd': 6},
        {'clip': 'graze_chew', 'w': 1.5, 'min': 3, 'max': 6},
        {'clip': 'graze_look', 'w': 1.5, 'cd': 10},
        {'clip': 'ear_flick', 'w': 1, 'cd': 6},
    ],
    'notice': [
        {'clip': 'alert_up', 'w': 3},
        {'clip': 'bleat', 'w': 1},
    ],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 30},
        {'clip': 'lie_chew', 'w': 3, 'min': 8, 'max': 20},
        {'clip': 'lie_look', 'w': 2, 'min': 6, 'max': 14},
        {'clip': 'sleep', 'w': 0.5, 'min': 20, 'max': 60, 'boost': {'night': 10}},
        {'clip': 'ear_flick', 'w': 1, 'cd': 8},
    ],
}
