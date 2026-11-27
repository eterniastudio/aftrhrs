package net.axther.aftrhrs.gametest.core;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real players for game tests: a {@link ServerPlayer} placed in the player list through {@code placeNewPlayer} with
 * an in-memory connection (Netty {@link EmbeddedChannel}), so every join/quit listener, the player list, lookups by
 * name and per-player packets work like for a real client. What the server sends them is captured: chat lines and
 * dialogs. Dialog and chat packets are also encoded with the real codecs, so a packet a real client couldn't decode
 * fails the test.
 */
public final class Bots {
    private Bots() {
    }

    private static net.minecraft.network.ProtocolInfo<net.minecraft.network.protocol.game.ClientGamePacketListener> play;

    /** A connected test player. */
    public static final class Bot {
        /** The player (a new object after a respawn: follows the connection). */
        public ServerPlayer player;
        final net.minecraft.server.network.ServerGamePacketListenerImpl listener;
        final EmbeddedChannel channel;
        final Connection connection;
        private final List<String> chat = new ArrayList<>();
        private final List<Dialog> dialogs = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
        /** Custom payloads (mod packets) the server sent, in order; tests read and clear it. */
        public final List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> payloads = new ArrayList<>();

        Bot(ServerPlayer player, EmbeddedChannel channel, Connection connection) {
            this.player = player;
            this.channel = channel;
            this.connection = connection;
            this.listener = player.connection;
        }

        public String name() {
            return player.getGameProfile().name();
        }

        public UUID id() {
            return player.getUUID();
        }

        /** Reads everything the server sent since the last call. */
        public void pump() {
            if (listener.player != player) player = listener.player;
            if (channel.isOpen()) {
                channel.runPendingTasks();
                channel.flushOutbound();
            }
            Object o;
            while ((o = channel.readOutbound()) != null) {
                if (!(o instanceof Packet<?> p)) continue;
                try {
                    encode(p);
                } catch (RuntimeException e) {
                    problems.add(p.type() + " can't be encoded: " + e);
                }
                try {
                    switch (p) {
                        case ClientboundSystemChatPacket s -> {
                            chat.add((s.overlay() ? "[bar] " : "") + s.content().getString());
                        }
                        case ClientboundPlayerChatPacket c -> chat.add(c.unsignedContent().map(Component::getString).orElse(c.body().content()));
                        case ClientboundDisguisedChatPacket d -> chat.add(d.message().getString());
                        case ClientboundShowDialogPacket d -> {
                            dialogs.add(d.dialog().value());
                        }
                        case net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket c -> payloads.add(c.payload());
                        default -> {
                        }
                    }
                } catch (RuntimeException e) {
                    problems.add(p.type() + " can't be encoded: " + e);
                }
            }
        }

        /** Encodes like the real pipeline (bundles are split there): throws when a client couldn't be sent it. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void encode(Packet<?> p) {
            if (p instanceof net.minecraft.network.protocol.BundlePacket<?> b) {
                for (Packet<?> sub : b.subPackets()) encode(sub);
                return;
            }
            if (play == null) play = net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE
                    .bind(RegistryFriendlyByteBuf.decorator(player.level().getServer().registryAccess()));
            io.netty.buffer.ByteBuf buf = Unpooled.buffer();
            try {
                ((net.minecraft.network.codec.StreamCodec) play.codec()).encode(buf, p);
            } finally {
                buf.release();
            }
        }

        /** Chat lines received since the last {@link #clear} (plain text). */
        public List<String> chat() {
            pump();
            return chat;
        }

        public boolean heard(String fragment) {
            for (String s : chat()) if (s.contains(fragment)) return true;
            return false;
        }

        /** The last dialog the server opened for this player, or null. */
        public Dialog lastDialog() {
            pump();
            return dialogs.isEmpty() ? null : dialogs.get(dialogs.size() - 1);
        }

        public int dialogCount() {
            pump();
            return dialogs.size();
        }

        /** Packets that could not be encoded (a real client would have been kicked). */
        public List<String> problems() {
            pump();
            return problems;
        }

        /** Dies (generic damage) and presses Respawn. */
        public void dieAndRespawn() {
            player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
            listener.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                    net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            pump();
            listener.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }

        /**
         * Acts as if this client listed {@code channels} (Fabric's channel registration), so the server sends it those
         * mod payloads ({@code ServerPlayNetworking.canSend} becomes true) and fires the register event.
         */
        public void openChannels(net.minecraft.resources.Identifier... channels) {
            try {
                Object addon = net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl.getAddon(listener);
                java.lang.reflect.Method register = null;
                for (Class<?> k = addon.getClass(); k != null && register == null; k = k.getSuperclass()) {
                    for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                        if (m.getName().equals("register") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == List.class) register = m;
                    }
                }
                if (register == null) throw new IllegalStateException("no register(List) on " + addon.getClass());
                register.setAccessible(true);
                register.invoke(addon, List.of(channels));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        public void clear() {
            pump();
            chat.clear();
            dialogs.clear();
        }

        /** Runs a command as this player, as if typed: through the command packet, so Feather's command hooks run. */
        public void cmd(String line) {
            clear();
            if (player.hasDisconnected()) return;
            player.connection.handleChatCommand(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(line.startsWith("/") ? line.substring(1) : line));
            pump();
        }

        /** Runs a command as this player straight through the dispatcher (no typed-command hooks). */
        public void exec(String line) {
            clear();
            MinecraftServer server = player.level().getServer();
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), line.startsWith("/") ? line.substring(1) : line);
            pump();
        }

        public void op(boolean op) {
            var list = player.level().getServer().getPlayerList();
            if (op) list.op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            else list.deop(player.nameAndId());
        }

        /** Quits like a client closing the game: the channel closes, then the server handles the disconnection. */
        /** Abandons every claim this player made (menus and commands can claim land other tests stand on). */
        public void abandonClaims() {
            try {
                Class<?> mc = Class.forName("net.axther.aftrhrs.feature.claims.ClaimsModule");
                Object m = net.axther.aftrhrs.core.Feather.get().module((Class) mc);
                if (m == null) return;
                var rf = mc.getDeclaredField("registry");
                rf.setAccessible(true);
                Object reg = rf.get(m);
                var owned = reg.getClass().getDeclaredMethod("ownedBy", UUID.class);
                owned.setAccessible(true);
                var abandon = mc.getDeclaredMethod("abandon", ServerPlayer.class, Class.forName("net.axther.aftrhrs.feature.claims.Claim"));
                abandon.setAccessible(true);
                for (Object c : new ArrayList<>((java.util.Collection<?>) owned.invoke(reg, id()))) abandon.invoke(m, player, c);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        public void leave() {
            if (player.hasDisconnected()) return;
            pump();
            connection.disconnect(new DisconnectionDetails(Component.literal("test over")));
            connection.handleDisconnection();
            // Anything sent to this player that a real client couldn't decode would have kicked them.
            if (!problems.isEmpty()) throw new IllegalStateException(name() + " was sent packets a client can't decode: " + problems);
        }
    }

    /** A unique player name for this run (names are kept by the server across test runs). */
    public static String name(String base) {
        String s = base + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36), 36);
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    public static Bot join(GameTestHelper h, String name) {
        return join(h.getLevel(), name, h.absoluteVec(new Vec3(1.5, 2, 1.5)));
    }

    public static Bot join(ServerLevel level, String name, Vec3 at) {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        player.snapTo(at.x, at.y, at.z, 0, 0);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        // Join handlers may send a new player to spawn; tests want them where they asked.
        player.connection.teleport(at.x, at.y, at.z, 0, 0);
        Bot bot = new Bot(player, channel, connection);
        bot.clear();
        return bot;
    }

    /** A console command source that keeps what it was told. */
    public static final class Console implements CommandSource {
        public final List<String> out = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            out.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        public CommandSourceStack stack(ServerLevel level) {
            return new CommandSourceStack(this, Vec3.atCenterOf(level.getRespawnData().pos()), net.minecraft.world.phys.Vec2.ZERO, level,
                    LevelBasedPermissionSet.OWNER, Component.literal("Console"), level.getServer());
        }

        public void cmd(ServerLevel level, String line) {
            level.getServer().getCommands().performPrefixedCommand(stack(level), line);
        }
    }
}
