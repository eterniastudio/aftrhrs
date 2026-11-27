"""Cinderwing Phoenix BOSS clips on the mount's shared model (the mount's own clips are clips/cinderwing_phoenix.py, owned
by the mounts' polish). Boss-only: boss_* hovers / taunt / heavy flinch (_birdboss), sound keyframes on the attack
clips, Talon Rake's melee hit frame, and the wrapped summon spin (it ended at -720 deg and unwound on the blend out)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _bossanim as B  # noqa: E402
import _birdboss as BB  # noqa: E402

IDLE = BB.IDLE
STATES = BB.STATES
TAGS = dict(BB.TAGS, talon_rake={'hit': 0.65})


def clips(m):
    out = BB.build(m, 'chest_core', B.P(m, 'ember_volley', 0.4), B.P(m, 'rebirth', 0.8))
    out.append(B.keep(m, B.with_sounds(m, 'ember_volley', [(0.2, 'ability_screech', 0.8, 1.1), (0.45, 'ability_whoosh', 0.9, 1.0),
                                                       (0.6, 'ability_fireball', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'solar_dive', [(0.3, 'ability_screech', 1.0, 1.0), (0.85, 'ability_whoosh', 1.0, 0.8),
                                                     (1.3, 'ability_slam', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'cinder_cyclone', [(0.15, 'ability_flare', 0.8, 1.1), (0.45, 'ability_whoosh', 1.0, 0.9),
                                                         (0.75, 'ability_whoosh', 1.0, 1.1)])))
    out.append(B.keep(m, B.with_sounds(m, 'rebirth', [(0.2, 'ability_flare', 0.9, 0.8), (0.7, 'ability_inhale', 1.0, 0.9),
                                                  (1.25, 'ability_screech', 1.0, 0.9), (1.27, 'ability_fireball', 1.0, 0.7)])))
    out.append(B.keep(m, B.with_sounds(m, 'pyre_brand', [(0.25, 'ability_screech', 0.9, 1.1), (0.75, 'ability_whoosh', 0.9, 1.0),
                                                     (0.9, 'ability_fireball', 1.0, 0.8)])))
    out.append(B.keep(m, B.with_sounds(m, 'talon_rake', [(0.25, 'ability_screech', 0.7, 1.2), (0.5, 'ability_whoosh', 0.9, 1.1),
                                                     (0.65, 'ability_rake', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'summon', [(0.2, 'ability_flare', 1.0, 0.8), (1.6, 'ability_whoosh', 1.0, 0.9),
                                                 (2.0, 'ability_screech', 1.0, 0.9)])))
    return out
