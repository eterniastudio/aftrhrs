package net.axther.aftrhrs.feature.altars;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * Boss altars: right-click a Summoning Altar (furniture block {@code arcana_summoning_altar}) while holding a boss's
 * summon item. After the checks (place rules per boss, cooldowns, no boss already loose nearby, room to spawn, build
 * rights, away from spawn) the item is used up, the altar charges for a few seconds and the boss (a native Arcana
 * creature; altars.yml still names it by its MythicMobs id) appears beside it. The fight is tracked: a boss that strays
 * is pulled back, a boss nobody fights any more leaves (the summoner gets the item back), an endless fight times out,
 * and the boss scales up for the party around it. Settings: altars.yml; data: altars-data.yml (same format as Paper).
 */
public final class AltarsModule implements FeatherModule, FeatherCommand, Hooks.AltarArenas {
    private static volatile AltarsModule active;

    private final Feather feather;
    private Yaml config;
    private final Path dataFile;

    private String altarItem = "arcana_summoning_altar";
    private final Map<String, BossSpec> byItem = new LinkedHashMap<>();
    private final Map<String, BossSpec> byMob = new LinkedHashMap<>();
    private long altarCooldownMs, playerCooldownMs;
    private int buildupTicks, bossCheckRadius, spawnExclusion, leashRadius, abandonRadius, abandonSeconds;
    private long maxFightMs;
    private boolean requireBuild, refundAbandon, refundTimeout, refundVanish;
    private String announce = "server";
    /** Party scaling (altars.yml party-scaling). */
    private boolean scaleEnabled;
    private double healthPerPlayer, damagePerPlayer;
    private int scaleMaxPlayers;
    /** The max-health modifier: one per boss, replaced (never stacked) when the party grows. Paper's feather:party_scaling. */
    static final Identifier SCALE_ID = Identifier.fromNamespaceAndPath("feather", "party_scaling");
    /** altars-data.yml exists but could not be read: never save over it (see {@link #loadData}). */
    private boolean dataUnreadable;
    /** Changed since the last data write that worked. */
    private boolean dataDirty;
    /** Data writes queued on the data thread and not finished yet. */
    private final java.util.concurrent.atomic.AtomicInteger dataWrites = new java.util.concurrent.atomic.AtomicInteger();

    private final Map<String, Long> altarUsed = new HashMap<>();
    private final Map<UUID, Long> playerUsed = new HashMap<>();
    private final Map<UUID, Fight> fights = new LinkedHashMap<>();
    /** Bosses whose fight ended while they were unloaded: removed as soon as they load again. */
    private final Set<UUID> orphans = new LinkedHashSet<>();
    private final Map<UUID, List<String>> pendingRefunds = new HashMap<>();

    /** Altars charging right now (altar key -> charge) and the players charging them. */
    private static final class Charge {
        final UUID player;
        final String itemId;
        Tasks.Task task;

        Charge(UUID player, String itemId) {
            this.player = player;
            this.itemId = itemId;
        }
    }

    private final Map<String, Charge> charging = new HashMap<>();
    private final Set<UUID> chargingPlayers = new HashSet<>();
    private final Map<UUID, Long> clickTick = new HashMap<>();
    private Tasks.Task monitor;

    public AltarsModule(Feather feather) {
        this.feather = feather;
        this.dataFile = feather.getDataFolder().resolve("altars-data.yml");
    }

    @Override
    public String name() {
        return "Boss altars";
    }

    // ------------------------------------------------------------------ Hooks.AltarArenas (graves keep clear)

    @Override
    public List<Loc> arenaCenters() {
        List<Loc> out = new ArrayList<>();
        for (Fight f : fights.values()) {
            Loc l = f.home();
            if (l != null) out.add(l);
        }
        return out;
    }

    @Override
    public double arenaRadius() {
        return leashRadius;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void enable() {
        active = this;
        reload();   // also loads altars-data.yml
        feather.bind("altars", this);
        feather.provide(Hooks.AltarArenas.class, this);
        feather.once("altars.events", AltarsModule::registerEvents);
        monitor = Tasks.timer(40L, 20L, this::tickFights);
        Tasks.later(40L, this::validate);
    }

    @Override
    public void disable() {
        if (monitor != null) monitor.cancel();
        // An altar still charging when the server stops: give the offering back.
        for (Charge c : charging.values()) {
            if (c.task != null) c.task.cancel();
            refund(c.player, c.itemId, "refund", byItem.get(c.itemId));
        }
        charging.clear();
        chargingPlayers.clear();
        save();
        feather.withdraw(Hooks.AltarArenas.class);
        if (active == this) active = null;
    }

    @Override
    public void reload() {
        config = feather.loadResourceConfig("altars.yml");
        altarItem = config.getString("altar-item", "arcana_summoning_altar").toLowerCase(Locale.ROOT);
        altarCooldownMs = Math.max(0, config.getLong("altar-cooldown-seconds", 900)) * 1000L;
        playerCooldownMs = Math.max(0, config.getLong("player-cooldown-seconds", 1800)) * 1000L;
        buildupTicks = Math.max(1, (int) Math.round(config.getDouble("buildup-seconds", 5) * 20));
        bossCheckRadius = Math.max(0, config.getInt("boss-check-radius", 96));
        spawnExclusion = Math.max(0, config.getInt("spawn-exclusion-radius", 160));
        requireBuild = config.getBoolean("require-build-permission", true);
        leashRadius = Math.max(8, config.getInt("leash-radius", 48));
        abandonRadius = Math.max(8, config.getInt("abandon-radius", 64));
        abandonSeconds = Math.max(10, config.getInt("abandon-seconds", 180));
        maxFightMs = Math.max(1, config.getLong("max-fight-minutes", 30)) * 60_000L;
        refundAbandon = config.getBoolean("refund-on-abandon", true);
        refundTimeout = config.getBoolean("refund-on-timeout", false);
        refundVanish = config.getBoolean("refund-on-vanish", true);
        announce = config.getString("announce", "server").toLowerCase(Locale.ROOT);
        scaleEnabled = config.getBoolean("party-scaling.enabled", true);
        healthPerPlayer = Math.max(0, config.getDouble("party-scaling.health-per-extra-player", 0.6));
        damagePerPlayer = Math.max(0, config.getDouble("party-scaling.damage-per-extra-player", 0.15));
        scaleMaxPlayers = Math.max(1, config.getInt("party-scaling.max-players", 6));
        byItem.clear();
        byMob.clear();
        Yaml bosses = config.getConfigurationSection("bosses");
        if (bosses != null) {
            for (String mob : bosses.getKeys(false)) {
                Yaml s = bosses.getConfigurationSection(mob);
                if (s == null) continue;
                BossSpec spec = BossSpec.parse(mob, s, feather.getLogger());
                if (spec == null) continue;
                if (byItem.containsKey(spec.itemId())) {
                    feather.getLogger().warn("altars.yml: item {} is used by two bosses; {} skipped.", spec.itemId(), mob);
                    continue;
                }
                byItem.put(spec.itemId(), spec);
                byMob.put(mob, spec);
            }
        }
        feather.getLogger().info("Boss altars: {} summonable bosses.", byMob.size());
        tellBossBoard();
        // The boss board may start after us: tell it again once every module is up.
        Tasks.later(1L, this::tellBossBoard);
        // Also re-read on /feather reload, so a data file an admin fixed (or edited) is picked up. A change still on its
        // way to the disk lands first: reading the file back must not undo it.
        if (dataWrites.get() > 0) SafeYaml.drain(10);
        loadData();
    }

    /** The boss board lists every summonable boss, kills or not. */
    private void tellBossBoard() {
        Hooks.BossBoard board = feather.service(Hooks.BossBoard.class);
        if (board == null) return;
        Map<String, String> names = new LinkedHashMap<>();
        for (BossSpec s : byMob.values()) names.put(s.mobType(), s.name());
        try {
            board.bosses(names);
        } catch (RuntimeException e) {
            feather.getLogger().warn("Could not hand the altar bosses to the boss board", e);
        }
    }

    private void validate() {
        if (active != this) return;
        List<String> badMobs = new ArrayList<>(), badItems = new ArrayList<>();
        for (BossSpec s : byMob.values()) {
            if (bossType(s.mobType()) == null) badMobs.add(s.mobType());
            if (ArcanaContent.item(s.itemId()) == null) badItems.add(s.itemId());
        }
        if (ArcanaContent.block(altarItem) == null) badItems.add(altarItem);
        if (!badMobs.isEmpty()) feather.getLogger().warn("Boss altars: unknown boss types {}", badMobs);
        if (!badItems.isEmpty()) feather.getLogger().warn("Boss altars: unknown items {}", badItems);
    }

    /** The native entity type of an altars.yml boss (MythicMobs id like ArcanaWukong). */
    static EntityType<?> bossType(String mobType) {
        EntityType<?> t = ArcanaEntities.byMythicId(mobType);
        return t != null ? t : Legacy.mythicType(mobType).orElse(null);
    }

    /** The MythicMobs id of an entity if it is an Arcana creature (what altars.yml and altars-data.yml name). */
    static String typeOf(Entity e) {
        return e instanceof ArcanaMob am ? am.species().mythicId : null;
    }

    // ------------------------------------------------------------------ events (registered once per JVM)

    private static void registerEvents() {
        ArcanaContent.hook(id -> {
            AltarsModule m = active;
            return m != null && m.altarItem.equals(id);
        }, new FurnitureHook() {
            @Override
            public InteractionResult onUse(FurnitureContext ctx) {
                AltarsModule m = active;
                if (m == null || ctx.player() == null || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
                return m.use(ctx.player(), ctx.level(), ctx.origin()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }

            @Override
            public void onRemoved(FurnitureContext ctx) {
                AltarsModule m = active;
                if (m != null) m.altarBroken(ctx.level(), ctx.origin());
            }
        });
        // a hint when a summon item is used anywhere but an altar
        UseItemCallback.EVENT.register((player, level, hand) -> {
            AltarsModule m = active;
            if (m != null && player instanceof ServerPlayer p && hand == InteractionHand.MAIN_HAND) m.hint(p);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            AltarsModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!m.altarItem.equals(ArcanaContent.id(level.getBlockState(hit.getBlockPos())))) m.hint(p);
            return InteractionResult.PASS;
        });
        // party scaling in damage: the boss hits harder, and takes less when its max health hit the cap
        FeatherEvents.DAMAGE_AMOUNT.register((victim, source, amount) -> {
            AltarsModule m = active;
            return m == null ? amount : m.scaledDamage(victim, source, amount);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            AltarsModule m = active;
            if (m != null && !m.fights.isEmpty()) m.bossHurt(entity, source, taken);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            AltarsModule m = active;
            if (m != null && !m.fights.isEmpty()) m.bossDied(entity, source);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            AltarsModule m = active;
            if (m != null && !m.orphans.isEmpty() && m.orphans.contains(entity.getUUID())) m.orphanLoaded(entity);
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            AltarsModule m = active;
            if (m != null) m.join(h.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            AltarsModule m = active;
            if (m != null) m.clickTick.remove(h.player.getUUID());
        });
        // Paper's altar furniture becomes a block through fm-furniture; nothing else of ours was saved in the world.
    }

    // ------------------------------------------------------------------ messages

    private Component msg(String key, TagResolver... r) {
        return feather.text().parse(config.getString("messages." + key, key), r);
    }

    private void send(Sender to, String key, TagResolver... r) {
        // No explicit default: an older altars.yml without a newer key falls back to the bundled message.
        String raw = config.getString("messages." + key);
        if (raw == null || raw.isBlank()) return;
        to.sendMessage(feather.text().parse(raw, r));
    }

    private void send(ServerPlayer to, String key, TagResolver... r) {
        send(Sender.of(to), key, r);
    }

    /** The boss's MiniMessage name as one component, so its colour stops at the name instead of running on. */
    private TagResolver boss(BossSpec s) {
        return Placeholder.component("boss", s == null ? Component.text("boss") : feather.text().parse(s.name()));
    }

    private static TagResolver arg(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    private static TagResolver itemName(String id) {
        var stack = ArcanaContent.stack(id, 1);
        return Placeholder.component("item", stack.isEmpty() ? Component.text(id) : Adv.adventure(stack.getHoverName()));
    }

    private static String time(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    // ------------------------------------------------------------------ clicks

    private void hint(ServerPlayer player) {
        String held = ArcanaContent.id(player.getMainHandItem());
        if (held != null && byItem.containsKey(held) && !debounced(player, 40)) {
            Adv.actionBar(player, msg("hint-use-altar", boss(byItem.get(held))));
        }
    }

    /** True if the click was used. */
    private boolean use(ServerPlayer player, ServerLevel level, BlockPos altar) {
        if (debounced(player, 4)) return true;
        var held = player.getMainHandItem();
        String heldId = ArcanaContent.id(held);
        BossSpec spec = heldId == null ? null : byItem.get(heldId);
        if (spec == null) {
            if (held.isEmpty()) {
                showInfo(player);
                return true;
            }
            return false;
        }
        if (!Perms.has(player, "feather.altars.use")) {
            send(player, "no-permission");
            return true;
        }
        if (player.isSpectator()) return true;
        String key = key(level, altar);
        boolean bypass = Perms.has(player, "feather.altars.bypass");
        long now = System.currentTimeMillis();

        if (charging.containsKey(key) || chargingPlayers.contains(player.getUUID())) {
            send(player, "busy");
            return true;
        }
        for (Fight f : fights.values()) {
            if (f.altarKey.equals(key)) {
                send(player, "fight-active", boss(byMob.get(f.mobType)));
                return true;
            }
        }
        if (bossType(spec.mobType()) == null) {
            send(player, "unknown-boss", boss(spec));
            return true;
        }
        if (!bypass) {
            String problem = spec.placeProblem(level, altar);
            if (problem != null) {
                send(player, "wrong-place", boss(spec), Placeholder.parsed("hint", spec.hint()));
                return true;
            }
            Long a = altarUsed.get(key);
            if (a != null && now - a < altarCooldownMs) {
                send(player, "altar-cooldown", Placeholder.unparsed("time", time(altarCooldownMs - (now - a))));
                return true;
            }
            Long p = playerUsed.get(player.getUUID());
            if (p != null && now - p < playerCooldownMs) {
                send(player, "player-cooldown", Placeholder.unparsed("time", time(playerCooldownMs - (now - p))));
                return true;
            }
            if (nearSpawn(level, altar, player)) {
                send(player, "near-spawn", Placeholder.unparsed("radius", String.valueOf(spawnExclusion)));
                return true;
            }
        }
        if (bossNearby(level, Vec3.atCenterOf(altar))) {
            send(player, "boss-nearby");
            return true;
        }
        Loc spot = findSpot(level, altar, spec, player.position());
        if (spot == null) {
            send(player, "no-space", boss(spec), Placeholder.unparsed("w", String.valueOf(spec.width())),
                    Placeholder.unparsed("h", String.valueOf(spec.height())));
            return true;
        }
        if (requireBuild && !bypass && !mayBuild(player, spot)) {
            send(player, "no-build");
            return true;
        }

        // Every check passed: take the offering and start the ritual.
        if (!player.isCreative()) held.shrink(1);
        startCharge(player, level, altar, key, spec);
        return true;
    }

    private void showInfo(ServerPlayer player) {
        send(player, "info-header");
        for (BossSpec s : byMob.values()) {
            send(player, "info-line", boss(s), itemName(s.itemId()), Placeholder.parsed("hint", s.hint()));
        }
    }

    private boolean debounced(ServerPlayer p, int ticks) {
        long now = Tasks.now();
        Long last = clickTick.get(p.getUUID());
        if (last != null && now - last >= 0 && now - last < ticks) return true;
        clickTick.put(p.getUUID(), now);
        if (clickTick.size() > 1024) clickTick.clear();
        return false;
    }

    static String key(ServerLevel level, BlockPos b) {
        return Worlds.name(level) + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private static Loc parseKey(String key) {
        try {
            int c = key.lastIndexOf(':');
            ServerLevel w = Worlds.resolve(key.substring(0, c));
            String[] p = key.substring(c + 1).split(",");
            return w == null ? null : new Loc(w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ checks

    private boolean nearSpawn(ServerLevel level, BlockPos at, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        List<Loc> spawns = new ArrayList<>();
        var respawn = server.getRespawnData();
        ServerLevel spawnLevel = server.getLevel(respawn.globalPos().dimension());
        if (spawnLevel != null) spawns.add(Loc.of(spawnLevel, respawn.globalPos().pos()));
        Hooks.Spawn places = feather.service(Hooks.Spawn.class);
        try {
            Loc s = places == null ? null : places.spawn();
            if (s != null) spawns.add(s);
        } catch (RuntimeException ignored) {
            // the places module has no spawn yet
        }
        for (Loc s : spawns) {
            if (s.level() != level) continue;
            double dx = s.x() - at.getX(), dz = s.z() - at.getZ();
            if (dx * dx + dz * dz <= (double) spawnExclusion * spawnExclusion) return true;
        }
        // Vanilla spawn protection (vanilla checks it before any block use).
        return server.isUnderSpawnProtection(level, at, player);
    }

    private boolean bossNearby(ServerLevel level, Vec3 at) {
        double r2 = (double) bossCheckRadius * bossCheckRadius;
        for (Fight f : fights.values()) {
            Entity e = entity(f.boss);
            Loc l = e != null && e.isAlive() ? Loc.of(e) : f.home();
            if (l != null && l.level() == level && l.vec().distanceToSqr(at) <= r2) return true;
        }
        if (bossCheckRadius <= 0) return false;
        for (Mob e : level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(bossCheckRadius),
                e -> e.isAlive() && byMob.containsKey(typeOf(e)))) {
            if (e != null) return true;
        }
        return false;
    }

    /** Claims (BUILD) and vanilla spawn protection where the boss appears. Nothing is placed. */
    private boolean mayBuild(ServerPlayer player, Loc where) {
        if (!feather.claimAllows(player, where, ClaimAction.BUILD)) return false;
        return !where.level().getServer().isUnderSpawnProtection(where.level(), where.blockPos(), player)
                && player.mayBuild() && where.level().mayInteract(player, where.blockPos());
    }

    /**
     * Finds where the boss appears: a clear w x h x w box a few blocks from the altar, preferring the side away from the
     * player. Walkers need solid ground under the box; flyers appear 3 blocks up.
     */
    private static Loc findSpot(ServerLevel level, BlockPos altar, BossSpec spec, Vec3 from) {
        Vec3 dir = new Vec3(altar.getX() + 0.5 - from.x, 0, altar.getZ() + 0.5 - from.z);
        if (dir.lengthSqr() < 1e-4) dir = new Vec3(0, 0, 1);
        dir = dir.normalize();
        int half = spec.width() / 2;
        int base = Math.max(3, half + 2);
        int[] dists = {base, base + 1, base + 2, base - 1};
        double[] angles = {0, 45, -45, 90, -90, 135, -135, 180};
        int[] dys = spec.flying() ? new int[]{3, 2, 4, 1, 5} : new int[]{0, 1, -1, 2, -2, 3, -3};
        for (int d : dists) {
            if (d < 2) continue;
            for (double a : angles) {
                // Bukkit's rotateAroundY: x' = x cos + z sin, z' = -x sin + z cos
                double rad = Math.toRadians(a), cos = Math.cos(rad), sin = Math.sin(rad);
                Vec3 v = new Vec3(dir.x * cos + dir.z * sin, 0, -dir.x * sin + dir.z * cos).scale(d);
                int bx = (int) Math.floor(altar.getX() + 0.5 + v.x);
                int bz = (int) Math.floor(altar.getZ() + 0.5 + v.z);
                for (int dy : dys) {
                    int by = altar.getY() + dy;
                    if (clear(level, bx, by, bz, half, spec.height(), !spec.flying())) {
                        // the boss arrives facing the altar (and so the summoner)
                        float yaw = (float) Math.toDegrees(Math.atan2(v.x, -v.z));
                        return new Loc(level, bx + 0.5, by, bz + 0.5, yaw, 0);
                    }
                }
            }
        }
        return null;
    }

    private static boolean clear(ServerLevel level, int cx, int y, int cz, int half, int height, boolean needGround) {
        if (y <= level.getMinY() || y + height >= level.getMaxY()) return false;
        if (!level.hasChunk(cx >> 4, cz >> 4)) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (needGround) {
            BlockState below = level.getBlockState(p.set(cx, y - 1, cz));
            if (!below.isSolid()) return false;
        }
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int dy = 0; dy < height; dy++) {
                    p.set(x, y + dy, z);
                    BlockState b = level.getBlockState(p);
                    if (!b.getCollisionShape(level, p).isEmpty() || b.getFluidState().is(FluidTags.LAVA)) return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ ritual

    private void startCharge(ServerPlayer player, ServerLevel level, BlockPos altar, String key, BossSpec spec) {
        UUID pid = player.getUUID();
        String pname = player.getGameProfile().name();
        Vec3 center = new Vec3(altar.getX() + 0.5, altar.getY() + 1.0, altar.getZ() + 0.5);
        chargingPlayers.add(pid);
        send(player, "accepted", boss(spec));
        Charge charge = new Charge(pid, spec.itemId());
        charging.put(key, charge);
        SoundEvent chargeSound = sound(spec.chargeSound());
        int[] t = {0};
        charge.task = Tasks.timer(0L, 1L, () -> {
            if (t[0] % 2 == 0) {
                double progress = (double) t[0] / buildupTicks;
                for (int i = 0; i < 3; i++) {
                    double ang = t[0] * 0.35 + i * (Math.PI * 2 / 3);
                    double r = 1.6 - progress * 1.1;
                    level.sendParticles(spec.particle(), center.x + Math.cos(ang) * r, center.y + progress * 2.2,
                            center.z + Math.sin(ang) * r, 2, 0.02, 0.02, 0.02, 0.0);
                }
                level.sendParticles(ParticleTypes.ENCHANT, center.x, center.y + 0.3, center.z, 6, 0.6, 0.2, 0.6, 0.6);
            }
            if (t[0] % 20 == 0) {
                int left = (buildupTicks - t[0] + 19) / 20;
                float pitch = 0.6f + 0.9f * t[0] / buildupTicks;
                if (chargeSound != null) level.playSound(null, center.x, center.y, center.z, chargeSound, SoundSource.HOSTILE, 1.2f, pitch);
                Title title = Title.title(feather.text().parse(spec.name()),
                        msg("countdown", Placeholder.unparsed("n", String.valueOf(left))),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1150), Duration.ofMillis(250)));
                for (ServerPlayer p : level.players()) {
                    if (p.position().distanceToSqr(center) <= 32 * 32) Adv.of(p).showTitle(title);
                }
            }
            if (++t[0] < buildupTicks) return;
            charge.task.cancel();
            charging.remove(key);
            chargingPlayers.remove(pid);
            finishCharge(pid, pname, level, altar, key, spec);
        });
    }

    /** The altar block went away: a ritual on it ends with the offering refunded. */
    private void altarBroken(ServerLevel level, BlockPos altar) {
        String key = key(level, altar);
        Charge c = charging.remove(key);
        if (c == null) return;
        if (c.task != null) c.task.cancel();
        chargingPlayers.remove(c.player);
        refund(c.player, c.itemId, "refund-broken", byItem.get(c.itemId));
    }

    private void finishCharge(UUID pid, String pname, ServerLevel level, BlockPos altar, String key, BossSpec spec) {
        ServerPlayer player = feather.player(pid);
        if (!altarItem.equals(ArcanaContent.id(level.getBlockState(altar)))) {   // the altar was broken mid-ritual
            refund(pid, spec.itemId(), "refund-broken", spec);
            return;
        }
        Vec3 from = player != null && player.level() == level ? player.position() : Vec3.atCenterOf(altar);
        Loc spot = findSpot(level, altar, spec, from);
        Entity boss = spot == null ? null : spawn(spec, spot);
        if (boss == null) {
            refund(pid, spec.itemId(), "spawn-failed", spec);
            return;
        }
        lightning(level, spot.vec());
        level.sendParticles(spec.particle(), spot.x(), spot.y() + 1, spot.z(), 80, 1.2, 1.2, 1.2, 0.08);
        level.sendParticles(ParticleTypes.EXPLOSION, spot.x(), spot.y() + 1, spot.z(), 3, 0.6, 0.6, 0.6, 0);
        SoundEvent arrival = sound(spec.arrivalSound());
        if (arrival != null) level.playSound(null, spot.x(), spot.y(), spot.z(), arrival, SoundSource.HOSTILE, 2.0f, 1.0f);
        long now = System.currentTimeMillis();
        altarUsed.put(key, now);
        playerUsed.put(pid, now);
        Fight fight = new Fight(boss.getUUID(), spec.mobType(), pid, pname, spec.itemId(), key, spot, now);
        fights.put(boss.getUUID(), fight);
        scale(fight, boss);
        saveData();
        TagResolver[] r = {Placeholder.unparsed("player", pname), boss(spec)};
        switch (announce) {
            case "server" -> level.getServer().getPlayerList().broadcastSystemMessage(Adv.vanilla(msg("summoned", r)), false);
            case "nearby" -> {
                Component c = msg("summoned", r);
                for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(spot.vec()) <= 96 * 96) Adv.send(p, c);
            }
            default -> {
            }
        }
        feather.getLogger().info("{} summoned {} at {}", pname, spec.mobType(), key);
        if (player != null) feather.advance(player, "event", "altar");
    }

    /** Spawns the boss at {@code at}, facing its yaw (its spawn skills run as on a MythicMobs spawn). */
    private static Entity spawn(BossSpec spec, Loc at) {
        EntityType<?> type = bossType(spec.mobType());
        if (type == null) return null;
        Entity e = type.create(at.level(), EntitySpawnReason.TRIGGERED);
        if (e == null) return null;
        e.snapTo(at.x(), at.y(), at.z(), at.yaw(), 0);
        e.setYHeadRot(at.yaw());
        if (e instanceof Mob mob) {
            mob.yBodyRot = at.yaw();
            mob.finalizeSpawn(at.level(), at.level().getCurrentDifficultyAt(at.blockPos()), EntitySpawnReason.TRIGGERED, null);
        }
        return at.level().addFreshEntity(e) ? e : null;
    }

    private static void lightning(ServerLevel level, Vec3 at) {
        LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt == null) return;
        bolt.snapTo(at);
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }

    private static SoundEvent sound(String key) {
        return key == null || key.isBlank() ? null : new Species.Sound(key, 1, 1).event();
    }

    // ------------------------------------------------------------------ party scaling

    /** Players (alive, not spectating) within the leash radius of the fight's arena: the party the boss is scaled for. */
    private List<ServerPlayer> party(Fight f, Entity boss) {
        Loc home = f.home();
        Loc at = home != null ? home : boss == null ? null : Loc.of(boss);
        List<ServerPlayer> out = new ArrayList<>();
        if (at == null) return out;
        double r2 = (double) leashRadius * leashRadius;
        for (ServerPlayer p : at.level().players()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            if (p.position().distanceToSqr(at.vec()) <= r2) out.add(p);
        }
        return out;
    }

    /**
     * Scales the boss for the players around it: max health x (1 + health-per-extra-player x extra players), keeping its
     * health percentage, and the damage it deals x (1 + damage-per-extra-player x extra players). Only ever scales up.
     * Health goes on as one attribute modifier ({@link #SCALE_ID}, removed and re-added, so it never stacks); if the
     * max-health cap stops it short, the rest is made up by reducing the damage the boss takes.
     */
    private void scale(Fight f, Entity entity) {
        if (!scaleEnabled || !(entity instanceof LivingEntity boss) || !boss.isAlive()) return;
        List<ServerPlayer> party = party(f, entity);
        int n = Math.max(1, Math.min(scaleMaxPlayers, party.size()));
        if (n <= f.scaledFor) return;
        boolean first = f.scaledFor == 0;
        double healthMult = 1 + healthPerPlayer * (n - 1);
        AttributeInstance attr = boss.getAttribute(Attributes.MAX_HEALTH);
        if (attr != null) {
            double oldMax = attr.getValue();
            double share = oldMax > 0 ? Math.max(0, Math.min(1, boss.getHealth() / oldMax)) : 1;
            attr.removeModifier(SCALE_ID);
            double unscaled = attr.getValue();
            if (healthMult > 1) {
                attr.addPermanentModifier(new AttributeModifier(SCALE_ID, healthMult - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
            double newMax = attr.getValue();
            boss.setHealth((float) Math.min(newMax, Math.max(0.5, share * newMax)));
            double reached = unscaled > 0 ? newMax / unscaled : 1;
            f.toughness = reached < healthMult - 1e-6 ? healthMult / reached : 1.0;
        }
        f.damageMult = 1 + damagePerPlayer * (n - 1);
        f.scaledFor = n;
        if (n > 1) {
            BossSpec spec = byMob.get(f.mobType);
            TagResolver[] r = {boss(spec), Placeholder.unparsed("players", String.valueOf(n)),
                    Placeholder.unparsed("health", fmt(healthMult)), Placeholder.unparsed("damage", fmt(f.damageMult))};
            String key = first ? "scaled" : "rescaled";
            Set<UUID> told = new HashSet<>();
            for (ServerPlayer p : party) {
                send(p, key, r);
                told.add(p.getUUID());
            }
            ServerPlayer summoner = feather.player(f.summoner);
            if (summoner != null && !told.contains(f.summoner)) send(summoner, key, r);
            feather.getLogger().info("Boss altar fight: {} scaled for {} players (health x{}{}, damage x{}).", f.mobType, n, fmt(healthMult),
                    f.toughness > 1 ? ", x" + fmt(f.toughness) + " of it as damage reduction (max-health cap)" : "", fmt(f.damageMult));
        }
        if (!first) saveData();
    }

    private static String fmt(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private Fight fightOf(Entity e) {
        return e == null || fights.isEmpty() ? null : fights.get(e.getUUID());
    }

    /** Party scaling in damage (FeatherEvents.DAMAGE_AMOUNT). */
    private float scaledDamage(LivingEntity victim, DamageSource source, float amount) {
        if (fights.isEmpty()) return amount;
        Fight hurt = fightOf(victim);
        float out = amount;
        if (hurt != null && hurt.toughness > 1) out = (float) (out / hurt.toughness);
        // the attacker, or the shooter of a projectile
        Fight attacker = fightOf(source.getEntity());
        if (attacker != null && attacker != hurt && attacker.damageMult != 1.0) out = (float) (out * attacker.damageMult);
        return out;
    }

    /** Counts each player's damage on altar bosses, for the boss board. */
    private void bossHurt(LivingEntity boss, DamageSource source, float taken) {
        Fight f = fightOf(boss);
        if (f == null || taken <= 0) return;
        ServerPlayer by = playerBehind(source);
        if (by == null) return;
        f.damage.merge(by.getUUID(), (double) taken, Double::sum);
        f.names.put(by.getUUID(), by.getGameProfile().name());
    }

    /** The player behind a hit: directly, through a projectile, or through their tamed animal. */
    private static ServerPlayer playerBehind(DamageSource source) {
        ServerPlayer p = FeatherEvents.attacker(source);
        if (p != null) return p;
        if (source.getEntity() instanceof OwnableEntity o && o.getOwner() instanceof ServerPlayer owner) return owner;
        return null;
    }

    /** Hands a won fight to the boss board: who fought (with their share of the damage) and how long it took. */
    private void recordKill(Fight f, ServerPlayer killer) {
        Hooks.BossBoard board = feather.service(Hooks.BossBoard.class);
        if (board == null) return;
        Map<UUID, Double> dealt = new LinkedHashMap<>(f.damage);
        Map<UUID, String> names = new HashMap<>(f.names);
        if (dealt.isEmpty() && killer != null) {
            dealt.put(killer.getUUID(), 1.0);
            names.put(killer.getUUID(), killer.getGameProfile().name());
        }
        double total = 0;
        for (double d : dealt.values()) total += d;
        if (total <= 0) return;
        List<Hooks.BossBoard.Hunter> party = new ArrayList<>();
        for (Map.Entry<UUID, Double> d : dealt.entrySet()) {
            party.add(new Hooks.BossBoard.Hunter(d.getKey(), names.getOrDefault(d.getKey(), "?"), d.getValue() / total));
        }
        BossSpec spec = byMob.get(f.mobType);
        try {
            board.recordKill(f.mobType, spec == null ? f.mobType : spec.name(), f.startedAt, System.currentTimeMillis(), party);
        } catch (RuntimeException ex) {
            feather.getLogger().warn("Could not record the boss kill on the boss board", ex);
        }
    }

    // ------------------------------------------------------------------ fights

    /** The boss's entity if it is loaded in any level. */
    private Entity entity(UUID id) {
        MinecraftServer s = Feather.serverOrNull();
        if (s == null) return null;
        for (ServerLevel l : s.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    private void tickFights() {
        if (fights.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Fight f : new ArrayList<>(fights.values())) {
            Loc home = f.home();
            Entity e = entity(f.boss);
            if (now - f.startedAt > maxFightMs) {
                end(f, e, "timeout", refundTimeout);
                continue;
            }
            boolean loaded = e != null && !e.isRemoved();
            if (loaded && e instanceof LivingEntity le && le.isDeadOrDying()) continue;   // the death event ends it
            if (loaded) {
                f.missingSeconds = 0;
                scale(f, e);   // more players arrived: scale up (never down)
                if (home != null && (e.level() != home.level() || e.position().distanceToSqr(home.vec()) > (double) leashRadius * leashRadius)) {
                    ((ServerLevel) e.level()).sendParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY() + 1, e.getZ(), 40, 0.6, 1, 0.6, 0.05);
                    if (e.level() == home.level()) e.teleportTo(home.x(), home.y(), home.z());
                    else e.teleport(new net.minecraft.world.level.portal.TeleportTransition(home.level(), home.vec(), Vec3.ZERO, e.getYRot(), e.getXRot(),
                            net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
                    home.level().sendParticles(ParticleTypes.REVERSE_PORTAL, home.x(), home.y() + 1, home.z(), 40, 0.6, 1, 0.6, 0.05);
                }
            } else if (home != null && home.level().hasChunk(home.blockX() >> 4, home.blockZ() >> 4) && playerNear(home, 32)) {
                // the arena is loaded and watched, but the boss is gone without dying
                if (++f.missingSeconds >= 15) {
                    end(f, null, "vanished", refundVanish);
                    continue;
                }
            } else {
                f.missingSeconds = 0;
            }
            Loc probe = loaded ? Loc.of(e) : home;
            if (probe != null && playerNear(probe, abandonRadius)) f.lonelySeconds = 0;
            else if (++f.lonelySeconds >= abandonSeconds) end(f, e, "abandoned", refundAbandon);
        }
    }

    private static boolean playerNear(Loc at, double radius) {
        double r2 = radius * radius;
        for (ServerPlayer p : at.level().players()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            if (p.position().distanceToSqr(at.vec()) <= r2) return true;
        }
        return false;
    }

    /** Ends a fight that did not end in the boss's death: the boss leaves, optionally refunding the offering. */
    private void end(Fight f, Entity e, String reason, boolean refund) {
        fights.remove(f.boss);
        BossSpec spec = byMob.get(f.mobType);
        Set<UUID> told = new HashSet<>();
        if (e != null && !e.isRemoved()) {
            ServerLevel l = (ServerLevel) e.level();
            l.sendParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 1, e.getZ(), 60, 1, 1, 1, 0.05);
            SoundEvent leave = sound("entity.illusioner.mirror_move");
            if (leave != null) l.playSound(null, e.getX(), e.getY() + 1, e.getZ(), leave, SoundSource.HOSTILE, 1.5f, 0.6f);
            for (ServerPlayer p : l.players()) {
                if (p.distanceToSqr(e) <= 64 * 64) {
                    send(p, reason, boss(spec));
                    told.add(p.getUUID());
                }
            }
            // removed without dying: no death skills, no drops, no mount
            e.discard();
        } else {
            // not loaded (or already gone): if it ever loads again, it is removed on sight
            orphans.add(f.boss);
            while (orphans.size() > 200) orphans.remove(orphans.iterator().next());
        }
        ServerPlayer summoner = feather.player(f.summoner);
        if (summoner != null && !told.contains(f.summoner)) send(summoner, reason, boss(spec));
        if (refund && !f.altarKey.startsWith(SUMMON_PREFIX)) refund(f.summoner, f.itemId, "refund", spec);
        feather.getLogger().info("Boss altar fight ended ({}{}): {} summoned by {}", reason, refund ? ", refunded" : "", f.mobType, f.summonerName);
        saveData();
    }

    private void refund(UUID player, String itemId, String messageKey, BossSpec spec) {
        ServerPlayer p = feather.player(player);
        if (p == null) {
            pendingRefunds.computeIfAbsent(player, k -> new ArrayList<>()).add(itemId);
            saveData();
            return;
        }
        give(p, itemId);
        send(p, messageKey, boss(spec), itemName(itemId));
    }

    private static void give(ServerPlayer p, String itemId) {
        var stack = ArcanaContent.stack(itemId, 1);
        if (stack.isEmpty()) return;
        if (!p.getInventory().add(stack) && !stack.isEmpty()) p.spawnAtLocation((ServerLevel) p.level(), stack);
    }

    private void join(ServerPlayer joined) {
        UUID id = joined.getUUID();
        List<String> items = pendingRefunds.remove(id);
        if (items == null || items.isEmpty()) return;
        Tasks.later(40L, () -> {
            ServerPlayer p = feather.player(id);
            if (p == null) {
                pendingRefunds.computeIfAbsent(id, k -> new ArrayList<>()).addAll(items);
                return;
            }
            for (String item : items) {
                give(p, item);
                send(p, "refund", boss(null), itemName(item));
            }
            saveData();
        });
    }

    private void bossDied(LivingEntity e, DamageSource source) {
        Fight f = fights.remove(e.getUUID());
        if (f == null) return;
        feather.getLogger().info("Boss altar fight won: {} summoned by {}", f.mobType, f.summonerName);
        ServerPlayer killer = playerBehind(source);
        recordKill(f, killer);
        Hooks.Quests quests = feather.service(Hooks.Quests.class);
        if (quests != null) {
            try {
                quests.bossDefeated(f.mobType, f.damage.isEmpty() && killer != null ? Map.of(killer.getUUID(), 1.0) : new LinkedHashMap<>(f.damage));
            } catch (RuntimeException ex) {
                feather.getLogger().warn("Quests could not count the boss kill", ex);
            }
        }
        saveData();
    }

    private void orphanLoaded(Entity entity) {
        UUID id = entity.getUUID();
        Tasks.later(1L, () -> {
            orphans.remove(id);
            if (!entity.isRemoved()) entity.discard();
            saveData();
        });
    }

    // ------------------------------------------------------------------ data

    /**
     * Reads altars-data.yml. A file that can't be parsed is never saved over: it is logged, copied to
     * altars-data.yml.broken-&lt;time&gt;, and the .bak is loaded instead; with no readable backup either, saving stays
     * off until the file is fixed and /feather reload reads it again.
     */
    private void loadData() {
        Yaml d = SafeYaml.read(dataFile, feather.getLogger(), "altar cooldowns, fights and refunds");
        if (d == null) {
            dataUnreadable = true;
            return;
        }
        if (dataUnreadable) feather.getLogger().info("altars-data.yml is readable again; altar data is saved again.");
        dataUnreadable = false;
        // Everything is saved as it changes, so the file holds what memory holds (plus an admin's edits).
        altarUsed.clear();
        playerUsed.clear();
        orphans.clear();
        pendingRefunds.clear();
        Yaml a = d.getConfigurationSection("altar-cooldowns");
        if (a != null) for (String k : a.getKeys(false)) altarUsed.put(k.replace('|', '.'), a.getLong(k));
        Yaml p = d.getConfigurationSection("player-cooldowns");
        if (p != null) {
            for (String k : p.getKeys(false)) {
                try {
                    playerUsed.put(UUID.fromString(k), p.getLong(k));
                } catch (IllegalArgumentException ignored) {
                    // not a player id
                }
            }
        }
        Yaml fs = d.getConfigurationSection("fights");
        if (fs != null) {
            for (String k : fs.getKeys(false)) {
                Yaml s = fs.getConfigurationSection(k);
                Fight f = s == null ? null : Fight.load(s);
                if (f != null) fights.putIfAbsent(f.boss, f);   // a reload keeps fights already tracked in memory
            }
        }
        for (String s : d.getStringList("orphans")) {
            try {
                orphans.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // not an entity id
            }
        }
        Yaml r = d.getConfigurationSection("pending-refunds");
        if (r != null) {
            for (String k : r.getKeys(false)) {
                try {
                    pendingRefunds.put(UUID.fromString(k), new ArrayList<>(r.getStringList(k)));
                } catch (IllegalArgumentException ignored) {
                    // not a player id
                }
            }
        }
    }

    /** A change: marks the data dirty and writes a snapshot on the data thread. */
    private void saveData() {
        dataDirty = true;
        saveIfDirtyAsync();
    }

    /** Snapshot now (server thread), dumped and written on the data thread; dirty again if that write fails. */
    private void saveIfDirtyAsync() {
        if (dataUnreadable || !dataDirty) return;
        dataDirty = false;
        dataWrites.incrementAndGet();
        SafeYaml.writeAsync(dataFile, null, dataSnapshot(), feather.getLogger()).thenAccept(ok -> {
            dataWrites.decrementAndGet();
            if (!ok) Tasks.sync(() -> dataDirty = true);
        });
    }

    /** Writes everything now and waits for it (shutdown); false when it couldn't be written. */
    private boolean save() {
        if (dataUnreadable) return false;
        boolean ok = SafeYaml.writeSync(dataFile, null, dataSnapshot(), feather.getLogger());
        if (ok) dataDirty = false;
        return ok;
    }

    /** altars.yml's tree as plain maps (server thread; the refund lists are copied by the snapshot). */
    private Object dataSnapshot() {
        Yaml d = new Yaml();
        long now = System.currentTimeMillis();
        // keep only cooldowns that still matter (and keep the file small)
        altarUsed.values().removeIf(t -> now - t > altarCooldownMs);
        playerUsed.values().removeIf(t -> now - t > playerCooldownMs);
        for (Map.Entry<String, Long> e : altarUsed.entrySet()) d.set("altar-cooldowns." + e.getKey().replace('.', '|'), e.getValue());
        for (Map.Entry<UUID, Long> e : playerUsed.entrySet()) d.set("player-cooldowns." + e.getKey(), e.getValue());
        int i = 0;
        for (Fight f : fights.values()) f.save(d.createSection("fights." + (i++)));
        d.set("orphans", orphans.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, List<String>> e : pendingRefunds.entrySet()) d.set("pending-refunds." + e.getKey(), e.getValue());
        return d.snapshot();
    }

    // ------------------------------------------------------------------ /altars (admin)

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (!sender.hasPermission("feather.altars.admin")) {
            send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        switch (sub) {
            case "list" -> {
                send(sender, "admin-list-header", arg("fights", fights.size()), arg("altars", altarUsed.size()), arg("players", playerUsed.size()));
                int i = 1;
                for (Fight f : fights.values()) {
                    Entity e = entity(f.boss);
                    String where = e != null && !e.isRemoved()
                            ? Worlds.name(e.level()) + " " + e.getBlockX() + "," + e.getBlockY() + "," + e.getBlockZ()
                            : "unloaded (altar " + f.altarKey + ")";
                    String scaled = f.scaledFor > 1 ? ", scaled for " + f.scaledFor + " (damage x" + fmt(f.damageMult)
                            + (f.toughness > 1 ? ", toughness x" + fmt(f.toughness) : "") + ")" : "";
                    send(sender, "admin-list-row", arg("n", i++), arg("boss", f.mobType), arg("player", f.summonerName),
                            arg("ago", time(now - f.startedAt)), arg("where", where), arg("scaled", scaled),
                            arg("staff", f.altarKey.startsWith(SUMMON_PREFIX) ? config.getString("messages.admin-list-staff", "") : ""));
                }
                send(sender, "admin-usage", arg("label", label));
            }
            case "summon" -> {
                if (args.length < 2) {
                    send(sender, "admin-summon-usage", arg("label", label));
                    return true;
                }
                BossSpec spec = null;
                for (BossSpec s : byMob.values()) if (s.mobType().equalsIgnoreCase(args[1])) spec = s;
                ServerPlayer at = args.length >= 3 ? feather.findPlayer(sender, args[2]) : sender.player();
                if (spec == null || at == null) {
                    if (spec == null) send(sender, "admin-summon-unknown", arg("boss", args[1]), arg("bosses", byMob.keySet()));
                    else send(sender, "admin-summon-no-player", arg("label", label));
                    return true;
                }
                if (bossType(spec.mobType()) == null) {
                    send(sender, "unknown-boss", boss(spec));
                    return true;
                }
                Entity boss = summonFor(at, spec);
                send(sender, boss == null ? "admin-summon-no-room" : "admin-summoned", arg("boss", spec.mobType()), arg("player", at.getGameProfile().name()));
            }
            case "reset" -> {
                if (args.length < 2) {
                    send(sender, "admin-reset-usage", arg("label", label));
                    return true;
                }
                String who = args[1];
                ServerPlayer self = sender.player();
                if (who.equalsIgnoreCase("all")) {
                    altarUsed.clear();
                    playerUsed.clear();
                    send(sender, "admin-reset-all");
                } else if (who.equalsIgnoreCase("here") && self != null) {
                    int n = 0;
                    for (String k : new ArrayList<>(altarUsed.keySet())) {
                        Loc l = parseKey(k);
                        if (l != null && l.level() == self.level() && l.vec().distanceToSqr(self.position()) <= 16 * 16) {
                            altarUsed.remove(k);
                            n++;
                        }
                    }
                    send(sender, "admin-reset-here", arg("count", n));
                } else {
                    var op = feather.findOfflinePlayer(sender, who);
                    if (op == null) {
                        feather.notFound(sender, who);
                        return true;
                    }
                    playerUsed.remove(op.id());
                    send(sender, "admin-reset-player", arg("player", who));
                }
                saveData();
            }
            case "end" -> {
                if (args.length < 2) {
                    send(sender, "admin-end-usage", arg("label", label));
                    return true;
                }
                boolean refund = args.length >= 3 && args[2].equalsIgnoreCase("refund");
                List<Fight> list = new ArrayList<>(fights.values());
                List<Fight> pick = new ArrayList<>();
                if (args[1].equalsIgnoreCase("all")) pick.addAll(list);
                else {
                    try {
                        int n = Integer.parseInt(args[1]);
                        if (n >= 1 && n <= list.size()) pick.add(list.get(n - 1));
                    } catch (NumberFormatException ignored) {
                        // not a number: nothing picked
                    }
                }
                for (Fight f : pick) end(f, entity(f.boss), "ended", refund);
                send(sender, "admin-ended", arg("count", pick.size()), arg("refund", refund ? config.getString("messages.admin-ended-refund", "") : ""));
            }
            default -> send(sender, "admin-unknown", arg("label", label));
        }
        return true;
    }

    /** Fights started with /altars summon (events, tests): tracked like altar fights, but never refund an item. */
    private static final String SUMMON_PREFIX = "summon:";

    /** /altars summon: the boss appears in front of the player, without altar, offering or cooldowns. */
    private Entity summonFor(ServerPlayer player, BossSpec spec) {
        ServerLevel level = (ServerLevel) player.level();
        Vec3 facing = player.getLookAngle().multiply(1, 0, 1);
        if (facing.lengthSqr() < 1e-4) facing = new Vec3(0, 0, 1);
        // findSpot puts the boss on the far side of the "altar" from "from": the player's block, seen from behind them
        Vec3 from = player.position().subtract(facing.normalize().scale(2));
        BlockPos block = player.blockPosition();
        Loc spot = findSpot(level, block, spec, from);
        Entity boss = spot == null ? null : spawn(spec, spot);
        if (boss == null) return null;
        lightning(level, spot.vec());
        SoundEvent arrival = sound(spec.arrivalSound());
        if (arrival != null) level.playSound(null, spot.x(), spot.y(), spot.z(), arrival, SoundSource.HOSTILE, 2.0f, 1.0f);
        Fight fight = new Fight(boss.getUUID(), spec.mobType(), player.getUUID(), player.getGameProfile().name(), spec.itemId(),
                SUMMON_PREFIX + key(level, block), spot, System.currentTimeMillis());
        fights.put(boss.getUUID(), fight);
        scale(fight, boss);
        saveData();
        feather.getLogger().info("Staff summoned {} for {} at {}", spec.mobType(), player.getGameProfile().name(), key(level, block));
        return boss;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (!sender.hasPermission("feather.altars.admin")) return List.of();
        List<String> opts = switch (args.length) {
            case 1 -> List.of("list", "reset", "end", "summon");
            case 2 -> args[0].equalsIgnoreCase("reset")
                    ? concat(List.of("here", "all"), feather.completePlayers(sender, ""))
                    : args[0].equalsIgnoreCase("end") ? List.of("all", "1")
                    : args[0].equalsIgnoreCase("summon") ? new ArrayList<>(byMob.keySet()) : List.of();
            case 3 -> args[0].equalsIgnoreCase("end") ? List.of("refund")
                    : args[0].equalsIgnoreCase("summon") ? feather.completePlayers(sender, "") : List.of();
            default -> List.of();
        };
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return opts.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
