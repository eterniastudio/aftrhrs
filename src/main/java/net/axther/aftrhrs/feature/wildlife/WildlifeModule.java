package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Wildlife &amp; Undead update (wildlife SPEC section 5): the untameable Arcana livestock, wildlife and undead
 * (native entities now). Non-lethal harvests on cooldowns kept in each mob's {@link Pdc} ({@link Harvests}), breeding
 * with a 16-per-3x3-chunks cap ({@link WildBreeding}), kept mobs, the furniture stations ({@link Stations}), Squallgulls
 * and fishing hotspots ({@link Squallgulls}, {@link WildlifeHotspots}), map scraps, the Prospector's Pan, Duskflies and
 * the meals ({@link Finds}), the Bestiary ({@link Bestiary}) and the Restless Moon ({@link RestlessMoon}).
 *
 * <p>Loaded wild mobs are kept in a small map (entity uuid -> MythicMobs id), filled by entity load events, so the
 * timers never search the world. Settings and messages: {@code wildlife.yml}. Services: {@link Hooks.WildlifeHotspots},
 * {@link Hooks.Bestiary}.
 */
public final class WildlifeModule implements FeatherModule, FeatherCommand {
    static final String ADMIN = "feather.wildlife.admin";
    static final String NS = "feather:";
    private static volatile WildlifeModule active;

    private final Feather feather;
    Yaml cfg;

    final Harvests harvests = new Harvests(this);
    final WildBreeding breeding = new WildBreeding(this);
    final Stations stations = new Stations(this);
    final Squallgulls gulls = new Squallgulls(this);
    final Finds finds = new Finds(this);
    final Bestiary bestiary;
    final RestlessMoon moon = new RestlessMoon(this);

    /** Loaded wild mobs: entity -> MythicMobs id (species, young, wildlife, undead). Server thread only. */
    final Map<UUID, String> tracked = new HashMap<>();
    private final Map<UUID, Entity> entities = new HashMap<>();
    final Map<String, WildSpecies> species = new LinkedHashMap<>();
    /** Young id -> its species. */
    final Map<String, WildSpecies> youngOf = new HashMap<>();
    /** Undead id -> the vanilla mob whose kill quests it counts for. */
    final Map<String, String> undead = new LinkedHashMap<>();
    String gullId = "ArcanaSquallgull", duskfliesId = "ArcanaDuskflies";
    String keptVar = "kept";
    boolean keptOnLeash = true, keptOnName = true, keptOnBreed = true;
    private Set<String> worlds = new HashSet<>();
    long harvestTickMs = 10_000;

    private record SoundSpec(Identifier id, float volume, float pitch) {
    }

    private final Map<String, SoundSpec> sounds = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Map<UUID, long[]> handled = new HashMap<>();
    private final List<Tasks.Task> tasks = new ArrayList<>();

    public WildlifeModule(Feather feather) {
        this.feather = feather;
        this.bestiary = new Bestiary(this);
    }

    @Override
    public String name() {
        return "Wildlife";
    }

    @Override
    public void enable() {
        bestiary.load();
        reload();
        active = this;
        feather.bind("wildlife", this);
        feather.bind("bestiary", (sender, label, args) -> {
            ServerPlayer p = sender.player();
            if (p == null) {
                sender.sendMessage("Players only.");
                return true;
            }
            openBestiary(p);
            return true;
        });
        feather.provide(Hooks.WildlifeHotspots.class, WildlifeHotspots.INSTANCE);
        feather.provide(Hooks.Bestiary.class, new Hooks.Bestiary() {
            @Override
            public void openBestiary(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
                bestiary.open(player, back);
            }

            @Override
            public Hooks.Bestiary.BestiaryMob bestiaryMob(java.util.UUID player, String mob) {
                return bestiary.line(player, mob);
            }

            @Override
            public java.util.List<Hooks.Bestiary.BestiaryPage> bestiaryPages(java.util.UUID player) {
                return bestiary.pages(player);
            }
        });
        net.axther.aftrhrs.net.Inspect.register("wildlife", stations::inspect);
        feather.once("wildlife.events", WildlifeModule::registerEvents);
        // already loaded entities (a /feather reload or a late enable)
        Tasks.later(40, () -> {
            if (active != this) return;
            for (ServerLevel l : Feather.server().getAllLevels()) for (Entity e : l.getAllEntities()) consider(e);
        });
        feather.getLogger().info("Wildlife: {} species, {} undead.", species.size(), undead.size());
    }

    @Override
    public void reload() {
        cfg = feather.loadResourceConfig("wildlife.yml");
        worlds = new HashSet<>();
        for (String w : cfg.getStringList("worlds")) if (w != null && !w.isBlank()) worlds.add(w.trim());
        harvestTickMs = (long) (Math.max(2, cfg.getDouble("tick.harvest-seconds", 10)) * 1000);
        species.clear();
        youngOf.clear();
        Yaml sec = section("species");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                Yaml c = sec.getConfigurationSection(id);
                if (c == null) continue;
                WildSpecies s = new WildSpecies(id, c);
                species.put(id, s);
                if (s.young != null) youngOf.put(s.young, s);
            }
        }
        undead.clear();
        Yaml u = section("undead");
        if (u != null) {
            for (String id : u.getKeys(false)) {
                String base = u.getString(id, "");
                undead.put(id, base == null ? "" : base.trim().toLowerCase(Locale.ROOT));
            }
        }
        gullId = cfg.getString("squallgull.id", "ArcanaSquallgull");
        duskfliesId = cfg.getString("duskflies.id", "ArcanaDuskflies");
        keptVar = cfg.getString("kept.variable", "kept");
        keptOnLeash = cfg.getBoolean("kept.on-leash", true);
        keptOnName = cfg.getBoolean("kept.on-name", true);
        keptOnBreed = cfg.getBoolean("kept.on-breed", true);
        sounds.clear();
        Set<String> soundKeys = new java.util.LinkedHashSet<>();
        Yaml snd = cfg.getConfigurationSection("sounds");
        if (snd != null) soundKeys.addAll(snd.getKeys(false));
        Yaml defSnd = cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection("sounds");
        if (defSnd != null) soundKeys.addAll(defSnd.getKeys(false));
        for (String k : soundKeys) {
            SoundSpec s = parseSound(cfg.getString("sounds." + k, ""));
            if (s != null) sounds.put(k, s);
        }
        breeding.reload(cfg);
        stations.reload(cfg);
        gulls.reload(cfg);
        finds.reload(cfg);
        bestiary.reload(cfg);
        moon.reload(cfg);
        restartTasks();
    }

    private void restartTasks() {
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        long harvest = Math.max(40L, harvestTickMs / 50);
        tasks.add(Tasks.timer(harvest, harvest, () -> harvests.tick(System.currentTimeMillis())));
        long sight = Math.max(10L, Math.round(cfg.getDouble("tick.sight-seconds", 2) * 20));
        tasks.add(Tasks.timer(sight + 3, sight, bestiary::scan));
        long gull = Math.max(10L, Math.round(cfg.getDouble("tick.gull-seconds", 2) * 20));
        tasks.add(Tasks.timer(gull + 7, gull, gulls::scan));
        long follow = Math.max(2L, cfg.getLong("tick.follow-ticks", 10));
        tasks.add(Tasks.timer(follow, follow, gulls::steer));
        tasks.add(Tasks.timer(40L, 100L, moon::tick));
        tasks.add(Tasks.timer(650L, 600L, () -> bestiary.save(false)));   // codex saves at 620, pets at 610
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        moon.clear();
        stations.clear();
        gulls.clear();
        bestiary.shutdown();
        tracked.clear();
        entities.clear();
        WildlifeHotspots.clear();
        feather.withdraw(Hooks.WildlifeHotspots.class);
        feather.withdraw(Hooks.Bestiary.class);
        net.axther.aftrhrs.net.Inspect.unregister("wildlife");
    }

    // ------------------------------------------------------------------ events (registered once per JVM)

    private static void registerEvents() {
        ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
            WildlifeModule m = active;
            if (m == null) return;
            if (m.stations.isStaleLabel(e)) {
                e.discard();
                return;
            }
            m.consider(e);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((e, level) -> {
            WildlifeModule m = active;
            if (m != null) m.removed(e);
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            WildlifeModule m = active;
            if (m != null && !generated) m.stations.chunkLoaded(level, chunk);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            WildlifeModule m = active;
            if (m == null || level.isClientSide() || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            return m.onUseEntity(p, hand, entity);
        });
        net.axther.aftrhrs.entity.CreatureEvents.HARVESTED.register((mob, p, what) -> {
            WildlifeModule m = active;
            if (m == null || !m.enabledWorld(mob.level())) return;
            String type = mythicType(mob);
            if (type != null && m.isWild(type)) m.bestiary.harvested(p, m.family(type));
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            WildlifeModule m = active;
            if (m == null || level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            if (!m.enabledWorld(p.level())) return InteractionResult.PASS;
            return m.finds.onUse(p, null) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            WildlifeModule m = active;
            if (m == null || level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            if (!m.enabledWorld(p.level())) return InteractionResult.PASS;
            return m.finds.onUse(p, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            WildlifeModule m = active;
            if (m == null || taken <= 0) return;
            String type = m.tracked.get(entity.getUUID());
            if (type != null && type.equals(m.gullId) && FeatherEvents.attacker(source) != null) m.gulls.hit(entity);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            WildlifeModule m = active;
            if (m != null) m.onDeath(entity, source);
        });
        // After the add succeeded: a full inventory touching the drop every tick must not count it again and again.
        FeatherEvents.ITEM_PICKED_UP.register((p, item, taken) -> {
            WildlifeModule m = active;
            if (m == null) return;
            String from = Pdc.getString(item, NS + "wild_from");
            if (from != null) m.bestiary.harvested(p, from);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            WildlifeModule m = active;
            if (m == null) return;
            m.handled.remove(h.player.getUUID());
            m.finds.forget(h.player.getUUID());
            m.moon.forget(h.player);
        });
        FeatherEvents.ITEM_CONSUMED.register((p, stack, hand) -> {
            WildlifeModule m = active;
            if (m != null) m.finds.consumed(p, stack);
        });
        ArcanaContent.hook(id -> {
            WildlifeModule m = active;
            return m != null && m.stations.isStation(id);
        }, new Stations.Hook());
        Legacy.onFurniture((level, origin, oraxenId, bukkitValues, yaw) -> {
            WildlifeModule m = active;
            if (m != null) m.stations.restoreLegacy(level, origin, oraxenId, bukkitValues);
        });
        // Old Paper leftovers: the floating station counters (non-saved on Paper, but a crash could leave one).
        Legacy.converter((level, entity, bukkit) -> {
            if (bukkit.contains(NS + Stations.LABEL)) {
                entity.discard();
                return true;
            }
            return false;
        });
    }

    static WildlifeModule active() {
        return active;
    }

    // ------------------------------------------------------------------ shared helpers

    public Feather feather() {
        return feather;
    }

    Yaml section(String path) {
        Yaml s = cfg.getConfigurationSection(path);
        if (s != null) return s;
        return cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection(path);
    }

    long getLong(Entity e, String key) {
        return Pdc.getLong(e, NS + key, 0L);
    }

    void setLong(Entity e, String key, long value) {
        if (value == 0) Pdc.remove(e, NS + key);
        else Pdc.putLong(e, NS + key, value);
    }

    int getInt(Entity e, String key) {
        return Pdc.getInt(e, NS + key, 0);
    }

    void setInt(Entity e, String key, int value) {
        if (value == 0) Pdc.remove(e, NS + key);
        else Pdc.putInt(e, NS + key, value);
    }

    String getString(Entity e, String key) {
        return Pdc.getString(e, NS + key);
    }

    void setString(Entity e, String key, String value) {
        if (value == null) Pdc.remove(e, NS + key);
        else Pdc.putString(e, NS + key, value);
    }

    void warnOnce(String key, String message) {
        if (warned.add(key)) feather.getLogger().warn(message);
    }

    String raw(String key) {
        String path = "messages." + key;
        if (cfg.isList(path)) return String.join("\n", cfg.getStringList(path));
        String v = cfg.getString(path);
        if (v == null) {
            warnOnce("msg:" + key, "wildlife.yml is missing 'messages." + key + "'.");
            return "";
        }
        return v;
    }

    Component msg(String key, TagResolver... r) {
        return feather.text().parse(raw(key), r);
    }

    void send(Sender to, String key, TagResolver... r) {
        String raw = raw(key);
        if (!raw.isEmpty()) to.sendMessage(feather.text().parse(raw, r));
    }

    void send(ServerPlayer to, String key, TagResolver... r) {
        String raw = raw(key);
        if (!raw.isEmpty()) Adv.send(to, feather.text().parse(raw, r));
    }

    void bar(ServerPlayer to, String key, TagResolver... r) {
        String raw = raw(key);
        if (!raw.isEmpty() && to != null) Adv.actionBar(to, feather.text().parse(raw, r));
    }

    void sound(ServerPlayer p, String key) {
        SoundSpec s = sounds.get(key);
        if (s != null && p != null) Adv.sound(p, s.id().toString(), s.volume(), s.pitch());
    }

    /** Plays a configured sound at a spot for everyone near. */
    void soundAt(ServerLevel level, Vec3 at, String key) {
        SoundSpec s = sounds.get(key);
        if (s == null || level == null) return;
        SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getOptional(s.id()).orElseGet(() -> SoundEvent.createVariableRangeEvent(s.id()));
        level.playSound(null, at.x, at.y, at.z, ev, SoundSource.NEUTRAL, s.volume(), s.pitch());
    }

    private SoundSpec parseSound(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) return null;
        String[] parts = spec.trim().split("\\s+");
        try {
            Identifier id = Identifier.parse(parts[0].toLowerCase(Locale.ROOT));
            return new SoundSpec(id, parts.length > 1 ? Float.parseFloat(parts[1]) : 1f, parts.length > 2 ? Float.parseFloat(parts[2]) : 1f);
        } catch (RuntimeException e) {
            feather.getLogger().warn("wildlife.yml: bad sound '{}' (use: <sound key> [volume] [pitch]).", spec);
            return null;
        }
    }

    boolean enabledWorld(net.minecraft.world.level.Level w) {
        return w != null && (worlds.isEmpty() || worlds.contains(Worlds.name(w)) || worlds.contains(Worlds.key(w)));
    }

    static String time(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        long h = s / 3600, mins = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return mins > 0 ? h + "h " + mins + "m" : h + "h";
        if (mins > 0) return sec > 0 && mins < 5 ? mins + "m " + sec + "s" : mins + "m";
        return s + "s";
    }

    /** The species' display name, or the id without "Arcana". */
    String displayName(String type) {
        if (type == null) return "?";
        WildSpecies s = species.get(type);
        if (s == null) s = youngOf.get(type);
        if (s != null) return s.name;
        String n = cfg.getString("names." + type);
        if (n != null) return n;
        return type.startsWith("Arcana") ? type.substring("Arcana".length()) : type;
    }

    /** Adult species / wildlife / undead id for any tracked type (young map to their species). */
    String family(String type) {
        WildSpecies s = youngOf.get(type);
        return s != null ? s.id : type;
    }

    /** Whether this MythicMobs id is one of ours. */
    boolean isWild(String type) {
        return type != null && (species.containsKey(type) || youngOf.containsKey(type) || undead.containsKey(type)
                || type.equals(gullId) || type.equals(duskfliesId) || bestiary.knows(type));
    }

    // ------------------------------------------------------------------ items

    /**
     * A product stack: a content (ex-Oraxen) id, else a vanilla item. Null (logged once) when neither exists.
     */
    ItemStack item(String spec, int amount) {
        if (spec == null || spec.isBlank() || amount <= 0) return null;
        String id = spec.trim().toLowerCase(Locale.ROOT);
        ItemStack s = id.contains(":") && !id.startsWith("aftrhrs:") && !id.startsWith("oraxen:") ? ItemStack.EMPTY : ArcanaContent.stack(id, 1);
        if (s.isEmpty()) {
            Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
            if (item != null && item != Items.AIR) s = new ItemStack(item);
        }
        if (s.isEmpty()) {
            warnOnce("item:" + id, "wildlife.yml names item '" + id + "', which is neither a mod item nor a vanilla item; nothing given.");
            return null;
        }
        s.setCount(Math.min(amount, s.getMaxStackSize()));
        return s;
    }

    /** Rolls min..max of a drop. */
    ItemStack roll(WildSpecies.Drop d) {
        if (d == null) return null;
        int n = d.min() >= d.max() ? d.min() : ThreadLocalRandom.current().nextInt(d.min(), d.max() + 1);
        return item(d.item(), n);
    }

    static void give(ServerPlayer p, ItemStack item) {
        if (item == null || item.isEmpty()) return;
        ItemStack rest = item.copy();
        if (!p.getInventory().add(rest) || !rest.isEmpty()) drop(p.level(), p.position(), rest);
    }

    static ItemEntity drop(ServerLevel level, Vec3 at, ItemStack item) {
        if (item == null || item.isEmpty() || level == null) return null;
        ItemEntity e = new ItemEntity(level, at.x, at.y, at.z, item);
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
        return e;
    }

    static boolean creative(ServerPlayer p) {
        return p.isCreative();
    }

    /** Takes one of the held item and gives {@code back} (may be null) in its place. Creative keeps everything. */
    static void useOne(ServerPlayer p, InteractionHand hand, ItemStack back) {
        if (creative(p)) {
            give(p, back);
            return;
        }
        ItemStack held = p.getItemInHand(hand);
        if (held.getCount() <= 1) {
            p.setItemInHand(hand, back == null ? ItemStack.EMPTY : back);
        } else {
            held.shrink(1);
            give(p, back);
        }
    }

    /** Wears the held tool by one (unbreaking applies); nothing in creative. */
    static void wear(ServerPlayer p, InteractionHand hand) {
        if (creative(p)) return;
        ItemStack held = p.getItemInHand(hand);
        if (held.isDamageableItem()) held.hurtAndBreak(1, p, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
    }

    /** Game mode, then land claims (animals and furniture need BUILD trust in someone else's claim). */
    boolean mayUse(ServerPlayer p, ServerLevel level, BlockPos at, ClaimAction action) {
        GameType gm = p.gameMode();
        if (gm == GameType.SPECTATOR) return false;
        if (gm == GameType.ADVENTURE && action != ClaimAction.ACCESS && action != ClaimAction.CONTAINER) return false;
        if (feather.claimAllows(p, Loc.of(level, at), action)) return true;
        bar(p, "claimed");
        sound(p, "error");
        return false;
    }

    // ------------------------------------------------------------------ kept

    /** A player leashed, named or bred it: it no longer despawns, and the pack's caster.kept is 1. */
    void keep(Entity e) {
        if (e == null || e.isRemoved()) return;
        Pdc.putTag(e, NS + "wild_kept", ByteTag.valueOf((byte) 1));
        if (e instanceof ArcanaWildlife w) {
            w.setKept(true);
            w.setVar(keptVar, 1);
        } else if (e instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
    }

    boolean kept(Entity e) {
        return Pdc.has(e, NS + "wild_kept");
    }

    /** Leashed or name-tagged now: kept for good (the old leash / name events). */
    void checkKept(Entity e) {
        if (kept(e) || !(e instanceof Mob mob)) return;
        if ((keptOnLeash && mob.isLeashed()) || (keptOnName && mob.hasCustomName())) keep(e);
    }

    // ------------------------------------------------------------------ tracking

    Entity entity(UUID id) {
        Entity e = entities.get(id);
        return e == null || e.isRemoved() ? null : e;
    }

    /** Live tracked entities (copy). */
    List<Entity> trackedEntities() {
        List<Entity> out = new ArrayList<>();
        for (Entity e : entities.values()) if (!e.isRemoved()) out.add(e);
        return out;
    }

    static String mythicType(Entity e) {
        return e instanceof ArcanaMob am ? am.species().mythicId : null;
    }

    /** The tracked type, or (for an entity not seen yet) its species id, tracked from now on when it is ours. */
    String typeOf(Entity e) {
        String t = tracked.get(e.getUUID());
        if (t != null) return t;
        t = mythicType(e);
        if (isWild(t)) {
            track(e, t);
            return t;
        }
        return null;
    }

    private void track(Entity e, String type) {
        entities.put(e.getUUID(), e);
        if (tracked.put(e.getUUID(), type) != null) return;
        if (kept(e) && e instanceof ArcanaWildlife w && !w.kept()) w.setKept(true);
        boolean livestock = species.containsKey(type) || youngOf.containsKey(type);
        if (!livestock && !type.equals(gullId)) return;
        Tasks.later(20, () -> {
            if (e.isRemoved()) return;
            // a young that grew up: the adult carries the kept flag over; mirror it in Feather's mark
            if (livestock && !kept(e) && e instanceof ArcanaWildlife w && (w.var(keptVar) == 1 || w.kept())) {
                if (w.var(keptVar) == 1 || keptOnBreed) keep(e);
            }
            harvests.restoreLook(e, type);
        });
        gulls.tracked(e, type);
    }

    void consider(Entity e) {
        String t = mythicType(e);
        if (isWild(t)) track(e, t);
    }

    private void removed(Entity e) {
        UUID id = e.getUUID();
        entities.remove(id);
        if (tracked.remove(id) != null) {
            breeding.forget(id);
            gulls.forget(id);
            harvests.forget(id);
        }
        stations.labelRemoved(e);
    }

    // ------------------------------------------------------------------ clicks on mobs

    /** One click can arrive twice (interact-at, interact) and once per hand: handle it once. */
    private boolean handledJustNow(ServerPlayer p, Entity e) {
        long[] h = handled.get(p.getUUID());
        long now = Feather.server().getTickCount();
        return h != null && h[0] == e.getUUID().getMostSignificantBits() && now - h[1] >= 0 && now - h[1] <= 2;
    }

    private void markHandled(ServerPlayer p, Entity e) {
        handled.put(p.getUUID(), new long[]{e.getUUID().getMostSignificantBits(), Feather.server().getTickCount()});
        if (handled.size() > 1024) handled.clear();
    }

    private InteractionResult onUseEntity(ServerPlayer p, InteractionHand hand, Entity clicked) {
        String type = typeOf(clicked);
        if (type == null) return InteractionResult.PASS;
        if (handledJustNow(p, clicked)) return InteractionResult.SUCCESS;
        if (handle(p, clicked, type, hand)) {
            markHandled(p, clicked);
            return InteractionResult.SUCCESS;
        }
        // a lead or a name tag lands this tick: kept for good from then on
        Tasks.later(1, () -> checkKept(clicked));
        return InteractionResult.PASS;
    }

    private boolean handle(ServerPlayer p, Entity e, String type, InteractionHand hand) {
        if (!enabledWorld(e.level()) || p.isSpectator()) return false;
        ItemStack held = p.getItemInHand(hand);
        String oid = ArcanaContent.id(held);
        if (type.equals(duskfliesId)) return held.is(Items.GLASS_BOTTLE) && oid == null && finds.bottle(p, e, hand);
        if (type.equals(gullId)) return gulls.interact(p, e, held, hand);
        WildSpecies young = youngOf.get(type);
        if (young != null) return harvests.blocksYoung(young, held, oid);
        WildSpecies s = species.get(type);
        if (s == null) return false;
        if (harvests.interact(p, e, s, held, oid, hand)) return true;
        // a bucket on livestock without a milk harvest does nothing (and keeps the bucket)
        return held.is(Items.BUCKET) && oid == null;
    }

    // ------------------------------------------------------------------ deaths

    private void onDeath(LivingEntity dead, net.minecraft.world.damagesource.DamageSource source) {
        String type = tracked.get(dead.getUUID());
        if (type == null) {
            type = mythicType(dead);
            if (!isWild(type)) return;
        }
        if (type.equals(gullId)) gulls.died(dead);
        ServerPlayer killer = FeatherEvents.attacker(source);
        if (killer == null) return;
        bestiary.slain(killer, family(type));
        String base = undead.get(type);
        if (base != null) {
            Hooks.Quests q = feather.service(Hooks.Quests.class);
            if (q != null) {
                q.progress(killer, "undead_cull", type, 1);
                // Quest kill targets name the vanilla mob: on Paper the undead were zombies / skeletons and counted as
                // such by the quests module; as native entities they count for their base here.
                if (!base.isEmpty()) q.progress(killer, "kill", base, 1);
            }
            feather.stat(killer.getUUID(), "undead-culled", 1);
        }
    }

    /** Marks a dropped product so whoever picks it up gets the harvest in their bestiary. */
    void markFrom(ItemEntity item, String type) {
        if (item != null) Pdc.putString(item, NS + "wild_from", family(type));
    }

    // ------------------------------------------------------------------ commands

    /**
     * /bestiary: the bestiary is part of the creature codex, so this opens the codex on its Wildlife tab (the bestiary's
     * page list is a button there). Without the codex (pets module off) the bestiary's own dialogs open.
     */
    void openBestiary(ServerPlayer p) {
        Hooks.Codex codex = feather.service(Hooks.Codex.class);
        if (codex != null) codex.openCodex(p, "wildlife", null, null);
        else bestiary.open(p, null);
    }

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.isEmpty() || sub.equals("bestiary")) {
            ServerPlayer p = sender.player();
            if (p != null) openBestiary(p);
            else send(sender, "admin.help");
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            send(sender, "admin.no-permission");
            return true;
        }
        WildAdmin.run(this, sender, sub, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) return List.of();
        return WildAdmin.complete(this, sender, args);
    }

    // ------------------------------------------------------------------ for other modules

    /** True while the Restless Moon night runs in the configured overworld. */
    public boolean restlessMoon() {
        return moon.active();
    }

    /** {@link #restlessMoon()} of the running module; false while wildlife is off (Witching Hour reads it). */
    public static boolean restlessMoonNow() {
        WildlifeModule m = active;
        return m != null && m.restlessMoon();
    }

    static TagResolver tag(String k, Object v) {
        return Text.unparsed(k, v);
    }
}
