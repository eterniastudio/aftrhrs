package net.axther.aftrhrs.feature.mail;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.JoinDigest;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Offline mail and gifts. {@code /mail send <player> <message>} leaves a letter for anyone who has played here; {@code /mail}
 * opens the Mail screen (newest first, Reply, Delete, Clear All). {@code /mail gift <player>} opens a chest GUI for up to 9
 * stacks ({@link MailGifts}); gifts are picked up at a mailbox block at spawn ({@link Mailboxes}).
 *
 * <p>Letters are plain text (never parsed as MiniMessage). Ignored senders' letters are dropped without telling them,
 * muted players can't send, sends are rate limited and each player keeps at most {@code mail.max-letters} letters
 * (the oldest read ones make room). Data: {@link MailStore} ({@code data/mail.yml}).
 */
public final class MailModule implements FeatherModule, FeatherCommand {
    static final String USE = "feather.mail";
    static final String ADMIN = "feather.mail.admin";
    static final String GIFT = "feather.mail.gift";
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    /** The running module (Fabric listeners can't be unregistered; they go quiet while this is null). */
    static volatile MailModule active;

    final Feather plugin;
    final MailStore store;
    final MailGifts gifts;
    final Mailboxes mailboxes;
    private Tasks.Task ticker;
    private long ticks;

    int maxLetterLength;
    int maxNoteLength;
    int maxLetters;
    int maxGifts;
    int pageSize;
    long cooldownMs;
    int perHour;
    long returnAfterMs;
    double accessDistance;
    long draftTimeoutMs;
    /** When each player sent their recent letters and gifts (for the rate limit). */
    private final Map<UUID, Deque<Long>> sends = new HashMap<>();

    public MailModule(Feather plugin) {
        this.plugin = plugin;
        this.store = new MailStore(plugin);
        this.gifts = new MailGifts(this);
        this.mailboxes = new Mailboxes(this);
    }

    @Override
    public String name() {
        return "Mail";
    }

    @Override
    public void enable() {
        reload();
        store.load();
        active = this;
        plugin.once("mail.events", () -> {
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                MailModule m = active;
                if (m != null) {
                    m.gifts.onQuit(h.player);
                    m.store.saveIfDirtyAsync();
                }
            });
            MailGifts.registerEvents();
            Mailboxes.registerEvents();
        });
        plugin.bind("mail", this);
        // Storage networks leave mailbox blocks out (Feather did this through a direct lookup).
        plugin.provide(Hooks.Mailboxes.class, mailboxes::isMailbox);
        plugin.provide(Hooks.Mail.class, new Hooks.Mail() {
            @Override
            public boolean systemLetter(UUID to, String fromName, String body) {
                return MailModule.this.systemLetter(to, fromName, body);
            }

            @Override
            public boolean systemGift(UUID to, String fromName, String note, List<ItemStack> items) {
                return MailModule.this.systemGift(to, fromName, note, items);
            }
        });
        plugin.hub().add(new Hub.Entry("mail", Hub.Group.COMMUNITY, Hub.ORDER_MAIL, p -> Perms.has(p, USE),
                p -> hubLabel(p, text().msg("menu.hub.mail")), p -> text().msg("menu.hub.mail-tooltip"), "hub-social", this::open));
        gifts.returnExpired();
        ticker = Tasks.timer(40L, 40L, this::tick);
        JoinDigest.register("mail", 30, this::collectJoin);
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        maxLetterLength = clamp(c.getInt("mail.max-letter-length", 256), 16, 1000);
        maxNoteLength = clamp(c.getInt("mail.max-note-length", 100), 0, 500);
        maxLetters = clamp(c.getInt("mail.max-letters", 50), 1, 500);
        maxGifts = clamp(c.getInt("mail.max-waiting-gifts", 10), 1, 100);
        pageSize = clamp(c.getInt("mail.page-size", 5), 1, 10);
        cooldownMs = Math.max(0, c.getLong("mail.cooldown-seconds", 3)) * 1000L;
        perHour = clamp(c.getInt("mail.max-per-hour", 30), 1, 10_000);
        returnAfterMs = Math.max(0, c.getLong("mail.return-after-days", 0)) * 86_400_000L;
        accessDistance = Math.max(2, Math.min(32, c.getDouble("mail.access-distance", 6)));
        draftTimeoutMs = 5 * 60_000L;
        mailboxes.reload(c);
        // A file that couldn't be read is tried again (after it was fixed).
        if (store.unreadable) store.load();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        JoinDigest.unregister("mail");
        gifts.disable();
        // Read and deleted letters still waiting for the tick (Feather.disable then drains the data thread).
        store.saveIfDirtyAsync();
        plugin.withdraw(Hooks.Mail.class);
        plugin.hub().remove("mail");
        if (active == this) active = null;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Every 2 seconds: the mailbox sparkle; every 30 s gifts left unconfirmed; every 10 minutes unclaimed gifts. */
    private void tick() {
        ticks++;
        // Letters read or deleted since the last run (marked dirty, written in the background).
        store.saveIfDirtyAsync();
        mailboxes.sparkle(ticks);
        if (ticks % 15 == 0) gifts.expireDrafts();
        if (ticks % 300 == 0) {
            gifts.returnExpired();
            // Send times older than the hourly window no longer matter.
            long now = System.currentTimeMillis();
            sends.values().removeIf(q -> q.isEmpty() || now - q.peekLast() > 3_600_000L);
        }
    }

    Text text() {
        return plugin.text();
    }

    Dialogs dialogs() {
        return plugin.dialogs();
    }

    void error(Audience to, String key, TagResolver... tags) {
        text().send(to, key, tags);
        ServerPlayer p = to instanceof Sender s ? s.player() : null;
        if (p != null) text().sound(p, "error");
    }

    void error(ServerPlayer to, String key, TagResolver... tags) {
        text().send(to, key, tags);
        text().sound(to, "error");
    }

    /** Tells the player the mail is closed (data/mail.yml couldn't be read) and returns true. */
    boolean closed(ServerPlayer to) {
        if (!store.unreadable) return false;
        error(to, "mail.closed");
        return true;
    }

    boolean closed(Sender to) {
        if (!store.unreadable) return false;
        error(to, "mail.closed");
        return true;
    }

    // ------------------------------------------------------------------ shared helpers

    /** The sender's display name now (online) or as it was when they wrote (offline). */
    Component nameOf(UUID id, String name, String display) {
        ServerPlayer online = id == null ? null : plugin.player(id);
        if (online != null) return Names.display(online).clickEvent(null).hoverEvent(null);
        if (display != null && !display.isEmpty()) {
            try {
                return GSON.deserialize(display).clickEvent(null).hoverEvent(null);
            } catch (RuntimeException ignored) {
                // fall back to the name
            }
        }
        return Component.text(name == null ? "?" : name);
    }

    static String displayJson(ServerPlayer p) {
        try {
            return GSON.serialize(Names.display(p));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "5m ago" / "just now". */
    String ago(long time) {
        long d = System.currentTimeMillis() - time;
        if (d < 60_000L) return text().raw("mail.just-now");
        return text().raw("mail.ago").replace("<time>", Placeholders.duration(d));
    }

    /** A muted player is told so (true). */
    boolean muted(ServerPlayer p) {
        Profiles.Profile prof = plugin.profiles().get(p);
        if (prof == null || !prof.muted()) return false;
        error(p, "mod.you-are-muted-perm", Text.unparsed("reason", ""));
        return true;
    }

    /** Frozen by staff (the moderation module's /freeze, kept in the profile). */
    boolean frozen(ServerPlayer p) {
        Profiles.Profile prof = plugin.profiles().get(p);
        return prof != null && prof.frozen;
    }

    /** True (and the player told) when they have to wait before sending again. Doesn't count a send. */
    boolean rateLimited(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Deque<Long> q = sends.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
        if (!q.isEmpty() && now - q.peekLast() < cooldownMs) {
            error(p, "mail.slow-down", Text.unparsed("seconds", Math.max(1, (cooldownMs - (now - q.peekLast()) + 999) / 1000)));
            return true;
        }
        if (q.size() >= perHour) {
            error(p, "mail.hourly-limit", Text.unparsed("max", perHour));
            return true;
        }
        return false;
    }

    void countSend(ServerPlayer p) {
        sends.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    /** Whether {@code receiver} has {@code sender} on their ignore list (staff with feather.ignore.exempt can't be ignored). */
    boolean ignoring(UUID receiver, ServerPlayer sender) {
        if (Perms.has(sender, "feather.ignore.exempt")) return false;
        Profiles.Profile prof = plugin.profiles().load(receiver);
        return prof != null && prof.ignored.contains(sender.getUUID());
    }

    /** Someone who is online (name, nickname or unique prefix) or has played here before; null (and told) if nobody. */
    OfflinePlayer target(ServerPlayer viewer, String input) {
        ServerPlayer online = plugin.findPlayer(viewer, input);
        if (online != null) return OfflinePlayer.of(online);
        OfflinePlayer off = plugin.findOfflinePlayer(viewer, input); // also saved nicknames
        if (off != null && (off.isOnline() || off.hasPlayedBefore())) return off;
        error(viewer, "mail.unknown-player", Text.unparsed("name", input));
        return null;
    }

    /** Someone by id (name from the player files when known). */
    OfflinePlayer offline(UUID id, String fallbackName) {
        ServerPlayer online = plugin.player(id);
        if (online != null) return OfflinePlayer.of(online);
        OfflinePlayer known = plugin.lookup().byId(id);
        if (known != null && known.getName() != null) return known;
        return new OfflinePlayer(id, fallbackName);
    }

    static String nameOf(OfflinePlayer p, String fallback) {
        return p.getName() == null ? fallback : p.getName();
    }

    /** Display name for a recipient: online → their display name, else the plain name. */
    static Component recipient(OfflinePlayer p, String fallback) {
        ServerPlayer online = p.online();
        return online != null ? Names.display(online).clickEvent(null).hoverEvent(null) : Component.text(nameOf(p, fallback));
    }

    /**
     * Cleans what a player typed: no § codes or control characters, at most {@code lines} lines, trimmed. Empty when
     * nothing is left. The text is shown as plain text everywhere (Text.unparsed), so tags are just characters.
     */
    static String clean(String raw, int lines) {
        if (raw == null) return "";
        List<String> kept = new ArrayList<>();
        for (String part : raw.replace("\r", "").split("\n", -1)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (ch == '\t') sb.append(' ');
                else if (ch == '§' || ch < 0x20 || ch == 0x7f || (ch >= 0x202a && ch <= 0x202e) || (ch >= 0x2066 && ch <= 0x2069)) continue;
                else sb.append(ch);
            }
            String line = sb.toString().strip();
            if (!line.isEmpty()) kept.add(line);
        }
        while (kept.size() > Math.max(1, lines)) {
            String last = kept.remove(kept.size() - 1);
            kept.set(kept.size() - 1, kept.get(kept.size() - 1) + " " + last);
        }
        return String.join("\n", kept);
    }

    int unread(UUID id) {
        MailStore.Box b = store.find(id);
        return b == null ? 0 : b.unread();
    }

    int waitingGifts(UUID id) {
        MailStore.Box b = store.find(id);
        return b == null ? 0 : b.gifts.size();
    }

    /** The hub's Mail label with the "✉ n" badge while letters are unread or gifts wait. */
    Component hubLabel(ServerPlayer player, Component label) {
        int n = unread(player.getUUID()) + waitingGifts(player.getUUID());
        return n > 0 ? label.append(text().msg("menu.hub.teleport-badge", Text.unparsed("count", n))) : label;
    }

    // ------------------------------------------------------------------ letters

    /** Sends a letter. True when the sender was told it went (also when the recipient ignores them). */
    boolean sendLetter(ServerPlayer from, OfflinePlayer to, String toName, String raw) {
        Text text = text();
        if (closed(from) || plugin.authPending(from)) return false;
        if (to.getUniqueId().equals(from.getUUID())) {
            error(from, "mail.self");
            return false;
        }
        if (muted(from)) return false;
        String body = clean(raw, 6);
        if (body.isEmpty()) {
            error(from, "mail.empty");
            return false;
        }
        if (body.length() > maxLetterLength) {
            error(from, "mail.too-long", Text.unparsed("max", maxLetterLength), Text.unparsed("length", body.length()));
            return false;
        }
        if (rateLimited(from)) return false;
        UUID id = to.getUniqueId();
        boolean ignored = ignoring(id, from);
        if (!ignored) {
            MailStore.Box box = store.box(id);
            List<MailStore.Letter> before = new ArrayList<>(box.letters);
            while (box.letters.size() >= maxLetters) {
                MailStore.Letter oldestRead = box.letters.stream().filter(l -> l.read).findFirst().orElse(null);
                if (oldestRead == null) break;
                box.letters.remove(oldestRead);
            }
            if (box.letters.size() >= maxLetters) {
                box.letters.clear();
                box.letters.addAll(before);
                error(from, "mail.box-full", Text.component("name", recipient(to, toName)));
                return false;
            }
            MailStore.Letter letter = new MailStore.Letter(store.nextId(), from.getUUID(), Names.name(from), displayJson(from),
                    body, System.currentTimeMillis(), false);
            box.letters.add(letter);
            if (!store.save()) {
                box.letters.clear();
                box.letters.addAll(before);
                error(from, "mail.save-failed");
                return false;
            }
        }
        countSend(from);
        text.send(from, "mail.sent", Text.component("name", recipient(to, toName)));
        text.sound(from, "request-sent");
        plugin.advance(from, "event", "mail");
        ServerPlayer online = to.online();
        if (!ignored && online != null && !plugin.authPending(online)) {
            Adv.send(online, clickable(text.msg("mail.new-letter", Text.component("name", Names.display(from)))));
            text.sound(online, "message");
        }
        return true;
    }

    /**
     * A letter from the server (a market stall, ...): no sender to reply to, never ignored or rate limited. False when
     * the mail is closed or the save failed (nothing kept then). Tells the player when they are online.
     */
    boolean systemLetter(UUID to, String fromName, String body) {
        if (to == null || store.unreadable) return false;
        String text = clean(body, 6);
        if (text.isEmpty()) return false;
        MailStore.Box box = store.box(to);
        while (box.letters.size() >= maxLetters) {
            MailStore.Letter oldest = box.letters.stream().filter(l -> l.read).findFirst().orElse(box.letters.get(0));
            box.letters.remove(oldest);
        }
        MailStore.Letter letter = new MailStore.Letter(store.nextId(), null, fromName, null, text, System.currentTimeMillis(), false);
        box.letters.add(letter);
        if (!store.save()) {
            box.letters.remove(letter);
            return false;
        }
        ServerPlayer online = plugin.player(to);
        if (online != null && !plugin.authPending(online)) {
            Adv.send(online, clickable(text().msg("mail.new-letter", Text.component("name", Component.text(fromName == null ? "?" : fromName)))));
            text().sound(online, "message");
        }
        return true;
    }

    /**
     * Items from the server waiting at the mailbox (9 stacks per gift; more become several gifts). Saved before this
     * returns: false (nothing kept) when the mail is closed or the save failed.
     */
    boolean systemGift(UUID to, String fromName, String note, List<ItemStack> items) {
        if (to == null || store.unreadable || items == null || items.isEmpty()) return false;
        List<ItemStack> real = new ArrayList<>();
        for (ItemStack s : items) if (s != null && !s.isEmpty()) real.add(s.copy());
        if (real.isEmpty()) return false;
        MailStore.Box box = store.box(to);
        List<MailStore.Gift> added = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < real.size(); i += 9) {
            List<ItemStack> part = real.subList(i, Math.min(real.size(), i + 9));
            MailStore.Gift g = new MailStore.Gift(store.nextId(), null, fromName, null, note == null ? "" : clean(note, 1), now, MailStore.encode(part), false);
            box.gifts.add(g);
            added.add(g);
        }
        if (!store.save()) {
            box.gifts.removeAll(added);
            return false;
        }
        for (MailStore.Gift g : added) store.log("SYSTEM-GIFT #" + g.id + " from " + fromName + " to " + to + ": " + MailGifts.describe(MailStore.decode(g.items)));
        return true;
    }

    /** Makes a chat line open the Mail screen when clicked. */
    Component clickable(Component line) {
        return line.clickEvent(ClickEvent.runCommand("/mail")).hoverEvent(HoverEvent.showText(text().msg("mail.open-hover")));
    }

    /** The Mail screen, first page. */
    void open(ServerPlayer player) {
        open(player, 0);
    }

    /**
     * The Mail screen: newest letter first, each with its sender, how long ago, new/read and the text; Reply and Delete per
     * letter, Write a Letter, Clear All and the pages. Opening it marks the letters on the page read.
     */
    void open(ServerPlayer player, int page) {
        if (closed(player) || plugin.authPending(player)) return;
        Text text = text();
        MailStore.Box box = store.find(player.getUUID());
        List<MailStore.Letter> letters = box == null ? List.of() : new ArrayList<>(box.letters);
        java.util.Collections.reverse(letters);
        int unread = box == null ? 0 : box.unread();
        int pages = Math.max(1, (letters.size() + pageSize - 1) / pageSize);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Dialogs.Menu menu = dialogs().menu(text.msg("mail.screen.title"));
        if (letters.isEmpty()) menu.text(text.msg("mail.screen.empty"), 300);
        else menu.text(text.msg(unread > 0 ? "mail.screen.summary" : "mail.screen.summary-read",
                Text.unparsed("count", letters.size()), Text.unparsed("new", unread),
                Text.unparsed("page", pg + 1), Text.unparsed("pages", pages)), 300);
        List<MailStore.Letter> shown = letters.subList(pg * pageSize, Math.min(letters.size(), (pg + 1) * pageSize));
        int n = pg * pageSize;
        boolean changed = false;
        for (MailStore.Letter l : shown) {
            n++;
            menu.text(text.msg(l.read ? "mail.screen.letter-read" : "mail.screen.letter-new", Text.unparsed("n", n),
                    Text.component("name", nameOf(l.from, l.fromName, l.fromDisplay)), Text.unparsed("ago", ago(l.time)),
                    Text.unparsed("message", l.text)), 300);
        }
        int waiting = box == null ? 0 : box.gifts.size();
        if (waiting > 0) {
            menu.text(text.msg(mailboxes.any() ? "mail.screen.gifts" : "mail.screen.gifts-no-mailbox",
                    Text.unparsed("gifts", giftCount(waiting))), 300);
        }
        n = pg * pageSize;
        for (MailStore.Letter l : shown) {
            n++;
            final int num = n;
            final long id = l.id;
            Component name = nameOf(l.from, l.fromName, l.fromDisplay);
            if (l.from != null) {
                menu.button(text.msg("mail.screen.reply", Text.unparsed("n", num)), text.msg("mail.screen.reply-tooltip", Text.component("name", name)), 150,
                        (ServerPlayer p) -> openCompose(p, l.from, l.fromName, pg));
            } else {
                menu.button(text.msg("mail.screen.no-reply", Text.unparsed("n", num)), null, 150, (ServerPlayer p) -> open(p, pg));
            }
            menu.button(text.msg("mail.screen.delete", Text.unparsed("n", num)), text.msg("mail.screen.delete-tooltip", Text.component("name", name)), 150,
                    (ServerPlayer p) -> {
                        delete(p, id);
                        open(p, pg);
                    });
            if (!l.read) {
                l.read = true;
                changed = true;
            }
        }
        menu.button(text.msg("mail.screen.write"), text.msg("mail.screen.write-tooltip"), 150, (ServerPlayer p) -> openWrite(p, ""));
        if (!letters.isEmpty()) menu.button(text.msg("mail.screen.clear"), text.msg("mail.screen.clear-tooltip"), 150, (ServerPlayer p) -> confirmClear(p));
        if (pg > 0) menu.button(text.msg("mail.screen.prev"), null, 150, (ServerPlayer p) -> open(p, pg - 1));
        if (pg < pages - 1) menu.button(text.msg("mail.screen.next"), null, 150, (ServerPlayer p) -> open(p, pg + 1));
        menu.columns(2);
        plugin.exitButton(menu, player);
        menu.open(player);
        text.sound(player, "menu-open");
        // Read flags need no write-before-act: the mail tick writes them in the background.
        if (changed) store.markDirty();
    }

    String giftCount(int n) {
        return text().raw(n == 1 ? "mail.count.gift" : "mail.count.gifts").replace("<count>", String.valueOf(n));
    }

    String letterCount(int n) {
        return text().raw(n == 1 ? "mail.count.letter" : "mail.count.letters").replace("<count>", String.valueOf(n));
    }

    private void delete(ServerPlayer p, long id) {
        if (closed(p)) return;
        MailStore.Box box = store.find(p.getUUID());
        if (box == null) return;
        if (box.letters.removeIf(l -> l.id == id)) {
            store.markDirty();
            text().send(p, "mail.deleted");
            text().sound(p, "deleted");
        }
    }

    void confirmClear(ServerPlayer player) {
        if (closed(player)) return;
        Text text = text();
        MailStore.Box box = store.find(player.getUUID());
        int count = box == null ? 0 : box.letters.size();
        if (count == 0) {
            text.send(player, "mail.clear-none");
            return;
        }
        Dialogs.Menu menu = dialogs().menu(text.msg("mail.clear.title"));
        menu.text(text.msg(count == 1 ? "mail.clear.body-one" : "mail.clear.body", Text.unparsed("count", count)), 300);
        menu.button(text.msg("mail.clear.confirm"), null, 150, (ServerPlayer p) -> {
            MailStore.Box b = store.find(p.getUUID());
            if (b == null || closed(p)) return;
            int n = b.letters.size();
            b.letters.clear();
            store.markDirty();
            text.send(p, "mail.cleared", Text.unparsed("count", n));
            text.sound(p, "deleted");
            open(p, 0);
        });
        menu.columns(1);
        menu.exit(text.msg("menu.back"), p -> open(p, 0));
        menu.open(player);
    }

    private Dialogs.TextField messageInput() {
        return Dialogs.Input.text("message", text().msg("mail.compose.message")).maxLength(maxLetterLength).width(300)
                .multiline(6, 80).build();
    }

    /** Reply: one box for the letter, Send, ← Back to the Mail screen. */
    void openCompose(ServerPlayer player, UUID to, String toName, int page) {
        Text text = text();
        OfflinePlayer target = offline(to, toName);
        Component name = recipient(target, toName);
        Dialogs.Menu menu = dialogs().menu(text.msg("mail.compose.title", Text.component("name", name)));
        menu.text(text.msg("mail.compose.body", Text.unparsed("max", maxLetterLength)), 300);
        menu.input(messageInput());
        menu.button(text.msg("mail.compose.send"), null, 150, (ServerPlayer p, Dialogs.Response view) -> {
            String msg = view == null ? null : view.getText("message");
            if (sendLetter(p, target, toName, msg)) open(p, page);
        });
        menu.columns(1);
        menu.exit(text.msg("menu.back"), p -> open(p, page));
        menu.open(player);
    }

    /** Write a Letter: who to and what. */
    void openWrite(ServerPlayer player, String initialTo) {
        Text text = text();
        Dialogs.Menu menu = dialogs().menu(text.msg("mail.write.title"));
        menu.text(text.msg("mail.compose.body", Text.unparsed("max", maxLetterLength)), 300);
        menu.input(Dialogs.Input.text("to", text.msg("mail.write.to")).initial(initialTo == null ? "" : initialTo).maxLength(32).width(200).build());
        menu.input(messageInput());
        menu.button(text.msg("mail.compose.send"), null, 150, (ServerPlayer p, Dialogs.Response view) -> {
            String who = view == null ? null : view.getText("to");
            String msg = view == null ? null : view.getText("message");
            if (who == null || who.isBlank()) {
                error(p, "mail.write.no-name");
                return;
            }
            OfflinePlayer t = target(p, who.trim());
            if (t == null) return;
            if (sendLetter(p, t, who.trim(), msg)) open(p, 0);
        });
        menu.columns(1);
        menu.exit(text.msg("menu.back"), p -> open(p, 0));
        menu.open(player);
    }

    // ------------------------------------------------------------------ join

    /**
     * Join digest (order 30): the one mail line, clickable. The digest waits while the player still has to type the
     * server code (they get nothing until then) and out the death screen (unsent gifts handed back to a dead player
     * would vanish when they respawn), so unsent gifts go back here first.
     */
    private void collectJoin(ServerPlayer p, List<JoinDigest.Line> out) {
        if (store.unreadable || plugin.authPending(p) || p.isDeadOrDying()) return;
        UUID id = p.getUUID();
        gifts.returnDrafts(p);
        int letters = unread(id);
        int waiting = waitingGifts(id);
        if (letters == 0 && waiting == 0) return;
        String key;
        if (waiting == 0) key = "mail.join.letters";
        else if (letters == 0) key = mailboxes.any() ? "mail.join.gifts" : "mail.join.gifts-no-mailbox";
        else key = mailboxes.any() ? "mail.join.both" : "mail.join.both-no-mailbox";
        Component line = JoinDigest.render(key, Text.unparsed("letters", letterCount(letters)), Text.unparsed("gifts", giftCount(waiting)));
        if (line != null) out.add(new JoinDigest.Line(30, clickable(line), "message"));
    }

    // ------------------------------------------------------------------ /mail

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = text();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("mailbox")) {
            if (!sender.hasPermission(ADMIN)) {
                error(sender, "general.no-permission");
                return true;
            }
            mailboxes.command(sender, args);
            return true;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        switch (sub) {
            case "", "read", "open", "inbox" -> open(p, 0);
            case "send", "write" -> {
                if (args.length < 3) {
                    if (args.length == 2) {
                        OfflinePlayer t = target(p, args[1]);
                        if (t != null) openCompose(p, t.getUniqueId(), nameOf(t, args[1]), 0);
                        return true;
                    }
                    text.send(p, "mail.usage-send");
                    return true;
                }
                OfflinePlayer t = target(p, args[1]);
                if (t == null) return true;
                sendLetter(p, t, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "gift" -> {
                if (!Perms.has(p, GIFT)) {
                    error(p, "general.no-permission");
                    return true;
                }
                if (args.length < 2) {
                    text.send(p, "mail.usage-gift");
                    return true;
                }
                OfflinePlayer t = target(p, args[1]);
                if (t != null) gifts.start(p, t, nameOf(t, args[1]));
            }
            case "gifts", "pickup" -> gifts.openNearby(p);
            case "clear" -> confirmClear(p);
            default -> text.send(p, Perms.has(p, ADMIN) ? "mail.help-admin" : "mail.help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("send", "gift", "gifts", "clear"));
            if (sender.hasPermission(ADMIN)) subs.add("mailbox");
            for (String s : subs) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("send") || args[0].equalsIgnoreCase("gift"))) {
            out.addAll(plugin.lookup().complete(sender.player(), args[1], true));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mailbox") && sender.hasPermission(ADMIN)) {
            for (String s : List.of("create", "remove", "list")) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }
}
