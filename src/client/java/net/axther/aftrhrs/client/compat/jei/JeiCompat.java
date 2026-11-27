package net.axther.aftrhrs.client.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.recipes.ServerRecipes;
import net.axther.aftrhrs.client.storage.StorageSession;
import net.axther.aftrhrs.client.storage.TerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JEI for Aftr Hrs: the server's recipes (the mod's furniture and item recipes, datapacks, /recipes custom ones; a
 * dedicated server doesn't send its recipe list, so JEI otherwise only knows vanilla's built-in ones) go into JEI's own
 * categories; Feather workstations (cooking meals) get Aftr Hrs categories; and in a Crafting Terminal the "+" button
 * fills the grid from the storage network. In singleplayer JEI already has the integrated server's recipes and
 * {@link #sync} skips every id it knows, so nothing shows twice.
 */
@mezz.jei.api.JeiPlugin
public final class JeiCompat implements IModPlugin {
	private static final Identifier UID = Identifier.fromNamespaceAndPath(Companion.ID, "jei");
	/** Workstation kinds with their own category; anything else goes to "workstation". */
	static final List<String> MACHINES = List.of("cooking", "workstation", "smithing");

	private static IJeiRuntime runtime;
	private static final Map<String, IRecipeType<ServerRecipes.Machine>> TYPES = new LinkedHashMap<>();
	private static boolean listening;
	private static final Set<Identifier> added = new HashSet<>();
	/** Server items already in JEI's list ("item|model"). */
	private static final Set<String> addedItems = new HashSet<>();

	@Override
	public Identifier getPluginUid() {
		return UID;
	}

	static IRecipeType<ServerRecipes.Machine> type(String machine) {
		return TYPES.computeIfAbsent(machine, m -> IRecipeType.create(Companion.ID, m, ServerRecipes.Machine.class));
	}

	/**
	 * Aftr Hrs items are registered items now (their own subtypes for free); this is for the few vanilla items a
	 * server still gives an {@code item_model} (custom recipe results, migrated stacks). Without it JEI counts every
	 * one as its base item, so they never show in the list and R/U on them finds the wrong recipes. Plain stacks get
	 * no subtype. JEI's own plugin runs first and keeps its interpreters (potions, books, …).
	 *
	 * <p>Only {@link #ownsSubtypes} items: other mods' items are skipped on purpose. JEI keeps the first interpreter
	 * for an item and logs an error for every later one, so claiming theirs would clash with their own plugins.
	 */
	@Override
	public void registerItemSubtypes(ISubtypeRegistration registration) {
		ISubtypeInterpreter<ItemStack> model = (stack, ctx) -> customModel(stack);
		for (Item item : BuiltInRegistries.ITEM) if (ownsSubtypes(item)) registration.registerSubtypeInterpreter(item, model);
	}

	/** Items JEI's own plugin gives subtypes (VanillaPlugin.registerItemSubtypes). */
	private static final Set<Item> JEI_SUBTYPES = Set.of(Items.TIPPED_ARROW, Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION,
		Items.ENCHANTED_BOOK, Items.LIGHT, Items.PAINTING, Items.GOAT_HORN, Items.FIREWORK_ROCKET, Items.FIREWORK_STAR,
		Items.SUSPICIOUS_STEW, Items.OMINOUS_BOTTLE, Items.SHIELD, Items.DECORATED_POT);

	/**
	 * Items whose JEI subtypes come from Aftr Hrs' {@code item_model} interpreter: vanilla (minus JEI's own) and Aftr
	 * Hrs' items. Other mods' items keep their own interpreters (and the slot mixin leaves their wildcards alone).
	 */
	public static boolean ownsSubtypes(Item item) {
		if (item == Items.AIR || JEI_SUBTYPES.contains(item)) return false;
		String ns = BuiltInRegistries.ITEM.getKey(item).getNamespace();
		return ns.equals("minecraft") || ns.equals(Companion.ID);
	}

	/** The stack's own {@code item_model} when the server gave it one, else null (a plain vanilla item). */
	static Identifier customModel(ItemStack stack) {
		if (stack.getComponentsPatch().isEmpty()) return null;
		Identifier model = stack.get(DataComponents.ITEM_MODEL);
		Identifier base = DEFAULT_MODEL.computeIfAbsent(stack.getItem(), i -> java.util.Optional.ofNullable(new ItemStack(i).get(DataComponents.ITEM_MODEL))).orElse(null);
		return model == null || model.equals(base) ? null : model;
	}

	private static final Map<Item, Optional<Identifier>> DEFAULT_MODEL = new java.util.concurrent.ConcurrentHashMap<>();

	@Override
	public void registerCategories(IRecipeCategoryRegistration registration) {
		IGuiHelper gui = registration.getJeiHelpers().getGuiHelper();
		for (String m : MACHINES) registration.addRecipeCategories(new MachineCategory(type(m), gui, m));
	}

	@Override
	public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
		IRecipeTransferHandlerHelper helper = registration.getTransferHelper();
		IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> table = helper.createUnregisteredRecipeTransferHandler(
			helper.createBasicRecipeTransferInfo(CraftingMenu.class, MenuType.CRAFTING, RecipeTypes.CRAFTING, 1, 9, 10, 36));
		registration.addRecipeTransferHandler(new FillTransfer<>(CraftingMenu.class, MenuType.CRAFTING, 3, 1, table, helper), RecipeTypes.CRAFTING);
		IRecipeTransferHandler<InventoryMenu, RecipeHolder<CraftingRecipe>> pocket = helper.createUnregisteredRecipeTransferHandler(
			helper.createBasicRecipeTransferInfo(InventoryMenu.class, null, RecipeTypes.CRAFTING, 1, 4, 9, 36));
		registration.addRecipeTransferHandler(new FillTransfer<>(InventoryMenu.class, null, 2, 1, pocket, helper), RecipeTypes.CRAFTING);
	}

	@Override
	public void registerGuiHandlers(IGuiHandlerRegistration registration) {
		// the cosmetic armor panel left of the survival inventory
		registration.addGuiContainerHandler(net.minecraft.client.gui.screens.inventory.InventoryScreen.class,
			new IGuiContainerHandler<net.minecraft.client.gui.screens.inventory.InventoryScreen>() {
				@Override
				public List<Rect2i> getGuiExtraAreas(net.minecraft.client.gui.screens.inventory.InventoryScreen screen) {
					int[] r = net.axther.aftrhrs.client.cosmetics.CosmeticsClient.lastPanel;
					return r == null ? List.of() : List.of(new Rect2i(r[0], r[1], r[2], r[3]));
				}
			});
		registration.addGuiContainerHandler(TerminalScreen.class, new IGuiContainerHandler<TerminalScreen>() {
			@Override
			public List<Rect2i> getGuiExtraAreas(TerminalScreen screen) {
				ScreenRectangle r = screen.panelArea();
				return List.of(new Rect2i(r.left(), r.top(), r.width(), r.height()));
			}

			@Override
			public Optional<? extends mezz.jei.api.runtime.IClickableIngredient<?>> getClickableIngredientUnderMouse(
				mezz.jei.api.gui.builder.IClickableIngredientFactory builder, TerminalScreen screen, double mx, double my) {
				// R / U over a network item (or a Quick Craft recipe's result) shows its recipes / uses.
				ItemStack stack = screen.networkStackAt(mx, my);
				int[] cell = screen.networkCellAt(mx, my);
				if (stack.isEmpty() || cell == null) return Optional.empty();
				return builder.createBuilder(stack).buildWithArea(cell[0], cell[1], 16, 16);
			}
		});
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
		// The terminals' "JEI sync" search toggle reads and writes JEI's search text.
		net.axther.aftrhrs.client.storage.StoragePrefs.searchLink(new net.axther.aftrhrs.client.storage.StoragePrefs.SearchLink() {
			@Override
			public String get() {
				IJeiRuntime rt = runtime;
				return rt == null ? null : rt.getIngredientFilter().getFilterText();
			}

			@Override
			public void set(String text) {
				IJeiRuntime rt = runtime;
				if (rt != null && !text.equals(rt.getIngredientFilter().getFilterText())) rt.getIngredientFilter().setFilterText(text);
			}
		});
		added.clear();
		addedItems.clear();
		if (!listening) {
			listening = true;
			Companion.recipes().onChange(r -> apply());
		}
		apply();
	}

	/** DevScript ({@code jei <item>}): JEI's recipe screen for an item's recipes (false without a JEI runtime). */
	public static boolean showRecipes(ItemStack stack) {
		IJeiRuntime rt = runtime;
		if (rt == null) return false;
		rt.getRecipesGui().show(rt.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack));
		return true;
	}

	@Override
	public void onRuntimeUnavailable() {
		runtime = null;
		net.axther.aftrhrs.client.storage.StoragePrefs.searchLink(null);
		added.clear();
		addedItems.clear();
	}

	/** Adds the server's recipes JEI doesn't have yet, and hides built-in ones the server doesn't have. */
	private static void apply() {
		IJeiRuntime rt = runtime;
		ServerRecipes recipes = Companion.recipes();
		if (rt == null) return;
		if (!recipes.ready()) {
			// built off-thread; the listener calls this again once it is
			recipes.requestBuild();
			return;
		}
		ServerRecipes.Built b = recipes.built();
		IRecipeManager rm = rt.getRecipeManager();
		int n = 0;
		n += sync(rm, RecipeTypes.CRAFTING, b.crafting);
		n += sync(rm, RecipeTypes.SMELTING, b.smelting);
		n += sync(rm, RecipeTypes.BLASTING, b.blasting);
		n += sync(rm, RecipeTypes.SMOKING, b.smoking);
		n += sync(rm, RecipeTypes.CAMPFIRE_COOKING, b.campfire);
		n += sync(rm, RecipeTypes.STONECUTTING, b.stonecutting);
		n += sync(rm, RecipeTypes.SMITHING, b.smithing);
		for (Map.Entry<String, List<ServerRecipes.Machine>> e : b.machines.entrySet()) {
			String m = MACHINES.contains(e.getKey()) ? e.getKey() : "workstation";
			List<ServerRecipes.Machine> fresh = new ArrayList<>();
			for (ServerRecipes.Machine r : e.getValue()) if (added.add(r.id())) fresh.add(r);
			if (!fresh.isEmpty()) {
				rm.addRecipes(type(m), fresh);
				n += fresh.size();
			}
		}
		int items = addItems(rt, b);
		Companion.LOG.info("JEI: added {} Aftr Hrs recipes, {} server items", n, items);
	}

	/** Puts every server item the recipes mention into JEI's item list (once per kind). */
	private static int addItems(IJeiRuntime rt, ServerRecipes.Built b) {
		List<ItemStack> fresh = new ArrayList<>();
		for (ItemStack s : b.items) {
			Identifier model = customModel(s);
			if (model != null && addedItems.add(BuiltInRegistries.ITEM.getKey(s.getItem()) + "|" + model)) fresh.add(s.copyWithCount(1));
		}
		if (!fresh.isEmpty()) rt.getIngredientManager().addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, fresh);
		return fresh.size();
	}

	private static <R extends Recipe<?>> int sync(IRecipeManager rm, IRecipeHolderType<R> type, List<RecipeHolder<R>> server) {
		Map<Identifier, RecipeHolder<R>> existing = rm.createRecipeLookup(type).includeHidden().get()
			.collect(Collectors.toMap(h -> h.id().identifier(), h -> h, (a, c) -> a));
		Set<Identifier> serverIds = new HashSet<>();
		List<RecipeHolder<R>> fresh = new ArrayList<>();
		for (RecipeHolder<R> h : server) {
			Identifier id = h.id().identifier();
			serverIds.add(id);
			if (!existing.containsKey(id) && added.add(id)) fresh.add(h);
		}
		if (!fresh.isEmpty()) rm.addRecipes(type, fresh);
		// Built-in vanilla recipes the server took away (datapacks, disabled features).
		List<RecipeHolder<R>> gone = new ArrayList<>();
		for (Map.Entry<Identifier, RecipeHolder<R>> e : existing.entrySet()) {
			// Only plain recipes: special ones (dyeing, fireworks, …) never come from the server.
			if (!serverIds.contains(e.getKey()) && !added.contains(e.getKey()) && plain(e.getValue().value())) gone.add(e.getValue());
		}
		if (!gone.isEmpty() && !server.isEmpty()) rm.hideRecipes(type, gone);
		return fresh.size();
	}

	private static boolean plain(Recipe<?> r) {
		return r instanceof net.minecraft.world.item.crafting.ShapedRecipe || r instanceof net.minecraft.world.item.crafting.ShapelessRecipe
			|| r instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe || r instanceof net.minecraft.world.item.crafting.StonecutterRecipe
			|| r instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe;
	}

	// ------------------------------------------------------------------ "+" (move items)

	/**
	 * JEI's own "+" needs JEI on the server, so on Aftr Hrs it never works. Instead Feather fills the grid: in a
	 * Crafting Terminal from the network and the inventory, at a crafting table or the inventory's 2x2 grid from the
	 * inventory. Without the Companion link, JEI's handler answers as before.
	 */
	private record FillTransfer<C extends AbstractContainerMenu>(Class<C> menuClass, MenuType<C> menuType, int size, int gridStart,
		IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> basic, IRecipeTransferHandlerHelper helper)
		implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {

		@Override
		public Class<? extends C> getContainerClass() {
			return menuClass;
		}

		@Override
		public Optional<MenuType<C>> getMenuType() {
			return Optional.ofNullable(menuType);
		}

		@Override
		public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
			return RecipeTypes.CRAFTING;
		}

		private static StorageSession terminal(AbstractContainerMenu menu) {
			TerminalScreen screen = TerminalScreen.open();
			if (screen == null || screen.getMenu() != menu) return null;
			StorageSession s = screen.session();
			return s == StorageSession.current() ? s : null;
		}

		@Override
		public IRecipeTransferError transferRecipe(IRecipeTransferContext<RecipeHolder<CraftingRecipe>, C> context, boolean doTransfer) {
			IRecipeTransferError result = handle(context.getContainer(), context.getRecipe(), context.getRecipeSlots(), context.getPlayer(), context.isMaxTransfer(), doTransfer);
			if (result == PASS) return basic.transferRecipe(context, doTransfer);
			if (result == null && doTransfer) context.completeRecipeTransfer(RecipeTransferResult.SUCCESS);
			return result;
		}

		@Override
		@SuppressWarnings("removal")
		public IRecipeTransferError transferRecipe(C container, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean max, boolean doTransfer) {
			IRecipeTransferError result = handle(container, recipe, slots, player, max, doTransfer);
			return result == PASS ? basic.transferRecipe(container, recipe, slots, player, max, doTransfer) : result;
		}

		private IRecipeTransferError handle(C container, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean max, boolean doTransfer) {
			String id = recipe.id().identifier().toString();
			StorageSession s = terminal(container);
			if (s != null) {
				if (!doTransfer) return missing(s, container, slots, player);
				s.fill(id, max);
				return null;
			}
			if (!net.axther.aftrhrs.client.net.Link.has("craftfill")) return PASS;
			if (size == 2 && !fits2x2(recipe.value())) return helper.createUserErrorWithTooltip(Component.translatable("aftrhrs.jei.too-big"));
			if (!doTransfer) return missing(null, container, slots, player);
			StorageSession.craftFill(id, max);
			return null;
		}

		private static boolean fits2x2(CraftingRecipe r) {
			if (r instanceof net.minecraft.world.item.crafting.ShapedRecipe sr) return sr.getWidth() <= 2 && sr.getHeight() <= 2;
			return r.placementInfo().ingredients().size() <= 4;
		}

		/** Red slots for ingredients that are neither in the network (terminal), the inventory nor the grid. */
		private IRecipeTransferError missing(StorageSession s, C container, IRecipeSlotsView slots, Player player) {
			List<ItemStack> have = new ArrayList<>();
			if (s != null) for (StorageSession.Entry e : s.items.values()) have.add(e.item());
			for (ItemStack it : player.getInventory().getNonEquipmentItems()) if (!it.isEmpty()) have.add(it);
			for (int i = gridStart; i < gridStart + size * size && i < container.slots.size(); i++) {
				ItemStack it = container.getSlot(i).getItem();
				if (!it.isEmpty()) have.add(it);
			}
			List<IRecipeSlotView> miss = new ArrayList<>();
			for (IRecipeSlotView view : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
				List<ItemStack> options = view.getItemStacks().toList();
				if (options.isEmpty()) continue;
				boolean found = false;
				for (ItemStack opt : options) {
					for (ItemStack h : have) {
						if (ItemStack.isSameItemSameComponents(opt, h)) {
							found = true;
							break;
						}
					}
					if (found) break;
				}
				if (!found) miss.add(view);
			}
			if (miss.isEmpty()) return null;
			return helper.createUserErrorForMissingSlots(Component.translatable("aftrhrs.storage.missing"), miss);
		}
	}

	/** Marker: let JEI's own handler answer. */
	private static final IRecipeTransferError PASS = new IRecipeTransferError() {
		@Override
		public Type getType() {
			return Type.INTERNAL;
		}
	};

	// ------------------------------------------------------------------ workstation category

	private static final class MachineCategory extends AbstractRecipeCategory<ServerRecipes.Machine> {
		private final IDrawable arrow;

		MachineCategory(IRecipeType<ServerRecipes.Machine> type, IGuiHelper gui, String machine) {
			super(type, Component.translatable("aftrhrs.machine." + machine),
				gui.createDrawableItemStack(machine.equals("smithing") ? new ItemStack(net.axther.aftrhrs.feature.smithing.Smithing.block(
				net.axther.aftrhrs.feature.smithing.station.Station.ANVIL)) : new ItemStack(machine.equals("cooking") ? Items.CAMPFIRE : Items.CRAFTING_TABLE)), 150, 48);
			this.arrow = gui.getRecipeArrow();
		}

		@Override
		public void setRecipe(IRecipeLayoutBuilder builder, ServerRecipes.Machine recipe, IFocusGroup focuses) {
			int n = recipe.inputs().size();
			for (int i = 0; i < n && i < 8; i++) {
				builder.addSlot(RecipeIngredientRole.INPUT, 1 + (i % 4) * 18, 1 + (i / 4) * 18)
					.setStandardSlotBackground()
					.addItemStacks(recipe.inputs().get(i));
			}
			builder.addSlot(RecipeIngredientRole.OUTPUT, 113, 10).setOutputSlotBackground().addItemStack(recipe.output());
			if (!recipe.station().isEmpty()) builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, 133, 31).addItemStack(recipe.station());
		}

		@Override
		public void draw(ServerRecipes.Machine recipe, IRecipeSlotsView slots, GuiGraphicsExtractor g, double mx, double my) {
			arrow.draw(g, 82, 11);
			if (recipe.note() != null) {
				var font = Minecraft.getInstance().font;
				var lines = font.split(recipe.note(), 110);
				if (!lines.isEmpty()) g.text(font, lines.getFirst(), 1, 39, Theme.LABEL_SOFT, false);
			}
		}

		@Override
		public Identifier getIdentifier(ServerRecipes.Machine recipe) {
			return recipe.id();
		}
	}
}
