"""Sandshroud: a desert husk wrapped in a sun-faded indigo head-wrap and veil, a caravan drifter with a cracked, empty
water urn at its hip. Heat-weary: it sways, slumps, trudges with its head low into the wind (the wrap's tail streams
behind it), tugs the veil up over its face, and lifts its empty urn to tip it out (sand trickles, never water)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, env, keys, once, pulse, slump, undead_tags, wave, decay  # noqa: E402

TAGS = undead_tags(base_once=('twitch', 'look_around', 'veil_tug', 'pour_urn'), walks=('walk_trudge', 'walk_drag'))


def flutter(c, L, amt=1.0):
    """The wrap's loose tail streams and flaps behind the head (added to any clip)."""
    c.rot('wrap_tail', lambda t: (amt * (14 + 6 * wave(t, L, 3) + 3 * wave(t, L, 7, 0.3)), 0, amt * 4 * wave(t, L, 2, 0.6)), add=True)
    return c


def veil_tug(m, L=2.4):
    """The right hand comes up to the veil and drags it higher over the face; the head turns from the wind."""
    c = once(m, 'veil_tug', L)
    up = lambda t: env(t, 0.0, L, 0.45, 0.55)
    tug = lambda t: keys(t, [(0, 0), (0.7, 0), (1.1, 1), (1.5, 0.6), (L, 0.6)])
    # the zombie arm rests ~84 forward; up to ~150 and the forearm folds in to bring the hand to the chin
    c.rot('arm_r', lambda t: (60 * up(t) + 6 * tug(t), 0, -12 * up(t)), add=True)
    c.rot('forearm_r', lambda t: (70 * up(t) - 10 * tug(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-6 * up(t) + 4 * tug(t), 14 * up(t), 0), add=True)
    flutter(c, L, 1.2)
    c.sound(0.95, 'minecraft:item.armor.equip_leather', 0.35, 0.8)
    return c


def pour_urn(m, L=3.0):
    """The left hand drops to the urn and tips it up: nothing but sand trickles out; it shakes it once, lets it fall."""
    c = once(m, 'pour_urn', L)
    up = lambda t: env(t, 0.0, L, 0.5, 0.6)
    tip = lambda t: keys(t, [(0, 0), (0.6, 0), (1.1, 1), (2.2, 1), (2.6, 0), (L, 0)])
    shake = lambda t: tip(t) * 8 * wave(t, L, 9) * pulse(t, 1.6, 2.1)
    c.rot('arm_l', lambda t: (-70 * up(t), 0, 10 * up(t)), add=True)
    c.rot('forearm_l', lambda t: (20 * up(t), 0, 0), add=True)
    c.rot('urn', lambda t: (0, 0, -95 * tip(t) + shake(t)), add=True)
    c.rot('h_head', lambda t: (-16 * up(t), -12 * up(t), 6 * up(t)), add=True)
    c.rot('body', lambda t: (-4 * up(t), -6 * up(t), 4 * up(t)), add=True)
    c.particle(1.3, 'minecraft:white_ash', 6, 0.2, 0.01, 0.4)
    c.sound(1.2, 'minecraft:block.sand.fall', 0.4, 1.3)
    c.sound(1.8, 'minecraft:block.decorated_pot.hit', 0.35, 1.4)
    return c


def clips(m):
    w = Walker(m, crack=('minecraft:block.bone_block.break', 0.3, 1.2))
    sway = flutter(w.idle_sway(amt=0.8), 6.4, 0.7)
    trudge = flutter(w.walk_variant('walk_trudge', 1.3, stride=22, lurch=6, bob=1.1, lean=14, sway=4, head_bob=4), 1.3, 1.2)
    drag = flutter(w.walk_variant('walk_drag', 1.4, stride=22, drag='l', lurch=8, bob=1.0, lean=10), 1.4, 1.1)
    run = flutter(w.run(0.8, stride=36), 0.8, 1.5)
    return [sway, slump(w), w.twitch(), w.sniff(), w.look_around(), veil_tug(m), pour_urn(m), trudge, drag, run,
            hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'idle_sway', 'w': 3, 'min': 6, 'max': 14},
        {'clip': 'slump', 'w': 2, 'min': 6, 'max': 12, 'boost': {'hot': 2}},
        {'clip': 'twitch', 'w': 1.5, 'cd': 8},
        {'clip': 'veil_tug', 'w': 2, 'cd': 14},
        {'clip': 'pour_urn', 'w': 1.5, 'cd': 25, 'boost': {'hot': 2}},
        {'clip': 'look_around', 'w': 1, 'cd': 12},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'walk_trudge', 'w': 2.5, 'min': 8, 'max': 20},
        {'clip': 'walk_drag', 'w': 1, 'min': 8, 'max': 16},
    ],
    'notice': [{'clip': 'sniff', 'w': 1}],
}
