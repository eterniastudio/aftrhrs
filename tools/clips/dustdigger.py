"""Dustdigger: a badlands prospector zombie with a head lamp and a pick over its shoulder. Still a miner at heart: it
taps its flickering lamp, wipes the dust off its brow, sways, twitches, sniffs for you, and trudges with the pick
shouldered."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Walker, decay, env, keys, once, pulse, slump, undead_tags, wave  # noqa: E402

TAGS = undead_tags(base_once=('twitch', 'lamp_tap', 'wipe_brow'), walks=('walk_trudge', 'walk_drag'))


def lamp_tap(m, L=2.4):
    """The free hand goes up to the head lamp and knocks it twice; the lamp jiggles, the head ducks."""
    c = once(m, 'lamp_tap', L)
    up = lambda t: keys(t, [(0, 0), (0.45, 1), (1.6, 1), (2.1, 0), (L, 0)])
    knock = lambda t: pulse(t, 0.65, 0.85) + pulse(t, 1.05, 1.25)
    # the arm (zombie rest +84, forward) goes up to ~156; the forearm folds back (x+) to bring the hand onto the lamp
    c.rot('arm_l', lambda t: (72 * up(t) + 4 * knock(t), 0, 8 * up(t)), add=True)
    c.rot('forearm_l', lambda t: (78 * up(t) - 16 * knock(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-6 * up(t) - 5 * knock(t), -8 * up(t), 6 * up(t)), add=True)
    c.rot('lamp', lambda t: (8 * decay(t, 0.7, 6, 5) + 6 * decay(t, 1.1, 6, 5), 0, 0), add=True)
    c.sound(0.75, 'minecraft:block.lantern.hit', 0.45, 1.3)
    c.sound(1.15, 'minecraft:block.lantern.hit', 0.45, 1.2)
    return c


def wipe_brow(m, L=2.4):
    """Drags a forearm across its brow to clear the dust, head tipped back."""
    c = once(m, 'wipe_brow', L)
    up = lambda t: env(t, 0.0, L, 0.45, 0.5)
    swipe = lambda t: keys(t, [(0, 0), (0.6, 0), (1.3, 1), (L, 1)])
    c.rot('arm_l', lambda t: (62 * up(t), 24 * up(t) * (swipe(t) - 0.5), (4 + 16 * swipe(t)) * up(t)), add=True)
    c.rot('forearm_l', lambda t: (82 * up(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (12 * up(t), 8 * up(t) * (0.5 - swipe(t)), 0), add=True)
    c.rot('body', lambda t: (3 * up(t), 0, 0), add=True)
    c.sound(0.9, 'minecraft:block.sand.step', 0.3, 1.2)
    return c


def clips(m):
    w = Walker(m, crack=('minecraft:block.bone_block.break', 0.3, 1.3))
    return [w.idle_sway(), slump(w), w.twitch(), w.sniff(), lamp_tap(m), wipe_brow(m),
            w.walk_variant('walk_trudge', 1.25, stride=24, lurch=6, bob=1.2, lean=12, arms=None, sway=4, head_bob=5),
            w.walk_variant('walk_drag', 1.35, stride=24, drag='r', lurch=8, bob=1.0, lean=10, arms=None),
            w.run(0.8, reach=None), hurt(m, amt=0.8)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 3, 'min': 4, 'max': 10},
        {'clip': 'idle_sway', 'w': 2.5, 'min': 6, 'max': 14},
        {'clip': 'slump', 'w': 1.5, 'min': 6, 'max': 12},
        {'clip': 'twitch', 'w': 2, 'cd': 8},
        {'clip': 'lamp_tap', 'w': 2, 'cd': 15, 'boost': {'night': 2}},
        {'clip': 'wipe_brow', 'w': 1.5, 'cd': 15, 'boost': {'hot': 2}},
        {'clip': 'look_around', 'w': 1, 'cd': 12},
    ],
    'walk': [
        {'clip': '@mood', 'w': 2, 'min': 6, 'max': 16},
        {'clip': 'walk_trudge', 'w': 2.5, 'min': 8, 'max': 20},
        {'clip': 'walk_drag', 'w': 1, 'min': 8, 'max': 16},
    ],
    'notice': [{'clip': 'sniff', 'w': 1}],
}
