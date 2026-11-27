package net.axther.aftrhrs.mixin.chat;

import net.axther.aftrhrs.feature.display.TabListNames;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The tab-list name set by the tab module or nicknames (Paper's {@code playerListName}). */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerTabNameMixin {
    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$tabName(CallbackInfoReturnable<Component> cir) {
        Component c = TabListNames.get(((ServerPlayer) (Object) this).getUUID());
        if (c != null) cir.setReturnValue(c);
    }
}
