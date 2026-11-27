#!/usr/bin/env python3
"""Mechanical parity checks: old Paper stack (Feather plugin, MythicMobs, Oraxen, Companion) vs the Fabric mod.

Run from the repo root:  python mod/tools/parity.py [--json out.json]
Prints Markdown sections (used to build mod/PARITY.md). Read-only; stdlib only (+ mod/tools/miniyaml.py).
Heuristic on purpose: string literals in Java source. Dynamic keys ("menu.hub." + key) show up as prefix matches.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OLD_SRC = os.path.join(ROOT, 'src', 'net', 'axther', 'feather')
NEW_SRC = os.path.join(ROOT, 'mod', 'src')
RES = os.path.join(ROOT, 'res')
PLUGINS = os.environ.get('AFTRHRS_PLUGINS', 'C:/Users/kevin/Desktop/plugins')
MM_MOBS = os.path.join(PLUGINS, 'MythicMobs', 'packs', 'Arcana', 'mobs')
ORAXEN_ITEMS = os.path.join(PLUGINS, 'Oraxen', 'items')
SPECIES = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'data', 'aftrhrs', 'species')
CONTENT = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'data', 'aftrhrs', 'content', 'content.json')

STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def java_files(base):
    return [f for f in glob.glob(os.path.join(base, '**', '*.java'), recursive=True)]


def read(f):
    with open(f, encoding='utf-8', errors='replace') as fh:
        return fh.read()


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)


OLD = {f: strip_comments(read(f)) for f in java_files(OLD_SRC)}
NEW = {f: strip_comments(read(f)) for f in java_files(NEW_SRC)}


def literals(sources):
    out = {}
    for f, s in sources.items():
        for m in STRING.finditer(s):
            out.setdefault(m.group(1), set()).add(f)
    return out


OLD_LIT = literals(OLD)
NEW_LIT = literals(NEW)


def rel(f):
    return os.path.relpath(f, ROOT).replace('\\', '/')


# ------------------------------------------------------------------ yaml (just what we need)
def yaml_top_keys(path):
    keys = []
    for line in read(path).splitlines():
        m = re.match(r'^([A-Za-z0-9_\-]+):', line)
        if m:
            keys.append(m.group(1))
    return keys


def yaml_leaf_keys(path):
    """Dotted leaf keys of a block-style YAML file (indent-based; good enough for messages/config)."""
    stack = []
    leaves = []
    sections = []
    for raw in read(path).splitlines():
        if not raw.strip() or raw.lstrip().startswith('#') or raw.lstrip().startswith('- '):
            continue
        m = re.match(r'^( *)([^:#\s][^:]*?|"[^"]+"|\'[^\']+\'):(\s.*|)$', raw)
        if not m:
            continue
        indent = len(m.group(1))
        key = m.group(2).strip('"\'')
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path_ = '.'.join([k for _, k in stack] + [key])
        val = m.group(3).strip()
        if val == '' or val.startswith('#'):
            sections.append(path_)
        else:
            leaves.append(path_)
        stack.append((indent, key))
    # a "section" with no children (e.g. "key:" then a list) is a leaf too
    leafset = set(leaves)
    for s in sections:
        if not any(l.startswith(s + '.') for l in leafset):
            leaves.append(s)
    return leaves, sections


# ------------------------------------------------------------------ 1. commands
def bound_commands(sources):
    out = {}
    for f, s in sources.items():
        for m in re.finditer(r'\bbind(?:Command)?\(\s*"([a-z0-9_\-]+)"', s):
            out.setdefault(m.group(1), set()).add(f)
        for m in re.finditer(r'Travel\.bind\(([^;]*?)\);', s, re.S):
            for lit in STRING.findall(m.group(1)):
                out.setdefault(lit, set()).add(f)
        # for (String x : List.of(...)) ... bind(x
        for m in re.finditer(r'for\s*\(\s*String\s+(\w+)\s*:\s*([^)]*\)|\w+)\s*\)', s):
            var, expr = m.group(1), m.group(2)
            body = s[m.end():m.end() + 250]
            if not re.search(r'bind(?:Command)?\(\s*' + var + r'\b', body):
                continue
            if expr.startswith('List.of'):
                # expr may be cut at the first ')': take the whole List.of( ... )
                start = s.index('List.of', m.start())
                end = s.index(')', start)
                lits = STRING.findall(s[start:end])
            else:
                cm = re.search(r'\b' + re.escape(expr) + r'\s*=\s*List\.of\(([^)]*)\)', s)
                lits = STRING.findall(cm.group(1)) if cm else []
            for lit in lits:
                out.setdefault(lit, set()).add(f)
    return out


def plugin_commands():
    cmds = {}
    cur = None
    in_cmds = False
    for line in read(os.path.join(RES, 'plugin.yml')).splitlines():
        if re.match(r'^commands:', line):
            in_cmds = True
            continue
        if in_cmds and re.match(r'^\S', line):
            in_cmds = False
        if not in_cmds:
            continue
        m = re.match(r'^  ([a-z0-9_\-]+):', line)
        if m:
            cur = m.group(1)
            cmds[cur] = {'aliases': [], 'permission': None}
            continue
        m = re.match(r'^    aliases:\s*\[(.*)\]', line)
        if m and cur:
            cmds[cur]['aliases'] = [a.strip() for a in m.group(1).split(',') if a.strip()]
        m = re.match(r'^    permission:\s*(\S+)', line)
        if m and cur:
            cmds[cur]['permission'] = m.group(1)
    return cmds


# ------------------------------------------------------------------ 2. modules
def config_modules():
    leaves, _ = yaml_leaf_keys(os.path.join(RES, 'config.yml'))
    return [k[len('modules.'):] for k in leaves if k.startswith('modules.') and k.count('.') == 1]


def registered_modules():
    out = []
    for f, s in NEW.items():
        for m in re.finditer(r'feather\.register\(\s*"([a-z0-9\-]+)"', s):
            out.append((m.group(1), rel(f)))
    return out


# ------------------------------------------------------------------ 3/4. messages + config keys
def key_status(key, old_lit, new_lit):
    """'lit' if the full key is a literal, 'pre' if a dynamic prefix of it is, '' otherwise."""
    if key in old_lit or key in new_lit:
        pass

    def find(lits):
        if key in lits:
            return 'lit'
        parts = key.split('.')
        # relative key: a dotted suffix of at least 2 segments ("set" alone is too vague)
        for i in range(1, len(parts) - 1):
            if '.'.join(parts[i:]) in lits:
                return 'suffix'
        for i in range(len(parts) - 1, 0, -1):
            pre = '.'.join(parts[:i]) + '.'
            if pre in lits:
                return 'pre'
        # "foo.bar-" + x style
        last = parts[-1]
        for cut in range(len(last) - 1, 1, -1):
            if last[cut - 1] in '-_':
                pre = '.'.join(parts[:-1] + [last[:cut]])
                if pre in lits:
                    return 'pre'
        return ''
    return find(old_lit), find(new_lit)


def msg_keys_report():
    leaves, _ = yaml_leaf_keys(os.path.join(RES, 'messages.yml'))
    rows = []
    for k in leaves:
        o, n = key_status(k, OLD_LIT, NEW_LIT)
        rows.append((k, o, n))
    return rows


CONFIG_GETTERS = re.compile(
    r'\.(?:get(?:String|Int|Boolean|Double|Long|StringList|IntegerList|List|MapList|ConfigurationSection|Section|Keys)?|'
    r'section|contains|isSet|isConfigurationSection|isList|isString|isInt)\(\s*"([A-Za-z0-9_.\-]+)"')


def config_reads(sources):
    out = {}
    for f, s in sources.items():
        for m in CONFIG_GETTERS.finditer(s):
            out.setdefault(m.group(1), set()).add(f)
    return out


# ------------------------------------------------------------------ 5. data files
DATA_FILE = re.compile(r'"([A-Za-z0-9_\-/]+\.(?:yml|yaml|json|txt|dat|sha256|log|csv))"')


def data_files(sources):
    out = {}
    for f, s in sources.items():
        for m in DATA_FILE.finditer(s):
            out.setdefault(m.group(1), set()).add(f)
    return out


# ------------------------------------------------------------------ 6/7. mobs + items
def mythic_ids():
    out = {}
    for f in sorted(glob.glob(os.path.join(MM_MOBS, '*.yml'))):
        for k in yaml_top_keys(f):
            out[k] = os.path.basename(f)
    return out


def species_mm():
    out = {}
    for f in glob.glob(os.path.join(SPECIES, '*.json')):
        if f.endswith('index.json'):
            continue
        d = json.load(open(f, encoding='utf-8'))
        out[d.get('mm_id')] = d.get('id')
    return out


def oraxen_ids():
    out = {}
    for f in sorted(glob.glob(os.path.join(ORAXEN_ITEMS, '*.yml'))):
        for k in yaml_top_keys(f):
            out[k] = os.path.basename(f)
    return out


def content_ids():
    d = json.load(open(CONTENT, encoding='utf-8'))
    return {e['id']: e.get('kind') for e in d['entries']}


# ------------------------------------------------------------------ 8. companion
def companion_handlers(sources):
    out = {}
    for f, s in sources.items():
        for m in re.finditer(r'\bon\(\s*"([a-z_]+)"', s):
            out.setdefault(m.group(1), set()).add(rel(f))
    return out


def companion_sends(sources):
    out = {}
    pat = re.compile(r'(?:send(?:Encoded)?\([^;]*?,\s*"([a-z_]+)"|message\(\s*"([a-z_]+)"|addProperty\(\s*"t"\s*,\s*"([a-z_]+)")')
    for f, s in sources.items():
        if 'ompanion' not in s and 'Wire' not in s and 'Bridge' not in f:
            continue
        for m in pat.finditer(s):
            t = next(g for g in m.groups() if g)
            out.setdefault(t, set()).add(rel(f))
    return out


# ------------------------------------------------------------------ 9. listeners
def bukkit_events():
    out = {}
    for f, s in OLD.items():
        for m in re.finditer(r'@EventHandler[^\n]*\n\s*(?:public\s+|private\s+|protected\s+)?void\s+\w+\(\s*(?:final\s+)?([\w.]+)', s):
            out.setdefault(m.group(1).split('.')[-1], set()).add(f)
    return out


_PROTECT = ['BLOCK_PROTECTED', 'ClaimsHook', 'claimAllows']
_USE = ['UseBlockCallback', 'UseItemCallback', 'BlockEvents.USE', 'ItemEvents.USE', 'PHYSICAL_INTERACT', 'ArcanaContent.hook',
        'FurnitureHook', 'useOn', 'useItemOn', 'useWithoutItem']
_GUI = ['Gui.', 'Gui ', 'CONTAINER_CLICK', 'CONTAINER_OPEN', 'AbstractContainerMenu', 'onClose', 'clicked(', 'MenuProvider']
_FURN = ['ArcanaContent.hook', 'FurnitureHook', 'onUse', 'onBreak', 'onPlaced', 'onRemoved', 'onFurniture']
_TRAVEL = ['PLAYER_TELEPORT', 'PLAYER_TELEPORTED', 'BEFORE_WORLD_CHANGE', 'TeleportCause', 'travelLocked', 'TravelLock']
_ENTITY_LOAD = ['ENTITY_LOAD', 'ENTITY_UNLOAD', 'Legacy.converter', 'onFurniture', 'TRANSIENT_TAG', 'START_TRACKING']

# Bukkit/Paper/plugin event -> identifiers of its equivalent in the mod (any one present in the counterpart files).
# None = no equivalent needed (the reason is in NO_EQUIV).
EVENT_EQUIV = {
    'PlayerJoinEvent': ['ServerPlayConnectionEvents.JOIN', 'onWelcome', 'JOIN_MESSAGE', 'profiles()'],
    'PlayerQuitEvent': ['DISCONNECT', 'onQuit', 'QUIT_MESSAGE'],
    'PlayerKickEvent': ['DISCONNECT'],
    'PlayerRegisterChannelEvent': ['ClientboundPlayChannelEvents.REGISTER'],
    'AsyncChatEvent': ['ALLOW_CHAT_MESSAGE', 'CHAT_MESSAGE', 'CHAT_GATE', 'CHAT_MODERATION'],
    'AsyncPlayerChatEvent': ['ALLOW_CHAT_MESSAGE', 'CHAT_GATE'],
    'AsyncPlayerPreLoginEvent': ['PLAYER_LOGIN', 'ServerPlayConnectionEvents.INIT', 'profiles.start', 'Profiles'],
    'AsyncPlayerSendSuggestionsEvent': ['COMMAND_SUGGESTIONS', 'SEND_COMMANDS'],
    'AsyncTabCompleteEvent': ['COMMAND_SUGGESTIONS', 'SEND_COMMANDS'],
    'TabCompleteEvent': ['COMMAND_SUGGESTIONS', 'SEND_COMMANDS', 'onTabComplete'],
    'PlayerCommandSendEvent': ['SEND_COMMANDS'],
    'PlayerCommandPreprocessEvent': ['FeatherEvents.COMMAND', 'COMMAND.register'],
    'PaperServerListPingEvent': ['SERVER_STATUS'],
    'BlockBreakEvent': ['PlayerBlockBreakEvents', 'onBreak', 'BLOCK_PROTECTED', 'playerDestroy', 'playerWillDestroy'],
    'BlockDamageEvent': ['AttackBlockCallback'],
    'BlockPlaceEvent': ['BLOCK_PLACE', 'BLOCK_PLACED', 'onPlaced', 'setPlacedBy'],
    'BlockBurnEvent': ['BLOCK_BURN', 'FIRE_SPREAD'] + _PROTECT,
    'BlockIgniteEvent': ['FIRE_SPREAD', 'BLOCK_BURN'] + _PROTECT,
    'BlockSpreadEvent': ['FIRE_SPREAD'] + _PROTECT,
    'BlockExplodeEvent': ['EXPLOSION'] + _PROTECT,
    'EntityExplodeEvent': ['EXPLOSION'] + _PROTECT,
    'TNTPrimeEvent': ['EXPLOSION', 'UseBlockCallback', 'BlockEvents.USE'] + _PROTECT,
    'BlockFromToEvent': ['FLUID_INTO'] + _PROTECT,
    'BlockBreakBlockEvent': ['FLUID_INTO', 'ArcanaCropBlock', 'loot'],
    'BlockPistonExtendEvent': ['PISTON'] + _PROTECT,
    'BlockPistonRetractEvent': ['PISTON'] + _PROTECT,
    'BlockDispenseEvent': ['DISPENSE'] + _PROTECT,
    'BlockDestroyEvent': _PROTECT,
    'StructureGrowEvent': _PROTECT,
    'EntityChangeBlockEvent': ['ENTITY_CHANGE_BLOCK'] + _PROTECT,
    'BlockFormEvent': ['BLOCK_FORMED'],
    'BlockFadeEvent': ['maintains_farmland', 'FarmBlock', 'fade', 'turnToDirt'],
    'BlockFertilizeEvent': ['BoneMeal', 'bonemeal', 'BONE_MEAL', 'performBonemeal'] + _PROTECT,
    'BlockDropItemEvent': ['loot', 'dropResources', 'onBreak', 'PlayerBlockBreakEvents'],
    'BlockReceiveGameEvent': ['VIBRATION'],
    'BlockShearEntityEvent': ['DISPENSE', 'Shorn', 'shear'],
    'BlockCookEvent': ['ArcanaItem', 'CRAFT_RESULT', 'recipe'],
    'FurnaceBurnEvent': ['ArcanaItem', 'fuel', 'FuelValues'],
    'BrewEvent': ['ArcanaItem', 'brew'],
    'BrewingStandFuelEvent': ['ArcanaItem', 'brew'],
    'PrepareAnvilEvent': ['COMBINE_RESULT'],
    'PrepareSmithingEvent': ['COMBINE_RESULT', 'CRAFT_RESULT'],
    'PrepareResultEvent': ['COMBINE_RESULT', 'CRAFT_RESULT'],
    'PrepareItemCraftEvent': ['CRAFT_RESULT'],
    'PrepareItemEnchantEvent': ['ENCHANT', 'enchant'],
    'CraftItemEvent': ['ITEM_CRAFTED', 'CRAFT_RESULT'],
    'CrafterCraftEvent': ['CRAFT_RESULT', 'Crafter'],
    'SmithItemEvent': ['ITEM_CRAFTED', 'COMBINE_RESULT'],
    'EnchantItemEvent': ['STAT_AWARDED', 'ENCHANT', 'enchant'],
    'InventoryClickEvent': _GUI,
    'InventoryDragEvent': _GUI,
    'InventoryCloseEvent': _GUI + ['removed(', 'stopOpen'],
    'InventoryOpenEvent': ['CONTAINER_OPEN'] + _PROTECT,
    'InventoryMoveItemEvent': ['HOPPER_PULL'] + _PROTECT,
    'ChunkLoadEvent': ['ServerChunkEvents.CHUNK_LOAD', 'CHUNK_LOAD'],
    'ChunkUnloadEvent': ['ServerChunkEvents.CHUNK_UNLOAD', 'CHUNK_UNLOAD'],
    'WorldUnloadEvent': ['SERVER_STOPPING', 'SERVER_STOPPED', 'disable()', 'UNLOAD'],
    'EntitiesLoadEvent': _ENTITY_LOAD,
    'EntitiesUnloadEvent': _ENTITY_LOAD,
    'EntityAddToWorldEvent': _ENTITY_LOAD,
    'EntityRemoveEvent': _ENTITY_LOAD + ['onRemoved', 'onBreak'],
    'EntityRemoveFromWorldEvent': _ENTITY_LOAD,
    'MythicMobSpawnEvent': _ENTITY_LOAD + ['ArcanaWildlife', 'Arcana'],
    'EntityDamageEvent': ['ALLOW_DAMAGE', 'AFTER_DAMAGE', 'ENTITY_DAMAGE', 'DAMAGE_AMOUNT', 'CRITICAL_HIT'],
    'EntityDamageByEntityEvent': ['ALLOW_DAMAGE', 'AFTER_DAMAGE', 'ENTITY_DAMAGE', 'DAMAGE_AMOUNT', 'attacker('],
    'EntityKnockbackEvent': ['knockback', 'Knockback', 'PLAYER_MOVE', 'travelLocked'],
    'EntityDeathEvent': ['AFTER_DEATH', 'PLAYER_DEATH', 'ALLOW_DEATH', 'CreatureEvents.DIED', 'dropCustomDeathLoot', 'die('],
    'PlayerDeathEvent': ['PLAYER_DEATH', 'AFTER_DEATH', 'ALLOW_DEATH'],
    'MythicMobDeathEvent': ['AFTER_DEATH', 'CreatureEvents.DIED', 'ArcanaMonster', 'die('],
    'EntityRegainHealthEvent': ['HEAL'],
    'EntityTargetEvent': ['MOB_TARGET'],
    'EntityTargetLivingEntityEvent': ['MOB_TARGET'],
    'EntityTameEvent': ['ANIMAL_TAMED', 'CreatureEvents.TAMED'],
    'EntityTransformEvent': ['MOB_CONVERT', 'MOB_CONVERSION'],
    'EntityPickupItemEvent': ['ITEM_PICKUP', 'pickUpItem', 'wantsToPickUp', 'canPickUp'],
    'EntityDropItemEvent': ['hook(', 'Lay', 'spawnAtLocation'],
    'EntityPotionEffectEvent': ['EFFECT_ADD'],
    'AreaEffectCloudApplyEvent': ['EFFECT_APPLY', 'EFFECT_ADD'],
    'PotionSplashEvent': ['EFFECT_APPLY', 'EFFECT_ADD'],
    'EntityCombustEvent': ['igniteForSeconds', 'setRemainingFireTicks', 'fireImmune', 'burn_in_daylight', 'Combust', 'ALLOW_DAMAGE'],
    'EntityCombustByEntityEvent': ['igniteForSeconds', 'setRemainingFireTicks', 'ALLOW_DAMAGE', 'EFFECT_ADD'],
    'EntityShootBowEvent': ['BowItem', 'releaseUsing', 'Projectile', 'arrow', 'ServerEntityEvents.ENTITY_LOAD'],
    'EntityPortalEvent': ['TeleportCause', 'PORTAL', 'canUsePortal', 'changeDimension', 'BEFORE_WORLD_CHANGE'],
    'EntityInteractEvent': ['PHYSICAL_INTERACT'],
    'EntityPlaceEvent': ['ENTITY_PLACE', 'UseBlockCallback', 'ItemEvents.USE', 'BlockEvents.USE'],
    'EntityBreedEvent': ['breed', 'Breed', 'spawnChildFromBreeding'],
    'EntityEnterLoveModeEvent': ['setInLove', 'isFood', 'Breed', 'breed'],
    'EntityMountEvent': ['startRiding', 'ALLOW_MOUNT', 'travelLocked', 'TravelLock', 'UseEntityCallback'],
    'EntityDismountEvent': ['stopRiding', 'removePassenger', 'DISMOUNT', 'dismount'],
    'EntityToggleGlideEvent': ['ELYTRA', 'FallFlying', 'fallFlying', 'travelLocked'],
    'HangingBreakEvent': ['ENTITY_DAMAGE'],
    'HangingBreakByEntityEvent': ['ENTITY_DAMAGE'],
    'HangingPlaceEvent': ['ItemEvents.USE', 'UseItemCallback', 'BlockEvents.USE', 'HangingEntityItem'],
    'VehicleDamageEvent': ['ENTITY_DAMAGE'],
    'VehicleDestroyEvent': ['ENTITY_DAMAGE'],
    'VehicleEnterEvent': ['UseEntityCallback', 'startRiding', 'travelLocked', 'Restraint'],
    'PlayerArmorStandManipulateEvent': ['UseEntityCallback', 'ArmorStand'],
    'PlayerInteractEvent': _USE + ['AttackBlockCallback', 'onUse', 'SWAP_HANDS', 'CreatureEvents.INTERACT'],
    'PlayerInteractEntityEvent': ['UseEntityCallback', 'CreatureEvents.INTERACT', 'FurnitureHook', 'interact(', 'mobInteract'],
    'PlayerInteractAtEntityEvent': ['UseEntityCallback', 'CreatureEvents.INTERACT', 'interactAt', 'interact('],
    'PrePlayerAttackEntityEvent': ['AttackEntityCallback'],
    'PlayerItemConsumeEvent': ['ITEM_CONSUMED'],
    'PlayerItemMendEvent': ['ITEM_MENDED'],
    'PlayerItemHeldEvent': ['selected', 'getSelectedSlot', 'travelLocked', 'END_SERVER_TICK'],
    'PlayerFishEvent': ['FISH_CAUGHT', 'FISH_CATCH', 'FISH_REEL', 'FISH_BITE', 'HOOK_PULL', 'FISH_LURE'],
    'PlayerDropItemEvent': ['ITEM_DROP'],
    'PlayerSwapHandItemsEvent': ['SWAP_HANDS'],
    'PlayerMoveEvent': ['PLAYER_MOVE', 'PLAYER_MOVE_BLOCK', 'Restraint'],
    'PlayerToggleSneakEvent': ['isShiftKeyDown', 'isCrouching', 'PLAYER_MOVE', 'END_SERVER_TICK'],
    'PlayerToggleFlightEvent': ['mayfly', 'getAbilities', 'flying', 'Restraint', 'travelLocked'],
    'PlayerVelocityEvent': ['travelLocked', 'PLAYER_MOVE', 'Restraint', 'setDeltaMovement'],
    'PlayerRiptideEvent': ['travelLocked', 'Riptide', 'riptide', 'TridentItem'],
    'PlayerElytraBoostEvent': ['travelLocked', 'FireworkRocket', 'firework', 'UseItemCallback'],
    'PlayerLaunchProjectileEvent': ['travelLocked', 'UseItemCallback', 'Projectile'],
    'ProjectileLaunchEvent': ['travelLocked', 'UseItemCallback', 'Projectile', 'ENTITY_LOAD'],
    'ProjectileHitEvent': ['PROJECTILE_HIT_BLOCK'] + _PROTECT,
    'PlayerGameModeChangeEvent': ['gameMode', 'GameType', 'travelLocked', 'END_SERVER_TICK'],
    'PlayerTeleportEvent': _TRAVEL,
    'PlayerPortalEvent': _TRAVEL + ['PORTAL'],
    'PlayerChangedWorldEvent': _TRAVEL + ['AFTER_PLAYER_CHANGE_WORLD', 'level()'],
    'PlayerRespawnEvent': ['PLAYER_RESPAWN', 'RESPAWN_POSITION', 'AFTER_RESPAWN'],
    'PlayerBucketEmptyEvent': ['UseItemCallback', 'ItemEvents.USE', 'BlockEvents.USE', 'BucketItem', 'FLUID_INTO'] + _PROTECT,
    'PlayerBucketFillEvent': ['UseItemCallback', 'ItemEvents.USE', 'BucketItem'] + _PROTECT,
    'PlayerBucketEntityEvent': ['UseEntityCallback', 'Bucketable', 'bucket'],
    'PlayerShearEntityEvent': ['UseEntityCallback', 'Shorn', 'shear'],
    'PlayerLeashEntityEvent': ['UseEntityCallback', 'Leash', 'leash'],
    'PlayerUnleashEntityEvent': ['UseEntityCallback', 'Leash', 'leash'],
    'PlayerNameEntityEvent': ['UseEntityCallback', 'NAME_TAG', 'name_tag', 'CreatureEvents.INTERACT', 'setCustomName'],
    'PlayerTakeLecternBookEvent': ['MENU_BUTTON'],
    'PlayerInsertLecternBookEvent': ['UseBlockCallback', 'BlockEvents.USE', 'ItemEvents.USE'],
    'SignChangeEvent': ['SIGN_EDIT'],
    'PlayerOpenSignEvent': ['SIGN_EDIT', 'UseBlockCallback', 'BlockEvents.USE'],
    'CreatureSpawnEvent': ['MOB_SPAWN', 'SPAWNER_SPAWN'],
    'FoodLevelChangeEvent': ['FoodData', 'getFoodData', 'setFoodLevel', 'hunger', 'END_SERVER_TICK'],
    'ItemSpawnEvent': ['ItemEntity', 'ENTITY_LOAD'],
    'BaseEntityInteractEvent': ['UseEntityCallback', 'CreatureEvents.INTERACT', 'AttackEntityCallback'],
    'ModelMountEvent': ['ArcanaMount', 'startRiding'],
    'MythicMechanicLoadEvent': ['Abilities.register', 'BossFx'],
    'OraxenFurnitureBreakEvent': _FURN + ['PlayerBlockBreakEvents'],
    'OraxenFurnitureInteractEvent': _FURN + ['UseBlockCallback'],
    'OraxenFurniturePlaceEvent': _FURN + ['BLOCK_PLACED'],
    'OraxenStringBlockBreakEvent': ['ArcanaCropBlock', 'Crops', 'PlayerBlockBreakEvents'],
    'OraxenItemsLoadedEvent': None,
    'OraxenPackGeneratedEvent': None,
    'ModelRegistrationEvent': None,
    'MythicConditionLoadEvent': None,
    'MythicReloadedEvent': None,
}
NO_EQUIV = {
    'OraxenItemsLoadedEvent': 'items are registered at mod init (ArcanaContent); nothing to wait for',
    'OraxenPackGeneratedEvent': 'PackBridge dropped: assets ship in the mod jar',
    'ModelRegistrationEvent': 'PackBridge dropped: GeckoLib models ship in the mod jar',
    'MythicConditionLoadEvent': 'MythicMobs conditions (featherpetslot) became CreatureEvents.CAN_TAME',
    'MythicReloadedEvent': 'no MythicMobs; species data is in the jar',
}


# Checked by hand (fm-audit, 2026-11-19): (event, old file name) -> (status, note). File name '*' = every file.
_RESTRAINT = ('✅', 'moderation/Restraint (auth gate + /freeze share it)')
_STORAGE_BLOCKS = ('⚠️', 'real blocks now: strength 1.5/1200, IMMOVEABLE; no carrier/display to protect (StorageContent)')
_COINS = ('⚠️', 'native coin is in no recipe, fuel or brewing tag: nothing to block (Coins javadoc)')
OVERRIDES = {
    ('*', 'AuthModule.java'): _RESTRAINT,
    ('*', 'StorageBlocks.java'): _STORAGE_BLOCKS,
    ('EntityShootBowEvent', 'Freeze.java'): ('✅', 'Restraint: UseItemCallback FAIL'),
    ('PlayerItemConsumeEvent', 'Freeze.java'): ('✅', 'Restraint: UseItemCallback FAIL'),
    ('PlayerItemConsumeEvent', 'TravelLock.java'): ('✅', 'UseItemCallback (chorus fruit)'),
    ('PlayerSwapHandItemsEvent', 'TravelLock.java'): ('✅', 'per-tick check moves a robber off a lunge weapon'),
    ('ModelMountEvent', 'MegMounts.java'): ('✅', 'TravelLock per-tick check covers Arcana mounts'),
    ('BlockCookEvent', 'Coins.java'): _COINS, ('BrewEvent', 'Coins.java'): _COINS,
    ('BrewingStandFuelEvent', 'Coins.java'): _COINS, ('CraftItemEvent', 'Coins.java'): _COINS,
    ('CrafterCraftEvent', 'Coins.java'): _COINS, ('FurnaceBurnEvent', 'Coins.java'): _COINS,
    ('PrepareAnvilEvent', 'Coins.java'): _COINS, ('PrepareItemCraftEvent', 'Coins.java'): _COINS,
    ('PrepareResultEvent', 'Coins.java'): _COINS, ('PrepareSmithingEvent', 'Coins.java'): _COINS,
    ('EntityPickupItemEvent', 'Coins.java'): ('✅', 'FeatherEvents.MOB_PICKUP (MobPickupMixin) in BankModule: mobs leave coins'),
    ('EntityPickupItemEvent', 'Police.java'): ('✅', 'Police: setCanPickUpLoot(false)'),
    ('EntityTargetEvent', 'Police.java'): ('✅', 'ArcanaMonster police never pick targets'),
    ('EntityTransformEvent', 'Police.java'): ('✅', 'police are ArcanaMonster (a Monster, no vanilla conversion path)'),
    ('EntityTargetEvent', 'MythicAllies.java'): ('✅', 'bossfx/Allies via ArcanaMonster.setTarget + ALLOW_DAMAGE'),
    ('BlockCookEvent', 'FishingListener.java'): ('⚠️', 'native fish match no cod smelting/campfire recipe (PORTING known gaps)'),
    ('InventoryClickEvent', 'FishingListener.java'): ('⚠️', 'no-cook furnace guard not needed (no recipe matches)'),
    ('InventoryDragEvent', 'FishingListener.java'): ('⚠️', 'no-cook furnace guard not needed (no recipe matches)'),
    ('InventoryMoveItemEvent', 'FishingListener.java'): ('⚠️', 'no-cook hopper guard not needed (no recipe matches)'),
    ('InventoryClickEvent', 'ToolStatsModule.java'): ('✅', 'COMBINE_RESULT (anvil/grindstone repairs)'),
    ('InventoryClickEvent', 'Smoking.java'): ('✅', 'CRAFT_RESULT (joint rolling)'),
    ('EntityDamageEvent', 'NametagModule.java'): ('✅', 'health polled every tick'),
    ('EntityDamageEvent', 'TabModule.java'): ('✅', 'health task'),
    ('PlayerDeathEvent', 'NametagModule.java'): ('✅', 'client-only tag display; dead players have none'),
    ('PlayerPortalEvent', 'NametagModule.java'): ('✅', 'client-only tag display never blocks portals'),
    ('PlayerDeathEvent', 'StorageModule.java'): ('✅', 'window close returns the cursor (StorageModule.closed)'),
    ('PlayerTeleportEvent', 'StorageModule.java'): ('✅', 'views revalidated every tick'),
    ('PlayerMoveEvent', 'Teleports.java'): ('✅', 'warm-up tick checks moved() > 0.6 blocks'),
    ('EntityRegainHealthEvent', 'PetsCompanion.java'): ('✅', 'health diff pushed every second'),
    ('EntityTargetEvent', 'HologramsModule.java'): ('✅', 'mob lines have NoAi'),
    ('EntityTransformEvent', 'HologramsModule.java'): ('✅', 'FeatherEvents.MOB_CONVERT (MobConvertMixin) refuses owned mob lines (fm-qa)'),
    ('EntityTameEvent', 'WildlifeModule.java'): ('✅', 'ArcanaWildlife is an Animal, not tameable'),
    ('MythicMechanicLoadEvent', 'MythicPets.java'): ('⚠️', 'featherpet{a=...} mechanics became CreatureEvents (TAMED, STAY, FED...)'),
    ('BlockDispenseEvent', 'QuestEvents.java'): ('✅', 'FeatherEvents.DISPENSE marks the placed spot (fm-audit)'),
    ('BlockPistonExtendEvent', 'QuestEvents.java'): ('✅', 'BLOCK_FORMED (PistonMovingBlockMixin)'),
    ('BlockPistonRetractEvent', 'QuestEvents.java'): ('✅', 'BLOCK_FORMED (PistonMovingBlockMixin)'),
    ('EntityChangeBlockEvent', 'QuestEvents.java'): ('✅', 'BLOCK_FORMED (FallingBlockLandMixin)'),
    ('BlockExplodeEvent', 'FarmingModule.java'): ('✅', 'crop blocks drop by loot table (PORTING Coast & Harvest)'),
    ('EntityExplodeEvent', 'FarmingModule.java'): ('✅', 'crop blocks drop by loot table'),
    ('BlockFromToEvent', 'FarmingModule.java'): ('✅', 'crop blocks drop by loot table'),
    ('BlockPistonExtendEvent', 'FarmingModule.java'): ('✅', 'crop blocks drop by loot table'),
    ('BlockPistonRetractEvent', 'FarmingModule.java'): ('✅', 'crop blocks drop by loot table'),
    ('EntityChangeBlockEvent', 'FarmingModule.java'): ('✅', 'crop blocks; FarmlandBlockMixin'),
    ('BlockFadeEvent', 'FarmingModule.java'): ('✅', 'crop blocks are in minecraft:maintains_farmland'),
    ('EntityCombustEvent', 'Keepers.java'): ('✅', 'keeper villager: permanently invulnerable, no AI'),
    ('EntityDamageEvent', 'Keepers.java'): ('✅', 'keeper villager: permanently invulnerable'),
    ('EntityDeathEvent', 'Keepers.java'): ('✅', 'keeper villager: permanently invulnerable'),
    ('EntityPortalEvent', 'Keepers.java'): ('✅', 'keeper villager: no AI, no gravity'),
    ('EntityPotionEffectEvent', 'Keepers.java'): ('✅', 'FeatherEvents.EFFECT_ADD refuses every effect on keepers (ShopEvents, fm-qa)'),
    ('EntityTargetEvent', 'Keepers.java'): ('✅', 'keeper villager: no AI'),
    ('*', 'ShopClicks.java'): ('✅', 'shop/ShopEvents (UseBlockCallback, PlayerBlockBreakEvents)'),
    ('*', 'CompanionModule.java'): ('✅', 'net/Companion (DISCONNECT, ClientboundPlayChannelEvents.REGISTER)'),
    # fm-furniture (checked by hand, fm-qa): Oraxen base entities + barrier/display overlays became block entities.
    ('BlockBreakEvent', 'PowerModule.java'): ('✅', 'claims guard every block break; PowerBlockEntity drops its fuel, loot table the item'),
    ('ChunkUnloadEvent', 'PowerModule.java'): ('✅', 'machines are block entities (FurnitureTicker): no in-memory index to unload'),
    ('EntitiesLoadEvent', 'PowerModule.java'): ('✅', 'no display entities; FurnitureLegacy removes Paper feather:power_display strays'),
    ('PlayerQuitEvent', 'PowerModule.java'): ('✅', 'no per-player state (placedTick was a Paper double-event guard)'),
    ('EntitiesLoadEvent', 'CuriosModule.java'): ('✅', 'CurioBlockEntity + client renderer; FurnitureLegacy removes feather:curio_part strays'),
    ('EntitiesUnloadEvent', 'CuriosModule.java'): ('✅', 'CurioBlockEntity: nothing tracked in memory'),
    ('EntityRemoveEvent', 'CuriosModule.java'): ('✅', 'CurioBlockEntity.preRemoveSideEffects drops the contents'),
    ('EntitiesLoadEvent', 'GadgetsModule.java'): ('✅', 'GadgetBlockEntity + client renderer; FurnitureLegacy removes feather:gadget_owner strays'),
    ('EntitiesUnloadEvent', 'GadgetsModule.java'): ('✅', 'GadgetBlockEntity: nothing tracked in memory'),
    ('ItemSpawnEvent', 'GadgetsModule.java'): ('✅', 'stateful drop: player break handled by the module, explosions via LootTableEvents.MODIFY_DROPS'),
    ('EntityTransformEvent', 'Drops.java'): ('✅', 'vanilla SPLIT_ON_DEATH copies entity tags, so the spawn-reason tag reaches split slimes'),
    ('PlayerJoinEvent', 'Relics.java'): ('✅', 'feature/altars/BossRelics: ServerPlayConnectionEvents.JOIN restores the leap cooldown'),
    ('PlayerQuitEvent', 'Relics.java'): ('✅', 'feature/altars/BossRelics: DISCONNECT'),
}


def new_counterparts(old_file):
    """New files that hold the port of an old file: same class name anywhere in mod/src, plus their packages."""
    base = os.path.basename(old_file)
    hits = [f for f in NEW if os.path.basename(f) == base]
    dirs = {os.path.dirname(f) for f in hits}
    alias = {'ClaimsOraxen.java': 'claims', 'MegHook.java': 'pets', 'FishMegHook.java': 'fishing', 'WildMeg.java': 'wildlife',
             'MegMounts.java': 'bank', 'MythicPets.java': 'pets', 'MythicFx.java': 'bossfx', 'MythicAllies.java': 'bossfx',
             'AdvancementMythic.java': 'advancements', 'AdvancementOraxen.java': 'advancements', 'AnalyticsBosses.java': 'analytics',
             'Mailboxes.java': 'mail', 'Inspector.java': 'moderation', 'OraxenStaleMarkers.java': None, 'PackBridge.java': None,
             'Relics.java': 'relics', 'Generators.java': 'power', 'ArcanaTools.java': 'tools', 'CurioDisplays.java': 'curios'}
    if base in alias and alias[base]:
        dirs |= {os.path.dirname(f) for f in NEW if os.sep + alias[base] + os.sep in f and os.sep + 'feature' + os.sep in f}
    return sorted({f for f in NEW if os.path.dirname(f) in dirs}) if dirs else []


def event_rows():
    rows = []
    for ev, files in sorted(bukkit_events().items()):
        cands = EVENT_EQUIV.get(ev, 'unmapped')
        for of in sorted(files):
            name = rel(of).split('feather/', 1)[-1]
            ov = OVERRIDES.get((ev, os.path.basename(of))) or OVERRIDES.get(('*', os.path.basename(of)))
            if ov:
                rows.append((ev, name) + ov)
                continue
            if os.path.basename(of) in ('PackBridge.java', 'OraxenStaleMarkers.java'):
                rows.append((ev, name, '⚠️', 'dropped (see PORTING "Dropped")'))
                continue
            if cands is None:
                rows.append((ev, name, '⚠️', NO_EQUIV[ev]))
                continue
            if cands == 'unmapped':
                rows.append((ev, name, '?', 'unmapped event'))
                continue
            nf = new_counterparts(of)
            found = [c for c in cands if any(c in NEW[f] for f in nf)]
            if found:
                rows.append((ev, name, '✅', found[0] + ' in ' + ', '.join(sorted({os.path.basename(f) for f in nf if found[0] in NEW[f]}))[:80]))
            else:
                rows.append((ev, name, '❌', 'no ' + '/'.join(cands[:3]) + ' in ' + (os.path.basename(os.path.dirname(nf[0])) if nf else 'no counterpart')))
    return rows


# ------------------------------------------------------------------ hand-checked notes (fm-audit)
_PET_MODEL = ('⚠️', 'fm-cohesion: Blockbench model streaming removed; menus draw the native GeckoLib creature (CreatureView)')
_ITEM_EXPORT = ('⚠️', 'fm-cohesion: server item export (aftrhrs:server_* tabs) removed; native items have real creative tabs')
MODULE_NOTES = {
    'modelengine-pack-bridge': ('⚠️', 'dropped: assets ship in the mod jar; key kept so old configs stay valid'),
}
MESSAGE_NOTES = {
    'companion.outdated': ('⚠️', 'dropped: client and server are one jar now (PORTING)'),
    'companion.outdated-link': ('⚠️', 'dropped: client and server are one jar now'),
    'fishing.no-cook': ('⚠️', 'native fish match no cooking recipe, nothing to refuse'),
}
for _k in ('state-line', 'pane-ready', 'pane-waiting', 'countdown', 'you-lore', 'them-lore', 'ready-lore', 'unready-lore',
           'cancel-lore', 'countdown-lore'):
    MESSAGE_NOTES['trade.gui.' + _k] = ('⚠️', 'trade is a native screen (TradeMenu): labels only, no chest-icon lore/panes')
CONFIG_NOTES = {
    'BARREL': ('⚠️', 'storage fallback material: Paper-only (blocks are real now)'),
    'CHEST': ('⚠️', 'storage fallback material: Paper-only'),
    'SAPLING': ('⚠️', 'storage fallback material: Paper-only'),
    'SHULKER': ('⚠️', 'storage fallback material: Paper-only'),
    'TNT': ('⚠️', 'storage fallback material: Paper-only'),
    'fallback': ('⚠️', 'storage.blocks.*.fallback: Paper-only'),
    'skin': ('⚠️', 'storage.blocks.*.skin: Paper-only'),
    'display.scale': ('⚠️', 'storage display: Paper-only'),
    'display.yaw-offset': ('⚠️', 'storage display: Paper-only'),
    'storage.blocks': ('⚠️', 'Paper-only (the bundled config.yml says so)'),
    'MINECART': ('✅', 'not a config key (Material name match); claims match "minecart" ids'),
    'float': ('✅', 'not a config key (Paper goal name)'),
    'bond.xp.mechanic-max': ('⚠️', 'cap for featherpet{a=xp}; no Arcana skill used that mechanic, no native equivalent'),
    'mythic-level': ('⚠️', 'MythicMobs level of the police mob: no MythicMobs'),
    'squallgull.dive.target-variable': ('⚠️', 'MythicMobs variable name: no MythicMobs'),
    'companion.creative': _ITEM_EXPORT,
    'creative': _ITEM_EXPORT,
    'exclude.': _ITEM_EXPORT,
    'files': _ITEM_EXPORT,
    'ids': _ITEM_EXPORT,
    'kinds': _ITEM_EXPORT,
    'rules': _ITEM_EXPORT,
    'companion.latest-mod': ('⚠️', 'dropped: outdated-mod nudge (same jar)'),
    'companion.mod-download': ('⚠️', 'dropped: outdated-mod nudge (same jar)'),
    'resolution': ('⚠️', 'not a config key: Blockbench JSON field of the removed pet_model streaming (fm-cohesion)'),
    'homes.import-essentials-homes': ('⚠️', 'dropped: Essentials data was imported on Paper'),
    'spawns': ('⚠️', 'EssentialsFiles: dropped'),
    'world-name': ('⚠️', 'EssentialsFiles: dropped'),
    'max-rebuild-requests': ('⚠️', 'PackBridge: dropped'),
    'reload-command': ('⚠️', 'PackBridge: dropped'),
    'reload-delay-ticks': ('⚠️', 'PackBridge: dropped'),
    'reload-oraxen': ('⚠️', 'PackBridge: dropped'),
    'take-over-commands': ('⚠️', 'moot without EssentialsX: Feather commands always replace same-named vanilla ones'),
    # fm-furniture packages (checked by hand, fm-qa)
    'GLASS': ('✅', 'not a config key (Material name match in AreaTools)'),
    'GLAZED': ('✅', 'not a config key (Material name match in Fans)'),
    'arm': ('✅', 'not a config key (curio display part name)'),
    'hand': ('✅', 'not a config key (curio display part name)'),
    'hat': ('✅', 'not a config key (curio display part name)'),
    'fuel': ('✅', 'read as power.yml generator.fuel (PowerModule)'),
    'hopper-feed': ('✅', 'read as power.yml generator.hopper-feed (PowerModule)'),
    'generator.overlay': ('⚠️', 'the burning look is the generator block lit state (gen_content _overlay model), no overlay display'),
    'drops.mythic-mobs-use-vanilla-entries': ('⚠️', 'Arcana creatures are their own entity types, no vanilla base type to match; default false behaves the same'),
    'pet-whistle.signal': ('⚠️', 'MythicMobs signal name: the whistle calls pets directly (PetWhistle)'),
    'pet-whistle.mob-type-prefix': ('⚠️', 'MythicMobs id filter: the whistle reads pet records directly'),
    'pet-whistle.message-unavailable': ('⚠️', 'shown only when MythicMobs was missing; the native whistle is always available'),
    'rotate.enabled': ('⚠️', 'sneak + empty-hand rotation is content (FurnitureBlock.turned, hit sound); curios.yml rotate.* unread'),
    'rotate.ids': ('⚠️', 'content rotates every single-block piece; module hooks (hat stand wobble, rope pull) claim the click first'),
    'rotate.exclude': ('⚠️', 'hat stands and ropes are claimed by their module hooks before rotation'),
    'rotate.step-degrees': ('⚠️', 'content turns facing pieces 90 degrees (the default)'),
    'rotate.strict-step-degrees': ('⚠️', 'content turns 16-way pieces by the spec rotStep'),
    'rotate.sound': ('⚠️', 'content plays the block hit sound (wood pieces: block.wood.hit, the default)'),
    'slide-speed': ('⚠️', 'ropes are minecraft:climbable: vanilla ladder slide (0.15 vs Paper 0.12 blocks/tick)'),
    'slide-sound': ('⚠️', 'ropes are minecraft:climbable: vanilla climbing step sounds'),
}
DATA_NOTES = {
    'data/maintenance.yml': ('✅', 'MotdModule: dataFile("maintenance.yml")'),
    'last-sync.sha256': ('⚠️', 'PackBridge: dropped'), 'packbridge-last-sync.sha256': ('⚠️', 'PackBridge: dropped'),
    'packbridge.yml': ('⚠️', 'PackBridge: dropped (not bundled)'), 'leather_horse_armor.json': ('⚠️', 'PackBridge: dropped'),
    'spawn.yml': ('⚠️', 'EssentialsSpawn import: dropped (spawn lives in places.yml)'),
}
C2S_NOTES = {
    'pet_model': _PET_MODEL,
}
S2C_NOTES = {
    'pet_model': _PET_MODEL, 'pet_model_ok': _PET_MODEL, 'pet_model_missing': _PET_MODEL,
    'items': _ITEM_EXPORT, 'items_ok': _ITEM_EXPORT,
    'help': ('✅', 'not a Companion message (farming text key)'),
    'claimed': ('✅', 'not a Companion message'),
    'overencumbered': ('✅', 'not a Companion message (gadgets)'),
    'unencumbered': ('✅', 'not a Companion message (gadgets)'),
}


def table(header, rows):
    out = ['| ' + ' | '.join(header) + ' |', '|' + '---|' * len(header)]
    for r in rows:
        out.append('| ' + ' | '.join(str(c).replace('|', '/') for c in r) + ' |')
    return '\n'.join(out)


def counts(rows, col):
    from collections import Counter
    c = Counter(r[col] for r in rows)
    return ', '.join('%s %d' % (k, c[k]) for k in ('✅', '⚠️', '❌', '?') if c[k])


def main():
    res = {}
    out = []
    # 1. commands
    cmds = plugin_commands()
    newb = bound_commands(NEW)
    rows = []
    for name, spec in cmds.items():
        n = name in newb or name == 'feather'
        where = 'CoreModule.java' if name == 'feather' else ', '.join(sorted({os.path.basename(f) for f in newb.get(name, [])}))
        rows.append(('/' + name, ', '.join(spec['aliases']), spec['permission'] or '', '✅' if n else '❌', where))
    res['commands'] = rows
    out.append('### Commands (res/plugin.yml -> CommandBridge.bind)\n\n%d commands: %s. Aliases and permissions come from the '
               'bundled plugin.yml, byte-identical to res/plugin.yml.\n' % (len(rows), counts(rows, 3)))
    out.append(table(['command', 'aliases', 'permission', '', 'bound in'], rows))
    # 2. modules
    regs = dict(registered_modules())
    rows = []
    for k in config_modules():
        st = ('✅', regs[k].split('/')[-1]) if k in regs else MODULE_NOTES.get(k, ('❌', ''))
        rows.append((k,) + st)
    res['modules'] = rows
    out.append('\n### Module toggles (config.yml modules.* -> Modules.java)\n\n%d toggles: %s.\n' % (len(rows), counts(rows, 1)))
    out.append(table(['modules.<key>', '', 'registered in / note'], rows))
    # 3. messages
    mrows = msg_keys_report()
    res['messages'] = mrows
    used_old = [r for r in mrows if r[1]]
    dyn = [r for r in mrows if not r[1]]
    gone = [r for r in mrows if r[1] and not r[2]]
    out.append('\n### messages.yml keys\n\n%d keys. Referenced by the old code: %d (as a literal %d, through a dynamic prefix or a '
               'relative suffix %d); built only at run time (button .label/.hover, palette, announcements) %d. Still '
               'referenced by the mod: %d of the %d. Keys the old code used that the mod no longer references: %d.\n'
               % (len(mrows), len(used_old), sum(1 for r in mrows if r[1] == 'lit'), sum(1 for r in mrows if r[1] in ('pre', 'suffix')),
                  len(dyn), len(used_old) - len(gone), len(used_old), len(gone)))
    out.append(table(['key', '', 'why'], [(k,) + MESSAGE_NOTES.get(k, ('❌', 'unexplained')) for k, _, _ in gone]))
    # 4. config reads
    oc = config_reads(OLD)
    nc = config_reads(NEW)
    rows = [(k, ', '.join(sorted({os.path.basename(f) for f in oc[k]}))) + CONFIG_NOTES.get(k, ('❌', 'unexplained'))
            for k in sorted(oc) if k not in nc]
    res['config'] = rows
    out.append('\n### Config keys read\n\nLiteral keys passed to a getter (getString/getInt/.../getConfigurationSection, contains) '
               'anywhere in the old code: %d. Read by the mod too: %d. Not read: %d (%s).\n'
               % (len(oc), len(oc) - len(rows), len(rows), counts(rows, 2)))
    out.append(table(['key', 'old reader', '', 'why'], rows))
    # 5. data files
    od = data_files(OLD)
    nd = data_files(NEW)
    rows = []
    for k in sorted(od):
        st = ('✅', ', '.join(sorted({os.path.basename(f) for f in nd[k]}))[:90]) if k in nd else DATA_NOTES.get(k, ('❌', 'unexplained'))
        rows.append((k, ', '.join(sorted({os.path.basename(f) for f in od[k]}))[:70]) + st)
    res['data'] = rows
    out.append('\n### Files under plugins/Feather (configs and data/)\n\nEvery file name the old code read or wrote: %d (%s). '
               'Plus the folders data/players/<uuid>.yml (core Profiles), data/stats/ (analytics), exports/ (analytics). '
               'Same layout: FeatherImport copies plugins/Feather into aftrhrs/ as is.\n' % (len(rows), counts(rows, 2)))
    out.append(table(['file', 'old', '', 'mod'], rows))
    # 6. mobs
    mm = mythic_ids()
    sp = species_mm()
    rows = [(k, f, '✅' if k in sp else '❌', ('aftrhrs:' + sp[k]) if k in sp else '') for k, f in mm.items()]
    res['mobs'] = rows
    out.append('\n### MythicMobs mobs (packs/Arcana/mobs/*.yml -> entity types)\n\n%d mob ids: %s. Extra native type: '
               'aftrhrs:wukong_clone (the hair clones, a MythicMobs summon of ArcanaWukong on Paper).\n' % (len(rows), counts(rows, 2)))
    out.append(table(['MythicMobs id', 'file', '', 'entity type'], rows))
    # 7. items
    ox = oraxen_ids()
    ct = content_ids()
    missing = [(k, f) for k, f in ox.items() if k not in ct]
    extra = sorted(set(ct) - set(ox))
    res['items'] = {'oraxen': len(ox), 'content': len(ct), 'missing': missing, 'extra': extra}
    out.append('\n### Oraxen items (Oraxen/items/*.yml -> aftrhrs:<id>)\n\n%d Oraxen ids, %d native content ids; ✅ %d. Not '
               'registered: %s (Oraxen\'s own GUI icons in guis.yml; Feather never used them: ⚠️). Native-only ids: %s.\n'
               % (len(ox), len(ct), len(ox) - len(missing), ', '.join(k for k, _ in missing) or 'none', ', '.join(extra) or 'none'))
    # 8. companion
    oh = companion_handlers(OLD)
    nh = companion_handlers(NEW)
    rows = []
    for k in sorted(oh):
        new = sorted({x.split('/')[-1] for x in nh.get(k, []) if '/main/' in x})
        st = ('✅', ', '.join(new)) if new else C2S_NOTES.get(k, ('❌', ''))
        rows.append((k, ', '.join(sorted({x.split('/')[-1] for x in oh[k]}))) + st)
    res['c2s'] = rows
    out.append('\n### Companion client -> server messages (old Companion.on handlers)\n\n%d types: %s.\n' % (len(rows), counts(rows, 2)))
    out.append(table(['type', 'old handler', '', 'mod handler / note'], rows))
    os_ = companion_sends(OLD)
    ns = companion_sends(NEW)
    rows = []
    for k in sorted(os_):
        new = sorted({x.split('/')[-1] for x in ns.get(k, []) if '/main/' in x})
        st = S2C_NOTES.get(k) or (('✅', ', '.join(new)) if new else ('❌', ''))
        rows.append((k, ', '.join(sorted({x.split('/')[-1] for x in os_[k]}))) + st)
    res['s2c'] = rows
    out.append('\n### Companion server -> client messages\n\n%d types: %s.\n' % (len(rows), counts(rows, 2)))
    out.append(table(['type', 'old sender', '', 'mod sender / note'], rows))
    # 9. listeners
    rows = event_rows()
    res['events'] = rows
    out.append('\n### Bukkit event listeners\n\n%d (event, class) pairs from @EventHandler methods: %s. ✅ = the port\'s '
               'package uses an equivalent hook (mechanical: Fabric callback / FeatherEvents / CreatureEvents / native '
               'block or entity behavior) or was checked by hand (OVERRIDES in tools/parity.py).\n' % (len(rows), counts(rows, 2)))
    out.append(table(['event', 'old class', '', 'equivalent'], rows))
    print('\n'.join(out))
    if '--json' in sys.argv:
        json.dump(res, open(sys.argv[sys.argv.index('--json') + 1], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
