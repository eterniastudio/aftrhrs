package net.axther.aftrhrs.client.storage;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Storage Terminal for Companion players, laid out like a big chest: the network (searchable, scrolling) on
 * top, the player's inventory below. Click a network item to take a stack (right: half) into the inventory; click
 * an inventory item to store it (right: one). The Craftable tab turns the grid into Quick Craft: left-click crafts
 * the chosen amount, right-click as many as fit, shift puts the results into the storage. Everything goes through
 * Feather, which re-reads the chests each time.
 */
public final class StorageScreen extends Screen {
	private static final int INV_LABEL = 12;
	/** Everything under the grid: the inventory label, three inventory rows, the gap, the hotbar and the bottom border. */
	private static final int BELOW_GRID = INV_LABEL + 3 * 18 + 4 + 18 + 7;
	/** The screen's height without the grid rows: the panel's title and search rows, its footer, and all of the above. */
	private static final int CHROME = NetworkPanel.HEAD + NetworkPanel.FOOT + BELOW_GRID;
	/** Space kept free above and below. */
	private static final int MARGIN = 2;

	private final StorageSession session;
	private final NetworkPanel panel;
	private int left, top, w, h;
	private int invX, invY;
	private ItemStack hoveredInv = ItemStack.EMPTY;

	StorageScreen(StorageSession session) {
		super(session.title);
		this.session = session;
		this.panel = new NetworkPanel(session, net.minecraft.client.Minecraft.getInstance().font);
		this.panel.ownBackground = false;
	}

	@Override
	protected void init() {
		// As many grid rows as fit with the inventory and hotbar on screen (240 high, as at 1280x720 with Auto: 5 rows,
		// 235 high), at most 7; never under the panel's minimum of 2, scrolling shows the rest.
		int fit = (height - 2 * MARGIN - CHROME) / NetworkPanel.CELL;
		int rows = Math.max(2, Math.min(7, fit));
		panel.place(0, 0, 9, rows);
		w = panel.width();
		h = panel.height() + BELOW_GRID;
		left = (width - w) / 2;
		top = Math.max(MARGIN, (height - h) / 2);
		panel.place(left, top, 9, rows);
		addRenderableWidget(panel.search);
		panel.focusOnOpen(this);
		invX = left + NetworkPanel.SIDE;
		invY = top + panel.height() + INV_LABEL;
	}

	private int depositX() {
		return invX + 9 * 18 + 4;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		Theme.blit(g, Theme.STORAGE_PANEL, left, top, w, h);
		panel.render(g, mx, my);

		// The player's inventory: three rows, then the hotbar, like any container.
		g.text(font, Component.translatable("container.inventory"), invX + 1, invY - 10, Theme.LABEL, false);
		Inventory inv = minecraft.player.getInventory();
		hoveredInv = ItemStack.EMPTY;
		for (int r = 0; r < 3; r++) Theme.slotRow(g, invX, invY + r * 18, 9);
		Theme.slotRow(g, invX, invY + 3 * 18 + 4, 9);
		for (int i = 0; i < 36; i++) {
			int slot = i < 27 ? i + 9 : i - 27;
			int sx = invX + (i % 9) * 18, sy = invY + (i / 9) * 18 + (i >= 27 ? 4 : 0);
			boolean hov = Theme.inside(mx, my, sx, sy, 18, 18);
			if (hov) Theme.blit(g, Theme.SLOT_HIGHLIGHT_BACK, sx - 3, sy - 3, 24, 24);
			ItemStack it = inv.getItem(slot);
			if (!it.isEmpty()) {
				g.item(it, sx + 1, sy + 1);
				g.itemDecorations(font, it, sx + 1, sy + 1);
				if (hov) hoveredInv = it;
			}
			if (hov) Theme.blit(g, Theme.SLOT_HIGHLIGHT_FRONT, sx - 3, sy - 3, 24, 24);
		}
		// Store everything but the hotbar: a hopper button beside the inventory.
		int dx = depositX(), dy = invY;
		boolean dh = Theme.inside(mx, my, dx, dy, 18, 18);
		Theme.vanillaButton(g, dx, dy, 18, 18, dh);
		g.item(new ItemStack(Items.HOPPER), dx + 1, dy + 1);
		if (dh) g.requestCursor(CursorTypes.POINTING_HAND);

		super.extractRenderState(g, mx, my, a);
		panel.tooltip(g, mx, my);
		if (!hoveredInv.isEmpty()) g.setTooltipForNextFrame(font, hoveredInv, mx, my);
		else if (dh) g.setTooltipForNextFrame(font, Component.translatable("aftrhrs.storage.deposit-all"), mx, my);
	}

	private int invSlotAt(double mx, double my) {
		for (int i = 0; i < 36; i++) {
			int sx = invX + (i % 9) * 18, sy = invY + (i / 9) * 18 + (i >= 27 ? 4 : 0);
			if (Theme.inside(mx, my, sx, sy, 18, 18)) return i < 27 ? i + 9 : i - 27;
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		if (panel.clickSearch(mx, my)) {
			setFocused(panel.search);
			panel.search.setFocused(true);
			if (right) panel.search.setValue("");
			return true;
		}
		panel.search.setFocused(false);
		if (panel.clickHeader(mx, my, right)) {
			click();
			return true;
		}
		if (panel.clickScroll(mx, my)) return true;
		StorageSession.Recipe r = panel.recipeAt(mx, my);
		if (r != null) {
			session.craft(r.id(), right, event.hasShiftDown());
			return true;
		}
		StorageSession.Entry e = panel.entryAt(mx, my);
		if (e != null) {
			int max = e.item().getMaxStackSize();
			int want = right ? Math.max(1, (int) Math.min(max, e.total()) / 2) : max;
			session.take(e.key(), want, "inventory");
			return true;
		}
		int slot = invSlotAt(mx, my);
		if (slot >= 0) {
			ItemStack it = minecraft.player.getInventory().getItem(slot);
			if (!it.isEmpty()) session.putSlot(slot, right ? 1 : it.getCount());
			return true;
		}
		if (Theme.inside(mx, my, depositX(), invY, 18, 18)) {
			click();
			session.depositAll();
			return true;
		}
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
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		panel.scroll(sy);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (panel.search.isFocused() && !event.isEscape()) {
			panel.search.keyPressed(event);
			return true;
		}
		if (!event.isEscape() && minecraft.options.keyInventory.matches(event)) {
			onClose();
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
		session.close();
		super.removed();
	}

	private void click() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 0.35f));
	}
}
