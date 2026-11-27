"""Creature sound generator, called by gen_creatures.py (not standalone).

Reads the hand-written profiles in tools/sfx_profiles.py and the 26.3 client asset index (Loom cache), and produces:
  assets/aftrhrs/sounds.json            aftrhrs:<species>.<event> (+ .layer) and aftrhrs:shared.<event>
  assets/aftrhrs/lang/en_us.sounds.json subtitles (folded into en_us.json by the build)
  data/aftrhrs/sfx/shared.json          the shared events (fanfare, tame, revive ...) for the Java side
and returns the per-species "sfx" block that gen_creatures.py writes into data/aftrhrs/species/<id>.json.

Every referenced vanilla sound event and file is checked against the asset index, so a typo fails the build here
instead of turning into a silent "Unable to play unknown soundEvent" on a client.
"""
import fnmatch
import glob
import json
import os

import sfx_profiles as P

DEFAULT_DIST = 16
DEFAULT_JITTER = 0.06
PITCH_MIN, PITCH_MAX = 0.5, 2.0

EVENTS = {
    'ambient', 'ambient_happy', 'ambient_sleepy', 'ambient_scared', 'ambient_angry', 'snore', 'sleep', 'wake', 'pet',
    'happy', 'bond_up', 'bond_level', 'tame', 'tame_fail', 'eat', 'heal', 'hurt', 'death', 'step', 'sit', 'stand',
    'arrive', 'revive', 'ability', 'attack', 'saddle', 'jump', 'land', 'flap', 'gallop', 'splash',
}
# subtitle text when a profile does not name one ({n} = creature name)
DEFAULT_SUBS = {
    'ambient': '{n} calls', 'ambient_happy': '{n} chirps happily', 'ambient_sleepy': '{n} yawns', 'ambient_scared': '{n} whimpers',
    'ambient_angry': '{n} snarls', 'snore': '{n} snores', 'sleep': '{n} dozes off', 'wake': '{n} wakes up', 'pet': '{n} enjoys a pat',
    'happy': '{n} is delighted', 'bond_up': '{n} feels closer', 'bond_level': '{n} bond deepens', 'tame': '{n} bonds with you',
    'tame_fail': '{n} refuses', 'eat': '{n} eats', 'heal': '{n} feels better', 'hurt': '{n} hurts', 'death': '{n} dies',
    'step': 'Footsteps', 'sit': '{n} sits', 'stand': '{n} gets up', 'arrive': '{n} arrives', 'revive': '{n} returns',
    'ability': '{n} uses its gift', 'attack': '{n} attacks', 'saddle': '{n} is mounted', 'jump': '{n} jumps', 'land': '{n} lands',
    'flap': 'Wings flap', 'gallop': '{n} gallops', 'splash': '{n} splashes',
}


def asset_index():
    root = os.path.join(os.path.expanduser('~'), '.gradle', 'caches', 'fabric-loom', 'assets')
    cands = sorted(glob.glob(os.path.join(root, 'indexes', '26.3*.json')))
    if not cands:
        raise SystemExit('sfx_gen: no 26.3 asset index in %s (run a Loom build once)' % root)
    objects = json.load(open(cands[-1], encoding='utf-8'))['objects']
    h = objects['minecraft/sounds.json']['hash']
    vanilla = json.load(open(os.path.join(root, 'objects', h[:2], h), encoding='utf-8'))
    files = sorted(k[len('minecraft/sounds/'):-4] for k in objects if k.startswith('minecraft/sounds/') and k.endswith('.ogg'))
    return vanilla, files


class Builder:
    def __init__(self):
        self.vanilla, self.files = asset_index()
        self.fileset = set(self.files)
        self.errors = []
        self.warnings = []

    # ---------------------------------------------------------------- expansion

    def event_files(self, event, depth=0):
        """Vanilla event -> [(file, volume, pitch, weight)] with nested event references resolved."""
        e = self.vanilla.get(event)
        if e is None:
            self.errors.append('unknown vanilla sound event %s' % event)
            return []
        out = []
        for s in e.get('sounds', []):
            if isinstance(s, str):
                s = {'name': s}
            name = s['name']
            vol, pit, w = s.get('volume', 1.0), s.get('pitch', 1.0), s.get('weight', 1)
            if s.get('type') == 'event' and depth < 4:
                for f, v2, p2, w2 in self.event_files(name.replace('minecraft:', ''), depth + 1):
                    out.append((f, v2 * vol, p2 * pit, w2 * w))
            else:
                out.append((name.replace('minecraft:', ''), vol, pit, w))
        return out

    def ref_files(self, ref):
        if 'event' in ref:
            return self.event_files(ref['event'])
        path = ref['file']
        hits = fnmatch.filter(self.files, path) if any(c in path for c in '*?[') else ([path] if path in self.fileset else [])
        if not hits:
            self.errors.append('unknown vanilla sound file %s' % path)
        return [(f, 1.0, 1.0, 1) for f in hits]

    def entries(self, refs, dist, where):
        out = []
        for ref in refs:
            files = self.ref_files(ref)
            if not files:
                continue
            share = max(1, round(60 * ref.get('w', 1) / len(files)))
            for f, vol, pit, w in files:
                pitch = pit * ref.get('p', 1.0)
                if pitch < PITCH_MIN * 0.9 or pitch > PITCH_MAX * 1.1:   # small overshoots just clamp (as the engine does)
                    self.warnings.append('%s: pitch %.2f of %s clamped' % (where, pitch, f))
                pitch = min(PITCH_MAX, max(PITCH_MIN, pitch))
                e = {'name': 'minecraft:' + f, 'volume': round(min(1.0, vol * ref.get('v', 1.0)), 3), 'pitch': round(pitch, 3),
                     'weight': max(1, int(share * w))}
                if dist != DEFAULT_DIST:
                    e['attenuation_distance'] = int(dist)
                out.append(e)
        return out

    # ---------------------------------------------------------------- build

    def build(self, species):
        """species: the generated species dicts (id, name, kind). Returns (sounds_json, lang, sfx_by_id, shared)."""
        sounds, lang = {}, {}
        sfx_by_id = {}
        ids = {s['id']: s for s in species}
        missing = sorted(set(ids) - set(P.PROFILES))
        extra = sorted(set(P.PROFILES) - set(ids))
        if missing:
            self.errors.append('species without a sound profile: %s' % missing)
        if extra:
            self.errors.append('sound profiles for unknown species: %s' % extra)
        for sid in sorted(ids):
            prof = P.PROFILES.get(sid)
            if prof is None:
                continue
            name = ids[sid]['name']
            dist = prof.get('_dist', DEFAULT_DIST)
            jitter = prof.get('_jitter', DEFAULT_JITTER)
            subs = dict(DEFAULT_SUBS, **prof.get('_subs', {}))
            events = {}
            for ev, spec in prof.items():
                if ev.startswith('_') or spec is None:
                    continue
                base = ev.split('_', 1)[0] if ev.startswith('ability_') else ev
                if base not in EVENTS and not ev.startswith('ability_'):
                    self.errors.append('%s: unknown sound event %s' % (sid, ev))
                    continue
                if isinstance(spec, list):
                    spec = P.E(spec)
                d = spec.get('dist', dist)
                key = '%s.%s' % (sid, ev)
                main = self.entries(spec['sounds'], d, key)
                if not main:
                    self.errors.append('%s: no sounds' % key)
                    continue
                sub_text = spec.get('sub') or subs.get(ev) or subs.get(base) or DEFAULT_SUBS.get(base, '{n}')
                sub_key = 'subtitles.aftrhrs.' + key
                sounds[key] = {'sounds': main, 'subtitle': sub_key}
                lang[sub_key] = sub_text.replace('{n}', name)
                info = {'jitter': spec.get('jitter', jitter)}
                if d != DEFAULT_DIST:
                    info['dist'] = d
                if spec.get('layer'):
                    sounds[key + '.layer'] = {'sounds': self.entries(spec['layer'], d, key + '.layer')}
                    info['layer'] = int(spec.get('delay', 0))
                events[ev] = info
            for need in ('ambient', 'hurt', 'death'):
                if need not in events and ids[sid]['kind'] != 'particles':
                    self.errors.append('%s: profile lacks %s' % (sid, need))
            out = {'events': events, 'every': int(prof.get('_every', 260))}
            if '_snore_every' in prof:
                out['snore_every'] = int(prof['_snore_every'])
            if '_particle' in prof:
                out['particle'] = prof['_particle']
            if dist != DEFAULT_DIST:
                out['dist'] = dist
            sfx_by_id[sid] = out
        shared = {}
        for ev, spec in P.SHARED.items():
            key = 'shared.' + ev
            sub_key = 'subtitles.aftrhrs.' + key
            sounds[key] = {'sounds': self.entries(spec['sounds'], DEFAULT_DIST, key), 'subtitle': sub_key}
            lang[sub_key] = spec.get('sub', ev)
            info = {'jitter': spec.get('jitter', DEFAULT_JITTER)}
            if spec.get('layer'):
                sounds[key + '.layer'] = {'sounds': self.entries(spec['layer'], DEFAULT_DIST, key + '.layer')}
                info['layer'] = int(spec.get('delay', 0))
            shared[ev] = info
        return sounds, lang, sfx_by_id, shared


def generate(species, assets, data, write_json):
    """Called by gen_creatures.main(): writes sounds.json, the subtitles and shared.json; sets s['sfx'] per species."""
    b = Builder()
    sounds, lang, sfx_by_id, shared = b.build(species)
    for w in sorted(set(b.warnings)):
        print('sfx warning:', w)
    if b.errors:
        for e in sorted(set(b.errors)):
            print('sfx ERROR:', e)
        raise SystemExit('sfx_gen: %d error(s)' % len(set(b.errors)))
    for s in species:
        if s['id'] in sfx_by_id:
            s['sfx'] = sfx_by_id[s['id']]
    write_json(os.path.join(assets, 'sounds.json'), dict(sorted(sounds.items())))
    write_json(os.path.join(assets, 'lang', 'en_us.sounds.json'), dict(sorted(lang.items())))
    write_json(os.path.join(data, 'sfx', 'shared.json'), {'events': shared})
    n_layers = sum(1 for k in sounds if k.endswith('.layer'))
    n_entries = sum(len(v['sounds']) for v in sounds.values())
    print('sfx: %d sound events (%d layers, %d variants) for %d species' % (len(sounds), n_layers, n_entries, len(sfx_by_id)))
