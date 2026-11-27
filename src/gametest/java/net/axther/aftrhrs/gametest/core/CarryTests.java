package net.axther.aftrhrs.gametest.core;

import net.axther.aftrhrs.feature.travel.CarriedBlock;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Carrying blocks and mobs (docs/carrying.md): a chest comes up with its contents and goes down elsewhere with them
 * (no item lost or doubled), half a double chest comes up single, denied blocks stay put, full hands can't pick up
 * more, and a cow is held and set down while a zombie isn't picked up.
 */
public class CarryTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    static Bots.Bot bot(GameTestHelper h, String name) {
        Bots.Bot b = Bots.join(h, Bots.name(name));
        b.player.getInventory().clearContent(); // the starter kit fills the hotbar
        b.player.snapTo(h.absoluteVec(new Vec3(0.5, 1, 0.5)), 0, 0);
        return b;
    }

    /** Right-click a block face through the real game path (Fabric's UseBlockCallback included). */
    static void click(GameTestHelper h, ServerPlayer p, BlockPos rel, Direction face) {
        BlockPos abs = h.absolutePos(rel);
        p.gameMode.useItemOn(p, h.getLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs).relative(face, 0.5), face, abs, false));
    }

    static int diamonds(ChestBlockEntity c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.DIAMOND)) n += c.getItem(i).getCount();
        return n;
    }

    @GameTest(maxTicks = 60)
    public void carryChestKeepsContents(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST);
        h.getBlockEntity(new BlockPos(3, 1, 3), ChestBlockEntity.class).setItem(4, new ItemStack(Items.DIAMOND, 17));
        Bots.Bot a = bot(h, "cBlk");
        ServerPlayer p = a.player;
        p.setShiftKeyDown(true);
        click(h, p, new BlockPos(3, 1, 3), Direction.UP);
        check(h, h.getBlockState(new BlockPos(3, 1, 3)).isAir(), "the chest is still there: " + a.chat());
        check(h, p.hasAttached(CarriedBlock.CARRIED), "the player doesn't carry it");
        // With full hands a sneak-click on another container doesn't pick it up too: the chest goes down on top of it.
        h.setBlock(new BlockPos(5, 1, 5), Blocks.BARREL);
        click(h, p, new BlockPos(5, 1, 5), Direction.UP);
        check(h, h.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.BARREL), "the barrel came up with full hands");
        check(h, h.getBlockState(new BlockPos(5, 2, 5)).is(Blocks.CHEST), "the chest wasn't set down on the barrel: " + a.chat());
        check(h, !p.hasAttached(CarriedBlock.CARRIED), "still carrying after setting it down");
        check(h, diamonds(h.getBlockEntity(new BlockPos(5, 2, 5), ChestBlockEntity.class)) == 17, "the diamonds didn't come along");
        check(h, h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(10)).isEmpty(),
                "items were dropped on the way");
        a.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void carryHalfDoubleChestAndDenied(GameTestHelper h) {
        floor(h);
        // Facing north, a LEFT half joins the block to its east.
        h.setBlock(new BlockPos(2, 1, 3), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT));
        h.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT));
        h.setBlock(new BlockPos(5, 1, 5), Blocks.SPAWNER);
        Bots.Bot a = bot(h, "cDbl");
        ServerPlayer p = a.player;
        p.setShiftKeyDown(true);
        click(h, p, new BlockPos(5, 1, 5), Direction.UP);
        check(h, h.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.SPAWNER) && !p.hasAttached(CarriedBlock.CARRIED), "a spawner came up");
        click(h, p, new BlockPos(3, 1, 3), Direction.UP);
        CarriedBlock c = p.getAttached(CarriedBlock.CARRIED);
        check(h, c != null && c.state().getValue(ChestBlock.TYPE) == ChestType.SINGLE, "half a double chest isn't carried as a single chest: " + c);
        check(h, h.getBlockState(new BlockPos(2, 1, 3)).getValue(ChestBlock.TYPE) == ChestType.SINGLE, "the half left behind still thinks it's double");
        a.leave();
        h.succeed();
    }

    /** A tamed Arcana pet can be picked up by its owner (sneak + right-click), not by someone else. */
    @GameTest(maxTicks = 60)
    public void carryOwnPet(GameTestHelper h) {
        floor(h);
        var species = net.axther.aftrhrs.entity.ArcanaEntities.all().get(net.axther.aftrhrs.entity.ArcanaEntities.type("emberkit"));
        net.minecraft.world.entity.TamableAnimal pet = net.axther.aftrhrs.gametest.creatures.CT.spawn(h, species, new Vec3(3.5, 1, 3.5));
        Bots.Bot owner = bot(h, "cPet"), other = bot(h, "cOth");
        pet.tame(owner.player);
        other.player.setShiftKeyDown(true);
        UseEntityCallback.EVENT.invoker().interact(other.player, h.getLevel(), InteractionHand.MAIN_HAND, pet, null);
        check(h, pet.getVehicle() == null, "someone else picked up the pet");
        owner.player.setShiftKeyDown(true);
        UseEntityCallback.EVENT.invoker().interact(owner.player, h.getLevel(), InteractionHand.MAIN_HAND, pet, null);
        check(h, pet.getVehicle() == owner.player, "the owner couldn't pick up their pet: " + owner.chat());
        UseEntityCallback.EVENT.invoker().interact(owner.player, h.getLevel(), InteractionHand.MAIN_HAND, pet, null);
        check(h, pet.getVehicle() == null, "sneak + right-click didn't put the pet down");
        owner.leave();
        other.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 80)
    public void carryCowNotZombie(GameTestHelper h) {
        floor(h);
        Cow cow = net.minecraft.world.entity.EntityTypes.COW.create(h.getLevel(), EntitySpawnReason.COMMAND);
        cow.snapTo(h.absoluteVec(new Vec3(3.5, 1, 3.5)), 0, 0);
        h.getLevel().addFreshEntity(cow);
        Zombie zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(h.getLevel(), EntitySpawnReason.COMMAND);
        zombie.snapTo(h.absoluteVec(new Vec3(5.5, 1, 5.5)), 0, 0);
        zombie.setNoAi(true);
        h.getLevel().addFreshEntity(zombie);
        Bots.Bot a = bot(h, "cMob");
        ServerPlayer p = a.player;
        p.setShiftKeyDown(true);
        UseEntityCallback.EVENT.invoker().interact(p, h.getLevel(), InteractionHand.MAIN_HAND, zombie, null);
        check(h, zombie.getVehicle() == null, "a zombie was picked up");
        UseEntityCallback.EVENT.invoker().interact(p, h.getLevel(), InteractionHand.MAIN_HAND, cow, null);
        check(h, cow.getVehicle() == p, "the cow wasn't picked up: " + a.chat());
        h.runAfterDelay(3, () -> {
            // In the arms: in front of the carrier, off the ground.
            check(h, cow.getY() > p.getY() + 0.1 && cow.distanceTo(p) < 1.5, "the cow isn't held in front: " + cow.position() + " vs " + p.position());
            p.setShiftKeyDown(false);
            click(h, p, new BlockPos(1, 0, 5), Direction.UP);
            check(h, cow.getVehicle() == null, "the cow wasn't set down");
            check(h, cow.blockPosition().equals(h.absolutePos(new BlockPos(1, 1, 5))), "the cow landed at " + cow.blockPosition());
            check(h, cow.isAlive(), "the cow didn't survive being carried");
            a.leave();
            h.succeed();
        });
    }
}
