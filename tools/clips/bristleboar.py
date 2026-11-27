"""Bristleboar (+ piglets): a bristly forest boar. Busy nose: it snuffles the ground for truffles, grunts, shakes
the dirt off and wallows (rocking on its side) when it rains. Neutral but touchy: its notice is a hackles-up stare
with a hoof pawing the ground. Piglets bounce, buck and trot circles around the sow."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Quad, add_ears, add_tail, env, loop, once, pulse, wave  # noqa: E402

TAGS = {'lie_chew': {'p': 'lying'}, 'lie_look': {'p': 'lying'}, 'sleep': {'p': 'lying'}, 'wallow': {'p': 'lying'}}
PROCEDURAL = {'breath': 0.016, 'tail_sway': 6}


def bristle(q, m, L=2.6):
    """Notice: head lowered, ears pinned forward, back hunched, a front hoof paws the ground twice, tail up stiff."""
    c = once(m, 'bristle', L)
    e = lambda t: env(t, 0.0, L, 0.3, 0.6)
    q.head_rot(c, lambda t: (-12 * e(t), 0, 0))
    c.rot('body', lambda t: (-4 * e(t), 0, 0), add=True)
    c.pos('body', lambda t: (0, -0.5 * e(t), 0.5 * e(t)), add=True)
    add_ears(c, m, lambda t: (10 * e(t), 26 * e(t), 0))
    add_tail(c, m, lambda t: (-40 * e(t), 6 * math.sin(t * 30) * e(t), 0), lag=0.03, gain=1.0)
    paw = lambda t: pulse(t, 0.6, 1.0) + pulse(t, 1.2, 1.6)
    c.rot(q.rf, lambda t: (-26 * paw(t), 0, 0), add=True)
    c.pos(q.rf, lambda t: (0, 1.0 * paw(t), 0), add=True)
    c.sound(0.3, 'minecraft:entity.hoglin.ambient', 0.35, 1.3)
    c.sound(0.95, 'step', 0.8, 0.9)
    c.sound(1.55, 'step', 0.8, 0.85)
    return c


def wallow(q, m, L=5.0):
    """Lying loop: rocks over onto its flank and back, legs paddling lazily (a mud wallow, mostly in the rain)."""
    c = loop(m, 'wallow', L, 'lie')
    roll = lambda t: 26 + 14 * wave(t, L, 1)
    c.rot('body', lambda t: (0, 0, roll(t)), add=True)
    c.pos('body', lambda t: (0.8 * (roll(t) / 40), -0.2, 0), add=True)
    for lg, ph in ((q.lf, 0), (q.rf, 0.5), (q.lb, 0.25), (q.rb, 0.75)):
        c.rot(lg, (lambda ph: lambda t: (10 * wave(t, L, 2, ph), 0, 0))(ph), add=True)
    q.head_rot(c, lambda t: (4 * wave(t, L, 2), 8 * wave(t, L, 1), -roll(t) * 0.6))
    add_tail(c, m, lambda t: (0, 14 * wave(t, L, 4), 0))
    c.sound(0.4, 'minecraft:block.mud.step', 0.35, 0.8)
    c.sound(2.9, 'minecraft:block.mud.step', 0.3, 0.75)
    return c


def clips(m):
    q = Quad(m, size=1.15, snort=('minecraft:entity.pig.ambient', 0.3, 0.7))
    out = [q.look_around(), q.head_shake(), q.chew_cud(), q.rest_stand(), q.groom(), q.rub(), q.stamp(), q.tail_swish(),
           q.ear_flick(), q.herd_glance(), q.hop(height=3.5), q.frolic(), q.follow_prance(),
           q.body_shake(sound=('minecraft:block.rooted_dirt.step', 0.4, 1.0)),
           q.snuffle(sound=('minecraft:entity.pig.ambient', 0.2, 1.35)), q.call('grunt', 1.2, 'ambient', 10),
           q.graze_tear(), q.graze_step(), q.graze_chew(), q.graze_look(),
           q.lie_down(L=1.4), q.stand_up(L=1.3), q.lie_chew(), q.lie_look(), q.sleep(curl=26, drop=1.6)]
    out += [bristle(q, m), wallow(q, m), hurt(m, extra=('tail',))]
    return out


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 4, 'min': 5, 'max': 12},
        {'clip': 'look_around', 'w': 2, 'cd': 14},
        {'clip': 'snuffle', 'w': 4, 'cd': 10},
        {'clip': 'grunt', 'w': 2, 'cd': 10},
        {'clip': 'head_shake', 'w': 1, 'cd': 18},
        {'clip': 'body_shake', 'w': 1, 'cd': 30, 'boost': {'rain': 4}},
        {'clip': 'chew_cud', 'w': 1.5, 'min': 5, 'max': 10, 'when': '!young'},
        {'clip': 'rest_stand', 'w': 1, 'min': 6, 'max': 14, 'when': '!young', 'boost': {'night': 2.5}},
        {'clip': 'groom', 'w': 1, 'cd': 25},
        {'clip': 'rub', 'w': 5, 'cd': 25, 'when': 'rub_near'},
        {'clip': 'stamp', 'w': 0.8, 'cd': 25},
        {'clip': 'tail_swish', 'w': 2, 'cd': 6},
        {'clip': 'ear_flick', 'w': 1, 'cd': 8},
        {'clip': 'herd_glance', 'w': 2, 'cd': 15, 'when': 'herd_near:12'},
        {'clip': 'hop', 'w': 3, 'cd': 5, 'when': 'young'},
        {'clip': 'frolic', 'w': 2.5, 'cd': 9, 'when': 'young,!night'},
        {'clip': 'prance', 'w': 3, 'cd': 12, 'when': 'young,adult_near:8'},
    ],
    'graze': [
        {'clip': '@mood', 'w': 5, 'min': 3, 'max': 6},
        {'clip': 'graze_tear', 'w': 2.5, 'cd': 5},
        {'clip': 'graze_step', 'w': 2, 'cd': 6},
        {'clip': 'graze_chew', 'w': 1.5, 'min': 3, 'max': 6},
        {'clip': 'graze_look', 'w': 1.5, 'cd': 10},
        {'clip': 'tail_swish', 'w': 2, 'cd': 5},
    ],
    'notice': [
        {'clip': 'bristle', 'w': 3, 'when': '!young'},
        {'clip': 'herd_glance', 'w': 2, 'when': 'young'},
        {'clip': 'grunt', 'w': 1},
    ],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 30},
        {'clip': 'lie_chew', 'w': 2, 'min': 8, 'max': 18},
        {'clip': 'lie_look', 'w': 2, 'min': 6, 'max': 14},
        {'clip': 'wallow', 'w': 1, 'min': 8, 'max': 20, 'boost': {'rain': 6}},
        {'clip': 'sleep', 'w': 0.5, 'min': 20, 'max': 60, 'boost': {'night': 10}},
        {'clip': 'tail_swish', 'w': 1, 'cd': 10},
        {'clip': 'ear_flick', 'w': 1, 'cd': 8},
    ],
}
