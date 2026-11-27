"""Shared tables for the tameable hostiles (phase 2): two modes. Wild (untamed): the idle_wild loop instead of the pet
moods, wild-only idles, a stalking gait toward a far target, a threat display when it picks a target (MoodBrain).
Tamed: calm companion idles, sit and sleep. Not a model module."""

PET_DEFAULTS = ('look_around', 'stretch', 'sniff', 'groom', 'scratch', 'yawn')


def tables(wild, tame, sitting, lying, extra_states=None):
    """wild / tame: [(clip, weight, cooldown | (min, max), when-extra)] standing entries; the defaults pet idles become
    tame-only. Returns (IDLE, STATES)."""
    standing = [{'clip': '@mood', 'w': 5, 'min': 6, 'max': 14}]
    for gate, entries in (('!tame', wild), ('tame', tame)):
        for e in entries:
            clip, w, cd = e[0], e[1], e[2]
            when = gate + (',' + e[3] if len(e) > 3 and e[3] else '')
            d = {'clip': clip, 'w': w, 'when': when}
            if isinstance(cd, tuple):
                d['min'], d['max'] = cd
            else:
                d['cd'] = cd
            standing.append(d)
    have = {e['clip'] for e in standing}
    for clip in PET_DEFAULTS:
        if clip not in have:
            standing.append({'clip': clip, 'w': 1, 'cd': 24, 'when': 'tame'})
    states = {'idle_wild': 'idle_wild', 'stalk': 'stalk', 'threat': 'threat'}
    states.update(extra_states or {})
    return {'standing': standing, 'sitting': sitting, 'lying': lying}, states


TAGS = {'idle_wild': {'p': 'standing', 'loop': 'loop'},
        'stalk': {'p': 'standing', 'gait': 'walk', 'move': True, 'loop': 'loop'},
        'threat': {'layer': 'overlay', 'move': True}}
