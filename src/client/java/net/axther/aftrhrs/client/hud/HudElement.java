package net.axther.aftrhrs.client.hud;

import net.minecraft.network.chat.Component;

/**
 * Every piece of the Companion's HUD that can be switched off, moved and resized ({@link HudConfig}). Each has a home:
 * an anchor on the screen ({@code ax}, {@code ay}: 0 = left/top, 0.5 = middle, 1 = right/bottom; the element's own
 * matching point sits there, so a right-anchored element grows to the left) and an offset in GUI pixels. The pet
 * portraits are the exception: at home their right edge is what is anchored (see {@link #rightEdge}).
 *
 * <p>At home, top-centre pieces step aside for the player list (Tab), and pieces under the debug text columns step aside
 * while the full F3 screen is open. A custom-placed piece always keeps its spot.
 */
public enum HudElement {
	COMPASS("compass", 0.5f, 0f, 0, 2),
	/** Right edge, between the status-effect icons and the coin purse; left edge, under the land name, with a sidebar. */
	QUESTS("quests", 1f, 0.5f, -2, 0),
	/** Below the status-effect icons and vanilla's toasts while those show. */
	QUEST_TOAST("quest_toast", 1f, 0f, -6, 40),
	/** At home its right edge sits {@code dx} from the screen's center (left of the hotbar and the offhand slot), however many pets it shows. */
	PETS("pets", 0.5f, 1f, -124, -12),
	LAND("land", 0f, 0f, 6, 6),
	CLAIM_TOOL("claim_tool", 0.5f, 0f, 0, 22),
	BANNER("banner", 0.5f, 0.25f, 0, 40),
	CLAIM_TOAST("claim_toast", 0.5f, 0f, 0, 32),
	/** Above the action-bar line (and the held item's name). */
	TPA("tpa", 0.5f, 1f, 0, -80),
	/**
	 * Left edge, at home just above whatever vanilla's chat can fill (moving up while chat is open, or beside it when
	 * there is no room above; {@link ChatGameHud}). This anchor is only the editor's reference for a moved card.
	 */
	CHATGAME("chatgame", 0f, 0.5f, 6, -30),
	/** Bottom right, away from the status-effect icons at the top right. */
	WALLET("wallet", 1f, 1f, -6, -6),
	/**
	 * The look-at card; at home its top sits just under the crosshair whatever its height, or beside the crosshair (right,
	 * else left, whichever misses the other pieces) when it would reach the action bar ({@link InspectHud}).
	 */
	INSPECT("inspect", 0.5f, 0.5f, 0, 36),
	/**
	 * The smithing recipe note: at home beside the crosshair (right, else left when that would cover another piece), above
	 * the requests toast ({@link net.axther.aftrhrs.client.smithing.SmithingHud}).
	 */
	NOTE("note", 0.5f, 0.5f, 0, 0);

	public final String id;
	public final float ax, ay;
	public final int dx, dy;

	HudElement(String id, float ax, float ay, int dx, int dy) {
		this.id = id;
		this.ax = ax;
		this.ay = ay;
		this.dx = dx;
		this.dy = dy;
	}

	/** At home, the element's right edge (not its own anchor point) sits at the anchor plus {@code dx}. */
	public boolean rightEdge() {
		return this == PETS;
	}

	/** The pieces whose home is the top center, where boss bars and the player list are. */
	public boolean topCenter() {
		return this == COMPASS || this == CLAIM_TOOL || this == CLAIM_TOAST;
	}

	/** At home under one of the F3 debug text columns (top-left / right edge); hidden there while the full debug screen shows. */
	public boolean underDebugText() {
		return this == LAND || this == QUESTS || this == QUEST_TOAST;
	}

	public Component label() {
		return Component.translatable("aftrhrs.hud.el." + id);
	}

	public Component tip() {
		return Component.translatable("aftrhrs.hud.el." + id + ".tip");
	}

	public static HudElement byId(String id) {
		for (HudElement e : values()) if (e.id.equals(id)) return e;
		return null;
	}
}
