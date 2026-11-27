# Aftr Hrs mod: porting guide

The Aftr Hrs server (Paper + Feather + MythicMobs/ModelEngine + Oraxen + the Companion client mod) becomes **one
Fabric 26.3 mod**, `mod/` in this repo (branch `fabric-mod`). Target: a 1:1 copy of the live server's behavior, then
deeper polish now that we own the code (GeckoLib creatures with more animation states, furniture as real blocks with
state toggles, native screens instead of chest/dialog workarounds).

## Build & run

- JDK: `JAVA_HOME=C:/Users/kevin/.jdks/temurin-25.0.3`. Build: `cd mod && ./gradlew build` (jar in `build/libs`).
- Dev server: `./gradlew runServer` (run dir `run-server/`, port 25690, offline mode, flat world). Pipe `stop` to stdin for a boot test:
  `(echo stop | ./gradlew runServer --console=plain) > /tmp/srv.log 2>&1`.
- Dev client: `./gradlew runClient [-Pjoin=localhost:25690]`. Never drive the client window while the user is at the desktop.
- Reference sources (gitignored): `../.ref/mc/` = decompiled Minecraft 26.3 (Mojang names; grep it for APIs),
  `../.ref/geckolib/` = GeckoLib 5.5.7 classes (`com.geckolib.*`; use `javap -cp ../.ref/geckolib <class>`).
  Fabric API jars are in `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`.
- MC 26.3 is unobfuscated: Mojang names everywhere. `Identifier` (not ResourceLocation). Java 25.

## Where the old code lives (read-only sources to port from)

| Old | Path |
|---|---|
| Feather plugin (Bukkit) | `../src/net/axther/feather/**` (+ `../res/*.yml`, docs in `../docs/`) |
| Companion client mod | `../client/common/src/main/java/net/axther/aftrhrs/**` |
| Creature specs (source of truth for every Arcana mob) | `C:/Users/kevin/Desktop/plugins/MythicMobs/packs/Arcana/tools/specs/*.json` + `*.skills.yml`, `tools/wild_specs/`, generators `tools/gen_mm.py`, `tools/gen_wild.py`; generated MythicMobs output in `../mobs`, `../skills` of that pack |
| Creature models (ModelEngine bbmodels) | `C:/Users/kevin/Desktop/plugins/MythicMobs/packs/Arcana/models/*.bbmodel` |
| Oraxen items/furniture | `C:/Users/kevin/Desktop/plugins/Oraxen/items/*.yml`, pack `C:/Users/kevin/Desktop/plugins/Oraxen/pack/` |
| Art sources/generators | `C:/Users/kevin/Documents/Blockbench/models/arcana_items/**` |

Do not edit anything outside `mod/` (and never touch the live server).

## Package layout (`mod/src/main/java/net/axther/aftrhrs/`)

- `AftrHrs` (initializer), `Modules` (the module list; one `feather.register(...)` line per module, Feather's start order).
- `core/` — the platform every port uses:
  - `Feather` — data folder `<server>/aftrhrs/` (same layout as `plugins/Feather/`, so the live folder copies over as-is),
    `getConfig()`, `text()`, `loadResourceConfig(name)` (bundled defaults in `src/main/resources/feather/`),
    `dataFile(name)` = `aftrhrs/data/<name>`, `module(Class)`, `bind(cmd, handler)`, `player(uuid)`, `onlinePlayers()`,
    static `Feather.get()`, `Feather.server()`.
  - `FeatherModule` — `name/enable/reload/disable` like before.
  - `Yaml` — drop-in for Bukkit `YamlConfiguration`/`ConfigurationSection` (same getters, dotted paths, sections,
    `getKeys(false)`, `set`, `save(Path)`, `Yaml.load(Path)`). **Keep every data file's format identical** so old data loads.
  - `Text` — messages.yml MiniMessage with palette tags; same API (`msg`, `send`, `actionBar`, `button`, `sound`, `unparsed`).
  - `Adv` — Adventure ⇄ vanilla: `Adv.vanilla(Component)`, `Adv.of(ServerPlayer)` (an Adventure `Audience`), `Adv.sound(...)`.
    Keep using Adventure `Component`/MiniMessage in ported code; convert at the vanilla boundary.
  - `Tasks` — `later(ticks, r)`, `timer(delay, period, r)`, `async(...)`, `sync(r)`; returns `Task.cancel()`.
  - `Perms` — `Perms.has(player|source, "feather.x")` with plugin.yml defaults (LuckPerms-Fabric answers first).
  - `cmd/` — `FeatherCommand` (`onCommand(Sender, label, args)`, `onTabComplete`), `Sender`, `CommandBridge`. Commands,
    aliases and permissions come from `src/main/resources/feather/plugin.yml` (Feather's table); add new ones there.
  - `Pdc` — PersistentDataContainer replacement: a CompoundTag attachment on entities / block entities / chunks /
    levels (`Pdc.getString(entity, "feather:key")` ...) plus item `custom_data` helpers. `Pdc.LEGACY` holds Paper's
    old BukkitValues for migration.
- `feature/<module>/` — ported Feather modules (one package per module/group: `feature.homes`, `feature.pets`, ...).
- `content/` — native items, blocks, furniture (ex-Oraxen), block entities, creative tabs.
- `entity/` — GeckoLib creatures (ex-MythicMobs + ModelEngine): base classes, species data, abilities, AI.
- `net/` — payloads. The Companion's JSON protocol (`aftrhrs:api`) is kept so the client code ports nearly unchanged.
- `migrate/` — importers from the Paper world/data (Feather data, MythicMobs pets, Oraxen furniture + items).
- Client code: `mod/src/client/java/net/axther/aftrhrs/client/**` (split source set; only client code may touch
  `net.minecraft.client`). Client mixins go in `aftrhrs.client.mixins.json`, common ones in `aftrhrs.mixins.json`.

## Porting rules

1. **Same behavior, same config keys, same messages, same data files.** Port logic faithfully; then improve where a
   Bukkit limitation forced a workaround (e.g. barrier+ItemDisplay furniture → a real block; chest-GUI → real screen).
2. Bukkit → Mojang: `Player`→`ServerPlayer`, `Location`→`ServerLevel`+`Vec3`/`BlockPos`, `World`→`ServerLevel`
   (key = `level.dimension().identifier()`), `ItemStack`→`net.minecraft.world.item.ItemStack`, `Material`→`Items.X`/`Blocks.X`,
   events → Fabric API callbacks (`ServerPlayConnectionEvents`, `UseBlockCallback`, `UseItemCallback`,
   `UseEntityCallback`, `AttackEntityCallback`, `PlayerBlockBreakEvents`, `ServerLivingEntityEvents`,
   `ServerEntityEvents`, `ServerTickEvents`, `ServerMessageEvents`, `ServerPlayerEvents`, `EntitySleepEvents`...).
   No callback? Add a small mixin + an event in `core/event/FeatherEvents.java` (one shared place; keep hooks generic).
3. World names in old data: Paper stored `world`, `world_nether`, `world_the_end` plus world UUIDs. Map them with
   `core/Worlds.resolve(name|key|uuid)` (overworld/nether/end + `namespace:path` keys).
4. Every module keeps working for **vanilla-UI fallbacks** only where cheap; the mod is now required on clients, so
   prefer real screens/payloads over chest GUIs when it makes the experience better.
5. No `System.out`; log with `Feather.get().getLogger()` / `AftrHrs.LOG`. American spelling. Player-facing name of the
   server is exactly **Aftr Hrs**.
6. Build must stay green: run `./gradlew build` before you finish, and a boot test of the dev server if you touched
   registration, mixins or module enable code.

## Migration from the Paper server (`migrate/`)

Moving the live server: stop Paper, put the Fabric server + this mod next to the old files, keep the world folder and
copy the old `plugins/` folder next to the Fabric server (only `plugins/Feather/` is read). First start:

1. `PaperWorldFix` (mod init, before the world opens): Paper 26.x keeps the seed/dimension settings, game rules,
   weather etc. inside `dimensions/minecraft/overworld/data/minecraft/`; they are copied to `<world>/data/minecraft/`
   (vanilla's place) so the seed survives. Pre-26 `world_nether`/`world_the_end` layouts are copied in too.
2. `FeatherImport` (server starting, before Feather): copies `plugins/Feather/**` into `aftrhrs/` once (same layout).
3. Entities: `EntityLoadMixin` keeps Paper's `BukkitValues` in the `Pdc.LEGACY` attachment; `EntityTypeMixin` makes a
   MythicMobs mob (`BukkitValues."mythicmobs:type" = ArcanaBlackCat`) load as the native type (`Legacy.mythicType`:
   explicit `Legacy.mapMythic(id, type)` or the convention `ArcanaBlackCat → aftrhrs:black_cat`), same UUID, position,
   health. On `ENTITY_LOAD` the legacy tag is merged into `Pdc` (same key names, e.g. `feather:pet`,
   `mythicmobs:owner`, `mythicmobs:variables` = JSON with `arcana_bond`, `stay`, `petname`, `arcana_namecolor`...) and
   every `Legacy.converter(...)` is offered the entity once. **Module ports register converters** for their old
   entities: pets adopt records (clear NoAI/Silent/invisibility that MythicMobs/ModelEngine set!), Oraxen furniture
   ItemDisplays (`oraxen:furniture` style keys) become blocks and their barrier/light blocks + Interaction entities
   are removed, Feather holograms/nametags/seats/displays are re-created or removed.
4. Block entities: `PublicBukkitValues` → `Pdc` on the block entity. Chunks: `ChunkBukkitValues` → `Pdc` on the chunk.
5. Items: every ItemStack/ItemStackTemplate built from saved data with `custom_data.PublicBukkitValues` converts
   (`LegacyItems`): `oraxen:id` → the native item `aftrhrs:<id>` (use `LegacyItems.rename` if an id changed), Oraxen's
   model/name/lore components dropped, other Paper item keys moved to the top level of `custom_data` (same names).
   Works for inventories, ender chests, containers, shulkers, bundles, dropped items, mail.

Test world: `%TEMP%/feather/companion263/world` (Paper 26.3; a tamed Black Cat "Midnight" at -899.5 -60 -899.5,
pets.yml records) — copy it into `mod/run-server/world` (skip `session.lock`) plus its `plugins/Feather` into
`mod/run-server/plugins/`. `mod/tools/nbtdump.py` dumps region/entity NBT.

## Companion protocol inside the mod

The old Companion client mod now lives in the client source set (`mod/src/client/java/net/axther/aftrhrs/client/**`,
package `net.axther.aftrhrs.client.*`: `claims`, `hud`, `menu`, `pets`, `places`, `storage`, `fish`, `entity`,
`recipes`, `compat/jei`, `update`, `net`, `mixin`). The `Platform` service and the NeoForge/Forge/REI
layers are gone; `client/Companion.init()` (called from `AftrHrsClient`) wires everything into Fabric directly
(receiver, join/leave, client tick, key mappings, the `CreatureView` PIP renderer). The old server-item creative tabs
(`aftrhrs:server_*`, `ServerItems`, Feather's `ItemExport`/`OraxenCatalog`, the hello's `items` hash) are gone: every
item is registered content with its own `ArcanaContent` tabs. The `creative` feature key is still accepted by the
server (older clients) but unused. Client mixins: `aftrhrs.client.mixins.json`. Sprites/shaders: `src/client/resources/assets/aftrhrs/`.
Client strings: `src/main/resources/assets/aftrhrs/lang/en_us.client.json` (to be merged into `en_us.json`).
`DevScript` works as before (`JAVA_TOOL_OPTIONS=-Daftrhrs.devScript=@file`, see `client/README.md` for the steps).

**Wire format is unchanged** (docs/companion-protocol.md): `aftrhrs:api`, one flag byte (0 JSON, 1 gzip JSON) +
body, big server messages as `part` messages, items as `{"id","n"}` or `{"b": base64 gzipped NBT}`. The payload
(`net/ApiPayload`) is registered for both directions by `net.axther.aftrhrs.net.Companion.init(feather)` in
`AftrHrs.onInitialize` (also in singleplayer: the integrated server answers the hello too).

**Server endpoint: `net.axther.aftrhrs.net.Companion`** (port of Feather's `CompanionModule` transport + session).
The handshake still runs: a player is linked only after a valid `hello`, and the server agrees only to features that
`companion.features.<key>` leaves on (`hub` follows `menus`, `craftfill` follows `recipes`); with `modules.companion`
off no hello is answered and every feature falls back. All static, server thread:

```java
Companion.on("pets_request", "pets", (player, msg) -> { ... });  // client message, only from players with "pets"
Companion.on("open_hub", (player, msg) -> { ... });              // any linked client
if (Companion.has(player, "pets")) Companion.send(player, "pets", body);   // push (sets body "t")
Companion.send(player, Companion.message("welcome"));            // or a full message with "t"
List<byte[]> enc = Companion.encode(big); Companion.sendEncoded(p, "recipes", enc);   // encode once, send to many
Companion.onWelcome((player, hello) -> ...);   // after the welcome; hello carries the client's "recipes"/"items" hashes
Companion.onQuit(player -> ...);               // a linked player left: end their sessions
Companion.moddedWith("claims");                // online players with a feature
Companion.addFeature("newthing", "newthing");  // a feature beyond Feather's list (add it to client Link.FEATURES too)
Companion.gate(player -> authPending(player)); // the auth port: hold everything but the handshake
```

Handlers must validate every field (`Wire.str/num/bool`) and re-check permissions/state; client messages are
requests (40/s rate limit, 64 KiB max, no parts). Value helpers: `Wire.item(ItemStack)`, `Wire.item(Item)`,
`Wire.text(adventure Component)`, `Wire.text(vanilla Component)`.

**Recipes for JEI** (`net/RecipeExport`): a dedicated 26.3 server doesn't send its recipe list, so on each welcome
with `recipes` the server sends every `RecipeManager` recipe (shaped, shapeless, transmute, cooking, stonecutting,
smithing transform; vanilla, the mod's furniture/item recipes, datapacks, `/recipes` custom ones) in the old wire
format, or `recipes_ok` when the hello's `recipes` hash matches the client's cache. Built at server start, rebuilt
after a datapack reload or `feather.recipesUpdated()` (`Hooks.RecipesChanged`, provided while `modules.companion`
runs) and pushed when the hash moved. Workstation recipes come from `RecipeExport.source(key, sink -> sink.machine(...))`
(farming registers its cooking meals as `feather:cooking`; a source exports nothing while its module is off). JEI's
`sync` skips ids it already has, so singleplayer shows nothing twice.

**One mod, not "plugin + optional client".** Every client has these screens, so a feature the config leaves on is the
default for everyone; `companion.features.<key>` off (or `modules.companion` off) is the only way to get the vanilla
dialog / chest fallback. The handshake stays (proto check; a client with another mod version links anyway and the
server logs a note). Player-facing text never says "install the Companion"; the outdated-mod nudge
(`companion.latest-mod`, `mod-download`, `messages.yml companion.outdated*`) is gone (config keys kept, unread).
`net/MenuBridge` (port of Feather's `MenuBridge`): installed as `Dialogs.MenuUpgrader` while the companion module runs,
every Feather dialog goes out as `menu` (hub layout with group tabs for `hub`), clicks come back as `menu_click` /
`menu_closed` (one use, 10 minutes, values through `Dialogs.Response.of`). It also answers `open_hub` (the H key, same
checks as Shift+F) and `open_settings`, and adds the Settings page's "HUD & Display" row (`Hub.SETTINGS_HUD`, feature
`hud`, sends `hud_settings`).

**Look-at card** (`inspect` feature; client `client/hud/InspectHud`): `net.axther.aftrhrs.net.Inspect` (port of
Feather's `InspectBridge` + `InspectSource`) answers the client's `inspect` (`x,y,z`; `e` = entity UUID is still read)
with `inspect_info` (`key`, `ttl`, `kind`, `title`, `icon`, `rows`, or `none`). It checks first: 200 ms per player,
loaded chunk, a candidate block (an `ArcanaCropBlock`, farmland under one = the crop, or any furniture block part),
`blockInteractionRange() + 1`, a ray from the eye to the block's outline, not spectating. Modules register a source in
`enable()` and drop it in `disable()`:

```java
Inspect.register("farming", (player, level, pos, state) -> card-or-null);   // null = not mine, Inspect.none() = mine, show nothing
Inspect.unregister("farming");
JsonObject card = Inspect.card("crop", title, icon);   // rows: Inspect.bar / text(c, color, glyph) / count / timer
```

Sources check claims themselves (as the click does, silently) and must not change anything a click wouldn't.
Registered: farming (crops: stage, watered, why not growing; no claim check, like the status click), fishing (crab
pots: catch-up rolls, held count or next catch; container trust), wildlife (churn, cheese press, silk frame, nesting
box, trough; container trust). Messages: `farming.inspect-stage`/`status-*`, `fishing.crabpot.inspect-*`,
wildlife.yml `station.inspect-*` (unchanged keys).

**Auto-updater**: on for the whole mod (clients, and dedicated servers that opt in); see "Releases & auto-update"
below. `client/update/Updater` is only the client's settings and toasts; the engine is the common `update/` package.

**Creatures in menus** (`client/entity/CreatureView` + `CreatureRenderer`): the pet screen (roster cards, stage,
stable stalls, codex grid and entry) and the pet HUD draw the mod's own GeckoLib creatures: a client-only instance of
the creature's `EntityType` (never added to the world, kept per key such as `stage:<pet id>`), dressed with the pet's
variant tint, shiny and name, holding `sit` / `sleep` / `lie_down` as fits, `happy` on a pat
(`CreatureView.play(key, "happy")`), drawn through the normal entity renderer into a picture-in-picture texture and
fitted by the baked model's bounds. Works offline. The `pets`/`codex` snapshots carry `entity` (registry id;
`CreatureView.type` also takes old `mid` model ids and MythicMobs ids). The Blockbench streaming (`pet_model`,
`pet_model_ok`, `pet_model_missing`, `client/model/*`, `PetModels`, the model lab, the server's MythicMobs pack scan)
is removed; an old client's `pet_model` request is ignored.

## Core services (what module ports use)

Everything below lives in `core/` (branch `fm-core`). Bukkit → core cheat sheet first, then each service.

| Paper / old Feather | Core |
|---|---|
| `Player` | `ServerPlayer`; name `Names.name(p)`, display name `Names.display(p)` (Adventure), `Names.set(p, comp)` (nick module) |
| `player.displayName()` | `Names.display(p)`; after changing it call `feather.playerNamesChanged(p)` (fires `FeatherEvents.NAMES_CHANGED`) |
| `OfflinePlayer` | `core.OfflinePlayer(id, name)`: `online()`, `hasPlayedBefore()`; `feather.lookup().byId(uuid)` / `byName(name)` |
| `Location` | `core.Loc(level, x, y, z, yaw, pitch)`: `Loc.of(entity)`, `blockPos()`, `vec()`, `add`, `distanceSquared`, `coords()` |
| `player.hasPermission(n)` | `Perms.has(p, n)` / `sender.hasPermission(n)` |
| `player.canSee(other)` | `feather.canSee(viewer, target)` (the vanish module provides `Hooks.Vanish`) |
| `Bukkit.getScheduler()` | `Tasks.later/timer/sync/async` |
| `plugin.dialogs().menu(...)`, `DialogInput.text(...)`, `DialogResponseView` | `feather.dialogs().menu(...)`, `Dialogs.Input.text(...)`, `Dialogs.Response` |
| `Bukkit.createInventory(holder, 54, title)` + click listener | `Gui.create(6, title)` (see below) |
| `ItemMeta` name/lore/flags/glint, `SkullMeta` | `Icons.icon(Items.X).name(c).lore(list).flags().glint(b).build()`, `Icons.icon(item, nameKey, loreKey, tags...)`, `Icons.head(player)` / `Icons.head(uuid)` |
| `YamlConfiguration` / `SafeYaml` / `SafeFiles` | `Yaml`; `SafeYaml.read(path, log, what)` (null = unreadable, don't save), `SafeYaml.write(path, data, log)` |
| `Legacy.key(section, key, old)` | same, takes a `Yaml` |
| `Material.BLACK_STAINED_GLASS_PANE`, `LIME_DYE` | 26.3 colored items are `ColorCollection`s: `Items.STAINED_GLASS_PANE.black()`, `Items.DYE.lime()`, `Items.WOOL.red()` |
| EssentialsX (`EssentialsHook`, `EssentialsFiles`) | **dropped**: Essentials data was imported into Feather files on Paper; nothing reads Essentials any more |

### `Feather` (`Feather.get()`; modules receive it in their constructor)

- Services: `profiles()`, `teleports()`, `dialogs()`, `hub()`, `lookup()`, `picker()`, `placeholders()`, `scoreTeams()`,
  `timezones()` (null unless tool-stats provides one), `text()`, `getConfig()`, `dataFile(name)`, `loadResourceConfig(name)`.
- **Service registry** (how modules talk to modules that may be off or not ported yet):
  `feather.provide(Type.class, impl)` in `enable()`, `feather.service(Type.class)` → impl or **null**, `withdraw(Type.class)`.
  Everything is cleared when the server stops. Ready-made interfaces are in `Hooks`: `Auth`, `Afk`, `Vanish`,
  `TravelLock`, `Advancer`, `Stats`, `Mail`, `Ignores`, `Locks`, `BankBlocks`, `BountyTag`, `PlacesChanged`,
  `RecipesChanged`; plus `ClaimsHook` and `Timezones`. A module that others need richly (bank balance for the shop, ...)
  defines its own interface in its package and provides it; callers look it up the same way.
- Travel hooks (fm-travel): `Afk.reason(p)` (the /afk note), `Spawn` (Places provides: `/setspawn`'s spot), and for
  other ports to provide: `HomeGuard` (claims: may a home be set here), `Graves` (graves in the Companion places list
  and /deathpoint), `ChatGate` (moderation/chat: muted or chat paused).
- Progression hooks (fm-progression): `Hooks.Quests` (quests provide it: `progress(p, type, target, amount[, key])` with
  the old type names, `withdrew`, `goalPercent`, `bondXp`, `bossDefeated`), `Hooks.Bank` (the bank provides it; rewards
  call `credit`), `Hooks.PetProgress` (pets provide it: `codexUnseen`, `addBondToFollowers`, `countActive`,
  `topBondLevel`), `Hooks.FishRarity` (fishing; while it is provided quests/analytics leave rod catches to fishing),
  `Hooks.BossBoard` (boss board; altars call `bosses` / `recordKill`).
  Economy: `Hooks.Bank` (bank module: `credit/debit/transfer/balance/canCredit/isCoin/coin/robbing/robberyDeath/bankChests`...),
  `Hooks.Mail` (mail module); old Paper item bytes (`serializeAsBytes` / `serializeItemsAsBytes`) via `migrate/PaperItemBytes`.
- Shortcuts over the hooks (all safe when the provider is missing): `authPending(p)` / `authBlocked(p)`, `isAfk(p)`,
  `canSee(viewer, target)`, `travelLocked(p)`, `lockedAgainst(level, pos, uuid)`, `isBankBlock(level, pos)`,
  `advance(p, trigger, target)` (+ `advancer(Hooks.Advancer)`), `stat(uuid, stat, amount)`,
  `mailLetter(uuid, from, body)`, `mailItems(uuid, from, note, List<ItemStack>)`, `placesChanged(uuid)`,
  `recipesUpdated()`, `playerNamesChanged(p)`.
- Claims: `claimsHook(ClaimsHook)` (claims module), `claimAllows(ServerPlayer|UUID, Loc, ClaimAction)`, `claimed(Loc)`,
  `sameClaim(Loc, Loc)`.
- Teleports: `movePlayer(p, Loc)` / `teleportPlayer(p, Loc)` → `CompletableFuture<Boolean>` (already completed; no
  warm-up; refused while travel-locked), `teleportNow(p, Loc, TeleportCause)` → boolean (raw), `beforeTeleport(p, Loc)`
  (call before any teleport you do by hand: fires `BEFORE_WORLD_CHANGE` across levels and drops carried players).
- Players: `findPlayer(Sender|ServerPlayer viewer, input)`, `findOfflinePlayer(viewer, input)` → `OfflinePlayer`,
  `notFound(Sender|ServerPlayer, input)`, `completePlayers(viewer, prefix)`, `completeOthers(viewer, prefix)`,
  `player(uuid)`, `onlinePlayers()`.
- Menus: `openHub(p)`, `openSettings(p)`, `exitButton(menu, p)` / `menuExit(menu, p)`, `addTravelExtras(menu, p, width)`.
- `module(Class)` / `moduleOf(Class)`, `bind(cmd, FeatherCommand)` / `bindCommand(...)`, `isEnabled()`.
- **Fabric listeners can't be unregistered**: register them through `feather.once("mymodule.events", () -> ...)` in
  `enable()` and have them return early unless your module is running (keep a `static volatile` active instance; see
  `feature/core/CoreModule`).

### `Profiles` (`data/players/<uuid>.yml`, same keys as Paper)

`profiles().get(ServerPlayer)` (online), `load(UUID)` (anyone), `save(profile)` (async write), `hasPlayedBefore(uuid)`.
`Profile` fields are public (`homes`, `nickRaw`, `tpToggledOff`, `back`, `deaths`, `ignored`, `mutedUntil`, `trail`,
`timezone`, the moderation fields...) plus `firstJoin` (Bukkit's `!hasPlayedBefore()` for this session).
`Profiles.Home` (`of(name, Loc|player)`, `location()` → `Loc` or null), `DeathPoint`, `Warning`, `ModEntry`. Loaded when
the connection is set up (before JOIN), saved on disconnect. `snapshotInventory/Ender` still hold Paper's base64 blobs;
the moderation port decides the new format.

### `Teleports`

`teleports().teleport(p, Supplier<Loc> dest, Component destName, Runnable onArrive[, boolean checkSafe, Runnable onCancel])`
(warm-up with action bar + sounds; cancels on moving > 0.6 blocks or damage), `warmupFor(p)`,
`cancel(p, notify, reasons...)`, `isWarmingUp(p)`, static `findSafe(Loc)` / `safe(Loc)`. Moves are tagged
`TeleportCause.COMMAND`, so a /back listener on `FeatherEvents.PLAYER_TELEPORTED` sees them.

### `Dialogs` (vanilla 26.3 dialogs)

Same shape as the plugin: `menu(title)` → `.text(c[, width])`, `.item(stack, c, width)`, `.input(Input)`,
`.button(label, tooltip, width, Consumer<ServerPlayer>)` or `(…, BiConsumer<ServerPlayer, Dialogs.Response>)`,
`.sectionButton(...)`, `.section(key, label, buttons)`, `.columns(n)`, `.layout("hub")`, `.exit(label, Consumer|null)`,
`.open(p)`. Inputs: `Dialogs.Input.text(key, label).initial(s).maxLength(n).width(w).multiline(maxLines, height).build()`,
`Input.bool(key, label).initial(b).build()`, `Input.numberRange(key, label, start, end).step(f).initial(f).width(w).build()`,
`Input.singleOption(key, label, List<Dialogs.Option(id, display, initial)>).build()`. Callback values:
`response.getText(key)`, `getFloat(key)`, `getBoolean(key)` (validated against the menu's inputs).
Buttons are `minecraft:custom` clicks with id `aftrhrs:menu` (menu + button number + input values), handled by
`ServerCommonPacketListenerImplMixin` → `FeatherEvents.CUSTOM_CLICK` → `Dialogs.handleClick`; one use per menu,
10-minute lifetime, last 8 menus per player kept.
**Companion port**: implement `Dialogs.MenuUpgrader` (`boolean offer(ServerPlayer, Menu)`) and install it with
`feather.dialogs().upgrader(...)`; run its clicks with
`dialogs.run(player, button, Dialogs.Response.of(menu.inputs(), key -> rawValue))`.

### `Gui` (server-side chest menus)

`Gui.create(rows, title)`; `set(slot, stack[, Runnable | Gui.Handler])`, `fill(from, to, stack)`, `get(slot)`, `clear()`,
`unlock(slots...)` (ordinary chest slots; `onChange(Runnable)` hears about them), `onBottomClick(handler)` (take over
clicks in the player's inventory too), `onOtherClick(handler)`, `onClose(Consumer<ServerPlayer>)`, `drawer(Consumer<Gui>)`
+ `refresh()`, `attach(state)` / `attachment()` (the old `InventoryHolder` subclasses), `open(p)`, `viewers()`, `close()`,
static `Gui.of(player)` (the Gui a player has open). Locked slots never move items, whatever the click; handlers get a
`Gui.Click(player, gui, slot, kind, hotbar, cursor)` (`isShiftClick()`, `isRightClick()`, `kind` = Bukkit's `ClickType`)
and run on the server thread right after the click packet, so opening another menu from a handler is safe.
`PlayerPicker` (`feather.picker().open(viewer, new PlayerPicker.Request(title, chosen, toggle, exclude, back), page)`) is
the reference port of a Paper chest GUI.

### `Hub` (the Feather menu): modules add their own buttons

`feather.hub().add(key, Hub.Group, order, sound, permission | Predicate, Consumer<ServerPlayer> action)` adds a button
labeled `menu.hub.<key>` / `menu.hub.<key>-tooltip`; `add(new Hub.Entry(...))` for per-player labels (badges). Groups:
`MAIN`, `DAILY`, `COMMUNITY`, `STYLE` (a sub-screen once it has 2+ entries) and `TRAVEL` (Wild / Go Back: in the warps
menu via `addTravelExtras`, or on the hub when there is no `warps` entry). Use the `Hub.ORDER_*` constants to keep the
old layout (homes 10, warps 20, visit 30, pets 40, claims 50, guide 90...). Use the ids `homes` and `visit` for those
buttons: the Shift+F hotkey (`hotkey.opens`) runs them by id. Settings: `addToggle(new Hub.Toggle(key, Hub.TOGGLE_*,
visible, enabled, flip))` (labels `menu.settings.<key>`), `addSettingsButton(id, Hub.SETTINGS_*, visible,
(menu, p, width, back) -> ...)`. Greeting feeds: `homeLimit(p -> n|-1)`, `petCount(p -> "2/3"|"")`,
`requestCount(p -> n)`, `portrait(p -> plush|null)`.

### Other helpers

- `PlayerLookup` (`feather.lookup()`): `match/find/findExact/findOffline/findOfflineId/notFound/complete`,
  `nickOwner(plain)`, `byId(uuid)`, `byName(name)`, statics `key`, `plainOfRaw`, `nickPlain`, `nickToken`. Offline
  username lookups use the player files (`name:`), never Mojang.
- `Placeholders` (`feather.placeholders()`): `format(raw, subject, viewer, tags...)`, `tags(subject, viewer)`,
  `serverName()`, `luckPerms()`; statics `duration(ms)`, `parseDuration`, `punishmentDuration`, `looksLikeDuration`,
  `heartsNumber`, `legacyOrMini`, `MAX_DURATION`. PlaceholderAPI `%...%` codes are not expanded (Paper-only).
- `LuckPermsHook.create()` → null without LuckPerms; `prefix/suffix/group(p)`, `weight(group)`, `has(uuid, node)`.
- `ScoreTeams` (`feather.scoreTeams()`): `configure(hideNametags, collisions)`, `assign(p, sortKey)`, `ensure(p)`, `remove(p)`.
- `Timezones`: the tool-stats port does `zones = new Timezones(feather); feather.provide(Timezones.class, zones);
  feather.bind("timezone", zones); zones.reload();`. Then `zone(p)`, `format(ms, p)`, `openTimezonePicker(p[, back])`.
- `Mentions`: the chat port creates one (`enable/reload/disable`); `scan(sender, plain)` → `Scan` (`any()`, `targets()`,
  `render(message, viewer)`), `ping(sender, scan, viewers)`; it keeps `@name` chat completions up to date.
- `HubArt` (`banner()`, `moon(phase)`; fonts `feather:hub_banner` U+E000 and `feather:hub` U+E200+phase) and
  `GuideGlyphs.of(name)` (font `aftrhrs:glyph/<name>`, U+E000) are used only when the font JSON ships in the mod's
  assets; whoever ports the Oraxen glyphs adds those fonts.
- Not in core: `Inspector` (offline /invsee snapshots) is moderation-only and moves with the moderation module.

### Events (`core/event/FeatherEvents`, fired by `net.axther.aftrhrs.mixin.*`)

Allow-style (return false to cancel): `CUSTOM_CLICK`, `PLAYER_TELEPORT(p, from, to, TeleportCause)`, `COMMAND(p, typed)`,
`BLOCK_PLACE(p, level, pos, state, stack)`, `SIGN_EDIT(p, sign, front, lines)`, `PHYSICAL_INTERACT(entity, level, pos,
state)` (farmland, pressure plates, tripwire), `ITEM_PICKUP(p, itemEntity)`, `ITEM_DROP(p, stack)` (the item goes back),
`SWAP_HANDS(p)`, `CONTAINER_OPEN(p, provider)`, `PLAYER_MOVE(p, from, to)` (tick-based; false puts the player back).
Notifications: `PLAYER_TELEPORTED`, `BEFORE_WORLD_CHANGE`, `BLOCK_PLACED`, `PLAYER_MOVE_BLOCK`, `PLAYER_DEATH` (before
drops), `PLAYER_RESPAWN`, `NAMES_CHANGED`. Modifier: `RESPAWN_POSITION(p, transition, bedOrAnchor)` → a new
`TeleportTransition` or null. `TeleportCause` is tagged for Feather moves and /tp (COMMAND), pearls, nether/end portals,
end gateways and spectating; `cause.run(() -> ...)` tags your own. Use Fabric API for join/quit, chat
(`ServerMessageEvents.ALLOW_CHAT_MESSAGE`), damage (`ServerLivingEntityEvents.ALLOW_DAMAGE`; `FeatherEvents.attacker(source)`
finds the player behind a projectile), deaths, block breaking and the use/attack callbacks.
Travel events (fm-travel): `AFK_CHANGED(p, afk)` (tab list / nametags refresh), `SLEEP_IGNORED(p)` (true: not counted
for sleeping), `FORCED_POSE(p)` (a held pose: /lay, /swim). Players may ride players (`EntityRidePlayerMixin`, carry).
Progression events (fm-progression): `BLOCK_FORMED(level, pos)` (fluids, falling blocks, pistons), `SPAWNER_SPAWN(level,
entity, trial)`, `FISH_CAUGHT(p, rod, hook, loot)`, `STAT_AWARDED`, `ITEM_CRAFTED`, `ITEM_MENDED`, `COMBINE_RESULT`,
`ANIMAL_TAMED` (mixins in `mixin/` and `mixin/progress/`).
`MOB_CONVERT(mob, intoType)` (fm-qa, `MobConvertMixin`): false cancels any mob conversion (lightning, drowning,
curing, slime splits) before it happens, like Paper's `EntityTransformEvent` (shop keepers, hologram mob lines).
`MOB_PICKUP(mob, stack)`: false keeps a dropped item on the ground (bank coins).

### Chat, login and visibility events (fm-chat)

- Chat runs through Fabric's `ServerMessageEvents.ALLOW_CHAT_MESSAGE` in phases: `FeatherEvents.CHAT_GATE` (auth takes
  the server code), `CHAT_MODERATION` (staff chat, mutes, /mutechat), the default phase (modules that read or consume
  lines: chat games, AFK...), then `CHAT_FORMAT`, where the chat module delivers the line per viewer itself (unsigned
  system messages, or signed messages with the formatted line as unsigned content under the `aftrhrs:chat` chat
  type) and cancels vanilla's broadcast; it then fires `ServerMessageEvents.CHAT_MESSAGE` itself, so "after chat"
  listeners still see every shown line. Register with `ALLOW_CHAT_MESSAGE.register(FeatherEvents.CHAT_MODERATION, ...)`.
- `PLAYER_LOGIN(profile, address, current)` (refuse/replace the login screen; bans, maintenance, auth cooldowns),
  `JOIN_MESSAGE` / `QUIT_MESSAGE` (replace or drop the yellow lines), `SERVER_STATUS` (server-list entry, network
  thread), `SEND_COMMANDS` (empty command tree), `COMMAND_SUGGESTIONS` (merge into tab completion), `ENTITY_VISIBLE`
  + `FeatherEvents.refreshVisibility(entity)` (per-viewer entity hiding: vanish), `MOB_TARGET`, `CONTAINER_CLICK`,
  `AFK_CHANGED` (the AFK port fires it; tab list and nametags refresh).
- Hooks: `Hooks.ChatGate` (`silenced(p)`: muted or chat locked; moderation provides), `Hooks.Afk.reason(p)` (default ""),
  `Hooks.Ignores` (messaging and moderation provide it), `Hooks.Vanish` (staff module), `Hooks.Auth` (auth).
- Frozen players: `profiles().get(p).frozen`; muted: `profiles().get(p).muted()`. `feature.moderation.Restraint.register(pred)`
  holds players in place (auth gate and /freeze use it).

World protection (fm-world, fired by `mixin.protect.*`; claims, chest locks and graves use them): `EXPLOSION(level,
explosion, blocks)` (remove positions to spare them), `FIRE_SPREAD` / `BLOCK_BURN` `(level, from, to)`, `FLUID_INTO(level, to, dir)`,
`PISTON(level, piston, push, extending, moved, destroyed)`, `DISPENSE`, `ENTITY_CHANGE_BLOCK(entity, level, pos, newState)`
(endermen, ravagers, withers, zombies on doors, foxes, rabbits, silverfish, falling blocks, fireballs, lightning; every
`Level.destroyBlock` by a non-player), `PROJECTILE_HIT_BLOCK`, `EFFECT_APPLY(target, effect, source)` (splash/lingering
potions), `ENTITY_DAMAGE` (frames, paintings, knots, cushions, boats, minecarts, armor stands: what Fabric's
`ALLOW_DAMAGE` misses), `HOOK_PULL`, `MOB_SPAWN` (natural + patrols), `MENU_BUTTON` (lectern Take Book...), `HOPPER_PULL`;
`PHYSICAL_INTERACT` now also covers turtle eggs. Right-clicks: claims check the block side in Fabric's
`BlockEvents.USE_ITEM_ON/USE_WITHOUT_ITEM` and the item side in `ItemEvents.USE_ON/USE` (Bukkit's
useInteractedBlock/useItemInHand split); modules that consume a click before claims (graves, the claim tool) use
`UseBlockCallback`, which runs first. Services: claims provide `ClaimsHook` and `Hooks.HomeGuard`; chest locks
`Hooks.Locks`; graves `Hooks.Graves`.

### Boot-test note

Console commands piped in before the server's first tick fail with "An unexpected error occurred" (even vanilla `stop`).
Delay them: `((sleep 40; printf 'feather\nstop\n') | ./gradlew runServer --console=plain) > /tmp/srv.log 2>&1`.

## Entities (Arcana creatures, `entity/`)

Every MythicMobs + ModelEngine creature is a native GeckoLib 5.5.7 entity (71 types). Generated, data-driven, re-runnable:

- **Generator** `tools/gen_creatures.py` (Python 3 + Pillow + PyYAML; reads the Arcana pack read-only). Writes
  `assets/aftrhrs/geckolib/models/entity/<model>.geo.json`, `geckolib/animations/entity/<model>.animation.json`,
  `textures/entity/<model>.png` (+ `<model>_glowmask.png` for glow bones / light_emission cubes: GeckoLib's
  AutoGlowingGeoLayer draws it fullbright), spawn egg item model + texture (`items/<id>_spawn_egg.json`,
  `textures/item/spawn_egg/<id>.png`), the species table `data/aftrhrs/species/<id>.json` (+ `index.json`), the
  `minecraft:undead` / `minecraft:burn_in_daylight` entity tags, and `lang/en_us.entities.json` (**the merge step folds it
  into `en_us.json`**). Rerun it after a bbmodel or spec changes; never hand-edit its output.
- **Species table** = the specs (`tools/specs`, `tools/wild_specs`, bosses/police mobs yml) flattened: stats, hitbox, tame /
  heal items + chance, sounds, spawn rules (vanilla + Terralith biomes, dimension, rarity, conditions, caps), kind, flags
  (flyer, aquatic, mount, hostile, untameable, young_of, egg_only, preset_name ...), clip roles, mood rules, flourish
  pools, glow bones, ability lines (`abilities` / `lines`), model helper data (seats, name-tag height, shadow).
  `Species` reads it (`Species.anim(role)`, `clips`, `json` for anything else).

**Ids.** Entity id = model name without `arcana_`: `aftrhrs:emberkit`, `aftrhrs:luna`, `aftrhrs:tufthorn_young`. Bosses that
share a mount's model: `aftrhrs:cinderwing_phoenix_boss`, `aftrhrs:tempest_roc_boss` (the tameable mounts are
`aftrhrs:cinderwing_phoenix` / `aftrhrs:tempest_roc`). Lookup: `ArcanaEntities.type("emberkit")`,
`ArcanaEntities.byMythicId("ArcanaEmberkit")` (every MythicMobs id incl. `ArcanaCinderwingMount`, `ArcanaTufthornYoung`,
`ArcanaWukong`, `ArcanaPolice`), `ArcanaEntities.mythicIds()` for migration, `ArcanaEntities.spawnEgg(id)`.

**Base classes** (all implement `ArcanaMob`: `species()`, `play(clip)`, `accent(clip)`, `hold(clip)`, idle/walk clip
setters, `scheduler()`, `var/setVar` (saved ints), `busy/setBusy/free`, `setBoneVisible`):

| Class | Kinds | Notes |
|---|---|---|
| `ArcanaCreature` (TamableAnimal) | pet, hostile (tameable), Lurefin | taming, stay/follow, feed, pet, rename, nap, leash-teleport past 20 blocks, wild despawn/unsaved, aquatic swim, flyers |
| `ArcanaMount` (extends ArcanaCreature) | mount | two seats, walker / flyer / swimmer controls, motion controller |
| `ArcanaWildlife` (Animal) | wildlife, livestock (+ young), duskflies | idle life, gaits, herds, young follow + grow up |
| `ArcanaMonster` (Monster) | undead, boss, police | melee / bow AI, boss bar, idle antics |

**API for the pets port** (`ArcanaCreature`): `bond()` / `setBond(0..5)` (synced, saved; abilities read it via
`bond(a,b)` = a + b·bond and `bondLvl(a,every)`), `isTame()` / `getOwner()` / `tamedBy(ServerPlayer)`,
`staying()` / `setStaying(boolean, ServerPlayer|null)` (sit / stand clips + messages), `variant()/setVariant`,
`tint()/setTint(0xRRGGBB)` (multiplied over the texture on clients; the old ModelEngine tint), `shiny()/setShiny`,
`var/setVar` (saved ability vars, e.g. `gift_armed`), name = vanilla custom name (visible).
**`CreatureEvents`** (Fabric events, server thread): `CAN_TAME` (return false = pet cap; replaces `featherpetslot`),
`TAMED` (replaces `featherpet{a=register}`; give the plushie here), `DIED`, `ABILITY` (skill id fired: bond XP), `FED`,
`INTERACT` (runs before the creature's own click handling; return non-PASS to take over: /pets menu, plush revive,
breeding, Work Here), `STAY`. Plushies, pet cap, bond XP, revive, whistle, stable, Work Here, Canopip replant / Sporeling
quality (send `Abilities.signal(mob, "CANOPIP_REPLANT"|"SPORELING_BOOST", player)`), Ripplekit fishing and the Hushwing's
sculk hush are the pets/farming/fishing/civic modules' job.

**API for the wildlife port** (`ArcanaWildlife`): `hook("Harvest"|"Root"|"Shorn"|"Regrow"|"AntlersOff"|"AntlersOn"|
"HoldFish"|"DropFish"|"Dive"|"Lay"|"Spin"|"Hide")` (= the old `ArcanaWild_*` skills cast by name), `kept()/setKept`
(never despawns), `spook(ticks)`, `setBoneVisible(bone, visible)`, `ageMinutes()`. Breeding: spawn the young type
(`ArcanaEntities.byMythicId(species.youngId())` / the `young` field).

**API for the bosses / police port** (`ArcanaMonster`): `bossBar()`, `play/hold`, `setBusy`, `scheduler()`; register the
boss attack skills with `Abilities.register("ArcanaWukong_Brain", ctx -> ...)` - the spec lines already fire them on their
MythicMobs triggers (the not-yet-ported ids are logged at startup). Police: `setTarget(robber)`; it never picks targets.

**Abilities** (`entity/ability`): `Abilities` runs each species' spec lines on their triggers (`~onTimer:N`, `~onAttack`,
`~onDamaged`, `~onDeath`, `~onSpawn`, `~onLoad`, `~onInteract`, `~onSignal:X`); plain mechanics (potion, ignite, particles,
sound, freeze, damage, state, setvariable, extinguish) run as is, `skill{s=Id}` calls the Java skill registered under that
MythicMobs id (`PetSkills`, `CatSkills` (Luna, Kevin), `MountSkills`, `WildSkills`). `Ctx` gives the caster, target,
owner helpers, bond math, cooldowns (`ctx.cooldown(key, seconds)`) and delayed sequences (`ctx.seq().run(..).wait(n)`).

**Animation**: see the **Animation** section below (clip tags, the client state machine, sitting, idle pools, the
procedural layer, the clip authoring kit and `anim_check.py`). Server-side the mount motion controller (air_rise /
air_fall / leap / land / land_hard; hover / fly / climb / dive / glide; tread / swim / rise / dive / splash / breach;
ride_walk / ride_run gait; fidgets) and Luna / Kevin's jump_start / jump / jump_end still set the intent as before.

**Client** (`client/entity`): `ArcanaRenderers.init()` registers an `ArcanaGeoRenderer` per type (head bone follows look,
partvis hidden bones, tint, model shadow, young scaling, glowmask layer that goes dark during the death clip for
`glow_out` species).

**Mixins** (own config `aftrhrs.entity.mixins.json`): Shift does not dismount a flying mount in the air or a swim mount in
water (it descends / dives); a mount rider's arm swing reaches `Abilities.onRiderSwing` (Phoenix kindle). `MobCapMixin` keeps
wild Arcana creatures out of the vanilla mob cap (see Natural spawning).

**Natural spawning** (`ArcanaSpawner`, `SpawnConditions`; settings `config.yml arcana-spawning`). Arcana creatures are
in **no** vanilla biome spawn list: putting them there (the first port did, weights 8/4/1) made them compete with
vanilla's own entries under the shared mob cap and took 50-90 % of the CREATURE picks in meadows, cherry groves,
deserts and most Terralith valleys, so new chunks ended up with few vanilla animals. As on Paper (MythicMobs random
spawns, `Action: ADD` / `REPLACE`):

- **Additive spawner** for every non-undead rule (pets, mounts, wildlife, livestock, duskflies, the tameable hostiles).
  Every `interval-ticks` (20) each online, non-spectator player gets `attempts` (2) random spots `min-distance`..
  `max-distance` (12..64) blocks away horizontally, `y-range` (16) up/down: the first ground spot (two free blocks over
  a sturdy top: surface or cave floor) or water block scanning down, then up, from a random height. Nobody within
  `min-distance` of the spot, not within 24 blocks of the world spawn, chunk loaded and entity-ticking. The spot's
  dimension + biome pick the rules (`ArcanaSpawner.rulesAt`; LAND rules on ground, SEA/WATER rules in water); in random
  order each rule off its per-player cooldown rolls `chance.<rarity>` (common 0.06, uncommon 0.035, rare 0.012,
  very_rare 0.006: the MythicMobs chances), then must pass the kind rules (aggressive kinds only at night via
  `nightForSpawns`; bosses, police, undead never; monster-category types need the `spawn_monsters` game rule; on
  Peaceful nothing that Peaceful would delete at once: wild hostiles, not-in-peaceful types), the rule's conditions,
  `structures`, `cap`/`cap_radius`/`cap_types` (MythicMobs `mobsinradius`), the type's `SpawnPlacements` position +
  predicate, room for the hitbox (`noCollision`, `checkSpawnObstruction`) and `FeatherEvents.MOB_SPAWN` (claims with
  mob spawning off block Enemy types, as for vanilla natural spawns). At most one spawn per spot; the rule then waits
  `cooldown-seconds.<rarity>` (60 / 150 / 600 / 1200) for that player. **Local cap**: no spawns for a player while
  `local-cap` (8) wild Arcana creatures (`ArcanaSpawner.isWild`: untamed, not kept, not leashed, not persistent,
  not egg-only) are within `local-cap-radius` (64). Off with the `spawn_mobs` game rule or `arcana-spawning.enabled:
  false`. Spawns use `EntitySpawnReason.NATURAL`; wild-despawn rules are unchanged (gone when no player is within 64,
  not saved). Every key is re-read each run, so `/feather reload` applies them.
- **Vanilla mob cap**: `entity.mixin.MobCapMixin` makes `NaturalSpawner.createState` count every Arcana creature except
  the undead as MISC (outside the cap), so wild Arcana creatures never take room from vanilla spawns. The undead stand
  in for zombies / skeletons and still count as monsters.
- **Undead variants** (`replace`: vanilla, share, moon_share, biomes): real replacement. `mixin.NaturalSpawnReplaceMixin`
  wraps `NaturalSpawner.getMobForSpawn` in `spawnCategoryForPosition` (vanilla's natural spawner only: not chunk
  generation, spawners, trial spawners, eggs, reinforcements, commands) and fires `FeatherEvents.NATURAL_REPLACE`;
  `ArcanaEntities.naturalReplacement` returns the variant for a vanilla `zombie` / `skeleton` about to spawn in one of
  its biomes, at night (`nightForSpawns`; the Nether always), with probability `share`, or `moon_share` on the
  Restless Moon (new moon, at night), if its hitbox fits. The variant then goes through vanilla's remaining checks
  (darkness via the walk-target rule, obstruction, `MOB_SPAWN`), `finalizeSpawn` and the mob cap exactly like the
  zombie would have, so the total monster count is unchanged. The undead have no biome entries and no additive rules.
  Any vanilla natural MONSTER type can be replaced: zombies, skeletons, **spiders** (Gloomweaver: dark forests, pale
  gardens, old-growth taigas, incl. cave spots under them), **drowned** (Reedwader: rivers, in the water) and
  **husks** (Sandshroud: deserts). A drowned variant overrides `checkSpawnObstruction` (vanilla's default refuses any
  liquid, which would have turned away every drowned replacement); game tests `UndeadVariantTests` measure each share
  through vanilla's spawner.
- **Variant behavior** (`ArcanaMonster`, spec `base`): `SPIDER` = the vanilla spider's goals (`VariantGoals`: leap,
  neutral in the light, drops the target in the light), wall-climbing navigation + synced climbing flag, cobweb and
  poison immunity, Hard-mode random effect, 16 HP / 0.3 / 2 dmg, spec `arthropod: true` (gen_creatures puts it in
  `#minecraft:arthropod`, not `#undead`); `DROWNED` = amphibious navigation + the drowned's swim control, go-to-water by
  day, go-to-shore / swim-up at night, attacks only in the dark or in the water, step height 1, `swim` / `tread` clips
  under water; `HUSK` = the husk's Hunger touch (140 ticks x regional difficulty), no sunburn (`PreventSunburn`).
  Spec `snip` (`{item, min, max, extra, bone, regrow_minutes}`): shears take the item off a calm variant (no target),
  hide the bone until it regrows, fire `CreatureEvents.HARVESTED` (the bestiary counts it).
- **Spawn predicate** (`ArcanaEntities.canSpawn`, registered with `SpawnPlacements` so eggs / commands keep working and
  both spawners agree): any reason but NATURAL / CHUNK_GENERATION is always allowed. Natural: bosses and the police
  never, aggressive kinds only at night (`Conditions.night` 13000..23000; dimensions without a day cycle have no daytime),
  undead need vanilla's darkness rule, everything else needs a rule covering the spot (dimension, biome, conditions,
  structures, cap). No random roll in the predicate any more: rarity is the spawner's chance.
- **Conditions** (`SpawnConditions`, compiled once per rule): `<expr> [true|false]`, `(...)`, `||` and `&&` with per-
  operand `true|false`; atoms `outside`, `inside`, `night`, `day`, `dawn`, `raining`, `thundering`,
  `worldtime{t=AtoB}` (wraps past midnight), `height{h=<N}` (`>N`, `<=N`, `>=N`, `AtoB`, `N`) on the spawn block's Y,
  `lunarphase{p=N}` (day % 8). The species data uses `outside true|false`, `night true`, `worldtime{t=..}`,
  `height{h=<40}` (Wickling caves) and the Velvetstag's `(worldtime || worldtime || worldtime) true`. Unknown syntax is
  logged at startup and that rule never spawns (it used to count as true).
- **Water spawns** (Creatures 2, ocean): vanilla's `Mob.checkSpawnObstruction` refuses any liquid in the hitbox, so no
  aquatic species (Bubblefin, Ripplekit, Driftglow, Lurefin, Tidemane) ever spawned from a SEA rule.
  `ArcanaCreature` / `ArcanaWildlife` now only ask for room when the species is aquatic (as vanilla's `WaterAnimal`);
  `gametest/creatures/OceanTests` probes every ocean biome. Terralith's `deep_warm_ocean` joins every `WARM_OCEAN` rule.
- **Aquatic wildlife** (wild spec `"aquatic": {"swim": <in-water speed factor>, "water_only": bool}`; Kelpback, Inkcurl):
  `ArcanaWildlife` swims with vanilla's smooth-swimming move/look controls, water-bound (or amphibious) navigation, the
  `randomswim` goal and dolphin-style gliding (idle ones sink slowly to rest on the floor); it breathes under water and
  dries out on land in 2 minutes. Its idle life (graze / lie / custom acts) runs in water; herd mates join in water.
- **Fishbowl pet** (spec `"fishbowl"`; the Pockie, `entity/FishbowlCreature`): an aquatic pet with a second body
  state. Synced `DATA_BOWL`: SWIM -> (out of water, water < 0.12 deep for 4 ticks) ENTERING (`bowl_in`, held still, 34
  ticks) -> IN_BOWL -> (water >= 0.4 deep for 4 ticks) EXITING (`bowl_out`, 30 ticks) -> SWIM. The bowl is part of the
  model (bones `bowl` > `bowl_roll` glass, `water` level contents) and hidden in SWIM; in the bowl the hitbox is
  `fishbowl.box`, it walks on land with ground navigation (`bowl_roll`: the glass turns once per 0.75 s = its
  circumference at the gait's nominal speed), never dries out, and `clipFor` maps every clip to its `bowl_` twin (whole-
  body water clips without a twin are skipped, overlays play); `postureClip` / `moodIdle` / `moodWalk` (ArcanaCreature
  hooks) and `ArcanaMob.clipFor` (client: death, sit fallback) do the same for postures, moods and the gait. Pool
  condition `in_bowl`. Spec `"glass_layer"`: `gen_creatures` moves translucent texels (0 < alpha < 255) into
  `<model>_glass.png`, drawn by `ArcanaGeoRenderer.GlassLayer` with vanilla's culled entity-translucent type (Iris maps
  it). Spec `"glow_dark"`: the glowmask fades by day (raw brightness at the creature) and dims while asleep.
- **Penguins** (spec `"penguin"`, `entity/PenguinCreature`; winter 2026): Emperor and Rockhopper adults
  (`aftrhrs:emperor_penguin`, `aftrhrs:rockhopper_penguin`) and their chicks (`*_chick`, codex forms via codex.yml
  `form-of`). Amphibious: ground navigation over the amphibious path finder (FollowOwnerGoal only takes ground / flying
  navigation), its own swim move control + `travelInWater` (fast, pitching; floats at the surface when idle), never
  drowns; gait `slide` (belly toboggan, +60 % speed) when an adult hurries over snow or ice. Natural spawns only near
  water / ice (`checkSpawnObstruction`) and bring their colony (`penguin.group`, chicks follow the adults). Adults are
  `raised` (no tame item; `PetsTests.tameable` skips them): a raw fish tames a wild chick, each fish keeps a tamed chick
  growing 5 minutes (vars `fed` / `grow`), after 20 fed minutes `growUp` makes the adult and fires
  `CreatureEvents.GREW_UP` (the pets module moves the record: species, entity, tag). Boats: `PenguinBoats` +
  `entity/mixin/BoatPenguinMixin` (thrust x (1 + boost) on water, sled friction 0.85 and 1-block steps on snow, the
  penguin passenger faces forward). The server alone decides who pushes (synced `DATA_PUSH`, cap 4, 30-60 s, they let go
  outside `penguin.habitat`) and who rides; the boat physics on the steering client read only that synced state. Fishing
  buddy: a raw fish to an adult starts a session (one per player per 5 min, the penguin rests 5 min, max 3 extra fish);
  `FishingListener.caught` asks `PenguinCreature.buddyDouble` after the journal / records / catch counts and adds a
  fresh fish of the same species, no heavier than the catch. Game tests: `gametest/creatures/PenguinTests`.
- Game tests pause the spawner timer (`-Daftrhrs.spawner.paused=true` in `runGametest`) and drive it through
  `ArcanaSpawner.probe(player, pos, filter)` (one attempt, chance and cooldown ignored, local cap kept); see
  `gametest/creatures/SpawnTests`. Proof on a server: `/aftrhrs debug spawns [attempts]` runs the predicate (see Sound
  below).

## Sound (creature sound design, `entity/CreatureSfx`)

Every species has a hand-written **sound profile** in `tools/sfx_profiles.py` (archetypes `dog()`, `cat()`, `critter()`,
`beast()`, `undead()`, `boss()` plus per-species overrides). `gen_creatures.py` runs `tools/sfx_gen.py`, which writes
`assets/aftrhrs/sounds.json` (`aftrhrs:<species>.<event>` + `.layer`, `aftrhrs:shared.*`; variants with pitch / volume /
weight / attenuation_distance), the subtitles (`lang/en_us.sounds.json`), `data/aftrhrs/sfx/shared.json` and each
species table's `sfx` block (events, ambient interval, snore interval, petting particle, range). It checks every
vanilla event and file against the 26.3 asset index (Loom cache) and fails on a typo, a species without a profile or a
profile without ambient / hurt / death. Only vanilla sound files are used (re-pitched, re-weighted, layered); no custom
`.ogg` (ffmpeg could encode them, but every sound we needed exists in vanilla, and re-encoded Mojang audio is not ours
to ship). Never hand-edit the outputs.

**Events**: ambient (+ `ambient_happy` / `_sleepy` / `_scared` / `_angry`), `snore`, `sleep`, `wake`, `pet`, `happy`,
`bond_up`, `bond_level`, `tame`, `tame_fail`, `eat`, `heal`, `hurt`, `death`, `step`, `sit`, `stand`, `arrive`,
`revive`, `ability` (+ `ability_<skill suffix>`), `attack`, mounts `saddle` / `jump` / `land` / `flap` / `gallop` /
`splash`. An event may carry a `layer` played N ticks later and a per-play pitch jitter (server side).

**Hooks**:
- Base classes override `getAmbientSound` / `playAmbientSound` / `getAmbientSoundInterval` (vanilla's ambient timer,
  profile interval, x1.5 while staying), `getHurtSound` + `playHurtSound` (layer), `getDeathSound` (+ death layer in
  `die`), `getVoicePitch` (profile jitter, no baby doubling: young forms are pitched in their profile) and
  `playStepSound` (profile step at 0.2 volume, else the block's step). The old timer-driven ambient / step code is gone.
- Ambient mood: target -> angry, happy / petted -> happy, scared / worried / hurt / alert mood -> scared, sleepy mood or
  staying at night -> sleepy. **Sleeping** (napping, a held sleep / lie pose, a dormant Vinebound) plays only `snore`
  (breathing, purring, grinding stones) at its own rhythm, or nothing.
- Petting (`petBy`) plays the species' `pet` sound + its particle; the old owner-side `entity.cat.purr` from
  `Perks.pet` (pets.yml `sounds.petting`) is removed. Luna / Kevin have no `pet` event: their pet reactions are theirs.
- Pets module: `addXp` -> `CreatureSfx.bondUp` (shared chime + the species' `bond_up`, max every 3 s per pet) or, on a
  level-up, `bondLevel` (bell arpeggio rising one note per level, the species' `bond_level`, finale + note / end rod /
  species particles). Revive -> `revive` (+ shared totem/anchor shimmer); whistle -> shared whistle + `arrive`;
  `/pets summon` arrival and the 20-block leash teleport -> `arrive`.
- Creature: tame (`tame` + shared chime) / tame fail, feed (`eat`, then `heal`), Shift stay / follow (`sit` / `stand`),
  nap (`sleep` / `wake`), melee (`attack`). Abilities: `Ctx.ability()` -> `ability[_<skill>]` (2 s cooldown); boss
  attack clips (`BossKit.clip`) -> the boss's `ability` roar (5 s cooldown). Mounts: mounting `saddle`, leap / takeoff
  `jump`, landing / touchdown `land`; `CreatureSfx.tick` adds wing `flap`s for flyers in the air, `gallop` for ridden
  mounts at speed and a `splash` when plunging into water.
- Debug (op): `/aftrhrs debug sfx <species|all> <event|all|check>` plays one / every event at you (`check` verifies
  every profile event is registered); `/aftrhrs debug spawns [attempts]` paints one of each species' biomes far from
  spawn and runs the real spawn predicate at noon and at midnight.

| Species | Voice / material | Why (non-obvious choices) |
|---|---|---|
| Cairnling | amethyst / calcite clinks, tuff steps; pet = gravel at half pitch + amethyst resonance + faint grindstone; snore = slow grindstone | a rock golem: its "purr" is tumbling pebbles, never a cat |
| Emberkit | fox voice over campfire crackle, fire-charge puffs, extinguish hiss on hurt / death | a fox made of embers |
| Glimmermoth | parrot wing flutter + note-block chimes (pet / happy are chime intervals), no voice | moths are silent; the glimmer is its voice |
| Dogs | 26.x wolf sound sets per breed: Corgi `cute` 1.32, Dachshund `puglin` 1.28, Beagle classic 1.12 (+ sad whine bay on its sniff), Husky classic 1.0 ("talks": whine + bark), Copper Husky `grumpy` 0.95, Golden Retriever classic 0.95 (pants), Saint Bernard `big` 0.72 | smaller = higher; real dog sounds only |
| Cats | cat events only: Tabby 1.0, Black Cat `cat_royal` 0.92, Calico Kitten `baby_cat`, Maine Coon 0.8 (trill = everyday voice), Luna 1.1, Kevin 0.86 (+ audible chonk steps) | Maine Coons are chirpers; Kevin weighs 40 pounds |
| Honeycub | polar bear cub + panda voice, bee loop / pollinate hum, honey drink | a bear cub covered in bees |
| Puddlepup | axolotl voice, splashes, drips, mud-squelch paw steps, shake on standing | splashy |
| Wickling | candle ambient (its main voice), wax steps, flint strike on wake / tame, extinguish puff on sleep / death | a living candle |
| Cloudlamb | soft high sheep + breeze air whoosh, wool steps / pet | sheep in a cloud |
| Barkling | creaking (high), azalea rustle, wood steps, creaking-heart snore | a pale-oak sentinel |
| Burrowpip, Mossbun, Canopip, Magpip | armadillo + sniffer snuffles; rabbit + moss; fox + piglin-cheer chatter; parrot | Canopip's monkey chatter = piglin celebrate pitched up |
| Sporeling | slime squish + pufferfish puffs, fungus | spores |
| Hushwing | sculk clicks, low flute hoots, 10-block range, near-silent wings | it hushes sculk, so it is hushed itself |
| Snowpelt Owlet | double low flute "hoo-hoo", powder-snow steps | owls hoot; parrots don't |
| Runeback Tortoise | turtle + enchanting table / beacon hum on pet, shell clunk on sit | the runes hum |
| Skyfin Ray | underwater whale calls + breeze air | a manta of the sky |
| Shellpip, Ripplekit, Bubblefin, Driftglow | baby turtle + pot clicks + brushing; dolphin squeaks; bubble pops + conduit; glow squid + shimmer | sea creatures sound wet |
| Dunebun / Snowmuff | deep rabbit thumps, sand + wool steps / polar bear, a soft low goat-horn "trunk toot", snow steps | Snowmuff is a mammoth calf |
| Cinderwing Phoenix / Tempest Roc | blaze + fire, dragon wing flaps / phantom + breeze wind, distant thunder purr; 24-block range | big birds of fire and storm |
| Tidemane | horse breathing, dolphin layer, gallop, plunge splash | a sea horse in the literal sense |
| Floepup / Kelpback / Inkcurl | goat-bleat "maa" + puglin bark, flipper slaps / low cow moo + dolphin water calls / squid gurgles, ink squirt | a seal pup, a sea cow, a shy octopus |
| Pockie | axolotl water chirps pitched up, bubble pops; on land every touch is its glass bowl (soft clink as it rolls, glass tap on pet), axolotl splash in / out of the bowl, glow-squid squirt for its ability | a tiny wet voice in a glass ball |
| Emperor / Rockhopper Penguin | low two-pulse goat-bleat trumpet, dolphin gulp, snowy foot slaps / donkey hee-haw pitched high (the real rockhopper bray), parrot squawks, stone taps; chicks: a thin flute whistle / a squeaky parrot chirp | each species distinct; penguins bray, they don't quack |
| Cinder Imp | blaze 1.6 + fire, witch cackle when angry | an imp |
| Rime Wraith | soul-sand-valley whispers + stray rattle + cold wind, ice-glass hurt / shatter death | eerie (it is the one players met by day) |
| Volt Wisp | beacon hum, copper bulb clicks, chiptune `bit` notes, lightning crack death | a ball of static |
| Geode Crawler / Lurefin | silverfish + amethyst clusters / conduit lure hum + guardian jaws | crystal bug / anglerfish |
| Livestock & wildlife | pig + hoglin, moody cow, sheep + nether wart, chicken quack + webbed steps, slime trail, silverfish + silk, shy goat, gull with phantom cries | young forms x1.45 pitch |
| Undead | skeleton / zombie / husk voices pitched down with biome layers (drips, mud, frost, leaves, soul whispers + chains), 20-block range; Gloomweaver = spider voice + creaking wood + silk rustle, Sandshroud = husk + pouring sand, Reedwader = drowned + river drips + bobber plink | eerie, each from its home |
| Bosses | dragon growls, ravager roars, warden agitation, iron golem stomps, thunder, wind; Wukong = vindicator laugh + bells; 40-48-block range | big |

## Animation (branch `fm-anim`)

Every Arcana creature runs one animation framework. Data lives in the species table (`anim` block), clips in the
generated `geckolib/animations/entity/<model>.animation.json`; both come from `tools/gen_creatures.py`. Coverage report:
`ANIMATION.md` (written by `tools/anim_check.py`).

### What was broken and why (2026-11-19)

- **Every keyframe rotation was mirrored in game.** GeckoLib negates rotation x and y when it loads a bedrock animation;
  the generator copied the .bbmodel keyframes as they were, so every pitch and yaw played inverted: sits pitched the
  front down and the rear up (a cat "sit" looked like a crouch / belly flop, a dog's like a play bow), heads nodded the
  wrong way, tails curled the wrong side. `animkit.flip_rotations` now writes x / y negated (positions and scales are
  untouched); the tools read the json back through `animkit.load_gecko`, so everything authored or analysed is in
  Blockbench convention (what bb-render, `bbmodel_sample_pose` and the old ModelEngine server show).
- **Still-only clips played on while the creature walked.** Every one-shot (roll_over, belly_up, flop, sploot, loaf,
  play_bow, happy, greet ...) was a triggered animation on the `action` controller, which overrides the walk on every
  bone it keys and always runs to its end; the mood brain only checked "not moving" at the instant it fired, nothing
  kept the pet from wandering or following during a 2 - 5 s clip, so it slid along rolled over / belly up. Held poses
  were the same: nothing stopped a sit / sleep hold from showing while the body moved.
- Posture changes were timer-chained holds on the server (`hold("sit_down")` then `hold("sit")` after N ticks), so a
  move or a stay toggle mid-chain left the wrong pose, and nothing related the clips to what the body was doing.

### Layers (`entity/CreatureAnim.java`)

| controller | plays | who decides |
|---|---|---|
| `main` (base) | locomotion, postures (sit / lie / sleep / held poses), idle loops, whole-body one-shots | `CreatureAnim.Driver` on the client |
| `action` (overlay) | clips tagged `overlay`: attacks, hurt, mount fidgets, head / ear / tail-only clips; layered over the body, may play while moving | `mob.play(clip)` |
| `accent` (additive) | leap, land, land_hard, ride_takeoff, ride_land | `mob.accent(clip)` |

`ArcanaMob.play(clip)` routes a clip to its layer by its tag. The **server sets the intent** (synced: idle clip, walk
clip, held clip; vanilla's sitting flag; triggered one-shots). The **client Driver** (one per creature, every frame):

1. Measures movement from the creature's own position (EMA of blocks per tick, teleports ignored) with hysteresis:
   moving above `walk_on` (0.012), still after 3 ticks below `walk_off` (0.005); run above `run_on` (0.8 x speed
   attribute), back to walk below `run_off` (0.68 x). Flyers in the air and swimmers count vertical speed too.
2. Moving: the gait (the server's walk clip; the base walk becomes `run` by measured speed; a flyer in the air flies) or
   a held clip tagged `move` (air_rise, jump). Walk / run playback speed follows the real speed (0.75 - 1.4 x). A posture
   or a still-only one-shot is cancelled by a 3 - 4 tick cross-fade: **nothing tagged `move: false` plays while moving.**
3. Still: the held clip (sit, sleep, knead, stand_guard ...) or the idle loop. A held posture change goes through the
   transition clips of `anim.transitions` (BFS over postures: sitting -> standing = stand_up, lying -> standing =
   wake_up + stand_up ...) as one GeckoLib sequence; idle loops cross-fade (8 ticks).
4. A one-shot triggered on `main` keeps playing unless the creature moves and the clip cannot; 5 ticks before its end it
   blends into whatever comes next.

`-Daftrhrs.animTrace=true` logs one `[animtrace]` line per creature per client tick (clip, posture, moving, speed,
intent) plus every decision; a still-only base clip shown more than 7 ticks into a move is logged as `VIOLATION`.

### Clip tags (`tools/animtags.py`, `anim.clips`)

`{"p": posture, "from": posture (transitions), "move": bool, "loop": "loop|once|hold", "layer": "base|overlay|additive",
"drv": "hbte", "eyes": bool, "len": s, "gait": "walk|run|swim|fly|idle|jump"}`. Postures: standing, sitting, lying,
belly, airborne, swimming. Derived: hand overrides (`TAGS` in `clips/<model>.py`) > name rules (`LOCO`, `POSTURE`,
`TRANSITION`, `OVERLAY` tables) > keyframe analysis (root / body rotation past 75 deg = belly; dropped + front up =
sitting; dropped and level = lying). Transitions are measured: a lie_down that starts standing is tagged
standing > lying. Boss / police / undead one-shots stay overlays (their skills own them). `drv`: what the clip
animates (h head, b body, t tail, e ears) so the procedural layer leaves those bones alone.

### Sitting, napping, hitbox (`ArcanaCreature`)

Vanilla's sitting pose (`isOrderedToSit` / `isInSittingPose`, synced) is the truth. `postureTick()` (every tick) holds
the species' sit clip while sitting, the sleep clip while napping (staying pets nap at night, wake at day), or a
sitting / lying idle variant; a hold a skill set (knead, stand_guard, hide) is never overridden. Getting up: the
creature stays put (`isImmobile`) for the length of its way up (wake_up / stand_up / unhide) so the clip plays before it
walks off. Hitbox: `getDefaultDimensions` scales by posture (`anim.boxes`, default sit 1 x 0.8, lie 1.1 x 0.6; the eye
scales with the height), refreshed when the sitting flag or held clip changes. Mounts told to stay are parked the same
way. Sounds: `CreatureSfx` sit / stand / sleep / wake fire as the transition starts.

Every tameable has a sit clip and a way into and out of it: authored where missing (`clips/<model>.py`: Maine Coon real
sit + its old loaf kept as `loaf`, Honeycub sits on its rump, Glimmermoth's transitions join its `rest` pose, the
aquatic pets and Tidemane got sit_down / stand_up / lie_down / wake_up) and generated direct sit <-> sleep blends
(`sit_lie_down`, `lie_sit_up`) where a model's own lie_down / wake_up start or end standing.

### Idle variety (`MoodBrain`, `anim.idle`)

Every 3 - 8 s a still, free tameable picks from the pool of its posture:
`{"clip": name | "@mood" | "@sit" | "@sleep", "w": weight, "min"/"max": seconds a loop lasts, "cd": cooldown s,
"when": "night,!rain", "boost": {"owner_near": 2}}`. A loop runs for min..max s, a one-shot plays once (and the
creature does not wander while a still-only one plays: `lingering()`), `@mood` returns to the mood's loop. The standing
pool also gets the mood's flourish pool (`pools`, gen_mm's lists) while calm. Conditions (`MoodBrain.holds`): night,
day, dawn, cold, hot, rain, thunder, happy, hurt, monster_near, in_water, in_combat, on_bed, sunbeam, in_chest_block,
on_container, staying, tame, owner_near[:r], owner_far, player_near[:r], alone, mood:<clip>, owner_holding:<item>,
owner_hurt, owner_crouching, owner_sprinting. Tamed creatures also glance at their owner (`Goals.LookAtOwnerGoal`).

### Bosses and the police (`BossBrain`, `anim.idle.boss_calm` / `boss_combat`)

`ArcanaMonster` of kind boss / police is driven by `entity/BossBrain` instead of the per-second rage swap: idle loops
and one-shots from `anim.idle.boss_calm` (no target) or `boss_combat` (a target; a combat one-shot is a taunt the boss
stands still for), `when` tokens `p1 p2 p3 p2+ far near air ground night day`, never the same loop twice in a row.
Phase = Wukong's `phase` var, the Phoenix's rebirth, else below half health = 2; entering a phase plays the state
`phase<N>` clip (standing). States `walk<N>` (per phase), `chase` (with a target), `stance<N>` (fallback idle),
`hurt_heavy` (a hit worth 3 % of max health). Flinches never cut an attack clip and are rate-limited; a melee attack clip
tagged `hit` (seconds) deals its damage on that frame (if the target is still within reach + 2); timed boss skills wait
for a running attack and retry once (`BossKit.waiting`); the body stays for its whole death clip (+0.5 s) with a finale
burst per boss, and is not tinted red after the killing blow's flash. Clips: `tools/clips/<model>.py`; the bird bosses
share the mounts' models, so theirs live in `clips/<model>__boss.py` (gen_creatures loads `clips/<model>__*.py` after the
model's own module) and are named `boss_*`. Boss sounds are clip sound keyframes (`ability_*` events in
`sfx_profiles.BOSS_SFX`) on the wind-up / swing / impact frames; the skills' Java sounds layer on the same ticks.
`/aftrhrs debug anim skill <targets> <skill>` casts an ability (checks).

### Procedural layer (`client/entity/ArcanaGeoRenderer.procedural`, `anim.procedural`)

Head turns toward where the creature looks (clamped `look_yaw` 55 / `look_pitch` 35; off while lying), a slow breath on
`body` (scale, `breath` 0.012; deeper and slower when lying), an idle tail sway along the `tail` chain (`tail_sway` 5 deg;
smaller when lying), an ear twitch every few seconds (`ear_twitch`). Each runs only where the bones exist and the base
clip does not already drive them (`drv`). **No blinking anywhere**: `gen_creatures` opens eye-shutting keys outside
sleep-type clips (`animtags.SLEEPY`: sleep*, lie_down, wake_up, nod_off, idle_sleepy, walk_sleepy, idle_rain,
idle_sunbeam, idle_box, purr_melt, idle_bed, knead, snore_nap, death, dormant, burrowed, awaken, sit_lie_down,
lie_sit_up) and records them in `anim.unblinked` (Wukong's idle / taunt); `anim_check` fails on any left.

### Authoring clips (phase 2): `tools/clips/<model>.py`

```python
from animkit import Clip, keys, pulse, step, wave, overshoot, decay, posture_set, transition, held_pose

TAGS = {'paws_crossed': {'p': 'lying'}}                 # tag overrides (merged over the derived ones)
STATES = {'sit': 'sit'}                                 # state -> clip
TRANSITIONS = {'sitting>lying': 'settle'}               # posture pair -> clip
IDLE = {'standing': [{'clip': 'idle_look', 'w': 3, 'min': 5, 'max': 10}],
        'sitting': [{'clip': 'sit_groom', 'w': 1, 'cd': 30, 'boost': {'owner_near': 2}}]}
PROCEDURAL = {'tail_sway': 8, 'tail': ['tail', 'tail_tip']}
GAIT = {'run_on': 0.2}

def clips(m):                         # m: animkit.ModelView (generated clips, Blockbench convention)
    c = Clip(m, 'idle_look', 4.0, 'loop')          # once | loop | hold
    c.copy('idle')                                  # start from the idle's tracks
    c.rot('h_head', lambda t: (0, 25 * wave(t, 4.0), 0))
    m.put(c)                                        # later clips in this module can sample it
    return [c] + posture_set(m)                     # posture_set: sit_down / stand_up / lie_down / wake_up from the poses
```

`Clip.rot/pos/scl(bone, fn(t) -> (x, y, z), add=False)`, `copy(clip)`, `blend(bone, pose_a, pose_b, t0, t1)`,
`blend_all`, `follow(bone, src, lag)`; `m.pose(clip, t | 'end')`, `m.bones`, `m.find('head', 'tail')`; curves `wave`,
`pulse`, `step`, `keys`, `overshoot`, `decay`. Baked at 20 fps, loops seamless. Values are Blockbench convention
(x+ pitches the front up, y+ turns towards -x, z+ raises +x; position in px). Never edit geometry or textures; keep the
mod's own motion style (study the model's idle / walk / happy); no eye closing outside sleep-type clips.

Loop: `python tools/gen_creatures.py && python tools/anim_check.py` (fails on errors), look at it with
`python tools/anim_sheet.py out.png "idle@0,sit@end,sleep@1" <model> ...` (headless bb-render contact sheet of the
generated clips; `anim_preview.py` writes the scratch .bbmodel it renders, the source is untouched), then in game:
`/aftrhrs debug anim trace|walk|stay|nap|play|tame|info <targets> ...` (op) with `-Daftrhrs.animTrace=true` on the client.
`run-server/anim_session.sh <devscript> <tag>` runs the dev server (port 25715) and a client with a DevScript and the
trace on, then stops both (logs in /tmp/anim/).

### Wildlife / livestock / undead idle director (`WildBrain`, phase 2 p2-wild)

The untameables have no `MoodBrain`; `WildBrain` plays their `anim.idle` pools (same entry format) every 2.5 - 7 s:
the pool named after the creature's activity clip (`graze`, `dormant`, `swim`, a perched gull's `idle`), else
`standing` when free, `lying` while a wildlife rest (life `lie`) holds (loops only: they swap the held lying clip and
go back to it), `notice` once when a player walks up (10 blocks, undead 12, a sneaking player 4), `walk` for walk-cycle
variants kept min..max s (tag them `{'move': True, 'gait': 'walk'}`). Never the same pick twice in a row; while moving
only overlay one-shots. Extra conditions: `dusk`, `young`, `spooked`, `herd_near[:r]` / `adult_near[:r]` /
`young_near[:r]` (same species family), `rub_near` (log / fence / wall beside it), `var:<name>`; a pick whose
condition found a herd mate / player / post turns the head to it while it plays. Still-only one-shots hold the stroll
goal (`lingering()`). Wildlife lying down / getting up go through `lie_down` / `stand_up` (`standing>lying`,
`lying>standing`) and the creature waits for `stand_up` before walking off. Undead one-shots are overlays by default:
tag whole-body idles `{'layer': 'base', 'move': False}` (`clips/_herd.py undead_tags`). Shared builders for herd
animals and humanoid undead: `tools/clips/_herd.py` (`Quad`, `Walker`, `hurt`).

## Content (ex-Oraxen items, blocks, furniture)

Every Oraxen item, custom block and furniture piece is native mod content with its **Oraxen id kept**:
`aftrhrs:<oraxen id>` (`aftrhrs:arcana_goob_coin`, `aftrhrs:arcana_oak_chair`). Code: `content/`.

### Regenerating
- `python mod/tools/gen_content.py` (stdlib only) reads `plugins/Oraxen/items/*.yml`, `Oraxen/recipes/*.yml`,
  `Oraxen/pack/{models,textures}`, `arcana_items/tab_layout.py` and the 26.3 client jar from the Loom cache, and
  rewrites (clears first) `assets/aftrhrs/{models/{arcana,feather,block,gen},textures/block,blockstates}`, its own `items/*`
  (spawn eggs stay: gen_creatures), `lang/en_us.json` (the other `en_us.<area>.json` parts stay),
  `data/aftrhrs/{content/content.json,loot_table/blocks,recipe,tags}` and the content entries of
  `data/minecraft/tags/{block/mineable/*,block/wool,item/*}` (hand-written tags such as `block/climbable` and the
  entity tags stay). Variant models (lit hearth, playing gramophone, stages, unlit/closed) assign their own elements
  to parts. Rerun it after any Oraxen/generator change.
- It also reads the in-repo content root `mod/content/{items/*.yml,pack/{models,textures}}` (Oraxen layout, read after
  Oraxen and winning on equal ids/paths; `--content` overrides the path): content whose source lives in git. Today that
  is the 75 newer fishing species, built by `python mod/tools/fishart/build.py` (models, textures and the items yml;
  `tools/fishart/sheet.py` renders contact sheets). Fish species items (`arcana_fish_<species>` with a species in
  `feather/fishing.yml`) get a size dispatch in their item model: by display context and custom model data float 0
  (the weight's length scale, `FishWeight`), see docs/fishing.md "Size".
- Building-block sets that live in git (snow, ice, blue ice, calcite: polished / bricks / chiseled / tiles + stairs,
  slabs, walls; cracked calcite bricks, calcite wall) come from `python mod/tools/blocksets/build.py` (Pillow): textures
  (`blocksets/textures.py` + `paint.py`, drawn in code), cube_all models, `content/items/arcana_block_sets.yml` (`tab:
  arcana_blocks` puts them in Building Blocks) and `content/recipes/{shaped,stonecutting,furnace}.block_sets.yml`
  (in-repo recipe files are `content/recipes/<kind>[.<part>].yml`). Block entries may set `friction` (polished ice
  0.98, polished blue ice 0.989) and `map_color`. `python mod/tools/blocksets/igloo.py` rebuilds the igloo dome
  (`data/minecraft/structure/igloo/top.nbt`, from the vanilla template) out of snow bricks; `IglooPieceMixin` fills a
  basement-less igloo's hatch with polished snow. Tests: `gametest/content/BlockSetTests`.
- `python mod/tools/check_content.py` checks every blockstate/item model reference, parents, textures, face counts,
  the -16..32 element range and atlas mixing (what a 26.x client rejects).
- `python mod/tools/verify_render.py --all` (numpy + Pillow + `arcana_items/plants3d/mcrender.py`) proves every
  generated furniture block (all parts, all rotations) has exactly the geometry of the Oraxen display entity
  (scale/translation/yaw); without `--all` it renders side-by-side PNGs into `mod/build/verify_render/`.
- Helpers: `tools/miniyaml.py` (YAML subset), `tools/mm.py` (MiniMessage to styled segments), `tools/geo.py`.
- Textures: pack textures are copied (deduped by content) into the block atlas as `aftrhrs:block/<pack path>`;
  vanilla `item/`/`entity/` sprites used by models are copied to `aftrhrs:block/vanilla/...` so no model mixes atlases.
- Names/lore: lang keys `item.aftrhrs.<id>`, `item.aftrhrs.<id>.lore.<n>`, `container.aftrhrs.<id>`,
  `itemGroup.aftrhrs.<oraxen file>`; colors/gradients are applied from the table (`Texts`). Creative tabs follow
  `tab_layout.py` (one tab per Oraxen file) plus Bank and Power.

### Kinds (content.json `kind`)
| kind | what |
|---|---|
| `item` | plain item; Oraxen `components` become vanilla data components (food, consumable, equippable hats, durability, use_cooldown, use_remainder, glint, attributes); base material behavior kept (tool tiers + tool tags, fishing rods incl. cast model, trident, carrot on a stick, cod food). `commands`, `backpack` (inventory in the item's `container` component, blocked items) and `bottledexp` (XP flask) are implemented in `ArcanaItem`. |
| `furniture` | `FurnitureBlock` (one block per piece) |
| `block`, `stairs`, `slab`, `wall` | ex note-block / shaped custom blocks: real blocks with Oraxen hardness, tool, drops, sounds, light (walls: in-repo block sets, `#minecraft:walls`) |
| `crop` | `aftrhrs:arcana_<crop>_crop`, `age` 0..3 (stage models), on farmland, no growth of its own (farming port); drops seeds (+1 produce when ripe) unless a hook claims the break |
| `crop_stage` | the old `arcana_crop_<crop>_stage<n>` ids: hidden items; `ArcanaContent.stateFor(id)` = the crop block at that age |
| `alias` | `arcana_<stone>_hearth_lit`: block item of the hearth with `lit=true` (one block, two items, like Oraxen) |

### Furniture blocks
- **Placement** follows Oraxen `limited_placing`: clicked top face = floor, bottom face = roof, side = wall (wall
  pieces face away from the wall). Model north faces the placer. 4-way `facing` (VERY_STRICT); STRICT/NONE pieces
  get `rotation` (0..15, 22.5 degrees each; 8-way pieces use even values) with the 22.5/45/67.5 degree geometry baked
  into generated models (blockstate variants only rotate in 90 degree steps). Multi-block pieces are always 4-way.
- **Multi-block**: Oraxen barrier cells + hitbox cells become `part` states (part 0 = origin). Breaking any part
  breaks the piece and drops the origin's loot; placement needs all cells free; pistons can't move furniture;
  explosion resistance 1200 (Oraxen furniture ignored explosions). Geometry outside the -16..32 model range of the
  origin (3-4 block tall lamps, scaled pieces) is rendered by the part it sits in.
- **Shapes**: barrier cells = full-block collision; Oraxen interaction hitboxes = outline only (no collision, as
  before). Oraxen's offsets mirror model x; the generator picks the frame that matches the geometry.
- **Right-click order**: module hooks (sneak + item clicks, which vanilla never offers to a block, reach the hooks
  through a late-phase `UseBlockCallback`; hooks and furniture break hooks run after the default phase, so claims veto
  first), then sneak + empty hands rotates (single-block pieces; replaces Oraxen's plain
  right-click rotation), storage opens, gramophone takes/ejects a disc, seat, toggle.
- **Seats**: Oraxen `seats` heights; an invisible item display tagged `aftrhrs_seat`, removed when empty; the player
  is put on top of / in front of the piece when standing up. Nearest free seat to the click.
- **Storage**: `STORAGE` = block entity container (rows/title/sounds, comparator output, `open` state while
  viewed); `SHULKER` (sack) keeps its contents in the dropped item; `PERSONAL` = one inventory per player (player
  Pdc `aftrhrs:personal_storage`); `ENDERCHEST` = the ender chest; `DISPOSAL` = trash (deleted on close).
- **Light**: Oraxen `lights` per part (lights outside the piece go to the nearest part).
- **Stages** (4 planters): `age` with stage models, random-tick growth from Oraxen delay/probability/light boost,
  bone meal chance, per-stage drops. Harvest on right-click stays with the tools port (hook).
- **Waterlogged**: every furniture block.

### State toggles
| property | pieces | how |
|---|---|---|
| `lit` (default on) | every non-interactive piece with Oraxen `lights` (90: lanterns, chandeliers, sconces, candle holders, lamps, lamp posts, street lamps, fire pits, tiki torches...) | right-click; the unlit model drops flame elements and swaps lit textures (campfire logs, copper bulbs, furnace fronts...); glow textures with no vanilla off state (lantern glass, froglights, sea lantern, glowstone, shroomlight, fireflies) get a dimmed copy in `textures/block/unlit/`; light 0; glowing elements (`light_emission`: floor lamp, crystal lamp, candelabra) stop glowing when unlit |
| `lit` (default off) | the 12 stone hearths | flint and steel / fire charge lights, shovel or water bucket douses (replaces the Feather `hearth-*` swap rules); lit model = the old `_lit` piece |
| `lit` | `feather_coal_generator` (2 tall) | set by the power module (no click toggle); lit adds the `fx_on` overlay (multipart) |
| `open` (default open) | 12 wood shutters, 16 curtains | right-click; closed models are generated (shutter panels swing shut, curtains drawn) |
| `open` | 88 storage pieces | true while a player has it open (the pack has no open models) |
| `has_record` | gramophone | a disc plays like a jukebox; playing model while it has one |
| `powered` | `feather_encased_fan` | for the power module (6-way `facing`; propeller is a static overlay) |
| `age` | 4 planters | growth stages |
| `waterlogged` | all furniture | water |

### API (`ArcanaContent`)
- `item(id)`, `stack(id, count)`, `block(id)`, `stateFor(id)` (crop stage / lit hearth ids map to states),
  `id(ItemStack)`, `id(BlockState)` (Oraxen-style id or null; `oraxen:`/`aftrhrs:` prefixes accepted on input),
  `isFurniture(state)`, `origin(state, pos)`, `spec(id)` (`FurnitureSpec`: parts, seats, storage, toggle...),
  `entry(id)` (raw table JSON), `entries()`, `tab(oraxenFile)`.
- `place(level, origin, id, front)` / `placeState(level, origin, state)` place a piece with its parts;
  `replace(level, pos, newId)` swaps a piece in place keeping orientation/lit/open (copper wax/scrape/aging).
- `hook(id | Predicate<String>, FurnitureHook)`: `onUse(ctx)` (server, before the block's own behavior; non-PASS
  claims the click), `onBreak(ctx)` (false cancels), `onPlaced`, `onRemoved`. `FurnitureContext` = id, level,
  origin, origin state, clicked pos, player, hand, held stack, hit, `facing()`, `yaw()`.
- Block entities: `aftrhrs:furniture_storage` (`FurnitureStorageBlockEntity`, a `Container`), `aftrhrs:gramophone`.
- Module block entities: `blockEntity(Predicate<String> ids, factory)` before `init()` makes those pieces
  `FurnitureEntityBlock`s whose origin gets the module's block entity (implement `FurnitureTicker` to be ticked);
  `blocks(ids)` after `init()` for its `BlockEntityType` (see `feature/furniture/FurnitureEntities`).
- Migration: Oraxen furniture yaw -> `facing`/`rotation` (rotation = round(yaw / 22.5) & 15, model north faces the
  placer); use `place(...)` so parts are created.

### Known gaps
- Relic mechanics (`aura`, `energyblast`, `thor`, `lifeleech`, `witherskull`, `spear_lunge`, `knockback_strike`,
  `misc`) are kept in each entry's `mechanics` for the tools/relics port; only the spear/trident charging models
  are wired (item model `using_item`).
- `backpack_cosmetic` (backpack worn on the back) needs a client render layer; `glowing` (relic item glow) not done.
- The fan propeller does not spin (needs a block entity renderer); crops do not grow on their own by design.
- Oraxen `COD`-based fish are edible like cod but don't smelt into cooked cod (no vanilla recipe matches them).
## Pets (`feature/pets`, branch `fm-pets`)

Port of Feather's pets (`PetsModule`, store, menu, stable, codex, perks, variants, breeding, Work Here, guard,
Companion bridge) plus the pet whistle. `data/pets.yml` and `data/pets-codex.yml` keep the Paper format; records keep
the **MythicMobs id** as `species` (`ArcanaBlackCat`), mapped with `ArcanaEntities.byMythicId`.

- **Creature API, no polling**: cap = `CreatureEvents.CAN_TAME`, record = `TAMED`, stay = `STAY`, feed XP = `FED`,
  fallen = `DIED`, name tags / breeding treats / petting = `INTERACT` (a name tag renames through the record and its
  rules). The record is pushed onto the creature with `PetsModule.applyTo`: `setBond`, `setTint`/`setShiny`/`setVariant`
  and the custom name (shiny ✦ + the Affection name color are now on the creature's own name tag).
- Entity tag: Pdc `feather:pet` = record id (same key as Paper). Plushies: native `aftrhrs:arcana_plush_*` (looked
  up by id, null-safe; unconverted `oraxen:id` custom data still matches). Whistle: `aftrhrs:arcana_pet_whistle`,
  `tools.yml pet-whistle` (the `ARCANAWHISTLE` signal is now direct: unstay, come over, greet).
- Cross-world follow: `FeatherEvents.PLAYER_TELEPORTED` (other level or > `follow.far-distance`) and `PLAYER_RESPAWN`;
  unloaded pets are fetched with a `PORTAL` chunk ticket (expires on its own).
- **Migration** (`PetMigration`, a `Legacy.converter`): a MythicMobs creature that loads as its native type gets
  `NoAI`/`Silent`/invisibility/held items cleared, `mythicmobs:owner` → owner + tamed, `mythicmobs:variables`
  (`stay`, `arcana_bond`, `petname`, `arcana_namecolor`, saved ability ints like `gift_armed`) → creature state; the
  `mythicmobs:*`/`modelengine:*` tags are dropped. One tick later the record (by `feather:pet` or entity id) wins;
  tamed creatures without a record are adopted with their old name, bond and color. Stabled/fallen records whose
  entity still loads are removed as leftovers, as on Paper.
- Services provided: `Hooks.PetProgress` (quests, calendar, chat games, advancements), `Hooks.Pets` (cap, whistle),
  `Hooks.PetWork` (farming's Sporelings). Read: `Hooks.Quests` (tame/codex/bond progress, `bondXp`),
  `Hooks.Farmhand` (farming's crops for the farmhand job), `Hooks.Bestiary` (codex tab, wildlife).
- New shared bits: `FeatherEvents.EFFECT_ADD` (+ `LivingEntityEffectMixin`; pets refuse strangers' harmful potions),
  `MobPersistenceAccessor` (a released pet despawns like a wild one again).
- Admin: `/pets admin list <player>` (new) prints every record with its live entity (migration checks).

## Coast & Harvest (fishing, farming, civic: `feature/fishing`, `feature/farming`, `feature/civic`, `feature/coast`)

- **Shared helpers** (`feature/coast`): `Coast` (content ids, give/take, sounds/particles by name, claims, biome keys
  and tags, `followingNearby(owner, "ArcanaX"|id, radius)` = tamed, following, not working), `SpotData` (state of a
  furniture block in the chunk Pdc `aftrhrs:coast_spots`, keyed `x,y,z`, holding the old base-entity PDC keys),
  `BlockIndex` (loaded origins of chosen furniture blocks, palette-filtered chunk scans), `Parts` (unsaved item/text
  displays tagged `feather:*_part`; strays are discarded on load).
- **Events** (FeatherEvents, mixins in `mixin/coast`): `FISH_LURE` (wait modifier), `FISH_BITE`, `FISH_ESCAPE`,
  `FISH_REEL` (false keeps the hook in), `FISH_CATCH` (mutable loot), `ITEM_CONSUMED`, `CRAFT_RESULT`, `VIBRATION`.
  `FishingHookAccessor` exposes the bite timers.
- **Hooks**: provides `FishRarity` (fishing), `Farmhand` and `Crops` (farming; `Crops` is the scythe's old
  ArcanaTools.CropHook); reads `PetWork`, `WildlifeHotspots`, `Quests`, `Bank`.
- **Crops** grow on Feather's per-chunk timers from the chunk Pdc records (`feather:farm_crops`/`farm_soil`, Paper's
  strings), the crop block's `age` is the stage; a record whose block is still an Oraxen tripwire becomes the crop
  block at the recorded stage when its chunk is first processed. Crop blocks are in `minecraft:maintains_farmland`.
  A player break is claimed by the farming hook (claim check, custom drops, Canopip replant); explosions, water and
  pistons use the crop block's loot table.
- **Migration**: `Legacy.onFurniture` restores compost bins, seaweed lines, plaques/tanks (`feather:fish_display`
  byte arrays via `PaperItems`) and fountain basins (goal link, goal light); crab pots and crops need nothing (chunk
  Pdc). Old part displays are discarded by `Legacy.converter`s.

## Wildlife, altars and bosses (`feature/wildlife`, `feature/altars`, `feature/bossfx`, branch `fm-wildlife`)

- **Wildlife** (`modules.wildlife`, wildlife.yml keyed by MythicMobs ids): tracks loaded `ArcanaWildlife` / undead by
  `species().mythicId`; harvest timers live in the entity Pdc under Paper's `feather:wild_*` keys. Stations are content
  furniture claimed with `ArcanaContent.hook`, state in the chunk Pdc (`feather:wild_stations`, one entry per origin);
  migrated stations get their state back through `Legacy.onFurniture`. Provides `Hooks.WildlifeHotspots` (fishing) and
  `Hooks.Bestiary` (its menu is part of the creature codex). Meal buffs follow `FeatherEvents.ITEM_CONSUMED`.
- **Creature codex** (docs/codex.md, branch `codex-unify`): the taming guide and the pets codex merged into one menu
  listing every registered `ArcanaEntities` type (young and `form-of` types as forms of their parent). Entries are built
  from the species data; text from guide.yml (taming, generated outside this repo) and the hand-written
  `feather/codex.yml` (tabs, realms, wildlife / monster / boss text); boss offerings from altars.yml. Kept by the pets
  module (`feature/pets/Codex`, progress in data/pets-codex.yml), provided as `Hooks.Codex`; `feature/codex/CodexModule`
  (`modules.taming-guide`) is the front door (/codex + aliases /tamingguide /taming /arcanaguide /creatures, the hub
  button). Mod screen `client/codex/CodexScreen` (key `key.aftrhrs.codex`, unbound). `CodexTests` fails when a
  registered type has no complete entry.
- **Altars** (`modules.boss-altars`, altars.yml + altars-data.yml unchanged): the Summoning Altar is the content block
  `arcana_summoning_altar`; bosses spawn as native types (`ArcanaEntities.byMythicId`). Provides `Hooks.AltarArenas`;
  won fights call `Hooks.BossBoard.recordKill` and `Hooks.Quests.bossDefeated`. Party scaling = a `feather:party_scaling`
  max-health modifier plus **`FeatherEvents.DAMAGE_AMOUNT`** (new, `LivingEntityDamageMixin`: scale any living entity's
  incoming damage before armor; chained modifier). `BossRelics` (Cinderplume / Galeheart, tools.yml `relics.*`, follows
  `modules.arcana-tools`) is registered on its own (`feature/altars/BossRelics`).
- **Boss skills**: `entity/ability/BossSkills` (Phoenix, Roc, Wyrm, Colossus + extra attacks for their unused clips),
  `WukongSkills` (brain, phases, parry, clones), `PoliceSkills`, shared helpers `BossKit`. The Wukong hair clone is its
  own type `aftrhrs:wukong_clone` (gen_creatures.py: the wukong model at scale 0.78 with its tint); species json carries
  the MythicMobs `faction`. **Boss FX** (`modules.boss-fx`): `feature/bossfx/BossFx` quake / fissure / pillar with
  never-saved block/item displays copying real blocks (no-ops while the module is off); `Allies`: same-faction mobs never
  target or hurt each other.

## Storage network, holograms, glyph fonts (branch `fm-storage`)

### Storage (`feature/storage`, modules.storage)
- Real blocks `aftrhrs:feather_storage_terminal`, `aftrhrs:feather_crafting_terminal`, `aftrhrs:feather_inventory_connector`
  (`StorageContent`, registered at mod init by `StorageModule.factory`; terminals face their placer). Assets, loot
  tables, the three recipes and tag entries come from `tools/gen_storage.py`, which `gen_content.py` runs at its end
  (they live in dirs gen_content clears). Recipes carry the load condition `aftrhrs:feather_config`
  (`keys: [modules.storage, storage.recipes]`, reads `aftrhrs/config.yml` at data-pack load). Item names/lore are
  translatable with English fallbacks (no lang entries needed). No block entity: a block's kind and facing are all
  the state there is; windows check `Terminal.At.present()`.
- Same commands/permissions/messages (`/storage give`, `/quickcraft`, `/craft` opens Quick Craft for
  `feather.storage.quickcraft` via `FeatherEvents.COMMAND`). Player choices in player Pdc (`feather:storage_sort`,
  `feather:quickcraft_amount`, `feather:quickcraft_network_first`). Chest windows (`View.Menu`) handle clicks inside
  the click packet; Companion players get `StorageBridge` (`storage`, `storage_delta`, `storage_closed`,
  `st_take/put/deposit_all/fill/close`, `craft_fill` + results; protocol unchanged). Crafting tables Feather opens
  are `WorkbenchMenu`s; other code can mark its own with `StorageModule.trustWorkbench(menu)` so JEI "+" may fill it.
- Mailboxes are left out of networks through the new `Hooks.Mailboxes` (mail port: provide it).
- Migration: chunk Pdc `feather:storage_blocks` records become the native blocks a tick after the chunk loads (carrier
  block still there; record dropped either way); `feather:storage_display` entities are discarded; old items
  (`PublicBukkitValues feather:storage_item`) convert via the new `LegacyItems.tagged(key, value -> path)`.

### Holograms (`feature/holograms`, modules.holograms)
- `/holo` (alias hologram/holograms), `feather.holograms.admin` / `.console`, `holograms:` config keys and
  `aftrhrs/holograms.yml` unchanged (world names kept Paper-style, resolved with `Worlds`). Editor = core `Dialogs`.
- Lines are vanilla Text/Item/Block display + Interaction entities, created only while someone is near, tagged
  `FeatherEvents.TRANSIENT_TAG` (never saved) and Pdc `feather:hologram`. Per-player pages and per-viewer lines use
  `FeatherEvents.ENTITY_VISIBLE` (Paper's showEntity/hideEntity): `Visibility.hideByDefault/show/hide` re-runs the tracker.
- `#ITEM: oraxen:<id>` → `ArcanaContent`; `#ENTITY: mythic:<MythicId>` → `ArcanaEntities.byMythicId`; also any
  `namespace:mob`. `#HEAD: <name>` is resolved by the client (no server Mojang lookups).
- Click actions: `console` actions should use `{uuid}`; one that uses `{player}` is skipped (logged) when the clicker's
  name isn't a plain username (`[A-Za-z0-9_]{1,16}`), so offline-mode/proxy names can't inject command text.
- API for modules: `feather.module(HologramsModule.class)` → `create`, `createTemporary(owner,id,Loc,lines)`, `setLines`,
  `setResolver(h, ServerPlayer -> TagResolver)`, `move`, `delete`, `deleteOwnedBy`. Bank chest holograms read `Hooks.Bank`.
- Damage/heal numbers: Fabric AFTER_DAMAGE (+ AFTER_DEATH for the killing blow), `FeatherEvents.HEAL` (Bukkit regain
  reasons SATIATED/REGEN/MAGIC_REGEN/MAGIC/CUSTOM), `FeatherEvents.CRITICAL_HIT`. Settings toggle via `hub().addToggle`.
- Migration: a `Legacy.converter` discards Paper entities tagged `feather:hologram` / `feather:hologram_mythic`.

### Glyph fonts (ex Oraxen glyphs)
- `tools/gen_glyphs.py` (stdlib) copies from `plugins/Oraxen`: `assets/feather/font/hub.json` + `textures/hub/*`
  (panels, moon phases U+E200+phase, shift spaces), `assets/feather/font/hub_banner.json` (the 16 banner frames as
  U+E000..U+E00F; the hub sends U+E000 and the client mixin `BannerGlyphMixin` swaps the frame at 6 fps, as Oraxen's
  text shader did) and `assets/aftrhrs/font/glyph/guide_<x>.json` / `guide_icon_<x>.json` (one font per taming-guide
  glyph, U+E000). `HubArt` and `GuideGlyphs` pick them up automatically. `feather_stall_gui` ships with the shop port.
  Oraxen's own sample glyphs (chat tags, emoji, `required`, `interface`, the spinner) were never used by Feather: not shipped.

### Dropped (Oraxen/ModelEngine bridges)
- `OraxenItemHook` (Oraxen item by id, ids list): every user (holograms, boss FX, places icons, staff `/i`, advancement
  icons, storage skins, power items) reads `ArcanaContent.stack(id, n)` / `ArcanaContent.entries()` instead.
- `OraxenStaleMarkers` (cleared Oraxen furniture records left on non-barrier blocks): there are no barrier records any
  more; furniture is real blocks and the furniture migration removes old barriers.
- `PackBridge` + `res/packbridge.yml` (zipped ModelEngine's pack into Oraxen's and rebuilt it): models, textures and
  fonts ship in the mod jar. `packbridge.yml` is no longer bundled; `modules.modelengine-pack-bridge` is kept in
  config.yml only so old configs stay valid, and nothing reads it.

## Parity audit and deployment (branch `fm-audit`)

- `PARITY.md`: the checklist against the Paper server (commands, module toggles, messages, config keys, data files,
  MythicMobs ids, Oraxen ids, Companion messages, Bukkit listeners) with the open gaps and owners at the top.
  Regenerate its tables with `python mod/tools/parity.py` after merges; hand-checked verdicts live in the script.
- `DEPLOY.md`: moving the live server from Paper to Fabric (backup, server mods, first boot, rollback, clients).
- `Modules.java` is in `Feather.onEnable`'s exact order; the `companion` module is registered there
  (`Companion.module()`), right after auth, and every fm-furniture module at Paper's place.
- **Module-owned recipes** (fm-qa): `RecipeInjector.own(key, holder|null)` puts one recipe built from config into the
  server's recipe list (replacing a datapack recipe with that id; null removes it), keeps it across datapack reloads
  and resends recipe lists/books. The Chest Key `aftrhrs:chest_key` is built this way from `locks.key.recipe`,
  `ingredients`, `material` and messages.yml `locks.key.*` while `modules.locks` runs (no datapack JSON any more).
- Lang files: `en_us.json`, `en_us.entities.json` and the content parts are generated; hand-written strings go in
  their own `en_us.<area>.json` (`en_us.trade.json`, `en_us.relics.json`), folded in by `processResources`.
## Furniture modules: curios, gadgets, power, tools, relics (branch `fm-furniture`)

- **Block entities** (`feature/furniture/FurnitureEntities`, ids from the bundled curios/gadgets/power.yml):
  `aftrhrs:curio` (pedestal item, hat, statue item, flower-box plants, doormat text + hidden item), `aftrhrs:gadget`
  (drink/cookies/caged mob, spike coating), `aftrhrs:power` (owner, fan push/pull, generator fuel + burn). Client
  renderers in `client/furniture` draw what Paper drew with display entities (same matrices as CurioDisplays: model
  space through the item display's 180 degree turn). Clients get only what they draw (no hidden doormat item, the
  caged mob only after its HMAC verifies).
- **Curios** (`feature/furniture/curios`): sneak + empty-hand rotation is content's; curios.yml `rotate.*` is unused.
  End crystals on pedestals feed `FeatherEvents.ENCHANTING_POWER` (bookshelf count of a table; `EnchantmentMenuMixin`),
  so offers, clues and the enchanting all agree (Paper re-rolled offers). Observer pulse = a shape update to the
  neighbors. The hat stand's wobble tilts the hat only (the stand is a static block model).
- **Gadgets** (`feature/furniture/gadgets`): item-side state keeps Paper's custom-data keys (`feather:gadget_kind`,
  `_fluid`, `_units`, `_item` = Paper item bytes, `_mob` = Paper entity bytes, `_mob_sig` HMAC-SHA256 with
  `data/gadgets.key`, `_mob_type`). A stateful jar/cage drops as exactly one piece (player break: the module removes
  it; explosions: `LootTableEvents.MODIFY_DROPS`). Ropes are `minecraft:climbable` (data tag) plus walk-to-climb.
- **Power** (`feature/furniture/power`): generators set `lit` on both parts, fans `powered`; a powered fan's
  blockstate hides the static propeller (gen_content `static_when`) and `PowerRenderer` spins it. Fans face the way
  the placer looks. Generator window = one-row chest menu with a fuel-only slot.
- **Migration** (`migrate/OraxenFurniture`, `OraxenStorage`, `JavaSer`, `PaperItems`; `feature/furniture/FurnitureLegacy`):
  every Oraxen furniture entity becomes its block (next tick, ordered: fountain tiers/spouts after basins, bottom-up),
  hitbox/seat entities and barrier/LIGHT blocks go; `Legacy.onFurniture(level, origin, oraxenId, bukkitValues, yaw)`
  hands each module the old base entity's PDC. Oraxen storage (`oraxen:storage`, `oraxen:personal_storage`) is read
  with a class-free Java-serialization parser (2 MB cap, element/node budgets, cycle checks; unreadable data kept as
  `aftrhrs:legacy_storage`). Power records come from chunk PDC `feather:power_blocks`. Stray overlay entities
  (`feather:curio_part`, `feather:gadget_owner`, `feather:power_display`) are removed.
- Tools / relics: see `feature/tools`, `feature/relics` (merged from `fm-furniture-tools`): scythes go through
  `Hooks.Crops`; area tools and relic effects check BUILD claims per block / per target; Cinderplume and Galeheart
  belong to `feature/altars/BossRelics`.
- **Worn backpacks** (`feature/relics/BackpackWear`, branch `backpack-wear`, docs/arcana-tools.md "Backpacks"): the
  worn stack is the `aftrhrs:worn_backpack` attachment (persistent, copy-on-death). `mixin.relics.InventoryMenuBackSlotMixin`
  appends the back slot to every `InventoryMenu` as index **46** (after the offhand, 45) on both sides; code that walks
  `inventoryMenu.slots` must not assume 46 slots. Graves take it via `Hooks.BackSlot` (grave entry slot 41);
  `mixin.relics.PlayerBackpackDropMixin` drops it with the death loot when no grave did. Payloads: `aftrhrs:backpack`
  (who wears what, unchanged), `aftrhrs:backpack_key` (serverbound key), `aftrhrs:back_slot` (settings).
  Settings: tools.yml `backpacks.*`. Tests: `gametest/relics/BackpackTests`.
- **Cosmetic armor and the hat slot** (`feature/cosmetics`, `modules.cosmetics`): the `aftrhrs:outfit` attachment
  (persistent, copy-on-death, synced to the player and everyone tracking them) holds the hat, four cosmetic armor pieces
  and the hide switches. `InventoryMenuBackSlotMixin` appends five more slots right after the back slot: **47** hat,
  **48-51** cosmetic head, chest, legs, feet, so `InventoryMenu` has 52 slots with the mod. Graves take them via
  `Hooks.Outfit` (grave entry slots 42-46); `PlayerBackpackDropMixin` drops what's left with the death loot. Payloads:
  `aftrhrs:cosmetics_settings` (S2C, on/off) and `aftrhrs:cosmetics_toggle` (C2S, flip a hide switch). Client:
  `client/cosmetics/CosmeticsClient` (render-state overrides from `AvatarRendererCosmeticsMixin`, the side panel left of
  the inventory, the hat slot frame); the panel steps aside while the recipe book is open, JEI keeps clear of it, and
  `RecipeBookScreenOutfitMixin` stops clicks on it from counting as clicks outside the window. Cosmetic slots never
  give stats (nothing reads them but rendering). An elytra always shows. Tests: `gametest/econ/CosmeticsTests`.

## Releases & auto-update (branch `fm-updater`)

Players and servers get the mod from the public releases-only repo **`eterniastudio/aftrhrs`** (no source there:
README, LICENSE and releases). The old Companion repo `eterniastudio/aftrhrs-companion` is frozen: never publish
anything there again (old Companion installs would pull it).

**Signing key.** A new Ed25519 key, separate from the Companion's: private
`~/.config/aftrhrs/aftrhrs-release-ed25519.pem` (next to the old `companion-release-ed25519.pem`), public
`pTOmhfKsBFTF7NDz1kFMtW6ssXf3aa7MAhUObr+OaHU=` (`update/Updater.PUBLIC_KEY`). Separate keys mean an old Companion can
never accept a release of this mod and this mod can never accept a Companion manifest, even if a file lands in the
wrong repo. **Back the private key up**: without it no update can be published that installed copies accept (a new key
would mean everyone reinstalls by hand once).

**Publishing.** Bump `version` in `gradle.properties`, then from `mod/`:
`python release.py --notes "What changed"`. It reads the version, builds the jar, resolves the GeckoLib
(`geckolib_modrinth_id`) and Fabric API (`fabric_version`) files on Modrinth for `minecraft_version` (download URL,
fabric.mod.json id/version, SHA-256, size; cached in `mod/release/cache/`), writes and signs
`mod/release/v<version>/manifest.json` + `.sig`, refuses a version not newer than the latest release, and creates the
GitHub release `v<version>` (jar + manifest + sig, marked latest) with `gh`. `--dry-run` does everything but the
publish; `--no-build` uses the jar in `build/libs`. Needs Python `cryptography` and `gh` logged in as eterniastudio.
The first release (1.0.0) is what players install by hand; every later one reaches them by itself.

**Manifest** (format 2; `update/Manifest`): `repo`, `version`, `notes`, `minecraft` (the jar's `~26.3` predicate),
`min_minecraft`, `min_loader`, `mod` {id, version, name, url, sha256, size} and `dependencies` [{id, version, min, name,
url, sha256, size, source}], where `min` is the jar's own `depends` minimum (`>=5.5.7` → `5.5.7`; Fabric API `*` →
none). `manifest.json.sig` = base64 Ed25519 over the exact bytes. The mod accepts only: a valid signature, format 2,
this repo, the mod named `aftrhrs-<version>.jar` from `<releases>/download/`, dependencies from `cdn.modrinth.com/data/`
or `github.com/`, plain jar names, sizes up to 256 MB.

**Engine** (`update/`, common code: runs on clients and dedicated servers):

- `Updater`: checks `github.com/eterniastudio/aftrhrs/releases/latest/download/manifest.json` (+ `.sig`; not the API,
  so no API rate limit) 8 s (client) / 20 s (server) after start, then every 15 min on a client and every 6 h on a
  server; offline → again in 30 min (or sooner); HTTP 429/403 → waits `Retry-After` / `X-RateLimit-Reset` (at least
  15 min). Never moves to a lower or equal version. Checks keep going with an update staged: the status stays READY
  unless a newer release turns up, which is downloaded and replaces it.
- `Plan`: the **mods folder is the folder the mod's own jar was loaded from** (Fabric's mod origin: `mods/`, NoRisk's
  `custom_mods/`, ...). It puts the new mod jar there and moves out the running jar plus any other `aftrhrs` jar there
  that is older (e.g. a forgotten Companion jar). Per dependency: nothing if the installed one (loaded or in the folder)
  is the same or newer; otherwise the new jar goes in and every older copy *in that folder* (matched by its
  fabric.mod.json id) goes out. A dependency loaded from another folder (launcher-managed) is never touched: if it is
  still at least `min` it stays; if not, nothing is staged and the settings row / console say what to update by hand
  (a second copy would stop Fabric from starting). Unrelated jars are only read for their mod id. The release's
  `minecraft` / `min_minecraft` / `min_loader` must fit the running game, else nothing is staged.
- Download: `config/aftrhrs/updates/staged/<name>.part` → size + SHA-256 + fabric.mod.json id/version checked (and
  `net/axther/aftrhrs/AftrHrs.class` in the mod jar) → renamed; a jar already staged with the right hash is reused.
  Then the verified `manifest.json(.sig)` and `plan.txt` sit beside them; status READY; client toast
  "Update X downloaded / Restart the game to apply it." (again once on joining a world).
- Restart (client `update/Restart`): **Update to X & restart** (top right of the title screen and pause menu while an
  update is staged, and Restart now in HUD & Display → Updates) and **Update to X & rejoin** (on the disconnect screen
  of a server that runs a newer version; it waits for the download when needed). It writes `updates/relaunch.txt` (the
  working folder, then this game's own command: the JVM's input arguments minus a debugger agent, `fabric.*` /
  `log4j*` system properties a launcher wrapper or the dev launch injector set at run time, `-cp`, `KnotClient`, and
  Fabric's launch arguments with Minecraft's quick play swapped for the server or singleplayer world the player was in)
  and closes the game normally (`Minecraft.stop()`, which saves a singleplayer world). The exit hook starts the helper
  with that file: it waits for the game to exit, applies the plan, then starts the command and deletes the file (it
  holds the session token). Launcher wrappers (Prism's `EntryPoint` reads its arguments from stdin) are skipped by
  starting `KnotClient` directly. If the command can't be built, the game still closes and the update goes in.
  Tested in a dev client against a local signed mirror (`tools/updater_test.py mirror`, `-Daftrhrs.update.releases`,
  `-Daftrhrs.update.jar`, DevScript `restartupdate <marker>` / `kickscreen`): staged, swapped, reopened, rejoined.
- Apply (`Swap`, JDK classes only, no nested classes): at JVM exit, all or nothing: hashes re-checked, new jars copied in
  as `.part`, old ones moved to `updates/rollback-new/`, `.part` renamed to `.jar`; any failure undoes it. Windows keeps
  the running jars locked, so then `Swap.class` is copied to `updates/helper/` and run as a separate `javaw` process
  that waits for the game's PID to exit and applies the plan (log: `updates/update.log`). On success the replaced jars
  become `updates/rollback/` with `ROLLBACK.txt` (what was added / removed; manual rollback = delete the added jars,
  move these back, turn updates off). A start that still finds a staged update re-verifies it (signature, newer than
  the running version, plan, hashes) and applies it at that exit; an installed or stale one is deleted.
- The old Companion updater's leftovers in `config/aftrhrs/update/` are cleaned up; its `updates.json` `mode` is kept
  (a player who had set Off stays Off).

**Client** (`client/update/Updater`): on by default; HUD & Display → Updates: Automatic / Notify only / Off
(`config/aftrhrs/updates.json`). Development runs (the mod isn't a jar) show "Updates are off in development builds".

**Dedicated server** (`update/ServerUpdates`): `config.yml updater.server: stage | off` (default off). Console or
`feather.admin`: `feather update` (status), `feather update check`, `feather update stage` (works with `off` too),
`feather update cancel`. Staff online get a line when an update is staged, and on join while one waits. An integrated
(singleplayer) server never runs the server updater.

**Version handshake** (`net/VersionCheck`, client `client/update/VersionCheckClient`): configuration payload
`aftrhrs:version` (a string, both directions). The server queues a configuration task from `BEFORE_CONFIGURE` in phase
`aftrhrs:version_check`, ordered before the default phase where Fabric's registry sync queues its own task, so it runs
before Fabric's registry sync and vanilla's registry data. The client answers with its version and, if they differ,
disconnects itself with a translated message: older → "This server runs Aftr Hrs X — restart to update" plus the
updater's state (downloaded: restart; otherwise it starts the download right away, Notify mode included; updates off:
the releases link); newer → "the server needs updating". The server also drops a mismatched client with an English
version of the same and logs which side needs updating. Equal → `completeTask`, normal join. Skipped for the
singleplayer host, while reconfiguring, for clients without the channel, and with `updater.version-check: false`; no
answer in 15 s → let through. Vanilla's login checks (whitelist, bans) come before this.

**Testing without GitHub** (`tools/updater_test.py`, see its docstring). `-Daftrhrs.update.releases=http://127.0.0.1:8765/`
points the updater at a local mirror with GitHub's layout (the signature check is unchanged; the mirror is signed with
the real key). `-Daftrhrs.update.jar=<copy of a jar>` lets a dev client update that copy and its folder (dependency
versions then come from that folder only). `updater_test.py mirror` builds the mirror (`--dep <jar>` serves a
dependency from it, `fakejar` makes a version-bumped copy, `--tamper-jar` / `--tamper-manifest`), `dummymod` makes an
unrelated mod, `server` drives a production Fabric server (`fabric-server-launch.jar`) through console steps. DevScript
with `-Daftrhrs.devScript.anywhere=true` runs steps without a world (title and disconnect screens).

Tested 2026-11-19 (a production Fabric server on Windows, port 25717, plus the dev client): tampered jar → "doesn't
match the release (SHA-256)", nothing staged; edited manifest → "isn't signed by Aftr Hrs"; 1.0.0 → 1.0.1 with
GeckoLib 5.5.7 → 5.5.8 (a version-bumped copy) staged automatically (`updater.server: stage`) and applied by the
helper after `stop` (the jar is locked in-process); the restart loaded aftrhrs 1.0.1 + GeckoLib 5.5.8 with Fabric API
and an unrelated mod untouched and the rollback copy kept; the dev client staged and applied the same into a copy of a
mods folder and showed the toast in game; a 1.0.0 client on a 1.0.1 server and a 1.0.2 client on a 1.0.1 server got
the right messages before any registry sync; equal versions joined normally; an older or equal release is "up to date".

## Christmas set (branch `christmas-decor`, `feature/christmas`)

Content lives in the in-repo content root, made by **`python mod/tools/xmasart/gen.py`** (then `python mod/tools/gen_content.py`):
`content/items/arcana_christmas.yml`, `content/pack/{models,textures}/arcana/christmas/`, `content/recipes/{shaped,shapeless}.christmas.yml`
and the generated `feature/christmas/TreeSlots.java`. `tex.py` paints the 16x16 textures (original art), `pieces.py` builds every model,
`gen.py` writes items/recipes, checks (coplanar faces, -16..32, UVs, recipe clashes against vanilla + every content recipe) and renders
previews (`Documents/Blockbench/models/arcana_items/christmas/renders`); `lang.py` collects the module's message keys into
`lang/en_us.christmas.json`. Blockbench copies of the models: `arcana_items/christmas/bbmodel/`. Creative tab "Christmas"
(`arcana_christmas`, an extra tab in gen_content).

Generic gen_content additions it relies on (usable by any content):
- recipes are also read from `content/recipes/<kind>[.<set>].yml`;
- a furniture's own `furniture.aftrhrs:` block feeds the `FURN_OVERRIDES` keys: `_toggle`/`_toggle_default`/`_toggle_player`/`_toggle_sound`,
  `_off_model` (the model while the toggle is off: wrapped present, nutcracker jaw), `_stage_light` (light per growth stage; `FurnitureSpec.Stages.light`);
- `pack.state_models: {prop: {value: model}}` makes the item model pick a model from the stack's `block_state` (a wrapped present looks wrapped);
- unlit models: pack textures named `*_flame` are dropped, a sibling `<tex>_unlit.png` replaces `<tex>`, and `light_emission` is stripped
  (the trees' twinkling bulbs are an animated emissive texture with a dim `bulbs_unlit`).
- The 3-block trees are stored at half size with `properties.scale: 2` so their textures keep 1:1 density.

Behavior (`ChristmasModule`, module key `christmas`, on by default):
- `arcana_fir_tree` (`TreeBlockEntity`, client `client/christmas/TreeRenderer`): baubles / candy canes / gingerbread men hang on the free
  `TreeSlots` spot nearest the click, star or angel on the tip, `arcana_tree_lights` winds lights on (`lit` = lights on, light 12 on all
  three parts); empty hand switches the lights, sneak + empty hand takes the nearest thing back; breaking drops everything.
- Presents `arcana_<red|green|blue|gold|white>_present` (`PresentBlockEntity`, 9 slots, `open` state = unwrapped): right-click opens the box,
  sneak + empty hand opens the wrap dialog (recipient, note, "don't open until Christmas" = locked until the next Dec 25). Only the
  recipient (or anyone, if blank) opens it; others see who it is for and from; the wrapper sneak-clicks to unwrap it again. Breaking
  keeps contents + gift in the item (`container`, `custom_data.gift`, `block_state`, lore; the tooltip hides the contents) via
  `LootTableEvents.MODIFY_DROPS`. No presents, shulker boxes or filled bundles inside; hoppers can't reach in.
- Snow globe shakes (snowflakes + chime), advent wreath lights one candle per flint-and-steel click (stages 0..4), mistletoe
  (`MistletoeBlockEntity`) sends hearts up when two players stand under it. Everything else uses the generic furniture toggles.
- Tests: `gametest/content/ChristmasTests`.

## Fairy lights (`feature/fairylights`, `client/fairylights`, branch `fairy-lights`)

**Based on [Fairy Lights](https://github.com/pau101/Fairy-Lights) by Paul Fulham (pau101), MIT.** The license is bundled
as `licenses/FairyLights-LICENSE.md` (and credited in `fabric.mod.json` contributors); every ported source file keeps an
attribution header. Ported from the 1.19.2/master (Forge, Mojang names) sources and reworked for Fabric 26.3; the models,
textures and sounds are our own.

- **Fasteners** (`Fastener`): `HookBlockEntity` (block `aftrhrs:fairy_hook`, the String Hook), `KnotEntity`
  (`aftrhrs:fairy_knot`, a `BlockAttachedEntity` on fences and walls) and `PlayerFastener` (the loose end in the hand).
  A strand (`Strand`) belongs to the fastener it starts at (`outgoing`); the other end only remembers it (`incoming`).
  `FastenerRef` = hook pos / knot pos / player UUID. Stringing onto any sturdy face puts a hook there by itself
  (`placed=false`: it goes with its last strand and drops nothing); a hook placed from the item stays and drops itself.
- **Saving / sync**: hooks keep their strands in the block entity (`Strands`, also the update tag); knots in a
  persistent, synced Fabric attachment (`KnotEntity.DATA`; it is touched in `FairyLights.init` so it exists before anyone
  joins: clients announce which attachments they accept). Strands save their spec, slack, light mode and light blocks.
- **Shape**: `math/Catenary` (Fairy Lights' catenary solver + slack easing) worked out from both ends and cached until an
  end moves; lights / pennants are spaced along it (`Strand.features`). Client sway: a damped spring per strand (wind,
  stronger in rain/storms, pushed by entities walking into it) in `FairyLightsClient.tick`; bulbs swing with it.
- **Rendering** (26.3 render states): `HookRenderer` (BER, off-screen so long strands never pop) and `KnotRenderer`
  snapshot their strands in `extractRenderState` (`StrandRender.extract`: positions with sway, light per point,
  twinkle/flicker brightness) and draw them in `submit` with custom geometry: cords, voxel bulbs (`BulbShapes`, generated),
  fir sprigs + berries (garland), tinsel strips, pennant cloths and letters (`submitText`), camera-facing glow halos
  (`RenderTypes.eyes`). Texture: `textures/entity/fairy_lights.png` (`tools/fairylights_art.py`).
- **Clicks**: strands are neither blocks nor entities, so `MinecraftStrandMixin` asks `StrandPicker` on attack/use;
  the server re-checks reach, distance to the strand and claims (`FairyNet.Action`). Punch = take down (drops the item);
  string/stick = looser/tighter; a light / pennant / dye / glowstone on one bulb or pennant changes just that one; empty
  hand cycles lights on / dusk-to-dawn / off, or opens the bunting letters screen (`FairyNet.EditText` / `SetText`).
- **Real light**: lit, still strands keep up to `light-blocks-per-strand` `minecraft:light` blocks (levels from the
  styles) in air at some bulbs; dusk-to-dawn mode follows `isDarkOutside`. Removed with the strand / when switched off.
- **Limits** (`feather/fairy-lights.yml`, module "Fairy lights"): max length (24), strands per hook/knot (8), per
  chunk (48), light blocks per strand (6). Claims: BUILD trust at each end. Ops in creative bypass the counts.
- **Content** (`tools/gen_fairylights.py`, called at the end of `gen_content.py` because it writes into the folders
  that script clears): item models + definitions (`fairy_*`), the hook blockstate/model, 49 recipes (shaped, `crafting_dye`,
  `crafting_transmute` for twinkle, `aftrhrs:fairy_pattern`), loot, tags, `lang/en_us.fairylights.json` and
  `client/fairylights/BulbShapes.java` (the in-world light shapes; the item models use the same table).
- **Ids**: strands `fairy_lights` (String of Lights), `fairy_garland` (Pine Garland), `fairy_tinsel`, `fairy_bunting`
  (Pennant Bunting); lights `fairy_bulb`, `fairy_holiday_bulb`, `fairy_edison_bulb`, `fairy_paper_lantern`,
  `fairy_orb_lantern`, `fairy_star`, `fairy_snowflake`, `fairy_icicle`, `fairy_candle_lantern`, `fairy_heart`,
  `fairy_flower`, `fairy_pumpkin`, `fairy_ghost`; pennants `fairy_pennant_{triangle,swallowtail,square}`; `fairy_hook`.
  Item components: `aftrhrs:strand` (bulbs, flags, color, wire, text), `aftrhrs:twinkle`. Creative tab
  `aftrhrs:fairy_lights` ("Lights & Garlands", ready-made presets incl. Christmas ones). JEI: plain recipes come through
  `RecipeExport`; pattern examples are exported to the workstation page.
- **Staff**: `/fairylights string <from> <to> <preset> [slack]`, `give <preset>`, `clear <radius>`, `count`.
- **Not ported**: jingles (MIDI songs, note-block triggering, the jingle HUD), the redstone/comparator output of
  fasteners, the separate letter bunting (letters are on the pennant bunting instead), the styled text editor
  (plain letters only), the light block items (lights only go on strands), Valkyrien Warfare integration, the tutorial
  "Clippy", meteor / spider / skull / witch / moon / incandescent-variant styles. The older civic `arcana_string_lights`
  (display-entity strings on `arcana_string_light_post`) stays as it is: it has live data in `civic-strings.yml`.
- Tests: `gametest/content/FairyLightsTests` (stringing by hand between a fence and a wall, save/load of hooks and
  knots, breaking a hook / fence drops the strand and clears its light, length and per-hook limits, the recipes,
  clicks on hung strands).
