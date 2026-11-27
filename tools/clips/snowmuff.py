"""Snowmuff (walking mount for two, a baby woolly mammoth).

Phase 2: gaits get weight (head nods on the steps, the trunk swings with the stride and lags behind, ears flap, a
heavier body bob with step sounds). Unridden life: an idle trunk swing, trunk grazing (reach down, curl a mouthful
up), a snow spray over its back, a playful stomp-and-toss, ear fanning and trunk toots (its ride fidgets, now also
idles); sitting trunk curls and ear fans; lying sleep and a trunk-curled snooze."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, pulse, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, hold, pair, pos, pose_of, rot, scl  # noqa: E402


def ears(c, fn):
    """fn(t) -> how far the ears fan out (degrees, + = out); mirrored."""
    rot(c, 'ear_r', lambda t: (0, 0, fn(t)))
    rot(c, 'ear_l', lambda t: (0, 0, -fn(t)))


def clips(m):
    out = []
    idle, sleep = frozen(m, 'idle'), frozen(m, 'sleep')

    # ---- gaits with weight: nods on the steps, trunk swing with lag, ear flaps, step sounds
    for name, nod, swing, flap in (('walk', 4, 10, 6), ('ride_walk', 4, 10, 6), ('run', 6, 14, 10), ('ride_run', 6, 14, 10),
                                   ('walk_happy', 5, 14, 10)):
        src = frozen(m, name)
        L = float(src['animation_length'])
        c = Clip(m, name, L, 'loop')
        copy_from(c, src)
        rot(c, 'h_head', lambda t, L=L, nod=nod: (nod * wave(t, L, 2, 0.15), 0, 0))
        rot(c, 'trunk', lambda t, L=L, s=swing: (4 * wave(t, L, 2, 0.3), 0, s * wave(t, L, 1, 0.1)))
        rot(c, 'trunk_tip', lambda t, L=L, s=swing: (6 * wave(t, L, 2, 0.45), 0, s * 1.2 * wave(t, L, 1, 0.25)))
        ears(c, lambda t, L=L, f=flap: f * wave(t, L, 2, 0.35))
        if 'walk' in name:
            c.sound(0.0, 'step', 0.5, 0.95)
            c.sound(L / 2, 'step', 0.5, 0.9)
        out.append(keep(m, c))

    # ---- idle trunk swing (standing loop): the trunk sways and curls, ears drift, weight shifts
    L = 6.0
    c = Clip(m, 'trunk_swing', L, 'loop')
    copy_from(c, idle, loop=True, skip={'trunk', 'trunk_tip'})
    rot(c, 'trunk', lambda t: (8 + 6 * wave(t, L, 2), 0, 16 * wave(t, L, 1)))
    rot(c, 'trunk_tip', lambda t: (14 + 10 * wave(t, L, 2, 0.15), 0, 20 * wave(t, L, 1, 0.12)))
    ears(c, lambda t: 6 + 6 * wave(t, L, 3))
    rot(c, 'body', lambda t: (0, 0, 2 * wave(t, L, 1, 0.25)))
    rot(c, 'h_head', lambda t: (2 * wave(t, L, 2), 5 * wave(t, L, 1, 0.3), 0))
    out.append(keep(m, c))

    # ---- trunk grazing (one-shot): reach down, grab, curl it up to the mouth, munch
    c = Clip(m, 'graze', 4.2)
    copy_from(c, idle, loop=True)
    dn = lambda t: keys(t, [(0, 0), (0.6, 1), (1.4, 1), (2.0, 0.2), (3.6, 0.2), (4.2, 0)])
    curl = lambda t: keys(t, [(0, 0), (1.4, 0), (2.0, 1), (3.4, 1), (4.2, 0)])
    rot(c, 'h_head', lambda t: (-22 * dn(t) + 2 * wave(t, 0.4, 1) * env(t, 2.1, 2.3, 3.4, 3.6), 0, 0))
    rot(c, 'body', lambda t: (-4 * dn(t), 0, 0))
    rot(c, 'trunk', lambda t: (-14 * dn(t) + 70 * curl(t), 0, 6 * wave(t, 0.5, 1) * env(t, 0.6, 0.8, 1.2, 1.4)))
    rot(c, 'trunk_tip', lambda t: (-10 * dn(t) + 60 * curl(t), 0, 0))
    ears(c, lambda t: 8 * curl(t))
    c.sound(1.3, 'minecraft:block.grass.break', 0.4, 0.8)
    c.sound(2.3, 'eat', 0.5, 1.0)
    c.sound(3.0, 'eat', 0.4, 1.1)
    out.append(keep(m, c))

    # ---- snow spray (one-shot): scoop low, swing the trunk up and back over the head, puff, shake it off
    c = Clip(m, 'snow_spray', 3.2)
    copy_from(c, idle, loop=True)
    scoop = lambda t: env(t, 0.0, 0.5, 0.8, 1.1)
    up = lambda t: keys(t, [(0, 0), (0.9, 0), (1.3, 1), (1.7, 1), (2.2, 0), (3.2, 0)])
    sh = lambda t: wave(t, 0.18, 1) * env(t, 2.1, 2.2, 2.8, 3.0)
    rot(c, 'h_head', lambda t: (-18 * scoop(t) + 24 * up(t), 0, 8 * sh(t)))
    rot(c, 'trunk', lambda t: (-20 * scoop(t) + 125 * up(t), 0, 0))
    rot(c, 'trunk_tip', lambda t: (-10 * scoop(t) + 50 * up(t), 0, 0))
    ears(c, lambda t: 18 * up(t) + 10 * sh(t))
    rot(c, 'body', lambda t: (3 * up(t), 0, 4 * sh(t)))
    rot(c, 'tail', lambda t: (0, 0, 20 * sh(t)))
    c.sound(0.7, 'minecraft:block.snow.break', 0.6, 0.9)
    c.sound(1.4, 'minecraft:block.powder_snow.break', 0.8, 1.1)
    c.sound(2.2, 'minecraft:block.wool.hit', 0.6, 0.8)
    out.append(keep(m, c))

    # ---- stomp and toss (one-shot, playful): rocks back, stamps both front feet, tosses its head and trunk
    c = Clip(m, 'stomp_toss', 2.4)
    copy_from(c, idle, loop=True)
    back = lambda t: keys(t, [(0, 0), (0.35, 1), (0.6, 0), (0.95, 1), (1.2, 0), (2.4, 0)])
    toss = lambda t: pulse(t, 1.3, 2.2)
    rot(c, 'body', lambda t: (6 * back(t), 0, 0))
    pos(c, 'body', lambda t: (0, 1.2 * back(t), 0.8 * back(t)))
    rot(c, 'leg_fr', lambda t: (34 * keys(t, [(0, 0), (0.35, 1), (0.6, 0), (2.4, 0)]), 0, 0))
    rot(c, 'leg_fl', lambda t: (34 * keys(t, [(0, 0), (0.6, 0), (0.95, 1), (1.2, 0), (2.4, 0)]), 0, 0))
    rot(c, 'h_head', lambda t: (16 * toss(t), 0, 10 * wave(t, 0.45, 1) * toss(t)))
    rot(c, 'trunk', lambda t: (60 * toss(t), 0, 14 * wave(t, 0.45, 1, 0.1) * toss(t)))
    rot(c, 'trunk_tip', lambda t: (40 * toss(t), 0, 0))
    ears(c, lambda t: 22 * toss(t))
    c.sound(0.6, 'step', 1.0, 0.8)
    c.sound(1.2, 'step', 1.0, 0.8)
    c.sound(1.6, 'ambient_happy', 0.7, 1.1)
    out.append(keep(m, c))

    # ---- trunk curl (partial overlay: standing and sitting): curls the trunk up, holds, lets it uncurl
    c = Clip(m, 'trunk_curl', 2.6)
    cu = lambda t: env(t, 0.0, 0.6, 1.8, 2.6)
    rot(c, 'trunk', lambda t: (55 * cu(t), 0, 5 * wave(t, 1.3, 1) * cu(t)))
    rot(c, 'trunk_tip', lambda t: (75 * cu(t), 0, 0))
    rot(c, 'h_head', lambda t: (6 * cu(t), 0, 0))
    out.append(keep(m, c))

    # ---- trunk-curled snooze (lying variant)
    L = 4.0
    c = Clip(m, 'sleep_curl', L, 'loop')
    copy_from(c, sleep, loop=True, speed=3.0 / L)
    rot(c, 'trunk', lambda t: (40, 0, 30))
    rot(c, 'trunk_tip', lambda t: (70 + 4 * wave(t, L, 1), 0, 20))
    rot(c, 'h_head', lambda t: (-6, -12, -8))
    ears(c, lambda t: -4 + 3 * pulse(t, 2.6, 3.0))
    pos(c, 'body', lambda t: (0, 0.3 * wave(t, L, 1), 0))
    out.append(keep(m, c))
    import _p2kit
    _p2kit.reactions(m, out, pet='ride_trunk_toot', pet_sit='trunk_curl', bond_level='stomp_toss')
    return out


TAGS = {'trunk_swing': {'p': 'standing'}, 'graze': {'p': 'standing'}, 'snow_spray': {'p': 'standing'},
        'stomp_toss': {'p': 'standing'}, 'trunk_curl': {'layer': 'overlay', 'move': True}, 'sleep_curl': {'p': 'lying'}}

IDLE = {
    'standing': [{'clip': '@mood', 'w': 4, 'min': 6, 'max': 14},
                 {'clip': 'trunk_swing', 'w': 4, 'min': 8, 'max': 18},
                 {'clip': 'graze', 'w': 3, 'cd': 14, 'when': '!rain'},
                 {'clip': 'snow_spray', 'w': 2, 'cd': 30, 'boost': {'cold': 3, 'hot': 2}},
                 {'clip': 'stomp_toss', 'w': 1, 'cd': 30, 'boost': {'happy': 4, 'owner_near': 1.5}},
                 {'clip': 'ride_ear_fan', 'w': 2, 'cd': 12, 'boost': {'hot': 3}},
                 {'clip': 'ride_trunk_toot', 'w': 1, 'cd': 30, 'boost': {'owner_near': 2}},
                 {'clip': 'trunk_curl', 'w': 2, 'cd': 14},
                 {'clip': 'shake', 'w': 1, 'cd': 40, 'boost': {'rain': 4}},
                 {'clip': 'stretch', 'w': 1, 'cd': 30}, {'clip': 'yawn', 'w': 1, 'cd': 40, 'boost': {'night': 3}}],
    'sitting': [{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'trunk_curl', 'w': 3, 'cd': 12},
                {'clip': 'ride_ear_fan', 'w': 2, 'cd': 14}, {'clip': 'ride_trunk_toot', 'w': 1, 'cd': 30}],
    'lying': [{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'sleep_curl', 'w': 2, 'min': 30, 'max': 90}],
}
PROCEDURAL = {'ear_twitch': 2.0, 'ears': ['ear_r', 'ear_l'], 'tail_sway': 6, 'tail': ['tail']}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
