package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.migrate.PaperItemBytes;
import com.mojang.serialization.Dynamic;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Everything the shop used to ask Oraxen ({@code ShopOraxen}): ex-Oraxen items are native items {@code aftrhrs:<id>},
 * ex-Oraxen furniture (counters, market stalls) are native blocks with the same ids. Items in shop.yml are looked up
 * when used, so an id the content port doesn't have (yet) is just not offered. Stall stock keeps Paper's item format:
 * base64 of gzipped NBT with a DataVersion ({@code ItemStack#serializeAsBytes}), so the live data loads unchanged.
 */
final class ShopItems {
    private static final Map<Class<?>, Method> ORIGIN = new ConcurrentHashMap<>();
    private static final Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private ShopItems() {
    }

    /** The item an id names: {@code minecraft:<id>} or a namespaced id as is, a bare id as {@code aftrhrs:<id>}; else null. */
    static Item item(String id) {
        if (id == null || id.isBlank()) return null;
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("oraxen:")) s = s.substring(7);
        Identifier key = s.contains(":") ? Identifier.tryParse(s) : AftrHrs.id(s);
        if (key == null) return null;
        return BuiltInRegistries.ITEM.getOptional(key).filter(i -> i != Items.AIR).orElse(null);
    }

    /** One fresh item, or null. */
    static ItemStack make(String id) {
        Item it = item(id);
        return it == null ? null : new ItemStack(it);
    }

    /** The shop id of an item: the path of an {@code aftrhrs:} item (the old Oraxen id), else null. */
    static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return AftrHrs.ID.equals(key.getNamespace()) ? key.getPath() : null;
    }

    // ------------------------------------------------------------------ furniture blocks

    /** The ex-Oraxen id of a native content block ({@code aftrhrs:<id>}), else null. */
    static String blockId(BlockState state) {
        if (state == null || state.isAir()) return null;
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return AftrHrs.ID.equals(key.getNamespace()) ? key.getPath() : null;
    }

    /**
     * The origin block (part 0) of the piece {@code pos} belongs to. Multi-block furniture blocks expose
     * {@code BlockPos origin(BlockState, BlockPos)}; looked up by name so the shop doesn't depend on the content package.
     */
    static BlockPos origin(BlockState state, BlockPos pos) {
        Block b = state.getBlock();
        Method m = ORIGIN.computeIfAbsent(b.getClass(), c -> {
            try {
                Method found = c.getMethod("origin", BlockState.class, BlockPos.class);
                return found.getReturnType() == BlockPos.class ? found : NONE;
            } catch (NoSuchMethodException e) {
                return NONE;
            }
        });
        if (m == NONE) return pos;
        try {
            Object o = m.invoke(b, state, pos);
            return o instanceof BlockPos p ? p : pos;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return pos;
        }
    }

    /** A content block found near a player: its origin and id. */
    record Spot(ServerLevel level, BlockPos pos, String id) implements SellBack.Booth {
        /** The piece is still there. */
        @Override
        public boolean valid() {
            return level.isLoaded(pos) && id.equals(blockId(level.getBlockState(pos)));
        }

        @Override
        public net.axther.aftrhrs.core.Loc at() {
            return net.axther.aftrhrs.core.Loc.of(level, pos);
        }

        double distanceSquared(double x, double y, double z) {
            double dx = pos.getX() + 0.5 - x, dy = pos.getY() - y, dz = pos.getZ() + 0.5 - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** The nearest content block within {@code radius} whose id passes {@code accept} (its origin), or null. */
    static Spot nearest(ServerLevel level, double x, double y, double z, double radius, Predicate<String> accept) {
        int r = (int) Math.ceil(radius);
        BlockPos c = BlockPos.containing(x, y, z);
        Spot best = null;
        double bestD = radius * radius;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!level.isLoaded(m)) continue;
                    BlockState st = level.getBlockState(m);
                    String id = blockId(st);
                    if (id == null || !accept.test(id)) continue;
                    Spot s = new Spot(level, origin(st, m.immutable()), id);
                    double d = s.distanceSquared(x, y, z);
                    if (d <= bestD) {
                        bestD = d;
                        best = s;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Paper's item bytes

    static String encode(ItemStack item) {
        if (Feather.serverOrNull() == null || item == null || item.isEmpty()) return "";
        try {
            return PaperItemBytes.encodeItemBase64(item);
        } catch (RuntimeException e) {
            Feather.get().getLogger().warn("Shop: could not encode {}", item, e);
            return "";
        }
    }

    /** An item saved by {@link #encode} or by Paper; null when it can't be read. Old Oraxen items convert on the way. */
    static ItemStack decode(String data) {
        if (data == null || data.isEmpty() || Feather.serverOrNull() == null) return null;
        try {
            ItemStack s = PaperItemBytes.decodeItemBase64(data);
            return s.isEmpty() ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
