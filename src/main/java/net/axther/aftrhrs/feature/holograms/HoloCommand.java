package net.axther.aftrhrs.feature.holograms;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /holo (alias /hologram): staff commands to make and edit holograms. Pages and lines are numbered from 1. Content:
 * plain text is a text line; {@code #ITEM:}, {@code #HEAD:}, {@code #BLOCK:}, {@code #ENTITY:} and {@code #BLANK}
 * make the other kinds. Most commands work from the console; {@code create} there needs {@code --at <world> <x> <y> <z>}.
 */
final class HoloCommand implements FeatherCommand {
    private static final List<String> SUBS = List.of("create", "delete", "list", "near", "info", "tp", "movehere", "move",
            "addline", "setline", "insertline", "removeline", "line", "page", "set", "action", "copy", "edit", "view", "reload", "help");

    private final HologramsModule m;

    HoloCommand(HologramsModule m) {
        this.m = m;
    }

    private Text text() {
        return m.text();
    }

    private void send(Sender to, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        text().send(to, "holograms." + key, r);
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver u(String k, Object v) {
        return Text.unparsed(k, v);
    }

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(HologramsModule.ADMIN)) {
            text().send(sender, "general.no-permission");
            return true;
        }
        if (sender.player() != null && m.authBlocked(sender.player())) return true;
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "create" -> create(sender, args);
                case "delete", "remove" -> delete(sender, args);
                case "list" -> list(sender);
                case "near" -> near(sender, args);
                case "info" -> info(sender, args);
                case "tp", "teleport" -> tp(sender, args);
                case "movehere" -> movehere(sender, args);
                case "move" -> move(sender, args);
                case "addline" -> addline(sender, args);
                case "setline" -> setline(sender, args, false);
                case "insertline" -> setline(sender, args, true);
                case "removeline" -> removeline(sender, args);
                case "line" -> line(sender, args);
                case "page" -> page(sender, args);
                case "set" -> set(sender, args);
                case "action", "actions" -> action(sender, args);
                case "copy" -> copy(sender, args);
                case "edit" -> edit(sender, args);
                case "view" -> view(sender, args);
                case "reload" -> {
                    m.reload();
                    send(sender, m.store.unreadable ? "unreadable" : "reloaded", u("count", m.holograms().stream().filter(h -> !h.temporary()).count()));
                }
                default -> send(sender, "help");
            }
        } catch (NumberFormatException e) {
            send(sender, "bad-number", u("value", e.getMessage() == null ? "" : e.getMessage().replace("For input string: ", "")));
        }
        return true;
    }

    // ------------------------------------------------------------------ lookups

    /** The hologram named by args[i], told to the sender when missing or not editable. */
    private Hologram need(Sender s, String[] args, int i, boolean edit) {
        if (args.length <= i) {
            send(s, "help");
            return null;
        }
        Hologram h = m.hologram(args[i]);
        if (h == null) {
            send(s, "unknown", u("name", args[i]));
            return null;
        }
        if (edit && h.temporary()) {
            send(s, "managed", u("name", h.name()), u("owner", h.owner()));
            return null;
        }
        if (edit && m.store.unreadable) {
            // Changes couldn't be saved: refuse them rather than lose them on the next restart.
            send(s, "not-saved");
            return null;
        }
        return h;
    }

    private int pageIndex(Sender s, Hologram h, String arg) {
        int p = Integer.parseInt(arg) - 1;
        if (p < 0 || p >= h.pages.size()) {
            send(s, "no-page", u("page", arg), u("pages", h.pages.size()));
            return -1;
        }
        return p;
    }

    private int lineIndex(Sender s, HoloPage page, String arg, boolean allowEnd) {
        int l = Integer.parseInt(arg) - 1;
        if (l < 0 || l > page.lines.size() || (!allowEnd && l == page.lines.size())) {
            send(s, "no-line", u("line", arg), u("lines", page.lines.size()));
            return -1;
        }
        return l;
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    /** Checks an item, head, block or mob line before it is saved. */
    private boolean valid(Sender s, HoloLine l) {
        boolean ok = switch (l.type()) {
            case ITEM -> l.id().toLowerCase(Locale.ROOT).contains("{player}") || m.content.item(l.id(), null) != null;
            case HEAD -> l.id().toLowerCase(Locale.ROOT).contains("{player}") || m.content.head(l.id(), null) != null;
            case BLOCK -> HoloContent.block(l.id()) != null;
            case ENTITY -> m.content.entityKnown(l.id());
            default -> true;
        };
        if (!ok) send(s, "bad-content", u("content", l.content()));
        return ok;
    }

    private boolean saved(Sender s, Hologram h) {
        if (!m.changed(h)) {
            send(s, "not-saved");
            return false;
        }
        return true;
    }

    /** Where a player stands for create / movehere: eye height, or the block they stand on (top center) with --block. */
    static Loc here(ServerPlayer p, boolean block) {
        double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();
        if (block) return new Loc(p.level(), Math.floor(ex) + 0.5, Math.floor(p.getY()) + 2.0, Math.floor(ez) + 0.5);
        return new Loc(p.level(), ex, ey, ez);
    }

    // ------------------------------------------------------------------ subcommands

    private void create(Sender s, String[] args) {
        if (args.length < 2) {
            send(s, "help");
            return;
        }
        String name = args[1];
        if (!HologramsModule.validName(name)) {
            send(s, "bad-name", u("name", name));
            return;
        }
        if (m.hologram(name) != null) {
            send(s, "exists", u("name", name));
            return;
        }
        int i = 2;
        boolean block = false;
        Loc at = null;
        while (i < args.length && args[i].startsWith("--")) {
            String flag = args[i].toLowerCase(Locale.ROOT);
            if (flag.equals("--block")) {
                block = true;
                i++;
            } else if (flag.equals("--at") && i + 4 < args.length) {
                ServerLevel w = Worlds.resolve(args[i + 1]);
                if (w == null) {
                    send(s, "no-world", u("world", args[i + 1]));
                    return;
                }
                at = new Loc(w, Double.parseDouble(args[i + 2]), Double.parseDouble(args[i + 3]), Double.parseDouble(args[i + 4]));
                i += 5;
            } else break;
        }
        if (at == null) {
            if (!(s.player() instanceof ServerPlayer p)) {
                send(s, "console-create");
                return;
            }
            at = here(p, block);
        }
        String first = join(args, i);
        if (first.isEmpty()) first = text().raw("holograms.default-line").replace("<name>", name);
        HoloLine line = new HoloLine(first);
        if (!valid(s, line)) return;
        if (m.store.unreadable) {
            send(s, "not-saved");
            return;
        }
        Hologram h = m.create(name, at, List.of(first));
        if (h != null && s.player() instanceof ServerPlayer p && h.setting("billboard").equals("fixed")) {
            // A fixed hologram faces the player who made it.
            h.settings.put("yaw", String.valueOf(Math.round(((p.getYRot() + 180) % 360 + 360) % 360 - 180)));
            m.changed(h);
        }
        send(s, "created", u("name", name), u("where", where(h)));
    }

    private void delete(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null) return;
        m.delete(h);
        send(s, "deleted", u("name", h.name()));
    }

    private void list(Sender s) {
        List<Hologram> all = new ArrayList<>(m.holograms());
        all.sort(Comparator.comparing(Hologram::name));
        if (all.isEmpty()) {
            send(s, "list-empty");
            return;
        }
        send(s, "list-header", u("count", all.size()));
        for (Hologram h : all) line(s, h, -1);
    }

    private void line(Sender s, Hologram h, double dist) {
        String key = dist >= 0 ? "near-line" : h.temporary() ? "list-line-temporary" : "list-line";
        send(s, key, u("name", h.name()), u("where", where(h)), u("pages", h.pages.size()), u("lines", h.lineCount()),
                u("owner", h.owner() == null ? "" : h.owner()), u("distance", dist < 0 ? "" : String.valueOf(Math.round(dist))));
    }

    static String where(Hologram h) {
        return h == null ? "?" : h.world + " " + Math.round(h.x * 10) / 10.0 + " " + Math.round(h.y * 10) / 10.0 + " " + Math.round(h.z * 10) / 10.0;
    }

    private void near(Sender s, String[] args) {
        if (!(s.player() instanceof ServerPlayer p)) {
            text().send(s, "general.players-only");
            return;
        }
        double radius = args.length > 1 ? Double.parseDouble(args[1]) : 20;
        List<Hologram> found = new ArrayList<>();
        for (Hologram h : m.holograms()) {
            Loc l = h.location();
            if (l != null && l.level() == p.level() && distance(l, p) <= radius) found.add(h);
        }
        found.sort(Comparator.comparingDouble(h -> distance(h.location(), p)));
        if (found.isEmpty()) {
            send(s, "near-empty", u("radius", Math.round(radius)));
            return;
        }
        send(s, "near-header", u("count", found.size()), u("radius", Math.round(radius)));
        for (Hologram h : found) line(s, h, distance(h.location(), p));
    }

    private static double distance(Loc l, ServerPlayer p) {
        return Math.sqrt(l.distanceSquared(Loc.of(p)));
    }

    private void info(Sender s, String[] args) {
        Hologram h = need(s, args, 1, false);
        if (h == null) return;
        HoloRender r = m.render(h);
        send(s, "info-header", u("name", h.name()), u("where", where(h)), u("pages", h.pages.size()),
                u("viewers", r == null ? 0 : r.viewers.size()), u("entities", m.entityCount(h)));
        for (int p = 0; p < h.pages.size(); p++) {
            HoloPage page = h.pages.get(p);
            send(s, "info-page", u("page", p + 1), u("lines", page.lines.size()), u("actions", page.actions.size()));
            for (int i = 0; i < page.lines.size(); i++) {
                HoloLine l = page.lines.get(i);
                String extra = (l.height() != null ? " h=" + l.height() : "") + (l.hasOffset() ? " offset=" + l.ox + "," + l.oy + "," + l.oz : "");
                send(s, "info-line", u("line", i + 1), u("type", l.type().name().toLowerCase(Locale.ROOT)), u("content", l.content()), u("extra", extra));
            }
        }
        StringBuilder set = new StringBuilder();
        for (String k : HoloSettings.KEYS) {
            if (set.length() > 0) set.append(", ");
            set.append(k).append('=').append(h.setting(k)).append(h.settings.containsKey(k) ? "*" : "");
        }
        send(s, "info-settings", u("settings", set.toString()));
    }

    private void tp(Sender s, String[] args) {
        if (!(s.player() instanceof ServerPlayer p)) {
            text().send(s, "general.players-only");
            return;
        }
        Hologram h = need(s, args, 1, false);
        if (h == null) return;
        Loc l = h.location();
        if (l == null) {
            send(s, "no-world", u("world", h.world));
            return;
        }
        m.plugin.teleportPlayer(p, l.add(0, -1.6, 0).withRotation(p.getYRot(), p.getXRot()));
        send(s, "teleported", u("name", h.name()));
    }

    private void movehere(Sender s, String[] args) {
        if (!(s.player() instanceof ServerPlayer p)) {
            text().send(s, "general.players-only");
            return;
        }
        Hologram h = need(s, args, 1, true);
        if (h == null) return;
        boolean block = args.length > 2 && args[2].equalsIgnoreCase("--block");
        h.setLocation(here(p, block));
        if (saved(s, h)) send(s, "moved", u("name", h.name()), u("where", where(h)));
    }

    private void move(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null) return;
        if (args.length < 5) {
            send(s, "help");
            return;
        }
        Loc cur = h.location();
        double x = coord(args[2], h.x), y = coord(args[3], h.y), z = coord(args[4], h.z);
        String world = h.world;
        if (args.length > 5) {
            ServerLevel w = Worlds.resolve(args[5]);
            if (w == null) {
                send(s, "no-world", u("world", args[5]));
                return;
            }
            world = Worlds.name(w);
        } else if (cur == null) {
            send(s, "no-world", u("world", h.world));
            return;
        }
        h.world = world;
        h.x = x;
        h.y = y;
        h.z = z;
        if (saved(s, h)) send(s, "moved", u("name", h.name()), u("where", where(h)));
    }

    private static double coord(String arg, double cur) {
        if (arg.startsWith("~")) return cur + (arg.length() > 1 ? Double.parseDouble(arg.substring(1)) : 0);
        return Double.parseDouble(arg);
    }

    private void addline(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null) return;
        int page = h.pages.size() - 1;
        int from = 2;
        if (args.length > 3 && args[2].matches("\\d+")) {
            page = pageIndex(s, h, args[2]);
            if (page < 0) return;
            from = 3;
        }
        String content = join(args, from);
        if (content.isEmpty()) {
            send(s, "help");
            return;
        }
        HoloPage p = h.pages.get(page);
        if (p.lines.size() >= m.maxLines) {
            send(s, "too-many-lines", u("max", m.maxLines));
            return;
        }
        HoloLine l = new HoloLine(content);
        if (!valid(s, l)) return;
        p.lines.add(l);
        if (saved(s, h)) send(s, "line-added", u("name", h.name()), u("page", page + 1), u("line", p.lines.size()));
    }

    private void setline(Sender s, String[] args, boolean insert) {
        Hologram h = need(s, args, 1, true);
        if (h == null || args.length < 5) {
            if (h != null) send(s, "help");
            return;
        }
        int page = pageIndex(s, h, args[2]);
        if (page < 0) return;
        HoloPage p = h.pages.get(page);
        int line = lineIndex(s, p, args[3], insert);
        if (line < 0) return;
        String content = join(args, 4);
        HoloLine l = new HoloLine(content);
        if (!valid(s, l)) return;
        if (insert) {
            if (p.lines.size() >= m.maxLines) {
                send(s, "too-many-lines", u("max", m.maxLines));
                return;
            }
            p.lines.add(line, l);
        } else p.lines.get(line).setContent(content);
        if (saved(s, h)) send(s, insert ? "line-inserted" : "line-set", u("name", h.name()), u("page", page + 1), u("line", line + 1));
    }

    private void removeline(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null || args.length < 4) {
            if (h != null) send(s, "help");
            return;
        }
        int page = pageIndex(s, h, args[2]);
        if (page < 0) return;
        HoloPage p = h.pages.get(page);
        int line = lineIndex(s, p, args[3], false);
        if (line < 0) return;
        p.lines.remove(line);
        if (saved(s, h)) send(s, "line-removed", u("name", h.name()), u("page", page + 1), u("line", line + 1));
    }

    /** /holo line <name> <page> <line> height <value|default> | offset <x> <y> <z> */
    private void line(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null || args.length < 6) {
            if (h != null) send(s, "help");
            return;
        }
        int page = pageIndex(s, h, args[2]);
        if (page < 0) return;
        HoloPage p = h.pages.get(page);
        int line = lineIndex(s, p, args[3], false);
        if (line < 0) return;
        HoloLine l = p.lines.get(line);
        switch (args[4].toLowerCase(Locale.ROOT)) {
            case "height" -> {
                if (args[5].equalsIgnoreCase("default")) l.height = null;
                else l.height = Math.max(0, Math.min(10, Double.parseDouble(args[5])));
                if (saved(s, h)) send(s, "height-set", u("line", line + 1), u("height", l.height == null ? "default" : l.height));
            }
            case "offset" -> {
                if (args.length < 8) {
                    send(s, "help");
                    return;
                }
                l.ox = clampOff(Double.parseDouble(args[5]));
                l.oy = clampOff(Double.parseDouble(args[6]));
                l.oz = clampOff(Double.parseDouble(args[7]));
                if (saved(s, h)) send(s, "offset-set", u("line", line + 1), u("offset", l.ox + " " + l.oy + " " + l.oz));
            }
            default -> send(s, "help");
        }
    }

    private static double clampOff(double d) {
        return Math.max(-8, Math.min(8, d));
    }

    /** /holo page <name> add [after] | remove <page> | swap <a> <b> */
    private void page(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null || args.length < 3) {
            if (h != null) send(s, "help");
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (h.pages.size() >= m.maxPages) {
                    send(s, "too-many-pages", u("max", m.maxPages));
                    return;
                }
                // [at]: the number the new page gets (default: after the last).
                int at = h.pages.size();
                if (args.length > 3) at = Math.max(0, Math.min(h.pages.size(), Integer.parseInt(args[3]) - 1));
                HoloPage page = new HoloPage();
                String content = join(args, 4);
                page.lines.add(new HoloLine(content.isEmpty() ? text().raw("holograms.default-page").replace("<page>", String.valueOf(at + 1)) : content));
                h.pages.add(at, page);
                if (saved(s, h)) send(s, "page-added", u("name", h.name()), u("page", at + 1));
            }
            case "remove" -> {
                if (args.length < 4) {
                    send(s, "help");
                    return;
                }
                int p = pageIndex(s, h, args[3]);
                if (p < 0) return;
                if (h.pages.size() == 1) {
                    send(s, "last-page");
                    return;
                }
                h.pages.remove(p);
                if (saved(s, h)) send(s, "page-removed", u("name", h.name()), u("page", p + 1));
            }
            case "swap" -> {
                if (args.length < 5) {
                    send(s, "help");
                    return;
                }
                int a = pageIndex(s, h, args[3]);
                int b = a < 0 ? -1 : pageIndex(s, h, args[4]);
                if (a < 0 || b < 0) return;
                java.util.Collections.swap(h.pages, a, b);
                if (saved(s, h)) send(s, "pages-swapped", u("name", h.name()), u("a", a + 1), u("b", b + 1));
            }
            default -> send(s, "help");
        }
    }

    private void set(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null) return;
        if (args.length < 4) {
            send(s, "settings-list", u("settings", String.join(", ", HoloSettings.KEYS)));
            return;
        }
        String key = args[2].toLowerCase(Locale.ROOT);
        if (!HoloSettings.KEYS.contains(key)) {
            send(s, "bad-setting", u("setting", key), u("settings", String.join(", ", HoloSettings.KEYS)));
            return;
        }
        String value = join(args, 3);
        if (!value.equalsIgnoreCase("default") && HoloSettings.normalize(key, value) == null) {
            send(s, "bad-value", u("setting", key), u("value", value), u("examples", String.join(", ", HoloSettings.suggestions(key))));
            return;
        }
        if (m.store.unreadable) {
            send(s, "not-saved");
            return;
        }
        m.setSetting(h, key, value);
        send(s, "set", u("name", h.name()), u("setting", key), u("value", h.setting(key)));
    }

    /** /holo action <name> <page> add <left|right|any> <type>: <value> | remove <n> | clear | list */
    private void action(Sender s, String[] args) {
        Hologram h = need(s, args, 1, true);
        if (h == null || args.length < 4) {
            if (h != null) send(s, "help");
            return;
        }
        int page = pageIndex(s, h, args[2]);
        if (page < 0) return;
        HoloPage p = h.pages.get(page);
        switch (args[3].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                HoloPage.Action a = HoloPage.Action.parse(join(args, 4));
                if (a == null) {
                    send(s, "bad-action");
                    return;
                }
                // A console action runs as the server for whoever clicks: only for the console and trusted staff.
                if (a.type().equals("console") && !mayAddConsoleAction(s)) {
                    send(s, "console-action-denied");
                    return;
                }
                p.actions.add(a);
                if (saved(s, h)) send(s, "action-added", u("page", page + 1), u("action", a.toString()));
            }
            case "remove" -> {
                int i = args.length > 4 ? Integer.parseInt(args[4]) - 1 : -1;
                if (i < 0 || i >= p.actions.size()) {
                    send(s, "no-action", u("count", p.actions.size()));
                    return;
                }
                HoloPage.Action a = p.actions.remove(i);
                if (saved(s, h)) send(s, "action-removed", u("page", page + 1), u("action", a.toString()));
            }
            case "clear" -> {
                p.actions.clear();
                if (saved(s, h)) send(s, "actions-cleared", u("page", page + 1));
            }
            default -> {
                if (p.actions.isEmpty()) send(s, "actions-empty", u("page", page + 1));
                for (int i = 0; i < p.actions.size(); i++) send(s, "action-line", u("index", i + 1), u("action", p.actions.get(i).toString()));
            }
        }
    }

    private void copy(Sender s, String[] args) {
        Hologram from = need(s, args, 1, false);
        if (from == null) return;
        if (args.length < 3 || !HologramsModule.validName(args[2])) {
            send(s, "bad-name", u("name", args.length < 3 ? "" : args[2]));
            return;
        }
        if (m.hologram(args[2]) != null) {
            send(s, "exists", u("name", args[2]));
            return;
        }
        Loc at = s.player() instanceof ServerPlayer p ? here(p, args.length > 3 && args[3].equalsIgnoreCase("--block")) : from.location();
        if (at == null) {
            send(s, "no-world", u("world", from.world));
            return;
        }
        Hologram h = m.create(args[2], at, List.of());
        h.pages.clear();
        for (HoloPage p : from.pages) h.pages.add(p.copy());
        h.settings.putAll(from.settings);
        if (saved(s, h)) send(s, "copied", u("name", from.name()), u("copy", h.name()), u("where", where(h)));
    }

    private void edit(Sender s, String[] args) {
        if (!(s.player() instanceof ServerPlayer p)) {
            text().send(s, "general.players-only");
            return;
        }
        Hologram h = need(s, args, 1, true);
        if (h != null) m.editor.open(p, h, 0);
    }

    /** /holo view <name> [player]: the page and the text a player sees (placeholders filled in for them). */
    private void view(Sender s, String[] args) {
        Hologram h = need(s, args, 1, false);
        if (h == null) return;
        ServerPlayer who = args.length > 2 ? m.plugin.findPlayer(s, args[2]) : s.player();
        if (args.length > 2 && who == null) {
            m.plugin.notFound(s, args[2]);
            return;
        }
        List<String> seen = m.preview(h, who);
        send(s, "view-header", u("name", h.name()), u("player", who == null ? "-" : who.getGameProfile().name()), u("page", seen.get(0)), u("pages", h.pages.size()));
        for (int i = 1; i < seen.size(); i++) send(s, "view-line", u("line", i), u("text", seen.get(i)));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(Sender sender, String alias, String[] args) {
        if (!sender.hasPermission(HologramsModule.ADMIN)) return List.of();
        List<String> out = new ArrayList<>();
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        if (args.length == 1) out.addAll(SUBS);
        else if (args.length == 2 && !sub.equals("create") && !sub.equals("list") && !sub.equals("near") && !sub.equals("reload") && !sub.equals("help")) {
            boolean editing = !List.of("info", "tp", "teleport", "view", "copy").contains(sub);
            for (Hologram h : m.holograms()) if (!editing || !h.temporary()) out.add(h.name());
        } else if (args.length == 2 && sub.equals("near")) out.addAll(List.of("10", "20", "50"));
        else {
            Hologram h = m.hologram(args[1]);
            int pages = h == null ? 1 : h.pages.size();
            switch (sub) {
                case "create" -> {
                    if (last.startsWith("-")) out.addAll(List.of("--block", "--at"));
                    else out.addAll(contentCompletions(sender, args, 2));
                }
                case "addline" -> {
                    if (args.length == 3) for (int i = 1; i <= pages; i++) out.add(String.valueOf(i));
                    out.addAll(contentCompletions(sender, args, args.length > 3 && args[2].matches("\\d+") ? 3 : 2));
                }
                case "setline", "insertline", "removeline", "line" -> {
                    if (args.length == 3) for (int i = 1; i <= pages; i++) out.add(String.valueOf(i));
                    else if (args.length == 4 && h != null) {
                        int pi = parse(args[2]) - 1;
                        int n = pi >= 0 && pi < h.pages.size() ? h.pages.get(pi).lines.size() : 0;
                        for (int i = 1; i <= n + (sub.equals("insertline") ? 1 : 0); i++) out.add(String.valueOf(i));
                    } else if (sub.equals("line") && args.length == 5) out.addAll(List.of("height", "offset"));
                    else if (sub.equals("line") && args.length == 6 && args[4].equalsIgnoreCase("height")) out.addAll(List.of("default", "0.3", "0.6"));
                    else if (sub.equals("line") && args.length >= 6) out.add("0");
                    else if (!sub.equals("removeline") && !sub.equals("line")) {
                        if (args.length == 5 && h != null) {
                            int pi = parse(args[2]) - 1, li = parse(args[3]) - 1;
                            if (sub.equals("setline") && pi >= 0 && pi < h.pages.size() && li >= 0 && li < h.pages.get(pi).lines.size()) {
                                out.add(h.pages.get(pi).lines.get(li).content());
                            }
                        }
                        out.addAll(contentCompletions(sender, args, 4));
                    }
                }
                case "page" -> {
                    if (args.length == 3) out.addAll(List.of("add", "remove", "swap"));
                    else if (args.length <= 5) for (int i = 1; i <= pages + (args[2].equalsIgnoreCase("add") ? 1 : 0); i++) out.add(String.valueOf(i));
                }
                case "set" -> {
                    if (args.length == 3) out.addAll(HoloSettings.KEYS);
                    else if (args.length == 4) {
                        out.addAll(HoloSettings.suggestions(args[2].toLowerCase(Locale.ROOT)));
                        out.add("default");
                    }
                }
                case "action", "actions" -> {
                    if (args.length == 3) for (int i = 1; i <= pages; i++) out.add(String.valueOf(i));
                    else if (args.length == 4) out.addAll(List.of("add", "remove", "clear", "list"));
                    else if (args.length == 5 && args[3].equalsIgnoreCase("add")) out.addAll(HoloPage.Action.CLICKS);
                    else if (args.length == 6 && args[3].equalsIgnoreCase("add")) for (String t : HoloPage.Action.TYPES) out.add(t + ":");
                    else if (args.length == 7 && args[5].equalsIgnoreCase("menu:")) out.addAll(List.of("hub", "homes", "warps", "pets", "quests"));
                    else if (args.length == 7 && args[5].equalsIgnoreCase("page:")) out.addAll(List.of("next", "prev", "1"));
                }
                case "move" -> {
                    if (args.length <= 5) out.add("~");
                    else if (args.length == 6) for (ServerLevel w : net.axther.aftrhrs.core.Feather.server().getAllLevels()) out.add(Worlds.name(w));
                }
                case "movehere", "copy" -> {
                    if (args.length == (sub.equals("copy") ? 4 : 3)) out.add("--block");
                }
                case "view" -> {
                    if (args.length == 3) return m.plugin.completePlayers(sender, last);
                }
                default -> {
                }
            }
        }
        String low = last.toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String o : out) if (o.toLowerCase(Locale.ROOT).startsWith(low) && !filtered.contains(o)) filtered.add(o);
        return filtered.size() > 60 ? filtered.subList(0, 60) : filtered;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Completions for a content argument: the #TYPE: prefixes, then materials, players, mobs or Oraxen ids. */
    private List<String> contentCompletions(Sender sender, String[] args, int from) {
        List<String> out = new ArrayList<>();
        int at = args.length - 1;
        if (at < from) return out;
        String first = args[from].toUpperCase(Locale.ROOT);
        String last = args[at].toLowerCase(Locale.ROOT);
        if (at == from) {
            out.addAll(List.of("#ITEM:", "#HEAD:", "#BLOCK:", "#ENTITY:", "#BLANK"));
            return out;
        }
        if (at != from + 1) {
            if (first.startsWith("#ITEM:") || first.startsWith("#HEAD:") || first.startsWith("#BLOCK:")) out.addAll(List.of("spin", "still", "scale=1.5"));
            return out;
        }
        Collection<String> oraxen = last.startsWith("oraxen:") ? net.axther.aftrhrs.content.ArcanaContent.entries().keySet() : List.of();
        switch (first) {
            case "#ITEM:" -> {
                for (Item it : BuiltInRegistries.ITEM) if (it != Items.AIR) out.add(shortId(BuiltInRegistries.ITEM.getKey(it)));
                out.add("oraxen:");
                for (String id : oraxen) out.add("oraxen:" + id);
            }
            case "#BLOCK:" -> {
                for (Block b : BuiltInRegistries.BLOCK) if (b != Blocks.AIR && b != Blocks.CAVE_AIR && b != Blocks.VOID_AIR) out.add(shortId(BuiltInRegistries.BLOCK.getKey(b)));
            }
            case "#HEAD:" -> {
                out.add("{player}");
                out.addAll(m.plugin.completePlayers(sender, args[at]));
                for (String id : oraxen) out.add("oraxen:" + id);
            }
            case "#ENTITY:" -> {
                for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
                    String id = shortId(BuiltInRegistries.ENTITY_TYPE.getKey(t));
                    if (id.startsWith(last.replace("minecraft:", "")) && HoloRender.entityType(id) != null) out.add(id);
                }
                if (last.startsWith("m")) for (String id : ArcanaEntities.mythicIds().keySet()) out.add("mythic:" + id);
            }
            default -> {
            }
        }
        return out;
    }

    /** {@code minecraft:stone} as {@code stone}; other namespaces stay. */
    private static String shortId(Identifier id) {
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    /**
     * Only the console (or RCON, or a command block), or someone with feather.holograms.console (for /execute as: the
     * player it runs as).
     */
    private static boolean mayAddConsoleAction(Sender s) {
        if (s.player() == null && s.source().getEntity() == null) return true;
        return s.hasPermission(HologramsModule.CONSOLE);
    }
}
