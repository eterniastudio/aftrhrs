package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** The Land menu (/claim, the hub's Land button) and its pages: a claim, its trust list, its flags, abandoning. */
final class ClaimMenu {
    private static final int W = 150;
    private static final int FULL = 304;
    private final ClaimsModule m;

    ClaimMenu(ClaimsModule m) {
        this.m = m;
    }

    private Text text() {
        return m.text();
    }

    // ------------------------------------------------------------------ main page

    void open(ServerPlayer p) {
        Text text = text();
        UUID id = p.getUUID();
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.title"));
        menu.text(text.msg("claims.menu.blocks", Text.unparsed("used", m.registry.used(id)), Text.unparsed("left", Math.max(0, m.remaining(id))),
                Text.unparsed("total", m.total(id)), Text.unparsed("count", m.registry.ownedBy(id).size()), Text.unparsed("max", m.settings.maxClaims)), FULL);
        Claim here = m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
        if (here == null) menu.text(text.msg("claims.menu.here-wild"), FULL);
        else menu.text(text.msg(here.admin() ? "claims.menu.here-admin" : "claims.menu.here", m.claimTags(here, p)), FULL);
        menu.text(text.msg("claims.menu.how", Text.unparsed("min", m.settings.minWidth)), FULL);
        if (here == null || here.isOwner(id)) {
            menu.button(text.msg("claims.menu.claim-chunk"), text.msg("claims.menu.claim-chunk-tooltip"), W, pl -> m.commands.claim(pl, new String[]{"chunk"}));
        }
        menu.button(text.msg("claims.menu.show"), text.msg("claims.menu.show-tooltip"), W, pl -> {
            m.visuals.flash(pl);
            text.send(pl, "claims.showing", Text.unparsed("seconds", m.settings.showSeconds));
        });
        menu.button(text.msg("claims.menu.list", Text.unparsed("count", m.registry.ownedBy(id).size())), text.msg("claims.menu.list-tooltip"), W, this::openList);
        if (here != null && (m.canManage(p, here) || here.levelOf(id) != null)) {
            menu.button(text.msg("claims.menu.this-claim"), text.msg("claims.menu.this-claim-tooltip"), W, pl -> openClaim(pl, here));
        }
        menu.columns(2);
        m.plugin.exitButton(menu, p);
        menu.open(p);
        text.sound(p, "menu-open");
    }

    private void openList(ServerPlayer p) {
        Text text = text();
        List<Claim> mine = m.registry.ownedBy(p.getUUID());
        List<Claim> trusted = new ArrayList<>();
        for (Claim c : m.registry.all()) if (!c.isOwner(p.getUUID()) && c.trust.containsKey(p.getUUID())) trusted.add(c);
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.list-title"));
        menu.text(text.msg(mine.isEmpty() ? "claims.menu.list-empty" : "claims.menu.list-body", Text.unparsed("count", mine.size())), FULL);
        for (Claim c : mine) {
            menu.button(text.msg("claims.menu.list-entry", m.claimTags(c, p)), text.msg("claims.menu.list-entry-tooltip", m.claimTags(c, p)), FULL,
                    pl -> openClaim(pl, c));
        }
        for (Claim c : trusted) {
            menu.button(text.msg("claims.menu.list-trusted", m.claimTags(c, p)), text.msg("claims.menu.list-entry-tooltip", m.claimTags(c, p)), FULL,
                    pl -> openClaim(pl, c));
        }
        menu.columns(1);
        menu.exit(text.msg("menu.back"), this::open);
        menu.open(p);
    }

    // ------------------------------------------------------------------ one claim

    void openClaim(ServerPlayer p, Claim c) {
        if (gone(p, c)) return;
        Text text = text();
        boolean manage = m.canManage(p, c);
        boolean owner = m.isOwnerOrAdmin(p, c);
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.claim-title", m.claimTags(c, p)));
        menu.text(text.msg(c.admin() ? "claims.menu.claim-body-admin" : "claims.menu.claim-body", m.claimTags(c, p)), FULL);
        if (c.admin()) {
            menu.text(text.msg("claims.menu.allow-list", Text.unparsed("use", c.allowUse.isEmpty() ? "-" : String.join(", ", c.allowUse)),
                    Text.unparsed("break", c.allowBreak.isEmpty() ? "-" : String.join(", ", c.allowBreak))), FULL);
        }
        if (manage) {
            menu.button(text.msg("claims.menu.trust", Text.unparsed("count", c.trust.size())), text.msg("claims.menu.trust-tooltip"), W, pl -> openTrust(pl, c));
            menu.button(text.msg("claims.menu.flags"), text.msg("claims.menu.flags-tooltip"), W, pl -> openFlags(pl, c, q -> openClaim(q, c)));
            menu.button(text.msg("claims.menu.rename"), text.msg("claims.menu.rename-tooltip"), W, pl -> openRename(pl, c));
        }
        menu.button(text.msg("claims.menu.show"), text.msg("claims.menu.show-tooltip"), W, pl -> {
            m.visuals.flash(pl);
            text.send(pl, "claims.showing", Text.unparsed("seconds", m.settings.showSeconds));
        });
        Claim here = m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
        if (owner && here == c) {
            menu.button(text.msg("claims.menu.unclaim-chunk"), text.msg("claims.menu.unclaim-chunk-tooltip"), W, pl -> {
                if (gone(pl, c)) return;
                if (m.registry.at(pl.level(), pl.getBlockX(), pl.getBlockZ()) != c) {
                    text.send(pl, "claims.not-in-claim");
                    return;
                }
                confirmUnclaim(pl, c, pl.getBlockX() >> 4, pl.getBlockZ() >> 4, q -> openClaim(q, c));
            });
        }
        if (owner) menu.button(text.msg("claims.menu.abandon"), text.msg("claims.menu.abandon-tooltip"), W, pl -> confirmAbandon(pl, c, q -> openClaim(q, c)));
        menu.columns(2);
        menu.exit(text.msg("menu.back"), this::open);
        menu.open(p);
    }

    private boolean gone(ServerPlayer p, Claim c) {
        if (!c.removed) return false;
        text().send(p, "claims.gone");
        return true;
    }

    void confirmAbandon(ServerPlayer p, Claim c, Consumer<ServerPlayer> back) {
        Text text = text();
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.abandon-title"));
        menu.text(text.msg(c.admin() ? "claims.menu.abandon-body-admin" : "claims.menu.abandon-body", m.claimTags(c, p)), FULL);
        menu.button(text.msg("claims.menu.abandon-yes"), null, W, pl -> {
            if (gone(pl, c)) return;
            if (!m.isOwnerOrAdmin(pl, c)) {
                text.send(pl, "claims.not-owner");
                return;
            }
            m.abandon(pl, c);
        });
        menu.button(text.msg("menu.back"), null, W, pl -> {
            if (back != null) back.accept(pl);
        });
        menu.columns(2);
        menu.open(p);
    }

    /** A yes / back dialog (the same shape as abandoning). */
    private void confirm(ServerPlayer p, Component title, Component body, Component yes, Consumer<ServerPlayer> onYes, Consumer<ServerPlayer> back) {
        Dialogs.Menu menu = m.plugin.dialogs().menu(title);
        menu.text(body, FULL);
        menu.button(yes, null, W, onYes);
        menu.button(text().msg("menu.back"), null, W, pl -> {
            if (back != null) back.accept(pl);
        });
        menu.columns(2);
        menu.open(p);
    }

    /** Unclaiming chunk (cx, cz) of a claim, after a yes. */
    void confirmUnclaim(ServerPlayer p, Claim c, int cx, int cz, Consumer<ServerPlayer> back) {
        Text text = text();
        confirm(p, text.msg("claims.menu.unclaim-title"), text.msg("claims.menu.unclaim-body", m.claimTags(c, p)), text.msg("claims.menu.unclaim-yes"),
                pl -> {
                    if (gone(pl, c)) return;
                    m.unclaimChunk(pl, c, cx, cz);
                }, back);
    }

    /** Giving a claim to {@code to} (an exact username), after a yes; everything is checked again then. */
    void confirmTransfer(ServerPlayer p, Claim c, String to) {
        Text text = text();
        confirm(p, text.msg("claims.menu.transfer-title"), text.msg("claims.menu.transfer-body", m.claimTags(c, p), Text.unparsed("player", to)),
                text.msg("claims.menu.transfer-yes"), pl -> m.commands.transfer(pl, c, to, true), null);
    }

    /** Staff deleting any claim, after a yes. */
    void confirmDelete(ServerPlayer p, Claim c) {
        Text text = text();
        confirm(p, text.msg("claims.menu.delete-title"), text.msg("claims.menu.delete-body", m.claimTags(c, p)), text.msg("claims.menu.delete-yes"),
                pl -> m.commands.deleteClaim(net.axther.aftrhrs.core.cmd.Sender.of(pl), c), null);
    }

    private void openRename(ServerPlayer p, Claim c) {
        Text text = text();
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.rename-title"));
        menu.input(Dialogs.Input.text("name", text.msg("claims.menu.rename-label")).initial(c.name).maxLength(32).width(240).build());
        menu.button(text.msg("claims.menu.rename-save"), null, W, (pl, view) -> {
            if (gone(pl, c) || !m.canManage(pl, c)) return;
            String name = view == null ? null : view.getText("name");
            if (name != null) m.commands.rename(pl, c, name);
            openClaim(pl, c);
        });
        menu.button(text.msg("menu.back"), null, W, pl -> openClaim(pl, c));
        menu.columns(2);
        menu.open(p);
    }

    // ------------------------------------------------------------------ flags

    void openFlags(ServerPlayer p, Claim c, Consumer<ServerPlayer> back) {
        if (gone(p, c)) return;
        Text text = text();
        boolean manage = m.canManage(p, c);
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.flags-title", m.claimTags(c, p)));
        menu.text(text.msg(manage ? "claims.menu.flags-body" : "claims.menu.flags-body-view"), FULL);
        for (ClaimFlag f : ClaimFlag.values()) {
            boolean on = c.flag(f);
            Component label = text.msg(on ? "claims.menu.flag-on" : "claims.menu.flag-off",
                    Text.component("flag", text.msg("claims.flag." + f.key())));
            menu.button(label, text.msg("claims.flag." + f.key() + "-tooltip"), FULL, pl -> {
                if (manage) setFlag(pl, c, f, !c.flag(f));
                openFlags(pl, c, back);
            });
        }
        menu.columns(1);
        if (back != null) menu.exit(text.msg("menu.back"), back);
        else menu.exit(text.msg("menu.close"), null);
        menu.open(p);
    }

    void setFlag(ServerPlayer p, Claim c, ClaimFlag f, boolean on) {
        if (c.removed || !m.canManage(p, c) || m.closed(p)) return;
        if (on == f.def) c.flags.remove(f);
        else c.flags.put(f, on);
        m.store.markDirty();
        m.bridge.touch(c);
        text().send(p, on ? "claims.flag-set-on" : "claims.flag-set-off", Text.component("flag", text().msg("claims.flag." + f.key())),
                Text.unparsed("name", c.name));
        text().sound(p, "click");
    }

    // ------------------------------------------------------------------ trust

    private void openTrust(ServerPlayer p, Claim c) {
        if (gone(p, c)) return;
        Text text = text();
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.trust-title", m.claimTags(c, p)));
        menu.text(text.msg("claims.menu.trust-body"), FULL);
        for (Map.Entry<UUID, TrustLevel> e : new ArrayList<>(c.trust.entrySet())) {
            UUID who = e.getKey();
            String name = c.trustNames.getOrDefault(who, who.toString().substring(0, 8));
            menu.button(text.msg("claims.menu.trust-entry", Text.unparsed("player", name), Text.unparsed("level", text.raw("claims.level." + e.getValue().key()))),
                    text.msg("claims.menu.trust-entry-tooltip"), FULL, pl -> openTrustPlayer(pl, c, who, name));
        }
        String pub = c.publicTrust == null ? text.raw("claims.level.none") : text.raw("claims.level." + c.publicTrust.key());
        menu.button(text.msg("claims.menu.public", Text.unparsed("level", pub)), text.msg("claims.menu.public-tooltip"), FULL, pl -> {
            TrustLevel next = c.publicTrust == null ? TrustLevel.ACCESS : c.publicTrust == TrustLevel.ACCESS ? TrustLevel.CONTAINER
                    : c.publicTrust == TrustLevel.CONTAINER ? TrustLevel.BUILD : null;
            setPublic(pl, c, next, true);
            openTrust(pl, c);
        });
        menu.button(text.msg("claims.menu.trust-add"), text.msg("claims.menu.trust-add-tooltip"), FULL, pl -> openTrustAdd(pl, c));
        menu.columns(1);
        menu.exit(text.msg("menu.back"), pl -> openClaim(pl, c));
        menu.open(p);
    }

    private void openTrustAdd(ServerPlayer p, Claim c) {
        Text text = text();
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.trust-add-title"));
        menu.input(Dialogs.Input.text("player", text.msg("claims.menu.trust-add-label")).maxLength(32).width(240).build());
        for (TrustLevel lv : TrustLevel.values()) {
            if (lv == TrustLevel.MANAGER && !m.isOwnerOrAdmin(p, c)) continue;
            menu.button(text.msg("claims.menu.level-button", Text.unparsed("level", text.raw("claims.level." + lv.key()))),
                    text.msg("claims.level." + lv.key() + "-tooltip"), W, (pl, view) -> {
                        String raw = view == null ? null : view.getText("player");
                        if (raw == null || raw.isBlank()) {
                            openTrust(pl, c);
                            return;
                        }
                        OfflinePlayer target = m.plugin.findOfflinePlayer(pl, raw.trim());
                        if (target == null) {
                            m.plugin.notFound(pl, raw.trim());
                            return;
                        }
                        String name = target.name() == null ? raw.trim() : target.name();
                        if (setTrust(pl, c, target.id(), name, lv, true)) {
                            text.send(pl, "claims.trusted", Text.unparsed("player", name), Text.unparsed("level", text.raw("claims.level." + lv.key())),
                                    Text.unparsed("count", 1), Text.unparsed("name", c.name));
                        }
                        openTrust(pl, c);
                    });
        }
        menu.columns(2);
        menu.exit(text.msg("menu.back"), pl -> openTrust(pl, c));
        menu.open(p);
    }

    private void openTrustPlayer(ServerPlayer p, Claim c, UUID who, String name) {
        Text text = text();
        Dialogs.Menu menu = m.plugin.dialogs().menu(text.msg("claims.menu.trust-player-title", Text.unparsed("player", name)));
        for (TrustLevel lv : TrustLevel.values()) {
            if (lv == TrustLevel.MANAGER && !m.isOwnerOrAdmin(p, c)) continue;
            menu.button(text.msg(c.trust.get(who) == lv ? "claims.menu.level-current" : "claims.menu.level-button",
                    Text.unparsed("level", text.raw("claims.level." + lv.key()))), text.msg("claims.level." + lv.key() + "-tooltip"), W, pl -> {
                setTrust(pl, c, who, name, lv, true);
                openTrust(pl, c);
            });
        }
        menu.button(text.msg("claims.menu.untrust"), null, W, pl -> {
            if (setTrust(pl, c, who, name, null, true)) text.send(pl, "claims.untrusted", Text.unparsed("player", name), Text.unparsed("count", 1));
            openTrust(pl, c);
        });
        menu.columns(2);
        menu.exit(text.msg("menu.back"), pl -> openTrust(pl, c));
        menu.open(p);
    }

    /**
     * Sets (or with null removes) a player's trust. Managers may give up to Build; only the owner (or an admin) adds
     * or removes managers. False (with a message when {@code tell}) when not allowed or nothing changed.
     */
    boolean setTrust(ServerPlayer p, Claim c, UUID who, String name, TrustLevel level, boolean tell) {
        if (c.removed || m.closed(p)) return false;
        if (!m.canManage(p, c)) {
            if (tell) text().send(p, "claims.not-manager");
            return false;
        }
        if (who.equals(c.owner)) {
            if (tell) text().send(p, "claims.trust-owner");
            return false;
        }
        TrustLevel had = c.trust.get(who);
        boolean ownerRights = m.isOwnerOrAdmin(p, c);
        if ((level == TrustLevel.MANAGER || had == TrustLevel.MANAGER) && !ownerRights) {
            if (tell) text().send(p, "claims.trust-manager-owner-only");
            return false;
        }
        if (level == null) {
            if (had == null) return false;
            c.trust.remove(who);
            c.trustNames.remove(who);
        } else {
            if (had == level) return true;
            c.trust.put(who, level);
            c.trustNames.put(who, name);
        }
        m.store.markDirty();
        m.bridge.touch(c);
        return true;
    }

    void setPublic(ServerPlayer p, Claim c, TrustLevel level, boolean tell) {
        if (c.removed || m.closed(p) || !m.canManage(p, c)) return;
        if (level == TrustLevel.MANAGER) return;
        c.publicTrust = level;
        m.store.markDirty();
        m.bridge.touch(c);
        if (tell) {
            text().send(p, "claims.public-set", Text.unparsed("level", level == null ? text().raw("claims.level.none") : text().raw("claims.level." + level.key())),
                    Text.unparsed("count", 1));
        }
    }
}
