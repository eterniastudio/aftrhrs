package net.axther.aftrhrs.client.mixin;

import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;

/** Which of vanilla's toast slots (32 px each, from the top right) are taken, so the quest pop-up can sit below them. */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {
	@Accessor("occupiedSlots")
	BitSet aftrhrs$occupiedSlots();
}
