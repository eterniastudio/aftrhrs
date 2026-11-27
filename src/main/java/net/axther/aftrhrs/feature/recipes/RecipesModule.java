package net.axther.aftrhrs.feature.recipes;

import com.mojang.serialization.Dynamic;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.recipe.v1.ingredient.DefaultCustomIngredients;
import net.kyori.adventure.text.Component;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Custom recipes made in game: {@code /recipes} opens a chest manager (see {@link RecipeEditor}) where admins lay
 * out real items as ingredients and a result. Recipes live in {@code recipes.yml} (items as base64 of gzipped item NBT
 * with a DataVersion, the format of Paper's {@code serializeAsBytes}, so the live file loads as is) and are real
 * recipes in the server's recipe list as {@code feather:custom/<id>} ({@link RecipeInjector}). Saving re-registers on
 * the fly and resends the recipe list, so online players can use a recipe straight away; a datapack {@code /reload}
 * keeps them.
 */
public final class RecipesModule implements FeatherModule, FeatherCommand {
    public static final String NAMESPACE = "feather";
    public static final String PREFIX = "custom/";
    /** Recipe ids: lower case letters, digits, {@code _} and {@code -} ({@code .} would break YAML paths). */
    static final Pattern ID = Pattern.compile("^[a-z0-9_-]{1,40}$");
    private static final String HEADER = """
            # Feather custom recipes. Made and edited in game with /recipes; items are stored as base64.
            # Hand edits: change type/enabled/exact/experience/cook-time, then /recipes reload.
            """;

    /** What a recipe is crafted in. Inputs are indices 0-8 of the editor's 3x3 grid. */
    enum Type {
        SHAPED("shaped", Items.CRAFTING_TABLE, 0),
        SHAPELESS("shapeless", Items.CRAFTER, 0),
        FURNACE("furnace", Items.FURNACE, 200),
        BLASTING("blasting", Items.BLAST_FURNACE, 100),
        SMOKING("smoking", Items.SMOKER, 100),
        CAMPFIRE("campfire", Items.CAMPFIRE, 600),
        STONECUTTER("stonecutter", Items.STONECUTTER, 0),
        SMITHING("smithing", Items.SMITHING_TABLE, 0);

        private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8};
        private static final int[] CENTER = {4};
        /** Smithing: template, base, addition along the middle row. */
        private static final int[] ROW = {3, 4, 5};

        final String key;
        final Item icon;
        final int defaultTime;

        Type(String key, Item icon, int defaultTime) {
            this.key = key;
            this.icon = icon;
            this.defaultTime = defaultTime;
        }

        boolean crafting() {
            return this == SHAPED || this == SHAPELESS;
        }

        boolean cooking() {
            return defaultTime > 0;
        }

        int[] inputs() {
            if (crafting()) return ALL;
            return this == SMITHING ? ROW : CENTER;
        }

        boolean uses(int index) {
            for (int i : inputs()) if (i == index) return true;
            return false;
        }

        static Type parse(String s) {
            if (s == null) return null;
            String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Type t : values()) if (t.key.equals(k) || t.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(k)) return t;
            return switch (k) {
                case "blast-furnace", "blast" -> BLASTING;
                case "smoker" -> SMOKING;
                case "smithing-transform" -> SMITHING;
                case "stonecutting" -> STONECUTTER;
                default -> null;
            };
        }
    }

    /** One recipe (also the editor's draft). Items are private copies. */
    static final class CustomRecipe {
        /** Null for a new recipe until it is saved (then it is named after its result) or renamed. */
        String id;
        Type type = Type.SHAPED;
        boolean enabled = true;
        boolean exact;
        float experience;
        int cookTime = Type.FURNACE.defaultTime;
        ItemStack result;
        final ItemStack[] inputs = new ItemStack[9];

        CustomRecipe copy() {
            CustomRecipe c = new CustomRecipe();
            c.id = id;
            c.type = type;
            c.enabled = enabled;
            c.exact = exact;
            c.experience = experience;
            c.cookTime = cookTime;
            c.result = copyOf(result);
            for (int i = 0; i < 9; i++) c.inputs[i] = copyOf(inputs[i]);
            return c;
        }
    }

    private static volatile RecipesModule active;

    private final Feather plugin;
    private final Map<String, CustomRecipe> recipes = new TreeMap<>();
    /** Enabled recipes as they are in the recipe list, by id. */
    private final Map<String, RecipeHolder<?>> registered = new LinkedHashMap<>();
    private RecipeEditor editor;
    private boolean discover;
    /** recipes.yml as last registered, so /feather reload only re-registers when the file changed. */
    private String lastFile;
    /** recipes.yml exists but couldn't be read: never save over it. */
    private boolean unreadable;

    public RecipesModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Custom recipes";
    }

    @Override
    public void enable() {
        active = this;
        discover = plugin.getConfig().getBoolean("custom-recipes.discover", true);
        editor = new RecipeEditor(plugin, this);
        plugin.once("recipes.events", () -> {
            ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
                RecipesModule m = active;
                if (m != null && m.discover && !m.registered.isEmpty()) h.player.awardRecipes(List.copyOf(m.registered.values()));
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                RecipesModule m = active;
                if (m != null && m.editor != null) m.editor.quit(h.player);
            });
        });
        plugin.bind("recipes", this);
        loadAll();
        lastFile = fileText();
        registerAll();
        plugin.getLogger().info("Custom recipes: {} loaded, {} enabled.", recipes.size(), registered.size());
    }

    @Override
    public void reload() {
        discover = plugin.getConfig().getBoolean("custom-recipes.discover", true);
        // Re-registering makes every client reload its recipes.
        if (Objects.equals(lastFile, fileText())) return;
        reloadRecipes();
    }

    @Override
    public void disable() {
        if (editor != null) editor.shutdown();
        if (active == this) active = null;
        registered.clear();
        // The server is stopping: nothing more to resend; the next start reads recipes.yml again.
        RecipeInjector.Extra.list = List.of();
    }

    // ------------------------------------------------------------------ access

    /** All recipes by id (sorted). Callers must not change them; edit a {@link CustomRecipe#copy()}. */
    Map<String, CustomRecipe> recipes() {
        return recipes;
    }

    CustomRecipe get(String id) {
        return id == null ? null : recipes.get(id.toLowerCase(Locale.ROOT));
    }

    static ResourceKey<Recipe<?>> key(String id) {
        return ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + id));
    }

    Component typeName(Type type) {
        return plugin.text().msg("recipes.types." + type.key);
    }

    Component stateName(boolean enabled) {
        return plugin.text().msg(enabled ? "recipes.state-enabled" : "recipes.state-disabled");
    }

    // ------------------------------------------------------------------ file

    private Path file() {
        return plugin.getDataFolder().resolve("recipes.yml");
    }

    private String fileText() {
        try {
            return Files.exists(file()) ? Files.readString(file()) : "";
        } catch (IOException e) {
            return null;
        }
    }

    /** True while recipes.yml couldn't be read; saving and deleting are refused until it's fixed and reloaded. */
    boolean fileUnreadable() {
        return unreadable;
    }

    private void loadAll() {
        Path f = file();
        Yaml yml = SafeYaml.read(f, plugin.getLogger(), "custom recipe edits");
        if (yml == null) {
            // Keep the recipes already loaded (if any) and refuse to write until the file is fixed.
            unreadable = true;
            return;
        }
        unreadable = false;
        recipes.clear();
        if (!Files.exists(f)) return;
        Yaml all = yml.getConfigurationSection("recipes");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            Yaml s = all.getConfigurationSection(id);
            if (s == null) continue;
            try {
                CustomRecipe r = read(id, s);
                recipes.put(r.id, r);
            } catch (RuntimeException e) {
                // Left untouched in the file: saves only rewrite the recipe they change.
                plugin.getLogger().warn("recipes.yml: skipped recipe '{}': {}", id, e.getMessage());
            }
        }
    }

    private static CustomRecipe read(String id, Yaml s) {
        CustomRecipe r = new CustomRecipe();
        r.id = id.toLowerCase(Locale.ROOT);
        if (!ID.matcher(r.id).matches()) throw new IllegalArgumentException("bad id");
        r.type = Type.parse(s.getString("type"));
        if (r.type == null) throw new IllegalArgumentException("unknown type " + s.getString("type"));
        r.enabled = s.getBoolean("enabled", true);
        r.exact = s.getBoolean("exact", false);
        r.experience = (float) s.getDouble("experience", 0);
        r.cookTime = Math.max(1, s.getInt("cook-time", r.type.cooking() ? r.type.defaultTime : Type.FURNACE.defaultTime));
        r.result = decode(s.getString("result"));
        if (r.result == null) throw new IllegalArgumentException("no result");
        List<String> in = s.getStringList("inputs");
        for (int i = 0; i < 9 && i < in.size(); i++) r.inputs[i] = decode(in.get(i));
        return r;
    }

    /** Writes (or with {@code r == null} removes) one recipe, leaving the rest of the file as it is. */
    private boolean write(String id, CustomRecipe r) {
        Path f = file();
        Yaml yml = unreadable ? null : SafeYaml.read(f, plugin.getLogger(), "custom recipe edits");
        if (yml == null) {
            // Rewriting it from an empty config would wipe every other recipe.
            unreadable = true;
            plugin.getLogger().error("Not saving recipes.yml: it couldn't be read. Fix it (a .broken- copy was kept) and run /recipes reload.");
            return false;
        }
        if (r == null) {
            yml.set("recipes." + id, null);
        } else {
            Yaml s = yml.createSection("recipes." + id);
            s.set("type", r.type.key);
            s.set("enabled", r.enabled);
            s.set("exact", r.exact);
            if (r.type.cooking()) {
                s.set("experience", (double) r.experience);
                s.set("cook-time", r.cookTime);
            }
            s.set("result", encode(r.result));
            List<String> in = new ArrayList<>();
            for (int i = 0; i < 9; i++) in.add(r.inputs[i] == null ? "" : encode(r.inputs[i]));
            s.set("inputs", in);
        }
        // dumped and written on the data thread, in order with the other data saves, and waited for: the editor tells
        // the player, and lastFile must be what is on disk
        boolean ok = SafeYaml.writeSync(f, HEADER, yml.snapshot(), plugin.getLogger());
        if (ok) lastFile = fileText();
        return ok;
    }

    private static HolderLookup.Provider registries() {
        return Feather.server().registryAccess();
    }

    /** Base64 of gzipped item NBT with a DataVersion (Paper's {@code ItemStack.serializeAsBytes}). */
    static String encode(ItemStack item) {
        Tag tag = ItemStack.CODEC.encodeStart(registries().createSerializationContext(NbtOps.INSTANCE), item).getOrThrow();
        CompoundTag compound = (CompoundTag) tag;
        compound.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(compound, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Reads {@link #encode}'s format (and Paper's), upgrading items saved by an older version. Null for nothing. */
    static ItemStack decode(String b64) {
        if (b64 == null || b64.isBlank()) return null;
        byte[] bytes = Base64.getDecoder().decode(b64.trim());
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            try {
                tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
            } catch (IOException e2) {
                throw new IllegalArgumentException("unreadable item: " + e.getMessage());
            }
        }
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        int version = tag.getIntOr("DataVersion", current);
        tag.remove("DataVersion");
        Tag fixed = tag;
        if (version < current) {
            fixed = Feather.server().getFixerUpper()
                    .update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, (Tag) tag), version, current).getValue();
        }
        ItemStack item = ItemStack.CODEC.parse(registries().createSerializationContext(NbtOps.INSTANCE), fixed)
                .getOrThrow(m -> new IllegalArgumentException("bad item: " + m));
        return empty(item) ? null : item;
    }

    static boolean empty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    static ItemStack copyOf(ItemStack item) {
        return empty(item) ? null : item.copy();
    }

    // ------------------------------------------------------------------ registering

    private void registerAll() {
        registered.clear();
        for (CustomRecipe r : recipes.values()) {
            if (!r.enabled) continue;
            try {
                registered.put(r.id, holder(r));
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Custom recipe '{}' could not be registered: {}", r.id, e.getMessage());
            }
        }
        apply();
    }

    /** Puts the registered recipes into the server's recipe list and resends recipe lists and books to everyone. */
    private void apply() {
        RecipeInjector.Extra.list = List.copyOf(registered.values());
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        RecipeManager manager = server.getRecipeManager();
        ((RecipeInjector) manager).aftrhrs$replace(NAMESPACE, PREFIX, registered.values());
        manager.finalizeRecipeLoading(server.getWorldData().enabledFeatures());
        List<ServerPlayer> online = plugin.onlinePlayers();
        if (online.isEmpty()) return;
        ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(manager.getSynchronizedItemProperties(),
                manager.getSynchronizedStonecutterRecipes());
        for (ServerPlayer p : online) {
            p.connection.send(packet);
            // Display ids change with the list: the whole book goes again.
            p.getRecipeBook().sendInitialRecipeBook(p);
        }
    }

    /** {@code /recipes reload}: reads recipes.yml again and re-registers everything. */
    int reloadRecipes() {
        List<RecipeHolder<?>> old = List.copyOf(registered.values());
        if (!old.isEmpty()) for (ServerPlayer p : plugin.onlinePlayers()) p.resetRecipes(old);
        loadAll();
        lastFile = fileText();
        registerAll();
        plugin.recipesUpdated();
        if (discover && !registered.isEmpty()) {
            List<RecipeHolder<?>> now = List.copyOf(registered.values());
            for (ServerPlayer p : plugin.onlinePlayers()) p.awardRecipes(now);
        }
        return recipes.size();
    }

    int registeredCount() {
        return registered.size();
    }

    /** Paper's ExactChoice (same item and components) or MaterialChoice (the item type). */
    private static Ingredient choice(CustomRecipe r, ItemStack item) {
        if (r.exact && !item.getComponentsPatch().isEmpty()) return DefaultCustomIngredients.components(item.copyWithCount(1));
        return Ingredient.of(item.getItem());
    }

    RecipeHolder<?> holder(CustomRecipe r) {
        return new RecipeHolder<>(key(r.id), build(r));
    }

    /** The recipe for a (valid) custom recipe. Shaped grids are trimmed to their bounding box. */
    static Recipe<?> build(CustomRecipe r) {
        ItemStackTemplate result = ItemStackTemplate.fromNonEmptyStack(r.result);
        ItemStack[] in = r.inputs;
        Recipe.CommonInfo common = new Recipe.CommonInfo(true);
        CraftingRecipe.CraftingBookInfo crafting = new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, "");
        AbstractCookingRecipe.CookingBookInfo cooking = new AbstractCookingRecipe.CookingBookInfo(CookingBookCategory.MISC, "");
        switch (r.type) {
            case SHAPED -> {
                int minR = 3, maxR = -1, minC = 3, maxC = -1;
                for (int i = 0; i < 9; i++) {
                    if (empty(in[i])) continue;
                    minR = Math.min(minR, i / 3);
                    maxR = Math.max(maxR, i / 3);
                    minC = Math.min(minC, i % 3);
                    maxC = Math.max(maxC, i % 3);
                }
                if (maxR < 0) throw new IllegalArgumentException("no ingredients");
                String[] shape = new String[maxR - minR + 1];
                Map<Character, Ingredient> keys = new HashMap<>();
                for (int row = minR; row <= maxR; row++) {
                    StringBuilder sb = new StringBuilder();
                    for (int col = minC; col <= maxC; col++) {
                        int i = row * 3 + col;
                        if (empty(in[i])) {
                            sb.append(' ');
                        } else {
                            char c = (char) ('A' + i);
                            sb.append(c);
                            keys.put(c, choice(r, in[i]));
                        }
                    }
                    shape[row - minR] = sb.toString();
                }
                return new ShapedRecipe(common, crafting, ShapedRecipePattern.of(keys, shape), result);
            }
            case SHAPELESS -> {
                List<Ingredient> list = new ArrayList<>();
                for (ItemStack item : in) if (!empty(item)) list.add(choice(r, item));
                if (list.isEmpty()) throw new IllegalArgumentException("no ingredients");
                return new ShapelessRecipe(common, crafting, result, list);
            }
            case FURNACE -> {
                return new SmeltingRecipe(common, cooking, choice(r, need(in[4])), result, r.experience, r.cookTime);
            }
            case BLASTING -> {
                return new BlastingRecipe(common, cooking, choice(r, need(in[4])), result, r.experience, r.cookTime);
            }
            case SMOKING -> {
                return new SmokingRecipe(common, cooking, choice(r, need(in[4])), result, r.experience, r.cookTime);
            }
            case CAMPFIRE -> {
                return new CampfireCookingRecipe(common, cooking, choice(r, need(in[4])), result, r.experience, r.cookTime);
            }
            case STONECUTTER -> {
                return new StonecutterRecipe(common, choice(r, need(in[4])), result);
            }
            case SMITHING -> {
                return new SmithingTransformRecipe(common, Optional.of(choice(r, need(in[3]))), choice(r, need(in[4])),
                        Optional.of(choice(r, need(in[5]))), result);
            }
        }
        throw new IllegalArgumentException("unknown type");
    }

    private static ItemStack need(ItemStack item) {
        if (empty(item)) throw new IllegalArgumentException("missing input");
        return item;
    }

    // ------------------------------------------------------------------ validating and saving

    /** Why an id can't be used (null when it can). {@code originalId} is the id the recipe is saved under now. */
    Component idProblem(String id, String originalId) {
        Text text = plugin.text();
        if (id == null || !ID.matcher(id).matches()) return text.msg("recipes.problems.bad-id", Text.unparsed("id", String.valueOf(id)));
        ResourceKey<Recipe<?>> vanilla = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(id));
        if (Feather.server().getRecipeManager().byKey(vanilla).isPresent()) return text.msg("recipes.problems.vanilla-id", Text.unparsed("id", id));
        if (recipes.containsKey(id) && !id.equals(originalId)) return text.msg("recipes.problems.taken-id", Text.unparsed("id", id));
        return null;
    }

    /** A free id named after the result item: {@code diamond}, else {@code diamond_2}, {@code diamond_3} ... */
    private String autoId(ItemStack result, String originalId) {
        String base = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath();
        if (base.length() > 36) base = base.substring(0, 36);
        String id = base;
        for (int n = 2; idProblem(id, originalId) != null; n++) id = base + "_" + n;
        return id;
    }

    /** Everything wrong with a draft, one message each; empty when it can be saved. */
    List<Component> problems(CustomRecipe d, String originalId) {
        Text text = plugin.text();
        List<Component> out = new ArrayList<>();
        if (d.id != null) {
            Component idp = idProblem(d.id, originalId);
            if (idp != null) out.add(idp);
        }
        if (empty(d.result)) out.add(text.msg("recipes.problems.no-result"));
        if (d.type.crafting()) {
            boolean any = false;
            for (ItemStack item : d.inputs) any |= !empty(item);
            if (!any) out.add(text.msg("recipes.problems.no-ingredients"));
        } else if (d.type == Type.SMITHING) {
            if (empty(d.inputs[3]) || empty(d.inputs[4]) || empty(d.inputs[5])) out.add(text.msg("recipes.problems.smithing"));
        } else if (empty(d.inputs[4])) {
            out.add(text.msg("recipes.problems.no-input"));
        }
        if (d.type.cooking() && d.cookTime < 1) out.add(text.msg("recipes.problems.cook-time"));
        if (out.isEmpty()) {
            try {
                build(d);
            } catch (RuntimeException e) {
                out.add(text.msg("recipes.problems.rejected", Text.unparsed("error", String.valueOf(e.getMessage()))));
            }
        }
        return out;
    }

    /**
     * Validates and saves a draft (a copy the caller no longer changes) and re-registers it live. Returns the
     * problems; when empty, the recipe is saved under {@code draft.id} (named after its result if it had none).
     */
    List<Component> save(CustomRecipe draft, String originalId) {
        CustomRecipe d = draft.copy();
        // Only the slots this type uses are kept, one item each (a slot is one ingredient whatever its stack size).
        for (int i = 0; i < 9; i++) d.inputs[i] = d.type.uses(i) && !empty(d.inputs[i]) ? d.inputs[i].copyWithCount(1) : null;
        if (!d.type.cooking()) {
            d.experience = 0;
            d.cookTime = Type.FURNACE.defaultTime;
        }
        // recipes.yml couldn't be read: saving would lose the recipes in it.
        if (unreadable) return List.of(plugin.text().msg("recipes.problems.file-unreadable"));
        List<Component> problems = problems(d, originalId);
        if (!problems.isEmpty()) return problems;
        if (d.id == null) d.id = autoId(d.result, originalId);
        draft.id = d.id;

        if (originalId != null && !originalId.equals(d.id)) {
            unregister(originalId);
            recipes.remove(originalId);
            write(originalId, null);
        }
        unregister(d.id);
        RecipeHolder<?> holder = null;
        if (d.enabled) {
            holder = holder(d);
            registered.put(d.id, holder);
        }
        recipes.put(d.id, d);
        write(d.id, d);
        apply();
        plugin.recipesUpdated();
        if (holder != null && discover) for (ServerPlayer p : plugin.onlinePlayers()) p.awardRecipes(List.of(holder));
        return List.of();
    }

    /** Takes a recipe out of everyone's recipe book and the registered list (apply() takes it off the server). */
    private void unregister(String id) {
        RecipeHolder<?> h = registered.remove(id);
        if (h == null) return;
        for (ServerPlayer p : plugin.onlinePlayers()) p.resetRecipes(List.of(h));
    }

    /** Removes a recipe from the server and the file. */
    boolean delete(String id) {
        if (!recipes.containsKey(id) || unreadable) return false;
        unregister(id);
        recipes.remove(id);
        write(id, null);
        apply();
        plugin.recipesUpdated();
        return true;
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        if (!sender.hasPermission("feather.recipes.admin")) {
            text.send(sender, "general.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (sender.player() != null) editor.openList(sender.player(), 0);
                else text.send(sender, "recipes.usage");
            }
            case "list" -> {
                if (recipes.isEmpty()) {
                    text.send(sender, "recipes.list-empty");
                    return true;
                }
                text.send(sender, "recipes.list-header", Text.unparsed("count", recipes.size()));
                for (CustomRecipe r : recipes.values()) {
                    text.send(sender, "recipes.list-entry", Text.unparsed("id", r.id), Text.component("type", typeName(r.type)),
                            Text.component("state", stateName(r.enabled)), Text.component("item", itemLabel(r.result)));
                }
            }
            case "reload" -> {
                int n = reloadRecipes();
                text.send(sender, "recipes.reloaded", Text.unparsed("count", n), Text.unparsed("enabled", registered.size()));
                if (unreadable) text.send(sender, "recipes.file-unreadable");
            }
            case "give" -> {
                if (args.length < 2) {
                    text.send(sender, "recipes.usage");
                    return true;
                }
                CustomRecipe r = get(args[1]);
                if (r == null) {
                    text.send(sender, "recipes.unknown", Text.unparsed("id", args[1]));
                    return true;
                }
                ServerPlayer target;
                if (args.length >= 3) {
                    target = plugin.findPlayer(sender, args[2]);
                    if (target == null) {
                        plugin.notFound(sender, args[2]);
                        return true;
                    }
                } else if (sender.player() != null) {
                    target = sender.player();
                } else {
                    text.send(sender, "recipes.usage");
                    return true;
                }
                RecipeEditor.give(target, r.result.copy());
                text.send(sender, "recipes.given", Text.unparsed("id", r.id), Text.unparsed("player", target.getGameProfile().name()),
                        Text.component("item", itemLabel(r.result)));
            }
            default -> text.send(sender, "recipes.usage");
        }
        return true;
    }

    /** "3x Diamond" with the item's hover. */
    static Component itemLabel(ItemStack item) {
        if (empty(item)) return Component.text("?");
        net.minecraft.network.chat.MutableComponent name = item.getHoverName().copy()
                .withStyle(s -> s.withHoverEvent(new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(item))));
        Component c = Adv.adventure(name);
        return item.getCount() > 1 ? Component.text(item.getCount() + "x ").append(c) : c;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (!sender.hasPermission("feather.recipes.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("list", "reload", "give"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("give")) out.addAll(recipes.keySet());
        else if (args.length == 3 && args[0].equalsIgnoreCase("give")) out.addAll(plugin.completePlayers(sender, args[2]));
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
