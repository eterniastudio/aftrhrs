"""Copies the relic models the content generator doesn't reference (spear charge frames, the thrown Tidecaller) from
the Oraxen pack into the mod's aftrhrs_relics namespace, with item definitions, for the relic mechanics
(feature/relics: spear_lunge item_model frames, ThrownRelicTrident renderer). Re-run after the pack changes.
Textures stay in the block atlas the content generator already fills (aftrhrs:block/arcana/relics/*)."""
import json, os

PACK = r'C:/Users/kevin/Desktop/plugins/Oraxen/pack/models'
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets')
NS = 'aftrhrs_relics'
# model path (pack) -> where the textures live in the mod's block atlas
MODELS = ['arcana/relics/frostfang_charge0', 'arcana/relics/frostfang_charge1', 'arcana/relics/ruyi_charge0',
          'arcana/relics/ruyi_charge1', 'arcana/relics/tidecaller_thrown']
# frames the mod already has as content models (aftrhrs:<path>)
EXISTING = ['arcana/relics/frostfang_active', 'arcana/relics/ruyi_active', 'arcana/relics/tidecaller']


def tex(t):
    if t.startswith('#'):
        return t
    if ':' in t:
        ns, p = t.split(':', 1)
        return t if ns != 'minecraft' else 'aftrhrs:block/vanilla/' + p
    return 'aftrhrs:block/' + t


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, separators=(',', ':'))


for m in MODELS:
    with open(os.path.join(PACK, m + '.json'), encoding='utf-8') as f:
        d = json.load(f)
    d.pop('format_version', None)
    d.pop('texture_size', None)
    d['textures'] = {k: tex(v) for k, v in (d.get('textures') or {}).items()}
    write(os.path.join(ROOT, NS, 'models', m + '.json'), d)
    write(os.path.join(ROOT, NS, 'items', m + '.json'), {'model': {'type': 'minecraft:model', 'model': NS + ':' + m}})
for m in EXISTING:
    write(os.path.join(ROOT, NS, 'items', m + '.json'), {'model': {'type': 'minecraft:model', 'model': 'aftrhrs:' + m}})
print('wrote', len(MODELS), 'models,', len(MODELS) + len(EXISTING), 'item definitions')
