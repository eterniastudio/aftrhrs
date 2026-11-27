package net.axther.aftrhrs.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** The gramophone (Oraxen {@code jukebox} furniture): plays music discs like a jukebox, shows its playing model. */
public class GramophoneBlockEntity extends BlockEntity {
    private ItemStack item = ItemStack.EMPTY;
    private final JukeboxSongPlayer player = new JukeboxSongPlayer(this::onSongChanged, getBlockPos());

    public GramophoneBlockEntity(BlockPos pos, BlockState state) {
        super(ArcanaContent.GRAMOPHONE_BE, pos, state);
    }

    private void onSongChanged() {
        if (level != null) level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        setChanged();
    }

    public ItemStack getTheItem() {
        return item;
    }

    public void setTheItem(ItemStack stack) {
        item = stack;
        boolean has = !item.isEmpty();
        if (level != null && level.getBlockState(getBlockPos()).hasProperty(FurnitureBlock.HAS_RECORD)) {
            level.setBlock(getBlockPos(), level.getBlockState(getBlockPos()).setValue(FurnitureBlock.HAS_RECORD, has), 3);
        }
        Optional<Holder<JukeboxSong>> song = JukeboxSong.fromStack(item);
        if (has && song.isPresent()) player.play(level, song.get());
        else player.stop(level, getBlockState());
        setChanged();
    }

    public void popOutTheItem() {
        if (level == null || level.isClientSide() || item.isEmpty()) return;
        ItemStack out = item.copy();
        setTheItem(ItemStack.EMPTY);
        Vec3 at = Vec3.atLowerCornerWithOffset(getBlockPos(), 0.5, 1.01, 0.5).offsetRandomXZ(level.getRandom(), 0.7f);
        ItemEntity e = new ItemEntity(level, at.x(), at.y(), at.z(), out);
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, GramophoneBlockEntity be) {
        // gramophones placed before has_record defaulted to false show the playing model with no disc: fix them up
        if (state.hasProperty(FurnitureBlock.HAS_RECORD) && state.getValue(FurnitureBlock.HAS_RECORD) == be.item.isEmpty()) {
            level.setBlock(pos, state.setValue(FurnitureBlock.HAS_RECORD, !be.item.isEmpty()), 3);
        }
        be.player.tick(level, state);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        item = input.read("RecordItem", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        input.getLong("ticks_since_song_started").ifPresent(t -> JukeboxSong.fromStack(item)
                .ifPresent(song -> player.setSongWithoutPlaying(song, t)));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!item.isEmpty()) output.store("RecordItem", ItemStack.CODEC, item);
        if (player.getSong() != null) output.putLong("ticks_since_song_started", player.getTicksSinceSongStarted());
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        popOutTheItem();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null) level.levelEvent(1011, getBlockPos(), 0);
    }
}
