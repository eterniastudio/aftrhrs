package net.axther.aftrhrs.client.mixin.jei;

import mezz.jei.api.ingredients.ISlotDisplayInterpretationBuilder;
import mezz.jei.api.ingredients.ISlotDisplayInterpreter;
import net.axther.aftrhrs.client.compat.jei.JeiCompat;
import net.minecraft.core.Holder;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With server items as subtypes (see {@link JeiCompat#registerItemSubtypes}), JEI's own slot interpreters would show
 * a plain "paper", "#planks" or "any fuel" slot as every paper there is, custom-model ones included. A plain slot means
 * the plain item, so an item slot stops being a wildcard only for items Aftr Hrs gives subtypes
 * ({@link JeiCompat#ownsSubtypes}): items JEI itself gives subtypes (potions, books, …) and other mods' subtype items
 * keep JEI's wildcard, and so does a slot whose item isn't a registry reference. The tag and fuel slots can't tell
 * items apart, so they are never wildcards (which also covers other mods' subtype items inside a tag). JEI refuses a
 * second interpreter for these slot types and runs its own last, hence a mixin. Optional: without JEI, or if a JEI
 * update renames these lambdas, nothing is applied and JEI behaves as before.
 */
@Pseudo
@Mixin(targets = "mezz.jei.library.plugins.vanilla.VanillaPlugin", remap = false)
public abstract class JeiVanillaPluginMixin {
	private static final String SET_WILDCARD = "Lmezz/jei/api/ingredients/ISlotDisplayInterpretationBuilder;setWildcardForSubtypes(Z)Lmezz/jei/api/ingredients/ISlotDisplayInterpretationBuilder;";

	/** The item slot being interpreted (render thread; set at the head of JEI's lambda, read just below). */
	private static final ThreadLocal<SlotDisplay.ItemSlotDisplay> aftrhrs$slot = new ThreadLocal<>();

	@Inject(method = "lambda$registerSlotDisplayInterpreters$1", at = @At("HEAD"), require = 0, remap = false)
	private static void aftrhrs$rememberSlot(SlotDisplay.ItemSlotDisplay display, ISlotDisplayInterpreter.IContext<?> ctx, ISlotDisplayInterpretationBuilder<?> builder, CallbackInfo ci) {
		aftrhrs$slot.set(display);
	}

	@ModifyArg(method = "lambda$registerSlotDisplayInterpreters$1", at = @At(value = "INVOKE", target = SET_WILDCARD), require = 0, remap = false)
	private static boolean aftrhrs$itemSlot(boolean wildcard) {
		SlotDisplay.ItemSlotDisplay display = aftrhrs$slot.get();
		aftrhrs$slot.remove();
		return wildcard && !(display != null && display.item().kind() == Holder.Kind.REFERENCE && JeiCompat.ownsSubtypes(display.item().value()));
	}

	@ModifyArg(method = "lambda$registerSlotDisplayInterpreters$2", at = @At(value = "INVOKE", target = SET_WILDCARD), require = 0, remap = false)
	private static boolean aftrhrs$tagSlot(boolean wildcard) {
		return false;
	}

	@ModifyArg(method = "lambda$registerSlotDisplayInterpreters$3", at = @At(value = "INVOKE", target = SET_WILDCARD), require = 0, remap = false)
	private static boolean aftrhrs$fuelSlot(boolean wildcard) {
		return false;
	}
}
