package net.axther.aftrhrs.gametest.soak;

import com.geckolib.animatable.GeoAnimatable;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.CreatureAnim;
import net.axther.aftrhrs.gametest.core.Bots;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Soak / persistence regressions (see mod/BUGS_soak.md). */
public class SoakTests {
    private static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    /**
     * CreatureAnim kept every creature's animation driver in a WeakHashMap whose value held the creature strongly, so
     * no Arcana creature (and, through it, its level) was ever garbage collected.
     */
    @GameTest(maxTicks = 400)
    public void creatureAnimationStateDoesNotLeakCreatures(GameTestHelper h) {
        List<WeakReference<Entity>> refs = new ArrayList<>();
        for (EntityType<?> t : new ArrayList<>(ArcanaEntities.all().keySet()).subList(0, 6)) {
            Entity e = h.spawn(t, new BlockPos(1, 2, 1));
            // builds the GeckoLib manager, i.e. registerControllers -> CreatureAnim.register
            ((GeoAnimatable) e).getAnimatableInstanceCache().getManagerForId(e.getId());
            check(h, CreatureAnim.driver(e) != null || ArcanaEntities.species(t).clips.isEmpty(), "no animation driver for " + t);
            refs.add(new WeakReference<>(e));
            e.discard();
        }
        h.succeedWhen(() -> {
            System.gc();
            int alive = 0;
            for (WeakReference<Entity> r : refs) if (r.get() != null) alive++;
            check(h, alive == 0, alive + " removed creatures are still in memory");
        });
    }

    /** ENTITY_VISIBLE is asked once per tracker check (two mixins asked every listener twice). */
    private static final AtomicInteger VISIBLE_ASKS = new AtomicInteger();
    private static volatile Entity visibleProbe;
    private static boolean visibleHooked;

    @GameTest
    public void entityVisibilityIsAskedOncePerCheck(GameTestHelper h) {
        if (!visibleHooked) {
            visibleHooked = true;
            FeatherEvents.ENTITY_VISIBLE.register((entity, viewer) -> {
                if (entity == visibleProbe) VISIBLE_ASKS.incrementAndGet();
                return true;
            });
        }
        Bots.Bot a = Bots.join(h, Bots.name("vis"));
        Entity pig = h.spawn(EntityTypes.PIG, new BlockPos(1, 2, 1));
        visibleProbe = pig;
        VISIBLE_ASKS.set(0);
        boolean seen = pig.broadcastToPlayer(a.player);
        visibleProbe = null;
        a.leave();
        check(h, seen, "the pig should be visible");
        check(h, VISIBLE_ASKS.get() == 1, "ENTITY_VISIBLE asked " + VISIBLE_ASKS.get() + " times for one check");
        h.succeed();
    }

    /** Placeholders are worked out lazily now; every tag still renders the same value. */
    @GameTest
    public void placeholdersRenderEveryTag(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("ph"));
        a.player.setHealth(13f);
        String raw = "<username>|<hp>/<max-hp>|<health>|<level>|<world>|<online>|<max>|<group>|<gamemode>|<x>|<server>|<hearts>|<afk>|<name>";
        String out = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(Feather.get().placeholders().format(raw, a.player, a.player));
        String[] p = out.split("\\|", -1);
        a.leave();
        check(h, p.length == 14, "bad split: " + out);
        check(h, p[0].equals(a.name()), "username: " + out);
        check(h, p[1].equals("13/20"), "hp: " + out);
        check(h, p[2].equals("6.5"), "health: " + out);
        check(h, !p[4].isEmpty() && !p[5].isEmpty() && Integer.parseInt(p[5]) >= 1, "world/online: " + out);
        check(h, p[7].equals("default"), "group: " + out);
        check(h, p[9].equals(String.valueOf(a.player.getBlockX())), "x: " + out);
        check(h, p[11].chars().filter(c -> c == '❤').count() == 10, "hearts: " + out);
        check(h, !out.contains("<"), "unresolved tag: " + out);
        h.succeed();
    }

    /** The scheduler rewrite (one removeIf pass): one-shots run once at their tick, timers keep their period, cancel works. */
    @GameTest(maxTicks = 60)
    public void schedulerRunsTasksOnTime(GameTestHelper h) {
        long start = Tasks.now();
        List<Long> ran = new ArrayList<>();
        AtomicInteger timer = new AtomicInteger();
        Tasks.later(1, () -> ran.add(Tasks.now() - start));
        Tasks.later(5, () -> ran.add(Tasks.now() - start));
        Tasks.Task cancelled = Tasks.later(3, () -> ran.add(-1L));
        cancelled.cancel();
        Tasks.Task[] t = new Tasks.Task[1];
        t[0] = Tasks.timer(2, 3, () -> {
            if (timer.incrementAndGet() == 4) t[0].cancel();
        });
        for (int i = 0; i < 500; i++) Tasks.later(2, () -> {
        });
        h.runAfterDelay(30, () -> {
            check(h, ran.size() == 2 && ran.get(0) == 1 && ran.get(1) == 5, "one-shots ran at " + ran);
            check(h, timer.get() == 4, "timer ran " + timer.get() + " times");
            h.succeed();
        });
    }

    /** Paper data on an entity nobody converts stays readable through Pdc, and the migration summary counts it. */
    @GameTest
    public void legacyEntityDataIsKeptAndCounted(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Entity pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        CompoundTag legacy = new CompoundTag();
        legacy.putString("soak:test", "kept");
        pig.setAttached(Pdc.LEGACY, legacy);
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        pig.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        String key = "entity with Paper tags, no converter (tags kept in Pdc)";
        long before = Legacy.counts().getOrDefault(key, 0L);
        level.addFreshEntity(pig);
        check(h, "kept".equals(Pdc.getString(pig, "soak:test")), "the Paper key is gone: " + Pdc.of(pig));
        check(h, Legacy.counts().getOrDefault(key, 0L) == before + 1, "not counted: " + Legacy.counts());
        check(h, Legacy.samples(key).contains("pig"), "no sample: " + Legacy.samples(key));
        // ids from world data never make new categories, and samples are capped in number and length
        String flood = "item without a native match (kept as its base item)";
        int categories = Legacy.counts().size();
        for (int i = 0; i < 500; i++) Legacy.count(flood, "x".repeat(200) + i);
        check(h, Legacy.counts().size() <= categories + 1, "a category per id");
        check(h, Legacy.samples(flood).size() <= 20, "samples not capped: " + Legacy.samples(flood).size());
        check(h, Legacy.samples(flood).stream().allMatch(x -> x.length() <= 67), "sample not truncated");
        pig.discard();
        h.succeed();
    }

    /** Every Arcana block entity (furniture, storage, machines) saves and loads back to the same data. */
    @GameTest(maxTicks = 400)
    public void everyBlockEntityRoundTrips(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (var c : ArcanaContent.entries().values()) {
            if (c.block() == null || "alias".equals(c.kind()) || c.cropStage() != null) continue;
            BlockState st = c.block().defaultBlockState();
            if (!st.hasBlockEntity()) continue;
            clear(level, at);
            if (!ArcanaContent.place(level, at, c.id(), Direction.NORTH)) continue;
            BlockEntity be = level.getBlockEntity(at);
            if (be == null) continue;
            if (be instanceof Container ct && ct.getContainerSize() > 0) {
                ct.setItem(0, new ItemStack(Items.DIAMOND, 7));
                ItemStack named = new ItemStack(Items.IRON_SWORD);
                named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Soak ✦ 名前"));
                ct.setItem(ct.getContainerSize() - 1, named);
            }
            CompoundTag saved = be.saveWithFullMetadata(level.registryAccess());
            BlockEntity copy = BlockEntity.loadStatic(at, level.getBlockState(at), saved, level.registryAccess());
            if (copy == null) {
                bad.add(c.id() + ": loadStatic returned null");
                continue;
            }
            CompoundTag again = copy.saveWithFullMetadata(level.registryAccess());
            String a = SoakPersist.canon(saved, false), b = SoakPersist.canon(again, false);
            if (!a.equals(b)) bad.add(c.id() + ": " + a + " -> " + b);
            checked++;
        }
        clear(level, at);
        check(h, checked > 0, "no block entities checked");
        check(h, bad.isEmpty(), bad.size() + " of " + checked + " block entities changed on save/load:\n" + String.join("\n", bad.subList(0, Math.min(10, bad.size()))));
        h.succeed();
    }

    private static void clear(ServerLevel level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(2, 3, 2))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
    }

    /** Every Arcana creature saves and loads back with the same owner, bond, name, variant and the rest of its data. */
    @GameTest(maxTicks = 400)
    public void everyCreatureRoundTrips(GameTestHelper h) {
        Bots.Bot owner = Bots.join(h, Bots.name("own"));
        ServerLevel level = h.getLevel();
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (EntityType<?> t : ArcanaEntities.all().keySet()) {
            Entity e = t.create(level, EntitySpawnReason.COMMAND);
            if (e == null) continue;
            BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
            e.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
            e.setCustomName(Component.literal("Soak " + n + " ✦"));
            if (e instanceof ArcanaCreature c && !c.species().untameable() && c.species().kind.tameableBase()) {
                c.tame(owner.player);
                c.setBond(3);
                c.setVariant("soak");
            }
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            e.saveWithoutId(out);
            CompoundTag first = out.buildResult();
            Entity back = EntityType.create(t, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), first), level,
                    EntitySpawnReason.LOAD).orElse(null);
            if (back == null) {
                bad.add(BuiltInRegistries.ENTITY_TYPE.getKey(t) + ": could not load");
                continue;
            }
            TagValueOutput out2 = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            back.saveWithoutId(out2);
            String a = SoakPersist.canon(first, true), b = SoakPersist.canon(out2.buildResult(), true);
            if (!a.equals(b)) bad.add(BuiltInRegistries.ENTITY_TYPE.getKey(t) + ": " + diff(a, b));
            if (back instanceof ArcanaCreature c2 && e instanceof ArcanaCreature c1) {
                if (c1.isTame() != c2.isTame() || c1.bond() != c2.bond() || !java.util.Objects.equals(c1.variant(), c2.variant())
                        || !java.util.Objects.equals(c1.getOwnerReference(), c2.getOwnerReference())) {
                    bad.add(BuiltInRegistries.ENTITY_TYPE.getKey(t) + ": tame/bond/variant/owner changed");
                }
            }
            if (back.getCustomName() == null || !back.getCustomName().getString().equals("Soak " + n + " ✦")) bad.add(BuiltInRegistries.ENTITY_TYPE.getKey(t) + ": name lost");
            n++;
        }
        owner.leave();
        check(h, n > 50, "only " + n + " creature types checked");
        check(h, bad.isEmpty(), bad.size() + " of " + n + " creatures changed on save/load:\n" + String.join("\n", bad.subList(0, Math.min(10, bad.size()))));
        h.succeed();
    }

    private static String diff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return "..." + a.substring(Math.max(0, i - 40), Math.min(a.length(), i + 60)) + " -> ..." + b.substring(Math.max(0, i - 40), Math.min(b.length(), i + 60));
    }

    /** SoakTicks tags collect per tick (space-separated, capped) and clear; works without the soak driver running. */
    @GameTest
    public void soakTickTagsCollectAndClear(GameTestHelper h) {
        SoakTicks.clearTags(); // the soak isn't on in game tests: nothing else tags or clears
        SoakTicks.tag("save");
        SoakTicks.tag("goto:Soak1:the_nether");
        check(h, SoakTicks.tags().equals("save goto:Soak1:the_nether"), "tags: '" + SoakTicks.tags() + "'");
        for (int i = 0; i < 200; i++) SoakTicks.tag("stats");
        check(h, SoakTicks.tags().length() < 300, "tags are not capped: " + SoakTicks.tags().length() + " chars");
        SoakTicks.clearTags();
        check(h, SoakTicks.tags().isEmpty(), "clear left '" + SoakTicks.tags() + "'");
        h.succeed();
    }

    /** A soak bot only breaks or clicks a block it placed when it is in the bot's world and its chunk is loaded. */
    @GameTest
    public void soakBrainSkipsBlocksInOtherWorldsOrUnloadedChunks(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos here = h.absolutePos(new BlockPos(1, 1, 1));
        check(h, SoakBrain.reachable(l, net.minecraft.core.GlobalPos.of(l.dimension(), here)), "a loaded block in the same world was skipped");
        check(h, !SoakBrain.reachable(l, net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.NETHER, here)),
                "a block in the Nether counted as reachable from " + l.dimension().identifier());
        BlockPos far = here.offset(40_000, 0, 40_000);
        check(h, l.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "the far chunk is loaded");
        check(h, !SoakBrain.reachable(l, net.minecraft.core.GlobalPos.of(l.dimension(), far)), "a block in an unloaded chunk counted as reachable");
        check(h, l.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "asking loaded the far chunk");
        h.succeed();
    }
}
