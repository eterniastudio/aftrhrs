package net.axther.aftrhrs.feature.smithing;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Soulbound (SMITHING.md 7.1, and the Soulbound enchantment, docs/enchants.md): a tool with it stays with you when you die. Graves leave it out; when the inventory
 * drops, it is held aside and handed back to the respawned player in the same slot.
 */
public final class Soulbound {
    private record Kept(int slot, ItemStack stack) {
    }

    private static final Map<UUID, List<Kept>> KEPT = new HashMap<>();

    private Soulbound() {
    }

    /** The Soulbound modifier on a smithed tool, or the Soulbound enchantment (feature.enchants) on anything. */
    public static boolean is(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        if (net.axther.aftrhrs.feature.enchants.Enchants.level(s, net.axther.aftrhrs.feature.enchants.Enchants.SOULBOUND) > 0) return true;
        ToolBuild b = Smithing.BUILD == null ? null : s.get(Smithing.BUILD);
        return b != null && b.level("soulbound") > 0;
    }

    /** Called as the inventory drops on death: soulbound pieces are taken out first. */
    public static void stash(Inventory inv) {
        if (!(inv.player instanceof ServerPlayer p)) return;
        List<Kept> kept = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && is(s)) {
                kept.add(new Kept(i, s.copy()));
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (!kept.isEmpty()) KEPT.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).addAll(kept);
    }

    static void init() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
            List<Kept> kept = KEPT.remove(oldP.getUUID());
            if (kept == null) return;
            Inventory inv = newP.getInventory();
            for (Kept k : kept) {
                if (inv.getItem(k.slot()).isEmpty()) inv.setItem(k.slot(), k.stack());
                else if (!inv.add(k.stack())) newP.drop(k.stack(), false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
        });
    }
}
