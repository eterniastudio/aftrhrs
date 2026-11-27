package net.axther.aftrhrs.feature.mail;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gifts. {@code /mail gift <player>} opens a two-row chest: the top row takes up to 9 stacks, the bottom row is the
 * controls (Send, Cancel). Send saves the items as a draft in {@code data/mail.yml} first and only then empties the
 * chest, then asks to confirm (what's in it, an optional note). Confirm turns the draft into a gift waiting at the
 * mailbox; Cancel, Escape, quitting, dying or five minutes without an answer give the items back. Closing the chest
 * without sending gives everything back.
 *
 * <p>Picking up ({@link #take}) only works next to a mailbox: what fits goes into the inventory, the rest stays. The
 * store is saved before items reach the player, and only the part that fits is handed out, so nothing is duplicated.
 */
final class MailGifts {
    private static final int SLOTS = 9;
    private static final int INFO = 9;
    private static final int SEND = 13;
    private static final int CANCEL = 17;
    private static final int GIFTS_PER_PAGE = 4;

    private final MailModule mail;
    private final Feather plugin;

    /** The gift chest's state. {@code done}: its items have been handed back or saved; it takes nothing more. */
    static final class GiftHolder {
        final UUID owner;
        final UUID to;
        final String toName;
        Gui gui;
        boolean done;

        GiftHolder(UUID owner, UUID to, String toName) {
            this.owner = owner;
            this.to = to;
            this.toName = toName;
        }
    }

    /** What fits in a player's inventory now and what doesn't. */
    record Split(List<ItemStack> fit, List<ItemStack> rest) {
    }

    MailGifts(MailModule mail) {
        this.mail = mail;
        this.plugin = mail.plugin;
    }

    /** Dying with the gift chest open: its items go back into the inventory first, so they drop (or stay) with the rest. */
    static void registerEvents() {
        FeatherEvents.PLAYER_DEATH.register((p, source) -> {
            MailModule m = MailModule.active;
            if (m == null) return;
            GiftHolder h = holderOf(p);
            if (h == null || h.done) return;
            h.done = true;
            for (ItemStack s : m.gifts.takeSlots(h)) p.getInventory().placeItemBackInInventory(s, Prediction.SERVER_ONLY);
        });
    }

    private Text text() {
        return mail.text();
    }

    private MailStore store() {
        return mail.store;
    }

    // ------------------------------------------------------------------ checks

    /** True (and the player told) while they can't send gifts: mail closed, server code, frozen, robbing the bank, muted. */
    boolean blocked(ServerPlayer p) {
        if (mail.closed(p) || plugin.authPending(p)) return true;
        if (mail.frozen(p)) {
            mail.error(p, "mail.gift.frozen");
            return true;
        }
        if (plugin.travelLocked(p)) {
            mail.error(p, "mail.gift.robbery");
            return true;
        }
        return mail.muted(p);
    }

    /** Whether the recipient takes another gift from this sender. Ignored and full get the same neutral line. */
    private boolean recipientTaking(ServerPlayer from, UUID to, String toName) {
        MailStore.Box b = store().find(to);
        int waiting = b == null ? 0 : b.gifts.size();
        if (waiting >= mail.maxGifts || mail.ignoring(to, from)) {
            mail.error(from, "mail.gift.not-taking", Text.unparsed("name", toName));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the gift chest

    void start(ServerPlayer p, OfflinePlayer target, String toName) {
        if (blocked(p)) return;
        if (target.getUniqueId().equals(p.getUUID())) {
            mail.error(p, "mail.gift.self");
            return;
        }
        if (!recipientTaking(p, target.getUniqueId(), toName)) return;
        if (mail.rateLimited(p)) return;
        // An older gift still waiting for its confirm click goes back first.
        returnDrafts(p, false);
        Component name = MailModule.recipient(target, toName);
        GiftHolder h = new GiftHolder(p.getUUID(), target.getUniqueId(), toName);
        // The title sits on the light chest background: the plain name, not the coloured nickname.
        Gui gui = Gui.create(2, text().msg("mail.gift.gui-title", Text.unparsed("name", Adv.plain(name))));
        h.gui = gui;
        gui.attach(h);
        ItemStack filler = Icons.icon(Items.STAINED_GLASS_PANE.gray()).flags().hideTooltip().build();
        for (int i = SLOTS; i < SLOTS * 2; i++) gui.set(i, filler.copy());
        gui.set(INFO, control(Items.PAPER, "mail.gift.gui-info", name));
        gui.set(SEND, control(Items.DYE.lime(), "mail.gift.gui-send", name), c -> {
            if (c.kind() == Gui.ClickKind.LEFT || c.kind() == Gui.ClickKind.RIGHT) send(c.player(), h);
        });
        gui.set(CANCEL, control(Items.BARRIER, "mail.gift.gui-cancel", name), c -> {
            if (c.kind() != Gui.ClickKind.LEFT && c.kind() != Gui.ClickKind.RIGHT) return;
            if (holderOf(c.player()) == h) c.player().closeContainer();
        });
        for (int i = 0; i < SLOTS; i++) gui.unlock(i);
        gui.onClose(pl -> {
            // Closing (Escape, Cancel, quitting, teleporting, another menu) gives back whatever is still in the top row.
            if (h.done) return;
            h.done = true;
            List<ItemStack> items = takeSlots(h);
            if (!items.isEmpty()) {
                giveOrDrop(pl, items);
                text().send(pl, "mail.gift.returned-to-you");
            }
        });
        gui.open(p);
        text().sound(p, "menu-open");
    }

    /** A button for the control row (locked: no item ever moves in or out of it). */
    private ItemStack control(Item item, String key, Component name) {
        List<Component> lore = text().lines(key + ".lore", Text.component("name", name), Text.unparsed("max", mail.maxGifts));
        boolean noLore = lore.isEmpty() || (lore.size() == 1 && PlainTextComponentSerializer.plainText().serialize(lore.get(0)).isEmpty());
        Icons.Icon icon = Icons.icon(item).name(text().itemMsg(key + ".name", Text.component("name", name))).flags();
        if (!noLore) icon.lore(lore);
        return icon.build();
    }

    private static GiftHolder holderOf(ServerPlayer p) {
        Gui g = Gui.of(p);
        return g != null && g.attachment() instanceof GiftHolder h ? h : null;
    }

    List<ItemStack> takeSlots(GiftHolder h) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = h.gui.container().getItem(i);
            if (!s.isEmpty()) items.add(s.copy());
            h.gui.container().setItem(i, ItemStack.EMPTY);
        }
        return items;
    }

    private static void giveOrDrop(ServerPlayer p, List<ItemStack> items) {
        for (ItemStack s : items) {
            if (s.isEmpty()) continue;
            p.getInventory().placeItemBackInInventory(s.copy(), Prediction.SERVER_ONLY);
        }
    }

    /** Send: the top row is saved as a draft (on disk) before it is emptied, then the confirm screen opens. */
    private void send(ServerPlayer p, GiftHolder h) {
        if (h.done || !p.getUUID().equals(h.owner)) return;
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = h.gui.container().getItem(i);
            if (!s.isEmpty()) items.add(s.copy());
        }
        if (items.isEmpty()) {
            mail.error(p, "mail.gift.empty-chest");
            return;
        }
        if (blocked(p) || !recipientTaking(p, h.to, h.toName) || mail.rateLimited(p)) return;
        MailStore.Draft d = new MailStore.Draft(store().nextId(), p.getUUID(), h.to, h.toName, MailStore.encode(items), System.currentTimeMillis());
        store().drafts.put(d.id(), d);
        if (!store().save()) {
            store().drafts.remove(d.id());
            mail.error(p, "mail.save-failed");
            return;
        }
        h.done = true;
        for (int i = 0; i < SLOTS; i++) h.gui.container().setItem(i, ItemStack.EMPTY);
        Tasks.sync(() -> {
            if (p.hasDisconnected()) return;
            if (holderOf(p) == h) p.closeContainer();
            if (store().drafts.containsKey(d.id())) confirm(p, d);
        });
    }

    private Component recipientName(MailStore.Draft d) {
        return MailModule.recipient(mail.offline(d.to(), d.toName()), d.toName());
    }

    Component itemLine(ItemStack s) {
        return text().msg("mail.gift.item", Text.unparsed("amount", s.getCount()), Text.component("item", Adv.adventure(s.getHoverName())));
    }

    private Component itemList(List<ItemStack> items) {
        List<Component> parts = new ArrayList<>();
        for (ItemStack s : items) parts.add(itemLine(s));
        return Component.join(JoinConfiguration.separator(text().msg("mail.gift.item-separator")), parts);
    }

    /** "Send this gift?": the items, a note box, Send Gift, and Cancel (also Escape), which gives them back. */
    private void confirm(ServerPlayer p, MailStore.Draft d) {
        Text text = text();
        List<ItemStack> items = MailStore.decode(d.items());
        Component name = recipientName(d);
        Dialogs.Menu menu = mail.dialogs().menu(text.msg("mail.gift.confirm-title", Text.component("name", name)));
        menu.text(text.msg("mail.gift.confirm-body", Text.component("name", name)), 300);
        List<Component> lines = new ArrayList<>();
        for (ItemStack s : items == null ? List.<ItemStack>of() : items) lines.add(text.msg("mail.gift.confirm-item", Text.component("item", itemLine(s))));
        menu.text(Component.join(JoinConfiguration.newlines(), lines), 300);
        if (mail.maxNoteLength > 0) {
            menu.input(Dialogs.Input.text("note", text.msg("mail.gift.note", Text.unparsed("max", mail.maxNoteLength)))
                    .maxLength(mail.maxNoteLength).width(300).build());
        }
        menu.button(text.msg("mail.gift.confirm-send"), text.msg("mail.gift.confirm-send-tooltip", Text.component("name", name)), 150,
                (ServerPlayer pl, Dialogs.Response view) -> finish(pl, d.id(), view == null ? null : view.getText("note")));
        menu.columns(1);
        menu.exit(text.msg("mail.gift.confirm-cancel"), pl -> cancel(pl, d.id(), true));
        menu.open(p);
    }

    /** The confirm click: the draft becomes a gift for the recipient (checked once more), saved before anyone is told. */
    private void finish(ServerPlayer p, long id, String rawNote) {
        MailStore.Draft d = store().drafts.get(id);
        if (d == null) {
            // Waited too long (or it went back another way): the items were already returned.
            mail.error(p, "mail.gift.draft-gone");
            return;
        }
        if (!d.owner().equals(p.getUUID())) return;
        if (blocked(p) || !recipientTaking(p, d.to(), d.toName())) {
            cancel(p, id, true);
            return;
        }
        String note = MailModule.clean(rawNote, 1);
        if (note.length() > mail.maxNoteLength) note = note.substring(0, mail.maxNoteLength);
        MailStore.Gift g = new MailStore.Gift(store().nextId(), p.getUUID(), Names.name(p), MailModule.displayJson(p), note,
                System.currentTimeMillis(), d.items(), false);
        MailStore.Box box = store().box(d.to());
        box.gifts.add(g);
        store().drafts.remove(id);
        if (!store().save()) {
            box.gifts.remove(g);
            store().drafts.put(id, d);
            mail.error(p, "mail.save-failed");
            cancel(p, id, true);
            return;
        }
        mail.countSend(p);
        List<ItemStack> items = MailStore.decode(d.items());
        store().log("GIFT #" + g.id + " from " + Names.name(p) + " (" + p.getUUID() + ") to " + d.toName() + " (" + d.to() + "): "
                + describe(items) + (note.isEmpty() ? "" : " | note: " + note));
        Component name = recipientName(d);
        text().send(p, "mail.gift.sent", Text.component("name", name));
        text().sound(p, "accepted");
        plugin.advance(p, "event", "mail-gift");
        ServerPlayer r = plugin.player(d.to());
        if (r != null && !plugin.authPending(r)) {
            Adv.send(r, mail.clickable(text().msg(mail.mailboxes.any() ? "mail.gift.arrived" : "mail.gift.arrived-no-mailbox",
                    Text.component("name", Names.display(p)))));
            text().sound(r, "request-received");
        }
    }

    /**
     * Gives a draft's items back to its owner (online). What doesn't fit waits at the mailbox as their unsent gift. The
     * store is saved before the items are handed out, so a crash can't return them twice.
     */
    boolean cancel(ServerPlayer p, long id, boolean tell) {
        MailStore.Draft d = store().drafts.get(id);
        if (d == null || !d.owner().equals(p.getUUID()) || store().unreadable) return false;
        List<ItemStack> items = MailStore.decode(d.items());
        if (items == null) {
            plugin.getLogger().error("Mail: the items of unsent gift #{} ({}) can't be read; it stays in data/mail.yml.", id, Names.name(p));
            return false;
        }
        Split split = split(p, items);
        store().drafts.remove(id);
        MailStore.Gift back = null;
        MailStore.Box own = store().box(p.getUUID());
        if (!split.rest().isEmpty()) {
            back = new MailStore.Gift(store().nextId(), p.getUUID(), Names.name(p), MailModule.displayJson(p), "",
                    System.currentTimeMillis(), MailStore.encode(split.rest()), true);
            own.gifts.add(back);
        }
        if (!store().save()) {
            store().drafts.put(id, d);
            if (back != null) own.gifts.remove(back);
            mail.error(p, "mail.save-failed");
            return false;
        }
        handOut(p, split.fit());
        if (tell) {
            text().send(p, back == null ? "mail.gift.returned-to-you" : "mail.gift.returned-some");
            text().sound(p, "cancel");
        }
        return true;
    }

    /** Every unsent gift of this player goes back to them (after a crash, on quit, before a new gift). */
    void returnDrafts(ServerPlayer p) {
        returnDrafts(p, true);
    }

    void returnDrafts(ServerPlayer p, boolean tell) {
        for (MailStore.Draft d : new ArrayList<>(store().drafts.values())) {
            if (d.owner().equals(p.getUUID())) cancel(p, d.id(), tell);
        }
    }

    /**
     * Leaving: an open gift chest and unsent gifts go back into the inventory now, before the player's data is saved
     * (Fabric's DISCONNECT runs first).
     */
    void onQuit(ServerPlayer p) {
        GiftHolder h = holderOf(p);
        if (h != null && !h.done) {
            h.done = true;
            List<ItemStack> items = takeSlots(h);
            // The cursor too: vanilla closes the menu after the save, dropping it at the logout spot for anyone.
            ItemStack carried = p.containerMenu.getCarried();
            if (!carried.isEmpty()) {
                items.add(carried.copy());
                p.containerMenu.setCarried(ItemStack.EMPTY);
            }
            giveOrDrop(p, items);
        }
        // On the death screen the inventory is thrown away at respawn: unsent gifts stay saved for the next join.
        if (!p.isDeadOrDying()) returnDrafts(p, false);
    }

    /** Unsent gifts older than five minutes go back to their (online) owner. */
    void expireDrafts() {
        long now = System.currentTimeMillis();
        for (MailStore.Draft d : new ArrayList<>(store().drafts.values())) {
            if (now - d.time() < mail.draftTimeoutMs) continue;
            ServerPlayer p = plugin.player(d.owner());
            if (p != null && !p.isDeadOrDying()) cancel(p, d.id(), true);
        }
    }

    /** Server stop: open gift chests and unsent gifts go back to their owners. */
    void disable() {
        for (ServerPlayer p : plugin.onlinePlayers()) {
            GiftHolder h = holderOf(p);
            if (h != null && !h.done) {
                h.done = true;
                giveOrDrop(p, takeSlots(h));
                p.closeContainer();
            }
            returnDrafts(p, false);
        }
    }

    /** Unclaimed gifts older than mail.return-after-days go back to their senders (off at 0). */
    void returnExpired() {
        if (mail.returnAfterMs <= 0 || store().unreadable) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<UUID, MailStore.Box> e : new ArrayList<>(store().boxes.entrySet())) {
            UUID owner = e.getKey();
            Iterator<MailStore.Gift> it = e.getValue().gifts.iterator();
            while (it.hasNext()) {
                MailStore.Gift g = it.next();
                if (g.returned || g.from == null || g.from.equals(owner) || now - g.time < mail.returnAfterMs) continue;
                it.remove();
                OfflinePlayer o = mail.offline(owner, null);
                String name = o.getName() == null ? owner.toString().substring(0, 8) : o.getName();
                MailStore.Gift back = new MailStore.Gift(store().nextId(), owner, name, null, g.note, now, g.items, true);
                store().box(g.from).gifts.add(back);
                store().log("RETURN #" + g.id + " -> #" + back.id + " unclaimed by " + name + " (" + owner + ") back to " + g.fromName + " (" + g.from + ")");
                changed = true;
            }
        }
        // The gift only moves between two boxes in one snapshot (no items leave anyone): written in the background.
        if (changed) {
            store().markDirty();
            store().saveIfDirtyAsync();
        }
    }

    // ------------------------------------------------------------------ inventory maths

    /** Splits items into what fits in the player's 36 storage slots right now and what doesn't (nothing is moved). */
    static Split split(ServerPlayer p, List<ItemStack> items) {
        List<ItemStack> sim = new ArrayList<>();
        var main = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < Math.min(36, main.size()); i++) sim.add(main.get(i).copy());
        List<ItemStack> fit = new ArrayList<>();
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack orig : items) {
            int left = orig.getCount();
            int max = orig.getMaxStackSize();
            // Partial stacks of the same item first, then empty slots (Bukkit's addItem).
            for (int i = 0; i < sim.size() && left > 0; i++) {
                ItemStack s = sim.get(i);
                if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, orig)) continue;
                int room = Math.min(max, s.getMaxStackSize()) - s.getCount();
                if (room <= 0) continue;
                int put = Math.min(room, left);
                s.grow(put);
                left -= put;
            }
            for (int i = 0; i < sim.size() && left > 0; i++) {
                if (!sim.get(i).isEmpty()) continue;
                int put = Math.min(max, left);
                sim.set(i, orig.copyWithCount(put));
                left -= put;
            }
            int placed = orig.getCount() - left;
            if (placed > 0) fit.add(orig.copyWithCount(placed));
            if (left > 0) rest.add(orig.copyWithCount(left));
        }
        return new Split(fit, rest);
    }

    /** Hands out stacks that were checked to fit; anything that still doesn't (it shouldn't) waits at the mailbox. */
    private void handOut(ServerPlayer p, List<ItemStack> fit) {
        if (fit.isEmpty()) return;
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack s : fit) {
            ItemStack copy = s.copy();
            p.getInventory().add(copy);
            if (!copy.isEmpty()) rest.add(copy);
        }
        if (rest.isEmpty()) return;
        MailStore.Gift back = new MailStore.Gift(store().nextId(), p.getUUID(), Names.name(p), MailModule.displayJson(p), "",
                System.currentTimeMillis(), MailStore.encode(rest), true);
        store().box(p.getUUID()).gifts.add(back);
        if (!store().save()) {
            store().box(p.getUUID()).gifts.remove(back);
            for (ItemStack s : rest) p.drop(s, false, Prediction.SERVER_ONLY);
        }
    }

    static String describe(List<ItemStack> items) {
        if (items == null) return "(unreadable)";
        return items.stream().map(s -> {
            StringBuilder b = new StringBuilder(s.getCount() + "x " + BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
            net.minecraft.network.chat.Component custom = s.get(DataComponents.CUSTOM_NAME);
            if (custom != null) b.append(" \"").append(custom.getString()).append('"');
            var ench = s.getEnchantments();
            if (!ench.isEmpty()) {
                b.append(" [").append(ench.entrySet().stream()
                        .map(e -> enchName(e.getKey()) + " " + e.getIntValue()).collect(Collectors.joining(", "))).append(']');
            }
            return b.toString();
        }).collect(Collectors.joining(", "));
    }

    private static String enchName(Holder<Enchantment> h) {
        return h.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
    }

    // ------------------------------------------------------------------ the mailbox screen

    /** /mail gifts: the gift screen when standing at a mailbox. */
    void openNearby(ServerPlayer p) {
        if (mail.closed(p)) return;
        if (!mail.mailboxes.near(p)) {
            mail.error(p, mail.mailboxes.any() ? "mail.gift.at-mailbox-only" : "mail.gift.no-mailbox");
            return;
        }
        openScreen(p, 0);
    }

    /** The mailbox: each gift with its sender, note and items, Take per gift, Take All. */
    void openScreen(ServerPlayer player, int page) {
        if (mail.closed(player) || plugin.authPending(player)) return;
        Text text = text();
        MailStore.Box box = store().find(player.getUUID());
        List<MailStore.Gift> list = box == null ? List.of() : new ArrayList<>(box.gifts);
        Dialogs.Menu menu = mail.dialogs().menu(text.msg("mail.mailbox.title"));
        if (list.isEmpty()) {
            menu.text(text.msg("mail.mailbox.empty"), 300);
            int unread = mail.unread(player.getUUID());
            menu.button(text.msg(unread > 0 ? "mail.mailbox.letters-new" : "mail.mailbox.letters", Text.unparsed("count", unread)), null, 150,
                    (ServerPlayer p) -> mail.open(p));
            menu.columns(1);
            menu.exit(text.msg("menu.close"), null);
            menu.open(player);
            return;
        }
        int pages = Math.max(1, (list.size() + GIFTS_PER_PAGE - 1) / GIFTS_PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        menu.text(text.msg("mail.mailbox.summary", Text.unparsed("gifts", mail.giftCount(list.size())),
                Text.unparsed("page", pg + 1), Text.unparsed("pages", pages)), 300);
        List<MailStore.Gift> shown = list.subList(pg * GIFTS_PER_PAGE, Math.min(list.size(), (pg + 1) * GIFTS_PER_PAGE));
        int n = pg * GIFTS_PER_PAGE;
        for (MailStore.Gift g : shown) {
            n++;
            Component name = mail.nameOf(g.from, g.fromName, g.fromDisplay);
            String key = !g.returned ? "mail.mailbox.gift" : player.getUUID().equals(g.from) ? "mail.mailbox.gift-unsent" : "mail.mailbox.gift-returned";
            List<Component> lines = new ArrayList<>();
            lines.add(text.msg(key, Text.unparsed("n", n), Text.component("name", name), Text.unparsed("ago", mail.ago(g.time))));
            if (g.note != null && !g.note.isEmpty()) lines.add(text.msg("mail.mailbox.note", Text.unparsed("note", g.note)));
            List<ItemStack> items = MailStore.decode(g.items);
            lines.add(items == null ? text.msg("mail.mailbox.unreadable") : itemList(items));
            menu.text(Component.join(JoinConfiguration.newlines(), lines), 300);
        }
        n = pg * GIFTS_PER_PAGE;
        for (MailStore.Gift g : shown) {
            n++;
            final long id = g.id;
            menu.button(text.msg("mail.mailbox.take", Text.unparsed("n", n)), text.msg("mail.mailbox.take-tooltip"), 150, (ServerPlayer p) -> {
                take(p, id);
                openScreen(p, pg);
            });
        }
        if (list.size() > 1) menu.button(text.msg("mail.mailbox.take-all"), text.msg("mail.mailbox.take-tooltip"), 150, (ServerPlayer p) -> {
            takeAll(p);
            openScreen(p, 0);
        });
        if (pg > 0) menu.button(text.msg("mail.screen.prev"), null, 150, (ServerPlayer p) -> openScreen(p, pg - 1));
        if (pg < pages - 1) menu.button(text.msg("mail.screen.next"), null, 150, (ServerPlayer p) -> openScreen(p, pg + 1));
        menu.columns(2);
        menu.exit(text.msg("menu.close"), null);
        menu.open(player);
    }

    private boolean canTake(ServerPlayer p) {
        if (mail.closed(p) || plugin.authPending(p)) return false;
        if (mail.frozen(p)) {
            mail.error(p, "mail.gift.frozen");
            return false;
        }
        if (!mail.mailboxes.near(p)) {
            mail.error(p, mail.mailboxes.any() ? "mail.gift.at-mailbox-only" : "mail.gift.no-mailbox");
            return false;
        }
        return true;
    }

    private void takeAll(ServerPlayer p) {
        if (!canTake(p)) return;
        MailStore.Box box = store().find(p.getUUID());
        if (box == null) return;
        for (MailStore.Gift g : new ArrayList<>(box.gifts)) {
            if (take(p, g.id) <= 0) break;
        }
    }

    /**
     * Takes one gift at the mailbox: what fits goes into the inventory, the rest stays in the gift. Returns how many
     * items were taken (0 for none, -1 when it couldn't be done).
     */
    int take(ServerPlayer p, long giftId) {
        if (!canTake(p)) return -1;
        MailStore.Box box = store().find(p.getUUID());
        MailStore.Gift g = box == null ? null : box.gifts.stream().filter(x -> x.id == giftId).findFirst().orElse(null);
        if (g == null) return -1;
        List<ItemStack> items = MailStore.decode(g.items);
        if (items == null) {
            mail.error(p, "mail.mailbox.unreadable");
            return -1;
        }
        Split split = split(p, items);
        if (split.fit().isEmpty()) {
            mail.error(p, "mail.gift.no-room");
            return 0;
        }
        String before = g.items;
        int index = box.gifts.indexOf(g);
        boolean all = split.rest().isEmpty();
        if (all) box.gifts.remove(g);
        else g.items = MailStore.encode(split.rest());
        if (!store().save()) {
            g.items = before;
            if (all) box.gifts.add(index, g);
            mail.error(p, "mail.save-failed");
            return -1;
        }
        handOut(p, split.fit());
        int taken = split.fit().stream().mapToInt(ItemStack::getCount).sum();
        store().log("TAKE #" + g.id + " by " + Names.name(p) + " (" + p.getUUID() + ") from " + g.fromName + ": " + describe(split.fit())
                + (all ? "" : " | left in the mailbox: " + describe(split.rest())));
        text().send(p, all ? "mail.gift.taken" : "mail.gift.taken-some", Text.component("name", mail.nameOf(g.from, g.fromName, g.fromDisplay)));
        text().sound(p, "item-pickup");
        return taken;
    }
}
