package net.axther.aftrhrs.feature.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.net.Companion;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Places for the Companion ({@code places} feature, docs/companion-protocol.md): a modded player's homes, the warps
 * they may use, spawn, their newest death point and their graves, as data. The client draws a compass, beams and a
 * travel screen, and travels by running the usual commands. A list goes out whenever it (or the player's world) differs
 * from the last one sent: checked for everyone every 2 s, and within a few ticks for players whose places just changed.
 * Runs while {@code modules.companion} is on (whatever travel modules run), like the plugin's bridge.
 */
public final class PlacesCompanion implements FeatherModule {
    private static final String FEATURE = "places";
    /** Every this many runs of the 10-tick task, everyone is checked (not only players marked as changed). */
    private static final int FULL_EVERY = 4;
    private static volatile PlacesCompanion active;

    private final Feather plugin;
    /** What each player was last sent: their world and the list, as one string. */
    private final Map<UUID, String> sent = new HashMap<>();
    private final Set<UUID> changed = new HashSet<>();
    private boolean allChanged;
    private int runs;
    private Tasks.Task task;

    public PlacesCompanion(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Companion places";
    }

    @Override
    public void enable() {
        if (!plugin.getConfig().getBoolean("modules.companion", true)) return;
        active = this;
        plugin.provide(Hooks.PlacesChanged.class, this::changed);
        plugin.once("places-companion.events", () -> {
            Companion.onQuit(p -> {
                PlacesCompanion m = active;
                if (m == null) return;
                m.sent.remove(p.getUUID());
                m.changed.remove(p.getUUID());
            });
            ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
                PlacesCompanion m = active;
                if (m != null) m.changed.add(p.getUUID());
            });
        });
        task = Tasks.timer(20L, 10L, this::tick);
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (task != null) task.cancel();
        task = null;
        sent.clear();
        changed.clear();
    }

    /** A player's places changed (null: everyone's, e.g. a warp or spawn); their list goes out on the next run. */
    void changed(UUID player) {
        if (player == null) allChanged = true;
        else changed.add(player);
    }

    private void tick() {
        boolean all = allChanged || ++runs % FULL_EVERY == 0;
        if (!all && changed.isEmpty()) return;
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if ((all || changed.contains(p.getUUID())) && Companion.has(p, FEATURE)) check(p);
        }
        allChanged = false;
        changed.clear();
    }

    private void check(ServerPlayer p) {
        JsonObject msg = Companion.message("places");
        msg.addProperty("w", Worlds.name(p.level()));
        msg.add("list", list(p));
        String key = msg.toString();
        if (key.equals(sent.get(p.getUUID()))) return;
        sent.put(p.getUUID(), key);
        Companion.send(p, msg);
    }

    private JsonArray list(ServerPlayer p) {
        JsonArray list = new JsonArray();
        if (plugin.module(HomesModule.class) != null && Perms.has(p, "feather.home")) {
            for (Profiles.Home h : plugin.profiles().get(p).homes().values()) list.add(icon(place("home", h.name(), h.world(), h.x(), h.y(), h.z()), h.icon()));
        }
        PlacesModule places = plugin.module(PlacesModule.class);
        if (places != null) {
            for (Profiles.Home h : places.usableWarps(p)) list.add(icon(place("warp", h.name(), h.world(), h.x(), h.y(), h.z()), h.icon()));
            if (Perms.has(p, "feather.spawn")) {
                Loc s = places.spawnLocation();
                list.add(place("spawn", "Spawn", Worlds.name(s.level()), s.x(), s.y(), s.z()));
            }
        }
        DeathPointModule deaths = plugin.module(DeathPointModule.class);
        if (deaths != null && Perms.has(p, DeathPointModule.USE)) {
            List<Profiles.DeathPoint> points = deaths.points(p);
            if (!points.isEmpty()) {
                Profiles.DeathPoint d = points.get(0);
                list.add(place("death", "death", d.world(), d.x(), d.y(), d.z()));
            }
        }
        Hooks.Graves graves = plugin.service(Hooks.Graves.class);
        if (graves != null) {
            for (Hooks.Graves.GravePlace g : graves.of(p.getUUID())) list.add(place("grave", "Grave #" + g.id(), g.world(), g.x() + 0.5, g.y(), g.z() + 0.5));
        }
        return list;
    }

    private static JsonObject place(String kind, String name, String world, double x, double y, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("name", name);
        o.addProperty("w", world);
        o.addProperty("x", round(x));
        o.addProperty("y", round(y));
        o.addProperty("z", round(z));
        return o;
    }

    /** A home's or warp's own icon (an item id; the client shows the place's symbol without one). */
    private static JsonObject icon(JsonObject place, String icon) {
        if (icon != null) place.addProperty("icon", icon);
        return place;
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
