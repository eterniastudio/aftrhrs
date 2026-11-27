package net.axther.aftrhrs.feature.advancements;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.triggers.ImpossibleTrigger;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Custom advancements (tabs "Aftr Hrs", "Companions", "Arcana Treasures", "Legends", "Home & Decor") read from
 * advancements.yml and added to the server's advancement list as {@code feather:<key>} (the ids the Paper server used,
 * so players' saved progress carries over). Every criterion is {@code minecraft:impossible}; Feather awards them
 * itself: modules report through {@link Feather#advance}, kills of wanted mobs and placed blocks come from Fabric
 * events, and a periodic scan checks inventories, helmets, playtime and pets.
 *
 * <p>On Paper they were loaded with Bukkit's unsafe loader into the world's datapack; here they are injected into
 * {@link ServerAdvancementManager} ({@link AdvancementInjector}), so a datapack {@code /reload} keeps them and
 * {@code /feather reload} changes them live (online players get their list resent only when something changed).
 *
 * <p>Targets: {@code tame}/{@code kill} name a mob either the MythicMobs way ({@code ArcanaBlackCat}) or by entity
 * id ({@code aftrhrs:black_cat}, {@code zombie}); both meet at the native entity id. {@code obtain}/{@code wear}/
 * {@code place} name ex-Oraxen item ids ({@code arcana_plush_beagle}, {@code oraxen:} prefix optional), which are now
 * {@code aftrhrs:<id>} items; globs ({@code arcana_hat_*}) and the content selector {@code furniture:<tab>[!<x>...]}
 * (every visible furniture piece of a creative tab, {@code *} for all; each {@code !<x>} leaves out a tab or ids
 * matching a glob, e.g. {@code furniture:*!feather_*}) are expanded at load.
 */
public final class AdvancementsModule implements FeatherModule {
    public static final String NAMESPACE = "feather";
    private static final Set<String> FRAMES = Set.of("task", "goal", "challenge");
    /** Triggers awarded through {@link Feather#advance} with a target (each also has an "-all" form). */
    private static final Set<String> TARGETED = Set.of("event", "tame", "kill", "obtain", "wear", "place");
    /** Triggers checked by the scan against a number. */
    private static final Set<String> VALUED = Set.of("playtime", "pets-count", "pet-level");
    /** Targets that are item ids (ex-Oraxen; globs allowed). */
    private static final Set<String> ITEM_TRIGGERS = Set.of("obtain", "wear", "place");
    private static final Set<String> MOB_TRIGGERS = Set.of("tame", "kill");

    private static volatile AdvancementsModule active;

    private final Feather plugin;
    private Tasks.Task scanTask;

    /** What advancements.yml asks for, parents first. */
    private final Map<Identifier, Entry> entries = new LinkedHashMap<>();
    /** Base trigger ("event", "obtain", "playtime" ...) -> entries. */
    private final Map<String, List<Entry>> byTrigger = new HashMap<>();
    /** Wanted-mob victims -> players who hurt them (weak: removed mobs go away by themselves). */
    private final Map<LivingEntity, Set<UUID>> damagers = new WeakHashMap<>();
    private Item fallbackIcon = Items.BOOK;
    private boolean announceToChat = true;

    /** One advancement: the holder, and which criteria a reported (canonical) target awards. */
    private record Entry(Identifier key, String id, String parent, String trigger, double value,
                         Map<String, List<String>> criteriaFor, AdvancementHolder holder) {
    }

    public AdvancementsModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Advancements";
    }

    @Override
    public void enable() {
        active = this;
        plugin.advancer(this::report);
        plugin.once("advancements.events", AdvancementsModule::hookEvents);
        readConfig();
        loadAndSync();
        startScan();
    }

    @Override
    public void reload() {
        readConfig();
        loadAndSync();
        startScan();
    }

    @Override
    public void disable() {
        if (scanTask != null) scanTask.cancel();
        plugin.advancer(null);
        if (active == this) active = null;
        damagers.clear();
        // The server is stopping: the next start (or a /reload without this module) leaves feather: out.
        AdvancementInjector.Extra.list = List.of();
    }

    private static void hookEvents() {
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            AdvancementsModule m = active;
            if (m != null) m.awardJoin(h.player);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            AdvancementsModule m = active;
            if (m != null && !blocked) m.hurt(victim, source);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
            AdvancementsModule m = active;
            if (m != null) m.died(victim, source);
        });
        FeatherEvents.BLOCK_PLACED.register((p, level, pos, state, stack) -> {
            AdvancementsModule m = active;
            if (m == null) return;
            String id = itemKey(stack);
            if (id != null) m.plugin.advance(p, "place", id);
        });
    }

    private void readConfig() {
        Yaml c = plugin.getConfig();
        String icon = c.getString("advancements.fallback-icon", "book");
        Item item = item(icon == null ? "book" : icon);
        fallbackIcon = item != null ? item : Items.BOOK;
        announceToChat = c.getBoolean("advancements.announce-to-chat", true);
    }

    private void startScan() {
        if (scanTask != null) scanTask.cancel();
        long ticks = Math.max(1, Math.round(plugin.getConfig().getDouble("advancements.scan-seconds", 5) * 20));
        scanTask = Tasks.timer(ticks, ticks, this::scan);
    }

    private void loadAndSync() {
        try {
            load();
            sync();
        } catch (RuntimeException e) {
            plugin.getLogger().error("Could not set up the advancements", e);
        }
        for (ServerPlayer p : plugin.onlinePlayers()) awardJoin(p);
    }

    // ------------------------------------------------------------------ advancements.yml -> advancements

    private void load() {
        entries.clear();
        byTrigger.clear();
        // Only the admin's copy counts (no bundled defaults: they would bring back entries they deleted).
        plugin.saveDefault("advancements.yml");
        Yaml yml = Yaml.load(plugin.getDataFolder().resolve("advancements.yml"));
        Yaml root = yml.getConfigurationSection("advancements");
        if (root == null) {
            plugin.getLogger().warn("advancements.yml has no 'advancements:' section.");
            return;
        }
        Set<String> itemIds = nativeItemIds();
        Map<String, Yaml> raw = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            Yaml s = root.getConfigurationSection(id);
            if (s == null) continue;
            if (Identifier.tryBuild(NAMESPACE, id) == null) {
                plugin.getLogger().warn("advancements.yml: '{}' is not a valid key (use a-z, 0-9, / . _ -); skipped.", id);
                continue;
            }
            raw.put(id, s);
        }
        List<String> unknownItems = new ArrayList<>();
        List<String> unknownMobs = new ArrayList<>();
        // Parents first; entries whose parent is missing (or that loop) are skipped with their children.
        Set<String> done = new LinkedHashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (var e : raw.entrySet()) {
                if (done.contains(e.getKey())) continue;
                String parent = e.getValue().getString("parent");
                if (parent != null && !done.contains(parent)) continue;
                Entry entry = build(e.getKey(), e.getValue(), itemIds, unknownItems, unknownMobs);
                if (entry == null) continue;
                done.add(e.getKey());
                entries.put(entry.key(), entry);
                byTrigger.computeIfAbsent(entry.trigger(), t -> new ArrayList<>()).add(entry);
                progress = true;
            }
        }
        for (String id : raw.keySet()) {
            if (!done.contains(id)) {
                plugin.getLogger().warn("advancements.yml: '{}' was skipped: its parent '{}' is missing or was skipped.", id,
                        raw.get(id).getString("parent"));
            }
        }
        if (!unknownItems.isEmpty()) {
            plugin.getLogger().warn("advancements.yml names {} item(s) the mod doesn't have (they can't be awarded until they exist): {}",
                    new HashSet<>(unknownItems).size(), preview(unknownItems));
        }
        if (!unknownMobs.isEmpty()) {
            plugin.getLogger().warn("advancements.yml names {} mob type(s) the mod doesn't have: {}", new HashSet<>(unknownMobs).size(),
                    preview(unknownMobs));
        }
    }

    private static String preview(List<String> list) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(list));
        return distinct.size() <= 12 ? String.join(", ", distinct)
                : String.join(", ", distinct.subList(0, 12)) + " ... (+" + (distinct.size() - 12) + " more)";
    }

    @SuppressWarnings("removal")
    private Entry build(String id, Yaml s, Set<String> itemIds, List<String> unknownItems, List<String> unknownMobs) {
        Identifier key = Identifier.fromNamespaceAndPath(NAMESPACE, id);
        String trigger = s.getString("trigger", "event").toLowerCase(Locale.ROOT).trim();
        boolean all = trigger.endsWith("-all");
        String base = all ? trigger.substring(0, trigger.length() - 4) : trigger;
        if (!TARGETED.contains(base) && !VALUED.contains(base) && !base.equals("join")) {
            plugin.getLogger().warn("advancements.yml: '{}' has an unknown trigger '{}'; it can't be earned.", id, trigger);
        }
        if (all && !TARGETED.contains(base)) {
            plugin.getLogger().warn("advancements.yml: '{}': '{}' has no -all form; treated as '{}'.", id, trigger, base);
            all = false;
        }

        // canonical target -> criteria it awards (criterion names stay the raw targets, as on Paper, so progress loads)
        Map<String, List<String>> criteriaFor = new LinkedHashMap<>();
        List<String> criteria = new ArrayList<>();
        Yaml groups = s.getConfigurationSection("groups");
        if (all && groups != null) {
            for (String group : groups.getKeys(false)) {
                List<String> members = expand(id, base, groups.getStringList(group), itemIds, unknownItems, unknownMobs);
                if (members.isEmpty()) {
                    plugin.getLogger().warn("advancements.yml: '{}': group '{}' is empty; left out.", id, group);
                    continue;
                }
                criteria.add(group);
                for (String m : members) criteriaFor.computeIfAbsent(canon(base, m), k -> new ArrayList<>()).add(group);
            }
        } else if (all) {
            for (String t : expand(id, base, s.getStringList("targets"), itemIds, unknownItems, unknownMobs)) {
                String c = canon(base, t);
                if (criteriaFor.containsKey(c)) continue;
                criteria.add(t);
                criteriaFor.put(c, List.of(t));
            }
        } else {
            criteria.add("done");
            if (TARGETED.contains(base)) {
                for (String t : expand(id, base, s.getStringList("targets"), itemIds, unknownItems, unknownMobs)) {
                    criteriaFor.put(canon(base, t), List.of("done"));
                }
                if (criteriaFor.isEmpty()) plugin.getLogger().warn("advancements.yml: '{}' has no targets; it can't be earned.", id);
            }
        }
        if (criteria.isEmpty()) {
            plugin.getLogger().warn("advancements.yml: '{}' has nothing to collect (no targets/groups left); skipped.", id);
            return null;
        }

        String parent = s.getString("parent");
        if (parent == null && s.getString("background") == null && !id.endsWith("/root")) {
            plugin.getLogger().warn("advancements.yml: '{}' has no parent; it will show as its own tab.", id);
        }
        String frameName = s.getString("frame", "task").toLowerCase(Locale.ROOT);
        if (!FRAMES.contains(frameName)) {
            plugin.getLogger().warn("advancements.yml: '{}': frame must be task, goal or challenge (not '{}').", id, frameName);
            frameName = "task";
        }
        AdvancementType frame = switch (frameName) {
            case "goal" -> AdvancementType.GOAL;
            case "challenge" -> AdvancementType.CHALLENGE;
            default -> AdvancementType.TASK;
        };
        Optional<ClientAsset.ResourceTexture> background = Optional.empty();
        String bg = s.getString("background");
        if (bg != null && parent == null) {
            Identifier bgId = Identifier.tryParse(bg);
            if (bgId != null) background = Optional.of(new ClientAsset.ResourceTexture(bgId));
            else plugin.getLogger().warn("advancements.yml: '{}': background '{}' is not a valid id.", id, bg);
        }
        DisplayInfo display = new DisplayInfo(icon(id, s.getString("icon", "paper")),
                Adv.vanilla(plugin.text().parse(s.getString("title", id))),
                Adv.vanilla(plugin.text().parse(s.getString("description", ""))),
                background, frame, s.getBoolean("toast", true), announceToChat && s.getBoolean("announce", true),
                s.getBoolean("hidden", false));
        Advancement.Builder b = Advancement.Builder.advancement().display(display);
        if (parent != null) b.parent(Identifier.fromNamespaceAndPath(NAMESPACE, parent));
        for (String c : criteria) b.addCriterion(c, CriteriaTriggers.IMPOSSIBLE.createCriterion(new ImpossibleTrigger.TriggerInstance()));
        b.requirements(all ? AdvancementRequirements.allOf(criteria) : AdvancementRequirements.allOf(List.of("done")));
        return new Entry(key, id, parent, base, s.getDouble("value", 1), criteriaFor, b.build(key));
    }

    /** Targets with globs expanded (item ids), noting ids and mob types the mod doesn't have. */
    private List<String> expand(String id, String base, List<String> targets, Set<String> itemIds, List<String> unknownItems,
                                List<String> unknownMobs) {
        List<String> out = new ArrayList<>();
        boolean items = ITEM_TRIGGERS.contains(base);
        boolean checkItems = !itemIds.isEmpty();
        for (String t : targets) {
            if (t == null || t.isBlank()) continue;
            t = t.trim();
            if (items) t = stripItemPrefix(t);
            if (items && t.regionMatches(true, 0, FURNITURE, 0, FURNITURE.length())) {
                List<String> hits = furniture(t.substring(FURNITURE.length()));
                if (hits.isEmpty()) unknownItems.add(t);
                out.addAll(hits);
                continue;
            }
            if (items && t.contains("*")) {
                Pattern p = glob(t);
                List<String> hits = itemIds.stream().filter(i -> p.matcher(i).matches()).sorted().toList();
                if (hits.isEmpty()) unknownItems.add(t);
                out.addAll(hits);
                continue;
            }
            if (items && checkItems && !itemIds.contains(t.toLowerCase(Locale.ROOT))) unknownItems.add(t);
            if (MOB_TRIGGERS.contains(base) && hasArcanaMobs()) {
                Identifier mob = Identifier.tryParse(mobKey(t));
                if (mob == null || BuiltInRegistries.ENTITY_TYPE.getOptional(mob).isEmpty()) unknownMobs.add(t);
            }
            out.add(t);
        }
        return out;
    }

    private static final String FURNITURE = "furniture:";

    /** A glob ({@code *} = anything) as a whole-string, lower-case pattern. */
    private static Pattern glob(String g) {
        return Pattern.compile(("\\Q" + g.toLowerCase(Locale.ROOT) + "\\E").replace("*", "\\E.*\\Q"));
    }

    /**
     * The content selector {@code furniture:<tab>[!<x>...]}: the ids of every visible furniture piece (lit hearths
     * too) whose creative tab matches {@code <tab>} (a glob; "misc" for pieces without one, as in the creative menu),
     * minus those whose tab or id matches any {@code <x>}. Sorted, so logs and criteria are stable.
     */
    static List<String> furniture(String selector) {
        String[] parts = selector.trim().split("!");
        Pattern tab = glob(parts[0].isBlank() ? "*" : parts[0].trim());
        List<Pattern> not = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) if (!parts[i].isBlank()) not.add(glob(parts[i].trim()));
        List<String> out = new ArrayList<>();
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.item() == null || c.hidden() || !(c.block() instanceof FurnitureBlock)) continue;
            String key = c.tab() == null ? "misc" : c.tab();
            if (!tab.matcher(key).matches()) continue;
            boolean left = false;
            for (Pattern p : not) left |= p.matcher(key).matches() || p.matcher(c.id()).matches();
            if (!left) out.add(c.id());
        }
        out.sort(null);
        return out;
    }

    /** Paths of every {@code aftrhrs:} item (the ex-Oraxen ids). */
    private static Set<String> nativeItemIds() {
        Set<String> out = new HashSet<>();
        for (Identifier k : BuiltInRegistries.ITEM.keySet()) if (k.getNamespace().equals(AftrHrs.ID)) out.add(k.getPath());
        return out;
    }

    private static boolean hasArcanaMobs() {
        for (Identifier k : BuiltInRegistries.ENTITY_TYPE.keySet()) if (k.getNamespace().equals(AftrHrs.ID)) return true;
        return false;
    }

    private static String stripItemPrefix(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        if (l.startsWith("oraxen:")) return t.substring(7);
        if (l.startsWith(AftrHrs.ID + ":")) return t.substring(AftrHrs.ID.length() + 1);
        return t;
    }

    /** The key a target is matched by: the item id (path) for item triggers, the entity id for mobs, else lower case. */
    static String canon(String trigger, String target) {
        String t = target.trim();
        if (ITEM_TRIGGERS.contains(trigger)) return stripItemPrefix(t).toLowerCase(Locale.ROOT);
        if (MOB_TRIGGERS.contains(trigger)) return mobKey(t);
        return t.toLowerCase(Locale.ROOT);
    }

    /**
     * A mob's entity id: {@code ArcanaBlackCat} (MythicMobs) → {@code aftrhrs:black_cat} (explicit mapping or the
     * naming convention, registered or not), {@code zombie} → {@code minecraft:zombie}, ids as they are.
     */
    static String mobKey(String t) {
        if (t.indexOf(':') >= 0) return t.toLowerCase(Locale.ROOT);
        var mapped = Legacy.mythicType(t);
        if (mapped.isPresent()) return BuiltInRegistries.ENTITY_TYPE.getKey(mapped.get()).toString();
        if (t.startsWith("Arcana") && t.length() > 6) {
            return AftrHrs.ID + ":" + t.substring(6).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }
        return "minecraft:" + t.toLowerCase(Locale.ROOT);
    }

    private ItemStackTemplate icon(String id, String icon) {
        String l = icon.toLowerCase(Locale.ROOT);
        if (l.startsWith("oraxen:")) {
            Item item = BuiltInRegistries.ITEM.getOptional(AftrHrs.id(l.substring(7))).orElse(null);
            return new ItemStackTemplate(item == null || item == Items.AIR ? fallbackIcon : item);
        }
        Item item = item(icon);
        if (item == null) {
            plugin.getLogger().warn("advancements.yml: '{}': unknown icon item '{}'.", id, icon);
            return new ItemStackTemplate(fallbackIcon);
        }
        return new ItemStackTemplate(item);
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id.toLowerCase(Locale.ROOT));
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    // ------------------------------------------------------------------ registering

    private void sync() {
        MinecraftServer server = Feather.serverOrNull();
        List<AdvancementHolder> list = new ArrayList<>();
        for (Entry e : entries.values()) list.add(e.holder());
        AdvancementInjector.Extra.list = List.copyOf(list);
        if (server == null) return;
        ServerAdvancementManager manager = server.getAdvancements();
        Map<Identifier, AdvancementHolder> before = new HashMap<>();
        for (AdvancementHolder h : manager.getAllAdvancements()) if (h.id().getNamespace().equals(NAMESPACE)) before.put(h.id(), h);
        int unchanged = 0;
        for (AdvancementHolder h : list) if (h.equals(before.get(h.id())) && h.value().equals(before.get(h.id()).value())) unchanged++;
        int removed = (int) before.keySet().stream().filter(k -> !entries.containsKey(k)).count();
        boolean changed = unchanged != list.size() || removed > 0;
        if (changed) {
            List<ServerPlayer> online = plugin.onlinePlayers();
            // Reloading a player's advancements reads them from disk: save first so no progress is lost.
            for (ServerPlayer p : online) p.getAdvancements().save();
            ((AdvancementInjector) manager).aftrhrs$replace(NAMESPACE, list);
            for (ServerPlayer p : online) p.getAdvancements().reload(manager);
        }
        plugin.getLogger().info("Advancements: {} in {} tabs ({} loaded, {} unchanged{}).", list.size(), countRoots(),
                changed ? list.size() - unchanged : 0, unchanged, removed > 0 ? ", " + removed + " removed" : "");
    }

    private long countRoots() {
        return entries.values().stream().filter(e -> e.parent() == null).count();
    }

    // ------------------------------------------------------------------ awarding

    /** {@link Feather#advance}: trigger "event"/"tame"/"kill"/"obtain"/"wear"/"place" with its target. */
    private void report(ServerPlayer player, String trigger, String target) {
        if (target == null || trigger == null || player.hasDisconnected()) return;
        MinecraftServer server = Feather.serverOrNull();
        if (server != null && !server.isSameThread()) {
            Tasks.sync(() -> report(player, trigger, target));
            return;
        }
        String base = trigger.toLowerCase(Locale.ROOT);
        List<Entry> list = byTrigger.get(base);
        if (list == null) return;
        String t = canon(base, target);
        for (Entry e : list) {
            List<String> criteria = e.criteriaFor().get(t);
            if (criteria != null) for (String c : criteria) award(player, e, c);
        }
    }

    private void award(ServerPlayer player, Entry e, String criterion) {
        AdvancementHolder h = live(e);
        if (h == null) return;
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(h);
        if (progress.isDone()) return;
        for (String c : progress.getRemainingCriteria()) {
            if (c.equals(criterion)) {
                player.getAdvancements().award(h, criterion);
                return;
            }
        }
    }

    /** The holder the server actually has (null if it isn't in the list, e.g. a datapack reload is underway). */
    private static AdvancementHolder live(Entry e) {
        MinecraftServer server = Feather.serverOrNull();
        return server == null ? null : server.getAdvancements().get(e.key());
    }

    private boolean done(ServerPlayer player, Entry e) {
        AdvancementHolder h = live(e);
        return h == null || player.getAdvancements().getOrStartProgress(h).isDone();
    }

    private void awardJoin(ServerPlayer p) {
        for (Entry e : byTrigger.getOrDefault("join", List.of())) award(p, e, "done");
    }

    /** Items, helmets, playtime and pets, for every online player. */
    private void scan() {
        if (entries.isEmpty()) return;
        damagers.keySet().removeIf(Entity::isRemoved);
        List<Entry> obtain = byTrigger.getOrDefault("obtain", List.of());
        List<Entry> wear = byTrigger.getOrDefault("wear", List.of());
        List<Entry> playtime = byTrigger.getOrDefault("playtime", List.of());
        List<Entry> petsCount = byTrigger.getOrDefault("pets-count", List.of());
        List<Entry> petLevel = byTrigger.getOrDefault("pet-level", List.of());
        Hooks.PetProgress pets = plugin.service(Hooks.PetProgress.class);
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!obtain.isEmpty()) {
                Set<String> ids = new HashSet<>();
                // Includes armor and the offhand.
                Inventory inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    String id = itemKey(inv.getItem(i));
                    if (id != null) ids.add(id);
                }
                String cursor = itemKey(p.containerMenu.getCarried());
                if (cursor != null) ids.add(cursor);
                if (!ids.isEmpty()) matchAll(p, obtain, ids);
            }
            if (!wear.isEmpty()) {
                String head = itemKey(p.getItemBySlot(EquipmentSlot.HEAD));
                if (head != null) matchAll(p, wear, Set.of(head));
            }
            if (!playtime.isEmpty()) {
                double hours = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 72000.0;
                for (Entry e : playtime) if (hours >= e.value()) award(p, e, "done");
            }
            if (pets != null && !petsCount.isEmpty()) {
                int n = pets.countActive(p.getUUID());
                for (Entry e : petsCount) if (n >= e.value()) award(p, e, "done");
            }
            if (pets != null && !petLevel.isEmpty()) {
                int lvl = pets.topBondLevel(p.getUUID());
                for (Entry e : petLevel) if (lvl >= e.value()) award(p, e, "done");
            }
        }
    }

    private void matchAll(ServerPlayer p, List<Entry> list, Set<String> ids) {
        for (Entry e : list) {
            if (done(p, e)) continue;
            for (String id : ids) {
                List<String> criteria = e.criteriaFor().get(id);
                if (criteria != null) for (String c : criteria) award(p, e, c);
            }
        }
    }

    /**
     * An item's advancement id: the path of an {@code aftrhrs:} item (the old Oraxen id), or the Oraxen id of a Paper
     * item that hasn't been converted; null for anything else (vanilla items never counted).
     */
    static String itemKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key.getNamespace().equals(AftrHrs.ID)) return key.getPath();
        String legacy = Pdc.legacyItemString(stack, "oraxen:id");
        return legacy == null ? null : legacy.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ kills

    /** Whether any kill advancement wants this entity id. */
    private boolean wantsKill(String mobKey) {
        for (Entry e : byTrigger.getOrDefault("kill", List.of())) if (e.criteriaFor().containsKey(mobKey)) return true;
        return false;
    }

    private static String typeKey(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private void hurt(LivingEntity victim, DamageSource source) {
        if (victim instanceof ServerPlayer || !wantsKill(typeKey(victim))) return;
        ServerPlayer attacker = attacker(source);
        if (attacker != null) damagers.computeIfAbsent(victim, k -> new LinkedHashSet<>()).add(attacker.getUUID());
    }

    private void died(LivingEntity victim, DamageSource source) {
        Set<UUID> who = damagers.remove(victim);
        if (victim instanceof ServerPlayer) return;
        String type = typeKey(victim);
        if (!wantsKill(type)) return;
        Set<UUID> all = new LinkedHashSet<>();
        if (who != null) all.addAll(who);
        ServerPlayer killer = attacker(source);
        if (killer != null) all.add(killer.getUUID());
        for (UUID id : all) {
            ServerPlayer p = plugin.player(id);
            if (p != null) plugin.advance(p, "kill", type);
        }
    }

    /** The player behind a hit: directly, by projectile, by their TNT, or by their tamed pet. */
    private ServerPlayer attacker(DamageSource source) {
        ServerPlayer p = FeatherEvents.attacker(source);
        if (p != null) return p;
        Entity direct = source.getDirectEntity();
        Entity cause = source.getEntity();
        if (direct instanceof PrimedTnt tnt && tnt.getOwner() instanceof ServerPlayer owner) return owner;
        for (Entity e : new Entity[]{cause, direct}) {
            if (e instanceof OwnableEntity own && own.getOwnerReference() != null) {
                ServerPlayer owner = plugin.player(own.getOwnerReference().getUUID());
                if (owner != null) return owner;
            }
        }
        return null;
    }

    /** For tests and other code: the advancement ids this module added. */
    public Collection<Identifier> ids() {
        return entries.keySet();
    }

    /** For tests: the canonical targets (item ids, entity ids, events) that award advancement {@code feather:<id>}. */
    public Set<String> targets(String id) {
        Entry e = entries.get(Identifier.fromNamespaceAndPath(NAMESPACE, id));
        return e == null ? Set.of() : java.util.Collections.unmodifiableSet(e.criteriaFor().keySet());
    }
}
