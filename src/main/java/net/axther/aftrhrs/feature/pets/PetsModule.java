package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.CreatureEvents;
import net.axther.aftrhrs.entity.CreatureSfx;
import net.axther.aftrhrs.mixin.MobPersistenceAccessor;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Pets (the native Arcana creatures): a store of every player's pets with a cap, bond levels 0-5 (set directly on the
 * creature with {@link ArcanaCreature#setBond}), revive with the creature's plushie, a {@code /pets} menu, the stable,
 * the codex, perks, variants, breeding, Work Here, and pets that follow their owner across worlds and far teleports.
 * The creature talks to this module through {@link CreatureEvents} (cap, tame, stay, feed, interact, death).
 * Settings and messages: {@code aftrhrs/pets.yml} (Feather's pets.yml).
 */
public final class PetsModule implements FeatherModule, FeatherCommand {
    /** The running module, for event listeners (Fabric listeners can't be unregistered). */
    private static volatile PetsModule active;

    enum Outcome { NEAR, MOVED, RESPAWNED, RIDDEN, FAILED, BUSY }

    /** Pdc key of the pet record id on a pet's entity (the Paper server's feather:pet). */
    static final String PET_KEY = "feather:pet";

    private final Feather feather;
    private final Text text;
    private final Map<String, PetSpecies> species = new LinkedHashMap<>();
    private final Map<String, PetSpecies> byPlush = new HashMap<>();
    private final Map<String, Sound> sounds = new HashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Tasks.Task> pendingFollow = new HashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final List<Tasks.Task> tasks = new ArrayList<>();
    /** Migrated creatures that had no record: what the Paper server knew about them (name, bond, color). */
    private final Map<UUID, PetMigration.Info> legacyInfo = new ConcurrentHashMap<>();

    private Yaml cfg;
    private PetStore store;
    private PetMenu menu;
    private Perks perks;
    private Stable stable;
    private Codex codex;
    private Variants variants;
    private Breeding breeding;
    private PetWork work;
    private PetGuard guard;
    private PetWhistle whistle;
    private PetsCompanion bridge;
    private long ticks;

    private int defaultCap;
    private boolean countMounts;
    private boolean adopt;
    private int followDelay;
    private double farDistance;
    private boolean crossWorldTeleport;
    private int loadTimeout;
    private int[] levels = {0, 100, 300, 600, 1000, 1500};
    private int xpNear, xpFeed, xpKill;
    private double nearRadius, killRadius;
    private long feedCooldownMs, killCooldownMs, capNoticeMs;
    private boolean killHostileOnly;
    private boolean reviveAir, reviveSneakBlock;
    private boolean levelTitle, levelParticles;
    private Pattern namePattern;
    private int nameMax;

    public PetsModule(Feather feather) {
        this.feather = feather;
        this.text = feather.text();
    }

    @Override
    public String name() {
        return "Pets";
    }

    @Override
    public void enable() {
        store = new PetStore(feather);
        store.load();
        perks = new Perks(this);
        stable = new Stable(this);
        codex = new Codex(this);
        codex.load();
        variants = new Variants(this);
        breeding = new Breeding(this);
        work = new PetWork(this);
        guard = new PetGuard(this);
        whistle = new PetWhistle(this);
        reload();
        menu = new PetMenu(this);
        bridge = new PetsCompanion(this);
        bridge.start();
        feather.bind("pets", this);
        registerEvents(feather);
        PetMigration.register(feather);
        tasks.add(Tasks.timer(100, 100, this::tick));
        tasks.add(Tasks.timer(610, 600, store::saveAsync));   // off the vanilla autosave tick (every 6000)
        tasks.add(Tasks.timer(20, 20, perks::tick));
        tasks.add(Tasks.timer(23, 20, variants::tick));
        tasks.add(Tasks.timer(27, 20, work::tick));
        tasks.add(Tasks.timer(1, 1, work::drain));
        long scan = Math.max(10L, Math.round(cfg.getDouble("codex.scan-seconds", 2) * 20));
        tasks.add(Tasks.timer(scan, scan, codex::scan));
        tasks.add(Tasks.timer(620, 600, () -> codex.save(false)));
        for (ServerPlayer online : feather.onlinePlayers()) codex.backfill(online);
        tasks.add(Tasks.later(40, this::scanLoaded));

        Hub hub = feather.hub();
        hub.add("pets", Hub.Group.MAIN, Hub.ORDER_PETS, "hub-pets", "feather.pets", this::openMenu);
        hub.petCount(p -> {
            int cap = cap(p);
            return countActive(p.getUUID()) + (cap == Integer.MAX_VALUE ? "" : "/" + cap);
        });
        hub.portrait(p -> feather.getConfig().getBoolean("hub.pet-portrait", true) ? portrait(p.getUUID()) : null);
        feather.provide(Hooks.Pets.class, new Hooks.Pets() {
            @Override
            public int cap(ServerPlayer player) {
                return PetsModule.this.cap(player);
            }

            @Override
            public int whistle(ServerPlayer player) {
                return whistle.blow(player);
            }
        });
        feather.provide(Hooks.PetProgress.class, new Hooks.PetProgress() {
            @Override
            public int codexUnseen(UUID player) {
                return codex == null || !codex.enabled() ? 0 : codex.unseen(player);
            }

            @Override
            public int addBondToFollowers(ServerPlayer player, int xp) {
                return PetsModule.this.addBondToFollowers(player, xp);
            }

            @Override
            public int countActive(UUID player) {
                return PetsModule.this.countActive(player);
            }

            @Override
            public int topBondLevel(UUID player) {
                return PetsModule.this.topBondLevel(player);
            }
        });
        // the creature codex: its front doors (/codex, the hub, the guide item, /bestiary) are other modules'
        feather.provide(Hooks.Codex.class, new Hooks.Codex() {
            @Override
            public void openCodex(ServerPlayer player, String category, String species, java.util.function.Consumer<ServerPlayer> back) {
                if (codex.enabled()) codex.open(player, category, species, back);
                else send(player, "codex.disabled");
            }

            @Override
            public void reloadCodex() {
                codex.reload(cfg, species, true);   // /codex reload: always rebuilt
                if (bridge != null) bridge.scan();
            }

            @Override
            public List<String> completions() {
                return codex.completions();
            }
        });
        feather.provide(Hooks.PetWork.class, new Hooks.PetWork() {
            @Override
            public boolean working(Entity creature) {
                Pet p = creature == null ? null : store.byEntity(creature.getUUID());
                return p != null && p.working();
            }

            @Override
            public List<Entity> workingEntities(String speciesPath) {
                List<Entity> out = new ArrayList<>();
                for (Pet p : store.loaded()) {
                    if (!p.working() || !matchesPath(p, speciesPath)) continue;
                    Entity e = entity(p);
                    if (e != null) out.add(e);
                }
                return out;
            }

            @Override
            public List<Loc> workingAt(String speciesPath) {
                List<Loc> out = new ArrayList<>();
                for (Pet p : store.all()) {
                    if (!p.working() || !matchesPath(p, speciesPath)) continue;
                    Loc l = p.work.location();
                    if (l != null) out.add(l);
                }
                return out;
            }
        });
        active = this;
        feather.getLogger().info("Pets: {} pet(s) on record, {} species.", store.all().size(), species.size());
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (bridge != null) bridge.stop();
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        for (Tasks.Task t : pendingFollow.values()) t.cancel();
        pendingFollow.clear();
        if (work != null) work.clear();
        feather.withdraw(Hooks.Pets.class);
        feather.withdraw(Hooks.PetProgress.class);
        feather.withdraw(Hooks.PetWork.class);
        feather.withdraw(Hooks.Codex.class);
        if (store != null) {
            // last positions of loaded pets, so a restart (or a later recall) knows where they are
            for (Pet p : store.loaded()) {
                Entity e = entity(p);
                if (e != null) {
                    syncRecord(p, e);
                    store.markDirty();
                }
            }
            store.shutdown();
        }
        if (codex != null) codex.shutdown();
    }

    @Override
    public void reload() {
        cfg = feather.loadResourceConfig("pets.yml");
        defaultCap = Math.max(0, cfg.getInt("cap.default", 6));
        countMounts = cfg.getBoolean("cap.count-mounts", true);
        capNoticeMs = (long) (cfg.getDouble("cap.notice-cooldown-seconds", 10) * 1000);
        adopt = cfg.getBoolean("adopt-existing", true);
        followDelay = Math.max(1, cfg.getInt("follow.delay-ticks", 20));
        farDistance = Math.max(8, cfg.getDouble("follow.far-distance", 48));
        crossWorldTeleport = "teleport".equalsIgnoreCase(cfg.getString("follow.cross-world", "respawn"));
        loadTimeout = Math.max(20, cfg.getInt("follow.load-timeout-ticks", 100));
        List<Integer> lv = cfg.getIntegerList("bond.levels");
        if (lv.size() >= 2) {
            levels = lv.stream().mapToInt(Integer::intValue).sorted().toArray();
            levels[0] = 0;
        }
        xpNear = cfg.getInt("bond.xp.near-owner-per-minute", 3);
        nearRadius = cfg.getDouble("bond.xp.near-radius", 16);
        xpFeed = cfg.getInt("bond.xp.feed", 10);
        feedCooldownMs = (long) (cfg.getDouble("bond.xp.feed-cooldown-seconds", 60) * 1000);
        xpKill = cfg.getInt("bond.xp.owner-kill", 2);
        killRadius = cfg.getDouble("bond.xp.owner-kill-radius", 16);
        killCooldownMs = (long) (cfg.getDouble("bond.xp.owner-kill-cooldown-seconds", 3) * 1000);
        killHostileOnly = cfg.getBoolean("bond.xp.owner-kill-hostile-only", true);
        levelTitle = cfg.getBoolean("bond.level-up.title", true);
        levelParticles = cfg.getBoolean("bond.level-up.particles", true);
        reviveAir = cfg.getBoolean("revive.right-click-air", true);
        reviveSneakBlock = cfg.getBoolean("revive.sneak-right-click-block", true);
        nameMax = Math.max(1, cfg.getInt("rename.max-length", 24));
        try {
            namePattern = Pattern.compile(cfg.getString("rename.pattern", "^[\\p{L}\\p{N} _'.-]+$"));
        } catch (RuntimeException e) {
            feather.getLogger().warn("pets.yml rename.pattern is not a valid regular expression: {}", e.getMessage());
            namePattern = Pattern.compile("^[\\p{L}\\p{N} _'.-]+$");
        }

        species.clear();
        byPlush.clear();
        Yaml sec = cfg.getConfigurationSection("species");
        Yaml defSpecies = cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection("species");
        if (sec != null || defSpecies != null) {
            // species shipped later (Feather 1.4 creatures) count even when the server's pets.yml predates them
            Set<String> ids = new LinkedHashSet<>();
            if (sec != null) ids.addAll(sec.getKeys(false));
            if (defSpecies != null) ids.addAll(defSpecies.getKeys(false));
            for (String id : ids) {
                Yaml c = sec != null && sec.isConfigurationSection(id) ? sec.getConfigurationSection(id)
                        : defSpecies == null ? null : defSpecies.getConfigurationSection(id);
                if (c == null) continue;
                PetSpecies.Kind kind;
                try {
                    kind = PetSpecies.Kind.valueOf(c.getString("kind", "pet").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    kind = PetSpecies.Kind.PET;
                }
                String plush = c.getString("plush");
                PetSpecies s = new PetSpecies(id, c.getString("name", id), kind, plush == null ? null : plush.toLowerCase(Locale.ROOT),
                        PetUtil.item(c.getString("heal", "")));
                species.put(id, s);
                if (s.plush() != null) byPlush.put(s.plush(), s);
                if (ArcanaEntities.byMythicId(id) == null) feather.getLogger().warn("pets.yml species {} has no native creature.", id);
            }
        }
        sounds.clear();
        Set<String> soundKeys = new LinkedHashSet<>();
        Yaml snd = cfg.getConfigurationSection("sounds");
        if (snd != null) soundKeys.addAll(snd.getKeys(false));
        // sounds added in later versions play even when the server's pets.yml predates them
        Yaml defSnd = cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection("sounds");
        if (defSnd != null) soundKeys.addAll(defSnd.getKeys(false));
        {
            for (String k : soundKeys) {
                Sound s = Text.parseSound(cfg.getString("sounds." + k, ""));
                if (s != null) sounds.put(k, s);
            }
        }
        perks.reload(cfg);
        stable.reload(cfg);
        codex.reload(cfg, species);
        variants.reload(cfg);
        breeding.reload(cfg, species);
        work.reload(cfg);
        whistle.reload();
        if (store != null) reapplyAll();
    }

    // ------------------------------------------------------------------ events (registered once per JVM)

    private static void registerEvents(Feather feather) {
        feather.once("pets.events", () -> {
            CreatureEvents.CAN_TAME.register((c, p) -> {
                PetsModule m = active;
                return m == null || m.hasFreeSlot(p, true);
            });
            CreatureEvents.TAMED.register((c, p) -> {
                PetsModule m = active;
                return m != null && m.register(c, p);
            });
            CreatureEvents.STAY.register((c, stay) -> {
                PetsModule m = active;
                if (m != null) m.onStay(c, stay);
            });
            CreatureEvents.FED.register((c, p, item) -> {
                PetsModule m = active;
                if (m != null) m.onFed(c, p);
            });
            CreatureEvents.GREW_UP.register((young, adult) -> {
                PetsModule m = active;
                if (m != null) m.onGrewUp(young, adult);
            });
            CreatureEvents.DIED.register((mob, source) -> {
                PetsModule m = active;
                if (m != null && mob instanceof ArcanaCreature c) m.onCreatureDied(c);
            });
            CreatureEvents.INTERACT.register((mob, p, hand) -> {
                PetsModule m = active;
                return m == null || !(mob instanceof ArcanaCreature c) ? InteractionResult.PASS : m.onInteract(c, p, hand);
            });
            ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
                PetsModule m = active;
                if (m != null) m.onAnyDeath(e, source);
            });
            ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, amount) -> {
                PetsModule m = active;
                return m == null || m.allowDamage(e, source);
            });
            FeatherEvents.EFFECT_ADD.register((e, effect, source) -> {
                PetsModule m = active;
                return m == null || m.guard.allowEffect(e, effect, source);
            });
            ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
                PetsModule m = active;
                if (m != null && e instanceof ArcanaCreature) Tasks.later(1, () -> {
                    PetsModule now = active;
                    if (now != null) now.handleLoaded(e);
                });
            });
            ServerEntityEvents.ENTITY_UNLOAD.register((e, level) -> {
                PetsModule m = active;
                if (m != null && e instanceof ArcanaCreature) m.onUnload(e);
            });
            UseItemCallback.EVENT.register((p, level, hand) -> {
                PetsModule m = active;
                if (m == null || level.isClientSide() || !(p instanceof ServerPlayer sp)) return InteractionResult.PASS;
                if (m.reviveAir && m.usePlushie(sp, hand)) return InteractionResult.SUCCESS;
                return m.whistle.use(sp, hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            });
            UseBlockCallback.EVENT.register((p, level, hand, hit) -> {
                PetsModule m = active;
                if (m == null || level.isClientSide() || !(p instanceof ServerPlayer sp)) return InteractionResult.PASS;
                if (m.reviveSneakBlock && sp.isShiftKeyDown() && m.usePlushie(sp, hand)) return InteractionResult.SUCCESS;
                return InteractionResult.PASS;
            });
            ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
                PetsModule m = active;
                if (m != null) m.onJoin(h.player);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                PetsModule m = active;
                if (m != null) m.onQuit(h.player);
            });
            FeatherEvents.PLAYER_TELEPORTED.register((p, from, to, cause) -> {
                PetsModule m = active;
                if (m == null || to == null || from == null) return;
                boolean far = to.level() != from.level() || from.distanceSquared(to) > m.farDistance * m.farDistance;
                if (far) m.scheduleFollow(p);
            });
            FeatherEvents.PLAYER_RESPAWN.register((o, n, alive) -> {
                PetsModule m = active;
                if (m != null) m.scheduleFollow(n);
            });
        });
    }

    // ------------------------------------------------------------------ accessors for the parts

    Feather feather() {
        return feather;
    }

    Dialogs dialogs() {
        return feather.dialogs();
    }

    Text text() {
        return text;
    }

    Yaml cfg() {
        return cfg;
    }

    PetStore store() {
        return store;
    }

    Perks perks() {
        return perks;
    }

    Stable stable() {
        return stable;
    }

    Codex codex() {
        return codex;
    }

    Variants variants() {
        return variants;
    }

    Breeding breeding() {
        return breeding;
    }

    PetWork work() {
        return work;
    }

    PetMenu menu() {
        return menu;
    }

    double farDistance() {
        return farDistance;
    }

    int nameMax() {
        return nameMax;
    }

    PetSpecies species(String id) {
        return species.get(id);
    }

    Set<String> speciesIds() {
        return new LinkedHashSet<>(species.keySet());
    }

    /**
     * A section of pets.yml, falling back to the bundled default when the server's file doesn't have it (keys added
     * after the file was written).
     */
    static Yaml section(Yaml cfg, String path) {
        if (cfg.isSet(path)) return cfg.getConfigurationSection(path);
        return cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection(path);
    }

    /** The native type of a record's species (the MythicMobs id), or null. */
    static EntityType<?> type(String mythicId) {
        return ArcanaEntities.byMythicId(mythicId);
    }

    private static boolean matchesPath(Pet p, String path) {
        EntityType<?> t = type(p.species);
        return t != null && path != null && BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().equalsIgnoreCase(path);
    }

    /** One of the owner's pets came out, went away, fell or was revived: a modded owner's HUD follows. */
    void changed(UUID owner) {
        if (bridge != null) bridge.changed(owner);
    }

    /** Modded players get the Companion's screen instead of the chest / dialog: true when it was sent. */
    boolean handOff(ServerPlayer player, String screen) {
        return bridge != null && bridge.open(player, screen);
    }

    /** The mod's screens (null before enable). */
    PetsCompanion companion() {
        return bridge;
    }

    void openMenu(ServerPlayer player) {
        menu.open(player, 0);
    }

    boolean busy(Pet p) {
        return inFlight.contains(p.id);
    }

    /** Null when nobody rides the pet, else "stable.dismount" (its owner sits on it) or "stable.ridden" (someone else). */
    String riddenState(Entity e, ServerPlayer owner) {
        UUID me = owner.getUUID();
        for (Entity passenger : e.getPassengers()) {
            if (passenger.getUUID().equals(me)) return "stable.dismount";
            if (passenger instanceof Player) return "stable.ridden";
        }
        return null;
    }

    // ------------------------------------------------------------------ text

    String raw(String key) {
        String path = "messages." + key;
        if (cfg.isList(path)) return String.join("\n", cfg.getStringList(path));
        String v = cfg.getString(path);
        if (v == null) {
            feather.getLogger().warn("pets.yml is missing '{}'.", path);
            return "<bad>" + key;
        }
        return v;
    }

    Component msg(String key, TagResolver... r) {
        return text.parse(raw(key), r);
    }

    List<Component> lines(String key, TagResolver... r) {
        String path = "messages." + key;
        List<String> raw = cfg.isList(path) ? cfg.getStringList(path) : List.of(raw(key));
        List<Component> out = new ArrayList<>();
        for (String line : raw) out.add(text.item(line, r));
        return out;
    }

    void send(Audience to, String key, TagResolver... r) {
        String raw = raw(key);
        if (!raw.isEmpty() && to != null) to.sendMessage(text.parse(raw, r));
    }

    void send(ServerPlayer to, String key, TagResolver... r) {
        if (to != null) send(Adv.of(to), key, r);
    }

    void actionBar(ServerPlayer to, String key, TagResolver... r) {
        String raw = raw(key);
        if (!raw.isEmpty() && to != null) Adv.actionBar(to, text.parse(raw, r));
    }

    void sound(ServerPlayer p, String key) {
        Sound s = sounds.get(key);
        if (s != null && p != null) Adv.sound(p, s);
    }

    String displayName(Pet p) {
        if (p.name != null && !p.name.isBlank()) return p.name;
        return speciesName(p);
    }

    String speciesName(Pet p) {
        PetSpecies s = species.get(p.species);
        return s != null ? s.name() : p.species;
    }

    /** Placeholders every pet message can use. {@code <pet>} wears the name color the owner picked (bond perk). */
    TagResolver petTags(Pet p) {
        return TagResolver.resolver(Text.component("pet", perks.name(p)), Text.unparsed("species", speciesName(p)),
                Text.unparsed("level", p.level), Text.unparsed("xp", p.xp), variants.tags(p));
    }

    String worldName(Level level) {
        if (level == null) return "?";
        if (level.dimension() == Level.NETHER) return raw("world-names.nether");
        if (level.dimension() == Level.END) return raw("world-names.end");
        if (level.dimension() == Level.OVERWORLD) return raw("world-names.overworld");
        return pretty(level.dimension().identifier().getPath());
    }

    String worldName(Pet.Spot spot) {
        if (spot == null) return "?";
        ServerLevel l = spot.level();
        return l != null ? worldName(l) : pretty(spot.worldName());
    }

    private static String pretty(String name) {
        if (name == null) return "?";
        String n = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        n = n.replace('_', ' ');
        return n.isEmpty() ? name : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    String nameOf(UUID player) {
        ServerPlayer online = feather.player(player);
        if (online != null) return Names.name(online);
        OfflinePlayer op = feather.lookup().byId(player);
        return op != null && op.name() != null ? op.name() : player.toString();
    }

    /** The pet's loaded, living entity, or null. */
    Entity entity(Pet p) {
        if (!p.alive() || p.entity == null) return null;
        return find(p.entity);
    }

    ArcanaCreature creature(Pet p) {
        return entity(p) instanceof ArcanaCreature c ? c : null;
    }

    private static Entity find(UUID id) {
        var server = Feather.serverOrNull();
        if (server == null || id == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null && e.isAlive() && !e.isRemoved()) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ cap

    int cap(ServerPlayer p) {
        return PetUtil.numeric(p, "feather.pets.cap.", defaultCap);
    }

    /** The plushie of the owner's main pet, for the hub's portrait: a following pet beats a staying one, then the newest. */
    ItemStack portrait(UUID owner) {
        Pet best = null;
        for (Pet p : store.of(owner)) {
            PetSpecies s = species.get(p.species);
            if (!p.alive() || s == null || s.plush() == null || PlushItems.item(s.plush()) == null) continue;
            if (best == null || (best.stay && !p.stay) || (best.stay == p.stay && p.created > best.created)) best = p;
        }
        return best == null ? null : PlushItems.build(species.get(best.species).plush());
    }

    int topBondLevel(UUID owner) {
        int best = 0;
        for (Pet p : store.of(owner)) best = Math.max(best, p.level);
        return best;
    }

    /** Bond XP for every living pet that follows the player (loaded in their world within 32 blocks). */
    int addBondToFollowers(ServerPlayer owner, int xp) {
        if (owner == null || xp <= 0 || store == null) return 0;
        double r = Math.max(32, nearRadius);
        int n = 0;
        for (Pet p : store.of(owner.getUUID())) {
            if (!p.alive() || p.stay) continue;
            Entity e = entity(p);
            if (e == null || e.level() != owner.level() || e.distanceToSqr(owner) > r * r) continue;
            addXp(p, xp);
            n++;
        }
        return n;
    }

    int countActive(UUID owner) {
        int n = 0;
        for (Pet p : store.ofUnsorted(owner)) {
            if (!p.alive()) continue;
            PetSpecies s = species.get(p.species);
            if (!countMounts && s != null && s.mount()) continue;
            n++;
        }
        return n;
    }

    /** True (and tells the player) when pet actions are blocked because data/pets.yml couldn't be read. */
    boolean refuse(ServerPlayer p) {
        if (store == null || !store.unreadable()) return false;
        if (!onCooldown("dataerror:" + p.getUUID(), 2000)) {
            send(p, "data-error");
            sound(p, "error");
        }
        return true;
    }

    boolean hasFreeSlot(ServerPlayer p, boolean notify) {
        if (store.unreadable()) {
            if (notify) refuse(p);
            return false;
        }
        int cap = cap(p);
        if (cap == Integer.MAX_VALUE) return true;
        boolean free = countActive(p.getUUID()) < cap;
        if (!free && notify) {
            if (!onCooldown("cap:" + p.getUUID(), capNoticeMs)) {
                send(p, "cap.reached", Text.unparsed("cap", cap));
                sound(p, "error");
            } else {
                actionBar(p, "cap.reached-bar", Text.unparsed("cap", cap));   // a quiet cue for every further click
            }
        }
        return free;
    }

    String capLabel(ServerPlayer p) {
        int cap = cap(p);
        return cap == Integer.MAX_VALUE ? "∞" : Integer.toString(cap);
    }

    boolean onCooldown(String key, long ms) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(key);
        if (until != null && now < until) return true;
        cooldowns.put(key, now + ms);
        if (cooldowns.size() > 8192) {
            cooldowns.values().removeIf(t -> t <= now);
            if (cooldowns.size() > 32768) cooldowns.clear();
        }
        return false;
    }

    // ------------------------------------------------------------------ store <-> entities

    static UUID tagOf(Entity e) {
        return PetStore.uuid(Pdc.getString(e, PET_KEY));
    }

    static void tag(Entity e, Pet p) {
        Pdc.putString(e, PET_KEY, p.id.toString());
    }

    static void untag(Entity e) {
        Pdc.remove(e, PET_KEY);
    }

    Pet find(Entity e) {
        UUID tag = tagOf(e);
        Pet p = tag == null ? null : store.get(tag);
        return p != null ? p : store.byEntity(e.getUUID());
    }

    /** Copies the creature's state (stay, health, position) into the record. */
    void syncRecord(Pet p, Entity e) {
        Pet.Spot before = p.spot;
        double hp = p.health, max = p.maxHealth;
        boolean stay = p.stay;
        p.spot = Pet.Spot.of(e);
        if (e instanceof LivingEntity le) {
            p.health = le.getHealth();
            p.maxHealth = le.getMaxHealth();
        }
        if (e instanceof ArcanaCreature c) {
            p.stay = c.staying();
            if (!p.stay && p.work != null) work.stop(p);
        }
        if (p.stay != stay || p.health != hp || p.maxHealth != max || moved(before, p.spot)) store.markDirty();
    }

    private static boolean moved(Pet.Spot a, Pet.Spot b) {
        if (a == null || b == null) return a != b;
        if (!java.util.Objects.equals(a.worldKey(), b.worldKey())) return true;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz > 0.25;
    }

    /** A name tag's name made to fit rename.max-length, or null when rename.pattern doesn't allow it. */
    private String cleanName(String raw) {
        String n = raw.strip();
        if (n.length() > nameMax) n = n.substring(0, nameMax).strip();
        return n.isEmpty() || !namePattern.matcher(n).matches() ? null : n;
    }

    /** Bond, look and name from the record onto the loaded creature (spawn, load, revive, perks, admin). */
    void applyTo(Pet p, ArcanaCreature c) {
        if (c == null) return;
        if (c.bond() != p.level) c.setBond(p.level);
        variants.apply(p, c);
        boolean styled = p.name != null || p.shiny || perks.color(p) != null;
        net.minecraft.network.chat.Component want = styled ? Adv.vanilla(perks.name(p))
                : c.hasCustomName() ? net.minecraft.network.chat.Component.literal(speciesName(p)) : null;
        if (want != null && !want.equals(c.getCustomName())) {
            c.setCustomName(want);
            c.setCustomNameVisible(true);
        }
        if (p.namePending) {
            p.namePending = false;
            store.markDirty();
        }
    }

    /** Re-applies the record to the pet's loaded entity (after a color change or /pets admin setbond). */
    void refresh(Pet p) {
        applyTo(p, creature(p));
        changed(p.owner);
    }

    void reapplyAll() {
        for (Pet p : store.loaded()) {
            ArcanaCreature c = creature(p);
            if (c != null) applyTo(p, c);
        }
    }

    /**
     * CreatureEvents.TAMED: a creature was tamed by a player. True when the owner, online, got the "tamed" line (the
     * creature then prints no fallback line of its own).
     */
    boolean register(ArcanaCreature c, ServerPlayer trigger) {
        if (store.unreadable()) return false;
        UUID owner = c.getOwnerReference() != null ? c.getOwnerReference().getUUID() : trigger == null ? null : trigger.getUUID();
        if (owner == null) return false;
        Pet existing = find(c);
        if (existing != null) {
            if (existing.owner.equals(owner)) {
                if (!c.getUUID().equals(existing.entity)) store.setEntity(existing, c.getUUID());
                existing.state = Pet.State.ALIVE;
                tag(c, existing);
                syncRecord(existing, c);
                applyTo(existing, c);
                store.markLoaded(existing);
                return false;
            }
            store.release(existing, !existing.replaced.isEmpty());   // a new owner tamed it: the old record ends here
        }
        Pet p = newRecord(c, owner, null, null);
        Variants.Roll roll = variants.roll(owner, p.species, null, null);
        if (roll.variant() != null || roll.shiny()) {
            p.variant = roll.variant();
            p.shiny = roll.shiny();
            store.markDirty();
            applyTo(p, c);
        }
        feather.stat(owner, "pets-tamed", 1);
        boolean firstOfKind = codex.tamed(owner, p.species, false);
        codex.found(owner, p);
        ServerPlayer o = feather.player(owner);
        if (o != null) {
            // one line: the codex's "new in your codex" rides on it for a first of its kind
            send(o, firstOfKind ? "tamed-first-kind" : "tamed", petTags(p), Text.unparsed("count", countActive(owner)),
                    Text.unparsed("cap", capLabel(o)), codex.tamedTags(owner));
            variants.announce(o, p);
            feather.advance(o, "tame", p.species);
            Hooks.Quests q = feather.service(Hooks.Quests.class);
            if (q != null) q.progress(o, "tame", p.species, 1, "tame:" + c.getUUID());
        }
        return o != null;
    }

    private Pet newRecord(ArcanaCreature c, UUID owner, UUID id, PetMigration.Info legacy) {
        Pet p = new Pet(id != null ? id : UUID.randomUUID(), owner, c.species().mythicId);
        p.created = System.currentTimeMillis();
        p.entity = c.getUUID();
        if (legacy != null) {
            // what the Paper server's creature carried: its name, bond and name color
            p.name = legacy.name() == null ? null : cleanName(variants.strip(legacy.name()));
            if (p.name != null && p.name.equals(speciesName(p))) p.name = null;
            p.level = Math.max(0, Math.min(maxLevel(), legacy.bond()));
            p.xp = levelXp(p.level);
            p.color = legacy.color();
        }
        syncRecord(p, c);
        if (legacy == null) p.level = levelFor(p.xp);
        tag(c, p);
        store.add(p);
        applyTo(p, c);
        changed(owner);
        return p;
    }

    void stashLegacy(UUID entity, PetMigration.Info info) {
        legacyInfo.put(entity, info);
    }

    private void scanLoaded() {
        var server = Feather.serverOrNull();
        if (server == null) return;
        for (ServerLevel l : server.getAllLevels()) {
            for (Entity e : l.getAllEntities()) if (e instanceof ArcanaCreature) handleLoaded(e);
        }
    }

    /** A creature entered the world: adopt it, sync it, or remove a stale copy. */
    void handleLoaded(Entity e) {
        if (!(e instanceof ArcanaCreature c) || !e.isAlive() || e.isRemoved() || store.unreadable()) return;
        PetMigration.Info legacy = legacyInfo.remove(e.getUUID());
        UUID tag = tagOf(e);
        Pet p = tag != null ? store.get(tag) : store.byEntity(e.getUUID());
        if (p == null && tag != null && store.isReleased(tag)) {
            makeWild(c);
            return;
        }
        if (p != null && !p.alive()) {
            // In the stable or dead: this entity is what was left in the world (a crash before its chunk saved, or an
            // old copy from before a respawn). A load never brings a pet back; only a revive or the stable does.
            feather.getLogger().info("Removed a leftover copy of {} (pet {}, {}).", displayName(p), p.id, p.stabled() ? "in the stable" : "dead");
            despawn(e);
            return;
        }
        if (p != null) {
            UUID uuid = e.getUUID();
            if (!uuid.equals(p.entity)) {
                Entity current = p.entity == null ? null : find(p.entity);
                if (p.replaced.contains(uuid) || current != null) {
                    feather.getLogger().info("Removed a leftover copy of {} (pet {}).", displayName(p), p.id);
                    despawn(e);
                    return;
                }
                // The record's entity isn't loaded and this one carries the pet's tag: this one is the pet now.
                store.addReplaced(p, p.entity);
                store.setEntity(p, uuid);
            }
            if (!c.isTame() || c.getOwnerReference() == null || !p.owner.equals(c.getOwnerReference().getUUID())) {
                c.setTame(true, true);
                c.setOwnerReference(EntityReference.of(p.owner));
            }
            c.setPersistenceRequired();
            tag(e, p);
            if (legacy != null && legacy.staying() != c.staying()) c.setStaying(legacy.staying(), null);
            syncRecord(p, e);
            applyTo(p, c);
            store.markLoaded(p);   // pruned by tick() once gone; an unload event alone may be a world change
            work.loaded(p);
            store.markDirty();
            changed(p.owner);
            return;
        }
        String mythic = c.species().mythicId;
        if (!adopt || !c.isTame() || c.getOwnerReference() == null || !species.containsKey(mythic)) return;
        UUID owner = c.getOwnerReference().getUUID();
        Pet before = store.replacedOwner(e.getUUID());
        if (before != null) {
            feather.getLogger().info("Removed a leftover copy of {} (pet {}).", displayName(before), before.id);
            despawn(e);
            return;
        }
        Pet adopted = newRecord(c, owner, tag, legacy);
        feather.getLogger().info("Adopted {} ({}) as a pet of {}.", speciesName(adopted), e.getUUID(), nameOf(owner));
    }

    private void onUnload(Entity e) {
        Pet p = store.byEntity(e.getUUID());
        if (p == null || !p.alive()) return;
        work.unloading(p, e);
        syncRecord(p, e);
        store.markDirty();
        changed(p.owner);
    }

    // ------------------------------------------------------------------ creature events

    private void onStay(ArcanaCreature c, boolean stay) {
        Pet p = store.byEntity(c.getUUID());
        if (p == null || !p.alive()) return;
        p.stay = stay;
        if (!stay) work.stop(p);
        store.markDirty();
        changed(p.owner);
    }

    private void onFed(ArcanaCreature c, ServerPlayer player) {
        Pet p = store.byEntity(c.getUUID());
        if (p == null || !p.alive() || !p.owner.equals(player.getUUID())) return;
        if (onCooldown("feed:" + p.id, feedCooldownMs)) return;
        addXp(p, xpFeed);
    }

    /**
     * The owner's click on their pet, before the creature's own handling: a name tag renames through the record, a
     * breeding treat is used here, an empty hand pets it (bond XP; the creature's own petting still runs).
     */
    private InteractionResult onInteract(ArcanaCreature c, ServerPlayer player, InteractionHand hand) {
        Pet p = store.byEntity(c.getUUID());
        if (p == null || !p.alive() || !p.owner.equals(player.getUUID())) return InteractionResult.PASS;
        if (store.unreadable()) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (held.is(Items.NAME_TAG) && !player.isShiftKeyDown()) {
            net.minecraft.network.chat.Component name = held.get(DataComponents.CUSTOM_NAME);
            if (name == null) return InteractionResult.PASS;
            if (onCooldown("nametag:" + p.id, 700)) return InteractionResult.SUCCESS;
            String clean = cleanName(variants.strip(name.getString()));
            if (clean == null) {
                send(player, "rename.tag-invalid", petTags(p));
                sound(player, "error");
                return InteractionResult.SUCCESS;
            }
            rename(p, clean);
            held.consume(1, player);
            PetUtil.particles(c, ParticleTypes.HAPPY_VILLAGER, 0.8, 6, 0.4, 0.4, 0.02);
            send(player, "rename.done", petTags(p));
            return InteractionResult.SUCCESS;
        }
        if (held.isEmpty()) {
            if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() == perks.pettingSneak()) perks.pet(player, p, c);
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) return InteractionResult.PASS;
        p.stay = c.staying();
        Breeding.Fed fed = breeding.feed(player, p, held, hand);
        return fed == Breeding.Fed.USED ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * A raised pet grew up into another type (a penguin chick): the same record now holds the adult (species, entity,
     * tag); the young entity is removed by the creature. The codex counts the grown-up form as tamed too.
     */
    private void onGrewUp(ArcanaCreature young, ArcanaCreature adult) {
        if (store.unreadable()) return;
        Pet p = find(young);
        if (p == null || !p.alive()) return;
        p.species = adult.species().mythicId;
        p.variant = null;
        // its adult kind counts as tamed for the advancements too (their targets keep the chick ids, so older pets count)
        ServerPlayer o = feather.player(p.owner);
        if (o != null) feather.advance(o, "tame", p.species);
        store.addReplaced(p, young.getUUID());
        store.setEntity(p, adult.getUUID());
        untag(young);
        tag(adult, p);
        syncRecord(p, adult);
        applyTo(p, adult);
        if (codex.tamed(p.owner, p.species, false)) codex.announceTamed(p.owner, p.species);   // no tame line says it here
        store.markDirty();
        changed(p.owner);
    }

    private void onCreatureDied(ArcanaCreature c) {
        Pet p = find(c);
        if (p != null && p.alive() && c.getUUID().equals(p.entity)) fallen(p, c);
    }

    private void onAnyDeath(LivingEntity dead, DamageSource source) {
        if (dead instanceof Player || xpKill <= 0) return;
        ServerPlayer killer = FeatherEvents.attacker(source);
        if (killer == null) return;
        if (killHostileOnly && !(dead instanceof Enemy)) return;
        for (Pet pet : store.of(killer.getUUID())) {
            if (!pet.alive() || pet.stay) continue;
            Entity e = entity(pet);
            if (e == null || e == dead || e.level() != dead.level()) continue;
            if (e.distanceToSqr(dead) > killRadius * killRadius) continue;
            if (onCooldown("kill:" + pet.id, killCooldownMs)) continue;
            addXp(pet, xpKill);
        }
    }

    private boolean allowDamage(LivingEntity e, DamageSource source) {
        if (source.getDirectEntity() instanceof FireworkRocketEntity fw && Pdc.has(fw, Codex.FIREWORK)) return false;
        if (!guard.allowDamage(e, source)) return false;
        if (source.is(DamageTypeTags.IS_FALL) && e instanceof ArcanaCreature) {
            Pet p = store.byEntity(e.getUUID());
            if (perks.noFallDamage(p)) return false;
        }
        return true;
    }

    private void fallen(Pet p, Entity where) {
        p.state = Pet.State.FALLEN;
        store.setEntity(p, null);
        p.spot = Pet.Spot.of(where);
        p.health = 0;
        p.diedAt = System.currentTimeMillis();
        p.stay = false;
        work.stop(p);
        changed(p.owner);
        ServerPlayer owner = feather.player(p.owner);
        if (owner != null) tellFallen(owner, p);
        else p.deathUnseen = true;
        // in the journal now: a crash before the 30 s save would leave it alive on record, and a recall would bring it
        // back without a plushie
        store.journal(p);
    }

    private void tellFallen(ServerPlayer owner, Pet p) {
        PetSpecies s = species.get(p.species);
        Pet.Spot at = p.spot;
        String plushName = s == null || s.plush() == null ? "?" : speciesName(p) + " plushie";
        send(owner, "fallen", petTags(p), Text.unparsed("world", worldName(at)),
                Text.unparsed("x", at == null ? 0 : (int) Math.floor(at.x())), Text.unparsed("y", at == null ? 0 : (int) Math.floor(at.y())),
                Text.unparsed("z", at == null ? 0 : (int) Math.floor(at.z())), Text.unparsed("plush", plushName));
        sound(owner, "fallen");
        p.deathUnseen = false;
    }

    private void onJoin(ServerPlayer player) {
        UUID id = player.getUUID();
        codex.joined(player);
        Tasks.later(60, () -> {
            ServerPlayer p = feather.player(id);
            if (p == null) return;
            if (store.unreadable()) {
                if (Perms.has(p, "feather.pets.admin")) send(p, "admin.data-error");
                return;
            }
            for (Pet pet : store.of(id)) {
                if (!pet.alive() && pet.deathUnseen) {
                    tellFallen(p, pet);
                    store.markDirty();
                }
            }
            // following pets left somewhere else (another world, far away, an unloaded chunk) catch up
            if (!p.isDeadOrDying()) bringFollowers(p, false);
        });
    }

    private void onQuit(ServerPlayer player) {
        Tasks.Task t = pendingFollow.remove(player.getUUID());
        if (t != null) t.cancel();
        PetUtil.forget(player.getUUID());
    }

    // ------------------------------------------------------------------ periodic

    private void tick() {
        ticks++;
        boolean minute = ticks % 12 == 0;
        for (Pet p : store.loaded()) {
            Entity e = entity(p);
            if (e == null) {
                store.unmarkLoaded(p);   // unloaded, dead or gone: its next load or spawn puts it back
                continue;
            }
            syncRecord(p, e);
            if (e instanceof ArcanaCreature c && c.bond() != p.level) applyTo(p, c);
            if (minute && xpNear > 0 && !p.stay) {
                ServerPlayer owner = feather.player(p.owner);
                if (owner != null && owner.level() == e.level() && owner.distanceToSqr(e) <= nearRadius * nearRadius && !owner.isSpectator()) {
                    addXp(p, xpNear);
                }
            }
        }
    }

    // ------------------------------------------------------------------ bond

    int levelFor(int xp) {
        int lvl = 0;
        for (int i = 0; i < levels.length; i++) if (xp >= levels[i]) lvl = i;
        return Math.min(lvl, 5);
    }

    int maxLevel() {
        return Math.min(5, levels.length - 1);
    }

    int nextLevelXp(Pet p) {
        return p.level + 1 < levels.length && p.level < 5 ? levels[p.level + 1] : -1;
    }

    int levelXp(int level) {
        return levels[Math.max(0, Math.min(level, levels.length - 1))];
    }

    void addXp(Pet p, int amount) {
        if (amount <= 0) return;
        Hooks.Quests q = feather.service(Hooks.Quests.class);
        if (q != null) amount = q.bondXp(amount);   // a server-goal bond boost
        int top = levels[maxLevel()];
        p.xp = Math.min(p.xp + amount, Math.max(top, p.xp));
        int lvl = levelFor(p.xp);
        store.markDirty();
        if (lvl <= p.level) {
            ArcanaCreature c = creature(p);
            if (c != null) CreatureSfx.bondUp(c);   // throttled chime + its pleased sound
            return;
        }
        int from = p.level;
        p.level = lvl;
        ArcanaCreature c = creature(p);
        if (c != null) {
            applyTo(p, c);
            if (levelParticles) {
                PetUtil.particles(c, ParticleTypes.HEART, 0.8, 8, 0.5, 0.4, 0);
                PetUtil.particles(c, ParticleTypes.TOTEM_OF_UNDYING, 0.8, 30, 0.4, 0.6, 0.25);
            }
            CreatureSfx.bondLevel(c, lvl);   // bell arpeggio, the species' celebration, finale + its particles
        }
        ServerPlayer owner = feather.player(p.owner);
        if (owner != null && q != null) q.progress(owner, "bond", p.species, lvl - from);
        if (owner != null) {
            send(owner, "bond.level-up", petTags(p));
            if (levelTitle) {
                Adv.of(owner).showTitle(Title.title(msg("bond.level-up-title", petTags(p)), msg("bond.level-up-subtitle", petTags(p)),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2200), Duration.ofMillis(600))));
            }
            sound(owner, "level-up");
        }
        perks.onLevelUp(p, from, lvl, owner);
        changed(p.owner);
    }

    /** Admin: sets the bond level (XP goes to that level's threshold). Announces newly unlocked perks. */
    void setBond(Pet p, int level) {
        int from = p.level;
        p.level = Math.max(0, Math.min(maxLevel(), level));
        p.xp = levelXp(p.level);
        store.markDirty();
        refresh(p);
        if (p.level > from) perks.onLevelUp(p, from, p.level, feather.player(p.owner));
    }

    /** The pet page's breeding line (ready / resting / bond needed), or null for species that don't breed. */
    Component breedingLine(Pet p) {
        if (!breeding.allowed(p.species) || breeding.item(p.species) == null) return null;
        TagResolver t = TagResolver.resolver(petTags(p), Text.unparsed("item", PetUtil.pretty(breeding.item(p.species))),
                Text.unparsed("bond", breeding.minBond()));
        if (p.level < breeding.minBond()) return msg("breeding.line-bond", t);
        long rest = breeding.restLeft(p);
        if (rest > 0) return msg("breeding.line-resting", t, Text.unparsed("time", Breeding.duration(rest)));
        return msg("breeding.line-ready", t);
    }

    // ------------------------------------------------------------------ revive

    /** A plushie click (air, or sneak + block): revives the latest fallen pet of its species. True when it took the click. */
    private boolean usePlushie(ServerPlayer player, InteractionHand hand) {
        String id = PlushItems.id(player.getItemInHand(hand));
        PetSpecies s = id == null ? null : byPlush.get(id.toLowerCase(Locale.ROOT));
        if (s == null) return false;
        Pet fallen = latestFallen(player.getUUID(), s.id());
        if (fallen == null) return false;   // nothing to revive: the plushie is just a plushie (it gets placed)
        if (onCooldown("revive:" + player.getUUID(), 500)) return true;   // the other hand / a double fire
        revive(player, fallen, hand);
        return true;
    }

    Pet latestFallen(UUID owner, String speciesId) {
        Pet best = null;
        for (Pet p : store.ofUnsorted(owner)) {
            if (!p.fallen() || !p.species.equals(speciesId)) continue;
            if (best == null || p.diedAt > best.diedAt) best = p;
        }
        return best;
    }

    boolean hasPlushie(ServerPlayer player, Pet p) {
        PetSpecies s = species.get(p.species);
        return s != null && s.plush() != null && plushSlot(player, s.plush()) >= 0;
    }

    private static int plushSlot(ServerPlayer player, String plush) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            String id = PlushItems.id(inv.getItem(i));
            if (id != null && id.equalsIgnoreCase(plush)) return i;
        }
        return -1;
    }

    /**
     * Brings a fallen pet back next to its owner, using up one of the species' plushies (from {@code hand}, or from
     * anywhere in the inventory when {@code hand} is null).
     */
    boolean revive(ServerPlayer player, Pet p, InteractionHand hand) {
        if (!p.fallen()) {
            send(player, p.stabled() ? "stable.in-stable" : "revive.not-fallen", petTags(p));
            return false;
        }
        if (!p.owner.equals(player.getUUID())) return false;
        PetSpecies s = species.get(p.species);
        // Second Wind (bond perk): from the menu, once a day, no plushie needed.
        boolean free = hand == null && perks.freeReviveReady(p);
        if (s == null || (!free && (s.plush() == null || PlushItems.item(s.plush()) == null)) || type(p.species) == null) {
            send(player, "revive.unavailable", petTags(p));
            sound(player, "error");
            return false;
        }
        if (!hasFreeSlot(player, false)) {
            send(player, "revive.at-cap", petTags(p), Text.unparsed("cap", capLabel(player)));
            sound(player, "error");
            return false;
        }
        boolean creative = player.isCreative() || free;
        ItemStack stack = null;
        if (!free) {
            if (hand != null) {
                ItemStack held = player.getItemInHand(hand);
                String id = PlushItems.id(held);
                stack = id != null && id.equalsIgnoreCase(s.plush()) ? held : null;
            } else {
                int slot = plushSlot(player, s.plush());
                stack = slot < 0 ? null : player.getInventory().getItem(slot);
            }
            if (stack == null || stack.isEmpty()) {
                send(player, "revive.need-plushie", petTags(p));
                sound(player, "error");
                return false;
            }
        }
        ItemStack taken = null;
        if (!creative) {
            taken = stack.copyWithCount(1);
            stack.shrink(1);
        }
        Entity e = spawnPet(p, PetUtil.near(player), 1.0);
        if (e == null) {
            if (taken != null) PetUtil.give(player, taken);
            send(player, "revive.failed", petTags(p));
            sound(player, "error");
            return false;
        }
        p.diedAt = 0;
        p.deathUnseen = false;
        if (free) perks.usedFreeRevive(p);
        PetUtil.particles(e, ParticleTypes.TOTEM_OF_UNDYING, 0.8, 40, 0.4, 0.6, 0.3);
        PetUtil.particles(e, ParticleTypes.HEART, 0.8, 6, 0.4, 0.3, 0);
        CreatureSfx.revive(e);
        send(player, free ? "perks.second-wind.used" : "revive.done", petTags(p));
        sound(player, "revive");
        store.markDirty();
        return true;
    }

    /** Spawns the pet's species as this record's living entity, tamed to the owner, with name, bond and look. */
    Entity spawnPet(Pet p, Loc at, double healthFraction) {
        EntityType<?> type = type(p.species);
        if (type == null || at == null) return null;
        ServerLevel level = at.level();
        Entity raw;
        try {
            raw = type.create(level, EntitySpawnReason.MOB_SUMMONED);
        } catch (Throwable t) {
            feather.getLogger().warn("Could not spawn {} for pet {}", p.species, p.id, t);
            return null;
        }
        if (!(raw instanceof ArcanaCreature c)) {
            if (raw != null) raw.discard();
            return null;
        }
        c.snapTo(at.x(), at.y(), at.z(), at.yaw(), 0);
        if (p.name != null) c.setCustomName(net.minecraft.network.chat.Component.literal(p.name));   // no preset name
        c.finalizeSpawn(level, level.getCurrentDifficultyAt(c.blockPosition()), EntitySpawnReason.MOB_SUMMONED, null);
        c.setTame(true, true);
        c.setOwnerReference(EntityReference.of(p.owner));
        c.setPersistenceRequired();
        if (healthFraction < 1.0 && healthFraction > 0) c.setHealth((float) Math.max(1.0, Math.min(c.getMaxHealth(), c.getMaxHealth() * healthFraction)));
        tag(c, p);
        if (!level.addFreshEntity(c)) return null;
        p.state = Pet.State.ALIVE;
        p.stay = false;
        work.stop(p);
        store.setEntity(p, c.getUUID());
        p.health = c.getHealth();
        p.maxHealth = c.getMaxHealth();
        p.spot = Pet.Spot.of(c);
        applyTo(p, c);
        store.markDirty();
        changed(p.owner);
        return c;
    }

    /** Removes a pet's entity (stable, respawn, leftovers); the creature's own cleanup runs (light blocks ...). */
    void despawn(Entity e) {
        untag(e);
        if (!e.isRemoved()) e.discard();
    }

    // ------------------------------------------------------------------ menu actions

    /** Sets stay or follow on a loaded pet (nothing to do if it already is). False if the pet isn't loaded. */
    boolean setStay(ServerPlayer owner, Pet p, boolean stay) {
        ArcanaCreature c = creature(p);
        if (c == null) return false;
        if (c.staying() != stay) c.setStaying(stay, owner);
        p.stay = stay;
        if (!stay) work.stop(p);
        store.markDirty();
        changed(p.owner);
        return true;
    }

    /** Renames (null resets to the species name). Returns an error message key, or null when done. */
    String rename(Pet p, String name) {
        if (name != null) {
            name = name.strip();
            if (name.isEmpty()) name = null;
        }
        if (name != null && (name.length() > nameMax || !namePattern.matcher(name).matches())) return "rename.invalid";
        p.name = name;
        ArcanaCreature c = creature(p);
        if (c != null) {
            applyTo(p, c);
            p.namePending = false;
        } else {
            p.namePending = true;   // not loaded (far away, in the stable): its next load takes this name
        }
        store.markDirty();
        changed(p.owner);
        return null;
    }

    /** The creature goes wild: no owner, follows nobody, normal look, despawns like a wild one again. */
    void makeWild(ArcanaCreature c) {
        if (c.staying()) c.setStaying(false, null);
        c.setTame(false, true);
        c.setOwnerReference(null);
        c.setBond(0);
        variants.clear(c);
        c.setCustomName(null);
        c.setCustomNameVisible(false);
        ((MobPersistenceAccessor) c).aftrhrs$setPersistenceRequired(false);
        untag(c);
    }

    /**
     * Lets the pet go: a loaded one turns wild (others can tame it), an unloaded one goes wild when next loaded.
     * Returns null when done, else the message key that explains why not.
     */
    String release(ServerPlayer owner, Pet p) {
        if (!p.owner.equals(owner.getUUID())) return "stable.not-yours";
        if (busy(p) || breeding.pending(p)) return "release.busy";
        ArcanaCreature c = creature(p);
        if (c != null) {
            String ridden = riddenState(c, owner);
            if (ridden != null) return ridden;
            makeWild(c);
            PetUtil.particles(c, ParticleTypes.CLOUD, 0.6, 12, 0.3, 0.3, 0.02);
        }
        boolean leftovers = !p.replaced.isEmpty() || (p.alive() && p.entity != null && c == null);
        store.release(p, leftovers);
        store.journalRelease(p, leftovers);   // a crash before the timed save would re-adopt the now wild creature
        changed(p.owner);
        return null;
    }

    // ------------------------------------------------------------------ following across worlds

    private void scheduleFollow(ServerPlayer player) {
        UUID id = player.getUUID();
        boolean any = false;
        for (Pet p : store.ofUnsorted(id)) {
            if (p.alive()) {
                any = true;
                break;
            }
        }
        if (!any) return;
        Tasks.Task old = pendingFollow.remove(id);
        if (old != null) old.cancel();
        pendingFollow.put(id, Tasks.later(followDelay, () -> {
            pendingFollow.remove(id);
            ServerPlayer now = feather.player(id);
            if (now != null && !now.isDeadOrDying()) bringFollowers(now, false);
        }));
    }

    /** Brings the player's following pets that are in another world, in unloaded chunks or far away. */
    int bringFollowers(ServerPlayer player, boolean quiet) {
        List<Pet> todo = new ArrayList<>();
        for (Pet p : store.of(player.getUUID())) {
            if (!p.alive()) continue;
            Entity e = entity(p);
            if (e != null) {
                if (e instanceof ArcanaCreature c) p.stay = c.staying();
                if (e.level() == player.level() && e.distanceToSqr(player) <= farDistance * farDistance) continue;
            }
            if (p.stay) continue;
            todo.add(p);
        }
        if (todo.isEmpty()) return 0;
        int[] left = {todo.size()};
        int[] moved = {0};
        UUID id = player.getUUID();
        for (Pet p : todo) {
            bring(p, player, false, outcome -> {
                if (outcome == Outcome.MOVED || outcome == Outcome.RESPAWNED) moved[0]++;
                ServerPlayer now = feather.player(id);
                if (--left[0] == 0 && moved[0] > 0 && !quiet && now != null) {
                    actionBar(now, "follow.caught-up", Text.unparsed("count", moved[0]));
                }
            });
        }
        return todo.size();
    }

    /** For the pet whistle: brings following pets in other worlds or unloaded chunks. How many are on their way. */
    int whistleFetch(ServerPlayer player) {
        int n = 0;
        for (Pet p : store.of(player.getUUID())) {
            if (!p.alive() || p.stay) continue;
            Entity e = entity(p);
            if (e != null && e.level() == player.level()) continue;   // the whistle reaches it directly
            n++;
            bring(p, player, false, o -> {
            });
        }
        return n;
    }

    /** Brings one pet to its owner, loading its chunk or respawning it from the record when needed. */
    void bring(Pet p, ServerPlayer owner, boolean unstay, Consumer<Outcome> done) {
        if (!p.alive()) {
            done.accept(Outcome.FAILED);
            return;
        }
        if (!inFlight.add(p.id)) {
            done.accept(Outcome.BUSY);
            return;
        }
        UUID ownerId = owner.getUUID();
        Consumer<Outcome> finish = o -> {
            inFlight.remove(p.id);
            done.accept(o);
        };
        try {
            Entity e = entity(p);
            if (e != null) {
                arrive(p, e, owner, unstay, finish);
                return;
            }
            Loc last = p.spot == null ? null : p.spot.location();
            if (last == null) {
                // Its world is gone (or it was never seen): nothing to load, so it comes back from the record.
                respawn(p, null, owner, finish);
                return;
            }
            ServerLevel level = last.level();
            int cx = last.blockX() >> 4, cz = last.blockZ() >> 4;
            // a portal ticket (expires on its own after 15 s) loads the 3x3 chunks with their entities
            level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(cx, cz), 2);
            awaitEntities(p, level, cx, cz, ownerId, unstay, finish, 0);
        } catch (Throwable t) {
            feather.getLogger().warn("Could not bring pet {}", p.id, t);
            finish.accept(Outcome.FAILED);
        }
    }

    private void awaitEntities(Pet p, ServerLevel level, int cx, int cz, UUID ownerId, boolean unstay, Consumer<Outcome> finish, int waited) {
        boolean loaded = true;
        for (int dx = -1; dx <= 1 && loaded; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.areEntitiesLoaded(ChunkPos.pack(cx + dx, cz + dz))) {
                    loaded = false;
                    break;
                }
            }
        }
        Entity e = p.entity == null ? null : find(p.entity);
        if (e == null && !loaded && waited < loadTimeout) {
            Tasks.later(2, () -> awaitEntities(p, level, cx, cz, ownerId, unstay, finish, waited + 2));
            return;
        }
        ServerPlayer owner = feather.player(ownerId);
        if (owner == null || !p.alive()) {
            finish.accept(Outcome.FAILED);
            return;
        }
        if (e != null) {
            // a moment for the load handling (record sync) before it is moved
            Tasks.later(3, () -> {
                ServerPlayer o = feather.player(ownerId);
                if (e.isAlive() && !e.isRemoved() && o != null) arrive(p, e, o, unstay, finish);
                else finish.accept(Outcome.FAILED);
            });
            return;
        }
        if (!loaded) {
            finish.accept(Outcome.FAILED);
            return;
        }
        // Its chunks are loaded with their entities and it isn't there: the entity is gone. Bring it back.
        respawn(p, null, owner, finish);
    }

    /** The pet is loaded: move it next to the owner (or respawn it there when crossing worlds). */
    private void arrive(Pet p, Entity e, ServerPlayer owner, boolean unstay, Consumer<Outcome> finish) {
        for (Entity passenger : e.getPassengers()) {
            if (passenger.getUUID().equals(owner.getUUID())) {
                finish.accept(Outcome.NEAR);
                return;
            }
            if (passenger instanceof Player) {
                finish.accept(Outcome.RIDDEN);
                return;
            }
        }
        if (unstay && e instanceof ArcanaCreature c && c.staying()) {
            c.setStaying(false, owner);
            p.stay = false;
            work.stop(p);
        }
        Loc to = PetUtil.near(owner);
        if (e.level() == owner.level()) {
            if (e.distanceToSqr(owner) < 9) {
                finish.accept(Outcome.NEAR);
                return;
            }
            boolean ok = e.teleportTo(to.level(), to.x(), to.y(), to.z(), Set.<Relative>of(), to.yaw(), e.getXRot(), false);
            if (ok) {
                if (e instanceof ArcanaCreature c) c.getNavigation().stop();
                p.spot = Pet.Spot.of(to);
                finish.accept(Outcome.MOVED);
            } else {
                finish.accept(Outcome.FAILED);
            }
            return;
        }
        if (crossWorldTeleport) {
            Entity moved = e.teleport(new TeleportTransition(to.level(), to.vec(), Vec3.ZERO, to.yaw(), 0, TeleportTransition.DO_NOTHING));
            if (moved != null) {
                if (!moved.getUUID().equals(p.entity)) store.setEntity(p, moved.getUUID());
                p.spot = Pet.Spot.of(to);
                finish.accept(Outcome.MOVED);
                return;
            }
        }
        respawn(p, e, owner, finish);
    }

    /** Spawns the pet fresh next to the owner, then removes the old entity (if any) so there is never two. */
    private void respawn(Pet p, Entity old, ServerPlayer owner, Consumer<Outcome> finish) {
        double fraction = 1.0;
        if (old instanceof LivingEntity le) {
            double max = le.getMaxHealth();
            fraction = max > 0 ? le.getHealth() / max : 1.0;
        } else if (p.maxHealth > 0 && p.health > 0) {
            fraction = p.health / p.maxHealth;
        }
        UUID previous = p.entity;
        Entity e = spawnPet(p, PetUtil.near(owner), fraction);
        if (e == null) {
            if (previous != null) store.setEntity(p, previous);
            finish.accept(Outcome.FAILED);
            return;
        }
        if (previous != null && !previous.equals(p.entity)) {
            store.addReplaced(p, previous);
            store.markDirty();
        }
        if (old != null && !old.isRemoved()) despawn(old);
        finish.accept(Outcome.RESPAWNED);
    }

    /** Summons every living pet that isn't staying. */
    int bringAll(ServerPlayer player) {
        int n = 0;
        UUID id = player.getUUID();
        for (Pet p : store.of(id)) {
            if (!p.alive() || p.stay) continue;
            n++;
            bring(p, player, false, o -> {
                ServerPlayer now = feather.player(id);
                if (now != null) reportSummon(now, p, o);
            });
        }
        return n;
    }

    void reportSummon(ServerPlayer player, Pet p, Outcome o) {
        if (player.hasDisconnected()) return;
        switch (o) {
            case MOVED, RESPAWNED -> {
                actionBar(player, "summon.arrived", petTags(p));
                sound(player, "summon");
                ArcanaCreature c = creature(p);
                if (c != null) CreatureSfx.arrive(c, false);
            }
            case NEAR -> actionBar(player, "summon.near", petTags(p));
            case RIDDEN -> send(player, "summon.ridden", petTags(p));
            case BUSY -> actionBar(player, "summon.busy", petTags(p));
            default -> send(player, "summon.failed", petTags(p));
        }
    }

    // ------------------------------------------------------------------ /pets

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            admin(sender, args);
            return true;
        }
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (refuse(player)) return true;
        switch (sub) {
            case "summon", "call" -> {
                if (bringAll(player) == 0) send(player, "summon.none");
            }
            case "stable" -> stable.open(player, false);
            case "codex" -> {
                // the old way in (kept as an alias): the codex is no longer part of /pets, this opens the real one
                if (codex.enabled()) codex.open(player, args.length > 1 ? args[1] : null, args.length > 1 ? codex.species(String.join(" ",
                        Arrays.copyOfRange(args, 1, args.length))) : null, null);
                else menu.open(player, 0);
            }
            default -> menu.open(player, 0);
        }
        return true;
    }

    /** /pets admin setbond|codexreset|nextvariant|setvariant|breedreset|worktime|workinfo|list &lt;player&gt; ... */
    private void admin(Sender sender, String[] args) {
        if (!sender.hasPermission("feather.pets.admin")) {
            text.send(sender, "general.no-permission");
            return;
        }
        String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        UUID target = args.length >= 3 ? playerId(sender, args[2]) : null;
        if (what.equals("setbond") && args.length >= 4 && target != null) {
            int level;
            try {
                level = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                send(sender, "admin.usage");
                return;
            }
            String name = args.length >= 5 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : null;
            int n = 0;
            for (Pet p : store.of(target)) {
                if (!matches(p, name)) continue;
                setBond(p, level);
                n++;
            }
            send(sender, "admin.setbond", Text.unparsed("count", n), Text.unparsed("level", Math.max(0, Math.min(maxLevel(), level))),
                    Text.unparsed("player", nameOf(target)));
            return;
        }
        if (what.equals("codexreset") && target != null) {
            codex.reset(target);
            send(sender, "admin.codexreset", Text.unparsed("player", nameOf(target)));
            return;
        }
        if (what.equals("list") && target != null) {
            // not in the Paper plugin: every record of a player with its live entity (migration checks)
            List<Pet> list = store.of(target);
            sender.sendMessage(nameOf(target) + ": " + list.size() + " pet(s)");
            for (Pet p : list) {
                Entity e = entity(p);
                String live = e == null ? "not loaded" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " at "
                        + (int) Math.floor(e.getX()) + " " + (int) Math.floor(e.getY()) + " " + (int) Math.floor(e.getZ())
                        + (e instanceof ArcanaCreature c ? ", tame " + c.isTame() + ", owner " + (c.getOwnerReference() == null ? "-" : c.getOwnerReference().getUUID())
                        + ", bond " + c.bond() + ", staying " + c.staying() + ", noAI " + c.isNoAi() + ", silent " + c.isSilent()
                        + ", name '" + (c.getCustomName() == null ? "" : c.getCustomName().getString()) + "', tint #"
                        + String.format("%06X", c.tint()) + ", shiny " + c.shiny() : "");
                sender.sendMessage(" - " + p.id + " " + p.species + " '" + displayName(p) + "' " + p.state.name().toLowerCase(Locale.ROOT)
                        + ", bond " + p.level + " (" + p.xp + " xp), stay " + p.stay + ", color " + p.color + ", variant " + p.variant
                        + ", shiny " + p.shiny + ", entity " + p.entity + " [" + live + "]");
            }
            return;
        }
        if (adminExtra(sender, what, target, args)) return;
        send(sender, "admin.usage");
    }

    private boolean matches(Pet p, String name) {
        return name == null || displayName(p).equalsIgnoreCase(name) || speciesName(p).equalsIgnoreCase(name) || p.species.equalsIgnoreCase(name);
    }

    private boolean adminExtra(Sender sender, String what, UUID target, String[] args) {
        if (target == null) return false;
        TagResolver who = Text.unparsed("player", nameOf(target));
        switch (what) {
            case "nextvariant" -> {
                if (args.length < 4) return false;
                String v = args[3].toLowerCase(Locale.ROOT);
                boolean shiny = args.length >= 5 && args[4].equalsIgnoreCase("shiny");
                variants.force(target, v.equals("none") ? null : v, shiny);
                send(sender, "admin.nextvariant", who, Text.unparsed("variant", v), Text.unparsed("shiny", shiny ? "shiny" : "not shiny"));
                return true;
            }
            case "setvariant" -> {
                if (args.length < 5) return false;
                String v = args[3].toLowerCase(Locale.ROOT);
                boolean shiny = args[4].equalsIgnoreCase("shiny");
                String name = args.length >= 6 ? String.join(" ", Arrays.copyOfRange(args, 5, args.length)) : null;
                int n = 0;
                for (Pet p : store.of(target)) {
                    if (!matches(p, name)) continue;
                    Variants.Variant var = variants.get(p.species, v);
                    p.variant = var == null ? null : var.key();
                    p.shiny = shiny;
                    refresh(p);
                    n++;
                }
                store.markDirty();
                send(sender, "admin.setvariant", who, Text.unparsed("count", n));
                return true;
            }
            case "breedreset" -> {
                breeding.resetCooldowns(target);
                send(sender, "admin.breedreset", who);
                return true;
            }
            case "worktime" -> {
                if (args.length < 4) return false;
                int minutes;
                try {
                    minutes = Math.max(0, Math.min(100_000, Integer.parseInt(args[3])));
                } catch (NumberFormatException e) {
                    return false;
                }
                int n = 0;
                List<String> states = new ArrayList<>();
                for (Pet p : store.of(target)) {
                    if (!p.working()) continue;
                    states.add(displayName(p) + " " + work.fakeElapsed(p, minutes));
                    n++;
                }
                send(sender, "admin.worktime", who, Text.unparsed("count", n), Text.unparsed("minutes", minutes),
                        Text.unparsed("pets", states.isEmpty() ? "-" : String.join(", ", states)));
                return true;
            }
            case "workinfo" -> {
                List<String> lines = work.info(target);
                send(sender, "admin.workinfo", who, Text.unparsed("count", lines.size()));
                for (String l : lines) sender.sendMessage("  " + l);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private UUID playerId(Sender sender, String name) {
        OfflinePlayer off = feather.findOfflinePlayer(sender, name);   // nicknames too
        if (off != null) return off.id();
        return PetStore.uuid(name);
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = List.of();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("summon", "stable"));
            if (sender.hasPermission("feather.pets.admin")) subs.add("admin");
            options = subs;
        } else if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("feather.pets.admin")) {
            if (args.length == 2) options = List.of("setbond", "codexreset", "nextvariant", "setvariant", "breedreset", "worktime", "workinfo", "list");
            else if (args.length == 3) options = feather.completePlayers(sender, "");
            else if (args.length == 4 && args[1].equalsIgnoreCase("setbond")) options = List.of("0", "1", "2", "3", "4", "5");
            else if (args.length == 4 && (args[1].equalsIgnoreCase("nextvariant") || args[1].equalsIgnoreCase("setvariant"))) {
                List<String> v = new ArrayList<>(variants.allKeys());
                v.add(0, "none");
                options = v;
            } else if (args.length == 5 && args[1].toLowerCase(Locale.ROOT).endsWith("variant")) options = List.of("shiny", "plain");
            else if (args.length == 4 && args[1].equalsIgnoreCase("worktime")) options = List.of("60", "240", "1440");
        }
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    static PetsModule active() {
        return active;
    }
}
