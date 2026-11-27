package net.axther.aftrhrs.gametest.content;

import io.netty.buffer.Unpooled;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/** Every content item and plain block: codecs, loot tables, placing/breaking blocks (tools, silk touch), food, hats, tools. */
public class ItemBlockTests {
    /** Every content item survives the save codec and the network codec unchanged (a bad component kicks clients). */
    @GameTest
    public void everyItemRoundTrips(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("item_codecs");
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.item() == null) continue;
            ItemStack s = new ItemStack(c.item());
            f.check(!s.isEmpty(), c.id() + ": empty stack");
            var enc = ItemStack.CODEC.encodeStart(ops, s);
            if (enc.error().isPresent()) {
                f.add(c.id() + ": save codec " + enc.error().get().message());
                continue;
            }
            var dec = ItemStack.CODEC.parse(ops, enc.getOrThrow());
            f.check(dec.result().isPresent() && ItemStack.isSameItemSameComponents(dec.getOrThrow(), s), c.id() + ": save round trip changed it");
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
                ItemStack back = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                f.check(ItemStack.isSameItemSameComponents(back, s), c.id() + ": network round trip changed it");
            } catch (RuntimeException ex) {
                f.add(c.id() + ": network codec " + ex);
            } finally {
                buf.release();
            }
            f.check(s.getHoverName().getString().length() > 0 && !s.getHoverName().getString().startsWith("item.aftrhrs"), c.id() + ": no name (" + s.getHoverName().getString() + ")");
        }
        f.finish(h);
    }

    /** Every content block has a loadable, non-empty loot table (crop stages and hidden parts included). */
    @GameTest
    public void everyBlockHasLoot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("block_loot");
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.block() == null || c.kind().equals("alias") || c.kind().equals("crop_stage")) continue;
            var key = c.block().getLootTable();
            if (key.isEmpty()) {
                f.add(c.id() + ": no loot table");
                continue;
            }
            LootTable t = level.getServer().reloadableRegistries().getLootTable(key.get());
            f.check(t != LootTable.EMPTY, c.id() + ": loot table " + key.get().identifier() + " missing or failed to load");
        }
        // every block in the mod namespace, not only content (storage network blocks, ...)
        for (Block b : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(b);
            if (!id.getNamespace().equals("aftrhrs") || ArcanaContent.entry(id.getPath()) != null) continue;
            var key = b.getLootTable();
            if (key.isEmpty()) continue;
            LootTable t = level.getServer().reloadableRegistries().getLootTable(key.get());
            if (b.asItem() != Items.AIR) f.check(t != LootTable.EMPTY, id + ": loot table missing or failed to load");
        }
        f.finish(h);
    }

    /** Plain blocks, stairs and slabs: placing, the right tool, no drop without a needed tool, silk touch, double slabs. */
    @GameTest(maxTicks = 200, padding = 16)
    public void blocksPlaceAndDrop(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btBlock", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("blocks");
        Holder<Enchantment> silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        int n = 0;
        // the raw table: Content keeps only what the game reads after registration
        Map<String, com.google.gson.JsonObject> blockDefs = new java.util.HashMap<>();
        for (var el : ArcanaContent.readTable().getAsJsonArray("entries")) {
            var e = el.getAsJsonObject();
            if (e.has("block") && e.get("block").isJsonObject()) blockDefs.put(e.get("id").getAsString(), e.getAsJsonObject("block"));
        }
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (!c.kind().equals("block") && !c.kind().equals("stairs") && !c.kind().equals("slab") && !c.kind().equals("wall")) continue;
            n++;
            var b = blockDefs.get(c.id());
            String tool = b.get("tool").isJsonNull() ? null : b.get("tool").getAsString();
            boolean needs = b.get("requires_tool").getAsBoolean();
            Item toolItem = tool == null ? Items.AIR : switch (tool) {
                case "pickaxe" -> Items.IRON_PICKAXE;
                case "axe" -> Items.IRON_AXE;
                case "shovel" -> Items.IRON_SHOVEL;
                case "hoe" -> Items.IRON_HOE;
                case "shears" -> Items.SHEARS;
                default -> Items.AIR;
            };
            String self = "aftrhrs:" + c.id();
            for (int mode = 0; mode < 3; mode++) { // 0 = right tool, 1 = bare hand, 2 = right tool + silk touch
                CT.clear(level, center, 3);
                FurnitureBehaviorTests.reset(p, center);
                level.setBlock(center.below(), Blocks.STONE.defaultBlockState(), 2 | 16);
                CT.pose(p, Vec3.atBottomCenterOf(center).add(0, -3, 0), 180, 30);
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(c.item(), 2));
                CT.click(p, center.below(), Direction.UP);
                BlockState s = level.getBlockState(center);
                if (!s.is(c.block())) {
                    f.add(c.id() + ": not placed (" + CT.id(s) + ")");
                    break;
                }
                int expect = 1;
                if (c.kind().equals("slab") && mode == 0) {
                    CT.click(p, center, Direction.UP); // second slab on top makes a double slab
                    s = level.getBlockState(center);
                    f.check(s.getValue(SlabBlock.TYPE) == SlabType.DOUBLE, c.id() + ": second slab did not double");
                    expect = 2;
                }
                int light = b.get("light").getAsInt();
                f.check(s.getLightEmission() == light, c.id() + ": light " + s.getLightEmission() + " expected " + light);
                ItemStack held = mode == 1 ? ItemStack.EMPTY : new ItemStack(toolItem);
                if (mode == 2) held.enchant(silk, 1);
                p.setItemInHand(InteractionHand.MAIN_HAND, held);
                if (mode == 0 && tool != null) {
                    float speed = held.getDestroySpeed(s);
                    f.check(speed > 1f, c.id() + ": " + tool + " is not faster (" + speed + ")");
                }
                CT.breakBlock(p, center);
                f.check(level.getBlockState(center).isAir(), c.id() + ": still there after breaking");
                Map<String, Integer> d = CT.collectDrops(level, center, 4);
                boolean gets = mode != 1 || !needs || tool == null;
                Map<String, Integer> want = gets ? Map.of(self, expect) : Map.of();
                f.check(d.equals(want), c.id() + " (" + new String[]{"tool", "hand", "silk"}[mode] + "): dropped " + d + " expected " + want);
            }
            bot.drain();
        }
        f.check(n >= 96, "plain blocks: " + n); // 34 + the winter block sets (50) + the Christmas blocks (12)
        CT.clear(level, center, 3);
        bot.leave();
        f.finish(h);
    }

    /** Every edible content item can be eaten: hunger goes up, the stack shrinks, use_remainder is given. */
    @GameTest(maxTicks = 200)
    public void everyFoodIsEdible(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btFood", true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("foods");
        int n = 0;
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.item() == null) continue;
            ItemStack proto = new ItemStack(c.item());
            var food = proto.get(DataComponents.FOOD);
            Consumable cons = proto.get(DataComponents.CONSUMABLE);
            if (food == null && cons == null) continue;
            n++;
            p.getInventory().clearContent();
            p.getFoodData().setFoodLevel(1);
            p.getFoodData().setSaturation(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, proto.copyWithCount(2));
            ItemStack hand = p.getMainHandItem();
            InteractionResult r = hand.use(level, p, InteractionHand.MAIN_HAND);
            f.check(r.consumesAction(), c.id() + ": can't start eating (" + r + ")");
            if (!r.consumesAction()) continue;
            ItemStack left = p.getMainHandItem().finishUsingItem(level, p);
            p.setItemInHand(InteractionHand.MAIN_HAND, left);
            if (food != null) {
                f.check(p.getFoodData().getFoodLevel() == Math.min(20, 1 + food.nutrition()), c.id() + ": food " + p.getFoodData().getFoodLevel() + " after eating (nutrition " + food.nutrition() + ")");
            }
            int count = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(c.item())) count += p.getInventory().getItem(i).getCount();
            f.check(count == 1, c.id() + ": " + count + " left of 2 after eating one");
            var rem = proto.get(DataComponents.USE_REMAINDER);
            if (rem != null) {
                ItemStack want = rem.convertInto().create();
                f.check(p.getInventory().countItem(want.getItem()) >= 1, c.id() + ": remainder " + want + " not given");
            }
            p.stopUsingItem();
        }
        f.check(n >= 50, "edible items: " + n);
        bot.leave();
        f.finish(h);
    }

    /** Hats (equippable content) go on with a right-click; tools mine what their tier mines. */
    @GameTest(maxTicks = 200)
    public void hatsEquipAndToolsMine(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btHat", true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("hats_tools");
        int hats = 0, tools = 0;
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.item() == null || c.block() != null) continue;
            ItemStack proto = new ItemStack(c.item());
            var tool = proto.get(DataComponents.TOOL);
            if (tool != null && !net.axther.aftrhrs.content.ArcanaItem.isBackpack(proto)) {
                tools++;
                String base = c.base() != null ? c.base() : "PAPER";
                BlockState target = base.endsWith("PICKAXE") ? Blocks.IRON_ORE.defaultBlockState()
                        : base.endsWith("_AXE") ? Blocks.OAK_LOG.defaultBlockState()
                        : base.endsWith("SHOVEL") ? Blocks.DIRT.defaultBlockState()
                        : base.endsWith("HOE") ? Blocks.HAY_BLOCK.defaultBlockState()
                        : base.endsWith("SWORD") ? Blocks.COBWEB.defaultBlockState() : null;
                if (target != null) {
                    f.check(proto.getDestroySpeed(target) > 1f, c.id() + " (" + base + "): not faster on " + target.getBlock());
                    if (base.endsWith("PICKAXE") && !base.startsWith("WOODEN") && !base.startsWith("GOLDEN")) {
                        f.check(proto.isCorrectToolForDrops(target), c.id() + ": can't harvest iron ore");
                    }
                    f.check(proto.isDamageableItem(), c.id() + ": tool has no durability");
                }
            }
        }
        f.check(tools >= 15, "tools: " + tools);
        bot.leave();
        f.finish(h);
    }

    /** Every hat goes on the head with a right-click (one of the stack; the old helmet comes back). */
    @GameTest(maxTicks = 600)
    public void hatsEquip(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btHat", true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("hats");
        java.util.List<ArcanaContent.Content> hats = new java.util.ArrayList<>();
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.item() != null && c.block() == null && new ItemStack(c.item()).has(DataComponents.EQUIPPABLE)) hats.add(c);
        }
        FurnitureSweepTests.stepped(h, hats, 1, 6, c -> {
            ItemStack proto = new ItemStack(c.item());
            Equippable eq = proto.get(DataComponents.EQUIPPABLE);
            p.getInventory().clearContent();
            for (EquipmentSlot s : EquipmentSlot.values()) if (s.isArmor()) p.setItemSlot(s, ItemStack.EMPTY);
            p.setItemSlot(eq.slot(), new ItemStack(Items.LEATHER_HELMET));
            p.setItemInHand(InteractionHand.MAIN_HAND, proto.copyWithCount(Math.min(3, proto.getMaxStackSize())));
            // the full server path (the tools module puts hats on through UseItemCallback)
            InteractionResult r = p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
            ItemStack worn = p.getItemBySlot(eq.slot());
            f.check(worn.is(c.item()) && worn.getCount() == 1, c.id() + ": wearing " + worn + " in " + eq.slot() + " after use (" + r + ")");
            int total = p.getInventory().countItem(c.item()); // counts the worn one too
            f.check(total == Math.min(3, proto.getMaxStackSize()), c.id() + ": " + total + " hats in all after equipping one of " + Math.min(3, proto.getMaxStackSize()));
            f.check(p.getInventory().countItem(Items.LEATHER_HELMET) == 1, c.id() + ": old helmet not given back");
            bot.drain();
        }, () -> {
            f.check(hats.size() >= 20, "hats: " + hats.size());
            bot.leave();
            f.finish(h);
        });
    }

    static boolean furniture(Block b) {
        return b instanceof FurnitureBlock;
    }

    static boolean mineable(BlockState s) {
        return s.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    /** Every visible item has a creative tab of its own (nothing lands in the uncoloured catch-all "misc" tab). */
    @GameTest
    public void everyVisibleItemHasATab(GameTestHelper h) {
        CT.Fails f = new CT.Fails("item_tabs");
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.item() == null || c.hidden()) continue;
            f.check(c.tab() != null && ArcanaContent.tab(c.tab()) != null, c.id() + ": no creative tab (" + c.tab() + ")");
        }
        f.check(ArcanaContent.tab("misc") == null, "the catch-all misc tab is registered");
        f.finish(h);
    }

    /** No two visible, tabbed items show the same name (they would look identical in creative and JEI). */
    @GameTest
    public void visibleItemNamesAreUnique(GameTestHelper h) {
        CT.Fails f = new CT.Fails("item_names");
        Map<String, String> lang = new java.util.HashMap<>();
        try (var in = ArcanaContent.class.getResourceAsStream("/assets/aftrhrs/lang/en_us.json")) {
            if (in == null) {
                f.add("no en_us.json");
            } else {
                for (var e : com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject().entrySet()) {
                    lang.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (java.io.IOException e) {
            f.add("en_us.json: " + e);
        }
        Map<String, String> seen = new java.util.HashMap<>();
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.item() == null || c.hidden() || c.tab() == null) continue;
            var name = new ItemStack(c.item()).getHoverName();
            String shown = name.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    ? lang.getOrDefault(t.getKey(), t.getKey()) : name.getString();
            String other = seen.putIfAbsent(shown, c.id());
            f.check(other == null, c.id() + " and " + other + " are both called '" + shown + "'");
        }
        f.finish(h);
    }
}
