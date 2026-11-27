#!/usr/bin/env python3
"""Generates the Aftr Hrs enchantments (docs/enchants.md): the data-driven definitions, their tags, the Treasure Hunter
loot table and the English names and descriptions.

Writes (re-runnable; owned files only):
  src/main/resources/data/aftrhrs/enchantment/*.json                 every enchantment (cleared first)
  src/main/resources/data/aftrhrs/tags/enchantment/**                 exclusive sets
  src/main/resources/data/aftrhrs/tags/item/enchantable/*.json        what some of them go on
  src/main/resources/data/aftrhrs/tags/block/treasure_hunter.json     the blocks Treasure Hunter digs finds from
  src/main/resources/data/aftrhrs/loot_table/enchant/treasure_hunter.json
  src/main/resources/data/minecraft/tags/enchantment/*.json           table / treasure / trade / loot / curse lists
  src/main/resources/assets/aftrhrs/lang/en_us.enchants.json

gen_content.py clears data/aftrhrs/tags, so it runs this script at its end (like gen_smithing.py).
Most effects are worked by feature/enchants (Java); only the plain ones (attributes, slowness on hit, XP) are vanilla
effect components here. Numbers that Java reads live in Enchants.java, next to the code that uses them.

Run: python mod/tools/gen_enchants.py   (stdlib only)
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', 'src', 'main', 'resources')
DATA = os.path.join(RES, 'data')
NS = 'aftrhrs'


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write('\n')


def lin(base, per):
    return {'type': 'minecraft:linear', 'base': base, 'per_level_above_first': per}


def attr(attribute, base, per, op='add_value', key=None):
    return {'attribute': attribute, 'id': '%s:enchantment.%s' % (NS, key), 'amount': lin(base, per), 'operation': op}


# --------------------------------------------------------------------------------------------- the enchantments
# kind: table (enchanting table, trades, loot), treasure (loot, fishing, trades; never the table), relic (loot and
# fishing only: no trades), curse (treasure + curse).
# weight 10 common .. 1 very rare; cost = (min base, per level); the max cost is min + 50.
E = []


def ench(id, name, desc, items, slots, max_level, weight, cost, kind, primary=None, exclusive=None, effects=None):
    E.append(dict(id=id, name=name, desc=desc, items=items, slots=slots, max=max_level, weight=weight, cost=cost,
                  kind=kind, primary=primary, exclusive=exclusive, effects=effects or {}))


COLD = {'type': 'minecraft:location_check', 'predicate': {'biomes': '#c:is_cold'}}
DIRECT = {'type': 'minecraft:damage_source_properties', 'predicate': {'is_direct': True}}

# -- melee
ench('rimebite', 'Rimebite',
     'Hits frost the target over and slow it. Deals +1 damage per level in cold biomes.',
     '#minecraft:enchantable/sharp_weapon', ['mainhand'], 2, 2, (10, 20), 'table',
     primary='#minecraft:enchantable/melee_weapon', exclusive='#aftrhrs:exclusive_set/elemental',
     effects={
         'minecraft:post_attack': [{
             'affected': 'victim', 'enchanted': 'attacker', 'requirements': DIRECT,
             'effect': {'type': 'minecraft:apply_mob_effect', 'to_apply': 'minecraft:slowness',
                        'min_duration': lin(2.0, 1.0), 'max_duration': lin(2.0, 1.0),
                        'min_amplifier': lin(0.0, 1.0), 'max_amplifier': lin(0.0, 1.0)}}],
         'minecraft:damage': [{'effect': {'type': 'minecraft:add', 'value': lin(1.0, 1.0)}, 'requirements': COLD}],
     })
ench('leeching', 'Leeching',
     'Heals you for 4% of the melee damage you deal per level (up to 2 hearts a hit).',
     '#minecraft:enchantable/sharp_weapon', ['mainhand'], 3, 1, (15, 15), 'treasure',
     primary='#minecraft:enchantable/melee_weapon')
ench('culling', 'Culling',
     '+10% damage per level to targets below 35% health.',
     '#minecraft:enchantable/sharp_weapon', ['mainhand'], 3, 2, (5, 10), 'table')
ench('witching_hour', 'Witching Hour',
     '+1 damage per level at night. Doubled under the Restless Moon.',
     '#minecraft:enchantable/sharp_weapon', ['mainhand'], 3, 2, (8, 12), 'table')
ench('cleave', 'Cleave',
     'Axe hits splash 20% of their damage per level onto up to 3 other monsters nearby.',
     '#minecraft:axes', ['mainhand'], 3, 2, (10, 12), 'table', primary='#minecraft:axes')
ench('giantslayer', 'Giantslayer',
     '+12% damage per level against big foes (40+ max health): bosses, golems, ravagers.',
     '#minecraft:enchantable/sharp_weapon', ['mainhand'], 4, 2, (5, 11), 'table',
     exclusive='#minecraft:exclusive_set/damage')

# -- ranged
ench('seeker', 'Seeker',
     'Arrows curve toward the nearest monster in front of you.',
     '#aftrhrs:enchantable/archery', ['mainhand'], 1, 1, (25, 0), 'treasure', exclusive='#aftrhrs:exclusive_set/archery')
ench('volley', 'Volley',
     'A fully drawn bow looses two extra arrows. The extras cost nothing and can\'t be picked up.',
     '#minecraft:enchantable/bow', ['mainhand'], 1, 1, (20, 0), 'treasure', exclusive='#aftrhrs:exclusive_set/archery')
ench('marking', 'Marking',
     'Shot targets glow for 4 seconds per level and take 10% more damage from everyone while marked.',
     '#aftrhrs:enchantable/archery', ['mainhand'], 2, 2, (10, 15), 'table')
ench('harpoon', 'Harpoon',
     'Crossbow bolts drag the creature they hit toward you. Never players or bosses.',
     '#minecraft:enchantable/crossbow', ['mainhand'], 1, 2, (15, 0), 'table')

# -- tools
ench('molten_touch', 'Molten Touch',
     'Mined blocks drop already smelted: ores as ingots, sand as glass, logs as charcoal.',
     '#minecraft:enchantable/mining_loot', ['mainhand'], 1, 2, (15, 0), 'table',
     exclusive='#aftrhrs:exclusive_set/molten_touch')
ench('prospector', 'Prospector',
     'Mining an ore also mines up to 8 connected ores of the same kind per level. Sneak to mine just one.',
     '#minecraft:pickaxes', ['mainhand'], 3, 1, (15, 15), 'treasure')
ench('timberfall', 'Timberfall',
     'Chopping a log fells the whole natural tree. Sneak to chop one log. Builds are left alone.',
     '#minecraft:axes', ['mainhand'], 1, 2, (15, 0), 'table')
ench('pocketing', 'Pocketing',
     'Drops from blocks you mine and mobs you kill go straight into your inventory.',
     '#aftrhrs:enchantable/pocketing', ['mainhand'], 1, 2, (20, 0), 'treasure')
ench('replanting', 'Replanting',
     'Breaking a ripe crop replants it from its own seeds. Level II harvests the 3x3 around it.',
     '#minecraft:hoes', ['mainhand'], 2, 2, (10, 15), 'table')
ench('bountiful', 'Bountiful',
     'Ripe crops have a 15% chance per level to drop one more of their harvest.',
     '#minecraft:hoes', ['mainhand'], 3, 2, (10, 10), 'table', exclusive='#aftrhrs:exclusive_set/bountiful')
ench('treasure_hunter', 'Treasure Hunter',
     'Digging dirt, sand, gravel, clay and mud sometimes turns up a find: nuggets, bones, sherds, map scraps.',
     '#minecraft:shovels', ['mainhand'], 3, 1, (10, 12), 'table')
ench('insight', 'Insight',
     '+50% experience per level from the blocks you mine and the mobs you kill.',
     '#aftrhrs:enchantable/insight', ['mainhand'], 3, 2, (10, 12), 'table',
     effects={
         'minecraft:block_experience': [{'effect': {'type': 'minecraft:multiply', 'factor': lin(1.5, 0.5)}}],
         'minecraft:mob_experience': [{'effect': {'type': 'minecraft:multiply', 'factor': lin(1.5, 0.5)}}],
     })

# -- armor
ench('vitality', 'Vitality',
     '+1 heart of max health per level.',
     '#minecraft:enchantable/chest_armor', ['chest'], 2, 1, (20, 20), 'treasure',
     effects={'minecraft:attributes': [attr('minecraft:max_health', 2.0, 2.0, key='vitality')]})
ench('second_wind', 'Second Wind',
     'Dropping below 3 hearts gives you Regeneration II and Absorption for a moment. Once every 2 minutes.',
     '#minecraft:enchantable/chest_armor', ['chest'], 1, 1, (25, 0), 'treasure')
ench('deflection', 'Deflection',
     '8% chance per level to deflect an arrow or other projectile that hits you.',
     '#minecraft:enchantable/chest_armor', ['chest'], 3, 2, (8, 12), 'table')
ench('nightsight', 'Nightsight',
     'Night Vision while you stand in the dark.',
     '#minecraft:enchantable/head_armor', ['head'], 1, 2, (15, 0), 'table')
ench('pack_bond', 'Pack Bond',
     'Your tamed pets within 24 blocks take 15% less damage per level, and slowly heal while you\'re close.',
     '#minecraft:enchantable/head_armor', ['head'], 2, 2, (15, 15), 'treasure')
ench('steadfast', 'Steadfast',
     '+10% knockback resistance per level.',
     '#minecraft:enchantable/leg_armor', ['legs'], 3, 2, (5, 10), 'table',
     effects={'minecraft:attributes': [attr('minecraft:knockback_resistance', 0.1, 0.1, key='steadfast')]})
ench('springstep', 'Springstep',
     'Jump higher, and fall 1 block further per level before it hurts.',
     '#minecraft:enchantable/foot_armor', ['feet'], 2, 2, (10, 12), 'table',
     effects={'minecraft:attributes': [attr('minecraft:jump_strength', 0.07, 0.07, key='springstep'),
                                       attr('minecraft:safe_fall_distance', 1.0, 1.0, key='springstep')]})
ench('driftwalker', 'Driftwalker',
     'Walk on powder snow without sinking, and move 15% faster per level on snow.',
     '#minecraft:enchantable/foot_armor', ['feet'], 2, 2, (10, 15), 'table',
     exclusive='#aftrhrs:exclusive_set/boots_speed')

# -- fishing (the fishing module reads these)
ench('steady_line', 'Steady Line',
     'Your line holds 15% more tension per level in fish fights.',
     '#minecraft:enchantable/fishing', ['mainhand'], 3, 2, (10, 9), 'table')
ench('heavy_haul', 'Heavy Haul',
     'Fish you catch run heavier, and trophy fish turn up a little more often.',
     '#minecraft:enchantable/fishing', ['mainhand'], 3, 1, (15, 9), 'table')
ench('twin_hook', 'Twin Hook',
     '10% chance per level that a common or uncommon fish comes in as two.',
     '#minecraft:enchantable/fishing', ['mainhand'], 2, 1, (15, 10), 'table')

# -- anything
ench('soulbound', 'Soulbound',
     'Stays with you when you die: it skips your grave and is back in your inventory when you respawn.',
     '#minecraft:enchantable/vanishing', ['any'], 1, 1, (25, 0), 'relic',
     exclusive='#aftrhrs:exclusive_set/soulbound')

# -- curses
ench('fragility_curse', 'Curse of Fragility',
     'Wears out twice as fast.',
     '#minecraft:enchantable/durability', ['any'], 1, 1, (25, 0), 'curse',
     effects={'minecraft:item_damage': [{'effect': {'type': 'minecraft:add', 'value': 1.0}}]})
ench('weight_curse', 'Curse of Weight',
     'You move 5% slower while you wear it.',
     '#minecraft:enchantable/armor', ['armor'], 1, 1, (25, 0), 'curse',
     effects={'minecraft:attributes': [attr('minecraft:movement_speed', -0.05, 0.0, op='add_multiplied_total', key='weight_curse')]})

ANVIL = {10: 1, 5: 2, 2: 4, 1: 8}

# --------------------------------------------------------------------------------------------- tags
EXCLUSIVE = {
    'elemental': ['minecraft:fire_aspect', 'aftrhrs:rimebite'],
    'archery': ['aftrhrs:seeker', 'aftrhrs:volley'],
    'molten_touch': ['minecraft:silk_touch', 'aftrhrs:molten_touch'],
    'bountiful': ['minecraft:fortune', 'aftrhrs:bountiful'],
    'boots_speed': ['minecraft:soul_speed', 'minecraft:frost_walker', 'aftrhrs:driftwalker'],
    'soulbound': ['minecraft:vanishing_curse', 'aftrhrs:soulbound'],
}
ITEM_TAGS = {
    'archery': ['#minecraft:enchantable/bow', '#minecraft:enchantable/crossbow'],
    'pocketing': ['#minecraft:enchantable/mining_loot', '#minecraft:enchantable/weapon', 'minecraft:shears'],
    'insight': ['#minecraft:enchantable/mining_loot', '#minecraft:enchantable/weapon', '#minecraft:enchantable/bow',
                '#minecraft:enchantable/crossbow'],
}
TREASURE_HUNTER_BLOCKS = ['#minecraft:dirt', '#minecraft:sand', 'minecraft:gravel', 'minecraft:suspicious_gravel',
                          'minecraft:clay', 'minecraft:mud', 'minecraft:muddy_mangrove_roots', 'minecraft:soul_sand',
                          'minecraft:soul_soil', 'minecraft:farmland', 'minecraft:dirt_path']


def item(name, weight, count=None, quality=0):
    e = {'type': 'minecraft:item', 'name': name, 'weight': weight}
    if quality:
        e['quality'] = quality
    if count:
        e['functions'] = [{'function': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': count[0], 'max': count[1]}}]
    return e


TREASURE_HUNTER_LOOT = {
    'type': 'minecraft:block',
    'pools': [{
        'rolls': 1,
        'entries': [
            item('minecraft:flint', 30, (1, 3)),
            item('minecraft:bone', 25, (1, 2)),
            item('minecraft:gold_nugget', 22, (2, 6), quality=1),
            item('minecraft:iron_nugget', 22, (3, 8)),
            item('minecraft:clay_ball', 15, (2, 4)),
            item('minecraft:copper_ingot', 10, (1, 2), quality=1),
            item('minecraft:emerald', 5, None, quality=2),
            item('minecraft:diamond', 1, None, quality=2),
            item('aftrhrs:arcana_soggy_map_scrap', 6, None, quality=1),
            item('minecraft:archer_pottery_sherd', 2, None, quality=1),
            item('minecraft:prize_pottery_sherd', 2, None, quality=1),
            item('minecraft:skull_pottery_sherd', 2, None, quality=1),
            item('minecraft:music_disc_13', 1, None, quality=2),
            item('minecraft:music_disc_cat', 1, None, quality=2),
            item('minecraft:name_tag', 2, None, quality=2),
        ],
    }],
}


def main():
    out = os.path.join(DATA, NS, 'enchantment')
    shutil.rmtree(out, ignore_errors=True)
    lang = {}
    groups = {'non_treasure': [], 'treasure': [], 'tradeable': [], 'on_random_loot': [], 'curse': []}
    for e in E:
        mb, mp = e['cost']
        d = {
            'anvil_cost': ANVIL.get(e['weight'], 4),
            'description': {'translate': 'enchantment.%s.%s' % (NS, e['id'])},
            'effects': e['effects'],
            'max_cost': {'base': mb + 50, 'per_level_above_first': mp},
            'max_level': e['max'],
            'min_cost': {'base': mb, 'per_level_above_first': mp},
            'slots': e['slots'],
            'supported_items': e['items'],
            'weight': e['weight'],
        }
        if e['primary']:
            d['primary_items'] = e['primary']
        if e['exclusive']:
            d['exclusive_set'] = e['exclusive']
        write(os.path.join(out, e['id'] + '.json'), dict(sorted(d.items())))
        key = '%s:%s' % (NS, e['id'])
        k = e['kind']
        if k == 'table':
            groups['non_treasure'].append(key)
        else:
            groups['treasure'].append(key)
            groups['on_random_loot'].append(key)
            if k != 'relic':
                groups['tradeable'].append(key)
        if k == 'curse':
            groups['curse'].append(key)
        lang['enchantment.%s.%s' % (NS, e['id'])] = e['name']
        lang['enchantment.%s.%s.desc' % (NS, e['id'])] = e['desc']

    mc = os.path.join(DATA, 'minecraft', 'tags', 'enchantment')
    for g, vals in groups.items():
        write(os.path.join(mc, g + '.json'), {'replace': False, 'values': vals})
    # Giantslayer joins the damage set (Sharpness, Smite, Bane) from both sides.
    write(os.path.join(mc, 'exclusive_set', 'damage.json'), {'replace': False, 'values': ['aftrhrs:giantslayer']})
    # Tooltip order: curses first like vanilla's, the rest after the vanilla list.
    order = [k for k in groups['curse']] + ['%s:%s' % (NS, e['id']) for e in E if e['kind'] != 'curse']
    write(os.path.join(mc, 'tooltip_order.json'), {'replace': False, 'values': order})

    tags = os.path.join(DATA, NS, 'tags')
    shutil.rmtree(os.path.join(tags, 'enchantment'), ignore_errors=True)
    for name, vals in EXCLUSIVE.items():
        write(os.path.join(tags, 'enchantment', 'exclusive_set', name + '.json'), {'values': vals})
    write(os.path.join(tags, 'enchantment', 'all.json'), {'values': ['%s:%s' % (NS, e['id']) for e in E]})
    for name, vals in ITEM_TAGS.items():
        write(os.path.join(tags, 'item', 'enchantable', name + '.json'), {'values': vals})
    write(os.path.join(tags, 'block', 'treasure_hunter.json'), {'values': TREASURE_HUNTER_BLOCKS})
    write(os.path.join(DATA, NS, 'loot_table', 'enchant', 'treasure_hunter.json'), TREASURE_HUNTER_LOOT)

    lang['aftrhrs.enchants.second_wind'] = 'Second Wind!'
    lang['aftrhrs.enchants.deflected'] = 'Deflected!'
    lang['aftrhrs.enchants.unearthed'] = 'Treasure Hunter: you unearthed %s'
    lang['aftrhrs.enchants.tooltip.hint'] = 'Hold Shift for what it does'
    for name in ITEM_TAGS:
        lang['tag.item.%s.enchantable.%s' % (NS, name)] = 'Enchantable: ' + name.replace('_', ' ').title()
    write(os.path.join(RES, 'assets', NS, 'lang', 'en_us.enchants.json'), dict(sorted(lang.items())))
    print('enchants: %d enchantments (%d table, %d treasure)' % (
        len(E), len(groups['non_treasure']), len(groups['treasure'])))


if __name__ == '__main__':
    main()
