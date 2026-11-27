package net.axther.aftrhrs.feature.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Crafting Terminal Remote (tier I: the Overworld only) and the Ender Crafting Terminal Remote (tier II: any
 * dimension). Sneak-right-click a Storage or Crafting Terminal to link it; right-click to open that network as a
 * Crafting Terminal from afar. The link is in the item's {@code custom_data} ({@code feather:remote}: id, dimension,
 * position, who linked it) and in {@link StorageRemotes} on the server, which keeps the network loaded.
 */
final class TerminalRemoteItem extends Item {
    static final String KEY = "feather:remote";

    /** 1: Overworld only; 2: across dimensions. */
    final int tier;

    TerminalRemoteItem(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    /** The link stored on a remote, or null. */
    record Link(String id, String dim, BlockPos pos, String by) {
    }

    static Link link(ItemStack stack) {
        CompoundTag t = net.axther.aftrhrs.core.Pdc.item(stack).getCompoundOrEmpty(KEY);
        String id = t.getStringOr("id", "");
        if (id.isEmpty()) return null;
        return new Link(id, t.getStringOr("dim", ""), new BlockPos(t.getIntOr("x", 0), t.getIntOr("y", 0), t.getIntOr("z", 0)), t.getStringOr("by", ""));
    }

    static int tierOf(ItemStack stack) {
        return stack.getItem() instanceof TerminalRemoteItem r ? r.tier : 0;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        Level level = ctx.getLevel();
        if (player == null || !player.isSecondaryUseActive()) return InteractionResult.PASS;
        if (!(level.getBlockState(ctx.getClickedPos()).getBlock() instanceof StorageBlock.Terminal)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
        StorageModule m = StorageModule.active();
        if (m == null) return InteractionResult.PASS;
        StorageBlock.Terminal t = (StorageBlock.Terminal) level.getBlockState(ctx.getClickedPos()).getBlock();
        m.remotes().link(p, ctx.getItemInHand(), new StorageBlock.Terminal.At(sl, ctx.getClickedPos().immutable(), t.kind));
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel) || !(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
        StorageModule m = StorageModule.active();
        if (m == null) return InteractionResult.PASS;
        m.remotes().use(p, player.getItemInHand(hand));
        return InteractionResult.SUCCESS_SERVER;
    }

    /** A remote in an inventory keeps its link known (links nobody has carried for a long time are forgotten). */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
        if (!(owner instanceof ServerPlayer) || level.getGameTime() % 1200 != 0) return;
        StorageModule m = StorageModule.active();
        Link l = link(stack);
        if (m != null && l != null) m.remotes().seen(l.id());
    }

    @Override
    public Component getName(ItemStack stack) {
        return tier == 2 ? StorageContent.line("item.aftrhrs.feather_terminal_remote_ender", "Ender Crafting Terminal Remote", 0xC3A6FF)
                : StorageContent.line("item.aftrhrs.feather_terminal_remote", "Crafting Terminal Remote", 0x8FD3FF);
    }
}
