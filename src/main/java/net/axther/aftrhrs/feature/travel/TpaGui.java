package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The "who do you want to visit?" chest: one head per online player. Left-click asks to go to them, right-click asks
 * them to come to you; players already waiting on you are listed first and shift-click accepts them.
 */
final class TpaGui {
    private static final int PER_PAGE = 45;

    private final Feather plugin;
    private final TpaModule tpa;

    TpaGui(Feather plugin, TpaModule tpa) {
        this.plugin = plugin;
        this.tpa = tpa;
    }

    void open(ServerPlayer viewer, int page) {
        Text text = plugin.text();
        Set<UUID> waiting = tpa.pendingSenders(viewer);
        List<ServerPlayer> others = new ArrayList<>();
        for (ServerPlayer p : plugin.onlinePlayers()) if (p != viewer && plugin.canSee(viewer, p)) others.add(p);
        others.sort((a, b) -> {
            boolean wa = waiting.contains(a.getUUID()), wb = waiting.contains(b.getUUID());
            if (wa != wb) return wa ? -1 : 1;
            return Names.name(a).compareToIgnoreCase(Names.name(b));
        });
        int pages = Math.max(1, (others.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));

        Gui gui = Gui.create(6, text.msg("gui.tpa.title",
                Text.unparsed("page", current + 1), Text.unparsed("pages", pages), Text.unparsed("online", others.size())));

        boolean canHere = Perms.has(viewer, "feather.tpahere");
        int start = current * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < others.size(); i++) {
            ServerPlayer target = others.get(start + i);
            UUID id = target.getUUID();
            boolean isWaiting = waiting.contains(id);
            List<Component> lore = new ArrayList<>();
            if (!Names.plain(target).equals(Names.name(target))) {
                lore.addAll(text.lines("gui.tpa.head-realname", Text.unparsed("name", Names.name(target))));
            }
            lore.addAll(text.lines("gui.tpa.head-world", Text.unparsed("world", Travel.prettyWorld(Worlds.name(target.level())))));
            if (isWaiting) lore.addAll(text.lines("gui.tpa.head-waiting"));
            lore.addAll(text.lines(canHere ? "gui.tpa.head-actions" : "gui.tpa.head-actions-no-here"));
            if (plugin.profiles().get(target).tpToggledOff) lore.addAll(text.lines("gui.tpa.head-toggled"));
            ItemStack head = Icons.icon(Icons.head(target))
                    .name(text.itemMsg("gui.tpa.head-name", Text.component("name", Names.display(target))))
                    .lore(lore).glint(isWaiting).build();
            String name = Names.name(target);
            gui.set(i, head, click -> {
                text.sound(viewer, "click");
                ServerPlayer t = plugin.player(id);
                viewer.closeContainer();
                if (t == null) {
                    text.send(viewer, "tpa.sender-offline");
                    text.sound(viewer, "error");
                    return;
                }
                if (click.isShiftClick() && tpa.pendingSenders(viewer).contains(id)) {
                    tpa.respond(viewer, name, true);
                } else if (click.isRightClick() && canHere) {
                    tpa.send(viewer, t, true);
                } else {
                    tpa.send(viewer, t, false);
                }
            });
        }
        if (others.isEmpty()) {
            gui.set(22, Icons.icon(Items.STAINED_GLASS_PANE.lightGray(), "gui.tpa.nobody", "gui.tpa.nobody-lore"));
        }

        // Bottom bar
        Gui.navBar(gui, text, current > 0, c -> open(viewer, current - 1), current < pages - 1, c -> open(viewer, current + 1),
                Icons.icon(Items.FEATHER, "gui.nav.hub", "gui.nav.hub-lore"), c -> {
                    viewer.closeContainer();
                    plugin.openHub(viewer);
                });
        if (!waiting.isEmpty()) {
            ItemStack bell = Icons.icon(Items.BELL, "gui.tpa.requests", "gui.tpa.requests-lore", Text.unparsed("count", waiting.size()));
            bell.setCount(Math.min(64, waiting.size()));
            gui.set(48, bell, c -> {
                text.sound(viewer, "click");
                viewer.closeContainer();
                tpa.openMenu(viewer);
            });
        }
        if (Perms.has(viewer, "feather.tptoggle")) {
            boolean off = plugin.profiles().get(viewer).tpToggledOff;
            gui.set(50, Icons.icon(off ? Items.DYE.gray() : Items.DYE.lime(),
                    off ? "gui.tpa.toggle-off" : "gui.tpa.toggle-on", "gui.tpa.toggle-lore"), c -> {
                text.sound(viewer, "click");
                Travel.perform(viewer, "tptoggle");
                open(viewer, current);
            });
        }
        gui.open(viewer);
        text.sound(viewer, "menu-open");
    }
}
