package net.axther.aftrhrs.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.client.Companion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedSet;

/**
 * The player's HUD choices, kept in {@code config/aftrhrs/hud.json}: which pieces show, where each sits and how big it
 * is, plus the world effects (waypoint beams, claim walls) and a few per-piece options. Everything is client-side;
 * turning a piece off never hides anything Feather would otherwise tell you (the chat lines stay).
 *
 * <p>Drawing goes through {@link #begin}/{@link #end}: an element draws itself at (0, 0) in its own size and the layout
 * moves and scales it into place, remembering where it landed for the {@link HudEditorScreen}.
 *
 * <p>When the pieces show ({@link #covered}):
 * <ul>
 * <li>No screen, or chat: everything draws, except the look-at card and the smithing note, which need no screen at all.
 * The requests toast's keys are live only with no screen.</li>
 * <li>Any other screen: only the timed pop-ups draw (quest toast, claim toast, region banner), so they don't expire
 * unseen, plus the requests toast with its keys dimmed. Tracker, pets, purse, compass, land chip, claim tool panel and
 * chat-game card hide. The purse's delta lines and roll wait until nothing covers.</li>
 * <li>F1 hides everything. Quiet mode keeps only requests.</li>
 * </ul>
 */
public final class HudConfig {
	public static final HudConfig INSTANCE = new HudConfig();
	public static final float MIN_SCALE = 0.5f, MAX_SCALE = 2.5f;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Where one element sits. {@code custom} false means its built-in home ({@link HudElement}). */
	public static final class Pos {
		public boolean on = true;
		public float scale = 1f;
		public boolean custom;
		public float ax, ay;
		public int dx, dy;
	}

	/** Waypoint beams: every place, only your death point and graves, or none. */
	public enum Beams {ALL, URGENT, OFF}

	private final Map<HudElement, Pos> pos = new EnumMap<>(HudElement.class);
	public Beams beams = Beams.ALL;
	public boolean beamLabels = true;
	public boolean claimWalls = true;
	public boolean claimFx = true;
	public boolean claimSounds = true;
	public int compassWidth = 220;
	public int questRows = 6;
	public int petCount = 3;
	public boolean petsVertical;
	/** A soft page turn when a chat game card appears (off by default: chat already chimes). */
	public boolean chatGameSound;
	/** The coin purse shows "•••" instead of numbers (streaming). */
	public boolean walletHideAmounts;
	/** Photo mode: F1 (vanilla's hidden HUD) also hides beams, their names and claim walls. */
	public boolean hideFxWithF1 = true;
	/** Quiet mode (its key): every Companion world effect and HUD piece off but the teleport toast. Not saved. */
	public static boolean quiet;
	private boolean loaded;

	/**
	 * The rectangles elements were last drawn in (GUI pixels): x, y, w, h, in draw order (the last drawn is on top). For
	 * the layout editor, which clears it ({@link #forgetDrawn}) and redraws every piece each frame; outside the editor it
	 * is never cleared, so a piece that stopped drawing keeps its old rectangle here.
	 */
	private final LinkedHashMap<HudElement, float[]> drawn = new LinkedHashMap<>();
	/**
	 * Where pieces drew this frame (reset by {@link HudEditorScreen#drawAll} before anything draws) and last frame. The
	 * layout uses these to keep pieces out of each other's way; unlike {@link #drawn} they never hold a stale rectangle.
	 */
	static final Map<HudElement, float[]> frameRects = new EnumMap<>(HudElement.class), lastFrameRects = new EnumMap<>(HudElement.class);
	/** True while the layout editor draws: hidden elements still report where they would be. */
	static boolean editing;

	private HudConfig() {
		for (HudElement e : HudElement.values()) pos.put(e, new Pos());
	}

	public static HudConfig get() {
		if (!INSTANCE.loaded) INSTANCE.load();
		return INSTANCE;
	}

	public Pos pos(HudElement e) {
		return pos.get(e);
	}

	/** Whether the player wants this piece. */
	public static boolean shown(HudElement e) {
		return get().pos(e).on;
	}

	public static void toggle(HudElement e) {
		Pos p = get().pos(e);
		p.on = !p.on;
		INSTANCE.save();
	}

	/**
	 * Whether Companion world effects (beams and their names, claim walls) are hidden right now: quiet mode, or F1
	 * with "Hide world effects with F1" on.
	 */
	public static boolean hidden() {
		return quiet || (get().hideFxWithF1 && Minecraft.getInstance().gui.hud.isHidden());
	}

	/** True when a screen other than chat is open over the HUD (inventories, menus, pause...). Chat counts as not covering: you type over the game. */
	public static boolean covered(Minecraft mc) {
		Screen s = mc.gui.screen();
		return s != null && !(s instanceof ChatScreen); // InBedChatScreen extends ChatScreen
	}

	public boolean atHome(HudElement e) {
		Pos p = pos(e);
		return !p.custom && p.scale == 1f;
	}

	public void reset(HudElement e) {
		Pos p = pos(e);
		boolean on = p.on;
		pos.put(e, new Pos());
		pos(e).on = on;
	}

	public void resetAll() {
		for (HudElement e : HudElement.values()) pos.put(e, new Pos());
		beams = Beams.ALL;
		beamLabels = claimWalls = claimFx = claimSounds = true;
		compassWidth = 220;
		questRows = 6;
		petCount = 3;
		petsVertical = false;
		chatGameSound = false;
		walletHideAmounts = false;
		hideFxWithF1 = true;
		save();
	}

	// ------------------------------------------------------------------ placing

	/** A new frame of HUD drawing starts: this frame's rectangles become last frame's. */
	static void newFrame() {
		sidebarFor = null;
		lastFrameRects.clear();
		lastFrameRects.putAll(frameRects);
		frameRects.clear();
	}

	/**
	 * Moves the pose so {@code e} (drawn at (0, 0), {@code w} × {@code h} before scaling) lands where the player put it.
	 * False (and nothing pushed) when it is switched off.
	 */
	public static boolean begin(GuiGraphicsExtractor g, HudElement e, int w, int h) {
		return begin(g, e, w, h, 0, 0);
	}

	/** As {@link #begin(GuiGraphicsExtractor, HudElement, int, int)}; {@code homeShiftY} moves it only while at its home. */
	public static boolean begin(GuiGraphicsExtractor g, HudElement e, int w, int h, int homeShiftY) {
		return begin(g, e, w, h, 0, homeShiftY);
	}

	/**
	 * As {@link #begin(GuiGraphicsExtractor, HudElement, int, int)}; the shifts move it only while at its home, and it is
	 * kept on screen after them.
	 */
	public static boolean begin(GuiGraphicsExtractor g, HudElement e, int w, int h, int homeShiftX, int homeShiftY) {
		return begin(g, e, w, h, homeShiftX, homeShiftY, false);
	}

	/**
	 * As {@link #begin(GuiGraphicsExtractor, HudElement, int, int, int, int)}; {@code evenIfOff} places it even while it is
	 * switched off (a piece fading out after the player turned it off). The editor and the player list still apply.
	 */
	public static boolean begin(GuiGraphicsExtractor g, HudElement e, int w, int h, int homeShiftX, int homeShiftY, boolean evenIfOff) {
		HudConfig c = get();
		Pos p = c.pos(e);
		int sw = g.guiWidth(), sh = g.guiHeight();
		float[] r = c.place(e, w, h, sw, sh);
		if (!p.custom) {
			r[0] += homeShiftX;
			// At home at the top center, a piece moves down below any boss bars.
			r[1] += homeShiftY + (e.topCenter() ? bossBarsBottom(sh) : 0);
			// Whole-pixel shifts and screen bounds keep it on the framebuffer grid place() put it on.
			r[0] = r[2] >= sw ? 0 : Mth.clamp(r[0], 0, sw - r[2]);
			r[1] = r[3] >= sh ? 0 : Mth.clamp(r[1], 0, sh - r[3]);
		}
		// The editor needs the spot of a hidden piece too (it draws a placeholder there).
		if (editing) c.drawn.put(e, r);
		if (!p.on && !evenIfOff) return false;
		// ... and steps aside while the player list (Tab) shows there.
		if (!editing && !p.custom && e.topCenter() && playerListShown()) return false;
		// ... and those under the debug text step aside while the full F3 screen shows (it draws after the HUD, on top). A
		// quest pop-up that fires meanwhile runs out unseen (its timer is wall-clock), the same as under the player list.
		if (!editing && !p.custom && e.underDebugText() && debugTextShown()) return false;
		c.drawn.put(e, r);
		frameRects.put(e, r);
		float s = drawScale(p.scale);
		g.pose().pushMatrix();
		g.pose().translate(r[0], r[1]);
		g.pose().scale(s, s);
		return true;
	}

	public static void end(GuiGraphicsExtractor g) {
		g.pose().popMatrix();
	}

	/** Where {@code e} goes on a {@code sw} × {@code sh} screen: x, y, scaled w, scaled h (kept on screen). */
	public float[] place(HudElement e, int w, int h, int sw, int sh) {
		Pos p = pos(e);
		float ax = p.custom ? p.ax : e.ax, ay = p.custom ? p.ay : e.ay;
		int dx = p.custom ? p.dx : e.dx, dy = p.custom ? p.dy : e.dy;
		// At home the quest tracker gives the right edge to a scoreboard sidebar and moves to the left edge, to the top of
		// the band left free there (under the land name, above the chat).
		if (!p.custom && e == HudElement.QUESTS && sidebarShown()) {
			ax = 0f;
			ay = 0f;
			dx = -dx;
			dy = questsBand(sw, sh)[0];
		}
		float s = drawScale(p.scale);
		float W = w * s, H = h * s;
		float x = !p.custom && e.rightEdge() ? ax * sw + dx - W : ax * sw - ax * W + dx, y = ay * sh - ay * H + dy;
		x = W >= sw ? 0 : Mth.clamp(x, 0, sw - W);
		y = H >= sh ? 0 : Mth.clamp(y, 0, sh - H);
		// On whole framebuffer pixels (W and H already are), so the pixel font never lands between them.
		return new float[]{snapPx(x), snapPx(y), W, H};
	}

	// ------------------------------------------------------------------ crisp scales

	static float guiScale() {
		return Math.max(1f, (float) Minecraft.getInstance().getWindow().getGuiScale());
	}

	/** The scale actually drawn: the saved one, snapped so scale × GUI scale is a whole number of framebuffer px per texel. */
	public static float drawScale(float s) {
		float gs = guiScale();
		int lo = Math.max(1, (int) Math.ceil(MIN_SCALE * gs - 1e-4f));
		int hi = Math.max(lo, (int) Math.floor(MAX_SCALE * gs + 1e-4f));
		return Mth.clamp(Math.round(s * gs), lo, hi) / gs;
	}

	/** {@code v} GUI px moved to the nearest framebuffer pixel. */
	static float snapPx(float v) {
		float gs = guiScale();
		return Math.round(v * gs) / gs;
	}

	/** One size step up ({@code dir} 1) or down (-1) from what is drawn now: one framebuffer px per texel. */
	public static float stepScale(float s, int dir) {
		float gs = guiScale();
		return drawScale((Math.round(drawScale(s) * gs) + dir) / gs);
	}

	// ------------------------------------------------------------------ keeping out of each other's way

	/** Whether the quest tracker is at its home but moved to the left edge for a scoreboard sidebar. */
	static boolean questsRelocated() {
		return !get().pos(HudElement.QUESTS).custom && sidebarShown();
	}

	/**
	 * The free band {top, bottom} (GUI px) the quest tracker may use at its home this frame. On the right (its home):
	 * below the status-effect icons and above the coin purse's lines. Moved left for a sidebar: below the land name, the
	 * compass and the boss bars where they reach that far, and above the chat and the chat game card.
	 */
	static int[] questsBand(int sw, int sh) {
		Minecraft mc = Minecraft.getInstance();
		int top, bottom;
		if (!sidebarShown()) {
			float qs = drawScale(get().pos(HudElement.QUESTS).scale), left = sw - 2 - 158 * qs - 4;
			int fx = effectsBottom(mc);
			top = fx > 0 ? fx + 2 : 2;
			// On a narrow screen (GUI 3-4) the compass and the claim tool panel at the top center reach over to the right edge.
			for (HudElement e : new HudElement[]{HudElement.COMPASS, HudElement.CLAIM_TOOL}) {
				float[] r = frameRects.get(e);
				if (r != null && r[0] + r[2] > left && r[1] < sh / 3f) top = Math.max(top, (int) Math.ceil(r[1] + r[3]) + 4);
			}
			int lines = WalletHud.homeLineBandTop(sw, sh, editing);
			bottom = lines >= 0 ? lines - 1 : sh - 2;
			int[] band = {top, aboveRequests(left, sw, top, bottom)};
			// ... and so does the chat game card from the left edge: the tracker keeps to the side of it with more room, when
			// that side holds a quest (the title, one row and "+n more"); else it covers the card's end as it would anyway.
			int[] beside = besideCard(left, sw, band[0], band[1]);
			return beside[1] - beside[0] >= Math.ceil(60 * qs) ? beside : band;
		}
		float right = 2 + 158 * drawScale(get().pos(HudElement.QUESTS).scale) + 4;
		top = 6;
		float[] land = frameRects.get(HudElement.LAND);
		if (land != null && land[0] < right && land[1] < sh / 3f) top = Math.max(top, (int) Math.ceil(land[1] + land[3]) + 4);
		// Only drawn this frame when the player list isn't covering the top center.
		float[] compass = frameRects.get(HudElement.COMPASS);
		if (compass != null && compass[0] < right && compass[1] < sh / 3f) top = Math.max(top, (int) Math.ceil(compass[1] + compass[3]) + 4);
		// Boss bars are 182 wide, centered.
		if (sw / 2 - 91 < right) top = Math.max(top, bossBarsBottom(sh));
		bottom = sh - 4;
		if (mc.options.chatVisibility().get() != net.minecraft.world.entity.player.ChatVisiblity.HIDDEN) bottom = Math.min(bottom, chatTop(mc, sh) - 4);
		int[] band = besideCard(0, right, top, bottom);
		return new int[]{band[0], aboveRequests(0, right, band[0], band[1])};
	}

	/**
	 * The band {@code top}..{@code bottom}, kept to the side of the chat game card (drawn earlier this frame) with more
	 * room where the card reaches across {@code left}..{@code right} within it.
	 */
	private static int[] besideCard(float left, float right, int top, int bottom) {
		float[] card = frameRects.get(HudElement.CHATGAME);
		if (card != null && card[0] < right && card[0] + card[2] > left) {
			int cardTop = (int) Math.floor(card[1]), cardBottom = (int) Math.ceil(card[1] + card[3]);
			if (cardBottom > top && cardTop < bottom) {
				int above = cardTop - 4 - top, below = bottom - (cardBottom + 4);
				if (above >= below) bottom = cardTop - 4;
				else top = cardBottom + 4;
			}
		}
		return new int[]{top, bottom};
	}

	/**
	 * {@code bottom}, raised to end above the requests toast where it showed last frame (it draws after the tracker) and
	 * reaches across {@code left}..{@code right}; on a short screen the wide toast would otherwise cover the tracker's rows.
	 */
	private static int aboveRequests(float left, float right, int top, int bottom) {
		float[] tpa = editing ? null : lastFrameRects.get(HudElement.TPA); // the editor shows every piece at once
		if (tpa == null || tpa[0] >= right || tpa[0] + tpa[2] <= left) return bottom;
		int tpaTop = (int) Math.floor(tpa[1]) - 4;
		return tpaTop > top && tpaTop < bottom ? tpaTop : bottom;
	}

	/** How far down the status-effect icons at the top right reach (0 with none showing); vanilla's own layout. */
	static int effectsBottom(Minecraft mc) {
		net.minecraft.client.player.LocalPlayer p = mc.player;
		net.minecraft.client.gui.screens.Screen s = mc.gui.screen();
		if (p == null || s != null && s.showsActiveEffects()) return 0;
		boolean good = false, bad = false;
		for (net.minecraft.world.effect.MobEffectInstance e : p.getActiveEffects()) {
			if (!e.showIcon()) continue;
			if (e.getEffect().value().isBeneficial()) good = true;
			else bad = true;
		}
		int base = 1 + (mc.isDemo() ? 15 : 0);
		// The harmful row is always at base + 26, whether or not beneficial ones show.
		return bad ? base + 26 + 24 : good ? base + 24 : 0;
	}

	/** How far down the top-right corner is taken now: the status-effect icons and vanilla's toasts. */
	static int topRightBottom(Minecraft mc) {
		int bottom = effectsBottom(mc);
		// The highest occupied toast slot + 1.
		int slots = ((net.axther.aftrhrs.client.mixin.ToastManagerAccessor) mc.gui.toastManager()).aftrhrs$occupiedSlots().length();
		return Math.max(bottom, slots * net.minecraft.client.gui.components.toasts.Toast.SLOT_HEIGHT);
	}

	/** Top of the area vanilla's chat can fill now (focused or not), in GUI px; vanilla's own layout. */
	static int chatTop(Minecraft mc, int sh) {
		double s = mc.options.chatScale().get();
		int lh = (int) (9 * (mc.options.chatLineSpacing().get() + 1.0));
		int bottom = Mth.floor((sh - 40) / s);
		return (int) Math.floor((bottom - mc.gui.hud.getChat().getLinesPerPage() * lh) * s);
	}

	/** Right edge of vanilla's chat background, in GUI px. */
	static int chatRight(Minecraft mc) {
		double s = mc.options.chatScale().get();
		int maxWidth = Mth.ceil(net.minecraft.client.gui.components.ChatComponent.getWidth(mc.options.chatWidth().get()) / s);
		// Its background runs 4 px left of the text to 8 px past it, after a 4 px indent (all scaled).
		return (int) Math.ceil((maxWidth + 12) * s);
	}

	static boolean intersects(float[] a, float x, float y, float w, float h) {
		return a != null && a[0] < x + w && x < a[0] + a[2] && a[1] < y + h && y < a[1] + a[3];
	}

	/**
	 * Whether a rectangle (GUI px) overlaps a piece other than {@code self} drawn earlier this frame (the quest tracker,
	 * chat game card, land name, ...) or vanilla's scoreboard sidebar.
	 */
	public static boolean overlapsOthers(HudElement self, float x, float y, float w, float h) {
		for (Map.Entry<HudElement, float[]> e : frameRects.entrySet()) {
			if (e.getKey() != self && intersects(e.getValue(), x, y, w, h)) return true;
		}
		Minecraft mc = Minecraft.getInstance();
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		// Worked out at most once a frame (it lists the scoreboard).
		if (sidebarFor == null || sidebarW != sw || sidebarH != sh) {
			sidebarFor = Boolean.TRUE;
			sidebarW = sw;
			sidebarH = sh;
			sidebarNow = sidebarRect(mc.font, sw, sh);
		}
		return intersects(sidebarNow, x, y, w, h);
	}

	/** The sidebar's rectangle this frame for {@link #overlapsOthers} ({@code sidebarFor} null: not worked out yet this frame). */
	private static Boolean sidebarFor;
	private static int sidebarW, sidebarH;
	private static float[] sidebarNow;

	/**
	 * The lowest a card in the middle of the screen may reach (GUI px) so it ends above the requests toast at its home, or
	 * {@link Integer#MAX_VALUE} while no toast shows there. The toast draws last, on top.
	 */
	public static int requestsFloor(int sh) {
		return TpaToast.showing() && get().atHome(HudElement.TPA) ? sh + HudElement.TPA.dy - 32 - 4 : Integer.MAX_VALUE;
	}

	/** How far below the top the boss bars reach (0 with none), plus a small gap; vanilla's own layout. */
	private static int bossBarsBottom(int guiHeight) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden()) return 0;
		int n = ((net.axther.aftrhrs.client.mixin.BossHealthOverlayAccessor) mc.gui.hud.getBossOverlay()).aftrhrs$events().size();
		if (n == 0) return 0;
		// The first bar at y 12, then one every 19 until a third of the screen is used; each bar is 5 high.
		int y = 12, last = 12;
		for (int i = 0; i < n; i++) {
			last = y;
			y += 19;
			if (y >= guiHeight / 3) break;
		}
		return last + 5 + 2;
	}

	/** Whether vanilla shows the player list now (same rule as vanilla's HUD). */
	private static boolean playerListShown() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !mc.options.keyPlayerList.isDown()) return false;
		net.minecraft.world.scores.Objective list = mc.level.getScoreboard().getDisplayObjective(net.minecraft.world.scores.DisplaySlot.LIST);
		return !(mc.isLocalServer() && mc.player.connection.getListedOnlinePlayers().size() <= 1 && list == null);
	}

	/** Whether the full F3 debug screen (both text columns) shows now. Only always-on entries such as hitboxes, chunk borders or a pinned FPS line do not count. */
	private static boolean debugTextShown() {
		Minecraft mc = Minecraft.getInstance();
		return mc.debugEntries.isOverlayVisible() && mc.getDebugOverlay().showDebugScreen();
	}

	/** Whether vanilla shows a scoreboard sidebar now (the player's team color slot first, as vanilla does). */
	static boolean sidebarShown() {
		return sidebarObjective() != null;
	}

	/** The objective vanilla's sidebar shows now, or null. */
	private static net.minecraft.world.scores.Objective sidebarObjective() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return null;
		net.minecraft.world.scores.Scoreboard board = mc.level.getScoreboard();
		net.minecraft.world.scores.PlayerTeam team = board.getPlayersTeam(mc.player.getScoreboardName());
		if (team != null) {
			java.util.Optional<net.minecraft.world.scores.TeamColor> color = team.getColor();
			if (color.isPresent()) {
				net.minecraft.world.scores.Objective o = board.getDisplayObjective(color.get().displaySlot());
				if (o != null) return o;
			}
		}
		return board.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
	}

	/** Vanilla's sidebar order: the highest score first, then by name. */
	private static final java.util.Comparator<net.minecraft.world.scores.PlayerScoreEntry> SIDEBAR_ORDER =
		java.util.Comparator.comparingInt(net.minecraft.world.scores.PlayerScoreEntry::value).reversed()
			.thenComparing(net.minecraft.world.scores.PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

	/**
	 * Where vanilla's scoreboard sidebar is now (x, y, w, h in GUI px, with a 2 px margin), or null without one; vanilla's
	 * own layout: up to 15 entries, as wide as the title or the widest "name: score", centered a third below the middle.
	 */
	static float[] sidebarRect(net.minecraft.client.gui.Font font, int sw, int sh) {
		net.minecraft.world.scores.Objective o = sidebarObjective();
		if (o == null) return null;
		net.minecraft.world.scores.Scoreboard board = o.getScoreboard();
		net.minecraft.network.chat.numbers.NumberFormat fmt = o.numberFormatOrDefault(net.minecraft.network.chat.numbers.StyledFormat.SIDEBAR_DEFAULT);
		java.util.List<net.minecraft.world.scores.PlayerScoreEntry> entries = new java.util.ArrayList<>();
		for (net.minecraft.world.scores.PlayerScoreEntry e : board.listPlayerScores(o)) if (!e.isHidden()) entries.add(e);
		entries.sort(SIDEBAR_ORDER);
		int n = Math.min(15, entries.size());
		int width = font.width(o.getDisplayName()), spacer = font.width(": ");
		for (int i = 0; i < n; i++) {
			net.minecraft.world.scores.PlayerScoreEntry e = entries.get(i);
			net.minecraft.network.chat.Component name = net.minecraft.world.scores.PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName());
			int scoreW = font.width(e.formatValue(fmt));
			width = Math.max(width, font.width(name) + (scoreW > 0 ? spacer + scoreW : 0));
		}
		int height = n * 9;
		int bottom = sh / 2 + height / 3;
		// The background runs from 2 left of the text to 1 short of the right edge, the title band 10 above the rows.
		float x = sw - width - 3 - 2, y = bottom - height - 10;
		float right = sw - 3 + 2;
		return new float[]{x - 2, y - 2, right - x + 4, bottom - y + 4};
	}

	/** Puts {@code e}'s top-left corner at (x, y), anchoring it to the nearest third of the screen. */
	public void moveTo(HudElement e, float x, float y, float W, float H, int sw, int sh) {
		Pos p = pos(e);
		float cx = x + W / 2, cy = y + H / 2;
		p.ax = cx < sw / 3f ? 0f : cx > sw * 2 / 3f ? 1f : 0.5f;
		p.ay = cy < sh / 3f ? 0f : cy > sh * 2 / 3f ? 1f : 0.5f;
		p.dx = Math.round(x - (p.ax * sw - p.ax * W));
		p.dy = Math.round(y - (p.ay * sh - p.ay * H));
		p.custom = true;
	}

	public float[] lastDrawn(HudElement e) {
		return drawn.get(e);
	}

	/** The pieces in {@link #drawn}, in the order they drew (the last one is on top). */
	public SequencedSet<HudElement> drawnInOrder() {
		return drawn.sequencedKeySet();
	}

	public void forgetDrawn() {
		drawn.clear();
	}

	// ------------------------------------------------------------------ file

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("aftrhrs").resolve("hud.json");
	}

	public void load() {
		loaded = true;
		Path f = file();
		if (!Files.isRegularFile(f)) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			if (o.has("elements") && o.get("elements").isJsonObject()) {
				for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("elements").entrySet()) {
					HudElement e = HudElement.byId(en.getKey());
					if (e == null || !en.getValue().isJsonObject()) continue;
					Pos p;
					try {
						p = GSON.fromJson(en.getValue(), Pos.class);
					} catch (RuntimeException bad) {
						// One unreadable piece keeps its default; the rest of the file still counts.
						Companion.LOG.warn("Couldn't read the HUD piece '{}' in {}; it stays at its default", en.getKey(), f);
						continue;
					}
					if (p == null) continue;
					p.scale = Float.isFinite(p.scale) ? Mth.clamp(p.scale, MIN_SCALE, MAX_SCALE) : 1f;
					p.ax = Mth.clamp(p.ax, 0f, 1f);
					p.ay = Mth.clamp(p.ay, 0f, 1f);
					pos.put(e, p);
				}
			}
			if (o.has("beams")) {
				try {
					beams = Beams.valueOf(o.get("beams").getAsString());
				} catch (RuntimeException ignored) {
				}
			}
			beamLabels = bool(o, "beamLabels", beamLabels);
			claimWalls = bool(o, "claimWalls", claimWalls);
			claimFx = bool(o, "claimFx", claimFx);
			claimSounds = bool(o, "claimSounds", claimSounds);
			petsVertical = bool(o, "petsVertical", petsVertical);
			chatGameSound = bool(o, "chatGameSound", chatGameSound);
			walletHideAmounts = bool(o, "walletHideAmounts", walletHideAmounts);
			hideFxWithF1 = bool(o, "hideFxWithF1", hideFxWithF1);
			compassWidth = Mth.clamp(num(o, "compassWidth", compassWidth), 140, 360);
			questRows = Mth.clamp(num(o, "questRows", questRows), 1, 10);
			petCount = Mth.clamp(num(o, "petCount", petCount), 1, 6);
		} catch (IOException | RuntimeException ex) {
			Companion.LOG.warn("Couldn't read {}; using the default HUD", f, ex);
			// Keep the player's file: the next save would write over it.
			try {
				Files.copy(f, f.resolveSibling("hud.json.broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
		}
	}

	public void save() {
		JsonObject o = new JsonObject();
		JsonObject els = new JsonObject();
		for (Map.Entry<HudElement, Pos> en : pos.entrySet()) els.add(en.getKey().id, GSON.toJsonTree(en.getValue()));
		o.add("elements", els);
		o.addProperty("beams", beams.name());
		o.addProperty("beamLabels", beamLabels);
		o.addProperty("claimWalls", claimWalls);
		o.addProperty("claimFx", claimFx);
		o.addProperty("claimSounds", claimSounds);
		o.addProperty("compassWidth", compassWidth);
		o.addProperty("questRows", questRows);
		o.addProperty("petCount", petCount);
		o.addProperty("petsVertical", petsVertical);
		o.addProperty("chatGameSound", chatGameSound);
		o.addProperty("walletHideAmounts", walletHideAmounts);
		o.addProperty("hideFxWithF1", hideFxWithF1);
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("hud.json.tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException ex) {
				Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException ex) {
			Companion.LOG.warn("Couldn't save {}", f, ex);
		}
	}

	private static boolean bool(JsonObject o, String k, boolean d) {
		try {
			return o.has(k) ? o.get(k).getAsBoolean() : d;
		} catch (RuntimeException e) {
			return d;
		}
	}

	private static int num(JsonObject o, String k, int d) {
		try {
			return o.has(k) ? o.get(k).getAsInt() : d;
		} catch (RuntimeException e) {
			return d;
		}
	}
}
