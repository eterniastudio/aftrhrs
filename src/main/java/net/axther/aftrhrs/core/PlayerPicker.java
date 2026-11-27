package net.axther.aftrhrs.core;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A chest of player heads for choosing people, styled like the /tpa picker. Every online player the viewer can see is
 * listed, plus anyone already chosen (even offline). Chosen players glow and are listed first; clicking a head
 * toggles it. Used by chest locks (auto-unlock list) and graves (who may loot).
 */
public final class PlayerPicker {
    private static final int PER_PAGE = 45;
    private final Feather plugin;

    PlayerPicker(Feather plugin) {
        this.plugin = plugin;
    }

    /** What a picker chooses for. {@code chosen} is read fresh on every redraw; {@code back} may be null. */
    public record Request(Component title, Supplier<Set<UUID>> chosen, Consumer<UUID> toggle, Set<UUID> exclude, Runnable back) {
    }

    public void open(ServerPlayer viewer, Request request, int page) {
        Text text = plugin.text();
        Set<UUID> chosen = request.chosen().get();
        LinkedHashSet<UUID> people = new LinkedHashSet<>(chosen);
        List<ServerPlayer> online = new ArrayList<>(plugin.onlinePlayers());
        online.sort((a, b) -> Names.name(a).compareToIgnoreCase(Names.name(b)));
        for (ServerPlayer p : online) if (plugin.canSee(viewer, p)) people.add(p.getUUID());
        people.removeAll(request.exclude());
        people.remove(viewer.getUUID());
        List<UUID> list = new ArrayList<>(people);
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));

        Gui gui = Gui.create(6, request.title());
        int start = current * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < list.size(); i++) {
            UUID id = list.get(start + i);
            boolean on = chosen.contains(id);
            ServerPlayer onlineP = plugin.player(id);
            Component name;
            if (onlineP != null) name = Names.display(onlineP);
            else {
                OfflinePlayer off = plugin.lookup().byId(id);
                name = Component.text(off == null || off.name() == null ? "?" : off.name());
            }
            List<Component> lore = new ArrayList<>();
            if (onlineP == null) lore.addAll(text.lines("gui.picker.offline"));
            lore.addAll(text.lines(on ? "gui.picker.chosen" : "gui.picker.not-chosen"));
            ItemStack head = Icons.icon(Icons.head(id, onlineP)).name(text.itemMsg("gui.picker.head-name", Text.component("name", name)))
                    .lore(lore).glint(on).build();
            gui.set(i, head, c -> {
                text.sound(viewer, "click");
                request.toggle().accept(id);
                open(viewer, request, current);
            });
        }
        if (list.isEmpty()) gui.set(22, Icons.icon(Items.STAINED_GLASS_PANE.lightGray(), "gui.picker.nobody", "gui.picker.nobody-lore"));
        Gui.navBar(gui, text, current > 0, c -> open(viewer, request, current - 1), current < pages - 1, c -> open(viewer, request, current + 1),
                request.back() == null ? null : Icons.icon(Items.FEATHER, "gui.nav.back", null), c -> {
                    viewer.closeContainer();
                    request.back().run();
                });
        gui.set(48, Icons.icon(Items.DYE.lime(), "gui.picker.count", null, Text.unparsed("count", chosen.size())));
        gui.open(viewer);
    }
}
