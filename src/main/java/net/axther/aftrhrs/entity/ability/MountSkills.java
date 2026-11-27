package net.axther.aftrhrs.entity.ability;

import net.axther.aftrhrs.entity.ArcanaMount;
import net.axther.aftrhrs.entity.Conditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The mounts' rider perks and tricks (dunebun / snowmuff / cinderwing_mount / tempest_roc_mount / tidemane skills). */
final class MountSkills {
    private MountSkills() {
    }

    private static void riders(Ctx c, java.util.function.Consumer<Player> buff) {
        if (!(c.self instanceof ArcanaMount m) || m.getControllingPassenger() == null) return;
        for (Entity e : m.getPassengers()) if (e instanceof Player p) buff.accept(p);
    }

    static void register() {
        // Dunebun: jump strength is an attribute (ArcanaEntities), riders get Jump Boost II (III from bond 3), no falls
        Abilities.register("ArcanaDunebun_Setup", c -> {
        });
        Abilities.register("ArcanaDunebun_Riders", c -> riders(c, p -> {
            // minLeft = the skill's ~onTimer:4 (gen_creatures, dunebun.json) + 3: refreshed before it can run out, not resent every run
            c.sustain(p, "JUMP_BOOST", 30, c.bondLvl(1, 3), false, 7);
            p.resetFallDistance();
            c.self.resetFallDistance();
        }));
        Abilities.register("ArcanaDunebun_Binky", c -> {
            ServerPlayer o = c.ownerWithin(10);
            if (o == null || c.staying() || c.self.isVehicle() || c.self.getRandom().nextFloat() >= 0.3f) return;
            c.ability();
            c.sched().after(5, () -> {
                c.sound("entity.goat.long_jump", 0.6, 1.2);
                c.particles(ParticleTypes.END_ROD, 6, 0.3, 0.3, 1.4);
            });
        });

        // Snowmuff: Resistance + never freeze for riders, frost-walks still water while ridden
        Abilities.register("ArcanaSnowmuff_Riders", c -> riders(c, p -> {
            // minLeft = ~onTimer:10 (gen_creatures, snowmuff.json) + 3
            c.sustain(p, "RESISTANCE", 30, 0, false, 13);
            p.setTicksFrozen(0);
        }));
        Abilities.register("ArcanaSnowmuff_FrostWalk", c -> {
            if (!(c.self instanceof ArcanaMount m) || m.getControllingPassenger() == null || !m.onGround()) return;
            int r = (int) Math.round(c.bond(2, 0.4));
            BlockPos feet = m.blockPosition();
            boolean froze = false;
            for (BlockPos p : BlockPos.betweenClosed(feet.offset(-r, -1, -r), feet.offset(r, -1, r))) {
                if (p.distSqr(feet.below()) > (r + 0.5) * (r + 0.5)) continue;
                BlockState st = c.level().getBlockState(p);
                if (st.is(Blocks.WATER) && st.getFluidState().isSource() && c.level().getBlockState(p.above()).isAir()) {
                    c.level().setBlockAndUpdate(p, Blocks.FROSTED_ICE.defaultBlockState());
                    c.level().scheduleTick(p, Blocks.FROSTED_ICE, net.minecraft.util.Mth.nextInt(m.getRandom(), 60, 120));
                    froze = true;
                }
            }
            if ((froze || c.level().getBlockState(feet.below()).is(Blocks.FROSTED_ICE)) && c.cooldown("frostfx", 6)) {
                c.ability();
                c.particles(c.self, ParticleTypes.SNOWFLAKE, 24, 1.2, 0.1, 0.2, 0.02);
                c.sound("block.glass.break", 0.5, 1.6);
                c.sound("block.powder_snow.step", 0.9, 0.8);
                c.fired();
            }
        });
        Abilities.register("ArcanaSnowmuff_Passenger", c -> {
        });
        Abilities.register("ArcanaSnowmuff_Stomp", c -> {
            ServerPlayer o = c.ownerWithin(8);
            if (o == null || c.staying() || c.self.isVehicle() || c.self.getRandom().nextFloat() >= 0.3f) return;
            c.ability();
            c.sched().after(10, () -> {
                c.particles(c.self, ParticleTypes.SNOWFLAKE, 16, 0.8, 0.1, 0.2, 0.02);
                c.sound("block.powder_snow.step", 0.8, 0.7);
            });
        });

        // Cinderwing Phoenix: Fire Resistance for riders, ember trail, left-click kindles (rider swing hook)
        // minLeft = ~onTimer:10 (gen_creatures, cinderwing_phoenix.json) + 3
        Abilities.register("ArcanaCinderwingMount_Riders", c -> riders(c, p -> c.sustain(p, "FIRE_RESISTANCE", (int) c.bond(60, 12), 0, false, 13)));
        Abilities.register("ArcanaCinderwingMount_Trail", c -> {
            if (!(c.self instanceof ArcanaMount m) || m.getControllingPassenger() == null) return;
            c.particles(c.self, ParticleTypes.SMALL_FLAME, 3, 0.5, 0.2, 1.0, 0.01);
            c.particles(c.self, ParticleTypes.FLAME, 1, 0.3, 0.1, 1.3, 0.005);
        });
        Abilities.register("ArcanaCinderwingMount_Passenger", c -> {
        });
        Abilities.register("ArcanaCinderwingMount_Kindle", c -> {
            if (!(c.trigger instanceof ServerPlayer p) || !(c.self instanceof ArcanaMount m) || m.getControllingPassenger() != p) return;
            if (!c.cooldown("kindle", 3)) return;
            Vec3 eye = p.getEyePosition();
            BlockHitResult hit = c.level().clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(8)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            c.ability();
            c.sound(p, "item.firecharge.use", 0.7, 1.3);
            if (hit.getType() != HitResult.Type.BLOCK) return;
            BlockPos above = hit.getBlockPos().above();
            if (c.level().getBlockState(above).isAir() && c.level().mayInteract(p, above)) {
                c.level().setBlockAndUpdate(above, Blocks.FIRE.defaultBlockState());
            }
            Fx.particles(c.level(), Vec3.atCenterOf(hit.getBlockPos()).add(0, 1, 0), ParticleTypes.FLAME, 10, 0.25, 0.25, 0.02);
            c.fired();
        });

        // Tempest Roc: Speed + Slow Falling for riders; landing after a dive lets out a gust
        Abilities.register("ArcanaTempestRocMount_Riders", c -> riders(c, p -> {
            // minLeft = ~onTimer:10 (gen_creatures, tempest_roc.json) + 3
            c.sustain(p, "SPEED", 60, 0, false, 13);
            c.sustain(p, "SLOW_FALLING", 100, 0, false, 13);
        }));
        Abilities.register("ArcanaTempestRocMount_DiveWatch", c -> {
            if (!(c.self instanceof ArcanaMount m)) return;
            double vy = m.getY() - m.yo;
            if (m.getControllingPassenger() != null && !m.onGround() && vy < -0.2) m.setVar("diving", 1);
            if (m.onGround() && m.var("diving") == 1) {
                m.setVar("diving", 0);
                if (!c.cooldown("gust", 3)) return;
                c.ability();
                c.particles(c.self, ParticleTypes.GUST, 1, 0, 0, 0.3, 0);
                c.particles(c.self, ParticleTypes.CLOUD, 30, 2, 0.1, 0.2, 0.15);
                c.sound("entity.ender_dragon.flap", 1.0, 1.4);
                c.sound("entity.phantom.flap", 1.0, 0.6);
                for (LivingEntity e : c.around(c.self, c.bond(4, 0.6), LivingEntity.class, e -> !(e instanceof Player)
                        && !(e instanceof OwnableEntity o && o.getOwnerReference() != null) && !m.hasPassenger(e))) {
                    PetSkills.throwFrom(e, m.position(), 8, 3);
                }
                c.fired();
            }
        });
        Abilities.register("ArcanaTempestRocMount_Passenger", c -> {
        });

        // Tidemane: water breathing for riders, a spray of water when it idles in water
        // RideTick runs every 20 ticks while ridden (Abilities.onRide, not an ~onTimer): minLeft = 20 + 3
        Abilities.register("ArcanaTidemane_RideTick", c -> riders(c, p -> c.sustain(p, "WATER_BREATHING", 60, 0, false, 23)));
        Abilities.register("ArcanaTidemane_Spray", c -> {
            if (!c.hasOwner() || c.self.isVehicle() || !Conditions.inWater(c.self) || c.moving() || c.self.getRandom().nextFloat() >= 0.3f) return;
            c.ability();
            c.sched().after(6, () -> {
                c.particles(c.self, ParticleTypes.SPLASH, 30, 0.8, 0.4, 2.0, 0.1);
                c.sound("entity.horse.breathe", 0.7, 0.8);
            });
        });
    }
}
