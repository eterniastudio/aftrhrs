package net.axther.aftrhrs.client.hud;

import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.ui.HitButton;
import net.axther.aftrhrs.client.update.Updater;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * HUD &amp; display settings: every piece of the Companion's HUD with an on/off switch and a size, a few options per
 * piece, the world effects (waypoint beams, claim walls), and the way into the {@link HudEditorScreen} to drag things
 * around. Every change is saved at once ({@link HudConfig}). Opened from Feather's Settings page, the mod's key, or the
 * loader's mod list.
 *
 * <p>Every switch, size button and option is a {@link HitButton}, so the screen works from the keyboard (Tab,
 * Shift+Tab, the arrows, Enter or Space; a row focused off-screen scrolls into view) and the Narrator reads each control
 * with its value and the row's tooltip.
 */
public final class HudSettingsScreen extends Screen {
	private static final int ROW = 20;
	private final @Nullable Screen parent;
	/** Opened from Feather's Settings page: the message that reopens it on close (open_settings / open_hub), else null. */
	private String backToFeather;
	/** "Reset all" asks for a second click within {@link #CONFIRM_MS}. */
	private static final long CONFIRM_MS = 3000;
	private long resetArmedAt;
	private int px, py, pw, ph;
	/** The scrolled rows' area (screen y). */
	private int top, bottom;
	private float scroll;
	private int content;
	private @Nullable Component tip;
	/** The rows, built once per init() (a resize builds them again). */
	private final List<Row> rows = new ArrayList<>();
	/** Each row widget's row offset in the scrolled content (for scrolling a focused one into view). */
	private final Map<HitButton, Integer> rowOf = new HashMap<>();
	/** Where the focus goes when the focused widget turns inactive (a + at its limit: its −). */
	private final Map<HitButton, HitButton> sibling = new HashMap<>();
	private HitButton editButton, resetButton, doneButton;
	/** The focused widget's id, carried over a resize. */
	private @Nullable String restoreFocus;

	public HudSettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("aftrhrs.hud.title"));
		this.parent = parent;
	}

	@Override
	protected void rebuildWidgets() {
		// A resize rebuilds the widgets and clears focus before init() runs: remember what had it.
		if (getFocused() instanceof HitButton h) restoreFocus = h.id;
		super.rebuildWidgets();
	}

	@Override
	protected void init() {
		pw = Math.min(width - 16, 340);
		ph = Math.min(height - 16, 300);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		top = py + 26;
		bottom = py + ph - 30;
		rows.clear();
		rowOf.clear();
		sibling.clear();
		buildRows(HudConfig.get());
		// Children order is the Tab order: row by row, left to right, then the footer.
		for (Row r : rows) for (HitButton w : r.widgets) addWidget(w.clip(top, bottom));
		int bw = (pw - 24) / 3, footTop = py + ph - 30;
		editButton = new HitButton("footer:edit", bw, 16, Component.translatable("aftrhrs.hud.edit-layout"),
			() -> minecraft.gui.setScreen(new HudEditorScreen(this)), this::paintButton);
		resetButton = new HitButton("footer:reset", bw, 16, Component.translatable("aftrhrs.hud.reset-all"), () -> {
			if (System.currentTimeMillis() - resetArmedAt < CONFIRM_MS) {
				resetArmedAt = 0;
				HudConfig.get().resetAll();
			} else {
				resetArmedAt = System.currentTimeMillis();
			}
		}, this::paintButton);
		doneButton = new HitButton("footer:done", bw, 16, Component.translatable("gui.done"), this::onClose, this::paintButton);
		for (HitButton w : List.of(editButton, resetButton, doneButton)) addWidget(w.clip(footTop, py + ph));
		layout();
		String keep = restoreFocus;
		restoreFocus = null;
		if (keep != null) {
			for (GuiEventListener l : children()) if (l instanceof HitButton h && h.id.equals(keep) && h.visible && h.active) setFocused(h);
		}
	}

	@Override
	protected void setInitialFocus() {
		// Keep a restored focus (vanilla would step on to the next widget from it).
		if (getFocused() == null) super.setInitialFocus();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** The screen for Feather's "HUD & Display" button. */
	public static HudSettingsScreen fromFeather(String back) {
		HudSettingsScreen s = new HudSettingsScreen(null);
		// Only the two pages Feather can reopen on request.
		s.backToFeather = back.equals("open_settings") ? back : "open_hub";
		return s;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
		if (backToFeather != null && parent == null && net.axther.aftrhrs.client.net.Link.has("menus")) {
			net.axther.aftrhrs.client.net.Link.send(net.axther.aftrhrs.client.net.Link.message(backToFeather));
		}
	}

	/**
	 * Places every widget for the current scroll, rows scrolled out of view included (arrow keys pick by rectangle),
	 * and sets their messages and states; a focused widget that turned inactive hands the focus to its sibling.
	 */
	private void layout() {
		content = 0;
		for (Row r : rows) content += r.height;
		scroll = Mth.clamp(scroll, 0, Math.max(0, content - (bottom - top)));
		int ry = top - Math.round(scroll);
		for (Row r : rows) {
			r.layout.layout(ry);
			ry += r.height;
		}
		int bw = (pw - 24) / 3, by = py + ph - 24;
		editButton.place(px + 8, by);
		boolean armed = System.currentTimeMillis() - resetArmedAt < CONFIRM_MS;
		Component reset = Component.translatable(armed ? "aftrhrs.hud.reset-all.confirm" : "aftrhrs.hud.reset-all");
		resetButton.setMessage(reset);
		resetButton.hint(Component.translatable(armed ? "aftrhrs.hud.reset-all.confirm.tip" : "aftrhrs.hud.reset-all.tip"));
		resetButton.place(px + 12 + bw, by);
		doneButton.place(px + 16 + bw * 2, by);
		if (getFocused() instanceof HitButton h && (!h.active || !h.visible)) {
			HitButton s = sibling.get(h);
			if (s != null && s.active && s.visible) setFocused(s);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		tip = null;
		layout();
		Theme.blit(g, Theme.MENU_PANEL, px, py, pw, ph);
		Theme.blit(g, Theme.MENU_HEADER, px + 2, py + 2, pw - 4, 20);
		g.centeredText(font, title, px + pw / 2, py + 8, Theme.MOON);

		g.enableScissor(px + 4, top, px + pw - 4, bottom);
		int ry = top - Math.round(scroll);
		boolean live = my >= top && my < bottom;
		for (Row r : rows) {
			if (ry + r.height > top && ry < bottom) {
				r.draw.draw(g, mx, my, ry, live);
				for (HitButton w : r.widgets) w.extractRenderState(g, mx, my, a);
			}
			ry += r.height;
		}
		g.disableScissor();
		if (content > bottom - top) {
			int track = bottom - top, knob = Math.max(16, track * track / content);
			int ky = top + Math.round((track - knob) * (scroll / Math.max(1, content - track)));
			g.fill(px + pw - 6, ky, px + pw - 4, ky + knob, 0x88C3A6FF);
		}

		// Footer.
		editButton.extractRenderState(g, mx, my, a);
		resetButton.extractRenderState(g, mx, my, a);
		doneButton.extractRenderState(g, mx, my, a);
		if (resetButton.isHovered()) {
			boolean armed = System.currentTimeMillis() - resetArmedAt < CONFIRM_MS;
			tip = Component.translatable(armed ? "aftrhrs.hud.reset-all.confirm.tip" : "aftrhrs.hud.reset-all.tip");
		}

		super.extractRenderState(g, mx, my, a);
		if (tip != null) g.setTooltipForNextFrame(font, font.split(tip, 220), mx, my);
	}

	// ------------------------------------------------------------------ rows

	/** {@code layout} places the row's widgets for the row's top {@code y}; {@code draw} paints the rest of it. */
	private record Row(int height, RowDraw draw, RowLayout layout, List<HitButton> widgets) {
	}

	@FunctionalInterface
	private interface RowDraw {
		void draw(GuiGraphicsExtractor g, int mx, int my, int y, boolean live);
	}

	@FunctionalInterface
	private interface RowLayout {
		void layout(int y);
	}

	private void add(Row r) {
		int offset = 0;
		for (Row o : rows) offset += o.height;
		for (HitButton w : r.widgets) rowOf.put(w, offset);
		rows.add(r);
	}

	private void buildRows(HudConfig c) {
		add(header("aftrhrs.hud.section.screen"));
		for (HudElement e : HudElement.values()) add(element(e));
		add(header("aftrhrs.hud.section.options"));
		add(cycle("aftrhrs.hud.opt.compass-width", () -> Component.translatable("aftrhrs.hud.width." + (c.compassWidth <= 170 ? "narrow" : c.compassWidth >= 280 ? "wide" : "normal")),
			() -> c.compassWidth = c.compassWidth <= 170 ? 220 : c.compassWidth >= 280 ? 160 : 300));
		add(stepper("aftrhrs.hud.opt.quest-rows", () -> c.questRows, v -> c.questRows = Mth.clamp(v, 1, 10)));
		add(stepper("aftrhrs.hud.opt.pet-count", () -> c.petCount, v -> c.petCount = Mth.clamp(v, 1, 6)));
		add(cycle("aftrhrs.hud.opt.pet-layout", () -> Component.translatable(c.petsVertical ? "aftrhrs.hud.pets.column" : "aftrhrs.hud.pets.row"),
			() -> c.petsVertical = !c.petsVertical));
		add(onOff("aftrhrs.hud.opt.chatgame-sound", () -> c.chatGameSound, () -> c.chatGameSound = !c.chatGameSound));
		add(onOff("aftrhrs.hud.wallet.hide_amounts", () -> c.walletHideAmounts, () -> c.walletHideAmounts = !c.walletHideAmounts));
		add(header("aftrhrs.hud.section.world"));
		add(cycle("aftrhrs.hud.opt.beams", () -> Component.translatable("aftrhrs.hud.beams." + c.beams.name().toLowerCase(Locale.ROOT)),
			() -> c.beams = HudConfig.Beams.values()[(c.beams.ordinal() + 1) % HudConfig.Beams.values().length]));
		add(onOff("aftrhrs.hud.opt.beam-labels", () -> c.beamLabels, () -> c.beamLabels = !c.beamLabels));
		add(onOff("aftrhrs.hud.opt.claim-walls", () -> c.claimWalls, () -> c.claimWalls = !c.claimWalls));
		add(onOff("aftrhrs.hud.opt.claim-fx", () -> c.claimFx, () -> c.claimFx = !c.claimFx));
		add(onOff("aftrhrs.hud.opt.claim-sounds", () -> c.claimSounds, () -> c.claimSounds = !c.claimSounds));
		add(onOff("aftrhrs.hud.opt.hide-fx-f1", () -> c.hideFxWithF1, () -> c.hideFxWithF1 = !c.hideFxWithF1));
		add(header("aftrhrs.hud.section.updates"));
		if (Updater.status() != Updater.Status.DEV) {
			add(cycle("aftrhrs.hud.opt.updates", () -> Component.translatable("aftrhrs.update.mode." + Updater.mode().name().toLowerCase(Locale.ROOT)),
				() -> {
					var modes = Updater.Mode.values();
					Updater.mode(modes[(Updater.mode().ordinal() + 1) % modes.length]);
				}));
		}
		add(updateStatus());
	}

	/** What the updater is doing, with Check now / Install when they apply. */
	private Row updateStatus() {
		int bw = 80;
		HitButton button = new HitButton("updater", bw, 16, Component.empty(), () -> {
			var st = Updater.status();
			if (st == Updater.Status.READY) net.axther.aftrhrs.client.update.Restart.now(null);
			else if (st == Updater.Status.AVAILABLE) Updater.installNow();
			else Updater.checkNow();
		}, this::paintButton);
		RowLayout layout = y -> {
			var st = Updater.status();
			boolean ready = st == Updater.Status.READY;
			boolean busy = st == Updater.Status.CHECKING || st == Updater.Status.DOWNLOADING;
			boolean install = st == Updater.Status.AVAILABLE;
			button.place(px + pw - 14 - bw, y + 2);
			button.visible = st != Updater.Status.DEV && (ready || Updater.mode() != Updater.Mode.OFF);
			button.active = !busy;
			button.setMessage(Component.translatable(ready ? "aftrhrs.update.restart.short" : install ? "aftrhrs.update.install" : "aftrhrs.update.check"));
			button.hint(Component.translatable("aftrhrs.update.status." + st.name().toLowerCase(Locale.ROOT), Updater.version()));
		};
		return new Row(ROW, (g, mx, my, y, live) -> {
			var st = Updater.status();
			String key = "aftrhrs.update.status." + st.name().toLowerCase(Locale.ROOT);
			Component line = Component.translatable(key, Updater.version());
			int bx = px + pw - 14 - bw;
			int textW = (button.visible ? bx - 8 : px + pw - 14) - (px + 14);
			int color = st == Updater.Status.READY || st == Updater.Status.AVAILABLE ? Theme.MOON
				: st == Updater.Status.FAILED ? 0xFFF29BB8 : Theme.MUTED;
			g.text(font, Theme.fit(font, line, textW), px + 14, y + 6, color, true);
			String why = Updater.detail();
			if (live && Theme.inside(mx, my, px + 14, y + 2, textW, ROW - 4)) {
				tip = why.isEmpty() ? Component.translatable(key + ".tip") : Component.literal(why);
			}
		}, layout, List.of(button));
	}

	private Row header(String key) {
		return new Row(18, (g, mx, my, y, live) -> {
			g.text(font, Component.translatable(key), px + 10, y + 7, Theme.MOON, true);
			g.fill(px + 10, y + 16, px + pw - 10, y + 17, 0x44C3A6FF);
		}, y -> {
		}, List.of());
	}

	/** Switch, name, size. */
	private Row element(HudElement e) {
		String id = e.name().toLowerCase(Locale.ROOT);
		HitButton pill = new HitButton("pill:" + id, 28, 14, e.label(), () -> {
			HudConfig c = HudConfig.get();
			HudConfig.Pos p = c.pos(e);
			p.on = !p.on;
			c.save();
		}, pillPainter(() -> HudConfig.get().pos(e).on)).hint(e.tip());
		HitButton home = small("home:" + id, "⌂", () -> {
			HudConfig c = HudConfig.get();
			c.reset(e);
			c.save();
		}).hint(Component.translatable("aftrhrs.hud.reset-one"));
		HitButton minus = small("minus:" + id, "−", () -> step(e, -1)).hint(e.tip());
		HitButton plus = small("plus:" + id, "+", () -> step(e, 1)).hint(e.tip());
		sibling.put(plus, minus);
		sibling.put(minus, plus);
		sibling.put(home, pill);
		RowLayout layout = y -> {
			HudConfig c = HudConfig.get();
			HudConfig.Pos p = c.pos(e);
			int rx = px + pw - 14;
			float ds = HudConfig.drawScale(p.scale);
			Component pct = Component.literal(Math.round(ds * 100) + "%");
			pill.place(px + 10, y + 3);
			pill.setMessage(CommonComponents.optionNameValue(e.label(), p.on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF));
			plus.place(rx - 12, y + 3);
			plus.active = HudConfig.stepScale(p.scale, 1) != ds;
			plus.setMessage(Component.translatable("aftrhrs.hud.narrate.size-up", e.label(), pct));
			minus.place(rx - 68, y + 3);
			minus.active = HudConfig.stepScale(p.scale, -1) != ds;
			minus.setMessage(Component.translatable("aftrhrs.hud.narrate.size-down", e.label(), pct));
			boolean away = !c.atHome(e);
			home.place(rx - 84, y + 3);
			home.visible = home.active = away;
			home.setMessage(Component.translatable("aftrhrs.hud.narrate.reset-position", e.label()));
		};
		return new Row(ROW, (g, mx, my, y, live) -> {
			HudConfig c = HudConfig.get();
			HudConfig.Pos p = c.pos(e);
			g.text(font, e.label(), px + 46, y + 6, p.on ? Theme.TEXT : Theme.SUBTLE, true);
			if (live && Theme.inside(mx, my, px + 46, y + 2, 150, ROW - 4)) tip = e.tip();
			// Size: − 100% + (the size drawn, snapped so the pixel font stays even; one crisp step per click)
			float ds = HudConfig.drawScale(p.scale);
			g.centeredText(font, Math.round(ds * 100) + "%", px + pw - 14 - 34, y + 6, ds == 1f ? Theme.MUTED : Theme.SKY);
			if (live && home.visible && Theme.inside(mx, my, home.getX(), home.getY(), 14, 14)) tip = Component.translatable("aftrhrs.hud.reset-one");
		}, layout, List.of(pill, home, minus, plus));
	}

	private static void step(HudElement e, int dir) {
		HudConfig c = HudConfig.get();
		HudConfig.Pos p = c.pos(e);
		p.scale = HudConfig.stepScale(p.scale, dir);
		c.save();
	}

	private Row onOff(String key, Supplier<Boolean> get, Runnable flip) {
		Component label = Component.translatable(key);
		HitButton pill = new HitButton("opt:" + key, 28, 14, label, () -> {
			flip.run();
			HudConfig.INSTANCE.save();
		}, pillPainter(get::get)).hint(Component.translatable(key + ".tip"));
		RowLayout layout = y -> {
			pill.place(px + 10, y + 3);
			pill.setMessage(CommonComponents.optionNameValue(label, get.get() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF));
		};
		return new Row(ROW, (g, mx, my, y, live) -> {
			boolean on = get.get();
			g.text(font, label, px + 46, y + 6, on ? Theme.TEXT : Theme.SUBTLE, true);
			if (live && Theme.inside(mx, my, px + 46, y + 2, pw - 60, ROW - 4)) tip = Component.translatable(key + ".tip");
		}, layout, List.of(pill));
	}

	private Row cycle(String key, Supplier<Component> value, Runnable next) {
		Component label = Component.translatable(key);
		int bw = 120;
		HitButton button = new HitButton("opt:" + key, bw, 16, label, () -> {
			next.run();
			HudConfig.INSTANCE.save();
		}, this::paintButton).hint(Component.translatable(key + ".tip"));
		RowLayout layout = y -> {
			Component v = value.get();
			button.place(px + pw - 14 - bw, y + 2);
			button.label(v);
			button.setMessage(CommonComponents.optionNameValue(label, v));
		};
		return new Row(ROW, (g, mx, my, y, live) -> {
			g.text(font, label, px + 14, y + 6, Theme.TEXT, true);
			if (live && Theme.inside(mx, my, px + 14, y + 2, pw - bw - 40, ROW - 4)) tip = Component.translatable(key + ".tip");
		}, layout, List.of(button));
	}

	private Row stepper(String key, IntSupplier get, IntConsumer set) {
		Component label = Component.translatable(key);
		HitButton minus = small("minus:" + key, "−", () -> {
			set.accept(get.getAsInt() - 1);
			HudConfig.INSTANCE.save();
		}).hint(Component.translatable(key + ".tip"));
		HitButton plus = small("plus:" + key, "+", () -> {
			set.accept(get.getAsInt() + 1);
			HudConfig.INSTANCE.save();
		}).hint(Component.translatable(key + ".tip"));
		RowLayout layout = y -> {
			int rx = px + pw - 14;
			Component v = Component.literal(String.valueOf(get.getAsInt()));
			minus.place(rx - 68, y + 3);
			minus.setMessage(Component.translatable("aftrhrs.hud.narrate.step-down", label, v));
			plus.place(rx - 12, y + 3);
			plus.setMessage(Component.translatable("aftrhrs.hud.narrate.step-up", label, v));
		};
		return new Row(ROW, (g, mx, my, y, live) -> {
			g.text(font, label, px + 14, y + 6, Theme.TEXT, true);
			g.centeredText(font, String.valueOf(get.getAsInt()), px + pw - 14 - 34, y + 6, Theme.SKY);
			if (live && Theme.inside(mx, my, px + 14, y + 2, pw - 100, ROW - 4)) tip = Component.translatable(key + ".tip");
		}, layout, List.of(minus, plus));
	}

	// ------------------------------------------------------------------ bits

	private static HitButton.Painter pillPainter(BooleanSupplier on) {
		return (g, self, lit) -> {
			int x = self.getX(), y = self.getY(), w = self.getWidth(), h = self.getHeight();
			boolean o = on.getAsBoolean();
			g.fill(x, y, x + w, y + h, lit ? 0xFF3A3360 : 0xFF241E40);
			g.outline(x, y, w, h, o ? 0xFF86E8B0 : 0xFF6B6487);
			int kx = o ? x + w - 12 : x + 2;
			g.fill(kx, y + 2, kx + 10, y + h - 2, o ? 0xFF86E8B0 : 0xFF6B6487);
		};
	}

	/** A 14px square button with a glyph ({@code message} is set per frame by the row's layout). */
	private HitButton small(String id, String glyph, Runnable run) {
		return new HitButton(id, 14, 14, Component.literal(glyph), run, (g, self, lit) -> {
			int x = self.getX(), y = self.getY();
			Theme.menuButton(g, x, y, 14, 14, lit && self.active, self.active);
			g.centeredText(font, self.label(), x + 7, y + 3, self.active ? Theme.TEXT : Theme.DIM);
		}).label(Component.literal(glyph));
	}

	private void paintButton(GuiGraphicsExtractor g, HitButton self, boolean lit) {
		int x = self.getX(), y = self.getY(), w = self.getWidth(), h = self.getHeight();
		Theme.menuButton(g, x, y, w, h, lit && self.active, self.active);
		g.centeredText(font, self.label(), x + w / 2, y + (h - 8) / 2, self.active ? Theme.TEXT : Theme.DIM);
	}

	// ------------------------------------------------------------------ input

	@Override
	public void setFocused(@Nullable GuiEventListener l) {
		super.setFocused(l);
		if (l instanceof HitButton h && rowOf.containsKey(h)) reveal(h);
	}

	/** Scrolls so the focused widget's row sits inside the list (a little clear of its edges). */
	private void reveal(HitButton h) {
		int offset = rowOf.get(h);
		int rowTop = top + offset - Math.round(scroll);
		if (rowTop < top + 2) scroll = offset - 2;
		else if (rowTop > bottom - ROW - 2) scroll = offset - (bottom - ROW - 2 - top);
		layout();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll -= (float) sy * 16;
		return true;
	}
}
