"""Floepup (harbor-seal pup, Creatures 2 ocean): every clip authored here (the source bbmodel has none).

Character: a round, sleek, unhurried pup. On land it galumphs (the body humps, the front flippers push together, the
hind flippers lift), sits up propped on its front flippers, sleeps flat on its side, claps its flippers, does the seal
"banana" stretch and scratches its belly. In water it swims with lateral sweeps of the hind flippers, floats upright
"bottling" when idle, rolls and spins. Ability (Lifeguard): a dash with a gulp of air.
Eyes close only in sleep-type clips and death (h_eyes squashes onto the painted lash glyph)."""
from animkit import Clip, wave, pulse, step, keys, decay, posture_set

EYES_SHUT = (1, 0.06, 1)

# ----------------------------------------------------------------------------------------------- poses
SIT = {('body', 'rotation'): (16, 0, 0), ('body', 'position'): (0, 1.4, 0), ('h_head', 'rotation'): (-8, 0, 0),
       ('tail', 'rotation'): (-18, 0, 0), ('tail_tip', 'rotation'): (-6, 0, 0),
       ('flipper_fl', 'rotation'): (-10, 0, -38), ('flipper_fr', 'rotation'): (-10, 0, 38)}
SLEEP = {('body', 'rotation'): (0, 0, 14), ('body', 'position'): (0, -0.2, 0), ('h_head', 'rotation'): (-12, 6, 6),
         ('h_head', 'position'): (0, -0.4, 0), ('tail', 'rotation'): (-4, 10, 0), ('tail_tip', 'rotation'): (0, 8, 0),
         ('flipper_fl', 'rotation'): (0, 10, 26), ('flipper_fr', 'rotation'): (0, -14, -10),
         ('h_eyes', 'scale'): EYES_SHUT}
FLOAT = {('body', 'rotation'): (40, 0, 0), ('body', 'position'): (0, 2.0, 1.0), ('h_head', 'rotation'): (-40, 0, 0),
         ('tail', 'rotation'): (-10, 0, 0), ('flipper_fl', 'rotation'): (0, 30, -10), ('flipper_fr', 'rotation'): (0, -30, 10)}


def hold(c, pose, skip=()):
    for (b, ch), v in pose.items():
        if (b, ch) not in skip:
            c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)
    return c


def add(pose, bone, ch, fn):
    """pose value + fn(t)"""
    base = pose.get((bone, ch), (1, 1, 1) if ch == 'scale' else (0, 0, 0))
    return lambda t: tuple(a + b for a, b in zip(base, fn(t)))


def breathe(c, L, amp=0.12, cycles=1):
    c.scl('body', lambda t: (1 + amp * 0.12 * wave(t, L, cycles), 1 + amp * 0.2 * wave(t, L, cycles), 1), add=True)


def clips(m):
    out = []

    def put(c):
        m.put(c)
        out.append(c)
        return c

    # ------------------------------------------------------------------ standing / locomotion
    L = 4.0
    c = Clip(m, 'idle', L, 'loop')
    c.pos('body', lambda t: (0, 0.12 * wave(t, L, 2), 0))
    c.rot('h_head', lambda t: (3 * wave(t, L, 1, 0.1), 6 * wave(t, L, 1, 0.3), 0))
    c.rot('tail', lambda t: (-3 - 3 * wave(t, L, 2, 0.25), 0, 0))
    c.rot('tail_tip', lambda t: (-4 * wave(t, L, 2, 0.1), 0, 0))
    c.rot('flipper_fl', lambda t: (0, 0, 4 * wave(t, L, 2)))
    c.rot('flipper_fr', lambda t: (0, 0, -4 * wave(t, L, 2, 0.15)))
    c.rot('buoy', lambda t: (0, 0, 3 * wave(t, L, 2, 0.2)))
    put(c)

    def galumph(name, L, amp, bounce):
        c = Clip(m, name, L, 'loop')
        c.rot('body', lambda t: (amp * wave(t, L), 0, 0))
        c.pos('body', lambda t: (0, bounce * max(0.0, wave(t, L, 1, 0.12)), 0))
        c.rot('h_head', lambda t: (-amp * 0.7 * wave(t, L, 1, 0.08), 0, 0))
        c.rot('flipper_fl', lambda t: (28 * wave(t, L, 1, 0.25), 0, -6 + 8 * wave(t, L, 1, 0.5)))
        c.rot('flipper_fr', lambda t: (28 * wave(t, L, 1, 0.25), 0, 6 - 8 * wave(t, L, 1, 0.5)))
        c.rot('tail', lambda t: (-6 - amp * 0.9 * wave(t, L, 1, 0.45), 0, 0))
        c.rot('tail_tip', lambda t: (-8 * wave(t, L, 1, 0.3), 0, 0))
        c.rot('buoy', lambda t: (0, 0, 8 * wave(t, L, 1, 0.3)))
        return put(c)

    galumph('walk', 1.0, 7, 0.7)
    galumph('walk_happy', 0.8, 9, 1.0)
    galumph('run', 0.6, 11, 1.3)

    L = 1.4
    c = Clip(m, 'swim', L, 'loop')
    c.rot('body', lambda t: (0, -4 * wave(t, L), 0))
    c.pos('body', lambda t: (0, 0.3 * wave(t, L, 2), 0))
    c.rot('tail', lambda t: (-6, 18 * wave(t, L, 1, 0.1), 0))
    c.rot('tail_tip', lambda t: (0, 24 * wave(t, L, 1, -0.05), 0))
    c.rot('flipper_hl', lambda t: (0, 6 * wave(t, L, 1, 0.2), 0))
    c.rot('flipper_hr', lambda t: (0, 6 * wave(t, L, 1, 0.2), 0))
    c.rot('flipper_fl', lambda t: (0, 40, 22))
    c.rot('flipper_fr', lambda t: (0, -40, -22))
    c.rot('h_head', lambda t: (4, 3 * wave(t, L), 0))
    put(c)

    L = 3.0
    c = Clip(m, 'idle_swim', L, 'loop')
    hold(c, FLOAT, skip={('body', 'position'), ('flipper_fl', 'rotation'), ('flipper_fr', 'rotation')})
    c.pos('body', add(FLOAT, 'body', 'position', lambda t: (0, 0.4 * wave(t, L), 0)))
    c.rot('flipper_fl', add(FLOAT, 'flipper_fl', 'rotation', lambda t: (0, 10 * wave(t, L, 2), 0)))
    c.rot('flipper_fr', add(FLOAT, 'flipper_fr', 'rotation', lambda t: (0, -10 * wave(t, L, 2), 0)))
    c.rot('tail_tip', lambda t: (8 * wave(t, L, 1, 0.25), 0, 0))
    put(c)

    # ------------------------------------------------------------------ postures
    L = 3.0
    c = Clip(m, 'sit', L, 'loop')
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (2 * wave(t, L), 5 * wave(t, L, 1, 0.3), 0)))
    breathe(c, L)
    put(c)

    L = 4.0
    c = Clip(m, 'sleep', L, 'loop')
    hold(c, SLEEP)
    breathe(c, L, 0.25)
    put(c)

    # ------------------------------------------------------------------ reactions and states
    c = Clip(m, 'hurt', 0.4)
    c.rot('h_head', lambda t: (12 * pulse(t, 0, 0.4), 0, 0))
    c.rot('flipper_fl', lambda t: (0, 0, 18 * pulse(t, 0, 0.35)))
    c.rot('flipper_fr', lambda t: (0, 0, -18 * pulse(t, 0, 0.35)))
    c.rot('tail', lambda t: (-14 * pulse(t, 0, 0.4), 0, 0))
    put(c)

    L = 1.6
    c = Clip(m, 'death', L, 'hold')
    c.rot('body', lambda t: (0, 0, 88 * step(t, 0.1, 1.0)))
    c.pos('body', lambda t: (0, -0.2 * step(t, 0.1, 1.0), 0))
    c.rot('h_head', lambda t: (-10 * step(t, 0.2, 1.1), 0, 8 * step(t, 0.2, 1.1)))
    c.rot('flipper_fl', lambda t: (0, 0, 30 * step(t, 0.4, 1.2)))
    c.rot('tail', lambda t: (-6 * step(t, 0.3, 1.3), 0, 0))
    c.scl('h_eyes', lambda t: (1, 1 - 0.94 * step(t, 0.3, 0.8), 1))
    put(c)

    L = 1.2
    c = Clip(m, 'eat', L)
    c.rot('h_head', lambda t: (-22 * pulse(t, 0, 1.2) + 8 * pulse(t, 0.6, 1.0), 0, 0))
    c.rot('jaw', lambda t: (-14 * (pulse(t, 0.2, 0.45) + pulse(t, 0.55, 0.8) + pulse(t, 0.85, 1.1)), 0, 0))
    put(c)

    def bark(c, t0, t1, up=10):
        c.rot('jaw', lambda t, f=c.tracks.get(('jaw', 'rotation')): tuple(
            a + b for a, b in zip((f(t) if f else (0, 0, 0)), (-20 * pulse(t, t0, t1), 0, 0))))
        c.rot('h_head', lambda t, f=c.tracks.get(('h_head', 'rotation')): tuple(
            a + b for a, b in zip((f(t) if f else (0, 0, 0)), (up * pulse(t, t0 - 0.05, t1 + 0.05), 0, 0))))

    L = 1.6
    c = Clip(m, 'happy', L)
    c.rot('body', lambda t: (0, 8 * wave(t, L, 3) * pulse(t, 0, L), 0))
    c.pos('body', lambda t: (0, 0.9 * pulse(t, 0.1, 0.5) + 0.9 * pulse(t, 0.8, 1.2), 0))
    c.rot('flipper_fl', lambda t: (0, 0, 30 * abs(wave(t, L, 3)) * pulse(t, 0, L)))
    c.rot('flipper_fr', lambda t: (0, 0, -30 * abs(wave(t, L, 3)) * pulse(t, 0, L)))
    c.rot('tail', lambda t: (-18 * pulse(t, 0, L), 12 * wave(t, L, 3), 0))
    bark(c, 0.3, 0.55)
    put(c)

    L = 2.0
    c = Clip(m, 'tame', L)
    c.pos('body', lambda t: (0, 1.0 * pulse(t, 0.2, 0.7), 0))
    c.rot('body', lambda t: (10 * pulse(t, 0.2, 0.9), 0, 0))
    c.rot('flipper_fl', lambda t: (0, -30 * (pulse(t, 0.9, 1.2) + pulse(t, 1.3, 1.6)), -20 * pulse(t, 0.8, 1.7)))
    c.rot('flipper_fr', lambda t: (0, 30 * (pulse(t, 0.9, 1.2) + pulse(t, 1.3, 1.6)), 20 * pulse(t, 0.8, 1.7)))
    c.rot('tail', lambda t: (-20 * pulse(t, 0.1, 1.9), 0, 0))
    bark(c, 0.3, 0.6, 14)
    put(c)

    # Lifeguard: a quick lunge forward with a gulp of air (jaw open, head up), flippers sweeping back
    L = 1.6
    c = Clip(m, 'ability', L)
    c.rot('body', lambda t: (-10 * pulse(t, 0.0, 0.5) + 8 * pulse(t, 0.5, 1.3), 0, 0))
    c.pos('body', lambda t: (0, 0.6 * pulse(t, 0.3, 0.9), -2.5 * pulse(t, 0.1, 0.8)))
    c.rot('h_head', lambda t: (22 * pulse(t, 0.4, 1.4), 0, 0))
    c.rot('jaw', lambda t: (-24 * pulse(t, 0.5, 1.1), 0, 0))
    c.rot('flipper_fl', lambda t: (0, -40 * pulse(t, 0.05, 0.6), 10))
    c.rot('flipper_fr', lambda t: (0, 40 * pulse(t, 0.05, 0.6), -10))
    c.rot('tail', lambda t: (-8, 26 * wave(t, 0.5) * pulse(t, 0, 0.9), 0))
    c.rot('buoy', lambda t: (0, 0, 20 * decay(t, 0.3, 2.5, 3)))
    put(c)

    L = 3.0
    c = Clip(m, 'look_around', L)
    c.rot('h_head', lambda t: (4 * pulse(t, 0, L), keys(t, [(0, 0), (0.6, 32), (1.3, 32), (2.0, -30), (2.6, -30), (3.0, 0)]), 0))
    put(c)

    L = 1.5
    c = Clip(m, 'greet', L)
    c.rot('h_head', lambda t: (16 * pulse(t, 0, L), 0, 8 * wave(t, L, 2) * pulse(t, 0, L)))
    c.rot('tail', lambda t: (-22 * pulse(t, 0, L), 0, 0))
    c.rot('tail_tip', lambda t: (-14 * wave(t, L, 3), 0, 0))
    bark(c, 0.25, 0.5, 6)
    bark(c, 0.6, 0.85, 6)
    put(c)

    L = 1.2
    c = Clip(m, 'notice', L)
    c.rot('h_head', lambda t: (18 * pulse(t, 0, L), 0, 0))
    c.rot('body', lambda t: (6 * pulse(t, 0, L), 0, 0))
    c.pos('body', lambda t: (0, 0.5 * pulse(t, 0, L), 0))
    put(c)

    L = 1.0
    c = Clip(m, 'spawn', L)
    c.scl('body', lambda t: (0.6 + 0.4 * step(t, 0, 0.6), 0.6 + 0.4 * step(t, 0, 0.6), 0.6 + 0.4 * step(t, 0, 0.6)))
    c.rot('h_head', lambda t: (14 * pulse(t, 0.4, 1.0), 0, 0))
    put(c)

    L = 1.8
    c = Clip(m, 'pet', L)
    c.rot('h_head', lambda t: (10 * pulse(t, 0, L), 0, 16 * pulse(t, 0.1, 1.7)))
    c.rot('body', lambda t: (0, 0, 6 * pulse(t, 0.1, 1.7)))
    c.rot('tail_tip', lambda t: (-12 * wave(t, L, 3), 0, 0))
    put(c)

    L = 4.0
    c = Clip(m, 'idle_sleepy', L, 'loop')
    c.rot('h_head', lambda t: (-10 + 3 * wave(t, L), 0, 0))
    c.scl('h_eyes', lambda t: (1, 0.55 + 0.1 * wave(t, L), 1))
    breathe(c, L, 0.2)
    put(c)

    L = 2.0
    c = Clip(m, 'idle_happy', L, 'loop')
    c.pos('body', lambda t: (0, 0.35 * abs(wave(t, L, 2)), 0))
    c.rot('h_head', lambda t: (6, 6 * wave(t, L), 0))
    c.rot('flipper_fl', lambda t: (0, 0, 12 * wave(t, L, 2)))
    c.rot('flipper_fr', lambda t: (0, 0, -12 * wave(t, L, 2)))
    c.rot('tail', lambda t: (-12 - 4 * wave(t, L, 2), 0, 0))
    put(c)

    # ------------------------------------------------------------------ flourishes (land)
    L = 2.4
    c = Clip(m, 'groom', L)
    c.rot('h_head', lambda t: (-6 * pulse(t, 0.2, 2.2), 0, -18 * pulse(t, 0.2, 2.2)))
    c.rot('flipper_fl', lambda t: (-50 * pulse(t, 0.2, 2.2), 0, 44 * pulse(t, 0.2, 2.2) + 10 * wave(t, 0.4) * pulse(t, 0.5, 1.9)))
    c.rot('body', lambda t: (0, 0, -6 * pulse(t, 0.2, 2.2)))
    put(c)

    L = 2.0
    c = Clip(m, 'yawn', L)
    c.rot('h_head', lambda t: (20 * pulse(t, 0.1, 1.9), 0, 0))
    c.rot('jaw', lambda t: (-30 * pulse(t, 0.3, 1.6), 0, 0))
    c.rot('flipper_fl', lambda t: (0, 0, 14 * pulse(t, 0.2, 1.8)))
    c.rot('flipper_fr', lambda t: (0, 0, -14 * pulse(t, 0.2, 1.8)))
    put(c)

    # the seal "banana": head and hind flippers both lifted off the ground, held, released
    L = 2.6
    c = Clip(m, 'stretch', L)
    c.rot('h_head', lambda t: (26 * pulse(t, 0.1, 2.4), 0, 0))
    c.rot('tail', lambda t: (-30 * pulse(t, 0.2, 2.4), 0, 0))
    c.rot('tail_tip', lambda t: (-20 * pulse(t, 0.3, 2.4), 0, 0))
    c.rot('flipper_fl', lambda t: (0, -20 * pulse(t, 0.2, 2.2), 20 * pulse(t, 0.2, 2.2)))
    c.rot('flipper_fr', lambda t: (0, 20 * pulse(t, 0.2, 2.2), -20 * pulse(t, 0.2, 2.2)))
    put(c)

    L = 1.8
    c = Clip(m, 'flipper_clap', L)
    up = lambda t: step(t, 0, 0.35) * (1 - step(t, 1.45, 1.8))
    c.rot('body', lambda t: (14 * up(t), 0, 0))
    c.pos('body', lambda t: (0, 1.0 * up(t), 0))
    c.rot('h_head', lambda t: (-6 * up(t), 0, 0))
    c.rot('tail', lambda t: (-14 * up(t), 0, 0))
    clap = lambda t: abs(wave(t - 0.35, 0.36)) * (step(t, 0.35, 0.45) * (1 - step(t, 1.35, 1.45)))
    c.rot('flipper_fl', lambda t: (-40 * up(t), -40 * up(t) - 20 * clap(t), 30 * up(t)))
    c.rot('flipper_fr', lambda t: (-40 * up(t), 40 * up(t) + 20 * clap(t), -30 * up(t)))
    bark(c, 0.5, 0.75, 8)
    put(c)

    L = 1.4
    c = Clip(m, 'head_tilt', L)
    c.rot('h_head', lambda t: (4 * pulse(t, 0, L), 0, 20 * pulse(t, 0.1, 1.3)))
    put(c)

    L = 2.4
    c = Clip(m, 'belly_scratch', L)
    c.rot('body', lambda t: (0, 0, 22 * pulse(t, 0.1, 2.3)))
    c.rot('flipper_fr', lambda t: (20 * wave(t, 0.4) * pulse(t, 0.5, 1.9), 30 * pulse(t, 0.2, 2.2), 30 * pulse(t, 0.2, 2.2)))
    c.rot('h_head', lambda t: (-4 * pulse(t, 0.1, 2.3), 0, -10 * pulse(t, 0.1, 2.3)))
    put(c)

    L = 2.4
    c = Clip(m, 'roll_over', L)
    c.rot('body', lambda t: (0, 0, keys(t, [(0, 0), (0.6, 180), (1.6, 180), (2.3, 360), (2.4, 360)])))
    c.pos('body', lambda t: (0, 0.3 * pulse(t, 0.0, 0.7) + 0.3 * pulse(t, 1.6, 2.4), 0))
    c.rot('flipper_fl', lambda t: (0, 0, 20 * wave(t, 0.5) * pulse(t, 0.6, 1.6)))
    c.rot('flipper_fr', lambda t: (0, 0, -20 * wave(t, 0.5) * pulse(t, 0.6, 1.6)))
    put(c)

    # ------------------------------------------------------------------ flourishes (water)
    L = 2.0
    c = Clip(m, 'swim_roll', L)
    c.copy('swim')
    c.rot('body', lambda t: (0, 0, 360 * step(t, 0.2, 1.8)), add=True)
    put(c)

    L = 2.2
    c = Clip(m, 'swim_spin', L)
    hold(c, FLOAT, skip={('body', 'rotation')})
    c.rot('body', lambda t: (40, 360 * step(t, 0.2, 2.0), 0))
    c.rot('flipper_fl', lambda t: (0, 30, 30))
    put(c)

    # ------------------------------------------------------------------ sitting / lying variants
    L = 3.0
    c = Clip(m, 'sit_look', L)
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (6 * pulse(t, 0, L), keys(t, [(0, 0), (0.7, 30), (1.5, 30), (2.2, -24), (3.0, 0)]), 0)))
    put(c)

    L = 1.8
    c = Clip(m, 'sit_clap', L)
    hold(c, SIT, skip={('flipper_fl', 'rotation'), ('flipper_fr', 'rotation')})
    clap2 = lambda t: abs(wave(t, 0.45)) * pulse(t, 0.1, 1.7)
    c.rot('flipper_fl', lambda t: (-10 - 30 * pulse(t, 0, L), -30 * pulse(t, 0, L) - 18 * clap2(t), -38 + 50 * pulse(t, 0, L)))
    c.rot('flipper_fr', lambda t: (-10 - 30 * pulse(t, 0, L), 30 * pulse(t, 0, L) + 18 * clap2(t), 38 - 50 * pulse(t, 0, L)))
    put(c)

    L = 3.0
    c = Clip(m, 'sleep_twitch', L)
    hold(c, SLEEP, skip={('flipper_fl', 'rotation'), ('tail_tip', 'rotation')})
    c.rot('flipper_fl', add(SLEEP, 'flipper_fl', 'rotation', lambda t: (0, 0, 10 * pulse(t, 1.0, 1.25) + 8 * pulse(t, 1.4, 1.6))))
    c.rot('tail_tip', add(SLEEP, 'tail_tip', 'rotation', lambda t: (-10 * pulse(t, 1.8, 2.2), 0, 0)))
    breathe(c, L, 0.25)
    put(c)

    return out + posture_set(m)


TAGS = {
    'sit_look': {'p': 'sitting'}, 'sit_clap': {'p': 'sitting'},
    'sleep_twitch': {'p': 'lying'},
    'swim_roll': {'p': 'swimming', 'move': True}, 'swim_spin': {'p': 'swimming'},
    'idle_sleepy': {'p': 'standing'},
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


IDLE = {
    'standing': [
        idle_entry('flipper_clap', 2, cd=25, when='!in_water', boost={'happy': 2, 'owner_near': 1.5}),
        idle_entry('stretch', 1.5, cd=30, when='!in_water'),
        idle_entry('head_tilt', 2, cd=12, boost={'player_near': 2}),
        idle_entry('belly_scratch', 1.5, cd=25, when='!in_water'),
        idle_entry('roll_over', 0.6, cd=60, when='!in_water', boost={'happy': 3}),
        idle_entry('swim_roll', 2, cd=16, when='in_water'),
        idle_entry('swim_spin', 1.5, cd=20, when='in_water'),
    ],
    'sitting': [
        idle_entry('sit_look', 2, cd=14),
        idle_entry('sit_clap', 1, cd=30, boost={'owner_near': 2}),
    ],
    'lying': [
        idle_entry('sleep_twitch', 1, mn=3, mx=3, cd=20),
    ],
}
PROCEDURAL = {'tail_sway': 4, 'breath': 0.012}
