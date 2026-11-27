package net.axther.aftrhrs.client.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.client.Companion;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The client's side of the auto-updater ({@link net.axther.aftrhrs.update.Updater} does the work, shared with the
 * dedicated server): on by default, the player's choice of Automatic / Notify only / Off (HUD &amp; Display → Updates,
 * saved in {@code config/aftrhrs/updates.json}), and the toasts ("Update 1.0.1 downloaded — restart to apply"). A
 * development run (the mod isn't a jar) never updates.
 */
public final class Updater {
	public enum Mode {AUTO, NOTIFY, OFF}

	public enum Status {DEV, IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED}

	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);
	private static volatile Mode mode = Mode.AUTO;
	private static volatile String toastedFor = "";

	private Updater() {
	}

	public static void init() {
		load();
		net.axther.aftrhrs.update.Updater.init(net.axther.aftrhrs.update.Updater.Side.CLIENT,
			() -> net.axther.aftrhrs.update.Updater.Mode.valueOf(mode.name()),
			(status, version, detail) -> {
				if (status == net.axther.aftrhrs.update.Updater.Status.READY) {
					toast("ready", version);
					Restart.ready();
				}
				else if (status == net.axther.aftrhrs.update.Updater.Status.AVAILABLE) toast("available", version);
			});
	}

	public static Mode mode() {
		return mode;
	}

	public static void mode(Mode m) {
		mode = m;
		save();
		net.axther.aftrhrs.update.Updater.modeChanged();
	}

	public static Status status() {
		return Status.valueOf(net.axther.aftrhrs.update.Updater.status().name());
	}

	public static String version() {
		return net.axther.aftrhrs.update.Updater.version();
	}

	public static String detail() {
		return net.axther.aftrhrs.update.Updater.detail();
	}

	/** The downloaded version waiting for a restart, or null. */
	public static String staged() {
		return net.axther.aftrhrs.update.Updater.staged();
	}

	public static void checkNow() {
		if (mode != Mode.OFF) net.axther.aftrhrs.update.Updater.check(mode == Mode.AUTO);
	}

	/** Notify mode's "Install": download what was found now. */
	public static void installNow() {
		net.axther.aftrhrs.update.Updater.install();
	}

	/**
	 * A server runs a newer version: fetch it now unless updates are off (Notify only included: the player can't play
	 * there without it). True when a download is (or will be) on its way.
	 */
	public static boolean fetchForServer() {
		var st = net.axther.aftrhrs.update.Updater.status();
		if (mode == Mode.OFF || st == net.axther.aftrhrs.update.Updater.Status.DEV) return false;
		if (st == net.axther.aftrhrs.update.Updater.Status.AVAILABLE) net.axther.aftrhrs.update.Updater.install();
		else net.axther.aftrhrs.update.Updater.check(true);
		return true;
	}

	/** Shows the "downloaded, restart" toast again (on joining a world), once per version and kind. */
	public static void remind() {
		String v = staged();
		if (v != null) toast("ready", v);
	}

	private static void toast(String kind, String version) {
		if ((kind + version).equals(toastedFor)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return;
		mc.execute(() -> {
			if (mc.gui == null) return;
			toastedFor = kind + version;
			SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST,
				Component.translatable("aftrhrs.update.toast." + kind, version), Component.translatable("aftrhrs.update.toast." + kind + ".body"));
		});
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("aftrhrs/updates.json");
	}

	private static void load() {
		try {
			Path f = file();
			if (!Files.isRegularFile(f)) return;
			JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			if (o.has("mode") && o.get("mode").isJsonPrimitive()) mode = Mode.valueOf(o.get("mode").getAsString().toUpperCase(Locale.ROOT));
		} catch (IOException | RuntimeException ignored) {
			// Missing or unreadable: automatic.
		}
	}

	private static void save() {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("mode", mode.name().toLowerCase(Locale.ROOT));
			Files.writeString(f, o.toString());
		} catch (IOException e) {
			Companion.LOG.warn("Couldn't save update settings", e);
		}
	}

	/** For the settings screen: the jar this game runs, or empty in development. */
	public static Optional<Path> jar() {
		return net.axther.aftrhrs.update.Updater.jar();
	}
}
