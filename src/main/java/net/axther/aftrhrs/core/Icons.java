package net.axther.aftrhrs.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Menu icons and player heads: what Bukkit's ItemMeta calls did ({@code displayName}, {@code lore},
 * {@code setHideTooltip}, {@code addItemFlags(ItemFlag.values())}, {@code setEnchantmentGlintOverride},
 * {@code SkullMeta.setPlayerProfile}). Names and lore are Adventure components (use {@link Text#itemMsg} /
 * {@link Text#lines}, which already turn italics off).
 */
public final class Icons {
    /** The tooltip parts {@code ItemFlag.values()} used to hide. */
    private static final List<DataComponentType<?>> FLAGS = List.of(DataComponents.ATTRIBUTE_MODIFIERS,
            DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS, DataComponents.UNBREAKABLE,
            DataComponents.CAN_BREAK, DataComponents.CAN_PLACE_ON, DataComponents.DYED_COLOR, DataComponents.TRIM,
            DataComponents.POTION_CONTENTS, DataComponents.JUKEBOX_PLAYABLE, DataComponents.BANNER_PATTERNS,
            DataComponents.FIREWORKS, DataComponents.FIREWORK_EXPLOSION);

    private Icons() {
    }

    /** A builder for a menu icon of this item. */
    public static Icon icon(Item item) {
        return new Icon(new ItemStack(item));
    }

    /** A builder over a copy of this stack. */
    public static Icon icon(ItemStack stack) {
        return new Icon(stack.copy());
    }

    /**
     * A Feather-style icon: name from {@code nameKey} (null: no tooltip at all, a filler pane), lore from
     * {@code loreKey} (may be null), every extra tooltip line hidden.
     */
    public static ItemStack icon(Item item, String nameKey, String loreKey, TagResolver... r) {
        Text text = Feather.get().text();
        Icon i = icon(item).flags();
        if (nameKey == null) return i.hideTooltip().build();
        i.name(text.itemMsg(nameKey, r));
        if (loreKey != null) i.lore(text.lines(loreKey, r));
        return i.build();
    }

    /** A player head wearing this online player's skin (the full profile, so the client needn't look it up). */
    public static ItemStack head(ServerPlayer player) {
        ItemStack head = new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
        return head;
    }

    /** A player head for a possibly offline player, resolved by the client from the id. */
    public static ItemStack head(UUID id) {
        ItemStack head = new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(id));
        return head;
    }

    /** A head for an online player if there is one, else by id. */
    public static ItemStack head(UUID id, ServerPlayer online) {
        return online != null ? head(online) : head(id);
    }

    public static void name(ItemStack stack, Component name) {
        stack.set(DataComponents.CUSTOM_NAME, Adv.vanilla(name));
    }

    public static void lore(ItemStack stack, List<Component> lines) {
        List<net.minecraft.network.chat.Component> out = new ArrayList<>(lines.size());
        for (Component c : lines) out.add(Adv.vanilla(c));
        stack.set(DataComponents.LORE, new ItemLore(out));
    }

    public static final class Icon {
        private final ItemStack stack;

        private Icon(ItemStack stack) {
            this.stack = stack;
        }

        public Icon name(Component name) {
            Icons.name(stack, name);
            return this;
        }

        public Icon lore(List<Component> lines) {
            Icons.lore(stack, lines);
            return this;
        }

        public Icon count(int count) {
            stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), count)));
            return this;
        }

        /** Nothing at all on hover (filler panes). */
        public Icon hideTooltip() {
            TooltipDisplay d = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
            stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, d.hiddenComponents()));
            return this;
        }

        /** Hides enchantments, attributes, dye, trims and the like ({@code addItemFlags(ItemFlag.values())}). */
        public Icon flags() {
            TooltipDisplay d = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
            for (DataComponentType<?> t : FLAGS) d = d.withHidden(t, true);
            stack.set(DataComponents.TOOLTIP_DISPLAY, d);
            return this;
        }

        public Icon glint(boolean on) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, on);
            return this;
        }

        public ItemStack build() {
            return stack;
        }
    }
}
