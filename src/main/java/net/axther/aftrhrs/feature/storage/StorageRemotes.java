package net.axther.aftrhrs.feature.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crafting Terminal Remotes on the server (docs/storage.md, "Terminal remotes"): which remote is linked to which
 * terminal ({@code data/storage-remotes.json}, so it survives restarts and works while the remote sits in an
 * inventory), the reach rules, and keeping every linked network loaded: a chunk ticket ({@code aftrhrs:storage_remote},
 * loading and ticking, keeps its dimension running) on every chunk the network's flood fill touches, refreshed every
 * two seconds, within {@code storage.remote.max-chunks-per-network} / {@code max-chunks-per-player}, and released when
 * no remote is linked any more, the link is forgotten or the terminal is broken. With {@code storage.remote.keep-ticking:
 * false} the ticket only loads ({@code aftrhrs:storage_remote_loading}: the network stays readable, hoppers there
 * stop), and with {@code offline-release-hours} a network whose remote owners are all offline and unused for that
 * long is let go until a remote is used again. The flood fill itself is kept
 * ({@link Scan}): it runs again while the network still grows, when a player places or breaks storage near it, when the
 * remote is used, and otherwise about once a minute.
 */
final class StorageRemotes {
    static TicketType TICKET;
    /** {@code keep-ticking: false}: loaded only (no simulation, so keeping the dimension active would do nothing). */
    static TicketType TICKET_LOADING;
    /** Ticket radius 1: the chunk itself ticks (hoppers keep running), its neighbours are only loaded. */
    static final int RADIUS = 1;

    /** Registers the ticket types (mod init). */
    static void initTicketType() {
        if (TICKET == null) {
            TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, AftrHrs.id("storage_remote"),
                    new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
        }
        if (TICKET_LOADING == null) {
            TICKET_LOADING = Registry.register(BuiltInRegistries.TICKET_TYPE, AftrHrs.id("storage_remote_loading"),
                    new TicketType(0L, TicketType.FLAG_LOADING));
        }
    }

    /** One linked remote. */
    static final class LinkData {
        final String id;
        final String dim;
        final BlockPos pos;
        final UUID owner;
        final String ownerName;
        final long created;
        long seen;

        LinkData(String id, String dim, BlockPos pos, UUID owner, String ownerName, long created, long seen) {
            this.id = id;
            this.dim = dim;
            this.pos = pos;
            this.owner = owner;
            this.ownerName = ownerName;
            this.created = created;
            this.seen = seen;
        }

        String network() {
            return dim + "|" + pos.asLong();
        }
    }

    private final StorageModule module;
    private final Map<String, LinkData> links = new LinkedHashMap<>();
    /** Links whose terminal was broken (id -> when): such a remote says so instead of opening. */
    private final Map<String, Long> dead = new LinkedHashMap<>();
    /** Chunks ticketed per network ("dim|pos"). */
    private final Map<String, Set<Long>> tickets = new HashMap<>();
    /** The ticket type each network's chunks hold (keep-ticking can change on a reload: they are released with this one). */
    private final Map<String, TicketType> ticketTypes = new HashMap<>();
    private final Map<String, Long> capWarned = new HashMap<>();
    /** Each kept-loaded network's last flood fill ("dim|pos"), so a pass only reads networks that may have changed. */
    private final Map<String, Scan> scans = new HashMap<>();
    private boolean dirty;
    private boolean loadedOk = true;

    // config (storage.remote.*)
    boolean enabled = true, keepLoaded = true, keepTicking = true;
    int maxChunksPerNetwork = 16, maxChunksPerPlayer = 48, maxDistance = 0, forgetDays = 30, maxLinksPerPlayer = 4;
    /** 0: off. Otherwise networks whose remote owners are all offline and unused this long get no ticket. */
    double offlineReleaseHours = 0;

    StorageRemotes(StorageModule module) {
        this.module = module;
    }

    void readConfig(Yaml c) {
        enabled = c.getBoolean("remote.enabled", true);
        keepLoaded = c.getBoolean("remote.keep-loaded", true);
        keepTicking = c.getBoolean("remote.keep-ticking", true);
        offlineReleaseHours = Math.max(0, c.getDouble("remote.offline-release-hours", 0));
        maxChunksPerNetwork = Math.max(1, c.getInt("remote.max-chunks-per-network", 16));
        maxChunksPerPlayer = Math.max(1, c.getInt("remote.max-chunks-per-player", 48));
        maxDistance = Math.max(0, c.getInt("remote.max-distance", 0));
        forgetDays = Math.max(1, c.getInt("remote.forget-after-days", 30));
        maxLinksPerPlayer = Math.max(1, c.getInt("remote.max-links-per-player", 4));
    }

    private Text text() {
        return module.text();
    }

    // ------------------------------------------------------------------ file

    private Path file() {
        return module.plugin().dataFile("storage-remotes.json");
    }

    void load() {
        links.clear();
        dead.clear();
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("links")) {
                JsonObject l = e.getAsJsonObject();
                LinkData d = new LinkData(l.get("id").getAsString(), l.get("dim").getAsString(),
                        new BlockPos(l.get("x").getAsInt(), l.get("y").getAsInt(), l.get("z").getAsInt()),
                        UUID.fromString(l.get("owner").getAsString()), l.get("name").getAsString(), l.get("created").getAsLong(), l.get("seen").getAsLong());
                links.put(d.id, d);
            }
            if (o.has("dead")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("dead").entrySet()) dead.put(e.getKey(), e.getValue().getAsLong());
            loadedOk = true;
        } catch (IOException | RuntimeException e) {
            loadedOk = false;
            module.plugin().getLogger().error("data/storage-remotes.json could not be read; terminal remotes keep working but it is not saved over until fixed", e);
        }
    }

    void save() {
        if (!dirty || !loadedOk) return;
        dirty = false;
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (LinkData d : links.values()) {
            JsonObject l = new JsonObject();
            l.addProperty("id", d.id);
            l.addProperty("dim", d.dim);
            l.addProperty("x", d.pos.getX());
            l.addProperty("y", d.pos.getY());
            l.addProperty("z", d.pos.getZ());
            l.addProperty("owner", d.owner.toString());
            l.addProperty("name", d.ownerName);
            l.addProperty("created", d.created);
            l.addProperty("seen", d.seen);
            arr.add(l);
        }
        o.add("links", arr);
        JsonObject deadObj = new JsonObject();
        dead.forEach(deadObj::addProperty);
        o.add("dead", deadObj);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("storage-remotes.json.tmp");
            Files.writeString(tmp, gson.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            dirty = true;
            module.plugin().getLogger().warn("Couldn't save data/storage-remotes.json", e);
        }
    }

    // ------------------------------------------------------------------ linking

    static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "the Overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> dim;
        };
    }

    /** Sneak-right-click on a terminal with a remote: links it (same checks as opening the terminal). */
    void link(ServerPlayer p, ItemStack stack, StorageBlock.Terminal.At at) {
        if (!enabled) {
            text().send(p, "storage.remote.disabled");
            module.error(p);
            return;
        }
        if (!module.mayOpen(p, at)) return;
        TerminalRemoteItem.Link old = TerminalRemoteItem.link(stack);
        // Each player may have at most max-links-per-player links: a new one past that is refused (relinking a remote
        // replaces its own link, so that is always fine).
        int mine = 0;
        for (LinkData x : links.values()) if (x.owner.equals(p.getUUID()) && (old == null || !x.id.equals(old.id()))) mine++;
        if (mine >= maxLinksPerPlayer) {
            text().send(p, "storage.remote.too-many", Text.unparsed("max", maxLinksPerPlayer));
            module.error(p);
            return;
        }
        if (old != null && links.remove(old.id()) != null) releaseUnused();
        String id = UUID.randomUUID().toString();
        String dim = at.level().dimension().identifier().toString();
        long now = System.currentTimeMillis();
        LinkData d = new LinkData(id, dim, at.pos(), p.getUUID(), p.getGameProfile().name(), now, now);
        links.put(id, d);
        dirty = true;
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("dim", dim);
        tag.putInt("x", at.pos().getX());
        tag.putInt("y", at.pos().getY());
        tag.putInt("z", at.pos().getZ());
        tag.putString("by", p.getGameProfile().name());
        Pdc.itemPut(stack, TerminalRemoteItem.KEY, tag);
        List<net.minecraft.network.chat.Component> lore = new ArrayList<>();
        lore.add(net.minecraft.network.chat.Component.translatableWithFallback("item.aftrhrs.feather_terminal_remote.linked",
                        "Linked to %s %s %s in %s", at.pos().getX(), at.pos().getY(), at.pos().getZ(), dimName(dim))
                .withStyle(s -> s.withColor(0x86E8B0).withItalic(false)));
        lore.addAll(StorageContent.remoteLore(TerminalRemoteItem.tierOf(stack)));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        text().send(p, "storage.remote.linked", Text.unparsed("x", at.pos().getX()), Text.unparsed("y", at.pos().getY()),
                Text.unparsed("z", at.pos().getZ()), Text.unparsed("dimension", dimName(dim)));
        module.click(p);
        refresh(d.network());
        save();
    }

    /** A remote in someone's inventory: its link is still in use. */
    void seen(String id) {
        LinkData d = links.get(id);
        if (d == null) return;
        d.seen = System.currentTimeMillis();
        dirty = true;
    }

    /** Why this player can't reach this terminal with a remote of this tier now (a messages.yml key), or null. */
    String reach(ServerPlayer p, StorageBlock.Terminal.At t, int tier) {
        if (!enabled) return "storage.remote.disabled";
        boolean here = p.level().dimension() == Level.OVERWORLD, there = t.level().dimension() == Level.OVERWORLD;
        if (tier < 2 && (!here || !there)) return "storage.remote.overworld-only";
        if (maxDistance > 0 && p.level() == t.level() && t.distanceSqr(p) > (double) maxDistance * maxDistance) return "storage.remote.too-far";
        return null;
    }

    /** Whether the player still carries a remote with this link (main inventory, armor or off hand). */
    static boolean holds(ServerPlayer p, String id) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!(it.getItem() instanceof TerminalRemoteItem)) continue;
            TerminalRemoteItem.Link l = TerminalRemoteItem.link(it);
            if (l != null && l.id().equals(id)) return true;
        }
        return false;
    }

    /** The highest tier among the player's remotes with this link (0: none). */
    static int heldTier(ServerPlayer p, String id) {
        Inventory inv = p.getInventory();
        int best = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!(it.getItem() instanceof TerminalRemoteItem r)) continue;
            TerminalRemoteItem.Link l = TerminalRemoteItem.link(it);
            if (l != null && l.id().equals(id)) best = Math.max(best, r.tier);
        }
        return best;
    }

    /**
     * Right-click with a remote: opens its terminal as a Crafting Terminal, or says why not. Only the link id is read
     * from the item: where the terminal is comes from the server's own record of that link (made by a real link on a
     * real terminal), so an edited item can't point anywhere. Checks that need no chunk come first, and a chunk is
     * never loaded (or generated) here: the linked network is kept loaded by its ticket, or the remote says it isn't
     * loaded yet.
     */
    void use(ServerPlayer p, ItemStack stack) {
        TerminalRemoteItem.Link l = TerminalRemoteItem.link(stack);
        int tier = TerminalRemoteItem.tierOf(stack);
        if (l == null) {
            text().send(p, "storage.remote.unlinked");
            return;
        }
        if (dead.containsKey(l.id())) {
            text().send(p, "storage.remote.broken");
            module.error(p);
            return;
        }
        LinkData d = links.get(l.id());
        if (d == null) {
            // Unknown here (forgotten after a long time unused, the data file lost, or a made-up item): never trusted.
            text().send(p, "storage.remote.unknown");
            module.error(p);
            return;
        }
        MinecraftServer server = module.server();
        Identifier dimId = Identifier.tryParse(d.dim);
        ServerLevel level = server == null || dimId == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null) {
            text().send(p, "storage.remote.gone");
            module.error(p);
            return;
        }
        StorageBlock.Terminal.At probe = new StorageBlock.Terminal.At(level, d.pos, Kind.TERMINAL);
        String why = reach(p, probe, tier);
        if (why != null) {
            text().send(p, why, Text.unparsed("max", maxDistance));
            module.error(p);
            return;
        }
        // Permission and claim trust need no chunk (claims are the server's own data).
        if (!module.mayOpen(p, probe)) return;
        d.seen = System.currentTimeMillis();
        dirty = true;
        if (!level.hasChunk(d.pos.getX() >> 4, d.pos.getZ() >> 4)) {
            // Ticket it within the owner's caps; it loads over the next ticks (an existing chunk: a terminal was there).
            refresh(d.network());
            text().send(p, "storage.remote.not-loaded");
            return;
        }
        if (!(level.getBlockState(d.pos).getBlock() instanceof StorageBlock.Terminal term)) {
            terminalGone(level, d.pos);
            text().send(p, "storage.remote.gone");
            module.error(p);
            return;
        }
        refresh(d.network());
        module.openRemote(p, new StorageBlock.Terminal.At(level, d.pos, term.kind), tier, d.id);
    }

    /** A terminal was broken or replaced: its links die (their remotes say so) and its chunks are let go. */
    void terminalGone(ServerLevel level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        boolean any = false;
        for (LinkData d : new ArrayList<>(links.values())) {
            if (d.dim.equals(dim) && d.pos.equals(pos)) {
                links.remove(d.id);
                dead.put(d.id, System.currentTimeMillis());
                any = true;
            }
        }
        if (!any) return;
        while (dead.size() > 10_000) dead.remove(dead.keySet().iterator().next());
        dirty = true;
        release(dim + "|" + pos.asLong());
        save();
    }

    // ------------------------------------------------------------------ keeping networks loaded

    /** A settled network is read again at least this often (a backstop for changes no hook sees: pistons, commands). */
    static final long RESCAN_MS = 60_000;

    /** One network's last flood fill: the loaded chunks it touched, in scan order (without home, which is always held). */
    static final class Scan {
        final String dim;
        final long home;
        List<Long> chunks;
        long nextScan;
        /** Reads in a row that found the same chunks: after two alike it waits {@link #RESCAN_MS}. */
        int sameCount;
        /** A storage block near it came or went (or a remote was used): read it again on the next pass. */
        boolean dirty;
        /** Flood fills run for it (game tests check the cache with it). */
        int runs;

        Scan(String dim, long home) {
            this.dim = dim;
            this.home = home;
        }

        boolean near(long chunk) {
            int x = ChunkPos.unpack(chunk).x(), z = ChunkPos.unpack(chunk).z();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long c = ChunkPos.pack(x + dx, z + dz);
                    if (c == home || (chunks != null && chunks.contains(c))) return true;
                }
            }
            return false;
        }
    }

    /**
     * A storage block, terminal or connector was placed or broken by a player: every kept-loaded network in that
     * dimension with a chunk at or next to it is read again on the next pass (a new chest on a chunk border can reach
     * into a new chunk).
     */
    void blockChanged(ServerLevel level, BlockPos pos) {
        if (scans.isEmpty()) return;
        String dim = level.dimension().identifier().toString();
        long chunk = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        for (Scan s : scans.values()) if (s.dim.equals(dim) && s.near(chunk)) s.dirty = true;
    }

    /** Every two seconds: forget long-unused links, then (re)ticket every linked network's chunks. */
    void tick() {
        long now = System.currentTimeMillis();
        long forget = forgetDays * 86_400_000L;
        boolean removed = links.values().removeIf(d -> now - d.seen > forget);
        if (removed) dirty = true;
        releaseUnused();
        if (!keepLoaded) {
            for (String key : new ArrayList<>(tickets.keySet())) release(key);
        } else {
            // Networks in order of their oldest link; each player's chunks count against their own cap (one shared
            // budget per owner for the whole pass). A network whose owner has no budget left gets no ticket at all.
            Map<UUID, Integer> used = new HashMap<>();
            for (Map.Entry<String, LinkData> e : firstLinks().entrySet()) {
                LinkData d = e.getValue();
                if (idleOffline(e.getKey(), now)) {
                    release(e.getKey());
                    continue;
                }
                int left = maxChunksPerPlayer - used.getOrDefault(d.owner, 0);
                if (left <= 0) {
                    if (tickets.containsKey(e.getKey())) warnCap(e.getKey(), d, 0);
                    release(e.getKey());
                    continue;
                }
                int got = ensure(e.getKey(), d, Math.min(maxChunksPerNetwork, left));
                used.merge(d.owner, got, Integer::sum);
            }
        }
        save();
    }

    /**
     * With {@code offline-release-hours} set: every remote linked to this network belongs to an offline player and none
     * was carried or used within that time. (Using one tickets it again; until then it says "not loaded".)
     */
    boolean idleOffline(String key, long now) {
        if (offlineReleaseHours <= 0) return false;
        MinecraftServer server = module.server();
        if (server == null || server.getPlayerList() == null) return false;
        long window = (long) (offlineReleaseHours * 3_600_000L);
        for (LinkData d : links.values()) {
            if (!d.network().equals(key)) continue;
            if (server.getPlayerList().getPlayer(d.owner) != null || now - d.seen < window) return false;
        }
        return true;
    }

    /** The ticket type new tickets get now (storage.remote.keep-ticking). */
    private TicketType ticketType() {
        return keepTicking || TICKET_LOADING == null ? TICKET : TICKET_LOADING;
    }

    /** Each linked network with its oldest link (whose owner's budget it counts against), oldest first. */
    private Map<String, LinkData> firstLinks() {
        Map<String, LinkData> first = new LinkedHashMap<>();
        List<LinkData> sorted = new ArrayList<>(links.values());
        sorted.sort(Comparator.comparingLong(d -> d.created));
        for (LinkData d : sorted) first.putIfAbsent(d.network(), d);
        return first;
    }

    /**
     * Re-tickets one network now (after linking or using a remote), within its owner's budget: what the owner's
     * other networks hold now counts first, the same as in {@link #tick}.
     */
    private void refresh(String key) {
        if (!keepLoaded) return;
        // Linked or used just now: read the network again (the player may expect what they just built to be held).
        Scan scan = scans.get(key);
        if (scan != null) scan.dirty = true;
        Map<String, LinkData> first = firstLinks();
        LinkData d = first.get(key);
        if (d == null) return;
        int used = 0;
        for (Map.Entry<String, LinkData> e : first.entrySet()) {
            if (!e.getKey().equals(key) && e.getValue().owner.equals(d.owner)) used += tickets.getOrDefault(e.getKey(), Set.of()).size();
        }
        int left = maxChunksPerPlayer - used;
        if (left <= 0) {
            warnCap(key, d, 0);
            release(key);
            return;
        }
        ensure(key, d, Math.min(maxChunksPerNetwork, left));
    }

    /** Tickets the network's chunks (at most {@code cap}); returns how many it holds. */
    private int ensure(String key, LinkData d, int cap) {
        MinecraftServer server = module.server();
        Identifier dimId = Identifier.tryParse(d.dim);
        ServerLevel level = server == null || dimId == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null) return 0;
        TicketType type = ticketType();
        TicketType had = ticketTypes.get(key);
        // keep-ticking changed: the old tickets go (with their own type), the new ones come below.
        if (had != null && had != type) release(key);
        long home = ChunkPos.pack(d.pos.getX() >> 4, d.pos.getZ() >> 4);
        Set<Long> want = new LinkedHashSet<>();
        want.add(home);
        Scan scan = scans.computeIfAbsent(key, k -> new Scan(d.dim, home));
        if (level.hasChunk(d.pos.getX() >> 4, d.pos.getZ() >> 4)) {
            if (!(level.getBlockState(d.pos).getBlock() instanceof StorageBlock.Terminal term)) {
                terminalGone(level, d.pos);
                return 0;
            }
            long now = System.currentTimeMillis();
            if (scan.chunks == null || scan.dirty || now >= scan.nextScan) {
                Set<Long> touched = new LinkedHashSet<>();
                // Reading the network also starts watching its storage for the "who changed what" log.
                Network.scan(module, null, new StorageBlock.Terminal.At(level, d.pos, term.kind), touched);
                scan.runs++;
                List<Long> chunks = new ArrayList<>();
                for (long c : touched) {
                    // Only chunks that are loaded now: a ticket on a chunk nobody has made yet would generate it. (The
                    // terminal's own ticket loads its neighbours, so a network reaches further on the next pass.)
                    if (c != home && level.hasChunk(ChunkPos.unpack(c).x(), ChunkPos.unpack(c).z())) chunks.add(c);
                }
                // Still growing (into chunks its own tickets just loaded) or just changed: read again next pass;
                // the same twice in a row: settled, read again only after a while (staggered across networks).
                scan.sameCount = chunks.equals(scan.chunks) ? scan.sameCount + 1 : 0;
                scan.nextScan = now + (scan.sameCount >= 1 ? RESCAN_MS + (key.hashCode() & 0x3FFF) : 0);
                scan.chunks = chunks;
                scan.dirty = false;
            }
            // The cap every pass: the owner's budget changes with their other networks.
            for (long c : scan.chunks) {
                if (!level.hasChunk(ChunkPos.unpack(c).x(), ChunkPos.unpack(c).z())) continue;
                if (want.size() >= cap) {
                    warnCap(key, d, cap);
                    break;
                }
                want.add(c);
            }
        } else {
            // Home not loaded yet (its ticket loads it): read the network once it is.
            scan.dirty = true;
        }
        Set<Long> have = tickets.computeIfAbsent(key, k -> new HashSet<>());
        ticketTypes.put(key, type);
        for (long c : want) if (have.add(c)) level.getChunkSource().addTicketWithRadius(type, ChunkPos.unpack(c), RADIUS);
        for (long c : new ArrayList<>(have)) {
            if (!want.contains(c)) {
                have.remove(c);
                level.getChunkSource().removeTicketWithRadius(type, ChunkPos.unpack(c), RADIUS);
            }
        }
        return have.size();
    }

    private void warnCap(String key, LinkData d, int cap) {
        long now = System.currentTimeMillis();
        Long last = capWarned.get(key);
        if (last != null && now - last < 600_000) return;
        capWarned.put(key, now);
        module.plugin().getLogger().warn("Storage remote: the network at {} in {} (linked by {}) hit its chunk cap ({} kept loaded); the rest "
                + "is loaded only while someone is near (storage.remote.max-chunks-per-network / max-chunks-per-player)",
                d.pos.toShortString(), d.dim, d.ownerName, cap);
    }

    /** Releases the tickets of networks no remote is linked to any more. */
    private void releaseUnused() {
        Set<String> linked = new HashSet<>();
        for (LinkData d : links.values()) linked.add(d.network());
        for (String key : new ArrayList<>(tickets.keySet())) if (!linked.contains(key)) release(key);
    }

    private void release(String key) {
        scans.remove(key);
        Set<Long> have = tickets.remove(key);
        TicketType type = ticketTypes.remove(key);
        if (type == null) type = TICKET;
        if (have == null || have.isEmpty()) return;
        String dim = key.substring(0, key.indexOf('|'));
        MinecraftServer server = module.server();
        Identifier dimId = Identifier.tryParse(dim);
        ServerLevel level = server == null || dimId == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null) return;
        for (long c : have) level.getChunkSource().removeTicketWithRadius(type, ChunkPos.unpack(c), RADIUS);
    }

    /** Module stop / reload: every ticket goes, the file is written. */
    void stop() {
        for (String key : new ArrayList<>(tickets.keySet())) release(key);
        scans.clear();
        save();
    }

    // ------------------------------------------------------------------ for tests and /storage info

    /** The chunks held for the network of this terminal. */
    Set<Long> ticketed(ServerLevel level, BlockPos pos) {
        return Set.copyOf(tickets.getOrDefault(level.dimension().identifier().toString() + "|" + pos.asLong(), Set.of()));
    }

    /** How many times the remotes have read the network of this terminal (0: not kept loaded). */
    int scansOf(ServerLevel level, BlockPos pos) {
        Scan s = scans.get(level.dimension().identifier().toString() + "|" + pos.asLong());
        return s == null ? 0 : s.runs;
    }

    int linkCount() {
        return links.size();
    }

    /** The ticket type the network of this terminal holds (null: none). */
    TicketType ticketTypeOf(ServerLevel level, BlockPos pos) {
        return ticketTypes.get(level.dimension().identifier().toString() + "|" + pos.asLong());
    }

    boolean isDead(String id) {
        return dead.containsKey(id);
    }
}
