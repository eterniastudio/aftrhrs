package net.axther.aftrhrs.net;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.update.Updater;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.players.NameAndId;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The version handshake: the very first thing in the configuration phase. Client and server run the same jar, and two
 * different versions can't play together (registries, payloads, screens), so the server sends its mod version
 * ({@code aftrhrs:version}) before Fabric API's registry sync and vanilla's registry data. The client answers with its
 * own; on a mismatch it disconnects itself with a message that says what to do ("This server runs Aftr Hrs 1.0.1 —
 * restart to update", with the updater's staged download if there is one; or "the server needs updating"), and the
 * server drops it too with an English version of the same, for a client that didn't. Equal versions go on to the usual
 * configuration.
 *
 * <p>The task is queued from {@code BEFORE_CONFIGURE} in a phase ordered before the default one, where Fabric's registry
 * sync queues its own; tasks run one after another, so a mismatch never reaches the registry sync. Skipped for the
 * singleplayer host, while reconfiguring, for clients without the channel (no Aftr Hrs, or one from before the
 * handshake: the registry sync then decides as before) and with {@code updater.version-check: false}. A client that
 * never answers is let through after 15 seconds.
 */
public final class VersionCheck {
	public record VersionPayload(String version) implements CustomPacketPayload {
		public static final Type<VersionPayload> TYPE = new Type<>(AftrHrs.id("version"));
		public static final StreamCodec<FriendlyByteBuf, VersionPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeUtf(p.version, 64), buf -> new VersionPayload(buf.readUtf(64)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(AftrHrs.ID + ":version");
	private static final Identifier PHASE = AftrHrs.id("version_check");
	private static final Map<ServerConfigurationPacketListenerImpl, Task> OPEN = Collections.synchronizedMap(new WeakHashMap<>());

	private VersionCheck() {
	}

	public static String version() {
		return Updater.current();
	}

	public static void init() {
		PayloadTypeRegistry.clientboundConfiguration().register(VersionPayload.TYPE, VersionPayload.CODEC);
		PayloadTypeRegistry.serverboundConfiguration().register(VersionPayload.TYPE, VersionPayload.CODEC);
		ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE);
		ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(PHASE, (handler, server) -> {
			if (ServerConfigurationNetworking.isReconfiguring(handler)) return;
			if (server.isSingleplayerOwner(new NameAndId(handler.getOwner()))) return;
			Feather f = Feather.get();
			if (f != null && f.getConfig() != null && !f.getConfig().getBoolean("updater.version-check", true)) return;
			if (!ServerConfigurationNetworking.canSend(handler, VersionPayload.TYPE)) return;
			Task task = new Task();
			OPEN.put(handler, task);
			handler.addTask(task);
		});
		// Receivers run on the network thread; configuration tasks start and finish on the server thread.
		ServerConfigurationNetworking.registerGlobalReceiver(VersionPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerConfigurationPacketListenerImpl handler = ctx.packetListener();
			Task task = OPEN.remove(handler);
			if (task == null || !task.open() || !handler.isAcceptingMessages()) return;
			task.close();
			String mine = version(), theirs = payload.version();
			if (!mine.equals(theirs)) {
				AftrHrs.LOG.info("{} runs Aftr Hrs {}, this server {}: refused ({})", handler.getOwner().name(), theirs, mine,
					Updater.compareVersions(theirs, mine) < 0 ? "client needs updating" : "server needs updating");
				handler.disconnect(message(mine, theirs));
				return;
			}
			handler.completeTask(TASK);
		}));
	}

	/** The server's words for a mismatch (the client normally shows its own, translated, first). */
	public static Component message(String server, String client) {
		return Updater.compareVersions(client, server) < 0
			? Component.literal("This server runs Aftr Hrs " + server + " — restart to update.\n\nYou have " + client
				+ ". The mod updates itself; if it doesn't, get " + server + " from " + Updater.RELEASES_PAGE)
			: Component.literal("This server runs Aftr Hrs " + server + ", older than yours (" + client
				+ ").\n\nThe server needs updating: let the staff know.");
	}

	private static final class Task implements ConfigurationTask {
		private boolean done;
		private long started;

		synchronized boolean open() {
			return !done;
		}

		synchronized void close() {
			done = true;
		}

		@Override
		public void start(Consumer<Packet<?>> send) {
			started = System.currentTimeMillis();
			send.accept(ServerConfigurationNetworking.createClientboundPacket(new VersionPayload(version())));
		}

		@Override
		public synchronized boolean tick() {
			if (done || System.currentTimeMillis() - started < 15_000) return false;
			done = true; // no answer: let the usual configuration decide
			return true;
		}

		@Override
		public Type type() {
			return TASK;
		}
	}
}
