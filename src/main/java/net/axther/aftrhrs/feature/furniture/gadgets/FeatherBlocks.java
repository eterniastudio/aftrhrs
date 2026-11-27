package net.axther.aftrhrs.feature.furniture.gadgets;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The feather block (a content block): nothing takes fall damage landing on it, a player falling onto it is slowed
 * down in the last blocks above it (a soft, sinking landing) and feathers puff out on landing and now and then while
 * walking on it.
 */
final class FeatherBlocks {
    private final GadgetsModule g;
    private Set<String> ids = Set.of();
    private double brakeHeight = 3.0, maxFallSpeed = 0.18, stepChance = 0.08;
    private String landSound = "block.wool.fall";
    private final Set<UUID> airborne = new HashSet<>();
    private static final ItemStack FEATHER = new ItemStack(Items.FEATHER);

    FeatherBlocks(GadgetsModule g) {
        this.g = g;
    }

    void load(Yaml c) {
        ids = GadgetsModule.lowerSet(c.getStringList("ids"));
        if (ids.isEmpty() && !c.contains("ids")) ids = Set.of("arcana_feather_block");
        brakeHeight = c.getDouble("brake-height", 3.0);
        maxFallSpeed = Math.abs(c.getDouble("max-fall-speed", 0.18));
        stepChance = c.getDouble("step-particle-chance", 0.08);
        landSound = c.getString("land-sound", "block.wool.fall");
    }

    void forget(UUID u) {
        airborne.remove(u);
    }

    /** The block at {@code b}, or null while its chunk is not fully loaded (never loads one). */
    private static @Nullable BlockState stateIfLoaded(Level level, BlockPos b) {
        LevelChunk c = level.getChunkSource().getChunkNow(b.getX() >> 4, b.getZ() >> 4);
        return c == null ? null : c.getBlockState(b);
    }

    private boolean isFeather(Level level, BlockPos b) {
        if (ids.isEmpty()) return false;
        BlockState s = stateIfLoaded(level, b);
        if (s == null) return false;
        String id = ArcanaContent.id(s);
        return id != null && ids.contains(id);
    }

    /** The feather block an entity stands on (checked a little below the feet, and under the hitbox corners). */
    private boolean onFeather(Entity e) {
        Vec3 l = e.position();
        Level level = e.level();
        if (isFeather(level, BlockPos.containing(l.x, l.y - 0.2, l.z))) return true;
        double w = e.getBbWidth() / 2 - 0.01;
        for (double dx : new double[]{-w, w}) {
            for (double dz : new double[]{-w, w}) {
                if (isFeather(level, BlockPos.containing(l.x + dx, l.y - 0.2, l.z + dz))) return true;
            }
        }
        return false;
    }

    /** True if fall damage for this entity should be cancelled. */
    boolean cushions(Entity e) {
        return onFeather(e);
    }

    void tickPlayer(ServerPlayer p) {
        if (ids.isEmpty() || p.getAbilities().flying || p.isFallFlying() || p.isSpectator()) {
            airborne.remove(p.getUUID());
            return;
        }
        ServerLevel level = (ServerLevel) p.level();
        // a chunk still loading: nothing to cushion yet (airborne stays, so the landing puff still comes)
        if (level.getChunkSource().getChunkNow(p.getBlockX() >> 4, p.getBlockZ() >> 4) == null) return;
        Vec3 v = g.motion(p);
        if (!p.onGround()) {
            if (v.y < -maxFallSpeed - 0.02 && featherBelow(level, p.position())) {
                // Brake the fall above a feather block: like slow falling, so the player sinks in softly.
                p.setDeltaMovement(v.x, Math.max(v.y * 0.5, -maxFallSpeed), v.z);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
                if (p.fallDistance > 1) p.fallDistance = 1;
                if (level.getRandom().nextDouble() < 0.3) puff(level, p.position(), 1);
            }
            if (v.y < -0.1) airborne.add(p.getUUID());
            return;
        }
        boolean on = onFeather(p);
        if (airborne.remove(p.getUUID()) && on) {
            puff(level, p.position(), 12);
            GadgetsModule.sound(level, p.position(), landSound, SoundSource.BLOCKS, 0.8f, 1.2f);
            return;
        }
        if (on && (Math.abs(v.x) > 0.01 || Math.abs(v.z) > 0.01 || p.isSprinting()) && level.getRandom().nextDouble() < stepChance) {
            puff(level, p.position(), 2);
        }
    }

    private boolean featherBelow(ServerLevel level, Vec3 l) {
        BlockPos b = BlockPos.containing(l);
        for (int i = 0; i <= Math.ceil(brakeHeight); i++) {
            BlockPos below = b.below(i);
            if (isFeather(level, below)) return l.y - (below.getY() + 1) <= brakeHeight;
            BlockState s = stateIfLoaded(level, below);
            if (s == null || s.isSolid()) return false;   // a missing chunk ends the search like a solid block
        }
        return false;
    }

    void puff(ServerLevel level, Vec3 at, int count) {
        double y = at.y + 0.1;
        level.sendParticles(ParticleTypes.CLOUD, at.x, y, at.z, Math.max(1, count / 2), 0.3, 0.05, 0.3, 0.02);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER), at.x, y, at.z, count, 0.3, 0.1, 0.3, 0.05);
    }
}
