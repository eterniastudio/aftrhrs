package net.axther.aftrhrs.feature.chatgames;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.chatgames.ChatGamesStore.Rec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Chat Games screen ({@code /chatgames}, Community → Chat Games): your wins, fastest win and streak, the top 10 this
 * week and of all time, and a button to turn the games on or off. {@code /chatgames top} prints the same lists in chat.
 */
final class ChatGamesScreen {
    private static final int TOP = 10;

    private final Feather plugin;
    private final ChatGamesModule games;

    ChatGamesScreen(Feather plugin, ChatGamesModule games) {
        this.plugin = plugin;
        this.games = games;
    }

    static String seconds(long ms) {
        return ms <= 0 ? "-" : String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    void open(ServerPlayer player) {
        if (plugin.authPending(player)) return;
        Text text = plugin.text();
        ChatGamesStore store = games.store;
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("chatgames.menu.title"));
        if (store.unreadable) menu.text(text.msg("chatgames.closed"), 320);
        long week = games.thisWeek();
        Rec r = store.recs.get(player.getUUID());
        menu.text(you(text, r, week), 320);
        menu.text(list(text, "chatgames.menu.week-title", store.topWeek(TOP, week), week, true, player.getUUID()), 320);
        menu.text(list(text, "chatgames.menu.all-title", store.topAllTime(TOP), week, false, player.getUUID()), 320);
        menu.text(text.msg("chatgames.menu.how", Text.unparsed("cap", games.hourlyCap)), 320);
        boolean on = games.shows(player);
        menu.button(text.msg(on ? "chatgames.menu.turn-off" : "chatgames.menu.turn-on"), text.msg("chatgames.menu.toggle-tooltip"), 300, p -> {
            games.toggle(p);
            open(p);
        });
        menu.columns(1);
        plugin.exitButton(menu, player);
        menu.open(player);
    }

    private static Component you(Text text, Rec r, long week) {
        return text.msg("chatgames.menu.you", Text.unparsed("wins", r == null ? 0 : r.wins), Text.unparsed("week", r == null ? 0 : r.weekWins(week)),
                Text.unparsed("fastest", seconds(r == null ? 0 : r.fastestMs)), Text.unparsed("streak", r == null ? 0 : r.streak),
                Text.unparsed("best", r == null ? 0 : r.bestStreak));
    }

    private Component list(Text text, String titleKey, List<Rec> top, long week, boolean weekly, UUID me) {
        List<Component> lines = new ArrayList<>();
        lines.add(text.msg(titleKey));
        if (top.isEmpty()) lines.add(text.msg("chatgames.menu.empty"));
        int rank = 0;
        for (Rec r : top) {
            rank++;
            lines.add(text.msg(r.id.equals(me) ? "chatgames.menu.row-you" : "chatgames.menu.row", Text.unparsed("rank", rank),
                    Text.unparsed("name", r.name == null ? "?" : r.name), Text.unparsed("wins", weekly ? r.weekWins(week) : r.wins),
                    Text.unparsed("fastest", seconds(r.fastestMs))));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** /chatgames top (and /chatgames from the console): both lists as chat lines. */
    void chatTop(Sender to) {
        Text text = plugin.text();
        ChatGamesStore store = games.store;
        long week = games.thisWeek();
        ServerPlayer p = to.player();
        UUID me = p == null ? null : p.getUUID();
        to.sendMessage(list(text, "chatgames.menu.week-title", store.topWeek(TOP, week), week, true, me));
        to.sendMessage(list(text, "chatgames.menu.all-title", store.topAllTime(TOP), week, false, me));
        if (p != null) to.sendMessage(you(text, store.recs.get(p.getUUID()), week));
    }
}
