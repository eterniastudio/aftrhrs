package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** {@link FeatherEvents#SIGN_EDIT} when a player finishes editing a sign. */
@Mixin(SignBlockEntity.class)
abstract class SignBlockEntityMixin {
    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$signEdit(Player player, SignTextSlot slot, List<FilteredText> lines, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) return;
        List<String> plain = new ArrayList<>(lines.size());
        for (FilteredText t : lines) plain.add(t.raw());
        SignBlockEntity self = (SignBlockEntity) (Object) this;
        if (!FeatherEvents.SIGN_EDIT.invoker().allow(sp, self, slot == SignTextSlot.FRONT, plain)) {
            ci.cancel();
            self.setAllowedPlayerEditor(null);
            if (self.getLevel() != null) self.getLevel().sendBlockUpdated(self.getBlockPos(), self.getBlockState(), self.getBlockState(), 3);
        }
    }
}
