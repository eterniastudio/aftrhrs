package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.menu.MenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A server "clear dialog" also closes a Feather menu drawn as the mod's {@link MenuScreen} (the menu that dialog would
 * have been): code that closes a menu by hand, such as the bank robbery's lock game, works the same either way.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClearDialogMixin {
	@Shadow
	@Final
	protected Minecraft minecraft;

	@Inject(method = "clearDialog", at = @At("HEAD"))
	private void aftrhrs$closeMenuScreen(CallbackInfo ci) {
		if (minecraft.gui.screen() instanceof MenuScreen) minecraft.gui.setScreen(null);
	}
}
