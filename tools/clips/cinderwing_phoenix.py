"""Cinderwing Phoenix (flying mount; the boss form shares the model, its attack clips live in the __boss module).

Phase 2: every ground clip used to keep the wings spread and flapping (the ground idle was a hover). Ground life is
re-authored with folded wings (_p2birds): a strut walk with a head pump, preening, wing settles, a horizon scan,
sunning with the wings open on sunny days, a head-tucked sleep, an ember shake (signature) and weightier ridden
takeoff / touchdown accents. The body banks into turns in the air (procedural bank)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, posture_set, wave  # noqa: E402
from _p2kit import keep, env, hold, pair, pos, rot, scl  # noqa: E402
import _p2birds  # noqa: E402

BOSS = ('summon', 'rebirth', 'talon_rake', 'ember_volley', 'cinder_cyclone', 'pyre_brand', 'solar_dive')


def ember_shake(m, folded, wing_bones):
    """Signature: a full-body fluff and shake that throws the tail plumes and crest wide, then a proud settle."""
    c = Clip(m, 'ember_shake', 2.2)
    hold(c, folded)
    sh = lambda t: wave(t, 0.16, 1) * env(t, 0.35, 0.45, 1.25, 1.45)
    puff = lambda t: env(t, 0.2, 0.4, 1.3, 1.8)
    scl(c, 'body', lambda t: (0.05 * puff(t), 0.04 * puff(t), 0.05 * puff(t)))
    rot(c, 'body', lambda t: (0, 0, 5 * sh(t)))
    rot(c, 'neck', lambda t: (keys(t, [(0, 0), (0.3, -10), (1.4, -10), (1.8, 8), (2.2, 0)]), 6 * sh(t), 0))
    rot(c, 'h_head', lambda t: (keys(t, [(0, 0), (0.3, 6), (1.4, 6), (1.8, -10), (2.2, 0)]), 0, -14 * sh(t)))
    pair(c, 'right_wing', 'left_wing', lambda t: (0, 14 * puff(t), 8 * puff(t) + 7 * sh(t)))
    for b, s in (('plume_right_r', 1), ('plume_left_r', -1)):
        rot(c, b, lambda t, s=s: (8 * puff(t), s * 22 * puff(t), 0))
    rot(c, 'tail', lambda t: (10 * puff(t), 8 * sh(t), 0))
    for b, s in (('right_crest_feather_r', 1), ('left_crest_feather_r', -1)):
        rot(c, b, lambda t, s=s: (-12 * puff(t), s * 16 * puff(t), 0))
    rot(c, 'crest', lambda t: (-14 * puff(t), 0, 0))
    c.sound(0.4, 'minecraft:entity.blaze.burn', 0.5, 1.4)
    c.sound(0.5, 'minecraft:entity.parrot.fly', 0.6, 0.7)
    c.sound(1.75, 'minecraft:block.fire.ambient', 0.6, 1.2)
    return [keep(m, c)]


CFG = {'wings_r': ('right_wing', 'right_wing_mid', 'right_wing_tip'),
       'wings_l': ('left_wing', 'left_wing_mid', 'left_wing_tip'),
       'legs': (('right_leg', 'right_talon'), ('left_leg', 'left_talon')),
       'fan_r': ['plume_right_r'], 'fan_l': ['plume_left_r'], 'crest': ['crest'],
       'spread_name': 'sun_wings', 'boss': BOSS, 'sig': ember_shake}


def clips(m):
    out = _p2birds.bird(m, CFG)
    for c in out:
        m.put(c)
    out += posture_set(m, lengths={'sit_down': 1.1, 'stand_up': 0.9, 'lie_down': 1.4, 'wake_up': 1.4})
    import _p2kit
    _p2kit.reactions(m, out, pet='ride_crest_flare', pet_sit='preen', bond_level='ember_shake')
    return out


TAGS = dict(_p2birds.TAGS, ember_shake={'layer': 'base', 'move': False})
IDLE = _p2birds.idle_tables('sun_wings', 'day,!rain', sig=[('ember_shake', 2, 24)])
PROCEDURAL = {'bank': 2.2, 'bank_max': 32, 'tail': ['tail', 'tail_fan'], 'tail_sway': 3, 'breath': 0.008}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
