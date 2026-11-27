package net.axther.aftrhrs.entity.ability;

import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.axther.aftrhrs.entity.Conditions;
import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Creatures 2, ocean: the Floepup's Lifeguard (pet ability), the Kelpback's garden hooks and surface breath, the
 * Inkcurl's ink squirt (tools/specs/floepup.json, tools/wild_specs/kelpback.json + inkcurl.json); the Pockie's glow
 * pocket (tools/specs/pockie.json).
 */
final class OceanSkills {
    private OceanSkills() {
    }

    /** Owner light level at or below which the Pockie's glow pocket works out of water (monster-spawning dark). */
    static final int POCKIE_DARK = 7;
    /** The glow pocket's dust: the pocket shark's green bioluminescence. */
    static final DustParticleOptions POCKIE_GLOW = new DustParticleOptions(0x7DFFB0, 1.0f);

    /** How far up a Kelpback looks for the surface when it wants a breath. */
    private static final int SURFACE_SCAN = 12;

    static void register() {
        // ---------------------------------------------------------------- Floepup: lifeguard
        // Every second (line ~onTimer:20, target @Owner): its owner in water within 16 blocks, out of air (below a third)
        // or badly hurt (below a third of their health) under water -> it rushes in with a gulp of air: full air,
        // Regeneration I, a push toward the surface. Then it rests 60 -> 30 s (bond).
        Abilities.register("ArcanaFloepup_Lifeguard", c -> {
            ServerPlayer o = c.ownerWithin(16);
            if (o == null || o.isSpectator() || o.isCreative() || !Conditions.inWater(o)) return;
            boolean air = o.getAirSupply() < o.getMaxAirSupply() / 3;
            boolean hurt = o.isUnderWater() && o.getHealth() < o.getMaxHealth() / 3;
            if (!air && !hurt) return;
            if (!c.cooldown("lifeguard", c.bond(60, -6))) return;
            c.ability();
            // the dash: straight at its owner through the water
            Vec3 to = o.position().add(0, 0.4, 0).subtract(c.self.position());
            if (to.length() > 1.5 && Conditions.inWater(c.self)) {
                c.self.setDeltaMovement(to.normalize().scale(Math.min(1.2, 0.25 + to.length() * 0.12)));
                c.self.needsSync = true;
            }
            c.self.getLookControl().setLookAt(o);
            c.particles(ParticleTypes.BUBBLE, 16, 0.3, 0.3, 0.4);
            c.sched().after(10, () -> {
                if (!o.isAlive() || o.level() != c.level()) return;
                o.setAirSupply(o.getMaxAirSupply());
                c.potion(o, "REGENERATION", (int) c.bond(80, 20), 0, true);
                if (o.isUnderWater()) {
                    o.push(0, 0.55, 0);
                    o.needsSync = true;
                }
                c.particles(o, ParticleTypes.BUBBLE, 30, 0.4, 0.6, 1.0);
                c.particles(o, ParticleTypes.BUBBLE_POP, 12, 0.4, 0.4, 1.4);
                c.sound(o, "entity.player.breath", 0.9, 1.2);
                c.sound("block.bubble_column.upwards_inside", 0.7, 1.3);
                c.fired();
            });
        });

        // ---------------------------------------------------------------- Pockie: glow pocket
        // Every 2 s (line ~onTimer:40, target @Owner): its owner within 10 blocks, in water or in the dark (light <= 7)
        // -> it squirts glowing fluid from its pockets: wild monsters within bond(12, 2) blocks of the owner glow (marked),
        // fish too while the owner is in water, and an owner under water gets Night Vision; for bond(100, 20) ticks each.
        // Only when there is something to light (a monster or fish to mark, or an owner under water); rests bond(20, -2) s.
        // Works from its fishbowl on land as well (the bowl clips play its squirt).
        Abilities.register("ArcanaPockie_GlowPocket", c -> {
            ServerPlayer o = c.ownerWithin(10);
            if (o == null || o.isSpectator()) return;
            boolean wet = Conditions.inWater(o);
            boolean dark = c.level().getMaxLocalRawBrightness(o.blockPosition()) <= POCKIE_DARK;
            if (!wet && !dark) return;
            double r = c.bond(12, 2);
            List<LivingEntity> marks = c.around(o, r, LivingEntity.class,
                    e -> Conditions.isWildMonster(e) || wet && e instanceof AbstractFish && e.isAlive() && Conditions.inWater(e));
            boolean under = c.level().getFluidState(BlockPos.containing(o.getEyePosition())).is(FluidTags.WATER);
            if (marks.isEmpty() && !under) return;
            if (!c.cooldown("glowpocket", c.bond(20, -2))) return;
            c.ability();
            int ticks = (int) c.bond(100, 20);
            c.sched().after(12, () -> {
                if (!c.self.isAlive()) return;
                c.particles(net.minecraft.core.particles.ParticleTypes.GLOW, 24, 0.3, 0.3, 0.3);
                c.particles(POCKIE_GLOW, 14, 0.35, 0.3, 0.35);
                c.sound("entity.glow_squid.squirt", 0.7, 1.7);
                int lines = 0;
                for (LivingEntity e : marks) {
                    if (!e.isAlive() || e.level() != c.level()) continue;
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false));
                    if (lines++ < 6) c.line(c.self, e, POCKIE_GLOW, 0.7, 0.3, e.getBbHeight() * 0.6);
                }
                if (under && o.isAlive()) c.potion(o, "NIGHT_VISION", ticks, 0, false);
                c.fired();
            });
        });

        // ---------------------------------------------------------------- Kelpback: the garden on its back
        Abilities.register("ArcanaKelpback_Shorn", c -> {
            c.mob.setVar("shorn", 1);
            c.play("harvest");
            c.mob.setBoneVisible("garden", false);
            c.sound("entity.sheep.shear", 1, 0.8);
            c.particles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.KELP_PLANT.defaultBlockState()), 18, 0.6, 0.4, 1.2);
        });
        Abilities.register("ArcanaKelpback_Regrow", c -> {
            c.mob.setVar("shorn", 0);
            c.mob.setBoneVisible("garden", true);
            c.particles(ParticleTypes.HAPPY_VILLAGER, 12, 0.6, 0.3, 1.2);
            c.sound("block.wet_grass.place", 0.6, 0.8);
        });
        // life "breathe": swims up to the surface, takes a breath (bubbles, a soft spout), then drifts back down
        Abilities.register("ArcanaKelpback_Breathe", c -> {
            if (!Conditions.inWater(c.self) || !c.cooldown("breathe", 30)) return;
            BlockPos at = c.self.blockPosition();
            int surface = -1;
            for (int dy = 0; dy <= SURFACE_SCAN; dy++) {
                if (!c.level().getFluidState(at.above(dy)).is(FluidTags.WATER)) {
                    surface = at.getY() + dy;
                    break;
                }
            }
            if (surface < 0) return;
            final int top = surface;
            int rise = surface - at.getY();
            if (rise > 1) c.self.getNavigation().moveTo(c.self.getX(), surface - 1.0, c.self.getZ(), 1.0);
            int wait = Math.min(80, 10 + rise * 8);
            c.sched().after(wait, () -> {
                if (!c.self.isAlive() || c.busy()) return;
                c.play("surface_breath");
                c.sched().after(20, () -> {
                    c.sound("entity.player.breath", 0.7, 0.45);
                    Vec3 p = c.self.position();
                    c.level().sendParticles(ParticleTypes.SPLASH, p.x, top + 0.1, p.z, 16, 0.4, 0.1, 0.4, 0.05);
                    c.level().sendParticles(ParticleTypes.BUBBLE, p.x, p.y + 1.0, p.z, 10, 0.4, 0.4, 0.4, 0.02);
                });
            });
        });

        // ---------------------------------------------------------------- Inkcurl: startled -> ink and jet off
        Abilities.register("ArcanaInkcurl_Startle", c -> {
            if (!c.cooldown("startle", 3)) return;
            c.play("ink_jet");
            c.particles(ParticleTypes.SQUID_INK, 30, 0.6, 0.5, 0.5);
            c.sound("entity.squid.squirt", 1.0, 1.3);
            if (c.self instanceof ArcanaWildlife w) w.spook(120);
            if (c.trigger != null && Conditions.inWater(c.self)) {
                Vec3 away = c.self.position().subtract(c.trigger.position()).multiply(1, 0.3, 1);
                if (away.lengthSqr() > 1.0E-4) {
                    c.self.setDeltaMovement(away.normalize().scale(0.7));
                    c.self.needsSync = true;
                }
            }
        });
    }
}
