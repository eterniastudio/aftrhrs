package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.migrate.Legacy;
import net.axther.aftrhrs.migrate.LegacyItems;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The three storage blocks as real blocks and items ({@code aftrhrs:feather_storage_terminal},
 * {@code aftrhrs:feather_crafting_terminal}, {@code aftrhrs:feather_inventory_connector}; assets and recipes from
 * {@code mod/tools/gen_storage.py}) plus everything that must exist before a world loads: the recipe load condition
 * and the Paper migration (old storage items, chunk records, leftover skin displays). Registered at mod init whether
 * or not {@code modules.storage} is on, so placed blocks and items always survive.
 */
public final class StorageContent {
    static final Map<Kind, StorageBlock> BLOCKS = new EnumMap<>(Kind.class);
    static final Map<Kind, Item> ITEMS = new EnumMap<>(Kind.class);
    /** The Crafting Terminal Remotes: tier I (Overworld) and tier II (Ender, any dimension). */
    static Item REMOTE, REMOTE_ENDER;
    /** The Storage Vault ({@code aftrhrs:storage_vault}): an upright multiblock, 20 stacks of each kind of item per block. */
    public static StorageVaultBlock VAULT;
    public static Item VAULT_ITEM;
    public static net.minecraft.world.level.block.entity.BlockEntityType<StorageVaultBE> VAULT_BE;
    static final String VAULT_ID = "storage_vault";
    private static boolean done;

    private StorageContent() {
    }

    // English names and lore (messages.yml storage.items.*, palette colors): the client has no messages.yml, so the
    // item carries them as translatable text with these fallbacks.
    private static final int SKY = 0x8FD3FF, ACCENT = 0xC3A6FF, GOOD = 0x86E8B0, MUTED = 0xA49DC2, DIM = 0x6B6487;

    static Component line(String key, String english, int color) {
        MutableComponent c = Component.translatableWithFallback(key, english);
        return c.withStyle(s -> s.withColor(color).withItalic(false));
    }

    private static List<Component> lore(Kind k) {
        String[][] lines = switch (k) {
            case TERMINAL -> new String[][]{{"Place it next to a chest, barrel, vault or shulker box:", "m"},
                    {"all the storage touching it shows up as one.", "m"}, {"Right-click to open · Inventory Connectors reach further", "d"}};
            case CRAFTING -> new String[][]{{"A Storage Terminal with Quick Craft:", "m"}, {"craft straight from your storage.", "m"},
                    {"Right-click to open", "d"}};
            case CONNECTOR -> new String[][]{{"Joins any chest, barrel, vault or shulker box touching it", "m"},
                    {"to the network of a terminal nearby.", "m"}, {"Connectors next to each other link up", "d"}};
        };
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            out.add(line("item.aftrhrs." + k.block + ".lore." + i, lines[i][0], lines[i][1].equals("m") ? MUTED : DIM));
        }
        return out;
    }

    /** A remote's own lore (the link line comes first once it is linked). */
    static List<Component> remoteLore(int tier) {
        List<Component> out = new ArrayList<>();
        String id = tier == 2 ? "feather_terminal_remote_ender" : "feather_terminal_remote";
        out.add(line("item.aftrhrs." + id + ".lore.0", tier == 2 ? "Tier II: opens a linked terminal from any dimension." : "Tier I: opens a linked terminal anywhere in the Overworld.", MUTED));
        out.add(line("item.aftrhrs." + id + ".lore.1", "Keeps the linked storage loaded and live.", MUTED));
        out.add(line("item.aftrhrs." + id + ".lore.2", "Sneak-right-click a terminal to link · Right-click to open", DIM));
        return out;
    }

    static Component name(Kind k) {
        return switch (k) {
            case TERMINAL -> line("item.aftrhrs." + k.block, "Storage Terminal", SKY);
            case CRAFTING -> line("item.aftrhrs." + k.block, "Crafting Terminal", ACCENT);
            case CONNECTOR -> line("item.aftrhrs." + k.block, "Inventory Connector", GOOD);
        };
    }

    private static void registerVault() {
        ResourceKey<Block> bk = ResourceKey.create(Registries.BLOCK, AftrHrs.id(VAULT_ID));
        // Iron-hard; pistons can't move a vault (its blocks would lose track of each other).
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(bk).mapColor(MapColor.METAL).sound(SoundType.NETHERITE_BLOCK)
                .strength(3f, 6f).requiresCorrectToolForDrops().pushReaction(PushReaction.IMMOVEABLE);
        VAULT = Registry.register(BuiltInRegistries.BLOCK, bk, new StorageVaultBlock(p));
        VAULT_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id(VAULT_ID),
                net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(StorageVaultBE::new, VAULT).build());
        ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, AftrHrs.id(VAULT_ID));
        List<Component> lore = List.of(
                line("item.aftrhrs." + VAULT_ID + ".lore.0", "Holds 20 stacks of every kind of item, any number of kinds.", MUTED),
                line("item.aftrhrs." + VAULT_ID + ".lore.1", "Stack vaults into one: up to 3x3 across, as tall as you like.", MUTED),
                line("item.aftrhrs." + VAULT_ID + ".lore.2", "No window: hoppers, comparators and terminals reach it", DIM));
        Component name = line("item.aftrhrs." + VAULT_ID, "Storage Vault", SKY);
        VAULT_ITEM = Registry.register(BuiltInRegistries.ITEM, ik, new BlockItem(VAULT, new Item.Properties().setId(ik)
                .component(DataComponents.LORE, new ItemLore(lore))) {
            @Override
            public Component getName(ItemStack stack) {
                return name;
            }
        });
        Item.BY_BLOCK.put(VAULT, VAULT_ITEM);
    }

    /** Registers blocks, items, the creative tab entries, the recipe condition and the migration. Mod init only. */
    public static synchronized void init() {
        if (done) return;
        done = true;
        for (Kind k : Kind.values()) {
            ResourceKey<Block> bk = ResourceKey.create(Registries.BLOCK, AftrHrs.id(k.block));
            BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(bk)
                    .mapColor(k == Kind.TERMINAL ? MapColor.COLOR_BLACK : MapColor.TERRACOTTA_GRAY)
                    .sound(k == Kind.TERMINAL ? SoundType.POLISHED_DEEPSLATE : SoundType.TUFF_BRICKS)
                    // Explosions, pistons and fluids never touched a storage block on Paper either.
                    .strength(1.5f, 1200f).pushReaction(PushReaction.IMMOVEABLE);
            StorageBlock block = Registry.register(BuiltInRegistries.BLOCK, bk,
                    k.terminal() ? new StorageBlock.Terminal(k, p) : new StorageBlock(k, p));
            BLOCKS.put(k, block);
            ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, AftrHrs.id(k.block));
            Item.Properties ip = new Item.Properties().setId(ik).component(DataComponents.LORE, new ItemLore(lore(k)));
            Component name = name(k);
            Item item = Registry.register(BuiltInRegistries.ITEM, ik, new BlockItem(block, ip) {
                @Override
                public Component getName(ItemStack stack) {
                    return name;
                }
            });
            Item.BY_BLOCK.put(block, item);
            ITEMS.put(k, item);
        }
        for (int tier = 1; tier <= 2; tier++) {
            ResourceKey<Item> rk = ResourceKey.create(Registries.ITEM, AftrHrs.id(tier == 2 ? "feather_terminal_remote_ender" : "feather_terminal_remote"));
            Item.Properties ip = new Item.Properties().setId(rk).stacksTo(1).component(DataComponents.LORE, new ItemLore(remoteLore(tier)));
            if (tier == 2) ip = ip.rarity(net.minecraft.world.item.Rarity.RARE);
            Item remote = Registry.register(BuiltInRegistries.ITEM, rk, new TerminalRemoteItem(tier, ip));
            if (tier == 2) REMOTE_ENDER = remote;
            else REMOTE = remote;
        }
        registerVault();
        StorageLog.init();
        StorageRemotes.initTicketType();
        ResourceKey<CreativeModeTab> tab = ArcanaContent.tab("arcana_utility");
        if (tab != null) {
            CreativeModeTabEvents.modifyOutputEvent(tab).register(out -> {
                for (Kind k : Kind.values()) out.accept(ITEMS.get(k));
                out.accept(REMOTE);
                out.accept(REMOTE_ENDER);
                out.accept(VAULT_ITEM);
            });
        }
        FeatherConfigCondition.register();

        // ---- Paper migration
        // Storage items were a vanilla block item tagged feather:storage_item=<kind>.
        LegacyItems.tagged(StorageItems.TAG, v -> {
            Kind k = Kind.parse(v);
            return k == null ? null : k.block;
        });
        // Skin displays were never saved, but an older Feather or a crash may have left one.
        Legacy.converter((level, entity, bukkit) -> {
            if (!bukkit.contains(StorageMigration.DISPLAY_KEY) && !Pdc.has(entity, StorageMigration.DISPLAY_KEY)) return false;
            entity.discard();
            return true;
        });
        ServerChunkEvents.CHUNK_LOAD.register(StorageMigration::onChunkLoad);
    }
}
