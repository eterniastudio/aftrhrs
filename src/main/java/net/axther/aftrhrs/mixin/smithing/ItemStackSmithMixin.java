package net.axther.aftrhrs.mixin.smithing;

import net.axther.aftrhrs.feature.smithing.Effects;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.ToolStats;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Smithed tools wear by their own rules (Reinforced, Unyielding, Everlasting) and are never destroyed: at 0 durability
 * they break (stop working, keep everything) until repaired at the Assembly Bench.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackSmithMixin {
    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true)
    private void aftrhrs$smithWear(int amount, ServerLevel level, @Nullable ServerPlayer player, Consumer<?> onBreak, CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (Smithing.BUILD == null || !self.has(Smithing.BUILD) || !self.isDamageableItem()) return;
        ci.cancel();
        if (player != null && player.hasInfiniteMaterials() || self.has(Smithing.BROKEN)) return;
        int cost = Effects.durabilityCost(self, amount, level.getRandom());
        if (cost <= 0) return;
        int dmg = self.getDamageValue() + cost;
        if (dmg >= self.getMaxDamage()) {
            self.setDamageValue(self.getMaxDamage());
            self.set(Smithing.BROKEN, Unit.INSTANCE);
            ToolStats.apply(self, level.registryAccess());
            if (player != null) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 0.8f, 0.8f);
                player.sendOverlayMessage(Component.translatable("aftrhrs.smithing.broke", self.getHoverName()).withStyle(ChatFormatting.RED));
            }
        } else {
            self.setDamageValue(dmg);
        }
    }
}
