package net.axther.aftrhrs.feature.calendar;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.JoinDigest;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.calendar.CalendarRewards.Goal;
import net.axther.aftrhrs.feature.calendar.CalendarRewards.Reward;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The login calendar: a daily reward with a 7-day streak cycle (day 7 is the big one) and weekly grace days, plus
 * playtime rewards for active time (not AFK, past the auth gate): tiers for time played today and one-time lifetime
 * milestones. {@code /calendar} (Daily → Calendar in the hub) shows it all ({@link CalendarScreen}).
 *
 * <p>Days follow the server's day ({@code daily.reset-zone}, 00:00), never the player's own time zone, so changing
 * one's zone can't give an extra claim; it only changes how times are shown. A claim is checked against both the stored
 * day and the claim's timestamp in the current zone, so a zone change in the config can't grant a second claim either,
 * and a clock that went backwards blocks claims until it catches up. Claims are written to disk before anything is paid
 * (a crash can lose a reward but never pays twice). Playtime is sampled every few seconds and saved every minute, on
 * quit, on {@code /feather reload} and on shutdown. Data: {@code data/calendar.yml}.
 */
public final class CalendarModule implements FeatherModule, FeatherCommand {
    static final int CYCLE = 7;
    static final long NEVER = Long.MIN_VALUE;
    private static final long DAY_MS = 86_400_000L;
    private static final long SAMPLE_TICKS = 100L;   // 5 s
    private static final long MAX_SAMPLE_MS = 15_000L;
    private static final int KEEP_SEEN_DAYS = 70;
    private static final String HEADER = "# Feather login calendar: streaks, active playtime and waiting rewards.\n"
            + "# Days are epoch days in daily.reset-zone; times are epoch ms; playtime is in ms.\n\n";

    private static volatile CalendarModule active;

    /** Everything remembered about one player. Days are epoch days in the reset zone. */
    static final class Rec {
        final UUID id;
        String name;
        int streak;
        int best;
        long lastClaimDay = NEVER;
        long lastClaimAt;
        /** One entry per grace day spent: the day of the claim that spent it. */
        final List<Long> grace = new ArrayList<>();
        long promptDay = NEVER;
        final TreeSet<Long> seen = new TreeSet<>();
        long playDay = NEVER;
        long playToday;
        long playTotal;
        final Set<String> tiersHit = new LinkedHashSet<>();
        final Set<String> milestonesHit = new LinkedHashSet<>();
        /** Playtime rewards reached but not claimed: "t:&lt;id&gt;:&lt;day&gt;" or "m:&lt;id&gt;". */
        final List<String> waiting = new ArrayList<>();
        transient long lastSample;

        Rec(UUID id) {
            this.id = id;
        }
    }

    /** Where a player stands with today's login reward. */
    record Status(boolean claimed, boolean alive, int streak, int missed, int graceLeft, int cycleDay, boolean blocked) {
    }

    private final Feather plugin;
    private final Path file;
    private final Map<UUID, Rec> recs = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AftrHrs-Calendar-IO");
        t.setDaemon(true);
        return t;
    });
    private final Object writeLock = new Object();
    private long snapshotSeq;
    private long writtenSeq;
    private boolean unreadable;
    private boolean dirty;
    private Tasks.Task sampler;
    private Tasks.Task saver;
    final CalendarScreen screen;

    // accounts that claimed something today, per connection (hashed address)
    private long ipDay = NEVER;
    private final Map<String, Set<UUID>> ipAccounts = new HashMap<>();

    // settings
    ZoneId zone = ZoneId.of("America/Toronto");
    int graceDays = 1;
    int accountsPerIp = 2;
    int keepTierDays = 7;
    boolean prompt = true;
    boolean monthView = true;
    String readySound = "request-received";
    String claimSound = "accepted";
    String goalSound = "home-set";
    List<Reward> days = List.of();
    List<Goal> tiers = List.of();
    List<Goal> milestones = List.of();

    public CalendarModule(Feather plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().resolve("data").resolve("calendar.yml");
        this.screen = new CalendarScreen(plugin, this);
    }

    @Override
    public String name() {
        return "Calendar";
    }

    @Override
    public void enable() {
        reload();
        load();
        active = this;
        plugin.once("calendar.events", () -> {
            ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
                CalendarModule m = active;
                if (m != null) m.joined(h.player);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                CalendarModule m = active;
                if (m != null) m.quit(h.player);
            });
        });
        plugin.bind("calendar", this);
        long now = System.currentTimeMillis();
        for (ServerPlayer p : plugin.onlinePlayers()) rec(p).lastSample = now;
        sampler = Tasks.timer(SAMPLE_TICKS, SAMPLE_TICKS, this::sample);
        saver = Tasks.timer(1200L, 1200L, () -> {
            if (dirty) saveAsync();
        });
        plugin.hub().add("calendar", Hub.Group.DAILY, Hub.ORDER_CALENDAR, "hub-social", "feather.calendar", screen::open);
        JoinDigest.register("calendar", 10, this::collectJoin);
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        String z = c.getString("daily.reset-zone", "America/Toronto");
        ZoneId old = zone;
        try {
            zone = z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z.trim());
        } catch (RuntimeException e) {
            plugin.getLogger().warn("daily.reset-zone: unknown zone '{}'; using America/Toronto.", z);
            zone = ZoneId.of("America/Toronto");
        }
        if (!old.equals(zone) && !recs.isEmpty()) {
            plugin.getLogger().info("Calendar: the day now turns at 00:00 {} (was {}). Today's claims are checked by their time, so nobody gets a second one.",
                    zone.getId(), old.getId());
        }
        // An older config.yml without the block uses the bundled one (the config's defaults).
        Yaml s = c.getConfigurationSection("calendar");
        if (s == null) s = new Yaml();
        graceDays = Math.max(0, Math.min(6, s.getInt("streak.grace-days-per-week", 1)));
        accountsPerIp = Math.max(0, s.getInt("max-accounts-per-ip", 2));
        prompt = s.getBoolean("join-prompt", true);
        monthView = s.getBoolean("month-view", true);
        readySound = s.getString("sounds.ready", "request-received");
        claimSound = s.getString("sounds.claim", "accepted");
        goalSound = s.getString("sounds.goal", "home-set");
        days = CalendarRewards.readDays(s.getConfigurationSection("streak.days"), plugin.getLogger());
        tiers = CalendarRewards.readGoals(s.getConfigurationSection("playtime"), "daily", "minutes", plugin.getLogger());
        milestones = CalendarRewards.readGoals(s.getConfigurationSection("playtime"), "milestones", "hours", plugin.getLogger());
        keepTierDays = Math.max(1, Math.min(60, s.getInt("playtime.keep-unclaimed-days", 7)));
        if (unreadable) load();
        else if (dirty) saveAsync();
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (sampler != null) sampler.cancel();
        if (saver != null) saver.cancel();
        JoinDigest.unregister("calendar");
        plugin.hub().remove("calendar");
        long now = System.currentTimeMillis();
        for (ServerPlayer p : plugin.onlinePlayers()) account(p, now);
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Claims whose write callback hasn't run yet: a written one is paid (rolled back for a player who left), the
        // rest are rolled back. Only then the last snapshot, so it holds the outcome.
        for (Long key : new ArrayList<>(pendingPay.keySet())) settle(key, pendingPay.get(key).written == 1);
        pendingPay.clear();
        Yaml data = snapshot();
        if (data != null) write(data, snapshotSeq);
    }

    // ------------------------------------------------------------------ days

    long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    long dayOf(long ms) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay();
    }

    /** Monday of the week holding {@code day}. */
    static long weekStart(long day) {
        return LocalDate.ofEpochDay(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    /** When the next server day starts, epoch ms. */
    long nextDayAt() {
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static int cycleOf(int streak) {
        return ((Math.max(1, streak) - 1) % CYCLE) + 1;
    }

    /** The day of the last claim: the stored day, or the claim's moment in today's zone if that is later. */
    long lastDay(Rec r) {
        if (r.lastClaimDay == NEVER) return NEVER;
        return r.lastClaimAt > 0 ? Math.max(r.lastClaimDay, dayOf(r.lastClaimAt)) : r.lastClaimDay;
    }

    int graceUsedThisWeek(Rec r, long today) {
        long start = weekStart(today);
        int n = 0;
        for (long d : r.grace) if (d >= start && d <= today) n++;
        return n;
    }

    Status status(Rec r) {
        long today = today();
        long now = System.currentTimeMillis();
        long last = lastDay(r);
        int graceLeft = Math.max(0, graceDays - graceUsedThisWeek(r, today));
        // A clock that went backwards: wait until it is past the last claim again.
        boolean blocked = r.lastClaimAt > now;
        if (blocked || (last != NEVER && last >= today)) {
            return new Status(true, true, r.streak, 0, graceLeft, cycleOf(r.streak), blocked);
        }
        if (last == NEVER || r.streak <= 0) return new Status(false, false, 0, 0, graceLeft, 1, false);
        int missed = (int) Math.min(Integer.MAX_VALUE, today - last - 1);
        boolean alive = missed <= graceLeft;
        return new Status(false, alive, alive ? r.streak : 0, missed, graceLeft, alive ? cycleOf(r.streak + 1) : 1, false);
    }

    Reward dayReward(int cycleDay) {
        return cycleDay >= 1 && cycleDay <= days.size() ? days.get(cycleDay - 1) : Reward.NONE;
    }

    // ------------------------------------------------------------------ records

    Rec rec(ServerPlayer p) {
        Rec r = recs.computeIfAbsent(p.getUUID(), Rec::new);
        r.name = Names.name(p);
        return r;
    }

    Rec find(UUID id) {
        return recs.get(id);
    }

    boolean closed() {
        return unreadable;
    }

    /** True while the player still has to pass the auth gate (their time doesn't count, they can't claim). */
    private boolean gated(ServerPlayer p) {
        return plugin.authPending(p);
    }

    /** Starts a new day for the playtime counters when the server day has moved on. */
    private void rollDay(Rec r, long today) {
        if (r.playDay != NEVER && today <= r.playDay) return;
        r.playDay = today;
        r.playToday = 0;
        r.tiersHit.clear();
        // Unclaimed tier rewards wait keep-unclaimed-days, then go.
        r.waiting.removeIf(w -> {
            if (!w.startsWith("t:")) return false;
            int at = w.lastIndexOf(':');
            try {
                return Long.parseLong(w.substring(at + 1)) < today - keepTierDays + 1;
            } catch (NumberFormatException e) {
                return true;
            }
        });
        dirty = true;
    }

    /** Adds the active time since the last sample. */
    private void account(ServerPlayer p, long now) {
        Rec r = rec(p);
        long elapsed = r.lastSample <= 0 ? 0 : Math.max(0, Math.min(MAX_SAMPLE_MS, now - r.lastSample));
        r.lastSample = now;
        rollDay(r, today());
        if (elapsed <= 0 || gated(p) || plugin.isAfk(p)) return;
        r.playToday += elapsed;
        r.playTotal += elapsed;
        dirty = true;
    }

    private void sample() {
        long now = System.currentTimeMillis();
        long today = today();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            account(p, now);
            if (gated(p)) continue;
            Rec r = rec(p);
            checkGoals(p, r);
            // A player whose join digest hasn't gone out yet gets the prompt in it.
            if ((r.promptDay == NEVER || r.promptDay < today) && !JoinDigest.pending(p)) newDay(p, r, today);
        }
        pruneIps(today);
    }

    /** The first moment of a server day for this player (midnight while online, a late auth, staff changes): the prompt goes out on its own. */
    private void newDay(ServerPlayer p, Rec r, long today) {
        Component line = newDayLine(p, r, today, false);
        if (line == null) return;
        Adv.send(p, line);
        plugin.text().sound(p, readySound);
    }

    /**
     * Marks the server day as seen for this player and returns the [Claim] prompt (null when nothing waits, prompts
     * are off or the player has no calendar). {@code bare}: as a join-digest bullet, without the prefix.
     */
    private Component newDayLine(ServerPlayer p, Rec r, long today, boolean bare) {
        r.promptDay = today;
        r.seen.add(today);
        while (r.seen.size() > KEEP_SEEN_DAYS) r.seen.pollFirst();
        dirty = true;
        if (!prompt || !Perms.has(p, "feather.calendar")) return null;
        Status st = status(r);
        if (st.claimed()) return null;
        Reward reward = dayReward(st.cycleDay());
        if (reward.empty()) return null;
        Text text = plugin.text();
        Component claim = text.msg("calendar.prompt-button.label")
                .hoverEvent(HoverEvent.showText(text.msg("calendar.prompt-button.hover")))
                .clickEvent(ClickEvent.runCommand("/calendar claim"));
        String key = st.cycleDay() == CYCLE ? "calendar.prompt-big" : "calendar.prompt";
        if (text.raw(key).isEmpty()) return null;   // a server that blanked the line
        TagResolver[] tags = {Text.unparsed("day", st.cycleDay()), Text.component("reward", CalendarRewards.describe(text, reward)),
                Text.component("claim", claim)};
        return bare ? JoinDigest.render(key, tags) : text.msg(key, tags);
    }

    /** Join digest (order 10): the day's [Claim] prompt on the first join of a server day. */
    private void collectJoin(ServerPlayer p, List<JoinDigest.Line> out) {
        if (unreadable || gated(p)) return;
        long today = today();
        Rec r = rec(p);
        if (r.promptDay != NEVER && r.promptDay >= today) return;
        Component line = newDayLine(p, r, today, true);
        if (line != null) out.add(new JoinDigest.Line(10, line, readySound));
    }

    private void checkGoals(ServerPlayer p, Rec r) {
        Text text = plugin.text();
        for (Goal g : tiers) {
            if (r.playToday < g.ms() || r.tiersHit.contains(g.id())) continue;
            r.tiersHit.add(g.id());
            if (!g.reward().empty()) r.waiting.add("t:" + g.id() + ":" + r.playDay);
            dirty = true;
            text.send(p, "calendar.tier-reached", Text.unparsed("time", clock(g.ms())), Text.component("button", openButton()));
            text.sound(p, goalSound);
        }
        for (Goal g : milestones) {
            if (r.playTotal < g.ms() || r.milestonesHit.contains(g.id())) continue;
            r.milestonesHit.add(g.id());
            if (!g.reward().empty()) r.waiting.add("m:" + g.id());
            dirty = true;
            text.send(p, "calendar.milestone-reached", Text.unparsed("time", clock(g.ms())), Text.component("button", openButton()));
            text.sound(p, goalSound);
        }
    }

    private Component openButton() {
        Text text = plugin.text();
        return text.msg("calendar.open-button.label").hoverEvent(HoverEvent.showText(text.msg("calendar.open-button.hover")))
                .clickEvent(ClickEvent.runCommand("/calendar"));
    }

    private void joined(ServerPlayer p) {
        Rec r = rec(p);
        r.lastSample = System.currentTimeMillis();
        rollDay(r, today());
        // The day's prompt goes out with the join digest (collectJoin); the sampler catches midnight while online.
    }

    private void quit(ServerPlayer p) {
        account(p, System.currentTimeMillis());
        Rec r = recs.get(p.getUUID());
        if (r != null) r.lastSample = 0;
        if (dirty) saveAsync();
    }

    // ------------------------------------------------------------------ claims

    /** Playtime rewards waiting for the player, with their reward (unknown ids are left out). */
    List<Map.Entry<String, Goal>> waitingGoals(Rec r) {
        List<Map.Entry<String, Goal>> out = new ArrayList<>();
        for (String w : r.waiting) {
            Goal g = goalFor(w);
            if (g != null) out.add(Map.entry(w, g));
        }
        return out;
    }

    private Goal goalFor(String w) {
        String[] parts = w.split(":");
        if (parts.length < 2) return null;
        List<Goal> list = parts[0].equals("t") ? tiers : parts[0].equals("m") ? milestones : List.of();
        for (Goal g : list) if (g.id().equals(parts[1])) return g;
        return null;
    }

    /** Checks that the rewards can be handed over right now; sends the reason and returns false if not. */
    private boolean canPay(ServerPlayer p, List<Reward> rewards) {
        if (unreadable) {
            error(p, "calendar.closed");
            return false;
        }
        if (!CalendarRewards.fits(p, CalendarRewards.stacks(rewards))) {
            error(p, "calendar.no-room");
            return false;
        }
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        if (CalendarRewards.coins(rewards) > 0 && bank != null && !bank.isOpen()) {
            error(p, "calendar.bank-closed");
            return false;
        }
        if (!ipAllows(p)) {
            error(p, "calendar.ip-limit");
            return false;
        }
        return true;
    }

    /** Pays out (call only after the claim is saved). Returns how many pets got bond XP. */
    private int pay(ServerPlayer p, List<Reward> rewards, String note) {
        long coins = CalendarRewards.coins(rewards);
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        String name = Names.name(p);
        if (coins > 0) {
            if (bank == null) plugin.getLogger().warn("Calendar: {} coins for {} not paid (the bank is off).", coins, name);
            else if (bank.credit(p.getUUID(), name, coins, "REWARD", note) < 0) {
                plugin.getLogger().warn("Calendar: could not pay {} coins to {} ({}).", coins, name, note);
            }
        }
        for (ItemStack s : CalendarRewards.stacks(rewards)) {
            // Checked to fit; anything that still doesn't (a full inventory in between) lands at the player's feet.
            p.getInventory().add(s);
            if (!s.isEmpty()) p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), s));
        }
        int bond = CalendarRewards.bond(rewards);
        int pets = 0;
        Hooks.PetProgress petsHook = plugin.service(Hooks.PetProgress.class);
        if (bond > 0 && petsHook != null) {
            try {
                pets = petsHook.addBondToFollowers(p, bond);
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Calendar: bond XP for {} failed: {}", name, e.toString());
            }
        }
        CalendarRewards.runCommands(plugin, p, rewards);
        return pets;
    }

    /** /calendar claim: today's login reward. */
    void claimDaily(ServerPlayer p) {
        if (gated(p)) return;
        Text text = plugin.text();
        Rec r = rec(p);
        Status st = status(r);
        if (st.claimed()) {
            text.send(p, "calendar.already", Text.unparsed("time", Placeholders.duration(nextDayAt() - System.currentTimeMillis())));
            text.sound(p, "error");
            return;
        }
        Reward reward = dayReward(st.cycleDay());
        List<Reward> list = List.of(reward);
        if (!canPay(p, list)) return;

        // Remember the old state, so a failed save (or a player gone before the write landed) changes nothing.
        int oldStreak = r.streak, oldBest = r.best;
        long oldDay = r.lastClaimDay, oldAt = r.lastClaimAt;
        List<Long> graceBefore = new ArrayList<>(r.grace);
        long today = today();
        boolean seenBefore = r.seen.contains(today);
        boolean restarted = !st.alive() && r.streak > 0;
        if (st.alive()) {
            for (int i = 0; i < st.missed(); i++) r.grace.add(today);
            r.streak = r.streak + 1;
        } else {
            r.streak = 1;
        }
        r.best = Math.max(r.best, r.streak);
        r.lastClaimDay = today;
        r.lastClaimAt = System.currentTimeMillis();
        r.seen.add(today);
        r.grace.removeIf(d -> d < weekStart(today) - 7);
        long claimAt = r.lastClaimAt;
        // Noted with the claim (in the same snapshot), so a second account on the connection can't slip in meanwhile.
        String ip = noteIp(p);
        UUID id = p.getUUID();
        Runnable undo = () -> {
            // Only while the record still holds this claim (nothing claimed or loaded over it since).
            if (r.lastClaimAt == claimAt) {
                r.streak = oldStreak;
                r.best = oldBest;
                r.lastClaimDay = oldDay;
                r.lastClaimAt = oldAt;
                r.grace.clear();
                r.grace.addAll(graceBefore);
                if (!seenBefore) r.seen.remove(today);
            }
            unnoteIp(ip, id);
            ServerPlayer on = plugin.player(id);
            if (on != null && !on.hasDisconnected()) error(on, "calendar.closed");
        };
        commitThenPay(id, on -> {
            int pets = pay(on, list, "calendar day " + st.cycleDay());
            plugin.getLogger().info("Calendar: {} claimed day {} (streak {}{}).", Names.name(on), st.cycleDay(), r.streak,
                    st.missed() > 0 && st.alive() ? ", " + st.missed() + " grace day(s)" : "");
            text.send(on, st.cycleDay() == CYCLE ? "calendar.claimed-big" : "calendar.claimed", Text.unparsed("day", st.cycleDay()),
                    Text.unparsed("streak", r.streak), Text.component("reward", CalendarRewards.describe(text, reward)));
            if (st.alive() && st.missed() > 0) text.send(on, "calendar.grace-used", Text.unparsed("count", st.missed()));
            if (restarted) text.send(on, "calendar.restarted", Text.unparsed("best", r.best));
            if (reward.bond() > 0) text.send(on, pets > 0 ? "calendar.bond-given" : "calendar.bond-no-pets",
                    Text.unparsed("amount", reward.bond()), Text.unparsed("count", pets));
            text.sound(on, claimSound);
            plugin.advance(on, "event", "calendar");
        }, undo);
    }

    /** /calendar claim playtime: every playtime reward that is waiting. */
    void claimPlaytime(ServerPlayer p) {
        if (gated(p)) return;
        Text text = plugin.text();
        Rec r = rec(p);
        List<Map.Entry<String, Goal>> goals = waitingGoals(r);
        if (goals.isEmpty()) {
            // Leftovers whose tier was removed from the config.
            if (!r.waiting.isEmpty()) {
                r.waiting.clear();
                dirty = true;
            }
            text.send(p, "calendar.nothing-waiting");
            text.sound(p, "error");
            return;
        }
        List<Reward> list = new ArrayList<>();
        for (Map.Entry<String, Goal> e : goals) list.add(e.getValue().reward());
        if (!canPay(p, list)) return;
        List<String> before = new ArrayList<>(r.waiting);
        r.waiting.clear();
        String ip = noteIp(p);
        UUID id = p.getUUID();
        Runnable undo = () -> {
            // Put back what this claim took, except what is waiting again already.
            for (String w : before) if (!r.waiting.contains(w)) r.waiting.add(w);
            unnoteIp(ip, id);
            ServerPlayer on = plugin.player(id);
            if (on != null && !on.hasDisconnected()) error(on, "calendar.closed");
        };
        commitThenPay(id, on -> {
            List<String> ids = new ArrayList<>();
            for (Map.Entry<String, Goal> e : goals) ids.add(e.getKey());
            int pets = pay(on, list, "playtime " + CalendarRewards.ids(ids));
            plugin.getLogger().info("Calendar: {} claimed playtime rewards {}.", Names.name(on), ids);
            Reward total = new Reward(CalendarRewards.coins(list), CalendarRewards.bond(list),
                    list.stream().flatMap(x -> x.items().stream()).toList(), List.of(), "");
            text.send(on, "calendar.playtime-claimed", Text.unparsed("count", goals.size()), Text.component("reward", CalendarRewards.describe(text, total)));
            if (total.bond() > 0) text.send(on, pets > 0 ? "calendar.bond-given" : "calendar.bond-no-pets",
                    Text.unparsed("amount", total.bond()), Text.unparsed("count", pets));
            text.sound(on, claimSound);
        }, undo);
    }

    private void error(ServerPlayer p, String key, TagResolver... r) {
        plugin.text().send(p, key, r);
        plugin.text().sound(p, "error");
    }

    // ------------------------------------------------------------------ one claim set per connection

    private String ipKey(ServerPlayer p) {
        String ip;
        try {
            ip = p.getIpAddress();
        } catch (RuntimeException e) {
            ip = "?";
        }
        if (ip == null) ip = "?";
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("feather-calendar:" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            return ip;
        }
    }

    private void pruneIps(long today) {
        if (ipDay != today) {
            ipDay = today;
            if (!ipAccounts.isEmpty()) {
                ipAccounts.clear();
                dirty = true;
            }
        }
    }

    /** At most max-accounts-per-ip accounts claim from one connection per day (alts). */
    private boolean ipAllows(ServerPlayer p) {
        if (accountsPerIp <= 0 || Perms.has(p, "feather.calendar.admin")) return true;
        pruneIps(today());
        Set<UUID> set = ipAccounts.get(ipKey(p));
        return set == null || set.contains(p.getUUID()) || set.size() < accountsPerIp;
    }

    /** Counts the claim for the player's connection; the connection key when this added the player (for {@link #unnoteIp}), else null. */
    private String noteIp(ServerPlayer p) {
        if (accountsPerIp <= 0) return null;
        pruneIps(today());
        String key = ipKey(p);
        if (!ipAccounts.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(p.getUUID())) return null;
        dirty = true;
        return key;
    }

    /** Takes back a {@link #noteIp} of a claim that was rolled back. */
    private void unnoteIp(String key, UUID id) {
        if (key == null) return;
        Set<UUID> set = ipAccounts.get(key);
        if (set != null && set.remove(id)) {
            if (set.isEmpty()) ipAccounts.remove(key);
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** "30m", "1h", "1h 30m", "24h". */
    static String clock(long ms) {
        long m = Math.max(0, ms / 60_000);
        if (m < 60) return m + "m";
        return (m / 60) + "h" + (m % 60 == 0 ? "" : " " + (m % 60) + "m");
    }

    /** "45s", "12m", "2h 05m" for counters (seconds shown below a minute). */
    static String played(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        return (m / 60) + "h " + String.format(Locale.ROOT, "%02d", m % 60) + "m";
    }

    private static final Pattern PART = Pattern.compile("(\\d+)([smhd]?)");

    /** "90", "90s", "29m50s", "1h30m", "2d" to ms; -1 if unreadable. */
    static long parseTime(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String s = raw.toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            if (n > 100_000_000L) return -1;
            total += switch (m.group(2)) {
                case "m" -> n * 60_000L;
                case "h" -> n * 3_600_000L;
                case "d" -> n * DAY_MS;
                default -> n * 1000L;
            };
        }
        return end == s.length() && end > 0 ? total : -1;
    }

    // ------------------------------------------------------------------ /calendar

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            admin(sender, args);
            return true;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (gated(p)) return true;
        if (sub.equals("claim")) {
            if (args.length > 1 && args[1].toLowerCase(Locale.ROOT).startsWith("play")) claimPlaytime(p);
            else claimDaily(p);
            return true;
        }
        screen.open(p);
        return true;
    }

    private void admin(Sender sender, String[] args) {
        Text text = plugin.text();
        if (!sender.hasPermission("feather.calendar.admin")) {
            text.send(sender, "general.no-permission");
            return;
        }
        if (args.length < 3) {
            text.send(sender, "calendar.admin.usage");
            return;
        }
        UUID id = resolve(sender, args[1]);
        if (id == null) {
            plugin.notFound(sender, args[1]);
            return;
        }
        ServerPlayer online = plugin.player(id);
        Rec r = online != null ? rec(online) : recs.computeIfAbsent(id, Rec::new);
        if (r.name == null) r.name = args[1];
        String what = args[2].toLowerCase(Locale.ROOT);
        long today = today();
        switch (what) {
            case "info" -> {
            }
            case "streak" -> {
                int n;
                try {
                    n = args.length > 3 ? Integer.parseInt(args[3]) : -1;
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n < 0 || n > 100_000) {
                    text.send(sender, "calendar.admin.usage");
                    return;
                }
                r.streak = n;
                r.best = Math.max(r.best, n);
                if (n > 0 && r.lastClaimDay == NEVER) {
                    // A streak that is still alive, waiting for today's claim.
                    r.lastClaimDay = today - 1;
                    r.lastClaimAt = System.currentTimeMillis() - DAY_MS;
                }
                if (n == 0) {
                    r.lastClaimDay = NEVER;
                    r.lastClaimAt = 0;
                }
            }
            case "playtime" -> {
                String which = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "";
                long ms = args.length > 4 ? parseTime(args[4]) : -1;
                if (!(which.equals("today") || which.equals("total")) || ms < 0) {
                    text.send(sender, "calendar.admin.usage");
                    return;
                }
                if (online != null) account(online, System.currentTimeMillis());
                rollDay(r, today);
                if (which.equals("today")) {
                    r.playToday = ms;
                    r.playTotal = Math.max(r.playTotal, ms);
                    for (Goal g : tiers) if (g.ms() > ms) r.tiersHit.remove(g.id());
                } else {
                    r.playTotal = ms;
                    for (Goal g : milestones) if (g.ms() > ms) r.milestonesHit.remove(g.id());
                }
            }
            case "nextday" -> {
                int n;
                try {
                    n = args.length > 3 ? Integer.parseInt(args[3]) : 1;
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n < 1 || n > 365) {
                    text.send(sender, "calendar.admin.usage");
                    return;
                }
                if (online != null) account(online, System.currentTimeMillis());
                shift(r, n);
                // An online player sees the new day at once (the same as staying online over midnight).
                if (online != null) {
                    rollDay(r, today);
                    if (!gated(online)) newDay(online, r, today);
                }
            }
            case "reset" -> {
                recs.remove(id);
                Rec fresh = online != null ? rec(online) : null;
                if (fresh != null) {
                    fresh.lastSample = System.currentTimeMillis();
                    rollDay(fresh, today);
                }
                for (Set<UUID> s : ipAccounts.values()) s.remove(id);
                dirty = true;
                saveAsync();
                text.send(sender, "calendar.admin.reset", Text.unparsed("player", args[1]));
                return;
            }
            default -> {
                text.send(sender, "calendar.admin.usage");
                return;
            }
        }
        dirty = true;
        if (!what.equals("info")) saveAsync();
        if (online != null && !what.equals("info") && !gated(online)) checkGoals(online, r);
        Status st = status(r);
        text.send(sender, "calendar.admin.info", Text.unparsed("player", r.name), Text.unparsed("streak", st.streak()),
                Text.unparsed("stored", r.streak), Text.unparsed("best", r.best), Text.unparsed("claimed", st.claimed() ? "yes" : "no"),
                Text.unparsed("day", st.cycleDay()), Text.unparsed("grace", st.graceLeft()), Text.unparsed("grace-max", graceDays),
                Text.unparsed("today", played(r.playToday)), Text.unparsed("today-s", r.playToday / 1000),
                Text.unparsed("total", played(r.playTotal)), Text.unparsed("total-s", r.playTotal / 1000),
                Text.unparsed("waiting", r.waiting.isEmpty() ? "-" : String.join(", ", r.waiting)));
    }

    /** Moves every stored day of a player back by {@code n}: the same as {@code n} days passing (admin testing). */
    private void shift(Rec r, int n) {
        long ms = n * DAY_MS;
        if (r.lastClaimDay != NEVER) r.lastClaimDay -= n;
        if (r.lastClaimAt > 0) r.lastClaimAt -= ms;
        r.grace.replaceAll(d -> d - n);
        if (r.promptDay != NEVER) r.promptDay -= n;
        if (r.playDay != NEVER) r.playDay -= n;
        TreeSet<Long> seen = new TreeSet<>();
        for (long d : r.seen) seen.add(d - n);
        r.seen.clear();
        r.seen.addAll(seen);
        List<String> waiting = new ArrayList<>();
        for (String w : r.waiting) {
            if (w.startsWith("t:")) {
                int at = w.lastIndexOf(':');
                try {
                    waiting.add(w.substring(0, at + 1) + (Long.parseLong(w.substring(at + 1)) - n));
                } catch (NumberFormatException ignored) {
                    // dropped
                }
            } else waiting.add(w);
        }
        r.waiting.clear();
        r.waiting.addAll(waiting);
        for (Set<UUID> s : ipAccounts.values()) s.remove(r.id);
    }

    private UUID resolve(Sender sender, String name) {
        var server = Feather.serverOrNull();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayerByName(name);
        if (p != null) return p.getUUID();
        for (Rec r : recs.values()) if (name.equalsIgnoreCase(r.name)) return r.id;
        OfflinePlayer known = plugin.findOfflinePlayer((Sender) null, name); // nicknames, unique prefixes
        if (known != null) return known.id();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            OfflinePlayer op = plugin.lookup().byName(name);
            return op != null && op.hasPlayedBefore() ? op.id() : null;
        }
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("feather.calendar.admin");
        if (args.length == 1) {
            out.add("claim");
            if (admin) out.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
            out.add("playtime");
        } else if (admin && args[0].equalsIgnoreCase("admin")) {
            if (args.length == 2) out.addAll(plugin.completePlayers(sender, args[1]));
            else if (args.length == 3) out.addAll(List.of("info", "streak", "playtime", "nextday", "reset"));
            else if (args.length == 4 && args[2].equalsIgnoreCase("playtime")) out.addAll(List.of("today", "total"));
            else if (args.length == 4 && args[2].equalsIgnoreCase("nextday")) out.addAll(List.of("1", "2", "3"));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ disk

    private void load() {
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "login streaks and playtime");
        if (yml == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        Map<UUID, Long> samples = new HashMap<>();
        for (Rec r : recs.values()) samples.put(r.id, r.lastSample);
        recs.clear();
        Yaml ps = yml.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                Yaml s = ps.getConfigurationSection(key);
                if (s == null) continue;
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warn("calendar.yml: skipping {} (not a UUID).", key);
                    continue;
                }
                Rec r = new Rec(id);
                r.name = s.getString("name");
                r.streak = Math.max(0, s.getInt("streak"));
                r.best = Math.max(r.streak, s.getInt("best"));
                r.lastClaimDay = s.contains("last-claim-day") ? s.getLong("last-claim-day") : NEVER;
                r.lastClaimAt = s.getLong("last-claim-at", 0);
                for (Object o : s.getList("grace", List.of())) if (o instanceof Number n) r.grace.add(n.longValue());
                r.promptDay = s.contains("prompt-day") ? s.getLong("prompt-day") : NEVER;
                for (Object o : s.getList("seen", List.of())) if (o instanceof Number n) r.seen.add(n.longValue());
                r.playDay = s.contains("play-day") ? s.getLong("play-day") : NEVER;
                r.playToday = Math.max(0, s.getLong("play-today"));
                r.playTotal = Math.max(0, s.getLong("play-total"));
                r.tiersHit.addAll(s.getStringList("tiers"));
                r.milestonesHit.addAll(s.getStringList("milestones"));
                r.waiting.addAll(s.getStringList("waiting"));
                r.lastSample = samples.getOrDefault(id, 0L);
                recs.put(id, r);
            }
        }
        ipAccounts.clear();
        ipDay = yml.contains("ip-claims.day") ? yml.getLong("ip-claims.day") : NEVER;
        Yaml ips = yml.getConfigurationSection("ip-claims.accounts");
        if (ips != null) {
            for (String k : ips.getKeys(false)) {
                Set<UUID> set = new LinkedHashSet<>();
                for (String u : ips.getStringList(k)) {
                    try {
                        set.add(UUID.fromString(u));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                ipAccounts.put(k, set);
            }
        }
        pruneIps(today());
        dirty = false;
    }

    /**
     * The data as a detached tree (server thread: fresh maps and lists only, nothing shared with the records), or
     * null while the file is unreadable. Turning it into text is left to the writer, off the server thread for
     * periodic saves.
     */
    private Yaml snapshot() {
        if (unreadable) return null;
        Yaml yml = new Yaml();
        yml.set("zone", zone.getId());
        // Built straight into maps (the dotted set() of every field split and walked the path each time).
        Map<String, Object> players = new LinkedHashMap<>();
        for (Rec r : recs.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (r.name != null) m.put("name", r.name);
            m.put("streak", r.streak);
            m.put("best", r.best);
            if (r.lastClaimDay != NEVER) m.put("last-claim-day", r.lastClaimDay);
            if (r.lastClaimAt > 0) m.put("last-claim-at", r.lastClaimAt);
            if (!r.grace.isEmpty()) m.put("grace", new ArrayList<>(r.grace));
            if (r.promptDay != NEVER) m.put("prompt-day", r.promptDay);
            if (!r.seen.isEmpty()) m.put("seen", new ArrayList<>(r.seen));
            if (r.playDay != NEVER) m.put("play-day", r.playDay);
            m.put("play-today", r.playToday);
            m.put("play-total", r.playTotal);
            if (!r.tiersHit.isEmpty()) m.put("tiers", new ArrayList<>(r.tiersHit));
            if (!r.milestonesHit.isEmpty()) m.put("milestones", new ArrayList<>(r.milestonesHit));
            if (!r.waiting.isEmpty()) m.put("waiting", new ArrayList<>(r.waiting));
            players.put(r.id.toString(), m);
        }
        if (!players.isEmpty()) yml.raw().put("players", players);
        if (ipDay != NEVER && !ipAccounts.isEmpty()) {
            yml.set("ip-claims.day", ipDay);
            for (Map.Entry<String, Set<UUID>> e : ipAccounts.entrySet()) {
                yml.set("ip-claims.accounts." + e.getKey(), e.getValue().stream().map(UUID::toString).toList());
            }
        }
        snapshotSeq++;
        return yml;
    }

    /** Dumps and writes a snapshot unless a newer one is already on disk (any thread). */
    private boolean write(Yaml snap, long seq) {
        synchronized (writeLock) {
            if (seq <= writtenSeq) return true;
            boolean ok = SafeYaml.write(file, HEADER + snap.saveToString(), plugin.getLogger());
            if (ok) writtenSeq = seq;
            return ok;
        }
    }

    /** Periodic / quit saves: snapshot now, dumped and written in the background. */
    void saveAsync() {
        Yaml snap = snapshot();
        if (snap == null) return;
        long seq = snapshotSeq;
        dirty = false;
        try {
            io.execute(() -> {
                if (!write(snap, seq)) Tasks.sync(() -> dirty = true);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            if (!write(snap, seq)) dirty = true;
        }
    }

    /** A claim waiting for its write: paid once it is on disk, rolled back if it couldn't be written. */
    private static final class Claim {
        final UUID player;
        final java.util.function.Consumer<ServerPlayer> pay;
        final Runnable rollback;
        /** 0 while the write runs, 1 written, 2 failed (set on the io thread). */
        volatile int written;

        Claim(UUID player, java.util.function.Consumer<ServerPlayer> pay, Runnable rollback) {
            this.player = player;
            this.pay = pay;
            this.rollback = rollback;
        }
    }

    /**
     * Claims whose write hasn't been settled yet, by claim number (server thread only). Keyed per claim, not per
     * player: a daily and a playtime claim of one player can both be on their way.
     */
    private final Map<Long, Claim> pendingPay = new LinkedHashMap<>();
    private long claimSeq;

    /**
     * Claims: the record already holds the claim (so a second one is refused meanwhile, {@code calendar.already});
     * it is written on the io thread and paid on the server thread once it is on disk ({@code pay}, given the player
     * as they are then). A failed write, or a player who left before that, runs {@code rollback} instead. A crash can
     * lose a reward but never pays twice. The single io thread is FIFO, and {@link #write} counts a newer snapshot that
     * is already on disk (it holds the claim too) as committed.
     */
    private void commitThenPay(UUID id, java.util.function.Consumer<ServerPlayer> pay, Runnable rollback) {
        Yaml snap = snapshot();
        if (snap == null) {
            rollback.run();
            return;
        }
        long seq = snapshotSeq;
        dirty = false;
        long key = ++claimSeq;
        Claim c = new Claim(id, pay, rollback);
        pendingPay.put(key, c);
        try {
            io.execute(() -> {
                boolean ok = writeClaim(snap, seq);
                c.written = ok ? 1 : 2;
                Tasks.sync(() -> settle(key, ok));
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            boolean ok = writeClaim(snap, seq);
            c.written = ok ? 1 : 2;
            settle(key, ok);
        }
    }

    /** {@link #write} for a claim: anything thrown counts as a failed write, so the claim is still settled. */
    private boolean writeClaim(Yaml snap, long seq) {
        try {
            return write(snap, seq);
        } catch (RuntimeException e) {
            plugin.getLogger().error("Could not save {}: {}", file, e.toString());
            return false;
        }
    }

    /** A claim's write finished (server thread): pays an online player, else rolls back. Once per claim. */
    private void settle(long key, boolean ok) {
        Claim c = pendingPay.remove(key);
        if (c == null) return;   // disable() settled it already
        ServerPlayer on = ok ? plugin.player(c.player) : null;
        if (on != null && !on.hasDisconnected()) {
            c.pay.accept(on);
            return;
        }
        c.rollback.run();
        dirty = true;
    }
}
