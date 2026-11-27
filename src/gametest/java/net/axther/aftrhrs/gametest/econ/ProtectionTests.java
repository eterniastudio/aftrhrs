package net.axther.aftrhrs.gametest.econ;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.claims.ClaimsModule;
import net.axther.aftrhrs.feature.locks.LocksModule;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.kyori.adventure.audience.Audience;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Claims, graves and chest locks: every way around them found in the bug test. Claims tests run in their own batch
 * (environment {@code econ_claims}) so the test claims never reach another area's tests.
 */
public class ProtectionTests {
    static final String CLAIMS = "aftrhrs_gametest:econ_claims";

    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static ClaimsModule claims() {
        return Feather.get().module(ClaimsModule.class);
    }

    /**
     * An admin claim over the test's relative columns x in [x1, x2], all z of the structure; removed again when
     * {@code body} is done (also when it throws).
     */
    static void withAdminClaim(GameTestHelper h, int x1, int x2, Consumer<Object> body) {
        ClaimsModule m = claims();
        BlockPos a = h.absolutePos(new BlockPos(x1, 0, 0)), b = h.absolutePos(new BlockPos(x2, 0, 7));
        Object area = R.call(R.cls("net.axther.aftrhrs.feature.claims.Area"), "rect", a.getX(), a.getZ(), b.getX(), b.getZ());
        Object c = R.call(m, "createAdmin", Audience.empty(), h.getLevel(), area, "bt" + Long.toString(System.nanoTime(), 36));
        check(h, c != null, "the test claim could not be created");
        try {
            body.accept(c);
        } finally {
            R.call(R.get(m, "registry"), "remove", c);
            R.call(R.get(m, "store"), "markDirty");
        }
    }

    // ------------------------------------------------------------------ claims

    /** /claim admin turns claim admin mode on (an op builds, opens containers and uses doors in any claim) and off again. */
    @GameTest(environment = CLAIMS)
    public void claimAdminModeIgnoresClaims(GameTestHelper h) {
        Bots.Bot op = Bots.join(h, Bots.name("cadm"));
        try {
            op.op(true);
            withAdminClaim(h, 0, 7, c -> {
                ClaimsModule m = claims();
                net.axther.aftrhrs.core.Loc where = net.axther.aftrhrs.core.Loc.of(h.getLevel(), h.absolutePos(new BlockPos(3, 1, 3)));
                ServerPlayer p = op.player;
                check(h, !m.allows(p, where, net.axther.aftrhrs.core.ClaimAction.BUILD), "an op may build in an admin claim without admin mode");
                op.exec("/claim admin");
                for (var a : net.axther.aftrhrs.core.ClaimAction.values())
                    check(h, m.allows(p, where, a), "claim admin mode on, but " + a + " is still refused: " + op.chat());
                check(h, m.allows(p.getUUID(), where, net.axther.aftrhrs.core.ClaimAction.BUILD), "admin mode doesn't cover checks by UUID (fans, pets)");
                op.exec("/claim admin off");
                check(h, !m.allows(p, where, net.axther.aftrhrs.core.ClaimAction.BUILD), "claim admin mode off, but the op still builds");
                op.exec("/claim admin on");
                check(h, m.allows(p, where, net.axther.aftrhrs.core.ClaimAction.BUILD), "/claim admin on didn't turn it on");
                op.exec("/claim admin");
                check(h, !m.allows(p, where, net.axther.aftrhrs.core.ClaimAction.BUILD), "/claim admin didn't toggle it off");
            });
            // losing the permission ends it, and without the permission it can't be turned on
            op.exec("/claim admin on");
            op.op(false);
            check(h, !(boolean) R.call(claims(), "bypassing", op.player), "a deopped player still ignores claims");
            op.op(true);
            op.exec("/claim admin off");
            op.op(false);
            op.exec("/claim admin on");
            op.op(true);
            check(h, !(boolean) R.call(claims(), "bypassing", op.player), "a player without the permission turned claim admin mode on");
        } finally {
            op.op(false);
            op.leave();
        }
        h.succeed();
    }

    /** An outsider's arrow presses no wooden button in a claim; an arrow nobody shot (dispenser farms) still does. */
    @GameTest(environment = CLAIMS)
    public void arrowsCannotPressClaimButtons(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("arw"));
        try {
            withAdminClaim(h, 0, 7, c -> {
                BlockPos wall = new BlockPos(3, 2, 3), btn = wall.above();
                h.setBlock(wall, Blocks.STONE);
                h.setBlock(btn, Blocks.OAK_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
                BlockPos abs = h.absolutePos(btn);
                Arrow arrow = EntityTypes.ARROW.create(h.getLevel(), EntitySpawnReason.COMMAND);
                arrow.setOwner(a.player);
                arrow.snapTo(abs.getX() + 0.5, abs.getY() + 0.05, abs.getZ() + 0.5, 0, 0);
                h.getLevel().addFreshEntity(arrow);
                BlockState s = h.getLevel().getBlockState(abs);
                s.entityInside(h.getLevel(), abs, arrow, InsideBlockEffectApplier.NOOP, true);
                check(h, !h.getLevel().getBlockState(abs).getValue(ButtonBlock.POWERED), "an outsider's arrow pressed a claim button");
                arrow.setOwner(null);
                s.entityInside(h.getLevel(), abs, arrow, InsideBlockEffectApplier.NOOP, true);
                check(h, h.getLevel().getBlockState(abs).getValue(ButtonBlock.POWERED), "an arrow nobody shot no longer presses buttons");
                arrow.discard();
            });
        } finally {
            a.leave();
        }
        h.succeed();
    }

    /** Bone meal and saplings outside a claim can't grow into it; crops right at the border still take bone meal. */
    @GameTest(environment = CLAIMS)
    public void growthStopsAtClaimBorders(GameTestHelper h) {
        withAdminClaim(h, 5, 7, c -> {
            ServerLevel level = h.getLevel();
            BlockPos grass = new BlockPos(1, 1, 3);
            h.setBlock(grass, Blocks.GRASS_BLOCK);
            h.setBlock(grass.above(), Blocks.AIR);
            ItemStack meal = new ItemStack(Items.BONE_MEAL, 4);
            check(h, !BoneMealItem.growCrop(meal, level, h.absolutePos(grass)), "bone meal on grass 4 blocks from a claim grew");
            check(h, meal.getCount() == 4, "refused bone meal was used up");
            BlockPos water = new BlockPos(1, 2, 5);
            h.setBlock(water.below(), Blocks.SAND);
            h.setBlock(water, Blocks.WATER);
            check(h, !BoneMealItem.growWaterPlant(meal, level, h.absolutePos(water), Direction.UP), "bone meal in water next to a claim grew sea grass");
            BlockPos sapling = new BlockPos(2, 1, 1);
            h.setBlock(sapling.below(), Blocks.DIRT);
            h.setBlock(sapling, Blocks.OAK_SAPLING);
            SaplingBlock sb = (SaplingBlock) Blocks.OAK_SAPLING;
            for (int i = 0; i < 3; i++) {
                BlockState st = level.getBlockState(h.absolutePos(sapling));
                if (st.getBlock() instanceof SaplingBlock) sb.advanceTree(level, h.absolutePos(sapling), st, level.getRandom());
            }
            check(h, !level.getBlockState(h.absolutePos(sapling)).is(Blocks.OAK_LOG), "a sapling next to a claim grew a tree into it");
            BlockPos crop = new BlockPos(4, 1, 6);
            h.setBlock(crop.below(), Blocks.FARMLAND);
            h.setBlock(crop, Blocks.WHEAT);
            check(h, BoneMealItem.growCrop(meal, level, h.absolutePos(crop)), "bone meal on wheat beside a claim was refused (crops grow in place)");
        });
        h.succeed();
    }

    /** Copper golems belong to the land they are created on; a wild golem led into a claim stays wild. */
    @GameTest(environment = CLAIMS)
    public void copperGolemsKeepToTheirOwnLand(GameTestHelper h) {
        withAdminClaim(h, 4, 7, c -> {
            ServerLevel level = h.getLevel();
            BlockPos inside = h.absolutePos(new BlockPos(6, 1, 3)), outside = h.absolutePos(new BlockPos(1, 1, 3));
            CopperGolem wild = h.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(1, 2, 1));
            CopperGolem home = h.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(6, 2, 1));
            check(h, !FeatherEvents.MOB_USE_CONTAINER.invoker().allow(wild, level, inside), "a wild golem may sort a claim's chest");
            check(h, FeatherEvents.MOB_USE_CONTAINER.invoker().allow(wild, level, outside), "a wild golem may not sort a wild chest");
            check(h, FeatherEvents.MOB_USE_CONTAINER.invoker().allow(home, level, inside), "a golem built in the claim may not sort its chest");
            check(h, !FeatherEvents.MOB_USE_CONTAINER.invoker().allow(home, level, outside), "a claim's golem may sort chests in the wild");
            // Led into the claim, the wild golem adopts nothing.
            wild.snapTo(inside.getX() + 0.5, inside.getY() + 1, inside.getZ() + 0.5, 0, 0);
            check(h, !FeatherEvents.MOB_USE_CONTAINER.invoker().allow(wild, level, inside), "a wild golem adopted the claim it was led into");
            check(h, Pdc.getString(wild, "feather:claim_home") == null, "a home was recorded for a wild golem");
            wild.discard();
            home.discard();
        });
        h.succeed();
    }

    /** Lightning called down with Channeling spares claim animals and villagers (natural lightning doesn't). */
    @GameTest(environment = CLAIMS)
    public void channelingSparesClaimMobs(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("chn"));
        try {
            withAdminClaim(h, 0, 7, c -> {
                Villager v = h.spawn(EntityTypes.VILLAGER, new BlockPos(3, 2, 3));
                LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(h.getLevel(), EntitySpawnReason.TRIGGERED);
                bolt.setCause(a.player);
                check(h, !FeatherEvents.THUNDER_HIT.invoker().allow(v, bolt), "an outsider's Channeling bolt may hit a claim villager");
                bolt.setCause(null);
                check(h, FeatherEvents.THUNDER_HIT.invoker().allow(v, bolt), "natural lightning is refused");
                v.discard();
            });
        } finally {
            a.leave();
        }
        h.succeed();
    }

    /** A pet answers for its owner: an outsider's wolf can't hurt claim animals. */
    @GameTest(environment = CLAIMS)
    public void petsCannotHurtClaimAnimals(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("pet"));
        try {
            withAdminClaim(h, 4, 7, c -> {
                Cow cow = h.spawn(EntityTypes.COW, new BlockPos(6, 2, 3));
                Wolf wolf = h.spawn(EntityTypes.WOLF, new BlockPos(1, 2, 3));
                wolf.tame(a.player);
                float before = cow.getHealth();
                cow.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(wolf), 2f);
                check(h, cow.getHealth() == before, "an outsider's wolf hurt a claim cow");
                cow.discard();
                wolf.discard();
            });
        } finally {
            a.leave();
        }
        h.succeed();
    }

    /** A hopper cart outside a claim can't pull from a chest cart inside it. */
    @GameTest(environment = CLAIMS)
    public void hopperCartsCannotPullFromClaims(GameTestHelper h) {
        withAdminClaim(h, 4, 7, c -> {
            MinecartHopper hopper = h.spawn(EntityTypes.HOPPER_MINECART, new BlockPos(2, 2, 3));
            MinecartChest chest = h.spawn(EntityTypes.CHEST_MINECART, new BlockPos(5, 2, 3));
            check(h, !FeatherEvents.HOPPER_PULL.invoker().allow(h.getLevel(), hopper, chest), "a hopper cart outside pulled from a claim's chest cart");
            hopper.snapTo(chest.getX() - 0.5, chest.getY(), chest.getZ(), 0, 0);
            check(h, FeatherEvents.HOPPER_PULL.invoker().allow(h.getLevel(), hopper, chest), "a hopper cart inside the claim was refused");
            hopper.discard();
            chest.discard();
        });
        h.succeed();
    }

    /** Eyes of ender are a build action: nobody fills a claim's portal frames. */
    @GameTest(environment = CLAIMS)
    public void enderEyesNeedBuildTrust(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("eye"));
        try {
            withAdminClaim(h, 0, 7, c -> {
                BlockPos frame = new BlockPos(3, 1, 3);
                h.setBlock(frame, Blocks.END_PORTAL_FRAME);
                BlockPos abs = h.absolutePos(frame);
                a.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_EYE));
                a.player.gameMode.useItemOn(a.player, h.getLevel(), a.player.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(abs).add(0, 0.4, 0), Direction.UP, abs, false));
                check(h, !h.getLevel().getBlockState(abs).getValue(EndPortalFrameBlock.HAS_EYE), "an outsider put an eye of ender into a claim's portal frame");
            });
        } finally {
            a.leave();
        }
        h.succeed();
    }

    /** Admin claims have no budget, but a 20,000-block one (a teleport between the two corners) is refused. */
    @GameTest(environment = CLAIMS)
    public void hugeAdminClaimsAreRefused(GameTestHelper h) {
        Object area = R.call(R.cls("net.axther.aftrhrs.feature.claims.Area"), "rect", 0, 0, 20_000, 16);
        Object c = R.call(claims(), "createAdmin", Audience.empty(), h.getLevel(), area, "btHuge");
        if (c != null) {
            R.call(R.get(claims(), "registry"), "remove", c);
            R.call(R.get(claims(), "store"), "markDirty");
        }
        check(h, c == null, "a 20,000-block admin claim was created");
        h.succeed();
    }

    // ------------------------------------------------------------------ the claim index and the Companion claims list

    private static Object newObject(String cls, Class<?>[] types, Object... args) {
        try {
            var c = R.cls(cls).getDeclaredConstructor(types);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object testClaim(int id, String world, Object... areas) {
        Object c = newObject("net.axther.aftrhrs.feature.claims.Claim", new Class<?>[]{int.class, UUID.class, String.class, String.class, String.class},
                id, null, "", "btIdx" + id, world);
        R.call(c, "setAreas", List.of(areas));
        return c;
    }

    private static Object rect(int x1, int z1, int x2, int z2) {
        return R.call(R.cls("net.axther.aftrhrs.feature.claims.Area"), "rect", x1, z1, x2, z2);
    }

    /** The id of the claim covering (x, z) in this registry, or 0. */
    private static int idAt(Object registry, String world, int x, int z) {
        Object c = R.call(registry, "at", world, x, z);
        return c == null ? 0 : (int) R.get(c, "id");
    }

    /**
     * The chunk index (published as a copy-on-write snapshot) finds the same claim for every column before and after a
     * save and a load of N claims (the load publishes once, in bulk), matches a plain scan of the claims, and follows
     * removals and reshapes. Runs on its own store and file, so the live claims are never touched.
     */
    @GameTest(environment = CLAIMS)
    public void claimIndexMatchesAfterABulkLoad(GameTestHelper h) throws Exception {
        String world = "btIndex";
        Path file = Files.createTempFile("aftrhrs-claims-index", ".yml");
        Object a = newObject("net.axther.aftrhrs.feature.claims.ClaimStore", new Class<?>[]{Feather.class, Path.class}, Feather.get(), file);
        Object b = newObject("net.axther.aftrhrs.feature.claims.ClaimStore", new Class<?>[]{Feather.class, Path.class}, Feather.get(), file);
        try {
            Object ra = R.get(a, "registry");
            List<Object> claims = new ArrayList<>();
            // 36 rectangles of mixed sizes (some across chunk borders, a few sharing chunks) and 4 chunk claims.
            int id = 1;
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 6; j++) {
                    int x = i * 37 - 100, z = j * 29 - 90;
                    claims.add(testClaim(id++, world, rect(x, z, x + 5 + (i * 7 + j) % 20, z + 4 + (j * 5 + i) % 17)));
                }
            }
            for (int k = 0; k < 4; k++) claims.add(testClaim(id++, world, R.call(R.cls("net.axther.aftrhrs.feature.claims.Area"), "ofChunk", 20 + k * 2, -15)));
            for (Object c : claims) R.call(ra, "add", c);
            List<String> problems = new ArrayList<>();
            List<Integer> want = new ArrayList<>();
            for (int x = -110; x <= 400; x += 3) {
                for (int z = -250; z <= 100; z += 3) {
                    int found = idAt(ra, world, x, z);
                    int scan = 0;
                    for (Object c : claims) if ((boolean) R.call(c, "contains", x, z)) scan = (int) R.get(c, "id");
                    if (found != scan) problems.add("(" + x + ", " + z + "): the index says #" + found + ", the claims say #" + scan);
                    want.add(found);
                }
            }
            check(h, want.stream().anyMatch(n -> n != 0), "no sample column hit a claim");
            R.call(a, "markDirty");
            R.call(a, "saveNow");
            R.call(b, "load");
            Object rb = R.get(b, "registry");
            check(h, ((Map<?, ?>) R.get(rb, "byId")).size() == claims.size(), "loaded " + ((Map<?, ?>) R.get(rb, "byId")).size() + " of " + claims.size() + " claims");
            int i = 0;
            for (int x = -110; x <= 400; x += 3) {
                for (int z = -250; z <= 100; z += 3) {
                    int got = idAt(rb, world, x, z);
                    if (got != want.get(i)) problems.add("(" + x + ", " + z + ") after the load: #" + got + ", before #" + want.get(i));
                    i++;
                }
            }
            // near() and overlapping() read the same index
            check(h, ((java.util.Set<?>) R.call(rb, "near", world, -100, -90, 4)).stream().anyMatch(c -> (int) R.get(c, "id") == 1), "near() misses claim #1 after the load");
            check(h, !((List<?>) R.call(rb, "overlapping", world, rect(-100, -90, -99, -89), null)).isEmpty(), "overlapping() misses claim #1 after the load");
            // a removal and a reshape are published at once
            Object loaded1 = R.call(rb, "get", 1);
            R.call(rb, "remove", loaded1);
            check(h, idAt(rb, world, -100, -90) == 0, "a removed claim is still found");
            Object loaded2 = R.call(rb, "get", 2);
            R.call(rb, "setAreas", loaded2, List.of(rect(1000, 1000, 1010, 1010)));
            check(h, idAt(rb, world, 1005, 1005) == 2 && idAt(rb, world, -100, -61) == 0, "a reshaped claim isn't found at its new place only");
            check(h, idAt(ra, world, -100, -90) == 1, "the other store's index changed with this one");
            reportProblems(h, problems);
        } finally {
            R.call(a, "close");
            R.call(b, "close");
            Files.deleteIfExists(file);
        }
        h.succeed();
    }

    /** The claims.yml text the old snapshot wrote (Yaml.set per key), kept here to prove the plain-map snapshot is byte-identical. */
    @SuppressWarnings("unchecked")
    private static String oldClaimsText(Object store) {
        net.axther.aftrhrs.core.Yaml yml = new net.axther.aftrhrs.core.Yaml();
        Object registry = R.get(store, "registry");
        yml.set("next-id", R.get(registry, "nextId"));
        for (Object c : (java.util.Collection<Object>) R.call(registry, "all")) {
            String p = "claims." + R.get(c, "id") + ".";
            UUID owner = (UUID) R.get(c, "owner");
            String ownerName = (String) R.get(c, "ownerName");
            yml.set(p + "owner", owner == null ? "admin" : owner.toString());
            yml.set(p + "owner-name", ownerName == null ? "" : ownerName);
            yml.set(p + "name", R.get(c, "name"));
            yml.set(p + "world", R.get(c, "world"));
            yml.set(p + "created", R.get(c, "created"));
            List<String> areas = new ArrayList<>();
            for (Object a : (List<Object>) R.get(c, "areas")) areas.add((String) R.call(a, "serialize"));
            yml.set(p + "areas", areas);
            Map<UUID, String> names = (Map<UUID, String>) R.get(c, "trustNames");
            for (Map.Entry<UUID, Object> e : ((Map<UUID, Object>) R.get(c, "trust")).entrySet()) {
                yml.set(p + "trust." + e.getKey() + ".level", R.call(e.getValue(), "key"));
                yml.set(p + "trust." + e.getKey() + ".name", names.getOrDefault(e.getKey(), ""));
            }
            Object pub = R.get(c, "publicTrust");
            yml.set(p + "public", pub == null ? "none" : R.call(pub, "key"));
            for (Map.Entry<Object, Boolean> e : ((Map<Object, Boolean>) R.get(c, "flags")).entrySet()) yml.set(p + "flags." + R.call(e.getKey(), "key"), e.getValue());
            if (owner == null) {
                yml.set(p + "allow-use", new ArrayList<>((java.util.Set<String>) R.get(c, "allowUse")));
                yml.set(p + "allow-break", new ArrayList<>((java.util.Set<String>) R.get(c, "allowBreak")));
                String greeting = (String) R.get(c, "greeting");
                if (greeting != null && !greeting.isEmpty()) yml.set(p + "greeting", greeting);
            }
        }
        for (Map.Entry<UUID, Object> e : ((Map<UUID, Object>) R.get(store, "budgets")).entrySet()) {
            long minutes = (long) R.get(e.getValue(), "minutes"), bonus = (long) R.get(e.getValue(), "bonus");
            if (minutes == 0 && bonus == 0) continue;
            String p = "players." + e.getKey() + ".";
            yml.set(p + "name", R.get(e.getValue(), "name"));
            yml.set(p + "minutes", minutes);
            yml.set(p + "bonus-chunks", bonus);
        }
        return R.get(R.cls("net.axther.aftrhrs.feature.claims.ClaimStore"), "HEADER") + yml.saveToString();
    }

    /**
     * claims.yml from the plain-map snapshot (dumped off the server thread) is byte-identical to what the old Yaml.set
     * snapshot wrote: admin and player claims, trust, public trust, flags, allow lists, a greeting, a claim without a
     * name, zero and non-zero budgets (one without a name); and it reads back.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @GameTest(environment = CLAIMS)
    public void claimsFileMatchesTheOldSnapshot(GameTestHelper h) throws Exception {
        Path file = Files.createTempFile("aftrhrs-claims-dump", ".yml");
        Object store = newObject("net.axther.aftrhrs.feature.claims.ClaimStore", new Class<?>[]{Feather.class, Path.class}, Feather.get(), file);
        Object back = newObject("net.axther.aftrhrs.feature.claims.ClaimStore", new Class<?>[]{Feather.class, Path.class}, Feather.get(), file);
        try {
            Object reg = R.get(store, "registry");
            Class flagC = R.cls("net.axther.aftrhrs.feature.claims.ClaimFlag"), trustC = R.cls("net.axther.aftrhrs.feature.claims.TrustLevel");
            // an empty store first: only next-id
            String empty = (String) R.call(R.cls("net.axther.aftrhrs.core.Yaml"), "dump", R.call(store, "snapshot"));
            check(h, oldClaimsText(store).equals(R.get(R.cls("net.axther.aftrhrs.feature.claims.ClaimStore"), "HEADER") + empty), "an empty store dumps differently");
            Object admin = testClaim(7, "world", rect(0, 0, 40, 30));
            ((java.util.Set<String>) R.get(admin, "allowUse")).addAll(List.of("lever", "#buttons"));
            ((java.util.Set<String>) R.get(admin, "allowBreak")).add("bank-chest");
            R.set(admin, "greeting", "<gold>Welcome to spawn");
            ((Map) R.get(admin, "flags")).put(Enum.valueOf(flagC, "PVP"), true);
            Object admin2 = testClaim(8, "world_nether", rect(-5, -5, 5, 5));
            Object mine = newObject("net.axther.aftrhrs.feature.claims.Claim", new Class<?>[]{int.class, UUID.class, String.class, String.class, String.class},
                    9, UUID.randomUUID(), "Sunny", "Sunny's farm", "world");
            R.call(mine, "setAreas", List.of(rect(100, 100, 120, 130), R.call(R.cls("net.axther.aftrhrs.feature.claims.Area"), "ofChunk", 9, 9)));
            UUID friend = UUID.randomUUID(), helper = UUID.randomUUID();
            ((Map) R.get(mine, "trust")).put(friend, Enum.valueOf(trustC, "BUILD"));
            ((Map) R.get(mine, "trustNames")).put(friend, "Friend");
            ((Map) R.get(mine, "trust")).put(helper, Enum.valueOf(trustC, "MANAGER"));
            R.set(mine, "publicTrust", Enum.valueOf(trustC, "ACCESS"));
            ((Map) R.get(mine, "flags")).put(Enum.valueOf(flagC, "MOB_SPAWNING"), false);
            ((Map) R.get(mine, "flags")).put(Enum.valueOf(flagC, "FIRE_SPREAD"), true);
            Object nameless = newObject("net.axther.aftrhrs.feature.claims.Claim", new Class<?>[]{int.class, UUID.class, String.class, String.class, String.class},
                    10, UUID.randomUUID(), null, null, "world");
            R.call(nameless, "setAreas", List.of(rect(500, 500, 510, 510)));
            for (Object c : List.of(admin, admin2, mine, nameless)) R.call(reg, "add", c);
            R.set(reg, "nextId", 11);
            Map<UUID, Object> budgets = (Map<UUID, Object>) R.get(store, "budgets");
            Object b1 = R.call(store, "budget", UUID.randomUUID());
            R.set(b1, "name", "Sunny");
            R.set(b1, "minutes", 1234L);
            R.set(b1, "bonus", -2L);
            R.call(store, "budget", UUID.randomUUID()); // zero: left out
            Object b3 = R.call(store, "budget", UUID.randomUUID());
            R.set(b3, "name", null);
            R.set(b3, "bonus", 5L);
            check(h, budgets.size() == 3, "budgets weren't made");
            String want = oldClaimsText(store);
            String got = R.get(R.cls("net.axther.aftrhrs.feature.claims.ClaimStore"), "HEADER")
                    + (String) R.call(R.cls("net.axther.aftrhrs.core.Yaml"), "dump", R.call(store, "snapshot"));
            check(h, want.equals(got), "claims.yml changed:\n--- old\n" + want + "\n--- new\n" + got);
            // the save path writes exactly that, and it reads back
            R.set(store, "dirty", true);
            R.call(store, "saveNow");
            String written = Files.readString(file);
            check(h, written.equals(want), "the saved file differs from the snapshot text:\n" + written);
            R.call(back, "load");
            check(h, ((Map<?, ?>) R.get(R.get(back, "registry"), "byId")).size() == 4, "the saved file doesn't read back");
        } finally {
            R.call(store, "close");
            R.call(back, "close");
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".bak"));
        }
        h.succeed();
    }

    private static void reportProblems(GameTestHelper h, List<String> problems) {
        if (problems.isEmpty()) return;
        throw h.assertionException(Component.literal(problems.size() + " problem(s): " + String.join("; ", problems.subList(0, Math.min(8, problems.size())))));
    }

    @SuppressWarnings("unchecked")
    private static <T> T companionField(String field) {
        try {
            var f = net.axther.aftrhrs.net.Companion.class.getDeclaredField(field);
            f.setAccessible(true);
            return (T) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Links the bot as a Companion client with these features (what a hello would agree on). */
    private static void linkCompanion(ServerPlayer p, String... features) {
        Map<UUID, net.axther.aftrhrs.net.Companion.Session> sessions = companionField("SESSIONS");
        sessions.put(p.getUUID(), new net.axther.aftrhrs.net.Companion.Session("test", java.util.Set.of(features)));
    }

    /** The Companion messages of this type sent to the player since the last call (a bot has no channel: they queue). */
    private static List<com.google.gson.JsonObject> companionSent(ServerPlayer p, String type) {
        Map<UUID, List<byte[]>> waiting = companionField("WAITING");
        List<byte[]> q = waiting.remove(p.getUUID());
        List<com.google.gson.JsonObject> out = new ArrayList<>();
        if (q == null) return out;
        Map<Integer, byte[][]> parts = new java.util.HashMap<>();
        for (byte[] payload : q) {
            com.google.gson.JsonObject o = companionDecode(payload);
            if (o == null) continue;
            if ("part".equals(o.get("t").getAsString())) {
                int id = o.get("id").getAsInt(), i = o.get("i").getAsInt(), n = o.get("n").getAsInt();
                byte[][] pieces = parts.computeIfAbsent(id, k -> new byte[n][]);
                pieces[i] = java.util.Base64.getDecoder().decode(o.get("b").getAsString());
                boolean done = true;
                for (byte[] piece : pieces) done &= piece != null;
                if (!done) continue;
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                for (byte[] piece : pieces) all.writeBytes(piece);
                o = companionDecode(all.toByteArray());
                if (o == null) continue;
            }
            if (type.equals(o.get("t").getAsString())) out.add(o);
        }
        return out;
    }

    private static com.google.gson.JsonObject companionDecode(byte[] payload) {
        try {
            byte[] body;
            if (payload[0] == 0) body = java.util.Arrays.copyOfRange(payload, 1, payload.length);
            else try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(payload, 1, payload.length - 1))) {
                body = in.readAllBytes();
            }
            return com.google.gson.JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The claims list goes only to whom a change concerns. Two modded players in other worlds (the Nether and the End,
     * away from every other claims test): a minute of accrual that doesn't move their chunk totals sends neither a
     * list; renaming a claim next to A re-sends A's list only (without the look); a reload with a changed colour sends
     * both a list with the new look.
     */
    @GameTest(environment = CLAIMS, maxTicks = 40)
    public void claimListsGoOnlyToWhomAChangeConcerns(GameTestHelper h) throws Exception {
        ClaimsModule m = claims();
        Object bridge = R.get(m, "bridge");
        var server = h.getLevel().getServer();
        ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER), end = server.getLevel(net.minecraft.world.level.Level.END);
        check(h, nether != null && end != null, "no Nether or End on the test server");
        Bots.Bot a = Bots.join(nether, Bots.name("clsA"), new Vec3(40.5, 120, 40.5));
        Bots.Bot b = Bots.join(end, Bots.name("clsB"), new Vec3(100.5, 50, 0.5));
        Object claim = null;
        Path cfg = Feather.get().getDataFolder().resolve("claims.yml");
        String original = Files.exists(cfg) ? Files.readString(cfg) : null;
        try {
            linkCompanion(a.player, "claims");
            linkCompanion(b.player, "claims");
            R.call(bridge, "tick");
            List<com.google.gson.JsonObject> fa = companionSent(a.player, "claims"), fb = companionSent(b.player, "claims");
            check(h, fa.size() == 1 && fb.size() == 1, "the first lists: A got " + fa.size() + ", B got " + fb.size());
            check(h, fa.get(0).has("look") && fb.get(0).has("look"), "a first list came without the look");

            // 1. a minute of play that doesn't move either total
            Object settings = R.get(m, "settings");
            long perHour = (long) R.get(settings, "chunksPerHour");
            for (Bots.Bot bot : List.of(a, b)) {
                Object budget = R.call(R.get(m, "store"), "budget", bot.id());
                long minutes = 0;
                while (minutes < 600 && (minutes + 1) * perHour / 60 != minutes * perHour / 60) minutes++;
                R.set(budget, "minutes", minutes);
            }
            long totalA = (long) R.call(m, "total", a.id()), totalB = (long) R.call(m, "total", b.id());
            R.call(m, "accrue");
            check(h, (long) R.call(m, "total", a.id()) == totalA && (long) R.call(m, "total", b.id()) == totalB, "the test minute moved a total");
            R.call(bridge, "tick");
            fa = companionSent(a.player, "claims");
            fb = companionSent(b.player, "claims");
            check(h, fa.isEmpty() && fb.isEmpty(), "an accrual minute that changed no total re-sent lists: A " + fa.size() + ", B " + fb.size());

            // 2. a claim next to A, then renamed: A only, without the look
            claim = R.call(m, "createAdmin", Audience.empty(), nether, rect(44, 44, 60, 60), "btNear" + Long.toString(System.nanoTime(), 36));
            check(h, claim != null, "the test claim could not be created");
            R.call(bridge, "tick");
            fa = companionSent(a.player, "claims");
            fb = companionSent(b.player, "claims");
            check(h, fa.size() == 1 && fb.isEmpty(), "a new claim next to A: A got " + fa.size() + " list(s), B (another world) " + fb.size());
            R.call(R.get(m, "commands"), "rename", a.player, claim, "btRenamed");
            R.call(bridge, "tick");
            fa = companionSent(a.player, "claims");
            fb = companionSent(b.player, "claims");
            check(h, fa.size() == 1 && fb.isEmpty(), "a rename next to A: A got " + fa.size() + " list(s), B " + fb.size());
            check(h, fa.get(0).toString().contains("btRenamed"), "A's new list lacks the new name: " + fa.get(0));
            check(h, !fa.get(0).has("look"), "a list re-sent for an edit carries the look again");

            // 3. a reload with another colour: both, with the look
            net.axther.aftrhrs.core.Yaml y = net.axther.aftrhrs.core.Yaml.load(cfg);
            y.set("view.colors.own", "#123456");
            Files.writeString(cfg, y.saveToString());
            R.call(m, "reload");
            R.call(bridge, "tick");
            fa = companionSent(a.player, "claims");
            fb = companionSent(b.player, "claims");
            check(h, fa.size() == 1 && fb.size() == 1, "after a reload: A got " + fa.size() + " list(s), B " + fb.size());
            for (com.google.gson.JsonObject o : List.of(fa.get(0), fb.get(0))) {
                check(h, o.has("look") && "#123456".equals(o.getAsJsonObject("look").get("own").getAsString()), "a list after the reload lacks the new look: " + o);
            }
        } finally {
            if (claim != null) {
                R.call(R.get(m, "registry"), "remove", claim);
                R.call(R.get(m, "store"), "markDirty");
            }
            if (original != null) Files.writeString(cfg, original);
            else Files.deleteIfExists(cfg);
            R.call(m, "reload");
            a.leave();
            b.leave();
        }
        h.succeed();
    }

    /**
     * Water from the wilderness doesn't flow into a claim (vanilla's spread, FLUID_INTO), water inside one claim flows
     * on within it, and water leaving a claim for the wilderness is fine.
     */
    @GameTest(environment = CLAIMS)
    public void waterStopsAtClaimBorders(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        withAdminClaim(h, 4, 6, c -> {
            BlockState water = Blocks.WATER.defaultBlockState();
            // wild source at x 3: east is the claim (refused), west is wild (fine)
            BlockPos wild = h.absolutePos(new BlockPos(3, 1, 2));
            level.setBlock(wild, water, 2);
            level.getFluidState(wild).tick(level, wild, level.getBlockState(wild));
            check(h, level.getFluidState(wild.east()).isEmpty(), "water flowed from the wilderness into a claim");
            check(h, !level.getFluidState(wild.west()).isEmpty(), "water didn't flow in the wilderness (the test is wrong)");
            // source in the claim at x 5: both sides are the same claim
            BlockPos in = h.absolutePos(new BlockPos(5, 1, 5));
            level.setBlock(in, water, 2);
            level.getFluidState(in).tick(level, in, level.getBlockState(in));
            check(h, !level.getFluidState(in.east()).isEmpty() && !level.getFluidState(in.west()).isEmpty(), "water didn't flow within one claim");
            // claim edge at x 6: out into the wild x 7
            BlockPos edge = h.absolutePos(new BlockPos(6, 1, 0));
            level.setBlock(edge, water, 2);
            level.getFluidState(edge).tick(level, edge, level.getBlockState(edge));
            check(h, !level.getFluidState(edge.east()).isEmpty(), "water couldn't leave a claim for the wilderness");
            // the event itself, by direction: into the claim from the west refused, along it allowed
            check(h, !FeatherEvents.FLUID_INTO.invoker().allow(level, h.absolutePos(new BlockPos(4, 1, 6)), Direction.EAST), "FLUID_INTO allowed wild -> claim");
            check(h, FeatherEvents.FLUID_INTO.invoker().allow(level, h.absolutePos(new BlockPos(5, 1, 6)), Direction.EAST), "FLUID_INTO refused claim -> same claim");
            check(h, FeatherEvents.FLUID_INTO.invoker().allow(level, h.absolutePos(new BlockPos(3, 1, 6)), Direction.WEST), "FLUID_INTO refused claim -> wild");
        });
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) level.setBlock(h.absolutePos(new BlockPos(x, 1, z)), Blocks.AIR.defaultBlockState(), 2);
        h.succeed();
    }

    // ------------------------------------------------------------------ graves

    /** Water never flows into a grave: GraveModule listens on FLUID_INTO. */
    @GameTest(maxTicks = 100)
    public void waterCannotFlowIntoGraves(GameTestHelper h) {
        Bots.Bot owner = EconTests.join(h, "grf");
        owner.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        owner.player.kill(h.getLevel());
        h.runAfterDelay(3, () -> {
            try {
                var graves = Graves.of(owner.id());
                check(h, !graves.isEmpty(), "no grave was made");
                BlockPos pos = (BlockPos) R.call(graves.get(0), "blockPos");
                ServerLevel level = h.getLevel();
                for (Direction d : Direction.values()) {
                    check(h, !FeatherEvents.FLUID_INTO.invoker().allow(level, pos, d), "water may flow into a grave moving " + d);
                }
                check(h, FeatherEvents.FLUID_INTO.invoker().allow(level, pos.above(2), Direction.DOWN), "water may not flow next to a grave (the test is wrong)");
            } finally {
                Graves.clear(owner.id());
                h.getLevel().getServer().getPlayerList().respawn(owner.player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                owner.leave();
            }
            h.succeed();
        });
    }

    /** A lava or water bucket emptied at a grave's head neither destroys it (burning the loot) nor drops the head. */
    @GameTest(maxTicks = 100)
    public void bucketsCannotWashGravesAway(GameTestHelper h) {
        Bots.Bot owner = EconTests.join(h, "grv");
        Bots.Bot other = Bots.join(h, Bots.name("grvB"));
        owner.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        owner.player.kill(h.getLevel());
        h.runAfterDelay(3, () -> {
            try {
                var graves = Graves.of(owner.id());
                check(h, !graves.isEmpty(), "no grave was made");
                BlockPos pos = (BlockPos) R.call(graves.get(0), "blockPos");
                BlockState before = h.getLevel().getBlockState(pos);
                check(h, before.is(Blocks.PLAYER_HEAD) || before.is(Blocks.PLAYER_WALL_HEAD), "the grave block is " + before);
                for (BucketItem bucket : new BucketItem[]{(BucketItem) Items.LAVA_BUCKET, (BucketItem) Items.WATER_BUCKET}) {
                    other.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bucket));
                    boolean emptied = bucket.emptyContents(other.player, h.getLevel(), pos, null);
                    check(h, !emptied && h.getLevel().getBlockState(pos).is(before.getBlock()), bucket + " washed the grave away");
                }
                check(h, EconTests.dropped(h, owner.player, Items.PLAYER_HEAD) == 0, "a player head dropped");
            } finally {
                Graves.clear(owner.id());
                h.getLevel().getServer().getPlayerList().respawn(owner.player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                owner.leave();
                other.leave();
            }
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ chest locks

    /** A locks.yml entry that can't be read (a renamed block) is kept in the file, not deleted by the next save. */
    @GameTest
    public void unreadableLocksSurviveASave(GameTestHelper h) throws Exception {
        LocksModule m = Feather.get().module(LocksModule.class);
        check(h, m != null, "the locks module is off");
        Path file = (Path) R.get(m, "file");
        String original = Files.exists(file) ? Files.readString(file) : null;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "locks:\n- world: world\n  x: 1\n  y: 2\n  z: 3\n  block: minecraft:no_such_chest_btest\n"
                    + "  owner: 00000000-0000-0000-0000-000000000001\n  owner-name: Someone\n  salt: s\n  hash: h\n  length: 4\n");
            R.call(m, "load");
            R.call(m, "save");
            ((java.util.concurrent.ExecutorService) R.get(m, "io")).submit(() -> { }).get();
            String now = Files.readString(file);
            check(h, now.contains("no_such_chest_btest"), "the unreadable lock was dropped from locks.yml:\n" + now);
        } finally {
            if (original == null) Files.deleteIfExists(file);
            else Files.writeString(file, original);
            R.call(m, "load");
        }
        h.succeed();
    }

    /**
     * The spawn claim (any claim): strangers still use the shop counters, market stalls, chess tables, shopkeepers and
     * stall keepers. Plain villagers stay protected.
     */
    @GameTest(environment = CLAIMS)
    public void publicShopAndChessWorkInClaims(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("pub"));
        try {
            withAdminClaim(h, 0, 7, c -> {
                Object prot = R.get(claims(), "protection");
                ServerPlayer p = a.player;
                ServerLevel level = h.getLevel();
                String[] ids = {"arcana_shop_counter_miner", "arcana_shop_counter_general", "arcana_market_stall_red", "arcana_chess_table"};
                for (int i = 0; i < ids.length; i++) {
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.axther.aftrhrs.AftrHrs.id(ids[i]));
                    BlockPos rel = new BlockPos(1 + i * 2 % 6, 1, 2 + i * 2 / 6 * 3);
                    h.setBlock(rel, block);
                    BlockPos abs = h.absolutePos(rel);
                    Object need = R.call(prot, "clickNeed", level, abs, level.getBlockState(abs));
                    check(h, need == null, ids[i] + " needs " + need + " in a claim");
                }
                var keeper = net.axther.aftrhrs.feature.shop.KeeperContent.TYPES.get(net.axther.aftrhrs.feature.shop.KeeperContent.Trade.GROCER)
                        .create(level, EntitySpawnReason.COMMAND);
                check(h, R.call(prot, "useNeed", p, keeper) == null, "a shopkeeper needs trust in a claim");
                Villager stallKeeper = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
                Pdc.putString(stallKeeper, "feather:stall_keeper", "test");
                check(h, R.call(prot, "useNeed", p, stallKeeper) == null, "a stall keeper needs trust in a claim");
                Villager plain = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
                check(h, R.call(prot, "useNeed", p, plain) != null, "plain villagers lost their claim protection");
                // a chess move in someone else's claim
                BlockPos table = h.absolutePos(new BlockPos(1 + 3 * 2 % 6, 1, 2 + 3 * 2 / 6 * 3));
                p.snapTo(table.getX() + 0.5, table.getY(), table.getZ() - 1.0, 0, 0);
                Object chess = Feather.get().module(R.cls("net.axther.aftrhrs.feature.chess.ChessModule").asSubclass(net.axther.aftrhrs.core.FeatherModule.class));
                Object err = R.call(chess, "check", p, level, level.getBlockEntity(table), true);
                check(h, err == null, "chess in a claim: " + err);
            });
        } finally {
            a.leave();
        }
        h.succeed();
    }
}
