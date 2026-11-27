package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.Sender;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** /hat puts the held item (one of the stack) on your head; /hat off takes it back off. */
public final class HatModule implements FeatherModule, Travel.Named {
    private final Feather plugin;
    private final Set<Item> blocked = new HashSet<>();

    public HatModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Hats";
    }

    @Override
    public void enable() {
        reload();
        Travel.bind(plugin, this, "hat");
        plugin.hub().add("hat", Hub.Group.STYLE, Hub.ORDER_HAT, "hub-hat", "feather.hat", this::wear);
    }

    @Override
    public void reload() {
        blocked.clear();
        for (String s : plugin.getConfig().getStringList("hat.blocked-items")) {
            Identifier id = Identifier.tryParse(s.trim().toLowerCase(Locale.ROOT));
            Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
            if (item.isPresent()) blocked.add(item.get());
            else plugin.getLogger().warn("hat.blocked-items: unknown item {}", s);
        }
    }

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        ServerPlayer player = sender.player();
        if (player == null) {
            plugin.text().send(sender, "general.players-only");
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("remove"))) remove(player);
        else wear(player);
        return true;
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        if (args.length == 1 && "off".startsWith(args[0].toLowerCase(Locale.ROOT))) return List.of("off");
        return List.of();
    }

    /** Curse of Binding keeps a helmet on (outside creative), as it would in the armor slot. */
    private static boolean bound(ServerPlayer player, ItemStack stack) {
        return !stack.isEmpty() && !player.isCreative()
                && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    void wear(ServerPlayer player) {
        Text text = plugin.text();
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            text.send(player, "hat.empty-hand");
            text.sound(player, "error");
            return;
        }
        if (blocked.contains(hand.getItem()) && !Perms.has(player, "feather.hat.any")) {
            text.send(player, "hat.blocked");
            text.sound(player, "error");
            return;
        }
        ItemStack old = player.getItemBySlot(EquipmentSlot.HEAD);
        if (bound(player, old)) {
            text.send(player, "hat.cursed");
            text.sound(player, "error");
            return;
        }
        ItemStack wear = hand.copyWithCount(1);
        player.setItemSlot(EquipmentSlot.HEAD, wear);
        if (hand.getCount() <= 1) {
            player.setItemInHand(InteractionHand.MAIN_HAND, old.isEmpty() ? ItemStack.EMPTY : old);
        } else {
            hand.shrink(1);
            if (!old.isEmpty()) Travel.giveOrDrop(player, old);
        }
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        player.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getEyeY() + 0.5, player.getZ(), 8, 0.25, 0.15, 0.25, 0);
        text.send(player, "hat.worn", Text.component("item", Adv.adventure(wear.getHoverName())));
        text.sound(player, "hat");
        plugin.advance(player, "event", "hat");
    }

    void remove(ServerPlayer player) {
        Text text = plugin.text();
        ItemStack old = player.getItemBySlot(EquipmentSlot.HEAD);
        if (old.isEmpty()) {
            text.send(player, "hat.nothing");
            text.sound(player, "error");
            return;
        }
        if (bound(player, old)) {
            text.send(player, "hat.cursed");
            text.sound(player, "error");
            return;
        }
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        net.kyori.adventure.text.Component name = Adv.adventure(old.getHoverName());
        Travel.giveOrDrop(player, old);
        text.send(player, "hat.removed", Text.component("item", name));
        text.sound(player, "click");
    }
}
