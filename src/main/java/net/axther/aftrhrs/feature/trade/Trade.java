package net.axther.aftrhrs.feature.trade;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One open trade between two players. Each side's offer lives in its own 16-slot escrow container (the trade's store);
 * each player sees a {@link TradeMenu}: their offer on the left, the partner's mirrored on the right, Ready states and
 * the countdown. Any change to either offer un-readies both players at once, and the swap re-checks the offers against
 * what both agreed to (the old chest window's rules, now a real two-sided screen).
 */
final class Trade {
    /** One player's half: their offer, their open window and whether they're Ready. */
    final class Side {
        final UUID id;
        final String name;
        /** The escrow: any change to it un-readies both players. */
        final SimpleContainer offer = new SimpleContainer(TradeMenu.OFFER) {
            @Override
            public void setChanged() {
                super.setChanged();
                changed();
            }
        };
        TradeMenu menu;
        boolean ready;
        /** What the escrow file last recorded for this side (offer + cursor), to skip needless writes. */
        List<ItemStack> persisted = List.of();
        /** Their cursor, taken when their window closed (so vanilla never drops it); given back with the offer. */
        final List<ItemStack> extra = new ArrayList<>();

        Side(ServerPlayer p) {
            this.id = p.getUUID();
            this.name = Names.name(p);
        }

        Side other() {
            return this == a ? b : a;
        }

        ServerPlayer player() {
            return module.plugin().player(id);
        }

        Trade trade() {
            return Trade.this;
        }

        /** The offer as it stands, slot by slot (empty stacks keep their place), for comparing. */
        List<ItemStack> offer() {
            List<ItemStack> out = new ArrayList<>(TradeMenu.OFFER);
            for (int i = 0; i < TradeMenu.OFFER; i++) out.add(offer.getItem(i).copy());
            return out;
        }

        /** The offered items (no gaps). */
        List<ItemStack> items() {
            return Stacks.copies(offer());
        }

        /** Empties the offer and hands back what was in it. */
        List<ItemStack> takeAll() {
            List<ItemStack> out = items();
            for (int i = 0; i < TradeMenu.OFFER; i++) offer.setItem(i, ItemStack.EMPTY);
            return out;
        }

        boolean viewing(ServerPlayer p) {
            return p != null && menu != null && p.containerMenu == menu;
        }

        /** What the trade holds for this player right now: the offer plus their cursor while the window is open. */
        List<ItemStack> held() {
            List<ItemStack> out = items();
            for (ItemStack it : extra) out.add(it.copy());
            ServerPlayer p = player();
            if (viewing(p) && !menu.getCarried().isEmpty()) out.add(menu.getCarried().copy());
            return out;
        }

        /** The viewer's synced values: their Ready, the partner's Ready, the countdown. */
        ContainerData state() {
            return new ContainerData() {
                @Override
                public int get(int i) {
                    return switch (i) {
                        case TradeMenu.DATA_MY_READY -> ready ? 1 : 0;
                        case TradeMenu.DATA_THEIR_READY -> other().ready ? 1 : 0;
                        default -> countdown;
                    };
                }

                @Override
                public void set(int i, int value) {
                }

                @Override
                public int getCount() {
                    return 3;
                }
            };
        }
    }

    final TradeModule module;
    final Side a;
    final Side b;
    boolean ended;
    /** Seconds left on the countdown, or -1 while it isn't running. */
    private int countdown = -1;
    private Tasks.Task countdownTask;
    private boolean persistQueued;
    private List<ItemStack> agreedA, agreedB;

    Trade(TradeModule module, ServerPlayer first, ServerPlayer second) {
        this.module = module;
        this.a = new Side(first);
        this.b = new Side(second);
    }

    List<Side> sides() {
        return List.of(a, b);
    }

    Side side(UUID id) {
        return a.id.equals(id) ? a : b.id.equals(id) ? b : null;
    }

    private Text text() {
        return module.text();
    }

    /** Opens both windows. False when one of them couldn't be opened (nothing is held yet). */
    boolean open() {
        ServerPlayer pa = a.player(), pb = b.player();
        if (pa == null || pb == null) return false;
        openFor(a, pa, pb);
        openFor(b, pb, pa);
        return a.viewing(pa) && b.viewing(pb);
    }

    private void openFor(Side me, ServerPlayer p, ServerPlayer partner) {
        // The title and the partner's label sit on light backgrounds: the plain nickname, not its colours.
        String partnerName = Names.plain(partner);
        net.kyori.adventure.text.Component title = text().msg("trade.gui.title", Text.unparsed("player", partnerName));
        TradeMenu.Data data = new TradeMenu.Data(partner.getUUID(), Names.name(partner), labels(partnerName));
        p.openMenu(new ExtendedMenuProvider<TradeMenu.Data>() {
            @Override
            public TradeMenu.Data getScreenOpeningData(ServerPlayer player) {
                return data;
            }

            @Override
            public Component getDisplayName() {
                return Adv.vanilla(title);
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                TradeMenu m = new TradeMenu(id, inv, data, me.offer, me.other().offer, me.state(), me);
                me.menu = m;
                return m;
            }
        });
    }

    /** messages.yml's trade.gui labels, in {@link TradeMenu}'s L_* order. */
    private List<Component> labels(String partnerName) {
        Text t = text();
        net.kyori.adventure.text.Component how = net.kyori.adventure.text.Component.empty();
        boolean first = true;
        for (net.kyori.adventure.text.Component line : t.lines("trade.gui.how-lore")) {
            if (!first) how = how.append(net.kyori.adventure.text.Component.newline());
            how = how.append(line);
            first = false;
        }
        return List.of(
                Adv.vanilla(t.msg("trade.gui.you")),
                Adv.vanilla(t.msg("trade.gui.them", Text.unparsed("player", partnerName))),
                Adv.vanilla(t.msg("trade.gui.ready")),
                Adv.vanilla(t.msg("trade.gui.unready")),
                Adv.vanilla(t.msg("trade.gui.cancel")),
                Adv.vanilla(t.msg("trade.gui.state-ready")),
                Adv.vanilla(t.msg("trade.gui.state-waiting")),
                Adv.vanilla(t.msg("trade.gui.how")),
                Adv.vanilla(how));
    }

    // ------------------------------------------------------------------ clicks

    /** A Ready / Cancel button click from {@code me}'s window. */
    void button(Side me, ServerPlayer p, int id) {
        if (ended || !me.id.equals(p.getUUID()) || !me.viewing(p)) return;
        if (id != TradeMenu.BUTTON_READY && id != TradeMenu.BUTTON_CANCEL) return;
        text().sound(p, "click");
        // After the click packet: never close windows from inside it.
        Tasks.sync(() -> {
            if (ended) return;
            if (id == TradeMenu.BUTTON_READY) toggleReady(me);
            else module.end(this, "cancelled", me, null);
        });
    }

    /** {@code me}'s window closed (Esc, another menu, death or a disconnect). */
    void closed(Side me, ServerPlayer p) {
        if (ended) return;
        if (p.hasDisconnected()) module.end(this, "left", me, p);
        else if (p.isDeadOrDying()) module.end(this, "died", me, p);
        else module.end(this, "closed", me, p);
    }

    /** An offer may have changed: nobody is Ready any more and the countdown stops; a save is queued. */
    void changed() {
        if (ended) return;
        boolean wasReady = a.ready || b.ready;
        a.ready = false;
        b.ready = false;
        stopCountdown();
        if (wasReady) {
            for (Side s : sides()) text().sound(s.player(), "cancel");
        }
        queuePersist();
    }

    /** Saves what the trade holds a moment later, so a burst of clicks is one write. */
    void queuePersist() {
        if (persistQueued) return;
        persistQueued = true;
        Tasks.later(TradeModule.PERSIST_DELAY, () -> {
            persistQueued = false;
            if (!ended) module.persistIfChanged(this);
        });
    }

    private void toggleReady(Side me) {
        if (!me.ready && a.items().isEmpty() && b.items().isEmpty()) {
            text().send(me.player(), "trade.empty");
            text().sound(me.player(), "error");
            return;
        }
        me.ready = !me.ready;
        if (me.ready && me.other().ready) startCountdown();
        else stopCountdown();
    }

    // ------------------------------------------------------------------ countdown and swap

    private void startCountdown() {
        stopCountdown();
        agreedA = a.offer();
        agreedB = b.offer();
        countdown = Math.max(1, module.countdownSeconds());
        for (Side s : sides()) text().sound(s.player(), "warmup-tick");
        countdownTask = Tasks.timer(20L, 20L, () -> {
            if (ended || countdown < 0) return;
            countdown--;
            if (countdown <= 0) {
                complete();
                return;
            }
            for (Side s : sides()) text().sound(s.player(), "warmup-tick");
        });
    }

    void stopCountdown() {
        countdown = -1;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    /** The countdown ran out: check, then swap both offers in one go. */
    private void complete() {
        stopCountdown();
        if (ended) return;
        if (!a.ready || !b.ready || !Stacks.same(a.offer(), agreedA) || !Stacks.same(b.offer(), agreedB)) {
            changed();
            return;
        }
        if (!module.validate(this)) return;
        ServerPlayer pa = a.player(), pb = b.player();
        boolean fitA = Stacks.fits(pa, withCursor(b.items(), a)), fitB = Stacks.fits(pb, withCursor(a.items(), b));
        if (!fitA || !fitB) {
            a.ready = false;
            b.ready = false;
            for (Side s : sides()) {
                boolean mine = s == a ? !fitA : !fitB;
                boolean theirs = s == a ? !fitB : !fitA;
                ServerPlayer sp = s.player();
                if (mine) text().send(sp, "trade.full-you");
                if (theirs) text().send(sp, "trade.full-them", Text.component("player", Names.display(s.other().player())));
                text().sound(sp, "error");
            }
            return;
        }
        module.complete(this, pa, pb);
    }

    /** The items plus whatever is on the player's cursor (it goes back into their inventory with them). */
    private static List<ItemStack> withCursor(List<ItemStack> items, Side s) {
        List<ItemStack> out = new ArrayList<>(items);
        if (s.menu != null && !s.menu.getCarried().isEmpty()) out.add(s.menu.getCarried().copy());
        return out;
    }
}
