package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.migrate.Legacy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The police: the native GeckoLib officer ({@code aftrhrs:police}, the ported MythicMobs {@code ArcanaPolice}; the
 * {@code mythic-mob} setting still names it and maps through {@link Legacy#mythicType}), otherwise a vanilla stand-in.
 *
 * <p>The stand-in is a <b>wandering trader</b>: drawn with the villager model, its robe already police blue, not a
 * monster (so it also works on peaceful). Its trading, wandering and fleeing goals are taken away (it keeps swimming)
 * and it gets one goal: path to the robber and hit them. It carries an iron sword, is named "Police Officer", never
 * trades, never despawns by itself and drops nothing.
 *
 * <p>Every officer carries {@code feather:bank_police} (the robbery id) in its {@link Pdc}, and is removed when the
 * robbery ends, when it is far from the robber, and whenever one loads without a robbery to belong to (so officers
 * left by a crash, or Paper-era officers in a migrated world, never linger). Officers only ever target and hurt the
 * robber; anyone may hurt them.
 */
final class Police {
    static final String TAG = "feather:bank_police";

    private final Robberies robberies;

    String mythicMob = "ArcanaPolice";
    String name = "<#5B8CFF><b>Police Officer";
    double health = 24;
    double damage = 4;
    double speed = 0.33;
    double reach = 2.4;
    int attackCooldown = 20;
    Item heldItem = Items.IRON_SWORD;

    Police(Robberies robberies) {
        this.robberies = robberies;
    }

    void reload(Yaml c) {
        if (c == null) return;
        mythicMob = c.getString("mythic-mob", "ArcanaPolice");
        name = c.getString("fallback.name", name);
        health = Math.max(1, c.getDouble("fallback.health", 24));
        damage = Math.max(0, c.getDouble("fallback.damage", 4));
        speed = Math.max(0.05, Math.min(1.0, c.getDouble("fallback.speed", 0.33)));
        reach = Math.max(1, Math.min(6, c.getDouble("fallback.reach", 2.4)));
        attackCooldown = Math.max(5, c.getInt("fallback.attack-cooldown-ticks", 20));
        String held = c.getString("fallback.held-item", "iron_sword");
        Identifier id = held == null ? null : Identifier.tryParse(held.toLowerCase(Locale.ROOT));
        Item m = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        heldItem = m == null || m == Items.AIR ? null : m;
    }

    void enable() {
        sweep(null);
    }

    static boolean isPolice(Entity e) {
        return e != null && Pdc.has(e, TAG);
    }

    /** Which robbery spawned it ("" if unknown). */
    private static String robberyOf(Entity e) {
        String s = Pdc.getString(e, TAG);
        return s == null ? "" : s;
    }

    /** Removes every officer in loaded levels that doesn't belong to {@code keep} (null: all of them). */
    void sweep(Robberies.Robbery keep) {
        var server = Feather.serverOrNull();
        if (server == null) return;
        for (ServerLevel w : server.getAllLevels()) {
            List<Entity> found = new ArrayList<>();
            for (Entity e : w.getAllEntities()) {
                if (isPolice(e) && (keep == null || !keep.id.equals(robberyOf(e)))) found.add(e);
            }
            for (Entity e : found) remove(e);
        }
    }

    static void remove(Entity e) {
        if (e != null && !e.isRemoved()) e.discard();
    }

    private static Entity entity(UUID id) {
        var server = Feather.serverOrNull();
        if (server == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    void removeAll(Robberies.Robbery r) {
        for (UUID id : new ArrayList<>(r.police)) remove(entity(id));
        r.police.clear();
        sweep(null);
    }

    /** The native officer type, or empty while it isn't registered. */
    Optional<EntityType<?>> nativeType() {
        if (mythicMob == null || mythicMob.isBlank()) return Optional.empty();
        return Legacy.mythicType(mythicMob);
    }

    String kind() {
        return nativeType().map(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()).orElse("wandering trader stand-in");
    }

    /** Spawns one officer; null if there was no room. */
    Entity spawn(Robberies.Robbery r, Loc at) {
        ServerLevel level = at.level();
        Entity e = null;
        Optional<EntityType<?>> type = nativeType();
        // The native officer is a monster underneath, which the game deletes on Peaceful: use the stand-in there.
        if (type.isPresent() && level.getDifficulty() != Difficulty.PEACEFUL) {
            try {
                e = type.get().create(level, EntitySpawnReason.MOB_SUMMONED);
            } catch (RuntimeException ex) {
                e = null;
            }
            if (e != null) {
                e.snapTo(at.x(), at.y(), at.z(), at.yaw(), 0f);
                if (e instanceof Mob mob) {
                    mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at.vec())), EntitySpawnReason.MOB_SUMMONED, null);
                    mob.setCanPickUpLoot(false);
                    for (EquipmentSlot s : EquipmentSlot.values()) mob.setDropChance(s, 0f);
                }
                Pdc.putString(e, TAG, r.id);
                if (!level.addFreshEntity(e)) e = null;
            }
        }
        if (e == null) e = spawnFallback(r, level, at);
        if (e != null) r.police.add(e.getUUID());
        return e;
    }

    private Entity spawnFallback(Robberies.Robbery r, ServerLevel level, Loc at) {
        WanderingTrader t = net.minecraft.world.entity.EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (t == null) return null;
        t.snapTo(at.x(), at.y(), at.z(), at.yaw(), 0f);
        Pdc.putString(t, TAG, r.id);
        t.setDespawnDelay(0);
        t.setCanPickUpLoot(false);
        t.getOffers().clear();
        t.setCustomName(Adv.vanilla(robberies.bank.text().parse(name)));
        t.setCustomNameVisible(true);
        AttributeInstance hp = t.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(health);
        t.setHealth((float) health);
        AttributeInstance sp = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(speed);
        AttributeInstance follow = t.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) follow.setBaseValue(64);
        if (heldItem != null) t.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(heldItem));
        for (EquipmentSlot s : EquipmentSlot.values()) t.setDropChance(s, 0f);
        t.goalSelector.removeAllGoals(g -> !(g instanceof FloatGoal));
        t.goalSelector.addGoal(1, new Chase(t));
        return level.addFreshEntity(t) ? t : null;
    }

    /** Points every officer at the robber, drops the ones that are gone or far away. */
    void tick(Robberies.Robbery r, ServerPlayer robber, double despawnDistance) {
        for (UUID id : new ArrayList<>(r.police)) {
            Entity e = entity(id);
            if (e == null || e.isRemoved() || !e.isAlive()) {
                r.police.remove(id);
                continue;
            }
            if (e.level() != robber.level() || e.distanceToSqr(robber) > despawnDistance * despawnDistance) {
                remove(e);
                r.police.remove(id);
                continue;
            }
            if (e instanceof Mob m && m.getTarget() != robber) m.setTarget(robber);
        }
    }

    /** Officers within {@code radius} of the robber. */
    int near(Robberies.Robbery r, ServerPlayer robber, double radius) {
        int n = 0;
        for (UUID id : r.police) {
            Entity e = entity(id);
            if (e != null && e.isAlive() && e.level() == robber.level() && e.distanceToSqr(robber) <= radius * radius) n++;
        }
        return n;
    }

    /** A free spot on solid ground {@code min}..{@code max} blocks from {@code center} in a loaded chunk, or null. */
    static Loc spot(Loc center, double min, double max) {
        ServerLevel w = center.level();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = Minigames.RANDOM.nextDouble() * Math.PI * 2;
            double dist = min + Minigames.RANDOM.nextDouble() * Math.max(0, max - min);
            int x = (int) Math.floor(center.x() + Math.cos(angle) * dist);
            int z = (int) Math.floor(center.z() + Math.sin(angle) * dist);
            if (!w.hasChunk(x >> 4, z >> 4)) continue;
            int cy = (int) Math.floor(center.y());
            for (int i = 0; i <= 16; i++) {
                int y = cy + (i % 2 == 0 ? i / 2 : -(i + 1) / 2);
                if (y <= w.getMinY() || y >= w.getMaxY() - 2) continue;
                BlockPos feet = new BlockPos(x, y, z);
                if (passable(w, feet) && passable(w, feet.above())) {
                    BlockPos below = feet.below();
                    BlockState floor = w.getBlockState(below);
                    if (!floor.getCollisionShape(w, below).isEmpty() && floor.getFluidState().isEmpty()
                            && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CACTUS)) {
                        return new Loc(w, x + 0.5, y, z + 0.5, (float) Math.toDegrees(angle) + 90f, 0f);
                    }
                }
            }
        }
        return null;
    }

    private static boolean passable(ServerLevel w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        return s.getCollisionShape(w, pos).isEmpty() && s.getFluidState().isEmpty();
    }

    /** The stand-in's one goal: run to the robber and hit them. */
    private final class Chase extends Goal {
        private final Mob mob;
        private int cooldown;
        private int repath;

        Chase(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private ServerPlayer robber() {
            ServerPlayer p = robberies.robberPlayer();
            return p != null && p.level() == mob.level() && p.isAlive() ? p : null;
        }

        @Override
        public boolean canUse() {
            return robber() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return robber() != null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            ServerPlayer r = robber();
            if (r == null) return;
            if (repath-- <= 0 || mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(r, 1.0);
                repath = 8;
            }
            mob.getLookControl().setLookAt(r, 30f, 30f);
            if (cooldown > 0) cooldown--;
            double dy = Math.abs(mob.getY() - r.getY());
            double dx = mob.getX() - r.getX(), dz = mob.getZ() - r.getZ();
            if (cooldown <= 0 && dy < 2.5 && dx * dx + dz * dz <= reach * reach && mob.hasLineOfSight(r)) {
                mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
                ServerLevel level = (ServerLevel) mob.level();
                // A mob's hit does nothing on peaceful (vanilla scales mob damage by difficulty), so there the officer's
                // hit is plain damage: the police work on every difficulty.
                if (level.getDifficulty() == Difficulty.PEACEFUL) r.hurtServer(level, level.damageSources().generic(), (float) damage);
                else r.hurtServer(level, level.damageSources().mobAttack(mob), (float) damage);
                cooldown = attackCooldown;
            }
        }
    }

    // ------------------------------------------------------------------ events (registered once by Robberies)

    /** Officers only hurt the robber. */
    static boolean allowDamage(Robberies rob, LivingEntity target, Entity from) {
        if (!isPolice(from)) return true;
        ServerPlayer robber = rob == null ? null : rob.robberPlayer();
        return robber != null && target == robber;
    }

    /** Officers left over from a crash or an unloaded chunk are removed as soon as they load. */
    static void onLoad(Robberies rob, Entity e) {
        if (!isPolice(e)) return;
        Robberies.Robbery r = rob == null ? null : rob.current();
        if (r == null || !r.id.equals(robberyOf(e))) {
            net.axther.aftrhrs.core.Tasks.later(1, () -> remove(e));
        }
    }
}
