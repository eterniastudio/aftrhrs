"""Tempest Roc (flying mount; the boss form shares the model, its attack clips live in the __boss module).

Phase 2: like the phoenix, every ground clip kept the wings spread and flapping. Ground life is re-authored with
folded wings (_p2birds): strut walk, preening, wing settles, a horizon scan, a storm stance (wings open into the
wind when it rains), a head-tucked sleep, a static crackle (signature: the storm core pulses, feathers bristle, a
shiver runs through the crest) and weightier ridden takeoff / touchdown accents. Banks into turns in the air."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, posture_set, wave  # noqa: E402
from _p2kit import keep, env, hold, pair, rot, scl  # noqa: E402
import _p2birds  # noqa: E402

BOSS = ('summon', 'charge', 'eye_of_the_storm', 'thunder_dive', 'chain_squall', 'gale_buffet', 'stormcall',
        'tempest_spiral')


def static_crackle(m, folded, wing_bones):
    c = Clip(m, 'static_crackle', 2.4)
    hold(c, folded)
    jit = lambda t: (wave(t, 0.1, 1) * 0.6 + wave(t, 0.15, 1, 0.3) * 0.4) * env(t, 0.5, 0.6, 1.5, 1.7)
    up = lambda t: env(t, 0.3, 0.55, 1.6, 2.1)
    scl(c, 'storm_core', lambda t: (0.25 * up(t) + 0.08 * jit(t), 0.25 * up(t) + 0.08 * jit(t), 0.25 * up(t)))
    scl(c, 'body', lambda t: (0.04 * up(t), 0.03 * up(t), 0.04 * up(t)))
    rot(c, 'body', lambda t: (0, 0, 2.5 * jit(t)))
    rot(c, 'neck', lambda t: (keys(t, [(0, 0), (0.4, 8), (1.6, 8), (2.4, 0)]), 0, 0))
    rot(c, 'h_head', lambda t: (keys(t, [(0, 0), (0.4, -6), (1.6, -6), (2.4, 0)]), 4 * jit(t), 8 * jit(t)))
    for b in ('crest', 'crest_a_r', 'crest_b_r', 'crest_c_r', 'crest_d_r'):
        if m.has_bone(b):
            rot(c, b, lambda t: (-14 * up(t) + 4 * jit(t), 0, 0))
    pair(c, 'right_wing', 'left_wing', lambda t: (0, 10 * up(t), 6 * up(t) + 4 * jit(t)))
    for b, s in (('tail_fan_r', 1), ('tail_fan_l', -1)):
        rot(c, b, lambda t, s=s: (0, s * 14 * up(t), 0))
    rot(c, 'tail', lambda t: (8 * up(t), 0, 0))
    c.sound(0.45, 'minecraft:block.beacon.power_select', 0.35, 1.8)
    c.sound(0.6, 'minecraft:entity.breeze.idle_ground', 0.5, 1.3)
    c.sound(1.6, 'minecraft:entity.lightning_bolt.thunder', 0.12, 1.6)
    return [keep(m, c)]


CFG = {'wings_r': ('right_wing', 'right_wing_mid', 'right_wing_tip'),
       'wings_l': ('left_wing', 'left_wing_mid', 'left_wing_tip'),
       'legs': (('right_leg', 'right_talon'), ('left_leg', 'left_talon')),
       'fan_r': ['tail_fan_r'], 'fan_l': ['tail_fan_l'], 'crest': ['crest'],
       'spread_name': 'storm_stance', 'boss': BOSS, 'sig': static_crackle}


def clips(m):
    out = _p2birds.bird(m, CFG)
    for c in out:
        m.put(c)
    out += posture_set(m, lengths={'sit_down': 1.2, 'stand_up': 1.0, 'lie_down': 1.4, 'wake_up': 1.5})
    import _p2kit
    _p2kit.reactions(m, out, pet='ride_crest_flare', pet_sit='preen', bond_level='static_crackle')
    return out


TAGS = dict(_p2birds.TAGS, static_crackle={'layer': 'base', 'move': False})
IDLE = _p2birds.idle_tables('storm_stance', 'rain', sig=[('static_crackle', 2, 22)])
next(e for e in IDLE['standing'] if e['clip'] == 'static_crackle')['boost'] = {'thunder': 4, 'rain': 2}
PROCEDURAL = {'bank': 2.4, 'bank_max': 34, 'tail': ['tail', 'tail_fan'], 'tail_sway': 3, 'breath': 0.008}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
