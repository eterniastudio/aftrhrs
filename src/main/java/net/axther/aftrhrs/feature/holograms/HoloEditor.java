package net.axther.aftrhrs.feature.holograms;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * /holo edit &lt;name&gt;: the hologram editor dialog. One page at a time: each line is a button that opens its own
 * screen (edit the text, height and offset, move it up or down, delete it); below them Add Line, the page arrows,
 * Add Page / Remove Page, Settings, Move Here and Delete. Every change is saved and shown at once.
 */
final class HoloEditor {
    private static final int WIDE = 300;
    private static final int HALF = 150;

    private final HologramsModule m;

    HoloEditor(HologramsModule m) {
        this.m = m;
    }

    private Text text() {
        return m.text();
    }

    private Component msg(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        return text().msg("holograms.editor." + key, r);
    }

    /** The hologram again by name (it may have been deleted or replaced meanwhile), or null with a note. */
    private Hologram again(ServerPlayer p, String name) {
        if (!Perms.has(p, HologramsModule.ADMIN)) {
            text().send(p, "general.no-permission");
            return null;
        }
        Hologram h = m.hologram(name);
        if (h == null || h.temporary()) {
            text().send(p, "holograms.unknown", Text.unparsed("name", name));
            return null;
        }
        if (m.store.unreadable) {
            text().send(p, "holograms.not-saved");
            return null;
        }
        return h;
    }

    /** A button that runs on the hologram (looked up again) and then reopens whatever {@code then} opens. */
    private BiConsumer<ServerPlayer, Dialogs.Response> on(String name, Action action) {
        return (p, view) -> {
            Hologram h = again(p, name);
            if (h != null) action.run(p, h, view);
        };
    }

    private interface Action {
        void run(ServerPlayer p, Hologram h, Dialogs.Response view);
    }

    private boolean commit(ServerPlayer p, Hologram h) {
        if (m.changed(h)) return true;
        text().send(p, "holograms.not-saved");
        return false;
    }

    private String preview(HoloLine l, int max) {
        String s = l.type() == HoloLine.Type.TEXT ? m.content.plain(l.content()) : l.content();
        if (s.isBlank()) s = l.content();
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // ------------------------------------------------------------------ the main screen

    void open(ServerPlayer p, Hologram h, int pageIndex) {
        int page = Math.max(0, Math.min(h.pages.size() - 1, pageIndex));
        HoloPage pg = h.pages.get(page);
        String name = h.name();
        Dialogs.Menu menu = m.dialogs.menu(msg("title", Text.unparsed("name", name)));
        menu.text(msg("body", Text.unparsed("where", HoloCommand.where(h)), Text.unparsed("page", page + 1),
                Text.unparsed("pages", h.pages.size()), Text.unparsed("lines", pg.lines.size()), Text.unparsed("actions", pg.actions.size())), WIDE);
        for (int i = 0; i < pg.lines.size(); i++) {
            HoloLine l = pg.lines.get(i);
            int index = i;
            menu.button(msg("line", Text.unparsed("index", i + 1), Text.unparsed("preview", preview(l, 24))),
                    msg("line-tooltip", Text.unparsed("type", l.type().name().toLowerCase(Locale.ROOT)), Text.unparsed("content", l.content())),
                    HALF, on(name, (pl, hh, v) -> openLine(pl, hh, page, index)));
        }
        menu.button(msg("add-line"), msg("add-line-tooltip"), HALF, on(name, (pl, hh, v) -> openAdd(pl, hh, page)));
        if (h.pages.size() > 1) {
            menu.button(msg("prev-page"), null, HALF, on(name, (pl, hh, v) -> open(pl, hh, (page - 1 + hh.pages.size()) % hh.pages.size())));
            menu.button(msg("next-page"), null, HALF, on(name, (pl, hh, v) -> open(pl, hh, (page + 1) % hh.pages.size())));
        }
        menu.button(msg("add-page"), msg("add-page-tooltip"), HALF, on(name, (pl, hh, v) -> {
            if (hh.pages.size() >= m.maxPages) {
                text().send(pl, "holograms.too-many-pages", Text.unparsed("max", m.maxPages));
                open(pl, hh, page);
                return;
            }
            HoloPage np = new HoloPage();
            int at = Math.min(page + 1, hh.pages.size());
            np.lines.add(new HoloLine(text().raw("holograms.default-page").replace("<page>", String.valueOf(at + 1))));
            hh.pages.add(at, np);
            commit(pl, hh);
            open(pl, hh, at);
        }));
        if (h.pages.size() > 1) {
            menu.button(msg("remove-page"), msg("remove-page-tooltip"), HALF, on(name, (pl, hh, v) -> {
                if (hh.pages.size() > 1 && page < hh.pages.size()) {
                    hh.pages.remove(page);
                    commit(pl, hh);
                }
                open(pl, hh, page - 1);
            }));
        }
        menu.button(msg("settings"), msg("settings-tooltip"), HALF, on(name, (pl, hh, v) -> openSettings(pl, hh, page)));
        menu.button(msg("move-here"), msg("move-here-tooltip"), HALF, on(name, (pl, hh, v) -> {
            hh.setLocation(HoloCommand.here(pl, false));
            if (commit(pl, hh)) text().send(pl, "holograms.moved", Text.unparsed("name", hh.name()), Text.unparsed("where", HoloCommand.where(hh)));
            open(pl, hh, page);
        }));
        menu.button(msg("delete"), msg("delete-tooltip"), HALF, on(name, (pl, hh, v) -> openDelete(pl, hh, page)));
        menu.columns(2);
        menu.exit(text().msg("menu.close"), null);
        menu.open(p);
    }

    // ------------------------------------------------------------------ one line

    private void openLine(ServerPlayer p, Hologram h, int page, int index) {
        if (page >= h.pages.size() || index >= h.pages.get(page).lines.size()) {
            open(p, h, page);
            return;
        }
        HoloLine l = h.pages.get(page).lines.get(index);
        String name = h.name();
        Dialogs.Menu menu = m.dialogs.menu(msg("line-title", Text.unparsed("index", index + 1), Text.unparsed("page", page + 1)));
        menu.text(msg("line-body"), WIDE);
        menu.input(Dialogs.Input.text("content", msg("content-label")).initial(l.content()).maxLength(512).width(WIDE).build());
        menu.input(Dialogs.Input.text("height", msg("height-label")).initial(l.height() == null ? "" : String.valueOf(l.height())).maxLength(8).width(HALF).build());
        menu.input(Dialogs.Input.text("offset", msg("offset-label")).initial(l.hasOffset() ? l.ox + " " + l.oy + " " + l.oz : "").maxLength(40).width(HALF).build());
        menu.button(msg("save"), null, HALF, on(name, (pl, hh, v) -> {
            HoloLine cur = line(hh, page, index);
            if (cur != null && apply(pl, cur, v)) commit(pl, hh);
            open(pl, hh, page);
        }));
        menu.button(msg("remove-line"), null, HALF, on(name, (pl, hh, v) -> {
            if (line(hh, page, index) != null) {
                hh.pages.get(page).lines.remove(index);
                commit(pl, hh);
            }
            open(pl, hh, page);
        }));
        menu.button(msg("move-up"), null, HALF, on(name, (pl, hh, v) -> {
            if (line(hh, page, index) != null && index > 0) {
                Collections.swap(hh.pages.get(page).lines, index, index - 1);
                commit(pl, hh);
                openLine(pl, hh, page, index - 1);
            } else open(pl, hh, page);
        }));
        menu.button(msg("move-down"), null, HALF, on(name, (pl, hh, v) -> {
            if (line(hh, page, index) != null && index < hh.pages.get(page).lines.size() - 1) {
                Collections.swap(hh.pages.get(page).lines, index, index + 1);
                commit(pl, hh);
                openLine(pl, hh, page, index + 1);
            } else open(pl, hh, page);
        }));
        menu.columns(2);
        menu.exit(text().msg("menu.back"), pl -> {
            Hologram hh = again(pl, name);
            if (hh != null) open(pl, hh, page);
        });
        menu.open(p);
    }

    private static HoloLine line(Hologram h, int page, int index) {
        if (page >= h.pages.size() || index >= h.pages.get(page).lines.size()) return null;
        return h.pages.get(page).lines.get(index);
    }

    /** Copies the line screen's inputs into the line; false (with a note) when something isn't valid. */
    private boolean apply(ServerPlayer p, HoloLine l, Dialogs.Response v) {
        String content = v == null ? null : v.getText("content");
        String height = v == null ? null : v.getText("height");
        String offset = v == null ? null : v.getText("offset");
        if (content != null && !content.isBlank()) {
            HoloLine test = new HoloLine(content);
            if (!valid(p, test)) return false;
            l.setContent(content);
        }
        if (height != null) {
            String t = height.strip();
            if (t.isEmpty() || t.equalsIgnoreCase("default")) l.height = null;
            else {
                try {
                    l.height = Math.max(0, Math.min(10, Double.parseDouble(t)));
                } catch (NumberFormatException e) {
                    text().send(p, "holograms.bad-number", Text.unparsed("value", t));
                    return false;
                }
            }
        }
        if (offset != null) {
            String t = offset.strip();
            if (t.isEmpty()) {
                l.ox = l.oy = l.oz = 0;
            } else {
                String[] parts = t.split("[\\s,]+");
                try {
                    if (parts.length != 3) throw new NumberFormatException(t);
                    l.ox = Math.max(-8, Math.min(8, Double.parseDouble(parts[0])));
                    l.oy = Math.max(-8, Math.min(8, Double.parseDouble(parts[1])));
                    l.oz = Math.max(-8, Math.min(8, Double.parseDouble(parts[2])));
                } catch (NumberFormatException e) {
                    text().send(p, "holograms.bad-number", Text.unparsed("value", t));
                    return false;
                }
            }
        }
        return true;
    }

    private boolean valid(ServerPlayer p, HoloLine l) {
        boolean ok = switch (l.type()) {
            case ITEM -> l.id().toLowerCase(Locale.ROOT).contains("{player}") || m.content.item(l.id(), null) != null;
            case HEAD -> l.id().toLowerCase(Locale.ROOT).contains("{player}") || m.content.head(l.id(), null) != null;
            case BLOCK -> HoloContent.block(l.id()) != null;
            case ENTITY -> m.content.entityKnown(l.id());
            default -> true;
        };
        if (!ok) text().send(p, "holograms.bad-content", Text.unparsed("content", l.content()));
        return ok;
    }

    // ------------------------------------------------------------------ adding a line

    private void openAdd(ServerPlayer p, Hologram h, int page) {
        String name = h.name();
        Dialogs.Menu menu = m.dialogs.menu(msg("add-title", Text.unparsed("page", page + 1)));
        menu.text(msg("add-body"), WIDE);
        menu.input(Dialogs.Input.text("content", msg("content-label")).initial("").maxLength(512).width(WIDE).build());
        menu.button(msg("add"), null, HALF, on(name, (pl, hh, v) -> {
            String content = v == null ? null : v.getText("content");
            if (content != null && !content.isBlank() && page < hh.pages.size()) {
                HoloPage pg = hh.pages.get(page);
                HoloLine l = new HoloLine(content);
                if (pg.lines.size() >= m.maxLines) text().send(pl, "holograms.too-many-lines", Text.unparsed("max", m.maxLines));
                else if (valid(pl, l)) {
                    pg.lines.add(l);
                    commit(pl, hh);
                }
            }
            open(pl, hh, page);
        }));
        menu.columns(1);
        menu.exit(text().msg("menu.back"), pl -> {
            Hologram hh = again(pl, name);
            if (hh != null) open(pl, hh, page);
        });
        menu.open(p);
    }

    // ------------------------------------------------------------------ settings

    private void openSettings(ServerPlayer p, Hologram h, int page) {
        String name = h.name();
        Dialogs.Menu menu = m.dialogs.menu(msg("settings-title", Text.unparsed("name", name)));
        menu.text(msg("settings-body"), WIDE);
        for (String key : HoloSettings.KEYS) {
            menu.input(Dialogs.Input.text(key.replace('-', '_'), Component.text(key)).initial(h.setting(key)).maxLength(48).width(HALF + 50).build());
        }
        menu.button(msg("save"), null, HALF, on(name, (pl, hh, v) -> {
            boolean changed = false;
            for (String key : HoloSettings.KEYS) {
                String raw = v == null ? null : v.getText(key.replace('-', '_'));
                if (raw == null || raw.strip().equals(hh.setting(key))) continue;
                String val = raw.strip();
                if (val.isEmpty() || val.equalsIgnoreCase("default")) {
                    changed |= hh.settings.remove(key) != null;
                    continue;
                }
                String norm = HoloSettings.normalize(key, val);
                if (norm == null) {
                    text().send(pl, "holograms.bad-value", Text.unparsed("setting", key), Text.unparsed("value", val),
                            Text.unparsed("examples", String.join(", ", HoloSettings.suggestions(key))));
                    continue;
                }
                hh.settings.put(key, norm);
                changed = true;
            }
            if (changed) commit(pl, hh);
            open(pl, hh, page);
        }));
        menu.columns(1);
        menu.exit(text().msg("menu.back"), pl -> {
            Hologram hh = again(pl, name);
            if (hh != null) open(pl, hh, page);
        });
        menu.open(p);
    }

    // ------------------------------------------------------------------ delete

    private void openDelete(ServerPlayer p, Hologram h, int page) {
        String name = h.name();
        Dialogs.Menu menu = m.dialogs.menu(msg("delete-title", Text.unparsed("name", name)));
        menu.text(msg("delete-body", Text.unparsed("name", name)), WIDE);
        menu.button(msg("delete-confirm"), null, HALF, on(name, (pl, hh, v) -> {
            m.delete(hh);
            text().send(pl, "holograms.deleted", Text.unparsed("name", hh.name()));
        }));
        menu.columns(1);
        menu.exit(text().msg("menu.back"), pl -> {
            Hologram hh = again(pl, name);
            if (hh != null) open(pl, hh, page);
        });
        menu.open(p);
    }
}
