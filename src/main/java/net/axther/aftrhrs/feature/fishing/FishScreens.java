package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.item.enchantment.Enchantments;

/** The Fish Journal and the records list (dialogs, in Feather's menu style; Companion players get the mod screen). */
final class FishScreens {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final FishingModule m;

    FishScreens(FishingModule m) {
        this.m = m;
    }

    private Text text() {
        return m.plugin().text();
    }

    private ZoneId zone() {
        String z = m.plugin().getConfig().getString("daily.reset-zone", "America/Toronto");
        try {
            return ZoneId.of(z);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private String fishName(FishTable.Species s) {
        return m.items.color(s.rarity()) + s.name().replace("<", "\\<");
    }

    void journal(ServerPlayer p) {
        if (m.companionBridge.open(p, "journal")) return;
        FishData.Journal j = m.data.journal(p.getUUID());
        Dialogs.Menu menu = m.dialogs().menu(text().msg("fishing.journal.title"));
        menu.text(text().msg("fishing.journal.summary", Text.unparsed("count", countKnown(j)), Text.unparsed("total", m.table.species.size())), 300);
        String gear = gearLine(p);
        if (gear != null) menu.text(text().parse(gear), 300);
        List<String> claimable = new ArrayList<>();
        for (String rarity : FishTable.RARITIES) {
            List<FishTable.Species> list = m.table.species.values().stream().filter(s -> s.rarity().equals(rarity)).toList();
            if (list.isEmpty()) continue;
            int have = 0;
            for (FishTable.Species s : list) if (j.species.containsKey(s.id())) have++;
            boolean done = have == list.size();
            boolean paid = j.paid.contains(rarity);
            if (done && !paid && m.journalRewards.getOrDefault(rarity, 0L) > 0) claimable.add(rarity);
            String key = done ? (paid ? "fishing.journal.tier-complete" : "fishing.journal.tier-claim") : "fishing.journal.tier";
            menu.text(text().msg(key, Text.unparsed("tier", FishTable.pretty(rarity)), Text.unparsed("have", have),
                    Text.unparsed("total", list.size()), Text.unparsed("coins", m.journalRewards.getOrDefault(rarity, 0L)),
                    Text.component("color", Component.empty()), Text.component("colour", Component.empty()), Text.unparsed("rarity", rarity)), 300);
            for (FishTable.Species s : list) {
                FishData.Entry e = j.species.get(s.id());
                if (e != null) {
                    String first = e.first > 0 ? DATE.format(Instant.ofEpochMilli(e.first).atZone(zone())) : "?";
                    menu.item(m.items.icon(s), text().msg("fishing.journal.entry", Text.component("fish", text().parse(fishName(s))),
                            Text.unparsed("count", e.count), Text.unparsed("best", e.best > 0 ? FishItems.lb(e.best) : "-"),
                            Text.unparsed("first", first)), 240);
                } else {
                    menu.item(new ItemStack(Items.DYE.lightGray()), text().msg("fishing.journal.unknown", Text.unparsed("hint", hint(s))), 240);
                }
            }
        }
        for (String rarity : claimable) {
            menu.button(text().msg("fishing.journal.claim-button", Text.unparsed("tier", FishTable.pretty(rarity)),
                    Text.unparsed("coins", m.journalRewards.getOrDefault(rarity, 0L))), null, 200, pl -> {
                m.checkTier(pl, rarity);
                journal(pl);
            });
        }
        menu.button(text().msg("fishing.journal.records-button"), null, 200, this::records);
        menu.columns(1);
        menu.exit(text().msg("menu.close"), null);
        menu.open(p);
        text().sound(p, "menu-open");
    }

    private int countKnown(FishData.Journal j) {
        int n = 0;
        for (String id : j.species.keySet()) if (m.table.species.containsKey(id)) n++;
        return n;
    }

    /**
     * Where and when a species bites, in one plain sentence (the journal's "???" rows): "Bites in the deep ocean at
     * night", "Bites in rivers or ponds and lakes in a tropical climate when it rains · likes Grub Bait". Exact
     * conditions (habitat, climate, time, weather, needed rod, liked bait, strong bonuses), in the same order for all.
     */
    String hint(FishTable.Species s) {
        List<String> places = new ArrayList<>();
        for (String b : s.biomes()) places.add(m.table.hintName(b));
        String where;
        if (!places.isEmpty()) where = or(places);
        else if (s.water() == FishTable.Water.FRESH) where = "fresh water";
        else if (s.water() == FishTable.Water.SALT) where = "salt water";
        else where = "any water";
        StringBuilder b = new StringBuilder("Bites in ").append(where);
        if (!places.isEmpty() && s.water() == FishTable.Water.FRESH) b.append(" (fresh water)");
        if (!places.isEmpty() && s.water() == FishTable.Water.SALT) b.append(" (salt water)");
        if (!s.climate().isEmpty()) {
            List<String> cl = new ArrayList<>();
            for (String c : s.climate()) cl.add(m.table.hintName(c));
            b.append(" in ").append(cl.size() > 1 ? or(cl) + " climates" : "a " + cl.get(0) + " climate");
        }
        if (Boolean.TRUE.equals(s.underground())) b.append(" underground");
        switch (s.time()) {
            case DAY -> b.append(" by day");
            case NIGHT -> b.append(" at night");
            case DAWN -> b.append(" at dawn");
            case DUSK -> b.append(" at dusk");
            case TWILIGHT -> b.append(" at dawn or dusk");
            default -> {
            }
        }
        switch (s.weather()) {
            case CLEAR -> b.append(" in clear weather");
            case RAIN -> b.append(" when it rains");
            case THUNDER -> b.append(" in thunderstorms");
            default -> {
            }
        }
        if (s.needsRod() != null) b.append(" · needs the ").append(FishTable.pretty(s.needsRod().replaceFirst("^arcana_", "")));
        if (s.bait() != null) b.append(" · likes ").append(FishTable.pretty(s.bait().replaceFirst("^arcana_", "")));
        List<String> more = new ArrayList<>();
        for (Map.Entry<String, Double> e : s.bonus().entrySet()) {
            if (e.getValue() < 1.5) continue;
            String k = e.getKey();
            String w = switch (k) {
                case "rain" -> "in rain";
                case "thunder" -> "in thunderstorms";
                case "night" -> "at night";
                case "day" -> "by day";
                case "dawn" -> "at dawn";
                case "dusk" -> "at dusk";
                case "full-moon", "full_moon" -> "under a full moon";
                case "clear" -> "in clear weather";
                case "open-water" -> "in open water";
                default -> k.startsWith("group:") ? "in " + m.table.hintName(k.substring(6)) : null;
            };
            if (w != null) more.add(w);
        }
        if (!more.isEmpty()) b.append(" · more ").append(or(more));
        if (s.rank() >= m.table.openWaterFrom) b.append(" · open water only");
        return b.toString();
    }

    private static String or(List<String> list) {
        if (list.size() <= 1) return list.isEmpty() ? "" : list.get(0);
        return String.join(", ", list.subList(0, list.size() - 1)) + " or " + list.get(list.size() - 1);
    }

    /**
     * What the player's rod does for the catch (the journal's gear line): its Luck of the Sea / Lure levels, the rare
     * and legendary odds multipliers they and the rod give, how far the typical weight moves toward the max and the
     * trophy chance. The held rod, else the luckiest rod in the inventory; null without one.
     */
    String gearLine(ServerPlayer p) {
        ItemStack rod = m.rodStack(p);
        if (rod.isEmpty()) {
            int best = -1;
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!FishingModule.isRod(st)) continue;
                int l = FishingModule.enchant(p.level(), st, Enchantments.LUCK_OF_THE_SEA);
                if (l > best) {
                    best = l;
                    rod = st;
                }
            }
        }
        if (rod.isEmpty()) return null;
        int luck = FishingModule.enchant(p.level(), rod, Enchantments.LUCK_OF_THE_SEA);
        int lure = FishingModule.enchant(p.level(), rod, Enchantments.LURE);
        String id = m.items.idOf(rod);
        FishTable.Gear g = id == null ? null : m.table.rods.get(id);
        double rare = m.table.luckOfTheSea.rarity("rare", luck) * m.table.lureEnchant.rarity("rare", lure) * (g == null ? 1 : g.weights().getOrDefault("rare", 1.0));
        double leg = m.table.luckOfTheSea.rarity("legendary", luck) * m.table.lureEnchant.rarity("legendary", lure)
                * (g == null ? 1 : g.weights().getOrDefault("legendary", 1.0));
        List<String> en = new ArrayList<>();
        if (luck > 0) en.add("Luck of the Sea " + roman(luck));
        if (lure > 0) en.add("Lure " + roman(lure));
        int heavy = net.axther.aftrhrs.feature.enchants.Enchants.level(rod, net.axther.aftrhrs.feature.enchants.Enchants.HEAVY_HAUL);
        if (heavy > 0) en.add("Heavy Haul " + roman(heavy));
        String name = id != null ? FishTable.pretty(id.replaceFirst("^arcana_", "")) : "Fishing Rod";
        return text().raw("fishing.journal.gear").replace("<rod>", name.replace("<", "\\<"))
                .replace("<enchants>", en.isEmpty() ? text().raw("fishing.journal.gear-plain") : String.join(", ", en))
                .replace("<rare>", String.format(Locale.ROOT, "%.2f", rare)).replace("<legendary>", String.format(Locale.ROOT, "%.2f", leg))
                .replace("<skew>", String.valueOf(Math.round(m.table.weightSkew(luck, lure, heavy) * 100)))
                .replace("<trophy>", String.valueOf(Math.round(m.table.trophyChance(luck, lure, heavy) * 100)));
    }

    static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    List<Map.Entry<String, FishData.Record>> sortedRecords() {
        List<Map.Entry<String, FishData.Record>> list = new ArrayList<>(m.data.records.entrySet());
        list.sort(Comparator.<Map.Entry<String, FishData.Record>>comparingInt(e -> {
            FishTable.Species s = m.table.species.get(e.getKey());
            return s == null ? 99 : -s.rank();
        }).thenComparing(Map.Entry::getKey));
        list.removeIf(e -> !m.table.species.containsKey(e.getKey()));
        return list;
    }

    String recordLine(String speciesId, FishData.Record r) {
        FishTable.Species s = m.table.species.get(speciesId);
        return text().raw("fishing.records.line").replace("<fish>", s == null ? speciesId : fishName(s))
                .replace("<weight>", FishItems.lb(r.grams())).replace("<name>", r.name().replace("<", "\\<"));
    }

    void records(ServerPlayer p) {
        if (m.companionBridge.open(p, "records")) return;
        Dialogs.Menu menu = m.dialogs().menu(text().msg("fishing.records.title"));
        List<Map.Entry<String, FishData.Record>> list = sortedRecords();
        if (list.isEmpty()) menu.text(text().msg("fishing.records.empty"), 300);
        else {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, FishData.Record> e : list) {
                if (!b.isEmpty()) b.append('\n');
                b.append(recordLine(e.getKey(), e.getValue()));
            }
            menu.text(text().parse(b.toString()), 300);
        }
        menu.text(text().msg("fishing.records.how"), 300);
        menu.button(text().msg("fishing.records.journal-button"), null, 200, this::journal);
        menu.columns(1);
        menu.exit(text().msg("menu.close"), null);
        menu.open(p);
    }

    void recordsChat(Sender to) {
        List<Map.Entry<String, FishData.Record>> list = sortedRecords();
        text().send(to, "fishing.records.chat-head", Text.unparsed("count", list.size()));
        for (Map.Entry<String, FishData.Record> e : list) to.sendMessage(text().parse(recordLine(e.getKey(), e.getValue())));
    }

    static net.minecraft.network.chat.Component vanilla(Component c) {
        return Adv.vanilla(c);
    }
}
