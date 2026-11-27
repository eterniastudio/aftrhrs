package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMonster;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Every altar boss, staff-summoned like an altar fight (/altars summon), fights iron golems and a player to its death:
 * no exceptions, its phases fire, Wukong's clones come and go, the boss bar empties, the fight ends as a win and the
 * killer gets the trophy / relic.
 */
public class BossTests {
    static Object altars() {
        return Feather.get().module(CT.cls("net.axther.aftrhrs.feature.altars.AltarsModule").asSubclass(net.axther.aftrhrs.core.FeatherModule.class));
    }

    @SuppressWarnings("unchecked")
    static Map<UUID, Object> fights() {
        return (Map<UUID, Object>) CT.get(altars(), "fights");
    }

    /** While Wukong's palm fingers stand unclaimed, puts the player at the first one; true while it does. */
    private static boolean claimFinger(ArcanaMonster boss, ServerPlayer p, ServerLevel level) {
        try {
            Class<?> skills = Class.forName("net.axther.aftrhrs.entity.ability.WukongSkills");
            java.lang.reflect.Field fights = skills.getDeclaredField("FIGHTS"), need = skills.getDeclaredField("CLAIM");
            fights.setAccessible(true);
            need.setAccessible(true);
            Object f = ((Map<?, ?>) fights.get(null)).get(boss);
            if (f == null || !(boolean) field(f, "finale") || (boolean) field(f, "sealed")) return false;
            Vec3[] at = (Vec3[]) field(f, "fingerAt");
            int[] claim = (int[]) field(f, "claim");
            for (int i = 0; i < 5; i++) {
                if (at[i] == null || claim[i] >= need.getInt(null)) continue;
                if (p.position().distanceToSqr(at[i]) > 1) p.teleportTo(level, at[i].x, at[i].y, at[i].z, java.util.Set.of(), 0, 0, false);
                return true;
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field fl = o.getClass().getDeclaredField(name);
        fl.setAccessible(true);
        return fl.get(o);
    }

    private void fight(GameTestHelper h, String mythicId) {
        CT.arena(h, 40, 40);
        ServerLevel level = h.getLevel();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(20, 1, 12));
        p.setPermanentlyInvulnerable(true);
        p.setYRot(0);
        var src = level.getServer().createCommandSourceStack();
        level.getServer().getCommands().performPrefixedCommand(src, "altars summon " + mythicId + " " + p.getGameProfile().name());
        Species s = ArcanaEntities.species(ArcanaEntities.byMythicId(mythicId));
        List<Entity> found = level.getEntitiesOfClass(Entity.class, new AABB(p.blockPosition()).inflate(32), e -> e.getType() == ArcanaEntities.byMythicId(mythicId));
        h.assertTrue(!found.isEmpty(), "no " + mythicId + " after /altars summon");
        ArcanaMonster boss = (ArcanaMonster) found.get(0);
        h.assertTrue(fights().containsKey(boss.getUUID()), "summoned boss is not tracked as a fight");
        List<IronGolem> golems = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            IronGolem g = h.spawn(EntityTypes.IRON_GOLEM, new Vec3(14 + i * 4, 1, 28));
            g.setPersistenceRequired();
            golems.add(g);
        }
        int[] maxPhase = {0}, maxClones = {0}, sawBar = {0};
        List<String> problems = new ArrayList<>();
        boolean[] dead = {false};
        long[] diedAt = {0};
        // the player hits hard every 8 ticks (bosses shrug hits off while they arrive / change phase)
        h.onEachTick(() -> {
            long t = h.getTick();
            if (!boss.isAlive()) {
                if (!dead[0]) {
                    dead[0] = true;
                    diedAt[0] = t;
                }
                return;
            }
            if (boss.bossBar() != null && boss.bossBar().getPlayers().contains(p)) sawBar[0] = 1;
            maxPhase[0] = Math.max(maxPhase[0], boss.var("phase"));
            var cloneType = ArcanaEntities.type("wukong_clone");
            int clones = level.getEntitiesOfClass(Mob.class, boss.getBoundingBox().inflate(64), m -> m.getType() == cloneType && m.isAlive()).size();
            maxClones[0] = Math.max(maxClones[0], clones);
            // Buddha's Palm: stand at each golden finger until it is claimed, as a player does (without that the finale
            // always took its slowest path, two misses and a regrow, and the fight's length varied by thousands of ticks)
            if (mythicId.equals("ArcanaWukong") && claimFinger(boss, p, level)) return;
            if (t > 20 && t % 8 == 0) {
                // stay in reach of the fight like a real player (leash / abandon checks look for players nearby)
                if (p.distanceToSqr(boss) > 100) p.teleportTo(level, boss.getX() + 3, boss.getY(), boss.getZ(), java.util.Set.of(), 0, 0, false);
                CT.set(boss, "invulnerableTime", 0);
                boss.hurtServer(level, level.damageSources().playerAttack(p), Math.max(12f, boss.getMaxHealth() / 40f));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(dead[0], mythicId + " still alive (health " + boss.getHealth() + "/" + boss.getMaxHealth() + ")");
            h.assertTrue(h.getTick() > diedAt[0] + 130, "waiting for the death clip");
            h.assertTrue(boss.isRemoved(), "boss body not removed after its death clip");
            if (boss.bossBar() != null && !boss.bossBar().getPlayers().isEmpty()) problems.add("boss bar still shown to " + boss.bossBar().getPlayers().size());
            if (fights().containsKey(boss.getUUID())) problems.add("fight not ended by the death");
            if (sawBar[0] == 0 && boss.bossBar() != null) problems.add("the player never saw the boss bar");
            if (mythicId.equals("ArcanaWukong")) {
                if (maxPhase[0] < 3) problems.add("Wukong reached phase " + maxPhase[0] + " only");
                if (maxClones[0] == 0) problems.add("Wukong never called clones");
                var cloneType = ArcanaEntities.type("wukong_clone");
                int left = level.getEntitiesOfClass(Mob.class, new AABB(p.blockPosition()).inflate(96), m -> m.getType() == cloneType).size();
                if (left > 0) problems.add(left + " clone(s) left after Wukong died");
            }
            String trophy = Map.of("ArcanaWukong", "arcana_trophy_wukong_circlet", "ArcanaGlacialWyrm", "arcana_trophy_wyrm_horns",
                    "ArcanaRuneboundColossus", "arcana_trophy_colossus_core", "ArcanaCinderwingPhoenix", "arcana_trophy_phoenix_plume",
                    "ArcanaTempestRoc", "arcana_trophy_roc_talon").get(mythicId);
            boolean got = false;
            List<String> inv = new ArrayList<>();
            for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
                if (st.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
                inv.add(id);
                if (id.equals(trophy)) got = true;
            }
            if (!got) problems.add("no " + trophy + " for the fighter (inventory " + inv + ")");
            if (mythicId.equals("ArcanaWukong") && !inv.contains("arcana_relic_ruyi_jingu_bang")) problems.add("no Ruyi Jingu Bang for the top damage dealer");
            AftrHrs.LOG.info("[bt-creatures] {} fight: died at tick {}, phase {}, clones {}, inventory {}", mythicId, diedAt[0], maxPhase[0], maxClones[0], inv);
            for (IronGolem g : golems) if (!g.isRemoved()) g.discard();
            CT.disconnect(p);
            CT.report(h, mythicId + " fight", problems);
        });
    }

    // The redesigned fight's length varies (Peach Feast heals, invulnerable set pieces); the test player claims the
    // palm's fingers, so the finale no longer waits out two misses.
    @GameTest(maxTicks = 8000, padding = 96, skyAccess = true)
    public void wukongFight(GameTestHelper h) {
        fight(h, "ArcanaWukong");
    }

    @GameTest(maxTicks = 4500, padding = 96, skyAccess = true)
    public void glacialWyrmFight(GameTestHelper h) {
        fight(h, "ArcanaGlacialWyrm");
    }

    @GameTest(maxTicks = 4500, padding = 96, skyAccess = true)
    public void runeboundColossusFight(GameTestHelper h) {
        fight(h, "ArcanaRuneboundColossus");
    }

    @GameTest(maxTicks = 4500, padding = 96, skyAccess = true)
    public void cinderwingPhoenixFight(GameTestHelper h) {
        fight(h, "ArcanaCinderwingPhoenix");
    }

    @GameTest(maxTicks = 4500, padding = 96, skyAccess = true)
    public void tempestRocFight(GameTestHelper h) {
        fight(h, "ArcanaTempestRoc");
    }

    /** Wukong removed without dying (altar timeout / abandon / /altars end): his hair clones go too, and clones are never saved. */
    @GameTest(maxTicks = 200, padding = 96)
    public void wukongClonesLeaveWithHim(GameTestHelper h) {
        CT.arena(h, 20, 20);
        ServerLevel level = h.getLevel();
        Species wk = ArcanaEntities.species(ArcanaEntities.type("wukong"));
        Species cl = ArcanaEntities.species(ArcanaEntities.type("wukong_clone"));
        Entity boss = CT.spawn(h, wk, new Vec3(10, 1, 10));
        Entity clone = CT.spawn(h, cl, new Vec3(13, 1, 10));
        h.assertFalse(clone.shouldBeSaved(), "a clone would be saved with its chunk (its removal timer is not)");
        boss.discard();
        h.assertTrue(clone.isRemoved(), "the clone outlived Wukong's removal");
        h.succeed();
    }

    /**
     * Tempest Roc's Stormcall keeps at most 4 of its own wild wisps (cast after cast with the cooldown cleared), and when
     * the Roc is removed without dying (altar timeout / abandon / /altars end) its untamed wisps go with it, while a wisp a
     * player tamed and an unrelated wild wisp stay.
     */
    @GameTest(maxTicks = 700, padding = 64)
    public void rocStormcallCapsAndSweepsWisps(GameTestHelper h) {
        CT.arena(h, 30, 30);
        ServerLevel level = h.getLevel();
        Species roc = ArcanaEntities.species(ArcanaEntities.type("tempest_roc_boss"));
        Species wisp = ArcanaEntities.species(ArcanaEntities.type("volt_wisp"));
        ArcanaMonster boss = CT.spawn(h, roc, new Vec3(15, 1, 15));
        String tag = "aftrhrs.minion." + boss.getUUID();
        var wispType = ArcanaEntities.type("volt_wisp");
        java.util.function.Supplier<List<Mob>> tagged = () -> level.getEntitiesOfClass(Mob.class, boss.getBoundingBox().inflate(48),
                m -> m.getType() == wispType && m.isAlive() && m.entityTags().contains(tag));
        int[] most = {0};
        List<String> problems = new ArrayList<>();
        h.onEachTick(() -> {
            long t = h.getTick();
            if (boss.isRemoved() || t > 520) return;
            most[0] = Math.max(most[0], tagged.get().size());
            if (t % 20 == 0) {
                // cast after cast: clear the cooldown (and the busy retry) each time
                boss.scheduler().clearCooldown("Stormcall");
                boss.scheduler().clearCooldown("retry_ArcanaRoc_Stormcall");
                net.axther.aftrhrs.entity.ability.Abilities.runSkill(boss, "ArcanaRoc_Stormcall", null);
            }
        });
        h.runAfterDelay(540, () -> {
            List<Mob> mine = tagged.get();
            if (most[0] > 4) problems.add("Stormcall kept " + most[0] + " wisps (cap 4)");
            if (mine.size() < 2) problems.add("Stormcall called only " + mine.size() + " wisps in 25 casts");
            Mob tamed = mine.isEmpty() ? null : mine.get(0);
            if (tamed instanceof net.minecraft.world.entity.TamableAnimal ta) ta.setTame(true, false);
            Entity stray = CT.spawn(h, wisp, new Vec3(17, 1, 15));
            boss.discard();
            h.runAfterDelay(2, () -> {
                for (Mob m : mine) {
                    if (m == tamed) continue;
                    if (!m.isRemoved()) problems.add("an untamed Stormcall wisp outlived the Roc's removal");
                }
                if (tamed != null && tamed.isRemoved()) problems.add("the tamed Stormcall wisp was swept with the Roc");
                if (stray.isRemoved()) problems.add("an unrelated wild wisp was swept with the Roc");
                for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(boss.blockPosition()).inflate(64), m -> m.getType() == wispType)) m.discard();
                CT.report(h, "roc stormcall", problems);
                h.succeed();
            });
        });
    }

    /**
     * Every literal sound id the boss skills play (c.sound / Fx.sound in BossSkills, WukongSkills and BossKit, grepped when
     * this test was written) is a real sound event: Fx.sound skips an unknown one (and logs it once), so a typo would play
     * nothing.
     */
    @GameTest(maxTicks = 20)
    public void bossSoundIdsResolve(GameTestHelper h) {
        String[] ids = {
            "block.anvil.land", "block.bamboo.place", "block.beacon.activate", "block.beacon.deactivate", "block.beacon.power_select",
            "block.deepslate.break", "block.deepslate.hit", "block.fire.ambient", "block.glass.break", "block.respawn_anchor.charge",
            "block.snow.break", "block.stone.break", "entity.blaze.ambient", "entity.blaze.shoot", "entity.breeze.idle_ground",
            "entity.breeze.land", "entity.breeze.whirl", "entity.breeze.wind_burst", "entity.elder_guardian.curse",
            "entity.ender_dragon.growl", "entity.evoker.cast_spell", "entity.fox.hurt", "entity.fox.screech", "entity.fox.teleport",
            "entity.generic.explode", "entity.goat.long_jump", "entity.iron_golem.attack", "entity.iron_golem.repair",
            "entity.lightning_bolt.thunder", "entity.phantom.swoop", "entity.player.attack.knockback", "entity.player.hurt_drown",
            "entity.ravager.death", "entity.ravager.stunned", "entity.warden.sonic_charge", "entity.witch.celebrate",
            "item.firecharge.use", "item.shield.block", "item.trident.riptide_3", "item.trident.throw", "item.trident.thunder"
        };
        List<String> problems = new ArrayList<>();
        for (String id : ids) if (new Species.Sound(id, 1, 1).event() == null) problems.add("unknown sound id " + id);
        CT.report(h, "boss sound ids", problems);
        h.succeed();
    }
}
