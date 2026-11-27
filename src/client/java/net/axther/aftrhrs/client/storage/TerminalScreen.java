package net.axther.aftrhrs.client.storage;

import net.axther.aftrhrs.client.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;

/**
 * The Crafting Terminal for Companion players: Feather opens a real crafting table, and this screen puts the
 * network beside it. Left-click a network item to pick up a stack (right: half, shift: into the inventory); click
 * the panel while holding an item to store it (right: one). The panel's Craftable tab is Quick Craft (left: the
 * chosen amount, right: as many as fit, shift: into the storage). Shift-clicking an inventory or hotbar slot stores it in
 * the network (vanilla would move it into the grid); the grid and the result keep vanilla's shift-click. JEI's "+"
 * button fills the grid from the network (see the JEI plugin). The recipe book is hidden: the panel takes its place.
 */
public final class TerminalScreen extends CraftingScreen {
	private static TerminalScreen open;

	private final StorageSession session;
	private final NetworkPanel panel;

	public TerminalScreen(CraftingMenu menu, Inventory inventory, Component title, StorageSession session) {
		super(menu, inventory, title);
		this.session = session;
		this.panel = new NetworkPanel(session, net.minecraft.client.Minecraft.getInstance().font);
		this.panel.depositButton = true;
	}

	/** The open terminal screen (for the JEI/REI transfer handlers and exclusion areas), or null. */
	public static TerminalScreen open() {
		return open;
	}

	public StorageSession session() {
		return session;
	}

	@Override
	protected void init() {
		// The recipe book would sit where the panel goes.
		if (minecraft.player != null) minecraft.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, false);
		super.init();
		children().stream().filter(c -> c instanceof ImageButton).toList().forEach(this::removeWidget);
		// The panel is as tall as the crafting table's window and sits to its left, where the recipe book would be.
		int rows = Math.max(3, (imageHeight - NetworkPanel.HEAD - NetworkPanel.FOOT) / NetworkPanel.CELL);
		int cols = 9;
		while (cols > 4 && (NetworkPanel.SIDE * 2 + cols * NetworkPanel.CELL + 4 + NetworkPanel.SCROLL_W) + 2 + imageWidth > width - 8) cols--;
		panel.place(0, 0, cols, rows);
		int total = panel.width() + 2 + imageWidth;
		int left = Math.max(4, (width - total) / 2);
		panel.place(left, topPos + (imageHeight - panel.height()) / 2, cols, rows);
		leftPos = left + panel.width() + 2;
		addRenderableWidget(panel.search);
		panel.focusOnOpen(this);
		open = this;
	}

	/** The network item under the mouse and its cell (for JEI/REI recipe lookups), or null. */
	public StorageSession.Entry networkEntryAt(double mx, double my) {
		return panel.entryAt(mx, my);
	}

	/** The item a panel cell shows (a network item, or a Quick Craft recipe's result), or empty. */
	public ItemStack networkStackAt(double mx, double my) {
		return panel.stackAt(mx, my);
	}

	public int[] networkCellAt(double mx, double my) {
		return panel.cellAt(mx, my);
	}

	public ScreenRectangle panelArea() {
		return new ScreenRectangle(panel.x, panel.y, panel.width(), panel.height());
	}

	/**
	 * The panel is drawn with the window's background: below the item on the cursor (a later render layer) and the
	 * tooltips, so a stack picked up from the network stays visible while it is carried over the panel (drawn after
	 * the screen, the panel used to hide it). The search box, a widget, is drawn right after the background.
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractBackground(g, mx, my, a);
		panel.render(g, mx, my);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractRenderState(g, mx, my, a);
		if (menu.getCarried().isEmpty()) panel.tooltip(g, mx, my);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		boolean right = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;
		if (panel.contains(mx, my)) {
			if (panel.clickSearch(mx, my)) {
				setFocused(panel.search);
				panel.search.setFocused(true);
				if (right) panel.search.setValue("");
				return true;
			}
			panel.search.setFocused(false);
			if (panel.clickDeposit(mx, my)) {
				click();
				session.depositAll();
				return true;
			}
			if (panel.clickHeader(mx, my, right)) {
				click();
				return true;
			}
			if (panel.clickScroll(mx, my)) return true;
			ItemStack carried = menu.getCarried();
			if (!carried.isEmpty()) {
				session.putCursor(right ? 1 : carried.getCount());
				return true;
			}
			StorageSession.Recipe r = panel.recipeAt(mx, my);
			if (r != null) {
				session.craft(r.id(), right, event.hasShiftDown());
				return true;
			}
			StorageSession.Entry e = panel.entryAt(mx, my);
			if (e != null) {
				int max = e.item().getMaxStackSize();
				int stack = (int) Math.min(max, e.total());
				if (event.hasShiftDown()) session.take(e.key(), max, "inventory");
				else session.take(e.key(), right ? Math.max(1, (stack + 1) / 2) : stack, "cursor");
			}
			return true;
		}
		panel.search.setFocused(false);
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return panel.drag(event.y()) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		panel.release();
		return super.mouseReleased(event);
	}

	@Override
	protected void slotClicked(Slot slot, int slotId, int button, ContainerInput input) {
		// Shift-click in the inventory: into the storage, not into the crafting grid.
		if (input == ContainerInput.QUICK_MOVE && slot != null && slot.container instanceof Inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE) {
			ItemStack it = slot.getItem();
			if (!it.isEmpty()) session.putSlot(slot.getContainerSlot(), it.getCount());
			return;
		}
		super.slotClicked(slot, slotId, button, input);
	}

	@Override
	protected boolean hasClickedOutside(double mx, double my, int xo, int yo) {
		// Clicks on the panel are not drops onto the floor.
		if (panel.contains(mx, my)) return false;
		return super.hasClickedOutside(mx, my, xo, yo);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		if (panel.contains(x, y)) {
			panel.scroll(sy);
			return true;
		}
		return super.mouseScrolled(x, y, sx, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (panel.search.isFocused() && !event.isEscape()) {
			panel.search.keyPressed(event);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (panel.search.isFocused()) return panel.search.charTyped(event);
		return super.charTyped(event);
	}

	@Override
	public void removed() {
		// JEI/REI's recipe screens replace this one while the crafting table stays open: keep the session then.
		boolean closed = minecraft.player == null || minecraft.player.containerMenu != menu;
		if (closed) {
			if (open == this) open = null;
			if (StorageSession.current() == session) StorageSession.clear();
		}
		super.removed();
	}


	private void click() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 0.35f));
	}
}
