package net.axther.aftrhrs.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The Companion's look. Feather menus use the hub art's night palette and pixel sprites
 * ({@code textures/gui/sprites/menu/*}, made by {@code art/gui_sprites.py}); terminal screens use vanilla's container
 * gray and vanilla's own slot/button sprites, so they sit next to the crafting table as if they were part of it.
 */
public final class Theme {
	// hub art palette (Feather messages.yml)
	public static final int TEXT = 0xFFF1EDFF;
	public static final int MUTED = 0xFFA49DC2;
	// TEXT: primary. MUTED: secondary info. SUBTLE: present but de-emphasised (off, unknown, empty, hints); >=4.5:1 on panel/inset/tooltip.
	// DIM: disabled/inactive controls only (WCAG-exempt); never for text the player needs to read.
	public static final int SUBTLE = 0xFF8A83A8;
	public static final int DIM = 0xFF6B6487;
	public static final int ACCENT = 0xFFC3A6FF;
	public static final int SKY = 0xFF8FD3FF;
	public static final int MOON = 0xFFFFE7A8;
	// vanilla container text
	public static final int LABEL = 0xFF404040;
	public static final int LABEL_SOFT = 0xFF505050;
	public static final int BAD = 0xFFFF5555;
	public static final int BAD_ON_LIGHT = 0xFFAA0000; // vanilla dark red, 4.54 on container gray

	public static final Identifier MENU_PANEL = sprite("menu/panel");
	public static final Identifier MENU_HEADER = sprite("menu/header");
	public static final Identifier MENU_DIVIDER = sprite("menu/divider");
	public static final Identifier MENU_BUTTON = sprite("menu/button");
	public static final Identifier MENU_BUTTON_HOVER = sprite("menu/button_hover");
	public static final Identifier MENU_BUTTON_DISABLED = sprite("menu/button_disabled");
	public static final Identifier MENU_TAB = sprite("menu/tab");
	public static final Identifier MENU_TAB_SELECTED = sprite("menu/tab_selected");
	public static final Identifier MENU_INSET = sprite("menu/inset");
	public static final Identifier MENU_SCROLLER = sprite("menu/scroller");
	public static final Identifier STORAGE_PANEL = sprite("storage/panel");
	public static final Identifier STORAGE_WELL = sprite("storage/well");
	/** Vanilla's empty slot nine times across ({@code art/gui_sprites.py --mod-storage}); see {@link #slotRow}. */
	public static final Identifier SLOT_ROW = sprite("storage/slot_row");

	public static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
	public static final Identifier SLOT_HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back");
	public static final Identifier SLOT_HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
	public static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
	public static final Identifier BUTTON_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
	public static final Identifier SCROLLER = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
	public static final Identifier SCROLLER_DISABLED = Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");

	private Theme() {
	}

	private static Identifier sprite(String path) {
		return Identifier.fromNamespaceAndPath(Companion.ID, path);
	}

	public static void blit(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
	}

	/** Empty slot frames for one row of n (1..9) cells in a single GUI element (per-slot blits each pay GuiRenderState's layering search). */
	public static void slotRow(GuiGraphicsExtractor g, int x, int y, int n) {
		if (n <= 0) return;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_ROW, 162, 18, 0, 0, x, y, Math.min(9, n) * 18, 18);
		for (int c = 9; c < n; c++) blit(g, SLOT, x + c * 18, y, 18, 18); // never hit today (cols <= 9), kept for safety
	}

	/** A night button in one of its three states. */
	public static void menuButton(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, boolean active) {
		blit(g, !active ? MENU_BUTTON_DISABLED : hover ? MENU_BUTTON_HOVER : MENU_BUTTON, x, y, w, h);
	}

	/** A vanilla button (terminal screens). */
	public static void vanillaButton(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover) {
		blit(g, hover ? BUTTON_HOVER : BUTTON, x, y, w, h);
	}

	/** Centered text without a drop shadow: dark ink on a light card or button, where a shadow smears the letters. */
	public static void centeredFlat(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence text, int cx, int y, int color) {
		g.text(font, text, cx - font.width(text) / 2, y, color, false);
	}

	public static void centeredFlat(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component text, int cx, int y, int color) {
		centeredFlat(g, font, text.getVisualOrderText(), cx, y, color);
	}

	public static void centeredFlat(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text, int cx, int y, int color) {
		g.text(font, text, cx - font.width(text) / 2, y, color, false);
	}

	public static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** The first line of {@code text} wrapped to {@code width}; empty (never missing) for blank text. */
	public static net.minecraft.util.FormattedCharSequence firstLine(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text, int width) {
		java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(text, Math.max(1, width));
		return lines.isEmpty() ? net.minecraft.util.FormattedCharSequence.EMPTY : lines.getFirst();
	}

	/** Plain text cut to {@code width} with "…" when it doesn't fit, so a cut is never mistaken for the whole name. */
	public static String ellipsize(net.minecraft.client.gui.Font font, String text, int width) {
		if (text == null || font.width(text) <= width) return text == null ? "" : text;
		int room = width - font.width("…");
		return room <= 0 ? "" : font.plainSubstrByWidth(text, room).stripTrailing() + "…";
	}

	/** Styled text cut to {@code width} with "…" (see {@link #fit}): colored or bold names keep their look. */
	public static net.minecraft.util.FormattedCharSequence ellipsize(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text, int width) {
		return fit(font, text, width);
	}

	/** {@code text} cut off at {@code width} (styles kept). */
	public static net.minecraft.util.FormattedCharSequence clip(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text, int width) {
		return net.minecraft.locale.Language.getInstance().getVisualOrder(font.substrByWidth(text, Math.max(0, width)));
	}

	/**
	 * {@code text} on one line within {@code width}: cut by width (not at a word break) and ended with "…" in the style
	 * at the cut when it doesn't fit, so a cut label never passes for the whole one. Only the first line of a label
	 * with a line break is shown.
	 */
	public static net.minecraft.util.FormattedCharSequence fit(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text, int width) {
		net.minecraft.network.chat.FormattedText line = firstParagraph(font, text);
		if (font.width(line) <= width) return net.minecraft.locale.Language.getInstance().getVisualOrder(line);
		int room = width - font.width("…");
		if (room <= 0) return net.minecraft.util.FormattedCharSequence.EMPTY;
		net.minecraft.network.chat.FormattedText head = font.substrByWidth(line, room);
		net.minecraft.network.chat.Style[] last = {net.minecraft.network.chat.Style.EMPTY};
		head.visit((style, s) -> {
			if (!s.isEmpty()) last[0] = style;
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return net.minecraft.util.FormattedCharSequence.composite(net.minecraft.locale.Language.getInstance().getVisualOrder(head),
			net.minecraft.util.FormattedCharSequence.forward("…", last[0]));
	}

	/** Whether {@link #fit} shows {@code text} whole (one line, no "…"). */
	public static boolean fits(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text, int width) {
		return text.getString().indexOf('\n') < 0 && font.width(text) <= width;
	}

	private static net.minecraft.network.chat.FormattedText firstParagraph(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text) {
		if (text.getString().indexOf('\n') < 0) return text;
		java.util.List<net.minecraft.network.chat.FormattedText> lines = font.getSplitter().splitLines(text, Integer.MAX_VALUE, net.minecraft.network.chat.Style.EMPTY);
		return lines.isEmpty() ? net.minecraft.network.chat.FormattedText.EMPTY : lines.getFirst();
	}

	// ------------------------------------------------------------------ scroll bars

	/** The thumb's length on a track {@code h} long (at least {@code min}). */
	private static int thumb(int h, int content, int view, int min) {
		return Math.min(h, Math.max(min, (int) ((long) h * view / content)));
	}

	/** The thumb's top on a track at {@code y}, {@code h} long. */
	private static int thumbY(int y, int h, int bar, double scroll, int content, int view) {
		return y + (int) ((h - bar) * Mth.clamp(scroll / (content - view), 0, 1));
	}

	/** Vertical scroll indicator in flat colors ({@code trackArgb} 0: no track); draws nothing when the content fits. */
	public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int w, int h, double scroll, int content, int view, int trackArgb, int thumbArgb) {
		if (content <= view || h <= 0) return;
		if (trackArgb != 0) g.fill(x, y, x + w, y + h, trackArgb);
		int bar = thumb(h, content, view, 10);
		int ty = thumbY(y, h, bar, scroll, content, view);
		g.fill(x, ty, x + w, ty + bar, thumbArgb);
	}

	/** The same with sprites (the night menus' inset track and scroller); draws nothing when the content fits. */
	public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int w, int h, double scroll, int content, int view, Identifier track, Identifier thumb) {
		if (content <= view || h <= 0) return;
		blit(g, track, x, y, w, h);
		int bar = thumb(h, content, view, 12);
		blit(g, thumb, x, thumbY(y, h, bar, scroll, content, view), w, bar);
	}

	/** The scroll that puts the (flat) thumb's middle under the pointer at {@code my}, for dragging a bar. */
	public static double scrollAt(double my, int y, int h, int content, int view) {
		if (content <= view || h <= 0) return 0;
		int bar = thumb(h, content, view, 10);
		double t = (my - y - bar / 2.0) / Math.max(1, h - bar);
		return Mth.clamp(t, 0, 1) * (content - view);
	}
}
