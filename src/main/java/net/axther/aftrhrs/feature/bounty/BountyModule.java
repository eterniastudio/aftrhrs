package net.axther.aftrhrs.feature.bounty;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.trade.Stacks;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bounties: {@code /bounty <player>} opens a chest where the placer puts reward items (whitelisted, e.g. diamonds and
 * netherite) and confirms. Feather holds the items ({@code data/bounties.yml}, Paper's item bytes, so the live file
 * loads as it is); several players can stack rewards on the same target. The tab list shows {@code <bounty>} next to
 * bountied players ({@link Hooks.BountyTag}).
 *
 * <p>When another player kills the target, the victim drops a <em>bounty head</em>. The reward stays on the server
 * under a claim id kept in the head's {@code custom_data} ({@code feather:bounty_claim}, the old PDC key), so copying
 * the item copies nothing: the first right-click consumes the claim and pays out, any copy finds the claim gone.
 * Unclaimed contributions expire after a while and go back to their placers (offline ones: through the mail when the
 * mail module is on, else into the bounty mailbox, handed out on join).
 */
public final class BountyModule implements FeatherModule, FeatherCommand {
    static final String CLAIM_KEY = "feather:bounty_claim";
    private static final String ADMIN = "feather.bounty.admin";

    static final class Contribution {
        UUID placer;
        String placerName;
        long placed;
        List<ItemStack> items = new ArrayList<>();
    }

    static final class Bounty {
        UUID target;
        String targetName;
        final List<Contribution> parts = new ArrayList<>();
        /** The tab-list tag, rebuilt when the reward changes. */
        volatile Component tag;
    }

    static final class Claim {
        String id;
        UUID victim;
        String victimName;
        UUID killer;
        String killerName;
        long created;
        List<ItemStack> items = new ArrayList<>();
        List<UUID> placers = new ArrayList<>();
    }

    /** The reward chest: the reward slots come first, the bar is the last row. */
    private static final class Editor {
        final UUID placer;
        final UUID target;
        final String targetName;
        final int rewardSlots;
        final int barStart;
        Gui gui;
        boolean done;

        Editor(UUID placer, UUID target, String targetName, int rewardSlots, int barStart) {
            this.placer = placer;
            this.target = target;
            this.targetName = targetName;
            this.rewardSlots = rewardSlots;
            this.barStart = barStart;
        }

        int cancelSlot() {
            return barStart + 2;
        }

        int infoSlot() {
            return barStart + 4;
        }

        int confirmSlot() {
            return barStart + 6;
        }
    }

    private static volatile BountyModule active;

    private final Feather plugin;
    private final Path file;
    /** Read by {@link #tag} from display code that may run off the main thread; changed on the main thread only. */
    private final Map<UUID, Bounty> bounties = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> mailbox = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<String, Long> recentKills = new HashMap<>();
    /** Open reward chests, by placer. */
    private final Map<UUID, Editor> editors = new HashMap<>();
    private ExecutorService io;
    private Tasks.Task expiryTask;
    /** bounties.yml could not be read: bounties are off and the file is never saved over. */
    private boolean unreadable;
    // config
    private final Map<Item, Double> values = new LinkedHashMap<>();
    private double minValue;
    private int maxStacks;
    private long cooldownMs;
    private long expiryMs;
    private boolean announcePlace;
    private boolean announceKill;
    private boolean announceClaim;
    private long sameKillerMs;
    private boolean sameIp;
    private boolean offlineTargets;
    private String tabTag;
    private int summaryMax;
    private String mailFrom;

    public BountyModule(Feather plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("bounties.yml");
    }

    @Override
    public String name() {
        return "Bounties";
    }

    @Override
    public void enable() {
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Feather-Bounties");
            t.setDaemon(true);
            return t;
        });
        reload();
        load();
        active = this;
        plugin.bind("bounty", this);
        plugin.provide(Hooks.BountyTag.class, this::tag);
        plugin.hub().add("bounties", Hub.Group.COMMUNITY, Hub.ORDER_BOUNTIES, "hub-social", "feather.bounty", this::openList);
        expiryTask = Tasks.timer(200L, 1200L, this::expire);
        plugin.once("bounty.events", () -> {
            FeatherEvents.PLAYER_DEATH.register((p, source) -> {
                BountyModule m = active;
                if (m == null) return;
                m.editorDied(p);
                m.onDeath(p, source);
            });
            FeatherEvents.PLAYER_RESPAWN.register((old, now, alive) -> {
                BountyModule m = active;
                if (m != null && m.mailbox.containsKey(now.getUUID())) m.deliverMailbox(now.getUUID(), 5L);
            });
            ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
                BountyModule m = active;
                if (m != null) m.onJoin(h.player);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
                BountyModule m = active;
                if (m != null) m.onQuit(h.player);
            });
            // Right-clicking a bounty head claims it (never places it). The client side stops placement predictions too.
            UseItemCallback.EVENT.register((player, level, hand) -> useHead(player, hand));
            UseBlockCallback.EVENT.register((player, level, hand, hit) -> useHead(player, hand));
            FeatherEvents.BLOCK_PLACE.register((p, level, pos, state, stack) -> claimId(stack) == null);
        });
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        values.clear();
        Yaml rewards = c.getConfigurationSection("bounty.rewards");
        if (rewards == null || rewards.getKeys(false).isEmpty()) rewards = Feather.bundled("config.yml").getConfigurationSection("bounty.rewards");
        if (rewards != null) {
            for (String key : rewards.getKeys(false)) {
                Item item = itemOf(key);
                if (item == null) {
                    plugin.getLogger().warn("config.yml bounty.rewards: unknown item {}", key);
                    continue;
                }
                values.put(item, Math.max(0, rewards.getDouble(key, 1)));
            }
        }
        minValue = Math.max(0, c.getDouble("bounty.min-value", 1));
        maxStacks = Math.max(1, Math.min(45, c.getInt("bounty.max-stacks", 18)));
        cooldownMs = Math.max(0, c.getLong("bounty.cooldown-seconds", 60)) * 1000L;
        expiryMs = Math.max(0, c.getLong("bounty.expire-hours", 168)) * 3_600_000L;
        announcePlace = c.getBoolean("bounty.announce.placed", true);
        announceKill = c.getBoolean("bounty.announce.killed", true);
        announceClaim = c.getBoolean("bounty.announce.claimed", true);
        sameKillerMs = Math.max(0, c.getLong("bounty.anti-farm.same-killer-minutes", 30)) * 60_000L;
        sameIp = c.getBoolean("bounty.anti-farm.same-ip", false);
        offlineTargets = c.getBoolean("bounty.offline-targets", true);
        tabTag = c.getString("bounty.tab-tag", " <#ff6b81>☠ <reward></#ff6b81>");
        summaryMax = Math.max(1, c.getInt("bounty.tab-summary-items", 1));
        mailFrom = c.getString("bounty.mail-from", "Bounty Board");
        for (Bounty b : bounties.values()) b.tag = null;
    }

    @Override
    public void disable() {
        if (expiryTask != null) expiryTask.cancel();
        // Anyone still filling a reward chest gets the items back (into the mailbox if their inventory is full).
        for (Editor ed : new ArrayList<>(editors.values())) {
            if (ed.done) continue;
            ed.done = true;
            List<ItemStack> items = takeRewards(ed);
            ServerPlayer p = plugin.player(ed.placer);
            if (p != null) {
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack it : items) {
                    ItemStack copy = it.copy();
                    p.getInventory().add(copy);   // leaves what didn't fit in the stack
                    if (!copy.isEmpty()) left.add(copy);
                }
                if (!left.isEmpty()) mailbox.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).addAll(left);
                p.closeContainer();
            } else if (!items.isEmpty()) {
                mailbox.computeIfAbsent(ed.placer, k -> new ArrayList<>()).addAll(items);
            }
        }
        editors.clear();
        save();
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warn("Bounty data write timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (active == this) active = null;
    }

    private static Item itemOf(String key) {
        Identifier id = Identifier.tryParse(key.toLowerCase(Locale.ROOT).contains(":") ? key.toLowerCase(Locale.ROOT) : "minecraft:" + key.toLowerCase(Locale.ROOT));
        if (id == null) return null;
        return BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).orElse(null);
    }

    private Text text() {
        return plugin.text();
    }

    // ------------------------------------------------------------------ rewards and summaries

    private boolean allowed(ItemStack item) {
        if (Stacks.empty(item)) return true;
        // Plain items only: no renamed, enchanted or custom items dressed up as diamonds.
        return values.containsKey(item.getItem()) && item.getComponentsPatch().isEmpty();
    }

    double value(List<ItemStack> items) {
        double v = 0;
        for (ItemStack s : items) if (!Stacks.empty(s)) v += values.getOrDefault(s.getItem(), 0.0) * s.getCount();
        return v;
    }

    /** "netherite_scrap" -> "Netherite Scrap" (AnalyticsModule.pretty). */
    static String pretty(String key) {
        StringBuilder b = new StringBuilder();
        for (String w : key.replace('-', '_').split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static String itemName(Item item, int count) {
        String n = pretty(BuiltInRegistries.ITEM.getKey(item).getPath());
        if (count == 1 || n.endsWith("Scrap") || n.endsWith("s")) return n;
        return n + "s";
    }

    /** Counts per item type, most valuable first. */
    private Map<Item, Integer> counts(List<ItemStack> items) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack s : items) if (!Stacks.empty(s)) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Double.compare(values.getOrDefault(b.getKey(), 0.0) * b.getValue(), values.getOrDefault(a.getKey(), 0.0) * a.getValue()));
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : list) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** "12 Diamonds, 2 Netherite Ingots" (all of it) or the first {@code max} types plus "+N". */
    String summary(List<ItemStack> items, int max) {
        Map<Item, Integer> counts = counts(items);
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (i++ >= max) break;
            parts.add(e.getValue() + " " + itemName(e.getKey(), e.getValue()));
        }
        String s = String.join(", ", parts);
        if (counts.size() > max) s += " +" + (counts.size() - max);
        return s;
    }

    private List<ItemStack> allItems(Bounty b) {
        List<ItemStack> all = new ArrayList<>();
        for (Contribution c : b.parts) all.addAll(c.items);
        return all;
    }

    /** The tab-list tag for {@code <bounty>}: empty unless the player has an active bounty. */
    Component tag(ServerPlayer p) {
        Bounty b = bounties.get(p.getUUID());
        if (b == null) return Component.empty();
        Component t = b.tag;
        if (t == null) {
            try {
                List<ItemStack> items = allItems(b);
                t = text().parse(tabTag, Text.unparsed("reward", summary(items, summaryMax)), Text.unparsed("value", fmt(value(items))));
            } catch (RuntimeException e) {
                return Component.empty();   // changed mid-read by the main thread; the next refresh gets it
            }
            b.tag = t;
        }
        return t;
    }

    public boolean hasBounty(UUID id) {
        return bounties.containsKey(id);
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }

    private void broadcast(Component msg, ServerPlayer... except) {
        Set<ServerPlayer> skip = new java.util.HashSet<>(java.util.Arrays.asList(except));
        for (ServerPlayer o : plugin.onlinePlayers()) if (!skip.contains(o)) Adv.of(o).sendMessage(msg);
        Feather.server().sendSystemMessage(Adv.vanilla(msg));
    }

    // ------------------------------------------------------------------ placing

    private void openEditor(ServerPlayer placer, UUID target, String targetName) {
        int rows = (maxStacks + 8) / 9;
        Editor ed = new Editor(placer.getUUID(), target, targetName, maxStacks, rows * 9);
        Gui gui = Gui.create(rows + 1, text().msg("bounty.gui.title", Text.unparsed("target", targetName)));
        ed.gui = gui;
        gui.attach(ed);
        for (int s = 0; s < maxStacks; s++) gui.unlock(s);
        ItemStack filler = Icons.icon(Items.STAINED_GLASS_PANE.black(), null, null);
        for (int s = maxStacks; s < gui.size(); s++) gui.set(s, filler.copy());
        gui.set(ed.cancelSlot(), Icons.icon(Items.STAINED_GLASS_PANE.red(), "bounty.gui.cancel", "bounty.gui.cancel-lore"), c -> {
            text().sound(c.player(), "click");
            c.player().closeContainer();
        });
        gui.onChange(() -> changed(ed));
        gui.onClose(p -> closed(ed, p));
        refresh(ed);
        Editor old = editors.put(placer.getUUID(), ed);
        if (old != null && !old.done) old.gui.close();
        editors.put(placer.getUUID(), ed);
        gui.open(placer);
        text().sound(placer, "menu-open");
    }

    /** Reward slots changed: anything that may not be a reward goes back to the placer; then the bar is redrawn. */
    private void changed(Editor ed) {
        if (ed.done) return;
        ServerPlayer p = plugin.player(ed.placer);
        boolean refused = false;
        for (int s = 0; s < ed.rewardSlots; s++) {
            ItemStack it = ed.gui.container().getItem(s);
            if (allowed(it)) continue;
            ed.gui.container().setItem(s, ItemStack.EMPTY);
            refused = true;
            if (p != null) Stacks.give(p, List.of(it));
            else mailbox.computeIfAbsent(ed.placer, k -> new ArrayList<>()).add(it);
        }
        if (refused && p != null) {
            text().send(p, "bounty.not-allowed", Text.unparsed("allowed", allowedList()));
            text().sound(p, "error");
        }
        refresh(ed);
    }

    /** Updates the info head and the confirm button with what is in the chest now. */
    private void refresh(Editor ed) {
        if (ed.done) return;
        List<ItemStack> items = rewards(ed);
        double v = value(items);
        Bounty existing = bounties.get(ed.target);
        TagResolver[] r = {
                Text.unparsed("target", ed.targetName),
                Text.unparsed("value", fmt(v)),
                Text.unparsed("min", fmt(minValue)),
                Text.unparsed("reward", items.isEmpty() ? "-" : summary(items, 3)),
                Text.unparsed("current", existing == null ? "-" : summary(allItems(existing), 3)),
                Text.unparsed("allowed", allowedList())};
        ItemStack head = Icons.icon(Icons.head(ed.target, plugin.player(ed.target))).flags()
                .name(text().itemMsg("bounty.gui.info", r)).lore(text().lines("bounty.gui.info-lore", r)).build();
        ed.gui.set(ed.infoSlot(), head);
        boolean ok = !items.isEmpty() && v >= minValue;
        ed.gui.set(ed.confirmSlot(), Icons.icon(ok ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.gray(),
                ok ? "bounty.gui.confirm" : "bounty.gui.confirm-locked", ok ? "bounty.gui.confirm-lore" : "bounty.gui.confirm-locked-lore", r), c -> {
            text().sound(c.player(), "click");
            confirm(c.player(), ed);
        });
        ed.gui.refresh();
    }

    private String allowedList() {
        List<String> names = new ArrayList<>();
        for (Item i : values.keySet()) names.add(pretty(BuiltInRegistries.ITEM.getKey(i).getPath()));
        return String.join(", ", names);
    }

    private List<ItemStack> rewards(Editor ed) {
        List<ItemStack> out = new ArrayList<>();
        for (int s = 0; s < ed.rewardSlots; s++) {
            ItemStack it = ed.gui.container().getItem(s);
            if (!it.isEmpty()) out.add(it.copy());
        }
        return out;
    }

    /** Empties the reward slots and returns what was in them. */
    private List<ItemStack> takeRewards(Editor ed) {
        List<ItemStack> out = rewards(ed);
        for (int s = 0; s < ed.rewardSlots; s++) ed.gui.container().setItem(s, ItemStack.EMPTY);
        return out;
    }

    private void closed(Editor ed, ServerPlayer p) {
        editors.remove(ed.placer, ed);
        if (ed.done) return;
        ed.done = true;
        List<ItemStack> items = takeRewards(ed);
        if (items.isEmpty()) return;
        // Respawning closes the old (dead) player's menu: its inventory is thrown away, so keep the items here.
        if (p.hasDisconnected() || p.isRemoved() || p.isDeadOrDying()) {
            mailbox.computeIfAbsent(ed.placer, k -> new ArrayList<>()).addAll(items);
            save();
            return;
        }
        Stacks.give(p, items);
        text().send(p, "bounty.cancelled");
    }

    private void confirm(ServerPlayer p, Editor ed) {
        if (ed.done || unreadable) return;
        List<ItemStack> items = rewards(ed);
        if (items.isEmpty()) {
            text().send(p, "bounty.empty");
            text().sound(p, "error");
            return;
        }
        for (ItemStack it : items) {
            if (!allowed(it)) {
                text().send(p, "bounty.not-allowed", Text.unparsed("allowed", allowedList()));
                text().sound(p, "error");
                return;
            }
        }
        double v = value(items);
        if (v < minValue) {
            text().send(p, "bounty.too-small", Text.unparsed("value", fmt(v)), Text.unparsed("min", fmt(minValue)));
            text().sound(p, "error");
            return;
        }
        ed.done = true;
        takeRewards(ed);
        p.closeContainer();
        Bounty b = bounties.get(ed.target);
        if (b == null) {
            b = new Bounty();
            b.target = ed.target;
            bounties.put(ed.target, b);
        }
        b.targetName = ed.targetName;
        Contribution c = new Contribution();
        c.placer = p.getUUID();
        c.placerName = Names.name(p);
        c.placed = System.currentTimeMillis();
        c.items = items;
        b.parts.add(c);
        b.tag = null;
        if (!net.axther.aftrhrs.core.Perms.has(p, ADMIN)) cooldowns.put(p.getUUID(), System.currentTimeMillis());
        save();
        TagResolver[] r = {Text.unparsed("target", ed.targetName), Text.component("placer", Names.display(p)),
                Text.unparsed("reward", summary(items, 4)), Text.unparsed("total", summary(allItems(b), 4))};
        text().send(p, "bounty.placed", r);
        text().sound(p, "accepted");
        ServerPlayer target = plugin.player(ed.target);
        if (target != null) {
            text().send(target, "bounty.placed-on-you", r);
            text().sound(target, "request-received");
        }
        if (announcePlace) broadcast(text().msg("bounty.announce-placed", r), p, target);
        plugin.stat(p.getUUID(), "bounties-placed", 1);
        plugin.advance(p, "event", "bounty-place");
    }

    // ------------------------------------------------------------------ claiming

    private void onDeath(ServerPlayer victim, net.minecraft.world.damagesource.DamageSource source) {
        ServerPlayer killer = FeatherEvents.attacker(source);
        if (killer == null && victim.getLastHurtByPlayer() instanceof ServerPlayer sp) killer = sp;
        if (killer == null || killer == victim) return;
        Bounty b = bounties.get(victim.getUUID());
        if (b == null || b.parts.isEmpty()) return;
        UUID killerId = killer.getUUID();
        boolean onlyOwn = b.parts.stream().allMatch(c -> c.placer.equals(killerId));
        if (onlyOwn) {
            text().send(killer, "bounty.own-bounty");
            return;
        }
        long now = System.currentTimeMillis();
        String pair = killerId + ">" + victim.getUUID();
        Long last = recentKills.get(pair);
        if (sameKillerMs > 0 && last != null && now - last < sameKillerMs) {
            text().send(killer, "bounty.too-soon", Text.unparsed("target", Names.name(victim)));
            return;
        }
        if (sameIp && killer.getIpAddress().equals(victim.getIpAddress())) {
            text().send(killer, "bounty.same-address");
            return;
        }
        recentKills.put(pair, now);
        recentKills.values().removeIf(t -> now - t > Math.max(sameKillerMs, 60_000L));

        Claim c = new Claim();
        c.id = UUID.randomUUID().toString();
        c.victim = victim.getUUID();
        c.victimName = Names.name(victim);
        c.killer = killerId;
        c.killerName = Names.name(killer);
        c.created = now;
        c.items = allItems(b);
        for (Contribution part : b.parts) if (!c.placers.contains(part.placer)) c.placers.add(part.placer);
        claims.put(c.id, c);
        bounties.remove(victim.getUUID());
        save();

        ItemStack head = head(c);
        ServerLevel level = victim.level();
        if (victim.getY() < level.getMinY() + 1) {
            // Died in the void: the head would fall forever, so the killer gets it straight away.
            Stacks.give(killer, List.of(head));
        } else {
            ItemEntity drop = new ItemEntity(level, victim.getX(), victim.getY(), victim.getZ(), head);
            drop.setUnlimitedLifetime();
            drop.setPermanentlyInvulnerable(true);
            drop.setGlowingTag(true);
            level.addFreshEntity(drop);
        }
        TagResolver[] r = {Text.unparsed("target", c.victimName), Text.component("killer", Names.display(killer)),
                Text.unparsed("reward", summary(c.items, 4))};
        if (announceKill) broadcast(text().msg("bounty.announce-killed", r));
        else text().send(killer, "bounty.head-dropped", r);
    }

    /** The victim's head, named and with the reward in its lore; only the claim id is stored in it. */
    private ItemStack head(Claim c) {
        TagResolver[] r = {Text.unparsed("target", c.victimName), Text.unparsed("killer", c.killerName)};
        List<Component> lore = new ArrayList<>(text().lines("bounty.head.lore-top", r));
        for (Map.Entry<Item, Integer> e : counts(c.items).entrySet()) {
            lore.addAll(text().lines("bounty.head.lore-item", Text.unparsed("count", e.getValue()),
                    Text.unparsed("item", itemName(e.getKey(), e.getValue()))));
        }
        lore.addAll(text().lines("bounty.head.lore-bottom", r));
        ItemStack head = Icons.icon(Icons.head(c.victim, plugin.player(c.victim))).flags()
                .name(text().itemMsg("bounty.head.name", r)).lore(lore).glint(true).build();
        Pdc.itemPutString(head, CLAIM_KEY, c.id);
        return head;
    }

    static String claimId(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return null;
        return Pdc.itemString(item, CLAIM_KEY);
    }

    private static InteractionResult useHead(Player player, InteractionHand hand) {
        String id = claimId(player.getItemInHand(hand));
        if (id == null) return InteractionResult.PASS;
        if (player instanceof ServerPlayer sp) {
            BountyModule m = active;
            if (m != null) m.redeem(sp, hand, id);
        }
        return InteractionResult.SUCCESS;   // never place a bounty head
    }

    private void redeem(ServerPlayer p, InteractionHand hand, String id) {
        // Consume the head in hand first, whatever happens next.
        ItemStack inHand = p.getItemInHand(hand);
        if (id.equals(claimId(inHand))) inHand.shrink(1);
        Claim c = claims.remove(id);
        if (c == null) {
            text().send(p, "bounty.already-claimed");
            text().sound(p, "error");
            return;
        }
        save();
        Stacks.give(p, c.items);
        TagResolver[] r = {Text.unparsed("target", c.victimName), Text.component("claimer", Names.display(p)),
                Text.unparsed("reward", summary(c.items, 4))};
        text().send(p, "bounty.claimed", r);
        text().sound(p, "accepted");
        if (announceClaim) broadcast(text().msg("bounty.announce-claimed", r), p);
        plugin.stat(p.getUUID(), "bounties-claimed", 1);
        plugin.advance(p, "event", "bounty-claim");
    }

    // ------------------------------------------------------------------ refunds, expiry, mailbox

    /** Returns a contribution to its placer now, or through the mail / the bounty mailbox while they're away. */
    private void refund(Contribution c, String messageKey, String targetName) {
        ServerPlayer p = plugin.player(c.placer);
        TagResolver[] r = {Text.unparsed("target", targetName), Text.unparsed("reward", summary(c.items, 4))};
        if (p != null && !p.isDeadOrDying()) {
            Stacks.give(p, c.items);
            text().send(p, messageKey, r);
            return;
        }
        String note = PlainTextComponentSerializer.plainText().serialize(text().msg(messageKey, r));
        String prefix = PlainTextComponentSerializer.plainText().serialize(text().parse("<prefix>"));
        if (!prefix.isEmpty() && note.startsWith(prefix)) note = note.substring(prefix.length()).strip();
        List<ItemStack> copies = Stacks.copies(c.items);
        if (!plugin.mailItems(c.placer, mailFrom, note, copies)) mailbox.computeIfAbsent(c.placer, k -> new ArrayList<>()).addAll(c.items);
    }

    private void expire() {
        if (expiryMs <= 0 || unreadable) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (var it = bounties.values().iterator(); it.hasNext(); ) {
            Bounty b = it.next();
            for (var pi = b.parts.iterator(); pi.hasNext(); ) {
                Contribution c = pi.next();
                if (now - c.placed < expiryMs) continue;
                pi.remove();
                refund(c, "bounty.expired", b.targetName);
                changed = true;
                b.tag = null;
            }
            if (b.parts.isEmpty()) it.remove();
        }
        if (changed) save();
    }

    /**
     * The placer died with the reward chest open: vanilla only closes it on respawn, on the discarded player, so the
     * rewards (and the cursor) go into the inventory now and drop with the death loot (or stay, with keepInventory).
     */
    private void editorDied(ServerPlayer p) {
        Editor ed = editors.get(p.getUUID());
        if (ed == null || ed.done) return;
        ed.done = true;
        editors.remove(p.getUUID(), ed);
        List<ItemStack> items = takeRewards(ed);
        if (Gui.of(p) == ed.gui && !p.containerMenu.getCarried().isEmpty()) {
            items.add(p.containerMenu.getCarried().copy());
            p.containerMenu.setCarried(ItemStack.EMPTY);
        }
        for (ItemStack it : items) p.getInventory().placeItemBackInInventory(it, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    /** Vanilla closes menus on a disconnect only after the player file is saved: a reward chest left open keeps its items in the bounty mailbox. */
    private void onQuit(ServerPlayer p) {
        Editor ed = editors.remove(p.getUUID());
        if (ed == null || ed.done) return;
        ed.done = true;
        List<ItemStack> items = takeRewards(ed);
        if (p.containerMenu != null && Gui.of(p) == ed.gui && !p.containerMenu.getCarried().isEmpty()) {
            items.add(p.containerMenu.getCarried().copy());
            p.containerMenu.setCarried(ItemStack.EMPTY);
        }
        if (items.isEmpty()) return;
        mailbox.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).addAll(items);
        save();
    }

    private void onJoin(ServerPlayer p) {
        Bounty b = bounties.get(p.getUUID());
        if (b != null && !Names.name(p).equals(b.targetName)) {
            b.targetName = Names.name(p);
            b.tag = null;
        }
        if (!mailbox.containsKey(p.getUUID())) return;
        deliverMailbox(p.getUUID(), 40L);
    }

    /** Hands the bounty mailbox out; a player still on the death screen gets it after respawning instead. */
    private void deliverMailbox(UUID id, long delay) {
        Tasks.later(delay, () -> {
            ServerPlayer now = plugin.player(id);
            if (now == null || now.isDeadOrDying() || now.isRemoved()) return;
            List<ItemStack> items = mailbox.remove(id);
            if (items == null || items.isEmpty()) return;
            save();   // the file lets go first: a crash now can lose them, never give them twice
            Stacks.give(now, items);
            text().send(now, "bounty.mailbox", Text.unparsed("reward", summary(items, 4)));
        });
    }

    // ------------------------------------------------------------------ /bounty list

    public void openList(ServerPlayer viewer) {
        Dialogs.Menu menu = plugin.dialogs().menu(text().msg("bounty.list.title", Text.unparsed("count", bounties.size())));
        List<Bounty> list = new ArrayList<>(bounties.values());
        list.sort((a, b) -> Double.compare(value(allItems(b)), value(allItems(a))));
        if (list.isEmpty()) menu.text(text().msg("bounty.list.empty"), 300);
        long now = System.currentTimeMillis();
        boolean admin = net.axther.aftrhrs.core.Perms.has(viewer, ADMIN);
        for (Bounty b : list) {
            Set<String> placers = new LinkedHashSet<>();
            long first = Long.MAX_VALUE;
            for (Contribution c : b.parts) {
                placers.add(c.placerName);
                first = Math.min(first, c.placed);
            }
            String expires = expiryMs <= 0 ? text().raw("bounty.list.never") : Placeholders.duration(Math.max(0, first + expiryMs - now));
            ServerPlayer online = plugin.player(b.target);
            menu.item(Icons.head(b.target, online), text().msg("bounty.list.entry", Text.unparsed("target", b.targetName),
                    Text.unparsed("reward", summary(allItems(b), 6)), Text.unparsed("placers", String.join(", ", placers)),
                    Text.unparsed("expires", expires), Text.component("status", text().msg(online != null && plugin.canSee(viewer, online)
                            ? "bounty.list.online" : "bounty.list.offline"))), 260);
            if (admin) {
                UUID id = b.target;
                menu.button(text().msg("bounty.list.remove", Text.unparsed("target", b.targetName)), text().msg("bounty.list.remove-tooltip"), 150, p -> {
                    if (!net.axther.aftrhrs.core.Perms.has(p, ADMIN)) return;
                    remove(Sender.of(p), id);
                    openList(p);
                });
            }
        }
        if (admin) menu.columns(2);
        plugin.exitButton(menu, viewer);
        menu.open(viewer);
        text().sound(viewer, "menu-open");
    }

    private void remove(Sender sender, UUID target) {
        Bounty b = bounties.remove(target);
        if (b == null) {
            text().send(sender, "bounty.none", Text.unparsed("target", "?"));
            return;
        }
        for (Contribution c : b.parts) refund(c, "bounty.refunded", b.targetName);
        save();
        text().send(sender, "bounty.removed", Text.unparsed("target", b.targetName));
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("remove") || sub.equals("clear")) {
            if (!sender.hasPermission(ADMIN)) {
                text().send(sender, "general.no-permission");
                return true;
            }
            if (args.length < 2) {
                text().send(sender, "bounty.usage");
                return true;
            }
            UUID target = null;
            for (Bounty b : bounties.values()) if (b.targetName.equalsIgnoreCase(args[1])) target = b.target;
            if (target == null) {
                text().send(sender, "bounty.none", Text.unparsed("target", args[1]));
                return true;
            }
            remove(sender, target);
            return true;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text().send(sender, "general.players-only");
            return true;
        }
        if (sub.isEmpty() || sub.equals("list")) {
            openList(p);
            if (sub.isEmpty()) text().send(p, "bounty.usage");
            return true;
        }
        UUID targetId;
        String targetName;
        ServerPlayer online = plugin.findPlayer(p, args[0]);
        if (online != null) {
            targetId = online.getUUID();
            targetName = Names.name(online);
        } else {
            OfflinePlayer off = offlineTargets ? plugin.findOfflinePlayer(p, args[0]) : null;
            // A vanished player the sender can't see counts as not found (no hint that they're online).
            ServerPlayer hidden = off == null ? null : off.online();
            if (hidden != null && !plugin.canSee(p, hidden)) off = null;
            if (off == null || !off.hasPlayedBefore() || off.name() == null) {
                plugin.notFound(p, args[0]);
                return true;
            }
            targetId = off.id();
            targetName = off.name();
        }
        if (targetId.equals(p.getUUID())) {
            text().send(p, "bounty.self");
            text().sound(p, "error");
            return true;
        }
        Long last = cooldowns.get(p.getUUID());
        if (last != null && cooldownMs > 0 && System.currentTimeMillis() - last < cooldownMs) {
            text().send(p, "bounty.cooldown", Text.unparsed("time", Placeholders.duration(cooldownMs - (System.currentTimeMillis() - last) + 999)));
            text().sound(p, "error");
            return true;
        }
        openEditor(p, targetId, targetName);
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            out.add("list");
            if (sender.hasPermission(ADMIN)) out.add("remove");
            out.addAll(plugin.completeOthers(sender, last));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("remove") && sender.hasPermission(ADMIN)) {
            for (Bounty b : bounties.values()) out.add(b.targetName);
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ disk

    private List<String> encodeAll(List<ItemStack> items) {
        List<String> out = new ArrayList<>();
        for (ItemStack it : items) if (!Stacks.empty(it)) out.add(net.axther.aftrhrs.migrate.PaperItemBytes.encodeItemBase64(it));
        return out;
    }

    private List<ItemStack> decodeAll(List<String> raw) {
        List<ItemStack> out = new ArrayList<>();
        for (String s : raw) {
            try {
                ItemStack it = net.axther.aftrhrs.migrate.PaperItemBytes.decodeItemBase64(s);
                if (!it.isEmpty()) out.add(it);
            } catch (RuntimeException e) {
                // Never lose it quietly: the whole file is left alone until it is fixed.
                throw new IllegalStateException("bounties.yml: could not read an item (" + e + "): " + s.substring(0, Math.min(24, s.length())) + "...", e);
            }
        }
        return out;
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Yaml yml = Yaml.loadStrict(file);
            Yaml bs = yml.getConfigurationSection("bounties");
            if (bs != null) {
                for (String key : bs.getKeys(false)) {
                    Yaml s = bs.getConfigurationSection(key);
                    if (s == null) continue;
                    Bounty b = new Bounty();
                    b.target = UUID.fromString(key);
                    b.targetName = s.getString("name", "?");
                    for (Map<?, ?> m : s.getMapList("contributions")) {
                        Contribution c = new Contribution();
                        c.placer = UUID.fromString(String.valueOf(m.get("placer")));
                        c.placerName = String.valueOf(m.get("placer-name"));
                        c.placed = m.get("placed") instanceof Number n ? n.longValue() : System.currentTimeMillis();
                        c.items = decodeAll(strings(m.get("items")));
                        if (!c.items.isEmpty()) b.parts.add(c);
                    }
                    if (!b.parts.isEmpty()) bounties.put(b.target, b);
                }
            }
            Yaml cs = yml.getConfigurationSection("claims");
            if (cs != null) {
                for (String key : cs.getKeys(false)) {
                    Yaml s = cs.getConfigurationSection(key);
                    if (s == null) continue;
                    Claim c = new Claim();
                    c.id = key;
                    c.victim = UUID.fromString(s.getString("victim"));
                    c.victimName = s.getString("victim-name", "?");
                    String k = s.getString("killer");
                    c.killer = k == null ? null : UUID.fromString(k);
                    c.killerName = s.getString("killer-name", "?");
                    c.created = s.getLong("created");
                    c.items = decodeAll(s.getStringList("items"));
                    for (String u : s.getStringList("placers")) c.placers.add(UUID.fromString(u));
                    claims.put(key, c);
                }
            }
            Yaml mb = yml.getConfigurationSection("mailbox");
            if (mb != null) {
                for (String key : mb.getKeys(false)) {
                    List<ItemStack> items = decodeAll(mb.getStringList(key));
                    if (!items.isEmpty()) mailbox.put(UUID.fromString(key), items);
                }
            }
        } catch (IOException | RuntimeException e) {
            bounties.clear();
            claims.clear();
            mailbox.clear();
            unreadable = true;
            plugin.getLogger().error("Could not read {}: {} (bounties are off until it is fixed; the file is left untouched)", file, e.toString());
            throw new IllegalStateException("bounties.yml unreadable", e);
        }
    }

    /** Snapshots everything on the calling thread; it is turned into YAML and written in the background. */
    void save() {
        if (unreadable) return;
        Yaml yml = new Yaml();
        for (Bounty b : bounties.values()) {
            String base = "bounties." + b.target + ".";
            yml.set(base + "name", b.targetName);
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Contribution c : b.parts) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("placer", c.placer.toString());
                m.put("placer-name", c.placerName);
                m.put("placed", c.placed);
                m.put("items", encodeAll(c.items));
                parts.add(m);
            }
            yml.set(base + "contributions", parts);
        }
        for (Claim c : claims.values()) {
            String base = "claims." + c.id + ".";
            yml.set(base + "victim", c.victim.toString());
            yml.set(base + "victim-name", c.victimName);
            if (c.killer != null) yml.set(base + "killer", c.killer.toString());
            yml.set(base + "killer-name", c.killerName);
            yml.set(base + "created", c.created);
            yml.set(base + "items", encodeAll(c.items));
            yml.set(base + "placers", c.placers.stream().map(UUID::toString).toList());
        }
        for (Map.Entry<UUID, List<ItemStack>> e : mailbox.entrySet()) yml.set("mailbox." + e.getKey(), encodeAll(e.getValue()));
        // plain maps copied here; the YAML text is made on the IO thread
        Object snap = yml.snapshot();
        Runnable write = () -> {
            try {
                String data = Yaml.dump(snap);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling("bounties.yml.tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().error("Could not save {}: {}", file, e.toString());
            }
        };
        try {
            io.execute(write);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            write.run();
        }
    }
}
