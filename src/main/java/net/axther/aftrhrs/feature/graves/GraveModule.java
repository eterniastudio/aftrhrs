package net.axther.aftrhrs.feature.graves;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.JoinDigest;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.PlayerPicker;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Graves: when a player dies with items to drop, the drops and the dropped XP go into a grave (their own head on the
 * ground near the death, with a floating name and clock) instead of on the ground. Right-click opens it for the owner
 * and the players they trust ({@code /grave trust}, or Settings); Take All puts everything back where it was (armor
 * on, offhand, the backpack on the back). Graves are locked for {@code graves.locked-minutes}, then open to everyone for
 * {@code graves.open-minutes}, then the items spill out. The grave block can't be broken, blown up, pushed, burned or
 * washed away; only the owner of the claim it stands in may break it, which spills it. Graves never go into a claim the
 * dead player can't build in. Bank robbery deaths keep dropping on the ground. Data: {@code data/graves.yml} (same
 * format as Paper's, items as Paper's item bytes), written before the inventory is emptied.
 */
public final class GraveModule implements FeatherModule {
    static final String USE = "feather.grave";
    static final String ADMIN = "feather.grave.admin";
    static final String DISPLAY_KEY = "feather:grave_display";
    static final String BLOCK_KEY = "feather:grave";
    /** A grave entry's slot for the backpack worn on the back ({@code Hooks.BackSlot}; 0-40 are inventory slots). */
    static final int BACK_SLOT = 41;
    /** Grave entry slots for the outfit (cosmetics, {@code Hooks.Outfit}): hat, then cosmetic head, chest, legs, feet. */
    static final int OUTFIT_FIRST = 42, OUTFIT_SIZE = 5;
    /** Bukkit BlockFace names in head rotation order (rotation 0 = south, clockwise in 16ths). */
    private static final String[] FACES = {"SOUTH", "SOUTH_SOUTH_WEST", "SOUTH_WEST", "WEST_SOUTH_WEST", "WEST", "WEST_NORTH_WEST",
            "NORTH_WEST", "NORTH_NORTH_WEST", "NORTH", "NORTH_NORTH_EAST", "NORTH_EAST", "EAST_NORTH_EAST", "EAST", "EAST_SOUTH_EAST",
            "SOUTH_EAST", "SOUTH_SOUTH_EAST"};

    /** What happened at a player's last death, for the note after respawning. */
    private record Outcome(Grave grave, boolean noSpot) {
    }

    private static volatile GraveModule active;

    private final Feather plugin;
    private final GraveMenu menu;
    private final Path file;
    private final Map<String, Grave> graves = new LinkedHashMap<>();
    private final Map<Grave.Pos, Grave> byPos = new HashMap<>();
    /** Graves that couldn't be read; written back untouched so nothing in them is lost. */
    private final Map<String, Map<String, Object>> unreadableGraves = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> trust = new HashMap<>();
    /** Things that happened to an owner's graves while they were offline, told on join. */
    private final Map<UUID, List<String>> notices = new HashMap<>();
    private final Map<UUID, Loc> lastGround = new HashMap<>();
    private final Map<UUID, Outcome> outcomes = new HashMap<>();
    private final Map<UUID, Long> lastNote = new HashMap<>();
    private Tasks.Task task;
    private boolean unreadable;

    private long lockedMs;
    private long openMs;
    private int maxPerPlayer;
    private int searchRadius;
    private double spawnRadius;
    private double bankRadius;
    private boolean altarFights;
    private boolean hologram;
    private Set<String> disabledWorlds = Set.of();

    public GraveModule(Feather plugin) {
        this.plugin = plugin;
        this.menu = new GraveMenu(plugin, this);
        this.file = plugin.dataFile("graves.yml");
    }

    @Override
    public String name() {
        return "Graves";
    }

    @Override
    public void enable() {
        reload();
        load();
        active = this;
        plugin.once("graves.events", GraveModule::registerEvents);
        plugin.bind("grave", new FeatherCommand() {
            @Override
            public boolean onCommand(Sender sender, String label, String[] args) {
                command(sender, args);
                return true;
            }

            @Override
            public List<String> onTabComplete(Sender sender, String label, String[] args) {
                return complete(sender, args);
            }
        });
        plugin.hub().addSettingsButton("graves", Hub.SETTINGS_GRAVES, p -> Perms.has(p, USE), this::addSettingsButton);
        plugin.provide(Hooks.Graves.class, new Hooks.Graves() {
            @Override
            public List<GravePlace> of(UUID owner) {
                List<GravePlace> out = new ArrayList<>();
                int n = 0;
                for (Grave g : GraveModule.this.of(owner)) out.add(new GravePlace(++n, g.world, g.x + 0.5, g.y, g.z + 0.5));
                return out;
            }

            @Override
            public Component deathPointLine(ServerPlayer player, Profiles.DeathPoint death) {
                return GraveModule.this.deathPointLine(player, death);
            }

            @Override
            public Loc trackTarget(UUID owner, Profiles.DeathPoint death) {
                return GraveModule.this.trackTarget(owner, death);
            }
        });
        task = Tasks.timer(20L, 20L, this::tick);
        JoinDigest.register("graves", 60, this::collectJoin);
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig().getConfigurationSection("graves");
        if (c == null) c = new Yaml();
        lockedMs = Math.max(0, c.getLong("locked-minutes", 60)) * 60_000L;
        openMs = Math.max(1, c.getLong("open-minutes", 30)) * 60_000L;
        maxPerPlayer = Math.max(1, c.getInt("max-per-player", 3));
        searchRadius = Math.max(1, Math.min(16, c.getInt("search-radius", 8)));
        spawnRadius = Math.max(0, c.getDouble("keep-clear.spawn-radius", 16));
        bankRadius = Math.max(0, c.getDouble("keep-clear.bank-radius", 10));
        altarFights = c.getBoolean("keep-clear.altar-fights", true);
        hologram = c.getBoolean("hologram", true);
        Set<String> worlds = new HashSet<>();
        for (String w : c.getStringList("disabled-worlds")) worlds.add(w.toLowerCase(Locale.ROOT));
        disabledWorlds = worlds;
        if (!hologram) for (Grave g : graves.values()) removeDisplay(g);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        JoinDigest.unregister("graves");
        menu.closeEverything();
        for (Grave g : graves.values()) removeDisplay(g);
        save();
        plugin.withdraw(Hooks.Graves.class);
        if (active == this) active = null;
    }

    private Text text() {
        return plugin.text();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        graves.clear();
        byPos.clear();
        unreadableGraves.clear();
        trust.clear();
        notices.clear();
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "graves");
        if (yml == null) {
            unreadable = true;
            plugin.getLogger().error("Graves are off until data/graves.yml is fixed: deaths drop items on the ground as usual.");
            return;
        }
        unreadable = false;
        Yaml gs = yml.getConfigurationSection("graves");
        if (gs != null) {
            for (String id : gs.getKeys(false)) {
                Yaml s = gs.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    Grave g = Grave.load(id, s);
                    graves.put(id, g);
                    byPos.put(g.pos(), g);
                } catch (RuntimeException e) {
                    unreadableGraves.put(id, s.getValues(false));
                    plugin.getLogger().error("Could not read grave {} in graves.yml ({}); it is kept in the file untouched.", id, e.toString());
                }
            }
        }
        Yaml ts = yml.getConfigurationSection("trust");
        if (ts != null) {
            for (String owner : ts.getKeys(false)) {
                try {
                    Set<UUID> set = new LinkedHashSet<>();
                    for (String s : ts.getStringList(owner)) set.add(UUID.fromString(s));
                    trust.put(UUID.fromString(owner), set);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        Yaml ns = yml.getConfigurationSection("notices");
        if (ns != null) {
            for (String owner : ns.getKeys(false)) {
                try {
                    notices.put(UUID.fromString(owner), new ArrayList<>(ns.getStringList(owner)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (!graves.isEmpty()) plugin.getLogger().info("Graves: {} loaded.", graves.size());
    }

    /** Writes graves.yml (atomically). False when it couldn't be written or the file is unreadable. */
    boolean save() {
        if (unreadable) return false;
        Yaml yml = new Yaml();
        for (Grave g : graves.values()) g.save(yml.createSection("graves." + g.id));
        for (Map.Entry<String, Map<String, Object>> e : unreadableGraves.entrySet()) yml.createSection("graves." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, Set<UUID>> e : trust.entrySet()) {
            if (!e.getValue().isEmpty()) yml.set("trust." + e.getKey(), new ArrayList<>(e.getValue().stream().map(UUID::toString).toList()));
        }
        for (Map.Entry<UUID, List<String>> e : notices.entrySet()) {
            if (!e.getValue().isEmpty()) yml.set("notices." + e.getKey(), new ArrayList<>(e.getValue()));
        }
        return SafeYaml.write(file, yml.saveToString(), plugin.getLogger());
    }

    // ------------------------------------------------------------------ lookups (also used by death points)

    Grave at(ServerLevel level, BlockPos pos) {
        return level == null || pos == null || byPos.isEmpty() ? null : byPos.get(Grave.Pos.of(level, pos));
    }

    boolean isGrave(ServerLevel level, BlockPos pos) {
        return !byPos.isEmpty() && at(level, pos) != null;
    }

    List<Grave> of(UUID owner) {
        List<Grave> out = new ArrayList<>();
        for (Grave g : graves.values()) if (g.owner.equals(owner)) out.add(g);
        out.sort(Comparator.comparingLong(g -> g.created));
        return out;
    }

    boolean trusts(UUID owner, UUID other) {
        Set<UUID> s = trust.get(owner);
        return s != null && s.contains(other);
    }

    boolean canOpen(ServerPlayer p, Grave g) {
        return g.owner.equals(p.getUUID()) || trusts(g.owner, p.getUUID()) || !g.locked(System.currentTimeMillis());
    }

    /** The grave made at this death point's death, if it's still there. */
    Grave graveFor(UUID owner, Profiles.DeathPoint d) {
        for (Grave g : graves.values()) {
            if (g.owner.equals(owner) && Math.abs(g.deathTime - d.time()) < 5000) return g;
        }
        return null;
    }

    /** Where the death point arrow should lead: the grave, when there is one. */
    Loc trackTarget(UUID owner, Profiles.DeathPoint d) {
        Grave g = graveFor(owner, d);
        return g == null ? null : g.center();
    }

    /** The death point dialog's line about this death's grave, or null when it has none. */
    Component deathPointLine(ServerPlayer p, Profiles.DeathPoint d) {
        Grave g = graveFor(p.getUUID(), d);
        if (g == null) return null;
        return text().msg("grave.deathpoint-line", status(g));
    }

    /** {@code <coords> <world> <status> <time>} for a grave. */
    private TagResolver[] status(Grave g) {
        long now = System.currentTimeMillis();
        boolean locked = g.locked(now);
        return new TagResolver[]{Text.unparsed("coords", g.coords()), Text.unparsed("world", prettyWorld(g.world)),
                Text.component("status", text().msg(locked ? "grave.status-locked" : "grave.status-open")),
                Text.unparsed("time", Placeholders.duration(Math.max(0, (locked ? g.unlockAt : g.expireAt) - now))),
                Text.unparsed("owner", g.ownerName), Text.unparsed("stacks", g.stacks()), Text.unparsed("id", g.id)};
    }

    static String prettyWorld(String w) {
        return switch (w) {
            case "world" -> "Overworld";
            case "world_nether" -> "Nether";
            case "world_the_end" -> "The End";
            default -> w;
        };
    }

    // ------------------------------------------------------------------ dying

    private boolean robberyDeath(ServerPlayer p) {
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        return bank != null && bank.robberyDeath(p);
    }

    /** At the start of the death, before anything drops: the inventory and the XP go into a grave. */
    private void onDeath(ServerPlayer p) {
        outcomes.remove(p.getUUID());
        ServerLevel level = p.level();
        if (level.getGameRules().get(GameRules.KEEP_INVENTORY) || p.isSpectator()) return;
        if (!Perms.has(p, USE) || disabledWorlds.contains(Worlds.name(level).toLowerCase(Locale.ROOT))) return;
        // Bank robbery: dying or quitting mid-robbery drops everything on the ground, exactly as before.
        if (robberyDeath(p)) return;
        if (unreadable) return;
        Inventory inv = p.getInventory();
        // Where each drop comes from: the inventory slot (0-35 storage, 36-39 armor, 40 offhand).
        List<Grave.Entry> entries = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty()) continue;
            // Vanilla deletes these on death; never keep one.
            if (EnchantmentHelper.has(s, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) continue;
            // A Soulbound smithed tool stays with the player (smithing hands it back on respawn).
            if (net.axther.aftrhrs.feature.smithing.Soulbound.is(s)) continue;
            entries.add(new Grave.Entry(slot <= 40 ? slot : -1, s.copy()));
            slots.add(slot);
        }
        // The backpack worn on the back (relics) goes in too, as slot 41: Take All puts it back on.
        Hooks.BackSlot back = plugin.service(Hooks.BackSlot.class);
        ItemStack worn = back == null ? ItemStack.EMPTY : back.worn(p);
        if (!worn.isEmpty() && EnchantmentHelper.has(worn, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) worn = ItemStack.EMPTY;
        if (!worn.isEmpty()) entries.add(new Grave.Entry(BACK_SLOT, worn.copy()));
        // The outfit (cosmetic armor and the hat) goes in as slots 42..46: Take All puts it back on.
        Hooks.Outfit outfit = plugin.service(Hooks.Outfit.class);
        List<ItemStack> look = outfit == null ? List.of() : outfit.worn(p);
        boolean dressed = false;
        for (int i = 0; i < look.size() && i < OUTFIT_SIZE; i++) {
            if (look.get(i).isEmpty()) continue;
            entries.add(new Grave.Entry(OUTFIT_FIRST + i, look.get(i)));
            dressed = true;
        }
        if (entries.isEmpty()) return;
        Loc death = Loc.of(p);
        BlockPos spot;
        try {
            spot = spotFinder(p).find(death, lastGround.get(p.getUUID()));
        } catch (RuntimeException e) {
            plugin.getLogger().warn("Graves: looking for a spot for {} failed: {}", Names.name(p), e.toString());
            spot = null;
        }
        if (spot == null) {
            outcomes.put(p.getUUID(), new Outcome(null, true));
            plugin.getLogger().info("Graves: no safe spot near {}'s death at {} {}; items dropped as usual.", Names.name(p), death.worldName(), death.coords());
            return;
        }
        long now = System.currentTimeMillis();
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8);
        } while (graves.containsKey(id) || unreadableGraves.containsKey(id));
        Grave g = new Grave(id, p.getUUID(), Names.name(p), Worlds.name(level), spot.getX(), spot.getY(), spot.getZ(),
                BlockStateParser.serialize(level.getBlockState(spot)), now, now);
        g.unlockAt = now + lockedMs;
        g.expireAt = g.unlockAt + openMs;
        g.unlockNotified = lockedMs == 0;
        g.rotation = FACES[Math.floorMod(Math.round(p.getYRot() / 22.5f), 16)];
        g.xp = Math.min(p.experienceLevel * 7, 100);
        for (Property prop : p.getGameProfile().properties().get("textures")) {
            g.skinValue = prop.value();
            g.skinSignature = prop.signature();
        }
        g.items.addAll(entries);
        graves.put(g.id, g);
        byPos.put(g.pos(), g);
        Map<Grave, long[]> clocks = new HashMap<>();
        for (Grave o : of(p.getUUID())) clocks.put(o, new long[]{o.unlockAt, o.expireAt, o.unlockNotified ? 1 : 0});
        List<Grave> unlockedEarly = enforceCap(p.getUUID(), now);
        // On disk first; only then do the items leave the inventory.
        if (!save()) {
            graves.remove(g.id);
            byPos.remove(g.pos());
            for (Grave o : unlockedEarly) {
                long[] c = clocks.get(o);
                o.unlockAt = c[0];
                o.expireAt = c[1];
                o.unlockNotified = c[2] == 1;
            }
            plugin.getLogger().error("Graves: could not save graves.yml, so {}'s items dropped as usual.", Names.name(p));
            return;
        }
        // Everything worth keeping is in the grave; vanilla now finds nothing to drop and no XP to give.
        for (int slot : slots) inv.setItem(slot, ItemStack.EMPTY);
        if (!worn.isEmpty()) back.clear(p);
        if (dressed) outfit.clear(p);
        p.experienceLevel = 0;
        p.experienceProgress = 0;
        p.totalExperience = 0;
        // graves.yml is on disk: the player file follows, or a crash before the autosave gives everything twice.
        savePlayer(p);
        placeBlock(g);
        ensureDisplay(g, now);
        outcomes.put(p.getUUID(), new Outcome(g, false));
        plugin.placesChanged(p.getUUID());
        for (Grave o : unlockedEarly) tell(o, "grave.unlocked-early", null);
        plugin.getLogger().info("Graves: {}'s grave {} at {} {} ({} stacks, {} xp).", Names.name(p), g.id, g.world, g.coords(), g.stacks(), g.xp);
    }

    /** More locked graves than allowed: the oldest open up early. Returns the ones that did. */
    private List<Grave> enforceCap(UUID owner, long now) {
        List<Grave> locked = new ArrayList<>();
        for (Grave g : of(owner)) if (g.locked(now)) locked.add(g);
        List<Grave> out = new ArrayList<>();
        while (locked.size() > maxPerPlayer) {
            Grave oldest = locked.remove(0);
            oldest.unlockAt = now;
            oldest.expireAt = Math.min(oldest.expireAt, now + openMs);
            oldest.unlockNotified = true;
            out.add(oldest);
        }
        return out;
    }

    private GraveSpot spotFinder(ServerPlayer dying) {
        List<GraveSpot.Zone> zones = new ArrayList<>();
        GraveSpot.Zone vanilla = GraveSpot.vanillaSpawn();
        if (vanilla != null) zones.add(vanilla);
        if (spawnRadius > 0) {
            Hooks.Spawn spawn = plugin.service(Hooks.Spawn.class);
            Loc s = spawn == null ? null : spawn.spawn();
            if (s != null && s.level() != null) zones.add(new GraveSpot.Zone(s.level(), s.x(), s.z(), spawnRadius));
        }
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        if (bank != null && bankRadius > 0) {
            for (Loc l : bank.bankChests()) if (l.level() != null) zones.add(new GraveSpot.Zone(l.level(), l.x(), l.z(), bankRadius));
        }
        if (altarFights) {
            Hooks.AltarArenas altars = plugin.service(Hooks.AltarArenas.class);
            if (altars != null) {
                for (Loc l : altars.arenaCenters()) if (l.level() != null) zones.add(new GraveSpot.Zone(l.level(), l.x(), l.z(), altars.arenaRadius()));
            }
        }
        // Never in the block someone else is standing in.
        ServerLevel level = dying.level();
        Set<BlockPos> occupied = new HashSet<>();
        for (ServerPlayer o : level.players()) {
            if (o == dying || o.distanceToSqr(dying) > 32 * 32) continue;
            occupied.add(o.blockPosition());
            occupied.add(BlockPos.containing(o.getEyePosition()));
        }
        return new GraveSpot(searchRadius, zones, b -> isGrave(level, b) || occupied.contains(b),
                l -> plugin.claimAllows(dying, l, ClaimAction.BUILD));
    }

    private void onRespawn(ServerPlayer p) {
        Outcome o = outcomes.remove(p.getUUID());
        if (o == null) return;
        UUID id = p.getUUID();
        // Just after the death point note (10 ticks).
        Tasks.later(12L, () -> {
            ServerPlayer pl = plugin.player(id);
            if (pl == null) return;
            if (o.noSpot()) {
                text().send(pl, "grave.no-spot");
                return;
            }
            Grave g = o.grave();
            if (g.removed) return;
            Component button = Component.empty();
            List<Profiles.DeathPoint> points = deathPoints(pl);
            if (Perms.has(pl, "feather.deathpoint") && !points.isEmpty() && graveFor(pl.getUUID(), points.get(0)) == g) {
                button = text().button("grave.button-track", "/deathpoint track 1");
            }
            TagResolver[] r = status(g);
            TagResolver[] all = Arrays.copyOf(r, r.length + 2);
            all[r.length] = Text.component("coords-button", coordsButton(g));
            all[r.length + 1] = Text.component("button", button);
            text().send(pl, lockedMs > 0 ? "grave.respawn" : "grave.respawn-open", all);
        });
    }

    private List<Profiles.DeathPoint> deathPoints(ServerPlayer p) {
        Profiles.Profile prof = plugin.profiles().get(p);
        return prof == null ? List.of() : prof.deaths;
    }

    private Component coordsButton(Grave g) {
        return text().msg("grave.coords", Text.unparsed("coords", g.coords()))
                .hoverEvent(HoverEvent.showText(text().msg("deathpoint.coords-hover")))
                .clickEvent(ClickEvent.copyToClipboard(g.coords()));
    }

    // ------------------------------------------------------------------ the block and its hologram

    private void placeBlock(Grave g) {
        ServerLevel level = g.level();
        if (level == null) return;
        BlockPos pos = g.blockPos();
        int rot = Math.max(0, Arrays.asList(FACES).indexOf(g.rotation));
        BlockState head = Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, rot);
        level.setBlock(pos, head, 3);
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            PropertyMap props = g.skinValue == null ? PropertyMap.EMPTY
                    : new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", g.skinValue, g.skinSignature)));
            String name = g.ownerName == null || g.ownerName.length() > 16 ? "" : g.ownerName;
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(g.owner, name, props)));
            be.applyComponentsFromItemStack(stack);
            Pdc.putString(be, BLOCK_KEY, g.id);
            be.setChanged();
            level.sendBlockUpdated(pos, head, head, 3);
        }
    }

    /** The head should be there: put it back if something (a crash before the chunk saved, an edit tool) took it. */
    private void ensureBlock(Grave g) {
        ServerLevel level = g.level();
        if (level == null) return;
        BlockState s = level.getBlockState(g.blockPos());
        if (s.is(Blocks.PLAYER_HEAD)) return;
        if (GraveSpot.replaceable(s) > 0 || BlockStateParser.serialize(s).equals(g.original)) {
            placeBlock(g);
            plugin.getLogger().info("Graves: put grave {}'s head back at {} {}.", g.id, g.world, g.coords());
        } else if (!g.warnedMissing) {
            g.warnedMissing = true;
            plugin.getLogger().warn("Graves: grave {} at {} {} is now {}; its items are safe (/grave open {}).", g.id, g.world, g.coords(),
                    BlockStateParser.serialize(s), g.id);
        }
    }

    private void restoreBlock(Grave g) {
        ServerLevel level = g.level();
        if (level == null) return;
        BlockPos pos = g.blockPos();
        if (!level.getBlockState(pos).is(Blocks.PLAYER_HEAD)) return;
        BlockState d;
        try {
            d = BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), g.original, false).blockState();
        } catch (Exception e) {
            d = Blocks.AIR.defaultBlockState();
        }
        level.setBlock(pos, d, 3);
    }

    private net.minecraft.network.chat.Component hologramText(Grave g, long now) {
        boolean locked = g.locked(now);
        return Adv.vanilla(text().msg(locked ? "grave.hologram-locked" : "grave.hologram-open", Text.unparsed("name", g.ownerName),
                Text.unparsed("time", Placeholders.duration(Math.max(0, (locked ? g.unlockAt : g.expireAt) - now)))));
    }

    private void ensureDisplay(Grave g, long now) {
        if (!hologram) return;
        ServerLevel level = g.level();
        if (level == null) return;
        net.minecraft.network.chat.Component t = hologramText(g, now);
        Entity e = g.display == null ? null : level.getEntity(g.display);
        if (e instanceof Display.TextDisplay td && td.isAlive()) {
            td.setText(t);
            return;
        }
        Display.TextDisplay td = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
        td.setPos(g.x + 0.5, g.y + 0.95, g.z + 0.5);
        td.setText(t);
        td.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        td.setFlags(Display.TextDisplay.FLAG_SHADOW);
        Pdc.putString(td, DISPLAY_KEY, g.id);
        td.addTag(net.axther.aftrhrs.core.event.FeatherEvents.TRANSIENT_TAG);   // drawn again while the chunk is loaded, never saved
        level.addFreshEntity(td);
        g.display = td.getUUID();
    }

    private void removeDisplay(Grave g) {
        ServerLevel level = g.level();
        Entity e = g.display == null || level == null ? null : level.getEntity(g.display);
        if (e != null) e.discard();
        g.display = null;
    }

    /** Holograms that turn up with a chunk but don't belong to a live grave (an older copy) are cleared. */
    private void onEntityLoad(Entity e) {
        if (!(e instanceof Display.TextDisplay)) return;
        String id = Pdc.getString(e, DISPLAY_KEY);
        if (id == null) return;
        Grave g = graves.get(id);
        if (g == null || (g.display != null && !e.getUUID().equals(g.display))) {
            e.discard();
            return;
        }
        if (g.display == null) g.display = e.getUUID();
    }

    // ------------------------------------------------------------------ the clock

    private void tick() {
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (Grave g : new ArrayList<>(graves.values())) {
            if (g.removed) continue;
            if (!g.unlockNotified && now >= g.unlockAt) {
                g.unlockNotified = true;
                dirty = true;
                tell(g, "grave.unlocked", null);
            }
            if (now >= g.expireAt) {
                expireWhenLoaded(g);
                continue;
            }
            if (g.chunkLoaded()) {
                ensureBlock(g);
                ensureDisplay(g, now);
                menu.tickInfo(g);
            }
        }
        if (dirty) save();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (p.isDeadOrDying() || p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR || p.getAbilities().flying || p.isPassenger()) continue;
            if (!p.onGround() || p.isInLava() || p.isInWater()) continue;
            BlockPos at = p.blockPosition();
            if (p.level().getBlockState(at).is(Blocks.LAVA) || p.level().getBlockState(at.below()).is(Blocks.LAVA)) continue;
            lastGround.put(p.getUUID(), Loc.of(p));
        }
    }

    /** Grave expiry ticket: loads the grave's chunk off the server thread (10 s, not saved). Registered at mod init. */
    static TicketType EXPIRE_TICKET;
    /** Graves whose chunk is being loaded for their expiry. */
    private final Set<String> expiring = new HashSet<>();

    /** Registers the expiry ticket type; at mod init, before the registries freeze. */
    public static void initTicketType() {
        if (EXPIRE_TICKET == null) {
            EXPIRE_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, AftrHrs.id("grave_expire"), new TicketType(200L, TicketType.FLAG_LOADING));
        }
    }

    /** A timed expiry: spills at once when the chunk is loaded, else asks for it with a ticket and spills when it arrives. */
    private void expireWhenLoaded(Grave g) {
        ServerLevel level = g.level();
        if (level == null) return;   // the world isn't loaded: wait for it
        int cx = g.x >> 4, cz = g.z >> 4;
        if (level.getChunkSource().getChunkNow(cx, cz) != null || EXPIRE_TICKET == null) {
            expire(g, "grave.expired", null);
            return;
        }
        if (!expiring.add(g.id)) return;   // already asked
        ChunkPos cp = new ChunkPos(cx, cz);
        level.getChunkSource().addTicketAndLoadWithRadius(EXPIRE_TICKET, cp, 0).whenComplete((res, err) -> Tasks.sync(() -> {
            expiring.remove(g.id);
            // loaded: spill now; failed: the next clock tick asks again
            if (!g.removed && level.getChunkSource().getChunkNow(cx, cz) != null) expire(g, "grave.expired", null);
            level.getChunkSource().removeTicketWithRadius(EXPIRE_TICKET, cp, 0);
        }));
    }

    /** Spills the items where the grave stands (loading the chunk first), puts the old block back and forgets the grave. */
    private void expire(Grave g, String noticeKey, String player) {
        if (g.removed) return;
        ServerLevel level = g.level();
        if (level == null) return;   // the world isn't loaded: wait for it
        level.getChunk(g.x >> 4, g.z >> 4);
        spill(g, noticeKey, player);
    }

    private void spill(Grave g, String noticeKey, String player) {
        if (g.removed) return;
        menu.closeAll(g);
        ServerLevel level = g.level();
        if (level == null) return;
        List<ItemStack> loot = new ArrayList<>();
        for (Grave.Entry e : g.items) {
            if (e.item() != null && !e.item().isEmpty()) loot.add(e.item());
            e.set(null);
        }
        int xp = g.xp;
        g.xp = 0;
        // The old block goes back first: an item dropped inside the head would be pushed out sideways, maybe into lava.
        forget(g);
        Vec3 at = spillAt(level, g.blockPos());
        for (ItemStack s : loot) {
            // Laid down, not thrown: a scattered stack could roll off into something that destroys it.
            ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, s, 0, 0, 0);
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
        if (xp > 0) ExperienceOrb.award(level, at, xp);
        if (noticeKey != null) tell(g, noticeKey, player);
        plugin.getLogger().info("Graves: grave {} of {} at {} {} spilled ({}).", g.id, g.ownerName, g.world, g.coords(), noticeKey);
    }

    /** How far from a spill lava or fire must stay (overworld lava runs 3 blocks), and how far the spill may move. */
    private static final int HEAT_REACH = 4, SPILL_REACH = 6;

    /**
     * Where a spilled grave's items are laid down: on its own spot, unless lava or fire has come near it since the grave
     * was made (lava beside a grave pours into the spot as soon as the head is gone). Then the closest spot within a few
     * blocks that has a floor and no lava or fire within reach, in loaded chunks; failing that, the grave's own spot.
     */
    static Vec3 spillAt(ServerLevel level, BlockPos grave) {
        int r = SPILL_REACH + HEAT_REACH;
        List<BlockPos> heat = new ArrayList<>();
        for (BlockPos b : BlockPos.betweenClosed(grave.offset(-r, -3, -r), grave.offset(r, 4, r))) {
            if (level.hasChunkAt(b) && burns(level.getBlockState(b))) heat.add(b.immutable());
        }
        BlockPos best = null;
        if (spotOk(level, grave) && clearOf(heat, grave)) {
            best = grave;
        } else {
            double bestDist = Double.MAX_VALUE;
            for (BlockPos b : BlockPos.betweenClosed(grave.offset(-SPILL_REACH, -2, -SPILL_REACH), grave.offset(SPILL_REACH, 3, SPILL_REACH))) {
                double d = b.distSqr(grave);
                if (d < bestDist && level.hasChunkAt(b) && clearOf(heat, b) && spotOk(level, b)) {
                    bestDist = d;
                    best = b.immutable();
                }
            }
        }
        return Vec3.atBottomCenterOf(best != null ? best : grave).add(0, 0.1, 0);
    }

    /** No lava or fire close enough to run or spread onto {@code b} (level with it or above, where it flows from). */
    private static boolean clearOf(List<BlockPos> heat, BlockPos b) {
        for (BlockPos h : heat) {
            if (Math.abs(h.getX() - b.getX()) <= HEAT_REACH && Math.abs(h.getZ() - b.getZ()) <= HEAT_REACH
                    && h.getY() >= b.getY() - 1 && h.getY() <= b.getY() + 3) return false;
        }
        return true;
    }

    /** Room for items to lie: an open spot over a floor that doesn't hurt them (or water, where they float). */
    private static boolean spotOk(ServerLevel level, BlockPos b) {
        BlockState s = level.getBlockState(b);
        if (burns(s) || s.is(Blocks.CACTUS) || !s.getCollisionShape(level, b).isEmpty()) return false;
        BlockState floor = level.getBlockState(b.below());
        if (burns(floor) || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CACTUS)) return false;
        if (s.getFluidState().is(FluidTags.WATER)) return true;
        if (!floor.isFaceSturdy(level, b.below(), Direction.UP)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = b.relative(d);
            if (level.hasChunkAt(n) && level.getBlockState(n).is(Blocks.CACTUS)) return false;   // never load a neighbour chunk
        }
        return true;
    }

    private static boolean burns(BlockState s) {
        return s.getFluidState().is(FluidTags.LAVA) || s.is(BlockTags.FIRE) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE);
    }

    /** Takes the grave away: old block back, hologram gone, off disk. */
    private void forget(Grave g) {
        g.removed = true;
        menu.closeAll(g);
        restoreBlock(g);
        removeDisplay(g);
        graves.remove(g.id);
        byPos.remove(g.pos());
        save();
        plugin.placesChanged(g.owner);
    }

    /** Tells the owner about one of their graves now, or when they next join. */
    private void tell(Grave g, String key, String player) {
        ServerPlayer p = plugin.player(g.owner);
        long now = System.currentTimeMillis();
        if (p != null) {
            text().send(p, key, Text.unparsed("coords", g.coords()), Text.unparsed("world", prettyWorld(g.world)),
                    Text.unparsed("time", Placeholders.duration(Math.max(0, g.expireAt - now))), Text.unparsed("max", maxPerPlayer),
                    Text.unparsed("player", player == null ? "" : player));
            text().sound(p, "error");
            return;
        }
        if (key.equals("grave.unlocked") || key.equals("grave.unlocked-early")) key = "grave.unlocked-offline";
        notices.computeIfAbsent(g.owner, k -> new ArrayList<>()).add(key + "|" + g.coords() + "|" + prettyWorld(g.world)
                + "|" + (player == null ? "" : player));
        save();
    }

    /** Join digest (order 60): what happened to the player's graves while they were away, then how many they have. */
    private void collectJoin(ServerPlayer p, List<JoinDigest.Line> out) {
        if (plugin.authPending(p)) return;
        UUID id = p.getUUID();
        List<String> list = notices.remove(id);
        if (list != null) {
            for (String n : list) {
                String[] parts = n.split("\\|", -1);
                if (parts.length < 3) continue;
                addLine(out, JoinDigest.render(parts[0], Text.unparsed("coords", parts[1]), Text.unparsed("world", parts[2]),
                        Text.unparsed("player", parts.length > 3 ? parts[3] : ""), Text.unparsed("time", ""), Text.unparsed("max", maxPerPlayer)));
            }
            save();
        }
        List<Grave> mine = of(id);
        if (!mine.isEmpty()) addLine(out, JoinDigest.render("grave.join", Text.unparsed("count", mine.size()),
                Text.component("button", text().button("grave.button-list", "/grave"))));
    }

    private static void addLine(List<JoinDigest.Line> out, Component line) {
        if (line != null) out.add(new JoinDigest.Line(60, line, null));
    }

    // ------------------------------------------------------------------ opening and taking

    private InteractionResult onInteract(ServerPlayer p, ServerLevel level, InteractionHand hand, BlockPos pos) {
        Grave g = at(level, pos);
        if (g == null) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (plugin.authPending(p) || p.isSpectator()) return InteractionResult.FAIL;
        if (!canOpen(p, g)) {
            note(p, claimHolder(p, g) ? "grave.not-yours-claim" : "grave.not-yours", g);
            return InteractionResult.FAIL;
        }
        menu.open(p, g, false);
        return InteractionResult.FAIL;
    }

    /** A short message, at most once a second per player. */
    private void note(ServerPlayer p, String key, Grave g) {
        long now = System.currentTimeMillis();
        Long last = lastNote.get(p.getUUID());
        if (last != null && now - last < 1000) return;
        lastNote.put(p.getUUID(), now);
        text().send(p, key, status(g));
        text().sound(p, "error");
    }

    /** Puts one stack where it belongs in the player's inventory; what doesn't fit stays in {@code e}. */
    private boolean give(ServerPlayer p, Grave.Entry e, boolean toSlot) {
        Inventory inv = p.getInventory();
        if (toSlot && e.slot == BACK_SLOT) {
            // The worn backpack goes back on the back when it is free (else it goes wherever it fits, below).
            Hooks.BackSlot back = plugin.service(Hooks.BackSlot.class);
            if (back != null && back.wear(p, e.item())) {
                e.set(null);
                return true;
            }
            return false;
        }
        if (toSlot && e.slot >= OUTFIT_FIRST && e.slot < OUTFIT_FIRST + OUTFIT_SIZE) {
            // An outfit piece goes back into its slot when it is free (else it goes wherever it fits, below).
            Hooks.Outfit outfit = plugin.service(Hooks.Outfit.class);
            if (outfit != null && outfit.wear(p, e.slot - OUTFIT_FIRST, e.item())) {
                e.set(null);
                return true;
            }
            return false;
        }
        if (toSlot && e.slot >= 0 && e.slot <= 40) {
            if (inv.getItem(e.slot).isEmpty()) {
                inv.setItem(e.slot, e.item());
                e.set(null);
                return true;
            }
        }
        if (toSlot) return false;
        ItemStack copy = e.item().copy();
        inv.add(copy);
        if (copy.isEmpty()) {
            e.set(null);
            return true;
        }
        e.set(copy);
        return false;
    }

    private boolean stillOpen(ServerPlayer p, GraveMenu.View v) {
        Grave g = v.grave;
        if (g.removed || !graves.containsKey(g.id)) {
            p.closeContainer();
            return false;
        }
        if (!v.admin && !canOpen(p, g)) {
            p.closeContainer();
            note(p, "grave.not-yours", g);
            return false;
        }
        // Opened by clicking the head: walking or teleporting away (another world) ends the looting.
        if (!v.admin && (p.level() != g.level() || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(g.blockPos())) > REACH_SQ)) {
            p.closeContainer();
            return false;
        }
        return true;
    }

    /** How far from its grave a looter may stand (squared; a bit more than block reach). */
    private static final double REACH_SQ = 8.0 * 8.0;

    /** Writes the player file now, so it never disagrees with graves.yml after a crash (no dupe, no loss). */
    private static void savePlayer(ServerPlayer p) {
        if (p.hasDisconnected()) return;
        try {
            p.level().getServer().getPlayerList().save(p);
        } catch (RuntimeException e) {
            Feather.get().getLogger().warn("Graves: could not save {}'s player file: {}", Names.name(p), e.toString());
        }
    }

    void takeOne(ServerPlayer p, GraveMenu.View v, Grave.Entry e) {
        if (!stillOpen(p, v)) return;
        Grave g = v.grave;
        if (!g.items.contains(e) || e.item() == null) {
            menu.draw(v);
            return;
        }
        boolean own = g.owner.equals(p.getUUID());
        int before = e.item().getCount();
        if (!(own && give(p, e, true))) give(p, e, false);
        if (e.item() != null && e.item().getCount() == before) {
            text().send(p, "grave.full");
            text().sound(p, "error");
        } else text().sound(p, "click");
        g.items.removeIf(x -> x.item() == null || x.item().isEmpty());
        if (g.stacks() == 0 && g.xp > 0) {
            p.giveExperiencePoints(g.xp);
            text().send(p, "grave.xp", Text.unparsed("xp", g.xp));
            g.xp = 0;
        }
        finishTake(p, g);
    }

    void takeAll(ServerPlayer p, GraveMenu.View v) {
        if (!stillOpen(p, v)) return;
        Grave g = v.grave;
        boolean own = g.owner.equals(p.getUUID());
        if (own) {
            // First everything that can go straight back to its own slot (armor back on, the offhand) ...
            for (Grave.Entry e : g.items) if (e.item() != null) give(p, e, true);
        }
        // ... then the rest wherever it fits.
        for (Grave.Entry e : g.items) if (e.item() != null) give(p, e, false);
        g.items.removeIf(x -> x.item() == null || x.item().isEmpty());
        int xp = g.xp;
        if (xp > 0) {
            p.giveExperiencePoints(xp);
            g.xp = 0;
        }
        int left = g.stacks();
        if (left > 0) {
            text().send(p, "grave.took-some", Text.unparsed("left", left));
            text().sound(p, "error");
        } else {
            text().send(p, own ? "grave.took-all" : "grave.took-all-other", Text.unparsed("owner", g.ownerName));
            text().sound(p, "accepted");
        }
        if (xp > 0) text().send(p, "grave.xp", Text.unparsed("xp", xp));
        finishTake(p, g);
    }

    /** After a take: the grave file is written, then every view redraws. */
    private void finishTake(ServerPlayer p, Grave g) {
        p.inventoryMenu.broadcastChanges();
        if (g.empty()) {
            plugin.getLogger().info("Graves: {} emptied grave {} of {}.", Names.name(p), g.id, g.ownerName);
            forget(g);
            savePlayer(p);
            if (!g.owner.equals(p.getUUID())) tell(g, "grave.looted", Names.name(p));
            return;
        }
        save();
        savePlayer(p);
        menu.redrawAll(g);
    }

    // ------------------------------------------------------------------ protection: the block is inert

    private boolean onBreak(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Grave g = at(level, pos);
        if (g == null) return true;
        // The owner of the claim it stands in may clear it out: the items spill on the ground, the owner is told.
        if (!g.owner.equals(p.getUUID()) && claimHolder(p, g)) {
            text().send(p, "grave.claim-spilled", status(g));
            text().sound(p, "click");
            plugin.getLogger().info("Graves: {} spilled grave {} of {} on their claim.", Names.name(p), g.id, g.ownerName);
            expire(g, "grave.spilled-by-claim", Names.name(p));
            return false;
        }
        note(p, canOpen(p, g) ? "grave.cant-break" : "grave.not-yours", g);
        return false;
    }

    /** The owner (or a manager) of a claim this grave stands in. */
    private boolean claimHolder(ServerPlayer p, Grave g) {
        Loc c = g.center();
        return c != null && plugin.claimed(c) && plugin.claimAllows(p, c, ClaimAction.MANAGE);
    }

    private static void registerEvents() {
        FeatherEvents.PLAYER_DEATH.register((p, source) -> {
            GraveModule m = active;
            if (m != null) m.onDeath(p);
        });
        FeatherEvents.PLAYER_RESPAWN.register((oldP, newP, alive) -> {
            GraveModule m = active;
            if (m != null && !alive) m.onRespawn(newP);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            GraveModule m = active;
            if (m == null) return;
            m.lastNote.remove(h.player.getUUID());
            m.lastGround.remove(h.player.getUUID());
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            GraveModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            return m.onInteract(p, sl, hand, hit.getBlockPos());
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            GraveModule m = active;
            return m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl) || m.onBreak(p, sl, pos);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            GraveModule m = active;
            if (m != null) m.onEntityLoad(entity);
        });
        FeatherEvents.EXPLOSION.register((level, explosion, blocks) -> {
            GraveModule m = active;
            if (m != null && !m.byPos.isEmpty()) blocks.removeIf(b -> m.isGrave(level, b));
        });
        FeatherEvents.PISTON.register((level, piston, push, extending, moved, destroyed) -> {
            GraveModule m = active;
            if (m == null || m.byPos.isEmpty()) return true;
            if (extending && m.isGrave(level, piston.relative(push))) return false;
            for (BlockPos b : moved) if (m.isGrave(level, b) || m.isGrave(level, b.relative(push))) return false;
            for (BlockPos b : destroyed) if (m.isGrave(level, b)) return false;
            return true;
        });
        FeatherEvents.BLOCK_BURN.register((level, from, to) -> {
            GraveModule m = active;
            return m == null || !m.isGrave(level, to);
        });
        FeatherEvents.FIRE_SPREAD.register((level, from, to) -> {
            GraveModule m = active;
            return m == null || !m.isGrave(level, to);
        });
        FeatherEvents.FLUID_INTO.register((level, to, dir) -> {
            GraveModule m = active;
            return m == null || !m.isGrave(level, to);
        });
        FeatherEvents.BLOCK_PLACE.register((player, level, pos, state, stack) -> {
            GraveModule m = active;
            return m == null || !m.isGrave(level, pos);
        });
        FeatherEvents.DISPENSE.register((level, pos, state) -> {
            GraveModule m = active;
            if (m == null || m.byPos.isEmpty() || !state.hasProperty(BlockStateProperties.FACING)) return true;
            return !m.isGrave(level, pos.relative(state.getValue(BlockStateProperties.FACING)));
        });
        FeatherEvents.ENTITY_CHANGE_BLOCK.register((entity, level, pos, state) -> {
            GraveModule m = active;
            return m == null || !m.isGrave(level, pos);
        });
        // Paper graves kept their hologram out of the chunk files; any copy that still turns up is stale.
        Legacy.converter((level, entity, bukkitValues) -> {
            if (!bukkitValues.contains(DISPLAY_KEY)) return false;
            entity.discard();
            return true;
        });
    }

    // ------------------------------------------------------------------ trust

    private void toggleTrust(ServerPlayer owner, UUID other) {
        Set<UUID> s = trust.computeIfAbsent(owner.getUUID(), k -> new LinkedHashSet<>());
        if (!s.remove(other)) s.add(other);
        save();
    }

    /** Settings: "Grave Trust · n" opens the player picker; Back returns to Settings. */
    private void addSettingsButton(Dialogs.Menu m, ServerPlayer player, int width, java.util.function.Consumer<ServerPlayer> back) {
        if (!Perms.has(player, USE)) return;
        int n = trust.getOrDefault(player.getUUID(), Set.of()).size();
        m.button(text().msg("grave.settings", Text.unparsed("count", n)), text().msg("grave.settings-tooltip"), width, p -> {
            text().sound(p, "click");
            openTrust(p, () -> back.accept(p));
        });
    }

    void openTrust(ServerPlayer p, Runnable back) {
        plugin.picker().open(p, new PlayerPicker.Request(text().msg("grave.picker-title"),
                () -> trust.getOrDefault(p.getUUID(), Set.of()), id -> toggleTrust(p, id), Set.of(p.getUUID()), back), 0);
    }

    private UUID resolve(Sender viewer, String name) {
        ServerPlayer online = plugin.findPlayer(viewer, name);
        if (online != null) return online.getUUID();
        OfflinePlayer off = plugin.findOfflinePlayer(viewer, name);
        return off != null ? off.id() : null;
    }

    private String nameOf(UUID id) {
        OfflinePlayer off = plugin.lookup().byId(id);
        return off != null && off.name() != null ? off.name() : id.toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ dialogs

    /** /grave: your graves, newest first, with Track (through death points) and the trust list. */
    void openMine(ServerPlayer p) {
        Dialogs.Menu m = plugin.dialogs().menu(text().msg("grave.menu.title"));
        List<Grave> mine = of(p.getUUID());
        Collections.reverse(mine);
        m.text(text().msg(mine.isEmpty() ? "grave.menu.empty" : "grave.menu.body", Text.unparsed("max", maxPerPlayer)), 300);
        boolean dp = Perms.has(p, "feather.deathpoint");
        int n = 0;
        for (Grave g : mine) {
            n++;
            TagResolver[] r = status(g);
            TagResolver[] all = Arrays.copyOf(r, r.length + 1);
            all[r.length] = Text.unparsed("n", n);
            m.text(text().msg("grave.menu.entry", all), 300);
            int idx = -1;
            if (dp) {
                List<Profiles.DeathPoint> pts = deathPoints(p);
                for (int i = 0; i < pts.size(); i++) if (graveFor(p.getUUID(), pts.get(i)) == g) idx = i + 1;
            }
            if (idx > 0) {
                int k = idx;
                m.button(text().msg("grave.menu.track", Text.unparsed("n", n)), text().msg("grave.menu.track-tooltip"), 300,
                        pl -> CommandBridge.dispatch(Sender.of(pl), "deathpoint track " + k));
            }
        }
        m.button(text().msg("grave.menu.trust", Text.unparsed("count", trust.getOrDefault(p.getUUID(), Set.of()).size())),
                text().msg("grave.menu.trust-tooltip"), 300, pl -> openTrust(pl, () -> openMine(pl)));
        m.columns(1);
        plugin.exitButton(m, p);
        m.open(p);
        text().sound(p, "menu-open");
    }

    /** Staff: every grave (or one player's), with Teleport, Open and Remove. */
    private void openAdminList(ServerPlayer p, UUID filter) {
        List<Grave> list = new ArrayList<>(graves.values());
        if (filter != null) list.removeIf(g -> !g.owner.equals(filter));
        list.sort(Comparator.comparingLong((Grave g) -> g.created).reversed());
        Dialogs.Menu m = plugin.dialogs().menu(text().msg("grave.admin.title"));
        m.text(text().msg(list.isEmpty() ? "grave.admin.empty" : "grave.admin.body", Text.unparsed("count", list.size())), 300);
        int shown = 0;
        for (Grave g : list) {
            if (++shown > 30) break;
            m.text(text().msg("grave.admin.entry", status(g)), 300);
            m.button(text().msg("grave.admin.teleport"), null, 98, pl -> adminTp(pl, g));
            m.button(text().msg("grave.admin.open"), null, 98, pl -> adminOpen(pl, g));
            m.button(text().msg("grave.admin.remove"), text().msg("grave.admin.remove-tooltip"), 98, pl -> adminRemove(Sender.of(pl), g));
        }
        m.columns(3);
        m.exit(text().msg("menu.close"), null);
        m.open(p);
        text().sound(p, "menu-open");
    }

    private void adminTp(ServerPlayer p, Grave g) {
        if (!Perms.has(p, ADMIN) || g.removed) return;
        ServerLevel level = g.level();
        if (level == null) {
            text().send(p, "deathpoint.world-missing", Text.unparsed("world", g.world));
            return;
        }
        Loc to = new Loc(level, g.x + 0.5, g.y + 1.0, g.z + 0.5, p.getYRot(), p.getXRot());
        // Stand beside the head rather than in it.
        BlockPos side = g.blockPos().north();
        if (level.getBlockState(side).getCollisionShape(level, side).isEmpty()
                && level.getBlockState(side.above()).getCollisionShape(level, side.above()).isEmpty()) {
            to = new Loc(level, side.getX() + 0.5, side.getY(), side.getZ() + 0.5, p.getYRot(), p.getXRot());
        }
        plugin.movePlayer(p, to);
        text().send(p, "grave.admin.teleported", status(g));
    }

    private void adminOpen(ServerPlayer p, Grave g) {
        if (!Perms.has(p, ADMIN) || g.removed) return;
        menu.open(p, g, true);
    }

    private void adminRemove(Sender s, Grave g) {
        if (!s.hasPermission(ADMIN) || g.removed) return;
        text().send(s, "grave.admin.removed", status(g));
        expire(g, "grave.removed-by-staff", null);
    }

    // ------------------------------------------------------------------ command

    private Grave byIdOrNull(String s) {
        return graves.get(s.toLowerCase(Locale.ROOT));
    }

    private void command(Sender sender, String[] args) {
        Text t = text();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(ADMIN);
        switch (sub) {
            case "" -> {
                if (!sender.isPlayer()) {
                    t.send(sender, "general.players-only");
                    return;
                }
                ServerPlayer p = sender.player();
                if (!Perms.has(p, USE)) {
                    t.send(p, "general.no-permission");
                    return;
                }
                openMine(p);
            }
            case "trust", "untrust" -> {
                if (!sender.isPlayer()) {
                    t.send(sender, "general.players-only");
                    return;
                }
                ServerPlayer p = sender.player();
                if (!Perms.has(p, USE)) {
                    t.send(p, "general.no-permission");
                    return;
                }
                if (args.length < 2) {
                    t.send(p, "grave.usage");
                    return;
                }
                UUID id = resolve(sender, args[1]);
                if (id == null) {
                    plugin.notFound(p, args[1]);
                    return;
                }
                if (id.equals(p.getUUID())) {
                    t.send(p, "grave.trust-self");
                    return;
                }
                Set<UUID> set = trust.computeIfAbsent(p.getUUID(), k -> new LinkedHashSet<>());
                boolean add = sub.equals("trust");
                boolean changed = add ? set.add(id) : set.remove(id);
                save();
                String who = nameOf(id);
                if (add) t.send(p, changed ? "grave.trusted" : "grave.already-trusted", Text.unparsed("player", who));
                else t.send(p, changed ? "grave.untrusted" : "grave.not-trusted", Text.unparsed("player", who));
                t.sound(p, "click");
            }
            case "trusted" -> {
                if (!sender.isPlayer()) {
                    t.send(sender, "general.players-only");
                    return;
                }
                ServerPlayer p = sender.player();
                Set<UUID> set = trust.getOrDefault(p.getUUID(), Set.of());
                if (set.isEmpty()) t.send(p, "grave.trust-list-empty");
                else t.send(p, "grave.trust-list", Text.unparsed("players", String.join(", ", set.stream().map(this::nameOf).toList())));
            }
            case "list" -> {
                if (!admin) {
                    t.send(sender, "general.no-permission");
                    return;
                }
                UUID filter = null;
                if (args.length > 1) {
                    filter = resolve(sender, args[1]);
                    if (filter == null) {
                        plugin.notFound(sender, args[1]);
                        return;
                    }
                }
                if (sender.isPlayer()) openAdminList(sender.player(), filter);
                else {
                    int n = 0;
                    for (Grave g : graves.values()) {
                        if (filter != null && !g.owner.equals(filter)) continue;
                        n++;
                        t.send(sender, "grave.admin.line", status(g));
                    }
                    if (n == 0) t.send(sender, "grave.admin.empty");
                }
            }
            case "tp", "open", "remove", "age" -> {
                if (!admin) {
                    t.send(sender, "general.no-permission");
                    return;
                }
                if (args.length < 2) {
                    t.send(sender, "grave.usage-admin");
                    return;
                }
                List<Grave> targets = new ArrayList<>();
                Grave one = byIdOrNull(args[1]);
                if (one != null) targets.add(one);
                else if (sub.equals("remove") || sub.equals("age")) {
                    UUID id = resolve(sender, args[1]);
                    if (id != null) targets.addAll(of(id));
                }
                if (targets.isEmpty()) {
                    t.send(sender, "grave.admin.not-found", Text.unparsed("input", args[1]));
                    return;
                }
                switch (sub) {
                    case "tp" -> {
                        if (sender.isPlayer()) adminTp(sender.player(), targets.get(0));
                        else t.send(sender, "general.players-only");
                    }
                    case "open" -> {
                        if (sender.isPlayer()) adminOpen(sender.player(), targets.get(0));
                        else t.send(sender, "general.players-only");
                    }
                    case "remove" -> {
                        for (Grave g : targets) adminRemove(sender, g);
                    }
                    default -> {
                        // Test hook: moves the clock of these graves forward.
                        long minutes;
                        try {
                            minutes = args.length > 2 ? Long.parseLong(args[2]) : -1;
                        } catch (NumberFormatException e) {
                            minutes = -1;
                        }
                        if (minutes < 0 || minutes > 100_000) {
                            t.send(sender, "grave.usage-admin");
                            return;
                        }
                        long ms = minutes * 60_000L;
                        for (Grave g : targets) {
                            g.unlockAt -= ms;
                            g.expireAt -= ms;
                        }
                        save();
                        t.send(sender, "grave.admin.aged", Text.unparsed("count", targets.size()), Text.unparsed("minutes", minutes));
                        tick();
                    }
                }
            }
            default -> t.send(sender, admin ? "grave.usage-admin" : "grave.usage");
        }
    }

    private List<String> complete(Sender sender, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("trust", "untrust", "trusted"));
            if (admin) subs.addAll(List.of("list", "tp", "open", "remove", "age"));
            for (String s : subs) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String pre = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("trust") || sub.equals("list") || sub.equals("remove") || sub.equals("age")) {
                out.addAll(plugin.completePlayers(sender, pre));
            }
            if (sub.equals("untrust") && sender.isPlayer()) {
                for (UUID id : trust.getOrDefault(sender.player().getUUID(), Set.of())) {
                    String n = nameOf(id);
                    if (n.toLowerCase(Locale.ROOT).startsWith(pre)) out.add(n);
                }
            }
            if (admin && (sub.equals("tp") || sub.equals("open") || sub.equals("remove") || sub.equals("age"))) {
                for (String id : graves.keySet()) if (id.startsWith(pre)) out.add(id);
            }
        }
        return out;
    }
}
