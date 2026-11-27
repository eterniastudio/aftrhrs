package net.axther.aftrhrs.feature.smithing.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * One block class for every smithing station: a horizontal facing (the front faces the player who placed it), the
 * station's shape turned to match, and every click handed to its {@link StationBE}. Both sides run the station's
 * {@code use} so the client swings (or lets the item act) exactly when the server will do something.
 */
public final class StationBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ON = BooleanProperty.create("on");
    public static final EnumProperty<Coal> COAL = EnumProperty.create("coal", Coal.class);

    /** What lies in the forge's coal bed. */
    public enum Coal implements StringRepresentable {
        EMPTY, COLD, LIT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public final Station station;
    private final VoxelShape[] shapes = new VoxelShape[4];

    public StationBlock(Station station, Properties p) {
        super(p);
        this.station = station;
        BlockState def = stateDefinition.any();
        if (def.hasProperty(FACING)) def = def.setValue(FACING, Direction.NORTH);
        if (def.hasProperty(ON)) def = def.setValue(ON, false);
        if (def.hasProperty(COAL)) def = def.setValue(COAL, Coal.EMPTY);
        registerDefaultState(def);
        for (Direction d : Direction.Plane.HORIZONTAL) shapes[d.get2DDataValue()] = rotate(station.boxes, d);
    }

    // the station is read from a thread-local while the state definition is built (super() runs before the field is set)
    private static final ThreadLocal<Station> BUILDING = new ThreadLocal<>();

    public static StationBlock create(Station station, Properties p) {
        BUILDING.set(station);
        try {
            return new StationBlock(station, p);
        } finally {
            BUILDING.remove();
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        Station s = BUILDING.get();
        if (s == null || s.facing) b.add(FACING);
        if (s == Station.FORGE) b.add(COAL);
        if (s == Station.BELLOWS || s == Station.RUNE_TABLE) b.add(ON);
    }

    /** Boxes in pixels for a north-facing station, turned to face {@code d} (as the blockstate turns the model). */
    static VoxelShape rotate(double[][] boxes, Direction d) {
        VoxelShape out = Shapes.empty();
        for (double[] b : boxes) {
            double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
            double[] r = switch (d) {
                case EAST -> new double[]{16 - z1, 16 - z0, x0, x1};
                case SOUTH -> new double[]{16 - x1, 16 - x0, 16 - z1, 16 - z0};
                case WEST -> new double[]{z0, z1, 16 - x1, 16 - x0};
                default -> new double[]{x0, x1, z0, z1};
            };
            out = Shapes.or(out, Block.box(r[0], b[1], r[2], r[1], b[4], r[3]));
        }
        return out.optimize();
    }

    /** Stations never hide the faces of blocks beside them (most have gaps: legs, mouths, open tops). */
    @Override
    protected VoxelShape getOcclusionShape(BlockState state) {
        return station == Station.FIRE_BRICKS ? super.getOcclusionShape(state) : Shapes.empty();
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapes[state.hasProperty(FACING) ? state.getValue(FACING).get2DDataValue() : 2];
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s = defaultBlockState();
        return s.hasProperty(FACING) ? s.setValue(FACING, ctx.getHorizontalDirection().getOpposite()) : s;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.hasProperty(FACING) ? state.setValue(FACING, rot.rotate(state.getValue(FACING))) : state;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.hasProperty(FACING) ? state.setValue(FACING, mirror.mirror(state.getValue(FACING))) : state;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hit) {
        if (station == Station.BELLOWS) return Bellows.use(state, level, pos, player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        if (level.getBlockEntity(pos) instanceof StationBE be) {
            InteractionResult r = be.use(player, hand, hit);
            if (r != InteractionResult.PASS) return r;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (station == Station.BELLOWS) return Bellows.use(state, level, pos, player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        if (level.getBlockEntity(pos) instanceof StationBE be) return be.use(player, InteractionHand.MAIN_HAND, hit);
        return InteractionResult.PASS;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (station == Station.BELLOWS && state.getValue(ON)) level.setBlock(pos, state.setValue(ON, false), 3);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof StationBE be) be.animate(random);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation o, boolean moving) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof StationBE be) be.neighborChanged(level.hasNeighborSignal(pos));
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return station == Station.FORGE || station == Station.CRUCIBLE || station == Station.GREAT_DRAIN;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction dir) {
        return level.getBlockEntity(pos) instanceof StationBE be ? be.comparator() : 0;
    }

    // ------------------------------------------------------------------ block entity

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return station.newBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!station.ticks) return null;
        return level.isClientSide()
                ? (l, p, s, be) -> {
                    if (be instanceof StationBE sb) sb.clientTick();
                }
                : (l, p, s, be) -> {
                    if (be instanceof StationBE sb) sb.serverTick((ServerLevel) l);
                };
    }
}
