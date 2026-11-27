#!/usr/bin/env python3
"""Writes the mod's Feather advancements (src/main/resources/feather/advancements.yml) from the mod's own data:

  data/aftrhrs/species/*.json        tameable pets, mounts and hostiles, the bosses
  data/aftrhrs/content/content.json  plushies, hats, relics, trophies, tools, backpacks, planters, furniture collections
                                     (by id and creative tab: run tools/gen_content.py first)

    python mod/tools/gen_advancements.py

Re-run it after adding a creature or content (gen_creatures.py / gen_content.py), then tools/gen_config_history.py at
release so unedited server copies upgrade. Hand edits to advancements.yml are overwritten: change texts here. Titles,
descriptions and icons are the ones the Paper server used (art/gen_advancements.py, retired): criterion names are the
targets, so keep them stable or saved progress stops matching.

Who counts as tameable: kind pet / mount / hostile and not untameable. A creature that is only ever raised (no tame block
of its own, e.g. the adult penguins) is reached through its chick, so the chick is the target: PetsModule.register reports
the chick's id when it is tamed. Egg-only pets (Kevin, Luna, Kitty, Clover, Frog Guy) are pets like any other and also
get a hidden advancement of their own (SPECIAL).
"""
import glob
import json
import os

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
DATA = os.path.join(RES, 'data', 'aftrhrs')
OUT = os.path.join(RES, 'feather', 'advancements.yml')

# ------------------------------------------------------------------------------------------------------ data
species = []
for f in sorted(glob.glob(os.path.join(DATA, 'species', '*.json'))):
    if os.path.basename(f) == 'index.json':
        continue
    species.append(json.load(open(f, encoding='utf-8')))


def tameable(s):
    return s['kind'] in ('pet', 'mount', 'hostile') and not s.get('untameable') and 'tame' in s


def by_kind(kind):
    return sorted(s['mm_id'] for s in species if s['kind'] == kind and tameable(s))


pets, mounts, hostiles = by_kind('pet'), by_kind('mount'), by_kind('hostile')
mm_ids = {s['mm_id'] for s in species}

content = json.load(open(os.path.join(DATA, 'content', 'content.json'), encoding='utf-8'))
ENTRIES = content['entries']
ITEM_IDS = {e['id'] for e in ENTRIES}


def tab(key, pred=lambda i: True):
    """Ids in a creative tab, in table order."""
    return [e['id'] for e in ENTRIES if e.get('tab') == key and pred(e['id'])]


def native(i):
    """Arcana content, not the Feather power machines that share the utility tab."""
    return not i.startswith('feather_')


plushies = sorted(i for i in ITEM_IDS if i.startswith('arcana_plush_'))
hats = tab('arcana_hats_common') + tab('arcana_hats_uncommon') + tab('arcana_hats_rare')
relics = tab('arcana_relics')
trophies = tab('arcana_bosses', lambda i: i.startswith('arcana_trophy_'))
hammers = tab('arcana_hammers', lambda i: i.endswith('_hammer'))
excavators = tab('arcana_hammers', lambda i: i.endswith('_excavator'))
axes = tab('arcana_fieldtools', lambda i: i.endswith('_lumber_axe'))
scythes = tab('arcana_fieldtools', lambda i: i.endswith('_scythe'))
gear = tab('arcana_gear')
backpacks = [i for i in gear if i.startswith('arcana_backpack_')]
planters = [i for i in tab('arcana_utility') + tab('arcana_stonework_garden') if i.endswith('_planter')]

BOSSES = [  # (mob type, summon item, title, description)
    ('ArcanaCinderwingPhoenix', 'arcana_summon_cinder_offering', 'From the Ashes', 'Defeat the Cinderwing Phoenix'),
    ('ArcanaTempestRoc', 'arcana_summon_tempest_totem', 'Eye of the Storm', 'Defeat the Tempest Roc'),
    ('ArcanaGlacialWyrm', 'arcana_summon_rime_heart', 'Cold Snap', 'Defeat the Glacial Wyrm'),
    ('ArcanaRuneboundColossus', 'arcana_summon_runic_keystone', 'Bigger They Are', 'Defeat the Runebound Colossus'),
    ('ArcanaWukong', 'arcana_summon_immortal_peach', 'Journey to the West', 'Defeat Sun Wukong, the Monkey King'),
]
BOSSES = [b for b in BOSSES if b[0] in mm_ids]

# The egg-only pets: one hidden goal each (key, title, description)
SPECIAL = [
    ('ArcanaKevin', 'kevin', 'A Very Good Boy', 'Tame Kevin'),
    ('ArcanaKitty', 'kitty', 'Gentle Giant', 'Tame Kitty'),
    ('ArcanaClover', 'clover', 'Lucky Charm', 'Tame Clover'),
    ('ArcanaFrogGuy', 'frogguy', 'Merit Badge Buddy', 'Tame Frog Guy'),
    ('ArcanaLuna', 'luna', 'Moonlit', 'Tame Luna'),
]
plush_of = {s['mm_id']: s.get('plush') for s in species}

COLLECTIONS = [  # (criterion, title, description, icon, creative tabs)
    ('woodwork', 'Carpenter', 'Place a piece of woodwork furniture', 'oraxen:arcana_oak_rocking_chair',
     ['arcana_woodwork_seating', 'arcana_woodwork_storage', 'arcana_woodwork_tables', 'arcana_woodwork_walls']),
    ('stonework', 'Stonemason', 'Place a piece of stonework', 'oraxen:arcana_stone_birdbath',
     ['arcana_stonework_columns', 'arcana_stonework_garden', 'arcana_stonework_hearths', 'arcana_stonework_seating']),
    ('fabric', 'Soft Furnishings', 'Place a sofa, armchair, rug or curtain', 'oraxen:arcana_white_armchair',
     ['arcana_fabric_hangings', 'arcana_fabric_seats', 'arcana_fabric_sofas']),
    ('copper', 'Copper Age', 'Place a copper lamp or pipe', 'oraxen:arcana_copper_street_lamp', ['arcana_copperworks']),
    ('lighting', 'Let There Be Light', 'Hang a lantern, chandelier or sconce', 'oraxen:arcana_lantern_chandelier',
     ['arcana_lighting']),
    ('decor', 'Finishing Touches', 'Place a potted plant, globe or clock', 'oraxen:arcana_potted_monstera', ['arcana_decor']),
    ('furniture', 'Cozy Corner', 'Place an armchair, lamp, table or bench', 'oraxen:arcana_armchair', ['arcana_furniture']),
    ('utility', 'Handy Helper', 'Place a trash can, locker, cabinet or gramophone', 'oraxen:arcana_gramophone',
     ['arcana_utility']),
]
# Folded into these tabs by gen_content (TAB_FOLD) but not part of the Paper collections: the string lights, the
# Feather power machines. The collections keep their members (criteria of decor/designer stay the same).
NOT_COLLECTED = {'arcana_string_lights'}
COLLECTIONS = [(c, t, d, i, [x for f in tabs for x in tab(f, lambda x: native(x) and x not in NOT_COLLECTED)])
               for c, t, d, i, tabs in COLLECTIONS]
COLLECTIONS = [c for c in COLLECTIONS if c[4]]

# ------------------------------------------------------------------------------------------------------ advancements
adv = {}


def a(key, parent, icon, title, desc, trigger, frame='task', **extra):
    e = {'icon': icon, 'title': title, 'description': desc, 'frame': frame, 'trigger': trigger}
    if parent:
        e['parent'] = parent
    e.update(extra)
    adv[key] = e


def root(key, icon, title, desc, background):
    a(key, None, icon, title, desc, 'join', background=background, toast=False, announce=False)


# ------------------------------------------------------------------ Aftr Hrs (server life)
root('aftrhrs/root', 'firefly_bush', '<gradient:#c3a6ff:#8fd3ff>Aftr Hrs</gradient>',
     'Welcome to the night shift', 'minecraft:block/cherry_planks')
a('aftrhrs/hub', 'aftrhrs/root', 'feather', 'Shift + F', 'Open the Feather menu', 'event', targets=['hub'])
a('aftrhrs/home', 'aftrhrs/hub', 'red_bed', 'Home Sweet Home', 'Set a home with /sethome', 'event', targets=['sethome'])
a('aftrhrs/back', 'aftrhrs/home', 'recovery_compass', 'Second Chances', 'Go back with /back', 'event', targets=['back'])
a('aftrhrs/warp', 'aftrhrs/hub', 'ender_pearl', 'Fast Travel', 'Use a warp', 'event', targets=['warp'])
a('aftrhrs/rtp', 'aftrhrs/warp', 'grass_block', 'Into the Wild', 'Take a random teleport with /rtp', 'event', targets=['rtp'])
a('aftrhrs/tpa', 'aftrhrs/hub', 'player_head', 'Visiting Hours', 'Teleport to a friend, or bring one over, with /tpa',
  'event', targets=['tpa'])
a('aftrhrs/msg', 'aftrhrs/tpa', 'paper', 'Psst!', 'Send someone a private message', 'event', targets=['msg'])
a('aftrhrs/carry', 'aftrhrs/tpa', 'carrot_on_a_stick', 'Piggyback Ride',
  'Sneak and right-click a friend with an empty hand to carry them', 'event', targets=['carry'])
a('aftrhrs/nick', 'aftrhrs/hub', 'name_tag', 'What\'s in a Name', 'Pick a nickname with /nick', 'event', targets=['nick'])
a('aftrhrs/hat', 'aftrhrs/nick', 'leather_helmet', 'Hat Trick', 'Wear something silly with /hat', 'event', targets=['hat'])
a('aftrhrs/sit', 'aftrhrs/root', 'oak_stairs', 'Take a Seat', 'Sit down with /sit or on some stairs', 'event', targets=['sit'])
a('aftrhrs/lay', 'aftrhrs/sit', 'white_bed', 'Power Nap', 'Lie down with /lay', 'event', targets=['lay'])
a('aftrhrs/swim', 'aftrhrs/lay', 'turtle_scute', 'Army Crawl', 'Crawl along with /swim', 'event', targets=['swim'])
a('aftrhrs/afk', 'aftrhrs/sit', 'clock', 'Brb', 'Step away from the keyboard', 'event', targets=['afk'])
a('aftrhrs/trail', 'aftrhrs/root', 'firework_star', 'Shooting Star', 'Pick a flight trail with /trail', 'event',
  targets=['trail'])
a('aftrhrs/lock', 'aftrhrs/root', 'tripwire_hook', 'Keep Your Hands Off', 'Lock a chest with a PIN', 'event',
  targets=['lock'])
a('aftrhrs/lock_share', 'aftrhrs/lock', 'player_head', 'Spare Key', 'Let a friend open your locked chest', 'event',
  targets=['lock-share'])
a('aftrhrs/hour', 'aftrhrs/root', 'clock', 'Settling In', 'Play for an hour', 'playtime', value=1)
a('aftrhrs/ten_hours', 'aftrhrs/hour', 'campfire', 'Regular', 'Play for 10 hours', 'playtime', 'goal', value=10)
a('aftrhrs/night_owl', 'aftrhrs/ten_hours', 'lantern', 'Night Owl', 'Play for 100 hours. Truly after hours.', 'playtime',
  'challenge', value=100)

# ------------------------------------------------------------------ Companions (taming)
root('companions/root', 'oraxen:arcana_taming_guide', '<gradient:#b57cff:#ffb24a>Companions</gradient>',
     'Cute critters and loyal friends', 'minecraft:block/moss_block')
a('companions/guide', 'companions/root', 'oraxen:arcana_taming_guide', 'Required Reading', 'Open the taming guide',
  'event', targets=['guide'])
a('companions/first_pet', 'companions/guide', 'oraxen:arcana_plush_beagle', 'First Friend', 'Tame a pet', 'tame',
  targets=pets)
a('companions/three_pets', 'companions/first_pet', 'lead', 'Pack Leader', 'Have 3 pets at once', 'pets-count', 'goal',
  value=3)
a('companions/bonded', 'companions/first_pet', 'cookie', 'Bonded', 'Raise a pet\'s bond to level 3', 'pet-level', value=3)
a('companions/best_friends', 'companions/bonded', 'cake', 'Best Friends', 'Raise a pet\'s bond to the top level',
  'pet-level', 'challenge', value=5)
a('companions/all_pets', 'companions/three_pets', 'oraxen:arcana_plush_golden_retriever', 'Menagerie',
  'Tame every kind of pet', 'tame-all', 'challenge', targets=pets)
if mounts:
    flying = [m for m in mounts if 'Cinderwing' in m or 'Roc' in m]
    a('companions/first_mount', 'companions/guide', 'saddle', 'Saddle Up', 'Tame a mount', 'tame', targets=mounts)
    if flying:
        a('companions/flying_mount', 'companions/first_mount', 'oraxen:arcana_plush_cinderwing_phoenix',
          'Up, Up and Away', 'Tame a flying mount', 'tame', 'goal', targets=flying)
    a('companions/all_mounts', 'companions/first_mount', 'golden_horse_armor', 'Stable Master', 'Tame every kind of mount',
      'tame-all', 'challenge', targets=mounts)
if hostiles:
    a('companions/first_hostile', 'companions/guide', 'oraxen:arcana_plush_cinder_imp',
      'Unlikely Friends', 'Tame a creature that wanted to hurt you', 'tame', 'goal', targets=hostiles)
    a('companions/all_hostiles', 'companions/first_hostile', 'wither_skeleton_skull', 'Monster Whisperer',
      'Tame every kind of hostile creature', 'tame-all', 'challenge', targets=hostiles)
a('companions/everything', 'companions/all_pets', 'nether_star', 'Gotta Tame \'Em All', 'Tame every creature in Arcana',
  'tame-all', 'challenge', targets=pets + mounts + hostiles)
for mm_id, key, title, desc in SPECIAL:
    if mm_id in pets:
        a('companions/' + key, 'companions/first_pet', 'oraxen:' + plush_of[mm_id], title, desc, 'tame', 'goal',
          targets=[mm_id], hidden=True)
a('companions/whistle', 'companions/first_pet', 'oraxen:arcana_pet_whistle', 'Here, Boy!', 'Get a pet whistle', 'obtain',
  targets=['arcana_pet_whistle'])
if plushies:
    a('companions/plush', 'companions/root', 'oraxen:arcana_plush_beagle', 'Cuddly', 'Get a plushie', 'obtain',
      targets=plushies)
    a('companions/all_plush', 'companions/plush', 'oraxen:arcana_plush_kevin', 'Plush Collector', 'Collect every plushie',
      'obtain-all', 'challenge', targets=plushies)

# ------------------------------------------------------------------ Treasures (items)
root('treasures/root', 'oraxen:' + (relics[0] if relics else 'arcana_taming_guide'),
     '<gradient:#ffd38a:#ff8fa3>Arcana Treasures</gradient>', 'Tools, trinkets and relics', 'minecraft:block/amethyst_block')
for key, group, icon_id, title, desc in [('hammer', hammers, 'arcana_iron_hammer', 'Heavy Lifting', 'Get a hammer'),
                                         ('excavator', excavators, 'arcana_iron_excavator', 'Dig Deep', 'Get an excavator'),
                                         ('lumber_axe', axes, 'arcana_iron_lumber_axe', 'Timber!', 'Get a lumber axe'),
                                         ('scythe', scythes, 'arcana_iron_scythe', 'Reaper', 'Get a harvest scythe')]:
    if group:
        a('treasures/' + key, 'treasures/root', 'oraxen:' + icon_id, title, desc, 'obtain', targets=group)
netherite = [i for i in hammers + excavators + axes + scythes if 'netherite' in i]
if netherite:
    a('treasures/netherite_tools', 'treasures/hammer', 'oraxen:arcana_netherite_hammer', 'Overkill',
      'Get every netherite Arcana tool', 'obtain-all', 'challenge', targets=netherite)
if backpacks:
    a('treasures/backpack', 'treasures/root', 'oraxen:' + backpacks[0], 'Pack Mule', 'Get a backpack', 'obtain',
      targets=backpacks)
    a('treasures/big_backpack', 'treasures/backpack', 'oraxen:' + backpacks[-1], 'Expedition Ready',
      'Get the biggest backpack', 'obtain', 'goal', targets=[backpacks[-1]])
for key, item, title, desc in [('magnet', 'arcana_magnet_charm', 'Attractive', 'Get a magnet charm'),
                               ('wand', 'arcana_builders_wand', 'Master Builder', 'Get a builder\'s wand'),
                               ('xp_flask', 'arcana_xp_flask', 'Bottled Wisdom', 'Get an XP flask')]:
    if item in gear:
        a('treasures/' + key, 'treasures/root', 'oraxen:' + item, title, desc, 'obtain',
          targets=[i for i in (item, item + '_on') if i in gear])
# The seasonal Santa hat (Christmas tab) is left out of the hats on purpose: adding it to all_hats would un-complete
# Haberdasher for everyone who has it.
if hats:
    a('treasures/hat', 'treasures/root', 'oraxen:' + hats[0], 'Fancy Hat', 'Wear an Arcana hat', 'wear', targets=hats)
    crowns = [h for h in hats if 'crown' in h]
    if crowns:
        a('treasures/crown', 'treasures/hat', 'oraxen:' + crowns[0], 'Royalty', 'Wear a crown', 'wear', 'goal', targets=crowns)
    a('treasures/all_hats', 'treasures/hat', 'oraxen:' + hats[len(hats) // 2], 'Haberdasher', 'Collect every hat',
      'obtain-all', 'challenge', targets=hats)
if relics:
    a('treasures/relic', 'treasures/root', 'oraxen:' + relics[0], 'Relic Hunter', 'Find a relic', 'obtain', 'goal',
      targets=relics)
    a('treasures/all_relics', 'treasures/relic', 'oraxen:arcana_relic_ruyi_jingu_bang', 'Legendary Arsenal', 'Own every relic',
      'obtain-all', 'challenge', targets=relics)

# ------------------------------------------------------------------ Bosses
root('bosses/root', 'oraxen:arcana_summoning_altar', '<gradient:#ff8fa3:#ffd38a>Legends</gradient>',
     'Summon and slay the bosses of Arcana', 'minecraft:block/blackstone')
a('bosses/summon', 'bosses/root', 'oraxen:arcana_summoning_altar', 'Altar Ego', 'Summon a boss at a Summoning Altar', 'event',
  targets=['altar'])
for mob, summon, title, desc in BOSSES:
    a('bosses/' + mob[6:].lower(), 'bosses/summon', 'oraxen:' + summon, title, desc, 'kill', 'goal', targets=[mob])
if BOSSES:
    a('bosses/all', 'bosses/summon', 'netherite_sword', 'Slayer of Legends', 'Defeat every boss', 'kill-all', 'challenge',
      targets=[b[0] for b in BOSSES])
if trophies:
    a('bosses/trophy', 'bosses/root', 'oraxen:' + trophies[0], 'Bragging Rights', 'Win a boss trophy', 'obtain', targets=trophies)
    a('bosses/all_trophies', 'bosses/trophy', 'oraxen:' + trophies[-1], 'Trophy Room', 'Collect every boss trophy',
      'obtain-all', 'challenge', targets=trophies)

# ------------------------------------------------------------------ Home & decor
root('decor/root', 'oraxen:arcana_armchair', '<gradient:#86e8b0:#8fd3ff>Home & Decor</gradient>',
     'Make the place your own', 'minecraft:block/oak_planks')
# Every visible furniture piece in any creative tab, resolved by AdvancementsModule at load (furniture:<tab>[!<tab or
# id glob>]); the Feather power machines are not Arcana furniture.
a('decor/first', 'decor/root', 'oraxen:arcana_armchair', 'Moving In', 'Place a piece of Arcana furniture', 'place',
  targets=['furniture:*!feather_*'])
for crit, title, desc, icon, members in COLLECTIONS:
    a('decor/' + crit, 'decor/first', icon, title, desc, 'place', targets=members)
if planters:
    a('decor/planter', 'decor/first', 'oraxen:' + planters[0], 'Green Thumb', 'Place a planter', 'place', targets=planters)
a('decor/designer', 'decor/first', 'painting', 'Interior Designer', 'Place something from every furniture collection',
  'place-all', 'challenge', groups={c[0]: c[4] for c in COLLECTIONS})
# Chess tables (feature/chess): the winner of a checkmate reports the event "chess-checkmate".
a('decor/checkmate', 'decor/first', 'oraxen:arcana_chess_table', 'Checkmate!', 'Win a game of chess at a chess table by checkmate',
  'event', 'goal', targets=['chess-checkmate'])

# ------------------------------------------------------------------ Anvil & Ember (feature/smithing)
# The smithing stations report events through Feather.advance ("event", "smith_*"): SmithingProgress.
root('smithing/root', 'oraxen:smithing_anvil', '<gradient:#ffb36b:#e2702a>Anvil & Ember</gradient>',
     'Smith your own tools, part by part', 'minecraft:block/deepslate_bricks')
a('smithing/carve', 'smithing/root', 'oraxen:carving_bench', 'Stone Age', 'Carve a part on the Carving Bench', 'event', targets=['smith_carve'])
a('smithing/assemble', 'smithing/carve', 'oraxen:assembly_bench', 'Some Assembly Required', 'Rivet a tool together on the Assembly Bench',
  'event', targets=['smith_assemble'])
a('smithing/cast', 'smithing/assemble', 'oraxen:casting_bench', 'Pour Decisions', 'Cast a part in a mold', 'event', targets=['smith_cast'])
a('smithing/forge', 'smithing/cast', 'oraxen:smithing_anvil', 'Strike While It\'s Hot', 'Finish a part on the Smithing Anvil', 'event',
  targets=['smith_forge'])
a('smithing/forge_5', 'smithing/forge', 'oraxen:smithing_anvil', 'Perfect Rhythm', 'Forge a five-star part', 'event', 'goal', targets=['smith_forge_5'])
a('smithing/alloy', 'smithing/cast', 'oraxen:crucible', 'Better Together', 'Stir an alloy in a crucible', 'event', targets=['smith_alloy'])
a('smithing/all_alloys', 'smithing/alloy', 'oraxen:ingot_starforged', 'Metallurgist', 'Make every alloy', 'event-all', 'challenge',
  targets=['smith_alloy_' + x for x in ('steel', 'rose_gold', 'chime_bronze', 'tidebronze', 'rimesteel', 'emberforged_steel',
                                         'pearlsteel', 'echo_steel', 'voidgold', 'starforged')])
a('smithing/masterwork', 'smithing/forge_5', 'oraxen:smithed_pickaxe', 'Masterwork', 'Assemble a tool from five-star parts', 'event',
  'challenge', targets=['smith_masterwork'])
a('smithing/etch', 'smithing/assemble', 'oraxen:runesmith_table', 'Runesmith', 'Etch a modifier at the Runesmith Table', 'event',
  targets=['smith_etch'])
a('smithing/trophy', 'smithing/etch', 'oraxen:arcana_trophy_wyrm_horns', 'Trophy-bound', 'Bind a boss trophy into a tool', 'event', 'goal',
  targets=['smith_trophy_bound'])
a('smithing/tier4', 'smithing/alloy', 'oraxen:great_crucible_drain', 'Forged in the Deep', 'Assemble a tool with a tier-4 head', 'event',
  'goal', targets=['smith_tier4'])

# ------------------------------------------------------------------------------------------------------ checks
for k, e in adv.items():
    assert e.get('parent') or k.endswith('/root'), '%s has no parent' % k
    if e['trigger'] in ('tame', 'kill', 'tame-all', 'kill-all'):
        assert e.get('targets'), '%s has no targets' % k
        for t in e['targets']:
            assert t in mm_ids, '%s: no creature %s' % (k, t)
for k, e in adv.items():
    if e['trigger'] in ('obtain', 'wear', 'obtain-all') or (e['trigger'] == 'place' and k != 'decor/first'):
        for t in e.get('targets') or []:
            assert t in ITEM_IDS, '%s: no item %s' % (k, t)

header = """# Feather advancements. Generated by mod/tools/gen_advancements.py from the mod's species and content tables;
# re-run it after adding creatures or content (hand edits here are overwritten). Texts are MiniMessage.
# See docs/advancements.md.
#
# Each entry: parent, icon (an item id or oraxen:<id>), title, description, frame (task/goal/challenge),
# trigger, targets / groups / value, and optionally hidden, toast, announce, background (tab roots).
# Item targets may also be furniture:<tab>[!<tab or id glob>] (every visible furniture piece of a creative tab).
"""


class NoAliases(yaml.SafeDumper):
    # Shared lists (e.g. every pet for two advancements) are written out each time, not as &id001 anchors.
    def ignore_aliases(self, data):
        return True


body = yaml.dump({'advancements': adv}, Dumper=NoAliases, sort_keys=False, allow_unicode=True, width=140)
with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
    f.write(header + body)
print('wrote %d advancements: %d pets, %d mounts, %d hostiles, %d plushies, %d furniture collections'
      % (len(adv), len(pets), len(mounts), len(hostiles), len(plushies), len(COLLECTIONS)))
