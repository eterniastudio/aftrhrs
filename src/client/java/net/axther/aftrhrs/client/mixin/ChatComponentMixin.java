package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.Companion;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * JEI warns that a Paper server sends no recipes the moment it starts, a second before Feather sends them. The
 * warning is held back; it shows after all only if no recipes arrive (see {@link Companion#heldWarning}).
 */
@Mixin(ChatListener.class)
public abstract class ChatComponentMixin {
	@Inject(method = "handleSystemMessage", at = @At("HEAD"), cancellable = true)
	private void aftrhrs$holdJeiWarning(Component message, boolean remote, CallbackInfo ci) {
		if (message.getContents() instanceof TranslatableContents t
			&& t.getKey().equals("jei.message.server.recipe.sync.unavailable")
			&& Companion.holdWarning(message)) {
			ci.cancel();
		}
	}
}
