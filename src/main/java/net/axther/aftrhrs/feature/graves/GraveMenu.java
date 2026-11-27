package net.axther.aftrhrs.feature.graves;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The chest that shows a grave's items. It is only a picture of the grave: every slot is locked ({@link Gui}) and
 * clicks are handled by {@link GraveModule} (taking one stack, Take All), which changes the grave and redraws every
 * open view of it, so shift-clicks, number keys, drags, double-click collecting and two people looting at once can't
 * copy anything.
 */
final class GraveMenu {
    static final int PER_PAGE = 45;
    static final int PREV = Gui.NAV_PREV, INFO = 47, TAKE_ALL = 48, CLOSE = Gui.NAV_CLOSE, NEXT = Gui.NAV_NEXT;

    /** One open view: which grave, which page, and the entry shown in each item slot when it was drawn. */
    static final class View {
        final Grave grave;
        final boolean admin;
        final Gui gui;
        int page;
        final Grave.Entry[] shown = new Grave.Entry[PER_PAGE];

        View(Grave grave, boolean admin, Gui gui) {
            this.grave = grave;
            this.admin = admin;
            this.gui = gui;
        }
    }

    private final Feather plugin;
    private final GraveModule graves;
    private final List<View> open = new ArrayList<>();

    GraveMenu(Feather plugin, GraveModule graves) {
        this.plugin = plugin;
        this.graves = graves;
    }

    void open(ServerPlayer p, Grave g, boolean admin) {
        Gui gui = Gui.create(6, plugin.text().msg("grave.gui.title", Text.unparsed("name", g.ownerName)));
        View v = new View(g, admin, gui);
        gui.attach(v);
        gui.onBottomClick(c -> {
            // Nothing moves by itself, in either inventory, while a grave is open.
        });
        gui.onClose(pl -> {
            if (gui.viewers().isEmpty()) open.remove(v);
        });
        open.add(v);
        draw(v);
        gui.open(p);
        plugin.text().sound(p, "menu-open");
    }

    /** Every open view of this grave. */
    List<View> views(Grave g) {
        List<View> out = new ArrayList<>();
        for (View v : open) if (v.grave == g && !v.gui.viewers().isEmpty()) out.add(v);
        return out;
    }

    void redrawAll(Grave g) {
        for (View v : views(g)) draw(v);
    }

    void closeAll(Grave g) {
        for (View v : views(g)) v.gui.close();
        open.removeIf(v -> v.grave == g);
    }

    void closeEverything() {
        for (View v : new ArrayList<>(open)) v.gui.close();
        open.clear();
    }

    void draw(View v) {
        Grave g = v.grave;
        List<Grave.Entry> live = new ArrayList<>();
        for (Grave.Entry e : g.items) if (e.item() != null && !e.item().isEmpty()) live.add(e);
        int pages = Math.max(1, (live.size() + PER_PAGE - 1) / PER_PAGE);
        v.page = Math.max(0, Math.min(v.page, pages - 1));
        Gui gui = v.gui;
        gui.clear();
        Arrays.fill(v.shown, null);
        int start = v.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < live.size(); i++) {
            Grave.Entry e = live.get(start + i);
            v.shown[i] = e;
            gui.set(i, e.item().copy(), c -> graves.takeOne(c.player(), v, e));
        }
        Gui.navBar(gui, plugin.text(), v.page > 0, c -> {
            v.page--;
            draw(v);
        }, v.page < pages - 1, c -> {
            v.page++;
            draw(v);
        }, null, null);
        gui.set(INFO, info(g));
        gui.set(TAKE_ALL, icon(Items.CHEST, "grave.gui.take-all", "grave.gui.take-all-lore",
                Text.unparsed("count", live.size()), Text.unparsed("xp", g.xp)), c -> graves.takeAll(c.player(), v));
    }

    /** Refreshes only the status icon (the clock) of every open view. */
    void tickInfo(Grave g) {
        for (View v : views(g)) v.gui.set(INFO, info(g));
    }

    private ItemStack info(Grave g) {
        long now = System.currentTimeMillis();
        boolean locked = g.locked(now);
        return icon(Items.CLOCK, "grave.gui.info", locked ? "grave.gui.info-locked" : "grave.gui.info-open",
                Text.unparsed("name", g.ownerName), Text.unparsed("time", Placeholders.duration(Math.max(0, (locked ? g.unlockAt : g.expireAt) - now))),
                Text.unparsed("xp", g.xp), Text.unparsed("coords", g.coords()));
    }

    private static ItemStack icon(Item item, String nameKey, String loreKey, TagResolver... r) {
        return Icons.icon(item, nameKey, loreKey, r);
    }
}
