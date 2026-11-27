package net.axther.aftrhrs.entity.ability;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Conditions;
import net.axther.aftrhrs.entity.CreatureSfx;
import net.axther.aftrhrs.entity.CreatureUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * The pets', dogs', cats' and tameable hostiles' jobs (tools/specs/*.skills.yml + skills/taming.yml), ported 1:1:
 * same ranges, cooldowns, effects and bond scaling. Each is registered under its MythicMobs skill id and called by the
 * spec's {@code skill{s=...}} line on its trigger; the caller's target (usually {@code @Owner}) is {@code ctx.target}.
 */
final class PetSkills {
    private PetSkills() {
    }

    private static void reg(String id, Abilities.Skill s) {
        Abilities.register(id, s);
    }

    /** The owner as the line's target, within {@code r} blocks, else null. */
    private static ServerPlayer owner(Ctx c, double r) {
        return c.ownerWithin(r);
    }

    /** Pdc tag on items a player dropped by dying: whose death drop it is (fetching pets leave those alone). */
    public static final String DEATH_DROP = "aftrhrs:death_drop";
    private static java.util.UUID dying;   // nullable
    private static long dyingAt = Long.MIN_VALUE;

    /**
     * Whether a fetching pet may bring this item to its owner: never someone else's thrown item (stored thrower, loaded or
     * not) or pickup target, never another player's death drop, never from a claim the owner can't build in.
     */
    public static boolean fetchable(ServerPlayer owner, ItemEntity item) {
        java.util.UUID me = owner.getUUID();
        if (item.thrower != null && !me.equals(item.thrower.getUUID())) return false;
        if (item.target != null && !me.equals(item.target)) return false;
        String dead = net.axther.aftrhrs.core.Pdc.getString(item, DEATH_DROP);
        if (dead != null && !dead.equals(me.toString())) return false;
        var f = net.axther.aftrhrs.core.Feather.get();
        return f == null || f.claimAllows(owner, net.axther.aftrhrs.core.Loc.of(item), net.axther.aftrhrs.core.ClaimAction.BUILD);
    }

    /** A player's death drops (items spawned while they die, in that tick) get {@link #DEATH_DROP}. */
    private static void tagDeathDrops() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((e, source, amount) -> {
            if (e instanceof ServerPlayer p) {
                dying = p.getUUID();
                dyingAt = p.level().getGameTime();
            }
            return true;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
            if (dying != null && e instanceof ItemEntity i && level.getGameTime() == dyingAt && i.tickCount == 0) {
                net.axther.aftrhrs.core.Pdc.putString(i, DEATH_DROP, dying.toString());
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (e instanceof ServerPlayer) dying = null;
        });
    }

    static void register() {
        tagDeathDrops();
        // ---------------------------------------------------------------- shared buffs (skills/taming.yml)
        reg("ArcanaCairnling_Fortify", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying()) return;
            c.potion(o, "RESISTANCE", (int) c.bond(120, 24), 0, true);
        });
        reg("ArcanaGlimmermoth_Glimmer", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || !Conditions.night(c.level())) return;
            c.potion(o, "NIGHT_VISION", (int) c.bond(320, 64), 0, true);
            c.particles(ParticleTypes.END_ROD, 4, 0.3, 0.3, 0.4);
        });
        reg("ArcanaRunebackTortoise_Tide", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || !Conditions.inWater(o)) return;
            c.potion(o, "WATER_BREATHING", (int) c.bond(200, 40), 0, true);
        });
        reg("ArcanaSkyfinRay_Updraft", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null) return;
            c.potion(o, "SLOW_FALLING", (int) c.bond(80, 16), 0, true);
        });

        // ---------------------------------------------------------------- Frog Guy: hop buddy (egg-only)
        // While he follows his owner (within 10 blocks): Jump Boost I (II from bond 3). Now and then (while idle and
        // free) he does a happy hop and a croak.
        reg("ArcanaFrogGuy_HopBuddy", c -> {
            ServerPlayer o = owner(c, 10);
            if (o == null || c.staying()) return;
            c.potion(o, "JUMP_BOOST", 60, c.bondLvl(0, 3), false);
            if (!c.moving() && !c.busy() && !c.inCombat() && c.self.getRandom().nextFloat() < 0.15f && c.cooldown("hop", 45)) {
                c.ability();
            }
        });

        // ---------------------------------------------------------------- Emberkit: hearth-lighter
        reg("ArcanaEmberkit_Hearth", c -> {
            if (c.staying() || owner(c, 8) == null) return;
            int r = (int) Math.round(c.bond(3, 0.6));
            List<BlockPos> fires = blocks(c.self, r, r, st -> (st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_CAMPFIRE))
                    && !st.getValue(CampfireBlock.LIT) && !st.getValue(CampfireBlock.WATERLOGGED) && !st.getValue(CampfireBlock.SIGNAL_FIRE));
            if (fires.isEmpty()) return;
            c.ability();
            c.sound("entity.blaze.shoot", 0.4, 1.8);
            c.sched().after(10, () -> {
                c.particles(ParticleTypes.FLAME, 10, 0.25, 0.15, 0.5);
                for (BlockPos p : fires) {
                    BlockState st = c.level().getBlockState(p);
                    if (!(st.getBlock() instanceof CampfireBlock) || st.getValue(CampfireBlock.LIT)) continue;
                    c.level().setBlock(p, st.setValue(CampfireBlock.LIT, true), 11);
                    Fx.sound(c.self, "item.flintandsteel.use", 0.8f, 1.0f);
                    Fx.particles(c.level(), Vec3.atCenterOf(p).add(0, 0.1, 0), ParticleTypes.FLAME, 8, 0.3, 0.2, 0.02);
                }
                c.fired();
            });
        });

        // ---------------------------------------------------------------- Snowpelt Owlet: night watch
        reg("ArcanaSnowpeltOwlet_NightWatch", c -> {
            ServerPlayer o = owner(c, 16);
            if (o == null || c.staying() || !Conditions.night(c.level())) return;
            c.ability();
            c.sched().after(8, () -> {
                c.sound("block.note_block.flute", 0.9, 0.6);
                c.particles(ParticleTypes.SNOWFLAKE, 10, 0.4, 0.3, 0.7);
            });
            c.sched().after(14, () -> c.sound("block.note_block.flute", 0.9, 0.5));
            for (LivingEntity e : c.around(o, c.bond(16, 1.6), LivingEntity.class, Conditions::isWildMonster)) {
                c.potion(e, "GLOWING", (int) c.bond(100, 20), 0, false);
            }
            c.fired();
        });

        // ---------------------------------------------------------------- Cairnling: pebble miner
        reg("ArcanaCairnling_PebbleMiner", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || Conditions.outside(o) || o.getY() >= 56) return;
            c.potion(o, "HASTE", 120, c.bondLvl(0, 3), true);
            if (c.self.getRandom().nextFloat() < 0.15f) {
                c.ability();
                c.sched().after(11, () -> {
                    c.sound("block.stone.break", 0.6, 1.4);
                    c.particles(ParticleTypes.CRIT, 6, 0.3, 0.1, 0.2);
                });
            }
            c.fired();
        });

        // ---------------------------------------------------------------- Glimmermoth: pollinate
        reg("ArcanaGlimmermoth_Pollinate", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || !Conditions.day(c.level())) return;
            c.ability();
            c.sound("entity.bee.pollinate", 0.7, 1.4);
            c.sched().after(6, () -> c.particles(ParticleTypes.WAX_ON, 10, 0.4, 0.3, 0.3));
            BlockPos floor = o.blockPosition().below();
            c.sched().after(14, () -> bloomGrass(c, floor));
            c.sched().after(30, () -> {
                if (c.self.getRandom().nextDouble() < c.bond(0, 0.2)) bloomGrass(c, floor);
            });
            c.fired();
        });

        // ---------------------------------------------------------------- Runeback Tortoise: shell ward
        reg("ArcanaRunebackTortoise_ShellWard", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || o.getHealth() >= 8) return;
            if (!c.cooldown("shellward", c.bond(60, -4.8))) return;
            c.ability();
            c.potion(o, "ABSORPTION", 200, 0, true);
            c.sound("item.armor.equip_turtle", 1.0, 0.8);
            c.particles(o, ParticleTypes.END_ROD, 8, 0.4, 0.6, 1.0);
            c.sched().after(10, () -> {
                c.sound("block.enchantment_table.use", 0.7, 1.2);
                c.particles(ParticleTypes.ENCHANT, 24, 0.8, 0.5, 0.8);
            });
            c.fired();
        });

        // ---------------------------------------------------------------- Skyfin Ray: tailwind, sun glide
        reg("ArcanaSkyfinRay_Tailwind", c -> {
            ServerPlayer o = owner(c, 16);
            if (o == null || !o.isFallFlying()) return;
            c.ability();
            Vec3 look = o.getLookAngle();
            o.push(look.x * c.bond(0.5, 0.06), 0.1, look.z * c.bond(0.5, 0.06));
            o.needsSync = true;
            c.sound("entity.phantom.flap", 0.8, 1.2);
            c.particles(o, ParticleTypes.CLOUD, 8, 0.4, 0.2, 0.2);
            c.fired();
        });
        reg("ArcanaSkyfinRay_SunGlide", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || !Conditions.day(c.level()) || !Conditions.outside(o) || o.isFallFlying()) return;
            c.potion(o, "SPEED", 120, 0, true);
        });

        // ---------------------------------------------------------------- Mossbun: bloom
        reg("ArcanaMossbun_Bloom", c -> {
            ServerPlayer o = owner(c, 10);
            if (o == null || c.staying() || !c.cooldown("bloom", 9)) return;
            c.self.getNavigation().moveTo(o, 1.3);
            c.sched().after(15, () -> {
                c.self.getJumpControl().jump();
                c.self.setDeltaMovement(c.self.getDeltaMovement().add(0, 0.4, 0));
                c.ability();
                c.sound("entity.rabbit.jump", 0.6, 1.4);
            });
            c.sched().after(27, () -> {
                c.particles(ParticleTypes.HAPPY_VILLAGER, 6, 0.4, 0.4, 1.0);
                c.sound("item.bone_meal.use", 0.8, 1.2);
                int r = (int) Math.round(c.bond(4, 0.4));
                for (BlockPos p : blocks(c.self, r, 1, st -> st.is(BlockTags.CROPS) || st.is(BlockTags.SAPLINGS)
                        || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.COCOA))) {
                    if (BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), c.level(), p)) {
                        c.level().levelEvent(1505, p, 15);
                        Fx.particles(c.level(), Vec3.atCenterOf(p), ParticleTypes.HAPPY_VILLAGER, 4, 0.3, 0.3, 0.02);
                    }
                }
                c.fired();
            });
        });

        // ---------------------------------------------------------------- Burrowpip / Shellpip: finds
        reg("ArcanaBurrowpip_Forage", c -> {
            if (owner(c, 10) == null) return;
            BlockState on = c.level().getBlockState(c.self.blockPosition().below());
            if (!(on.is(BlockTags.SUBSTRATE_OVERWORLD) || on.is(BlockTags.SAND) || on.is(Blocks.GRAVEL) || on.is(Blocks.CLAY)
                    || on.is(BlockTags.BASE_STONE_OVERWORLD) || on.is(Blocks.DRIPSTONE_BLOCK) || on.is(Blocks.CALCITE))) return;
            if (!c.cooldown("forage", c.bond(90, -6))) return;
            dig(c, Blocks.DIRT, "block.rooted_dirt.break", "block.gravel.hit", 8, 8, 12, () -> c.drop(weighted(c, BURROWPIP)),
                    "block.amethyst_block.chime", 0.8, 1.5);
        });
        reg("ArcanaShellpip_Comb", c -> {
            if (owner(c, 10) == null) return;
            BlockState on = c.level().getBlockState(c.self.blockPosition().below());
            if (!(on.is(BlockTags.SAND) || on.is(Blocks.GRAVEL) || on.is(Blocks.SUSPICIOUS_GRAVEL))) return;
            if (!c.cooldown("comb", c.bond(90, -6))) return;
            dig(c, Blocks.SAND, "block.sand.break", "block.sand.hit", 6, 6, 12, () -> {
                String id = weightedId(c, SHELLPIP);
                Item item = CreatureUtil.customItem(id);
                if (item != null) c.drop(new ItemStack(item));
            }, "block.amethyst_block.chime", 0.7, 1.7);
        });

        // ---------------------------------------------------------------- Honeycub
        reg("ArcanaHoneycub_Snack", c -> {
            ServerPlayer o = owner(c, 6);
            if (o == null || o.getFoodData().getFoodLevel() >= 14 || !c.cooldown("snack", 30)) return;
            c.ability();
            c.sound("block.honey_block.slide", 0.7, 1.3);
            c.sched().after(12, () -> {
                int food = (int) Math.round(c.bond(4, 0.8));
                o.getFoodData().eat(food, 2f / (2f * Math.max(1, food)));
                c.sound(o, "item.honey_bottle.drink", 0.8, 1.2);
                c.particles(o, ParticleTypes.FALLING_HONEY, 8, 0.4, 0.3, 1.4);
                c.hearts(c.self, 2, 0.9);
                c.fired();
            });
        });
        reg("ArcanaHoneycub_Hug", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || o.getHealth() >= 6 || !c.cooldown("hug", 45)) return;
            c.play(c.species().anim("tame"));
            c.potion(o, "REGENERATION", (int) c.bond(100, 20), 0, true);
            c.hearts(o, 5, 1.2);
            c.particles(ParticleTypes.DRIPPING_HONEY, 6, 0.3, 0.2, 0.8);
            c.sound("entity.polar_bear.ambient_baby", 0.8, 1.5);
            c.fired();
        });

        // ---------------------------------------------------------------- Puddlepup
        reg("ArcanaPuddlepup_Douse", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || !o.isOnFire() || !c.cooldown("douse", 3)) return;
            o.clearFire();
            c.ability();
            c.particles(o, ParticleTypes.SPLASH, 40, 0.4, 0.6, 1.0);
            c.particles(o, ParticleTypes.FALLING_WATER, 12, 0.35, 0.2, 2.1);
            c.particles(o, ParticleTypes.BUBBLE_POP, 10, 0.4, 0.5, 1.2);
            c.sound(o, "entity.generic.extinguish_fire", 0.7, 1.4);
            c.sound("entity.axolotl.splash", 0.8, 1.3);
            c.fired();
        });
        reg("ArcanaPuddlepup_FireWatch", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null) return;
            int r = (int) Math.round(c.bond(3, 0.6));
            List<BlockPos> fires = blocks(o, r, r, st -> st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE));
            if (fires.isEmpty() || !c.cooldown("firewatch", 2)) return;
            c.ability();
            for (BlockPos p : fires) {
                Fx.particles(c.level(), Vec3.atCenterOf(p), ParticleTypes.SPLASH, 8, 0.3, 0.2, 0.02);
                c.level().removeBlock(p, false);
            }
            c.sound(o, "block.fire.extinguish", 0.6, 1.3);
            c.sound("entity.axolotl.splash", 0.6, 1.4);
            c.fired();
        });
        reg("ArcanaPuddlepup_SwimBuddy", c -> {
            ServerPlayer o = owner(c, 10);
            if (o == null || !Conditions.inWater(o)) return;
            c.potion(o, "DOLPHINS_GRACE", 60, 0, true);
            c.particles(ParticleTypes.BUBBLE_POP, 4, 0.3, 0.3, 0.5);
        });

        // ---------------------------------------------------------------- Wickling: lantern (a real moving light block)
        reg("ArcanaWickling_Lantern", c -> {
            boolean want = c.hasOwner() && (!Conditions.outside(c.self) || Conditions.night(c.level()));
            if (!want) {
                Lights.off(c.mob, "wick");
                return;
            }
            int level = (int) Math.min(15, c.bond(9, 1));
            if (Lights.move(c.mob, "wick", level, false)) {
                if (c.cooldown("wickfx", 8)) {
                    c.ability();
                    c.particles(ParticleTypes.SMALL_FLAME, 6, 0.15, 0.1, 0.8);
                    c.particles(ParticleTypes.WAX_ON, 3, 0.2, 0.2, 0.4);
                    c.sound("block.candle.ambient", 0.8, 1.4);
                }
            }
        });
        reg("ArcanaWickling_Unlight", c -> Lights.off(c.mob, "wick"));
        reg("ArcanaWickling_Cleanup", c -> Lights.off(c.mob, "wick"));

        // ---------------------------------------------------------------- Driftglow: underwater lantern + sting
        reg("ArcanaDriftglow_Light", c -> {
            boolean want = c.hasOwner() && Conditions.inWater(c.self);
            if (!want) {
                Lights.off(c.mob, "sl");
                return;
            }
            if (Lights.move(c.mob, "sl", (int) Math.min(15, c.bond(10, 1)), true) && c.cooldown("driftfx", 10)) {
                c.particles(ParticleTypes.GLOW, 8, 0.3, 0.3, 0.5);
                c.sound("entity.glow_squid.ambient", 0.4, 1.9);
            }
        });
        reg("ArcanaSeaLight_Off", c -> Lights.off(c.mob, "sl"));
        reg("ArcanaDriftglow_Cleanup", c -> Lights.off(c.mob, "sl"));
        reg("ArcanaDriftglow_Sting", c -> {
            if (owner(c, 6) == null) return;
            for (Mob m : c.around(c.self, c.bond(5, 0.4), Mob.class, m -> m.getType() == EntityTypes.DROWNED || m.getType() == EntityTypes.GUARDIAN)) {
                m.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), (float) c.bond(4, 0.4));
                c.potion(m, "SLOWNESS", 40, 1, true);
                c.particles(m, ParticleTypes.ELECTRIC_SPARK, 12, 0.3, 0.5, 1.0);
                c.sound("entity.glow_squid.squirt", 0.6, 1.6);
                if (c.cooldown("stingfx", 2)) {
                    c.ability();
                    c.particles(ParticleTypes.GLOW, 10, 0.4, 0.4, 0.5);
                }
                c.fired();
            }
        });

        // ---------------------------------------------------------------- Bubblefin
        reg("ArcanaBubblefin_Tide", c -> {
            ServerPlayer o = owner(c, 12);
            if (o == null || !Conditions.inWater(o)) return;
            int t = (int) c.bond(100, 20);
            c.potion(o, "CONDUIT_POWER", t, 0, false);
            c.potion(o, "DOLPHINS_GRACE", t, 0, false);
            c.particles(ParticleTypes.BUBBLE, 6, 0.3, 0.3, 0.8);
            if (c.cooldown("puff", 12)) {
                c.ability();
                c.sched().after(18, () -> {
                    c.particles(ParticleTypes.BUBBLE_POP, 14, 0.3, 0.3, 1.0);
                    c.particles(o, ParticleTypes.BUBBLE, 10, 0.4, 0.6, 1.0);
                    c.sound("block.bubble_column.upwards_inside", 0.6, 1.4);
                });
            }
            c.fired();
        });

        // ---------------------------------------------------------------- Cloudlamb
        reg("ArcanaCloudlamb_Bounce", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying()) return;
            c.potion(o, "JUMP_BOOST", 80, c.bondLvl(0, 3), false);
        });
        reg("ArcanaCloudlamb_Cushion", c -> {
            ServerPlayer o = owner(c, 16);
            if (o == null || o.onGround() || o.isFallFlying() || o.getDeltaMovement().y > -0.6) return;
            if (!c.cooldown("cushion", 4)) return;
            c.potion(o, "SLOW_FALLING", (int) c.bond(40, 8), 0, true);
            c.ability();
            c.particles(o, ParticleTypes.CLOUD, 30, 0.6, 0.15, -0.2);
            c.particles(o, ParticleTypes.POOF, 10, 0.5, 0.2, 0.2);
            c.sound(o, "block.wool.place", 0.8, 1.5);
            c.sound("entity.sheep.ambient", 0.5, 1.8);
            c.fired();
        });
        reg("ArcanaCloudlamb_Gift", c -> {
            if (owner(c, 16) == null) return;
            c.drop(c.self.getRandom().nextBoolean() ? new ItemStack(Items.WOOL.white()) : new ItemStack(Items.STRING, 2));
            c.play(c.species().anim("happy"));
            c.particles(ParticleTypes.CLOUD, 12, 0.3, 0.2, 0.4);
            c.sound("entity.sheep.shear", 0.7, 1.5);
            c.fired();
        });

        // ---------------------------------------------------------------- Magpip / Golden Retriever: fetch
        reg("ArcanaMagpip_Fetch", c -> {
            ServerPlayer o = owner(c, 16);
            if (o == null || c.staying()) return;
            int b = c.bond();
            double r = b < 2 ? 10 : b <= 3 ? 12 : 15;
            // never another player's drops (thrown, aimed at them, death items) or items in a claim the owner can't build in
            List<ItemEntity> items = c.around(o.position(), r, ItemEntity.class, i -> i.isAlive() && fetchable(o, i)
                    && !c.level().getBlockState(i.blockPosition()).is(Blocks.HOPPER) && !c.level().getBlockState(i.blockPosition().below()).is(Blocks.HOPPER));
            if (items.isEmpty()) return;
            c.ability();
            c.sound("entity.allay.item_thrown", 0.6, 1.5);
            c.sound("entity.parrot.ambient", 0.6, 1.8);
            c.sched().after(10, () -> {
                items.stream().sorted(Comparator.comparingDouble(i -> i.distanceToSqr(o))).limit(24).forEach(i -> {
                    if (!i.isAlive()) return;
                    Fx.particles(i, ParticleTypes.END_ROD, 3, 0.15, 0.15, 0.2, 0.02);
                    i.teleportTo(o.getX(), o.getY(), o.getZ());
                    i.setDeltaMovement(Vec3.ZERO);
                });
                c.sound(o, "entity.allay.item_given", 0.7, 1.3);
                c.particles(o, ParticleTypes.END_ROD, 4, 0.3, 0.2, 0.3);
                c.fired();
            });
        });
        reg("ArcanaGoldenRetriever_Fetch", c -> {
            ServerPlayer o = owner(c, 16);
            if (o == null || c.staying()) return;
            var f = net.axther.aftrhrs.core.Feather.get();
            List<AbstractArrow> arrows = c.around(c.self, c.bond(12, 1.2), AbstractArrow.class,
                    a -> a.isAlive() && a.getDeltaMovement().lengthSqr() < 1.0E-4
                            && !(a.getOwner() instanceof net.minecraft.world.entity.player.Player shooter && shooter != o)   // not someone else's arrows
                            && (f == null || f.claimAllows(o, net.axther.aftrhrs.core.Loc.of(a), net.axther.aftrhrs.core.ClaimAction.BUILD)));
            arrows.stream().sorted(Comparator.comparingDouble(a -> a.distanceToSqr(c.self))).limit(6).forEach(a -> {
                Fx.particles(a, ParticleTypes.CLOUD, 4, 0.2, 0.1, 0.2, 0.02);
                a.teleportTo(o.getX(), o.getY(), o.getZ());
                c.ability();
                c.particles(o, ParticleTypes.HAPPY_VILLAGER, 4, 0.3, 0.2, 0.3);
                c.sound(o, "entity.item.pickup", 0.6, 0.9);
                c.fired();
            });
        });
        reg("ArcanaGoldenRetriever_Swim", c -> {
            ServerPlayer o = owner(c, 10);
            if (o == null || !Conditions.inWater(c.self) || !Conditions.inWater(o)) return;
            c.potion(o, "DOLPHINS_GRACE", 60, 0, false);
            c.particles(ParticleTypes.BUBBLE_POP, 4, 0.3, 0.3, 0.5);
        });

        // ---------------------------------------------------------------- Beagle: scent
        reg("ArcanaBeagle_Scent", c -> {
            ServerPlayer o = owner(c, 12);
            if (o == null || c.staying()) return;
            double r = c.bond(24, 2.4);
            List<Mob> stock = c.around(o, r, Mob.class, m -> FARM.contains(m.getType()));
            if (stock.isEmpty()) return;
            Mob nearest = stock.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(o))).orElseThrow();
            c.ability();
            c.sound("entity.fox.sniff", 0.6, 0.9);
            c.sched().seq().wait(12).run(() -> c.sound("entity.fox.sniff", 0.6, 1.0)).wait(20).run(() -> {
                CreatureSfx.play(c.mob, "ambient", 1.1f, 1.15f);   // the beagle bays: found it (entity.wolf.howl is no sound in 26.3)
                c.line(c.self, nearest, new DustParticleOptions(0x66E08A, 0.8f), 0.6, 0.5, 0.6);
                for (Mob m : stock) {
                    m.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.GLOWING, (int) c.bond(160, 32), 0, false, false));
                    c.particles(m, ParticleTypes.HAPPY_VILLAGER, 4, 0.3, 0.3, 0.8);
                }
                c.fired();
            }).start();
        });

        // ---------------------------------------------------------------- Corgi: herd
        reg("ArcanaCorgi_Herd", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || !o.isShiftKeyDown()) return;
            double r = c.bond(14, 1.4);
            List<Mob> stock = c.around(o, r, Mob.class, m -> HERD.contains(m.getType()));
            if (stock.isEmpty()) return;
            if (c.cooldown("herdbark", 2)) {
                c.ability();
                c.sched().after(6, () -> {
                    c.sound("entity.wolf.ambient", 0.7, 1.7);
                    c.particles(ParticleTypes.NOTE, 1, 0, 0, 1.0);
                });
                c.sched().after(16, () -> c.sound("entity.wolf.ambient", 0.6, 1.8));
            }
            for (Mob m : stock) {
                if (m.distanceTo(o) < 2.5) continue;
                Vec3 pull = o.position().subtract(m.position()).normalize().scale(0.6);
                m.setDeltaMovement(m.getDeltaMovement().add(pull.x, 0.05, pull.z));
                c.particles(m, ParticleTypes.CLOUD, 1, 0.2, 0.05, 0.1);
            }
            c.fired();
        });

        // ---------------------------------------------------------------- Dachshund: badger, pests, truffles
        reg("ArcanaDachshund_Badger", c -> {
            if (!c.hasOwner() || !(c.target instanceof LivingEntity t) || !PESTS.contains(t.getType())) return;
            t.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), (float) c.bond(5, 1));
            c.particles(t, ParticleTypes.CRIT, 6, 0.2, 0.2, 0.3);
        });
        reg("ArcanaDachshund_PestHunt", c -> {
            ServerPlayer o = owner(c, 16);
            if (o == null || c.staying()) return;
            c.around(o, 8, Mob.class, m -> PESTS.contains(m.getType())).stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(o)))
                    .ifPresent(m -> {
                        c.self.setTarget(m);
                        if (c.cooldown("yip", 4)) c.sound("entity.wolf.growl", 0.5, 1.7);
                    });
        });
        reg("ArcanaDachshund_Truffle", c -> {
            ServerPlayer o = owner(c, 12);
            if (o == null || c.staying()) return;
            int r = (int) Math.round(c.bond(12, 1.2));
            blocks(o, r, r, st -> st.is(Blocks.CHEST) || st.is(Blocks.BARREL) || st.is(Blocks.TRAPPED_CHEST)).stream()
                    .min(Comparator.comparingDouble(p -> p.distToCenterSqr(o.position()))).ifPresent(p -> {
                        Vec3 at = Vec3.atCenterOf(p);
                        c.ability();
                        c.sound("entity.fox.sniff", 0.6, 1.2);
                        c.sched().seq().wait(14).run(() -> c.sound("entity.fox.sniff", 0.6, 1.3)).wait(14).run(() -> {
                            c.sound("entity.wolf.ambient", 0.6, 1.7);
                            c.line(c.self, at, new DustParticleOptions(0xF2B23C, 0.9f), 0.5, 0.3);
                            Fx.particles(c.level(), at, ParticleTypes.WAX_ON, 14, 0.35, 0.35, 0.02);
                            Fx.particles(c.level(), at, new DustParticleOptions(0xF2B23C, 1.2f), 10, 0.3, 0.3, 0.02);
                            c.fired();
                        }).start();
                    });
        });

        // ---------------------------------------------------------------- Calico Kitten: purr therapy
        reg("ArcanaCalicoKitten_Purr", c -> {
            ServerPlayer o = owner(c, 6);
            if (o == null) return;
            if (o.isShiftKeyDown() && o.distanceTo(c.self) < 3) {
                c.ability();
                c.sound("entity.cat.purr", 0.9, 1.35);
                c.potion(o, "REGENERATION", 60, 1, false);
                c.hearts(c.self, 1, 0.55);
                c.fired();
            }
            if (o.getHealth() < o.getMaxHealth() * 0.75f && c.cooldown("soothe", 10)) {
                c.ability();
                c.sound("entity.cat.purr", 1.0, 1.2);
                c.potion(o, "REGENERATION", (int) c.bond(100, 12), 0, false);
                c.hearts(o, 3, 1.2);
                c.hearts(c.self, 1, 0.55);
                c.fired();
            }
        });

        // ---------------------------------------------------------------- Black Cat: luck, morning gift
        reg("ArcanaBlackCat_Luck", c -> {
            ServerPlayer o = owner(c, 12);
            if (o == null) return;
            c.potion(o, "LUCK", 140, c.bondLvl(1, 3), false);
        });
        reg("ArcanaBlackCat_NightWatch", c -> {
            if (c.hasOwner() && Conditions.timeIn(c.level(), 12500, 23999) && c.mob.var("gift_armed") != 1) c.mob.setVar("gift_armed", 1);
        });
        reg("ArcanaBlackCat_MorningGift", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || !Conditions.timeIn(c.level(), 0, 3000) || c.mob.var("gift_armed") != 1) return;
            c.mob.setVar("gift_armed", 0);
            c.ability();
            c.sound("entity.cat.purreow", 0.8, 1.0);
            c.sched().after(12, () -> {
                c.drop(weighted(c, BLACKCAT));
                if (c.self.getRandom().nextDouble() < c.bond(0, 0.1)) c.drop(weighted(c, BLACKCAT));
                c.particles(ParticleTypes.WITCH, 8, 0.3, 0.2, 0.3);
                c.sound("entity.item.pickup", 0.5, 0.8);
                c.actionBar(o, "gift", "&5<pet> &7left a little gift at its feet.");
                c.fired();
            });
        });

        // ---------------------------------------------------------------- Tabby Cat: creeper deterrent
        reg("ArcanaTabbyCat_Deter", c -> {
            ServerPlayer o = owner(c, 12);
            if (o == null) return;
            for (Creeper cr : c.around(o, c.bond(7, 0.7), Creeper.class, Entity::isAlive)) {
                boolean primed = cr.getSwellDir() > 0 || cr.isIgnited();
                throwFrom(cr, o.position(), primed ? 12 : 3, primed ? 4 : 1);
                c.particles(cr, primed ? ParticleTypes.ANGRY_VILLAGER : ParticleTypes.SMOKE, 3, 0.3, 0.3, primed ? 1.2 : 0.8);
                if (primed && c.cooldown("hiss", 2)) {
                    c.ability();
                    c.sound("entity.cat.hiss", 1.0, 1.0);
                    c.particles(ParticleTypes.ANGRY_VILLAGER, 2, 0.2, 0.1, 0.6);
                }
                c.fired();
            }
        });

        // ---------------------------------------------------------------- Maine Coon: night warden
        reg("ArcanaMaineCoon_Ward", c -> {
            ServerPlayer o = owner(c, 16);
            if (o == null) return;
            for (Mob m : c.around(o, c.bond(16, 1.6), Mob.class, m -> m.getType() == EntityTypes.PHANTOM)) {
                throwFrom(m, o.position(), 10, 4);
                c.particles(m, ParticleTypes.WITCH, 6, 0.4, 0.4, 0.3);
            }
            for (Creeper cr : c.around(o, 6, Creeper.class, Entity::isAlive)) {
                throwFrom(cr, o.position(), 3, 1);
                c.particles(cr, ParticleTypes.SMOKE, 3, 0.2, 0.2, 0.8);
            }
            if (!c.around(c.self, 24, Mob.class, m -> m.getType() == EntityTypes.PHANTOM).isEmpty()) hiss(c);
        });
        reg("ArcanaMaineCoon_NightWatch", c -> {
            if (!c.hasOwner() || !Conditions.night(c.level())) return;
            c.around(c.self, 8, LivingEntity.class, Conditions::isWildMonster).stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(c.self)))
                    .ifPresent(m -> {
                        c.self.getLookControl().setLookAt(m);
                        hiss(c);
                    });
        });

        // ---------------------------------------------------------------- Husky / Copper Husky
        reg("ArcanaHusky_Sled", c -> {
            ServerPlayer o = owner(c, 10);
            if (o == null || c.staying() || !o.isSprinting()) return;
            boolean snow = onSnow(o);
            c.potion(o, "SPEED", 40, snow ? c.bondLvl(1, 4) : c.bondLvl(0, 4), false);
            if (snow) c.particles(o, ParticleTypes.SNOWFLAKE, 5, 0.3, 0.05, 0.1);
            c.potion(c.self, "SPEED", 40, 1, false);
        });
        reg("ArcanaHusky_Warm", c -> {
            ServerPlayer o = owner(c, 10);
            if (o != null) o.setTicksFrozen(0);
        });
        reg("ArcanaHuskyCopper_Trail", c -> {
            ServerPlayer o = owner(c, 10);
            if (o == null || c.staying() || !o.isSprinting()) return;
            c.potion(o, "SPEED", 40, c.bondLvl(0, 4), false);
            c.potion(c.self, "SPEED", 40, 1, false);
            if (o.getFoodData().getFoodLevel() < 20 && c.cooldown("stamina", c.bond(8, -0.8))) {
                o.getFoodData().eat(1, 0.5f);
                c.particles(o, new DustParticleOptions(0xD9823B, 0.9f), 6, 0.3, 0.2, 0.9);
                c.sound("entity.wolf.pant", 0.5, 1.2);
                c.fired();
            }
        });
        reg("ArcanaHusky_Howl", c -> {
            if (!c.hasOwner() || !Conditions.night(c.level()) || c.mob.var("howling") == 1) return;
            if (c.self.getRandom().nextFloat() >= 0.25f || !c.cooldown("howl", 60)) return;
            howl(c);
            c.sched().after(20, () -> {
                for (Mob m : c.around(c.self, 16, Mob.class, m -> m instanceof ArcanaMob am
                        && (am.species().mythicId.equals("ArcanaHusky") || am.species().mythicId.equals("ArcanaHuskyCopper")))) {
                    Abilities.signal((ArcanaMob) m, "ARCANAHOWL", c.self);
                }
            });
        });
        reg("ArcanaHusky_HowlBack", c -> {
            if (!c.hasOwner() || c.mob.var("howling") == 1 || !c.cooldown("howlback", 30)) return;
            c.sched().after(14, () -> howl(c));
        });

        // ---------------------------------------------------------------- Saint Bernard: rescue
        reg("ArcanaSaintBernard_RescueCheck", c -> {
            ServerPlayer o = owner(c, 24);
            if (o == null) return;
            if (c.staying() && o.distanceTo(c.self) >= 3.5) return;
            boolean need = o.getHealth() < o.getMaxHealth() * 0.25f || o.isFullyFrozen()
                    || c.level().getBlockState(o.blockPosition()).is(Blocks.POWDER_SNOW);
            if (!need || !c.cooldown("rescue", c.bond(90, -8))) return;
            c.mob.setVar("rescuing", 1);
            c.sound("entity.wolf.whine", 1.0, 0.6);
            c.particles(ParticleTypes.SMALL_FLAME, 4, 0.2, 0.2, 0.5);
            for (int i = 0; i < 16; i++) {
                c.sched().after(i * 10, () -> {
                    if (c.mob.var("rescuing") != 1 || !o.isAlive()) return;
                    if (o.distanceTo(c.self) < 3.5) offerBarrel(c, o);
                    else c.self.getNavigation().moveTo(o, 1.4);
                });
            }
            c.sched().after(170, () -> c.mob.setVar("rescuing", 0));
        });
        reg("ArcanaSaintBernard_StormWarmth", c -> {
            ServerPlayer o = owner(c, 12);
            if (o == null || !c.level().isRaining() || !Conditions.outside(o) || !Conditions.cold(o)) return;
            c.potion(o, "RESISTANCE", 60, 0, false);
            c.particles(ParticleTypes.SNOWFLAKE, 2, 0.4, 0.2, 1.2);
        });

        // ---------------------------------------------------------------- Barkling: guard
        reg("ArcanaBarkling_GuardTick", c -> {
            if (!c.hasOwner()) return;
            boolean night = Conditions.night(c.level());
            int guarding = c.mob.var("guarding");
            if (c.staying() && night && guarding != 1) {
                c.mob.setVar("guarding", 1);
                c.play("stand_up");
                c.mob.hold("stand_guard");
                c.sound("entity.creaking.activate", 0.6, 1.6);
            } else if (c.staying() && !night && guarding == 1) {
                c.mob.setVar("guarding", 0);
                c.mob.hold(c.species().anim("sit"));
                c.sound("entity.creaking.deactivate", 0.5, 1.6);
            } else if (!c.staying() && guarding == 1) {
                c.mob.setVar("guarding", 0);
                if ("stand_guard".equals(c.mob.holdClip())) c.mob.hold("");
            }
            if (c.staying() && night) {
                List<LivingEntity> foes = c.around(c.self, 12, LivingEntity.class, Conditions::isWildMonster);
                if (foes.isEmpty() || !c.cooldown("volley", 3)) return;
                c.play("root_call");
                c.sound("entity.creaking.attack", 0.8, 1.4);
                List<LivingEntity> hit = foes.stream().sorted(Comparator.comparingDouble(e -> e.distanceToSqr(c.self))).limit(4).toList();
                c.sched().after(12, () -> {
                    for (LivingEntity e : hit) {
                        if (!e.isAlive()) continue;
                        root(c, e);
                        e.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), (float) c.bond(3, 0.4));
                    }
                    c.fired();
                });
            }
        });
        reg("ArcanaBarkling_Ward", c -> {
            // while following within 16 blocks, monsters that hit the owner are rooted (see PetSkills.ownerHurt)
            // the ward itself is event driven: ArcanaEntities' owner-damage hook calls ownerHurt
        });

        // ---------------------------------------------------------------- Hushwing
        reg("ArcanaHushwing_Hush", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || c.sched().onCooldown("hush")) return;   // no 17^3 scan while it would not fire
            if (!Conditions.blockNear(o, 8, 8, st -> st.is(Blocks.SCULK_SENSOR) || st.is(Blocks.CALIBRATED_SCULK_SENSOR) || st.is(Blocks.SCULK_SHRIEKER))) return;
            if (!c.cooldown("hush", 15)) return;
            c.play("hush");
            c.sound("block.sculk_sensor.clicking_stop", 0.4, 1.4);
            c.sched().after(8, () -> c.particles(ParticleTypes.SCULK_SOUL, 4, 0.3, 0.2, 0.5));
            c.fired();
        });
        reg("ArcanaHushwing_Veil", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || c.bond() < 3 || !o.hasEffect(MobEffects.DARKNESS)) return;
            o.removeEffect(MobEffects.DARKNESS);
            c.particles(o, ParticleTypes.SCULK_SOUL, 3, 0.3, 0.3, 1.4);
            c.sound("block.sculk_catalyst.bloom", 0.3, 1.6);
            c.fired();
        });
        reg("ArcanaHushwing_FlyStates", c -> {
        });
        reg("ArcanaMagpip_FlyStates", c -> {
        });

        // ---------------------------------------------------------------- Sporeling
        reg("ArcanaSporeling_Spores", c -> {
            if (!c.hasOwner()) return;
            List<BlockPos> crops = blocks(c.self, (int) Math.round(c.bond(8, 0.4)), 2, st -> st.is(BlockTags.CROPS) || st.is(Blocks.MELON_STEM)
                    || st.is(Blocks.PUMPKIN_STEM) || st.is(Blocks.TRIPWIRE));
            if (crops.isEmpty()) return;
            if (!c.staying()) c.ability();
            c.sched().after(10, () -> {
                c.sound("entity.puffer_fish.blow_out", 0.4, 1.7);
                c.particles(c.self, ParticleTypes.MYCELIUM, 24, 1.2, 0.6, 1.2, 0.02);
            });
            c.sched().after(16, () -> crops.forEach(p -> Fx.particles(c.level(), Vec3.atCenterOf(p), ParticleTypes.MYCELIUM, 3, 0.3, 0.2, 0.02)));
        });
        reg("ArcanaSporeling_Boost", c -> {
            if (!c.cooldown("boost", 2)) return;
            if (!c.staying()) c.play("spore_puff");
            c.sound("block.amethyst_block.chime", 0.6, 1.6);
            c.particles(ParticleTypes.HAPPY_VILLAGER, 6, 0.4, 0.3, 1.0);
            c.fired();
        });

        // ---------------------------------------------------------------- Canopip
        reg("ArcanaCanopip_SeedFind", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || c.self.getRandom().nextDouble() >= c.bond(0.15, 0.02)) return;
            c.ability();
            c.sound("block.rooted_dirt.break", 0.7, 1.3);
            c.particles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()), 10, 0.3, 0.1, 0.1);
            c.sched().after(26, () -> {
                String[] seeds = {"rice", "tomato", "grape", "pepper", "blueberry", "tea", "corn"};
                String seed = seeds[c.self.getRandom().nextInt(seeds.length)];
                Item item = CreatureUtil.customItem("arcana_seeds_" + seed);
                if (item != null) {
                    ItemStack s = new ItemStack(item);
                    if (!o.getInventory().add(s)) o.getInventory().placeItemBackInInventory(s, net.minecraft.util.Prediction.SERVER_ONLY);
                }
                c.actionBar(o, "seeds", "&7<pet> &7dug up some &f<seed> Seeds&7.",
                        net.axther.aftrhrs.core.Text.unparsed("seed", Character.toUpperCase(seed.charAt(0)) + seed.substring(1)));
                c.sound(o, "entity.item.pickup", 0.6, 1.4);
                c.particles(ParticleTypes.HAPPY_VILLAGER, 5, 0.3, 0.3, 0.8);
                c.fired();
            });
        });
        reg("ArcanaCanopip_Mimic", c -> {
            ServerPlayer o = owner(c, 5);
            if (o == null || c.staying() || c.moving() || !o.isShiftKeyDown() || !c.cooldown("mimic", 8)) return;
            if (c.self.getRandom().nextBoolean()) {
                c.play("peekaboo");
                c.sched().after(26, () -> c.sound("entity.fox.ambient", 0.5, 2.0));
            } else {
                c.play("scratch_head");
                c.sound("entity.fox.sniff", 0.5, 1.6);
            }
        });
        reg("ArcanaCanopip_Treetop", c -> {
            if (!c.hasOwner() || c.staying() || c.moving() || c.self.getRandom().nextFloat() >= 0.4f) return;
            if (!Conditions.blockNear(c.self, 4, 4, st -> st.is(Blocks.JUNGLE_LEAVES) || st.is(Blocks.BAMBOO) || st.is(Blocks.VINE) || st.is(Blocks.JUNGLE_LOG))) return;
            c.play("swing");
            c.sound("block.vine.step", 0.7, 1.1);
            c.sched().after(10, () -> c.particles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.JUNGLE_LEAVES.defaultBlockState()), 10, 0.6, 0.4, 1.2));
            c.sched().after(20, () -> c.sound("entity.fox.ambient", 0.6, 1.9));
        });
        reg("ArcanaCanopip_Replanted", c -> {
            if (!c.cooldown("replanted", 2)) return;
            if (!c.staying()) c.ability();
            c.sound("item.crop.plant", 0.7, 1.2);
            c.particles(ParticleTypes.HAPPY_VILLAGER, 4, 0.4, 0.2, 0.3);
            c.fired();
        });

        // ---------------------------------------------------------------- Geode Crawler
        reg("ArcanaGeodeCrawler_Prospect", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || Conditions.outside(o) || !c.cooldown("prospect", 40)) return;
            c.ability();
            c.sound("block.amethyst_block.resonate", 0.8, 1.2);
            c.particles(ParticleTypes.WAX_OFF, 12, 0.6, 0.4, 0.8);
            int r = (int) Math.round(c.bond(8, 0.8)), ry = (int) Math.round(c.bond(5, 0.5));
            c.sched().after(13, () -> {
                for (BlockPos p : blocks(o, r, ry, st -> ORES.contains(st.getBlock()))) {
                    Vec3 at = Vec3.atCenterOf(p);
                    Fx.particles(c.level(), at, ParticleTypes.GLOW, 8, 0.45, 0.45, 0.02);
                    c.line(c.self, at, ParticleTypes.WAX_OFF, 0.6, 0.5);
                    Fx.sound(c.self, "block.amethyst_block.chime", 0.5f, 1.4f);
                }
                c.fired();
            });
        });
        reg("ArcanaCrawler_CrystalBurst", c -> {
            if (c.hasOwner() || c.level().getNearestPlayer(c.self, 5) == null || !c.cooldown("burst", 9)) return;
            c.play("crystal_burst");
            c.sched().after(14, () -> {
                for (Player p : c.around(c.self, 4, Player.class, p -> !p.isCreative() && !p.isSpectator())) {
                    p.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), 5);
                }
                c.particles(c.self, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.AMETHYST_BLOCK.defaultBlockState()), 40, 1.5, 1, 0.5, 0.05);
                c.sound("block.amethyst_cluster.break", 1.2, 0.8);
            });
        });

        // ---------------------------------------------------------------- Cinder Imp
        reg("ArcanaImp_Leap", c -> {
            LivingEntity t = c.self.getTarget();
            if (t == null || !c.cooldown("leap", 4)) return;
            double d = t.distanceTo(c.self);
            if (d < 3 || d > 10) return;
            // telegraphed: the leap clip crouches and coils for 0.35 s, then it springs
            c.play("leap");
            c.sched().after(7, () -> {
                if (!c.self.isAlive() || !t.isAlive() || !c.self.onGround()) return;
                Vec3 dir = t.position().subtract(c.self.position());
                c.self.setDeltaMovement(dir.x * 0.18, 0.55, dir.z * 0.18);
            });
        });
        reg("ArcanaCinderImp_Thaw", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || !c.level().getBlockState(o.blockPosition()).is(Blocks.POWDER_SNOW)) return;
            o.setTicksFrozen(0);
            c.particles(o, ParticleTypes.SMALL_FLAME, 4, 0.4, 0.3, 0.4);
            c.sound(o, "block.fire.ambient", 0.3, 1.6);
        });
        reg("ArcanaCinderImp_EmberWard", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || !o.isOnFire() || !c.cooldown("emberward", c.bond(60, -6))) return;
            c.ability();
            o.clearFire();
            c.potion(o, "FIRE_RESISTANCE", 200, 0, true);
            c.line(c.self, o, ParticleTypes.FLAME, 0.4, 0.5, 1.0);
            c.particles(o, ParticleTypes.FLAME, 16, 0.5, 0.6, 1);
            c.sound("item.firecharge.use", 0.6, 1.6);
            c.sound("entity.generic.eat", 0.6, 1.4);
            c.fired();
        });

        // ---------------------------------------------------------------- Rime Wraith
        reg("ArcanaWraith_Wail", c -> {
            if (c.hasOwner() || c.level().getNearestPlayer(c.self, 8) == null || !c.cooldown("wail", 10)) return;
            c.play("wail");
            c.sched().after(10, () -> {
                for (Player p : c.around(c.self, 6, Player.class, p -> !p.isCreative() && !p.isSpectator())) c.potion(p, "SLOWNESS", 80, 1, true);
                c.particles(c.self, ParticleTypes.SNOWFLAKE, 40, 3, 1, 0, 0.02);
                c.sound("entity.stray.ambient", 1.2, 0.6);
            });
        });
        reg("ArcanaRimeWraith_FrostWard", c -> {
            ServerPlayer o = owner(c, 12);
            if (o == null || c.staying()) return;
            for (LivingEntity e : c.around(o, c.bond(5, 0.6), LivingEntity.class, e -> e != c.self && Conditions.isWildMonster(e))) {
                c.potion(e, "SLOWNESS", 60, c.bondLvl(0, 3), false);
                c.particles(e, ParticleTypes.SNOWFLAKE, 6, 0.3, 0.4, 1);
                if (c.cooldown("chillfx", 6)) {
                    c.ability();
                    c.line(c.self, e, ParticleTypes.SNOWFLAKE, 0.5, 1, 1);
                    c.sound("block.powder_snow.place", 0.7, 0.9);
                }
                c.fired();
            }
        });
        reg("ArcanaRimeWraith_Cooler", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying()) return;
            int r = (int) Math.round(c.bond(3, 0.6));
            if (!Conditions.blockNear(o, r, r, st -> st.getFluidState().is(Fluids.LAVA) || st.getFluidState().is(Fluids.FLOWING_LAVA))) return;
            c.potion(o, "FIRE_RESISTANCE", 100, 0, false);
            c.particles(o, ParticleTypes.SNOWFLAKE, 8, 0.5, 0.2, 0.1);
            if (c.cooldown("coolerfx", 10)) {
                c.ability();
                c.line(c.self, o, ParticleTypes.SNOWFLAKE, 0.5, 1, 1);
                c.sound("block.powder_snow.place", 0.7, 1.2);
            }
            c.fired();
        });

        // ---------------------------------------------------------------- Volt Wisp
        reg("ArcanaWisp_Zap", c -> {
            LivingEntity t = c.self.getTarget();
            if (t == null || t.distanceTo(c.self) >= 8 || !c.cooldown("zap", 4)) return;
            c.play("zap");
            c.sched().after(8, () -> {
                if (!t.isAlive()) return;
                c.line(c.self, t, ParticleTypes.ELECTRIC_SPARK, 0.33, 0.3, 1);
                t.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), 4);
                c.sound(t, "entity.lightning_bolt.impact", 0.6, 1.6);
            });
        });
        reg("ArcanaVoltWisp_Charge", c -> {
            ServerPlayer o = owner(c, 8);
            if (o == null || c.staying() || !c.cooldown("charge", c.bond(55, -4))) return;
            c.ability();
            c.sound("block.beacon.power_select", 0.5, 2);
            c.sched().after(12, () -> {
                c.potion(o, "HASTE", 200, 0, true);
                c.potion(o, "SPEED", 200, 0, true);
                c.line(c.self, o, ParticleTypes.ELECTRIC_SPARK, 0.3, 0.3, 1);
                c.particles(o, ParticleTypes.ELECTRIC_SPARK, 20, 0.5, 0.8, 1);
                c.sound(o, "entity.lightning_bolt.impact", 0.3, 2);
                c.fired();
            });
        });
        reg("ArcanaVoltWisp_Guard", c -> {
            ServerPlayer o = owner(c, 10);
            if (o == null) return;
            for (LivingEntity e : c.around(o, 5, LivingEntity.class, e -> e != c.self && Conditions.isWildMonster(e)
                    && e.getType() != EntityTypes.ENDERMAN && e.getType() != EntityTypes.PIGLIN && e.getType() != EntityTypes.ZOMBIFIED_PIGLIN)) {
                c.play("zap");
                c.line(c.self, e, ParticleTypes.ELECTRIC_SPARK, 0.25, 0.3, 1);
                e.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), (float) c.bond(2, 0.4));
                c.sound(e, "entity.lightning_bolt.impact", 0.4, 1.8);
                c.fired();
            }
        });

        // ---------------------------------------------------------------- Lurefin (wild only)
        reg("ArcanaLurefin_Lure", c -> {
            if (!Conditions.inWater(c.self)) return;
            for (Player p : c.around(c.self, 14, Player.class, p -> !p.isCreative() && !p.isSpectator() && Conditions.inWater(p) && p.distanceTo(c.self) > 3)) {
                if (c.cooldown("lurefx", 3)) {
                    c.ability();
                    c.particles(ParticleTypes.GLOW, 16, 0.3, 0.3, 1.0);
                    c.sound("block.beacon.ambient", 0.8, 1.9);
                }
                Vec3 pull = c.self.position().subtract(p.position()).normalize().scale(0.5);
                p.push(pull.x, pull.y, pull.z);
                p.needsSync = true;
                c.particles(p, ParticleTypes.GLOW, 6, 0.4, 0.4, 1.0);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    static final Set<EntityType<?>> FARM = Set.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN, EntityTypes.RABBIT, EntityTypes.GOAT);
    static final Set<EntityType<?>> HERD = Set.of(EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.PIG, EntityTypes.CHICKEN, EntityTypes.GOAT);
    static final Set<EntityType<?>> PESTS = Set.of(EntityTypes.SILVERFISH, EntityTypes.ENDERMITE, EntityTypes.CAVE_SPIDER);
    static final Set<Block> ORES = Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.REDSTONE_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);

    /** ArcanaBurrowpip_Finds (item, min, max, weight). */
    static final Object[][] BURROWPIP = {{Items.FLINT, 1, 2, 20.0}, {Items.COAL, 1, 2, 18.0}, {Items.CLAY_BALL, 1, 3, 16.0},
            {Items.IRON_NUGGET, 1, 3, 14.0}, {Items.COPPER_INGOT, 1, 1, 12.0}, {Items.GOLD_NUGGET, 1, 2, 10.0},
            {Items.LAPIS_LAZULI, 1, 2, 4.0}, {Items.AMETHYST_SHARD, 1, 1, 3.5}, {Items.EMERALD, 1, 1, 2.0}, {Items.DIAMOND, 1, 1, 0.5}};
    /** ArcanaBlackCat_Gifts. */
    static final Object[][] BLACKCAT = {{Items.STRING, 1, 2, 32.0}, {Items.FEATHER, 1, 2, 28.0}, {Items.RABBIT_HIDE, 1, 1, 22.0},
            {Items.RABBIT_FOOT, 1, 1, 12.0}, {Items.PHANTOM_MEMBRANE, 1, 1, 6.0}};
    /** ArcanaShellpip_Finds (custom item ids). */
    static final Object[][] SHELLPIP = {{"arcana_seashell", 28.0}, {"arcana_sand_dollar", 18.0}, {"arcana_driftwood", 18.0},
            {"arcana_sea_glass_shard_white", 11.0}, {"arcana_sea_glass_shard_aqua", 9.0}, {"arcana_sea_glass_shard_green", 7.0},
            {"arcana_sea_glass_shard_blue", 6.0}, {"arcana_sea_glass_shard_amber", 3.0}};

    static ItemStack weighted(Ctx c, Object[][] table) {
        double total = 0;
        for (Object[] r : table) total += (double) r[3];
        double x = c.self.getRandom().nextDouble() * total;
        for (Object[] r : table) {
            x -= (double) r[3];
            if (x <= 0) {
                int lo = (int) r[1], hi = (int) r[2];
                return new ItemStack((Item) r[0], lo + (hi > lo ? c.self.getRandom().nextInt(hi - lo + 1) : 0));
            }
        }
        return new ItemStack((Item) table[0][0]);
    }

    static String weightedId(Ctx c, Object[][] table) {
        double total = 0;
        for (Object[] r : table) total += (double) r[1];
        double x = c.self.getRandom().nextDouble() * total;
        for (Object[] r : table) {
            x -= (double) r[1];
            if (x <= 0) return (String) r[0];
        }
        return (String) table[0][0];
    }

    /**
     * Every matching block in the (squashed) sphere around {@code center}, in no particular order. Never loads chunks
     * (one that is not loaded has none) and skips sections that cannot hold a match ({@link Conditions#scanBox}).
     */
    static List<BlockPos> blocks(Entity center, int r, int ry, java.util.function.Predicate<BlockState> test) {
        List<BlockPos> out = new java.util.ArrayList<>();
        BlockPos c = center.blockPosition();
        double lim = (r + 0.5) * (r + 0.5) + ry * ry;
        Conditions.scanBox(center.level(), c.getX() - r, c.getY() - ry, c.getZ() - r, c.getX() + r, c.getY() + ry, c.getZ() + r, test, p -> {
            if (p.distSqr(c) <= lim) out.add(p.immutable());
            return false;   // visit the whole box
        });
        return out;
    }

    private static void bloomGrass(Ctx c, BlockPos floor) {
        if (!c.level().getBlockState(floor).is(Blocks.GRASS_BLOCK)) return;
        if (BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), c.level(), floor)) c.level().levelEvent(1505, floor, 15);
        Fx.particles(c.level(), Vec3.atCenterOf(floor).add(0, 1.2, 0), ParticleTypes.HAPPY_VILLAGER, 12, 1.5, 0.3, 0.02);
    }

    private static void dig(Ctx c, Block particle, String s1, String s2, int d1, int d2, int d3, Runnable find,
                            String doneSound, double v, double p) {
        BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, particle.defaultBlockState());
        c.ability();
        c.sched().seq().wait(6).run(() -> {
            c.sound(s1, 0.7, 1.3);
            c.particles(dust, 10, 0.3, 0.1, 0.1);
        }).wait(d1).run(() -> {
            c.sound(s2, 0.7, 1.4);
            c.particles(dust, 10, 0.3, 0.1, 0.1);
        }).wait(d2).run(() -> {
            c.sound(s1, 0.7, 1.5);
            c.particles(dust, 10, 0.3, 0.1, 0.1);
        }).wait(d3).run(() -> {
            find.run();
            c.sound(doneSound, v, p);
            c.particles(ParticleTypes.HAPPY_VILLAGER, 6, 0.3, 0.3, 0.6);
            c.fired();
        }).start();
    }

    /** MythicMobs throw{velocity;velocityY;fromOrigin}: knocks {@code e} away from {@code from}. */
    static void throwFrom(Entity e, Vec3 from, double velocity, double velocityY) {
        Vec3 d = e.position().subtract(from).multiply(1, 0, 1);
        if (d.lengthSqr() < 1.0E-4) d = new Vec3(1, 0, 0);
        d = d.normalize().scale(velocity / 10.0);
        e.setDeltaMovement(d.x, velocityY / 10.0, d.z);
        e.needsSync = true;
    }

    private static void hiss(Ctx c) {
        if (!c.cooldown("hiss", 6)) return;
        c.ability();
        c.sound("entity.cat.hiss", 1.0, 0.85);
        c.particles(ParticleTypes.WITCH, 4, 0.15, 0.1, 0.5);
    }

    /** The howl itself is the profile's ability_howl / ability_howlback (played by c.ability(), delayed whine layers). */
    private static void howl(Ctx c) {
        c.mob.setVar("howling", 1);
        c.sched().after(100, () -> c.mob.setVar("howling", 0));
        c.ability();
        c.fired();
    }

    private static boolean onSnow(Player p) {
        BlockState on = p.level().getBlockState(p.blockPosition().below());
        BlockState at = p.level().getBlockState(p.blockPosition());
        return on.is(Blocks.SNOW_BLOCK) || on.is(Blocks.SNOW) || on.is(Blocks.POWDER_SNOW) || on.is(BlockTags.ICE)
                || at.is(Blocks.SNOW) || at.is(Blocks.POWDER_SNOW);
    }

    private static void offerBarrel(Ctx c, ServerPlayer o) {
        c.mob.setVar("rescuing", 0);
        c.self.getLookControl().setLookAt(o);
        c.ability();
        c.sound("block.barrel.open", 0.8, 1.2);
        c.sched().after(14, () -> {
            c.potion(o, "REGENERATION", 80, 1, true);
            for (int i = 0; i < 80; i++) c.sched().after(i, () -> o.setTicksFrozen(0));
            if (c.level().getBlockState(o.blockPosition()).is(Blocks.POWDER_SNOW)) {
                o.setDeltaMovement(0, 0.75, 0);
                o.needsSync = true;
                c.particles(o, ParticleTypes.SNOWFLAKE, 12, 0.4, 0.3, 0.5);
            }
            c.sound(o, "entity.generic.drink", 0.8, 0.9);
            c.particles(o, ParticleTypes.SMALL_FLAME, 10, 0.4, 0.4, 1.0);
            c.hearts(o, 3, 1.8);
            c.actionBar(o, "barrel", "&6<pet> &7brings you its warm barrel.");
            c.fired();
        });
    }

    /** Barkling root: Slowness IV for 3 s with a burst of pale oak splinters. */
    static void root(Ctx c, LivingEntity e) {
        c.potion(e, "SLOWNESS", 60, 3, false);
        c.particles(e, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PALE_OAK_LOG.defaultBlockState()), 14, 0.35, 0.15, 0.1);
        c.sound(e, "block.rooted_dirt.break", 0.8, 0.8);
    }

    /** Barkling ward: a monster that hit the barkling's owner is rooted (called from ArcanaEntities' damage hook). */
    static void ownerHurt(ServerPlayer owner, LivingEntity attacker) {
        if (!Conditions.isWildMonster(attacker)) return;
        for (ArcanaCreature c : Conditions.nearby(owner, 16, ArcanaCreature.class,
                x -> x.species().mythicId.equals("ArcanaBarkling") && x.isOwnedBy(owner) && !x.staying())) {
            if (!c.scheduler().ready("wardhit", 20)) continue;
            Ctx ctx = new Ctx(c, "ArcanaBarkling_Ward", attacker, attacker);
            root(ctx, attacker);
            return;
        }
    }

    /** Moving light blocks (Wickling LIGHT in air; Driftglow / sea light waterlogged LIGHT in still water). */
    static final class Lights {
        private Lights() {
        }

        /** Places / moves the light to the creature's feet (or head). True if a light is lit now. */
        static boolean move(ArcanaMob mob, String key, int level, boolean water) {
            ServerLevel lvl = (ServerLevel) mob.self().level();
            BlockPos feet = mob.self().blockPosition();
            int keyHash = (int) (feet.asLong() ^ (feet.asLong() >>> 32)) * 31 + level;
            if (mob.var(key + "_set") == 1 && mob.var(key + "_key") == keyHash) return true;
            off(mob, key);
            mob.setVar(key + "_key", keyHash);
            for (BlockPos p : new BlockPos[] {feet, feet.above()}) {
                BlockState st = lvl.getBlockState(p);
                boolean ok = water ? st.getFluidState().is(Fluids.WATER) && st.getFluidState().isSource() && st.is(Blocks.WATER) : st.isAir();
                if (!ok) continue;
                lvl.setBlock(p, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, Math.max(0, Math.min(15, level)))
                        .setValue(LightBlock.WATERLOGGED, water), 3);
                mob.setVar(key + "_set", 1);
                mob.setVar(key + "_x", p.getX());
                mob.setVar(key + "_y", p.getY());
                mob.setVar(key + "_z", p.getZ());
                return true;
            }
            return false;
        }

        static void off(ArcanaMob mob, String key) {
            if (mob.var(key + "_set") != 1) return;
            BlockPos p = new BlockPos(mob.var(key + "_x"), mob.var(key + "_y"), mob.var(key + "_z"));
            var level = mob.self().level();
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.LIGHT)) level.setBlock(p, st.getValue(LightBlock.WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            for (String s : new String[] {"_set", "_x", "_y", "_z", "_key"}) mob.setVar(key + s, 0);
        }
    }
}
