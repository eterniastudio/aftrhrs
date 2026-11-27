package net.axther.aftrhrs.gametest.soak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.axther.aftrhrs.net.ApiPayload;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * A headless "client" for the soak run: a real {@link Connection} on a netty {@link EmbeddedChannel}, logged in through
 * the same {@link PrepareSpawnTask} path a real join takes (player data load, vehicle, placeNewPlayer, Fabric JOIN),
 * moved by real movement packets, disconnected by closing the channel. Outbound packets are swallowed (keep-alives and
 * teleports are answered, Companion messages decoded), so nothing piles up in memory.
 */
final class SoakBot {
    final String name;
    final UUID id;
    private Connection connection;
    private EmbeddedChannel channel;
    private PrepareSpawnTask prepare;
    private final Queue<Supplier<Packet<?>>> replies = new ConcurrentLinkedQueue<>();
    /** Last Companion message of each type ("menu", "pets", ...). */
    final Map<String, JsonObject> lastMessage = new ConcurrentHashMap<>();
    private final Map<Integer, byte[][]> parts = new ConcurrentHashMap<>();
    volatile long packetsOut;
    volatile String lastDisconnect;
    volatile boolean respawned;
    /** The last command this bot typed (for problem reports). */
    volatile String lastCommand = "";
    boolean linked;

    SoakBot(String name) {
        this.name = name;
        this.id = UUIDUtil.createOfflinePlayerUUID(name);
    }

    boolean connected() {
        return connection != null && connection.isConnected() && prepare == null;
    }

    boolean connecting() {
        return prepare != null;
    }

    ServerPlayer player(MinecraftServer server) {
        return connected() ? server.getPlayerList().getPlayer(id) : null;
    }

    /** Starts a login (finishes over the next ticks in {@link #tick}). */
    void login(MinecraftServer server) {
        if (connection != null) return;
        connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(new Sink(), connection);
        linked = false;
        lastMessage.clear();
        prepare = new PrepareSpawnTask(server, new NameAndId(id, name));
        prepare.start(p -> {
        });
    }

    /** Client-side disconnect: the channel closes, the server notices on the next connection tick. */
    void quit() {
        if (channel != null) channel.close();
        if (prepare != null) {
            prepare.close();
            prepare = null;
        }
    }

    /** One client tick: finish a pending login, answer the server, run the connection's tick. */
    void tick(MinecraftServer server) {
        if (connection == null) return;
        if (prepare != null) {
            if (!channel.isOpen()) {
                prepare.close();
                prepare = null;
                connection = null;
                return;
            }
            if (!prepare.tick()) return;
            try {
                if (server.getPlayerList().getPlayer(id) != null) {
                    SoakDriver.problem("bot " + name + ": duplicate login refused");
                    quit();
                    connection = null;
                    return;
                }
                Component err = server.getPlayerList().canPlayerLogin(connection.getRemoteAddress(), new NameAndId(id, name));
                if (err != null) {
                    SoakDriver.log("bot " + name + " refused: " + err.getString());
                    quit();
                    connection = null;
                    return;
                }
                prepare.spawnPlayer(connection, new CommonListenerCookie(new GameProfile(id, name), 0, ClientInformation.createDefault(), false));
            } catch (Exception e) {
                // what the vanilla configuration listener turns into "Invalid player data"
                SoakDriver.problem("bot " + name + " couldn't be placed in the world (Invalid player data)", e);
                prepare = null;
                quit();
                connection = null;
                return;
            }
            prepare = null;
            send(new ServerboundPlayerLoadedPacket());
            hello();
        }
        // the listener's tick first (resetPosition, player doTick, keep-alive), then answer what the server sent so far;
        // answers to answers wait for the next tick, like a real client one round trip later
        connection.tick();
        if (!connection.isConnected()) {
            // tick() ran handleDisconnection: the player is gone
            connection = null;
            channel = null;
            replies.clear();
            return;
        }
        for (int n = replies.size(); n > 0; n--) {
            Supplier<Packet<?>> r = replies.poll();
            if (r == null) break;
            Packet<?> p = r.get();
            if (p != null) send(p);
        }
        if (respawned) {
            respawned = false;
            send(new ServerboundPlayerLoadedPacket());
        }
    }

    /** The client's end-of-tick packet (lets the server accept the next tick's movement). */
    void endTick() {
        if (connected()) send(net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.INSTANCE);
    }

    /** A packet from "the client". */
    void send(Packet<?> packet) {
        if (channel == null || !channel.isOpen()) return;
        try {
            channel.writeInbound(packet);
        } catch (Exception e) {
            SoakDriver.problem("bot " + name + ": " + packet.getClass().getSimpleName() + " threw", e);
        }
    }

    /** A Companion message ({@code aftrhrs:api}) from the bot's "mod". */
    void api(JsonObject msg) {
        byte[] json = msg.toString().getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[json.length + 1];
        System.arraycopy(json, 0, b, 1, json.length);
        send(new ServerboundCustomPayloadPacket(new ApiPayload(b)));
    }

    static JsonObject message(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    private void hello() {
        JsonObject o = message("hello");
        o.addProperty("proto", 1);
        o.addProperty("mod", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("aftrhrs").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        o.addProperty("mc", "26.3");
        JsonArray f = new JsonArray();
        for (String s : new String[]{"menus", "hub", "recipes", "storage", "craftfill", "claims", "pets", "places", "quests", "tpa", "hud",
                "trade", "chatgames", "wallet", "fishing", "inspect"}) f.add(s);
        o.add("features", f);
        o.addProperty("recipes", "");
        api(o);
    }

    private void inspect(Object msg) {
        if (msg instanceof ClientboundBundlePacket b) {
            for (Packet<?> p : b.subPackets()) inspect(p);
            return;
        }
        packetsOut++;
        switch (msg) {
            case ClientboundKeepAlivePacket k -> replies.add(() -> new ServerboundKeepAlivePacket(k.getId()));
            case ClientboundPlayerPositionPacket pp -> replies.add(() -> {
                ServerPlayer p = SoakDriver.server() == null ? null : SoakDriver.server().getPlayerList().getPlayer(id);
                if (p == null) return null;
                return new ServerboundAcceptTeleportationPacket(pp.id(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
            });
            case ClientboundRespawnPacket ignored -> respawned = true;
            case ClientboundDisconnectPacket d -> {
                lastDisconnect = d.reason().getString();
                SoakDriver.log("bot " + name + " disconnected by server: " + lastDisconnect);
            }
            case ClientboundSystemChatPacket c -> {
                String s = c.content().getString();
                if (s.contains("unexpected error") || s.contains("An error occurred") || s.contains("Unknown or incomplete command")) {
                    SoakDriver.problem("bot " + name + " got: " + s + " (after /" + lastCommand + ")");
                }
            }
            case ClientboundCustomPayloadPacket cp when cp.payload() instanceof ApiPayload api -> receive(api.bytes());
            default -> {
            }
        }
    }

    private void receive(byte[] payload) {
        JsonObject o = decode(payload);
        if (o == null) return;
        String t = o.has("t") ? o.get("t").getAsString() : "";
        if (t.equals("part")) {
            int pid = o.get("id").getAsInt(), i = o.get("i").getAsInt(), n = o.get("n").getAsInt();
            byte[][] pieces = parts.computeIfAbsent(pid, k -> new byte[n][]);
            pieces[i] = java.util.Base64.getDecoder().decode(o.get("b").getAsString());
            for (byte[] piece : pieces) if (piece == null) return;
            parts.remove(pid);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (byte[] piece : pieces) out.writeBytes(piece);
            o = decode(out.toByteArray());
            if (o == null) return;
            t = o.has("t") ? o.get("t").getAsString() : "";
        }
        if (t.equals("welcome")) linked = true;
        lastMessage.put(t, o);
    }

    private JsonObject decode(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        try {
            byte[] body;
            if (payload[0] == 0) body = java.util.Arrays.copyOfRange(payload, 1, payload.length);
            else if (payload[0] == 1) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1))) {
                    body = in.readAllBytes();
                }
            } else {
                SoakDriver.problem("bot " + name + ": Companion payload with flag " + payload[0]);
                return null;
            }
            JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            SoakDriver.problem("bot " + name + ": unreadable Companion message", e);
            return null;
        }
    }

    /** Swallows every outbound message (completing its promise, so send listeners run) after a look at it. */
    private final class Sink extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            try {
                inspect(msg);
            } catch (Exception e) {
                SoakDriver.problem("bot " + name + ": inspecting " + msg.getClass().getSimpleName(), e);
            } finally {
                ReferenceCountUtil.release(msg);
                promise.trySuccess();
            }
        }
    }
}
