"""Reedwader: a river drowned, an old angler in a reed hat with a bobber still hooked on the brim and a creel at
his hip. Waterlogged and slow on land: it sways, slumps, wades with high dragging steps, flicks the bobber on its brim,
lifts its hat and wrings the water out of it. In the water (the server swaps the clips while it is under water) it
treads upright or swims after its prey with a heavy breaststroke."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, body_shake, env, keys, loop, once, pulse, slump, undead_tags, wave, decay  # noqa: E402
from animkit import Clip  # noqa: E402

TAGS = dict(undead_tags(base_once=('twitch', 'look_around', 'bobber_flick', 'wring_hat', 'shake_off'), walks=('walk_wade',)),
            swim={'p': 'swimming', 'move': True, 'gait': 'swim', 'layer': 'base'},
            tread={'p': 'swimming', 'move': False, 'layer': 'base'})


def bob(c, L, amt=1.0):
    """The bobber swings on its line from the brim (added to any clip)."""
    c.rot('bobber', lambda t: (amt * 10 * wave(t, L, 2, 0.2), 0, amt * 8 * wave(t, L, 1, 0.5)), add=True)
    return c


def bobber_flick(m, L=1.8):
    """A flick of the head sets the bobber spinning on its line; the head follows it round, then it settles."""
    c = once(m, 'bobber_flick', L)
    flick = lambda t: keys(t, [(0, 0), (0.25, 0), (0.4, 1), (0.6, 0), (L, 0)])
    c.rot('h_head', lambda t: (4 * flick(t), -10 * flick(t) + 8 * env(t, 0.5, L, 0.3, 0.5) * wave(t, L, 1), 14 * flick(t)), add=True)
    c.rot('bobber', lambda t: (34 * decay(t, 0.4, 2.2, 3.0), 0, 40 * decay(t, 0.42, 1.8, 2.6)), add=True)
    c.sound(0.45, 'minecraft:entity.fishing_bobber.retrieve', 0.4, 1.3)
    return c


def wring_hat(m, L=3.2):
    """Both hands go up, lift the reed hat off and twist it: water runs out (drips); the hat goes back on."""
    c = once(m, 'wring_hat', L)
    up = lambda t: env(t, 0.0, L, 0.5, 0.6)
    lift = lambda t: keys(t, [(0, 0), (0.6, 0), (0.9, 1), (2.3, 1), (2.6, 0), (L, 0)])
    twist = lambda t: lift(t) * 12 * math.sin(2 * math.pi * 2.0 * t) * pulse(t, 1.0, 2.2)
    c.rot('arm_l', lambda t: (66 * up(t), 0, -14 * up(t)), add=True)
    c.rot('arm_r', lambda t: (66 * up(t), 0, 14 * up(t)), add=True)
    c.rot('forearm_l', lambda t: (56 * up(t), 0, 0), add=True)
    c.rot('forearm_r', lambda t: (56 * up(t), 0, 0), add=True)
    c.pos('hat', lambda t: (0, 4.5 * lift(t), -3.0 * lift(t)))
    c.rot('hat', lambda t: (-30 * lift(t), twist(t), 0))
    c.rot('h_head', lambda t: (-8 * up(t), 0, 0), add=True)
    c.particle(1.2, 'minecraft:falling_water', 5, 0.3, 0.0, 0.9)
    c.particle(1.7, 'minecraft:falling_water', 5, 0.3, 0.0, 0.9)
    c.sound(1.2, 'minecraft:block.pointed_dripstone.drip_water', 0.5, 0.9)
    c.sound(1.7, 'minecraft:block.pointed_dripstone.drip_water', 0.5, 1.1)
    return c


def swim(m, L=1.6):
    """Under water: body forward and level, legs in a slow flutter, arms in a heavy breaststroke; the hat stays on."""
    c = Clip(m, 'swim', L, 'loop')
    ph = lambda t: 2 * math.pi * t / L
    c.rot('root', lambda t: (-72, 0, 2 * math.sin(ph(t))))
    c.pos('root', lambda t: (0, 9 + 0.6 * math.sin(2 * ph(t)), 12))
    c.rot('h_head', lambda t: (38 + 3 * math.sin(2 * ph(t)), 0, 0))
    # breaststroke: reach forward over the head (x ~ 170), sweep out (z) and back, glide
    stroke = lambda t: keys(t % L, [(0, 0), (0.5, 0), (0.9, 1), (L, 0)])
    out = lambda t: math.sin(math.pi * min(1.0, max(0.0, ((t % L) - 0.5) / 0.6)))
    c.rot('arm_l', lambda t: (178 - 55 * stroke(t), 0, -8 - 34 * out(t)))
    c.rot('arm_r', lambda t: (178 - 55 * stroke(t), 0, 8 + 34 * out(t)))
    c.rot('forearm_l', lambda t: (-10 * out(t), 0, 0))
    c.rot('forearm_r', lambda t: (-10 * out(t), 0, 0))
    c.rot('leg_l', lambda t: (14 * math.sin(2 * ph(t)), 0, -3))
    c.rot('leg_r', lambda t: (-14 * math.sin(2 * ph(t)), 0, 3))
    bob(c, L, 0.6)
    return c


def tread(m, L=3.2):
    """Under water, still: upright, legs pedaling slowly, arms sculling at the sides, bobbing."""
    c = Clip(m, 'tread', L, 'loop')
    c.copy('idle')
    ph = lambda t: 2 * math.pi * t / L
    c.pos('root', lambda t: (0, 0.8 * math.sin(ph(t)), 0))
    c.rot('arm_l', lambda t: (40 + 10 * math.sin(2 * ph(t)), 0, -28 + 10 * math.sin(2 * ph(t) + 0.8)))
    c.rot('arm_r', lambda t: (40 + 10 * math.sin(2 * ph(t) + math.pi), 0, 28 - 10 * math.sin(2 * ph(t) + 0.8 + math.pi)))
    c.rot('leg_l', lambda t: (18 * math.sin(ph(t)), 0, -2))
    c.rot('leg_r', lambda t: (-18 * math.sin(ph(t)), 0, 2))
    bob(c, L, 0.8)
    return c


def clips(m):
    w = Walker(m, crack=('minecraft:block.wet_sponge.break', 0.3, 1.4))
    sway = bob(w.idle_sway(amt=0.9), 6.4)
    wade = bob(w.walk_variant('walk_wade', 1.4, stride=34, lurch=7, bob=1.6, lean=8, sway=5, head_bob=5), 1.4, 1.2)
    run = bob(w.run(0.8, stride=36), 0.8, 1.4)
    shake = body_shake(w, sound=('minecraft:entity.generic.splash', 0.3, 1.3), extra=('bobber',))
    return [sway, slump(w), w.twitch(), w.sniff(), w.look_around(), bobber_flick(m), wring_hat(m), shake, wade, run,
            swim(m), tread(m), hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'idle_sway', 'w': 3, 'min': 6, 'max': 14},
        {'clip': 'slump', 'w': 1.5, 'min': 6, 'max': 12},
        {'clip': 'twitch', 'w': 1.5, 'cd': 8},
        {'clip': 'bobber_flick', 'w': 2, 'cd': 10},
        {'clip': 'wring_hat', 'w': 1.5, 'cd': 25, 'boost': {'rain': 2}},
        {'clip': 'shake_off', 'w': 1, 'cd': 25, 'boost': {'rain': 3}},
        {'clip': 'look_around', 'w': 1, 'cd': 12},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'walk_wade', 'w': 2.5, 'min': 8, 'max': 20},
    ],
    'notice': [{'clip': 'sniff', 'w': 1}],
}
