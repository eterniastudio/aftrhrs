package net.axther.aftrhrs.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The trident's loyalty / foil data keys, for {@code ThrownRelicTrident} (a trident subclass with the relic's look). */
@Mixin(ThrownTrident.class)
public interface ThrownTridentAccessor {
    @Accessor("ID_LOYALTY")
    static EntityDataAccessor<Byte> aftrhrs$loyalty() {
        throw new AssertionError();
    }

    @Accessor("ID_FOIL")
    static EntityDataAccessor<Boolean> aftrhrs$foil() {
        throw new AssertionError();
    }
}
