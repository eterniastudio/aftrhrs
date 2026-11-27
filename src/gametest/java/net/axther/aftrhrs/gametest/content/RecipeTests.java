package net.axther.aftrhrs.gametest.content;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every shaped / shapeless / cooking / stonecutting / smithing recipe on the server (vanilla, content, storage,
 * custom): crafted for real in a crafting table menu by a player (the result slot shows this recipe's result, taking
 * it uses the ingredients up), cooked in a real furnace / smoker / blast furnace for the mod's own cooking recipes,
 * and looked up for the rest. Content recipes are checked against their data file's result.
 */
public class RecipeTests {
    static ItemStack first(Ingredient ing, ContextMap ctx) {
        ItemStack s = ing.display().resolveForFirstStack(ctx);
        if (s.isEmpty()) s = ing.items().findFirst().map(hh -> new ItemStack(hh)).orElse(ItemStack.EMPTY);
        return s.copyWithCount(1);
    }

    /** The data file's result id for a content recipe, or null. */
    static String fileResult(Identifier id) {
        String path = "/data/" + id.getNamespace() + "/recipe/" + id.getPath() + ".json";
        try (InputStream in = RecipeTests.class.getResourceAsStream(path)) {
            if (in == null) return null;
            JsonObject j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!j.has("result")) return null;
            var r = j.get("result");
            return r.isJsonPrimitive() ? r.getAsString() : r.getAsJsonObject().get("id").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    static String key(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    @GameTest(maxTicks = 6000, padding = 8)
    public void everyRecipeCrafts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btCraft", true);
        ServerPlayer p = bot.p;
        ContextMap ctx = SlotDisplayContext.fromLevel(level);
        CT.Fails f = new CT.Fails("recipes");
        BlockPos table = h.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        List<RecipeHolder<?>> all = new ArrayList<>(level.getServer().getRecipeManager().getRecipes());
        int[] counts = new int[6];
        List<RecipeHolder<?>> cooking = new ArrayList<>();
        FurnitureSweepTests.stepped(h, all, 150, holder -> {
            Recipe<?> r = holder.value();
            Identifier id = holder.id().identifier();
            String file = fileResult(id);
            try {
                switch (r) {
                    case ShapedRecipe sr -> {
                        counts[0]++;
                        List<ItemStack> grid = new ArrayList<>();
                        for (int i = 0; i < 9; i++) grid.add(ItemStack.EMPTY);
                        List<Optional<Ingredient>> ings = sr.getIngredients();
                        for (int y = 0; y < sr.getHeight(); y++) {
                            for (int x = 0; x < sr.getWidth(); x++) {
                                Optional<Ingredient> o = ings.get(y * sr.getWidth() + x);
                                if (o.isPresent()) grid.set(y * 3 + x, first(o.get(), ctx));
                            }
                        }
                        craft(p, level, table, id, sr, grid, file, f);
                    }
                    case ShapelessRecipe sl -> {
                        counts[1]++;
                        List<ItemStack> grid = new ArrayList<>();
                        for (int i = 0; i < 9; i++) grid.add(ItemStack.EMPTY);
                        List<Ingredient> ings = sl.placementInfo().ingredients();
                        for (int i = 0; i < ings.size() && i < 9; i++) grid.set(i, first(ings.get(i), ctx));
                        craft(p, level, table, id, sl, grid, file, f);
                    }
                    case AbstractCookingRecipe cr -> {
                        counts[2]++;
                        ItemStack in = first(cr.input(), ctx);
                        @SuppressWarnings("unchecked")
                        RecipeType<AbstractCookingRecipe> type = (RecipeType<AbstractCookingRecipe>) cr.getType();
                        var found = level.getServer().getRecipeManager().getRecipeFor(type, new SingleRecipeInput(in), level);
                        ItemStack want = cr.assemble(new SingleRecipeInput(in));
                        f.check(found.isPresent(), id + ": cooking input " + key(in) + " finds no recipe");
                        if (found.isPresent()) {
                            ItemStack got = found.get().value().assemble(new SingleRecipeInput(in));
                            f.check(ItemStack.isSameItemSameComponents(got, want), id + ": " + key(in) + " cooks into " + key(got) + " (recipe " + found.get().id().identifier() + ") not " + key(want));
                        }
                        if (file != null) f.check(key(want).equals(file), id + ": result " + key(want) + " but the file says " + file);
                        if (!id.getNamespace().equals("minecraft")) cooking.add(holder);
                    }
                    case StonecutterRecipe st -> {
                        counts[3]++;
                        ItemStack in = first(st.input(), ctx);
                        ItemStack want = st.assemble(new SingleRecipeInput(in));
                        boolean listed = false;
                        for (var e : level.recipeAccess().stonecutterRecipes().selectByInput(in).entries()) {
                            if (e.recipe().recipe().isPresent() && e.recipe().recipe().get().id().equals(holder.id())) listed = true;
                        }
                        f.check(listed, id + ": stonecutter does not offer it for " + key(in));
                        if (file != null) f.check(key(want).equals(file), id + ": result " + key(want) + " but the file says " + file);
                    }
                    case SmithingTransformRecipe sm -> {
                        counts[4]++;
                        ItemStack t = sm.templateIngredient().map(i -> first(i, ctx)).orElse(ItemStack.EMPTY);
                        ItemStack b = first(sm.baseIngredient(), ctx);
                        ItemStack a = sm.additionIngredient().map(i -> first(i, ctx)).orElse(ItemStack.EMPTY);
                        SmithingRecipeInput in = new SmithingRecipeInput(t, b, a);
                        f.check(sm.matches(in, level), id + ": smithing inputs don't match");
                        var found = level.getServer().getRecipeManager().getRecipeFor(RecipeType.SMITHING, in, level);
                        f.check(found.isPresent() && found.get().id().equals(holder.id()), id + ": smithing table picks " + found.map(x -> x.id().identifier().toString()).orElse("nothing"));
                        ItemStack want = sm.assemble(in);
                        if (file != null) f.check(key(want).equals(file), id + ": result " + key(want) + " but the file says " + file);
                    }
                    default -> counts[5]++;
                }
            } catch (RuntimeException ex) {
                f.add(id + ": exception " + ex);
                net.axther.aftrhrs.AftrHrs.LOG.error("[bt-content] recipe {} crashed", id, ex);
            }
            bot.drain();
        }, () -> {
            // the mod's own cooking recipes in real furnaces (fuel, cook time, output slot)
            for (RecipeHolder<?> holder : cooking) cookForReal(h, level, holder, ctx, f);
            net.axther.aftrhrs.AftrHrs.LOG.info("[bt-content] recipes: {} shaped, {} shapeless, {} cooking, {} stonecutting, {} smithing, {} other",
                    counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
            f.check(counts[0] + counts[1] > 1900, "only " + (counts[0] + counts[1]) + " crafting recipes");
            p.closeContainer();
            bot.leave();
            f.finish(h);
        });
    }

    static void craft(ServerPlayer p, ServerLevel level, BlockPos table, Identifier id, CraftingRecipe r, List<ItemStack> grid, String file, CT.Fails f) {
        p.getInventory().clearContent();
        CraftingMenu menu = new CraftingMenu(7, p.getInventory(), ContainerLevelAccess.create(level, table));
        p.containerMenu = menu;
        for (int i = 0; i < 9; i++) menu.getSlot(1 + i).set(grid.get(i).copy());
        ItemStack want = r.assemble(net.minecraft.world.item.crafting.CraftingInput.of(3, 3, grid));
        ItemStack shown = menu.getSlot(0).getItem();
        if (file != null) f.check(key(want).equals(file), id + ": result " + key(want) + " but the file says " + file);
        if (shown.isEmpty()) {
            f.add(id + ": crafting table shows nothing for " + describe(grid));
        } else if (!ItemStack.isSameItemSameComponents(shown, want) || shown.getCount() != want.getCount()) {
            var other = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, net.minecraft.world.item.crafting.CraftingInput.of(3, 3, grid), level);
            f.add(id + ": table shows " + shown.getCount() + "x " + key(shown) + " not " + want.getCount() + "x " + key(want)
                    + " (shadowed by " + other.map(x -> x.id().identifier().toString()).orElse("?") + ")");
        } else {
            // take it: the result goes to the cursor, every grid slot loses one item (remainders stay)
            menu.clicked(0, 0, ContainerInput.PICKUP, p);
            ItemStack carried = menu.getCarried();
            // same item and count (tool-stats stamps the crafter on tools as they are taken)
            f.check(carried.is(want.getItem()) && carried.getCount() == want.getCount(), id + ": took " + carried + " not " + want);
            for (int i = 0; i < 9; i++) {
                ItemStack in = grid.get(i);
                ItemStack now = menu.getSlot(1 + i).getItem();
                if (in.isEmpty()) continue;
                ItemStack rem = in.getItem().getCraftingRemainder() == null ? ItemStack.EMPTY : in.getItem().getCraftingRemainder().create();
                boolean ok = now.isEmpty() || (!rem.isEmpty() && now.is(rem.getItem()));
                f.check(ok, id + ": grid slot " + i + " still holds " + now + " after crafting");
            }
            menu.setCarried(ItemStack.EMPTY);
        }
        for (int i = 0; i < 9; i++) menu.getSlot(1 + i).set(ItemStack.EMPTY);
        p.containerMenu = p.inventoryMenu;
    }

    static String describe(List<ItemStack> grid) {
        StringBuilder sb = new StringBuilder("[");
        for (ItemStack s : grid) sb.append(s.isEmpty() ? "-" : key(s) + (s.getComponentsPatch().isEmpty() ? "" : "{..}")).append(' ');
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ the JEI export (RecipeExport)

    static Object export(String method, Object... args) {
        try {
            Class<?> c = Class.forName("net.axther.aftrhrs.net.RecipeExport");
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
                m.setAccessible(true);
                return m.invoke(null, args);
            }
            throw new IllegalStateException("no RecipeExport." + method);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A record component of a RecipeExport.Result. */
    static Object part(Object record, String name) {
        try {
            java.lang.reflect.Method m = record.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T companionStatic(String field) {
        try {
            java.lang.reflect.Field f = net.axther.aftrhrs.net.Companion.class.getDeclaredField(field);
            f.setAccessible(true);
            return (T) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonObject decodePayload(byte[] payload) {
        try {
            byte[] body;
            if (payload[0] == 0) body = java.util.Arrays.copyOfRange(payload, 1, payload.length);
            else try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(payload, 1, payload.length - 1))) {
                body = in.readAllBytes();
            }
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** Payloads as one message each (parts joined). */
    static List<JsonObject> decodeAll(List<byte[]> payloads) {
        List<JsonObject> out = new ArrayList<>();
        java.util.Map<Integer, byte[][]> parts = new java.util.HashMap<>();
        for (byte[] payload : payloads) {
            JsonObject o = decodePayload(payload);
            if (o == null) continue;
            if ("part".equals(o.get("t").getAsString())) {
                int id = o.get("id").getAsInt(), i = o.get("i").getAsInt(), n = o.get("n").getAsInt();
                byte[][] pieces = parts.computeIfAbsent(id, k -> new byte[n][]);
                pieces[i] = java.util.Base64.getDecoder().decode(o.get("b").getAsString());
                boolean done = true;
                for (byte[] piece : pieces) done &= piece != null;
                if (!done) continue;
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                for (byte[] piece : pieces) all.writeBytes(piece);
                o = decodePayload(all.toByteArray());
                if (o == null) continue;
            }
            out.add(o);
        }
        return out;
    }

    /** What the server queued for a channel-less test player since the last call. */
    static List<JsonObject> sentTo(ServerPlayer p) {
        java.util.Map<java.util.UUID, List<byte[]>> waiting = companionStatic("WAITING");
        List<byte[]> q = waiting.remove(p.getUUID());
        return q == null ? new ArrayList<>() : decodeAll(q);
    }

    /**
     * The background build (on another thread, streamed through gzip) gives the same list and hash as the old build on
     * the server thread, and its payload is a well-formed "recipes" message carrying that hash after the list.
     */
    @GameTest(maxTicks = 200)
    public void recipeExportOffThreadMatchesTheServerThread(GameTestHelper h) {
        net.minecraft.server.MinecraftServer server = h.getLevel().getServer();
        Object snap = export("snapshot", server);
        Object[] built = new Object[1];
        Thread t = new Thread(() -> built[0] = export("build", snap), "test-recipe-export");
        t.start();
        try {
            t.join(60_000);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        Object now = export("buildNow", server);
        if (built[0] == null) throw h.assertionException(net.minecraft.network.chat.Component.literal("the background build failed (see the log)"));
        Object bg = built[0];
        String hashBg = (String) part(bg, "hash"), hashNow = (String) part(now, "hash");
        int recipes = (int) part(bg, "recipes"), machines = (int) part(bg, "machines");
        if (!hashBg.equals(hashNow) || recipes != (int) part(now, "recipes") || machines != (int) part(now, "machines")) {
            throw h.assertionException(net.minecraft.network.chat.Component.literal("background " + recipes + "+" + machines + " " + hashBg
                    + " vs server thread " + part(now, "recipes") + "+" + part(now, "machines") + " " + hashNow));
        }
        @SuppressWarnings("unchecked")
        List<JsonObject> msgs = decodeAll((List<byte[]>) part(bg, "encoded"));
        JsonObject msg = msgs.size() == 1 ? msgs.getFirst() : null;
        if (msg == null || !"recipes".equals(msg.get("t").getAsString()) || !hashBg.equals(msg.get("hash").getAsString())
                || msg.getAsJsonArray("recipes").size() != recipes + machines || recipes < 1000) {
            throw h.assertionException(net.minecraft.network.chat.Component.literal("the streamed message: " + msgs.size() + " message(s), "
                    + (msg == null ? "-" : msg.get("t") + " hash " + msg.get("hash") + ", " + msg.getAsJsonArray("recipes").size() + " entries")));
        }
        h.succeed();
    }

    /**
     * A modded player whose welcome comes before any list (recipes:"") is answered once the background build is done;
     * asking again with that hash gets recipes_ok.
     */
    @GameTest(maxTicks = 400)
    public void recipeExportWelcomeWaitsForTheBuild(GameTestHelper h) {
        CT.Bot bot = CT.join(h, "rxWait", false);
        ServerPlayer p = bot.p;
        java.util.Map<java.util.UUID, net.axther.aftrhrs.net.Companion.Session> sessions = companionStatic("SESSIONS");
        sessions.put(p.getUUID(), new net.axther.aftrhrs.net.Companion.Session("test", java.util.Set.of("recipes")));
        sentTo(p);
        try {
            java.lang.reflect.Field enc = Class.forName("net.axther.aftrhrs.net.RecipeExport").getDeclaredField("encoded");
            enc.setAccessible(true);
            enc.set(null, null); // as before the first list was built
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        export("sendTo", p, "");
        List<JsonObject> got = new ArrayList<>(sentTo(p));
        for (JsonObject o : got) {
            if ("recipes".equals(o.get("t").getAsString())) throw h.assertionException(net.minecraft.network.chat.Component.literal("the list went out before it was built"));
        }
        if (!(boolean) export("building")) throw h.assertionException(net.minecraft.network.chat.Component.literal("no background build started"));
        h.succeedWhen(() -> {
            got.addAll(sentTo(p));
            JsonObject list = null;
            for (JsonObject o : got) if ("recipes".equals(o.get("t").getAsString())) list = o;
            if (list == null) throw h.assertionException(net.minecraft.network.chat.Component.literal("no recipes list yet"));
            String hash = list.get("hash").getAsString();
            if (!hash.equals(export("hash")) || list.getAsJsonArray("recipes").size() < 1000) {
                throw h.assertionException(net.minecraft.network.chat.Component.literal("the list: hash " + hash + " vs " + export("hash") + ", "
                        + list.getAsJsonArray("recipes").size() + " entries"));
            }
            export("sendTo", p, hash);
            JsonObject ok = null;
            for (JsonObject o : sentTo(p)) if ("recipes_ok".equals(o.get("t").getAsString())) ok = o;
            if (ok == null || !hash.equals(ok.get("hash").getAsString())) throw h.assertionException(net.minecraft.network.chat.Component.literal("the cached hash got no recipes_ok"));
            bot.leave();
        });
    }

    static void cookForReal(GameTestHelper h, ServerLevel level, RecipeHolder<?> holder, ContextMap ctx, CT.Fails f) {
        AbstractCookingRecipe cr = (AbstractCookingRecipe) holder.value();
        BlockState furnace;
        if (cr.getType() == RecipeType.SMELTING) furnace = Blocks.FURNACE.defaultBlockState();
        else if (cr.getType() == RecipeType.SMOKING) furnace = Blocks.SMOKER.defaultBlockState();
        else if (cr.getType() == RecipeType.BLASTING) furnace = Blocks.BLAST_FURNACE.defaultBlockState();
        else return; // campfire: lookup only
        BlockPos pos = h.absolutePos(new BlockPos(3, 1, 3));
        level.setBlock(pos, furnace, 3);
        if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity be)) {
            f.add(holder.id().identifier() + ": no furnace block entity");
            return;
        }
        ItemStack in = first(cr.input(), ctx);
        ItemStack want = cr.assemble(new SingleRecipeInput(in));
        be.setItem(0, in.copyWithCount(1));
        be.setItem(1, new ItemStack(Items.COAL));
        for (int t = 0; t < 1000 && be.getItem(2).isEmpty(); t++) AbstractFurnaceBlockEntity.serverTick(level, pos, level.getBlockState(pos), be);
        ItemStack out = be.getItem(2);
        f.check(ItemStack.isSameItemSameComponents(out, want), holder.id().identifier() + ": " + furnace.getBlock() + " made " + out + " from " + key(in) + " not " + want);
        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }
}
