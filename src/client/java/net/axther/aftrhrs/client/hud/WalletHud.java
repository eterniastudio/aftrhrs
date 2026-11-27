package net.axther.aftrhrs.client.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The coin purse ({@code wallet} feature): the player's bank balance in a corner of the HUD. When it changes, the number
 * rolls to the new total and a short line ("+18 ⛃ Sold at a counter") drifts up from the purse and fades; Feather sums
 * a burst into one line, and a line of the same kind arriving while the last one is still fresh joins it. Coins waiting
 * at the bank show as a dim "+n waiting". With <i>Hide amounts</i> every number is "•••".
 *
 * <p>Nothing here asks Feather for anything: the purse only listens. While a screen (not chat) is open the lines and the roll wait
 * (they start once the player can see them), so a sale made in a menu still shows when it closes.
 */
public final class WalletHud {
	private static final Identifier PURSE = sprite("wallet/purse");
	private static final Identifier COIN = sprite("wallet/coin_0");
	private static final Identifier COIN_GLINT = sprite("wallet/coin_1");
	private static final Identifier SHADE = sprite("wallet/shade");
	private static final Identifier[] DIAL = {sprite("wallet/dial_0"), sprite("wallet/dial_1"), sprite("wallet/dial_2"), sprite("wallet/dial_3")};
	private static final int PLUS = 0x86E8B0, MINUS = 0xFF8FA3, GOLD = 0xFFD24A;
	private static final long ROLL_MS = 600, HOLD_MS = 800, FADE_MS = 1600, LIFE_MS = HOLD_MS + FADE_MS;
	/** A new line of the same type joins a visible one this young (a burst split over two of Feather's flushes). */
	private static final long MERGE_MS = 1200;
	private static final int MAX_LINES = 3, DRIFT = 14;
	private static final String HIDDEN = "•••";
	/** Secondary text that still reads (at least 4.5:1 on the night panel). */

	/** One delta line; {@code shownAt} 0 until it was first on screen. */
	private static final class Line {
		final String type;
		String label;
		long amount;
		long shownAt;

		Line(String type, String label, long amount) {
			this.type = type;
			this.label = label;
			this.amount = amount;
		}

		/** "+18 ⛃ " and the label as last laid out, and what for: laid out again when the amount, label or font change. */
		FormattedCharSequence amtText, labelText;
		int amtW, labelW;
		private long layAmount;
		private String layLabel;
		private boolean layHide, layUni;
		private Language layLang;

		void layout(Font font, boolean hide) {
			Language lang = Language.getInstance();
			boolean uni = Minecraft.getInstance().options.forceUnicodeFont().get();
			if (amtText != null && layAmount == amount && layLabel == label && layHide == hide && layUni == uni && layLang == lang) return;
			layAmount = amount;
			layLabel = label;
			layHide = hide;
			layUni = uni;
			layLang = lang;
			String amt = (amount > 0 ? "+" : "−") + (hide ? HIDDEN : fmt(Math.abs(amount))) + " ⛃ ";
			amtText = lang.getVisualOrder(FormattedText.of(amt));
			labelText = lang.getVisualOrder(FormattedText.of(label));
			amtW = font.width(amtText);
			labelW = font.width(labelText);
		}
	}

	/** The purse's number and its "+n waiting" line as last laid out, and what for ({@link #draw}). */
	private static FormattedCharSequence numText, waitText;
	private static int numWidth, waitWidth;
	private static long numFor, waitFor;
	private static boolean numHide, numUni, waitHide, waitUni;
	private static Language numLang, waitLang;

	private static boolean known;
	private static long target, from, rollAt;
	private static boolean rollWaiting;
	private static long waiting;
	/** Client clock time of the next interest payout, and about how much it pays; {@code interestAt} -1: no interest. */
	private static long interestAt = -1, interestAbout;
	private static final List<Line> lines = new ArrayList<>();
	/**
	 * How far up from the bottom of the screen vanilla's subtitle stack reaches (plus a gap; 0 with none), as the lines
	 * at home keep clear of it. It rises at once and only drops once the stack stayed lower for {@link #SUBS_DROP_MS}, so
	 * the lines don't bob as footstep subtitles come and go.
	 */
	private static int subsClear;
	private static long subsLowerSince = -1;
	private static final long SUBS_DROP_MS = 1000;
	/**
	 * How far (GUI px) the lines at home were last lifted above the subtitle stack; the quest tracker's band above them
	 * counts it ({@link #homeLineBandTop}). It follows {@link #subsClear}, so it doesn't bob either.
	 */
	private static int lastLift;

	private WalletHud() {
	}

	/**
	 * Top y of the band the delta lines can reach above the purse at its home, or -1 when the purse doesn't draw there.
	 * At home the lines always go up, right-aligned. The taller "+n waiting" purse is assumed, so what keeps clear of it
	 * (the quest tracker) doesn't jump when that line comes or goes.
	 */
	static int homeLineBandTop(int sw, int sh, boolean preview) {
		HudConfig c = HudConfig.get();
		if (!preview && (HudConfig.quiet || !active() || !known) ||!HudConfig.shown(HudElement.WALLET) || !c.atHome(HudElement.WALLET)) return -1;
		int top = sh - 6 - 27;
		return top - 11 - ((MAX_LINES - 1) * 10 + DRIFT) - 1 - (preview ? 0 : lastLift);
	}

	/** The subtitle stack's reach for this frame ({@link #subsClear}). */
	private static int subtitlesClear(Minecraft mc, long now) {
		int want = 0;
		if (mc.options.showSubtitles().get()) {
			var overlay = ((net.axther.aftrhrs.client.mixin.HudSubtitleAccessor) mc.gui.hud).aftrhrs$subtitles();
			int rows = ((net.axther.aftrhrs.client.mixin.SubtitleOverlayAccessor) overlay).aftrhrs$audible().size();
			// Vanilla's rows: the first centered 35 above the bottom, 9 high with a 1 px box around, then one every 10.
			want = rows == 0 ? 0 : 40 + (rows - 1) * 10 + 2;
		}
		if (want >= subsClear) {
			subsClear = want;
			subsLowerSince = -1;
		} else if (subsLowerSince < 0) {
			subsLowerSince = now;
		} else if (now - subsLowerSince >= SUBS_DROP_MS) {
			subsClear = want;
			subsLowerSince = -1;
		}
		return subsClear;
	}

	private static Identifier sprite(String path) {
		return Identifier.fromNamespaceAndPath(Companion.ID, path);
	}

	public static void register() {
		Link.on("wallet", WalletHud::accept);
	}

	public static boolean active() {
		return Link.has("wallet");
	}

	public static void clear() {
		known = false;
		target = from = 0;
		rollAt = 0;
		rollWaiting = false;
		waiting = 0;
		interestAt = -1;
		interestAbout = 0;
		lines.clear();
		lastLift = 0;
	}

	private static void accept(JsonObject m) {
		long now = System.currentTimeMillis();
		if (m.has("bal")) {
			long bal = Math.max(0, Link.lng(m, "bal", target));
			if (!known) {
				from = target = bal; // a snapshot: nothing to count up from
			} else if (bal != target) {
				from = shown(now);
				target = bal;
				rollWaiting = true;
			}
			known = true;
		}
		if (m.has("pending")) waiting = Math.max(0, Link.lng(m, "pending", waiting));
		if (m.has("interestIn")) {
			interestAt = now + Math.max(0, Link.lng(m, "interestIn", 0));
			interestAbout = Math.max(0, Link.lng(m, "interestAbout", 0));
		} else if (m.has("bal")) {
			interestAt = -1; // a full message without interest: it is off
		}
		JsonElement tx = m.get("tx");
		if (tx != null && tx.isJsonArray()) {
			for (JsonElement e : tx.getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				long amount = Link.lng(o, "amount", 0);
				String type = Link.str(o, "type");
				if (amount == 0) continue;
				String label = Link.str(o, "label");
				add(type, label.isBlank() ? titleCase(type) : label, amount, now);
			}
		}
	}

	private static void add(String type, String label, long amount, long now) {
		for (int i = lines.size() - 1; i >= 0; i--) {
			Line l = lines.get(i);
			if (!l.type.equals(type) || (l.shownAt != 0 && now - l.shownAt > MERGE_MS)) continue;
			l.amount += amount;
			l.label = label;
			lines.remove(i);
			if (l.amount == 0) return;
			// Fresh again, and the newest: nearest the purse.
			if (l.shownAt != 0) l.shownAt = now;
			lines.add(l);
			return;
		}
		lines.add(new Line(type, label, amount));
		while (lines.size() > MAX_LINES) lines.removeFirst();
	}

	/** The number on screen now (part way through a roll). */
	private static long shown(long now) {
		if (rollWaiting) return from;
		if (rollAt == 0) return target;
		float t = Math.min(1f, (now - rollAt) / (float) ROLL_MS);
		if (t >= 1f) return target;
		float e = 1f - (1f - t) * (1f - t) * (1f - t); // ease out
		return from + Math.round((target - from) * (double) e);
	}

	private static boolean rolling(long now) {
		return rollWaiting || (rollAt != 0 && now - rollAt < ROLL_MS);
	}

	// ------------------------------------------------------------------ drawing

	/** {@code preview}: the HUD layout editor is drawing it (1,234 and a +18 line when nothing real is there). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		if (!preview && (mc.player == null || !active() || !known || mc.gui.hud.isHidden())) return;
		// Hidden under a screen (not chat), before begin(): the lines and the roll keep waiting for it to close.
		if (!preview && HudConfig.covered(mc)) return;
		Font font = mc.font;
		long now = System.currentTimeMillis();
		boolean hide = HudConfig.get().walletHideAmounts;
		boolean sample = preview && !known;
		// Lines and the roll only start once nothing covers the HUD (chat doesn't count: you type over the game).
		boolean visible = preview || !HudConfig.covered(mc);
		if (visible && rollWaiting) {
			rollWaiting = false;
			rollAt = now;
		}
		long value = sample ? 1234 : shown(now);
		long wait = sample ? 0 : waiting;

		// The number and the waiting line are laid out again only when they change (every frame only while it rolls).
		Language lang = Language.getInstance();
		boolean uni = mc.options.forceUnicodeFont().get();
		if (numText == null || value != numFor || hide != numHide || uni != numUni || lang != numLang) {
			numFor = value;
			numHide = hide;
			numUni = uni;
			numLang = lang;
			numText = lang.getVisualOrder(FormattedText.of(hide ? HIDDEN : fmt(value)));
			numWidth = font.width(numText);
		}
		if (wait <= 0) {
			waitText = null;
		} else if (waitText == null || wait != waitFor || hide != waitHide || uni != waitUni || lang != waitLang) {
			waitFor = wait;
			waitHide = hide;
			waitUni = uni;
			waitLang = lang;
			waitText = Component.translatable("aftrhrs.wallet.waiting", hide ? HIDDEN : fmt(wait)).getVisualOrderText();
			waitWidth = font.width(waitText);
		}
		FormattedCharSequence num = numText, waitLine = waitText;
		int numW = numWidth;
		// [purse 16][3][number][2][coin 10], the waiting line under the number
		int w = Math.max(16 + 3 + numW + 2 + 10, waitLine == null ? 0 : 16 + 3 + waitWidth) + 2;
		int h = waitLine == null ? 18 : 27;
		if (!HudConfig.begin(g, HudElement.WALLET, w, h)) {
			lastLift = 0;
			// Switched off: nothing waits to pop up the moment it is switched back on.
			if (!preview) {
				lines.clear();
				rollWaiting = false;
				rollAt = 0;
			}
			return;
		}
		float[] r = HudConfig.get().lastDrawn(HudElement.WALLET);
		int sw = g.guiWidth(), sh = g.guiHeight();
		// Where the lines go: up from the purse, unless it sits near the top (then down); on its open side.
		boolean down = r != null && r[1] + r[3] / 2 < sh / 3f;
		boolean alignLeft = r != null && r[0] + r[2] / 2 < sw / 2f;
		// At home with subtitles on, the lines start above the subtitle stack (the purse itself stays put).
		int lift = 0;
		if (!preview && r != null && !down && !HudConfig.get().pos(HudElement.WALLET).custom) {
			int clear = subtitlesClear(mc, now);
			float scale = r[3] / h;
			if (clear > 0) lift = Math.max(0, Math.round(((r[1] - 11 * scale) + 9 * scale - (sh - clear)) / scale));
		}
		if (!preview) lastLift = lift;

		int nx = 16 + 3;
		Theme.blit(g, SHADE, nx - 6, 1, numW + 2 + 10 + 12, 16);
		Theme.blit(g, PURSE, 0, 1, 16, 16);
		g.text(font, num, nx, 5, rolling(now) ? 0xFFFFF4C8 : 0xFF000000 | GOLD, true);
		// The coin glints while the number rolls (two frames), and holds still otherwise.
		boolean glint = rolling(now) && (now / 120) % 2 == 0;
		Theme.blit(g, glint ? COIN_GLINT : COIN, nx + numW + 2, 2, 10, 10);
		if (waitLine != null) g.text(font, waitLine, nx, 18, Theme.SUBTLE, true);

		drawLines(g, font, now, preview, visible, hide, w, h, down, alignLeft, lift);
		HudConfig.end(g);

		if (preview && r != null) interestTip(g, font, r, now, sample, hide);
	}

	private static void drawLines(GuiGraphicsExtractor g, Font font, long now, boolean preview, boolean visible, boolean hide, int w, int h,
			boolean down, boolean alignLeft, int lift) {
		List<Line> show = lines;
		if (preview && lines.isEmpty()) {
			Line l = new Line("SELLBACK", Component.translatable("aftrhrs.hud.sample.wallet").getString(), 18);
			l.shownAt = now - 400;
			show = List.of(l);
		} else if (visible) {
			for (Line l : lines) if (l.shownAt == 0) l.shownAt = now;
			lines.removeIf(l -> now - l.shownAt > LIFE_MS);
		}
		// Newest nearest the purse; older ones further along the drift.
		int slot = 0;
		for (int i = show.size() - 1; i >= 0; i--, slot++) {
			Line l = show.get(i);
			if (l.shownAt == 0) continue;
			long age = now - l.shownAt;
			float alpha = age < 150 ? age / 150f : age < HOLD_MS ? 1f : Math.max(0f, 1f - (age - HOLD_MS) / (float) FADE_MS);
			int a = Math.round(alpha * 255);
			if (a < 8) continue;
			int drift = Math.round(Math.min(1f, age / (float) LIFE_MS) * DRIFT);
			int step = slot * 10 + drift;
			int y = down ? h + 2 + step : -11 - step - lift;
			l.layout(font, hide);
			int aw = l.amtW, lw = l.labelW;
			int x = alignLeft ? 0 : w - aw - lw;
			int col = l.amount > 0 ? PLUS : MINUS;
			g.text(font, l.amtText, x, y, (a << 24) | col, true);
			g.text(font, l.labelText, x + aw, y, (a << 24) | 0xF1EDFF, true);
		}
	}

	/** Edit layout only: hovering the purse shows the moon-dial with the next interest payout. */
	private static void interestTip(GuiGraphicsExtractor g, Font font, float[] r, long now, boolean sample, boolean hide) {
		Minecraft mc = Minecraft.getInstance();
		double mx = mc.mouseHandler.getScaledXPos(mc.getWindow()), my = mc.mouseHandler.getScaledYPos(mc.getWindow());
		if (mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) return;
		boolean on = sample || interestAt >= 0;
		long left = sample ? 3 * 3_600_000L + 12 * 60_000L : Math.max(0, interestAt - now);
		long about = sample ? 6 : interestAbout;
		Component title = Component.translatable("aftrhrs.wallet.interest");
		Component line = on
			? Component.translatable("aftrhrs.wallet.interest.next", hide ? HIDDEN : fmt(about), until(left))
			: Component.translatable("aftrhrs.wallet.interest.off");
		int tw = Math.max(font.width(title), font.width(line)) + 15 + 14;
		int th = 26;
		// Beside the purse, on the side with room, kept on screen.
		int x = Math.round(r[0]) - tw - 4;
		if (x < 2) x = Math.round(r[0] + r[2]) + 4;
		x = Math.max(2, Math.min(g.guiWidth() - tw - 2, x));
		int y = Math.max(2, Math.min(g.guiHeight() - th - 2, Math.round(r[1] + r[3] / 2 - th / 2f)));
		Theme.blit(g, Theme.MENU_PANEL, x, y, tw, th);
		// The moon waxes over the last day: a thin crescent a day out, full in the last hours.
		int phase = !on ? 0 : left > 18 * 3_600_000L ? 0 : left > 12 * 3_600_000L ? 1 : left > 6 * 3_600_000L ? 2 : 3;
		Theme.blit(g, DIAL[phase], x + 6, y + 5, 15, 15);
		g.text(font, title, x + 25, y + 5, Theme.MOON, true);
		g.text(font, line, x + 25, y + 15, on ? Theme.TEXT : Theme.SUBTLE, true);
	}

	// ------------------------------------------------------------------ text

	private static String fmt(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	/** "3h 12m" style, like Feather's own /balance. */
	private static String until(long ms) {
		long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
		if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
		if (h > 0) return h + "h " + m + "m";
		if (m > 0) return m + "m " + (s % 60) + "s";
		return (s % 60) + "s";
	}

	/** STALL-RENT -> "Stall Rent" (an older Feather, or a type without a name in its messages). */
	static String titleCase(String type) {
		StringBuilder b = new StringBuilder();
		for (String w : type.replace('-', ' ').replace('_', ' ').trim().split("\\s+")) {
			if (w.isEmpty()) continue;
			if (!b.isEmpty()) b.append(' ');
			b.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
		}
		return b.toString();
	}
}
