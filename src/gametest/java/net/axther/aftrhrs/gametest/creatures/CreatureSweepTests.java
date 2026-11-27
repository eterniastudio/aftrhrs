package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaMonster;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.axther.aftrhrs.entity.Conditions;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Every Arcana creature type, spawned like a spawn egg: ticks for a minute, survives a save / load round trip with its
 * state, dies (with its drops), takes every animation call, and follows its natural spawn rules.
 */
public class CreatureSweepTests {
    private static boolean nonBoss(Species s) {
        return s.kind != Species.Kind.BOSS;
    }

    /** Who killed what (the last damage source is forgotten 2 s after the hit). */
    static final Map<java.util.UUID, String> KILLERS = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((e, level) -> {
            if (e instanceof ArcanaMob && e.getRemovalReason() == Entity.RemovalReason.DISCARDED && !KILLERS.containsKey(e.getUUID())) {
                var p = level.getNearestPlayer(e, -1);
                KILLERS.put(e.getUUID(), "discard, nearest player " + (p == null ? "none" : String.format("%.0f", p.distanceTo(e)) + " blocks"));
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            if (e instanceof ArcanaMob) KILLERS.put(e.getUUID(), src.getMsgId() + " from " + (src.getEntity() == null ? "-" : src.getEntity().getType().toShortString())
                    + (src.getDirectEntity() != null && src.getDirectEntity() != src.getEntity() ? " via " + src.getDirectEntity().getType().toShortString() : ""));
        });
    }

    /** All non-boss creatures tick for a minute next to a (creative) player: no exceptions, nobody vanishes. */
    @GameTest(maxTicks = 1400, padding = 48, skyAccess = true)
    public void soakEveryCreature(GameTestHelper h) {
        CT.arena(h, 48, 48);
        List<Species> all = CT.species(CreatureSweepTests::nonBoss);
        ServerPlayer watcher = CT.player(h, GameType.CREATIVE, new Vec3(20, 2, 20));
        Map<Entity, Species> spawned = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        int i = 0;
        for (Species s : all) {
            try {
                Entity e = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 9, 5)));
                spawned.put(e, s);
            } catch (Throwable t) {
                problems.add(s.id + " spawn: " + CT.err(t));
            }
        }
        h.runAfterDelay(1200, () -> {
            for (var en : spawned.entrySet()) {
                Entity e = en.getKey();
                Species s = en.getValue();
                String killer = KILLERS.getOrDefault(e.getUUID(), "");
                // what burns by day (undead, the Rime Wraith: minecraft:burn_in_daylight) may burn up in the soak
                boolean sunburn = (s.kind == Species.Kind.UNDEAD || e.is(net.minecraft.tags.EntityTypeTags.BURN_IN_DAYLIGHT))
                        && killer.startsWith("onFire");
                // a wild one that wandered (flew) 64+ blocks from every player despawns by design (ArcanaPet_WildUnload);
                // a monster (or the police, ArcanaMonster.removeWhenFarAway) 32+ blocks from every player may despawn at
                // random, like any vanilla monster
                java.util.regex.Matcher far = java.util.regex.Pattern.compile("nearest player ([0-9]+) blocks").matcher(killer);
                if (far.find()) {
                    int d = Integer.parseInt(far.group(1));
                    if (d >= 60 || d >= 32 && (e.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
                            || s.kind == Species.Kind.POLICE)) sunburn = true;
                }
                if (e.isRemoved() && !expectedGone(s) && !sunburn) problems.add(s.id + " was removed (" + e.getRemovalReason() + ") during the soak"
                        + " by " + KILLERS.get(e.getUUID()));
                else if (e instanceof LivingEntity le && !e.isRemoved() && le.getHealth() <= 0) problems.add(s.id + " died during the soak");
            }
            CT.disconnect(watcher);
            CT.report(h, "soak", problems);
            h.succeed();
        });
    }

    private static boolean expectedGone(Species s) {
        if (s.kind == Species.Kind.PARTICLES) return true;   // Duskflies: gone at sunrise
        // lines with remove{delay=...} @self ~onSpawn (short-lived effects)
        for (var o : s.abilities) {
            String l = Species.str(o, "line", "");
            if (l != null && l.contains("remove{") && l.contains("~onSpawn")) return true;
        }
        return false;
    }

    /** Save -> load keeps every creature's type, health, tame state, owner, bond, look, sitting and saved vars. */
    @GameTest(maxTicks = 200, padding = 48)
    public void saveLoadRoundTrip(GameTestHelper h) {
        CT.arena(h, 48, 48);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.CREATIVE, new Vec3(20, 2, 20));
        List<String> problems = new ArrayList<>();
        int i = 0;
        for (Species s : CT.species(x -> true)) {
            try {
                Entity e = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 9, 5)));
                if (e instanceof LivingEntity le) le.setHealth(Math.max(1, le.getMaxHealth() * 0.5f));
                if (e instanceof ArcanaCreature c && !s.untameable()) {
                    c.tame(owner);
                    c.setPersistenceRequired();
                    c.setBond(3);
                    c.setTint(0x336699);
                    c.setShiny(true);
                    c.setVariant("test");
                    c.setVar("gift_armed", 7);
                    c.setStaying(true, null);
                    c.setCustomName(net.minecraft.network.chat.Component.literal("Ünïcode ✦ name"));
                }
                if (e instanceof ArcanaWildlife w) {
                    w.setKept(true);
                    w.setVar("bt_var", 5);
                }
                if (e instanceof ArcanaMonster m) m.setVar("bt_var", 4);
                CompoundTag tag = CT.save(e);
                Entity back = CT.load(level, tag);
                if (back == null) {
                    problems.add(s.id + ": load returned null");
                    continue;
                }
                if (back.getType() != e.getType()) problems.add(s.id + ": type " + back.getType());
                if (back instanceof LivingEntity a && e instanceof LivingEntity b && Math.abs(a.getHealth() - b.getHealth()) > 0.01)
                    problems.add(s.id + ": health " + a.getHealth() + " != " + b.getHealth());
                if (!back.getUUID().equals(e.getUUID())) problems.add(s.id + ": uuid changed");
                if (e instanceof ArcanaCreature c && back instanceof ArcanaCreature d && !s.untameable()) {
                    if (!d.isTame()) problems.add(s.id + ": tame lost");
                    if (d.getOwnerReference() == null || !d.getOwnerReference().getUUID().equals(owner.getUUID())) problems.add(s.id + ": owner lost");
                    if (d.bond() != 3) problems.add(s.id + ": bond " + d.bond());
                    if (d.tint() != 0x336699) problems.add(s.id + ": tint " + Integer.toHexString(d.tint()));
                    if (!d.shiny()) problems.add(s.id + ": shiny lost");
                    if (!"test".equals(d.variant())) problems.add(s.id + ": variant " + d.variant());
                    if (d.var("gift_armed") != 7) problems.add(s.id + ": var lost");
                    if (!d.staying()) problems.add(s.id + ": staying lost");
                    if (d.getCustomName() == null || !d.getCustomName().getString().equals("Ünïcode ✦ name")) problems.add(s.id + ": name " + d.getCustomName());
                    if (!d.shouldBeSaved()) problems.add(s.id + ": tamed creature would not be saved");
                }
                if (back instanceof ArcanaWildlife w) {
                    if (!w.kept()) problems.add(s.id + ": kept lost");
                    if (w.var("bt_var") != 5) problems.add(s.id + ": var lost");
                }
                if (back instanceof ArcanaMonster m && m.var("bt_var") != 4) problems.add(s.id + ": var lost");
                // the loaded copy replaces the original and must tick fine (onLoad lines run one tick later)
                e.discard();
                level.addFreshEntity(back);
            } catch (Throwable t) {
                problems.add(s.id + ": " + CT.err(t));
            }
        }
        h.runAfterDelay(100, () -> {
            CT.disconnect(owner);
            CT.report(h, "save/load", problems);
            h.succeed();
        });
    }

    /** Wild tameables and wildlife are not saved with their chunk (MythicMobs WildUnload); tamed / kept / egg-only ones are. */
    @GameTest(maxTicks = 40, padding = 48)
    public void saveRules(GameTestHelper h) {
        CT.arena(h, 48, 48);
        List<String> problems = new ArrayList<>();
        int i = 0;
        for (Species s : CT.species(x -> true)) {
            Entity e = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 9, 5)));
            if (e instanceof ArcanaCreature c && !s.flag("egg_only") && c.shouldBeSaved()) problems.add(s.id + ": a wild one would be saved");
            if (e instanceof ArcanaMonster m && s.kind == Species.Kind.BOSS && m.removeWhenFarAway(10000)) problems.add(s.id + ": a boss despawns when far away");
            e.discard();
        }
        CT.report(h, "save rules", problems);
        h.succeed();
    }

    /** Every creature dies to a player's hit: death clip / skills run, wildlife and monsters drop their loot, no exceptions. */
    @GameTest(maxTicks = 500, padding = 48)
    public void deathAndDrops(GameTestHelper h) {
        CT.arena(h, 48, 48);
        ServerLevel level = h.getLevel();
        ServerPlayer killer = CT.player(h, GameType.SURVIVAL, new Vec3(20, 2, 20));
        killer.setPermanentlyInvulnerable(true);
        List<String> problems = new ArrayList<>();
        Map<Entity, Species> victims = new HashMap<>();
        int i = 0;
        for (Species s : CT.species(x -> x.kind != Species.Kind.PARTICLES)) {   // duskflies are an invulnerable swarm
            try {
                Entity e = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 9, 5)));
                victims.put(e, s);
                // Wukong can't die before his Buddha's Palm finale (WukongTests plays it): start him in his last phase
                if (s.id.equals("wukong") && e instanceof net.axther.aftrhrs.entity.ArcanaMonster m) {
                    m.setVar("phase", 4);
                    m.setVar("floor", 0);
                }
            } catch (Throwable t) {
                problems.add(s.id + " spawn: " + CT.err(t));
            }
        }
        // a hit every 5 ticks, as long as it takes (bosses shrug hits off while they arrive / change phase)
        for (int tick = 5; tick < 300; tick += 5) {
            h.runAfterDelay(tick, () -> {
                for (var en : victims.entrySet()) {
                    Entity e = en.getKey();
                    if (!e.isAlive()) continue;
                    try {
                        CT.set(e, "invulnerableTime", 0);
                        e.hurtServer(level, level.damageSources().playerAttack(killer), 100000f);
                    } catch (Throwable t) {
                        problems.add(en.getValue().id + " death: " + CT.err(t));
                    }
                }
            });
        }
        h.runAfterDelay(380, () -> {
            for (var en : victims.entrySet()) if (en.getKey().isAlive()) problems.add(en.getValue().id + ": still alive after 60 hits");
            for (var en : victims.entrySet()) {
                Species s = en.getValue();
                Entity e = en.getKey();
                boolean sure = false;
                for (Species.Drop d : s.drops) if (d.chance() >= 1 && d.min() > 0 && (d.item() != null || d.custom() != null)) sure = true;
                if (!sure) continue;
                AABB box = e.getBoundingBox().inflate(4, 6, 4);
                if (level.getEntitiesOfClass(ItemEntity.class, box).isEmpty()) problems.add(s.id + ": no drops (expected a sure drop)");
            }
            CT.disconnect(killer);
            CT.report(h, "death/drops", problems);
            h.succeed();
        });
    }

    /** play / accent / hold / stopPlay / playRole (and a boss's flinch / action) with every clip, unknown and null never throw. */
    @GameTest(maxTicks = 200, padding = 48)
    public void animationCallsNeverThrow(GameTestHelper h) {
        CT.arena(h, 48, 48);
        List<String> problems = new ArrayList<>();
        List<Entity> made = new ArrayList<>();
        int i = 0;
        for (Species s : CT.species(x -> true)) {
            Entity e;
            try {
                e = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 9, 5)));
                made.add(e);
            } catch (Throwable t) {
                problems.add(s.id + " spawn: " + CT.err(t));
                continue;
            }
            ArcanaMob m = CT.mob(e);
            List<String> clips = new ArrayList<>(s.clips.keySet());
            clips.add("no_such_clip");
            clips.add("");
            clips.add(null);
            for (String clip : clips) {
                try {
                    m.play(clip);
                    m.accent(clip);
                    m.hold(clip);
                    m.stopPlay(clip);
                    if (m instanceof ArcanaMonster mon) {
                        mon.action(clip);
                        mon.flinch(3);
                        mon.flinch(0);
                    }
                } catch (Throwable t) {
                    problems.add(s.id + " clip '" + clip + "': " + CT.err(t));
                }
            }
            for (String role : List.of("spawn", "hurt", "attack", "death", "happy", "eat", "sit", "sleep", "tame", "greet", "ability", "nope")) {
                try {
                    m.playRole(role);
                } catch (Throwable t) {
                    problems.add(s.id + " role " + role + ": " + CT.err(t));
                }
            }
            m.hold("");
        }
        h.runAfterDelay(60, () -> {
            for (Entity e : made) if (!e.isRemoved()) e.discard();
            CT.report(h, "animation", problems);
            h.succeed();
        });
    }

    /**
     * Aggressive creatures never spawn naturally by day; bosses and the police never spawn naturally at all; undead,
     * bosses and the police have no rule in the additive spawner (undead only replace vanilla spawns).
     */
    @GameTest(maxTicks = 40)
    public void aggressiveOnlyAtNight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<String> problems = new ArrayList<>();
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        try {
            h.setTime(day0 + 6000);
            for (Species s : CT.species(x -> true)) {
                boolean aggressive = net.axther.aftrhrs.entity.ArcanaEntities.aggressive(s);
                boolean never = s.kind == Species.Kind.BOSS || s.kind == Species.Kind.POLICE;
                if (!aggressive && !never) continue;
                for (int k = 0; k < 50; k++) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    boolean ok = SpawnPlacements.checkSpawnRules((net.minecraft.world.entity.EntityType) CT.type(s), level, EntitySpawnReason.NATURAL, pos, level.getRandom());
                    if (ok) {
                        problems.add(s.id + ": natural spawn allowed at noon");
                        break;
                    }
                }
            }
            h.setTime(day0 + 18000);
            for (Species s : CT.species(x -> x.kind == Species.Kind.BOSS || x.kind == Species.Kind.POLICE)) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                boolean ok = SpawnPlacements.checkSpawnRules((net.minecraft.world.entity.EntityType) CT.type(s), level, EntitySpawnReason.NATURAL, pos, level.getRandom());
                if (ok) problems.add(s.id + ": natural spawn allowed at night");
            }
        } finally {
            h.setTime(before);
        }
        for (Species s : CT.species(x -> x.kind == Species.Kind.BOSS || x.kind == Species.Kind.POLICE || x.kind == Species.Kind.UNDEAD)) {
            for (Species.Spawn rule : s.spawns) {
                for (String b : rule.biomes()) {
                    if (net.axther.aftrhrs.entity.ArcanaSpawner.rulesAt(rule.dimension(), b).stream().anyMatch(r -> r.species == s)) {
                        problems.add(s.id + ": in the additive spawner at " + b);
                    }
                }
            }
        }
        CT.report(h, "spawn rules", problems);
        h.succeed();
    }

    /** A tamed hostile-kind pet (Cinder Imp, Volt Wisp ...) is not deleted when the difficulty is Peaceful. */
    @GameTest(maxTicks = 40)
    public void peacefulKeepsTamedPets(GameTestHelper h) {
        CT.arena(h, 10, 10);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.CREATIVE, new Vec3(4, 2, 4));
        List<String> problems = new ArrayList<>();
        List<ArcanaCreature> pets = new ArrayList<>();
        List<ArcanaCreature> wild = new ArrayList<>();
        int i = 0;
        for (Species s : CT.species(x -> x.kind == Species.Kind.HOSTILE)) {
            ArcanaCreature c = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 4, 2)));
            wild.add(c);
            if (s.untameable()) continue;
            ArcanaCreature t = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 4, 2)));
            t.tamedBy(owner);
            pets.add(t);
        }
        Difficulty was = level.getDifficulty();
        try {
            level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
            for (ArcanaCreature c : pets) c.checkDespawn();
            for (ArcanaCreature c : wild) c.checkDespawn();
        } finally {
            level.getServer().setDifficulty(was, true);
        }
        for (ArcanaCreature c : pets) if (c.isRemoved()) problems.add(c.species().id + ": tamed pet deleted on Peaceful");
        for (ArcanaCreature c : wild) if (!c.isRemoved()) problems.add(c.species().id + ": wild hostile stays on Peaceful");
        for (ArcanaCreature c : pets) if (!c.isRemoved()) c.discard();
        CT.disconnect(owner);
        CT.report(h, "peaceful", problems);
        h.succeed();
    }

    /**
     * Species resolves its hot values once (flags, sections, base, despawn, life, sfx events, aquatic switches): for
     * every loaded species they must match what the raw json says, read the way the old per-call lookups did.
     */
    @GameTest(maxTicks = 20)
    public void speciesCacheMatchesJson(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        for (Species s : CT.species(x -> true)) {
            com.google.gson.JsonObject j = s.json;
            String base = Species.str(j, "base", "").toUpperCase(java.util.Locale.ROOT);
            if (!base.equals(s.base)) problems.add(s.id + ": base " + s.base + " != " + base);
            if (!Species.str(j, "despawn", "true").equals(s.despawn)) problems.add(s.id + ": despawn " + s.despawn);
            for (Map.Entry<String, com.google.gson.JsonElement> e : j.entrySet()) {
                String k = e.getKey();
                com.google.gson.JsonElement v = e.getValue();
                boolean flag = v.isJsonPrimitive() && v.getAsBoolean();
                if (s.flag(k) != flag) problems.add(s.id + ": flag(" + k + ") " + s.flag(k));
                com.google.gson.JsonObject sec = v.isJsonObject() ? v.getAsJsonObject() : null;
                if (s.section(k) != sec) problems.add(s.id + ": section(" + k + ") is not the json object");
            }
            if (s.flag("no_such_key") || s.section("no_such_key") != null) problems.add(s.id + ": a missing key reads as set");
            if (s.hostile() != s.flag("hostile") || s.flyer() != s.flag("flyer") || s.eggOnly() != s.flag("egg_only")
                    || s.noNap() != s.flag("no_nap") || s.panicRun() != s.flag("panic_run") || s.untameable() != s.flag("untameable"))
                problems.add(s.id + ": a cached flag differs from flag()");
            if (s.young() != j.has("young_of") || s.quietCond() != j.has("quiet_cond")) problems.add(s.id + ": young / quiet_cond");
            if (s.speed() != Species.dbl(j, "speed", 0.25) || s.followRange() != Species.dbl(j, "follow_range", 16)) problems.add(s.id + ": speed / follow range");
            com.google.gson.JsonObject life = j.has("life") && j.get("life").isJsonObject() ? j.getAsJsonObject("life") : null;
            if (s.life != life) problems.add(s.id + ": life is not the json object");
            if (life != null) {
                if (s.lifeEvery != (int) Species.dbl(life, "every", 100)) problems.add(s.id + ": life every " + s.lifeEvery);
                if (s.lifeChance(0.3) != Species.dbl(life, "chance", 0.3) || s.lifeChance(0.4) != Species.dbl(life, "chance", 0.4))
                    problems.add(s.id + ": life chance " + s.lifeChance(-1));
            } else if (s.lifeEvery != 0) problems.add(s.id + ": life every without a life table");
            com.google.gson.JsonObject sfx = j.has("sfx") && j.get("sfx").isJsonObject() ? j.getAsJsonObject("sfx") : null;
            java.util.Set<String> events = sfx == null || !sfx.has("events") ? java.util.Set.of() : sfx.getAsJsonObject("events").keySet();
            if (!s.sfxEvents.keySet().equals(events)) problems.add(s.id + ": sfx events " + s.sfxEvents.keySet() + " != " + events);
            for (String ev : events) if (s.sfxEvents.get(ev) != sfx.getAsJsonObject("events").getAsJsonObject(ev)) problems.add(s.id + ": sfx event " + ev);
            if (s.sfxSplash != events.contains("splash") || s.sfxFlap != events.contains("flap") || s.sfxGallop != events.contains("gallop"))
                problems.add(s.id + ": splash / flap / gallop switches");
            com.google.gson.JsonObject aq = s.aquatic();
            if (aq != (j.has("aquatic") ? j.getAsJsonObject("aquatic") : null)) problems.add(s.id + ": aquatic");
            boolean follow = aq != null && aq.has("follow") && aq.get("follow").getAsBoolean();
            boolean hover = aq != null && aq.has("hover") && aq.get("hover").getAsBoolean();
            if (s.aquaticFollow != follow || s.aquaticHover != hover) problems.add(s.id + ": aquatic follow / hover");
            com.google.gson.JsonObject mount = j.has("mount") ? j.getAsJsonObject("mount") : null;
            com.google.gson.JsonObject motion = mount != null && mount.has("motion") ? mount.getAsJsonObject("motion") : null;
            if (s.mountData() != mount || s.motion() != motion) problems.add(s.id + ": mount / motion");
        }
        CT.report(h, "species cache", problems);
        h.succeed();
    }

    /**
     * The section-aware block scan (Conditions.blockNear / scanBox, PetSkills.blocks): a block exactly at the box edge
     * is found, one a block further is not, a corner across a chunk and a section boundary is found, an all-air box and
     * an unloaded chunk find nothing (and the scan does not load the chunk). blocks() keeps its squashed sphere.
     */
    @GameTest(maxTicks = 20, padding = 32)
    public void blockScanEdgesAndBoundaries(GameTestHelper h) {
        CT.arena(h, 24, 24);
        ServerLevel level = h.getLevel();
        BlockPos ref = h.absolutePos(new BlockPos(4, 5, 4));
        // the centre sits 2 blocks before a chunk edge on x / z and a section edge on y, so +3 crosses all three
        BlockPos c = new BlockPos((ref.getX() & ~15) + 14, (ref.getY() & ~15) + 14, (ref.getZ() & ~15) + 14);
        Entity probe = net.minecraft.world.entity.EntityTypes.MARKER.create(level, EntitySpawnReason.COMMAND);
        h.assertTrue(probe != null, "no marker");
        probe.setPos(Vec3.atBottomCenterOf(c));
        BlockState lava = Blocks.LAVA.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        Predicate<BlockState> isLava = st -> st.getFluidState().is(Fluids.LAVA);
        Class<?> petSkills = CT.cls("net.axther.aftrhrs.entity.ability.PetSkills");
        List<String> problems = new ArrayList<>();
        int r = 3;
        // {dx, dy, dz, blockNear finds it, blocks() finds it}
        int[][] cases = {
                {3, 0, 0, 1, 1}, {-3, 0, 0, 1, 1}, {0, 3, 0, 1, 1}, {0, 0, -3, 1, 1},
                {3, 3, 0, 1, 1},      // across the chunk edge (x) and the section edge (y)
                {0, 3, 3, 1, 1},      // across the chunk edge (z) and the section edge (y)
                {3, 3, 3, 1, 0},      // the far corner: in the box, outside blocks()' sphere
                {-3, -3, -3, 1, 0},
                {4, 0, 0, 0, 0}, {0, 4, 0, 0, 0}, {0, 0, 4, 0, 0}, {-4, 0, 0, 0, 0}, {0, -4, 0, 0, 0}, {4, 4, 4, 0, 0},
        };
        for (int[] k : cases) {
            BlockPos at = c.offset(k[0], k[1], k[2]);
            level.setBlock(at, lava, 2 | 16);
            boolean near = Conditions.blockNear(probe, r, r, isLava);
            @SuppressWarnings("unchecked")
            List<BlockPos> found = (List<BlockPos>) CT.call(petSkills, "blocks", probe, r, r, isLava);
            level.setBlock(at, air, 2 | 16);
            String where = "lava at " + k[0] + "," + k[1] + "," + k[2];
            if (near != (k[3] == 1)) problems.add(where + ": blockNear " + near);
            if (found.contains(at) != (k[4] == 1)) problems.add(where + ": blocks() " + found);
            if (found.size() > 1 || !found.isEmpty() && !found.contains(at)) problems.add(where + ": blocks() found extra " + found);
        }
        // all air: a box high above the arena (its sections are empty)
        probe.setPos(Vec3.atBottomCenterOf(c.above(48)));
        if (Conditions.blockNear(probe, r, r, st -> !st.isAir())) problems.add("an all-air box found a block");
        // an unloaded chunk: nothing found, and the scan does not load it
        int far = (c.getX() >> 4) + 4000;
        if (level.getChunkSource().getChunkNow(far, c.getZ() >> 4) == null) {
            boolean hit = Conditions.scanBox(level, far << 4, c.getY() - 2, c.getZ() - 2, (far << 4) + 4, c.getY() + 2, c.getZ() + 2, st -> true, p -> true);
            if (hit) problems.add("an unloaded chunk was scanned");
            if (level.getChunkSource().getChunkNow(far, c.getZ() >> 4) != null) problems.add("the scan loaded a chunk");
        } else {
            problems.add("the far test chunk is already loaded");
        }
        CT.report(h, "block scan", problems);
        h.succeed();
    }

    /**
     * Every registered aftrhrs sound event has a sounds.json entry and every sounds.json entry is registered (CreatureSfx
     * registers them from the species' sfx blocks; tools/sfx_gen.py writes sounds.json), so dead ids cannot pile up in
     * the registry sync every player gets at login.
     */
    @GameTest
    public void soundEventsMatchSoundsJson(GameTestHelper h) {
        java.util.Set<String> registered = new java.util.TreeSet<>();
        for (var id : net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.keySet()) {
            if (id.getNamespace().equals(net.axther.aftrhrs.AftrHrs.ID)) registered.add(id.getPath());
        }
        java.util.Set<String> json = new java.util.TreeSet<>();
        try (var in = CreatureSweepTests.class.getResourceAsStream("/assets/aftrhrs/sounds.json")) {
            if (in == null) throw h.assertionException(net.minecraft.network.chat.Component.literal("no assets/aftrhrs/sounds.json on the classpath"));
            json.addAll(com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().keySet());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        List<String> problems = new ArrayList<>();
        for (String id : registered) if (!json.contains(id)) problems.add("registered without a sounds.json entry: " + id);
        for (String id : json) if (!registered.contains(id)) problems.add("sounds.json entry not registered: " + id);
        if (registered.isEmpty()) problems.add("no aftrhrs sound events registered");
        net.axther.aftrhrs.AftrHrs.LOG.info("[bt-creatures] sound events: {} registered, {} in sounds.json", registered.size(), json.size());
        CT.report(h, "sound events", problems);
        h.succeed();
    }
}
