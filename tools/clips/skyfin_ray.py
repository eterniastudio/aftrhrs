"""Skyfin Ray (phase 2): a sky manta. Undulating glides (a travelling wave down each wing, tips lagging), lazy banking
circles, barrel rolls ONLY while hovering in the air (`when: in_air`; never on the ground). Its old `happy` and
`roll_over` were barrel rolls that also fired on the ground; they are re-authored as a joyful wing ripple / bounce and a
rippling stretch, and the roll became its own air-only clip. Sitting: a ray settled flat, edges rippling; sleeping:
tail curled, slow wing breaths."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step  # noqa: E402

WIND = 'minecraft:entity.breeze.idle_air'
WHOOSH = 'minecraft:entity.phantom.flap'
CLOUD = 'minecraft:cloud'


def wings(c, up, tip=None):
    """Both wings raised by up(t) deg (tips follow by tip(t)): right z-, left z+ lifts the tips."""
    R(c, 'right_wing', lambda t: (0, 0, -up(t)))
    R(c, 'left_wing', lambda t: (0, 0, up(t)))
    if tip:
        R(c, 'right_wing_tip', lambda t: (0, 0, -tip(t)))
        R(c, 'left_wing_tip', lambda t: (0, 0, tip(t)))


def wave(c, amp, freq, e, lag=0.14):
    """A travelling ripple down both wings (tip lags the root): the manta stroke."""
    wings(c, lambda t: amp * osc(t, freq) * e(t), lambda t: amp * 1.2 * osc(t, freq, -lag) * e(t))


def tail(c, amp, freq, e, yaw=True):
    for i, b in enumerate(('tail', 'tail_mid', 'tail_tip')):
        R(c, b, lambda t, i=i: ((0 if yaw else 1) * amp * (1 + 0.4 * i) * osc(t, freq, -0.12 * i) * e(t),
                                (1 if yaw else 0) * amp * (1 + 0.4 * i) * osc(t, freq, -0.12 * i) * e(t), 0))


def clips(m):
    out = Out(m)
    one = lambda t: 1.0

    # --- re-authored: no rolling on the ground -------------------------------------------------------------------
    c = start(m, 'happy', 1.6, src='idle')
    up = lambda t: bump(t, 0.1, 0.6) + 0.7 * bump(t, 0.7, 1.2)
    P(c, 'body', lambda t: (0, 2.5 * up(t), 0))
    R(c, 'body', lambda t: (-8 * bump(t, 0.1, 0.6) + 6 * bump(t, 0.6, 1.0), 0, 0))
    wave(c, 22, 2.5, lambda t: env(t, 0.05, 1.5, 0.2))
    tail(c, 18, 2.5, lambda t: env(t, 0.05, 1.5, 0.2))
    c.sound(0.15, WHOOSH, 0.35, 1.3)
    c.sound(0.75, WHOOSH, 0.3, 1.4)
    out.append(c)

    c = start(m, 'roll_over', 2.2, src='idle')      # happy pool: a long rippling stretch, wings reaching up
    e = lambda t: env(t, 0.2, 2.0, 0.45)
    wings(c, lambda t: 24 * e(t), lambda t: 30 * e(t) + 8 * osc(t, 3, -0.1) * env(t, 0.6, 1.8))
    R(c, 'body', lambda t: (10 * e(t), 0, 0))
    R(c, 'h_head', lambda t: (10 * e(t), 0, 0))
    tail(c, 14, 1.5, e, yaw=False)
    c.sound(0.4, WIND, 0.25, 1.4)
    out.append(c)

    # barrel roll: air only. Dips, rolls a full turn with the wings tucked a little, levels out with a flourish
    c = start(m, 'barrel_roll', 2.2, src='hover')
    roll = lambda t: step(t, 0.45, 1.45)
    R(c, 'body', lambda t: (8 * bump(t, 0.2, 0.6), 0, 360 * roll(t)))
    P(c, 'body', lambda t: (0, -1.5 * bump(t, 0.1, 0.55) + 3 * bump(t, 0.45, 1.6), 0))
    wings(c, lambda t: 14 * env(t, 0.45, 1.45, 0.2) - 18 * bump(t, 1.45, 1.95), lambda t: 22 * env(t, 0.45, 1.45, 0.2))
    tail(c, 22, 1.2, lambda t: env(t, 0.4, 2.0, 0.3))
    c.sound(0.5, WHOOSH, 0.4, 1.1)
    c.sound(0.9, WIND, 0.3, 1.5)
    c.particle(1.0, CLOUD, 3, 0.6, 0.01, 0.5)
    out.append(c)

    # --- standing / hovering ------------------------------------------------------------------------------------
    # undulating glide (loop variant): the deep slow manta stroke, a travelling wave down each wing
    c = start(m, 'undulate', 6.0, 'loop', src='idle')
    wave(c, 18, 1 / 2.0, one, 0.16)
    P(c, 'body', lambda t: (0, 0.8 * osc(t, 1 / 2.0, -0.25), 0))
    R(c, 'body', lambda t: (3 * osc(t, 1 / 2.0, -0.3), 0, 0))
    tail(c, 10, 1 / 2.0, one)
    R(c, 'left_horn_fin', lambda t: (6 * osc(t, 1 / 2.0, -0.2), 0, 0))
    R(c, 'right_horn_fin', lambda t: (6 * osc(t, 1 / 2.0, -0.2), 0, 0))
    c.sound(0.5, WHOOSH, 0.2, 0.9)
    c.sound(2.5, WHOOSH, 0.2, 0.95)
    c.sound(4.5, WHOOSH, 0.2, 0.9)
    out.append(c)

    # lazy circling (loop variant, air): banks into a long turn one way, then the other, tail trailing the bank
    c = start(m, 'lazy_circle', 9.0, 'loop', src='hover')
    bank = lambda t: osc(t, 1 / 9.0)
    R(c, 'body', lambda t: (0, 25 * bank(t), 28 * bank(t)))
    R(c, 'h_head', lambda t: (0, 10 * osc(t, 1 / 9.0, -0.05), 0))
    for i, b in enumerate(('tail', 'tail_mid', 'tail_tip')):
        R(c, b, lambda t, i=i: (0, -12 * (1 + 0.5 * i) * osc(t, 1 / 9.0, -0.06 * (i + 1)), 0))
    wings(c, lambda t: 8 * bank(t), lambda t: 10 * bank(t))
    out.append(c)

    # horn fins curl in and unfurl (overlay)
    c = start(m, 'fin_curl', 1.8, src='idle', at=0, bones=['left_horn_fin', 'right_horn_fin', 'left_fin_curl_r', 'right_fin_curl_r'])
    cu = lambda t: env(t, 0.15, 1.5, 0.3)
    R(c, 'left_horn_fin', lambda t: (-30 * cu(t), -10 * cu(t), 0))
    R(c, 'right_horn_fin', lambda t: (-30 * cu(t), 10 * cu(t), 0))
    R(c, 'left_fin_curl_r', lambda t: (-35 * cu(t) + 10 * osc(t, 3) * cu(t), 0, 0))
    R(c, 'right_fin_curl_r', lambda t: (-35 * cu(t) + 10 * osc(t, 3, 0.2) * cu(t), 0, 0))
    out.append(c)

    # tail whip: the whip tail cracks side to side (overlay)
    c = start(m, 'tail_whip', 1.6, src='idle', at=0, bones=['tail', 'tail_mid', 'tail_tip'])
    for i, b in enumerate(('tail', 'tail_mid', 'tail_tip')):
        R(c, b, lambda t, i=i: (10 * bump(t, 0.1, 0.5), 30 * (1 + 0.6 * i) * jolt(t, 0.2 + 0.07 * i, 0.8 + 0.07 * i)
                                - 22 * (1 + 0.6 * i) * jolt(t, 0.7 + 0.07 * i, 1.4 + 0.05 * i), 0))
    c.sound(0.75, WHOOSH, 0.2, 1.8)
    out.append(c)

    # settled on the ground: lies flat, wing edges rippling like a ray on the seabed (loop variant, on ground)
    c = start(m, 'ground_ripple', 6.0, 'loop', src='idle')
    P(c, 'body', lambda t: (0, -0.6, 0))
    R(c, 'right_wing_tip', lambda t: (0, 0, 9 * osc(t, 0.5)))
    R(c, 'left_wing_tip', lambda t: (0, 0, -9 * osc(t, 0.5, 0.5)))
    tail(c, 6, 1 / 3.0, one)
    out.append(c)

    # charged (spec anim; it was an empty 1 s clip): wind-charged for its Tailwind / sun Speed, it rides higher,
    # the wings shiver with energy between strong strokes, sparks and gusts trail off its wingtips (loop variant)
    c = start(m, 'charged', 3.0, 'loop', src='hover')
    P(c, 'body', lambda t: (0, 1.2 + 0.5 * osc(t, 1 / 1.5, -0.25), 0))
    wave(c, 14, 1 / 1.5, one, 0.14)
    for w, s in (('right_wing_tip', -1), ('left_wing_tip', 1)):
        R(c, w, lambda t, s=s: (0, 0, s * 3 * osc(t, 12.0)))
    R(c, 'left_horn_fin', lambda t: (10 + 4 * osc(t, 1 / 1.5), 0, 0))
    R(c, 'right_horn_fin', lambda t: (10 + 4 * osc(t, 1 / 1.5), 0, 0))
    tail(c, 12, 1 / 1.5, one)
    c.particle(0.4, 'minecraft:electric_spark', 3, 0.9, 0.05, 0.5)
    c.particle(1.9, 'minecraft:electric_spark', 3, 0.9, 0.05, 0.5)
    c.particle(1.1, CLOUD, 2, 0.8, 0.01, 0.4)
    c.sound(0.2, WIND, 0.2, 1.6)
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    c = start(m, 'pet', 1.8, src='idle')
    e = lambda t: env(t, 0.1, 1.65, 0.3)
    wings(c, lambda t: 12 * e(t), lambda t: 22 * e(t) + 6 * osc(t, 4) * e(t))
    R(c, 'body', lambda t: (6 * e(t), 0, 0))
    P(c, 'body', lambda t: (0, 0.8 * e(t), 0))
    R(c, 'left_horn_fin', lambda t: (16 * e(t), 0, 0))
    R(c, 'right_horn_fin', lambda t: (16 * e(t), 0, 0))
    tail(c, 14, 2.0, e)
    out.append(c)

    c = overlay_from(m, 'pet_sit', 1.6, 'sit', 'end', ('h_head', 'left_horn_fin', 'right_horn_fin', 'tail', 'tail_mid', 'tail_tip'))
    e2 = lambda t: env(t, 0.1, 1.45, 0.3)
    R(c, 'h_head', lambda t: (6 * e2(t), 0, 0))
    R(c, 'left_horn_fin', lambda t: (16 * e2(t), 0, 0))
    R(c, 'right_horn_fin', lambda t: (16 * e2(t), 0, 0))
    tail(c, 12, 2.0, e2)
    out.append(c)

    # bond level-up: rises on huge strokes, turns a full flat circle with its wings spread wide, a gust of cloud
    c = start(m, 'level_up', 3.0, src='hover')
    rise = lambda t: bump(t, 0.2, 2.8)
    P(c, 'root', lambda t: (0, 10 * rise(t), 0))
    R(c, 'root', lambda t: (0, 360 * step(t, 0.6, 2.2), 0))
    wave(c, 30, 1.5, lambda t: env(t, 0.1, 2.8, 0.25))
    R(c, 'body', lambda t: (-12 * env(t, 0.2, 2.6, 0.4), 0, 0))
    tail(c, 20, 1.5, lambda t: env(t, 0.1, 2.8, 0.3))
    c.sound(0.2, WHOOSH, 0.5, 0.9)
    c.sound(0.9, WHOOSH, 0.5, 1.0)
    c.sound(1.3, 'ambient_happy', 0.9, 1.0)
    c.sound(1.6, WHOOSH, 0.5, 1.1)
    c.particle(1.2, CLOUD, 8, 0.8, 0.04, 0.4)
    out.append(c)

    # --- sitting (settled) variants -------------------------------------------------------------------------------
    c = start(m, 'sit_ripple', 6.0, 'loop', src='sit', at='end')
    R(c, 'right_wing_tip', lambda t: (0, 0, 8 * osc(t, 0.5)))
    R(c, 'left_wing_tip', lambda t: (0, 0, -8 * osc(t, 0.5, 0.5)))
    R(c, 'h_head', lambda t: (0, 8 * osc(t, 1 / 6.0), 0))
    out.append(c)

    c = start(m, 'sit_tail_curl', 6.0, 'loop', src='sit', at='end')
    cu = lambda t: env(t, 0.6, 5.0, 0.8)
    for i, b in enumerate(('tail', 'tail_mid', 'tail_tip')):
        R(c, b, lambda t, i=i: (0, (20 + 10 * i) * cu(t) + 4 * osc(t, 0.5, -0.1 * i), 0))
    out.append(c)

    # --- sleeping variants --------------------------------------------------------------------------------------
    c = start(m, 'sleep_breathe', 8.0, 'loop', src='sleep')
    wings(c, lambda t: 3 * osc(t, 0.25), lambda t: 5 * osc(t, 0.25, -0.1))
    out.append(c)

    c = start(m, 'sleep_dream', 8.0, 'loop', src='sleep')
    q = lambda t: env(t, 2.5, 4.5, 0.4)
    wave(c, 6, 1.0, q)
    R(c, 'tail_tip', lambda t: (0, 10 * bump(t, 6.0, 6.6), 0))
    out.append(c)
    return out


TAGS = {
    'happy': {'p': 'standing'}, 'roll_over': {'p': 'standing'}, 'barrel_roll': {'p': 'standing'},
    'pet_sit': {'p': 'sitting'},
    'sit_ripple': {'p': 'sitting'}, 'sit_tail_curl': {'p': 'sitting'},
    'sleep_breathe': {'p': 'lying'}, 'sleep_dream': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'undulate', 'w': 4, 'min': 6, 'max': 18, 'cd': 12},
        {'clip': 'lazy_circle', 'w': 3, 'min': 9, 'max': 18, 'cd': 20, 'when': 'in_air'},
        {'clip': 'barrel_roll', 'w': 2, 'cd': 30, 'when': 'in_air', 'boost': {'happy': 3, 'owner_near': 1.5}},
        {'clip': 'ground_ripple', 'w': 3, 'min': 6, 'max': 18, 'cd': 15, 'when': 'on_ground'},
        {'clip': 'fin_curl', 'w': 2, 'cd': 15},
        {'clip': 'tail_whip', 'w': 2, 'cd': 18},
        {'clip': 'charged', 'w': 3, 'min': 4, 'max': 9, 'cd': 25, 'when': 'in_air,day',
         'boost': {'thunder': 4, 'owner_sprinting': 3, 'owner_near': 1.5}},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},
        {'clip': 'sit_ripple', 'w': 3, 'min': 6, 'max': 12, 'cd': 15},
        {'clip': 'sit_tail_curl', 'w': 2, 'min': 6, 'max': 12, 'cd': 25},
    ],
    'lying': [
        {'clip': 'sleep_breathe', 'w': 2, 'min': 24, 'max': 64},
        {'clip': 'sleep_dream', 'w': 1, 'min': 8, 'max': 16, 'cd': 40},
    ],
}

PROCEDURAL = {'tail_sway': 8, 'breath': 0.008, 'look_yaw': 35, 'look_pitch': 20}
