package net.axther.aftrhrs.client.mixin;

import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Animates the hub banner (font {@code feather:hub_banner}, ex Oraxen animated glyph): the server only sends U+E000;
 * the font holds 16 frames at U+E000..U+E00F (mod/tools/gen_glyphs.py) and this swaps in the frame for the time,
 * 6 frames a second, like Oraxen's text shader did.
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
public abstract class BannerGlyphMixin {
	@Unique
	private static final Identifier BANNER = Identifier.fromNamespaceAndPath("feather", "hub_banner");
	@Unique
	private static final int FIRST = 0xE000, FRAMES = 16, FPS = 6;

	@Inject(method = "getGlyphSource", at = @At("RETURN"), cancellable = true)
	private void aftrhrs$animateBanner(FontDescription description, CallbackInfoReturnable<GlyphSource> cir) {
		if (!(description instanceof FontDescription.Resource r) || !BANNER.equals(r.id())) return;
		GlyphSource frames = cir.getReturnValue();
		cir.setReturnValue(new GlyphSource() {
			@Override
			public BakedGlyph getGlyph(int codepoint) {
				if (codepoint != FIRST) return frames.getGlyph(codepoint);
				return frames.getGlyph(FIRST + (int) (Util.getMillis() * FPS / 1000L % FRAMES));
			}

			@Override
			public BakedGlyph getRandomGlyph(RandomSource random, int width) {
				return frames.getRandomGlyph(random, width);
			}
		});
	}
}
