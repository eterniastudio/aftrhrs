package net.axther.aftrhrs.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Penguins and boats (like a dolphin's Dolphin's Grace, but for the boat). One set of rules for both sides:
 *
 * <ul>
 *   <li><b>Push</b>: wild adult penguins the server sent to push this boat ({@link PenguinCreature#pushing()}, synced)
 *       and swimming within {@link #PUSH_RANGE} blocks add {@link #WILD_EACH} each, counting at most {@link #WILD_CAP};
 *       a tamed adult penguin riding in the boat adds its species' {@code penguin.boat} (+ {@code boat_bond} per bond
 *       level). The total is capped at {@link #TOTAL_CAP}. It multiplies the boat's forward paddling thrust on water.</li>
 *   <li><b>Sled</b>: with a tamed adult penguin aboard, a boat on snow (snow, snow blocks, powder snow, snowy grass /
 *       dirt) glides with friction {@link #SLED_FRICTION} (about 4.5 blocks/s, a third of water speed; vanilla crawls at
 *       1.2) and steps up 1-block snowy bumps. Ice stays vanilla.</li>
 * </ul>
 *
 * <p>Server-authoritative: the server alone decides which penguins push which boat (cap, timeouts, habitat) and which
 * penguin boards which boat; those are synced entity state. The boat's physics (client side for a player's boat,
 * server side for a boat nobody steers) read only that state through this class ({@code BoatPenguinMixin}), so no client
 * can claim a boost the server didn't give it.
 */
public final class PenguinBoats {
    /** Thrust bonus per wild penguin pushing, how many count, and the total cap (wild + pet). */
    public static final double WILD_EACH = 0.10;
    public static final int WILD_CAP = 4;
    public static final double TOTAL_CAP = 0.60;
    /** Blocks from the boat within which a pushing penguin counts. */
    public static final double PUSH_RANGE = 6.0;
    /** Ground friction of a penguin sled on snow (vanilla snow 0.6), and its step height. */
    public static final float SLED_FRICTION = 0.85f;
    public static final float SLED_STEP = 1.0f;

    private PenguinBoats() {
    }

    /** Wild penguins pushing this boat right now (uncapped count). */
    public static int wildPushers(AbstractBoat boat) {
        Level level = boat.level();
        int id = boat.getId();
        return level.getEntitiesOfClass(PenguinCreature.class, boat.getBoundingBox().inflate(PUSH_RANGE),
                p -> p.isAlive() && !p.isTame() && p.pushing() == id && p.isInWater()).size();
    }

    /** The tamed adult penguin riding in this boat, or null. */
    public static PenguinCreature petAboard(AbstractBoat boat) {
        for (Entity e : boat.getPassengers()) {
            if (e instanceof PenguinCreature p && p.isTame() && !p.chick() && p.isAlive()) return p;
        }
        return null;
    }

    /** The pet's own boost: its species' boat value plus its bond. */
    public static double petBoost(PenguinCreature p) {
        return p.cfgD("boat", 0.3) + p.cfgD("boat_bond", 0.02) * p.bond();
    }

    /** The boat's thrust bonus (0 = none): wild pushers + a pet aboard, capped. */
    public static double boost(AbstractBoat boat) {
        double wild = Math.min(WILD_CAP, wildPushers(boat)) * WILD_EACH;
        PenguinCreature pet = petAboard(boat);
        double own = pet == null ? 0 : petBoost(pet);
        return Mth.clamp(wild + own, 0, TOTAL_CAP);
    }

    /** The extra velocity a paddling boat (forward key held, on water) gets this tick: vanilla's thrust x boost. */
    public static Vec3 extraThrust(AbstractBoat boat) {
        double b = boost(boat);
        if (b <= 0) return Vec3.ZERO;
        double a = 0.04 * b;
        float yaw = boat.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw) * a, 0, Mth.cos(yaw) * a);
    }

    /** True while a tamed adult penguin rides in the boat and the boat sits on snow. */
    public static boolean sledding(AbstractBoat boat) {
        return petAboard(boat) != null && onSnow(boat);
    }

    /** The boat's ground friction: a penguin sled glides on snow; anything else (ice too) stays vanilla. */
    public static float sledFriction(AbstractBoat boat, float vanilla) {
        return vanilla < SLED_FRICTION && sledding(boat) ? SLED_FRICTION : vanilla;
    }

    /** Step height of the boat: a penguin sled climbs 1-block snowy bumps, any other boat none. */
    public static float stepHeight(AbstractBoat boat) {
        return !boat.isInWater() && sledding(boat) ? SLED_STEP : 0f;
    }

    /** The boat rests on (or in) snow: a snow layer at its feet or snowy ground right under it. */
    public static boolean onSnow(AbstractBoat boat) {
        Level level = boat.level();
        BlockPos at = BlockPos.containing(boat.getX(), boat.getY() + 0.01, boat.getZ());
        return snowy(level.getBlockState(at)) || snowy(level.getBlockState(at.below()));
    }

    /** Snow layers and blocks, powder snow, and ground blocks covered in snow (snowy grass, podzol, mycelium). */
    public static boolean snowy(BlockState s) {
        if (s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) return true;
        if (s.is(BlockTags.SNOW)) return true;
        return s.hasProperty(BlockStateProperties.SNOWY) && s.getValue(BlockStateProperties.SNOWY);
    }
}
