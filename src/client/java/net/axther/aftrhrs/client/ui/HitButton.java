package net.axther.aftrhrs.client.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * A hand-drawn control as a real widget, so the screens that paint their own buttons work from the keyboard (Tab,
 * Shift+Tab, the arrows, Enter or Space) and the Narrator reads them. The screen places it each frame (setX/setY,
 * {@link #active}, {@link #visible}, the narrated message) and draws it with its {@link Painter}; a click on it, Enter or
 * Space runs {@link #press}. {@link #clip} keeps a control scrolled under a header or footer from being clicked through
 * it. The message is what the Narrator says ("Compass: ON"); {@link #label} is what the painter shows, the message
 * unless set.
 */
public class HitButton extends AbstractButton {
	/** Draws the control at its rectangle; {@code lit}: hovered, or focused from the keyboard. */
	@FunctionalInterface
	public interface Painter {
		void paint(GuiGraphicsExtractor g, HitButton self, boolean lit);
	}

	private final Runnable press;
	private final Painter painter;
	private @Nullable Component hint;
	private @Nullable Component label;
	private int clipTop = Integer.MIN_VALUE, clipBottom = Integer.MAX_VALUE;
	/** No click of its own: the press makes the screen's own sound. */
	private boolean quiet;
	/** The keyboard focus outline's color (light, for the night panels; screens on light paper pass a dark one). */
	private int focusColor = Theme.TEXT;
	/** The screen's name for it, to find it again after a resize (keeps the focus). */
	public final String id;

	public HitButton(String id, int w, int h, Component message, Runnable press, Painter painter) {
		super(0, 0, w, h, message);
		this.id = id;
		this.press = press;
		this.painter = painter;
	}

	/** Clicks only land between {@code top} and {@code bottom} (screen y). */
	public HitButton clip(int top, int bottom) {
		clipTop = top;
		clipBottom = bottom;
		return this;
	}

	/** Said by the Narrator after the message (what the control does: a row's tooltip). */
	public HitButton hint(@Nullable Component hint) {
		this.hint = hint;
		return this;
	}

	/** The press plays its own sound (no UI click). */
	public HitButton quiet() {
		quiet = true;
		return this;
	}

	/** The keyboard focus outline's color. */
	public HitButton focusColor(int argb) {
		focusColor = argb;
		return this;
	}

	/** What the painter shows (null: the message). */
	public HitButton label(@Nullable Component label) {
		this.label = label;
		return this;
	}

	public Component label() {
		return label != null ? label : getMessage();
	}

	/** Moves it (one call for the per-frame layout). */
	public void place(int x, int y) {
		setX(x);
		setY(y);
	}

	/** Whether the pointer is over it, or it has the focus and the keyboard was used last. */
	public boolean lit() {
		return isHovered() || isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
	}

	/**
	 * A mouse click doesn't move the focus here, as the screens' own hit loops never did: a press may focus something
	 * else (the pet rename box), and a focused row scrolls into view, which a click on a half-shown one shouldn't do.
	 */
	@Override
	public boolean shouldTakeFocusAfterInteraction() {
		return false;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		// The click leaves another control's keyboard focus behind (Enter would press that one), before the press runs.
		Screen screen = Minecraft.getInstance().gui.screen();
		if (screen != null && screen.getFocused() instanceof HitButton other && other != this) screen.setFocused(null);
		super.onClick(event, doubleClick);
	}

	@Override
	public void onPress(InputWithModifiers input) {
		press.run();
	}

	@Override
	public void playDownSound(SoundManager sounds) {
		if (!quiet) sounds.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f));
	}

	@Override
	public boolean isMouseOver(double x, double y) {
		return super.isMouseOver(x, y) && y >= clipTop && y < clipBottom;
	}

	@Override
	protected void handleCursor(GuiGraphicsExtractor g) {
		if (isHovered() && active) g.requestCursor(CursorTypes.POINTING_HAND);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		painter.paint(g, this, lit());
		// Where the keyboard focus is (the mouse shows itself).
		if (isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard()) {
			g.outline(getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2, focusColor);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
		if (hint != null) output.add(NarratedElementType.HINT, hint);
	}
}
