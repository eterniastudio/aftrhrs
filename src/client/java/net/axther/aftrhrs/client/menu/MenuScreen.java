package net.axther.aftrhrs.client.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Decode;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Feather dialog drawn by the Companion (docs/companion-protocol.md, "Menus"). Same buttons, same callbacks on
 * the server; the mod adds the Aftr Hrs look, hover tooltips, a filter for long lists, scrolling, and for the hub
 * its groups as tabs on one screen. Every card and the exit are real widgets, so the menu works from the keyboard:
 * Tab / Shift+Tab and the arrows move between them, Enter or Space presses, Ctrl+Tab and Ctrl+1..9 switch the hub's tabs.
 */
public final class MenuScreen extends Screen {
	/** Button row height and gaps, as in vanilla menus; header plaque; footer; inner padding; tab strip. */
	private static final int GAP = 4, CARD_H = 20, HEADER_H = 24, FOOTER_H = 30, PAD = 10, TAB_H = 16;
	/** The pressed card alone shows for this long before the rest dims (a fast server never flashes gray). */
	private static final long DIM_AFTER = 250;
	/** No answer this long: say so; this long: give up and close (the server already spent the click). */
	private static final long NO_ANSWER_AFTER = 3000, GIVE_UP_AFTER = 4000;

	private record Btn(int index, Component label, Component tip, String section, ItemStack icon) {
	}

	private record Section(String key, Component label, List<Btn> buttons) {
	}

	private record Body(Component text, ItemStack item, int width) {
	}

	private record Input(String kind, String key, Component label, JsonObject spec) {
	}

	private final int id;
	private final boolean hub;
	private final List<Body> body = new ArrayList<>();
	private final List<Input> inputs = new ArrayList<>();
	private final List<Btn> buttons = new ArrayList<>();
	private final List<Section> sections = new ArrayList<>();
	private final int columns;
	private final Component exitLabel;
	private final boolean exitCallback;

	/** -1: the hub's home tab (or a list menu), else the index into {@link #sections}. */
	private int tab = -1;
	private double scroll;
	private int contentHeight;
	private EditBox filter;
	private final Map<String, AbstractWidget> inputWidgets = new LinkedHashMap<>();
	private final Map<String, Object> inputValues = new HashMap<>();
	/** The cards shown on the current tab (filtered), in order: one widget each. */
	private final List<CardButton> cardWidgets = new ArrayList<>();
	private NightButton exitButton;
	/** Whether the exit's label was last given the dimmed color (null: not yet this init). */
	private Boolean exitDim;
	/** The first card row's offset in the scrolled content (set while drawing; worked out before the first frame). */
	private int cardsOrigin = -1;
	/** A hub tab whose label is cut, under the pointer this frame (its full label goes in a tooltip). */
	private Component hoveredTab;
	private boolean waiting;
	private long waitingSince;
	/** The button index sent and waiting ({@code -1}: the exit with a callback). */
	private int pressed = Integer.MIN_VALUE;
	/** Carried over a reopen or a resize: the focused card's button index, the filter's text and focus. */
	private Integer restoreFocus;
	private String restoreFilter;
	private boolean restoreFilterFocus;

	private int px, py, pw, ph;
	/** The open tab's x and width this frame (0 wide: no open tab drawn, e.g. on the hub's home). */
	private int openTabX, openTabW;

	// Text layout kept between frames (the body, title and tabs never change; the widths only in init()).
	/** Each body's wrapped lines for {@link #bodyLinesW} (the content width; item bodies wrap 50 narrower). */
	private final Map<Body, List<FormattedCharSequence>> bodyLines = new IdentityHashMap<>();
	private int bodyLinesW = -1;
	/** The title fitted to {@link #titleMax} (-1: not yet). */
	private FormattedCharSequence titleLine;
	private boolean titleFits;
	private int titleMax = -1;
	/** Per tab (home first): its width, its label fitted to the tab, and whether it fits whole; filled as needed. */
	private int[] tabW;
	private FormattedCharSequence[] tabLine;
	private boolean[] tabFits;

	private MenuScreen(JsonObject m) {
		super(Decode.text(m.get("title"), Component.literal("Aftr Hrs")));
		this.id = Link.num(m, "id", -1);
		this.hub = "hub".equals(Link.str(m, "layout"));
		this.columns = Math.max(1, Math.min(4, Link.num(m, "columns", 1)));
		for (JsonElement e : arr(m, "body")) {
			JsonObject o = e.getAsJsonObject();
			body.add(new Body(Decode.text(o.get("c")), "item".equals(Link.str(o, "k")) ? Decode.item(o.get("item")) : ItemStack.EMPTY, Link.num(o, "w", 280)));
		}
		for (JsonElement e : arr(m, "inputs")) {
			JsonObject o = e.getAsJsonObject();
			inputs.add(new Input(Link.str(o, "k"), Link.str(o, "key"), Decode.text(o.get("label"), null), o));
		}
		int index = 0;
		for (JsonElement e : arr(m, "buttons")) {
			JsonObject o = e.getAsJsonObject();
			String section = Link.str(o, "section");
			buttons.add(new Btn(index++, Decode.text(o.get("c")), Decode.text(o.get("tip"), null), section.isEmpty() ? null : section, icon(o)));
		}
		for (JsonElement e : arr(m, "sections")) {
			JsonObject o = e.getAsJsonObject();
			List<Btn> list = new ArrayList<>();
			for (JsonElement b : arr(o, "buttons")) {
				JsonObject bo = b.getAsJsonObject();
				list.add(new Btn(index++, Decode.text(bo.get("c")), Decode.text(bo.get("tip"), null), null, icon(bo)));
			}
			sections.add(new Section(Link.str(o, "key"), Decode.text(o.get("c")), list));
		}
		JsonObject exit = m.has("exit") && m.get("exit").isJsonObject() ? m.getAsJsonObject("exit") : null;
		this.exitLabel = exit == null ? null : Decode.text(exit.get("c"), Component.translatable("gui.done"));
		this.exitCallback = exit != null && Link.bool(exit, "cb", false);
	}

	/** A button's icon (an item drawn left of its label), or EMPTY. */
	private static ItemStack icon(JsonObject o) {
		return o.has("icon") ? Decode.item(o.get("icon")) : ItemStack.EMPTY;
	}

	private static JsonArray arr(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	// ------------------------------------------------------------------ messages

	public static void register() {
		Link.on("menu", m -> {
			MenuScreen s = new MenuScreen(m);
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() instanceof MenuScreen old && old.hub == s.hub) {
				// Reopening the same kind of screen (a toggle, a page) keeps the tab the player was on...
				if (s.hub) s.tab = old.tab < s.sections.size() ? old.tab : -1;
				// ...the card a keyboard player was on, and a list's filter.
				boolean same = s.hub || old.title.getString().equals(s.title.getString());
				if (same && old.getFocused() instanceof CardButton c) s.restoreFocus = c.btn.index();
				if (same && !s.hub && old.filter != null) {
					s.restoreFilter = old.filter.getValue();
					s.restoreFilterFocus = old.filter.isFocused();
				}
			}
			mc.gui.setScreen(s);
		});
		Link.on("menu_done", m -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() instanceof MenuScreen s && s.id == Link.num(m, "id", -2)) mc.gui.setScreen(null);
		});
		Link.on("menu_close", m -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() instanceof MenuScreen) mc.gui.setScreen(null);
		});
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void rebuildWidgets() {
		// A resize rebuilds the widgets and clears focus before init() runs: remember what had it.
		if (getFocused() instanceof CardButton c) restoreFocus = c.btn.index();
		if (filter != null) {
			restoreFilter = filter.getValue();
			restoreFilterFocus = filter.isFocused();
		}
		super.rebuildWidgets();
	}

	@Override
	protected void init() {
		// A resize, a GUI scale or a language/resource reload re-inits the screen: lay the text out again.
		bodyLines.clear();
		bodyLinesW = -1;
		titleMax = -1;
		titleLine = null;
		tabW = new int[sections.size() + 1];
		tabLine = new FormattedCharSequence[sections.size() + 1];
		tabFits = new boolean[sections.size() + 1];
		int widest = 0;
		for (Body b : body) widest = Math.max(widest, Math.min(b.width(), 360));
		int grid = (hub ? 2 : columns) * 150 + ((hub ? 2 : columns) - 1) * GAP;
		pw = Mth.clamp(Math.max(grid, widest) + PAD * 2, 220, width - 16);
		layout();

		// Children order is the Tab order: the filter, the inputs, the cards (the exit goes last by its tab group).
		String was = restoreFilter == null ? "" : restoreFilter;
		boolean filterFocus = restoreFilterFocus;
		restoreFilter = null;
		restoreFilterFocus = false;
		filter = null;
		if (!hub && buttons.size() > 10) {
			filter = new EditBox(font, px + pw - 112, py + 5, 102, 14, Component.translatable("aftrhrs.menu.filter"));
			filter.setHint(Component.translatable("aftrhrs.menu.filter").withColor(Theme.SUBTLE));
			filter.setValue(was); // before the responder, so restoring doesn't count as typing
			filter.setResponder(s -> {
				scroll = 0;
				rebuildCards();
			});
			addRenderableWidget(filter);
		}
		inputWidgets.clear();
		for (Input in : inputs) {
			AbstractWidget w = widget(in);
			if (w != null) {
				inputWidgets.put(in.key(), w);
				addRenderableWidget(w);
			}
		}
		exitButton = null;
		exitDim = null;
		if (exitLabel != null) {
			exitButton = new NightButton(Math.min(150, pw - 24), 20, exitLabel, b -> {
				if (waiting) return;
				if (exitCallback) click(1f);
				exit();
			}).fitted().quiet();
			exitButton.setTabOrderGroup(1);
			addRenderableWidget(exitButton);
		}
		cardWidgets.clear();
		cardsOrigin = -1;
		rebuildCards();
		if (filter != null && filterFocus) setFocused(filter);
		Integer keep = restoreFocus;
		restoreFocus = null;
		if (keep != null) for (CardButton c : cardWidgets) if (c.btn.index() == keep) setFocused(c);
	}

	@Override
	protected void setInitialFocus() {
		// Keep a restored focus (vanilla would step on to the next widget from it).
		if (getFocused() == null) super.setInitialFocus();
	}

	/** One widget per card on the current tab, in order; the focused card keeps its focus if it is still shown. */
	private void rebuildCards() {
		Integer keep = getFocused() instanceof CardButton c ? c.btn.index() : null;
		for (CardButton c : cardWidgets) removeWidget(c);
		cardWidgets.clear();
		List<Btn> list = visibleButtons();
		for (int i = 0; i < list.size(); i++) cardWidgets.add(addWidget(new CardButton(list.get(i), i)));
		if (keep != null) for (CardButton c : cardWidgets) if (c.btn.index() == keep) setFocused(c);
	}

	/** Whether the card widgets still match the buttons shown (the filter or tab can't change without a rebuild, but be sure). */
	private boolean cardsMatch(List<Btn> list) {
		if (list.size() != cardWidgets.size()) return false;
		for (int i = 0; i < list.size(); i++) if (cardWidgets.get(i).btn != list.get(i)) return false;
		return true;
	}

	private AbstractWidget widget(Input in) {
		int w = Mth.clamp(Link.num(in.spec(), "w", 200), 60, pw - PAD * 2);
		switch (in.kind()) {
			case "text" -> {
				EditBox box = new EditBox(font, 0, 0, w, 18, in.label() != null ? in.label() : Component.empty());
				box.setMaxLength(Math.max(1, Link.num(in.spec(), "max", 32)));
				Object kept = inputValues.get(in.key());
				box.setValue(kept instanceof String s ? s : Link.str(in.spec(), "initial"));
				box.setResponder(s -> inputValues.put(in.key(), s));
				return box;
			}
			case "bool" -> {
				boolean initial = inputValues.get(in.key()) instanceof Boolean b ? b : Link.bool(in.spec(), "initial", false);
				inputValues.put(in.key(), initial);
				return new NightButton(w, 20, boolLabel(in, initial), btn -> {
					boolean now = !(Boolean) inputValues.get(in.key());
					inputValues.put(in.key(), now);
					btn.setMessage(boolLabel(in, now));
				});
			}
			case "option" -> {
				JsonArray opts = arr(in.spec(), "options");
				if (opts.isEmpty()) return null;
				int start = 0;
				for (int i = 0; i < opts.size(); i++) if (Link.bool(opts.get(i).getAsJsonObject(), "initial", false)) start = i;
				if (inputValues.get(in.key()) instanceof Integer kept) start = kept;
				inputValues.put(in.key(), start);
				return new NightButton(w, 20, optionLabel(in, opts, start), btn -> {
					int next = ((Integer) inputValues.get(in.key()) + 1) % opts.size();
					inputValues.put(in.key(), next);
					btn.setMessage(optionLabel(in, opts, next));
				});
			}
			case "range" -> {
				double min = Link.dbl(in.spec(), "min", 0), max = Link.dbl(in.spec(), "max", 1);
				double step = Link.dbl(in.spec(), "step", 0);
				double initial = inputValues.get(in.key()) instanceof Double d ? d : Link.dbl(in.spec(), "initial", min);
				return new Slider(w, in, min, max, step, initial);
			}
			default -> {
				return null;
			}
		}
	}

	private static Component boolLabel(Input in, boolean on) {
		Component label = in.label() != null ? in.label() : Component.literal(in.key());
		return Component.empty().append(label).append(": ").append(Component.translatable(on ? "options.on" : "options.off"));
	}

	private static Component optionLabel(Input in, JsonArray opts, int i) {
		Component value = Decode.text(opts.get(i).getAsJsonObject().get("c"), Component.literal(Link.str(opts.get(i).getAsJsonObject(), "id")));
		return in.label() == null ? value : Component.empty().append(in.label()).append(": ").append(value);
	}

	/** A number input; the value is snapped to the step and shown like the dialog's label format. */
	private final class Slider extends AbstractSliderButton {
		private final Input in;
		private final double min, max, step;

		Slider(int w, Input in, double min, double max, double step, double initial) {
			super(0, 0, w, 20, Component.empty(), max > min ? (initial - min) / (max - min) : 0);
			this.in = in;
			this.min = min;
			this.max = max;
			this.step = step;
			applyValue();
			updateMessage();
		}

		private double current() {
			double v = min + value * (max - min);
			if (step > 0) v = min + Math.round((v - min) / step) * step;
			return Mth.clamp(v, Math.min(min, max), Math.max(min, max));
		}

		@Override
		protected void updateMessage() {
			double v = current();
			String num = v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
			Component label = in.label() != null ? in.label() : Component.empty();
			setMessage(Component.translatable("options.generic_value", label, Component.literal(num)));
		}

		@Override
		protected void applyValue() {
			inputValues.put(in.key(), current());
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			// A sunken track with a night button as the handle.
			Theme.blit(g, Theme.MENU_INSET, getX(), getY(), getWidth(), getHeight());
			Theme.menuButton(g, getX() + (int) (value * (getWidth() - 8)), getY(), 8, getHeight(), isHoveredOrFocused(), active);
			extractScrollingStringOverContents(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE), getMessage(), 2);
		}
	}

	/** The buttons on the current tab, unfiltered (the hub's home leaves out the groups that have a tab). */
	private List<Btn> tabButtons() {
		List<Btn> list = tab >= 0 && tab < sections.size() ? sections.get(tab).buttons() : buttons;
		if (hub && tab < 0 && !sections.isEmpty()) {
			// The hub's group buttons are the tabs above (those that fit; a group without a tab keeps its button).
			int shown = tabsShown() - 1;
			List<Btn> home = new ArrayList<>();
			for (Btn b : list) {
				boolean tabbed = false;
				for (int i = 0; i < Math.min(shown, sections.size()); i++) if (sections.get(i).key().equals(b.section())) tabbed = true;
				if (b.section() == null || !tabbed) home.add(b);
			}
			list = home;
		}
		return list;
	}

	/** The buttons shown on the current tab, filtered. */
	private List<Btn> visibleButtons() {
		List<Btn> list = tabButtons();
		String q = filter == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return list;
		List<Btn> out = new ArrayList<>();
		for (Btn b : list) if (b.label().getString().toLowerCase(Locale.ROOT).contains(q)) out.add(b);
		return out;
	}

	/** Fits the panel's height to the current tab's content (scrolling only when the screen is too small). */
	private void layout() {
		int tabs = hasTabs() ? TAB_H - 2 : 0;
		int max = Math.min(height - 24 - tabs, 360);
		ph = Mth.clamp(HEADER_H + 6 + measure() + 6 + FOOTER_H, 100, Math.max(100, max));
		px = (width - pw) / 2;
		py = (height - ph + tabs) / 2;
		scroll = 0;
	}

	/** The scrolled area's height for the current tab (unfiltered, so the panel doesn't change with what is typed). */
	private int measure() {
		int cols = hub ? 2 : columns;
		int rows = (tabButtons().size() + cols - 1) / cols;
		return headHeight() + rows * (CARD_H + GAP);
	}

	/** The height of what the home tab shows above the cards (the body and the inputs), as {@link #extractRenderState} draws it. */
	private int headHeight() {
		if (tab >= 0) return 0;
		int innerW = pw - PAD * 2 - 6;
		int h = 0;
		for (Body b : body) {
			int n = bodyLines(b, innerW).size();
			if (!b.item().isEmpty()) {
				h += Math.max(40, Math.max(2, (40 - n * 10) / 2) + n * 10) + 6;
			} else {
				h += n * 10 + 6;
			}
		}
		if (!body.isEmpty()) h += 12;
		for (Input in : inputs) {
			switch (in.kind()) {
				case "text" -> h += (in.label() != null ? 11 : 0) + 18 + 6;
				case "bool", "range" -> h += 20 + 6;
				case "option" -> h += arr(in.spec(), "options").isEmpty() ? 0 : 20 + 6;
				default -> {
				}
			}
		}
		return h;
	}

	/** The first card row's offset in the scrolled content. */
	private int cardsOrigin() {
		return cardsOrigin >= 0 ? cardsOrigin : 6 + headHeight();
	}

	/** {@code b}'s lines for a content width of {@code w} (an item body wraps beside its portrait, 50 narrower). */
	private List<FormattedCharSequence> bodyLines(Body b, int w) {
		if (w != bodyLinesW) {
			bodyLines.clear();
			bodyLinesW = w;
		}
		return bodyLines.computeIfAbsent(b, k -> font.split(k.text(), k.item().isEmpty() ? w : w - 50));
	}

	/** How many tabs (home included) fit on the panel's top edge; {@link #drawTabs} stops at the same place. */
	private int tabsShown() {
		int x = px + PAD, n = 0;
		for (int i = 0; i <= sections.size(); i++) {
			n++;
			x += tabWidth(i) + 2;
			if (x > px + pw - 30) break;
		}
		return n;
	}

	private boolean hasTabs() {
		return hub && !sections.isEmpty();
	}

	private int contentTop() {
		return py + HEADER_H;
	}

	private int contentBottom() {
		return py + ph - FOOTER_H;
	}

	/** Waiting long enough that the other cards dim (the first moments only hold the pressed card). */
	private boolean dimmed() {
		return waiting && Util.getMillis() - waitingSince > DIM_AFTER;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		if (filter != null) {
			filter.setX(px + pw - 112);
			filter.setY(py + 5);
		}
		openTabW = 0;
		hoveredTab = null;
		if (hasTabs()) drawTabs(g, mx, my, false);
		Theme.blit(g, Theme.MENU_PANEL, px, py, pw, ph);
		if (hasTabs()) drawTabs(g, mx, my, true);
		// Header plaque: the title (Feather's banner glyphs render through the server pack's fonts).
		Theme.blit(g, Theme.MENU_HEADER, px + 2, py + 2, pw - 4, HEADER_H - 2);
		// The open tab runs on into the header: its fill covers the header's lit top edge below it.
		if (openTabW > 0) g.fill(openTabX + 2, py + 1, openTabX + openTabW - 2, py + 3, 0xFF2B2344);
		int titleMax = filter != null ? pw - 132 : pw - 24;
		int titleCx = filter != null ? px + 12 + titleMax / 2 : px + pw / 2;
		if (titleMax != this.titleMax || titleLine == null) {
			this.titleMax = titleMax;
			titleLine = Theme.fit(font, title, titleMax);
			titleFits = Theme.fits(font, title, titleMax);
		}
		g.centeredText(font, titleLine, titleCx, py + 9, Theme.TEXT);
		boolean titleCut = !titleFits && Theme.inside(mx, my, titleCx - titleMax / 2, py + 6, titleMax, 12);

		int top = contentTop(), bottom = contentBottom();
		g.enableScissor(px + 2, top, px + pw - 2, bottom);
		int y = top + 6 - (int) scroll;
		int innerX = px + PAD, innerW = pw - PAD * 2 - 6;

		if (tab < 0) {
			for (Body b : body) y = drawBody(g, b, innerX, y, innerW) + 6;
			if (!body.isEmpty()) {
				Theme.blit(g, Theme.MENU_DIVIDER, px + pw / 2 - 60, y - 2, 121, 11);
				y += 12;
			}
			for (Input in : inputs) {
				AbstractWidget w = inputWidgets.get(in.key());
				if (w == null) continue;
				if (in.kind().equals("text") && in.label() != null) {
					g.text(font, in.label(), innerX + 1, y, Theme.MUTED);
					y += 11;
				}
				w.setX(innerX);
				w.setY(y);
				w.visible = y >= top - 2 && y + w.getHeight() <= bottom + 2;
				y += w.getHeight() + 6;
			}
		}

		List<Btn> list = visibleButtons();
		if (!cardsMatch(list)) rebuildCards();
		cardsOrigin = y + (int) scroll - top;
		int cols = hub ? 2 : columns;
		int cardW = (innerW - (cols - 1) * GAP) / cols;
		// The cards are widgets placed here each frame and drawn inside the scissor (so hover stops at its edges).
		for (CardButton c : cardWidgets) {
			int col = c.slot % cols, row = c.slot / cols;
			int cx = innerX + col * (cardW + GAP), cy = y + row * (CARD_H + GAP);
			c.setX(cx);
			c.setY(cy);
			c.setWidth(cardW);
			c.active = !waiting;
			c.visible = true; // off-screen cards stay reachable by Tab (focusing one scrolls it into view)
			if (cy + CARD_H > top && cy < bottom) c.extractRenderState(g, mx, my, a);
		}
		int rows = (list.size() + cols - 1) / cols;
		int end = y + rows * (CARD_H + GAP);
		if (list.isEmpty() && filter != null && !filter.getValue().isBlank()) {
			g.centeredText(font, Component.translatable("aftrhrs.menu.no-match"), px + pw / 2, y + 6, Theme.MUTED);
			end += 20;
		}
		contentHeight = end + (int) scroll - top;
		g.disableScissor();
		int view = bottom - top;
		Theme.scrollbar(g, px + pw - PAD - 4, top + 4, 6, view - 8, scroll, contentHeight, view, Theme.MENU_INSET, Theme.MENU_SCROLLER);

		// Footer: the exit button, like a vanilla "Done" (a widget, drawn by super below); no answer for a while: say so.
		long waited = waiting ? Util.getMillis() - waitingSince : 0;
		boolean noAnswer = waiting && waited > NO_ANSWER_AFTER;
		if (exitButton != null) {
			int ew = exitButton.getWidth();
			exitButton.setX(px + (pw - ew) / 2);
			exitButton.setY(bottom + 5);
			exitButton.active = !waiting;
			exitButton.visible = !noAnswer;
			boolean mine = waiting && pressed == -1;
			exitButton.held = mine;
			exitButton.undimmed = !dimmed();
			boolean dim = dimmed() && !mine;
			exitButton.textColor = dim ? Theme.DIM : Theme.TEXT;
			// An inactive button grays a label that has no color of its own (#A0A0A0, from the first moment of waiting):
			// the label carries its color, so the exit stays white until the rest dims and then dims like them.
			if (exitDim == null || exitDim != dim) {
				exitDim = dim;
				exitButton.setMessage(Component.empty().append(exitLabel).withColor(exitButton.textColor));
			}
		}
		if (noAnswer) g.centeredText(font, Component.translatable("aftrhrs.menu.no-answer"), px + pw / 2, bottom + (exitLabel != null ? 11 : 6), Theme.MUTED);

		super.extractRenderState(g, mx, my, a);

		if (hoveredTab != null) g.setTooltipForNextFrame(font, font.split(hoveredTab, 220), mx, my);
		else if (titleCut) g.setTooltipForNextFrame(font, font.split(title, 220), mx, my);
	}

	@Override
	public void tick() {
		// The server ran the click and had nothing more to show (menu_done lost or never sent).
		if (waiting && Util.getMillis() - waitingSince > GIVE_UP_AFTER) minecraft.gui.setScreen(null);
	}

	private int drawBody(GuiGraphicsExtractor g, Body b, int x, int y, int w) {
		if (!b.item().isEmpty()) {
			// The hub's portrait (pet plushie or head) at double size in a sunken frame, the greeting beside it.
			Theme.blit(g, Theme.MENU_INSET, x, y, 40, 40);
			g.pose().pushMatrix();
			g.pose().translate(x + 4, y + 4);
			g.pose().scale(2f, 2f);
			g.item(b.item(), 0, 0);
			g.pose().popMatrix();
			List<FormattedCharSequence> lines = bodyLines(b, w);
			int ty = y + Math.max(2, (40 - lines.size() * 10) / 2);
			for (FormattedCharSequence line : lines) {
				g.text(font, line, x + 50, ty, Theme.TEXT);
				ty += 10;
			}
			return Math.max(y + 40, ty);
		}
		for (FormattedCharSequence line : bodyLines(b, w)) {
			g.centeredText(font, line, x + w / 2, y, Theme.TEXT);
			y += 10;
		}
		return y;
	}

	/** Tab {@code i}'s width (home is 0): the label plus padding. */
	private int tabWidth(int i) {
		if (tabW[i] == 0) tabW[i] = Math.min(font.width(tabLabel(i)) + 16, 110);
		return tabW[i];
	}

	/** Tab {@code i}'s label fitted to the tab (see {@link #tabFits}). */
	private FormattedCharSequence tabLine(int i) {
		if (tabLine[i] == null) {
			Component label = tabLabel(i);
			int room = tabWidth(i) - 8;
			tabLine[i] = Theme.fit(font, label, room);
			tabFits[i] = Theme.fits(font, label, room);
		}
		return tabLine[i];
	}

	private Component tabLabel(int i) {
		return i == 0 ? Component.translatable("aftrhrs.menu.home") : sections.get(i - 1).label();
	}

	/**
	 * Tabs sit on the panel's top edge like the creative inventory's: the open one is drawn after the panel so it
	 * joins it, the others before, so the panel's rim crosses under them.
	 */
	private void drawTabs(GuiGraphicsExtractor g, int mx, int my, boolean selectedPass) {
		int x = px + PAD;
		for (int i = 0; i <= sections.size(); i++) {
			int w = tabWidth(i);
			boolean active = tab == i - 1;
			if (active == selectedPass) {
				boolean hov = Theme.inside(mx, my, x, py - TAB_H + 2, w, TAB_H);
				int ty = active ? py - TAB_H + 2 : py - TAB_H + 4;
				Theme.blit(g, active ? Theme.MENU_TAB_SELECTED : Theme.MENU_TAB, x, ty, w, active ? TAB_H + 1 : TAB_H - 2);
				if (active) {
					openTabX = x;
					openTabW = w;
				}
				int color = active ? Theme.MOON : hov ? Theme.TEXT : Theme.MUTED;
				g.centeredText(font, tabLine(i), x + w / 2, ty + (active ? 5 : 4), color);
				if (hov && !tabFits[i]) hoveredTab = tabLabel(i);
				if (hov && !active && !waiting) g.requestCursor(CursorTypes.POINTING_HAND);
			}
			x += w + 2;
			if (x > px + pw - 30) break;
		}
	}

	private int tabAt(double mx, double my) {
		if (my < py - TAB_H + 2 || my >= py + 2) return Integer.MIN_VALUE;
		int x = px + PAD;
		for (int i = 0; i <= sections.size(); i++) {
			int w = tabWidth(i);
			if (mx >= x && mx < x + w) return i - 1;
			x += w + 2;
			if (x > px + pw - 30) break;
		}
		return Integer.MIN_VALUE;
	}

	/**
	 * A card as a real button: focusable (Tab, arrows), pressed by Enter, Space or a click, with its tip (and its whole
	 * label when the card cuts it) as a tooltip on hover and on keyboard focus. Placed and drawn by the screen each frame.
	 */
	private final class CardButton extends AbstractButton {
		final Btn btn;
		/** Its place in the list shown (row and column follow from it). */
		final int slot;
		private boolean tipCut;
		/** The label fitted for {@link #fitRoom} with {@link #fitDots} kept free (kept until those change). */
		private int fitRoom = -1, fitDots = -1;
		private FormattedCharSequence fitLine;
		private boolean fitCut;

		CardButton(Btn btn, int slot) {
			super(0, 0, 0, CARD_H, btn.label());
			this.btn = btn;
			this.slot = slot;
			updateTip(false);
		}

		private void updateTip(boolean cut) {
			tipCut = cut;
			Component tip = cut && btn.tip() != null ? Component.empty().append(btn.label()).append("\n").append(btn.tip()) : cut ? btn.label() : btn.tip();
			setTooltip(tip == null ? null : Tooltip.create(tip));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			if (!waiting) press(btn);
		}

		@Override
		public void playDownSound(SoundManager sounds) {
			// press() makes the menu's own click
		}

		@Override
		public boolean isMouseOver(double x, double y) {
			// a card scrolled under the header or the footer can't be clicked through them
			return super.isMouseOver(x, y) && y >= contentTop() && y < contentBottom();
		}

		@Override
		protected void handleCursor(GuiGraphicsExtractor g) {
			if (isHovered() && active) g.requestCursor(CursorTypes.POINTING_HAND);
		}

		@Override
		protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor g, int mouseX, int mouseY) {
			if (!waiting) super.extractTooltipForNextRenderPass(g, mouseX, mouseY);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			int x = getX(), y = getY(), w = getWidth(), h = getHeight();
			boolean mine = waiting && btn.index() == pressed;
			boolean dim = dimmed();
			boolean lit = !waiting && (isHovered() || isFocused() && minecraft.getLastInputType().isKeyboard());
			Theme.menuButton(g, x, y, w, h, lit || mine, mine || !dim);
			ItemStack icon = btn.icon();
			// With an icon: the item at the left, the label centered in the rest of the card.
			int left = icon.isEmpty() ? x : x + 20;
			if (!icon.isEmpty()) g.item(icon, x + 4, y + (h - 16) / 2);
			int room = x + w - left - 10;
			// While it waits, the pressed card counts dots after its label (kept inside the card).
			String dots = mine && dim ? ".".repeat((int) (Util.getMillis() / 300 % 4)) : "";
			int dotsW = mine && dim ? font.width("...") + 1 : 0;
			if (room != fitRoom || dotsW != fitDots) {
				fitRoom = room;
				fitDots = dotsW;
				fitCut = !Theme.fits(font, btn.label(), room);
				fitLine = Theme.fit(font, btn.label(), room - dotsW);
			}
			if (fitCut != tipCut) updateTip(fitCut);
			FormattedCharSequence line = fitLine;
			int cx = (left + x + w) / 2 - dotsW / 2;
			g.centeredText(font, line, cx, y + 6, dim && !mine ? Theme.DIM : Theme.TEXT);
			if (!dots.isEmpty()) g.text(font, dots, cx + (font.width(line) + 1) / 2 + 1, y + 6, Theme.MUTED);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	// ------------------------------------------------------------------ input

	@Override
	public void setFocused(GuiEventListener l) {
		// a card a rebuild already removed (it was pressed and the tab changed) doesn't take the focus back
		if (l instanceof CardButton c && !cardWidgets.contains(c)) l = null;
		super.setFocused(l);
		if (l instanceof CardButton c) reveal(c);
	}

	/** Scrolls so the card is in view (with a little margin). */
	private void reveal(CardButton c) {
		int cols = hub ? 2 : columns;
		int cy = cardsOrigin() + c.slot / cols * (CARD_H + GAP);
		int view = contentBottom() - contentTop();
		if (cy - 6 < scroll) scroll = cy - 6;
		else if (cy + CARD_H + 6 > scroll + view) scroll = cy + CARD_H + 6 - view;
		int content = Math.max(contentHeight, cardsOrigin() + (cardWidgets.size() + cols - 1) / cols * (CARD_H + GAP));
		scroll = Mth.clamp(scroll, 0, Math.max(0, content - view));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		int view = contentBottom() - contentTop();
		scroll = Mth.clamp(scroll - sy * 16, 0, Math.max(0, contentHeight - view));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The cards, the exit and the inputs are widgets: super reaches them (none is active while waiting).
		if (super.mouseClicked(event, doubleClick)) return true;
		if (waiting || event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		if (hasTabs()) {
			int t = tabAt(event.x(), event.y());
			if (t != Integer.MIN_VALUE) {
				switchTab(t);
				return true;
			}
		}
		return false;
	}

	private void switchTab(int t) {
		if (tab == t) return;
		tab = t;
		layout();
		rebuildCards();
		click(1.1f);
	}

	private void press(Btn b) {
		if (b.section() != null && hub) {
			for (int i = 0; i < sections.size(); i++) {
				if (sections.get(i).key().equals(b.section())) {
					switchTab(i);
					return;
				}
			}
		}
		click(1f);
		send(b.index());
	}

	private void send(int index) {
		JsonObject o = Link.message("menu_click");
		o.addProperty("id", id);
		o.addProperty("button", index);
		JsonObject values = new JsonObject();
		for (Input in : inputs) {
			Object v = inputValues.get(in.key());
			switch (in.kind()) {
				case "text" -> values.addProperty(in.key(), v instanceof String s ? s : Link.str(in.spec(), "initial"));
				case "bool" -> values.addProperty(in.key(), v instanceof Boolean b && b);
				case "range" -> values.addProperty(in.key(), v instanceof Double d ? d : Link.dbl(in.spec(), "initial", 0));
				case "option" -> {
					JsonArray opts = arr(in.spec(), "options");
					int i = v instanceof Integer n ? n : 0;
					if (i < opts.size()) values.addProperty(in.key(), Link.str(opts.get(i).getAsJsonObject(), "id"));
				}
				default -> {
				}
			}
		}
		o.add("values", values);
		Link.send(o);
		waiting = true;
		pressed = index;
		waitingSince = Util.getMillis();
	}

	private void exit() {
		if (exitCallback) {
			send(-1);
		} else {
			closeQuietly();
		}
	}

	private void closeQuietly() {
		JsonObject o = Link.message("menu_closed");
		o.addProperty("id", id);
		Link.send(o);
		minecraft.gui.setScreen(null);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			if (waiting) {
				// The server took the click (its pending entry is spent): after a moment Escape just closes.
				if (Util.getMillis() - waitingSince > DIM_AFTER) minecraft.gui.setScreen(null);
				return true;
			}
			if (hub && tab >= 0) {
				switchTab(-1);
				return true;
			}
			if (exitCallback) click(1f);
			exit();
			return true;
		}
		if (waiting) return true;
		// Hub tabs from the keyboard, like vanilla's tab bars: Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+1..9 (only the tabs drawn).
		if (hasTabs() && event.hasControlDown()) {
			int shown = tabsShown();
			if (event.key() == InputConstants.KEY_TAB) {
				switchTab(Math.floorMod(tab + 1 + (event.hasShiftDown() ? -1 : 1), shown) - 1);
				return true;
			}
			int d = event.key() - InputConstants.KEY_1;
			if (d >= 0 && d <= 8) {
				if (d < shown) switchTab(d - 1);
				return true;
			}
		}
		// Enter in a text box presses the card only when it is the only one (never "the first of many").
		if (event.isConfirmation() && getFocused() instanceof EditBox && cardWidgets.size() == 1) {
			press(cardWidgets.getFirst().btn);
			return true;
		}
		// The filter takes the keys it uses; Tab, Up and Down still move the focus.
		if (filter != null && filter.isFocused() && filter.keyPressed(event)) return true;
		return super.keyPressed(event);
	}

	private void click(float pitch) {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.35f));
	}
}
