package net.axther.aftrhrs.client.codex;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.entity.CreatureView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The creature codex (the unified codex and taming guide): every creature the mod has, in tabs (Companions, Dogs &amp;
 * Cats, Mounts, Wild Ones, Wildlife, Monsters, Bosses), a realm filter (Overworld, Ocean, Nether, End) and a search box.
 * Each card is the creature's own animated model (a dark silhouette until seen; a tameable creature stays gray until
 * tamed). Picking one opens its page: the model on a stage (drag to turn it, its young and other forms and its color
 * variants to try on), then how to tame it, or for wild creatures what it is, where it lives and how dangerous it is,
 * its stats and drops, and for bosses the altar and the fight.
 *
 * <p>Everything comes from Feather's {@code codex} snapshot ({@link CodexData}); the screen asks for nothing else.
 */
public final class CodexScreen extends Screen {
	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath(Companion.ID, "pets/" + name);
	}

	static final Identifier PANEL = sprite("panel"), CARD = sprite("card"), CARD_HOVER = sprite("card_hover"), CARD_SELECTED = sprite("card_selected"),
		BUTTON = sprite("button"), BUTTON_HOVER = sprite("button_hover"), BUTTON_MINT = sprite("button_mint"), BUTTON_MINT_HOVER = sprite("button_mint_hover"),
		TAB = sprite("tab"), TAB_ON = sprite("tab_selected"), STAGE = sprite("stage"), PEDESTAL = sprite("pedestal"), INSET = sprite("inset"),
		HEART = sprite("heart_full"), STAR = sprite("star"), PAW = sprite("paw"), LOCK = sprite("lock");
	static final int GRAY = 0xFF8A8090, SILHOUETTE = 0xFF2A2230;
	static final int INK = 0xFF6B4A57, PINK = 0xFFD9668F, SOFT = 0xFF9C8189, MINT = 0xFF3E9E7E, WHITE = 0xFFFFFFFF, RED = 0xFFD9534F,
		GOLD = 0xFFC9A227, VIOLET = 0xFF8E5FD0, ORANGE = 0xFFE08A3C;
	private static final int SIDE_W = 116, DETAIL_W = 184;

	private int px, py, pw, ph;
	/** The tab (a category id; null = all), the realm (null = everywhere) and the selected creature. */
	private String category, realm, selected;
	/** The form whose model the stage shows (null = the creature itself) and a variant tint being tried on (-1 none). */
	private String form;
	private int tryTint = -1;
	private float gridScroll, detailScroll, detailMax;
	/** The sidebar's scroll (only when even its compact layout doesn't fit) and how far it goes. */
	private float sideScroll;
	private int sideMax;
	/** A scroll bar being dragged: 0 none, 1 the grid's, 2 the page's. */
	private int dragBar;
	/** The bars as last drawn (x, y, height, content, view), for clicking and dragging them. */
	private final int[] gridBar = new int[5], detailBar = new int[5];
	private float yaw = CreatureView.DEFAULT_YAW;
	private boolean dragging;
	private double dragX;
	private long lastSpin = Util.getMillis();
	private final long opened = Util.getMillis();
	private EditBox search;
	private final List<Hit> hits = new ArrayList<>();
	private List<FormattedCharSequence> tip;
	/** Where Escape goes back to (the pet page "View in Codex" came from), or null to close. */
	private final Screen parent;

	// Kept between frames: rebuilt for a new snapshot (CodexData.version) or by init() (resize, GUI scale, language).
	/** The sidebar's rows, realm chips, layout and counts for {@link #sideVersion} (-1: rebuild). */
	private long sideVersion = -1;
	private SideRow sideAll;
	private final List<SideRow> sideCats = new ArrayList<>();
	private final List<CodexData.Realm> sideRealms = new ArrayList<>();
	private final List<Chip> sideChips = new ArrayList<>();
	private Chip sideAllChip;
	private String sideRealmHeader;
	private boolean sideCompact;
	/** The header's counts line and its width for {@link #countsVersion}. */
	private long countsVersion = -1;
	private String countsText;
	private int countsW;
	/** {@link #shown()} for the snapshot, tab, realm and search it was worked out for. */
	private long shownVersion = -1;
	private String shownCategory, shownRealm, shownQuery;
	private List<CodexData.Entry> shownList = List.of();
	/** The grid's labels by species, fitted to {@link #cellLabelW} for {@link #cellLabelVersion}. */
	private final Map<String, String> cellLabel = new HashMap<>();
	private int cellLabelW = -1;
	private long cellLabelVersion = -1;

	/** A tab row: icon, the "seen/total" text and its width, complete, its label whole and fitted to the row (cut: with "…"). */
	private record SideRow(ItemStack icon, String num, int numW, boolean complete, Component label, String plain, String text, boolean cut) {
	}

	/** A realm chip: its label whole and fitted to a full row and to half a row (the compact layout). */
	private record Chip(Component label, String plain, String full, boolean fullCut, String half, boolean halfCut) {
	}

	/** A clickable area this frame. */
	private record Hit(int x, int y, int w, int h, Runnable run) {
	}

	public CodexScreen(String category, String species) {
		this(category, species, null);
	}

	/** {@code parent}: the screen Escape returns to (null: Escape closes, as from the key or the hub). */
	public CodexScreen(String category, String species, Screen parent) {
		super(Component.translatable("aftrhrs.codex.title"));
		this.parent = parent;
		this.category = blank(category);
		this.selected = blank(species);
		if (this.category != null && isRealm(this.category)) {
			realm = this.category;
			this.category = null;
		}
		if (selected != null && this.category == null && realm == null) {
			CodexData.Entry e = find(selected);
			if (e != null) this.category = e.category();
		}
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	private static boolean isRealm(String id) {
		for (CodexData.Realm r : CodexData.realms) if (r.id().equals(id)) return true;
		return false;
	}

	/** Opens the codex (asks Feather for a fresh snapshot, which opens this screen). */
	public static void open(String category, String species) {
		CodexData.request(category, species);
	}

	@Override
	protected void init() {
		pw = Math.min(width - 12, 492);
		ph = Math.min(height - 16, 276);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		sideScroll = 0; // a resize changes the panel's height, and with it the sidebar's layout
		sideVersion = countsVersion = shownVersion = cellLabelVersion = -1;
		cellLabel.clear();
		cellLabelW = -1;
		String was = search == null ? "" : search.getValue();
		search = new EditBox(font, px + SIDE_W + 14, py + 24, 140, 14, Component.translatable("aftrhrs.codex.search"));
		search.setHint(Component.translatable("aftrhrs.codex.search").withColor(SOFT));
		search.setMaxLength(32);
		search.setValue(was);
		search.setResponder(s -> gridScroll = 0);
		addRenderableWidget(search);
	}

	/** A fresh snapshot came in (Feather answered, or something opened the codex on a tab / creature). */
	void dataChanged(String category, String species) {
		String c = blank(category), s = blank(species);
		if (c != null) {
			if (isRealm(c)) {
				realm = c;
				this.category = null;
			} else {
				this.category = c;
			}
			gridScroll = 0;
		}
		if (s != null) {
			select(s, false);
			// opened on a creature the current filters hide: show its own tab
			CodexData.Entry e = find(s);
			if (e != null && !shown().contains(e)) {
				category = e.category();
				realm = null;
				search.setValue("");
				gridScroll = 0;
			}
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	// ------------------------------------------------------------------ data

	private static CodexData.Entry find(String species) {
		for (CodexData.Entry e : CodexData.entries) if (e.species().equals(species)) return e;
		return null;
	}

	/** The entries on the grid (tab, realm, search); worked out again only when one of them or the snapshot changes. */
	private List<CodexData.Entry> shown() {
		String q = search == null ? "" : search.getValue().trim();
		if (shownVersion == CodexData.version && Objects.equals(shownCategory, category) && Objects.equals(shownRealm, realm) && q.equals(shownQuery)) {
			return shownList;
		}
		List<CodexData.Entry> out = new ArrayList<>();
		for (CodexData.Entry e : CodexData.entries) {
			if (category != null && !category.equals(e.category())) continue;
			if (realm != null && !e.realms().contains(realm)) continue;
			if (!e.matches(q)) continue;
			out.add(e);
		}
		shownVersion = CodexData.version;
		shownCategory = category;
		shownRealm = realm;
		shownQuery = q;
		shownList = List.copyOf(out);
		return shownList;
	}

	private void select(String species, boolean sound) {
		boolean same = species != null && species.equals(selected);
		selected = same && sound ? null : species;
		form = null;
		tryTint = -1;
		detailScroll = 0;
		yaw = CreatureView.DEFAULT_YAW;
		if (sound) chime(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		hits.clear();
		tip = null;
		long now = Util.getMillis();
		if (!dragging) yaw += (now - lastSpin) * 0.012f;
		lastSpin = now;
		blit(g, PANEL, px, py, pw, ph);
		// header
		g.text(font, Component.translatable("aftrhrs.codex.title").withStyle(ChatFormatting.BOLD), px + 12, py + 9, PINK, false);
		if (countsVersion != CodexData.version) {
			countsVersion = CodexData.version;
			countsText = Component.translatable("aftrhrs.codex.counts", CodexData.seen, CodexData.total, CodexData.tamed, CodexData.tameable).getString();
			countsW = font.width(countsText);
		}
		int cw = countsW;
		g.text(font, countsText, px + pw - 12 - cw, py + 9, SOFT, false);
		if (!CodexData.nextMilestone.isEmpty() && Theme.inside(mx, my, px + pw - 12 - cw, py + 7, cw, 11)) tip = font.split(Component.literal(CodexData.nextMilestone), 220);
		drawSidebar(g, mx, my);
		CodexData.Entry sel = selected == null ? null : find(selected);
		int gridX = px + SIDE_W + 14, gridR = sel == null ? px + pw - 10 : px + pw - DETAIL_W - 14;
		search.setX(gridX);
		search.setWidth(Math.max(60, Math.min(150, gridR - gridX)));
		drawBestiaryLine(g, mx, my, gridX + search.getWidth() + 6, gridR);
		drawGrid(g, mx, my, now, gridX, py + 42, gridR, py + ph - 8, sel);
		if (sel != null) drawDetail(g, mx, my, now, sel, px + pw - DETAIL_W - 8, py + 22, DETAIL_W, ph - 30);
		super.extractRenderState(g, mx, my, a);
		if (tip != null) g.setTooltipForNextFrame(font, tip, mx, my);
	}

	/**
	 * Tabs and realm chips down the left. When they don't fit the panel (a short screen) they go compact: 15px rows,
	 * realm chips two to a row; if even that doesn't fit (more tabs or realms in the config, a big GUI scale) the
	 * sidebar scrolls under a clip, and rows outside it can't be clicked.
	 */
	private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
		ensureSide();
		int x = px + 8, w = SIDE_W;
		int top = py + 22, bottom = py + ph - 8;
		boolean anyRealm = !CodexData.realms.isEmpty();
		boolean compact = sideCompact;
		int pitch = compact ? 15 : 17, bh = compact ? 14 : 16;
		sideScroll = Mth.clamp(sideScroll, 0, sideMax);
		boolean clip = sideMax > 0;
		if (clip) g.enableScissor(x - 2, top, x + w + 2, bottom);
		int y = py + 24 - (int) sideScroll;
		sideButton(g, mx, my, x, y, w, bh, top, bottom, sideAll, category == null, () -> {
			category = null;
			gridScroll = 0;
		}, null);
		y += pitch;
		for (int i = 0; i < sideCats.size(); i++) {
			CodexData.Category c = CodexData.categories.get(i);
			String id = c.id();
			sideButton(g, mx, my, x, y, w, bh, top, bottom, sideCats.get(i), id.equals(category), () -> {
				category = id;
				gridScroll = 0;
			}, c.blurb().isEmpty() ? null : c.blurb());
			y += pitch;
		}
		if (anyRealm) {
			y += 4;
			g.text(font, sideRealmHeader, x + 2, y, SOFT, false);
			y += 11;
			// "All" across, then one chip per row, or two to a row when compact (a cut name shows whole in the tip)
			chip(g, mx, my, x, y, w, top, bottom, sideAllChip, false, realm == null, () -> realm = null);
			int half = (w - 2) / 2;
			for (int i = 0; i < sideRealms.size(); i++) {
				String id = sideRealms.get(i).id();
				int cx = x, cw = w;
				if (compact) {
					if (i % 2 == 0) y += 14;
					cx = i % 2 == 0 ? x : x + w - half;
					cw = half;
				} else {
					y += 14;
				}
				chip(g, mx, my, cx, y, cw, top, bottom, sideChips.get(i), compact, id.equals(realm), () -> realm = id.equals(realm) ? null : id);
			}
		}
		if (clip) {
			g.disableScissor();
			// there is more: a thin bar just right of the sidebar
			Theme.scrollbar(g, x + w + 1, top, 2, bottom - top, sideScroll, bottom - top + sideMax, bottom - top, 0, 0xAAD9668F);
		}
	}

	/** Where the sidebar's last row ends (screen y, unscrolled) in the normal or the compact layout. */
	private static int sideEnd(int top, int cats, int realms, boolean compact, boolean anyRealm) {
		int y = top + 2 + (cats + 1) * (compact ? 15 : 17);
		if (!anyRealm) return y - 1;
		int rows = 1 + (compact ? (realms + 1) / 2 : realms);
		return y + 15 + (rows - 1) * 14 + 13;
	}

	/**
	 * Builds the sidebar's model when the snapshot changed or after init(): one pass over the entries counts every tab,
	 * "All" and each realm; each row's count text and its label fitted to the row; the realms with anything in them and
	 * their chips fitted to a full and a half row; the layout (compact or not) and how far it scrolls.
	 */
	private void ensureSide() {
		if (sideVersion == CodexData.version) return;
		sideVersion = CodexData.version;
		int[] all = new int[2];
		Map<String, int[]> byCategory = new HashMap<>(), byRealm = new HashMap<>();
		for (CodexData.Entry e : CodexData.entries) {
			int k = e.known() ? 1 : 0;
			all[0] += k;
			all[1]++;
			int[] c = byCategory.computeIfAbsent(e.category(), id -> new int[2]);
			c[0] += k;
			c[1]++;
			List<String> rs = e.realms();
			for (int i = 0; i < rs.size(); i++) {
				if (rs.indexOf(rs.get(i)) != i) continue; // a realm named twice counts once
				int[] r = byRealm.computeIfAbsent(rs.get(i), id -> new int[2]);
				r[0] += k;
				r[1]++;
			}
		}
		int w = SIDE_W;
		sideAll = sideRow(Component.translatable("aftrhrs.codex.all"), all, ItemStack.EMPTY, w);
		sideCats.clear();
		for (CodexData.Category c : CodexData.categories) {
			sideCats.add(sideRow(Component.literal(c.name()), byCategory.getOrDefault(c.id(), new int[2]), stack(c.item()), w));
		}
		// the realms with anything in them ("All" comes first)
		sideRealms.clear();
		sideChips.clear();
		int half = (w - 2) / 2;
		for (CodexData.Realm r : CodexData.realms) {
			int[] n = byRealm.get(r.id());
			if (n == null || n[1] <= 0) continue;
			sideRealms.add(r);
			sideChips.add(chipText(Component.literal(r.name()), w, half));
		}
		sideAllChip = chipText(Component.translatable("aftrhrs.codex.realm.all"), w, half);
		sideRealmHeader = Component.translatable("aftrhrs.codex.realm").getString();
		int top = py + 22, bottom = py + ph - 8, cats = CodexData.categories.size();
		boolean anyRealm = !CodexData.realms.isEmpty();
		sideCompact = sideEnd(top, cats, sideRealms.size(), false, anyRealm) > bottom;
		sideMax = Math.max(0, sideEnd(top, cats, sideRealms.size(), sideCompact, anyRealm) - bottom);
	}

	private SideRow sideRow(Component label, int[] n, ItemStack icon, int w) {
		String num = n[0] + "/" + n[1];
		int nw = font.width(num);
		int inset = icon.isEmpty() ? 6 : 17;
		String plain = label.getString();
		String text = Theme.ellipsize(font, plain, w - inset - nw - 10);
		return new SideRow(icon, num, nw, n[0] >= n[1] && n[1] > 0, label, plain, text, !text.equals(plain));
	}

	private Chip chipText(Component label, int w, int half) {
		String plain = label.getString();
		String full = Theme.ellipsize(font, plain, w - 4), halfText = Theme.ellipsize(font, plain, half - 4);
		return new Chip(label, plain, full, !full.equals(plain), halfText, !halfText.equals(plain));
	}

	/** {@code top}..{@code bottom}: the sidebar's clip (a row outside it can't be hovered or clicked). */
	private void sideButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, int top, int bottom, SideRow row,
							boolean on, Runnable run, String hover) {
		boolean hov = Theme.inside(mx, my, x, y, w, h) && my >= top && my < bottom;
		blit(g, on ? CARD_SELECTED : hov ? CARD_HOVER : CARD, x, y, w, h);
		// 6px in from each side: clear of the selected card's corner brackets.
		int tx = x + 6, ty = y + (h - 8) / 2;
		if (!row.icon().isEmpty()) {
			g.pose().pushMatrix();
			g.pose().translate(x + 3, y + (h - 12) / 2);
			g.pose().scale(0.75f, 0.75f);
			g.item(row.icon(), 0, 0);
			g.pose().popMatrix();
			tx = x + 17;
		}
		g.text(font, row.text(), tx, ty, on ? PINK : INK, false);
		g.text(font, row.num(), x + w - 6 - row.numW(), ty, row.complete() ? MINT : SOFT, false);
		if (hov) {
			g.requestCursor(CursorTypes.POINTING_HAND);
			if (hover != null) tip = font.split(Component.literal(hover), 200);
			else if (row.cut()) tip = font.split(row.label(), 200);
		}
		clippedHit(x, y, w, h, top, bottom, () -> {
			run.run();
			chime(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
		});
	}

	/** {@code half}: the chip is half a row wide (the compact layout), else a full row. */
	private void chip(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int top, int bottom, Chip c, boolean half, boolean on, Runnable run) {
		boolean hov = Theme.inside(mx, my, x, y, w, 13) && my >= top && my < bottom;
		blit(g, on ? BUTTON_MINT : hov ? BUTTON_HOVER : BUTTON, x, y, w, 13);
		String text = half ? c.half() : c.full();
		// Dark ink on the mint chip has no shadow (it would smear); white on berry keeps it.
		if (on) Theme.centeredFlat(g, font, text, x + w / 2, y + 3, 0xFF1F5A47);
		else g.centeredText(font, text, x + w / 2, y + 3, WHITE);
		if (hov) {
			g.requestCursor(CursorTypes.POINTING_HAND);
			if (half ? c.halfCut() : c.fullCut()) tip = font.split(c.label(), 200);
		}
		clippedHit(x, y, w, 13, top, bottom, () -> {
			run.run();
			gridScroll = 0;
			chime(SoundEvents.UI_BUTTON_CLICK.value(), 1.5f);
		});
	}

	/** A click area cut to the rows {@code top}..{@code bottom} (none at all when the row is outside them). */
	private void clippedHit(int x, int y, int w, int h, int top, int bottom, Runnable run) {
		int y0 = Math.max(y, top), y1 = Math.min(y + h, bottom);
		if (y1 > y0) hits.add(new Hit(x, y0, w, y1 - y0, run));
	}

	/** On the Wildlife tab (or wherever bestiary creatures are), the bestiary's page progress, pages in the tooltip. */
	private void drawBestiaryLine(GuiGraphicsExtractor g, int mx, int my, int x, int right) {
		if (CodexData.bestiary.isEmpty()) return;
		boolean relevant = category == null ? false : shown().stream().anyMatch(CodexData.Entry::inBestiary);
		if (!relevant) return;
		int done = 0;
		for (CodexData.Page p : CodexData.bestiary) if (p.paid()) done++;
		Component line = Component.translatable("aftrhrs.codex.bestiary-pages", done, CodexData.bestiary.size());
		String s = Theme.ellipsize(font, line.getString(), right - x);
		g.text(font, s, x, py + 27, GOLD, false);
		if (Theme.inside(mx, my, x, py + 24, font.width(s), 12)) {
			List<FormattedCharSequence> lines = new ArrayList<>();
			lines.add(Component.translatable("aftrhrs.codex.bestiary-help").withStyle(ChatFormatting.GRAY).getVisualOrderText());
			for (CodexData.Page p : CodexData.bestiary) {
				Component c = p.paid() ? Component.translatable("aftrhrs.codex.bestiary-page-paid", p.name(), p.done(), p.total())
					: Component.translatable("aftrhrs.codex.bestiary-page", p.name(), p.done(), p.total(), p.reward());
				lines.add(c.getVisualOrderText());
			}
			tip = lines;
		}
	}

	private void drawGrid(GuiGraphicsExtractor g, int mx, int my, long now, int x0, int top, int x1, int bottom, CodexData.Entry sel) {
		List<CodexData.Entry> list = shown();
		int gridW = x1 - x0;
		int cols = Math.max(2, gridW / 60), cell = (gridW - (cols - 1) * 4) / cols;
		int rows = (list.size() + cols - 1) / cols;
		float maxScroll = Math.max(0, rows * (cell + 14) - (bottom - top));
		gridScroll = Mth.clamp(gridScroll, 0, maxScroll);
		if (list.isEmpty()) {
			gridBar[3] = 0;
			Theme.centeredFlat(g, font, Component.translatable(CodexData.entries.isEmpty() ? "aftrhrs.codex.loading" : "aftrhrs.codex.empty"), (x0 + x1) / 2, top + 30, SOFT);
			return;
		}
		g.enableScissor(x0 - 2, top, x1 + 2, bottom);
		for (int i = 0; i < list.size(); i++) {
			CodexData.Entry e = list.get(i);
			int cx = x0 + (i % cols) * (cell + 4), cy = top + (i / cols) * (cell + 14) - (int) gridScroll;
			if (cy + cell + 12 < top || cy > bottom) continue;
			boolean hov = Theme.inside(mx, my, cx, cy, cell, cell + 10) && my >= top && my < bottom;
			blit(g, e == sel ? CARD_SELECTED : hov ? CARD_HOVER : CARD, cx, cy, cell, cell + 10);
			int tint = !e.known() ? SILHOUETTE : e.tameable() && !e.tamed() ? GRAY : -1;
			float spin = CreatureView.DEFAULT_YAW + (hov ? (now - opened) * 0.08f : 0);
			model(g, "codex:" + e.species(), e, e.type(), CreatureView.Look.PLAIN, cx + 3, cy + 3, cx + cell - 3, cy + cell - 3, spin, "", now, tint, hov ? 0 : 33);
			Theme.centeredFlat(g, font, cellLabel(e, cell), cx + cell / 2, cy + cell, e.known() ? INK : SOFT);
			if (e.tamed()) blit(g, HEART, cx + cell - 11, cy + 3, 9, 9);
			else if (e.known() && e.boss()) blit(g, STAR, cx + cell - 11, cy + 3, 9, 9);
			if (hov) {
				g.requestCursor(CursorTypes.POINTING_HAND);
				String id = e.species();
				hits.add(new Hit(cx, cy, cell, cell + 10, () -> select(id, true)));
			}
		}
		g.disableScissor();
		bar(g, gridBar, x1 + 2, top, bottom - top, gridScroll, rows * (cell + 14), bottom - top, 0x30D9668F);
	}

	/** A grid cell's label (its name once known, else "???") fitted to the cell, kept per species. */
	private String cellLabel(CodexData.Entry e, int cell) {
		if (cell != cellLabelW || cellLabelVersion != CodexData.version) {
			cellLabel.clear();
			cellLabelW = cell;
			cellLabelVersion = CodexData.version;
		}
		return cellLabel.computeIfAbsent(e.species(), s -> Theme.ellipsize(font, e.known() ? e.name() : "???", cell - 6));
	}

	/** A thin pastel scroll bar in a list's gutter; its geometry is kept for clicking and dragging it. */
	private static void bar(GuiGraphicsExtractor g, int[] geo, int x, int y, int h, float scroll, int content, int view, int track) {
		geo[0] = x;
		geo[1] = y;
		geo[2] = h;
		geo[3] = content;
		geo[4] = view;
		Theme.scrollbar(g, x, y, 3, h, scroll, content, view, track, 0xAAD9668F);
	}

	/** Whether a click lands on a drawn bar (1px of slop either side, clear of the stage beside the grid's; never when the list fits). */
	private static boolean onBar(int[] geo, double mx, double my) {
		return geo[3] > geo[4] && Theme.inside(mx, my, geo[0] - 1, geo[1], 5, geo[2]);
	}

	// ------------------------------------------------------------------ the page

	/** One line of the page: text, a row of item icons, or the forms / swatches rows (drawn and hit-tested by kind). */
	private interface Line {
		int height();

		void draw(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, boolean visible);
	}

	private void drawDetail(GuiGraphicsExtractor g, int mx, int my, long now, CodexData.Entry e, int x, int y, int w, int h) {
		int stageH = Mth.clamp(h / 2 - 10, 70, 112);
		blit(g, STAGE, x, y, w, stageH);
		blit(g, PEDESTAL, x + 18, y + stageH - 26, w - 36, 24);
		CodexData.Form f = form == null ? null : e.forms().stream().filter(o -> o.species().equals(form)).findFirst().orElse(null);
		EntityType<?> type = f != null ? f.type() : e.type();
		int tint = !e.known() ? SILHOUETTE : tryTint != -1 || !e.tameable() || e.tamed() ? -1 : GRAY;
		CreatureView.Look look = tryTint != -1 && e.known() && f == null ? new CreatureView.Look(tryTint & 0xFFFFFF, false, null) : CreatureView.Look.PLAIN;
		model(g, "codexentry:" + (f != null ? f.species() : e.species()), f != null ? null : e, type, look, x + 12, y + 4, x + w - 12, y + stageH - 10, yaw, "", now, tint, 0);
		if (Theme.inside(mx, my, x, y, w, stageH)) {
			g.requestCursor(dragging ? CursorTypes.RESIZE_EW : CursorTypes.POINTING_HAND);
			// the hint as a caption on the pedestal, drawn after the model so it never covers the creature
			if (!dragging) Theme.centeredFlat(g, font, Theme.ellipsize(font, Component.translatable("aftrhrs.codex.drag").getString(), w - 16), x + w / 2, y + stageH - 11, SOFT);
		}
		// name and kind
		int ty = y + stageH + 3;
		String kind = Theme.ellipsize(font, e.kind(), w / 2);
		int kindW = font.width(kind);
		g.text(font, Theme.clip(font, Component.literal(e.known() ? e.name() : "???").withStyle(ChatFormatting.BOLD), w - kindW - 8), x + 2, ty,
			e.known() ? readable(e.color()) : SOFT, false);
		g.text(font, kind, x + w - 2 - kindW, ty, SOFT, false);
		ty += 11;
		// the scrolling part
		List<Line> lines = lines(e);
		int top = ty, bottom = y + h;
		int total = 0;
		for (Line l : lines) total += l.height();
		detailMax = Math.max(0, total - (bottom - top));
		detailScroll = Mth.clamp(detailScroll, 0, detailMax);
		g.enableScissor(x, top, x + w, bottom);
		int ly = top - (int) detailScroll;
		for (Line l : lines) {
			boolean visible = ly + l.height() > top && ly < bottom;
			if (visible) l.draw(g, x + 2, ly, w - 4, Theme.inside(mx, my, x, top, w, bottom - top) ? mx : -1, my, ly >= top && ly + l.height() <= bottom);
			ly += l.height();
		}
		g.disableScissor();
		bar(g, detailBar, x + w - 3, top, bottom - top, detailScroll, total, bottom - top, 0x18D9668F);
	}

	private List<Line> lines(CodexData.Entry e) {
		List<Line> out = new ArrayList<>();
		if (!e.known()) {
			text(out, Component.translatable("aftrhrs.codex.unknown"), SOFT);
			if (!e.habitat().isEmpty()) text(out, Component.translatable("aftrhrs.codex.lives", e.habitat()), INK);
			if (!e.forms().isEmpty()) formsRow(out, e);
			return out;
		}
		// danger, realms, state
		List<String> tags = new ArrayList<>();
		for (String r : e.realms()) for (CodexData.Realm realm : CodexData.realms) if (realm.id().equals(r)) tags.add(realm.name());
		String state = e.tameable() ? Component.translatable(e.tamed() ? "aftrhrs.codex.tamed" : "aftrhrs.codex.not-tamed").getString() : "";
		Component head = Component.literal(e.dangerText().isEmpty() ? "" : e.dangerText()).withColor(dangerColor(e.danger()))
			.append(Component.literal((tags.isEmpty() ? "" : "  · " + String.join(", ", tags)) + (state.isEmpty() ? "" : "  · " + state)).withColor(SOFT));
		text(out, head, SOFT);
		gap(out, 3);
		if (e.tameable()) {
			if (!e.ability().isEmpty()) text(out, Component.translatable("aftrhrs.codex.job", e.ability()), INK);
			if (!e.habitat().isEmpty()) text(out, Component.translatable("aftrhrs.codex.lives", e.habitat()), INK);
			if (!e.tame().isEmpty()) {
				text(out, e.odds().isEmpty() ? Component.translatable("aftrhrs.codex.tame", e.tame())
					: Component.translatable("aftrhrs.codex.tame-odds", e.tame(), e.odds()), MINT);
			}
			text(out, e.heal().isEmpty() ? Component.translatable("aftrhrs.codex.heal-unknown") : Component.translatable("aftrhrs.codex.heal", e.heal()), INK);
			if (!e.bond().isEmpty()) text(out, Component.translatable("aftrhrs.codex.bond", e.bond()), PINK);
		} else {
			if (!e.about().isEmpty()) text(out, Component.literal(e.about()), INK);
			// a boss's habitat is its altar's place, shown with the offering below
			if (!e.habitat().isEmpty() && e.summonItem() == null) text(out, Component.translatable("aftrhrs.codex.lives", e.habitat()), INK);
			if (!e.behavior().isEmpty()) text(out, Component.translatable("aftrhrs.codex.does", e.behavior()), INK);
		}
		// stats
		if (e.health() > 0) {
			StringBuilder s = new StringBuilder(Component.translatable("aftrhrs.codex.health", num(e.health())).getString());
			if (e.attack() > 0) s.append("   ").append(Component.translatable("aftrhrs.codex.attack", num(e.attack())).getString());
			if (e.armor() > 0) s.append("   ").append(Component.translatable("aftrhrs.codex.armor", num(e.armor())).getString());
			if (e.xp() > 0) s.append("   ").append(Component.translatable("aftrhrs.codex.xp", e.xp()).getString());
			gap(out, 2);
			text(out, Component.literal(s.toString()), SOFT);
		}
		// boss: the altar and the fight
		if (e.summonItem() != null) {
			gap(out, 3);
			header(out, Component.translatable("aftrhrs.codex.summon"));
			itemRow(out, List.of(new CodexData.Drop(e.summonItem(), 1, 1, 1)), true);
			if (e.summonWhere() != null && !e.summonWhere().isEmpty()) text(out, Component.translatable("aftrhrs.codex.summon-where", e.summonWhere()), INK);
		}
		if (!e.phases().isEmpty()) {
			gap(out, 3);
			header(out, Component.translatable("aftrhrs.codex.fight"));
			for (String p : e.phases()) text(out, Component.literal("• " + p), INK);
		}
		if (!e.drops().isEmpty()) {
			gap(out, 3);
			header(out, Component.translatable("aftrhrs.codex.drops"));
			itemRow(out, e.drops(), false);
		}
		if (e.inBestiary()) {
			gap(out, 3);
			text(out, Component.translatable("aftrhrs.codex.bestiary", e.harvested(), e.slain(), e.bestiaryPage()), GOLD);
		}
		if (!e.forms().isEmpty()) {
			gap(out, 3);
			formsRow(out, e);
			CodexData.Form f = form == null ? null : e.forms().stream().filter(o -> o.species().equals(form)).findFirst().orElse(null);
			if (f != null && f.seen() && !f.note().isEmpty()) text(out, Component.literal(f.note()), SOFT);
		}
		if (!e.variants().isEmpty() || e.shinySeen()) {
			gap(out, 3);
			header(out, Component.translatable("aftrhrs.codex.looks"));
			swatches(out, e);
		}
		return out;
	}

	/** A creature's color made dark enough to read on the pastel panel (pale yellows and aquas get darker). */
	static int readable(int rgb) {
		int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
		double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
		if (lum > 0.62) {
			double k = 0.62 / lum;
			r = (int) (r * k);
			g = (int) (g * k);
			b = (int) (b * k);
		}
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static String num(double d) {
		return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
	}

	private static int dangerColor(String danger) {
		return switch (danger == null ? "" : danger) {
			case "harmless" -> MINT;
			case "neutral" -> GOLD;
			case "hostile" -> RED;
			case "boss" -> VIOLET;
			case "tameable" -> ORANGE;
			default -> SOFT;
		};
	}

	private void gap(List<Line> out, int h) {
		out.add(new Line() {
			public int height() {
				return h;
			}

			public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, boolean visible) {
			}
		});
	}

	private void header(List<Line> out, Component c) {
		text(out, c.copy().withStyle(ChatFormatting.BOLD), PINK);
	}

	/** Wrapped text, one line each. */
	private void text(List<Line> out, Component c, int color) {
		for (FormattedCharSequence seq : font.split(c, DETAIL_W - 8)) {
			out.add(new Line() {
				public int height() {
					return 10;
				}

				public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, boolean visible) {
					g.text(font, seq, x, y, color, false);
				}
			});
		}
	}

	/** Item icons with their amounts (hover: name, amount and chance). {@code named}: the item's name beside it. */
	private void itemRow(List<Line> out, List<CodexData.Drop> drops, boolean named) {
		int perRow = named ? 1 : Math.max(1, (DETAIL_W - 8) / 34);
		for (int start = 0; start < drops.size(); start += perRow) {
			List<CodexData.Drop> row = drops.subList(start, Math.min(drops.size(), start + perRow));
			out.add(new Line() {
				public int height() {
					return 19;
				}

				public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, boolean visible) {
					int ix = x;
					for (CodexData.Drop d : row) {
						ItemStack stack = stack(d.item());
						if (stack.isEmpty()) continue;
						g.item(stack, ix, y + 1);
						String amount = d.min() == d.max() ? String.valueOf(d.max()) : Math.max(0, d.min()) + "-" + d.max();
						String chance = d.chance() >= 1 ? "" : Math.round(d.chance() * 100) + "%";
						if (named) {
							g.text(font, stack.getHoverName(), ix + 19, y + 5, INK, false);
						} else {
							g.text(font, amount, ix + 17, y + 2, INK, false);
							if (!chance.isEmpty()) g.text(font, chance, ix + 17, y + 10, SOFT, false);
						}
						if (Theme.inside(mx, my, ix, y, named ? w : 32, 18)) {
							List<FormattedCharSequence> t = new ArrayList<>();
							t.add(stack.getHoverName().getVisualOrderText());
							if (!named) t.add(Component.translatable("aftrhrs.codex.drop-amount", amount, chance.isEmpty() ? "100%" : chance).withStyle(ChatFormatting.GRAY).getVisualOrderText());
							tip = t;
						}
						ix += 34;
					}
				}
			});
		}
	}

	/** The creature's forms (young, clones) as chips: click one to see its model on the stage. */
	private void formsRow(List<Line> out, CodexData.Entry e) {
		text(out, Component.translatable("aftrhrs.codex.forms").withStyle(ChatFormatting.BOLD), PINK);
		for (CodexData.Form f : e.forms()) {
			out.add(new Line() {
				public int height() {
					return 15;
				}

				public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, boolean visible) {
					boolean on = f.species().equals(form);
					boolean usable = f.seen() && e.known();
					boolean hov = usable && Theme.inside(mx, my, x, y, w, 13);
					blit(g, on ? BUTTON_MINT : hov ? BUTTON_HOVER : BUTTON, x, y, w, 13);
					String label = usable ? f.name() : "???";
					if (usable && f.health() > 0) label += "  ❤ " + num(f.health()) + (f.attack() > 0 ? "  ⚔ " + num(f.attack()) : "");
					String text = Theme.ellipsize(font, label, w - 6);
					if (on) Theme.centeredFlat(g, font, text, x + w / 2, y + 3, 0xFF1F5A47);
					else g.centeredText(font, text, x + w / 2, y + 3, usable ? WHITE : 0xFFCFC4C9);
					if (hov) g.requestCursor(CursorTypes.POINTING_HAND);
					if (usable && visible) hits.add(new Hit(x, y, w, 13, () -> {
						form = on ? null : f.species();
						tryTint = -1;
						chime(SoundEvents.NOTE_BLOCK_PLING.value(), 1.3f);
					}));
				}
			});
		}
	}

	/** Color variant swatches (click to try one on the model) and the shiny star. */
	private void swatches(List<Line> out, CodexData.Entry e) {
		int perRow = Math.max(1, (DETAIL_W - 8 + 3) / 13);
		int slots = e.variants().size() + (e.shinySeen() ? 1 : 0);
		for (int start = 0; start < slots; start += perRow) {
			int from = start;
			out.add(new Line() {
				public int height() {
					return 13;
				}

				public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, boolean visible) {
					for (int i = from; i < Math.min(slots, from + perRow); i++) {
						int sx = x + (i - from) * 13;
						if (i >= e.variants().size()) {
							blit(g, STAR, sx, y + 1, 9, 9);
							continue;
						}
						CodexData.Variant v = e.variants().get(i);
						int col = 0xFF000000 | v.tint();
						g.fill(sx, y, sx + 10, y + 10, PINK);
						g.fill(sx + 1, y + 1, sx + 9, y + 9, v.seen() ? col : 0xFFCFC4C9);
						if (Theme.inside(mx, my, sx, y, 10, 10)) {
							tip = List.of(Component.literal(v.seen() ? v.name() : "???").getVisualOrderText());
							if (v.seen()) g.requestCursor(CursorTypes.POINTING_HAND);
						}
						if (v.seen() && visible) hits.add(new Hit(sx, y, 10, 10, () -> {
							tryTint = tryTint == col ? -1 : col;
							form = null;
							chime(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f);
						}));
					}
				}
			});
		}
	}

	// ------------------------------------------------------------------ pieces

	/**
	 * The creature (the mod's GeckoLib model, client-side); a firefly swarm for the particle-only Duskflies; a paw while
	 * there is no world to make one in. {@code color}: multiplied over everything, -1 for none. {@code maxAgeMs}: how long
	 * the last picture may be shown again (0: live; see {@link CreatureView}).
	 */
	private void model(GuiGraphicsExtractor g, String key, CodexData.Entry e, EntityType<?> type, CreatureView.Look look, int x0, int y0, int x1, int y1,
					   float yaw, String hold, long now, int color, int maxAgeMs) {
		if (e != null && e.swarm()) {
			swarm(g, x0, y0, x1, y1, now, color);
			return;
		}
		if (CreatureView.draw(g, key, type, look, x0, y0, x1, y1, yaw, CreatureView.DEFAULT_PITCH, hold, color, maxAgeMs)) return;
		int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
		blit(g, PAW, cx - 4, cy - 4 + (int) (Math.sin(now / 200.0) * 2), 9, 9);
	}

	/** Duskflies have no model: a few glowing motes drifting about (dark ones for an unknown entry). */
	private static void swarm(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, long now, int color) {
		int w = x1 - x0, h = y1 - y0;
		boolean dark = color != -1 && (color & 0xFFFFFF) < 0x404040;
		for (int i = 0; i < 9; i++) {
			double t = now / 900.0 + i * 1.7;
			int x = x0 + w / 2 + (int) (Math.sin(t * 1.3 + i) * w * 0.32);
			int y = y0 + h / 2 + (int) (Math.cos(t * 0.9 + i * 2) * h * 0.28);
			float glow = 0.5f + 0.5f * (float) Math.sin(t * 3 + i);
			int alpha = dark ? 0xFF : (int) (120 + 135 * glow);
			int rgb = dark ? 0x2A2230 : 0xE8FF8A;
			g.fill(x, y, x + 2, y + 2, alpha << 24 | rgb);
			if (!dark && glow > 0.7f) g.fill(x - 1, y - 1, x + 3, y + 3, 0x30E8FF8A);
		}
	}

	static ItemStack stack(String id) {
		if (id == null || id.isEmpty()) return ItemStack.EMPTY;
		Identifier key = Identifier.tryParse(id.contains(":") ? id : "aftrhrs:" + id);
		if (key == null) return ItemStack.EMPTY;
		Item item = BuiltInRegistries.ITEM.getValue(key);
		return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}

	private static void blit(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
	}

	private void chime(SoundEvent sound, float pitch) {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, 0.45f));
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		if (!Theme.inside(mx, my, search.getX(), search.getY(), search.getWidth(), search.getHeight())) search.setFocused(false);
		// the scroll bars: click to jump there, drag to scroll
		if (onBar(gridBar, mx, my)) {
			dragBar = 1;
			gridScroll = (float) Theme.scrollAt(my, gridBar[1], gridBar[2], gridBar[3], gridBar[4]);
			return true;
		}
		if (selected != null && onBar(detailBar, mx, my)) {
			dragBar = 2;
			detailScroll = (float) Theme.scrollAt(my, detailBar[1], detailBar[2], detailBar[3], detailBar[4]);
			return true;
		}
		for (Hit h : new ArrayList<>(hits)) {
			if (Theme.inside(mx, my, h.x, h.y, h.w, h.h)) {
				h.run.run();
				return true;
			}
		}
		if (selected != null && Theme.inside(mx, my, px + pw - DETAIL_W - 8, py + 22, DETAIL_W, Mth.clamp((ph - 30) / 2 - 10, 70, 112))) {
			dragging = true;
			dragX = mx;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragBar != 0) {
			int[] geo = dragBar == 1 ? gridBar : detailBar;
			float v = (float) Theme.scrollAt(event.y(), geo[1], geo[2], geo[3], geo[4]);
			if (dragBar == 1) gridScroll = v;
			else detailScroll = v;
			return true;
		}
		if (dragging) {
			yaw += (float) (event.x() - dragX) * 1.4f;
			dragX = event.x();
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		dragBar = 0;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (sideMax > 0 && mx < px + 8 + SIDE_W + 4) {
			sideScroll -= (float) sy * 14;
			return true;
		}
		if (selected != null && mx >= px + pw - DETAIL_W - 8) detailScroll -= (float) sy * 14;
		else gridScroll -= (float) sy * 18;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (search.isFocused() && !event.isEscape()) {
			search.keyPressed(event);
			return true;
		}
		if (event.isEscape() && selected != null) {
			select(null, false);
			return true;
		}
		// the codex key closes the codex, and the pet page it may have come from (Escape goes back to that)
		if (Companion.CODEX_KEY.matches(event)) {
			minecraft.gui.setScreen(null);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (search.isFocused()) return search.charTyped(event);
		// typing anywhere starts a search
		if (!Character.isISOControl(event.codepoint()) && !Character.isWhitespace(event.codepoint())) {
			search.setFocused(true);
			setFocused(search);
			return search.charTyped(event);
		}
		return super.charTyped(event);
	}
}
