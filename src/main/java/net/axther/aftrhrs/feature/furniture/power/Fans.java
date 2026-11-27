package net.axther.aftrhrs.feature.furniture.power;

import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Encased fans: an air stream that pushes (or pulls) items, mobs and players, lifts items up a column, and processes
 * the items in it after it passes water (washing), fire or a campfire (smoking), lava (blasting) or soul fire
 * (haunting), like Create's bulk processing. Mobs in a processing stream get its effect. The stream stops at a
 * solid block (fences, bars, campfires, leaves and signs let air through) and where the fan's owner couldn't build.
 * The fan blows toward its {@code facing} (the model's front, the way the placer looked).
 */
final class Fans {
    static final long TICKS = 2L;

    enum Process {
        NONE, WASH, SMOKE, BLAST, HAUNT
    }

    private static final class Stream {
        int length;
        Process[] process;
        long made;
    }

    private static final class Progress {
        Process process;
        Item type;
        int amount;
        int ticks;
        int need;
        long seen;
        /** Null when the item has no recipe for this process. */
        Object result;
    }

    /** One chance output of a washing or haunting rule. */
    private record Out(Item type, int count, double chance) {
    }


    private final PowerModule m;
    private final Map<PowerBlockEntity, Stream> streams = new IdentityHashMap<>();
    private final Map<UUID, Progress> items = new HashMap<>();
    private final Map<Process, Map<Item, List<Out>>> rules = new EnumMap<>(Process.class);
    private long run;

    private int reach;
    private double push, mobFactor, maxSpeed;
    private boolean pushPlayers, pushMobs, mobEffects, stripDye;
    private int ticksPer16;
    float spinDegrees;

    Fans(PowerModule module) {
        this.m = module;
        FanShield.inStream = this::inStream;
    }

    /** True when the item is inside a running fan's stream right now (for {@link FanShield}, between fan ticks). */
    private boolean inStream(ItemEntity item) {
        AABB at = item.getBoundingBox();
        for (Map.Entry<PowerBlockEntity, Stream> e : streams.entrySet()) {
            PowerBlockEntity fan = e.getKey();
            Stream s = e.getValue();
            if (s.length <= 0 || !fan.powered || fan.isRemoved() || fan.getLevel() != item.level()) continue;
            BlockPos origin = fan.getBlockPos();
            Direction f = facing(fan);
            if (new AABB(origin.relative(f, 1)).minmax(new AABB(origin.relative(f, s.length))).intersects(at)) return true;
        }
        return false;
    }

    void load(Yaml c) {
        reach = Math.max(1, Math.min(32, c.getInt("reach", 8)));
        push = c.getDouble("push", 0.11);
        mobFactor = c.getDouble("mob-push-factor", 0.6);
        maxSpeed = c.getDouble("max-speed", 0.6);
        pushPlayers = c.getBoolean("push-players", true);
        pushMobs = c.getBoolean("push-mobs", true);
        mobEffects = c.getBoolean("mob-effects", true);
        ticksPer16 = Math.max(1, c.getInt("processing.ticks-per-16-items", 150));
        stripDye = c.getBoolean("processing.washing-removes-dye", true);
        spinDegrees = (float) c.getDouble("spin-degrees-per-tick", 18);
        rules.clear();
        rules.put(Process.WASH, readRules(c.getConfigurationSection("processing.washing"), "washing"));
        rules.put(Process.HAUNT, readRules(c.getConfigurationSection("processing.haunting"), "haunting"));
        streams.clear();
        items.clear();
        FanShield.clear();
    }

    private static @Nullable Item item(String s) {
        Identifier id = Identifier.tryParse(s.contains(":") ? s.toLowerCase(Locale.ROOT) : "minecraft:" + s.toLowerCase(Locale.ROOT));
        Item i = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return i == Items.AIR ? null : i;
    }

    private Map<Item, List<Out>> readRules(@Nullable Yaml s, String what) {
        Map<Item, List<Out>> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            Item in = item(k);
            if (in == null) {
                m.warnOnce(what + k, "power.yml fan.processing." + what + ": unknown item " + k);
                continue;
            }
            List<Out> list = new ArrayList<>();
            for (String line : s.getStringList(k)) {
                String[] f = line.trim().split("\\s+");
                Item mat = item(f[0]);
                if (mat == null) {
                    m.warnOnce(what + line, "power.yml fan.processing." + what + "." + k + ": unknown item " + f[0]);
                    continue;
                }
                int count = f.length > 1 ? Integer.parseInt(f[1]) : 1;
                double chance = f.length > 2 ? Double.parseDouble(f[2]) : 1.0;
                list.add(new Out(mat, count, chance));
            }
            out.put(in, list);
        }
        return out;
    }

    void invalidate(PowerBlockEntity fan) {
        streams.remove(fan);
    }

    static Direction facing(PowerBlockEntity fan) {
        return FurnitureBlock.front(fan.getBlockState());
    }

    // ------------------------------------------------------------------ the stream

    private static boolean letsAirThrough(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.getCollisionShape(level, p).isEmpty()) return true;
        if (s.is(BlockTags.FENCES) || s.is(BlockTags.CAMPFIRES) || s.is(BlockTags.LEAVES) || s.is(BlockTags.ALL_SIGNS)) return true;
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_bars");
    }

    private static @Nullable Process catalyst(BlockState s) {
        if (s.is(Blocks.WATER) || s.is(Blocks.BUBBLE_COLUMN)
                || (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED))) return Process.WASH;
        if (s.is(Blocks.LAVA)) return Process.BLAST;
        if (s.is(Blocks.FIRE)) return Process.SMOKE;
        if (s.is(Blocks.SOUL_FIRE)) return Process.HAUNT;
        if (s.is(Blocks.CAMPFIRE) && s.getValue(CampfireBlock.LIT)) return Process.SMOKE;
        if (s.is(Blocks.SOUL_CAMPFIRE) && s.getValue(CampfireBlock.LIT)) return Process.HAUNT;
        return null;
    }

    private Stream stream(ServerLevel level, PowerBlockEntity fan) {
        Stream s = streams.get(fan);
        long now = level.getGameTime();
        if (s != null && now - s.made < 20) return s;
        s = new Stream();
        s.made = now;
        s.process = new Process[reach];
        BlockPos origin = fan.getBlockPos();
        Direction f = facing(fan);
        Process current = Process.NONE;
        int len = 0;
        for (int i = 1; i <= reach; i++) {
            BlockPos b = origin.relative(f, i);
            if (!level.isLoaded(b) || m.machineAt(level, b) || !letsAirThrough(level, b)) break;
            if (fan.owner != null && !m.feather.claimAllows(fan.owner, Loc.of(level, b), ClaimAction.BUILD)) break;
            Process p = catalyst(level.getBlockState(b));
            if (p != null) current = p;
            s.process[i - 1] = current;
            len = i;
        }
        s.length = len;
        streams.put(fan, s);
        return s;
    }

    // ------------------------------------------------------------------ ticking

    void tick() {
        run++;
        for (PowerBlockEntity fan : m.machines()) {
            if (!fan.isFan() || !fan.powered || !(fan.getLevel() instanceof ServerLevel level)) continue;
            Stream s = stream(level, fan);
            if (s.length > 0) blow(level, fan, s);
            // a fan blowing into a smithing hearth (or its bellows) works the fire
            if (run % 20 == 0 && !fan.reverse) {
                net.axther.aftrhrs.feature.smithing.Workshop.airInto(level, fan.getBlockPos().relative(facing(fan), s.length + 1), facing(fan));
            }
        }
        if (run % 50 == 0) {
            long cutoff = run - 20;
            items.values().removeIf(p -> p.seen < cutoff);
            streams.keySet().removeIf(PowerBlockEntity::isRemoved);
        }
    }

    private void blow(ServerLevel level, PowerBlockEntity fan, Stream s) {
        BlockPos origin = fan.getBlockPos();
        Direction f = facing(fan);
        AABB box = new AABB(origin.relative(f, 1)).minmax(new AABB(origin.relative(f, s.length)));
        Vec3 dir = Vec3.atLowerCornerOf(f.getUnitVec3i());
        Vec3 flow = fan.reverse ? dir.scale(-1) : dir;
        Vec3 c = Vec3.atCenterOf(origin);
        boolean effectsNow = run % 10 == 0;
        for (Entity e : level.getEntities((Entity) null, box)) {
            if (!pushable(e)) continue;
            // Items in the stream can't burn while it carries them past the lava or flames (and a moment after).
            if (e instanceof ItemEntity item) FanShield.mark(item);
            double along = e.getBoundingBox().getCenter().subtract(c).dot(dir) - 0.5;
            int seg = Math.max(0, Math.min(s.length - 1, (int) Math.floor(along)));
            Process p = s.process[seg];
            double falloff = Math.max(0.3, 1.0 - along / (s.length + 1));
            double strength = push * falloff * (e instanceof LivingEntity ? mobFactor : 1.0);
            Vec3 v = e.getDeltaMovement();
            double speed = v.dot(flow);
            // An item still being processed stays in the stream's last block until it is done (raw ore needs 150
            // ticks per 16, longer than the stream takes to blow it through), then it is blown out like anything else.
            if (e instanceof ItemEntity item && along >= s.length - 1 && processing(level, item, p)) {
                if (speed > 0) {
                    e.setDeltaMovement(v.subtract(flow.scale(speed)));
                    e.syncVelocity = true;
                }
                process(level, item, p);
                continue;
            }
            if (speed < maxSpeed) {
                v = v.add(flow.scale(Math.min(strength, maxSpeed - speed)));
                // Hold items against gravity in a rising stream.
                if (flow.y > 0 && e instanceof ItemEntity) v = new Vec3(v.x, Math.max(v.y, 0.06), v.z);
                e.setDeltaMovement(v);
                e.syncVelocity = true;
            }
            if (flow.y > 0) e.resetFallDistance();
            if (e instanceof ItemEntity item) process(level, item, p);
            else if (effectsNow && mobEffects && p != Process.NONE && e instanceof LivingEntity le) affect(level, le, p);
        }
        if (run % 3 == 0) particles(level, fan, s, flow);
    }

    private boolean pushable(Entity e) {
        if (e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof FallingBlockEntity) return true;
        if (e instanceof Player p) return pushPlayers && !p.isSpectator() && !(p.isCreative() && p.getAbilities().flying);
        if (e instanceof ArmorStand) return false;
        if (e instanceof LivingEntity || e instanceof VehicleEntity) return pushMobs;
        return false;
    }

    private void particles(ServerLevel level, PowerBlockEntity fan, Stream s, Vec3 flow) {
        var r = level.getRandom();
        int i = r.nextInt(s.length);
        BlockPos b = fan.getBlockPos().relative(facing(fan), i + 1);
        double px = b.getX() + 0.2 + r.nextDouble() * 0.6, py = b.getY() + 0.2 + r.nextDouble() * 0.6, pz = b.getZ() + 0.2 + r.nextDouble() * 0.6;
        Process p = s.process[i];
        ParticleOptions particle = switch (p) {
            case WASH -> ParticleTypes.SPLASH;
            case SMOKE -> ParticleTypes.SMOKE;
            case BLAST -> ParticleTypes.FLAME;
            case HAUNT -> ParticleTypes.SOUL_FIRE_FLAME;
            default -> ParticleTypes.CLOUD;
        };
        double sp = p == Process.NONE ? 0.12 : 0.06;
        level.sendParticles(particle, px, py, pz, 0, flow.x, flow.y, flow.z, sp);
    }

    // ------------------------------------------------------------------ mobs

    private void affect(ServerLevel level, LivingEntity e, Process p) {
        if (e instanceof Player pl && pl.isCreative()) return;
        Difficulty diff = level.getDifficulty();
        switch (p) {
            case SMOKE -> {
                if (e.hasEffect(MobEffects.FIRE_RESISTANCE)) return;
                e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 60));
                e.hurtServer(level, level.damageSources().source(DamageTypes.IN_FIRE), diff == Difficulty.HARD ? 3 : 2);
            }
            case BLAST -> {
                if (e.hasEffect(MobEffects.FIRE_RESISTANCE)) return;
                e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 100));
                float dmg = switch (diff) {
                    case HARD -> 6;
                    case NORMAL -> 4;
                    default -> 3;
                };
                e.hurtServer(level, level.damageSources().source(DamageTypes.IN_FIRE), dmg);
            }
            case WASH -> {
                if (e.getRemainingFireTicks() > 0) {
                    e.clearFire();
                    level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.NEUTRAL, 0.5f, 1.4f);
                }
                if (e instanceof Enderman || e instanceof Blaze || e instanceof SnowGolem) {
                    e.hurtServer(level, level.damageSources().source(DamageTypes.DROWN), 2);
                }
            }
            case HAUNT -> {
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 1, true, true));
                e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, true, true));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ items

    /** The item's progress for this process, started (or restarted, when the stack changed) if needed. */
    private Progress progress(ServerLevel level, ItemEntity item, Process p) {
        ItemStack stack = item.getItem();
        Progress pr = items.get(item.getUUID());
        if (pr == null || pr.process != p || pr.type != stack.getItem() || pr.amount != stack.getCount()) {
            pr = new Progress();
            pr.process = p;
            pr.type = stack.getItem();
            pr.amount = stack.getCount();
            pr.need = (int) Math.ceil(stack.getCount() / 16.0) * ticksPer16;
            pr.result = result(level, p, stack);
            items.put(item.getUUID(), pr);
        }
        return pr;
    }

    /** True while this process has something to make of the item (it isn't done yet). */
    private boolean processing(ServerLevel level, ItemEntity item, Process p) {
        return p != Process.NONE && progress(level, item, p).result != null;
    }

    private void process(ServerLevel level, ItemEntity item, Process p) {
        if (p == Process.NONE) {
            items.remove(item.getUUID());
            return;
        }
        ItemStack stack = item.getItem();
        Progress pr = progress(level, item, p);
        pr.seen = run;
        if (pr.result == null) return;
        pr.ticks += (int) TICKS;
        if (pr.ticks < pr.need) return;
        items.remove(item.getUUID());
        finish(level, item, stack, p, pr.result);
    }

    @SuppressWarnings("unchecked")
    private void finish(ServerLevel level, ItemEntity item, ItemStack stack, Process p, Object result) {
        Vec3 at = item.position();
        List<ItemStack> outs = new ArrayList<>();
        if (result instanceof ItemStack cooked) {
            addSplit(outs, cooked, cooked.getCount() * stack.getCount());
        } else if (result instanceof List<?> list) {
            var r = level.getRandom();
            Map<Item, Integer> counts = new LinkedHashMap<>();
            for (int n = 0; n < stack.getCount(); n++) {
                for (Out o : (List<Out>) list) if (r.nextDouble() < o.chance()) counts.merge(o.type(), o.count(), Integer::sum);
            }
            for (Map.Entry<Item, Integer> e : counts.entrySet()) addSplit(outs, new ItemStack(e.getKey()), e.getValue());
        }
        if (outs.isEmpty()) item.discard();
        else {
            item.setItem(outs.getFirst());
            for (int i = 1; i < outs.size(); i++) {
                ItemEntity extra = new ItemEntity(level, at.x, at.y, at.z, outs.get(i));
                extra.setDeltaMovement(item.getDeltaMovement());
                level.addFreshEntity(extra);
            }
        }
        switch (p) {
            case WASH -> {
                level.sendParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 8, 0.15, 0.1, 0.15, 0);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.25f, 1.5f);
            }
            case SMOKE -> level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 3, 0.1, 0.1, 0.1, 0.01);
            case BLAST -> level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 2, 0.1, 0.1, 0.1, 0);
            case HAUNT -> level.sendParticles(ParticleTypes.SOUL, at.x, at.y, at.z, 4, 0.15, 0.1, 0.15, 0.01);
            default -> {
            }
        }
    }

    private static void addSplit(List<ItemStack> outs, ItemStack like, int total) {
        int max = Math.max(1, like.getMaxStackSize());
        while (total > 0) {
            ItemStack s = like.copyWithCount(Math.min(max, total));
            total -= s.getCount();
            outs.add(s);
        }
    }

    /** What processing gives for one of this stack: an ItemStack (per input item), a rule list, or null. */
    private @Nullable Object result(ServerLevel level, Process p, ItemStack stack) {
        boolean custom = PowerModule.idOf(stack) != null;
        SingleRecipeInput in = new SingleRecipeInput(stack.copyWithCount(1));
        var recipes = level.recipeAccess();
        switch (p) {
            case SMOKE -> {
                return recipes.getRecipeFor(RecipeType.SMOKING, in, level).map(r -> (Object) r.value().assemble(in)).orElse(null);
            }
            case BLAST -> {
                // Furnace recipes first (food cooks like in a furnace; nothing is burnt up), then blast furnace ones.
                var r = recipes.getRecipeFor(RecipeType.SMELTING, in, level).map(x -> x.value().assemble(in));
                if (r.isEmpty()) r = recipes.getRecipeFor(RecipeType.BLASTING, in, level).map(x -> x.value().assemble(in));
                return r.orElse(null);
            }
            case WASH, HAUNT -> {
                if (custom) return null;
                List<Out> rule = rules.getOrDefault(p, Map.of()).get(stack.getItem());
                if (rule != null) return rule;
                if (p == Process.WASH && stripDye) {
                    Item plain = undyed(stack);
                    if (plain != null) return List.of(new Out(plain, 1, 1.0));
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Concrete powder -> concrete, dyed wool, carpet, beds, glass and terracotta -> the plain kind. */
    private static @Nullable Item undyed(ItemStack s) {
        String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (n.endsWith("_concrete_powder")) return item(n.substring(0, n.length() - "_powder".length()));
        if (n.startsWith("white_")) return null;
        if (s.is(ItemTags.WOOL)) return Items.WOOL.white();
        if (s.is(ItemTags.WOOL_CARPETS)) return Items.CARPET.white();
        if (s.is(ItemTags.BEDS)) return Items.BED.white();
        if (n.endsWith("_stained_glass")) return Items.GLASS;
        if (n.endsWith("_stained_glass_pane")) return Items.GLASS_PANE;
        if (n.endsWith("_terracotta") && !n.contains("glazed")) return Items.TERRACOTTA;
        return null;
    }

    // ------------------------------------------------------------------ status

    void status(ServerPlayer p, PowerBlockEntity fan) {
        Stream s = stream((ServerLevel) fan.getLevel(), fan);
        Process proc = Process.NONE;
        for (int i = 0; i < s.length; i++) if (s.process[i] != Process.NONE) proc = s.process[i];
        String mode = m.message(fan.reverse ? "fan-pull" : "fan-push");
        String power = m.message(fan.powered ? "fan-powered" : "fan-unpowered").replace("<range>", String.valueOf((int) m.range));
        String process = proc == Process.NONE ? "" : m.message("process-" + switch (proc) {
            case WASH -> "wash";
            case SMOKE -> "smoke";
            case BLAST -> "blast";
            case HAUNT -> "haunt";
            default -> "none";
        });
        m.bar(p, "fan-status", Placeholder.parsed("mode", mode), Placeholder.parsed("power", power),
                Placeholder.parsed("process", process), Placeholder.unparsed("length", String.valueOf(s.length)),
                Placeholder.unparsed("range", String.valueOf((int) m.range)));
    }
}
