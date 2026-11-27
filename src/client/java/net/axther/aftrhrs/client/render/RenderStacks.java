package net.axther.aftrhrs.client.render;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Item stacks block-entity renderers show every frame (an ornament, a propeller, a curio's arm, the wheel...), made once
 * instead of per frame. Render thread only. Tied to the client level: a new level (joining, changing dimension) drops
 * them, as their components may be rebound then.
 *
 * <p>The stacks are shared: pass them only to {@code ItemModelResolver.updateForTopItem}, which just reads them; never
 * change them or hand them to gameplay code.
 */
public final class RenderStacks {
    /** Weak: a level left behind (back on the title screen) is not kept alive by its stacks. */
    private static WeakReference<Level> owner = new WeakReference<>(null);
    private static final Map<String, ItemStack> BY_ID = new HashMap<>();
    private static @Nullable ItemStack clay, wheel;

    private RenderStacks() {
    }

    private static void check(@Nullable Level l) {
        if (l != owner.get()) {
            owner = new WeakReference<>(l);
            BY_ID.clear();
            clay = wheel = null;
        }
    }

    /** {@link ArcanaContent#stack} of one, or EMPTY for an unknown id (cached too). */
    public static ItemStack content(@Nullable Level l, String id) {
        check(l);
        ItemStack s = BY_ID.get(id);
        if (s == null) {
            s = ArcanaContent.stack(id, 1);
            BY_ID.put(id, s);
        }
        return s;
    }

    /** A clay ball (the casting table's clay). */
    public static ItemStack clay(@Nullable Level l) {
        check(l);
        if (clay == null) clay = new ItemStack(Items.CLAY_BALL);
        return clay;
    }

    /** The whetstone wheel shown with its spinning model. */
    public static ItemStack wheel(@Nullable Level l, Identifier model) {
        check(l);
        if (wheel == null) {
            wheel = new ItemStack(Smithing.block(Station.WHEEL));
            wheel.set(DataComponents.ITEM_MODEL, model);
        }
        return wheel;
    }
}
