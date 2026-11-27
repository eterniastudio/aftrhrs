package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.feature.coast.Coast;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.phys.Vec3;

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
 * Fishing (docs/fishing.md, {@code modules.fishing}, settings in fishing.yml): custom fish by biome group, time,
 * weather and gear; the strike window for rare and legendary fish; rods, offhand bait, Lucky Lure and the Ripplekit;
 * the Fish Journal; server records at fish scales; trophy plaques and aquarium tanks; crab pots; tournaments; message
 * bottles. The catch hooks into the native fishing hook ({@code FeatherEvents.FISH_*}, FishingHookMixin). Quests
 * count custom fish by content id and by rarity tag ({@code #rare}, {@code #rare+}: {@link Hooks.FishRarity}).
 */
public final class FishingModule implements FeatherModule, FeatherCommand {
    static final String PERM = "feather.fishing", ADMIN = "feather.fishing.admin";
    static final String LUCKY_LURE = "feather:lucky_lure_until";

    static volatile FishingModule active;

    private final Feather plugin;
    final FishTable table = new FishTable();
    final FishItems items;
    final FishData data;
    final FishingListener listener;
    final FishScreens screens;
    final Tournament tournament;
    final FishFurniture furniture;
    final CrabPots crabPots;
    final FishCompanion companionBridge;
    final FishFights fights;
    private Tasks.Task saver, fightTask;
    private final Set<String> warned = new HashSet<>();

    // settings
    double vanillaCommonShare = 0.5, shellfishChance = 0.04, extrasChance = 0.5;
    boolean strikeEnabled = true;
    boolean hotspotsEnabled = true;
    long strikeWindowMs = 800;
    int legendaryStrikes = 3, rebiteMin = 30, rebiteMax = 70;
    String strikeSound = "entity.player.splash.high_speed 1.0 0.7", strikeSoundLegendary = "entity.elder_guardian.flop 1.0 0.6",
            strikeHitSound = "entity.fishing_bobber.retrieve 1.0 0.6";
    String ripplekitMob = "ArcanaRipplekit";
    double ripplekitRadius = 12, ripplekitSpeed = 0.75;
    String clamId = "arcana_shellfish_clam", pearlId = "arcana_pearl", blackPearlId = "arcana_black_pearl", bottleId = "arcana_message_bottle";
    double blackPearlChance = 0.15;
    String crackAnimation = "ability";
    List<Weighted> shellfishCatch = List.of(), extrasSalt = List.of(), extrasFresh = List.of(), bottleRewards = List.of();
    final Map<String, Long> journalRewards = new HashMap<>();
    boolean broadcastLegendary = true;

    // debug (staff, /fishing debug)
    final Map<UUID, String> debugForce = new HashMap<>();
    final Set<UUID> debugFast = new HashSet<>(), debugBites = new HashSet<>(), debugRipple = new HashSet<>();

    /** "id min-max weight" from a list in fishing.yml. */
    record Weighted(String id, int min, int max, double weight) {
    }

    public FishingModule(Feather plugin) {
        this.plugin = plugin;
        this.items = new FishItems(this);
        this.data = new FishData(plugin.dataFile("fishing.yml"), plugin.getLogger());
        this.listener = new FishingListener(this);
        this.screens = new FishScreens(this);
        this.tournament = new Tournament(this);
        this.furniture = new FishFurniture(this);
        this.crabPots = new CrabPots(this);
        this.companionBridge = new FishCompanion(this);
        this.fights = new FishFights(this);
    }

    @Override
    public String name() {
        return "Fishing";
    }

    public Feather plugin() {
        return plugin;
    }

    Dialogs dialogs() {
        return plugin.dialogs();
    }

    @Override
    public void enable() {
        data.load();
        reload();
        active = this;
        plugin.once("fishing.events", () -> {
            FishingListener.register();
            FishFurniture.register();
            CrabPots.register();
            FishCompanion.register();
            FishFights.register();
        });
        furniture.start();
        crabPots.start();
        net.axther.aftrhrs.net.Inspect.register("fishing", crabPots::inspect);
        companionBridge.start();
        plugin.bind("fishing", this);
        plugin.provide(Hooks.FishRarity.class, this::rarityOf);
        plugin.hub().add("fishing", Hub.Group.COMMUNITY, Hub.ORDER_FISHING, "hub-social", PERM, this::openJournal);
        tournament.start();
        saver = Tasks.timer(1200L, 1200L, data::saveIfDirty);
        fightTask = Tasks.timer(20L, 20L, fights::tick);
    }

    @Override
    public void reload() {
        data.saveIfDirty();
        if (data.unreadable) data.load();
        Yaml y = plugin.loadResourceConfig("fishing.yml");
        table.load(y, this::warnOnce);
        items.reload(y);
        vanillaCommonShare = clamp(y.getDouble("catch.vanilla-common-share", 0.5), 0, 1);
        shellfishChance = clamp(y.getDouble("catch.shellfish-chance", 0.04), 0, 1);
        extrasChance = clamp(y.getDouble("catch.extras-chance", 0.5), 0, 1);
        shellfishCatch = weighted(y.getStringList("catch.shellfish"), "catch.shellfish");
        extrasSalt = weighted(y.getStringList("catch.extras.salt"), "catch.extras.salt");
        extrasFresh = weighted(y.getStringList("catch.extras.fresh"), "catch.extras.fresh");
        strikeEnabled = y.getBoolean("strike.enabled", true);
        hotspotsEnabled = y.getBoolean("hotspots.enabled", true);
        strikeWindowMs = Math.max(100, y.getLong("strike.window-ms", 800));
        legendaryStrikes = Math.max(1, Math.min(10, y.getInt("strike.legendary-strikes", 3)));
        List<Integer> rebite = y.getIntegerList("strike.rebite-ticks");
        rebiteMin = Math.max(5, rebite.size() > 0 ? rebite.get(0) : 30);
        rebiteMax = Math.max(rebiteMin + 1, rebite.size() > 1 ? rebite.get(1) : 70);
        strikeSound = y.getString("strike.sound", strikeSound);
        strikeSoundLegendary = y.getString("strike.sound-legendary", strikeSoundLegendary);
        strikeHitSound = y.getString("strike.hit-sound", strikeHitSound);
        ripplekitMob = y.getString("ripplekit.mob", "ArcanaRipplekit");
        ripplekitRadius = Math.max(1, y.getDouble("ripplekit.radius", 12));
        ripplekitSpeed = clamp(y.getDouble("ripplekit.bite-speed", 0.75), 0.05, 1);
        clamId = y.getString("ripplekit.clam", "arcana_shellfish_clam").toLowerCase(Locale.ROOT);
        pearlId = y.getString("ripplekit.pearl", "arcana_pearl").toLowerCase(Locale.ROOT);
        blackPearlId = y.getString("ripplekit.black-pearl", "arcana_black_pearl").toLowerCase(Locale.ROOT);
        blackPearlChance = clamp(y.getDouble("ripplekit.black-pearl-chance", 0.15), 0, 1);
        crackAnimation = y.getString("ripplekit.crack-animation", "ability");
        bottleId = y.getString("bottle.item", "arcana_message_bottle").toLowerCase(Locale.ROOT);
        bottleRewards = weighted(y.getStringList("bottle.rewards"), "bottle.rewards");
        journalRewards.clear();
        long[] def = {40, 100, 250, 800};
        for (int i = 0; i < FishTable.RARITIES.size(); i++) {
            String r = FishTable.RARITIES.get(i);
            journalRewards.put(r, Math.max(0, y.getLong("journal.rewards." + r, def[i])));
        }
        broadcastLegendary = y.getBoolean("catch.broadcast-legendary", true);
        tournament.reload(y);
        fights.reload(y);
        furniture.reload(y);
        crabPots.reload(y);
        plugin.getLogger().info("Fishing: {} species, {} biome groups, {} rods, {} bait.", table.species.size(), table.groupNames().size(),
                table.rods.size(), table.bait.size());
    }

    @Override
    public void disable() {
        companionBridge.stop();
        if (saver != null) saver.cancel();
        saver = null;
        if (fightTask != null) fightTask.cancel();
        fightTask = null;
        tournament.stop();
        furniture.stop();
        net.axther.aftrhrs.net.Inspect.unregister("fishing");
        crabPots.stop();
        listener.clear();
        plugin.withdraw(Hooks.FishRarity.class);
        plugin.hub().remove("fishing");
        data.save();
        if (active == this) active = null;
    }

    void warnOnce(String message) {
        if (warned.add(message)) plugin.getLogger().warn(message);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private List<Weighted> weighted(List<String> lines, String where) {
        List<Weighted> out = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.trim().split("\\s+");
            try {
                int min = 1, max = 1;
                double w = 1;
                if (p.length == 2) w = Double.parseDouble(p[1]);
                else if (p.length >= 3) {
                    String[] r = p[1].split("-");
                    min = Integer.parseInt(r[0]);
                    max = r.length > 1 ? Integer.parseInt(r[1]) : min;
                    w = Double.parseDouble(p[2]);
                }
                if (w > 0 && !p[0].isEmpty()) out.add(new Weighted(p[0].toLowerCase(Locale.ROOT), Math.max(1, min), Math.max(min, max), w));
            } catch (RuntimeException e) {
                warnOnce("fishing.yml " + where + ": can't read '" + line + "' (use: id [min-max] weight).");
            }
        }
        return List.copyOf(out);
    }

    Weighted pickEntry(List<Weighted> list) {
        double total = 0;
        for (Weighted w : list) total += w.weight();
        if (total <= 0) return null;
        double x = ThreadLocalRandom.current().nextDouble() * total;
        for (Weighted w : list) {
            x -= w.weight();
            if (x < 0) return w;
        }
        return list.get(list.size() - 1);
    }

    String pickWeighted(List<Weighted> list) {
        Weighted w = pickEntry(list);
        return w == null ? null : w.id();
    }

    void playSound(ServerLevel level, Vec3 at, String spec) {
        Coast.soundSpec(level, at, spec, SoundSource.PLAYERS);
    }

    // ------------------------------------------------------------------ where, what gear

    boolean salt(ServerLevel level, BlockPos at) {
        return table.salt(table.groupsOf(Coast.biomeKey(level, at)));
    }

    FishTable.Context context(ServerPlayer p, ServerLevel w, Vec3 at, boolean openWater) {
        BlockPos pos = BlockPos.containing(at);
        String biome = Coast.biomeKey(w, pos);
        var groups = table.groupsOf(biome);
        boolean underground = pos.getY() < table.undergroundMaxY && w.getBrightness(LightLayer.SKY, pos) <= table.undergroundMaxSky;
        long time = Math.floorMod(w.getDefaultClockTime(), 24000L);
        boolean fullMoon;
        try {
            fullMoon = w.environmentAttributes().getValue(EnvironmentAttributes.MOON_PHASE, pos) == MoonPhase.FULL_MOON;
        } catch (RuntimeException e) {
            fullMoon = (w.getDefaultClockTime() / 24000L) % 8 == 0;
        }
        ItemStack rod = p == null ? ItemStack.EMPTY : rodStack(p);
        return new FishTable.Context(biome, groups, table.salt(groups), underground, time, w.isRaining(), w.isThundering(), fullMoon,
                openWater, p == null ? null : rodId(p), p == null ? null : baitId(p), p != null && luckyLure(p), p != null && ripplekitNear(p),
                hotspotsEnabled && hotspot(w, at) ? hotspotRare() : 1, enchant(w, rod, Enchantments.LUCK_OF_THE_SEA), enchant(w, rod, Enchantments.LURE));
    }

    /** The rod in use (main hand first, like vanilla), or empty. */
    ItemStack rodStack(ServerPlayer p) {
        if (isRod(p.getMainHandItem())) return p.getMainHandItem();
        return isRod(p.getOffhandItem()) ? p.getOffhandItem() : ItemStack.EMPTY;
    }

    /** An enchantment's level on a stack (0 when the registry doesn't know it). */
    static int enchant(ServerLevel level, ItemStack s, ResourceKey<Enchantment> key) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), s);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A wildlife Squallgull hotspot here (Hooks.WildlifeHotspots; always false while wildlife is off). */
    boolean hotspot(ServerLevel level, Vec3 at) {
        Hooks.WildlifeHotspots h = plugin.service(Hooks.WildlifeHotspots.class);
        try {
            return h != null && at != null && h.isHotspot(level, at);
        } catch (RuntimeException e) {
            return false;
        }
    }

    double hotspotBite() {
        Hooks.WildlifeHotspots h = plugin.service(Hooks.WildlifeHotspots.class);
        return h == null ? 1 : Math.max(1, h.biteSpeedMultiplier());
    }

    double hotspotRare() {
        Hooks.WildlifeHotspots h = plugin.service(Hooks.WildlifeHotspots.class);
        return h == null ? 1 : Math.max(1, h.rareWeightMultiplier());
    }

    static boolean isRod(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof FishingRodItem;
    }

    /** The fishing rod in use: main hand first (like vanilla), else the off hand. Its content id, or null. */
    String rodId(ServerPlayer p) {
        ItemStack main = p.getMainHandItem();
        if (isRod(main)) return items.idOf(main);
        ItemStack off = p.getOffhandItem();
        return isRod(off) ? items.idOf(off) : null;
    }

    /** Bait in the off hand (only while the rod is in the main hand), or null. */
    String baitId(ServerPlayer p) {
        if (!isRod(p.getMainHandItem())) return null;
        String id = items.idOf(p.getOffhandItem());
        return id != null && table.bait.containsKey(id) ? id : null;
    }

    /** Uses up one of the bait that was on when the fish bit: the off hand first, then the main hand, then the rest. */
    void useBait(ServerPlayer p, String baitId) {
        if (baitId == null || p.isCreative()) return;
        if (baitId.equals(items.idOf(p.getOffhandItem()))) {
            p.getOffhandItem().shrink(1);
            return;
        }
        if (baitId.equals(items.idOf(p.getMainHandItem()))) {
            p.getMainHandItem().shrink(1);
            return;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            ItemStack s = inv.getItem(i);
            if (!baitId.equals(items.idOf(s))) continue;
            s.shrink(1);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            return;
        }
    }

    boolean luckyLure(ServerPlayer p) {
        return Pdc.getLong(p, LUCKY_LURE, 0) > System.currentTimeMillis();
    }

    void setLuckyLure(ServerPlayer p, long until) {
        if (until <= 0) Pdc.remove(p, LUCKY_LURE);
        else Pdc.putLong(p, LUCKY_LURE, until);
    }

    boolean ripplekitNear(ServerPlayer p) {
        if (debugRipple.contains(p.getUUID())) return true;
        return !Coast.followingNearby(p, ripplekitMob, ripplekitRadius).isEmpty();
    }

    boolean isRipplekit(Entity e) {
        EntityType<?> t = Coast.creatureType(ripplekitMob);
        return e != null && t != null && e.getType() == t;
    }

    // ------------------------------------------------------------------ quests

    /** Rarity of a reported fish id for quest tags: custom fish by species, vanilla fish are common, the rest null. */
    String rarityOf(String id) {
        if (id == null) return null;
        String s = id.toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring(10);
        if (s.startsWith("aftrhrs:")) s = s.substring(8);
        if (s.startsWith("arcana_fish_")) {
            FishTable.Species sp = table.species.get(s.substring(12));
            return sp == null ? null : sp.rarity();
        }
        return switch (s) {
            case "cod", "salmon", "tropical_fish", "pufferfish" -> "common";
            default -> null;
        };
    }

    /** Reports catches for quests and the server goal. */
    void countCatch(ServerPlayer p, String id, int amount) {
        Hooks.Quests q = plugin.service(Hooks.Quests.class);
        if (q != null && amount > 0) q.progress(p, "fish", id, amount);
    }

    /** What a catch counts as (old QuestEvents.fishId): a custom fish's id, a stand-in's id, a content id, else the item path. */
    String fishId(ItemStack s) {
        String id = items.idOf(s);
        if (id != null) return id;
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    // ------------------------------------------------------------------ a fish landed

    /** A custom fish was caught: journal, tournament, and a cheer for rare and legendary ones. */
    void landed(ServerPlayer p, FishTable.Species s, int grams) {
        Text text = plugin.text();
        FishData.Journal j = data.journal(p.getUUID());
        j.name = Names.name(p);
        FishData.Entry e = j.species.get(s.id());
        boolean first = e == null;
        if (first) {
            e = new FishData.Entry();
            e.first = System.currentTimeMillis();
            j.species.put(s.id(), e);
        }
        e.count++;
        boolean best = grams > e.best;
        if (best) e.best = grams;
        data.dirty = true;
        var fish = Text.component("fish", text.parse(items.color(s.rarity()) + s.name().replace("<", "\\<")));
        var weight = Text.unparsed("weight", FishWeight.format(grams));
        if (first) {
            text.send(p, "fishing.journal.new-species", fish, weight, Text.unparsed("count", j.species.size()),
                    Text.unparsed("total", table.species.size()));
            checkTier(p, s.rarity());
        } else if (best && s.rank() >= 1) {
            text.send(p, "fishing.journal.personal-best", fish, weight);
        }
        if (s.rank() >= 2) {
            playSound(p.level(), p.position(), s.rank() >= 3 ? "ui.toast.challenge_complete 0.8 1.2" : "entity.player.levelup 0.6 1.6");
            p.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1.2, p.getZ(), 12, 0.4, 0.4, 0.4, 0);
            text.send(p, s.rank() >= 3 ? "fishing.catch.legendary" : "fishing.catch.rare", fish, weight);
        }
        if (s.rank() >= 3 && broadcastLegendary) {
            for (ServerPlayer o : plugin.onlinePlayers()) {
                if (o != p && !plugin.authBlocked(o)) text.send(o, "fishing.catch.legendary-broadcast", fish, weight, Text.component("player", Names.display(p)));
            }
        }
        tournament.entry(p, s, grams);
        plugin.advance(p, "event", "fish_" + s.rarity());
    }

    /** Pays a journal tier reward when every species of that rarity is caught (once). */
    void checkTier(ServerPlayer p, String rarity) {
        FishData.Journal j = data.journal(p.getUUID());
        if (j.paid.contains(rarity) || !tierComplete(j, rarity)) return;
        long coins = journalRewards.getOrDefault(rarity, 0L);
        Text text = plugin.text();
        var tier = Text.unparsed("tier", FishTable.pretty(rarity));
        if (coins <= 0) {
            j.paid.add(rarity);
            data.dirty = true;
            text.send(p, "fishing.journal.tier-done", tier);
            return;
        }
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        if (bank != null && bank.credit(p.getUUID(), Names.name(p), coins, "fish journal: " + rarity)) {
            j.paid.add(rarity);
            data.dirty = true;
            data.save();
            text.send(p, "fishing.journal.tier-paid", tier, Text.unparsed("coins", coins));
            playSound(p.level(), p.position(), "ui.toast.challenge_complete 0.7 1.4");
        } else {
            text.send(p, "fishing.journal.tier-unpaid", tier);
        }
    }

    boolean tierComplete(FishData.Journal j, String rarity) {
        int n = 0;
        for (FishTable.Species s : table.species.values()) {
            if (!s.rarity().equals(rarity)) continue;
            n++;
            if (!j.species.containsKey(s.id())) return false;
        }
        return n > 0;
    }

    // ------------------------------------------------------------------ message bottles and clams

    void openBottle(ServerPlayer p, InteractionHand hand) {
        ItemStack held = p.getItemInHand(hand);
        if (held.isEmpty()) return;
        Text text = plugin.text();
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        String rewardText = null;
        ItemStack give = null;
        long coins = 0;
        for (int tries = 0; tries < 8 && rewardText == null; tries++) {
            Weighted w = pickEntry(bottleRewards);
            if (w == null) break;
            int n = w.min() + ThreadLocalRandom.current().nextInt(w.max() - w.min() + 1);
            if (w.id().equals("coins")) {
                if (bank == null) continue;
                coins = n;
                rewardText = text.raw("fishing.bottle.coins").replace("<amount>", String.valueOf(n));
            } else if (w.id().startsWith("minecraft:")) {
                var mat = Coast.vanillaItem(w.id());
                if (mat == null || mat == Items.AIR) continue;
                give = new ItemStack(mat, Math.min(n, new ItemStack(mat).getMaxStackSize()));
                rewardText = n + "× " + FishTable.pretty(w.id().substring(10));
            } else {
                give = items.other(w.id(), n);
                if (give == null) continue;
                rewardText = n + "× " + FishTable.pretty(w.id().replaceFirst("^arcana_", ""));
            }
        }
        if (coins > 0 && !bank.credit(p.getUUID(), Names.name(p), coins, "message bottle")) {
            text.send(p, "fishing.bottle.bank-closed");
            return;
        }
        if (!p.isCreative()) held.shrink(1);
        if (give != null) Coast.give(p, give);
        List<String> notes = text.variants("fishing.bottle.notes");
        String note = notes.isEmpty() ? "" : notes.get(ThreadLocalRandom.current().nextInt(notes.size()));
        text.send(p, "fishing.bottle.opened", Text.component("note", text.parse(note)),
                Text.unparsed("reward", rewardText == null ? text.raw("fishing.bottle.nothing") : rewardText));
        playSound(p.level(), p.position(), "entity.item.pickup 0.8 0.8");
        playSound(p.level(), p.position(), "block.glass.break 0.4 1.6");
    }

    /** Takes a clam from the main hand and gives a pearl (sometimes a black pearl). */
    void crackClam(ServerPlayer p, Vec3 at) {
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) return;
        boolean black = ThreadLocalRandom.current().nextDouble() < blackPearlChance;
        ItemStack pearl = items.other(black ? blackPearlId : pearlId, 1);
        if (pearl == null) {
            plugin.text().actionBar(p, "fishing.clam.missing");
            return;
        }
        held.shrink(1);
        Coast.give(p, pearl);
        playSound(p.level(), at, "entity.turtle.egg_crack 0.9 1.3");
        p.level().sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.BONE_MEAL), at.x, at.y, at.z, 10, 0.2, 0.2, 0.2, 0.05);
        plugin.text().send(p, black ? "fishing.clam.black-pearl" : "fishing.clam.pearl");
    }

    /** A clam held at the player's own Ripplekit: crack it (cooldown, owner check). True when it was a Ripplekit. */
    boolean crack(ServerPlayer p, Entity clicked) {
        return listener.crack(p, clicked);
    }

    void playAnimation(Entity e, String clip) {
        if (e instanceof ArcanaMob mob && clip != null && !clip.isBlank() && !clip.equalsIgnoreCase("none")) mob.play(clip);
    }

    boolean ownsRipplekit(ServerPlayer p, Entity e) {
        return e instanceof ArcanaCreature c && Coast.ownedBy(c, p);
    }

    // ------------------------------------------------------------------ API for other modules

    /** Opens the Fish Journal (hub button). */
    public void openJournal(ServerPlayer p) {
        screens.journal(p);
    }

    /** Opens the records list (civic's fish scale fallback, /fishing records). */
    public void openRecords(ServerPlayer p) {
        screens.records(p);
    }

    /** Records changed: redraw the boards at the fish scales. */
    void recordsChanged() {
        data.dirty = true;
        furniture.refreshBoards();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        String sub = args.length == 0 ? "journal" : args[0].toLowerCase(Locale.ROOT);
        Text text = plugin.text();
        switch (sub) {
            case "journal" -> {
                if (sender.isPlayer()) screens.journal(sender.player());
                else text.send(sender, "general.players-only");
            }
            case "records" -> {
                if (sender.isPlayer()) screens.records(sender.player());
                else screens.recordsChat(sender);
            }
            case "tournament" -> tournament.command(sender, args);
            case "debug" -> {
                if (!sender.hasPermission(ADMIN)) {
                    text.send(sender, "general.no-permission");
                    return true;
                }
                FishDebug.run(this, sender, args);
            }
            default -> text.send(sender, sender.hasPermission(ADMIN) ? "fishing.help-admin" : "fishing.help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            out.addAll(List.of("journal", "records", "tournament"));
            if (admin) out.add("debug");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tournament")) {
            out.add("status");
            if (admin) out.addAll(List.of("start", "stop"));
        } else if (admin && args[0].equalsIgnoreCase("debug")) {
            out.addAll(FishDebug.complete(this, args));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    static void broadcast(Feather plugin, net.kyori.adventure.text.Component c) {
        for (ServerPlayer p : plugin.onlinePlayers()) Adv.of(p).sendMessage(c);
    }
}
