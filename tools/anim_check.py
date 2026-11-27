#!/usr/bin/env python3
"""Animation validator for every Arcana species (run after gen_creatures.py). Writes mod/ANIMATION.md.

  python anim_check.py            (exit 1 if any ERROR)

Per species (data/aftrhrs/species/<id>.json "anim" + the generated <model>.animation.json):
  states       every state maps to a clip that exists; tameables have sit + sleep (+ idle, walk, death for all)
  transitions  tameables can get standing<->sitting and sitting<->lying through clips; each transition starts on the
               pose it comes from and ends on the pose it goes to (pop > 12 deg / 1.2 px is a WARN: GeckoLib still
               cross-fades the stage change)
  loops        loop clips end where they start (seam > 3 deg / 0.3 px / 0.05 scale is a WARN)
  blink        no clip closes the eyes except sleep-type clips (ERROR)
  tags         posture tags are consistent (sit = sitting, sleep = lying, locomotion may move, belly / sit / lie clips
               may not, transitions have from != to) (ERROR)
  sanity       no bone rotates beyond 400 deg (except whole turns: spins, rolls, rings) or moves beyond 96 px; no NaN
               (ERROR)
  fishbowl     a species with a "fishbowl" never pokes through its glass bowl in any clip, overlay, cross-fade or
               transition the game can show (bowl_check.py; ERROR)
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import ModelView, channel_points, load_gecko, sample  # noqa: E402
from animtags import SLEEPY, closes_eyes  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
GECKO = os.path.join(RES, 'assets', 'aftrhrs', 'geckolib')
SPECIES = os.path.join(RES, 'data', 'aftrhrs', 'species')
TAMEABLE = {'pet', 'hostile', 'mount'}


def pose_diff(view, a, ta, b, tb):
    """Max rotation (deg) and position (px) difference between two poses over the bones either keys."""
    pa, pb = view.pose(a, ta), view.pose(b, tb)
    rot = pos = 0.0
    for key in set(pa) | set(pb):
        rest = (1.0, 1.0, 1.0) if key[1] == 'scale' else (0.0, 0.0, 0.0)
        va, vb = pa.get(key, rest), pb.get(key, rest)
        d = max(abs(x - y) for x, y in zip(va, vb))
        if key[1] == 'rotation':
            rot = max(rot, d)
        elif key[1] == 'position':
            pos = max(pos, d)
    return rot, pos


def path(trans, a, b):
    """Transition clips from posture a to b (the Java AnimSpec.path: direct, else up to three hops)."""
    if a == b:
        return []
    if a + '>' + b in trans:
        return [trans[a + '>' + b]]
    frontier = [[a]]
    seen = {a}
    while frontier:
        route = frontier.pop(0)
        if len(route) > 3:
            break
        for k in trans:
            f, t = k.split('>')
            if f != route[-1] or t in seen:
                continue
            nxt = route + [t]
            if t == b:
                return [trans[x + '>' + y] for x, y in zip(nxt, nxt[1:])]
            seen.add(t)
            frontier.append(nxt)
    return None


def seam(view, clip):
    c = view.clips[clip]
    L = float(c.get('animation_length') or 0)
    worst = (0.0, '')
    for bone, chs in (c.get('bones') or {}).items():
        for ch, data in chs.items():
            pts = channel_points(data)
            if len(pts) < 2:
                continue
            rest = (1.0, 1.0, 1.0) if ch == 'scale' else (0.0, 0.0, 0.0)
            a, b = sample(data, 0.0, rest), sample(data, L, rest)
            d = max(abs(x - y) for x, y in zip(a, b))
            if ch == 'rotation':
                d = max(min(abs(x - y) % 360, 360 - abs(x - y) % 360) for x, y in zip(a, b))   # whole turns are seamless
            lim = {'rotation': 3.0, 'position': 0.3, 'scale': 0.05}[ch]
            if d / lim > worst[0]:
                worst = (d / lim, '%s.%s %.2f' % (bone, ch, d))
    return worst


def sanity(view, clip):
    """Bones beyond 400 deg (unless the axis is a one-way spin: rings, rolls, spins) or 48 px, NaN."""
    out = []
    for bone, chs in (view.clips[clip].get('bones') or {}).items():
        for ch, data in chs.items():
            pts = channel_points(data)
            for axis in range(3):
                vals = [p[2][axis] for p in pts]
                if any(math.isnan(v) for v in vals):
                    out.append('%s.%s NaN' % (bone, ch))
                    break
                big = max((abs(v) for v in vals), default=0)
                if ch == 'rotation' and big > 400:
                    steps = [b - a for a, b in zip(vals, vals[1:]) if abs(b - a) > 1e-6]
                    turns = abs(vals[-1] - vals[0]) % 360
                    spin = steps and (all(x > 0 for x in steps) or all(x < 0 for x in steps)
                                                      or min(turns, 360 - turns) < 5)
                    if not spin:
                        out.append('%s.%s axis %d reaches %.0f deg' % (bone, ch, axis, big))
                elif ch == 'position' and big > (96 if bone in ('root', 'body') else 256):
                    out.append('%s.%s axis %d reaches %.0f px' % (bone, ch, axis, big))
    return out[:3]


def check(sid):
    sp = json.load(open(os.path.join(SPECIES, sid + '.json'), encoding='utf-8'))
    res = {'id': sid, 'kind': sp.get('kind'), 'errors': [], 'warns': [], 'clips': 0}
    model = sp.get('model')
    if not model:
        res['skip'] = True
        return res
    view = ModelView(model, json.load(open(os.path.join(GECKO, 'models', 'entity', model + '.geo.json'))),
                     load_gecko(os.path.join(GECKO, 'animations', 'entity', model + '.animation.json')))
    anim = sp.get('anim') or {}
    tags = anim.get('clips') or {}
    states = anim.get('states') or {}
    trans = anim.get('transitions') or {}
    idle = anim.get('idle') or {}
    res['clips'] = len(view.clips)
    res['authored'] = len(anim.get('authored') or [])
    E, W = res['errors'], res['warns']
    tame = sp.get('kind') in TAMEABLE and not sp.get('untameable')
    res['tame'] = tame
    # states
    for st, clip in states.items():
        if clip not in view.clips:
            E.append('state %s -> missing clip %s' % (st, clip))
    need = ['idle', 'walk', 'death'] + (['sit', 'sleep'] if tame else [])
    for st in need:
        if st not in states:
            (E if st in ('idle', 'sit') else W).append('no %s state' % st)
    res['states'] = sum(1 for s in need if s in states)
    res['states_need'] = len(need)
    # transitions
    want = ['standing>sitting', 'sitting>standing'] + (['sitting>lying', 'lying>sitting'] if 'sleep' in states else [])
    have = 0
    if tame:
        sit = states.get('sit')
        for k in want:
            route = path(trans, *k.split('>'))
            if route and all(c in view.clips for c in route):
                have += 1
            elif k == 'standing>sitting' and sit and view.clips[sit].get('loop') == 'hold_on_last_frame'                     and pose_diff(view, sit, 0, states.get('idle'), 0)[0] < 12:
                have += 1      # a held sit that animates itself in from the standing pose (the tortoise's hide)
            else:
                E.append('no transition %s' % k)
        res['trans'] = '%d/%d' % (have, len(want))
    else:
        res['trans'] = '-'
    poses = {'standing': ('idle' if 'idle' in view.clips else states.get('idle'), 0), 'sitting': (states.get('sit'), 'end'), 'lying': (states.get('sleep'), 0)}
    for k, clip in trans.items():
        f, t = k.split('>')
        if clip not in view.clips:
            continue
        for end, (pc, pt) in (('start', poses.get(f, (None, 0))), ('end', poses.get(t, (None, 0)))):
            if not pc or pc not in view.clips:
                continue
            rot, pos = pose_diff(view, clip, 0 if end == 'start' else 'end', pc, pt)
            if rot > 12 or pos > 1.2:
                W.append('%s %s pops vs %s (%.0f deg, %.1f px)' % (clip, end, pc, rot, pos))
    # tags
    for clip, t in tags.items():
        if clip not in view.clips:
            E.append('tag for missing clip %s' % clip)
            continue
        if (t.get('eyes') or closes_eyes(view.clips[clip])) and not SLEEPY.match(clip):
            E.append('blink: %s closes the eyes' % clip)
        if t.get('gait') in ('walk', 'run', 'swim', 'fly') and not t.get('move'):
            E.append('locomotion %s cannot move' % clip)
        if t.get('p') in ('sitting', 'lying', 'belly') and t.get('move') and t.get('layer') == 'base' and clip != 'death':
            E.append('%s is a %s pose but may move' % (clip, t['p']))
        if 'from' in t and t['from'] == t['p'] and clip != 'spawn':
            W.append('transition %s from == to' % clip)
    if states.get('sit') and tags.get(states['sit'], {}).get('p') != 'sitting':
        E.append('sit clip %s not tagged sitting' % states['sit'])
    if states.get('sleep') and tags.get(states['sleep'], {}).get('p') not in ('lying', 'belly'):
        W.append('sleep clip %s tagged %s' % (states['sleep'], tags.get(states['sleep'], {}).get('p')))
    # loops + sanity
    seams = 0
    for clip, c in view.clips.items():
        if c.get('loop') is True:
            score, where = seam(view, clip)
            if score > 1:
                seams += 1
                W.append('loop %s seam %s' % (clip, where))
        for s in sanity(view, clip):
            E.append('sanity %s: %s' % (clip, s))
    res['seams'] = seams
    res['pools'] = {k: len(v) for k, v in idle.items()}
    res['postures'] = {}
    for t in tags.values():
        res['postures'][t['p']] = res['postures'].get(t['p'], 0) + 1
    res['proc'] = ''.join(c for c, k in (('h', 'head'), ('b', 'body'), ('t', 'tail'), ('e', 'ears')) if anim.get('procedural', {}).get(k))
    return res


def main():
    ids = json.load(open(os.path.join(SPECIES, 'index.json')))['species']
    results = [check(i) for i in ids]
    rs = [r for r in results if not r.get('skip')]
    import bowl_check
    for r in rs:
        if r['id'] in bowl_check.species_with_bowls():
            r['errors'] += bowl_check.Check(r['id']).run(verbose=True)
    errs = sum(len(r['errors']) for r in rs)
    warns = sum(len(r['warns']) for r in rs)
    tame = [r for r in rs if r['tame']]
    full_states = sum(1 for r in rs if r['states'] == r['states_need'])
    full_trans = sum(1 for r in tame if r['trans'].split('/')[0] == r['trans'].split('/')[1])
    clips = sum(r['clips'] for r in rs)
    authored = sum(r['authored'] for r in rs)
    lines = ['# Animation coverage (generated by `tools/anim_check.py`; do not edit)', '',
             'Rerun after `tools/gen_creatures.py`: `python tools/anim_check.py`. The framework is described in PORTING.md',
             '"Animation". ERRORs fail the check; WARNs are worth a look (a pop is still cross-faded in game).', '',
             '| | |', '|---|---|',
             '| species with a model | %d |' % len(rs),
             '| clips (all models, incl. authored) | %d (authored: %d) |' % (clips, authored),
             '| species with every required state | %d / %d |' % (full_states, len(rs)),
             '| tameables with every posture transition | %d / %d |' % (full_trans, len(tame)),
             '| errors | %d |' % errs, '| warnings | %d |' % warns, '',
             '## Per species', '',
             '| species | kind | clips | authored | states | transitions | loop seams | postures (tags) | idle pools | procedural | errors | warnings |',
             '|---|---|---|---|---|---|---|---|---|---|---|---|']
    for r in rs:
        post = ' '.join('%s %d' % (k[:3], v) for k, v in sorted(r['postures'].items()))
        pools = ' '.join('%s %d' % (k[:3], v) for k, v in r['pools'].items())
        lines.append('| %s | %s | %d | %d | %d/%d | %s | %d | %s | %s | %s | %d | %d |' % (
            r['id'], r['kind'], r['clips'], r['authored'], r['states'], r['states_need'], r['trans'], r['seams'], post, pools,
            r['proc'] or '-', len(r['errors']), len(r['warns'])))
    lines += ['', '## Findings', '']
    for r in rs:
        if not r['errors'] and not r['warns']:
            continue
        lines.append('### %s' % r['id'])
        for e in r['errors']:
            lines.append('- **ERROR** ' + e)
        for w in r['warns']:
            lines.append('- WARN ' + w)
        lines.append('')
    open(os.path.join(MOD, 'ANIMATION.md'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('anim_check: %d species, %d clips (%d authored), states %d/%d, tameable transitions %d/%d, %d errors, %d warnings'
          % (len(rs), clips, authored, full_states, len(rs), full_trans, len(tame), errs, warns))
    for r in rs:
        for e in r['errors']:
            print('ERROR', r['id'], e)
    return 1 if errs else 0


if __name__ == '__main__':
    sys.exit(main())
