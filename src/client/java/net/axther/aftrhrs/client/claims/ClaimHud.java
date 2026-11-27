package net.axther.aftrhrs.client.claims;

import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.hud.HudConfig;
import net.axther.aftrhrs.client.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;

/**
 * The claims HUD for modded players: the claim tool's panel (mode, what the selection costs, the budget bar), a chip
 * naming the claim you stand in while holding the view item, a region banner when you walk into someone's claim, and
 * a toast when one of your claims changes.
 */
public final class ClaimHud {
	private static final String[] MODES = {"rect", "chunk", "circle", "admin"};

	private static int standingId = Integer.MIN_VALUE;
	private static String bannerTitle, bannerSub;
	private static int bannerColor;
	private static long bannerAt;

	private static Component toastTitle, toastLine;
	private static int toastColor;
	private static long toastAt;

	private ClaimHud() {
	}

	/** Every client tick: notices when the player walks into or out of a claim. */
	public static void tick(Minecraft mc) {
		ClaimState s = ClaimState.INSTANCE;
		Player p = mc.player;
		if (p == null || !s.active()) {
			standingId = Integer.MIN_VALUE;
			return;
		}
		ClaimState.Claim here = s.at(p.getBlockX(), p.getBlockZ());
		int id = here == null ? 0 : here.id();
		if (standingId == Integer.MIN_VALUE) {
			standingId = id;
			return;
		}
		if (id == standingId) return;
		ClaimState.Claim before = null;
		for (ClaimState.Claim c : s.claims) if (c.id() == standingId) before = c;
		standingId = id;
		if (here != null && !here.own() && here.enter()) {
			String greet = here.greeting() != null && !here.greeting().isEmpty() ? strip(here.greeting()) : null;
			banner(here.admin() ? here.name() : here.name(), greet != null ? greet
				: here.admin() ? Component.translatable("aftrhrs.claims.banner.admin").getString()
				: Component.translatable("aftrhrs.claims.banner.owned", here.owner()).getString() + relationNote(here), s.colorOf(here.rel()));
		} else if (here == null && before != null && !before.own() && before.enter()) {
			banner(Component.translatable("aftrhrs.claims.banner.wild").getString(), Component.translatable("aftrhrs.claims.banner.left", before.name()).getString(), 0xC9C2E6);
		}
	}

	private static String relationNote(ClaimState.Claim c) {
		if (c.admin()) return "";
		if (c.level() != null && !c.level().isEmpty()) return "  ·  " + Component.translatable("aftrhrs.claims.level." + c.level()).getString();
		return c.pvp() ? "  ·  " + Component.translatable("aftrhrs.claims.pvp").getString() : "";
	}

	private static String strip(String miniMessage) {
		return miniMessage.replaceAll("<[^>]+>", "");
	}

	private static void banner(String title, String sub, int color) {
		bannerTitle = title;
		bannerSub = sub;
		bannerColor = color;
		bannerAt = System.currentTimeMillis();
	}

	static void toast(boolean grow, String name, long left, List<ClaimState.Piece> pieces) {
		long blocks = 0;
		for (ClaimState.Piece p : pieces) blocks += p.blocks();
		toastTitle = Component.translatable(grow ? "aftrhrs.claims.toast.claimed" : "aftrhrs.claims.toast.unclaimed", name);
		toastLine = left >= 0
			? Component.translatable("aftrhrs.claims.toast.line", String.format(Locale.ROOT, "%,d", blocks), left)
			: Component.translatable("aftrhrs.claims.toast.blocks", String.format(Locale.ROOT, "%,d", blocks));
		toastColor = grow ? ClaimState.INSTANCE.colOwn : 0xFF8FA3;
		toastAt = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------ drawing

	/** {@code preview}: the HUD layout editor is drawing it (every piece, with sample text where nothing is going on). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		Player p = mc.player;
		ClaimState s = ClaimState.INSTANCE;
		// The layout editor also works without a world (the mods list): then only the samples show.
		if (!preview && (p == null || !s.active() || mc.gui.hud.isHidden())) return;
		Font font = mc.font;
		long now = System.currentTimeMillis();
		boolean tool = p != null && s.active() && s.holdingTool(p);
		// A screen over the HUD (inventory, menus; chat and the bed's chat don't count) hides the tool panel and the
		// land chip; the banner and the toast still play out (the toast keeps its drop below the panel's spot).
		boolean covered = !preview && HudConfig.covered(mc);
		if ((tool || preview) && !covered) toolPanel(g, font, mc, p, s);
		if (!covered && (preview || (p != null && s.active() && !tool && (s.holdingView(p) || s.pinned)))) standChip(g, font, p, s);
		drawBanner(g, font, now, preview);
		drawToast(g, font, tool, now, preview);
	}

	private static void toolPanel(GuiGraphicsExtractor g, Font font, Minecraft mc, Player p, ClaimState s) {
		int pw = 236, ph = 66;
		HudConfig cfg = HudConfig.get();
		// At home under the compass at home: make room for it.
		int y = cfg.atHome(HudElement.CLAIM_TOOL) && cfg.atHome(HudElement.COMPASS) && HudConfig.shown(HudElement.COMPASS)
			&& net.axther.aftrhrs.client.places.PlaceState.INSTANCE.active() ? 10 : 0;
		if (!HudConfig.begin(g, HudElement.CLAIM_TOOL, pw, ph, y)) return;
		try {
			toolPanelBody(g, font, mc, p, s, 0, 0, pw);
		} finally {
			HudConfig.end(g);
		}
	}

	private static void toolPanelBody(GuiGraphicsExtractor g, Font font, Minecraft mc, Player p, ClaimState s, int x, int y, int pw) {
		int ph = 66;
		Theme.blit(g, Theme.MENU_PANEL, x, y, pw, ph);
		// Mode chips.
		int chipW = 42, cx = x + 8;
		for (String m : MODES) {
			if (m.equals("admin") && !s.mode.equals("admin")) continue;
			boolean on = m.equals(s.mode);
			Theme.blit(g, on ? Theme.MENU_TAB_SELECTED : Theme.MENU_TAB, cx, y + 6, chipW, 13);
			g.centeredText(font, Component.translatable("aftrhrs.claims.mode." + m), cx + chipW / 2, y + 9, on ? Theme.MOON : Theme.MUTED);
			cx += chipW + 3;
		}
		// What the selection is / would cost.
		Component line;
		ClaimState.Piece sel = p != null && s.active() ? ClaimWalls.selection(mc, p, s) : null;
		if (sel != null) {
			long blocks = sel.blocks();
			String size = sel.round() ? Component.translatable("aftrhrs.claims.radius", sel.radius()).getString() : (sel.maxX() - sel.minX() + 1) + " × " + (sel.maxZ() - sel.minZ() + 1);
			boolean tooBig = sel.round() && sel.radius() > s.maxRadius;
			boolean tooSmall = sel.round() ? sel.radius() < s.minRadius : Math.min(sel.maxX() - sel.minX(), sel.maxZ() - sel.minZ()) + 1 < s.minWidth;
			long cost = ClaimShapes.chunks(blocks);
			line = Component.translatable(s.resizing != null ? "aftrhrs.claims.hud.resizing" : "aftrhrs.claims.hud.selection", size,
				String.format(Locale.ROOT, "%,d", blocks), cost);
			int col = tooBig || tooSmall || cost > s.left ? 0xFFFF8F8F : Theme.TEXT;
			g.text(font, line, x + 8, y + 24, col, true);
			if (tooBig) g.text(font, Component.translatable("aftrhrs.claims.hud.too-big", s.maxRadius), x + 8, y + 36, 0xFFFF8F8F, true);
			else if (tooSmall) g.text(font, Component.translatable("aftrhrs.claims.hud.too-small", s.minWidth), x + 8, y + 36, 0xFFFF8F8F, true);
		} else {
			line = Component.translatable("aftrhrs.claims.hud.how." + (s.mode.equals("chunk") ? "chunk" : s.mode.equals("circle") ? "circle" : "rect"));
			g.text(font, line, x + 8, y + 24, Theme.MUTED, true);
			ClaimState.Claim here = p != null && s.active() ? s.at(p.getBlockX(), p.getBlockZ()) : null;
			Component where = here == null ? Component.translatable("aftrhrs.claims.hud.wild")
				: Component.literal(ClaimState.glyphOf(here.rel()) + " ").append(Component.translatable("aftrhrs.claims.hud.in", here.name(), here.owner()));
			g.text(font, where, x + 8, y + 36, here == null ? Theme.MUTED : 0xFF000000 | s.colorOf(here.rel()), true);
		}
		budgetBar(g, font, s, x + 8, y + 57, pw - 16);
	}

	private static void budgetBar(GuiGraphicsExtractor g, Font font, ClaimState s, int x, int y, int w) {
		long total = Math.max(1, s.total);
		float used = Mth.clamp((total - s.left) / (float) total, 0f, 1f);
		g.fill(x, y, x + w, y + 3, 0xFF1A1630);
		int fill = Math.round(w * used);
		g.fillGradient(x, y, x + fill, y + 3, 0xFF000000 | s.colOwn, 0xFF000000 | Theme.SKY);
		String label = Component.translatable("aftrhrs.claims.hud.left", s.left, s.total).getString();
		g.text(font, label, x + w - font.width(label), y - 10, 0xFFC9C2E6, true);
	}

	private static void standChip(GuiGraphicsExtractor g, Font font, Player p, ClaimState s) {
		ClaimState.Claim here = p != null && s.active() ? s.at(p.getBlockX(), p.getBlockZ()) : null;
		Component text = here == null ? Component.translatable("aftrhrs.claims.hud.wild")
			: Component.translatable("aftrhrs.claims.hud.in", here.name(), here.owner());
		int w = font.width(text) + 22;
		if (!HudConfig.begin(g, HudElement.LAND, w, 16)) return;
		Theme.blit(g, Theme.MENU_INSET, 0, 0, w, 16);
		// The relation as a mark in its color (not color alone): ✔ own, ✚ trusted, ★ admin, ✖ others, · wilderness.
		g.text(font, ClaimState.glyphOf(here == null ? null : here.rel()), 4, 4, here == null ? 0xFF6B6487 : 0xFF000000 | s.colorOf(here.rel()), true);
		g.text(font, text, 14, 4, Theme.TEXT, true);
		HudConfig.end(g);
	}

	private static void drawBanner(GuiGraphicsExtractor g, Font font, long now, boolean preview) {
		String title = bannerTitle, sub = bannerSub;
		int color = bannerColor;
		long age = now - bannerAt;
		if (title != null && age > 3600) {
			bannerTitle = null;
			title = null;
		}
		if (title == null) {
			if (!preview) return;
			title = Component.translatable("aftrhrs.hud.sample.banner").getString();
			sub = Component.translatable("aftrhrs.claims.banner.admin").getString();
			color = 0xFFE7A8;
			age = 1000;
		}
		float a = age < 400 ? age / 400f : age > 2800 ? 1f - (age - 2800) / 800f : 1f;
		int alpha = Math.max(4, Math.round(a * 255));
		float scale = 2f;
		int tw = font.width(title), sw = sub != null ? font.width(sub) : 0;
		int w = Math.max(Math.round(tw * scale) + 36, sw) + 4, h = 37, cx = w / 2;
		if (!HudConfig.begin(g, HudElement.BANNER, w, h)) return;
		g.pose().pushMatrix();
		g.pose().translate(cx, 0);
		g.pose().scale(scale, scale);
		g.text(font, title, -tw / 2, 0, (alpha << 24) | 0xF1EDFF, true);
		g.pose().popMatrix();
		// A thin rule in the claim's color that draws itself outwards.
		int half = Math.round(Math.min(1f, age / 600f) * (tw * scale / 2 + 18));
		g.fill(cx - half, 21, cx + half, 22, (alpha << 24) | color);
		if (sub != null) g.text(font, sub, cx - sw / 2, 27, (alpha << 24) | 0xC9C2E6, true);
		HudConfig.end(g);
	}

	private static void drawToast(GuiGraphicsExtractor g, Font font, boolean tool, long now, boolean preview) {
		Component title = toastTitle, line = toastLine;
		int color = toastColor;
		long age = now - toastAt;
		if (title != null && age > 4200) {
			toastTitle = null;
			title = null;
		}
		if (title == null) {
			if (!preview) return;
			title = Component.translatable("aftrhrs.claims.toast.claimed", Component.translatable("aftrhrs.hud.sample.claim").getString());
			line = Component.translatable("aftrhrs.claims.toast.line", "256", 62);
			color = ClaimState.INSTANCE.colOwn;
			age = 1000;
		}
		float slide = age < 300 ? 1f - age / 300f : age > 3700 ? (age - 3700) / 500f : 0f;
		int tw = Math.max(font.width(title), font.width(line)) + 28;
		HudConfig cfg = HudConfig.get();
		// At home it drops below the tool panel while the tool is in hand.
		int drop = (tool || preview) && cfg.atHome(HudElement.CLAIM_TOAST) && cfg.atHome(HudElement.CLAIM_TOOL) && HudConfig.shown(HudElement.CLAIM_TOOL) ? 70 : 0;
		// The drop is the element's home shift, so its outline, hit box and overlap checks match the drawn card.
		if (!HudConfig.begin(g, HudElement.CLAIM_TOAST, tw, 30, drop)) return;
		int y = -Math.round(slide * 40);
		Theme.blit(g, Theme.MENU_PANEL, 0, y, tw, 30);
		g.fill(6, y + 7, 10, y + 23, 0xFF000000 | color);
		g.text(font, title, 16, y + 7, Theme.MOON, true);
		g.text(font, line, 16, y + 18, Theme.TEXT, true);
		HudConfig.end(g);
	}

	public static void clear() {
		standingId = Integer.MIN_VALUE;
		bannerTitle = null;
		toastTitle = null;
	}
}
