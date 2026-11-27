package net.axther.aftrhrs.feature.wildlife;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.net.Inspect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The furniture stations (wildlife SPEC section 5), now native furniture blocks claimed through
 * {@link ArcanaContent#hook}. Each station's state lives in its chunk's {@link Pdc} ({@code feather:wild_stations},
 * one compound per origin block "x,y,z" with the old furniture PDC key names):
 * <ul>
 *   <li>butter churn: a milk bucket in, butter out after {@code stations.churn.seconds};</li>
 *   <li>cheese press: 3 milk buckets, a cheese wheel after {@code stations.cheese-press.minutes}; a wheel clicked on the
 *       press cuts into 4 wedges;</li>
 *   <li>silk frame: Loomworm silk piles up (max 8), right-click to collect;</li>
 *   <li>nesting box: Pondwaddle eggs land in it (max {@code stations.nesting-box.max}), right-click to collect;</li>
 *   <li>feeding trough: holds up to 64 of one food and keeps fed livestock within 6 blocks fed.</li>
 * </ul>
 * A small floating counter (a text display, removed and rebuilt when the chunk loads) shows what the frame, box and
 * trough hold. Breaking a station drops what it held.
 */
final class Stations {
    static final String KEY = WildlifeModule.NS + "wild_stations";
    static final String CHURN_AT = "wild_churn_at", CHURN_BY = "wild_churn_by", BUTTER = "wild_butter", PRESS_MILK = "wild_press_milk",
            PRESS_AT = "wild_press_at", SILK = "wild_silk", SILK_ITEM = "wild_silk_item", EGGS = "wild_eggs", EGG_ITEM = "wild_egg_item",
            TROUGH_ITEM = "wild_trough_item", TROUGH_COUNT = "wild_trough_count", LABEL = "wild_label";

    private final WildlifeModule m;
    private final Map<String, Display.TextDisplay> labels = new HashMap<>();

    String churnId = "arcana_butter_churn", pressId = "arcana_cheese_press", frameId = "arcana_silk_frame",
            nestId = "arcana_nesting_box", troughId = "arcana_feeding_trough";
    private long churnMs = 5000, pressMs = 600_000;
    private String butter = "arcana_butter", wheel = "arcana_cheese_wheel", wedge = "arcana_cheese_wedge";
    private int pressMilk = 3, wedges = 4, nestMax = 16, troughMax = 64;
    double troughRadius = 6;
    private String frameCredits = "ArcanaLoomworm", nestCredits = "ArcanaPondwaddle";
    private boolean showLabels = true;
    private double labelHeight = 1.25;
    /** Everything a trough accepts: every species' breed and fed food. */
    private Match troughFood = Match.NONE;

    Stations(WildlifeModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        churnId = cfg.getString("stations.churn.id", churnId);
        churnMs = WildSpecies.seconds(cfg.getDouble("stations.churn.seconds", 5));
        butter = cfg.getString("stations.churn.output", butter);
        pressId = cfg.getString("stations.cheese-press.id", pressId);
        pressMilk = Math.max(1, cfg.getInt("stations.cheese-press.milk", 3));
        pressMs = WildSpecies.minutes(cfg.getDouble("stations.cheese-press.minutes", 10));
        wheel = cfg.getString("stations.cheese-press.output", wheel);
        wedge = cfg.getString("stations.cheese-press.wedge", wedge);
        wedges = Math.max(1, cfg.getInt("stations.cheese-press.wedges", 4));
        frameId = cfg.getString("stations.silk-frame.id", frameId);
        frameCredits = cfg.getString("stations.silk-frame.credits", frameCredits);
        nestId = cfg.getString("stations.nesting-box.id", nestId);
        nestMax = Math.max(1, cfg.getInt("stations.nesting-box.max", 16));
        nestCredits = cfg.getString("stations.nesting-box.credits", nestCredits);
        troughId = cfg.getString("stations.feeding-trough.id", troughId);
        troughMax = Math.max(1, cfg.getInt("stations.feeding-trough.max", 64));
        troughRadius = Math.max(1, cfg.getDouble("stations.feeding-trough.radius", 6));
        m.harvests.reload(WildSpecies.seconds(Math.max(5, cfg.getDouble("stations.feeding-trough.check-seconds", 30))));
        showLabels = cfg.getBoolean("stations.labels", true);
        labelHeight = cfg.getDouble("stations.label-height", 1.25);
        List<String> foods = new ArrayList<>();
        for (WildSpecies s : m.species.values()) {
            if (s.breedFood != null) foods.addAll(s.breedFood.raw());
            if (s.fed != null) foods.addAll(s.fed.food().raw());
        }
        troughFood = new Match(foods);
        if (!labels.isEmpty()) Tasks.later(1, this::relabelAll);
    }

    boolean isStation(String id) {
        return id != null && (id.equals(churnId) || id.equals(pressId) || id.equals(frameId) || id.equals(nestId) || id.equals(troughId));
    }

    // ------------------------------------------------------------------ state in the chunk Pdc

    static String posKey(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    static BlockPos parse(String key) {
        String[] p = key.split(",");
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CompoundTag all(LevelChunk chunk) {
        CompoundTag pdc = chunk.getAttached(Pdc.DATA);
        return pdc == null ? new CompoundTag() : pdc.getCompoundOrEmpty(KEY);
    }

    /** A copy of the station's state, or null when the chunk holds none for it. */
    static CompoundTag read(ServerLevel level, BlockPos origin) {
        CompoundTag t = all(level.getChunkAt(origin)).getCompound(posKey(origin)).orElse(null);
        return t == null ? null : t.copy();
    }

    static void write(ServerLevel level, BlockPos origin, CompoundTag state) {
        LevelChunk chunk = level.getChunkAt(origin);
        CompoundTag stations = all(chunk).copy();
        if (state == null) stations.remove(posKey(origin));
        else stations.put(posKey(origin), state);
        Pdc.putTag(chunk, KEY, stations.isEmpty() ? null : stations);
    }

    /** The state of the station at origin; a fresh one (id only) when none was saved yet. */
    private CompoundTag state(ServerLevel level, BlockPos origin, String id) {
        CompoundTag t = read(level, origin);
        if (t == null) {
            t = new CompoundTag();
            t.putString("id", id);
        }
        return t;
    }

    private static long l(CompoundTag t, String k) {
        return t.getLong(k).orElse(0L);
    }

    private static int i(CompoundTag t, String k) {
        return t.getInt(k).orElse(0);
    }

    private static String s(CompoundTag t, String k) {
        return t.getString(k).orElse(null);
    }

    private static void setL(CompoundTag t, String k, long v) {
        if (v == 0) t.remove(k);
        else t.putLong(k, v);
    }

    private static void setI(CompoundTag t, String k, int v) {
        if (v == 0) t.remove(k);
        else t.putInt(k, v);
    }

    private static void setS(CompoundTag t, String k, String v) {
        if (v == null) t.remove(k);
        else t.putString(k, v);
    }

    /** Whether a station with this id still stands at origin. */
    private boolean stands(ServerLevel level, BlockPos origin, String id) {
        return level.isLoaded(origin) && id != null && id.equals(ArcanaContent.id(level.getBlockState(origin)));
    }

    /** One station near a spot. */
    private record Near(BlockPos pos, CompoundTag state) {
    }

    /** The nearest station of this id within {@code r} blocks whose state passes {@code ok}. */
    private Near near(ServerLevel level, Vec3 at, double r, String id, Predicate<CompoundTag> ok) {
        int minCx = (int) Math.floor(at.x - r) >> 4, maxCx = (int) Math.floor(at.x + r) >> 4;
        int minCz = (int) Math.floor(at.z - r) >> 4, maxCz = (int) Math.floor(at.z + r) >> 4;
        Near best = null;
        double bestD = Double.MAX_VALUE;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                CompoundTag stations = all(chunk);
                for (String k : stations.keySet()) {
                    CompoundTag t = stations.getCompoundOrEmpty(k);
                    if (!id.equals(s(t, "id"))) continue;
                    BlockPos p = parse(k);
                    if (p == null) continue;
                    double d = Vec3.atBottomCenterOf(p).distanceToSqr(at);
                    if (d > r * r || d >= bestD || !ok.test(t) || !stands(level, p, id)) continue;
                    best = new Near(p, t.copy());
                    bestD = d;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the furniture hook

    /** Claims clicks, placing and removal of the stations (registered once; asks the running module). */
    static final class Hook implements FurnitureHook {
        @Override
        public InteractionResult onUse(FurnitureContext ctx) {
            WildlifeModule m = WildlifeModule.active();
            if (m == null || !(ctx.player() instanceof ServerPlayer p)) return InteractionResult.PASS;
            if (ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
            m.stations.use(p, ctx.level(), ctx.origin(), ctx.id());
            return InteractionResult.SUCCESS;
        }

        @Override
        public void onPlaced(FurnitureContext ctx) {
            WildlifeModule m = WildlifeModule.active();
            if (m == null) return;
            CompoundTag t = new CompoundTag();
            t.putString("id", ctx.id());
            write(ctx.level(), ctx.origin(), t);
        }

        @Override
        public void onRemoved(FurnitureContext ctx) {
            WildlifeModule m = WildlifeModule.active();
            if (m != null) m.stations.removed(ctx.level(), ctx.origin());
        }
    }

    private void use(ServerPlayer p, ServerLevel level, BlockPos origin, String id) {
        if (!m.mayUse(p, level, origin, ClaimAction.CONTAINER)) return;
        InteractionHand hand = InteractionHand.MAIN_HAND;
        ItemStack held = p.getItemInHand(hand);
        String oid = ArcanaContent.id(held);
        CompoundTag t = state(level, origin, id);
        if (id.equals(churnId)) churn(p, level, origin, t, held, oid, hand);
        else if (id.equals(pressId)) press(p, level, origin, t, held, oid, hand);
        else if (id.equals(frameId)) collect(p, level, origin, t, SILK, SILK_ITEM, "frame", frameCredits);
        else if (id.equals(nestId)) collect(p, level, origin, t, EGGS, EGG_ITEM, "nest", nestCredits);
        else if (id.equals(troughId)) trough(p, level, origin, t, held, oid, hand);
    }

    private void churn(ServerPlayer p, ServerLevel level, BlockPos origin, CompoundTag t, ItemStack held, String oid, InteractionHand hand) {
        long now = System.currentTimeMillis();
        finishChurn(level, origin, t, now);
        int ready = i(t, BUTTER);
        if (ready > 0) {
            ItemStack out = m.item(butter, ready);
            if (out == null) return;
            setI(t, BUTTER, 0);
            write(level, origin, t);
            WildlifeModule.give(p, out);
            m.sound(p, "station");
            m.bar(p, "station.churn-collected", WildlifeModule.tag("count", ready));
            return;
        }
        long at = l(t, CHURN_AT);
        if (at > 0) {
            m.bar(p, "station.churning", WildlifeModule.tag("time", WildlifeModule.time(at - now)));
            return;
        }
        if (oid != null || !held.is(Items.MILK_BUCKET)) {
            m.bar(p, "station.churn-idle");
            return;
        }
        WildlifeModule.useOne(p, hand, new ItemStack(Items.BUCKET));
        setL(t, CHURN_AT, now + churnMs);
        setS(t, CHURN_BY, p.getUUID().toString());
        write(level, origin, t);
        m.bar(p, "station.churn-start", WildlifeModule.tag("time", WildlifeModule.time(churnMs)));
        m.soundAt(level, Vec3.atCenterOf(origin), "churn");
        Tasks.later(Math.max(1, churnMs / 50) + 1, () -> {
            if (!stands(level, origin, churnId)) return;
            CompoundTag s = read(level, origin);
            if (s == null) return;
            finishChurn(level, origin, s, System.currentTimeMillis());
            // the churner gets it straight away when still close by; otherwise it waits in the churn
            String by = s(s, CHURN_BY);
            ServerPlayer owner = null;
            try {
                owner = by == null ? null : Feather.get().player(java.util.UUID.fromString(by));
            } catch (IllegalArgumentException ignored) {
            }
            int n = i(s, BUTTER);
            if (owner != null && n > 0 && owner.level() == level && owner.position().distanceToSqr(Vec3.atCenterOf(origin)) <= 64) {
                ItemStack out = m.item(butter, n);
                if (out == null) return;
                setI(s, BUTTER, 0);
                write(level, origin, s);
                WildlifeModule.give(owner, out);
                m.sound(owner, "station");
                m.bar(owner, "station.churn-collected", WildlifeModule.tag("count", n));
            }
        });
    }

    private void finishChurn(ServerLevel level, BlockPos origin, CompoundTag t, long now) {
        long at = l(t, CHURN_AT);
        if (at <= 0 || now < at) return;
        setL(t, CHURN_AT, 0);
        setI(t, BUTTER, i(t, BUTTER) + 1);
        write(level, origin, t);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, origin.getX() + 0.5, origin.getY() + 1.3, origin.getZ() + 0.5, 6, 0.25, 0.2, 0.25, 0);
    }

    private void press(ServerPlayer p, ServerLevel level, BlockPos origin, CompoundTag t, ItemStack held, String oid, InteractionHand hand) {
        long now = System.currentTimeMillis();
        long at = l(t, PRESS_AT);
        if (at > 0 && now >= at) {
            ItemStack out = m.item(wheel, 1);
            if (out == null) return;
            setL(t, PRESS_AT, 0);
            write(level, origin, t);
            WildlifeModule.give(p, out);
            m.sound(p, "station");
            m.bar(p, "station.press-collected");
            return;
        }
        if (wheel.equals(oid)) {
            ItemStack out = m.item(wedge, wedges);
            if (out == null) return;
            WildlifeModule.useOne(p, hand, null);
            WildlifeModule.give(p, out);
            m.sound(p, "cut");
            m.bar(p, "station.press-cut", WildlifeModule.tag("count", wedges));
            return;
        }
        if (at > 0) {
            m.bar(p, "station.pressing", WildlifeModule.tag("time", WildlifeModule.time(at - now)));
            return;
        }
        int milk = i(t, PRESS_MILK);
        if (oid != null || !held.is(Items.MILK_BUCKET)) {
            m.bar(p, "station.press-idle", WildlifeModule.tag("milk", milk), WildlifeModule.tag("need", pressMilk));
            return;
        }
        WildlifeModule.useOne(p, hand, new ItemStack(Items.BUCKET));
        milk++;
        m.soundAt(level, Vec3.atCenterOf(origin), "pour");
        if (milk >= pressMilk) {
            setI(t, PRESS_MILK, 0);
            setL(t, PRESS_AT, now + pressMs);
            m.bar(p, "station.press-start", WildlifeModule.tag("time", WildlifeModule.time(pressMs)));
        } else {
            setI(t, PRESS_MILK, milk);
            m.bar(p, "station.press-idle", WildlifeModule.tag("milk", milk), WildlifeModule.tag("need", pressMilk));
        }
        write(level, origin, t);
    }

    private void collect(ServerPlayer p, ServerLevel level, BlockPos origin, CompoundTag t, String countKey, String itemKey, String what, String credits) {
        int n = i(t, countKey);
        String spec = s(t, itemKey);
        if (n <= 0 || spec == null) {
            m.bar(p, "station." + what + "-empty");
            return;
        }
        List<ItemStack> out = stacks(spec, n);
        if (out.isEmpty()) return;
        for (ItemStack part : out) WildlifeModule.give(p, part);
        setI(t, countKey, 0);
        write(level, origin, t);
        m.sound(p, "station");
        m.bar(p, "station." + what + "-collected", WildlifeModule.tag("count", n));
        if (credits != null && !credits.isBlank()) m.bestiary.harvested(p, credits);
        label(level, origin, t);
    }

    private void trough(ServerPlayer p, ServerLevel level, BlockPos origin, CompoundTag t, ItemStack held, String oid, InteractionHand hand) {
        String inside = s(t, TROUGH_ITEM);
        int count = i(t, TROUGH_COUNT);
        String key = Match.key(held);
        // An empty hand takes the food back out.
        if (held.isEmpty()) {
            if (count > 0 && inside != null) {
                for (ItemStack part : stacks(inside, count)) WildlifeModule.give(p, part);
                setI(t, TROUGH_COUNT, 0);
                setS(t, TROUGH_ITEM, null);
                write(level, origin, t);
                m.bar(p, "station.trough-emptied", WildlifeModule.tag("count", count));
                label(level, origin, t);
                return;
            }
            m.bar(p, "station.trough-empty");
            return;
        }
        if (!troughFood.matches(held)) {
            if (count > 0 && inside != null) {
                m.bar(p, "station.trough-holds", WildlifeModule.tag("count", count), WildlifeModule.tag("max", troughMax), WildlifeModule.tag("item", pretty(inside)));
            } else m.bar(p, "station.trough-not-food");
            return;
        }
        if (count > 0 && inside != null && !inside.equals(key)) {
            m.bar(p, "station.trough-other", WildlifeModule.tag("item", pretty(inside)));
            m.sound(p, "error");
            return;
        }
        int room = troughMax - count;
        if (room <= 0) {
            m.bar(p, "station.trough-full", WildlifeModule.tag("max", troughMax));
            return;
        }
        int put = Math.min(room, held.getCount());
        if (!WildlifeModule.creative(p)) held.shrink(put);
        setS(t, TROUGH_ITEM, key);
        setI(t, TROUGH_COUNT, count + put);
        write(level, origin, t);
        m.soundAt(level, Vec3.atCenterOf(origin), "trough");
        m.bar(p, "station.trough-holds", WildlifeModule.tag("count", count + put), WildlifeModule.tag("max", troughMax), WildlifeModule.tag("item", pretty(key)));
        label(level, origin, t);
    }

    /** {@code n} of an item as full stacks (empty when the item doesn't exist or n is 0). */
    private List<ItemStack> stacks(String spec, int n) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack one = n > 0 ? m.item(spec, 1) : null;
        if (one == null) return out;
        int left = n;
        while (left > 0) {
            ItemStack part = one.copy();
            part.setCount(Math.min(left, one.getMaxStackSize()));
            left -= part.getCount();
            out.add(part);
        }
        return out;
    }

    // ------------------------------------------------------------------ the look-at card

    /**
     * Stations for the look-at card ({@code inspect}): what clicking this station with an empty hand would say, and the
     * counters floating over it, read from its state without changing anything (a finished churn counts as butter here;
     * the next click finishes it for real). Needs container access in a claim, as the click does, but says nothing when
     * it's missing. Null when the block isn't a station.
     */
    JsonObject inspect(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState st) {
        String id = ArcanaContent.id(st);
        if (!isStation(id) || !ArcanaContent.isFurniture(st)) return null;
        BlockPos origin = ArcanaContent.origin(st, pos);
        if (p.isSpectator() || !Feather.get().claimAllows(p, Loc.of(level, origin), ClaimAction.CONTAINER)) return Inspect.none();
        CompoundTag t = state(level, origin, id);
        long now = System.currentTimeMillis();
        JsonObject card = Inspect.card("station", pretty(id), m.item(id, 1));
        if (id.equals(churnId)) {
            long at = l(t, CHURN_AT);
            int ready = i(t, BUTTER) + (at > 0 && now >= at ? 1 : 0);
            if (ready > 0) Inspect.text(card, plain("station.inspect-butter-ready", "count", ready), Inspect.GOOD);
            else if (at > 0) Inspect.timer(card, at - now, plain("station.inspect-churning", null, 0));
            else Inspect.text(card, plain("station.churn-idle", null, 0), Inspect.MUTED);
        } else if (id.equals(pressId)) {
            long at = l(t, PRESS_AT);
            if (at > 0 && now >= at) {
                Inspect.text(card, plain("station.inspect-wheel-ready", null, 0), Inspect.GOOD);
            } else if (at > 0) {
                Inspect.bar(card, pressMs <= 0 ? 1 : 1 - (at - now) / (double) pressMs, plain("station.inspect-pressing", null, 0));
                Inspect.timer(card, at - now, plain("station.inspect-time-left", null, 0));
            } else {
                Inspect.count(card, Math.min(i(t, PRESS_MILK), pressMilk), pressMilk, plain("station.inspect-milk", null, 0));
            }
        } else if (id.equals(frameId)) {
            int n = i(t, SILK);
            if (n > 0) Inspect.count(card, n, frameMax(), plain("station.inspect-silk", null, 0));
            else Inspect.text(card, plain("station.frame-empty", null, 0), Inspect.MUTED);
        } else if (id.equals(nestId)) {
            int n = i(t, EGGS);
            if (n > 0) Inspect.count(card, n, nestMax, plain("station.inspect-eggs", null, 0));
            else Inspect.text(card, plain("station.nest-empty", null, 0), Inspect.MUTED);
        } else if (id.equals(troughId)) {
            int n = i(t, TROUGH_COUNT);
            String in = s(t, TROUGH_ITEM);
            if (n > 0 && in != null) Inspect.count(card, n, troughMax, pretty(in));
            else Inspect.text(card, plain("station.trough-empty", null, 0), Inspect.MUTED);
        }
        return card;
    }

    /** The most silk any species leaves on a frame (what a frame fills up to). */
    private int frameMax() {
        int max = 0;
        for (WildSpecies s : m.species.values()) if (s.silk != null) max = Math.max(max, s.silk.max());
        return max > 0 ? max : 8;
    }

    /** A wildlife.yml message as plain text, with one optional number filled in. */
    private String plain(String key, String tag, int value) {
        return tag == null ? Inspect.plain(m.msg(key)) : Inspect.plain(m.msg(key, WildlifeModule.tag(tag, value)));
    }

    static String pretty(String id) {
        String n = id.startsWith("arcana_") ? id.substring(7) : id;
        n = n.replace('_', ' ');
        return n.isEmpty() ? id : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ------------------------------------------------------------------ what the mobs do

    /** A Pondwaddle lays: into a nesting box with room within {@code r}. True when it went into one. */
    boolean nest(ServerLevel level, Vec3 at, double r, ItemStack egg) {
        String eggSpec = Match.key(egg);
        Near box = near(level, at, r, nestId, t -> {
            String in = s(t, EGG_ITEM);
            return i(t, EGGS) < nestMax && (in == null || in.equals(eggSpec) || i(t, EGGS) == 0);
        });
        if (box == null) return false;
        CompoundTag t = box.state();
        setS(t, EGG_ITEM, eggSpec);
        setI(t, EGGS, Math.min(nestMax, i(t, EGGS) + egg.getCount()));
        write(level, box.pos(), t);
        m.soundAt(level, Vec3.atCenterOf(box.pos()), "lay");
        label(level, box.pos(), t);
        return true;
    }

    /** A fed Loomworm spins: one more silk into a silk frame with room within the worm's radius. */
    boolean spin(ServerLevel level, Vec3 at, WildSpecies.Silk silk) {
        Near frame = near(level, at, silk.radius(), frameId, t -> i(t, SILK) < silk.max());
        if (frame == null) return false;
        int add = Math.max(1, silk.drop().min());
        CompoundTag t = frame.state();
        setS(t, SILK_ITEM, silk.drop().item());
        setI(t, SILK, Math.min(silk.max(), i(t, SILK) + add));
        write(level, frame.pos(), t);
        BlockPos p = frame.pos();
        level.sendParticles(ParticleTypes.WHITE_ASH, p.getX() + 0.5, p.getY() + 1.3, p.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0);
        label(level, p, t);
        return true;
    }

    /** Takes one matching food from a trough within the trough radius. True when the mob got fed. */
    boolean feedFromTrough(ServerLevel level, Vec3 at, Match food) {
        Near trough = near(level, at, troughRadius, troughId, t -> i(t, TROUGH_COUNT) > 0 && food.matches(s(t, TROUGH_ITEM)));
        if (trough == null) return false;
        CompoundTag t = trough.state();
        int left = i(t, TROUGH_COUNT) - 1;
        setI(t, TROUGH_COUNT, left);
        if (left <= 0) setS(t, TROUGH_ITEM, null);
        write(level, trough.pos(), t);
        label(level, trough.pos(), t);
        return true;
    }

    // ------------------------------------------------------------------ breaking

    /** The station is gone: drop what it held, forget its state, remove its counter. */
    private void removed(ServerLevel level, BlockPos origin) {
        CompoundTag t = read(level, origin);
        unlabel(level, origin);
        if (t == null) return;
        write(level, origin, null);
        Vec3 at = Vec3.atCenterOf(origin);
        List<ItemStack> out = new ArrayList<>();
        int n;
        if ((n = i(t, SILK)) > 0 && s(t, SILK_ITEM) != null) out.addAll(stacks(s(t, SILK_ITEM), n));
        if ((n = i(t, EGGS)) > 0 && s(t, EGG_ITEM) != null) out.addAll(stacks(s(t, EGG_ITEM), n));
        if ((n = i(t, TROUGH_COUNT)) > 0 && s(t, TROUGH_ITEM) != null) out.addAll(stacks(s(t, TROUGH_ITEM), n));
        if ((n = i(t, BUTTER)) > 0) out.addAll(stacks(butter, n));
        int buckets = i(t, PRESS_MILK) + (l(t, CHURN_AT) > 0 ? 1 : 0);
        if (l(t, PRESS_AT) > 0) {
            if (System.currentTimeMillis() >= l(t, PRESS_AT)) out.addAll(stacks(wheel, 1));
            else buckets += pressMilk;
        }
        out.addAll(stacks("milk_bucket", buckets));
        for (ItemStack s : out) WildlifeModule.drop(level, at, s);
    }

    // ------------------------------------------------------------------ migration

    /** An old Oraxen station became a block: its furniture PDC (feather:wild_*) becomes the station state. */
    void restoreLegacy(ServerLevel level, BlockPos origin, String oraxenId, CompoundTag bukkit) {
        String id = oraxenId == null ? null : oraxenId.toLowerCase(Locale.ROOT);
        if (id != null && id.contains(":")) id = id.substring(id.indexOf(':') + 1);
        if (!isStation(id)) return;
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        for (String k : bukkit.keySet()) {
            if (!k.startsWith(WildlifeModule.NS + "wild_") || k.equals(WildlifeModule.NS + LABEL)) continue;
            Tag v = bukkit.get(k);
            if (v != null) t.put(k.substring(WildlifeModule.NS.length()), v.copy());
        }
        write(level, origin, t);
        label(level, origin, t);
    }

    // ------------------------------------------------------------------ labels

    private static String labelKey(ServerLevel level, BlockPos p) {
        return WildlifeHotspots.key(level) + "|" + posKey(p);
    }

    void label(ServerLevel level, BlockPos origin, CompoundTag t) {
        if (!showLabels || t == null) return;
        String id = s(t, "id");
        String text = null;
        if (frameId.equals(id)) {
            int n = i(t, SILK);
            if (n > 0) text = m.raw("station.label-frame").replace("<count>", String.valueOf(n));
        } else if (nestId.equals(id)) {
            int n = i(t, EGGS);
            if (n > 0) text = m.raw("station.label-nest").replace("<count>", String.valueOf(n));
        } else if (troughId.equals(id)) {
            int n = i(t, TROUGH_COUNT);
            String in = s(t, TROUGH_ITEM);
            if (n > 0 && in != null) text = m.raw("station.label-trough").replace("<count>", String.valueOf(n)).replace("<item>", pretty(in));
        }
        String key = labelKey(level, origin);
        Display.TextDisplay old = labels.get(key);
        if (text == null || text.isBlank()) {
            if (old != null) old.discard();
            labels.remove(key);
            return;
        }
        net.minecraft.network.chat.Component component = Adv.vanilla(m.feather().text().parse(text));
        if (old != null && !old.isRemoved()) {
            old.setText(component);
            return;
        }
        Display.TextDisplay d = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
        d.setPos(origin.getX() + 0.5, origin.getY() + labelHeight, origin.getZ() + 0.5);
        d.setText(component);
        d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        d.setFlags(Display.TextDisplay.FLAG_SHADOW);
        d.setBackgroundColor(0);
        d.setViewRange(0.25f);
        Pdc.putString(d, WildlifeModule.NS + LABEL, key);
        d.addTag(net.axther.aftrhrs.core.event.FeatherEvents.TRANSIENT_TAG);   // rebuilt when the chunk loads, never saved
        labels.put(key, d);
        level.addFreshEntity(d);
    }

    private void unlabel(ServerLevel level, BlockPos origin) {
        Display.TextDisplay d = labels.remove(labelKey(level, origin));
        if (d != null) d.discard();
    }

    /** A counter display that isn't one of the live labels (saved by a chunk unload, or left by Paper). */
    boolean isStaleLabel(Entity e) {
        return e instanceof Display.TextDisplay d && Pdc.has(e, WildlifeModule.NS + LABEL) && labels.get(Pdc.getString(e, WildlifeModule.NS + LABEL)) != d;
    }

    void labelRemoved(Entity e) {
        if (!(e instanceof Display.TextDisplay) || !Pdc.has(e, WildlifeModule.NS + LABEL)) return;
        String key = Pdc.getString(e, WildlifeModule.NS + LABEL);
        // a copy saved with the chunk is dropped as stale when it loads again, and rebuilt from the station state
        if (labels.get(key) == e) labels.remove(key);
    }

    /** A chunk loaded: (re)build the counters of its stations, forget stations whose block is gone. */
    void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        CompoundTag stations = all(chunk);
        if (stations.isEmpty()) return;
        Tasks.later(1, () -> {
            for (String k : all(chunk).keySet()) {
                BlockPos p = parse(k);
                if (p == null) continue;
                CompoundTag t = read(level, p);
                if (t == null) continue;
                if (!stands(level, p, s(t, "id"))) {
                    if (level.isLoaded(p)) write(level, p, null);
                    continue;
                }
                label(level, p, t);
            }
        });
    }

    private void relabelAll() {
        List<String> keys = new ArrayList<>(labels.keySet());
        for (String key : keys) {
            Display.TextDisplay d = labels.get(key);
            if (d == null || d.isRemoved() || !(d.level() instanceof ServerLevel level)) continue;
            BlockPos p = parse(key.substring(key.indexOf('|') + 1));
            if (p != null) label(level, p, read(level, p));
        }
    }

    // ------------------------------------------------------------------ admin

    /** Stations with saved state within 8 blocks: (pos, state). */
    private List<Near> around(ServerLevel level, Vec3 at) {
        List<Near> out = new ArrayList<>();
        int r = 8;
        for (int cx = ((int) Math.floor(at.x) - r) >> 4; cx <= ((int) Math.floor(at.x) + r) >> 4; cx++) {
            for (int cz = ((int) Math.floor(at.z) - r) >> 4; cz <= ((int) Math.floor(at.z) + r) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                CompoundTag stations = all(chunk);
                for (String k : stations.keySet()) {
                    BlockPos p = parse(k);
                    if (p != null && Vec3.atCenterOf(p).distanceToSqr(at) <= r * r) out.add(new Near(p, stations.getCompoundOrEmpty(k).copy()));
                }
            }
        }
        return out;
    }

    /** /wildlife due: finishes churning and pressing within 8 blocks. */
    int due(ServerLevel level, Vec3 at) {
        int n = 0;
        for (Near s : around(level, at)) {
            CompoundTag t = s.state();
            boolean changed = false;
            if (l(t, CHURN_AT) > 0) {
                setL(t, CHURN_AT, 1);
                n++;
                changed = true;
            }
            if (l(t, PRESS_AT) > 0) {
                setL(t, PRESS_AT, 1);
                n++;
                changed = true;
            }
            if (changed) write(level, s.pos(), t);
        }
        return n;
    }

    List<String> info(ServerLevel level, Vec3 at) {
        List<String> out = new ArrayList<>();
        for (Near s : around(level, at)) {
            CompoundTag t = s.state();
            BlockPos b = s.pos();
            StringBuilder sb = new StringBuilder("STATION " + s(t, "id") + " @" + b.getX() + "," + b.getY() + "," + b.getZ());
            sb.append(" silk=").append(i(t, SILK)).append(" eggs=").append(i(t, EGGS));
            String ti = s(t, TROUGH_ITEM);
            sb.append(" trough=").append(ti == null ? "-" : ti.toLowerCase(Locale.ROOT)).append('x').append(i(t, TROUGH_COUNT));
            sb.append(" butter=").append(i(t, BUTTER)).append(" churning=").append(l(t, CHURN_AT) > 0 ? 1 : 0);
            sb.append(" milk=").append(i(t, PRESS_MILK)).append(" pressing=").append(l(t, PRESS_AT) > 0 ? 1 : 0);
            Display.TextDisplay lbl = labels.get(labelKey(level, b));
            sb.append(" label=").append(lbl != null && !lbl.isRemoved() ? 1 : 0);
            out.add(sb.toString());
        }
        return out;
    }

    void clear() {
        for (Display.TextDisplay d : labels.values()) d.discard();
        labels.clear();
    }
}
