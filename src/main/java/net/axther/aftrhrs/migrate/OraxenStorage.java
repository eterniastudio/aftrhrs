package net.axther.aftrhrs.migrate;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.content.FurnitureSpec;
import net.axther.aftrhrs.content.FurnitureStorageBlockEntity;
import net.axther.aftrhrs.core.Pdc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Oraxen storage contents from Paper. Oraxen kept them as {@code oraxen:storage} (STORAGE / SHULKER furniture: in the
 * base entity's PDC, and in a broken sack's item) and {@code oraxen:personal_storage} (PERSONAL: on the player), both a
 * {@code BukkitObjectOutputStream} ItemStack[] (read with {@link JavaSer}, never loading a class). ENDERCHEST pieces
 * used the vanilla ender chest and DISPOSAL kept nothing, so neither has anything to move.
 * <ul>
 *   <li>placed pieces: into the native piece's {@code aftrhrs:furniture_storage} block entity, slot for slot; stacks
 *       beyond its size (or for a piece without storage) drop at the piece;</li>
 *   <li>players: {@code oraxen:personal_storage} becomes {@code aftrhrs:personal_storage} (same slots);</li>
 *   <li>sack items carried over with {@code oraxen:storage} in their custom data fill the sack when it is placed.</li>
 * </ul>
 * Unreadable data is kept, never deleted: in the block entity's Pdc as {@code aftrhrs:legacy_storage} (logged with the
 * position) or left in the player's / item's data.
 */
public final class OraxenStorage {
    public static final String KEY = "oraxen:storage";
    public static final String PERSONAL = "oraxen:personal_storage";
    private static final String PERSONAL_NEW = "aftrhrs:personal_storage";
    public static final String KEPT = "aftrhrs:legacy_storage";
    /** Set by {@link LegacyItems} on items it converted that carried Oraxen storage contents (sacks). */
    public static final String MIGRATED = "aftrhrs:oraxen_storage_migrated";

    private OraxenStorage() {
    }

    static void init() {
        Legacy.onFurniture(OraxenStorage::onFurniture);
        Legacy.converter(OraxenStorage::onPlayer);
        ArcanaContent.hook(id -> {
            FurnitureSpec s = ArcanaContent.spec(id);
            return s != null && s.storage != null;
        }, new FurnitureHook() {
            @Override
            public void onPlaced(FurnitureContext ctx) {
                placedWithLegacy(ctx);
            }
        });
    }

    /** The stacks in Oraxen's ItemStack[] bytes (nulls for empty slots); null if unreadable. */
    public static @Nullable List<ItemStack> decode(MinecraftServer server, byte[] bytes) {
        if (bytes == null || bytes.length > JavaSer.MAX_BYTES) {
            AftrHrs.LOG.warn("Oraxen storage data too large ({} bytes); not decoded", bytes == null ? 0 : bytes.length);
            return null;
        }
        try {
            List<Object> all = JavaSer.read(bytes);
            List<ItemStack> out = new ArrayList<>();
            boolean first = true;
            for (Object o : all) {
                if (first && o instanceof byte[]) { // the array length (writeInt)
                    first = false;
                    continue;
                }
                first = false;
                if (o instanceof byte[]) continue;
                Object p = JavaSer.plain(o);
                if (p == null) {
                    out.add(null);
                    continue;
                }
                if (!(p instanceof Map<?, ?> m)) throw new IllegalStateException("not an item");
                out.add(item(server, m));
            }
            return out;
        } catch (Throwable e) { // incl. StackOverflowError: hostile or broken data never takes the server down
            if (e instanceof VirtualMachineError && !(e instanceof StackOverflowError)) throw (VirtualMachineError) e;
            AftrHrs.LOG.warn("Oraxen storage data unreadable ({}: {})", e.getClass().getSimpleName(), JavaSer.describe(e.getMessage()));
            return null;
        }
    }

    /** One Bukkit ItemStack map: Paper's {DataVersion, id, count, components{id: SNBT}} or the old {v, type, amount, meta}. */
    static @Nullable ItemStack item(MinecraftServer server, Map<?, ?> m) throws Exception {
        if (m.get("bytes") instanceof List<?> bl) { // a raw serializeAsBytes form
            byte[] b = new byte[bl.size()];
            for (int i = 0; i < b.length; i++) b[i] = ((Number) bl.get(i)).byteValue();
            return PaperItems.fromBytes(server, b);
        }
        CompoundTag t = new CompoundTag();
        if (m.get("id") instanceof String id) {
            t.putString("id", id);
            t.putInt("count", m.get("count") instanceof Number n ? n.intValue() : 1);
            if (m.get("components") instanceof Map<?, ?> comps && !comps.isEmpty()) {
                CompoundTag c = new CompoundTag();
                TagParser<Tag> parser = TagParser.create(NbtOps.INSTANCE);
                for (Map.Entry<?, ?> e : comps.entrySet()) {
                    if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String snbt)) continue;
                    try {
                        c.put(k, parser.parseFully(snbt));
                    } catch (Exception ex) {
                        AftrHrs.LOG.warn("Oraxen storage: item {} component {} unreadable; dropped that component", JavaSer.describe(id), JavaSer.describe(k));
                    }
                }
                t.put("components", c);
            }
            if (m.get("DataVersion") instanceof Number dv) t.putInt("DataVersion", dv.intValue());
        } else if (m.get("type") instanceof String type) {
            t.putString("id", "minecraft:" + type.toLowerCase(Locale.ROOT));
            t.putInt("count", m.get("amount") instanceof Number n ? n.intValue() : 1);
            if (m.get("v") instanceof Number dv) t.putInt("DataVersion", dv.intValue());
            if (m.containsKey("meta")) AftrHrs.LOG.warn("Oraxen storage: old-format {} keeps its type and count; its item meta is not carried over", JavaSer.describe(type));
        } else {
            throw new IllegalStateException("unknown item map");
        }
        return PaperItems.fromTag(server, t);
    }

    // ------------------------------------------------------------------ placed pieces

    private static void onFurniture(ServerLevel level, BlockPos origin, String id, CompoundTag bv, float yaw) {
        byte[] bytes = bv.getByteArray(KEY).orElse(null);
        if (bytes == null || bytes.length == 0) return;
        BlockEntity be = level.getBlockEntity(origin);
        List<ItemStack> items = decode(level.getServer(), bytes);
        if (items == null) {
            String where;
            if (be != null) {
                Pdc.putTag(be, KEPT, new ByteArrayTag(bytes));
                be.setChanged();
                where = " in its block entity (" + KEPT + ")";
            } else {
                // the old entity goes right after this: keep the bytes in a file so an admin can still recover them
                where = " in " + keepFile(level, origin, id, bytes);
            }
            AftrHrs.LOG.warn("Oraxen storage of {} at {} {} could not be read; raw data kept{}", id, level.dimension().identifier(),
                    origin.toShortString(), where);
            return;
        }
        fill(level, origin, be instanceof FurnitureStorageBlockEntity s ? s : null, items);
    }

    /** Writes unreadable storage bytes to {@code aftrhrs/data/legacy-storage/}; the file name, or "NOWHERE" on failure. */
    private static String keepFile(ServerLevel level, BlockPos origin, String id, byte[] bytes) {
        try {
            net.axther.aftrhrs.core.Feather f = net.axther.aftrhrs.core.Feather.get();
            java.nio.file.Path dir = f != null ? f.dataFile("legacy-storage")
                    : net.axther.aftrhrs.core.Feather.get().getDataFolder().resolve("data").resolve("legacy-storage");
            java.nio.file.Files.createDirectories(dir);
            String name = (level.dimension().identifier().toString() + "_" + origin.getX() + "_" + origin.getY() + "_" + origin.getZ()
                    + "_" + id).replaceAll("[^A-Za-z0-9_.-]", "_") + ".bin";
            java.nio.file.Path file = dir.resolve(name);
            java.nio.file.Files.write(file, bytes);
            return file.toString();
        } catch (Exception e) {
            AftrHrs.LOG.warn("Could not keep unreadable Oraxen storage bytes: {}", e.toString());
            return "NOWHERE";
        }
    }

    private static void fill(ServerLevel level, BlockPos origin, @Nullable FurnitureStorageBlockEntity be, List<ItemStack> items) {
        int size = be == null ? 0 : be.getContainerSize();
        List<ItemStack> extra = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s == null || s.isEmpty()) continue;
            if (i < size && be.getItem(i).isEmpty()) be.setItem(i, s);
            else extra.add(s);
        }
        if (be != null) be.setChanged();
        for (ItemStack s : extra) Containers.dropItemStack(level, origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5, s);
        if (!extra.isEmpty()) AftrHrs.LOG.info("Oraxen storage at {}: {} stacks did not fit and dropped", origin.toShortString(), extra.size());
    }

    /** A sack (or any storage piece) placed from an item still carrying Oraxen's contents. */
    private static void placedWithLegacy(FurnitureContext ctx) {
        ItemStack held = ctx.held();
        if (held == null || held.isEmpty()) return;
        CustomData cd = held.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return;
        CompoundTag tag = cd.copyTag();
        // only what the legacy item migration produced (it moves oraxen:storage here); never arbitrary custom data
        byte[] bytes = tag.getByteArray(MIGRATED).orElse(null);
        if (bytes == null || bytes.length == 0) return;
        if (bytes.length > JavaSer.MAX_BYTES) {
            AftrHrs.LOG.warn("Oversized Oraxen storage on a placed {} at {}; ignored", ctx.id(), ctx.origin().toShortString());
            return;
        }
        BlockEntity be = ctx.level().getBlockEntity(ctx.origin());
        List<ItemStack> items = decode(ctx.level().getServer(), bytes);
        if (items == null) {
            if (be != null) {
                Pdc.putTag(be, KEPT, new ByteArrayTag(bytes));
                be.setChanged();
            }
            AftrHrs.LOG.warn("Oraxen storage in a placed {} at {} could not be read; raw data kept", ctx.id(), ctx.origin().toShortString());
            return;
        }
        fill(ctx.level(), ctx.origin(), be instanceof FurnitureStorageBlockEntity s ? s : null, items);
    }

    // ------------------------------------------------------------------ personal storage (on the player)

    private static boolean onPlayer(ServerLevel level, Entity e, CompoundTag bv) {
        if (!(e instanceof ServerPlayer p)) return false;
        byte[] bytes = bv.getByteArray(PERSONAL).orElse(null);
        if (bytes == null || bytes.length == 0) return false;
        if (Pdc.of(p).contains(PERSONAL_NEW)) {
            Pdc.remove(p, PERSONAL);
            return false;
        }
        List<ItemStack> items = decode(level.getServer(), bytes);
        if (items == null) {
            AftrHrs.LOG.warn("Personal Oraxen storage of {} could not be read; kept as {} in their data", p.getGameProfile().name(), PERSONAL);
            return false;
        }
        var ops = p.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ListTag list = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s == null || s.isEmpty()) continue;
            int slot = i;
            ItemStack.CODEC.encodeStart(ops, s).result().ifPresent(t -> {
                CompoundTag ct = new CompoundTag();
                ct.putInt("Slot", slot);
                ct.put("Item", t);
                list.add(ct);
            });
        }
        Pdc.putTag(p, PERSONAL_NEW, list);
        Pdc.remove(p, PERSONAL);
        AftrHrs.LOG.info("Moved {} personal storage stacks of {} from Oraxen", list.size(), p.getGameProfile().name());
        return false; // other converters may want the player too
    }
}
