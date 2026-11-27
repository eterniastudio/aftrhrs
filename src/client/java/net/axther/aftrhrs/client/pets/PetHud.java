package net.axther.aftrhrs.client.pets;

import net.axther.aftrhrs.client.hud.HudConfig;
import net.axther.aftrhrs.client.hud.HudElement;
import net.axther.aftrhrs.client.entity.CreatureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Your pets that are out with you, as little live 3D portraits above the left of the hotbar: idling, with a health bar
 * and their bond hearts. Where they sit, how many show and how big they are is up to the player (HUD settings). Feather pushes a fresh {@code pets} snapshot when one is summoned, hurt, healed or sent away.
 */
public final class PetHud {
	private static final int SIZE = 30, GAP = 4;

	private PetHud() {
	}

	/** {@code preview}: the HUD layout editor is drawing it (empty cards stand in for pets that aren't out). */
	public static void draw(GuiGraphicsExtractor g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		PetData data = PetData.INSTANCE;
		// Pets stay up while typing in chat.
		if (!preview && (mc.player == null || !data.active() || mc.gui.hud.isHidden() || HudConfig.covered(mc))) return;
		HudConfig cfg = HudConfig.get();
		int max = cfg.petCount;
		List<PetData.Pet> out = new ArrayList<>();
		if (data.active()) for (PetData.Pet p : data.pets) if (p.out() && p.alive() && out.size() < max) out.add(p);
		if (out.isEmpty() && !preview) return;
		int slots = preview ? max : out.size();
		boolean vertical = cfg.petsVertical;
		int span = max * (SIZE + GAP) - GAP;
		// At home a row ends left of the hotbar; on a screen too narrow for it there (GUI 4 on a 1280-wide window) it would
		// be kept on screen over the hotbar (91 left of the center), so it stands as a column there instead.
		if (!vertical && !cfg.pos(HudElement.PETS).custom
			&& span * HudConfig.drawScale(cfg.pos(HudElement.PETS).scale) > g.guiWidth() / 2 - 91 - 2) vertical = true;
		if (!HudConfig.begin(g, HudElement.PETS, vertical ? SIZE : span, vertical ? span : SIZE)) return;
		Font font = mc.font;
		long now = Util.getMillis();
		// Packed toward the hotbar end (right when in a row, bottom when in a column).
		int lead = (max - slots) * (SIZE + GAP);
		for (int i = 0; i < slots; i++) {
			int x = vertical ? 0 : lead + i * (SIZE + GAP), y = vertical ? lead + i * (SIZE + GAP) : 0;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, PetScreen.CARD, x, y, SIZE, SIZE);
			if (i >= out.size()) {
				g.centeredText(font, "♥", x + SIZE / 2, y + SIZE / 2 - 4, 0x88F29BB8);
				continue;
			}
			portrait(g, font, out.get(i), x, y, now);
		}
		HudConfig.end(g);
	}

	private static void portrait(GuiGraphicsExtractor g, Font font, PetData.Pet p, int px, int y, long now) {
		int size = SIZE;
		boolean drawn = CreatureView.draw(g, "hud:" + p.id(), p.type(), PetScreen.look(p), px + 2, y + 2, px + size - 2, y + size - 6,
			CreatureView.DEFAULT_YAW + 10, 8, "", -1, 33);
		if (!drawn && !p.plush().isEmpty()) {
			g.item(p.plush(), px + 7, y + 6);
		}
		if (p.maxHealth() > 0) {
			float f = (float) Math.max(0, Math.min(1, p.health() / p.maxHealth()));
			int col = f > 0.5f ? 0xFF7FD67F : f > 0.25f ? 0xFFF2C14E : 0xFFE0607E;
			g.fill(px + 3, y + size - 5, px + size - 3, y + size - 3, 0xAA3A2030);
			g.fill(px + 3, y + size - 5, px + 3 + Math.round((size - 6) * f), y + size - 3, col);
		}
		for (int h = 0; h < Math.min(5, p.level()); h++) g.fill(px + 3 + h * 3, y + 3, px + 5 + h * 3, y + 5, 0xFFF29BB8);
		if (p.shiny()) g.text(font, "✦", px + size - 8, y + 2, 0xFFFFE06A, true);
	}
}
