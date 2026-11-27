package net.axther.aftrhrs.gametest.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.gametest.core.Bots;
import net.axther.aftrhrs.net.Companion;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Quick Craft on the storage terminals: the chest window (players without the Companion screen) through real clicks,
 * and the Companion terminal's Craftable view through its protocol messages ({@code qc_*}), the way the mod sends
 * them. Covers Auto-Craft (planks for a chest from logs, exact counts; nothing with it off), the no-loop rule (no
 * Diamond from diamonds to block to diamonds), shift-crafting into the storage, right-click as many as fit, Show:
 * All Recipes with what is missing, the search, and bogus or refused requests.
 */
public class QuickCraftTests {
    static final BlockPos TERMINAL = new BlockPos(2, 1, 2), CHEST = new BlockPos(3, 1, 2);
    // Chest window slots (CraftView).
    static final int AUTO = 48, SHOW = 47, PAGE = 44, CRAFT_BUTTON = 53;

    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static Block terminal() {
        return BuiltInRegistries.BLOCK.getValue(AftrHrs.id("feather_storage_terminal"));
    }

    /** A floor, a Storage Terminal with a chest next to it holding {@code items}, and a player standing by. */
    static Bots.Bot rig(GameTestHelper h, String name, ItemStack... items) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        h.setBlock(TERMINAL, terminal());
        h.setBlock(CHEST, Blocks.CHEST);
        Container c = chest(h);
        for (int i = 0; i < items.length; i++) c.setItem(i, items[i]);
        Bots.Bot b = Bots.join(h, Bots.name(name));
        b.player.getInventory().clearContent();
        return b;
    }

    static Container chest(GameTestHelper h) {
        return (Container) h.getBlockEntity(CHEST, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
    }

    static int inChest(GameTestHelper h, Item item) {
        Container c = chest(h);
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    static int inInventory(ServerPlayer p, Item item) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }

    static String lore(ItemStack icon) {
        StringBuilder b = new StringBuilder();
        for (Component line : icon.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) b.append(line.getString()).append('\n');
        return b.toString();
    }

    // ------------------------------------------------------------------ chest window

    /** The recipe icon slot (0-44, 8 columns) showing this item on the current page, or -1. */
    static int icon(ServerPlayer p, Item item) {
        for (int slot = 0; slot < 45; slot++) {
            if (slot % 9 == 8) continue;
            if (p.containerMenu.slots.get(slot).getItem().is(item)) return slot;
        }
        return -1;
    }

    /** The slot of the recipe icon for this item on any page (turns pages with the Page button, all the way round), or -1. */
    static int iconAnyPage(ServerPlayer p, Item item) {
        String[] parts = p.containerMenu.slots.get(PAGE).getItem().getHoverName().getString().replaceAll("[^0-9/]", "").split("/");
        int pages = parts.length < 2 ? 1 : Integer.parseInt(parts[1]);
        for (int turn = 0; turn < pages; turn++) {
            int at = icon(p, item);
            if (at >= 0) return at;
            if (pages > 1) click(p, PAGE, 0, ContainerInput.PICKUP);
        }
        return -1;
    }

    static void click(ServerPlayer p, int slot, int button, ContainerInput input) {
        p.containerMenu.clicked(slot, button, input, p);
    }

    static boolean chestWindow(ServerPlayer p) {
        return p.containerMenu.slots.size() >= 54 && p.containerMenu != p.inventoryMenu;
    }

    /**
     * Chest window: a chest from 3 oak logs only with Auto-Craft on (it says it crafts 8 planks first, uses exactly 2
     * logs and leaves no planks); with Auto-Craft off there is no Chest. The plain Storage Terminal has Craft too.
     */
    @GameTest(maxTicks = 100)
    public void chestWindowAutoCraftsAChestFromLogs(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcAuto", new ItemStack(Items.OAK_LOG, 3));
        ServerPlayer p = b.player;
        h.useBlock(TERMINAL, p);
        check(h, chestWindow(p), "the terminal didn't open the chest window");
        check(h, p.containerMenu.slots.get(CRAFT_BUTTON).getItem().is(Items.CRAFTING_TABLE), "the plain Storage Terminal has no Craft button");
        click(p, CRAFT_BUTTON, 0, ContainerInput.PICKUP);
        h.runAfterDelay(3, () -> {
            check(h, p.containerMenu.slots.get(AUTO).getItem().is(Items.CRAFTER), "Auto-Craft isn't on by default (slot 48: "
                    + p.containerMenu.slots.get(AUTO).getItem() + ")");
            int chest = iconAnyPage(p, Items.CHEST);
            check(h, chest >= 0, "with Auto-Craft on, 3 logs don't list a Chest");
            String lore = lore(p.containerMenu.slots.get(chest).getItem());
            check(h, lore.contains("Crafts first:") && lore.contains("8× Oak Planks"), "the Chest doesn't say it crafts 8 planks first: " + lore);
        });
        // One step per tick: the window takes at most 8 clicks a tick.
        h.runAfterDelay(5, () -> {
            click(p, AUTO, 0, ContainerInput.PICKUP);
            check(h, p.containerMenu.slots.get(AUTO).getItem().is(Items.DROPPER), "Auto-Craft didn't turn off");
            check(h, iconAnyPage(p, Items.CHEST) < 0, "with Auto-Craft off there is a Chest from logs alone");
        });
        h.runAfterDelay(7, () -> check(h, iconAnyPage(p, Items.OAK_PLANKS) >= 0, "planks from logs aren't listed"));
        h.runAfterDelay(9, () -> click(p, AUTO, 0, ContainerInput.PICKUP));
        h.runAfterDelay(11, () -> {
            int again = iconAnyPage(p, Items.CHEST);
            check(h, again >= 0, "Auto-Craft back on: no Chest");
            b.clear();
            click(p, again, 0, ContainerInput.PICKUP);
            check(h, b.heard("Crafted 1× Chest (after 1 ingredient craft(s))"), "no Auto-Craft chat line: " + b.chat());
            check(h, inInventory(p, Items.CHEST) == 1, "chests in the inventory: " + inInventory(p, Items.CHEST));
            check(h, inChest(h, Items.OAK_LOG) == 1, "logs left in the storage: " + inChest(h, Items.OAK_LOG) + " (expected 1)");
            check(h, inChest(h, Items.OAK_PLANKS) == 0 && inInventory(p, Items.OAK_PLANKS) == 0, "planks were left over: "
                    + inChest(h, Items.OAK_PLANKS) + " stored, " + inInventory(p, Items.OAK_PLANKS) + " carried");
            p.closeContainer();
            b.leave();
            h.succeed();
        });
    }

    /** No loops: with 9 diamonds a Block of Diamond is listed, but never Diamond (diamonds to block to diamonds). */
    @GameTest(maxTicks = 100)
    public void chestWindowNeverListsDiamondThroughTheBlock(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcLoop", new ItemStack(Items.DIAMOND, 9));
        ServerPlayer p = b.player;
        h.useBlock(TERMINAL, p);
        click(p, CRAFT_BUTTON, 0, ContainerInput.PICKUP);
        h.runAfterDelay(3, () -> {
            check(h, p.containerMenu.slots.get(AUTO).getItem().is(Items.CRAFTER), "Auto-Craft isn't on");
            check(h, iconAnyPage(p, Items.DIAMOND_BLOCK) >= 0, "9 diamonds don't list a Block of Diamond");
            check(h, iconAnyPage(p, Items.DIAMOND) < 0, "Diamond is listed (the diamonds -> block -> diamonds loop)");
            p.closeContainer();
            b.leave();
            h.succeed();
        });
    }

    /** Shift-click crafts into the storage (the inventory gets nothing); right-click crafts as many as fit. */
    @GameTest(maxTicks = 100)
    public void chestWindowShiftCraftsIntoStorageAndRightClickCraftsAll(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcShift", new ItemStack(Items.OAK_PLANKS, 2));
        ServerPlayer p = b.player;
        h.useBlock(TERMINAL, p);
        click(p, CRAFT_BUTTON, 0, ContainerInput.PICKUP);
        h.runAfterDelay(3, () -> {
            int stick = iconAnyPage(p, Items.STICK);
            check(h, stick >= 0, "2 planks don't list sticks");
            check(h, lore(p.containerMenu.slots.get(stick).getItem()).contains("Shift: into storage"), "no shift hint in the tooltip");
            b.clear();
            click(p, stick, 0, ContainerInput.QUICK_MOVE);
            check(h, b.heard("Crafted 4× Stick into storage."), "no 'into storage' chat line: " + b.chat());
            check(h, inInventory(p, Items.STICK) == 0, "shift-crafted sticks went to the inventory: " + inInventory(p, Items.STICK));
            check(h, inChest(h, Items.STICK) == 4 && inChest(h, Items.OAK_PLANKS) == 0, "storage after the shift-craft: "
                    + inChest(h, Items.STICK) + " sticks, " + inChest(h, Items.OAK_PLANKS) + " planks");

            // Right-click: 10 planks make 5 crafts at once (a left-click would be 1).
            chest(h).setItem(5, new ItemStack(Items.OAK_PLANKS, 10));
            h.runAfterDelay(2, () -> {
                click(p, 35, 0, ContainerInput.PICKUP); // Refresh: ends the hold after the craft, reads the chest again
                int s2 = iconAnyPage(p, Items.STICK);
                check(h, s2 >= 0, "10 planks don't list sticks");
                b.clear();
                click(p, s2, 1, ContainerInput.PICKUP);
                check(h, b.heard("Crafted 20× Stick."), "right-click didn't craft all 5 times: " + b.chat());
                check(h, inInventory(p, Items.STICK) == 20 && inChest(h, Items.OAK_PLANKS) == 0, "after right-click: "
                        + inInventory(p, Items.STICK) + " sticks carried, " + inChest(h, Items.OAK_PLANKS) + " planks left");
                p.closeContainer();
                b.leave();
                h.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ the Companion terminal (protocol)

    @SuppressWarnings("unchecked")
    static <T> T stat(String field) {
        try {
            Field f = Companion.class.getDeclaredField(field);
            f.setAccessible(true);
            return (T) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Links the player as a Companion client with these features (what a hello would agree on). */
    static void link(ServerPlayer p, String... features) {
        Map<UUID, Companion.Session> sessions = stat("SESSIONS");
        sessions.put(p.getUUID(), new Companion.Session("test", Set.of(features)));
    }

    /** A client message, through the real receiver (feature checks, rate limit, auth gate). */
    static void send(ServerPlayer p, JsonObject msg) {
        try {
            byte[] json = msg.toString().getBytes(StandardCharsets.UTF_8);
            byte[] b = new byte[json.length + 1];
            System.arraycopy(json, 0, b, 1, json.length);
            Method m = Companion.class.getDeclaredMethod("receive", ServerPlayer.class, byte[].class);
            m.setAccessible(true);
            m.invoke(null, p, b);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A request whose answer the test reads at once: the bridge sends at most one list per 300 ms and takes at most
     * one craft per 100 ms, so wait that out first.
     */
    static void op(ServerPlayer p, JsonObject msg) {
        try {
            Thread.sleep(320);
        } catch (InterruptedException ignored) {
        }
        send(p, msg);
    }

    static JsonObject msg(String type, Object... kv) {
        JsonObject o = Companion.message(type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Boolean bo) o.addProperty((String) kv[i], bo);
            else if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else o.addProperty((String) kv[i], String.valueOf(v));
        }
        return o;
    }

    /** Everything the server sent this player since the last call (a bot has no channel, so it all waits in a queue). */
    static List<JsonObject> sent(ServerPlayer p) {
        Map<UUID, List<byte[]>> waiting = stat("WAITING");
        List<byte[]> q = waiting.remove(p.getUUID());
        List<JsonObject> out = new ArrayList<>();
        if (q == null) return out;
        Map<Integer, byte[][]> parts = new HashMap<>();
        for (byte[] payload : q) {
            JsonObject o = decode(payload);
            if (o == null) continue;
            if ("part".equals(o.get("t").getAsString())) {
                int id = o.get("id").getAsInt(), i = o.get("i").getAsInt(), n = o.get("n").getAsInt();
                byte[][] pieces = parts.computeIfAbsent(id, k -> new byte[n][]);
                pieces[i] = Base64.getDecoder().decode(o.get("b").getAsString());
                boolean done = true;
                for (byte[] piece : pieces) done &= piece != null;
                if (!done) continue;
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                for (byte[] piece : pieces) all.writeBytes(piece);
                o = decode(all.toByteArray());
                if (o == null) continue;
            }
            out.add(o);
        }
        return out;
    }

    static JsonObject decode(byte[] payload) {
        try {
            byte[] body;
            if (payload[0] == 0) body = java.util.Arrays.copyOfRange(payload, 1, payload.length);
            else try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1))) {
                body = in.readAllBytes();
            }
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** The last message of this type in {@code list}, or null. */
    static JsonObject last(List<JsonObject> list, String type) {
        JsonObject found = null;
        for (JsonObject o : list) if (type.equals(o.get("t").getAsString())) found = o;
        return found;
    }

    /** The recipe entry with this id in a {@code quickcraft} list, or null. */
    static JsonObject recipe(JsonObject list, String id) {
        if (list == null) return null;
        for (JsonElement e : list.getAsJsonArray("recipes")) {
            if (e.isJsonObject() && id.equals(e.getAsJsonObject().get("r").getAsString())) return e.getAsJsonObject();
        }
        return null;
    }

    static List<String> ids(JsonObject list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (JsonElement e : list.getAsJsonArray("recipes")) if (e.isJsonObject()) out.add(e.getAsJsonObject().get("r").getAsString());
        return out;
    }

    static String plain(JsonArray lore) {
        StringBuilder b = new StringBuilder();
        // Through the vanilla component, so item names are translated the way a client shows them.
        for (JsonElement e : lore) b.append(net.axther.aftrhrs.core.Adv.vanilla(GsonComponentSerializer.gson().deserializeFromTree(e)).getString()).append('\n');
        return b.toString();
    }

    /** Opens the terminal as a Companion player; returns the session id from the {@code storage} message. */
    static int openLinked(GameTestHelper h, ServerPlayer p) {
        link(p, "storage");
        sent(p);
        h.useBlock(TERMINAL, p);
        JsonObject storage = last(sent(p), "storage");
        check(h, storage != null, "a Companion player got no storage message");
        check(h, storage.has("quickcraft") && storage.getAsJsonObject("quickcraft").get("depth").getAsInt() == 3,
                "the storage message has no quickcraft field (depth 3): " + storage.get("quickcraft"));
        return storage.get("s").getAsInt();
    }

    /**
     * The Companion terminal's Craftable view: the list has the Chest from logs (Auto-Craft, crafted once), its tooltip
     * says 8 planks come first, crafting it uses exactly 2 logs; with Auto-Craft off it's gone. Diamond never shows.
     */
    @GameTest(maxTicks = 100)
    public void terminalCraftableViewAutoCraftsFromLogs(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcLink", new ItemStack(Items.OAK_LOG, 3), new ItemStack(Items.DIAMOND, 9));
        ServerPlayer p = b.player;
        int s = openLinked(h, p);
        send(p, msg("qc_open", "s", s, "all", false, "q", ""));
        JsonObject list = last(sent(p), "quickcraft");
        check(h, list != null, "qc_open got no quickcraft list");
        check(h, list.get("auto").getAsBoolean(), "Auto-Craft isn't on by default");
        JsonObject chest = recipe(list, "minecraft:chest");
        check(h, chest != null && chest.get("st").getAsString().equals("auto") && chest.get("n").getAsInt() == 1,
                "the Chest isn't listed as an Auto-Craft recipe made once: " + chest);
        check(h, recipe(list, "minecraft:diamond_block") != null, "no Block of Diamond from 9 diamonds");
        check(h, recipe(list, "minecraft:diamond") == null, "Diamond is listed (the diamonds -> block -> diamonds loop)");
        JsonObject planks = recipe(list, "minecraft:oak_planks");
        check(h, planks != null && planks.get("n").getAsInt() == 3 && planks.get("st").getAsString().equals("direct"),
                "oak planks aren't listed as 3 direct crafts: " + planks);

        send(p, msg("qc_info", "s", s, "r", "minecraft:chest"));
        JsonObject info = last(sent(p), "qc_info");
        check(h, info != null, "no qc_info reply");
        String lore = plain(info.getAsJsonArray("lore"));
        check(h, lore.contains("Crafts first:") && lore.contains("8× Oak Planks") && lore.contains("Shift: into storage"),
                "the Chest's tooltip lines: " + lore);

        b.clear();
        op(p, msg("qc_craft", "s", s, "r", "minecraft:chest", "max", false, "store", false));
        List<JsonObject> after = sent(p);
        JsonObject result = last(after, "qc_result");
        check(h, result != null && result.get("ok").getAsBoolean() && result.get("steps").getAsInt() == 1,
                "qc_result for the chest: " + result);
        check(h, b.heard("Crafted 1× Chest (after 1 ingredient craft(s))"), "no chat line: " + b.chat());
        check(h, inInventory(p, Items.CHEST) == 1 && inChest(h, Items.OAK_LOG) == 1 && inChest(h, Items.OAK_PLANKS) == 0
                && inInventory(p, Items.OAK_PLANKS) == 0, "after crafting: chest " + inInventory(p, Items.CHEST) + ", logs "
                + inChest(h, Items.OAK_LOG) + ", planks " + inChest(h, Items.OAK_PLANKS) + "+" + inInventory(p, Items.OAK_PLANKS));
        check(h, last(after, "quickcraft") != null && last(after, "quickcraft").get("held").getAsBoolean(),
                "the list after a craft isn't sent in held order");

        // Auto-Craft off: 1 log makes planks, but no Chest.
        op(p, msg("qc_options", "s", s, "auto", false));
        JsonObject off = last(sent(p), "quickcraft");
        check(h, off != null && !off.get("auto").getAsBoolean() && recipe(off, "minecraft:chest") == null
                && recipe(off, "minecraft:oak_planks") != null, "with Auto-Craft off: " + ids(off));
        b.leave();
        h.succeed();
    }

    /** Shift (store) puts the results into the network; right-click (max) crafts as many as fit. */
    @GameTest(maxTicks = 100)
    public void terminalShiftStoresAndRightClickCraftsAll(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcStore", new ItemStack(Items.OAK_PLANKS, 2));
        ServerPlayer p = b.player;
        int s = openLinked(h, p);
        send(p, msg("qc_open", "s", s, "all", false, "q", ""));
        sent(p);
        op(p, msg("qc_craft", "s", s, "r", "minecraft:stick", "max", false, "store", true));
        JsonObject result = last(sent(p), "qc_result");
        check(h, result != null && result.get("ok").getAsBoolean() && result.get("items").getAsInt() == 4, "store craft: " + result);
        check(h, b.heard("Crafted 4× Stick into storage."), "no 'into storage' line: " + b.chat());
        check(h, inInventory(p, Items.STICK) == 0 && inChest(h, Items.STICK) == 4, "shift-crafted sticks: " + inInventory(p, Items.STICK)
                + " carried, " + inChest(h, Items.STICK) + " stored");

        chest(h).setItem(10, new ItemStack(Items.OAK_PLANKS, 12));
        // The player's list must show the sticks again before they can be crafted (a tooltip request refreshes it), and
        // for 1.5 s after a craft the list keeps its places (sticks went, so they come back once that is over).
        try {
            Thread.sleep(1300);
        } catch (InterruptedException ignored) {
        }
        op(p, msg("qc_info", "s", s, "r", "minecraft:stick"));
        List<JsonObject> refreshed = sent(p);
        check(h, recipe(last(refreshed, "quickcraft"), "minecraft:stick") != null, "the list didn't pick up the new planks: "
                + (last(refreshed, "quickcraft") == null ? "no list, got " + refreshed.stream().map(o -> o.get("t").getAsString()).toList() : ids(last(refreshed, "quickcraft"))));
        op(p, msg("qc_craft", "s", s, "r", "minecraft:stick", "max", true, "store", false));
        JsonObject all = last(sent(p), "qc_result");
        check(h, all != null && all.get("ok").getAsBoolean() && all.get("items").getAsInt() == 24, "right-click craft: " + all);
        check(h, inInventory(p, Items.STICK) == 24 && inChest(h, Items.OAK_PLANKS) == 0, "after right-click: " + inInventory(p, Items.STICK)
                + " sticks carried, " + inChest(h, Items.OAK_PLANKS) + " planks left");
        b.leave();
        h.succeed();
    }

    /** Show: All Recipes lists the beacon (can't be made) with its missing ingredients; the search filters the list. */
    @GameTest(maxTicks = 100)
    public void terminalAllRecipesAndSearch(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcAll", new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.GLASS, 5));
        ServerPlayer p = b.player;
        int s = openLinked(h, p);
        send(p, msg("qc_open", "s", s, "all", false, "q", "stick"));
        JsonObject sticks = last(sent(p), "quickcraft");
        List<String> found = ids(sticks);
        check(h, found.contains("minecraft:stick") && !found.contains("minecraft:chest"), "search 'stick': " + found);
        for (JsonElement e : sticks.getAsJsonArray("recipes")) {
            JsonObject item = e.getAsJsonObject().getAsJsonObject("item");
            String id = item.has("id") ? item.get("id").getAsString() : "";
            check(h, id.contains("stick") || id.isEmpty(), "search 'stick' listed " + e);
        }
        op(p, msg("qc_options", "s", s, "q", ""));
        check(h, recipe(last(sent(p), "quickcraft"), "minecraft:chest") != null, "an empty search doesn't list the Chest again");

        op(p, msg("qc_options", "s", s, "all", true, "q", "beacon"));
        JsonObject all = last(sent(p), "quickcraft");
        JsonObject beacon = recipe(all, "minecraft:beacon");
        check(h, beacon != null && beacon.get("st").getAsString().equals("none"), "All Recipes + 'beacon' doesn't list the beacon: " + ids(all));
        check(h, all.get("all").getAsBoolean() && all.get("ready").getAsInt() == 0, "the beacon list says " + all.get("ready") + " ready");
        send(p, msg("qc_info", "s", s, "r", "minecraft:beacon"));
        String lore = plain(last(sent(p), "qc_info").getAsJsonArray("lore"));
        check(h, lore.contains("You can't make it yet") && lore.contains("(you have 0)") && lore.contains("Nether Star"),
                "the beacon's tooltip lines: " + lore);
        b.clear();
        op(p, msg("qc_craft", "s", s, "r", "minecraft:beacon", "max", false, "store", false));
        JsonObject r = last(sent(p), "qc_result");
        check(h, r != null && !r.get("ok").getAsBoolean() && "missing".equals(r.get("problem").getAsString()), "crafting the beacon: " + r);
        check(h, inInventory(p, Items.BEACON) == 0 && inChest(h, Items.GLASS) == 5, "a beacon was made or glass was used");
        b.leave();
        h.succeed();
    }

    /**
     * The list goes out again only when something changed: asking for a tooltip with nothing changed sends no new
     * list; a change in the chest does. Also times the All Recipes list over a mixed network (printed to the log).
     */
    @GameTest(maxTicks = 100)
    public void terminalListOnlyChangesWithTheItems(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcSig", new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.BIRCH_LOG, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.REDSTONE, 64), new ItemStack(Items.STRING, 64), new ItemStack(Items.GOLD_INGOT, 64),
                new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.COAL, 64), new ItemStack(Items.SAND, 64), new ItemStack(Items.GLASS, 64),
                new ItemStack(Items.CLAY_BALL, 64), new ItemStack(Items.LEATHER, 64), new ItemStack(Items.FEATHER, 64), new ItemStack(Items.FLINT, 64),
                new ItemStack(Items.QUARTZ, 64), new ItemStack(Items.COPPER_INGOT, 64), new ItemStack(Items.SLIME_BALL, 64), new ItemStack(Items.PAPER, 64));
        ServerPlayer p = b.player;
        int s = openLinked(h, p);
        long t0 = System.nanoTime();
        send(p, msg("qc_open", "s", s, "all", true, "q", ""));
        long all = System.nanoTime() - t0;
        JsonObject list = last(sent(p), "quickcraft");
        check(h, list != null && list.getAsJsonArray("recipes").size() > 500, "All Recipes listed " + (list == null ? 0 : list.getAsJsonArray("recipes").size()));
        t0 = System.nanoTime();
        op(p, msg("qc_options", "s", s, "all", false));
        long craftable = System.nanoTime() - t0;
        JsonObject ready = last(sent(p), "quickcraft");
        AftrHrs.LOG.info("Quick Craft timing: All Recipes ({} listed, {} ready) {} ms, Craftable ({} listed) {} ms",
                list.getAsJsonArray("recipes").size(), list.get("ready").getAsInt(), all / 1_000_000, ready.getAsJsonArray("recipes").size(), craftable / 1_000_000);
        check(h, all < 3_000_000_000L && craftable < 3_000_000_000L, "the lists took " + all / 1_000_000 + " / " + craftable / 1_000_000 + " ms");

        send(p, msg("qc_info", "s", s, "r", "minecraft:chest"));
        List<JsonObject> quiet = sent(p);
        check(h, last(quiet, "qc_info") != null && last(quiet, "quickcraft") == null, "nothing changed, but the list was sent again");
        chest(h).setItem(20, new ItemStack(Items.NETHER_STAR, 1));
        chest(h).setItem(21, new ItemStack(Items.OBSIDIAN, 3));
        op(p, msg("qc_info", "s", s, "r", "minecraft:chest"));
        JsonObject changed = last(sent(p), "quickcraft");
        check(h, changed != null && recipe(changed, "minecraft:beacon") != null, "after a nether star and obsidian arrived the list didn't change: "
                + (changed == null ? "no list" : "no beacon"));
        b.leave();
        h.succeed();
    }

    /** Quick Craft's recipe version (bumps when the server's recipes changed). */
    static long recipeVersion() {
        try {
            Class<?> mc = Class.forName("net.axther.aftrhrs.feature.storage.StorageModule");
            Method active = mc.getDeclaredMethod("active");
            active.setAccessible(true);
            Object m = active.invoke(null);
            Method craft = mc.getDeclaredMethod("craft");
            craft.setAccessible(true);
            Object qc = craft.invoke(m);
            Method version = qc.getClass().getDeclaredMethod("version");
            version.setAccessible(true);
            return (long) version.invoke(qc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Throttle: a burst of 50 qc_options rebuilds and sends the list at most twice (once now, once merged later). */
    @GameTest(maxTicks = 200)
    public void terminalOptionFloodRebuildsAtMostTwice(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcFlood", new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.IRON_INGOT, 64));
        ServerPlayer p = b.player;
        int s = openLinked(h, p);
        op(p, msg("qc_open", "s", s, "all", false, "q", ""));
        sent(p);
        try {
            Thread.sleep(320);
        } catch (InterruptedException ignored) {
        }
        long verBefore = recipeVersion();
        String invBefore = p.getInventory().getNonEquipmentItems().stream().filter(it -> !it.isEmpty()).map(ItemStack::toString).toList().toString();
        for (int i = 0; i < 50; i++) send(p, msg("qc_options", "s", s, "all", i % 2 == 0, "q", i % 3 == 0 ? "oak" : "", "auto", i % 4 < 2));
        int now = 0;
        for (JsonObject o : sent(p)) if ("quickcraft".equals(o.get("t").getAsString())) now++;
        final int first = now;
        check(h, first <= 1, "50 qc_options sent " + first + " lists at once");
        h.runAfterDelay(30, () -> {
            int later = 0;
            JsonObject lastList = null;
            StringBuilder seen = new StringBuilder();
            for (JsonObject o : sent(p)) {
                if ("quickcraft".equals(o.get("t").getAsString())) {
                    later++;
                    lastList = o;
                    seen.append(" v").append(o.get("v")).append(" all=").append(o.get("all")).append(" q=").append(o.get("q")).append(" auto=").append(o.get("auto"))
                            .append(" held=").append(o.get("held"));
                }
            }
            // A change of the server's recipes meanwhile (another test's module-owned recipe) is a real reason for one more.
            int allowed = 2 + (recipeVersion() != verBefore ? 1 : 0);
            check(h, first + later <= allowed, "50 qc_options sent " + (first + later) + " lists in all (at most 2 expected):" + seen + " version " + verBefore + "->" + recipeVersion() + " before=" + invBefore + " inv=" + p.getInventory().getNonEquipmentItems().stream().filter(it -> !it.isEmpty()).map(ItemStack::toString).toList()
                    + " chest=" + inChest(h, Items.OAK_LOG) + "/" + inChest(h, Items.COBBLESTONE));
            check(h, later >= 1 && lastList != null, "the merged changes never went out (" + later + " later lists)");
            b.leave();
            h.succeed();
        });
    }

    /**
     * The list is not the permission: with limited crafting turned on after the list was built, crafting a recipe the
     * player never unlocked is refused at craft time and nothing is used. (Own batch: the game rule is global.)
     */
    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:storage_isolated")
    public void terminalRechecksTheRecipeAtCraftTime(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcLimit", new ItemStack(Items.OAK_PLANKS, 2));
        ServerPlayer p = b.player;
        int s = openLinked(h, p);
        send(p, msg("qc_open", "s", s, "all", false, "q", ""));
        check(h, recipe(last(sent(p), "quickcraft"), "minecraft:stick") != null, "no sticks listed");
        var rules = h.getLevel().getGameRules();
        boolean was = rules.get(net.minecraft.world.level.gamerules.GameRules.LIMITED_CRAFTING);
        rules.set(net.minecraft.world.level.gamerules.GameRules.LIMITED_CRAFTING, true, h.getLevel().getServer());
        try {
            p.getRecipeBook().remove(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
                    net.minecraft.resources.Identifier.withDefaultNamespace("stick")));
            b.clear();
            op(p, msg("qc_craft", "s", s, "r", "minecraft:stick", "max", true, "store", false));
            JsonObject r = last(sent(p), "qc_result");
            check(h, r != null && !r.get("ok").getAsBoolean() && "blocked".equals(r.get("problem").getAsString()), "a locked recipe: " + r);
            check(h, inInventory(p, Items.STICK) == 0 && inChest(h, Items.OAK_PLANKS) == 2, "a locked recipe still crafted: "
                    + inInventory(p, Items.STICK) + " sticks, " + inChest(h, Items.OAK_PLANKS) + " planks left");
        } finally {
            rules.set(net.minecraft.world.level.gamerules.GameRules.LIMITED_CRAFTING, was, h.getLevel().getServer());
        }
        b.leave();
        h.succeed();
    }

    /**
     * Requests the server must refuse: a recipe that isn't in the player's list, a wrong session, a bad amount,
     * crafting before the view is open, garbage fields, and a client without the storage feature.
     */
    @GameTest(maxTicks = 100)
    public void terminalRefusesBogusRequests(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcBad", new ItemStack(Items.OAK_PLANKS, 4));
        ServerPlayer p = b.player;
        int s = openLinked(h, p);
        // Before qc_open: nothing happens.
        op(p, msg("qc_craft", "s", s, "r", "minecraft:stick", "max", true, "store", false));
        check(h, last(sent(p), "qc_result") == null && inInventory(p, Items.STICK) == 0, "qc_craft worked before qc_open");

        send(p, msg("qc_open", "s", s, "all", false, "q", ""));
        sent(p);
        // Not in the list (can't be made, Show: Craftable): refused as unknown, nothing used.
        op(p, msg("qc_craft", "s", s, "r", "minecraft:diamond_pickaxe", "max", true, "store", false));
        JsonObject r = last(sent(p), "qc_result");
        check(h, r != null && !r.get("ok").getAsBoolean() && "unknown".equals(r.get("problem").getAsString()), "unlisted recipe: " + r);
        // A made-up id and a number for an id.
        op(p, msg("qc_craft", "s", s, "r", "aftrhrs:nope/../../x", "max", true, "store", true));
        r = last(sent(p), "qc_result");
        check(h, r != null && !r.get("ok").getAsBoolean(), "a made-up recipe id: " + r);
        JsonObject weird = msg("qc_craft", "s", s);
        weird.add("r", new JsonArray());
        weird.addProperty("max", "yes");
        op(p, weird);
        r = last(sent(p), "qc_result");
        check(h, r != null && !r.get("ok").getAsBoolean() && inInventory(p, Items.STICK) == 0, "garbage fields: " + r);
        // Wrong session id: ignored.
        op(p, msg("qc_craft", "s", s + 1000, "r", "minecraft:stick", "max", true, "store", false));
        check(h, last(sent(p), "qc_result") == null && inInventory(p, Items.STICK) == 0, "a wrong session id crafted");
        // A bad amount is ignored (stays 1), a good one is taken.
        op(p, msg("qc_options", "s", s, "amount", 7));
        JsonObject list = last(sent(p), "quickcraft");
        check(h, list != null && list.get("amount").getAsInt() == 1, "amount 7 was accepted: " + (list == null ? null : list.get("amount")));
        op(p, msg("qc_options", "s", s, "amount", 4, "auto", "maybe"));
        list = last(sent(p), "quickcraft");
        check(h, list != null && list.get("amount").getAsInt() == 4 && list.get("auto").getAsBoolean(), "amount 4 / auto 'maybe': " + list.get("amount") + " " + list.get("auto"));
        // A client that didn't agree on "storage" is ignored entirely.
        link(p, "menus");
        op(p, msg("qc_craft", "s", s, "r", "minecraft:stick", "max", true, "store", false));
        check(h, sent(p).isEmpty() && inInventory(p, Items.STICK) == 0, "a client without the storage feature could craft");
        link(p, "storage");
        // The real thing still works: 4 crafts (amount 4) need 8 planks, only 4 are there: 2 crafts.
        op(p, msg("qc_craft", "s", s, "r", "minecraft:stick", "max", false, "store", false));
        r = last(sent(p), "qc_result");
        check(h, r != null && r.get("ok").getAsBoolean() && inInventory(p, Items.STICK) == 8 && inChest(h, Items.OAK_PLANKS) == 0,
                "the real craft: " + r + ", sticks " + inInventory(p, Items.STICK));
        b.leave();
        h.succeed();
    }

    /** Quick Craft's engine of the running storage module. */
    static Object quickCraft() {
        try {
            Class<?> mc = Class.forName("net.axther.aftrhrs.feature.storage.StorageModule");
            Method active = mc.getDeclaredMethod("active");
            active.setAccessible(true);
            Method craft = mc.getDeclaredMethod("craft");
            craft.setAccessible(true);
            return craft.invoke(active.invoke(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object qcCall(String name, Class<?>[] types, Object... args) {
        Object qc = quickCraft();
        try {
            Method m = qc.getClass().getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(qc, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static int qcBuilds() {
        return (int) qcCall("builds", new Class<?>[0]);
    }

    static Object qcById(String id) {
        return qcCall("byId", new Class<?>[]{String.class}, id);
    }

    /**
     * Quick Craft reads the recipes again only when the server's recipe list changed (no timer): asking twice, by id
     * and for a player's list builds at most once and keeps the version; a module-owned recipe (RecipeInjector.own)
     * rebuilds once, bumps the version and is found by id; removing it does the same the other way.
     */
    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:storage_isolated")
    public void recipesRebuildOnlyWhenTheListChanges(GameTestHelper h) {
        Bots.Bot b = rig(h, "qcBuilds");
        ServerPlayer p = b.player;
        long v0 = recipeVersion();
        int builds0 = qcBuilds();
        recipeVersion();
        qcById("minecraft:chest");
        qcCall("usable", new Class<?>[]{ServerPlayer.class}, p);
        check(h, qcBuilds() - builds0 <= 1 && recipeVersion() == v0, "nothing changed, but Quick Craft built " + (qcBuilds() - builds0)
                + " times (version " + v0 + " -> " + recipeVersion() + ")");
        check(h, qcById("minecraft:chest") != null, "Quick Craft has no Chest recipe");

        net.minecraft.resources.ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> key = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.RECIPE, AftrHrs.id("gametest/qc_rebuild"));
        java.util.Map<Character, net.minecraft.world.item.crafting.Ingredient> keys = Map.of(
                'D', net.minecraft.world.item.crafting.Ingredient.of(Items.DIRT), 'G', net.minecraft.world.item.crafting.Ingredient.of(Items.GRAVEL));
        var recipe = new net.minecraft.world.item.crafting.ShapedRecipe(new net.minecraft.world.item.crafting.Recipe.CommonInfo(true),
                new net.minecraft.world.item.crafting.CraftingRecipe.CraftingBookInfo(net.minecraft.world.item.crafting.CraftingBookCategory.MISC, ""),
                net.minecraft.world.item.crafting.ShapedRecipePattern.of(keys, "DGD", "GDG", "DGD"),
                net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.SPONGE, 3)));
        try {
            int before = qcBuilds();
            long v1 = recipeVersion();
            net.axther.aftrhrs.feature.recipes.RecipeInjector.own(key, new net.minecraft.world.item.crafting.RecipeHolder<>(key, recipe));
            long v2 = recipeVersion();
            check(h, qcBuilds() == before + 1, "a new recipe built Quick Craft " + (qcBuilds() - before) + " times (1 expected)");
            check(h, v2 > v1, "a new recipe didn't bump the version (" + v1 + " -> " + v2 + ")");
            check(h, qcById(key.identifier().toString()) != null, "Quick Craft doesn't know the new recipe");
            check(h, qcBuilds() == before + 1, "asking again built once more");

            net.axther.aftrhrs.feature.recipes.RecipeInjector.own(key, null);
            long v3 = recipeVersion();
            check(h, qcById(key.identifier().toString()) == null, "Quick Craft still has the removed recipe");
            check(h, v3 > v2, "removing the recipe didn't bump the version (" + v2 + " -> " + v3 + ")");
        } finally {
            net.axther.aftrhrs.feature.recipes.RecipeInjector.own(key, null);
            net.axther.aftrhrs.feature.recipes.RecipeInjector.Extra.owned.remove(key);
        }
        b.leave();
        h.succeed();
    }
}
