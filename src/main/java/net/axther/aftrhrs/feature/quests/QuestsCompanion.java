package net.axther.aftrhrs.feature.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.net.Companion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The quest tracker for the Companion HUD ({@code quests} feature, docs/companion-protocol.md): a modded player's
 * daily and weekly quests and the server goal, as plain-text data for the HUD. Changes only mark the player (or, for
 * a change to the goal itself, everyone); a once-a-second task sends what differs from the last message, so a burst of
 * progress is one message.
 *
 * <p>While only the server goal's progress moves (everyone's contributions), clients that agreed on
 * {@code quest_goal} get the small shared {@code {"t":"quest_goal","goal":{...}}} instead of their whole list again,
 * encoded once for all of them; older clients still get the full {@code quests} message.
 */
final class QuestsCompanion {
    private static final String FEATURE = "quests", GOAL_FEATURE = "quest_goal";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final QuestsModule m;
    /** The daily and weekly lists each player last got (the message without the goal). */
    private final Map<UUID, String> sent = new HashMap<>();
    /** The goal each player last got, in a {@code quests} or a {@code quest_goal} message ("" for none). */
    private final Map<UUID, String> goalSent = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    /** The server goal's shape as last seen (id, target, done, quest-coin boost): a change re-sends every list. */
    private String goalShapeSeen;
    /** The server goal's progress as last seen: a change alone sends quest_goal. */
    private long goalProgressSeen = Long.MIN_VALUE;
    /** The goal reward's text for {@link #rewardKey} (the goal id, its reward, and whether the bank and pets run). */
    private String rewardKey, rewardText;
    /** Quest reward texts of this flush, by {@code (coins << 20) ^ bond}. */
    private final Map<Long, String> rewardMemo = new HashMap<>();
    private Tasks.Task task;

    QuestsCompanion(QuestsModule m) {
        this.m = m;
    }

    void start() {
        task = Tasks.timer(20L, 20L, this::flush);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** The config or messages were reloaded: the goal's reward text is built again. */
    void reload() {
        rewardKey = null;
    }

    /** A linked player left: they get a full list after their next hello. */
    void quit(ServerPlayer p) {
        sent.remove(p.getUUID());
        goalSent.remove(p.getUUID());
        dirty.remove(p.getUUID());
    }

    /** This player's quests changed (progress, a new day or week, a reroll); sent on the next flush. */
    void changed(ServerPlayer p) {
        if (p != null) dirty.add(p.getUUID());
    }

    private void flush() {
        rewardMemo.clear();
        ServerGoal g = m.goal;
        String shape = m.goalEnabled ? g.id + "|" + g.target + "|" + g.completed() + "|" + g.boost("quest-coins") : "";
        long progress = g.progress;
        boolean shapeChanged = !shape.equals(goalShapeSeen);
        boolean progressChanged = progress != goalProgressSeen;
        List<ServerPlayer> online = m.plugin().onlinePlayers();
        // Rewards are boosted, and the goal may have appeared, gone or completed: every list again.
        if (shapeChanged) for (ServerPlayer p : online) dirty.add(p.getUUID());
        send(online, progressChanged);
        goalShapeSeen = shape;
        goalProgressSeen = progress;
        dirty.clear();
    }

    /** Sends what changed to every player who needs it; the goal is built (once) only when one of them does. */
    private void send(List<ServerPlayer> online, boolean progressChanged) {
        JsonObject[] goal = new JsonObject[1];
        String[] goalKey = new String[1];
        AtomicReference<List<byte[]>> shared = new AtomicReference<>();   // the quest_goal message, encoded once
        // lists() turns players over (state()): several joins in one flush share one bank and one quests snapshot.
        m.batchRollovers(() -> {
            for (ServerPlayer p : online) {
                UUID id = p.getUUID();
                // a player not sent anything yet (just said hello) gets their first list now
                boolean full = dirty.contains(id) || !sent.containsKey(id);
                if (!full && !progressChanged) continue;   // nothing changed for them: no permission checks either
                if (!Companion.has(p, FEATURE) || !Perms.has(p, QuestsModule.PERM) || m.store.unreadable || m.plugin().authPending(p)) continue;
                if (goalKey[0] == null) {
                    goal[0] = goalJson();
                    goalKey[0] = goal[0] == null ? "" : goal[0].toString();
                }
                String goalFor = goal[0] != null && Perms.has(p, QuestsModule.GOAL_PERM) ? goalKey[0] : "";
                if (full) {
                    sendFull(p, goal[0], goalFor);
                } else if (!goalFor.equals(goalSent.get(id))) {
                    if (goalFor.isEmpty()) {
                        sendFull(p, goal[0], goalFor);   // they lost the goal permission: the list without it
                    } else if (Companion.has(p, GOAL_FEATURE)) {
                        if (shared.get() == null) {
                            JsonObject msg = Companion.message(GOAL_FEATURE);
                            msg.add("goal", goal[0]);
                            shared.set(Companion.encode(msg));
                        }
                        Companion.sendEncoded(p, GOAL_FEATURE, shared.get());
                        goalSent.put(id, goalFor);
                    } else {
                        sendFull(p, goal[0], goalFor);   // a client from before quest_goal
                    }
                }
            }
        });
    }

    /** The whole {@code quests} message, when the lists or the goal differ from what the player last got. */
    private void sendFull(ServerPlayer p, JsonObject goal, String goalFor) {
        UUID id = p.getUUID();
        JsonObject msg = lists(p);
        String key = msg.toString();
        if (key.equals(sent.get(id)) && goalFor.equals(goalSent.get(id))) return;
        if (!goalFor.isEmpty()) msg.add("goal", goal);
        sent.put(id, key);
        goalSent.put(id, goalFor);
        Companion.send(p, msg);
    }

    /** The {@code quests} message without the goal: the player's daily and weekly quests. */
    private JsonObject lists(ServerPlayer p) {
        QuestStore.Quests q = m.state(p);
        JsonObject msg = Companion.message("quests");
        msg.add("daily", quests(q.daily));
        msg.add("weekly", quests(q.weekly));
        return msg;
    }

    /** The server goal as the HUD shows it (shared by every message of a flush), or null while it is off or none runs. */
    private JsonObject goalJson() {
        ServerGoal g = m.goal;
        QuestPool.Goal def = m.goalEnabled ? g.def() : null;
        if (def == null) return null;
        String rk = g.id + "|" + def.reward().coins() + "|" + def.reward().bond()
                + "|" + (m.plugin().service(Hooks.Bank.class) != null) + "|" + (m.pets() != null);
        if (!rk.equals(rewardKey)) {
            rewardKey = rk;
            rewardText = plain(m.rewardText(def.reward().coins(), def.reward().bond(), -1));
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", g.label());
        o.addProperty("progress", g.progress);
        o.addProperty("goal", g.target);
        o.addProperty("done", g.completed());
        o.addProperty("reward", rewardText);
        return o;
    }

    private JsonArray quests(List<QuestStore.Slot> slots) {
        JsonArray arr = new JsonArray();
        for (QuestStore.Slot s : slots) {
            QuestPool.Def d = m.pool.def(s.id);
            if (d == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id());
            o.addProperty("name", m.task(d));
            o.addProperty("progress", s.progress);
            o.addProperty("goal", d.amount());
            o.addProperty("done", s.progress >= d.amount());
            o.addProperty("reward", reward(m.boosted(d.reward().coins()), d.reward().bond()));
            arr.add(o);
        }
        return arr;
    }

    private String reward(long coins, int bond) {
        return rewardMemo.computeIfAbsent((coins << 20) ^ bond, k -> plain(m.rewardText(coins, bond, -1)));
    }

    private static String plain(Component c) {
        return PLAIN.serialize(c);
    }
}
