package net.axther.aftrhrs.gametest.econ;

import net.axther.aftrhrs.feature.cosmetics.Cosmetics;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Cosmetic armor and the hat slot (feature.cosmetics.Cosmetics): slots, no stats, persistence, hiding, death and graves. */
public class CosmeticsTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static Slot slot(ServerPlayer p, int i) {
        return p.inventoryMenu.getSlot(Cosmetics.MENU_FIRST + i);
    }

    /** Five outfit slots follow the back slot; the hat takes anything, each cosmetic piece only what fits its armor slot. */
    @GameTest
    public void outfitSlotsTakeTheRightItems(GameTestHelper h) {
        Bots.Bot b = EconTests.join(h, "cos");
        try {
            ServerPlayer p = b.player;
            InventoryMenu m = p.inventoryMenu;
            check(h, m.slots.size() >= Cosmetics.MENU_FIRST + Cosmetics.SIZE, "the inventory menu has " + m.slots.size() + " slots");
            check(h, slot(p, Cosmetics.HAT).mayPlace(new ItemStack(Items.SUNFLOWER)), "the hat slot refuses a flower");
            check(h, slot(p, Cosmetics.HEAD).mayPlace(new ItemStack(Items.GOLDEN_HELMET)), "the cosmetic helmet slot refuses a helmet");
            check(h, !slot(p, Cosmetics.HEAD).mayPlace(new ItemStack(Items.IRON_CHESTPLATE)), "the cosmetic helmet slot takes a chestplate");
            check(h, slot(p, Cosmetics.CHEST).mayPlace(new ItemStack(Items.IRON_CHESTPLATE)), "the cosmetic chest slot refuses a chestplate");
            check(h, !slot(p, Cosmetics.FEET).mayPlace(new ItemStack(Items.DIRT)), "the cosmetic boots slot takes dirt");
            check(h, slot(p, Cosmetics.LEGS).getMaxStackSize(new ItemStack(Items.IRON_LEGGINGS)) == 1, "an outfit slot holds more than one");
        } finally {
            b.leave();
        }
        h.succeed();
    }

    /** Cosmetic armor gives no armor points or toughness: only the real slots do. */
    @GameTest
    public void cosmeticArmorGivesNoStats(GameTestHelper h) {
        Bots.Bot b = EconTests.join(h, "cosS");
        try {
            ServerPlayer p = b.player;
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) p.setItemSlot(s, ItemStack.EMPTY);
            slot(p, Cosmetics.HEAD).set(new ItemStack(Items.NETHERITE_HELMET));
            slot(p, Cosmetics.CHEST).set(new ItemStack(Items.NETHERITE_CHESTPLATE));
            slot(p, Cosmetics.LEGS).set(new ItemStack(Items.NETHERITE_LEGGINGS));
            slot(p, Cosmetics.FEET).set(new ItemStack(Items.NETHERITE_BOOTS));
            p.tick();
            check(h, p.getArmorValue() == 0, "cosmetic netherite gives " + p.getArmorValue() + " armor points");
            check(h, p.getAttributeValue(Attributes.ARMOR_TOUGHNESS) == 0, "cosmetic netherite gives toughness");
            check(h, Cosmetics.outfit(p).get(Cosmetics.CHEST).is(Items.NETHERITE_CHESTPLATE), "the cosmetic chestplate didn't stay in its slot");
            Cosmetics.clearItems(p);
        } finally {
            b.leave();
        }
        h.succeed();
    }

    /** The outfit and the hide switches save and load (the attachment's codec), and a switch flips back and forth. */
    @GameTest
    public void outfitSavesAndHides(GameTestHelper h) {
        Bots.Bot b = EconTests.join(h, "cosP");
        try {
            ServerPlayer p = b.player;
            slot(p, Cosmetics.HAT).set(new ItemStack(Items.CARVED_PUMPKIN));
            slot(p, Cosmetics.FEET).set(new ItemStack(Items.LEATHER_BOOTS));
            R.call(Cosmetics.class, "toggle", p, Cosmetics.LEGS);
            Cosmetics.Outfit o = Cosmetics.outfit(p);
            check(h, o.hides(Cosmetics.LEGS) && !o.hides(Cosmetics.HEAD), "the leggings switch didn't hide only the leggings: " + o.hidden());
            RegistryAccess reg = h.getLevel().registryAccess();
            var ops = reg.createSerializationContext(NbtOps.INSTANCE);
            Tag saved = Cosmetics.Outfit.CODEC.encodeStart(ops, o).getOrThrow();
            Cosmetics.Outfit back = Cosmetics.Outfit.CODEC.parse(ops, saved).getOrThrow();
            check(h, back.get(Cosmetics.HAT).is(Items.CARVED_PUMPKIN) && back.get(Cosmetics.FEET).is(Items.LEATHER_BOOTS)
                    && back.get(Cosmetics.CHEST).isEmpty() && back.hides(Cosmetics.LEGS), "the outfit changed through save and load: " + saved);
            R.call(Cosmetics.class, "toggle", p, Cosmetics.LEGS);
            check(h, !Cosmetics.outfit(p).hides(Cosmetics.LEGS), "a second click didn't show the leggings again");
            Cosmetics.clearItems(p);
        } finally {
            b.leave();
        }
        h.succeed();
    }

    /** Shift-clicking an outfit item takes it into the inventory; shift-clicking armor in the inventory still puts it on for real. */
    @GameTest
    public void shiftClicksKeepTheirOldMeaning(GameTestHelper h) {
        Bots.Bot b = EconTests.join(h, "cosQ");
        try {
            ServerPlayer p = b.player;
            p.getInventory().clearContent();
            slot(p, Cosmetics.HEAD).set(new ItemStack(Items.GOLDEN_HELMET));
            p.inventoryMenu.clicked(Cosmetics.MENU_FIRST + Cosmetics.HEAD, 0, ContainerInput.QUICK_MOVE, p);
            check(h, Cosmetics.outfit(p).get(Cosmetics.HEAD).isEmpty(), "shift-click left the cosmetic helmet in its slot");
            check(h, p.getInventory().countItem(Items.GOLDEN_HELMET) == 1, "the cosmetic helmet didn't land in the inventory");
            // a chestplate shift-clicked from the inventory goes on the real chest, not the cosmetic slot
            p.getInventory().clearContent();
            p.getInventory().setItem(9, new ItemStack(Items.IRON_CHESTPLATE));
            p.inventoryMenu.clicked(9, 0, ContainerInput.QUICK_MOVE, p);
            check(h, p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "shift-click didn't put the chestplate on");
            check(h, Cosmetics.outfit(p).get(Cosmetics.CHEST).isEmpty(), "shift-click put the chestplate in the cosmetic slot");
            p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        } finally {
            b.leave();
        }
        h.succeed();
    }

    /** A death puts the outfit into the grave (slots 42..46) and keeps the switches; taking it all puts every piece back on. */
    @GameTest(maxTicks = 100)
    public void deathPutsTheOutfitInTheGrave(GameTestHelper h) {
        Bots.Bot b = EconTests.join(h, "cosD");
        ServerPlayer p = b.player;
        slot(p, Cosmetics.HAT).set(new ItemStack(Items.SUNFLOWER));
        slot(p, Cosmetics.CHEST).set(new ItemStack(Items.GOLDEN_CHESTPLATE));
        R.call(Cosmetics.class, "toggle", p, Cosmetics.HEAD);
        p.kill(h.getLevel());
        h.runAfterDelay(3, () -> {
            try {
                List<Object> graves = Graves.of(b.id());
                check(h, !graves.isEmpty(), "no grave was made");
                check(h, Graves.count(b.id(), Items.SUNFLOWER) == 1 && Graves.count(b.id(), Items.GOLDEN_CHESTPLATE) == 1, "the outfit isn't in the grave");
                h.getLevel().getServer().getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                ServerPlayer now = h.getLevel().getServer().getPlayerList().getPlayer(b.id());
                Cosmetics.Outfit after = Cosmetics.outfit(now);
                check(h, after.get(Cosmetics.HAT).isEmpty() && after.get(Cosmetics.CHEST).isEmpty(), "the outfit is both in the grave and still worn");
                check(h, after.hides(Cosmetics.HEAD), "the death reset the hide switches");
                check(h, EconTests.dropped(h, now, Items.SUNFLOWER) == 0, "the hat dropped on the ground as well");
                // Take All: every piece back into its own slot
                Object g = graves.get(0);
                for (Object e : new java.util.ArrayList<>((List<?>) R.get(g, "items"))) {
                    if (R.get(e, "item") != null) R.call(Graves.module(), "give", now, e, true);
                }
                Cosmetics.Outfit worn = Cosmetics.outfit(now);
                check(h, worn.get(Cosmetics.HAT).is(Items.SUNFLOWER) && worn.get(Cosmetics.CHEST).is(Items.GOLDEN_CHESTPLATE),
                        "taking the grave didn't put the outfit back on");
                Cosmetics.clearItems(now);
                R.call(Cosmetics.class, "toggle", now, Cosmetics.HEAD);
            } finally {
                Graves.clear(b.id());
                b.leave();
            }
            h.succeed();
        });
    }
}
