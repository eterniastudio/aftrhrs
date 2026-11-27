"""Sun Wukong (boss) and his hair clones (same model). The model is the user's preferred pre-polish3 version
(model_overrides/arcana_wukong.bbmodel): geometry, textures and his own 24 clips stay; every attack clip's timing was
already matched by WukongSkills. Added: a monkey's calm idles (peering under his hand, a staff twirl, a head scratch,
restless bouncing), a phase-1 fighting stance (half way to his rage crouch), a phase-3 "Havoc in Heaven" stance, a heavy
stagger, and sound keyframes on the staff swings. combo / staff_spin / parry / somersault are re-baked so their
whole-turn spins (720 / 1440 / -360 deg) end at 0 instead of unwinding when the next clip blends in.

2026-11-20 redesign (mod/WUKONG.md "Clip table"): the set pieces and moves of the new fight, authored in redesign() below;
their key times match WukongSkills / WukongArena exactly."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import math  # noqa: E402

from animkit import Clip, wave, pulse, step, decay  # noqa: E402
import _bossanim as B  # noqa: E402
from _wukong2 import redesign  # noqa: E402

TAGS = {n: {'loop': 'loop'} for n in ('idle_peer', 'idle_twirl', 'idle_scratch', 'idle_bounce', 'stance', 'idle_havoc')}
# redesign loops: base-layer holds the Java sets as the idle clip; the still ones never play while he moves
TAGS.update({n: {'loop': 'loop', 'p': 'standing', 'move': False, 'layer': 'base'}
             for n in ('peach_eat', 'dizzy', 'furnace', 'sealed', 'idle_final', 'idle_shoulder')})
TAGS['cloud_ride'] = {'loop': 'loop', 'p': 'standing', 'move': True, 'layer': 'base'}   # also his walk while he hovers
# set pieces that sit or flip but are performed standing (no posture hunting, head look stays on)
TAGS.update({n: {'p': 'standing'} for n in ('ascend', 'peach_choke', 'break_free', 'vault', 'cloud_dive', 'transform', 'reveal', 'giant_slam',
                                            'havoc', 'giant_grow', 'cloud_strike')})
STATES = {'stance1': 'stance', 'stance2': 'idle_rage', 'stance3': 'idle_havoc', 'walk2': 'walk_rage', 'walk3': 'walk_rage',
          'hurt_heavy': 'hurt_heavy', 'stance4': 'idle_final', 'walk4': 'walk_rage'}
IDLE = {
    'boss_calm': [
        {'clip': 'idle', 'w': 3, 'min': 6, 'max': 12},
        {'clip': 'idle_peer', 'w': 2, 'min': 6, 'max': 6, 'cd': 10},
        {'clip': 'idle_twirl', 'w': 2, 'min': 6, 'max': 6, 'cd': 12},
        {'clip': 'idle_scratch', 'w': 1, 'min': 6, 'max': 6, 'cd': 20},
        {'clip': 'idle_bounce', 'w': 2, 'min': 3, 'max': 9},
        {'clip': 'idle_shoulder', 'w': 2, 'min': 6, 'max': 6, 'cd': 12},
    ],
    'boss_combat': [
        {'clip': 'stance', 'w': 5, 'min': 3, 'max': 9, 'when': 'p1'},
        {'clip': 'idle_bounce', 'w': 1, 'min': 3, 'max': 6, 'when': 'p1'},
        {'clip': 'idle_twirl', 'w': 1, 'min': 6, 'max': 6, 'cd': 15, 'when': 'p1,far'},
        {'clip': 'idle_rage', 'w': 5, 'min': 3, 'max': 9, 'when': 'p2'},
        {'clip': 'idle_havoc', 'w': 5, 'min': 3, 'max': 9, 'when': 'p3'},
        {'clip': 'idle_final', 'w': 5, 'min': 3, 'max': 9, 'when': 'p4'},
    ],
}


def soften(c, base, k):
    """Every track becomes base + k * (track - base): a pose half way between the idle and the clip."""
    for key, f in list(c.tracks.items()):
        b = base.get(key, B.REST[key[1]])
        c.tracks[key] = (lambda f, b: lambda t: tuple(b[i] + k * (f(t)[i] - b[i]) for i in range(3)))(f, b)


def clips(m):
    out = []
    I0 = B.P(m, 'idle', 0)
    L = m.length('idle')
    shade = B.P(m, 'fire_eyes', 0.6)          # left hand up at the brow

    # ---------------------------------------------------------------- calm idles (6 s = two idle loops)
    c = Clip(m, 'idle_peer', 2 * L, 'loop')
    B.ccopy(c, 'idle', period=L)
    up = lambda t: step(t, 0.3, 1.0) - step(t, 5.0, 5.8)
    for bone in ('arm_l', 'forearm_l', 'hand_l'):
        a, b = I0.get((bone, 'rotation'), (0, 0, 0)), shade.get((bone, 'rotation'), (0, 0, 0))
        c.tracks[(bone, 'rotation')] = (lambda a, b: lambda t: tuple(a[i] + (b[i] - a[i]) * up(t) for i in range(3)))(a, b)
    yaw = lambda t: 30 * (step(t, 1.0, 2.0) - step(t, 2.8, 3.6)) - 30 * (step(t, 2.8, 3.6) - step(t, 4.4, 5.2))
    c.rot('h_head', lambda t: (6 * up(t), yaw(t), -5 * up(t)), add=True)
    c.rot('chest', lambda t: (-4 * up(t), 0.3 * yaw(t - 0.2), 0), add=True)
    c.pos('body', lambda t: (0, 0.6 * up(t), 0), add=True)
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_twirl', 2 * L, 'loop')
    B.ccopy(c, 'idle', period=L)
    tw = lambda t: step(t, 0.6, 1.2) - step(t, 4.4, 5.2)
    spin = lambda t: 720 * step(t, 1.2, 4.2)
    c.rot('arm_r', lambda t: (55 * tw(t), 0, -10 * tw(t)), add=True)
    c.rot('forearm_r', lambda t: (25 * tw(t), 0, 0), add=True)
    c.rot('staff', lambda t: (86 * tw(t), spin(t), 0), add=True)
    c.rot('h_head', lambda t: (-6 * tw(t), 10 * tw(t), 0), add=True)
    c.rot('tail1', lambda t: (0, 12 * tw(t) * wave(t, 2 * L, 6), 0), add=True)
    c.pos('body', lambda t: (0, -1.0 * pulse(t, 4.3, 5.3), 0), add=True)
    B.sounds(c, [(1.5, 'ability_whoosh', 0.5, 1.5), (2.3, 'ability_whoosh', 0.5, 1.6), (3.1, 'ability_whoosh', 0.5, 1.7),
                 (4.8, 'ability_thud', 0.5, 1.3)])
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_scratch', 2 * L, 'loop')
    B.ccopy(c, 'idle', period=L)
    sc = lambda t: step(t, 0.4, 1.1) - step(t, 4.2, 5.0)
    c.rot('arm_l', lambda t: (150 * sc(t), 0, 10 * sc(t)), add=True)
    c.rot('forearm_l', lambda t: (-60 * sc(t), 0, 55 * sc(t)), add=True)
    c.rot('hand_l', lambda t: (25 * sc(t) * wave(t, 2 * L, 30), 0, 0), add=True)
    c.rot('h_head', lambda t: (-8 * sc(t), -10 * sc(t), 14 * sc(t) + 3 * sc(t) * wave(t, 2 * L, 15)), add=True)
    c.rot('jaw', lambda t: (-8 * pulse(t, 1.4, 3.6), 0, 0), add=True)
    c.rot('tail2', lambda t: (0, 14 * sc(t) * wave(t, 2 * L, 4), 0), add=True)
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_bounce', L, 'loop')
    B.ccopy(c, 'idle')
    c.pos('body', lambda t: (0, 0.9 * abs(wave(t, L, 3)), 0), add=True)
    c.rot('leg_l', lambda t: (6 * abs(wave(t, L, 3)), 0, 0), add=True)
    c.rot('leg_r', lambda t: (6 * abs(wave(t, L, 3)), 0, 0), add=True)
    c.rot('shin_l', lambda t: (-9 * abs(wave(t, L, 3)), 0, 0), add=True)
    c.rot('shin_r', lambda t: (-9 * abs(wave(t, L, 3)), 0, 0), add=True)
    c.rot('h_head', lambda t: (0, 14 * wave(t, L, 1), 3 * wave(t, L, 3)), add=True)
    for i, bone in enumerate(('tail1', 'tail2', 'tail3', 'tail4')):
        c.rot(bone, (lambda i: lambda t: (0, (8 + 5 * i) * wave(t, L, 2, -0.08 * i), 0))(i), add=True)
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- battle stances
    R = m.length('idle_rage')
    c = Clip(m, 'stance', 2 * R, 'loop')
    B.ccopy(c, 'idle_rage', speed=0.5, period=R)
    soften(c, I0, 0.6)
    c.pos('body', lambda t: (0, 0.5 * wave(t, 2 * R, 2), 0), add=True)
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_havoc', R, 'loop')
    B.ccopy(c, 'idle_rage', speed=2.0, period=R)
    c.rot('h_head', lambda t: (4 * wave(t, R, 3), 10 * wave(t, R, 1), 4 * wave(t, R, 2)), add=True)
    c.rot('chest', lambda t: (-4, 0, 3 * wave(t, R, 2)), add=True)
    c.rot('jaw', lambda t: (-8 - 6 * wave(t, R, 4), 0, 0), add=True)
    c.pos('body', lambda t: (0, -0.6 + 0.6 * abs(wave(t, R, 2)), 0), add=True)
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- heavy stagger
    c = Clip(m, 'hurt_heavy', 0.8, 'once')
    stag = B.add(I0, B.off(rot={'chest': (16, 0, 6), 'h_head': (18, -12, 0), 'arm_l': (-30, 0, 30), 'arm_r': (-20, 0, -18),
                                'leg_r': (-18, 0, 0), 'shin_r': (10, 0, 0), 'jaw': (-14, 0, 0)},
                           pos={'body': (0, -0.5, 2.0)}))
    B.seq(c, [(0, I0), (0.08, stag, 'out'), (0.3, stag), (0.8, I0)])
    B.sounds(c, [(0.02, 'ability_grunt', 0.8, 1.0)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- sounds on the swings; whole-turn spins re-baked
    out.append(B.keep(m, B.with_sounds(m, 'attack', [(0.3, 'ability_whoosh', 0.9, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'attack2', [(0.25, 'ability_kiai', 0.7, 1.1), (0.45, 'ability_whoosh', 1.0, 0.85)])))
    out.append(B.keep(m, B.with_sounds(m, 'attack3', [(0.28, 'ability_whoosh', 0.9, 1.25)])))
    out.append(B.keep(m, B.with_sounds(m, 'combo', [(0.3, 'ability_whoosh', 0.9, 1.0), (0.65, 'ability_whoosh', 0.9, 1.15),
                                                (1.2, 'ability_kiai', 0.9, 1.0), (1.5, 'ability_whoosh', 1.0, 0.8)])))
    out.append(B.keep(m, B.with_sounds(m, 'staff_spin', [(0.2, 'ability_kiai', 0.8, 1.1)] +
                                   [(0.3 + 0.25 * i, 'ability_whoosh', 0.7, 1.0 + 0.04 * i) for i in range(7)])))
    out.append(B.keep(m, B.with_sounds(m, 'parry', [(0.05, 'ability_whoosh', 0.8, 1.4), (0.3, 'ability_whoosh', 0.8, 1.5)])))
    out.append(B.keep(m, B.with_sounds(m, 'somersault', [(0.2, 'ability_kiai', 0.8, 1.2), (0.35, 'ability_whoosh', 0.9, 1.1)])))
    out.append(B.keep(m, B.with_sounds(m, 'ground_slam', [(0.3, 'ability_kiai', 0.9, 0.95), (0.55, 'ability_whoosh', 1.0, 0.8)])))
    out.append(B.keep(m, B.with_sounds(m, 'leap', [(0.25, 'ability_kiai', 1.0, 1.0), (1.5, 'ability_whoosh', 1.0, 0.75)])))
    out.append(B.keep(m, B.with_sounds(m, 'extend', [(0.4, 'ability_kiai', 0.9, 1.1)])))
    out.append(B.keep(m, B.with_sounds(m, 'death', [(0.3, 'ability_grunt', 1.0, 0.8), (1.4, 'ability_thud', 1.0, 0.8),
                                                (2.6, 'ability_stone', 1.0, 0.8)])))
    out += redesign(m, I0)
    return out
