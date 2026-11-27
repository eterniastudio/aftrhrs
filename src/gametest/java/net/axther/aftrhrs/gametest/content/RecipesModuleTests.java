package net.axther.aftrhrs.gametest.content;

import io.netty.buffer.Unpooled;
import net.axther.aftrhrs.core.Feather;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The custom recipes module: a recipe written to recipes.yml shows up on /recipes reload (craftable, synced to
 * clients with packets a client can decode), survives a datapack /reload, and is gone after it is removed + reloaded.
 */
public class RecipesModuleTests {
    static String encode(ItemStack s) {
        try {
            var m = Class.forName("net.axther.aftrhrs.feature.recipes.RecipesModule").getDeclaredMethod("encode", ItemStack.class);
            m.setAccessible(true);
            return (String) m.invoke(null, s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void console(MinecraftServer server, String cmd) {
        CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, cmd);
    }

    static boolean crafts(ServerLevel level) {
        List<ItemStack> grid = new ArrayList<>();
        grid.add(new ItemStack(Items.DEAD_BUSH));
        grid.add(new ItemStack(Items.DEAD_BUSH));
        var r = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(2, 1, grid), level);
        return r.isPresent() && r.get().value().assemble(CraftingInput.of(2, 1, grid)).is(Items.EMERALD);
    }

    /**
     * An editor save goes through the data thread but is waited for: right after it returns recipes.yml holds the
     * change (with its header), the other recipes untouched.
     */
    @GameTest
    public void editorSaveLandsBeforeItReturns(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("recipes_sync_save");
        Object module = Feather.get().module(Class.forName("net.axther.aftrhrs.feature.recipes.RecipesModule").asSubclass(net.axther.aftrhrs.core.FeatherModule.class));
        if (module == null) {
            f.add("the recipes module is off");
            f.finish(h);
            return;
        }
        Path file = Feather.get().getDataFolder().resolve("recipes.yml");
        String before = Files.exists(file) ? Files.readString(file) : null;
        Object lastFile = FurnitureBehaviorTests.field(module, "lastFile");
        try {
            Files.writeString(file, "recipes:\n  bt_keep_me:\n    type: shaped\n  bt_sync_drop:\n    type: shaped\n");
            Object ok = FurnitureBehaviorTests.call(module, "write", "bt_sync_drop", null);
            String now = Files.readString(file);
            f.check(Boolean.TRUE.equals(ok), "the save reported a failure");
            f.check(!now.contains("bt_sync_drop"), "the removed recipe is still in the file right after the save:\n" + now);
            f.check(now.contains("bt_keep_me"), "the save dropped another recipe:\n" + now);
            f.check(now.startsWith("#"), "the header is missing:\n" + now);
        } finally {
            if (before == null) Files.deleteIfExists(file);
            else Files.writeString(file, before);
            java.lang.reflect.Field lf = module.getClass().getDeclaredField("lastFile");
            lf.setAccessible(true);
            lf.set(module, lastFile);
        }
        f.finish(h);
    }

    @GameTest(maxTicks = 400, environment = ToolTests.ENV)
    public void customRecipeAddReloadRemove(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        CT.Bot bot = CT.join(h, "btRecipe", true);
        CT.Fails f = new CT.Fails("recipes_module");
        Path file = Feather.get().getDataFolder().resolve("recipes.yml");
        String before = Files.exists(file) ? Files.readString(file) : null;
        String yml = "recipes:\n  bt_bush_emerald:\n    type: shaped\n    enabled: true\n    exact: false\n    result: " + encode(new ItemStack(Items.EMERALD))
                + "\n    inputs:\n    - " + encode(new ItemStack(Items.DEAD_BUSH)) + "\n    - " + encode(new ItemStack(Items.DEAD_BUSH))
                + "\n    - ''\n    - ''\n    - ''\n    - ''\n    - ''\n    - ''\n    - ''\n";
        Files.writeString(file, yml);
        bot.drain();
        console(server, "recipes reload");
        ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> key = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("feather", "custom/bt_bush_emerald"));
        f.check(server.getRecipeManager().byKey(key).isPresent(), "custom recipe not in the recipe list after /recipes reload");
        f.check(crafts(level), "2 dead bushes don't craft an emerald");
        // what online players were sent must decode on a client
        ClientboundUpdateRecipesPacket pkt = new ClientboundUpdateRecipesPacket(server.getRecipeManager().getSynchronizedItemProperties(),
                server.getRecipeManager().getSynchronizedStonecutterRecipes());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundUpdateRecipesPacket.STREAM_CODEC.encode(buf, pkt);
            ClientboundUpdateRecipesPacket.STREAM_CODEC.decode(buf);
        } catch (RuntimeException e) {
            f.add("recipe packet does not survive encode/decode: " + e);
        } finally {
            buf.release();
        }
        // a datapack reload keeps it
        console(server, "reload");
        h.runAfterDelay(100, () -> {
            f.check(server.getRecipeManager().byKey(key).isPresent(), "custom recipe lost on /reload");
            f.check(crafts(level), "custom recipe not craftable after /reload");
            try {
                if (before == null) Files.deleteIfExists(file);
                else Files.writeString(file, before);
            } catch (java.io.IOException e) {
                f.add("could not restore recipes.yml: " + e);
            }
            console(server, "recipes reload");
            f.check(server.getRecipeManager().byKey(key).isEmpty(), "removed custom recipe still registered");
            f.check(!crafts(level), "removed custom recipe still crafts");
            bot.leave();
            f.finish(h);
        });
    }
}
