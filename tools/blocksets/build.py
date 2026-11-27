#!/usr/bin/env python3
"""Builds the snow, ice and calcite building-block sets (content whose source lives in the repo).

Writes (Oraxen format, read by tools/gen_content.py from its in-repo content root, default mod/content):
  mod/content/pack/textures/arcana/blocks/sets/<texture>.png      16x16 textures (textures.py, paint.py)
  mod/content/pack/models/arcana/blocks/sets/<texture>.json       cube_all block models
  mod/content/items/arcana_block_sets.yml                         the blocks, stairs, slabs and walls
  mod/content/recipes/{shaped,stonecutting,furnace}.block_sets.yml crafting, stonecutter and furnace recipes
Everything goes in the Building Blocks creative tab (tab: arcana_blocks), after the older Oraxen blocks.

Families (like vanilla stone families): base (vanilla block) -> polished -> bricks -> tiles, plus chiseled; stairs and
slabs for polished/bricks/tiles, walls for bricks/tiles. Calcite also gets cracked bricks (smelt the bricks) and a
plain calcite wall next to the existing arcana_calcite_stairs / arcana_calcite_slab (vanilla calcite texture).

Run:  python mod/tools/blocksets/build.py      (needs Pillow)
Then: python mod/tools/gen_content.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import textures  # noqa: E402

MOD = os.path.dirname(os.path.dirname(HERE))
CONTENT = os.path.join(MOD, 'content')
TEX_DIR = 'arcana/blocks/sets'
TAB = 'arcana_blocks'

# material key -> (display word, vanilla base block, block properties)
MATERIALS = {
    'snow': ('Snow', 'snow_block', dict(hardness=0.8, tool='shovel', requires=True, sound='snow', map='snow')),
    'ice': ('Ice', 'packed_ice', dict(hardness=0.5, tool='pickaxe', requires=False, sound='glass', map='ice')),
    'blue_ice': ('Blue Ice', 'blue_ice', dict(hardness=2.8, tool='pickaxe', requires=False, sound='glass', map='ice')),
    'calcite': ('Calcite', 'calcite', dict(hardness=0.75, tool='pickaxe', requires=True, sound='calcite',
                                           map='terracotta_white')),
}
# Polished ice is as slippery as the ice it is cut from; bricks, tiles and chiseled ice have grip (joints).
FRICTION = {'polished_ice': 0.98, 'polished_blue_ice': 0.989}

TOOL_TAG = {'pickaxe': '#minecraft:pickaxes', 'shovel': '#minecraft:shovels'}


def forms(mat):
    """[(texture name, display name, level in the chain, variants)] for a material."""
    word = MATERIALS[mat][0]
    out = [('polished_' + mat, 'Polished ' + word, 1, ('stairs', 'slab')),
           (mat + '_bricks', word + ' Bricks', 2, ('stairs', 'slab', 'wall'))]
    if mat == 'calcite':
        out.append(('cracked_calcite_bricks', 'Cracked Calcite Bricks', None, ()))
    out += [('chiseled_' + mat, 'Chiseled ' + word, 2.5, ()),
            (mat + '_tiles', word + ' Tiles', 3, ('stairs', 'slab', 'wall'))]
    return out


def variant_id(tex, kind):
    """polished_snow -> arcana_polished_snow_stairs, snow_bricks -> arcana_snow_brick_stairs."""
    stem = tex[:-1] if tex.endswith(('bricks', 'tiles')) else tex
    return 'arcana_%s_%s' % (stem, kind)


def variant_name(name, kind):
    stem = name[:-1] if name.endswith(('Bricks', 'Tiles')) else name
    return '%s %s' % (stem, {'stairs': 'Stairs', 'slab': 'Slab', 'wall': 'Wall'}[kind])


def q(s):
    return json.dumps(s)


def block_yml(iid, name, typ, tex, mat):
    p = MATERIALS[mat][2]
    lines = ['%s:' % iid, '  itemname: %s' % q(name), '  material: PAPER', '  tab: ' + TAB, '  pack:',
             '    generate_model: false']
    if typ == 'FULL':
        lines.append('    model: %s/%s' % (TEX_DIR, tex))
    lines += ['  mechanics:', '    block:', '      type: ' + typ, '      map_color: ' + p['map']]
    if tex in FRICTION:
        lines.append('      friction: %s' % FRICTION[tex])
    if typ == 'FULL':
        lines += ['      appearance:', '        model: %s/%s' % (TEX_DIR, tex)]
    else:
        lines += ['      textures:', '        all: %s/%s' % (TEX_DIR, tex)]
    lines.append('      block_sounds:')
    for ev in ('place', 'break', 'hit', 'step', 'fall'):
        lines.append('        %s: {sound: block.%s.%s, volume: 1.0, pitch: 1.0}' % (ev, p['sound'], ev))
    lines += ['      breaking:', '      - when:', "        - '%s'" % TOOL_TAG[p['tool']],
              '        hardness: %s' % p['hardness'], '        drops:', '        - {item: %s, probability: 1.0}' % iid,
              '      - else:', '        hardness: %s' % p['hardness']]
    lines.append('        drops: []' if p['requires'] else '        drops:\n        - {item: %s, probability: 1.0}' % iid)
    return '\n'.join(lines) + '\n'


def ing(x):
    return '{oraxen_item: %s}' % x if x.startswith('arcana_') else '{minecraft_type: %s}' % x.upper()


def shaped(name, shape, keys, result, amount):
    out = ['%s:' % name, '  shape:'] + ['  - %s' % row for row in shape] + ['  ingredients:']
    out += ['    %s: %s' % (k, ing(v)) for k, v in keys.items()]
    out += ['  result:', '    oraxen_item: %s' % result, '    amount: %d' % amount]
    return '\n'.join(out) + '\n'


def stonecut(src, result, amount):
    return ('%s_from_%s_stonecutting:\n  input: %s\n  result:\n    oraxen_item: %s\n    amount: %d\n'
            % (result, src.replace('arcana_', ''), ing(src), result, amount))


def build():
    items, rec_shaped, rec_cut, rec_furnace = [], [], [], []
    tex_out = os.path.join(CONTENT, 'pack', 'textures', TEX_DIR)
    mod_out = os.path.join(CONTENT, 'pack', 'models', TEX_DIR)
    os.makedirs(tex_out, exist_ok=True)
    os.makedirs(mod_out, exist_ok=True)
    count = 0
    for mat, (word, base, props) in MATERIALS.items():
        fs = forms(mat)
        by_level = {}
        for tex, name, level, variants in fs:
            textures.make(tex).image().save(os.path.join(tex_out, tex + '.png'))
            with open(os.path.join(mod_out, tex + '.json'), 'w', encoding='utf-8', newline='\n') as f:
                json.dump({'parent': 'minecraft:block/cube_all', 'textures': {'all': '%s/%s' % (TEX_DIR, tex)}}, f)
                f.write('\n')
            bid = 'arcana_' + tex
            items.append(block_yml(bid, name, 'FULL', tex, mat))
            count += 1
            for kind in variants:
                vid = variant_id(tex, kind)
                items.append(block_yml(vid, variant_name(name, kind), {'stairs': 'STAIR', 'slab': 'SLAB', 'wall': 'WALL'}[kind],
                                       tex, mat))
                count += 1
                if kind == 'stairs':
                    rec_shaped.append(shaped(vid, ['A__', 'AA_', 'AAA'], {'A': bid}, vid, 4))
                elif kind == 'slab':
                    rec_shaped.append(shaped(vid, ['AAA'], {'A': bid}, vid, 6))
                else:
                    rec_shaped.append(shaped(vid, ['AAA', 'AAA'], {'A': bid}, vid, 6))
            if level is not None:
                by_level[level] = (bid, tex, variants)
        # crafting chain: 2x2 of the previous step gives 4; chiseled = two brick slabs stacked
        chain = [base] + [by_level[l][0] for l in (1, 2, 3)]
        for prev, nxt in zip(chain, chain[1:]):
            rec_shaped.append(shaped(nxt, ['AA', 'AA'], {'A': prev}, nxt, 4))
        rec_shaped.append(shaped('arcana_chiseled_' + mat, ['A', 'A'], {'A': variant_id(mat + '_bricks', 'slab')},
                                 'arcana_chiseled_' + mat, 1))
        # stonecutter: from the base or any step, every later step and the variants of it and later steps
        sources = [(0, base)] + [(l, by_level[l][0]) for l in (1, 2, 3)]
        for slevel, src in sources:
            for level in sorted(by_level):
                bid, tex, variants = by_level[level]
                if level > slevel:
                    rec_cut.append(stonecut(src, bid, 1))
                if level >= slevel and level != 2.5:
                    for kind in variants:
                        rec_cut.append(stonecut(src, variant_id(tex, kind), 2 if kind == 'slab' else 1))
        if mat == 'calcite':
            rec_furnace.append('arcana_cracked_calcite_bricks:\n  input: {oraxen_item: arcana_calcite_bricks}\n'
                               '  result:\n    oraxen_item: arcana_cracked_calcite_bricks\n  cookingTime: 200\n'
                               '  experience: 0.1\n')
            # the plain calcite wall, beside the existing Oraxen calcite stairs and slab (vanilla calcite texture)
            items.append(block_yml('arcana_calcite_wall', 'Calcite Wall', 'WALL', None, mat)
                         .replace('all: %s/None' % TEX_DIR, 'all: block/calcite'))
            count += 1
            rec_shaped.append(shaped('arcana_calcite_wall', ['AAA', 'AAA'], {'A': 'calcite'}, 'arcana_calcite_wall', 6))
            for vid, n in (('arcana_calcite_stairs', 1), ('arcana_calcite_slab', 2), ('arcana_calcite_wall', 1)):
                rec_cut.append(stonecut('calcite', vid, n))

    head = ('# Generated by mod/tools/blocksets/build.py: the snow, ice and calcite block sets. Read by\n'
            '# tools/gen_content.py (in-repo content root). Edit the generator, not this file.\n\n')
    with open(os.path.join(CONTENT, 'items', 'arcana_block_sets.yml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(head + '\n'.join(items))
    os.makedirs(os.path.join(CONTENT, 'recipes'), exist_ok=True)
    for kind, recs in (('shaped', rec_shaped), ('stonecutting', rec_cut), ('furnace', rec_furnace)):
        with open(os.path.join(CONTENT, 'recipes', kind + '.block_sets.yml'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(head + ''.join(recs))
    return count, len(rec_shaped), len(rec_cut), len(rec_furnace)


if __name__ == '__main__':
    n, s, c, fu = build()
    print('block sets: %d blocks, %d shaped, %d stonecutting, %d furnace recipes' % (n, s, c, fu))
