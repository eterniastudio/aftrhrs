package net.axther.aftrhrs.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether the recipe book is open (the cosmetic armor panel steps aside for it). */
@Mixin(AbstractRecipeBookScreen.class)
public interface AbstractRecipeBookScreenAccessor {
    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> aftrhrs$book();
}
