package net.axther.aftrhrs.core;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Data files that couldn't be read this session, so their feature stopped saving (a broken YAML file is never
 * overwritten). Staff with {@code feather.admin} are told when it happens and again each time they join, one line per
 * file, until a later read of the file works. The console has the details ({@link SafeYaml#read}, the bank).
 */
public final class DataAlerts {
    /** Broken file -> what it holds ("bank balances"), in the order they broke. */
    private static final Map<Path, String> BROKEN = new ConcurrentHashMap<>();
    private static final AtomicBoolean HOOKED = new AtomicBoolean();

    private DataAlerts() {
    }

    /** Hooks the join message (once per JVM; Feather.boot). */
    static void init() {
        if (!HOOKED.compareAndSet(false, true)) return;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> joined(handler.player));
    }

    /**
     * {@code file} couldn't be read and {@code what} is paused until it is fixed. Staff online are told now (on the
     * server thread; these calls come from module load paths, never from chunk loading).
     */
    public static void broken(Path file, String what) {
        if (file == null) return;
        Path key = file.toAbsolutePath().normalize();
        String w = what == null || what.isBlank() ? "changes" : what;
        BROKEN.put(key, w);
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        server.execute(() -> {
            if (server.getPlayerList() == null) return;
            Component line = line(key, w);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) if (Perms.has(p, "feather.admin")) p.sendSystemMessage(line);
        });
    }

    /** Forgets every broken file (Feather.stop: the next singleplayer world has a data folder of its own). */
    static void clear() {
        BROKEN.clear();
    }

    /** {@code file} was read fine (again). */
    public static void fixed(Path file) {
        if (file == null || BROKEN.isEmpty()) return;
        BROKEN.remove(file.toAbsolutePath().normalize());
    }

    /** Whether the file is currently marked broken. */
    public static boolean isBroken(Path file) {
        return file != null && BROKEN.containsKey(file.toAbsolutePath().normalize());
    }

    /** One line per broken file, as staff see them. */
    public static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        for (Map.Entry<Path, String> e : BROKEN.entrySet()) out.add(line(e.getKey(), e.getValue()));
        return out;
    }

    /** Tells a joining staff member about every broken file (nothing for anyone else). */
    public static void joined(ServerPlayer player) {
        if (player == null || BROKEN.isEmpty() || !Perms.has(player, "feather.admin")) return;
        for (Component c : lines()) player.sendSystemMessage(c);
    }

    private static Component line(Path file, String what) {
        String name = shown(file);
        Feather f = Feather.get();
        Text text = f == null ? null : f.text();
        if (text != null && text.has("admin.data-broken")) {
            return text.vanilla("admin.data-broken", Text.unparsed("file", name), Text.unparsed("what", what));
        }
        return Component.literal("\u26A0 " + name + " couldn't be read; " + what + " are paused. See the server console.")
                .withStyle(ChatFormatting.RED);
    }

    /** The path inside the data folder ("data/bank.yml"), else the file name. */
    private static String shown(Path file) {
        Feather f = Feather.get();
        Path root = f == null || f.getDataFolder() == null ? null : f.getDataFolder().toAbsolutePath().normalize();
        if (root != null && file.startsWith(root)) return root.relativize(file).toString().replace('\\', '/');
        Path n = file.getFileName();
        return n == null ? file.toString() : n.toString();
    }
}
