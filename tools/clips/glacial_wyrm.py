"""Glacial Wyrm (boss): a crystal-armored ice serpent reared up from its coils. Its attack clips are kept (their hit
frames are now what BossSkills waits for) and get sound keyframes; new: calm idles (serpentine sway, a slow scan with
the frill, frost breath, coil tighten), battle stances (phase 1 low and coiled to strike, phase 2 reared with the
crystals flared), a hissing taunt, the phase-2 crystal eruption and hit flinches."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, wave, pulse, step  # noqa: E402
import _bossanim as B  # noqa: E402

UPPER = ('body', 'spine_1', 'spine_2', 'spine_3', 'neck', 'h_head', 'jaw', 'frill', 'left_fin', 'right_fin')
RIDGES = ('spine_1_ridge', 'spine_2_ridge', 'spine_3_ridge')

TAGS = {'stance': {'loop': 'loop'}, 'stance2': {'loop': 'loop'}, 'frost_fang': {'hit': 0.95}}
STATES = {'stance1': 'stance', 'stance2': 'stance2', 'phase2': 'phase2', 'hurt': 'hurt', 'hurt_heavy': 'hurt_heavy'}
IDLE = {
    'boss_calm': [
        {'clip': 'idle', 'w': 2, 'min': 6, 'max': 12},
        {'clip': 'idle_sway', 'w': 3, 'min': 6, 'max': 12},
        {'clip': 'idle_scan', 'w': 2, 'min': 6, 'max': 6},
        {'clip': 'idle_breath', 'w': 2, 'min': 6, 'max': 6, 'cd': 15},
        {'clip': 'idle_coil', 'w': 1, 'min': 6, 'max': 6, 'cd': 20},
    ],
    'boss_combat': [
        {'clip': 'stance', 'w': 5, 'min': 6, 'max': 12, 'when': 'p1'},
        {'clip': 'stance2', 'w': 5, 'min': 6, 'max': 12, 'when': 'p2+'},
        {'clip': 'idle_sway', 'w': 1, 'min': 3, 'max': 6, 'when': 'p1'},
        {'clip': 'taunt', 'w': 1, 'cd': 22, 'when': 'far'},
    ],
}
PROCEDURAL = {'breath': 0.008, 'look_yaw': 45, 'look_pitch': 25}


def sway(c, L, amp=1.0, cycles=1):
    """A serpent's S-sway up the spine: each segment a little later and wider."""
    for i, bone in enumerate(('body', 'spine_1', 'spine_2', 'spine_3', 'neck')):
        a = amp * (2.5 + i * 0.6)
        c.rot(bone, (lambda a, i: lambda t: (0, 0.5 * a * wave(t, L, cycles, -0.07 * i), a * wave(t, L, cycles, -0.09 * i)))(a, i), add=True)
    c.rot('h_head', lambda t: (0, -2.0 * amp * wave(t, L, cycles, -0.45), -3.5 * amp * wave(t, L, cycles, -0.5)), add=True)
    for i, bone in enumerate(('tail_1', 'tail_2', 'tail_3', 'tail_tip')):
        c.rot(bone, (lambda i: lambda t: (0, amp * (4 + 3 * i) * wave(t, L, cycles, 0.1 + 0.08 * i), 0))(i), add=True)


def clips(m):
    out = []
    I0 = B.P(m, 'idle', 0)
    reared = B.P(m, 'shatter_roar', 1.3)        # reared up, jaw wide: the roar
    gather = B.P(m, 'shatter_roar', 0.8)        # curled down, gathering
    coiled = B.P(m, 'frost_fang', 0.75)         # drawn back to strike
    slammed = B.P(m, 'rime_spikes', 1.05)       # head slammed into the ground

    # ---------------------------------------------------------------- calm idles (6 s = two 3 s idle loops)
    c = Clip(m, 'idle_sway', 6.0, 'loop')
    B.ccopy(c, 'idle', period=3.0)
    sway(c, 6.0, 1.0)
    c.rot('frill', lambda t: (-4 * wave(t, 6, 2), 0, 0), add=True)
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_scan', 6.0, 'loop')
    B.ccopy(c, 'idle', period=3.0)
    yaw = lambda t: 32 * (step(t, 0.4, 1.4) - step(t, 2.6, 3.6)) - 32 * (step(t, 2.6, 3.6) - step(t, 4.8, 5.7))
    c.rot('neck', lambda t: (0, 0.45 * yaw(t - 0.15), 0), add=True)
    c.rot('h_head', lambda t: (-6 * pulse(t, 0.4, 5.7), 0.55 * yaw(t), 0), add=True)
    c.rot('spine_3', lambda t: (0, 0.2 * yaw(t - 0.3), 0), add=True)
    c.rot('frill', lambda t: (14 * (pulse(t, 1.0, 2.4) + pulse(t, 3.4, 4.8)), 0, 0), add=True)
    c.scl('frill', lambda t: (1 + 0.15 * (pulse(t, 1.0, 2.4) + pulse(t, 3.4, 4.8)),) * 3)
    c.rot('tail_tip', lambda t: (0, 18 * wave(t, 6, 3), 0), add=True)
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_breath', 6.0, 'loop')
    B.ccopy(c, 'idle', period=3.0)
    exhale = lambda t: pulse(t, 1.6, 4.2)
    inhale = lambda t: pulse(t, 0.4, 1.8)
    c.rot('body', lambda t: (4 * inhale(t) - 5 * exhale(t), 0, 0), add=True)
    c.rot('neck', lambda t: (6 * inhale(t) - 8 * exhale(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (8 * inhale(t) - 10 * exhale(t), 0, 0), add=True)
    c.rot('jaw', lambda t: (-16 * exhale(t), 0, 0), add=True)
    c.rot('left_fin', lambda t: (0, -10 * inhale(t), 0), add=True)
    c.rot('right_fin', lambda t: (0, 10 * inhale(t), 0), add=True)
    for r in RIDGES:
        c.scl(r, lambda t: (1 + 0.12 * inhale(t),) * 3)
    B.sounds(c, [(0.4, 'ability_inhale', 0.5, 1.2), (1.7, 'ability_breath', 0.45, 1.3)])
    out.append(B.keep(m, c))

    c = Clip(m, 'idle_coil', 6.0, 'loop')
    B.ccopy(c, 'idle', period=3.0)
    tight = lambda t: step(t, 0.5, 2.0) - step(t, 3.8, 5.6)
    c.rot('coil', lambda t: (0, 14 * tight(t), 0), add=True)
    c.pos('root', lambda t: (0, -1.6 * tight(t), 0))
    c.rot('body', lambda t: (-6 * tight(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (8 * tight(t), 0, 0), add=True)
    for i, bone in enumerate(('tail_1', 'tail_2', 'tail_3', 'tail_tip')):
        c.rot(bone, (lambda i: lambda t: (0, -(10 + 6 * i) * tight(t) + 5 * wave(t, 6, 2, i * 0.1), 0))(i), add=True)
    B.sounds(c, [(0.6, 'ability_grind', 0.5, 1.3)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- battle stances
    c = Clip(m, 'stance', 3.0, 'loop')
    B.ccopy(c, 'idle')
    B.add_const(c, B.diff(B.mix(I0, coiled, 0.55), I0))
    B.add_const(c, B.off(rot={'frill': (12, 0, 0)}, pos={'root': (0, -1.0, 0)}))
    sway(c, 3.0, 0.8)
    c.rot('jaw', lambda t: (-6 * wave(t, 3, 1) - 6, 0, 0), add=True)
    out.append(B.keep(m, c))

    c = Clip(m, 'stance2', 2.0, 'loop')
    B.ccopy(c, 'idle', speed=1.5, period=3.0)
    B.add_const(c, B.diff(B.mix(I0, reared, 0.45), I0))
    B.add_const(c, B.off(rot={'frill': (18, 0, 0), 'h_head': (-10, 0, 0)}, scl={r: (1.35, 1.35, 1.35) for r in RIDGES}))
    sway(c, 2.0, 1.1)
    c.rot('jaw', lambda t: (-10 * wave(t, 2, 2) - 10, 0, 0), add=True)
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- taunt: a hissing threat display
    c = Clip(m, 'taunt', 2.4, 'once')
    flare = B.add(B.mix(I0, reared, 0.7), B.off(rot={'frill': (22, 0, 0)}, scl={'frill': (1.3, 1.3, 1.3)}))
    B.seq(c, [(0, I0), (0.4, B.mix(I0, gather, 0.6)), (0.75, flare, 'back'), (1.8, flare), (2.4, I0)])
    B.shake(c, 'h_head', 0.8, 1.8, 6, 9, axes=(0, 1, 1))
    B.shake(c, 'neck', 0.8, 1.8, 3, 9, axes=(0, 1, 0))
    B.sounds(c, [(0.25, 'ability_inhale', 0.8, 1.1), (0.75, 'ability_hiss', 1.0, 1.0)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- phase 2: the crystals erupt
    c = Clip(m, 'phase2', 3.0, 'once')
    big = B.add(reared, B.off(rot={'frill': (24, 0, 0)}, scl={r: (2.0, 2.0, 2.0) for r in RIDGES}))
    end = B.add(B.mix(I0, reared, 0.45), B.off(rot={'frill': (18, 0, 0), 'h_head': (-10, 0, 0)}, scl={r: (1.35, 1.35, 1.35) for r in RIDGES}))
    B.seq(c, [(0, I0), (0.6, gather), (1.3, gather), (1.65, big, 'back'), (2.4, big), (3.0, end)])
    B.shake(c, 'body', 0.6, 1.35, 3, 13)
    B.shake(c, 'h_head', 1.65, 2.4, 5, 10)
    c.pos('root', lambda t: (0, -2 * pulse(t, 0.3, 1.5), 0))
    B.sounds(c, [(0.1, 'ability_grind', 1.0, 0.8), (0.6, 'ability_inhale', 1.0, 0.8), (1.6, 'ability_roar', 1.0, 0.85),
                 (1.7, 'ability_shatter', 1.0, 0.9)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- hit reactions
    hit = B.add(I0, B.off(rot={'body': (6, 0, 3), 'spine_2': (4, 0, 0), 'neck': (8, 0, 0), 'h_head': (14, 8, 0), 'jaw': (-14, 0, 0)}))
    c = Clip(m, 'hurt', 0.45, 'once')
    B.seq(c, [(0, B.only(I0, UPPER)), (0.08, B.only(hit, UPPER), 'out'), (0.45, B.only(I0, UPPER))])
    out.append(B.keep(m, c))

    heavy = B.add(I0, B.off(rot={'body': (12, -8, 6), 'spine_1': (6, 0, 0), 'spine_2': (6, 0, 0), 'neck': (12, 0, 0),
                                 'h_head': (20, -14, 0), 'jaw': (-26, 0, 0), 'frill': (-10, 0, 0)}, pos={'root': (0, -0.6, 1.2)}))
    c = Clip(m, 'hurt_heavy', 0.9, 'once')
    B.seq(c, [(0, I0), (0.1, heavy, 'out'), (0.35, heavy), (0.9, I0)])
    B.shake(c, 'h_head', 0.1, 0.55, 4, 12)
    B.sounds(c, [(0.05, 'ability_hiss', 0.6, 1.3)])
    out.append(B.keep(m, c))

    # ---------------------------------------------------------------- attacks: sounds on their frames
    out.append(B.keep(m, B.with_sounds(m, 'glacial_breath', [(0.25, 'ability_inhale', 1.0, 1.0), (0.95, 'ability_breath', 1.0, 0.9),
                                                         (1.35, 'ability_breath', 0.8, 0.8)])))
    out.append(B.keep(m, B.with_sounds(m, 'tail_sweep', [(0.6, 'ability_hiss', 0.7, 1.2), (0.85, 'ability_whoosh', 1.0, 0.8),
                                                     (1.0, 'ability_slam', 0.8, 1.2)])))
    out.append(B.keep(m, B.with_sounds(m, 'rime_spikes', [(0.35, 'ability_inhale', 0.8, 1.1), (0.75, 'ability_hiss', 0.8, 1.0),
                                                      (1.0, 'ability_slam', 1.0, 1.0), (1.05, 'ability_shatter', 0.8, 1.1)])))
    out.append(B.keep(m, B.with_sounds(m, 'shatter_roar', [(0.4, 'ability_inhale', 1.0, 0.9), (1.2, 'ability_roar', 1.0, 0.9),
                                                       (1.3, 'ability_shatter', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'burrow_strike', [(0.35, 'ability_hiss', 0.8, 1.0), (0.6, 'ability_dig', 1.0, 0.8),
                                                        (1.2, 'ability_dig', 0.7, 0.6), (1.85, 'ability_slam', 1.0, 0.9),
                                                        (1.9, 'ability_shatter', 0.9, 0.9)])))
    out.append(B.keep(m, B.with_sounds(m, 'absolute_winter', [(0.2, 'ability_inhale', 1.0, 0.8), (0.9, 'ability_roar', 1.0, 0.75),
                                                          (1.95, 'ability_slam', 1.0, 0.8), (2.0, 'ability_shatter', 1.0, 0.8)])))
    out.append(B.keep(m, B.with_sounds(m, 'frost_fang', [(0.4, 'ability_hiss', 0.8, 1.2), (0.92, 'ability_bite', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'summon', [(0.1, 'ability_dig', 1.0, 0.7), (0.8, 'ability_shatter', 0.9, 0.9),
                                                 (1.9, 'ability_roar', 1.0, 0.9)])))
    out.append(B.keep(m, B.with_sounds(m, 'death', [(0.1, 'ability_hiss', 0.9, 0.7), (1.0, 'ability_roar', 0.8, 0.6),
                                                (1.75, 'ability_slam', 1.0, 0.7), (1.85, 'ability_shatter', 1.0, 0.7)])))
    return out
