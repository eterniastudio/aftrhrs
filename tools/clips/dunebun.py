"""Dunebun (hopping mount for two, a big fluffy rune-charmed bunny).

Phase 2: the ridden gaits were leg swings with no hop (ride_run's body barely left the ground); they are now bounds
with a real arc, pitch, stretch in the air and a squash on landing (the unridden run / walk were already bounds).
Unridden life: grazing, a periscope (up on the haunches, ears high, looking around), a binky (happy twist-jump), a
warning thump, nose twitches, an ear wash; sitting nose / ear play; lying sleep and the contented side flop."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, pulse, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, hold, pair, pos, pose_of, rot, scl  # noqa: E402

EARS = (('right_ear', 'right_ear_tip'), ('left_ear', 'left_ear_tip'))


def ears(c, fn, tip=None):
    """fn(t) -> right ear rotation delta (left mirrored); tip(t) for the tips."""
    pair(c, 'right_ear', 'left_ear', fn)
    if tip:
        pair(c, 'right_ear_tip', 'left_ear_tip', tip)


def clips(m):
    out = []
    idle, run, walk, sit, sleep = (frozen(m, n) for n in ('idle', 'run', 'walk', 'sit', 'sleep'))

    # ---- ridden bounds: the unridden bound, a bigger arc, nose-up push / nose-down reach, stretch and squash
    def bound(name, src, arc, ear_back):
        L = float(src['animation_length'])
        c = Clip(m, name, L, 'loop')
        copy_from(c, src)
        by = c.tracks[('body', 'position')]
        c.tracks[('body', 'position')] = lambda t: (0, by(t)[1] * arc, 0)
        air = lambda t: max(0.0, by(t)[1]) / 5.0
        scl(c, 'body', lambda t: (0, -0.05 * pulse(t, 0.3 * L, 0.5 * L) * 2 + 0.04 * air(t), 0.05 * air(t)))
        ears(c, lambda t: (ear_back + 10 * air(t), 0, 0), lambda t: (12 * air(t), 0, 0))
        rot(c, 'tail', lambda t: (-10 * air(t), 0, 0))
        c.sound(0.72 * L, 'step', 0.7, 0.9)
        return c

    out.append(bound('ride_run', run, 1.3, 26))
    out.append(bound('ride_walk', walk, 1.0, 14))

    # ---- graze (standing loop): head down, nibbling, ears relaxed, a look up now and then
    L = 6.0
    c = Clip(m, 'graze', L, 'loop')
    copy_from(c, idle, skip={'h_head'})
    down = lambda t: 1 - pulse(t, 3.4, 4.6)
    rot(c, 'h_head', lambda t: (-34 * down(t) + 3 * wave(t, 0.25, 1) * down(t), 6 * wave(t, L, 1), 0))
    rot(c, 'body', lambda t: (-6 * down(t), 0, 0))
    pos(c, 'body', lambda t: (0, -0.6 * down(t), 0))
    pair(c, 'right_arm', 'left_arm', lambda t: (6 * down(t), 0, 0))
    ears(c, lambda t: (20 * down(t) - 8 * pulse(t, 3.4, 4.6), 0, 0))
    for k in range(6):
        c.sound(0.5 + k * 0.5 if k < 5 else 5.0, 'eat', 0.25, 1.3)
    out.append(keep(m, c))

    # ---- periscope (one-shot): up on the haunches, ears tall, a slow look around, back down
    c = Clip(m, 'periscope', 3.6)
    copy_from(c, idle)
    up = lambda t: keys(t, [(0, 0), (0.35, -0.15), (0.75, 1), (2.8, 1), (3.3, -0.05), (3.6, 0)])
    rot(c, 'body', lambda t: (30 * up(t), 0, 0))
    pos(c, 'body', lambda t: (0, 3 * up(t), -2 * up(t)))
    pair(c, 'right_leg', 'left_leg', lambda t: (-30 * up(t), 0, 0))
    pair(c, 'right_foot', 'left_foot', lambda t: (0, 0, 0))
    pair(c, 'right_arm', 'left_arm', lambda t: (8 * up(t), 0, 0))
    rot(c, 'h_head', lambda t: (-26 * up(t), keys(t, [(0, 0), (1.0, 0), (1.5, 34), (2.0, 34), (2.4, -28), (2.8, -28), (3.2, 0)]), 0))
    ears(c, lambda t: (-14 * up(t), 0, 0), lambda t: (-6 * up(t), 0, 0))
    rot(c, 'tail', lambda t: (16 * up(t), 0, 0))
    out.append(keep(m, c))

    # ---- binky (one-shot, happy): crouch, leap with a mid-air body twist and head flick, land, wiggle
    c = Clip(m, 'binky', 1.8)
    copy_from(c, idle)
    air = lambda t: keys(t, [(0, 0), (0.25, -0.25), (0.45, 1), (0.85, 1.05), (1.1, 0), (1.25, -0.15), (1.5, 0)])
    pos(c, 'body', lambda t: (0, 9 * max(0, air(t)) + 3 * min(0, air(t)), 0))
    rot(c, 'body', lambda t: (keys(t, [(0, 0), (0.25, -6), (0.5, 14), (0.95, -8), (1.2, 0)]),
                              32 * pulse(t, 0.45, 1.05), 26 * pulse(t, 0.5, 1.05)))
    rot(c, 'h_head', lambda t: (0, -30 * pulse(t, 0.5, 1.1), -22 * pulse(t, 0.55, 1.05)))
    pair(c, 'right_leg', 'left_leg', lambda t: (keys(t, [(0, 0), (0.25, 10), (0.5, -45), (0.9, -20), (1.15, 0)]), 0, 0))
    pair(c, 'right_foot', 'left_foot', lambda t: (keys(t, [(0, 0), (0.5, 40), (0.9, 20), (1.15, 0)]), 0, 0))
    pair(c, 'right_arm', 'left_arm', lambda t: (keys(t, [(0, 0), (0.45, -30), (0.85, 30), (1.15, 0)]), 0, 0))
    ears(c, lambda t: (keys(t, [(0, 0), (0.45, 30), (0.9, -10), (1.3, 6), (1.8, 0)]), 0, 0),
         lambda t: (24 * pulse(t, 0.45, 1.3), 0, 0))
    rot(c, 'tail', lambda t: (0, 20 * wave(t, 0.18, 1) * env(t, 1.15, 1.2, 1.6, 1.8), 0))
    c.sound(0.42, 'minecraft:entity.rabbit.jump', 0.8, 0.8)
    c.sound(1.12, 'step', 0.8, 1.0)
    out.append(keep(m, c))
    m.put(c)
    # ArcanaDunebun_Binky plays `ability`: the binky, with the rune charm flaring
    binky = frozen(m, 'binky')
    c = Clip(m, 'ability', 1.8)
    copy_from(c, binky, loop=False)
    scl(c, 'charm', lambda t: (0.6 * pulse(t, 0.3, 1.3),) * 3)
    c.sound(0.42, 'minecraft:entity.rabbit.jump', 0.8, 0.8)
    c.sound(0.5, 'minecraft:block.amethyst_block.chime', 0.6, 1.4)
    c.sound(1.12, 'step', 0.8, 1.0)
    out.append(keep(m, c))

    # ---- thump (one-shot, alert): freeze, ears up, two hard hind-foot thumps
    c = Clip(m, 'thump', 1.6)
    copy_from(c, idle)
    th = lambda t: keys(t, [(0, 0), (0.3, 0), (0.42, 1), (0.5, 0), (0.85, 0), (0.97, 1), (1.05, 0)], 'linear')
    rot(c, 'body', lambda t: (keys(t, [(0, 0), (0.25, 5), (1.3, 5), (1.6, 0)]), 0, 0))
    pair(c, 'right_foot', 'left_foot', lambda t: (-26 * th(t), 0, 0))
    pair(c, 'right_leg', 'left_leg', lambda t: (8 * th(t), 0, 0))
    pos(c, 'body', lambda t: (0, -0.8 * th(t), 0))
    rot(c, 'h_head', lambda t: (keys(t, [(0, 0), (0.25, 10), (1.3, 10), (1.6, 0)]), 0, 0))
    ears(c, lambda t: (-18 * env(t, 0.0, 0.2, 1.3, 1.6), 0, 0), lambda t: (-8 * env(t, 0.0, 0.2, 1.3, 1.6), 0, 0))
    c.sound(0.45, 'minecraft:entity.rabbit.jump', 0.9, 0.45)
    c.sound(1.0, 'minecraft:entity.rabbit.jump', 0.9, 0.45)
    out.append(keep(m, c))

    # ---- nose twitch / ear play (partial overlays: standing and sitting)
    c = Clip(m, 'nose_twitch', 2.0)
    rot(c, 'h_head', lambda t: (4 * pulse(t, 0.0, 2.0) + 1.6 * wave(t, 0.12, 1) * env(t, 0.2, 0.3, 1.6, 1.8), 0,
                                3 * pulse(t, 0.0, 2.0)))
    ears(c, lambda t: (-6 * pulse(t, 0.1, 1.9), 0, 0), lambda t: (8 * wave(t, 0.5, 1) * env(t, 0.2, 0.4, 1.6, 1.9), 0, 0))
    c.sound(0.4, 'minecraft:entity.rabbit.ambient', 0.3, 1.5)
    out.append(keep(m, c))

    c = Clip(m, 'ear_flick', 1.4)
    rot(c, 'right_ear', lambda t: (keys(t, [(0, 0), (0.15, 26), (0.3, -6), (0.45, 3), (0.6, 0)]), 0, 0))
    rot(c, 'right_ear_tip', lambda t: (keys(t, [(0, 0), (0.2, -20), (0.4, 12), (0.6, 0)]), 0, 0))
    rot(c, 'left_ear', lambda t: (keys(t, [(0, 0), (0.6, 0), (0.75, 24), (0.9, -5), (1.05, 2), (1.2, 0)]), 0, 0))
    rot(c, 'left_ear_tip', lambda t: (keys(t, [(0, 0), (0.8, -18), (1.0, 10), (1.2, 0)]), 0, 0))
    rot(c, 'h_head', lambda t: (0, 0, 6 * pulse(t, 0.05, 0.6) - 6 * pulse(t, 0.65, 1.2)))
    out.append(keep(m, c))

    # ---- ear wash (overlay over stand or sit): paws up, pull an ear down and scrub it
    c = Clip(m, 'ear_wash', 3.0)
    e = lambda t: env(t, 0.0, 0.4, 2.5, 3.0)
    scrub = lambda t: wave(t, 0.35, 1) * env(t, 0.5, 0.6, 2.3, 2.5)
    pair(c, 'right_arm', 'left_arm', lambda t: (72 * e(t) + 10 * scrub(t), 0, 0))
    rot(c, 'h_head', lambda t: (-24 * e(t) + 4 * scrub(t), 0, -14 * e(t)))
    rot(c, 'right_ear', lambda t: (34 * e(t), 0, 30 * e(t)))
    rot(c, 'right_ear_tip', lambda t: (20 * e(t) + 8 * scrub(t), 0, 0))
    rot(c, 'left_ear', lambda t: (10 * e(t), 0, 0))
    c.sound(0.9, 'minecraft:block.wool.hit', 0.25, 1.4)
    c.sound(1.6, 'minecraft:block.wool.hit', 0.25, 1.5)
    out.append(keep(m, c))

    # ---- the contented flop (lying variant): flops onto its side, legs out, slow breathing, ear twitch
    L = 5.0
    c = Clip(m, 'flop', L, 'loop')
    hold(c, pose_of(sleep, 0))
    rot(c, 'body', lambda t: (0, 0, 58))
    pos(c, 'body', lambda t: (3, -2.5 + 0.25 * wave(t, L, 2), 0))
    pair(c, 'right_leg', 'left_leg', lambda t: (-22, 0, 0))
    pair(c, 'right_arm', 'left_arm', lambda t: (34, 0, 0))
    rot(c, 'h_head', lambda t: (-6, 0, -18))
    rot(c, 'right_ear', lambda t: (8 * pulse(t, 3.2, 3.6), 0, 0))
    out.append(keep(m, c))
    import _p2kit
    _p2kit.reactions(m, out, pet='nose_twitch', pet_sit='ear_flick', bond_level='binky')
    return out


TAGS = {'graze': {'p': 'standing'}, 'periscope': {'p': 'standing'}, 'binky': {'p': 'standing'},
        'thump': {'p': 'standing'}, 'flop': {'p': 'lying'}, 'nose_twitch': {'layer': 'overlay', 'move': True},
        'ear_flick': {'layer': 'overlay', 'move': True}, 'ear_wash': {'layer': 'overlay', 'move': False}}

IDLE = {
    'standing': [{'clip': '@mood', 'w': 5, 'min': 6, 'max': 14},
                 {'clip': 'graze', 'w': 4, 'min': 8, 'max': 20, 'cd': 20, 'when': '!rain'},
                 {'clip': 'periscope', 'w': 2, 'cd': 18, 'boost': {'player_near:10': 1.5}},
                 {'clip': 'binky', 'w': 1, 'cd': 30, 'boost': {'happy': 5, 'owner_near': 1.5}},
                 {'clip': 'thump', 'w': 1, 'cd': 40, 'when': 'monster_near', 'boost': {'thunder': 3}},
                 {'clip': 'nose_twitch', 'w': 2, 'cd': 10}, {'clip': 'ear_flick', 'w': 2, 'cd': 10},
                 {'clip': 'ear_wash', 'w': 2, 'cd': 25}, {'clip': 'groom', 'w': 1, 'cd': 30},
                 {'clip': 'stretch', 'w': 1, 'cd': 30}, {'clip': 'yawn', 'w': 1, 'cd': 40, 'boost': {'night': 3}}],
    'sitting': [{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'nose_twitch', 'w': 3, 'cd': 10},
                {'clip': 'ear_flick', 'w': 2, 'cd': 12}, {'clip': 'ear_wash', 'w': 2, 'cd': 24}],
    'lying': [{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'flop', 'w': 2, 'min': 20, 'max': 60, 'when': '!night'}],
}
PROCEDURAL = {'ear_twitch': 1.4, 'tail_sway': 4}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
