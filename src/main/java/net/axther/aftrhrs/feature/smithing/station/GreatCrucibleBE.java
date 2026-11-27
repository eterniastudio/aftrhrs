package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Great Crucible (SMITHING.md 3.2): a fire-brick pot built around one hollow column. The drain sits in its wall
 * with the tap facing out; behind it is the hollow, two blocks deep, on a 3x3 fire-brick floor, ringed by fire bricks
 * on both layers. Lava (or a lit hearth) under the floor's center heats it. It holds 36 ingots, melts tier-4 metal and
 * makes the great alloys. Pieces dropped into the hollow sink in.
 */
public final class GreatCrucibleBE extends MeltBE {
    public static final int CAPACITY = 36 * 90;
    private boolean formed;
    private int check;

    public GreatCrucibleBE(BlockPos pos, BlockState state) {
        super(Station.GREAT_DRAIN, pos, state);
    }

    public boolean formed() {
        return formed;
    }

    /** The bottom of the hollow column. */
    public BlockPos hollow() {
        return worldPosition.relative(facing().getOpposite());
    }

    @Override
    public int capacity() {
        return formed ? CAPACITY : 0;
    }

    @Override
    protected boolean great() {
        return true;
    }

    @Override
    public float heat() {
        if (level == null || !formed) return Heat.AIR;
        BlockPos under = hollow().below(2);
        if (level.getBlockEntity(under) instanceof ForgeBE forge) return forge.heat();
        BlockState s = level.getBlockState(under);
        if (s.is(Blocks.LAVA) || s.is(Blocks.LAVA_CAULDRON)) return 1650;
        return Heat.AIR;
    }

    @Override
    protected Vec3 mouth() {
        return Vec3.atLowerCornerOf(hollow()).add(0.5, 1.6, 0.5);
    }

    private boolean brick(BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.is(Smithing.FIRE_BRICKS) || s.is(Smithing.block(Station.GREAT_DRAIN));
    }

    /** Checks the structure. */
    public boolean validate() {
        if (level == null) return false;
        BlockPos h = hollow();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!brick(h.offset(dx, -1, dz))) return false;
                if (dx == 0 && dz == 0) continue;
                if (!brick(h.offset(dx, 0, dz)) || !brick(h.offset(dx, 1, dz))) return false;
            }
        }
        return level.getBlockState(h).isAir() && level.getBlockState(h.above()).isAir();
    }

    @Override
    public void serverTick(ServerLevel level) {
        if (check-- <= 0) {
            check = 40;
            boolean was = formed;
            formed = validate();
            if (was != formed) sync();
        }
        if (!formed) return;
        super.serverTick(level);
        // pieces dropped into the hollow sink into the melt
        if (level.getGameTime() % 10 == 0) {
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(hollow()).expandTowards(0, 1, 0))) {
                var stack = ie.getItem();
                while (!stack.isEmpty() && accepts(stack.copyWithCount(1))) {
                    addSolid(stack.split(1));
                    sync();
                }
                if (stack.isEmpty()) ie.discard();
            }
        }
    }

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        if (!formed) {
            if (!client()) tell(p, "aftrhrs.smithing.great.unformed");
            return p.getItemInHand(hand).isEmpty() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        return super.use(p, hand, hit);
    }

    public Direction tap() {
        return facing();
    }
}
