package net.axther.aftrhrs.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The join digest: what modules have to tell a player who just joined (a calendar reward waiting, quests, mail, bank
 * interest, stall sales, graves, open reports) as one "While you were away" message with a bullet per line, instead of
 * up to ten separate chat lines arriving over three seconds.
 *
 * <p>Modules {@link #register} a {@link Provider} in their {@code enable()} (and {@link #unregister} it in
 * {@code disable()}); it adds its lines, with their side effects (a prompt day noted, a note taken off the store), when
 * the digest runs: 60 ticks after the join, or as soon as the auth gate lets the player in ({@link #run}). While the
 * player still has to type the server code or is on the death screen it waits (every 3 s, up to 10 minutes). Each
 * player gets it once per connection.
 *
 * <p>Lines are rendered with {@link #render}: without {@code <prefix>} while the digest is on, so they read as bullets;
 * with {@code config.yml chat.join-digest: false} every line is sent on its own, with its prefix and sound, as before.
 * One line alone is sent as a normal prefixed message. Server thread.
 */
public final class JoinDigest {
    /** One thing to tell: its place in the digest (lower first), the text and the sound cue key (or null). */
    public record Line(int order, Component text, String sound) {
    }

    /** Adds the module's lines for a player who just came in (alive, past the auth gate). */
    public interface Provider {
        void collect(ServerPlayer p, List<Line> out);
    }

    private record Entry(String id, int order, Provider provider) {
    }

    /** Ticks after the join before the digest goes out (the MOTD comes at 10). */
    static final long DELAY = 60L;
    private static final long RETRY = 60L;
    private static final int MAX_TRIES = 200;

    private static final List<Entry> PROVIDERS = new CopyOnWriteArrayList<>();
    /** Players who got their digest in this connection (cleared when they leave). */
    private static final Set<UUID> DONE = ConcurrentHashMap.newKeySet();
    private static boolean hooked;

    private JoinDigest() {
    }

    /** Adds (or replaces) a module's provider; {@code order} sorts the providers (calendar 10, quests 20 ... reports 90). */
    public static void register(String id, int order, Provider provider) {
        hook();
        PROVIDERS.removeIf(e -> e.id().equals(id));
        PROVIDERS.add(new Entry(id, order, provider));
        PROVIDERS.sort(Comparator.comparingInt(Entry::order));
    }

    public static void unregister(String id) {
        PROVIDERS.removeIf(e -> e.id().equals(id));
    }

    /** Whether join lines are batched into one message ({@code chat.join-digest}, default on). */
    public static boolean on() {
        Feather f = Feather.get();
        return f == null || f.getConfig().getBoolean("chat.join-digest", true);
    }

    /**
     * A provider's line: the message without its prefix while the digest is on (a bullet), the normal message
     * otherwise; null when the server blanked the message (such lines are left out).
     */
    public static Component render(String key, TagResolver... resolvers) {
        Text text = Feather.get().text();
        if (text.raw(key).isEmpty()) return null;
        return on() ? text.msgBare(key, resolvers) : text.msg(key, resolvers);
    }

    /** True until the player's digest went out in this connection: join lines of their own wait for it (the calendar sampler). */
    public static boolean pending(ServerPlayer p) {
        return p != null && hooked && !DONE.contains(p.getUUID());
    }

    /** The player is in (the auth gate let them through): their digest goes out now, if it hasn't yet. */
    public static void run(ServerPlayer p) {
        if (p != null) attempt(p.getUUID(), 0);
    }

    private static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            UUID id = h.player.getUUID();
            DONE.remove(id);
            Tasks.later(DELAY, () -> attempt(id, 0));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> DONE.remove(h.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            PROVIDERS.clear();
            DONE.clear();
        });
    }

    private static void attempt(UUID id, int tries) {
        Feather f = Feather.get();
        ServerPlayer p = f == null ? null : f.player(id);
        if (p == null || p.hasDisconnected() || DONE.contains(id)) return;
        // Not before the server code is typed (they get nothing until then), nor on the death screen (gifts handed
        // to a dead player vanish when they respawn).
        if (f.authPending(p) || p.isDeadOrDying()) {
            if (tries < MAX_TRIES) Tasks.later(RETRY, () -> attempt(id, tries + 1));
            else DONE.add(id);   // gave up: nothing waits for it any more
            return;
        }
        DONE.add(id);
        send(f, p, collect(f, p));
    }

    private static List<Line> collect(Feather f, ServerPlayer p) {
        List<Line> lines = new ArrayList<>();
        for (Entry e : PROVIDERS) {
            try {
                e.provider().collect(p, lines);
            } catch (RuntimeException ex) {
                f.getLogger().warn("Join digest: '{}' failed for {}", e.id(), p.getScoreboardName(), ex);
            }
        }
        lines.removeIf(l -> l == null || l.text() == null);
        lines.sort(Comparator.comparingInt(Line::order));   // stable: a provider's own lines keep their order
        return lines;
    }

    private static void send(Feather f, ServerPlayer p, List<Line> lines) {
        if (lines.isEmpty()) return;
        Text text = f.text();
        if (!on()) {
            for (Line l : lines) {
                Adv.send(p, l.text());
                if (l.sound() != null) text.sound(p, l.sound());
            }
            return;
        }
        Component body;
        if (lines.size() == 1) {
            body = text.prefix().append(lines.get(0).text());
        } else {
            List<Component> parts = new ArrayList<>(lines.size() + 1);
            parts.add(text.msg("join-digest.header"));
            for (Line l : lines) parts.add(text.msg("join-digest.line", Text.component("line", l.text())));
            body = Component.join(JoinConfiguration.newlines(), parts);
        }
        Adv.send(p, body);
        for (Line l : lines) {
            if (l.sound() == null) continue;
            text.sound(p, l.sound());
            break;
        }
    }
}
