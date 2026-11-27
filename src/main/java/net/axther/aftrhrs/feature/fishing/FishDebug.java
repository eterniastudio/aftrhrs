package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.coast.Coast;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code /fishing debug ...} (feather.fishing.admin): drive and inspect fishing for testing. Plain-text output (one fact
 * per line) so the bot tests can read it.
 */
final class FishDebug {
    private FishDebug() {
    }

    static final List<String> SUBS = List.of("force", "fast", "bites", "ripplekit", "lure", "give", "roll", "context", "groups", "inspect",
            "journal", "records", "furniture", "pots", "potready", "crack", "place", "clearrecords", "tier", "reset", "insert");

    private static boolean on(String[] a, int i) {
        return a.length <= i || !a[i].equalsIgnoreCase("off");
    }

    static void run(FishingModule m, Sender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        ServerPlayer p = a.length > 2 ? m.plugin().findPlayer(s, a[2]) : (s.isPlayer() ? s.player() : null);
        if (a.length > 2 && p == null && !sub.equals("groups")) s.sendMessage("FISHDEBUG no player " + a[2]);
        if (!sub.isEmpty() && !sub.equals("groups") && !sub.equals("records") && !sub.equals("clearrecords") && p == null) {
            s.sendMessage("FISHDEBUG usage: /fishing debug " + String.join("|", SUBS) + " <player> ...");
            return;
        }
        try {
            run(m, s, a, sub, p);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            s.sendMessage("FISHDEBUG bad arguments: " + e.getMessage());
        }
    }

    private static void run(FishingModule m, Sender s, String[] a, String sub, ServerPlayer p) {
        switch (sub) {
            case "force" -> {
                String what = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "";
                m.debugForce.put(p.getUUID(), what);
                s.sendMessage("FISHDEBUG forced " + what + (m.table.species.containsKey(what) ? " (species)" : " (item)"));
            }
            case "fast" -> toggle(s, m.debugFast, p, on(a, 3), "fast");
            case "bites" -> toggle(s, m.debugBites, p, on(a, 3), "bites");
            case "ripplekit" -> toggle(s, m.debugRipple, p, on(a, 3), "ripplekit");
            case "lure" -> {
                int minutes = a.length > 3 ? Integer.parseInt(a[3]) : 60;
                m.setLuckyLure(p, minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L);
                s.sendMessage("FISHDEBUG lure " + minutes + " active=" + m.luckyLure(p));
            }
            case "give" -> {
                FishTable.Species sp = a.length > 3 ? m.table.species.get(a[3].toLowerCase(Locale.ROOT)) : null;
                if (sp == null) {
                    ItemStack other = a.length > 3 ? m.items.other(a[3].toLowerCase(Locale.ROOT), a.length > 4 ? Integer.parseInt(a[4]) : 1) : null;
                    if (other == null) {
                        s.sendMessage("FISHDEBUG unknown " + (a.length > 3 ? a[3] : ""));
                        return;
                    }
                    Coast.give(p, other);
                    s.sendMessage("FISHDEBUG gave " + a[3]);
                    return;
                }
                int grams = a.length > 4 ? Integer.parseInt(a[4]) : FishTable.rollGrams(sp);
                Coast.give(p, m.items.fish(sp, grams, p.getUUID(), net.axther.aftrhrs.core.Names.name(p)));
                s.sendMessage("FISHDEBUG gave " + sp.id() + " " + grams);
            }
            case "roll" -> {
                int n = a.length > 3 ? Math.max(1, Math.min(100_000, Integer.parseInt(a[3]))) : 1000;
                boolean open = a.length <= 4 || Boolean.parseBoolean(a[4]);
                FishTable.Context c = m.context(p, p.level(), p.position(), open);
                Map<String, Integer> rar = new LinkedHashMap<>(), spc = new TreeMap<>();
                for (String r : FishTable.RARITIES) rar.put(r, 0);
                int none = 0;
                for (int i = 0; i < n; i++) {
                    FishTable.Species x = m.table.roll(c);
                    if (x == null) {
                        none++;
                        continue;
                    }
                    rar.merge(x.rarity(), 1, Integer::sum);
                    spc.merge(x.id(), 1, Integer::sum);
                }
                StringBuilder b = new StringBuilder("FISHDEBUG roll n=" + n + " none=" + none);
                for (Map.Entry<String, Integer> e : rar.entrySet()) b.append(' ').append(e.getKey()).append('=').append(e.getValue());
                s.sendMessage(b.toString());
                s.sendMessage("FISHDEBUG species " + spc);
            }
            case "context" -> {
                FishTable.Context c = m.context(p, p.level(), p.position(), true);
                s.sendMessage("FISHDEBUG context biome=" + c.biome() + " groups=" + c.groups() + " salt=" + c.salt() + " underground=" + c.underground()
                        + " time=" + c.time() + " storm=" + c.storm() + " rod=" + c.rod() + " bait=" + c.bait() + " lure=" + c.luckyLure()
                        + " ripplekit=" + c.ripplekit() + " hotspot=" + c.hotspot());
            }
            case "groups" -> {
                String key = a.length > 3 ? a[3] : a.length > 2 ? a[2] : "minecraft:plains";
                if (!key.contains(":")) key = "minecraft:" + key;
                var g = m.table.groupsOf(key);
                s.sendMessage("FISHDEBUG groups " + key + " " + g + " salt=" + m.table.salt(g));
            }
            case "inspect" -> {
                ItemStack held = p.getMainHandItem();
                FishItems.Fish f = m.items.read(held);
                s.sendMessage("FISHDEBUG inspect id=" + m.items.idOf(held) + " type=" + held.getItem() + " amount=" + held.getCount()
                        + (f == null ? " fish=no" : " species=" + f.species() + " rarity=" + f.rarity() + " grams=" + f.grams()
                        + " catcher=" + (f.catcher() == null ? "-" : FishItems.nameOf(f.catcher()))));
            }
            case "journal" -> {
                FishData.Journal j = m.data.journal(p.getUUID());
                s.sendMessage("FISHDEBUG journal " + net.axther.aftrhrs.core.Names.name(p) + " species=" + j.species.size() + " paid=" + j.paid);
                for (Map.Entry<String, FishData.Entry> e : j.species.entrySet()) {
                    s.sendMessage("FISHDEBUG entry " + e.getKey() + " count=" + e.getValue().count + " best=" + e.getValue().best);
                }
            }
            case "tier" -> {
                String r = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "common";
                FishData.Journal j = m.data.journal(p.getUUID());
                for (FishTable.Species sp : m.table.species.values()) {
                    if (!sp.rarity().equals(r) || j.species.containsKey(sp.id())) continue;
                    FishData.Entry e = new FishData.Entry();
                    e.count = 1;
                    e.first = System.currentTimeMillis();
                    j.species.put(sp.id(), e);
                }
                m.data.dirty = true;
                m.checkTier(p, r);
                s.sendMessage("FISHDEBUG tier " + r + " paid=" + j.paid.contains(r));
            }
            case "records" -> {
                for (var e : m.screens.sortedRecords()) {
                    s.sendMessage("FISHDEBUG record " + e.getKey() + " " + e.getValue().grams() + " " + e.getValue().name());
                }
                s.sendMessage("FISHDEBUG records " + m.data.records.size());
            }
            case "insert" -> {
                BlockPos b = new BlockPos(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]));
                if (!(p.level().getBlockEntity(b) instanceof Container c)) {
                    s.sendMessage("FISHDEBUG insert no container");
                    return;
                }
                ItemStack held = p.getMainHandItem();
                for (int i = 0; i < c.getContainerSize() && !held.isEmpty(); i++) {
                    if (c.getItem(i).isEmpty()) {
                        c.setItem(i, held.copy());
                        held.setCount(0);
                    }
                }
                c.setChanged();
                s.sendMessage("FISHDEBUG insert done " + held.getItem());
            }
            case "reset" -> {
                m.data.journals.remove(p.getUUID());
                m.data.records.clear();
                m.data.tournament.clear();
                m.data.tournamentUntil = 0;
                m.data.dirty = true;
                m.recordsChanged();
                int pots = m.crabPots.forgetNear(p.level(), p.position(), 64);
                m.debugForce.remove(p.getUUID());
                s.sendMessage("FISHDEBUG reset pots=" + pots);
            }
            case "clearrecords" -> {
                m.data.records.clear();
                m.recordsChanged();
                s.sendMessage("FISHDEBUG records cleared");
            }
            case "furniture" -> {
                List<String> lines = m.furniture.describe();
                for (String l : lines) s.sendMessage("FISHDEBUG " + l);
                s.sendMessage("FISHDEBUG furniture " + lines.size());
            }
            case "pots" -> {
                List<String> lines = m.crabPots.describe(p.level());
                for (String l : lines) s.sendMessage("FISHDEBUG " + l);
                s.sendMessage("FISHDEBUG pots " + lines.size());
            }
            case "potready" -> {
                int rolls = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                s.sendMessage("FISHDEBUG potready " + m.crabPots.fastForward(p, 16, rolls));
            }
            case "crack" -> {
                if (!m.clamId.equals(m.items.idOf(p.getMainHandItem()))) {
                    s.sendMessage("FISHDEBUG crack no clam in hand");
                    return;
                }
                m.crackClam(p, p.position().add(0, 1, 0));
                s.sendMessage("FISHDEBUG crack done");
            }
            case "place" -> {
                // place <player> <furniture id> <x> <y> <z>: places furniture (as that player for the pot owner)
                if (a.length < 7) {
                    s.sendMessage("FISHDEBUG place usage");
                    return;
                }
                BlockPos at = new BlockPos(Integer.parseInt(a[4]), Integer.parseInt(a[5]), Integer.parseInt(a[6]));
                if (!ArcanaContent.place(p.level(), at, a[3], Direction.NORTH)) {
                    s.sendMessage("FISHDEBUG place failed");
                    return;
                }
                var t = m.furniture.attach(p.level(), at);
                if (t != null && t.kind == FishFurniture.Kind.POT) m.crabPots.placed(p.level(), at, p);
                s.sendMessage("FISHDEBUG placed " + a[3] + " @" + at.getX() + "," + at.getY() + "," + at.getZ());
            }
            default -> s.sendMessage("FISHDEBUG usage: /fishing debug " + String.join("|", SUBS) + " <player> ...");
        }
    }

    private static void toggle(Sender s, java.util.Set<UUID> set, ServerPlayer p, boolean on, String what) {
        if (on) set.add(p.getUUID());
        else set.remove(p.getUUID());
        s.sendMessage("FISHDEBUG " + what + " " + (on ? "on" : "off"));
    }

    static List<String> complete(FishingModule m, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(SUBS);
        else if (a.length == 3) for (ServerPlayer p : m.plugin().onlinePlayers()) out.add(net.axther.aftrhrs.core.Names.name(p));
        else if (a.length == 4 && (a[1].equalsIgnoreCase("force") || a[1].equalsIgnoreCase("give"))) out.addAll(m.table.species.keySet());
        else if (a.length == 4 && a[1].equalsIgnoreCase("tier")) out.addAll(FishTable.RARITIES);
        return out;
    }
}
