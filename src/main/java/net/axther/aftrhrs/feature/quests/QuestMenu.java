package net.axther.aftrhrs.feature.quests;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Timezones;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Daily screens: Quests (today's and this week's quests with progress bars, rewards, the time until each reset
 * in the player's own time zone, Claim buttons and the daily reroll) and Server Goal (the goal, its progress, the top
 * contributors, what it unlocks, active boosts and the reward to claim).
 */
final class QuestMenu {
    private static final int WIDTH = 300;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);

    private final QuestsModule quests;

    QuestMenu(QuestsModule quests) {
        this.quests = quests;
    }

    private Text text() {
        return quests.text();
    }

    /** The zone the player sees times in (their /timezone when tool stats run, else the reset zone). */
    private ZoneId zone(ServerPlayer p) {
        Timezones zones = quests.plugin().timezones();
        return zones == null ? quests.zone : zones.zone(p);
    }

    /** "▮▮▮▯▯▯▯▯▯▯" in the palette. */
    Component bar(long have, long need, int cells) {
        int full = need <= 0 ? cells : (int) Math.min(cells, have * cells / need);
        String f = text().raw("quests.menu.bar-full-char"), e = text().raw("quests.menu.bar-empty-char");
        return text().msg("quests.menu.bar", Text.unparsed("full", f.repeat(full)), Text.unparsed("empty", e.repeat(cells - full)));
    }

    // ------------------------------------------------------------------ quests

    void open(ServerPlayer p) {
        if (quests.store.unreadable) {
            quests.error(p, "quests.closed");
            return;
        }
        QuestStore.Quests q = quests.state(p);
        Text text = text();
        Dialogs.Menu m = quests.plugin().dialogs().menu(text.msg("quests.menu.title"));
        long now = System.currentTimeMillis();
        ZoneId zone = zone(p);
        long nextDay = quests.nextDay(), nextWeek = quests.nextWeek();
        m.text(text.msg("quests.menu.daily-header", Text.unparsed("time", Placeholders.duration(nextDay - now)),
                Text.unparsed("clock", CLOCK.format(Instant.ofEpochMilli(nextDay).atZone(zone)))), WIDTH);
        m.text(lines(q.daily), WIDTH);
        m.text(text.msg("quests.menu.weekly-header", Text.unparsed("time", Placeholders.duration(nextWeek - now)),
                Text.unparsed("clock", DAY_CLOCK.format(Instant.ofEpochMilli(nextWeek).atZone(zone)))), WIDTH);
        m.text(lines(q.weekly), WIDTH);
        if (quests.goalEnabled && quests.goal.boost("quest-coins") > 1) {
            ServerGoal.Boost b = quests.goal.boosts.get("quest-coins");
            m.text(text.msg("quests.menu.boost", Text.component("boost", quests.boostName(b.type(), b.multiplier())),
                    Text.unparsed("time", Placeholders.duration(b.until() - now))), WIDTH);
        }
        if (quests.pets() != null) m.text(text.msg("quests.menu.bond-hint"), WIDTH);

        for (int w = 0; w < 2; w++) {
            List<QuestStore.Slot> slots = w == 0 ? q.daily : q.weekly;
            boolean weekly = w == 1;
            for (int i = 0; i < slots.size(); i++) {
                QuestStore.Slot s = slots.get(i);
                QuestPool.Def d = quests.pool.def(s.id);
                if (d == null || s.claimed || s.progress < d.amount()) continue;
                int index = i;
                String id = s.id;
                m.button(text.msg("quests.menu.claim", Text.unparsed("task", quests.task(d))),
                        text.msg("quests.menu.claim-tooltip", Text.component("reward", quests.rewardText(quests.boosted(d.reward().coins()), d.reward().bond(), -1))),
                        WIDTH, pl -> quests.claim(pl, weekly, index, id));
            }
        }
        int left = quests.rerollsPerDay - q.rerolls;
        if (left > 0 && rerollable(q) > 0) {
            m.button(text.msg("quests.menu.reroll", Text.unparsed("left", left)), text.msg("quests.menu.reroll-tooltip"), WIDTH, this::openReroll);
        }
        if (quests.goalEnabled && Perms.has(p, QuestsModule.GOAL_PERM)) {
            m.button(text.msg("quests.menu.goal"), text.msg("quests.menu.goal-tooltip"), WIDTH, this::openGoal);
        }
        m.columns(1);
        quests.plugin().exitButton(m, p);
        m.open(p);
        text.sound(p, "menu-open");
    }

    private int rerollable(QuestStore.Quests q) {
        int n = 0;
        for (QuestStore.Slot s : q.daily) {
            QuestPool.Def d = quests.pool.def(s.id);
            if (d != null && !s.claimed && s.progress < d.amount()) n++;
        }
        return n;
    }

    private Component lines(List<QuestStore.Slot> slots) {
        Text text = text();
        if (slots.isEmpty()) return text.msg("quests.menu.none");
        List<Component> out = new ArrayList<>();
        for (QuestStore.Slot s : slots) {
            QuestPool.Def d = quests.pool.def(s.id);
            if (d == null) continue;
            String status = s.claimed ? "claimed" : s.progress >= d.amount() ? "ready" : "open";
            out.add(text.msg("quests.menu.quest-" + status, Text.unparsed("task", quests.task(d)),
                    Text.component("difficulty", text.msg("quests.difficulty." + d.difficulty())),
                    Text.component("bar", bar(s.progress, d.amount(), 10)),
                    Text.unparsed("have", QuestsModule.fmt(s.progress)), Text.unparsed("need", QuestsModule.fmt(d.amount())),
                    Text.component("reward", quests.rewardText(quests.boosted(d.reward().coins()), d.reward().bond(), -1))));
        }
        return Component.join(JoinConfiguration.newlines(), out);
    }

    void openReroll(ServerPlayer p) {
        QuestStore.Quests q = quests.state(p);
        Text text = text();
        int left = quests.rerollsPerDay - q.rerolls;
        Dialogs.Menu m = quests.plugin().dialogs().menu(text.msg("quests.menu.reroll-title"));
        m.text(text.msg(left > 0 ? "quests.menu.reroll-body" : "quests.menu.reroll-used", Text.unparsed("left", Math.max(0, left))), WIDTH);
        if (left > 0) {
            for (int i = 0; i < q.daily.size(); i++) {
                QuestStore.Slot s = q.daily.get(i);
                QuestPool.Def d = quests.pool.def(s.id);
                if (d == null || s.claimed || s.progress >= d.amount()) continue;
                int index = i;
                String id = s.id;
                m.button(text.msg("quests.menu.reroll-pick", Text.unparsed("task", quests.task(d)),
                                Text.component("difficulty", text.msg("quests.difficulty." + d.difficulty()))),
                        null, WIDTH, pl -> quests.reroll(pl, index, id));
            }
        }
        m.columns(1);
        m.exit(text.msg("menu.back"), this::open);
        m.open(p);
        text.sound(p, "page");
    }

    // ------------------------------------------------------------------ server goal

    void openGoal(ServerPlayer p) {
        Text text = text();
        ServerGoal g = quests.goal;
        g.check();
        Dialogs.Menu m = quests.plugin().dialogs().menu(text.msg("quests.goal.menu.title"));
        long now = System.currentTimeMillis();
        QuestPool.Goal def = g.def();
        if (!quests.goalEnabled || def == null) {
            m.text(text.msg("quests.goal.menu.none"), WIDTH);
        } else {
            String label = g.label();
            m.text(text.msg(g.completed() ? "quests.goal.menu.goal-done" : "quests.goal.menu.goal", Text.unparsed("goal", label),
                    Text.unparsed("time", Placeholders.duration(quests.nextWeek() - now))), WIDTH);
            m.text(text.msg("quests.goal.menu.progress", Text.component("bar", bar(g.progress, g.target, 20)),
                    Text.unparsed("have", QuestsModule.fmt(g.progress)), Text.unparsed("need", QuestsModule.fmt(g.target)),
                    Text.unparsed("percent", g.percent())), WIDTH);

            List<Component> top = new ArrayList<>();
            top.add(text.msg("quests.goal.menu.top-title"));
            List<Map.Entry<UUID, Long>> best = g.top(quests.goalTopShown);
            if (best.isEmpty()) top.add(text.msg("quests.goal.menu.top-empty"));
            for (int i = 0; i < best.size(); i++) {
                top.add(text.msg("quests.goal.menu.top-row", Text.unparsed("rank", i + 1), Text.unparsed("player", g.nameOf(best.get(i).getKey())),
                        Text.unparsed("amount", QuestsModule.fmt(best.get(i).getValue())),
                        Text.component("medal", i < 3 ? text.msg("leaderboard.medal-" + (i + 1)) : Component.empty())));
            }
            long mine = g.contributions.getOrDefault(p.getUUID(), 0L);
            long min = g.minContribution();
            top.add(text.msg(mine >= min ? "quests.goal.menu.you-enough" : "quests.goal.menu.you", Text.unparsed("amount", QuestsModule.fmt(mine)),
                    Text.unparsed("min", QuestsModule.fmt(min))));
            m.text(Component.join(JoinConfiguration.newlines(), top), WIDTH);

            List<Component> unlocks = new ArrayList<>();
            unlocks.add(text.msg("quests.goal.menu.unlocks-title"));
            if (def.reward().coins() > 0 || def.reward().bond() > 0) {
                unlocks.add(text.msg("quests.goal.menu.unlocks-reward", Text.unparsed("min", QuestsModule.fmt(min)),
                        Text.component("reward", quests.rewardText(def.reward().coins(), def.reward().bond(), -1))));
            }
            for (QuestPool.Boost b : def.boosts()) {
                unlocks.add(text.msg("quests.goal.menu.unlocks-boost", Text.component("boost", quests.boostName(b.type(), b.multiplier())),
                        Text.unparsed("hours", b.hours())));
            }
            if (def.unlocks() != null && !def.unlocks().isBlank()) {
                unlocks.add(text.msg("quests.goal.menu.unlocks-extra", Text.component("extra", text.parse(def.unlocks()))));
            }
            if (unlocks.size() > 1) m.text(Component.join(JoinConfiguration.newlines(), unlocks), WIDTH);
        }
        for (ServerGoal.Boost b : g.boosts.values()) {
            if (b.until() <= now) continue;
            m.text(text.msg("quests.goal.menu.boost-active", Text.component("boost", quests.boostName(b.type(), b.multiplier())),
                    Text.unparsed("time", Placeholders.duration(b.until() - now))), WIDTH);
        }
        List<ServerGoal.Pending> waiting = g.rewards.get(p.getUUID());
        if (waiting != null && !waiting.isEmpty()) {
            long coins = 0;
            int bond = 0;
            for (ServerGoal.Pending r : waiting) {
                coins += r.coins();
                bond += r.bond();
            }
            m.text(text.msg("quests.goal.menu.reward-waiting", Text.unparsed("goal", waiting.get(waiting.size() - 1).label()),
                    Text.unparsed("count", waiting.size())), WIDTH);
            m.button(text.msg("quests.goal.menu.claim", Text.component("reward", quests.rewardText(coins, bond, -1))),
                    text.msg("quests.goal.menu.claim-tooltip"), WIDTH, quests::claimGoal);
        }
        if (Perms.has(p, QuestsModule.PERM)) m.button(text.msg("quests.goal.menu.quests"), null, WIDTH, this::open);
        m.columns(1);
        quests.plugin().exitButton(m, p);
        m.open(p);
        text.sound(p, "menu-open");
    }
}
