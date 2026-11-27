package net.axther.aftrhrs.client.update;

import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.net.VersionCheck;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

/**
 * The client half of {@link VersionCheck}: answers the server's version with ours, or, when they differ, leaves with a
 * message that says what to do. Older than the server: "This server runs Aftr Hrs X — restart to update" plus what the
 * updater has (downloaded and waiting for a restart, downloading now, or where to get it); newer: the server needs
 * updating.
 */
public final class VersionCheckClient {
	private VersionCheckClient() {
	}

	public static void init() {
		ClientConfigurationNetworking.registerGlobalReceiver(VersionCheck.VersionPayload.TYPE, (payload, ctx) -> {
			String server = payload.version(), mine = VersionCheck.version();
			ctx.responseSender().sendPacket(new VersionCheck.VersionPayload(mine));
			if (server.equals(mine)) return;
			Companion.LOG.info("The server runs Aftr Hrs {}, this client {}: not joining", server, mine);
			ctx.responseSender().disconnect(message(server, mine));
		});
		// In a world with an update waiting: remind once.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Updater.remind());
	}

	static Component message(String server, String mine) {
		if (net.axther.aftrhrs.update.Updater.compareVersions(mine, server) >= 0) {
			return Component.translatable("aftrhrs.version.newer", server, mine);
		}
		// "Update & rejoin" on the disconnect screen comes back to this server
		var sd = net.minecraft.client.Minecraft.getInstance().getCurrentServer();
		Restart.kicked(sd != null && !sd.isRealm() ? sd.ip : null, server);
		String staged = Updater.staged();
		Component what;
		if (staged != null && net.axther.aftrhrs.update.Updater.compareVersions(staged, server) >= 0) {
			what = Component.translatable("aftrhrs.version.older.ready", staged);
		} else if (Updater.fetchForServer()) {
			what = Component.translatable("aftrhrs.version.older.downloading");
		} else {
			what = Component.translatable("aftrhrs.version.older.manual", server, net.axther.aftrhrs.update.Updater.RELEASES_PAGE);
		}
		return Component.translatable("aftrhrs.version.older", server, mine).append("\n\n").append(what);
	}
}
