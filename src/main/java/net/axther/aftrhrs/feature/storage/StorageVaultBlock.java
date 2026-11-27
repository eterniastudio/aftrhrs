package net.axther.aftrhrs.feature.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Storage Vault ({@code aftrhrs:storage_vault}): vault blocks placed together join into one upright vault, up to
 * 3x3 across and as tall as the world allows ({@link VaultForm}). A vault holds any number of kinds of items, each up
 * to 20 stacks per block of it ({@link StorageVaultBE}). It has no window: hoppers, comparators and the storage
 * network reach it through any block, and a right-click tells what it holds.
 *
 * <p>The six direction properties say which neighbors belong to the same vault, for the connected textures; only
 * {@link VaultForm} sets them. {@link #AXIS} is left from when vaults lay sideways: it no longer means anything.
 */
public final class StorageVaultBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    static final Map<Direction, BooleanProperty> LINK = PipeBlock.PROPERTY_BY_DIRECTION;

    public StorageVaultBlock(Properties p) {
        super(p);
        BlockState s = stateDefinition.any().setValue(AXIS, Direction.Axis.X);
        for (BooleanProperty b : LINK.values()) s = s.setValue(b, false);
        registerDefaultState(s);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
        for (BooleanProperty b : LINK.values()) builder.add(b);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel sl && !old.is(this)) {
            VaultForm.reform(sl, List.of(pos));
            remotesNear(sl, pos);
        }
    }

    /** Kept-loaded networks near a vault block that came or went read themselves again on the next pass. */
    private static void remotesNear(ServerLevel level, BlockPos pos) {
        StorageModule m = StorageModule.active();
        if (m != null) m.remotes().blockChanged(level, pos);
    }

    /** A player placed it: it's theirs, and joins (only) what it may join now that that is known. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.@Nullable LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel sl && placer instanceof ServerPlayer p && level.getBlockEntity(pos) instanceof StorageVaultBE be) {
            be.setOwner(p.getUUID());
            // Its neighbors too: on placement (owner not yet known) it may have joined a vault it doesn't belong to.
            List<BlockPos> seeds = new ArrayList<>(7);
            seeds.add(pos);
            for (Direction d : Direction.values()) seeds.add(pos.relative(d));
            VaultForm.reform(sl, seeds);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        // The vault it was part of re-forms from what is left (each block keeps its own stacks).
        List<BlockPos> around = new ArrayList<>(6);
        for (Direction d : Direction.values()) if (state.getValue(LINK.get(d))) around.add(pos.relative(d));
        if (!around.isEmpty()) VaultForm.reform(level, around);
        remotesNear(level, pos);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorageVaultBE(pos, state);
    }

    // ------------------------------------------------------------------ comparators

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction dir) {
        return level.getBlockEntity(pos) instanceof StorageVaultBE be ? be.signal() : 0;
    }

    /** A vault's contents changed: next tick, comparators beside any of its blocks read it again (once per tick). */
    static void comparatorsSoon(Level level, BlockPos controller) {
        if (level.isClientSide() || !(level.getBlockState(controller).getBlock() instanceof StorageVaultBlock b)) return;
        if (!level.getBlockTicks().hasScheduledTick(controller, b)) level.scheduleTick(controller, b, 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof StorageVaultBE be)) return;
        be.settle();
        for (StorageVaultBE m : be.group()) level.updateNeighbourForOutputSignal(m.getBlockPos(), this);
    }

    // ------------------------------------------------------------------ a look inside

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof StorageVaultBE be) {
            String size = be.sizeX() + "×" + be.sizeZ() + "×" + be.height();
            sp.sendOverlayMessage(Component.translatableWithFallback("aftrhrs.storage.vault.info",
                    "Storage Vault · %s · %s kinds of items · up to %s stacks of each",
                    size, String.format(Locale.ROOT, "%,d", be.kinds()),
                    String.format(Locale.ROOT, "%,d", (long) be.blocks() * StorageVaultBE.STACKS_PER_BLOCK)));
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ structures

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        BlockState out = state;
        if (rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90) {
            out = out.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) out = out.setValue(LINK.get(rot.rotate(d)), state.getValue(LINK.get(d)));
        return out;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState out = state;
        for (Direction d : Direction.Plane.HORIZONTAL) out = out.setValue(LINK.get(mirror.mirror(d)), state.getValue(LINK.get(d)));
        return out;
    }
}
