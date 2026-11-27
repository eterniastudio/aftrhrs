#!/usr/bin/env python3
"""Clip classification + the species `anim` block (the data the Java animation state machine reads).

Every clip of every model gets a tag (gen_creatures.py writes them into data/aftrhrs/species/<id>.json "anim.clips"):

  p      posture: standing | sitting | lying | belly | airborne | swimming   (for transitions: the END posture)
  from   transitions only: the posture the clip starts in (sit_down: standing -> sitting)
  move   true if the clip may keep playing while the creature actually moves (locomotion, combat overlays)
  loop   loop | once | hold
  layer  base (main controller: locomotion, postures, whole-body flourishes) | overlay (action controller, layered over
         whatever the body does: attacks, hurt, head-only clips) | additive (accent controller: leap, land ...)
  drv    what the clip drives, for the procedural layer: h head rotation, b body scale (breathing), t tail, e ears
  eyes   true if the clip closes the eyes (only allowed in sleep-type clips; anim_check.py enforces it)
  len    seconds

Derived from: the hand-override table (TAGS below + clips/<model>.py TAGS) > clip-name rules > keyframe analysis
(root/body rotation beyond 75 deg = belly/rolling, a big root/body drop with the body level = lying).
"""
import re

from animkit import channel_points, sample

# ----------------------------------------------------------------------------------------------- name rules
LOCO = {
    # clip: (posture, gait)
    'walk': ('standing', 'walk'), 'run': ('standing', 'run'), 'charge': ('standing', 'run'),
    'walk_happy': ('standing', 'walk'), 'walk_sneak': ('standing', 'walk'), 'walk_sleepy': ('standing', 'walk'),
    'walk_limp': ('standing', 'walk'), 'walk_scared': ('standing', 'run'), 'walk_rage': ('standing', 'run'),
    'ride_walk': ('standing', 'walk'), 'ride_run': ('standing', 'run'), 'ride_idle': ('standing', 'idle'),
    'swim': ('swimming', 'swim'), 'idle_swim': ('swimming', 'idle'), 'ride_swim': ('swimming', 'swim'),
    'ride_tread': ('swimming', 'idle'), 'ride_rise': ('swimming', 'swim'),
    'fly': ('airborne', 'fly'), 'hover': ('airborne', 'idle'), 'glide': ('airborne', 'fly'), 'dive': ('airborne', 'fly'),
    'ride_fly': ('airborne', 'fly'), 'ride_hover': ('airborne', 'idle'), 'ride_glide': ('airborne', 'fly'),
    'ride_climb': ('airborne', 'fly'), 'ride_dive': ('airborne', 'fly'),
    'air_rise': ('airborne', 'jump'), 'air_fall': ('airborne', 'jump'), 'jump': ('airborne', 'jump'),
    'jump_start': ('airborne', 'jump'), 'jump_end': ('standing', 'jump'), 'takeoff': ('airborne', 'fly'),
    'land': ('standing', 'jump'), 'flap': ('airborne', 'fly'), 'zoomies': ('standing', 'run'),
}
ADDITIVE = {'leap', 'land', 'land_hard', 'ride_takeoff', 'ride_land'}
# held / looped posture clips by name
POSTURE = {
    'sit': 'sitting', 'rest': 'sitting', 'sit_tall': 'sitting', 'idle_beg': 'sitting', 'beg_trick': 'sitting',
    'idle_box': 'sitting', 'judge': 'sitting', 'paws_crossed': 'lying', 'knead': 'standing', 'knead_start': 'standing',
    'knead_end': 'standing', 'stand_guard': 'standing',
    'sleep': 'lying', 'lie': 'lying', 'idle_sleepy': None, 'idle_bed': 'lying', 'loaf': 'lying', 'sploot': 'lying',
    'snore_nap': 'lying', 'nod_off': None, 'purr_melt': 'lying', 'lean_flop': 'lying', 'flop': 'lying',
    'idle_sunbeam': 'lying', 'hide': 'sitting', 'burrowed': 'lying', 'dormant': 'lying', 'burrow': 'lying',
    'belly_up': 'belly', 'roll_over': 'belly', 'belly_trap': 'belly', 'idle_rain': None, 'death': 'lying',
    'cower': None, 'idle_scared': None, 'idle_flop': 'lying',
}
# transitions: (from, to)
TRANSITION = {
    'sit_down': ('standing', 'sitting'), 'stand_up': ('sitting', 'standing'),
    'lie_down': ('sitting', 'lying'), 'wake_up': ('lying', 'sitting'), 'unhide': ('sitting', 'standing'),
    'emerge': ('lying', 'standing'), 'spawn': ('standing', 'standing'),
    # authored by gen_creatures.py where a species' own lie_down / wake_up start or end standing
    'sit_lie_down': ('sitting', 'lying'), 'lie_sit_up': ('lying', 'sitting'),
}
# combat and reactions: layered over locomotion, keep playing while moving
OVERLAY = re.compile(r'^(attack\d*|bite|zap|peck|hurt|swat|love_bite|shoot|talon_rake|parry|combo|swing|swing_pick|'
                     r'stomp|tail_sweep|clap|squawk|roar|creak|rattle|wail|hush|flap|ride_look_back|ride_ear_fan|'
                     r'ride_ear_twitch|ride_crest_flare|ride_ruffle|ride_sniff|ride_stomp|ride_thump|ride_trunk_toot|'
                     r'ride_shake_mane|mount|dismount)$')
# boss / police / undead: every non-locomotion clip stays an overlay (their skills drive them; status quo)
MONSTER_KINDS = {'boss', 'police', 'undead'}
# sleep-type clips may close the eyes (no mob ever blinks otherwise)
SLEEPY = re.compile(r'^(sleep.*|lie_down|wake_up|nod_off|idle_sleepy|walk_sleepy|idle_rain|idle_sunbeam|idle_box|'
                    r'purr_melt|idle_bed|knead|snore_nap|death|dormant|burrowed|awaken|sit_lie_down|lie_sit_up)$')

HEAD = re.compile(r'^(h_|hi_)?head$|^h_')
EYES = re.compile(r'eye|lid|blink', re.I)
TAIL = re.compile(r'tail', re.I)
EAR = re.compile(r'(^|_)ear(_|$)|ears?$', re.I)
CORE = re.compile(r'^(root|body|core|torso|chest|hips|pelvis)$|leg|foot|paw', re.I)


def _range(view, clip, bone, ch, n=24):
    c = view.clips[clip]
    L = float(c.get('animation_length') or 0.05)
    data = ((c.get('bones') or {}).get(bone) or {}).get(ch)
    if data is None:
        return None
    rest = (1.0, 1.0, 1.0) if ch == 'scale' else (0.0, 0.0, 0.0)
    return [sample(data, L * k / n, rest) for k in range(n + 1)]


def analyse(view, clip):
    """Keyframe analysis: belly (rolled over), lying (dropped, level), sitting (dropped, front up), eyes closed."""
    out = {}
    roll = 0.0
    pitch = []
    drop = 0.0
    for bone in ('root', 'body', 'core'):
        r = _range(view, clip, bone, 'rotation')
        if r:
            roll = max(roll, max(max(abs(v[0]), abs(v[2])) for v in r))
            if bone == 'body':
                pitch = [v[0] for v in r]
        p = _range(view, clip, bone, 'position')
        if p:
            drop = min(drop, min(v[1] for v in p))
    body_y = (view.pivot.get('body') or [0, 4, 0])[1] or 4
    out['roll'] = roll
    out['drop'] = -drop / max(1.0, body_y)
    out['pitch'] = max(pitch) if pitch else 0.0
    if roll > 75:
        out['p'] = 'belly'
    elif out['drop'] > 0.3 and out['pitch'] > 12:
        out['p'] = 'sitting'
    elif out['drop'] > 0.3:
        out['p'] = 'lying'
    out['eyes'] = closes_eyes(view.clips[clip])
    return out


def pose_distance(view, a, ta, b, tb):
    """Rough distance between two poses: summed rotation (deg) + 8 x position (px) differences over keyed bones."""
    pa, pb = view.pose(a, ta), view.pose(b, tb)
    d = 0.0
    for key in set(pa) | set(pb):
        if key[1] == 'scale':
            continue
        va, vb = pa.get(key, (0.0, 0.0, 0.0)), pb.get(key, (0.0, 0.0, 0.0))
        d += sum(abs(x - y) for x, y in zip(va, vb)) * (8 if key[1] == 'position' else 1)
    return d


def closes_eyes(clip):
    """True if any eye bone's scale keyframe goes below 0.55 on x or y (eyes shut)."""
    for bone, chs in (clip.get('bones') or {}).items():
        if EYES.search(bone) and 'scale' in chs:
            for t, pre, post in channel_points(chs['scale']):
                if min(pre[0], pre[1], post[0], post[1]) < 0.55:
                    return True
    return False


def strip_blinks(view):
    """No mob ever blinks: in every clip that is not sleep-type, eye scale keyframes that shut the eyes are opened
    (scale 1). Returns the clips it changed. The source bbmodel is untouched (this edits the generated json)."""
    changed = []
    for name, clip in view.clips.items():
        if SLEEPY.match(name) or not closes_eyes(clip):
            continue
        for bone, chs in (clip.get('bones') or {}).items():
            if not EYES.search(bone) or 'scale' not in chs or not isinstance(chs['scale'], dict):
                continue
            for k, e in list(chs['scale'].items()):
                def fix(v):
                    if isinstance(v, list) and min(float(x) for x in v[:2]) < 0.55:
                        return [1, 1, 1]
                    return v
                if isinstance(e, dict):
                    for side in ('pre', 'post', 'vector'):
                        if side in e:
                            e[side] = fix(e[side])
                else:
                    chs['scale'][k] = fix(e)
        changed.append(name)
    return changed


def drives(view, clip, head_bone):
    keyed = (view.clips[clip].get('bones') or {})
    d = ''
    if head_bone and 'rotation' in (keyed.get(head_bone) or {}):
        d += 'h'
    if 'scale' in (keyed.get('body') or {}) or 'position' in (keyed.get('body') or {}):
        d += 'b'
    if any(TAIL.search(b) and 'rotation' in ch for b, ch in keyed.items()):
        d += 't'
    if any(EAR.search(b) and 'rotation' in ch for b, ch in keyed.items()):
        d += 'e'
    return d


def is_partial(view, clip):
    """True if the clip moves no core bone (root / body / legs): it can layer over any posture."""
    keyed = view.clips[clip].get('bones') or {}
    return bool(keyed) and not any(CORE.search(b) for b in keyed)


def classify(view, clip, kind, sit_role=None, head_bone=None, overrides=None):
    c = view.clips[clip]
    loop = {True: 'loop', 'hold_on_last_frame': 'hold'}.get(c.get('loop'), 'once')
    a = analyse(view, clip)
    tag = {'p': 'standing', 'move': False, 'loop': loop, 'layer': 'base', 'len': float(c.get('animation_length') or 0)}
    if clip in LOCO:
        tag['p'], tag['gait'] = LOCO[clip]
        tag['move'] = True
    if clip in ADDITIVE:
        tag['layer'] = 'additive'
        tag['move'] = True
        tag['p'] = 'airborne' if clip not in ('land', 'land_hard', 'ride_land') else 'standing'
    elif clip in TRANSITION:
        tag['from'], tag['p'] = TRANSITION[clip]
    elif clip in POSTURE and POSTURE[clip]:
        tag['p'] = POSTURE[clip]
    elif clip not in LOCO and 'p' in a:
        tag['p'] = a['p']
    if clip == sit_role and clip not in TRANSITION:
        tag['p'] = 'sitting'
    if OVERLAY.match(clip) or (kind in MONSTER_KINDS and clip not in LOCO and clip not in POSTURE and clip != 'death'
                               and tag['layer'] == 'base' and loop != 'loop'):
        tag['layer'] = 'overlay'
        tag['move'] = True
    elif tag['layer'] == 'base' and clip not in LOCO and loop == 'once' and is_partial(view, clip):
        tag['layer'] = 'overlay'
        tag['move'] = True
    if clip == 'death':
        tag['layer'] = 'base'
        tag['move'] = True
    if a['eyes']:
        tag['eyes'] = True
    d = drives(view, clip, head_bone)
    if d:
        tag['drv'] = d
    if overrides and clip in overrides:
        tag.update(overrides[clip])
    tag['len'] = round(tag['len'], 3)
    return tag


# ----------------------------------------------------------------------------------------------- species block
def chain(view, pred):
    """Bones matching pred in hierarchy order (parents first), e.g. tail, tail_mid, tail_tip."""
    found = [b for b in view.bones if pred(b)]
    depth = {}

    def dep(b):
        if b not in depth:
            p = view.parent.get(b)
            depth[b] = 0 if p is None else dep(p) + 1
        return depth[b]
    return sorted(found, key=lambda b: (dep(b), view.bones.index(b)))


def procedural(view, head_bone):
    tails = chain(view, lambda b: TAIL.search(b) and not re.search(r'scarf|fin_?$', b))
    tails = [b for b in tails if b.startswith('tail') or b.startswith('h_tail')][:3]
    ears = [b for b in view.bones if EAR.search(b)][:2]
    out = {}
    if head_bone:
        out['head'] = head_bone
    if 'body' in view.parent:
        out['body'] = 'body'
    if tails:
        out['tail'] = tails
    if len(ears) == 2:
        out['ears'] = ears
    return out


def transitions(tags, sit_clip):
    out = {}
    for clip, t in tags.items():
        if 'from' in t and t['from'] != t['p']:
            key = t['from'] + '>' + t['p']
            if key not in out or clip == {'standing>sitting': 'sit_down', 'sitting>standing': 'stand_up'}.get(key):
                out[key] = clip
    return out


def default_idle(tags, pools, sit_clip, sleep_clip):
    """Default idle pools per posture. '@mood' = the mood brain's idle loop, '@sit' / '@sleep' the species' clips.
    One-shots from the mood pools are added at runtime (MoodBrain); these are the posture-aware extras."""
    standing = [{'clip': '@mood', 'w': 6, 'min': 6, 'max': 16}]
    for clip in ("look_around", "stretch", "sniff", "groom", "scratch", "yawn"):
        if clip in tags:
            standing.append({'clip': clip, 'w': 1, 'cd': 14})
    sitting = []
    if sit_clip:
        sitting.append({'clip': '@sit', 'w': 8, 'min': 8, 'max': 24})
    for clip, t in tags.items():
        # layered head / ear / tail one-shots work over the sit pose (whole-body flourishes start standing: not here)
        if t['loop'] == 'once' and t['layer'] == 'overlay' and not OVERLAY.match(clip) and not t.get('eyes'):
            sitting.append({'clip': clip, 'w': 1, 'cd': 18})
    if 'yawn' in tags and tags['yawn']['layer'] == 'overlay' and not any(e['clip'] == 'yawn' for e in sitting):
        sitting.append({'clip': 'yawn', 'w': 1, 'cd': 30, 'boost': {'night': 3}})
    lying = [{'clip': '@sleep', 'w': 1, 'min': 30, 'max': 90}] if sleep_clip else []
    return {'standing': standing, 'sitting': sitting, 'lying': lying}


def build(species, view, tables):
    """-> the species' `anim` block. tables: merged clips/<model>.py tables (animkit.merge)."""
    kind = species.get('kind')
    head = species.get('head_bone')
    roles = species.get('anims') or {}
    sit_role = roles.get('sit')
    overrides = dict(tables.get('TAGS') or {})
    tags = {}
    for clip in view.clips:
        tags[clip] = classify(view, clip, kind, sit_role, head, overrides)
    sleep_clip = roles.get('sleep') or ('lie' if 'lie' in tags else None)
    # transitions: where each one really starts and ends (some lie_down clips start standing, not sitting)
    refs = {'standing': ('idle', 0), 'sitting': (sit_role, 'end'), 'lying': (sleep_clip, 0)}
    refs = {k: v for k, v in refs.items() if v[0] and v[0] in view.clips}
    for clip, t in tags.items():
        if clip not in TRANSITION or clip == 'spawn' or (overrides.get(clip) or {}).get('from') or len(refs) < 2:
            continue
        ends = []
        for at in (0, 'end'):
            best = min(refs, key=lambda k: pose_distance(view, clip, at, *refs[k]))
            ends.append(best)
        if ends[0] != ends[1]:
            t['from'], t['p'] = ends
    states = {}
    for state, clip in (('idle', species.get('base_idle') or ('hover' if species.get('flyer') and 'hover' in tags else 'idle')),
                        ('walk', species.get('base_walk') or ('fly' if species.get('flyer') and 'fly' in tags else 'walk')),
                        ('run', 'run'), ('swim', 'swim'), ('swim_idle', 'idle_swim'), ('fly', 'fly'), ('hover', 'hover'),
                        ('sit', sit_role), ('sleep', sleep_clip), ('lie', 'lie' if 'lie' in tags else sleep_clip),
                        ('death', 'death'), ('hurt', roles.get('hurt'))):
        if clip and clip in tags:
            states[state] = clip
    states.update({k: v for k, v in (tables.get('STATES') or {}).items() if v in tags})
    trans = transitions(tags, states.get('sit'))
    trans.update({k: v for k, v in (tables.get('TRANSITIONS') or {}).items() if v in tags})
    idle = default_idle(tags, species.get('pools') or {}, states.get('sit'), states.get('sleep'))
    for posture, entries in (tables.get('IDLE') or {}).items():
        have = {e['clip'] for e in idle.setdefault(posture, [])}
        for e in entries:
            if e.get('replace'):
                idle[posture] = [x for x in idle[posture] if x['clip'] != e['clip']]
                e = {k: v for k, v in e.items() if k != 'replace'}
            elif e['clip'] in have:
                idle[posture] = [x for x in idle[posture] if x['clip'] != e['clip']]
            idle[posture].append(e)
    for posture in idle:
        idle[posture] = [e for e in idle[posture] if e['clip'].startswith('@') or e['clip'] in tags]
    proc = procedural(view, head)
    proc.update(tables.get('PROCEDURAL') or {})
    block = {'clips': tags, 'states': states, 'transitions': trans, 'idle': idle, 'procedural': proc}
    if tables.get('GAIT'):
        block['gait'] = tables['GAIT']
    if tables.get('authored'):
        block['authored'] = sorted(set(tables['authored']))
    if tables.get('unblinked'):
        block['unblinked'] = sorted(tables['unblinked'])
    return block
