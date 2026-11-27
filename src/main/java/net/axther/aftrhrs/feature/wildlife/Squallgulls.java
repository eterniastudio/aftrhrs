package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.axther.aftrhrs.core.ClaimAction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Squallgulls (wildlife SPEC section 1/5): every {@code tick.gull-seconds} a gull without a fish snatches one raw fish
 * from the hotbar of a player within {@code squallgull.steal.radius} (one per {@code cooldown-seconds} per gull); hitting
 * it makes it drop the fish (dying too). Fed a raw fish, it follows the feeder (circling above their boat) for
 * {@code follow.minutes} and every {@code dive.every-seconds} dives at a patch of open water that becomes a
 * {@link WildlifeHotspots fishing hotspot}. State lives in the gull's Pdc; only gulls that follow someone are steered.
 */
final class Squallgulls {
    static final String HELD = "wild_held_fish", STEAL = "wild_steal_at", FOLLOW = "wild_follow", FOLLOW_UNTIL = "wild_follow_until",
            DIVE = "wild_dive_at", NO_GRAVITY = "wild_nogravity";

    private final WildlifeModule m;
    private final Set<UUID> following = new HashSet<>();

    private double stealRadius = 3;
    private long stealCooldownMs = 60_000;
    private Match stealable = Match.NONE, food = Match.NONE;
    private long followMs = 300_000;
    private double height = 4, circle = 3, teleport = 24, speed = 0.6;
    private long diveMs = 60_000;
    private double diveMin = 6, diveMax = 14;
    private boolean needsBoat = true;
    private long hotspotMs = 60_000;
    private double hotspotRadius = 4;
    private boolean particles = true;

    Squallgulls(WildlifeModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        stealRadius = Math.max(1, cfg.getDouble("squallgull.steal.radius", 3));
        stealCooldownMs = WildSpecies.seconds(cfg.getDouble("squallgull.steal.cooldown-seconds", 60));
        stealable = new Match(cfg.getStringList("squallgull.steal.fish"));
        food = new Match(cfg.getStringList("squallgull.follow.food"));
        followMs = WildSpecies.minutes(cfg.getDouble("squallgull.follow.minutes", 5));
        height = cfg.getDouble("squallgull.follow.height", 4);
        circle = Math.max(0, cfg.getDouble("squallgull.follow.circle-radius", 3));
        teleport = Math.max(8, cfg.getDouble("squallgull.follow.teleport-distance", 24));
        speed = Math.max(0.05, cfg.getDouble("squallgull.follow.speed", 0.6));
        diveMs = WildSpecies.seconds(Math.max(5, cfg.getDouble("squallgull.dive.every-seconds", 60)));
        diveMin = Math.max(1, cfg.getDouble("squallgull.dive.min-distance", 6));
        diveMax = Math.max(diveMin, cfg.getDouble("squallgull.dive.max-distance", 14));
        needsBoat = cfg.getBoolean("squallgull.dive.needs-boat", true);
        hotspotMs = WildSpecies.seconds(Math.max(5, cfg.getDouble("hotspots.seconds", 60)));
        hotspotRadius = Math.max(1, cfg.getDouble("hotspots.radius", 4));
        particles = cfg.getBoolean("hotspots.particles", true);
        WildlifeHotspots.configure(cfg.getDouble("hotspots.bite-speed-multiplier", 1.25), cfg.getDouble("hotspots.rare-weight-multiplier", 1.3));
    }

    boolean holding(Entity gull) {
        return Pdc.has(gull, WildlifeModule.NS + HELD);
    }

    /** The held fish: saved as item NBT (Paper kept serialized item bytes; those are read once too). */
    private ItemStack held(Entity gull) {
        CompoundTag pdc = gull.getAttached(Pdc.DATA);
        Tag t = pdc == null ? null : pdc.get(WildlifeModule.NS + HELD);
        if (t == null) return null;
        try {
            CompoundTag item = t instanceof CompoundTag c ? c
                    : t instanceof ByteArrayTag b ? NbtIo.readCompressed(new ByteArrayInputStream(b.getAsByteArray()), NbtAccounter.unlimitedHeap()) : null;
            if (item == null) return new ItemStack(Items.COD);
            item = item.copy();
            item.remove("DataVersion");
            return ItemStack.CODEC.parse(gull.registryAccess().createSerializationContext(NbtOps.INSTANCE), item).result().orElse(new ItemStack(Items.COD));
        } catch (Exception e) {
            return new ItemStack(Items.COD);
        }
    }

    private void setHeld(Entity gull, ItemStack fish) {
        if (fish == null) {
            Pdc.remove(gull, WildlifeModule.NS + HELD);
            return;
        }
        Tag t = ItemStack.CODEC.encodeStart(gull.registryAccess().createSerializationContext(NbtOps.INSTANCE), fish).result().orElse(null);
        if (t != null) Pdc.putTag(gull, WildlifeModule.NS + HELD, t);
    }

    void tracked(Entity e, String type) {
        if (!type.equals(m.gullId)) return;
        if (m.getLong(e, FOLLOW_UNTIL) > System.currentTimeMillis()) following.add(e.getUUID());
        else if (m.getLong(e, FOLLOW_UNTIL) > 0 || m.getLong(e, NO_GRAVITY) > 0) stop(e);
    }

    // ------------------------------------------------------------------ theft

    /** Every tick.gull-seconds: thefts and hotspot splashes. */
    void scan() {
        long now = System.currentTimeMillis();
        if (particles) {
            for (WildlifeHotspots.Spot s : WildlifeHotspots.active()) {
                ServerLevel w = level(s.level());
                if (w == null) continue;
                BlockPos bp = BlockPos.containing(s.x(), s.y(), s.z());
                if (!w.isLoaded(bp) || w.getNearestPlayer(s.x(), s.y(), s.z(), 32, false) == null) continue;
                w.sendParticles(ParticleTypes.FISHING, s.x(), s.y(), s.z(), 10, s.radius() / 2, 0.05, s.radius() / 2, 0.02);
                w.sendParticles(ParticleTypes.SPLASH, s.x(), s.y(), s.z(), 6, s.radius() / 3, 0.1, s.radius() / 3, 0);
            }
        }
        if (stealable.isEmpty()) return;
        for (Entity g : m.trackedEntities()) {
            if (!m.gullId.equals(m.tracked.get(g.getUUID()))) continue;
            if (!m.enabledWorld(g.level()) || holding(g) || now < m.getLong(g, STEAL)) continue;
            String follows = m.getString(g, FOLLOW);
            for (ServerPlayer p : ((ServerLevel) g.level()).players()) {
                if (p.isCreative() || p.isSpectator() || p.distanceToSqr(g) > stealRadius * stealRadius) continue;
                if (p.getUUID().toString().equals(follows)) continue;
                if (steal(g, p, now)) break;
            }
        }
    }

    static ServerLevel level(String key) {
        for (ServerLevel l : Feather.server().getAllLevels()) if (WildlifeHotspots.key(l).equals(key)) return l;
        return null;
    }

    private boolean steal(Entity gull, ServerPlayer p, long now) {
        Inventory inv = p.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty() || !stealable.matches(s)) continue;
            ItemStack one = s.copyWithCount(1);
            s.shrink(1);
            inv.setItem(slot, s.isEmpty() ? ItemStack.EMPTY : s);
            setHeld(gull, one);
            m.setLong(gull, STEAL, now + stealCooldownMs);
            Harvests.hook(gull, "HoldFish");
            m.soundAt((ServerLevel) gull.level(), gull.position(), "gull-steal");
            m.bar(p, "gull.stolen", WildlifeModule.tag("item", one.getHoverName().getString()));
            return true;
        }
        return false;
    }

    /** A player hit it: it lets go of the fish. */
    void hit(Entity gull) {
        ItemStack fish = held(gull);
        if (fish == null) return;
        setHeld(gull, null);
        Harvests.hook(gull, "DropFish");
        WildlifeModule.drop((ServerLevel) gull.level(), gull.position(), fish);
    }

    /** It died: the fish drops with it. */
    void died(Entity gull) {
        ItemStack fish = held(gull);
        if (fish == null) return;
        setHeld(gull, null);
        WildlifeModule.drop((ServerLevel) gull.level(), gull.position(), fish);
    }

    // ------------------------------------------------------------------ feeding and following

    boolean interact(ServerPlayer p, Entity gull, ItemStack held, InteractionHand hand) {
        if (food.isEmpty() || !food.matches(held)) return false;
        // a gull in someone else's claim is theirs to feed (the food stays in hand)
        if (!m.mayUse(p, (ServerLevel) gull.level(), gull.blockPosition(), ClaimAction.BUILD)) return true;
        long now = System.currentTimeMillis();
        WildlifeModule.useOne(p, hand, null);
        m.setString(gull, FOLLOW, p.getUUID().toString());
        m.setLong(gull, FOLLOW_UNTIL, now + followMs);
        m.setLong(gull, DIVE, now + diveMs);
        following.add(gull.getUUID());
        ((ServerLevel) gull.level()).sendParticles(ParticleTypes.HEART, gull.getX(), gull.getY() + 0.5, gull.getZ(), 3, 0.2, 0.2, 0.2, 0);
        m.sound(p, "gull-fed");
        m.bar(p, "gull.fed", WildlifeModule.tag("time", WildlifeModule.time(followMs)));
        m.bestiary.harvested(p, m.gullId);
        return true;
    }

    /** Every tick.follow-ticks: steer the gulls that follow someone. */
    void steer() {
        if (following.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(following)) {
            Entity g = m.entity(id);
            if (g == null) {
                following.remove(id);
                continue;
            }
            String who = m.getString(g, FOLLOW);
            ServerPlayer p = null;
            try {
                p = who == null ? null : Feather.get().player(UUID.fromString(who));
            } catch (IllegalArgumentException ignored) {
            }
            if (p == null || now >= m.getLong(g, FOLLOW_UNTIL) || p.level() != g.level()) {
                stop(g);
                continue;
            }
            Entity anchor = p.getVehicle() != null ? p.getVehicle() : p;
            double angle = (now % 12_000) / 12_000.0 * Math.PI * 2;
            Vec3 target = anchor.position().add(Math.cos(angle) * circle, height, Math.sin(angle) * circle);
            Vec3 at = g.position();
            double dist = at.distanceTo(target);
            if (dist > teleport) {
                g.teleportTo(target.x, target.y, target.z);
                continue;
            }
            if (!g.isNoGravity()) {
                // turned back on in stop(); the mark is in the Pdc so it survives a chunk unload or restart
                g.setNoGravity(true);
                m.setLong(g, NO_GRAVITY, 1);
            }
            if (g instanceof Mob mob) mob.getNavigation().stop();
            Vec3 dir = target.subtract(at);
            if (dist > 0.6) {
                g.setDeltaMovement(dir.normalize().scale(Math.min(speed, dist * 0.3)));
                float yaw = (float) (Mth.atan2(dir.z, dir.x) * (180F / Math.PI)) - 90F;
                g.setYRot(yaw);
                g.setYHeadRot(yaw);
            } else {
                g.setDeltaMovement(Vec3.ZERO);
            }
            g.needsSync = true;
            if (now >= m.getLong(g, DIVE) && (!needsBoat || p.getVehicle() instanceof AbstractBoat)) {
                if (!dive(g, p, now)) m.setLong(g, DIVE, now + 10_000);
            }
        }
    }

    /** Dives at a patch of open water near the player; it becomes a hotspot. False when there is no open water near. */
    boolean dive(Entity gull, ServerPlayer p, long now) {
        ServerLevel w = (ServerLevel) p.level();
        Vec3 c = p.position();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vec3 spot = null;
        for (int i = 0; i < 10 && spot == null; i++) {
            double a = r.nextDouble(Math.PI * 2), d = diveMin + r.nextDouble() * (diveMax - diveMin + 0.001);
            int x = (int) Math.floor(c.x + Math.cos(a) * d), z = (int) Math.floor(c.z + Math.sin(a) * d);
            if (!w.hasChunkAt(x, z)) continue;
            int topY = w.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            BlockPos top = new BlockPos(x, topY, z);
            if (w.getBlockState(top).is(Blocks.WATER) && w.getBlockState(top.above()).isAir()) spot = new Vec3(x + 0.5, topY + 1, z + 0.5);
        }
        if (spot == null) return false;
        m.setLong(gull, DIVE, now + diveMs);
        WildlifeHotspots.add(w, spot, hotspotRadius, now + hotspotMs);
        Harvests.hook(gull, "Dive");
        // the dive skill has no target here: aim it at the spot
        Vec3 d = spot.subtract(gull.position());
        if (d.lengthSqr() > 1.0E-4) {
            gull.setDeltaMovement(d.normalize().scale(1.1));
            gull.needsSync = true;
        }
        w.sendParticles(ParticleTypes.SPLASH, spot.x, spot.y, spot.z, 30, 0.6, 0.1, 0.6, 0.1);
        m.soundAt(w, spot, "gull-dive");
        m.bar(p, "gull.dive");
        return true;
    }

    private void stop(Entity g) {
        following.remove(g.getUUID());
        if (m.getLong(g, NO_GRAVITY) > 0) {
            g.setNoGravity(false);
            m.setLong(g, NO_GRAVITY, 0);
        }
        m.setString(g, FOLLOW, null);
        m.setLong(g, FOLLOW_UNTIL, 0);
        m.setLong(g, DIVE, 0);
    }

    void forget(UUID id) {
        following.remove(id);
    }

    void clear() {
        following.clear();
    }

    int followingCount() {
        return following.size();
    }
}
