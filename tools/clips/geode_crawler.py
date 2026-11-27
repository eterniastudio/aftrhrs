"""Geode Crawler (tameable hostile: a pudgy amethyst beetle).

Phase 2: its burrow / burrowed / emerge clips were never used. Wild: it lurks (the idle_wild loop digs in, waits
buried with only its crystals and antennae showing, and digs back out), skitters in jerky bursts with dead stops,
stalks low with mandibles working, and rears with mandibles spread and crystals flared when it picks a target
(emerge keeps playing as it starts to move). Tamed: antenna twitches, crystal preening, a happy crystal chime;
sits, sleeps, and sometimes sleeps burrowed."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, pulse, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, pos, rot, sampler, scl  # noqa: E402
import _p2hostile  # noqa: E402

LEGS = [s + '_' + p + '_leg' for s in ('right', 'left') for p in ('front', 'middle', 'back')]
TRIPOD_A = ('right_front_leg', 'left_middle_leg', 'right_back_leg')
CRYSTALS = ('crystal_back',)


def segments(c, parts):
    """parts: [(src_frozen, t_start, t_end, src_offset)] -> each bone channel samples the part covering t."""
    keys_ = set()
    for src, *_ in parts:
        for bone, chs in (src.get('bones') or {}).items():
            if c.m.has_bone(bone):
                for ch in chs:
                    keys_.add((bone, ch))
    for bone, ch in keys_:
        fns = [(t0, t1, sampler(src, bone, ch, offset=off - t0, loop=loop)) for src, t0, t1, off, loop in parts]

        def f(t, fns=fns):
            for t0, t1, g in fns:
                if t0 <= t < t1:
                    return g(t)
            return fns[-1][2](t)
        c.tracks[(bone, ch)] = f
    return c


def clips(m):
    out = []
    idle, walk, burrow, burrowed, emerge, sleep = (frozen(m, n) for n in ('idle', 'walk', 'burrow', 'burrowed', 'emerge', 'sleep'))
    Lb, Le = float(burrow['animation_length']), float(emerge['animation_length'])

    # ---- wild lurk: idle, dig in, wait buried, dig out, look about (one seamless loop)
    ta, tb, td = 1.6, 1.6 + Lb, 9.4
    L = td + Le + 1.6
    c = Clip(m, 'idle_wild', L, 'loop')
    segments(c, [(idle, 0, ta, 0, True), (burrow, ta, tb, 0, False), (burrowed, tb, td, 0, True),
                 (emerge, td, td + Le, 0, False), (idle, td + Le, L + 1, 0, True)])
    rot(c, 'right_antenna', lambda t: (0, 14 * wave(t, 1.3, 1) * env(t, tb + 0.5, tb + 1, td - 1, td - 0.5), 0))
    rot(c, 'left_antenna', lambda t: (0, -14 * wave(t, 1.7, 1) * env(t, tb + 0.5, tb + 1, td - 1, td - 0.5), 0))
    scl(c, 'crystal_back', lambda t: (0.06 * wave(t, 2.0, 1) * env(t, tb, tb + 1, td - 1, td),) * 3)
    c.sound(ta + 0.2, 'minecraft:block.gravel.break', 0.5, 0.9)
    c.sound(ta + 0.7, 'minecraft:block.gravel.break', 0.4, 1.0)
    c.sound(td + 0.2, 'minecraft:block.gravel.break', 0.6, 0.8)
    c.sound(td + 0.5, 'minecraft:block.amethyst_cluster.step', 0.5, 1.2)
    out.append(keep(m, c))

    # ---- skitter (wild one-shot): three fast tripod bursts sideways and back, each ending in a dead stop
    c = Clip(m, 'skitter', 2.4)
    copy_from(c, idle, loop=True)
    burst = lambda t: sum(env(t, s, s + 0.05, s + 0.3, s + 0.35) for s in (0.2, 0.9, 1.6))
    side = lambda t: keys(t, [(0, 0), (0.2, 0), (0.55, 2.5), (0.9, 2.5), (1.25, -1.5), (1.6, -1.5), (1.95, 0), (2.4, 0)])
    pos(c, 'body', lambda t: (side(t), 0.3 * burst(t), 0))
    rot(c, 'body', lambda t: (0, 8 * wave(t, 1.4, 1) * burst(t), 0))
    for leg in LEGS:
        s = 1 if leg in TRIPOD_A else -1
        rot(c, leg, lambda t, s=s: (0, s * 26 * wave(t, 0.12, 1) * burst(t), 12 * abs(wave(t, 0.12, 1)) * burst(t)))
    rot(c, 'h_head', lambda t: (0, keys(t, [(0, 0), (0.55, 14), (1.25, -14), (1.95, 0)]), 0))
    for k in (0.25, 0.4, 0.95, 1.1, 1.65, 1.8):
        c.sound(k, 'step', 0.4, 1.3)
    out.append(keep(m, c))

    # ---- stalk: low and slow, mandibles working, antennae forward
    L = 1.4
    c = Clip(m, 'stalk', L, 'loop')
    copy_from(c, walk, speed=float(walk['animation_length']) / L)
    pos(c, 'body', lambda t: (0, -2, 0))
    rot(c, 'body', lambda t: (-4, 0, 0))
    rot(c, 'right_mandible', lambda t: (0, -10 * abs(wave(t, 0.7, 1)), 0))
    rot(c, 'left_mandible', lambda t: (0, 10 * abs(wave(t, 0.7, 1)), 0))
    rot(c, 'right_antenna', lambda t: (-24, -8, 0))
    rot(c, 'left_antenna', lambda t: (-24, 8, 0))
    out.append(keep(m, c))

    # ---- threat (overlay): rears its front, mandibles spread wide, crystals flare, antennae back, chitter
    c = Clip(m, 'threat', 1.4)
    e = lambda t: keys(t, [(0, 0), (0.15, -0.15), (0.35, 1), (1.0, 1), (1.4, 0)])
    rot(c, 'thorax', lambda t: (14 * e(t), 0, 0))
    rot(c, 'h_head', lambda t: (10 * e(t), 0, 0))
    rot(c, 'right_mandible', lambda t: (0, -34 * e(t) + 8 * wave(t, 0.2, 1) * env(t, 0.4, 0.5, 0.9, 1.0), 0))
    rot(c, 'left_mandible', lambda t: (0, 34 * e(t) - 8 * wave(t, 0.2, 1) * env(t, 0.4, 0.5, 0.9, 1.0), 0))
    rot(c, 'right_antenna', lambda t: (30 * e(t), -20 * e(t), 0))
    rot(c, 'left_antenna', lambda t: (30 * e(t), 20 * e(t), 0))
    scl(c, 'crystal_back', lambda t: (0.18 * e(t),) * 3)
    c.sound(0.3, 'ambient_angry', 1.0, 1.0)
    c.sound(0.35, 'minecraft:block.amethyst_cluster.hit', 0.7, 0.8)
    out.append(keep(m, c))

    # ---- tamed: antenna twitch (overlay, any posture)
    c = Clip(m, 'antenna_twitch', 1.6)
    rot(c, 'right_antenna', lambda t: (keys(t, [(0, 0), (0.1, -18), (0.25, 6), (0.4, -10), (0.6, 0)]), 10 * pulse(t, 0, 0.6), 0))
    rot(c, 'left_antenna', lambda t: (keys(t, [(0, 0), (0.7, 0), (0.8, -16), (0.95, 8), (1.1, -6), (1.3, 0)]), -10 * pulse(t, 0.7, 1.3), 0))
    rot(c, 'h_head', lambda t: (0, 0, 6 * pulse(t, 0, 0.6) - 6 * pulse(t, 0.7, 1.3)))
    out.append(keep(m, c))

    # ---- tamed: crystal preening (one-shot): front legs reach back over the crystals and polish them
    c = Clip(m, 'crystal_preen', 3.2)
    copy_from(c, idle, loop=True)
    e2 = lambda t: env(t, 0, 0.5, 2.6, 3.2)
    rub = lambda t: wave(t, 0.4, 1) * env(t, 0.6, 0.7, 2.4, 2.6)
    rot(c, 'right_front_leg', lambda t: (0, -30 * e2(t) + 10 * rub(t), -40 * e2(t)))
    rot(c, 'left_front_leg', lambda t: (0, 30 * e2(t) - 10 * rub(t), 40 * e2(t)))
    rot(c, 'thorax', lambda t: (8 * e2(t), 0, 0))
    rot(c, 'crystal_back', lambda t: (0, 0, 2 * rub(t)))
    c.sound(1.0, 'minecraft:block.amethyst_block.step', 0.4, 1.4)
    c.sound(1.8, 'minecraft:block.amethyst_block.step', 0.4, 1.5)
    out.append(keep(m, c))

    # ---- tamed: happy chime (one-shot): a little bounce, crystals shimmer one after another
    c = Clip(m, 'chime', 1.8)
    copy_from(c, idle, loop=True)
    pos(c, 'body', lambda t: (0, 1.2 * pulse(t, 0.1, 0.5) + 0.8 * pulse(t, 0.6, 0.95), 0))
    for i, b in enumerate(('crystal_main_r', 'crystal_right', 'crystal_left', 'crystal_front_r')):
        if m.has_bone(b):
            scl(c, b, lambda t, i=i: (0.2 * pulse(t, 0.3 + i * 0.25, 0.7 + i * 0.25),) * 3)
            c.sound(0.4 + i * 0.25, 'minecraft:block.amethyst_block.chime', 0.6, 1.0 + i * 0.15)
    rot(c, 'right_antenna', lambda t: (-12 * pulse(t, 0, 1.8), 0, 0))
    rot(c, 'left_antenna', lambda t: (-12 * pulse(t, 0, 1.8), 0, 0))
    out.append(keep(m, c))
    import _p2kit
    _p2kit.reactions(m, out, pet='chime', pet_sit='antenna_twitch', bond_level='celebrate')
    return out


TAGS = dict(_p2hostile.TAGS, skitter={'p': 'standing'}, antenna_twitch={'layer': 'overlay', 'move': True},
            crystal_preen={'p': 'standing'}, chime={'p': 'standing'}, emerge={'move': True},
            burrowed={'p': 'lying', 'loop': 'loop'})
IDLE, STATES = _p2hostile.tables(
    wild=[('skitter', 2, 14), ('antenna_twitch', 2, 8), ('threat', 1, 30, 'player_near:10')],
    tame=[('antenna_twitch', 3, 8), ('crystal_preen', 2, 20, ''), ('chime', 2, 22, ''), ('skitter', 1, 30, 'happy'),
          ('crystal_burst', 0.5, 60, 'happy')],
    sitting=[{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'antenna_twitch', 'w': 3, 'cd': 8}],
    lying=[{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'burrowed', 'w': 2, 'min': 30, 'max': 90}])
PROCEDURAL = {'ears': ['right_antenna', 'left_antenna'], 'ear_twitch': 2.0}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
