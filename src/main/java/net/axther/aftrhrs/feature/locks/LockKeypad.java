package net.axther.aftrhrs.feature.locks;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The PIN keypad: a 6-row chest. Row 2 shows the entered digits (lime) and the empty cells (gray), centered; digits
 * 1-9 are light-blue panes whose stack size is the digit, in a 3x3 block (slots 30-32, 39-41, 48-50). The arrow in
 * the bottom-left corner deletes the last digit, the red pane in the bottom-right cancels. Once enough digits are
 * typed the PIN is handed to the session's callback, which decides what happens next (open, retry, confirm ...).
 * The PIN itself is never shown back: entered cells are plain dots. Every slot is locked ({@link Gui}).
 */
final class LockKeypad {
    static final int[] DIGIT_SLOTS = {30, 31, 32, 39, 40, 41, 48, 49, 50};
    static final int DELETE = 45;
    static final int CANCEL = 53;
    static final int INFO = 4;

    /** Gets the finished PIN, with the keypad still open. */
    interface Done {
        void accept(ServerPlayer player, Session session, String pin);
    }

    /** One open keypad. */
    static final class Session {
        final int length;
        final Done done;
        final StringBuilder digits = new StringBuilder();
        Gui gui;

        Session(int length, Done done) {
            this.length = length;
            this.done = done;
        }
    }

    private final Feather plugin;

    LockKeypad(Feather plugin) {
        this.plugin = plugin;
    }

    /** Opens a fresh keypad titled {@code title} for a PIN of {@code length} digits. */
    void open(ServerPlayer player, Component title, int length, Component infoName, List<Component> infoLore, Done done) {
        Session s = new Session(length, done);
        Gui gui = Gui.create(6, title);
        s.gui = gui;
        gui.attach(s);
        ItemStack filler = Icons.icon(Items.STAINED_GLASS_PANE.black()).hideTooltip().build();
        gui.fill(0, 53, filler);
        gui.set(INFO, Icons.icon(Items.TRIPWIRE_HOOK).name(infoName).lore(infoLore).flags().build());
        Text text = plugin.text();
        for (int d = 1; d <= 9; d++) {
            int digit = d;
            ItemStack pane = Icons.icon(Items.STAINED_GLASS_PANE.lightBlue())
                    .name(text.itemMsg("locks.gui.digit-name", Text.unparsed("digit", d)))
                    .lore(text.lines("locks.gui.digit-lore", Text.unparsed("digit", d))).count(d).flags().build();
            gui.set(DIGIT_SLOTS[d - 1], pane, c -> press(c.player(), s, digit));
        }
        gui.set(DELETE, Icons.icon(Items.ARROW).name(text.itemMsg("locks.gui.delete")).lore(text.lines("locks.gui.delete-lore")).flags().build(),
                c -> delete(c.player(), s));
        gui.set(CANCEL, Icons.icon(Items.STAINED_GLASS_PANE.red()).name(text.itemMsg("locks.gui.cancel")).lore(text.lines("locks.gui.cancel-lore")).flags().build(),
                c -> {
                    text.sound(c.player(), "click");
                    c.player().closeContainer();
                    text.send(c.player(), "locks.cancelled");
                });
        drawDisplay(s);
        gui.open(player);
    }

    /** Clears the typed digits (after a wrong PIN) and redraws. */
    void clear(Session session) {
        session.digits.setLength(0);
        drawDisplay(session);
    }

    /** Row 2: one cell per digit of the PIN, centered. */
    private void drawDisplay(Session s) {
        Text text = plugin.text();
        int start = 9 + (9 - s.length) / 2;
        for (int i = 0; i < s.length; i++) {
            boolean filled = i < s.digits.length();
            Item pane = filled ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.gray();
            s.gui.set(start + i, Icons.icon(pane).name(text.itemMsg(filled ? "locks.gui.entered" : "locks.gui.empty")).flags().build());
        }
    }

    private void delete(ServerPlayer player, Session s) {
        if (s.digits.length() > 0) s.digits.setLength(s.digits.length() - 1);
        plugin.text().sound(player, "click");
        drawDisplay(s);
    }

    private void press(ServerPlayer player, Session s, int digit) {
        if (Gui.of(player) != s.gui) return;
        if (s.digits.length() >= s.length) return;
        s.digits.append(digit);
        plugin.text().sound(player, "lock-digit", 1.0f + 0.08f * digit);
        drawDisplay(s);
        if (s.digits.length() == s.length) s.done.accept(player, s, s.digits.toString());
    }
}
