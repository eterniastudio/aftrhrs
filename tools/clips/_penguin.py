"""Shared clip kit for the penguins (Emperor, Rockhopper and their chicks; winter update). Every clip is authored here;
the source bbmodels have none.

Rig (all four): root > body (pivot at the hip centre) > h_head (> beak > jaw, held_fish on adults; h_eyes; crest_l /
crest_r on the rockhopper; tuft on the chicks), flipper_l (+x) / flipper_r, tail, leg_l / leg_r. Values are Blockbench
convention (x+ pitches the front up, z+ raises the +x side, position in px).

Character: the emperor is slow and dignified (small, heavy waddle; the bow-and-trumpet call), the rockhopper bouncy
and brash (it hops with both feet together, its crest plumes bounce, a crest-shaking bray), the chicks clumsy (wobbly
waddle, topple over, beg with flapping flippers). On ice and snow the adults toboggan on their bellies (`slide`); in
water they swim flat and fast with wing-like flipper strokes (`swim`) and float upright-ish at rest (`idle_swim`).
Boat clips: `boat_sit` riding in the back seat, `boat_push` leaning back over the stern paddling with both flippers.

The fish prop (`held_fish`, adults): the game hides the bone by default and shows it for eat / catch / ability; these
clips also scale it in and out at the right moments (it appears in the bill, is swallowed or tossed).
Eyes close only in sleep-type clips (animtags.SLEEPY) and death (h_eyes squashes onto the painted lash glyph)."""
from animkit import Clip, wave, pulse, step, keys, decay, posture_set

EYES_SHUT = (1, 0.06, 1)
HIDDEN = (0.0, 0.0, 0.0)


def hold(c, pose, skip=()):
    for (b, ch), v in pose.items():
        if (b, ch) not in skip and c.m.has_bone(b):
            c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)
    return c


def add(pose, bone, ch, fn):
    """pose value + fn(t)"""
    base = pose.get((bone, ch), (1, 1, 1) if ch == 'scale' else (0, 0, 0))
    return lambda t: tuple(a + b for a, b in zip(base, fn(t)))


def merged(*poses):
    out = {}
    for p in poses:
        out.update(p)
    return out


def side_pair(c, fn_l, fn_r=None):
    """flipper_l gets fn_l(t); flipper_r gets the mirror (y and z negated) unless fn_r is given."""
    c.rot('flipper_l', fn_l)
    c.rot('flipper_r', fn_r or (lambda t: (lambda v: (v[0], -v[1], -v[2]))(fn_l(t))))


def fish(c, t_in, t_out, fade=0.08):
    """held_fish shows between t_in and t_out (scale pops in / out), hidden otherwise."""
    if c.m.has_bone('held_fish'):
        c.scl('held_fish', lambda t: (lambda k: (k, k, k))(step(t, t_in, t_in + fade) * (1 - step(t, t_out - fade, t_out))))


def jaw_calls(c, spans, amp=-22):
    prev = c.tracks.get(('jaw', 'rotation'))
    c.rot('jaw', lambda t: (sum(amp * pulse(t, a, b) for a, b in spans) + (prev(t)[0] if prev else 0), 0, 0))


def crest_follow(c, lag=0.08, gain=0.7, extra=None):
    """the rockhopper's plumes trail the head (and bounce with the body)."""
    if not c.m.has_bone('crest_l'):
        return
    src = c.tracks.get(('h_head', 'rotation'))
    bsrc = c.tracks.get(('body', 'position'))

    def f(sign):
        def g(t):
            ts = t - lag
            ts = ts % c.L if c.loop == 'loop' else max(0.0, ts)
            h = src(ts) if src else (0, 0, 0)
            by = bsrc(ts)[1] if bsrc else 0.0
            by0 = bsrc(t)[1] if bsrc else 0.0
            e = extra(t) if extra else (0, 0, 0)
            # a plume lags behind a nod and flicks up when the body drops (and down when it rises)
            return (-gain * h[0] * 0.5 + 6 * (by - by0) + e[0], 0 + e[1] * sign, e[2] * sign)
        return g
    c.rot('crest_l', f(1))
    c.rot('crest_r', f(-1))


# ================================================================================================ adults

def adult_clips(m, K):
    """K: S (size, emperor 1), lift (belly pose lift px), hop (rockhopper two-footed hop), front (body front depth px),
    tall (body height px), call ('emperor' | 'rockhopper')."""
    S, lift = K['S'], K['lift']
    out = []

    def put(c):
        m.put(c)
        out.append(c)
        return c

    SIT = {('body', 'position'): (0, -1.3 * S, 0.3 * S), ('body', 'rotation'): (12, 0, 0), ('h_head', 'rotation'): (-10, 0, 0),
           ('leg_l', 'rotation'): (-12, 0, 0), ('leg_r', 'rotation'): (-12, 0, 0),
           ('leg_l', 'position'): (0, 1.3 * S, -0.1 * S), ('leg_r', 'position'): (0, 1.3 * S, -0.1 * S),
           ('flipper_l', 'rotation'): (0, 0, 14), ('flipper_r', 'rotation'): (0, 0, -14), ('tail', 'rotation'): (-18, 0, 0)}
    BELLY = {('body', 'rotation'): (-82, 0, 0), ('body', 'position'): (0, lift, 0), ('h_head', 'rotation'): (84, 0, 0),
             ('leg_l', 'rotation'): (74, 0, 0), ('leg_r', 'rotation'): (74, 0, 0),
             ('flipper_l', 'rotation'): (0, 0, 8), ('flipper_r', 'rotation'): (0, 0, -8), ('tail', 'rotation'): (8, 0, 0)}
    SLEEP = merged(BELLY, {('h_head', 'rotation'): (72, 12, 6), ('h_head', 'position'): (0, -0.6 * S, 0),
                           ('flipper_l', 'rotation'): (0, 0, 4), ('flipper_r', 'rotation'): (0, 0, -14),
                           ('h_eyes', 'scale'): EYES_SHUT})
    # in water: flat, centred on the hitbox (the body pivot is at the feet), flippers out to the sides for the stroke
    SWIM = {('body', 'rotation'): (-86, 0, 0), ('body', 'position'): (0, lift + 2.5 * S, 0.45 * K['tall']),
            ('h_head', 'rotation'): (84, 0, 0), ('leg_l', 'rotation'): (80, 0, 0), ('leg_r', 'rotation'): (80, 0, 0),
            ('flipper_l', 'rotation'): (0, 0, 62), ('flipper_r', 'rotation'): (0, 0, -62), ('tail', 'rotation'): (12, 0, 0)}
    FLOAT = {('body', 'rotation'): (-55, 0, 0), ('body', 'position'): (0, lift * 0.6 + 2 * S, 0.3 * K['tall']),
             ('h_head', 'rotation'): (52, 0, 0), ('leg_l', 'rotation'): (60, 0, 0), ('leg_r', 'rotation'): (60, 0, 0),
             ('flipper_l', 'rotation'): (0, 0, 28), ('flipper_r', 'rotation'): (0, 0, -28)}
    BOAT = merged(SIT, {('body', 'position'): (0, -1.6 * S, 0), ('leg_l', 'position'): (0, 1.4 * S, 0.3 * S),
                        ('leg_r', 'position'): (0, 1.4 * S, 0.3 * S), ('leg_l', 'rotation'): (-30, 0, 0),
                        ('leg_r', 'rotation'): (-30, 0, 0)})

    # ------------------------------------------------------------------ standing
    L = 4.0
    c = Clip(m, 'idle', L, 'loop')
    c.rot('body', lambda t: (0, 0, 1.4 * wave(t, L)))
    c.rot('h_head', lambda t: (2 * wave(t, L, 1, 0.2), 6 * wave(t, L, 1, 0.35), -1.2 * wave(t, L)))
    side_pair(c, lambda t: (0, 0, 2 + 2 * wave(t, L, 2, 0.1)))
    c.rot('tail', lambda t: (0, 3 * wave(t, L, 1, 0.3), 0))
    crest_follow(c)
    put(c)

    L = 5.0
    c = Clip(m, 'idle_look', L, 'loop')
    c.rot('body', lambda t: (0, 0, 1.2 * wave(t, L)))
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (0.8, 6), (2.2, 6), (3.0, -4), (4.2, -4), (5.0, 0)]),
                               keys(t, [(0, 0), (0.8, 34), (2.2, 34), (3.0, -30), (4.2, -30), (5.0, 0)]), 0))
    side_pair(c, lambda t: (0, 0, 2))
    crest_follow(c)
    put(c)

    # waddle (emperor) / two-footed hop (rockhopper)
    def waddle(name, L, roll, lean, lift_leg):
        c = Clip(m, name, L, 'loop')
        c.rot('body', lambda t: (lean, 2 * wave(t, L, 1, 0.25), roll * wave(t, L)))
        c.pos('body', lambda t: (0.25 * S * wave(t, L), 0.3 * S * abs(wave(t, L)), 0))
        c.rot('h_head', lambda t: (-lean * 0.6, -2 * wave(t, L, 1, 0.25), -roll * 0.55 * wave(t, L)))
        c.rot('leg_l', lambda t: (22 * wave(t, L, 1, 0.25), 0, 0))
        c.rot('leg_r', lambda t: (-22 * wave(t, L, 1, 0.25), 0, 0))
        c.pos('leg_l', lambda t: (0, lift_leg * S * max(0.0, wave(t, L, 1, 0.0)), 0))
        c.pos('leg_r', lambda t: (0, lift_leg * S * max(0.0, -wave(t, L, 1, 0.0)), 0))
        side_pair(c, lambda t: (0, 0, 14 + 7 * wave(t, L, 1, 0.5)), lambda t: (0, 0, -14 + 7 * wave(t, L, 1, 0.5)))
        c.rot('tail', lambda t: (0, 6 * wave(t, L), 0))
        crest_follow(c)
        return put(c)

    def hops(name, L, height, lean):
        c = Clip(m, name, L, 'loop')
        air = lambda t: max(0.0, wave(t, L, 1, 0.0))     # in the air for the first half of the cycle
        c.pos('body', lambda t: (0, height * S * air(t), 0))
        c.rot('body', lambda t: (lean - 6 * air(t) + 5 * pulse(t, L * 0.5, L), 0, 0))
        c.rot('h_head', lambda t: (-lean * 0.6 + 8 * air(t) - 6 * pulse(t, L * 0.5, L * 0.85), 0, 0))
        for leg in ('leg_l', 'leg_r'):
            c.rot(leg, lambda t: (-18 * pulse(t, 0, L * 0.25) + 26 * pulse(t, L * 0.15, L * 0.5), 0, 0))
        side_pair(c, lambda t: (0, 0, 12 + 34 * air(t)))
        c.rot('tail', lambda t: (-10 * air(t), 0, 0))
        c.scl('body', lambda t: (1 + 0.04 * pulse(t, L * 0.5, L * 0.8), 1 - 0.06 * pulse(t, L * 0.5, L * 0.8), 1))
        crest_follow(c, lag=0.06, gain=0.9)
        return put(c)

    if K.get('hop'):
        hops('walk', 0.6, 1.8, -4)
        hops('walk_happy', 0.5, 2.3, -4)
        hops('run', 0.45, 2.2, -10)
    else:
        waddle('walk', 1.1, 7, 0, 0.6)
        waddle('walk_happy', 0.9, 9, 0, 0.8)
        waddle('run', 0.6, 9, -12, 0.9)

    # belly toboggan: legs kicking behind, flippers pushing now and then, head up looking ahead
    L = 1.2
    c = Clip(m, 'slide', L, 'loop')
    hold(c, BELLY, skip={('leg_l', 'rotation'), ('leg_r', 'rotation'), ('flipper_l', 'rotation'), ('flipper_r', 'rotation'),
                         ('body', 'position'), ('h_head', 'rotation')})
    c.pos('body', add(BELLY, 'body', 'position', lambda t: (0, 0.15 * S * wave(t, L, 2), 0)))
    c.rot('h_head', add(BELLY, 'h_head', 'rotation', lambda t: (4, 4 * wave(t, L), 0)))
    c.rot('leg_l', add(BELLY, 'leg_l', 'rotation', lambda t: (22 * wave(t, L, 2), 0, 0)))
    c.rot('leg_r', add(BELLY, 'leg_r', 'rotation', lambda t: (-22 * wave(t, L, 2), 0, 0)))
    side_pair(c, lambda t: (-24 * pulse(t, 0.1, 0.7), 0, 10 - 6 * pulse(t, 0.1, 0.7)))
    crest_follow(c)
    put(c)

    # ------------------------------------------------------------------ water
    L = 0.9
    c = Clip(m, 'swim', L, 'loop')
    hold(c, SWIM, skip={('body', 'rotation'), ('body', 'position'), ('flipper_l', 'rotation'), ('flipper_r', 'rotation'),
                        ('leg_l', 'rotation'), ('leg_r', 'rotation')})
    c.rot('body', add(SWIM, 'body', 'rotation', lambda t: (5 * wave(t, L, 1, 0.1), 0, 0)))
    c.pos('body', add(SWIM, 'body', 'position', lambda t: (0, 0.6 * S * wave(t, L), 0)))
    side_pair(c, lambda t: (0, 38 * wave(t, L), 62 + 8 * wave(t, L, 1, 0.25)))
    c.rot('leg_l', add(SWIM, 'leg_l', 'rotation', lambda t: (6 * wave(t, L, 1, 0.3), 0, 0)))
    c.rot('leg_r', add(SWIM, 'leg_r', 'rotation', lambda t: (6 * wave(t, L, 1, 0.3), 0, 0)))
    crest_follow(c, gain=0.3, extra=lambda t: (-20, 0, 0))
    put(c)

    L = 3.0
    c = Clip(m, 'idle_swim', L, 'loop')
    hold(c, FLOAT, skip={('body', 'position'), ('leg_l', 'rotation'), ('leg_r', 'rotation'), ('h_head', 'rotation')})
    c.pos('body', add(FLOAT, 'body', 'position', lambda t: (0, 0.35 * S * wave(t, L), 0)))
    c.rot('h_head', add(FLOAT, 'h_head', 'rotation', lambda t: (3 * wave(t, L, 1, 0.2), 14 * wave(t, L, 1, 0.4), 0)))
    c.rot('leg_l', add(FLOAT, 'leg_l', 'rotation', lambda t: (14 * wave(t, L, 2), 0, 0)))
    c.rot('leg_r', add(FLOAT, 'leg_r', 'rotation', lambda t: (-14 * wave(t, L, 2), 0, 0)))
    side_pair(c, lambda t: (0, 10 * wave(t, L, 2, 0.2), 28))
    crest_follow(c)
    put(c)

    L = 1.8
    c = Clip(m, 'swim_roll', L, 'loop')
    c.copy('swim')
    c.rot('body', lambda t: (0, 0, 360 * step(t, 0.15, 1.6)), add=True)
    put(c)

    # ------------------------------------------------------------------ postures
    L = 3.0
    c = Clip(m, 'sit', L, 'loop')
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (2 * wave(t, L), 6 * wave(t, L, 1, 0.3), 0)))
    crest_follow(c)
    put(c)

    L = 4.0
    c = Clip(m, 'sleep', L, 'loop')
    hold(c, SLEEP, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SLEEP, 'h_head', 'rotation', lambda t: (1.5 * wave(t, L), 0, 0)))
    c.scl('body', lambda t: (1 + 0.012 * wave(t, L), 1, 1 + 0.02 * wave(t, L)))
    put(c)

    L = 3.0
    c = Clip(m, 'sit_look', L, 'loop')
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (4 * pulse(t, 0, L), keys(t, [(0, 0), (0.7, 32), (1.5, 32), (2.2, -26), (3.0, 0)]), 0)))
    crest_follow(c)
    put(c)

    # ------------------------------------------------------------------ boat (riding in the back seat)
    L = 3.0
    c = Clip(m, 'boat_sit', L, 'loop')
    hold(c, BOAT, skip={('body', 'rotation'), ('h_head', 'rotation')})
    c.rot('body', add(BOAT, 'body', 'rotation', lambda t: (1.5 * wave(t, L, 1, 0.25), 0, 2.5 * wave(t, L))))
    c.rot('h_head', add(BOAT, 'h_head', 'rotation', lambda t: (3 * wave(t, L, 1, 0.1),
                                                               keys(t, [(0, 0), (0.8, 24), (1.5, 24), (2.3, -18), (3.0, 0)]), -2 * wave(t, L))))
    crest_follow(c)
    put(c)

    # leaning back over the stern, both flippers paddling in turn: a little engine
    L = 0.8
    c = Clip(m, 'boat_push', L, 'loop')
    hold(c, BOAT, skip={('body', 'rotation'), ('h_head', 'rotation'), ('body', 'position')})
    c.rot('body', lambda t: (26 + 3 * wave(t, L, 2), 0, 3 * wave(t, L)))
    c.pos('body', add(BOAT, 'body', 'position', lambda t: (0, 0.25 * S * abs(wave(t, L)), 0.6 * S)))
    c.rot('h_head', lambda t: (-20 + 3 * wave(t, L, 2), 0, -3 * wave(t, L)))
    c.rot('flipper_l', lambda t: (-55 + 45 * wave(t, L), 0, 24))
    c.rot('flipper_r', lambda t: (-55 - 45 * wave(t, L), 0, -24))
    c.rot('tail', lambda t: (-20, 0, 0))
    crest_follow(c)
    put(c)

    # ------------------------------------------------------------------ reactions
    c = Clip(m, 'hurt', 0.4)
    c.rot('h_head', lambda t: (14 * pulse(t, 0, 0.4), 0, 0))
    side_pair(c, lambda t: (0, 0, 30 * pulse(t, 0, 0.35)))
    put(c)

    L = 1.6
    c = Clip(m, 'death', L, 'hold')
    c.rot('body', lambda t: (0, 0, 84 * step(t, 0.1, 0.9)))
    c.pos('body', lambda t: (0, 0.2 * S * step(t, 0.1, 0.9), 0))
    c.rot('h_head', lambda t: (-8 * step(t, 0.3, 1.1), 0, 10 * step(t, 0.3, 1.1)))
    side_pair(c, lambda t: (0, 0, 34 * step(t, 0.5, 1.2)), lambda t: (0, 0, -6 * step(t, 0.5, 1.2)))
    c.rot('leg_l', lambda t: (16 * step(t, 0.4, 1.2), 0, 0))
    c.scl('h_eyes', lambda t: (1, 1 - 0.94 * step(t, 0.4, 0.8), 1))
    put(c)

    # gulps a fish: it appears in the bill, head back, two swallows
    L = 1.4
    c = Clip(m, 'eat', L)
    c.rot('h_head', lambda t: (-14 * pulse(t, 0.0, 0.35) + 38 * pulse(t, 0.35, 1.25), 0, 0))
    c.rot('body', lambda t: (4 * pulse(t, 0.35, 1.25), 0, 0))
    jaw_calls(c, [(0.4, 0.62), (0.7, 0.9), (1.0, 1.15)], -18)
    fish(c, 0.02, 0.85)
    if m.has_bone('held_fish'):
        c.pos('held_fish', lambda t: (0, 0, 1.6 * S * step(t, 0.6, 0.85)))
    side_pair(c, lambda t: (0, 0, 12 * pulse(t, 0.9, 1.35)))
    crest_follow(c)
    put(c)

    # catch: dips down, pops up with a fish and flips it toward the player
    L = 1.7
    c = Clip(m, 'catch', L)
    c.rot('body', lambda t: (-22 * pulse(t, 0.0, 0.7) + 6 * pulse(t, 0.8, 1.3), 0, 0))
    c.rot('h_head', lambda t: (-34 * pulse(t, 0.0, 0.6) + 30 * pulse(t, 0.7, 1.15), 0, 0))
    c.pos('body', lambda t: (0, 0.6 * S * pulse(t, 0.55, 0.95), 0))
    jaw_calls(c, [(0.95, 1.15)], -26)
    fish(c, 0.42, 1.12)
    if m.has_bone('held_fish'):
        c.pos('held_fish', lambda t: (0, 4 * S * step(t, 0.95, 1.12), -3 * S * step(t, 0.95, 1.12)))
        c.rot('held_fish', lambda t: (0, 0, 40 * wave(t, 0.25) * pulse(t, 0.45, 0.9)))
    side_pair(c, lambda t: (0, 0, 10 + 30 * abs(wave(t, 0.3)) * pulse(t, 1.1, 1.7)))
    crest_follow(c)
    put(c)

    # ability (fishing buddy): dives forward, comes back up with a fish and shakes it
    L = 2.2
    c = Clip(m, 'ability', L)
    c.rot('body', lambda t: (-55 * pulse(t, 0.1, 1.15), 0, 0))
    c.pos('body', lambda t: (0, -2.5 * S * pulse(t, 0.3, 1.1), -1.5 * S * pulse(t, 0.1, 1.15)))
    c.rot('h_head', lambda t: (-20 * pulse(t, 0.1, 0.9) + 14 * pulse(t, 1.2, 2.1), 22 * wave(t, 0.3) * pulse(t, 1.3, 1.9), 0))
    side_pair(c, lambda t: (-40 * pulse(t, 0.15, 1.0), 0, 20 + 30 * pulse(t, 0.15, 1.0)))
    fish(c, 1.05, 2.15)
    c.sound(0.45, 'splash', 0.6)
    crest_follow(c)
    put(c)

    L = 1.6
    c = Clip(m, 'happy', L)
    c.pos('body', lambda t: (0, 1.4 * S * pulse(t, 0.15, 0.55) + 1.2 * S * pulse(t, 0.75, 1.15), 0))
    c.rot('h_head', lambda t: (16 * pulse(t, 0.1, 1.4), 0, 0))
    side_pair(c, lambda t: (0, 0, 14 + 34 * abs(wave(t, 0.4)) * pulse(t, 0.05, 1.5)))
    jaw_calls(c, [(0.3, 0.55), (0.85, 1.1)])
    crest_follow(c, gain=0.9)
    put(c)

    L = 2.0
    c = Clip(m, 'tame', L)
    c.rot('h_head', lambda t: (-18 * pulse(t, 0.1, 0.6) + 20 * pulse(t, 0.7, 1.6), 0, 10 * pulse(t, 1.2, 1.9)))
    c.rot('body', lambda t: (-8 * pulse(t, 0.1, 0.6), 0, 0))
    c.pos('body', lambda t: (0, 1.2 * S * pulse(t, 0.8, 1.2), 0))
    side_pair(c, lambda t: (0, 0, 10 + 40 * abs(wave(t, 0.36)) * pulse(t, 0.7, 1.8)))
    jaw_calls(c, [(0.9, 1.15), (1.25, 1.5)])
    crest_follow(c)
    put(c)

    L = 1.6
    c = Clip(m, 'greet', L)
    # a little bow, then flipper up
    c.rot('body', lambda t: (-14 * pulse(t, 0.05, 0.8), 0, 0))
    c.rot('h_head', lambda t: (-22 * pulse(t, 0.05, 0.8) + 12 * pulse(t, 0.9, 1.5), 0, 0))
    c.rot('flipper_l', lambda t: (0, 0, 10 + 60 * pulse(t, 0.8, 1.55) + 10 * wave(t, 0.25) * pulse(t, 0.9, 1.45)))
    c.rot('flipper_r', lambda t: (0, 0, -10))
    jaw_calls(c, [(1.0, 1.2)])
    crest_follow(c)
    put(c)

    L = 1.0
    c = Clip(m, 'notice', L)
    c.rot('h_head', lambda t: (14 * pulse(t, 0, L), 0, 0))
    c.rot('body', lambda t: (4 * pulse(t, 0, L), 0, 0))
    c.scl('body', lambda t: (1, 1 + 0.03 * pulse(t, 0, L), 1))
    side_pair(c, lambda t: (0, 0, 6 + 10 * pulse(t, 0.05, 0.6)))
    crest_follow(c)
    put(c)

    L = 3.0
    c = Clip(m, 'look_around', L)
    c.rot('h_head', lambda t: (4 * pulse(t, 0, L), keys(t, [(0, 0), (0.6, 36), (1.3, 36), (2.0, -32), (2.6, -32), (3.0, 0)]), 0))
    crest_follow(c)
    put(c)

    # spawn (also a chick growing up): pops in, a fluff-shake
    L = 1.1
    c = Clip(m, 'spawn', L)
    c.scl('body', lambda t: (lambda k: (k, k, k))(0.72 + 0.28 * step(t, 0, 0.45) + 0.05 * pulse(t, 0.35, 0.6)))
    c.rot('body', lambda t: (0, 0, 9 * wave(t, 0.18) * pulse(t, 0.55, 1.05)))
    c.rot('h_head', lambda t: (10 * pulse(t, 0.5, 1.1), 0, -6 * wave(t, 0.18) * pulse(t, 0.55, 1.05)))
    side_pair(c, lambda t: (0, 0, 8 + 24 * pulse(t, 0.55, 1.05)))
    crest_follow(c)
    put(c)

    L = 1.8
    c = Clip(m, 'pet', L)
    c.rot('h_head', lambda t: (8 * pulse(t, 0, L), 0, 16 * pulse(t, 0.1, 1.7)))
    c.rot('body', lambda t: (0, 0, 5 * pulse(t, 0.1, 1.7)))
    side_pair(c, lambda t: (0, 0, 6 + 14 * pulse(t, 0.2, 1.6)))
    crest_follow(c)
    put(c)

    L = 4.0
    c = Clip(m, 'idle_sleepy', L, 'loop')
    c.rot('h_head', lambda t: (-12 + 3 * wave(t, L), 0, 2 * wave(t, L, 1, 0.3)))
    c.rot('body', lambda t: (0, 0, 2 * wave(t, L)))
    c.scl('h_eyes', lambda t: (1, 0.55 + 0.1 * wave(t, L), 1))
    side_pair(c, lambda t: (0, 0, 0))
    crest_follow(c)
    put(c)

    L = 2.0
    c = Clip(m, 'idle_happy', L, 'loop')
    c.rot('body', lambda t: (0, 0, 4 * wave(t, L)))
    c.pos('body', lambda t: (0, 0.35 * S * abs(wave(t, L)), 0))
    c.rot('h_head', lambda t: (6, 6 * wave(t, L), -3 * wave(t, L)))
    side_pair(c, lambda t: (0, 0, 14 + 10 * abs(wave(t, L, 2))))
    crest_follow(c)
    put(c)

    # begging for the fish in its owner's hand: looks up, flippers flapping, little bounces, bill opening
    L = 1.6
    c = Clip(m, 'idle_beg', L, 'loop')
    c.rot('h_head', lambda t: (26 + 3 * wave(t, L, 2), 5 * wave(t, L), 0))
    c.rot('body', lambda t: (5, 0, 3 * wave(t, L)))
    c.pos('body', lambda t: (0, 0.35 * S * abs(wave(t, L, 2)), 0))
    c.rot('flipper_l', lambda t: (0, 0, 22 + 22 * wave(t, L, 4)))
    c.rot('flipper_r', lambda t: (0, 0, -22 + 22 * wave(t, L, 4, 0.5)))
    jaw_calls(c, [(0.2, 0.45), (1.0, 1.25)], -16)
    crest_follow(c)
    put(c)

    L = 1.2
    c = Clip(m, 'shake', L)
    c.rot('body', lambda t: (0, 10 * wave(t, 0.16) * pulse(t, 0.05, 1.0), 6 * wave(t, 0.16, 1, 0.25) * pulse(t, 0.05, 1.0)))
    c.rot('h_head', lambda t: (0, -18 * wave(t, 0.16, 1, 0.1) * pulse(t, 0.0, 0.9), 0))
    side_pair(c, lambda t: (0, 0, 16 + 22 * abs(wave(t, 0.16)) * pulse(t, 0.05, 1.1)))
    c.particle(0.3, 'minecraft:splash', 10, 0.5, 0.1, 0.6)
    crest_follow(c, gain=1.0)
    put(c)

    # preening under a flipper
    L = 2.6
    c = Clip(m, 'preen', L)
    up = lambda t: pulse(t, 0.1, 2.5)
    c.rot('h_head', lambda t: (-26 * up(t), 48 * up(t), 6 * up(t)))
    c.rot('flipper_l', lambda t: (0, 0, 8 + 42 * up(t)))
    c.rot('body', lambda t: (0, 0, -4 * up(t)))
    c.rot('jaw', lambda t: (-8 * abs(wave(t, 0.3)) * pulse(t, 0.5, 2.1), 0, 0))
    crest_follow(c)
    put(c)

    if K['call'] == 'emperor':
        # the emperor's call: bows its head to its chest, then raises its bill skyward and trumpets twice
        L = 3.2
        c = Clip(m, 'call', L)
        bow = lambda t: pulse(t, 0.1, 1.3)
        sky = lambda t: step(t, 1.2, 1.7) * (1 - step(t, 2.7, 3.15))
        c.rot('h_head', lambda t: (-46 * bow(t) + 58 * sky(t), 0, 0))
        c.rot('body', lambda t: (-6 * bow(t) + 7 * sky(t), 0, 0))
        c.scl('body', lambda t: (1, 1 + 0.03 * sky(t), 1))
        side_pair(c, lambda t: (-14 * sky(t), 0, 6 + 8 * sky(t)))
        jaw_calls(c, [(1.75, 2.15), (2.25, 2.65)], -24)
        c.sound(1.8, 'ambient')
    else:
        # the rockhopper's bray: head thrust forward, bill wide, crest shaking, flippers out
        L = 2.2
        c = Clip(m, 'call', L)
        thrust = lambda t: step(t, 0.2, 0.5) * (1 - step(t, 1.7, 2.1))
        c.rot('h_head', lambda t: (18 * thrust(t) + 4 * wave(t, 0.12) * thrust(t), 10 * wave(t, 0.5) * thrust(t), 0))
        c.rot('body', lambda t: (-8 * thrust(t), 0, 0))
        side_pair(c, lambda t: (-10 * thrust(t), 0, 10 + 36 * thrust(t)))
        jaw_calls(c, [(0.5, 0.85), (0.95, 1.3), (1.4, 1.7)], -28)
        c.sound(0.55, 'ambient')
    crest_follow(c, gain=1.0, extra=(lambda t: (0, 18 * wave(t, 0.14) * step(t, 0.3, 0.5) * (1 - step(t, 1.6, 1.9)), 0))
                 if K['call'] != 'emperor' else None)
    put(c)

    c = Clip(m, 'flap', 1.0)
    side_pair(c, lambda t: (0, 0, 10 + 44 * abs(wave(t, 0.33)) * pulse(t, 0.0, 1.0)))
    put(c)

    L = 1.4
    c = Clip(m, 'head_tilt', L)
    c.rot('h_head', lambda t: (4 * pulse(t, 0, L), 0, 22 * pulse(t, 0.1, 1.3)))
    crest_follow(c)
    put(c)

    L = 2.6
    c = Clip(m, 'stretch', L)
    s_ = lambda t: pulse(t, 0.1, 2.5)
    c.rot('h_head', lambda t: (24 * s_(t), 0, 0))
    c.scl('body', lambda t: (1, 1 + 0.05 * s_(t), 1))
    side_pair(c, lambda t: (36 * s_(t), 0, 30 * s_(t)))
    c.rot('jaw', lambda t: (-20 * pulse(t, 0.9, 1.9), 0, 0))
    crest_follow(c)
    put(c)

    return out + posture_set(m)


def adult_tags():
    t = {
        'slide': {'p': 'standing', 'move': True, 'gait': 'walk', 'loop': 'loop'},
        'boat_sit': {'p': 'standing', 'move': True, 'loop': 'loop'},
        'boat_push': {'p': 'standing', 'move': True, 'loop': 'loop'},
        'swim_roll': {'p': 'swimming', 'move': True},
        'idle_look': {'p': 'standing'}, 'idle_beg': {'p': 'standing'}, 'idle_sleepy': {'p': 'standing'},
        'idle_happy': {'p': 'standing'}, 'sit_look': {'p': 'sitting'}, 'sleep': {'p': 'lying'},
        'catch': {'p': 'standing', 'layer': 'base'}, 'ability': {'p': 'standing'}, 'eat': {'p': 'standing'},
        'preen': {'p': 'standing'}, 'call': {'p': 'standing'}, 'stretch': {'p': 'standing'},
        'shake': {'p': 'standing'}, 'happy': {'p': 'standing'}, 'tame': {'p': 'standing'}, 'greet': {'p': 'standing'},
    }
    return t


def adult_idle():
    return {
        'standing': [
            {'clip': 'idle_look', 'w': 3, 'min': 5, 'max': 10},
            {'clip': 'preen', 'w': 2, 'cd': 20, 'when': '!in_water'},
            {'clip': 'call', 'w': 1.2, 'cd': 40, 'when': '!in_water', 'boost': {'herd_near': 1.5, 'dawn': 2}},
            {'clip': 'flap', 'w': 1.5, 'cd': 14, 'when': '!in_water'},
            {'clip': 'head_tilt', 'w': 1.5, 'cd': 12, 'boost': {'player_near': 2}},
            {'clip': 'stretch', 'w': 1, 'cd': 30, 'when': '!in_water'},
            {'clip': 'swim_roll', 'w': 2, 'cd': 14, 'when': 'in_water'},
        ],
        'sitting': [
            {'clip': 'sit_look', 'w': 2, 'cd': 14},
        ],
    }


# ================================================================================================ chicks

def chick_clips(m, K):
    """K: S (size), lift (belly lift px), tall (body height px)."""
    S, lift = K['S'], K['lift']
    out = []

    def put(c):
        m.put(c)
        out.append(c)
        return c

    SIT = {('body', 'position'): (0, -0.6 * S, 0), ('body', 'rotation'): (8, 0, 0), ('h_head', 'rotation'): (-4, 0, 0),
           ('leg_l', 'position'): (0, 0.6 * S, 0.0), ('leg_r', 'position'): (0, 0.6 * S, 0.0),
           ('leg_l', 'rotation'): (-30, 0, 8), ('leg_r', 'rotation'): (-30, 0, -8),
           ('flipper_l', 'rotation'): (0, 0, 22), ('flipper_r', 'rotation'): (0, 0, -22)}
    SLEEP = {('body', 'rotation'): (-78, 0, 0), ('body', 'position'): (0, lift, 0),
             ('h_head', 'rotation'): (70, 10, 8), ('h_head', 'position'): (0, -0.3 * S, 0),
             ('leg_l', 'rotation'): (70, 0, 0), ('leg_r', 'rotation'): (70, 0, 0),
             ('flipper_l', 'rotation'): (0, 0, 10), ('flipper_r', 'rotation'): (0, 0, -10),
             ('h_eyes', 'scale'): EYES_SHUT}
    PADDLE = {('body', 'rotation'): (-30, 0, 0), ('body', 'position'): (0, 1.2 * S, 0.2 * K['tall']),
              ('h_head', 'rotation'): (26, 0, 0), ('flipper_l', 'rotation'): (0, 0, 40), ('flipper_r', 'rotation'): (0, 0, -40)}

    L = 3.0
    c = Clip(m, 'idle', L, 'loop')
    c.rot('body', lambda t: (0, 0, 2 * wave(t, L)))
    c.rot('h_head', lambda t: (3 * wave(t, L, 1, 0.2), 8 * wave(t, L, 1, 0.35), -2 * wave(t, L)))
    side_pair(c, lambda t: (0, 0, 6 + 3 * wave(t, L, 2, 0.1)))
    c.rot('tuft', lambda t: (4 * wave(t, L, 2, 0.3), 0, 0))
    put(c)

    def waddle(name, L, roll, lean):
        c = Clip(m, name, L, 'loop')
        c.rot('body', lambda t: (lean, 4 * wave(t, L, 1, 0.25), roll * wave(t, L)))
        c.pos('body', lambda t: (0.2 * S * wave(t, L), 0.35 * S * abs(wave(t, L)), 0))
        c.rot('h_head', lambda t: (-lean * 0.5, -3 * wave(t, L, 1, 0.25), -roll * 0.4 * wave(t, L)))
        c.rot('leg_l', lambda t: (28 * wave(t, L, 1, 0.25), 0, 0))
        c.rot('leg_r', lambda t: (-28 * wave(t, L, 1, 0.25), 0, 0))
        c.pos('leg_l', lambda t: (0, 0.6 * S * max(0.0, wave(t, L)), 0))
        c.pos('leg_r', lambda t: (0, 0.6 * S * max(0.0, -wave(t, L)), 0))
        side_pair(c, lambda t: (0, 0, 22 + 10 * wave(t, L, 1, 0.5)), lambda t: (0, 0, -22 + 10 * wave(t, L, 1, 0.5)))
        c.rot('tuft', lambda t: (8 * wave(t, L, 2, 0.2), 0, 6 * wave(t, L)))
        return put(c)

    waddle('walk', 0.7, 10, 0)
    waddle('walk_happy', 0.6, 12, 0)
    waddle('run', 0.42, 12, -10)

    L = 0.8
    c = Clip(m, 'swim', L, 'loop')
    hold(c, PADDLE, skip={('body', 'position'), ('flipper_l', 'rotation'), ('flipper_r', 'rotation')})
    c.pos('body', add(PADDLE, 'body', 'position', lambda t: (0, 0.3 * S * wave(t, L, 2), 0)))
    c.rot('leg_l', lambda t: (50 + 30 * wave(t, L), 0, 0))
    c.rot('leg_r', lambda t: (50 - 30 * wave(t, L), 0, 0))
    side_pair(c, lambda t: (0, 20 * wave(t, L, 2), 40 + 16 * wave(t, L, 2)))
    put(c)

    L = 2.4
    c = Clip(m, 'idle_swim', L, 'loop')
    hold(c, PADDLE, skip={('body', 'position'), ('h_head', 'rotation')})
    c.pos('body', add(PADDLE, 'body', 'position', lambda t: (0, 0.3 * S * wave(t, L), 0)))
    c.rot('h_head', add(PADDLE, 'h_head', 'rotation', lambda t: (3 * wave(t, L), 12 * wave(t, L, 1, 0.3), 0)))
    c.rot('leg_l', lambda t: (40 + 16 * wave(t, L, 2), 0, 0))
    c.rot('leg_r', lambda t: (40 - 16 * wave(t, L, 2), 0, 0))
    put(c)

    L = 3.0
    c = Clip(m, 'sit', L, 'loop')
    hold(c, SIT, skip={('h_head', 'rotation')})
    c.rot('h_head', add(SIT, 'h_head', 'rotation', lambda t: (2 * wave(t, L), 8 * wave(t, L, 1, 0.3), 0)))
    put(c)

    L = 4.0
    c = Clip(m, 'sleep', L, 'loop')
    hold(c, SLEEP)
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L), 1, 1 + 0.03 * wave(t, L)))
    put(c)

    c = Clip(m, 'hurt', 0.4)
    c.rot('h_head', lambda t: (16 * pulse(t, 0, 0.4), 0, 0))
    side_pair(c, lambda t: (0, 0, 34 * pulse(t, 0, 0.35)))
    put(c)

    L = 1.4
    c = Clip(m, 'death', L, 'hold')
    c.rot('body', lambda t: (0, 0, 86 * step(t, 0.1, 0.8)))
    c.rot('h_head', lambda t: (-8 * step(t, 0.3, 1.0), 0, 8 * step(t, 0.3, 1.0)))
    side_pair(c, lambda t: (0, 0, 30 * step(t, 0.4, 1.1)), lambda t: (0, 0, -6 * step(t, 0.4, 1.1)))
    c.scl('h_eyes', lambda t: (1, 1 - 0.94 * step(t, 0.3, 0.7), 1))
    put(c)

    L = 1.3
    c = Clip(m, 'eat', L)
    c.rot('h_head', lambda t: (-16 * pulse(t, 0, 0.35) + 34 * pulse(t, 0.35, 1.2), 0, 0))
    jaw_calls(c, [(0.4, 0.6), (0.7, 0.9)], -20)
    side_pair(c, lambda t: (0, 0, 8 + 30 * abs(wave(t, 0.3)) * pulse(t, 0.8, 1.3)))
    put(c)

    L = 1.5
    c = Clip(m, 'happy', L)
    c.pos('body', lambda t: (0, 1.1 * S * pulse(t, 0.1, 0.5) + 1.1 * S * pulse(t, 0.7, 1.1), 0))
    c.rot('h_head', lambda t: (14 * pulse(t, 0.05, 1.3), 0, 0))
    side_pair(c, lambda t: (0, 0, 16 + 44 * abs(wave(t, 0.3)) * pulse(t, 0.0, 1.4)))
    jaw_calls(c, [(0.25, 0.45), (0.85, 1.05)])
    c.rot('tuft', lambda t: (16 * wave(t, 0.4) * pulse(t, 0, L), 0, 0))
    put(c)

    L = 2.0
    c = Clip(m, 'tame', L)
    c.rot('h_head', lambda t: (24 * pulse(t, 0.2, 1.8), 0, 12 * wave(t, 0.8) * pulse(t, 0.4, 1.8)))
    c.pos('body', lambda t: (0, 1.0 * S * pulse(t, 0.5, 0.9) + 0.8 * S * pulse(t, 1.1, 1.45), 0))
    side_pair(c, lambda t: (0, 0, 16 + 50 * abs(wave(t, 0.3)) * pulse(t, 0.3, 1.9)))
    jaw_calls(c, [(0.5, 0.7), (1.1, 1.3)])
    put(c)

    L = 1.4
    c = Clip(m, 'greet', L)
    c.rot('h_head', lambda t: (18 * pulse(t, 0, L), 0, 10 * wave(t, 0.7) * pulse(t, 0, L)))
    side_pair(c, lambda t: (0, 0, 16 + 40 * abs(wave(t, 0.28)) * pulse(t, 0.1, 1.3)))
    jaw_calls(c, [(0.3, 0.5), (0.7, 0.9)])
    put(c)

    L = 1.0
    c = Clip(m, 'notice', L)
    c.rot('h_head', lambda t: (16 * pulse(t, 0, L), 0, 0))
    c.scl('body', lambda t: (1, 1 + 0.04 * pulse(t, 0, L), 1))
    side_pair(c, lambda t: (0, 0, 8 + 16 * pulse(t, 0.05, 0.6)))
    put(c)

    L = 3.0
    c = Clip(m, 'look_around', L)
    c.rot('h_head', lambda t: (4 * pulse(t, 0, L), keys(t, [(0, 0), (0.6, 36), (1.3, 36), (2.0, -32), (2.6, -32), (3.0, 0)]), 0))
    put(c)

    L = 1.0
    c = Clip(m, 'spawn', L)
    c.scl('body', lambda t: (lambda k: (k, k, k))(0.6 + 0.4 * step(t, 0, 0.5) + 0.06 * pulse(t, 0.4, 0.7)))
    c.rot('body', lambda t: (0, 0, 10 * wave(t, 0.2) * pulse(t, 0.5, 1.0)))
    put(c)

    L = 1.8
    c = Clip(m, 'pet', L)
    c.rot('h_head', lambda t: (10 * pulse(t, 0, L), 0, 18 * pulse(t, 0.1, 1.7)))
    c.rot('body', lambda t: (0, 0, 7 * pulse(t, 0.1, 1.7)))
    c.scl('body', lambda t: (1 + 0.04 * pulse(t, 0.2, 1.6), 1 + 0.03 * pulse(t, 0.2, 1.6), 1 + 0.04 * pulse(t, 0.2, 1.6)))
    put(c)

    L = 4.0
    c = Clip(m, 'idle_sleepy', L, 'loop')
    c.rot('h_head', lambda t: (-14 + 6 * pulse(t, 2.6, 3.2), 0, 3 * wave(t, L)))
    c.rot('body', lambda t: (0, 0, 3 * wave(t, L)))
    c.scl('h_eyes', lambda t: (1, 0.5 + 0.12 * wave(t, L), 1))
    put(c)

    L = 1.6
    c = Clip(m, 'idle_happy', L, 'loop')
    c.rot('body', lambda t: (0, 0, 6 * wave(t, L)))
    c.pos('body', lambda t: (0, 0.4 * S * abs(wave(t, L)), 0))
    c.rot('h_head', lambda t: (8, 8 * wave(t, L), -4 * wave(t, L)))
    side_pair(c, lambda t: (0, 0, 18 + 12 * abs(wave(t, L, 2))))
    put(c)

    # begging: head straight up, flippers flapping hard, bill open peeping, bouncing on its feet
    L = 1.2
    c = Clip(m, 'idle_beg', L, 'loop')
    c.rot('h_head', lambda t: (32 + 4 * wave(t, L, 2), 6 * wave(t, L), 0))
    c.rot('body', lambda t: (6, 0, 4 * wave(t, L)))
    c.pos('body', lambda t: (0, 0.4 * S * abs(wave(t, L, 2)), 0))
    c.rot('flipper_l', lambda t: (0, 0, 30 + 26 * wave(t, L, 4)))
    c.rot('flipper_r', lambda t: (0, 0, -30 + 26 * wave(t, L, 4, 0.5)))
    jaw_calls(c, [(0.1, 0.3), (0.4, 0.6), (0.75, 0.95)], -24)
    put(c)

    L = 1.1
    c = Clip(m, 'shake', L)
    c.rot('body', lambda t: (0, 12 * wave(t, 0.15) * pulse(t, 0.05, 1.0), 9 * wave(t, 0.15, 1, 0.25) * pulse(t, 0.05, 1.0)))
    c.scl('body', lambda t: (1 + 0.05 * pulse(t, 0.05, 1.0), 1, 1 + 0.05 * pulse(t, 0.05, 1.0)))
    side_pair(c, lambda t: (0, 0, 20 + 26 * abs(wave(t, 0.15)) * pulse(t, 0.05, 1.05)))
    c.rot('tuft', lambda t: (20 * wave(t, 0.15) * pulse(t, 0, 1.1), 0, 0))
    c.particle(0.3, 'minecraft:splash', 8, 0.4, 0.1, 0.5)
    put(c)

    # wobble: overbalances, topples onto its side, kicks, rocks back up
    L = 2.6
    c = Clip(m, 'wobble', L)
    tip = lambda t: keys(t, [(0, 0), (0.3, -8), (0.55, 12), (0.9, 74), (1.6, 74), (2.1, -10), (2.35, 4), (2.6, 0)])
    c.rot('body', lambda t: (0, 0, tip(t)))
    c.pos('body', lambda t: (0.8 * S * pulse(t, 0.6, 2.1), 0.3 * S * pulse(t, 0.6, 2.1), 0))
    c.rot('leg_l', lambda t: (30 * wave(t, 0.3) * pulse(t, 0.9, 1.7), 0, 0))
    c.rot('leg_r', lambda t: (-30 * wave(t, 0.3) * pulse(t, 0.9, 1.7), 0, 0))
    side_pair(c, lambda t: (0, 0, 20 + 40 * abs(wave(t, 0.25)) * pulse(t, 0.4, 2.2)))
    c.rot('h_head', lambda t: (8 * pulse(t, 0.9, 1.7), 0, -18 * pulse(t, 0.8, 1.8)))
    put(c)

    c = Clip(m, 'flap', 0.9)
    side_pair(c, lambda t: (0, 0, 14 + 48 * abs(wave(t, 0.3)) * pulse(t, 0.0, 0.9)))
    put(c)

    L = 1.2
    c = Clip(m, 'peep', L)
    c.rot('h_head', lambda t: (24 * pulse(t, 0.05, 1.1), 0, 0))
    jaw_calls(c, [(0.25, 0.42), (0.55, 0.72), (0.82, 0.98)], -22)
    put(c)

    # huddle: hunkers down, flippers tucked, head sunk into the down (cold), for a while
    L = 3.0
    c = Clip(m, 'huddle', L, 'loop')
    c.pos('body', lambda t: (0, -0.35 * S, 0))
    c.pos('leg_l', lambda t: (0, 0.35 * S, 0))
    c.pos('leg_r', lambda t: (0, 0.35 * S, 0))
    c.scl('body', lambda t: (1.04 + 0.01 * wave(t, L), 0.95, 1.04 + 0.01 * wave(t, L)))
    c.pos('h_head', lambda t: (0, -0.6 * S, 0))
    c.rot('h_head', lambda t: (-6, 4 * wave(t, L), 0))
    side_pair(c, lambda t: (0, 0, -6))
    put(c)

    return out + posture_set(m)


def chick_tags():
    return {
        'idle_beg': {'p': 'standing'}, 'idle_sleepy': {'p': 'standing'}, 'idle_happy': {'p': 'standing'},
        'sleep': {'p': 'lying'}, 'wobble': {'p': 'standing'}, 'huddle': {'p': 'standing'},
        'shake': {'p': 'standing'}, 'happy': {'p': 'standing'}, 'tame': {'p': 'standing'}, 'eat': {'p': 'standing'},
        'peep': {'p': 'standing'}, 'greet': {'p': 'standing'},
    }


def chick_idle():
    return {
        'standing': [
            {'clip': 'peep', 'w': 3, 'cd': 8, 'boost': {'adult_near': 1.5, 'owner_near': 1.5}},
            {'clip': 'flap', 'w': 2, 'cd': 10, 'when': '!in_water'},
            {'clip': 'wobble', 'w': 1, 'cd': 40, 'when': '!in_water'},
            {'clip': 'huddle', 'w': 1.2, 'min': 5, 'max': 10, 'when': '!in_water', 'boost': {'night': 2, 'rain': 2}},
            {'clip': 'look_around', 'w': 1.5, 'cd': 12},
        ],
    }
