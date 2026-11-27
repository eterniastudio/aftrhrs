package net.axther.aftrhrs.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic ex-Oraxen furniture: one block per piece, driven by a {@link FurnitureSpec}. Multi-block pieces place
 * invisible-to-the-player "parts" (same block, {@code part} > 0) that carry their share of the shape and the light
 * and break the whole piece. Facing: model north faces the placer (4-way {@code facing}, or 8/16-way
 * {@code rotation} for free-standing pieces Oraxen let turn in 45/22.5 degree steps).
 *
 * <p>Right-click order: module hooks, sneak + empty hand rotates (single-block pieces), storage opens, the
 * gramophone takes/ejects a disc, seats sit, toggles flip ({@code lit} / {@code open}).
 */
public class FurnitureBlock extends Block implements SimpleWaterloggedBlock, BonemealableBlock {
    static final ThreadLocal<FurnitureSpec> BUILDING = new ThreadLocal<>();

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Direction> FACING_ALL = BlockStateProperties.FACING;
    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty HAS_RECORD = BlockStateProperties.HAS_RECORD;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final Map<String, IntegerProperty> INTS = new HashMap<>();

    public final FurnitureSpec spec;
    public final @Nullable IntegerProperty part;
    public final @Nullable IntegerProperty age;
    public final @Nullable BooleanProperty toggle;
    /**
     * Vanilla's per-state shape caches (BlockStateBase.Cache), shared by every state with the same {@link #stateCacheKey}
     * (FurnitureStateCacheMixin). The Cache holds only collision-shape data (collision shape, large-shape flag, face
     * sturdiness from the support shape, full-block flag), which here is {@code spec.collision[part][quadrant]}: light,
     * fluid (waterlogged), lit/open/age and the rest are kept per state outside it. About 22k furniture states share
     * about 6k caches (one per part and quadrant of each block), which saves startup time.
     */
    public final Object[] sharedStateCaches;

    static synchronized IntegerProperty intProp(String name, int max) {
        return INTS.computeIfAbsent(name + max, k -> IntegerProperty.create(name, 0, max));
    }

    static FurnitureBlock create(FurnitureSpec spec, Properties props) {
        BUILDING.set(spec);
        try {
            return spec.hasBlockEntity() || ArcanaContent.moduleEntity(spec.id) != null
                    ? new FurnitureEntityBlock(spec, props) : new FurnitureBlock(spec, props);
        } finally {
            BUILDING.remove();
        }
    }

    protected FurnitureBlock(FurnitureSpec spec, Properties props) {
        super(props);
        this.spec = spec;
        this.part = spec.multiPart() ? intProp("part", spec.parts.length - 1) : null;
        this.age = spec.stages != null && spec.stages.count() > 1 ? intProp("age", spec.stages.count() - 1) : null;
        this.toggle = spec.toggle == null ? null : "open".equals(spec.toggle.property()) ? OPEN : LIT;
        this.sharedStateCaches = new Object[spec.parts.length * 4];
        BlockState d = stateDefinition.any().setValue(WATERLOGGED, false);
        if (toggle != null) d = d.setValue(toggle, spec.toggle.defaultValue());
        if (spec.storage != null && toggle != OPEN) d = d.setValue(OPEN, false);
        if (age != null) d = d.setValue(age, Math.min(spec.stages.initial(), spec.stages.count() - 1));
        // any() starts boolean properties at true: a new gramophone showed "playing" and refused every disc
        if (spec.jukebox) d = d.setValue(HAS_RECORD, false);
        if (spec.powered) d = d.setValue(POWERED, false);
        registerDefaultState(d);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        FurnitureSpec s = BUILDING.get();
        Set<Property<?>> props = new LinkedHashSet<>();
        props.add(switch (s.facing) {
            case H4 -> FACING;
            case ALL6 -> FACING_ALL;
            case ROT16 -> ROTATION;
        });
        if (s.multiPart()) props.add(intProp("part", s.parts.length - 1));
        if (s.toggle != null) props.add("open".equals(s.toggle.property()) ? OPEN : LIT);
        if (s.storage != null) props.add(OPEN);
        if (s.stages != null && s.stages.count() > 1) props.add(intProp("age", s.stages.count() - 1));
        if (s.jukebox) props.add(HAS_RECORD);
        if (s.powered) props.add(POWERED);
        props.add(WATERLOGGED);
        builder.add(props.toArray(new Property<?>[0]));
    }

    // ------------------------------------------------------------------------------------------------ geometry
    /** Clockwise quarter turns (0 = facing north) used for shapes and part offsets. */
    public static int quadrant(BlockState s) {
        if (s.hasProperty(FACING)) return quadrantOf(s.getValue(FACING));
        if (s.hasProperty(ROTATION)) return ((s.getValue(ROTATION) + 2) / 4) & 3;
        if (s.hasProperty(FACING_ALL)) {
            Direction d = s.getValue(FACING_ALL);
            return d.getAxis().isHorizontal() ? quadrantOf(d) : 0;
        }
        return 0;
    }

    private static int quadrantOf(Direction d) {
        return switch (d) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    /** The piece's front (the side that faced the placer). */
    public static Direction front(BlockState s) {
        if (s.hasProperty(FACING_ALL)) return s.getValue(FACING_ALL);
        return switch (quadrant(s)) {
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /** Yaw in degrees clockwise from north. */
    public static float yawDegrees(BlockState s) {
        if (s.hasProperty(ROTATION)) return s.getValue(ROTATION) * 22.5f;
        return quadrant(s) * 90f;
    }

    /** A model cell turned clockwise q quarter turns (x east, z south). */
    public static BlockPos turn(BlockPos c, int q) {
        int x = c.getX(), z = c.getZ();
        for (int i = 0; i < (q & 3); i++) {
            int nx = -z;
            z = x;
            x = nx;
        }
        return new BlockPos(x, c.getY(), z);
    }

    public int partIndex(BlockState s) {
        return part != null && s.hasProperty(part) ? s.getValue(part) : 0;
    }

    /** Index into {@link #sharedStateCaches}: states with the same part and quadrant have the same collision shape. */
    public int stateCacheKey(BlockState s) {
        return partIndex(s) * 4 + quadrant(s);
    }

    /** The origin (part 0) of the piece this state at pos belongs to. */
    public BlockPos origin(BlockState s, BlockPos pos) {
        int p = partIndex(s);
        if (p == 0) return pos;
        return pos.subtract(turn(spec.parts[p], quadrant(s)));
    }

    /** World positions of all parts, index = part number. */
    public List<BlockPos> partPositions(BlockState originState, BlockPos origin) {
        List<BlockPos> out = new ArrayList<>(spec.parts.length);
        int q = quadrant(originState);
        for (BlockPos c : spec.parts) out.add(origin.offset(turn(c, q)));
        return out;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return spec.outline[partIndex(state)][quadrant(state)];
    }

    // The collision shape feeds vanilla's per-state Cache, which is shared by part and quadrant (sharedStateCaches). Any
    // future override of getCollisionShape, getBlockSupportShape or an offset function that reads other properties
    // must drop the shared cache or put those properties into stateCacheKey.
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return spec.collision[partIndex(state)][quadrant(state)];
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    /** Light of a state; static on the spec because the block computes light while it is being constructed. */
    static int lightOf(FurnitureSpec spec, BlockState s) {
        if (spec.toggle != null && "lit".equals(spec.toggle.property()) && s.hasProperty(LIT) && !s.getValue(LIT)) return 0;
        int p = spec.multiPart() ? s.getValue(intProp("part", spec.parts.length - 1)) : 0;
        if (spec.stages != null && spec.stages.light() != null && spec.stages.count() > 1) {
            int a = s.getValue(intProp("age", spec.stages.count() - 1));
            int[] sl = spec.stages.light();
            return sl.length == 0 ? 0 : sl[Math.min(a, sl.length - 1)];
        }
        return spec.light[p];
    }

    // ------------------------------------------------------------------------------------------------ placement
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        boolean floor = ctx.replacingClickedOnBlock() || face == Direction.UP;
        boolean roof = !floor && face == Direction.DOWN;
        boolean wall = !floor && !roof;
        if ((floor && !spec.floor) || (roof && !spec.roof) || (wall && !spec.wall)) return null;
        BlockState s = defaultBlockState();
        switch (spec.facing) {
            case H4 -> s = s.setValue(FACING, wall ? face : ctx.getHorizontalDirection().getOpposite());
            case ALL6 -> s = s.setValue(FACING_ALL, ctx.getNearestLookingDirection().getOpposite());
            case ROT16 -> {
                int r;
                if (wall) {
                    r = quadrantOf(face) * 4;
                } else {
                    r = Math.floorMod(Math.round(ctx.getRotation() / 22.5f), 16);
                    int step = Math.max(1, spec.rotStep);
                    r = Math.floorMod(Math.round(r / (float) step) * step, 16);
                }
                s = s.setValue(ROTATION, r);
            }
        }
        Level level = ctx.getLevel();
        BlockPos origin = ctx.getClickedPos();
        if (spec.multiPart()) {
            List<BlockPos> ps = partPositions(s, origin);
            for (int i = 1; i < ps.size(); i++) {
                BlockPos p = ps.get(i);
                if (!level.isInsideBuildHeight(p.getY()) || !level.getBlockState(p).canBeReplaced()) return null;
            }
        }
        return s.setValue(WATERLOGGED, level.getFluidState(origin).is(Fluids.WATER));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level.isClientSide()) return;
        if (spec.multiPart() && partIndex(state) == 0) {
            List<BlockPos> ps = partPositions(state, pos);
            for (int i = 1; i < ps.size(); i++) {
                BlockPos p = ps.get(i);
                level.setBlock(p, state.setValue(part, i).setValue(WATERLOGGED, level.getFluidState(p).is(Fluids.WATER)), 3);
            }
        }
        if (level instanceof ServerLevel sl) {
            ArcanaContent.firePlaced(new FurnitureContext(ArcanaContent.idOf(this), sl, pos, state, pos,
                    by instanceof ServerPlayer sp ? sp : null, InteractionHand.MAIN_HAND, stack, null));
        }
    }

    /** Sets a property on every part of the piece (lights live on all parts). */
    public void setOnAll(Level level, BlockPos origin, BlockState originState, Property<Boolean> prop, boolean value) {
        List<BlockPos> ps = partPositions(originState, origin);
        for (int i = 0; i < ps.size(); i++) {
            BlockState cur = level.getBlockState(ps.get(i));
            if (cur.is(this) && partIndex(cur) == i && cur.hasProperty(prop)) level.setBlock(ps.get(i), cur.setValue(prop, value), 3);
        }
    }

    // ------------------------------------------------------------------------------------------------ breaking
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && spec.multiPart() && partIndex(state) != 0) {
            BlockPos origin = origin(state, pos);
            BlockState os = level.getBlockState(origin);
            if (os.is(this) && partIndex(os) == 0) {
                if (!player.preventsBlockDrops()) {
                    dropResources(os, level, origin, level.getBlockEntity(origin), player, player.getMainHandItem());
                }
                level.setBlock(origin, fluidOrAir(os), 35);
                level.levelEvent(player, 2001, origin, getId(os));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    static BlockState fluidOrAir(BlockState s) {
        return s.hasProperty(WATERLOGGED) && s.getValue(WATERLOGGED)
                ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        BlockPos origin = origin(state, pos);
        if (spec.multiPart()) {
            BlockState os = state.setValue(part, 0);
            List<BlockPos> ps = partPositions(os, origin);
            for (int i = 0; i < ps.size(); i++) {
                BlockPos p = ps.get(i);
                if (p.equals(pos)) continue;
                BlockState cur = level.getBlockState(p);
                if (cur.is(this) && partIndex(cur) == i && quadrant(cur) == quadrant(state)) {
                    level.setBlock(p, fluidOrAir(cur), 35);
                }
            }
        }
        if (partIndex(state) == 0) {
            Seats.release(level, pos);
            ArcanaContent.fireRemoved(new FurnitureContext(ArcanaContent.idOf(this), level, pos, state, pos, null,
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY, null));
        }
    }

    // ------------------------------------------------------------------------------------------------ water
    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction dir, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (state.getValue(WATERLOGGED)) ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return super.updateShape(state, level, ticks, pos, dir, neighbourPos, neighbourState, random);
    }

    // ------------------------------------------------------------------------------------------------ rotation
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (state.hasProperty(FACING)) return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        if (state.hasProperty(FACING_ALL)) return state.setValue(FACING_ALL, rotation.rotate(state.getValue(FACING_ALL)));
        if (state.hasProperty(ROTATION)) return state.setValue(ROTATION, rotation.rotate(state.getValue(ROTATION), 16));
        return state;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (state.hasProperty(FACING)) return state.rotate(mirror.getRotation(state.getValue(FACING)));
        if (state.hasProperty(ROTATION)) return state.setValue(ROTATION, mirror.mirror(state.getValue(ROTATION), 16));
        return state;
    }

    /** The next orientation for sneak + empty-hand rotation; null if the piece can't turn. */
    @Nullable BlockState turned(BlockState s) {
        if (spec.multiPart()) return null;
        if (s.hasProperty(FACING)) return s.setValue(FACING, s.getValue(FACING).getClockWise());
        if (s.hasProperty(ROTATION)) return s.setValue(ROTATION, (s.getValue(ROTATION) + Math.max(1, spec.rotStep)) & 15);
        if (s.hasProperty(FACING_ALL)) {
            Direction d = s.getValue(FACING_ALL);
            Direction next = switch (d) {
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case SOUTH -> Direction.WEST;
                case WEST -> Direction.UP;
                case UP -> Direction.DOWN;
                case DOWN -> Direction.NORTH;
            };
            return s.setValue(FACING_ALL, next);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ use
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        BlockPos origin = origin(state, pos);
        BlockState os = level.getBlockState(origin);
        if (!os.is(this)) os = state;
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            InteractionResult r = ArcanaContent.fireUse(new FurnitureContext(ArcanaContent.idOf(this), sl, origin, os,
                    pos, sp, hand, stack, hit));
            if (r != InteractionResult.PASS) return r;
        }
        // hearths: flint and steel / fire charge light them, a shovel douses them
        if (spec.hearthLitItem != null && toggle == LIT) {
            boolean lit = os.getValue(LIT);
            if (!lit && (stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE))) {
                if (!level.isClientSide()) {
                    setOnAll(level, origin, os, LIT, true);
                    boolean charge = stack.is(Items.FIRE_CHARGE);
                    level.playSound(null, pos, charge ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE,
                            SoundSource.BLOCKS, 1f, level.getRandom().nextFloat() * 0.4f + 0.8f);
                    if (charge) stack.consume(1, player);
                    else stack.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
                }
                return InteractionResult.SUCCESS;
            }
            if (lit && stack.is(ItemTags.SHOVELS)) {
                if (!level.isClientSide()) {
                    setOnAll(level, origin, os, LIT, false);
                    level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1f);
                    stack.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
                }
                return InteractionResult.SUCCESS;
            }
            if (lit && stack.is(Items.WATER_BUCKET)) {
                if (!level.isClientSide()) {
                    setOnAll(level, origin, os, LIT, false);
                    level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1f);
                    if (!player.getAbilities().instabuild) player.setItemInHand(hand, new ItemStack(Items.BUCKET));
                }
                return InteractionResult.SUCCESS;
            }
        }
        if (spec.jukebox && !stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.JUKEBOX_PLAYABLE)
                && !os.getValue(HAS_RECORD)) {
            if (!level.isClientSide() && level.getBlockEntity(origin) instanceof GramophoneBlockEntity g) {
                g.setTheItem(stack.consumeAndReturn(1, player));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos origin = origin(state, pos);
        BlockState os = level.getBlockState(origin);
        if (!os.is(this)) return InteractionResult.PASS;
        if (player.isSecondaryUseActive()) {
            if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
            BlockState t = turned(os);
            if (t == null) return InteractionResult.PASS;
            if (!level.isClientSide()) {
                level.setBlock(origin, t, 3);
                level.playSound(null, origin, getSoundType(os).getHitSound(), SoundSource.BLOCKS, 0.8f, 1.2f);
            }
            return InteractionResult.SUCCESS;
        }
        if (spec.storage != null) {
            if (player instanceof ServerPlayer sp) FurnitureStorage.open(sp, this, origin, os);
            return InteractionResult.SUCCESS;
        }
        if (spec.jukebox) {
            if (os.getValue(HAS_RECORD)) {
                if (!level.isClientSide() && level.getBlockEntity(origin) instanceof GramophoneBlockEntity g) g.popOutTheItem();
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
        if (!spec.seats.isEmpty()) {
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
                return Seats.sit(sp, sl, origin, os, this, hit.getLocation()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            return InteractionResult.SUCCESS;
        }
        if (toggle != null && spec.toggle.player()) {
            if (!level.isClientSide()) {
                boolean v = !os.getValue(toggle);
                setOnAll(level, origin, os, toggle, v);
                SoundEvent snd = toggleSound(v);
                if (snd != null) level.playSound(null, pos, snd, SoundSource.BLOCKS, 0.8f, 1f);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private @Nullable SoundEvent toggleSound(boolean on) {
        String s = spec.toggle.sound();
        if (s == null) s = toggle == OPEN ? "wood" : "lamp";
        return switch (s) {
            case "fire" -> on ? SoundEvents.FLINTANDSTEEL_USE : SoundEvents.CANDLE_EXTINGUISH;
            case "wood" -> on ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE;
            case "cloth" -> SoundEvents.WOOL_PLACE;
            default -> on ? SoundEvents.COPPER_BULB_TURN_ON : SoundEvents.COPPER_BULB_TURN_OFF;
        };
    }

    // ------------------------------------------------------------------------------------------------ growth
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return age != null && state.getValue(age) < spec.stages.count() - 1;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (age == null) return;
        int a = state.getValue(age);
        if (a >= spec.stages.count() - 1) return;
        float chance = level.getMaxLocalRawBrightness(pos) >= spec.stages.lightMin() ? spec.stages.boostChance() : spec.stages.chance();
        if (random.nextFloat() < chance) level.setBlock(pos, state.setValue(age, a + 1), 2);
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state, net.minecraft.world.level.block.BonemealSource source) {
        return age != null && spec.stages.bonemeal() > 0 && state.getValue(age) < spec.stages.count() - 1;
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state, net.minecraft.world.level.block.BonemealSource source) {
        return random.nextFloat() < spec.stages.bonemeal();
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state, net.minecraft.world.level.block.BonemealSource source) {
        if (age != null) level.setBlock(pos, state.setValue(age, Math.min(spec.stages.count() - 1, state.getValue(age) + 1)), 2);
    }

    // ------------------------------------------------------------------------------------------------ misc
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return spec.storage != null && spec.storage.type().ownInventory();
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        BlockEntity be = level.getBlockEntity(origin(state, pos));
        return net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromBlockEntity(be);
    }
}
