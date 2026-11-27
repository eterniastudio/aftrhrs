package net.axther.aftrhrs.feature.furniture;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.feature.furniture.curios.CurioBlockEntity;
import net.axther.aftrhrs.feature.furniture.gadgets.GadgetBlockEntity;
import net.axther.aftrhrs.migrate.Legacy;
import net.axther.aftrhrs.migrate.PaperItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Paper-era curio / gadget / power data, carried onto the native blocks (registered at mod init, whether or not the
 * modules are on, so nothing is lost):
 * <ul>
 *   <li>curio state from the Oraxen base entity's PDC ({@code feather:curio_item / curio_hidden} Paper item bytes,
 *       {@code curio_plants} "poppy,,dandelion", {@code curio_text} lines) goes into the piece's block entity;</li>
 *   <li>gadget state ({@code feather:gadget_*}, {@code spike_*}) likewise;</li>
 *   <li>overlay display entities those modules drew ({@code feather:curio_part}, {@code feather:gadget_owner} - liquid
 *       overlays and the frozen copy of a caged mob - and {@code feather:power_display}) were never meant to be saved;
 *       any that were are removed. Power machines themselves come from chunk data ({@code PowerModule}).</li>
 * </ul>
 */
public final class FurnitureLegacy {
    private FurnitureLegacy() {
    }

    public static void init() {
        Legacy.onFurniture(FurnitureLegacy::converted);
        Legacy.converter(FurnitureLegacy::strayPart);
        net.axther.aftrhrs.feature.furniture.power.PowerModule.initMigration();
    }

    private static boolean strayPart(ServerLevel level, Entity e, CompoundTag bv) {
        if (bv.contains("feather:curio_part") || bv.contains("feather:gadget_owner") || bv.contains("feather:power_display")) {
            e.ejectPassengers();
            e.discard();
            return true;
        }
        return false;
    }

    private static void converted(ServerLevel level, BlockPos origin, String id, CompoundTag bv, float yaw) {
        if (FurnitureEntities.isCurio(id)) curio(level, origin, bv);
        else if (FurnitureEntities.isGadget(id)) gadget(level, origin, bv);
    }

    private static ItemStack item(ServerLevel level, CompoundTag bv, String key) {
        byte[] b = bv.getByteArray(key).orElse(null);
        if (b == null || b.length == 0) return ItemStack.EMPTY;
        ItemStack s = PaperItems.fromBytes(level.getServer(), b);
        return s == null ? ItemStack.EMPTY : s;
    }

    private static void curio(ServerLevel level, BlockPos origin, CompoundTag bv) {
        ItemStack item = item(level, bv, "feather:curio_item");
        ItemStack hidden = item(level, bv, "feather:curio_hidden");
        String plants = bv.getStringOr("feather:curio_plants", "");
        String text = bv.getStringOr("feather:curio_text", "");
        if (item.isEmpty() && hidden.isEmpty() && plants.isEmpty() && text.isEmpty()) return;
        if (!(level.getBlockEntity(origin) instanceof CurioBlockEntity c)) {
            // the piece could not be placed: give the contents back to the world
            List<ItemStack> drop = new ArrayList<>(List.of(item, hidden));
            for (String s : plants.split(",", -1)) {
                Item p = plant(s);
                if (p != null) drop.add(new ItemStack(p));
            }
            for (ItemStack s : drop) if (!s.isEmpty()) Containers.dropItemStack(level, origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5, s);
            return;
        }
        if (!item.isEmpty()) c.setItem(item);
        if (!hidden.isEmpty()) c.setHidden(hidden);
        if (!plants.isEmpty()) {
            String[] parts = plants.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                Item p = plant(parts[i]);
                if (p != null) c.setPlant(i, p, parts.length);
            }
        }
        if (!text.isBlank()) c.setText(new ArrayList<>(Arrays.asList(text.split("\n", -1))));
    }

    /** A Bukkit material key ({@code poppy}) as an item. */
    private static Item plant(String s) {
        if (s == null || s.isBlank()) return null;
        Identifier id = Identifier.tryParse(s.contains(":") ? s.toLowerCase(Locale.ROOT) : "minecraft:" + s.toLowerCase(Locale.ROOT));
        Item i = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return i == null || i == Items.AIR ? null : i;
    }

    private static void gadget(ServerLevel level, BlockPos origin, CompoundTag bv) {
        if (level.getBlockEntity(origin) instanceof GadgetBlockEntity g) {
            g.loadPaper(bv);
        } else if (bv.contains("feather:gadget_kind")) {
            // the piece could not be placed and was dropped as an item: the state goes onto that item (same custom
            // data keys as on Paper), so a jar keeps its drink and a cage its mob when it is placed again
            for (net.minecraft.world.entity.item.ItemEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(origin).inflate(1))) {
                ItemStack s = e.getItem();
                String sid = net.axther.aftrhrs.content.ArcanaContent.id(s);
                if (sid == null || !FurnitureEntities.isGadget(sid) || s.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) continue;
                CompoundTag t = new CompoundTag();
                for (String k : List.of("feather:gadget_kind", "feather:gadget_fluid", "feather:gadget_units", "feather:gadget_item",
                        "feather:gadget_mob", "feather:gadget_mob_sig", "feather:gadget_mob_type")) {
                    net.minecraft.nbt.Tag v = bv.get(k);
                    if (v != null) t.put(k, v.copy());
                }
                ItemStack withState = s.copy();
                withState.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(t));
                e.setItem(withState);
                return;
            }
            AftrHrs.LOG.warn("Gadget state at {} had no block or dropped piece to go to; dropped", origin.toShortString());
        }
    }
}
