package net.axther.aftrhrs.feature.shop;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Places a counter keeper ({@link CounterKeeper}) on the clicked block's face, facing the player. */
public class KeeperItem extends Item {
    final KeeperContent.Trade trade;

    public KeeperItem(KeeperContent.Trade trade, Properties properties) {
        super(properties);
        this.trade = trade;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer p)) return InteractionResult.PASS;
        ShopModule shop = ShopModule.active;
        if (shop == null) return InteractionResult.FAIL;
        return shop.placeKeeper(p, ctx, trade) ? InteractionResult.SUCCESS_SERVER : InteractionResult.FAIL;
    }
}
