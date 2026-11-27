package net.axther.aftrhrs.feature.bank;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.net.Companion;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The coin purse for the Companion ({@code wallet} feature, docs/companion-protocol.md): a modded player's own bank
 * balance, coins waiting for them, the next interest payout, and the history lines that moved the balance, as
 * plain-text data for the wallet HUD. Every history line only queues; a once-a-second task sends one message per
 * player, so a burst (Sell All, interest for everyone) is one message with lines of the same kind summed.
 *
 * <p>Nothing here can be asked for: the client only listens. A player only ever hears about their own account, and only
 * what {@code /balance} and the bank's History page already show them.
 */
final class WalletCompanion {
    private static final String FEATURE = "wallet";
    /** The /bank permission: no bank screen, no purse. */
    private static final String PERM = "feather.bank";
    private static final int MAX_LINES = 5;
    /** History lines kept per player between flushes (a runaway loop can't grow this without bound). */
    private static final int MAX_QUEUED = 256;

    /** The running purse (Companion hooks can't be unregistered; they check this). */
    private static volatile WalletCompanion active;

    private final BankModule bank;
    /** History lines since the last message, oldest first, per online modded player. */
    private final Map<UUID, List<BankStore.Tx>> pending = new HashMap<>();
    /** What the last message said (balance, pending, interest), to skip one that would change nothing. */
    private final Map<UUID, String> sent = new HashMap<>();
    /** The balance in the last message: each queued line counts for what it moved the balance from there. */
    private final Map<UUID, Long> sentBalance = new HashMap<>();
    /** Look again at these players on the next flush (a reload may have changed interest). */
    private final Set<UUID> dirty = new HashSet<>();
    /** messages.yml bank.history.* as plain text, per history type. */
    private final Map<String, String> labels = new HashMap<>();
    private Tasks.Task task;
    /** The next interest payout time the last flush saw (-1: interest off; MIN_VALUE: not looked yet). */
    private long lastNext = Long.MIN_VALUE;

    WalletCompanion(BankModule bank) {
        this.bank = bank;
    }

    void start() {
        active = this;
        bank.plugin.once("bank.wallet", () -> Companion.onQuit(p -> {
            WalletCompanion w = active;
            if (w != null) w.forget(p.getUUID());
        }));
        task = Tasks.timer(20L, 20L, this::flush);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        if (active == this) active = null;
        pending.clear();
        sent.clear();
        sentBalance.clear();
        dirty.clear();
    }

    private void forget(UUID id) {
        pending.remove(id);
        sent.remove(id);
        sentBalance.remove(id);
        dirty.remove(id);
    }

    /** The bank settings were reloaded: labels and interest may differ, so every modded player is looked at again. */
    void reloaded() {
        labels.clear();
        for (ServerPlayer p : bank.plugin.onlinePlayers()) if (Companion.has(p, FEATURE)) dirty.add(p.getUUID());
    }

    /** BankStore#record: a history line on {@code a} (server thread). Queued only for its online, modded owner. */
    void recorded(BankStore.Account a, BankStore.Tx tx) {
        ServerPlayer p = bank.plugin.player(a.id);
        if (p == null || !Companion.has(p, FEATURE)) return;
        List<BankStore.Tx> q = pending.computeIfAbsent(a.id, k -> new ArrayList<>());
        if (q.size() >= MAX_QUEUED) q.removeFirst();
        q.add(tx);
    }

    private void flush() {
        // The payout hour passed: everyone's countdown moves on, also for players whose payout was 0 (no history line).
        long payout = bank.interestOn ? bank.nextPayout() : -1;
        if (payout != lastNext) {
            if (lastNext != Long.MIN_VALUE) {
                for (ServerPlayer p : bank.plugin.onlinePlayers()) if (sent.containsKey(p.getUUID())) dirty.add(p.getUUID());
            }
            lastNext = payout;
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : bank.plugin.onlinePlayers()) {
            UUID id = p.getUUID();
            online.add(id);
            boolean first = !sent.containsKey(id);
            if (!first && !pending.containsKey(id) && !dirty.contains(id)) continue;
            List<BankStore.Tx> txs = pending.remove(id);
            if (!Companion.has(p, FEATURE) || !Perms.has(p, PERM) || !bank.isOpen() || bank.plugin.authPending(p)) {
                // Nothing goes out; once they may see it again, the next message is a fresh snapshot.
                sent.remove(id);
                sentBalance.remove(id);
                continue;
            }
            BankStore.Account a = bank.store.find(id);
            long balance = a == null ? 0 : a.balance;
            long waiting = a == null ? 0 : a.pending;
            JsonObject msg = Companion.message("wallet");
            msg.addProperty("bal", balance);
            msg.addProperty("pending", waiting);
            String key = balance + "|" + waiting;
            if (bank.interestOn) {
                long next = bank.nextPayout();
                long about = bank.interestFor(balance);
                msg.addProperty("interestIn", Math.max(0, next - System.currentTimeMillis()));
                msg.addProperty("interestAbout", about);
                key += "|" + next + "|" + about;
            }
            // The first message after the handshake is a snapshot: the client has nothing to count up from yet.
            JsonArray lines = first || txs == null ? new JsonArray() : lines(txs, sentBalance.getOrDefault(id, balance));
            if (!lines.isEmpty()) msg.add("tx", lines);
            if (!first && lines.isEmpty() && key.equals(sent.get(id))) continue;
            sent.put(id, key);
            sentBalance.put(id, balance);
            Companion.send(p, msg);
        }
        dirty.clear();
        // Lines for players who left (or never got this far) don't wait for anyone.
        pending.keySet().removeIf(id -> !online.contains(id));
    }

    /**
     * The queued lines as the client shows them: each counts for what it moved the balance (so lines that only touched
     * the player's pocket, like buying coins with diamonds, robbery loot or collected coins, count for nothing and are
     * left out), lines of one type are summed, and at most {@link #MAX_LINES} types go out, the biggest ones, in order.
     */
    private JsonArray lines(List<BankStore.Tx> txs, long from) {
        record Sum(String type, long amount, String note, int count) {
        }
        Map<String, Sum> byType = new LinkedHashMap<>();
        long before = from;
        for (BankStore.Tx t : txs) {
            long moved = t.balance() - before;
            before = t.balance();
            if (moved == 0) continue;
            byType.merge(t.type(), new Sum(t.type(), moved, t.note(), 1),
                    (x, y) -> new Sum(x.type(), x.amount() + y.amount(), y.note(), x.count() + y.count()));
        }
        List<Sum> sums = new ArrayList<>();
        for (Sum s : byType.values()) if (s.amount() != 0) sums.add(s);
        if (sums.size() > MAX_LINES) {
            List<Sum> biggest = new ArrayList<>(sums);
            biggest.sort((x, y) -> Long.compare(Math.abs(y.amount()), Math.abs(x.amount())));
            Set<Sum> keep = new HashSet<>(biggest.subList(0, MAX_LINES));
            sums.removeIf(s -> !keep.contains(s));
        }
        JsonArray out = new JsonArray();
        for (Sum s : sums) {
            JsonObject o = new JsonObject();
            o.addProperty("type", s.type());
            o.addProperty("label", label(s.type()));
            o.addProperty("amount", s.amount());
            // A note belongs to one line; a merged burst has none.
            if (s.count() == 1 && !s.note().isBlank()) o.addProperty("note", s.note());
            out.add(o);
        }
        return out;
    }

    /** What the bank's History page calls this type (messages.yml bank.history.<type>), as plain text. */
    private String label(String type) {
        return labels.computeIfAbsent(type, t -> {
            List<String> raw = bank.text().variants("bank.history." + t.toLowerCase(Locale.ROOT));
            String plain = raw.isEmpty() ? "" : Adv.plain(bank.text().parse(raw.getFirst())).strip();
            return plain.isEmpty() ? titleCase(t) : plain;
        });
    }

    /** STALL-RENT -> "Stall Rent". */
    static String titleCase(String type) {
        StringBuilder b = new StringBuilder();
        for (String w : type.replace('-', ' ').replace('_', ' ').trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }
}
