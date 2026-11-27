package net.axther.aftrhrs.feature.advancements;

import net.minecraft.advancements.AdvancementHolder;

import java.util.Collection;
import java.util.List;

/**
 * Implemented by {@code ServerAdvancementManager} (mixin): Feather's advancements live in the server's advancement list
 * next to the datapack ones. A datapack reload builds a new manager, which picks up {@link Extra#list} again.
 */
public interface AdvancementInjector {
    /** Replaces every advancement in {@code namespace} with {@code extra} and rebuilds the tree. */
    void aftrhrs$replace(String namespace, Collection<AdvancementHolder> extra);

    /** What a freshly built manager adds (the advancements module's current list; empty while it is off). */
    final class Extra {
        public static volatile Collection<AdvancementHolder> list = List.of();

        private Extra() {
        }
    }
}
