package net.axther.aftrhrs.client.menu;

import net.axther.aftrhrs.client.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A button drawn with the menu sprites (inputs inside Feather menus: toggles and option pickers; the menu's exit).
 * {@link #fitted()} draws the label cut with "…" (full label in the tooltip) instead of vanilla's marquee scroll.
 */
final class NightButton extends AbstractButton {
	private final Consumer<NightButton> onPress;
	private boolean fitted, quiet, tipCut;
	/** Drawn lit though inactive (the menu's pressed button while it waits for the server). */
	boolean held;
	/** Drawn as if active though it isn't (the menu's first moments of waiting, before the rest dims). */
	boolean undimmed;
	/** The fitted label's color. */
	int textColor = Theme.TEXT;

	NightButton(int w, int h, Component message, Consumer<NightButton> onPress) {
		super(0, 0, w, h, message);
		this.onPress = onPress;
	}

	/** The label is cut to the button with "…", the whole label in a tooltip when cut. */
	NightButton fitted() {
		fitted = true;
		return this;
	}

	/** No vanilla click sound (the press plays its own). */
	NightButton quiet() {
		quiet = true;
		return this;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.accept(this);
	}

	@Override
	public void playDownSound(SoundManager sounds) {
		if (!quiet) super.playDownSound(sounds);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Theme.menuButton(g, getX(), getY(), getWidth(), getHeight(), held || active && isHoveredOrFocused(), active || held || undimmed);
		if (!fitted) {
			extractDefaultLabel(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
			return;
		}
		Font font = Minecraft.getInstance().font;
		int room = getWidth() - 8;
		boolean cut = !Theme.fits(font, getMessage(), room);
		if (cut != tipCut) {
			tipCut = cut;
			setTooltip(cut ? Tooltip.create(getMessage()) : null);
		}
		g.centeredText(font, Theme.fit(font, getMessage(), room), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, textColor);
	}

	@Override
	protected void handleCursor(GuiGraphicsExtractor g) {
		// a held or not-yet-dimmed button isn't "not allowed", just busy for a moment
		if (active || !held && !undimmed) super.handleCursor(g);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
