package net.axther.aftrhrs.core;

import net.axther.aftrhrs.AftrHrs;
import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Feather's menus on vanilla dialogs ({@code net.minecraft.server.dialog}; Paper's dialog API was a wrapper over
 * these). Same shape as the plugin's {@code Dialogs}: {@link #menu} → {@link Menu#text}/{@link Menu#item}/
 * {@link Menu#input}/{@link Menu#button}/{@link Menu#exit} → {@link Menu#open}. Buttons run Java callbacks on the
 * server thread; a dialog closes after a click unless the callback opens another one.
 *
 * <p>Each button is a {@code minecraft:custom} click ({@link CustomAll}, id {@code aftrhrs:menu}) carrying the menu
 * and button number plus every input value; a mixin on {@code ServerCommonPacketListenerImpl} hands it to
 * {@link #handleClick}. Like Paper's click callbacks a menu works once and for ten minutes, and input values are
 * checked against the menu's own inputs before a callback sees them ({@link Response}).
 *
 * <p>Before drawing a vanilla dialog, {@link Menu#open} offers the menu to the {@link MenuUpgrader} (the Companion
 * bridge, which draws the same menu with its own screen and calls {@link #run} for clicks).
 */
public final class Dialogs {
    /** The custom click id every Feather dialog button sends. */
    public static final Identifier CLICK_ID = AftrHrs.id("menu");
    private static final String MENU_KEY = "aftrhrs:menu", BUTTON_KEY = "aftrhrs:button";
    private static final long LIFETIME_MS = 10 * 60 * 1000L;
    /** Menus a player can have waiting at once (back-and-forth screens leave a few behind). */
    private static final int KEEP = 8;

    /** Draws menus some other way (the Companion mod's screens). Return true when it took the menu. */
    @FunctionalInterface
    public interface MenuUpgrader {
        boolean offer(ServerPlayer player, Menu menu);
    }

    private final Feather plugin;
    private volatile MenuUpgrader upgrader;
    private final Map<UUID, Deque<Pending>> pending = new HashMap<>();
    private int nextId;

    private record Pending(int id, Menu menu, long expires) {
    }

    Dialogs(Feather plugin) {
        this.plugin = plugin;
    }

    /** Installs (or with null, removes) the menu upgrader; the companion module does this. */
    public void upgrader(MenuUpgrader upgrader) {
        this.upgrader = upgrader;
    }

    public Menu menu(Component title) {
        return new Menu(title);
    }

    /** A body line: text, or an item with text beside it. */
    public record Body(Component text, ItemStack item, int width) {
    }

    /**
     * A button. {@code section} names the hub group it opens ({@link Menu#section}), so the Companion can switch to
     * that tab instead of clicking; null for ordinary buttons.
     */
    /** One button. {@code icon}: an item drawn on it by the Companion menu (EMPTY: none; vanilla dialogs show the label only). */
    public record Button(Component label, Component tooltip, int width, BiConsumer<ServerPlayer, Response> onClick, String section, ItemStack icon) {
        public Button(Component label, Component tooltip, int width, BiConsumer<ServerPlayer, Response> onClick, String section) {
            this(label, tooltip, width, onClick, section, ItemStack.EMPTY);
        }
    }

    /** A hub group shown as a tab by the Companion (vanilla clients reach it through its own button and screen). */
    public record Section(String key, Component label, List<Button> buttons) {
    }

    // ------------------------------------------------------------------ inputs

    /**
     * A dialog input: Paper's {@code DialogInput}. Build with {@link #text}, {@link #bool}, {@link #numberRange} or
     * {@link #singleOption}; keys must be letters, digits and {@code _}.
     */
    public sealed interface Input permits TextField, Toggle, Range, Choice {
        String key();

        Component label();

        static TextField.Builder text(String key, Component label) {
            return new TextField.Builder(key, label);
        }

        static Toggle.Builder bool(String key, Component label) {
            return new Toggle.Builder(key, label);
        }

        static Range.Builder numberRange(String key, Component label, float start, float end) {
            return new Range.Builder(key, label, start, end);
        }

        static Choice.Builder singleOption(String key, Component label, List<Option> options) {
            return new Choice.Builder(key, label, options);
        }
    }

    /** A text box ({@code multilineMaxLines} 0 = single line). */
    public record TextField(String key, Component label, boolean labelVisible, String initial, int maxLength, int width,
                            boolean multiline, int multilineMaxLines, int multilineHeight) implements Input {
        public static final class Builder {
            private final String key;
            private final Component label;
            private boolean labelVisible = true;
            private String initial = "";
            private int maxLength = 32, width = 200;
            private boolean multiline;
            private int maxLines, height;

            private Builder(String key, Component label) {
                this.key = key;
                this.label = label;
            }

            public Builder initial(String initial) {
                this.initial = initial == null ? "" : initial;
                return this;
            }

            public Builder maxLength(int maxLength) {
                this.maxLength = Math.max(1, maxLength);
                return this;
            }

            public Builder width(int width) {
                this.width = width;
                return this;
            }

            public Builder labelVisible(boolean visible) {
                this.labelVisible = visible;
                return this;
            }

            /** Paper's {@code multiline(MultilineOptions.create(maxLines, height))}; 0 leaves either unset. */
            public Builder multiline(int maxLines, int height) {
                this.multiline = true;
                this.maxLines = maxLines;
                this.height = height;
                return this;
            }

            public TextField build() {
                String init = initial.length() > maxLength ? initial.substring(0, maxLength) : initial;
                return new TextField(key, label, labelVisible, init, maxLength, clampWidth(width), multiline, maxLines, height);
            }
        }
    }

    /** A checkbox. */
    public record Toggle(String key, Component label, boolean initial, String onTrue, String onFalse) implements Input {
        public static final class Builder {
            private final String key;
            private final Component label;
            private boolean initial;
            private String onTrue = "true", onFalse = "false";

            private Builder(String key, Component label) {
                this.key = key;
                this.label = label;
            }

            public Builder initial(boolean initial) {
                this.initial = initial;
                return this;
            }

            public Builder onTrue(String s) {
                this.onTrue = s;
                return this;
            }

            public Builder onFalse(String s) {
                this.onFalse = s;
                return this;
            }

            public Toggle build() {
                return new Toggle(key, label, initial, onTrue, onFalse);
            }
        }
    }

    /** A slider ({@code step} and {@code initial} may be null). */
    public record Range(String key, Component label, String labelFormat, float start, float end, Float initial, Float step, int width) implements Input {
        public static final class Builder {
            private final String key;
            private final Component label;
            private final float start, end;
            private String labelFormat = "options.generic_value";
            private Float initial, step;
            private int width = 200;

            private Builder(String key, Component label, float start, float end) {
                this.key = key;
                this.label = label;
                this.start = start;
                this.end = end;
            }

            public Builder initial(Float initial) {
                this.initial = initial;
                return this;
            }

            public Builder step(Float step) {
                this.step = step != null && step > 0 ? step : null;
                return this;
            }

            public Builder width(int width) {
                this.width = width;
                return this;
            }

            public Builder labelFormat(String format) {
                this.labelFormat = format;
                return this;
            }

            public Range build() {
                Float init = initial;
                if (init != null) init = Math.max(Math.min(start, end), Math.min(Math.max(start, end), init));
                return new Range(key, label, labelFormat, start, end, init, step, clampWidth(width));
            }
        }
    }

    /** One choice of a {@link Choice}; {@code display} may be null (shows the id). */
    public record Option(String id, Component display, boolean initial) {
    }

    /** A cycling button with fixed choices. */
    public record Choice(String key, Component label, boolean labelVisible, List<Option> options, int width) implements Input {
        public static final class Builder {
            private final String key;
            private final Component label;
            private final List<Option> options;
            private boolean labelVisible = true;
            private int width = 200;

            private Builder(String key, Component label, List<Option> options) {
                this.key = key;
                this.label = label;
                this.options = List.copyOf(options);
            }

            public Builder width(int width) {
                this.width = width;
                return this;
            }

            public Builder labelVisible(boolean visible) {
                this.labelVisible = visible;
                return this;
            }

            public Choice build() {
                if (options.isEmpty()) throw new IllegalArgumentException("A single-option input needs options");
                return new Choice(key, label, labelVisible, options, clampWidth(width));
            }
        }
    }

    /**
     * The input values a button callback reads (Paper's {@code DialogResponseView}), limited to the menu's own
     * inputs and their bounds: text is cut to its max length and stripped of control characters, numbers are
     * clamped and snapped to the step, options must be one of the menu's.
     */
    public static final class Response {
        public static final Response EMPTY = new Response(List.of(), k -> null);
        private final Map<String, Object> values = new HashMap<>();

        /** Builds a response from raw values ({@code raw.apply(key)}: a String, Number or Boolean, or null). */
        public static Response of(List<Input> inputs, Function<String, Object> raw) {
            return new Response(inputs, raw);
        }

        private Response(List<Input> inputs, Function<String, Object> raw) {
            for (Input in : inputs) {
                Object v = raw.apply(in.key());
                switch (in) {
                    case TextField t -> {
                        String s = v == null ? t.initial() : String.valueOf(v);
                        s = t.multiline() ? s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "") : s.replaceAll("\\p{Cntrl}", "");
                        if (s.length() > t.maxLength()) s = s.substring(0, t.maxLength());
                        values.put(in.key(), s);
                    }
                    case Toggle b -> {
                        boolean on = b.initial();
                        if (v instanceof Boolean bool) on = bool;
                        else if (v instanceof Number n) on = n.intValue() != 0;
                        else if (v instanceof String s) on = s.equalsIgnoreCase("true") || s.equals(b.onTrue());
                        values.put(in.key(), on);
                    }
                    case Range r -> {
                        float lo = Math.min(r.start(), r.end()), hi = Math.max(r.start(), r.end());
                        float f = r.initial() != null ? r.initial() : r.start();
                        if (v instanceof Number n) f = n.floatValue();
                        else if (v instanceof String s) {
                            try {
                                f = Float.parseFloat(s);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (Float.isNaN(f)) f = lo;
                        f = Math.max(lo, Math.min(hi, f));
                        if (r.step() != null && r.step() > 0) f = Math.max(lo, Math.min(hi, lo + Math.round((f - lo) / r.step()) * r.step()));
                        values.put(in.key(), f);
                    }
                    case Choice c -> {
                        String chosen = null, first = null, initial = null;
                        for (Option o : c.options()) {
                            if (first == null) first = o.id();
                            if (initial == null && o.initial()) initial = o.id();
                            if (v != null && o.id().equals(String.valueOf(v))) chosen = o.id();
                        }
                        values.put(in.key(), chosen != null ? chosen : initial != null ? initial : first);
                    }
                }
            }
        }

        /** A text box's or option's value, or null when the menu has no such input. */
        public String getText(String key) {
            Object v = values.get(key);
            return v == null ? null : String.valueOf(v);
        }

        /** A slider's value, or null. */
        public Float getFloat(String key) {
            return values.get(key) instanceof Float f ? f : null;
        }

        /** A checkbox's value, or null. */
        public Boolean getBoolean(String key) {
            return values.get(key) instanceof Boolean b ? b : null;
        }
    }

    // ------------------------------------------------------------------ menu

    public final class Menu {
        private final Component title;
        private final List<Body> body = new ArrayList<>();
        private final List<Input> inputs = new ArrayList<>();
        private final List<Button> buttons = new ArrayList<>();
        private final List<Section> sections = new ArrayList<>();
        private Button exit;
        private int columns = 1;
        private String layout = "list";

        private Menu(Component title) {
            this.title = title;
        }

        public Menu text(Component text) {
            body.add(new Body(text, null, 280));
            return this;
        }

        public Menu text(Component text, int width) {
            body.add(new Body(text, null, width));
            return this;
        }

        /** An item (e.g. the player's head) with a description beside it. */
        public Menu item(ItemStack item, Component description, int width) {
            body.add(new Body(description, item, width));
            return this;
        }

        public Menu input(Input input) {
            inputs.add(input);
            return this;
        }

        /** Accepts an unbuilt builder too, so {@code menu.input(Input.text(...).maxLength(16))} reads like Paper's. */
        public Menu input(TextField.Builder b) {
            return input(b.build());
        }

        public Menu columns(int columns) {
            this.columns = Math.max(1, columns);
            return this;
        }

        /** A layout hint for the Companion ({@code list}, {@code hub}); vanilla dialogs ignore it. */
        public Menu layout(String layout) {
            this.layout = layout == null ? "list" : layout;
            return this;
        }

        public Menu button(Component label, Component tooltip, int width, Consumer<ServerPlayer> onClick) {
            buttons.add(new Button(label, tooltip, width, onClick == null ? null : (p, v) -> onClick.accept(p), null));
            return this;
        }

        public Menu button(Component label, Component tooltip, int width, BiConsumer<ServerPlayer, Response> onClick) {
            buttons.add(new Button(label, tooltip, width, onClick, null));
            return this;
        }

        /** A button with an item drawn on it (the Companion menu; vanilla dialogs show the label only). */
        public Menu button(Component label, Component tooltip, int width, ItemStack icon, Consumer<ServerPlayer> onClick) {
            buttons.add(new Button(label, tooltip, width, onClick == null ? null : (p, v) -> onClick.accept(p), null,
                    icon == null ? ItemStack.EMPTY : icon));
            return this;
        }

        /** A button that opens hub group {@code section} (see {@link #section}). */
        public Menu sectionButton(String section, Component label, Component tooltip, int width, Consumer<ServerPlayer> onClick) {
            buttons.add(new Button(label, tooltip, width, (p, v) -> onClick.accept(p), section));
            return this;
        }

        /** A hub group's entries, for the Companion's tabs. Vanilla dialogs don't show these. */
        public Menu section(String key, Component label, List<Button> entries) {
            sections.add(new Section(key, label, List.copyOf(entries)));
            return this;
        }

        /** The bottom button (also what Escape does). Without a callback it just closes the screen. */
        public Menu exit(Component label, Consumer<ServerPlayer> onClick) {
            exit = new Button(label, null, 150, onClick == null ? null : (p, v) -> onClick.accept(p), null);
            return this;
        }

        public boolean isEmpty() {
            return buttons.isEmpty();
        }

        public int buttonCount() {
            return buttons.size();
        }

        public int columnCount() {
            return columns;
        }

        public Component title() {
            return title;
        }

        public List<Body> body() {
            return Collections.unmodifiableList(body);
        }

        public List<Input> inputs() {
            return Collections.unmodifiableList(inputs);
        }

        public List<Button> buttons() {
            return Collections.unmodifiableList(buttons);
        }

        public List<Section> sections() {
            return Collections.unmodifiableList(sections);
        }

        public Button exitButton() {
            return exit;
        }

        public String layout() {
            return layout;
        }

        public void open(ServerPlayer player) {
            if (player == null || player.hasDisconnected()) return;
            // No menus before the server code is in (auth gate).
            if (plugin.authPending(player)) return;
            MenuUpgrader up = upgrader;
            if (up != null) {
                try {
                    if (up.offer(player, this)) return;
                } catch (RuntimeException e) {
                    plugin.getLogger().warn("Menu upgrader failed; showing the vanilla dialog", e);
                }
            }
            int id = remember(player, this);
            player.openDialog(Holder.direct(build(id)));
        }

        private Dialog build(int id) {
            List<DialogBody> parts = new ArrayList<>();
            for (Body b : body) {
                if (b.item() == null || b.item().isEmpty()) {
                    parts.add(new PlainMessage(Adv.vanilla(b.text()), clampWidth(b.width())));
                } else {
                    Optional<PlainMessage> desc = b.text() == null ? Optional.empty()
                            : Optional.of(new PlainMessage(Adv.vanilla(b.text()), clampWidth(b.width())));
                    parts.add(new ItemBody(ItemStackTemplate.fromNonEmptyStack(b.item()), desc, false, false, 16, 16));
                }
            }
            List<net.minecraft.server.dialog.Input> vanillaInputs = new ArrayList<>(inputs.size());
            for (Input in : inputs) vanillaInputs.add(vanilla(in));
            CommonDialogData common = new CommonDialogData(Adv.vanilla(title), Optional.empty(), true, false,
                    DialogAction.CLOSE, parts, vanillaInputs);
            ActionButton exitAction = exit == null ? null
                    : exit.onClick() == null ? new ActionButton(new CommonButtonData(Adv.vanilla(exit.label()), 150), Optional.empty())
                    : make(exit, id, -1);
            if (buttons.isEmpty()) {
                return new NoticeDialog(common, exitAction == null ? NoticeDialog.DEFAULT_ACTION : exitAction);
            }
            List<ActionButton> actions = new ArrayList<>(buttons.size());
            for (int i = 0; i < buttons.size(); i++) actions.add(make(buttons.get(i), id, i));
            return new MultiActionDialog(common, actions, Optional.ofNullable(exitAction), columns);
        }
    }

    private static int clampWidth(int w) {
        return Math.max(1, Math.min(1024, w));
    }

    private static ActionButton make(Button b, int menuId, int index) {
        CommonButtonData data = new CommonButtonData(Adv.vanilla(b.label()),
                b.tooltip() == null ? Optional.empty() : Optional.of(Adv.vanilla(b.tooltip())), clampWidth(b.width()));
        CompoundTag additions = new CompoundTag();
        additions.putInt(MENU_KEY, menuId);
        additions.putInt(BUTTON_KEY, index);
        return new ActionButton(data, Optional.of(new CustomAll(CLICK_ID, Optional.of(additions))));
    }

    private static net.minecraft.server.dialog.Input vanilla(Input in) {
        return switch (in) {
            case TextField t -> new net.minecraft.server.dialog.Input(t.key(), new TextInput(t.width(), Adv.vanilla(t.label()),
                    t.labelVisible(), t.initial(), t.maxLength(),
                    t.multiline() ? Optional.of(new TextInput.MultilineOptions(
                            t.multilineMaxLines() > 0 ? Optional.of(t.multilineMaxLines()) : Optional.empty(),
                            t.multilineHeight() > 0 ? Optional.of(Math.min(512, t.multilineHeight())) : Optional.empty()))
                            : Optional.empty()));
            case Toggle b -> new net.minecraft.server.dialog.Input(b.key(), new BooleanInput(Adv.vanilla(b.label()), b.initial(), b.onTrue(), b.onFalse()));
            case Range r -> new net.minecraft.server.dialog.Input(r.key(), new NumberRangeInput(r.width(), Adv.vanilla(r.label()), r.labelFormat(),
                    new NumberRangeInput.RangeInfo(r.start(), r.end(), Optional.ofNullable(r.initial()), Optional.ofNullable(r.step()))));
            case Choice c -> {
                List<SingleOptionInput.Entry> entries = new ArrayList<>();
                boolean seenInitial = false;
                for (Option o : c.options()) {
                    boolean init = o.initial() && !seenInitial;
                    seenInitial |= init;
                    entries.add(new SingleOptionInput.Entry(o.id(), o.display() == null ? Optional.empty() : Optional.of(Adv.vanilla(o.display())), init));
                }
                yield new net.minecraft.server.dialog.Input(c.key(), new SingleOptionInput(c.width(), entries, Adv.vanilla(c.label()), c.labelVisible()));
            }
        };
    }

    // ------------------------------------------------------------------ clicks

    private synchronized int remember(ServerPlayer player, Menu menu) {
        Deque<Pending> q = pending.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        q.removeIf(p -> p.expires() < now);
        while (q.size() >= KEEP) q.pollFirst();
        int id = ++nextId;
        q.addLast(new Pending(id, menu, now + LIFETIME_MS));
        return id;
    }

    private synchronized Pending take(ServerPlayer player, int id) {
        Deque<Pending> q = pending.get(player.getUUID());
        if (q == null) return null;
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = q.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.expires() < now) it.remove();
            else if (p.id() == id) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    /** Forgets a player's waiting menus (they left). */
    synchronized void forget(UUID player) {
        pending.remove(player);
    }

    synchronized void clear() {
        pending.clear();
    }

    /**
     * A {@code minecraft:custom} click from a player (called by the click mixin through
     * {@link net.axther.aftrhrs.core.event.FeatherEvents#CUSTOM_CLICK}). True when it was one of ours.
     */
    public boolean handleClick(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (!CLICK_ID.equals(id)) return false;
        if (payload.isEmpty() || !(payload.get() instanceof CompoundTag tag)) return true;
        int menuId = tag.getIntOr(MENU_KEY, -1);
        int index = tag.getIntOr(BUTTON_KEY, Integer.MIN_VALUE);
        Pending p = take(player, menuId);
        if (p == null) return true;
        Button button;
        if (index == -1) button = p.menu().exitButton();
        else if (index >= 0 && index < p.menu().buttons().size()) button = p.menu().buttons().get(index);
        else return true;
        Response response = Response.of(p.menu().inputs(), key -> {
            Tag v = tag.get(key);
            if (v == null) return null;
            if (v instanceof net.minecraft.nbt.StringTag) return v.asString().orElse(null);
            if (v instanceof net.minecraft.nbt.ByteTag) return v.asBoolean().orElse(null);
            Optional<Number> n = v.asNumber();
            return n.isPresent() ? n.get() : v.asString().orElse(null);
        });
        run(player, button, response);
        return true;
    }

    /** Runs a button's callback for a click that came from a dialog or the Companion (on the server thread). */
    public void run(ServerPlayer player, Button button, Response response) {
        if (button == null || button.onClick() == null) return;
        Runnable run = () -> {
            if (!player.hasDisconnected() && !plugin.authPending(player)) {
                try {
                    button.onClick().accept(player, response == null ? Response.EMPTY : response);
                } catch (RuntimeException e) {
                    plugin.getLogger().error("Menu button failed", e);
                }
            }
        };
        var server = Feather.serverOrNull();
        if (server == null) return;
        if (server.isSameThread()) run.run();
        else server.execute(run);
    }
}
