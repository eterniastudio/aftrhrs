"""Velvetstag: a shy forest stag, out at dawn and dusk. Always half listening: ears swivel, the white tail flicks, it
lifts its nose to test the air. `alert` (its Watch skill plays it when an unsneaking player is near) is re-authored as a
one-shot stance: neck snapped up, ears locked forward, tail flagged, a warning stamp, then it slowly lowers its guard
(the old looping version held the creature in alert until it moved). Rubs the velvet off its antlers on trees, grazes
with frequent look-ups, chews with its head high."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Quad, ZERO, add_ears, add_tail, env, lerp3, once, overshoot, pulse, step, wave  # noqa: E402

PROCEDURAL = {'tail_sway': 3, 'look_yaw': 60}


def alert(q, m, L=4.2):
    c = once(m, 'alert', L)
    a = m.pose('alert', 0)
    i = m.pose('idle', 0)
    e = lambda t: overshoot(t, 0.0, 0.3, 0.2) * (1 - step(t, L - 1.0, L))
    for b in ('neck', 'h_head', 'body', 'tail'):
        ra, ri = a.get((b, 'rotation'), ZERO), i.get((b, 'rotation'), ZERO)
        c.rot(b, (lambda ra, ri: lambda t: lerp3(ri, ra, e(t)))(ra, ri))
    c.rot('neck', lambda t: (6 * e(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (0, 10 * pulse(t, 1.4, 2.4) - 8 * pulse(t, 2.6, 3.3), 0), add=True)
    add_ears(c, m, lambda t: (10 * e(t), 24 * e(t) - 30 * pulse(t, 1.2, 1.6), 0))
    stomp = lambda t: pulse(t, 2.0, 2.35)
    c.rot(q.rf, lambda t: (30 * stomp(t), 0, 0), add=True)
    c.pos(q.rf, lambda t: (0, 1.6 * stomp(t), 0), add=True)
    c.pos('body', lambda t: (0, -0.3 * pulse(t, 2.3, 2.5), 0), add=True)
    add_tail(c, m, lambda t: (0, 0, 0))
    c.rot('tail', lambda t: (-10 * pulse(t, 2.3, 2.6), 0, 0), add=True)
    c.sound(0.15, 'minecraft:entity.horse.breathe', 0.4, 1.5)
    c.sound(2.35, 'step', 1.0, 0.75)
    return c


def antler_rub(q, m, L=3.6):
    """Head low and forward, antlers scraped side to side against the trunk; legs braced."""
    c = once(m, 'antler_rub', L)
    e = lambda t: env(t, 0.1, L - 0.1, 0.5, 0.6)
    c.rot('neck', lambda t: (-22 * e(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-14 * e(t) + 4 * wave(t, 0.45) * e(t), 18 * wave(t, 0.9) * e(t), 10 * wave(t, 0.9, 1, 0.1) * e(t)), add=True)
    c.rot('body', lambda t: (-3 * e(t), 0, 1.5 * wave(t, 0.9) * e(t)), add=True)
    for lg in (q.lb, q.rb):
        c.rot(lg, lambda t: (-6 * e(t), 0, 0), add=True)
    for lg in (q.lf, q.rf):
        c.rot(lg, lambda t: (5 * e(t), 0, 0), add=True)
    add_ears(c, m, lambda t: (0, -10 * e(t), 10 * e(t)))
    add_tail(c, m, lambda t: (-12 * e(t), 0, 0))
    for t0 in (0.8, 1.7, 2.6):
        c.sound(t0, 'minecraft:block.wood.hit', 0.3, 1.3)
    return c


def sniff_air(q, m, L=2.6):
    """Nose up into the wind, nostrils working, ears forward: testing the air."""
    c = once(m, 'sniff_air', L)
    e = lambda t: env(t, 0.0, L, 0.4, 0.6)
    c.rot('neck', lambda t: (16 * e(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (18 * e(t), 8 * wave(t, L, 1) * e(t), 0), add=True)
    c.rot('jaw', lambda t: (-2.5 * max(0, wave(t, 0.25)) * env(t, 0.5, 1.9, 0.1, 0.1), 0, 0), add=True)
    add_ears(c, m, lambda t: (8 * e(t), 20 * e(t), 0))
    c.sound(0.7, 'minecraft:entity.horse.breathe', 0.3, 1.6)
    c.sound(1.3, 'minecraft:entity.horse.breathe', 0.25, 1.7)
    return c


def clips(m):
    q = Quad(m, size=1.0, neck='neck', snort=('minecraft:entity.horse.breathe', 0.4, 1.4))
    return [q.look_around(), q.head_shake(), q.chew_cud(), q.rest_stand(), q.groom(), q.stamp(),
            q.tail_swish('tail_flick', 0.9), q.ear_flick(), q.call('bugle', 2.0, 'ambient', 18),
            q.graze_tear(), q.graze_step(), q.graze_chew(), q.graze_look(),
            alert(q, m), antler_rub(q, m), sniff_air(q, m), hurt(m, extra=('tail',))]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'look_around', 'w': 3, 'cd': 10},
        {'clip': 'sniff_air', 'w': 2.5, 'cd': 12, 'boost': {'dawn': 2, 'dusk': 2}},
        {'clip': 'ear_flick', 'w': 2.5, 'cd': 5},
        {'clip': 'tail_flick', 'w': 2, 'cd': 5},
        {'clip': 'head_shake', 'w': 1, 'cd': 20},
        {'clip': 'chew_cud', 'w': 2, 'min': 4, 'max': 10},
        {'clip': 'rest_stand', 'w': 1, 'min': 5, 'max': 12, 'boost': {'night': 2}},
        {'clip': 'groom', 'w': 1, 'cd': 25},
        {'clip': 'antler_rub', 'w': 5, 'cd': 30, 'when': 'rub_near,!var:antlers_off'},
        {'clip': 'stamp', 'w': 0.6, 'cd': 25},
        {'clip': 'bugle', 'w': 0.6, 'cd': 40, 'boost': {'dusk': 3, 'dawn': 3}},
    ],
    'graze': [
        {'clip': '@mood', 'w': 4, 'min': 2, 'max': 5},
        {'clip': 'graze_look', 'w': 3, 'cd': 6},
        {'clip': 'graze_tear', 'w': 1.5, 'cd': 5},
        {'clip': 'graze_step', 'w': 2, 'cd': 6},
        {'clip': 'graze_chew', 'w': 1.5, 'min': 3, 'max': 6},
        {'clip': 'ear_flick', 'w': 1.5, 'cd': 4},
        {'clip': 'tail_flick', 'w': 1.5, 'cd': 4},
    ],
    'notice': [
        {'clip': 'sniff_air', 'w': 3},
        {'clip': 'ear_flick', 'w': 1},
    ],
}
