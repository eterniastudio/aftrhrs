package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The livestock harvests (wildlife SPEC section 1/5): milking, brushing, shearing (fleece regrows only on warped
 * nylium), truffle rooting while leashed, egg laying (into a nesting box when one is near), the fed state (silk into a
 * silk frame, glow mucus), the Lanternsnail bucket and the Velvetstag's daily antler shed. Every cooldown and timer is a
 * timestamp in the mob's Pdc ({@code feather:wild_*}, the same keys Paper kept), so restarts and chunk unloads keep them.
 */
final class Harvests {
    // Pdc keys (LONG epoch ms unless noted)
    static final String MILK = "wild_milk", BRUSH = "wild_brush", SHORN = "wild_shorn", REGROW = "wild_regrow",
            LAY = "wild_lay", ROOT = "wild_root", FED = "wild_fed_until", FED_BY = "wild_fed_by", SILK = "wild_silk_at",
            DRIP = "wild_drip_at", ANTLER = "wild_antler_shed",
            BOTTLE = "wild_bottle";

    private final WildlifeModule m;
    /** Next trough search per fed mob (in memory: a search is only a cost, never a state). */
    private final Map<UUID, Long> troughCheck = new HashMap<>();
    private long troughCheckMs = 30_000;

    Harvests(WildlifeModule module) {
        this.m = module;
    }

    void reload(long troughCheckMs) {
        this.troughCheckMs = troughCheckMs;
    }

    static void hook(Entity e, String name) {
        if (e instanceof ArcanaWildlife w && !w.isRemoved()) w.hook(name);
    }

    static Vec3 at(Entity e, double dy) {
        return e.position().add(0, dy, 0);
    }

    // ------------------------------------------------------------------ clicks

    /** Young never harvest or eat: block feeding/milking/shearing on them (silently). */
    boolean blocksYoung(WildSpecies s, ItemStack held, String oid) {
        if (oid == null && (held.is(Items.BUCKET) || held.is(Items.SHEARS) || held.is(Items.BRUSH) || held.is(Items.WATER_BUCKET)
                || held.is(Items.GLASS_BOTTLE))) return true;
        return (s.breedFood != null && s.breedFood.matches(held)) || (s.fed != null && s.fed.food().matches(held));
    }

    boolean interact(ServerPlayer p, Entity e, WildSpecies s, ItemStack held, String oid, InteractionHand hand) {
        if (oid == null) {
            if (held.is(Items.BUCKET) && s.milkCooldownMs > 0) return milk(p, e, s, hand);
            if (held.is(Items.BRUSH) && s.brush != null) return brush(p, e, s, hand);
            if (held.is(Items.SHEARS) && s.shear != null) return shear(p, e, s, hand);
            if (held.is(Items.WATER_BUCKET) && s.bucket != null) return bucket(p, e, s, hand);
            if (held.is(Items.GLASS_BOTTLE) && s.bottle != null) return bottle(p, e, s, hand);
        }
        boolean fedFood = s.fed != null && s.fed.food().matches(held);
        boolean breedFood = s.breedFood != null && s.breedFood.matches(held);
        if (!fedFood && !breedFood) return false;
        return feed(p, e, s, hand, fedFood, breedFood);
    }

    private boolean ready(ServerPlayer p, Entity e, String key) {
        long left = m.getLong(e, key) - System.currentTimeMillis();
        if (left <= 0) return true;
        m.bar(p, "harvest.wait", WildlifeModule.tag("name", m.displayName(m.typeOf(e))), WildlifeModule.tag("time", WildlifeModule.time(left)));
        m.sound(p, "error");
        return false;
    }

    private boolean mayUse(ServerPlayer p, Entity e) {
        return m.mayUse(p, (ServerLevel) e.level(), e.blockPosition(), ClaimAction.BUILD);
    }

    private void done(ServerPlayer p, Entity e, WildSpecies s, String what) {
        hook(e, "Harvest");
        m.bestiary.harvested(p, s.id);
        m.feather().stat(p.getUUID(), "wild-harvests", 1);
        m.bar(p, "harvest." + what, WildlifeModule.tag("name", s.name));
    }

    private boolean milk(ServerPlayer p, Entity e, WildSpecies s, InteractionHand hand) {
        if (!ready(p, e, MILK) || !mayUse(p, e)) return true;
        WildlifeModule.useOne(p, hand, new ItemStack(Items.MILK_BUCKET));
        m.setLong(e, MILK, System.currentTimeMillis() + s.milkCooldownMs);
        m.sound(p, "milk");
        done(p, e, s, "milked");
        return true;
    }

    /** Glass bottle: the creature breathes into it (the Voidpuff's purple breath -> Dragon's Breath), then a cooldown. */
    private boolean bottle(ServerPlayer p, Entity e, WildSpecies s, InteractionHand hand) {
        if (!ready(p, e, BOTTLE) || !mayUse(p, e)) return true;
        WildlifeModule.useOne(p, hand, m.roll(s.bottle.drop()));
        m.setLong(e, BOTTLE, System.currentTimeMillis() + s.bottle.cooldownMs());
        m.sound(p, "bottle");
        Vec3 c = at(e, e.getBbHeight() * 0.4);
        ((ServerLevel) e.level()).sendParticles(net.minecraft.core.particles.PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0f),
                c.x, c.y, c.z, 12, 0.25, 0.2, 0.25, 0.01);
        done(p, e, s, "bottled");
        return true;
    }

    private boolean brush(ServerPlayer p, Entity e, WildSpecies s, InteractionHand hand) {
        if (!ready(p, e, BRUSH) || !mayUse(p, e)) return true;
        ItemStack out = m.roll(s.brush.drop());
        WildlifeModule.wear(p, hand);
        WildlifeModule.give(p, out);
        m.setLong(e, BRUSH, System.currentTimeMillis() + s.brush.cooldownMs());
        m.sound(p, "brush");
        Vec3 c = at(e, e.getBbHeight() * 0.7);
        ((ServerLevel) e.level()).sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 6, 0.3, 0.2, 0.3, 0.01);
        done(p, e, s, "brushed");
        return true;
    }

    private boolean shear(ServerPlayer p, Entity e, WildSpecies s, InteractionHand hand) {
        if (m.getLong(e, SHORN) > 0) {
            m.bar(p, "harvest.shorn", WildlifeModule.tag("name", s.name));
            m.sound(p, "error");
            return true;
        }
        if (!mayUse(p, e)) return true;
        WildlifeModule.wear(p, hand);
        WildlifeModule.give(p, m.roll(s.shear.drop()));
        m.setLong(e, SHORN, System.currentTimeMillis());
        m.setLong(e, REGROW, 0);
        hook(e, "Shorn");
        m.sound(p, "shear");
        done(p, e, s, "sheared");
        return true;
    }

    private boolean bucket(ServerPlayer p, Entity e, WildSpecies s, InteractionHand hand) {
        if (!mayUse(p, e)) return true;
        ItemStack b = m.item(s.bucket.item(), 1);
        if (b == null) {
            m.bar(p, "harvest.missing-item");
            return true;
        }
        m.finds.markBucket(b, s.id, m.kept(e));
        hook(e, "Hide");
        WildlifeModule.useOne(p, hand, b);
        m.sound(p, "bucket");
        m.bestiary.harvested(p, s.id);
        m.bar(p, "harvest.bucketed", WildlifeModule.tag("name", s.name));
        e.discard();
        return true;
    }

    private boolean feed(ServerPlayer p, Entity e, WildSpecies s, InteractionHand hand, boolean fedFood, boolean breedFood) {
        if (!mayUse(p, e)) return true;
        long now = System.currentTimeMillis();
        boolean used = false;
        if (fedFood) {
            long until = Math.max(now, m.getLong(e, FED)) + s.fed.durationMs();
            // never more than two servings ahead
            until = Math.min(until, now + 2 * s.fed.durationMs());
            m.setLong(e, FED, until);
            m.setString(e, FED_BY, p.getUUID().toString());
            used = true;
        }
        WildBreeding.Result r = breedFood && s.breedable() ? m.breeding.feed(p, e, s) : WildBreeding.Result.OFF;
        if (r == WildBreeding.Result.LOVE) used = true;
        if (!used) {
            // resting or crowded: told by WildBreeding; the food stays in hand
            return true;
        }
        WildlifeModule.useOne(p, hand, null);
        Vec3 c = at(e, e.getBbHeight() * 0.8);
        ((ServerLevel) e.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 5, 0.3, 0.2, 0.3, 0);
        m.sound(p, "feed");
        if (fedFood && r != WildBreeding.Result.LOVE) {
            m.bar(p, "harvest.fed", WildlifeModule.tag("name", s.name), WildlifeModule.tag("time", WildlifeModule.time(m.getLong(e, FED) - now)));
        }
        return true;
    }

    // ------------------------------------------------------------------ looks after a load

    /**
     * Bone visibility is saved with the native entity, but a mob carried over from Paper only has the timestamps: show
     * the shorn fleece, shed antlers or a held fish again (quietly, without the hook's sound and particles).
     */
    void restoreLook(Entity e, String type) {
        if (!(e instanceof ArcanaWildlife w)) return;
        WildSpecies s = m.species.get(type);
        if (s != null) {
            if (s.shear != null && m.getLong(e, SHORN) > 0 && !hidden(w, "fleece")) {
                w.setVar("shorn", 1);
                w.setBoneVisible("fleece", false);
            }
            if (s.antlers != null && m.getLong(e, ANTLER) > 0 && !hidden(w, "antlers")) {
                w.setVar("antlers_off", 1);
                w.setBoneVisible("antlers", false);
            }
        }
        if (type.equals(m.gullId) && m.gulls.holding(e) && w.var("holding") != 1) {
            w.setVar("holding", 1);
            w.setBoneVisible("held_fish", true);
        }
    }

    private static boolean hidden(ArcanaWildlife w, String bone) {
        for (String b : w.hiddenBones().split(",")) if (b.equals(bone)) return true;
        return false;
    }

    // ------------------------------------------------------------------ timers

    /** Every tick.harvest-seconds: eggs, truffles, regrowth, fed products, troughs, antlers, kept marks. */
    void tick(long now) {
        if (m.tracked.isEmpty()) return;
        for (Entity e : m.trackedEntities()) {
            String type = m.tracked.get(e.getUUID());
            if (type == null) continue;
            m.checkKept(e);
            WildSpecies s = m.species.get(type);
            if (s == null || !m.enabledWorld(e.level())) continue;
            try {
                tickOne(e, s, now);
            } catch (RuntimeException ex) {
                m.warnOnce("tick:" + s.id, "Wildlife: timers of " + s.id + " failed: " + ex);
            }
        }
        if (troughCheck.size() > 4096) troughCheck.clear();
    }

    private void tickOne(Entity e, WildSpecies s, long now) {
        if (s.lay != null) lay(e, s, now);
        if (s.root != null) root(e, s, now);
        if (s.shear != null && m.getLong(e, SHORN) > 0) regrow(e, s);
        if (s.fed != null) fed(e, s, now);
        if (s.antlers != null) antlers(e, s, now);
    }

    private void lay(Entity e, WildSpecies s, long now) {
        long at = m.getLong(e, LAY);
        if (at == 0) {
            m.setLong(e, LAY, now + between(s.lay.minMs(), s.lay.maxMs()));
            return;
        }
        if (now < at) return;
        m.setLong(e, LAY, now + between(s.lay.minMs(), s.lay.maxMs()));
        ItemStack egg = m.roll(s.lay.drop());
        if (egg == null) return;
        hook(e, "Lay");
        ServerLevel level = (ServerLevel) e.level();
        if (s.lay.nestRadius() > 0 && m.stations.nest(level, e.position(), s.lay.nestRadius(), egg)) return;
        m.markFrom(WildlifeModule.drop(level, at(e, 0.3), egg), s.id);
        m.soundAt(level, e.position(), "lay");
    }

    private void root(Entity e, WildSpecies s, long now) {
        WildSpecies.Root r = s.root;
        if (now < m.getLong(e, ROOT)) return;
        Mob mob = e instanceof Mob mm ? mm : null;
        if (r.leashed() && (mob == null || !mob.isLeashed())) return;
        ServerLevel level = (ServerLevel) e.level();
        BlockState under = level.getBlockState(e.blockPosition().below());
        Block ub = under.getBlock();
        if (!r.blocks().isEmpty() && !r.blocks().contains(ub)) return;
        hook(e, "Root");
        if (ThreadLocalRandom.current().nextDouble() >= r.chance()) {
            m.setLong(e, ROOT, now + r.retryMs());
            return;
        }
        m.setLong(e, ROOT, now + r.cooldownMs());
        ItemStack truffle = m.roll(r.drop());
        Vec3 at = at(e, 0.2);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, under), at.x, at.y, at.z, 12, 0.3, 0.1, 0.3, 0);
        ItemEntity dropped = WildlifeModule.drop(level, at, truffle);
        m.markFrom(dropped, s.id);
        m.soundAt(level, at, "root");
        if (mob != null && mob.isLeashed() && mob.getLeashHolder() instanceof ServerPlayer holder) {
            m.bar(holder, "harvest.rooted", WildlifeModule.tag("name", s.name));
        }
    }

    private void regrow(Entity e, WildSpecies s) {
        Block under = e.level().getBlockState(e.blockPosition().below()).getBlock();
        if (!s.shear.regrowOn().isEmpty() && !s.shear.regrowOn().contains(under)) return;
        long grown = m.getLong(e, REGROW) + m.harvestTickMs;
        if (grown < s.shear.regrowMs()) {
            m.setLong(e, REGROW, grown);
            return;
        }
        m.setLong(e, SHORN, 0);
        m.setLong(e, REGROW, 0);
        hook(e, "Regrow");
    }

    private void fed(Entity e, WildSpecies s, long now) {
        ServerLevel level = (ServerLevel) e.level();
        long until = m.getLong(e, FED);
        if (until - now < m.harvestTickMs * 3 / 2) {
            Long next = troughCheck.get(e.getUUID());
            if (next == null || now >= next) {
                troughCheck.put(e.getUUID(), now + troughCheckMs);
                if (m.stations.feedFromTrough(level, e.position(), s.fed.food())) {
                    until = Math.max(now, until) + s.fed.durationMs();
                    m.setLong(e, FED, until);
                }
            }
        }
        if (until <= now) {
            if (m.getLong(e, SILK) != 0) m.setLong(e, SILK, 0);
            if (m.getLong(e, DRIP) != 0) m.setLong(e, DRIP, 0);
            return;
        }
        if (s.silk != null) {
            long at = m.getLong(e, SILK);
            if (at == 0) m.setLong(e, SILK, now + s.silk.everyMs());
            else if (now >= at && m.stations.spin(level, e.position(), s.silk)) {
                m.setLong(e, SILK, now + s.silk.everyMs());
                hook(e, "Spin");
            }
        }
        if (s.drip != null) {
            long at = m.getLong(e, DRIP);
            if (at == 0) m.setLong(e, DRIP, now + s.drip.everyMs());
            else if (now >= at) {
                m.setLong(e, DRIP, now + s.drip.everyMs());
                ItemStack mucus = m.roll(s.drip.drop());
                m.markFrom(WildlifeModule.drop(level, at(e, 0.2), mucus), s.id);
                Vec3 c = at(e, 0.3);
                level.sendParticles(ParticleTypes.GLOW, c.x, c.y, c.z, 8, 0.2, 0.1, 0.2, 0);
            }
        }
    }

    private void antlers(Entity e, WildSpecies s, long now) {
        WildSpecies.Antlers a = s.antlers;
        long shed = m.getLong(e, ANTLER);
        if (shed > 0) {
            if (now - shed >= a.regrowMs()) {
                m.setLong(e, ANTLER, 0);
                hook(e, "AntlersOn");
            }
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= a.chance()) return;
        if (a.playerWithin() > 0 && e.level().getNearestPlayer(e, a.playerWithin()) == null) return;
        shed(e, s, now);
    }

    /** Drops the antlers now (also /wildlife shed). */
    void shed(Entity e, WildSpecies s, long now) {
        m.setLong(e, ANTLER, now);
        hook(e, "AntlersOff");
        ServerLevel level = (ServerLevel) e.level();
        m.markFrom(WildlifeModule.drop(level, at(e, 0.4), m.roll(s.antlers.drop())), s.id);
        m.soundAt(level, e.position(), "antlers");
    }

    void forget(UUID id) {
        troughCheck.remove(id);
    }

    private static long between(long min, long max) {
        return max <= min ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
