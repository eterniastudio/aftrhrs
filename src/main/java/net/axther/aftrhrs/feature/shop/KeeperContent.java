package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

import java.util.EnumMap;
import java.util.Map;

/**
 * The counter keepers' entity types ({@code aftrhrs:keeper_general}, {@code _fishmonger}, {@code _grocer},
 * {@code _miner}) and the items that place them (same ids), in the Civic creative tab next to the counters. Models,
 * textures, item art and recipes come from {@code tools/gen_keepers.py}. Mod init only.
 */
public final class KeeperContent {
    /** Which counter a keeper stands in for. */
    public enum Trade {
        GENERAL("general"), FISHMONGER("fishmonger"), GROCER("grocer"), MINER("miner");

        public final String key;

        Trade(String key) {
            this.key = key;
        }

        public String id() {
            return "keeper_" + key;
        }
    }

    public static final Map<Trade, EntityType<CounterKeeper>> TYPES = new EnumMap<>(Trade.class);
    public static final Map<Trade, Item> ITEMS = new EnumMap<>(Trade.class);
    private static boolean done;

    private KeeperContent() {
    }

    public static synchronized void init() {
        if (done) return;
        done = true;
        for (Trade t : Trade.values()) {
            ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, AftrHrs.id(t.id()));
            EntityType<CounterKeeper> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                    EntityType.Builder.<CounterKeeper>of((ty, level) -> new CounterKeeper(ty, level, t), MobCategory.MISC)
                            .sized(0.6f, 1.95f).eyeHeight(1.62f).clientTrackingRange(10).noLootTable().build(key));
            FabricDefaultAttributeRegistry.register(type, CounterKeeper.attributes());
            TYPES.put(t, type);
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, AftrHrs.id(t.id()));
            ITEMS.put(t, Registry.register(BuiltInRegistries.ITEM, itemKey, new KeeperItem(t, new Item.Properties().setId(itemKey).stacksTo(16))));
        }
        ResourceKey<CreativeModeTab> tab = ArcanaContent.tab("arcana_civic");
        if (tab != null) CreativeModeTabEvents.modifyOutputEvent(tab).register(out -> ITEMS.values().forEach(out::accept));
    }

    /** The trade of a keeper item, or null. */
    public static Trade tradeOf(Item item) {
        return item instanceof KeeperItem k ? k.trade : null;
    }
}
