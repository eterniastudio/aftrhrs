"""Runebound Colossus (boss): a rune-carved stone golem, slow and enormous. Its own attack clips are kept (they read
right after the rotation-sign fix) and get sound keyframes on their frames; Seismic Slam and Worldbreaker get longer,
shaking wind-ups (the Java hit ticks follow: BossSkills). New: four calm idles (rune hum, scan, weight shift with a
stomp, fist clench), two battle stances (phase 1 guard, phase 2 hunched and flaring), a roar taunt, the phase-2
ignition, hit flinches, a heavier walk for phase 2, and a sounded awaken / death."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, wave, pulse, step  # noqa: E402
import _bossanim as B  # noqa: E402

ARMS = ('right_arm', 'left_arm', 'right_forearm', 'left_forearm', 'right_fist', 'left_fist')

TAGS = {
    'stance': {'loop': 'loop'}, 'stance2': {'loop': 'loop'},
    'walk_rage': {'gait': 'walk', 'move': True, 'p': 'standing'},
    'stonebreaker_combo': {'hit': 0.8},
}
STATES = {'stance1': 'stance', 'stance2': 'stance2', 'walk2': 'walk_rage', 'phase2': 'phase2', 'hurt': 'hurt',
          'hurt_heavy': 'hurt_heavy'}
IDLE = {
    'boss_calm': [
        {'clip': 'idle', 'w': 3, 'min': 8, 'max': 16},
        {'clip': 'idle_runes', 'w': 3, 'min': 8, 'max': 16},
        {'clip': 'idle_scan', 'w': 2, 'min': 8, 'max': 8},
        {'clip': 'idle_shift', 'w': 2, 'min': 8, 'max': 8, 'cd': 20},
        {'clip': 'idle_flex', 'w': 1, 'min': 8, 'max': 8, 'cd': 30},
    ],
    'boss_combat': [
        {'clip': 'stance', 'w': 5, 'min': 6, 'max': 12, 'when': 'p1'},
        {'clip': 'stance2', 'w': 5, 'min': 6, 'max': 12, 'when': 'p2+'},
        {'clip': 'idle_runes', 'w': 1, 'min': 4, 'max': 8, 'when': 'p1'},
        {'clip': 'taunt', 'w': 1, 'cd': 25, 'when': 'far'},
    ],
}
PROCEDURAL = {'breath': 0.006, 'look_yaw': 40, 'look_pitch': 20}


def clips(m):
    out = []
    I0 = B.P(m, 'idle', 0)
    crouch = B.P(m, 'seismic_slam', 0.3)       # arms back, knees bent: gathering
    over = B.P(m, 'seismic_slam', 0.6)         # fists overhead
    slam = B.P(m, 'seismic_slam', 0.8)         # fists in the ground
    ward = B.P(m, 'runic_ward', 0.5)           # arms crossed before the chest

    # ---------------------------------------------------------------- calm idles (8 s = two of the 4 s idle loops)
    c = Clip(m, 'idle_runes', 8.0, 'loop')
    B.ccopy(c, 'idle', period=4.0)
    c.scl('chest_core', lambda t: (1 + 0.12 * pulse(t % 4, 0.4, 1.6),) * 3)
    c.pos('shoulder_shards', lambda t: (0, 1.4 * wave(t, 8, 2), 0), add=True)
    c.rot('right_arm', lambda t: (0, 0, 9 * pulse(t, 2.0, 6.0)), add=True)
    c.rot('left_arm', lambda t: (0, 0, -9 * pulse(t, 2.0, 6.0)), add=True)
    c.rot('right_forearm', lambda t: (12 * pulse(t, 2.0, 6.0), 0, 0), add=True)
    c.rot('left_forearm', lambda t: (12 * pulse(t, 2.0, 6.0), 0, 0), add=True)
    c.rot('h_head', lambda t: (-6 * pulse(t, 2.0, 6.0), 0, 0), add=True)
    c.rot('torso', lambda t: (1.5 * wave(t, 8, 2), 0, 0), add=True)
    B.sounds(c, [(2.2, 'ability_hum', 0.8, 1.0)])
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_scan', 8.0, 'loop')
    B.ccopy(c, 'idle', period=4.0)
    yaw = lambda t: 38 * (step(t, 0.6, 2.0) - step(t, 3.6, 5.2)) - 38 * (step(t, 3.6, 5.2) - step(t, 6.4, 7.6))
    c.rot('h_head', lambda t: (-3 * pulse(t, 0.6, 7.6), yaw(t), 0), add=True)
    c.rot('torso', lambda t: (0, 0.35 * yaw(t - 0.25), 0), add=True)
    c.rot('body', lambda t: (0, 0.12 * yaw(t - 0.4), 0))
    c.scl('eye', lambda t: (1 + 0.25 * pulse(t, 1.8, 2.6) + 0.25 * pulse(t, 5.0, 5.8), 1, 1))
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_shift', 8.0, 'loop')
    B.ccopy(c, 'idle', period=4.0)
    lean = lambda t: 4 * (step(t, 0.5, 1.6) - step(t, 3.0, 3.8)) - 4 * (step(t, 4.6, 5.6) - step(t, 7.0, 7.9))
    c.rot('body', lambda t: (0, 0, lean(t)))
    c.pos('root', lambda t: (-0.6 * lean(t) / 4, -0.6 * pulse(t, 3.6, 4.2), 0))
    # the right foot comes up and stamps (dust and a thud at 4.0 s)
    lift = lambda t: step(t, 3.0, 3.6) - step(t, 3.8, 3.95)
    c.rot('right_leg', lambda t: (28 * lift(t), 0, 0))
    c.rot('right_shin', lambda t: (-34 * lift(t), 0, 0))
    c.rot('torso', lambda t: (-3 * pulse(t, 3.8, 4.6), 0, 0), add=True)
    c.rot('h_head', lambda t: (4 * pulse(t, 3.8, 4.6), 0, 0), add=True)
    c.pos('shoulder_shards', lambda t: (0, -1.5 * math.exp(-8 * max(0, t - 3.95)) * (t > 3.95), 0), add=True)
    B.sounds(c, [(3.95, 'ability_stomp', 0.7, 1.1)])
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_flex', 8.0, 'loop')
    B.ccopy(c, 'idle', period=4.0)
    rr = lambda t: pulse(t, 0.6, 3.4)
    ll = lambda t: pulse(t, 4.0, 6.8)
    c.rot('right_arm', lambda t: (38 * rr(t), 0, 10 * rr(t)), add=True)
    c.rot('right_forearm', lambda t: (60 * rr(t) + 8 * rr(t) * wave(t, 8, 12), 0, 0), add=True)
    c.rot('left_arm', lambda t: (38 * ll(t), 0, -10 * ll(t)), add=True)
    c.rot('left_forearm', lambda t: (60 * ll(t) + 8 * ll(t) * wave(t, 8, 12), 0, 0), add=True)
    c.rot('h_head', lambda t: (-14 * (rr(t) + ll(t)), 22 * rr(t) - 22 * ll(t), 0), add=True)
    c.scl('chest_core', lambda t: (1 + 0.15 * (rr(t) + ll(t)),) * 3)
    B.sounds(c, [(1.4, 'ability_grind', 0.6, 1.2), (4.8, 'ability_grind', 0.6, 1.1)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- battle stances
    guard_d = B.off(
        rot={'torso': (-10, 0, 0), 'h_head': (10, 0, 0), 'right_arm': (28, 0, 14), 'left_arm': (28, 0, -14),
             'right_forearm': (40, 0, 0), 'left_forearm': (40, 0, 0), 'right_leg': (8, 0, 4), 'left_leg': (-6, 0, -4),
             'right_shin': (-10, 0, 0), 'left_shin': (-4, 0, 0)},
        pos={'root': (0, -1.2, 0)})
    c = Clip(m, 'stance', 4.0, 'loop')
    B.ccopy(c, 'idle')
    B.add_const(c, guard_d)
    c.rot('torso', lambda t: (2.2 * wave(t, 4, 2), 0, 0), add=True)
    c.pos('root', lambda t: (0, 0.5 * wave(t, 4, 2, 0.25), 0), add=True)
    c.rot('right_forearm', lambda t: (4 * wave(t, 4, 1), 0, 0), add=True)
    c.rot('left_forearm', lambda t: (4 * wave(t, 4, 1, 0.5), 0, 0), add=True)
    c.scl('chest_core', lambda t: (1.1 + 0.08 * wave(t, 4, 2),) * 3)
    out.append(B.keep(m, c))

    rage_d = B.off(
        rot={'torso': (-20, 0, 0), 'h_head': (18, 0, 0), 'right_arm': (42, 0, 26), 'left_arm': (42, 0, -26),
             'right_forearm': (55, 0, 0), 'left_forearm': (55, 0, 0), 'right_leg': (8, 0, 4), 'left_leg': (-6, 0, -4),
             'right_shin': (-10, 0, 0), 'left_shin': (-4, 0, 0)},
        pos={'root': (0, -2.0, 0), 'shoulder_shards': (0, 2.5, 0)})
    c = Clip(m, 'stance2', 2.0, 'loop')
    B.ccopy(c, 'idle', speed=2.0, period=4.0)
    B.add_const(c, rage_d)
    c.rot('torso', lambda t: (3.5 * wave(t, 2, 2), 0, 1.2 * wave(t, 2, 1)), add=True)
    c.pos('root', lambda t: (0, 0.7 * wave(t, 2, 2, 0.25), 0), add=True)
    c.pos('shoulder_shards', lambda t: (0.3 * wave(t, 2, 6), 0.6 * wave(t, 2, 4), 0), add=True)
    c.scl('chest_core', lambda t: (1.3 + 0.15 * wave(t, 2, 2),) * 3)
    c.rot('h_head', lambda t: (0, 6 * wave(t, 2, 1), 0), add=True)
    out.append(B.keep(m, c))

    # heavier phase-2 walk: the normal walk, hunched with fists raised
    c = Clip(m, 'walk_rage', float(m.length('walk')), 'loop')
    B.ccopy(c, 'walk')
    B.add_const(c, B.off(rot={'torso': (-14, 0, 0), 'h_head': (12, 0, 0), 'right_arm': (24, 0, 10), 'left_arm': (24, 0, -10),
                              'right_forearm': (35, 0, 0), 'left_forearm': (35, 0, 0)}, pos={'shoulder_shards': (0, 2.0, 0)}))
    c.scl('chest_core', lambda t: (1.25,) * 3)
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- taunt: a grinding roar, arms flung wide
    spread = B.add(I0, B.off(
        rot={'torso': (16, 0, 0), 'h_head': (22, 0, 0), 'right_arm': (10, 0, 72), 'left_arm': (10, 0, -72),
             'right_forearm': (20, 0, 0), 'left_forearm': (20, 0, 0)},
        pos={'shoulder_shards': (0, 4, 0), 'root': (0, 0.5, 0)}, scl={'chest_core': (1.6, 1.6, 1.6)}))
    c = Clip(m, 'taunt', 2.6, 'once')
    B.seq(c, [(0, I0), (0.45, B.mix(I0, crouch, 0.6)), (0.8, spread, 'back'), (1.9, spread), (2.6, I0)])
    B.shake(c, 'torso', 0.8, 1.9, 2.5, 11)
    B.shake(c, 'h_head', 0.8, 1.9, 3, 13)
    B.sounds(c, [(0.2, 'ability_grind', 0.9, 0.9), (0.8, 'ability_roar', 1.0, 1.0)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- phase 2: the runes ignite
    kneel = B.mix(I0, B.P(m, 'awaken', 0), 0.7)
    c = Clip(m, 'phase2', 3.2, 'once')
    B.seq(c, [(0, I0), (0.5, kneel), (1.3, kneel), (1.75, spread, 'back'), (2.3, spread), (2.55, over, 'out'),
              (2.75, slam, 'snap'), (3.2, B.mix(slam, I0, 0.6))])
    B.shake(c, 'torso', 0.5, 1.35, 3.5, 14)
    B.shake(c, 'shoulder_shards', 0.5, 2.3, 5, 9)
    c.scl('chest_core', lambda t: (1 + 0.7 * step(t, 0.6, 1.7) - 0.3 * step(t, 2.9, 3.2),) * 3)
    c.pos('left_shard', lambda t: (5 * pulse(t, 1.5, 2.7), 2 * pulse(t, 1.5, 2.7), 0))
    c.pos('right_shard', lambda t: (-5 * pulse(t, 1.5, 2.7), 2 * pulse(t, 1.5, 2.7), 0))
    B.sounds(c, [(0.1, 'ability_grind', 1.0, 0.8), (1.0, 'ability_hum', 1.0, 0.8), (1.7, 'ability_roar', 1.0, 0.85),
                 (2.75, 'ability_slam', 1.0, 0.9)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- hit reactions (upper body: the legs keep walking)
    c = Clip(m, 'hurt', 0.5, 'once')
    hit = B.add(I0, B.off(rot={'torso': (9, 0, 2), 'h_head': (12, 6, 0), 'right_arm': (-12, 0, 6), 'left_arm': (-12, 0, -6)},
                          pos={'shoulder_shards': (0, 1.2, 0)}))
    B.seq(c, [(0, B.only(I0, ('torso', 'h_head') + ARMS + ('shoulder_shards',))), (0.08, B.only(hit, ('torso', 'h_head') + ARMS + ('shoulder_shards',)), 'out'),
              (0.5, B.only(I0, ('torso', 'h_head') + ARMS + ('shoulder_shards',)))])
    out.append(B.keep(m, c))

    c = Clip(m, 'hurt_heavy', 0.95, 'once')
    stag = B.add(I0, B.off(rot={'body': (10, 0, -4), 'torso': (12, 0, 4), 'h_head': (16, -8, 0), 'right_arm': (-25, 0, 18),
                                'left_arm': (-20, 0, -22), 'right_leg': (-12, 0, 0), 'right_shin': (6, 0, 0)},
                           pos={'root': (0, -0.5, 1.6), 'shoulder_shards': (0, 2, 0)}, scl={'chest_core': (0.8, 0.8, 0.8)}))
    B.seq(c, [(0, I0), (0.1, stag, 'out'), (0.35, stag), (0.95, I0)])
    B.shake(c, 'torso', 0.1, 0.6, 2.5, 13)
    B.sounds(c, [(0.05, 'ability_grind', 0.6, 1.3)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- attacks: longer, shaking wind-ups + sounds
    c = B.retime(m, 'seismic_slam', 'seismic_slam', [(0, 0), (0.5, 0.3), (0.85, 0.3), (1.2, 0.6), (1.35, 0.8), (2.15, 1.6)])
    B.shake(c, 'torso', 0.45, 0.9, 2.2, 12)
    B.shake(c, 'right_arm', 0.45, 0.9, 3, 10)
    B.shake(c, 'left_arm', 0.45, 0.9, 3, 10)
    c.scl('chest_core', lambda t: (1 + 0.4 * step(t, 0.3, 0.9) - 0.4 * step(t, 1.4, 2.1),) * 3)
    B.sounds(c, [(0.15, 'ability_grind', 1.0, 0.9), (0.9, 'ability_heave', 1.0, 0.9), (1.33, 'ability_slam', 1.0, 1.0)])
    out.append(B.keep(m, c))

    c = B.retime(m, 'worldbreaker', 'worldbreaker',
                 [(0, 0), (0.6, 0.2), (1.1, 0.2), (1.6, 0.6), (2.0, 0.6), (2.15, 0.8), (3.75, 2.4)])
    B.shake(c, 'torso', 0.55, 1.15, 3, 12)
    B.shake(c, 'torso', 1.55, 2.05, 3.5, 15)
    B.shake(c, 'right_arm', 1.55, 2.05, 4, 13)
    B.shake(c, 'left_arm', 1.55, 2.05, 4, 13)
    B.shake(c, 'shoulder_shards', 0.3, 2.05, 6, 9)
    c.scl('chest_core', lambda t: (1 + 0.8 * step(t, 0.3, 2.0) - 0.8 * step(t, 2.4, 3.6),) * 3)
    B.sounds(c, [(0.1, 'ability_grind', 1.0, 0.75), (0.7, 'ability_hum', 1.0, 0.7), (1.25, 'ability_roar', 1.0, 0.8),
                 (1.7, 'ability_heave', 1.0, 0.8), (2.13, 'ability_slam', 1.0, 0.8), (2.3, 'ability_crash', 1.0, 0.9)])
    out.append(B.keep(m, c))

    out.append(B.keep(m, B.with_sounds(m, 'faultline_sweep', [(0.2, 'ability_grind', 0.9, 1.0), (0.65, 'ability_whoosh', 1.0, 0.8),
                                                          (0.8, 'ability_slam', 0.9, 1.1)])))
    out.append(B.keep(m, B.with_sounds(m, 'pillar_eruption', [(0.25, 'ability_heave', 0.8, 1.0), (0.78, 'ability_slam', 0.9, 1.15),
                                                          (1.0, 'ability_hum', 0.9, 0.7)])))
    out.append(B.keep(m, B.with_sounds(m, 'stonebreaker_combo', [(0.3, 'ability_heave', 0.8, 1.1), (0.62, 'ability_whoosh', 0.9, 0.9),
                                                             (0.8, 'ability_slam', 0.8, 1.25)])))
    out.append(B.keep(m, B.with_sounds(m, 'boulder_hurl', [(0.2, 'ability_grind', 0.8, 1.2), (0.55, 'ability_heave', 0.9, 1.0),
                                                       (0.72, 'ability_throw', 1.0, 0.9)])))
    out.append(B.keep(m, B.with_sounds(m, 'runic_ward', [(0.15, 'ability_grind', 0.7, 1.2), (0.5, 'ability_ward', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'awaken', [(0.1, 'ability_grind', 1.0, 0.7), (0.9, 'ability_hum', 1.0, 0.8),
                                                 (1.45, 'ability_roar', 1.0, 0.9)])))
    out.append(B.keep(m, B.with_sounds(m, 'death', [(0.1, 'ability_grind', 1.0, 0.7), (1.0, 'ability_hum', 0.9, 0.6),
                                                (2.05, 'ability_crash', 1.0, 0.8)])))
    return out
