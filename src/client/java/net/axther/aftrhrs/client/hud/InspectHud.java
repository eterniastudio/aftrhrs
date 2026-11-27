package net.axther.aftrhrs.client.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Decode;
import net.axther.aftrhrs.client.net.Link;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.ArcanaCropBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The look-at card ({@code inspect} feature): rest the crosshair on an Arcana crop (or its farmland), a crab pot or a
 * station and, after a steady 250 ms, a small card under (or, when tall, beside) the crosshair shows what a right-click would tell. Only
 * candidates are asked about (an Arcana crop block, farmland under one, a furniture block), each once per target;
 * answers are kept for their {@code ttl} (a "nothing here" answer for 5 s) and asked again while you keep looking. The server ({@code net.axther.aftrhrs.net.Inspect}) checks reach, sight and claims; the client never
 * decides what you may see.
 */
public final class InspectHud {
	private static final long STEADY_MS = 250, NONE_MS = 5000, RETRY_MS = 1500, STALE_MS = 1500;
	private static final int MIN_W = 112, MAX_W = 196, PAD = 7;

	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath(Companion.ID, "inspect/" + name);
	}

	private static final Identifier CARD = sprite("card"), WELL = sprite("well"), RULE = sprite("rule"), TRACK = sprite("bar_track"),
		PIP = sprite("pip"), PIP_EMPTY = sprite("pip_empty"), CLOCK = sprite("clock");
	private static final Map<String, Identifier> FILLS = Map.of("#8FD3FF", sprite("bar_fill_sky"), "#86E8B0", sprite("bar_fill_mint"),
		"#FFE7A8", sprite("bar_fill_moon"));
	private static final Identifier FILL = sprite("bar_fill");
	private static final Map<String, Identifier> GLYPHS = Map.of("drop", sprite("drop"), "drop_empty", sprite("drop_empty"), "sun", sprite("sun"),
		"moon", sprite("moon"), "warn", sprite("warn"), "star", sprite("star"), "dot", sprite("dot"), "clock", CLOCK);

	/** One row of a card. {@code ms} counts down from {@code at} (the time the answer arrived). */
	private record Row(String k, String text, int color, String glyph, float v, int have, int of, long ms, String fill) {
	}

	private record Info(String kind, String title, ItemStack icon, List<Row> rows, boolean none, long at, long ttl) {
		boolean fresh(long now) {
			return now - at < ttl;
		}
	}

	private static final Map<String, Info> cache = new HashMap<>();
	/** What the crosshair is on now (null: nothing worth asking about), and since when. */
	private static String target;
	private static JsonObject targetMsg;
	private static long targetSince;
	/** The last request: its key and when. */
	private static String asked;
	private static long askedAt;
	/** The newest card that arrived, whatever its key (the layout editor shows it, so a DevScript {@code recv} can be seen). */
	private static Info lastShown;
	private static long shownSince;
	private static String shownKey;
	/** The world the cache is about. */
	private static Object lastLevel;
	/**
	 * Where the card for this target sits: {@link #BELOW}, {@link #RIGHT} or {@link #LEFT} of the crosshair, or {@link #ABOVE}
	 * it while the requests toast takes the space below (decided once
	 * per target, so it doesn't jump).
	 */
	private static int side;
	private static boolean sideDecided;
	/** Whether the requests toast showed when {@link #side} was decided, and the GUI size then: a change decides again. */
	private static boolean sideWithTpa;
	private static int sideW, sideH;
	private static final int BELOW = 0, RIGHT = 1, LEFT = -1, ABOVE = 2, HIDDEN = 3;
	/**
	 * Beside the crosshair: the width the card was cut to ({@link #MAX_W} unless narrower fits) and its top (GUI px), and its
	 * natural size and whether a scoreboard sidebar showed when that was decided (a change decides again).
	 */
	private static int sideCapW = MAX_W, sideKeyW, sideKeyH;
	private static float sideY;
	private static boolean sideSidebar;
	/** The card on screen (frozen at {@code drawnAt}), which fades out over {@link #OUT_MS} once its target is gone. */
	private static Info drawnInfo;
	private static long drawnAt, drawnSince, lostAt = -1;
	private static int drawnSide;
	private static final long OUT_MS = 120;
	/** Below the crosshair: clear of the attack indicator; beside it; above the action-bar text. */
	private static final int GAP_BELOW = 16, SIDE_X = 14, ACTION_TOP = 76;

	private InspectHud() {
	}

	public static void register() {
		Link.on("inspect_info", InspectHud::accept);
	}

	public static boolean active() {
		return Link.has("inspect");
	}

	public static void clear() {
		cache.clear();
		target = asked = shownKey = null;
		targetMsg = null;
		lastShown = null;
		lastLevel = null;
		drawnInfo = null;
		sideDecided = false;
	}

	// ------------------------------------------------------------------ asking

	/** Every client tick: what is the crosshair on, and is it time to ask? */
	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		// Another world: the same x,y,z there is something else.
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			cache.clear();
		}
		JsonObject msg = null;
		String key = null;
		// Nothing is asked while the card is switched off, a screen is open or the HUD is hidden.
		if (active() && mc.player != null && mc.level != null && mc.gui.screen() == null && !mc.gui.hud.isHidden() && HudConfig.shown(HudElement.INSPECT)) {
			HitResult hit = mc.hitResult;
			if (hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = b.getBlockPos();
				BlockState state = mc.level.getBlockState(pos);
				boolean candidate = state.getBlock() instanceof ArcanaCropBlock || ArcanaContent.isFurniture(state)
					// farmland only stands for a crop on it
					|| state.is(Blocks.FARMLAND) && mc.level.getBlockState(pos.above()).getBlock() instanceof ArcanaCropBlock;
				if (candidate) {
					key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
					msg = Link.message("inspect");
					msg.addProperty("x", pos.getX());
					msg.addProperty("y", pos.getY());
					msg.addProperty("z", pos.getZ());
				}
			}
		}
		if (key == null ? target != null : !key.equals(target)) {
			target = key;
			targetMsg = msg;
			targetSince = now;
			// Looking away and back fades the card in again.
			shownKey = null;
		}
		if (target == null || now - targetSince < STEADY_MS) return;
		Info known = cache.get(target);
		if (known != null && known.fresh(now)) return;
		// One request per target at a time; ask again if no answer came (the server drops requests that come too fast).
		if (target.equals(asked) && now - askedAt < RETRY_MS) return;
		asked = target;
		askedAt = now;
		Link.send(targetMsg);
	}

	private static void accept(JsonObject m) {
		String key = Link.str(m, "key");
		if (key.isEmpty() || key.length() > 64) return;
		long now = System.currentTimeMillis();
		if (key.equals(asked)) asked = null;
		boolean none = Link.bool(m, "none", false);
		Info info;
		if (none) {
			info = new Info("", "", ItemStack.EMPTY, List.of(), true, now, NONE_MS);
		} else {
			List<Row> rows = new ArrayList<>();
			if (m.has("rows") && m.get("rows").isJsonArray()) {
				for (JsonElement el : m.getAsJsonArray("rows")) {
					if (el.isJsonObject() && rows.size() < 8) {
						Row r = row(el.getAsJsonObject());
						if (r != null) rows.add(r);
					}
				}
			}
			long ttl = Math.max(500, Math.min(30_000, Link.lng(m, "ttl", 2000)));
			info = new Info(Link.str(m, "kind"), Link.str(m, "title"), Decode.item(m.get("icon")), List.copyOf(rows), false, now, ttl);
			lastShown = info;
		}
		cache.values().removeIf(i -> now - i.at() > i.ttl() + STALE_MS);
		if (cache.size() > 64) cache.clear();
		cache.put(key, info);
	}

	private static Row row(JsonObject o) {
		String k = Link.str(o, "k");
		int color = color(Link.str(o, "color"), Theme.TEXT);
		String colorKey = Link.str(o, "color").toUpperCase(Locale.ROOT);
		return switch (k) {
			case "bar" -> new Row(k, Link.str(o, "label"), Theme.TEXT, null, (float) Math.max(0, Math.min(1, Link.dbl(o, "v", 0))), 0, 0, 0,
				colorKey);
			case "text" -> new Row(k, Link.str(o, "c"), color, glyphFor(Link.str(o, "g"), colorKey), 0, 0, 0, 0, null);
			case "count" -> new Row(k, Link.str(o, "label"), Theme.TEXT, null, 0, Math.max(0, Link.num(o, "have", 0)), Math.max(0, Link.num(o, "of", 0)), 0, null);
			case "timer" -> new Row(k, Link.str(o, "label"), Theme.TEXT, "clock", 0, 0, 0, Math.max(0, Link.lng(o, "ms", 0)), null);
			default -> null;
		};
	}

	/** The server's glyph, or one that fits the color (rose: something's wrong, mint: good, sky: water). */
	private static String glyphFor(String g, String color) {
		if (GLYPHS.containsKey(g)) return g;
		return switch (color) {
			case "#FF8FA3" -> "warn";
			case "#86E8B0" -> "star";
			case "#8FD3FF" -> "drop";
			default -> "dot";
		};
	}

	private static int color(String hex, int fallback) {
		if (hex.length() != 7 || hex.charAt(0) != '#') return fallback;
		try {
			return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	// ------------------------------------------------------------------ drawing

	/** {@code preview}: the HUD layout editor is drawing it (the newest card, or a sample one). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (preview) {
			card(g, mc.font, lastShown != null ? lastShown : sample(now), now, 10_000, 1f, true, null);
			return;
		}
		if (mc.player == null || !active() || mc.gui.hud.isHidden() || mc.gui.screen() != null) {
			drawnInfo = null;
			return;
		}
		Info info = target == null ? null : cache.get(target);
		// Keep showing the last answer for a moment past its ttl while the fresh one is on its way.
		if (info != null && !info.none() && now - info.at() <= info.ttl() + STALE_MS) {
			if (!target.equals(shownKey)) {
				shownKey = target;
				shownSince = now;
				sideDecided = false;
			}
			drawnInfo = info;
			drawnAt = now;
			drawnSince = now - shownSince;
			lostAt = -1;
			drawnSide = card(g, mc.font, info, now, drawnSince, 1f, false, null);
			return;
		}
		// Nothing to show for the target: the last card fades out where it was, its timers held.
		if (drawnInfo == null) return;
		if (lostAt < 0) lostAt = now;
		float t = (now - lostAt) / (float) OUT_MS;
		if (t >= 1f) {
			drawnInfo = null;
			return;
		}
		card(g, mc.font, drawnInfo, drawnAt, drawnSince, 1f - t, false, drawnSide);
	}

	/** Whether the requests toast shows at its home (above the action bar, right under a card below the crosshair). */
	private static boolean tpaAtHome() {
		return TpaToast.showing() && HudConfig.get().atHome(HudElement.TPA);
	}

	/** Whether a rectangle overlaps a piece drawn earlier this frame (the quest tracker, chat game card, land name, ...). */
	private static boolean coversAny(float x, float y, float w, float h) {
		return HudConfig.overlapsOthers(HudElement.INSPECT, x, y, w, h);
	}

	private static Info sample(long now) {
		List<Row> rows = List.of(
			new Row("bar", Component.translatable("aftrhrs.hud.sample.inspect.stage").getString(), Theme.TEXT, null, 0.66f, 0, 0, 0, ""),
			new Row("text", Component.translatable("aftrhrs.hud.sample.inspect.watered").getString(), Theme.SKY, "drop", 0, 0, 0, 0, null),
			new Row("text", Component.translatable("aftrhrs.hud.sample.inspect.dark").getString(), 0xFFFF8FA3, "moon", 0, 0, 0, 0, null));
		return new Info("crop", Component.translatable("aftrhrs.hud.sample.inspect.title").getString(), ItemStack.EMPTY, rows, false, now, 2000);
	}

	private static int rowHeight(Font font, Row r, int w) {
		return switch (r.k()) {
			case "bar" -> 20;
			case "count" -> r.of() > 0 && r.of() <= 16 ? 19 : 11;
			case "text" -> 1 + 10 * lines(font, r.text(), w - 12).size();
			default -> 11;
		};
	}

	/** A text row's lines: one when it fits, else two (the second cut with "…" if still too long). */
	private static List<String> lines(Font font, String text, int w) {
		if (font.width(text) <= w) return List.of(text);
		String first = font.plainSubstrByWidth(text, w);
		int space = first.lastIndexOf(' ');
		if (space > first.length() / 2) first = first.substring(0, space);
		String rest = text.substring(first.length()).strip();
		return rest.isEmpty() ? List.of(first) : List.of(first.stripTrailing(), Theme.ellipsize(font, rest, w));
	}

	/** A card's size {w, h} at most {@code maxW} wide (at least {@link #MIN_W}); its height measured at that width. */
	private static int[] layout(Font font, Info info, long now, int maxW) {
		boolean icon = !info.icon().isEmpty();
		int headX = icon ? PAD + 22 : PAD;
		int want = headX + font.width(info.title()) + PAD;
		for (Row r : info.rows()) want = Math.max(want, PAD + rowWidth(font, r, now - info.at()) + PAD);
		int w = Math.max(MIN_W, Math.min(Math.min(MAX_W, maxW), want));
		int inner = w - 2 * PAD;
		int h = (icon ? 26 : 18) + 4;
		for (Row r : info.rows()) h += rowHeight(font, r, inner);
		h += PAD - 3;
		return new int[]{w, h};
	}

	/**
	 * A spot beside the crosshair at {@code x} for a card {@code W} × {@code H} (GUI px): the top near the crosshair, slid up
	 * to end above {@code floor}; NaN when that leaves the screen or covers another piece or the scoreboard sidebar. With no
	 * requests toast, a card that can't slide up enough stays at the crosshair (it may reach the action bar, as before).
	 */
	private static float sideSpot(float x, float W, float H, int sh, int floor, boolean tpa) {
		float y = Math.min(sh / 2f - 8, floor - H);
		if (!tpa && y < 4) y = sh / 2f - 8;
		return y >= 4 && !coversAny(x, y, W, H) ? y : Float.NaN;
	}

	/**
	 * Draws a card; {@code out} multiplies its opacity (fading out), {@code where} is where it sits ({@link #BELOW},
	 * {@link #RIGHT}, {@link #LEFT}, {@link #ABOVE} or {@link #HIDDEN}; null: this target's decision, made now if it isn't yet).
	 * Returns where it sits.
	 */
	private static int card(GuiGraphicsExtractor g, Font font, Info info, long now, long since, float out, boolean preview, Integer where) {
		boolean icon = !info.icon().isEmpty();
		int headX = icon ? PAD + 22 : PAD;
		int head = icon ? 26 : 18;
		// Its natural size: below and above the crosshair.
		int[] nat = layout(font, info, now, MAX_W);
		int nw = nat[0], nh = nat[1];
		// At home the card's top sits just under the crosshair, however tall it is; when that would reach the action bar
		// (and the held item's name, and the requests toast) it sits beside the crosshair instead, its top near it and slid up
		// to end above them, narrower if that's what fits: on the right (left of a scoreboard sidebar), or on the left when the
		// right is taken (the quest tracker), else under it after all. With the requests toast at its home a card that fits
		// nowhere beside goes above the crosshair, and with no room there either it waits until the toast has gone.
		float scale = HudConfig.drawScale(HudConfig.get().pos(HudElement.INSPECT).scale);
		int sw = g.guiWidth(), sh = g.guiHeight();
		int at, capW = sideCapW;
		float sy = sideY;
		if (where != null) {
			at = where;
		} else if (preview || !sideDecided || sideWithTpa != tpaAtHome() || sideW != sw || sideH != sh || sideKeyW != nw || sideKeyH != nh
				|| sideSidebar != HudConfig.sidebarShown()) {
			boolean tpa = tpaAtHome();
			int floor = Math.min(sh - ACTION_TOP, HudConfig.requestsFloor(sh));
			at = BELOW;
			capW = MAX_W;
			sy = 0;
			if (sh / 2 + GAP_BELOW + nh * scale > floor) {
				at = -99;
				float[] bar = HudConfig.sidebarRect(font, sw, sh);
				// Right of the crosshair, left of a sidebar.
				float rx = sw / 2f + SIDE_X;
				float limit = bar != null ? bar[0] - 4 : sw - 4;
				int cap = Math.min(MAX_W, (int) Math.floor((limit - rx) / scale));
				if (cap >= MIN_W) {
					int[] l = layout(font, info, now, cap);
					float y = sideSpot(rx, l[0] * scale, l[1] * scale, sh, floor, tpa);
					if (!Float.isNaN(y)) {
						at = RIGHT;
						capW = cap;
						sy = y;
					}
				}
				if (at == -99) {
					cap = Math.min(MAX_W, (int) Math.floor((sw / 2f - SIDE_X - 4) / scale));
					if (cap >= MIN_W) {
						int[] l = layout(font, info, now, cap);
						float W = l[0] * scale;
						float y = sideSpot(sw / 2f - SIDE_X - W, W, l[1] * scale, sh, floor, tpa);
						if (!Float.isNaN(y)) {
							at = LEFT;
							capW = cap;
							sy = y;
						}
					}
				}
				if (at == -99) {
					float W = nw * scale, H = nh * scale, ay = sh / 2f - GAP_BELOW - H;
					// Nothing beside it either: above the crosshair rather than under the toast; else nothing until it has gone.
					if (tpa && ay >= 4 && !coversAny(sw / 2f - W / 2, ay, W, H)) at = ABOVE;
					else at = tpa && !preview ? HIDDEN : BELOW;
					capW = MAX_W;
				}
			}
			if (!preview) {
				// Back from waiting for the toast: it fades in again.
				if (sideDecided && side == HIDDEN && at != HIDDEN) {
					shownSince = System.currentTimeMillis();
					since = 0;
				}
				side = at;
				sideDecided = true;
				sideWithTpa = tpa;
				sideW = sw;
				sideH = sh;
				sideKeyW = nw;
				sideKeyH = nh;
				sideSidebar = HudConfig.sidebarShown();
				sideCapW = capW;
				sideY = sy;
			}
		} else {
			at = side;
		}
		if (at == HIDDEN) return at;
		boolean beside = at == RIGHT || at == LEFT;
		int w = nw, h = nh;
		if (beside && capW < MAX_W) {
			int[] l = layout(font, info, now, capW);
			w = l[0];
			h = l[1];
		}
		int inner = w - 2 * PAD;
		float W = w * scale, H = h * scale;
		int shiftX = at == RIGHT ? Math.round(W / 2f) + SIDE_X : at == LEFT ? -Math.round(W / 2f) - SIDE_X : 0;
		int shiftY = at == ABOVE ? -Math.round(H / 2f) - HudElement.INSPECT.dy - GAP_BELOW
			: beside ? Math.round(sy - (sh / 2f - H / 2f)) - HudElement.INSPECT.dy
			: Math.round(H / 2f) - HudElement.INSPECT.dy + GAP_BELOW;
		if (!HudConfig.begin(g, HudElement.INSPECT, w, h, shiftX, shiftY)) return at;
		float in = Math.min(1f, since / 140f);
		int y0 = Math.round((1f - in) * 4);
		float alpha = in * out;
		// Nearly clear text would be drawn opaque (vanilla ignores alpha under 4): nothing then.
		if (alpha < 0.05f) {
			HudConfig.end(g);
			return at;
		}
		g.blitSprite(RenderPipelines.GUI_TEXTURED, CARD, 0, y0, w, h, alpha);
		int ta = Math.round(alpha * 255) << 24;
		if (icon) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, WELL, PAD - 1, y0 + PAD - 2, 20, 20, alpha);
			// Items have no opacity: shown once the card is mostly there.
			if (alpha >= 0.6f) g.item(info.icon(), PAD + 1, y0 + PAD);
		}
		String title = Theme.ellipsize(font, info.title(), w - headX - PAD);
		g.text(font, title, headX, y0 + (icon ? PAD + 4 : PAD), (Theme.MOON & 0xFFFFFF) | ta, true);
		int y = y0 + head;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, RULE, PAD + 1, y - 1, w - 2 * PAD - 2, 1, alpha);
		y += 4;
		for (Row r : info.rows()) {
			drawRow(g, font, r, PAD, y, inner, now - info.at(), alpha, ta);
			y += rowHeight(font, r, inner);
		}
		HudConfig.end(g);
		return at;
	}

	private static int rowWidth(Font font, Row r, long age) {
		return switch (r.k()) {
			case "bar" -> font.width(r.text());
			case "text" -> 12 + font.width(r.text());
			case "count" -> font.width(r.text()) + 8 + font.width(r.have() + " / " + r.of());
			case "timer" -> 12 + font.width(r.text()) + 8 + font.width("~00m 00s"); // steady while it counts down
			default -> 0;
		};
	}

	private static void drawRow(GuiGraphicsExtractor g, Font font, Row r, int x, int y, int w, long age, float alpha, int ta) {
		switch (r.k()) {
			case "bar" -> {
				g.text(font, Theme.ellipsize(font, r.text(), w), x, y, (Theme.TEXT & 0xFFFFFF) | ta, true);
				bar(g, x, y + 10, w, r.v(), FILLS.getOrDefault(r.fill(), FILL), alpha);
			}
			case "text" -> {
				Identifier glyph = GLYPHS.get(r.glyph());
				if (glyph != null) g.blitSprite(RenderPipelines.GUI_TEXTURED, glyph, x, y - 1, 9, 9, alpha);
				int ly = y;
				for (String line : lines(font, r.text(), w - 12)) {
					g.text(font, line, x + 12, ly, (r.color() & 0xFFFFFF) | ta, true);
					ly += 10;
				}
			}
			case "count" -> {
				String n = r.have() + " / " + r.of();
				int nw = font.width(n);
				boolean full = r.of() > 0 && r.have() >= r.of();
				g.text(font, Theme.ellipsize(font, r.text(), w - nw - 6), x, y, (Theme.TEXT & 0xFFFFFF) | ta, true);
				g.text(font, n, x + w - nw, y, ((full ? Theme.MOON : Theme.SKY) & 0xFFFFFF) | ta, true);
				if (r.of() > 0 && r.of() <= 16) {
					// one pip per slot (at most 16, so they always fit the card)
					int step = Math.max(5, Math.min(7, w / r.of()));
					for (int i = 0; i < r.of(); i++) {
						g.blitSprite(RenderPipelines.GUI_TEXTURED, i < r.have() ? PIP : PIP_EMPTY, x + i * step, y + 10, 5, 5, alpha);
					}
				}
			}
			case "timer" -> {
				String t = time(r.ms() - age);
				int tw = font.width(t);
				g.blitSprite(RenderPipelines.GUI_TEXTURED, CLOCK, x, y - 1, 9, 9, alpha);
				g.text(font, Theme.ellipsize(font, r.text(), w - 12 - tw - 6), x + 12, y, (Theme.TEXT & 0xFFFFFF) | ta, true);
				g.text(font, t, x + w - tw, y, (Theme.MOON & 0xFFFFFF) | ta, true);
			}
			default -> {
			}
		}
	}

	private static void bar(GuiGraphicsExtractor g, int x, int y, int w, float v, Identifier fill, float alpha) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, TRACK, x, y, w, 7, alpha);
		int fw = Math.round((w - 2) * v);
		if (fw >= 4) g.blitSprite(RenderPipelines.GUI_TEXTURED, fill, x + 1, y + 1, fw, 5, alpha);
		else if (fw > 0) g.fill(x + 1, y + 2, x + 1 + fw, y + 6, 0xFFC3A6FF);
	}

	/** "~4m 10s", "45s", "1h 5m": the time left, counted down here; "now" once it ran out. */
	private static String time(long ms) {
		if (ms <= 0) return Component.translatable("aftrhrs.inspect.now").getString();
		long s = (ms + 999) / 1000;
		if (s < 60) return s + "s";
		long m = s / 60;
		if (m < 10) return "~" + m + "m " + (s % 60) + "s";
		if (m < 60) return "~" + m + "m";
		return "~" + (m / 60) + "h " + (m % 60) + "m";
	}
}
