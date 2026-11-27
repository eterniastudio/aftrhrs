#!/usr/bin/env python3
"""Preview helper: copies a creature's source .bbmodel into a scratch folder with its animations replaced by the
GENERATED GeckoLib clips (including clips authored in tools/clips/), so bbmodel_render / bbmodel_sample_pose / the
Blockbench app can show exactly what the mod plays. The source bbmodel is never written.

  python anim_preview.py <model> [<model> ...]       e.g. python anim_preview.py tabby_cat corgi
  env AFTRHRS_PREVIEW_DIR overrides the scratch folder (default: Documents/Blockbench/models/_fm_anim)
"""
import json
import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import channel_points, load_gecko  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), 'src', 'main', 'resources', 'assets', 'aftrhrs', 'geckolib')
PACK = os.environ.get('ARCANA_PACK', r'C:\Users\kevin\Desktop\plugins\MythicMobs\packs\Arcana')
OUT = os.environ.get('AFTRHRS_PREVIEW_DIR', os.path.join(os.path.expanduser('~'), 'Documents', 'Blockbench', 'models', '_fm_anim'))


def lerp_mode(data, t):
    """The key's GeckoLib lerp_mode at time t (catmullrom keys preview as Blockbench catmullrom, the rest linear)."""
    if isinstance(data, dict) and not any(k in data for k in ('vector', 'pre', 'post')):
        for k, e in data.items():
            try:
                if abs(float(k) - t) < 1e-9 and isinstance(e, dict) and e.get('lerp_mode') == 'catmullrom':
                    return 'catmullrom'
            except ValueError:
                pass
    return 'linear'


def preview(model):
    src = os.path.join(HERE, 'model_overrides', 'arcana_%s.bbmodel' % model)
    if not os.path.isfile(src):
        src = os.path.join(PACK, 'models', 'arcana_%s.bbmodel' % model)
    bb = json.load(open(src, encoding='utf-8'))
    anims = load_gecko(os.path.join(RES, 'animations', 'entity', model + '.animation.json'))['animations']
    by_name = {g['name']: g['uuid'] for g in bb.get('groups', [])}
    out = []
    for name, a in anims.items():
        loop = {True: 'loop', 'hold_on_last_frame': 'hold'}.get(a.get('loop'), 'once')
        animators = {}
        for bone, chs in (a.get('bones') or {}).items():
            u = by_name.get(bone)
            if not u:
                continue
            kfs = []
            for ch, data in chs.items():
                for t, pre, post in channel_points(data):
                    kfs.append({'channel': ch, 'time': t, 'interpolation': lerp_mode(data, t), 'uuid': str(uuid.uuid4()),
                                'data_points': [{'x': post[0], 'y': post[1], 'z': post[2]}]})
            animators[u] = {'name': bone, 'type': 'bone', 'keyframes': kfs}
        out.append({'uuid': str(uuid.uuid4()), 'name': name, 'loop': loop, 'override': False,
                    'length': a.get('animation_length', 1), 'snapping': 20, 'animators': animators})
    bb['animations'] = out
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, model + '.bbmodel')
    json.dump(bb, open(path, 'w', encoding='utf-8'))
    return path


if __name__ == '__main__':
    for m in sys.argv[1:]:
        print(preview(m))
