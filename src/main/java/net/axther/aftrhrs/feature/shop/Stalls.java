package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.JoinDigest;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Market stalls. Staff register an {@code arcana_market_stall_*} furniture block with {@code /stall create <name>}; a
 * player rents it for a week at a time from their bank balance ({@code /stall rent} or a right-click), stocks up to
 * {@code max-listings} listings from their hand with a price each, and other players buy from it with a right-click.
 * Proceeds go into the owner's balance (a letter sums up sales made while they were offline). When the rent runs out
 * (or the owner leaves) the unsold stock is mailed back. Once a stall keeper stands ({@link Keepers}), its chest screens
 * ({@link StallMenus}) replace the dialogs below; they call the same checked methods.
 *
 * <p>Dupe safety: stock lives only in {@code data/shop.yml}, never in a real inventory. Adding takes the item out of
 * the hand first (and only if it is still exactly what the screen showed), then saves; taking back and buying save the
 * lower stock first and hand items over last; a failed save undoes the step before anything is handed out. Every click
 * re-checks the stall, the listing, the price (never above what the screen showed), the stock, both balances and the
 * inventory room at that moment.
 */
final class Stalls {
    private static final long WEEK = 7L * 86_400_000L;
    private static final int W = 150;
    /** Most items one listing holds (adding the same item again joins its listing). */
    static final int MAX_STOCK = 1_000_000;
    private final ShopModule shop;

    long rent = 50;
    int maxWeeksAhead = 4;
    int maxPerPlayer = 1;
    int maxListings = 18;
    long maxPrice = 100_000;
    double distance = 6;
    long noteMs = 10 * 60_000L;
    String mailFrom = "Market Stall";

    Stalls(ShopModule shop) {
        this.shop = shop;
    }

    void reload(Yaml c) {
        // ShopModule (enable and /feather reload) calls this; the join digest drops providers at server stop, and
        // collectJoin does nothing once this shop module stopped.
        JoinDigest.register("stalls", 50, this::collectJoin);
        if (c == null) return;
        rent = Math.max(0, Math.min(1_000_000_000L, c.getLong("rent", 50)));
        maxWeeksAhead = Math.max(1, Math.min(52, c.getInt("max-weeks-ahead", 4)));
        maxPerPlayer = Math.max(1, Math.min(54, c.getInt("max-per-player", 1)));
        maxListings = Math.max(1, Math.min(54, c.getInt("max-listings", 18)));
        maxPrice = Math.max(1, Math.min(1_000_000_000L, c.getLong("max-price", 100_000)));
        distance = Math.max(2, Math.min(32, c.getDouble("distance", 6)));
        noteMs = Math.max(1, c.getLong("offline-note-minutes", 10)) * 60_000L;
        mailFrom = c.getString("mail-from", "Market Stall");
    }

    private Text text() {
        return shop.text();
    }

    private ShopStore store() {
        return shop.store;
    }

    private static String name(ServerPlayer p) {
        return Names.name(p);
    }

    // ------------------------------------------------------------------ lookups

    /**
     * The registered stall of a furniture block ({@code spot} is its origin), or null. The stall's position is where the
     * Oraxen base entity stood on Paper, which is the origin block of the native piece; a stall saved in some other
     * spot of the same piece is still found within a block.
     */
    ShopStore.Stall byBlock(ShopItems.Spot spot) {
        if (spot == null || store().stalls.isEmpty() || !spot.id().startsWith(ShopModule.STALL_PREFIX)) return null;
        for (ShopStore.Stall s : store().stalls.values()) {
            if (Worlds.resolve(s.world) == spot.level() && s.block().equals(spot.pos())) return s;
        }
        for (ShopStore.Stall s : store().stalls.values()) {
            if (Worlds.resolve(s.world) != spot.level()) continue;
            if (Math.abs(s.x - (spot.pos().getX() + 0.5)) < 1.1 && Math.abs(s.y - spot.pos().getY()) < 1.1
                    && Math.abs(s.z - (spot.pos().getZ() + 0.5)) < 1.1) return s;
        }
        return null;
    }

    /** The registered stall nearest to the player within reach, or null. */
    ShopStore.Stall nearest(ServerPlayer p) {
        ShopStore.Stall best = null;
        double bestD = (distance + 1.5) * (distance + 1.5);
        for (ShopStore.Stall s : store().stalls.values()) {
            Loc l = s.location();
            if (l == null || l.level() != p.level()) continue;
            double dx = l.x() - p.getX(), dy = l.y() - p.getY(), dz = l.z() - p.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** At the stall, or at its keeper. */
    boolean near(ServerPlayer p, ShopStore.Stall s) {
        return ShopModule.near(p, s.location(), distance) || (s.hasKeeper() && ShopModule.near(p, s.keeperSpot(), distance));
    }

    private int owned(UUID id) {
        int n = 0;
        for (ShopStore.Stall s : store().stalls.values()) if (id.equals(s.owner)) n++;
        return n;
    }

    /** The stall as it is now (by id): null if it was removed in the meantime. */
    ShopStore.Stall current(String id) {
        return store().stall(id);
    }

    // ------------------------------------------------------------------ clicks

    void click(ServerPlayer p, ShopStore.Stall s) {
        if (!Perms.has(p, ShopModule.STALL_PERM)) {
            shop.error(p, "general.no-permission");
            return;
        }
        if (shop.closed(p)) return;
        if (!s.rented()) openRent(p, s);
        else if (p.getUUID().equals(s.owner)) {
            if (s.hasKeeper()) shop.menus.openSell(p, s, 0);
            else openManage(p, s);
        } else if (s.hasKeeper()) shop.menus.openBuy(p, s, 0);
        else openBrowse(p, s);
    }

    // ------------------------------------------------------------------ renting

    private void openRent(ServerPlayer p, ShopStore.Stall s) {
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.rent-title", Text.unparsed("name", s.name)));
        menu.text(text().msg("shop.stall.rent-body", shop.n("rent", rent), Text.unparsed("listings", maxListings), shop.n("balance", shop.balance(p))), 300);
        final long quoted = rent;
        menu.button(text().msg("shop.stall.rent-confirm", shop.n("rent", rent)), null, W, pl -> {
            if (rent(pl, s.id, quoted)) {
                ShopStore.Stall now = current(s.id);
                if (now != null && now.hasKeeper()) shop.menus.openSell(pl, now, 0);
                else if (now != null) openManage(pl, now);
            }
        });
        menu.button(text().msg("menu.close"), null, W, pl -> {
        });
        menu.columns(2);
        menu.open(p);
        text().sound(p, "menu-open");
    }

    boolean rent(ServerPlayer p, String stallId, long quoted) {
        if (shop.closed(p)) return false;
        ShopStore.Stall s = current(stallId);
        if (s == null) {
            shop.error(p, "shop.stall.gone");
            return false;
        }
        if (s.rented()) {
            shop.error(p, p.getUUID().equals(s.owner) ? "shop.stall.already-yours" : "shop.stall.taken");
            return false;
        }
        if (!near(p, s)) {
            shop.error(p, "shop.stall.too-far");
            return false;
        }
        if (owned(p.getUUID()) >= maxPerPlayer) {
            shop.error(p, "shop.stall.limit", Text.unparsed("max", maxPerPlayer));
            return false;
        }
        if (rent > quoted) {
            shop.error(p, "shop.store.price-changed", shop.n("price", rent), shop.n("was", quoted));
            return false;
        }
        Hooks.Bank bank = shop.bank();
        if (bank.robbing(p)) {
            shop.error(p, "bank.robbery.no-banking");
            return false;
        }
        if (rent > 0 && bank.debit(p.getUUID(), name(p), rent, "STALL-RENT", s.name) < 0) {
            shop.error(p, "shop.not-enough", shop.n("price", rent), shop.n("balance", bank.balance(p.getUUID())));
            return false;
        }
        long now = System.currentTimeMillis();
        s.owner = p.getUUID();
        s.ownerName = name(p);
        s.rentedAt = now;
        s.rentUntil = now + WEEK;
        if (!store().save()) {
            s.owner = null;
            s.ownerName = null;
            if (rent > 0) bank.credit(p.getUUID(), name(p), rent, "STALL-RENT", "refund: " + s.name);
            shop.error(p, "shop.save-failed");
            return false;
        }
        store().log("STALL-RENT " + s.id + " by " + name(p) + " " + p.getUUID() + " rent=" + rent + " until=" + s.rentUntil);
        text().send(p, "shop.stall.rented", Text.unparsed("name", s.name), shop.n("rent", rent), Text.unparsed("time", ShopModule.until(s.rentUntil)));
        text().sound(p, "accepted");
        shop.keepers.renamed(s);
        if (!s.hasKeeper() && shop.keepers.giveToken(p, s, false)) text().send(p, "shop.stall.keeper.given", Text.unparsed("name", s.name),
                Text.unparsed("distance", (int) shop.keepers.distance));
        return true;
    }

    boolean renew(ServerPlayer p, String stallId) {
        if (shop.closed(p)) return false;
        ShopStore.Stall s = current(stallId);
        if (s == null || !p.getUUID().equals(s.owner)) {
            shop.error(p, "shop.stall.not-yours");
            return false;
        }
        long now = System.currentTimeMillis();
        if (s.rentUntil + WEEK - now > maxWeeksAhead * WEEK) {
            shop.error(p, "shop.stall.ahead-limit", Text.unparsed("weeks", maxWeeksAhead));
            return false;
        }
        Hooks.Bank bank = shop.bank();
        if (bank.robbing(p)) {
            shop.error(p, "bank.robbery.no-banking");
            return false;
        }
        if (rent > 0 && bank.debit(p.getUUID(), name(p), rent, "STALL-RENT", s.name + " (+1 week)") < 0) {
            shop.error(p, "shop.not-enough", shop.n("price", rent), shop.n("balance", bank.balance(p.getUUID())));
            return false;
        }
        long before = s.rentUntil;
        s.rentUntil = Math.max(s.rentUntil, now) + WEEK;
        if (!store().save()) {
            s.rentUntil = before;
            if (rent > 0) bank.credit(p.getUUID(), name(p), rent, "STALL-RENT", "refund: " + s.name);
            shop.error(p, "shop.save-failed");
            return false;
        }
        store().log("STALL-RENEW " + s.id + " by " + name(p) + " " + p.getUUID() + " rent=" + rent + " until=" + s.rentUntil);
        text().send(p, "shop.stall.renewed", Text.unparsed("name", s.name), Text.unparsed("time", ShopModule.until(s.rentUntil)));
        text().sound(p, "accepted");
        return true;
    }

    // ------------------------------------------------------------------ the owner's screen

    void openManage(ServerPlayer p, ShopStore.Stall s) {
        if (shop.closed(p)) return;
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.manage-title", Text.unparsed("name", s.name)));
        menu.text(text().msg("shop.stall.manage-body", Text.unparsed("time", ShopModule.until(s.rentUntil)),
                Text.unparsed("count", s.listings.size()), Text.unparsed("max", maxListings), shop.n("rent", rent)), 300);
        int n = 0;
        for (ShopStore.Listing l : s.listings) {
            n++;
            ItemStack proto = l.prototype();
            if (proto == null) continue;
            menu.item(proto.copyWithCount(Math.min(proto.getMaxStackSize(), Math.max(1, l.stock))), text().msg("shop.stall.listing-own",
                    Text.unparsed("n", n), Text.component("item", ShopModule.nameOf(proto)), Text.unparsed("stock", l.stock), shop.n("price", l.price)), 250);
        }
        n = 0;
        for (ShopStore.Listing l : s.listings) {
            n++;
            final long lid = l.id;
            final int num = n;
            menu.button(text().msg("shop.stall.take-back", Text.unparsed("n", num)), null, W, pl -> {
                takeBack(pl, s.id, lid);
                reopenManage(pl, s.id);
            });
            menu.button(text().msg("shop.stall.reprice", Text.unparsed("n", num)), null, W, pl -> openPrice(pl, s.id, lid));
        }
        if (s.listings.size() < maxListings) menu.button(text().msg("shop.stall.add"), text().msg("shop.stall.add-tooltip"), W, pl -> openAdd(pl, s.id));
        menu.button(text().msg("shop.stall.renew", shop.n("rent", rent)), text().msg("shop.stall.renew-tooltip", Text.unparsed("weeks", maxWeeksAhead)), W, pl -> {
            renew(pl, s.id);
            reopenManage(pl, s.id);
        });
        menu.button(text().msg("shop.stall.leave"), text().msg("shop.stall.leave-tooltip"), W, pl -> confirmLeave(pl, s.id));
        menu.columns(2);
        menu.exit(text().msg("menu.close"), null);
        menu.open(p);
        text().sound(p, "menu-open");
    }

    private void reopenManage(ServerPlayer p, String id) {
        ShopStore.Stall s = current(id);
        if (s != null && p.getUUID().equals(s.owner) && near(p, s)) openManage(p, s);
    }

    /** The stall when this player may manage it right now (else null, and the player was told why). */
    ShopStore.Stall ownedHere(ServerPlayer p, String id) {
        if (shop.closed(p)) return null;
        ShopStore.Stall s = current(id);
        if (s == null || !p.getUUID().equals(s.owner)) {
            shop.error(p, "shop.stall.not-yours");
            return null;
        }
        if (s.rentUntil <= System.currentTimeMillis()) {
            shop.error(p, "shop.stall.expired");
            return null;
        }
        if (!near(p, s)) {
            shop.error(p, "shop.stall.too-far");
            return null;
        }
        return s;
    }

    private void openAdd(ServerPlayer p, String id) {
        ShopStore.Stall s = ownedHere(p, id);
        if (s == null) return;
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) {
            shop.error(p, "shop.stall.hold-item");
            return;
        }
        final ItemStack snapshot = held.copy();
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.add-title"));
        menu.item(snapshot, text().msg("shop.stall.add-body", Text.component("item", ShopModule.nameOf(snapshot)), Text.unparsed("count", snapshot.getCount()),
                shop.n("max", maxPrice)), 250);
        menu.input(Dialogs.Input.text("price", text().msg("shop.stall.price")).initial("").maxLength(12).width(150).build());
        menu.button(text().msg("shop.stall.add-confirm"), null, W, (pl, view) -> {
            addListing(pl, id, snapshot, view == null ? null : view.getText("price"));
            reopenManage(pl, id);
        });
        menu.button(text().msg("menu.back"), null, W, pl -> reopenManage(pl, id));
        menu.columns(2);
        menu.open(p);
    }

    /** Puts what is in the hand on the stall, only if it is exactly what the screen showed. */
    boolean addListing(ServerPlayer p, String id, ItemStack snapshot, String rawPrice) {
        return addFrom(p, id, p.getInventory().getSelectedSlot(), snapshot, rawPrice, "shop.stall.hand-changed");
    }

    /**
     * Puts the stack in one inventory slot (0-35) on the stall, only if it is still exactly what the screen showed. The
     * same item already on the stall gets the new stock added (at the new price); anything else is a new listing. The
     * slot is emptied first, then the stall is saved; a failed save puts the item back.
     */
    boolean addFrom(ServerPlayer p, String id, int slot, ItemStack snapshot, String rawPrice, String changedKey) {
        ShopStore.Stall s = ownedHere(p, id);
        if (s == null) return false;
        long price = ShopModule.parseAmount(rawPrice, -1);
        if (price <= 0 || price > maxPrice) {
            shop.error(p, "shop.stall.bad-price", shop.n("max", maxPrice));
            return false;
        }
        ItemStack held = slot < 0 || slot >= 36 ? null : p.getInventory().getItem(slot);
        if (held == null || held.isEmpty() || held.getCount() != snapshot.getCount() || !ShopModule.similar(held, snapshot)) {
            shop.error(p, changedKey);
            return false;
        }
        if (shop.keepers.isToken(held) || shop.menus.isPreview(held)) {
            shop.error(p, "shop.stall.keeper.cant-sell-token");
            return false;
        }
        ItemStack taken = held.copy();
        String encoded = ShopStore.encode(taken.copyWithCount(1));
        ShopStore.Listing same = sameListing(s, taken);
        if (same == null && encoded.isEmpty()) {
            shop.error(p, "shop.save-failed");
            return false;
        }
        if (same == null && s.listings.size() >= maxListings) {
            shop.error(p, "shop.stall.full", Text.unparsed("max", maxListings));
            return false;
        }
        if (same != null && (long) same.stock + taken.getCount() > MAX_STOCK) {
            shop.error(p, "shop.stall.stock-full", Text.unparsed("max", MAX_STOCK));
            return false;
        }
        ShopStore.Listing l = same != null ? same : new ShopStore.Listing(store().nextId++, encoded, 0, price);
        int beforeStock = l.stock;
        long beforePrice = l.price;
        p.getInventory().setItem(slot, ItemStack.EMPTY);
        l.stock += taken.getCount();
        l.price = price;
        if (same == null) s.listings.add(l);
        if (!store().save()) {
            if (same == null) s.listings.remove(l);
            l.stock = beforeStock;
            l.price = beforePrice;
            if (p.getInventory().getItem(slot).isEmpty()) p.getInventory().setItem(slot, taken);
            else ShopModule.give(p, List.of(taken));
            shop.error(p, "shop.save-failed");
            return false;
        }
        ShopModule.saveInventory(p);
        store().log("STALL-ADD " + s.id + " #" + l.id + " by " + name(p) + " " + p.getUUID() + " added=" + taken.getCount() + " stock=" + l.stock
                + " price=" + price + " item=" + l.item);
        if (same == null) text().send(p, "shop.stall.added", Text.unparsed("count", l.stock), Text.component("item", ShopModule.nameOf(taken)), shop.n("price", price));
        else text().send(p, "shop.stall.added-more", Text.unparsed("count", taken.getCount()), Text.component("item", ShopModule.nameOf(taken)),
                Text.unparsed("stock", l.stock), shop.n("price", price));
        text().sound(p, "accepted");
        shop.menus.refresh(s.id);
        return true;
    }

    /** The listing an item would join (the same item already on the stall), or null. */
    ShopStore.Listing sameListing(ShopStore.Stall s, ItemStack item) {
        for (ShopStore.Listing l : s.listings) {
            ItemStack proto = l.prototype();
            if (proto != null && ShopModule.similar(proto, item)) return l;
        }
        return null;
    }

    /** Takes a listing back (as much as fits; the rest stays listed). Saved before the items are handed over. */
    boolean takeBack(ServerPlayer p, String id, long listingId) {
        return takeBack(p, id, listingId, Integer.MAX_VALUE);
    }

    /** Takes back at most {@code limit} of a listing (as much as fits). */
    boolean takeBack(ServerPlayer p, String id, long listingId, int limit) {
        ShopStore.Stall s = ownedHere(p, id);
        if (s == null) return false;
        ShopStore.Listing l = s.listing(listingId);
        ItemStack proto = l == null ? null : l.prototype();
        if (l == null || proto == null) {
            shop.error(p, "shop.stall.listing-gone");
            return false;
        }
        int give = (int) Math.min(Math.min(l.stock, Math.max(1, limit)), ShopModule.room(p, proto));
        if (give <= 0) {
            shop.error(p, "shop.no-room");
            return false;
        }
        int before = l.stock;
        int index = s.listings.indexOf(l);
        l.stock -= give;
        if (l.stock <= 0) s.listings.remove(l);
        if (!store().save()) {
            l.stock = before;
            if (!s.listings.contains(l)) s.listings.add(index, l);
            shop.error(p, "shop.save-failed");
            return false;
        }
        ShopModule.give(p, ShopModule.stacks(proto, give));
        ShopModule.saveInventory(p);
        store().log("STALL-TAKE " + s.id + " #" + l.id + " by " + name(p) + " " + p.getUUID() + " took=" + give + " left=" + Math.max(0, l.stock));
        text().send(p, l.stock > 0 ? "shop.stall.took-some" : "shop.stall.took", Text.unparsed("count", give), Text.component("item", ShopModule.nameOf(proto)),
                Text.unparsed("left", Math.max(0, l.stock)));
        text().sound(p, "item-pickup");
        shop.menus.refresh(s.id);
        return true;
    }

    private void openPrice(ServerPlayer p, String id, long listingId) {
        openPrice(p, id, listingId, pl -> reopenManage(pl, id));
    }

    /** The price form; {@code after} runs once it is done (or Back is pressed). */
    void openPrice(ServerPlayer p, String id, long listingId, Consumer<ServerPlayer> after) {
        ShopStore.Stall s = ownedHere(p, id);
        if (s == null) return;
        ShopStore.Listing l = s.listing(listingId);
        ItemStack proto = l == null ? null : l.prototype();
        if (proto == null) {
            shop.error(p, "shop.stall.listing-gone");
            return;
        }
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.reprice-title"));
        menu.item(proto, text().msg("shop.stall.reprice-body", Text.component("item", ShopModule.nameOf(proto)), shop.n("price", l.price), shop.n("max", maxPrice)), 250);
        menu.input(Dialogs.Input.text("price", text().msg("shop.stall.price")).initial(String.valueOf(l.price)).maxLength(12).width(150).build());
        menu.button(text().msg("shop.stall.reprice-confirm"), null, W, (pl, view) -> {
            reprice(pl, id, listingId, view == null ? null : view.getText("price"));
            after.accept(pl);
        });
        menu.button(text().msg("menu.back"), null, W, after::accept);
        menu.columns(2);
        menu.open(p);
    }

    boolean reprice(ServerPlayer p, String id, long listingId, String raw) {
        ShopStore.Stall s = ownedHere(p, id);
        if (s == null) return false;
        ShopStore.Listing l = s.listing(listingId);
        if (l == null) {
            shop.error(p, "shop.stall.listing-gone");
            return false;
        }
        long price = ShopModule.parseAmount(raw, -1);
        if (price <= 0 || price > maxPrice) {
            shop.error(p, "shop.stall.bad-price", shop.n("max", maxPrice));
            return false;
        }
        long before = l.price;
        l.price = price;
        if (!store().save()) {
            l.price = before;
            shop.error(p, "shop.save-failed");
            return false;
        }
        store().log("STALL-PRICE " + s.id + " #" + l.id + " " + before + " -> " + price + " by " + name(p));
        text().send(p, "shop.stall.repriced", shop.n("price", price));
        shop.menus.refresh(s.id);
        return true;
    }

    private void confirmLeave(ServerPlayer p, String id) {
        confirmLeave(p, id, pl -> reopenManage(pl, id));
    }

    void confirmLeave(ServerPlayer p, String id, Consumer<ServerPlayer> back) {
        ShopStore.Stall s = ownedHere(p, id);
        if (s == null) return;
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.leave-title"));
        menu.text(text().msg("shop.stall.leave-body", Text.unparsed("name", s.name), Text.unparsed("count", s.listings.size())), 300);
        menu.button(text().msg("shop.stall.leave-confirm"), null, W, pl -> {
            ShopStore.Stall now = current(id);
            if (now == null || !pl.getUUID().equals(now.owner)) {
                shop.error(pl, "shop.stall.not-yours");
                return;
            }
            end(now, "left");
        });
        menu.button(text().msg("menu.back"), null, W, back::accept);
        menu.columns(2);
        menu.open(p);
    }

    // ------------------------------------------------------------------ buyers

    void openBrowse(ServerPlayer p, ShopStore.Stall s) {
        if (shop.closed(p)) return;
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.browse-title", Text.unparsed("name", s.name)));
        menu.text(text().msg(s.listings.isEmpty() ? "shop.stall.browse-empty" : "shop.stall.browse-body", Text.unparsed("owner", s.ownerName == null ? "?" : s.ownerName),
                shop.n("balance", shop.balance(p))), 300);
        int n = 0;
        for (ShopStore.Listing l : s.listings) {
            n++;
            ItemStack proto = l.prototype();
            if (proto == null) continue;
            menu.item(proto.copyWithCount(Math.min(proto.getMaxStackSize(), Math.max(1, l.stock))), text().msg("shop.stall.listing",
                    Text.unparsed("n", n), Text.component("item", ShopModule.nameOf(proto)), Text.unparsed("stock", l.stock), shop.n("price", l.price)), 250);
        }
        n = 0;
        for (ShopStore.Listing l : s.listings) {
            n++;
            final long lid = l.id;
            menu.button(text().msg("shop.stall.buy", Text.unparsed("n", n), shop.n("price", l.price)), null, W, pl -> openBuy(pl, s.id, lid));
        }
        menu.columns(2);
        menu.exit(text().msg("menu.close"), null);
        menu.open(p);
        text().sound(p, "menu-open");
    }

    private void openBuy(ServerPlayer p, String id, long listingId) {
        if (shop.closed(p)) return;
        ShopStore.Stall s = current(id);
        ShopStore.Listing l = s == null ? null : s.listing(listingId);
        ItemStack proto = l == null ? null : l.prototype();
        if (proto == null) {
            shop.error(p, "shop.stall.listing-gone");
            return;
        }
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.buy-title"));
        menu.item(proto, text().msg("shop.stall.buy-body", Text.component("item", ShopModule.nameOf(proto)), Text.unparsed("stock", l.stock),
                shop.n("price", l.price), shop.n("balance", shop.balance(p))), 250);
        menu.input(Dialogs.Input.text("amount", text().msg("shop.stall.amount")).initial("1").maxLength(6).width(120).build());
        final long quoted = l.price;
        menu.button(text().msg("shop.stall.buy-confirm"), null, W, (pl, view) -> buyOrConfirm(pl, id, listingId, quoted,
                view == null ? null : view.getText("amount"), pp -> {
                    ShopStore.Stall now = current(id);
                    if (now != null && now.rented() && near(pp, now)) openBrowse(pp, now);
                }));
        menu.button(text().msg("menu.back"), null, W, pl -> {
            ShopStore.Stall now = current(id);
            if (now != null && now.rented()) openBrowse(pl, now);
        });
        menu.columns(2);
        menu.open(p);
    }

    /** "all"/"max": as many as the player can afford and carry, and the stall has. */
    long maxQuantity(ServerPlayer p, ShopStore.Listing l, ItemStack proto) {
        long afford = l.price <= 0 ? 0 : shop.balance(p) / l.price;
        return Math.min(Math.min(l.stock, afford), ShopModule.room(p, proto));
    }

    /**
     * A typed purchase: buys at once, or first asks when it costs {@code stalls.keeper.confirm-above} or more (the same
     * line as the keeper's screen). {@code after} runs once it is done (bought, refused or backed out of).
     */
    void buyOrConfirm(ServerPlayer p, String id, long listingId, long quoted, String raw, Consumer<ServerPlayer> after) {
        ShopStore.Stall s = current(id);
        ShopStore.Listing l = s == null ? null : s.listing(listingId);
        ItemStack proto = l == null ? null : l.prototype();
        long qty = proto == null ? -1 : ShopModule.parseAmount(raw, Math.max(1, maxQuantity(p, l, proto)));
        long confirm = shop.keepers.confirmAbove;
        if (qty <= 0 || qty > l.stock || confirm <= 0 || Math.min(l.price, quoted) > Long.MAX_VALUE / qty || Math.min(l.price, quoted) * qty < confirm) {
            // buy() checks everything again (and says what's wrong)
            buy(p, id, listingId, quoted, raw);
            after.accept(p);
            return;
        }
        long total = Math.min(l.price, quoted) * qty;
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.gui.confirm-title"));
        menu.item(proto.copyWithCount(Math.max(1, (int) Math.min(qty, proto.getMaxStackSize()))), text().msg("shop.stall.gui.confirm-body",
                Text.unparsed("count", qty), Text.component("item", ShopModule.nameOf(proto)), shop.n("price", total),
                Text.unparsed("owner", s.ownerName == null ? "?" : s.ownerName), shop.n("balance", shop.balance(p))), 250);
        final String amount = String.valueOf(qty);
        menu.button(text().msg("shop.stall.gui.confirm-yes", shop.n("price", total)), null, W, pl -> {
            buy(pl, id, listingId, quoted, amount);
            after.accept(pl);
        });
        menu.button(text().msg("menu.back"), null, W, after::accept);
        menu.columns(2);
        menu.open(p);
    }

    /**
     * Buys {@code raw} of a listing at no more than {@code quoted} each. Checked now: stall, rent, listing, price, stock,
     * the buyer's balance, the owner's room, inventory room. The lower stock is saved first, then the coins move in one
     * bank step, then the items are handed over; a failure undoes the earlier steps.
     */
    boolean buy(ServerPlayer p, String id, long listingId, long quoted, String raw) {
        if (!Perms.has(p, ShopModule.STALL_PERM)) {
            shop.error(p, "general.no-permission");
            return false;
        }
        if (shop.closed(p)) return false;
        Hooks.Bank bank = shop.bank();
        if (bank.robbing(p)) {
            shop.error(p, "bank.robbery.no-banking");
            return false;
        }
        ShopStore.Stall s = current(id);
        if (s == null || !s.rented() || s.rentUntil <= System.currentTimeMillis()) {
            shop.error(p, "shop.stall.closed-now");
            return false;
        }
        if (p.getUUID().equals(s.owner)) {
            shop.error(p, "shop.stall.own");
            return false;
        }
        if (!near(p, s)) {
            shop.error(p, "shop.stall.too-far");
            return false;
        }
        ShopStore.Listing l = s.listing(listingId);
        ItemStack proto = l == null ? null : l.prototype();
        if (l == null || proto == null || l.stock <= 0) {
            shop.error(p, "shop.stall.listing-gone");
            return false;
        }
        if (l.price > quoted) {
            shop.error(p, "shop.store.price-changed", shop.n("price", l.price), shop.n("was", quoted));
            return false;
        }
        long qty = ShopModule.parseAmount(raw, Math.max(1, maxQuantity(p, l, proto)));
        if (qty <= 0) {
            shop.error(p, "shop.bad-amount", Text.unparsed("max", l.stock));
            return false;
        }
        if (qty > l.stock) {
            shop.error(p, "shop.stall.only-left", Text.unparsed("stock", l.stock));
            return false;
        }
        long total;
        try {
            total = Math.multiplyExact(l.price, qty);
        } catch (ArithmeticException e) {
            shop.error(p, "shop.bad-amount", Text.unparsed("max", l.stock));
            return false;
        }
        long balance = bank.balance(p.getUUID());
        if (balance < total) {
            shop.error(p, "shop.not-enough", shop.n("price", total), shop.n("balance", balance));
            return false;
        }
        if (!bank.canCredit(s.owner, total)) {
            shop.error(p, "shop.stall.owner-full");
            return false;
        }
        if (ShopModule.room(p, proto) < qty) {
            shop.error(p, "shop.no-room");
            return false;
        }
        List<ItemStack> items = ShopModule.stacks(proto, qty);
        int before = l.stock;
        int index = s.listings.indexOf(l);
        l.stock -= (int) qty;
        if (l.stock <= 0) s.listings.remove(l);
        if (!store().save()) {
            l.stock = before;
            if (!s.listings.contains(l)) s.listings.add(index, l);
            shop.error(p, "shop.save-failed");
            return false;
        }
        String itemName = net.axther.aftrhrs.core.Adv.plain(ShopModule.nameOf(proto));
        if (!bank.transfer(p.getUUID(), name(p), s.owner, s.ownerName, total, "STALL",
                qty + "x " + itemName + " at " + s.name, qty + "x " + itemName + " sold to " + name(p))) {
            l.stock = before;
            if (!s.listings.contains(l)) s.listings.add(index, l);
            store().save();
            shop.error(p, "shop.not-enough", shop.n("price", total), shop.n("balance", bank.balance(p.getUUID())));
            return false;
        }
        ShopModule.give(p, items);
        ShopModule.saveInventory(p);
        shop.menus.refresh(s.id);
        store().log("STALL-SALE " + s.id + " #" + l.id + " " + qty + "x to " + name(p) + " " + p.getUUID() + " from " + s.ownerName + " " + s.owner
                + " coins=" + total + " left=" + Math.max(0, l.stock));
        text().send(p, "shop.stall.bought", Text.unparsed("count", qty), Text.component("item", ShopModule.nameOf(proto)), shop.n("price", total),
                shop.n("balance", bank.balance(p.getUUID())));
        text().sound(p, "accepted");
        ServerPlayer owner = shop.online(s.owner);
        if (owner != null) {
            text().send(owner, "shop.stall.sold", Text.component("buyer", Names.display(p)), Text.unparsed("count", qty),
                    Text.component("item", ShopModule.nameOf(proto)), shop.n("price", total), Text.unparsed("name", s.name));
            text().sound(owner, "item-pickup");
        } else {
            ShopStore.Note note = store().notes.computeIfAbsent(s.owner, k -> new ShopStore.Note());
            if (note.sales == 0) note.since = System.currentTimeMillis();
            note.sales++;
            note.coins = Math.addExact(note.coins, total);
            store().save();
        }
        return true;
    }

    // ------------------------------------------------------------------ the end of a rental

    /**
     * Ends a rental ("expired", "left", "removed"): the stall is cleared and saved first, then the unsold stock goes to
     * the owner's mailbox (or waits for /stall collect when mail can't take it), with a letter.
     */
    void end(ShopStore.Stall s, String why) {
        if (!s.rented()) return;
        UUID owner = s.owner;
        String ownerName = s.ownerName;
        List<ShopStore.Listing> before = new ArrayList<>(s.listings);
        long until = s.rentUntil;
        long at = s.rentedAt;
        // the renter's keeper goes with the rental (a staff keeper stays for the next renter)
        boolean dropKeeper = s.hasKeeper() && !s.keeperStaff;
        String kWorld = s.keeperWorld, kType = s.keeperType, kJob = s.keeperJob;
        UUID kEntity = s.keeper;
        Loc kSpot = s.keeperSpot();
        int kIssue = s.keeperIssue;
        List<ItemStack> items = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        for (ShopStore.Listing l : s.listings) {
            ItemStack proto = l.prototype();
            if (proto == null) {
                shop.plugin.getLogger().error("Stall {}: listing #{} can't be read; its data stays in shop-log.txt: {}", s.id, l.id, l.item);
                store().log("STALL-UNREADABLE " + s.id + " #" + l.id + " stock=" + l.stock + " item=" + l.item);
                continue;
            }
            for (ItemStack st : ShopModule.stacks(proto, l.stock)) {
                items.add(st);
                raw.add(ShopStore.encode(st));
            }
        }
        s.owner = null;
        s.ownerName = null;
        s.rentUntil = 0;
        s.rentedAt = 0;
        s.listings.clear();
        s.keeperIssue++;
        if (dropKeeper) {
            s.clearKeeper();
            s.keeperType = null;
            s.keeperJob = null;
        }
        if (!store().save()) {
            s.owner = owner;
            s.ownerName = ownerName;
            s.rentUntil = until;
            s.rentedAt = at;
            s.listings.addAll(before);
            s.keeperIssue = kIssue;
            if (dropKeeper) {
                s.keeperWorld = kWorld;
                s.keeper = kEntity;
                s.keeperType = kType;
                s.keeperJob = kJob;
            }
            shop.plugin.getLogger().error("Stall {} could not be ended (shop.yml not saved); trying again later.", s.id);
            return;
        }
        store().log("STALL-END " + s.id + " (" + why + ") owner=" + ownerName + " " + owner + " returning=" + raw);
        shop.menus.closeAll(s.id);
        if (dropKeeper) {
            shop.keepers.despawn(s.id, kEntity, kSpot);
            store().log("KEEPER-END " + s.id + " entity=" + kEntity);
        } else {
            shop.keepers.renamed(s);
        }
        boolean mailed = items.isEmpty() || shop.plugin.mailItems(owner, mailFrom, text().raw("shop.stall.mail-note").replace("<name>", s.name), items);
        if (!mailed) {
            store().returns.computeIfAbsent(owner, k -> new ArrayList<>()).addAll(raw);
            store().save();
        }
        String letter = text().raw(items.isEmpty() ? "shop.stall.letter-ended-empty" : mailed ? "shop.stall.letter-ended" : "shop.stall.letter-ended-collect")
                .replace("<name>", s.name).replace("<count>", String.valueOf(items.size()));
        boolean lettered = shop.plugin.mailLetter(owner, mailFrom, letter);
        ServerPlayer online = shop.online(owner);
        if (online != null) {
            text().send(online, mailed ? "shop.stall.ended" : "shop.stall.ended-collect", Text.unparsed("name", s.name), Text.unparsed("count", items.size()));
        } else if (!lettered) {
            store().log("STALL-END-NOTE " + s.id + " no letter for " + owner);
        }
    }

    /** Every minute: rentals that ran out end, offline sale notes go out as letters. */
    void tick() {
        Hooks.Bank bank = shop.bank();
        if (store().unreadable || bank == null || !bank.isOpen()) return;
        long now = System.currentTimeMillis();
        for (ShopStore.Stall s : new ArrayList<>(store().stalls.values())) {
            if (s.rented() && s.rentUntil <= now) end(s, "expired");
        }
        boolean changed = false;
        for (Iterator<Map.Entry<UUID, ShopStore.Note>> it = store().notes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, ShopStore.Note> e = it.next();
            ShopStore.Note n = e.getValue();
            if (shop.online(e.getKey()) != null || now - n.since < noteMs) continue;
            String body = text().raw("shop.stall.letter-sales").replace("<count>", String.valueOf(n.sales)).replace("<coins>", ShopModule.fmt(n.coins));
            if (shop.plugin.mailLetter(e.getKey(), mailFrom, body)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) store().save();
    }

    /** Join digest (order 50): sales made while away (if no letter went yet) and stock waiting for /stall collect. */
    private void collectJoin(ServerPlayer p, List<JoinDigest.Line> out) {
        if (ShopModule.active != shop) return;
        ShopStore.Note n = store().notes.remove(p.getUUID());
        if (n != null) {
            store().save();
            add(out, JoinDigest.render("shop.stall.away-sales", Text.unparsed("count", n.sales), shop.n("coins", n.coins)));
        }
        List<String> waiting = store().returns.get(p.getUUID());
        if (waiting != null && !waiting.isEmpty()) add(out, JoinDigest.render("shop.stall.collect-waiting", Text.unparsed("count", waiting.size())));
    }

    private static void add(List<JoinDigest.Line> out, net.kyori.adventure.text.Component line) {
        if (line != null) out.add(new JoinDigest.Line(50, line, null));
    }

    /** /stall collect: stock that couldn't be mailed, as much as fits (saved before handing out). */
    void collect(ServerPlayer p) {
        if (shop.closed(p)) return;
        List<String> waiting = store().returns.get(p.getUUID());
        if (waiting == null || waiting.isEmpty()) {
            shop.error(p, "shop.stall.collect-none");
            return;
        }
        List<ItemStack> give = new ArrayList<>();
        List<String> keep = new ArrayList<>();
        for (String raw : waiting) {
            ItemStack it = ShopStore.decode(raw);
            if (it == null) {
                keep.add(raw);
                continue;
            }
            List<ItemStack> trial = new ArrayList<>(give);
            trial.add(it);
            if (ShopModule.fits(p, trial)) give.add(it);
            else keep.add(raw);
        }
        if (give.isEmpty()) {
            shop.error(p, "shop.no-room");
            return;
        }
        List<String> before = new ArrayList<>(waiting);
        if (keep.isEmpty()) store().returns.remove(p.getUUID());
        else store().returns.put(p.getUUID(), keep);
        if (!store().save()) {
            store().returns.put(p.getUUID(), before);
            shop.error(p, "shop.save-failed");
            return;
        }
        ShopModule.give(p, give);
        ShopModule.saveInventory(p);
        store().log("STALL-COLLECT " + name(p) + " " + p.getUUID() + " stacks=" + give.size() + " left=" + keep.size());
        text().send(p, keep.isEmpty() ? "shop.stall.collected" : "shop.stall.collected-some", Text.unparsed("count", give.size()), Text.unparsed("left", keep.size()));
        text().sound(p, "item-pickup");
    }

    // ------------------------------------------------------------------ /stall

    void command(Sender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = sender.hasPermission(ShopModule.STALL_ADMIN);
        if (sub.equals("keeper") && args.length >= 2 && admin && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("place"))) {
            keeperAdmin(sender, args);
            return;
        }
        switch (sub) {
            case "create", "remove", "list", "expire" -> {
                if (!admin) {
                    shop.error(sender, "general.no-permission");
                    return;
                }
                admin(sender, sub, args);
                return;
            }
            default -> {
            }
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text().send(sender, "shop.stall.help-admin");
            return;
        }
        if (!Perms.has(p, ShopModule.STALL_PERM)) {
            shop.error(p, "general.no-permission");
            return;
        }
        if (shop.closed(p)) return;
        switch (sub) {
            case "" -> {
                ShopStore.Stall s = nearest(p);
                if (s == null) text().send(p, admin ? "shop.stall.help-admin" : "shop.stall.help");
                else click(p, s);
            }
            case "rent" -> {
                ShopStore.Stall s = nearest(p);
                if (s == null) shop.error(p, "shop.stall.none-near");
                else if (s.rented()) shop.error(p, p.getUUID().equals(s.owner) ? "shop.stall.already-yours" : "shop.stall.taken");
                else openRent(p, s);
            }
            case "renew" -> {
                ShopStore.Stall s = mine(p);
                if (s == null) shop.error(p, "shop.stall.none-yours");
                else renew(p, s.id);
            }
            case "leave" -> {
                ShopStore.Stall s = mine(p);
                if (s == null) shop.error(p, "shop.stall.none-yours");
                else if (!near(p, s)) shop.error(p, "shop.stall.too-far");
                else confirmLeave(p, s.id);
            }
            case "collect" -> collect(p);
            case "keeper" -> {
                if (args.length >= 2 && admin) {
                    ShopStore.Stall s = store().stall(args[1]);
                    if (s == null) shop.error(p, "shop.stall.unknown", Text.unparsed("name", args[1]));
                    else if (shop.keepers.giveToken(p, s, true)) text().send(p, "shop.stall.keeper.given-staff", Text.unparsed("name", s.name));
                    return;
                }
                ShopStore.Stall s = mine(p);
                if (s == null) shop.error(p, "shop.stall.none-yours");
                else if (s.hasKeeper() && s.keeperStaff) shop.error(p, "shop.stall.keeper.staff-placed");
                else if (shop.keepers.giveToken(p, s, false)) text().send(p, s.hasKeeper() ? "shop.stall.keeper.given-move" : "shop.stall.keeper.given",
                        Text.unparsed("name", s.name), Text.unparsed("distance", (int) shop.keepers.distance));
            }
            case "info" -> {
                ShopStore.Stall s = mine(p);
                if (s == null) text().send(p, "shop.stall.none-yours");
                else text().send(p, "shop.stall.info", Text.unparsed("name", s.name), Text.unparsed("time", ShopModule.until(s.rentUntil)),
                        Text.unparsed("count", s.listings.size()), Text.unparsed("max", maxListings));
            }
            default -> text().send(p, admin ? "shop.stall.help-admin" : "shop.stall.help");
        }
    }

    /** Staff: /stall keeper remove <stall> and /stall keeper place <stall> <x> <y> <z> [yaw] [world] (console too). */
    private void keeperAdmin(Sender sender, String[] args) {
        if (store().unreadable) {
            shop.error(sender, "shop.closed");
            return;
        }
        ShopStore.Stall s = args.length >= 3 ? store().stall(args[2]) : null;
        if (s == null) {
            shop.error(sender, "shop.stall.unknown", Text.unparsed("name", args.length >= 3 ? args[2] : ""));
            return;
        }
        if (args[1].equalsIgnoreCase("remove")) {
            shop.keepers.remove(sender, s);
            return;
        }
        ServerPlayer sp = sender.player();
        Loc base = sp != null ? Loc.of(sp) : s.location();
        try {
            if (args.length < 6) throw new IllegalArgumentException();
            double x = coord(args[3], base == null ? 0 : base.x()), y = coord(args[4], base == null ? 0 : base.y()), z = coord(args[5], base == null ? 0 : base.z());
            float yaw = args.length >= 7 ? Float.parseFloat(args[6]) : 0f;
            ServerLevel w = args.length >= 8 ? Worlds.resolve(args[7]) : base != null ? base.level() : null;
            if (w == null) throw new IllegalArgumentException();
            shop.keepers.placeAt(sender, s, new Loc(w, x, y, z, yaw, 0));
        } catch (IllegalArgumentException e) {
            shop.error(sender, "shop.stall.keeper.place-usage");
        }
    }

    private static double coord(String raw, double rel) {
        if (raw.startsWith("~")) return rel + (raw.length() == 1 ? 0 : Double.parseDouble(raw.substring(1)));
        return Double.parseDouble(raw);
    }

    private ShopStore.Stall mine(ServerPlayer p) {
        ShopStore.Stall here = nearest(p);
        if (here != null && p.getUUID().equals(here.owner)) return here;
        for (ShopStore.Stall s : store().stalls.values()) if (p.getUUID().equals(s.owner)) return s;
        return null;
    }

    private void admin(Sender sender, String sub, String[] args) {
        if (store().unreadable) {
            shop.error(sender, "shop.closed");
            return;
        }
        switch (sub) {
            case "list" -> {
                text().send(sender, "shop.stall.list-header", Text.unparsed("count", store().stalls.size()));
                for (ShopStore.Stall s : store().stalls.values()) {
                    text().send(sender, s.rented() ? "shop.stall.list-rented" : "shop.stall.list-free", Text.unparsed("name", s.name), Text.unparsed("id", s.id),
                            Text.unparsed("where", (long) Math.floor(s.x) + ", " + (long) Math.floor(s.y) + ", " + (long) Math.floor(s.z) + " (" + s.world + ")"),
                            Text.unparsed("owner", s.ownerName == null ? "" : s.ownerName), Text.unparsed("time", s.rented() ? ShopModule.until(s.rentUntil) : ""),
                            Text.unparsed("count", s.listings.size()));
                }
            }
            case "create" -> {
                ServerPlayer p = sender.player();
                if (p == null) {
                    text().send(sender, "general.players-only");
                    return;
                }
                if (args.length < 2 || !args[1].matches("[A-Za-z0-9_-]{1,24}")) {
                    shop.error(p, "shop.stall.create-usage");
                    return;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (store().stalls.containsKey(id)) {
                    shop.error(p, "shop.stall.name-taken", Text.unparsed("name", args[1]));
                    return;
                }
                ShopItems.Spot base = target(p);
                if (base == null) {
                    shop.error(p, "shop.stall.look-at-stall");
                    return;
                }
                ShopStore.Stall existing = byBlock(base);
                if (existing != null) {
                    shop.error(p, "shop.stall.already-stall", Text.unparsed("name", existing.name));
                    return;
                }
                Loc at = new Loc(base.level(), base.pos().getX() + 0.5, base.pos().getY(), base.pos().getZ() + 0.5);
                if (!shop.plugin.claimAllows(p, at, ClaimAction.BUILD)) {
                    shop.error(p, "shop.stall.claimed");
                    return;
                }
                ShopStore.Stall s = new ShopStore.Stall(id);
                s.name = args[1];
                s.world = Worlds.name(base.level());
                s.x = at.x();
                s.y = at.y();
                s.z = at.z();
                s.furniture = base.id();
                store().stalls.put(id, s);
                if (!store().save()) {
                    store().stalls.remove(id);
                    shop.error(p, "shop.save-failed");
                    return;
                }
                store().log("STALL-CREATE " + id + " at " + s.world + " " + s.x + "," + s.y + "," + s.z + " by " + name(p));
                text().send(p, "shop.stall.created", Text.unparsed("name", s.name));
                text().sound(p, "accepted");
            }
            case "remove", "expire" -> {
                if (args.length < 2 || store().stall(args[1]) == null) {
                    shop.error(sender, "shop.stall.unknown", Text.unparsed("name", args.length < 2 ? "" : args[1]));
                    return;
                }
                ShopStore.Stall s = store().stall(args[1]);
                if (s.rented()) end(s, sub.equals("remove") ? "removed" : "expired (staff)");
                if (s.rented()) {
                    shop.error(sender, "shop.save-failed");
                    return;
                }
                if (sub.equals("remove")) {
                    UUID kEntity = s.keeper;
                    Loc kSpot = s.keeperSpot();
                    store().stalls.remove(s.id);
                    store().save();
                    shop.menus.closeAll(s.id);
                    if (kSpot != null || kEntity != null) shop.keepers.despawn(s.id, kEntity, kSpot);
                    store().log("STALL-REMOVE " + s.id + " by " + sender.getName());
                    text().send(sender, "shop.stall.removed", Text.unparsed("name", s.name));
                } else {
                    text().send(sender, "shop.stall.expired-now", Text.unparsed("name", s.name));
                }
            }
            default -> text().send(sender, "shop.stall.help-admin");
        }
    }

    /** The market stall block the player looks at (any part), or the nearest one within 4 blocks. */
    private ShopItems.Spot target(ServerPlayer p) {
        ServerLevel level = p.level();
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(6));
        HitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bh.getBlockPos();
            BlockState st = level.getBlockState(pos);
            String id = ShopItems.blockId(st);
            if (id != null && id.startsWith(ShopModule.STALL_PREFIX)) return new ShopItems.Spot(level, ShopItems.origin(st, pos), id);
        }
        return shop.nearestFurniture(p, 4, id -> id.startsWith(ShopModule.STALL_PREFIX));
    }

    List<String> complete(Sender sender, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission(ShopModule.STALL_ADMIN);
        if (args.length == 1) {
            out.addAll(List.of("rent", "renew", "leave", "collect", "info", "keeper"));
            if (admin) out.addAll(List.of("create", "remove", "list", "expire"));
        } else if (args.length == 2 && admin && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("expire"))) {
            for (ShopStore.Stall s : store().stalls.values()) out.add(s.name);
        } else if (args.length == 2 && admin && args[0].equalsIgnoreCase("keeper")) {
            out.addAll(List.of("remove", "place"));
            for (ShopStore.Stall s : store().stalls.values()) out.add(s.name);
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("keeper") && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("place"))) {
            for (ShopStore.Stall s : store().stalls.values()) out.add(s.name);
        }
        return out;
    }
}
