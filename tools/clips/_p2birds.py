"""Ground life for the two flying mounts, Cinderwing Phoenix and Tempest Roc (phase 2). Their generated standing
clips all kept the wings spread and flapping on the ground; here the wings fold (ride_idle's folded pose), the walk
becomes a strut, and they get preening, wing settles, a horizon scan, a wings-open stance, a head-tucked sleep and
weightier ridden takeoff / touchdown accents. Not a model module (imported by cinderwing_phoenix.py / tempest_roc.py)."""
from animkit import Clip, keys, pulse, wave
from _p2kit import (copy_from, env, fold, frozen, hold, keep, pair, pos, pose_of, rot, sampler, scl, subtree, t3)

# expressive one-shots whose wing motion is kept in the middle (they open and refold); the rest fold tight
SPREAD = {'happy': 0.9, 'celebrate': 1.0, 'tame': 1.0, 'greet': 0.8, 'startle': 0.9, 'stretch': 1.0, 'dance': 0.8,
          'beg_trick': 0.7, 'shake': 0.6, 'hop': 0.6, 'spin': 0.5, 'ability': 0.8}
FLOURISH = ('sniff', 'stretch', 'yawn', 'groom', 'spin', 'roll_over', 'play_bow', 'hop', 'nod_off', 'shiver', 'shake',
            'pant', 'sigh', 'greet', 'notice', 'curious', 'beg_trick', 'celebrate', 'cower', 'startle', 'dance', 'happy',
            'tame', 'ability')

TAGS = {'preen': {'layer': 'overlay', 'move': False}, 'wing_settle': {'layer': 'overlay', 'move': False},
        'scan': {'layer': 'overlay', 'move': True}, 'sleep_tuck': {'p': 'lying'}}


def bird(m, cfg):
    """cfg: wings_r / wings_l (shoulder, mid, tip), legs ((leg, talon) right, (leg, talon) left), fan_r / fan_l (tail
    fan bones spreading outward with +y / -y), crest (bones that rise with -x), spread_name (the wings-open stance),
    sig fn(m, folded, wing_bones) -> [clips] (signature flourishes)."""
    out = []
    ride_idle = frozen(m, 'ride_idle')
    wing_bones = subtree(m, cfg['wings_r'][0]) + subtree(m, cfg['wings_l'][0])
    folded = {k: v for k, v in pose_of(ride_idle, 0).items() if k[0] in wing_bones}
    (rl, rt), (ll, lt) = cfg['legs']
    R, Lw = cfg['wings_r'], cfg['wings_l']
    fan_r, fan_l = cfg['fan_r'], cfg['fan_l']

    def fan(c, fn):
        for b in fan_r:
            rot(c, b, lambda t: (0, fn(t), 0))
        for b in fan_l:
            rot(c, b, lambda t: (0, -fn(t), 0))

    # ---- idle: wings folded, breathing, weight shifts, head looks around, tail fan and crest play
    L = 8.0
    c = Clip(m, 'idle', L, 'loop')
    hold(c, folded)
    copy_from(c, ride_idle, only=set(wing_bones) | {'tail', 'tail_fan'} | set(fan_r + fan_l))
    pos(c, 'body', lambda t: (0, 0.35 * wave(t, L, 4), 0))
    rot(c, 'body', lambda t: (1.5 * wave(t, L, 2, 0.1), 0, 2.2 * wave(t, L, 1)))
    rot(c, rl, lambda t: (0, 0, -2.2 * wave(t, L, 1)))
    rot(c, ll, lambda t: (0, 0, -2.2 * wave(t, L, 1)))
    rot(c, 'neck', lambda t: (3 * wave(t, L, 2, 0.25) + keys(t, [(0, 0), (5.6, 0), (6.0, -6), (6.5, 0), (8, 0)]), 0, 0))
    rot(c, 'h_head', lambda t: (keys(t, [(0, 0), (1.2, 0), (1.6, -4), (3.4, -4), (3.8, 6), (5.0, 6), (5.4, 0), (8, 0)]),
                                keys(t, [(0, 0), (1.2, 0), (1.6, 32), (3.4, 32), (3.8, -24), (5.0, -24), (5.4, 0), (8, 0)]),
                                keys(t, [(0, 0), (1.6, 0), (2.0, 10), (3.0, 10), (3.4, 0), (8, 0)])))
    for b in cfg['crest']:
        rot(c, b, lambda t: (-4 * pulse(t, 1.4, 2.4) - 6 * pulse(t, 3.6, 4.4), 0, 0))
    fan(c, lambda t: 6 * pulse(t, 6.6, 7.6))
    m.put(c)
    out.append(keep(m, c))
    idle = frozen(m, 'idle')

    # ---- strut walk (ground) and its moods: legs from ride_walk, wings folded, pigeon head pump, tail sway
    rw = frozen(m, 'ride_walk')
    WL = float(rw['animation_length'])

    def strut(name, Lc, amp=1.0, bob=1.0, crouch=0.0, neck=0.0, head=0.0, pump=1.0, limp=0.0, spread=0.0, roll=1.0):
        c = Clip(m, name, Lc, 'loop')
        hold(c, folded)
        sp = WL / Lc
        for b in (rl, rt, ll, lt):
            for ch in ('rotation', 'position'):
                f = sampler(rw, b, ch, speed=sp)
                a = amp * ((1 - limp) if b in (rl, rt) else 1)
                c.tracks[(b, ch)] = (lambda f, a: lambda t: tuple(x * a for x in t3(f(t))))(f, a)
        pos(c, 'body', lambda t: (0, -crouch + 0.6 * bob * abs(wave(t, Lc, 1)) - 0.3 * bob, 0))
        rot(c, 'body', lambda t: (-crouch * 1.5 + 1.5 * bob * wave(t, Lc, 2, 0.25), 0, 2.4 * roll * wave(t, Lc, 1)))
        rot(c, 'neck', lambda t: (neck + 7 * pump * wave(t, Lc, 2), 0, 0))
        rot(c, 'h_head', lambda t: (head - 6 * pump * wave(t, Lc, 2, 0.08), 0, -1.5 * wave(t, Lc, 1)))
        rot(c, 'tail', lambda t: (2 * wave(t, Lc, 2, 0.4), 4 * wave(t, Lc, 1, 0.2), 0))
        fan(c, lambda t: spread)
        pair(c, R[0], Lw[0], lambda t: (0, 0, 2 * wave(t, Lc, 2, 0.3)))
        if limp:
            rot(c, 'body', lambda t: (0, 0, 3 * pulse(t % Lc, 0.0, Lc * 0.5)))
        c.sound(Lc * 0.25, 'step', 0.6)
        c.sound(Lc * 0.75, 'step', 0.6)
        return c

    out.append(strut('walk', WL))
    out.append(strut('ride_walk', WL, pump=0.6))
    out.append(strut('walk_happy', WL * 0.85, amp=1.2, bob=2.2, pump=1.3, spread=8, neck=4))
    out.append(strut('walk_sneak', WL * 1.6, amp=0.8, bob=0.4, crouch=4, neck=-18, head=16, pump=0.3))
    out.append(strut('walk_sleepy', WL * 1.5, amp=0.7, bob=0.6, neck=-12, head=-10, pump=0.4))
    out.append(strut('walk_limp', WL * 1.3, amp=0.9, limp=0.6, pump=0.6))
    out.append(strut('walk_scared', WL * 0.75, amp=1.3, bob=1.2, crouch=2.5, neck=-10, head=8, pump=1.4))

    # ---- mood idles and flourishes: wings folded (expressive ones open and refold)
    for name in list(m.clips):
        if name in cfg.get('boss', ()) or name == 'idle_swim':
            continue
        if not (name.startswith('idle_') or name in FLOURISH):
            continue
        Ls = m.length(name)
        sp = SPREAD.get(name)
        if name == 'idle_hot':
            spread = (lambda t: 0.45)        # holds its wings off the body to cool down
        elif sp:
            spread = (lambda Ls, sp: lambda t: sp * env(t, 0.12 * Ls, 0.3 * Ls, 0.7 * Ls, 0.92 * Ls))(Ls, sp)
        else:
            spread = None
        out.append(fold(m, name, folded, wing_bones, k=0.25 if name.startswith('idle_') else 0.35, spread=spread))

    # ---- partial flourishes (overlay: they work standing and sitting): preen, wing settle, scan
    c = Clip(m, 'preen', 3.4)
    e = lambda t: env(t, 0.0, 0.6, 2.7, 3.4)
    nib = lambda t: wave(t, 0.3, 1) * env(t, 0.8, 0.9, 2.4, 2.6)
    rot(c, 'neck', lambda t: (-14 * e(t), 62 * e(t), 0))
    rot(c, 'h_head', lambda t: ((-26 - 5 * nib(t)) * e(t), 58 * e(t), -18 * e(t)))
    hold(c, folded)
    lift = lambda t: env(t, 0.5, 0.9, 2.4, 2.9)
    rot(c, R[0], lambda t: (0, 10 * lift(t), 10 * lift(t)))
    rot(c, R[1], lambda t: (0, 6 * lift(t), 0))
    c.sound(1.0, 'minecraft:item.armor.equip_elytra', 0.35, 1.5)
    c.sound(1.8, 'minecraft:item.armor.equip_elytra', 0.3, 1.7)
    out.append(keep(m, c))

    c = Clip(m, 'wing_settle', 2.4)
    hold(c, folded)
    op = lambda t: keys(t, [(0, 0), (0.45, 1), (1.25, 1), (1.7, -0.08), (2.0, 0.03), (2.4, 0)])
    sh = lambda t: wave(t, 0.14, 1) * env(t, 0.5, 0.6, 1.1, 1.25)
    pair(c, R[0], Lw[0], lambda t: (0, 50 * op(t), 22 * op(t) + 6 * sh(t)))
    pair(c, R[1], Lw[1], lambda t: (0, 22 * op(t), 4 * sh(t)))
    pair(c, R[2], Lw[2], lambda t: (0, 10 * op(t), 6 * sh(t)))
    rot(c, 'neck', lambda t: (6 * op(t), 0, 0))
    rot(c, 'h_head', lambda t: (-4 * op(t), 0, 4 * sh(t)))
    rot(c, 'tail', lambda t: (8 * op(t), 6 * sh(t), 0))
    fan(c, lambda t: 12 * op(t))
    c.sound(0.3, 'flap', 0.5, 1.1)
    c.sound(0.7, 'minecraft:entity.parrot.fly', 0.6, 0.8)
    c.sound(1.6, 'flap', 0.35, 1.3)
    out.append(keep(m, c))

    c = Clip(m, 'scan', 3.6)
    e2 = lambda t: env(t, 0.0, 0.5, 3.0, 3.6)
    rot(c, 'neck', lambda t: (14 * e2(t), 0, 0))
    rot(c, 'h_head', lambda t: (-6 * e2(t), keys(t, [(0, 0), (0.6, 0), (1.2, 42), (1.9, 42), (2.5, -38), (3.0, -38), (3.6, 0)]), 0))
    for b in cfg['crest']:
        rot(c, b, lambda t: (-10 * env(t, 0.4, 0.7, 2.8, 3.4), 0, 0))
    out.append(keep(m, c))

    # ---- wings-open stance (standing loop variant): sunning (phoenix) / leaning into the storm wind (roc)
    c = Clip(m, cfg.get('spread_name', 'sun_wings'), 6.0, 'loop')
    copy_from(c, idle, skip=set(wing_bones) | {'h_head', 'neck'}, loop=True, speed=8.0 / 6.0)
    hold(c, folded)
    pair(c, R[0], Lw[0], lambda t: (0, 54 + 2 * wave(t, 6, 2), 26 + 3 * wave(t, 6, 3)))
    pair(c, R[1], Lw[1], lambda t: (0, 20 + 2 * wave(t, 6, 2, 0.2), -2))
    pair(c, R[2], Lw[2], lambda t: (0, 10, 3 * wave(t, 6, 3, 0.3)))
    rot(c, 'neck', lambda t: (10 + 2 * wave(t, 6, 1), 0, 0))
    rot(c, 'h_head', lambda t: (8, 14 * wave(t, 6, 1, 0.25), 0))
    fan(c, lambda t: 10)
    out.append(keep(m, c))

    # ---- sleep with the head tucked back into the wing (lying variant)
    sl = frozen(m, 'sleep')
    c = Clip(m, 'sleep_tuck', 4.0, 'loop')
    copy_from(c, sl, loop=True, skip={'neck', 'h_head'}, speed=float(sl['animation_length']) / 4.0)
    rot(c, 'neck', lambda t: (62, 18, 0))
    rot(c, 'h_head', lambda t: (-48 + 1.5 * wave(t, 4, 1), 165, 0))
    pos(c, 'body', lambda t: (0, 0.25 * wave(t, 4, 1), 0))
    out.append(keep(m, c))

    # ---- ridden takeoff / touchdown accents (additive: start and end at zero)
    c = Clip(m, 'ride_takeoff', 0.8)
    rot(c, 'body', lambda t: (keys(t, [(0, 0), (0.12, -7), (0.35, 14), (0.8, 0)]), 0, 0))
    pos(c, 'body', lambda t: (0, keys(t, [(0, 0), (0.12, -2.5), (0.35, 2.2), (0.8, 0)]), 0))
    for leg, tal in cfg['legs']:
        rot(c, leg, lambda t: (keys(t, [(0, 0), (0.12, 14), (0.32, -38), (0.8, 0)]), 0, 0))
        rot(c, tal, lambda t: (keys(t, [(0, 0), (0.12, -12), (0.32, 34), (0.8, 0)]), 0, 0))
    pair(c, R[0], Lw[0], lambda t: (0, 0, keys(t, [(0, 0), (0.1, 26), (0.3, -30), (0.55, 8), (0.8, 0)])))
    pair(c, R[1], Lw[1], lambda t: (0, 0, keys(t, [(0, 0), (0.14, 12), (0.34, -14), (0.8, 0)])))
    rot(c, 'neck', lambda t: (keys(t, [(0, 0), (0.15, -10), (0.4, 8), (0.8, 0)]), 0, 0))
    rot(c, 'tail', lambda t: (keys(t, [(0, 0), (0.35, 12), (0.8, 0)]), 0, 0))
    c.sound(0.28, 'flap', 0.9, 0.85)
    out.append(keep(m, c))

    c = Clip(m, 'ride_land', 0.75)
    rot(c, 'body', lambda t: (keys(t, [(0, 0), (0.1, -5), (0.32, 3), (0.75, 0)]), 0, 0))
    pos(c, 'body', lambda t: (0, keys(t, [(0, 0), (0.1, -3.5), (0.32, 0.6), (0.75, 0)]), 0))
    scl(c, 'body', lambda t: (keys(t, [(0, 0), (0.1, 0.05), (0.3, 0), (0.75, 0)]),
                              keys(t, [(0, 0), (0.1, -0.07), (0.3, 0.02), (0.75, 0)]), 0))
    for leg, tal in cfg['legs']:
        rot(c, leg, lambda t: (keys(t, [(0, 0), (0.1, 20), (0.32, -4), (0.75, 0)]), 0, 0))
        rot(c, tal, lambda t: (keys(t, [(0, 0), (0.1, -22), (0.32, 4), (0.75, 0)]), 0, 0))
    pair(c, R[0], Lw[0], lambda t: (0, 0, keys(t, [(0, 0), (0.08, 24), (0.2, 30), (0.45, -4), (0.75, 0)])))
    rot(c, 'neck', lambda t: (keys(t, [(0, 0), (0.1, -12), (0.35, 6), (0.75, 0)]), 0, 0))
    rot(c, 'tail', lambda t: (keys(t, [(0, 0), (0.1, 14), (0.4, -3), (0.75, 0)]), 0, 0))
    out.append(keep(m, c))

    if cfg.get('sig'):
        out += cfg['sig'](m, folded, wing_bones)
    return out


def idle_tables(spread_name, spread_when, sig=()):
    """The two birds' idle pools (sig: [(clip, weight, cooldown)] signature flourishes)."""
    standing = [{'clip': '@mood', 'w': 5, 'min': 8, 'max': 16},
                {'clip': 'preen', 'w': 3, 'cd': 18}, {'clip': 'wing_settle', 'w': 2, 'cd': 16},
                {'clip': 'scan', 'w': 2, 'cd': 14}, {'clip': spread_name, 'w': 2, 'min': 6, 'max': 12, 'cd': 40, 'when': spread_when},
                {'clip': 'ride_ruffle', 'w': 1, 'cd': 20}, {'clip': 'ride_crest_flare', 'w': 1, 'cd': 24},
                {'clip': 'sniff', 'w': 0.5, 'cd': 30}, {'clip': 'scratch', 'w': 0.5, 'cd': 30},
                {'clip': 'stretch', 'w': 1, 'cd': 30}, {'clip': 'yawn', 'w': 1, 'cd': 40, 'boost': {'night': 3}}]
    standing += [{'clip': c, 'w': w, 'cd': cd} for c, w, cd in sig]
    sitting = [{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'preen', 'w': 3, 'cd': 20},
               {'clip': 'scan', 'w': 2, 'cd': 18}, {'clip': 'ride_ruffle', 'w': 1, 'cd': 20},
               {'clip': 'ride_crest_flare', 'w': 1, 'cd': 24}, {'clip': 'ride_look_back', 'w': 1, 'cd': 24}]
    lying = [{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'sleep_tuck', 'w': 2, 'min': 30, 'max': 90}]
    return {'standing': standing, 'sitting': sitting, 'lying': lying}
