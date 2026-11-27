package net.axther.aftrhrs.client.hud;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Decode;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The chat game card ({@code chatgames} feature): while a chat game runs, a small card above the chat shows the game's
 * icon and name, the question exactly as chat shows it, the reward and a timer bar that drains locally. Answers are
 * still typed in chat; the card has no buttons and nothing to copy. When the game ends it flips to who won (or the
 * answer, on a timeout), stays 4 s and folds away. A new game replaces the card; leaving the server clears it.
 *
 * <p>Feather sends the answer only with the end of the game ({@code chatgame_end}), and only where the chat line shows
 * it too.
 */
public final class ChatGameHud {
	private static final int WRAP = 180, PAD = 8, LINE = 10, MAX_LINES = 5, MIN_W = 140;
	private static final long SLIDE_MS = 250, FLIP_MS = 240, END_MS = 4000, FOLD_MS = 300;
	/** No end message this long after the timer ran out (a reload, a lost message): the card goes by itself. */
	private static final long LOST_MS = 10_000;

	private static final Identifier CARD = sprite("card"), HEADER = sprite("header"), SEAL = sprite("seal"), COIN = sprite("coin"),
		BAR = sprite("bar"), BAR_FILL = sprite("bar_fill"), BAR_FILL_LOW = sprite("bar_fill_low");
	private static final Map<String, Identifier> ICONS = Map.of(
		"math", sprite("icon_math"), "trivia", sprite("icon_trivia"), "unscramble", sprite("icon_unscramble"),
		"gaps", sprite("icon_gaps"), "creature", sprite("icon_creature"), "fast-type", sprite("icon_fast_type"));

	private record Game(String type, Component name, Component prompt, long reward, long seconds, long startedAt) {
	}

	private static Game game;
	/** The end line once the game is over (null while it runs). */
	private static Component ending;
	private static long endedAt;

	/** A text's wrapped lines, kept while the text (same object), the language and the unicode-font setting stay. */
	private static final class Wrapped {
		Component src;
		String lang;
		boolean uni;
		List<FormattedCharSequence> rows = List.of();
		int maxW;
	}

	private static final Wrapped PROMPT = new Wrapped(), RESULT = new Wrapped();
	/** The editor's sample game (made once per language, so its wrapped prompt is kept too). */
	private static Game sample;
	private static String sampleLang;
	/** Where the card sits at home now (GUI px, eased toward its spot), and for which card; {@code curFor} null: snap. */
	private static float curX, curY;
	private static Object curFor;
	private static long curNanos;

	private ChatGameHud() {
	}

	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath(Companion.ID, "chatgame/" + name);
	}

	public static void register() {
		Link.on("chatgame", ChatGameHud::start);
		Link.on("chatgame_end", ChatGameHud::end);
	}

	public static boolean active() {
		return Link.has("chatgames");
	}

	public static void clear() {
		game = null;
		ending = null;
		PROMPT.src = RESULT.src = null;
		PROMPT.rows = RESULT.rows = List.of();
		curFor = null;
	}

	private static void start(JsonObject m) {
		String type = Link.str(m, "type");
		if (!ICONS.containsKey(type)) type = "trivia";
		Component prompt = Decode.text(m.get("prompt"));
		if (prompt.getString().isBlank()) return;
		Component name = Decode.text(m.get("name"), Component.translatable("aftrhrs.chatgame.title"));
		game = new Game(type, name, prompt, Math.max(0, Link.lng(m, "reward", 0)), Mth.clamp(Link.lng(m, "seconds", 60), 3, 600),
			System.currentTimeMillis());
		ending = null;
		Minecraft mc = Minecraft.getInstance();
		if (HudConfig.get().chatGameSound && HudConfig.shown(HudElement.CHATGAME) && visible(mc)) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.5f));
		}
	}

	private static void end(JsonObject m) {
		if (game == null || ending != null) return;
		String answer = plain(Link.str(m, "answer"), 120);
		Component a = Component.literal(answer).withColor(Theme.ACCENT);
		Component line = switch (Link.str(m, "how")) {
			case "won" -> {
				String winner = plain(Link.str(m, "winner"), 40);
				String time = String.format(Locale.ROOT, "%.1f", Math.max(0, Link.lng(m, "ms", 0)) / 1000.0);
				Component who = Component.literal(winner.isEmpty() ? "?" : winner).withColor(Theme.TEXT);
				yield answer.isEmpty() ? Component.translatable("aftrhrs.chatgame.won-plain", who, time)
					: Component.translatable("aftrhrs.chatgame.won", who, time, a);
			}
			case "timeout" -> answer.isEmpty() ? Component.translatable("aftrhrs.chatgame.timeout-plain")
				: Component.translatable("aftrhrs.chatgame.timeout", a);
			case "stopped" -> answer.isEmpty() ? Component.translatable("aftrhrs.chatgame.stopped")
				: Component.translatable("aftrhrs.chatgame.stopped-answer", a);
			default -> null; // "hidden" (games switched off mid-game) and anything newer: just go
		};
		if (line == null) {
			game = null;
			return;
		}
		ending = line;
		endedAt = System.currentTimeMillis();
	}

	/** Server text shown as-is, without control characters and cut to a sane length. */
	private static String plain(String s, int max) {
		String t = s.replaceAll("\\p{Cntrl}", "").strip();
		return t.length() > max ? t.substring(0, max) + "…" : t;
	}

	/** In game with the HUD up, chat not hidden, and no screen over it but chat itself (you answer there). */
	private static boolean visible(Minecraft mc) {
		if (mc.player == null || mc.gui.hud.isHidden()) return false;
		if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return false;
		return !HudConfig.covered(mc);
	}

	/** {@code preview}: the HUD layout editor is drawing it (a sample game when none runs). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		Game gm = game;
		Component end = ending;
		long ended = endedAt;
		if (gm != null && end != null && now - ended > END_MS + FOLD_MS || gm != null && end == null && now - gm.startedAt > gm.seconds * 1000 + LOST_MS) {
			clear();
			gm = null;
		}
		long started;
		if (gm == null) {
			if (!preview) return;
			gm = sample(mc);
			started = now - 18_000;
			end = null;
		} else if (!preview && (!active() || !visible(mc))) {
			return;
		} else {
			started = gm.startedAt;
		}
		Font font = mc.font;
		Wrapped prompt = wrapped(PROMPT, mc, font, gm.prompt);
		Wrapped result = end == null ? null : wrapped(RESULT, mc, font, end);
		// One size for both faces, so the flip doesn't make the card jump.
		int rows = Math.max(prompt.rows.size(), result == null ? 0 : result.rows.size());
		String reward = gm.reward > 0 ? String.valueOf(gm.reward) : "";
		int rewardW = reward.isEmpty() ? 0 : font.width(reward) + 12;
		int bodyW = Math.max(prompt.maxW, result == null ? 0 : result.maxW);
		int w = Mth.clamp(Math.max(bodyW + 2 * PAD, 24 + font.width(gm.name) + 8 + rewardW + 6), MIN_W, WRAP + 2 * PAD);
		int h = 33 + rows * LINE;
		boolean beside = home(g, mc, gm, w, h, preview);
		if (!HudConfig.begin(g, HudElement.CHATGAME, w, h, Math.round(shiftX), Math.round(shiftY))) return;
		g.pose().pushMatrix();
		long age = now - started;
		// Slides in from the left edge, except beside the chat (it would sweep across the chat lines).
		if (!preview && !beside && age < SLIDE_MS) g.pose().translate(-(w + 8) * ease(1f - age / (float) SLIDE_MS), 0);
		boolean showEnd = false;
		if (end != null) {
			long since = now - ended;
			float sy = 1f;
			if (since < FLIP_MS) {
				// The flip: the question squashes away, the result opens up.
				float t = since / (float) FLIP_MS;
				sy = Math.abs(1f - 2f * t);
				showEnd = t >= 0.5f;
			} else {
				showEnd = true;
				if (since > END_MS) sy = 1f - Math.min(1f, (since - END_MS) / (float) FOLD_MS);
			}
			sy = Math.max(0.02f, sy);
			g.pose().translate(0, h / 2f);
			g.pose().scale(1f, sy);
			g.pose().translate(0, -h / 2f);
		}
		Theme.blit(g, CARD, 0, 0, w, h);
		Theme.blit(g, HEADER, 3, 3, w - 6, 15);
		Theme.blit(g, SEAL, 4, 2, 16, 16);
		Theme.blit(g, ICONS.getOrDefault(gm.type, ICONS.get("trivia")), 6, 4, 12, 12);
		int nameRoom = w - 24 - (rewardW > 0 ? rewardW + 8 : 6);
		g.text(font, Theme.fit(font, gm.name, nameRoom), 24, 7, Theme.TEXT, true);
		if (!reward.isEmpty()) {
			Theme.blit(g, COIN, w - 16, 6, 9, 9);
			g.text(font, reward, w - 18 - font.width(reward), 7, Theme.MOON, true);
		}
		if (showEnd) {
			// The result, centered in the body (where the question and the timer were).
			List<FormattedCharSequence> lines = result.rows;
			int top = 22 + (rows * LINE + 7 - lines.size() * LINE) / 2;
			for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), PAD, top + i * LINE, Theme.MUTED, true);
		} else {
			List<FormattedCharSequence> lines = prompt.rows;
			for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), PAD, 22 + i * LINE, Theme.TEXT, true);
			timer(g, font, gm.seconds, started, w, 22 + rows * LINE + 1, end != null ? ended : now);
		}
		g.pose().popMatrix();
		HudConfig.end(g);
	}

	/** This frame's shift from the enum home ({@link HudElement#CHATGAME}) to where the card sits at home. */
	private static float shiftX, shiftY;

	/**
	 * Works out where the card sits at home ({@link #shiftX}, {@link #shiftY}): just above whatever vanilla's chat can
	 * fill now (higher while chat is open), else right of the chat when there is room, else at the top left. True when
	 * it sits right of the chat.
	 */
	private static boolean home(GuiGraphicsExtractor g, Minecraft mc, Game gm, int w, int h, boolean preview) {
		HudConfig c = HudConfig.get();
		shiftX = shiftY = 0;
		if (c.pos(HudElement.CHATGAME).custom) {
			curFor = null;
			return false;
		}
		int sw = g.guiWidth(), sh = g.guiHeight();
		float s = HudConfig.drawScale(c.pos(HudElement.CHATGAME).scale);
		float ws = w * s, hs = h * s;
		int top = HudConfig.chatTop(mc, sh);
		float minY = 4;
		float[] land = HudConfig.frameRects.get(HudElement.LAND);
		if (land != null && !c.pos(HudElement.LAND).custom) minY = Math.max(minY, land[1] + land[3] + 4);
		float tx = HudElement.CHATGAME.dx, ty = top - 4 - hs;
		boolean beside = false;
		if (ty < minY) {
			// Not enough room above the open chat: beside it, unless that runs off screen or into the quest tracker
			// (last frame's: it draws after this card).
			float rx = HudConfig.chatRight(mc) + 4, ry = Mth.clamp(top, minY, Math.max(minY, sh - 40 - hs));
			if (rx + ws <= sw - 4 && !HudConfig.intersects(HudConfig.lastFrameRects.get(HudElement.QUESTS), rx, ry, ws, hs)) {
				tx = rx;
				ty = ry;
				beside = true;
			} else {
				// Pinned to the top it covers only the oldest lines; the newest guesses at the bottom stay clear.
				ty = minY;
			}
		}
		// Ease toward the spot (about 150 ms) as chat opens and closes; snap for a new card or a resize.
		long nanos = System.nanoTime();
		if (preview || curFor != gm || Math.abs(ty - curY) > sh / 2f || Math.abs(tx - curX) > sw / 2f) {
			curX = tx;
			curY = ty;
			curFor = preview ? null : gm;
		} else {
			float dt = Math.min(0.25f, (nanos - curNanos) / 1e9f);
			float k = 1f - (float) Math.exp(-dt / 0.05f);
			curX += (tx - curX) * k;
			curY += (ty - curY) * k;
		}
		curNanos = nanos;
		float[] r = c.place(HudElement.CHATGAME, w, h, sw, sh);
		shiftX = curX - r[0];
		shiftY = curY - r[1];
		return beside;
	}

	/** The editor's sample game, made again when the language changes. */
	private static Game sample(Minecraft mc) {
		String lang = mc.options.languageCode;
		if (sample == null || !lang.equals(sampleLang)) {
			sampleLang = lang;
			sample = new Game("math", Component.translatable("aftrhrs.hud.sample.chatgame.name").withColor(Theme.SKY),
				Component.translatable("aftrhrs.hud.sample.chatgame.prompt", Component.literal("7 × 8 + 3").withColor(0xFFFFD38A)), 5, 60, 0);
		}
		return sample;
	}

	private static void timer(GuiGraphicsExtractor g, Font font, long seconds, long started, int w, int y, long now) {
		long total = seconds * 1000;
		long left = Math.max(0, started + total - now);
		float f = left / (float) total;
		boolean low = left < Math.min(10_000, total / 4);
		String secs = Component.translatable("aftrhrs.chatgame.seconds", (left + 999) / 1000).getString();
		int sw = Math.max(font.width(secs), font.width("00s"));
		int bw = w - 2 * PAD - sw - 4;
		Theme.blit(g, BAR, PAD, y, bw, 5);
		int fw = Math.round((bw - 2) * f);
		if (fw > 0) Theme.blit(g, low ? BAR_FILL_LOW : BAR_FILL, PAD + 1, y + 1, fw, 3);
		g.text(font, secs, w - PAD - font.width(secs), y - 2, low ? 0xFFFF9EB8 : Theme.MUTED, true);
	}

	/** {@code text}'s lines, from {@code c} while it is still about the same text and font settings. */
	private static Wrapped wrapped(Wrapped c, Minecraft mc, Font font, Component text) {
		String lang = mc.options.languageCode;
		boolean uni = mc.options.forceUnicodeFont().get();
		if (c.src == text && uni == c.uni && lang.equals(c.lang)) return c;
		c.rows = lines(font, text);
		int maxW = 0;
		for (FormattedCharSequence l : c.rows) maxW = Math.max(maxW, font.width(l));
		c.maxW = maxW;
		c.src = text;
		c.lang = lang;
		c.uni = uni;
		return c;
	}

	/** {@code text} wrapped to the card, at most {@link #MAX_LINES} lines; a cut shows as "…" on the last one. */
	private static List<FormattedCharSequence> lines(Font font, Component text) {
		List<FormattedText> parts = font.getSplitter().splitLines(text, WRAP, Style.EMPTY);
		if (parts.size() > MAX_LINES) {
			parts = new ArrayList<>(parts.subList(0, MAX_LINES));
			FormattedText last = parts.get(MAX_LINES - 1);
			parts.set(MAX_LINES - 1, FormattedText.composite(font.substrByWidth(last, WRAP - font.width("…")), FormattedText.of("…")));
		}
		List<FormattedCharSequence> out = new ArrayList<>(parts.size());
		for (FormattedText p : parts) out.add(Language.getInstance().getVisualOrder(p));
		return List.copyOf(out);
	}

	private static float ease(float t) {
		return t * t * (3 - 2 * t);
	}
}
