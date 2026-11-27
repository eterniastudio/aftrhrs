"""Honeycub (phase 1 + 2): a bear cub hugging a glowing honey pot, in a bee-stripe scarf.

Phase 1: its sit kept the body at standing height (legs stuck out under a floating body); the cub now plops down on its
rump (body lowered by the leg length, a little lean back, arms on its tummy); transitions re-authored.
Phase 2: its sleep had the same floating body (legs forward at standing height): it now sleeps slumped on its rump,
head on its chest, arms round the pot. Life: paw dipped in the honey and licked, rolling on its back (still only,
`belly`), plopping on its rump for a moment, swatting at a bee that buzzes round its head, hugging the pot, sniffing."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, posture_set  # noqa: E402
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step  # noqa: E402

BEE = 'minecraft:entity.bee.loop'
POLL = 'minecraft:entity.bee.pollinate'
SLURP = 'minecraft:item.honey_bottle.drink'
HONEY = 'minecraft:falling_honey'
ARMS = (('left_arm', 1), ('right_arm', -1))
LEGS = ('left_leg', 'right_leg')


def clips(m):
    base = m.pose('sit', 'end')
    sit = Clip(m, 'sit', 0.6, 'hold')
    for (bone, ch), v in base.items():
        sit.tracks[(bone, ch)] = (lambda v: lambda t: v)(v)
    sit.pos('body', lambda t: (0, -1.9, 0))
    sit.rot('body', lambda t: (7, 0, 0))
    sit.rot('h_head', lambda t: (-3, 0, 6))
    sit.rot('left_arm', lambda t: (-28, 0, -12))
    sit.rot('right_arm', lambda t: (-28, 0, 12))
    sit.pos('left_leg', lambda t: (0, 1.6, -0.4))
    sit.pos('right_leg', lambda t: (0, 1.6, -0.4))
    m.put(sit)

    # sleep: slumped on its rump like the sit, leaning back, chin on its chest, arms round the pot; eyes shut
    old = m.pose('sleep', 0)
    sleep = Clip(m, 'sleep', 4.0, 'loop')
    for (bone, ch), v in m.pose('sit', 'end').items():
        sleep.tracks[(bone, ch)] = (lambda v: lambda t: v)(v)
    for key in (('h_eyes', 'scale'), ('h_left_ear', 'rotation'), ('h_right_ear', 'rotation')):
        if key in old:
            sleep.tracks[key] = (lambda v: lambda t: v)(old[key])
    sleep.rot('body', lambda t: (12 + 1.5 * osc(t, 0.25), 0, -3))
    sleep.scl('body', lambda t: (1 + 0.02 * osc(t, 0.25), 1 + 0.03 * osc(t, 0.25), 1 + 0.02 * osc(t, 0.25)))
    sleep.rot('h_head', lambda t: (-24 - 2 * osc(t, 0.25, -0.1), 0, 10))
    sleep.pos('h_head', lambda t: (0, -0.4, 0))
    sleep.rot('left_arm', lambda t: (-20, 0, -18))
    sleep.rot('right_arm', lambda t: (-20, 0, 18))
    m.put(sleep)
    out = Out(m, [sit, sleep])
    for c in posture_set(m):
        m.put(c)
        out.append(c)

    # --- standing ----------------------------------------------------------------------------------------------
    # paw lick: dips a paw into the pot, licks it clean (head bobs), smacks its lips; honey drips
    c = start(m, 'paw_lick', 3.4)
    dip = lambda t: bump(t, 0.2, 0.9)
    lick = lambda t: env(t, 0.9, 2.8, 0.25)
    R(c, 'right_arm', lambda t: (-20 * dip(t) - 75 * lick(t), 0, 10 * dip(t) + 20 * lick(t)))
    R(c, 'h_head', lambda t: (-22 * dip(t) - 12 * lick(t) + 8 * osc(t, 3.0) * lick(t), 0, -10 * lick(t)))
    S(c, 'drip', lambda t: (0, 0.6 * bump(t, 0.4, 1.4), 0))
    R(c, 'h_left_ear', lambda t: (0, 0, 8 * lick(t)))
    c.sound(0.55, 'minecraft:block.honey_block.step', 0.5, 1.3)
    c.sound(1.2, SLURP, 0.35, 1.5)
    c.sound(1.9, SLURP, 0.3, 1.6)
    c.particle(0.6, HONEY, 2, 0.15, 0.0, 0.5)
    out.append(c)

    # back roll: plops over onto its back, wiggles its feet in the air, rocks, rolls back up (only while still)
    c = start(m, 'back_roll', 3.6)
    back = lambda t: step(t, 0.3, 0.8) * (1 - step(t, 2.7, 3.2))
    R(c, 'body', lambda t: (85 * back(t) + 12 * osc(t, 1.2) * env(t, 1.0, 2.6, 0.3), 0, 10 * osc(t, 0.6) * env(t, 0.9, 2.7, 0.3)))
    P(c, 'body', lambda t: (0, -1.0 * back(t), 2.0 * back(t)))
    for i, leg in enumerate(LEGS):
        R(c, leg, lambda t, i=i: (-25 * back(t) + 30 * osc(t, 2.4, 0.5 * i) * env(t, 0.9, 2.6, 0.2), 0, 0))
    for arm, s in ARMS:
        R(c, arm, lambda t, s=s: (-20 * back(t), 0, -s * 25 * back(t)))
    R(c, 'h_head', lambda t: (-35 * back(t), 0, 0))
    c.sound(0.75, 'minecraft:block.wool.fall', 0.5, 1.2)
    c.sound(1.4, 'ambient_happy', 0.7, 1.0)
    c.sound(3.15, 'minecraft:block.wool.step', 0.5, 1.1)
    out.append(c)

    # rump plop: sits down on its bottom with a bump, looks around pleased, hops back up
    c = start(m, 'rump_plop', 3.4)
    sp = m.pose('sit', 'end')
    idle0 = m.pose('idle', 0)
    d = lambda t: step(t, 0.25, 0.55) * (1 - step(t, 2.6, 3.0))
    for (bone, ch), v in sp.items():
        if ch == 'scale':
            continue
        a = idle0.get((bone, ch), (0, 0, 0))
        add_fn = (lambda a, v: lambda t: tuple((v[i] - a[i]) * d(t) for i in range(3)))(a, v)
        (R if ch == 'rotation' else P)(c, bone, add_fn)
    S(c, 'body', lambda t: (0.08 * bump(t, 0.5, 0.7), -0.1 * bump(t, 0.5, 0.7), 0.08 * bump(t, 0.5, 0.7)))
    R(c, 'h_head', lambda t: (0, 30 * osc(t, 0.6) * env(t, 0.8, 2.5, 0.3), 0))
    c.sound(0.55, 'minecraft:block.wool.fall', 0.4, 1.3)
    out.append(c)

    # bee swat: a bee buzzes round its head; its head follows it round, then a two-paw swat, a startled sit back
    c = start(m, 'bee_swat', 3.2)
    trk = lambda t: env(t, 0.1, 1.9, 0.25)
    R(c, 'h_head', lambda t: (10 * trk(t) + 6 * osc(t, 1.2, 0.25) * trk(t), 45 * osc(t, 1.2) * trk(t), 8 * osc(t, 1.2) * trk(t)))
    for arm, s in ARMS:
        R(c, arm, lambda t, s=s: (-120 * jolt(t, 1.85, 2.5) - 20 * bump(t, 1.5, 1.85), 0, s * 20 * jolt(t, 1.85, 2.5)))
    R(c, 'body', lambda t: (-8 * bump(t, 1.8, 2.2) + 10 * bump(t, 2.2, 2.9), 0, 0))
    P(c, 'root', lambda t: (0, 1.2 * bump(t, 1.9, 2.3), 0))
    R(c, 'h_left_ear', lambda t: (0, 0, 15 * bump(t, 2.0, 2.4)))
    R(c, 'h_right_ear', lambda t: (0, 0, -15 * bump(t, 2.0, 2.4)))
    c.sound(0.15, BEE, 0.25, 1.4)
    c.sound(1.95, 'minecraft:entity.player.attack.weak', 0.3, 1.6)
    c.sound(2.15, 'minecraft:entity.bee.hurt', 0.2, 1.4)
    out.append(c)

    # pot hug: squeezes its honey pot, sways side to side, nuzzles it
    c = start(m, 'pot_hug', 3.0)
    h = lambda t: env(t, 0.2, 2.8, 0.35)
    for arm, s in ARMS:
        R(c, arm, lambda t, s=s: (-15 * h(t), -s * 25 * h(t), 0))
    S(c, 'pot', lambda t: (0.08 * h(t), -0.05 * h(t), 0.08 * h(t)))
    R(c, 'body', lambda t: (0, 0, 7 * osc(t, 0.66) * h(t)))
    R(c, 'h_head', lambda t: (-16 * h(t), 0, 10 * osc(t, 0.66, -0.1) * h(t)))
    c.particle(1.4, HONEY, 2, 0.15, 0.0, 0.5)
    out.append(c)

    # sniff the air (loop variant): nose up, sampling the breeze for flowers
    c = start(m, 'sniff_air', 6.0, 'loop')
    R(c, 'h_head', lambda t: (18 + 4 * osc(t, 6.0) * (env(t, 0.5, 1.5, 0.1) + env(t, 3.5, 4.5, 0.1)), 30 * osc(t, 1 / 6.0), 0))
    R(c, 'body', lambda t: (6, 0, 0))
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    c = start(m, 'pet', 2.2)
    e = lambda t: env(t, 0.1, 2.05, 0.3)
    R(c, 'body', lambda t: (0, 0, 9 * osc(t, 0.9) * e(t)))
    R(c, 'h_head', lambda t: (12 * e(t), 0, 12 * osc(t, 0.9, -0.1) * e(t)))
    R(c, 'h_left_ear', lambda t: (20 * e(t), 0, 10 * e(t)))
    R(c, 'h_right_ear', lambda t: (20 * e(t), 0, -10 * e(t)))
    for arm, s in ARMS:
        R(c, arm, lambda t, s=s: (-15 * e(t), -s * 20 * e(t), 0))
    out.append(c)

    c = overlay_from(m, 'pet_sit', 2.0, 'sit', 'end', ('h_head', 'h_left_ear', 'h_right_ear'))
    e2 = lambda t: env(t, 0.1, 1.85, 0.3)
    R(c, 'h_head', lambda t: (12 * e2(t), 0, 12 * osc(t, 0.9, -0.1) * e2(t)))
    R(c, 'h_left_ear', lambda t: (20 * e2(t), 0, 10 * e2(t)))
    R(c, 'h_right_ear', lambda t: (20 * e2(t), 0, -10 * e2(t)))
    out.append(c)

    # bond level-up: lifts the honey pot high over its head, bounces, honey splashes, a happy spin
    c = start(m, 'level_up', 3.0)
    lift = lambda t: env(t, 0.2, 2.6, 0.35)
    for arm, s in ARMS:
        R(c, arm, lambda t, s=s: (-150 * lift(t), 0, s * 10 * lift(t)))
    P(c, 'pot', lambda t: (0, 6 * lift(t), 3 * lift(t)))
    P(c, 'root', lambda t: (0, 3 * (bump(t, 0.6, 1.0) + bump(t, 1.1, 1.5)), 0))
    R(c, 'root', lambda t: (0, 360 * step(t, 1.6, 2.4), 0))
    R(c, 'h_head', lambda t: (25 * lift(t), 0, 0))
    S(c, 'glow_honey', lambda t: (0.5 * jolt(t, 0.6, 2.8),) * 3)
    c.particle(0.9, HONEY, 6, 0.3, 0.0, 1.2)
    c.particle(1.1, 'minecraft:wax_on', 6, 0.4, 0.05, 1.0)
    c.sound(0.6, POLL, 0.5, 1.2)
    c.sound(1.0, 'ambient_happy', 0.9, 1.1)
    c.sound(1.7, 'minecraft:block.honey_block.slide', 0.5, 1.4)
    out.append(c)

    # --- sitting (on its rump) variants --------------------------------------------------------------------------
    c = start(m, 'sit_honey_dip', 4.0, 'loop', src='sit', at='end')
    dp = lambda t: bump(t, 0.3, 1.0)
    lk = lambda t: env(t, 1.0, 3.4, 0.3)
    R(c, 'right_arm', lambda t: (-10 * dp(t) - 70 * lk(t), 0, 20 * lk(t)))
    R(c, 'h_head', lambda t: (-15 * dp(t) - 12 * lk(t) + 6 * osc(t, 3.0) * lk(t), 0, -8 * lk(t)))
    c.sound(1.4, SLURP, 0.25, 1.6)
    out.append(c)

    c = start(m, 'sit_sway', 4.0, 'loop', src='sit', at='end')
    R(c, 'body', lambda t: (0, 0, 8 * osc(t, 0.5)))
    R(c, 'h_head', lambda t: (0, 0, 8 * osc(t, 0.5, -0.1)))
    for leg in LEGS:
        R(c, leg, lambda t: (8 * osc(t, 1.0), 0, 0))
    out.append(c)

    c = start(m, 'sit_bee_watch', 4.0, 'loop', src='sit', at='end')
    R(c, 'h_head', lambda t: (14 + 6 * osc(t, 0.5, 0.25), 40 * osc(t, 0.5), 0))
    R(c, 'right_arm', lambda t: (-50 * bump(t, 2.8, 3.4), 0, 0))
    c.sound(0.2, BEE, 0.18, 1.5)
    out.append(c)

    # --- sleeping variants (eyes shut) -----------------------------------------------------------------------------
    c = start(m, 'sleep_snuffle', 8.0, 'loop', src='sleep')
    R(c, 'h_head', lambda t: (3 * bump(t, 2.0, 2.4) + 3 * bump(t, 2.6, 3.0), 0, 0))
    R(c, 'h_right_ear', lambda t: (0, 0, -12 * bump(t, 5.0, 5.3)))
    out.append(c)

    c = start(m, 'sleep_slump', 8.0, 'loop', src='sleep')
    sl = lambda t: env(t, 1.0, 7.0, 1.2)
    R(c, 'body', lambda t: (8 * sl(t), 0, -8 * sl(t)))
    R(c, 'h_head', lambda t: (0, 0, 10 * sl(t)))
    R(c, 'left_arm', lambda t: (10 * sl(t), 0, -10 * sl(t)))
    out.append(c)
    return out


TAGS = {
    'back_roll': {'p': 'belly'},
    'pet_sit': {'p': 'sitting'},
    'sit_honey_dip': {'p': 'sitting'}, 'sit_sway': {'p': 'sitting'}, 'sit_bee_watch': {'p': 'sitting'},
    'sleep_snuffle': {'p': 'lying'}, 'sleep_slump': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'paw_lick', 'w': 3, 'cd': 25},
        {'clip': 'back_roll', 'w': 2, 'cd': 45, 'boost': {'happy': 3, 'owner_near': 1.5}},
        {'clip': 'rump_plop', 'w': 2, 'cd': 35},
        {'clip': 'bee_swat', 'w': 3, 'cd': 30, 'boost': {'day': 1.5}},
        {'clip': 'pot_hug', 'w': 2, 'cd': 25, 'boost': {'night': 2, 'cold': 2}},
        {'clip': 'sniff_air', 'w': 3, 'min': 6, 'max': 18, 'cd': 15},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},
        {'clip': 'sit_honey_dip', 'w': 3, 'min': 4, 'max': 12, 'cd': 18},
        {'clip': 'sit_sway', 'w': 2, 'min': 4, 'max': 12, 'cd': 20, 'boost': {'happy': 2}},
        {'clip': 'sit_bee_watch', 'w': 2, 'min': 4, 'max': 8, 'cd': 25, 'when': 'day'},
    ],
    'lying': [
        {'clip': 'sleep_snuffle', 'w': 2, 'min': 24, 'max': 64},
        {'clip': 'sleep_slump', 'w': 1, 'min': 8, 'max': 24, 'cd': 30},
    ],
}

PROCEDURAL = {'ear_twitch': 1.2, 'tail_sway': 4, 'breath': 0.016}
