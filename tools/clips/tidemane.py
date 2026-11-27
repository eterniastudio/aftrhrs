"""Tidemane (swim mount for two, a kelpie water horse). Parked when told to stay: the posture transitions
(sit_down, stand_up, lie_down, wake_up) are authored from its own idle / sit / sleep poses.

Phase 2: swimming reads as swimming: the neck stretches forward, the tail kicks harder with follow-through down the
fin, the mane streams; rising pitches the nose up for real, diving tips it down with the legs swept back; new
additive accents for the plunge into water and the breach out of it (ArcanaMount plays them). On land: kelp grazing,
pawing the ground, a rear-up when happy, tail swishes, head tosses (its mane-shake fidget, now also an idle); lying
sleep plus a flat-out sleep on its side."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, posture_set, pulse, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, hold, pair, pos, pose_of, rot, sampler, t3  # noqa: E402

LEGS = ('leg_fl', 'leg_fr', 'leg_hl', 'leg_hr')


def amplified(m, name, src, gain, bones, extra=None, new_name=None):
    """A loop copied from src with the motion of `bones` scaled by gain around each channel's mean."""
    L = float(src['animation_length'])
    c = Clip(m, new_name or name, L, 'loop')
    copy_from(c, src)
    for b in bones:
        if ('rotation' not in (src['bones'].get(b) or {})):
            continue
        f = sampler(src, b, 'rotation')
        vals = [t3(f(L * i / 24)) for i in range(25)]
        mean = tuple(sum(v[j] for v in vals) / len(vals) for j in range(3))
        c.tracks[(b, 'rotation')] = (lambda f, mean: lambda t: tuple(mean[j] + gain * (t3(f(t))[j] - mean[j])
                                                                     for j in range(3)))(f, mean)
    if extra:
        extra(c, L)
    return c


def clips(m):
    out = posture_set(m)
    for c in out:
        m.put(c)
    idle, sleep = frozen(m, 'idle'), frozen(m, 'sleep')
    tailb = ('tail', 'tail_b', 'tail_fin')

    # ---- water: stronger, readable swim states
    def swim_extra(pitch, neck, mane):
        def f(c, L):
            rot(c, 'body', lambda t: (pitch + 3 * wave(t, L, 1, 0.25), 0, 0))
            pos(c, 'body', lambda t: (0, 0.6 * wave(t, L, 1), 0))
            rot(c, 'neck', lambda t: (neck - 3 * wave(t, L, 1, 0.1), 0, 0))
            rot(c, 'h_head', lambda t: (-neck * 0.5, 0, 0))
            rot(c, 'mane', lambda t: (mane + 5 * wave(t, L, 2, 0.3), 0, 0))
            pair(c, 'ear_r', 'ear_l', lambda t: (16, 0, 0))
        return f

    for name, gain, pitch, neck, mane in (('ride_swim', 1.4, 0, -16, 14), ('swim', 1.4, 0, -14, 12),
                                          ('ride_rise', 1.5, 16, 8, 8), ('ride_dive', 1.5, -22, -18, 20)):
        src = frozen(m, name)
        c = amplified(m, name, src, gain, tailb, swim_extra(pitch, neck, mane))
        if name == 'ride_dive':
            pair(c, 'leg_fl', 'leg_fr', lambda t: (-20, 0, 0))
            pair(c, 'leg_hl', 'leg_hr', lambda t: (-14, 0, 0))
        c.sound(0.0, 'minecraft:entity.player.swim', 0.25, 0.8)
        out.append(keep(m, c))
    for name in ('ride_tread', 'idle_swim'):
        src = frozen(m, name)
        c = amplified(m, name, src, 1.3, tailb, swim_extra(6, 4, 8))
        out.append(keep(m, c))

    # plunge into / breach out of the water (additive accents: start and end at zero)
    c = Clip(m, 'ride_plunge', 0.8)
    rot(c, 'body', lambda t: (keys(t, [(0, 0), (0.15, -18), (0.5, 4), (0.8, 0)]), 0, 0))
    pos(c, 'body', lambda t: (0, keys(t, [(0, 0), (0.15, -2.5), (0.5, 0.5), (0.8, 0)]), 0))
    rot(c, 'neck', lambda t: (keys(t, [(0, 0), (0.12, 14), (0.35, -12), (0.8, 0)]), 0, 0))
    rot(c, 'tail', lambda t: (keys(t, [(0, 0), (0.18, -24), (0.45, 12), (0.8, 0)]), 0, 0))
    rot(c, 'tail_fin', lambda t: (keys(t, [(0, 0), (0.25, -20), (0.55, 10), (0.8, 0)]), 0, 0))
    pair(c, 'leg_fl', 'leg_fr', lambda t: (keys(t, [(0, 0), (0.15, -30), (0.8, 0)]), 0, 0))
    pair(c, 'leg_hl', 'leg_hr', lambda t: (keys(t, [(0, 0), (0.15, -24), (0.8, 0)]), 0, 0))
    rot(c, 'mane', lambda t: (keys(t, [(0, 0), (0.2, 20), (0.8, 0)]), 0, 0))
    out.append(keep(m, c))

    c = Clip(m, 'ride_breach', 0.9)
    rot(c, 'body', lambda t: (keys(t, [(0, 0), (0.2, 24), (0.55, -4), (0.9, 0)]), 0, 0))
    pos(c, 'body', lambda t: (0, keys(t, [(0, 0), (0.2, 2.5), (0.55, -0.6), (0.9, 0)]), 0))
    rot(c, 'neck', lambda t: (keys(t, [(0, 0), (0.2, 16), (0.5, -8), (0.9, 0)]), 0, 0))
    rot(c, 'h_head', lambda t: (keys(t, [(0, 0), (0.25, 10), (0.6, -6), (0.9, 0)]), 0, 0))
    pair(c, 'leg_fl', 'leg_fr', lambda t: (keys(t, [(0, 0), (0.2, 40), (0.5, 10), (0.9, 0)]), 0, 0))
    pair(c, 'leg_fl_low', 'leg_fr_low', lambda t: (keys(t, [(0, 0), (0.2, -50), (0.5, -10), (0.9, 0)]), 0, 0))
    rot(c, 'tail', lambda t: (keys(t, [(0, 0), (0.15, 20), (0.4, -14), (0.9, 0)]), 0, 0))
    rot(c, 'mane', lambda t: (keys(t, [(0, 0), (0.25, 16), (0.6, -6), (0.9, 0)]), 0, 0))
    c.sound(0.2, 'minecraft:entity.horse.breathe', 0.8, 0.9)
    out.append(keep(m, c))

    # ---- land: kelp grazing (standing loop variant), pawing, rearing, tail swish, head toss
    L = 6.0
    c = Clip(m, 'graze', L, 'loop')
    copy_from(c, idle, skip={'neck', 'h_head'})
    dn = lambda t: 1 - pulse(t, 3.6, 4.8)
    rot(c, 'neck', lambda t: (-52 * dn(t), 4 * wave(t, L, 1), 0))
    rot(c, 'h_head', lambda t: (-10 * dn(t) + 3 * wave(t, 0.35, 1) * dn(t), 0, 0))
    rot(c, 'mane', lambda t: (-6 * dn(t), 0, 0))
    pair(c, 'leg_fl', 'leg_fr', lambda t: (4 * dn(t), 0, 0))
    for k in range(5):
        c.sound(0.6 + k * 0.6, 'eat', 0.25, 1.1)
    out.append(keep(m, c))

    c = Clip(m, 'paw', 2.6)
    copy_from(c, idle)
    strike = lambda t: sum(pulse(t, 0.3 + k * 0.6, 0.8 + k * 0.6) for k in range(3))
    rot(c, 'leg_fr', lambda t: (52 * strike(t), 0, 0))
    rot(c, 'leg_fr_low', lambda t: (-95 * strike(t), 0, 0))
    rot(c, 'neck', lambda t: (-14 * env(t, 0, 0.3, 2.0, 2.6) + 4 * strike(t), 0, 0))
    rot(c, 'tail', lambda t: (0, 10 * wave(t, 0.6, 1) * env(t, 0.2, 0.4, 2.0, 2.4), 0))
    for k in range(3):
        c.sound(0.75 + k * 0.6, 'step', 0.8, 0.8)
    out.append(keep(m, c))

    c = Clip(m, 'rear', 2.6)
    copy_from(c, idle)
    up = lambda t: keys(t, [(0, 0), (0.3, -0.12), (0.8, 1), (1.6, 1), (2.1, -0.06), (2.6, 0)])
    pad = lambda t: wave(t, 0.4, 1) * env(t, 0.7, 0.8, 1.5, 1.7)
    rot(c, 'body', lambda t: (34 * up(t), 0, 0))
    pos(c, 'body', lambda t: (0, 5 * up(t), 3 * up(t)))
    pair(c, 'leg_hl', 'leg_hr', lambda t: (-34 * up(t), 0, 0))
    pair(c, 'leg_hl_low', 'leg_hr_low', lambda t: (8 * up(t), 0, 0))
    rot(c, 'leg_fl', lambda t: (40 * up(t) + 24 * pad(t), 0, 0))
    rot(c, 'leg_fr', lambda t: (40 * up(t) - 24 * pad(t), 0, 0))
    pair(c, 'leg_fl_low', 'leg_fr_low', lambda t: (-80 * up(t), 0, 0))
    rot(c, 'neck', lambda t: (-12 * up(t), 0, 0))
    rot(c, 'h_head', lambda t: (-6 * up(t), 0, 8 * pad(t)))
    rot(c, 'mane', lambda t: (14 * up(t), 0, 0))
    rot(c, 'tail', lambda t: (-20 * up(t), 0, 0))
    c.sound(0.7, 'minecraft:entity.horse.angry', 0.6, 1.1)
    c.sound(2.1, 'step', 1.0, 0.7)
    out.append(keep(m, c))

    c = Clip(m, 'tail_swish', 1.6)
    sw = lambda t: wave(t, 0.8, 1) * env(t, 0.0, 0.2, 1.3, 1.6)
    rot(c, 'tail', lambda t: (0, 0, 26 * sw(t)))
    rot(c, 'tail_b', lambda t: (0, 0, 22 * wave(t, 0.8, 1, -0.1) * env(t, 0.05, 0.3, 1.3, 1.6)))
    rot(c, 'tail_fin', lambda t: (0, 0, 18 * wave(t, 0.8, 1, -0.2) * env(t, 0.1, 0.35, 1.3, 1.6)))
    out.append(keep(m, c))

    # ---- flat-out sleep on its side (lying variant)
    L = 5.0
    c = Clip(m, 'sleep_flat', L, 'loop')
    hold(c, pose_of(sleep, 0))
    rot(c, 'body', lambda t: (0, 0, 64))
    pos(c, 'body', lambda t: (4, -3 + 0.3 * wave(t, L, 1), 0))
    rot(c, 'neck', lambda t: (-30, 0, -20))
    rot(c, 'h_head', lambda t: (-8, 0, -10))
    for b in LEGS:
        rot(c, b, lambda t: (8, 0, -10))
    rot(c, 'tail', lambda t: (10, 0, 0))
    if m.has_bone('h_eyes'):
        c.tracks[('h_eyes', 'scale')] = lambda t: (1, 0.1, 1)
    out.append(keep(m, c))
    import _p2kit
    _p2kit.reactions(m, out, pet='ride_shake_mane', pet_sit='tail_swish', bond_level='rear')
    return out


TAGS = {'graze': {'p': 'standing'}, 'paw': {'p': 'standing'}, 'rear': {'p': 'standing'},
        'tail_swish': {'layer': 'overlay', 'move': True}, 'sleep_flat': {'p': 'lying', 'eyes': True},
        'ride_plunge': {'layer': 'additive', 'move': True, 'p': 'swimming'},
        'ride_breach': {'layer': 'additive', 'move': True, 'p': 'swimming'}}

IDLE = {
    'standing': [{'clip': '@mood', 'w': 5, 'min': 6, 'max': 14},
                 {'clip': 'graze', 'w': 4, 'min': 8, 'max': 20, 'cd': 16},
                 {'clip': 'paw', 'w': 2, 'cd': 20, 'boost': {'owner_near': 2}},
                 {'clip': 'rear', 'w': 1, 'cd': 40, 'boost': {'happy': 5}},
                 {'clip': 'tail_swish', 'w': 3, 'cd': 8},
                 {'clip': 'ride_shake_mane', 'w': 2, 'cd': 16, 'boost': {'rain': 3}},
                 {'clip': 'groom', 'w': 1, 'cd': 30}, {'clip': 'yawn', 'w': 1, 'cd': 40, 'boost': {'night': 3}}],
    'sitting': [{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'tail_swish', 'w': 3, 'cd': 8},
                {'clip': 'ride_shake_mane', 'w': 2, 'cd': 18}, {'clip': 'ride_look_back', 'w': 1, 'cd': 20}],
    'lying': [{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'sleep_flat', 'w': 2, 'min': 30, 'max': 90}],
}
PROCEDURAL = {'bank': 1.6, 'bank_max': 22, 'ear_twitch': 1.4}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
