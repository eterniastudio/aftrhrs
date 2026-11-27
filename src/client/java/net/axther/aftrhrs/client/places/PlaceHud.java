package net.axther.aftrhrs.client.places;

import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.hud.HudConfig;
import net.axther.aftrhrs.client.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * A compass ribbon along the top of the screen: the four directions, and every place (home, warp, spawn, your death
 * point, graves) as a colored marker where it lies. The one you face is named underneath with its distance.
 */
public final class PlaceHud {
	private PlaceHud() {
	}

	/** {@code preview}: the HUD layout editor is drawing it (shown even without places or Feather). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		Player p = mc.player;
		PlaceState s = PlaceState.INSTANCE;
		// The layout editor also works without a world (the mods list): then the ribbon faces north, without places.
		if (!preview && (p == null || !s.active() || mc.gui.hud.isHidden() || HudConfig.covered(mc))) return;
		int width = HudConfig.get().compassWidth;
		if (!HudConfig.begin(g, HudElement.COMPASS, width, 28)) return;
		try {
			body(g, mc, p, s, width);
		} finally {
			HudConfig.end(g);
		}
	}

	private static void body(GuiGraphicsExtractor g, Minecraft mc, Player p, PlaceState s, int width) {
		Font font = mc.font;
		int half = width / 2, cx = half, y = 1;
		float yaw = p == null ? 180f : Mth.wrapDegrees(p.getYRot());
		// A see-through strip, but dark enough that the dim direction labels read over snow and sky.
		g.fill(cx - half, y, cx + half, y + 11, 0xC0140F26);
		g.fill(cx - half, y + 11, cx + half, y + 12, 0x55C3A6FF);
		// Directions every 45°.
		Language lang = Language.getInstance();
		boolean uni = mc.options.forceUnicodeFont().get();
		if (lang != widthsLang || uni != widthsUni) {
			// The fixed labels' widths change with the font: Force Unicode Font, or a resource reload (a new Language).
			widthsLang = lang;
			widthsUni = uni;
			for (int i = 0; i < DIRS.length; i++) DIR_W[i] = font.width(DIRS[i]);
			for (int i = 0; i < SYMBOLS.length; i++) SYMBOL_W[i] = font.width(SYMBOLS[i]);
			facingFor = null;
		}
		for (int i = 0; i < 8; i++) {
			float rel = Mth.wrapDegrees(i * 45f - yaw);
			if (Math.abs(rel) > 90) continue;
			int x = cx + Math.round(rel / 90f * half);
			boolean major = i % 2 == 0;
			int alpha = edgeAlpha(rel);
			g.text(font, DIRS[i], x - DIR_W[i] / 2, y + 2, (alpha << 24) | (major ? (i == 4 ? 0xFF8F8F : 0xF1EDFF) : 0xA49DC2));
		}
		for (int a = 0; a < 360; a += 15) {
			if (a % 45 == 0) continue;
			float rel = Mth.wrapDegrees(a - yaw);
			if (Math.abs(rel) > 90) continue;
			int x = cx + Math.round(rel / 90f * half);
			g.fill(x, y + 8, x + 1, y + 11, (edgeAlpha(rel) / 2 << 24) | 0xA49DC2);
		}
		// Places.
		PlaceState.Place facing = null;
		float best = 12f;
		for (PlaceState.Place pl : p == null ? java.util.List.<PlaceState.Place>of() : s.hereView()) {
			double dx = pl.x() - p.getX(), dz = pl.z() - p.getZ();
			if (dx * dx + dz * dz < 4) continue;
			float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float rel = Mth.wrapDegrees(bearing - yaw);
			if (Math.abs(rel) > 90) continue;
			int x = cx + Math.round(rel / 90f * half);
			int col = (edgeAlpha(rel) << 24) | pl.color();
			g.fill(x - 3, y - 1, x + 4, y + 12, (edgeAlpha(rel) / 3 << 24) | pl.color());
			int si = symbol(pl.kind());
			g.text(font, SYMBOLS[si], x + 1 - SYMBOL_W[si] / 2, y + 2, col);
			if (Math.abs(rel) < best) {
				best = Math.abs(rel);
				facing = pl;
			}
		}
		g.fill(cx, y - 1, cx + 1, y + 13, 0xFFFFE7A8);
		if (facing != null) {
			long metres = Math.round(PlaceState.distance(p, facing));
			// Laid out again only when the place, the whole metres, the language or the font change.
			if (facing != facingFor || metres != facingMetres) {
				String name = facing.kind().equals("death") ? Component.translatable("aftrhrs.places.death").getString() : facing.name();
				String text = facing.symbol() + " " + name + "  ·  " + String.format(Locale.ROOT, "%,dm", metres);
				facingFor = facing;
				facingMetres = metres;
				facingText = lang.getVisualOrder(FormattedText.of(text));
				facingW = font.width(facingText);
			}
			int tw = facingW + 10;
			Theme.blit(g, Theme.MENU_INSET, cx - tw / 2, y + 14, tw, 12);
			g.text(font, facingText, cx - facingW / 2, y + 16, 0xFF000000 | facing.color());
		}
	}

	/** The direction labels (S first, every 45°), laid out once; their widths follow the font ({@link #widthsLang}). */
	private static final FormattedCharSequence[] DIRS = forward("S", "SW", "W", "NW", "N", "NE", "E", "SE");
	/** The places' symbols, by {@link #symbol}; the same characters as {@link PlaceState.Place#symbol}. */
	private static final FormattedCharSequence[] SYMBOLS = forward("⌂", "✦", "☀", "☠", "✝", "•");
	private static final int[] DIR_W = new int[DIRS.length], SYMBOL_W = new int[SYMBOLS.length];
	private static Language widthsLang;
	private static boolean widthsUni;
	/** The "⌂ Home  ·  120m" line under the ribbon, for this place at this distance. */
	private static PlaceState.Place facingFor;
	private static long facingMetres;
	private static FormattedCharSequence facingText;
	private static int facingW;

	private static FormattedCharSequence[] forward(String... s) {
		FormattedCharSequence[] out = new FormattedCharSequence[s.length];
		for (int i = 0; i < s.length; i++) out[i] = FormattedCharSequence.forward(s[i], Style.EMPTY);
		return out;
	}

	private static int symbol(String kind) {
		return switch (kind) {
			case "home" -> 0;
			case "warp" -> 1;
			case "spawn" -> 2;
			case "death" -> 3;
			case "grave" -> 4;
			default -> 5;
		};
	}

	private static int edgeAlpha(float rel) {
		return Math.round(255 * Mth.clamp((90 - Math.abs(rel)) / 25f, 0.15f, 1f));
	}
}
