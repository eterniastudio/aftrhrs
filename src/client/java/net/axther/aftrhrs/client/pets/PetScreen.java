package net.axther.aftrhrs.client.pets;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.entity.CreatureView;
import net.axther.aftrhrs.client.ui.HitButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Companion's pet menu: a pastel "pet nursery" with two tabs (pet management only; the creature codex is its own
 * screen, {@code client/codex}, reached from a pet's "Codex" button, /codex, the hub or its key).
 * <ul>
 *   <li><b>My Pets</b>: a roster of cards with little live models, and a stage where the chosen pet stands on a grassy
 *   island, spinning slowly (drag to turn it, click to give it a pat: it plays its happy animation and hearts float
 *   up). Bond hearts, XP, health, perks and every action the vanilla menu has.</li>
 *   <li><b>Stable</b>: one wooden stall per stable slot, parked pets asleep in the hay, "Bring out" on each.</li>
 * </ul>
 * Every action is a request; Feather does the real thing and answers with a fresh snapshot.
 */
public final class PetScreen extends Screen {
	public enum Tab {
		PETS("pets"), STABLE("stable");

		final String id;

		Tab(String id) {
			this.id = id;
		}

		public static Tab of(String id) {
			for (Tab t : values()) if (t.id.equals(id)) return t;
			return PETS;
		}
	}

	// ------------------------------------------------------------------ look
	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath(Companion.ID, "pets/" + name);
	}

	static final Identifier PANEL = sprite("panel"), CARD = sprite("card"), CARD_HOVER = sprite("card_hover"), CARD_SELECTED = sprite("card_selected"),
		BUTTON = sprite("button"), BUTTON_HOVER = sprite("button_hover"), BUTTON_MINT = sprite("button_mint"), BUTTON_MINT_HOVER = sprite("button_mint_hover"),
		BUTTON_OFF = sprite("button_disabled"), TAB = sprite("tab"), TAB_ON = sprite("tab_selected"), STAGE = sprite("stage"), PEDESTAL = sprite("pedestal"),
		STALL = sprite("stall"), INSET = sprite("inset"), HEART = sprite("heart_full"), HEART_EMPTY = sprite("heart_empty"), PAW = sprite("paw"),
		STAR = sprite("star"), LOCK = sprite("lock"), BAR = sprite("bar");
	/** A fallen pet in gray. */
	static final int GRAY = 0xFF8A8090;
	static final int INK = 0xFF6B4A57, PINK = 0xFFB8466F, SOFT = 0xFF7A6068, MINT = 0xFF2A735A, WHITE = 0xFFFFFFFF, RED = 0xFFA93834;
	/** The heart glyph next to the health bar (the bar keeps its brighter fill). */
	static final int HP = 0xFFC0405E;

	private static final int TAB_H = 16;

	private Tab tab;
	private int px, py, pw, ph;
	private String selected;
	private float roster;
	private float yaw = CreatureView.DEFAULT_YAW;
	private boolean dragging;
	private double dragX;
	private long lastSpin = Util.getMillis();
	private final long opened = Util.getMillis();
	private final List<float[]> hearts = new ArrayList<>();
	private final List<Btn> buttons = new ArrayList<>();
	private String tip;
	/** "View in Codex" was clicked and the codex is on its way: the link waits (no double requests) until then. */
	private long codexPendingUntil;
	/** The open tab's list scroll bar as last drawn (x, y, height, content, view), and whether it is being dragged. */
	private final int[] listBar = new int[5];
	private boolean draggingBar;

	private enum Popup {NONE, RENAME, COLOR, RELEASE}

	private Popup popup = Popup.NONE;
	/** The stabled pet a popup was opened for (from its stall), or null for the selected pet. */
	private String popupFor;
	private EditBox renameBox;

	/** A button this frame; {@code id} names its widget, which lives as long as the button keeps being drawn. */
	private record Btn(String id, int x, int y, int w, int h, Component label, boolean mint, boolean active, Runnable run, String tip) {
	}

	/**
	 * The clickable buttons as widgets (Tab, the arrows, Enter or Space, the Narrator), by {@link Btn#id}, in the order
	 * drawn; a widget whose button wasn't drawn this frame goes. While a popup is open only its buttons are widgets.
	 */
	private final Map<String, HitButton> widgets = new LinkedHashMap<>();
	/** This frame's buttons by id (a widget presses the one drawn last), and the ids in drawing order. */
	private final Map<String, Btn> live = new HashMap<>();
	private final List<String> drawn = new ArrayList<>();

	public PetScreen(Tab tab) {
		super(Component.translatable("aftrhrs.pets.title"));
		this.tab = tab;
	}

	public static void openTab(Tab tab) {
		PetData.request(tab == Tab.PETS ? null : tab.id);
	}

	@Override
	protected void init() {
		pw = Math.min(width - 16, 430);
		ph = Math.min(height - 24, 254);
		px = (width - pw) / 2;
		py = (height - ph) / 2 + TAB_H / 2;
		renameBox = new EditBox(font, 0, 0, 150, 18, Component.translatable("aftrhrs.pets.rename"));
		renameBox.setMaxLength(24);
		renameBox.visible = false;
		addRenderableWidget(renameBox);
		// Back from the codex (or a resize): the link works again, and the stage doesn't jump by the time spent away.
		codexPendingUntil = 0;
		lastSpin = Util.getMillis();
	}

	/** A new snapshot arrived (after an action, or Feather opened a menu): keep the selection if the pet is still there. */
	void dataChanged(Tab open) {
		if (open != null && open != tab) switchTab(open, false);
		if (popup != Popup.NONE && popupPet() == null) closePopup();
	}

	/** The pet the open popup acts on: the stall it came from, else the selected pet. */
	private PetData.Pet popupPet() {
		if (popupFor == null) return current();
		for (PetData.Pet p : PetData.INSTANCE.pets) if (p.id().equals(popupFor)) return p;
		return null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		buttons.clear();
		live.clear();
		drawn.clear();
		tip = null;
		long now = Util.getMillis();
		if (!dragging) yaw += (now - lastSpin) * 0.012f;
		lastSpin = now;
		drawTabs(g, mx, my, false);
		blit(g, PANEL, px, py, pw, ph);
		drawTabs(g, mx, my, true);
		switch (tab) {
			case PETS -> drawPets(g, mx, my, now);
			case STABLE -> drawStable(g, mx, my, now);
		}
		boolean modal = popup != Popup.NONE;
		for (Btn b : buttons) {
			if (modal) drawButton(g, b, -1, my);
			else drawWidget(g, b, mx, my, a);
		}
		if (modal) {
			// Only the popup's own tips: what the hearts and badges underneath set this frame doesn't show through it.
			tip = null;
			drawPopup(g, mx, my, a);
		}
		syncWidgets();
		drawHearts(g, now);
		super.extractRenderState(g, mx, my, a);
		if (tip != null) g.setTooltipForNextFrame(font, font.split(Component.literal(tip), 200), mx, my);
	}

	private void drawTabs(GuiGraphicsExtractor g, int mx, int my, boolean front) {
		int x = px + 12;
		for (Tab t : Tab.values()) {
			Component label = Component.translatable("aftrhrs.pets.tab." + t.id);
			int w = font.width(label) + 22;
			boolean on = t == tab;
			if (on == front) {
				blit(g, on ? TAB_ON : TAB, x, py - TAB_H + (on ? 2 : 4), w, TAB_H + (on ? 1 : -2));
				g.text(font, label, x + 11, py - TAB_H + (on ? 7 : 8), on ? PINK : INK, false);
			}
			if (!on && Theme2.inside(mx, my, x, py - TAB_H + 4, w, TAB_H - 4)) g.requestCursor(CursorTypes.POINTING_HAND);
			x += w + 3;
		}
	}

	private int tabAt(double mx, double my) {
		int x = px + 12, i = 0;
		for (Tab t : Tab.values()) {
			int w = font.width(Component.translatable("aftrhrs.pets.tab." + t.id)) + 22;
			if (Theme2.inside(mx, my, x, py - TAB_H + 2, w, TAB_H)) return i;
			x += w + 3;
			i++;
		}
		return -1;
	}

	// ------------------------------------------------------------------ My Pets

	private List<PetData.Pet> roster() {
		List<PetData.Pet> out = new ArrayList<>();
		for (PetData.Pet p : PetData.INSTANCE.pets) if (!p.stabled()) out.add(p);
		return out;
	}

	private PetData.Pet current() {
		List<PetData.Pet> list = roster();
		if (list.isEmpty()) return null;
		for (PetData.Pet p : list) if (p.id().equals(selected)) return p;
		selected = list.getFirst().id();
		return list.getFirst();
	}

	private void drawPets(GuiGraphicsExtractor g, int mx, int my, long now) {
		PetData data = PetData.INSTANCE;
		List<PetData.Pet> list = roster();
		int listX = px + 10, listY = py + 10, listW = 176, listH = ph - 42;
		int cardW = 86, cardH = 42, cols = 2;
		g.text(font, Component.translatable("aftrhrs.pets.count", list.size()), listX + 2, listY, PINK, false);
		int top = listY + 12, bottom = listY + listH;
		int rows = (list.size() + cols - 1) / cols;
		float maxScroll = Math.max(0, rows * (cardH + 4) - (bottom - top));
		roster = Mth.clamp(roster, 0, maxScroll);
		g.enableScissor(listX, top, listX + listW, bottom);
		PetData.Pet cur = current();
		for (int i = 0; i < list.size(); i++) {
			PetData.Pet p = list.get(i);
			int cx = listX + (i % cols) * (cardW + 4), cy = top + (i / cols) * (cardH + 4) - (int) roster;
			if (cy + cardH < top || cy > bottom) continue;
			boolean hov = popup == Popup.NONE && Theme2.inside(mx, my, cx, cy, cardW, cardH) && my >= top && my < bottom;
			blit(g, p == cur ? CARD_SELECTED : hov ? CARD_HOVER : CARD, cx, cy, cardW, cardH);
			model(g, "card:" + p.id(), p.type(), look(p), p.plush(), cx + 2, cy + 4, cx + 30, cy + cardH - 4, CreatureView.DEFAULT_YAW + (hov ? (now - opened) * 0.06f : 0),
				CreatureView.DEFAULT_PITCH, anim(p), now, p.fallen() ? GRAY : -1, hov ? 0 : 33);
			String name = net.axther.aftrhrs.client.Theme.ellipsize(font, p.name(), cardW - 34);
			g.text(font, name, cx + 31, cy + 6, INK, false);
			g.text(font, net.axther.aftrhrs.client.Theme.ellipsize(font, p.speciesName(), cardW - 34), cx + 31, cy + 16, SOFT, false);
			int hx = cx + 31;
			for (int h = 0; h < Math.min(5, p.maxLevel()); h++) {
				blit(g, h < p.level() ? HEART : HEART_EMPTY, hx + h * 8, cy + 27, 9, 9);
			}
			if (p.shiny()) blit(g, STAR, cx + cardW - 12, cy + 4, 9, 9);
			if (p.fallen()) g.text(font, "✝", cx + cardW - 10, cy + 28, RED, false);
			if (hov) g.requestCursor(CursorTypes.POINTING_HAND);
		}
		g.disableScissor();
		bar(g, listX + listW + 1, top, bottom - top, rows * (cardH + 4));
		if (list.isEmpty()) {
			net.axther.aftrhrs.client.Theme.centeredFlat(g, font, Component.translatable("aftrhrs.pets.none"), listX + listW / 2, top + 30, SOFT);
		}
		// Bring everyone.
		buttons.add(new Btn("bring-all", listX, py + ph - 28, listW, 18, Component.translatable("aftrhrs.pets.bring-all"), true, data.active(),
			() -> {
				net.axther.aftrhrs.client.net.Link.send(net.axther.aftrhrs.client.net.Link.message("pets_bring_all"));
				chime(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.4f);
			}, null));
		if (cur != null) drawStage(g, mx, my, now, cur);
	}

	private void drawStage(GuiGraphicsExtractor g, int mx, int my, long now, PetData.Pet p) {
		int sx = px + 192, sy = py + 10, sw = pw - 202, sh = stageHeight();
		blit(g, STAGE, sx, sy, sw, sh);
		int pedW = Math.min(sw - 20, 150);
		blit(g, PEDESTAL, sx + (sw - pedW) / 2, sy + sh - 30, pedW, 30);
		boolean hover = popup == Popup.NONE && Theme2.inside(mx, my, sx, sy, sw, sh);
		model(g, "stage:" + p.id(), p.type(), look(p), p.plush(), sx + 20, sy + 6, sx + sw - 20, sy + sh - 18, yaw, CreatureView.DEFAULT_PITCH, anim(p), now,
			p.fallen() ? GRAY : -1, 0);
		if (hover) {
			g.requestCursor(dragging ? CursorTypes.RESIZE_EW : CursorTypes.POINTING_HAND);
			// the hint as a caption on the pedestal, drawn after the model so it never covers the pet; a soft backing keeps
			// it readable over the grass and dirt
			if (!dragging) {
				String hint = net.axther.aftrhrs.client.Theme.ellipsize(font, Component.translatable("aftrhrs.pets.pat").getString(), sw - 16);
				int hw = font.width(hint), cx = sx + sw / 2, hy = sy + sh - 11;
				g.fill(cx - hw / 2 - 3, hy - 2, cx + hw - hw / 2 + 3, hy + 9, 0xC8FFF7EE);
				net.axther.aftrhrs.client.Theme.centeredFlat(g, font, hint, cx, hy, SOFT);
			}
		}
		if (p.shiny()) {
			for (int i = 0; i < 3; i++) {
				double t = (now / 700.0 + i * 2.1);
				int x = sx + sw / 2 + (int) (Math.cos(t) * (sw / 3.0)), y = sy + 20 + (int) (Math.sin(t * 1.3) * 20) + i * 18;
				blit(g, STAR, x, y, 9, 9);
			}
		}
		// Name, species, bond.
		int y = sy + sh + 5;
		String name = (p.shiny() ? "✦ " : "") + p.name();
		Component edit = Component.translatable("aftrhrs.pets.edit");
		// The name stops short of the edit button and the bond hearts on the right.
		net.minecraft.util.FormattedCharSequence nameLine = net.axther.aftrhrs.client.Theme.clip(font, Component.literal(name).withStyle(ChatFormatting.BOLD),
			sw - 5 * 10 - font.width(edit) - 20);
		g.text(font, nameLine, sx + 2, y, p.color() != null ? 0xFF000000 | p.color() : PINK, false);
		int nameW = font.width(nameLine);
		buttons.add(new Btn("edit", sx + nameW + 6, y - 2, font.width(edit) + 8, 12, edit, false, true, () -> openPopup(Popup.RENAME), Component.translatable("aftrhrs.pets.rename").getString()));
		String species = p.speciesName() + (p.variant() != null ? " · " + p.variant().name() : "");
		Component codex = Component.translatable("aftrhrs.pets.codex-link");
		boolean codexOn = PetData.INSTANCE.codexEnabled;
		int codexW = codexOn ? font.width(codex) + 8 : 0;
		g.text(font, net.axther.aftrhrs.client.Theme.ellipsize(font, species, sw - 70 - codexW), sx + 2, y + 11, SOFT, false);
		// "View in Codex": the species' page of the creature codex (its own screen; Escape there comes back here)
		if (codexOn) {
			buttons.add(new Btn("codex", sx + 2 + Math.min(font.width(species), sw - 70 - codexW) + 4, y + 9, codexW, 12, codex, false, now >= codexPendingUntil,
				() -> {
					closePopup();
					codexPendingUntil = Util.getMillis() + 1500;
					net.axther.aftrhrs.client.codex.CodexData.request(null, p.species(), this);
					chime(SoundEvents.BOOK_PAGE_TURN, 1.2f);
				}, Component.translatable("aftrhrs.pets.codex-link-tip").getString()));
		}
		// Bond hearts + XP bar on the right.
		int hx = sx + sw - 5 * 10;
		for (int h = 0; h < Math.min(5, p.maxLevel()); h++) blit(g, h < p.level() ? HEART : HEART_EMPTY, hx + h * 10, y, 9, 9);
		bar(g, hx, y + 12, 48, p.bond(), 0xFFF29BB8, 0xFFFFD3E2);
		if (Theme2.inside(mx, my, hx, y, 50, 18)) {
			tip = p.xpNext() < 0 ? Component.translatable("aftrhrs.pets.bond-max", p.level()).getString()
				: Component.translatable("aftrhrs.pets.bond", p.level(), p.xp() - p.xpLevel(), p.xpNext() - p.xpLevel()).getString();
		}
		// Health and where.
		int ly = y + 24;
		if (p.maxHealth() > 0 && !p.fallen()) {
			g.text(font, "❤", sx + 2, ly, HP, false);
			bar(g, sx + 12, ly + 2, 60, (float) (p.health() / p.maxHealth()), 0xFFE0607E, 0xFFFFC2CF);
			g.text(font, String.format(Locale.ROOT, "%.0f/%.0f", p.health(), p.maxHealth()), sx + 76, ly, SOFT, false);
		}
		Component status = Component.translatable("aftrhrs.pets.state." + (p.fallen() ? "fallen" : p.working() ? "working" : p.staying() ? "staying" : "following"));
		int stW = font.width(status) + 10;
		blit(g, INSET, sx + sw - stW, ly - 2, stW, 12);
		g.text(font, status, sx + sw - stW + 5, ly, p.fallen() ? RED : p.working() ? MINT : INK, false);
		if (!p.where().isEmpty()) g.text(font, net.axther.aftrhrs.client.Theme.ellipsize(font, "⌖ " + p.where(), sw - 4), sx + 2, ly + 12, SOFT, false);
		// Perks as little paw badges.
		int kx = sx + 2, ky = ly + 24;
		for (PetData.Perk k : p.perks()) {
			blit(g, k.unlocked() ? PAW : LOCK, kx, ky, 9, 9);
			if (Theme2.inside(mx, my, kx, ky, 9, 9)) {
				tip = k.name() + (k.unlocked() ? "" : " (" + Component.translatable("aftrhrs.pets.perk-at", k.at()).getString() + ")") + "\n" + k.desc();
			}
			kx += 12;
		}
		if (!p.breeding().isEmpty()) g.text(font, net.axther.aftrhrs.client.Theme.ellipsize(font, p.breeding(), sw - (kx - sx) - 6), kx + 4, ky + 1, SOFT, false);
		actions(p, sx, py + ph - 48, sw);
	}

	/** The stage shrinks on small screens so the details and buttons below it still fit. */
	private int stageHeight() {
		return Mth.clamp(ph - 136, 64, 118);
	}

	private void actions(PetData.Pet p, int x, int y, int w) {
		List<Btn> row = new ArrayList<>();
		int bw = (w - 8) / 3;
		if (p.fallen()) {
			row.add(btn("revive", Component.translatable("aftrhrs.pets.revive"), true, () -> act(p, "revive", SoundEvents.TOTEM_USE)));
		} else {
			row.add(btn("summon", Component.translatable("aftrhrs.pets.summon"), true, () -> act(p, "summon", SoundEvents.NOTE_BLOCK_CHIME.value())));
			// A grayed button says why on hover (from what the snapshot already tells).
			String stayWhy = p.loaded() ? null : tr("aftrhrs.pets.why.unloaded");
			String workWhy = p.working() ? tr("aftrhrs.pets.why.working") : !p.loaded() ? tr("aftrhrs.pets.why.unloaded")
				: !p.canWork() ? tr("aftrhrs.pets.why.work-off") : null;
			row.add(btn("stay", Component.translatable(p.staying() ? "aftrhrs.pets.follow" : "aftrhrs.pets.stay"), false, p.loaded(),
				() -> act(p, p.staying() ? "follow" : "stay", SoundEvents.NOTE_BLOCK_HAT.value()), stayWhy));
			row.add(btn("work", Component.translatable("aftrhrs.pets.work"), false, p.canWork() && !p.working(), () -> act(p, "work", SoundEvents.NOTE_BLOCK_BELL.value()),
				workWhy));
			row.add(btn("stable", Component.translatable("aftrhrs.pets.stable"), false, () -> act(p, "stable", SoundEvents.WOOD_PLACE)));
		}
		// Name colors come with the Affection perk (its name and level are the server's).
		PetData.Perk aff = p.perks().stream().filter(k -> "affection".equals(k.id())).findFirst().orElse(null);
		String colorWhy = p.canColor() ? null : aff != null && !aff.unlocked() ? tr("aftrhrs.pets.why.no-color", aff.name(), aff.at())
			: tr("aftrhrs.pets.why.color-off");
		row.add(btn("color", Component.translatable("aftrhrs.pets.color"), false, p.canColor(), () -> openPopup(Popup.COLOR), colorWhy));
		row.add(btn("release", Component.translatable("aftrhrs.pets.release"), false, () -> openPopup(Popup.RELEASE)));
		for (int i = 0; i < row.size(); i++) {
			Btn b = row.get(i);
			int bx = x + (i % 3) * (bw + 4), by = y + (i / 3) * 20;
			buttons.add(new Btn("act:" + b.id, bx, by, bw, 18, b.label, b.mint, b.active, b.run, b.tip));
		}
	}

	private static Btn btn(String id, Component label, boolean mint, Runnable run) {
		return new Btn(id, 0, 0, 0, 0, label, mint, true, run, null);
	}

	private static Btn btn(String id, Component label, boolean mint, boolean active, Runnable run, String tip) {
		return new Btn(id, 0, 0, 0, 0, label, mint, active, run, tip);
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	private void act(PetData.Pet p, String action, SoundEvent sound) {
		PetData.action(p, action, null, null);
		chime(sound, 1.2f);
	}

	// ------------------------------------------------------------------ Stable

	private void drawStable(GuiGraphicsExtractor g, int mx, int my, long now) {
		PetData data = PetData.INSTANCE;
		List<PetData.Pet> parked = new ArrayList<>();
		for (PetData.Pet p : data.pets) if (p.stabled()) parked.add(p);
		int cap = data.capStable < 0 ? Math.max(9, parked.size() + 3) : data.capStable;
		String capText = data.capStable < 0 ? "∞" : String.valueOf(data.capStable);
		// Kept inside the panel (at GUI 4 on a 1280-wide window the hint is wider than the panel).
		g.text(font, net.axther.aftrhrs.client.Theme.fit(font, Component.translatable("aftrhrs.pets.stable.title", parked.size(), capText), pw - 24), px + 12, py + 10, PINK, false);
		g.text(font, net.axther.aftrhrs.client.Theme.fit(font, Component.translatable("aftrhrs.pets.stable.hint"), pw - 24), px + 12, py + 21, SOFT, false);
		int cols = 4, cellW = (pw - 24 - (cols - 1) * 6) / cols, cellH = 74;
		int top = py + 34, bottom = py + ph - 8;
		int rows = (cap + cols - 1) / cols;
		float maxScroll = Math.max(0, rows * (cellH + 6) - (bottom - top));
		roster = Mth.clamp(roster, 0, maxScroll);
		g.enableScissor(px + 4, top, px + pw - 4, bottom);
		for (int i = 0; i < cap; i++) {
			int cx = px + 12 + (i % cols) * (cellW + 6), cy = top + (i / cols) * (cellH + 6) - (int) roster;
			if (cy + cellH < top || cy > bottom) continue;
			blit(g, STALL, cx, cy, cellW, cellH);
			if (i >= parked.size()) {
				g.centeredText(font, net.axther.aftrhrs.client.Theme.fit(font, Component.translatable("aftrhrs.pets.stable.empty"), cellW - 6), cx + cellW / 2, cy + cellH / 2 - 4, 0xAAFFF6EC);
				continue;
			}
			PetData.Pet p = parked.get(i);
			String a = CreatureView.firstClip(p.type(), "sleep", "lie_down", "sit");
			model(g, "stall:" + p.id(), p.type(), look(p), p.plush(), cx + 6, cy + 4, cx + cellW - 6, cy + cellH - 18, CreatureView.DEFAULT_YAW, CreatureView.DEFAULT_PITCH, a, now, -1, 33);
			if (a.isEmpty() || a.startsWith("sleep")) {
				float z = ((now + i * 400) % 2400) / 2400f;
				g.text(font, "z", cx + cellW - 16 + (int) (z * 6), cy + 14 - (int) (z * 10), (Math.round((1 - z) * 255) << 24) | 0xFFFFFF, false);
			}
			// The nameplate is the dark band along the stall's foot (rows cellH-16..cellH-6), inside its frame.
			g.centeredText(font, net.axther.aftrhrs.client.Theme.ellipsize(font, p.name(), cellW - 12), cx + cellW / 2, cy + cellH - 14, WHITE);
			// Only while the stall's button is in view: a stall scrolled half out can't be used through the panel's edge.
			boolean hov = popup == Popup.NONE && Theme2.inside(mx, my, cx, cy, cellW, cellH) && my >= top && my < bottom
				&& cy + cellH - 30 >= top && cy + cellH - 16 <= bottom;
			if (hov) {
				buttons.add(new Btn("stall:" + p.id() + ":bring", cx + 6, cy + cellH - 30, cellW - 12, 14, Component.translatable("aftrhrs.pets.bring-out"), true, true,
					() -> act(p, "bring_out", SoundEvents.NOTE_BLOCK_CHIME.value()), null));
				// Rename and release work in the stable too (Feather allows both for stabled pets): side by side when both
				// labels fit (4px a side, as drawButton leaves), else stacked full width (Bring out starts at cy+cellH-30, clear).
				if (cy + 4 >= top) {
					Component ren = Component.translatable("aftrhrs.pets.rename"), rel = Component.translatable("aftrhrs.pets.release");
					int room = cellW - 12; // the same inner width as Bring out
					int wRen = font.width(ren) + 8, wRel = font.width(rel) + 8;
					Runnable doRen = () -> openPopup(Popup.RENAME, p), doRel = () -> openPopup(Popup.RELEASE, p);
					if (wRen + 2 + wRel <= room) {
						wRen += (room - 2 - wRen - wRel) / 2;
						buttons.add(new Btn("stall:" + p.id() + ":rename", cx + 6, cy + 4, wRen, 12, ren, false, true, doRen, null));
						buttons.add(new Btn("stall:" + p.id() + ":release", cx + 8 + wRen, cy + 4, room - 2 - wRen, 12, rel, false, true, doRel, null));
					} else {
						buttons.add(new Btn("stall:" + p.id() + ":rename", cx + 6, cy + 4, room, 12, ren, false, true, doRen, null));
						buttons.add(new Btn("stall:" + p.id() + ":release", cx + 6, cy + 18, room, 12, rel, false, true, doRel, null));
					}
				}
			}
		}
		g.disableScissor();
		bar(g, px + pw - 9, top, bottom - top, rows * (cellH + 6));
	}

	/** The open tab's list scroll bar (in its gutter; nothing when the list fits), kept for clicking and dragging it. */
	private void bar(GuiGraphicsExtractor g, int x, int y, int h, int content) {
		listBar[0] = x;
		listBar[1] = y;
		listBar[2] = h;
		listBar[3] = content;
		listBar[4] = h;
		net.axther.aftrhrs.client.Theme.scrollbar(g, x, y, 3, h, roster, content, h, 0x30D9668F, 0xAAD9668F);
	}

	private boolean onBar(double mx, double my) {
		return listBar[3] > listBar[4] && Theme2.inside(mx, my, listBar[0] - 1, listBar[1], 5, listBar[2]);
	}

	// ------------------------------------------------------------------ popups

	private void openPopup(Popup p) {
		openPopup(p, null);
	}

	private void openPopup(Popup p, PetData.Pet target) {
		closePopup();
		popup = p;
		popupFor = target == null ? null : target.id();
		PetData.Pet cur = popupPet();
		if (p == Popup.RENAME && cur != null) {
			renameBox.setValue(cur.custom() ? cur.name() : "");
			renameBox.visible = true;
			setFocused(renameBox);
		}
		chime(SoundEvents.UI_BUTTON_CLICK.value(), 1.3f);
	}

	private void closePopup() {
		popup = Popup.NONE;
		popupFor = null;
		renameBox.visible = false;
		setFocused(null);
	}

	private void drawPopup(GuiGraphicsExtractor g, int mx, int my, float a) {
		PetData.Pet p = popupPet();
		if (p == null) {
			closePopup();
			return;
		}
		g.fill(px, py, px + pw, py + ph, 0x66FFE6EF);
		int w = 200, h = popup == Popup.COLOR ? 76 : 70, x = (width - w) / 2, y = (height - h) / 2;
		blit(g, PANEL, x, y, w, h);
		List<Btn> local = new ArrayList<>();
		List<Runnable> swatches = new ArrayList<>();
		switch (popup) {
			case RENAME -> {
				net.axther.aftrhrs.client.Theme.centeredFlat(g, font, net.axther.aftrhrs.client.Theme.fit(font, Component.translatable("aftrhrs.pets.rename-title", p.speciesName()), w - 16), x + w / 2, y + 10, PINK);
				renameBox.setX(x + 25);
				renameBox.setY(y + 24);
				local.add(new Btn("pop:save", x + 12, y + 48, 56, 16, Component.translatable("aftrhrs.pets.save"), true, true, () -> {
					PetData.action(p, "rename", "name", renameBox.getValue().trim());
					chime(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f);
					closePopup();
				}, null));
				local.add(new Btn("pop:reset", x + 72, y + 48, 56, 16, Component.translatable("aftrhrs.pets.reset"), false, p.custom(), () -> {
					PetData.action(p, "rename", "name", "");
					closePopup();
				}, p.custom() ? null : tr("aftrhrs.pets.why.no-custom")));
				local.add(new Btn("pop:cancel", x + 132, y + 48, 56, 16, Component.translatable("gui.cancel"), false, true, this::closePopup, null));
			}
			case COLOR -> {
				net.axther.aftrhrs.client.Theme.centeredFlat(g, font, net.axther.aftrhrs.client.Theme.fit(font, Component.translatable("aftrhrs.pets.color-title"), w - 16), x + w / 2, y + 10, PINK);
				int perRow = (w - 24 + 3) / 17, n = Math.min(perRow, p.colors().size());
				int left = x + (w - (n * 17 - 3)) / 2, sx = left, sy = y + 24;
				for (int c : p.colors()) {
					final int color = c, cx = sx, cy = sy;
					local.add(new Btn("pop:swatch:" + color, sx, sy, 14, 14, Component.empty(), false, true, () -> {
						PetData.action(p, "color", "color", String.format("#%06X", color));
						chime(SoundEvents.NOTE_BLOCK_PLING.value(), 1.8f);
						closePopup();
					}, null));
					// Drawn after the buttons, so the hit area's sprite doesn't cover the color.
					swatches.add(() -> {
						boolean hov = Theme2.inside(mx, my, cx, cy, 14, 14);
						g.fill(cx - (hov ? 1 : 0), cy - (hov ? 1 : 0), cx + 14 + (hov ? 1 : 0), cy + 14 + (hov ? 1 : 0), hov ? WHITE : PINK);
						g.fill(cx + 1, cy + 1, cx + 13, cy + 13, 0xFF000000 | color);
					});
					sx += 17;
					if (sx > x + w - 24) {
						sx = left;
						sy += 17;
					}
				}
				local.add(new Btn("pop:no-color", x + 12, y + h - 22, 84, 16, Component.translatable("aftrhrs.pets.no-color"), false, p.color() != null, () -> {
					PetData.action(p, "color", "color", "");
					closePopup();
				}, null));
				local.add(new Btn("pop:cancel", x + w - 96, y + h - 22, 84, 16, Component.translatable("gui.cancel"), false, true, this::closePopup, null));
			}
			case RELEASE -> {
				net.axther.aftrhrs.client.Theme.centeredFlat(g, font, net.axther.aftrhrs.client.Theme.fit(font, Component.translatable("aftrhrs.pets.release-title", p.name()), w - 16), x + w / 2, y + 12, RED);
				net.axther.aftrhrs.client.Theme.centeredFlat(g, font, net.axther.aftrhrs.client.Theme.fit(font, Component.translatable("aftrhrs.pets.release-sub"), w - 16), x + w / 2, y + 26, SOFT);
				local.add(new Btn("pop:release-yes", x + 12, y + 46, 84, 16, Component.translatable("aftrhrs.pets.release-yes"), false, true, () -> {
					com.google.gson.JsonObject m = net.axther.aftrhrs.client.net.Link.message("pet_action");
					m.addProperty("pet", p.id());
					m.addProperty("a", "release");
					m.addProperty("confirm", true);
					net.axther.aftrhrs.client.net.Link.send(m);
					closePopup();
				}, null));
				local.add(new Btn("pop:release-no", x + w - 96, y + 46, 84, 16, Component.translatable("aftrhrs.pets.release-no"), true, true, this::closePopup, null));
			}
			default -> {
			}
		}
		buttons.clear();
		buttons.addAll(local);
		for (Btn b : local) drawWidget(g, b, mx, my, a);
		swatches.forEach(Runnable::run);
	}

	/** Draws a clickable button through its widget (made the first time the button is drawn). */
	private void drawWidget(GuiGraphicsExtractor g, Btn b, int mx, int my, float a) {
		if (b.w <= 0) return;
		live.put(b.id, b);
		if (!drawn.contains(b.id)) drawn.add(b.id);
		HitButton w = widgets.computeIfAbsent(b.id, id -> new HitButton(id, b.w, b.h, b.label, () -> {
			Btn cur = live.get(id);
			if (cur != null && cur.active) cur.run.run();
		}, (gg, self, lit) -> {
			Btn cur = live.get(self.id);
			if (cur != null) paintButton(gg, cur, lit && cur.active, self.isHovered());
		}).quiet().focusColor(INK));
		w.setRectangle(b.w, b.h, b.x, b.y);
		w.active = b.active;
		w.visible = true;
		// A swatch has no label: the Narrator says its color.
		w.setMessage(b.label.getString().isEmpty() && b.id.startsWith("pop:swatch:")
			? Component.translatable("aftrhrs.pets.narrate.swatch", String.format(Locale.ROOT, "#%06X", Integer.parseInt(b.id.substring("pop:swatch:".length())) & 0xFFFFFF))
			: b.label);
		w.hint(b.tip == null ? null : Component.literal(b.tip));
		w.extractRenderState(g, mx, my, a);
	}

	/**
	 * After a frame: the widgets match the buttons drawn (new ones were made while drawing; the ones not drawn go) in
	 * drawing order, which is the Tab order. The focused one keeps the focus.
	 */
	private void syncWidgets() {
		if (drawn.equals(new ArrayList<>(widgets.keySet())) && widgets.values().stream().allMatch(children()::contains)) return;
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

	// ------------------------------------------------------------------ pieces

	/**
	 * The creature itself (the mod's GeckoLib model, client-side, no server needed) with the pet's look; the plushie or a
	 * paw while there is no world to make one in. {@code color}: multiplied over everything, -1 for none. {@code maxAgeMs}:
	 * how long the last picture may be shown again (0: live; see {@link CreatureView}).
	 */
	private void model(GuiGraphicsExtractor g, String key, net.minecraft.world.entity.EntityType<?> type, CreatureView.Look look,
					   net.minecraft.world.item.ItemStack fallback, int x0, int y0, int x1, int y1, float yaw, float pitch, String hold, long now, int color,
					   int maxAgeMs) {
		if (CreatureView.draw(g, key, type, look, x0, y0, x1, y1, yaw, pitch, hold, color, maxAgeMs)) return;
		int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
		if (!fallback.isEmpty()) g.item(fallback, cx - 8, cy - 8);
		else blit(g, PAW, cx - 4, cy - 4 + (int) (Math.sin(now / 200.0) * 2), 9, 9);
	}

	/** The pet's look on its model: color variant tint, shiny, its name. */
	static CreatureView.Look look(PetData.Pet p) {
		return new CreatureView.Look(p.tint(), p.shiny(), p.name());
	}

	/** Held pose: sitting while it stays, lying still when fallen, else its idle. */
	private static String anim(PetData.Pet p) {
		if (p.fallen()) return CreatureView.firstClip(p.type(), "lie_down", "sleep");
		if (p.staying()) return CreatureView.firstClip(p.type(), "sit");
		return "";
	}

	private void bar(GuiGraphicsExtractor g, int x, int y, int w, float f, int fill, int back) {
		blit(g, BAR, x - 1, y - 1, w + 2, 6);
		g.fill(x + 1, y + 1, x + w - 1, y + 3, back);
		int fw = Math.round((w - 2) * Mth.clamp(f, 0f, 1f));
		if (fw > 0) g.fill(x + 1, y + 1, x + 1 + fw, y + 3, fill);
	}

	private void drawButton(GuiGraphicsExtractor g, Btn b, int mx, int my) {
		if (b.w <= 0) return;
		boolean over = Theme2.inside(mx, my, b.x, b.y, b.w, b.h);
		paintButton(g, b, b.active && over, over);
	}

	/** {@code hov}: drawn lit (hovered or keyboard-focused, and active); {@code over}: under the pointer (its tip shows). */
	private void paintButton(GuiGraphicsExtractor g, Btn b, boolean hov, boolean over) {
		boolean cut = false;
		Identifier s = !b.active ? BUTTON_OFF : b.mint ? (hov ? BUTTON_MINT_HOVER : BUTTON_MINT) : (hov ? BUTTON_HOVER : BUTTON);
		if (!b.label.getString().isEmpty() || b.w > 12) blit(g, s, b.x, b.y, b.w, b.h);
		if (!b.label.getString().isEmpty()) {
			int col = !b.active ? 0xFF9A8F94 : b.mint ? 0xFF1F5A47 : WHITE;
			// cut with "…" when it doesn't fit (never a silent mid-word cut); the whole label then shows on hover
			cut = !net.axther.aftrhrs.client.Theme.fits(font, b.label, b.w - 4);
			var line = net.axther.aftrhrs.client.Theme.fit(font, b.label, b.w - 4);
			// White on the berry buttons keeps its shadow; dark ink on mint (and the faded disabled label) has none.
			if (col == WHITE) g.centeredText(font, line, b.x + b.w / 2, b.y + (b.h - 8) / 2, col);
			else net.axther.aftrhrs.client.Theme.centeredFlat(g, font, line, b.x + b.w / 2, b.y + (b.h - 8) / 2, col);
		}
		// no hand on a disabled button, but its tip still says why it is gray
		if (hov) g.requestCursor(CursorTypes.POINTING_HAND);
		if (over && b.tip != null) tip = b.tip;
		else if (over && cut) tip = b.label.getString();
	}

	private void drawHearts(GuiGraphicsExtractor g, long now) {
		for (Iterator<float[]> it = hearts.iterator(); it.hasNext(); ) {
			float[] h = it.next();
			float age = (now - h[3]) / 1000f;
			if (age > 1.4f) {
				it.remove();
				continue;
			}
			int x = Math.round(h[0] + (float) Math.sin(age * 5 + h[2]) * 4), y = Math.round(h[1] - age * 38);
			blit(g, HEART, x, y, 9, 9);
		}
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
		if (popup != Popup.NONE) {
			// the rename box and the popup's buttons are widgets
			super.mouseClicked(event, doubleClick);
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
		int t = tabAt(mx, my);
		if (t >= 0 && Tab.values()[t] != tab) {
			switchTab(Tab.values()[t], true);
			return true;
		}
		if (onBar(mx, my)) {
			draggingBar = true;
			roster = (float) net.axther.aftrhrs.client.Theme.scrollAt(my, listBar[1], listBar[2], listBar[3], listBar[4]);
			return true;
		}
		// The buttons are widgets.
		if (super.mouseClicked(event, doubleClick)) return true;
		if (tab == Tab.PETS) {
			List<PetData.Pet> list = roster();
			int listX = px + 10, top = py + 22, cardW = 86, cardH = 42;
			for (int i = 0; i < list.size(); i++) {
				int cx = listX + (i % 2) * (cardW + 4), cy = top + (i / 2) * (cardH + 4) - (int) roster;
				if (Theme2.inside(mx, my, cx, cy, cardW, cardH) && my >= top && my < py + 10 + ph - 42) {
					if (!list.get(i).id().equals(selected)) {
						selected = list.get(i).id();
						yaw = CreatureView.DEFAULT_YAW;
						chime(SoundEvents.UI_BUTTON_CLICK.value(), 1.5f);
					}
					return true;
				}
			}
			int sx = px + 192, sy = py + 10, sw = pw - 202, sh = stageHeight();
			if (Theme2.inside(mx, my, sx, sy, sw, sh)) {
				dragging = true;
				dragX = mx;
				pat(mx, my);
				return true;
			}
		}
		return false;
	}

	/** A pat: the pet plays its happy animation, hearts float up, and it makes a happy little sound. */
	private void pat(double mx, double my) {
		PetData.Pet p = current();
		if (p == null || p.fallen()) return;
		CreatureView.play("stage:" + p.id(), "happy");
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (int i = 0; i < 5; i++) hearts.add(new float[]{(float) mx - 4 + r.nextInt(-14, 15), (float) my - 6 + r.nextInt(-6, 7), r.nextFloat() * 6, Util.getMillis() + i * 60L});
		SoundEvent sound = switch (p.kind()) {
			case "mount" -> SoundEvents.HORSE_AMBIENT;
			case "hostile" -> SoundEvents.ALLAY_ITEM_TAKEN;
			default -> SoundEvents.CAT_PURREOW_BABY.value();
		};
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.2f + r.nextFloat() * 0.3f, 0.5f));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingBar) {
			roster = (float) net.axther.aftrhrs.client.Theme.scrollAt(event.y(), listBar[1], listBar[2], listBar[3], listBar[4]);
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
		draggingBar = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (popup != Popup.NONE) return true; // modal: the list behind it stays put
		// My Pets: only over the roster (the stage and buttons are on the right); the Stable is all list
		if (tab == Tab.PETS && mx >= px + 192) return super.mouseScrolled(mx, my, sx, sy);
		roster -= (float) sy * 18;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (popup != Popup.NONE && event.key() == InputConstants.KEY_ESCAPE) {
			closePopup();
			return true;
		}
		// Enter saves the name, unless a popup button has the focus (Enter presses that one).
		if (popup == Popup.RENAME && !(getFocused() instanceof HitButton) && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			PetData.Pet p = popupPet();
			if (p != null) PetData.action(p, "rename", "name", renameBox.getValue().trim());
			closePopup();
			return true;
		}
		// The pet key closes the menu it opened (not while a popup, such as the rename box, takes the keys).
		if (popup == Popup.NONE && Companion.PETS_KEY.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private void switchTab(Tab t, boolean ask) {
		tab = t;
		roster = 0;
		listBar[3] = 0;
		yaw = CreatureView.DEFAULT_YAW;
		closePopup();
		if (ask) {
			PetData.request(null);
			chime(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
		}
	}

	/** Small shared helpers (kept here so the pet screens don't depend on the menu theme). */
	static final class Theme2 {
		static boolean inside(double mx, double my, int x, int y, int w, int h) {
			return mx >= x && my >= y && mx < x + w && my < y + h;
		}
	}
}
