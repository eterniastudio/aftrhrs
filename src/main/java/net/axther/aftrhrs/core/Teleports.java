package net.axther.aftrhrs.core;

import net.axther.aftrhrs.core.event.TeleportCause;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Teleports with a warm-up: an action-bar countdown with ticking sounds that cancels if the player moves or is
 * hurt, then a whoosh of particles at both ends. Moves are tagged {@link TeleportCause#COMMAND}, so /back records the
 * departure point.
 *
 * <p>Teleports to places that may have changed since they were saved (homes, warps, /back, a friend) are checked with
 * {@link #safe}: the spot, or one close by, must have room to stand, a floor, and no lava, fire or void. A destination
 * chunk that isn't loaded is loaded through a ticket first (never a server-thread getChunk), and the teleport finishes
 * once it is there; a newer teleport, a quit, a death or being moved elsewhere drops that pending arrival.
 */
public final class Teleports {
    /** How far {@link #safe} looks for another spot: blocks sideways, and up and down. */
    private static final int SEARCH_RADIUS = 2, SEARCH_HEIGHT = 8;
    /** Blocks nobody should be dropped into or onto. */
    private static final Set<Block> DANGER = Set.of(Blocks.LAVA, Blocks.LAVA_CAULDRON, Blocks.FIRE,
            Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POWDER_SNOW, Blocks.NETHER_PORTAL,
            Blocks.END_PORTAL, Blocks.END_GATEWAY);

    private final Feather plugin;
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    /** Players whose checked teleport waits for its destination chunk: the token of the newest one. */
    private final Map<UUID, Object> pending = new HashMap<>();
    private int warmupSeconds;
    private boolean cancelOnMove;
    private boolean cancelOnDamage;
    private boolean particles;

    private static final class Warmup {
        final ServerPlayer player;
        final Loc start;
        /** Runs when the warm-up is called off because the player moved or got hurt (not when replaced). */
        final Runnable onCancel;
        /** The chat line the player gets when it is called off (with {@code <reason>}). */
        final String cancelKey;
        Tasks.Task task;

        Warmup(ServerPlayer player, Runnable onCancel, String cancelKey) {
            this.player = player;
            this.start = Loc.of(player);
            this.onCancel = onCancel;
            this.cancelKey = cancelKey;
        }
    }

    Teleports(Feather plugin) {
        this.plugin = plugin;
    }

    /** Hooks damage and quit (once, from boot). */
    void init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!cancelOnDamage || warmups.isEmpty() || blocked || !(entity instanceof ServerPlayer player)) return;
            if (warmups.containsKey(player.getUUID())) {
                cancel(player, true, Text.component("reason", plugin.text().msg("teleport.reason-hurt")));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> cancel(h.player, false));
        // Dying ends a warm-up: the respawn is a new ServerPlayer, the old one would "arrive" and fail (or, with
        // cancel-on-damage off, never notice the death).
        net.axther.aftrhrs.core.event.FeatherEvents.PLAYER_DEATH.register((p, source) -> cancel(p, false));
        // Moved away by anything else (a portal, /tp, a jail or robbery move) while the destination chunk loads: that
        // arrival is stale and must not pull the player back. Small nudges (a dismount, a sit fix) keep it. The
        // continuation drops its own token before it moves the player, so its own arrival never lands here. Every
        // dimension change goes through ServerPlayer.teleport(TeleportTransition), so this covers portals too.
        net.axther.aftrhrs.core.event.FeatherEvents.PLAYER_TELEPORTED.register((p, from, to, cause) -> {
            if (!pending.isEmpty() && to.distanceSquared(from) > 64) pending.remove(p.getUUID()); // MAX across levels
        });
    }

    void reload(Yaml c) {
        warmupSeconds = Math.max(0, c.getInt("teleport.warmup-seconds", 3));
        cancelOnMove = c.getBoolean("teleport.cancel-on-move", true);
        cancelOnDamage = c.getBoolean("teleport.cancel-on-damage", true);
        particles = c.getBoolean("teleport.particles", true);
    }

    /** The warm-up this player gets, in seconds (0: they go at once). */
    public int warmupFor(ServerPlayer player) {
        return Perms.has(player, "feather.teleport.instant") ? 0 : warmupSeconds;
    }

    /**
     * Teleports {@code player} to {@code destination} (resolved when the warm-up ends, so a moving target is
     * followed) and runs {@code onArrive} afterwards. A newer teleport replaces an older warm-up.
     */
    public void teleport(ServerPlayer player, Supplier<Loc> destination, Component destName, Runnable onArrive) {
        teleport(player, destination, destName, onArrive, false, null);
    }

    /**
     * {@link #teleport(ServerPlayer, Supplier, Component, Runnable)}; with {@code checkSafe} the destination is checked
     * with {@link #safe} when the warm-up ends (a nearby safe spot is used, or the teleport is refused).
     * {@code onCancel} runs when the warm-up is called off because the player moved or got hurt.
     */
    public void teleport(ServerPlayer player, Supplier<Loc> destination, Component destName, Runnable onArrive,
                         boolean checkSafe, Runnable onCancel) {
        teleport(player, destination, destName, onArrive, checkSafe, onCancel, "teleport.cancelled");
    }

    /**
     * {@link #teleport(ServerPlayer, Supplier, Component, Runnable, boolean, Runnable)}; {@code cancelKey} is the
     * messages.yml chat line the player gets when the warm-up is called off (with {@code <reason>}; null:
     * {@code teleport.cancelled}), so a caller can say more in one line instead of adding a second.
     */
    public void teleport(ServerPlayer player, Supplier<Loc> destination, Component destName, Runnable onArrive,
                         boolean checkSafe, Runnable onCancel, String cancelKey) {
        cancel(player, false);
        if (plugin.travelLocked(player)) {
            // Robbing the bank: no teleports at all (docs/bank-robbery.md).
            plugin.text().send(player, "bank.robbery.teleport-blocked");
            plugin.text().sound(player, "error");
            return;
        }
        int seconds = warmupFor(player);
        if (seconds <= 0) {
            go(player, destination, destName, onArrive, checkSafe);
            return;
        }
        Warmup warmup = new Warmup(player, onCancel, cancelKey == null ? "teleport.cancelled" : cancelKey);
        warmups.put(player.getUUID(), warmup);
        Text text = plugin.text();
        int total = seconds * 20;
        int[] ticks = {0};
        warmup.task = Tasks.timer(1L, 1L, () -> {
            if (player.hasDisconnected() || warmups.get(player.getUUID()) != warmup) {
                if (warmup.task != null) warmup.task.cancel();
                return;
            }
            if (cancelOnMove && moved(warmup, Loc.of(player))) {
                cancel(player, true, Text.component("reason", text.msg("teleport.reason-moved")));
                return;
            }
            if (ticks[0] >= total) {
                warmups.remove(player.getUUID());
                warmup.task.cancel();
                go(player, destination, destName, onArrive, checkSafe);
                return;
            }
            if (ticks[0] % 20 == 0) {
                int left = (total - ticks[0] + 19) / 20;
                float pitch = 0.8f + 0.5f * ((float) ticks[0] / total);
                text.sound(player, "warmup-tick", pitch);
                if (particles) {
                    player.level().sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(),
                            12, 0.35, 0.6, 0.35, 0.02);
                }
                text.actionBar(player, "teleport.warmup", Text.unparsed("seconds", left),
                        Text.component("bar", bar(ticks[0], total)), Text.component("destination", destName));
            }
            ticks[0]++;
        });
    }

    private static boolean moved(Warmup w, Loc to) {
        return !to.sameLevel(w.start) || to.distanceSquared(w.start) > 0.36;
    }

    private Component bar(int ticks, int total) {
        int cells = 12;
        int done = Math.round(cells * (float) ticks / total);
        return plugin.text().parse("<accent>" + "▮".repeat(done) + "</accent><muted>" + "▮".repeat(cells - done) + "</muted>");
    }

    private void go(ServerPlayer player, Supplier<Loc> destination, Component destName, Runnable onArrive, boolean checkSafe) {
        Loc to = destination.get();
        Text text = plugin.text();
        if (to == null || to.level() == null) {
            text.send(player, "teleport.destination-gone");
            text.sound(player, "error");
            return;
        }
        if (!checkSafe) {
            travel(player, to, destName, onArrive);
            return;
        }
        ServerLevel w = to.level();
        int cx = to.blockX() >> 4, cz = to.blockZ() >> 4;
        if (w.getChunkSource().getChunkNow(cx, cz) != null) {
            arrive(player, safe(to), destName, onArrive);
            return;
        }
        // Not loaded: a loading ticket (vanilla PLAYER_SPAWN, gone 20 ticks after the load) brings the chunk in off the
        // server thread's back, like /rtp; the check and the move run on the server thread once it is there. Only the
        // centre chunk is waited for: safe() skips spots in unloaded neighbours.
        UUID id = player.getUUID();
        Object token = new Object();
        pending.put(id, token);
        w.getChunkSource().addTicketAndLoadWithRadius(TicketType.PLAYER_SPAWN, new ChunkPos(cx, cz), 0)
                .whenComplete((res, err) -> Tasks.sync(() -> {
                    if (pending.get(id) != token) return; // replaced by a newer teleport, or the player left or died
                    pending.remove(id);
                    ServerPlayer here = plugin.player(id);
                    if (here == null) return;
                    if (plugin.travelLocked(here)) {
                        // A robbery started while the chunk loaded: say why, like teleport() does.
                        plugin.text().send(here, "bank.robbery.teleport-blocked");
                        plugin.text().sound(here, "error");
                        return;
                    }
                    arrive(here, err == null ? safe(to) : null, destName, onArrive);
                }));
    }

    /** Moves the player to a checked spot, or tells them there was none. */
    private void arrive(ServerPlayer player, Loc safe, Component destName, Runnable onArrive) {
        if (safe == null) {
            Text text = plugin.text();
            text.send(player, "teleport.unsafe", Text.component("destination", destName));
            text.sound(player, "error");
            return;
        }
        travel(player, safe, destName, onArrive);
    }

    /** True while a checked teleport of this player waits for its destination chunk to load. */
    public boolean isLoadingDestination(ServerPlayer player) {
        return pending.containsKey(player.getUUID());
    }

    private void travel(ServerPlayer player, Loc to, Component destName, Runnable onArrive) {
        Text text = plugin.text();
        if (particles) {
            player.level().sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1, player.getZ(), 60, 0.4, 0.8, 0.4, 0.4);
        }
        if (!plugin.teleportNow(player, to, TeleportCause.COMMAND)) {
            text.send(player, "teleport.failed");
            text.sound(player, "error");
            return;
        }
        ServerPlayer here = plugin.player(player.getUUID());
        if (here == null) return;
        if (particles) {
            here.level().sendParticles(ParticleTypes.REVERSE_PORTAL, here.getX(), here.getY() + 1, here.getZ(), 50, 0.4, 0.8, 0.4, 0.05);
            here.level().sendParticles(ParticleTypes.END_ROD, here.getX(), here.getY() + 1, here.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
        }
        text.sound(here, "teleport");
        text.actionBar(here, "teleport.arrived", Text.component("destination", destName));
        if (onArrive != null) onArrive.run();
    }

    // ------------------------------------------------------------------ safe spots

    /**
     * {@code l} itself when a player can stand there (room for feet and head, a floor, no lava, fire or portal, above
     * the void), else the closest such spot within a couple of blocks sideways and {@value #SEARCH_HEIGHT} up or down
     * (keeping the facing), else null. Only looks into loaded chunks.
     */
    public static Loc safe(Loc l) {
        ServerLevel w = l.level();
        if (w == null) return null;
        if (standable(w, l.x(), l.y(), l.z(), false)) return l;
        int bx = l.blockX(), by = l.blockY(), bz = l.blockZ();
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = bx + dx, cz = bz + dz;
                    if (!w.hasChunk(cx >> 4, cz >> 4)) continue;
                    for (int i = 0; i <= SEARCH_HEIGHT * 2; i++) {
                        int cy = by + (i % 2 == 1 ? (i + 1) / 2 : -(i / 2));
                        if (cy - 1 < w.getMinY() || cy + 2 > w.getMaxY() + 1) continue;
                        BlockPos below = new BlockPos(cx, cy - 1, cz);
                        double top = collisionTop(w, below);
                        if (top <= 0) continue;
                        double y = below.getY() + top;
                        if (standable(w, cx + 0.5, y, cz + 0.5, true)) return new Loc(w, cx + 0.5, y, cz + 0.5, l.yaw(), l.pitch());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Whether a player standing with their feet at {@code y} is safe: nothing that collides with their body, a floor
     * at most half a block under their feet, and no lava, fire, magma, portal or the like from just under the feet to
     * the head. {@code dryHead}: also no water at eye height (for spots we pick). False when a block it needs to look
     * at is in an unloaded chunk.
     */
    private static boolean standable(ServerLevel w, double x, double y, double z, boolean dryHead) {
        if (y < w.getMinY() + 0.5 || y + 1.8 > w.getMaxY() + 1) return false;
        int x0 = (int) Math.floor(x - 0.3), x1 = (int) Math.floor(x + 0.3);
        int z0 = (int) Math.floor(z - 0.3), z1 = (int) Math.floor(z + 0.3);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) if (!w.hasChunk(cx >> 4, cz >> 4)) return false;
        }
        AABB body = new AABB(x - 0.3, y + 1e-3, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        AABB underfoot = new AABB(x - 0.3, y - 0.5, z - 0.3, x + 0.3, y + 1e-3, z + 0.3);
        int eyes = (int) Math.floor(y + 1.62);
        boolean floor = false;
        for (int cy = (int) Math.floor(y - 0.5); cy <= (int) Math.floor(y + 1.8); cy++) {
            if (cy < w.getMinY()) continue;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    BlockPos pos = new BlockPos(cx, cy, cz);
                    BlockState b = w.getBlockState(pos);
                    if (DANGER.contains(b.getBlock())) return false;
                    if (dryHead && cy == eyes && b.is(Blocks.WATER)) return false;
                    for (AABB box : b.getCollisionShape(w, pos).toAabbs()) {
                        AABB at = box.move(cx, cy, cz);
                        if (at.intersects(body)) return false;
                        if (at.intersects(underfoot)) floor = true;
                    }
                }
            }
        }
        return floor;
    }

    /** The top of a block's collision box, relative to the block (0 when nothing collides, 1.5 for a fence). */
    private static double collisionTop(ServerLevel w, BlockPos pos) {
        double top = 0;
        for (AABB box : w.getBlockState(pos).getCollisionShape(w, pos).toAabbs()) top = Math.max(top, box.maxY);
        return top;
    }

    // ------------------------------------------------------------------ cancelling

    /**
     * Cancels a running warm-up; with {@code notify} the player is told why. Also drops an arrival still waiting for
     * its destination chunk (a newer teleport, a quit or a death replaces it).
     */
    public boolean cancel(ServerPlayer player, boolean notify, TagResolver... reason) {
        pending.remove(player.getUUID());
        Warmup w = warmups.remove(player.getUUID());
        if (w == null) return false;
        if (w.task != null) w.task.cancel();
        if (notify) {
            plugin.text().send(player, w.cancelKey, reason);
            plugin.text().actionBar(player, "teleport.cancelled-bar");
            plugin.text().sound(player, "cancel");
            if (w.onCancel != null) w.onCancel.run();
        }
        return true;
    }

    public boolean isWarmingUp(ServerPlayer player) {
        return warmups.containsKey(player.getUUID());
    }

    void cancelAll() {
        for (Warmup w : warmups.values()) if (w.task != null) w.task.cancel();
        warmups.clear();
        pending.clear();
    }

    /** "x, y, z" block coordinates. */
    public static String coords(Loc l) {
        return l.coords();
    }
}
