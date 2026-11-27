"""Runeback Tortoise (phase 2): slow and ancient. It stays by withdrawing into its rune shell (`hide`, its sit role), so
the posture graph is rebuilt around that: standing > sitting = tuck_in (looks round, then pulls in with a shell thunk),
sitting > standing = unhide (peeks, legs out, then the head), sitting > lying = sit_lie_down, lying > sitting =
wake_up, and standing <> lying blends, all authored from the poses (no pops). `hide` itself is now the still hidden
pose (it used to replay the tuck from standing). Life: head in and out of the shell, rune pulses (enchanting glyphs
rise), chewing, neck stretches, sun basking."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import transition  # noqa: E402
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, start, step, Clip  # noqa: E402

SHELL = 'minecraft:item.armor.equip_turtle'
RUNE = 'minecraft:block.enchantment_table.use'
HUM = 'minecraft:block.beacon.ambient'
SHAMBLE = 'minecraft:entity.turtle.shamble'
CHEW = 'minecraft:entity.armadillo.eat'
GLYPH = 'minecraft:enchant'
LEGS = ('left_front_leg', 'right_front_leg', 'left_back_leg', 'right_back_leg')


def hold_pose(m, name, pose, L=2.0):
    c = Clip(m, name, L, 'hold')
    for k, v in pose.items():
        c.tracks[k] = (lambda v: lambda t: v)(v)
    return c


def clips(m):
    out = Out(m)
    hidden = m.pose('hide', 'end')
    hide = hold_pose(m, 'hide', hidden)
    m.put(hide)
    out.append(hide)

    # standing > hidden: a wary look round, then it pulls everything in and the shell drops with a thunk
    c = Clip(m, 'tuck_in', 1.4)
    idle0 = m.pose('idle', 0)
    c.blend_all(idle0, hidden, 0.55, 1.05)
    R(c, 'neck', lambda t: (0, 22 * bump(t, 0.0, 0.55), 0))
    S(c, 'root', lambda t: (0.03 * bump(t, 1.0, 1.25), -0.04 * bump(t, 1.0, 1.25), 0.03 * bump(t, 1.0, 1.25)))
    c.sound(0.6, SHELL, 0.6, 0.8)
    c.sound(1.05, 'minecraft:block.stone.place', 0.35, 0.7)
    m.put(c)
    out.append(c)

    # hidden > standing: the head peeks out first, looks, then the legs, then it rises
    c = Clip(m, 'unhide', 1.6)
    c.blend_all(hidden, idle0, 0.7, 1.45)
    peek = lambda t: bump(t, 0.05, 0.75)
    S(c, 'neck', lambda t: (0.3 * peek(t),) * 3)
    P(c, 'neck', lambda t: (0, 0.5 * peek(t), -2.0 * peek(t)))
    R(c, 'neck', lambda t: (0, 18 * osc(t, 2.0) * peek(t), 0))
    c.sound(0.75, SHELL, 0.5, 1.0)
    c.sound(1.3, SHAMBLE, 0.4, 0.8)
    m.put(c)
    out.append(c)

    # posture blends that match the poses exactly (the old ones popped)
    for name, a, ta, b, tb, L in (('wake_up', 'sleep', 0, 'hide', 'end', 1.6), ('sit_down', 'idle', 0, 'sleep', 0, 1.6),
                                  ('stand_up', 'sleep', 0, 'idle', 0, 1.6)):
        c = transition(m, name, a, ta, b, tb, L, 0.1)
        m.put(c)
        out.append(c)

    # --- standing ----------------------------------------------------------------------------------------------
    # head in and out: something startles it, the head snaps into the shell, a long pause, it creeps back out
    c = start(m, 'head_retract', 4.0)
    inn = lambda t: step(t, 0.25, 0.45) * (1 - step(t, 2.2, 3.4))
    S(c, 'neck', lambda t: (-0.4 * inn(t),) * 3)
    P(c, 'neck', lambda t: (0, -1.0 * inn(t), 4.0 * inn(t)))
    R(c, 'neck', lambda t: (6 * inn(t), 20 * osc(t, 1.0) * env(t, 3.0, 3.95, 0.3), 0))
    for leg in LEGS:
        S(c, leg, lambda t: (-0.12 * inn(t),) * 3)
    P(c, 'body', lambda t: (0, -0.6 * inn(t), 0))
    c.sound(0.28, SHELL, 0.4, 1.1)
    out.append(c)

    # rune pulse: it goes still, the shell swells with light twice, glyphs rise off it, a deep hum
    c = start(m, 'rune_pulse', 3.6)
    pulse = lambda t: bump(t, 0.5, 1.3) + 0.8 * bump(t, 1.6, 2.4)
    S(c, 'shell', lambda t: (0.035 * pulse(t), 0.06 * pulse(t), 0.035 * pulse(t)))
    P(c, 'shell', lambda t: (0, 0.4 * pulse(t), 0))
    R(c, 'neck', lambda t: (14 * env(t, 0.3, 3.0, 0.5), 0, 0))
    R(c, 'h_head', lambda t: (10 * env(t, 0.3, 3.0, 0.5), 0, 0))
    c.particle(0.8, GLYPH, 10, 0.7, 0.5, 1.1)
    c.particle(1.9, GLYPH, 8, 0.7, 0.5, 1.1)
    c.sound(0.6, RUNE, 0.5, 1.0)
    c.sound(1.7, HUM, 0.25, 1.6)
    out.append(c)

    # chewing: head down to nibble something, then a long contented chew
    c = start(m, 'chew', 4.0)
    dn = lambda t: env(t, 0.2, 1.4, 0.35)
    R(c, 'neck', lambda t: (-30 * dn(t) + 6 * env(t, 1.4, 3.8, 0.4), 6 * osc(t, 0.5) * env(t, 1.4, 3.8), 0))
    R(c, 'h_head', lambda t: (-8 * dn(t), 0, 0))
    R(c, 'jaw', lambda t: (-20 * bump(t, 0.85, 1.15) - 9 * (osc(t, 2.5) * 0.5 + 0.5) * env(t, 1.4, 3.8, 0.2), 0, 0))
    R(c, 'left_front_leg', lambda t: (5 * dn(t), 0, 0))
    R(c, 'right_front_leg', lambda t: (5 * dn(t), 0, 0))
    for t0 in (1.0, 1.6, 2.4, 3.2):
        c.sound(t0, CHEW, 0.35, 0.7)
    out.append(c)

    # neck stretch + yawn (mouth only)
    c = start(m, 'neck_stretch', 3.4)
    st = lambda t: env(t, 0.3, 2.9, 0.6)
    P(c, 'neck', lambda t: (0, 0.6 * st(t), -2.2 * st(t)))
    R(c, 'neck', lambda t: (20 * st(t), 0, 0))
    R(c, 'h_head', lambda t: (16 * bump(t, 1.0, 2.4), 0, 6 * bump(t, 1.0, 2.4)))
    R(c, 'jaw', lambda t: (-28 * bump(t, 1.1, 2.3), 0, 0))
    for i, leg in enumerate(LEGS[:2]):
        R(c, leg, lambda t: (-10 * st(t), 0, 0))
    out.append(c)

    # slow look (loop variant): the ancient, slow pan of the head left ... and right
    c = start(m, 'slow_look', 8.0, 'loop')
    R(c, 'neck', lambda t: (4 * osc(t, 0.25, 0.25), 32 * osc(t, 0.125), 0))
    R(c, 'h_head', lambda t: (0, 10 * osc(t, 0.125, -0.05), 0))
    out.append(c)

    # sun basking (loop variant, day): legs splayed out, neck long, chin up
    c = start(m, 'sun_bask', 8.0, 'loop')
    P(c, 'body', lambda t: (0, -0.8, 0))
    for leg, sx, sz in (('left_front_leg', 1, -1), ('right_front_leg', -1, -1), ('left_back_leg', 1, 1), ('right_back_leg', -1, 1)):
        R(c, leg, lambda t, sx=sx, sz=sz: (sz * 25, 0, sx * 18))
    P(c, 'neck', lambda t: (0, 0, -1.5))
    R(c, 'neck', lambda t: (16 + 3 * osc(t, 0.25), 0, 0))
    R(c, 'h_head', lambda t: (14, 0, 6 * osc(t, 0.125)))
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    c = start(m, 'pet', 2.4)
    e = lambda t: env(t, 0.1, 2.25, 0.4)
    P(c, 'neck', lambda t: (0, 0.5 * e(t), -1.6 * e(t)))
    R(c, 'neck', lambda t: (18 * e(t), 0, 0))
    R(c, 'h_head', lambda t: (16 * e(t), 0, 10 * osc(t, 0.6) * e(t)))
    S(c, 'shell', lambda t: (0.03 * bump(t, 0.6, 1.6), 0.05 * bump(t, 0.6, 1.6), 0.03 * bump(t, 0.6, 1.6)))
    c.particle(0.9, GLYPH, 6, 0.6, 0.4, 1.0)
    out.append(c)

    # petting while hidden: the head peeks out to meet the hand, the runes hum (overlay over the hidden pose)
    c = Clip(m, 'pet_sit', 2.0)
    for b in ('neck', 'h_head'):
        for ch in ('rotation', 'position', 'scale'):
            v = hidden.get((b, ch), (1, 1, 1) if ch == 'scale' else (0, 0, 0))
            c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)
    pk = lambda t: env(t, 0.1, 1.85, 0.35)
    S(c, 'neck', lambda t: (0.35 * pk(t),) * 3)
    P(c, 'neck', lambda t: (0, 0.6 * pk(t), -3.0 * pk(t)))
    R(c, 'neck', lambda t: (10 * pk(t), 0, 0))
    R(c, 'h_head', lambda t: (12 * pk(t), 0, 8 * osc(t, 0.7) * pk(t)))
    c.particle(0.7, GLYPH, 5, 0.6, 0.4, 0.9)
    out.append(c)

    # bond level-up: the runes flare, it rears onto its back legs and stomps down, glyphs burst
    c = start(m, 'level_up', 3.4)
    rear = lambda t: step(t, 0.3, 0.9) * (1 - step(t, 1.4, 1.6))
    R(c, 'body', lambda t: (16 * rear(t), 0, 0))
    P(c, 'body', lambda t: (0, 2.0 * rear(t) - 0.5 * bump(t, 1.58, 1.8), 0))
    R(c, 'left_front_leg', lambda t: (30 * rear(t), 0, 0))
    R(c, 'right_front_leg', lambda t: (30 * rear(t), 0, 0))
    R(c, 'neck', lambda t: (22 * env(t, 0.3, 3.0, 0.4), 0, 0))
    R(c, 'jaw', lambda t: (-18 * bump(t, 1.7, 2.4), 0, 0))
    S(c, 'shell', lambda t: (0.06 * jolt(t, 1.55, 3.2), 0.1 * jolt(t, 1.55, 3.2), 0.06 * jolt(t, 1.55, 3.2)))
    c.particle(1.6, GLYPH, 24, 0.9, 0.8, 1.0)
    c.particle(1.6, 'minecraft:end_rod', 6, 0.7, 0.05, 1.1)
    c.sound(0.4, RUNE, 0.6, 0.9)
    c.sound(1.58, SHELL, 0.8, 0.7)
    c.sound(1.65, HUM, 0.4, 1.8)
    out.append(c)

    # --- hidden (sitting) variants -------------------------------------------------------------------------------
    c = start(m, 'shell_peek', 6.0, 'loop', src='hide', at='end')
    pk = lambda t: env(t, 0.8, 4.6, 0.6)
    S(c, 'neck', lambda t: (0.32 * pk(t),) * 3)
    P(c, 'neck', lambda t: (0, 0.5 * pk(t), -2.6 * pk(t)))
    R(c, 'neck', lambda t: (0, 35 * (env(t, 1.6, 2.6, 0.35) - env(t, 2.8, 3.9, 0.35)), 0))
    out.append(c)

    c = start(m, 'shell_runes', 6.0, 'loop', src='hide', at='end')
    gl = lambda t: bump(t, 1.0, 3.0)
    S(c, 'shell', lambda t: (0.03 * gl(t), 0.05 * gl(t), 0.03 * gl(t)))
    c.particle(1.8, GLYPH, 6, 0.6, 0.4, 1.0)
    c.sound(1.4, HUM, 0.12, 1.5)
    out.append(c)

    c = start(m, 'shell_paddle', 4.0, 'loop', src='hide', at='end')
    ft = lambda t: env(t, 0.5, 3.5, 0.5)
    for i, leg in enumerate(LEGS):
        S(c, leg, lambda t: (0.25 * ft(t),) * 3)
        R(c, leg, lambda t, i=i: (15 * osc(t, 1.0, i * 0.25) * ft(t), 0, 0))
    out.append(c)

    # --- sleeping variants (eyes shut) -----------------------------------------------------------------------------
    c = start(m, 'sleep_deep', 8.0, 'loop', src='sleep')
    S(c, 'shell', lambda t: (0.012 * osc(t, 0.25), 0.025 * osc(t, 0.25), 0.012 * osc(t, 0.25)))
    R(c, 'h_head', lambda t: (-3, 0, 0))
    out.append(c)

    c = start(m, 'sleep_runes', 8.0, 'loop', src='sleep')
    S(c, 'shell', lambda t: (0.02 * bump(t, 2.0, 5.0), 0.035 * bump(t, 2.0, 5.0), 0.02 * bump(t, 2.0, 5.0)))
    c.particle(3.4, GLYPH, 4, 0.6, 0.3, 1.0)
    R(c, 'jaw', lambda t: (-5 * bump(t, 6.0, 6.6), 0, 0))
    out.append(c)
    return out


TAGS = {
    'hide': {'p': 'sitting'},
    'tuck_in': {'from': 'standing', 'p': 'sitting'},
    'unhide': {'from': 'sitting', 'p': 'standing'},
    'wake_up': {'from': 'lying', 'p': 'sitting'},
    'sit_down': {'from': 'standing', 'p': 'lying'},
    'stand_up': {'from': 'lying', 'p': 'standing'},
    'pet_sit': {'p': 'sitting', 'layer': 'overlay', 'move': True},
    'shell_peek': {'p': 'sitting'}, 'shell_runes': {'p': 'sitting'}, 'shell_paddle': {'p': 'sitting'},
    'sleep_deep': {'p': 'lying'}, 'sleep_runes': {'p': 'lying'},
}

TRANSITIONS = {'standing>sitting': 'tuck_in', 'sitting>standing': 'unhide', 'lying>sitting': 'wake_up',
               'standing>lying': 'sit_down', 'lying>standing': 'stand_up'}

# the head-only flourishes the default sitting pool offers would pop the head out of the shell: hidden, it peeks,
# its runes glow or its feet paddle instead
_NO_SIT = ('eat', 'tame', 'look_around', 'sniff', 'yawn', 'nod_off', 'notice', 'curious', 'pet_sit')

IDLE = {
    'standing': [
        {'clip': 'head_retract', 'w': 3, 'cd': 30, 'boost': {'monster_near': 3, 'player_near:4': 1.5}},
        {'clip': 'rune_pulse', 'w': 3, 'cd': 35, 'boost': {'night': 2}},
        {'clip': 'chew', 'w': 3, 'cd': 25},
        {'clip': 'graze', 'w': 2, 'cd': 25},
        {'clip': 'neck_stretch', 'w': 2, 'cd': 40, 'boost': {'dawn': 3}},
        {'clip': 'slow_look', 'w': 3, 'min': 8, 'max': 16, 'cd': 15},
        {'clip': 'sun_bask', 'w': 2, 'min': 16, 'max': 40, 'cd': 60, 'when': 'day,!rain'},
    ],
    'sitting': [{'clip': c, 'w': 0, 'replace': True} for c in _NO_SIT] + [
        {'clip': 'shell_peek', 'w': 3, 'min': 6, 'max': 6, 'cd': 18, 'boost': {'owner_near': 2}},
        {'clip': 'shell_runes', 'w': 2, 'min': 6, 'max': 12, 'cd': 25, 'boost': {'night': 2}},
        {'clip': 'shell_paddle', 'w': 1, 'min': 4, 'max': 8, 'cd': 30},
    ],
    'lying': [
        {'clip': 'sleep_deep', 'w': 2, 'min': 24, 'max': 64},
        {'clip': 'sleep_runes', 'w': 1, 'min': 8, 'max': 16, 'cd': 40, 'boost': {'night': 1.5}},
    ],
}

# slow head on a long neck; the shell breathes only a little
PROCEDURAL = {'look_yaw': 45, 'look_pitch': 25, 'tail_sway': 4, 'breath': 0.006}
GAIT = {'run_on': 0.2, 'run_off': 0.16}
