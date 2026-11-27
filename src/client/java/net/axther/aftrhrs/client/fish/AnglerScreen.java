package net.axther.aftrhrs.client.fish;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.ui.HitButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Angler's Journal (docs/companion-protocol.md, "Fishing"): the Fish Journal as wooden shelves in a tackle box,
 * one per rarity with its ribbon, progress and reward, every caught fish drawn large as its real item; uncaught ones
 * are a '?' tag with the dialog's hint. A finished tier gets a glowing Claim button (Feather checks and pays). The
 * Records tab is a wall of brass plaques. Everything shown comes from Feather's snapshot; nothing is decided here.
 */
public final class AnglerScreen extends Screen {
	public enum Tab {
		JOURNAL, RECORDS;

		public static Tab of(String s) {
			return "records".equals(s) ? RECORDS : JOURNAL;
		}

		Component label() {
			return Component.translatable("aftrhrs.fish.tab." + name().toLowerCase(Locale.ROOT));
		}
	}

	static final Identifier FRAME = sprite("fish/frame"), HEADER = sprite("fish/header"), SHELF = sprite("fish/shelf"),
		CELL = sprite("fish/cell"), CELL_HOVER = sprite("fish/cell_hover"), RIBBON = sprite("fish/ribbon"),
		PLAQUE = sprite("fish/plaque"), TAG = sprite("fish/tag"), CLAIM = sprite("fish/claim"),
		CLAIM_HOVER = sprite("fish/claim_hover"), GLOW = sprite("fish/glow"), SEAL = sprite("fish/seal"), CROWN = sprite("fish/crown");

	private static final int TAB_H = 16, HEADER_H = 22, FOOTER_H = 30, RIM = 7;
	private static final int CELL_SIZE = 36, CELL_GAP = 4, SHELF_H = 8, PLAQUE_H = 30;
	/** Dark ink on light ribbons and buttons; engraving on brass; gold for coins; green for done. */
	private static final int INK = 0xFF241C42, ENGRAVE = 0xFF3A2418, ENGRAVE_SOFT = 0xFF6E4A2C, GOLD = 0xFFFFD24A, GOOD = 0xFF86E8B0;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

	private Tab tab;
	private final Map<Tab, Double> scroll = new HashMap<>();
	private int contentHeight;
	private int px, py, pw, ph;
	/**
	 * The fish cells, Claim buttons and Done as widgets (Tab, the arrows, Enter or Space, the Narrator), by id, in the
	 * order drawn. They are made while drawing and go when a frame no longer draws them (another tab).
	 */
	private final Map<String, HitButton> widgets = new LinkedHashMap<>();
	/** This frame's press and paint for each widget, and the ids in drawing order. */
	private final Map<String, Runnable> livePress = new HashMap<>();
	private final Map<String, HitButton.Painter> livePaint = new HashMap<>();
	private final Set<String> drawn = new LinkedHashSet<>();
	/** A fish cell focused from the keyboard: its tooltip, shown beside it (the pointer shows its own). */
	private List<FormattedCharSequence> focusTip = List.of();
	private int focusTipX, focusTipY;
	/** Claims sent and not answered yet (the button waits), by rarity: when they were sent. */
	private final Map<String, Long> pending = new HashMap<>();
	private boolean opened;

	public AnglerScreen(Tab tab) {
		super(Component.translatable("aftrhrs.fish.title"));
		this.tab = tab;
	}

	private static Identifier sprite(String path) {
		return Identifier.fromNamespaceAndPath(Companion.ID, path);
	}

	/** A fresh snapshot came (a claim answered, a refresh); {@code open} switches tab when Feather asked for one. */
	void dataChanged(Tab open) {
		pending.clear();
		if (open != null && open != tab) switchTab(open, false);
	}

	@Override
	protected void init() {
		pw = Math.min(width - 16, Mth.clamp(width - 40, 260, 400));
		ph = Mth.clamp(height - 28 - TAB_H, 120, 330);
		px = (width - pw) / 2;
		py = (height - ph + TAB_H) / 2;
		if (!opened) {
			opened = true;
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f, 0.6f));
		}
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int contentTop() {
		return py + RIM + HEADER_H + 4;
	}

	private int contentBottom() {
		return py + ph - FOOTER_H;
	}

	private int innerX() {
		return px + RIM + 7;
	}

	private int innerW() {
		return pw - (RIM + 7) * 2 - 6;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		drawTabs(g, mx, my, false);
		Theme.blit(g, FRAME, px, py, pw, ph);
		drawTabs(g, mx, my, true);

		// Header: the title, and how far along (journal) or how many records.
		Theme.blit(g, HEADER, px + RIM, py + RIM, pw - RIM * 2, HEADER_H);
		Component right = tab == Tab.JOURNAL
			? Component.translatable("aftrhrs.fish.summary", FishJournal.have, FishJournal.total)
			: Component.translatable("aftrhrs.fish.records.count", FishJournal.records.size());
		int rw = font.width(right);
		g.text(font, right, px + pw - RIM - 8 - rw, py + RIM + 6, Theme.MUTED);
		Component title = tab == Tab.JOURNAL ? getTitle() : Component.translatable("aftrhrs.fish.records.title");
		g.text(font, Theme.fit(font, title, pw - RIM * 2 - rw - 24), px + RIM + 8, py + RIM + 6, Theme.MOON);

		int top = contentTop(), bottom = contentBottom();
		livePress.clear();
		livePaint.clear();
		drawn.clear();
		focusTip = List.of();
		double s = scroll.getOrDefault(tab, 0.0);
		g.enableScissor(px + RIM, top, px + pw - RIM, bottom);
		int y0 = top + 4 - (int) s;
		boolean inside = my >= top && my < bottom && mx >= px + RIM && mx < px + pw - RIM;
		List<FormattedCharSequence> tip = new ArrayList<>();
		int end = tab == Tab.JOURNAL ? drawJournal(g, y0, inside, mx, my, tip) : drawRecords(g, y0, mx, my);
		contentHeight = end + (int) s - top;
		g.disableScissor();
		// keep the scroll inside the content (a snapshot can make it shorter)
		double max = Math.max(0, contentHeight - (bottom - top));
		if (s > max) scroll.put(tab, max);
		drawScrollbar(g, top, bottom);

		// Footer: Done.
		int ew = Math.min(120, pw - 40), ex = px + (pw - ew) / 2, ey = py + ph - RIM - 21;
		Component doneLabel = Component.translatable("gui.done");
		control("done", ex, ey, ew, 20, doneLabel, () -> {
			click(1f);
			onClose();
		}, (gg, self, lit) -> {
			Theme.menuButton(gg, self.getX(), self.getY(), self.getWidth(), 20, lit, true);
			gg.centeredText(font, doneLabel, self.getX() + self.getWidth() / 2, self.getY() + 6, Theme.TEXT);
		}).extractRenderState(g, mx, my, a);
		syncWidgets();

		super.extractRenderState(g, mx, my, a);
		if (!tip.isEmpty()) g.setTooltipForNextFrame(font, tip, mx, my);
		else if (!focusTip.isEmpty()) g.setTooltipForNextFrame(font, focusTip, focusTipX, focusTipY);
	}

	/** This frame's widget {@code id} at x, y, w, h (made the first time); {@code press} and {@code paint} are this frame's. */
	private HitButton control(String id, int x, int y, int w, int h, Component message, Runnable press, HitButton.Painter paint) {
		livePress.put(id, press);
		livePaint.put(id, paint);
		drawn.add(id);
		HitButton b = widgets.computeIfAbsent(id, k -> new HitButton(k, w, h, message, () -> {
			Runnable run = livePress.get(k);
			if (run != null) run.run();
		}, (g, self, lit) -> {
			HitButton.Painter p = livePaint.get(self.id);
			if (p != null) p.paint(g, self, lit);
		}).quiet());
		b.setRectangle(w, h, x, y);
		b.setMessage(message);
		b.visible = true;
		b.active = true;
		return b;
	}

	/** The widgets match what this frame drew, in drawing order (the Tab order); the focused one keeps the focus. */
	private void syncWidgets() {
		if (new ArrayList<>(drawn).equals(new ArrayList<>(widgets.keySet())) && widgets.values().stream().allMatch(children()::contains)) return;
		var focus = getFocused();
		for (HitButton w : widgets.values()) removeWidget(w);
		Map<String, HitButton> keep = new LinkedHashMap<>();
		for (String id : drawn) {
			HitButton w = widgets.get(id);
			if (w != null) keep.put(id, addWidget(w));
		}
		widgets.clear();
		widgets.putAll(keep);
		if (focus instanceof HitButton h && widgets.containsValue(h) && getFocused() != h) setFocused(h);
	}

	@Override
	public void setFocused(GuiEventListener l) {
		super.setFocused(l);
		// a cell or Claim focused from the keyboard scrolls into view
		if (!(l instanceof HitButton b) || b.id.equals("done")) return;
		int top = contentTop(), bottom = contentBottom(), view = bottom - top;
		double s = scroll.getOrDefault(tab, 0.0);
		if (b.getY() < top + 2) s -= top + 2 - b.getY();
		else if (b.getBottom() > bottom - 2) s += b.getBottom() - (bottom - 2);
		scroll.put(tab, Mth.clamp(s, 0, Math.max(0, contentHeight - view)));
	}

	private int drawJournal(GuiGraphicsExtractor g, int y, boolean inside, int mx, int my, List<FormattedCharSequence> tip) {
		int x = innerX(), w = innerW();
		if (FishJournal.tiers.isEmpty()) {
			g.centeredText(font, Component.translatable("aftrhrs.fish.empty"), px + pw / 2, y + 10, Theme.MUTED);
			return y + 30;
		}
		int cols = Math.max(1, (w + CELL_GAP) / (CELL_SIZE + CELL_GAP));
		// spread the cells over the shelf's width
		int stride = cols > 1 ? Math.min(CELL_SIZE + 12, (w - CELL_SIZE) / (cols - 1)) : 0;
		String me = minecraft.player != null ? minecraft.player.getGameProfile().name() : "";
		if (!FishJournal.gear.isEmpty()) {
			// what the rod's enchantments do for the catch (Feather's numbers)
			for (FormattedCharSequence line : font.split(Component.literal(FishJournal.gear), w)) {
				g.text(font, line, x, y, Theme.MUTED, false);
				y += font.lineHeight + 1;
			}
			y += 5;
		}
		for (FishJournal.Tier t : FishJournal.tiers) {
			y = drawTierHeader(g, t, x, y, w, inside, mx, my, tip);
			List<FishJournal.Fish> fish = t.fish();
			for (int row = 0; row * cols < fish.size(); row++) {
				for (int c = 0; c < cols && row * cols + c < fish.size(); c++) {
					FishJournal.Fish f = fish.get(row * cols + c);
					int cx = x + c * stride, cy = y;
					control("fish:" + t.rarity() + ":" + (row * cols + c), cx, cy, CELL_SIZE, CELL_SIZE, fishNarration(f), () -> {
					}, (gg, self, lit) -> {
						Theme.blit(gg, lit ? CELL_HOVER : CELL, self.getX(), self.getY(), CELL_SIZE, CELL_SIZE);
						drawCell(gg, f, self.getX(), self.getY(), me);
						if (self.isHovered()) fishTip(f, t, me, tip);
						else if (self.isFocused() && minecraft.getLastInputType().isKeyboard()) {
							List<FormattedCharSequence> lines = new ArrayList<>();
							fishTip(f, t, me, lines);
							focusTip = lines;
							focusTipX = self.getX() + CELL_SIZE - 4;
							focusTipY = self.getY() + 8;
						}
					}).clip(contentTop(), contentBottom()).extractRenderState(g, mx, my, 0);
				}
				Theme.blit(g, SHELF, x - 2, y + CELL_SIZE - 2, w + 4, SHELF_H);
				y += CELL_SIZE + SHELF_H + 4;
			}
			y += 6;
		}
		return y;
	}

	/** The rarity's ribbon, its progress and reward, and a Claim button (or the seal once it's done). */
	private int drawTierHeader(GuiGraphicsExtractor g, FishJournal.Tier t, int x, int y, int w, boolean inside, int mx, int my,
							   List<FormattedCharSequence> tip) {
		String name = Theme.ellipsize(font, t.name(), 110);
		int rw = font.width(name) + 24;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, RIBBON, x, y, rw, 14, 0xFF000000 | t.color());
		g.text(font, name, x + 12, y + 3, INK, false);
		int tx = x + rw + 6;
		String progress = t.have() + "/" + t.total();
		g.text(font, progress, tx, y + 3, Theme.TEXT, true);
		tx += font.width(progress) + 6;
		if (t.done()) {
			Theme.blit(g, SEAL, tx, y + 1, 11, 11);
			Component done = Component.translatable(t.coins() > 0 ? "aftrhrs.fish.tier.collected" : "aftrhrs.fish.tier.complete");
			g.text(font, done, tx + 14, y + 3, GOOD, true);
		} else if (t.claimable()) {
			// complete, the reward waits on the Claim button (which shows the amount)
			g.text(font, Component.translatable("aftrhrs.fish.tier.complete"), tx, y + 3, GOOD, true);
		} else if (t.coins() > 0) {
			g.text(font, Component.translatable("aftrhrs.fish.tier.reward", t.coins()), tx, y + 3, GOLD, true);
		}
		if (t.claimable()) {
			Component label = Component.translatable("aftrhrs.fish.claim", t.coins());
			Component claimTip = Component.translatable("aftrhrs.fish.claim.tip", t.name(), t.coins());
			int bw = Math.max(54, font.width(label) + 16), bx = x + w - bw, by = y - 1;
			Long sent = pending.get(t.rarity());
			boolean waiting = sent != null && System.currentTimeMillis() - sent < 3000;
			HitButton claim = control("claim:" + t.rarity(), bx, by, bw, 16, label, () -> {
				pending.put(t.rarity(), System.currentTimeMillis());
				click(1.2f);
				FishJournal.claim(t.rarity());
			}, (gg, self, lit) -> {
				int cx = self.getX(), cy = self.getY();
				if (self.active) {
					// a slow golden pulse so a waiting reward catches the eye
					float pulse = 0.55f + 0.45f * Mth.sin((System.currentTimeMillis() % 2400L) / 2400f * Mth.TWO_PI);
					int alpha = (int) (pulse * (lit ? 255 : 210));
					gg.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, cx - 5, cy - 5, bw + 10, 26, (alpha << 24) | 0xFFFFFF);
					Theme.blit(gg, lit ? CLAIM_HOVER : CLAIM, cx, cy, bw, 16);
					gg.centeredText(font, label, cx + bw / 2, cy + 4, INK);
				} else {
					Theme.menuButton(gg, cx, cy, bw, 16, false, false);
					gg.centeredText(font, label, cx + bw / 2, cy + 4, Theme.DIM);
				}
				if (self.active && self.isHovered()) tip.addAll(font.split(claimTip, 200));
			}).clip(contentTop(), contentBottom()).hint(claimTip);
			claim.active = !waiting;
			claim.extractRenderState(g, mx, my, 0);
		}
		return y + 18;
	}

	/** A cell's fish: the item at double size with its count and your record crown, or the unknown tag. */
	private void drawCell(GuiGraphicsExtractor g, FishJournal.Fish f, int cx, int cy, String me) {
		if (f.caught()) {
			drawBig(g, f.item(), cx + 2, cy + 2);
			if (f.count() > 1) {
				String n = "×" + f.count();
				g.text(font, n, cx + CELL_SIZE - 3 - font.width(n), cy + CELL_SIZE - 11, Theme.TEXT, true);
			}
			if (f.recordGrams() > 0 && f.best() >= f.recordGrams() && f.recordName().equalsIgnoreCase(me)) {
				Theme.blit(g, CROWN, cx + CELL_SIZE - 12, cy + 4, 9, 5);
			}
		} else {
			Theme.blit(g, TAG, cx + 2, cy + 2, 32, 32);
		}
	}

	/** What the Narrator says for a cell: the fish and how many you caught, or that it is still unknown. */
	private static Component fishNarration(FishJournal.Fish f) {
		if (!f.caught()) return Component.translatable("aftrhrs.fish.unknown");
		return Component.translatable("aftrhrs.fish.narrate", f.name(), Component.translatable("aftrhrs.fish.caught", f.count()));
	}

	private void fishTip(FishJournal.Fish f, FishJournal.Tier t, String me, List<FormattedCharSequence> tip) {
		List<Component> lines = new ArrayList<>();
		if (!f.caught()) {
			lines.add(Component.translatable("aftrhrs.fish.unknown").withColor(Theme.SUBTLE));
			if (!f.hint().isEmpty()) lines.add(Component.translatable("aftrhrs.fish.hint", f.hint()).withColor(Theme.MUTED));
		} else {
			lines.add(Component.literal(f.name()).withColor(t.color()));
			lines.add(Component.translatable("aftrhrs.fish.caught", f.count()).withColor(Theme.MUTED));
			if (f.best() > 0) lines.add(Component.translatable("aftrhrs.fish.best", FishJournal.lb(f.best())).withColor(Theme.MUTED));
			if (f.first() > 0) lines.add(Component.translatable("aftrhrs.fish.first", date(f.first())).withColor(Theme.MUTED));
			if (f.recordGrams() <= 0) lines.add(Component.translatable("aftrhrs.fish.record.none").withColor(Theme.SUBTLE));
			else if (f.recordName().equalsIgnoreCase(me)) lines.add(Component.translatable("aftrhrs.fish.record.yours", FishJournal.lb(f.recordGrams())).withColor(GOLD));
			else lines.add(Component.translatable("aftrhrs.fish.record", FishJournal.lb(f.recordGrams()), f.recordName()).withColor(Theme.SKY));
		}
		for (Component c : lines) tip.addAll(font.split(c, 220));
	}

	private static String date(long epochMs) {
		return DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
	}

	private void drawBig(GuiGraphicsExtractor g, ItemStack item, int x, int y) {
		if (item.isEmpty()) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(2f, 2f);
		g.item(item, 0, 0);
		g.pose().popMatrix();
	}

	private int drawRecords(GuiGraphicsExtractor g, int y, int mx, int my) {
		int x = innerX(), w = innerW();
		if (FishJournal.records.isEmpty()) {
			g.centeredText(font, Component.translatable("aftrhrs.fish.records.empty"), px + pw / 2, y + 10, Theme.MUTED);
			y += 26;
		}
		String me = minecraft.player != null ? minecraft.player.getGameProfile().name() : "";
		for (FishJournal.Rec r : FishJournal.records) {
			Theme.blit(g, PLAQUE, x, y, w, PLAQUE_H);
			if (!r.item().isEmpty()) g.item(r.item(), x + 9, y + 7);
			// the rarity as a little enamel pip beside the name
			int tx = x + 31;
			g.fill(tx, y + 8, tx + 5, y + 13, ENGRAVE);
			g.fill(tx + 1, y + 9, tx + 4, y + 12, 0xFF000000 | FishJournal.colorOf(r.rarity()));
			int nameMax = w - 31 - 9 - 90;
			g.text(font, Theme.ellipsize(font, r.name(), nameMax), tx + 8, y + 7, ENGRAVE, false);
			String line = FishJournal.lb(r.grams()) + " · " + r.holder();
			g.text(font, Theme.ellipsize(font, line, nameMax + 8), tx, y + 18, ENGRAVE_SOFT, false);
			boolean mine = r.holder().equalsIgnoreCase(me);
			if (mine) Theme.blit(g, CROWN, tx + Math.min(font.width(line), nameMax + 8) + 4, y + 19, 9, 5);
			// your own best, on the right
			int rx = x + w - 10;
			Component label = Component.translatable("aftrhrs.fish.records.yours");
			g.text(font, label, rx - font.width(label), y + 7, ENGRAVE_SOFT, false);
			String best = r.mine() > 0 ? FishJournal.lb(r.mine()) : "—";
			g.text(font, best, rx - font.width(best), y + 18, ENGRAVE, false);
			y += PLAQUE_H + 3;
		}
		y += 4;
		for (FormattedCharSequence line : font.split(Component.translatable("aftrhrs.fish.records.how"), w)) {
			g.centeredText(font, line, x + w / 2, y, Theme.SUBTLE);
			y += 10;
		}
		return y + 4;
	}

	/** Tabs on the frame's top edge, like the menus': the open one joins the frame (drawn after it). */
	private void drawTabs(GuiGraphicsExtractor g, int mx, int my, boolean selectedPass) {
		int x = px + RIM + 6;
		for (Tab t : Tab.values()) {
			int w = tabWidth(t);
			boolean active = t == tab;
			if (active == selectedPass) {
				boolean hov = Theme.inside(mx, my, x, py - TAB_H + 2, w, TAB_H);
				int ty = active ? py - TAB_H + 2 : py - TAB_H + 4;
				Theme.blit(g, active ? Theme.MENU_TAB_SELECTED : Theme.MENU_TAB, x, ty, w, active ? TAB_H + 1 : TAB_H - 2);
				int color = active ? Theme.MOON : hov ? Theme.TEXT : Theme.MUTED;
				g.centeredText(font, t.label(), x + w / 2, ty + (active ? 5 : 4), color);
				if (hov && !active) g.requestCursor(CursorTypes.POINTING_HAND);
			}
			x += w + 2;
		}
	}

	private int tabWidth(Tab t) {
		return Math.max(54, font.width(t.label()) + 18);
	}

	private Tab tabAt(double mx, double my) {
		if (my < py - TAB_H + 2 || my >= py + 2) return null;
		int x = px + RIM + 6;
		for (Tab t : Tab.values()) {
			int w = tabWidth(t);
			if (mx >= x && mx < x + w) return t;
			x += w + 2;
		}
		return null;
	}

	private void drawScrollbar(GuiGraphicsExtractor g, int top, int bottom) {
		int view = bottom - top;
		Theme.scrollbar(g, px + pw - RIM - 9, top + 4, 6, view - 8, scroll.getOrDefault(tab, 0.0), contentHeight, view, Theme.MENU_INSET, Theme.MENU_SCROLLER);
	}

	// ------------------------------------------------------------------ input

	private void switchTab(Tab t, boolean sound) {
		if (t == tab) return;
		tab = t;
		if (sound) click(1.1f);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		int view = contentBottom() - contentTop();
		scroll.put(tab, Mth.clamp(scroll.getOrDefault(tab, 0.0) - sy * 16, 0, Math.max(0, contentHeight - view)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The fish cells, the Claim buttons and Done are widgets.
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		double mx = event.x(), my = event.y();
		Tab t = tabAt(mx, my);
		if (t != null) {
			switchTab(t, true);
			return true;
		}
		return false;
	}

	private void click(float pitch) {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.35f));
	}
}
