package net.axther.aftrhrs.feature.furniture.power;

import net.minecraft.world.entity.item.ItemEntity;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Items in an encased fan's stream can't burn: the stream passes lava, fire or a campfire to blast, smoke or haunt
 * them, and an item that touched the flames (or caught fire on the way) would otherwise burn up before it is done.
 * {@link Fans} marks every item it blows; {@code ItemEntityMixin} makes a marked item fire-immune and ignores fire and
 * lava damage to it for {@link #GRACE} ticks after the stream last touched it, so it can land and cool off.
 * Server thread only.
 */
public final class FanShield {
    /** How long an item stays protected after the stream last touched it (ticks). */
    static final long GRACE = 40;

    private static final Map<ItemEntity, Long> UNTIL = new WeakHashMap<>();
    /** Whether an item is inside a running stream right now (set by {@link Fans}): covers the ticks between fan updates. */
    static Predicate<ItemEntity> inStream = item -> false;

    private FanShield() {
    }

    static void mark(ItemEntity item) {
        UNTIL.put(item, item.level().getGameTime() + GRACE);
        if (item.getRemainingFireTicks() > 0) item.clearFire();
    }

    /** True while the item is (or just was) in a fan's stream. */
    public static boolean shielded(ItemEntity item) {
        if (item.level().isClientSide()) return false;
        Long until = UNTIL.get(item);
        if (until != null) {
            if (item.level().getGameTime() <= until) return true;
            UNTIL.remove(item);
        }
        if (inStream.test(item)) {
            mark(item);
            return true;
        }
        return false;
    }

    static void clear() {
        UNTIL.clear();
    }
}
