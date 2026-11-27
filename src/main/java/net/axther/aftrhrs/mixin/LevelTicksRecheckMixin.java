package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla re-runs tickCheck (areEntitiesLoaded and isPositionTicking) every tick for every container whose head tick
 * is overdue but whose chunk is not ticking (the lazy ring outside simulation distance): 4-6% of the soak's server
 * thread. When the check fails, push that container's next-check time 10-17 ticks ahead (jittered per chunk, so the
 * ring doesn't re-check all at once). Safe because the only other writers (addContainer, updateContainerScheduling via
 * schedule, drainContainers or clearArea) put the real head time, and a new tick can never sort ahead of an
 * already-overdue head. Cost: a chunk that just started ticking runs its first overdue fluid, fire or redstone ticks up
 * to ~0.85 s late. Runtime-only state: no save or network impact. {@code require = 0}: a perf mod that replaces the
 * scheduler just disables this optimisation.
 */
@Mixin(LevelTicks.class)
abstract class LevelTicksRecheckMixin<T> {
    private static final int AFTRHRS$RECHECK_BASE = 10;
    private static final int AFTRHRS$RECHECK_JITTER_MASK = 7; // 10..17 ticks

    @ModifyExpressionValue(method = "sortContainersToTick",
            at = @At(value = "INVOKE", target = "Ljava/util/function/LongPredicate;test(J)Z"),
            require = 0)
    private boolean aftrhrs$backOffFrozenContainer(boolean ticking,
                                                   @Local(argsOnly = true) long currentTick,
                                                   @Local Long2LongMap.Entry entry) {
        if (!ticking) {
            long jitter = HashCommon.mix(entry.getLongKey()) & AFTRHRS$RECHECK_JITTER_MASK;
            entry.setValue(currentTick + AFTRHRS$RECHECK_BASE + jitter); // the same fast-iterator write vanilla does a line above
        }
        return ticking;
    }
}
