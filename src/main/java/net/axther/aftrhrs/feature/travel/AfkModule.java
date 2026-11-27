package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK: {@code /afk [message]} or going idle for {@code afk.auto-seconds} marks a player away. Moving (turning the head
 * counts, being pushed by water doesn't), chatting, commands and clicking bring them back. AFK players show
 * {@code <afk>} in the tab list and nametags ({@link FeatherEvents#AFK_CHANGED}), don't count for sleeping, and warn
 * anyone who messages them ({@link Hooks.Afk#reason}). An optional kick removes players idle for too long.
 */
public final class AfkModule implements FeatherModule, Travel.Named, Hooks.Afk {
    private static final Identifier EARLY = AftrHrs.id("afk_activity");
    private static volatile AfkModule active;

    /** Where a player looked and stood at the last tick (for "moved or turned"). */
    private record Pose(float yaw, float pitch, int bx, int bz) {
    }

    private final Feather plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<UUID, String> afk = new ConcurrentHashMap<>();
    /** When each player went AFK; activity in the first seconds after /afk is ignored (typing the command moves the head). */
    private final Map<UUID, Long> since = new ConcurrentHashMap<>();
    private final Map<UUID, Pose> poses = new ConcurrentHashMap<>();
    private Tasks.Task task;
    private long autoMillis;
    private long kickMillis;
    private boolean broadcast;
    private boolean ignoreSleep;

    public AfkModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "AFK";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        Travel.bind(plugin, this, "afk");
        plugin.provide(Hooks.Afk.class, this);
        plugin.once("afk.events", AfkModule::hookEvents);
        long now = System.currentTimeMillis();
        for (ServerPlayer p : plugin.onlinePlayers()) lastActive.put(p.getUUID(), now);
        task = Tasks.timer(40L, 40L, this::check);
        plugin.hub().add("afk", Hub.Group.STYLE, Hub.ORDER_AFK, "hub-afk", "feather.afk", p -> Travel.perform(p, "afk"));
    }

    private static void hookEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AfkModule m = active;
            if (m != null) m.watchMoves();
        });
        // Early, so a chat module that cancels and resends lines can't hide the activity.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(EARLY, (message, sender, params) -> {
            AfkModule m = active;
            if (m != null && !m.plugin.authPending(sender)) m.active(sender);
            return true;
        });
        FeatherEvents.COMMAND.register((p, cmd) -> {
            AfkModule m = active;
            String c = cmd.toLowerCase(Locale.ROOT);
            if (m != null && !isAfkCommand(c)) m.active(p);
            return true;
        });
        UseBlockCallback.EVENT.register((p, w, h, hit) -> interacted(p));
        UseItemCallback.EVENT.register((p, w, h) -> interacted(p));
        AttackBlockCallback.EVENT.register((p, w, h, pos, dir) -> interacted(p));
        UseEntityCallback.EVENT.register((p, w, h, e, hit) -> interacted(p));
        AttackEntityCallback.EVENT.register((p, w, h, e, hit) -> interacted(p));
        FeatherEvents.SLEEP_IGNORED.register(p -> {
            AfkModule m = active;
            return m != null && m.ignoreSleep && m.isAfk(p);
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            AfkModule m = active;
            if (m != null) m.lastActive.put(h.player.getUUID(), System.currentTimeMillis());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            AfkModule m = active;
            if (m == null) return;
            UUID id = h.player.getUUID();
            m.afk.remove(id);
            m.since.remove(id);
            m.lastActive.remove(id);
            m.poses.remove(id);
        });
    }

    private static boolean isAfkCommand(String c) {
        for (String name : List.of("afk", "away", "feather:afk")) if (c.equals(name) || c.startsWith(name + " ")) return true;
        return false;
    }

    private static InteractionResult interacted(Player p) {
        AfkModule m = active;
        if (m != null && p instanceof ServerPlayer sp) m.active(sp);
        return InteractionResult.PASS;
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        autoMillis = Math.max(0, c.getLong("afk.auto-seconds", 300)) * 1000L;
        kickMillis = Math.max(0, c.getLong("afk.kick-seconds", 0)) * 1000L;
        broadcast = c.getBoolean("afk.broadcast", true);
        ignoreSleep = c.getBoolean("afk.ignore-for-sleep", true);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        boolean any = !afk.isEmpty();
        afk.clear();
        since.clear();
        if (active == this) active = null;
        if (any && ignoreSleep) for (ServerPlayer p : plugin.onlinePlayers()) p.level().updateSleepingPlayerList();
    }

    @Override
    public boolean isAfk(ServerPlayer p) {
        return p != null && afk.containsKey(p.getUUID());
    }

    /** The message given with /afk, or "". */
    @Override
    public String reason(ServerPlayer p) {
        String r = p == null ? null : afk.get(p.getUUID());
        return r == null ? "" : r;
    }

    private void setAfk(ServerPlayer p, boolean on, String message) {
        Text text = plugin.text();
        UUID id = p.getUUID();
        if (on == isAfk(p)) return;
        if (on) {
            afk.put(id, message == null ? "" : message);
            since.put(id, System.currentTimeMillis());
            plugin.advance(p, "event", "afk");
        } else {
            afk.remove(id);
            since.remove(id);
            lastActive.put(id, System.currentTimeMillis());
        }
        if (ignoreSleep) p.level().updateSleepingPlayerList();
        boolean hidden = plugin.profiles().get(p).vanished;
        if (broadcast && !hidden) {
            String key = on ? (message == null || message.isEmpty() ? "afk.now" : "afk.now-message") : "afk.back";
            Component line = text.msg(key, Text.component("name", Names.display(p)),
                    Text.unparsed("message", message == null ? "" : message));
            Hooks.Ignores ign = plugin.service(Hooks.Ignores.class);
            for (ServerPlayer viewer : plugin.onlinePlayers()) {
                if (ign != null && viewer != p && ign.ignores(viewer, p)) continue;
                Adv.send(viewer, line);
            }
            Feather.server().sendSystemMessage(Adv.vanilla(line));
        } else {
            text.send(p, on ? "afk.now-self" : "afk.back-self");
        }
        FeatherEvents.AFK_CHANGED.invoker().changed(p, on);
    }

    private void active(ServerPlayer p) {
        long now = System.currentTimeMillis();
        UUID id = p.getUUID();
        lastActive.put(id, now);
        if (isAfk(p) && now - since.getOrDefault(id, 0L) > 2500) Tasks.sync(() -> {
            ServerPlayer still = plugin.player(id);
            if (still != null) setAfk(still, false, null);
        });
    }

    /** Every tick: a turned head is a person; walking counts too, unless water or a vehicle did the moving. */
    private void watchMoves() {
        for (ServerPlayer p : plugin.onlinePlayers()) {
            Pose now = new Pose(p.getYRot(), p.getXRot(), p.getBlockX(), p.getBlockZ());
            Pose before = poses.put(p.getUUID(), now);
            if (before == null) continue;
            boolean turned = before.yaw() != now.yaw() || before.pitch() != now.pitch();
            boolean walked = !p.isInWater() && !p.isPassenger() && (before.bx() != now.bx() || before.bz() != now.bz());
            if (turned || walked) active(p);
        }
    }

    private void check() {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : List.copyOf(plugin.onlinePlayers())) {
            long idle = now - lastActive.getOrDefault(p.getUUID(), now);
            if (!isAfk(p) && autoMillis > 0 && idle >= autoMillis && !Perms.has(p, "feather.afk.exempt")) setAfk(p, true, null);
            if (kickMillis > 0 && idle >= kickMillis && !Perms.has(p, "feather.afk.kickexempt")) {
                p.connection.disconnect(plugin.text().vanilla("afk.kicked"));
            }
        }
    }

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        ServerPlayer p = sender.player();
        if (p == null) {
            plugin.text().send(sender, "general.players-only");
            return true;
        }
        if (isAfk(p)) {
            setAfk(p, false, null);
        } else {
            String msg = args.length == 0 || !Perms.has(p, "feather.afk.message") ? "" : String.join(" ", args);
            // The AFK note goes to everyone, so it follows the chat rules: no note while muted or while chat is paused.
            Hooks.ChatGate gate = plugin.service(Hooks.ChatGate.class);
            if (!msg.isEmpty() && gate != null && gate.silenced(p)) msg = "";
            setAfk(p, true, msg.length() > 64 ? msg.substring(0, 64) : msg);
        }
        return true;
    }
}
