package net.axther.aftrhrs.client.places;

import com.mojang.blaze3d.platform.InputConstants;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.hud.HudConfig;
import net.axther.aftrhrs.client.hud.HudElement;
import net.axther.aftrhrs.client.ui.HitButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every place you can go, nearest first: homes, warps, spawn, your death point and graves, with distance, a little
 * arrow pointing the way, and one click to go (the normal /home, /warp, /spawn, /back). Toggles for the beams and the
 * compass. The toggles and every place you can go to are widgets ({@link HitButton}): Tab, the arrows and Enter work,
 * and the Narrator reads them.
 */
public final class PlacesScreen extends Screen {
	private static final int ROW = 25;
	private int px, py, pw, ph;
	private float scroll;
	private HitButton beamsButton, compassButton;
	/** One widget per place with a command, by {@link #id}, in the order shown (nearest first: the Tab order too). */
	private final Map<String, HitButton> rowButtons = new HashMap<>();
	private final List<String> rowOrder = new ArrayList<>();
	/** Each row widget's place in the list this frame (for scrolling a focused one into view). */
	private final Map<HitButton, Integer> rowIndex = new HashMap<>();
	/** The list's scroll bar as last drawn (x, y, height, content, view), and whether it is being dragged. */
	private final int[] bar = new int[5];
	private boolean draggingBar;

	public PlacesScreen() {
		super(Component.translatable("aftrhrs.places.title"));
	}

	@Override
	protected void init() {
		pw = Math.min(width - 20, 300);
		ph = Math.min(height - 20, 240);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		rowButtons.clear();
		rowOrder.clear();
		rowIndex.clear();
		int tw = pw / 2 - 10;
		HudConfig cfg = HudConfig.get();
		beamsButton = addWidget(new HitButton("beams", tw, 16, Component.empty(), () -> {
			cfg.beams = HudConfig.Beams.values()[(cfg.beams.ordinal() + 1) % HudConfig.Beams.values().length];
			cfg.save();
		}, this::paintToggle));
		compassButton = addWidget(new HitButton("compass", tw, 16, Component.empty(), () -> HudConfig.toggle(HudElement.COMPASS), this::paintToggle));
	}

	private void paintToggle(GuiGraphicsExtractor g, HitButton self, boolean lit) {
		int x = self.getX(), y = self.getY(), w = self.getWidth();
		Theme.menuButton(g, x, y, w, 16, lit, true);
		g.centeredText(font, self.getMessage(), x + w / 2, y + 4, Theme.TEXT);
	}

	/** A place's widget key (a place is the same one across snapshots when these match). */
	private static String id(PlaceState.Place pl) {
		return pl.kind() + "|" + pl.name() + "|" + pl.command();
	}

	/** One widget per place you can go to, in the order shown (the children are re-ordered when that changes). */
	private void syncRows(List<PlaceState.Place> list, int top, int bottom) {
		List<String> order = new ArrayList<>();
		for (PlaceState.Place pl : list) if (pl.command() != null) order.add(id(pl));
		if (order.equals(rowOrder)) return;
		GuiEventListener focus = getFocused();
		for (HitButton b : rowButtons.values()) removeWidget(b);
		Map<String, HitButton> keep = new HashMap<>();
		for (PlaceState.Place pl : list) {
			if (pl.command() == null) continue;
			String id = id(pl), cmd = pl.command();
			HitButton b = rowButtons.get(id);
			if (b == null) {
				b = new HitButton("place:" + id, pw - 16, ROW - 2, Component.empty(), () -> {
					minecraft.player.connection.sendCommand(cmd);
					minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f));
					onClose();
				}, (g, self, lit) -> Theme.menuButton(g, self.getX(), self.getY(), self.getWidth(), self.getHeight(), lit, true)).quiet();
			}
			keep.put(id, addWidget(b.clip(top, bottom)));
		}
		rowButtons.clear();
		rowButtons.putAll(keep);
		rowOrder.clear();
		rowOrder.addAll(order);
		// a re-order removed and re-added the focused row: give it the focus back (no scrolling)
		if (focus instanceof HitButton h && rowButtons.containsValue(h) && getFocused() != h) super.setFocused(h);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		Player p = minecraft.player;
		PlaceState s = PlaceState.INSTANCE;
		Theme.blit(g, Theme.MENU_PANEL, px, py, pw, ph);
		Theme.blit(g, Theme.MENU_HEADER, px + 2, py + 2, pw - 4, 20);
		g.centeredText(font, title, px + pw / 2, py + 8, Theme.MOON);
		// Toggles.
		HudConfig cfg = HudConfig.get();
		beamsButton.place(px + 8, py + 26);
		beamsButton.setMessage(Component.translatable("aftrhrs.places.beams-" + cfg.beams.name().toLowerCase(Locale.ROOT)));
		beamsButton.extractRenderState(g, mx, my, a);
		compassButton.place(px + pw / 2 + 2, py + 26);
		compassButton.setMessage(Component.translatable(HudConfig.shown(HudElement.COMPASS) ? "aftrhrs.places.compass-on" : "aftrhrs.places.compass-off"));
		compassButton.extractRenderState(g, mx, my, a);
		List<PlaceState.Place> list = new ArrayList<>(s.here());
		if (p != null) list.sort(Comparator.comparingDouble(pl -> PlaceState.distance(p, pl)));
		int top = py + 48, bottom = py + ph - 8;
		syncRows(list, top, bottom);
		scroll = Mth.clamp(scroll, 0, Math.max(0, list.size() * ROW - (bottom - top)));
		placeRows(list, top);
		g.enableScissor(px + 4, top, px + pw - 4, bottom);
		int y = top - (int) scroll;
		for (PlaceState.Place pl : list) {
			if (y + ROW > top && y < bottom && p != null) {
				HitButton row = pl.command() == null ? null : rowButtons.get(id(pl));
				if (row != null) row.extractRenderState(g, mx, my, a);
				else Theme.menuButton(g, px + 8, y, pw - 16, ROW - 2, false, true);
				g.fill(px + 11, y + 3, px + 13, y + ROW - 5, 0xFF000000 | pl.color());
				String name = pl.kind().equals("death") ? Component.translatable("aftrhrs.places.death").getString() : pl.name();
				double d = PlaceState.distance(p, pl);
				String dist = String.format(Locale.ROOT, "%,dm", Math.round(d));
				int dx = px + pw - 46 - font.width(dist);
				// A long name stops short of the distance.
				// A home or warp with its own icon shows the item, the rest their symbol.
				net.minecraft.world.item.ItemStack icon = pl.iconStack();
				int tx = px + 18;
				if (!icon.isEmpty()) {
					g.item(icon, px + 16, y + 3);
					tx = px + 36;
				}
				String label = icon.isEmpty() ? pl.symbol() + " " + name : name;
				g.text(font, net.axther.aftrhrs.client.Theme.ellipsize(font, label, dx - 6 - tx), tx, y + 3, 0xFF000000 | pl.color(), true);
				g.text(font, Component.translatable("aftrhrs.places.kind." + pl.kind()), tx, y + 13, Theme.MUTED, false);
				g.text(font, dist, dx, y + 8, Theme.TEXT, true);
				// Arrow pointing the way, relative to where you look.
				float bearing = (float) Math.toDegrees(Math.atan2(-(pl.x() - p.getX()), pl.z() - p.getZ()));
				float rel = Mth.wrapDegrees(bearing - p.getYRot());
				g.pose().pushMatrix();
				g.pose().translate(px + pw - 30, y + 11);
				g.pose().rotate((float) Math.toRadians(rel));
				g.fill(-1, -6, 1, 5, Theme.MOON);
				g.fill(-3, -4, 4, -2, Theme.MOON);
				g.fill(-2, -5, 3, -4, Theme.MOON);
				g.pose().popMatrix();
			}
			y += ROW;
		}
		g.disableScissor();
		bar[0] = px + pw - 7;
		bar[1] = top;
		bar[2] = bottom - top;
		bar[3] = list.size() * ROW;
		bar[4] = bottom - top;
		Theme.scrollbar(g, bar[0], top, 3, bar[2], scroll, bar[3], bar[4], 0x40000000, Theme.MOON & 0xFFFFFF | 0xAA000000);
		if (list.isEmpty()) g.centeredText(font, Component.translatable("aftrhrs.places.none"), px + pw / 2, top + 20, Theme.MUTED);
		super.extractRenderState(g, mx, my, a);
	}

	/**
	 * Places every row widget (rows scrolled out of view too: the arrow keys pick by rectangle) and sets what the
	 * Narrator says: name, kind and distance.
	 */
	private void placeRows(List<PlaceState.Place> list, int top) {
		Player p = minecraft.player;
		rowIndex.clear();
		int y = top - (int) scroll;
		for (int i = 0; i < list.size(); i++, y += ROW) {
			PlaceState.Place pl = list.get(i);
			HitButton b = pl.command() == null ? null : rowButtons.get(id(pl));
			if (b == null) continue;
			rowIndex.put(b, i);
			b.place(px + 8, y);
			b.visible = p != null;
			String name = pl.kind().equals("death") ? Component.translatable("aftrhrs.places.death").getString() : pl.name();
			String dist = p == null ? "" : String.format(Locale.ROOT, "%,dm", Math.round(PlaceState.distance(p, pl)));
			b.setMessage(Component.translatable("aftrhrs.places.narrate", name, Component.translatable("aftrhrs.places.kind." + pl.kind()), dist));
		}
	}

	@Override
	public void setFocused(GuiEventListener l) {
		super.setFocused(l);
		// a row focused from the keyboard scrolls into view
		Integer i = l instanceof HitButton b ? rowIndex.get(b) : null;
		if (i == null) return;
		int top = py + 48, bottom = py + ph - 8, rowY = i * ROW;
		if (rowY - scroll < 2) scroll = rowY - 2;
		else if (rowY + ROW - scroll > bottom - top - 2) scroll = rowY + ROW - (bottom - top - 2);
		scroll = Math.max(0, scroll);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && bar[3] > bar[4] && Theme.inside(event.x(), event.y(), bar[0] - 1, bar[1], 5, bar[2])) {
			// the scroll bar: click to jump there, drag to scroll
			draggingBar = true;
			scroll = (float) Theme.scrollAt(event.y(), bar[1], bar[2], bar[3], bar[4]);
			return true;
		}
		// The toggles and the rows are widgets: super sends them the click.
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingBar) {
			scroll = (float) Theme.scrollAt(event.y(), bar[1], bar[2], bar[3], bar[4]);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingBar = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// The places key closes the screen it opened.
		if (Companion.PLACES_KEY.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll -= (float) sy * 16;
		return true;
	}
}
