package net.axther.aftrhrs.client.hud;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Requests as a toast: teleport requests ({@code tpa} feature: who wants to come to you, or wants you to come) and
 * trade requests ({@code trade} feature), each with a countdown and the accept / deny keys. Both kinds share one
 * queue, so the keys always answer exactly the request on screen ({@code /tpaccept <name>}, {@code /trade accept
 * <name>}). The keys are ignored for a moment after a toast appears (so a key already on its way can't answer a
 * request you haven't read), and a request arriving while one is showing waits its turn instead of replacing it.
 */
public final class TpaToast {
	private static final long GRACE_MS = 750;
	/** The widest a sender's name is shown. */
	private static final int NAME_W = 100;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final Identifier TRADE_ICON = Identifier.fromNamespaceAndPath(Companion.ID, "hud/trade");
	private static final Identifier TELEPORT_ICON = Identifier.fromNamespaceAndPath(Companion.ID, "hud/teleport");

	/** What a request asks for. TPA and TPA_HERE share one slot per sender on the server; TRADE has its own. */
	private enum Kind {
		TPA("aftrhrs.tpa.to", "tpaccept ", "tpdeny ", TELEPORT_ICON, 12, 12),
		TPA_HERE("aftrhrs.tpa.here", "tpaccept ", "tpdeny ", TELEPORT_ICON, 12, 12),
		TRADE("aftrhrs.trade.toast", "trade accept ", "trade deny ", TRADE_ICON, 15, 12);

		final String title;
		final String accept;
		final String deny;
		final Identifier icon;
		final int iconW;
		final int iconH;

		Kind(String title, String accept, String deny, Identifier icon, int iconW, int iconH) {
			this.title = title;
			this.accept = accept;
			this.deny = deny;
			this.icon = icon;
			this.iconW = iconW;
			this.iconH = iconH;
		}

		boolean trade() {
			return this == TRADE;
		}
	}

	private record Request(Kind kind, String shown, String name, long until) {
		/** The same request slot on the server: a newer one from that player replaces it, a clear drops it. */
		boolean sameAs(boolean trade, String other) {
			return kind.trade() == trade && name.equalsIgnoreCase(other);
		}
	}

	private static final Deque<Request> queue = new ArrayDeque<>();
	private static Request current;
	/** When {@code current} is (or will be, after the last one has left) on screen. */
	private static long shownAt;
	/** The request that just went, drawn sliding away for {@link #LEAVE_MS}; never answered by the keys. */
	private static Request leaving;
	private static long leftAt, leavingSince;
	/** Its countdown bar, held where it was when it left. */
	private static float leavingLeft;
	private static final long LEAVE_MS = 200;

	private TpaToast() {
	}

	public static void register() {
		Link.on("tpa_request", m -> accept(m, Link.bool(m, "here", false) ? Kind.TPA_HERE : Kind.TPA));
		Link.on("tpa_clear", m -> dismiss(m, false));
		Link.on("trade_request", m -> accept(m, Kind.TRADE));
		Link.on("trade_clear", m -> dismiss(m, true));
	}

	private static void accept(JsonObject m, Kind kind) {
		String name = Link.str(m, "name");
		if (!NAME.matcher(name).matches()) return; // without the real name the keys couldn't target this request
		String shown = Link.str(m, "from");
		Request r = new Request(kind, shown.isEmpty() ? name : shown, name,
			System.currentTimeMillis() + Math.max(5, Math.min(600, Link.lng(m, "seconds", 60))) * 1000L);
		boolean trade = kind.trade();
		queue.removeIf(q -> q.sameAs(trade, r.name));
		if (current != null && current.sameAs(trade, r.name)) {
			current = r;
			return;
		}
		if (current == null) show(r);
		else queue.add(r);
	}

	/**
	 * The request was answered elsewhere (chat, command), canceled or ran out: {@code name}, or {@code all}. A
	 * {@code tpa_clear} only drops teleport requests and a {@code trade_clear} only trade requests.
	 */
	private static void dismiss(JsonObject m, boolean trade) {
		if (Link.bool(m, "all", false)) {
			queue.removeIf(q -> q.kind.trade() == trade);
			if (current != null && current.kind.trade() == trade) next();
			return;
		}
		String name = Link.str(m, "name");
		if (name.isEmpty()) return;
		queue.removeIf(q -> q.sameAs(trade, name));
		if (current != null && current.sameAs(trade, name)) next();
	}

	private static void show(Request r) {
		current = r;
		long now = System.currentTimeMillis();
		// After the one leaving has gone (its keys' grace counts from then, too).
		shownAt = leaving != null && now - leftAt < LEAVE_MS ? leftAt + LEAVE_MS : now;
		if (HudConfig.shown(HudElement.TPA)) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.6f, 0.6f));
	}

	/**
	 * Whether a request toast is up, or about to be once the one leaving has gone (the look-at card keeps clear of it);
	 * never while the Requests piece is switched off.
	 */
	static boolean showing() {
		return current != null && HudConfig.shown(HudElement.TPA);
	}

	private static void next() {
		long now = System.currentTimeMillis();
		if (current != null && HudConfig.shown(HudElement.TPA) && now >= shownAt) {
			leaving = current;
			leftAt = now;
			leavingSince = now - shownAt;
			leavingLeft = barLeft(current, now);
		}
		current = null;
		while (!queue.isEmpty()) {
			Request r = queue.poll();
			if (r.until > now) {
				show(r);
				return;
			}
		}
	}

	public static void clear() {
		current = null;
		leaving = null;
		queue.clear();
	}

	/** Every tick: the accept / deny keys while a request is showing. */
	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (current != null && now >= current.until) next();
		// With the toast switched off the keys do nothing: they must never answer a request you can't see.
		boolean ready = current != null && mc.player != null && mc.gui.screen() == null && now - shownAt >= GRACE_MS && HudConfig.shown(HudElement.TPA)
				&& !mc.gui.hud.isHidden() // F1 hides the toast, so the keys must not answer it
				&& !playerListShown(mc); // the player list draws over the toast
		while (Companion.TPA_ACCEPT_KEY.consumeClick()) {
			if (ready) {
				mc.player.connection.sendCommand(current.kind.accept + current.name);
				next();
				ready = false;
			}
		}
		while (Companion.TPA_DENY_KEY.consumeClick()) {
			if (ready) {
				mc.player.connection.sendCommand(current.kind.deny + current.name);
				next();
				ready = false;
			}
		}
	}

	/** The player list (Tab) is drawn over the Companion HUD pieces, so while it shows the toast may be covered. */
	private static boolean playerListShown(Minecraft mc) {
		return mc.options.keyPlayerList.isDown();
	}

	/** How much of {@code r}'s time is left (the countdown bar), 0 to 1. */
	private static float barLeft(Request r, long now) {
		long total = Math.max(1, r.until - shownAt);
		return Mth.clamp((r.until - now) / (float) total, 0f, 1f);
	}

	/** {@code preview}: the HUD layout editor is drawing it (a sample request when none is waiting). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		Request r = current;
		long now = System.currentTimeMillis();
		long since = now - shownAt;
		if (!preview && (mc.player == null || mc.gui.hud.isHidden())) return;
		boolean sample = r == null && preview;
		if (sample) {
			r = new Request(Kind.TPA, "Alex", "Alex", now + 45_000);
			since = 15_000;
		}
		if (!preview && leaving != null) {
			long t = now - leftAt;
			if (t >= LEAVE_MS) leaving = null;
			else drawCard(g, mc, leaving, leavingSince, t / (float) LEAVE_MS, leavingLeft, false, 0);
		}
		// A request waiting for the one leaving to go isn't drawn yet.
		if (r == null || since < 0 && !preview) return;
		since = Math.max(0, since);
		int queued = 0;
		for (Request q : queue) if (q.until > now) queued++;
		// The keys answer only once its grace is over and nothing covers the HUD (as tick() checks).
		boolean live = since >= GRACE_MS && (preview || mc.gui.screen() == null && !playerListShown(mc));
		float left = sample ? 0.75f : barLeft(r, now);
		drawCard(g, mc, r, since, 0f, left, live, queued);
	}

	/** One toast: {@code out} 0 to 1 while it slides away and fades, {@code left} its countdown bar. */
	private static void drawCard(GuiGraphicsExtractor g, Minecraft mc, Request r, long since, float out, float left, boolean live, int queued) {
		Font font = mc.font;
		float slide = since < 300 ? 1f - since / 300f : 0f;
		// A long name is cut, so the toast stays a sensible size (the commands still use the real name).
		String who = r.shown;
		if (font.width(who) > NAME_W) who = font.plainSubstrByWidth(who, NAME_W - font.width("…")) + "…";
		Component title = Component.translatable(r.kind.title, who);
		Component keys = Component.translatable("aftrhrs.tpa.keys", Companion.TPA_ACCEPT_KEY.getTranslatedKeyMessage(), Companion.TPA_DENY_KEY.getTranslatedKeyMessage());
		String more = queued > 0 ? "+" + queued : null;
		int moreW = more == null ? 0 : font.width(more) + 6;
		int tx = 10 + r.kind.iconW + 5;
		int tw = Math.max(font.width(title) + moreW, font.width(keys)) + tx + 12;
		if (!HudConfig.begin(g, HudElement.TPA, tw, 32)) return;
		float e = out * out * (3 - 2 * out);
		int y = Math.round(slide * 40 + e * 40);
		float alpha = 1f - out;
		if (alpha >= 0.05f) {
			int a = Math.round(alpha * 255) << 24;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, Theme.MENU_PANEL, 0, y, tw, 32, alpha);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, r.kind.icon, 10, y + (31 - r.kind.iconH) / 2, r.kind.iconW, r.kind.iconH, alpha);
			g.text(font, title, tx, y + 7, (Theme.MOON & 0xFFFFFF) | a, true);
			g.text(font, keys, tx, y + 19, ((live ? Theme.TEXT : Theme.DIM) & 0xFFFFFF) | a, true);
			g.fill(4, y + 29, 4 + Math.round((tw - 8) * left), y + 30, 0xC3A6FF | a);
			// Right-aligned inside the padding, clear of the title.
			if (more != null) g.text(font, more, tw - 10 - font.width(more), y + 7, (Theme.MUTED & 0xFFFFFF) | a, true);
		}
		HudConfig.end(g);
	}
}
