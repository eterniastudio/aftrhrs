package net.axther.aftrhrs.client.mixin;

import net.minecraft.client.gui.components.SubtitleOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The subtitle rows showing (last frame's: the overlay refills the list as it draws), so their stack's height is known. */
@Mixin(SubtitleOverlay.class)
public interface SubtitleOverlayAccessor {
	@Accessor("audibleSubtitles")
	List<?> aftrhrs$audible();
}
