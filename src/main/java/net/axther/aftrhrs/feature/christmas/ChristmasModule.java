package net.axther.aftrhrs.feature.christmas;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Locale;

/**
 * The Christmas set's behavior (docs in PORTING.md "Christmas"):
 * <ul>
 * <li>Fir tree: hang baubles / candy canes / gingerbread men (the nearest free spot to the click), put a star or an
 * angel on top, wind tree lights on; an empty hand switches the lights, sneak + empty hand takes the nearest thing back.</li>
 * <li>Presents: right-click opens the box (nine slots); sneak + empty hand wraps it (a dialog: who it's for, a note,
 * "don't open until Christmas"). Only the recipient can open a wrapped present (anyone, if it's for anyone); the
 * wrapper can sneak-click to unwrap it again. Breaking one keeps contents and gift in the item.</li>
 * <li>Snow globe: right-click shakes it. Advent wreath: flint and steel lights the next candle, an empty hand snuffs
 * them. Mistletoe: {@link MistletoeBlockEntity}.</li>
 * </ul>
 */
public final class ChristmasModule implements FeatherModule {
    private static volatile ChristmasModule active;
    private final Feather plugin;

    public ChristmasModule(Feather plugin) {
        this.plugin = plugin;
    }

    /** The running module (null while disabled). */
    public static @Nullable ChristmasModule active() {
        return active;
    }

    @Override
    public String name() {
        return "Christmas";
    }

    @Override
    public void enable() {
        active = this;
        plugin.once("christmas.hooks", () -> {
            ArcanaContent.hook(ChristmasContent::isFirTree, new FurnitureHook() {
                @Override
                public InteractionResult onUse(FurnitureContext ctx) {
                    ChristmasModule m = active;
                    return m == null ? InteractionResult.PASS : m.useTree(ctx);
                }
            });
            ArcanaContent.hook(ChristmasContent::isPresent, new FurnitureHook() {
                @Override
                public InteractionResult onUse(FurnitureContext ctx) {
                    ChristmasModule m = active;
                    return m == null ? InteractionResult.PASS : m.usePresent(ctx);
                }
            });
            ArcanaContent.hook(ChristmasContent.SNOW_GLOBE, new FurnitureHook() {
                @Override
                public InteractionResult onUse(FurnitureContext ctx) {
                    return active == null ? InteractionResult.PASS : shake(ctx);
                }
            });
            ArcanaContent.hook(ChristmasContent.ADVENT, new FurnitureHook() {
                @Override
                public InteractionResult onUse(FurnitureContext ctx) {
                    return active == null ? InteractionResult.PASS : advent(ctx);
                }
            });
            // A present's contents and gift tag travel with its item, whatever breaks it.
            net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
                if (!(context.getOptional(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                        instanceof PresentBlockEntity be)) return;
                ItemStack full = be.toItem();
                if (full.isEmpty()) return;
                for (int i = 0; i < drops.size(); i++) {
                    if (drops.get(i).is(full.getItem())) {
                        drops.set(i, full);
                        return;
                    }
                }
            });
        });
    }

    @Override
    public void disable() {
        if (active == this) active = null;
    }

    static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("aftrhrs.christmas." + key, fallback, args);
    }

    /** {@link #tr} as an Adventure component, for dialogs (lang.py collects these keys too). */
    static net.kyori.adventure.text.Component atr(String key, String fallback, ComponentLike... args) {
        return net.kyori.adventure.text.Component.translatable("aftrhrs.christmas." + key, fallback, args);
    }

    /** messages.yml's palette {@code bad}, for the wrap dialog's error line. */
    private static final TextColor BAD = TextColor.color(0xFF8FA3);

    private static void sound(ServerLevel level, BlockPos pos, SoundEvent e, float vol, float pitch) {
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, e, SoundSource.BLOCKS, vol, pitch);
    }

    private static boolean creative(ServerPlayer p) {
        return p.getAbilities().instabuild;
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        if (!p.getInventory().add(s)) p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    // ================================================================== fir tree

    InteractionResult useTree(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        if (p == null || !(ctx.level().getBlockEntity(ctx.origin()) instanceof TreeBlockEntity be)) return InteractionResult.PASS;
        ItemStack held = ctx.held();
        String id = ArcanaContent.id(held);
        Vec3 at = be.local(ctx.hit() != null ? ctx.hit().getLocation() : Vec3.atCenterOf(ctx.clicked()));
        ServerLevel level = ctx.level();
        if (ChristmasContent.isOrnament(id)) {
            int slot = be.nearestFree(at);
            if (slot < 0) {
                p.sendOverlayMessage(tr("tree.full", "There's no room left on this tree."));
                return InteractionResult.SUCCESS;
            }
            be.hang(slot, id);
            if (!creative(p)) held.shrink(1);
            sound(level, ctx.clicked(), SoundEvents.AMETHYST_CLUSTER_PLACE, 0.7f, 1.5f + level.getRandom().nextFloat() * 0.3f);
            return InteractionResult.SUCCESS;
        }
        if (ChristmasContent.isTopper(id)) {
            if (!be.topper().isEmpty()) {
                p.sendOverlayMessage(tr("tree.topped", "This tree already has something on top."));
                return InteractionResult.SUCCESS;
            }
            be.setTopper(id);
            if (!creative(p)) held.shrink(1);
            sound(level, ctx.origin().above(2), SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.2f);
            return InteractionResult.SUCCESS;
        }
        if (ChristmasContent.TREE_LIGHTS.equals(id)) {
            if (be.hasLights()) {
                p.sendOverlayMessage(tr("tree.has_lights", "This tree already has lights."));
                return InteractionResult.SUCCESS;
            }
            be.setLights(true);
            setLit(ctx, true);
            if (!creative(p)) held.shrink(1);
            sound(level, ctx.clicked(), SoundEvents.COPPER_BULB_TURN_ON, 0.8f, 1.2f);
            return InteractionResult.SUCCESS;
        }
        boolean empty = p.getMainHandItem().isEmpty() && p.getOffhandItem().isEmpty();
        if (!empty || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (ctx.sneaking()) {
            ItemStack back = be.takeNearest(at);
            if (back == null) return InteractionResult.PASS;
            if (ChristmasContent.TREE_LIGHTS.equals(ArcanaContent.id(back))) setLit(ctx, false);
            give(p, back);
            sound(level, ctx.clicked(), SoundEvents.ITEM_PICKUP, 0.5f, 1.4f);
            return InteractionResult.SUCCESS;
        }
        if (be.hasLights()) {
            boolean on = !ctx.state().getValue(FurnitureBlock.LIT);
            setLit(ctx, on);
            sound(level, ctx.clicked(), on ? SoundEvents.COPPER_BULB_TURN_ON : SoundEvents.COPPER_BULB_TURN_OFF, 0.8f, 1.1f);
            return InteractionResult.SUCCESS;
        }
        p.sendOverlayMessage(tr("tree.hint", "Decorate it: baubles, candy canes, gingerbread, a star or angel, tree lights."));
        return InteractionResult.SUCCESS;
    }

    private static void setLit(FurnitureContext ctx, boolean on) {
        BlockState s = ctx.level().getBlockState(ctx.origin());
        if (s.getBlock() instanceof FurnitureBlock fb && s.hasProperty(FurnitureBlock.LIT)) {
            fb.setOnAll(ctx.level(), ctx.origin(), s, FurnitureBlock.LIT, on);
        }
    }

    // ================================================================== presents

    InteractionResult usePresent(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        if (p == null || ctx.hand() != InteractionHand.MAIN_HAND
                || !(ctx.level().getBlockEntity(ctx.origin()) instanceof PresentBlockEntity be)) return InteractionResult.PASS;
        boolean empty = p.getMainHandItem().isEmpty() && p.getOffhandItem().isEmpty();
        if (ctx.sneaking() && !empty) return InteractionResult.PASS; // placing blocks against it
        if (!be.wrapped()) {
            if (ctx.sneaking()) {
                if (be.isEmpty()) {
                    p.sendOverlayMessage(tr("present.empty", "Put something inside before you wrap it."));
                    return InteractionResult.SUCCESS;
                }
                openWrapDialog(p, ctx.level(), ctx.origin(), "", "", false, null);
                return InteractionResult.SUCCESS;
            }
            openBox(p, be, ctx.id());
            return InteractionResult.SUCCESS;
        }
        PresentBlockEntity.Gift g = be.gift();
        if (g == null) { // wrapped without a tag (should not happen): treat as for anyone
            unwrap(p, ctx, be, false);
            return InteractionResult.SUCCESS;
        }
        boolean mine = p.getUUID().equals(g.from());
        boolean forMe = g.forAnyone() || p.getUUID().equals(g.to());
        if (mine && ctx.sneaking()) {
            // the wrapper takes the paper off again (to change the contents or the tag)
            setOpen(ctx, true);
            be.setGift(null);
            sound(ctx.level(), ctx.origin(), SoundEvents.BOOK_PAGE_TURN, 1f, 0.8f);
            p.sendOverlayMessage(tr("present.unwrapped_own", "You take the wrapping off again."));
            return InteractionResult.SUCCESS;
        }
        if (!forMe) {
            p.sendOverlayMessage(tr("present.for", "A present for %s, from %s.", Component.literal(g.toName()).withStyle(ChatFormatting.GOLD),
                    Component.literal(g.fromName()).withStyle(ChatFormatting.GOLD)));
            return InteractionResult.SUCCESS;
        }
        long days = g.daysLeft();
        if (days > 0) {
            Component from = Component.literal(g.fromName()).withStyle(ChatFormatting.GOLD);
            p.sendOverlayMessage(days == 1 ? tr("present.wait_one", "From %s. Don't open until Christmas! (1 day to go)", from)
                    : tr("present.wait", "From %s. Don't open until Christmas! (%s days to go)", from, String.valueOf(days)));
            sound(ctx.level(), ctx.origin(), SoundEvents.BUNDLE_INSERT, 0.8f, 1.2f);
            return InteractionResult.SUCCESS;
        }
        unwrap(p, ctx, be, true);
        return InteractionResult.SUCCESS;
    }

    private static void setOpen(FurnitureContext ctx, boolean open) {
        BlockState s = ctx.level().getBlockState(ctx.origin());
        if (s.hasProperty(FurnitureBlock.OPEN)) ctx.level().setBlock(ctx.origin(), s.setValue(FurnitureBlock.OPEN, open), 3);
    }

    private void openBox(ServerPlayer p, PresentBlockEntity be, String id) {
        Component title = ArcanaContent.item(id) != null ? ArcanaContent.stack(id, 1).getHoverName() : Component.literal("Present");
        p.openMenu(new SimpleMenuProvider((cid, inv, pl) -> {
            ChestMenu m = new ChestMenu(MenuType.GENERIC_9x1, cid, inv, be, 1);
            for (int i = 0; i < PresentBlockEntity.SIZE && i < m.slots.size(); i++) {
                Slot old = m.slots.get(i);
                Slot s = new Slot(old.container, old.getContainerSlot(), old.x, old.y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return PresentBlockEntity.allowed(stack);
                    }
                };
                s.index = i;
                m.slots.set(i, s);
            }
            return m;
        }, title));
        sound((ServerLevel) be.getLevel(), be.getBlockPos(), SoundEvents.BUNDLE_INSERT, 0.8f, 1.0f);
    }

    /**
     * The wrap dialog, filled with what was typed before and the reason it didn't work ({@code error}, null the first
     * time): an unknown name opens it again instead of failing quietly.
     */
    private void openWrapDialog(ServerPlayer p, ServerLevel level, BlockPos origin, String to, String note, boolean wait,
                                net.kyori.adventure.text.@Nullable Component error) {
        Dialogs.Menu menu = plugin.dialogs().menu(atr("wrap.title", "Wrap This Present"));
        if (error != null) menu.text(error.color(BAD), 260);
        menu.text(atr("wrap.body", "Who is it for? Leave it blank for anyone."), 260)
                .input(Dialogs.Input.text("to", atr("wrap.to", "To")).initial(to).maxLength(16).width(220))
                .input(Dialogs.Input.text("note", atr("wrap.note", "Note (optional)")).initial(note).maxLength(60).width(260))
                .input(Dialogs.Input.bool("wait", atr("wrap.wait", "Don't open until Christmas")).initial(wait).build());
        menu.button(atr("wrap.confirm", "Wrap It"), atr("wrap.confirm_tip", "Wrap the present"), 120, (pl, resp) -> {
            String typedTo = resp.getText("to") == null ? "" : resp.getText("to");
            String typedNote = resp.getText("note") == null ? "" : resp.getText("note");
            boolean w = Boolean.TRUE.equals(resp.getBoolean("wait"));
            String r = wrap(pl, level, origin, typedTo, typedNote, w);
            if ("unknown".equals(r)) {
                openWrapDialog(pl, level, origin, typedTo, typedNote, w,
                        atr("present.unknown", "Nobody called %s has played here.", net.kyori.adventure.text.Component.text(typedTo.trim())));
            } else if ("far".equals(r)) {
                pl.sendOverlayMessage(tr("present.far", "Stand closer to the present to wrap it."));
            }
        });
        menu.exit(plugin.text().msg("menu.close"), null);
        menu.open(p);
    }

    /** The next Christmas Day on or after {@code day} (epoch day). */
    public static long nextChristmas(LocalDate day) {
        LocalDate xmas = LocalDate.of(day.getYear(), Month.DECEMBER, 25);
        if (day.isAfter(xmas)) xmas = xmas.plusYears(1);
        return xmas.toEpochDay();
    }

    /** Wraps the present at origin; the result as a message key for tests (null = wrapped). */
    public @Nullable String wrap(ServerPlayer p, ServerLevel level, BlockPos origin, @Nullable String toName, @Nullable String note, boolean wait) {
        if (!(level.getBlockEntity(origin) instanceof PresentBlockEntity be)) {
            p.sendOverlayMessage(tr("present.gone", "That present isn't there any more."));
            return "gone";
        }
        if (be.wrapped()) {
            p.sendOverlayMessage(tr("present.already", "Someone already wrapped this present."));
            return "gone";
        }
        if (p.position().distanceToSqr(Vec3.atCenterOf(origin)) > 8 * 8) return "far";
        if (be.isEmpty()) {
            p.sendOverlayMessage(tr("present.empty", "Put something inside before you wrap it."));
            return "empty";
        }
        String name = toName == null ? "" : toName.trim();
        java.util.UUID to = null;
        String shown = "";
        if (!name.isEmpty() && !List.of("anyone", "everyone", "all").contains(name.toLowerCase(Locale.ROOT))) {
            OfflinePlayer op = plugin.lookup().findOffline(p, name);
            if (op == null) return "unknown";   // the dialog opens again with the reason
            to = op.id();
            shown = op.name();
        }
        String text = note == null ? "" : note.strip();
        if (text.length() > 60) text = text.substring(0, 60);
        long until = wait ? nextChristmas(LocalDate.now()) : 0;
        if (until <= LocalDate.now().toEpochDay()) until = 0;
        be.setGift(new PresentBlockEntity.Gift(p.getUUID(), p.getGameProfile().name(), to, shown, text, until));
        BlockState s = level.getBlockState(origin);
        if (s.hasProperty(FurnitureBlock.OPEN)) level.setBlock(origin, s.setValue(FurnitureBlock.OPEN, false), 3);
        p.closeContainer();
        sound(level, origin, SoundEvents.BOOK_PAGE_TURN, 1f, 1.1f);
        sound(level, origin, SoundEvents.WOOL_PLACE, 0.8f, 1.4f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, origin.getX() + 0.5, origin.getY() + 0.7, origin.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0);
        p.sendOverlayMessage(to == null ? tr("present.wrapped_anyone", "Wrapped! Anyone may open it.")
                : tr("present.wrapped", "Wrapped for %s!", Component.literal(shown).withStyle(ChatFormatting.GOLD)));
        return null;
    }

    private void unwrap(ServerPlayer p, FurnitureContext ctx, PresentBlockEntity be, boolean celebrate) {
        PresentBlockEntity.Gift g = be.gift();
        List<ItemStack> gifts = be.takeContents();
        be.setGift(null);
        setOpen(ctx, true);
        for (ItemStack s : gifts) give(p, s);
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin();
        sound(level, o, SoundEvents.BOOK_PAGE_TURN, 1f, 0.7f);
        sound(level, o, SoundEvents.PLAYER_LEVELUP, 0.5f, 1.6f);
        level.sendParticles(ParticleTypes.FIREWORK, o.getX() + 0.5, o.getY() + 0.8, o.getZ() + 0.5, 14, 0.2, 0.2, 0.2, 0.06);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, o.getX() + 0.5, o.getY() + 0.6, o.getZ() + 0.5, 8, 0.35, 0.25, 0.35, 0);
        if (!celebrate || g == null) return;
        p.sendSystemMessage(tr("present.opened", "You open a present from %s!", Component.literal(g.fromName()).withStyle(ChatFormatting.GOLD))
                .copy().withStyle(ChatFormatting.GREEN));
        if (!g.note().isEmpty()) p.sendSystemMessage(Component.literal("“" + g.note() + "”").withStyle(ChatFormatting.ITALIC, ChatFormatting.WHITE));
        if (!g.from().equals(p.getUUID())) {
            ServerPlayer from = plugin.player(g.from());
            if (from != null) {
                // The nickname (its own colours win; a plain one is gold), not the account name.
                Component who = Component.empty().withStyle(ChatFormatting.GOLD).append(Adv.vanilla(Names.display(p)));
                from.sendSystemMessage(tr("present.opened_by", "%s opened your present!", who).copy().withStyle(ChatFormatting.GREEN));
            }
        }
    }

    // ================================================================== snow globe, advent wreath

    static InteractionResult shake(FurnitureContext ctx) {
        if (ctx.player() == null || ctx.sneaking() || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin();
        level.sendParticles(ParticleTypes.SNOWFLAKE, o.getX() + 0.5, o.getY() + 0.45, o.getZ() + 0.5, 18, 0.12, 0.12, 0.12, 0.008);
        sound(level, o, SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 1.4f + level.getRandom().nextFloat() * 0.3f);
        sound(level, o, SoundEvents.POWDER_SNOW_STEP, 0.5f, 1.5f);
        return InteractionResult.SUCCESS;
    }

    static InteractionResult advent(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        BlockState s = ctx.level().getBlockState(ctx.origin());
        if (p == null || !(s.getBlock() instanceof FurnitureBlock fb) || fb.age == null) return InteractionResult.PASS;
        int lit = s.getValue(fb.age);
        int max = fb.spec.stages.count() - 1;
        ItemStack held = ctx.held();
        if (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) {
            if (lit >= max) return InteractionResult.SUCCESS;
            ctx.level().setBlock(ctx.origin(), s.setValue(fb.age, lit + 1), 3);
            boolean charge = held.is(Items.FIRE_CHARGE);
            sound(ctx.level(), ctx.origin(), charge ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE, 0.8f, 1.1f);
            if (!creative(p)) {
                if (charge) held.shrink(1);
                else held.hurtAndBreak(1, p, ctx.hand() == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
            }
            return InteractionResult.SUCCESS;
        }
        if (held.isEmpty() && !ctx.sneaking() && ctx.hand() == InteractionHand.MAIN_HAND && lit > 0) {
            ctx.level().setBlock(ctx.origin(), s.setValue(fb.age, 0), 3);
            sound(ctx.level(), ctx.origin(), SoundEvents.CANDLE_EXTINGUISH, 1f, 1f);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
