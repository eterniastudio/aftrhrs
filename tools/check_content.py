#!/usr/bin/env python3
"""Offline check of the generated content assets (what a 26.x client would reject or show as missing):
every blockstate/item model reference resolves, parents resolve, texture variables resolve to existing textures,
elements have 1..6 faces and from/to inside -16..32, no model mixes the item and block atlases.
Run: python mod/tools/check_content.py  (after gen_content.py)."""
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), 'src', 'main', 'resources', 'assets')
JAR = zipfile.ZipFile(os.path.expanduser(
    '~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/'
    'minecraft-clientonly-deobf-26.3.jar'))
JN = set(JAR.namelist())
errors = []
cache = {}


def norm(ref, kind):
    ns, _, path = ref.partition(':') if ':' in ref else ('minecraft', '', ref)
    return ns, path


def load_model(ref):
    ns, path = norm(ref, 'models')
    key = ns + ':' + path
    if key in cache:
        return cache[key]
    p = os.path.join(RES, ns, 'models', path + '.json')
    m = None
    if os.path.exists(p):
        m = json.load(open(p, encoding='utf-8'))
    elif 'assets/%s/models/%s.json' % (ns, path) in JN:
        m = json.loads(JAR.read('assets/%s/models/%s.json' % (ns, path)))
    cache[key] = m
    return m


def tex_exists(ref):
    ns, path = norm(ref, 'textures')
    return os.path.exists(os.path.join(RES, ns, 'textures', path + '.png')) or \
        'assets/%s/textures/%s.png' % (ns, path) in JN


def atlas(ref):
    ns, path = norm(ref, 'textures')
    return 'items' if path.startswith('item/') else 'blocks'


checked = set()


def check_model(ref, where, block_model):
    key = (ref, block_model)
    if key in checked:
        return
    checked.add(key)
    chain = []
    r = ref
    while r:
        m = load_model(r)
        if m is None:
            if r.replace('minecraft:', '') in ('builtin/generated', 'item/generated', 'builtin/entity'):
                break
            errors.append('%s: missing model %s' % (where, r))
            return
        chain.append(m)
        r = m.get('parent')
        if len(chain) > 20:
            errors.append('%s: parent loop %s' % (where, ref))
            return
    tex = {}
    for m in chain:
        for k, v in (m.get('textures') or {}).items():
            tex.setdefault(k, v)
    elements = next((m['elements'] for m in chain if 'elements' in m), None)

    def resolve(v):
        n = 0
        while v and v.startswith('#') and n < 10:
            v = tex.get(v[1:])
            n += 1
        return v

    used = set()
    for e in elements or []:
        faces = e.get('faces') or {}
        if not 1 <= len(faces) <= 6:
            errors.append('%s (%s): element with %d faces' % (where, ref, len(faces)))
        for v in list(e['from']) + list(e['to']):
            if v < -16 - 1e-3 or v > 32 + 1e-3:
                errors.append('%s (%s): element out of range %s %s' % (where, ref, e['from'], e['to']))
                break
        for f in faces.values():
            t = resolve(f.get('texture', ''))
            if not t:
                errors.append('%s (%s): unresolved texture %s' % (where, ref, f.get('texture')))
                continue
            used.add(t)
    if 'particle' in tex:
        pt = resolve(tex['particle'])
        if pt:
            used.add(pt)
    for t in used:
        if not tex_exists(t):
            errors.append('%s (%s): missing texture %s' % (where, ref, t))
    atl = {atlas(t) for t in used if not t.startswith('#')}
    if elements and len(atl) > 1:
        errors.append('%s (%s): mixes atlases %s' % (where, ref, sorted(used)))
    if block_model and 'items' in atl and elements:
        errors.append('%s (%s): block model uses item atlas %s' % (where, ref, sorted(used)))


def walk_item_model(node, where):
    t = node.get('type', '').replace('minecraft:', '')
    if t == 'model':
        check_model(node['model'], where, False)
    for k in ('on_true', 'on_false', 'fallback', 'model'):
        if isinstance(node.get(k), dict):
            walk_item_model(node[k], where)
    for c in node.get('cases') or []:
        walk_item_model(c['model'], where)


ns_dir = os.path.join(RES, 'aftrhrs')
n_bs = n_items = 0
for f in os.listdir(os.path.join(ns_dir, 'blockstates')):
    bs = json.load(open(os.path.join(ns_dir, 'blockstates', f), encoding='utf-8'))
    n_bs += 1
    variants = []
    if 'variants' in bs:
        for v in bs['variants'].values():
            variants += v if isinstance(v, list) else [v]
    for part in bs.get('multipart') or []:
        a = part['apply']
        variants += a if isinstance(a, list) else [a]
    for v in variants:
        check_model(v['model'], 'blockstate ' + f, True)
for f in os.listdir(os.path.join(ns_dir, 'items')):
    d = json.load(open(os.path.join(ns_dir, 'items', f), encoding='utf-8'))
    n_items += 1
    walk_item_model(d['model'], 'item ' + f)
print('checked %d blockstates, %d item definitions, %d models' % (n_bs, n_items, len(checked)))
for e in errors[:60]:
    print('ERROR', e)
print('%d errors' % len(errors))
sys.exit(1 if errors else 0)
