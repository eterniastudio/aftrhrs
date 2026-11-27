package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.core.Yaml;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Locale;

/**
 * Hats: right-click puts ONE hat of the held stack on the head and returns the previous helmet (vanilla's equippable
 * swap would move the whole stack). Nothing here touches anvils, so hats stay renamable.
 */
final class Hats {
    private final ToolsModule plugin;
    private boolean enabled;
    private String prefix;
    private String sound;

    Hats(ToolsModule plugin) {
        this.plugin = plugin;
    }

    void load(Yaml c) {
        enabled = c.getBoolean("hats.enabled", true);
        prefix = c.getString("hats.id-prefix", "arcana_hat_").toLowerCase(Locale.ROOT);
        sound = c.getString("hats.sound", "item.armor.equip_leather");
    }

    boolean isHat(ItemStack stack) {
        String id = ToolsModule.idOf(stack);
        return id != null && id.startsWith(prefix);
    }

    /** Right-click with a hat. True if this took the click (off hand: blocked so vanilla can't equip the whole stack). */
    boolean use(ServerPlayer player, InteractionHand hand) {
        if (!enabled) return false;
        ItemStack held = player.getItemInHand(hand);
        if (!isHat(held)) return false;
        if (hand == InteractionHand.OFF_HAND) {
            player.containerMenu.sendAllDataToRemote();
            return true;
        }
        player.containerMenu.sendAllDataToRemote(); // the client may have predicted vanilla's whole-stack equip
        if (plugin.recentlyUsed(player, "hat", 4)) return true;
        ItemStack old = player.getItemBySlot(EquipmentSlot.HEAD);
        boolean creative = ToolsModule.isCreative(player);
        if (!old.isEmpty()) {
            if (ItemStack.isSameItemSameComponents(old, held)) return true; // already wearing this exact hat
            if (!creative && EnchantmentHelper.has(old, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) return true;
        }
        ItemStack wear = held.copyWithCount(1);
        player.setItemSlot(EquipmentSlot.HEAD, wear);
        if (held.getCount() <= 1) {
            player.setItemInHand(InteractionHand.MAIN_HAND, old.isEmpty() ? ItemStack.EMPTY : old);
        } else {
            held.shrink(1);
            if (!old.isEmpty() && !player.getInventory().add(old)) player.drop(old, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        ToolsModule.playSound((ServerLevel) player.level(), player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 1f, 1f);
        player.containerMenu.sendAllDataToRemote();
        return true;
    }
}
