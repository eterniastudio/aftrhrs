package net.axther.aftrhrs.gametest.econ;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.feature.coast.Parts;
import net.axther.aftrhrs.feature.locks.LocksModule;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Things the world must never destroy: dropped money, a grave's spilled items, locked chests, PIN lockouts. */
public class SafetyTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    /** Dropped Goob Coins shrug off cactus, explosions, lightning and fire; a plain gold nugget still burns (control). */
    @GameTest
    public void droppedCoinsSurviveTheWorld(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 2, 1)));
        ItemStack coins = ArcanaContent.stack("arcana_goob_coin", 5);
        check(h, coins != null && !coins.isEmpty(), "no Goob Coin item");
        ItemEntity coin = new ItemEntity(level, at.x, at.y, at.z, coins);
        ItemEntity nugget = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.GOLD_NUGGET, 5));
        level.addFreshEntity(coin);
        level.addFreshEntity(nugget);
        var src = level.damageSources();
        for (DamageSource s : new DamageSource[]{src.cactus(), src.explosion(null, null), src.lightningBolt(), src.inFire(),
                src.lava(), src.fallingBlock(null), src.anvil(null)}) {
            coin.hurtServer(level, s, 100);
            check(h, coin.isAlive(), "a dropped coin was destroyed by " + s.getMsgId());
        }
        nugget.hurtServer(level, src.cactus(), 100);
        check(h, !nugget.isAlive(), "a plain gold nugget survived cactus (the test is wrong)");
        coin.discard();
        h.succeed();
    }

    /** A grave that expires with lava beside it lays its items down out of the lava's reach, and they're all still there later. */
    @GameTest(maxTicks = 300)
    public void expiredGravesSpillAwayFromLava(GameTestHelper h) {
        Bots.Bot owner = EconTests.join(h, "grl");
        owner.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        owner.player.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 7));
        owner.player.kill(h.getLevel());
        h.runAfterDelay(3, () -> {
            ServerLevel level = h.getLevel();
            BlockPos[] grave = new BlockPos[1];
            try {
                var graves = Graves.of(owner.id());
                check(h, !graves.isEmpty(), "no grave was made");
                Object g = graves.get(0);
                grave[0] = (BlockPos) R.call(g, "blockPos");
                // Lava has crept up beside the grave since it was made; it pours into the spot once the head is gone.
                level.setBlockAndUpdate(grave[0].east(), Blocks.LAVA.defaultBlockState());
                R.call(Graves.module(), "expire", g, "grave.expired", null);
                check(h, Graves.of(owner.id()).isEmpty(), "the grave didn't expire");
            } catch (RuntimeException e) {
                cleanUp(h, owner, grave[0]);
                throw e;
            }
            h.runAfterDelay(120, () -> {
                try {
                    int diamonds = 0, logs = 0;
                    for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(grave[0]).inflate(12))) {
                        if (e.getItem().is(Items.DIAMOND)) diamonds += e.getItem().getCount();
                        if (e.getItem().is(Items.OAK_LOG)) logs += e.getItem().getCount();
                    }
                    check(h, diamonds == 3 && logs == 7, "the spilled grave lost items to the lava: " + diamonds + " diamonds, " + logs + " logs left");
                } finally {
                    cleanUp(h, owner, grave[0]);
                }
                h.succeed();
            });
        });
    }

    /**
     * graves.yml keeps each item's saved form until the item changes: after taking part of a stack (and the save that
     * follows), the file holds the new count, and every other item reads back exactly as it went in.
     */
    @GameTest(maxTicks = 100)
    public void graveSavesKeepCountsAndComponents(GameTestHelper h) {
        Bots.Bot owner = EconTests.join(h, "grk");
        ServerLevel level = h.getLevel();
        owner.player.getInventory().clearContent();
        ItemStack blade = new ItemStack(Items.DIAMOND_SWORD);
        var sharpness = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
        blade.enchant(sharpness, 3);
        blade.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Grave Blade"));
        blade.setDamageValue(17);
        ItemStack bladeCopy = blade.copy();
        owner.player.getInventory().setItem(0, blade);
        owner.player.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 40));
        owner.player.kill(level);
        h.runAfterDelay(3, () -> {
            try {
                List<Object> graves = Graves.of(owner.id());
                check(h, graves.size() == 1, "expected one grave, got " + graves.size());
                Object g = graves.get(0);
                Object logs = null;
                for (Object e : Graves.entries(g)) if (Graves.item(e) != null && Graves.item(e).is(Items.OAK_LOG)) logs = e;
                check(h, logs != null && Graves.item(logs).getCount() == 40, "the logs aren't in the grave");
                check(h, R.get(logs, "data") != null, "the death save didn't keep the items' saved form");
                // Back from the dead with room for only 34 more logs: 6 stay in the grave.
                ServerPlayer now = level.getServer().getPlayerList().respawn(owner.player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                now.getInventory().clearContent();
                for (int i = 0; i < 36; i++) now.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
                now.getInventory().setItem(9, new ItemStack(Items.OAK_LOG, 30));
                R.call(Graves.module(), "give", now, logs, false);
                check(h, Graves.item(logs) != null && Graves.item(logs).getCount() == 6, "6 logs should be left: " + Graves.item(logs));
                check(h, R.get(logs, "data") == null, "changing an item kept its old saved form");
                Object back = Graves.reread(g);
                int logsBack = 0;
                ItemStack bladeBack = null;
                for (Object e : Graves.entries(back)) {
                    ItemStack it = Graves.item(e);
                    if (it.is(Items.OAK_LOG)) logsBack += it.getCount();
                    if (it.is(Items.DIAMOND_SWORD)) bladeBack = it;
                }
                check(h, logsBack == 6, "graves.yml holds " + logsBack + " logs, not 6");
                check(h, bladeBack != null && ItemStack.isSameItemSameComponents(bladeBack, bladeCopy) && bladeBack.getCount() == 1,
                        "the sword came back different: " + bladeBack + " " + (bladeBack == null ? "" : bladeBack.getComponents()));
                check(h, Graves.entries(back).size() == Graves.entries(g).size(), "graves.yml lost or added entries");
            } finally {
                Graves.clear(owner.id());
                owner.leave();
            }
            h.succeed();
        });
    }

    private static void cleanUp(GameTestHelper h, Bots.Bot owner, BlockPos around) {
        ServerLevel level = h.getLevel();
        Graves.clear(owner.id());
        if (around != null) {
            for (BlockPos b : BlockPos.betweenClosed(around.offset(-8, -3, -8), around.offset(8, 3, 8))) {
                if (level.getFluidState(b).is(net.minecraft.tags.FluidTags.LAVA)) level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
            }
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(12))) e.discard();
        }
        level.getServer().getPlayerList().respawn(owner.player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        owner.leave();
    }

    /** The ender dragon flies through plain blocks but can't tear out a locked chest. */
    @GameTest
    public void dragonCannotTearOutLockedChests(GameTestHelper h) throws Exception {
        LocksModule m = Feather.get().module(LocksModule.class);
        check(h, m != null, "the locks module is off");
        ServerLevel level = h.getLevel();
        BlockPos chest = h.absolutePos(new BlockPos(1, 1, 1)), plain = h.absolutePos(new BlockPos(2, 1, 1));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(plain, Blocks.STONE.defaultBlockState());
        Object lock = newLock(level, chest);
        @SuppressWarnings("unchecked") Map<Object, Object> locks = (Map<Object, Object>) R.get(m, "locks");
        Object key = R.get(lock, "pos");
        locks.put(key, lock);
        try {
            EnderDragon dragon = EntityTypes.ENDER_DRAGON.create(level, EntitySpawnReason.COMMAND);
            check(h, dragon != null, "no dragon");
            R.call(dragon, "checkWalls", level, new AABB(chest).minmax(new AABB(plain)));
            check(h, level.getBlockState(chest).is(Blocks.CHEST), "the dragon tore out a locked chest");
            check(h, level.getBlockState(plain).isAir(), "the dragon didn't break the plain block (mob griefing off? the test is wrong)");
        } finally {
            locks.remove(key);
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }

    /**
     * Fire whose burn roll always passes (a stub RandomSource: nextInt is 0) burns a plain plank beside it but not a bank
     * block, a mailbox or a locked block next to the same fire: the protection hooks run after vanilla's burn-odds roll.
     * Bank and mailbox spots are position records, so planks stand in for the chest; locks only cover chests and barrels
     * (not flammable), so planks are made lockable for the test.
     */
    @SuppressWarnings("unchecked")
    @GameTest
    public void fireSparesProtectedBlocksAfterTheBurnRoll(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        LocksModule locksModule = Feather.get().module(LocksModule.class);
        var bank = Feather.get().module(net.axther.aftrhrs.feature.bank.BankModule.class);
        var mail = Feather.get().module(net.axther.aftrhrs.feature.mail.MailModule.class);
        check(h, locksModule != null && bank != null && mail != null, "the locks, bank or mail module is off");
        BlockPos fire = h.absolutePos(new BlockPos(3, 1, 3));
        BlockPos bankAt = fire.east(), mailAt = fire.north(), lockedAt = fire.west(), plain = fire.south();
        for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) level.setBlock(h.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), 2);
        for (BlockPos b : new BlockPos[]{bankAt, mailAt, lockedAt, plain}) level.setBlock(b, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        java.util.Set<Object> banks = (java.util.Set<Object>) R.get(R.get(bank, "store"), "banks");
        java.util.Set<Object> mailboxes = (java.util.Set<Object>) R.get(R.get(mail, "store"), "mailboxes");
        java.util.Set<Object> lockable = (java.util.Set<Object>) R.get(locksModule, "lockable");
        Map<Object, Object> locks = (Map<Object, Object>) R.get(locksModule, "locks");
        Object bankPos = R.call(R.cls("net.axther.aftrhrs.feature.bank.BankStore$Pos"), "of", level, bankAt);
        Object mailPos = R.call(R.cls("net.axther.aftrhrs.feature.mail.MailStore$Pos"), "of", level, mailAt);
        boolean addedLockable = lockable.add(Blocks.OAK_PLANKS);
        Object lock = newLock(level, lockedAt);
        Object lockKey = R.get(lock, "pos");
        // fire only ticks near a player
        Bots.Bot near = Bots.join(h, Bots.name("fir"));
        banks.add(bankPos);
        mailboxes.add(mailPos);
        locks.put(lockKey, lock);
        try {
            level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
            net.minecraft.util.RandomSource always = new net.minecraft.world.level.levelgen.LegacyRandomSource(1) {
                @Override
                public int nextInt(int bound) {
                    return 0;
                }

                @Override
                public float nextFloat() {
                    return 0.99f;
                }
            };
            java.lang.reflect.Method tick = net.minecraft.world.level.block.FireBlock.class.getDeclaredMethod("tick",
                    BlockState.class, ServerLevel.class, BlockPos.class, net.minecraft.util.RandomSource.class);
            tick.setAccessible(true);
            tick.invoke(Blocks.FIRE, level.getBlockState(fire), level, fire, always);
            check(h, !level.getBlockState(plain).is(Blocks.OAK_PLANKS), "the plain plank beside the fire didn't burn (the test is wrong): " + level.getBlockState(plain));
            check(h, level.getBlockState(bankAt).is(Blocks.OAK_PLANKS), "fire burned a bank block: " + level.getBlockState(bankAt));
            check(h, level.getBlockState(mailAt).is(Blocks.OAK_PLANKS), "fire burned a mailbox: " + level.getBlockState(mailAt));
            check(h, level.getBlockState(lockedAt).is(Blocks.OAK_PLANKS), "fire burned a locked block: " + level.getBlockState(lockedAt));
        } finally {
            locks.remove(lockKey);
            banks.remove(bankPos);
            mailboxes.remove(mailPos);
            if (addedLockable) lockable.remove(Blocks.OAK_PLANKS);
            for (BlockPos b : BlockPos.betweenClosed(fire.offset(-3, 0, -3), fire.offset(3, 3, 3))) {
                if (!level.getBlockState(b).isAir()) level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
            }
            near.leave();
        }
        h.succeed();
    }

    /** A player locked out of a PIN lock stays locked out across a restart (save, then load). */
    @GameTest
    public void pinLockoutsSurviveARestart(GameTestHelper h) throws Exception {
        LocksModule m = Feather.get().module(LocksModule.class);
        check(h, m != null, "the locks module is off");
        ServerLevel level = h.getLevel();
        BlockPos chest = h.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        java.nio.file.Path file = (java.nio.file.Path) R.get(m, "file");
        String original = java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : null;
        Object lock = newLock(level, chest);
        @SuppressWarnings("unchecked") Map<Object, Object> locks = (Map<Object, Object>) R.get(m, "locks");
        UUID guesser = UUID.randomUUID();
        long until = System.currentTimeMillis() + 600_000L;
        try {
            @SuppressWarnings("unchecked") Map<UUID, long[]> attempts = (Map<UUID, long[]>) R.get(lock, "attempts");
            attempts.put(guesser, new long[]{0, until});
            locks.put(R.get(lock, "pos"), lock);
            R.call(m, "save");
            ((java.util.concurrent.ExecutorService) R.get(m, "io")).submit(() -> { }).get();
            R.call(m, "load");
            Object loaded = R.call(m, "lockAt", level, chest);
            check(h, loaded != null, "the lock didn't come back");
            @SuppressWarnings("unchecked") Map<UUID, long[]> back = (Map<UUID, long[]>) R.get(loaded, "attempts");
            check(h, back.containsKey(guesser) && back.get(guesser)[1] == until, "the lockout was lifted by the restart: " + back.keySet());
        } finally {
            if (original == null) java.nio.file.Files.deleteIfExists(file);
            else java.nio.file.Files.writeString(file, original);
            R.call(m, "load");
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }

    /** Furniture display parts (fish on plaques, tanks, seaweed overlays, string-light bulbs) are never written to the world. */
    @GameTest
    public void furniturePartsAreNeverSaved(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 2, 1)));
        var d = Parts.item(level, at, 0, new ItemStack(Items.COD), ItemDisplayContext.FIXED, com.mojang.math.Transformation.IDENTITY,
                "feather:fish_part", "test");
        try {
            check(h, d.isAlive(), "the part was removed as a leftover the moment it spawned");
            check(h, !d.shouldBeSaved(), "a furniture part would be saved with the chunk");
        } finally {
            Parts.remove(d);
        }
        h.succeed();
    }

    private static Object newLock(ServerLevel level, BlockPos at) throws Exception {
        Class<?> c = R.cls("net.axther.aftrhrs.feature.locks.LocksModule$Lock");
        Constructor<?> k = c.getDeclaredConstructor();
        k.setAccessible(true);
        Object l = k.newInstance();
        BlockState s = level.getBlockState(at);
        R.set(l, "pos", R.call(R.cls("net.axther.aftrhrs.feature.locks.LocksModule$Pos"), "of", level, at));
        R.set(l, "material", s.getBlock());
        R.set(l, "owner", UUID.randomUUID());
        R.set(l, "ownerName", "SafetyTest");
        R.set(l, "salt", "s");
        R.set(l, "hash", "h");
        R.set(l, "length", 4);
        R.set(l, "created", System.currentTimeMillis());
        return l;
    }
}
