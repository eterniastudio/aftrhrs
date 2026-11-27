package net.axther.aftrhrs.feature.furniture.gadgets;

import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Sacks keep their contents through the furniture's own SHULKER storage (the broken sack drops as an item holding its
 * contents, and placing it puts them back). This class only adds the weight: carrying more than {@code max-carried}
 * heavy items (sacks by default) makes a player overencumbered (Slowness) until they drop some.
 */
final class Sacks {
    private final GadgetsModule g;
    private Set<String> heavy = Set.of();
    private Set<Item> heavyItems = Set.of();
    private int maxCarried = 2, slowAmp = 1, interval = 40;
    private boolean enabled = true;
    private final Set<UUID> burdened = new HashSet<>();

    Sacks(GadgetsModule g) {
        this.g = g;
    }

    void load(Yaml c) {
        enabled = c.getBoolean("overencumbered.enabled", true);
        Set<String> heavyIds = new HashSet<>();
        Set<Item> items = new HashSet<>();
        for (String s : c.getStringList("overencumbered.heavy-items")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("minecraft:")) {
                Identifier id = Identifier.tryParse(t);
                Item m = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (m != null && m != Items.AIR) items.add(m);
                else g.warnOnce("heavy:" + t, "gadgets.yml sack.overencumbered.heavy-items: unknown item " + s);
            } else if (!t.isEmpty()) {
                heavyIds.add(t.startsWith("oraxen:") ? t.substring(7) : t);
            }
        }
        if (heavyIds.isEmpty() && items.isEmpty() && !c.contains("overencumbered.heavy-items")) heavyIds.add("arcana_sack");
        heavy = heavyIds;
        heavyItems = items;
        maxCarried = Math.max(0, c.getInt("overencumbered.max-carried", 2));
        slowAmp = Math.max(0, c.getInt("overencumbered.slowness-amplifier", 1));
        interval = Math.max(10, c.getInt("overencumbered.check-ticks", 40));
    }

    int interval() {
        return interval;
    }

    void forget(ServerPlayer p) {
        burdened.remove(p.getUUID());
    }

    /** Number of heavy items a player carries (inventory, armor, off hand and the cursor). */
    int carried(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) n += weight(p.getInventory().getItem(i));
        n += weight(p.containerMenu.getCarried());
        return n;
    }

    private int weight(ItemStack s) {
        if (s == null || s.isEmpty()) return 0;
        if (heavyItems.contains(s.getItem())) return s.getCount();
        if (heavy.isEmpty()) return 0;
        String id = GadgetsModule.idOf(s);
        return id != null && heavy.contains(id) ? s.getCount() : 0;
    }

    void tick() {
        if (!enabled) return;
        for (ServerPlayer p : g.feather.onlinePlayers()) {
            boolean over = carried(p) > maxCarried;
            if (over) {
                p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, interval + 20, slowAmp, true, false, true));
                if (burdened.add(p.getUUID())) g.msg(p, g.message("overencumbered", "<warn>Overencumbered! You carry too many sacks."));
            } else if (burdened.remove(p.getUUID())) {
                MobEffectInstance s = p.getEffect(MobEffects.SLOWNESS);
                if (s != null && s.isAmbient() && s.getAmplifier() == slowAmp && s.getDuration() <= interval + 20) {
                    p.removeEffect(MobEffects.SLOWNESS);
                }
                g.msg(p, g.message("unencumbered", "<good>You can move freely again."));
            }
        }
    }
}
