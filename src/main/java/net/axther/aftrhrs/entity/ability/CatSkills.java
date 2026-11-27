package net.axther.aftrhrs.entity.ability;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.Conditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Luna's, Kevin's, Kitty's and Clover's behaviour tables (tools/specs/luna.skills.yml, kevin.skills.yml, kitty.json,
 * clover.json): biscuits on
 * beds and wool, the hunt (chatter + pounce), 3 am zoomies, petting reactions (belly trap, pronk, head bonk, swat when
 * over-petted / lean flop, love bite, purr melt, grumpy / face smush, chin up, paw hug, bunny kick), hairballs, the
 * cucumber jump and the judgy stare, paw fishing with the fish tax, the breakfast yowl, Kitty's bed buddy nights, camera
 * boops and Halloween floof, Clover's lucky finds, welcome-back bows and creeper crab hops. Their idle antics (blep, loaf, sploot, paws crossed, judge, mlem ...) come from the mood brain's
 * flourish pools; every real jump plays jump_start / jump / jump_end ({@link ArcanaCreature} jump states).
 */
final class CatSkills {
    private static final Set<EntityType<?>> PREY = Set.of(EntityTypes.CHICKEN, EntityTypes.RABBIT, EntityTypes.PARROT, EntityTypes.BAT,
            EntityTypes.SILVERFISH, EntityTypes.ENDERMITE, EntityTypes.FROG, EntityTypes.BEE);

    private CatSkills() {
    }

    private static boolean free(Ctx c) {
        return c.hasOwner() && !c.staying() && !c.busy();
    }

    static void register() {
        // ---------------------------------------------------------------- Luna
        Abilities.register("ArcanaLuna_Moonsight", c -> {
            ServerPlayer o = c.ownerWithin(12);
            if (o == null || !Conditions.timeIn(c.level(), 12500, 23500)) return;
            c.potion(o, "NIGHT_VISION", (int) c.bond(300, 60), 0, false);
        });
        Abilities.register("ArcanaLuna_SoftSpot", c -> {
            if (!free(c) || c.moving() || c.inCombat()) return;
            if (!Conditions.onSoftSpot(c.self) || c.self.getRandom().nextFloat() >= 0.4f || !c.cooldown("knead", 20)) return;
            c.mob.setBusy(true);
            c.play("knead_start");
            c.seq().wait(34).run(() -> {
                c.mob.hold("knead");
                c.sound("entity.cat.purr", 0.8, 1.0);
            }).wait(40).run(() -> {
                c.sound("entity.cat.purr", 0.8, 1.05);
                c.hearts(c.self, 1, 0.8);
            }).wait(40).run(() -> c.sound("entity.cat.purr", 0.8, 0.95)).wait(40).run(() -> {
                c.sound("entity.cat.purr", 0.8, 1.0);
                c.hearts(c.self, 1, 0.8);
            }).wait(30).run(() -> {
                c.mob.hold("");
                c.play("knead_end");
            }).wait(14).run(() -> {
                c.mob.free();
                c.fired();
            }).start();
        });
        Abilities.register("ArcanaLuna_Hunt", c -> {
            if (!free(c) || c.inCombat()) return;
            c.around(c.self, 8, LivingEntity.class, e -> PREY.contains(e.getType()) && e.isAlive()).stream()
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(c.self))).ifPresent(prey -> {
                        if (!c.cooldown("stalk", 30)) return;
                        c.self.getLookControl().setLookAt(prey);
                        face(c, prey.position());
                        c.play("chatter");
                        c.sound("entity.cat.ambient", 0.4, 1.9);
                        c.sched().after(14, () -> c.sound("entity.cat.ambient", 0.4, 2.0));
                        c.sched().after(48, () -> {
                            if (c.self.getRandom().nextFloat() >= 0.65f || !prey.isAlive()) return;
                            face(c, prey.position());
                            c.play("pounce");
                            c.sched().after(24, () -> {
                                Vec3 d = prey.position().subtract(c.self.position());
                                double h = Math.max(0.1, d.horizontalDistance());
                                c.self.setDeltaMovement(d.x / h * Math.min(0.9, h * 0.18), 0.45, d.z / h * Math.min(0.9, h * 0.18));
                                c.self.needsSync = true;
                                c.sound("entity.cat.purreow", 0.6, 1.4);
                            });
                        });
                    });
        });
        Abilities.register("ArcanaLuna_Zoomies", c -> {
            if (!free(c) || c.inCombat()) return;
            boolean night = Conditions.timeIn(c.level(), 17000, 20000) && c.self.getRandom().nextFloat() < 0.35f;
            boolean happy = c.creature != null && c.creature.happy() && c.self.getRandom().nextFloat() < 0.06f;
            if (!(night || happy) || !c.cooldown("zoomies", 90)) return;
            c.play("zoomies");
            c.potion(c.self, "SPEED", 70, 2, false);
            c.sound("entity.cat.stray_ambient", 0.6, 1.6);
            int[] at = {0, 14, 30, 44};
            for (int t : at) c.sched().after(t, () -> dart(c));
            c.fired();
        });
        Abilities.register("ArcanaLuna_PetReact", c -> petReact(c, true));
        Abilities.register("ArcanaLuna_PetDecay", c -> c.mob.setVar("pets", 0));
        Abilities.register("ArcanaLuna_Hairball", c -> {
            if (!free(c) || c.inCombat() || c.moving() || c.self.getRandom().nextFloat() >= 0.08f) return;
            c.mob.setBusy(true);
            c.play("hairball");
            c.seq().wait(10).run(() -> c.sound("entity.cat.hiss", 0.5, 0.6)).wait(9).run(() -> c.sound("entity.cat.hiss", 0.5, 0.55))
                    .wait(9).run(() -> c.sound("entity.cat.hiss", 0.6, 0.5)).wait(20).run(() -> {
                        c.sound("entity.player.burp", 0.8, 1.4);
                        c.drop(new ItemStack(Items.STRING));
                    }).wait(20).run(c.mob::free).start();
        });
        Abilities.register("ArcanaLuna_Spook", c -> {
            if (!c.hasOwner() || c.staying()) return;
            if (c.around(c.self, 5, Creeper.class, Entity::isAlive).isEmpty() || !c.cooldown("spook", 20)) return;
            c.play("startle_jump");
            c.sound("entity.cat.hiss", 1.0, 1.4);
        });

        // ---------------------------------------------------------------- Kevin
        Abilities.register("ArcanaKevin_PawFish", c -> {
            if (!c.hasOwner() || c.moving() || c.inCombat() || c.busy()) return;
            int water = 0;
            for (var p : net.minecraft.core.BlockPos.betweenClosed(c.self.blockPosition().offset(-2, -1, -2), c.self.blockPosition().offset(2, 1, 2))) {
                if (c.level().getBlockState(p).is(Blocks.WATER)) water++;
            }
            if (water <= 2 || c.self.getRandom().nextFloat() >= 0.5f || !c.cooldown("pawfish", c.bond(150, -18))) return;
            c.mob.setBusy(true);
            c.play("paw_fish");
            c.seq().wait(17).run(() -> splash(c, 0.35, 1.7, 10)).wait(21).run(() -> splash(c, 0.35, 1.6, 10)).wait(25).run(() -> splash(c, 0.5, 1.4, 16))
                    .wait(6).run(() -> {
                        c.sound("entity.fishing_bobber.splash", 0.5, 1.3);
                        Fx.particles(c.level(), c.forward(0.9).add(0, 0.3, 0), ParticleTypes.BUBBLE_POP, 8, 0.3, 0.2, 0.02);
                    }).wait(4).run(() -> {
                        if (c.self.getRandom().nextInt(4) < 3) catchFish(c);
                        else fishTax(c);
                    }).start();
        });
        Abilities.register("ArcanaKevin_Breakfast", c -> {
            ServerPlayer o = c.ownerWithin(16);
            if (o == null || !Conditions.dawn(c.level()) || c.inCombat() || c.busy() || !c.cooldown("breakfast", 1200)) return;
            c.self.getLookControl().setLookAt(o);
            face(c, o.position());
            c.play("yowl");
            c.sound("entity.cat.beg_for_food", 1.3, 0.8);
            c.sched().after(24, () -> c.sound("entity.cat.beg_for_food", 1.3, 0.75));
        });
        Abilities.register("ArcanaKevin_PetReact", c -> petReact(c, false));
        Abilities.register("ArcanaKevin_PetDecay", c -> c.mob.setVar("pets", 0));
        Abilities.register("ArcanaKevin_Unbothered", c -> {
            if (!c.hasOwner() || c.busy()) return;
            c.around(c.self, 6, Creeper.class, Entity::isAlive).stream().findFirst().ifPresent(cr -> {
                if (!c.cooldown("stare", 25)) return;
                c.self.getLookControl().setLookAt(cr);
                face(c, cr.position());
                c.play("judge");
            });
        });
        registerKitty();
        registerClover();
        registerLunaTwo();
        registerOlive();
        registerOzzy();
    }

    // ---------------------------------------------------------------- Kitty
    private static void registerKitty() {
        // His job: when the owner goes to bed he hops onto the foot of the bed and sleeps belly-up hugging the pillow.
        // When the owner gets up: a big stretch, and the owner wakes well rested (Regeneration, Absorption).
        Abilities.register("ArcanaKitty_BedBuddy", c -> {
            if (!c.hasOwner() || c.creature == null) return;
            ServerPlayer o = c.owner();
            boolean near = o != null && o.level() == c.level() && o.distanceToSqr(c.self) < 24 * 24;
            boolean asleep = near && o.isSleeping();
            if (c.mob.var("bedbuddy") == 1) {
                if (asleep) {
                    if (c.self.getRandom().nextFloat() < 0.15f) c.sound("entity.cat.purr", 0.5, 0.8);
                    return;
                }
                c.mob.setVar("bedbuddy", 0);
                c.mob.hold("");
                c.play("wake_stretch");
                c.sound("entity.cat.ambient", 0.8, 0.7);
                if (near) {
                    int secs = (int) c.bond(30, 6);
                    c.potion(o, "REGENERATION", secs * 20, c.bond() >= 3 ? 1 : 0, false);
                    c.potion(o, "ABSORPTION", secs * 40, c.bond() >= 5 ? 1 : 0, false);
                    c.hearts(o, 3, 1.0);
                    c.actionBar(o, "kitty-warm", "&2Kitty&7 kept you warm all night.");
                }
                c.sched().after(92, c.mob::free);
                c.fired();
                return;
            }
            if (!asleep || c.staying() || c.busy() || c.inCombat()) return;
            BlockPos head = o.getSleepingPos().orElse(null);
            if (head == null) return;
            BlockPos spot = head;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (c.level().getBlockState(head.relative(d)).is(BlockTags.BEDS)) {
                    spot = head.relative(d);   // the foot of the bed
                    break;
                }
            }
            c.mob.setBusy(true);
            c.mob.setVar("bedbuddy", 1);
            c.self.teleportTo(spot.getX() + 0.5, spot.getY() + 0.5625, spot.getZ() + 0.5);
            face(c, Vec3.atCenterOf(head));
            c.particles(ParticleTypes.CLOUD, 4, 0.3, 0.1, 0.3);
            c.mob.hold("bed_hug");
            c.sound("entity.cat.purr", 0.9, 0.8);
        });
        // Comes right up to your face and boops the camera when you stand close and look at him.
        Abilities.register("ArcanaKitty_Boop", c -> {
            if (!free(c) || c.moving() || c.inCombat()) return;
            ServerPlayer o = c.ownerWithin(2.6);
            if (o == null || o.isShiftKeyDown()) return;
            Vec3 to = c.self.getEyePosition().subtract(o.getEyePosition()).normalize();
            if (o.getViewVector(1f).dot(to) < 0.93 || c.self.getRandom().nextFloat() >= 0.3f || !c.cooldown("boop", 40)) return;
            c.mob.setBusy(true);
            c.self.getLookControl().setLookAt(o);
            face(c, o.position());
            c.play("boop");
            c.seq().wait(14).run(() -> c.sound("entity.fox.sniff", 0.6, 1.2)).wait(6).run(() -> c.sound("entity.fox.sniff", 0.6, 1.3))
                    .wait(12).run(() -> {
                        c.sound("block.note_block.hat", 0.5, 1.8);
                        c.hearts(c.self, 1, 0.9);
                    }).wait(14).run(() -> c.sound("entity.cat.ambient", 0.4, 1.9)).wait(18).run(c.mob::free).start();
        });
        Abilities.register("ArcanaKitty_PetReact", CatSkills::petReactKitty);
        Abilities.register("ArcanaKitty_PetDecay", c -> c.mob.setVar("pets", 0));
        // Creepers: puffs up into a Halloween cat and hisses.
        Abilities.register("ArcanaKitty_Floof", c -> {
            if (!c.hasOwner() || c.busy()) return;
            c.around(c.self, 5, Creeper.class, Entity::isAlive).stream().findFirst().ifPresent(cr -> {
                if (!c.cooldown("floof", 20)) return;
                face(c, cr.position());
                c.play("floof_up");
                c.sound("entity.cat.hiss", 1.0, 0.9);
            });
        });
    }

    // ---------------------------------------------------------------- Clover
    private static final Item[] FLOWERS = {Items.DANDELION, Items.POPPY, Items.CORNFLOWER, Items.OXEYE_DAISY, Items.AZURE_BLUET, Items.ALLIUM};
    private static final Item[] SEEDS = {Items.WHEAT_SEEDS, Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.BEETROOT_SEEDS};

    private static void registerClover() {
        // His job: out with his owner on grass or dirt he sometimes sniffs, digs and comes up with a four-leaf clover in his
        // teeth (lucky_dig), sets it down and bats a lucky find over to the owner (lucky_gift). The owner gets a little Luck.
        // Higher bond: more often, rarer finds, longer Luck.
        Abilities.register("ArcanaClover_LuckyFind", c -> {
            if (!free(c) || c.moving() || c.inCombat()) return;
            ServerPlayer o = c.ownerWithin(10);
            BlockState ground = c.level().getBlockState(c.self.blockPosition().below());
            if (o == null || !ground.is(BlockTags.DIRT)) return;
            if (c.self.getRandom().nextFloat() >= 0.15f || !c.cooldown("luckyfind", Math.max(120, c.bond(300, -30)))) return;
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            face(c, o.position());
            c.play("lucky_dig");
            BlockParticleOption dirt = new BlockParticleOption(ParticleTypes.BLOCK, ground);
            c.seq().wait(10).run(() -> c.sound("entity.fox.sniff", 0.5, 1.4)).wait(8).run(() -> c.sound("entity.fox.sniff", 0.5, 1.5))
                    .wait(16).run(() -> dig(c, dirt)).wait(8).run(() -> dig(c, dirt)).wait(8).run(() -> dig(c, dirt))
                    .wait(8).run(() -> dig(c, dirt)).wait(16).run(() -> {
                        c.sound("block.grass.break", 0.5, 1.6);
                        Fx.particles(c.level(), c.forward(0.5).add(0, 0.3, 0), ParticleTypes.HAPPY_VILLAGER, 5, 0.2, 0.15, 0.02);
                    }).wait(38).run(() -> {
                        ServerPlayer to = c.owner();
                        if (to != null && to.level() == c.level()) face(c, to.position());
                        c.play("lucky_gift");
                    }).wait(13).run(() -> {
                        Vec3 at = c.forward(0.55).add(0, 0.15, 0);
                        ItemEntity it = new ItemEntity(c.level(), at.x, at.y, at.z, luckyFind(c), 0, 0, 0);
                        it.setPickUpDelay(10);
                        c.level().addFreshEntity(it);
                        c.sound("block.azalea_leaves.place", 0.6, 1.4);
                        c.sched().after(13, () -> batToOwner(c, it));
                    }).wait(23).run(() -> {
                        ServerPlayer to = c.ownerWithin(16);
                        if (to != null) {
                            int secs = (int) c.bond(120, 60);
                            c.potion(to, "LUCK", secs * 20, c.bond() >= 4 ? 1 : 0, false);
                            c.actionBar(to, "clover-lucky", "&aClover&7 found you something lucky!");
                        }
                        c.hearts(c.self, 2, 0.8);
                        c.sound("entity.cat.purreow", 0.7, 1.3);
                    }).wait(24).run(() -> {
                        c.mob.free();
                        c.fired();
                    }).start();
        });
        // Welcome back: when his owner returns after being away (out of range or logged off) he bows like a little
        // gentleman in a tuxedo, then greets them tail-up.
        Abilities.register("ArcanaClover_Greet", c -> {
            if (!c.hasOwner()) return;
            ServerPlayer o = c.owner();
            boolean far = o == null || o.level() != c.level() || o.distanceToSqr(c.self) > 24 * 24;
            if (far) {
                c.mob.setVar("away", 1);
                return;
            }
            if (c.mob.var("away") != 1 || o.distanceToSqr(c.self) > 6 * 6 || c.busy() || c.staying() || c.inCombat()) return;
            c.mob.setVar("away", 0);
            if (!c.cooldown("greet", 60)) return;
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            c.self.getLookControl().setLookAt(o);
            face(c, o.position());
            c.play("dapper_bow");
            c.seq().wait(34).run(() -> c.sound("entity.cat.purreow", 0.8, 1.25)).wait(24).run(() -> {
                c.play("tail_quiver");
                c.hearts(c.self, 2, 0.8);
            }).wait(10).run(() -> c.sound("entity.cat.ambient", 0.6, 1.6)).wait(38).run(c.mob::free).start();
        });
        Abilities.register("ArcanaClover_PetReact", CatSkills::petReactClover);
        Abilities.register("ArcanaClover_PetDecay", c -> c.mob.setVar("pets", 0));
        // Creepers: arches his back and crab-hops sideways, hissing.
        Abilities.register("ArcanaClover_Spook", c -> {
            if (!c.hasOwner() || c.busy()) return;
            c.around(c.self, 5, Creeper.class, Entity::isAlive).stream().findFirst().ifPresent(cr -> {
                if (!c.cooldown("spook", 20)) return;
                face(c, cr.position());
                c.play("crab_hop");
                c.sound("entity.cat.hiss", 1.0, 1.3);
            });
        });
    }

    private static void registerLunaTwo() {
        // Her job: the unimpressed glare. A monster comes near while she's out with her owner: she lowers her head,
        // flattens her ears and stares it down (stink_eye). Every monster around her that she can see gets Weakness and
        // Slowness. Higher bond: a wider stare, longer effects, level II from bond 4, and she glares more often.
        Abilities.register("ArcanaLunaTwo_StinkEye", c -> {
            if (!free(c) || c.staying()) return;
            ServerPlayer o = c.ownerWithin(12);
            if (o == null) return;
            double r = c.bond(6, 0.5);
            List<Monster> near = c.around(c.self, r, Monster.class, m -> glareable(c, m));
            if (near.isEmpty() || !c.cooldown("stinkeye", Math.max(10, c.bond(20, -2)))) return;
            Monster first = near.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(c.self))).orElse(near.get(0));
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            face(c, first.position());
            c.self.getLookControl().setLookAt(first);
            c.play("stink_eye");
            c.sound("entity.cat.stray_ambient", 0.7, 0.7);
            c.seq().wait(16).run(() -> {
                int secs = (int) Math.round(c.bond(6, 1));
                int lvl = c.bond() >= 4 ? 1 : 0;
                for (Monster m : c.around(c.self, r, Monster.class, m -> glareable(c, m))) {
                    c.potion(m, "WEAKNESS", secs * 20, lvl, true);
                    c.potion(m, "SLOWNESS", secs * 20, lvl, true);
                    c.particles(m, ParticleTypes.SMOKE, 8, 0.3, 0.3, m.getBbHeight() + 0.2, 0.01);
                }
                c.sound("entity.cat.hiss", 0.35, 0.8);
                ServerPlayer to = c.ownerWithin(16);
                if (to != null) c.actionBar(to, "luna-two-glare", "&dLuna But The Second One&7 is not impressed.");
            }).wait(40).run(() -> c.sound("entity.cat.ambient", 0.4, 0.7)).wait(20).run(() -> {
                c.mob.free();
                c.fired();
            }).start();
        });
        // Welcome back: one long look, one slow blink, then she looks away. (She did miss you. She won't say so.)
        Abilities.register("ArcanaLunaTwo_Greet", c -> {
            if (!c.hasOwner()) return;
            ServerPlayer o = c.owner();
            boolean far = o == null || o.level() != c.level() || o.distanceToSqr(c.self) > 24 * 24;
            if (far) {
                c.mob.setVar("away", 1);
                return;
            }
            if (c.mob.var("away") != 1 || o.distanceToSqr(c.self) > 6 * 6 || c.busy() || c.staying() || c.inCombat()) return;
            c.mob.setVar("away", 0);
            if (!c.cooldown("greet", 60)) return;
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            c.self.getLookControl().setLookAt(o);
            face(c, o.position());
            c.play("grump_greet");
            c.seq().wait(22).run(() -> c.sound("entity.cat.purr", 0.5, 0.8)).wait(16).run(() -> c.hearts(c.self, 1, 0.9))
                    .wait(22).run(c.mob::free).start();
        });
        Abilities.register("ArcanaLunaTwo_PetReact", CatSkills::petReactLunaTwo);
        Abilities.register("ArcanaLunaTwo_PetDecay", c -> c.mob.setVar("pets", 0));
        // Creepers: arched, hissing, hat askew.
        Abilities.register("ArcanaLunaTwo_Spook", c -> {
            if (!c.hasOwner() || c.busy()) return;
            c.around(c.self, 5, Creeper.class, Entity::isAlive).stream().findFirst().ifPresent(cr -> {
                if (!c.cooldown("spook", 20)) return;
                face(c, cr.position());
                c.play("hat_hiss");
                c.sound("entity.cat.hiss", 1.0, 1.0);
            });
        });
    }

    /** A monster she can stare down: alive, seen, nobody's pet, and not a boss (bosses don't care what she thinks). */
    private static boolean glareable(Ctx c, Monster m) {
        if (!m.isAlive() || !c.self.hasLineOfSight(m)) return false;
        if (m instanceof net.minecraft.world.entity.OwnableEntity own && own.getOwnerReference() != null) return false;
        if (m instanceof net.minecraft.world.entity.boss.wither.WitherBoss) return false;
        return !(m instanceof net.axther.aftrhrs.entity.ArcanaMob am && am.species().kind == net.axther.aftrhrs.entity.Species.Kind.BOSS);
    }

    /** Her petting: she tolerates it, head-bumps you or pushes your hand away; the 4th pet within 10 s gets a swat and the glare. */
    private static void petReactLunaTwo(Ctx c) {
        if (!c.hasOwner() || c.busy() || c.staying()) return;
        if (!(c.trigger instanceof ServerPlayer p) || c.creature == null || !c.creature.isOwnedBy(p) || p.isShiftKeyDown()
                || !p.getMainHandItem().isEmpty()) return;
        if (!c.cooldown("petreact", 2)) return;
        int pets = c.mob.var("pets") + 1;
        c.mob.setVar("pets", pets);
        c.mob.stopPlay("happy");
        if (pets > 3) {
            c.mob.setVar("pets", 0);
            c.mob.setBusy(true);
            c.play("swat_glare");
            c.sound("entity.cat.hiss", 0.6, 1.0);
            c.seq().wait(4).run(() -> c.sound("entity.player.attack.weak", 0.5, 1.6)).wait(4)
                    .run(() -> c.particles(ParticleTypes.ANGRY_VILLAGER, 1, 0.1, 0.1, 1.0)).wait(48).run(c.mob::free).start();
            return;
        }
        double r = c.self.getRandom().nextDouble() * 5;
        if (r < 2.2) {
            c.play("tolerate");
            c.sound("entity.cat.purr", 0.6, 0.8);
            c.sched().after(30, () -> c.hearts(c.self, 1, 0.8));
        } else if (r < 3.8) {
            c.play("head_bump");
            c.sound("entity.cat.purr", 0.8, 0.85);
            c.sched().after(14, () -> c.hearts(c.self, 1, 0.9));
        } else {
            c.play("paw_push");
            c.sound("entity.cat.stray_ambient", 0.4, 0.9);
        }
    }

    // ---------------------------------------------------------------- Olive
    private static void registerOlive() {
        // His job: purr therapy. The owner is hurt: he trots over, flops belly-up right next to them and purrs
        // (purr_flop), and the purring heals: Regeneration. Higher bond: longer, sooner, level II from bond 4.
        Abilities.register("ArcanaOlive_PurrTherapy", c -> {
            if (!free(c) || c.inCombat()) return;
            ServerPlayer o = c.ownerWithin(12);
            if (o == null || o.isDeadOrDying() || o.getHealth() >= o.getMaxHealth() * 0.6f) return;
            if (o.distanceToSqr(c.self) > 2.5 * 2.5) {
                c.self.getNavigation().moveTo(o, 1.25);
                return;
            }
            if (!c.cooldown("purr", Math.max(12, c.bond(30, -3)))) return;
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            face(c, o.position());
            c.play("purr_flop");
            c.sound("entity.cat.purr", 1.0, 1.0);
            c.seq().wait(20).run(() -> {
                int secs = (int) Math.round(c.bond(5, 1));
                c.potion(o, "REGENERATION", secs * 20, c.bond() >= 4 ? 1 : 0, true);
                c.hearts(c.self, 3, 0.6);
                c.sound("entity.cat.purr", 1.0, 0.95);
                c.actionBar(o, "olive-purr", "&6Olive&7 purrs at you. You feel better.");
            }).wait(30).run(() -> c.hearts(o, 2, 1.2)).wait(20).run(() -> {
                c.mob.free();
                c.fired();
            }).start();
        });
        // Welcome back: a bouncy trot, two head-bonks, and he flops over at your feet.
        Abilities.register("ArcanaOlive_Greet", c -> {
            if (!c.hasOwner()) return;
            ServerPlayer o = c.owner();
            boolean far = o == null || o.level() != c.level() || o.distanceToSqr(c.self) > 24 * 24;
            if (far) {
                c.mob.setVar("away", 1);
                return;
            }
            if (c.mob.var("away") != 1 || o.distanceToSqr(c.self) > 6 * 6 || c.busy() || c.staying() || c.inCombat()) return;
            c.mob.setVar("away", 0);
            if (!c.cooldown("greet", 60)) return;
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            c.self.getLookControl().setLookAt(o);
            face(c, o.position());
            c.play("derp_greet");
            c.sound("entity.cat.ambient", 0.8, 1.15);
            c.seq().wait(22).run(() -> c.sound("entity.cat.purreow", 0.7, 1.1)).wait(20).run(() -> c.hearts(c.self, 2, 0.8))
                    .wait(34).run(c.mob::free).start();
        });
        Abilities.register("ArcanaOlive_PetReact", CatSkills::petReactOlive);
        Abilities.register("ArcanaOlive_PetDecay", c -> c.mob.setVar("pets", 0));
        // Creepers: he puffs up and hops sideways, then forgets why.
        Abilities.register("ArcanaOlive_Spook", c -> {
            if (!c.hasOwner() || c.busy()) return;
            c.around(c.self, 5, Creeper.class, Entity::isAlive).stream().findFirst().ifPresent(cr -> {
                if (!c.cooldown("spook", 20)) return;
                face(c, cr.position());
                c.play("poof_hop");
                c.sound("entity.cat.hiss", 0.8, 1.2);
            });
        });
    }

    /** His petting: head rub, belly roll or a slow love blink; pet him a lot and he traps your hand (cutely). */
    private static void petReactOlive(Ctx c) {
        if (!c.hasOwner() || c.busy() || c.staying()) return;
        if (!(c.trigger instanceof ServerPlayer p) || c.creature == null || !c.creature.isOwnedBy(p) || p.isShiftKeyDown()
                || !p.getMainHandItem().isEmpty()) return;
        if (!c.cooldown("petreact", 2)) return;
        int pets = c.mob.var("pets") + 1;
        c.mob.setVar("pets", pets);
        c.mob.stopPlay("happy");
        if (pets > 4) {
            c.mob.setVar("pets", 0);
            c.mob.setBusy(true);
            c.play("belly_trap");
            c.sound("entity.cat.purreow", 0.7, 1.2);
            c.seq().wait(10).run(() -> c.hearts(c.self, 2, 0.6)).wait(50).run(c.mob::free).start();
            return;
        }
        double r = c.self.getRandom().nextDouble() * 3;
        if (r < 1.2) {
            c.play("head_rub");
            c.sound("entity.cat.purr", 0.8, 1.05);
            c.sched().after(12, () -> c.hearts(c.self, 1, 0.9));
        } else if (r < 2.2) {
            c.play("belly_roll");
            c.sound("entity.cat.purr", 0.9, 1.0);
            c.sched().after(20, () -> c.hearts(c.self, 2, 0.6));
        } else {
            c.play("love_blink");
            c.sound("entity.cat.ambient", 0.4, 1.2);
            c.sched().after(24, () -> c.hearts(c.self, 1, 0.9));
        }
    }

    // ---------------------------------------------------------------- Ozzy
    private static void registerOzzy() {
        // His job: ankle biter. A monster comes near his owner: he stalks it, wiggles his (considerable) behind and
        // pounces, biting it hard. Higher bond: he notices from further away, bites harder and more often.
        Abilities.register("ArcanaOzzy_Ambush", c -> {
            if (!free(c)) return;
            ServerPlayer o = c.ownerWithin(12);
            if (o == null) return;
            double r = c.bond(6, 0.4);
            List<Monster> near = c.around(o, r, Monster.class, m -> glareable(c, m) && m.distanceToSqr(c.self) < 10 * 10);
            if (near.isEmpty() || !c.cooldown("ambush", Math.max(3, c.bond(8, -0.8)))) return;
            Monster prey = near.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(c.self))).orElse(near.get(0));
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            face(c, prey.position());
            c.self.getLookControl().setLookAt(prey);
            c.play("stalk");
            c.sound("entity.cat.stray_ambient", 0.6, 0.7);
            c.seq().wait(26).run(() -> {
                if (!prey.isAlive()) return;
                face(c, prey.position());
                c.play("pounce");
                Vec3 d = prey.position().subtract(c.self.position());
                double h = Math.max(0.1, Math.sqrt(d.x * d.x + d.z * d.z));
                double v = Math.min(1.1, h * 0.2);
                c.self.setDeltaMovement(d.x / h * v, 0.42, d.z / h * v);
                c.sound("entity.cat.hiss", 0.7, 0.8);
            }).wait(9).run(() -> {
                if (!prey.isAlive() || prey.distanceToSqr(c.self) > 3.0 * 3.0) return;
                prey.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), (float) c.bond(4, 0.6));
                Vec3 k = prey.position().subtract(c.self.position()).multiply(1, 0, 1);
                if (k.lengthSqr() > 1.0E-4) {
                    k = k.normalize().scale(0.45);
                    prey.setDeltaMovement(prey.getDeltaMovement().add(k.x, 0.2, k.z));
                    prey.needsSync = true;
                }
                c.sound(prey, "entity.player.attack.strong", 0.7, 1.3);
                c.particles(prey, ParticleTypes.CRIT, 10, 0.3, 0.3, prey.getBbHeight() * 0.5);
            }).wait(10).run(() -> {
                c.mob.free();
                c.fired();
            }).start();
        });
        // Every so often he ambushes your ankles. It doesn't hurt. He does it anyway.
        Abilities.register("ArcanaOzzy_AnkleAttack", c -> {
            if (!free(c) || c.inCombat()) return;
            ServerPlayer o = c.ownerWithin(3.5);
            if (o == null || o.isSprinting() || c.self.getRandom().nextInt(200) != 0 || !c.cooldown("ankles", 150)) return;
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            face(c, o.position());
            c.self.getLookControl().setLookAt(o);
            c.play("ankle_attack");
            c.sound("entity.cat.hiss", 0.5, 1.1);
            c.seq().wait(6).run(() -> {
                c.sound("entity.player.attack.weak", 0.6, 1.5);
                c.particles(ParticleTypes.ANGRY_VILLAGER, 1, 0.1, 0.1, 0.8);
                c.actionBar(o, "ozzy-ankles", "&8Ozzy&7 attacked your ankles. Again.");
            }).wait(12).run(() -> c.sound("entity.player.attack.weak", 0.5, 1.7)).wait(22).run(c.mob::free).start();
        });
        // Welcome back: he trots up, head-bonks you twice... and bites you.
        Abilities.register("ArcanaOzzy_Greet", c -> {
            if (!c.hasOwner()) return;
            ServerPlayer o = c.owner();
            boolean far = o == null || o.level() != c.level() || o.distanceToSqr(c.self) > 24 * 24;
            if (far) {
                c.mob.setVar("away", 1);
                return;
            }
            if (c.mob.var("away") != 1 || o.distanceToSqr(c.self) > 6 * 6 || c.busy() || c.staying() || c.inCombat()) return;
            c.mob.setVar("away", 0);
            if (!c.cooldown("greet", 60)) return;
            c.mob.setBusy(true);
            c.self.getNavigation().stop();
            c.self.getLookControl().setLookAt(o);
            face(c, o.position());
            c.play("rude_greet");
            c.sound("entity.cat.ambient", 0.7, 0.8);
            c.seq().wait(22).run(() -> c.hearts(c.self, 1, 0.9)).wait(14).run(() -> {
                c.sound("entity.player.attack.weak", 0.6, 1.5);
                c.particles(ParticleTypes.ANGRY_VILLAGER, 1, 0.1, 0.1, 0.8);
            }).wait(28).run(c.mob::free).start();
        });
        Abilities.register("ArcanaOzzy_PetReact", CatSkills::petReactOzzy);
        Abilities.register("ArcanaOzzy_PetDecay", c -> c.mob.setVar("pets", 0));
        // Creepers: a puffed-up, fat, hissing arch.
        Abilities.register("ArcanaOzzy_Spook", c -> {
            if (!c.hasOwner() || c.busy()) return;
            c.around(c.self, 5, Creeper.class, Entity::isAlive).stream().findFirst().ifPresent(cr -> {
                if (!c.cooldown("spook", 20)) return;
                face(c, cr.position());
                c.play("fupa_puff");
                c.sound("entity.cat.hiss", 1.0, 0.75);
            });
        });
    }

    /** His petting: he puts up with it (tail lashing), nips you, or grabs and bunny-kicks; the 3rd pet in a row is war. */
    private static void petReactOzzy(Ctx c) {
        if (!c.hasOwner() || c.busy() || c.staying()) return;
        if (!(c.trigger instanceof ServerPlayer p) || c.creature == null || !c.creature.isOwnedBy(p) || p.isShiftKeyDown()
                || !p.getMainHandItem().isEmpty()) return;
        if (!c.cooldown("petreact", 2)) return;
        int pets = c.mob.var("pets") + 1;
        c.mob.setVar("pets", pets);
        c.mob.stopPlay("happy");
        if (pets > 2) {
            c.mob.setVar("pets", 0);
            c.mob.setBusy(true);
            c.play("murder_mittens");
            c.sound("entity.cat.hiss", 0.8, 0.9);
            c.seq().wait(4).run(() -> c.sound("entity.player.attack.weak", 0.6, 1.4)).wait(6)
                    .run(() -> c.sound("entity.player.attack.weak", 0.6, 1.6)).wait(4)
                    .run(() -> c.particles(ParticleTypes.ANGRY_VILLAGER, 2, 0.2, 0.1, 0.8)).wait(46).run(c.mob::free).start();
            return;
        }
        double r = c.self.getRandom().nextDouble() * 3;
        if (r < 1.0) {
            c.play("grudge_pet");
            c.sound("entity.cat.purr", 0.5, 0.75);
        } else if (r < 2.1) {
            c.play("love_bite");
            c.sched().after(6, () -> c.sound("entity.player.attack.weak", 0.5, 1.6));
            c.sched().after(14, () -> c.hearts(c.self, 1, 0.9));
        } else {
            c.play("bunny_kick");
            c.sound("entity.cat.hiss", 0.3, 1.3);
            c.sched().after(10, () -> c.sound("entity.player.attack.weak", 0.5, 1.5));
        }
    }

    private static void dig(Ctx c, BlockParticleOption dirt) {
        c.sound("block.rooted_dirt.hit", 0.6, 1.3);
        Fx.particles(c.level(), c.forward(0.6).add(0, 0.1, 0), dirt, 6, 0.15, 0.05, 0.08);
    }

    /** A lucky find: mostly nuggets, flowers and seeds; lapis, emeralds and (rarely) a diamond, likelier at higher bond. */
    private static ItemStack luckyFind(Ctx c) {
        var rnd = c.self.getRandom();
        double r = rnd.nextDouble() * 100;
        double b = c.bond() * 1.5;
        if (r < 1 + b * 0.4) return new ItemStack(Items.DIAMOND);
        if (r < 6 + b) return new ItemStack(Items.EMERALD);
        if (r < 14 + b * 1.5) return new ItemStack(Items.LAPIS_LAZULI, 2 + rnd.nextInt(2));
        if (r < 34) return new ItemStack(Items.GOLD_NUGGET, 2 + rnd.nextInt(3));
        if (r < 54) return new ItemStack(Items.IRON_NUGGET, 2 + rnd.nextInt(4));
        if (r < 70) return new ItemStack(FLOWERS[rnd.nextInt(FLOWERS.length)]);
        if (r < 85) return new ItemStack(SEEDS[rnd.nextInt(SEEDS.length)], 2 + rnd.nextInt(2));
        return new ItemStack(Items.SWEET_BERRIES, 2 + rnd.nextInt(3));
    }

    /** The paw flick: sends the find skidding over to the owner. */
    private static void batToOwner(Ctx c, ItemEntity it) {
        if (!it.isAlive()) return;
        c.sound("entity.player.attack.weak", 0.5, 1.7);
        ServerPlayer o = c.ownerWithin(16);
        if (o == null) return;
        Vec3 d = o.position().subtract(it.position());
        double h = Math.max(0.1, d.horizontalDistance());
        double v = Math.min(0.5, 0.12 + h * 0.07);
        it.setDeltaMovement(d.x / h * v, 0.22, d.z / h * v);
        it.needsSync = true;
    }

    /** Clover petting: cheek rub, purr roll or love blink; the 4th pet within 10 s gets a nip and an indignant groom. */
    private static void petReactClover(Ctx c) {
        if (!c.hasOwner() || c.busy() || c.staying()) return;
        if (!(c.trigger instanceof ServerPlayer p) || c.creature == null || !c.creature.isOwnedBy(p) || p.isShiftKeyDown()
                || !p.getMainHandItem().isEmpty()) return;
        if (!c.cooldown("petreact", 2)) return;
        int pets = c.mob.var("pets") + 1;
        c.mob.setVar("pets", pets);
        c.mob.stopPlay("happy");
        if (pets > 3) {
            c.mob.setVar("pets", 0);
            c.mob.setBusy(true);
            c.play("nip_groom");
            c.sound("entity.cat.hiss", 0.5, 1.5);
            c.seq().wait(5).run(() -> c.particles(ParticleTypes.ANGRY_VILLAGER, 1, 0.1, 0.1, 1.0)).wait(55).run(c.mob::free).start();
            return;
        }
        double r = c.self.getRandom().nextDouble() * 5;
        if (r < 1.6) {
            c.play("cheek_rub");
            c.sound("entity.cat.purr", 1.0, 1.05);
            c.sched().after(24, () -> c.hearts(c.self, 1, 0.8));
        } else if (r < 3) {
            c.mob.setBusy(true);
            c.play("purr_roll");
            c.sound("entity.cat.purr", 1.0, 1.0);
            c.seq().wait(30).run(() -> c.hearts(c.self, 2, 0.6)).wait(42).run(c.mob::free).start();
        } else if (r < 4.3) {
            c.play("love_blink");
            c.sound("entity.cat.purr", 0.8, 1.1);
            c.sched().after(30, () -> c.hearts(c.self, 1, 0.9));
        } else {
            c.play("tail_quiver");
            c.sound("entity.cat.ambient", 0.6, 1.7);
        }
    }

    /** Kitty petting: face smush, chin up or paw hug; the 4th pet within 10 s gets the arm grabbed and bunny-kicked. */
    private static void petReactKitty(Ctx c) {
        if (!c.hasOwner() || c.busy() || c.staying()) return;
        if (!(c.trigger instanceof ServerPlayer p) || c.creature == null || !c.creature.isOwnedBy(p) || p.isShiftKeyDown()
                || !p.getMainHandItem().isEmpty()) return;
        if (!c.cooldown("petreact", 2)) return;
        int pets = c.mob.var("pets") + 1;
        c.mob.setVar("pets", pets);
        c.mob.stopPlay("happy");
        if (pets > 3) {
            c.mob.setVar("pets", 0);
            c.mob.setBusy(true);
            c.play("bunny_kick");
            c.sound("entity.cat.hiss", 0.6, 1.1);
            c.seq().wait(16).run(() -> c.particles(ParticleTypes.CRIT, 6, 0.3, 0.2, 0.4)).wait(12)
                    .run(() -> c.particles(ParticleTypes.ANGRY_VILLAGER, 1, 0.1, 0.1, 1.0)).wait(40).run(c.mob::free).start();
            return;
        }
        double r = c.self.getRandom().nextDouble() * 5;
        if (r < 1.5) {
            c.mob.setBusy(true);
            c.play("face_smush");
            c.sound("entity.cat.purr", 1.0, 0.8);
            c.seq().wait(26).run(() -> c.hearts(c.self, 2, 0.8)).wait(36).run(c.mob::free).start();
        } else if (r < 3) {
            c.play("chin_up");
            c.sound("entity.cat.purr", 0.9, 0.85);
            c.sched().after(30, () -> c.hearts(c.self, 1, 0.9));
        } else if (r < 4.2) {
            c.play("paw_hug");
            c.seq().wait(24).run(() -> c.sound("entity.cat.ambient", 0.4, 1.5)).wait(8).run(() -> c.sound("entity.cat.purr", 0.8, 0.9)).start();
        } else {
            c.play("chirp");
            c.sound("entity.cat.ambient", 0.6, 1.6);
        }
    }

    /**
     * Luna / Kevin petting: the 4th pet within 10 s earns a swat (Luna) or a grumpy face (Kevin). Their reactions are
     * standing clips; while staying (sitting) the creature's {@code pet_sit} head rub plays instead.
     */
    private static void petReact(Ctx c, boolean luna) {
        if (!c.hasOwner() || c.busy() || c.staying()) return;
        if (!(c.trigger instanceof ServerPlayer p) || c.creature == null || !c.creature.isOwnedBy(p) || p.isShiftKeyDown()
                || !p.getMainHandItem().isEmpty()) return;
        if (!c.cooldown("petreact", 2)) return;
        int pets = c.mob.var("pets") + 1;
        c.mob.setVar("pets", pets);
        c.mob.stopPlay("happy");
        if (pets > 3) {
            c.mob.setVar("pets", 0);
            if (luna) {
                c.play("swat");
                c.sound("entity.cat.hiss", 0.8, 1.3);
            } else {
                c.play("grumpy");
                c.sound("entity.cat.stray_ambient", 0.8, 0.6);
            }
            c.particles(ParticleTypes.ANGRY_VILLAGER, 1, 0.1, 0.1, 1.0);
            return;
        }
        double r = c.self.getRandom().nextDouble() * 5.5;
        if (luna) {
            if (r < 1) {
                c.mob.setBusy(true);
                c.play("belly_trap");
                c.sound("entity.cat.purr", 0.9, 1.1);
                c.seq().wait(27).run(() -> {
                    c.sound("entity.cat.hiss", 0.5, 1.5);
                    c.particles(ParticleTypes.CRIT, 6, 0.3, 0.2, 0.5);
                }).wait(33).run(c.mob::free).start();
            } else if (r < 2) {
                c.self.setDeltaMovement(c.self.getDeltaMovement().add(0, 0.55, 0));
                c.self.needsSync = true;
                c.sound("entity.cat.ambient", 0.6, 1.5);
            } else if (r < 3.5) {
                c.play("head_bonk");
                c.sound("entity.cat.purr", 0.9, 1.2);
            } else {
                c.sound("entity.cat.purr", 0.9, 1.1);
            }
        } else {
            if (r < 1) {
                c.mob.setBusy(true);
                c.play("lean_flop");
                c.sound("entity.cat.purr", 0.9, 0.85);
                c.seq().wait(24).run(() -> {
                    c.sound("entity.slime.squish", 0.5, 0.6);
                    c.hearts(c.self, 2, 0.6);
                }).wait(40).run(c.mob::free).start();
            } else if (r < 2) {
                c.play("love_bite");
                c.seq().wait(9).run(() -> c.sound("entity.cat.ambient", 0.5, 1.3)).wait(18).run(() -> c.sound("entity.cat.purr", 0.8, 0.9)).start();
            } else if (r < 3.5) {
                c.mob.setBusy(true);
                c.play("purr_melt");
                c.sound("entity.cat.purr", 1.0, 0.8);
                c.seq().wait(30).run(() -> {
                    c.sound("entity.cat.purr", 1.0, 0.85);
                    c.hearts(c.self, 1, 0.8);
                }).wait(38).run(c.mob::free).start();
            } else {
                c.sound("entity.cat.purr", 0.9, 0.85);
            }
        }
    }

    private static void dart(Ctx c) {
        float yaw = c.self.getRandom().nextFloat() * 360;
        c.self.setYRot(yaw);
        c.self.setYBodyRot(yaw);
        double rad = yaw * Mth.DEG_TO_RAD;
        c.self.setDeltaMovement(-Math.sin(rad) * 0.75, 0.32, Math.cos(rad) * 0.75);
        c.self.needsSync = true;
        c.particles(ParticleTypes.CLOUD, 4, 0.2, 0.05, 0.1);
    }

    private static void face(Ctx c, Vec3 at) {
        Vec3 d = at.subtract(c.self.position());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        c.self.setYRot(yaw);
        c.self.setYBodyRot(yaw);
        c.self.setYHeadRot(yaw);
    }

    private static void splash(Ctx c, double v, double p, int n) {
        c.sound("entity.generic.splash", v, p);
        Fx.particles(c.level(), c.forward(0.9).add(0, 0.1, 0), ParticleTypes.SPLASH, n, 0.25, 0.1, 0.02);
    }

    private static void catchFish(Ctx c) {
        double r = c.self.getRandom().nextDouble() * 100;
        ItemStack fish = new ItemStack(r < 55 ? Items.COD : r < 85 ? Items.SALMON : r < 95 ? Items.TROPICAL_FISH : Items.PUFFERFISH);
        c.drop(fish);
        c.sound("entity.cod.flop", 0.8, 1.0);
        c.particles(ParticleTypes.HAPPY_VILLAGER, 4, 0.3, 0.2, 0.8);
        c.fired();
        c.sched().after(36, c.mob::free);
    }

    private static void fishTax(Ctx c) {
        c.seq().wait(36).run(() -> c.play("fish_tax")).wait(8).run(() -> c.sound("entity.generic.eat", 0.6, 0.8))
                .wait(10).run(() -> c.sound("entity.generic.eat", 0.6, 0.85)).wait(10).run(() -> c.sound("entity.generic.eat", 0.6, 0.8))
                .wait(14).run(() -> {
                    c.sound("entity.player.burp", 0.6, 0.7);
                    c.self.heal(4);
                }).wait(22).run(c.mob::free).start();
    }
}
