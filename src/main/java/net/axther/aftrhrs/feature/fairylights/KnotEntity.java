/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/entity/FenceFastenerEntity + server/fastener/FenceFastener.)
 */
package net.axther.aftrhrs.feature.fairylights;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A knot of cord tied round a fence or wall post ({@code aftrhrs:fairy_knot}), the fastener for strands strung to
 * posts. Its strands live in a synced, saved attachment. Punching it takes everything down; it unties itself when
 * the post goes or the last strand is gone.
 */
public final class KnotEntity extends BlockAttachedEntity {
    /** Height of the knot on the post. */
    public static final double HEIGHT = 0.625;
    public static final AttachmentType<CompoundTag> DATA = AttachmentRegistry.create(
            net.axther.aftrhrs.AftrHrs.id("fairy_knot"),
            b -> b.persistent(CompoundTag.CODEC).syncWith(ByteBufCodecs.TRUSTED_COMPOUND_TAG, AttachmentSyncPredicate.all()));

    private final KnotFastener fastener = new KnotFastener();
    private @Nullable CompoundTag loaded;
    private @Nullable AABB cullBox;
    private int cullAge = -1;
    // Client only, never saved or synced: the knot renderer's cache (cord colour, bow, wall post), worked out once a client
    // tick (rStamp: FairyLightsClient.renderTick) or when the attachment changes (rFor: the tag it was worked out for).
    public int rCord;
    public boolean rBow, rWall;
    public int rStamp = Integer.MIN_VALUE;
    public @Nullable Object rFor;

    public KnotEntity(EntityType<? extends KnotEntity> type, Level level) {
        super(type, level);
    }

    public KnotEntity(Level level, BlockPos pos) {
        super(FairyLights.KNOT, level, pos);
        setPos(pos.getX(), pos.getY(), pos.getZ());
    }

    public Fastener fastener() {
        CompoundTag t = getAttached(DATA);
        if (t != null && t != loaded) {
            loaded = t;
            fastener.load(t);
        }
        return fastener;
    }

    public static boolean isPost(BlockState s) {
        return s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS);
    }

    public static @Nullable KnotEntity find(Level level, BlockPos pos) {
        for (KnotEntity k : level.getEntitiesOfClass(KnotEntity.class, new AABB(pos).inflate(0.5))) {
            if (k.getPos().equals(pos) && !k.isRemoved()) return k;
        }
        return null;
    }

    public static KnotEntity findOrCreate(ServerLevel level, BlockPos pos) {
        KnotEntity k = find(level, pos);
        if (k != null) return k;
        k = new KnotEntity(level, pos);
        level.addFreshEntity(k);
        k.playSound(SoundEvents.LEAD_TIED, 0.7f, 1.1f);
        return k;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
    }

    @Override
    protected void recalculateBoundingBox() {
        setPosRaw(pos.getX() + 0.5, pos.getY() + HEIGHT, pos.getZ() + 0.5);
        double w = 3 / 16.0;
        setBoundingBox(new AABB(getX() - w, getY() - w, getZ() - w, getX() + w, getY() + w, getZ() + w));
    }

    @Override
    public boolean survives() {
        return !level().isLoaded(pos) || isPost(level().getBlockState(pos));
    }

    @Override
    public void tick() {
        super.tick();
        if (isRemoved()) return;
        Fastener f = fastener();
        if (level() instanceof ServerLevel sl) {
            // the post went away: come down at once (vanilla only checks every 100 ticks)
            if (tickCount % 5 == 0 && level().isLoaded(pos) && !isPost(level().getBlockState(pos))) {
                discard();
                dropItem(sl, null);
                return;
            }
            f.serverTick(sl);
            if (f.empty() && tickCount > 2 && !isRemoved()) discard();
        } else {
            Fastener.CLIENT_LOADED.add(f);
        }
    }

    @Override
    public void dropItem(ServerLevel level, @Nullable Entity causedBy) {
        boolean creative = causedBy instanceof Player p && p.hasInfiniteMaterials();
        fastener().removeAll(level, position(), !creative);
        playSound(SoundEvents.LEAD_UNTIED, 0.8f, 1.1f);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        ItemStack held = player.getItemInHand(hand);
        if (!StrandItem.isStrand(held)) return super.interact(player, hand, location);
        if (level() instanceof ServerLevel sl) StrandItem.useOnFastener(sl, player, hand, held, fastener());
        return InteractionResult.SUCCESS;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(FairyLights.HOOK_ITEM);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96 * 96;
    }

    /**
     * Client: bounds of the knot and its strands, for culling (worked out once a tick; every frame while an end is a
     * player, as those move between ticks and the knot's tick count stalls under /tick freeze).
     */
    public AABB renderBounds() {
        if (cullBox == null || cullAge != tickCount) {
            Fastener f = fastener();
            cullBox = f.renderBounds();
            boolean moving = false;
            for (Strand s : f.outgoing()) {
                if (s.dest.isPlayer()) {
                    moving = true;
                    break;
                }
            }
            cullAge = moving ? -1 : tickCount;
        }
        return cullBox;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.BLOCKS;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, 0, getPos());
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        // a change made this tick (before the knot ticked) still goes into the saved attachment
        if (!level().isClientSide()) fastener.flush();
        super.addAdditionalSaveData(output);
    }

    @Override
    public void onClientRemoval() {
        super.onClientRemoval();
        Fastener.CLIENT_LOADED.remove(fastener);
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        super.onRemoval(reason);
        Fastener.CLIENT_LOADED.remove(fastener);
    }

    final class KnotFastener extends Fastener {
        @Override
        public Level level() {
            return KnotEntity.this.level();
        }

        @Override
        public FastenerRef ref() {
            return FastenerRef.knot(KnotEntity.this.pos);
        }

        @Override
        public Vec3 point() {
            return new Vec3(pos.getX() + 0.5, pos.getY() + HEIGHT, pos.getZ() + 0.5);
        }

        @Override
        public BlockPos pos() {
            return KnotEntity.this.pos;
        }

        /** Gone once the knot entity is (discarded, unloaded, changed dimension). */
        @Override
        public boolean valid() {
            return !KnotEntity.this.isRemoved();
        }

        /** Knots keep their data in an attachment: write it at once (a knot outside entity-ticking range never ticks). */
        @Override
        public void markDirty() {
            super.markDirty();
            if (!level().isClientSide()) flush();
        }

        @Override
        protected void sync() {
            CompoundTag t = save();
            loaded = t;
            setAttached(DATA, t);
        }
    }
}
