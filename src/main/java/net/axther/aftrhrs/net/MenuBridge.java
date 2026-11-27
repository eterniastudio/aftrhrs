package net.axther.aftrhrs.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Feather menus as the mod's own screen (feature {@code menus}, {@code hub} for the hub's group tabs), the default
 * for every player now that the mod is on both sides: {@link Dialogs.Menu#open} offers each menu here first and it
 * goes out as a {@code menu} message; clicks come back by button index ({@code menu_click}, {@code menu_closed}).
 * Like the vanilla dialog's clicks, a menu id works once and for ten minutes, and input values are checked against
 * the menu's own inputs ({@link Dialogs.Response#of}). With {@code companion.features.menus} off (or the companion
 * module off) players get the vanilla dialog.
 *
 * <p>Port of Feather's {@code MenuBridge}. The hub key ({@code open_hub}), {@code open_settings} and the Settings
 * page's "HUD &amp; Display" row live in {@link Companion}.
 */
public final class MenuBridge {
    private static final long LIFETIME_MS = 10 * 60 * 1000L;
    /** Menus a player can have waiting at once (back-and-forth screens leave a few behind). */
    private static final int KEEP = 6;

    private record Pending(int id, Dialogs.Menu menu, List<Dialogs.Button> flat, long expires) {
    }

    private static final Map<UUID, Deque<Pending>> PENDING = new HashMap<>();
    private static int nextId;
    private static boolean registered;

    private MenuBridge() {
    }

    /** Message handlers and quit cleanup, once (from {@link Companion#init}). */
    static void init() {
        if (registered) return;
        registered = true;
        Companion.on("menu_click", "menus", MenuBridge::click);
        Companion.on("menu_closed", "menus", (p, m) -> take(p, Wire.num(m, "id", -1)));
        Companion.onQuit(p -> PENDING.remove(p.getUUID()));
    }

    /** The companion module started: menus go to the mod. */
    static void start(Feather feather) {
        feather.dialogs().upgrader(MenuBridge::offer);
    }

    static void stop(Feather feather) {
        feather.dialogs().upgrader(null);
        PENDING.clear();
    }

    // ------------------------------------------------------------------ menus out

    static boolean offer(ServerPlayer player, Dialogs.Menu menu) {
        if (!Companion.has(player, "menus")) return false;
        boolean hub = "hub".equals(menu.layout()) && Companion.has(player, "hub");
        int id = ++nextId;
        List<Dialogs.Button> flat = new ArrayList<>(menu.buttons());

        JsonObject m = Companion.message("menu");
        m.addProperty("id", id);
        add(m, "title", Wire.text(menu.title()));
        m.addProperty("layout", hub ? "hub" : "list");
        JsonArray body = new JsonArray();
        for (Dialogs.Body b : menu.body()) {
            JsonObject o = new JsonObject();
            boolean item = b.item() != null && !b.item().isEmpty();
            o.addProperty("k", item ? "item" : "text");
            if (item) add(o, "item", Wire.item(b.item()));
            add(o, "c", Wire.text(b.text()));
            o.addProperty("w", b.width());
            body.add(o);
        }
        m.add("body", body);
        m.add("inputs", inputs(menu.inputs()));
        m.add("buttons", buttons(menu.buttons()));
        m.addProperty("columns", menu.columnCount());
        Dialogs.Button exit = menu.exitButton();
        if (exit != null) {
            JsonObject e = new JsonObject();
            add(e, "c", Wire.text(exit.label()));
            e.addProperty("cb", exit.onClick() != null);
            m.add("exit", e);
        }
        if (hub) {
            JsonArray sections = new JsonArray();
            for (Dialogs.Section s : menu.sections()) {
                JsonObject o = new JsonObject();
                o.addProperty("key", s.key());
                add(o, "c", Wire.text(s.label()));
                o.add("buttons", buttons(s.buttons()));
                flat.addAll(s.buttons());
                sections.add(o);
            }
            m.add("sections", sections);
        }

        Deque<Pending> q = PENDING.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        q.addLast(new Pending(id, menu, flat, System.currentTimeMillis() + LIFETIME_MS));
        while (q.size() > KEEP) q.removeFirst();
        Companion.send(player, m);
        return true;
    }

    private static void add(JsonObject o, String key, JsonElement value) {
        if (value != null) o.add(key, value);
    }

    private static JsonArray buttons(List<Dialogs.Button> list) {
        JsonArray arr = new JsonArray();
        for (Dialogs.Button b : list) {
            JsonObject o = new JsonObject();
            add(o, "c", Wire.text(b.label()));
            if (b.tooltip() != null) add(o, "tip", Wire.text(b.tooltip()));
            o.addProperty("w", b.width());
            if (b.section() != null) o.addProperty("section", b.section());
            if (b.icon() != null && !b.icon().isEmpty()) add(o, "icon", Wire.item(b.icon()));
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray inputs(List<Dialogs.Input> list) {
        JsonArray arr = new JsonArray();
        for (Dialogs.Input in : list) {
            JsonObject o = new JsonObject();
            o.addProperty("key", in.key());
            switch (in) {
                case Dialogs.TextField t -> {
                    o.addProperty("k", "text");
                    if (t.labelVisible()) add(o, "label", Wire.text(t.label()));
                    o.addProperty("initial", t.initial());
                    o.addProperty("max", t.maxLength());
                    o.addProperty("w", t.width());
                    o.addProperty("multiline", t.multiline());
                }
                case Dialogs.Toggle b -> {
                    o.addProperty("k", "bool");
                    add(o, "label", Wire.text(b.label()));
                    o.addProperty("initial", b.initial());
                }
                case Dialogs.Range r -> {
                    o.addProperty("k", "range");
                    add(o, "label", Wire.text(r.label()));
                    o.addProperty("format", r.labelFormat());
                    o.addProperty("min", r.start());
                    o.addProperty("max", r.end());
                    if (r.step() != null) o.addProperty("step", r.step());
                    o.addProperty("initial", r.initial() != null ? r.initial() : r.start());
                    o.addProperty("w", r.width());
                }
                case Dialogs.Choice s -> {
                    o.addProperty("k", "option");
                    if (s.labelVisible()) add(o, "label", Wire.text(s.label()));
                    o.addProperty("w", s.width());
                    JsonArray opts = new JsonArray();
                    for (Dialogs.Option e : s.options()) {
                        JsonObject eo = new JsonObject();
                        eo.addProperty("id", e.id());
                        add(eo, "c", Wire.text(e.display() != null ? e.display() : Component.text(e.id())));
                        eo.addProperty("initial", e.initial());
                        opts.add(eo);
                    }
                    o.add("options", opts);
                }
            }
            arr.add(o);
        }
        return arr;
    }

    // ------------------------------------------------------------------ clicks

    private static Pending take(ServerPlayer player, int id) {
        Deque<Pending> q = PENDING.get(player.getUUID());
        if (q == null) return null;
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = q.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.expires() < now) {
                it.remove();
            } else if (p.id() == id) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    private static void click(ServerPlayer player, JsonObject msg) {
        int id = Wire.num(msg, "id", -1);
        Pending p = take(player, id);
        if (p == null) return;
        int index = Wire.num(msg, "button", Integer.MIN_VALUE);
        Dialogs.Button button;
        if (index == -1) button = p.menu().exitButton();
        else if (index >= 0 && index < p.flat().size()) button = p.flat().get(index);
        else return;
        JsonObject values = msg.has("values") && msg.get("values").isJsonObject() ? msg.getAsJsonObject("values") : new JsonObject();
        Feather feather = Feather.get();
        if (button != null && feather != null) {
            feather.dialogs().run(player, button, Dialogs.Response.of(p.menu().inputs(), key -> raw(values.get(key))));
        }
        JsonObject done = Companion.message("menu_done");
        done.addProperty("id", id);
        Companion.send(player, done);
    }

    /** A JSON value as what {@link Dialogs.Response#of} reads: String, Number, Boolean or null. */
    private static Object raw(JsonElement v) {
        if (v == null || !v.isJsonPrimitive()) return null;
        var prim = v.getAsJsonPrimitive();
        if (prim.isBoolean()) return prim.getAsBoolean();
        if (prim.isNumber()) return prim.getAsFloat();
        return prim.getAsString();
    }
}
