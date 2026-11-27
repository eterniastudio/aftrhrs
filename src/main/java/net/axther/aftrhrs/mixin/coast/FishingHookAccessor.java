package net.axther.aftrhrs.mixin.coast;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The fishing hook's bite timers, for the fishing module (legendary re-bites, debug fast bites). */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
    @Accessor("nibble")
    int aftrhrs$nibble();

    @Accessor("nibble")
    void aftrhrs$setNibble(int ticks);

    @Accessor("timeUntilLured")
    int aftrhrs$lured();

    @Accessor("timeUntilLured")
    void aftrhrs$setLured(int ticks);

    @Accessor("timeUntilHooked")
    void aftrhrs$setHooked(int ticks);

    @Accessor("DATA_BITING")
    static EntityDataAccessor<Boolean> aftrhrs$biting() {
        throw new AssertionError();
    }
}
