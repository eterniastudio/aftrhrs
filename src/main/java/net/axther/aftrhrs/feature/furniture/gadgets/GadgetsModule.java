package net.axther.aftrhrs.feature.furniture.gadgets;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.migrate.PaperItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Supplementaries-style behavior for Arcana furniture and blocks, all keyed by id in gadgets.yml: bamboo spikes (slow,
 * hurt, potion infusion), the feather block (no fall damage, slow landing), sacks (overencumbered), goblets and jars
 * (fluids, cookies), cages and jars catching small mobs, and climbable ropes.
 *
 * <p>State lives in the piece's {@link GadgetBlockEntity} (and, in the same {@code feather:gadget_*} keys as on
 * Paper, in a jar's or cage's item custom data while it is carried). Overlays and the caged mob are drawn by
 * {@code GadgetRenderer} (Paper spawned unsaved display entities and a frozen mob).
 */
public final class GadgetsModule implements FeatherModule, FeatherCommand {
    private static volatile GadgetsModule active;

    final Feather feather;
    Yaml cfg;

    final Spikes spikes = new Spikes(this);
    final FeatherBlocks featherBlocks = new FeatherBlocks(this);
    final Sacks sacks = new Sacks(this);
    final Vessels vessels = new Vessels(this);
    final Critters critters = new Critters(this);
    final Ropes ropes = new Ropes(this);

    private final Set<String> warned = new HashSet<>();
    private final List<Tasks.Task> tasks = new ArrayList<>();

    public GadgetsModule(Feather feather) {
        this.feather = feather;
    }

    static @Nullable GadgetsModule active() {
        return active;
    }

    @Override
    public String name() {
        return "Gadgets";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        feather.bind("gadgets", this);
        feather.once("gadgets.events", GadgetsModule::registerEvents);
    }

    @Override
    public void reload() {
        cfg = feather.loadResourceConfig("gadgets.yml");
        spikes.load(cfg.section("spikes"));
        featherBlocks.load(cfg.section("feather-block"));
        sacks.load(cfg.section("sack"));
        vessels.load(cfg.section("goblets"), cfg.section("jars"));
        critters.load(cfg.section("cages"), cfg.section("jars"));
        ropes.load(cfg.section("ropes"));
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        tasks.add(Tasks.timer(1, 1, this::tickPlayers));
        tasks.add(Tasks.timer(40, sacks.interval(), sacks::tick));
    }

    @Override
    public void disable() {
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        if (active == this) active = null;
    }

    private static void registerEvents() {
        ArcanaContent.hook(id -> {
            GadgetsModule m = active;
            return m != null && m.isGadget(id);
        }, new FurnitureHook() {
            @Override
            public InteractionResult onUse(FurnitureContext ctx) {
                GadgetsModule m = active;
                return m == null ? InteractionResult.PASS : m.use(ctx);
            }

            @Override
            public boolean onBreak(FurnitureContext ctx) {
                GadgetsModule m = active;
                return m == null || m.onBreak(ctx);
            }

            @Override
            public void onPlaced(FurnitureContext ctx) {
                GadgetsModule m = active;
                if (m != null) m.onPlaced(ctx);
            }

            @Override
            public void onRemoved(FurnitureContext ctx) {
                GadgetsModule m = active;
                if (m != null && m.ropes.isRopeId(ctx.id())) m.ropes.onBroken(ctx.level(), ctx.origin());
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            GadgetsModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            return m.useBlock(p, sl, hand, hit.getBlockPos(), hit.getDirection());
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            GadgetsModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND || !(entity instanceof LivingEntity le)) {
                return InteractionResult.PASS;
            }
            return m.critters.tryCatch(p, le) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            GadgetsModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(entity instanceof LivingEntity le) || !m.critters.holdsCatcher(p)) {
                return InteractionResult.PASS;
            }
            return m.critters.tryCatch(p, le) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            GadgetsModule m = active;
            if (m == null || !source.is(DamageTypeTags.IS_FALL)) return true;
            if (m.featherBlocks.cushions(entity)) {
                m.featherBlocks.puff((ServerLevel) entity.level(), entity.position(), 14);
                return false;
            }
            return !m.ropes.inRope(entity);
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            GadgetsModule m = active;
            if (m != null && level instanceof ServerLevel sl) m.ropes.onSupportBroken(sl, pos);
        });
        // A gadget with contents broken by anything but a player (explosions): its one piece drop carries them.
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            GadgetsModule m = active;
            if (m == null || !(context.getOptional(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                    instanceof GadgetBlockEntity be) || !be.hasState() || be.silent) return;
            ItemStack stateful = m.withState(be);
            if (stateful.isEmpty()) return;
            boolean replaced = false;
            for (int i = 0; i < drops.size(); i++) {
                if (drops.get(i).is(stateful.getItem())) {
                    if (!replaced) {
                        drops.set(i, stateful);
                        replaced = true;
                    } else if (drops.get(i).getCount() > 1) {
                        drops.get(i).shrink(1);
                    }
                }
            }
            if (!replaced) drops.add(stateful);
            be.silent = true; // the block entity must not drop it again on removal
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            GadgetsModule m = active;
            if (m != null) m.forget(handler.player);
        });
    }

    // ------------------------------------------------------------------ helpers shared by the features

    void warnOnce(String key, String message) {
        if (warned.add(key)) feather.getLogger().warn(message);
    }

    void msg(ServerPlayer p, String mini, TagResolver... r) {
        if (mini == null || mini.isBlank()) return;
        Adv.actionBar(p, feather.text().parse(mini, r));
    }

    String message(String key, String def) {
        return cfg.getString("messages." + key, def);
    }

    static Set<String> lowerSet(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    /** Ex-Oraxen id of a stack, or null. */
    static @Nullable String idOf(ItemStack stack) {
        return ArcanaContent.id(stack);
    }

    static ItemStack item(String id) {
        return ArcanaContent.stack(id, 1);
    }

    /** Game mode, then land claims. */
    boolean mayUse(ServerPlayer p, ServerLevel level, BlockPos at, ClaimAction action) {
        GameType gm = p.gameMode();
        if (gm == GameType.SPECTATOR) return false;
        if (gm == GameType.ADVENTURE && action != ClaimAction.ACCESS && action != ClaimAction.CONTAINER) return false;
        boolean ok = feather.claimAllows(p, Loc.of(level, at), action);
        if (!ok) msg(p, message("claimed", "<bad>Someone else's land."));
        return ok;
    }

    static boolean creative(Player p) {
        return p.getAbilities().instabuild;
    }

    /** Takes one of the held item and gives {@code back} (may be empty) in its place. Creative keeps everything. */
    static void useOne(Player p, InteractionHand hand, ItemStack back) {
        if (creative(p)) return;
        ItemStack held = p.getItemInHand(hand);
        if (held.getCount() <= 1) {
            p.setItemInHand(hand, back == null ? ItemStack.EMPTY : back);
        } else {
            held.shrink(1);
            if (back != null && !back.isEmpty()) give(p, back);
        }
    }

    static void give(Player p, ItemStack item) {
        if (item == null || item.isEmpty()) return;
        p.getInventory().placeItemBackInInventory(item, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    static void sound(Level level, Vec3 at, String key, SoundSource src, float vol, float pitch) {
        Identifier id = Identifier.tryParse(key.contains(":") ? key : "minecraft:" + key);
        if (id == null) return;
        SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
        level.playSound(null, at.x, at.y, at.z, ev, src, vol, pitch);
    }

    /**
     * World position of a furniture model point (model px, 0..16, floor y=0), from the curios SPEC: the piece draws
     * model (8,8,8) at the block center, turned by its yaw.
     */
    static Vec3 modelPoint(GadgetBlockEntity be, double mx, double my, double mz) {
        double yaw = Math.toRadians(be.yaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double a = (mx - 8) / 16.0, b = (my - 8) / 16.0, c = (8 - mz) / 16.0;
        BlockPos o = be.getBlockPos();
        return new Vec3(o.getX() + 0.5 + rx * a + fx * c, o.getY() + 0.5 + b, o.getZ() + 0.5 + rz * a + fz * c);
    }

    boolean isGadget(String id) {
        return spikes.isSpikes(id) || vessels.isVessel(id) || critters.isCage(id) || ropes.isRopeId(id);
    }

    /** Rebuilds what the piece shows (overlay or caged mob) from its state and syncs it. */
    void refresh(GadgetBlockEntity be) {
        String id = be.furnitureId();
        be.overlay = null;
        be.mobPreview = null;
        if ("mob".equals(be.kind)) be.mobPreview = critters.preview(be);
        else if (id != null && vessels.isVessel(id)) be.overlay = vessels.overlay(be, id);
        be.sync();
    }

    // ------------------------------------------------------------------ ticking

    /** Each player's movement in the last tick, from positions (the server's velocity of a player is not reliable). */
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Map<UUID, Vec3> moved = new HashMap<>();

    Vec3 motion(ServerPlayer p) {
        Vec3 v = moved.get(p.getUUID());
        return v == null ? Vec3.ZERO : v;
    }

    private void tickPlayers() {
        for (ServerPlayer p : feather.onlinePlayers()) {
            Vec3 now = p.position();
            Vec3 before = lastPos.put(p.getUUID(), now);
            moved.put(p.getUUID(), before == null ? Vec3.ZERO : now.subtract(before));
            ropes.tickPlayer(p);
            featherBlocks.tickPlayer(p);
        }
    }

    private void forget(ServerPlayer p) {
        lastPos.remove(p.getUUID());
        moved.remove(p.getUUID());
        ropes.forget(p.getUUID());
        featherBlocks.forget(p.getUUID());
        sacks.forget(p);
    }

    void tick(GadgetBlockEntity be) {
        Level level = be.getLevel();
        if (!(level instanceof ServerLevel sl)) return;
        // the schedule first: most ticks end here, before any id lookup (no per-BE role cache: the spike and cage id
        // sets change on a gadgets.yml reload)
        long t = sl.getGameTime() + (be.getBlockPos().asLong() & 0xFF);
        boolean spikeTurn = t % spikes.interval() == 0, cageTurn = t % 100 == 0;
        if (!spikeTurn && !cageTurn) return;
        String id = be.furnitureId();
        if (id == null) return;
        if (spikeTurn && spikes.isSpikes(id)) spikes.tick(sl, be);
        if (cageTurn && critters.isCage(id)) critters.egg(sl, be);
    }

    // ------------------------------------------------------------------ placing and breaking

    private void onPlaced(FurnitureContext ctx) {
        if (!(ctx.level().getBlockEntity(ctx.origin()) instanceof GadgetBlockEntity be)) return;
        if (readItemState(ctx.level(), ctx.held(), be)) refresh(be);
    }

    /** A player breaks a gadget holding something: it drops as one item carrying the contents. */
    private boolean onBreak(FurnitureContext ctx) {
        if (!(ctx.level().getBlockEntity(ctx.origin()) instanceof GadgetBlockEntity be) || !be.hasState()) return true;
        ServerPlayer p = ctx.player();
        // Furniture break hooks run before the claims' own break check (content registers first), so the claim is
        // checked here: otherwise anyone could break a filled jar / caged mob in someone else's claim and keep it.
        if (p != null && !mayUse(p, ctx.level(), ctx.origin(), ClaimAction.BUILD)) return false;
        be.silent = true;
        ItemStack drop = withState(be);
        BlockState os = ctx.level().getBlockState(ctx.origin());
        ctx.level().destroyBlock(ctx.origin(), false, p);
        if (ctx.level().getBlockState(ctx.origin()).is(os.getBlock())) {
            be.silent = false; // could not remove it
            return false;
        }
        if (p == null || !creative(p)) {
            BlockPos o = ctx.origin();
            net.minecraft.world.Containers.dropItemStack(ctx.level(), o.getX() + 0.5, o.getY() + 0.5, o.getZ() + 0.5, drop);
        }
        return false; // removed here; the vanilla break is cancelled
    }

    /** A gadget with contents removed some other way: its contents leave as the stateful item. */
    void dropWithState(GadgetBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel sl)) return;
        BlockPos o = be.getBlockPos();
        net.minecraft.world.Containers.dropItemStack(sl, o.getX() + 0.5, o.getY() + 0.5, o.getZ() + 0.5, withState(be));
    }

    // ------------------------------------------------------------------ item state (custom data keys as on Paper)

    static final String K_KIND = "feather:gadget_kind", K_FLUID = "feather:gadget_fluid", K_UNITS = "feather:gadget_units",
            K_ITEM = "feather:gadget_item", K_MOB = "feather:gadget_mob", K_MOB_TYPE = "feather:gadget_mob_type",
            K_MOB_SIG = "feather:gadget_mob_sig";
    private static final String[] STATE_KEYS = {K_KIND, K_FLUID, K_UNITS, K_ITEM, K_MOB, K_MOB_TYPE, K_MOB_SIG};

    static CompoundTag data(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        return cd == null ? new CompoundTag() : cd.copyTag();
    }

    static boolean hasState(ItemStack s) {
        return !s.isEmpty() && data(s).contains(K_KIND);
    }

    /** A carried gadget's state into a placed piece. */
    boolean readItemState(ServerLevel level, ItemStack s, GadgetBlockEntity be) {
        CompoundTag t = data(s);
        if (!t.contains(K_KIND)) return false;
        be.kind = t.getString(K_KIND).orElse(null);
        be.fluid = t.getString(K_FLUID).orElse(null);
        be.units = t.getIntOr(K_UNITS, 0);
        byte[] item = t.getByteArray(K_ITEM).orElse(null);
        ItemStack proto = item == null ? null : PaperItems.fromBytes(level.getServer(), item);
        be.proto = proto == null ? ItemStack.EMPTY : proto;
        be.mob = t.getByteArray(K_MOB).orElse(null);
        be.mobSig = t.getByteArray(K_MOB_SIG).orElse(null);
        be.mobType = t.getString(K_MOB_TYPE).orElse(null);
        return true;
    }

    /** The piece's own item carrying its state (filled jar, full cage), with a "Contains:" line. */
    ItemStack withState(GadgetBlockEntity be) {
        String id = be.furnitureId();
        ItemStack out = id == null ? ItemStack.EMPTY : item(id);
        if (out.isEmpty() || !be.hasState() || !(be.getLevel() instanceof ServerLevel sl)) return out;
        CompoundTag t = data(out);
        t.putString(K_KIND, be.kind);
        if (be.fluid != null) t.putString(K_FLUID, be.fluid);
        if (be.units != 0) t.putInt(K_UNITS, be.units);
        if (!be.proto.isEmpty()) t.put(K_ITEM, new ByteArrayTag(PaperItems.toBytes(sl.getServer(), be.proto.copyWithCount(1))));
        if (be.mob != null) t.put(K_MOB, new ByteArrayTag(be.mob));
        if (be.mobSig != null) t.put(K_MOB_SIG, new ByteArrayTag(be.mobSig));
        if (be.mobType != null) t.putString(K_MOB_TYPE, be.mobType);
        out.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        describe(out, sl);
        return out;
    }

    static void clearItemState(ItemStack s) {
        CompoundTag t = data(s);
        for (String k : STATE_KEYS) t.remove(k);
        if (t.isEmpty()) s.remove(DataComponents.CUSTOM_DATA);
        else s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        stripLore(s);
    }

    private static final String LORE_PREFIX = "Contains: ";

    /** Adds "Contains: ..." to a gadget item holding a drink, cookies or a mob. */
    void describe(ItemStack item, ServerLevel level) {
        CompoundTag t = data(item);
        String kind = t.getStringOr(K_KIND, "");
        String what;
        String extra = "";
        switch (kind) {
            case "mob" -> what = Vessels.cap(t.getStringOr(K_MOB_TYPE, "mob"));
            case "fluid" -> {
                String f = t.getStringOr(K_FLUID, "");
                if (f.equals("potion") || f.equals("stew")) {
                    byte[] b = t.getByteArray(K_ITEM).orElse(null);
                    ItemStack proto = b == null ? null : PaperItems.fromBytes(level.getServer(), b);
                    what = proto == null ? Vessels.cap(f) : proto.getHoverName().getString();
                } else what = Vessels.cap(f);
                Vessels.Kind k = vessels.kindOf(idOf(item));
                if (k != null && k.capacity > 1) extra = " (" + t.getIntOr(K_UNITS, 0) + "/" + k.capacity + ")";
            }
            case "cookies" -> {
                byte[] b = t.getByteArray(K_ITEM).orElse(null);
                ItemStack proto = b == null ? null : PaperItems.fromBytes(level.getServer(), b);
                what = proto == null ? "Cookies" : proto.getHoverName().getString();
                Vessels.Kind k = vessels.kindOf(idOf(item));
                if (k != null && k.capacity > 1) extra = " (" + t.getIntOr(K_UNITS, 0) + "/" + k.capacity + ")";
            }
            default -> {
                return;
            }
        }
        stripLore(item);
        ItemLore lore = item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(LORE_PREFIX + what + extra).withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        lines.addAll(lore.lines());
        item.set(DataComponents.LORE, new ItemLore(lines));
    }

    static void stripLore(ItemStack item) {
        ItemLore lore = item.get(DataComponents.LORE);
        if (lore == null) return;
        List<Component> lines = new ArrayList<>(lore.lines());
        if (lines.removeIf(c -> c.getString().startsWith(LORE_PREFIX))) item.set(DataComponents.LORE, new ItemLore(lines));
    }

    // ------------------------------------------------------------------ interaction dispatch

    private InteractionResult use(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        if (p == null || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        String id = ctx.id();
        boolean used;
        if (ropes.isRopeId(id)) used = ropes.interact(p, ctx.level(), ctx.origin());
        else if (!(ctx.level().getBlockEntity(ctx.origin()) instanceof GadgetBlockEntity be)) used = false;
        else if (spikes.isSpikes(id)) used = spikes.interact(p, ctx.level(), be);
        else if ("mob".equals(be.kind)) used = critters.interactFull(p, ctx.level(), be);
        else if (vessels.isVessel(id)) used = vessels.interact(p, ctx.level(), be, id);
        else used = false;
        return used ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** Rope placing and letting a caught mob out of a carried cage. */
    private InteractionResult useBlock(ServerPlayer p, ServerLevel level, InteractionHand hand, BlockPos clicked, net.minecraft.core.Direction face) {
        if (hand != InteractionHand.MAIN_HAND) {
            // a rope in the off hand would be placed by vanilla as a bare block outside the column logic (floating,
            // not hanging): ropes are only ever hung by the module, from the main hand
            String off = idOf(p.getOffhandItem());
            if (off != null && ropes.isRopeItem(off)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        }
        ItemStack held = p.getMainHandItem();
        String heldId = idOf(held);
        if (heldId == null) return InteractionResult.PASS;
        if (ropes.isRopeItem(heldId) && !ropes.isRopeId(ArcanaContent.id(level.getBlockState(clicked)))) {
            ropes.placeAgainst(p, level, clicked, face);
            return InteractionResult.SUCCESS; // ropes are only ever hung by the module (columns, knots)
        }
        if (critters.isFullCatcher(held) && !p.isSecondaryUseActive()) {
            critters.releaseFromItem(p, level, clicked, face);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // ------------------------------------------------------------------ /gadgets (staff inspection)

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (!sender.hasPermission("feather.gadgets.admin")) {
            sender.sendMessage(feather.text().parse("<bad>You can't use that."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reload();
            sender.sendMessage(feather.text().parse("<good>Gadgets reloaded."));
            return true;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            sender.sendMessage("Players only (or /gadgets reload).");
            return true;
        }
        info(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission("feather.gadgets.admin")) return List.of("info", "reload");
        return List.of();
    }

    private void info(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        int shown = 0;
        BlockPos c = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-6, -6, -6), c.offset(6, 6, 6))) {
            String id = ArcanaContent.id(level.getBlockState(b));
            if (id == null || !isGadget(id)) continue;
            StringBuilder sb = new StringBuilder("GADGET " + id + " @" + b.getX() + "," + b.getY() + "," + b.getZ());
            if (level.getBlockEntity(b) instanceof GadgetBlockEntity be) {
                if (be.kind != null) sb.append(" kind=").append(be.kind);
                if (be.fluid != null) sb.append(" fluid=").append(be.fluid);
                if (be.units != 0) sb.append(" units=").append(be.units);
                if (be.mobType != null) sb.append(" mob=").append(be.mobType);
                if (be.spikeEffects != null) sb.append(" effects=").append(be.spikeEffects).append(" charges=").append(be.spikeCharges);
                if (be.overlay != null) sb.append(" [").append(be.overlay).append("]");
                if (be.mobPreview != null) sb.append(" [caged mob shown]");
            }
            p.sendSystemMessage(Component.literal(sb.toString()));
            shown++;
        }
        var in = p.getLastClientInput();
        p.sendSystemMessage(Component.literal("INPUT fwd=" + in.forward() + " jump=" + in.jump() + " sneak=" + in.shift()
                + " ground=" + p.onGround() + " rope=" + ropes.mode(p) + " motion=" + motion(p)));
        p.sendSystemMessage(Component.literal("GADGETS " + shown + " near you"));
    }

    static boolean isAir(BlockState s) {
        return s.isAir() || s.is(Blocks.CAVE_AIR) || s.is(Blocks.VOID_AIR);
    }
}
