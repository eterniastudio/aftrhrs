package net.axther.aftrhrs.feature.toolstats;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Timezones;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tool stats: tools, weapons and armor remember who crafted them and when, and count blocks broken, mobs and
 * players slain, damage dealt, repairs, mending, fish caught, arrows shot, hits blocked or taken, and distance
 * flown or walked, in the item's {@code custom_data} ({@code feather:stats_*}, the same keys and number types Paper's
 * PersistentDataContainer used, so migrated items keep their stats). The lore shows a small block with blocks broken
 * and mobs slain (refreshed at most every few seconds); {@code /inspect} shows everything in a dialog, with times in
 * the viewer's time zone ({@link Timezones}, {@code /timezone}, owned by this module).
 */
public final class ToolStatsModule implements FeatherModule, FeatherCommand {
    /** Item groups that can carry stats ({@code tool-stats.items}). */
    enum Category {
        PICKAXES, AXES, SHOVELS, HOES, SWORDS, SPEARS, ARMOR, ELYTRA, BOWS, CROSSBOWS, TRIDENTS, MACES,
        FISHING_RODS, SHEARS, SHIELDS;

        static Category of(ItemStack stack) {
            Item item = stack.getItem();
            var build = net.axther.aftrhrs.feature.smithing.Smithing.BUILD == null ? null : stack.get(net.axther.aftrhrs.feature.smithing.Smithing.BUILD);
            if (build != null) return switch (build.tool()) {
                case "pickaxe", "sledge", "smithing_hammer" -> PICKAXES;
                case "hatchet", "broad_axe", "mattock" -> AXES;
                case "shovel", "excavator" -> SHOVELS;
                case "hoe", "scythe" -> HOES;
                case "sword", "dagger", "greatsword" -> SWORDS;
                case "spear" -> SPEARS;
                case "mace" -> MACES;
                case "shortbow", "longbow" -> BOWS;
                case "crossbow" -> CROSSBOWS;
                case "fishing_rod" -> FISHING_RODS;
                case "shield" -> SHIELDS;
                case "helmet", "chestplate", "leggings", "boots" -> ARMOR;
                default -> null;
            };
            String n = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (n.endsWith("_pickaxe") || stack.is(ItemTags.PICKAXES)) return PICKAXES;
            if (n.endsWith("_axe") || stack.is(ItemTags.AXES)) return AXES;
            if (n.endsWith("_shovel") || stack.is(ItemTags.SHOVELS)) return SHOVELS;
            if (n.endsWith("_hoe") || stack.is(ItemTags.HOES)) return HOES;
            if (n.endsWith("_sword") || stack.is(ItemTags.SWORDS)) return SWORDS;
            if (n.endsWith("_spear") || stack.is(ItemTags.SPEARS)) return SPEARS;
            if (n.endsWith("_helmet") || n.endsWith("_chestplate") || n.endsWith("_leggings") || n.endsWith("_boots")
                    || stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.LEG_ARMOR)
                    || stack.is(ItemTags.FOOT_ARMOR)) return ARMOR;
            if (item == Items.ELYTRA) return ELYTRA;
            if (item instanceof BowItem) return BOWS;
            if (item instanceof CrossbowItem) return CROSSBOWS;
            if (item instanceof TridentItem) return TRIDENTS;
            if (item instanceof MaceItem) return MACES;
            if (item instanceof FishingRodItem) return FISHING_RODS;
            if (item instanceof ShearsItem) return SHEARS;
            if (item instanceof ShieldItem) return SHIELDS;
            return null;
        }
    }

    private static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final EquipmentSlot[] HELD_AND_WORN = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static String key(String name) {
        return "feather:stats_" + name;
    }

    private static final String CRAFTED_AT = key("crafted_at"), CRAFTER = key("crafter"), CRAFTER_NAME = key("crafter_name"),
            FIRST_USED = key("first_used"), FIRST_USER = key("first_user"), UPGRADED_AT = key("upgraded_at"),
            BLOCKS = key("blocks"), MOBS = key("mobs"), PLAYERS = key("players"), DAMAGE = key("damage"),
            REPAIRS = key("repairs"), MENDED = key("mended"), FISH = key("fish"), SHOTS = key("shots"),
            BLOCKED = key("blocked"), HITS_TAKEN = key("hits_taken"), FLOWN = key("flown"), WALKED = key("walked"),
            KILLS = key("kills"), LORE_LINES = key("lore_lines");

    private static volatile ToolStatsModule active;

    private final Feather plugin;
    private final Timezones timezones;

    private boolean enabled;
    private boolean lore;
    private long loreIntervalMs;
    private int topKills;
    private Set<Category> categories = EnumSet.allOf(Category.class);

    /** Players whose worn/held items need a lore refresh, and when each last got one. */
    private final Set<UUID> pendingLore = new HashSet<>();
    private final Map<UUID, Long> lastLore = new HashMap<>();
    /** Distance sampling: last position, and meters not yet written to the elytra / boots. */
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Map<UUID, ServerLevel> lastLevel = new HashMap<>();
    private final Map<UUID, double[]> travel = new HashMap<>();
    /** Health + absorption of entities just before a hit, so a hit counts what actually came off (Paper's final damage). */
    /** Identity (entities compare by identity); a strong reference lives at most until tick() clears it. */
    private final Map<LivingEntity, Float> healthBefore = new IdentityHashMap<>();
    private Tasks.Task task;
    private long ticks;

    public ToolStatsModule(Feather plugin) {
        this.plugin = plugin;
        this.timezones = new Timezones(plugin);
    }

    @Override
    public String name() {
        return "Tool stats";
    }

    public Timezones timezones() {
        return timezones;
    }

    @Override
    public void enable() {
        reload();
        plugin.provide(Timezones.class, timezones);
        plugin.bind("inspect", this);
        plugin.bind("timezone", timezones);
        plugin.hub().addSettingsButton("timezone", Hub.SETTINGS_TIMEZONE, p -> Perms.has(p, "feather.timezone"),
                (menu, p, width, back) -> menu.button(plugin.text().msg("menu.settings.timezone", Text.unparsed("zone", timezones.zone(p).getId())),
                        plugin.text().msg("menu.settings.timezone-tooltip"), width, pp -> {
                            plugin.text().sound(pp, "hub-toggle");
                            timezones.openTimezonePicker(pp, back);
                        }));
        active = this;
        plugin.once("toolstats.events", ToolStatsModule::registerEvents);
        task = Tasks.timer(20L, 20L, this::tick);
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        enabled = c.getBoolean("tool-stats.enabled", true);
        lore = c.getBoolean("tool-stats.lore", true);
        loreIntervalMs = Math.max(0, (long) (c.getDouble("tool-stats.lore-refresh-seconds", 3) * 1000));
        topKills = Math.max(0, c.getInt("tool-stats.top-kills", 5));
        Set<Category> cats = EnumSet.noneOf(Category.class);
        List<String> list = c.isList("tool-stats.items") ? c.getStringList("tool-stats.items") : List.of();
        if (list.isEmpty()) cats.addAll(EnumSet.allOf(Category.class));
        for (String s : list) {
            try {
                cats.add(Category.valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warn("tool-stats.items: unknown group '{}'.", s);
            }
        }
        categories = cats;
        timezones.reload();
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            flushTravel(p);
            refreshLore(p);
        }
        if (active == this) active = null;
        healthBefore.clear();
        plugin.withdraw(Timezones.class);
    }

    // ------------------------------------------------------------------ events (registered once per JVM)

    private static void registerEvents() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            ToolStatsModule m = active;
            if (m != null && player instanceof ServerPlayer p) m.onBreak(p);
        });
        FeatherEvents.ITEM_CRAFTED.register((p, stack) -> {
            ToolStatsModule m = active;
            if (m != null) m.onCrafted(p, stack);
        });
        FeatherEvents.COMBINE_RESULT.register((menu, left, right, result) -> {
            ToolStatsModule m = active;
            if (m != null) m.onCombined(left, right, result);
        });
        FeatherEvents.ITEM_MENDED.register((p, stack, points) -> {
            ToolStatsModule m = active;
            if (m != null && m.enabled && points > 0) m.bump(p, stack, false, t -> add(t, MENDED, points));
        });
        FeatherEvents.FISH_CAUGHT.register((p, rod, hook, loot) -> {
            ToolStatsModule m = active;
            if (m != null && m.enabled && m.active(p)) m.bump(p, rod, false, t -> add(t, FISH, 1));
        });
        FeatherEvents.STAT_AWARDED.register((p, stat, n) -> {
            ToolStatsModule m = active;
            if (m != null) m.onStat(p, stat);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            ToolStatsModule m = active;
            // every consumer of the snapshot needs a player victim or a player source; a missing entry falls back
            if (m != null && m.enabled && (entity instanceof ServerPlayer || FeatherEvents.attacker(source) != null))
                m.healthBefore.put(entity, entity.getHealth() + entity.getAbsorptionAmount());
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            ToolStatsModule m = active;
            if (m != null) m.onDamaged(entity, source, taken, blocked);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            ToolStatsModule m = active;
            if (m != null) m.onDeath(entity, source);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            ToolStatsModule m = active;
            if (m != null) m.onQuit(h.player);
        });
    }

    // ------------------------------------------------------------------ helpers

    boolean tracked(ItemStack item) {
        if (item == null || item.isEmpty() || item.getCount() != 1) return false;
        Category cat = Category.of(item);
        return cat != null && categories.contains(cat);
    }

    private boolean active(ServerPlayer p) {
        return enabled && !p.isCreative() && !p.isSpectator();
    }

    private static CompoundTag data(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.copyTag();
    }

    private static long get(CompoundTag t, String k) {
        return t.getLong(k).orElse(0L);
    }

    private static void add(CompoundTag t, String k, long n) {
        t.putLong(k, get(t, k) + n);
    }

    private static void addDouble(CompoundTag t, String k, double n) {
        t.putDouble(k, t.getDouble(k).orElse(0.0) + n);
    }

    /**
     * Changes the stats of a stack a player holds or wears, in place (the live inventory stack, synced to the client
     * by the inventory menu). Sets "first used" on the first change. {@code showsInLore} marks counters the lore shows;
     * the lore then refreshes now or a few seconds later.
     */
    private void bump(ServerPlayer p, ItemStack item, boolean showsInLore, Consumer<CompoundTag> change) {
        if (!tracked(item)) return;
        boolean loreNow = showsInLore && lore && loreDue(p);
        if (showsInLore && lore && !loreNow) pendingLore.add(p.getUUID());
        CustomData.update(DataComponents.CUSTOM_DATA, item, t -> {
            markUsed(t, p);
            change.accept(t);
        });
        if (loreNow) writeLore(item);
    }

    private void bump(ServerPlayer p, EquipmentSlot slot, boolean showsInLore, Consumer<CompoundTag> change) {
        bump(p, p.getItemBySlot(slot), showsInLore, change);
    }

    private void markUsed(CompoundTag t, ServerPlayer p) {
        if (t.contains(FIRST_USED)) return;
        t.putLong(FIRST_USED, System.currentTimeMillis());
        if (!t.contains(CRAFTER)) t.putString(FIRST_USER, Names.name(p));
    }

    private boolean loreDue(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Long last = lastLore.get(p.getUUID());
        if (last != null && now - last < loreIntervalMs) return false;
        lastLore.put(p.getUUID(), now);
        pendingLore.remove(p.getUUID());
        return true;
    }

    private void stampCrafted(ItemStack item, ServerPlayer p) {
        CustomData.update(DataComponents.CUSTOM_DATA, item, t -> {
            if (t.contains(CRAFTER)) return;
            t.putLong(CRAFTED_AT, System.currentTimeMillis());
            t.putString(CRAFTER, p.getUUID().toString());
            t.putString(CRAFTER_NAME, Names.name(p));
        });
    }

    // ------------------------------------------------------------------ lore

    /** The lore block (separator + non-zero lines); empty when there's nothing to show. */
    private List<Component> statLines(CompoundTag t) {
        Text text = plugin.text();
        List<Component> out = new ArrayList<>();
        long b = get(t, BLOCKS);
        long slain = get(t, MOBS) + get(t, PLAYERS);
        if (b == 0 && slain == 0) return out;
        String sep = text.raw("inspect.lore.separator");
        if (!sep.isEmpty()) out.add(text.item(sep));
        if (b > 0) out.add(text.itemMsg("inspect.lore.blocks", Text.unparsed("count", number(b))));
        if (slain > 0) out.add(text.itemMsg("inspect.lore.slain", Text.unparsed("count", number(slain))));
        return out;
    }

    /** Replaces Feather's lore block at the end of the lore, leaving every other line (item lore, renames) alone. True if it changed. */
    private boolean writeLore(ItemStack item) {
        CompoundTag t = data(item);
        ItemLore before = item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<net.minecraft.network.chat.Component> current = new ArrayList<>(before.lines());
        int old = t.getInt(LORE_LINES).orElse(0);
        if (old > 0 && current.size() >= old && ownsTail(current, old)) {
            current.subList(current.size() - old, current.size()).clear();
        }
        List<Component> mine = lore ? statLines(t) : List.of();
        for (Component c : mine) current.add(Adv.vanilla(c));
        if (current.equals(before.lines()) && (mine.isEmpty() ? old == 0 : old == mine.size())) return false;
        if (current.isEmpty()) item.remove(DataComponents.LORE);
        else item.set(DataComponents.LORE, new ItemLore(current));
        CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> {
            if (mine.isEmpty()) tag.remove(LORE_LINES);
            else tag.putInt(LORE_LINES, mine.size());
        });
        return true;
    }

    /** Whether the last {@code n} lines are still ours (something else may have rewritten the lore since). */
    private boolean ownsTail(List<net.minecraft.network.chat.Component> lines, int n) {
        String sep = plugin.text().raw("inspect.lore.separator");
        if (sep.isEmpty()) return true;
        return lines.get(lines.size() - n).getString().equals(Adv.plain(plugin.text().item(sep)));
    }

    /** Refreshes the lore of a player's held and worn items where it's out of date. */
    private void refreshLore(ServerPlayer p) {
        for (EquipmentSlot slot : HELD_AND_WORN) {
            ItemStack item = p.getItemBySlot(slot);
            if (tracked(item)) writeLore(item);
        }
    }

    private void tick() {
        ticks++;
        healthBefore.clear();
        long now = System.currentTimeMillis();
        if (!pendingLore.isEmpty()) {
            for (UUID id : new ArrayList<>(pendingLore)) {
                Long last = lastLore.get(id);
                if (last != null && now - last < loreIntervalMs) continue;
                pendingLore.remove(id);
                lastLore.put(id, now);
                ServerPlayer p = plugin.player(id);
                if (p != null) refreshLore(p);
            }
        }
        // Distance with elytra / boots, sampled every 2 s and written every 30 s.
        if (ticks % 2 == 0) {
            for (ServerPlayer p : plugin.onlinePlayers()) sampleTravel(p);
        }
        if (ticks % 30 == 0) {
            for (ServerPlayer p : plugin.onlinePlayers()) flushTravel(p);
        }
    }

    private void sampleTravel(ServerPlayer p) {
        Vec3 at = p.position();
        ServerLevel level = p.level();
        Vec3 before = lastPos.put(p.getUUID(), at);
        ServerLevel beforeLevel = lastLevel.put(p.getUUID(), level);
        if (before == null || beforeLevel != level || !active(p) || p.isPassenger() || p.getAbilities().flying) return;
        double d = before.distanceTo(at);
        if (d < 0.5 || d > 150) return; // standing still, or a teleport
        double[] t = travel.computeIfAbsent(p.getUUID(), k -> new double[2]);
        if (p.isFallFlying()) t[0] += d;
        else t[1] += d;
    }

    private void flushTravel(ServerPlayer p) {
        double[] t = travel.remove(p.getUUID());
        if (t == null) return;
        if (t[0] > 0) {
            ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.is(Items.ELYTRA)) {
                double d = t[0];
                bump(p, chest, false, tag -> addDouble(tag, FLOWN, d));
            }
        }
        if (t[1] > 0) {
            ItemStack boots = p.getItemBySlot(EquipmentSlot.FEET);
            if (!boots.isEmpty() && (BuiltInRegistries.ITEM.getKey(boots.getItem()).getPath().endsWith("_boots") || boots.is(ItemTags.FOOT_ARMOR))) {
                double d = t[1];
                bump(p, boots, false, tag -> addDouble(tag, WALKED, d));
            }
        }
    }

    private void onQuit(ServerPlayer p) {
        flushTravel(p);
        if (pendingLore.remove(p.getUUID())) refreshLore(p);
        lastPos.remove(p.getUUID());
        lastLevel.remove(p.getUUID());
        lastLore.remove(p.getUUID());
    }

    // ------------------------------------------------------------------ crafting & repairs

    /** A tool taken out of a crafting grid or smithing table gets its crafter (an upgrade keeps the original one). */
    private void onCrafted(ServerPlayer p, ItemStack stack) {
        if (!enabled || !tracked(stack)) return;
        var menu = p.containerMenu;
        boolean smithing = menu instanceof SmithingMenu;
        if (!smithing && !(menu instanceof CraftingMenu) && !(menu instanceof InventoryMenu)) return;
        stampCrafted(stack, p);
        if (smithing) CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.putLong(UPGRADED_AT, System.currentTimeMillis()));
    }

    /**
     * An anvil or grindstone result that restores durability or combines two items counts as a repair. Counted on the
     * result itself (what the player takes), so shift-clicking it out counts too.
     */
    private void onCombined(ItemStack left, ItemStack right, ItemStack result) {
        if (!enabled || !tracked(result)) return;
        boolean restored = result.getDamageValue() < Math.max(left.getDamageValue(), right.getDamageValue());
        boolean combined = !left.isEmpty() && !right.isEmpty() && left.getItem() == right.getItem();
        if (!restored && !combined) return;
        CustomData.update(DataComponents.CUSTOM_DATA, result, t -> add(t, REPAIRS, 1));
    }

    // ------------------------------------------------------------------ use

    private void onBreak(ServerPlayer p) {
        if (!active(p)) return;
        bump(p, EquipmentSlot.MAINHAND, true, t -> add(t, BLOCKS, 1));
    }

    /** Bow and crossbow shots (the ITEM_USED statistic of the weapon). */
    private void onStat(ServerPlayer p, Stat<?> stat) {
        if (!enabled || stat.getType() != Stats.ITEM_USED || !(stat.getValue() instanceof Item item)) return;
        if (!(item instanceof BowItem) && !(item instanceof CrossbowItem)) return;
        if (!active(p)) return;
        ItemStack weapon = p.getUseItem().is(item) ? p.getUseItem()
                : p.getMainHandItem().is(item) ? p.getMainHandItem() : p.getOffhandItem().is(item) ? p.getOffhandItem() : ItemStack.EMPTY;
        bump(p, weapon, false, t -> add(t, SHOTS, 1));
    }

    /** The player and the item behind a hit: a melee swing, an arrow from their bow, or their thrown trident. */
    private record Source(ServerPlayer player, ItemStack item, boolean loose) {
    }

    private Source source(DamageSource damage) {
        Entity direct = damage.getDirectEntity();
        if (direct instanceof ServerPlayer p) return new Source(p, p.getMainHandItem(), false);
        if (direct instanceof ThrownTrident t && t.getOwner() instanceof ServerPlayer p) return new Source(p, t.getPickupItemStackOrigin(), true);
        if (direct instanceof AbstractArrow arrow && arrow.getOwner() instanceof ServerPlayer p) {
            ItemStack weapon = arrow.getWeaponItem();
            if (weapon == null || weapon.isEmpty()) return null;
            if (p.getMainHandItem().is(weapon.getItem())) return new Source(p, p.getMainHandItem(), false);
            if (p.getOffhandItem().is(weapon.getItem())) return new Source(p, p.getOffhandItem(), false);
        }
        return null;
    }

    private void credit(Source s, boolean showsInLore, Consumer<CompoundTag> change) {
        if (!active(s.player())) return;
        if (s.loose()) {
            // A thrown trident: not in an inventory, so its lore is written right away.
            if (!tracked(s.item())) return;
            CustomData.update(DataComponents.CUSTOM_DATA, s.item(), t -> {
                markUsed(t, s.player());
                change.accept(t);
            });
            if (showsInLore && lore) writeLore(s.item());
        } else {
            bump(s.player(), s.item(), showsInLore, change);
        }
    }

    /** What came off the entity in this hit (health + absorption before minus after), or {@code fallback}. */
    private double dealt(LivingEntity target, double fallback, boolean died) {
        Float before = healthBefore.remove(target);
        if (before == null) return fallback;
        return died ? before : Math.max(0, before - (target.getHealth() + target.getAbsorptionAmount()));
    }

    private void onDamaged(LivingEntity target, DamageSource damage, float taken, boolean blocked) {
        if (!enabled) return;
        double dealt = dealt(target, taken, false);
        if (target instanceof ServerPlayer victim && active(victim)) {
            if (blocked) {
                ItemStack shield = victim.getUseItem();
                if (shield.getItem() instanceof ShieldItem) bump(victim, shield, false, t -> add(t, BLOCKED, 1));
            }
            if (dealt > 0) hurtArmor(victim);
        }
        hit(target, damage, dealt);
    }

    private void hurtArmor(ServerPlayer victim) {
        for (EquipmentSlot slot : WORN) {
            ItemStack piece = victim.getItemBySlot(slot);
            if (tracked(piece) && Category.of(piece) == Category.ARMOR) bump(victim, piece, false, t -> add(t, HITS_TAKEN, 1));
        }
    }

    private void hit(LivingEntity target, DamageSource damage, double dealt) {
        if (dealt <= 0) return;
        Source s = source(damage);
        if (s == null || s.player() == target) return;
        credit(s, false, t -> addDouble(t, DAMAGE, dealt));
    }

    private void onDeath(LivingEntity victim, DamageSource damage) {
        if (!enabled) return;
        double dealt = dealt(victim, 0, true);
        if (victim instanceof ServerPlayer vp && active(vp) && dealt > 0) hurtArmor(vp);
        hit(victim, damage, dealt);
        Source s = source(damage);
        if (s == null || s.player() == victim) return;
        if (victim instanceof ServerPlayer) {
            credit(s, true, t -> add(t, PLAYERS, 1));
            return;
        }
        String type = EntityType.getKey(victim.getType()).toString();
        credit(s, true, t -> {
            add(t, MOBS, 1);
            CompoundTag byType = t.getCompoundOrEmpty(KILLS);
            add(byType, type, 1);
            t.put(KILLS, byType);
        });
    }

    // ------------------------------------------------------------------ /inspect

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        ServerPlayer viewer = sender.player();
        if (viewer == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        ServerPlayer owner = viewer;
        if (args.length >= 1) {
            if (!sender.hasPermission("feather.inspect.others")) {
                text.send(viewer, "general.no-permission");
                return true;
            }
            owner = plugin.findPlayer(viewer, args[0]);
            if (owner == null) {
                plugin.notFound(viewer, args[0]);
                return true;
            }
        }
        inspect(viewer, owner);
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length != 1 || !sender.hasPermission("feather.inspect.others")) return List.of();
        return new ArrayList<>(plugin.completePlayers(sender, args[0].toLowerCase(Locale.ROOT)));
    }

    /** Opens the stats dialog for the item in {@code owner}'s main hand. */
    void inspect(ServerPlayer viewer, ServerPlayer owner) {
        Text text = plugin.text();
        boolean self = viewer == owner;
        flushTravel(owner);
        ItemStack item = owner.getMainHandItem();
        TagResolver who = Text.component("name", Names.display(owner));
        if (item.isEmpty()) {
            text.send(viewer, self ? "inspect.empty-hand" : "inspect.empty-hand-other", who);
            text.sound(viewer, "error");
            return;
        }
        Component itemName = Adv.adventure(item.getHoverName());
        if (Category.of(item) == null) {
            text.send(viewer, "inspect.not-tracked", Text.component("item", itemName));
            text.sound(viewer, "error");
            return;
        }
        CompoundTag t = data(item);
        List<Component> rows = new ArrayList<>();

        String crafterN = t.getString(CRAFTER_NAME).orElse(null);
        Long crafted = t.getLong(CRAFTED_AT).orElse(null);
        if (crafterN != null) {
            rows.add(row("crafted-by", crafterN));
            if (crafted != null) rows.add(row("crafted-at", timezones.format(crafted, viewer)));
        } else {
            rows.add(text.msg("inspect.rows.found"));
            String first = t.getString(FIRST_USER).orElse(null);
            if (first != null) rows.add(row("first-owner", first));
        }
        t.getLong(UPGRADED_AT).ifPresent(u -> rows.add(row("upgraded-at", timezones.format(u, viewer))));
        Long first = t.getLong(FIRST_USED).orElse(null);
        rows.add(first == null ? text.msg("inspect.rows.never-used") : row("first-used", timezones.format(first, viewer)));

        List<Component> counts = new ArrayList<>();
        countRow(counts, "blocks", get(t, BLOCKS), true);
        countRow(counts, "mobs", get(t, MOBS), true);
        countRow(counts, "players", get(t, PLAYERS), false);
        double dmg = t.getDouble(DAMAGE).orElse(0.0);
        if (dmg > 0) {
            counts.add(text.msg("inspect.rows.damage", Text.unparsed("value", String.format(Locale.ENGLISH, "%,.1f", dmg)),
                    Text.unparsed("hearts", String.format(Locale.ENGLISH, "%,.1f", dmg / 2))));
        }
        countRow(counts, "shots", get(t, SHOTS), false);
        countRow(counts, "fish", get(t, FISH), false);
        countRow(counts, "blocked", get(t, BLOCKED), false);
        countRow(counts, "hits-taken", get(t, HITS_TAKEN), false);
        distanceRow(counts, "flown", t.getDouble(FLOWN).orElse(null));
        distanceRow(counts, "walked", t.getDouble(WALKED).orElse(null));
        countRow(counts, "repairs", get(t, REPAIRS), true);
        countRow(counts, "mended", get(t, MENDED), false);
        if (item.isDamageableItem()) {
            int max = item.getMaxDamage();
            counts.add(text.msg("inspect.rows.durability", Text.unparsed("value", number(Math.max(0, max - item.getDamageValue()))),
                    Text.unparsed("max", number(max))));
        }

        List<Component> top = topKills(t);

        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("inspect.title"));
        menu.item(item.copy(), text.msg(self ? "inspect.heading" : "inspect.heading-other", Text.component("item", itemName),
                Text.unparsed("type", pretty(BuiltInRegistries.ITEM.getKey(item.getItem()).getPath())), who), 250);
        menu.text(Component.join(JoinConfiguration.newlines(), rows), 300);
        menu.text(Component.join(JoinConfiguration.newlines(), counts), 300);
        if (!top.isEmpty()) {
            menu.text(text.msg("inspect.top-kills", Text.component("kills",
                    Component.join(JoinConfiguration.separator(text.msg("inspect.top-kills-separator")), top))), 300);
        }
        menu.text(text.msg("inspect.footer", Text.unparsed("zone", timezones.zone(viewer).getId())), 300);
        if (Perms.has(viewer, "feather.timezone")) {
            UUID ownerId = owner.getUUID();
            menu.button(text.msg("inspect.timezone-button"), text.msg("inspect.timezone-button-tooltip"), 200,
                    p -> timezones.openTimezonePicker(p, back -> {
                        ServerPlayer o = plugin.player(ownerId);
                        if (o != null) inspect(back, o);
                    }));
        }
        menu.exit(text.msg("menu.close"), null);
        menu.open(viewer);
        text.sound(viewer, "menu-open");
        plugin.advance(viewer, "event", "inspect");
    }

    private Component row(String key, String value) {
        return plugin.text().msg("inspect.rows." + key, Text.unparsed("value", value));
    }

    private void countRow(List<Component> out, String key, long value, boolean always) {
        if (value > 0 || always) out.add(row(key, number(value)));
    }

    private void distanceRow(List<Component> out, String key, Double meters) {
        if (meters == null || meters < 1) return;
        String v = meters >= 1000 ? String.format(Locale.ENGLISH, "%,.1f km", meters / 1000) : String.format(Locale.ENGLISH, "%,.0f m", meters);
        out.add(row(key, v));
    }

    private List<Component> topKills(CompoundTag t) {
        CompoundTag byType = t.getCompound(KILLS).orElse(null);
        if (byType == null || topKills == 0) return List.of();
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        for (String k : byType.keySet()) entries.add(Map.entry(k, get(byType, k)));
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<Component> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : entries.subList(0, Math.min(topKills, entries.size()))) {
            Identifier id = Identifier.tryParse(e.getKey());
            EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            String path = id == null ? e.getKey() : id.getPath();
            Component name = type != null ? Component.translatable(type.getDescriptionId()) : Component.text(pretty(path));
            out.add(plugin.text().msg("inspect.top-kill", Text.component("mob", name), Text.unparsed("count", number(e.getValue()))));
        }
        return out;
    }

    static String number(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }

    private static String pretty(String key) {
        StringBuilder b = new StringBuilder();
        for (String w : key.split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
