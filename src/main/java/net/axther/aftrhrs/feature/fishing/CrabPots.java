package net.axther.aftrhrs.feature.fishing;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.net.Inspect;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crab pots ({@code arcana_crab_pot} furniture). Each pot is a record in its chunk's {@link Pdc}
 * ({@code feather:crab_pots}: position, owner, next roll time, what it holds; the same string Paper kept in the chunk
 * PDC, so migrated pots keep their catch). Timers are wall-clock times: whenever a pot is looked at (clicked, or near
 * a player for the bubbles) the rolls it is owed are made, one shellfish every 6-10 minutes while it sits in water, up
 * to 5 held. Collecting needs container trust in a claim and counts every shellfish as a catch for quests.
 */
final class CrabPots {
    static final String KEY = "feather:crab_pots";

    static final class Pot {
        final ResourceKey<Level> world;
        final int x, y, z;
        UUID owner;
        long next;
        final List<String> stored = new ArrayList<>();

        Pot(ResourceKey<Level> world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        long key() {
            return ((long) x & 0x7FFFFFFL) | (((long) z & 0x7FFFFFFL) << 27) | ((long) y << 54);
        }

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    private final FishingModule m;
    private final Map<ResourceKey<Level>, Map<Long, Map<Long, Pot>>> pots = new HashMap<>();
    private Tasks.Task task;

    private long minMs = 6 * 60_000L, maxMs = 10 * 60_000L;
    private int cap = 5;
    private List<FishingModule.Weighted> catchTable = List.of();
    private int bubbleDistance = 24;

    CrabPots(FishingModule m) {
        this.m = m;
    }

    static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.crabPots.load(level, chunk);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            FishingModule m = FishingModule.active;
            if (m == null) return;
            Map<Long, Map<Long, Pot>> w = m.crabPots.pots.get(level.dimension());
            if (w != null) w.remove(chunk.getPos().pack());
        });
    }

    void reload(Yaml y) {
        minMs = (long) (Math.max(0.05, y.getDouble("crab-pots.min-minutes", 6)) * 60_000L);
        maxMs = Math.max(minMs, (long) (Math.max(0.05, y.getDouble("crab-pots.max-minutes", 10)) * 60_000L));
        cap = Math.max(1, Math.min(27, y.getInt("crab-pots.capacity", 5)));
        bubbleDistance = Math.max(4, y.getInt("crab-pots.bubble-distance", 24));
        List<FishingModule.Weighted> list = new ArrayList<>();
        for (String line : y.getStringList("crab-pots.catch")) {
            String[] p = line.trim().split("\\s+");
            try {
                list.add(new FishingModule.Weighted(p[0].toLowerCase(java.util.Locale.ROOT), 1, 1, p.length > 1 ? Double.parseDouble(p[1]) : 1));
            } catch (RuntimeException e) {
                m.warnOnce("fishing.yml crab-pots.catch: can't read '" + line + "' (use: id weight).");
            }
        }
        if (list.isEmpty()) {
            list.add(new FishingModule.Weighted("arcana_shellfish_crab", 1, 1, 45));
            list.add(new FishingModule.Weighted("arcana_shellfish_shrimp", 1, 1, 35));
            list.add(new FishingModule.Weighted("arcana_shellfish_clam", 1, 1, 20));
        }
        catchTable = List.copyOf(list);
    }

    void start() {
        pots.clear();
        var server = Feather.serverOrNull();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                List<LevelChunk> chunks = new ArrayList<>();
                level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunks::add);
                for (LevelChunk c : chunks) load(level, c);
            }
        }
        task = Tasks.timer(100L, 100L, this::tick);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        pots.clear();
    }

    // ------------------------------------------------------------------ chunk Pdc

    private Map<Long, Pot> chunk(ResourceKey<Level> world, int cx, int cz, boolean create) {
        Map<Long, Map<Long, Pot>> w = pots.get(world);
        if (w == null) {
            if (!create) return null;
            w = new HashMap<>();
            pots.put(world, w);
        }
        long key = ChunkPos.pack(cx, cz);
        Map<Long, Pot> c = w.get(key);
        if (c == null && create) {
            c = new LinkedHashMap<>();
            w.put(key, c);
        }
        return c;
    }

    private void load(ServerLevel level, LevelChunk chunk) {
        String raw = Pdc.getString(chunk, KEY);
        if (raw == null || raw.isEmpty()) return;
        Map<Long, Pot> c = chunk(level.dimension(), chunk.getPos().x(), chunk.getPos().z(), true);
        for (String entry : raw.split(";")) {
            String[] f = entry.split(",", -1);
            try {
                Pot p = new Pot(level.dimension(), Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                p.owner = f[3].isEmpty() ? null : UUID.fromString(f[3]);
                p.next = Long.parseLong(f[4]);
                if (f.length > 5 && !f[5].isEmpty()) for (String s : f[5].split("\\+")) p.stored.add(s);
                c.put(p.key(), p);
            } catch (RuntimeException e) {
                m.plugin().getLogger().warn("Fishing: skipping a broken crab pot entry in chunk {}: {}", chunk.getPos(), entry);
            }
        }
    }

    private void save(ServerLevel level, int cx, int cz) {
        if (level == null) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return;
        Map<Long, Pot> c = chunk(level.dimension(), cx, cz, false);
        if (c == null || c.isEmpty()) {
            Pdc.remove(chunk, KEY);
        } else {
            StringBuilder b = new StringBuilder();
            for (Pot p : c.values()) {
                if (!b.isEmpty()) b.append(';');
                b.append(p.x).append(',').append(p.y).append(',').append(p.z).append(',').append(p.owner == null ? "" : p.owner.toString())
                        .append(',').append(p.next).append(',').append(String.join("+", p.stored));
            }
            Pdc.putString(chunk, KEY, b.toString());
        }
        chunk.markUnsaved();
    }

    private ServerLevel level(Pot p) {
        var server = Feather.serverOrNull();
        return server == null ? null : server.getLevel(p.world);
    }

    private void save(Pot p) {
        save(level(p), p.x >> 4, p.z >> 4);
    }

    Pot at(ServerLevel level, BlockPos b) {
        Map<Long, Pot> c = chunk(level.dimension(), b.getX() >> 4, b.getZ() >> 4, false);
        if (c == null) return null;
        return c.get(new Pot(level.dimension(), b.getX(), b.getY(), b.getZ()).key());
    }

    private Pot create(ServerLevel level, BlockPos b, UUID owner) {
        Pot p = new Pot(level.dimension(), b.getX(), b.getY(), b.getZ());
        p.owner = owner;
        p.next = System.currentTimeMillis() + interval();
        chunk(p.world, b.getX() >> 4, b.getZ() >> 4, true).put(p.key(), p);
        save(p);
        return p;
    }

    private void forget(Pot p) {
        Map<Long, Pot> c = chunk(p.world, p.x >> 4, p.z >> 4, false);
        if (c != null) c.remove(p.key());
        save(p);
    }

    private long interval() {
        return minMs + (maxMs > minMs ? ThreadLocalRandom.current().nextLong(maxMs - minMs + 1) : 0);
    }

    // ------------------------------------------------------------------ furniture hooks (from FishFurniture)

    void placed(ServerLevel level, BlockPos b, ServerPlayer placer) {
        Pot p = at(level, b);
        if (p == null) create(level, b, placer == null ? null : placer.getUUID());
        else if (placer != null) {
            p.owner = placer.getUUID();
            save(p);
        }
        if (placer != null && !inWater(level, b)) m.plugin().text().send(placer, "fishing.crabpot.needs-water");
    }

    /** The pot went: what it held drops where it stood, and its record is forgotten. */
    void broken(ServerLevel level, BlockPos b) {
        Vec3 where = Vec3.atCenterOf(b);
        for (ItemStack s : takeAll(level, b)) Coast.drop(level, where, s);
    }

    List<ItemStack> takeAll(ServerLevel level, BlockPos b) {
        List<ItemStack> out = new ArrayList<>();
        Pot p = at(level, b);
        if (p == null) return out;
        catchUp(level, p, System.currentTimeMillis());
        for (String id : p.stored) {
            FishTable.Species sp = species(id);
            ItemStack s = sp != null ? m.items.fish(sp, m.table.rollGrams(sp, 0, 0), null, null) : m.items.other(id, 1);
            if (s != null) out.add(s);
        }
        forget(p);
        return out;
    }

    /** In the water: the pot's block holds water (waterlogged), or water touches it from a side or above. */
    static boolean inWater(ServerLevel level, BlockPos b) {
        if (level.getFluidState(b).is(FluidTags.WATER)) return true;
        for (Direction f : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (level.getFluidState(b.relative(f)).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** The species a pot catch id stands for ({@code arcana_fish_<species>}), or null for a plain item. */
    FishTable.Species species(String id) {
        return id != null && id.startsWith("arcana_fish_") ? m.table.species.get(id.substring(12)) : null;
    }

    /**
     * The catch table for a pot here: plain items always; a species only where it lives (its water, biome groups and
     * climate), so a pot in the sea doesn't fill with crawfish.
     */
    List<FishingModule.Weighted> tableFor(ServerLevel level, BlockPos pos) {
        boolean any = false;
        for (FishingModule.Weighted w : catchTable) if (species(w.id()) != null) any = true;
        if (!any) return catchTable;
        var groups = m.table.groupsOf(Coast.biomeKey(level, pos));
        boolean salt = m.table.salt(groups);
        List<FishingModule.Weighted> out = new ArrayList<>();
        for (FishingModule.Weighted w : catchTable) {
            FishTable.Species sp = species(w.id());
            if (sp == null || m.table.habitat(sp, groups, salt, false)) out.add(w);
        }
        return out;
    }

    boolean catchUp(ServerLevel level, Pot p, long now) {
        if (!inWater(level, p.pos())) {
            if (p.next < now) {
                p.next = now + interval();
                return true;
            }
            return false;
        }
        boolean changed = false;
        int guard = 0;
        List<FishingModule.Weighted> table = tableFor(level, p.pos());
        while (p.stored.size() < cap && now >= p.next && guard++ < 64) {
            String id = m.pickWeighted(table);
            if (id != null) p.stored.add(id);
            p.next += interval();
            changed = true;
        }
        if (p.stored.size() >= cap && p.next < now) p.next = now;
        return changed;
    }

    boolean click(ServerPlayer p, ServerLevel level, BlockPos b, String furnitureId) {
        Pot pot = at(level, b);
        if (pot == null) pot = create(level, b, null);
        Text text = m.plugin().text();
        if (!m.furniture.allowed(p, level, b)) return true;
        long now = System.currentTimeMillis();
        if (catchUp(level, pot, now)) save(pot);
        if (pot.stored.isEmpty()) {
            if (!inWater(level, b)) text.send(p, "fishing.crabpot.needs-water");
            else text.actionBar(p, "fishing.crabpot.empty", Text.unparsed("time", FishItems.duration(Math.max(0, pot.next - now))));
            return true;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : pot.stored) counts.merge(id, 1, Integer::sum);
        boolean wasFull = pot.stored.size() >= cap;
        pot.stored.clear();
        if (wasFull || pot.next <= now) pot.next = now + interval();
        save(pot);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            FishTable.Species sp = species(e.getKey());
            if (sp != null) {
                // a species (crawfish, mitten crab): real fish, each weighed, into the journal like a rod catch
                for (int i = 0; i < e.getValue(); i++) {
                    int grams = m.table.rollGrams(sp, 0, 0);
                    Coast.give(p, m.items.fish(sp, grams, p.getUUID(), net.axther.aftrhrs.core.Names.name(p)));
                    m.landed(p, sp, grams);
                }
                parts.add(e.getValue() + "× " + sp.name());
                m.countCatch(p, sp.oraxenId(), e.getValue());
                continue;
            }
            ItemStack s = m.items.other(e.getKey(), e.getValue());
            if (s == null) {
                m.warnOnce("Fishing: crab pot catch '" + e.getKey() + "' is not an item and has no stand-in (items.fallback).");
                continue;
            }
            Coast.give(p, s);
            parts.add(e.getValue() + "× " + FishTable.pretty(e.getKey().replaceFirst("^arcana_(shellfish_)?", "")));
            m.countCatch(p, e.getKey(), e.getValue());
        }
        text.send(p, "fishing.crabpot.collected", Text.unparsed("catch", String.join(", ", parts)));
        m.playSound(level, Vec3.atCenterOf(b), "entity.fishing_bobber.retrieve 0.8 0.9");
        m.playSound(level, Vec3.atCenterOf(b), "entity.player.splash 0.4 1.3");
        return true;
    }

    // ------------------------------------------------------------------ the look-at card

    /**
     * Crab pots for the look-at card ({@code inspect}): what a click on this pot would say, without collecting. The
     * rolls it is owed are made first (as a click or a nearby player does), then how many it holds, or when the next
     * catch comes. Needs the same container access as collecting (silently: no message). Null when the block isn't a
     * crab pot.
     */
    JsonObject inspect(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state) {
        String id = ArcanaContent.id(state);
        if (id == null || !ArcanaContent.isFurniture(state) || m.furniture.kindOf(id) != FishFurniture.Kind.POT) return null;
        BlockPos b = ArcanaContent.origin(state, pos);
        if (!Coast.allowed(p, level, b, ClaimAction.CONTAINER)) return Inspect.none();
        Pot pot = at(level, b);
        long now = System.currentTimeMillis();
        if (pot != null && catchUp(level, pot, now)) save(pot);
        JsonObject card = Inspect.card("crabpot", FishTable.pretty(id.replaceFirst("^arcana_", "")), m.items.other(id, 1));
        String caught = plain("fishing.crabpot.inspect-caught");
        if (pot == null) {
            // No record yet (a click would create one): empty, or out of the water as the click would say.
            if (!inWater(level, b)) Inspect.text(card, plain("fishing.crabpot.needs-water"), Inspect.ROSE);
            else Inspect.text(card, plain("fishing.crabpot.inspect-empty"), Inspect.MUTED);
        } else if (!pot.stored.isEmpty()) {
            Inspect.count(card, pot.stored.size(), cap, caught);
        } else if (!inWater(level, b)) {
            Inspect.text(card, plain("fishing.crabpot.needs-water"), Inspect.ROSE);
        } else {
            Inspect.text(card, plain("fishing.crabpot.inspect-empty"), Inspect.MUTED);
            Inspect.timer(card, Math.max(0, pot.next - now), plain("fishing.crabpot.inspect-next"));
        }
        return card;
    }

    /** A message as plain text, without the chat prefix. */
    private String plain(String key) {
        Text text = m.plugin().text();
        return Inspect.plain(text.parse(text.raw(key).replace("<prefix>", "")));
    }

    /** Every 5 s: pots near players catch up and, holding something, show a few bubbles. */
    private void tick() {
        long now = System.currentTimeMillis();
        double r2 = (double) bubbleDistance * bubbleDistance;
        for (Map.Entry<ResourceKey<Level>, Map<Long, Map<Long, Pot>>> we : pots.entrySet()) {
            var server = Feather.serverOrNull();
            ServerLevel w = server == null ? null : server.getLevel(we.getKey());
            if (w == null || w.players().isEmpty()) continue;
            for (Map<Long, Pot> c : we.getValue().values()) {
                for (Pot p : c.values()) {
                    Vec3 at = new Vec3(p.x + 0.5, p.y + 0.6, p.z + 0.5);
                    boolean near = false;
                    for (ServerPlayer pl : w.players()) {
                        if (pl.position().distanceToSqr(at) <= r2) {
                            near = true;
                            break;
                        }
                    }
                    if (!near) continue;
                    if (catchUp(w, p, now)) save(p);
                    if (!p.stored.isEmpty()) w.sendParticles(ParticleTypes.BUBBLE_POP, at.x, at.y, at.z, Math.min(6, 1 + p.stored.size()), 0.25, 0.2, 0.25, 0.01);
                }
            }
        }
    }

    // ------------------------------------------------------------------ debug

    int fastForward(ServerPlayer p, double radius, int rolls) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (Map<Long, Pot> c : pots.getOrDefault(p.level().dimension(), Map.of()).values()) {
            for (Pot pot : c.values()) {
                if (Vec3.atCenterOf(pot.pos()).distanceToSqr(p.position()) > radius * radius) continue;
                pot.next = now - (rolls - 1) * maxMs - 1;
                save(pot);
                n++;
            }
        }
        return n;
    }

    int forgetNear(ServerLevel level, Vec3 at, double radius) {
        int n = 0;
        for (Map<Long, Pot> c : pots.getOrDefault(level.dimension(), Map.of()).values()) {
            for (Pot pot : new ArrayList<>(c.values())) {
                if (Vec3.atCenterOf(pot.pos()).distanceToSqr(at) > radius * radius) continue;
                forget(pot);
                n++;
            }
        }
        return n;
    }

    List<String> describe(ServerLevel level) {
        List<String> out = new ArrayList<>();
        for (Map<Long, Pot> c : pots.getOrDefault(level.dimension(), Map.of()).values()) {
            for (Pot p : c.values()) {
                out.add("POT @" + p.x + "," + p.y + "," + p.z + " owner=" + (p.owner == null ? "-" : FishItems.nameOf(p.owner)) + " stored=" + p.stored
                        + " nextIn=" + (p.next - System.currentTimeMillis()) / 1000 + "s water=" + inWater(level, p.pos()));
            }
        }
        return out;
    }
}
