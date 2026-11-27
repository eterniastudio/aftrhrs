package net.axther.aftrhrs.client.cosmetics;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.feature.cosmetics.Cosmetics;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractSkullBlock;

import java.util.List;

/**
 * The client half of {@link Cosmetics}: draws players in their outfit, and the inventory screen's side panel (the four
 * cosmetic armor slots with their show/hide switches) and the hat slot's frame.
 */
public final class CosmeticsClient {
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier PANEL = AftrHrs.id("cosmetics/panel");
    private static final Identifier SHOWN = AftrHrs.id("cosmetics/shown"), SHOWN_HOVER = AftrHrs.id("cosmetics/shown_hover");
    private static final Identifier HIDDEN = AftrHrs.id("cosmetics/hidden"), HIDDEN_HOVER = AftrHrs.id("cosmetics/hidden_hover");
    /** The side panel, relative to the inventory's top-left corner (the armor slots sit at {@link Cosmetics#ARMOR_X}). */
    public static final int PANEL_X = -38, PANEL_Y = 2, PANEL_W = 36, PANEL_H = 84;
    /** The show/hide switches, left of each cosmetic slot. */
    private static final int SWITCH_X = -34, SWITCH_SIZE = 9;
    private static final String[] PIECE_KEYS = {"helmet", "chestplate", "leggings", "boots"};
    /** Where the side panel was last drawn (screen coordinates: x, y, w, h), or null; JEI keeps its overlays off it. */
    public static volatile int[] lastPanel;

    private CosmeticsClient() {
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Cosmetics.SettingsPayload.TYPE, (payload, ctx) -> Cosmetics.clientEnabled = payload.enabled());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Cosmetics.clientEnabled = false);
    }

    // ------------------------------------------------------------------ rendering

    /** After vanilla filled the render state from the real equipment: the outfit takes over what it covers. */
    public static void dress(Avatar avatar, AvatarRenderState state) {
        if (!Cosmetics.clientEnabled || !(avatar instanceof Player p)) return;
        Cosmetics.Outfit o = Cosmetics.outfit(p);
        if (o == Cosmetics.Outfit.EMPTY) return;
        boolean headChanged = false;
        for (int i = Cosmetics.HEAD; i <= Cosmetics.FEET; i++) {
            EquipmentSlot slot = Cosmetics.PIECE[i];
            ItemStack real = p.getItemBySlot(slot);
            // an elytra always shows: wings that vanish while gliding would look broken
            if (slot == EquipmentSlot.CHEST && real.has(DataComponents.GLIDER)) continue;
            ItemStack look = o.get(i);
            if (look.isEmpty() && !o.hides(i)) continue;
            ItemStack shown = look.isEmpty() ? ItemStack.EMPTY : look;
            switch (slot) {
                case HEAD -> {
                    state.headEquipment = HumanoidArmorLayer.shouldRender(shown, EquipmentSlot.HEAD) ? shown : ItemStack.EMPTY;
                    headChanged = true;
                }
                case CHEST -> state.chestEquipment = shown;
                case LEGS -> state.legsEquipment = shown;
                case FEET -> state.feetEquipment = shown;
                default -> {
                }
            }
        }
        ItemStack hat = o.get(Cosmetics.HAT);
        if (!headChanged && hat.isEmpty()) return;
        // What is drawn on the head as an item or a skull (vanilla's rule, for the look instead of the helmet slot):
        // the hat when there is one, else a non-armor cosmetic head piece (a pumpkin, a skull) or the real one.
        ItemStack head;
        if (!hat.isEmpty()) head = hat;
        else {
            ItemStack look = o.get(Cosmetics.HEAD);
            head = !look.isEmpty() ? look : o.hides(Cosmetics.HEAD) ? ItemStack.EMPTY : p.getItemBySlot(EquipmentSlot.HEAD);
        }
        state.wornHeadType = null;
        state.wornHeadProfile = null;
        state.headItem.clear();
        if (head.isEmpty()) return;
        if (head.getItem() instanceof BlockItem bi && bi.getBlock() instanceof AbstractSkullBlock skull) {
            state.wornHeadType = skull.getType();
            state.wornHeadProfile = head.get(DataComponents.PROFILE);
        } else if (!hat.isEmpty() || !HumanoidArmorLayer.shouldRender(head, EquipmentSlot.HEAD)) {
            Minecraft.getInstance().getItemModelResolver().updateForLiving(state.headItem, head, ItemDisplayContext.HEAD, p);
        }
    }

    // ------------------------------------------------------------------ the inventory screen

    /** Are the outfit slots on this menu (the server has them) and the side panel showing? */
    private static boolean panelShown(InventoryMenu menu) {
        if (menu.slots.size() <= Cosmetics.MENU_FIRST + Cosmetics.FEET) return false;
        return menu.slots.get(Cosmetics.MENU_FIRST + Cosmetics.HEAD).isActive();
    }

    /** The background: the hat slot's frame and the side panel with its slot frames and switches. */
    public static void drawBackground(GuiGraphicsExtractor g, InventoryMenu menu, int left, int top, int mx, int my, boolean bookOpen) {
        Cosmetics.clientPanelHidden = bookOpen;
        lastPanel = null;
        if (menu.slots.size() <= Cosmetics.MENU_FIRST + Cosmetics.FEET) return;
        Slot hat = menu.slots.get(Cosmetics.MENU_FIRST + Cosmetics.HAT);
        if (hat.isActive()) g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, left + hat.x - 1, top + hat.y - 1, 18, 18);
        if (!panelShown(menu)) return;
        lastPanel = new int[]{left + PANEL_X, top + PANEL_Y, PANEL_W, PANEL_H};
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left + PANEL_X, top + PANEL_Y, PANEL_W, PANEL_H);
        Cosmetics.Outfit o = Cosmetics.outfit(Minecraft.getInstance().player);
        for (int i = Cosmetics.HEAD; i <= Cosmetics.FEET; i++) {
            Slot s = menu.slots.get(Cosmetics.MENU_FIRST + i);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, left + s.x - 1, top + s.y - 1, 18, 18);
            int sx = left + SWITCH_X, sy = top + switchY(i);
            boolean hover = inside(mx, my, sx, sy, SWITCH_SIZE, SWITCH_SIZE);
            Identifier icon = o.hides(i) ? (hover ? HIDDEN_HOVER : HIDDEN) : (hover ? SHOWN_HOVER : SHOWN);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, icon, sx, sy, SWITCH_SIZE, SWITCH_SIZE);
        }
    }

    /** Tooltips for the switches and the empty outfit slots. */
    public static void drawTooltips(GuiGraphicsExtractor g, Font font, InventoryMenu menu, int left, int top, int mx, int my, ItemStack carried) {
        if (menu.slots.size() <= Cosmetics.MENU_FIRST + Cosmetics.FEET) return;
        Cosmetics.Outfit o = Cosmetics.outfit(Minecraft.getInstance().player);
        if (panelShown(menu)) {
            for (int i = Cosmetics.HEAD; i <= Cosmetics.FEET; i++) {
                if (!inside(mx, my, left + SWITCH_X, top + switchY(i), SWITCH_SIZE, SWITCH_SIZE)) continue;
                String piece = PIECE_KEYS[i - Cosmetics.HEAD];
                g.setTooltipForNextFrame(font, List.of(
                        Component.translatable("aftrhrs.cosmetics." + (o.hides(i) ? "hidden" : "shown"), Component.translatable("aftrhrs.cosmetics.piece." + piece)).getVisualOrderText(),
                        Component.translatable("aftrhrs.cosmetics.switch_hint").withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText()), mx, my);
                return;
            }
        }
        if (!carried.isEmpty()) return;
        for (int i = 0; i < Cosmetics.SIZE; i++) {
            Slot s = menu.slots.get(Cosmetics.MENU_FIRST + i);
            if (!s.isActive() || s.hasItem() || !inside(mx, my, left + s.x - 1, top + s.y - 1, 18, 18)) continue;
            String key = i == Cosmetics.HAT ? "hat" : PIECE_KEYS[i - Cosmetics.HEAD];
            g.setTooltipForNextFrame(font, List.of(
                    Component.translatable("aftrhrs.cosmetics.slot." + key).getVisualOrderText(),
                    Component.translatable("aftrhrs.cosmetics.slot." + key + ".hint").withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText()), mx, my);
            return;
        }
    }

    /** A click on a switch flips it (the server answers with the new outfit); true when one was hit. */
    public static boolean click(InventoryMenu menu, int left, int top, double mx, double my) {
        if (!panelShown(menu)) return false;
        for (int i = Cosmetics.HEAD; i <= Cosmetics.FEET; i++) {
            if (!inside(mx, my, left + SWITCH_X, top + switchY(i), SWITCH_SIZE, SWITCH_SIZE)) continue;
            if (ClientPlayNetworking.canSend(Cosmetics.TogglePayload.TYPE)) ClientPlayNetworking.send(new Cosmetics.TogglePayload(i));
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
            return true;
        }
        return false;
    }

    /** Is (mx, my) on the side panel (a click there is not a click outside the inventory, which would drop the cursor)? */
    public static boolean onPanel(InventoryMenu menu, int left, int top, double mx, double my) {
        return panelShown(menu) && inside(mx, my, left + PANEL_X, top + PANEL_Y, PANEL_W, PANEL_H);
    }

    private static int switchY(int piece) {
        return Cosmetics.ARMOR_Y[piece - Cosmetics.HEAD] + 4;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
