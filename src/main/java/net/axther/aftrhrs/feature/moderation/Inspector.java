package net.axther.aftrhrs.feature.moderation;

import com.mojang.serialization.Dynamic;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Names;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What staff can see of players who aren't online. On every quit, every {@code moderation.snapshot-minutes} and on
 * shutdown, each online player's inventory (with armor and offhand) and ender chest are saved into their profile,
 * along with where they were and the address they joined from. /invsee and /ec on an offline player open the last
 * snapshot read-only.
 *
 * <p>Snapshot format: the one Paper's {@code ItemStack.serializeItemsAsBytes} wrote (see {@link #encode}), so the live
 * server's snapshots read as they are (each item run through the data fixers from its own data version, and Oraxen
 * items become native ones as they load) and new snapshots look the same. Slots follow Bukkit's order: 0-35 storage and hotbar, 36-39 boots..helmet, 40 offhand.
 */
public final class Inspector {
    private static volatile Inspector active;
    /** Runs the quit snapshot before Profiles' own quit save (default phase), which then writes it once. */
    private static final Identifier BEFORE_PROFILE_SAVE = Identifier.fromNamespaceAndPath("aftrhrs", "inspector_before_profile_save");

    private final Feather plugin;
    private final ModerationModule mod;
    private Tasks.Task autosave;
    /** How old a snapshot may get before the autosave takes a new one (moderation.snapshot-minutes). */
    private long periodMs;
    private long periodTicks;
    /** Where the next autosave step goes on in the online list (taken modulo its size, so joins and quits are harmless). */
    private int cursor;
    /**
     * What the last snapshot of each online player held: a slot-ordered hash of the inventory and of the ender chest.
     * A part whose hash didn't change keeps its encoded text.
     */
    private final Map<UUID, long[]> fingerprints = new HashMap<>();

    Inspector(Feather plugin, ModerationModule mod) {
        this.plugin = plugin;
        this.mod = mod;
    }

    void enable() {
        active = this;
        reload();
        plugin.once("inspector.events", () -> {
            ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
                Inspector i = active;
                if (i != null) i.plugin.profiles().get(h.player).lastIp = h.player.getIpAddress();
            });
            ServerPlayConnectionEvents.DISCONNECT.addPhaseOrdering(BEFORE_PROFILE_SAVE, Event.DEFAULT_PHASE);
            ServerPlayConnectionEvents.DISCONNECT.register(BEFORE_PROFILE_SAVE, (h, server) -> {
                Inspector i = active;
                if (i == null) return;
                // A fresh snapshot into the profile; Profiles' quit handler (later phase) saves it, once.
                i.snapshot(h.player, i.plugin.profiles().get(h.player), true);
                i.fingerprints.remove(h.player.getUUID());
            });
        });
    }

    void reload() {
        if (autosave != null) autosave.cancel();
        long minutes = plugin.getConfig().getLong("moderation.snapshot-minutes", 5);
        autosave = null;
        if (minutes > 0) {
            periodMs = minutes * 60_000L;
            periodTicks = minutes * 60L * 20L;
            // A few players each second instead of everyone in one tick every period.
            autosave = Tasks.timer(20L, 20L, this::autosaveStep);
        }
    }

    /**
     * One autosave step (every second): snapshots and saves the next players whose snapshot is a period old, about
     * {@code online / period-in-seconds} of them, so everyone is covered once a period.
     */
    void autosaveStep() {
        List<ServerPlayer> online = plugin.onlinePlayers();
        int n = online.size();
        if (n == 0 || periodTicks <= 0) return;
        int budget = (int) Math.max(1, (n * 20L + periodTicks - 1) / periodTicks);
        long now = System.currentTimeMillis();
        int start = Math.floorMod(cursor, n);
        int done = 0, scanned = 0;
        while (scanned < n && done < budget) {
            ServerPlayer p = online.get((start + scanned) % n);
            scanned++;
            Profiles.Profile pr = plugin.profiles().get(p);
            if (pr.snapshotTime > 0 && now - pr.snapshotTime < periodMs) continue;
            snapshot(p, pr, false);
            plugin.profiles().save(pr);
            done++;
        }
        cursor = start + scanned;
    }

    /** Takes a last snapshot of everyone (Feather saves the profiles right after the modules stop). */
    void disable() {
        if (active == this) active = null;
        if (autosave != null) autosave.cancel();
        for (ServerPlayer p : plugin.onlinePlayers()) snapshot(p, plugin.profiles().get(p), true);
        fingerprints.clear();
    }

    /** {@link #snapshot(ServerPlayer, Profiles.Profile, boolean)}, encoding everything. */
    void snapshot(ServerPlayer p, Profiles.Profile pr) {
        snapshot(p, pr, true);
    }

    /**
     * Copies the player's items, location and address into their profile (not saved here). Unless {@code force}, a
     * part (inventory, ender chest) whose items didn't change since the last snapshot isn't encoded again.
     */
    void snapshot(ServerPlayer p, Profiles.Profile pr, boolean force) {
        try {
            RegistryAccess ra = p.level().registryAccess();
            ItemStack[] inv = new ItemStack[41];
            for (int i = 0; i < 41; i++) inv[i] = p.getInventory().getItem(i);
            ItemStack[] ender = contents(p.getEnderChestInventory());
            long[] last = fingerprints.get(p.getUUID());
            long invHash = fingerprint(inv), enderHash = fingerprint(ender);
            if (force || last == null || last[0] != invHash || pr.snapshotInventory == null) pr.snapshotInventory = encode(inv, ra);
            if (force || last == null || last[1] != enderHash || pr.snapshotEnder == null) pr.snapshotEnder = encode(ender, ra);
            fingerprints.put(p.getUUID(), new long[]{invHash, enderHash});
            pr.snapshotTime = System.currentTimeMillis();
        } catch (RuntimeException | IOException e) {
            plugin.getLogger().warn("Could not snapshot {}'s inventory: {}", Names.name(p), e.toString());
        }
        pr.lastLocation = Profiles.Home.of("last", p);
        String ip = p.getIpAddress();
        if (ip != null && !ip.isEmpty()) pr.lastIp = ip;
    }

    /** A hash of the items in slot order, components included (enchants, names, shulker contents) and counts. */
    static long fingerprint(ItemStack[] items) {
        long h = 1;
        for (ItemStack s : items) {
            long v = s == null || s.isEmpty() ? 0 : ((long) ItemStack.hashItemAndComponents(s) << 8) ^ s.getCount();
            h = h * 1_000_003L + v;
        }
        return h;
    }

    private static ItemStack[] contents(Container c) {
        ItemStack[] out = new ItemStack[c.getContainerSize()];
        for (int i = 0; i < out.length; i++) out[i] = c.getItem(i);
        return out;
    }

    // ------------------------------------------------------------------ the format

    /**
     * Paper's {@code ItemStack.serializeItemsAsBytes}, base64: a version byte (1), the slot count, then per slot an int
     * length (0 = empty) and that many bytes of the item as gzipped NBT with its {@code DataVersion}
     * ({@code serializeAsBytes}).
     */
    public static String encode(ItemStack[] items, RegistryAccess ra) throws IOException {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, ra);
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(1);
        out.writeInt(items.length);
        for (ItemStack it : items) {
            Tag tag = it == null || it.isEmpty() ? null : ItemStack.CODEC.encodeStart(ops, it).result().orElse(null);
            if (!(tag instanceof CompoundTag item)) {
                out.writeInt(0);
                continue;
            }
            item.putInt("DataVersion", version);
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            NbtIo.writeCompressed(item, one);
            out.writeInt(one.size());
            out.write(one.toByteArray());
        }
        out.flush();
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** Reads a snapshot written by Paper or by {@link #encode}; empty when unreadable. */
    public static ItemStack[] decode(String data, MinecraftServer server) {
        if (data == null || data.isEmpty()) return new ItemStack[0];
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
            int format = in.readByte();
            if (format != 1) throw new IOException("unknown snapshot format " + format);
            int count = in.readInt();
            if (count < 0 || count > 4096) throw new IOException("bad slot count " + count);
            int now = SharedConstants.getCurrentVersion().dataVersion().version();
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
            ItemStack[] out = new ItemStack[count];
            for (int i = 0; i < count; i++) {
                out[i] = ItemStack.EMPTY;
                int len = in.readInt();
                if (len <= 0) continue;
                byte[] one = new byte[len];
                in.readFully(one);
                CompoundTag item = NbtIo.readCompressed(new ByteArrayInputStream(one), NbtAccounter.unlimitedHeap());
                int version = item.getIntOr("DataVersion", now);
                item.remove("DataVersion");
                if (version < now) {
                    Tag fixed = server.getFixerUpper().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, (Tag) item), version, now).getValue();
                    if (fixed instanceof CompoundTag c) item = c;
                }
                out[i] = ItemStack.CODEC.parse(ops, item).result().orElse(ItemStack.EMPTY);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            Feather f = Feather.get();
            if (f != null) f.getLogger().warn("Could not read an inventory snapshot: {}", e.toString());
            return new ItemStack[0];
        }
    }

    // ------------------------------------------------------------------ the read-only view

    /** Opens the last snapshot of an offline player's inventory or ender chest. Every slot is locked. */
    public void open(ServerPlayer viewer, OfflinePlayer target, String name, boolean ender) {
        Text text = plugin.text();
        Profiles.Profile pr = plugin.profiles().load(target.id());
        if (pr.snapshotTime <= 0) {
            text.send(viewer, "mod.snapshot-none", Text.unparsed("name", name));
            return;
        }
        String date = mod.date(pr.snapshotTime, viewer);
        Gui gui;
        if (ender) {
            ItemStack[] c = decode(pr.snapshotEnder, Feather.server());
            gui = Gui.create(3, text.msg("mod.snapshot-title-ender", Text.unparsed("name", name), Text.unparsed("date", date)));
            for (int i = 0; i < Math.min(27, c.length); i++) gui.set(i, at(c, i));
        } else {
            ItemStack[] c = decode(pr.snapshotInventory, Feather.server());
            gui = Gui.create(5, text.msg("mod.snapshot-title-inventory", Text.unparsed("name", name), Text.unparsed("date", date)));
            // Row 1: armor (helmet..boots) and the offhand; rows 2-4 the main inventory; row 5 the hotbar.
            gui.set(0, label(Items.STAINED_GLASS_PANE.white(), "mod.snapshot-armor"));
            for (int i = 0; i < 4; i++) {
                ItemStack piece = at(c, 39 - i);
                gui.set(1 + i, !piece.isEmpty() ? piece : label(Items.STAINED_GLASS_PANE.lightGray(), "mod.snapshot-empty"));
            }
            ItemStack filler = label(Items.STAINED_GLASS_PANE.gray(), "mod.snapshot-taken", Text.unparsed("date", date));
            gui.set(5, filler);
            gui.set(6, filler);
            gui.set(7, label(Items.STAINED_GLASS_PANE.white(), "mod.snapshot-offhand"));
            ItemStack off = at(c, 40);
            gui.set(8, !off.isEmpty() ? off : label(Items.STAINED_GLASS_PANE.lightGray(), "mod.snapshot-empty"));
            for (int i = 9; i < 36; i++) gui.set(i, at(c, i));
            for (int i = 0; i < 9; i++) gui.set(36 + i, at(c, i));
        }
        gui.open(viewer);
        text.send(viewer, "mod.snapshot-opened", Text.unparsed("name", name), Text.unparsed("date", date));
        text.sound(viewer, "menu-open");
    }

    private static ItemStack at(ItemStack[] c, int i) {
        if (i >= c.length || c[i] == null || c[i].isEmpty()) return ItemStack.EMPTY;
        return c[i].copy();
    }

    private ItemStack label(Item item, String key, TagResolver... tags) {
        return Icons.icon(item).name(plugin.text().itemMsg(key, tags)).flags().build();
    }
}
