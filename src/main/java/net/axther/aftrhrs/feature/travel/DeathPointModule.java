package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Teleports;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Kept death points: the last few places a player died (world, position, time, cause) live in their profile. On
 * respawn the player gets the coordinates in chat (click to copy). {@code /deathpoint} ({@code /dp}) lists them in a
 * dialog with distance, time ago and cause; Track shows an action-bar arrow toward one until the player is close;
 * staff with {@code feather.deathpoint.teleport} also get Teleport. The Warps &amp; Spawn menu shows a "Death Point"
 * button while the player has one.
 */
public final class DeathPointModule implements FeatherModule, Travel.Named {
    static final String USE = "feather.deathpoint";
    static final String TELEPORT = "feather.deathpoint.teleport";
    /** Arrows clockwise from straight ahead. */
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static volatile DeathPointModule active;

    private final Feather plugin;
    /** Who is tracking which point. Kept by value, so a newer death doesn't switch the target. */
    private final Map<UUID, Profiles.DeathPoint> tracking = new HashMap<>();
    private Tasks.Task task;
    private int keep;
    private double arriveDistance;
    private boolean respawnMessage;

    public DeathPointModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Death points";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        Travel.bind(plugin, this, "deathpoint");
        plugin.once("deathpoint.events", () -> {
            FeatherEvents.PLAYER_DEATH.register((p, source) -> {
                DeathPointModule m = active;
                if (m != null) m.onDeath(p, source);
            });
            FeatherEvents.PLAYER_RESPAWN.register((o, n, alive) -> {
                DeathPointModule m = active;
                if (m != null && !alive) m.onRespawn(n);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
                DeathPointModule m = active;
                if (m != null) m.tracking.remove(h.player.getUUID());
            });
        });
        task = Tasks.timer(10L, 10L, this::tick);
    }

    @Override
    public void reload() {
        keep = Math.max(1, Math.min(10, plugin.getConfig().getInt("death-point.keep", 3)));
        arriveDistance = Math.max(1, plugin.getConfig().getDouble("death-point.arrive-distance", 5));
        respawnMessage = plugin.getConfig().getBoolean("death-point.respawn-message", true);
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (task != null) task.cancel();
        tracking.clear();
    }

    /** The player's kept points, newest first. */
    List<Profiles.DeathPoint> points(ServerPlayer player) {
        return plugin.profiles().get(player).deaths;
    }

    /** Adds the "☠ Death Point" button to the Warps &amp; Spawn menu when the player has a kept point. */
    void addWarpsButton(Dialogs.Menu menu, ServerPlayer player, int width) {
        if (!Perms.has(player, USE) || points(player).isEmpty()) return;
        Text text = plugin.text();
        menu.button(text.msg("deathpoint.warps-button"), text.msg("deathpoint.warps-button-tooltip"), width, p -> {
            text.sound(p, "click");
            open(p);
        });
    }

    // ------------------------------------------------------------------ recording

    private void onDeath(ServerPlayer p, DamageSource source) {
        Profiles.Profile profile = plugin.profiles().get(p);
        profile.deaths.add(0, new Profiles.DeathPoint(Worlds.name(p.level()), p.getX(), p.getY(), p.getZ(),
                System.currentTimeMillis(), cause(p, source)));
        while (profile.deaths.size() > keep) profile.deaths.remove(profile.deaths.size() - 1);
        plugin.profiles().save(profile);
        plugin.placesChanged(p.getUUID());
    }

    /** A short cause: the killer's name ("Zombie", "Bob"), else the damage kind ("Fall", "Lava"). */
    private static String cause(ServerPlayer p, DamageSource source) {
        if (source == null) return "";
        Entity damager = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        if (damager instanceof Projectile proj && proj.getOwner() != null) damager = proj.getOwner();
        if (damager instanceof ServerPlayer pl) return pl.getGameProfile().name();
        if (damager != null) {
            if (damager.getCustomName() != null) return damager.getCustomName().getString();
            return damager.getType().getDescription().getString();
        }
        return pretty(source.type().msgId());
    }

    /** "inFire" / "lava" / "outOfWorld" -> "In fire" / "Lava" / "Out of world". */
    private static String pretty(String id) {
        String s = id.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void onRespawn(ServerPlayer p) {
        if (!respawnMessage || !Perms.has(p, USE)) return;
        UUID id = p.getUUID();
        Tasks.later(10L, () -> {
            ServerPlayer now = plugin.player(id);
            if (now == null || points(now).isEmpty()) return;
            Profiles.DeathPoint d = points(now).get(0);
            plugin.text().send(now, "deathpoint.respawn", Text.component("coords", coordsButton(d)),
                    Text.unparsed("world", Travel.prettyWorld(d.world())),
                    Text.component("button", plugin.text().button("deathpoint.button-open", "/deathpoint")));
        });
    }

    // ------------------------------------------------------------------ text

    private static String coords(Profiles.DeathPoint d) {
        return (int) Math.floor(d.x()) + " " + (int) Math.floor(d.y()) + " " + (int) Math.floor(d.z());
    }

    /** The coordinates, click to copy. */
    private Component coordsButton(Profiles.DeathPoint d) {
        String c = coords(d);
        return plugin.text().msg("deathpoint.coords", Text.unparsed("coords", c))
                .hoverEvent(HoverEvent.showText(plugin.text().msg("deathpoint.coords-hover")))
                .clickEvent(ClickEvent.copyToClipboard(c));
    }

    /** "123 blocks" in the same world, "other world" otherwise. */
    private Component distance(ServerPlayer player, Profiles.DeathPoint d) {
        if (!Worlds.name(player.level()).equals(d.world())) return plugin.text().msg("deathpoint.other-world");
        double dx = d.x() - player.getX(), dy = d.y() - player.getY(), dz = d.z() - player.getZ();
        return plugin.text().msg("deathpoint.distance", Text.unparsed("blocks", (long) Math.sqrt(dx * dx + dy * dy + dz * dz)));
    }

    // ------------------------------------------------------------------ menu

    /** The dialog: one entry per kept point with Track (and Teleport for staff). */
    void open(ServerPlayer player) {
        Text text = plugin.text();
        List<Profiles.DeathPoint> list = points(player);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("deathpoint.menu.title"));
        boolean tp = Perms.has(player, TELEPORT);
        if (list.isEmpty()) menu.text(text.msg("deathpoint.menu.empty"));
        else menu.text(text.msg("deathpoint.menu.body"));
        long now = System.currentTimeMillis();
        Hooks.Graves graves = plugin.service(Hooks.Graves.class);
        for (int i = 0; i < list.size(); i++) {
            Profiles.DeathPoint d = list.get(i);
            int n = i + 1;
            menu.text(text.msg(d.cause().isEmpty() ? "deathpoint.menu.entry-no-cause" : "deathpoint.menu.entry",
                    Text.unparsed("n", n), Text.unparsed("coords", coords(d)), Text.unparsed("world", Travel.prettyWorld(d.world())),
                    Text.component("distance", distance(player, d)), Text.unparsed("ago", Placeholders.duration(now - d.time())),
                    Text.unparsed("cause", d.cause())), 300);
            Component grave = graves == null ? null : graves.deathPointLine(player, d);
            if (grave != null) menu.text(grave, 300);
            boolean tracked = d.equals(tracking.get(player.getUUID()));
            menu.button(text.msg(tracked ? "deathpoint.menu.untrack" : "deathpoint.menu.track", Text.unparsed("n", n)),
                    text.msg("deathpoint.menu.track-tooltip"), tp ? 148 : 300, p -> {
                        toggleTrack(p, d);
                        open(p);
                    });
            if (tp) {
                menu.button(text.msg("deathpoint.menu.teleport", Text.unparsed("n", n)), text.msg("deathpoint.menu.teleport-tooltip"),
                        148, p -> teleport(p, d));
            }
        }
        menu.columns(tp ? 2 : 1);
        plugin.exitButton(menu, player);
        menu.open(player);
        text.sound(player, "menu-open");
    }

    // ------------------------------------------------------------------ tracking

    private void toggleTrack(ServerPlayer player, Profiles.DeathPoint d) {
        Text text = plugin.text();
        if (d.equals(tracking.get(player.getUUID()))) {
            tracking.remove(player.getUUID());
            text.send(player, "deathpoint.track-stopped");
            text.sound(player, "click");
            return;
        }
        tracking.put(player.getUUID(), d);
        text.send(player, "deathpoint.track-started", Text.unparsed("coords", coords(d)),
                Text.unparsed("world", Travel.prettyWorld(d.world())));
        text.sound(player, "click");
        show(player, d);
    }

    private void tick() {
        if (tracking.isEmpty()) return;
        for (Map.Entry<UUID, Profiles.DeathPoint> e : new ArrayList<>(tracking.entrySet())) {
            ServerPlayer p = plugin.player(e.getKey());
            if (p == null) {
                tracking.remove(e.getKey());
                continue;
            }
            show(p, e.getValue());
        }
    }

    /** One action-bar update; stops tracking once the player is close. Leads to the death's grave when it has one. */
    private void show(ServerPlayer p, Profiles.DeathPoint d) {
        Text text = plugin.text();
        Hooks.Graves graves = plugin.service(Hooks.Graves.class);
        Loc grave = graves == null ? null : graves.trackTarget(p.getUUID(), d);
        String world = grave != null ? Worlds.name(grave.level()) : d.world();
        if (!Worlds.name(p.level()).equals(world)) {
            text.actionBar(p, "deathpoint.bar-other-world", Text.unparsed("world", Travel.prettyWorld(world)));
            return;
        }
        double dx = (grave != null ? grave.x() : d.x()) - p.getX(), dy = (grave != null ? grave.y() : d.y()) - p.getY(),
                dz = (grave != null ? grave.z() : d.z()) - p.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= arriveDistance) {
            tracking.remove(p.getUUID());
            text.actionBar(p, "deathpoint.bar-arrived");
            text.send(p, grave != null ? "grave.arrived" : "deathpoint.arrived");
            text.sound(p, "accepted");
            return;
        }
        // Minecraft yaw: 0 = +Z, growing clockwise seen from above; the angle to the point relative to where we look.
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - p.getYRot()) % 360 + 540) % 360 - 180;
        int idx = (int) Math.floorMod(Math.round(rel / 45.0), 8);
        text.actionBar(p, "deathpoint.bar", Text.unparsed("arrow", ARROWS[idx]), Text.unparsed("blocks", (long) dist),
                Text.unparsed("height", dy > 3 ? "↑" : dy < -3 ? "↓" : ""));
    }

    private void teleport(ServerPlayer player, Profiles.DeathPoint d) {
        Text text = plugin.text();
        if (!Perms.has(player, TELEPORT)) {
            text.send(player, "general.no-permission");
            return;
        }
        Loc l = d.location();
        if (l == null) {
            text.send(player, "deathpoint.world-missing", Text.unparsed("world", d.world()));
            text.sound(player, "error");
            return;
        }
        l = l.withRotation(player.getYRot(), player.getXRot());
        ServerLevel w = l.level();
        // Loads the chunk; then the spot (or one close by) must be safe to stand on.
        w.getChunk(l.blockX() >> 4, l.blockZ() >> 4);
        // Not below the world (a death in the void): try the top of that column.
        if (l.y() < w.getMinY() + 1) l = new Loc(w, l.x(), w.getHeight(Heightmap.Types.MOTION_BLOCKING, l.blockX(), l.blockZ()), l.z(), l.yaw(), l.pitch());
        Loc safe = Teleports.safe(l);
        if (safe == null) {
            text.send(player, "deathpoint.unsafe", Text.unparsed("coords", coords(d)));
            text.sound(player, "error");
            return;
        }
        plugin.movePlayer(player, safe).thenAccept(ok -> {
            if (!ok) return;
            text.send(player, "deathpoint.teleported", Text.unparsed("coords", coords(d)));
            text.sound(player, "teleport");
        });
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (!Perms.has(player, USE)) {
            text.send(player, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            open(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("track") && !sub.equals("tp") && !sub.equals("teleport")) {
            text.send(player, "deathpoint.usage");
            return true;
        }
        List<Profiles.DeathPoint> list = points(player);
        if (sub.equals("track") && args.length == 1 && tracking.containsKey(player.getUUID())) {
            toggleTrack(player, tracking.get(player.getUUID()));
            return true;
        }
        int n = 1;
        if (args.length > 1) {
            try {
                n = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                n = -1;
            }
        }
        if (list.isEmpty()) {
            text.send(player, "deathpoint.none");
            return true;
        }
        if (n < 1 || n > list.size()) {
            text.send(player, "deathpoint.bad-number", Text.unparsed("max", list.size()));
            return true;
        }
        Profiles.DeathPoint d = list.get(n - 1);
        if (sub.equals("track")) toggleTrack(player, d);
        else teleport(player, d);
        return true;
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : sender.hasPermission(TELEPORT) ? List.of("track", "tp") : List.of("track")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && sender.player() != null) {
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= points(sender.player()).size(); i++) out.add(String.valueOf(i));
            return out;
        }
        return List.of();
    }
}
