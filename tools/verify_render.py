#!/usr/bin/env python3
"""Visual equivalence check (dev tool, needs numpy + Pillow and arcana_items/plants3d/mcrender.py): renders each
sampled furniture piece twice with the Minecraft-faithful software renderer -
  A) the original Oraxen item model as the display entity showed it (properties.scale/translation, yaw),
  B) the generated blockstate: every part's variant model at its turned cell offset with the variant y rotation,
and reports the share of pixels that differ. Writes side-by-side PNGs to mod/build/verify_render/.
Run: python mod/tools/verify_render.py [ids...]"""
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
ASSETS = os.path.join(MOD, 'src', 'main', 'resources', 'assets', 'aftrhrs')
sys.path.insert(0, r'C:/Users/kevin/Documents/Blockbench/models/arcana_items/plants3d')
sys.path.insert(0, HERE)
import mcrender as R  # noqa: E402
import miniyaml  # noqa: E402

OUT = os.path.join(MOD, 'build', 'verify_render')
os.makedirs(OUT, exist_ok=True)
MDIRS = [os.path.join(ASSETS, 'models')]
TDIRS = [os.path.join(ASSETS, 'textures')]
CONTENT = json.load(open(os.path.join(MOD, 'src', 'main', 'resources', 'data', 'aftrhrs', 'content', 'content.json'),
                        encoding='utf-8'))
BY_ID = {e['id']: e for e in CONTENT['entries']}
ORAXEN = {}
import glob  # noqa: E402
for f in glob.glob(r'C:/Users/kevin/Desktop/plugins/Oraxen/items/*.yml'):
    if not f.endswith('guis.yml'):
        ORAXEN.update(miniyaml.load(f))


def transformed(model, scale, trans):
    els = []
    for e in model['elements']:
        e = json.loads(json.dumps(e))
        f = [(v - 8) * scale + 8 + 16 * trans[i] for i, v in enumerate(e['from'])]
        t = [(v - 8) * scale + 8 + 16 * trans[i] for i, v in enumerate(e['to'])]
        e['from'], e['to'] = f, t
        if e.get('rotation'):
            o = e['rotation'].get('origin', [8, 8, 8])
            e['rotation']['origin'] = [(v - 8) * scale + 8 + 16 * trans[i] for i, v in enumerate(o)]
        els.append(e)
    return dict(model, elements=els)


def turn(c, q):
    x, y, z = c
    for _ in range(q % 4):
        x, z = -z, x
    return x, y, z


def variant_for(bs, props):
    for key, v in bs['variants'].items():
        kv = dict(p.split('=') for p in key.split(',')) if key else {}
        if all(props.get(k) == val for k, val in kv.items()):
            return v
    return None


def quads_pair(fid, r16):
    e = BY_ID[fid]
    fu = e['furniture']
    cfg = ORAXEN[fid]
    props = (cfg['mechanics']['furniture'].get('properties') or {})
    sc = float((props.get('scale') or {}).get('x', 1.0))
    tr = props.get('translation') or {}
    trans = [float(tr.get(k, 0)) for k in 'xyz']
    qa = R.model_quads(transformed(R.load_model(cfg['pack']['model']), sc, trans), yrot=22.5 * r16)
    bs = json.load(open(os.path.join(ASSETS, 'blockstates', fid + '.json'), encoding='utf-8'))
    if 'variants' not in bs:
        return None, None
    base = {}
    if fu.get('toggle'):
        base[fu['toggle']['property']] = 'true' if fu['toggle']['default'] else 'false'
    if fu.get('jukebox'):
        base['has_record'] = 'false'
    if fu.get('stages'):
        base['age'] = str(len(fu['stages'].get('drops') or [1]) - 1)
    qb = []
    for pi, cell in enumerate(fu['parts']):
        p = dict(base)
        if len(fu['parts']) > 1:
            p['part'] = str(pi)
        if fu['facing'] == 'rot16':
            p['rotation'] = str(r16)
            q = (r16 + 2) // 4 % 4
        else:
            q = r16 // 4
            p['facing'] = ['north', 'east', 'south', 'west'][q]
        v = variant_for(bs, p)
        if v is None:
            return qa, None
        m = R.load_model(v['model'], MDIRS)
        qb += R.model_quads(m, offset=turn(cell, q), yrot=v.get('y', 0), tex_dirs=TDIRS)
    return qa, qb


def sweep():
    bad = 0
    n = 0
    for fid, e in BY_ID.items():
        fu = e.get('furniture')
        if not fu or fid not in ORAXEN or 'mechanics' not in ORAXEN[fid] or fu.get('stages') or fid.endswith('_hearth'):
            continue
        rots = [0, 4] if fu['facing'] != 'rot16' else [0, fu['rot_step'], 4 + fu['rot_step']]
        for r in rots:
            qa, qb = quads_pair(fid, r)
            if qb is None:
                continue
            n += 1
            A = np.array([q[0].ravel() for q in qa])
            B = np.array([q[0].ravel() for q in qb])
            worst = 0.0
            if len(A) != len(B):
                worst = 99
            else:
                used = np.zeros(len(B), bool)
                for a in A:
                    d = np.abs(B - a).max(1)
                    d[used] = 99
                    j = int(np.argmin(d))
                    used[j] = True
                    worst = max(worst, float(d[j]))
            if worst > 2e-3:
                bad += 1
                print('MISMATCH', fid, 'rot', r, len(A), len(B), 'max dev %.4f' % worst)
    print('sweep: %d piece/rotation pairs, %d mismatches' % (n, bad))


def render_pair(fid, r16):
    e = BY_ID[fid]
    fu = e['furniture']
    cfg = ORAXEN[fid]
    props = (cfg['mechanics']['furniture'].get('properties') or {})
    sc = float((props.get('scale') or {}).get('x', 1.0))
    tr = props.get('translation') or {}
    trans = [float(tr.get(k, 0)) for k in 'xyz']
    orig = transformed(R.load_model(cfg['pack']['model']), sc, trans)
    qa = R.model_quads(orig, yrot=22.5 * r16)
    bs = json.load(open(os.path.join(ASSETS, 'blockstates', fid + '.json'), encoding='utf-8'))
    qb = []
    base = {}
    if fu.get('toggle'):
        base[fu['toggle']['property']] = 'true' if fu['toggle']['default'] else 'false'
    if fu.get('jukebox'):
        base['has_record'] = 'false'
    if fu.get('stages'):
        base['age'] = str(len(fu['stages'].get('drops') or [1]) - 1)
    for pi, cell in enumerate(fu['parts']):
        p = dict(base)
        if len(fu['parts']) > 1:
            p['part'] = str(pi)
        if fu['facing'] == 'rot16':
            p['rotation'] = str(r16)
            q = (r16 + 2) // 4 % 4
        else:
            q = r16 // 4
            p['facing'] = ['north', 'east', 'south', 'west'][q]
        v = variant_for(bs, p)
        if v is None:
            print('  no variant', fid, p)
            continue
        m = R.load_model(v['model'], MDIRS)
        qb += R.model_quads(m, offset=turn(cell, q), yrot=v.get('y', 0), tex_dirs=TDIRS)
    xs = [c for quad in qa for c in quad[0]]
    lo, hi = np.min(xs, 0), np.max(xs, 0)
    tgt = (lo + hi) / 2
    dist = float(np.max(hi - lo)) * 2.2 + 1.5
    cam = R.Camera(tgt, dist, 35, 30)
    ia = R.render(qa, cam, 320, 320, ss=1)
    ib = R.render(qb, cam, 320, 320, ss=1)
    a = np.asarray(ia.convert('RGB'), dtype=np.int16) if isinstance(ia, Image.Image) else ia
    b = np.asarray(ib.convert('RGB'), dtype=np.int16) if isinstance(ib, Image.Image) else ib
    diff = np.abs(a.astype(np.int16) - b.astype(np.int16)).max(-1)
    share = float((diff > 40).mean())
    w = Image.new('RGB', (640, 320))
    w.paste(ia if isinstance(ia, Image.Image) else Image.fromarray(a.astype(np.uint8)), (0, 0))
    w.paste(ib if isinstance(ib, Image.Image) else Image.fromarray(b.astype(np.uint8)), (320, 0))
    w.save(os.path.join(OUT, '%s_r%d.png' % (fid, r16)))
    return share


if sys.argv[1:] == ['--all']:
    sweep()
    sys.exit(0)
SAMPLE = sys.argv[1:] or [
    'arcana_oak_chair', 'arcana_copper_street_lamp', 'arcana_iron_grand_lamp_post', 'arcana_plush_emberkit',
    'arcana_oak_porch_swing', 'arcana_quartz_bathtub', 'arcana_stone_arch_wide_quarter', 'arcana_stone_fountain_basin',
    'arcana_gazebo', 'arcana_lantern_hanging_lantern', 'arcana_red_sofa_left', 'arcana_oak_wardrobe',
    'arcana_market_stall_red', 'arcana_stone_hearth', 'arcana_red_armchair', 'arcana_globe', 'arcana_oak_shutters',
    'arcana_red_curtains', 'arcana_wheat_planter', 'arcana_gramophone', 'arcana_lifeguard_tower',
]
worst = 0
for fid in SAMPLE:
    if fid not in BY_ID or 'furniture' not in BY_ID[fid]:
        print('skip', fid)
        continue
    fu = BY_ID[fid]['furniture']
    rots = [0, 1, 2, 3, 5, 10] if fu['facing'] == 'rot16' and fu['rot_step'] == 1 else \
        [0, 2, 6, 10] if fu['facing'] == 'rot16' else [0, 4, 8, 12]
    res = []
    for r in rots:
        s = render_pair(fid, r)
        res.append('%d:%.4f' % (r, s))
        worst = max(worst, s)
    print('%-40s %s' % (fid, ' '.join(res)))
print('worst differing-pixel share: %.4f' % worst)
