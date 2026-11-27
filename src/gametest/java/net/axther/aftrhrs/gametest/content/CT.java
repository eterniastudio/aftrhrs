package net.axther.aftrhrs.gametest.content;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.axther.aftrhrs.AftrHrs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Shared helpers for the content suites: connected test players, clicks, breaks, drop collection, failure lists. */
public final class CT {
    private CT() {
    }

    /** A real (connected) survival player: FakePlayer can't ride seats or open menus. */
    public static final class Bot {
        public final ServerPlayer p;
        final EmbeddedChannel channel;
        final Connection connection;

        Bot(ServerPlayer p, EmbeddedChannel channel, Connection connection) {
            this.p = p;
            this.channel = channel;
            this.connection = connection;
        }

        /** Drops what the server sent (keeps memory flat in long sweeps). */
        public void drain() {
            if (!channel.isOpen()) return;
            channel.runPendingTasks();
            channel.flushOutbound();
            while (channel.readOutbound() != null) {
                // discard
            }
        }

        public void leave() {
            if (p.hasDisconnected()) return;
            drain();
            connection.disconnect(new DisconnectionDetails(Component.literal("test over")));
            connection.handleDisconnection();
        }
    }

    public static Bot join(GameTestHelper h, String base, boolean op) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        String name = base + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36), 36);
        if (name.length() > 16) name = name.substring(0, 16);
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        Vec3 at = h.absoluteVec(new Vec3(0.5, 1, 0.5));
        player.snapTo(at.x, at.y, at.z, 0, 0);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        // a real client reports "loaded" after the world shows; until then entity clicks are ignored and damage is off
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        if (op) server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
        Bot b = new Bot(player, channel, connection);
        b.drain();
        return b;
    }

    /** Puts the player at a spot looking with yaw/pitch (no packets needed). */
    public static void pose(Player p, Vec3 at, float yaw, float pitch) {
        p.snapTo(at.x, at.y, at.z, yaw, pitch);
        p.setYHeadRot(yaw);
    }

    /** Right-clicks face {@code face} of block {@code pos} with what is in the main hand (full server path). */
    public static InteractionResult click(ServerPlayer p, BlockPos pos, Direction face) {
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return click(p, new BlockHitResult(hit, face, pos, false));
    }

    public static InteractionResult click(ServerPlayer p, BlockHitResult hit) {
        return p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    /** Breaks a block like a survival player finishing mining it. */
    public static boolean breakBlock(ServerPlayer p, BlockPos pos) {
        return p.gameMode.destroyBlock(pos);
    }

    /** Item entities around a spot (picked up and discarded): item id -> total count. */
    public static Map<String, Integer> collectDrops(ServerLevel level, BlockPos center, double r) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(r))) {
            ItemStack s = e.getItem();
            out.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
            e.discard();
        }
        return out;
    }

    public static List<ItemEntity> dropEntities(ServerLevel level, BlockPos center, double r) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(r));
    }

    /** Non-player entities left in an area (seats, display parts...). */
    public static List<Entity> leftovers(ServerLevel level, BlockPos center, double r) {
        return level.getEntities((Entity) null, new AABB(center).inflate(r), e -> !(e instanceof Player) && !(e instanceof ItemEntity) && !e.isRemoved());
    }

    /** Clears a cube of radius r around center to air (no drops, no neighbor updates). */
    public static void clear(ServerLevel level, BlockPos center, int r) {
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            BlockState s = level.getBlockState(q);
            if (!s.isAir()) {
                level.removeBlockEntity(q);
                level.setBlock(q, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
            }
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(center).inflate(r + 1), e -> !(e instanceof Player))) e.discard();
    }

    public static String id(BlockState s) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    /** Collects failures; ends the test with all of them listed (and writes them to build/bt-content/<name>.txt). */
    public static final class Fails {
        final String name;
        final List<String> list = new ArrayList<>();
        int checks;

        public Fails(String name) {
            this.name = name;
        }

        public void check(boolean ok, String msg) {
            checks++;
            if (!ok) list.add(msg);
        }

        public void add(String msg) {
            list.add(msg);
        }

        public boolean any() {
            return !list.isEmpty();
        }

        public void finish(GameTestHelper h) {
            try {
                Path dir = Path.of("bt-content");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(name + ".txt"), checks + " checks, " + list.size() + " failures\n" + String.join("\n", list) + "\n");
            } catch (IOException ignored) {
            }
            AftrHrs.LOG.info("[bt-content] {}: {} checks, {} failures", name, checks, list.size());
            for (String s : list.subList(0, Math.min(list.size(), 200))) AftrHrs.LOG.warn("[bt-content] {}: {}", name, s);
            if (list.isEmpty()) {
                h.succeed();
            } else {
                String head = String.join("; ", list.subList(0, Math.min(list.size(), 12)));
                throw h.assertionException(Component.literal(name + ": " + list.size() + " failures of " + checks + ": " + head));
            }
        }
    }
}
