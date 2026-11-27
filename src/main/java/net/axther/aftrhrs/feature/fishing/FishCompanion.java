package net.axther.aftrhrs.feature.fishing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.net.Companion;
import net.axther.aftrhrs.net.Wire;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Fish Journal and the records for the Companion screen ({@code fishing} feature, docs/companion-protocol.md):
 * the client's Angler's Journal gets the journal as data; a tier's Claim button runs the same
 * {@link FishingModule#checkTier} as the dialog's. Like the dialog, a species the player hasn't caught is sent only as
 * a hint (no id, name or item).
 */
final class FishCompanion {
    private static final String FEATURE = "fishing";
    private static final Pattern HEX = Pattern.compile("#([0-9A-Fa-f]{6})");
    private static final long MIN_GAP_MS = 250;

    private final FishingModule m;
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private boolean running;

    FishCompanion(FishingModule m) {
        this.m = m;
    }

    static void register() {
        Companion.on("fish_journal_open", FEATURE, (p, msg) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.companionBridge.openRequest(p, msg);
        });
        Companion.on("fish_claim", FEATURE, (p, msg) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.companionBridge.claim(p, msg);
        });
        Companion.onQuit(p -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.companionBridge.lastRequest.remove(p.getUUID());
        });
    }

    void start() {
        running = true;
    }

    void stop() {
        running = false;
        lastRequest.clear();
    }

    private boolean modded(ServerPlayer p) {
        return running && Companion.has(p, FEATURE) && Perms.has(p, FishingModule.PERM);
    }

    /** A journal or records dialog is about to open: sends the mod's screen instead. True when it was sent. */
    boolean open(ServerPlayer p, String screen) {
        if (!modded(p)) return false;
        Companion.send(p, snapshot(p, screen.equals("records") ? "records" : "journal"));
        return true;
    }

    private boolean throttled(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(p.getUUID());
        if (last != null && now - last < MIN_GAP_MS) return true;
        lastRequest.put(p.getUUID(), now);
        return false;
    }

    private void openRequest(ServerPlayer p, JsonObject msg) {
        if (!modded(p) || throttled(p)) return;
        String screen = Wire.str(msg, "screen", "");
        Companion.send(p, snapshot(p, screen.equals("records") ? "records" : "journal"));
    }

    private void claim(ServerPlayer p, JsonObject msg) {
        if (!modded(p) || throttled(p)) return;
        String rarity = Wire.str(msg, "rarity", "");
        if (!FishTable.RARITIES.contains(rarity)) return;
        m.checkTier(p, rarity);
        Companion.send(p, snapshot(p, null));
    }

    String color(String rarity) {
        Matcher hex = HEX.matcher(m.items.color(rarity));
        return hex.find() ? "#" + hex.group(1).toUpperCase(java.util.Locale.ROOT) : "#F1EDFF";
    }

    JsonObject snapshot(ServerPlayer p, String open) {
        FishData.Journal j = m.data.journal(p.getUUID());
        JsonObject o = Companion.message("fish_journal");
        if (open != null) o.addProperty("open", open);
        int known = 0;
        for (String id : j.species.keySet()) if (m.table.species.containsKey(id)) known++;
        o.addProperty("have", known);
        o.addProperty("total", m.table.species.size());
        String gear = m.screens.gearLine(p);
        if (gear != null) o.addProperty("gear", net.axther.aftrhrs.net.Inspect.plain(m.plugin().text().parse(gear)));

        JsonArray tiers = new JsonArray();
        for (String rarity : FishTable.RARITIES) {
            List<FishTable.Species> list = m.table.species.values().stream().filter(s -> s.rarity().equals(rarity)).toList();
            if (list.isEmpty()) continue;
            int have = 0;
            for (FishTable.Species s : list) if (j.species.containsKey(s.id())) have++;
            long coins = m.journalRewards.getOrDefault(rarity, 0L);
            boolean complete = have == list.size(), paid = j.paid.contains(rarity);
            String state = !complete ? "open" : paid || coins <= 0 ? "done" : "claim";
            JsonObject t = new JsonObject();
            t.addProperty("rarity", rarity);
            t.addProperty("name", FishTable.pretty(rarity));
            t.addProperty("color", color(rarity));
            t.addProperty("have", have);
            t.addProperty("total", list.size());
            t.addProperty("coins", coins);
            t.addProperty("state", state);
            JsonArray fish = new JsonArray();
            for (FishTable.Species s : list) {
                FishData.Entry e = j.species.get(s.id());
                JsonObject f = new JsonObject();
                if (e != null) {
                    f.addProperty("id", s.id());
                    f.addProperty("caught", true);
                    f.addProperty("name", s.name());
                    f.add("item", Wire.item(m.items.icon(s)));
                    f.addProperty("count", e.count);
                    f.addProperty("best", e.best);
                    if (e.first > 0) f.addProperty("first", e.first);
                    FishData.Record r = m.data.records.get(s.id());
                    if (r != null) {
                        JsonObject rec = new JsonObject();
                        rec.addProperty("grams", r.grams());
                        rec.addProperty("name", r.name());
                        f.add("record", rec);
                    }
                } else {
                    f.addProperty("caught", false);
                    f.addProperty("hint", m.screens.hint(s));
                }
                fish.add(f);
            }
            t.add("fish", fish);
            tiers.add(t);
        }
        o.add("tiers", tiers);

        JsonArray records = new JsonArray();
        for (Map.Entry<String, FishData.Record> e : m.screens.sortedRecords()) {
            FishTable.Species s = m.table.species.get(e.getKey());
            if (s == null) continue;
            FishData.Record r = e.getValue();
            JsonObject rec = new JsonObject();
            rec.addProperty("id", s.id());
            rec.addProperty("name", s.name());
            rec.addProperty("rarity", s.rarity());
            rec.add("item", Wire.item(m.items.icon(s)));
            rec.addProperty("grams", r.grams());
            rec.addProperty("holder", r.name());
            FishData.Entry mine = j.species.get(s.id());
            if (mine != null && mine.best > 0) rec.addProperty("mine", mine.best);
            records.add(rec);
        }
        o.add("records", records);
        return o;
    }
}
