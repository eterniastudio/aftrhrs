package net.axther.aftrhrs.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A plain ex-Oraxen item. Food, consumables, hats (equippable), durability, attributes... are vanilla data
 * components set from the content table; this class adds the item name style and the few Oraxen use-mechanics that
 * are content rather than gameplay modules: {@code commands} (run commands as the player), {@code backpack}
 * (an inventory stored in the item) and {@code bottledexp} (the XP flask).
 */
public class ArcanaItem extends Item {
    final JsonObject entry;

    public ArcanaItem(Properties props, JsonObject entry) {
        super(props);
        this.entry = entry;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Texts.restyle(super.getName(stack), entry.getAsJsonObject("name"));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (entry.has("commands")) {
            if (player instanceof ServerPlayer sp) runCommands(sp, stack, entry.getAsJsonObject("commands"));
            return InteractionResult.SUCCESS;
        }
        if (entry.has("backpack")) {
            // Sneak: put it on your back (when the server allows wearing); else open it.
            if (player instanceof ServerPlayer sp && !(sp.isShiftKeyDown() && net.axther.aftrhrs.feature.relics.BackpackWear.sneakEquip(sp, hand))) {
                openBackpack(sp, stack, entry.getAsJsonObject("backpack"), pl -> inInventory(pl, stack));
            }
            return InteractionResult.SUCCESS;
        }
        if (entry.has("xp_flask")) {
            if (player instanceof ServerPlayer sp) return pourXp(sp, stack, hand, entry.getAsJsonObject("xp_flask").get("ratio").getAsFloat());
            return InteractionResult.SUCCESS;
        }
        return super.use(level, player, hand);
    }

    private static void runCommands(ServerPlayer p, ItemStack stack, JsonObject c) {
        if (p.getCooldowns().isOnCooldown(stack)) return;
        var server = p.level().getServer();
        if (c.has("player")) {
            for (JsonElement e : c.getAsJsonArray("player")) {
                server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), e.getAsString().replace("%player%", p.getGameProfile().name()));
            }
        }
        if (c.has("console")) {
            for (JsonElement e : c.getAsJsonArray("console")) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), e.getAsString().replace("%player%", p.getGameProfile().name()));
            }
        }
        int cd = c.has("cooldown") ? c.get("cooldown").getAsInt() : 0;
        if (cd > 0) p.getCooldowns().addCooldown(stack, cd);
    }

    // ------------------------------------------------------------------------------------------------ backpack

    /** Is this an Arcana backpack (an item holding an inventory of its own)? */
    public static boolean isBackpack(ItemStack stack) {
        return stack.getItem() instanceof ArcanaItem a && a.entry.has("backpack");
    }

    /** Is this menu an open backpack? */
    public static boolean isBackpackMenu(@Nullable net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return menu instanceof BackpackMenu;
    }

    /**
     * Opens backpack {@code stack} for {@code p}. The screen stays usable only while {@code stillHeld} says the stack is
     * still where it was opened from (the inventory, the back...). False if it isn't a backpack.
     */
    public static boolean openBackpack(ServerPlayer p, ItemStack stack, Predicate<Player> stillHeld) {
        if (!(stack.getItem() instanceof ArcanaItem a) || !a.entry.has("backpack")) return false;
        openBackpack(p, stack, a.entry.getAsJsonObject("backpack"), stillHeld);
        return true;
    }

    /** The backpack is still this very stack somewhere in the player's inventory. */
    private static boolean inInventory(Player player, ItemStack stack) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i) == stack) return true;
        }
        return false;
    }

    private static void openBackpack(ServerPlayer p, ItemStack stack, JsonObject bp, Predicate<Player> stillHeld) {
        int rows = Math.max(1, Math.min(6, bp.get("rows").getAsInt()));
        Set<String> blocked = new HashSet<>();
        if (bp.has("blocked")) for (JsonElement e : bp.getAsJsonArray("blocked")) blocked.add(e.getAsString());
        boolean[] loading = {true};
        SimpleContainer c = new SimpleContainer(rows * 9) {
            /** The backpack is still this very (non-empty) stack where it was opened from. */
            @Override
            public boolean stillValid(Player player) {
                if (stack.isEmpty()) return false; // split off / dropped: the stack object left behind is empty
                return stillHeld.test(player);
            }

            @Override
            public void setChanged() {
                super.setChanged();
                if (loading[0]) return;
                for (int i = 0; i < getContainerSize(); i++) {
                    ItemStack s = getItem(i);
                    if (!s.isEmpty() && (s == stack || isBlocked(s, blocked))) {
                        setItem(i, ItemStack.EMPTY);
                        give(p, s);
                    }
                }
                // Written on every change, so any copy of the backpack carries exactly what is inside right now.
                save(this);
            }

            private void save(SimpleContainer self) {
                if (stack.isEmpty()) return;
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < self.getContainerSize(); i++) items.add(self.getItem(i).copy());
                stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            }

            @Override
            public void stopOpen(ContainerUser user) {
                super.stopOpen(user);
                save(this);
                sound(p, bp, "close_sound");
            }
        };
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        var nn = net.minecraft.core.NonNullList.withSize(rows * 9, ItemStack.EMPTY);
        contents.copyInto(nn);
        for (int i = 0; i < nn.size(); i++) c.setItem(i, nn.get(i));
        loading[0] = false;
        sound(p, bp, "open_sound");
        Component title = bp.has("title") ? Texts.of(bp.getAsJsonObject("title")) : stack.getHoverName();
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new BackpackMenu(id, inv, c, rows, stack), title));
    }

    /**
     * The backpack's chest menu: the open backpack itself can't be picked up, moved, swapped, thrown or shift-clicked
     * (taking it out while open split off a copy holding the old contents: a dupe), and once it is no longer in the
     * inventory no click goes through.
     */
    static final class BackpackMenu extends net.minecraft.world.inventory.ChestMenu {
        private final ItemStack backpack;
        private final SimpleContainer contents;

        BackpackMenu(int id, net.minecraft.world.entity.player.Inventory inv, SimpleContainer contents, int rows, ItemStack backpack) {
            super(switch (rows) {
                case 1 -> net.minecraft.world.inventory.MenuType.GENERIC_9x1;
                case 2 -> net.minecraft.world.inventory.MenuType.GENERIC_9x2;
                case 4 -> net.minecraft.world.inventory.MenuType.GENERIC_9x4;
                case 5 -> net.minecraft.world.inventory.MenuType.GENERIC_9x5;
                case 6 -> net.minecraft.world.inventory.MenuType.GENERIC_9x6;
                default -> net.minecraft.world.inventory.MenuType.GENERIC_9x3;
            }, id, inv, contents, rows);
            this.backpack = backpack;
            this.contents = contents;
        }

        @Override
        public void clicked(int slot, int button, net.minecraft.world.inventory.ContainerInput input, Player player) {
            if (!contents.stillValid(player)) return;
            if (slot >= 0 && slot < slots.size() && slots.get(slot).getItem() == backpack) return;
            if (input == net.minecraft.world.inventory.ContainerInput.SWAP
                    && (button >= 0 && button < 9 || button == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND) && player.getInventory().getItem(button) == backpack) return;
            super.clicked(slot, button, input, player);
        }
    }

    private static boolean isBlocked(ItemStack s, Set<String> blocked) {
        if (blocked.isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        if (blocked.contains(id)) return true;
        return s.is(net.minecraft.tags.ItemTags.SHULKER_BOXES) && blocked.contains("minecraft:shulker_box");
    }

    private static void sound(ServerPlayer p, JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return;
        Identifier id = Identifier.tryParse(o.get(key).getAsString());
        if (id != null) p.level().playSound(null, p.blockPosition(), SoundEvent.createVariableRangeEvent(id), SoundSource.PLAYERS, 1f, 1f);
    }

    // ------------------------------------------------------------------------------------------------ XP flask
    /** Pours the player's experience into bottles o' enchanting (Oraxen bottledexp: ratio = share kept). */
    private static InteractionResult pourXp(ServerPlayer p, ItemStack flask, InteractionHand hand, float ratio) {
        int points = totalXp(p);
        int bottles = (int) Math.floor(points * ratio / 7.0);
        if (flask.isDamageableItem()) bottles = Math.min(bottles, flask.getMaxDamage() - flask.getDamageValue());
        if (bottles <= 0) return InteractionResult.FAIL;
        int cost = (int) Math.ceil(bottles * 7.0 / Math.max(0.01f, ratio));
        p.giveExperiencePoints(-Math.min(points, cost));
        ItemStack out = new ItemStack(Items.EXPERIENCE_BOTTLE, bottles);
        give(p, out);
        if (flask.isDamageableItem()) flask.hurtAndBreak(bottles, p, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 1f, 1f);
        return InteractionResult.SUCCESS;
    }

    private static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s) && !s.isEmpty()) {
            p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), s));
        }
    }

    private static int totalXp(Player p) {
        int level = p.experienceLevel;
        int base;
        if (level <= 16) base = level * level + 6 * level;
        else if (level <= 31) base = (int) (2.5 * level * level - 40.5 * level + 360);
        else base = (int) (4.5 * level * level - 162.5 * level + 2220);
        return base + Math.round(p.experienceProgress * p.getXpNeededForNextLevel());
    }

    static @Nullable JsonObject nameOf(Item item) {
        if (item instanceof ArcanaItem a) return a.entry.getAsJsonObject("name");
        return null;
    }
}
