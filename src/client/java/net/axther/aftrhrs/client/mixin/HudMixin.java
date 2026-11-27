package net.axther.aftrhrs.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Companion's HUD (claim tool panel, region banner, toasts) on every loader: over vanilla's chat, sidebar and
 * titles, but under the player list (Tab), the way vanilla layers its own pieces. Subtitles are not affected: in game
 * vanilla defers them to their own stratum after the whole HUD.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Hud;extractTabList(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
	private void aftrhrs$hud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		// The layout editor draws every piece itself (with samples), on top of its own screen.
		if (net.axther.aftrhrs.client.hud.HudEditorScreen.isOpen()) return;
		net.axther.aftrhrs.client.hud.HudEditorScreen.drawAll(graphics, false);
	}
}
