package net.axther.aftrhrs.feature.mail;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.migrate.PaperItemBytes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mail data in {@code data/mail.yml}: every player's letters and waiting gifts, gifts that are being sent (drafts:
 * the items already left the sender's chest GUI but the sender hasn't confirmed yet) and the mailbox blocks.
 *
 * <p>Saved through {@link SafeYaml} (temp file + atomic move, the previous file kept as {@code .bak}): synchronously on
 * every change that moves items or sends a letter ({@link #save}), so a gift's items are on disk before they leave
 * anyone; letters read or deleted only {@link #markDirty mark} the store, and the mail tick writes it in the background
 * ({@link #saveIfDirtyAsync}). A file that can't be read closes the mail
 * ({@link #unreadable}) and is never overwritten. Gift items are Paper's {@code serializeItemsAsBytes} in base64
 * ({@link PaperItemBytes}), the same as on the Paper server, so old gifts load (and convert) unchanged. Gifts and
 * pick-ups are also appended to {@code data/mail-gifts.log} for staff.
 */
final class MailStore {
    /** A mailbox block; {@code world} is the Paper-style world name ("world", "world_nether", ...). */
    record Pos(String world, int x, int y, int z) {
        static Pos of(Level level, BlockPos pos) {
            return new Pos(Worlds.name(level), pos.getX(), pos.getY(), pos.getZ());
        }

        ServerLevel level() {
            return Worlds.resolve(world);
        }

        BlockPos blockPos() {
            return new BlockPos(x, y, z);
        }

        String pretty() {
            return x + ", " + y + ", " + z + " (" + world + ")";
        }

        String key() {
            return world + "," + x + "," + y + "," + z;
        }

        static Pos parse(String s) {
            String[] p = s.split(",");
            if (p.length != 4) return null;
            try {
                return new Pos(p[0], Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** A letter. {@code fromDisplay} is the sender's display name at the time (JSON), for when they are offline. */
    static final class Letter {
        final long id;
        final UUID from;
        final String fromName;
        final String fromDisplay;
        final String text;
        final long time;
        boolean read;

        Letter(long id, UUID from, String fromName, String fromDisplay, String text, long time, boolean read) {
            this.id = id;
            this.from = from;
            this.fromName = fromName;
            this.fromDisplay = fromDisplay;
            this.text = text;
            this.time = time;
            this.read = read;
        }
    }

    /**
     * A gift waiting at the mailbox. {@code returned}: it came back to its sender (unclaimed, or an unsent gift that
     * didn't fit back into their inventory); then {@code from} is whoever it was meant for (or the owner themselves).
     */
    static final class Gift {
        final long id;
        final UUID from;
        final String fromName;
        final String fromDisplay;
        final String note;
        final long time;
        String items;
        final boolean returned;

        Gift(long id, UUID from, String fromName, String fromDisplay, String note, long time, String items, boolean returned) {
            this.id = id;
            this.from = from;
            this.fromName = fromName;
            this.fromDisplay = fromDisplay;
            this.note = note;
            this.time = time;
            this.items = items;
            this.returned = returned;
        }
    }

    /** A gift being sent: its items are out of the sender's hands and wait here for the confirm click. */
    record Draft(long id, UUID owner, UUID to, String toName, String items, long time) {
    }

    /** One player's letters (oldest first) and waiting gifts (oldest first). */
    static final class Box {
        final List<Letter> letters = new ArrayList<>();
        final List<Gift> gifts = new ArrayList<>();

        int unread() {
            int n = 0;
            for (Letter l : letters) if (!l.read) n++;
            return n;
        }
    }

    private final Feather plugin;
    private final Path file;
    private final Path logFile;
    final Map<UUID, Box> boxes = new HashMap<>();
    final Map<Long, Draft> drafts = new LinkedHashMap<>();
    final Set<Pos> mailboxes = new LinkedHashSet<>();
    private long nextId = 1;
    /** The file exists but couldn't be read: mail is closed and nothing is saved over it. */
    boolean unreadable;
    /** Something changed that only {@link #saveIfDirtyAsync} still has to write (letters read or deleted). */
    private boolean dirty;
    /** A background save failed: the next {@link #saveIfDirtyAsync} tries again. Set on the data thread. */
    private volatile boolean failed;
    /** Saves (sync or handed to the data thread) in this JVM; game tests count them. */
    int saves;
    static final String HEADER = "# Feather mail: letters, waiting gifts (items as base64) and mailboxes.\n"
            + "# Don't edit while the server runs; a parse error closes the mail until the file is fixed (mail.yml.bak holds the previous save).\n";

    MailStore(Feather plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("mail.yml");
        this.logFile = plugin.dataFile("mail-gifts.log");
    }

    long nextId() {
        return nextId++;
    }

    Box box(UUID id) {
        return boxes.computeIfAbsent(id, k -> new Box());
    }

    Box find(UUID id) {
        return boxes.get(id);
    }

    // ------------------------------------------------------------------ items

    static String encode(List<ItemStack> items) {
        return PaperItemBytes.encodeBase64(items);
    }

    /** The stacks (no empty ones); an empty list for nothing, null when the data can't be read. */
    static List<ItemStack> decode(String data) {
        List<ItemStack> out = new ArrayList<>();
        if (data == null || data.isEmpty()) return out;
        try {
            for (ItemStack s : PaperItemBytes.decodeBase64(data)) {
                if (s != null && !s.isEmpty()) out.add(s);
            }
            return out;
        } catch (RuntimeException e) {
            Feather.get().getLogger().warn("Mail: could not read gift items: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ disk

    void load() {
        boxes.clear();
        drafts.clear();
        mailboxes.clear();
        nextId = 1;
        dirty = false;
        failed = false;
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "letters, gifts and mailboxes");
        if (yml == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        nextId = Math.max(1, yml.getLong("next-id", 1));
        for (String s : yml.getStringList("mailboxes")) {
            Pos p = Pos.parse(s);
            if (p != null) mailboxes.add(p);
        }
        Yaml players = yml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID owner = uuid(key);
                if (owner == null) continue;
                Box box = box(owner);
                for (Map<?, ?> m : players.getMapList(key + ".letters")) {
                    try {
                        box.letters.add(new Letter(num(m.get("id")), uuid(m.get("from")), str(m.get("from-name")), str(m.get("from-display")),
                                str(m.get("text")), num(m.get("time")), Boolean.TRUE.equals(m.get("read"))));
                    } catch (RuntimeException ignored) {
                        // a broken entry is dropped, the rest loads
                    }
                }
                for (Map<?, ?> m : players.getMapList(key + ".gifts")) {
                    try {
                        box.gifts.add(new Gift(num(m.get("id")), uuid(m.get("from")), str(m.get("from-name")), str(m.get("from-display")),
                                str(m.get("note")), num(m.get("time")), str(m.get("items")), Boolean.TRUE.equals(m.get("returned"))));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        for (Map<?, ?> m : yml.getMapList("drafts")) {
            UUID owner = uuid(m.get("owner"));
            if (owner == null) continue;
            Draft d = new Draft(num(m.get("id")), owner, uuid(m.get("to")), str(m.get("to-name")), str(m.get("items")), num(m.get("time")));
            drafts.put(d.id(), d);
        }
        // Ids must stay unique even if next-id was lost or edited.
        long max = 0;
        for (Box b : boxes.values()) {
            for (Letter l : b.letters) max = Math.max(max, l.id);
            for (Gift g : b.gifts) max = Math.max(max, g.id);
        }
        for (Draft d : drafts.values()) max = Math.max(max, d.id());
        nextId = Math.max(nextId, max + 1);
    }

    /** A change that needs no write-before-act (a letter read or deleted): written by the next {@link #saveIfDirtyAsync}. */
    void markDirty() {
        dirty = true;
    }

    /**
     * Writes everything now and waits for it (items move, letters are sent). False (and nothing written) while the file
     * is unreadable or when the write fails; a failed write leaves the store dirty.
     */
    boolean save() {
        if (unreadable) return false;
        saves++;
        boolean ok = SafeYaml.writeSync(file, HEADER, snapshot(), plugin.getLogger());
        if (ok) {
            dirty = false;
            failed = false;
        }
        return ok;
    }

    /** Snapshots now and writes in the background when something changed since the last save (the mail tick, quits, disable). */
    void saveIfDirtyAsync() {
        if (unreadable || (!dirty && !failed)) return;
        dirty = false;
        failed = false;
        saves++;
        SafeYaml.writeAsync(file, HEADER, snapshot(), plugin.getLogger()).thenAccept(ok -> {
            if (!ok) failed = true;
        });
    }

    /** The whole file as plain maps, taken on the server thread. */
    private Object snapshot() {
        Yaml yml = new Yaml();
        yml.set("next-id", nextId);
        yml.set("mailboxes", mailboxes.stream().map(Pos::key).toList());
        for (Map.Entry<UUID, Box> e : boxes.entrySet()) {
            Box b = e.getValue();
            if (b.letters.isEmpty() && b.gifts.isEmpty()) continue;
            String base = "players." + e.getKey();
            if (!b.letters.isEmpty()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Letter l : b.letters) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", l.id);
                    if (l.from != null) m.put("from", l.from.toString());
                    m.put("from-name", l.fromName);
                    if (l.fromDisplay != null) m.put("from-display", l.fromDisplay);
                    m.put("text", l.text);
                    m.put("time", l.time);
                    m.put("read", l.read);
                    list.add(m);
                }
                yml.set(base + ".letters", list);
            }
            if (!b.gifts.isEmpty()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Gift g : b.gifts) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", g.id);
                    if (g.from != null) m.put("from", g.from.toString());
                    m.put("from-name", g.fromName);
                    if (g.fromDisplay != null) m.put("from-display", g.fromDisplay);
                    if (g.note != null && !g.note.isEmpty()) m.put("note", g.note);
                    m.put("time", g.time);
                    if (g.returned) m.put("returned", true);
                    m.put("items", g.items);
                    list.add(m);
                }
                yml.set(base + ".gifts", list);
            }
        }
        if (!drafts.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Draft d : drafts.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.id());
                m.put("owner", d.owner().toString());
                if (d.to() != null) m.put("to", d.to().toString());
                m.put("to-name", d.toName());
                m.put("time", d.time());
                m.put("items", d.items());
                list.add(m);
            }
            yml.set("drafts", list);
        }
        // A detached copy (gift item strings are shared with the live gifts, but immutable): dumped and written on the
        // data thread, in order with every other save.
        return yml.snapshot();
    }

    /** Appends one line to data/mail-gifts.log. */
    void log(String line) {
        String entry = Instant.now() + " " + line.replace('\n', ' ') + System.lineSeparator();
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warn("Could not write {}: {}", logFile, e.toString());
        }
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        try {
            return UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
