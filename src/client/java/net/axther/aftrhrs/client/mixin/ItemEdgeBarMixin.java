package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.feature.smithing.Mods;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A bladed smithed tool's Edge: a thin steel-blue bar under the durability bar once it has dulled. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ItemEdgeBarMixin {
    @Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
    private void aftrhrs$edgeBar(Font font, ItemStack stack, int x, int y, @Nullable String text, CallbackInfo ci) {
        if (Smithing.EDGE == null || stack.isEmpty()) return;
        Integer edge = stack.get(Smithing.EDGE);
        ToolBuild b = stack.get(Smithing.BUILD);
        if (edge == null || b == null || edge >= 100) return;
        SmithingDefs.ToolType t = SmithingDefs.tool(b.tool());
        if (t == null || !Mods.edged(t)) return;
        GuiGraphicsExtractor g = (GuiGraphicsExtractor) (Object) this;
        int w = Math.round(13f * edge / 100f);
        g.fill(x + 2, y + 15, x + 15, y + 16, 0xFF000000);
        g.fill(x + 2, y + 15, x + 2 + w, y + 16, edge > 40 ? 0xFF9FC7E8 : 0xFF6E8296);
    }
}
