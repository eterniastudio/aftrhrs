package net.axther.aftrhrs.client.claims;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The claim map: a top-down map of the chunks around you (from the chunks your client has loaded), with every claim
 * Feather told us about painted in its color. Hover a chunk to see whose it is; click wilderness to claim it, and
 * shift + right-click one of your chunks to unclaim it. Feather checks every request like the claim shovel would.
 */
public final class ClaimMapScreen extends Screen {
	private static final int RADIUS = 12;
	private static final int SIZE = (RADIUS * 2 + 1) * 16;
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Companion.ID, "claim_map");

	private DynamicTexture texture;
	private int originX, originZ;
	/** Terrain colors, sampled once for this origin (claim changes only repaint the claims over them). */
	private int[] terrain;
	private int terrainX, terrainZ;
	private long paintedVersion = -1;
	private int mapX, mapY, mapSize;
	/** The text column beside the map (room for it) instead of under it, and its left edge. */
	private static final int SIDE_TEXT = 130, SIDE_GAP = 12;
	private boolean side;
	private int textX;
	/** The budget and how-to lines, wrapped once per claims update and wrap width. */
	private List<net.minecraft.util.FormattedCharSequence> budgetLines = List.of(), helpLines = List.of();
	private long linesVersion = -1;
	private int linesWidth = -1;
	private int hoverCx = Integer.MIN_VALUE, hoverCz;
	/** The claim outlines last built for the map, and what they were built for. */
	private List<List<ClaimShapes.Edge>> outlineCache = List.of();
	private List<ClaimState.Claim> outlineClaims;
	private long outlineVersion = -1;
	private int outlineX, outlineZ, outlineZoom;
	/** The keyboard cursor's chunk (arrow keys), or MIN_VALUE before the first arrow press. */
	private int cursorCx = Integer.MIN_VALUE, cursorCz;
	/** The keyboard was used last (an arrow or Enter, until the mouse moves or clicks): the cursor, not the mouse, owns the tooltip and narration. */
	private boolean keyboard;
	/** Chunks shown each side of the player (mouse wheel: 4, 8 or 12). */
	private int zoom = 8;

	public ClaimMapScreen() {
		super(Component.translatable("aftrhrs.claims.map.title"));
	}

	@Override
	protected void init() {
		mapY = 34;
		side = false;
		// The how-to wraps to the map's width, and a narrower map can need another line: settle the size in a few steps.
		int under = Math.max(100, Math.min(width - 40, height - 70));
		for (int i = 0; i < 4; i++) {
			int fit = Math.max(100, Math.min(width - 40, height - mapY - footerHeight(under) - 4));
			if (fit == under) break;
			under = fit;
		}
		// Wide windows (GUI 3-4 on a 16:9 screen): the budget and how-to in a column beside the map, which can then be bigger.
		int sideMap = Math.min(width - 40 - SIDE_GAP - SIDE_TEXT, height - mapY - 7 - 4);
		side = sideMap > under && sideMap >= 100 && sideTextHeight() <= sideMap;
		mapSize = side ? sideMap : under;
		int groupW = side ? mapSize + SIDE_GAP + SIDE_TEXT : mapSize;
		// The map and the column are centred as one group.
		mapX = (width - groupW) / 2;
		textX = mapX + mapSize + SIDE_GAP;
		Player p = minecraft.player;
		if (p == null) return;
		originX = ((p.getBlockX() >> 4) - RADIUS) << 4;
		originZ = ((p.getBlockZ() >> 4) - RADIUS) << 4;
		if (texture == null) {
			texture = new DynamicTexture(() -> "Aftr Hrs claim map", new NativeImage(SIZE, SIZE, true));
			minecraft.getTextureManager().register(TEXTURE, texture);
		}
		paint();
	}

	/** Terrain from the loaded chunks, then the claims over it. */
	private void paint() {
		Minecraft mc = minecraft;
		if (mc.level == null || texture == null) return;
		NativeImage img = texture.getPixels();
		if (img == null) return;
		if (terrain == null || terrainX != originX || terrainZ != originZ) sampleTerrain(mc);
		for (int lz = 0; lz < SIZE; lz++) {
			for (int lx = 0; lx < SIZE; lx++) img.setPixel(lx, lz, terrain[lz * SIZE + lx]);
		}
		ClaimState s = ClaimState.INSTANCE;
		for (ClaimState.Claim c : s.claims) {
			if (c.pieces().isEmpty()) continue;
			int rgb = s.colorOf(c.rel());
			// Other players' claims are hatched (keyed to world x/z, so the stripes stay put when the map moves): not
			// told apart by color alone. Own, trusted and admin claims get a plain fill.
			boolean hatch = !c.own() && !c.rel().equals("trusted") && !c.admin();
			// Only the claim's own box (within the map) needs looking at.
			int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (ClaimState.Piece piece : c.pieces()) {
				minX = Math.min(minX, piece.minX());
				minZ = Math.min(minZ, piece.minZ());
				maxX = Math.max(maxX, piece.maxX());
				maxZ = Math.max(maxZ, piece.maxZ());
			}
			int lx0 = Math.max(0, minX - originX), lx1 = Math.min(SIZE - 1, maxX - originX);
			int lz0 = Math.max(0, minZ - originZ), lz1 = Math.min(SIZE - 1, maxZ - originZ);
			for (int lz = lz0; lz <= lz1; lz++) {
				for (int lx = lx0; lx <= lx1; lx++) {
					int x = originX + lx, z = originZ + lz;
					if (!c.contains(x, z)) continue;
					boolean edge = !c.contains(x - 1, z) || !c.contains(x + 1, z) || !c.contains(x, z - 1) || !c.contains(x, z + 1);
					int base = img.getPixel(lx, lz);
					int fill = edge ? 0xFF000000 | rgb
						: hatch ? mix(base, rgb, ((x + z) & 3) == 0 ? 0.85f : 0.4f)
						: mix(base, rgb, 0.5f);
					img.setPixel(lx, lz, fill);
				}
			}
		}
		texture.upload();
		paintedVersion = s.version;
	}

	private void sampleTerrain(Minecraft mc) {
		terrain = new int[SIZE * SIZE];
		terrainX = originX;
		terrainZ = originZ;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int[] heights = new int[SIZE];
		for (int lz = 0; lz < SIZE; lz++) {
			for (int lx = 0; lx < SIZE; lx++) {
				int x = originX + lx, z = originZ + lz;
				LevelChunk chunk = mc.level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
				int col = 0xFF14111F;
				int h = Integer.MIN_VALUE;
				if (chunk != null) {
					h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
					pos.set(x, h, z);
					BlockState state = chunk.getBlockState(pos);
					MapColor mapColor = state.getMapColor(mc.level, pos);
					int north = lz > 0 ? heights[lx] : h;
					MapColor.Brightness b = h > north ? MapColor.Brightness.HIGH : h < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
					col = mapColor == MapColor.NONE ? 0xFF14111F : mapColor.calculateARGBColor(b) | 0xFF000000;
				}
				heights[lx] = h;
				terrain[lz * SIZE + lx] = col;
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
		super.extractRenderState(g, mx, my, partialTick);
		ClaimState s = ClaimState.INSTANCE;
		if (paintedVersion != s.version) paint();
		Theme.blit(g, Theme.MENU_PANEL, mapX - 8, mapY - 26, (side ? mapSize + SIDE_GAP + SIDE_TEXT : mapSize) + 16, mapSize + 26 + footerHeight(mapSize));
		g.centeredText(font, title, width / 2, mapY - 18, Theme.MOON);
		int span = zoom * 2 + 1;
		float t0 = (RADIUS - zoom) / (float) (RADIUS * 2 + 1), t1 = (RADIUS + zoom + 1) / (float) (RADIUS * 2 + 1);
		g.blit(TEXTURE, mapX, mapY, mapX + mapSize, mapY + mapSize, t0, t1, t0, t1);
		int viewX = originX + (RADIUS - zoom) * 16, viewZ = originZ + (RADIUS - zoom) * 16;
		float cell = mapSize / (float) span;
		// Chunk grid.
		for (int i = 1; i < span; i++) {
			int o = Math.round(i * cell);
			g.fill(mapX + o, mapY, mapX + o + 1, mapY + mapSize, 0x22FFFFFF);
			g.fill(mapX, mapY + o, mapX + mapSize, mapY + o + 1, 0x22FFFFFF);
		}
		// Claim outlines, crisp at any scale.
		float px1 = mapSize / (float) (span * 16);
		List<List<ClaimShapes.Edge>> outlines = outlines(s, viewX, viewZ, span);
		for (int i = 0; i < outlines.size(); i++) {
			ClaimState.Claim c = s.claims.get(i);
			int col = 0xFF000000 | s.colorOf(c.rel());
			for (ClaimShapes.Edge e : outlines.get(i)) {
				float ex0 = mapX + (Math.min(e.x0(), e.x1()) - viewX) * px1, ez0 = mapY + (Math.min(e.z0(), e.z1()) - viewZ) * px1;
				float ex1 = mapX + (Math.max(e.x0(), e.x1()) - viewX) * px1, ez1 = mapY + (Math.max(e.z0(), e.z1()) - viewZ) * px1;
				int ax0 = Math.max(mapX, Math.round(ex0) - 1), az0 = Math.max(mapY, Math.round(ez0) - 1);
				int ax1 = Math.min(mapX + mapSize, Math.round(ex1) + 1), az1 = Math.min(mapY + mapSize, Math.round(ez1) + 1);
				if (ax1 > ax0 && az1 > az0) g.fill(ax0, az0, ax1, az1, col);
			}
		}
		// Hovered chunk.
		hoverCx = Integer.MIN_VALUE;
		if (mx >= mapX && my >= mapY && mx < mapX + mapSize && my < mapY + mapSize) {
			int gx = (int) ((mx - mapX) / cell), gz = (int) ((my - mapY) / cell);
			hoverCx = (viewX >> 4) + gx;
			hoverCz = (viewZ >> 4) + gz;
			if (!keyboardCursor()) highlight(g, cell, gx, gz);
		}
		// The keyboard cursor, with the same highlight (kept inside the view when zooming in).
		int cursorX = -1, cursorY = -1;
		if (cursorCx != Integer.MIN_VALUE) {
			cursorCx = Math.max(viewX >> 4, Math.min((viewX >> 4) + span - 1, cursorCx));
			cursorCz = Math.max(viewZ >> 4, Math.min((viewZ >> 4) + span - 1, cursorCz));
			int gx = cursorCx - (viewX >> 4), gz = cursorCz - (viewZ >> 4);
			if (keyboard || hoverCx != cursorCx || hoverCz != cursorCz) highlight(g, cell, gx, gz);
			cursorX = mapX + Math.round((gx + 1) * cell);
			cursorY = mapY + Math.round(gz * cell);
		}
		// You.
		Player p = minecraft.player;
		if (p != null) {
			float px = mapX + (float) ((p.getX() - viewX) / (span * 16) * mapSize), pz = mapY + (float) ((p.getZ() - viewZ) / (span * 16) * mapSize);
			g.pose().pushMatrix();
			g.pose().translate(px, pz);
			g.pose().rotate((float) Math.toRadians(p.getYRot() + 180));
			g.fill(-1, -4, 2, 3, 0xFFFFFFFF);
			g.fill(-3, -1, 4, 1, 0xFFFFFFFF);
			g.fill(0, -5, 1, -3, 0xFFFFE7A8);
			g.pose().popMatrix();
		}
		// Budget and how-to: beside the map (left-aligned) or under it (centred).
		wrapLines(side ? SIDE_TEXT : mapSize);
		if (side) {
			int hy = mapY + 2;
			for (var line : budgetLines) {
				g.text(font, line, textX, hy, Theme.TEXT);
				hy += 10;
			}
			hy++;
			for (var line : helpLines) {
				g.text(font, line, textX, hy, Theme.MUTED);
				hy += 10;
			}
		} else {
			int hy = mapY + mapSize + 6;
			for (var line : budgetLines) {
				g.centeredText(font, line, width / 2, hy, Theme.TEXT);
				hy += 10;
			}
			hy++;
			for (var line : helpLines) {
				g.centeredText(font, line, width / 2, hy, Theme.MUTED);
				hy += 10;
			}
		}
		if (hoverCx != Integer.MIN_VALUE && !keyboardCursor()) g.setComponentTooltipForNextFrame(font, tooltip(s, hoverCx, hoverCz), mx, my);
		else if (cursorCx != Integer.MIN_VALUE) g.setComponentTooltipForNextFrame(font, tooltip(s, cursorCx, cursorCz), cursorX, cursorY);
	}

	/**
	 * Each claim's outline within the view (world coordinates, one list per claim in {@code s.claims} order), rebuilt
	 * only when the claims change, the view pans or the zoom changes. Claims whose box misses the view get none.
	 */
	private List<List<ClaimShapes.Edge>> outlines(ClaimState s, int viewX, int viewZ, int span) {
		if (outlineVersion == s.version && outlineX == viewX && outlineZ == viewZ && outlineZoom == zoom && outlineClaims == s.claims) return outlineCache;
		outlineVersion = s.version;
		outlineX = viewX;
		outlineZ = viewZ;
		outlineZoom = zoom;
		outlineClaims = s.claims;
		int cx = viewX + span * 8, cz = viewZ + span * 8, reach = span * 8 + 1;
		List<List<ClaimShapes.Edge>> out = new ArrayList<>(s.claims.size());
		for (ClaimState.Claim c : s.claims) {
			out.add(ClaimWalls.near(c.pieces(), cx, cz, reach) ? ClaimShapes.outline(c.pieces(), cx, cz, reach) : List.of());
		}
		outlineCache = out;
		return out;
	}

	/** A chunk cell picked by the mouse or the keyboard cursor. */
	private void highlight(GuiGraphicsExtractor g, float cell, int gx, int gz) {
		int x0 = mapX + Math.round(gx * cell), y0 = mapY + Math.round(gz * cell), x1 = mapX + Math.round((gx + 1) * cell), y1 = mapY + Math.round((gz + 1) * cell);
		g.fill(x0, y0, x1, y1, 0x33FFFFFF);
		g.outline(x0, y0, x1 - x0, y1 - y0, 0xCCFFFFFF);
	}

	private static final Component HELP = Component.translatable("aftrhrs.claims.map.help");
	private static final Component KEYS = Component.translatable("aftrhrs.claims.map.keys");

	/** Below the map: the budget and the how-to (both wrapped to the map's width) and the panel's frame; only the frame in side mode. */
	private int footerHeight(int mapW) {
		if (side) return 7;
		wrapLines(mapW);
		return 7 + budgetLines.size() * 10 + helpLines.size() * 10 + 7;
	}

	/** How tall the side column's text is (budget, a 1 px gap, the how-to). */
	private int sideTextHeight() {
		wrapLines(SIDE_TEXT);
		return budgetLines.size() * 10 + 1 + helpLines.size() * 10;
	}

	/** (Re)wraps the budget and how-to lines when the claims changed or the wrap width did. */
	private void wrapLines(int wrap) {
		ClaimState s = ClaimState.INSTANCE;
		if (linesVersion == s.version && linesWidth == wrap) return;
		linesVersion = s.version;
		linesWidth = wrap;
		Component budget = Component.translatable("aftrhrs.claims.map.budget", s.left, s.total, s.count, s.max);
		budgetLines = font.split(budget, wrap);
		List<net.minecraft.util.FormattedCharSequence> help = new ArrayList<>(font.split(HELP, wrap));
		help.addAll(font.split(KEYS, wrap));
		helpLines = help;
	}

	/** The claim in a chunk (its middle first, then a few points across it), or null for wilderness. */
	private static ClaimState.Claim claimIn(ClaimState s, int cx, int cz) {
		int x0 = cx << 4, z0 = cz << 4;
		ClaimState.Claim c = s.at(x0 + 8, z0 + 8);
		for (int dz = 0; dz < 16 && c == null; dz += 5) for (int dx = 0; dx < 16 && c == null; dx += 5) c = s.at(x0 + dx, z0 + dz);
		return c;
	}

	/** Whose chunk (cx, cz) is and what a click does there: the tooltip, and what the narrator reads. */
	private static List<Component> tooltip(ClaimState s, int cx, int cz) {
		List<Component> lines = new ArrayList<>();
		ClaimState.Claim c = claimIn(s, cx, cz);
		lines.add(Component.translatable("aftrhrs.claims.map.chunk", cx, cz).withStyle(net.minecraft.ChatFormatting.GRAY));
		if (c == null) {
			lines.add(Component.translatable("aftrhrs.claims.map.wild"));
			if (s.allowed) lines.add(Component.translatable("aftrhrs.claims.map.click-claim").withStyle(net.minecraft.ChatFormatting.GREEN));
		} else {
			lines.add(Component.literal(ClaimState.glyphOf(c.rel()) + " " + c.name()).withColor(s.colorOf(c.rel())));
			lines.add(Component.translatable(c.admin() ? "aftrhrs.claims.banner.admin" : "aftrhrs.claims.banner.owned", c.owner()).withStyle(net.minecraft.ChatFormatting.GRAY));
			if (c.own()) lines.add(Component.translatable("aftrhrs.claims.map.click-unclaim").withStyle(net.minecraft.ChatFormatting.RED));
			else if (c.level() != null && !c.level().isEmpty()) lines.add(Component.translatable("aftrhrs.claims.level." + c.level()).withStyle(net.minecraft.ChatFormatting.AQUA));
		}
		return lines;
	}

	/** True when the keyboard cursor, not the mouse, is the chunk being pointed at. */
	private boolean keyboardCursor() {
		return keyboard && cursorCx != Integer.MIN_VALUE;
	}

	@Override
	public void mouseMoved(double mx, double my) {
		keyboard = false;
		super.mouseMoved(mx, my);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		keyboard = false;
		if (hoverCx != Integer.MIN_VALUE) {
			ClaimState s = ClaimState.INSTANCE;
			// The same rule as the tooltip, so a click does what the tooltip says.
			ClaimState.Claim c = claimIn(s, hoverCx, hoverCz);
			if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && c == null && s.allowed) {
				request("claim_chunk", hoverCx, hoverCz);
				return true;
			}
			if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT && c != null && c.own() && event.hasShiftDown()) {
				request("unclaim_chunk", hoverCx, hoverCz);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void request(String type, int cx, int cz) {
		var m = Link.message(type);
		m.addProperty("cx", cx);
		m.addProperty("cz", cz);
		Link.send(m);
		minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// The map key closes the map it opened.
		if (Companion.MAP_KEY.matches(event)) {
			onClose();
			return true;
		}
		// The keyboard cursor: arrows move it (it starts on your chunk), Enter claims wilderness like a click,
		// Shift + Enter unclaims one of your chunks like a shift + right-click.
		int dx = 0, dz = 0;
		switch (event.key()) {
			case InputConstants.KEY_LEFT -> dx = -1;
			case InputConstants.KEY_RIGHT -> dx = 1;
			case InputConstants.KEY_UP -> dz = -1;
			case InputConstants.KEY_DOWN -> dz = 1;
			default -> {
			}
		}
		Player p = minecraft.player;
		if ((dx != 0 || dz != 0) && p != null) {
			if (cursorCx == Integer.MIN_VALUE) {
				cursorCx = p.getBlockX() >> 4;
				cursorCz = p.getBlockZ() >> 4;
			} else {
				int loX = (originX >> 4) + RADIUS - zoom, loZ = (originZ >> 4) + RADIUS - zoom;
				cursorCx = Math.max(loX, Math.min(loX + zoom * 2, cursorCx + dx));
				cursorCz = Math.max(loZ, Math.min(loZ + zoom * 2, cursorCz + dz));
			}
			keyboard = true;
			triggerImmediateNarration(true);
			return true;
		}
		if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && cursorCx != Integer.MIN_VALUE) {
			keyboard = true;
			ClaimState s = ClaimState.INSTANCE;
			ClaimState.Claim c = claimIn(s, cursorCx, cursorCz);
			if (event.hasShiftDown()) {
				if (c != null && c.own()) request("unclaim_chunk", cursorCx, cursorCz);
			} else if (c == null && s.allowed) {
				request("claim_chunk", cursorCx, cursorCz);
			}
			return true;
		}
		return super.keyPressed(event);
	}

	/** The narrator reads the hovered (or keyboard cursor's) chunk: the same lines as its tooltip. */
	@Override
	protected void updateNarrationState(NarrationElementOutput out) {
		super.updateNarrationState(out);
		ClaimState s = ClaimState.INSTANCE;
		boolean mouse = hoverCx != Integer.MIN_VALUE && !keyboardCursor();
		int cx = mouse ? hoverCx : cursorCx, cz = mouse ? hoverCz : cursorCz;
		if (cx == Integer.MIN_VALUE) {
			Player p = minecraft.player;
			if (p != null) out.add(NarratedElementType.POSITION, Component.translatable("aftrhrs.claims.map.narrate.player", p.getBlockX() >> 4, p.getBlockZ() >> 4));
		} else {
			out.add(NarratedElementType.POSITION, tooltip(s, cx, cz).toArray(Component[]::new));
		}
		out.add(NarratedElementType.USAGE, Component.translatable("aftrhrs.claims.map.narrate.usage"));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		int before = zoom;
		zoom = Math.max(4, Math.min(RADIUS, zoom + (dy < 0 ? 4 : -4)));
		return before != zoom || super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public void removed() {
		if (texture != null) {
			minecraft.getTextureManager().release(TEXTURE);
			texture = null;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static int mix(int argb, int rgb, float t) {
		int r = Math.round(((argb >> 16) & 255) * (1 - t) + ((rgb >> 16) & 255) * t);
		int g = Math.round(((argb >> 8) & 255) * (1 - t) + ((rgb >> 8) & 255) * t);
		int b = Math.round((argb & 255) * (1 - t) + (rgb & 255) * t);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	static String fmt(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}
}
