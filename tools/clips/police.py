"""Police officer: sent after bank robbers. New: alert scanning (shading the eyes, turning slowly), a shoulder-radio
call, a whistle, a pointing "Stop right there!", a giving-up shrug, a chase run (arms pumping, baton up), a guard
stance while it closes in, and a baton swing with a real wind-up (the hit lands on its frame: tag hit)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, wave, pulse, step  # noqa: E402
import _bossanim as B  # noqa: E402

TAGS = {'idle_scan': {'loop': 'loop'}, 'idle_ready': {'loop': 'loop'}, 'attack': {'hit': 0.42}}
STATES = {'stance': 'idle_ready', 'chase': 'run'}
IDLE = {
    'boss_calm': [
        {'clip': 'idle', 'w': 2, 'min': 4, 'max': 8},
        {'clip': 'idle_scan', 'w': 3, 'min': 8, 'max': 8},
        {'clip': 'radio', 'w': 1, 'cd': 12},
        {'clip': 'whistle', 'w': 1, 'cd': 16},
    ],
    'boss_combat': [
        {'clip': 'idle_ready', 'w': 4, 'min': 3, 'max': 6},
        {'clip': 'point', 'w': 1, 'cd': 8, 'when': 'far'},
    ],
}
PROCEDURAL = {'look_yaw': 60}


def clips(m):
    out = []
    I0 = B.P(m, 'idle', 0)
    alert = B.P(m, 'alert', 0.9)
    L = m.length('idle')

    # alert scanning: a hand shading the eyes, turning slowly left and right, leaning in
    c = Clip(m, 'idle_scan', 2 * L, 'loop')
    B.ccopy(c, 'idle', period=L)
    sh = lambda t: step(t, 0.3, 0.9) - step(t, 6.9, 7.6)
    c.rot('arm_l', lambda t: (150 * sh(t), 0, -12 * sh(t)), add=True)
    c.rot('forearm_l', lambda t: (40 * sh(t), 0, 0), add=True)
    yaw = lambda t: 38 * (step(t, 1.0, 2.2) - step(t, 3.4, 4.4)) - 38 * (step(t, 3.4, 4.4) - step(t, 5.6, 6.6))
    c.rot('h_head', lambda t: (-4 * sh(t), yaw(t), 0), add=True)
    c.rot('body', lambda t: (-4 * sh(t), 0.35 * yaw(t - 0.2), 0), add=True)
    out.append(B.keep(m, c))

    # guard: baton raised across the body, weight forward, a little bounce
    c = Clip(m, 'idle_ready', 2.0, 'loop')
    B.ccopy(c, 'idle', speed=2.0, period=L)
    B.add_const(c, B.off(rot={'body': (-6, 0, 0), 'h_head': (4, 0, 0), 'arm_r': (55, 0, -10), 'forearm_r': (45, 0, 0),
                              'baton': (-30, 0, 0), 'arm_l': (30, 0, 12), 'forearm_l': (40, 0, 0), 'leg_l': (12, 0, 0),
                              'leg_r': (-8, 0, 0)}))
    c.pos('body', lambda t: (0, -0.3 + 0.3 * wave(t, 2.0, 2), 0), add=True)
    c.rot('baton', lambda t: (6 * wave(t, 2.0, 1), 0, 0), add=True)
    out.append(B.keep(m, c))

    # chase run: the walk at a run, arms pumping, leaning in, baton held high
    W = m.length('walk')
    c = Clip(m, 'run', 0.5, 'loop')
    B.ccopy(c, 'walk', speed=W / 0.5, period=W)
    for b in ('leg_l', 'leg_r', 'arm_l'):
        f = c.tracks.get((b, 'rotation'))
        if f:
            c.tracks[(b, 'rotation')] = (lambda f: lambda t: tuple(v * 1.4 for v in f(t)))(f)
    B.add_const(c, B.off(rot={'body': (-12, 0, 0), 'h_head': (10, 0, 0), 'arm_r': (70, 0, -6), 'forearm_r': (60, 0, 0),
                              'forearm_l': (40, 0, 0)}))
    c.pos('root', lambda t: (0, 0.8 * abs(wave(t, 0.5, 1)), 0), add=True)
    out.append(B.keep(m, c))

    # baton swing: raise and lean back (0.35 s), crack down (hit at 0.42 s), follow through
    c = Clip(m, 'attack', 0.85, 'once')
    up = B.add(I0, B.off(rot={'arm_r': (200, 0, -10), 'forearm_r': (30, 0, 0), 'baton': (25, 0, 0), 'body': (8, -16, 0),
                              'h_head': (-6, 12, 0), 'arm_l': (-25, 0, 12), 'leg_l': (-10, 0, 0), 'leg_r': (8, 0, 0)}))
    down = B.add(I0, B.off(rot={'arm_r': (55, 0, -4), 'forearm_r': (6, 0, 0), 'baton': (45, 0, 0), 'body': (-14, 14, 0),
                                'h_head': (6, -8, 0), 'arm_l': (15, 0, 6), 'leg_l': (18, 0, 0), 'leg_r': (-12, 0, 0)}))
    B.seq(c, [(0, I0), (0.32, up, 'out'), (0.42, down, 'snap'), (0.55, down), (0.85, I0)])
    B.sounds(c, [(0.36, 'minecraft:entity.player.attack.sweep', 0.6, 1.4)])
    out.append(B.keep(m, c))

    # whistle: hand to the mouth (the alert pose's left arm), head up, two shrill blasts
    c = Clip(m, 'whistle', 1.4, 'once')
    wh = B.add(B.mix(I0, B.only(alert, ('arm_l', 'forearm_l')), 1.0), B.off(rot={'h_head': (12, 0, 0), 'body': (5, 0, 0)}))
    B.seq(c, [(0, I0), (0.25, wh, 'out'), (1.1, wh), (1.4, I0)])
    c.pos('body', lambda t: (0, 0.5 * (pulse(t, 0.3, 0.6) + pulse(t, 0.7, 1.0)), 0), add=True)
    B.sounds(c, [(0.35, 'minecraft:block.note_block.flute', 1.0, 2.0), (0.45, 'minecraft:block.note_block.flute', 1.0, 1.89),
                 (0.75, 'minecraft:block.note_block.flute', 1.0, 2.0), (0.85, 'minecraft:block.note_block.flute', 1.0, 1.89)])
    out.append(B.keep(m, c))

    # radio: hand to the shoulder radio, head tilted to it, a crackle and a mutter
    c = Clip(m, 'radio', 2.6, 'once')
    rd = B.add(I0, B.off(rot={'arm_l': (55, 0, -28), 'forearm_l': (95, 0, 0), 'h_head': (-6, 18, -16), 'body': (0, 6, 0)}))
    B.seq(c, [(0, I0), (0.35, rd, 'out'), (2.2, rd), (2.6, I0)])
    c.rot('h_head', lambda t: (3 * pulse(t, 0.9, 1.3) + 3 * pulse(t, 1.5, 1.9), 0, 0), add=True)
    B.sounds(c, [(0.4, 'ability_radio', 0.8, 1.0), (1.0, 'ambient', 0.6, 1.1), (1.9, 'ability_radio', 0.7, 1.1)])
    out.append(B.keep(m, c))

    # "Stop right there!": arm thrust out at the target, two jabs
    c = Clip(m, 'point', 1.4, 'once')
    pt = B.add(I0, B.off(rot={'arm_l': (90, 0, -4), 'forearm_l': (4, 0, 0), 'body': (-4, -10, 0), 'h_head': (6, 8, 0)}))
    B.seq(c, [(0, I0), (0.2, pt, 'back'), (1.1, pt), (1.4, I0)])
    c.rot('arm_l', lambda t: (8 * (pulse(t, 0.45, 0.6) + pulse(t, 0.7, 0.85)), 0, 0), add=True)
    out.append(B.keep(m, c))

    # giving up: a big shrug, palms up, head shaking
    c = Clip(m, 'shrug', 1.8, 'once')
    sg = B.add(I0, B.off(rot={'arm_l': (20, 0, 28), 'arm_r': (20, 0, -28), 'forearm_l': (70, 0, 0), 'forearm_r': (70, 0, 0),
                              'h_head': (4, 0, 10), 'body': (3, 0, 0)}, pos={'body': (0, 0.6, 0)}))
    B.seq(c, [(0, I0), (0.35, sg, 'back'), (1.3, sg), (1.8, I0)])
    c.rot('h_head', lambda t: (0, 14 * pulse(t, 0.5, 1.3) * wave(t, 1.8, 4), 0), add=True)
    out.append(B.keep(m, c))
    return out

