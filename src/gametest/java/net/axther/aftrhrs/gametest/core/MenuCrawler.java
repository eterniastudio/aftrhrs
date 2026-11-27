package net.axther.aftrhrs.gametest.core;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Clicks through Feather dialogs like a player would: opens a start menu, clicks every button, and does the same in
 * every new menu that opens, breadth first (each path is replayed from the start because a click uses the menu up).
 * Menus with inputs are also clicked once with hostile values.
 */
public final class MenuCrawler {
    public record Clicked(String path, String label) {
    }

    private final Bots.Bot bot;
    private final Consumer<Bots.Bot> start;
    private final int maxDepth, maxClicks;
    public final List<Clicked> clicked = new ArrayList<>();
    public final Set<String> menus = new HashSet<>();
    private int clicks;

    public MenuCrawler(Bots.Bot bot, Consumer<Bots.Bot> start, int maxDepth, int maxClicks) {
        this.bot = bot;
        this.start = start;
        this.maxDepth = maxDepth;
        this.maxClicks = maxClicks;
    }

    static List<ActionButton> buttons(Dialog d) {
        List<ActionButton> out = new ArrayList<>();
        if (d instanceof MultiActionDialog m) {
            out.addAll(m.actions());
            m.exitAction().ifPresent(out::add);
        } else if (d instanceof NoticeDialog n) out.add(n.action());
        return out;
    }

    static String key(Dialog d) {
        StringBuilder sb = new StringBuilder(d.common().title().getString());
        for (ActionButton b : buttons(d)) sb.append('|').append(b.button().label().getString());
        return sb.toString();
    }

    /** Opens the start menu and replays a path; the dialog then showing, or null when the path no longer works. */
    private Dialog replay(List<Integer> path, boolean hostileLast) {
        bot.clear();
        start.accept(bot);
        Dialog d = bot.lastDialog();
        for (int i = 0; i < path.size(); i++) {
            if (d == null) return null;
            List<ActionButton> bs = buttons(d);
            int idx = path.get(i);
            if (idx >= bs.size()) return null;
            bot.clear();
            click(d, bs.get(idx), hostileLast && i == path.size() - 1);
            d = bot.lastDialog();
        }
        return d;
    }

    private void click(Dialog d, ActionButton b, boolean hostile) {
        if (b.action().isEmpty() || !(b.action().get() instanceof CustomAll c)) return;
        CompoundTag tag = c.additions().map(CompoundTag::copy).orElseGet(CompoundTag::new);
        for (var in : d.common().inputs()) {
            switch (in.control()) {
                case TextInput t -> tag.putString(in.key(), hostile ? "<red>../\u0000" + "W".repeat(t.maxLength() + 50) : (t.initial().isEmpty() ? "test1" : t.initial()));
                case BooleanInput bi -> tag.putBoolean(in.key(), !hostile);
                case NumberRangeInput n -> tag.putFloat(in.key(), hostile ? Float.NaN : (n.rangeInfo().start() + n.rangeInfo().end()) / 2f);
                case SingleOptionInput s -> tag.putString(in.key(), hostile ? "not-an-option" : s.entries().get(s.entries().size() - 1).id());
                default -> {
                }
            }
        }
        clicks++;
        Feather.get().dialogs().handleClick(bot.player, Dialogs.CLICK_ID, Optional.of(tag));
    }

    public void crawl() {
        Deque<List<Integer>> queue = new ArrayDeque<>();
        Dialog first = replay(List.of(), false);
        if (first == null) return;
        menus.add(key(first));
        for (int i = 0; i < buttons(first).size(); i++) queue.add(List.of(i));
        while (!queue.isEmpty() && clicks < maxClicks && !bot.player.hasDisconnected()) {
            List<Integer> path = queue.poll();
            List<Integer> parent = path.subList(0, path.size() - 1);
            Dialog before = replay(parent, false);
            if (before == null) continue;
            List<ActionButton> bs = buttons(before);
            int idx = path.get(path.size() - 1);
            if (idx >= bs.size()) continue;
            String label = bs.get(idx).button().label().getString();
            clicked.add(new Clicked(path.toString(), key(before).split("\\|")[0] + " > " + label));
            bot.clear();
            click(before, bs.get(idx), false);
            Dialog after = bot.lastDialog();
            if (!before.common().inputs().isEmpty() && clicks < maxClicks) {
                replay(path, true); // the same click with hostile input values
            }
            if (after == null || path.size() >= maxDepth) continue;
            if (!menus.add(key(after))) continue;
            for (int i = 0; i < buttons(after).size(); i++) {
                List<Integer> next = new ArrayList<>(path);
                next.add(i);
                queue.add(next);
            }
            // Leave the chest or screen a click may have opened before the next replay.
            bot.player.closeContainer();
        }
    }
}
