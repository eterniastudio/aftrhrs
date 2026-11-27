package net.axther.aftrhrs.migrate;

import com.mojang.serialization.Dynamic;
import net.axther.aftrhrs.core.Feather;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Paper's {@code ItemStack.serializeItemsAsBytes} / {@code deserializeItemsFromBytes} (Feather stored item lists that
 * way, base64'd, in mail gifts, inventory snapshots ...). Format: one version byte (1), an int count, then per item an
 * int length (0 = empty) and that many bytes of gzip NBT: the {@code ItemStack.CODEC} encoding plus {@code DataVersion}.
 *
 * <p>Reading runs older items through the vanilla data fixer, and parsing builds the stacks through the ItemStack
 * constructor, so Paper's {@code PublicBukkitValues} / Oraxen items convert like everywhere else ({@link LegacyItems}).
 * Writing produces the same format, so data stays readable by both the mod and an old Paper server.
 *
 * <p>Single items ({@code ItemStack.serializeAsBytes} / {@code deserializeBytes}: bounties, stall stock) are the same
 * gzip NBT without the list header: {@link #encodeItem} / {@link #decodeItem}.
 */
public final class PaperItemBytes {
    private PaperItemBytes() {
    }

    private static RegistryAccess registries() {
        MinecraftServer s = Feather.serverOrNull();
        if (s == null) throw new IllegalStateException("no server");
        return s.registryAccess();
    }

    /** Base64 of the Paper byte format. Empty stacks are kept as empty entries. */
    public static String encodeBase64(List<ItemStack> items) {
        return Base64.getEncoder().encodeToString(encode(items));
    }

    public static byte[] encode(List<ItemStack> items) {
        RegistryOps<Tag> ops = registries().createSerializationContext(NbtOps.INSTANCE);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(1);
            out.writeInt(items.size());
            for (ItemStack s : items) {
                if (s == null || s.isEmpty()) {
                    out.writeInt(0);
                    continue;
                }
                CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(ops, s).getOrThrow();
                NbtUtils.addCurrentDataVersion(tag);
                ByteArrayOutputStream one = new ByteArrayOutputStream();
                NbtIo.writeCompressed(tag, one);
                byte[] b = one.toByteArray();
                out.writeInt(b.length);
                out.write(b);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Error while writing itemstack", e);
        }
    }

    /** Every entry (empty ones as {@link ItemStack#EMPTY}); throws when the data can't be read. */
    public static List<ItemStack> decodeBase64(String data) {
        return decode(Base64.getDecoder().decode(data));
    }

    public static List<ItemStack> decode(byte[] data) {
        RegistryOps<Tag> ops = registries().createSerializationContext(NbtOps.INSTANCE);
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        List<ItemStack> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte version = in.readByte();
            if (version != 1) throw new IllegalArgumentException("Unsupported item list version " + version);
            int count = in.readInt();
            if (count < 0 || count > 100_000) throw new IllegalArgumentException("Bad item count " + count);
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                if (len == 0) {
                    out.add(ItemStack.EMPTY);
                    continue;
                }
                if (len < 0 || len > data.length) throw new IllegalArgumentException("Bad item length " + len);
                byte[] b = new byte[len];
                in.readFully(b);
                out.add(item(NbtIo.readCompressed(new ByteArrayInputStream(b), NbtAccounter.unlimitedHeap()), ops, current));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException("Error while reading itemstack", e);
        }
    }

    /** One item, Paper's {@code serializeAsBytes}: gzip NBT ({@code ItemStack.CODEC} plus {@code DataVersion}). */
    public static byte[] encodeItem(ItemStack item) {
        RegistryOps<Tag> ops = registries().createSerializationContext(NbtOps.INSTANCE);
        try {
            CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
            NbtUtils.addCurrentDataVersion(tag);
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, one);
            return one.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Error while writing itemstack", e);
        }
    }

    /** One item, Paper's {@code deserializeBytes}; throws when the data can't be read. */
    public static ItemStack decodeItem(byte[] data) {
        RegistryOps<Tag> ops = registries().createSerializationContext(NbtOps.INSTANCE);
        try {
            return item(NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap()), ops,
                    SharedConstants.getCurrentVersion().dataVersion().version());
        } catch (IOException e) {
            throw new IllegalArgumentException("Error while reading itemstack", e);
        }
    }

    public static String encodeItemBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(encodeItem(item));
    }

    public static ItemStack decodeItemBase64(String data) {
        return decodeItem(Base64.getDecoder().decode(data));
    }

    private static ItemStack item(CompoundTag tag, RegistryOps<Tag> ops, int current) {
        int from = NbtUtils.getDataVersion(tag, current);
        tag.remove("DataVersion");
        Tag fixed = tag;
        if (from < current) {
            MinecraftServer s = Feather.serverOrNull();
            if (s != null) fixed = s.getFixerUpper().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, (Tag) tag), from, current).getValue();
        }
        return ItemStack.CODEC.parse(ops, fixed).getOrThrow();
    }
}
