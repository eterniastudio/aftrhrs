package net.axther.aftrhrs.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a released pet despawn again like a wild creature ({@code Mob.persistenceRequired} has no public setter). */
@Mixin(Mob.class)
public interface MobPersistenceAccessor {
    @Accessor("persistenceRequired")
    void aftrhrs$setPersistenceRequired(boolean value);
}
