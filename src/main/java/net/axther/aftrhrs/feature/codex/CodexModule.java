package net.axther.aftrhrs.feature.codex;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The creature codex's front door ({@code modules.taming-guide}, the old taming guide's module): {@code /codex} and the
 * taming guide's old names ({@code /tamingguide}, {@code /taming}, {@code /arcanaguide}, which the Arcana Taming Guide
 * item runs), and the hub's Codex button. The codex itself (every creature, discovery, taming, the dialogs and the mod's
 * screen) is kept by the pets module and opened through {@link Hooks.Codex}; the taming guide's menu is merged into it.
 *
 * <p>{@code /codex <category|creature>} opens on a tab ("bosses", "wildlife") or a creature's page ("emberkit",
 * "Sun Wukong"). {@code /codex book} still opens the old written book ({@code pages} in guide.yml);
 * {@code /codex reload} (feather.admin) re-reads guide.yml and codex.yml.
 */
public final class CodexModule implements FeatherModule, FeatherCommand {
    private final Feather plugin;
    private ItemStack book = ItemStack.EMPTY;
    private int bookPages;

    public CodexModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Codex";
    }

    @Override
    public void enable() {
        reload();
        plugin.bind("codex", this);
        plugin.hub().add("codex", Hub.Group.MAIN, Hub.ORDER_GUIDE, "hub-guide", "feather.guide", p -> open(p, null, null));
    }

    @Override
    public void disable() {
        plugin.hub().remove("codex");
    }

    @Override
    public void reload() {
        Yaml c = plugin.loadResourceConfig("guide.yml");
        MiniMessage mini = MiniMessage.miniMessage();
        List<Filterable<net.minecraft.network.chat.Component>> pages = new ArrayList<>();
        for (String page : c.getStringList("pages")) pages.add(Filterable.passThrough(Adv.vanilla(mini.deserialize(page))));
        bookPages = pages.size();
        // A written book's title is plain text (max 32); the MiniMessage title is reduced to its text.
        String title = Adv.plain(mini.deserialize(c.getString("title", "Taming Guide")));
        if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) title = title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
        String author = Adv.plain(mini.deserialize(c.getString("author", "Arcana")));
        book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author, 0, pages, true));
        plugin.getLogger().info("Codex front door ready (/codex; the old taming guide book has {} pages).", bookPages);
    }

    /** Opens the codex (on a tab or creature); without the pets module (which keeps the codex) the old book. */
    public void open(ServerPlayer player, String category, String species) {
        Hooks.Codex codex = plugin.service(Hooks.Codex.class);
        if (codex != null) {
            codex.openCodex(player, category, species, null);
            return;
        }
        openBook(player);
    }

    /**
     * Opens the written book without giving it (Paper's {@code openBook}): the client is shown the book in the selected
     * hotbar slot for one packet, told to open it, then shown the real item again. The real inventory never changes.
     */
    void openBook(ServerPlayer player) {
        if (book.isEmpty() || player.connection == null) return;
        int slot = player.getInventory().getSelectedSlot();
        player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, book.copy()));
        player.connection.send(new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
        player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, player.getInventory().getItem(slot).copy()));
        plugin.text().sound(player, "book");
        plugin.advance(player, "event", "guide");
    }

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("feather.admin")) {
            reload();
            Hooks.Codex codex = plugin.service(Hooks.Codex.class);
            if (codex != null) codex.reloadCodex();
            plugin.text().send(sender, "general.reloaded");
            return true;
        }
        ServerPlayer player = sender.player();
        if (player == null) {
            plugin.text().send(sender, "general.players-only");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("book")) {
            openBook(player);
            return true;
        }
        // /codex <tab or creature>: the codex works out which it is
        String want = args.length == 0 ? null : String.join(" ", args);
        open(player, want, want);
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length != 1) return List.of();
        String a = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        Hooks.Codex codex = plugin.service(Hooks.Codex.class);
        if (codex != null) options.addAll(codex.completions());
        options.add("book");
        List<String> out = new ArrayList<>();
        for (String s : options) if (s.toLowerCase(Locale.ROOT).startsWith(a)) out.add(s);
        return out;
    }
}
