package net.axther.aftrhrs.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The HUD layout editor: every Companion HUD piece is shown (with sample content where nothing is going on) over the
 * live game. Drag a piece to move it (it snaps to the screen's center lines and edges), drag its corner or scroll over
 * it to resize, right-click to hide or show it, middle-click or R to put it back home. Arrow keys nudge the selected
 * piece; Tab (Shift+Tab) picks the next (previous) piece, and clicking the selected piece again picks the one under it.
 * Each piece remembers the nearest third of the screen it sits in, so it stays put when the window changes size.
 */
public final class HudEditorScreen extends Screen {
	private static final int HANDLE = 6, SNAP = 4;
	/** A hidden piece's label: secondary text that still reads (at least 4.5:1 on the night panel). */
	private final @Nullable Screen parent;
	private @Nullable HudElement selected, hovered;
	private boolean resizing, dragging, moved;
	/** The piece selected before the last press, and where it was (a click on it again picks the piece under it). */
	private @Nullable HudElement wasSelected;
	private double pressX, pressY;
	private float grabX, grabY;
	private boolean guideX, guideY;

	public HudEditorScreen(@Nullable Screen parent) {
		super(Component.translatable("aftrhrs.hud.editor"));
		this.parent = parent;
	}

	public static boolean isOpen() {
		return Minecraft.getInstance().gui.screen() instanceof HudEditorScreen;
	}

	/** Draws every HUD piece (the HUD mixin with {@code preview} false; the editor with true). */
	public static void drawAll(GuiGraphicsExtractor g, boolean preview) {
		HudConfig.newFrame();
		if (!preview) {
			// Photo mode: with vanilla's HUD hidden (F1) nothing of ours draws either.
			if (Minecraft.getInstance().gui.hud.isHidden()) return;
			// Quiet mode keeps only teleport requests, so Y / N never answer something unseen.
			if (HudConfig.quiet) {
				TpaToast.draw(g, false);
				return;
			}
		}
		net.axther.aftrhrs.client.places.PlaceHud.draw(g, preview);
		net.axther.aftrhrs.client.claims.ClaimHud.draw(g, preview);
		// Before the quest tracker: where the land name and the chat game card are this frame decides the free band the
		// tracker gets beside a scoreboard sidebar.
		ChatGameHud.draw(g, preview);
		QuestHud.draw(g, preview);
		net.axther.aftrhrs.client.pets.PetHud.draw(g, preview);
		WalletHud.draw(g, preview);
		InspectHud.draw(g, preview);
		// After the look-at card (it keeps clear of it), before the requests toast (it ends above it; the toast is on top).
		net.axther.aftrhrs.client.smithing.SmithingHud.draw(g, preview);
		TpaToast.draw(g, preview);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		HudConfig.INSTANCE.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		// Without a world (opened from the mods list) there is no game to see through: the usual menu background.
		if (minecraft.level == null) super.extractBackground(g, mx, my, a);
		g.fill(0, 0, width, height, 0x30000000);
		// Thirds: the anchor zones.
		int c = dragging ? 0x40C3A6FF : 0x18FFFFFF;
		g.fill(width / 3, 0, width / 3 + 1, height, c);
		g.fill(width * 2 / 3, 0, width * 2 / 3 + 1, height, c);
		g.fill(0, height / 3, width, height / 3 + 1, c);
		g.fill(0, height * 2 / 3, width, height * 2 / 3 + 1, c);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		HudConfig c = HudConfig.get();
		c.forgetDrawn();
		HudConfig.editing = true;
		try {
			drawAll(g, true);
		} finally {
			HudConfig.editing = false;
		}
		// The help chip is on top: the piece under it isn't hovered.
		if (!dragging) hovered = onChip(mx, my) ? null : at(mx, my);
		// Placeholders and outlines in draw order, so they stack the way the pieces do ...
		for (HudElement e : c.drawnInOrder()) {
			float[] r = c.lastDrawn(e);
			int x = Math.round(r[0]), y = Math.round(r[1]), w = Math.round(r[2]), h = Math.round(r[3]);
			boolean on = c.pos(e).on;
			if (!on) {
				g.fill(x, y, x + w, y + h, 0xCC140F26);
				Component label = Component.translatable("aftrhrs.hud.hidden-name", e.label());
				g.centeredText(font, Theme.firstLine(font, label, Math.max(20, w - 4)), x + w / 2, y + h / 2 - 4, Theme.SUBTLE);
			}
			g.outline(x - 1, y - 1, w + 2, h + 2, outlineColor(e));
		}
		// ... and the active pieces' handles and names last, so nothing drawn later covers them.
		if (hovered != null && hovered != selected) handleAndName(g, c, hovered);
		if (selected != null) handleAndName(g, c, selected);
		if (dragging && guideX) g.fill(width / 2, 0, width / 2 + 1, height, 0xAAFFE7A8);
		if (dragging && guideY) g.fill(0, height / 2, width, height / 2 + 1, 0xAAFFE7A8);
		if (hovered != null) {
			float[] r = c.lastDrawn(hovered);
			boolean corner = r != null && onHandle(r, mx, my);
			g.requestCursor(corner || resizing ? CursorTypes.CROSSHAIR : CursorTypes.RESIZE_ALL);
		}
		help(g, mx, my);
		super.extractRenderState(g, mx, my, a);
	}

	private int outlineColor(HudElement e) {
		return e == selected ? 0xFFFFE7A8 : e == hovered ? 0xFFC3A6FF : 0x66FFFFFF;
	}

	/** The resize handle and the "name · size" label of the selected or hovered piece. */
	private void handleAndName(GuiGraphicsExtractor g, HudConfig c, HudElement e) {
		float[] r = c.lastDrawn(e);
		if (r == null) return;
		int x = Math.round(r[0]), y = Math.round(r[1]), w = Math.round(r[2]), h = Math.round(r[3]);
		int col = outlineColor(e);
		g.outline(x - 1, y - 1, w + 2, h + 2, col);
		g.fill(x + w - HANDLE + 1, y + h - HANDLE + 1, x + w + 1, y + h + 1, col);
		Component name = Component.translatable("aftrhrs.hud.label", e.label(), Math.round(HudConfig.drawScale(c.pos(e).scale) * 100));
		int ly = y - 11 >= 0 ? y - 11 : y + h + 3;
		g.textWithBackdrop(font, name, x, ly, font.width(name), col);
	}

	/** After the first edit (a move, resize, hide, reset or nudge) the help shrinks to its first line (for this game session). */
	private static boolean helpSeen;
	/**
	 * Where the help panel goes (its top) and whether that spot covers no piece, where the one-line help chip goes when no
	 * such spot exists, and the layout they were chosen for; chosen again only when that changes.
	 */
	private int helpY, chipX, chipY;
	private boolean helpClear;
	private long helpKey = Long.MIN_VALUE;
	/** Whether the chip is on screen now (it takes clicks and the pointer). */
	private boolean chipShown;
	private int chipW;
	private static final int CHIP_H = 14;

	private boolean onChip(double mx, double my) {
		return chipShown && mx >= chipX && mx < chipX + chipW && my >= chipY && my < chipY + CHIP_H;
	}

	private void help(GuiGraphicsExtractor g, int mx, int my) {
		boolean over = onChip(mx, my);
		chipShown = false;
		if (dragging) return;
		int max = Math.min(width - 16, 440);
		java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
		lines.addAll(font.split(Component.translatable("aftrhrs.hud.help1"), max - 20));
		int first = lines.size();
		if (!helpSeen) lines.addAll(font.split(Component.translatable("aftrhrs.hud.help2"), max - 20));
		int w = 0;
		for (var l : lines) w = Math.max(w, font.width(l));
		w += 20;
		int h = lines.size() * 11 + 8, x = (width - w) / 2;
		Component chip = Component.translatable("aftrhrs.hud.help.chip");
		int cw = font.width(chip) + 12;
		HudConfig c = HudConfig.INSTANCE;
		long key = 31L * (31L * (31L * width + height) + h) + cw;
		for (HudElement e : c.drawnInOrder()) {
			float[] r = c.lastDrawn(e);
			key = key * 31 + e.ordinal();
			for (float v : r) key = key * 31 + Math.round(v);
		}
		if (key != helpKey) {
			helpKey = key;
			helpY = freeSpot(c, x, w, h);
			int[] spot = chipSpot(c, cw);
			chipX = spot[0];
			chipY = spot[1];
		}
		if (helpClear) {
			// Never over the piece being worked on.
			if (coversWorkedOn(c, x, helpY, w, h)) return;
			panel(g, lines, first, x, helpY, w, h, width / 2);
			return;
		}
		// No spot covers nothing (a big GUI scale): a one-line chip, and the whole help while the pointer is on it.
		if (!over && coversWorkedOn(c, chipX, chipY, cw, CHIP_H)) return;
		chipShown = true;
		chipW = cw;
		Theme.blit(g, Theme.MENU_PANEL, chipX, chipY, cw, CHIP_H);
		g.centeredText(font, chip, chipX + cw / 2, chipY + 3, over ? Theme.TEXT : Theme.MUTED);
		if (!over) return;
		// Anchored at the chip, growing toward the middle of the screen.
		int px = chipX + cw / 2 < width / 3 ? chipX : chipX + cw / 2 > width * 2 / 3 ? chipX + cw - w : chipX + cw / 2 - w / 2;
		int py = chipY + CHIP_H / 2 < height / 2 ? chipY : chipY + CHIP_H - h;
		px = Mth.clamp(px, 2, Math.max(2, width - w - 2));
		py = Mth.clamp(py, 2, Math.max(2, height - h - 2));
		panel(g, lines, first, px, py, w, h, px + w / 2);
	}

	private void panel(GuiGraphicsExtractor g, java.util.List<net.minecraft.util.FormattedCharSequence> lines, int first, int x, int y, int w, int h, int cx) {
		Theme.blit(g, Theme.MENU_PANEL, x, y, w, h);
		for (int i = 0; i < lines.size(); i++) g.centeredText(font, lines.get(i), cx, y + 5 + i * 11, i < first ? Theme.TEXT : Theme.MUTED);
	}

	/** Whether a box would cover the selected or hovered piece. */
	private boolean coversWorkedOn(HudConfig c, int x, int y, int w, int h) {
		for (HudElement e : new HudElement[]{selected, hovered}) {
			if (e != null && overlap(c.lastDrawn(e), x - 2, y - 2, w + 4, h + 4) > 0) return true;
		}
		return false;
	}

	/**
	 * The first of a few spots for the help panel that covers no piece ({@link #helpClear} true), else the one covering the
	 * least.
	 */
	private int freeSpot(HudConfig c, int x, int w, int h) {
		int[] tops = {height / 2 + 18, height / 2 - h - 18, height / 3 - h / 2, height * 2 / 3 - h / 2, 4, height - h - 4};
		int best = 0;
		long bestArea = Long.MAX_VALUE;
		for (int t : tops) {
			int y = Mth.clamp(t, 2, Math.max(2, height - h - 2));
			long area = 0;
			for (HudElement e : c.drawnInOrder()) area += overlap(c.lastDrawn(e), x - 2, y - 2, w + 4, h + 4);
			if (area == 0) {
				helpClear = true;
				return y;
			}
			if (area < bestArea) {
				bestArea = area;
				best = y;
			}
		}
		helpClear = false;
		return best;
	}

	/** The chip's spot {x, y}: the first of the four corners and the top and bottom middles covering no piece, else the least. */
	private int[] chipSpot(HudConfig c, int w) {
		int l = 2, r = Math.max(2, width - w - 2), m = Math.max(2, width / 2 - w / 2), t = 2, bt = Math.max(2, height - CHIP_H - 2);
		int[][] spots = {{l, bt}, {r, bt}, {l, t}, {r, t}, {m, t}, {m, bt}};
		int[] best = spots[0];
		long bestArea = Long.MAX_VALUE;
		for (int[] s : spots) {
			long area = 0;
			for (HudElement e : c.drawnInOrder()) area += overlap(c.lastDrawn(e), s[0] - 2, s[1] - 2, w + 4, CHIP_H + 4);
			if (area == 0) return s;
			if (area < bestArea) {
				bestArea = area;
				best = s;
			}
		}
		return best;
	}

	/** How much of a piece's rectangle (with its outline and the name label above it) a box covers. */
	private static long overlap(float @Nullable [] r, int x, int y, int w, int h) {
		if (r == null) return 0;
		float rx = r[0] - 1, ry = r[1] - 1 - 12, rw = r[2] + 2, rh = r[3] + 2 + 12;
		float ox = Math.min(x + w, rx + rw) - Math.max(x, rx), oy = Math.min(y + h, ry + rh) - Math.max(y, ry);
		return ox <= 0 || oy <= 0 ? 0 : (long) Math.ceil(ox * oy);
	}

	private @Nullable HudElement at(double mx, double my) {
		HudConfig c = HudConfig.INSTANCE;
		// The selected piece stays grabbable under one stacked on it.
		if (selected != null && inside(c.lastDrawn(selected), mx, my)) return selected;
		// Last drawn is on top.
		for (HudElement e : c.drawnInOrder().reversed()) {
			if (inside(c.lastDrawn(e), mx, my)) return e;
		}
		return null;
	}

	private static boolean inside(float @Nullable [] r, double mx, double my) {
		return r != null && mx >= r[0] - 1 && mx < r[0] + r[2] + 2 && my >= r[1] - 1 && my < r[1] + r[3] + 2;
	}

	private static boolean onHandle(float[] r, double mx, double my) {
		return mx >= r[0] + r[2] - HANDLE - 1 && mx <= r[0] + r[2] + 2 && my >= r[1] + r[3] - HANDLE - 1 && my <= r[1] + r[3] + 2;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The help chip takes the click (and keeps the selection).
		if (onChip(event.x(), event.y())) return true;
		wasSelected = selected;
		pressX = event.x();
		pressY = event.y();
		HudElement e = at(event.x(), event.y());
		HudConfig c = HudConfig.INSTANCE;
		if (e == null) {
			selected = null;
			return super.mouseClicked(event, doubleClick);
		}
		selected = e;
		switch (event.button()) {
			case InputConstants.MOUSE_BUTTON_LEFT -> {
				float[] r = c.lastDrawn(e);
				resizing = onHandle(r, event.x(), event.y());
				dragging = true;
				grabX = (float) event.x() - r[0];
				grabY = (float) event.y() - r[1];
			}
			case InputConstants.MOUSE_BUTTON_RIGHT -> {
				HudConfig.Pos p = c.pos(e);
				p.on = !p.on;
				c.save();
				helpSeen = true;
				click(p.on ? 1.4f : 0.9f);
			}
			case InputConstants.MOUSE_BUTTON_MIDDLE -> reset(e);
			default -> {
			}
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (!dragging || selected == null) return super.mouseDragged(event, dx, dy);
		HudConfig c = HudConfig.INSTANCE;
		float[] r = c.lastDrawn(selected);
		if (r == null) return true;
		HudConfig.Pos p = c.pos(selected);
		moved = true;
		float ds = HudConfig.drawScale(p.scale);
		float baseW = r[2] / ds, baseH = r[3] / ds;
		if (resizing) {
			float s = Mth.clamp((float) Math.max((event.x() - r[0]) / baseW, (event.y() - r[1]) / baseH), HudConfig.MIN_SCALE, HudConfig.MAX_SCALE);
			// Only sizes the pixel font draws evenly at.
			p.scale = HudConfig.drawScale(s);
			c.moveTo(selected, r[0], r[1], baseW * p.scale, baseH * p.scale, width, height);
		} else {
			float x = (float) event.x() - grabX, y = (float) event.y() - grabY;
			x = Mth.clamp(x, 0, Math.max(0, width - r[2]));
			y = Mth.clamp(y, 0, Math.max(0, height - r[3]));
			guideX = Math.abs(x + r[2] / 2 - width / 2f) < SNAP;
			guideY = Math.abs(y + r[3] / 2 - height / 2f) < SNAP;
			if (guideX) x = width / 2f - r[2] / 2;
			if (guideY) y = height / 2f - r[3] / 2;
			if (x < SNAP) x = 0;
			if (y < SNAP) y = 0;
			if (width - (x + r[2]) < SNAP) x = width - r[2];
			if (height - (y + r[3]) < SNAP) y = height - r[3];
			c.moveTo(selected, x, y, r[2], r[3], width, height);
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging) {
			if (moved) helpSeen = true;
			// A click (no drag) on the piece already selected picks the next piece under the pointer, if any: pieces
			// stacked on each other can each be reached. A press on its resize handle is not such a click.
			else if (!resizing && selected != null && selected == wasSelected) selected = below(selected, pressX, pressY);
			moved = false;
			dragging = resizing = false;
			guideX = guideY = false;
			HudConfig.INSTANCE.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		HudElement e = at(mx, my);
		if (e == null || sy == 0) return false;
		HudConfig c = HudConfig.INSTANCE;
		float[] r = c.lastDrawn(e);
		HudConfig.Pos p = c.pos(e);
		if (r == null) return false;
		float ds = HudConfig.drawScale(p.scale);
		float baseW = r[2] / ds, baseH = r[3] / ds, cx = r[0] + r[2] / 2, cy = r[1] + r[3] / 2;
		// One crisp size step (one framebuffer px per texel).
		p.scale = HudConfig.stepScale(p.scale, sy > 0 ? 1 : -1);
		// Grow and shrink about the middle.
		float w = baseW * p.scale, h = baseH * p.scale;
		c.moveTo(e, Mth.clamp(cx - w / 2, 0, Math.max(0, width - w)), Mth.clamp(cy - h / 2, 0, Math.max(0, height - h)), w, h, width, height);
		c.save();
		helpSeen = true;
		selected = e;
		return true;
	}

	/** The next drawn piece under ({@code mx}, {@code my}) after {@code e}, top to bottom and round again; {@code e} with none. */
	private HudElement below(HudElement e, double mx, double my) {
		HudConfig c = HudConfig.INSTANCE;
		java.util.List<HudElement> stack = new java.util.ArrayList<>();
		for (HudElement o : c.drawnInOrder().reversed()) if (inside(c.lastDrawn(o), mx, my)) stack.add(o);
		int i = stack.indexOf(e);
		if (stack.size() < 2 || i < 0) return e;
		return stack.get((i + 1) % stack.size());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_TAB) {
			// Every piece without the mouse: Tab the next in draw order, Shift+Tab the one before; past either end nothing.
			java.util.List<HudElement> order = new java.util.ArrayList<>(HudConfig.INSTANCE.drawnInOrder());
			if (!order.isEmpty()) {
				boolean back = event.hasShiftDown();
				int i = selected == null ? -1 : order.indexOf(selected);
				if (i < 0) selected = back ? order.getLast() : order.getFirst();
				else {
					int j = i + (back ? -1 : 1);
					selected = j < 0 || j >= order.size() ? null : order.get(j);
				}
				hovered = null;
				click(1.2f);
				triggerImmediateNarration(false);
			}
			return true;
		}
		if (selected != null) {
			int step = event.hasShiftDown() ? 10 : 1;
			int dx = 0, dy = 0;
			switch (event.key()) {
				case InputConstants.KEY_LEFT -> dx = -step;
				case InputConstants.KEY_RIGHT -> dx = step;
				case InputConstants.KEY_UP -> dy = -step;
				case InputConstants.KEY_DOWN -> dy = step;
				case InputConstants.KEY_R -> {
					reset(selected);
					return true;
				}
				case InputConstants.KEY_H, InputConstants.KEY_DELETE -> {
					HudConfig.Pos p = HudConfig.INSTANCE.pos(selected);
					p.on = !p.on;
					HudConfig.INSTANCE.save();
					helpSeen = true;
					triggerImmediateNarration(false);
					return true;
				}
				default -> {
				}
			}
			if (dx != 0 || dy != 0) {
				HudConfig c = HudConfig.INSTANCE;
				float[] r = c.lastDrawn(selected);
				if (r != null) {
					c.moveTo(selected, Mth.clamp(r[0] + dx, 0, Math.max(0, width - r[2])), Mth.clamp(r[1] + dy, 0, Math.max(0, height - r[3])), r[2], r[3], width, height);
					c.save();
					helpSeen = true;
				}
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private void reset(HudElement e) {
		HudConfig.INSTANCE.reset(e);
		HudConfig.INSTANCE.save();
		helpSeen = true;
		click(1.0f);
	}

	@Override
	protected void updateNarrationState(NarrationElementOutput out) {
		super.updateNarrationState(out);
		HudElement e = selected;
		if (e == null) return;
		HudConfig c = HudConfig.INSTANCE;
		HudConfig.Pos p = c.pos(e);
		String state = !p.on ? "hidden" : c.atHome(e) ? "home" : "moved";
		out.add(NarratedElementType.TITLE, Component.translatable("aftrhrs.hud.narrate", e.label(), Math.round(HudConfig.drawScale(p.scale) * 100),
			Component.translatable("aftrhrs.hud.narrate." + state)));
	}

	private void click(float pitch) {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch));
	}
}
