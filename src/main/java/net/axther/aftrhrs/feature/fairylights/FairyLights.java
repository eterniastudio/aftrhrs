/*
 * Fairy lights for Aftr Hrs: based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 */
package net.axther.aftrhrs.feature.fairylights;

import com.mojang.serialization.Codec;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registration and shared helpers of the fairy lights (String Hook, knots, the four strand items, 13 light styles,
 * pennants, the pattern recipe, payloads, creative tab). Called from {@code AftrHrs.onInitialize}, both sides.
 */
public final class FairyLights {
    public static DataComponentType<StrandSpec> STRAND;
    public static DataComponentType<Boolean> TWINKLE;
    public static HookBlock HOOK;
    public static Item HOOK_ITEM;
    public static BlockEntityType<HookBlockEntity> HOOK_BE;
    public static EntityType<KnotEntity> KNOT;
    public static final Map<StrandKind, Item> STRANDS = new EnumMap<>(StrandKind.class);
    public static final Map<BulbStyle, Item> BULBS = new EnumMap<>(BulbStyle.class);
    public static final Map<StrandSpec.Pennant, Item> PENNANTS = new EnumMap<>(StrandSpec.Pennant.class);
    public static ResourceKey<CreativeModeTab> TAB;

    // pitched vanilla sounds, chosen for each action
    public static final SoundEvent SOUND_CONNECT = SoundEvents.LEAD_TIED;
    public static final SoundEvent SOUND_DISCONNECT = SoundEvents.LEAD_UNTIED;
    public static final SoundEvent SOUND_SNAP = SoundEvents.LEAD_BREAK;
    public static final SoundEvent SOUND_STRETCH = SoundEvents.LEAD_TIED;
    public static final SoundEvent SOUND_ON = SoundEvents.COPPER_BULB_TURN_ON;
    public static final SoundEvent SOUND_OFF = SoundEvents.COPPER_BULB_TURN_OFF;
    public static final SoundEvent SOUND_SWAP = SoundEvents.AMETHYST_CLUSTER_PLACE;

    private static boolean initialized;

    private FairyLights() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        STRAND = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, AftrHrs.id("strand"),
                DataComponentType.<StrandSpec>builder().persistent(StrandSpec.CODEC).networkSynchronized(StrandSpec.STREAM_CODEC).cacheEncoding().build());
        TWINKLE = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, AftrHrs.id("twinkle"),
                DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());

        ResourceKey<net.minecraft.world.level.block.Block> hookKey = ResourceKey.create(Registries.BLOCK, AftrHrs.id("fairy_hook"));
        HOOK = Registry.register(BuiltInRegistries.BLOCK, hookKey, new HookBlock(BlockBehaviour.Properties.of().setId(hookKey)
                .strength(0.3f).noCollision().noOcclusion().sound(SoundType.CHAIN).pushReaction(PushReaction.POPPED)));
        HOOK_ITEM = item("fairy_hook", p -> new BlockItem(HOOK, p.useBlockDescriptionPrefix()));
        HOOK_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("fairy_hook"),
                FabricBlockEntityTypeBuilder.create(HookBlockEntity::new, HOOK).build());
        ResourceKey<EntityType<?>> knotKey = ResourceKey.create(Registries.ENTITY_TYPE, AftrHrs.id("fairy_knot"));
        KNOT = Registry.register(BuiltInRegistries.ENTITY_TYPE, knotKey,
                EntityType.Builder.<KnotEntity>of(KnotEntity::new, MobCategory.MISC).noLootTable().sized(0.375f, 0.375f)
                        .eyeHeight(0.0625f).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE).build(knotKey));

        // the knots' synced attachment must exist before anyone joins (clients announce which attachments they accept)
        java.util.Objects.requireNonNull(KnotEntity.DATA);

        for (StrandKind k : StrandKind.values()) STRANDS.put(k, item(k.itemId, p -> new StrandItem(k, p.stacksTo(16))));
        for (BulbStyle s : BulbStyle.values()) BULBS.put(s, item(s.itemId(), p -> new BulbItem(s, p.stacksTo(16))));
        for (StrandSpec.Pennant s : StrandSpec.Pennant.values()) PENNANTS.put(s, item(s.itemId(), p -> new PennantItem(s, p.stacksTo(16))));

        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, AftrHrs.id("fairy_pattern"), PatternRecipe.SERIALIZER);

        PayloadTypeRegistry.serverboundPlay().register(FairyNet.Action.TYPE, FairyNet.Action.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FairyNet.SetText.TYPE, FairyNet.SetText.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FairyNet.EditText.TYPE, FairyNet.EditText.CODEC);
        FairyNet.registerServer();

        TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, AftrHrs.id("fairy_lights"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB, FabricCreativeModeTab.builder()
                .title(Component.translatableWithFallback("itemGroup.aftrhrs.fairy_lights", "Lights & Garlands").copy()
                        .withStyle(s -> s.withColor(0xF2C94C)))
                .icon(() -> preset("holiday"))
                .displayItems((params, out) -> creativeItems().forEach(out::accept))
                .build());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) StrandItem.tickPlayer(p);
        });
        FairyCommands.register();
    }

    private static Item item(String id, java.util.function.Function<Item.Properties, Item> make) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, AftrHrs.id(id));
        return Registry.register(BuiltInRegistries.ITEM, key, make.apply(new Item.Properties().setId(key)));
    }

    // ------------------------------------------------------------------ helpers

    public static ItemStack strandItem(StrandKind kind) {
        return new ItemStack(STRANDS.get(kind));
    }

    public static Item bulbItem(BulbStyle s) {
        return BULBS.get(s);
    }

    public static Item pennantItem(StrandSpec.Pennant s) {
        return PENNANTS.get(s);
    }

    public static StrandSpec spec(ItemStack s) {
        StrandSpec spec = s.get(STRAND);
        if (spec != null) return spec;
        if (s.getItem() instanceof StrandItem si && si.kind == StrandKind.TINSEL) {
            DyedItemColor d = s.get(DataComponents.DYED_COLOR);
            return StrandSpec.EMPTY.withColor(d == null ? -1 : d.rgb() & 0xFFFFFF);
        }
        return StrandSpec.EMPTY;
    }

    /**
     * Writes a strand's spec onto its item, plus the icon colors (custom model data colors: the first bulbs / pennants,
     * so the inventory icon shows the strand's own colors).
     */
    public static void setSpec(ItemStack s, StrandSpec spec) {
        if (s.getItem() instanceof StrandItem si && si.kind == StrandKind.TINSEL) {
            if (spec.color() >= 0) s.set(DataComponents.DYED_COLOR, new DyedItemColor(spec.color()));
            else s.remove(DataComponents.DYED_COLOR);
            s.remove(STRAND);
            return;
        }
        if (spec.equals(StrandSpec.EMPTY)) s.remove(STRAND);
        else s.set(STRAND, spec);
        List<Integer> colors = new ArrayList<>();
        if (!spec.bulbs().isEmpty()) for (int i = 0; i < 4; i++) colors.add(spec.bulbs().get(i % spec.bulbs().size()).rgb());
        if (!spec.flags().isEmpty()) for (int i = 0; i < 4; i++) colors.add(spec.flags().get(i % spec.flags().size()).rgb());
        if (colors.isEmpty()) s.remove(DataComponents.CUSTOM_MODEL_DATA);
        else s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(spec.wire().ordinal() == 1), List.of(), colors));
    }

    private static StrandSpec.Bulb b(BulbStyle s, int color) {
        return new StrandSpec.Bulb(s, color, false);
    }

    private static StrandSpec.Bulb t(BulbStyle s, int color) {
        return new StrandSpec.Bulb(s, color, true);
    }

    private static StrandSpec.Flag f(StrandSpec.Pennant s, int color) {
        return new StrandSpec.Flag(s, color);
    }

    // Christmas palette (matches the holiday decorations): berry red, pine green, warm gold, frost blue, snow white
    public static final int RED = 0xD8322F, GREEN = 0x2F9E4A, GOLD = 0xF5B93A, BLUE = 0x3F7FE0, WHITE = 0xFFF4DC, PINK = 0xFF7AB0,
            ICE = 0xBFE6FF, PURPLE = 0x9A5BE0, ORANGE = 0xFF8A2E;

    /** Ready-made strands for the creative tab, commands and tests. */
    public static ItemStack preset(String name) {
        StrandSpec.Pennant TRI = StrandSpec.Pennant.TRIANGLE, SWA = StrandSpec.Pennant.SWALLOWTAIL, SQ = StrandSpec.Pennant.SQUARE;
        BulbStyle H = BulbStyle.HOLIDAY, F = BulbStyle.FAIRY;
        StrandKind kind = StrandKind.LIGHTS;
        StrandSpec spec = switch (name) {
            case "holiday" -> StrandSpec.EMPTY.withBulbs(List.of(b(H, RED), b(H, GREEN), b(H, GOLD), b(H, BLUE), b(H, WHITE))).withWire(StrandSpec.Wire.GREEN);
            case "warm" -> StrandSpec.EMPTY.withBulbs(List.of(b(F, -1)));
            case "twinkle" -> StrandSpec.EMPTY.withBulbs(List.of(t(F, WHITE), t(F, GOLD))).withWire(StrandSpec.Wire.WHITE);
            case "candy_cane" -> StrandSpec.EMPTY.withBulbs(List.of(b(H, RED), b(H, WHITE))).withWire(StrandSpec.Wire.WHITE);
            case "icicles" -> StrandSpec.EMPTY.withBulbs(List.of(t(BulbStyle.ICICLE, -1), b(BulbStyle.ICICLE, ICE))).withWire(StrandSpec.Wire.WHITE);
            case "winter" -> StrandSpec.EMPTY.withBulbs(List.of(b(BulbStyle.SNOWFLAKE, ICE), b(BulbStyle.STAR, GOLD))).withWire(StrandSpec.Wire.WHITE);
            case "lanterns" -> StrandSpec.EMPTY.withBulbs(List.of(b(BulbStyle.PAPER_LANTERN, RED), b(BulbStyle.PAPER_LANTERN, GOLD)));
            case "cafe" -> StrandSpec.EMPTY.withBulbs(List.of(b(BulbStyle.EDISON, -1)));
            case "rainbow" -> StrandSpec.EMPTY.withBulbs(List.of(b(F, RED), b(F, ORANGE), b(F, GOLD), b(F, GREEN), b(F, BLUE), b(F, PURPLE)));
            case "spooky" -> StrandSpec.EMPTY.withBulbs(List.of(b(BulbStyle.PUMPKIN, -1), b(BulbStyle.GHOST, -1)));
            case "sweetheart" -> StrandSpec.EMPTY.withBulbs(List.of(b(BulbStyle.HEART, PINK), b(BulbStyle.HEART, RED), b(BulbStyle.FLOWER, WHITE)));
            case "sampler" -> {
                List<StrandSpec.Bulb> all = new ArrayList<>();
                for (BulbStyle st : BulbStyle.values()) all.add(b(st, -1));
                yield StrandSpec.EMPTY.withBulbs(all);
            }
            case "garland" -> {
                kind = StrandKind.GARLAND;
                yield StrandSpec.EMPTY;
            }
            case "garland_lit" -> {
                kind = StrandKind.GARLAND;
                yield StrandSpec.EMPTY.withBulbs(List.of(b(F, GOLD), b(H, RED), b(F, GOLD), b(H, WHITE)));
            }
            case "tinsel_red", "tinsel_gold", "tinsel_silver", "tinsel_green", "tinsel_blue" -> {
                kind = StrandKind.TINSEL;
                yield StrandSpec.EMPTY.withColor(switch (name) {
                    case "tinsel_red" -> RED;
                    case "tinsel_gold" -> GOLD;
                    case "tinsel_green" -> GREEN;
                    case "tinsel_blue" -> BLUE;
                    default -> 0xDDE3EA;
                });
            }
            case "bunting_festive" -> {
                kind = StrandKind.BUNTING;
                yield StrandSpec.EMPTY.withFlags(List.of(f(TRI, RED), f(TRI, WHITE), f(TRI, GREEN)));
            }
            case "bunting_noel" -> {
                kind = StrandKind.BUNTING;
                yield StrandSpec.EMPTY.withFlags(List.of(f(SWA, RED), f(SWA, GREEN))).withText("NOEL");
            }
            case "bunting_summer" -> {
                kind = StrandKind.BUNTING;
                yield StrandSpec.EMPTY.withFlags(List.of(f(SQ, 0x3FA7D6), f(SQ, GOLD), f(SQ, 0xF26D5B), f(SQ, 0x7BC67E)));
            }
            case "bare_lights" -> StrandSpec.EMPTY;
            case "bare_bunting" -> {
                kind = StrandKind.BUNTING;
                yield StrandSpec.EMPTY;
            }
            default -> null;
        };
        if (spec == null) return ItemStack.EMPTY;
        ItemStack s = strandItem(kind);
        setSpec(s, spec);
        return s;
    }

    public static final List<String> PRESETS = List.of("holiday", "warm", "twinkle", "candy_cane", "icicles", "winter", "lanterns", "cafe",
            "rainbow", "sweetheart", "spooky", "sampler", "garland", "garland_lit", "tinsel_red", "tinsel_gold", "tinsel_silver", "tinsel_green", "tinsel_blue",
            "bunting_festive", "bunting_noel", "bunting_summer", "bare_lights", "bare_bunting");

    private static List<ItemStack> creativeItems() {
        List<ItemStack> out = new ArrayList<>();
        for (String p : PRESETS) out.add(preset(p));
        out.add(new ItemStack(HOOK_ITEM));
        for (BulbStyle s : BulbStyle.values()) {
            out.add(new ItemStack(BULBS.get(s)));
            if (s == BulbStyle.HOLIDAY) {
                for (int c : new int[]{RED, GREEN, GOLD, BLUE, WHITE}) {
                    ItemStack d = new ItemStack(BULBS.get(s));
                    d.set(DataComponents.DYED_COLOR, new DyedItemColor(c));
                    out.add(d);
                }
            }
        }
        for (StrandSpec.Pennant s : StrandSpec.Pennant.values()) out.add(new ItemStack(PENNANTS.get(s)));
        return out;
    }
}
