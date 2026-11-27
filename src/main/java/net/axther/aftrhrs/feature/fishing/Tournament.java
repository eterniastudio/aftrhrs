package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.BossBars;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.bossbar.BossBar;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fishing tournaments: staff start one ({@code /fishing tournament start <minutes>}) or the schedule does (weekend
 * evenings by default). A boss bar shows the time left and the leader; the biggest single custom fish wins, prizes are
 * coins from the config. The running tournament is saved, so a restart doesn't end it.
 */
final class Tournament {
    private final FishingModule m;
    private BossBar bar;
    private Tasks.Task task;
    private final Set<UUID> viewers = new HashSet<>();
    private long tick;

    private List<Long> prizes = List.of(500L, 250L, 100L);
    private boolean scheduled = true;
    private List<DayOfWeek> days = List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private LocalTime at = LocalTime.of(20, 0);
    private int scheduleMinutes = 60;
    private int maxMinutes = 24 * 60;

    Tournament(FishingModule m) {
        this.m = m;
    }

    void reload(Yaml y) {
        List<Long> p = new ArrayList<>();
        for (Object o : y.getList("tournament.prizes", List.of(500, 250, 100))) {
            if (o instanceof Number n && n.longValue() >= 0) p.add(n.longValue());
        }
        prizes = List.copyOf(p);
        scheduled = y.getBoolean("tournament.schedule.enabled", true);
        List<DayOfWeek> d = new ArrayList<>();
        for (String s : y.getStringList("tournament.schedule.days")) {
            try {
                d.add(DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                m.warnOnce("fishing.yml tournament.schedule.days: unknown day '" + s + "'.");
            }
        }
        days = d.isEmpty() ? List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) : List.copyOf(d);
        try {
            at = LocalTime.parse(y.getString("tournament.schedule.time", "20:00"));
        } catch (RuntimeException e) {
            m.warnOnce("fishing.yml tournament.schedule.time: use HH:MM (24 h).");
            at = LocalTime.of(20, 0);
        }
        scheduleMinutes = Math.max(5, y.getInt("tournament.schedule.minutes", 60));
        maxMinutes = Math.max(1, y.getInt("tournament.max-minutes", 24 * 60));
    }

    void start() {
        task = Tasks.timer(20L, 20L, this::tick);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        hideAll();
    }

    boolean running() {
        return m.data.tournamentUntil > System.currentTimeMillis();
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(m.plugin().getConfig().getString("daily.reset-zone", "America/Toronto"));
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private boolean watching(ServerPlayer p) {
        return Perms.has(p, FishingModule.PERM) && !m.plugin().authBlocked(p);
    }

    private void tick() {
        tick++;
        long now = System.currentTimeMillis();
        if (m.data.tournamentUntil > 0 && now >= m.data.tournamentUntil) end(null);
        if (tick % 30 == 0 && scheduled && !running()) checkSchedule(now);
        if (running()) updateBar(now);
        else hideAll();
    }

    private void checkSchedule(long now) {
        ZonedDateTime z = ZonedDateTime.now(zone());
        LocalDate today = z.toLocalDate();
        if (!days.contains(today.getDayOfWeek())) return;
        long slot = today.atTime(at).atZone(zone()).toInstant().toEpochMilli();
        long end = slot + scheduleMinutes * 60_000L;
        if (now < slot || now >= end || m.data.lastScheduled >= slot) return;
        m.data.lastScheduled = slot;
        begin(end, null);
    }

    void begin(long until, Sender by) {
        m.data.tournament.clear();
        m.data.tournamentStarted = System.currentTimeMillis();
        m.data.tournamentUntil = until;
        m.data.dirty = true;
        m.data.save();
        String time = FishItems.duration(until - System.currentTimeMillis());
        for (ServerPlayer p : m.plugin().onlinePlayers()) {
            if (watching(p)) {
                m.plugin().text().send(p, "fishing.tournament.started", Text.unparsed("time", time));
                m.playSound(p.level(), p.position(), "block.note_block.bell 0.8 1.2");
            }
        }
        if (by != null && !by.isPlayer()) m.plugin().text().send(by, "fishing.tournament.started", Text.unparsed("time", time));
        m.plugin().getLogger().info("Fishing tournament started ({}).", time);
    }

    /** A custom fish was landed: the player's entry is their biggest single fish. */
    void entry(ServerPlayer p, FishTable.Species s, int grams) {
        if (!running() || grams <= 0) return;
        GameType gm = p.gameMode();
        if (gm != GameType.SURVIVAL && gm != GameType.ADVENTURE) return;
        FishData.Entrant old = m.data.tournament.get(p.getUUID());
        if (old != null && old.grams() >= grams) return;
        UUID leaderBefore = leader();
        m.data.tournament.put(p.getUUID(), new FishData.Entrant(Names.name(p), s.id(), grams));
        m.data.dirty = true;
        m.plugin().text().send(p, "fishing.tournament.entry", Text.unparsed("fish", s.name()), Text.unparsed("weight", FishItems.lb(grams)));
        if (!p.getUUID().equals(leaderBefore) && p.getUUID().equals(leader())) {
            for (ServerPlayer o : m.plugin().onlinePlayers()) {
                if (Perms.has(o, FishingModule.PERM)) m.plugin().text().actionBar(o, "fishing.tournament.new-leader",
                        Text.unparsed("player", Names.name(p)), Text.unparsed("fish", s.name()), Text.unparsed("weight", FishItems.lb(grams)));
            }
        }
    }

    private List<Map.Entry<UUID, FishData.Entrant>> standings() {
        List<Map.Entry<UUID, FishData.Entrant>> list = new ArrayList<>(m.data.tournament.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue().grams(), a.getValue().grams()));
        return list;
    }

    private UUID leader() {
        List<Map.Entry<UUID, FishData.Entrant>> s = standings();
        return s.isEmpty() ? null : s.get(0).getKey();
    }

    private String speciesName(String id) {
        FishTable.Species s = m.table.species.get(id);
        return s == null ? FishTable.pretty(id) : s.name();
    }

    private void updateBar(long now) {
        long total = Math.max(1, m.data.tournamentUntil - m.data.tournamentStarted);
        float progress = (float) Math.max(0, Math.min(1, (m.data.tournamentUntil - now) / (double) total));
        List<Map.Entry<UUID, FishData.Entrant>> s = standings();
        var title = s.isEmpty()
                ? m.plugin().text().msg("fishing.tournament.bar-empty", Text.unparsed("time", FishItems.duration(m.data.tournamentUntil - now)))
                : m.plugin().text().msg("fishing.tournament.bar", Text.unparsed("time", FishItems.duration(m.data.tournamentUntil - now)),
                Text.unparsed("leader", s.get(0).getValue().name()), Text.unparsed("fish", speciesName(s.get(0).getValue().species())),
                Text.unparsed("weight", FishItems.lb(s.get(0).getValue().grams())));
        if (bar == null) bar = BossBar.bossBar(title, progress, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        else {
            bar.name(title);
            bar.progress(progress);
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : m.plugin().onlinePlayers()) {
            online.add(p.getUUID());
            boolean want = watching(p);
            if (want && viewers.add(p.getUUID())) BossBars.show(p, bar);
            else if (!want && viewers.remove(p.getUUID())) BossBars.hide(p, bar);
        }
        viewers.retainAll(online);
    }

    private void hideAll() {
        if (bar == null) return;
        for (UUID id : viewers) {
            ServerPlayer p = m.plugin().player(id);
            if (p != null) BossBars.hide(p, bar);
        }
        viewers.clear();
        bar = null;
    }

    /** Ends it now: prizes to the top fish, results to everyone. */
    void end(Sender by) {
        List<Map.Entry<UUID, FishData.Entrant>> s = standings();
        m.data.tournamentUntil = 0;
        m.data.tournament.clear();
        m.data.dirty = true;
        hideAll();
        Text text = m.plugin().text();
        Hooks.Bank bank = m.plugin().service(Hooks.Bank.class);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < s.size() && i < Math.max(3, prizes.size()); i++) {
            FishData.Entrant e = s.get(i).getValue();
            long prize = i < prizes.size() ? prizes.get(i) : 0;
            boolean paid = false;
            if (prize > 0 && bank != null) paid = bank.credit(s.get(i).getKey(), e.name(), prize, "fishing tournament #" + (i + 1));
            lines.add(text.raw("fishing.tournament.result-line").replace("<place>", String.valueOf(i + 1)).replace("<player>", e.name().replace("<", "\\<"))
                    .replace("<fish>", speciesName(e.species())).replace("<weight>", FishItems.lb(e.grams()))
                    .replace("<prize>", paid ? text.raw("fishing.tournament.prize").replace("<coins>", String.valueOf(prize)) : ""));
        }
        m.data.save();
        for (ServerPlayer p : m.plugin().onlinePlayers()) {
            if (!watching(p)) continue;
            text.send(p, s.isEmpty() ? "fishing.tournament.ended-empty" : "fishing.tournament.ended");
            for (String l : lines) Adv.of(p).sendMessage(text.parse(l));
            m.playSound(p.level(), p.position(), "ui.toast.challenge_complete 0.6 1.3");
        }
        if (by != null && !by.isPlayer()) {
            text.send(by, s.isEmpty() ? "fishing.tournament.ended-empty" : "fishing.tournament.ended");
            for (String l : lines) by.sendMessage(text.parse(l));
        }
        m.plugin().getLogger().info("Fishing tournament ended ({} entrant(s)).", s.size());
    }

    void command(Sender sender, String[] args) {
        Text text = m.plugin().text();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        boolean admin = sender.hasPermission(FishingModule.ADMIN);
        switch (sub) {
            case "start" -> {
                if (!admin) {
                    text.send(sender, "general.no-permission");
                    return;
                }
                if (running()) {
                    text.send(sender, "fishing.tournament.already");
                    return;
                }
                int minutes;
                try {
                    minutes = args.length > 2 ? Integer.parseInt(args[2]) : scheduleMinutes;
                } catch (NumberFormatException e) {
                    minutes = -1;
                }
                if (minutes < 1 || minutes > maxMinutes) {
                    text.send(sender, "fishing.tournament.usage");
                    return;
                }
                begin(System.currentTimeMillis() + minutes * 60_000L, sender);
            }
            case "stop", "end" -> {
                if (!admin) {
                    text.send(sender, "general.no-permission");
                    return;
                }
                if (!running()) {
                    text.send(sender, "fishing.tournament.none");
                    return;
                }
                end(sender);
            }
            default -> {
                if (!running()) {
                    text.send(sender, "fishing.tournament.none");
                    return;
                }
                text.send(sender, "fishing.tournament.status", Text.unparsed("time", FishItems.duration(m.data.tournamentUntil - System.currentTimeMillis())),
                        Text.unparsed("count", m.data.tournament.size()));
                int i = 0;
                for (Map.Entry<UUID, FishData.Entrant> e : standings()) {
                    if (i++ >= 5) break;
                    sender.sendMessage(text.parse(text.raw("fishing.tournament.result-line").replace("<place>", String.valueOf(i))
                            .replace("<player>", e.getValue().name().replace("<", "\\<")).replace("<fish>", speciesName(e.getValue().species()))
                            .replace("<weight>", FishItems.lb(e.getValue().grams())).replace("<prize>", "")));
                }
            }
        }
    }
}
