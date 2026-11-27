package net.axther.aftrhrs.client.update;

import net.axther.aftrhrs.client.Companion;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-click "update and restart": the game closes, the staged update goes in, and the game opens again by itself, back
 * in the server (or singleplayer world) it was in. The reopened game is the same Java command this one was started with
 * (the JVM's own options, its class path, Fabric's launcher class and the game arguments, with Minecraft's quick play
 * pointing at where the player was), started by the update helper once this game has exited
 * ({@link net.axther.aftrhrs.update.Updater#restart}). If the command can't be put together the game still closes and
 * the update goes in; the player starts it again from their launcher.
 *
 * <p>The button shows on the title screen and the pause menu while an update is downloaded, and in Settings → Updates.
 * Kicked by a server that runs a newer version, the disconnect screen offers "Update & rejoin": it restarts at once when
 * the update is already downloaded, else as soon as it is.
 */
public final class Restart {
	private static final String KNOT = "net.fabricmc.loader.impl.launch.knot.KnotClient";
	/** The server whose newer version kicked us (rejoined after the restart), or null; and its version. */
	private static volatile @Nullable String kickedBy;
	private static volatile String kickedVersion = "";
	/** The player asked to restart once the download finishes. */
	private static volatile boolean whenReady;
	/** The buttons this adds: a screen that only lays its widgets out again on a resize (no re-init) keeps the old one. */
	private static final java.util.Set<Button> ADDED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	private Restart() {
	}

	public static void init() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			// AFTER_INIT runs again on a resize, also for screens (the disconnect screen) that keep their widgets then.
			if (screen instanceof TitleScreen || screen instanceof PauseScreen || screen instanceof DisconnectedScreen) Screens.getWidgets(screen).removeIf(ADDED::contains);
			if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
				if (Updater.staged() == null) return;
				Button b = Button.builder(Component.translatable("aftrhrs.update.restart", Updater.staged()), x -> now(null))
					.bounds(w - 166, 6, 160, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.translatable("aftrhrs.update.restart.tip"))).build();
				ADDED.add(b);
				Screens.getWidgets(screen).add(b);
			} else if (screen instanceof DisconnectedScreen && kickedBy != null) {
				Button b = Button.builder(label(), x -> {
					if (usable()) now(kickedBy);
					else {
						whenReady = true;
						Updater.fetchForServer();
					}
				}).bounds(w / 2 - 100, 8, 200, 20).build();
				ADDED.add(b);
				Screens.getWidgets(screen).add(b);
				ScreenEvents.afterTick(screen).register(s -> {
					b.setMessage(label());
					b.active = !whenReady || usable();
				});
			}
		});
	}

	/** The downloaded update is at least the version of the server that kicked us. */
	private static boolean usable() {
		String v = Updater.staged();
		return v != null && net.axther.aftrhrs.update.Updater.compareVersions(v, kickedVersion) >= 0;
	}

	private static Component label() {
		if (usable()) return Component.translatable("aftrhrs.update.rejoin", Updater.staged());
		if (whenReady) return Component.translatable("aftrhrs.update.rejoin.waiting");
		return Component.translatable("aftrhrs.update.rejoin.download");
	}

	/** A server runs a newer version ({@code version}) than ours: remember it for "Update & rejoin". */
	public static void kicked(@Nullable String address, String version) {
		kickedBy = address;
		kickedVersion = version;
		whenReady = false;
	}

	/** The update finished downloading (updater thread). */
	static void ready() {
		if (!whenReady || !usable()) return;
		whenReady = false;
		Minecraft mc = Minecraft.getInstance();
		String to = kickedBy;
		mc.execute(() -> now(to));
	}

	/**
	 * Closes the game to install the update and reopen it. {@code server}: rejoin that address; null: wherever the
	 * player is now (a server, a singleplayer world, or the title screen).
	 */
	public static void now(@Nullable String server) {
		Minecraft mc = Minecraft.getInstance();
		List<String> quick = server != null ? List.of("--quickPlayMultiplayer", server) : here(mc);
		boolean arranged = false;
		try {
			List<String> cmd = command(quick);
			arranged = cmd != null && net.axther.aftrhrs.update.Updater.restart(cmd, Path.of(System.getProperty("user.dir")));
		} catch (Throwable t) { // java.management missing in a trimmed runtime, odd launcher arguments...
			Companion.LOG.warn("Can't reopen the game by itself; it closes and installs the update", t);
		}
		Companion.LOG.info("Closing to install the update{}", arranged ? " and reopen" : "");
		mc.stop();
	}

	/** Quick play arguments that put the player back where they are. */
	private static List<String> here(Minecraft mc) {
		ServerData sd = mc.getCurrentServer();
		if (mc.getSingleplayerServer() != null) {
			Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			if (root.getFileName() != null) return List.of("--quickPlaySingleplayer", root.getFileName().toString());
		} else if (sd != null && !sd.isRealm()) {
			return List.of("--quickPlayMultiplayer", sd.ip);
		}
		return List.of();
	}

	/** This game's own command, with {@code quick} instead of any quick play it was started with; null when unknown. */
	static @Nullable List<String> command(List<String> quick) throws ClassNotFoundException {
		String exe = ProcessHandle.current().info().command().orElse(null);
		if (exe == null) return null;
		Class.forName(KNOT, false, ClassLoader.getSystemClassLoader());
		List<String> cmd = new ArrayList<>();
		cmd.add(exe);
		for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			// the agent of a debugger or profiler that's attached now would try the same port again
			if (a.startsWith("-agentlib:jdwp") || a.startsWith("-Xrunjdwp")) continue;
			cmd.add(a);
		}
		// Fabric's own settings that a launcher wrapper (or the dev launch injector) set at run time, not on the command line
		java.util.Set<String> given = new java.util.HashSet<>();
		for (String c : cmd) if (c.startsWith("-D")) given.add(c.substring(2).split("=", 2)[0]);
		for (String key : System.getProperties().stringPropertyNames()) {
			if ((key.startsWith("fabric.") || key.startsWith("log4j")) && !key.startsWith("fabric.dli.") && !given.contains(key)) {
				cmd.add("-D" + key + "=" + System.getProperty(key));
			}
		}
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));
		cmd.add(KNOT);
		String[] args = FabricLoader.getInstance().getLaunchArguments(false);
		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("--quickPlay")) {
				if (i + 1 < args.length && !args[i + 1].startsWith("--")) i++;
				continue;
			}
			cmd.add(args[i]);
		}
		cmd.addAll(quick);
		return cmd;
	}
}
