package net.axther.aftrhrs.mixin.relics;

import net.axther.aftrhrs.feature.relics.BackpackWear;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The death loot: a backpack still worn (no grave took it) drops with the inventory unless keepInventory is on. */
@Mixin(Player.class)
public abstract class PlayerBackpackDropMixin {
    @Inject(method = "dropEquipment", at = @At("HEAD"))
    private void aftrhrs$dropWornBackpack(ServerLevel level, CallbackInfo ci) {
        BackpackWear.dropOnDeath((Player) (Object) this, level);
        net.axther.aftrhrs.feature.cosmetics.Cosmetics.dropOnDeath((Player) (Object) this, level);
    }
}
