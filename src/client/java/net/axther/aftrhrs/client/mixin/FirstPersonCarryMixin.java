package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.carry.CarryClient;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In first person, a carried block shows as held in the main hand (the off hand stays empty). */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonCarryMixin {
    @Shadow
    private ItemStack mainHandItem;
    @Shadow
    private ItemStack offHandItem;
    @Shadow
    private float mainHandHeight;

    @Inject(method = "tick(Lnet/minecraft/client/player/LocalPlayer;)V", at = @At("TAIL"))
    private void aftrhrs$carriedBlock(LocalPlayer player, CallbackInfo ci) {
        ItemStack block = CarryClient.blockItem(player);
        if (block.isEmpty()) return;
        mainHandItem = block;
        offHandItem = ItemStack.EMPTY;
        mainHandHeight = 1.0f;
    }
}
