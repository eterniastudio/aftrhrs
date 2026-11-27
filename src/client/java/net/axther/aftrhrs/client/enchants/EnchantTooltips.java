package net.axther.aftrhrs.client.enchants;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * What each Aftr Hrs enchantment does, under its name in the tooltip (docs/enchants.md): always on enchanted books, and
 * on gear while Shift is held (otherwise one hint line). Vanilla shows only names.
 */
public final class EnchantTooltips {
    private EnchantTooltips() {
    }

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> add(stack, lines));
    }

    /** Description lines wrap at about this many characters. */
    private static final int WIDTH = 40;

    static List<String> wrap(String text, int width) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static void add(ItemStack stack, List<Component> lines) {
        boolean book = stack.has(DataComponents.STORED_ENCHANTMENTS);
        ItemEnchantments e = book ? stack.get(DataComponents.STORED_ENCHANTMENTS) : stack.get(DataComponents.ENCHANTMENTS);
        if (e == null || e.isEmpty()) return;
        boolean any = false;
        boolean show = book || Minecraft.getInstance().hasShiftDown();
        for (var entry : e.entrySet()) {
            Holder<Enchantment> h = entry.getKey();
            var key = h.unwrapKey().orElse(null);
            if (key == null || !key.identifier().getNamespace().equals("aftrhrs")) continue;
            any = true;
            if (!show) continue;
            String name = Enchantment.getFullname(h, entry.getIntValue()).getString();
            String desc = Component.translatable("enchantment.aftrhrs." + key.identifier().getPath() + ".desc").getString();
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).getString().equals(name)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) continue; // its name is hidden (tooltip display): say nothing either
            List<String> wrapped = wrap(desc, WIDTH);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(at + 1 + i, Component.literal("  " + wrapped.get(i)).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (any && !show) {
            int at = Math.min(lines.size(), 1);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).getString().isEmpty()) break;
                at = i + 1;
            }
            lines.add(Math.min(at, lines.size()), Component.translatable("aftrhrs.enchants.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
