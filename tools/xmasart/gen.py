#!/usr/bin/env python3
"""Aftr Hrs Christmas set: trees, ornaments, decorations, presents, festive blocks.

Writes the in-repo content root (read by tools/gen_content.py, Oraxen layout):
  mod/content/pack/textures/arcana/christmas/*.png      original pixel art (tex.py)
  mod/content/pack/models/arcana/christmas/*.json       block / item models (pieces.py)
  mod/content/items/arcana_christmas.yml                items, furniture, blocks
  mod/content/recipes/{shaped,shapeless}.christmas.yml  recipes
  mod/src/main/java/net/axther/aftrhrs/feature/christmas/TreeSlots.java  ornament slots of the fir tree
and review renders to Documents/Blockbench/models/arcana_items/christmas/renders (arcana_items/plants3d/mcrender.py).
Blockbench copies of the models: arcana_items/christmas/bbmodel (made with the blockbench-headless importer).

Run: python mod/tools/xmasart/gen.py [--no-render]   then  python mod/tools/gen_content.py
Checks (must print nothing under PROBLEMS): coplanar faces, -16..32 bounds, UVs, angles, texture refs, recipe clashes.
"""
import glob
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import geom  # noqa: E402
import pieces as P  # noqa: E402
import tex  # noqa: E402

MOD = os.path.dirname(os.path.dirname(HERE))
CONTENT = os.path.join(MOD, 'content')
TEX_DIR = os.path.join(CONTENT, 'pack', 'textures', 'arcana', 'christmas')
MODEL_DIR = os.path.join(CONTENT, 'pack', 'models', 'arcana', 'christmas')
ITEMS_YML = os.path.join(CONTENT, 'items', 'arcana_christmas.yml')
RECIPES = os.path.join(CONTENT, 'recipes')
JAVA_SLOTS = os.path.join(MOD, 'src', 'main', 'java', 'net', 'axther', 'aftrhrs', 'feature', 'christmas', 'TreeSlots.java')
ARCANA = r'C:/Users/kevin/Documents/Blockbench/models/arcana_items'
SET_DIR = os.path.join(ARCANA, 'christmas')
RENDERS = os.path.join(SET_DIR, 'renders')
JAR = os.path.expanduser('~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/'
                         'minecraft-clientonly-deobf-26.3.jar')
X = P.X
PROBLEMS = []
MODELS = {}   # model name -> elements (for checks / renders)


# ================================================================ model writing
def half(els):
    """Geometry at half size about (8, 8, 8), UVs kept: tall pieces are stored like this (the -16..32 model range) and
    drawn at full size (furniture `properties.scale: 2`), so their textures keep a 1:1 density."""
    out = []
    for e in els:
        e = json.loads(json.dumps(e))
        e['from'] = [round(8 + (v - 8) / 2, 4) for v in e['from']]
        e['to'] = [round(8 + (v - 8) / 2, 4) for v in e['to']]
        if 'rotation' in e:
            e['rotation']['origin'] = [round(8 + (v - 8) / 2, 4) for v in e['rotation']['origin']]
        out.append(e)
    return out


def write_model(name, els, textures, disp=None, scale2=False, **dkw):
    for p in geom.coplanar(els):
        PROBLEMS.append(f'{name}: coplanar {p}')
    full = els
    if scale2:
        els = half(els)
    for p in geom.check(els):
        PROBLEMS.append(f'{name}: {p}')
    used = {fc['texture'][1:] for e in els for fc in e['faces'].values()}
    missing = sorted(used - set(textures))
    if missing:
        PROBLEMS.append(f'{name}: unset texture vars {missing}')
    m = {'credit': 'Aftr Hrs Christmas set (mod/tools/xmasart/gen.py)', 'textures': textures, 'elements': els,
         'display': disp or geom.display(els, **dkw)}
    os.makedirs(MODEL_DIR, exist_ok=True)
    with open(os.path.join(MODEL_DIR, name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(m, f, indent=1)
    MODELS[name] = (full, textures)
    return f'arcana/christmas/{name}'


def write_cube(name, textures, parent='block/cube_all'):
    os.makedirs(MODEL_DIR, exist_ok=True)
    with open(os.path.join(MODEL_DIR, name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump({'parent': parent, 'textures': textures}, f, indent=1)
    return f'arcana/christmas/{name}'


HAT_HEAD = {'rotation': [0, 0, 0], 'translation': [0, 6.4, 0], 'scale': [1.6, 1.6, 1.6]}

# ================================================================ catalogue
ENTRIES = []    # yml blocks (strings)
SHAPED = []
SHAPELESS = []
IDS = []


def snd(group, pitch=1.0):
    s = f'block.{group}'
    return ('      block_sounds:\n'
            f'        place: {{ sound: {s}.place, volume: 1.0, pitch: {pitch} }}\n'
            f'        break: {{ sound: {s}.break, volume: 1.0, pitch: {pitch} }}\n'
            f'        hit: {{ sound: {s}.hit, volume: 0.5, pitch: {pitch} }}\n'
            f'        step: {{ sound: {s}.step, volume: 0.3, pitch: 1.0 }}\n'
            f'        fall: {{ sound: {s}.fall, volume: 0.5, pitch: 1.0 }}\n')


def head(iid, name, lore, model, hidden=False):
    IDS.append(iid)
    out = [f'{iid}:', f'  itemname: "{name}"', '  material: PAPER']
    if hidden:
        out.append('  excludeFromInventory: true')
    if lore:
        out.append('  lore:')
        out += [f'  - "{line}"' for line in lore]
    out += ['  pack:', '    generate_model: false', f'    model: {model}']
    return out


def furniture(iid, name, lore, model, *, place='floor', barriers=None, hitboxes=None, lights=None, rotation='VERY_STRICT',
              rotatable=True, sound=('wood', 1.0), storage=None, extra=None, state_models=None, stages=None, models=None,
              scale2=False):
    out = head(iid, name, lore, model)
    if state_models:
        out.append('    state_models:')
        for prop, cases in state_models.items():
            out.append(f'      {prop}:')
            out += [f'        "{k}": {v}' for k, v in cases.items()]
    if models:
        out.append('    models:')
        out += [f'      {k}: {v}' for k, v in models.items()]
    out += ['  mechanics:', '    furniture:', '      type: DISPLAY_ENTITY', '      properties:',
            '        display_transform: NONE']
    if scale2:
        out += ['        scale: { x: 2.0, y: 2.0, z: 2.0 }']
    if barriers:
        out += ['      barriers:'] + [f'      - {b}' for b in barriers]
    if hitboxes:
        out += ['      hitboxes:'] + [f'      - "{h}"' for h in hitboxes]
    if lights:
        out += ['      lights:'] + [f'      - {l}' for l in lights]
    out += [f'      rotatable: {"true" if rotatable else "false"}', f'      restricted_rotation: {rotation}',
            '      limited_placing:'] + [f'        {k}: {"true" if k == place else "false"}' for k in ('floor', 'wall', 'roof')]
    body = '\n'.join(out) + '\n' + snd(*sound)
    if storage:
        rows, title = storage
        body += ('      storage:\n        type: STORAGE\n'
                 f'        rows: {rows}\n        title: "{title}"\n'
                 '        open_sound: block.wool.place\n        close_sound: block.wool.break\n'
                 '        volume: 0.7\n        pitch: 1.2\n')
    if stages:
        body += '      stages:\n'
        for key in stages:
            body += (f'      - model: {key}\n        evolution: {{ delay: 100000, probability: 0.0 }}\n'
                     f'        drop: {{ silktouch: false, loots: [ {{ oraxen_item: {iid}, probability: 1.0 }} ] }}\n')
    if extra:
        body += '      aftrhrs:\n' + ''.join(f'        {k}: {json.dumps(v)}\n' for k, v in extra.items())
    body += ('      drop:\n        silktouch: false\n        loots:\n'
             f'        - {{ oraxen_item: {iid}, probability: 1.0 }}\n')
    ENTRIES.append(body)


def item(iid, name, lore, model, components=None, hidden=False):
    out = head(iid, name, lore, model, hidden)
    if components:
        out.append('  components:')
        for k, v in components.items():
            if isinstance(v, dict):
                out.append(f'    {k}:')
                out += [f'      {kk}: {vv}' for kk, vv in v.items()]
            else:
                out.append(f'    {k}: {v}')
    ENTRIES.append('\n'.join(out) + '\n')


def block(iid, name, lore, model, btype='FULL', sound='stone', tool=None, hardness=1.0, texture=None):
    out = head(iid, name, lore, model if btype == 'FULL' else f'block/{iid}')
    out += ['  mechanics:', '    block:', f'      type: {btype}']
    if btype == 'FULL':
        out += ['      appearance:', f'        model: {model}']
    else:
        out += [f'      model: {iid}', '      textures:', f'        all: {texture}']
    body = '\n'.join(out) + '\n' + snd(sound).replace('      block_sounds', '      block_sounds')
    body += '      breaking:\n'
    if tool:
        body += (f"      - when:\n        - '{tool}'\n        hardness: {hardness / 3:.2f}\n"
                 f'        drops:\n        - item: {iid}\n          probability: 1.0\n')
    body += (f'      - else:\n        hardness: {hardness}\n        drops:\n        - item: {iid}\n'
             '          probability: 1.0\n')
    ENTRIES.append(body)


def shaped(iid, shape, ing, amount=1):
    s = f'{iid}:\n  shape:\n' + ''.join(f'  - {r}\n' for r in shape) + '  ingredients:\n'
    for k, v in ing.items():
        key = 'oraxen_item' if v.startswith('arcana_') else 'minecraft_type'
        s += f'    {k}:\n      {key}: {v}\n'
    s += f'  result:\n    oraxen_item: {iid}\n'
    if amount > 1:
        s += f'    amount: {amount}\n'
    SHAPED.append((iid, shape, ing, s))


def shapeless(iid, ings, amount=1, name=None):
    counts = {}
    for v in ings:
        counts[v] = counts.get(v, 0) + 1
    s = f'{name or iid}:\n  ingredients:\n'
    for i, (v, n) in enumerate(counts.items()):
        key = 'oraxen_item' if v.startswith('arcana_') else 'minecraft_type'
        s += f'    {chr(65 + i)}:\n      {key}: {v}\n'
        if n > 1:
            s += f'      amount: {n}\n'
    s += f'  result:\n    oraxen_item: {iid}\n'
    if amount > 1:
        s += f'    amount: {amount}\n'
    SHAPELESS.append((name or iid, ings, s))


# ================================================================ build everything
def build():
    tree_lights = ['0,0,0 10', '0,1,0 10', '0,2,0 10']
    three = ['origin', '{y: 1}', '{y: 2}']
    # ---------------------------------------------------------------- trees
    els, t = P.decorated_tree('classic')
    m = write_model('christmas_tree', els, t, scale2=True, gui=15.5)
    furniture('arcana_christmas_tree', 'Christmas Tree',
              ['<gray>A tall fir hung with baubles,', '<gray>tinsel and twinkling lights.', '<dark_gray>Right-click to switch the lights'],
              m, barriers=three, lights=tree_lights, sound=('azalea_leaves', 1.0), scale2=True)
    shapeless('arcana_christmas_tree', ['arcana_fir_tree', 'arcana_tree_lights', 'arcana_tree_star', 'arcana_red_bauble',
                                        'arcana_red_bauble', 'arcana_gold_bauble', 'arcana_gold_bauble'])
    els, t = P.decorated_tree('frosted')
    m = write_model('frosted_christmas_tree', els, t, scale2=True, gui=15.5)
    furniture('arcana_frosted_christmas_tree', 'Frosted Christmas Tree',
              ['<gray>A snow-dusted fir with warm', '<gray>lights and an angel on top.', '<dark_gray>Right-click to switch the lights'],
              m, barriers=three, lights=tree_lights, sound=('azalea_leaves', 1.0), scale2=True)
    shapeless('arcana_frosted_christmas_tree', ['arcana_fir_tree', 'arcana_tree_lights', 'arcana_tree_angel',
                                                'arcana_silver_bauble', 'arcana_silver_bauble', 'arcana_blue_bauble',
                                                'arcana_blue_bauble', 'SNOWBALL'])
    els, t = P.fir_tree()
    m = write_model('fir_tree', els, t, scale2=True, gui=15.5)
    furniture('arcana_fir_tree', 'Fir Tree',
              ['<gray>A fresh fir, ready to decorate.', '<dark_gray>Hang baubles, candy canes and', '<dark_gray>gingerbread on it; top it with',
               '<dark_gray>a star or angel; add tree lights.', '<dark_gray>Sneak + right-click takes one off'],
              m, barriers=three, lights=tree_lights, sound=('azalea_leaves', 1.0), scale2=True,
              extra={'_toggle': 'lit', '_toggle_default': False, '_toggle_player': False})
    shaped('arcana_fir_tree', ['_L_', 'LLL', '_S_'], dict(L='SPRUCE_LEAVES', S='SPRUCE_LOG'))
    for lit in (True, False):
        els, t = P.fir_lights(lit)
        m = write_model('fir_tree_lights_' + ('lit' if lit else 'unlit'), els, t)
        item('arcana_fir_tree_lights_' + ('lit' if lit else 'unlit'), 'Fir Tree Lights', None, m, hidden=True)
    els, t = P.tabletop_tree()
    m = write_model('tabletop_tree', els, t)
    furniture('arcana_tabletop_tree', 'Tabletop Christmas Tree',
              ['<gray>A little tree in a crate,', '<gray>for a table or windowsill.', '<dark_gray>Right-click to switch the lights'],
              m, hitboxes=['0,0,0 0.5,1'], lights=['0,0,0 8'], rotation='STRICT', sound=('azalea_leaves', 1.2))
    shaped('arcana_tabletop_tree', ['_N_', '_L_', '_P_'], dict(N='GOLD_NUGGET', L='SPRUCE_LEAVES', P='SPRUCE_SLAB'))

    # ---------------------------------------------------------------- ornaments and toppers (items)
    dyes = {'red': 'RED_DYE', 'gold': 'YELLOW_DYE', 'silver': 'LIGHT_GRAY_DYE', 'blue': 'BLUE_DYE', 'green': 'GREEN_DYE',
            'purple': 'PURPLE_DYE'}
    for c, dye in dyes.items():
        els, t = P.bauble_item(c)
        m = write_model(f'{c}_bauble', els, t, gui=11, gui_rot=(20, 200, 0), hand=6, ground=5)
        item(f'arcana_{c}_bauble', f'{c.title()} Bauble', ['<gray>A glass tree ornament.', '<dark_gray>Right-click a Fir Tree to hang it'], m)
        shaped(f'arcana_{c}_bauble', ['N', 'G', 'D'], dict(N='GOLD_NUGGET' if c == 'gold' else 'IRON_NUGGET', G='GLASS', D=dye), 4)
    els, t = P.star_item()
    m = write_model('tree_star', els, t, gui=14, gui_rot=(10, 200, 0), hand=7, ground=6)
    item('arcana_tree_star', 'Tree Star', ['<gray>A shining star for the top', '<gray>of a tree.', '<dark_gray>Right-click a Fir Tree to place it'], m)
    shaped('arcana_tree_star', ['_N_', 'NGN', '_N_'], dict(N='GOLD_NUGGET', G='GLOWSTONE_DUST'))
    els, t = P.angel_item()
    m = write_model('tree_angel', els, t, gui=14, gui_rot=(15, 200, 0), hand=7, ground=6)
    item('arcana_tree_angel', 'Tree Angel', ['<gray>A little angel with a', '<gray>glowing halo.', '<dark_gray>Right-click a Fir Tree to place it'], m)
    shaped('arcana_tree_angel', ['_N_', 'FWF', '_W_'], dict(N='GOLD_NUGGET', F='FEATHER', W='WHITE_WOOL'))
    els, t = P.tree_lights_item()
    m = write_model('tree_lights', els, t, gui=13, gui_rot=(35, 200, 0), hand=7, ground=6)
    item('arcana_tree_lights', 'Tree Lights', ['<gray>A coil of twinkling lights.', '<dark_gray>Right-click a Fir Tree to wind them on'], m)
    shapeless('arcana_tree_lights', ['STRING', 'GLOWSTONE_DUST', 'RED_DYE', 'YELLOW_DYE', 'GREEN_DYE', 'BLUE_DYE'])
    els, t = P.candy_cane_item()
    m = write_model('candy_cane', els, t, gui=13, gui_rot=(10, 200, 0), hand=7, ground=6)
    item('arcana_candy_cane', 'Candy Cane', ['<gray>Peppermint, red and white.', '<dark_gray>Hangs on a Fir Tree too'], m,
         components={'food': '{ nutrition: 2, saturation: 0.6, can_always_eat: true }', 'consumable': '{ consume_seconds: 1.0 }'})
    shapeless('arcana_candy_cane', ['SUGAR', 'SUGAR', 'RED_DYE'], 3)
    els, t = P.gingerbread_man_item()
    m = write_model('gingerbread_man', els, t, gui=13, gui_rot=(10, 200, 0), hand=7, ground=6)
    item('arcana_gingerbread_man', 'Gingerbread Man', ['<gray>A spiced cookie with', '<gray>icing buttons.', '<dark_gray>Hangs on a Fir Tree too'], m,
         components={'food': '{ nutrition: 4, saturation: 0.5 }', 'consumable': '{ consume_seconds: 1.2 }'})
    shapeless('arcana_gingerbread_man', ['WHEAT', 'WHEAT', 'SUGAR', 'EGG', 'HONEY_BOTTLE'], 4)

    # ---------------------------------------------------------------- decorations
    els, t = P.wreath(False)
    m = write_model('wreath', els, t)
    furniture('arcana_wreath', 'Christmas Wreath', ['<gray>Fir, holly and a red bow.', '<dark_gray>Click a wall to hang it'],
              m, place='wall', hitboxes=['0,0,0 1,1'], sound=('azalea_leaves', 1.1))
    shaped('arcana_wreath', ['LLL', 'L_L', 'LRL'], dict(L='SPRUCE_LEAVES', R='RED_WOOL'))
    els, t = P.wreath(True)
    m = write_model('lit_wreath', els, t)
    furniture('arcana_lit_wreath', 'Lit Christmas Wreath', ['<gray>A wreath wound with warm lights.',
                                                            '<dark_gray>Click a wall to hang it;', '<dark_gray>right-click switches the lights'],
              m, place='wall', hitboxes=['0,0,0 1,1'], lights=['0,0,0 8'], sound=('azalea_leaves', 1.1))
    shapeless('arcana_lit_wreath', ['arcana_wreath', 'arcana_tree_lights'])
    els, t = P.mantel_garland()
    m = write_model('mantel_garland', els, t)
    furniture('arcana_mantel_garland', 'Mantel Garland', ['<gray>Greenery, bows and lights to', '<gray>drape along a mantel or shelf.',
                                                          '<dark_gray>Place on top of a hearth;', '<dark_gray>right-click switches the lights'],
              m, hitboxes=['0,0,0 1,0.25'], lights=['0,0,0 7'], sound=('azalea_leaves', 1.1))
    shaped('arcana_mantel_garland', ['LLL', 'RGR'], dict(L='SPRUCE_LEAVES', R='RED_DYE', G='GLOWSTONE_DUST'), 2)
    for c, body, cuff, wool, cw in (('red', 'block/red_wool', X + 'fur_white', 'RED_WOOL', 'WHITE_WOOL'),
                                    ('green', 'block/green_wool', X + 'fur_white', 'GREEN_WOOL', 'WHITE_WOOL'),
                                    ('blue', 'block/blue_wool', X + 'fur_white', 'BLUE_WOOL', 'WHITE_WOOL'),
                                    ('white', 'block/white_wool', 'block/red_wool', 'WHITE_WOOL', 'RED_WOOL')):
        els, t = P.stocking(body, cuff)
        m = write_model(f'{c}_stocking', els, t)
        furniture(f'arcana_{c}_stocking', f'{c.title()} Stocking',
                  ['<gray>Hang it on a hearth or wall', '<gray>and fill it with little gifts.', '<dark_gray>Right-click to open'],
                  m, place='wall', hitboxes=['0,0,0 0.75,1'], rotatable=False, storage=(1, f'{c.title()} Stocking'),
                  sound=('wool', 1.2))
        shaped(f'arcana_{c}_stocking', ['U_', 'C_', 'CC'], dict(U=cw, C=wool))
    papers = [('red', 'gold', 'RED_DYE'), ('green', 'red', 'GREEN_DYE'), ('blue', 'silver', 'BLUE_DYE'),
              ('gold', 'red', 'YELLOW_DYE'), ('white', 'green', 'WHITE_DYE')]
    for c, r, dye in papers:
        els, t = P.present(True, X + 'wrap_' + c, X + 'ribbon_' + r)
        m_open = write_model(f'{c}_present', els, t, gui=13)
        els, t = P.present(False, X + 'wrap_' + c, X + 'ribbon_' + r)
        m_wrap = write_model(f'{c}_present_wrapped', els, t, gui=13)
        furniture(f'arcana_{c}_present', f'{c.title()} Present',
                  ['<gray>Put gifts inside, then sneak +', '<gray>right-click to wrap it and', '<gray>write who it is for.',
                   '<dark_gray>Only they can open it'],
                  m_open, hitboxes=['0,0,0 0.75,0.625'], rotatable=False, sound=('wool', 1.3),
                  extra={'_toggle': 'open', '_toggle_default': True, '_toggle_player': False, '_off_model': m_wrap},
                  state_models={'open': {'false': m_wrap}})
        shaped(f'arcana_{c}_present', ['PSP', 'PDP', 'PPP'], dict(P='PAPER', S='STRING', D=dye))
    els, t = P.present_pile()
    m = write_model('present_pile', els, t)
    furniture('arcana_present_pile', 'Pile of Presents', ['<gray>A stack of wrapped gifts', '<gray>to set under the tree.'],
              m, hitboxes=['0,0,0 1,0.75'], sound=('wool', 1.3))
    shapeless('arcana_present_pile', ['PAPER', 'PAPER', 'PAPER', 'STRING', 'RED_DYE', 'GREEN_DYE', 'BLUE_DYE'])
    els, t = P.giant_candy_cane()
    m = write_model('giant_candy_cane', els, t)
    furniture('arcana_giant_candy_cane', 'Giant Candy Cane', ['<gray>A lawn-sized candy cane', '<gray>with a green bow.'],
              m, hitboxes=['0,0,0 0.375,1.5'], rotation='STRICT', sound=('calcite', 1.2))
    shaped('arcana_giant_candy_cane', ['BB', '_B', '_B'], dict(B='arcana_candy_cane_block'))
    els, t = P.snow_globe()
    m = write_model('snow_globe', els, t)
    furniture('arcana_snow_globe', 'Snow Globe', ['<gray>A tiny snowy village', '<gray>under glass.', '<dark_gray>Right-click to shake it'],
              m, hitboxes=['0,0,0 0.5,0.625'], rotation='STRICT', sound=('glass', 1.3), rotatable=False)
    shaped('arcana_snow_globe', ['_G_', 'GSG', '_P_'], dict(G='GLASS', S='SNOWBALL', P='DARK_OAK_SLAB'))
    for c, dye in (('red', 'RED_DYE'), ('blue', 'BLUE_DYE')):
        els, t = P.nutcracker(c)
        m = write_model(f'{c}_nutcracker', els, t)
        els, t = P.nutcracker_open(c)
        m2 = write_model(f'{c}_nutcracker_open', els, t)
        furniture(f'arcana_{c}_nutcracker', f'{c.title()} Nutcracker', ['<gray>A painted wooden soldier.', '<dark_gray>Right-click to work his jaw'],
                  m, hitboxes=['0,0,0 0.5,1'], rotation='STRICT', sound=('wood', 1.2),
                  # the toggle runs "backwards" so the item and a new piece show the closed mouth: open=true is
                  # the pack model (mouth shut), open=false the dropped jaw
                  extra={'_toggle': 'open', '_toggle_default': True, '_toggle_player': True, '_toggle_sound': 'wood',
                         '_off_model': m2})
        shaped(f'arcana_{c}_nutcracker', ['_B_', 'DPD', '_S_'], dict(B='BLACK_WOOL', D=dye, P='SPRUCE_PLANKS', S='STICK'))
    els, t = P.christmas_candle()
    m = write_model('christmas_candle', els, t)
    furniture('arcana_christmas_candle', 'Christmas Candle', ['<gray>A red pillar candle ringed', '<gray>with holly.',
                                                              '<dark_gray>Right-click to light or snuff'],
              m, hitboxes=['0,0,0 0.5,0.75'], lights=['0,0,0 10'], rotation='STRICT', sound=('candle', 1.0))
    shapeless('arcana_christmas_candle', ['RED_CANDLE', 'SPRUCE_LEAVES', 'GOLD_NUGGET'])
    stage_models = {}
    for n in range(5):
        els, t = P.advent_wreath(n)
        stage_models[f'lit{n}'] = write_model(f'advent_wreath_{n}', els, t)
    furniture('arcana_advent_wreath', 'Advent Wreath', ['<gray>Four candles: light one more', '<gray>each week before Christmas.',
                                                        '<dark_gray>Flint and steel lights the next;', '<dark_gray>an empty hand snuffs them'],
              stage_models['lit0'], hitboxes=['0,0,0 1,0.5'], sound=('candle', 1.0), rotatable=False,
              stages=list(stage_models), models=stage_models, extra={'_stage_light': [0, 4, 7, 9, 11]})
    shapeless('arcana_advent_wreath', ['PURPLE_CANDLE', 'PURPLE_CANDLE', 'PURPLE_CANDLE', 'PINK_CANDLE', 'SPRUCE_LEAVES',
                                       'SPRUCE_LEAVES'])
    for c, scarf, dye in (('red', 'block/red_wool', 'RED_DYE'), ('green', 'block/green_wool', 'GREEN_DYE')):
        els, t = P.snowman(scarf)
        m = write_model(f'{c}_scarf_snowman', els, t)
        furniture(f'arcana_{c}_scarf_snowman', 'Snowman' if c == 'red' else 'Snowman (Green Scarf)',
                  ['<gray>A snowman in a top hat', '<gray>that never melts.'],
                  m, barriers=['origin'], rotation='STRICT', sound=('snow', 1.0))
        shaped(f'arcana_{c}_scarf_snowman', ['_C_', 'DSD', '_S_'], dict(C='COAL', D=dye, S='SNOW_BLOCK'))
    els, t = P.mistletoe()
    m = write_model('mistletoe', els, t)
    furniture('arcana_mistletoe', 'Mistletoe', ['<gray>Hang it from a ceiling.', '<gray>Stand beneath it with someone...'],
              m, place='roof', sound=('azalea_leaves', 1.3))
    shapeless('arcana_mistletoe', ['VINE', 'BONE_MEAL', 'RED_DYE'])
    els, t = P.christmas_lantern()
    m = write_model('christmas_lantern', els, t)
    furniture('arcana_christmas_lantern', 'Christmas Lantern', ['<gray>A red lantern with a', '<gray>sprig of holly on top.',
                                                                '<dark_gray>Right-click to switch it'],
              m, hitboxes=['0,0,0 0.5,1'], lights=['0,0,0 14'], rotation='STRICT', sound=('lantern', 1.0))
    shapeless('arcana_christmas_lantern', ['LANTERN', 'RED_DYE', 'SPRUCE_LEAVES'])
    els, t = P.santa_hat()
    m = write_model('santa_hat', els, t, gui=15, gui_rot=(25, 210, 0), head=HAT_HEAD)
    item('arcana_hat_santa_hat', 'Santa Hat', ['<gray>Red felt, white fur and', '<gray>a floppy pompom.', '<dark_gray>Right-click to wear'], m,
         components={'max_stack_size': 64, 'equippable': {'slot': 'HEAD'}})
    shaped('arcana_hat_santa_hat', ['_W_', 'RRR', 'WWW'], dict(W='WHITE_WOOL', R='RED_WOOL'))

    # ---------------------------------------------------------------- building blocks
    m = write_cube('gingerbread_block', {'all': X + 'gingerbread'})
    block('arcana_gingerbread_block', 'Gingerbread Block', None, m, sound='packed_mud', tool='#minecraft:axes', hardness=1.0)
    shaped('arcana_gingerbread_block', ['GG', 'GG'], dict(G='arcana_gingerbread_man'))
    block('arcana_gingerbread_stairs', 'Gingerbread Stairs', None, None, 'STAIR', 'packed_mud', '#minecraft:axes', 1.0, X + 'gingerbread')
    shaped('arcana_gingerbread_stairs', ['G__', 'GG_', 'GGG'], dict(G='arcana_gingerbread_block'), 4)
    block('arcana_gingerbread_slab', 'Gingerbread Slab', None, None, 'SLAB', 'packed_mud', '#minecraft:axes', 1.0, X + 'gingerbread')
    shaped('arcana_gingerbread_slab', ['GGG'], dict(G='arcana_gingerbread_block'), 6)
    m = write_cube('gingerbread_bricks', {'all': X + 'gingerbread_bricks'})
    block('arcana_gingerbread_bricks', 'Gingerbread Bricks', None, m, sound='mud_bricks', tool='#minecraft:axes', hardness=1.2)
    shaped('arcana_gingerbread_bricks', ['GG', 'GG'], dict(G='arcana_gingerbread_block'), 4)
    block('arcana_gingerbread_brick_stairs', 'Gingerbread Brick Stairs', None, None, 'STAIR', 'mud_bricks', '#minecraft:axes', 1.2,
          X + 'gingerbread_bricks')
    shaped('arcana_gingerbread_brick_stairs', ['G__', 'GG_', 'GGG'], dict(G='arcana_gingerbread_bricks'), 4)
    block('arcana_gingerbread_brick_slab', 'Gingerbread Brick Slab', None, None, 'SLAB', 'mud_bricks', '#minecraft:axes', 1.2,
          X + 'gingerbread_bricks')
    shaped('arcana_gingerbread_brick_slab', ['GGG'], dict(G='arcana_gingerbread_bricks'), 6)
    m = write_cube('frosted_gingerbread', {'top': X + 'frosted_gingerbread_top', 'bottom': X + 'gingerbread',
                                           'side': X + 'frosted_gingerbread_side'}, parent='block/cube_bottom_top')
    block('arcana_frosted_gingerbread', 'Frosted Gingerbread', None, m, sound='packed_mud', tool='#minecraft:axes', hardness=1.0)
    shapeless('arcana_frosted_gingerbread', ['arcana_gingerbread_block', 'arcana_frosting_block'], 2)
    m = write_cube('frosting_block', {'all': X + 'frosting'})
    block('arcana_frosting_block', 'Frosting Block', None, m, sound='snow', tool='#minecraft:shovels', hardness=0.4)
    shaped('arcana_frosting_block', ['SS', 'SS'], dict(S='SUGAR'))
    m = write_cube('candy_cane_block', {'all': X + 'candy_cane_block'})
    block('arcana_candy_cane_block', 'Candy Cane Block', None, m, sound='calcite', tool='#minecraft:pickaxes', hardness=0.8)
    shaped('arcana_candy_cane_block', ['CC', 'CC'], dict(C='arcana_candy_cane'))
    block('arcana_candy_cane_stairs', 'Candy Cane Stairs', None, None, 'STAIR', 'calcite', '#minecraft:pickaxes', 0.8, X + 'candy_cane_block')
    shaped('arcana_candy_cane_stairs', ['C__', 'CC_', 'CCC'], dict(C='arcana_candy_cane_block'), 4)
    block('arcana_candy_cane_slab', 'Candy Cane Slab', None, None, 'SLAB', 'calcite', '#minecraft:pickaxes', 0.8, X + 'candy_cane_block')
    shaped('arcana_candy_cane_slab', ['CCC'], dict(C='arcana_candy_cane_block'), 6)
    m = write_cube('peppermint_block', {'end': X + 'peppermint_top', 'side': X + 'peppermint_side'}, parent='block/cube_column')
    block('arcana_peppermint_block', 'Peppermint Block', None, m, sound='calcite', tool='#minecraft:pickaxes', hardness=0.8)
    shaped('arcana_peppermint_block', ['SRS', 'RSR', 'SRS'], dict(S='SUGAR', R='RED_DYE'), 4)


# ================================================================ java slots
def write_slots():
    slots, top = P.fir_slots()
    rows = ',\n'.join(f'            {{{x}f, {y}f, {z}f, {a}f}}' for x, y, z, a in slots)
    src = f'''package net.axther.aftrhrs.feature.christmas;

/**
 * Where ornaments hang on the Fir Tree, in block-model pixels of the tree's origin block (facing north): x, y, z and
 * the outward angle in degrees (0 = east, 90 = south). Generated by mod/tools/xmasart/gen.py from the tree geometry;
 * do not edit by hand.
 */
public final class TreeSlots {{
    public static final float[][] SLOTS = {{
{rows}
    }};
    /** The tip, where a star or angel goes. */
    public static final float[] TOP = {{{top[0]}f, {top[1]}f, {top[2]}f}};

    private TreeSlots() {{
    }}
}}
'''
    os.makedirs(os.path.dirname(JAVA_SLOTS), exist_ok=True)
    with open(JAVA_SLOTS, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)


# ================================================================ recipe clash check
def _jar_recipes():
    z = zipfile.ZipFile(JAR)
    tags = {}
    for n in z.namelist():
        if n.startswith('data/minecraft/tags/item/') and n.endswith('.json'):
            tags['minecraft:' + n[len('data/minecraft/tags/item/'):-5]] = json.loads(z.read(n)).get('values', [])

    def expand(v, seen=()):
        if isinstance(v, dict):
            v = v.get('id') or v.get('item') or v.get('tag')
        if isinstance(v, list):
            out = set()
            for x in v:
                out |= expand(x, seen)
            return out
        if v.startswith('#'):
            t = v[1:]
            if t in seen:
                return set()
            out = set()
            for x in tags.get(t, []):
                out |= expand(x, seen + (t,))
            return out
        return {v}
    out = []
    for n in z.namelist():
        if n.startswith('data/minecraft/recipe/') and n.endswith('.json'):
            out.append((n, json.loads(z.read(n))))
    return out, expand


def _norm_shape(shape):
    rows = [r.replace('_', ' ') for r in shape]
    w = max(len(r) for r in rows)
    rows = [r.ljust(w) for r in rows]
    while rows and not rows[0].strip():
        rows.pop(0)
    while rows and not rows[-1].strip():
        rows.pop()
    cols = [i for i in range(w) if any(r[i] != ' ' for r in rows)]
    return [''.join(r[i] for i in range(cols[0], cols[-1] + 1)) for r in rows] if cols else []


def _grid(shape, key):
    rows = _norm_shape(shape)
    return [[key[c] if c != ' ' else None for c in r] for r in rows]


def _grids_clash(a, b):
    if len(a) != len(b) or len(a[0]) != len(b[0]):
        return False
    for g in (b, [list(reversed(r)) for r in b]):
        if all((x is None) == (y is None) and (x is None or x & y) for ra, rb in zip(a, g) for x, y in zip(ra, rb)):
            return True
    return False


def _bag_clash(a, b):
    if len(a) != len(b):
        return False
    b = list(b)

    def match(i, used):
        if i == len(a):
            return True
        for j in range(len(b)):
            if j not in used and a[i] & b[j]:
                if match(i + 1, used | {j}):
                    return True
        return False
    return match(0, frozenset())


def check_recipes():
    vanilla, expand = _jar_recipes()

    def mc(v):
        return {'aftrhrs:' + v} if v.startswith('arcana_') else {'minecraft:' + v.lower()}
    others = []  # (name, kind, data)
    for n, r in vanilla:
        t = r.get('type', '')
        if t == 'minecraft:crafting_shaped':
            key = {k: expand(v) for k, v in r['key'].items()}
            others.append((n, 'shaped', _grid(r['pattern'], key)))
        elif t == 'minecraft:crafting_shapeless':
            others.append((n, 'shapeless', [expand(v) for v in r['ingredients']]))
    mine = {i for i, *_ in SHAPED} | {i for i, *_ in SHAPELESS}
    for p in glob.glob(os.path.join(MOD, 'src', 'main', 'resources', 'data', 'aftrhrs', 'recipe', '*.json')):
        name = os.path.basename(p)[:-5]
        if name in mine:
            continue
        r = json.load(open(p, encoding='utf-8'))
        t = r.get('type', '')
        if t == 'minecraft:crafting_shaped':
            key = {k: expand(v) for k, v in r['key'].items()}
            others.append((name, 'shaped', _grid(r['pattern'], key)))
        elif t == 'minecraft:crafting_shapeless':
            others.append((name, 'shapeless', [expand(v) for v in r['ingredients']]))
    ours = [(i, 'shaped', _grid(shape, {k: mc(v) for k, v in ing.items()})) for i, shape, ing, _ in SHAPED]
    ours += [(i, 'shapeless', [mc(v) for v in ings]) for i, ings, _ in SHAPELESS]
    for i, (n1, k1, d1) in enumerate(ours):
        for n2, k2, d2 in others + ours[i + 1:]:
            if k1 == 'shaped' and k2 == 'shaped':
                hit = _grids_clash(d1, d2)
            else:
                a = [c for r in d1 for c in r if c] if k1 == 'shaped' else d1
                b = [c for r in d2 for c in r if c] if k2 == 'shaped' else d2
                hit = _bag_clash(a, b) and (k1 == 'shapeless' or k2 == 'shapeless')
            if hit:
                PROBLEMS.append(f'recipe clash: {n1} vs {n2}')


# ================================================================ renders
def render_all():
    sys.path.insert(0, os.path.join(ARCANA, 'plants3d'))
    import mcrender as R
    from PIL import Image
    os.makedirs(RENDERS, exist_ok=True)
    mdirs = [os.path.join(CONTENT, 'pack', 'models')]
    tdirs = [os.path.join(CONTENT, 'pack', 'textures')]

    def q(name, x, y, z, yrot=0):
        m = R.load_model('arcana/christmas/' + name, mdirs)
        if name in ('christmas_tree', 'frosted_christmas_tree', 'fir_tree'):  # stored at half size (scale2)
            m = dict(m, elements=[dict(e, **{'from': [8 + (v - 8) * 2 for v in e['from']], 'to': [8 + (v - 8) * 2 for v in e['to']]},
                                       **({'rotation': dict(e['rotation'], origin=[8 + (v - 8) * 2 for v in e['rotation']['origin']])}
                                          if 'rotation' in e else {})) for e in m['elements']])
        return R.model_quads(m, (x, y, z), yrot, tex_dirs=tdirs)

    def floor(x0, x1, z0, z1, top='block/spruce_planks'):
        out = []
        for x in range(x0, x1):
            for z in range(z0, z1):
                out += R.block_quads(top, top, x, -1, z)
        return out

    def shot(quads, target, dist, path, W=1200, H=800, az=200, el=22, fov=38, bg=(36, 40, 52)):
        R.render(quads, R.Camera(target, dist, az, el, fov=fov), W, H, bg=bg).save(path)

    # the whole set
    qs = floor(-2, 14, -4, 3)
    qs += q('christmas_tree', 0, 0, 0) + q('frosted_christmas_tree', 3, 0, 0) + q('fir_tree', 6, 0, 0)
    for i, n in enumerate(['red_present_wrapped', 'green_present', 'blue_present_wrapped', 'gold_present_wrapped',
                           'white_present', 'present_pile']):
        qs += q(n, -1 + i, 0, -3)
    for i, n in enumerate(['snow_globe', 'tabletop_tree', 'christmas_candle', 'advent_wreath_3', 'christmas_lantern',
                           'red_nutcracker', 'blue_nutcracker_open', 'red_scarf_snowman', 'giant_candy_cane']):
        qs += q(n, 5 + i * 0.9, 0, -3)
    shot(qs, (6, 1.0, -1.0), 15, os.path.join(RENDERS, 'set_all.png'), W=1600, H=900, az=195, el=24)
    # wall pieces on a brick wall, garland on a hearth-like block
    qs = []
    for x in range(-1, 9):
        for y in range(0, 3):
            qs += R.block_quads('block/bricks', 'block/bricks', x, y, 1)
    qs += floor(-1, 9, -2, 1)
    for i, n in enumerate(['wreath', 'lit_wreath', 'red_stocking', 'green_stocking', 'blue_stocking', 'white_stocking']):
        qs += q(n, i, 1, 0)
    qs += R.block_quads('block/stone_bricks', 'block/stone_bricks', 7, 0, 0) + q('mantel_garland', 7, 1, 0)
    qs += q('mistletoe', 3, 1, -1)
    shot(qs, (4, 1.4, 0), 8.5, os.path.join(RENDERS, 'set_wall.png'), W=1400, H=800, az=180, el=12)
    # close-ups
    for n, tall in (('christmas_tree', 3), ('frosted_christmas_tree', 3), ('fir_tree', 3), ('tabletop_tree', 1),
                    ('red_present', 1), ('red_present_wrapped', 1), ('present_pile', 1), ('snow_globe', 1),
                    ('red_nutcracker', 1.6), ('red_nutcracker_open', 1.6), ('christmas_candle', 1), ('advent_wreath_4', 1),
                    ('red_scarf_snowman', 1.8), ('christmas_lantern', 1), ('giant_candy_cane', 1.6), ('wreath', 1),
                    ('lit_wreath', 1), ('mantel_garland', 1), ('red_stocking', 1), ('mistletoe', 1),
                    ('red_bauble', 1), ('tree_star', 1), ('tree_angel', 1), ('tree_lights', 1), ('candy_cane', 1),
                    ('gingerbread_man', 1), ('santa_hat', 1)):
        qs = floor(-1, 2, -1, 2) + q(n, 0, 0, 0)
        tgt = (0.5, tall * 0.5, 0.5)
        dist = 2.2 + tall * 1.6
        a = R.render(qs, R.Camera(tgt, dist, 205, 18, fov=40), 600, 600, bg=(36, 40, 52))
        b = R.render(qs, R.Camera(tgt, dist, 150, 30, fov=40), 600, 600, bg=(36, 40, 52))
        im = Image.new('RGB', (1200, 600))
        im.paste(a, (0, 0))
        im.paste(b, (600, 0))
        im.save(os.path.join(RENDERS, f'piece_{n}.png'))
    # blocks
    qs = []
    for i, n in enumerate(['gingerbread_block', 'gingerbread_bricks', 'frosted_gingerbread', 'frosting_block',
                           'candy_cane_block', 'peppermint_block']):
        qs += q(n, i * 1.25, 0, 0)
    shot(qs, (3.2, 0.5, 0.5), 7.5, os.path.join(RENDERS, 'set_blocks.png'), W=1400, H=600, az=200, el=25)


# ================================================================ main
def main():
    names, sheet = tex.paint_all(TEX_DIR)
    os.makedirs(SET_DIR, exist_ok=True)
    from PIL import Image
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save(os.path.join(SET_DIR, 'textures_strip.png'))
    for f in glob.glob(os.path.join(MODEL_DIR, '*.json')):
        os.remove(f)
    build()
    write_slots()
    # texture refs exist
    for name, (els, t) in MODELS.items():
        for k, v in t.items():
            if v.startswith('arcana/christmas/') and not os.path.exists(os.path.join(TEX_DIR, v.split('/')[-1] + '.png')):
                PROBLEMS.append(f'{name}: missing texture {v}')
    if len(IDS) != len(set(IDS)):
        PROBLEMS.append('duplicate ids')
    hdr = ('# Aftr Hrs Christmas set: trees, ornaments, decorations, presents and festive blocks.\n'
           '# Generated by mod/tools/xmasart/gen.py (edit the generator, not this file); read by tools/gen_content.py.\n'
           '# Behavior (fir tree ornaments, presents, snow globe, advent wreath, mistletoe): feature/christmas.\n\n')
    os.makedirs(os.path.dirname(ITEMS_YML), exist_ok=True)
    with open(ITEMS_YML, 'w', encoding='utf-8', newline='\n') as f:
        f.write(hdr + '\n'.join(ENTRIES))
    os.makedirs(RECIPES, exist_ok=True)
    with open(os.path.join(RECIPES, 'shaped.christmas.yml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Christmas set shaped recipes ("_" = empty). Generated by mod/tools/xmasart/gen.py.\n\n'
                + '\n'.join(s for *_, s in SHAPED))
    with open(os.path.join(RECIPES, 'shapeless.christmas.yml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Christmas set shapeless recipes. Generated by mod/tools/xmasart/gen.py.\n\n'
                + '\n'.join(s for *_, s in SHAPELESS))
    check_recipes()
    print('items:', len(IDS), 'models:', len(os.listdir(MODEL_DIR)), 'textures:', len(names),
          'recipes:', len(SHAPED) + len(SHAPELESS))
    print('PROBLEMS:' if PROBLEMS else 'checks ok', *PROBLEMS, sep='\n  ')
    if '--no-render' not in sys.argv:
        render_all()
        print('renders ->', RENDERS)


if __name__ == '__main__':
    main()
