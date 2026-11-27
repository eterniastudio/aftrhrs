package net.axther.aftrhrs.migrate;

import net.axther.aftrhrs.AftrHrs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Paper's {@code ItemStack#serializeAsBytes()} format (gzipped NBT of the item with a {@code DataVersion}), which Feather
 * kept in PDC byte arrays ({@code feather:curio_item}, jar contents ...). Reading runs the vanilla data fixer from the
 * stored version and the {@link LegacyItems} conversion (Oraxen ids become native items) through the item codec.
 */
public final class PaperItems {
    private PaperItems() {
    }

    /** The item in Paper's bytes, or null (empty, unreadable). */
    public static @Nullable ItemStack fromBytes(MinecraftServer server, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(4L << 20));
            return fromTag(server, tag);
        } catch (IOException | RuntimeException e) {
            AftrHrs.LOG.warn("Unreadable Paper item bytes ({} bytes): {}", bytes.length, e.toString());
            return null;
        }
    }

    /** An item compound with an optional DataVersion (fixed up to the current version). */
    public static @Nullable ItemStack fromTag(MinecraftServer server, CompoundTag tag) {
        int version = tag.getIntOr("DataVersion", -1);
        CompoundTag t = tag.copy();
        t.remove("DataVersion");
        int current = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
        if (version > 0 && version < current) {
            t = (CompoundTag) server.getFixerUpper().update(net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                    new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE, t), version, current).getValue();
        }
        ItemStack s = ItemStack.CODEC.parse(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), t)
                .resultOrPartial(err -> AftrHrs.LOG.warn("Paper item: {}", err)).orElse(null);
        return s == null || s.isEmpty() ? null : s;
    }

    /** The same format back (gzipped NBT with DataVersion); used where a data file keeps Paper's bytes. */
    public static byte[] toBytes(MinecraftServer server, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new byte[0];
        try {
            Tag tag = ItemStack.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
            if (!(tag instanceof CompoundTag c)) return new byte[0];
            c.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(c, out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            AftrHrs.LOG.warn("Item not encodable: {}", e.toString());
            return new byte[0];
        }
    }
}
