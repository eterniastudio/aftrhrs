package net.axther.aftrhrs.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.axther.aftrhrs.client.menu.MenuScreen;
import net.axther.aftrhrs.net.ApiPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.axther.aftrhrs.client.net.Link;
import net.axther.aftrhrs.client.recipes.ServerRecipes;
import net.axther.aftrhrs.client.storage.StorageSession;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client half of the Aftr Hrs mod (once the standalone "Aftr Hrs Companion"; the name lives on in the protocol). The
 * screens switch on once the server's endpoint ({@code net.axther.aftrhrs.net.Companion}) answers the hello with the
 * features it allows ({@code companion.features.*}): Feather menus, the hub, recipes, storage terminals, pets, the HUD
 * pieces and world effects. A feature the server turns off falls back to its vanilla screen.
 *
 * <p>{@link #init()} is called once from {@link AftrHrsClient} and wires everything into Fabric: the
 * {@code aftrhrs:api} receiver (the payload type itself is registered on both sides by the common initializer),
 * join/leave, the client tick, key mappings and the pet model renderer. Items and their creative tabs are the
 * mod's own registered content ({@code ArcanaContent}); recipes for JEI still come from the server ({@code recipes}).
 */
public final class Companion {
	public static final String ID = "aftrhrs";
	public static final Logger LOG = LoggerFactory.getLogger("Aftr Hrs");

	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
	public static final KeyMapping HUB_KEY = new KeyMapping("key.aftrhrs.hub", InputConstants.KEY_H, CATEGORY);
	/** Claim borders always on / back to "only with the shovel or stick". */
	public static final KeyMapping CLAIMS_KEY = new KeyMapping("key.aftrhrs.claims", InputConstants.KEY_B, CATEGORY);
	/** The claim map. */
	public static final KeyMapping MAP_KEY = new KeyMapping("key.aftrhrs.claim_map", InputConstants.KEY_M, CATEGORY);
	/** The pet menu. */
	public static final KeyMapping PETS_KEY = new KeyMapping("key.aftrhrs.pets", InputConstants.KEY_J, CATEGORY);
	/** Places (homes, warps, death point) and the compass. */
	public static final KeyMapping PLACES_KEY = new KeyMapping("key.aftrhrs.places", InputConstants.KEY_K, CATEGORY);
	/** Quest tracker on/off. */
	public static final KeyMapping QUESTS_KEY = new KeyMapping("key.aftrhrs.quests", InputConstants.KEY_U, CATEGORY);
	/** Accept / deny the teleport request on screen. */
	public static final KeyMapping TPA_ACCEPT_KEY = new KeyMapping("key.aftrhrs.tpa_accept", InputConstants.KEY_Y, CATEGORY);
	public static final KeyMapping TPA_DENY_KEY = new KeyMapping("key.aftrhrs.tpa_deny", InputConstants.KEY_N, CATEGORY);
	/**
	 * HUD &amp; display settings (what shows, where, how big). Not O: since 26.3 that is vanilla's Friends key, which
	 * vanilla handles before any key mapping sees it.
	 */
	public static final KeyMapping HUD_KEY = new KeyMapping("key.aftrhrs.hud", InputConstants.KEY_I, CATEGORY);
	/** Quiet mode: every Companion world effect and HUD piece off (teleport requests stay) and back on. Unbound. */
	public static final KeyMapping QUIET_KEY = new KeyMapping("key.aftrhrs.quiet", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** The Angler's Journal (Fish Journal and records). Unbound by default. */
	public static final KeyMapping FISH_KEY = new KeyMapping("key.aftrhrs.fish_journal", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** The creature codex (every creature, how to tame it; the old taming guide). Unbound by default, like the Fish Journal. */
	public static final KeyMapping CODEX_KEY = new KeyMapping("key.aftrhrs.codex", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** Every key {@link #init} registers. */
	public static final java.util.List<KeyMapping> KEYS = java.util.List.of(HUB_KEY, CLAIMS_KEY, MAP_KEY, PETS_KEY, PLACES_KEY, QUESTS_KEY, TPA_ACCEPT_KEY, TPA_DENY_KEY, HUD_KEY,
		QUIET_KEY,
		FISH_KEY,
		CODEX_KEY);

	private static final ServerRecipes RECIPES = new ServerRecipes();
	/** JEI's "no server recipes" chat line, held until we know whether Feather sends them. */
	private static Component heldWarning;
	private static long heldAt;
	private static boolean releasing;
	private static boolean ready;
	/** The world last seen, to notice a dimension change. */
	private static Object lastLevel;

	private Companion() {
	}

	public static void init() {
		if (ready) return;
		ready = true;
		ClientPlayNetworking.registerGlobalReceiver(ApiPayload.TYPE, (payload, ctx) -> Link.receive(payload.bytes()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Link.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Link.onLeave());
		// The server's channel list arrives a moment after the join: say hello as soon as it lists aftrhrs:api.
		ServerboundPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
			if (channels.contains(ApiPayload.TYPE.id())) client.execute(Link::channelOpened);
		});
		for (KeyMapping key : KEYS) KeyMappingHelper.registerKeyMapping(key);
		ClientTickEvents.END_CLIENT_TICK.register(Companion::tick);
		PictureInPictureRendererRegistry.register(context -> net.axther.aftrhrs.client.entity.CreatureView.newRenderer());
		try {
			net.axther.aftrhrs.client.update.Updater.init();
			net.axther.aftrhrs.client.update.Restart.init(); // "update & restart" buttons
		} catch (RuntimeException | LinkageError e) {
			LOG.warn("Auto-update is unavailable", e);
		}
		net.axther.aftrhrs.client.update.VersionCheckClient.init();
		Link.init();
		MenuScreen.register();
		StorageSession.register();
		net.axther.aftrhrs.client.chess.ChessClient.register();
		RECIPES.register();
		net.axther.aftrhrs.client.claims.ClaimState.register();
		DevScript.SCREENS.put("claimmap", net.axther.aftrhrs.client.claims.ClaimMapScreen::new);
		net.axther.aftrhrs.client.pets.PetData.register();
		net.axther.aftrhrs.client.codex.CodexData.register();
		net.axther.aftrhrs.client.places.PlaceState.register();
		net.axther.aftrhrs.client.hud.QuestHud.register();
		net.axther.aftrhrs.client.hud.TpaToast.register();
		DevScript.SCREENS.put("places", net.axther.aftrhrs.client.places.PlacesScreen::new);
		// Feather's Settings page has a "HUD & display" button for mod players; it lands here.
		// Closing it goes back where it came from: Feather's page (a fresh one; the clicked one is spent) or any other screen.
		Link.on("hud_settings", m -> {
			var from = Minecraft.getInstance().gui.screen();
			// "back" (newer Feather): the message that reopens the page it came from; older ones get the hub.
			String back = m.has("back") && m.get("back").isJsonPrimitive() ? m.get("back").getAsString() : "open_hub";
			Minecraft.getInstance().gui.setScreen(from instanceof MenuScreen
				? net.axther.aftrhrs.client.hud.HudSettingsScreen.fromFeather(back)
				: new net.axther.aftrhrs.client.hud.HudSettingsScreen(from));
		});
		DevScript.SCREENS.put("hud", () -> new net.axther.aftrhrs.client.hud.HudSettingsScreen(null));
		DevScript.SCREENS.put("hudedit", () -> new net.axther.aftrhrs.client.hud.HudEditorScreen(null));
		for (var t : net.axther.aftrhrs.client.pets.PetScreen.Tab.values()) {
			DevScript.SCREENS.put(t.name().toLowerCase(java.util.Locale.ROOT), () -> {
				net.axther.aftrhrs.client.pets.PetScreen.openTab(t);
				return new net.axther.aftrhrs.client.pets.PetScreen(t);
			});
		}
		DevScript.SCREENS.put("codex", () -> new net.axther.aftrhrs.client.codex.CodexScreen(null, null));
		net.axther.aftrhrs.client.hud.ChatGameHud.register();
		net.axther.aftrhrs.client.hud.WalletHud.register();
		net.axther.aftrhrs.client.fish.FishJournal.register();
		DevScript.SCREENS.put("fishjournal", () -> {
			net.axther.aftrhrs.client.fish.FishJournal.mock();
			return new net.axther.aftrhrs.client.fish.AnglerScreen(net.axther.aftrhrs.client.fish.AnglerScreen.Tab.JOURNAL);
		});
		DevScript.SCREENS.put("fishrecords", () -> {
			net.axther.aftrhrs.client.fish.FishJournal.mock();
			return new net.axther.aftrhrs.client.fish.AnglerScreen(net.axther.aftrhrs.client.fish.AnglerScreen.Tab.RECORDS);
		});
		net.axther.aftrhrs.client.hud.InspectHud.register();
		LOG.info("Aftr Hrs client {} ready", version());
	}

	/** End of every client tick. */
	public static void tick(Minecraft mc) {
		Link.tick(mc);
		if (heldWarning != null) {
			if (RECIPES.available()) {
				heldWarning = null; // Feather sent the server's recipes: the warning is wrong
			} else if (System.currentTimeMillis() - heldAt > 8000) {
				Component w = heldWarning;
				heldWarning = null;
				releasing = true;
				try {
					mc.gui.chatListener().handleSystemMessage(w, false);
				} finally {
					releasing = false;
				}
			}
		}
		if (mc.level != lastLevel) {
			// Another dimension: what we knew about claims was about the old one (Feather sends the new world's soon).
			if (lastLevel != null && mc.level != null) net.axther.aftrhrs.client.claims.ClaimState.INSTANCE.worldChanged();
			// menu creatures were made in the old level: let it go
			net.axther.aftrhrs.client.entity.CreatureView.worldChanged();
			lastLevel = mc.level;
		}
		net.axther.aftrhrs.client.claims.ClaimHud.tick(mc);
		DevScript.tick(mc);
		while (CLAIMS_KEY.consumeClick()) {
			var claims = net.axther.aftrhrs.client.claims.ClaimState.INSTANCE;
			if (mc.player != null && claims.active()) {
				claims.pinned = !claims.pinned;
				mc.player.sendOverlayMessage(Component.translatable(claims.pinned ? "aftrhrs.claims.pinned-on" : "aftrhrs.claims.pinned-off"));
			}
		}
		while (MAP_KEY.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null && net.axther.aftrhrs.client.claims.ClaimState.INSTANCE.active()) mc.gui.setScreen(new net.axther.aftrhrs.client.claims.ClaimMapScreen());
		}
		net.axther.aftrhrs.client.hud.TpaToast.tick(mc);
		net.axther.aftrhrs.client.pets.PetData.tick();
		while (PLACES_KEY.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null && net.axther.aftrhrs.client.places.PlaceState.INSTANCE.active()) mc.gui.setScreen(new net.axther.aftrhrs.client.places.PlacesScreen());
		}
		while (QUESTS_KEY.consumeClick()) {
			if (mc.player != null && net.axther.aftrhrs.client.hud.QuestHud.active()) {
				net.axther.aftrhrs.client.hud.HudConfig.toggle(net.axther.aftrhrs.client.hud.HudElement.QUESTS);
				boolean on = net.axther.aftrhrs.client.hud.HudConfig.shown(net.axther.aftrhrs.client.hud.HudElement.QUESTS);
				mc.player.sendOverlayMessage(Component.translatable(on ? "aftrhrs.quests.tracker-on" : "aftrhrs.quests.tracker-off"));
			}
		}
		while (PETS_KEY.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null && net.axther.aftrhrs.client.pets.PetData.INSTANCE.active()) net.axther.aftrhrs.client.pets.PetData.request(null);
		}
		while (HUD_KEY.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new net.axther.aftrhrs.client.hud.HudSettingsScreen(null));
		}
		while (HUB_KEY.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null && Link.has("menus")) Link.send(Link.message("open_hub"));
		}
		while (QUIET_KEY.consumeClick()) {
			// Kept for the whole session (also across servers); a restart turns it off.
			if (mc.player != null) {
				net.axther.aftrhrs.client.hud.HudConfig.quiet = !net.axther.aftrhrs.client.hud.HudConfig.quiet;
				mc.player.sendOverlayMessage(Component.translatable(net.axther.aftrhrs.client.hud.HudConfig.quiet ? "aftrhrs.quiet.on" : "aftrhrs.quiet.off"));
			}
		}
		while (CODEX_KEY.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null && net.axther.aftrhrs.client.codex.CodexData.active()) net.axther.aftrhrs.client.codex.CodexData.request(null, null);
		}
		while (FISH_KEY.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null && Link.has("fishing")) net.axther.aftrhrs.client.fish.FishJournal.request("journal");
		}
		net.axther.aftrhrs.client.hud.InspectHud.tick(mc);
	}

	/** Called by the chat mixin; true when the message is held (it shows later if no recipes come). */
	public static boolean holdWarning(Component message) {
		if (releasing || RECIPES.available()) return !releasing;
		heldWarning = message;
		heldAt = System.currentTimeMillis();
		return true;
	}

	public static ServerRecipes recipes() {
		return RECIPES;
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	/** Leaving the server: forget everything it told us. */
	public static void onDisconnect() {
		heldWarning = null;
		StorageSession.clear();
		RECIPES.clear();
		net.axther.aftrhrs.client.claims.ClaimState.INSTANCE.clear();
		net.axther.aftrhrs.client.claims.ClaimFx.clear();
		net.axther.aftrhrs.client.claims.ClaimHud.clear();
		net.axther.aftrhrs.client.pets.PetData.INSTANCE.clear();
		net.axther.aftrhrs.client.codex.CodexData.clear();
		net.axther.aftrhrs.client.entity.CreatureView.clear();
		net.axther.aftrhrs.client.places.PlaceState.INSTANCE.clear();
		net.axther.aftrhrs.client.hud.QuestHud.clear();
		net.axther.aftrhrs.client.hud.TpaToast.clear();
		net.axther.aftrhrs.client.net.Decode.clearCache();
		net.axther.aftrhrs.client.hud.ChatGameHud.clear();
		net.axther.aftrhrs.client.hud.WalletHud.clear();
		net.axther.aftrhrs.client.fish.FishJournal.clear();
		net.axther.aftrhrs.client.hud.InspectHud.clear();
	}
}
