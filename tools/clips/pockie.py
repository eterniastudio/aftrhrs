"""Pockie (pocket shark, the fishbowl pet): every clip authored here (the source bbmodel has none).

Character: a tiny, curious, bouncy shark pup. It is mostly head, so it leads with its blunt snout: it darts, noses
things, chases bubbles, rolls over for fun and wags its whole tail end when happy. Out of water it lives in a glass
fishbowl (bones bowl > bowl_roll (the glass, turned like a wheel), water > water_slosh (the water, pebbles and seagrass,
always level)) that it pops into and leaps out of.

Two clip sets (FishbowlCreature.clipFor picks one; neither ever plays in the other state):
  water  idle, idle_swim, swim, walk, run, sit, sleep (+ variants), reactions; the bowl is keyed to nothing.
  bowl   bowl_* and sleep_bowl*: the fish shrunk to BOWL_SCALE and centered in the bowl, the bowl at full size.
         bowl_roll (the gait) turns the glass one full turn per 0.75 s: at the gait's nominal speed that is exactly the
         ball's circumference (12 px across: 37.7 px per turn), and the client plays it faster / slower with the real
         speed, so the glass rolls like a wheel; the fish stays upright, leans back against the roll and paddles.
  bowl_in / bowl_out are the hero transitions (anticipation, squash and stretch, splash): nose first through the mouth.
Overlay clips never key `body` (it carries the in-bowl scale); in the bowl they key only head and fins (bowl_hurt,
bowl_tail_wiggle), so the base clip keeps its tail curl. Eyes close only in sleep-type clips and death (h_eyes squashes
onto the lash).

Never through the glass: tools/bowl_check.py (run by anim_check.py) poses every bowl clip, overlay, cross-fade and
transition at 60 fps with the renderer's head look (procedural bowl_look_yaw / _pitch) and tail sway, and fails on any
cube within 0.1 px of a glass wall. The fish is nearly as long as the bowl is wide, so turns curl the tail (curl()),
the tail fin is carried upright (FLUKE_U / FLUKE_L) and the mouth is only ever passed nose first, fins tucked (tuck()).
"""
import math

from animkit import Clip, wave, pulse, step, keys, decay, overshoot, posture_set

EYES_SHUT = (1, 0.06, 1)
NIL = (0.01, 0.01, 0.01)
BOWL_SCALE = 0.62
BOWL_POS = (0, 1.0, 0.8)       # the scaled fish centered in the bowl (its body pivot on the bowl's axis, so it can turn in place)
FLUKE_U, FLUKE_L = (-36, 0, 0), (16, 0, 0)    # in-bowl tail fin fold (on top of the rest pose 50 / -40)
DIVE_Z = 1.06                  # body offset that puts the nose-down fish's cross-section on the mouth's axis
FIN_TUCK = 22                  # fins folded against the body (degrees on top of the rest pose)
ROLL_L = 0.75                  # one turn of the glass per gait cycle (see the module doc)

# ----------------------------------------------------------------------------------------------- poses (water)
SIT = {('body', 'position'): (0, -1.4, 0), ('body', 'rotation'): (-4, 0, 0), ('h_head', 'rotation'): (4, 0, 0),
       ('fin_l', 'rotation'): (0, 0, 18), ('fin_r', 'rotation'): (0, 0, -18), ('tail', 'rotation'): (2, 8, 0),
       ('tail_mid', 'rotation'): (0, 6, 0)}
SLEEP = {('body', 'position'): (0, -2.2, 0), ('body', 'rotation'): (-2, 6, 16), ('h_head', 'rotation'): (-4, 0, 0),
         ('fin_l', 'rotation'): (0, 0, 26), ('fin_r', 'rotation'): (0, 0, -6), ('tail', 'rotation'): (0, 16, 0),
         ('tail_mid', 'rotation'): (0, 14, 0), ('h_eyes', 'scale'): EYES_SHUT}


def hold(c, pose, skip=()):
    for (b, ch), v in pose.items():
        if (b, ch) not in skip:
            c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)
    return c


def add(pose, bone, ch, fn):
    base = pose.get((bone, ch), (1, 1, 1) if ch == 'scale' else (0, 0, 0))
    return lambda t: tuple(a + b for a, b in zip(base, fn(t)))


def water(c):
    """every water clip keys the bowl to nothing (so a cross-fade from / to a bowl clip never flashes it)"""
    c.scl('bowl', lambda t: NIL)
    return c


def bowl(c, pos=None, scale=None, slosh=None):
    """every bowl clip: the bowl at full size, the fish in its in-bowl pose (pos / scale offsets as functions of t)"""
    c.scl('bowl', lambda t: (1, 1, 1))
    c.pos('body', lambda t: tuple(a + b for a, b in zip(BOWL_POS, pos(t) if pos else (0, 0, 0))))
    c.scl('body', lambda t: tuple(BOWL_SCALE * k for k in (scale(t) if scale else (1, 1, 1))))
    # the tail fin carried a little more upright in the bowl: the fish is nearly as long as the bowl is wide
    c.rot('fluke_u', lambda t: FLUKE_U)
    c.rot('fluke_l', lambda t: FLUKE_L)
    if slosh:
        c.rot('water_slosh', slosh)
    return c


def swim_tail(c, L, amp, cycles=1, phase=0.0):
    """the S-wave down the tail (tail -> tail_mid -> tail_tip lag) with the flukes trailing"""
    c.rot('tail', lambda t: (0, amp * wave(t, L, cycles, phase), 0))
    c.rot('tail_mid', lambda t: (0, amp * 1.2 * wave(t, L, cycles, phase - 0.12), 0))
    c.rot('tail_tip', lambda t: (0, amp * 1.3 * wave(t, L, cycles, phase - 0.24), 0))


def curl(c, amount):
    """bends the tail into the turn on top of the swim wave (amount(t) in degrees at the root): a fish turning in a bowl
    curls into a C, which also keeps its tail fin off the glass"""
    for b, k in (('tail', 1.0), ('tail_mid', 1.0), ('tail_tip', 0.8)):
        f = c.tracks.get((b, 'rotation'))
        c.rot(b, (lambda f, k: lambda t: tuple(x + y for x, y in zip(f(t) if f else (0, 0, 0), (0, k * amount(t), 0))))(f, k))


def fins(c, L, amp, cycles=1, phase=0.0, base=0):
    c.rot('fin_l', lambda t: (amp * 0.4 * wave(t, L, cycles, phase + 0.25), 0, base + amp * wave(t, L, cycles, phase)))
    c.rot('fin_r', lambda t: (amp * 0.4 * wave(t, L, cycles, phase + 0.25), 0, -base - amp * wave(t, L, cycles, phase)))


def clips(m):
    out = []

    def put(c):
        # baked now, with the values this clip was written with: animkit.merge bakes the returned clips again after
        # clips() has returned, when the lambdas' free names (L, k, sq, ...) hold the last clip's values
        m.put(c)
        baked = m.clips[c.name]
        c.bake = lambda: baked
        out.append(c)
        return c

    # ================================================================== water: locomotion and idles
    L = 3.0
    c = water(Clip(m, 'idle', L, 'loop'))
    c.pos('body', lambda t: (0, 0.35 * wave(t, L, 1), 0.15 * wave(t, L, 1, 0.25)))
    c.rot('body', lambda t: (2 * wave(t, L, 1, 0.1), 3 * wave(t, L, 1, 0.4), 0))
    c.rot('h_head', lambda t: (2 * wave(t, L, 1, 0.3), 4 * wave(t, L, 1, 0.55), 0))
    swim_tail(c, L, 9, 2)
    fins(c, L, 10, 2)
    c.rot('jaw', lambda t: (3 * max(0.0, wave(t, L, 1, 0.6)), 0, 0))
    put(c)

    L = 2.4
    c = water(Clip(m, 'idle_swim', L, 'loop'))
    c.pos('body', lambda t: (0.3 * wave(t, L, 1, 0.25), 0.5 * wave(t, L, 1), 0.3 * wave(t, L, 1)))
    c.rot('body', lambda t: (3 * wave(t, L, 1, 0.2), 5 * wave(t, L, 1, 0.45), 2 * wave(t, L, 1, 0.3)))
    c.rot('h_head', lambda t: (0, 5 * wave(t, L, 1, 0.6), 0))
    swim_tail(c, L, 12, 2)
    fins(c, L, 14, 2)
    put(c)

    def gait(name, L, amp, roll, bob, finamp):
        c = water(Clip(m, name, L, 'loop'))
        c.rot('body', lambda t: (0, -amp * 0.35 * wave(t, L, 1, 0.1), roll * wave(t, L, 1)))
        c.pos('body', lambda t: (0, bob * wave(t, L, 2), 0))
        c.rot('h_head', lambda t: (2, -amp * 0.25 * wave(t, L, 1, 0.05), 0))
        swim_tail(c, L, amp)
        fins(c, L, finamp, 1, 0.25, 12)
        c.rot('dorsal', lambda t: (0, 0, 4 * wave(t, L, 1, -0.2)))
        return put(c)

    gait('swim', 0.7, 22, 3, 0.25, 8)
    gait('walk', 0.9, 16, 2, 0.2, 6)
    gait('run', 0.45, 28, 4, 0.3, 4)

    # ================================================================== water: postures
    L = 4.0
    c = water(Clip(m, 'sit', L, 'loop'))
    hold(c, SIT, skip={('body', 'position'), ('tail', 'rotation'), ('tail_mid', 'rotation'), ('fin_l', 'rotation'), ('fin_r', 'rotation')})
    c.pos('body', add(SIT, 'body', 'position', lambda t: (0, 0.2 * wave(t, L, 1), 0)))
    c.rot('tail', add(SIT, 'tail', 'rotation', lambda t: (0, 6 * wave(t, L, 2), 0)))
    c.rot('tail_mid', add(SIT, 'tail_mid', 'rotation', lambda t: (0, 8 * wave(t, L, 2, -0.15), 0)))
    c.rot('fin_l', add(SIT, 'fin_l', 'rotation', lambda t: (0, 0, 5 * wave(t, L, 2))))
    c.rot('fin_r', add(SIT, 'fin_r', 'rotation', lambda t: (0, 0, -5 * wave(t, L, 2))))
    put(c)

    L = 4.0
    c = water(Clip(m, 'sleep', L, 'loop'))
    hold(c, SLEEP, skip={('body', 'scale'), ('tail_mid', 'rotation')})
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L), 1 + 0.035 * wave(t, L), 1))
    c.rot('tail_mid', add(SLEEP, 'tail_mid', 'rotation', lambda t: (0, 3 * wave(t, L), 0)))
    c.particle(1.0, 'minecraft:bubble', 2, 0.1, 0.02, 0.6)
    put(c)

    # ================================================================== water: reactions
    c = Clip(m, 'hurt', 0.45)               # overlay: head / fins / tail only
    c.rot('h_head', lambda t: (14 * pulse(t, 0, 0.45), 10 * pulse(t, 0, 0.3), 0))
    c.rot('fin_l', lambda t: (0, 0, 30 * pulse(t, 0, 0.4)))
    c.rot('fin_r', lambda t: (0, 0, -30 * pulse(t, 0, 0.4)))
    c.rot('tail', lambda t: (0, 30 * pulse(t, 0, 0.35), 0))
    c.rot('tail_mid', lambda t: (0, 26 * pulse(t, 0.05, 0.4), 0))
    put(c)

    L = 2.0
    c = water(Clip(m, 'death', L, 'hold'))
    c.rot('body', lambda t: (0, 0, 180 * step(t, 0.15, 1.0) + 10 * decay(t, 1.0, 1.5, 3)))
    c.pos('body', lambda t: (0, 1.2 * pulse(t, 0.0, 0.6) - 1.0 * step(t, 0.8, 2.0), 0))
    c.rot('fin_l', lambda t: (0, 0, 40 * step(t, 0.4, 1.2)))
    c.rot('fin_r', lambda t: (0, 0, -40 * step(t, 0.4, 1.2)))
    c.rot('tail', lambda t: (0, 20 * decay(t, 0.0, 2.5, 2.5), 0))
    c.scl('h_eyes', lambda t: (1, 1 - 0.94 * step(t, 0.5, 1.0), 1))
    c.particle(0.3, 'minecraft:bubble', 6, 0.2, 0.03, 0.6)
    put(c)

    # eat: two bites (snap, chew), the whole body lunges into each
    L = 1.3
    c = water(Clip(m, 'eat', L))
    c.pos('body', lambda t: (0, 0, -1.4 * (pulse(t, 0.1, 0.45) + pulse(t, 0.55, 0.9))))
    c.rot('h_head', lambda t: (8 * (pulse(t, 0.1, 0.4) + pulse(t, 0.55, 0.85)) - 6 * pulse(t, 0.9, 1.3), 0, 0))
    c.rot('jaw', lambda t: (-26 * (pulse(t, 0.12, 0.3) + pulse(t, 0.57, 0.75)) - 8 * abs(wave(t - 0.9, 0.2)) * pulse(t, 0.9, 1.3), 0, 0))
    swim_tail(c, L, 14, 3)
    c.particle(0.3, 'minecraft:bubble', 4, 0.15, 0.02, 0.5)
    put(c)

    # happy: a squiggly wiggle and a little upward hop with the whole tail end wagging
    L = 1.8
    c = water(Clip(m, 'happy', L))
    c.pos('body', lambda t: (0, 1.6 * pulse(t, 0.2, 0.75) + 0.8 * pulse(t, 0.9, 1.35), 0))
    c.rot('body', lambda t: (10 * pulse(t, 0.15, 0.7), 10 * wave(t, L, 3) * pulse(t, 0, L), 6 * wave(t, L, 3, 0.25) * pulse(t, 0, L)))
    c.scl('body', lambda t: (1 + 0.08 * pulse(t, 0.05, 0.2), 1 - 0.12 * pulse(t, 0.05, 0.2) + 0.08 * pulse(t, 0.25, 0.5), 1))
    swim_tail(c, 0.3, 30)
    fins(c, 0.45, 26)
    c.rot('jaw', lambda t: (-14 * pulse(t, 0.25, 0.7), 0, 0))
    put(c)

    # pet: it leans its cheek into the hand and wiggles
    L = 2.0
    c = water(Clip(m, 'pet', L))
    c.rot('body', lambda t: (6 * pulse(t, 0, L), 0, 22 * pulse(t, 0.1, 1.8)))
    c.pos('body', lambda t: (0, 0.8 * pulse(t, 0.1, 1.8), 0))
    c.rot('h_head', lambda t: (8 * pulse(t, 0.1, 1.8), 0, 10 * wave(t, 0.5) * pulse(t, 0.4, 1.6)))
    swim_tail(c, 0.4, 20)
    fins(c, 0.5, 16)
    put(c)

    # tame: a full loop-the-loop of joy with a glow burst
    L = 2.2
    c = water(Clip(m, 'tame', L))
    c.rot('body', lambda t: (360 * step(t, 0.35, 1.25), 0, 0))
    c.pos('body', lambda t: (0, 2.5 * pulse(t, 0.3, 1.4), -1.5 * pulse(t, 0.3, 1.4)))
    c.scl('body', lambda t: (1 + 0.1 * pulse(t, 0.1, 0.35) - 0.06 * pulse(t, 0.35, 0.7), 1 - 0.14 * pulse(t, 0.1, 0.35) + 0.1 * pulse(t, 0.35, 0.7), 1))
    swim_tail(c, 0.35, 26)
    fins(c, 0.5, 24)
    c.rot('jaw', lambda t: (-16 * pulse(t, 1.3, 1.9), 0, 0))
    c.particle(1.2, 'minecraft:glow', 10, 0.3, 0.02, 0.5)
    put(c)

    # ability (glow pocket): puffs up, squeezes its pockets, the glow bursts out, tail flick
    L = 1.6
    c = water(Clip(m, 'ability', L))
    c.scl('body', lambda t: (1 + 0.14 * pulse(t, 0.1, 0.55) - 0.08 * pulse(t, 0.55, 0.8), 1 + 0.12 * pulse(t, 0.1, 0.55) - 0.1 * pulse(t, 0.55, 0.8), 1 - 0.06 * pulse(t, 0.1, 0.55)))
    c.rot('fin_l', lambda t: (0, -20 * pulse(t, 0.5, 1.0), 40 * pulse(t, 0.1, 0.6)))
    c.rot('fin_r', lambda t: (0, 20 * pulse(t, 0.5, 1.0), -40 * pulse(t, 0.1, 0.6)))
    c.pos('body', lambda t: (0, 0.6 * pulse(t, 0.55, 1.1), 1.0 * pulse(t, 0.55, 1.0)))
    c.rot('tail', lambda t: (0, 30 * decay(t, 0.6, 2.4, 3), 0))
    c.rot('h_head', lambda t: (10 * pulse(t, 0.5, 1.2), 0, 0))
    c.particle(0.6, 'minecraft:glow', 12, 0.25, 0.04, 0.4)
    put(c)

    L = 2.6
    c = water(Clip(m, 'look_around', L))
    c.rot('h_head', lambda t: (4 * pulse(t, 0, L), keys(t, [(0, 0), (0.5, 26), (1.1, 26), (1.7, -24), (2.2, -24), (2.6, 0)]), 0))
    c.rot('body', lambda t: (0, keys(t, [(0, 0), (0.6, 12), (1.1, 12), (1.8, -12), (2.2, -12), (2.6, 0)]), 0))
    swim_tail(c, L, 8, 2)
    put(c)

    L = 1.6
    c = water(Clip(m, 'greet', L))
    c.pos('body', lambda t: (0, 0.8 * pulse(t, 0.1, 0.6), -2.0 * pulse(t, 0.1, 1.2)))
    c.rot('body', lambda t: (8 * pulse(t, 0.1, 0.6), 0, 10 * wave(t, 0.4) * pulse(t, 0.5, 1.4)))
    swim_tail(c, 0.3, 26)
    c.rot('jaw', lambda t: (-12 * pulse(t, 0.2, 0.5), 0, 0))
    put(c)

    L = 1.0
    c = water(Clip(m, 'notice', L))
    c.rot('h_head', lambda t: (14 * pulse(t, 0, L), 0, 0))
    c.scl('body', lambda t: (1, 1 + 0.08 * pulse(t, 0.05, 0.35), 1))
    c.rot('dorsal', lambda t: (-10 * pulse(t, 0, L), 0, 0))
    put(c)

    L = 1.0
    c = water(Clip(m, 'spawn', L))
    c.scl('body', lambda t: (0.5 + 0.5 * overshoot(t, 0, 0.7),) * 3)
    c.rot('body', lambda t: (0, 0, 20 * decay(t, 0.3, 2, 4)))
    put(c)

    # level up (bond): three rising spirals and a flourish
    L = 2.4
    c = water(Clip(m, 'level_up', L))
    c.rot('body', lambda t: (16 * pulse(t, 0.2, 2.0), 720 * step(t, 0.25, 1.7), 0))
    c.pos('body', lambda t: (0, 3.0 * pulse(t, 0.2, 2.2), 0))
    swim_tail(c, 0.3, 28)
    fins(c, 0.4, 24)
    c.particle(1.6, 'minecraft:glow', 14, 0.35, 0.03, 0.5)
    put(c)

    # ================================================================== water: flourishes (idle pool, in water)
    # dart: a quick zip forward and a glide back (anticipation squash, stretch on the burst)
    L = 1.6
    c = water(Clip(m, 'dart', L))
    c.pos('body', lambda t: (0, 0.3 * pulse(t, 0.3, 0.8), keys(t, [(0, 0), (0.25, 1.0), (0.45, -5.5), (1.1, -4.5), (1.6, 0)])))
    c.scl('body', lambda t: (1 + 0.08 * pulse(t, 0.05, 0.3) - 0.06 * pulse(t, 0.3, 0.55), 1 - 0.08 * pulse(t, 0.05, 0.3), 1 - 0.08 * pulse(t, 0.05, 0.3) + 0.14 * pulse(t, 0.3, 0.55)))
    c.rot('body', lambda t: (-6 * pulse(t, 0.05, 0.3), 0, 0))
    swim_tail(c, 0.25, 34)
    fins(c, L, 6, 1, 0, -10 * 0)
    c.rot('fin_l', lambda t: (0, 0, 30 * pulse(t, 0.3, 0.9)))
    c.rot('fin_r', lambda t: (0, 0, -30 * pulse(t, 0.3, 0.9)))
    c.particle(0.45, 'minecraft:bubble', 4, 0.15, 0.02, 0.4)
    put(c)

    # chase a bubble: watches it rise, follows with its nose, snaps, misses, shakes its head
    L = 2.6
    c = water(Clip(m, 'chase_bubble', L))
    c.particle(0.0, 'minecraft:bubble', 3, 0.05, 0.03, 0.9)
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (0.4, 18), (1.0, 32), (1.25, 20), (1.8, 0), (2.6, 0)]),
                               keys(t, [(0, 0), (0.5, -14), (1.0, -8), (1.6, 10 * 0), (2.6, 0)]),
                               8 * wave(t - 1.6, 0.25) * pulse(t, 1.6, 2.3)))
    c.pos('body', lambda t: (0, 2.2 * pulse(t, 0.5, 1.6), -1.2 * pulse(t, 0.5, 1.4)))
    c.rot('body', lambda t: (14 * pulse(t, 0.4, 1.4), 0, 0))
    c.rot('jaw', lambda t: (-30 * pulse(t, 1.0, 1.2), 0, 0))
    swim_tail(c, 0.4, 20)
    c.sound(1.15, 'minecraft:block.bubble_column.bubble_pop', 0.6, 1.6)
    put(c)

    # tail wiggle (overlay: tail and fins only): a happy shimmy
    L = 1.2
    c = Clip(m, 'tail_wiggle', L)
    c.rot('tail', lambda t: (0, 26 * wave(t, 0.24) * pulse(t, 0, L), 0))
    c.rot('tail_mid', lambda t: (0, 30 * wave(t, 0.24, 1, -0.15) * pulse(t, 0, L), 0))
    c.rot('tail_tip', lambda t: (0, 30 * wave(t, 0.24, 1, -0.3) * pulse(t, 0, L), 0))
    c.rot('fin_l', lambda t: (0, 0, 14 * wave(t, 0.3) * pulse(t, 0, L)))
    c.rot('fin_r', lambda t: (0, 0, -14 * wave(t, 0.3) * pulse(t, 0, L)))
    put(c)

    # barrel roll: rolls a full turn along its length, stretched, with a bubble trail
    L = 1.8
    c = water(Clip(m, 'barrel_roll', L))
    c.rot('body', lambda t: (0, 0, 360 * step(t, 0.35, 1.25)))
    c.pos('body', lambda t: (0, 1.0 * pulse(t, 0.3, 1.4), -1.5 * pulse(t, 0.2, 1.5)))
    c.scl('body', lambda t: (1 - 0.06 * pulse(t, 0.35, 1.25), 1 - 0.06 * pulse(t, 0.35, 1.25) - 0.1 * pulse(t, 0.05, 0.3), 1 + 0.1 * pulse(t, 0.35, 1.25)))
    swim_tail(c, 0.3, 22)
    c.rot('fin_l', lambda t: (0, 0, -20 * pulse(t, 0.3, 1.3)))
    c.rot('fin_r', lambda t: (0, 0, 20 * pulse(t, 0.3, 1.3)))
    c.particle(0.8, 'minecraft:bubble', 5, 0.25, 0.02, 0.5)
    put(c)

    # curious nose bump: creeps up, boop (squash), recoils, shakes it off
    L = 2.4
    c = water(Clip(m, 'nose_bump', L))
    c.pos('body', lambda t: (0, 0, keys(t, [(0, 0), (0.7, -1.6), (0.85, -2.2), (0.95, -1.4), (1.2, 0.8), (1.8, 0.3), (2.4, 0)])))
    c.scl('body', lambda t: (1 + 0.1 * pulse(t, 0.82, 1.02), 1 + 0.06 * pulse(t, 0.82, 1.02), 1 - 0.16 * pulse(t, 0.82, 1.02)))
    c.rot('h_head', lambda t: (-6 * pulse(t, 0.2, 0.85) + 10 * pulse(t, 0.9, 1.3), 18 * wave(t - 1.3, 0.22) * pulse(t, 1.3, 2.0), 0))
    c.rot('jaw', lambda t: (-10 * pulse(t, 1.0, 1.3), 0, 0))
    swim_tail(c, 0.5, 10)
    c.particle(0.9, 'minecraft:bubble', 3, 0.1, 0.02, 0.6)
    put(c)

    # belly-up float: a playful roll onto its back, flippers and tail paddling, then back (loop while it lingers)
    L = 3.0
    c = water(Clip(m, 'belly_up', L, 'loop'))
    c.rot('body', lambda t: (4 * wave(t, L), 0, 180 + 6 * wave(t, L, 1, 0.25)))
    c.pos('body', lambda t: (0, 0.8 + 0.3 * wave(t, L), 0))
    fins(c, L, 22, 3)
    swim_tail(c, L, 10, 2)
    c.rot('jaw', lambda t: (-8 * max(0.0, wave(t, L, 1, 0.3)), 0, 0))
    put(c)

    # happy spin in place (boosted when happy)
    L = 1.6
    c = water(Clip(m, 'happy_spin', L))
    c.rot('body', lambda t: (8 * pulse(t, 0.1, 1.4), 360 * step(t, 0.2, 1.2), 10 * pulse(t, 0.2, 1.2)))
    c.pos('body', lambda t: (0, 0.8 * pulse(t, 0.2, 1.3), 0))
    swim_tail(c, 0.3, 24)
    put(c)

    # ================================================================== water: sitting / lying variants
    L = 3.0
    c = water(Clip(m, 'sit_look', L))
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (4 * pulse(t, 0, L), keys(t, [(0, 0), (0.6, 24), (1.3, 24), (2.0, -22), (2.5, -22), (3.0, 0)]), 0)))
    put(c)

    L = 2.4
    c = water(Clip(m, 'sit_bubbles', L))
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (10 * pulse(t, 0.2, 2.2), 0, 0)))
    c.rot('jaw', lambda t: (-12 * (pulse(t, 0.4, 0.7) + pulse(t, 0.9, 1.2) + pulse(t, 1.4, 1.7)), 0, 0))
    for k in (0.55, 1.05, 1.55):
        c.particle(k, 'minecraft:bubble', 2, 0.05, 0.03, 0.7)
    c.sound(1.05, 'minecraft:block.bubble_column.bubble_pop', 0.4, 1.8)
    put(c)

    L = 4.0
    c = water(Clip(m, 'sleep_dream', L, 'loop'))
    hold(c, SLEEP, skip={('fin_l', 'rotation'), ('tail', 'rotation')})
    c.rot('fin_l', add(SLEEP, 'fin_l', 'rotation', lambda t: (0, 0, 10 * pulse(t, 1.0, 1.25) + 8 * pulse(t, 1.4, 1.6))))
    c.rot('tail', add(SLEEP, 'tail', 'rotation', lambda t: (0, 8 * pulse(t, 2.0, 2.4), 0)))
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L), 1 + 0.035 * wave(t, L), 1))
    put(c)

    # ================================================================== the bowl: the hero transitions
    # The hero transitions. The bowl's mouth (4 px across inside the painted rim) is narrower than the fish is long, so
    # it always goes through it nose first and straight up / down: fins, dorsal and tail fin tucked, stretched thin (the
    # squeeze), and the bowl only grows or shrinks while the fish is clear above its rim. tools/bowl_check.py proves it.
    def tuck(c, amount, bowlness):
        """fins, dorsal and tail fin folded flat along the body (amount(t): 0 = rest, 1 = tucked); bowlness(t) blends the
        tail fin between its water pose (0) and its in-bowl carriage (1)"""
        c.rot('fin_l', lambda t: (0, -55 * amount(t), FIN_TUCK * amount(t)))
        c.rot('fin_r', lambda t: (0, 55 * amount(t), -FIN_TUCK * amount(t)))
        c.rot('dorsal', lambda t: (55 * amount(t), 0, 0))
        c.rot('fluke_u', lambda t: (FLUKE_U[0] * bowlness(t) + 70 * amount(t), 0, 0))
        c.rot('fluke_l', lambda t: (FLUKE_L[0] * bowlness(t) - 60 * amount(t), 0, 0))

    # bowl_in (1.7 s, FishbowlCreature enter_ticks 34): stranded, it flops on its side, slapping its tail; rights itself
    # and crouches (squash); leaps high (stretch, shrinking to its bowl size) and flips nose-down at the top while the
    # bowl pops up out of the ground beneath it; dives straight in through the mouth (thin, fins tucked); levels out with
    # a squash in the water (splash at 0.95 s), the water sloshes and the bowl wobbles, then a happy wag.
    L = 1.7
    c = Clip(m, 'bowl_in', L)
    c.scl('bowl', lambda t: (max(0.01, overshoot(t, 0.70, 0.86, 0.05)),) * 3)
    c.pos('bowl', lambda t: (0, -2.0 * (1 - step(t, 0.70, 0.84)), 0))
    c.rot('bowl_roll', lambda t: (0, 0, 6 * decay(t, 0.98, 2.2, 3.5)))
    c.scl('water', lambda t: (1, max(0.01, step(t, 0.74, 0.9)), 1))
    c.rot('water_slosh', lambda t: (9 * decay(t, 0.98, 1.8, 2.5), 0, 6 * decay(t, 1.02, 1.4, 2.5)))
    fish_y = lambda t: keys(t, [(0, -2.0), (0.42, -2.0), (0.52, -2.6), (0.62, 4.0), (0.76, 12.6), (0.84, 12.9), (0.9, 9.0),
                                (0.98, BOWL_POS[1] - 0.3), (1.08, BOWL_POS[1] + 0.25), (1.3, BOWL_POS[1])], 'smooth')
    fish_z = lambda t: keys(t, [(0, 0), (0.55, 0), (0.8, DIVE_Z), (0.98, DIVE_Z), (1.25, BOWL_POS[2])])
    c.pos('body', lambda t: (0, fish_y(t), fish_z(t)))
    k = lambda t: 1 - (1 - BOWL_SCALE) * step(t, 0.56, 0.78)
    sq = lambda t: pulse(t, 0.8, 1.02)               # the squeeze through the mouth
    c.scl('body', lambda t: (k(t) * (1 + 0.16 * pulse(t, 0.4, 0.55) - 0.16 * sq(t) + 0.12 * pulse(t, 0.98, 1.18)),
                             k(t) * (1 - 0.24 * pulse(t, 0.4, 0.55) + 0.1 * pulse(t, 0.55, 0.75) - 0.16 * sq(t) - 0.16 * pulse(t, 0.98, 1.18)),
                             k(t) * (1 + 0.1 * pulse(t, 0.4, 0.55) + 0.12 * sq(t))))
    c.rot('body', lambda t: (keys(t, [(0, 0), (0.5, 0), (0.6, 34), (0.72, 20), (0.82, -90), (0.97, -90), (1.12, 6), (1.3, 0)]), 0,
                             keys(t, [(0, 0), (0.1, 80), (0.4, 80), (0.52, 0)]) + 8 * wave(t, 0.14) * pulse(t, 0.1, 0.4)))
    c.rot('h_head', lambda t: (-10 * pulse(t, 0.55, 0.75) + 4 * pulse(t, 1.1, 1.5), 0, 0))
    c.rot('tail', lambda t: (0, 34 * wave(t, 0.14) * pulse(t, 0.08, 0.42) + 20 * wave(t, 0.25) * pulse(t, 1.15, 1.7), 0))
    c.rot('tail_mid', lambda t: (0, 30 * wave(t, 0.14, 1, -0.2) * pulse(t, 0.08, 0.42) + 18 * wave(t, 0.25, 1, -0.15) * pulse(t, 1.15, 1.7), 0))
    tuck(c, lambda t: step(t, 0.66, 0.8) * (1 - step(t, 1.0, 1.2)), lambda t: step(t, 0.56, 0.78))
    fl = c.tracks[('fin_l', 'rotation')]
    fr = c.tracks[('fin_r', 'rotation')]
    c.rot('fin_l', lambda t: tuple(a + b for a, b in zip(fl(t), (0, 0, 20 * abs(wave(t, 0.2)) * pulse(t, 0.1, 0.4)))))
    c.rot('fin_r', lambda t: tuple(a + b for a, b in zip(fr(t), (0, 0, -20 * abs(wave(t, 0.2)) * pulse(t, 0.1, 0.4)))))
    c.rot('jaw', lambda t: (-18 * pulse(t, 0.15, 0.4) - 14 * pulse(t, 1.2, 1.55), 0, 0))
    c.sound(0.72, 'minecraft:block.glass.place', 0.5, 1.7)
    c.sound(0.55, 'minecraft:entity.axolotl.splash', 0.35, 1.7)
    c.particle(0.97, 'minecraft:splash', 14, 0.25, 0.08, 0.9)
    c.particle(1.0, 'minecraft:bubble', 6, 0.2, 0.02, 0.5)
    put(c)

    # bowl_out (1.5 s, exit_ticks 30): in deep water: it wriggles and crouches on the bottom of the bowl, tips nose-up,
    # shoots straight up out of the mouth (thin, fins tucked); clear of the rim it flips nose-down and grows back to its
    # full size while the bowl swells a touch and pops away with a chime; it dives into the water with a splash (0.95 s)
    # and shakes itself out.
    L = 1.5
    c = Clip(m, 'bowl_out', L)
    c.scl('bowl', lambda t: (max(0.01, 1 + 0.06 * pulse(t, 0.55, 0.7) - step(t, 0.7, 0.84)),) * 3)
    c.rot('bowl_roll', lambda t: (0, 0, 8 * wave(t, 0.15) * pulse(t, 0.0, 0.36)))
    c.scl('water', lambda t: (1, max(0.01, 1 - step(t, 0.6, 0.8)), 1))
    c.rot('water_slosh', lambda t: (8 * wave(t, 0.3) * pulse(t, 0, 0.45), 0, 0))
    c.pos('body', lambda t: (0, keys(t, [(0, BOWL_POS[1]), (0.22, BOWL_POS[1] - 0.5), (0.4, BOWL_POS[1] + 1.3), (0.5, BOWL_POS[1] + 1.8),
                                        (0.66, 12.4), (0.78, 13.2), (0.86, 11.0), (0.97, -0.8), (1.15, 0.3), (1.5, 0)]),
                             keys(t, [(0, BOWL_POS[2]), (0.45, DIVE_Z), (0.8, DIVE_Z), (1.0, 0)])))
    k = lambda t: BOWL_SCALE + (1 - BOWL_SCALE) * step(t, 0.7, 0.86)
    sq = lambda t: pulse(t, 0.46, 0.7)
    c.scl('body', lambda t: (k(t) * (1 + 0.14 * pulse(t, 0.12, 0.38) - 0.16 * sq(t) + 0.08 * pulse(t, 0.95, 1.12)),
                             k(t) * (1 - 0.18 * pulse(t, 0.12, 0.38) - 0.16 * sq(t) - 0.12 * pulse(t, 0.95, 1.12)),
                             k(t) * (1 + 0.12 * sq(t))))
    c.rot('body', lambda t: (keys(t, [(0, 0), (0.25, -4), (0.46, 90), (0.7, 90), (0.82, -70), (0.97, -60), (1.12, -6), (1.5, 0)]), 0,
                             10 * wave(t, 0.2) * pulse(t, 0.0, 0.3)))
    c.rot('tail', lambda t: (0, 30 * wave(t, 0.2) * pulse(t, 0.0, 0.36) + 22 * wave(t, 0.2) * pulse(t, 1.1, 1.5), 0))
    c.rot('tail_mid', lambda t: (0, 26 * wave(t, 0.2, 1, -0.2) * pulse(t, 0.0, 0.36) + 20 * wave(t, 0.2, 1, -0.2) * pulse(t, 1.1, 1.5), 0))
    tuck(c, lambda t: step(t, 0.3, 0.46) * (1 - step(t, 0.78, 0.95)), lambda t: 1 - step(t, 0.7, 0.86))
    c.rot('h_head', lambda t: (0, 0, 14 * wave(t, 0.2) * pulse(t, 1.1, 1.5)))
    c.sound(0.72, 'minecraft:block.amethyst_block.chime', 0.6, 1.8)
    c.particle(0.6, 'minecraft:splash', 10, 0.3, 0.06, 0.8)
    c.particle(0.97, 'minecraft:bubble', 12, 0.3, 0.03, 0.4)
    put(c)

    # ================================================================== the bowl: gait and idles
    # bowl_roll: the glass turns forward like a wheel (one turn per cycle), a little bump on each of its four
    # shoulders; the water piles to the back; the fish leans back against the roll, paddling hard to keep up.
    L = ROLL_L
    c = Clip(m, 'bowl_roll', L, 'loop')
    bowl(c, pos=lambda t: (0, 0.25 * wave(t, L, 2), -0.1), slosh=lambda t: (8 + 2 * wave(t, L, 2), 0, 2 * wave(t, L, 1)))
    c.rot('bowl_roll', lambda t: (-360 * t / L, 0, 0))
    c.pos('bowl', lambda t: (0, 0.3 * abs(wave(t, L, 2)), 0))
    c.rot('body', lambda t: (8 + 3 * wave(t, L, 2), 0, 3 * wave(t, L, 1)))
    swim_tail(c, L / 2, 26)
    fins(c, L / 2, 22, 1, 0, 10)
    c.rot('h_head', lambda t: (-4, 0, 0))
    put(c)

    L = 3.0
    c = Clip(m, 'bowl_idle', L, 'loop')
    bowl(c, pos=lambda t: (0.3 * wave(t, L, 1, 0.25), 0.4 * wave(t, L), 0.2 * wave(t, L)),
         slosh=lambda t: (1.2 * wave(t, L, 1, 0.1), 0, 1.5 * wave(t, L, 1, 0.35)))
    c.rot('body', lambda t: (2 * wave(t, L, 1, 0.1), 8 * wave(t, L, 1, 0.4), 0))
    swim_tail(c, L, 10, 2)
    fins(c, L, 12, 2)
    c.rot('bowl_roll', lambda t: (0, 0, 0.8 * wave(t, L, 1, 0.3)))
    put(c)

    # nose to the glass (loop): it presses its snout flat against the front pane, tail fluttering, eyes on you
    L = 3.0
    c = Clip(m, 'bowl_nose_glass', L, 'loop')
    bowl(c, pos=lambda t: (0, 0.2 * wave(t, L), -1.3 - 0.15 * wave(t, L, 2)),
         scale=lambda t: (1.04, 1.0, 0.94 + 0.02 * wave(t, L, 2)), slosh=lambda t: (-1.5, 0, 1 * wave(t, L)))
    c.rot('body', lambda t: (3, 4 * wave(t, L), 0))
    c.rot('h_head', lambda t: (4 + 2 * wave(t, L, 2), 0, 0))
    swim_tail(c, 0.5, 18)
    fins(c, 0.6, 14)
    c.rot('jaw', lambda t: (-6 * max(0.0, wave(t, L, 1, 0.2)), 0, 0))
    put(c)

    # laps (loop): swims little circles around the bowl, banking into the turn
    L = 3.2
    c = Clip(m, 'bowl_laps', L, 'loop')
    R = 0.2                     # a tight pirouette around the bowl's middle: the bowl is only a little longer than the fish
    bowl(c, pos=lambda t: (R * math.sin(2 * math.pi * t / L), 0.3 * wave(t, L, 2), R * (math.cos(2 * math.pi * t / L) - 0.5)),
         slosh=lambda t: (2 * math.cos(2 * math.pi * t / L), 0, 2 * math.sin(2 * math.pi * t / L)))
    c.rot('body', lambda t: (0, 360 * t / L, 10))
    swim_tail(c, 0.53, 7)
    curl(c, lambda t: 34)
    fins(c, 0.53, 10, 1, 0, 8)
    put(c)

    # blows a string of bubbles that pop at the surface
    L = 2.2
    c = Clip(m, 'bowl_bubbles', L)
    bowl(c, pos=lambda t: (0, 0.6 * pulse(t, 0.2, 2.0), 0), slosh=lambda t: (0, 0, 0.8 * wave(t, L)))
    c.rot('body', lambda t: (12 * pulse(t, 0.2, 2.0), 0, 0))
    c.rot('jaw', lambda t: (-14 * (pulse(t, 0.4, 0.65) + pulse(t, 0.8, 1.05) + pulse(t, 1.2, 1.45)), 0, 0))
    c.rot('h_head', lambda t: (6 * pulse(t, 0.3, 1.6), 0, 0))
    swim_tail(c, 0.6, 10)
    for k2 in (0.5, 0.9, 1.3):
        c.particle(k2, 'minecraft:bubble', 2, 0.04, 0.03, 0.75)
    c.sound(1.0, 'minecraft:block.bubble_column.bubble_pop', 0.4, 1.9)
    c.sound(1.4, 'minecraft:block.bubble_column.bubble_pop', 0.35, 2.0)
    put(c)

    # peeks out of the top: tips nose-up in the middle of the bowl, rises up into the neck until its snout is just under
    # the rim and its eyes look out over the edge of the glass, turns to look one way and the other, then sinks back and
    # levels out (all of it inside the glass: tools/bowl_check.py)
    L = 2.6
    PEEK_UP, PEEK_YAW = 2.0, 20
    c = Clip(m, 'bowl_peek', L)
    up = lambda t: keys(t, [(0, 0), (0.45, 0.6), (0.85, PEEK_UP), (0.95, PEEK_UP - 0.2), (1.05, PEEK_UP), (1.95, PEEK_UP), (2.3, 0.6), (2.6, 0)])
    bowl(c, pos=lambda t: (0, up(t), -0.17 * step(t, 0.1, 0.55) * (1 - step(t, 2.05, 2.5))),
         slosh=lambda t: (0, 0, 1.2 * wave(t, 0.8) * pulse(t, 0.3, 2.3)))
    c.rot('body', lambda t: (keys(t, [(0, 0), (0.15, 0), (0.55, 86), (2.05, 86), (2.5, 0)]),
                             keys(t, [(0, 0), (1.1, 0), (1.3, PEEK_YAW), (1.5, PEEK_YAW), (1.7, -PEEK_YAW), (1.88, -PEEK_YAW), (2.02, 0), (2.6, 0)]), 0))
    c.rot('h_head', lambda t: (3 * wave(t - 0.9, 0.5) * pulse(t, 0.9, 1.95), 0, 0))
    swim_tail(c, 0.35, 8)
    c.rot('fin_l', lambda t: (0, 0, FIN_TUCK * step(t, 0.1, 0.5) * (1 - step(t, 2.05, 2.45)) + 8 * wave(t, 0.4) * pulse(t, 0.9, 2.0)))
    c.rot('fin_r', lambda t: (0, 0, -FIN_TUCK * step(t, 0.1, 0.5) * (1 - step(t, 2.05, 2.45)) - 8 * wave(t, 0.4) * pulse(t, 0.9, 2.0)))
    c.sound(0.9, 'minecraft:entity.fish.swim', 0.25, 1.9)
    put(c)

    # bumps the glass: swims at the pane, bonk (squash), the whole bowl wobbles with a clink, the water sloshes
    L = 1.8
    c = Clip(m, 'bowl_wobble', L)
    bowl(c, pos=lambda t: (keys(t, [(0, 0), (0.35, 0.8), (0.45, 1.1), (0.6, 0.3), (1.8, 0)]), 0, 0),
         scale=lambda t: (1 - 0.14 * pulse(t, 0.4, 0.58), 1 + 0.08 * pulse(t, 0.4, 0.58), 1),
         slosh=lambda t: (0, 0, -10 * decay(t, 0.45, 1.6, 2.5)))
    c.rot('body', lambda t: (0, -60 * pulse(t, 0.05, 0.6), 0))
    c.rot('bowl_roll', lambda t: (0, 0, -9 * decay(t, 0.45, 2.0, 2.8)))
    c.pos('bowl', lambda t: (0, 0.5 * pulse(t, 0.45, 0.7), 0))
    c.rot('h_head', lambda t: (0, 16 * wave(t - 0.7, 0.2) * pulse(t, 0.7, 1.3), 0))
    swim_tail(c, 0.3, 20)
    c.sound(0.45, 'minecraft:block.glass.hit', 0.5, 1.9)
    put(c)

    L = 2.6
    c = Clip(m, 'bowl_look_around', L)
    bowl(c, pos=lambda t: (0, 0.2 * wave(t, L), 0), slosh=lambda t: (0, 0, 1.5 * wave(t, L)))
    c.rot('body', lambda t: (0, keys(t, [(0, 0), (0.6, 40), (1.1, 40), (1.8, -40), (2.2, -40), (2.6, 0)]), 0))
    swim_tail(c, 0.5, 14)
    fins(c, 0.5, 10)
    put(c)

    # ================================================================== the bowl: postures (held while staying)
    # parked (sit): hovering mid-bowl facing out, slow tail, the glass dead still
    L = 4.0
    c = Clip(m, 'bowl_sit', L, 'loop')
    bowl(c, pos=lambda t: (0, -0.2 + 0.3 * wave(t, L), -0.3), slosh=lambda t: (0.8 * wave(t, L, 1, 0.2), 0, 0.8 * wave(t, L)))
    c.rot('body', lambda t: (0, 5 * wave(t, L, 1, 0.3), 0))
    swim_tail(c, L, 8, 2)
    fins(c, L, 10, 2)
    put(c)

    # parked variant: face pressed to the glass, looking out at you
    L = 3.0
    c = Clip(m, 'bowl_sit_glass', L, 'loop')
    bowl(c, pos=lambda t: (0, -0.2 + 0.15 * wave(t, L), -1.15), scale=lambda t: (1.04, 1.0, 0.94), slosh=lambda t: (-1.0, 0, 0))
    c.rot('h_head', lambda t: (3 + 2 * wave(t, L, 2), 0, 6 * wave(t, L)))
    swim_tail(c, 0.6, 14)
    put(c)

    # parked variant: slow laps
    L = 4.8
    c = Clip(m, 'bowl_sit_laps', L, 'loop')
    bowl(c, pos=lambda t: (R * math.sin(2 * math.pi * t / L), -0.3, R * (math.cos(2 * math.pi * t / L) - 0.5)),
         slosh=lambda t: (1.2 * math.cos(2 * math.pi * t / L), 0, 1.2 * math.sin(2 * math.pi * t / L)))
    c.rot('body', lambda t: (0, 360 * t / L, 6))
    swim_tail(c, 0.8, 9)
    curl(c, lambda t: 34)
    put(c)

    # asleep on the pebbles at the bottom of the bowl, eyes shut, glow dimmed (the renderer dims it), a bubble now
    # and then
    SB = {('body', 'rotation'): (-3, 12, 14), ('h_eyes', 'scale'): EYES_SHUT, ('tail', 'rotation'): (0, 22, 0),
          ('tail_mid', 'rotation'): (0, 18, 0), ('fin_l', 'rotation'): (0, 0, 24), ('fin_r', 'rotation'): (0, 0, -4)}
    L = 4.0
    c = Clip(m, 'sleep_bowl', L, 'loop')
    bowl(c, pos=lambda t: (0, -0.8 + 0.08 * wave(t, L), 0), scale=lambda t: (1 + 0.02 * wave(t, L), 1 + 0.035 * wave(t, L), 1))
    hold(c, SB)
    c.particle(1.5, 'minecraft:bubble', 1, 0.05, 0.02, 0.6)
    put(c)

    L = 4.0
    c = Clip(m, 'sleep_bowl_dream', L, 'loop')
    bowl(c, pos=lambda t: (0, -0.8 + 0.08 * wave(t, L), 0), scale=lambda t: (1 + 0.02 * wave(t, L), 1 + 0.035 * wave(t, L), 1))
    hold(c, SB, skip={('fin_l', 'rotation'), ('tail_mid', 'rotation')})
    c.rot('fin_l', add(SB, 'fin_l', 'rotation', lambda t: (0, 0, 12 * pulse(t, 1.0, 1.25) + 10 * pulse(t, 1.45, 1.7))))
    c.rot('tail_mid', add(SB, 'tail_mid', 'rotation', lambda t: (0, 10 * pulse(t, 2.2, 2.6), 0)))
    put(c)

    # ================================================================== the bowl: reactions
    # overlays in the bowl key head and fins only: the base clip keeps the tail (a spin curls it to clear the glass)
    c = Clip(m, 'bowl_hurt', 0.45)          # overlay: the body keeps its bowl pose, the tail its curl
    c.rot('h_head', lambda t: (10 * pulse(t, 0, 0.45), 8 * pulse(t, 0, 0.3), 0))
    c.rot('fin_l', lambda t: (0, 0, 30 * pulse(t, 0, 0.4)))
    c.rot('fin_r', lambda t: (0, 0, -30 * pulse(t, 0, 0.4)))
    put(c)

    # the tail wiggle's bowl twin: a happy fin flutter (the tail belongs to the base clip in the bowl)
    L = 1.2
    c = Clip(m, 'bowl_tail_wiggle', L)
    c.rot('fin_l', lambda t: (6 * wave(t, 0.3, 1, 0.25) * pulse(t, 0, L), 0, 18 * wave(t, 0.3) * pulse(t, 0, L)))
    c.rot('fin_r', lambda t: (6 * wave(t, 0.3, 1, 0.25) * pulse(t, 0, L), 0, -18 * wave(t, 0.3) * pulse(t, 0, L)))
    put(c)

    # death in the bowl: rolls belly up and floats to the surface, eyes shut; the bowl stays (it is the last thing seen)
    L = 2.0
    c = Clip(m, 'bowl_death', L, 'hold')
    bowl(c, pos=lambda t: (0, 1.4 * step(t, 0.3, 1.6), 0), slosh=lambda t: (0, 0, 4 * decay(t, 0.1, 1.2, 2)))
    c.rot('body', lambda t: (0, 0, 180 * step(t, 0.15, 1.0)))
    c.rot('fin_l', lambda t: (0, 0, 40 * step(t, 0.4, 1.2)))
    c.rot('fin_r', lambda t: (0, 0, -40 * step(t, 0.4, 1.2)))
    c.scl('h_eyes', lambda t: (1, 1 - 0.94 * step(t, 0.5, 1.0), 1))
    put(c)

    L = 1.4
    c = Clip(m, 'bowl_eat', L)
    bowl(c, pos=lambda t: (0, 1.6 * pulse(t, 0.0, 1.3), 0), slosh=lambda t: (0, 0, 1.5 * wave(t, 0.7)))
    c.rot('body', lambda t: (16 * pulse(t, 0.0, 0.9), 0, 0))
    c.rot('jaw', lambda t: (-28 * (pulse(t, 0.3, 0.5) + pulse(t, 0.65, 0.85)), 0, 0))
    swim_tail(c, 0.3, 22)
    c.particle(0.5, 'minecraft:splash', 4, 0.15, 0.04, 0.95)
    put(c)

    L = 1.8
    c = Clip(m, 'bowl_happy', L)
    bowl(c, pos=lambda t: (0, 2.0 * pulse(t, 0.2, 0.75) + 0.8 * pulse(t, 0.9, 1.35), 0),
         scale=lambda t: (1 + 0.1 * pulse(t, 0.05, 0.2), 1 - 0.14 * pulse(t, 0.05, 0.2) + 0.1 * pulse(t, 0.25, 0.5), 1),
         slosh=lambda t: (0, 0, 6 * decay(t, 0.6, 1.6, 2.5)))
    c.rot('body', lambda t: (10 * pulse(t, 0.15, 0.7), 12 * wave(t, L, 3) * pulse(t, 0, L), 0))
    c.pos('bowl', lambda t: (0, 1.0 * pulse(t, 0.25, 0.6), 0))
    c.rot('bowl_roll', lambda t: (0, 0, 6 * decay(t, 0.6, 2.0, 3)))
    swim_tail(c, 0.3, 30)
    fins(c, 0.45, 24)
    c.rot('jaw', lambda t: (-14 * pulse(t, 0.25, 0.7), 0, 0))
    c.sound(0.62, 'minecraft:block.glass.hit', 0.3, 2.0)
    put(c)

    L = 2.0
    c = Clip(m, 'bowl_pet', L)
    bowl(c, pos=lambda t: (0, 0.6 * pulse(t, 0.1, 1.8), -1.4 * pulse(t, 0.1, 1.8)), slosh=lambda t: (-2 * pulse(t, 0.1, 1.8), 0, 0))
    c.rot('body', lambda t: (6 * pulse(t, 0, L), 0, 22 * pulse(t, 0.1, 1.8)))
    c.rot('h_head', lambda t: (8 * pulse(t, 0.1, 1.8), 0, 10 * wave(t, 0.5) * pulse(t, 0.4, 1.6)))
    swim_tail(c, 0.4, 22)
    fins(c, 0.5, 16)
    put(c)

    L = 2.2
    c = Clip(m, 'bowl_tame', L)
    bowl(c, pos=lambda t: (0, 1.2 * pulse(t, 0.3, 1.3), 0), slosh=lambda t: (0, 0, 8 * decay(t, 1.2, 1.5, 2.5)),
         scale=lambda t: (1 + 0.1 * pulse(t, 0.1, 0.3), 1 - 0.14 * pulse(t, 0.1, 0.3) + 0.12 * pulse(t, 0.3, 0.7), 1))
    c.rot('body', lambda t: (0, 360 * step(t, 0.35, 1.2), 10 * pulse(t, 0.35, 1.2)))
    c.pos('bowl', lambda t: (0, 1.4 * pulse(t, 0.3, 0.7) + 0.6 * pulse(t, 1.2, 1.45), 0))
    c.rot('bowl_roll', lambda t: (0, 0, 7 * decay(t, 0.7, 2.0, 3)))
    swim_tail(c, 0.35, 20)
    curl(c, lambda t: 22 * pulse(t, 0.3, 1.3))
    fins(c, 0.5, 24)
    c.particle(1.2, 'minecraft:glow', 10, 0.3, 0.02, 0.5)
    c.sound(0.7, 'minecraft:block.glass.hit', 0.3, 1.9)
    put(c)

    L = 1.6
    c = Clip(m, 'bowl_ability', L)
    bowl(c, scale=lambda t: (1 + 0.14 * pulse(t, 0.1, 0.55) - 0.08 * pulse(t, 0.55, 0.8), 1 + 0.12 * pulse(t, 0.1, 0.55) - 0.1 * pulse(t, 0.55, 0.8), 1),
         pos=lambda t: (0, 0.8 * pulse(t, 0.55, 1.1), 0), slosh=lambda t: (0, 0, 3 * decay(t, 0.6, 1.5, 3)))
    c.rot('fin_l', lambda t: (0, -20 * pulse(t, 0.5, 1.0), 40 * pulse(t, 0.1, 0.6)))
    c.rot('fin_r', lambda t: (0, 20 * pulse(t, 0.5, 1.0), -40 * pulse(t, 0.1, 0.6)))
    c.rot('tail', lambda t: (0, 30 * decay(t, 0.6, 2.4, 3), 0))
    c.rot('body', lambda t: (-10 * pulse(t, 0.5, 1.2), 0, 0))
    c.particle(0.6, 'minecraft:glow', 14, 0.35, 0.04, 0.6)
    put(c)

    L = 2.4
    c = Clip(m, 'bowl_level_up', L)
    bowl(c, pos=lambda t: (0.25 * math.sin(4 * math.pi * step(t, 0.2, 1.8)), 1.2 * pulse(t, 0.2, 2.2), 0.25 * math.cos(4 * math.pi * step(t, 0.2, 1.8)) - 0.25),
         slosh=lambda t: (0, 0, 6 * wave(t, 0.6) * pulse(t, 0.2, 2.2)))
    c.rot('body', lambda t: (10 * pulse(t, 0.2, 2.0), 720 * step(t, 0.2, 1.8), 12 * pulse(t, 0.2, 1.8)))
    c.pos('bowl', lambda t: (0, 1.0 * pulse(t, 1.8, 2.1), 0))
    swim_tail(c, 0.3, 12)
    curl(c, lambda t: 32 * step(t, 0.05, 0.25) * (1 - step(t, 1.85, 2.2)))
    c.particle(1.7, 'minecraft:glow', 14, 0.35, 0.03, 0.6)
    c.sound(1.9, 'minecraft:block.glass.hit', 0.35, 2.0)
    put(c)

    L = 1.6
    c = Clip(m, 'bowl_greet', L)
    bowl(c, pos=lambda t: (0, 0.8 * pulse(t, 0.1, 0.6) + 0.6 * pulse(t, 0.1, 1.3), -1.1 * pulse(t, 0.1, 1.3)), slosh=lambda t: (-3 * pulse(t, 0.1, 1.3), 0, 0))
    c.pos('bowl', lambda t: (0, 0.9 * pulse(t, 0.1, 0.45) + 0.5 * pulse(t, 0.5, 0.8), 0))
    c.rot('body', lambda t: (4 * pulse(t, 0.1, 0.6), 0, 10 * wave(t, 0.4) * pulse(t, 0.5, 1.4)))
    swim_tail(c, 0.3, 26)
    c.rot('jaw', lambda t: (-12 * pulse(t, 0.2, 0.5), 0, 0))
    c.sound(0.45, 'minecraft:block.glass.hit', 0.25, 2.0)
    put(c)

    L = 1.0
    c = Clip(m, 'bowl_notice', L)
    bowl(c, pos=lambda t: (0, 0.4 * pulse(t, 0, L), -0.8 * pulse(t, 0, L)))
    c.rot('h_head', lambda t: (14 * pulse(t, 0, L), 0, 0))
    c.rot('dorsal', lambda t: (-10 * pulse(t, 0, L), 0, 0))
    put(c)

    return out + posture_set(m)


TAGS = {
    # whole-body water flourishes and the belly-up float only while still (in water)
    'belly_up': {'p': 'belly', 'move': False},
    'sit_look': {'p': 'sitting'}, 'sit_bubbles': {'p': 'sitting'},
    'sleep_dream': {'p': 'lying'},
    # the bowl's postures: held while staying, cross-faded (they never go through the water sit / lie transitions)
    'bowl_sit': {'p': 'standing', 'move': False}, 'bowl_sit_glass': {'p': 'standing', 'move': False},
    'bowl_sit_laps': {'p': 'standing', 'move': False},
    'sleep_bowl': {'p': 'standing', 'move': False}, 'sleep_bowl_dream': {'p': 'standing', 'move': False},
    'bowl_death': {'p': 'lying', 'layer': 'base', 'move': False},
    'bowl_hurt': {'layer': 'overlay', 'move': True}, 'bowl_tail_wiggle': {'layer': 'overlay', 'move': True},
    # the gait and the idle loops
    'bowl_roll': {'p': 'standing', 'move': True, 'gait': 'walk'},
    'bowl_idle': {'p': 'standing', 'gait': 'idle'},
    # the transitions: the server holds the creature still for their length (FishbowlCreature: busy, and in water no drift
    # at all), so the only motion left is settling / sinking a few pixels; that must not cancel them (a bowl rolling into
    # a pond is still sinking when it gets out)
    'bowl_in': {'p': 'standing', 'move': True}, 'bowl_out': {'p': 'swimming', 'move': True},
}


def idle_entry(clip, w, mn=None, mx=None, cd=None, when=None, boost=None):
    e = {'clip': clip, 'w': w}
    if mn is not None:
        e['min'], e['max'] = mn, mx
    if cd is not None:
        e['cd'] = cd
    if when:
        e['when'] = when
    if boost:
        e['boost'] = boost
    return e


W = 'mood:idle_swim,!in_bowl'      # water idles (the mood in water is idle_swim)
B = 'mood:bowl_idle,in_bowl'       # bowl idles (FishbowlCreature maps the mood to bowl_idle)
IDLE = {
    'standing': [
        idle_entry('dart', 2, cd=10, when=W, boost={'happy': 1.5}),
        idle_entry('chase_bubble', 2, cd=16, when=W),
        idle_entry('tail_wiggle', 1.5, cd=10, when=W, boost={'owner_near': 1.5}),
        idle_entry('barrel_roll', 1.2, cd=20, when=W, boost={'happy': 2}),
        idle_entry('nose_bump', 1.5, cd=18, when=W, boost={'player_near': 2}),
        idle_entry('belly_up', 0.8, mn=3, mx=5, cd=40, when=W, boost={'happy': 2}),
        idle_entry('happy_spin', 1, cd=20, when=W + ',happy'),
        idle_entry('look_around', 1, cd=16, when=W),
        idle_entry('bowl_nose_glass', 2, mn=3, mx=6, cd=14, when=B, boost={'player_near': 2}),
        idle_entry('bowl_laps', 2, mn=3, mx=6, cd=12, when=B),
        idle_entry('bowl_bubbles', 1.5, cd=14, when=B),
        idle_entry('bowl_peek', 1.2, cd=20, when=B, boost={'owner_near': 1.5}),
        idle_entry('bowl_wobble', 1, cd=22, when=B),
        idle_entry('bowl_look_around', 1, cd=16, when=B),
    ],
    'sitting': [
        idle_entry('sit_look', 2, cd=14, when='!in_bowl'),
        idle_entry('sit_bubbles', 1.5, cd=18, when='!in_bowl'),
        idle_entry('bowl_sit_glass', 2, mn=4, mx=8, cd=14, when='in_bowl', boost={'player_near': 2}),
        idle_entry('bowl_sit_laps', 2, mn=4, mx=8, cd=14, when='in_bowl'),
    ],
    'lying': [
        idle_entry('sleep_dream', 1, mn=4, mx=4, cd=20, when='!in_bowl'),
        idle_entry('sleep_bowl_dream', 1, mn=4, mx=4, cd=20, when='in_bowl'),
    ],
}
PROCEDURAL = {'tail_sway': 5, 'breath': 0.01, 'bank': 1.4, 'bank_max': 24, 'bowl_look_yaw': 22, 'bowl_look_pitch': 12}
