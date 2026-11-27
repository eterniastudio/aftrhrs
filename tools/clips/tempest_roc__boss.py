"""Tempest Roc BOSS clips on the mount's shared model (the mount's own clips are clips/tempest_roc.py, owned by the
mounts' polish). Boss-only: boss_* hovers / taunt / heavy flinch (_birdboss), the phase-2 storm call, a real Charge
(the model's was a 3-bone loop: rear back, wings swept, talons first), sound keyframes on every attack clip, Gale
Buffet's melee hit frame, and the wrapped spins of Eye of the Storm / Tempest Spiral (they ended at 360 / 720 deg)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, pulse, step  # noqa: E402
import _bossanim as B  # noqa: E402
import _birdboss as BB  # noqa: E402

IDLE = BB.IDLE
STATES = dict(BB.STATES, phase2='boss_phase2')
TAGS = dict(BB.TAGS, gale_buffet={'hit': 0.85}, charge={'p': 'airborne', 'layer': 'overlay', 'move': True, 'loop': 'once'})


def clips(m):
    out = BB.build(m, 'storm_core', B.mix(B.P(m, 'hover', 0), B.P(m, 'stormcall', 1.0), 0.5), B.P(m, 'gale_buffet', 0.6))
    H0 = B.P(m, 'hover', 0)
    call = B.P(m, 'stormcall', 1.0)          # wings straight up, head to the sky
    tuck = B.P(m, 'thunder_dive', 0.5)       # body flat, talons tucked: the dive
    talons = B.P(m, 'thunder_dive', 0.75)    # talons thrown forward: the strike

    # Charge: 1.4 s, the rush starts at 0.5 s (BossSkills lunge at tick 10), talons hit at 0.9 s (tick 18)
    c = Clip(m, 'charge', 1.4, 'once')
    rear = B.mix(H0, call, 0.55)
    B.seq(c, [(0, H0), (0.42, rear), (0.55, tuck, 'out'), (0.82, tuck), (0.9, talons, 'snap'), (1.05, talons), (1.4, H0)])
    B.shake(c, 'body', 0.2, 0.45, 2.5, 12)
    B.sounds(c, [(0.15, 'ability_screech', 0.9, 1.1), (0.5, 'ability_whoosh', 1.0, 0.8), (0.9, 'ability_rake', 1.0, 0.9)])
    out.append(B.keep(m, c))

    # phase 2: the roc calls the storm down on itself (wings up, crackling), then claps it out
    c = Clip(m, 'boss_phase2', 2.8, 'once')
    clap = B.P(m, 'gale_buffet', 0.85)
    B.seq(c, [(0, H0), (0.6, call), (1.7, call), (1.95, clap, 'snap'), (2.2, clap), (2.8, H0)])
    B.shake(c, 'body', 0.6, 1.7, 3, 14)
    B.shake(c, 'h_head', 0.6, 1.7, 4, 9)
    c.scl('storm_core', lambda t: (1 + 0.6 * step(t, 0.4, 1.7) - 0.6 * step(t, 2.0, 2.7),) * 3)
    B.sounds(c, [(0.2, 'ability_screech', 1.0, 0.9), (0.8, 'ability_crackle', 1.0, 1.0), (1.4, 'ability_crackle', 1.0, 1.2),
                 (1.95, 'ability_thunder', 1.0, 1.0), (1.97, 'ability_whoosh', 1.0, 0.7)])
    out.append(B.keep(m, c))

    out.append(B.keep(m, B.with_sounds(m, 'thunder_dive', [(0.2, 'ability_screech', 0.9, 1.0), (0.5, 'ability_whoosh', 1.0, 0.8),
                                                       (0.72, 'ability_rake', 1.0, 0.9)])))
    out.append(B.keep(m, B.with_sounds(m, 'chain_squall', [(0.25, 'ability_crackle', 0.8, 1.2), (0.6, 'ability_screech', 0.9, 1.0),
                                                       (0.78, 'ability_whoosh', 0.9, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'gale_buffet', [(0.3, 'ability_screech', 0.8, 1.1), (0.75, 'ability_whoosh', 1.0, 0.8),
                                                      (0.85, 'ability_gust', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'stormcall', [(0.3, 'ability_screech', 1.0, 0.9), (0.8, 'ability_crackle', 0.9, 1.0),
                                                    (1.5, 'ability_crackle', 0.8, 1.3)])))
    out.append(B.keep(m, B.with_sounds(m, 'eye_of_the_storm', [(0.3, 'ability_whoosh', 0.9, 1.1), (0.75, 'ability_whoosh', 0.9, 1.2),
                                                           (1.3, 'ability_screech', 1.0, 0.9), (1.35, 'ability_gust', 1.0, 0.8)])))
    out.append(B.keep(m, B.with_sounds(m, 'tempest_spiral', [(0.35, 'ability_whoosh', 0.9, 1.0), (0.75, 'ability_whoosh', 0.9, 1.15),
                                                         (1.1, 'ability_whoosh', 0.9, 1.3), (1.5, 'ability_gust', 1.0, 1.0)])))
    out.append(B.keep(m, B.with_sounds(m, 'summon', [(0.3, 'ability_crackle', 1.0, 0.9), (1.4, 'ability_whoosh', 1.0, 0.9),
                                                 (1.5, 'ability_screech', 1.0, 0.9)])))
    return out
