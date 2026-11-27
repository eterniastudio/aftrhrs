package net.axther.aftrhrs.mixin;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import net.axther.aftrhrs.feature.recipes.RecipeInjector;
import net.axther.aftrhrs.feature.recipes.RecipesModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Custom recipes (/recipes) and module-owned recipes (Chest Key) in the server's recipe list; see {@link RecipeInjector}. */
@Mixin(RecipeManager.class)
abstract class RecipeManagerMixin implements RecipeInjector {
    @Shadow @Final @Mutable private RecipeMap recipes;
    @Shadow @Final @Mutable private Collection<RecipeHolder<?>> learnableRecipes;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void aftrhrs$addCustom(HolderLookup.Provider registries, CallbackInfo ci) {
        Collection<RecipeHolder<?>> extra = RecipeInjector.Extra.list;
        if (!extra.isEmpty()) aftrhrs$replace(RecipesModule.NAMESPACE, RecipesModule.PREFIX, extra);
        if (!RecipeInjector.Extra.owned.isEmpty()) aftrhrs$applyOwned();
    }

    @Override
    public void aftrhrs$replace(String namespace, String pathPrefix, Collection<RecipeHolder<?>> extra) {
        aftrhrs$rebuild(key -> {
            Identifier id = key.identifier();
            return id.getNamespace().equals(namespace) && id.getPath().startsWith(pathPrefix);
        }, extra);
    }

    @Override
    public void aftrhrs$applyOwned() {
        Map<ResourceKey<Recipe<?>>, Optional<RecipeHolder<?>>> owned = Map.copyOf(RecipeInjector.Extra.owned);
        List<RecipeHolder<?>> add = new ArrayList<>();
        for (Optional<RecipeHolder<?>> h : owned.values()) h.ifPresent(add::add);
        aftrhrs$rebuild(owned::containsKey, add);
    }

    @Override
    public Object aftrhrs$recipeMap() {
        return recipes;
    }

    /**
     * Fabric API's recipe sync keeps a per-serializer table on every RecipeMap, filled in RecipeMap.create(). A map built
     * through the constructor (as here) lacks it, and Fabric then crashes sending recipes to a joining player
     * ("Invalid player data"). Fill it the same way Fabric does.
     */
    @Unique
    private static void aftrhrs$attachFabricSyncMap(RecipeMap map) {
        try {
            java.lang.reflect.Field f = RecipeMap.class.getDeclaredField("bySyncedSerializer");
            f.setAccessible(true);
            Map<net.minecraft.world.item.crafting.RecipeSerializer<?>, List<RecipeHolder<?>>> by = new java.util.IdentityHashMap<>();
            for (net.minecraft.world.item.crafting.RecipeSerializer<?> s : net.fabricmc.fabric.impl.recipe.sync.RecipeSyncImpl.getSyncedSerializers()) by.put(s, new ArrayList<>());
            for (RecipeHolder<?> h : map.values()) {
                List<RecipeHolder<?>> l = by.get(h.value().getSerializer());
                if (l != null) l.add(h);
            }
            f.set(map, by);
        } catch (NoSuchFieldException e) {
            net.axther.aftrhrs.AftrHrs.LOG.debug("Fabric recipe sync table not present");
        } catch (ReflectiveOperationException | RuntimeException e) {
            net.axther.aftrhrs.AftrHrs.LOG.error("Could not attach Fabric's recipe sync table", e);
        }
    }

    @Unique
    private void aftrhrs$rebuild(Predicate<ResourceKey<Recipe<?>>> drop, Collection<RecipeHolder<?>> extra) {
        Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey<Recipe<?>>, RecipeHolder<?>> e : ((RecipeMapAccessor) recipes).aftrhrs$byKey().entrySet()) {
            if (drop.test(e.getKey())) continue;
            byKey.put(e.getKey(), e.getValue());
        }
        for (RecipeHolder<?> h : extra) byKey.put(h.id(), h);
        ImmutableMultimap.Builder<RecipeType<?>, RecipeHolder<?>> byType = ImmutableMultimap.builder();
        for (RecipeHolder<?> h : byKey.values()) byType.put(h.value().getType(), h);
        recipes = RecipeMapAccessor.aftrhrs$create(byType.build(), ImmutableMap.copyOf(byKey));
        aftrhrs$attachFabricSyncMap(recipes);
        List<RecipeHolder<?>> learnable = new ArrayList<>();
        for (RecipeHolder<?> h : byKey.values()) if (!h.value().isSpecial()) learnable.add(h);
        learnableRecipes = List.copyOf(learnable);
    }
}
