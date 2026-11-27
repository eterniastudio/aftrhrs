package net.axther.aftrhrs.client.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.SubtitleOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's subtitle box, so the coin purse can keep its lines above the subtitles. */
@Mixin(Hud.class)
public interface HudSubtitleAccessor {
	@Accessor("subtitleOverlay")
	SubtitleOverlay aftrhrs$subtitles();
}
