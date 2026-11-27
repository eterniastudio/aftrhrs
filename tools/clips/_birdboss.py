"""Shared builder for the two bird bosses (Cinderwing Phoenix, Tempest Roc) on the mounts' models: everything here is
named boss_* (or is a boss-only attack clip) so the tameable mount on the same model never plays it. Not a model module.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, wave, pulse, step  # noqa: E402
import _bossanim as B  # noqa: E402

WINGS = ('right_wing', 'left_wing', 'right_wing_mid', 'left_wing_mid', 'right_wing_tip', 'left_wing_tip')
UPPER = ('body', 'neck', 'h_head', 'jaw', 'crest', 'tail', 'tail_fan')


def amplify(c, bones, k):
    """Multiplies the rotation of `bones` (bigger wingbeats)."""
    for b in bones:
        f = c.tracks.get((b, 'rotation'))
        if f:
            c.tracks[(b, 'rotation')] = (lambda f: lambda t: tuple(v * k for v in f(t)))(f)


def hover_loop(m, name, L, speed=1.0):
    c = Clip(m, name, L, 'loop')
    B.ccopy(c, 'hover', speed=speed, period=m.length('hover'))
    return c


def build(m, glow_bone, spread_pose, screech_pose, voice='ability_screech'):
    """Calm / combat hovers, the blazing phase-2 hover, a screech taunt and the heavy flinch. Returns the clips."""
    out = []
    H0 = B.P(m, 'hover', 0)
    L = m.length('hover')

    c = hover_loop(m, 'boss_hover_menace', 2 * L)
    c.rot('h_head', lambda t: (-8, 0, 0), add=True)
    c.rot('neck', lambda t: (-6, 0, 3 * wave(t, 2 * L, 1)), add=True)
    c.rot('body', lambda t: (0, 0, 3 * wave(t, 2 * L, 1)), add=True)
    c.rot('crest', lambda t: (-10, 0, 0), add=True)
    c.rot('jaw', lambda t: (-8 * pulse(t, 0.3 * L, 1.2 * L), 0, 0), add=True)
    c.rot('tail_fan', lambda t: (0, 6 * wave(t, 2 * L, 1, 0.2), 0), add=True)
    out.append(B.keep(m, c))

    c = hover_loop(m, 'boss_hover_scan', 4 * L)
    T = 4 * L
    yaw = lambda t: 35 * (step(t, 0.1 * T, 0.25 * T) - step(t, 0.45 * T, 0.6 * T)) - 35 * (step(t, 0.45 * T, 0.6 * T) - step(t, 0.8 * T, 0.95 * T))
    c.rot('h_head', lambda t: (-4, 0.6 * yaw(t), 0), add=True)
    c.rot('neck', lambda t: (0, 0.4 * yaw(t - 0.1), 0), add=True)
    c.rot('body', lambda t: (0, 0.15 * yaw(t - 0.25), 0), add=True)
    out.append(B.keep(m, c))

    c = hover_loop(m, 'boss_hover_flare', 3 * L)
    T = 3 * L
    fl = lambda t: pulse(t, 0.3 * T, 0.85 * T)
    amplify(c, WINGS[:2], 1.2)
    c.rot('crest', lambda t: (-18 * fl(t), 0, 0), add=True)
    c.rot('tail_fan', lambda t: (14 * fl(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (12 * fl(t), 0, 0), add=True)
    c.rot('jaw', lambda t: (-22 * fl(t), 0, 0), add=True)
    c.rot('body', lambda t: (6 * fl(t), 0, 0), add=True)
    if glow_bone:
        c.scl(glow_bone, lambda t: (1 + 0.25 * fl(t),) * 3)
    B.sounds(c, [(0.35 * T, 'ability_flare', 0.7, 1.0)])
    out.append(B.keep(m, c))

    # phase 2: faster, bigger wingbeats, head low and crest blazing
    c = hover_loop(m, 'boss_hover_blaze', 2 * L, speed=1.5)
    amplify(c, WINGS[:2], 1.25)
    c.rot('h_head', lambda t: (-10, 0, 0), add=True)
    c.rot('crest', lambda t: (-16 + 4 * wave(t, 2 * L, 3), 0, 0), add=True)
    c.rot('jaw', lambda t: (-10 - 6 * wave(t, 2 * L, 3), 0, 0), add=True)
    c.rot('tail_fan', lambda t: (10, 8 * wave(t, 2 * L, 3), 0), add=True)
    if glow_bone:
        c.scl(glow_bone, lambda t: (1.15 + 0.1 * wave(t, 2 * L, 3),) * 3)
    out.append(B.keep(m, c))

    # taunt: rears up, wings flung wide, a long screech with the head thrashing
    c = Clip(m, 'boss_taunt', 2.2, 'once')
    wide = B.add(spread_pose, B.off(rot={'jaw': (-38, 0, 0), 'crest': (-20, 0, 0), 'h_head': (18, 0, 0), 'tail_fan': (16, 0, 0)}))
    B.seq(c, [(0, H0), (0.35, B.mix(H0, screech_pose, 0.7)), (0.65, wide, 'back'), (1.7, wide), (2.2, H0)])
    B.shake(c, 'h_head', 0.65, 1.7, 7, 9, axes=(1, 1, 0))
    B.sounds(c, [(0.15, 'ability_whoosh', 0.7, 1.2), (0.6, voice, 1.0, 1.0)])
    out.append(B.keep(m, c))

    # heavy hit: knocked off balance in the air, wings flail, recovers
    c = Clip(m, 'boss_hurt_heavy', 0.85, 'once')
    knock = B.add(H0, B.off(rot={'body': (-18, 0, 16), 'neck': (14, 0, 0), 'h_head': (20, 10, 0), 'jaw': (-20, 0, 0),
                                 'right_wing': (0, 0, 40), 'left_wing': (0, 0, -10), 'tail': (14, 0, 0)},
                            pos={'body': (0, -1.5, 1.5)}))
    B.seq(c, [(0, H0), (0.1, knock, 'out'), (0.35, knock), (0.85, H0)])
    B.shake(c, 'body', 0.1, 0.5, 4, 11)
    B.sounds(c, [(0.03, voice, 0.6, 1.3)])
    out.append(B.keep(m, c))
    return out


IDLE = {
    'boss_calm': [
        {'clip': 'boss_hover_menace', 'w': 3, 'min': 5, 'max': 10},
        {'clip': 'hover', 'w': 2, 'min': 4, 'max': 8},
        {'clip': 'boss_hover_scan', 'w': 2, 'min': 5, 'max': 5},
        {'clip': 'boss_hover_flare', 'w': 1, 'min': 4, 'max': 4, 'cd': 15},
    ],
    'boss_combat': [
        {'clip': 'boss_hover_menace', 'w': 4, 'min': 4, 'max': 8, 'when': 'p1'},
        {'clip': 'boss_hover_flare', 'w': 1, 'min': 4, 'max': 4, 'cd': 12, 'when': 'p1'},
        {'clip': 'boss_hover_blaze', 'w': 5, 'min': 4, 'max': 8, 'when': 'p2+'},
        {'clip': 'boss_taunt', 'w': 1, 'cd': 22, 'when': 'far'},
    ],
}
TAGS = {n: {'p': 'airborne', 'loop': 'loop'} for n in ('boss_hover_menace', 'boss_hover_scan', 'boss_hover_flare', 'boss_hover_blaze')}
STATES = {'stance1': 'boss_hover_menace', 'stance2': 'boss_hover_blaze', 'hurt_heavy': 'boss_hurt_heavy'}
