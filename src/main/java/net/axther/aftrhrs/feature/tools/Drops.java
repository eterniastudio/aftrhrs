package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drop table: Arcana items dropped by vanilla mobs (source = entity type name) or Arcana creatures
 * (source = {@code mythic:<MythicMobs id>}, mapped to the native creature type). Player kills only; never from spawner /
 * trial spawner mobs (tagged by {@code SpawnReasonMixin}, also for mobs migrated from Paper), nor slimes split from one.
 * {@code credit}: {@code killer} (the last hit), {@code top-damage}, {@code participants}.
 */
final class Drops {
    /** Entity tag prefix for a mob's spawn reason ({@code aftrhrs.spawn.spawner}, {@code aftrhrs.spawn.trial_spawner}). */
    public static final String SPAWN_TAG = "aftrhrs.spawn.";
    /** Paper-era marker on slimes split from a blocked parent. */
    private static final String NO_DROPS = "arcanatools:no_table_drops";

    private record Entry(String source, String item, double chance, boolean broadcast, boolean giveToKiller,
                         boolean looting, String credit) {
    }

    private final ToolsModule plugin;
    private boolean enabled;
    private final Map<String, List<Entry>> bySource = new HashMap<>();
    private final Set<String> damageTracked = new HashSet<>();
    /** victim UUID -> (player UUID -> damage dealt) */
    private final Map<UUID, Map<UUID, Double>> damage = new HashMap<>();
    private final Set<String> blockedReasons = new HashSet<>();
    private double lootingBonus, participantMinShare;
    private String broadcastMsg, killerMsg;
    private final Set<String> warnedMissing = new HashSet<>();

    Drops(ToolsModule plugin) {
        this.plugin = plugin;
    }

    void load(Yaml c) {
        enabled = c.getBoolean("drops.enabled", true);
        lootingBonus = Math.max(0, c.getDouble("drops.looting-bonus-per-level", 0.33));
        broadcastMsg = c.getString("drops.broadcast-message", "<prefix><text><player></text> <muted>found</muted> <item> <muted>from</muted> <mob><muted>!");
        killerMsg = c.getString("drops.killer-message", "<prefix><muted>Something dropped:</muted> <item>");
        blockedReasons.clear();
        for (String s : c.getStringList("drops.blocked-spawn-reasons")) blockedReasons.add(s.trim().toLowerCase(Locale.ROOT));
        participantMinShare = Math.max(0, Math.min(1, c.getDouble("drops.participant-min-share", 0.05)));
        damageTracked.clear();
        damage.clear();
        bySource.clear();
        warnedMissing.clear();
        int count = 0;
        for (Map<?, ?> raw : c.getMapList("drops.table")) {
            Object src = raw.get("source"), item = raw.get("item"), chance = raw.get("chance");
            if (src == null || item == null || chance == null) {
                plugin.log().warn("drops.table entry needs source, item and chance: {}", raw);
                continue;
            }
            String source = src.toString().trim();
            EntityType<?> type;
            if (source.regionMatches(true, 0, "mythic:", 0, 7)) {
                type = ArcanaEntities.byMythicId(source.substring(7));
                if (type == null) {
                    plugin.log().warn("drops.table: unknown Arcana creature {}", source);
                    continue;
                }
            } else {
                Identifier id = Identifier.tryParse(source.toLowerCase(Locale.ROOT).contains(":") ? source.toLowerCase(Locale.ROOT)
                        : "minecraft:" + source.toLowerCase(Locale.ROOT));
                type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                if (type == null) {
                    plugin.log().warn("drops.table: unknown entity type {} (use mythic:Name for Arcana creatures)", source);
                    continue;
                }
            }
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            double p;
            try {
                p = Double.parseDouble(chance.toString());
            } catch (NumberFormatException e) {
                plugin.log().warn("drops.table: bad chance {} for {}", chance, item);
                continue;
            }
            String credit = raw.get("credit") == null ? "killer" : raw.get("credit").toString().trim().toLowerCase(Locale.ROOT);
            if (!credit.equals("killer") && !credit.equals("top-damage") && !credit.equals("participants")) {
                plugin.log().warn("drops.table: credit must be killer, top-damage or participants ({}); using killer", item);
                credit = "killer";
            }
            if (!credit.equals("killer")) damageTracked.add(key);
            String itemId = item.toString().trim().toLowerCase(Locale.ROOT);
            if (!itemId.startsWith("minecraft:")) itemId = ToolsModule.stripNs(itemId);
            bySource.computeIfAbsent(key, k -> new ArrayList<>()).add(new Entry(key, itemId, Math.max(0, Math.min(1, p)),
                    bool(raw.get("broadcast"), false), bool(raw.get("give-to-killer"), false), bool(raw.get("looting"), true), credit));
            count++;
        }
        plugin.log().info("Loaded {} drop table entries.", count);
    }

    private static boolean bool(Object o, boolean def) {
        return o == null ? def : Boolean.parseBoolean(o.toString());
    }

    Set<String> configuredIds() {
        Set<String> ids = new HashSet<>();
        for (List<Entry> list : bySource.values()) for (Entry e : list) if (!e.item().startsWith("minecraft:")) ids.add(e.item());
        return ids;
    }

    private static String key(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    void onDeath(LivingEntity entity, DamageSource source) {
        if (!enabled || bySource.isEmpty() || entity instanceof Player) return;
        Map<UUID, Double> dealt = damage.remove(entity.getUUID());
        ServerPlayer killer = entity.getLastHurtByPlayer() instanceof ServerPlayer sp ? sp : null;
        if (killer == null) killer = FeatherEvents.attacker(source);
        if (killer == null) killer = topDamager(dealt); // a tracked boss finished off by fire, a fall or a pet
        if (killer == null || isBlocked(entity)) return;
        List<Entry> candidates = bySource.get(key(entity));
        if (candidates == null || candidates.isEmpty()) return;
        ServerLevel level = (ServerLevel) entity.level();
        int looting = EnchantmentHelper.getItemEnchantmentLevel(
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOOTING), killer.getMainHandItem());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Entry e : candidates) for (ServerPlayer receiver : receivers(e, killer, dealt)) give(e, receiver, entity, looting, rnd);
    }

    private void give(Entry e, ServerPlayer killer, LivingEntity entity, int looting, ThreadLocalRandom rnd) {
        double chance = e.chance();
        if (e.looting() && looting > 0) chance = Math.min(1.0, chance * (1.0 + looting * lootingBonus));
        if (rnd.nextDouble() >= chance) return;
        ItemStack stack = build(e.item());
        if (stack == null) return;
        Component itemName = Adv.adventure(stack.getHoverName());
        if (e.giveToKiller()) {
            ItemStack copy = stack.copy();
            if (!killer.getInventory().add(copy) && !copy.isEmpty()) killer.drop(copy, false, net.minecraft.util.Prediction.SERVER_ONLY);
        } else {
            entity.spawnAtLocation((ServerLevel) entity.level(), stack);
        }
        var resolvers = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                Placeholder.unparsed("player", killer.getGameProfile().name()),
                Placeholder.component("item", itemName),
                Placeholder.component("mob", Adv.adventure(entity.getName()))};
        if (e.broadcast()) {
            Component msg = plugin.text(broadcastMsg, resolvers);
            for (ServerPlayer p : plugin.feather.onlinePlayers()) Adv.send(p, msg);
            plugin.log().info(Adv.plain(msg));
        } else if (killerMsg != null && !killerMsg.isBlank()) {
            Adv.send(killer, plugin.text(killerMsg, resolvers));
        }
    }

    private List<ServerPlayer> receivers(Entry e, ServerPlayer killer, Map<UUID, Double> dealt) {
        if (e.credit().equals("killer") || dealt == null || dealt.isEmpty()) return List.of(killer);
        if (e.credit().equals("top-damage")) {
            ServerPlayer top = topDamager(dealt);
            return List.of(top != null ? top : killer);
        }
        double total = 0;
        for (double d : dealt.values()) total += d;
        List<ServerPlayer> out = new ArrayList<>();
        for (Map.Entry<UUID, Double> d : dealt.entrySet()) {
            ServerPlayer p = plugin.feather.player(d.getKey());
            if (p != null && total > 0 && d.getValue() / total >= participantMinShare) out.add(p);
        }
        if (out.isEmpty()) out.add(killer);
        return out;
    }

    private ServerPlayer topDamager(Map<UUID, Double> dealt) {
        if (dealt == null) return null;
        ServerPlayer best = null;
        double most = 0;
        for (Map.Entry<UUID, Double> d : dealt.entrySet()) {
            ServerPlayer p = plugin.feather.player(d.getKey());
            if (p != null && d.getValue() > most) {
                best = p;
                most = d.getValue();
            }
        }
        return best;
    }

    /** Records player damage on mobs that have top-damage / participants entries (bosses). */
    void onDamaged(LivingEntity victim, DamageSource source, float taken) {
        if (!enabled || damageTracked.isEmpty() || victim instanceof Player || taken <= 0) return;
        ServerPlayer by = FeatherEvents.attacker(source);
        if (by == null && source.getEntity() instanceof OwnableEntity pet && pet.getOwner() instanceof ServerPlayer owner) by = owner;
        if (by == null || !damageTracked.contains(key(victim))) return;
        if (damage.size() > 256) damage.keySet().removeIf(id -> ((ServerLevel) victim.level()).getEntity(id) == null);
        damage.computeIfAbsent(victim.getUUID(), k -> new HashMap<>()).merge(by.getUUID(), (double) taken, Double::sum);
    }

    /** Mobs from a blocked spawn reason, or split from one (split slimes inherit entity tags). */
    private boolean isBlocked(Entity entity) {
        for (String r : blockedReasons) if (entity.entityTags().contains(SPAWN_TAG + r)) return true;
        return Pdc.has(entity, NO_DROPS);
    }

    private ItemStack build(String id) {
        if (id.startsWith("minecraft:")) {
            Identifier key = Identifier.tryParse(id);
            Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
            if (item != null && item != Items.AIR) return new ItemStack(item);
            if (warnedMissing.add(id)) plugin.log().warn("Drop table item {} is not a Minecraft item; skipped.", id);
            return null;
        }
        ItemStack s = ArcanaContent.stack(id, 1);
        if (!s.isEmpty()) return s;
        if (warnedMissing.add(id)) plugin.log().warn("Drop table item {} does not exist; skipped.", id);
        return null;
    }
}
