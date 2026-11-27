package net.axther.aftrhrs.feature.calendar;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Timezones;
import net.axther.aftrhrs.feature.calendar.CalendarModule.Rec;
import net.axther.aftrhrs.feature.calendar.CalendarModule.Status;
import net.axther.aftrhrs.feature.calendar.CalendarRewards.Goal;
import net.axther.aftrhrs.feature.calendar.CalendarRewards.Reward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The calendar dialog ({@code /calendar}, Daily → Calendar): the 7-day cycle as a row (hover a day for its reward),
 * the streak, grace days left this week, when the next day starts in the player's own time zone, today's playtime
 * with its tiers, the next lifetime milestone, a small month view of the days they were on, and Claim buttons for
 * whatever is waiting.
 */
final class CalendarScreen {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final Feather plugin;
    private final CalendarModule cal;

    CalendarScreen(Feather plugin, CalendarModule cal) {
        this.plugin = plugin;
        this.cal = cal;
    }

    void open(ServerPlayer player) {
        if (plugin.authPending(player)) return;
        Text text = plugin.text();
        Rec r = cal.rec(player);
        Status st = cal.status(r);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("calendar.title"));
        if (cal.closed()) menu.text(text.msg("calendar.closed"), 320);

        // Streak and grace.
        menu.text(text.msg(st.streak() > 0 ? "calendar.streak" : "calendar.streak-none", Text.unparsed("streak", st.streak()),
                Text.unparsed("best", r.best), Text.unparsed("grace", st.graceLeft()), Text.unparsed("grace-max", cal.graceDays)), 320);

        // The cycle row.
        List<Component> cells = new ArrayList<>();
        for (int d = 1; d <= CalendarModule.CYCLE; d++) {
            Reward reward = cal.dayReward(d);
            String state;
            if (d < st.cycleDay()) state = "claimed";
            else if (d == st.cycleDay()) state = st.claimed() ? "today-claimed" : "today";
            else state = d == CalendarModule.CYCLE ? "big" : "upcoming";
            Component cell = text.msg("calendar.row." + state, Text.unparsed("day", d), Text.component("short", CalendarRewards.shortForm(text, reward)));
            cells.add(cell.hoverEvent(HoverEvent.showText(text.msg("calendar.row.hover", Text.unparsed("day", d),
                    Text.component("reward", CalendarRewards.describe(text, reward))))));
        }
        menu.text(Component.join(JoinConfiguration.separator(text.msg("calendar.row.gap")), cells), 320);

        // Today's line (and what happens to the streak).
        Reward today = cal.dayReward(st.cycleDay());
        String key = st.claimed() ? "calendar.today-claimed" : "calendar.today-ready";
        List<Component> lines = new ArrayList<>();
        lines.add(text.msg(key, Text.unparsed("day", st.cycleDay()), Text.component("reward", CalendarRewards.describe(text, today))));
        if (!st.claimed() && st.missed() > 0) {
            lines.add(text.msg(st.alive() ? "calendar.grace-will-cover" : "calendar.streak-broken",
                    Text.unparsed("count", st.missed()), Text.unparsed("best", r.best)));
        }
        if (st.cycleDay() != CalendarModule.CYCLE) {
            lines.add(text.msg("calendar.big-day", Text.unparsed("days", CalendarModule.CYCLE - st.cycleDay()),
                    Text.component("reward", CalendarRewards.describe(text, cal.dayReward(CalendarModule.CYCLE)))));
        }
        long now = System.currentTimeMillis();
        long next = cal.nextDayAt();
        Timezones zones = plugin.timezones();
        ZoneId mine = zones != null ? zones.zone(player) : cal.zone;
        lines.add(text.msg("calendar.next-day", Text.unparsed("time", Placeholders.duration(next - now)),
                Text.unparsed("clock", HOUR.format(Instant.ofEpochMilli(next).atZone(mine))), Text.unparsed("zone", mine.getId())));
        menu.text(Component.join(JoinConfiguration.newlines(), lines), 320);

        // Playtime.
        List<Component> play = new ArrayList<>();
        play.add(text.msg("calendar.played-today", Text.unparsed("time", CalendarModule.played(r.playToday))));
        List<Component> tierCells = new ArrayList<>();
        boolean shownNext = false;
        for (Goal g : cal.tiers) {
            String state;
            if (r.tiersHit.contains(g.id())) state = waiting(r, g, true) ? "ready" : "done";
            else {
                state = shownNext ? "locked" : "next";
                shownNext = true;
            }
            tierCells.add(text.msg("calendar.tier." + state, Text.unparsed("time", CalendarModule.clock(g.ms())),
                    Text.component("short", CalendarRewards.shortForm(text, g.reward())),
                    Text.unparsed("left", CalendarModule.played(g.ms() - r.playToday)))
                    .hoverEvent(HoverEvent.showText(CalendarRewards.describe(text, g.reward()))));
        }
        if (!tierCells.isEmpty()) play.add(Component.join(JoinConfiguration.separator(text.msg("calendar.tier.gap")), tierCells));
        Goal nextMilestone = null;
        for (Goal g : cal.milestones) {
            if (!r.milestonesHit.contains(g.id())) {
                nextMilestone = g;
                break;
            }
        }
        if (nextMilestone != null) {
            play.add(text.msg("calendar.lifetime", Text.unparsed("time", CalendarModule.played(r.playTotal)),
                    Text.unparsed("goal", CalendarModule.clock(nextMilestone.ms())),
                    Text.component("reward", CalendarRewards.describe(text, nextMilestone.reward())),
                    Text.unparsed("left", CalendarModule.played(nextMilestone.ms() - r.playTotal))));
        } else if (!cal.milestones.isEmpty()) {
            play.add(text.msg("calendar.lifetime-done", Text.unparsed("time", CalendarModule.played(r.playTotal))));
        }
        int waiting = cal.waitingGoals(r).size();
        if (waiting > 0) play.add(text.msg("calendar.waiting", Text.unparsed("count", waiting)));
        menu.text(Component.join(JoinConfiguration.newlines(), play), 320);

        if (cal.monthView) menu.text(month(text, r), 320);

        if (!st.claimed() && !cal.closed()) {
            menu.button(text.msg(st.cycleDay() == CalendarModule.CYCLE ? "calendar.claim-big" : "calendar.claim", Text.unparsed("day", st.cycleDay())),
                    text.msg("calendar.claim-tooltip", Text.component("reward", CalendarRewards.describe(text, today))), 150, p -> {
                        cal.claimDaily(p);
                        open(p);
                    });
        }
        if (waiting > 0 && !cal.closed()) {
            menu.button(text.msg("calendar.claim-playtime", Text.unparsed("count", waiting)), text.msg("calendar.claim-playtime-tooltip"), 150, p -> {
                cal.claimPlaytime(p);
                open(p);
            });
        }
        menu.columns(2);
        plugin.exitButton(menu, player);
        menu.open(player);
    }

    private static boolean waiting(Rec r, Goal g, boolean tier) {
        String prefix = tier ? "t:" + g.id() + ":" : "m:" + g.id();
        for (String w : r.waiting) if (tier ? w.startsWith(prefix) : w.equals(prefix)) return true;
        return false;
    }

    /** This month in the reset zone, Monday first: the days they were on, today, the rest. */
    private Component month(Text text, Rec r) {
        LocalDate today = LocalDate.now(cal.zone);
        LocalDate first = today.withDayOfMonth(1);
        List<Component> rows = new ArrayList<>();
        rows.add(text.msg("calendar.month.title", Text.unparsed("month", MONTH.format(first)),
                Text.unparsed("count", r.seen.stream().filter(d -> d >= first.toEpochDay() && d <= today.toEpochDay()).count())));
        List<Component> row = new ArrayList<>();
        int lead = first.getDayOfWeek().getValue() - 1;
        for (int i = 0; i < lead; i++) row.add(text.msg("calendar.month.blank"));
        for (LocalDate d = first; d.getMonth() == first.getMonth(); d = d.plusDays(1)) {
            String num = String.format(Locale.ROOT, "%02d", d.getDayOfMonth());
            long ed = d.toEpochDay();
            String state = ed == today.toEpochDay() ? (r.seen.contains(ed) ? "today-on" : "today")
                    : ed > today.toEpochDay() ? "future" : r.seen.contains(ed) ? "on" : "off";
            row.add(text.msg("calendar.month." + state, Text.unparsed("n", num)));
            if (row.size() == 7) {
                rows.add(Component.join(JoinConfiguration.separator(Component.space()), row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(Component.join(JoinConfiguration.separator(Component.space()), row));
        return Component.join(JoinConfiguration.newlines(), rows);
    }
}
