#!/usr/bin/env python3
"""Generates the mod's native content (ex-Oraxen items, blocks and furniture) from the Oraxen configs and pack.

Reads (never writes):  Oraxen/items/*.yml, Oraxen/recipes/*.yml, Oraxen/pack/{models,textures},
                       mod/content/{items/*.yml,recipes/*.yml,pack/{models,textures}} (the in-repo content root, same format as
                       Oraxen's: content whose source lives in git, e.g. the fishing species built by
                       tools/fishart/build.py; it is read after Oraxen and wins on equal ids and paths),
                       src/main/resources/feather/fishing.yml (fish species weights, for the fish size models),
                       arcana_items/tab_layout.py (creative tabs), the Minecraft 26.3 client jar (vanilla textures and
                       blockstates to copy from).
Writes (re-runnable; owned output dirs are cleared first):
  src/main/resources/assets/aftrhrs/{models/{arcana,feather,block,gen},textures/block,items,blockstates,lang}
  src/main/resources/data/aftrhrs/{content/content.json,loot_table/blocks,recipe,tags}
  src/main/resources/data/minecraft/tags/{block/mineable,block/wool,item/...}.json  (content tool/mineable tags)

Run: python mod/tools/gen_content.py   (stdlib only; paths overridable with --oraxen, --content, --arcana, --jar)
Ids are kept 1:1 (aftrhrs:<oraxen id>) so configs and the world migration map directly.
"""
import argparse
import ast
import copy
import glob
import hashlib
import json
import math
import os
import re
import shutil
import sys
import zipfile
from collections import Counter, OrderedDict, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geo  # noqa: E402
import miniyaml  # noqa: E402
import mm  # noqa: E402
import pngmini  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'aftrhrs')
DATA = os.path.join(RES, 'data', 'aftrhrs')
NS = 'aftrhrs'

ap = argparse.ArgumentParser()
ap.add_argument('--oraxen', default=r'C:/Users/kevin/Desktop/plugins/Oraxen')
ap.add_argument('--content', default=os.path.join(MOD, 'content'), help='in-repo content root (Oraxen layout)')
ap.add_argument('--arcana', default=r'C:/Users/kevin/Documents/Blockbench/models/arcana_items')
ap.add_argument('--jar', default=None, help='Minecraft 26.3 client jar (vanilla assets)')
args = ap.parse_args()
ORAXEN = args.oraxen
PACK = os.path.join(ORAXEN, 'pack')
CONTENT_ROOT = args.content
# Pack lookups: the in-repo content pack first, then Oraxen's.
PACKS = [p for p in (os.path.join(CONTENT_ROOT, 'pack'), PACK) if os.path.isdir(p)]
JAR_PATH = args.jar or os.path.expanduser(
    '~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/'
    'minecraft-clientonly-deobf-26.3.jar')
JAR = zipfile.ZipFile(JAR_PATH)
JAR_NAMES = set(JAR.namelist())

WARN = []
GAPS = []


def warn(msg):
    WARN.append(msg)


def wjson(path, obj, compact=True):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        if compact:
            json.dump(obj, f, separators=(',', ':'), ensure_ascii=False)
        else:
            json.dump(obj, f, indent=1, ensure_ascii=False)
            f.write('\n')


# ----------------------------------------------------------------------------------------------------------- clean
OWNED = [os.path.join(ASSETS, p) for p in ('models/arcana', 'models/feather', 'models/block', 'models/gen',
                                          'textures/block', 'blockstates')]
OWNED += [os.path.join(DATA, p) for p in ('content', 'loot_table/blocks', 'recipe', 'tags')]
MC_TAGS = os.path.join(RES, 'data', 'minecraft', 'tags')
for p in OWNED:
    shutil.rmtree(p, ignore_errors=True)
# Shared dirs: only what this script (and gen_storage) writes. Spawn egg items and the entity tags belong to
# gen_creatures.py; the other lang parts (en_us.<area>.json) and tags such as climbable are hand-written.
for p in glob.glob(os.path.join(ASSETS, 'items', '*.json')):
    if not p.endswith('_spawn_egg.json'):
        os.remove(p)
if os.path.exists(os.path.join(ASSETS, 'lang', 'en_us.json')):
    os.remove(os.path.join(ASSETS, 'lang', 'en_us.json'))
MC_TAGS_OWNED = ('block/mineable/', 'block/wool.json', 'block/wither_immune.json', 'block/dragon_immune.json', 'item/',
                 'block/walls.json', 'block/stairs.json', 'block/slabs.json')
for p in glob.glob(os.path.join(MC_TAGS, '**', '*.json'), recursive=True):
    rel = os.path.relpath(p, MC_TAGS).replace(os.sep, '/')
    if not rel.startswith(MC_TAGS_OWNED):
        continue
    with open(p, encoding='utf-8') as f:
        mine = '"aftrhrs:' in f.read()
    if mine:
        os.remove(p)

# ------------------------------------------------------------------------------------------------------- load items
ITEMS = OrderedDict()  # id -> (file base, cfg)
for path in sorted(glob.glob(os.path.join(ORAXEN, 'items', '*.yml'))) + sorted(glob.glob(os.path.join(CONTENT_ROOT, 'items', '*.yml'))):
    base = os.path.splitext(os.path.basename(path))[0]
    if base == 'guis':
        continue
    data = miniyaml.load(path) or {}
    for k, v in data.items():
        if isinstance(v, dict):
            ITEMS[k] = (base, v)


def tabs_from_layout():
    p = os.path.join(args.arcana, 'tab_layout.py')
    if not os.path.exists(p):
        return []
    tree = ast.parse(open(p, encoding='utf-8').read())
    ns = {}
    for node in tree.body:
        if isinstance(node, ast.Assign):
            names = [t.id for t in node.targets if isinstance(t, ast.Name)] + \
                    [e.id for t in node.targets if isinstance(t, ast.Tuple) for e in t.elts if isinstance(e, ast.Name)]
            if any(n in ('TABS', 'WOOD', 'STONE', 'FABRIC', 'COPPER', 'LIGHT', 'DECOR', 'UTIL') for n in names):
                exec(compile(ast.Module(body=[node], type_ignores=[]), p, 'exec'), {}, ns)
    return ns.get('TABS', [])


# Near-empty tabs (1-2 items) folded into a related one: a yml naming one of these (by file or `tab:`) lands in the other.
TAB_FOLD = {'arcana': 'arcana_gear', 'arcana_civic_items': 'arcana_lighting', 'arcana_bank': 'arcana_civic',
            'feather_power': 'arcana_utility'}
# Item files whose base is not a tab key of their own, sent to an existing tab (see resolve_tab).
TAB_ALIAS = {}

TABS = [{'key': k, 'slot': s, 'icon': i, 'name': n, 'color': c} for k, s, i, n, c in tabs_from_layout()]
TABS.sort(key=lambda t: t['slot'])
TABS = [t for t in TABS if t['key'] not in TAB_FOLD]
for extra, icon, name, col in (('arcana_bank', 'arcana_goob_coin', 'Bank', '#e6c35c'),
                               ('feather_power', 'feather_coal_generator', 'Power', '#c9a26b'),
                               ('arcana_christmas', 'arcana_christmas_tree', 'Christmas', '#e0533f'),
                               ('arcana_tropical', 'arcana_jungle_tiki_bar', 'Tropical', '#e8a33d')):
    if extra in TAB_FOLD:
        continue
    if extra not in {t['key'] for t in TABS} and any(b == extra for b, _ in ITEMS.values()):
        TABS.append({'key': extra, 'slot': 100 + len(TABS), 'icon': icon, 'name': name, 'color': col})
TAB_KEYS = {t['key'] for t in TABS}


def resolve_tab(fb):
    """The creative tab key for an items file base (or a yml's own `tab:`), None when it maps to none (the Java side
    puts those in the catch-all misc tab). No general prefix match: 'arcana' is a file base too."""
    fb = TAB_FOLD.get(fb, fb)
    if fb in TAB_KEYS:
        return fb
    if fb in TAB_ALIAS:
        return TAB_ALIAS[fb]
    if fb.startswith('arcana_plushies_') and 'arcana_plushies' in TAB_KEYS:
        return 'arcana_plushies'   # arcana_plushies_<set>.yml: one file per plushie set, one tab
    return None

# ------------------------------------------------------------------------------------------------- textures/models
TEX_MAP = {}       # source ref (no namespace) -> our ref "aftrhrs:block/..." or "minecraft:block/..."
TEX_HASH = {}      # sha1 -> our ref
TEX_COPIED = 0
MODEL_DONE = {}    # pack model path -> our ref
MODEL_JSON = {}    # pack model path -> raw json (cached)
FLAME = {'block/fire_0', 'block/fire_1', 'block/soul_fire_0', 'block/soul_fire_1', 'block/campfire_fire',
         'block/soul_campfire_fire', 'block/torch', 'block/soul_torch', 'block/copper_torch', 'block/redstone_torch'}
UNLIT_SWAP = {'block/campfire_log_lit': 'block/campfire_log', 'block/soul_campfire_log_lit': 'block/campfire_log',
              'block/furnace_front_on': 'block/furnace_front', 'block/blast_furnace_front_on': 'block/blast_furnace_front',
              'block/smoker_front_on': 'block/smoker_front', 'block/jack_o_lantern': 'block/carved_pumpkin',
              'block/redstone_lamp_on': 'block/redstone_lamp', 'block/candle_lit': 'block/candle'}
for _c in ('', 'exposed_', 'weathered_', 'oxidized_'):
    UNLIT_SWAP['block/%scopper_bulb_lit' % _c] = 'block/%scopper_bulb' % _c
    UNLIT_SWAP['block/%scopper_bulb_lit_powered' % _c] = 'block/%scopper_bulb_powered' % _c
for _c in ('white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple',
           'blue', 'brown', 'green', 'red', 'black'):
    UNLIT_SWAP['block/%s_candle_lit' % _c] = 'block/%s_candle' % _c
# Light sources with no vanilla "off" texture (lantern glass, froglights, sea lanterns...): the unlit model uses a dimmed
# copy (bright pixels darkened and desaturated, dark frame pixels kept) so switching a lamp off shows.
GLOW_DIM = {'block/lantern', 'block/soul_lantern', 'block/glowstone', 'block/shroomlight', 'block/sea_lantern',
            'arcana/wildlife/fireflies'}
for _c in ('', 'exposed_', 'weathered_', 'oxidized_'):
    GLOW_DIM.add('block/%scopper_lantern' % _c)
for _c in ('ochre', 'verdant', 'pearlescent'):
    GLOW_DIM.update(('block/%s_froglight_side' % _c, 'block/%s_froglight_top' % _c))


def strip_ns(ref):
    return ref.split(':', 1)[1] if ref.startswith('minecraft:') else ref


def pack_tex(path):
    for pk in PACKS:
        p = os.path.join(pk, 'textures', path + '.png')
        if os.path.exists(p):
            return p
    return None


def copy_tex_bytes(data, meta, dest_rel):
    """Writes a texture (dedupe by content) and returns its ref."""
    global TEX_COPIED
    h = hashlib.sha1(data + (meta or b'')).hexdigest()
    if h in TEX_HASH:
        return TEX_HASH[h]
    out = os.path.join(ASSETS, 'textures', 'block', dest_rel + '.png')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'wb') as f:
        f.write(data)
    if meta:
        with open(out + '.mcmeta', 'wb') as f:
            f.write(meta)
    ref = NS + ':block/' + dest_rel
    TEX_HASH[h] = ref
    TEX_COPIED += 1
    return ref


def tex_ref(ref):
    """Maps a model texture reference to the mod's: pack textures are copied into the block atlas
    (assets/aftrhrs/textures/block/...), vanilla block textures stay, vanilla item/entity textures are copied into
    the block atlas so every model uses one atlas (26.x rejects mixed atlases)."""
    if ref.startswith('#'):
        return ref
    src = strip_ns(ref)
    if ':' in src:
        return ref  # other namespaces: leave
    if src in TEX_MAP:
        return TEX_MAP[src]
    p = pack_tex(src)
    if p:
        meta = open(p + '.mcmeta', 'rb').read() if os.path.exists(p + '.mcmeta') else None
        out = copy_tex_bytes(open(p, 'rb').read(), meta, src)
    else:
        jp = 'assets/minecraft/textures/' + src + '.png'
        if jp not in JAR_NAMES:
            warn('missing texture %s' % src)
            out = 'minecraft:' + src
        elif src.startswith('block/'):
            out = 'minecraft:' + src
        else:
            meta = JAR.read(jp + '.mcmeta') if jp + '.mcmeta' in JAR_NAMES else None
            out = copy_tex_bytes(JAR.read(jp), meta, 'vanilla/' + src)
    TEX_MAP[src] = out
    return out


def raw_model(path):
    path = strip_ns(path)
    if path in MODEL_JSON:
        return MODEL_JSON[path]
    m = None
    for pk in PACKS:
        p = os.path.join(pk, 'models', path + '.json')
        if os.path.exists(p):
            m = json.load(open(p, encoding='utf-8'))
            break
    MODEL_JSON[path] = m
    return m


def vanilla_model_exists(path):
    return 'assets/minecraft/models/' + strip_ns(path) + '.json' in JAR_NAMES


KEEP_KEYS = ('parent', 'textures', 'elements', 'display', 'gui_light', 'ambientocclusion')
DROPPED_FACELESS = []


def clean_elements(elements, where):
    out = []
    for e in elements:
        faces = {k: v for k, v in (e.get('faces') or {}).items() if v}
        if not faces:
            DROPPED_FACELESS.append(where)
            continue
        e = dict(e, faces=faces)
        for k in ('name', 'uuid', 'color', 'box_uv', 'locked', 'render_order', 'allow_mirror_modeling', 'origin'):
            if k == 'origin':
                continue
            e.pop(k, None)
        if not geo.in_range(e):
            warn('element out of range in %s: %s %s' % (where, e['from'], e['to']))
        out.append(e)
    return out


def emit_model(path):
    """Copies a pack model (and its pack-local parents) into assets/aftrhrs/models/<path>.json; returns the ref."""
    path = strip_ns(path)
    if path in MODEL_DONE:
        return MODEL_DONE[path]
    m = raw_model(path)
    if m is None:
        if vanilla_model_exists(path):
            MODEL_DONE[path] = 'minecraft:' + path
            return MODEL_DONE[path]
        warn('missing model %s' % path)
        MODEL_DONE[path] = 'minecraft:block/barrier'
        return MODEL_DONE[path]
    ref = NS + ':' + path
    MODEL_DONE[path] = ref
    out = {}
    for k in KEEP_KEYS:
        if k not in m:
            continue
        v = m[k]
        if k == 'parent':
            v = emit_model(v)
        elif k == 'textures':
            v = {tk: tex_ref(tv) for tk, tv in v.items()}
        elif k == 'elements':
            v = clean_elements(v, path)
        out[k] = v
    wjson(os.path.join(ASSETS, 'models', path + '.json'), out)
    return ref


def chain(path):
    """[(path, json)] from the leaf model up to the first model holding elements (pack-local only)."""
    out = []
    p = strip_ns(path)
    while p:
        m = raw_model(p)
        if m is None:
            break
        out.append((p, m))
        if 'elements' in m:
            break
        p = strip_ns(m['parent']) if 'parent' in m else None
    return out


def resolved(path):
    """(elements, merged source textures, ambientocclusion, owner path) of a pack model."""
    ch = chain(path)
    tex = {}
    ao = None
    for p, m in ch:
        for k, v in (m.get('textures') or {}).items():
            tex.setdefault(k, v)
        if ao is None and 'ambientocclusion' in m:
            ao = m['ambientocclusion']
    if not ch or 'elements' not in ch[-1][1]:
        # parent is vanilla (cube_all etc.): load vanilla elements for geometry purposes
        last = ch[-1][1] if ch else {}
        vp = last.get('parent')
        els = []
        while vp:
            vp = strip_ns(vp)
            jp = 'assets/minecraft/models/' + vp + '.json'
            if jp not in JAR_NAMES:
                break
            vm = json.loads(JAR.read(jp))
            for k, v in (vm.get('textures') or {}).items():
                tex.setdefault(k, v)
            if 'elements' in vm:
                els = vm['elements']
                break
            vp = vm.get('parent')
        return clean_elements(copy.deepcopy(els), path), tex, ao, None
    return clean_elements(copy.deepcopy(ch[-1][1]['elements']), path), tex, ao, ch[-1][0]


def resolve_var(tex, ref, depth=0):
    while ref and ref.startswith('#') and depth < 10:
        ref = tex.get(ref[1:])
        depth += 1
    return strip_ns(ref) if ref else None


# ------------------------------------------------------------------------------------------- generated block models
GEN_CACHE = {}
# leaf models by content: a leaf identical to one already written (same geometry and textures: a furniture part or
# rotation that came out the same) reuses that model instead of writing a copy
LEAF_CACHE = {}
GEN_COUNT = Counter()


def gen_model(leaf, elements, tex, ao, key, name):
    """Writes a flattened block model: elements (already transformed) + full texture map. Dedupes by content."""
    body = {'elements': elements}
    if ao is not None:
        body['ambientocclusion'] = ao
    h = hashlib.sha1(json.dumps(body, sort_keys=True).encode()).hexdigest()[:12]
    if h not in GEN_CACHE:
        rel = 'gen/g_' + h
        wjson(os.path.join(ASSETS, 'models', rel + '.json'), body)
        GEN_CACHE[h] = NS + ':' + rel
        GEN_COUNT['geometry'] += 1
    leaf_out = {'parent': GEN_CACHE[h], 'textures': {k: tex_ref(v) for k, v in tex.items()}}
    k = hashlib.sha1(json.dumps(leaf_out, sort_keys=True).encode()).hexdigest()
    if k in LEAF_CACHE:
        GEN_COUNT['leaf_shared'] += 1
        return LEAF_CACHE[k]
    rel = 'block/' + name
    wjson(os.path.join(ASSETS, 'models', rel + '.json'), leaf_out)
    GEN_COUNT['leaf'] += 1
    LEAF_CACHE[k] = NS + ':' + rel
    return LEAF_CACHE[k]


# ---------------------------------------------------------------------------------------------- name/lore helpers
LANG = OrderedDict()


def text_entry(key, src, default_italic=False):
    segs = mm.parse(str(src))
    for s in segs:
        if default_italic is False:
            s.setdefault('italic', False)
    LANG[key] = mm.plain(segs)
    st = mm.style_of(segs)
    if st is not None:
        return {'key': key, 'style': st}
    return {'key': key, 'segments': segs}


# ----------------------------------------------------------------------------------------------------- classify
# Items that survive as dropped entities: money must never be destroyed by the world. Paper's Feather made dropped Goob
# Coins fire-proof; here they also shrug off cactus, explosions, lightning and falling blocks (data tag
# aftrhrs:coin_proof). Only the void, /kill and the usual 5-minute despawn remove them.
DAMAGE_PROOF = {'arcana_goob_coin'}

# Display names that would read the same as another item's: the decorative watering can (furniture) next to the
# working tool arcana_watering_can_tool ("Watering Can").
NAME_OVERRIDES = {'arcana_watering_can': 'Decorative Watering Can'}

FURN_OVERRIDES = {
    # Feather power machines: plain Oraxen items placed by Feather over barriers; here they are real blocks.
    'feather_coal_generator': {'barriers': ['origin', {'y': 1}], 'rotatable': True, 'restricted_rotation': 'VERY_STRICT',
                               'limited_placing': {'floor': True, 'wall': False, 'roof': False},
                               'block_sounds': {k: {'sound': 'block.metal.' + k} for k in ('place', 'break', 'hit', 'step', 'fall')},
                               '_overlay': 'feather/power/feather_coal_generator_fx_on', '_toggle': 'lit',
                               '_toggle_default': False, '_toggle_player': False, '_light': 13},
    'feather_encased_fan': {'barrier': True, 'rotatable': True, 'restricted_rotation': 'VERY_STRICT',
                            'limited_placing': {'floor': True, 'wall': True, 'roof': True},
                            'block_sounds': {k: {'sound': 'block.copper.' + k} for k in ('place', 'break', 'hit', 'step', 'fall')},
                            '_facing': 'all6', '_overlay_static': 'feather/power/feather_encased_fan_propeller',
                            '_powered': True},
}

# Furniture whose block model leaves out some elements (by texture variable) because a block entity renderer draws
# them instead; the item keeps the full model. The chess table's pieces show the live game (feature/chess/).
BER_STRIP = {'arcana_chess_table': ('#white', '#black')}

ENTRIES = []
COUNTS = Counter()
BLOCK_IDS = []          # registered block ids (for tags/loot)
MINEABLE = defaultdict(list)
WOOL = []
ITEM_TAGS = defaultdict(list)
BLOCK_TAGS = defaultdict(list)  # minecraft block tags for shaped blocks (walls connect only to #walls)
LOOT = {}


def sounds(bs):
    out = {}
    for k in ('break', 'step', 'place', 'hit', 'fall'):
        s = (bs or {}).get(k)
        if isinstance(s, dict) and s.get('sound'):
            snd = s['sound']
            out[k] = snd if ':' in snd else 'minecraft:' + snd
    return out


def sound_tool(snd):
    s = snd.get('break', '')
    if any(w in s for w in ('stone', 'deepslate', 'tuff', 'basalt', 'brick', 'calcite', 'metal', 'copper', 'lantern',
                            'chain', 'anvil', 'iron', 'netherite', 'glass', 'amethyst', 'blackstone', 'quartz',
                            'polished', 'nether', 'bone', 'coral', 'dripstone', 'mud_bricks', 'decorated_pot',
                            'heavy_core', 'vault', 'trial', 'lodestone', 'packed_mud', 'resin')):
        return 'pickaxe'
    if any(w in s for w in ('wood', 'bamboo', 'cherry', 'nether_wood', 'ladder', 'scaffolding', 'chiseled_bookshelf',
                            'stem', 'hanging_sign')):
        return 'axe'
    if any(w in s for w in ('grass', 'gravel', 'sand', 'mud', 'snow', 'soul_soil', 'rooted_dirt', 'moss', 'clay')):
        return 'shovel'
    return None


def loot_items(loots, cond=None):
    entries = []
    for l in loots:
        it = l.get('oraxen_item') or l.get('item')
        if it:
            name = NS + ':' + it
        elif l.get('minecraft_type'):
            name = 'minecraft:' + l['minecraft_type'].lower()
        else:
            continue
        e = {'type': 'minecraft:item', 'name': name}
        p = float(l.get('probability', 1.0))
        conds = []
        if p < 1.0:
            conds.append({'type': 'minecraft:random_chance', 'chance': p})
        amt = l.get('amount') or l.get('max_amount')
        if amt and int(amt) > 1:
            e['modifier'] = [{'type': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform',
                                                                            'min': int(l.get('min_amount', 1)),
                                                                            'max': int(amt)}}]
        if conds:
            e['condition'] = cond_of(conds)
        entries.append(e)
    return entries


def loot_table(bid, pools):
    LOOT[bid] = {'type': 'minecraft:block', 'pools': pools, 'random_sequence': NS + ':blocks/' + bid}


def pool(entries, cond=None, silk=False):
    p = {'rolls': 1, 'entries': entries}
    conds = []
    if cond:
        conds.append(cond)
    if silk:
        conds.append('minecraft:tool/can_silk_touch')
    conds.append({'type': 'minecraft:survives_explosion'})
    p['condition'] = cond_of(conds)
    return p


def cond_of(conds):
    return conds[0] if len(conds) == 1 else {'type': 'minecraft:all_of', 'terms': conds}


def match(bid, state):
    return {'type': 'minecraft:match_block', 'blocks': NS + ':' + bid, 'state': state}


# -------------------------------------------------------------------------------------------- furniture geometry
def parse_floats(s):
    return [float(x) for x in re.split(r'[ ,]+', str(s).strip()) if x != '']


def barrier_cells(fu):
    cells = []
    if fu.get('barrier') is True:
        cells.append((0, 0, 0))
    for b in fu.get('barriers') or []:
        if b == 'origin' or b is None:
            cells.append((0, 0, 0))
        elif isinstance(b, dict):
            cells.append((int(b.get('x', 0)), int(b.get('y', 0)), int(b.get('z', 0))))
    return list(dict.fromkeys(cells))


def hitboxes(fu):
    out = []
    for h in fu.get('hitboxes') or []:
        v = parse_floats(h)
        if len(v) >= 5:
            out.append(v[:5])
    return out


def vol_overlap(a, b):
    v = 1.0
    for i in range(3):
        d = min(a[1][i], b[1][i]) - max(a[0][i], b[0][i])
        if d <= 0:
            return 0.0
        v *= d
    return v


def hb_box(h, mx, mz):
    x, y, z, w, hh = h
    cx, cz = 8 + 16 * mx * x, 8 + 16 * mz * z
    return ([cx - 8 * w, 16 * y, cz - 8 * w], [cx + 8 * w, 16 * (y + hh), cz + 8 * w])


def cell_box(c):
    return ([16 * c[0], 16 * c[1], 16 * c[2]], [16 * c[0] + 16, 16 * c[1] + 16, 16 * c[2] + 16])


def clip(box, c, eps=0.25):
    """box ∩ cell c, in cell-local px; None if (nearly) empty."""
    cb = cell_box(c)
    lo = [max(box[0][i], cb[0][i]) - 16 * c[i] for i in range(3)]
    hi = [min(box[1][i], cb[1][i]) - 16 * c[i] for i in range(3)]
    if any(hi[i] - lo[i] < eps for i in range(3)):
        return None
    return [round(v, 3) for v in lo + hi]


def boxes_union_area(boxes):
    return boxes


def element_world(elements, scale, trans):
    return [geo.transform(e, scale, trans, strip_cull=False) for e in elements]


SHUTTER_PARTS = re.compile(r'^(stile_out|rail|panel|louvre)')
CURTAIN_LEFT = {'top', 'upper', 'narrow_y0', 'narrow_y1', 'lower', 'hem'}
CURTAIN_RIGHT = {'top_e', 'upper_e', 'narrow_e_y0', 'narrow_e_y1', 'lower_e', 'hem_e'}


def closed_variant(owner, elements):
    """Generated 'closed' geometry for shutters/curtains (the pack only has the open look). None if unsupported."""
    names = [e.get('name', '') for e in raw_elements(owner)]
    if owner.endswith('woodwork/_shutters'):
        out = []
        for e, n in zip(elements, names):
            if n.startswith('stile_in'):
                continue  # hinge stile: would sit inside the jamb when closed
            if SHUTTER_PARTS.match(n):
                right = n.endswith('_r')
                e = geo.rotate180_y(e, 16.0 if right else 0.0, 15.5)
            out.append(e)
        return out
    if owner.endswith('fabric/_curtains'):
        out = []
        for e, n in zip(elements, names):
            if n.startswith('gather') or n.startswith('tie'):
                continue
            if n in CURTAIN_LEFT or n in CURTAIN_RIGHT:
                e = copy.deepcopy(e)
                x0, x1 = (0.0, 8.0) if n in CURTAIN_LEFT else (8.0, 16.0)
                e['from'][0], e['to'][0] = x0, x1
                if n.startswith('lower'):
                    e['to'][1] = 13.0  # close the gap the removed tie-back leaves
                    for fk in ('north', 'south', 'east', 'west'):
                        if fk in e['faces'] and 'uv' in e['faces'][fk]:
                            uv = e['faces'][fk]['uv']
                            e['faces'][fk]['uv'] = [uv[0], 3.0, uv[2], 11.0]
                for fk, fv in e['faces'].items():
                    if fk in ('north', 'up', 'down') and 'uv' in fv:
                        fv['uv'] = [x0, fv['uv'][1], x1, fv['uv'][3]]
                    elif fk == 'south' and 'uv' in fv:
                        fv['uv'] = [16 - x1, fv['uv'][1], 16 - x0, fv['uv'][3]]
            out.append(e)
        return out
    return None


def raw_elements(owner):
    m = raw_model(owner) if owner else None
    return [e for e in (m or {}).get('elements', []) if e.get('faces')]


DIMMED = {}


def dim_tex(src):
    """Ref of a dimmed copy of a glowing texture (aftrhrs:block/unlit/...), or None if it can't be read."""
    if src in DIMMED:
        return DIMMED[src]
    p = pack_tex(src)
    jp = 'assets/minecraft/textures/' + src + '.png'
    if p:
        data, meta = open(p, 'rb').read(), (open(p + '.mcmeta', 'rb').read() if os.path.exists(p + '.mcmeta') else None)
    elif jp in JAR_NAMES:
        data, meta = JAR.read(jp), (JAR.read(jp + '.mcmeta') if jp + '.mcmeta' in JAR_NAMES else None)
    else:
        DIMMED[src] = None
        return None
    try:
        w, h, px = pngmini.read(data)
    except ValueError as e:
        warn('cannot dim %s: %s' % (src, e))
        DIMMED[src] = None
        return None
    for o in range(0, len(px), 4):
        if not px[o + 3]:
            continue
        r, g, b = px[o], px[o + 1], px[o + 2]
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        t = min(1.0, max(0.0, (lum - 80) / 110))
        if t <= 0:
            continue
        k = 1 - 0.55 * t
        px[o], px[o + 1], px[o + 2] = (int(round((c + (lum - c) * 0.4 * t) * k)) for c in (r, g, b))
    DIMMED[src] = copy_tex_bytes(pngmini.write(w, h, px), meta, 'unlit/' + src)
    return DIMMED[src]


def unlit_variant(elements, tex):
    """Elements without flames + swapped texture map for an unlit light; (elements, tex, changed).
    Pack textures can bring their own off look: <name>_flame textures are flames (dropped), a sibling <name>_unlit.png
    is the switched-off copy, and elements stop glowing (light_emission) when unlit."""
    keep = []
    changed = False
    for e in elements:
        refs = [resolve_var(tex, f.get('texture', '')) for f in e['faces'].values()]
        if refs and all(r in FLAME or (r or '').endswith('_flame') for r in refs):
            changed = True
            continue
        if 'light_emission' in e:
            e = dict(e)
            del e['light_emission']
            changed = True
        keep.append(e)
    tex2 = {}
    for k, v in tex.items():
        r = strip_ns(v) if not v.startswith('#') else v
        if r in UNLIT_SWAP:
            tex2[k] = UNLIT_SWAP[r]
            changed = True
        elif not r.startswith('#') and not r.endswith('_unlit') and pack_tex(r + '_unlit'):
            tex2[k] = r + '_unlit'
            changed = True
        elif r in GLOW_DIM and dim_tex(r):
            tex2[k] = dim_tex(r)
            changed = True
        else:
            tex2[k] = v
    return keep, tex2, changed


class Furn:
    pass


def assign_parts(world, cells, grow=False):
    """Part index per element so every element stays inside -16..32 of the block that renders it. With grow, an element
    no cell can hold gets a new cell (the piece's own geometry); otherwise it goes to the nearest cell (variant models:
    lit hearths, playing gramophone, growth stages, unlit/closed models, whose element lists differ from the base)."""
    out = []
    for e in world:
        mid = [(e['from'][i] + e['to'][i]) / 2 for i in range(3)]
        cands = [i for i, c in enumerate(cells) if geo.in_range(geo.transform(e, shift=[16 * v for v in c], strip_cull=False))]
        if 0 in cands:
            out.append(0)
            continue
        if cands:
            cands.sort(key=lambda i: sum((mid[k] - (16 * cells[i][k] + 8)) ** 2 for k in range(3)))
            out.append(cands[0])
            continue
        c = tuple(int(math.floor(mid[i] / 16)) for i in range(3))
        if c not in cells:
            if not grow:
                out.append(min(range(len(cells)), key=lambda i: sum((mid[k] - (16 * cells[i][k] + 8)) ** 2 for k in range(3))))
                continue
            cells.append(c)
        out.append(cells.index(c))
    return out


def analyze_furniture(fid, cfg, fu):
    f = Furn()
    f.id = fid
    props = fu.get('properties') or {}
    sc = props.get('scale') or {}
    f.scale = float(sc.get('x', 1.0)) if sc else 1.0
    tr = props.get('translation') or {}
    f.trans = [16 * float(tr.get(k, 0.0)) for k in 'xyz'] if tr else [0.0, 0.0, 0.0]
    model = cfg['pack']['model']
    f.model = model
    els, tex, ao, owner = resolved(model)
    f.tex, f.ao, f.owner = tex, ao, owner
    f.src_elements = els
    f.world = element_world(els, f.scale, f.trans)
    ebs = [geo.ebox(e) for e in f.world]
    bars = barrier_cells(fu)
    hbs = hitboxes(fu)
    # Oraxen's offsets mirror model x (FurnitureMechanic); pick the frame that matches the geometry best.
    best, f.mirror = -1.0, (-1, 1)
    for mx, mz in ((-1, 1), (1, 1), (-1, -1), (1, -1)):
        boxes = [cell_box((mx * c[0], c[1], mz * c[2])) for c in bars] + [hb_box(h, mx, mz) for h in hbs]
        score = sum(vol_overlap(b, eb) for b in boxes for eb in ebs)
        if score > best + 1e-6:
            best, f.mirror = score, (mx, mz)
    mx, mz = f.mirror
    f.bars = [(mx * c[0], c[1], mz * c[2]) for c in bars]
    f.hbs = [hb_box(h, mx, mz) for h in hbs]
    cells = [(0, 0, 0)]
    for c in f.bars:
        if c not in cells:
            cells.append(c)
    for b in f.hbs:
        lo = [int(math.floor(b[0][i] / 16)) for i in range(3)]
        hi = [int(math.ceil(b[1][i] / 16)) - 1 for i in range(3)]
        for x in range(lo[0], hi[0] + 1):
            for y in range(lo[1], hi[1] + 1):
                for z in range(lo[2], hi[2] + 1):
                    c = (x, y, z)
                    if c not in cells and clip(b, c, eps=1.0) is not None:
                        cells.append(c)
    # assign elements to parts so every element stays inside -16..32 of the block that renders it
    f.assign = assign_parts(f.world, cells, grow=True)
    f.cells = cells
    # shapes per part (cell-local px, facing north)
    f.collision, f.outline = [], []
    for i, c in enumerate(cells):
        col = [[0, 0, 0, 16, 16, 16]] if c in f.bars else []
        out = list(col)
        for b in f.hbs:
            cb = clip(b, c)
            if cb and not col:
                out.append(cb)
        if not out:
            geo_boxes = [geo.ebox(e) for e, a in zip(f.world, f.assign) if a == i] or \
                        [geo.ebox(e) for e in f.world]
            mn = [min(b[0][k] for b in geo_boxes) for k in range(3)]
            mxx = [max(b[1][k] for b in geo_boxes) for k in range(3)]
            cb = clip((mn, mxx), c)
            out = [cb] if cb else [[4, 0, 4, 12, 4, 12]]
        f.collision.append(col)
        f.outline.append(out)
    # lights per part
    f.light = [0] * len(cells)
    for l in fu.get('lights') or []:
        v = parse_floats(l)
        if len(v) < 4:
            continue
        c = (int(round(mx * v[0])), int(round(v[1])), int(round(mz * v[2])))
        lvl = max(0, min(15, int(v[3])))
        if c in cells:
            i = cells.index(c)
        else:
            i = min(range(len(cells)), key=lambda k: sum((cells[k][j] - c[j]) ** 2 for j in range(3)))
        f.light[i] = max(f.light[i], lvl)
    # seats: [x, y, z, yaw] in blocks from the origin block's min corner (facing north)
    f.seats = []
    for s in fu.get('seats') or []:
        v = parse_floats(s)
        if len(v) < 3:
            continue
        yaw = v[3] if len(v) > 3 else 0.0
        f.seats.append([round((8 + 16 * mx * v[0]) / 16, 4), round(v[1] + 1.975, 4),
                        round((8 + 16 * mz * v[2]) / 16, 4), yaw])
    return f


def facing_mode(fid, fu, f):
    over = FURN_OVERRIDES.get(fid, {})
    if over.get('_facing'):
        return over['_facing'], 4
    rr = (fu.get('restricted_rotation') or 'VERY_STRICT').upper()
    step = {'VERY_STRICT': 4, 'STRICT': 2, 'NONE': 1}.get(rr, 4)
    if len(f.cells) > 1 or step == 4:
        return 'h4', 4
    # finer rotation only if the rotated geometry stays inside the model bounds
    for st in (step, 2):
        ok = True
        for r in range(st, 4, st):
            for e in f.world:
                if not geo.in_range(geo.transform(e, yaw=-22.5 * r, strip_cull=False)):
                    ok = False
                    break
            if not ok:
                break
        if ok:
            return 'rot16', st
    return 'h4', 4


def furniture_models(fid, f, mode, step, variants_spec):
    """Builds the block models. variants_spec: list of (state_suffix, elements, tex) model sources (e.g. lit/unlit).
    Returns {(src_index, part, rot_index): model_ref}."""
    out = {}
    for si, (suffix, elements, tex) in enumerate(variants_spec):
        world = elements
        # a variant model has its own element list (more or fewer elements than the base): assign its own parts
        assign = f.assign if world is f.world else assign_parts(world, f.cells)
        rots = [0] if mode != 'rot16' else list(range(0, 4, step))
        for r in rots:
            for pi, c in enumerate(f.cells):
                els = []
                for e, a in zip(world, assign):
                    if a != pi:
                        continue
                    els.append(geo.transform(e, yaw=-22.5 * r, shift=[16 * v for v in c]))
                name = '%s/%s%s%s' % (fid, suffix or 'base', '_p%d' % pi if len(f.cells) > 1 else '',
                                      '_r%d' % r if r else '')
                plain = (not suffix and r == 0 and pi == 0 and len(f.cells) == 1 and f.scale == 1.0
                         and f.trans == [0, 0, 0] and len(els) == len(f.src_elements) and f.owner is not None)
                if plain:
                    out[(si, pi, r)] = emit_model(f.model)
                else:
                    out[(si, pi, r)] = gen_model(None, els, tex, f.ao, None, name)
    return out


H4 = [('north', 0), ('east', 90), ('south', 180), ('west', 270)]
ALL6 = [('north', 0, 0), ('east', 0, 90), ('south', 0, 180), ('west', 0, 270), ('up', 270, 0), ('down', 90, 0)]


def furniture_blockstate(fid, f, mode, step, models, src_states, extra_props=None, overlay=None, overlay_static=None,
                         static_when=None):
    """src_states: list of (state dict for model-affecting props) aligned with model sources."""
    variants = OrderedDict()
    parts = []

    def put(key, model, x=0, y=0):
        if model is None:
            v = {'model': 'aftrhrs:block/empty'}
        else:
            v = {'model': model}
        if x:
            v['x'] = x
        if y:
            v['y'] = y
        variants[key] = v

    for si, st in enumerate(src_states):
        for pi in range(len(f.cells)):
            base = dict(st)
            if len(f.cells) > 1:
                base['part'] = str(pi)
            if mode == 'h4':
                for d, y in H4:
                    k = dict(base, facing=d)
                    put(','.join('%s=%s' % kv for kv in sorted(k.items())), models[(si, pi, 0)], y=y)
            elif mode == 'all6':
                for d, x, y in ALL6:
                    k = dict(base, facing=d)
                    put(','.join('%s=%s' % kv for kv in sorted(k.items())), models[(si, pi, 0)], x=x, y=y)
            else:
                for r in range(16):
                    rr = r - (r % step)
                    k = dict(base, rotation=str(r))
                    put(','.join('%s=%s' % kv for kv in sorted(k.items())), models[(si, pi, rr % 4)], y=90 * (rr // 4))
    if overlay or overlay_static:
        mp = []
        for key, v in variants.items():
            when = dict(kv.split('=') for kv in key.split(','))
            mp.append({'when': when, 'apply': v})
        for ov, cond in ((overlay, {'lit': 'true'}), (overlay_static, dict(static_when or {}))):
            if not ov:
                continue
            ref = emit_model(ov)
            if mode == 'all6':
                for d, x, y in ALL6:
                    a = {'model': ref}
                    if x:
                        a['x'] = x
                    if y:
                        a['y'] = y
                    w = dict(cond, facing=d)
                    if len(f.cells) > 1:
                        w['part'] = '0'
                    mp.append({'when': w, 'apply': a})
            else:
                for d, y in H4:
                    a = {'model': ref}
                    if y:
                        a['y'] = y
                    w = dict(cond, facing=d)
                    if len(f.cells) > 1:
                        w['part'] = '0'
                    mp.append({'when': w, 'apply': a})
        wjson(os.path.join(ASSETS, 'blockstates', fid + '.json'), {'multipart': mp})
    else:
        wjson(os.path.join(ASSETS, 'blockstates', fid + '.json'), {'variants': variants})


def is_fire_light(f):
    for v in f.tex.values():
        r = strip_ns(v)
        if r in FLAME or r in UNLIT_SWAP or 'candle' in r or 'campfire' in r:
            return True
    return False


# ----------------------------------------------------------------------------------------------------- the passes
HEARTH_LIT = {}  # base id -> lit id
for iid in ITEMS:
    if iid.endswith('_hearth_lit') and iid[:-4] in ITEMS:
        HEARTH_LIT[iid[:-4]] = iid
HEARTH_ALIAS = {v: k for k, v in HEARTH_LIT.items()}

CROP_STAGE = re.compile(r'^arcana_crop_([a-z_]+)_stage(\d+)$')
CROPS = OrderedDict()  # crop name -> {stage: id}
for iid, (fb, cfg) in ITEMS.items():
    m = CROP_STAGE.match(iid)
    b = ((cfg.get('mechanics') or {}).get('block') or {})
    if m and b.get('type') == 'STRING':
        CROPS.setdefault(m.group(1), {})[int(m.group(2))] = iid

CROP_BLOCK_IDS = {}
for crop in CROPS:
    bid = 'arcana_%s_crop' % crop
    assert bid not in ITEMS, bid
    CROP_BLOCK_IDS[crop] = bid

open(os.devnull, 'w')
os.makedirs(ASSETS, exist_ok=True)


def mc_id(v):
    v = str(v)
    return v if ':' in v else 'minecraft:' + v.lower()


def vanilla_components(iid, cfg):
    """Oraxen `components` -> vanilla data component JSON (decoded in Java with the vanilla codecs)."""
    c = dict(cfg.get('components') or {})
    out = OrderedDict()
    special = {}
    if cfg.get('unstackable'):
        out['minecraft:max_stack_size'] = 1
    if 'max_stack_size' in c:
        out['minecraft:max_stack_size'] = int(c.pop('max_stack_size'))
    if 'durability' in c:
        out['minecraft:max_damage'] = int(c.pop('durability'))
        out['minecraft:damage'] = 0
        out['minecraft:max_stack_size'] = 1
    if 'food' in c:
        fd = c.pop('food')
        out['minecraft:food'] = {'nutrition': int(fd.get('nutrition', 0)), 'saturation': float(fd.get('saturation', 0)),
                                 'can_always_eat': bool(fd.get('can_always_eat', False))}
        out['minecraft:consumable'] = {}
    if 'consumable' in c:
        cs = c.pop('consumable')
        o = {}
        if 'consume_seconds' in cs:
            o['consume_seconds'] = float(cs['consume_seconds'])
        if 'animation' in cs:
            o['animation'] = str(cs['animation']).lower()
        if 'sound' in cs:
            o['sound'] = mc_id(cs['sound'])
        if 'has_consume_particles' in cs:
            o['has_consume_particles'] = bool(cs['has_consume_particles'])
        effs = []
        for ef in cs.get('on_consume_effects') or []:
            t = str(ef.get('type', 'apply_effects'))
            if t in ('apply_effects', 'minecraft:apply_effects'):
                lst = []
                for eid, ed in (ef.get('effects') or {}).items():
                    lst.append({'id': mc_id(eid), 'duration': int((ed or {}).get('duration', 200)),
                                'amplifier': int((ed or {}).get('amplifier', 0))})
                effs.append({'type': 'minecraft:apply_effects', 'effects': lst,
                             'probability': float(ef.get('probability', 1.0))})
            else:
                effs.append(dict(ef, type=mc_id(t)))
        if effs:
            o['on_consume_effects'] = effs
        out['minecraft:consumable'] = o
    if 'equippable' in c:
        eq = c.pop('equippable')
        o = {'slot': str(eq.get('slot', 'head')).lower()}
        if 'swappable' in eq:
            o['swappable'] = bool(eq['swappable'])
        out['minecraft:equippable'] = o
    if 'use_cooldown' in c:
        uc = c.pop('use_cooldown')
        o = {'seconds': float(uc.get('seconds', 1))}
        if uc.get('group'):
            o['cooldown_group'] = mc_id(uc['group'])
        out['minecraft:use_cooldown'] = o
    if 'use_remainder' in c:
        ur = c.pop('use_remainder')
        it = ur.get('oraxen_item')
        out['minecraft:use_remainder'] = {'id': (NS + ':' + it) if it else mc_id(ur.get('minecraft_type', 'air')),
                                          'count': int(ur.get('amount', 1))}
    if 'enchantment_glint_override' in c:
        out['minecraft:enchantment_glint_override'] = bool(c.pop('enchantment_glint_override'))
    if iid in DAMAGE_PROOF:
        c.pop('fire_resistant', None)
        special['damage_resistant'] = NS + ':coin_proof'   # a damage-type tag; set in Java (no registry to parse it here)
    elif c.pop('fire_resistant', False):
        special['fire_resistant'] = True
    for k in list(c):
        warn('%s: unhandled component %s' % (iid, k))
    return out, special


def base_entry(iid, fb, cfg):
    e = OrderedDict()
    e['id'] = iid
    fb = cfg.get('tab') or fb  # in-repo content may name its creative tab (an Oraxen items file base)
    e['tab'] = resolve_tab(fb)
    e['hidden'] = bool(cfg.get('excludeFromInventory'))
    if e['tab'] is None and not e['hidden']:
        warn('%s: file %s maps to no creative tab (falls into misc)' % (iid, fb))
    src = str(NAME_OVERRIDES.get(iid) or cfg.get('itemname') or cfg.get('displayname') or iid)
    if iid in HEARTH_ALIAS:
        # the lit hearth is its own item: "Lit Cobblestone Hearth" (after any leading MiniMessage tags)
        m = re.match(r'^((?:<[^>]*>)*)(.*)$', src, re.S)
        src = m.group(1) + 'Lit ' + m.group(2)
    e['name'] = text_entry('item.%s.%s' % (NS, iid), src)
    LANG['block.%s.%s' % (NS, iid)] = LANG['item.%s.%s' % (NS, iid)]
    e['lore'] = [text_entry('item.%s.%s.lore.%d' % (NS, iid, i), l) for i, l in enumerate(cfg.get('lore') or [])]
    e['base'] = (cfg.get('material') or 'PAPER').upper()
    comps, special = vanilla_components(iid, cfg)
    if comps:
        e['components'] = comps
    if special:
        e.update(special)
    attrs = []
    for k, a in (cfg.get('AttributeModifiers') or {}).items():
        if not isinstance(a, dict):
            continue
        at = str(a.get('attribute', '')).lower()
        for pre in ('generic_', 'player_'):
            if at.startswith(pre):
                at = at[len(pre):]
        op = {'ADD_NUMBER': 'add_value', 'ADD_SCALAR': 'add_multiplied_base',
              'MULTIPLY_SCALAR_1': 'add_multiplied_total'}.get(str(a.get('operation', 'ADD_NUMBER')).upper(), 'add_value')
        slot = str(a.get('slot', 'ANY')).lower()
        slot = {'hand': 'mainhand'}.get(slot, slot)
        attrs.append({'type': 'minecraft:' + at, 'id': '%s:%s/%s' % (NS, iid, k.lower()),
                      'amount': float(a.get('amount', 0)), 'operation': op, 'slot': slot})
    if attrs:
        e.setdefault('components', OrderedDict())['minecraft:attribute_modifiers'] = attrs
    mech = dict(cfg.get('mechanics') or {})
    if 'commands' in mech:
        e['commands'] = mech.pop('commands')
    if 'backpack' in mech:
        bp = mech.pop('backpack')
        e['backpack'] = {'rows': int(bp.get('rows', 3)),
                         'title': text_entry('container.%s.%s' % (NS, iid), bp.get('title') or cfg.get('itemname')),
                         'open_sound': bp.get('open_sound'), 'close_sound': bp.get('close_sound'),
                         'blocked': [b.replace('oraxen:', NS + ':') if ':' in b else 'minecraft:' + b
                                     for b in bp.get('blocked-items') or []]}
    if 'bottledexp' in mech:
        e['xp_flask'] = {'ratio': float(mech.pop('bottledexp').get('ratio', 0.5))}
    for k in ('furniture', 'block'):
        mech.pop(k, None)
    if mech:
        e['mechanics'] = mech
        GAPS.append('%s: Oraxen mechanics %s kept in the content table (behavior not ported)' % (iid, sorted(mech)))
    if cfg.get('glowing'):
        e['glowing'] = cfg['glowing']
    return e


def pack_model_ref(cfg):
    pk = cfg.get('pack') or {}
    if pk.get('model'):
        return emit_model(pk['model'])
    return 'minecraft:item/' + str(cfg.get('material', 'paper')).lower()


# ------------------------------------------------------------------------------------------------- fish sizes
# A caught fish is drawn at a size that goes with its weight (FishWeight.java, same constants): the length scale
# (grams / 1500)^(1/3), eased into [0.4, 2.2]. Feather writes it as custom model data float 0; the item model picks the
# size step (8% apart) by range dispatch and scales the model about its centre with an item-model transformation. The
# GUI, shelves and frames/plaques/tanks ("fixed": Feather scales those displays itself) keep the model's own size; without a
# value (commons carry no weight, old catches) the species' typical weight sets the size.
FISH_SIZE_REF, FISH_SIZE_MIN, FISH_SIZE_MAX, FISH_SIZE_STEP = 1500.0, 0.4, 2.2, 1.08


def fish_length_scale(grams):
    x = math.log(max(0.01, grams) / FISH_SIZE_REF) / 3.0
    hi, lo = math.log(FISH_SIZE_MAX), math.log(FISH_SIZE_MIN)
    y = hi * math.tanh(x / hi) if x >= 0 else lo * math.tanh(x / lo)
    return math.exp(y)


def fish_weights():
    """{species: (min, typical, max) grams} from fishing.yml (each species is one flow mapping, maybe wrapped)."""
    path = os.path.join(RES, 'feather', 'fishing.yml')
    if not os.path.exists(path):
        return {}
    txt = open(path, encoding='utf-8').read().replace('\r\n', '\n')
    if '\nspecies:\n' not in txt:
        return {}
    sec = txt.split('\nspecies:\n', 1)[1]
    sec = re.split(r'\n[a-z]', sec, maxsplit=1)[0]
    out = {}
    for m in re.finditer(r'^  ([a-z0-9_]+): \{(.*?)\}\s*$', re.sub(r'\n\s{6,}', ' ', sec), re.M):
        g = re.search(r'grams: \[(\d+), (\d+), (\d+)\]', m.group(2))
        if g:
            out[m.group(1)] = tuple(int(v) for v in g.groups())
    return out


FISH_WEIGHTS = fish_weights()


def scaled(model, s):
    s = round(s, 4)
    t = round(0.5 * (1 - s), 4)
    return dict(model, transformation={'translation': [t, t, t], 'left_rotation': [0, 0, 0, 1], 'scale': [s, s, s],
                                       'right_rotation': [0, 0, 0, 1]})


def fish_size_model(species, base):
    """The item model of a fish species: GUI and fixed as modelled, elsewhere scaled by the stack's weight."""
    lo_g, typ_g, hi_g = FISH_WEIGHTS[species]
    s_lo, s_typ, s_hi = fish_length_scale(lo_g), fish_length_scale(typ_g), fish_length_scale(hi_g)
    steps = int(round(math.log(FISH_SIZE_MAX / FISH_SIZE_MIN) / math.log(FISH_SIZE_STEP)))
    entries = []
    for k in range(steps + 1):
        c = FISH_SIZE_MIN * FISH_SIZE_STEP ** k
        lo_edge = c / math.sqrt(FISH_SIZE_STEP)
        if c * math.sqrt(FISH_SIZE_STEP) < s_lo or lo_edge > s_hi:
            continue
        entries.append({'threshold': round(lo_edge, 4), 'model': scaled(base, c)})
    entries[0]['threshold'] = round(min(entries[0]['threshold'], s_lo - 0.001), 4)
    sized = {'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
             'entries': entries, 'fallback': scaled(base, s_typ)}
    return {'type': 'minecraft:select', 'property': 'minecraft:display_context',
            'cases': [{'when': ['gui', 'fixed', 'on_shelf'], 'model': base}], 'fallback': sized}


def item_model_def(iid, cfg, model_ref):
    pk = cfg.get('pack') or {}
    m = {'type': 'minecraft:model', 'model': model_ref}
    # pack.state_models: {property: {value: model}}: a block item whose stack carries that block state (a wrapped
    # present's open=false) shows that model instead.
    for prop, cases in (pk.get('state_models') or {}).items():
        m = {'type': 'minecraft:select', 'property': 'minecraft:block_state', 'block_state_property': str(prop),
             'cases': [{'when': str(val).lower(), 'model': {'type': 'minecraft:model', 'model': emit_model(path)}}
                       for val, path in cases.items()], 'fallback': m}
    if iid.startswith('arcana_fish_') and iid[len('arcana_fish_'):] in FISH_WEIGHTS:
        m = fish_size_model(iid[len('arcana_fish_'):], m)
    if pk.get('cast_model'):
        m = {'type': 'minecraft:condition', 'property': 'minecraft:fishing_rod/cast',
             'on_false': m, 'on_true': {'type': 'minecraft:model', 'model': emit_model(pk['cast_model'])}}
    mech = cfg.get('mechanics') or {}
    if mech.get('trident'):
        ap_ = mech['trident'].get('appearance') or {}
        if ap_.get('charging-model'):
            m = {'type': 'minecraft:condition', 'property': 'minecraft:using_item',
                 'on_false': m, 'on_true': {'type': 'minecraft:model', 'model': emit_model(ap_['charging-model'])}}
    if mech.get('spear_lunge') and mech['spear_lunge'].get('active_model'):
        m = {'type': 'minecraft:condition', 'property': 'minecraft:using_item',
             'on_false': m, 'on_true': {'type': 'minecraft:model', 'model': emit_model(mech['spear_lunge']['active_model'])}}
    wjson(os.path.join(ASSETS, 'items', iid + '.json'), {'model': m})


def add_mineable(bid, tool):
    if tool in ('pickaxe', 'axe', 'shovel', 'hoe'):
        MINEABLE[tool].append(NS + ':' + bid)


for iid, (fb, cfg) in ITEMS.items():
    mech = cfg.get('mechanics') or {}
    pk = cfg.get('pack') or {}
    fu = mech.get('furniture')
    if iid in FURN_OVERRIDES:
        fu = dict(FURN_OVERRIDES[iid])
    blk = mech.get('block')
    e = base_entry(iid, fb, cfg)

    # --------------------------------------------------------------------------------------------- crop stages
    m = CROP_STAGE.match(iid)
    if m and blk and blk.get('type') == 'STRING':
        e['kind'] = 'crop_stage'
        e['crop_stage'] = {'block': CROP_BLOCK_IDS[m.group(1)], 'age': int(m.group(2))}
        e['hidden'] = True
        emit_model(blk.get('appearance', {}).get('model') or pk['model'])
        item_model_def(iid, cfg, pack_model_ref(cfg))
        ENTRIES.append(e)
        COUNTS['crop stage (item alias of a crop block state)'] += 1
        continue

    # ------------------------------------------------------------------------------------------- hearth _lit
    if iid in HEARTH_ALIAS:
        e['kind'] = 'alias'
        e['alias'] = {'block': HEARTH_ALIAS[iid], 'state': {'lit': 'true'}}
        item_model_def(iid, cfg, pack_model_ref(cfg))
        ENTRIES.append(e)
        COUNTS['alias item (lit hearth -> hearth block lit=true)'] += 1
        continue

    # -------------------------------------------------------------------------------------------- furniture
    if fu:
        # FURN_OVERRIDES keys can also come from the item's own `furniture.aftrhrs:` block (in-repo content):
        # _toggle/_toggle_default/_toggle_player/_toggle_sound, _off_model (the model while the toggle is off),
        # _stage_light (light level per growth stage).
        over = dict(FURN_OVERRIDES.get(iid, {}))
        over.update(fu.get('aftrhrs') or {})
        f = analyze_furniture(iid, cfg, fu)
        mode, step = facing_mode(iid, fu, f)
        lp = fu.get('limited_placing') or {'floor': True, 'wall': False, 'roof': False}
        fe = OrderedDict()
        fe['facing'] = mode
        fe['rot_step'] = step
        fe['rotatable'] = bool(fu.get('rotatable', True))
        fe['place'] = {k: bool(lp.get(k, False)) for k in ('floor', 'wall', 'roof')}
        fe['parts'] = [list(c) for c in f.cells]
        fe['collision'] = f.collision
        fe['outline'] = f.outline
        fe['seats'] = f.seats
        snd = sounds(fu.get('block_sounds'))
        fe['sounds'] = snd
        storage = fu.get('storage')
        if storage:
            fe['storage'] = {'type': str(storage.get('type', 'STORAGE')).upper(), 'rows': int(storage.get('rows', 3)),
                             'title': text_entry('container.%s.%s' % (NS, iid), storage.get('title') or cfg.get('itemname')),
                             'open_sound': storage.get('open_sound'), 'close_sound': storage.get('close_sound'),
                             'volume': float(storage.get('volume', 1.0)), 'pitch': float(storage.get('pitch', 1.0))}
        if fu.get('jukebox'):
            fe['jukebox'] = {'volume': float(fu['jukebox'].get('volume', 1.0)), 'pitch': float(fu['jukebox'].get('pitch', 1.0))}
        stages = fu.get('stages')
        light = list(f.light)
        if over.get('_light'):
            light[0] = over['_light']
        # model sources + model-affecting state
        sources = []   # (suffix, elements, tex)
        states = []    # dict
        toggle, tdef, tplayer, tsound = None, True, True, None
        busy = bool(storage or fu.get('jukebox') or stages or f.seats)
        if iid in HEARTH_LIT:
            lit_id = HEARTH_LIT[iid]
            lcfg = ITEMS[lit_id][1]
            lf = analyze_furniture(lit_id, lcfg, lcfg['mechanics']['furniture'])
            toggle, tdef, tplayer, tsound = 'lit', False, False, 'fire'
            light = [max(a, b) for a, b in zip(lf.light + [0] * len(f.cells), light)]
            sources = [('', f.world, f.tex), ('lit', lf.world, lf.tex)]
            states = [{'lit': 'false'}, {'lit': 'true'}]
            fe['hearth'] = {'lit_item': lit_id}
        elif over.get('_toggle'):
            toggle, tdef, tplayer = over['_toggle'], over.get('_toggle_default', True), over.get('_toggle_player', True)
            tsound = over.get('_toggle_sound')
            sources = [('', f.world, f.tex)]
            states = [{}]
            if over.get('_off_model'):
                els, tex, ao, owner = resolved(over['_off_model'])
                sources.append(('closed' if toggle == 'open' else 'unlit', element_world(els, f.scale, f.trans), tex))
                states = [{toggle: 'true'}, {toggle: 'false'}]
        elif stages:
            n = len(stages)
            models = pk.get('models') or {}
            for i, sdef in enumerate(stages):
                mref = models.get(sdef.get('model')) or f.model
                els, tex, ao, owner = resolved(mref)
                sources.append(('age%d' % i, element_world(els, f.scale, f.trans), tex))
                states.append({'age': str(i)})
            ev = (stages[0].get('evolution') or {})
            delay, prob = float(ev.get('delay', 12000)), float(ev.get('probability', 1.0))
            lb = ev.get('light_boost') or {}
            fe['stages'] = {'count': n, 'chance': round(min(1.0, 1365.0 * prob / max(1.0, delay)), 5),
                            'light_min': int(lb.get('minimum_light_level', 0)),
                            'boost_chance': round(min(1.0, 1365.0 * prob / max(1.0, delay - float(lb.get('boost_tick', 0)))), 5),
                            'bonemeal': float((ev.get('bone_meal') or {}).get('chance', 0)) / 100.0,
                            'initial': int(fu.get('initial_stage', 0))}
            for i, sdef in enumerate(stages):
                d = sdef.get('drop') or fu.get('drop') or {}
                fe['stages'].setdefault('drops', []).append([l.get('oraxen_item') for l in d.get('loots') or []])
            if over.get('_stage_light'):
                fe['stages']['light'] = [max(0, min(15, int(v))) for v in over['_stage_light']]
        elif fu.get('jukebox'):
            models = pk.get('models') or {}
            act = models.get(fu['jukebox'].get('active_model'))
            sources = [('', f.world, f.tex)]
            states = [{'has_record': 'false'}]
            if act:
                els, tex, ao, owner = resolved(act)
                sources.append(('playing', element_world(els, f.scale, f.trans), tex))
                states.append({'has_record': 'true'})
            else:
                states = [{}]
        elif any(light) and not busy:
            toggle, tdef, tplayer = 'lit', True, True
            tsound = 'fire' if is_fire_light(f) else 'lamp'
            keep, tex2, changed = unlit_variant(f.world, f.tex)
            if changed:
                sources = [('', f.world, f.tex), ('unlit', keep, tex2)]
                states = [{'lit': 'true'}, {'lit': 'false'}]
            else:
                sources = [('', f.world, f.tex)]
                states = [{}]
        elif f.owner and (f.owner.endswith('woodwork/_shutters') or f.owner.endswith('fabric/_curtains')) and not busy:
            closed = closed_variant(f.owner, f.world)
            toggle, tdef, tplayer = 'open', True, True
            tsound = 'wood' if 'shutters' in f.owner else 'cloth'
            sources = [('', f.world, f.tex), ('closed', closed, f.tex)]
            states = [{'open': 'true'}, {'open': 'false'}]
        else:
            world = f.world
            if iid in BER_STRIP:
                world = [el for el in f.world
                         if not all(fv.get('texture') in BER_STRIP[iid] for fv in el['faces'].values())]
            sources = [('', world, f.tex)]
            states = [{}]
        if toggle:
            fe['toggle'] = {'property': toggle, 'default': tdef, 'player': tplayer, 'sound': tsound}
        if over.get('_powered'):
            fe['powered'] = True
        fe['light'] = light
        models = furniture_models(iid, f, mode, step, sources)
        furniture_blockstate(iid, f, mode, step, models, states, overlay=over.get('_overlay'),
                             overlay_static=over.get('_overlay_static'),
                             # a powered fan's propeller is drawn spinning by the power module's block entity renderer
                             static_when={'powered': 'false'} if over.get('_powered') else None)
        e['kind'] = 'furniture'
        e['furniture'] = fe
        tool = sound_tool(snd)
        add_mineable(iid, tool)
        item_model_def(iid, cfg, pack_model_ref(cfg))
        # loot
        d = fu.get('drop') or {'loots': [{'oraxen_item': iid, 'probability': 1.0}]}
        cond = match(iid, {'part': '0'}) if len(f.cells) > 1 else None
        if iid in HEARTH_LIT:
            lit_id = HEARTH_LIT[iid]
            pools = [pool(loot_items(d.get('loots') or []), match(iid, dict({'lit': 'false'}, **({'part': '0'} if cond else {})))),
                     pool([{'type': 'minecraft:item', 'name': NS + ':' + lit_id}],
                          match(iid, dict({'lit': 'true'}, **({'part': '0'} if cond else {}))))]
        elif stages:
            pools = []
            for i, ids in enumerate(fe['stages'].get('drops') or []):
                pools.append(pool(loot_items([{'oraxen_item': x, 'probability': 1.0} for x in ids if x]),
                                  match(iid, {'age': str(i)})))
        else:
            loots = d.get('loots') or []
            if not loots and storage and str(storage.get('type', '')).upper() == 'SHULKER':
                loots = [{'oraxen_item': iid, 'probability': 1.0}]  # Feather's sacks dropped themselves with contents
            items = loot_items(loots)
            if storage and str(storage.get('type', '')).upper() == 'SHULKER':
                for it in items:
                    it['modifier'] = {'type': 'minecraft:copy_components', 'source': 'block_entity',
                                      'include': ['minecraft:custom_name', 'minecraft:container']}
            pools = [pool(items, cond, silk=bool(d.get('silktouch')))]
        loot_table(iid, pools)
        BLOCK_IDS.append(iid)
        ENTRIES.append(e)
        COUNTS['furniture'] += 1
        if len(f.cells) > 1:
            COUNTS['  of which multi-block'] += 1
        if mode == 'rot16':
            COUNTS['  of which %d-way rotation' % (16 // step)] += 1
        if storage:
            COUNTS['  of which storage'] += 1
        if f.seats:
            COUNTS['  of which seats'] += 1
        if toggle:
            COUNTS['  of which toggle %s' % toggle] += 1
        continue

    # ------------------------------------------------------------------------------------------------ blocks
    if blk:
        bt = str(blk.get('type', 'FULL')).upper()
        snd = sounds(blk.get('block_sounds'))
        brk = blk.get('breaking') or []
        hardness, tool, requires, drops = 1.0, None, False, None
        for rule in brk:
            if rule.get('when'):
                w = [str(x) for x in rule['when']]
                tool = {'#minecraft:pickaxes': 'pickaxe', '#minecraft:axes': 'axe', '#minecraft:shovels': 'shovel',
                        '#minecraft:hoes': 'hoe', 'minecraft:shears': 'shears'}.get(w[0])
                drops = rule.get('drops')
            else:
                hardness = float(rule.get('hardness', 1.0))
                if not rule.get('drops') and tool:
                    requires = True
                if drops is None or not tool:
                    drops = rule.get('drops')
        be = OrderedDict(hardness=hardness, tool=tool, requires_tool=requires, light=int(blk.get('light', 0) or 0),
                         sounds=snd)
        # In-repo block sets (mod/content/items) may also set slipperiness and a map color.
        if blk.get('friction') is not None:
            be['friction'] = float(blk['friction'])
        if blk.get('map_color'):
            be['map_color'] = str(blk['map_color'])
        e['block'] = be
        if tool == 'shears':
            WOOL.append(NS + ':' + iid)
        else:
            add_mineable(iid, tool)
        loot_table(iid, [pool(loot_items([{'oraxen_item': d.get('item'), 'probability': d.get('probability', 1.0),
                                           'amount': d.get('max_amount')} for d in drops or []]))] if drops else [])
        if bt == 'FULL':
            e['kind'] = 'block'
            mref = emit_model((blk.get('appearance') or {}).get('model') or pk['model'])
            wjson(os.path.join(ASSETS, 'blockstates', iid + '.json'), {'variants': {'': {'model': mref}}})
            item_model_def(iid, cfg, pack_model_ref(cfg))
            COUNTS['full block'] += 1
        elif bt in ('STAIR', 'SLAB', 'WALL'):
            tex = tex_ref((blk.get('textures') or {}).get('all') or 'block/stone')
            if bt == 'WALL':
                e['kind'] = 'wall'
                for suf, par in (('_post', 'template_wall_post'), ('_side', 'template_wall_side'),
                                 ('_side_tall', 'template_wall_side_tall'), ('_inventory', 'wall_inventory')):
                    wjson(os.path.join(ASSETS, 'models', 'block', iid + suf + '.json'),
                          {'parent': 'minecraft:block/' + par, 'textures': {'wall': tex}})
                bs = json.loads(JAR.read('assets/minecraft/blockstates/cobblestone_wall.json').decode())
                s = json.dumps(bs).replace('minecraft:block/cobblestone_wall', '%s:block/%s' % (NS, iid))
                wjson(os.path.join(ASSETS, 'blockstates', iid + '.json'), json.loads(s))
                wjson(os.path.join(ASSETS, 'items', iid + '.json'),
                      {'model': {'type': 'minecraft:model', 'model': '%s:block/%s_inventory' % (NS, iid)}})
                BLOCK_TAGS['walls'].append(NS + ':' + iid)
                COUNTS['wall'] += 1
                BLOCK_IDS.append(iid)
                ENTRIES.append(e)
                continue
            if bt == 'STAIR':
                e['kind'] = 'stairs'
                for suf, par in (('', 'stairs'), ('_inner', 'inner_stairs'), ('_outer', 'outer_stairs')):
                    wjson(os.path.join(ASSETS, 'models', 'block', iid + suf + '.json'),
                          {'parent': 'minecraft:block/' + par, 'textures': {'bottom': tex, 'top': tex, 'side': tex}})
                bs = json.loads(JAR.read('assets/minecraft/blockstates/oak_stairs.json').decode())
                s = json.dumps(bs).replace('minecraft:block/oak_stairs', '%s:block/%s' % (NS, iid))
                wjson(os.path.join(ASSETS, 'blockstates', iid + '.json'), json.loads(s))
                BLOCK_TAGS['stairs'].append(NS + ':' + iid)
                COUNTS['stairs'] += 1
            else:
                e['kind'] = 'slab'
                wjson(os.path.join(ASSETS, 'models', 'block', iid + '.json'),
                      {'parent': 'minecraft:block/slab', 'textures': {'bottom': tex, 'top': tex, 'side': tex}})
                wjson(os.path.join(ASSETS, 'models', 'block', iid + '_top.json'),
                      {'parent': 'minecraft:block/slab_top', 'textures': {'bottom': tex, 'top': tex, 'side': tex}})
                wjson(os.path.join(ASSETS, 'models', 'block', iid + '_double.json'),
                      {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex}})
                wjson(os.path.join(ASSETS, 'blockstates', iid + '.json'), {'variants': {
                    'type=bottom': {'model': '%s:block/%s' % (NS, iid)},
                    'type=top': {'model': '%s:block/%s_top' % (NS, iid)},
                    'type=double': {'model': '%s:block/%s_double' % (NS, iid)}}})
                LOOT[iid]['pools'] = [pool([{'type': 'minecraft:item', 'name': NS + ':' + iid, 'modifier': [
                    {'type': 'minecraft:set_count', 'count': 2, 'condition': match(iid, {'type': 'double'})}]}])]
                BLOCK_TAGS['slabs'].append(NS + ':' + iid)
                COUNTS['slab'] += 1
            wjson(os.path.join(ASSETS, 'items', iid + '.json'),
                  {'model': {'type': 'minecraft:model', 'model': '%s:block/%s' % (NS, iid)}})
        else:
            warn('unsupported block type %s for %s' % (bt, iid))
            continue
        BLOCK_IDS.append(iid)
        ENTRIES.append(e)
        continue

    # ------------------------------------------------------------------------------------------------- items
    e['kind'] = 'item'
    comps = e.get('components') or {}
    if (comps.get('minecraft:equippable') or {}).get('slot', '') == 'head':
        COUNTS['hat (head-equippable item)'] += 1
    else:
        COUNTS['item'] += 1
    base = e['base']
    for kind in ('axe', 'hoe', 'pickaxe', 'shovel', 'sword'):
        if base.endswith('_' + kind.upper()):
            ITEM_TAGS[kind + 's'].append(NS + ':' + iid)
    if base == 'FISHING_ROD':
        ITEM_TAGS['enchantable/fishing'].append(NS + ':' + iid)
    if base == 'TRIDENT':
        ITEM_TAGS['enchantable/trident'].append(NS + ':' + iid)
    item_model_def(iid, cfg, pack_model_ref(cfg))
    ENTRIES.append(e)

# ----------------------------------------------------------------------------------------------------- crop blocks
for crop, stages in CROPS.items():
    bid = CROP_BLOCK_IDS[crop]
    n = max(stages) + 1
    variants = {}
    shapes = []
    snd = {}
    for age in range(n):
        sid = stages.get(age) or stages[max(stages)]
        cfg = ITEMS[sid][1]
        blk = cfg['mechanics']['block']
        snd = sounds(blk.get('block_sounds')) or snd
        mpath = (blk.get('appearance') or {}).get('model') or cfg['pack']['model']
        variants['age=%d' % age] = {'model': emit_model(mpath)}
        els, tex, ao, owner = resolved(mpath)
        bb = [geo.ebox(x) for x in els]
        if bb:
            mn = [max(0.0, min(b[0][k] for b in bb)) for k in range(3)]
            mx = [min(16.0, max(b[1][k] for b in bb)) for k in range(3)]
            shapes.append([round(v, 2) for v in (mn[0], 0.0, mn[2], mx[0], max(2.0, mx[1]), mx[2])])
        else:
            shapes.append([0, 0, 0, 16, 2, 16])
    wjson(os.path.join(ASSETS, 'blockstates', bid + '.json'), {'variants': variants})
    seeds = 'arcana_seeds_' + crop if 'arcana_seeds_' + crop in ITEMS else None
    produce = 'arcana_crop_' + crop if 'arcana_crop_' + crop in ITEMS else None
    e = OrderedDict(id=bid, kind='crop', tab=None, hidden=True,
                    name=text_entry('block.%s.%s' % (NS, bid), '%s Crop' % crop.replace('_', ' ').title()))
    LANG['item.%s.%s' % (NS, bid)] = LANG['block.%s.%s' % (NS, bid)]
    e['crop'] = {'stages': n, 'seeds': seeds, 'produce': produce, 'shapes': shapes, 'sounds': snd,
                 'stage_ids': [stages.get(a) for a in range(n)]}
    pools = []
    if seeds:
        pools.append(pool([{'type': 'minecraft:item', 'name': NS + ':' + seeds}]))
    if produce:
        pools.append(pool([{'type': 'minecraft:item', 'name': NS + ':' + produce}], match(bid, {'age': str(n - 1)})))
    loot_table(bid, pools)
    BLOCK_IDS.append(bid)
    ENTRIES.append(e)
    COUNTS['crop block'] += 1

# ----------------------------------------------------------------------------------------------------- recipes
RECIPES = {}
RECIPE_SKIPPED = []


def ingredient(d):
    if not isinstance(d, dict):
        return None
    if d.get('oraxen_item'):
        if d['oraxen_item'] not in ITEMS:
            return None
        return NS + ':' + d['oraxen_item']
    if d.get('minecraft_type'):
        mt = d['minecraft_type'].lower()
        if 'assets/minecraft/items/%s.json' % mt not in JAR_NAMES:
            return None
        return 'minecraft:' + mt
    if d.get('tag'):
        t = d['tag']
        return '#' + (t if ':' in t else 'minecraft:' + t)
    if d.get('minecraft_item'):
        return None
    return None


def result(d):
    if not isinstance(d, dict):
        return None
    if d.get('oraxen_item'):
        if d['oraxen_item'] not in ITEMS:
            return None
        r = {'id': NS + ':' + d['oraxen_item']}
    elif d.get('minecraft_type'):
        r = {'id': 'minecraft:' + d['minecraft_type'].lower()}
    else:
        return None
    if int(d.get('amount', 1)) > 1:
        r['count'] = int(d['amount'])
    return r


def recipe_name(name):
    return re.sub(r'[^a-z0-9_./-]', '_', name.lower())


# Oraxen's recipe files, then the in-repo ones (mod/content/recipes/<kind>[.<part>].yml, e.g. shaped.winter_blocks.yml)
for rf in (sorted(glob.glob(os.path.join(ORAXEN, 'recipes', '*.yml')))
           + sorted(glob.glob(os.path.join(CONTENT_ROOT, 'recipes', '*.yml')))):
    kind = os.path.basename(rf).split('.')[0]
    data = miniyaml.load(rf) or {}
    if not isinstance(data, dict):
        continue
    for name, r in data.items():
        if not isinstance(r, dict):
            continue
        res = result(r.get('result'))
        if res is None:
            RECIPE_SKIPPED.append('%s/%s (result)' % (kind, name))
            continue
        out = None
        if kind == 'shaped':
            keys = {}
            ok = True
            for k, v in (r.get('ingredients') or {}).items():
                ing = ingredient(v)
                if ing is None:
                    ok = False
                keys[str(k)] = ing
            shape = [str(s).replace('_', ' ') for s in r.get('shape') or []]
            used = set(''.join(shape)) - {' '}
            if ok and used <= set(keys):
                out = {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': shape,
                       'key': {k: v for k, v in keys.items() if k in used}, 'result': res}
        elif kind == 'shapeless':
            ings = []
            ok = True
            for k, v in (r.get('ingredients') or {}).items():
                ing = ingredient(v)
                if ing is None:
                    ok = False
                ings += [ing] * int((v or {}).get('amount', 1))
            if ok and 0 < len(ings) <= 9:
                out = {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': ings, 'result': res}
        elif kind in ('furnace', 'campfire', 'smoking', 'blasting'):
            ing = ingredient(r.get('input'))
            if ing:
                out = {'type': 'minecraft:' + {'furnace': 'smelting', 'campfire': 'campfire_cooking'}.get(kind, kind),
                       'category': 'food' if kind in ('campfire', 'smoking') else 'misc',
                       'cookingtime': int(r.get('cookingTime', 200)), 'experience': float(r.get('experience', 0)),
                       'ingredient': ing, 'result': res}
        elif kind == 'stonecutting':
            ing = ingredient(r.get('input'))
            if ing:
                out = {'type': 'minecraft:stonecutting', 'ingredient': ing, 'result': res}
        elif kind == 'smithing':
            t, b, a = ingredient(r.get('template')), ingredient(r.get('base')), ingredient(r.get('addition'))
            if t and b and a:
                out = {'type': 'minecraft:smithing_transform', 'template': t, 'base': b, 'addition': a, 'result': res}
        if out is None:
            RECIPE_SKIPPED.append('%s/%s' % (kind, name))
            continue
        RECIPES[recipe_name(name)] = out
for name, r in RECIPES.items():
    wjson(os.path.join(DATA, 'recipe', name + '.json'), r, compact=False)

# ------------------------------------------------------------------------------------------------- loot, tags, lang
for bid, lt in LOOT.items():
    wjson(os.path.join(DATA, 'loot_table', 'blocks', bid + '.json'), lt, compact=False)
for tool, ids in MINEABLE.items():
    wjson(os.path.join(MC_TAGS, 'block', 'mineable', tool + '.json'), {'replace': False, 'values': sorted(ids)}, False)
if WOOL:
    wjson(os.path.join(MC_TAGS, 'block', 'wool.json'), {'replace': False, 'values': sorted(WOOL)}, False)
for tag, ids in ITEM_TAGS.items():
    wjson(os.path.join(MC_TAGS, 'item', tag + '.json'), {'replace': False, 'values': sorted(ids)}, False)
for tag, ids in BLOCK_TAGS.items():
    wjson(os.path.join(MC_TAGS, 'block', tag + '.json'), {'replace': False, 'values': sorted(ids)}, False)
    wjson(os.path.join(MC_TAGS, 'item', tag + '.json'), {'replace': False, 'values': sorted(ids)}, False)
furn = [NS + ':' + e['id'] for e in ENTRIES if e['kind'] == 'furniture']
wjson(os.path.join(DATA, 'tags', 'block', 'furniture.json'), {'values': furn}, False)
wjson(os.path.join(DATA, 'tags', 'item', 'furniture.json'), {'values': furn}, False)
wjson(os.path.join(DATA, 'tags', 'block', 'crops.json'),
      {'values': [NS + ':' + e['id'] for e in ENTRIES if e['kind'] == 'crop']}, False)
# What can't destroy a DAMAGE_PROOF item lying on the ground (everything but the void and /kill).
wjson(os.path.join(DATA, 'tags', 'damage_type', 'coin_proof.json'), {'values': [
    '#minecraft:is_fire', '#minecraft:is_explosion', '#minecraft:is_lightning', 'minecraft:cactus',
    'minecraft:falling_anvil', 'minecraft:falling_block', 'minecraft:falling_stalactite', 'minecraft:hot_floor',
    'minecraft:sweet_berry_bush', 'minecraft:wither', 'minecraft:wither_skull']}, False)
for t in TABS:
    LANG['itemGroup.%s.%s' % (NS, t['key'])] = t['name']
# the catch-all tab ArcanaContent.registerTabs adds for items that map to no tab (none should: see resolve_tab)
LANG['itemGroup.%s.misc' % NS] = 'Aftr Hrs'
# two visible, tabbed items with the same name look identical in creative / JEI
_names = defaultdict(list)
for _e in ENTRIES:
    if _e.get('tab') and not _e.get('hidden') and _e['kind'] not in ('crop',):
        _names[LANG.get('item.%s.%s' % (NS, _e['id']))].append(_e['id'])
for _n, _ids in sorted(_names.items(), key=lambda x: str(x[0])):
    if len(_ids) > 1:
        warn('items share the name %r: %s' % (_n, ', '.join(_ids)))
wjson(os.path.join(ASSETS, 'lang', 'en_us.json'), LANG, compact=False)

content = OrderedDict()
content['generated_by'] = 'mod/tools/gen_content.py'
content['tabs'] = TABS
content['entries'] = ENTRIES
wjson(os.path.join(DATA, 'content', 'content.json'), content)

# ----------------------------------------------------------------------------------------------------- report
print('entries: %d (from %d Oraxen items)' % (len(ENTRIES), len(ITEMS)))
for k in sorted(COUNTS, key=lambda k: (k.startswith('  '), k)):
    pass
order = ['item', 'hat (head-equippable item)', 'furniture', '  of which multi-block', '  of which storage',
         '  of which seats', '  of which toggle lit', '  of which toggle open', '  of which 8-way rotation',
         '  of which 16-way rotation', 'full block', 'stairs', 'slab', 'wall', 'crop block',
         'crop stage (item alias of a crop block state)', 'alias item (lit hearth -> hearth block lit=true)']
for k in order + [k for k in COUNTS if k not in order]:
    if k in COUNTS:
        print('  %-50s %d' % (k, COUNTS[k]))
print('models copied: %d, generated geometry: %d, generated block models: %d, textures: %d'
      % (len([v for v in MODEL_DONE.values() if v.startswith(NS)]), GEN_COUNT['geometry'], GEN_COUNT['leaf'], TEX_COPIED))
print('recipes: %d written, %d skipped' % (len(RECIPES), len(RECIPE_SKIPPED)))
for s in RECIPE_SKIPPED:
    print('  skipped recipe', s)
if DROPPED_FACELESS:
    print('faceless elements dropped: %d (%s)' % (len(DROPPED_FACELESS), ', '.join(sorted(set(DROPPED_FACELESS))[:10])))
for w in sorted(set(WARN)):
    print('WARN', w)
gc = Counter(g.split(':', 1)[1] for g in GAPS)
for g, n in gc.items():
    print('GAP x%d:%s' % (n, g))

# The storage network blocks (Feather, not Oraxen) write into the dirs cleared above: regenerate them too.
import runpy  # noqa: E402
runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'gen_storage.py'), run_name='__main__')
# The fairy lights (hook blockstate, recipes, loot, item definitions) also write into the cleared dirs.
runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'gen_fairylights.py'), run_name='__main__')
# Anvil & Ember (smithing): blockstates, item definitions, station models/textures, recipes, loot and tags.
runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'gen_smithing.py'), run_name='__main__')
# Enchantments: their exclusive-set, item and block tags live in the cleared data/aftrhrs/tags.
runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'gen_enchants.py'), run_name='__main__')
# Counter keepers (shop): item definitions and recipes in the cleared dirs, plus their models and textures.
runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'gen_keepers.py'), run_name='__main__')
