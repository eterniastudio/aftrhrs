package net.axther.aftrhrs.client.mixin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import net.axther.aftrhrs.client.entity.CreatureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link CreatureView} submit its own picture-in-picture states. Vanilla's scissor stack is a private class, so
 * this keeps a copy of it (same intersection rule) for the states' clip area.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin implements CreatureView.Sink {
	@Shadow
	@Final
	private GuiRenderState guiRenderState;

	@Unique
	private final Deque<ScreenRectangle> aftrhrs$scissors = new ArrayDeque<>();

	@Shadow
	public abstract Matrix3x2fStack pose();

	@Shadow
	public abstract int guiWidth();

	@Shadow
	public abstract int guiHeight();

	@Inject(method = "enableScissor", at = @At("TAIL"))
	private void aftrhrs$pushScissor(int x0, int y0, int x1, int y1, CallbackInfo ci) {
		ScreenRectangle rect = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformAxisAligned(pose());
		ScreenRectangle last = Objects.requireNonNullElse(aftrhrs$scissors.peekLast(), new ScreenRectangle(0, 0, guiWidth(), guiHeight()));
		aftrhrs$scissors.addLast(Objects.requireNonNullElse(rect.intersection(last), ScreenRectangle.empty()));
	}

	@Inject(method = "disableScissor", at = @At("HEAD"))
	private void aftrhrs$popScissor(CallbackInfo ci) {
		aftrhrs$scissors.pollLast();
	}

	@Override
	public void aftrhrs$submitPip(PictureInPictureRenderState state) {
		guiRenderState.addPicturesInPictureState(state);
	}

	@Override
	public @Nullable ScreenRectangle aftrhrs$scissor() {
		return aftrhrs$scissors.peekLast();
	}
}
