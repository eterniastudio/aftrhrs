package net.axther.aftrhrs.feature.recipes;

import net.axther.aftrhrs.core.Feather;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implemented by {@code RecipeManager} (mixin): the custom recipes live in the server's recipe list next to the
 * datapack ones. A datapack reload builds a new manager, which picks up {@link Extra#list} and {@link Extra#owned}
 * again before its recipes are finalized. After {@link #aftrhrs$replace} call {@code finalizeRecipeLoading} and resend
 * the recipe lists.
 */
public interface RecipeInjector {
    /** Replaces every recipe whose id is in {@code namespace} under {@code pathPrefix} with {@code extra}. */
    void aftrhrs$replace(String namespace, String pathPrefix, Collection<RecipeHolder<?>> extra);

    /** Applies {@link Extra#owned} (module-owned single recipes) to this manager's list. */
    void aftrhrs$applyOwned();

    /**
     * The manager's live recipe map (compare by identity only): a new object whenever the recipe list changes:
     * datapack reload (new manager), {@link #aftrhrs$replace}, {@link #aftrhrs$applyOwned}.
     */
    Object aftrhrs$recipeMap();

    /** What a freshly built manager adds (the custom recipes module's enabled recipes; empty while it is off). */
    final class Extra {
        public static volatile Collection<RecipeHolder<?>> list = List.of();
        /** Recipes a module builds from its config (e.g. the Chest Key from locks.key.*); empty = no such recipe. */
        public static final Map<ResourceKey<Recipe<?>>, Optional<RecipeHolder<?>>> owned = new ConcurrentHashMap<>();

        private Extra() {
        }
    }

    /**
     * A module-owned recipe: {@code holder} replaces whatever recipe has {@code key} (also a datapack one), null removes
     * it. Kept across datapack reloads. Applied to the running server at once; recipe lists and books are resent.
     */
    static void own(ResourceKey<Recipe<?>> key, @Nullable RecipeHolder<?> holder) {
        Extra.owned.put(key, Optional.ofNullable(holder));
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        RecipeManager manager = server.getRecipeManager();
        ((RecipeInjector) manager).aftrhrs$applyOwned();
        manager.finalizeRecipeLoading(server.getWorldData().enabledFeatures());
        // Modules enable before the player list exists; nobody to resend to then.
        List<ServerPlayer> online = server.getPlayerList() == null ? List.of() : server.getPlayerList().getPlayers();
        if (!online.isEmpty()) {
            ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(manager.getSynchronizedItemProperties(),
                    manager.getSynchronizedStonecutterRecipes());
            for (ServerPlayer p : online) {
                p.connection.send(packet);
                p.getRecipeBook().sendInitialRecipeBook(p);
            }
        }
        // While starting, the JEI export is built once the server is up anyway.
        Feather f = Feather.get();
        if (f != null && server.getPlayerList() != null) f.recipesUpdated();
    }
}
