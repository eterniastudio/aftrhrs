package net.axther.aftrhrs.client.trade;

import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.feature.trade.TradeMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/**
 * The trade window: your offer (4x4) on the left, the partner's mirrored on the right, a Ready lamp under each, the
 * countdown in the middle, Ready and Cancel buttons, and your inventory below. Labels come from the server (Feather's
 * messages.yml); every click is decided by the server ({@link TradeMenu}).
 */
public final class TradeScreen extends AbstractContainerScreen<TradeMenu> {
    private static final int LAMP_Y = TradeMenu.GRID_Y + 4 * 18 + 3, BUTTON_Y = LAMP_Y + 7;
    private static final int READY_ON = 0xFF55C46A, READY_OFF = 0xFFC45555;
    /** The partner's state on the light panel: dark green when ready, dark amber while not. */
    private static final int STATE_READY = 0xFF2E8B3E, STATE_WAITING = 0xFF8A5A00;
    private Button ready;
    private boolean shownReady;
    /** Fixed for the window's life, so cut once (init runs again on a resize): the title, the two offer labels. */
    private FormattedCharSequence titleLine, youLine, themLine;
    private Component stateReady, stateWaiting;

    public static void register() {
        MenuScreens.register(TradeMenu.TYPE, TradeScreen::new);
    }

    public TradeScreen(TradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TradeMenu.WIDTH, TradeMenu.HEIGHT);
        this.inventoryLabelY = TradeMenu.INV_Y - 10; // a pixel clear of the Ready and Cancel buttons
    }

    @Override
    protected void init() {
        super.init();
        TradeMenu.Data d = menu.data();
        shownReady = menu.myReady();
        ready = addRenderableWidget(Button.builder(readyLabel(shownReady), b -> press(TradeMenu.BUTTON_READY))
                .bounds(leftPos + TradeMenu.MY_X - 1, topPos + BUTTON_Y, 74, 18)
                .tooltip(Tooltip.create(d.label(TradeMenu.L_HOW_LORE)))
                .build());
        Button cancel = addRenderableWidget(Button.builder(d.label(TradeMenu.L_CANCEL), b -> press(TradeMenu.BUTTON_CANCEL))
                .bounds(leftPos + TradeMenu.THEIR_X - 1, topPos + BUTTON_Y, 74, 18)
                .build());
        // an operator's long label still scrolls in the button; the tooltip shows it whole
        if (font.width(d.label(TradeMenu.L_CANCEL)) > 70) cancel.setTooltip(Tooltip.create(d.label(TradeMenu.L_CANCEL)));
        // The labels sit on our light panel: nickname colors and the server's pastel state colors don't read there.
        stateReady = flat(d.label(TradeMenu.L_STATE_READY));
        stateWaiting = flat(d.label(TradeMenu.L_STATE_WAITING));
        int statusW = Math.max(Math.max(font.width(stateReady), font.width(stateWaiting)), font.width(Component.translatable("aftrhrs.trade.countdown", 9)));
        // the widest status is kept clear, so the title doesn't jump as the status changes
        titleLine = Theme.ellipsize(font, flat(title), imageWidth - 16 - statusW - 6);
        // each offer label over its 4-slot grid (98 + 72 stays inside the 176px panel)
        youLine = Theme.ellipsize(font, flat(d.label(TradeMenu.L_YOU)), 4 * 18);
        themLine = Theme.ellipsize(font, flat(d.label(TradeMenu.L_THEM)), 4 * 18);
    }

    /** The text alone, in the color it is drawn with. */
    private static Component flat(Component c) {
        return Component.literal(c.getString());
    }

    private Component readyLabel(boolean isReady) {
        return menu.data().label(isReady ? TradeMenu.L_UNREADY : TradeMenu.L_READY);
    }

    private void press(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean r = menu.myReady();
        if (r != shownReady && ready != null) {
            shownReady = r;
            ready.setMessage(readyLabel(r));
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        int x = leftPos, y = topPos;
        Theme.blit(g, Theme.STORAGE_PANEL, x, y, imageWidth, imageHeight);
        // Slot frames a row at a time (one GUI element per row instead of one per slot).
        for (int row = 0; row < (TradeMenu.OFFER + 3) / 4; row++) {
            int rowY = y + TradeMenu.GRID_Y + row * 18 - 1, n = Math.min(4, TradeMenu.OFFER - row * 4);
            Theme.slotRow(g, x + TradeMenu.MY_X - 1, rowY, n);
            Theme.slotRow(g, x + TradeMenu.THEIR_X - 1, rowY, n);
        }
        // The divider between the two offers.
        int mid = x + (TradeMenu.MY_X + 4 * 18 + TradeMenu.THEIR_X) / 2;
        g.fill(mid - 1, y + TradeMenu.GRID_Y - 2, mid + 1, y + LAMP_Y + 4, 0x40000000);
        lamp(g, x + TradeMenu.MY_X - 1, y + LAMP_Y, menu.myReady());
        lamp(g, x + TradeMenu.THEIR_X - 1, y + LAMP_Y, menu.theirReady());
        for (int row = 0; row < 3; row++) Theme.slotRow(g, x + 7, y + TradeMenu.INV_Y - 1 + row * 18, 9);
        Theme.slotRow(g, x + 7, y + TradeMenu.INV_Y + 57, 9);
    }

    private static void lamp(GuiGraphicsExtractor g, int x, int y, boolean on) {
        g.fill(x, y, x + 72, y + 4, on ? READY_ON : READY_OFF);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
        g.text(font, titleLine, titleLabelX, titleLabelY, Theme.LABEL, false);
        g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Theme.LABEL, false);
        int labelY = TradeMenu.GRID_Y - 11;
        g.text(font, youLine, TradeMenu.MY_X, labelY, Theme.LABEL, false);
        g.text(font, themLine, TradeMenu.THEIR_X, labelY, Theme.LABEL, false);
        int countdown = menu.countdown();
        boolean theirs = menu.theirReady();
        Component status = countdown > 0 ? Component.translatable("aftrhrs.trade.countdown", countdown) : theirs ? stateReady : stateWaiting;
        int w = font.width(status);
        g.text(font, status, imageWidth - 8 - w, titleLabelY, countdown > 0 || theirs ? STATE_READY : STATE_WAITING, false);
    }
}
