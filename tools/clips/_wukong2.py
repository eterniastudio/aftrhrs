"""Sun Wukong, 2026-11-20 fight redesign: the clips of mod/WUKONG.md "Clip table" (imported by clips/wukong.py; not a
model module, so gen_creatures never loads it on its own). Key times match WukongSkills / WukongArena exactly.

Blockbench convention. Rig facts used here: legs hang from the hips (thigh 8 px, shin 7 px, sole 4 px below the ankle),
so a crouch of d px keeps the feet planted with thigh a, shin -2a, foot a where cos a = 1 - d / 15 (crouch_rot); the
staff hangs from hand_r (31 px of shaft above the fist at rest, 13 below; x 180 flips the long end over); the cloud is a
root-level bone the Java shows / hides; `peach` sits in the left fist (shown only during the feast). Upward chains
(plumes) lean back with +x, hanging ones (ribbons) with -x; the tail curls up with -x.
"""
import math

from animkit import Clip, wave, pulse, step, decay
import _bossanim as B

PLUME_L = ('plume_l', 'plume_l2', 'plume_l3')
PLUME_R = ('plume_r', 'plume_r2', 'plume_r3')
RIB_L = ('ribbon_l', 'ribbon_l2')
RIB_R = ('ribbon_r', 'ribbon_r2')
TAIL = ('tail1', 'tail2', 'tail3', 'tail4')


def crouch_rot(d, spread=3.0):
    """Leg angles that drop the hips by d px with the soles still flat on the ground."""
    a = math.degrees(math.acos(max(-1.0, min(1.0, 1 - d / 15.0))))
    return {'leg_l': (a, 0, -spread), 'leg_r': (a, 0, spread), 'shin_l': (-2 * a, 0, 0), 'shin_r': (-2 * a, 0, 0),
            'foot_l': (a, 0, 0), 'foot_r': (a, 0, 0), 'skirt_f': (-0.35 * a, 0, 0)}


def pose(base, rot=None, pos=None, scl=None, d=None, spread=3.0):
    """`base` with the given channels set to absolute values; d = a crouch of d px (feet planted)."""
    out = dict(base)
    if d:
        rot = dict(crouch_rot(d, spread), **(rot or {}))
        pos = dict({'body': (0, -d, 0)}, **(pos or {}))
    for ch, vals in (('rotation', rot), ('position', pos), ('scale', scl)):
        for b, v in (vals or {}).items():
            out[(b, ch)] = tuple(float(x) for x in v)
    return out


def whip(c, bones, t0, amp, axis=0, freq=2.2, damp=4.0, lag=0.06, grow=0.35):
    """Follow-through: a damped swing down a chain from t0 (later bones lag and swing wider)."""
    for i, b in enumerate(bones):
        def fn(t, i=i):
            v = amp * (1 + grow * i) * decay(t, t0 + lag * i, freq, damp)
            return tuple(v if k == axis else 0.0 for k in range(3))
        c.rot(b, fn, add=True)
    return c


def sway(c, bones, amp, cycles, axis=1, lag=0.08, grow=0.3, env=None):
    """Seamless sway down a chain (whole cycles over the clip); env(t) scales it."""
    for i, b in enumerate(bones):
        def fn(t, i=i):
            e = env(t) if env else 1.0
            v = amp * (1 + grow * i) * e * wave(t, c.L, cycles, -lag * i)
            return tuple(v if k == axis else 0.0 for k in range(3))
        c.rot(b, fn, add=True)
    return c


def stream(c, bones, amp, axis=0, env=None):
    """A constant lean down a chain (wind / speed), scaled by env(t)."""
    for i, b in enumerate(bones):
        def fn(t, i=i):
            v = amp * (1 + 0.25 * i) * (env(t) if env else 1.0)
            return tuple(v if k == axis else 0.0 for k in range(3))
        c.rot(b, fn, add=True)
    return c


def hair(c, t0, amp=1.0, back=True):
    """Plumes, ribbons, tail and skirt whip after an impact / launch at t0 (back: thrown backwards, else forwards)."""
    s = 1 if back else -1
    whip(c, PLUME_L + PLUME_R, t0, 16 * amp * s, axis=0)
    whip(c, RIB_L + RIB_R, t0, -20 * amp * s, axis=0)
    whip(c, TAIL, t0, 14 * amp, axis=0, freq=1.8)
    whip(c, ('skirt_f', 'skirt_b'), t0, 10 * amp, axis=0, freq=3, damp=6)
    return c


def k3(k):
    return (k, k, k)


def redesign(m, I0):
    out = []
    has = m.has_bone

    # the left fist at the mouth (eating) and lowered with the peach at the chest
    # (solved with forward kinematics: the peach in front of the mouth / at the chest while he sits)
    EAT_UP = {'arm_l': (71.6, 11.6, -2.5), 'forearm_l': (69.4, 31.6, -25.6), 'hand_l': (0, 0, 0)}
    EAT_LO = {'arm_l': (5.7, 2, 3), 'forearm_l': (90, 40, -27.8), 'hand_l': (0, 0, 0)}
    # sitting cross-legged on the ground: hips sunk to the floor, thighs out, shins folded under
    SIT = {'leg_l': (107.9, -14.7, -63.5), 'shin_l': (-110.7, -22.2, 13.1), 'leg_r': (108.5, 15.1, 61.8),
           'shin_r': (-123, 25.3, -11.6), 'foot_l': (0, 0, 0), 'foot_r': (0, 0, 0), 'skirt_f': (-70, 0, 0),
           'skirt_b': (20, 0, 0), 'chest': (4, 0, 0),
           # right fist on the knee, the staff standing upright beside him
           'arm_r': (6.9, -3.3, -78.9), 'forearm_r': (86.7, -44.6, 57.5), 'staff': (-68.2, 14.6, 17.9)}
    SIT_POS = {'body': (0, -15, 0)}

    # ------------------------------------------------------------------------------ dizzy (loop; others end on it)
    L = 1.2
    c = Clip(m, 'dizzy', L, 'loop')
    base = pose(I0, d=2.5, rot={'arm_r': (-4, 0, -14), 'forearm_r': (-8, 0, 0), 'staff': (-34, 0, 18),
                                'arm_l': (6, 0, 12), 'forearm_l': (-10, 0, 0), 'jaw': (-10, 0, 0), 'chest': (-8, 0, 0)})
    B.seq(c, [(0, base), (L, base)])
    c.rot('body', lambda t: (0, 6 * wave(t, L, 1, 0.25), 7 * wave(t, L, 1)), add=True)
    c.rot('chest', lambda t: (4 * wave(t, L, 1, 0.25), 0, -5 * wave(t, L, 1)), add=True)
    c.rot('h_head', lambda t: (8 * wave(t, L, 2, 0.25), 10 * wave(t, L, 1, 0.5), 14 * wave(t, L, 2)), add=True)
    c.rot('jaw', lambda t: (-4 * abs(wave(t, L, 2)), 0, 0), add=True)
    c.rot('leg_l', lambda t: (6 * max(0.0, wave(t, L, 1)), 0, 0), add=True)
    c.rot('leg_r', lambda t: (6 * max(0.0, -wave(t, L, 1)), 0, 0), add=True)
    c.rot('arm_l', lambda t: (0, 0, 6 * wave(t, L, 1, 0.1)), add=True)
    sway(c, TAIL, 10, 1, axis=1)
    sway(c, PLUME_L + PLUME_R, 6, 1, axis=2)
    B.sounds(c, [(0.3, 'ability_grunt', 0.35, 1.5)])
    out.append(B.keep(m, c))
    DZ = B.P(m, 'dizzy', 0)

    # ------------------------------------------------------------------------------ idle_final (phase-4 stance)
    L = 1.6
    c = Clip(m, 'idle_final', L, 'loop')
    base = pose(I0, d=5, rot={'chest': (-24, 0, 0), 'h_head': (16, 0, 0), 'arm_r': (-12, 0, -14), 'forearm_r': (-10, 0, 0),
                              'staff': (112, 0, 0), 'arm_l': (8, 0, 18), 'forearm_l': (-24, 0, 0), 'jaw': (-6, 0, 0)},
                spread=6)
    B.seq(c, [(0, base), (L, base)])
    c.rot('chest', lambda t: (3.5 * wave(t, L, 2), 0, 0), add=True)                    # heavy breathing
    c.pos('body', lambda t: (0, 0.5 * wave(t, L, 2, 0.15), 0), add=True)
    c.rot('arm_l', lambda t: (-3 * wave(t, L, 2), 0, 2 * wave(t, L, 2)), add=True)
    c.rot('jaw', lambda t: (-5 * max(0.0, wave(t, L, 2, 0.1)), 0, 0), add=True)
    c.rot('h_head', lambda t: (-2 * wave(t, L, 2), 3 * wave(t, L, 1), 0), add=True)
    stream(c, PLUME_L + PLUME_R, -6)                                                  # plumes droop
    sway(c, TAIL, 6, 1, axis=1)
    sway(c, RIB_L + RIB_R, 4, 2, axis=0)
    c.particle(0.2, 'minecraft:smoke', 3, 0.4, 0.01, 0.8)
    c.particle(1.0, 'minecraft:smoke', 3, 0.4, 0.01, 0.6)
    out.append(B.keep(m, c))
    FIN = B.P(m, 'idle_final', 0)

    # ------------------------------------------------------------------------------ ascend (phase 1 -> 2)
    c = Clip(m, 'ascend', 3.0)
    wind = pose(I0, d=7, rot={'chest': (-22, 0, 0), 'h_head': (18, 0, 0), 'arm_r': (-48, 0, -16), 'forearm_r': (-10, 0, 0),
                              'staff': (-40, 0, 0), 'arm_l': (-52, 0, 16), 'forearm_l': (-20, 0, 0)}, spread=5)
    launch = pose(I0, rot={'leg_l': (24, 0, -3), 'leg_r': (-12, 0, 3), 'shin_l': (-34, 0, 0), 'shin_r': (-8, 0, 0),
                           'foot_l': (10, 0, 0), 'foot_r': (24, 0, 0), 'arm_r': (170, 10, -12), 'forearm_r': (-6, 0, 0),
                           'staff': (180, 0, 0), 'arm_l': (160, 0, 24), 'chest': (10, 0, 0), 'h_head': (12, 0, 0)},
                  pos={'body': (0, 14, 0)})
    tuck = pose(I0, rot={'body': (-180, 0, 0), 'leg_l': (100, 0, 0), 'leg_r': (100, 0, 0), 'shin_l': (-118, 0, 0),
                         'shin_r': (-118, 0, 0), 'arm_r': (40, 0, -20), 'staff': (90, 0, 0), 'arm_l': (60, 0, 20),
                         'chest': (-12, 0, 0), 'h_head': (-12, 0, 0)}, pos={'body': (0, 26, 0)})
    pre = pose(I0, rot={'body': (-340, 0, 0), 'leg_l': (40, 0, -4), 'leg_r': (10, 0, 4), 'shin_l': (-40, 0, 0),
                        'shin_r': (-30, 0, 0), 'arm_r': (60, 0, -50), 'staff': (40, 0, 0), 'arm_l': (40, 0, 40),
                        'chest': (-10, 0, 0)}, pos={'body': (0, 7, 0)})
    # superhero landing: left foot planted forward, right knee down, left fist on the ground, staff swept out behind
    land = pose(I0, rot={'body': (-360, 0, 0), 'leg_l': (72, 0, -8), 'shin_l': (-126, 0, 0), 'foot_l': (54, 0, 0),
                         'leg_r': (-6, 0, 8), 'shin_r': (-84, 0, 0), 'foot_r': (62, 0, 0), 'chest': (-30, 0, 0),
                         'h_head': (22, 0, 0), 'arm_l': (28, 0, 26), 'forearm_l': (-6, 0, 0), 'arm_r': (12, 0, -78),
                         'forearm_r': (-16, 0, 0), 'staff': (64, 0, 0), 'skirt_f': (-40, 0, 0), 'skirt_b': (14, 0, 0)},
                pos={'body': (0, -9, -1)})
    settle = pose(land, pos={'body': (0, -10, -1)}, rot={'chest': (-34, 0, 0), 'h_head': (26, 0, 0)})
    roar = pose(I0, rot={'body': (-360, 0, 0), 'chest': (16, 0, 0), 'h_head': (24, 0, 0), 'jaw': (-30, 0, 0),
                         'arm_r': (172, 0, -14), 'forearm_r': (-6, 0, 0), 'staff': (90, 0, 90),
                         'arm_l': (172, 0, 14), 'forearm_l': (-6, 0, 0), 'leg_l': (4, 0, -8), 'leg_r': (4, 0, 8)},
                pos={'body': (0, 1, 0)}, scl={'eyes': (1.5, 1.5, 1.5)})
    roar2 = pose(roar, rot={'chest': (20, 0, 0), 'h_head': (28, 0, 0), 'jaw': (-32, 0, 0)})
    B.seq(c, [(0, I0), (0.5, wind), (0.75, launch, 'out'), (1.0, tuck), (1.4, pre), (1.6, land, 'in'), (1.75, settle, 'out'),
              (2.0, roar, 'back'), (2.6, roar2), (3.0, pose(I0, rot={'body': (-360, 0, 0)}))])
    B.shake(c, 'chest', 2.05, 2.6, 2.5, 13)
    B.shake(c, 'h_head', 2.05, 2.6, 2.0, 11)
    hair(c, 0.75, 1.2)
    hair(c, 1.6, 1.4, back=False)
    hair(c, 2.0, 0.8)
    if has('cloud'):
        c.scl('cloud', lambda t: k3(0.01 + 1.19 * pulse(t, 0.62, 1.2)))
        c.pos('cloud', lambda t: (0, 10 * step(t, 0.7, 1.0), 0))
    B.sounds(c, [(0.4, 'ability_kiai', 0.9, 1.1), (0.75, 'ability_cloud', 1.0, 1.0), (1.0, 'ability_whoosh', 1.0, 0.9),
                 (1.6, 'ability_slam', 1.0, 0.9), (2.0, 'ability_roar', 1.0, 0.95), (2.05, 'ability_gong', 0.9, 0.8)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ havoc (phase 2 -> 3)
    c = Clip(m, 'havoc', 3.2)
    staff_up = {'arm_r': (48, 0, -62), 'forearm_r': (-12, 0, 0), 'staff': (-10, 0, 0)}
    out_l = pose(I0, d=2, rot=dict(staff_up, **{'arm_l': (30, 0, 46), 'forearm_l': (50, 0, 0), 'chest': (6, 0, 0),
                                                'h_head': (6, 0, 0), 'jaw': (-10, 0, 0)}))
    in_l = pose(I0, d=2.5, rot=dict(staff_up, **{'arm_l': (16.2, 12.4, 0), 'forearm_l': (104.5, 35.4, -12.9), 'chest': (12, 0, 0),
                                                 'h_head': (10, 0, 0), 'jaw': (-22, 0, 0)}))
    sky = pose(I0, rot={'chest': (22, 0, 0), 'h_head': (32, 0, 0), 'jaw': (-34, 0, 0), 'arm_l': (-8, 0, 74),
                        'forearm_l': (-8, 0, 0), 'arm_r': (168, 10, -8), 'forearm_r': (-10, 0, 0), 'staff': (180, 0, 0)},
               pos={'body': (0, 2, 0)}, scl={'eyes': (1.6, 1.6, 1.6)})
    wind2 = pose(sky, pos={'body': (0, 5, 0)}, rot={'chest': (18, 0, 0), 'h_head': (-10, 0, 0), 'jaw': (-12, 0, 0),
                                                     'arm_l': (150, -10, 10), 'forearm_l': (-30, 0, 0)})
    slam = pose(I0, d=6, rot={'arm_r': (64, 0, 0), 'forearm_r': (-8, 0, 0), 'staff': (180, 0, 0), 'chest': (-30, 0, 0),
                              'arm_l': (60, 0, 12), 'forearm_l': (-20, 0, 0), 'h_head': (14, 0, 0), 'jaw': (-20, 0, 0)},
                spread=6)
    B.seq(c, [(0, I0), (0.25, out_l), (0.4, in_l, 'snap'), (0.55, out_l), (0.7, in_l, 'snap'), (0.85, out_l),
              (1.0, in_l, 'snap'), (1.3, sky, 'back'), (2.0, pose(sky, rot={'chest': (24, 0, 0)})), (2.15, wind2),
              (2.4, slam, 'snap'), (2.8, slam), (3.2, I0)])
    B.shake(c, 'chest', 1.35, 2.0, 2.5, 12)
    for t0 in (0.4, 0.7, 1.0):
        c.pos('body', (lambda t0: lambda t: (0, 0, 0.8 * decay(t, t0, 3, 9)))(t0), add=True)
    hair(c, 1.3, 0.8)
    hair(c, 2.4, 1.4, back=False)
    B.sounds(c, [(0.4, 'ability_thud', 0.8, 1.3), (0.7, 'ability_thud', 0.8, 1.2), (1.0, 'ability_thud', 0.9, 1.1),
                 (1.3, 'ability_roar', 1.0, 0.85), (2.15, 'ability_kiai', 0.8, 0.9), (2.3, 'ability_whoosh', 1.0, 0.75),
                 (2.4, 'ability_slam', 1.0, 0.8)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ peach_eat (loop) / peach_choke
    L = 1.6
    c = Clip(m, 'peach_eat', L, 'loop')
    lo = pose(I0, rot=dict(SIT, **EAT_LO), pos=SIT_POS)
    up_open = pose(I0, rot=dict(SIT, **EAT_UP, **{'jaw': (-22, 0, 0), 'h_head': (6, 0, 0)}), pos=SIT_POS)
    bite = pose(I0, rot=dict(SIT, **EAT_UP, **{'jaw': (0, 0, 0), 'h_head': (-4, 0, 0), 'chest': (0, 0, 0)}), pos=SIT_POS)
    chew = pose(lo, rot={'h_head': (2, 0, -4)})
    B.seq(c, [(0, lo), (0.35, up_open), (0.5, bite, 'snap'), (0.62, bite), (0.85, chew), (1.15, up_open), (1.3, bite, 'snap'),
              (1.42, bite), (1.6, lo)])
    c.rot('jaw', lambda t: (-6 * abs(wave(t, L, 8)) * (pulse(t, 0.55, 1.05) + pulse(t, 1.35, 1.6) + pulse(t, -0.25, 0.25)), 0, 0),
          add=True)
    c.pos('body', lambda t: (0, 0.5 * (decay(t, 0.5, 3, 8) + decay(t, 1.3, 3, 8)), 0), add=True)
    sway(c, TAIL, 10, 2, axis=1)
    c.rot('tail1', lambda t: (-6 * abs(wave(t, L, 1)), 0, 0), add=True)
    if has('peach'):
        # bitten smaller twice, then a fresh peach out of nowhere (it is a loop: he has a pile)
        c.scl('peach', lambda t: k3(1.0 - 0.18 * step(t, 0.48, 0.55) - 0.2 * step(t, 1.28, 1.35) + 0.38 * step(t, 1.48, 1.58)))
    B.sounds(c, [(0.5, 'ability_chew', 0.8, 1.0), (1.3, 'ability_chew', 0.8, 1.1)])
    out.append(B.keep(m, c))

    c = Clip(m, 'peach_choke', 1.2)
    p0 = B.P(m, 'peach_eat', 0)
    throat = pose(p0, rot={'arm_l': (57.6, 25, -18.2), 'forearm_l': (97.8, 40.6, -20.7), 'hand_l': (0, 0, 0), 'jaw': (-20, 0, 0),
                           'h_head': (-10, 0, 0), 'chest': (-6, 0, 0)}, scl={'eyes': (1.4, 1.4, 1.4)})
    cough = pose(throat, rot={'chest': (-26, 0, 0), 'h_head': (-16, 0, 0), 'jaw': (-28, 0, 0)}, pos={'body': (0, -15, -1)})
    scramble = pose(I0, d=7, rot={'chest': (-20, 0, 0), 'h_head': (-6, 0, 0), 'arm_l': (40, 25, -18), 'forearm_l': (98, 40, -20),
                                  'hand_l': (0, 0, 0), 'arm_r': (10, 0, -40), 'staff': (-40, 0, 30), 'jaw': (-18, 0, 0)},
                    spread=6)
    stagger = pose(DZ, rot={'chest': (14, 0, 8), 'h_head': (16, 0, 10), 'arm_l': (40, 0, 60), 'jaw': (-20, 0, 0)},
                   pos={'body': (0, -2, 2)})
    B.seq(c, [(0, p0), (0.15, throat, 'out'), (0.3, cough, 'snap'), (0.5, scramble), (0.8, stagger, 'out'), (1.2, DZ)])
    B.shake(c, 'chest', 0.28, 0.5, 3, 16)
    if has('peach'):
        c.scl('peach', lambda t: k3(1.0 - step(t, 0.1, 0.25)))
    B.sounds(c, [(0.3, 'ability_grunt', 1.0, 1.4), (0.42, 'ability_grunt', 0.8, 1.6), (0.8, 'ability_thud', 0.5, 1.4)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ transform / reveal
    c = Clip(m, 'transform', 1.4)
    seal = pose(I0, d=3, rot={'arm_l': (21.7, 9.5, -13.8), 'forearm_l': (86.2, 31.9, -9.8), 'arm_r': (19.4, -5.4, 19),
                              'forearm_r': (80.9, -33.1, 18.4), 'staff': (0, 0, 0), 'h_head': (-8, 0, 0), 'chest': (-6, 0, 0)}, scl={'eyes': (1.5, 1.5, 1.5)})
    spin = pose(seal, rot={'body': (0, 360, 0)}, pos={'body': (0, 4, 0)}, scl={'body': (0.03, 0.03, 0.03)})
    B.seq(c, [(0, I0), (0.4, seal, 'snap'), (0.6, pose(seal, rot={'h_head': (-12, 0, 0)})), (1.2, spin, 'in'), (1.4, spin)])
    B.shake(c, 'arm_l', 0.4, 0.6, 2, 18)
    B.shake(c, 'arm_r', 0.4, 0.6, 2, 18)
    whip(c, PLUME_L + PLUME_R + TAIL, 0.6, 18, axis=1, freq=1.5, damp=1.5)
    B.sounds(c, [(0.35, 'ability_kiai', 0.8, 1.2), (0.65, 'ability_whoosh', 0.8, 1.4), (1.0, 'ability_poof', 1.0, 1.0)])
    c.particle(1.15, 'minecraft:poof', 14, 0.6, 0.05, 0.5)
    out.append(B.keep(m, c))

    c = Clip(m, 'reveal', 0.8)
    tiny = pose(I0, scl={'body': (0.03, 0.03, 0.03)})
    pop = pose(I0, rot={'arm_l': (40, 0, 84), 'forearm_l': (-20, 0, 0), 'arm_r': (40, 0, -84), 'forearm_r': (-20, 0, 0),
                        'staff': (60, 0, 0), 'leg_l': (20, 0, -18), 'leg_r': (-10, 0, 18), 'h_head': (16, 0, 0), 'jaw': (-24, 0, 0)},
               pos={'body': (0, 5, 0)}, scl={'body': (1.15, 1.15, 1.15), 'eyes': (1.6, 1.6, 1.6)})
    landp = pose(DZ, d=4, rot={'chest': (-14, 0, 6), 'h_head': (4, 0, 10)})
    B.seq(c, [(0, tiny), (0.1, pop, 'back'), (0.25, pose(pop, scl={'body': (1, 1, 1)})), (0.5, landp, 'in'), (0.8, DZ)])
    hair(c, 0.1, 1.2)
    hair(c, 0.5, 0.8, back=False)
    B.sounds(c, [(0.05, 'ability_poof', 1.0, 1.2), (0.3, 'ability_grunt', 0.9, 1.3), (0.5, 'ability_thud', 0.7, 1.2)])
    c.particle(0.05, 'minecraft:poof', 16, 0.7, 0.06, 0.5)
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ cloud_ride (loop) / strike / dive
    L = 1.6
    c = Clip(m, 'cloud_ride', L, 'loop')
    ride = pose(I0, d=3.5, rot={'body': (0, 28, 0), 'chest': (-10, -22, 0), 'h_head': (6, -6, 0),
                                'leg_l': (52, 0, -6), 'shin_l': (-70, 0, 0), 'foot_l': (18, 0, 0),
                                'leg_r': (18, 0, 8), 'shin_r': (-66, 0, 0), 'foot_r': (48, 0, 0),
                                'arm_l': (70, 0, 34), 'forearm_l': (-22, 0, 0), 'arm_r': (176, 0, -6), 'forearm_r': (0, 0, 0),
                                'staff': (90, 0, 0)}, spread=6)
    B.seq(c, [(0, ride), (L, ride)])
    c.rot('staff', lambda t: (0, 360 * t / L, 0), add=True)

    def bob(t):
        return 0.9 * wave(t, L, 1)
    c.pos('body', lambda t: (0, bob(t), 0), add=True)
    if has('cloud'):
        c.pos('cloud', lambda t: (0, bob(t), 0))
        c.rot('cloud', lambda t: (2 * wave(t, L, 1, 0.25), 0, 3 * wave(t, L, 1)))
        c.scl('cloud', lambda t: (1 + 0.04 * wave(t, L, 2), 1, 1 + 0.04 * wave(t, L, 2, 0.25)))
    c.rot('arm_l', lambda t: (4 * wave(t, L, 1, 0.1), 0, 4 * wave(t, L, 1)), add=True)
    stream(c, PLUME_L + PLUME_R, 26)
    stream(c, RIB_L + RIB_R, -38)
    stream(c, TAIL, 18)
    stream(c, ('skirt_b',), -18)
    sway(c, PLUME_L + PLUME_R, 6, 4, axis=0, lag=0.12)
    sway(c, RIB_L + RIB_R, 10, 4, axis=0, lag=0.12)
    sway(c, TAIL, 8, 2, axis=1)
    B.sounds(c, [(0.4, 'ability_whoosh', 0.3, 1.4), (1.2, 'ability_whoosh', 0.3, 1.5)])
    c.particle(0.0, 'minecraft:cloud', 3, 0.6, 0.02, 0.05)
    c.particle(0.8, 'minecraft:cloud', 3, 0.6, 0.02, 0.05)
    out.append(B.keep(m, c))
    R0 = B.P(m, 'cloud_ride', 0)

    c = Clip(m, 'cloud_strike', 0.8)
    up = pose(R0, rot={'arm_r': (168, 10, -8), 'forearm_r': (-10, 0, 0), 'staff': (180, 0, 0), 'chest': (8, -10, 0),
                       'h_head': (-14, 0, 0)}, pos={'body': (0, 2, 0)})
    stab = pose(R0, d=6, rot={'body': (0, 10, 0), 'arm_r': (18, 0, -8), 'forearm_r': (0, 0, 0), 'staff': (180, 0, 0),
                              'chest': (-30, -4, 0), 'h_head': (-20, 0, 0), 'arm_l': (40, 0, 50)}, spread=6)
    B.seq(c, [(0, R0), (0.2, up, 'out'), (0.35, stab, 'snap'), (0.5, stab), (0.8, R0)])
    if has('cloud'):
        c.pos('cloud', lambda t: (0, -1.5 * pulse(t, 0.3, 0.65), 0))
        c.scl('cloud', lambda t: (1 + 0.12 * pulse(t, 0.33, 0.6), 1 - 0.1 * pulse(t, 0.33, 0.6), 1 + 0.12 * pulse(t, 0.33, 0.6)))
    hair(c, 0.35, 1.0, back=False)
    B.sounds(c, [(0.2, 'ability_kiai', 0.8, 1.15), (0.3, 'ability_whoosh', 0.9, 1.0)])
    out.append(B.keep(m, c))

    c = Clip(m, 'cloud_dive', 1.4)
    spring = pose(R0, d=7, rot={'body': (0, 10, 0), 'chest': (-20, 0, 0), 'arm_r': (-40, 0, -14), 'staff': (-40, 0, 0),
                                'arm_l': (-40, 0, 14), 'h_head': (16, 0, 0)}, spread=6)
    air = pose(I0, rot={'leg_l': (70, 0, -4), 'leg_r': (50, 0, 4), 'shin_l': (-100, 0, 0), 'shin_r': (-90, 0, 0),
                        'foot_l': (30, 0, 0), 'foot_r': (30, 0, 0), 'arm_r': (168, 10, -8), 'forearm_r': (-10, 0, 0),
                        'staff': (180, 0, 0), 'arm_l': (165, -10, 14), 'forearm_l': (-24, 0, 0), 'chest': (12, 0, 0),
                        'h_head': (-6, 0, 0)}, pos={'body': (0, 8, 0)})
    fall = pose(air, rot={'chest': (16, 0, 0), 'leg_l': (40, 0, -6), 'leg_r': (30, 0, 6), 'shin_l': (-50, 0, 0),
                          'shin_r': (-50, 0, 0)}, pos={'body': (0, 3, 0)})
    hit = pose(I0, d=8, rot={'arm_r': (64, 0, 0), 'forearm_r': (-8, 0, 0), 'staff': (180, 0, 0), 'chest': (-32, 0, 0),
                             'arm_l': (60, 0, 12), 'forearm_l': (-20, 0, 0), 'h_head': (16, 0, 0), 'jaw': (-16, 0, 0)},
               spread=7)
    B.seq(c, [(0, R0), (0.2, spring), (0.4, air, 'out'), (0.75, fall), (0.9, hit, 'snap'), (1.1, hit), (1.4, I0)])
    if has('cloud'):
        c.scl('cloud', lambda t: k3(1.0 + 0.15 * pulse(t, 0.25, 0.45) - 0.99 * step(t, 0.4, 0.7)))
        c.pos('cloud', lambda t: (0, -2 * pulse(t, 0.15, 0.45), 0))
    hair(c, 0.4, 1.2)
    hair(c, 0.9, 1.5, back=False)
    B.sounds(c, [(0.2, 'ability_kiai', 1.0, 1.0), (0.4, 'ability_cloud', 0.9, 1.1), (0.75, 'ability_whoosh', 1.0, 0.8),
                 (0.9, 'ability_slam', 1.0, 0.9)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ Faxiang Tiandi
    c = Clip(m, 'giant_grow', 1.8)
    curl = pose(I0, d=5, rot={'chest': (-26, 0, 0), 'h_head': (-20, 0, 0), 'arm_l': (14, 0, 16), 'forearm_l': (80, 30, 0),
                              'arm_r': (14, 0, -16), 'forearm_r': (80, -30, 0), 'staff': (-30, 0, 0)}, spread=6)
    burst = pose(I0, d=2, rot={'chest': (18, 0, 0), 'h_head': (24, 0, 0), 'jaw': (-32, 0, 0), 'arm_l': (8, 0, 118),
                               'forearm_l': (-14, 0, 0), 'arm_r': (8, 0, -118), 'forearm_r': (-14, 0, 0), 'staff': (0, 0, 0)},
                 spread=12, scl={'eyes': (1.6, 1.6, 1.6)})
    B.seq(c, [(0, I0), (0.4, curl), (0.7, pose(curl, rot={'chest': (-30, 0, 0)})), (1.0, burst, 'back'),
              (1.5, pose(burst, rot={'chest': (20, 0, 0)})), (1.8, I0)])
    B.shake(c, 'chest', 0.4, 0.95, 2.5, 18)
    B.shake(c, 'chest', 1.0, 1.5, 2.0, 10)
    hair(c, 1.0, 1.4)
    B.sounds(c, [(0.2, 'ability_strain', 0.9, 0.8), (0.3, 'ability_grow', 1.0, 1.0), (1.0, 'ability_roar', 1.0, 0.7)])
    out.append(B.keep(m, c))

    c = Clip(m, 'giant_slam', 2.0)
    raise_ = pose(I0, rot={'arm_r': (120, 0, -10), 'forearm_r': (-20, 0, 0), 'staff': (150, 0, 0), 'arm_l': (110, 0, 10),
                           'forearm_l': (-30, 0, 0), 'chest': (8, 0, 0), 'h_head': (4, 0, 0)}, pos={'body': (0, 1, 0)})
    top = pose(I0, rot={'arm_r': (176, 0, -6), 'forearm_r': (-4, 0, 0), 'staff': (180, 0, 0), 'arm_l': (172, 0, 8),
                        'forearm_l': (-8, 0, 0), 'chest': (24, 0, 0), 'h_head': (10, 0, 0), 'jaw': (-14, 0, 0),
                        'leg_l': (-6, 0, -6), 'leg_r': (-6, 0, 6), 'foot_l': (-10, 0, 0), 'foot_r': (-10, 0, 0)},
               pos={'body': (0, 2.5, 0)})
    smash = pose(I0, d=8, rot={'arm_r': (62, 0, 14), 'forearm_r': (-6, 0, 0), 'staff': (180, 0, 0), 'arm_l': (60, 0, -16),
                               'forearm_l': (-24, 0, 0), 'chest': (-36, 0, 0), 'h_head': (18, 0, 0), 'jaw': (-24, 0, 0)},
                 spread=8)
    B.seq(c, [(0, I0), (0.5, raise_), (1.1, top, 'out'), (1.3, smash, 'snap'), (1.7, pose(smash, rot={'chest': (-34, 0, 0)})),
              (2.0, I0)])
    B.shake(c, 'chest', 1.3, 1.7, 2.5, 14)
    hair(c, 1.3, 1.6, back=False)
    B.sounds(c, [(0.9, 'ability_kiai', 1.0, 0.75), (1.15, 'ability_whoosh', 1.0, 0.6), (1.3, 'ability_slam', 1.0, 0.65)])
    out.append(B.keep(m, c))

    c = Clip(m, 'giant_sweep', 2.2)
    # the staff held out at full length, in line with the arm (x 180 flips the long end outward)
    arm_out = {'arm_r': (88, 0, -30), 'forearm_r': (-4, 0, 0), 'staff': (180, 0, 0), 'arm_l': (40, 0, 40), 'forearm_l': (30, 0, 0)}
    wound = pose(I0, d=5, rot=dict(arm_out, **{'body': (0, 62, 0), 'chest': (-8, 24, 0), 'h_head': (0, -30, 0)}), spread=8)
    mid = pose(wound, rot={'body': (0, -74, 0), 'chest': (-12, -10, 0), 'h_head': (0, 0, 0)})
    thru = pose(wound, rot={'body': (0, -210, 0), 'chest': (-10, -30, 0), 'h_head': (0, 20, 0)})
    follow = pose(wound, rot={'body': (0, -300, 0), 'chest': (-4, -16, 0), 'arm_r': (50, 0, -30)})
    endp = pose(I0, rot={'body': (0, -360, 0)})
    B.seq(c, [(0, I0), (0.6, wound, 'out'), (0.9, pose(wound, rot={'body': (0, 66, 0)})), (1.15, mid, 'linear'),
              (1.4, thru, 'linear'), (1.7, follow, 'out'), (2.2, endp)])
    stream(c, PLUME_L + PLUME_R, 24, axis=2, env=lambda t: pulse(t, 0.9, 1.8))
    stream(c, TAIL, 30, axis=1, env=lambda t: pulse(t, 0.95, 1.9))
    stream(c, RIB_L + RIB_R, 30, axis=2, env=lambda t: pulse(t, 0.95, 1.9))
    B.sounds(c, [(0.6, 'ability_kiai', 1.0, 0.8), (0.95, 'ability_whoosh', 1.0, 0.6), (1.15, 'ability_whoosh', 1.0, 0.65),
                 (1.35, 'ability_whoosh', 1.0, 0.7)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ staff throw / catch, vault
    c = Clip(m, 'staff_throw', 1.0)
    # javelin grip: the fist above and behind the shoulder, the staff pointing at the target
    cock = pose(I0, d=3, rot={'arm_r': (99.1, 12.6, 26.9), 'forearm_r': (108.8, -18.5, 38.7), 'staff': (57.3, 24.8, 13.3),
                              'chest': (8, 34, 0),
                              'h_head': (0, -28, 0), 'arm_l': (60, 0, 30), 'forearm_l': (-20, 0, 0),
                              'leg_l': (30, 0, -4), 'shin_l': (-30, 0, 0)}, spread=5)
    rel = pose(I0, d=4, rot={'arm_r': (96, -10, -8), 'forearm_r': (-2, 0, 0), 'staff': (180, 0, 0), 'chest': (-14, -24, 0),
                             'h_head': (10, 20, 0), 'arm_l': (-20, 0, 30), 'forearm_l': (-10, 0, 0),
                             'leg_l': (44, 0, -4), 'shin_l': (-54, 0, 0), 'foot_l': (10, 0, 0)}, spread=5)
    thrown = pose(rel, rot={'arm_r': (36, -10, -6), 'chest': (-18, -16, 0), 'h_head': (16, 10, 0)})
    B.seq(c, [(0, I0), (0.25, cock, 'out'), (0.4, rel, 'snap'), (0.6, thrown), (1.0, I0)])
    hair(c, 0.4, 0.9, back=False)
    B.sounds(c, [(0.28, 'ability_kiai', 0.9, 1.1), (0.4, 'ability_whoosh', 1.0, 1.1)])
    out.append(B.keep(m, c))

    c = Clip(m, 'staff_catch', 0.7)
    # caught high, the staff across his front (horizontal), twirled once, back to the stance
    reach = pose(I0, rot={'arm_r': (89.7, 6, -12.4), 'forearm_r': (74.7, 32.1, -23.2), 'staff': (-13.3, 7.3, -66.7),
                          'chest': (6, -10, 0), 'h_head': (12, 0, 0)})
    caught = pose(I0, d=3, rot={'arm_r': (20.1, -11.8, -7), 'forearm_r': (83.8, -17.5, -8.2), 'staff': (-13.7, -1.2, -119.2),
                                'chest': (-4, 0, 0)}, pos={'body': (0, -3, 1.5)})
    twirl = caught
    B.seq(c, [(0, reach), (0.1, caught, 'snap'), (0.45, twirl), (0.7, I0)])
    c.rot('staff', lambda t: (0, 0, 360 * step(t, 0.15, 0.5)), add=True)
    hair(c, 0.1, 0.8)
    B.sounds(c, [(0.1, 'ability_thud', 0.6, 1.5), (0.25, 'ability_whoosh', 0.7, 1.5)])
    out.append(B.keep(m, c))

    c = Clip(m, 'vault', 1.6)
    run = pose(I0, d=3, rot={'arm_r': (44, 0, -10), 'forearm_r': (-10, 0, 0), 'staff': (196, 0, 0), 'chest': (-14, 0, 0)})
    plant = pose(I0, d=4, rot={'arm_r': (70, 0, -8), 'forearm_r': (0, 0, 0), 'staff': (180, 0, 0), 'chest': (-20, 0, 0),
                               'arm_l': (-30, 0, 20), 'h_head': (12, 0, 0)})
    over = pose(I0, rot={'body': (-34, 0, 0), 'arm_r': (16, 0, -8), 'forearm_r': (0, 0, 0), 'staff': (180, 0, 0),
                         'leg_l': (74, 0, -4), 'leg_r': (66, 0, 4), 'shin_l': (-26, 0, 0), 'shin_r': (-34, 0, 0),
                         'arm_l': (40, 0, 50), 'chest': (-8, 0, 0), 'h_head': (20, 0, 0)}, pos={'body': (0, 16, 0)})
    kick = pose(I0, rot={'body': (24, 0, 0), 'arm_r': (-30, 0, -40), 'forearm_r': (-10, 0, 0), 'staff': (40, 0, 0),
                         'leg_l': (88, 0, -6), 'leg_r': (80, 0, 6), 'shin_l': (0, 0, 0), 'shin_r': (-6, 0, 0),
                         'foot_l': (-20, 0, 0), 'foot_r': (-20, 0, 0), 'arm_l': (-30, 0, 50), 'chest': (-6, 0, 0),
                         'h_head': (-10, 0, 0), 'jaw': (-20, 0, 0)}, pos={'body': (0, 10, 0)})
    landv = pose(I0, d=6, rot={'arm_r': (24, 0, -40), 'forearm_r': (-12, 0, 0), 'staff': (40, 0, 0), 'chest': (-22, 0, 0),
                               'arm_l': (30, 0, 30), 'h_head': (14, 0, 0)}, spread=7)
    B.seq(c, [(0, I0), (0.15, run), (0.3, plant, 'snap'), (0.6, pose(over, pos={'body': (0, 12, 0)}), 'out'), (0.85, over),
              (1.0, kick, 'snap'), (1.1, kick), (1.2, landv, 'in'), (1.6, I0)])
    hair(c, 0.3, 1.0)
    hair(c, 1.0, 1.0, back=False)
    hair(c, 1.2, 0.8, back=False)
    B.sounds(c, [(0.3, 'ability_thud', 0.6, 1.4), (0.45, 'ability_whoosh', 0.8, 1.2), (0.9, 'ability_kiai', 1.0, 1.15),
                 (1.0, 'ability_whoosh', 0.9, 1.3), (1.2, 'ability_thud', 0.8, 1.0)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ eye sweep
    c = Clip(m, 'eye_sweep', 2.4)
    shade = {'arm_l': (160, 0, -22), 'forearm_l': (-62, 0, 0), 'hand_l': (-20, 0, 0)}
    look_r = pose(I0, d=3, rot=dict(shade, **{'h_head': (-8, 60, 0), 'chest': (-10, 20, 0)}), scl={'eyes': (1.8, 1.8, 1.8)})
    look_l = pose(look_r, rot={'h_head': (-8, -60, 0), 'chest': (-10, -20, 0)})
    B.seq(c, [(0, I0), (0.5, pose(look_r, rot={'h_head': (-2, 30, 0)}, scl={'eyes': (1.3, 1.3, 1.3)}), 'out'), (0.6, look_r),
              (2.0, look_l, 'linear'), (2.1, look_l), (2.4, I0)])
    B.shake(c, 'h_head', 0.6, 2.0, 1.2, 15)
    c.scl('eyes', lambda t: k3(1 + 0.12 * wave(t, 0.2, 1) * pulse(t, 0.55, 2.05)), add=True)
    B.sounds(c, [(0.5, 'ability_kiai', 0.7, 1.25)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ furnace (loop)
    L = 3.0
    c = Clip(m, 'furnace', L, 'loop')
    grip = pose(I0, d=4, rot={'arm_r': (24.4, -11.8, 9.3), 'forearm_r': (54.9, -15.9, 35.5), 'staff': (-76, -31.7, -29.3),
                              'arm_l': (33.7, 8.2, -14.4), 'forearm_l': (73.3, 37.5, -22.9), 'chest': (-6, 0, 0),
                              'h_head': (-18, 0, 0)},
                spread=8, scl={'eyes': (1.4, 1.4, 1.4)})
    B.seq(c, [(0, grip), (L, grip)])

    def rise(t):
        return 0.5 - 0.5 * math.cos(2 * math.pi * t / L)
    c.rot('h_head', lambda t: (22 * rise(t), 0, 0), add=True)
    c.rot('chest', lambda t: (6 * rise(t), 0, 0), add=True)
    c.scl('eyes', lambda t: k3(1 + 0.35 * rise(t)), add=True)
    B.shake(c, 'chest', 0.0, L, 0.8, 9)
    stream(c, PLUME_L + PLUME_R, 18)
    stream(c, RIB_L + RIB_R, -30)
    sway(c, PLUME_L + PLUME_R, 9, 9, axis=0, lag=0.15)
    sway(c, PLUME_L + PLUME_R, 6, 6, axis=2, lag=0.1)
    sway(c, RIB_L + RIB_R, 14, 12, axis=0, lag=0.15)
    sway(c, TAIL, 10, 6, axis=1)
    sway(c, ('skirt_f', 'skirt_b'), 5, 12, axis=0)
    c.particle(0.0, 'minecraft:flame', 4, 0.5, 0.02, 0.3)
    c.particle(1.5, 'minecraft:flame', 4, 0.5, 0.02, 0.3)
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ Buddha's palm: look, sealed, break free
    c = Clip(m, 'palm_look', 1.6)
    snap_up = pose(I0, rot={'h_head': (42, 0, 0), 'chest': (12, 0, 0), 'jaw': (-22, 0, 0)}, scl={'eyes': (1.5, 1.7, 1.5)})
    recoil = pose(FIN, rot={'h_head': (40, 0, 0), 'chest': (6, 0, 0), 'jaw': (-18, 0, 0), 'arm_l': (124, 0, 30),
                            'forearm_l': (-84, 0, 0), 'arm_r': (64, 0, -30), 'staff': (90, 0, 0)},
                  pos={'body': (0, -5, 2)}, scl={'eyes': (1.5, 1.7, 1.5)})
    B.seq(c, [(0, I0), (0.3, snap_up, 'snap'), (0.6, recoil, 'out'), (1.2, pose(recoil, rot={'h_head': (34, 0, 0)})), (1.6, FIN)])
    B.shake(c, 'chest', 0.6, 1.3, 1.5, 16)
    hair(c, 0.3, 0.8)
    B.sounds(c, [(0.3, 'ability_grunt', 1.0, 1.3), (0.6, 'ability_thud', 0.5, 1.3)])
    out.append(B.keep(m, c))

    L = 2.0
    c = Clip(m, 'sealed', L, 'loop')
    pin = pose(I0, rot={'leg_l': (6, 0, -8), 'leg_r': (6, 0, 8), 'shin_l': (-96, 0, 0), 'shin_r': (-96, 0, 0),
                        'foot_l': (60, 0, 0), 'foot_r': (60, 0, 0), 'skirt_f': (-10, 0, 0), 'chest': (-22, 0, 0),
                        'h_head': (14, 0, 0), 'arm_l': (180, -16.6, 4.8), 'forearm_l': (16.1, -1.1, 12.5), 'hand_l': (0, 0, 0),
                        'arm_r': (180, 26.4, 1.1), 'forearm_r': (15.3, 14.9, -9.1), 'staff': (-11.7, 1.3, -101),
                        'jaw': (-6, 0, 0)},
               pos={'body': (0, -12, 0)})
    B.seq(c, [(0, pin), (L, pin)])

    def push(t):
        return pulse(t, 0.45, 0.95) + pulse(t, 1.35, 1.85)
    c.pos('body', lambda t: (0, 1.4 * push(t), 0), add=True)
    c.rot('arm_l', lambda t: (8 * push(t), 0, 0), add=True)
    c.rot('arm_r', lambda t: (8 * push(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (10 * push(t), 0, 0), add=True)
    c.rot('jaw', lambda t: (-10 * push(t), 0, 0), add=True)
    B.shake(c, 'chest', 0.45, 0.95, 2.5, 18)
    B.shake(c, 'chest', 1.35, 1.85, 2.5, 18)
    sway(c, TAIL, 8, 1, axis=1)
    B.sounds(c, [(0.6, 'ability_strain', 0.9, 1.0), (1.5, 'ability_strain', 0.7, 1.1)])
    out.append(B.keep(m, c))
    S0 = B.P(m, 'sealed', 0)

    c = Clip(m, 'break_free', 1.6)
    coil = pose(S0, rot={'chest': (-34, 0, 0), 'h_head': (-14, 0, 0), 'arm_l': (60, 0, 20), 'forearm_l': (-60, 0, 0),
                         'arm_r': (60, 0, -20), 'forearm_r': (-60, 0, 0), 'staff': (-60, 0, 0)}, pos={'body': (0, -13, 0)})
    burst = pose(I0, rot={'chest': (20, 0, 0), 'h_head': (26, 0, 0), 'jaw': (-32, 0, 0), 'arm_l': (10, 0, 130),
                          'forearm_l': (-10, 0, 0), 'arm_r': (10, 0, -130), 'forearm_r': (-10, 0, 0), 'staff': (0, 0, 0),
                          'leg_l': (-8, 0, -14), 'leg_r': (-8, 0, 14), 'foot_l': (-16, 0, 0), 'foot_r': (-16, 0, 0)},
                 pos={'body': (0, 5, 0)}, scl={'eyes': (1.6, 1.6, 1.6)})
    landb = pose(I0, d=5, rot={'chest': (-18, 0, 0), 'h_head': (14, 0, 0), 'arm_l': (20, 0, 40), 'arm_r': (30, 0, -40),
                               'staff': (40, 0, 0)}, spread=8)
    B.seq(c, [(0, S0), (0.4, coil), (0.6, burst, 'snap'), (0.75, burst), (1.0, landb, 'in'), (1.6, I0)])
    B.shake(c, 'chest', 0.1, 0.45, 3, 20)
    hair(c, 0.6, 1.6)
    hair(c, 1.0, 1.0, back=False)
    B.sounds(c, [(0.2, 'ability_strain', 1.0, 0.9), (0.55, 'ability_slam', 1.0, 0.7), (0.6, 'ability_roar', 1.0, 0.9),
                 (1.0, 'ability_thud', 0.8, 1.0)])
    c.particle(0.6, 'minecraft:poof', 20, 1.0, 0.1, 0.3)
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ laugh
    c = Clip(m, 'laugh', 2.0)
    belly = {'arm_l': (-2.1, 0.6, -18.8), 'forearm_l': (45.7, 6.9, -4.7), 'hand_l': (0, 0, 0)}
    back_ = pose(I0, rot=dict(belly, **{'chest': (14, 0, 0), 'h_head': (22, 0, 0), 'jaw': (-20, 0, 0),
                                        'arm_r': (10, 0, -30), 'staff': (20, 0, 0)}), pos={'body': (0, 0, 1)})
    fold = pose(I0, d=3, rot={'chest': (-26, 0, 0), 'h_head': (6, 0, 0), 'jaw': (-22, 0, 0), 'arm_l': (8, 0, 6),
                              'forearm_l': (-4, 0, 0), 'arm_r': (10, 0, -30), 'staff': (20, 0, 0),
                              'leg_l': (44, 0, -3), 'shin_l': (-70, 0, 0)})
    B.seq(c, [(0, I0), (0.25, back_, 'back'), (1.15, back_), (1.35, fold, 'snap'), (1.55, back_), (2.0, I0)])
    c.rot('chest', lambda t: (8 * wave(t, 2.0, 7) * pulse(t, 0.2, 1.2), 0, 0), add=True)
    c.rot('h_head', lambda t: (6 * wave(t, 2.0, 7, 0.1) * pulse(t, 0.2, 1.2), 0, 4 * wave(t, 2.0, 3)), add=True)
    c.rot('jaw', lambda t: (-10 * abs(wave(t, 2.0, 7)) * pulse(t, 0.2, 1.9), 0, 0), add=True)
    c.pos('body', lambda t: (0, 0.6 * abs(wave(t, 2.0, 7)) * pulse(t, 0.2, 1.2), 0), add=True)
    sway(c, TAIL, 12, 4, axis=1, env=lambda t: pulse(t, 0.1, 1.9))
    B.sounds(c, [(0.25, 'ability_laugh', 1.0, 1.0), (1.35, 'ability_thud', 0.45, 1.6), (1.5, 'ability_laugh', 0.7, 1.15)])
    out.append(B.keep(m, c))

    # ------------------------------------------------------------------------------ idle_shoulder (calm loop)
    L = 6.0
    c = Clip(m, 'idle_shoulder', L, 'loop')
    # staff across the back of his shoulders, both wrists hooked over it
    yoke = pose(I0, rot={'arm_r': (4.4, 58.4, -67.2), 'forearm_r': (150, 15.3, 38.4), 'staff': (-44.2, -12.7, -105),
                         'arm_l': (-15.7, -55, 91.2), 'forearm_l': (97.3, -60, 29.5), 'hand_l': (0, 0, 0), 'chest': (4, 0, 0),
                         'h_head': (4, 0, 0)})
    B.seq(c, [(0, yoke), (L, yoke)])
    c.rot('h_head', lambda t: (16 * pulse(t, 3.0, 4.0), 10 * wave(t, L, 1), 6 * wave(t, L, 2, 0.25)), add=True)
    c.rot('jaw', lambda t: (-26 * pulse(t, 3.05, 4.0), 0, 0), add=True)                       # a big lazy yawn
    c.rot('chest', lambda t: (6 * pulse(t, 3.0, 4.0) + 1.5 * wave(t, L, 2), 3 * wave(t, L, 1), 0), add=True)
    c.pos('body', lambda t: (0, -0.4 + 0.4 * wave(t, L, 2), 0), add=True)

    def tap(t):
        return pulse(t, 1.0, 1.3) + pulse(t, 1.4, 1.7) + pulse(t, 5.0, 5.3)
    c.rot('leg_r', lambda t: (8 * tap(t), 0, 0), add=True)
    c.rot('foot_r', lambda t: (-12 * tap(t), 0, 0), add=True)
    sway(c, TAIL, 14, 2, axis=1)
    sway(c, PLUME_L + PLUME_R, 4, 3, axis=2)
    B.sounds(c, [(3.1, 'ability_grunt', 0.35, 0.8)])
    out.append(B.keep(m, c))
    return out
