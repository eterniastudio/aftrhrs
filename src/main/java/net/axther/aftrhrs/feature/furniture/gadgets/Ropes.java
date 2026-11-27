package net.axther.aftrhrs.feature.furniture.gadgets;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vertical ropes: a rope item hangs a rope under a ceiling or under a fence / wall / chain (the top piece is the knot);
 * right-clicking a rope with another one lengthens the column at the bottom. Ropes are climbable blocks
 * ({@code minecraft:climbable}: hold on while sneaking, slide down slowly, no fall damage, jump to climb); walking into
 * one climbs too, as on Paper. Sneak + right-click a rope with an empty hand pulls the column up by one (giving the
 * rope back) and lifts a plain block or the mobs right under it, like a well bucket. Breaking a rope (or the block it
 * hangs from) drops everything below.
 */
final class Ropes {
    private final GadgetsModule g;
    private String ropeId = "arcana_rope", knotId = "arcana_rope_knot";
    private Set<String> itemIds = Set.of("arcana_rope");
    private int maxLength = 64;
    private double climbSpeed = 0.2;
    private String climbSound = "block.ladder.step";
    private boolean pullBlocks = true, pullMobs = true;
    private final Set<Block> supports = new HashSet<>();
    private final List<TagKey<Block>> supportTags = new ArrayList<>();
    private final Map<UUID, String> mode = new HashMap<>();
    private final Map<UUID, Long> lastOnRope = new HashMap<>();
    private boolean cascading;

    Ropes(GadgetsModule g) {
        this.g = g;
    }

    void load(Yaml c) {
        ropeId = c.getString("rope", "arcana_rope").toLowerCase(Locale.ROOT);
        knotId = c.getString("knot", "arcana_rope_knot").toLowerCase(Locale.ROOT);
        itemIds = GadgetsModule.lowerSet(c.contains("items") ? c.getStringList("items") : List.of(ropeId));
        maxLength = Math.max(1, c.getInt("max-length", 64));
        climbSpeed = c.getDouble("climb-speed", 0.2);
        climbSound = c.getString("climb-sound", "block.ladder.step");
        pullBlocks = c.getBoolean("pull-blocks", true);
        pullMobs = c.getBoolean("pull-mobs", true);
        supports.clear();
        supportTags.clear();
        List<String> sup = c.contains("supports") ? c.getStringList("supports")
                : List.of("#minecraft:fences", "#minecraft:walls", "#minecraft:fence_gates", "minecraft:iron_bars", "#minecraft:chains",
                "minecraft:chain", "#minecraft:leaves", "#minecraft:slabs", "#minecraft:trapdoors");
        for (String s : sup) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("#")) {
                Identifier key = Identifier.tryParse(t.substring(1));
                if (key != null) supportTags.add(TagKey.create(Registries.BLOCK, key));
            } else {
                Identifier key = Identifier.tryParse(t.contains(":") ? t : "minecraft:" + t);
                Block b = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
                if (b != null && b != Blocks.AIR) supports.add(b);
            }
        }
    }

    boolean isRopeId(String id) {
        return id != null && (ropeId.equals(id) || knotId.equals(id));
    }

    boolean isRopeItem(String id) {
        return itemIds.contains(id);
    }

    private boolean ropeAt(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && isRopeId(ArcanaContent.id(level.getBlockState(p)));
    }

    // ------------------------------------------------------------------ placing

    private static boolean free(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    private boolean support(BlockState s, ServerLevel level, BlockPos p, Direction face) {
        if (supports.contains(s.getBlock())) return true;
        for (TagKey<Block> t : supportTags) if (s.is(t)) return true;
        return face == Direction.DOWN && s.isSolidRender();
    }

    /** Rope item used on a block: hang a new column under it. */
    void placeAgainst(ServerPlayer p, ServerLevel level, BlockPos clicked, Direction face) {
        if (!support(level.getBlockState(clicked), level, clicked, face)) {
            g.msg(p, g.message("rope-where", "<muted>Ropes hang from a ceiling, or under a fence or wall."));
            return;
        }
        BlockPos target = clicked.below();
        if (ropeAt(level, target)) {
            extend(p, level, target);
            return;
        }
        if (!free(level, target)) {
            g.msg(p, g.message("rope-blocked", "<muted>There's no room for the rope there."));
            return;
        }
        if (!g.mayUse(p, level, target, ClaimAction.BUILD)) return;
        if (!place(level, target, knotId, p.getDirection().getOpposite())) return;
        GadgetsModule.useOne(p, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private boolean place(ServerLevel level, BlockPos at, String id, Direction front) {
        if (ArcanaContent.stateFor(id) == null) {
            g.warnOnce("rope-mech:" + id, "gadgets.yml ropes: '" + id + "' is not a furniture block.");
            return false;
        }
        boolean ok = ArcanaContent.place(level, at, id, front);
        if (ok) GadgetsModule.sound(level, Vec3.atCenterOf(at), "block.wool.place", SoundSource.BLOCKS, 1f, 0.9f);
        return ok;
    }

    private BlockPos bottomOf(ServerLevel level, BlockPos rope) {
        BlockPos b = rope;
        for (int i = 0; i < 1024 && ropeAt(level, b.below()); i++) b = b.below();
        return b;
    }

    private BlockPos topOf(ServerLevel level, BlockPos rope) {
        BlockPos b = rope;
        for (int i = 0; i < 1024 && ropeAt(level, b.above()); i++) b = b.above();
        return b;
    }

    private void extend(ServerPlayer p, ServerLevel level, BlockPos rope) {
        BlockPos bottom = bottomOf(level, rope);
        BlockPos top = topOf(level, rope);
        BlockPos target = bottom.below();
        if (top.getY() - target.getY() + 1 > maxLength) {
            g.msg(p, g.message("rope-too-long", "<muted>The rope can't get any longer."));
            return;
        }
        if (!free(level, target)) {
            g.msg(p, g.message("rope-blocked", "<muted>There's no room for the rope there."));
            return;
        }
        if (!g.mayUse(p, level, target, ClaimAction.BUILD)) return;
        Direction front = FurnitureBlock.front(level.getBlockState(rope));
        if (!place(level, target, ropeId, front)) return;
        GadgetsModule.useOne(p, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------ clicking a rope

    boolean interact(ServerPlayer p, ServerLevel level, BlockPos rope) {
        ItemStack held = p.getMainHandItem();
        String heldId = GadgetsModule.idOf(held);
        if (heldId != null && itemIds.contains(heldId)) {
            extend(p, level, rope);
            return true;
        }
        if (held.isEmpty() && p.isSecondaryUseActive()) {
            pullUp(p, level, rope);
            return true;
        }
        return false;
    }

    private void pullUp(ServerPlayer p, ServerLevel level, BlockPos rope) {
        BlockPos bottom = bottomOf(level, rope);
        if (!ropeAt(level, bottom)) return;
        if (!g.mayUse(p, level, bottom, ClaimAction.BUILD)) return;
        removeRope(level, bottom);
        if (!GadgetsModule.creative(p)) GadgetsModule.give(p, GadgetsModule.item(itemIds.iterator().next()));
        GadgetsModule.sound(level, Vec3.atCenterOf(bottom), "block.wool.break", SoundSource.BLOCKS, 1f, 1.1f);
        // Like a well bucket: what hangs right under the rope comes up one block.
        BlockPos under = bottom.below();
        if (!ropeAt(level, bottom.above())) return; // the whole rope is gone, nothing holds it
        if (pullBlocks && liftable(level, under) && free(level, bottom) && g.mayUse(p, level, under, ClaimAction.BUILD)) {
            level.setBlock(bottom, level.getBlockState(under), 3);
            level.setBlock(under, Blocks.AIR.defaultBlockState(), 3);
        }
        if (pullMobs && g.mayUse(p, level, under, ClaimAction.BUILD)) {
            for (Entity e : level.getEntities((Entity) null, new AABB(under))) {
                if (e instanceof Player || !e.blockPosition().equals(under)) continue;
                e.teleportTo(e.getX(), e.getY() + 1, e.getZ());
            }
        }
    }

    private static boolean liftable(ServerLevel level, BlockPos b) {
        BlockState s = level.getBlockState(b);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.is(Blocks.BARRIER) || s.getDestroySpeed(level, b) < 0) return false;
        if (s.hasBlockEntity()) return false;
        if (ArcanaContent.id(s) != null) return false; // furniture and content blocks keep their data elsewhere
        // Half of a two-block block (door, tall plant, bed, piston and its head): moving one half breaks the other
        // with nothing dropped.
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART)) return false;
        if (s.is(Blocks.PISTON_HEAD) || s.is(Blocks.MOVING_PISTON)) return false;
        if (s.hasProperty(BlockStateProperties.EXTENDED) && s.getValue(BlockStateProperties.EXTENDED)) return false;
        return true;
    }

    private void removeRope(ServerLevel level, BlockPos p) {
        boolean was = cascading;
        cascading = true;
        try {
            BlockState s = level.getBlockState(p);
            level.setBlock(p, s.getFluidState().isSource() ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        } finally {
            cascading = was;
        }
    }

    // ------------------------------------------------------------------ breaking

    /** A rope went away (by a player or anything else): every rope below it falls and drops as an item. */
    void onBroken(ServerLevel level, BlockPos at) {
        if (cascading) return;
        dropBelow(level, at);
    }

    private void dropBelow(ServerLevel level, BlockPos from) {
        BlockPos b = from.below();
        int n = 0;
        for (int i = 0; i < 1024; i++) {
            if (!ropeAt(level, b)) break;
            removeRope(level, b);
            n++;
            b = b.below();
        }
        if (n == 0) return;
        ItemStack item = GadgetsModule.item(itemIds.iterator().next());
        if (item.isEmpty()) return;
        while (n > 0) {
            ItemStack s = item.copyWithCount(Math.min(n, item.getMaxStackSize()));
            n -= s.getCount();
            net.minecraft.world.Containers.dropItemStack(level, from.getX() + 0.5, from.getY() - 0.5, from.getZ() + 0.5, s);
        }
    }

    /** The block a rope hangs from was broken: the whole column falls. */
    void onSupportBroken(ServerLevel level, BlockPos block) {
        if (!ropeAt(level, block.below()) || ropeAt(level, block)) return;
        dropBelow(level, block);
    }

    // ------------------------------------------------------------------ climbing (on top of the climbable tag)

    boolean inRope(Entity e) {
        Long t = lastOnRope.get(e.getUUID());
        if (t != null && e.level().getGameTime() - t <= 10) return true;
        if (!(e.level() instanceof ServerLevel sl)) return false;
        BlockPos b = e.blockPosition();
        return ropeAt(sl, b) || ropeAt(sl, b.above());
    }

    String mode(ServerPlayer p) {
        Long t = lastOnRope.get(p.getUUID());
        if (t == null || p.level().getGameTime() - t > 2) return "none";
        return mode.getOrDefault(p.getUUID(), "none");
    }

    void forget(UUID u) {
        lastOnRope.remove(u);
        mode.remove(u);
    }

    void tickPlayer(ServerPlayer p) {
        if (p.getAbilities().flying || p.isFallFlying() || p.isPassenger() || p.isSpectator()) return;
        ServerLevel level = (ServerLevel) p.level();
        BlockPos feet = p.blockPosition();
        if (!ropeAt(level, feet) && !ropeAt(level, feet.above())) return;
        lastOnRope.put(p.getUUID(), level.getGameTime());
        p.resetFallDistance();
        Input in = p.getLastClientInput();
        boolean moving = in.forward() || in.backward() || in.left() || in.right();
        if (in.jump() || (moving && !in.shift())) {
            mode.put(p.getUUID(), "climb");
            Vec3 v = g.motion(p);
            if (v.y < climbSpeed - 0.01) {
                p.setDeltaMovement(v.x, climbSpeed, v.z);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
            }
            if (level.getGameTime() % 8 == 0) GadgetsModule.sound(level, p.position(), climbSound, SoundSource.PLAYERS, 0.5f, 1f);
        } else if (in.shift()) {
            mode.put(p.getUUID(), "hold");
        } else {
            mode.put(p.getUUID(), p.onGround() ? "ground" : "slide");
        }
    }
}
