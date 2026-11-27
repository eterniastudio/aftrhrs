package net.axther.aftrhrs.feature.furniture.curios;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Curios: Supplementaries-style behavior for Arcana furniture, all keyed by furniture id in curios.yml.
 * <ul>
 *   <li>Pedestals show one item on top (swords blade-down, tools upright, the rest floating and turning), stacked
 *       pedestals join into one column, end crystals on pedestals add enchanting power.</li>
 *   <li>Hat stands wear a helmet / head / hat and wobble; the statue holds an item and waves when powered.</li>
 *   <li>Flower boxes hold up to three plants; doormats carry three lines of text and hide one item.</li>
 * </ul>
 * State lives in the piece's {@link CurioBlockEntity}; {@code CurioRenderer} draws it (Paper drew display entities).
 * Sneak + empty-hand rotation is built into furniture now; this module keeps only the curio clicks.
 */
public final class CuriosModule implements FeatherModule {
    private static volatile CuriosModule active;

    private final Feather plugin;
    private final CuriosConfig cfg = new CuriosConfig();
    private final Set<String> warned = new HashSet<>();

    public CuriosModule(Feather plugin) {
        this.plugin = plugin;
    }

    static @Nullable CuriosModule active() {
        return active;
    }

    @Override
    public String name() {
        return "Curios";
    }

    @Override
    public void enable() {
        readConfig();
        active = this;
        plugin.once("curios.hooks", () -> {
            ArcanaContent.hook(id -> {
                CuriosModule m = active;
                return m != null && m.cfg.kindOf(id) != null;
            }, new FurnitureHook() {
                @Override
                public InteractionResult onUse(FurnitureContext ctx) {
                    CuriosModule m = active;
                    return m == null ? InteractionResult.PASS : m.use(ctx);
                }

                @Override
                public void onPlaced(FurnitureContext ctx) {
                    CuriosModule m = active;
                    if (m != null && m.cfg.kindOf(ctx.id()) == CuriosConfig.Kind.PEDESTAL) m.restack(ctx.level(), ctx.origin());
                }
            });
            FeatherEvents.ENCHANTING_POWER.register((level, table, n) -> {
                CuriosModule m = active;
                return m == null || !(level instanceof ServerLevel sl) ? n : m.enchantingPower(sl, table, n);
            });
        });
    }

    @Override
    public void reload() {
        readConfig();
    }

    @Override
    public void disable() {
        if (active == this) active = null;
    }

    private void readConfig() {
        cfg.load(plugin.loadResourceConfig("curios.yml"), this::warnOnce);
    }

    CuriosConfig config() {
        return cfg;
    }

    private void warnOnce(String message) {
        if (warned.add(message)) plugin.getLogger().warn(message);
    }

    // ================================================================== ticking (statue wave) and removal

    void tick(CurioBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || (level.getGameTime() + be.getBlockPos().hashCode()) % 10 != 0) return;
        if (cfg.kindOf(be.furnitureId()) != CuriosConfig.Kind.STATUE) return;
        BlockPos p = be.getBlockPos();
        be.setWaving(level.hasNeighborSignal(p) || level.hasNeighborSignal(p.below()));
    }

    /** A pedestal went away: the column above / below gets its shape back. */
    void removed(CurioBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel sl) || cfg.kindOf(be.furnitureId()) != CuriosConfig.Kind.PEDESTAL) return;
        BlockPos at = be.getBlockPos();
        net.axther.aftrhrs.core.Tasks.later(2, () -> {
            restack(sl, at.below());
            restack(sl, at.above());
        });
    }

    // ================================================================== clicks

    private InteractionResult use(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        if (p == null || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(ctx.level().getBlockEntity(ctx.origin()) instanceof CurioBlockEntity c)) return InteractionResult.PASS;
        ItemStack held = p.getMainHandItem();
        boolean empty = held.isEmpty();
        boolean sneak = p.isSecondaryUseActive();
        CuriosConfig.Kind kind = cfg.kindOf(ctx.id());
        if (kind == null) return InteractionResult.PASS;
        boolean used = switch (kind) {
            case PEDESTAL -> pedestalClick(p, c, held, empty, sneak);
            case HAT_STAND -> hatClick(p, c, held, empty, sneak);
            case STATUE -> statueClick(p, c, held, empty, sneak);
            case FLOWER_BOX -> flowerClick(p, c, ctx, held, empty, sneak);
            case DOORMAT -> matClick(p, c, held, empty, sneak);
        };
        return used ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private boolean allowed(ServerPlayer p, CurioBlockEntity c, ClaimAction action) {
        GameType gm = p.gameMode();
        boolean ok = gm != GameType.SPECTATOR && (gm != GameType.ADVENTURE || action == ClaimAction.ACCESS)
                && plugin.claimAllows(p, Loc.of((ServerLevel) c.getLevel(), c.getBlockPos()), action);
        if (!ok) bar(p, "claim");
        return ok;
    }

    private void bar(ServerPlayer p, String key) {
        Adv.actionBar(p, plugin.text().parse(cfg.msg(key)));
    }

    private static void sound(CurioBlockEntity c, String sound, float pitch) {
        if (sound == null || sound.isBlank() || c.getLevel() == null) return;
        Identifier id = Identifier.tryParse(sound.contains(":") ? sound : "minecraft:" + sound);
        if (id == null) return;
        SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
        BlockPos b = c.getBlockPos();
        c.getLevel().playSound(null, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, ev, SoundSource.BLOCKS, 0.8f, pitch);
    }

    private static ItemStack takeOne(Player p, ItemStack held) {
        ItemStack one = held.copyWithCount(1);
        if (!p.getAbilities().instabuild) held.shrink(1);
        return one;
    }

    private static void give(Player p, ItemStack s) {
        if (p.getMainHandItem().isEmpty()) {
            p.setItemInHand(InteractionHand.MAIN_HAND, s);
            return;
        }
        p.getInventory().placeItemBackInInventory(s, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    private static boolean placesSomething(ItemStack held) {
        return held.getItem() instanceof BlockItem;
    }

    /** Sound plus an observer-detectable update (observers facing the piece fire once). */
    private void changed(CurioBlockEntity c, String sound) {
        sound(c, sound, 1f);
        if (cfg.observerPulse && c.getLevel() instanceof ServerLevel sl) {
            BlockState s = sl.getBlockState(c.getBlockPos());
            s.updateNeighbourShapes(sl, c.getBlockPos(), 3);
        }
    }

    // ---- pedestal

    private boolean pedestalClick(ServerPlayer p, CurioBlockEntity clicked, ItemStack held, boolean empty, boolean sneak) {
        CurioBlockEntity c = topOf(clicked);
        ItemStack stored = c.item();
        if (empty) {
            if (sneak || stored.isEmpty()) return false;
            if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
            c.setItem(ItemStack.EMPTY);
            give(p, stored);
            changed(c, "entity.item_frame.remove_item");
            return true;
        }
        String heldId = ArcanaContent.id(held);
        if (heldId != null && cfg.kindOf(heldId) == CuriosConfig.Kind.PEDESTAL) return false; // stacking: placed as a block
        if (sneak && placesSomething(held)) return false;
        if (!stored.isEmpty()) {
            bar(p, "pedestal-full");
            return true;
        }
        if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
        c.setItem(takeOne(p, held));
        changed(c, "entity.item_frame.add_item");
        return true;
    }

    private @Nullable CurioBlockEntity pedestalAt(ServerLevel level, BlockPos pos, @Nullable String variant) {
        if (!level.isLoaded(pos)) return null;
        if (!(level.getBlockEntity(pos) instanceof CurioBlockEntity c)) return null;
        String id = c.furnitureId();
        if (cfg.kindOf(id) != CuriosConfig.Kind.PEDESTAL) return null;
        if (variant != null && !cfg.pedestalVariant(id).equals(variant)) return null;
        return c;
    }

    private CurioBlockEntity topOf(CurioBlockEntity c) {
        if (!cfg.stackEnabled || !(c.getLevel() instanceof ServerLevel sl)) return c;
        String variant = cfg.pedestalVariant(c.furnitureId());
        CurioBlockEntity top = c;
        for (int i = 0; i < 64; i++) {
            CurioBlockEntity up = pedestalAt(sl, top.getBlockPos().above(), variant);
            if (up == null) break;
            top = up;
        }
        return top;
    }

    /** Gives a column of same-kind pedestals its connected shape (bottom / middle / top pieces). */
    void restack(ServerLevel level, BlockPos start) {
        if (!cfg.stackEnabled) return;
        CurioBlockEntity any = pedestalAt(level, start, null);
        if (any == null) return;
        String variant = cfg.pedestalVariant(any.furnitureId());
        CurioBlockEntity bottom = any;
        for (int i = 0; i < 64; i++) {
            CurioBlockEntity down = pedestalAt(level, bottom.getBlockPos().below(), variant);
            if (down == null) break;
            bottom = down;
        }
        List<CurioBlockEntity> column = new ArrayList<>();
        column.add(bottom);
        for (int i = 0; i < 64; i++) {
            CurioBlockEntity up = pedestalAt(level, column.getLast().getBlockPos().above(), variant);
            if (up == null) break;
            column.add(up);
        }
        int n = column.size();
        CurioBlockEntity top = column.getLast();
        // Items on covered pieces move to the top (or drop if the top already shows one).
        for (int i = 0; i < n - 1; i++) {
            CurioBlockEntity piece = column.get(i);
            ItemStack s = piece.item();
            if (s.isEmpty()) continue;
            piece.setItem(ItemStack.EMPTY);
            if (top.item().isEmpty()) top.setItem(s);
            else {
                BlockPos b = piece.getBlockPos();
                net.minecraft.world.Containers.dropItemStack(level, b.getX() + 0.5, b.getY() + 1.1, b.getZ() + 0.5, s);
            }
        }
        for (int i = 0; i < n; i++) {
            CurioBlockEntity piece = column.get(i);
            String want = n == 1 ? variant : i == 0 ? variant + cfg.stackBottom : i == n - 1 ? variant + cfg.stackTop : variant + cfg.stackMiddle;
            if (ArcanaContent.stateFor(want) == null) {
                warnOnce("Pedestal stack piece '" + want + "' is not a furniture block; the column keeps plain pieces.");
                want = variant;
            }
            if (!want.equals(piece.furnitureId())) swap(level, piece, want);
        }
    }

    /** Replaces a curio's piece with another id in place (same orientation), keeping what it holds. */
    private void swap(ServerLevel level, CurioBlockEntity c, String toId) {
        BlockPos pos = c.getBlockPos();
        CurioBlockEntity keep = new CurioBlockEntity(pos, c.getBlockState());
        c.copyTo(keep);
        c.silent = true;
        boolean ok = ArcanaContent.replace(level, pos, toId);
        CurioBlockEntity now = level.getBlockEntity(pos) instanceof CurioBlockEntity n ? n : null;
        if (now != null && now != c) keep.copyTo(now);
        else if (!ok || now == null) {
            c.silent = false;
            for (ItemStack s : keep.takeContents()) net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
        }
    }

    // ---- hat stand

    private boolean hatClick(ServerPlayer p, CurioBlockEntity c, ItemStack held, boolean empty, boolean sneak) {
        ItemStack stored = c.item();
        if (empty) {
            if (sneak) {
                if (!allowed(p, c, ClaimAction.ACCESS)) return true;
                c.startWobble();
                sound(c, "block.wood.hit", 1.3f);
                return true;
            }
            if (stored.isEmpty()) return false;
            if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
            c.setItem(ItemStack.EMPTY);
            give(p, stored);
            changed(c, "item.armor.equip_leather");
            return true;
        }
        if (!cfg.hats.matches(held)) return false;
        if (!stored.isEmpty()) {
            bar(p, "hat-full");
            return true;
        }
        if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
        c.setItem(takeOne(p, held));
        changed(c, "item.armor.equip_leather");
        return true;
    }

    // ---- statue

    private boolean statueClick(ServerPlayer p, CurioBlockEntity c, ItemStack held, boolean empty, boolean sneak) {
        ItemStack stored = c.item();
        if (empty) {
            if (sneak || stored.isEmpty()) return false;
            if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
            c.setItem(ItemStack.EMPTY);
            give(p, stored);
            changed(c, "entity.item_frame.remove_item");
            return true;
        }
        if (sneak && placesSomething(held)) return false;
        if (!stored.isEmpty()) {
            bar(p, "pedestal-full");
            return true;
        }
        if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
        c.setItem(takeOne(p, held));
        changed(c, "entity.item_frame.add_item");
        return true;
    }

    // ---- flower box

    /** The block a plant item stands for in a flower box (sweet berries are the bush), or null if it has none. */
    public static @Nullable BlockState plantState(Item item) {
        if (item == net.minecraft.world.item.Items.SWEET_BERRIES) {
            return net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3);
        }
        if (item == net.minecraft.world.item.Items.GLOW_BERRIES) return net.minecraft.world.level.block.Blocks.CAVE_VINES.defaultBlockState();
        return item instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : null;
    }

    private boolean flowerClick(ServerPlayer p, CurioBlockEntity c, FurnitureContext ctx, ItemStack held, boolean empty, boolean sneak) {
        int slots = cfg.slots.size();
        if (empty) {
            if (sneak) return false;
            int slot = lookedAtSlot(p, c);
            if (slot < 0) return false;
            if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
            Item m = c.plant(slot);
            c.setPlant(slot, null, slots);
            if (m != null) give(p, new ItemStack(m));
            sound(c, "block.grass.break", 1f);
            return true;
        }
        if (ArcanaContent.id(held) != null || !cfg.plants.matches(held) || plantState(held.getItem()) == null) return false;
        int free = -1;
        for (int i = 0; i < slots; i++) {
            if (c.plant(i) == null) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            bar(p, "flower-box-full");
            return true;
        }
        if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
        Item m = takeOne(p, held).getItem();
        c.setPlant(free, m, slots);
        sound(c, "block.grass.place", 1f);
        return true;
    }

    /** The planted slot closest to where the player looks (-1 if the box is empty). */
    private int lookedAtSlot(ServerPlayer p, CurioBlockEntity c) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getLookAngle().normalize();
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < cfg.slots.size(); i++) {
            if (c.plant(i) == null) continue;
            org.joml.Vector3f s = cfg.slots.get(i);
            Vec3 point = modelPoint(c, s.x, s.y + 0.15f * 16f, s.z);
            Vec3 to = point.subtract(eye);
            double along = to.dot(dir);
            double d = to.subtract(dir.scale(Math.max(0, along))).lengthSqr();
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * World position of a model point (model px, 0..16, floor y = 0, model north = the side facing the placer): the
     * piece draws model (8,8,8) at the block center, turned by its yaw (curios SPEC).
     */
    public static Vec3 modelPoint(CurioBlockEntity c, double mx, double my, double mz) {
        double yaw = Math.toRadians(c.yaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double a = (mx - 8) / 16.0, b = (my - 8) / 16.0, d = (8 - mz) / 16.0;
        BlockPos o = c.getBlockPos();
        return new Vec3(o.getX() + 0.5 + rx * a + fx * d, o.getY() + 0.5 + b, o.getZ() + 0.5 + rz * a + fz * d);
    }

    // ---- doormat

    private boolean matClick(ServerPlayer p, CurioBlockEntity c, ItemStack held, boolean empty, boolean sneak) {
        if (sneak) {
            ItemStack hidden = c.hidden();
            if (empty) {
                if (hidden.isEmpty()) return false;
                if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
                c.setHidden(ItemStack.EMPTY);
                give(p, hidden);
                sound(c, "block.wool.hit", 1f);
                return true;
            }
            if (!cfg.hideItems) return false;
            if (!hidden.isEmpty()) {
                bar(p, "hidden-full");
                return true;
            }
            if (!allowed(p, c, ClaimAction.CONTAINER)) return true;
            ItemStack one = takeOne(p, held);
            c.setHidden(one);
            Adv.actionBar(p, plugin.text().parse(cfg.msg("hidden"), Text.component("item", Adv.adventure(one.getHoverName()))));
            sound(c, "block.wool.place", 1f);
            return true;
        }
        if (!((empty && cfg.editEmptyHand) || (!empty && cfg.editItems.matches(held)))) return false;
        if (!allowed(p, c, ClaimAction.BUILD)) return true;
        openMatEditor(p, c);
        return true;
    }

    private void openMatEditor(ServerPlayer p, CurioBlockEntity c) {
        List<String> old = c.text();
        Dialogs.Menu m = plugin.dialogs().menu(plugin.text().parse(cfg.msg("doormat-title")));
        m.text(plugin.text().parse(cfg.msg("doormat-lines")));
        for (int i = 0; i < 3; i++) {
            m.input(Dialogs.Input.text("line" + i, Component.text("Line " + (i + 1)))
                    .initial(i < old.size() ? old.get(i) : "").maxLength(cfg.maxLine).width(220).build());
        }
        m.columns(2);
        ServerLevel level = (ServerLevel) c.getLevel();
        BlockPos pos = c.getBlockPos();
        m.button(plugin.text().parse(cfg.msg("doormat-save")), null, 100, (pl, view) -> {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 3; i++) lines.add(clean(view.getText("line" + i)));
            saveMat(pl, level, pos, lines);
        });
        m.button(plugin.text().parse(cfg.msg("doormat-clear")), null, 100, pl -> saveMat(pl, level, pos, List.of()));
        m.exit(Component.text("Cancel"), null);
        m.open(p);
    }

    private String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(b::appendCodePoint);
        String out = b.toString().strip();
        return out.length() > cfg.maxLine ? out.substring(0, cfg.maxLine) : out;
    }

    private void saveMat(ServerPlayer p, ServerLevel level, BlockPos pos, List<String> lines) {
        if (p.level() != level || p.distanceToSqr(Vec3.atCenterOf(pos)) > 100) return;
        if (!(level.getBlockEntity(pos) instanceof CurioBlockEntity c) || cfg.kindOf(c.furnitureId()) != CuriosConfig.Kind.DOORMAT) return;
        if (!allowed(p, c, ClaimAction.BUILD)) return;
        List<String> keep = new ArrayList<>(lines);
        while (!keep.isEmpty() && keep.getLast().isBlank()) keep.removeLast();
        c.setText(keep);
        if (!keep.isEmpty()) bar(p, "doormat-saved");
        sound(c, "item.ink_sac.use", 1f);
    }

    // ================================================================== enchanting

    /**
     * End crystals on pedestals near an enchanting table count as bookshelves: pedestals where vanilla looks for
     * bookshelves (the ring two blocks out, at the table's level or one up, with a power transmitter - air - in
     * between). Effective bookshelves = real + crystals x bookshelves-each, capped; vanilla then rolls offers, clues
     * and the enchanting itself from that count, so they always agree.
     */
    private int enchantingPower(ServerLevel level, BlockPos table, int shelves) {
        if (!cfg.pedestalEnabled || !cfg.enchantEnabled || cfg.shelvesEach <= 0) return shelves;
        int crystals = 0;
        for (BlockPos off : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            BlockPos between = table.offset(off.getX() / 2, off.getY(), off.getZ() / 2);
            if (!level.getBlockState(between).is(BlockTags.ENCHANTMENT_POWER_TRANSMITTER)) continue;
            CurioBlockEntity c = pedestalAt(level, table.offset(off), null);
            if (c != null && cfg.enchantItems.matches(c.item())) crystals++;
        }
        if (crystals <= 0) return shelves;
        return Math.min(Math.max(cfg.maxShelves, shelves), shelves + crystals * cfg.shelvesEach);
    }
}
