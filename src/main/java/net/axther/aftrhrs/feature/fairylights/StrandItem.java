/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/item/ConnectionItem and its subclasses: hooks on any face, knots on fences, the loose end in hand.)
 */
package net.axther.aftrhrs.feature.fairylights;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A strand item (String of Lights, Pine Garland, Tinsel, Pennant Bunting). Right-click a fence/wall post or any block
 * face to start (a knot or a hook goes there), walk to the other end holding the loose end, right-click again.
 */
public final class StrandItem extends Item {
    public final StrandKind kind;

    public StrandItem(StrandKind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    public static boolean isStrand(ItemStack s) {
        return s.getItem() instanceof StrandItem;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player p = ctx.getPlayer();
        if (p == null) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        ItemStack stack = ctx.getItemInHand();
        if (!matchesPending(p, stack)) return InteractionResult.PASS;
        BlockPos pos = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof HookBlock) {
            if (level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof HookBlockEntity be) {
                useOnFastener(sl, p, ctx.getHand(), stack, be.fastener());
            }
            return InteractionResult.SUCCESS;
        }
        if (KnotEntity.isPost(state)) {
            if (level instanceof ServerLevel sl && allowed(p, sl, pos)) {
                useOnFastener(sl, p, ctx.getHand(), stack, KnotEntity.findOrCreate(sl, pos).fastener());
            }
            return InteractionResult.SUCCESS;
        }
        BlockPlaceContext place = new BlockPlaceContext(ctx);
        BlockPos at = place.getClickedPos();
        BlockState hook = FairyLights.HOOK.defaultBlockState().setValue(HookBlock.FACING, ctx.getClickedFace());
        if (!place.canPlace() || !hook.canSurvive(level, at)) return InteractionResult.PASS;
        if (level instanceof ServerLevel sl) {
            if (!allowed(p, sl, at) || !hasRoom(p, sl, at, null)) return InteractionResult.FAIL;
            if (!level.setBlock(at, hook, 3)) return InteractionResult.FAIL;
            level.playSound(null, at, net.minecraft.sounds.SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7f, 1.3f);
            BlockEntity be = level.getBlockEntity(at);
            if (be instanceof HookBlockEntity h) useOnFastener(sl, p, ctx.getHand(), stack, h.fastener());
        }
        return InteractionResult.SUCCESS;
    }

    /** While stringing, only the same kind of strand may be used (another one in the other hand does nothing). */
    static boolean matchesPending(Player p, ItemStack stack) {
        PlayerFastener pf = PlayerFastener.existing(p);
        Map.Entry<UUID, FastenerRef> pending = pf == null ? null : pf.pending();
        if (pending == null) return true;
        Fastener origin = pending.getValue().resolve(p.level());
        Strand s = origin == null ? null : origin.strand(pending.getKey());
        return s == null || matches(s, stack);
    }

    static boolean matches(Strand s, ItemStack stack) {
        return stack.getItem() instanceof StrandItem si && si.kind == s.kind && FairyLights.spec(stack).equals(s.spec);
    }

    static boolean allowed(Player p, ServerLevel level, BlockPos pos) {
        if (!level.mayInteract(p, pos)) return false;
        Feather f = Feather.get();
        if (f != null && p instanceof ServerPlayer sp && !f.claimAllows(sp, Loc.of(level, pos), ClaimAction.BUILD)) {
            say(p, "claim", "You can't hang that here: this land is claimed.");
            return false;
        }
        return true;
    }

    /** Per-fastener and per-chunk limits (Aftr Hrs: so strands can't be used to lag a server). */
    static boolean hasRoom(Player p, ServerLevel level, BlockPos pos, @Nullable Fastener f) {
        if (p instanceof ServerPlayer sp && sp.hasInfiniteMaterials() && net.axther.aftrhrs.core.Perms.isOp(sp)) return true;
        if (f != null && f.count() >= FairyLightsConfig.maxPerFastener) {
            say(p, "full", "This spot already holds %s strands.", FairyLightsConfig.maxPerFastener);
            return false;
        }
        if (strandsInChunk(level, pos) >= FairyLightsConfig.maxPerChunk) {
            say(p, "chunk", "Too many strands around here (%s per chunk).", FairyLightsConfig.maxPerChunk);
            return false;
        }
        return true;
    }

    /** Strands that start in the chunk of {@code pos}. */
    public static int strandsInChunk(Level level, BlockPos pos) {
        int n = 0;
        var chunk = level.getChunkAt(pos);
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof HookBlockEntity h) n += h.fastener().outgoing().size();
        }
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        AABB box = new AABB(x0, level.getMinY(), z0, x0 + 16, level.getMaxY() + 1, z0 + 16);
        for (KnotEntity k : level.getEntitiesOfClass(KnotEntity.class, box)) n += k.fastener().outgoing().size();
        return n;
    }

    /** Server: start a strand at {@code target}, or finish the one this player is stringing there. */
    public static boolean useOnFastener(ServerLevel level, Player p, InteractionHand hand, ItemStack stack, Fastener target) {
        if (!(stack.getItem() instanceof StrandItem item)) return false;
        PlayerFastener pf = PlayerFastener.of(p);
        Map.Entry<UUID, FastenerRef> pending = pf.pending();
        if (pending != null) {
            Fastener origin = pending.getValue().resolve(level);
            Strand s = origin == null ? null : origin.strand(pending.getKey());
            if (s == null) {
                pf.incoming().clear();
            } else {
                if (!matches(s, stack)) return false;
                if (origin == target || origin.ref().equals(target.ref())) return false;
                if (target.joinedTo(origin.ref())) {
                    say(p, "joined", "These two are already strung together.");
                    return false;
                }
                double dist = origin.point().distanceTo(target.point());
                if (dist > FairyLightsConfig.maxLength) {
                    say(p, "long", "Too far: %s blocks (at most %s).", Math.round(dist), Math.round(FairyLightsConfig.maxLength));
                    return false;
                }
                if (!allowed(p, level, target.pos()) || !hasRoom(p, level, target.pos(), target)) return false;
                s.dest = target.ref();
                s.drop = true;
                s.invalidate();
                target.addIncoming(s.id, origin.ref());
                pf.incoming().remove(s.id);
                origin.markDirty();
                if (!p.hasInfiniteMaterials()) stack.shrink(1);
                Vec3 at = target.point();
                level.playSound(null, at.x, at.y, at.z, FairyLights.SOUND_CONNECT, SoundSource.BLOCKS, 1, 1);
                return true;
            }
        }
        if (!allowed(p, level, target.pos()) || !hasRoom(p, level, target.pos(), target)) return false;
        Strand s = new Strand(UUID.randomUUID(), item.kind, FairyLights.spec(stack), FastenerRef.player(p.getUUID()));
        s.drop = false;
        target.addOutgoing(s);
        pf.addIncoming(s.id, target.ref());
        Vec3 at = target.point();
        level.playSound(null, at.x, at.y, at.z, FairyLights.SOUND_CONNECT, SoundSource.BLOCKS, 0.8f, 1.2f);
        say(p, "started", "Now walk to the other end and right-click it (up to %s blocks).", Math.round(FairyLightsConfig.maxLength));
        return true;
    }

    static void say(Player p, String key, String fallback, Object... args) {
        p.sendOverlayMessage(Component.translatableWithFallback("aftrhrs.fairy_lights." + key, fallback, args).withStyle(ChatFormatting.GOLD));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        StrandSpec spec = FairyLights.spec(stack);
        switch (kind) {
            case LIGHTS, GARLAND -> {
                if (spec.bulbs().isEmpty() && kind == StrandKind.GARLAND) {
                    out.accept(Component.translatableWithFallback("aftrhrs.fairy_lights.tip.garland", "Craft it with lights to weave them in").withStyle(ChatFormatting.GRAY));
                } else if (spec.bulbs().isEmpty()) {
                    out.accept(Component.translatableWithFallback("aftrhrs.fairy_lights.tip.bare", "No lights yet: craft it with up to 8 lights").withStyle(ChatFormatting.GRAY));
                } else {
                    for (int i = 0; i < Math.min(8, spec.bulbs().size()); i++) {
                        StrandSpec.Bulb b = spec.bulbs().get(i);
                        Component name = Component.translatable("item.aftrhrs." + b.style().itemId());
                        out.accept(Component.literal("● ").withColor(b.rgb()).append(name.copy().withStyle(ChatFormatting.GRAY))
                                .append(b.twinkle() ? Component.translatableWithFallback("aftrhrs.fairy_lights.tip.twinkle", " (twinkling)").withStyle(ChatFormatting.DARK_GRAY) : Component.empty()));
                    }
                    if (spec.bulbs().size() > 8) out.accept(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
                }
                if (spec.wire() != StrandSpec.Wire.DARK) {
                    out.accept(Component.translatableWithFallback("aftrhrs.fairy_lights.wire." + spec.wire().getSerializedName(),
                            spec.wire() == StrandSpec.Wire.WHITE ? "White wire" : "Green wire").withStyle(ChatFormatting.DARK_GRAY));
                }
            }
            case BUNTING -> {
                if (spec.flags().isEmpty()) {
                    out.accept(Component.translatableWithFallback("aftrhrs.fairy_lights.tip.bare_bunting", "No pennants yet: craft it with up to 8 pennants").withStyle(ChatFormatting.GRAY));
                } else {
                    for (int i = 0; i < Math.min(8, spec.flags().size()); i++) {
                        StrandSpec.Flag f = spec.flags().get(i);
                        out.accept(Component.literal("▲ ").withColor(f.rgb())
                                .append(Component.translatable("item.aftrhrs." + f.shape().itemId()).withStyle(ChatFormatting.GRAY)));
                    }
                }
                if (!spec.text().isEmpty()) out.accept(Component.literal("“" + spec.text() + "”").withStyle(ChatFormatting.WHITE));
            }
            default -> {
            }
        }
        out.accept(Component.translatableWithFallback("aftrhrs.fairy_lights.tip.use", "Right-click a fence, wall or block, then the other end").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Server: the player's pending strand, if they let go of the item (no longer holding it), is dropped. */
    static void tickPlayer(ServerPlayer p) {
        PlayerFastener pf = PlayerFastener.existing(p);
        if (pf == null) return;
        Map.Entry<UUID, FastenerRef> pending = pf.pending();
        if (pending == null) return;
        ServerLevel level = (ServerLevel) p.level();
        Fastener origin = pending.getValue().resolve(level);
        Strand s = origin == null ? null : origin.strand(pending.getKey());
        if (s == null) {
            if (origin != null || pending.getValue().gone(level)) pf.incoming().clear();
            return;
        }
        if (!matches(s, p.getMainHandItem()) && !matches(s, p.getOffhandItem()) || p.isSpectator() || !p.isAlive()) {
            origin.removeStrand(level, s, null, false);
            pf.incoming().clear();
        }
    }

}
