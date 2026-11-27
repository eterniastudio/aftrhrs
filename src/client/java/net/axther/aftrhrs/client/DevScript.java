package net.axther.aftrhrs.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Developer aid: plays a little script in the dev client so screens and world effects can be checked without anyone
 * touching the window. Inert unless the JVM has {@code -Daftrhrs.devScript=step;step;...} (or {@code =@file}, one step per
 * line). Steps:
 * {@code wait <ticks>}, {@code cmd <command>}, {@code strand use|attack} (click the fairy-lights strand under the crosshair), {@code look <yaw> <pitch>}, {@code slot <0-8>}, {@code use} (right-click with the held item), {@code pin}
 * (claim borders on), {@code recv <json>} (handle a message as if Feather sent it), {@code click|rclick <x> <y>} / {@code drag} / {@code hover <x> <y>} (synthetic mouse in GUI coordinates), {@code sslot <n>} (shift-click menu slot n), {@code petpopup rename|color|release},
 * {@code key <name|code> [shift|ctrl]} (a key press to the open screen),
 * {@code creative [n]} (creative inventory on the n-th Aftr Hrs tab), {@code screen <name>} (see {@link #SCREENS}), {@code inv} (inventory),
 * {@code keybinds}, {@code backpack [sneak]} (the Equip / open backpack key), {@code view first|back|front}, {@code tradescreen} (the trade window, client-only), {@code jei <item>} (JEI's recipes for it),
 * {@code updtoast available|ready [version]} (the updater's toast), {@code hold forward|back|left|right|jump|sneak <ticks>}
 * (holds a movement key in the game for that long, e.g. to steer a boat; the script goes on), {@code guiscale <n>} (0 = Auto), {@code close}, {@code shot <name>}
 * (screenshots/&lt;name&gt;.png), {@code quit}, {@code sp <world>} / {@code join <host:port>} / {@code leave} / {@code respawn} (open a singleplayer world or a
 * server, back to the title screen), {@code status}. The script starts once the Companion has linked to Feather, or at once with
 * {@code -Daftrhrs.devScript.anywhere=true} (for steps that need no world).
 */
public final class DevScript {
	/** Screens a script can open, by name. Features add theirs at init. */
	public static final Map<String, Supplier<Screen>> SCREENS = new java.util.HashMap<>();

	private static Deque<String> steps;
	private static int waiting;
	/** {@code rec}: a screenshot every tick for a while, turning the camera a little each time. */
	private static String recName;
	private static int recLeft, recIndex;
	private static float recYaw, recPitch;
	/** {@code hold}: a movement key held down in the game (not the desktop) for some ticks. */
	private static net.minecraft.client.KeyMapping held;
	private static int heldLeft;

	private DevScript() {
	}

	public static void tick(Minecraft mc) {
		if (steps == null) {
			String s = System.getProperty("aftrhrs.devScript");
			if (s == null || s.isBlank()) return;
			if (s.startsWith("@")) {
				try {
					s = java.nio.file.Files.readString(java.nio.file.Path.of(s.substring(1))).replace("\r", "").replace('\n', ';');
				} catch (java.io.IOException e) {
					Companion.LOG.warn("devScript: can't read {}", s, e);
					s = "";
				}
			}
			steps = new ArrayDeque<>();
			for (String part : s.split(";")) if (!part.isBlank()) steps.add(part.trim());
			waiting = -1;
		}
		if (held != null) {
			held.setDown(--heldLeft > 0);
			if (heldLeft <= 0) held = null;
		}
		// -Daftrhrs.devScript.anywhere=true: run without a world too (title / disconnect screens: wait, shot, screen, quit).
		boolean anywhere = Boolean.getBoolean("aftrhrs.devScript.anywhere");
		if (steps.isEmpty() || !anywhere && (mc.player == null || !net.axther.aftrhrs.client.net.Link.connected())) return;
		if (recLeft > 0) {
			String name = recName + String.format(java.util.Locale.ROOT, "_%03d", recIndex++);
			Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> {
			});
			mc.player.setYRot(mc.player.getYRot() + recYaw);
			mc.player.setXRot(mc.player.getXRot() + recPitch);
			recLeft--;
			return;
		}
		if (waiting > 0) {
			waiting--;
			return;
		}
		String step = steps.poll();
		String[] a = step.split(" ", 2);
		String arg = a.length > 1 ? a[1] : "";
		Companion.LOG.info("devScript: {}", step);
		try {
			switch (a[0]) {
				case "wait" -> waiting = Integer.parseInt(arg);
				case "hold" -> {
					String[] v = arg.split(" ");
					if (held != null) held.setDown(false);
					held = switch (v[0]) {
						case "back" -> mc.options.keyDown;
						case "left" -> mc.options.keyLeft;
						case "right" -> mc.options.keyRight;
						case "jump" -> mc.options.keyJump;
						case "sneak" -> mc.options.keyShift;
						default -> mc.options.keyUp;
					};
					heldLeft = Integer.parseInt(v[1]);
					held.setDown(true);
				}
				case "chat" -> mc.options.chatVisibility().set(arg.equals("off") ? net.minecraft.world.entity.player.ChatVisiblity.HIDDEN
					: net.minecraft.world.entity.player.ChatVisiblity.FULL);
				case "rec" -> {
					// rec <name> <frames> [yaw per frame] [pitch per frame]; frames continue numbering for the same name
					String[] v = arg.split(" ");
					if (!v[0].equals(recName)) recIndex = 0;
					recName = v[0];
					recLeft = Integer.parseInt(v[1]);
					recYaw = v.length > 2 ? Float.parseFloat(v[2]) : 0;
					recPitch = v.length > 3 ? Float.parseFloat(v[3]) : 0;
				}
				case "cmd" -> mc.player.connection.sendCommand(arg);
				// strand use|attack: right-click / punch the fairy-lights strand under the crosshair (the real click path)
				case "strand" -> {
					boolean ok = arg.equals("attack") ? net.axther.aftrhrs.client.fairylights.FairyLightsClient.attack(mc)
						: net.axther.aftrhrs.client.fairylights.FairyLightsClient.use(mc);
					Companion.LOG.info("devScript: strand {} -> {}", arg, ok ? "hit" : "nothing under the crosshair");
				}
				case "say" -> mc.player.connection.sendChat(arg);
				case "look" -> {
					String[] yp = arg.split(" ");
					mc.player.setYRot(Float.parseFloat(yp[0]));
					mc.player.setXRot(Float.parseFloat(yp[1]));
				}
				case "slot" -> mc.player.getInventory().setSelectedSlot(Integer.parseInt(arg));
				case "use" -> {
					if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
						mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
					} else {
						mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
					}
				}
				case "mockpets" -> {
					// arg: a folder with pets.json and codex.json (the creatures are the mod's own models)
					java.nio.file.Path dir = java.nio.file.Path.of(arg);
					com.google.gson.JsonObject pets = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(dir.resolve("pets.json"))).getAsJsonObject();
					com.google.gson.JsonObject codex = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(dir.resolve("codex.json"))).getAsJsonObject();
					net.axther.aftrhrs.client.pets.PetData.mock(pets);
					net.axther.aftrhrs.client.codex.CodexData.mock(codex);
				}
				case "petscreen" -> mc.gui.setScreen(new net.axther.aftrhrs.client.pets.PetScreen(net.axther.aftrhrs.client.pets.PetScreen.Tab.of(arg)));
				case "send" -> net.axther.aftrhrs.client.net.Link.send(com.google.gson.JsonParser.parseString(arg).getAsJsonObject());
				case "recv" -> net.axther.aftrhrs.client.net.Link.inject(com.google.gson.JsonParser.parseString(arg).getAsJsonObject());
				// Synthetic input straight to the open screen (GUI coordinates; never the real mouse or keyboard).
				case "click", "rclick" -> {
					String[] xy = arg.split(" ");
					if (mc.gui.screen() != null) {
						var ev = new net.minecraft.client.input.MouseButtonEvent(Double.parseDouble(xy[0]), Double.parseDouble(xy[1]),
							new net.minecraft.client.input.MouseButtonInfo(a[0].equals("click") ? com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT : com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT, 0));
						mc.gui.screen().mouseClicked(ev, false);
						mc.gui.screen().mouseReleased(ev);
					}
				}
				// Shift-clicks menu slot n of the open container screen (synthetic, through the screen like a real click).
				case "sslot" -> {
					if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs) {
						var slot = cs.getMenu().slots.get(Integer.parseInt(arg.trim()));
						int left = 0, top = 0;
						for (Class<?> k = cs.getClass(); k != null; k = k.getSuperclass()) {
							try {
								var lf = k.getDeclaredField("leftPos");
								var tf = k.getDeclaredField("topPos");
								lf.setAccessible(true);
								tf.setAccessible(true);
								left = lf.getInt(cs);
								top = tf.getInt(cs);
								break;
							} catch (NoSuchFieldException ignored) {
							}
						}
						var ev = new net.minecraft.client.input.MouseButtonEvent(left + slot.x + 8, top + slot.y + 8,
							new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 1 /* left Shift (SDL) */));
						cs.mouseClicked(ev, false);
						cs.mouseReleased(ev);
					}
				}
				// Hovers menu slot n of the open container screen (its tooltip shows; never the real cursor).
				case "hslot" -> {
					if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs) {
						var slot = cs.getMenu().slots.get(Integer.parseInt(arg.trim()));
						int left = 0, top = 0;
						for (Class<?> k = cs.getClass(); k != null; k = k.getSuperclass()) {
							try {
								var lf = k.getDeclaredField("leftPos");
								var tf = k.getDeclaredField("topPos");
								lf.setAccessible(true);
								tf.setAccessible(true);
								left = lf.getInt(cs);
								top = tf.getInt(cs);
								break;
							} catch (NoSuchFieldException ignored) {
							}
						}
						double scale = mc.getWindow().getGuiScale();
						var fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
						var fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
						fx.setAccessible(true);
						fy.setAccessible(true);
						fx.setDouble(mc.mouseHandler, (left + slot.x + 8) * scale);
						fy.setDouble(mc.mouseHandler, (top + slot.y + 8) * scale);
					}
				}
				// Puts the game's mouse position at GUI coordinates (hover effects and tooltips; never the real cursor).
				case "hover" -> {
					String[] xy = arg.split(" ");
					double scale = mc.getWindow().getGuiScale();
					for (String f : new String[]{"xpos", "ypos"}) {
						var fld = net.minecraft.client.MouseHandler.class.getDeclaredField(f);
						fld.setAccessible(true);
						fld.setDouble(mc.mouseHandler, Double.parseDouble(xy[f.equals("xpos") ? 0 : 1]) * scale);
					}
				}
				case "drag" -> {
					String[] v = arg.split(" ");
					var s = mc.gui.screen();
					if (s != null) {
						double x0 = Double.parseDouble(v[0]), y0 = Double.parseDouble(v[1]), x1 = Double.parseDouble(v[2]), y1 = Double.parseDouble(v[3]);
						var info = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0);
						s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x0, y0, info), false);
						for (int i = 1; i <= 8; i++) {
							double x = x0 + (x1 - x0) * i / 8, y = y0 + (y1 - y0) * i / 8;
							s.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(x, y, info), (x1 - x0) / 8, (y1 - y0) / 8);
						}
						s.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x1, y1, info));
					}
				}
				// key <tab|enter|esc|up|down|left|right|home|end|pgup|pgdn|scancode> [shift|ctrl|ctrl+shift|-] [keycode]: a key press to the open screen
				case "key" -> {
					String[] v = arg.split(" ");
					// {scancode, SDL keycode}: Screen navigation reads the keycode, most other checks the scancode.
					int[] k = switch (v[0]) {
						case "tab" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_TAB, 9};
						case "enter" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 13};
						case "esc" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 27};
						case "right" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT, 0x4000004F};
						case "left" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_LEFT, 0x40000050};
						case "down" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_DOWN, 0x40000051};
						case "up" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_UP, 0x40000052};
						case "home" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_HOME, 0x4000004A};
						case "end" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_END, 0x4000004D};
						case "pgup" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP, 0x4000004B};
						case "pgdn" -> new int[]{com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN, 0x4000004E};
						default -> new int[]{Integer.parseInt(v[0]), v.length > 2 ? Integer.parseInt(v[2]) : 0};
					};
					String m = v.length > 1 ? v[1] : "";
					int mods = (m.contains("shift") ? 0x1 : 0) | (m.contains("ctrl") ? 0x40 : 0); // SDL left Shift, left Ctrl
					mc.setLastInputType(k[0] == com.mojang.blaze3d.platform.InputConstants.KEY_TAB ? net.minecraft.client.InputType.KEYBOARD_TAB
						: net.minecraft.client.InputType.KEYBOARD_ARROW);
					if (mc.gui.screen() != null) mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(k[0], k[1], mods));
				}
				// jeisearch <text>: sets JEI's search (only with JEI); "jeisearch" alone clears it
				case "jeisearch" -> {
					var link = net.axther.aftrhrs.client.storage.StoragePrefs.searchLink();
					if (link != null) link.set(arg);
				}
				// type <text>: typed characters to the open screen (a search box, a sign...)
				case "type" -> {
					if (mc.gui.screen() != null) arg.codePoints().forEach(c -> mc.gui.screen().charTyped(new net.minecraft.client.input.CharacterEvent(c)));
				}
				case "scroll" -> {
					String[] v = arg.split(" ");
					if (mc.gui.screen() != null) mc.gui.screen().mouseScrolled(Double.parseDouble(v[0]), Double.parseDouble(v[1]), 0, Double.parseDouble(v[2]));
				}
				case "petpopup" -> {
					if (mc.gui.screen() instanceof net.axther.aftrhrs.client.pets.PetScreen ps) {
						Class<?> popup = Class.forName(net.axther.aftrhrs.client.pets.PetScreen.class.getName() + "$Popup");
						var open = net.axther.aftrhrs.client.pets.PetScreen.class.getDeclaredMethod("openPopup", popup);
						open.setAccessible(true);
						for (Object c : popup.getEnumConstants()) if (((Enum<?>) c).name().equalsIgnoreCase(arg)) open.invoke(ps, c);
					}
				}
				case "creative" -> {
					// Opens the creative inventory on the n-th Aftr Hrs tab (the mod's content tabs; 0 = first).
					var screen = new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get());
					mc.gui.setScreen(screen);
					boolean byName = !arg.isBlank() && !Character.isDigit(arg.charAt(0)); // or a tab by its id path
					int want = arg.isBlank() || byName ? 0 : Integer.parseInt(arg), seen = 0;
					for (var tab : net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB) {
						var key = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
						if (key == null || !key.getNamespace().equals(Companion.ID) || (byName ? !key.getPath().equals(arg) : seen++ != want)) continue;
						for (var m : screen.getClass().getMethods()) {
							if (m.getName().equals("switchToPage") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
								for (var g : screen.getClass().getMethods()) {
									if (g.getName().equals("getPage") && g.getParameterCount() == 1) m.invoke(screen, g.invoke(screen, tab));
								}
							}
						}
						var select = net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
						select.setAccessible(true);
						select.invoke(screen, tab);
					}
				}
				case "pin" -> net.axther.aftrhrs.client.claims.ClaimState.INSTANCE.pinned = true;
				// The Equip / open backpack key (backpack sneak: with Sneak held), and the camera (view first|back|front).
				case "backpack" -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new net.axther.aftrhrs.feature.relics.RelicsInit.BackpackKeyPayload(arg.equals("sneak")));
				case "view" -> mc.options.setCameraType(switch (arg) {
					case "back" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
					case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
					default -> net.minecraft.client.CameraType.FIRST_PERSON;
				});
				// Photo mode: f1 on|off (vanilla's hidden HUD), quiet on|off, hidefx on|off ("Hide world effects with F1").
				case "f1" -> {
					if (mc.gui.hud.isHidden() == arg.equals("off")) mc.gui.hud.toggle();
				}
				case "quiet" -> net.axther.aftrhrs.client.hud.HudConfig.quiet = !arg.equals("off");
				case "hidefx" -> net.axther.aftrhrs.client.hud.HudConfig.get().hideFxWithF1 = !arg.equals("off");
				case "screen" -> {
					Supplier<Screen> s = SCREENS.get(arg);
					if (s != null) mc.gui.setScreen(s.get());
				}
				// tradescreen: the trade window, client-only (no partner; labels are placeholders like Feather's)
				case "tradescreen" -> {
					var labels = java.util.List.<net.minecraft.network.chat.Component>of(net.minecraft.network.chat.Component.literal("You"),
						net.minecraft.network.chat.Component.literal("Steve"), net.minecraft.network.chat.Component.literal("Ready"),
						net.minecraft.network.chat.Component.literal("Not ready"), net.minecraft.network.chat.Component.literal("Cancel"),
						net.minecraft.network.chat.Component.literal("Ready!"), net.minecraft.network.chat.Component.literal("Waiting..."),
						net.minecraft.network.chat.Component.literal("How it works"), net.minecraft.network.chat.Component.literal("Both press Ready"));
					var menu = new net.axther.aftrhrs.feature.trade.TradeMenu(0, mc.player.getInventory(),
						new net.axther.aftrhrs.feature.trade.TradeMenu.Data(java.util.UUID.randomUUID(), "Steve", labels));
					mc.gui.setScreen(new net.axther.aftrhrs.client.trade.TradeScreen(menu, mc.player.getInventory(), net.minecraft.network.chat.Component.literal("Trade with Steve")));
				}
				case "inv" ->mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
				case "keybinds" -> mc.gui.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, mc.options));
				// jei <item id>: JEI's recipes for the item (only with JEI installed)
				case "jei" -> {
					if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("jei")) {
						var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(arg));
						Class.forName("net.axther.aftrhrs.client.compat.jei.JeiCompat").getMethod("showRecipes", net.minecraft.world.item.ItemStack.class)
							.invoke(null, new net.minecraft.world.item.ItemStack(item));
					}
				}
				// updtoast available|ready <version>: the updater's toast, as when a release is found or downloaded
				case "updtoast" -> {
					String[] v = arg.split(" ");
					var toast = net.axther.aftrhrs.client.update.Updater.class.getDeclaredMethod("toast", String.class, String.class);
					toast.setAccessible(true);
					toast.invoke(null, v[0], v.length > 1 ? v[1] : "9.9.9");
				}
				// guiscale <n>: the GUI Scale option (0 = Auto); the open screen is laid out again at the new size.
				case "guiscale" -> {
					mc.options.guiScale().set(Integer.parseInt(arg.trim()));
					mc.resizeGui();
				}
				case "close" -> mc.gui.setScreen(null);
				case "quit" -> mc.stop();
				// Singleplayer (needs -Daftrhrs.devScript.anywhere=true): open a world from saves/, leave to the title screen.
				case "sp" -> mc.createWorldOpenFlows().openWorld(arg, () -> mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
				case "respawn" -> {
					mc.player.respawn();
					mc.gui.setScreen(null);
				}
				case "leave" -> mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
				case "join" -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc,
					net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(arg),
					new net.minecraft.client.multiplayer.ServerData("devScript", arg, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
				// Log what is on screen (a disconnect reason, an error screen) for scripted checks.
				case "status" -> {
					System.gc();
					int creatures = 0;
					if (mc.level != null) for (var e : mc.level.entitiesForRendering()) if (e instanceof net.axther.aftrhrs.entity.ArcanaMob) creatures++;
					if (mc.gui.screen() != null && mc.gui.screen().getFocused() instanceof net.minecraft.client.gui.components.EditBox box)
						Companion.LOG.info("devScript: status focused box=\"{}\" focused={}", box.getValue(), box.isFocused());
					var jeiLink = net.axther.aftrhrs.client.storage.StoragePrefs.searchLink();
					if (jeiLink != null) Companion.LOG.info("devScript: status jei search=\"{}\"", jeiLink.get());
					Companion.LOG.info("devScript: status screen={} title={} player={} level={} creatures={} animDrivers={} heapMB={}",
						mc.gui.screen() == null ? "-" : mc.gui.screen().getClass().getSimpleName(), mc.gui.screen() == null ? "-" : mc.gui.screen().getTitle().getString(),
						mc.player != null, mc.level != null, creatures, net.axther.aftrhrs.entity.CreatureAnim.driverCount(),
						(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
				}
				case "pause" -> mc.pauseGame(false);
				// kickscreen: the disconnect screen a newer server shows (with Update & rejoin), without leaving
				case "kickscreen" -> {
					net.axther.aftrhrs.client.update.Restart.kicked("localhost:25806", "5.4.6");
					mc.gui.setScreen(new net.minecraft.client.gui.screens.DisconnectedScreen(new net.minecraft.client.gui.screens.TitleScreen(),
						net.minecraft.network.chat.Component.translatable("disconnect.lost"),
						net.minecraft.network.chat.Component.translatable("aftrhrs.version.older", "5.4.6", "5.4.5").append("\n\n")
							.append(net.minecraft.network.chat.Component.translatable("aftrhrs.version.older.ready", "5.4.6"))));
				}
				// restartupdate <marker file>: Update & restart once (the reopened game runs the same script; the marker stops a loop)
				case "restartupdate" -> {
					java.nio.file.Path marker = java.nio.file.Path.of(arg);
					if (!java.nio.file.Files.exists(marker)) {
						java.nio.file.Files.writeString(marker, "1");
						net.axther.aftrhrs.client.update.Restart.now(null);
					}
				}
				case "shot" -> Screenshot.grab(mc.gameDirectory, arg + ".png", mc.gameRenderer.mainRenderTarget(), 1,
					msg -> Companion.LOG.info("devScript: saved {}", arg));
				default -> Companion.LOG.warn("devScript: unknown step {}", step);
			}
		} catch (RuntimeException | java.io.IOException | ReflectiveOperationException e) {
			Companion.LOG.warn("devScript step '{}' failed", step, e);
		}
	}
}
