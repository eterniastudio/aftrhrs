package net.axther.aftrhrs.feature.holograms;

import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A hologram above every bank chest: "Goob Bank", the bank's state (open, robbery in progress, the vault resealing
 * after a robbery, closed) and a spinning Goob Coin. Temporary holograms owned by "bank", checked against the bank's
 * chest list ({@link Hooks.Bank}, null while the bank is off) every few seconds, so creating or removing a bank chest
 * shows up on its own.
 */
final class BankHolograms {
    static final String OWNER = "bank";

    private final HologramsModule m;
    boolean enabled = true;
    private double lift = 1.1;
    private boolean coin = true;
    private String coinItem = "oraxen:arcana_goob_coin|gold_nugget";
    private String viewDistance = "24";
    int checkSeconds = 5;

    BankHolograms(HologramsModule m) {
        this.m = m;
    }

    void reload(Yaml c) {
        enabled = c == null || c.getBoolean("enabled", true);
        lift = c == null ? 1.1 : c.getDouble("height", 1.1);
        coin = c == null || c.getBoolean("coin", true);
        coinItem = c == null ? coinItem : c.getString("coin-item", "oraxen:arcana_goob_coin|gold_nugget");
        viewDistance = String.valueOf(c == null ? 24 : Math.max(4, Math.min(128, c.getInt("view-distance", 24))));
        checkSeconds = c == null ? 5 : Math.max(1, c.getInt("check-seconds", 5));
        // Texts or layout may have changed: build them again on the next check.
        clear();
    }

    void clear() {
        m.deleteOwnedBy(OWNER);
    }

    private static String id(Loc l) {
        return Worlds.name(l.level()) + ":" + l.blockX() + ":" + l.blockY() + ":" + l.blockZ();
    }

    /** Adds holograms for new bank chests and removes those of chests that are no longer banks. */
    void sync() {
        Hooks.Bank bank = m.plugin.service(Hooks.Bank.class);
        List<Loc> chests = enabled && bank != null ? bank.bankChests() : List.of();
        Set<String> want = new HashSet<>();
        for (Loc l : chests) {
            if (l == null || l.level() == null) continue;
            String id = id(l);
            want.add(OWNER + ":" + id);
            if (m.hologram(OWNER + ":" + id) == null) create(l, id);
        }
        for (Hologram h : new ArrayList<>(m.holograms())) {
            if (OWNER.equals(h.owner()) && !want.contains(h.name())) m.delete(h);
        }
    }

    private void create(Loc chest, String id) {
        List<String> lines = new ArrayList<>();
        lines.add(m.text().raw("holograms.bank.title"));
        lines.add(m.text().raw("holograms.bank.status-line"));
        if (coin) lines.add("#ITEM: " + coinItem);
        Loc base = new Loc(chest.level(), chest.blockX() + 0.5, chest.blockY() + lift, chest.blockZ() + 0.5);
        Hologram h = m.createTemporary(OWNER, id, base, lines);
        h.settings.put("view-distance", viewDistance);
        h.settings.put("update-interval", "20");
        h.resolver = viewer -> status();
        m.changed(h);
        HoloRender r = m.render(h);
        // The location is the top of the hologram: lift it by its own height so the lowest line sits above the chest.
        m.move(h, base.add(0, r == null ? 0 : r.pageHeight(0), 0));
    }

    private TagResolver status() {
        Hooks.Bank bank = m.plugin.service(Hooks.Bank.class);
        String state = bank == null ? "closed" : bank.chestState();
        long left = bank == null ? 0 : Math.max(0, bank.robbableAt() - System.currentTimeMillis());
        return Text.component("status", m.text().msg("holograms.bank.status." + state, Text.unparsed("time", time(left))));
    }

    /** "1h 20m", "4m 05s", "12s". */
    static String time(long ms) {
        Duration d = Duration.ofMillis(ms);
        long h = d.toHours();
        if (h > 0) return h + "h " + d.toMinutesPart() + "m";
        if (d.toMinutesPart() > 0) return d.toMinutesPart() + "m " + String.format("%02d", d.toSecondsPart()) + "s";
        return d.toSecondsPart() + "s";
    }
}
