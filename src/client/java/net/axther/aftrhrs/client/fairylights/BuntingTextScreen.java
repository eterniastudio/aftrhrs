package net.axther.aftrhrs.client.fairylights;

import com.mojang.blaze3d.platform.InputConstants;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.feature.fairylights.FairyNet;
import net.axther.aftrhrs.feature.fairylights.StrandSpec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Letters for a Pennant Bunting: one per pennant, centered on the strand. The box cleans what is typed as it goes (the
 * letters a pennant can show, upper case), Enter is Done, and a counter shows how many of the pennants are used.
 */
public final class BuntingTextScreen extends Screen {
    private final FairyNet.EditText edit;
    private EditBox box;

    public BuntingTextScreen(FairyNet.EditText edit) {
        super(Component.translatableWithFallback("aftrhrs.fairy_lights.letters.title", "Bunting Letters"));
        this.edit = edit;
    }

    @Override
    protected void init() {
        int w = 200, x = (width - w) / 2, y = height / 2 - 20;
        box = new EditBox(font, x, y, w, 20, Component.translatableWithFallback("aftrhrs.fairy_lights.letters.title", "Bunting Letters"));
        box.setMaxLength(Math.max(1, edit.max()));
        box.setResponder(v -> {
            String c = StrandSpec.cleanText(v);
            if (c.equals(v)) return;   // cleanText is idempotent, so the nested call from setValue stops here
            // keep the caret where the player was typing: after the cleaned text that was before it
            int cur = Math.min(c.length(), StrandSpec.cleanText(v.substring(0, Math.min(box.getCursorPosition(), v.length()))).length());
            box.setValue(c);
            box.moveCursorTo(cur, false);
        });
        box.setValue(edit.text());
        box.setHint(ALLOWED);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done()).bounds(x, y + 28, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + w / 2 + 2, y + 28, w / 2 - 2, 20).build());
    }

    /** Enter (or keypad Enter) is Done, unless a focused widget (the Done / Cancel buttons) takes the key first. */
    @Override
    public boolean keyPressed(KeyEvent e) {
        if (super.keyPressed(e)) return true;
        if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) {
            done();
            return true;
        }
        return false;
    }

    private void done() {
        ClientPlayNetworking.send(new FairyNet.SetText(edit.origin(), edit.strand(), StrandSpec.cleanText(box.getValue())));
        onClose();
    }

    /**
     * A night panel behind the title, counter, box and allowed letters: the strand is hung outdoors, and the lavender lines
     * all but vanish over a bright blurred sky without one.
     */
    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        int w = Math.max(200, font.width(ALLOWED)), x = (width - w) / 2;
        g.fill(x - 10, height / 2 - 56, x + w + 10, height / 2 + 48, 0xD0140F26);
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, height / 2 - 48, 0xFFF2C94C);
        int n = box.getValue().length(), max = Math.max(1, edit.max());
        g.centeredText(font, Component.translatableWithFallback("aftrhrs.fairy_lights.letters.count", "%s / %s letters", n, max),
                width / 2, height / 2 - 34, n >= max ? Theme.MOON : Theme.MUTED);
        // the box's hint only shows while it is empty and unfocused (never while typing), so the kept set gets its own
        // line under Done / Cancel (which end at height / 2 + 28)
        g.centeredText(font, ALLOWED, width / 2, height / 2 + 34, Theme.MUTED);
    }

    private static final Component ALLOWED = Component.translatableWithFallback("aftrhrs.fairy_lights.letters.allowed", "A-Z 0-9 !?.,'&+-*#@:");

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
