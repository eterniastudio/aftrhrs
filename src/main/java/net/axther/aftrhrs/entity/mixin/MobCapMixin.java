package net.axther.aftrhrs.entity.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wild Arcana creatures come from the additive spawner (ArcanaSpawner), so vanilla's mob cap doesn't count them (as if
 * MISC); the undead variants stand in for zombies / skeletons and still count as monsters.
 */
@Mixin(NaturalSpawner.class)
abstract class MobCapMixin {
    @WrapOperation(method = "createState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;getCategory()Lnet/minecraft/world/entity/MobCategory;"))
    private static MobCategory aftrhrs$arcanaOutsideCap(EntityType<?> type, Operation<MobCategory> original, @Local Entity entity) {
        return ArcanaEntities.countsTowardMobCap(entity) ? original.call(type) : MobCategory.MISC;
    }
}
