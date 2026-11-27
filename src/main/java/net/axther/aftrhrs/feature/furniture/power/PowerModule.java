package net.axther.aftrhrs.feature.furniture.power;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.migrate.PaperItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Power (power.yml): coal generators and the machines they run (the Encased Fan first). A generator gives
 * {@code generator.capacity} power units to machines within {@code generator.range} blocks, and only burns fuel while
 * something draws from it, scaled by the load.
 *
 * <p>Both are real furniture blocks now ({@code feather_coal_generator} with {@code lit}, {@code feather_encased_fan}
 * with {@code powered} and 6-way {@code facing}) with a {@link PowerBlockEntity}; the renderer spins a powered fan's
 * propeller. Paper's records (chunk PDC {@code feather:power_blocks} over barrier blocks) are turned into those blocks
 * when their chunk first loads.
 */
public final class PowerModule implements FeatherModule, FeatherCommand {
    static final String USE = "feather.power.use";
    static final String ADMIN = "feather.power.admin";
    static final String CHUNK_KEY = "feather:power_blocks";
    static final long GRID_TICKS = 10L;
    private static volatile PowerModule active;
    private static Float clientSpin;

    final Feather feather;
    Yaml cfg;
    final Fans fans = new Fans(this);
    private final Set<PowerBlockEntity> machines = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private final List<Tasks.Task> tasks = new ArrayList<>();
    private final Set<String> warned = new HashSet<>();

    String fanItem, generatorItem;
    int fanCost;
    int capacity;
    double range;
    private final Map<Item, Double> fuels = new HashMap<>();
    private final Map<String, Double> contentFuels = new LinkedHashMap<>();
    private boolean hoppers = true;

    public PowerModule(Feather feather) {
        this.feather = feather;
    }

    static @Nullable PowerModule active() {
        return active;
    }

    /** Degrees per tick a powered fan's propeller turns (client side: the bundled power.yml). */
    static float clientSpinDegrees() {
        PowerModule m = active;
        if (m != null) return m.fans.spinDegrees;
        if (clientSpin == null) clientSpin = (float) Feather.bundled("power.yml").getDouble("fan.spin-degrees-per-tick", 18);
        return clientSpin;
    }

    @Override
    public String name() {
        return "Power";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        feather.bind("power", this);
        feather.once("power.events", PowerModule::registerEvents);
    }

    @Override
    public void reload() {
        cfg = feather.loadResourceConfig("power.yml");
        fanItem = cfg.getString("fan.item", "feather_encased_fan").toLowerCase(Locale.ROOT);
        fanCost = Math.max(0, cfg.getInt("fan.power-cost", 2));
        generatorItem = cfg.getString("generator.item", "feather_coal_generator").toLowerCase(Locale.ROOT);
        capacity = Math.max(1, cfg.getInt("generator.capacity", 8));
        range = Math.max(1, cfg.getDouble("generator.range", 6));
        fans.load(cfg.section("fan"));
        fuels.clear();
        contentFuels.clear();
        Yaml f = cfg.getConfigurationSection("generator.fuel");
        if (f != null) {
            for (String k : f.getKeys(false)) {
                double secs = f.getDouble(k);
                if (secs <= 0) continue;
                if (k.toLowerCase(Locale.ROOT).startsWith("oraxen:")) {
                    contentFuels.put(k.substring("oraxen:".length()), secs);
                    continue;
                }
                Identifier id = Identifier.tryParse(k.contains(":") ? k.toLowerCase(Locale.ROOT) : "minecraft:" + k.toLowerCase(Locale.ROOT));
                Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null || item == Items.AIR) warnOnce("fuel-" + k, "power.yml generator.fuel: unknown item " + k);
                else fuels.put(item, secs);
            }
        }
        hoppers = cfg.getBoolean("generator.hopper-feed", true);
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        tasks.add(Tasks.timer(10, GRID_TICKS, this::tickGrid));
        tasks.add(Tasks.timer(3, Fans.TICKS, fans::tick));
        tasks.add(Tasks.timer(20, 20, this::refreshViews));
    }

    @Override
    public void disable() {
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        if (active == this) active = null;
    }

    private static void registerEvents() {
        ArcanaContent.hook(id -> {
            PowerModule m = active;
            return m != null && (id.equals(m.fanItem) || id.equals(m.generatorItem));
        }, new FurnitureHook() {
            @Override
            public InteractionResult onUse(FurnitureContext ctx) {
                PowerModule m = active;
                return m == null ? InteractionResult.PASS : m.use(ctx);
            }

            @Override
            public void onPlaced(FurnitureContext ctx) {
                PowerModule m = active;
                if (m != null) m.placed(ctx);
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            PowerModule m = active;
            if (m == null || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            String id = ArcanaContent.id(p.getItemInHand(hand));
            if (id == null || !(id.equals(m.fanItem) || id.equals(m.generatorItem))) return InteractionResult.PASS;
            if (Perms.has(p, USE)) return InteractionResult.PASS;
            m.bar(p, "no-permission");
            return InteractionResult.FAIL;
        });
    }

    // ------------------------------------------------------------------ helpers

    void warnOnce(String key, String message) {
        if (warned.add(key)) feather.getLogger().warn(message);
    }

    void bar(ServerPlayer p, String key, TagResolver... r) {
        String mini = cfg.getString("messages." + key, "");
        if (mini == null || mini.isBlank()) return;
        Adv.actionBar(p, feather.text().parse(mini, r));
    }

    String message(String key) {
        return cfg.getString("messages." + key, key);
    }

    /** A /power reply from power.yml {@code messages}. */
    private void reply(Sender to, String key, TagResolver... r) {
        String mini = message(key);
        if (!mini.isBlank()) to.sendMessage(feather.text().parse(mini, r));
    }

    private static TagResolver arg(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    static @Nullable String idOf(ItemStack stack) {
        return ArcanaContent.id(stack);
    }

    /** Full-load ticks one of this item gives, or 0 when it isn't a fuel. */
    double fuelTicks(ItemStack s) {
        if (s == null || s.isEmpty()) return 0;
        String id = idOf(s);
        if (id != null) return contentFuels.getOrDefault(id, 0.0) * 20;
        return fuels.getOrDefault(s.getItem(), 0.0) * 20;
    }

    void track(PowerBlockEntity be) {
        if (!be.isRemoved()) machines.add(be);
    }

    void untrack(PowerBlockEntity be) {
        machines.remove(be);
        fans.invalidate(be);
    }

    List<PowerBlockEntity> machines() {
        List<PowerBlockEntity> out = new ArrayList<>();
        for (PowerBlockEntity be : machines) if (!be.isRemoved() && be.getLevel() != null) out.add(be);
        return out;
    }

    /** True when a power block (either part of a generator) is at pos. */
    boolean machineAt(ServerLevel level, BlockPos pos) {
        String id = ArcanaContent.id(level.getBlockState(pos));
        return id != null && (id.equals(fanItem) || id.equals(generatorItem));
    }

    private boolean isGenerator(PowerBlockEntity be) {
        return generatorItem.equals(be.furnitureId());
    }

    private int height(PowerBlockEntity be) {
        return isGenerator(be) ? 2 : 1;
    }

    // ------------------------------------------------------------------ the grid

    /**
     * Every {@link #GRID_TICKS} ticks: hands each running machine power from the generator in range with the most
     * room that can still burn, then lets the generators burn for their load.
     */
    private void tickGrid() {
        List<PowerBlockEntity> gens = new ArrayList<>(), users = new ArrayList<>();
        for (PowerBlockEntity m : machines()) {
            if (isGenerator(m)) {
                m.load = 0;
                gens.add(m);
            } else users.add(m);
        }
        users.sort(Comparator.comparingLong(b -> b.getBlockPos().asLong()));
        Map<PowerBlockEntity, PowerBlockEntity> source = new HashMap<>();
        for (PowerBlockEntity u : users) {
            int cost = u.isFan() ? fanCost : 0;
            PowerBlockEntity best = null;
            for (PowerBlockEntity g : gens) {
                if (g.getLevel() != u.getLevel() || g.load + cost > capacity || !canBurn(g)) continue;
                if (distance(g, u) > range) continue;
                if (best == null || g.load < best.load) best = g;
            }
            if (best != null) {
                best.load += cost;
                source.put(u, best);
            }
        }
        for (PowerBlockEntity g : gens) burn(g);
        for (PowerBlockEntity u : users) {
            PowerBlockEntity g = source.get(u);
            boolean powered = g != null && g.active;
            if (powered != u.powered) {
                u.powered = powered;
                fans.invalidate(u);
            }
            BlockState s = u.getBlockState();
            if (s.hasProperty(FurnitureBlock.POWERED) && s.getValue(FurnitureBlock.POWERED) != powered) {
                u.getLevel().setBlock(u.getBlockPos(), s.setValue(FurnitureBlock.POWERED, powered), 3);
            }
        }
    }

    /** Shortest distance between the two machines' blocks. */
    double distance(PowerBlockEntity a, PowerBlockEntity b) {
        BlockPos pa = a.getBlockPos(), pb = b.getBlockPos();
        double dx = gap(pa.getX(), pa.getX() + 1, pb.getX(), pb.getX() + 1);
        double dy = gap(pa.getY(), pa.getY() + height(a), pb.getY(), pb.getY() + height(b));
        double dz = gap(pa.getZ(), pa.getZ() + 1, pb.getZ(), pb.getZ() + 1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double gap(double a0, double a1, double b0, double b1) {
        if (a1 < b0) return b0 - a1;
        if (b1 < a0) return a0 - b1;
        return 0;
    }

    List<PowerBlockEntity> machinesNear(PowerBlockEntity g) {
        List<PowerBlockEntity> out = new ArrayList<>();
        for (PowerBlockEntity u : machines()) if (!isGenerator(u) && u.getLevel() == g.getLevel() && distance(g, u) <= range) out.add(u);
        return out;
    }

    // ------------------------------------------------------------------ generators

    boolean canBurn(PowerBlockEntity g) {
        return g.burn > 0 || fuelTicks(g.fuel) > 0;
    }

    /** One grid step: burns for the load (a new item when the current one runs out) and updates the fire. */
    private void burn(PowerBlockEntity g) {
        boolean was = g.active;
        if (hoppers) feed(g);
        if (g.load > 0) {
            if (g.burn <= 0) {
                double ticks = fuelTicks(g.fuel);
                if (ticks > 0) {
                    g.burn += ticks;
                    if (g.fuel.getCount() <= 1) g.fuel = g.fuel.is(Items.LAVA_BUCKET) ? new ItemStack(Items.BUCKET) : ItemStack.EMPTY;
                    else g.fuel.shrink(1);
                    g.setChanged();
                }
            }
            g.active = g.burn > 0;
            if (g.active) {
                g.burn = Math.max(0, g.burn - GRID_TICKS * (double) g.load / capacity);
                g.setChanged();
            }
        } else g.active = false;
        BlockState s = g.getBlockState();
        if (s.hasProperty(FurnitureBlock.LIT) && s.getValue(FurnitureBlock.LIT) != g.active && s.getBlock() instanceof FurnitureBlock fb) {
            fb.setOnAll(g.getLevel(), g.getBlockPos(), s, FurnitureBlock.LIT, g.active);
        }
        if (g.active && g.getLevel() instanceof ServerLevel sl) effects(sl, g);
        if (was != g.active) g.setChanged();
    }

    private void effects(ServerLevel w, PowerBlockEntity g) {
        BlockPos p = g.getBlockPos();
        Vec3 c = Vec3.atCenterOf(p);
        Direction f = FurnitureBlock.front(g.getBlockState());
        w.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX() + 0.5, p.getY() + 2.15, p.getZ() + 0.5, 1, 0.08, 0.05, 0.08, 0.01);
        w.sendParticles(ParticleTypes.SMOKE, c.x + f.getStepX() * 0.55, c.y - 0.1, c.z + f.getStepZ() * 0.55, 1, 0.1, 0.05, 0.1, 0.0);
        BlockPos front = p.relative(f);
        if (w.getBlockState(front).getCollisionShape(w, front).isEmpty() && w.getRandom().nextDouble() < 0.2) {
            w.sendParticles(ParticleTypes.FLAME, c.x + f.getStepX() * 0.52, c.y - 0.2, c.z + f.getStepZ() * 0.52, 1, 0.12, 0.04, 0.12, 0.0);
        }
        if (--g.humTick <= 0) {
            g.humTick = 4;
            w.playSound(null, c.x, c.y, c.z, SoundEvents.BLASTFURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.7f, 0.6f);
        }
    }

    /** Hoppers on either block, pointing into it, pass one fuel item at a time while the slot has room. */
    private void feed(PowerBlockEntity g) {
        ItemStack slot = g.fuel;
        if (!slot.isEmpty() && slot.getCount() >= slot.getMaxStackSize()) return;
        ServerLevel w = (ServerLevel) g.getLevel();
        for (int i = 0; i < height(g); i++) {
            BlockPos self = g.getBlockPos().above(i);
            for (Direction f : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                BlockPos b = self.relative(f);
                BlockState hs = w.getBlockState(b);
                if (!hs.is(Blocks.HOPPER) || hs.getValue(HopperBlock.FACING) != f.getOpposite() || !hs.getValue(HopperBlock.ENABLED)) continue;
                if (!(w.getBlockEntity(b) instanceof HopperBlockEntity h)) continue;
                for (int s = 0; s < h.getContainerSize(); s++) {
                    ItemStack it = h.getItem(s);
                    if (fuelTicks(it) <= 0) continue;
                    if (!slot.isEmpty() && !ItemStack.isSameItemSameComponents(slot, it)) continue;
                    if (slot.isEmpty()) g.fuel = it.copyWithCount(1);
                    else slot.grow(1);
                    it.shrink(1);
                    h.setChanged();
                    g.setChanged();
                    return;
                }
            }
        }
    }

    private final Map<ServerPlayer, GeneratorMenu.View> views = new WeakHashMap<>();

    private void openGenerator(ServerPlayer p, PowerBlockEntity g) {
        GeneratorMenu.View view = new GeneratorMenu.View(g);
        ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.gray());
        pane.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new java.util.LinkedHashSet<>()));
        for (int i = 0; i < 9; i++) if (i != GeneratorMenu.FUEL_SLOT && i != GeneratorMenu.STATUS_SLOT) view.setItem(i, pane.copy());
        view.setItem(GeneratorMenu.STATUS_SLOT, status(g));
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new GeneratorMenu(id, inv, g, view, this::fuelTicks),
                Adv.vanilla(feather.text().parse(message("generator-title")))));
        views.put(p, view);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 0.5f, 0.8f);
    }

    /** The status item: burning or idle, load, fuel left and how many machines are in range. */
    private ItemStack status(PowerBlockEntity g) {
        int machines = machinesNear(g).size();
        ItemStack s = new ItemStack(g.active ? Items.CAMPFIRE : Items.FURNACE);
        TagResolver r = TagResolver.resolver(
                Placeholder.unparsed("load", String.valueOf(g.load)),
                Placeholder.unparsed("capacity", String.valueOf(capacity)),
                Placeholder.unparsed("seconds", String.valueOf(Math.round(g.burn / 20))),
                Placeholder.unparsed("machines", String.valueOf(machines)),
                Placeholder.unparsed("range", String.valueOf((int) range)));
        s.set(DataComponents.ITEM_NAME, Adv.vanilla(feather.text().parse(message(g.active ? "generator-burning" : "generator-idle"), r)));
        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("messages.generator-lore")) {
            lore.add(Adv.vanilla(feather.text().parse(line, r).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        }
        s.set(DataComponents.LORE, new ItemLore(lore));
        return s;
    }

    private void refreshViews() {
        for (Map.Entry<ServerPlayer, GeneratorMenu.View> e : new ArrayList<>(views.entrySet())) {
            ServerPlayer p = e.getKey();
            if (!(p.containerMenu instanceof GeneratorMenu gm) || gm.generator.isRemoved()) {
                views.remove(p);
                continue;
            }
            e.getValue().setItem(GeneratorMenu.STATUS_SLOT, status(gm.generator));
            gm.broadcastChanges();
        }
    }

    /** The generator is going: its windows close. */
    void closeViews(PowerBlockEntity g) {
        for (ServerPlayer p : new ArrayList<>(views.keySet())) {
            if (p.containerMenu instanceof GeneratorMenu gm && gm.generator == g) p.closeContainer();
        }
    }

    // ------------------------------------------------------------------ placing and clicking

    /** Who placed it (the fan's stream stops where they couldn't build); fans blow the way the placer looks. */
    private void placed(FurnitureContext ctx) {
        if (!(ctx.level().getBlockEntity(ctx.origin()) instanceof PowerBlockEntity be)) return;
        ServerPlayer p = ctx.player();
        if (p != null) be.owner = p.getUUID();
        be.setChanged();
        if (p != null && fanItem.equals(ctx.id())) {
            float pitch = p.getXRot();
            Direction look = pitch <= -50 ? Direction.UP : pitch >= 50 ? Direction.DOWN : p.getDirection();
            BlockState s = ctx.level().getBlockState(ctx.origin());
            if (s.hasProperty(FurnitureBlock.FACING_ALL)) ctx.level().setBlock(ctx.origin(), s.setValue(FurnitureBlock.FACING_ALL, look), 3);
        }
        if (p != null) bar(p, fanItem.equals(ctx.id()) ? "placed-fan" : "placed-generator");
    }

    /** Right-click: the generator opens its fuel window; the fan shows its state, and sneak flips push and pull. */
    private InteractionResult use(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        if (p == null) return InteractionResult.PASS;
        if (!(ctx.level().getBlockEntity(ctx.origin()) instanceof PowerBlockEntity be)) return InteractionResult.PASS;
        if (ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        boolean sneakPlace = p.isSecondaryUseActive() && !ctx.held().isEmpty();
        if (sneakPlace) return InteractionResult.PASS;
        Loc at = Loc.of(ctx.level(), ctx.origin());
        if (isGenerator(be)) {
            if (!feather.claimAllows(p, at, ClaimAction.CONTAINER)) {
                bar(p, "claim");
                return InteractionResult.SUCCESS;
            }
            openGenerator(p, be);
            return InteractionResult.SUCCESS;
        }
        if (p.isSecondaryUseActive()) {
            if (!feather.claimAllows(p, at, ClaimAction.BUILD)) {
                bar(p, "claim");
                return InteractionResult.SUCCESS;
            }
            be.reverse = !be.reverse;
            be.sync();
            fans.invalidate(be);
            ctx.level().playSound(null, ctx.origin(), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, be.reverse ? 0.7f : 1.1f);
        }
        fans.status(p, be);
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ Paper records (chunk PDC) -> blocks

    /**
     * Paper kept each chunk's machines as {@code x,y,z,kind,FACING,owner,reverse,burn,fuel;...} in the chunk PDC over
     * barrier blocks (non-persistent display entities showed them). Each one becomes its block here, once.
     */
    public static void initMigration() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (!Pdc.of(chunk).contains(CHUNK_KEY)) return;
            Tasks.later(1, () -> {
                if (level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) == chunk) migrate(level, chunk);
            });
        });
    }

    static void migrate(ServerLevel level, LevelChunk chunk) {
        String data = Pdc.getString(chunk, CHUNK_KEY);
        if (data == null || data.isEmpty()) {
            Pdc.remove(chunk, CHUNK_KEY);
            return;
        }
        PowerModule m = active;
        String fanId = m != null ? m.fanItem : "feather_encased_fan";
        String genId = m != null ? m.generatorItem : "feather_coal_generator";
        int n = 0;
        for (String entry : data.split(";")) {
            String[] f = entry.split(",");
            try {
                int x = Integer.parseInt(f[0]), y = Integer.parseInt(f[1]), z = Integer.parseInt(f[2]);
                boolean fan = "fan".equalsIgnoreCase(f[3]);
                if (!fan && !"generator".equalsIgnoreCase(f[3])) throw new IllegalArgumentException("bad kind");
                Direction facing = Direction.valueOf(f[4].toUpperCase(Locale.ROOT));
                UUID owner = "-".equals(f[5]) ? null : UUID.fromString(f[5]);
                // parse everything before touching the world, so a bad field can't leave a half-migrated machine
                boolean reverse = f.length > 6 && "1".equals(f[6]);
                double burn = f.length > 7 ? Double.parseDouble(f[7]) : 0;
                ItemStack fuel = f.length > 8 && !"-".equals(f[8]) ? PaperItems.fromBytes(level.getServer(), Base64.getDecoder().decode(f[8])) : null;
                BlockPos pos = new BlockPos(x, y, z);
                int height = fan ? 1 : 2;
                boolean intact = true;
                for (int i = 0; i < height; i++) if (!level.getBlockState(pos.above(i)).is(Blocks.BARRIER)) intact = false;
                if (!intact) {
                    // as on Paper: the barriers are gone, so is the machine (never overwrite what is there now)
                    if (fuel != null && !fuel.isEmpty()) {
                        Vec3 c = Vec3.atCenterOf(pos);
                        net.minecraft.world.Containers.dropItemStack(level, c.x, c.y, c.z, fuel);
                    }
                    continue;
                }
                for (int i = 0; i < height; i++) level.setBlock(pos.above(i), Blocks.AIR.defaultBlockState(), 2 | 16);
                BlockState state = ArcanaContent.stateFor(fan ? fanId : genId);
                if (state == null) continue;
                if (fan && state.hasProperty(FurnitureBlock.FACING_ALL)) state = state.setValue(FurnitureBlock.FACING_ALL, facing);
                if (!fan && state.hasProperty(FurnitureBlock.FACING) && facing.getAxis().isHorizontal()) state = state.setValue(FurnitureBlock.FACING, facing);
                if (!ArcanaContent.placeState(level, pos, state)) {
                    Vec3 c = Vec3.atCenterOf(pos);
                    net.minecraft.world.Containers.dropItemStack(level, c.x, c.y, c.z, ArcanaContent.stack(fan ? fanId : genId, 1));
                    if (fuel != null) net.minecraft.world.Containers.dropItemStack(level, c.x, c.y, c.z, fuel);
                    continue;
                }
                if (level.getBlockEntity(pos) instanceof PowerBlockEntity be) {
                    be.owner = owner;
                    be.reverse = reverse;
                    be.burn = Math.max(0, burn);
                    if (fuel != null) be.fuel = fuel;
                    be.sync();
                }
                n++;
            } catch (RuntimeException e) {
                Feather.get().getLogger().warn("Skipping a broken Paper power entry in chunk {}: {}", chunk.getPos(), entry);
            }
        }
        Pdc.remove(chunk, CHUNK_KEY);
        chunk.markUnsaved();
        if (n > 0) Feather.get().getLogger().info("Power: {} Paper machine(s) in chunk {} are blocks now", n, chunk.getPos());
    }

    // ------------------------------------------------------------------ /power

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            reply(sender, "admin-no-permission");
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            ServerPlayer target = feather.findPlayer(sender, args[1]);
            String kind = args[2].toLowerCase(Locale.ROOT);
            int n = 1;
            try {
                if (args.length > 3) n = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
            }
            ItemStack it = switch (kind) {
                case "fan" -> ArcanaContent.stack(fanItem, n);
                case "generator" -> ArcanaContent.stack(generatorItem, n);
                default -> ItemStack.EMPTY;
            };
            if (target == null || it.isEmpty()) {
                reply(sender, target == null ? "admin-no-player" : "admin-unknown-item");
                return true;
            }
            target.getInventory().placeItemBackInInventory(it, net.minecraft.util.Prediction.SERVER_ONLY);
            reply(sender, "admin-gave", arg("count", n), arg("kind", kind), arg("player", target.getGameProfile().name()));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
            ServerPlayer p = sender.player();
            int shown = 0;
            for (PowerBlockEntity m : machines()) {
                if (p != null && (m.getLevel() != p.level() || m.getBlockPos().distSqr(p.blockPosition()) > 32 * 32)) continue;
                BlockPos b = m.getBlockPos();
                String state = isGenerator(m)
                        ? "load " + m.load + "/" + capacity + (m.active ? " burning" : " idle") + ", fuel " + Math.round(m.burn / 20) + "s"
                        : (m.powered ? "powered" : "no power") + ", " + (m.reverse ? "pull" : "push") + ", facing "
                        + FurnitureBlock.front(m.getBlockState()).getName();
                reply(sender, "admin-info-row", arg("kind", isGenerator(m) ? "generator" : "fan"), arg("x", b.getX()), arg("y", b.getY()),
                        arg("z", b.getZ()), arg("state", state));
                shown++;
            }
            if (shown == 0) reply(sender, p == null ? "admin-info-none" : "admin-info-none-near");
            return true;
        }
        reply(sender, "admin-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) return List.of();
        if (args.length == 1) return List.of("give", "info");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return feather.completePlayers(sender, args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return List.of("fan", "generator");
        return List.of();
    }
}
