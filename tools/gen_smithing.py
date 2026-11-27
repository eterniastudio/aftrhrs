"""Anvil & Ember (smithing) assets and data, from the bundled definitions in data/aftrhrs/smithing/*.json.

Writes (gen_content.py runs this at its end: blockstates, item definitions, block models/textures, recipes, loot tables
and tags live in folders it clears; run by hand after changing it:  python mod/tools/gen_smithing.py):
  assets/aftrhrs/blockstates/<station>.json            facing (and coal bed / bellows / rune glow) variants
  assets/aftrhrs/models/block/smithing/*, textures/block/smithing/*   via smithing_blocks.py (station models)
  assets/aftrhrs/models/item/smithing/**               one flat model per part x material and per tool layer x material
  assets/aftrhrs/items/*.json                          item definitions: parts select their material, tools are a
                                                       composite of one select per sprite layer (aftrhrs:smith_look_N),
                                                       bows/crossbow/rod switch on drawing / loaded / cast
  assets/aftrhrs/items/smithing/whetstone_wheel_spin.json   the wheel the renderer spins
  assets/aftrhrs/equipment/smith_<material>.json       worn armor per plating material
  assets/aftrhrs/lang/en_us.smithing.json
  data/aftrhrs/recipe/smithing/*.json, data/aftrhrs/loot_table/blocks/<station>.json
  data/aftrhrs/tags/item/smithing/*.json, minecraft mineable tags and #minecraft:arrows (merged)
Item textures come from smithing_art.py (run it by hand after changing the art).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'aftrhrs')
DATA = os.path.join(RES, 'data', 'aftrhrs')
MC_TAGS = os.path.join(RES, 'data', 'minecraft', 'tags')
DEFS = os.path.join(DATA, 'smithing')
TEX = os.path.join(ASSETS, 'textures', 'item', 'smithing')
# smithing_art.py's unwritten tool frames: rel -> the identical texture's rel, or None (fully transparent: no layer)
with open(os.path.join(HERE, 'smithing_frames.json'), encoding='utf-8') as _f:
    ALIAS = json.load(_f)
# models written under models/item/smithing/tool this run (main() removes the rest)
TOOL_MODELS = set()


def wjson(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write('\n')


def load(name):
    with open(os.path.join(DEFS, name), encoding='utf-8') as f:
        return json.load(f)


MATERIALS = load('materials.json')['materials']
PARTS = load('parts.json')['parts']
TOOLS = load('tools.json')['tools']
MODIFIERS = load('modifiers.json')['modifiers']
ALLOYS = load('alloys.json')['alloys']
MAT = {m['id']: m for m in MATERIALS}
PART = {p['id']: p for p in PARTS}
ALLOY_METALS = ['steel', 'rose_gold', 'chime_bronze', 'tidebronze', 'rimesteel', 'emberforged_steel', 'pearlsteel', 'echo_steel',
                'voidgold', 'starforged']
STATIONS = ['forge_hearth', 'bellows', 'crucible', 'casting_bench', 'smithing_anvil', 'quench_trough', 'carving_bench', 'assembly_bench',
            'whetstone_wheel', 'runesmith_table', 'smithing_spout', 'fire_bricks', 'great_crucible_drain']
WOODEN = {'bellows', 'quench_trough', 'carving_bench', 'assembly_bench', 'whetstone_wheel'}


def fits(m, role):
    kind = m['kind']
    return {'head': 'head' in m, 'handle': 'handle' in m, 'limb': 'limb' in m, 'grip': 'grip' in m or 'handle' in m,
            'plating': 'plating' in m, 'string': kind == 'string', 'fletching': kind == 'fletching',
            'binding': kind not in ('string', 'fletching')}.get(role, False)


def layers(tool):
    out = []
    for p in tool['parts']:
        if p['layer'] not in [l for l, _ in out]:
            out.append((p['layer'], PART[p['part']]['role']))
    return out


def title(s):
    return ' '.join(w.capitalize() for w in s.split('_'))


# ------------------------------------------------------------------------------------------------ models

def flat_model(path, texture, parent='minecraft:item/generated'):
    out = os.path.join(ASSETS, 'models', 'item', 'smithing', path + '.json')
    wjson(out, {'parent': parent, 'textures': {'layer0': texture}})
    if path.startswith('tool/'):
        TOOL_MODELS.add(os.path.normcase(os.path.abspath(out)))
    return 'aftrhrs:item/smithing/' + path


def mref(model):
    return {'type': 'minecraft:model', 'model': model}


def tex_exists(rel):
    return os.path.exists(os.path.join(TEX, rel + '.png'))


PARENT = {'harvest': 'minecraft:item/handheld', 'melee': 'minecraft:item/handheld', 'ranged': 'minecraft:item/generated',
          'shield': 'minecraft:item/handheld', 'armor': 'minecraft:item/generated', 'ammo': 'minecraft:item/generated'}


def tool_parent(tool):
    b = tool.get('behavior') or []
    if 'bow' in b:
        return 'minecraft:item/bow'
    if 'crossbow' in b:
        return 'minecraft:item/crossbow'
    if 'fishing' in b:
        return 'minecraft:item/handheld_rod'
    return PARENT[tool['kind']]


def layer_select(tool, index, layer, role, suffix=''):
    """The select on aftrhrs:smith_look_<index>: one model per material that fits the layer's role."""
    cases = []
    fallback = None
    parent = tool_parent(tool)
    for m in MATERIALS:
        if not fits(m, role):
            continue
        rel = ALIAS.get('tool/%s/%s/%s%s' % (tool['id'], layer, m['id'], suffix), 'tool/%s/%s/%s%s' % (tool['id'], layer, m['id'], suffix))
        if rel is None or not tex_exists(rel):
            continue      # a transparent frame: this material draws nothing on the layer
        model = flat_model(rel, 'aftrhrs:item/smithing/' + rel, parent)
        cases.append({'when': m['id'], 'model': mref(model)})
        if fallback is None or m['id'] in ('iron', 'wood', 'leather', 'silk', 'down_feather'):
            fallback = mref(model)
    if not cases:
        return None
    return {'type': 'minecraft:select', 'property': 'minecraft:component', 'component': 'aftrhrs:smith_look_%d' % index,
            'cases': cases, 'fallback': fallback}


def tool_composite(tool, suffix=''):
    models = []
    for i, (layer, role) in enumerate(layers(tool)):
        sel = layer_select(tool, i, layer, role, suffix)
        if sel is not None:
            models.append(sel)
    return {'type': 'minecraft:composite', 'models': models}


def tool_item(tool):
    b = tool.get('behavior') or []
    idle = tool_composite(tool)
    if 'bow' in b:
        model = {'type': 'minecraft:condition', 'property': 'minecraft:using_item', 'on_false': idle,
                 'on_true': {'type': 'minecraft:range_dispatch', 'property': 'minecraft:use_duration', 'scale': 0.05,
                             'entries': [{'threshold': 0.65, 'model': tool_composite(tool, '_pulling_1')},
                                         {'threshold': 0.9, 'model': tool_composite(tool, '_pulling_2')}],
                             'fallback': tool_composite(tool, '_pulling_0')}}
    elif 'crossbow' in b:
        model = {'type': 'minecraft:select', 'property': 'minecraft:charge_type',
                 'cases': [{'when': 'arrow', 'model': tool_composite(tool, '_arrow')}, {'when': 'rocket', 'model': tool_composite(tool, '_firework')}],
                 'fallback': {'type': 'minecraft:condition', 'property': 'minecraft:using_item', 'on_false': idle,
                              'on_true': {'type': 'minecraft:range_dispatch', 'property': 'minecraft:crossbow/pull',
                                          'entries': [{'threshold': 0.58, 'model': tool_composite(tool, '_pulling_1')},
                                                      {'threshold': 1.0, 'model': tool_composite(tool, '_pulling_2')}],
                                          'fallback': tool_composite(tool, '_pulling_0')}}}
    elif 'fishing' in b:
        model = {'type': 'minecraft:condition', 'property': 'minecraft:fishing_rod/cast', 'on_false': idle, 'on_true': tool_composite(tool, '_cast')}
    else:
        model = idle
    wjson(os.path.join(ASSETS, 'items', 'smithed_%s.json' % tool['id']), {'model': model})


def part_item(part):
    cases, fallback = [], None
    for m in MATERIALS:
        if not fits(m, part['role']):
            continue
        rel = 'part/%s/%s' % (part['id'], m['id'])
        if not tex_exists(rel):
            continue
        model = flat_model(rel, 'aftrhrs:item/smithing/' + rel)
        cases.append({'when': m['id'], 'model': mref(model)})
        if fallback is None or m['id'] in ('iron', 'wood', 'silk', 'down_feather'):
            fallback = mref(model)
    wjson(os.path.join(ASSETS, 'items', 'part_%s.json' % part['id']),
          {'model': {'type': 'minecraft:select', 'property': 'minecraft:component', 'component': 'aftrhrs:smith_material',
                     'cases': cases, 'fallback': fallback}})


def misc_items():
    def simple(item, tex, parent='minecraft:item/generated'):
        model = flat_model('misc/' + tex, 'aftrhrs:item/smithing/misc/' + tex, parent)
        wjson(os.path.join(ASSETS, 'items', item + '.json'), {'model': mref(model)})
        return model

    simple('smith_knife', 'knife', 'minecraft:item/handheld')
    simple('smith_chisel', 'chisel', 'minecraft:item/handheld')
    simple('stirring_rod', 'stirring_rod', 'minecraft:item/handheld')
    simple('molding_box', 'molding_box')
    simple('fish_oil', 'fish_oil')
    simple('scribes_crystal', 'scribes_crystal')
    simple('modifier_crystal', 'modifier_crystal')
    simple('smiths_almanac', 'smiths_almanac')
    simple('fire_brick', 'fire_brick')
    simple('fire_clay', 'fire_clay')
    simple('whetstone', 'whetstone')
    for metal in ALLOY_METALS:
        simple('ingot_' + metal, 'ingot_' + metal)
        simple('nugget_' + metal, 'nugget_' + metal)
    # tongs: closed when holding a piece
    open_m = flat_model('misc/tongs', 'aftrhrs:item/smithing/misc/tongs', 'minecraft:item/handheld')
    held_m = flat_model('misc/tongs_holding', 'aftrhrs:item/smithing/misc/tongs_holding', 'minecraft:item/handheld')
    wjson(os.path.join(ASSETS, 'items', 'smith_tongs.json'), {'model': {
        'type': 'minecraft:condition', 'property': 'minecraft:has_component', 'component': 'aftrhrs:smith_held',
        'on_true': mref(held_m), 'on_false': mref(open_m)}})
    # ladle: the fluid it carries (custom model data string 0)
    empty = flat_model('misc/ladle', 'aftrhrs:item/smithing/misc/ladle', 'minecraft:item/handheld')
    cases = []
    for m in [x['id'] for x in MATERIALS if x['kind'] == 'metal'] + ['fish_oil']:
        if os.path.exists(os.path.join(TEX, 'misc', 'ladle_%s.png' % m)):
            cases.append({'when': m, 'model': mref(flat_model('misc/ladle_' + m, 'aftrhrs:item/smithing/misc/ladle_' + m, 'minecraft:item/handheld'))})
    wjson(os.path.join(ASSETS, 'items', 'smith_ladle.json'), {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 0, 'cases': cases, 'fallback': mref(empty)}})
    # molds: the shape pressed in
    shapes = [p['id'] for p in PARTS] + ['ingot', 'nugget']
    for item, prefix, blank in (('sand_mold', 'sand_mold_', 'sand_mold'), ('clay_mold_unfired', 'clay_mold_unfired_', 'clay_mold_unfired_ingot'),
                                ('clay_mold', 'clay_mold_', 'clay_mold_ingot')):
        cases = []
        for s in shapes:
            if os.path.exists(os.path.join(TEX, 'misc', prefix + s + '.png')):
                cases.append({'when': s, 'model': mref(flat_model('misc/' + prefix + s, 'aftrhrs:item/smithing/misc/' + prefix + s))})
        fb = flat_model('misc/' + blank, 'aftrhrs:item/smithing/misc/' + blank)
        wjson(os.path.join(ASSETS, 'items', item + '.json'), {'model': {
            'type': 'minecraft:select', 'property': 'minecraft:component', 'component': 'aftrhrs:smith_mold', 'cases': cases, 'fallback': mref(fb)}})


# ------------------------------------------------------------------------------------------------ blocks

def blockstates():
    rot = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
    for sid in STATIONS:
        variants = {}
        if sid == 'fire_bricks':
            variants[''] = {'model': 'aftrhrs:block/smithing/fire_bricks'}
        elif sid == 'forge_hearth':
            for f, y in rot.items():
                for coal, model in (('empty', 'forge_hearth'), ('cold', 'forge_hearth_cold'), ('lit', 'forge_hearth_lit')):
                    variants['coal=%s,facing=%s' % (coal, f)] = dict({'model': 'aftrhrs:block/smithing/' + model}, **({'y': y} if y else {}))
        elif sid in ('bellows', 'runesmith_table'):
            alt = 'bellows_pressed' if sid == 'bellows' else 'runesmith_table_lit'
            for f, y in rot.items():
                for on in ('false', 'true'):
                    variants['facing=%s,on=%s' % (f, on)] = dict({'model': 'aftrhrs:block/smithing/' + (alt if on == 'true' else sid)},
                                                                 **({'y': y} if y else {}))
        else:
            for f, y in rot.items():
                variants['facing=' + f] = dict({'model': 'aftrhrs:block/smithing/' + sid}, **({'y': y} if y else {}))
        wjson(os.path.join(ASSETS, 'blockstates', sid + '.json'), {'variants': variants})
        item_model = 'forge_hearth_cold' if sid == 'forge_hearth' else sid
        wjson(os.path.join(ASSETS, 'items', sid + '.json'), {'model': mref('aftrhrs:block/smithing/' + item_model)})
        wjson(os.path.join(DATA, 'loot_table', 'blocks', sid + '.json'), {
            'type': 'minecraft:block', 'random_sequence': 'aftrhrs:blocks/' + sid,
            'pools': [{'rolls': 1, 'bonus_rolls': 0, 'conditions': [{'condition': 'minecraft:survives_explosion'}],
                       'entries': [{'type': 'minecraft:item', 'name': 'aftrhrs:' + sid}]}]})
    wjson(os.path.join(ASSETS, 'items', 'smithing', 'whetstone_wheel_spin.json'), {'model': mref('aftrhrs:block/smithing/whetstone_wheel_spin')})


def equipment():
    for m in MATERIALS:
        if 'plating' not in m:
            continue
        wjson(os.path.join(ASSETS, 'equipment', 'smith_%s.json' % m['id']), {'layers': {
            'humanoid': [{'texture': 'aftrhrs:smith_%s' % m['id']}],
            'humanoid_leggings': [{'texture': 'aftrhrs:smith_%s' % m['id']}]}})


# ------------------------------------------------------------------------------------------------ data

def recipes():
    def shaped(name, pattern, key, result, count=1):
        wjson(os.path.join(DATA, 'recipe', 'smithing', name + '.json'), {
            'type': 'minecraft:crafting_shaped', 'category': 'misc', 'group': 'smithing', 'pattern': pattern, 'key': key,
            'result': {'id': result, 'count': count}})

    def shapeless(name, ingredients, result, count=1):
        wjson(os.path.join(DATA, 'recipe', 'smithing', name + '.json'), {
            'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'group': 'smithing', 'ingredients': ingredients,
            'result': {'id': result, 'count': count}})

    A = 'aftrhrs:'
    shaped('forge_hearth', ['BBB', 'B B', 'BIB'], {'B': 'minecraft:stone_bricks', 'I': 'minecraft:iron_bars'}, A + 'forge_hearth')
    shaped('bellows', ['PPP', 'LLL', 'PSP'], {'P': '#minecraft:planks', 'L': 'minecraft:leather', 'S': 'minecraft:stick'}, A + 'bellows')
    shaped('crucible', ['C C', 'C C', 'CIC'], {'C': 'minecraft:terracotta', 'I': 'minecraft:iron_ingot'}, A + 'crucible')
    shaped('casting_bench', ['SSS', 'C C'], {'S': 'minecraft:smooth_stone_slab', 'C': 'minecraft:cobblestone'}, A + 'casting_bench')
    shaped('smithing_anvil', ['III', ' I ', 'SSS'], {'I': 'minecraft:iron_ingot', 'S': 'minecraft:smooth_stone_slab'}, A + 'smithing_anvil')
    shaped('smithing_anvil_stone', ['SSS', ' C ', 'CCC'], {'S': 'minecraft:smooth_stone', 'C': 'minecraft:cobblestone'}, A + 'smithing_anvil')
    shaped('quench_trough', ['P P', 'PIP'], {'P': '#minecraft:planks', 'I': 'minecraft:iron_nugget'}, A + 'quench_trough')
    shaped('carving_bench', ['SSS', ' L ', ' L '], {'S': '#minecraft:wooden_slabs', 'L': '#minecraft:logs'}, A + 'carving_bench')
    shaped('assembly_bench', ['SSS', 'P P', 'P P'], {'S': '#minecraft:wooden_slabs', 'P': '#minecraft:planks'}, A + 'assembly_bench')
    shaped('whetstone_wheel', ['SWS', 'P P'], {'S': 'minecraft:stick', 'W': A + 'whetstone', 'P': '#minecraft:planks'}, A + 'whetstone_wheel')
    shaped('runesmith_table', ['DLD', ' B ', 'BBB'], {'D': 'minecraft:polished_deepslate', 'L': 'minecraft:lapis_block', 'B': 'minecraft:deepslate_bricks'},
           A + 'runesmith_table')
    shaped('smithing_spout', ['I  ', 'BII'], {'I': 'minecraft:iron_ingot', 'B': A + 'fire_brick'}, A + 'smithing_spout')
    shaped('fire_bricks', ['BB', 'BB'], {'B': A + 'fire_brick'}, A + 'fire_bricks')
    shaped('great_crucible_drain', ['BIB', 'BCB', 'BBB'], {'B': A + 'fire_brick', 'I': 'minecraft:iron_bars', 'C': A + 'crucible'}, A + 'great_crucible_drain')
    shaped('smith_tongs', [' I ', 'I I', 'S S'], {'I': 'minecraft:iron_ingot', 'S': 'minecraft:stick'}, A + 'smith_tongs')
    shaped('smith_knife', [' F', 'S '], {'F': 'minecraft:flint', 'S': 'minecraft:stick'}, A + 'smith_knife')
    shaped('smith_chisel', ['I', 'S'], {'I': 'minecraft:iron_ingot', 'S': 'minecraft:stick'}, A + 'smith_chisel')
    shaped('smith_chisel_flint', ['F', 'F', 'S'], {'F': 'minecraft:flint', 'S': 'minecraft:stick'}, A + 'smith_chisel')
    shaped('stirring_rod', ['  S', ' S ', 'S  '], {'S': 'minecraft:stick'}, A + 'stirring_rod')
    shaped('smith_ladle', ['I I', ' I ', ' S '], {'I': 'minecraft:iron_ingot', 'S': 'minecraft:stick'}, A + 'smith_ladle')
    shaped('molding_box', ['PSP', 'PPP'], {'P': '#minecraft:planks', 'S': 'minecraft:stick'}, A + 'molding_box')
    shaped('whetstone', ['SFS'], {'S': 'minecraft:sandstone', 'F': 'minecraft:flint'}, A + 'whetstone')
    shapeless('fire_clay', ['minecraft:clay_ball', '#minecraft:sand', 'minecraft:gravel'], A + 'fire_clay', 2)
    shapeless('smiths_almanac', ['minecraft:book', 'minecraft:iron_ingot'], A + 'smiths_almanac')
    shapeless('scribes_crystal', ['minecraft:amethyst_shard', 'minecraft:amethyst_shard', 'minecraft:amethyst_shard', 'minecraft:ender_pearl'],
              A + 'scribes_crystal')
    shapeless('fish_oil', ['minecraft:glass_bottle', '#minecraft:fishes', '#minecraft:fishes'], A + 'fish_oil')
    for kind in ('smelting', 'blasting'):
        wjson(os.path.join(DATA, 'recipe', 'smithing', 'fire_brick_' + kind + '.json'), {
            'type': 'minecraft:' + kind, 'category': 'misc', 'ingredient': A + 'fire_clay', 'result': {'id': A + 'fire_brick'},
            'experience': 0.2, 'cookingtime': 200 if kind == 'smelting' else 100})
    for metal in ALLOY_METALS:
        shapeless('ingot_%s_from_nuggets' % metal, [A + 'nugget_' + metal] * 9, A + 'ingot_' + metal)
        shapeless('nuggets_from_%s' % metal, [A + 'ingot_' + metal], A + 'nugget_' + metal, 9)


def merge_tag(path, values):
    data = {'replace': False, 'values': []}
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
    vals = data.setdefault('values', [])
    for v in values:
        if v not in vals:
            vals.append(v)
    wjson(path, data)


def tags():
    T = os.path.join(DATA, 'tags', 'item', 'smithing')
    wjson(os.path.join(T, 'sea_glass.json'), {'values': ['aftrhrs:arcana_sea_glass_shard_' + c for c in ('amber', 'aqua', 'blue', 'green', 'white')]})
    wjson(os.path.join(T, 'feathers.json'), {'values': ['minecraft:feather', 'aftrhrs:arcana_down_feather']})
    wjson(os.path.join(T, 'music_discs.json'), {'values': ['#minecraft:creeper_drop_music_discs', 'minecraft:music_disc_pigstep',
                                                           'minecraft:music_disc_otherside', 'minecraft:music_disc_relic',
                                                           'minecraft:music_disc_5', 'minecraft:music_disc_creator',
                                                           'minecraft:music_disc_precipice', 'minecraft:music_disc_tears',
                                                           'minecraft:music_disc_lava_chicken']})
    wjson(os.path.join(T, 'boss_trophies.json'), {'values': ['aftrhrs:arcana_trophy_' + t for t in
                                                             ('colossus_core', 'phoenix_plume', 'roc_talon', 'wukong_circlet', 'wyrm_horns')]})
    wjson(os.path.join(T, 'dyes.json'), {'values': ['minecraft:%s_dye' % c for c in
                                                    ('white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray',
                                                     'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black')]})
    pick = ['aftrhrs:' + s for s in STATIONS if s not in WOODEN]
    axe = ['aftrhrs:' + s for s in STATIONS if s in WOODEN]
    merge_tag(os.path.join(MC_TAGS, 'block', 'mineable', 'pickaxe.json'), pick)
    merge_tag(os.path.join(MC_TAGS, 'block', 'mineable', 'axe.json'), axe)
    merge_tag(os.path.join(MC_TAGS, 'item', 'arrows.json'), ['aftrhrs:smithed_arrow'])


# ------------------------------------------------------------------------------------------------ lang

TRAITS = {
    'seasoned': ('Seasoned', 'A wooden handle grows tougher with use: +1 durability per 50 blocks mined, up to +100.'),
    'bedrock_born': ('Bedrock-born', 'Mines faster as it wears: up to +30% speed near the end of its durability.'),
    'serrated': ('Serrated', 'Bites deeper as it wears: up to +2 damage near the end of its durability.'),
    'splintering': ('Splintering', 'Critical hits make the target bleed: 1 heart over 3 seconds.'),
    'springy': ('Springy', '+10% attack speed, and +10% draw speed on bows.'),
    'sure_grip': ('Sure Grip', 'Never slips, wet or dry: +5% attack speed.'),
    'taut': ('Taut', 'Arrows fly 15% faster.'),
    'deepdelver': ('Deepdelver', '+25% mining speed below Y 0; 15% slower above Y 64.'),
    'steady': ('Steady', 'Bow sway reduced by 40%: arrows fly truer.'),
    'shaggy': ('Shaggy', '+10% knockback resistance while held.'),
    'lodestone': ('Lodestone', 'Drops within 3 blocks of what you break fly to you.'),
    'gilded': ('Gilded', '+1 Fortune on tools, +1 Looting on weapons.'),
    'unyielding': ('Unyielding', 'Never loses more than 1 durability at a time, but works 20% slower.'),
    'glide': ('Glide', 'Arrows fly 30% farther.'),
    'tempered': ('Tempered', 'Quenching bonuses are doubled.'),
    'lustrous': ('Lustrous', '+1 upgrade slot for each rose gold part (up to 2).'),
    'resonant': ('Resonant', 'Mining stone rings out the ores within 6 blocks (they sparkle for a moment).'),
    'tidebound': ('Tidebound', 'No mining penalty underwater; +2 damage while it rains or you are wet.'),
    'frostbite': ('Frostbite', 'Hits slow the target (Slowness I, 2 s); +20% mining speed in cold biomes.'),
    'frost_tempered': ('Frost-tempered', 'Quenched in frost brine: +10% durability and +20% speed in cold biomes.'),
    'ember': ('Ember', 'Smelts a quarter of what it mines; hits set the target on fire for 3 s.'),
    'tidelucky': ('Tidelucky', '+1 Luck while held: better catches and finds.'),
    'fireproof': ('Fireproof', 'Immune to fire and lava; +20% damage in the Nether.'),
    'hush': ('Hush', 'Your mining and steps make no sculk vibrations; +3 damage on your first hit at a target.'),
    'kindled': ('Kindled', 'Hits within 2 s of each other stack +0.5 damage (up to +3).'),
    'warping': ('Warping', 'What you break goes straight into your pack.'),
    'radiant': ('Radiant', 'Mends 1 durability every 10 s in sunlight; +1 ability slot.'),
}

MODS = {
    'reinforced': ('Reinforced', '12% chance per level to lose no durability.'),
    'diamond_inlay': ('Diamond Inlay', '+500 durability, +0.5 damage, mines like diamond.'),
    'emerald_inlay': ('Emerald Inlay', '+50% durability, mines like iron at least.'),
    'netherite_cladding': ('Netherite Cladding', '+20% durability and damage, fireproof, mines like netherite.'),
    'lodestone': ('Lodestone', 'Drops within 5 blocks of what you break fly to you.'),
    'fortunes_favor': ("Fortune's Favor", '+1 Fortune (tools) or Looting (weapons) per level.'),
    'experienced': ('Experienced', '+50% experience from blocks and mobs per level.'),
    'soulbound': ('Soulbound', 'Stays with you when you die; graves leave it out.'),
    'honed': ('Honed', '+3 mining speed per level.'),
    'deepcore': ('Deepcore', '+30% speed on deepslate, tuff and ores.'),
    'lumberjack': ('Lumberjack', '+40% speed on wood per level.'),
    'tiller': ('Tiller', 'Right-click to till, like a hoe.'),
    'sea_glass_lens': ('Sea Glass Lens', 'No mining penalty underwater.'),
    'glowing': ('Glowing', 'Right-click to place a light (10 durability).'),
    'keen_edge': ('Keen Edge', '+1 damage per level.'),
    'smiting': ('Smiting', '+2.5 damage to the undead per level.'),
    'bane': ('Bane', '+2.5 damage to spiders and other arthropods per level; slows them.'),
    'sweeping': ('Sweeping', 'Wider, stronger sweeping strikes.'),
    'weighted': ('Weighted', '+1 knockback per level.'),
    'barbed': ('Barbed', 'Hits make the target bleed: 1 heart over 3 s per level.'),
    'venomous': ('Venomous', 'Hits poison (I, then II) for 4 s.'),
    'emberstrike': ('Emberstrike', 'Hits set the target on fire, 3 s per level.'),
    'frost_edge': ('Frost Edge', 'Hits slow the target (I, then II) for 2 s.'),
    'lifedrinker': ('Lifedrinker', 'Heals you 5% of your damage per level.'),
    'featherlight': ('Featherlight', '+5% attack speed per level.'),
    'power': ('Power', '+10% arrow damage per level.'),
    'quickdraw': ('Quickdraw', '+15% draw speed per level.'),
    'trueshot': ('Trueshot', 'Much less spread.'),
    'piercing': ('Piercing', 'Arrows pass through one more target per level.'),
    'heavy_arrows': ('Heavy Arrows', 'Arrows knock targets back further.'),
    'lure_charm': ('Lure Charm', 'Fish bite sooner (works as Lure).'),
    'treasure_sense': ('Treasure Sense', 'Better chance of treasure (works as Luck of the Sea).'),
    'braided_line': ('Braided Line', '+20% line strength in fish fights per level.'),
    'gentle_touch': ('Gentle Touch', 'Blocks drop themselves (silk touch).'),
    'smeltforged': ('Smeltforged', 'Smelts everything it mines.'),
    'broadstrike': ('Broadstrike', 'Mines a wider area: 3x3 on small tools, 5x5 on broad ones.'),
    'veinseeker': ('Veinseeker', 'Mines up to 12 more blocks of the same ore vein.'),
    'treefeller': ('Treefeller', 'Fells the whole tree.'),
    'harvester': ('Harvester', 'Harvests and replants ripe crops 3x3.'),
    'dual_wield': ('Dual Wield', 'Right-click a mob to strike with it from your off hand.'),
    'recall': ('Recall', 'A thrown spear comes back to you.'),
    'everlasting': ('Everlasting', 'Wears at a third of the rate.'),
    'grapple': ('Grapple', 'Reel in a hook caught on a block to pull yourself to it.'),
    'bulwark': ('Bulwark', 'Protection per level.'),
    'fireward': ('Fireward', 'Fire protection per level.'),
    'blastward': ('Blastward', 'Blast protection per level.'),
    'arrowward': ('Arrowward', 'Projectile protection per level.'),
    'featherstep': ('Featherstep', 'Less fall damage per level.'),
    'fleetfoot': ('Fleetfoot', '+5% movement speed per level.'),
    'deepbreath': ('Deepbreath', 'Breathe longer and work faster underwater.'),
    'spiked_plates': ('Spiked Plates', 'Attackers are hurt (thorns).'),
    'frostwalker': ('Frostwalker', 'Water freezes under your feet.'),
    'inscribed': ('Inscribed', '+1 upgrade slot.'),
    'harmonic': ('Harmonic', '+1 upgrade slot.'),
    'trophy_bound': ('Trophy-bound', '+1 ability slot per boss trophy bound.'),
    'engraved': ('Engraved', 'Takes the name of the name tag used.'),
    'etched': ('Etched', 'The name takes the dye\'s color.'),
}

TOOL_DESC = {
    'pickaxe': 'Pick head, binding, handle. The miner\'s tool.',
    'hatchet': 'Axe head, binding, handle. Chops, strips logs, disables shields.',
    'shovel': 'Shovel blade, binding, handle. Digs and makes paths.',
    'hoe': 'Hoe blade, binding, handle. Tills; right-click a ripe crop to harvest and replant.',
    'mattock': 'Pick head and axe head on one handle: mines stone and wood.',
    'sword': 'Blade, guard, handle. Sweeping strikes.',
    'dagger': 'Blade, guard, grip (makes 2). Fast; strike from the off hand; +50% from behind.',
    'spear': 'Spearhead on a tough handle. +1 reach; +30% when sprinting.',
    'mace': 'Hammer face, pommel, handle. The farther you fall, the harder it hits.',
    'greatsword': 'Broad blade, guard, pommel, tough handle. Slow, wide and heavy.',
    'sledge': 'Hammer face and two plates on a tough handle. Mines 3x3.',
    'excavator': 'Large plate, tough binding, tough handle. Digs 3x3.',
    'broad_axe': 'Broad axe head, tough binding, tough handle. Fells whole trees.',
    'scythe': 'Broad blade, tough binding, tough handle. Harvests and replants 3x3.',
    'smithing_hammer': 'Two hammer faces on a handle: the anvil\'s tool. Its head material makes the strike meter more forgiving.',
    'shortbow': 'Bow limb, grip, bowstring. Quick to draw.',
    'longbow': 'Two bow limbs, grip, bowstring. Powerful but slow.',
    'crossbow': 'Bow limb, stock grip, bowstring. Holds a shot.',
    'fishing_rod': 'Rod limb, line and hook. Pulls harder in fish fights.',
    'shield': 'Large plate and a handle rim. Metal shields recover from axes faster.',
    'helmet': 'Helm plate and mail.', 'chestplate': 'Cuirass plate and mail.', 'leggings': 'Greave plate and mail.', 'boots': 'Sabaton plate and mail.',
    'arrow': 'Arrowhead, shaft and fletching (makes 4).',
}

SOURCES = {
    'wood': 'Carve planks or logs on the Carving Bench.', 'stone': 'Chisel cobblestone (or any stone tool material).',
    'flint': 'Chisel flint.', 'bone': 'Carve bones or bone blocks.', 'bamboo': 'Carve bamboo.', 'leather': 'Carve leather; or wrap a handle.',
    'silk': 'Twist silk or string into bowstrings; or wrap a handle.', 'copper': 'Melt copper.', 'deer_hide': 'Carve deer hide; or wrap a grip.',
    'tufthorn_hide': 'Carve tufthorn hide; or wrap a handle.', 'iron': 'Melt iron.', 'gold': 'Melt gold.',
    'obsidian': 'Melt obsidian (a blaze or lava fire).', 'down_feather': 'Trim feathers or down feathers into fletching.',
    'steel': 'Fold hot iron with charcoal on the anvil, or alloy iron and charcoal in a crucible.',
    'rose_gold': 'Alloy copper and gold, half and half.', 'chime_bronze': 'Alloy copper with amethyst shards.',
    'tidebronze': 'Alloy copper with prismarine shards.', 'rimesteel': 'Alloy steel with a permafrost shard.',
    'emberforged_steel': 'Alloy steel with blaze powder.', 'pearlsteel': 'Alloy steel with a black pearl.',
    'netherite': 'Melt netherite (Great Crucible).', 'echo_steel': 'Alloy netherite with echo shards (Great Crucible).',
    'blazebone': 'Pour molten emberforged steel over a bone head on the Casting Bench.',
    'voidgold': 'Alloy gold with ender pearls (Great Crucible).', 'starforged': 'Alloy netherite with a nether star (Great Crucible).',
}

STATION_TEXT = {
    'forge': 'The heart of the shop. Feed it coal, charcoal, blaze rods or lava, light it with flint and steel, and lay up to three pieces on the coals. On its own a fire reaches 70% of its fuel\'s heat; bellows beside it (or a fan blowing into it) take it all the way. The dial on the front shows the heat; the crucible goes on top.',
    'bellows': 'Point its nozzle at a hearth and pump it (click, or blow a fan into it): full heat for two seconds, fuel burns faster.',
    'crucible': 'Sits on a lit hearth (or lava, magma, a campfire). Holds 4 ingots. Drop metal in, wait for it to melt, add an alloy\'s additives and stir three times with the stirring rod. Take metal out with a ladle or a spout. Fish melt into fish oil at a low heat.',
    'casting_bench': 'Molds: put a molding box down, pack it with 4 sand, press any part (or an ingot or nugget) into it. Or lay 4 clay balls, press a part, and fire the clay mold in the forge (it lasts 32 pours). Pour, let it cool, knock the casting out with a hammer: a rough part. Pour metal over a finished part to clad it.',
    'anvil': 'Lay a hot piece on it and strike with the smithing hammer when the marker is in the sweet spot (gold is perfect). Sneak to strike heavy: two moves, a narrower spot. Ingots are forged from scratch (sneak-click with an empty hand to choose the part); hot iron with charcoal folds into steel.',
    'trough': 'Fill it with water, fish oil, brine (water and dried kelp) or frost brine (water and a permafrost shard) and dunk a hot part to set it. Water: +10% durability but tier-3 parts dunked too hot can crack. Fish oil: +5% attack speed. Brine: +5% mining speed. Frost brine: Frost-tempered steel. Unquenched metal stays soft (-10% durability).',
    'carving_bench': 'Lay wood, stone, flint, bone, bamboo, hide, string or feathers down, sneak-click to choose the part, and cut in rhythm with the knife (the chisel for stone and flint): cuts on the beat make better stars. Lay a handle down and click with leather or hide to wrap it.',
    'assembly_bench': 'Lay parts in order (head, binding, handle); a part no tool takes next won\'t go down. Rivet with the hammer. A finished tool on the bench takes a new part (modifiers stay) or its head material and three hammer taps to repair.',
    'wheel': 'Hold a bladed tool against it (keep right-click held) to restore its Edge. Sneak-click to grind the newest modifier off; a Scribe\'s Crystal in your off hand keeps it as a modifier crystal.',
    'rune_table': 'The tool in the middle, up to four catalyst stacks around it. Strike four times with the chisel to etch the modifier the catalysts make into a free slot. Incremental modifiers take what\'s there and remember the rest.',
    'spout': 'Fit it beside a crucible (or the Great Crucible\'s drain) over a casting bench. Click it or give it a redstone pulse and it pours until the mold is full.',
    'fire_bricks': 'Fire clay baked into bricks: the Great Crucible is built from them.',
    'great_drain': 'The Great Crucible: a hollow column two blocks deep on a 3x3 floor of fire bricks, walled by fire bricks on both layers, with this drain in the wall (tap outward). Lava (or a lit hearth) under the floor\'s center heats it. Holds 36 ingots, melts tier-4 metal and makes the great alloys. Drop pieces into the hollow.',
}


def lang():
    L = {'itemGroup.aftrhrs.smithing': 'Anvil & Ember', 'aftrhrs.machine.smithing': 'Smithing',
         'aftrhrs.smithing.named': '%s %s', 'aftrhrs.smithing.rough': 'Rough %s', 'aftrhrs.smithing.part_line': '%s: %s',
         'aftrhrs.smithing.extra': ' (+%s)', 'aftrhrs.smithing.unassembled': 'Unassembled', 'aftrhrs.smithing.masterwork': '★ Masterwork ★',
         'aftrhrs.smithing.broken': 'Broken: repair at the Assembly Bench', 'aftrhrs.smithing.broke': '%s broke! Repair it at the Assembly Bench.',
         'aftrhrs.smithing.forged_by': 'Forged by %s', 'aftrhrs.smithing.shift': 'Hold Shift for stats', 'aftrhrs.smithing.edge': 'Edge %s/100',
         'aftrhrs.smithing.slots.u': 'Upgrades %s', 'aftrhrs.smithing.slots.a': 'Abilities %s', 'aftrhrs.smithing.slots.d': 'Defense %s',
         'aftrhrs.smithing.modifier_progress': '%s %s: %s/%s',
         'aftrhrs.smithing.stat.mining': 'Mining speed %s · mines %s', 'aftrhrs.smithing.stat.durability': 'Durability %s / %s',
         'aftrhrs.smithing.stat.ranged': 'Draw %s · speed %s · accuracy %s · +%s damage', 'aftrhrs.smithing.stat.arrow': 'Damage %s',
         'aftrhrs.smithing.rough.hint': 'Cast: finish it on the anvil for better stars',
         'aftrhrs.smithing.quench.none': 'Not quenched (soft)', 'aftrhrs.smithing.quenched.water': 'Water-quenched: +10% durability',
         'aftrhrs.smithing.quenched.oil': 'Oil-quenched: +5% attack speed', 'aftrhrs.smithing.quenched.brine': 'Brine-quenched: +5% mining speed',
         'aftrhrs.smithing.quenched.frost': 'Frost-quenched: Frost-tempered',
         'aftrhrs.smithing.mold.units': 'Takes %s units of metal', 'aftrhrs.smithing.mold.fire': 'Fire it in the forge (600°, 10 s)',
         'aftrhrs.smithing.shape.ingot': 'Ingot', 'aftrhrs.smithing.shape.nugget': 'Nugget',
         'aftrhrs.smithing.tongs.hint': 'Hold hot pieces without getting burned', 'aftrhrs.smithing.ladle.load': '%s units of %s',
         'aftrhrs.smithing.ladle.empty': 'Empty: fill it at a crucible', 'aftrhrs.smithing.hand.knife': 'Carves wood, bone, hide, string and feathers',
         'aftrhrs.smithing.hand.chisel': 'Carves stone and flint; etches runes', 'aftrhrs.smithing.hand.stirring_rod': 'Stirs alloys in the crucible',
         'aftrhrs.smithing.crystal.hint': 'Etch it onto a tool at the Runesmith Table', 'item.aftrhrs.modifier_crystal.of': 'Crystal of %s',
         'item.aftrhrs.smith_tongs.holding': 'Tongs (%s)',
         'item.aftrhrs.sand_mold.of': 'Sand Mold (%s)', 'item.aftrhrs.clay_mold.of': 'Clay Mold (%s)', 'item.aftrhrs.clay_mold_unfired.of': 'Unfired Clay Mold (%s)',
         'aftrhrs.smithing.burn': 'Ouch! That\'s hot: use tongs.', 'aftrhrs.smithing.claimed': 'You can\'t use this station here.',
         'aftrhrs.smithing.crucible.empty': 'The crucible is empty.', 'aftrhrs.smithing.crucible.no_alloy': 'Nothing to alloy (check the mix and the heat).',
         'aftrhrs.smithing.crucible.stir': 'Stirring... %s/3', 'aftrhrs.smithing.crucible.alloyed': 'The melt turns to %s!',
         'aftrhrs.smithing.great.unformed': 'The Great Crucible isn\'t built yet: see the Almanac.',
         'aftrhrs.smithing.anvil.target': 'Forging: %s', 'aftrhrs.smithing.anvil.cold': 'Too cold to move (%s): reheat it.',
         'aftrhrs.smithing.anvil.done': 'Done! %s stars.',
         'aftrhrs.smithing.quench.cracked': 'Hiss... crack! Too hot for water: it lost a star.', 'aftrhrs.smithing.quench.water': 'Hiss! Quenched in water.',
         'aftrhrs.smithing.quench.oil': 'Quenched in fish oil.', 'aftrhrs.smithing.quench.brine': 'Quenched in brine.',
         'aftrhrs.smithing.quench.frost': 'Quenched in frost brine: Frost-tempered!', 'aftrhrs.smithing.quench.too_cool': 'Too cool to quench: it stays soft.',
         'aftrhrs.smithing.carve.need_chisel': 'Use the chisel on stone and flint.', 'aftrhrs.smithing.carve.need_knife': 'Use the knife for this.',
         'aftrhrs.smithing.carve.target': 'Carving: %s', 'aftrhrs.smithing.carve.done': 'Carved! %s stars.', 'aftrhrs.smithing.carve.wrapped': 'Wrapped in %s.',
         'aftrhrs.smithing.assembly.hot': 'Let it cool first.', 'aftrhrs.smithing.assembly.no_fit': 'That part doesn\'t go there.',
         'aftrhrs.smithing.assembly.heading': 'Making: %s', 'aftrhrs.smithing.assembly.repaired': 'Repaired.',
         'aftrhrs.smithing.assembly.slots': 'Its modifiers wouldn\'t fit with that part.',
         'aftrhrs.smithing.wheel.sharp': 'Razor sharp.', 'aftrhrs.smithing.wheel.ground': '%s ground off.', 'aftrhrs.smithing.wheel.kept': '%s kept as a crystal.',
         'aftrhrs.smithing.rune.nothing': 'Those catalysts make nothing.', 'aftrhrs.smithing.rune.wrong_tool': '%s doesn\'t go on this tool.',
         'aftrhrs.smithing.rune.max': '%s is at its highest level.', 'aftrhrs.smithing.rune.no_slot': 'No free slot for %s.',
         'aftrhrs.smithing.rune.progress': '%s: %s/%s', 'aftrhrs.smithing.rune.done': '%s etched!',
         'aftrhrs.smithing.heat.cold': 'Cold', 'aftrhrs.smithing.heat.warm': 'Warm (%s°)', 'aftrhrs.smithing.heat.dull_red': 'Dull red (%s°)',
         'aftrhrs.smithing.heat.cherry': 'Cherry red (%s°)', 'aftrhrs.smithing.heat.orange': 'Orange (%s°)', 'aftrhrs.smithing.heat.yellow': 'Yellow (%s°)',
         'aftrhrs.smithing.heat.white': 'White hot (%s°)',
         'aftrhrs.smithing.slot.none': 'slotless',
         }
    for t in ('wood', 'gold', 'stone', 'copper', 'iron', 'diamond', 'netherite'):
        L['aftrhrs.smithing.tier.' + t] = title(t)
    for k in ('carved', 'wrap', 'string', 'fletching', 'metal', 'clad'):
        L['aftrhrs.smithing.kind.' + k] = {'carved': 'carved', 'wrap': 'hide', 'string': 'string', 'fletching': 'fletching', 'metal': 'metal',
                                           'clad': 'clad'}[k]
    for role, text in (('head', 'Head part · costs %s'), ('handle', 'Handle part · costs %s'), ('binding', 'Binding part · costs %s'),
                       ('grip', 'Grip part · costs %s'), ('limb', 'Limb part · costs %s'), ('string', 'String part · costs %s'),
                       ('fletching', 'Fletching · costs %s'), ('plating', 'Armor plate · costs %s')):
        L['aftrhrs.smithing.role.' + role] = text
    for mv in ('draw', 'flatten', 'bend', 'punch', 'fold', 'upset'):
        L['aftrhrs.smithing.move.' + mv] = title(mv)
    for m in MATERIALS:
        L['aftrhrs.smithing.material.' + m['id']] = {'deer_hide': 'Deer Hide', 'tufthorn_hide': 'Tufthorn', 'down_feather': 'Down Feather',
                                                     'emberforged_steel': 'Emberforged Steel'}.get(m['id'], title(m['id']))
        L['aftrhrs.smithing.fluid.' + m['id']] = 'Molten ' + L['aftrhrs.smithing.material.' + m['id']]
        L['aftrhrs.smithing.almanac.material.source.' + m['id']] = SOURCES.get(m['id'], '')
    L['aftrhrs.smithing.fluid.fish_oil'] = 'Fish Oil'
    for p in PARTS:
        L['aftrhrs.smithing.part.' + p['id']] = title(p['id'])
        L['item.aftrhrs.part_' + p['id']] = title(p['id'])
    for t in TOOLS:
        name = {'smithing_hammer': 'Smithing Hammer', 'broad_axe': 'Broad Axe', 'fishing_rod': 'Fishing Rod'}.get(t['id'], title(t['id']))
        L['aftrhrs.smithing.tool.' + t['id']] = name
        L['aftrhrs.smithing.tool.' + t['id'] + '.desc'] = TOOL_DESC.get(t['id'], '')
        L['item.aftrhrs.smithed_' + t['id']] = 'Smithed ' + name
    for k, (n, d) in TRAITS.items():
        L['aftrhrs.smithing.trait.' + k] = n
        L['aftrhrs.smithing.trait.' + k + '.desc'] = d
    for m in MODIFIERS:
        n, d = MODS.get(m['id'], (title(m['id']), ''))
        L['aftrhrs.smithing.modifier.' + m['id']] = n
        L['aftrhrs.smithing.modifier.' + m['id'] + '.desc'] = d
    for tag, n in (('aftrhrs.smithing.sea_glass', 'sea glass shards'), ('aftrhrs.smithing.feathers', 'feathers'),
                   ('aftrhrs.smithing.music_discs', 'any music disc'), ('aftrhrs.smithing.boss_trophies', 'a boss trophy'),
                   ('aftrhrs.smithing.dyes', 'any dye'), ('minecraft.logs', 'logs')):
        L['aftrhrs.smithing.tag.' + tag] = n
    names = {'forge_hearth': 'Forge Hearth', 'bellows': 'Bellows', 'crucible': 'Crucible', 'casting_bench': 'Casting Bench',
             'smithing_anvil': 'Smithing Anvil', 'quench_trough': 'Quench Trough', 'carving_bench': 'Carving Bench', 'assembly_bench': 'Assembly Bench',
             'whetstone_wheel': 'Whetstone Wheel', 'runesmith_table': 'Runesmith Table', 'smithing_spout': 'Spout', 'fire_bricks': 'Fire Bricks',
             'great_crucible_drain': 'Great Crucible Drain'}
    for sid, n in names.items():
        L['block.aftrhrs.' + sid] = n
    lang_station = {'forge_hearth': 'forge', 'bellows': 'bellows', 'crucible': 'crucible', 'casting_bench': 'casting_bench', 'smithing_anvil': 'anvil',
                    'quench_trough': 'trough', 'carving_bench': 'carving_bench', 'assembly_bench': 'assembly_bench', 'whetstone_wheel': 'wheel',
                    'runesmith_table': 'rune_table', 'smithing_spout': 'spout', 'fire_bricks': 'fire_bricks', 'great_crucible_drain': 'great_drain'}
    for sid, key in lang_station.items():
        L['aftrhrs.smithing.almanac.station.' + key] = STATION_TEXT[key]
        L['aftrhrs.smithing.note.' + key] = STATION_TEXT[key].split('.')[0] + '.'
    items = {'smith_tongs': ('Smith\'s Tongs', 'Pick hot pieces up without a burn; stations take pieces from them and put pieces into them.'),
             'smith_knife': ('Carving Knife', 'Cuts wood, bone, hide, string and feathers on the Carving Bench.'),
             'smith_chisel': ('Chisel', 'Cuts stone and flint; etches runes at the Runesmith Table.'),
             'stirring_rod': ('Stirring Rod', 'Three stirs turn the right mix in a hot crucible into its alloy.'),
             'smith_ladle': ('Ladle', 'Carries up to 4 ingots of molten metal from a crucible to a mold; tipped into water it sets into ingots.'),
             'molding_box': ('Molding Box', 'Put it on a Casting Bench and pack it with 4 sand to make a sand mold.'),
             'sand_mold': ('Sand Mold', ''), 'clay_mold': ('Clay Mold', ''), 'clay_mold_unfired': ('Unfired Clay Mold', ''),
             'fish_oil': ('Fish Oil', ''), 'scribes_crystal': ('Scribe\'s Crystal', ''), 'modifier_crystal': ('Modifier Crystal', ''),
             'smiths_almanac': ('Smith\'s Almanac', ''), 'fire_clay': ('Fire Clay', ''), 'fire_brick': ('Fire Brick', ''), 'whetstone': ('Whetstone', '')}
    for k, (n, d) in items.items():
        L['item.aftrhrs.' + k] = n
        if d:
            L['item.aftrhrs.' + k + '.desc'] = d
    for metal in ALLOY_METALS:
        mn = L['aftrhrs.smithing.material.' + metal]
        L['item.aftrhrs.ingot_' + metal] = mn + ' Ingot'
        L['item.aftrhrs.nugget_' + metal] = mn + ' Nugget'
    for liq, n, d in (('water', 'Water', 'Water: +10% durability (tier-3 parts can crack if too hot).'),
                      ('fish_oil', 'Fish oil', 'Fish oil: +5% attack speed, safe for every metal.'),
                      ('brine', 'Brine', 'Brine: +5% mining speed, safe for every metal.'),
                      ('frost_brine', 'Frost brine', 'Frost brine: steels come out Frost-tempered.')):
        L['aftrhrs.smithing.liquid.' + liq] = n
        L['aftrhrs.smithing.note.trough.' + liq] = d
    notes = {
        'heat': 'Heat: %s', 'forge.light': 'Light it with flint and steel', 'forge.fuel': 'Feed it coal, charcoal, blaze rods or lava',
        'forge.bellows': 'Bellows working: full heat', 'great.build': 'Not built yet: see the Almanac',
        'cast.empty': 'Molding box + 4 sand, or 4 clay balls', 'cast.sand': 'Sand %s/4', 'cast.clay': 'Clay %s/4',
        'cast.press': 'Press a part (or an ingot) in', 'cast.pour': 'Pour %s units from a ladle or spout', 'cast.fill': '%s %s/%s',
        'cast.cooling': 'Cooling... %ss', 'cast.ready': 'Ready: knock it out with a hammer',
        'anvil.empty': 'Lay a hot part or ingots here', 'anvil.heat': '%s° (works %s–%s°)', 'anvil.reheat': 'Too cold: back to the forge',
        'anvil.target': 'Forging: %s', 'anvil.how': 'Hammer in the sweet spot · Sneak: heavy', 'anvil.choose': 'Sneak-click: choose the part',
        'trough.empty': 'Fill with water (or fish oil)', 'trough.liquid': '%s %s/%s',
        'carve.empty': 'Lay wood, stone, bone, hide... here', 'carve.more': 'Add more, or sneak-click to choose', 'carve.wrap': 'Click with leather or hide to wrap',
        'carve.target': '%s: %s/%s cuts', 'carve.knife': 'Cut with the knife, on the beat', 'carve.chisel': 'Cut with the chisel, on the beat',
        'assembly.empty': 'Lay a head part down first', 'assembly.heading': 'Making: %s', 'assembly.rivet': 'Rivet it with the hammer',
        'assembly.tool': 'A part to swap, or its head material to repair', 'assembly.tap': 'Three hammer taps to repair',
        'rune.empty': 'Put a smithed tool in the middle', 'rune.slots': 'Free: %s upgrade · %s ability · %s defense',
        'rune.catalysts': 'Put catalysts round it', 'rune.etch': 'Etch with the chisel: %s/%s', 'wheel': 'Hold a bladed tool here · Sneak: grind off a modifier',
        'spout': 'Click (or redstone) to pour into the bench below', 'spout.pouring': 'Pouring %s',
        'crucible.empty': 'Empty (holds %s ingots)', 'crucible.fluid': '%s %s/%s', 'crucible.ready': 'Ready: %s · stir %s/3',
    }
    for k, v in notes.items():
        L['aftrhrs.smithing.note.' + k] = v
    alm = {
        'chapter.start': 'Getting Started', 'chapter.stations': 'Stations & Tools', 'chapter.materials': 'Materials', 'chapter.tools': 'Smithed Tools',
        'chapter.parts': 'Parts', 'chapter.modifiers': 'Modifiers', 'chapter.alloys': 'Alloys', 'chapter.heat': 'Heat & Quality',
        'start.1.title': '1. The stone age', 'start.1': 'No metal yet? Craft a Carving Bench and a Carving Knife. Lay cobblestone (with a chisel), flint, bone or wood on the bench, choose a part and cut in rhythm. Stone hammer faces carve too, so your first Smithing Hammer is two stone faces and a wooden handle.',
        'start.2.title': '2. Putting a tool together', 'start.2': 'On the Assembly Bench, lay the parts in order (head, binding, handle) and rivet with the hammer. Stars on each part add up to the tool\'s quality.',
        'start.3.title': '3. Fire', 'start.3': 'Build a Forge Hearth, feed it coal and light it with flint and steel. Put Bellows next to it, nozzle in, and pump for full heat. A fan blowing into it does the pumping for you.',
        'start.4.title': '4. Melting and casting', 'start.4': 'A Crucible on the hearth melts ingots, ore and old metal gear. Make a sand mold on the Casting Bench, ladle the metal in, let it cool and knock out a rough part.',
        'start.5.title': '5. The anvil', 'start.5': 'Heat the rough part in the forge until it glows, lay it on the Smithing Anvil and strike in the sweet spot. Each move of the plan needs a good strike. Your timing makes the stars. Ingots can be forged from scratch too.',
        'start.6.title': '6. Quenching', 'start.6': 'Dunk the hot part in the Quench Trough to set it: water, fish oil, brine or frost brine each give something. Unquenched metal stays soft.',
        'start.7.title': '7. Modifiers', 'start.7': 'Etch modifiers into a tool\'s slots at the Runesmith Table with catalysts and a chisel. Masterworks (all 5-star parts), books, music discs and boss trophies give extra slots. The Whetstone Wheel keeps blades sharp and grinds modifiers off.',
        'material.tier': 'Tier %s · %s', 'material.head': 'Head: %s durability · speed %s · mines %s · +%s damage',
        'material.handle': 'Handle: ×%s durability · ×%s speed · ×%s damage · ×%s attack speed',
        'material.limb': 'Limb: %s durability · draw ×%s · speed ×%s · +%s damage', 'material.plating': 'Plate: %s armor · %s toughness',
        'material.heat': 'Melts at %s° · works %s–%s°',
        'tool.parts': 'Parts: %s', 'tool.slots': 'Slots: %s upgrade · %s ability · %s defense', 'tool.sample': 'In iron: %s durability · %s damage · speed %s',
        'part.makes': 'Makes %s', 'part.made': 'Made: %s', 'part.plan': 'Anvil plan: %s',
        'made.cast': 'cast', 'made.anvil': 'forged', 'made.carve': 'carved',
        'modifier.slot': 'Slot: %s · up to level %s', 'modifier.per': 'Per level: %s %s (a few at a time)', 'modifier.needs': 'Needs: %s',
        'slot.u': 'upgrade', 'slot.a': 'ability', 'slot.d': 'defense', 'slot.none': 'slotless',
        'alloy.recipe': '%s → %s %s', 'alloy.heat': 'Needs %s° and three stirs', 'alloy.great': 'Only in the Great Crucible',
        'heat.fuel.title': 'Fuel', 'heat.fuel': 'Coal and charcoal burn to 1250° (875° without bellows). Blaze rods reach 1450°, lava 1650° and lights itself. Wood and other furnace fuels only reach 700°. Iron melts at 1150°, steel alloys at 1350°+, netherite at 1550°.',
        'heat.colors.title': 'Reading the heat', 'heat.colors': 'Dull red 400–600°, cherry 600–800°, orange 800–1000°, yellow 1000–1200°, white above. Each metal works in its own window; above it, a part on the coals scales and loses a star every few seconds.',
        'heat.quality.title': 'Stars and quality', 'heat.quality': 'Each part has 1–5 stars from how well it was made. A tool\'s quality is their average: ★ ×0.85, ★★★ ×1.0, ★★★★★ ×1.15 on durability, speed and damage. All five-star parts make a Masterwork: +1 upgrade slot and a golden name.',
        'heat.quench.title': 'Quenching', 'heat.quench': 'Water: +10% durability (tier-3+ parts dunked above their window crack). Fish oil: +5% attack speed. Brine: +5% mining speed. Frost brine: Frost-tempered steel. Steel\'s Tempered trait doubles all of it.',
    }
    for k, v in alm.items():
        L['aftrhrs.smithing.almanac.' + k] = v
    for i in range(1, 8):
        pass
    station_lang = {'forge': 'forge', 'bellows': 'bellows', 'crucible': 'crucible', 'casting_bench': 'casting_bench', 'anvil': 'anvil', 'trough': 'trough',
                    'carving_bench': 'carving_bench', 'assembly_bench': 'assembly_bench', 'wheel': 'wheel', 'rune_table': 'rune_table', 'spout': 'spout',
                    'fire_bricks': 'fire_bricks', 'great_drain': 'great_drain'}
    for k in station_lang:
        L.setdefault('aftrhrs.smithing.almanac.station.' + k, STATION_TEXT[k])
    wjson(os.path.join(ASSETS, 'lang', 'en_us.smithing.json'), dict(sorted(L.items())))
    return len(L)


def main():
    sys.path.insert(0, HERE)
    import smithing_blocks
    smithing_blocks.main()
    for part in PARTS:
        part_item(part)
    for tool in TOOLS:
        tool_item(tool)
    misc_items()
    blockstates()
    equipment()
    recipes()
    tags()
    # tool models no longer written (frames smithing_art.py aliases to an identical texture, or a dropped layer)
    for root, _, files in os.walk(os.path.join(ASSETS, 'models', 'item', 'smithing', 'tool')):
        for f in files:
            p = os.path.join(root, f)
            if f.endswith('.json') and os.path.normcase(os.path.abspath(p)) not in TOOL_MODELS:
                os.remove(p)
    n = lang()
    print('smithing: %d parts, %d tools, %d stations, %d lang keys' % (len(PARTS), len(TOOLS), len(STATIONS), n))


if __name__ == '__main__':
    main()
