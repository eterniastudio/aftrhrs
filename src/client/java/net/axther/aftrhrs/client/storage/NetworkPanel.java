package net.axther.aftrhrs.client.storage;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The terminal's grid as a vanilla-looking container section: title with the Items / Craftable tabs, search field
 * (with the JEI sync toggle when JEI runs, and the search options), the view's buttons, a grid of vanilla slots with a creative-style
 * scroller, and a footer line. Used by both terminal screens.
 *
 * <ul>
 *   <li><b>Items</b>: the network's items. Searching and sorting happen here, instantly; taking and putting go to the
 *   server.</li>
 *   <li><b>Craftable</b> (Quick Craft, when the server has it): what can be made from the network plus the
 *   inventory, with how many; Show switches to All Recipes (what is missing in the tooltip), Auto-Craft and the
 *   craft amount sit beside it. The server works out the list (the search box filters it there) and does every
 *   craft; the screen only asks.</li>
 * </ul>
 */
public final class NetworkPanel {
	public static final int CELL = 18;
	/** Title row + search row above the grid. */
	static final int HEAD = 32;
	/** Footer line below the grid. */
	static final int FOOT = 18;
	static final int SIDE = 7;
	static final int SCROLL_W = 14;
	private static final int BTN = 18, GAP = 2, JEI_W = 14, OPT_W = 14;
	/** Wait this long after the last key before the server searches the recipes. */
	private static final long QUERY_DELAY = 250;

	private final StorageSession session;
	private final Font font;
	public final EditBox search;
	public int x, y, cols, rows;
	/** Draw the gray panel behind it (false when the screen draws one around more than the panel). */
	public boolean ownBackground = true;
	/** A hopper button at the right of the header: store everything but the hotbar. */
	public boolean depositButton = false;
	private StoragePrefs.View view = StoragePrefs.View.ITEMS;
	private boolean byName;
	private int scrollRow;
	private boolean draggingScroll;
	private List<StorageSession.Entry> sorted = List.of();
	private int sortedVersion = -1;
	private String sortedQuery = null;
	private boolean sortedByName;
	private StorageSession.Entry hovered;
	private StorageSession.Recipe hoveredRecipe;
	private Component hoverTip;
	private long queryChanged;
	// JEI sync: the JEI text last seen, and a guard while the box is set from JEI.
	private String lastJei;
	private boolean fromJei;
	/** The search options (what the box does when a terminal opens) show in the grid's place. */
	private boolean optionsOpen;

	public NetworkPanel(StorageSession session, Font font) {
		this.session = session;
		this.font = font;
		this.search = new EditBox(font, 0, 0, 80, 14, Component.translatable("aftrhrs.storage.search"));
		search.setHint(Component.translatable("aftrhrs.storage.search"));
		search.setMaxLength(50);
		search.setResponder(this::searchChanged);
		openSearch();
		// The view the player left the terminal in; Craftable only when this server has Quick Craft.
		StoragePrefs.View last = StoragePrefs.view();
		if (last.crafting() && session.quickCraft) {
			view = last;
			session.craftOpen(view == StoragePrefs.View.ALL, search.getValue().trim());
		}
		hint();
	}

	public StoragePrefs.View view() {
		return view;
	}

	public boolean crafting() {
		return view.crafting();
	}

	public int width() {
		return SIDE + cols * CELL + 4 + SCROLL_W + SIDE;
	}

	public int height() {
		return HEAD + rows * CELL + FOOT;
	}

	/** Places the panel; the search box follows. */
	public void place(int x, int y, int cols, int rows) {
		this.x = x;
		this.y = y;
		this.cols = Math.max(3, cols);
		this.rows = Math.max(2, rows);
		layout();
	}

	// ------------------------------------------------------------------ header layout

	/** The buttons right of the search box, left to right, for the current view. */
	private enum Btn {SORT, SHOW, AUTO, AMOUNT, DEPOSIT}

	private List<Btn> buttons() {
		List<Btn> list = new ArrayList<>(4);
		if (crafting()) {
			list.add(Btn.SHOW);
			if (session.craftDepth > 0) list.add(Btn.AUTO);
			list.add(Btn.AMOUNT);
		} else {
			list.add(Btn.SORT);
		}
		if (depositButton) list.add(Btn.DEPOSIT);
		return list;
	}

	private int buttonX(int i, int n) {
		return x + SIDE + cols * CELL - (n - i) * BTN - (n - i - 1) * GAP;
	}

	private int headY() {
		return y + 15;
	}

	private boolean jeiShown() {
		return StoragePrefs.searchLink() != null;
	}

	private int jeiX() {
		return search.getX() + search.getWidth() + GAP;
	}

	/** The search options button, right of the J (or of the search box without JEI). */
	private int optX() {
		return jeiX() + (jeiShown() ? JEI_W + GAP : 0);
	}

	private void layout() {
		int n = buttons().size();
		int w = cols * CELL - n * (BTN + GAP) - 2 - (jeiShown() ? JEI_W + GAP : 0) - (OPT_W + GAP);
		search.setX(x + SIDE);
		search.setY(y + 16);
		search.setWidth(Math.max(24, w));
		hint();
	}

	private int hintWidth = -1;
	private boolean hintCrafting;

	/** The search box's hint, cut to fit (the box draws it unclipped, so a long one would run under the J button). */
	private void hint() {
		if (hintWidth == search.getWidth() && hintCrafting == crafting()) return;
		hintWidth = search.getWidth();
		hintCrafting = crafting();
		String text = net.minecraft.client.resources.language.I18n.get(crafting() ? "aftrhrs.storage.search-recipes" : "aftrhrs.storage.search");
		search.setHint(Component.literal(Theme.ellipsize(font, text, hintWidth - 8)));
	}

	// ------------------------------------------------------------------ tabs (title row)

	private Component tabLabel(boolean craft, boolean shortLabel) {
		return Component.translatable(craft ? (shortLabel ? "aftrhrs.storage.view-craft-short" : "aftrhrs.storage.view-craft") : "aftrhrs.storage.view-items");
	}

	/** The two tabs' x positions and widths: {itemsX, itemsW, craftX, craftW}; null without Quick Craft. */
	private int[] tabs() {
		if (!session.quickCraft) return null;
		boolean shortLabel = cols * CELL - tabWidth(false) - tabWidth(true) - 2 < 40;
		int cw = tabWidth(true, shortLabel), iw = tabWidth(false, shortLabel);
		int right = x + SIDE + cols * CELL;
		return new int[]{right - cw - 2 - iw, iw, right - cw, cw, shortLabel ? 1 : 0};
	}

	private int tabWidth(boolean craft) {
		return tabWidth(craft, false);
	}

	private int tabWidth(boolean craft, boolean shortLabel) {
		return font.width(tabLabel(craft, shortLabel)) + 8;
	}

	private void renderTabs(GuiGraphicsExtractor g, int mx, int my, int[] t) {
		for (int k = 0; k < 2; k++) {
			boolean craft = k == 1;
			int tx = t[k * 2], tw = t[k * 2 + 1], ty = y + 2;
			boolean selected = craft == crafting();
			boolean hov = Theme.inside(mx, my, tx, ty, tw, 13);
			Theme.vanillaButton(g, tx, ty, tw, 13, hov && !selected);
			int color = selected ? 0xFFFFFFFF : hov ? 0xFFFFFFA0 : 0xFFA0A0A0;
			g.centeredText(font, tabLabel(craft, t[4] == 1), tx + tw / 2, ty + 3, color);
			if (selected) g.fill(tx + 2, ty + 11, tx + tw - 2, ty + 12, 0xFFFFFFFF);
			if (hov) {
				hoverTip = Component.translatable(craft ? "aftrhrs.storage.view-craft-tip" : "aftrhrs.storage.view-items-tip");
				if (!selected) g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}
	}

	/** Switches the grid between the network's items and Quick Craft (remembered for next time). */
	public void setView(StoragePrefs.View v) {
		if (v.crafting() && !session.quickCraft) v = StoragePrefs.View.ITEMS;
		if (v == view) return;
		StoragePrefs.View was = view;
		view = v;
		optionsOpen = false;
		StoragePrefs.view(v);
		scrollRow = 0;
		queryChanged = 0;
		if (v.crafting() && !was.crafting()) session.craftOpen(v == StoragePrefs.View.ALL, search.getValue().trim());
		else if (v.crafting()) session.craftOption("all", v == StoragePrefs.View.ALL);
		else session.craftClose();
		hint();
		layout();
	}

	// ------------------------------------------------------------------ search and JEI sync

	private static StoragePrefs.SearchLink jei() {
		return StoragePrefs.jeiSync() ? StoragePrefs.searchLink() : null;
	}

	private static String jeiText(StoragePrefs.SearchLink jei) {
		try {
			String s = jei.get();
			return s == null ? "" : s;
		} catch (RuntimeException e) {
			return "";
		}
	}

	private void setFromJei(String text) {
		fromJei = true;
		try {
			search.setValue(text.length() > 50 ? text.substring(0, 50) : text);
		} finally {
			fromJei = false;
		}
	}

	/**
	 * What the box holds as the terminal opens ({@link StoragePrefs.OnOpen}): JEI's search, nothing, or the last search
	 * typed here. With JEI sync on the two stay in step from then on, so a cleared box clears JEI's search too.
	 */
	private void openSearch() {
		StoragePrefs.SearchLink link = StoragePrefs.searchLink();
		switch (StoragePrefs.onOpen()) {
			case JEI -> {
				String j = link == null ? "" : jeiText(link);
				if (!j.isEmpty()) setFromJei(j);
			}
			case KEEP -> {
				String last = StoragePrefs.lastSearch();
				if (!last.isEmpty()) search.setValue(last.length() > 50 ? last.substring(0, 50) : last);
			}
			case CLEAR -> {
				StoragePrefs.lastSearch("");
				StoragePrefs.SearchLink jei = jei();
				if (jei != null && !jeiText(jei).isEmpty()) {
					try {
						jei.set("");
					} catch (RuntimeException ignored) {
					}
				}
			}
		}
		StoragePrefs.SearchLink jei = jei();
		if (jei != null) lastJei = jeiText(jei);
	}

	/** Gives the search box the keyboard when the terminal opens, if the player asked for that; call from init(). */
	public void focusOnOpen(Screen screen) {
		if (!StoragePrefs.focusOnOpen()) return;
		screen.setFocused(search);
		search.setFocused(true);
	}

	private void searchChanged(String text) {
		StoragePrefs.lastSearch(text);
		scrollRow = 0;
		if (crafting()) queryChanged = System.currentTimeMillis();
		StoragePrefs.SearchLink jei = jei();
		if (jei != null && !fromJei) {
			try {
				jei.set(text);
				lastJei = text;
			} catch (RuntimeException ignored) {
			}
		}
	}

	/** Each frame: JEI's search into the box (both ways stay in step), and the delayed recipe search. */
	private void syncSearch() {
		StoragePrefs.SearchLink jei = jei();
		if (jei != null) {
			String j = jeiText(jei);
			if (!j.equals(lastJei)) {
				lastJei = j;
				if (!j.equals(search.getValue())) setFromJei(j);
			}
		}
		if (crafting() && queryChanged > 0 && System.currentTimeMillis() - queryChanged >= QUERY_DELAY) {
			queryChanged = 0;
			session.craftOption("q", search.getValue().trim());
		}
	}

	private void toggleJei() {
		boolean on = !StoragePrefs.jeiSync();
		StoragePrefs.jeiSync(on);
		StoragePrefs.SearchLink jei = jei();
		if (on && jei != null) {
			// Turning it on: a search typed here goes to JEI, else JEI's comes here.
			String mine = search.getValue();
			if (!mine.isEmpty()) {
				try {
					jei.set(mine);
				} catch (RuntimeException ignored) {
				}
				lastJei = mine;
			} else {
				lastJei = jeiText(jei);
				if (!lastJei.isEmpty()) setFromJei(lastJei);
			}
		}
	}

	// ------------------------------------------------------------------ items

	/** By name, then (the same name) by key, so equal names keep their order from one update to the next. */
	private static final Comparator<StorageSession.Entry> BY_NAME = Comparator.comparing(StorageSession.Entry::name)
		.thenComparingInt(StorageSession.Entry::key);
	private static final Comparator<StorageSession.Entry> BY_COUNT = Comparator.comparingLong((StorageSession.Entry e) -> -e.total())
		.thenComparing(BY_NAME);

	private List<StorageSession.Entry> entries() {
		String q = search.getValue().trim().toLowerCase(Locale.ROOT);
		if (sortedVersion == session.version && q.equals(sortedQuery) && sortedByName == byName) return sorted;
		List<StorageSession.Entry> list = new ArrayList<>();
		boolean mod = q.startsWith("@");
		String ns = mod ? q.substring(1) : null;
		for (StorageSession.Entry e : session.items.values()) {
			if (q.isEmpty() || (mod ? e.ns().contains(ns) : matches(e, q))) list.add(e);
		}
		list.sort(byName ? BY_NAME : BY_COUNT);
		sorted = list;
		sortedVersion = session.version;
		sortedQuery = q;
		sortedByName = byName;
		return list;
	}

	/** The search: the shown name, or the id's path ({@code oak_planks} or {@code oak planks}); {@code @mod} is handled by the caller. */
	private static boolean matches(StorageSession.Entry e, String q) {
		return e.name().contains(q) || e.path().contains(q) || e.pathSpaced().contains(q);
	}

	private List<StorageSession.Recipe> recipes() {
		return session.recipes == null ? List.of() : session.recipes;
	}

	private int size() {
		return crafting() ? recipes().size() : entries().size();
	}

	private int totalRows() {
		return (size() + cols - 1) / cols;
	}

	private int maxScroll() {
		return Math.max(0, totalRows() - rows);
	}

	// ------------------------------------------------------------------ drawing

	public void render(GuiGraphicsExtractor g, int mx, int my) {
		// JEI's runtime can come up after the screen opened: the J toggle then takes room from the search box.
		layout();
		syncSearch();
		if (ownBackground) Theme.blit(g, Theme.STORAGE_PANEL, x, y, width(), height());
		hovered = null;
		hoveredRecipe = null;
		hoverTip = null;
		int[] t = tabs();
		int titleW = t == null ? cols * CELL : t[0] - (x + SIDE) - 4;
		g.text(font, Theme.ellipsize(font, session.title.getString(), Math.max(0, titleW)), x + SIDE + 1, y + 6, Theme.LABEL, false);
		if (t != null) renderTabs(g, mx, my, t);

		renderButtons(g, mx, my);

		scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
		int gx = gridX(), gy = gridY();
		boolean craft = crafting();
		List<StorageSession.Entry> list = craft ? List.of() : entries();
		List<StorageSession.Recipe> rlist = craft ? recipes() : List.of();
		for (int r = 0; r < rows; r++) Theme.slotRow(g, gx, gy + r * CELL, cols);
		if (optionsOpen) {
			renderOptions(g, mx, my);
			renderScrollAndFooter(g, list, rlist);
			return;
		}
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				int cx = gx + c * CELL, cy = gy + r * CELL;
				int i = (scrollRow + r) * cols + c;
				boolean hov = Theme.inside(mx, my, cx, cy, CELL, CELL);
				if (hov) Theme.blit(g, Theme.SLOT_HIGHLIGHT_BACK, cx - 3, cy - 3, 24, 24);
				if (!craft && i < list.size()) {
					StorageSession.Entry e = list.get(i);
					g.item(e.item(), cx + 1, cy + 1);
					g.itemDecorations(font, e.item(), cx + 1, cy + 1, e.total() > 1 ? compact(e.total()) : "");
					if (hov) hovered = e;
				} else if (craft && i < rlist.size() && rlist.get(i) != null) {
					StorageSession.Recipe rec = rlist.get(i);
					g.item(rec.item(), cx + 1, cy + 1);
					if (rec.craftable()) {
						// How many it makes right now (crafts × what one craft makes); "+" at the right-click cap.
						long items = (long) rec.max() * Math.max(1, rec.item().getCount());
						String n = compact(items) + (rec.max() >= session.craftCap ? "+" : "");
						g.itemDecorations(font, rec.item(), cx + 1, cy + 1, items > 0 ? n : "");
						// Auto-Craft makes some ingredients first: a small gold corner.
						if ("auto".equals(rec.state())) g.fill(cx + 1, cy + 1, cx + 4, cy + 2, 0xFFFFD24A);
						if ("auto".equals(rec.state())) g.fill(cx + 1, cy + 2, cx + 2, cy + 4, 0xFFFFD24A);
					} else {
						// Can't be made yet: dimmed.
						g.fill(cx + 1, cy + 1, cx + 17, cy + 17, 0xA08B8B8B);
					}
					if (hov) hoveredRecipe = rec;
				}
				if (hov) Theme.blit(g, Theme.SLOT_HIGHLIGHT_FRONT, cx - 3, cy - 3, 24, 24);
			}
		}
		// Nothing to show: say why instead of a blank grid (cut to the grid's width).
		Component empty = null;
		boolean searching = !search.getValue().isBlank();
		if (craft) {
			if (session.recipes != null && rlist.isEmpty()) empty = Component.translatable(searching ? "aftrhrs.storage.craft-none-found" : "aftrhrs.storage.craft-none");
		} else if (list.isEmpty()) {
			if (searching && !session.items.isEmpty()) empty = Component.translatable("aftrhrs.storage.none-found");
			else if (session.info.has("containers") && session.info.get("containers").getAsInt() == 0) empty = Component.translatable("aftrhrs.storage.no-storage");
			else empty = Component.translatable("aftrhrs.storage.empty");
		}
		if (empty != null) {
			FormattedCharSequence line = Theme.firstLine(font, empty, cols * CELL - 4);
			g.text(font, line, gx + (cols * CELL - font.width(line)) / 2, gy + rows * CELL / 2 - 4, 0xFFFFFFFF, true);
		}
		renderScrollAndFooter(g, list, rlist);
	}

	private void renderScrollAndFooter(GuiGraphicsExtractor g, List<StorageSession.Entry> list, List<StorageSession.Recipe> rlist) {
		int gx = gridX(), gy = gridY();
		boolean craft = crafting();
		// Creative-style scroller in a sunken track.
		int tx = gx + cols * CELL + 4, h = rows * CELL;
		Theme.blit(g, Theme.STORAGE_WELL, tx, gy, SCROLL_W, h);
		int max = optionsOpen ? 0 : maxScroll();
		int thumbY = max == 0 ? gy + 1 : gy + 1 + (h - 17) * scrollRow / max;
		Theme.blit(g, max == 0 ? Theme.SCROLLER_DISABLED : Theme.SCROLLER, tx + 1, thumbY, 12, 15);

		// Footer: what's shown, and how full the chests are (or how much can be made).
		int fy = gy + h + 4;
		if (craft) {
			Component foot;
			if (session.recipes == null) foot = Component.translatable("aftrhrs.storage.craft-loading");
			else if (view == StoragePrefs.View.ALL) foot = Component.translatable("aftrhrs.storage.craft-footer-all", session.craftReady, listed(rlist));
			else foot = Component.translatable("aftrhrs.storage.craft-footer", session.craftReady);
			g.text(font, Theme.firstLine(font, foot, cols * CELL), gx + 1, fy, Theme.LABEL_SOFT, false);
		} else if (!session.lastFill.isEmpty()) {
			g.text(font, Component.translatable("aftrhrs.storage.missing"), gx + 1, fy, Theme.BAD_ON_LIGHT, false);
		} else if (session.info.has("used") && session.info.has("slots")) {
			String kinds = list.size() == session.items.size() ? String.valueOf(list.size()) : list.size() + "/" + session.items.size();
			g.text(font, Component.translatable("aftrhrs.storage.footer", kinds, session.info.get("used").getAsInt(), session.info.get("slots").getAsInt()),
				gx + 1, fy, Theme.LABEL_SOFT, false);
		}
	}

	private static int listed(List<StorageSession.Recipe> list) {
		int n = 0;
		for (StorageSession.Recipe r : list) if (r != null) n++;
		return n;
	}

	private void renderButtons(GuiGraphicsExtractor g, int mx, int my) {
		int sy = headY();
		if (jeiShown()) {
			int jx = jeiX();
			boolean on = StoragePrefs.jeiSync();
			boolean jh = Theme.inside(mx, my, jx, sy, JEI_W, 16);
			Theme.vanillaButton(g, jx, sy, JEI_W, 16, jh);
			g.centeredText(font, "J", jx + JEI_W / 2, sy + 4, on ? 0xFF55FF55 : 0xFF8B8B8B);
			if (jh) {
				hoverTip = Component.translatable(on ? "aftrhrs.storage.jei-on" : "aftrhrs.storage.jei-off");
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}
		int ox = optX();
		boolean oh = Theme.inside(mx, my, ox, sy, OPT_W, 16);
		Theme.vanillaButton(g, ox, sy, OPT_W, 16, oh);
		g.centeredText(font, "\u2699", ox + OPT_W / 2, sy + 4, optionsOpen ? 0xFFFFFF55 : oh ? 0xFFFFFFA0 : 0xFFE0E0E0);
		if (oh) {
			hoverTip = Component.translatable(optionsOpen ? "aftrhrs.storage.options-close" : "aftrhrs.storage.options");
			g.requestCursor(CursorTypes.POINTING_HAND);
		}
		List<Btn> list = buttons();
		for (int i = 0; i < list.size(); i++) {
			Btn b = list.get(i);
			int bx = buttonX(i, list.size());
			boolean hov = Theme.inside(mx, my, bx, sy, BTN, 16);
			Theme.vanillaButton(g, bx, sy, BTN, 16, hov);
			ItemStack icon = switch (b) {
				case SORT -> new ItemStack(byName ? Items.NAME_TAG : Items.CHEST);
				case SHOW -> new ItemStack(view == StoragePrefs.View.ALL ? Items.BOOKSHELF : Items.BOOK);
				case AUTO -> new ItemStack(session.craftAuto ? Items.CRAFTER : Items.DROPPER);
				case AMOUNT -> new ItemStack(Items.REDSTONE);
				case DEPOSIT -> new ItemStack(Items.HOPPER);
			};
			g.item(icon, bx + 1, sy);
			if (b == Btn.AMOUNT) g.itemDecorations(font, icon, bx + 1, sy, "×" + session.craftAmount);
			if (hov) {
				hoverTip = switch (b) {
					case SORT -> Component.translatable(byName ? "aftrhrs.storage.sort-name" : "aftrhrs.storage.sort-count");
					case SHOW -> Component.translatable(view == StoragePrefs.View.ALL ? "aftrhrs.storage.show-all" : "aftrhrs.storage.show-craftable");
					case AUTO -> session.craftAuto ? Component.translatable("aftrhrs.storage.auto-on", session.craftDepth) : Component.translatable("aftrhrs.storage.auto-off");
					case AMOUNT -> Component.translatable("aftrhrs.storage.amount", session.craftAmount);
					case DEPOSIT -> Component.translatable("aftrhrs.storage.deposit-all");
				};
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}
	}

	// ------------------------------------------------------------------ search options (in the grid's place)

	private enum Opt {ON_OPEN, FOCUS, JEI_SYNC}

	private List<Opt> options() {
		return jeiShown() ? List.of(Opt.ON_OPEN, Opt.FOCUS, Opt.JEI_SYNC) : List.of(Opt.ON_OPEN, Opt.FOCUS);
	}

	/** Each option row's height: up to 16, less when a short grid has to fit them all. */
	private int optionH(int n) {
		return Math.max(10, Math.min(16, (rows * CELL - 2) / n - 2));
	}

	private int optionY(int i, int n) {
		return gridY() + 1 + i * (optionH(n) + 2);
	}

	/** The open mode in effect: "JEI's search" without JEI opens empty. */
	private StoragePrefs.OnOpen onOpenShown() {
		StoragePrefs.OnOpen m = StoragePrefs.onOpen();
		return m == StoragePrefs.OnOpen.JEI && !jeiShown() ? StoragePrefs.OnOpen.CLEAR : m;
	}

	private Component optionLabel(Opt o) {
		return switch (o) {
			case ON_OPEN -> Component.translatable("aftrhrs.storage.opt-open",
				Component.translatable("aftrhrs.storage.opt-open-" + onOpenShown().name().toLowerCase(Locale.ROOT)));
			case FOCUS -> Component.translatable("aftrhrs.storage.opt-focus", onOff(StoragePrefs.focusOnOpen()));
			case JEI_SYNC -> Component.translatable("aftrhrs.storage.opt-jei", onOff(StoragePrefs.jeiSync()));
		};
	}

	private Component optionTip(Opt o) {
		return switch (o) {
			case ON_OPEN -> Component.translatable("aftrhrs.storage.opt-open-tip-" + onOpenShown().name().toLowerCase(Locale.ROOT));
			case FOCUS -> Component.translatable("aftrhrs.storage.opt-focus-tip");
			case JEI_SYNC -> Component.translatable(StoragePrefs.jeiSync() ? "aftrhrs.storage.jei-on" : "aftrhrs.storage.jei-off");
		};
	}

	private static Component onOff(boolean on) {
		return Component.translatable(on ? "aftrhrs.storage.opt-on" : "aftrhrs.storage.opt-off");
	}

	private void renderOptions(GuiGraphicsExtractor g, int mx, int my) {
		List<Opt> list = options();
		int n = list.size(), oh = optionH(n), w = cols * CELL - 2, ox = gridX() + 1;
		for (int i = 0; i < n; i++) {
			Opt o = list.get(i);
			int oy = optionY(i, n);
			boolean hov = Theme.inside(mx, my, ox, oy, w, oh);
			Theme.vanillaButton(g, ox, oy, w, oh, hov);
			FormattedCharSequence label = Theme.ellipsize(font, optionLabel(o), w - 8);
			g.text(font, label, ox + 4, oy + (oh - 8) / 2, hov ? 0xFFFFFFA0 : 0xFFFFFFFF, true);
			if (hov) {
				hoverTip = optionTip(o);
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}
	}

	/** A click on an option row: change it; true when one was hit. {@code right} steps the open mode backwards. */
	private boolean clickOption(double mx, double my, boolean right) {
		List<Opt> list = options();
		int n = list.size(), oh = optionH(n), w = cols * CELL - 2, ox = gridX() + 1;
		for (int i = 0; i < n; i++) {
			if (!Theme.inside(mx, my, ox, optionY(i, n), w, oh)) continue;
			switch (list.get(i)) {
				case ON_OPEN -> {
					boolean jei = jeiShown();
					StoragePrefs.OnOpen m = onOpenShown();
					StoragePrefs.onOpen(right ? m.previous(jei) : m.next(jei));
				}
				case FOCUS -> StoragePrefs.focusOnOpen(!StoragePrefs.focusOnOpen());
				case JEI_SYNC -> toggleJei();
			}
			return true;
		}
		return false;
	}

	private int gridX() {
		return x + SIDE;
	}

	private int gridY() {
		return y + HEAD;
	}

	/** Item or recipe tooltip (a recipe's adds what Quick Craft knows about it); call after everything else is drawn. */
	public void tooltip(GuiGraphicsExtractor g, int mx, int my) {
		if (hoverTip != null) {
			List<FormattedCharSequence> lines = font.split(hoverTip, 200);
			g.setTooltipForNextFrame(font, lines, mx, my);
			return;
		}
		if (hoveredRecipe != null) {
			List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), hoveredRecipe.item()));
			List<Component> extra = session.craftInfo(hoveredRecipe.id());
			if (extra == null) lines.add(Component.translatable("aftrhrs.storage.craft-wait").withStyle(ChatFormatting.DARK_GRAY));
			else lines.addAll(extra);
			g.setComponentTooltipForNextFrame(font, lines, mx, my);
			return;
		}
		if (hovered == null) return;
		List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), hovered.item()));
		lines.add(Component.translatable("aftrhrs.storage.total", String.format(Locale.ROOT, "%,d", hovered.total())).withStyle(ChatFormatting.GRAY));
		logLines(hovered.log(), lines);
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
	}

	/** "Last modified by Alice · Nov 21, 3:04 AM EST" (this computer's time zone), then the last few changes. */
	static void logLines(List<StorageSession.Change> log, List<Component> lines) {
		if (log == null || log.isEmpty()) return;
		long now = System.currentTimeMillis();
		StorageSession.Change last = log.getFirst();
		String when = net.axther.aftrhrs.feature.storage.StorageTime.when(last.at(), java.time.ZoneId.systemDefault());
		lines.add(Component.translatable("aftrhrs.storage.log-head", last.who(), when).withStyle(ChatFormatting.GRAY));
		for (StorageSession.Change c : log) {
			lines.add(Component.translatable("aftrhrs.storage.log-line", net.axther.aftrhrs.feature.storage.StorageTime.signed(c.delta()), c.who(),
				net.axther.aftrhrs.feature.storage.StorageTime.ago(c.at(), now)).withStyle(c.delta() >= 0 ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
		}
	}

	// ------------------------------------------------------------------ clicks

	public boolean contains(double mx, double my) {
		return Theme.inside(mx, my, x, y, width(), height());
	}

	private int indexAt(double mx, double my) {
		if (optionsOpen) return -1;
		int gx = gridX(), gy = gridY();
		if (mx < gx || my < gy || mx >= gx + cols * CELL || my >= gy + rows * CELL) return -1;
		int c = (int) (mx - gx) / CELL, r = (int) (my - gy) / CELL;
		return (scrollRow + r) * cols + c;
	}

	/** The network entry under the mouse (Items view), or null. */
	public StorageSession.Entry entryAt(double mx, double my) {
		if (crafting()) return null;
		int i = indexAt(mx, my);
		List<StorageSession.Entry> list = entries();
		return i >= 0 && i < list.size() ? list.get(i) : null;
	}

	/** The recipe under the mouse (Craftable view), or null. */
	public StorageSession.Recipe recipeAt(double mx, double my) {
		if (!crafting()) return null;
		int i = indexAt(mx, my);
		List<StorageSession.Recipe> list = recipes();
		return i >= 0 && i < list.size() ? list.get(i) : null;
	}

	/** What the cell under the mouse shows: a network item or a recipe's result (for JEI's R / U), or empty. */
	public ItemStack stackAt(double mx, double my) {
		StorageSession.Entry e = entryAt(mx, my);
		if (e != null) return e.item();
		StorageSession.Recipe r = recipeAt(mx, my);
		return r == null ? ItemStack.EMPTY : r.item();
	}

	/** The top-left corner of the grid cell under the mouse, or null. */
	public int[] cellAt(double mx, double my) {
		if (optionsOpen) return null;
		int gx = gridX(), gy = gridY();
		if (mx < gx || my < gy || mx >= gx + cols * CELL || my >= gy + rows * CELL) return null;
		return new int[]{gx + (int) (mx - gx) / CELL * CELL + 1, gy + (int) (my - gy) / CELL * CELL + 1};
	}

	/**
	 * A click on the tabs, the header buttons (not Deposit: see {@link #clickDeposit}) or, while they show, the search
	 * options; true when one was hit.
	 * {@code right}: a right-click (the amount goes down instead of up).
	 */
	public boolean clickHeader(double mx, double my, boolean right) {
		int[] t = tabs();
		if (t != null) {
			for (int k = 0; k < 2; k++) {
				if (Theme.inside(mx, my, t[k * 2], y + 2, t[k * 2 + 1], 13)) {
					setView(k == 1 ? (session.craftAll ? StoragePrefs.View.ALL : StoragePrefs.View.CRAFTABLE) : StoragePrefs.View.ITEMS);
					return true;
				}
			}
		}
		int sy = headY();
		if (jeiShown() && Theme.inside(mx, my, jeiX(), sy, JEI_W, 16)) {
			toggleJei();
			return true;
		}
		if (Theme.inside(mx, my, optX(), sy, OPT_W, 16)) {
			optionsOpen = !optionsOpen;
			return true;
		}
		if (optionsOpen && clickOption(mx, my, right)) return true;
		List<Btn> list = buttons();
		for (int i = 0; i < list.size(); i++) {
			if (!Theme.inside(mx, my, buttonX(i, list.size()), sy, BTN, 16)) continue;
			switch (list.get(i)) {
				case SORT -> byName = !byName;
				case SHOW -> setView(view == StoragePrefs.View.ALL ? StoragePrefs.View.CRAFTABLE : StoragePrefs.View.ALL);
				case AUTO -> {
					session.craftAuto = !session.craftAuto;
					session.craftOption("auto", session.craftAuto);
				}
				case AMOUNT -> {
					int[] a = session.craftAmounts;
					int k = 0;
					for (int j = 0; j < a.length; j++) if (a[j] == session.craftAmount) k = j;
					session.craftAmount = a[(k + (right ? a.length - 1 : 1)) % a.length];
					session.craftOption("amount", session.craftAmount);
				}
				case DEPOSIT -> {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	public boolean clickDeposit(double mx, double my) {
		if (!depositButton) return false;
		List<Btn> list = buttons();
		return Theme.inside(mx, my, buttonX(list.size() - 1, list.size()), headY(), BTN, 16);
	}

	public boolean clickSearch(double mx, double my) {
		return Theme.inside(mx, my, search.getX(), search.getY(), search.getWidth(), search.getHeight());
	}

	/** A press on the scroll track starts dragging the scroller. */
	public boolean clickScroll(double mx, double my) {
		if (optionsOpen) return false;
		int tx = gridX() + cols * CELL + 4;
		if (!Theme.inside(mx, my, tx, gridY(), SCROLL_W, rows * CELL)) return false;
		draggingScroll = true;
		dragTo(my);
		return true;
	}

	public boolean drag(double my) {
		if (!draggingScroll) return false;
		dragTo(my);
		return true;
	}

	public void release() {
		draggingScroll = false;
	}

	private void dragTo(double my) {
		int max = maxScroll();
		if (max == 0) return;
		double t = (my - gridY() - 7.5) / (rows * CELL - 15.0);
		scrollRow = Mth.clamp((int) Math.round(t * max), 0, max);
	}

	public void scroll(double amount) {
		scrollRow = Mth.clamp(scrollRow - (int) Math.signum(amount), 0, maxScroll());
	}

	static String compact(long n) {
		if (n < 1000) return String.valueOf(n);
		if (n < 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0).replace(".0k", "k");
		if (n < 1_000_000) return (n / 1000) + "k";
		return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
	}
}
