package net.axther.aftrhrs.feature.chat;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Item and inventory sharing in chat: {@code [i]} / {@code [item]} shows the held item (hover for the full
 * tooltip), {@code [inv]} / {@code [inventory]} and {@code [ec]} / {@code [enderchest]} link to the player's
 * inventory or ender chest. Clicking any of them opens a read-only chest with a snapshot taken when the message was
 * sent, so nothing can be taken out or duplicated. Each token works once per message.
 *
 * <p>{@link ChatModule} puts the buttons into each chat line ({@link #prepare}). Clicks come back as a
 * {@code minecraft:custom} click with id {@code aftrhrs:share} and the snapshot id as payload (Paper used a callback).
 */
public final class ChatShare implements FeatherModule {
    enum Kind { ITEM, INVENTORY, ENDER_CHEST }

    static final Identifier CLICK_ID = AftrHrs.id("share");
    private static final Pattern TOKEN = Pattern.compile("\\[(i|item|inv|inventory|ec|enderchest)]", Pattern.CASE_INSENSITIVE);
    private static final int ITEM_SLOT = 13;

    /** A frozen copy of what was shared. */
    record Snapshot(UUID id, Kind kind, String owner, Component ownerName, ItemStack[] contents, long expires) {
    }

    private static volatile ChatShare active;

    private final Feather plugin;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private Tasks.Task sweeper;
    private boolean enabled;
    private long lifetimeMs;

    public ChatShare(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Chat sharing";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        sweeper = Tasks.timer(1200L, 1200L, this::sweep);
        plugin.once("chatshare.events", () -> FeatherEvents.CUSTOM_CLICK.register((player, id, payload) -> {
            if (!CLICK_ID.equals(id)) return false;
            ChatShare m = active;
            if (m == null) return true;
            String raw = payload.flatMap(t -> t.asString()).orElse("");
            try {
                m.open(player, UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                m.plugin.text().send(player, "share.expired");
            }
            return true;
        }));
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        enabled = c.getBoolean("chat-share.enabled", true);
        lifetimeMs = Math.max(10, c.getLong("chat-share.expire-seconds", 600)) * 1000L;
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (sweeper != null) sweeper.cancel();
        snapshots.clear();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            Gui g = Gui.of(p);
            if (g != null && g.attachment() instanceof Snapshot) p.closeContainer();
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        snapshots.values().removeIf(s -> s.expires() <= now);
    }

    // ------------------------------------------------------------------ chat

    /** Kinds mentioned in a message, in order of first appearance (each once). */
    static Set<Kind> kinds(String plain) {
        Set<Kind> out = EnumSet.noneOf(Kind.class);
        Matcher m = TOKEN.matcher(plain);
        while (m.find()) out.add(kind(m.group(1)));
        return out;
    }

    private static Kind kind(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "i", "item" -> Kind.ITEM;
            case "inv", "inventory" -> Kind.INVENTORY;
            default -> Kind.ENDER_CHEST;
        };
    }

    /** Replaces the share tokens in a message (see {@link #prepare}). */
    Component render(ServerPlayer sender, Component message) {
        return prepare(sender, message).apply(message);
    }

    /**
     * Takes the snapshots now and returns what puts the share buttons in place of the first {@code [i]}, {@code [inv]}
     * and {@code [ec]} token (and their long forms) of a line, so the same shares can go into several copies of one
     * line (chat is rendered per viewer for mentions). Identity when there's nothing to share or the sender lacks
     * {@code feather.chat.share}. Server thread.
     */
    UnaryOperator<Component> prepare(ServerPlayer sender, Component message) {
        if (!enabled || !Perms.has(sender, "feather.chat.share")) return c -> c;
        Set<Kind> wanted = kinds(Adv.plain(message));
        if (wanted.isEmpty()) return c -> c;
        Map<Kind, Component> buttons = new EnumMap<>(Kind.class);
        try {
            for (Kind k : wanted) buttons.put(k, button(sender, k));
        } catch (RuntimeException e) {
            plugin.getLogger().warn("Could not share in chat for {}", Names.name(sender), e);
            return c -> c;
        }
        return c -> {
            Set<Kind> used = EnumSet.noneOf(Kind.class);
            return c.replaceText(TextReplacementConfig.builder().match(TOKEN).replacement((m, b) -> {
                Kind k = kind(m.group(1));
                Component button = buttons.get(k);
                if (button == null || !used.add(k)) return b; // only the first of each kind
                return button;
            }).build());
        };
    }

    private Component button(ServerPlayer sender, Kind kind) {
        Text text = plugin.text();
        ItemStack[] contents;
        Component label;
        Inventory inv = sender.getInventory();
        switch (kind) {
            case ITEM -> {
                ItemStack held = inv.getSelectedItem();
                if (held.isEmpty()) {
                    return text.msg("share.nothing-in-hand")
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(text.msg("share.nothing-in-hand-hover")));
                }
                contents = new ItemStack[]{held.copy()};
                Component name = Adv.adventure(held.getStyledHoverName());
                label = held.getCount() > 1
                        ? text.msg("share.item-count", Text.component("item", name), Text.unparsed("count", held.getCount()))
                        : text.msg("share.item", Text.component("item", name));
            }
            case INVENTORY -> {
                // 0-35 storage + hotbar, 36-39 boots..helmet, 40 offhand (Bukkit's order)
                contents = new ItemStack[41];
                for (int i = 0; i < 41; i++) contents[i] = inv.getItem(i).copy();
                label = text.msg("share.inventory", Text.component("name", Names.display(sender)), Text.unparsed("username", Names.name(sender)));
            }
            default -> {
                var ender = sender.getEnderChestInventory();
                contents = new ItemStack[ender.getContainerSize()];
                for (int i = 0; i < contents.length; i++) contents[i] = ender.getItem(i).copy();
                label = text.msg("share.ender-chest", Text.component("name", Names.display(sender)), Text.unparsed("username", Names.name(sender)));
            }
        }
        Snapshot snap = new Snapshot(UUID.randomUUID(), kind, Names.name(sender), Names.display(sender), contents,
                System.currentTimeMillis() + lifetimeMs);
        snapshots.put(snap.id(), snap);
        // Hover and click are built as vanilla style and carried over in Adventure's JSON form.
        Style style = Style.EMPTY.withClickEvent(new ClickEvent.Custom(CLICK_ID, Optional.of(StringTag.valueOf(snap.id().toString()))));
        style = kind == Kind.ITEM ? style.withHoverEvent(hoverFor(contents[0]))
                : style.withHoverEvent(new HoverEvent.ShowText(Adv.vanilla(text.msg("share.hover"))));
        Component carrier = Adv.adventure(net.minecraft.network.chat.Component.literal("x").withStyle(style));
        return label.hoverEvent(carrier.hoverEvent()).clickEvent(carrier.clickEvent());
    }

    /**
     * The tooltip copy of a shared item. The hover goes to every viewer and an oversized chat packet disconnects them,
     * so nothing is copied wholesale: the copy starts as a bare item of the same type and count and gets only small,
     * bounded parts of the original - its model, name, up to 8 lore lines, enchantments and damage. Text parts that
     * are too long or too deeply styled are cut down to plain text. Any failure gives the bare item.
     */
    private static HoverEvent hoverFor(ItemStack item) {
        ItemStack bare = new ItemStack(item.getItem(), Math.max(1, item.getCount()));
        try {
            ItemStack shown = bare.copy();
            Identifier model = item.get(DataComponents.ITEM_MODEL);
            if (model != null && model.toString().length() <= 256) shown.set(DataComponents.ITEM_MODEL, model);
            CustomModelData cmd = item.get(DataComponents.CUSTOM_MODEL_DATA);
            if (cmd != null && small(cmd)) shown.set(DataComponents.CUSTOM_MODEL_DATA, cmd);
            if (item.has(DataComponents.CUSTOM_NAME)) shown.set(DataComponents.CUSTOM_NAME, bounded(item.get(DataComponents.CUSTOM_NAME), 64));
            if (item.has(DataComponents.ITEM_NAME)) shown.set(DataComponents.ITEM_NAME, bounded(item.get(DataComponents.ITEM_NAME), 64));
            ItemLore lore = item.get(DataComponents.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                List<net.minecraft.network.chat.Component> out = new ArrayList<>();
                for (int i = 0; i < Math.min(8, lore.lines().size()); i++) out.add(bounded(lore.lines().get(i), 80));
                if (lore.lines().size() > 8) out.add(net.minecraft.network.chat.Component.literal("…"));
                shown.set(DataComponents.LORE, new ItemLore(out));
            }
            if (item.has(DataComponents.ENCHANTMENTS)) shown.set(DataComponents.ENCHANTMENTS, item.get(DataComponents.ENCHANTMENTS));
            if (item.has(DataComponents.STORED_ENCHANTMENTS)) shown.set(DataComponents.STORED_ENCHANTMENTS, item.get(DataComponents.STORED_ENCHANTMENTS));
            if (item.isDamaged()) shown.setDamageValue(item.getDamageValue());
            return new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(shown));
        } catch (RuntimeException e) {
            return new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(bare));
        }
    }

    /** Custom model data small enough for a hover (resource packs use a handful of values; anything bigger is dropped). */
    private static boolean small(CustomModelData cmd) {
        if (cmd.floats().size() > 16 || cmd.flags().size() > 16 || cmd.colors().size() > 16 || cmd.strings().size() > 8) return false;
        for (String s : cmd.strings()) if (s == null || s.length() > 64) return false;
        return true;
    }

    /** A text part kept as is when small, else cut to {@code maxChars} of plain text. */
    private static net.minecraft.network.chat.Component bounded(net.minecraft.network.chat.Component c, int maxChars) {
        if (c == null) return null;
        String plain = c.getString();
        boolean small = plain.length() <= maxChars && GsonComponentSerializer.gson().serialize(Adv.adventure(c)).length() <= 2048;
        if (small) return c;
        return net.minecraft.network.chat.Component.literal(plain.length() > maxChars ? plain.substring(0, maxChars) + "…" : plain);
    }

    // ------------------------------------------------------------------ viewing

    /** Opens a share for a viewer, or says it expired. Look, don't touch: every slot of the chest is locked. */
    void open(ServerPlayer viewer, UUID id) {
        Text text = plugin.text();
        Snapshot s = snapshots.get(id);
        if (s == null || s.expires() <= System.currentTimeMillis()) {
            if (s != null) snapshots.remove(id);
            text.send(viewer, "share.expired");
            text.sound(viewer, "error");
            return;
        }
        Gui gui;
        switch (s.kind()) {
            case ITEM -> {
                gui = Gui.create(3, title("share.title-item", s));
                ItemStack filler = filler();
                for (int i = 0; i < 27; i++) gui.set(i, filler);
                gui.set(ITEM_SLOT, s.contents()[0].copy());
            }
            case INVENTORY -> {
                gui = Gui.create(5, title("share.title-inventory", s));
                ItemStack[] c = s.contents();
                // Row 1: armor (helmet..boots) and the offhand, with labels.
                gui.set(0, label(Items.STAINED_GLASS_PANE.white(), "share.label-armor"));
                String[] empty = {"share.empty-helmet", "share.empty-chestplate", "share.empty-leggings", "share.empty-boots"};
                for (int i = 0; i < 4; i++) {
                    ItemStack piece = at(c, 39 - i);
                    gui.set(1 + i, piece != null ? piece : label(Items.STAINED_GLASS_PANE.lightGray(), empty[i]));
                }
                ItemStack filler = filler();
                gui.set(5, filler);
                gui.set(6, filler);
                gui.set(7, label(Items.STAINED_GLASS_PANE.white(), "share.label-offhand"));
                ItemStack off = at(c, 40);
                gui.set(8, off != null ? off : label(Items.STAINED_GLASS_PANE.lightGray(), "share.empty-offhand"));
                // Rows 2-4: the main inventory; row 5: the hotbar.
                for (int i = 9; i < 36; i++) gui.set(i, orEmpty(at(c, i)));
                for (int i = 0; i < 9; i++) gui.set(36 + i, orEmpty(at(c, i)));
            }
            default -> {
                gui = Gui.create(3, title("share.title-ender-chest", s));
                ItemStack[] c = s.contents();
                for (int i = 0; i < Math.min(27, c.length); i++) gui.set(i, orEmpty(at(c, i)));
            }
        }
        gui.attach(s);
        gui.open(viewer);
        text.sound(viewer, "menu-open");
    }

    private Component title(String key, Snapshot s) {
        return plugin.text().msg(key, Text.component("name", s.ownerName()), Text.unparsed("username", s.owner()));
    }

    private static ItemStack orEmpty(ItemStack s) {
        return s == null ? ItemStack.EMPTY : s;
    }

    private static ItemStack at(ItemStack[] c, int i) {
        if (i >= c.length || c[i] == null || c[i].isEmpty()) return null;
        return c[i].copy();
    }

    private static ItemStack filler() {
        return Icons.icon(Items.STAINED_GLASS_PANE.gray()).hideTooltip().build();
    }

    private ItemStack label(net.minecraft.world.item.Item item, String key) {
        return Icons.icon(item).name(plugin.text().itemMsg(key)).flags().build();
    }
}
