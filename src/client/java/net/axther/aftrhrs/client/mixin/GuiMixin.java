package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.storage.StorageSession;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A crafting table opened by a Crafting Terminal becomes the Companion's terminal screen. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen aftrhrs$terminal(Screen screen) {
		return StorageSession.wrap(screen);
	}
}
