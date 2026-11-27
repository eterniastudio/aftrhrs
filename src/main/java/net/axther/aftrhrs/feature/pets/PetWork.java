package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Work Here": a pet told to work stays at a spot and does its job within {@code work.radius} blocks of it. While its
 * chunk is loaded the creature's own job runs as always; Feather adds the work the creature can't do: time the chunk
 * was unloaded, and, for jobs the creature only does near the owner (or never while staying), time loaded with nobody
 * to do it. Earned time goes into a bank (capped at {@code work.max-hours}); on each chunk load and every live check
 * the bank is paid out in rounds of {@code every-seconds}. Payouts run from a queue with a per-tick time budget and only
 * touch loaded chunks, so a chunk load never lags the server and nothing is force-loaded.
 *
 * <p>Job types: {@code crops}, {@code bloom}, {@code items}, {@code farmhand} (vanilla crops and farming's, via
 * {@link Hooks.Farmhand}) and {@code guard} (the creature guards; Feather keeps the spot).
 */
final class PetWork {
    enum Type { CROPS, BLOOM, ITEMS, FARMHAND, GUARD }

    enum Live { ALWAYS, OWNER_AWAY, NEVER }

    enum Need { NONE, WATER, GROUND }

    record Loot(Item item, int min, int max, double weight) {
    }

    record Job(String species, Type type, long everyMs, Live live, double ownerRange, Need need, List<Loot> loot,
               double eats, int perRound, int maxPerRun, String description) {
    }

    private final PetsModule m;
    private final Map<String, Job> jobs = new HashMap<>();
    private final Deque<Run> queue = new ArrayDeque<>();
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private long seconds;

    private boolean enabled = true;
    private int radius = 8, vertical = 2;
    private long capMs = 24 * 3_600_000L;
    private long budgetNs = 2_000_000L;
    private int liveEvery = 60;
    private long loadDelay = 40;
    private double bondBonus = 0.1;
    private double orderRange = 24;

    PetWork(PetsModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        enabled = cfg.getBoolean("work.enabled", true);
        radius = Math.max(1, Math.min(16, cfg.getInt("work.radius", 8)));
        vertical = Math.max(0, Math.min(4, cfg.getInt("work.vertical", 2)));
        capMs = (long) (Math.max(0, cfg.getDouble("work.max-hours", 24)) * 3_600_000L);
        budgetNs = (long) (Math.max(0.2, cfg.getDouble("work.tick-budget-ms", 2)) * 1_000_000L);
        liveEvery = Math.max(10, cfg.getInt("work.live-check-seconds", 60));
        loadDelay = Math.max(1, cfg.getInt("work.load-delay-ticks", 40));
        bondBonus = Math.max(0, cfg.getDouble("work.bond-speedup", 0.1));
        orderRange = Math.max(4, cfg.getDouble("work.order-range", 24));
        double defRange = Math.max(1, cfg.getDouble("work.owner-range", 16));
        jobs.clear();
        Yaml sec = PetsModule.section(cfg, "work.jobs");
        if (sec == null) return;
        // Jobs shipped later also apply when an older pets.yml doesn't list them yet.
        Yaml shipped = cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection("work.jobs");
        Set<String> names = new LinkedHashSet<>(sec.getKeys(false));
        if (shipped != null) names.addAll(shipped.getKeys(false));
        for (String species : names) {
            Yaml c = sec.isConfigurationSection(species) ? sec.getConfigurationSection(species)
                    : shipped == null ? null : shipped.getConfigurationSection(species);
            if (c == null) continue;
            Type type;
            Live live;
            Need need;
            try {
                type = Type.valueOf(c.getString("type", "items").trim().toUpperCase(Locale.ROOT));
                live = Live.valueOf(c.getString("live", "owner-away").trim().toUpperCase(Locale.ROOT).replace('-', '_'));
                need = Need.valueOf(c.getString("needs", "none").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                m.feather().getLogger().warn("pets.yml work.jobs.{}: unknown type, live or needs value ({}).", species, e.getMessage());
                continue;
            }
            List<Loot> loot = new ArrayList<>();
            for (String line : c.getStringList("loot")) {
                Loot l = parseLoot(line);
                if (l == null) m.feather().getLogger().warn("pets.yml work.jobs.{}.loot: can't read '{}' (MATERIAL min-max weight).", species, line);
                else loot.add(l);
            }
            if (type == Type.ITEMS && loot.isEmpty()) continue;
            long every = (long) (Math.max(10, c.getDouble("every-seconds", 600)) * 1000);
            jobs.put(species, new Job(species, type, every, live, Math.max(1, c.getDouble("owner-range", defRange)), need,
                    List.copyOf(loot), Math.max(0, Math.min(1, c.getDouble("eats", 0))), Math.max(1, c.getInt("per-round", 4)),
                    Math.max(1, c.getInt("max-per-run", 6)), c.getString("description", "")));
        }
    }

    private static Loot parseLoot(String line) {
        if (line == null || line.isBlank()) return null;
        String[] p = line.trim().split("\\s+");
        Item item = PetUtil.item(p[0]);
        if (item == null) return null;
        int min = 1, max = 1;
        double w = 1;
        try {
            if (p.length > 1) {
                String[] r = p[1].split("-");
                min = Integer.parseInt(r[0]);
                max = r.length > 1 ? Integer.parseInt(r[1]) : min;
            }
            if (p.length > 2) w = Double.parseDouble(p[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        min = Math.max(1, Math.min(64, min));
        max = Math.max(min, Math.min(64, max));
        return new Loot(item, min, max, Math.max(0, w));
    }

    boolean enabled() {
        return enabled;
    }

    Job job(String species) {
        return jobs.get(species);
    }

    private long every(Job j, Pet p) {
        return Math.max(1000, (long) (j.everyMs() / (1 + bondBonus * Math.max(0, p.level))));
    }

    // ------------------------------------------------------------------ orders

    /** "Work Here" from the menu. Returns null when done, else a message key. */
    String order(ServerPlayer owner, Pet p) {
        if (!enabled) return "work.disabled";
        Entity e = m.entity(p);
        if (e == null) return "stay.not-loaded";
        if (e.level() != owner.level() || e.distanceToSqr(owner) > orderRange * orderRange) return "work.too-far";
        if (m.busy(p)) return "stable.busy";
        if (!m.setStay(owner, p, true)) return "stay.not-loaded";
        p.work = Pet.Spot.of(e);
        p.workClock = System.currentTimeMillis();
        p.workBank = 0;
        p.workWaiting = null;
        p.workLoaded = true;
        m.store().markDirty();
        return null;
    }

    /** Ends the work order (follow, summon, stable, death, release). */
    void stop(Pet p) {
        if (p.work == null) return;
        p.work = null;
        p.workBank = 0;
        p.workClock = 0;
        p.workWaiting = null;
        m.store().markDirty();
    }

    // ------------------------------------------------------------------ time

    private boolean live(Job j, Pet p, Entity e) {
        return switch (j.live()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case OWNER_AWAY -> {
                ServerPlayer o = m.feather().player(p.owner);
                yield o == null || o.level() != e.level() || o.distanceToSqr(e) > j.ownerRange() * j.ownerRange();
            }
        };
    }

    private void bank(Pet p, long ms) {
        if (ms <= 0) return;
        p.workBank = Math.min(capMs, p.workBank + ms);
    }

    /** The working pet's entity loaded again: everything since the clock is unloaded time, caught up now. */
    void loaded(Pet p) {
        if (!enabled || !p.working() || p.workLoaded) return;
        long now = System.currentTimeMillis();
        p.workLoaded = true;
        if (p.workClock > 0 && now > p.workClock) bank(p, now - p.workClock);
        p.workClock = now;
        m.store().markDirty();
        Job j = jobs.get(p.species);
        if (j != null && p.workBank >= every(j, p)) {
            p.workReport = true;
            UUID id = p.id;
            // a moment later, so the neighboring chunks (crops, the chest) have loaded too
            Tasks.later(loadDelay, () -> {
                Pet pet = m.store().get(id);
                if (pet != null) enqueue(pet, true);
            });
        }
    }

    /** The working pet's chunk is unloading: settle the loaded time so far. */
    void unloading(Pet p, Entity e) {
        if (!enabled || !p.working()) return;
        long now = System.currentTimeMillis();
        Job j = jobs.get(p.species);
        if (p.workLoaded && j != null && e != null && live(j, p, e) && now > p.workClock) bank(p, now - p.workClock);
        if (p.workLoaded) p.workClock = now;
        p.workLoaded = false;
        m.store().markDirty();
    }

    /** Every second: the live check every work.live-check-seconds. */
    void tick() {
        if (!enabled || ++seconds % liveEvery != 0) return;
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Pet p : m.store().loaded()) {
            if (!p.working()) continue;
            Entity e = m.entity(p);
            if (e == null) continue;   // unloaded: its clock waits for the next load
            any = true;
            if (!p.workLoaded) {
                loaded(p);
                continue;
            }
            Job j = jobs.get(p.species);
            if (j != null && live(j, p, e) && now > p.workClock) bank(p, now - p.workClock);
            p.workClock = now;
            if (j != null && p.workBank >= every(j, p)) enqueue(p, false);
        }
        if (any) m.store().markDirty();
    }

    /** Test hook: pretend {@code minutes} more passed. Unloaded: its clock goes back; loaded: banked and paid now. */
    String fakeElapsed(Pet p, int minutes) {
        long ms = minutes * 60_000L;
        if (m.entity(p) == null) {
            p.workClock -= ms;
            m.store().markDirty();
            return "unloaded";
        }
        bank(p, ms);
        enqueue(p, true);
        m.store().markDirty();
        return "loaded";
    }

    // ------------------------------------------------------------------ the queue

    private void enqueue(Pet p, boolean report) {
        Job j = jobs.get(p.species);
        if (j == null || !p.working() || !queued.add(p.id)) return;
        queue.add(new Run(p, j, report));
    }

    /** Every tick: runs queued work until work.tick-budget-ms is used. */
    void drain() {
        if (queue.isEmpty()) return;
        long end = System.nanoTime() + budgetNs;
        while (!queue.isEmpty() && System.nanoTime() < end) {
            Run t = queue.peekFirst();
            boolean done;
            try {
                done = t.step();
            } catch (Throwable ex) {
                m.feather().getLogger().warn("A pet's work failed", ex);
                done = true;
            }
            if (done) {
                queue.pollFirst();
                queued.remove(t.pet.id);
            }
        }
    }

    void clear() {
        queue.clear();
        queued.clear();
    }

    /** One pet's payout: a scan (one slab per step), then the rounds the bank pays for. */
    private final class Run {
        final Pet pet;
        final Job job;
        final boolean report;
        ServerLevel level;
        int cx, cy, cz, dx;
        boolean started, scanned;
        long rounds, apps, every;
        final List<BlockPos> found = new ArrayList<>();
        int idx, done;

        Run(Pet pet, Job job, boolean report) {
            this.pet = pet;
            this.job = job;
            this.report = report;
        }

        boolean step() {
            if (started && (!pet.working() || m.store().get(pet.id) != pet)) return true;
            if (!started) {
                started = true;
                if (!pet.working() || m.store().get(pet.id) != pet) return true;
                Loc at = pet.work.location();
                if (at == null) return true;
                level = at.level();
                cx = at.blockX();
                cy = at.blockY();
                cz = at.blockZ();
                if (!loaded(level, cx, cz)) return true;
                every = every(job, pet);
                rounds = pet.workBank / every;
                if (rounds <= 0) return true;
                dx = -radius;
                if (job.type() == Type.ITEMS) return items();
                if (job.type() == Type.GUARD) {
                    pet.workBank = 0;   // the creature guards while it's loaded; nothing to catch up
                    return true;
                }
                if (job.type() == Type.FARMHAND && farmContainer() == null) return waiting("no-container");
            }
            if (!scanned) {
                scanSlice();
                return false;
            }
            return switch (job.type()) {
                case CROPS -> crops();
                case FARMHAND -> farmhand();
                default -> bloom();
            };
        }

        private Container farmContainer() {
            Hooks.Farmhand h = m.feather().service(Hooks.Farmhand.class);
            int r = h == null ? 3 : Math.max(1, Math.min(radius, h.containerRadius()));
            return container(level, cx, cy, cz, pet.owner, r);
        }

        private boolean farmhand() {
            Container inv = farmContainer();
            if (inv == null) return waiting("no-container");
            Hooks.Farmhand h = m.feather().service(Hooks.Farmhand.class);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int n = 0;
            boolean full = false;
            while (apps > 0 && !found.isEmpty() && n < 32 && !full) {
                BlockPos b = found.remove(found.size() - 1);
                n++;
                if (!loaded(level, b.getX(), b.getZ())) continue;
                List<ItemStack> drops = harvestRipe(level, b, h);
                if (drops == null) continue;
                apps--;
                done++;
                for (ItemStack s : drops) {
                    if (s == null || s.isEmpty()) continue;
                    ItemStack left = PetUtil.add(inv, s);
                    if (!left.isEmpty()) {
                        full = true;
                        Block.popResource(level, b, left);
                    }
                }
            }
            if (!full && apps > 0 && !found.isEmpty()) return false;
            if (h != null && done > 0) {
                for (long i = 0; i < Math.min(rounds, 64); i++) {
                    if (r.nextDouble() >= h.seedFindChance()) continue;
                    ItemStack seed = h.seedFind();
                    if (seed != null && !seed.isEmpty()) PetUtil.add(inv, seed);
                }
                Entity worker = m.entity(pet);
                if (worker != null) h.worked(worker);
            }
            pet.workBank = Math.max(0, pet.workBank - rounds * every);
            if (found.isEmpty()) pet.workBank %= every;
            if (full) {
                waiting("container-full");
                return report(done, "work.result-harvest");
            }
            return finish(done, "work.result-harvest");
        }

        /** One x slab of the radius box per step, only in loaded chunks. */
        private void scanSlice() {
            int x = cx + dx;
            Hooks.Farmhand h = m.feather().service(Hooks.Farmhand.class);
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!loaded(level, x, z)) continue;
                for (int y = cy - vertical; y <= cy + vertical; y++) {
                    BlockPos b = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(b);
                    boolean wanted = switch (job.type()) {
                        case CROPS -> growable(s);
                        case FARMHAND -> ripe(level, b, s, h);
                        default -> bloomable(level, b, s);
                    };
                    if (wanted && m.feather().claimAllows(pet.owner, Loc.of(level, b), ClaimAction.BUILD)) found.add(b);
                }
            }
            if (++dx > radius) {
                scanned = true;
                Collections.shuffle(found);
                apps = rounds * job.perRound();
            }
        }

        private boolean crops() {
            int n = 0;
            while (apps > 0 && !found.isEmpty() && n < 32) {
                if (idx >= found.size()) idx = 0;
                BlockPos b = found.get(idx);
                n++;
                if (!loaded(level, b.getX(), b.getZ()) || !growable(level.getBlockState(b)) || !boneMeal(level, b)) {
                    found.remove(idx);
                    continue;
                }
                if (!growable(level.getBlockState(b))) found.remove(idx);
                else idx++;
                done++;
                apps--;
            }
            if (apps > 0 && !found.isEmpty()) return false;
            pet.workBank = Math.max(0, pet.workBank - rounds * every);
            if (found.isEmpty()) pet.workBank %= every;   // everything is grown: the rest of the time had nothing to do
            return finish(done, "work.result-crops");
        }

        private boolean bloom() {
            long max = Math.min(rounds, job.maxPerRun());
            for (BlockPos b : found) {
                if (done >= max) break;
                if (loaded(level, b.getX(), b.getZ()) && bloomable(level, b, level.getBlockState(b)) && boneMeal(level, b)) done++;
            }
            pet.workBank %= every;
            return finish(done, "work.result-bloom");
        }

        private boolean items() {
            BlockPos spot = new BlockPos(cx, cy, cz);
            if (job.need() == Need.WATER && !nearWater(level, spot)) return waiting("needs-water");
            if (job.need() == Need.GROUND && !naturalGround(level, spot)) return waiting("needs-ground");
            Container inv = container(level, cx, cy, cz, pet.owner, radius);
            if (inv == null) return waiting("no-container");
            ThreadLocalRandom r = ThreadLocalRandom.current();
            long paid = 0;
            boolean full = false;
            for (long i = 0; i < rounds && i < 4096; i++) {
                paid++;
                if (job.eats() > 0 && r.nextDouble() < job.eats()) continue;   // it ate this one
                Loot l = roll(job.loot(), r);
                if (l == null) continue;
                int amount = l.min() + (l.max() > l.min() ? r.nextInt(l.max() - l.min() + 1) : 0);
                ItemStack left = PetUtil.add(inv, new ItemStack(l.item(), amount));
                done += amount - left.getCount();
                if (!left.isEmpty()) {
                    full = true;
                    break;
                }
            }
            pet.workBank = Math.max(0, pet.workBank - paid * every);
            if (full) {
                waiting("container-full");
                return report(done, "work.result-items");
            }
            return finish(done, "work.result-items");
        }

        private boolean waiting(String why) {
            pet.workWaiting = why;
            m.store().markDirty();
            return true;
        }

        private boolean finish(int amount, String resultKey) {
            pet.workWaiting = null;
            m.store().markDirty();
            return report(amount, resultKey);
        }

        private boolean report(int amount, String resultKey) {
            // the spent work time goes to disk right away, so a crash can't pay the same catch-up twice
            if (amount > 0) m.store().journal(pet);
            boolean tell = report || pet.workReport;
            pet.workReport = false;
            if (!tell || amount <= 0) return true;
            ServerPlayer o = m.feather().player(pet.owner);
            if (o != null) m.send(o, "work.caught-up", m.petTags(pet), Text.component("result", m.msg(resultKey, Text.unparsed("count", amount))));
            return true;
        }
    }

    private static Loot roll(List<Loot> loot, ThreadLocalRandom r) {
        double total = 0;
        for (Loot l : loot) total += l.weight();
        if (total <= 0) return null;
        double x = r.nextDouble() * total;
        for (Loot l : loot) {
            x -= l.weight();
            if (x < 0) return l;
        }
        return loot.get(loot.size() - 1);
    }

    // ------------------------------------------------------------------ blocks

    /** A block whose chunk is loaded (reading one in an unloaded chunk would load it). */
    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().hasChunk(x >> 4, z >> 4);
    }

    private static boolean boneMeal(ServerLevel level, BlockPos pos) {
        boolean grew = BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), level, pos);
        if (grew) level.levelEvent(1505, pos, 15);   // the bone meal sparkles
        return grew;
    }

    /** An "age" property's value and maximum, or null. */
    private static int[] age(BlockState s) {
        for (Property<?> p : s.getProperties()) {
            if (p instanceof IntegerProperty ip && p.getName().equals("age")) {
                int max = ip.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                return new int[]{s.getValue(ip), max};
            }
        }
        return null;
    }

    /** Crops bone meal grows that aren't fully grown. Saplings are left alone (no trees appear while you're away). */
    private static boolean growable(BlockState s) {
        if (s.is(Blocks.TORCHFLOWER_CROP) || s.is(Blocks.PITCHER_CROP)) return false;
        if (!s.is(BlockTags.CROPS) && !s.is(Blocks.SWEET_BERRY_BUSH) && !s.is(Blocks.COCOA)) return false;
        int[] a = age(s);
        return a != null && a[0] < a[1];
    }

    private static boolean vanillaRipeType(BlockState s) {
        return s.is(Blocks.WHEAT) || s.is(Blocks.CARROTS) || s.is(Blocks.POTATOES) || s.is(Blocks.BEETROOTS) || s.is(Blocks.NETHER_WART);
    }

    /** Vanilla crops at full age (wheat, carrots, potatoes, beetroot, nether wart), or a ripe crop the hook knows. */
    private static boolean ripe(ServerLevel level, BlockPos pos, BlockState s, Hooks.Farmhand h) {
        if (vanillaRipeType(s)) {
            int[] a = age(s);
            return a != null && a[0] >= a[1];
        }
        try {
            return h != null && h.ripe(level, pos);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Harvests a ripe crop and replants it (one seed from the drops); null when it isn't ripe any more. */
    private static List<ItemStack> harvestRipe(ServerLevel level, BlockPos pos, Hooks.Farmhand h) {
        BlockState s = level.getBlockState(pos);
        if (!ripe(level, pos, s, h)) return null;
        if (!vanillaRipeType(s)) return h == null ? null : h.harvest(level, pos);
        Item seed = s.is(Blocks.WHEAT) ? Items.WHEAT_SEEDS : s.is(Blocks.CARROTS) ? Items.CARROT : s.is(Blocks.POTATOES) ? Items.POTATO
                : s.is(Blocks.BEETROOTS) ? Items.BEETROOT_SEEDS : Items.NETHER_WART;
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(s, level, pos, null));
        boolean replanted = false;
        for (ItemStack d : drops) {
            if (d.is(seed) && d.getCount() > 0) {
                d.shrink(1);
                replanted = true;
                break;
            }
        }
        if (replanted) {
            for (Property<?> p : s.getProperties()) {
                if (p instanceof IntegerProperty ip && p.getName().equals("age")) {
                    level.setBlock(pos, s.setValue(ip, 0), Block.UPDATE_CLIENTS);
                    break;
                }
            }
        } else {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        drops.removeIf(ItemStack::isEmpty);
        return drops;
    }

    private static boolean bloomable(ServerLevel level, BlockPos pos, BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) && level.getBlockState(pos.above()).isAir();
    }

    /** At least 3 water blocks within 3 blocks of the spot. */
    private static boolean nearWater(ServerLevel level, BlockPos spot) {
        int n = 0;
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (!loaded(level, spot.getX() + x, spot.getZ() + z)) continue;
                for (int y = -2; y <= 1; y++) {
                    if (level.getFluidState(spot.offset(x, y, z)).is(FluidTags.WATER) && ++n >= 3) return true;
                }
            }
        }
        return false;
    }

    /** The forager digs on natural ground: the dirt family, sand, gravel, clay or overworld stone under it. */
    private static boolean naturalGround(ServerLevel level, BlockPos spot) {
        BlockState at = level.getBlockState(spot);
        BlockState t = at.isSolid() ? at : level.getBlockState(spot.below());
        // 26.x: minecraft:dirt no longer has grass, podzol, mycelium, moss or mud; substrate_overworld has them all
        return t.is(BlockTags.SUBSTRATE_OVERWORLD) || t.is(BlockTags.SAND) || t.is(BlockTags.BASE_STONE_OVERWORLD)
                || t.is(Blocks.GRAVEL) || t.is(Blocks.CLAY) || t.is(Blocks.DRIPSTONE_BLOCK) || t.is(Blocks.CALCITE);
    }

    /** The nearest chest or barrel in range (loaded chunks only; bank chests and others' locks are skipped). */
    private Container container(ServerLevel level, int x, int y, int z, UUID owner, int radius) {
        double best = Double.MAX_VALUE;
        Container out = null;
        double r2 = (radius + 0.5) * (radius + 0.5);
        for (int chx = (x - radius) >> 4; chx <= (x + radius) >> 4; chx++) {
            for (int chz = (z - radius) >> 4; chz <= (z + radius) >> 4; chz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(chx, chz);
                if (c == null) continue;
                for (BlockEntity be : new ArrayList<>(c.getBlockEntities().values())) {
                    if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) continue;
                    BlockPos pos = be.getBlockPos();
                    int dy = pos.getY() - y;
                    if (Math.abs(dy) > vertical + 1) continue;
                    double d = sq(pos.getX() - x) + sq(pos.getZ() - z) + sq(dy);
                    if (d > r2 || d >= best) continue;
                    if (m.feather().isBankBlock(level, pos)) continue;
                    if (m.feather().lockedAgainst(level, pos, owner)) continue;
                    if (!m.feather().claimAllows(owner, Loc.of(level, pos), ClaimAction.CONTAINER)) continue;
                    Container inv;
                    BlockState s = be.getBlockState();
                    if (be instanceof ChestBlockEntity && s.getBlock() instanceof ChestBlock cb) inv = ChestBlock.getContainer(cb, s, level, pos, true);
                    else inv = (Container) be;
                    if (inv == null) continue;
                    best = d;
                    out = inv;
                }
            }
        }
        return out;
    }

    private static double sq(double d) {
        return d * d;
    }

    // ------------------------------------------------------------------ menu text

    Component describe(Pet p) {
        Job j = jobs.get(p.species);
        if (j == null || j.description() == null || j.description().isBlank()) return m.msg("work.no-offline-job");
        return m.msg("work.offline-job", Text.unparsed("job", j.description()));
    }

    /** {@code <x> <y> <z> <world> <waiting> <radius>} for the menus. */
    TagResolver tags(Pet p) {
        Pet.Spot s = p.work;
        Component waiting = p.workWaiting == null ? Component.empty() : m.msg("work.waiting." + p.workWaiting);
        return TagResolver.resolver(Text.unparsed("x", s == null ? "?" : (int) Math.floor(s.x())),
                Text.unparsed("y", s == null ? "?" : (int) Math.floor(s.y())),
                Text.unparsed("z", s == null ? "?" : (int) Math.floor(s.z())),
                Text.unparsed("world", m.worldName(s)), Text.component("waiting", waiting),
                Text.unparsed("radius", radius));
    }

    List<String> info(UUID owner) {
        List<String> out = new ArrayList<>();
        for (Pet p : m.store().of(owner)) {
            if (!p.working()) continue;
            Job j = jobs.get(p.species);
            out.add(m.displayName(p) + ": " + (m.entity(p) != null ? "loaded" : "unloaded") + ", banked "
                    + (p.workBank / 60_000) + " min, job " + (j == null ? "none" : j.type().name().toLowerCase(Locale.ROOT))
                    + (p.workWaiting == null ? "" : ", waiting: " + p.workWaiting));
        }
        return out;
    }
}
