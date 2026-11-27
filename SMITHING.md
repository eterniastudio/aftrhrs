# Anvil & Ember: hands-on modular blacksmithing (design)

Status: **built** (2026-11-22, branch `smithing`): all three phases at once, as decided (keep vanilla tools, timing
meter, everything at once, "Anvil & Ember"). Code: `feature/smithing/` (stations in `station/`), client in
`client/smithing/`, data in `data/aftrhrs/smithing/*.json`, art from `tools/smithing_art.py` and
`tools/smithing_blocks.py`, assets and data from `tools/gen_smithing.py` (run by `gen_content.py`), tests in
`gametest/.../content/SmithingTests.java`. Staff command: `/smith give|part|kit|mod|heat` (`/smithing` is the
vanilla smithing table). Settings: `smithing.yml`.

Built differently from this design, or not yet:
- Melting points are game values (iron 1150°, steel 1350°, netherite 1550°), so coal with bellows melts iron and
  the hotter alloys need blaze rods or lava. Hammer faces can be carved from stone (the first hammer).
- Brine is water and dried kelp (there is no salt item). Fish oil comes from fish melted in a crucible, or a bottle
  and two fish.
- The recipe note is a card beside the crosshair, not a paper on the block.
- Hoppers feed crucibles. A hopper can't yet take cast pieces off a casting bench.
- Not done: the Emberkit keeping a hearth warm, the coal generator pre-warming a crucible, the shop selling molds,
  a daily smithing quest, and a Smithing tab in `/inspect`.

What it is: Tinkers' Construct-level modularity (tools built from parts, every part from a material, materials carry
traits, tools take modifiers in slots) made the Hands-On Delight way. You smith with your own hands, in the world, at
blocks that show what you're doing: heat glowing in the coals, molten metal in the crucible, the blade taking shape
under the hammer, steam off the quench. There are no crafting grids or menus for the work itself.

Research behind this: `C:/Users/kevin/Desktop/aftrhrs-wt/smithing-research/` (`tic3_materials.md`,
`tic3_tools_modifiers.md`: Tinkers' Construct 3 materials, traits, alloys, tools, parts, about 150 modifiers, slot
rules and the smeltery flow, with numbers). The Hands-On Delight video showed the interaction style: a skillet with a
heat gauge, flipping by hand, chopping on a board, stacking a burger, stirring a pot, star ratings, and recipe
notes pinned to the station.

All names, numbers and art here are our own. Tinkers is the reference for depth, not something to copy.

---

## 1. Pillars

1. **Hands, not menus.** Every step is a right-click, a strike or a pour on a block that shows the result. Small
   on-block gauges (heat dial, strike meter, star rating) replace screens. Recipe notes pinned to each station say what
   comes next, like the order tickets in Hands-On Delight.
2. **Depth like Tinkers.** About 20 tools are each built from 2-4 parts. About 26 materials each give different stats
   and a trait per part role. About 50 modifiers sit in upgrade / ability / defense slots. The same pickaxe can be a
   fast, fragile rose gold digger or a heavy rimesteel tunnel-maker.
3. **Skill matters, a little.** How well you forge (heat, strike timing) gives each part 1-5 stars, worth up to ±15%
   stats. A full 5-star tool earns a bonus slot. A careless smith still gets a working tool, never a broken mess.
4. **Woven into Aftr Hrs.** The fans from the power module work the bellows. Fishing gives fish oil for quenching.
   Creatures give hides, silk and feathers for grips, strings and fletching. Winter gives permafrost for rimesteel.
   Boss trophies unlock ability slots. Graves respect Soulbound. Tool stats record the smith. (Section 10.)
5. **Vanilla+.** Iron, coal, lava, sand and clay are the backbone, and tools still look like Minecraft tools: original
   16x16 art in the mod's style, made from part sprites recolored per material.

---

## 2. The loop

```
 ore / ingots / scrap ──► CRUCIBLE (melt, alloy) ──► LADLE / SPOUT ──► MOLD on CASTING BENCH ──► rough part ★
                               ▲ heat                                                              │
                         FORGE + BELLOWS ◄───────────── reheat ◄──────────────────────────────────┤
                               │ glowing workpiece                                                 ▼
                               └────────► ANVIL (hammer strikes) ──► QUENCH TROUGH ──► finished part ★★★★
 wood / bone / stone / hide / silk ──► CARVING BENCH (knife, chisel, wrap) ──────────► finished part ★★★
                                                                                                   ▼
                                         ASSEMBLY BENCH (lay parts in order, rivet) ──► MODULAR TOOL
                                                                                                   ▼
                       GRINDSTONE (hone edges, grind off modifiers) ◄──► RUNESMITH TABLE (etch modifiers into slots)
```

- **Early game, no metal yet:** carve wood, stone, flint and bone parts on the Carving Bench, then assemble.
  Stone-age tools are better than vanilla wood and stone ones.
- **Iron age:** forge, crucible and anvil. Cast a rough head, refine it on the anvil, quench it, assemble.
- **Late game:** alloys (steel, rose gold, rimesteel, emberforged steel...) need a hotter forge (bellows, blaze fuel,
  then lava). Tier-4 metals need the Great Crucible.

---

## 3. Stations

Every station is a block with a block-entity renderer that shows its contents in 3D, built in the furniture style the
user approved (Blockbench models, original textures). Each station has a pinned **recipe note**, a paper on its side
that shows the current plan and timer for whatever is on it.

### 3.1 Forge Hearth (+ Bellows, + Chimney)
- 2 blocks tall: a stone-and-brick hearth with a hood/chimney on top (smoke particles).
- **Fuel:** charcoal or coal (1 heat unit), coal block (9), blaze rod (4, hotter cap), lava bucket (12, needed above
  1300°). Fuel sits visibly in the coal bed.
- **Heat** in degrees, 0-1600: it climbs while burning and cools slowly. A **heat dial** on the front has a needle, and
  color bands show which tier of metal the heat suits.
- **Bellows** block beside the hearth: right-click to pump. Each pump raises heat toward the current fuel's cap and burns
  fuel faster. **An Encased Fan from the power module blowing into the hearth pumps it automatically** (this is the
  automation link).
- **Workpieces:** up to 3 rest on the coals. Their color follows their own temperature: cold, dull red, cherry, orange,
  yellow, white. Each part has a working window (for example iron 800-1150°). Leaving a part in too long overheats it
  (scale, -1 star). Pick a part up with **tongs**, or a bare hand with a burn.
- An **Emberkit** pet sitting by the hearth keeps it at least warm (its "ignite" utility).

### 3.2 Crucible (and Great Crucible)
- A clay-and-iron pot that sits on a lit hearth (or on its own heater block). You see molten metal as a glowing liquid,
  with its level and swirl color, and solid chunks sinking until they melt.
- **Melting:** ingots (90 units), nuggets (10), blocks (810), raw ore (130, a small reward for smelting ore here),
  scrap (broken tools and parts give back 75% of their metal).
- **Alloying:** put the right metals in at the right ratio and heat, and they **stir into the alloy**. Right-click
  with a **stirring rod**: 3 stirs per batch, like stirring the pot in Hands-On Delight. Wrong ratios leave both metals
  separate (no loss).
- **Capacity:** 4 ingots. The **Great Crucible** is a 3x3x2 multiblock of fire bricks with a drain. It holds 36 ingots
  and is needed for tier-4 metals.
- **Out:** tip it with a **ladle** (an item that carries up to 4 ingots of one metal; dumped in water it makes ingots),
  or fit a **spout** that pours straight into a mold below.

### 3.3 Casting Bench + Molds
- A low stone table. Put a mold on it and pour. Molten metal fills the mold (animation), cools from orange to dark,
  and a hiss and steam mark when it's ready. Knock it out with a hammer to get a **rough part**.
- **Sand mold** (single use): pack a sand box (4 sand), press a template part into it (any part of that shape; the
  template is returned), remove it, and the imprint stays. Also makes ingot and nugget molds.
- **Fired clay mold** (reusable): shape clay around a template on the bench, then fire it in the forge. It lasts 32
  pours, so it's the volume option.
- A rough part from casting is **1 star** and has its shape and material. Finishing it on the anvil (heat, strikes,
  quench) is what raises its stars and is often required (see 3.4). Casting is the quick, low-skill way. Hammering a
  part from ingots on the anvil skips casting entirely but takes more strikes.

### 3.4 Smithing Anvil (the heart of it)
- Our own anvil block. Place a glowing workpiece (rough part, or ingots for forging from scratch) with tongs. Strike
  with the **smithing hammer** (a modular tool, so its material speeds forging).
- **Plan:** the recipe note shows the part's plan as a short row of moves: **Draw** (lengthen), **Flatten**,
  **Bend**, **Punch** (eye, for axes and hammers), **Fold** (for steel). Example, pickaxe head: Draw, Draw, Bend,
  Flatten.
- **Strike meter:** a small arc above the anvil with a moving marker and a sweet spot. Right-click when the marker is in
  the spot. Sneak + right-click strikes heavy, which counts double but is harder to time.
  - The spot narrows with the metal's tier and as the workpiece cools.
  - Below the working temperature the metal won't move (a dull clink): reheat it.
- **Shape progress** is shown on the piece itself: the model steps from bar to blank to finished shape.
- **Stars:** 1-5, from strike accuracy and staying in the heat window. The recipe note shows them as you work.
- Forging a part from scratch takes about 6-12 good strikes. Refining a cast part takes about 3-5.
- Netcode: the server owns the timing (marker phase from server ticks plus the player's latency allowance). The client
  only shows it, so autoclickers gain nothing over good timing.

### 3.5 Quench Trough
- A wooden trough filled with a liquid. Dunk a hot finished part: steam, a hiss, and the part is **set**.
  - **Water:** +10% durability, but quenching a tier-3+ part above its window can **crack** it (-1 star).
  - **Fish oil:** safe for every tier, +5% attack speed. Made from fish in a cooking pot (fishing link).
  - **Brine:** water + salt (salt comes from the coast content). Safe, +5% mining speed.
  - **Frost brine:** water + a permafrost shard. Gives any steel the "Frost-tempered" trait (section 6). Winter link.
- A part that isn't quenched counts as **soft**: -10% durability. Air-cooling on the anvil is allowed.

### 3.6 Carving Bench (the cutting board)
- A thick wooden bench. Put wood, bone, stone, flint, bamboo or hides on it and work them with a **knife** or
  **chisel**. Each strike shows the cut, like chopping on the Hands-On Delight board.
- Makes handles, tough handles, bindings, grips, arrow shafts and fletchings, plus stone-age heads (stone, flint,
  bone).
- **Wrapping:** put leather, deer hide, tufthorn hide or silk over a carved handle to wrap it. The wrap adds the hide's
  grip trait on top of the wood's.
- Stars come from strike count and rhythm (an easier meter than the anvil).

### 3.7 Assembly Bench (the burger stack)
- A sturdy workbench with a tool outline painted on top. Lay parts on it **in order**: head, then binding, then handle.
  Each part snaps into place on the drawing, and a wrong part just won't snap. Rivet with the hammer and the tool pops
  up, assembled.
- **Part swap:** put a tool on the bench, take a part off, and put another on. Modifiers stay, and the slot count is
  checked again.
- **Repair:** a broken or worn tool plus its head material (an ingot or a carved piece), then 3 hammer taps. It
  restores durability per unit of material, with the same values as melting.

### 3.8 Grindstone (whetstone wheel)
- A stone wheel on a frame. Hold a bladed tool against it and keep right-click held to crank it. Sparks fly while it
  **restores Edge** (section 5.4).
- **Grind off:** a modifier can be ground off a tool. The slot comes back, but the catalyst is lost, unless you use a
  **Scribe's Crystal** to keep it as a reusable modifier crystal.

### 3.9 Runesmith Table
- A heavy dark-stone table with rune grooves. Put the tool in the middle and up to 4 **catalysts** around it, all shown
  on the table.
- Etch with a **chisel**: a short rhythm of 3-5 strikes, with glowing lines drawn along the grooves. The modifier
  goes into its slot.
- **Incremental modifiers** take catalysts a few at a time. Progress is stored on the tool, and the recipe note shows
  "Keen Edge II: 21/36 quartz".

### 3.10 Hand tools (all simple items except the hammer)
- **Smithing hammer:** modular, made from a hammer face plus a handle. Its material speeds anvil work and adds strike
  forgiveness.
- **Tongs**, **knife**, **chisel**, **stirring rod**, **ladle**.

---

## 4. Parts

Costs are in ingots for metal (90 units each) or the same number of carved pieces. "Head" parts carry the main stats,
"handle" parts carry multipliers, and "binding" parts carry only traits.

| Part | Role | Cost | Made at | Used by |
|---|---|---|---|---|
| Pick head | head | 2 | cast / anvil / carve (stone, flint, bone) | pickaxe, mattock (½), war pick |
| Axe head | head | 2 | cast / anvil / carve | hatchet, mattock (½) |
| Shovel blade | head | 1 | cast / anvil / carve | shovel |
| Hoe blade | head | 1 | cast / anvil / carve | hoe |
| Blade | head | 2 | cast / anvil | sword, dagger (makes 2), spear |
| Broad blade | head | 6 | anvil (Fold) | greatsword, scythe |
| Hammer face | head | 6 | cast / anvil | sledge, maul, smithing hammer (2) |
| Broad axe head | head | 6 | anvil | broad axe |
| Large plate | head (part weight) | 4 | cast / anvil | sledge, excavator, shield |
| Spearhead | head | 2 | cast / anvil | spear, glaive |
| Guard | binding (traits) | 1 | cast / anvil | sword, greatsword, dagger |
| Pommel | binding (traits) | 1 | cast / carve | sword, greatsword, mace |
| Binding | binding | 1 | carve / anvil (wire) | pickaxe, hatchet, shovel, hoe, mattock |
| Tough binding | binding | 3 | carve / anvil | broad tools |
| Handle | handle | 1 | carve (wood, bone, bamboo) / cast | most small tools |
| Tough handle | handle | 3 | carve / cast | broad tools, spear |
| Grip | handle | 1 | carve + wrap | bows, crossbow, dagger |
| Bow limb | limb | 2 | carve (wood, bone) / anvil (metal: springy) | bows, crossbow, fishing rod |
| Bowstring | string (traits) | 1 | carve (string, silk) | bows, crossbow, fishing rod |
| Arrowhead | head | 1 (makes 4) | cast / carve (flint) | arrows, bolts |
| Fletching | traits | 1 (makes 4) | carve (feather, down feather, leaf) | arrows |
| Helm / cuirass / greave / sabaton plates | plating | 3 / 6 / 5 / 2 | anvil | armor |
| Mail | binding | 2 | anvil (chain rings) | armor |

---

## 5. Tools and stats

### 5.1 Tools (about 20)

| Tool | Parts | Behavior | Slots (U/A/D) |
|---|---|---|---|
| Pickaxe | pick head, binding, handle | normal | 3/1/0 |
| Hatchet | axe head, binding, handle | strips logs; disables shields | 3/1/0 |
| Shovel | shovel blade, binding, handle | paths | 3/1/0 |
| Hoe | hoe blade, binding, handle | tills; right-click on a ripe crop harvests and replants (Arcana crops too) | 3/1/0 |
| Mattock | pick head ½ + axe head ½, handle | pickaxe + axe | 3/1/0 |
| Sword | blade, guard, handle | sweep | 3/1/0 |
| Dagger | blade, guard, grip (crafts 2) | fast; off-hand attack; backstab +50% from behind | 3/1/0 |
| Spear | spearhead, tough handle | reach +1; charge thrust while sprinting; throwable with Recall | 3/1/0 |
| Mace | hammer face, pommel, handle | smash damage scales with fall (vanilla mace behavior) | 3/1/0 |
| Greatsword | broad blade, guard, pommel, tough handle | wide sweep, slow | 2/1/0 |
| Sledge | hammer face, large plate ×2, tough handle | mines 3x3 (reuses the `AreaTools` logic) | 2/1/0 |
| Excavator | large plate, tough binding, tough handle | digs 3x3 | 2/1/0 |
| Broad Axe | broad axe head, tough binding, tough handle | fells the whole tree (lumber logic) | 2/1/0 |
| Scythe | broad blade, tough binding, tough handle | harvests 3x3 crops and replants (works with the `Scythes` logic and Arcana crops) | 2/1/0 |
| Smithing Hammer | hammer face ×2, handle | the anvil tool; also a weak maul | 2/1/0 |
| Shortbow | bow limb, grip, bowstring | fast draw | 3/1/0 |
| Longbow | bow limb ×2, grip, bowstring | power, slow draw | 2/1/0 |
| Crossbow | bow limb, grip (stock), bowstring | holds a shot | 3/1/0 |
| Fishing Rod | bow limb, bowstring, arrowhead (hook) | **plugs into the fishing module:** stats feed bite time, fight strength and line tension | 3/1/0 |
| Shield | large plate, handle (rim) | blocks; metal shields resist axes longer | 2/0/2 |
| Armor (4 pieces) | plates + mail | protection from plating material | 2/0/3 |
| Arrows / bolts | arrowhead, shaft (handle material), fletching | ammo, made 4 at a time | n/a |

### 5.2 How stats are built
- Each **material** has **head stats**: durability, mining speed, harvest tier (wood, stone, iron, diamond,
  netherite), attack damage. It has **handle multipliers**: durability, mining speed, attack damage, attack speed.
  It has a **trait per role**: head, handle, binding, limb/grip, plating.
- A tool's head stats are the weighted average of its head parts. That is multiplied by the average of its handle
  multipliers, then by the tool type's own multipliers (a sledge has ×0.4 mining speed and ×4 durability, for example).
  Then quality (5.3) and modifiers apply.
- The result is written into vanilla components (max damage, tool rules, attribute modifiers), so everything vanilla
  (enchantment-like checks, Mending-like repair if we allow it, other mods' tooltips) sees a normal tool.
- **Broken, not destroyed:** at 0 durability the tool stops working and shows as broken, but it never disappears.
  Repair it at the Assembly Bench.

### 5.3 Quality (stars)
- Each part has 1-5 stars. The tool's quality is the average.
- Stats scale with quality: ★ ×0.85, ★★ ×0.93, ★★★ ×1.0, ★★★★ ×1.07, ★★★★★ ×1.15. This applies to durability, mining
  speed and attack damage.
- **Masterwork:** a tool where every part is 5 stars gets **+1 upgrade slot** and a golden name. The tool-stats
  module records "Forged by <player>, Masterwork".
- The quench (3.5) adds its own small bonus on top.

### 5.4 Edge (bladed tools and weapons)
- Swords, daggers, axes, spears, scythes and the greatsword have **Edge**, 0-100, shown as a thin bar under the
  durability bar.
- A sharp edge gives up to +15% damage. Edge drops slowly with hits, and the Grindstone restores it.
- This gives players a little maintenance ritual, and it can be turned off in the config.

---

## 6. Materials (26) and traits

Tiers: **1** carved / copper, **2** iron age, **3** alloys (needs bellows / blaze fuel), **4** great crucible / lava.
Stats are given as head durability / speed / tier / attack, then handle multipliers (dur, speed, atk, atk speed). They
will be balanced against vanilla in testing; these are starting points.

| # | Material | Tier | Source | Head | Handle | Trait (role) |
|---|---|---|---|---|---|---|
| 1 | Wood | 1 | any planks/logs, carved | 60 / 2 / wood / 0 | ×1, ×1, ×1, ×1 | **Seasoned** (handle): +1 durability per 50 blocks mined, up to +100 |
| 2 | Stone | 1 | cobblestone family, carved | 130 / 4 / stone / 1 | ×0.9, ×1.05 | **Bedrock-born** (head): mining speed rises as durability falls (up to +30%) |
| 3 | Flint | 1 | flint, knapped | 85 / 3.5 / stone / 1.25 | ×0.85, atk ×1.1 | **Serrated** (head): damage rises as durability falls (up to +2) |
| 4 | Bone | 1 | bone, carved | 100 / 2.5 / stone / 1.25 | ×0.75, atk spd ×1.1 | **Splintering** (head): crits make targets bleed 1 heart over 3 s |
| 5 | Bamboo | 1 | bamboo, carved | — | ×0.8, atk spd ×1.15 | **Springy** (handle/limb): +10% attack speed / draw speed |
| 6 | Leather | 1 | leather wrap | — | ×0.95, atk spd ×1.05 | **Sure Grip** (handle): no fumble while wet; +5% attack speed |
| 7 | Silk | 1 | Arcana silk (creature) | — | — | **Taut** (bowstring): +15% arrow speed |
| 8 | Copper | 1 | ingots, cast/forged | 210 / 5 / iron / 0.5 | ×0.8, ×1.1, ×1.05 | **Deepdelver** (head): +25% mining speed below Y 0, -15% above Y 64 |
| 9 | Deer hide | 1 | deer drop | — | ×1.0, atk spd ×1.08 | **Steady** (grip): bow sway reduced 40% |
| 10 | Tufthorn hide | 2 | tufthorn drop | — | ×1.1 | **Shaggy** (handle): +10% knockback resistance while held |
| 11 | Iron | 2 | ingots | 250 / 6 / iron / 2 | ×1.1 | **Lodestone** (head): drops within 3 blocks fly to you |
| 12 | Gold | 2 | ingots | 60 / 12 / wood / 0 | ×0.6, ×1.2 | **Gilded** (any): +1 Luck (Fortune/Looting); piglins stay calm |
| 13 | Obsidian | 2 | obsidian, melted | 700 / 3 / diamond / 1.5 | ×1.3, ×0.7 | **Unyielding** (head): never loses more than 1 durability per use; -20% speed |
| 14 | Down feather | 2 | Arcana down feather | — | — | **Glide** (fletching): arrows fly 30% farther, less drop |
| 15 | Steel | 3 | iron + charcoal (Fold ×2 on anvil) | 600 / 7 / diamond / 2.5 | ×1.15, ×1.0 | **Tempered** (any): quench bonuses doubled |
| 16 | Rose gold | 3 | copper + gold, 1:1 | 175 / 9 / iron / 1 | ×0.7, ×1.1, atk spd ×1.1 | **Lustrous** (any): +1 upgrade slot per part (max +2) |
| 17 | Chime bronze | 3 | copper + amethyst shards | 720 / 7 / diamond / 1.5 | ×1.0, ×1.1 | **Resonant** (head): mining stone pings ores within 6 blocks (they glow 3 s) |
| 18 | Tidebronze | 3 | copper + prismarine shards | 520 / 6.5 / diamond / 2 | ×1.0 | **Tidebound** (any): no mining penalty underwater; +2 damage while it rains or you're wet |
| 19 | Rimesteel | 3 | steel + permafrost shards (winter) | 650 / 7 / diamond / 2.25 | ×1.1 | **Frostbite** (head): hits slow targets (Slowness I, 2 s); +20% speed in cold biomes |
| 20 | Emberforged steel | 3 | steel + blaze powder | 620 / 7.5 / diamond / 2.5 | ×1.05 | **Ember** (head): smelts drops 25% of the time; hits set targets on fire for 3 s |
| 21 | Pearlsteel | 3 | steel + black pearl (fishing) | 580 / 7 / diamond / 2 | ×1.05, atk spd ×1.05 | **Tidelucky** (any): +1 Luck at sea (fishing link); +10% treasure on rods |
| 22 | Netherite | 4 | netherite ingots | 1600 / 8 / netherite / 3 | ×1.2, ×1.1 | **Fireproof** (any): immune to fire/lava; +20% damage in the Nether |
| 23 | Echo steel | 4 | netherite + echo shards | 1300 / 8.5 / netherite / 3 | ×1.1, ×1.15 | **Hush** (any): your mining and steps make no sculk vibrations; +3 damage on the first hit at a target |
| 24 | Blazebone | 4 | bone + molten emberforged steel (pour-over) | 900 / 7 / netherite / 3.5 | ×0.9, atk spd ×1.15 | **Kindled** (any): consecutive hits within 2 s stack +0.5 damage (max +3) |
| 25 | Voidgold | 4 | gold + ender pearls (great crucible) | 400 / 14 / netherite / 1.5 | ×0.8, ×1.25 | **Warping** (head): broken blocks drop straight into your inventory |
| 26 | Starforged | 4 | netherite + nether star (great crucible) | 2500 / 9 / netherite / 4 | ×1.3, ×1.15 | **Radiant** (any): regains 1 durability every 10 s in sunlight; +1 ability slot |

Trait rules:
- A tool gets each trait from each part. The same trait on two parts stacks: level II is stronger, and level III is the
  maximum.
- Rose gold's slot bonus is capped at 2.
- **Pour-over (cladding):** pouring molten metal over a finished part of another material converts it. Molten
  emberforged steel over bone makes Blazebone; molten iron over a stone head makes an iron-clad head (iron stats,
  stone's trait). It costs the part's own metal value. It's a natural smithing step and comes straight from Tinkers'
  composite casting.

---

## 7. Modifiers (about 50) and slots

Slots: **upgrade (U)** are common, **ability (A)** are rare and change behavior, **defense (D)** are for armor and shields.

Getting more slots:
- **Masterwork** (all parts 5 stars): +1 U.
- **Inscribed** (book & quill): +1 U.
- **Harmonic** (any music disc): +1 U.
- **Trophy-bound** (a boss trophy from the altars): +1 A. This is the boss link: each boss trophy can do this once.
- Rose gold parts: +1 U each, max 2.
- Starforged: +1 A.

Gems and netherite **cost** an upgrade slot (Tinkers 3 rule). Slots are earned through play, not bought with ores.

Incremental modifiers (marked "per") take a total of catalysts per level, a few at a time.

### 7.1 General upgrades (U)
| Modifier | Catalyst (per level) | Max | Effect / level |
|---|---|---|---|
| Reinforced | 3 iron nuggets + 1 obsidian (per 8) | 5 | +12% chance to not lose durability |
| Diamond Inlay | 1 diamond | 1 | +500 durability, +0.5 damage, harvest tier → diamond |
| Emerald Inlay | 1 emerald | 1 | +50% durability, harvest tier → iron |
| Netherite Cladding | 1 netherite ingot | 1 | +20% durability and damage, fireproof, tier → netherite |
| Lodestone | 1 lodestone (once) | 1 | magnet: drops within 5 blocks |
| Fortune's Favor | lapis (per 64), then a black pearl at III | 3 | +1 Luck (Fortune/Looting) |
| Experienced | experience bottles (per 16) | 3 | +50% XP from blocks and mobs |
| Soulbound | echo shard + soul sand + 2 ghast tears | 1 | kept on death; graves skip it |

### 7.2 Harvest upgrades (U)
| Modifier | Catalyst | Max | Effect |
|---|---|---|---|
| Honed | redstone (per 45) | 5 | +3 mining speed |
| Deepcore | 8 deepslate + 1 diamond | 1 | +30% speed on deepslate, tuff and ores |
| Lumberjack | 4 logs + 1 iron axe head | 2 | +40% speed on wood |
| Tiller | 1 hoe blade + 3 seeds | 1 | hoe on any tool (right-click) |
| Sea Glass Lens | 3 sea glass shards (coast) | 1 | no underwater mining penalty |
| Glowing | glow ink sac | 1 | right-click places a light (uses durability) |

### 7.3 Melee upgrades (U)
| Modifier | Catalyst | Max | Effect |
|---|---|---|---|
| Keen Edge | quartz (per 36) | 5 | +1 damage |
| Smiting | glistering melon (per 16) | 5 | +2.5 damage to undead |
| Bane | fermented spider eye (per 16) | 5 | +2.5 to arthropods, slows them |
| Sweeping | iron ingot + feather | 3 | wider sweep, +25% sweep damage |
| Weighted | 1 iron block | 2 | +1 knockback |
| Barbed | cactus + iron nuggets (per 8) | 3 | bleed 1 heart over 3 s per level |
| Venomous | spider eye + pufferfish | 2 | Poison I/II, 4 s |
| Emberstrike | blaze powder (per 25) | 3 | fire for 3 s per level |
| Frostbite | permafrost shard (per 4) | 2 | Slowness I/II, 2 s |
| Lifedrinker | 1 ghast tear | 2 | heal 5% / 10% of damage dealt |
| Featherlight | down feathers (per 16) | 3 | +5% attack speed |

### 7.4 Ranged upgrades (U)
| Modifier | Catalyst | Max | Effect |
|---|---|---|---|
| Power | flint (per 32) | 5 | +10% arrow damage |
| Quickdraw | 1 tripwire hook + string | 3 | +15% draw speed |
| Trueshot | 1 spyglass | 1 | less spread, no sway |
| Piercing | 1 iron arrowhead | 4 | arrows pass through +1 target |
| Heavy Arrows | slimeball | 2 | +1 knockback |

### 7.5 Fishing upgrades (U, rods only; uses the fishing module's hooks)
| Modifier | Catalyst | Max | Effect |
|---|---|---|---|
| Lure Charm | prismarine crystals (per 8) | 3 | -5 s bite wait per level |
| Treasure Sense | heart of the sea | 1 | +treasure chance |
| Braided Line | silk (per 4) | 3 | +20% line strength in fish fights |

### 7.6 Abilities (A)
| Modifier | Catalyst | Max | Effect |
|---|---|---|---|
| Gentle Touch | 4 silk + 1 emerald | 1 | silk touch |
| Smeltforged | blaze rod + furnace | 1 | autosmelt drops |
| Broadstrike | piston + slime block + 2 iron blocks | 2 | +1 area size (1x1 → 3x3 on small tools; 3x3 → 5x5 on broad tools) |
| Veinseeker | compass + redstone block + 2 gold | 1 | mines up to 12 connected ore blocks |
| Treefeller | 4 logs + axe head + iron block | 1 | fells trees (built in on the broad axe) |
| Harvester | hoe blade + 8 wheat + bone block | 1 | 3x3 harvest and replant (built in on the scythe) |
| Dual Wield | 2 daggers' worth of guard metal | 1 | swing an off-hand weapon (daggers have it built in) |
| Recall | echo shard + lead | 1 | thrown spears return |
| Everlasting | nether star | 1 | 1/3 durability use; never breaks below 1 |
| Grapple | 3 leads + tripwire hook | 1 | (crossbow/rod) pulls you to the hooked block |

### 7.7 Armor (D)
| Modifier | Catalyst | Max | Effect |
|---|---|---|---|
| Bulwark | iron blocks (per 2) | 4 | protection |
| Fireward | magma cream (per 8) | 4 | fire protection |
| Blastward | 4 TNT + iron block | 4 | blast protection |
| Arrowward | shield-plate scraps (iron) | 4 | projectile protection |
| Featherstep | down feathers (per 16) | 4 | fall damage reduction |
| Fleetfoot (U) | sugar (per 32) | 3 | +5% move speed (boots) |
| Deepbreath (U) | pufferfish + turtle scute | 3 | breath and underwater sight (helm) |
| Spiked Plates (U) | cactus + iron | 3 | thorns |
| Frostwalker (A) | permafrost shard + packed ice | 1 | walk on water by freezing it (boots) |

### 7.8 Slotless
| Modifier | Catalyst | Effect |
|---|---|---|
| Masterwork | (all parts 5 stars) | +1 U, golden name |
| Inscribed | book & quill | +1 U |
| Harmonic | music disc | +1 U |
| Trophy-bound | boss trophy | +1 A |
| Engraved | name tag | custom name and lore line |
| Etched | dye + gold nugget | tints the tool's metal |

---

## 8. Making it readable for players

- **Recipe notes:** a pinned paper on each station shows the current plan and timer (the Hands-On Delight tickets).
- **The Smith's Almanac:** a guide book with pages written in the mod's guide style. It covers every station, a page per
  material with stats and trait, every tool and part, every modifier, and alloy ratios.
- **JEI:** custom categories show every pipeline (melt, alloy, cast, forge plan, carve, assemble, modifier
  catalysts). The mod already has a JEI plugin.
- **Tooltips:** every part shows its material, stars and trait. A tool lists its parts, then traits, then modifiers
  and free slots. A separate stats tooltip (hold Shift) shows the full numbers.
- **Inspect:** `/inspect` (from the tool-stats module) gains a Smithing tab with each part, its quality, who forged
  it, and the modifier history.

---

## 9. Vanilla tools

Vanilla tools are not removed, so nothing breaks for existing players. Smithed tools are simply better at every tier,
and vanilla iron, diamond and netherite tools can be **melted for their metal** in the crucible. Section 12 asks
whether you want vanilla crafting of iron+ tools turned off later.

---

## 10. Links to the rest of Aftr Hrs

| System | Link |
|---|---|
| Power (fans, coal generator) | An Encased Fan blowing into the hearth works the bellows automatically. The coal generator's heat can pre-warm a crucible beside it. |
| Fishing | Fish oil quench; the modular fishing rod feeds bite time and fight strength; Pearlsteel and Treasure Sense; Braided Line uses silk. |
| Creatures | Silk (bowstrings, Gentle Touch), deer and tufthorn hide (grips), down feathers (fletching, Featherlight, Featherstep). The Emberkit pet keeps the hearth warm. |
| Winter | Permafrost shards for rimesteel, frost brine and Frostbite. |
| Coast | Sea glass for the lens, salt for brine, prismarine for tidebronze. |
| Bosses / altars | Trophies give +1 ability slot (Trophy-bound). Relic weapons stay as they are, and can be honed at the grindstone. |
| Graves | Soulbound tools stay with you. Broken tools still go in the grave. |
| Tool stats | Records the smith, Masterwork, part history and repairs. |
| Area tools | The sledge, excavator, broad axe and scythe reuse the existing 3x3 and lumber logic (and claims checks). |
| Economy / shop | The shop can sell sand molds, hand tools and basic ingots. A smith NPC stall could buy finished tools (later). |
| Quests / advancements | "Forge your first part", "Masterwork", "Alloy every metal", "A Trophy-bound blade", and daily "deliver a 4-star pickaxe". |
| Claims | Station use follows the claims rules (containers). |

---

## 11. Technical plan

- **Data-driven:** materials, parts, tool types, modifiers, traits, alloys, forge plans and catalysts live in JSON
  under `data/aftrhrs/smithing/`. They are reloadable, and JEI and the Almanac read the same data.
- **Items:**
  - One item per tool type (`aftrhrs:smithed_pickaxe`...) and one per part type (`aftrhrs:part_pick_head`...).
  - Material, stars and state (rough/finished/soft) go in data components (`aftrhrs:part`).
  - Tools carry an `aftrhrs:build` component (the parts list plus modifiers, slot use and incremental progress).
  - Stats are computed into vanilla components whenever the build changes, then cached.
- **Textures:**
  - Each part has grayscale base sprites (hand-drawn in the mod's style).
  - Each material has a 5-6 color palette, plus optional overlay details (gem glints, frost, embers).
  - A Python generator (`tools/gen_smithing.py`, like `gen_content.py`) bakes every part × material sprite and the
    item models.
  - Tools render as a composite item model of their parts (a 26.3 composite model with one layer per part), so a rose
    gold pick head on a bone handle looks like exactly that.
- **Blocks:**
  - Station blocks with block entities and renderers: items on surfaces, the liquid surface with a heat glow, workpiece
    glow color by temperature, smoke, steam and sparks.
  - Models are made in Blockbench (headless tools) in the furniture style.
- **Minigames:**
  - The anvil, carving and etching meters are client overlays drawn near the block.
  - The server owns the timing (tick phase plus latency allowance) and validates every strike.
  - Everything works for players without perfect timing: a miss is only a worse star, never a lost item.
- **Automation:**
  - Hoppers feed fuel and metal into the crucible and take ingots from an ingot mold under a spout.
  - The fan bellows.
  - A comparator reads heat (signal = heat / 100).
- **Safety:** no dupes through pours, ladles, pour-over or part swap. Every step moves one stack and checks
  ownership. There's a game test for each.
- **Config (`smithing.yml`):** on/off, Edge on/off, the quality curve, heat speeds, which vanilla recipes stay.
- **Tests:**
  - Game tests for stat math, every alloy, casting and molds, star math and repair.
  - Persistence round trips for every component, and modifier slot accounting.
  - Grind-off and crystals, broken tools, AoE with claims.
  - Client checks with DevScript screenshots of every station.

---

## 12. Build phases

1. **Phase 1, the core loop (the first release):**
   - Forge, bellows, crucible, ladle, casting bench, molds, anvil (minigame), quench trough, carving bench and
     assembly bench.
   - Pickaxe, hatchet, shovel, hoe, sword, dagger and smithing hammer, with their parts.
   - 14 materials (tiers 1-2 plus steel and rose gold), traits, quality and repair.
   - Textures, the Almanac's first chapters, JEI, and tests.
2. **Phase 2, depth:**
   - Grindstone and Edge; the Runesmith table with about 35 modifiers and slots.
   - Broad tools (sledge, excavator, broad axe, scythe, greatsword, mattock, mace, spear).
   - Tier-3 alloys, pour-over, and the links to power, fishing and winter.
3. **Phase 3, ranged and armor:**
   - Bows, crossbow, arrows, fishing rod, shield and armor.
   - The remaining modifiers, the Great Crucible, tier 4, the boss trophy slots, quests and advancements.

---

## 13. Questions for you

1. **Vanilla tools:** keep crafting vanilla iron/diamond/netherite tools alongside, or turn those recipes off once
   smithing is live?
2. **Minigame:** is a timing-based strike meter right, or do you want something gentler (a set number of strikes with
   no timing)?
3. **Scope of the first release:** ship Phase 1 on its own, or wait for Phases 1 and 2 together?
4. **Name:** "Anvil & Ember" for the update and "Smithing" in menus, or something else?
