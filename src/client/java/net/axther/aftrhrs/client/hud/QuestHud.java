package net.axther.aftrhrs.client.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The quest tracker ({@code quests} feature): today's and this week's quests down the right edge with progress bars,
 * the weekly server goal at the bottom, and a celebration toast the moment one completes. Toggle with the tracker key; move, resize or hide it in the HUD settings.
 */
public final class QuestHud {
	public record Quest(String id, String name, String scope, long progress, long goal, boolean done, String reward) {
	}

	private static List<Quest> quests = List.of();
	private static Quest serverGoal;
	private static final Set<String> doneSeen = new HashSet<>();
	private static boolean primed;

	private static String toastTitle, toastLine;
	private static long toastAt;

	private QuestHud() {
	}

	public static void register() {
		Link.on("quests", QuestHud::accept);
		Link.on("quest_goal", QuestHud::acceptGoal);
	}

	public static boolean active() {
		return Link.has("quests");
	}

	public static void clear() {
		quests = List.of();
		serverGoal = null;
		doneSeen.clear();
		primed = false;
		toastTitle = null;
		trackerA = 0f;
		lastFrame = null;
		trackerNanos = 0;
	}

	private static void accept(JsonObject m) {
		List<Quest> list = new ArrayList<>();
		read(m.get("daily"), "daily", list);
		read(m.get("weekly"), "weekly", list);
		serverGoal = goal(m);
		// Only what is done now is remembered: a quest rolled again on a later day (same id) celebrates again.
		Set<String> nowDone = new HashSet<>();
		for (Quest q : list) {
			if (!q.done()) continue;
			String key = q.scope() + ":" + q.id();
			nowDone.add(key);
			if (primed && !doneSeen.contains(key)) celebrate(q);
		}
		doneSeen.clear();
		doneSeen.addAll(nowDone);
		primed = true;
		quests = List.copyOf(list);
	}

	/**
	 * The server goal moved on between full quest lists ({@code quest_goal}): only the goal changes. No quest celebrates
	 * from it; the next {@code quests} message stays the one that decides.
	 */
	private static void acceptGoal(JsonObject m) {
		serverGoal = goal(m);
	}

	/** The message's server goal, or null without one. */
	private static Quest goal(JsonObject m) {
		if (!m.has("goal") || !m.get("goal").isJsonObject()) return null;
		JsonObject o = m.getAsJsonObject("goal");
		return new Quest("goal", Link.str(o, "name"), "goal", Link.lng(o, "progress", 0), Math.max(1, Link.lng(o, "goal", 1)), Link.bool(o, "done", false), Link.str(o, "reward"));
	}

	private static void read(JsonElement e, String scope, List<Quest> out) {
		if (e == null || !e.isJsonArray()) return;
		for (JsonElement q : (JsonArray) e) {
			if (!q.isJsonObject()) continue;
			JsonObject o = q.getAsJsonObject();
			out.add(new Quest(Link.str(o, "id"), Link.str(o, "name"), scope, Link.lng(o, "progress", 0), Math.max(1, Link.lng(o, "goal", 1)),
				Link.bool(o, "done", false), Link.str(o, "reward")));
		}
	}

	private static void celebrate(Quest q) {
		toastTitle = Component.translatable("aftrhrs.quests.done").getString();
		toastLine = q.name() + (q.reward().isEmpty() ? "" : "  ·  " + q.reward());
		toastAt = System.currentTimeMillis();
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 0.6f));
	}

	/** {@code preview}: the HUD layout editor is drawing it (sample quests when there are none). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		if (!preview && (mc.player == null || !active() || mc.gui.hud.isHidden())) {
			// F1, no world, no quests feature: gone at once (nothing to fade over).
			trackerA = 0f;
			lastFrame = null;
			return;
		}
		Font font = mc.font;
		long now = System.currentTimeMillis();
		List<Quest> all = quests;
		Quest goal = serverGoal;
		if (preview && all.isEmpty()) {
			// The editor's samples, made once per language (so the text cache keeps them too).
			Language lang = Language.getInstance();
			if (sampleLang != lang) {
				sampleLang = lang;
				sampleQuests = List.of(new Quest("a", Component.translatable("aftrhrs.hud.sample.quest1").getString(), "daily", 7, 10, false, ""),
					new Quest("b", Component.translatable("aftrhrs.hud.sample.quest2").getString(), "daily", 20, 64, false, ""),
					new Quest("c", Component.translatable("aftrhrs.hud.sample.quest3").getString(), "weekly", 1, 3, false, ""));
				sampleGoal = new Quest("goal", Component.translatable("aftrhrs.hud.sample.goal").getString(), "goal", 62, 100, false, "");
			}
			all = sampleQuests;
			if (goal == null) goal = sampleGoal;
		}
		if (preview) {
			Frame F = frame(g, all, goal);
			if (F != null) paint(g, font, F, 1f, false);
			// Back in the game the tracker shows at once (no fade-in), and the editor's samples never fade out there.
			trackerA = 1f;
			lastFrame = null;
		} else {
			// It fades in when it appears and out when it goes (a screen opens, chat reaches it, the tracker key), drawn
			// where it last was while it fades out.
			boolean on = HudConfig.shown(HudElement.QUESTS);
			Frame F = !all.isEmpty() && !HudConfig.covered(mc) && !trackerUnderChat(mc, g) && on ? frame(g, all, goal) : null;
			if (F != null) lastFrame = F;
			long n = System.nanoTime();
			float dt = trackerNanos == 0 ? 0 : Math.min(0.25f, (n - trackerNanos) / 1e9f);
			trackerNanos = n;
			trackerA = F != null ? Math.min(1f, trackerA + dt / IN_S) : Math.max(0f, trackerA - dt / OUT_S);
			// Below 5% nothing (vanilla draws text with alpha under 4 opaque).
			if (lastFrame != null && trackerA >= 0.05f) paint(g, font, lastFrame, trackerA, !on);
			else if (trackerA <= 0f) lastFrame = null;
		}
		toast(g, font, now, preview);
	}

	/** How opaque the tracker is now (0 to 1), when that was worked out, and where it was last laid out (it fades out there). */
	private static float trackerA;
	private static long trackerNanos;
	private static Frame lastFrame;
	/** Seconds to fade in and out. */
	static final float IN_S = 0.12f, OUT_S = 0.18f;

	/** {@code c} with its alpha multiplied by {@code a} (1: unchanged). */
	static int fade(int c, float a) {
		return (Math.round((c >>> 24) * a) << 24) | (c & 0xFFFFFF);
	}

	/**
	 * Whether focused chat would reach under the tracker at its home on the right edge (a narrow screen): it steps aside
	 * then, so chat history is never covered. Moved for a sidebar it already keeps above the chat.
	 */
	private static boolean trackerUnderChat(Minecraft mc, GuiGraphicsExtractor g) {
		if (!(mc.gui.screen() instanceof ChatScreen)) return false;
		HudConfig c = HudConfig.get();
		HudConfig.Pos p = c.pos(HudElement.QUESTS);
		if (p.custom || HudConfig.questsRelocated()) return false;
		return g.guiWidth() - 2 - TW * HudConfig.drawScale(p.scale) - 4 < HudConfig.chatRight(mc);
	}

	/** The tracker's width, its padding inside the panel frame, and the content width between. */
	private static final int TW = 158, PAD = 6, INNER = TW - 2 * PAD;

	/** One open quest's row as laid out: its (ellipsized) name, its "7/10" and that width, and how full its bar is. */
	private record Row(FormattedCharSequence name, FormattedCharSequence num, int numW, boolean weekly, float frac) {
	}

	/**
	 * The tracker's text, laid out once per quest list (same object), goal, language and unicode-font setting: the frame
	 * rate then only draws it. Rows are made for every open quest (how many show depends on the band each frame).
	 */
	private static final class TextCache {
		List<Quest> src;
		Quest goalSrc;
		Language lang;
		boolean uni;
		List<Quest> open = List.of();
		FormattedCharSequence title, allDone, today, week;
		Row[] rows = new Row[0];
		FormattedCharSequence goalName, goalPct;
		int goalPctW;
		int moreN = -1;
		FormattedCharSequence more;
	}

	/** The newest layout; a frame keeps the one it was made with ({@link Frame#text}), so a fading frame never mixes in another list's rows. */
	private static TextCache L = new TextCache();
	private static List<Quest> sampleQuests = List.of();
	private static Quest sampleGoal;
	private static Language sampleLang;

	/** {@link #L}, made again if anything it was laid out from changed. */
	private static TextCache text(Font font, List<Quest> all, Quest goal) {
		Language lang = Language.getInstance();
		boolean uni = Minecraft.getInstance().options.forceUnicodeFont().get();
		TextCache t = L;
		if (all == t.src && goal == t.goalSrc && lang == t.lang && uni == t.uni) return t;
		t = L = new TextCache();
		t.src = all;
		t.goalSrc = goal;
		t.lang = lang;
		t.uni = uni;
		List<Quest> open = new ArrayList<>();
		for (Quest q : all) if (!q.done()) open.add(q);
		t.open = open;
		t.title = Component.translatable("aftrhrs.quests.title").getVisualOrderText();
		t.allDone = Component.translatable("aftrhrs.quests.all-done").getVisualOrderText();
		t.today = Component.translatable("aftrhrs.quests.today").getVisualOrderText();
		t.week = Component.translatable("aftrhrs.quests.week").getVisualOrderText();
		t.rows = new Row[open.size()];
		for (int i = 0; i < t.rows.length; i++) {
			Quest q = open.get(i);
			String n = compact(q.progress()) + "/" + compact(q.goal());
			int nw = font.width(n);
			t.rows[i] = new Row(lang.getVisualOrder(FormattedText.of(Theme.ellipsize(font, q.name(), INNER - nw - 6))), lang.getVisualOrder(FormattedText.of(n)),
				nw, q.scope().equals("weekly"), q.progress() / (float) q.goal());
		}
		if (goal != null) {
			String pct = Math.round(100f * goal.progress() / goal.goal()) + "%";
			t.goalPctW = font.width(pct);
			t.goalPct = lang.getVisualOrder(FormattedText.of(pct));
			t.goalName = lang.getVisualOrder(FormattedText.of(Theme.ellipsize(font, "✦ " + goal.name(), INNER - t.goalPctW - 6)));
		} else {
			t.goalName = t.goalPct = null;
		}
		t.moreN = -1;
		t.more = null;
		return t;
	}

	/** The "+n more" line, kept while n stays. */
	private static FormattedCharSequence more(TextCache t, int n) {
		if (t.moreN != n) {
			t.moreN = n;
			t.more = Component.translatable("aftrhrs.quests.more", n).getVisualOrderText();
		}
		return t.more;
	}

	/**
	 * One frame of the tracker's layout: the open quests and how many rows show, the server goal (null: left out for room),
	 * the shift that keeps it in its band at home, its height, and the text it was laid out with.
	 */
	private record Frame(List<Quest> open, int rows, Quest goal, int shift, int h, TextCache text) {
	}

	/** Lays the tracker out for this frame; null when it doesn't show at all (beside a sidebar with no room). */
	private static Frame frame(GuiGraphicsExtractor g, List<Quest> all, Quest goal) {
		TextCache t = text(Minecraft.getInstance().font, all, goal);
		List<Quest> open = t.open;
		HudConfig c = HudConfig.get();
		int rows = Math.min(c.questRows, open.size());
		int shift = 0;
		if (!c.pos(HudElement.QUESTS).custom) {
			// At home it keeps to the band left free by the effect icons, the coin purse's lines, and (moved left for a
			// sidebar) the land name, chat and chat game card: it slides within it, then shows fewer rows ("+2 more").
			int sw = g.guiWidth(), sh = g.guiHeight();
			int[] band = HudConfig.questsBand(sw, sh);
			float s = HudConfig.drawScale(c.pos(HudElement.QUESTS).scale);
			int room = (int) Math.floor((band[1] - band[0]) / s);
			boolean left = HudConfig.questsRelocated();
			int full = rows;
			while (rows > 1 && trackerH(open, rows, goal != null) > room) rows--;
			if (trackerH(open, rows, goal != null) > room && goal != null) {
				// Not even one row with the server goal: the quests first (at the right edge too, where a short band under
				// the claim tool panel at GUI 4 would otherwise push the tracker up over the panel).
				goal = null;
				rows = full;
				while (rows > 1 && trackerH(open, rows, false) > room) rows--;
			}
			// Beside a sidebar, a tracker over the chat is worse than none (the tracker key still toggles it). The layout
			// editor still shows it (kept on screen), so it can be picked up and moved somewhere it fits.
			if (left && !HudConfig.editing && trackerH(open, rows, goal != null) > room) return null;
			if (!left) {
				int th = trackerH(open, rows, goal != null);
				float[] r = c.place(HudElement.QUESTS, TW, th, sw, sh);
				int y = Math.round(r[1]), hs = (int) Math.ceil(r[3]);
				if (y + hs > band[1]) shift = Math.max(band[1] - hs - y, band[0] - y);
				else if (y < band[0]) shift = Math.min(band[0] - y, band[1] - hs - y);
			}
		}
		return new Frame(open, rows, goal, shift, trackerH(open, rows, goal != null), t);
	}

	/** Draws a laid-out tracker {@code a} opaque; {@code evenIfOff}: while it fades out after being switched off. */
	private static void paint(GuiGraphicsExtractor g, Font font, Frame F, float a, boolean evenIfOff) {
		TextCache t = F.text();
		List<Quest> open = F.open();
		int rows = F.rows(), h = F.h();
		Quest goal = F.goal();
		if (!HudConfig.begin(g, HudElement.QUESTS, TW, h, 0, F.shift(), evenIfOff)) return;
		// The menu panel (opaque inside), like the other cards: readable over snow and sky alike.
		g.blitSprite(RenderPipelines.GUI_TEXTURED, Theme.MENU_PANEL, 0, 0, TW, h, a);
		g.fill(3, 5, 4, h - 5, fade(0xAAC3A6FF, a));
		int x = PAD, y = PAD;
		g.text(font, t.title, x, y, fade(Theme.MOON, a), true);
		y += 12;
		if (open.isEmpty()) {
			g.text(font, t.allDone, x, y, fade(0xFF86E8B0, a), true);
			y += 12;
		}
		String scope = null;
		for (int i = 0; i < rows; i++) {
			Quest q = open.get(i);
			Row row = t.rows[i];
			boolean weekly = row.weekly();
			if (!q.scope().equals(scope)) {
				// "Today" / "This week" over each group, so the two don't differ by hue alone.
				if (scope != null) g.fill(x, y - 3, x + INNER, y - 2, fade(0x33FFFFFF, a));
				g.text(font, weekly ? t.week : t.today, x, y, fade(Theme.MUTED, a), true);
				y += 10;
				scope = q.scope();
			}
			g.text(font, row.name(), x, y, fade(weekly ? Theme.SKY : Theme.TEXT, a), true);
			g.text(font, row.num(), x + INNER - row.numW(), y, fade(Theme.MUTED, a), true);
			bar(g, x, y + 11, INNER, row.frac(), weekly ? 0xFF8FD3FF : 0xFFC3A6FF, a);
			y += 20;
		}
		if (rows < open.size()) {
			g.text(font, more(t, open.size() - rows), x, y, fade(Theme.MUTED, a), true);
			y += 10;
		}
		if (goal != null) {
			y += 2;
			g.text(font, t.goalName, x, y, fade(Theme.MOON, a), true);
			g.text(font, t.goalPct, x + INNER - t.goalPctW, y, fade(Theme.MUTED, a), true);
			bar(g, x, y + 11, INNER, goal.progress() / (float) goal.goal(), 0xFFFFE7A8, a);
		}
		HudConfig.end(g);
	}

	/** The tracker's height (before scaling) showing the first {@code rows} of {@code open}, a "+n more" line for the rest. */
	private static int trackerH(List<Quest> open, int rows, boolean goal) {
		int body;
		if (open.isEmpty()) {
			body = 26;
		} else {
			int headers = 0;
			String scope = null;
			for (int i = 0; i < rows; i++) {
				if (!open.get(i).scope().equals(scope)) {
					headers++;
					scope = open.get(i).scope();
				}
			}
			body = 14 + rows * 20 + headers * 10 + (rows < open.size() ? 10 : 0);
		}
		// The panel's padding: 6 above the title, 8 under the last bar (3 of each are its frame).
		return body + (goal ? 22 : 0) + 6;
	}

	/** How far the quest pop-up sits below its home now (eased, so it doesn't jump when an effect or toast appears). */
	private static float toastShift;
	private static long toastShiftFor = Long.MIN_VALUE, toastShiftNanos;

	private static void toast(GuiGraphicsExtractor g, Font font, long now, boolean preview) {
		String title = toastTitle, line = toastLine;
		long age = now - toastAt;
		if (title != null && age > 4500) {
			toastTitle = null;
			title = null;
		}
		if (title == null) {
			if (!preview) return;
			title = Component.translatable("aftrhrs.quests.done").getString();
			line = Component.translatable("aftrhrs.hud.sample.quest1").getString();
			age = 1000;
		}
		float slide = age < 300 ? 1f - age / 300f : age > 4000 ? (age - 4000) / 500f : 0f;
		int room = Math.min(200, g.guiWidth() - 42);
		title = Theme.ellipsize(font, title, room);
		line = Theme.ellipsize(font, line, room);
		int tw = Math.max(font.width(title), font.width(line)) + 30;
		// Below the status-effect icons and vanilla's toasts while they show there (only at home).
		int target = Math.max(0, HudConfig.topRightBottom(Minecraft.getInstance()) + 4 - HudElement.QUEST_TOAST.dy);
		long nanos = System.nanoTime();
		if (preview || toastShiftFor != toastAt) {
			toastShift = target;
			toastShiftFor = preview ? Long.MIN_VALUE : toastAt;
		} else {
			float dt = Math.min(0.25f, (nanos - toastShiftNanos) / 1e9f);
			toastShift += (target - toastShift) * (1f - (float) Math.exp(-dt / 0.05f));
		}
		toastShiftNanos = nanos;
		if (!HudConfig.begin(g, HudElement.QUEST_TOAST, tw, 30, Math.round(toastShift))) return;
		int x = Math.round(slide * 40);
		Theme.blit(g, Theme.MENU_PANEL, x, 0, tw, 30);
		g.text(font, "★", x + 8, 11, Theme.MOON, true);
		g.text(font, title, x + 20, 7, Theme.MOON, true);
		g.text(font, line, x + 20, 18, Theme.TEXT, true);
		HudConfig.end(g);
	}

	private static String compact(long n) {
		if (n < 1000) return String.valueOf(n);
		if (n < 1_000_000) return (n % 1000 == 0 || n >= 10_000 ? String.valueOf(n / 1000) : String.format(java.util.Locale.ROOT, "%.1f", n / 1000.0)) + "k";
		return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0);
	}

	private static void bar(GuiGraphicsExtractor g, int x, int y, int w, float f, int col, float a) {
		// The panel's bevel tone: the empty track still shows on its dark inside.
		g.fill(x, y, x + w, y + 3, fade(0xFF312A4D, a));
		int fw = Math.round(w * Math.max(0, Math.min(1, f)));
		if (fw > 0) g.fill(x, y, x + fw, y + 3, fade(col, a));
	}
}
