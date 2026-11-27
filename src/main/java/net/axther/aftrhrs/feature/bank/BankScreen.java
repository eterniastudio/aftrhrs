package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The bank screen: a dialog in the style of the hub. The top shows the coin beside the player's balance, the interest
 * rate, the next payout and what they carry; below, two per row, Buy, Sell, Deposit, Withdraw, History and Interest.
 * Each action opens a small form with an amount box; every click is checked again by {@link BankModule}.
 */
final class BankScreen {
    private static final int W = 150;
    private final BankModule bank;

    BankScreen(BankModule bank) {
        this.bank = bank;
    }

    private Text text() {
        return bank.text();
    }

    private TagResolver n(String key, long v) {
        return bank.n(key, v);
    }

    /** Opens the bank at {@code pos} (also hands out pending coins first). */
    void open(ServerPlayer p, BankStore.Pos pos, boolean quiet) {
        if (bank.closed(p)) return;
        if (!Perms.has(p, "feather.bank")) {
            bank.error(p, "general.no-permission");
            return;
        }
        bank.sessions.put(p.getUUID(), pos);
        bank.deliverPending(p, true);
        BankStore.Account a = bank.store.find(p.getUUID());
        long balance = a == null ? 0 : a.balance;
        long pending = a == null ? 0 : a.pending;
        Dialogs.Menu menu = bank.dialogs.menu(text().msg("bank.menu.title"));
        Component info = text().msg(pending > 0 ? "bank.menu.info-pending" : "bank.menu.info",
                Text.component("name", Names.display(p)), n("balance", balance), Text.unparsed("rate", bank.rateText()),
                n("cap", bank.interestCap), Text.unparsed("next", BankModule.until(bank.nextPayout())),
                n("interest", bank.interestFor(balance)), n("pocket", bank.coins.count(p)),
                n("diamonds", BankModule.countDiamonds(p)), n("pending", pending));
        menu.item(bank.coins.one(), info, 250);
        menu.text(text().msg("bank.menu.rates", n("buy", bank.buyRate), n("sell", bank.sellRate)), 300);
        menu.button(text().msg("bank.menu.buy"), text().msg("bank.menu.buy-tooltip", n("buy", bank.buyRate)), W, this::openBuy);
        menu.button(text().msg("bank.menu.sell"), text().msg("bank.menu.sell-tooltip", n("sell", bank.sellRate)), W, this::openSell);
        menu.button(text().msg("bank.menu.deposit"), text().msg("bank.menu.deposit-tooltip"), W, this::openDeposit);
        menu.button(text().msg("bank.menu.withdraw"), text().msg("bank.menu.withdraw-tooltip"), W, this::openWithdraw);
        menu.button(text().msg("bank.menu.history"), text().msg("bank.menu.history-tooltip"), W, this::openHistory);
        menu.button(text().msg("bank.menu.interest"), text().msg("bank.menu.interest-tooltip"), W, this::openInterest);
        menu.columns(2);
        menu.exit(text().msg("menu.close"), null);
        menu.open(p);
        if (!quiet) text().sound(p, "menu-open");
    }

    private void back(ServerPlayer p) {
        BankStore.Pos pos = bank.sessions.get(p.getUUID());
        if (pos != null) open(p, pos, true);
    }

    /** One action: a line about what you have, an amount box and a confirm button; back to the bank afterwards. */
    private void form(ServerPlayer p, String key, long suggested, BiPredicate<ServerPlayer, String> action, TagResolver... r) {
        if (!bank.atBank(p)) return;
        Dialogs.Menu menu = bank.dialogs.menu(text().msg("bank.menu." + key + "-title"));
        menu.text(text().msg("bank.menu." + key + "-body", r), 300);
        menu.input(Dialogs.Input.text("amount", text().msg("bank.menu.amount"))
                .initial(String.valueOf(Math.max(0, suggested))).maxLength(16).width(200).build());
        menu.button(text().msg("bank.menu." + key + "-confirm"), null, W, (pl, view) -> {
            String raw = view == null ? null : view.getText("amount");
            action.test(pl, raw);
            // Back to the bank with the new numbers (unless they walked off or started robbing it).
            if (bank.nearBank(pl) && !bank.robberies.isRobber(pl)) back(pl);
        });
        menu.button(text().msg("menu.back"), null, W, this::back);
        menu.columns(2);
        menu.open(p);
    }

    private void openBuy(ServerPlayer p) {
        long have = BankModule.countDiamonds(p);
        long max = Math.min(have, bank.maxDiamondsPerTrade);
        form(p, "buy", max, bank::buy, n("have", have), n("rate", bank.buyRate), n("max", max), n("coins", max * bank.buyRate));
    }

    private void openSell(ServerPlayer p) {
        long have = bank.coins.count(p);
        long max = Math.min(have / bank.sellRate, bank.maxDiamondsPerTrade);
        form(p, "sell", max, bank::sell, n("have", have), n("rate", bank.sellRate), n("max", max));
    }

    private void openDeposit(ServerPlayer p) {
        long have = bank.coins.count(p);
        form(p, "deposit", have, bank::deposit, n("have", have), Text.unparsed("fee", feeText(bank.depositFeePercent)));
    }

    private void openWithdraw(ServerPlayer p) {
        BankStore.Account a = bank.store.find(p.getUUID());
        long bal = a == null ? 0 : a.balance;
        long room = bank.coins.room(p);
        form(p, "withdraw", Math.min(bal, room), bank::withdraw, n("balance", bal), n("room", room),
                Text.unparsed("fee", feeText(bank.withdrawFeePercent)));
    }

    private static String feeText(double pct) {
        return pct <= 0 ? "no fee" : java.math.BigDecimal.valueOf(pct).stripTrailingZeros().toPlainString() + "% fee";
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US);

    private void openHistory(ServerPlayer p) {
        if (!bank.atBank(p)) return;
        BankStore.Account a = bank.store.find(p.getUUID());
        Dialogs.Menu menu = bank.dialogs.menu(text().msg("bank.menu.history-title"));
        if (a == null || a.history.isEmpty()) {
            menu.text(text().msg("bank.menu.history-empty"), 300);
        } else {
            ZoneId zone = bank.zone;
            Component lines = Component.empty();
            boolean first = true;
            for (BankStore.Tx t : a.history) {
                if (!first) lines = lines.append(Component.newline());
                first = false;
                String key = "bank.history." + t.type().toLowerCase(Locale.ROOT);
                lines = lines.append(text().msg(t.amount() >= 0 ? "bank.menu.history-line-plus" : "bank.menu.history-line-minus",
                        Text.unparsed("when", WHEN.format(Instant.ofEpochMilli(t.time()).atZone(zone))),
                        Text.component("what", text().msg(key)), n("amount", Math.abs(t.amount())), n("balance", t.balance()),
                        Text.unparsed("note", t.note())));
            }
            menu.text(lines, 300);
        }
        menu.button(text().msg("menu.back"), null, W, this::back);
        menu.columns(1);
        menu.open(p);
    }

    private void openInterest(ServerPlayer p) {
        if (!bank.atBank(p)) return;
        BankStore.Account a = bank.store.find(p.getUUID());
        long bal = a == null ? 0 : a.balance;
        Dialogs.Menu menu = bank.dialogs.menu(text().msg("bank.menu.interest-title"));
        menu.text(text().msg("bank.menu.interest-body", Text.unparsed("rate", bank.rateText()), n("cap", bank.interestCap),
                Text.unparsed("hour", String.format(Locale.ROOT, "%02d:00", bank.interestHour)), Text.unparsed("zone", bank.zone.getId()),
                Text.unparsed("next", BankModule.until(bank.nextPayout())), n("interest", bank.interestFor(bal)),
                Text.unparsed("days", bank.activeDays), n("balance", bal)), 300);
        menu.button(text().msg("menu.back"), null, W, this::back);
        menu.columns(1);
        menu.open(p);
    }

    /**
     * Big payments ask first, and so does a name that only matched by nickname or prefix ({@code typed}: what was
     * typed, else null). The click re-checks everything.
     */
    void confirmPay(ServerPlayer p, UUID to, String toName, long amount, String typed) {
        Dialogs.Menu menu = bank.dialogs.menu(text().msg("bank.pay.confirm-title"));
        menu.text(text().msg(typed == null ? "bank.pay.confirm-body" : "bank.pay.confirm-body-match", n("amount", amount),
                Text.unparsed("player", toName), Text.unparsed("input", typed == null ? "" : typed)), 300);
        menu.button(text().msg("bank.pay.confirm-yes"), null, W, pl -> bank.pay(pl, to, amount, true));
        menu.button(text().msg("bank.pay.confirm-no"), null, W, pl -> text().send(pl, "bank.pay.cancelled"));
        menu.columns(2);
        menu.open(p);
    }

    /** /bank give|take|set with a name that matched by nickname or prefix: staff confirm the player first. */
    void confirmAdmin(ServerPlayer p, String sub, UUID id, String name, long amount, String typed) {
        Dialogs.Menu menu = bank.dialogs.menu(text().msg("bank.admin.confirm-title"));
        menu.text(text().msg("bank.admin.confirm-body", Text.unparsed("action", sub), n("amount", amount), Text.unparsed("player", name),
                Text.unparsed("input", typed)), 300);
        menu.button(text().msg("bank.admin.confirm-yes"), null, W, pl -> {
            if (Perms.has(pl, BankModule.ADMIN)) bank.adminBalance(net.axther.aftrhrs.core.cmd.Sender.of(pl), sub, id, amount);
        });
        menu.button(text().msg("bank.pay.confirm-no"), null, W, pl -> text().send(pl, "bank.admin.cancelled"));
        menu.columns(2);
        menu.open(p);
    }
}
