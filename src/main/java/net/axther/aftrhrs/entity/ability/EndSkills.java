package net.axther.aftrhrs.entity.ability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The End creatures (Creatures 2): the Chorusling's void rescue, the Shulkling's ender calm and levitation ward, and the
 * Voidpuff's puff / homing / breath. Same conventions as {@link PetSkills}: registered under the spec's skill ids, the
 * line's target ({@code @Owner}) is {@code ctx.target}, bond scales via {@code ctx.bond(a, b)}.
 */
public final class EndSkills {
    private EndSkills() {
    }

    /** The last solid ground an owner stood on near their Chorusling (Anchor writes it, VoidRescue reads it). */
    record Safe(ResourceKey<Level> level, Vec3 pos, long at) {
    }

    private static final Map<UUID, Safe> SAFE = new ConcurrentHashMap<>();
    /** The Voidpuff's purple breath. */
    static final PowerParticleOption BREATH = PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0f);

    /** The remembered safe spot of a player (tests, debugging). */
    public static @Nullable Vec3 safeSpot(ServerPlayer p) {
        Safe s = SAFE.get(p.getUUID());
        return s == null || s.level() != p.level().dimension() ? null : s.pos();
    }

    static void register() {
        // ---------------------------------------------------------------- Chorusling: void rescue
        Abilities.register("ArcanaChorusling_Anchor", c -> {
            ServerPlayer o = c.ownerWithin(48);
            if (o == null || o.isSpectator() || !o.onGround() || o.isPassenger() || o.isFallFlying()) return;
            if (!standable(c.level(), o.blockPosition())) return;
            SAFE.put(o.getUUID(), new Safe(o.level().dimension(), Vec3.atBottomCenterOf(o.blockPosition()), c.level().getGameTime()));
        });
        Abilities.register("ArcanaChorusling_VoidRescue", c -> {
            ServerPlayer o = c.owner();
            ServerLevel level = c.level();
            if (o == null || o.level() != level || o.isSpectator() || o.isCreative() || !o.isAlive()) return;
            if (o.getY() >= level.getMinY() - 1) return;                         // not below the world (yet)
            double dx = o.getX() - c.self.getX(), dz = o.getZ() - c.self.getZ();
            if (dx * dx + dz * dz > 64 * 64) return;
            if (!c.self.isAlive()) return;
            if (!c.cooldown("void_rescue", c.bond(180, -18))) {
                if (c.cooldown("void_rescue_msg", 6)) c.actionBar(o, "void-rescue-tired", "&dYour <pet> &7is still catching its breath...");
                return;
            }
            Vec3 to = rescueSpot(c, o);
            if (to == null) {
                c.sched().clearCooldown("void_rescue");
                return;
            }
            Vec3 from = o.position();
            o.stopRiding();
            o.teleportTo(to.x, to.y, to.z);
            o.setDeltaMovement(Vec3.ZERO);
            o.resetFallDistance();
            o.needsSync = true;
            c.potion(o, "SLOW_FALLING", (int) c.bond(60, 12), 0, true);
            Fx.particles(level, from, ParticleTypes.PORTAL, 40, 0.5, 1.0, 0.4);
            Fx.particles(level, to.add(0, 1, 0), ParticleTypes.PORTAL, 40, 0.5, 1.0, 0.4);
            Fx.particles(level, to.add(0, 0.2, 0), ParticleTypes.END_ROD, 12, 0.4, 0.3, 0.05);
            Fx.sound(o, "item.chorus_fruit.teleport", 1.0f, 0.9f);
            c.ability();
            c.sound("block.chorus_flower.grow", 0.9, 1.3);
            c.particles(ParticleTypes.REVERSE_PORTAL, 16, 0.3, 0.4, 0.8);
            c.actionBar(o, "void-rescue", "&dYour <pet> &7pulled you back from the void!");
            c.fired();
        });

        // ---------------------------------------------------------------- Shulkling: ender calm, levitation ward
        Abilities.register("ArcanaShulkling_EnderCalm", c -> {
            ServerPlayer o = c.ownerWithin(32);
            if (o == null || o.isSpectator()) return;
            double r = c.bond(16, 2);
            boolean calmed = false;
            for (Enderman e : c.around(o, r, Enderman.class, e -> e.isAlive() && (e.getTarget() == o || e.isAngryAt(o, c.level())))) {
                // a fight the owner started stays a fight
                if (e.getLastHurtByMob() == o) continue;
                e.stopBeingAngry();
                e.setTarget(null);
                Fx.particles(c.level(), e.position().add(0, 2.4, 0), ParticleTypes.NOTE, 1, 0.2, 0.1, 0);
                calmed = true;
            }
            if (calmed && c.cooldown("ender_calm_fx", 4)) {
                c.ability();
                c.sound("entity.shulker.ambient", 0.6, 1.6);
                c.particles(ParticleTypes.PORTAL, 10, 0.3, 0.3, 0.6);
                c.fired();
            }
        });
        Abilities.register("ArcanaShulkling_LevitationWard", c -> {
            ServerPlayer o = c.ownerWithin(16);
            if (o == null || o.isSpectator() || !o.hasEffect(MobEffects.LEVITATION) || o.isShiftKeyDown()) return;
            o.removeEffect(MobEffects.LEVITATION);
            c.potion(o, "SLOW_FALLING", (int) c.bond(80, 16), 0, true);
            if (c.cooldown("ward_fx", 2)) {
                c.ability();
                c.sound("entity.shulker.close", 0.7, 1.4);
                c.particles(o, ParticleTypes.END_ROD, 8, 0.3, 0.6, 0.8);
                c.fired();
            }
        });

        // ---------------------------------------------------------------- Voidpuff: puff up, drift home, breathe
        Abilities.register("ArcanaVoidpuff_Puff", c -> {
            if (!c.cooldown("puff", 2)) return;
            if (c.self instanceof ArcanaWildlife w) w.spook(100);
            c.play("puff");
            c.sound("entity.puffer_fish.blow_up", 0.8, 1.1);
            c.particles(BREATH, 10, 0.3, 0.3, 0.5);
            c.self.setDeltaMovement(c.self.getDeltaMovement().add(0, 0.35, 0));
            c.self.needsSync = true;
        });
        Abilities.register("ArcanaVoidpuff_Home", c -> {
            // keeps a drifter near the island it was born over: no ground under it for 24 blocks -> drift back
            if (c.mob.var("home_set") == 0) {
                c.mob.setVar("home_x", c.self.getBlockX());
                c.mob.setVar("home_y", c.self.getBlockY());
                c.mob.setVar("home_z", c.self.getBlockZ());
                c.mob.setVar("home_set", 1);
                return;
            }
            int ground = c.level().getHeight(Heightmap.Types.MOTION_BLOCKING, c.self.getBlockX(), c.self.getBlockZ());
            boolean overVoid = ground <= c.level().getMinY() || c.self.getY() - ground > 24;
            double hx = c.mob.var("home_x") + 0.5, hy = c.mob.var("home_y") + 2, hz = c.mob.var("home_z") + 0.5;
            boolean far = c.self.distanceToSqr(hx, hy, hz) > 40 * 40;
            if (!overVoid && !far) return;
            c.self.getNavigation().moveTo(hx + c.self.getRandom().nextInt(9) - 4, hy + c.self.getRandom().nextInt(3),
                    hz + c.self.getRandom().nextInt(9) - 4, 1.0);
        });
        Abilities.register("ArcanaVoidpuff_Breath", c -> {
            if (c.busy() || c.self instanceof ArcanaWildlife w && w.spooked()) return;
            c.play("breathe");
            c.sched().after(14, () -> {
                Fx.particles(c.level(), c.forward(0.6).add(0, 0.25, 0), BREATH, 6, 0.15, 0.1, 0.01);
                c.sound("entity.puffer_fish.blow_out", 0.4, 1.3);
            });
        });
    }

    /** Feet and head free, a sturdy block under the feet. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty();
    }

    /** The owner's last safe spot near the Chorusling (still standable), else beside the Chorusling itself. */
    private static @Nullable Vec3 rescueSpot(Ctx c, ServerPlayer o) {
        ServerLevel level = c.level();
        Safe s = SAFE.get(o.getUUID());
        if (s != null && s.level() == level.dimension() && s.pos().distanceToSqr(c.self.position()) < 128 * 128
                && standable(level, BlockPos.containing(s.pos()))) {
            return s.pos();
        }
        BlockPos at = c.self.blockPosition();
        if (c.self.onGround() && standable(level, at)) return Vec3.atBottomCenterOf(at);
        return null;
    }
}
