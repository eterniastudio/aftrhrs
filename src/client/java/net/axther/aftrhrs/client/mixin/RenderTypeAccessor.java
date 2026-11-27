package net.axther.aftrhrs.client.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** RenderType.create is package-private in vanilla (loaders widen it); the claim walls need their own type. */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
	@Invoker("create")
	static RenderType aftrhrs$create(String name, RenderSetup setup) {
		throw new AssertionError();
	}
}
