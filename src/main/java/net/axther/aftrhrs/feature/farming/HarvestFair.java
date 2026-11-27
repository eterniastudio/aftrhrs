package net.axther.aftrhrs.feature.farming;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.coast.Coast;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The weekly Harvest Fair: Prize crops (with their {@code crop_weight}) are entered at a grocer counter; at the judging
 * time ({@code fair.day} + {@code fair.time}) the heaviest of each kind win {@code fair.prizes} coins into their bank
 * balance (offline players too), the results are announced and a new fair starts. One entry per player and kind
 * (their heaviest counts). Kept in {@code data/farming-fair.yml}.
 */
final class HarvestFair {
    record Entry(UUID player, String name, int grams) {
    }

    private final FarmingModule m;
    private final Map<String, List<Entry>> entries = new LinkedHashMap<>();
    private long nextJudge;
    /** Changed since the last write that worked. */
    private boolean dirty;

    HarvestFair(FarmingModule module) {
        this.m = module;
    }

    private Path file() {
        return m.plugin().dataFile("farming-fair.yml");
    }

    long nextJudge() {
        return nextJudge;
    }

    void load() {
        entries.clear();
        Yaml y = Yaml.load(file());
        nextJudge = y.getLong("next-judge", 0);
        Yaml e = y.getConfigurationSection("entries");
        if (e != null) {
            for (String crop : e.getKeys(false)) {
                List<Entry> list = new ArrayList<>();
                for (Map<?, ?> row : e.getMapList(crop)) {
                    try {
                        list.add(new Entry(UUID.fromString(String.valueOf(row.get("uuid"))), String.valueOf(row.get("name")),
                                Integer.parseInt(String.valueOf(row.get("grams")))));
                    } catch (RuntimeException ignored) {
                    }
                }
                list.sort(Comparator.comparingInt(Entry::grams).reversed());
                entries.put(crop, list);
            }
        }
        if (nextJudge <= 0) {
            nextJudge = computeNext(System.currentTimeMillis());
            save();
        }
    }

    /** Writes the fair now and waits for it (load, judging, shutdown); false when it couldn't be written. */
    boolean save() {
        boolean ok = SafeYaml.writeSync(file(), null, snapshot(), m.plugin().getLogger());
        if (ok) dirty = false;
        return ok;
    }

    /** An entry changed: snapshot now, written on the data thread (marked dirty again if that fails). */
    void saveIfDirtyAsync() {
        if (!dirty) return;
        dirty = false;
        SafeYaml.writeAsync(file(), null, snapshot(), m.plugin().getLogger()).thenAccept(ok -> {
            if (!ok) Tasks.sync(() -> dirty = true);
        });
    }

    /** The whole file as plain maps (server thread). */
    private Object snapshot() {
        Yaml y = new Yaml();
        y.set("next-judge", nextJudge);
        for (Map.Entry<String, List<Entry>> e : entries.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Entry en : e.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", en.player().toString());
                row.put("name", en.name());
                row.put("grams", en.grams());
                rows.add(row);
            }
            y.set("entries." + e.getKey(), rows);
        }
        return y.snapshot();
    }

    private long computeNext(long after) {
        FarmConfig cfg = m.config();
        ZonedDateTime now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(after), cfg.fairZone);
        ZonedDateTime t = now.with(TemporalAdjusters.nextOrSame(cfg.fairDay)).with(cfg.fairTime).withSecond(0).withNano(0);
        if (!t.isAfter(now)) t = t.plusWeeks(1);
        return t.toInstant().toEpochMilli();
    }

    /** Every minute: judge when the time has come. */
    void tick() {
        if (!m.config().fairEnabled) return;
        if (System.currentTimeMillis() >= nextJudge) judge();
    }

    void judge() {
        FarmConfig cfg = m.config();
        Feather plugin = m.plugin();
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        List<Component> lines = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> e : entries.entrySet()) {
            List<Entry> list = e.getValue();
            for (int i = 0; i < list.size() && i < cfg.fairPrizes.size(); i++) {
                Entry en = list.get(i);
                long coins = cfg.fairPrizes.get(i);
                boolean paid = coins > 0 && bank != null
                        && bank.credit(en.player(), en.name(), coins, "REWARD", "Harvest Fair: " + FarmingModule.pretty(e.getKey()) + " #" + (i + 1)) >= 0;
                if (i == 0) {
                    lines.add(m.msg("fair.winner", Text.unparsed("crop", FarmingModule.pretty(e.getKey())), Text.unparsed("player", en.name()),
                            Text.unparsed("kg", FarmingModule.kg(en.grams())), Text.unparsed("coins", paid ? coins : 0)));
                }
                ServerPlayer online = plugin.player(en.player());
                if (online != null && paid) {
                    m.send(online, "fair.you-won", Text.unparsed("place", i + 1), Text.unparsed("crop", FarmingModule.pretty(e.getKey())),
                            Text.unparsed("coins", coins));
                }
            }
        }
        if (cfg.fairBroadcast) {
            List<Component> out = new ArrayList<>();
            if (lines.isEmpty()) out.add(m.msg("fair.no-entries"));
            else {
                out.add(m.msg("fair.results"));
                out.addAll(lines);
            }
            for (ServerPlayer p : plugin.onlinePlayers()) for (Component c : out) Adv.of(p).sendMessage(c);
            var server = Feather.serverOrNull();
            if (server != null) for (Component c : out) server.sendSystemMessage(Adv.vanilla(c));
        }
        plugin.getLogger().info("[Farming] Harvest Fair judged: {} entries.", entries.values().stream().mapToInt(List::size).sum());
        entries.clear();
        nextJudge = computeNext(System.currentTimeMillis());
        save();
    }

    /** Enters the Prize crop in the player's main hand (needs a grocer counter close by). */
    boolean submit(ServerPlayer p) {
        FarmConfig cfg = m.config();
        if (!cfg.fairEnabled) {
            m.send(p, "fair.off");
            return false;
        }
        ItemStack held = p.getMainHandItem();
        String id = Coast.id(held);
        int grams = FarmingModule.weightOf(held);
        if (id == null || !id.startsWith("arcana_crop_") || FarmingModule.qualityOf(held) != 2 || grams <= 0) {
            m.send(p, "fair.need-prize");
            return false;
        }
        if (!counterNear(p)) {
            m.send(p, "fair.need-counter");
            return false;
        }
        String crop = id.substring("arcana_crop_".length());
        List<Entry> list = entries.computeIfAbsent(crop, k -> new ArrayList<>());
        Entry old = null;
        for (Entry e : list) if (e.player().equals(p.getUUID())) old = e;
        if (old != null && old.grams() >= grams) {
            m.send(p, "fair.not-heavier", Text.unparsed("kg", FarmingModule.kg(old.grams())));
            return false;
        }
        if (old != null) list.remove(old);
        list.add(new Entry(p.getUUID(), net.axther.aftrhrs.core.Names.name(p), grams));
        list.sort(Comparator.comparingInt(Entry::grams).reversed());
        Coast.takeOne(p, InteractionHand.MAIN_HAND);
        dirty = true;
        saveIfDirtyAsync();
        int place = 1;
        for (Entry e : list) {
            if (e.player().equals(p.getUUID())) break;
            place++;
        }
        m.send(p, "fair.entered", Text.unparsed("crop", FarmingModule.pretty(crop)), Text.unparsed("kg", FarmingModule.kg(grams)),
                Text.unparsed("place", place), Text.unparsed("count", list.size()));
        Coast.sound(p.level(), p.position(), "entity.villager.yes", SoundSource.PLAYERS, 0.8f, 1.1f);
        return true;
    }

    private boolean counterNear(ServerPlayer p) {
        double r = m.config().counterRadius;
        int ri = (int) Math.ceil(r);
        ServerLevel level = p.level();
        BlockPos c = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-ri, -ri, -ri), c.offset(ri, ri, ri))) {
            String id = ArcanaContent.id(level.getBlockState(b));
            if (id != null && Coast.matches(m.config().counterIds, id)) return true;
        }
        return false;
    }

    void show(Sender to) {
        long left = Math.max(0, nextJudge - System.currentTimeMillis());
        long h = left / 3_600_000L, min = (left / 60_000L) % 60;
        m.send(to, "fair.header", Text.unparsed("time", (h >= 24 ? (h / 24) + "d " + (h % 24) + "h" : h + "h " + min + "m")));
        boolean any = false;
        for (Map.Entry<String, List<Entry>> e : entries.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Entry top = e.getValue().get(0);
            any = true;
            m.send(to, "fair.leader", Text.unparsed("crop", FarmingModule.pretty(e.getKey())), Text.unparsed("player", top.name()),
                    Text.unparsed("kg", FarmingModule.kg(top.grams())), Text.unparsed("count", e.getValue().size()));
        }
        if (!any) m.send(to, "fair.empty");
    }
}
