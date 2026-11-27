package net.axther.aftrhrs.client.fish;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Link;
import net.axther.aftrhrs.feature.fishing.FishFight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * The reel-in fight for a big fish (Feather's {@code fish_fight}, docs/companion-protocol.md "Fishing"). Hold the
 * mouse, Space or the use key to reel: the tension bar rises (faster while the fish runs), the catch bar fills; let go
 * and the tension drops while the fish takes line. Too much tension snaps the line, an empty catch bar loses the fish.
 * The screen plays {@link FishFight.Sim} with Feather's numbers and, when the fight ends (or Esc gives up), sends its
 * inputs ({@code fish_fight_result}: ticks played, the ticks where reeling switched). Feather replays them and alone
 * decides whether the fish is landed.
 */
public final class FightScreen extends Screen {
	private final long id;
	private final FishFight.Sim sim;
	private final String fishName;
	private final int color;
	private final List<Integer> toggles = new ArrayList<>();
	private boolean mouseDown, keyDown, reeling, sent;
	private float shownTension, shownProgress;

	private FightScreen(long id, FishFight.Params params, String fishName, int color) {
		super(Component.translatable("aftrhrs.fish.fight.title"));
		this.id = id;
		this.sim = new FishFight.Sim(params);
		this.fishName = fishName;
		this.color = color;
		this.shownTension = sim.tension;
		this.shownProgress = sim.progress;
	}

	static void register() {
		Link.on("fish_fight", m -> {
			long seed;
			try {
				seed = Long.parseLong(Link.str(m, "seed"));
			} catch (NumberFormatException e) {
				return;
			}
			FishFight.Params p = new FishFight.Params(seed, (float) Link.dbl(m, "strength", 0.5), (float) Link.dbl(m, "reel", 1),
				(float) Link.dbl(m, "line", 1), Link.num(m, "max", 900));
			Minecraft mc = Minecraft.getInstance();
			mc.gui.setScreen(new FightScreen(Link.lng(m, "id", 0), p, Link.str(m, "name"), FishJournal.hex(Link.str(m, "color"))));
		});
		Link.on("fish_fight_end", m -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() instanceof FightScreen s && s.id == Link.lng(m, "id", -1)) {
				s.sent = true;
				s.onClose();
			}
		});
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
	public void tick() {
		if (sent || sim.outcome != FishFight.Outcome.RUNNING) return;
		boolean want = mouseDown || keyDown;
		if (want != reeling) {
			toggles.add(sim.tick);
			reeling = want;
		}
		boolean wasRunning = sim.running();
		sim.step(reeling);
		if (!wasRunning && sim.running()) sound(SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 1.4f, 0.4f);
		if (reeling && sim.tick % 4 == 0) sound(SoundEvents.FISHING_BOBBER_RETRIEVE, 1.6f + sim.tension * 0.4f, 0.25f);
		if (sim.outcome != FishFight.Outcome.RUNNING) send();
	}

	private void sound(net.minecraft.sounds.SoundEvent e, float pitch, float vol) {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, vol));
	}

	/** Reports what was played (a fight that isn't over is a give-up) and closes. */
	private void send() {
		if (sent) return;
		sent = true;
		JsonObject m = Link.message("fish_fight_result");
		m.addProperty("id", id);
		m.addProperty("ticks", Math.max(1, sim.tick));
		JsonArray a = new JsonArray();
		for (int t : toggles) if (t < Math.max(1, sim.tick)) a.add(t);
		m.add("toggles", a);
		Link.send(m);
		onClose();
	}

	@Override
	public void onClose() {
		if (!sent) {
			send();
			return;
		}
		super.onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		mouseDown = true;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		mouseDown = false;
		return true;
	}

	private boolean reelKey(KeyEvent event) {
		return event.key() == InputConstants.KEY_SPACE || minecraft.options.keyUse.matches(event) || minecraft.options.keyJump.matches(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (reelKey(event)) {
			keyDown = true;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (reelKey(event)) {
			keyDown = false;
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractRenderState(g, mx, my, a);
		shownTension = Mth.lerp(0.35f, shownTension, sim.tension);
		shownProgress = Mth.lerp(0.35f, shownProgress, sim.progress);
		int w = Math.min(240, width - 40), h = 74;
		int x = (width - w) / 2, y = height - h - 52;
		g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xCC0E0B1C);
		g.fill(x - 1, y - 1, x + w + 1, y, 0xFF3A3262);
		g.text(font, Component.literal(fishName), x + 6, y + 5, 0xFF000000 | color, true);
		Component state = sim.running() ? Component.translatable("aftrhrs.fish.fight.running")
			: Component.translatable("aftrhrs.fish.fight.resting");
		g.text(font, state, x + w - 6 - font.width(state), y + 5, sim.running() ? 0xFFFF8A65 : Theme.MUTED, true);

		// tension: green, amber, red toward the line's limit (the snap point is the bar's right end)
		int bx = x + 6, bw = w - 12, by = y + 20;
		g.text(font, Component.translatable("aftrhrs.fish.fight.tension"), bx, by, Theme.MUTED, false);
		by += 10;
		float limit = sim.params.lineStrength();
		g.fill(bx, by, bx + bw, by + 8, 0xFF1A1630);
		int warn = bx + (int) (bw * 0.7f), danger = bx + (int) (bw * 0.88f);
		g.fill(warn, by, danger, by + 8, 0xFF3A2E14);
		g.fill(danger, by, bx + bw, by + 8, 0xFF3E1418);
		float t = Mth.clamp(shownTension / limit, 0, 1);
		int tc = t < 0.7f ? 0xFF6FD08C : t < 0.88f ? 0xFFFFC857 : 0xFFFF5A5A;
		g.fill(bx, by + 1, bx + (int) (bw * t), by + 7, tc);

		// catch: how much line is won back; empty = gone, full = landed
		by += 14;
		g.text(font, Component.translatable("aftrhrs.fish.fight.catch"), bx, by, Theme.MUTED, false);
		by += 10;
		g.fill(bx, by, bx + bw, by + 8, 0xFF1A1630);
		float pr = Mth.clamp(shownProgress, 0, 1);
		g.fill(bx, by + 1, bx + (int) (bw * pr), by + 7, 0xFF8FD3FF);
		int secs = Math.max(0, (sim.params.maxTicks() - sim.tick) / 20);
		Component hint = Component.translatable(reeling ? "aftrhrs.fish.fight.reeling" : "aftrhrs.fish.fight.hint", secs);
		g.centeredText(font, hint, x + w / 2, y + h + 6, reeling ? Theme.SKY : Theme.TEXT);
	}
}
