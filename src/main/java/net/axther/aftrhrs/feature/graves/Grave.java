package net.axther.aftrhrs.feature.graves;

import com.mojang.serialization.Dynamic;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One grave: who it belongs to, where its block stands, the block it replaced, its clock (locked until
 * {@link #unlockAt}, spilled at {@link #expireAt}), the dropped XP and the items, each with the inventory slot it came
 * from (-1 when unknown). Items are stored as Paper's {@code serializeAsBytes}: gzipped NBT with a {@code DataVersion},
 * so graves made on the Paper server load (upgraded by the data fixers) and new ones stay readable the same way.
 */
final class Grave {
    /**
     * One stored item and the player-inventory slot it came from (0-35 storage, 36-39 armor, 40 offhand, 41 the
     * backpack worn on the back, -1 unknown). The item's saved form is kept until the item changes, so saving
     * graves.yml (on every take, death and expiry) doesn't re-encode and gzip every item of every grave: every change
     * goes through {@link #set}, which drops it.
     */
    static final class Entry {
        final int slot;
        private ItemStack item;
        /** The Base64 of {@link Grave#serialize}, or null until the next save works it out. */
        private String data;

        Entry(int slot, ItemStack item) {
            this(slot, item, null);
        }

        /** As loaded: {@code data} is the stored text the item was read from (null to encode it again). */
        Entry(int slot, ItemStack item, String data) {
            this.slot = slot;
            this.item = item;
            this.data = data;
        }

        ItemStack item() {
            return item;
        }

        /** Replaces the stack (null when it has been taken); the saved form is worked out again on the next save. */
        void set(ItemStack s) {
            item = s;
            data = null;
        }

        /** The item's saved form, or null when there is no item. */
        String data() {
            if (data == null && item != null && !item.isEmpty()) data = Base64.getEncoder().encodeToString(serialize(item));
            return data;
        }
    }

    /** Block position key ({@code world} as Paper named it). */
    record Pos(String world, int x, int y, int z) {
        static Pos of(ServerLevel level, BlockPos b) {
            return new Pos(Worlds.name(level), b.getX(), b.getY(), b.getZ());
        }
    }

    final String id;
    final UUID owner;
    String ownerName;
    final String world;
    final int x, y, z;
    /** The block state that was here before (restored when the grave goes), as a block state string. */
    final String original;
    /** The head's rotation (a Bukkit BlockFace name, as Paper saved it). */
    String rotation = "SOUTH";
    String skinValue;
    String skinSignature;
    final long created;
    /** When the owner died (matches their death point). */
    final long deathTime;
    long unlockAt;
    long expireAt;
    int xp;
    boolean unlockNotified;
    final List<Entry> items = new ArrayList<>();

    // not saved
    UUID display;
    boolean removed;
    boolean warnedMissing;

    Grave(String id, UUID owner, String ownerName, String world, int x, int y, int z, String original, long created, long deathTime) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.original = original;
        this.created = created;
        this.deathTime = deathTime;
    }

    Pos pos() {
        return new Pos(world, x, y, z);
    }

    BlockPos blockPos() {
        return new BlockPos(x, y, z);
    }

    ServerLevel level() {
        return Worlds.resolve(world);
    }

    /** The center of the block, or null when the world isn't loaded. */
    Loc center() {
        ServerLevel w = level();
        return w == null ? null : new Loc(w, x + 0.5, y + 0.5, z + 0.5);
    }

    boolean chunkLoaded() {
        ServerLevel w = level();
        return w != null && w.hasChunk(x >> 4, z >> 4);
    }

    boolean locked(long now) {
        return now < unlockAt;
    }

    String coords() {
        return x + " " + y + " " + z;
    }

    /** Stacks still inside. */
    int stacks() {
        int n = 0;
        for (Entry e : items) if (e.item() != null && !e.item().isEmpty()) n++;
        return n;
    }

    boolean empty() {
        return stacks() == 0 && xp <= 0;
    }

    // ------------------------------------------------------------------ saving

    void save(Yaml s) {
        s.set("owner", owner.toString());
        s.set("name", ownerName);
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("original", original);
        s.set("rotation", rotation);
        if (skinValue != null) {
            s.set("skin.value", skinValue);
            s.set("skin.signature", skinSignature);
        }
        s.set("created", created);
        s.set("death", deathTime);
        s.set("unlock", unlockAt);
        s.set("expire", expireAt);
        s.set("xp", xp);
        s.set("unlock-notified", unlockNotified);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : items) {
            if (e.item() == null || e.item().isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", e.slot);
            m.put("data", e.data());
            list.add(m);
        }
        s.set("items", list);
    }

    /** Reads one grave; throws on anything malformed (the caller logs it and keeps the raw section). */
    static Grave load(String id, Yaml s) {
        String ownerRaw = s.getString("owner");
        if (ownerRaw == null) throw new IllegalArgumentException("no owner");
        Grave g = new Grave(id, UUID.fromString(ownerRaw), s.getString("name", "?"), s.getString("world"),
                s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getString("original", "minecraft:air"),
                s.getLong("created"), s.getLong("death"));
        g.rotation = s.getString("rotation", "SOUTH");
        g.skinValue = s.getString("skin.value");
        g.skinSignature = s.getString("skin.signature");
        g.unlockAt = s.getLong("unlock");
        g.expireAt = s.getLong("expire");
        g.xp = Math.max(0, s.getInt("xp"));
        g.unlockNotified = s.getBoolean("unlock-notified");
        if (g.world == null) throw new IllegalArgumentException("no world");
        for (Map<?, ?> m : s.getMapList("items")) {
            Object slot = m.get("slot");
            Object data = m.get("data");
            if (data == null) continue;
            String raw = String.valueOf(data);
            boolean[] upgraded = new boolean[1];
            ItemStack item = deserialize(Base64.getDecoder().decode(raw), upgraded);
            // The stored text is written back as it was; one saved by an older game version is encoded again once.
            g.items.add(new Entry(slot instanceof Number n ? n.intValue() : -1, item, upgraded[0] ? null : raw));
        }
        return g;
    }

    // ------------------------------------------------------------------ Paper's item bytes

    private static HolderLookup.Provider registries() {
        return Feather.server().registryAccess();
    }

    private static int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** Paper's {@code ItemStack.serializeAsBytes}: the item codec's NBT plus {@code DataVersion}, gzipped. */
    static byte[] serialize(ItemStack item) {
        Tag tag = ItemStack.CODEC.encodeStart(registries().createSerializationContext(NbtOps.INSTANCE), item).getOrThrow();
        CompoundTag c = tag instanceof CompoundTag ct ? ct : new CompoundTag();
        c.putInt("DataVersion", dataVersion());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(c, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Reads {@link #serialize}'s bytes (or Paper's), running the data fixers from the version they were saved with. */
    static ItemStack deserialize(byte[] bytes) {
        return deserialize(bytes, null);
    }

    /** {@link #deserialize(byte[])}; sets {@code upgraded[0]} when the bytes came from an older game version. */
    static ItemStack deserialize(byte[] bytes, boolean[] upgraded) {
        CompoundTag c;
        try {
            c = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable item bytes: " + e.getMessage(), e);
        }
        int from = c.getIntOr("DataVersion", dataVersion());
        c.remove("DataVersion");
        int to = dataVersion();
        Tag fixed = c;
        if (from < to) {
            if (upgraded != null) upgraded[0] = true;
            fixed = DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, (Tag) c), from, to).getValue();
        }
        return ItemStack.CODEC.parse(registries().createSerializationContext(NbtOps.INSTANCE), fixed)
                .getOrThrow(msg -> new IllegalArgumentException("bad item: " + msg));
    }
}
