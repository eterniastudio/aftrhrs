package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.feature.relics.RelicsInit;
import net.axther.aftrhrs.feature.relics.ThrownRelicTrident;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Relic tridents with their own thrown look (the Tidecaller) are thrown as {@link ThrownRelicTrident}. */
@Mixin(TridentItem.class)
abstract class TridentItemMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "releaseUsing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/Projectile;spawnProjectileFromRotation(Lnet/minecraft/world/entity/projectile/Projectile$ProjectileFactory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;FFF)Lnet/minecraft/world/entity/projectile/Projectile;"))
    private Projectile aftrhrs$relicTrident(Projectile.ProjectileFactory factory, ServerLevel level, ItemStack stack, LivingEntity owner,
                                            float yOffset, float power, float uncertainty) {
        if (RelicsInit.thrownModel(stack) != null) {
            return Projectile.spawnProjectileFromRotation(ThrownRelicTrident::new, level, stack, owner, yOffset, power, uncertainty);
        }
        return Projectile.spawnProjectileFromRotation(factory, level, stack, owner, yOffset, power, uncertainty);
    }
}
