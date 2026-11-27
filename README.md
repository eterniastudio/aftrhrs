# Aftr Hrs

**Aftr Hrs is a custom mod made for one private Minecraft server, the Aftr Hrs server.** It is not a general-purpose
mod and is not meant to be dropped into other modpacks or servers. This repository has its full source code, everything
needed to build it, and the [release downloads](../../releases) that players' games update to automatically.

The whole server is one Fabric mod: Feather's systems (claims, homes, pets, quests, the bank, storage networks, fishing,
farming and more), the Arcana creatures, every custom item and piece of furniture, and the client screens and HUD that go
with them. The same jar runs on the server and on every player's game, so the two always match.

## What's in the mod

The mod began as a port of the server's old Paper stack (the Feather plugin, MythicMobs + ModelEngine creatures, Oraxen
items and the Aftr Hrs Companion client mod) into a single Fabric 26.3 mod. With the code in one place, the creatures
and furniture could become native features instead of plugin workarounds.

At a glance: **~50 server modules**, **96 creatures** (50 pets, 5 mounts, 6 bosses and their forms, livestock, wildlife,
hostiles and undead), **~1,750 custom items, blocks and furniture pieces**, **97 fish**, **31 custom enchantments**,
**14 smithing metals** and native client screens for all of it.

### Server modules

Each one can be turned on or off under `modules:` in `aftrhrs/config.yml`. Every setting lives in a YAML file that
ships in the jar (`src/main/resources/feather/*.yml`) and is copied to `aftrhrs/` on the server, where it can be edited.

| Module | What it does |
|---|---|
| **Teleport requests** | `/tpa` (a player-head picker with no name), `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpacancel`, `/tptoggle`; clickable request cards and a warm-up that cancels if you move or get hurt |
| **Homes** | `/home`, `/homes` (menu), `/sethome`, `/delhome`, with per-rank home limits |
| **Spawn, warps and `/back`** | `/spawn`, `/warp` (menu), `/back`, the starter kit, first-join spawn, bedless respawn at spawn |
| **Random teleport, death points** | `/rtp` (`/wild`) to a random safe spot outside claims; `/deathpoint` lists your last deaths with an arrow to track one back |
| **Nicknames** | `/nick` with an in-game editor, gradients and color codes |
| **Chat** | Rank prefixes and per-rank formats, clickable links, @mentions with pings, join/quit/first-join messages; **chat share**: `[item]`, `[inv]` and `[ec]` show your held item, inventory or ender chest |
| **Messaging** | `/msg`, `/r`, `/msgtoggle`, `/socialspy`, `/realname`, `/seen` |
| **Chat games** | Timed trivia, word scrambles, phrases and creature-name rounds with coin rewards |
| **Tab list, nametags, MOTD** | Animated header/footer, rank-sorted names with nickname and AFK tag, health next to names; nametags with nickname, gradients, prefix and health; the server-list MOTD |
| **AFK, sit, carry** | AFK detection and tags, sit on stairs and slabs, sneak + right-click to lift and carry players (they can opt out) |
| **Hats, cosmetics, trails** | `/hat`, 30 wearable hats in three rarities, cosmetics, flight trails |
| **Hub** | `/feather` (`/menu`) or Shift+F: homes, warps, friends, pets, the taming guide, community, style and settings in one place, with a greeting that follows the time, weather and moon |
| **Claims** | Claim whole chunks, rectangles or circles with the claim tool; touching pieces join one claim with one trust list and one set of flags. Claim chunks are earned by playing; `/claim show` draws the borders |
| **Bank and shop** | Goob Coins: bank chests to buy coins with diamonds, sell, deposit (with daily interest) and withdraw, `/balance`, `/pay`, `/baltop`, bank robberies with minigames; the server shop |
| **Trade and mail** | A safe two-player trade screen; `/mail` letters to anyone who has played, and `/mail gift` item gifts picked up at the spawn mailbox |
| **Bounties** | `/bounty <player>`: put reward items on a player's head; several players can stack rewards, and the tab list shows who is wanted |
| **Quests** | Daily and weekly quests from a pool, server-wide goals, a notice-board menu |
| **Calendar** | A daily login reward on a 7-day streak (with grace days), playtime rewards for today and lifetime milestones (`/calendar`) |
| **Civic** | Town furniture that does something: notice boards (quests), warp kiosks, stable posts (pets), trash bins, fish scales (fishing records), working fountains |
| **Graves** | Your items and XP go into a grave where you died, locked to you for a while before they spill |
| **Chest locks** | Lock containers to yourself or trusted players |
| **Holograms** | `/holo`: floating text, items, heads, blocks and mobs with pages, click actions and placeholders; also damage and heal numbers |
| **Advancements** | A custom advancement tree for the server's content |
| **Tool stats** | Tools, weapons and armor remember who made them and count blocks broken, kills, damage, fish caught, distance and more |
| **Custom recipes** | Recipes for every custom item, shown in JEI when it is installed |
| **Staff commands** | `/gmc /gms /gma /gmsp`, `/god`, `/heal`, `/feed`, `/smite`, `/spawnmob`, `/i`, `/itemname`, `/fly`, `/speed`, `/vanish`, `/invsee`, `/ec`, `/top`, `/tphere`, `/broadcast`, `/craft` |
| **Moderation** | `/mute` (timed or permanent), `/ignore`, `/clearchat`, `/mutechat`, `/freeze` |
| **Utilities** | `/ping`, `/near`, `/list`, `/playtime`, `/whois`, `/trash`, `/skull`, `/repair`, virtual workstations (`/anvil`, `/grindstone`, `/stonecutter`, `/loom`, `/cartography`, `/smithing`), time and weather commands, info commands like `/rules` and `/discord`, rotating announcements |
| **Analytics** | Per-player counters (blocks, playtime, kills, distance, fishing) and daily server stats (unique and new players, peak online, session lengths) for the staff |
| **Auth gate** | The server has no whitelist: a new player types the server code in chat once to get in (stored only as a salted hash, never logged) |
| **Christmas** | A decoratable fir tree, gift boxes, mistletoe and a full set of decorations |
| **Chess** | Right-click a chess table to play chess on the mod's own chess screen |

### Creatures

Every creature is a GeckoLib entity with its own model, textures, sounds and animation states (idle, walk, run, sit,
sleep, eat, attack and more).

- **Pets (50)**, tamed with each species' own method from the **taming guide** (`/tamingguide`): dogs (beagle, corgi,
  dachshund, golden retriever, husky and copper husky, Saint Bernard), cats (black cat, calico kitten, Maine
  Coon, tabby, Kitty, Luna and Luna Two, Olive, Ozzy), penguins (emperor and rockhopper, with chicks) and the Arcana
  creatures: barkling, bubblefin, burrowpip, cairnling, canopip, chorusling, Clover, cloudlamb, driftglow, emberkit,
  floepup, Frog Guy, puddlepup, fumetail, glimmermoth, honeycub, hushwing, Kevin, magpip, mossbun, Pockie, ripplekit, runeback
  tortoise, shellpip, shulkling, skyfin ray, snowpelt owlet, soulmander, sporeling and wickling.
  `/pets` manages them: a pet cap, **bonds** that grow as you play together and unlock **bond perks**, a **stable** to
  park pets, **plushies** of each pet (57 to collect and place) and the **creature codex**. Some pets help with work,
  such as the ripplekit when fishing and the canopip on farms.
- **Mounts (5)**: cinderwing phoenix, tempest roc, dunebun, snowmuff, tidemane.
- **Bosses (6)**: Wukong (with his clones), the glacial wyrm, the runebound colossus, the cinderwing phoenix and the
  tempest roc. They are summoned at **altars** with boss items, have ground effects that copy the real blocks (quakes,
  fissures, pillars), a **boss board** for the fight, and drop trophies and **relics**.
- **Livestock (7, with young)**: bristleboar, lanternsnail, loomworm, pondwaddle, shroomwallow, tufthorn, wartgrazer.
  They can't be tamed but can be bred (16 per 3×3 chunks) and harvested on cooldowns, worked with the **butter churn**,
  **cheese press**, **silk frame**, **nesting box** and **feeding trough**.
- **Wildlife (5)**: inkcurl, kelpback, squallgull, velvetstag, voidpuff; plus duskflies.
- **Hostiles and undead (14)**: cinder imp, geode crawler, lurefin, rime wraith, volt wisp; bilgebones, dustdigger,
  gloomweaver, hollow pilgrim, mirewalker, reedwader, rimeshambler, sandshroud, vinebound. Some come out on the
  **Restless Moon**.
- **`/bestiary`** pays coins for discovering and hunting each creature.

### Fishing

97 fish across rivers, lakes, oceans and biomes, with a fight minigame when one bites, **hotspots** in the water,
three rods (driftwood, coral, abyssal) and four baits, fishing enchantments, a **fish journal**, a **records board**,
trophy plaques and display tanks, crab pots, message-in-a-bottle finds, map scraps, the Prospector's Pan, and
fishing **tournaments**.

### Farming and cooking

Custom crops (rice, tomato, grape, pepper, blueberry, tea, corn and more) with crop **quality**, a watering can, compost
and compost bins, seaweed farming, seeds from grass, planters, a farmhand that works nearby crops, cooking and smoking recipes,
**meal buffs** and a harvest **fair**.

### Smithing

A full forge: the **forge hearth** and **bellows** heat metal, the **crucible** and **great crucible** melt it, the
**smithing spout** and **casting bench** pour parts, the **smithing anvil** shapes them, the **quench trough** hardens
them, the **whetstone wheel** puts an edge on, the **carving** and **assembly benches** put tools together and the
**runesmith table** adds runes. 14 metals (copper, iron, gold, rose gold, steel, chime bronze, tidebronze, pearlsteel,
rimesteel, echo steel, emberforged steel, obsidian, netherite, starforged), each with its own tool and armor look.
Hot pieces carried loose (not in tongs) burn.

### Enchantments and relics

31 custom enchantments: bountiful, cleave, culling, deflection, driftwalker, giantslayer, harpoon, heavy haul,
insight, leeching, marking, molten touch, nightsight, pack bond, pocketing, prospector, replanting, rimebite, second
wind, seeker, soulbound, springstep, steadfast, steady line, timberfall, treasure hunter, twin hook, vitality, volley,
witching hour, plus the fragility and weight curses.

Relics are boss-drop weapons with abilities: Cinderplume, Dragonfire, Frostfang, Galeheart, Runebreaker, Ruyi,
Tidecaller and Witherbane.

### Tools and gear

Hammers and excavators (area mining), lumber axes (tree felling), scythes (area harvesting) in copper, iron, gold,
diamond and netherite; the builder's wand, the magnet charm, backpacks, the XP flask and the pet whistle.

### Items, blocks and furniture

About 1,750 custom items, building blocks and furniture pieces. Furniture is made of real blocks: you can sit on chairs
and benches, open cabinets and wardrobes, toggle lamps, and much of it has several states.

| Set | Pieces | Examples |
|---|---|---|
| Woodwork | 208 | chairs, tables, counters, cabinets, wardrobes and benches in every wood |
| Stonework | 182 | columns, hearths, garden benches, wishing wells in every stone |
| Outdoor | 147 | sun loungers, BBQ grills, bird feeders, birdhouses, clotheslines |
| Fishing | 122 | rods, bait, fish, trophies, tanks |
| Fabric | 119 | sofas, curtains, rugs, floor cushions in every color |
| Curios | 96 | statues, pedestals, hat stands, candle sconces, goblets and jars you can fill, flower boxes, doormats |
| Bathroom | 86 | bathtubs (fillable), shelves, scales, towels, bathrobes |
| Kitchen | 81 | fridges, bread boxes, cake stands, cutting boards, egg baskets |
| Coastal | 71 | aquarium tanks, buoys, beach towels, treasure chests |
| Civic | 70 | market stalls, fountains, gazebos, notice boards, warp kiosks |
| Wildlife | 64 | the churn, cheese press, feeding trough, antler mounts, down pillows, stews |
| Crops | 62 | every crop stage, compost |
| Plushies | 57 | a plushie of each pet |
| Arches | 56 | round, pointed and wide arches and keystones in every stone |
| Christmas | 52 | wreaths, baubles, nutcrackers, presents, stockings |
| Tropical | 49 | palms, coconut drinks, fruit crates, parrot perches |
| Copperworks | 20 | copper pipes, street lamps and wall lights, plain and weathered |
| Aquatic, lighting, decor, blocks | 54 | coral bricks, driftwood, chandeliers, lanterns, grandfather clocks, globes, tiles |
| Bosses, utility, furniture | 31 | altars, trophies, the ender cabinet, personal lockers, gramophones, planters |

Working furniture and gadgets:

- **Storage networks**: storage vaults, inventory connectors (link chests and barrels), a **storage terminal** with
  search and a **crafting terminal** that crafts straight from the network.
- **Power**: the coal generator runs **encased fans**, which push mobs, players and items, and blow items through lava,
  fire or campfires to blast, smoke or haunt them (items in the stream never burn).
- **Gadgets**: bamboo spikes, the feather block (brakes your fall), sacks, fillable goblets and jars, critter cages that
  hold small mobs, ropes.
- **Fairy lights**: string lights hung between hooks, in many bulb shapes and colors.
- **Curios**: rotating item displays, pedestals, hat stands, statues, observer-pulsed displays.
- **Games and music**: the chess table and the gramophone.

### Client

Native screens instead of chest and dialog workarounds: the hub, the HUD (pet status, boss bar, quests, notices), the
codex, pets, claims, trade, smithing, fishing journal and storage screens, a recipe viewer and JEI integration. All
key binds are under Options → Controls → Key Binds → Aftr Hrs; display options under **HUD & Display**.

### Under the hood

- **Migration**: on first boot, the mod imports the old Paper server's Feather data (homes, warps, pets, claims,
  balances and more) and converts old furniture and storage.
- **Updater**: signed self-updates for clients and opted-in servers (see below).
- **Game tests**: 400+ Fabric game tests and a soak/persistence driver in `src/gametest`, never shipped.

## Repository layout

```
build.gradle, settings.gradle, gradle.properties   Gradle build (Fabric Loom); versions in gradle.properties
gradlew, gradlew.bat, gradle/wrapper/              Gradle wrapper: no Gradle install needed
src/main/        common code (runs on server and client), mixins, resources, assets, data
src/client/      client-only code: screens, HUD, rendering
src/gametest/    a dev-only test mod (Fabric game tests and soak driver); never part of the release jar
content/         item, furniture, recipe and pack sources the generators turn into assets
tools/           Python generators and checkers for models, textures, animations, sounds and content
release.py       builds, signs and publishes a release (needs the private release key)
*.md             design notes: porting guide, animation, smithing, parity, bosses
```

## Building

Needs **JDK 25** (Gradle downloads one automatically if none is found) and an internet connection for the first build
(Minecraft, Fabric and the libraries are downloaded and cached).

```
./gradlew build          # Windows: gradlew.bat build
```

The jar is `build/libs/aftrhrs-<version>.jar`. The version comes from `gradle.properties`.

Other tasks:

| Command | What it does |
|---|---|
| `./gradlew runClient` | dev client (add `-Pjoin=localhost:25565` to join a server on start, `-PwithJei` to add JEI) |
| `./gradlew runServer` | dev server in `run-server/` |
| `./gradlew runGametest` | headless server that runs every game test and exits; report in `build/gametest-report.xml` |
| `./gradlew runSoak` | dev server with the soak/persistence driver |

The Python tools in `tools/` are only needed to regenerate assets. Their outputs are already committed, so building
the mod doesn't need them. Some of them read the original art sources, which are not in this repository.

## Releases

`python release.py --notes "..."` builds the jar, writes `manifest.json`, signs it with the Aftr Hrs release key and
publishes the release here. The private key is not in this repository, and installs reject any manifest it didn't sign.
A build from this source works, but it can't publish updates that existing installs accept.

## Install

Minecraft **26.3** with **Fabric Loader 0.20.1** or newer (use the Fabric installer, or a Fabric profile in your
launcher). Put these into your `mods` folder:

| File | Where |
|---|---|
| `aftrhrs-<version>.jar` | the [latest release](../../releases/latest) |
| Fabric API | [Modrinth](https://modrinth.com/mod/fabric-api) (the version the release page lists) |
| GeckoLib for Fabric 26.3 | [Modrinth](https://modrinth.com/mod/geckolib) (the version the release page lists) |

**Coming from the Aftr Hrs Companion?** Remove the old Companion jar (`aftrhrs-fabric-<version>.jar`) from your
`mods` folder (in some launchers, such as NoRisk, that is `custom_mods`). It uses the same mod id, so with both
installed the game won't start. The Companion's features are part of this mod now.

JEI is optional: the mod adds the server's recipes to it. Every key can be changed under Options → Controls → Key
Binds → Aftr Hrs.

## Automatic updates

Once installed, the mod keeps itself up to date, along with the GeckoLib and Fabric API versions it needs. It checks
this repository's latest release when the game starts and every few hours after. A newer version is downloaded in the
background; you get a small notice ("Update 1.0.1 downloaded") and it is put in place when you close the game, so
the next start runs it. Change it under **HUD & Display → Updates**: *Automatic*, *Notify only* or *Off* (with *Off*
the mod never contacts GitHub on its own).

If you join the server with a different version, you are told which one it runs ("This server runs Aftr Hrs 1.0.1 —
restart to update") instead of a confusing error, and the update starts downloading right away.

Updates are safe to trust:

- Every release has a `manifest.json` listing each jar's mod id, version, SHA-256 and size, and `manifest.json.sig`,
  an Ed25519 signature over that file. The mod only accepts a manifest signed by the Aftr Hrs release key, whose
  public half is built into the mod: `pTOmhfKsBFTF7NDz1kFMtW6ssXf3aa7MAhUObr+OaHU=`
- The mod jar comes only from this repository's releases, the dependencies from Modrinth's CDN (or GitHub), and each file is
  checked against the signed size and SHA-256 before it is used.
- It only ever moves to a higher version, and only touches its own jar and older copies of GeckoLib and Fabric API in
  the folder the mod was loaded from. Your other mods are never changed. A dependency your launcher manages in another
  folder is left alone.
- Nothing changes while the game runs: the new jars are swapped in only after all of them are downloaded and checked,
  so the mods folder never ends up with two copies or a half-written file. The jars it replaced are kept in
  `config/aftrhrs/updates/rollback/` (with a `ROLLBACK.txt` saying how to put them back).

If your launcher manages mod versions for you, you can set updates to *Off* and let the launcher handle it.

## Servers

Server owners use the same jar with the same Fabric API and GeckoLib. A dedicated server doesn't update itself unless
you ask it to: set `updater.server: stage` in `aftrhrs/config.yml` to download new releases in the background and
install them at the next restart, or run `feather update stage` from the console when you want to. `feather update`
shows what the updater knows.

## License

All rights reserved; see [LICENSE](LICENSE). The source is published so players and server staff can see what the mod
does and how it is built, not for reuse elsewhere.
