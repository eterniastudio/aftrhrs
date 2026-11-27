package net.axther.aftrhrs.update;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.cmd.Sender;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * The updater on a dedicated server: opt-in with {@code config.yml updater.server: stage} (default {@code off}), and
 * {@code /feather update [status|check|stage|cancel]} from the console (or {@code feather.admin}). A staged update is
 * installed when the server stops, so the next start runs it; staff online are told when one is ready. Clients update
 * themselves; an integrated (singleplayer) server never runs this.
 */
public final class ServerUpdates {
	private static volatile Sender waiting;

	private ServerUpdates() {
	}

	public static void init() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
		ServerLifecycleEvents.SERVER_STARTED.register(server -> Updater.init(Updater.Side.SERVER, ServerUpdates::mode,
			(status, version, detail) -> server.execute(() -> changed(server, status, version, detail))));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			String v = Updater.staged();
			if (v != null && Perms.has(handler.player, "feather.admin")) handler.player.sendSystemMessage(readyLine(v));
		});
	}

	/** {@code updater.server}: {@code stage} checks and downloads by itself; {@code off} (default) only on command. */
	private static Updater.Mode mode() {
		Feather f = Feather.get();
		String v = f == null || f.getConfig() == null ? "off" : f.getConfig().getString("updater.server", "off");
		return switch (v.trim().toLowerCase(Locale.ROOT)) {
			case "stage", "auto", "on", "true" -> Updater.Mode.AUTO;
			case "notify", "check" -> Updater.Mode.NOTIFY;
			default -> Updater.Mode.OFF;
		};
	}

	private static void changed(MinecraftServer server, Updater.Status status, String version, String detail) {
		Sender s = waiting;
		if (status == Updater.Status.READY) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) if (Perms.has(p, "feather.admin")) p.sendSystemMessage(readyLine(version));
		}
		if (s != null && status != Updater.Status.CHECKING && status != Updater.Status.DOWNLOADING) {
			waiting = null;
			s.sendMessage(line(status, version, detail));
		}
	}

	private static Component readyLine(String version) {
		return Component.literal("Aftr Hrs " + version + " is downloaded: restart the server to apply it.").withStyle(ChatFormatting.GOLD);
	}

	private static String line(Updater.Status st, String version, String detail) {
		String why = detail.isEmpty() ? "" : " (" + detail + ")";
		return switch (st) {
			case DEV -> "Updates are off in a development run (the mod isn't a jar).";
			case IDLE -> "Not checked yet.";
			case CHECKING -> "Checking " + Updater.RELEASES_PAGE + " ...";
			case UP_TO_DATE -> "Up to date (" + Updater.current() + ")" + why + ".";
			case AVAILABLE -> "Aftr Hrs " + version + " is out (running " + Updater.current() + "). /feather update stage downloads it.";
			case DOWNLOADING -> "Downloading Aftr Hrs " + version + " ...";
			case READY -> "Aftr Hrs " + version + " is downloaded and checked: it is installed when the server stops.";
			case FAILED -> "The last check failed" + why + ".";
		};
	}

	/** {@code /feather update ...}; true when handled. */
	public static boolean command(Sender sender, String[] args) {
		if (args.length == 0 || !args[0].equalsIgnoreCase("update")) return false;
		if (!sender.hasPermission("feather.admin")) {
			sender.sendMessage("You can't do that.");
			return true;
		}
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			sender.sendMessage("The game client updates itself (HUD & Display > Updates); this command is for dedicated servers.");
			return true;
		}
		Updater.Status st = Updater.status();
		switch (sub) {
			case "check", "stage" -> {
				if (st == Updater.Status.DEV || st == Updater.Status.READY) {
					sender.sendMessage(line(st, Updater.version(), Updater.detail()));
					return true;
				}
				waiting = sender;
				sender.sendMessage(sub.equals("stage") ? "Checking for an update to download..." : "Checking for an update...");
				if (sub.equals("stage") && st == Updater.Status.AVAILABLE) Updater.install();
				else Updater.check(sub.equals("stage"));
			}
			case "cancel" -> {
				if (st != Updater.Status.READY) {
					sender.sendMessage("Nothing is staged.");
				} else {
					Updater.cancel();
					sender.sendMessage("Dropped the staged update; nothing changes at the next restart.");
				}
			}
			default -> {
				sender.sendMessage("Aftr Hrs " + Updater.current() + Updater.jar().map(p -> " (" + p + ")").orElse("")
					+ ", updater.server: " + mode().name().toLowerCase(Locale.ROOT).replace("auto", "stage"));
				sender.sendMessage(line(st, Updater.version(), Updater.detail()));
				sender.sendMessage("/feather update check | stage | cancel");
			}
		}
		return true;
	}

	public static List<String> complete(String[] args) {
		if (args.length == 1) return "update".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("update") : List.of();
		if (args.length == 2 && args[0].equalsIgnoreCase("update")) {
			return List.of("status", "check", "stage", "cancel").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
		}
		return List.of();
	}
}
