package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The Bestiary (part of the creature codex through {@link Hooks.Bestiary}; {@code /bestiary} opens the codex's
 * Wildlife tab, whose "Bestiary pages" button opens {@link #open}): per player, every
 * wild creature of the update is Seen (within {@code bestiary.sight-radius} blocks and in line of sight, checked every
 * {@code tick.sight-seconds}), Harvested (a product taken from it) and Slain. Pages group the creatures by biome family;
 * finishing a page (every creature seen, and harvested or slain) pays its {@code reward} in Goob Coins into the bank
 * balance, once. Kept in {@code data/bestiary.yml} (same layout as on Paper), written off-thread, never over a file that
 * couldn't be read.
 */
final class Bestiary {
    record Page(String id, String name, long reward, List<String> mobs) {
    }

    record Info(String name, String habitat, String note) {
    }

    static final class Book {
        final Set<String> seen = new LinkedHashSet<>();
        final Map<String, Integer> harvested = new LinkedHashMap<>();
        final Map<String, Integer> slain = new LinkedHashMap<>();
        final Set<String> paid = new LinkedHashSet<>();

        boolean done(String mob) {
            return seen.contains(mob) && (harvested.getOrDefault(mob, 0) > 0 || slain.getOrDefault(mob, 0) > 0);
        }
    }

    private final WildlifeModule m;
    private final Path file;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Info> info = new HashMap<>();
    private final Set<String> all = new LinkedHashSet<>();
    private final Map<UUID, Book> books = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Feather-Bestiary-IO");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean dirty;
    private boolean unreadable;
    private boolean enabled = true, lineOfSight = true, seenNotice = true;
    private double radius = 12;
    /** Small module state saved alongside (the Restless Moon's last announced day). */
    long moonDay = -1;

    Bestiary(WildlifeModule module) {
        this.m = module;
        this.file = module.feather().dataFile("bestiary.yml");
    }

    void reload(Yaml cfg) {
        enabled = cfg.getBoolean("bestiary.enabled", true);
        radius = Math.max(2, Math.min(32, cfg.getDouble("bestiary.sight-radius", 12)));
        lineOfSight = cfg.getBoolean("bestiary.line-of-sight", true);
        seenNotice = cfg.getBoolean("bestiary.seen-notice", true);
        pages.clear();
        all.clear();
        Yaml ps = m.section("bestiary.pages");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                Yaml c = ps.getConfigurationSection(id);
                if (c == null) continue;
                List<String> mobs = new ArrayList<>();
                for (String s : c.getStringList("mobs")) if (s != null && !s.isBlank()) mobs.add(s.trim());
                if (mobs.isEmpty()) continue;
                pages.put(id, new Page(id, c.getString("name", id), Math.max(0, c.getLong("reward", 0)), mobs));
                all.addAll(mobs);
            }
        }
        info.clear();
        Yaml ms = m.section("bestiary.mobs");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                Yaml c = ms.getConfigurationSection(id);
                if (c != null) info.put(id, new Info(c.getString("name", null), c.getString("habitat", ""), c.getString("note", "")));
            }
        }
        if (unreadable) load();
    }

    boolean knows(String type) {
        return all.contains(type);
    }

    int total() {
        return all.size();
    }

    Book book(UUID player) {
        return books.computeIfAbsent(player, k -> new Book());
    }

    String name(String mob) {
        Info i = info.get(mob);
        return i != null && i.name() != null ? i.name() : m.displayName(mob);
    }

    // ------------------------------------------------------------------ progress

    /** Records a sighting; true if it is new. */
    boolean see(ServerPlayer p, String type) {
        String mob = m.family(type);
        if (!enabled || !all.contains(mob)) return false;
        Book b = book(p.getUUID());
        if (!b.seen.add(mob)) return false;
        dirty = true;
        if (seenNotice) {
            m.bar(p, "bestiary.seen-new", WildlifeModule.tag("name", name(mob)), WildlifeModule.tag("seen", b.seen.size()), WildlifeModule.tag("total", total()));
            m.sound(p, "bestiary-seen");
        }
        checkPages(p, b);
        return true;
    }

    void harvested(ServerPlayer p, String type) {
        String mob = m.family(type);
        if (!enabled || !all.contains(mob)) return;
        Book b = book(p.getUUID());
        b.seen.add(mob);
        b.harvested.merge(mob, 1, Integer::sum);
        dirty = true;
        checkPages(p, b);
    }

    void slain(ServerPlayer p, String type) {
        String mob = m.family(type);
        if (!enabled || !all.contains(mob)) return;
        Book b = book(p.getUUID());
        b.seen.add(mob);
        b.slain.merge(mob, 1, Integer::sum);
        dirty = true;
        checkPages(p, b);
    }

    private boolean complete(Book b, Page page) {
        for (String mob : page.mobs()) if (!b.done(mob)) return false;
        return true;
    }

    private void checkPages(ServerPlayer p, Book b) {
        for (Page page : pages.values()) {
            if (b.paid.contains(page.id()) || !complete(b, page)) continue;
            Hooks.Bank bank = m.feather().service(Hooks.Bank.class);
            if (page.reward() > 0 && bank != null) {
                if (bank.credit(p.getUUID(), Names.name(p), page.reward(), "BESTIARY", "bestiary page " + page.id()) < 0) {
                    // closed bank or full balance: try again on the next bestiary entry
                    m.send(p, "bestiary.bank-closed", WildlifeModule.tag("page", page.name()));
                    continue;
                }
            }
            b.paid.add(page.id());
            dirty = true;
            m.send(p, page.reward() > 0 && bank != null ? "bestiary.page-complete" : "bestiary.page-complete-free",
                    WildlifeModule.tag("page", page.name()), WildlifeModule.tag("coins", page.reward()));
            m.sound(p, "bestiary-page");
            m.feather().stat(p.getUUID(), "bestiary-pages", 1);
            save(false);
        }
    }

    /**
     * Every tick.sight-seconds: for each player, the tracked wild mobs (a map, no world search) not seen yet within the
     * sight radius and in line of sight are marked seen. Players who have seen everything are skipped.
     */
    void scan() {
        if (!enabled || all.isEmpty() || m.tracked.isEmpty()) return;
        double r2 = radius * radius;
        List<Entity> mobs = m.trackedEntities();
        for (ServerPlayer p : m.feather().onlinePlayers()) {
            if (p.isSpectator() || p.isDeadOrDying() || !m.enabledWorld(p.level())) continue;
            Book b = books.get(p.getUUID());
            if (b != null && b.seen.size() >= all.size()) continue;
            for (Entity e : mobs) {
                String type = m.tracked.get(e.getUUID());
                if (type == null) continue;
                String mob = m.family(type);
                if (!all.contains(mob) || b != null && b.seen.contains(mob)) continue;
                if (e.level() != p.level() || e.distanceToSqr(p) > r2) continue;
                if (lineOfSight && !p.hasLineOfSight(e)) continue;
                see(p, mob);
                b = books.get(p.getUUID());
            }
        }
    }

    // ------------------------------------------------------------------ the screens

    /** The page list. {@code back}: where the bottom button goes (the codex), or null to close. */
    void open(ServerPlayer viewer, Consumer<ServerPlayer> back) {
        Book b = book(viewer.getUUID());
        int seen = 0, done = 0;
        for (String mob : all) {
            if (b.seen.contains(mob)) seen++;
            if (b.done(mob)) done++;
        }
        TagResolver counts = TagResolver.resolver(WildlifeModule.tag("seen", seen), WildlifeModule.tag("done", done),
                WildlifeModule.tag("total", total()), WildlifeModule.tag("pages", b.paid.size()), WildlifeModule.tag("pages-total", pages.size()));
        Dialogs.Menu menu = m.feather().dialogs().menu(m.msg("bestiary.title", counts));
        menu.text(m.msg("bestiary.body", counts), 300);
        for (Page page : pages.values()) {
            int pd = 0;
            for (String mob : page.mobs()) if (b.done(mob)) pd++;
            TagResolver t = TagResolver.resolver(WildlifeModule.tag("page", page.name()), WildlifeModule.tag("done", pd),
                    WildlifeModule.tag("total", page.mobs().size()), WildlifeModule.tag("coins", page.reward()));
            Component label = m.msg(b.paid.contains(page.id()) ? "bestiary.page-button-done" : "bestiary.page-button", t);
            List<Component> tip = new ArrayList<>();
            for (String mob : page.mobs()) tip.add(line(b, mob, true));
            Component tooltip = Component.join(JoinConfiguration.newlines(), tip);
            String id = page.id();
            menu.button(label, tooltip, 150, pl -> openPage(pl, id, back));
        }
        menu.columns(2);
        if (back != null) menu.exit(m.feather().text().msg("menu.back"), back);
        else menu.exit(m.feather().text().msg("menu.close"), null);
        menu.open(viewer);
        m.feather().text().sound(viewer, "menu-open");
    }

    private Component line(Book b, String mob, boolean shortForm) {
        boolean seen = b.seen.contains(mob);
        Info i = info.get(mob);
        TagResolver t = TagResolver.resolver(WildlifeModule.tag("name", name(mob)),
                WildlifeModule.tag("harvested", b.harvested.getOrDefault(mob, 0)), WildlifeModule.tag("slain", b.slain.getOrDefault(mob, 0)),
                WildlifeModule.tag("habitat", i == null || i.habitat() == null ? "" : i.habitat()),
                WildlifeModule.tag("note", i == null || i.note() == null ? "" : i.note()));
        if (!seen) return m.msg(shortForm ? "bestiary.tip-unknown" : "bestiary.entry-unknown", t);
        String key = b.done(mob) ? "done" : "seen";
        return m.msg((shortForm ? "bestiary.tip-" : "bestiary.entry-") + key, t);
    }

    private void openPage(ServerPlayer viewer, String id, Consumer<ServerPlayer> back) {
        Page page = pages.get(id);
        if (page == null) {
            open(viewer, back);
            return;
        }
        Book b = book(viewer.getUUID());
        int pd = 0;
        for (String mob : page.mobs()) if (b.done(mob)) pd++;
        TagResolver t = TagResolver.resolver(WildlifeModule.tag("page", page.name()), WildlifeModule.tag("done", pd),
                WildlifeModule.tag("total", page.mobs().size()), WildlifeModule.tag("coins", page.reward()));
        Dialogs.Menu menu = m.feather().dialogs().menu(m.msg("bestiary.page-title", t));
        for (String mob : page.mobs()) menu.text(line(b, mob, false), 300);
        String rewardKey = b.paid.contains(page.id()) ? "bestiary.page-paid" : page.reward() > 0 ? "bestiary.page-reward" : "bestiary.page-no-reward";
        menu.text(m.msg(rewardKey, t), 300);
        menu.exit(m.feather().text().msg("menu.back"), pl -> open(pl, back));
        menu.open(viewer);
    }

    /** The codex's bestiary line for a creature (seen, harvested, slain, its page), or null when no page lists it. */
    Hooks.Bestiary.BestiaryMob line(UUID player, String type) {
        String mob = m.family(type);
        if (!enabled || !all.contains(mob)) return null;
        Book b = books.get(player);
        String page = "";
        for (Page p : pages.values()) if (p.mobs().contains(mob)) {
            page = p.name();
            break;
        }
        return b == null ? new Hooks.Bestiary.BestiaryMob(false, 0, 0, page)
                : new Hooks.Bestiary.BestiaryMob(b.seen.contains(mob), b.harvested.getOrDefault(mob, 0), b.slain.getOrDefault(mob, 0), page);
    }

    /** The pages with this player's progress, for the codex. */
    List<Hooks.Bestiary.BestiaryPage> pages(UUID player) {
        if (!enabled) return List.of();
        Book b = book(player);
        List<Hooks.Bestiary.BestiaryPage> out = new ArrayList<>();
        for (Page page : pages.values()) {
            int pd = 0;
            for (String mob : page.mobs()) if (b.done(mob)) pd++;
            out.add(new Hooks.Bestiary.BestiaryPage(page.id(), page.name(), pd, page.mobs().size(), page.reward(), b.paid.contains(page.id())));
        }
        return out;
    }

    /** One line per page and per creature for /wildlife book (tests and staff). */
    List<String> summary(UUID player) {
        Book b = book(player);
        List<String> out = new ArrayList<>();
        for (Page page : pages.values()) {
            int pd = 0;
            for (String mob : page.mobs()) if (b.done(mob)) pd++;
            out.add("PAGE " + page.id() + " " + pd + "/" + page.mobs().size() + (b.paid.contains(page.id()) ? " paid" : ""));
        }
        for (String mob : all) {
            out.add("MOB " + mob + " seen=" + (b.seen.contains(mob) ? 1 : 0) + " harvested=" + b.harvested.getOrDefault(mob, 0)
                    + " slain=" + b.slain.getOrDefault(mob, 0));
        }
        return out;
    }

    void reset(UUID player) {
        if (books.remove(player) != null) dirty = true;
    }

    // ------------------------------------------------------------------ disk

    void load() {
        books.clear();
        unreadable = false;
        Yaml yml = SafeYaml.read(file, m.feather().getLogger(), "bestiary entries");
        if (yml == null) {
            unreadable = true;
            return;
        }
        moonDay = yml.getLong("state.moon-day", -1);
        Yaml sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(k);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Yaml c = sec.getConfigurationSection(k);
            if (c == null) continue;
            Book b = new Book();
            b.seen.addAll(c.getStringList("seen"));
            b.paid.addAll(c.getStringList("paid"));
            Yaml h = c.getConfigurationSection("harvested");
            if (h != null) for (String mob : h.getKeys(false)) b.harvested.put(mob, h.getInt(mob));
            Yaml s = c.getConfigurationSection("slain");
            if (s != null) for (String mob : s.getKeys(false)) b.slain.put(mob, s.getInt(mob));
            books.put(id, b);
        }
    }

    void markDirty() {
        dirty = true;
    }

    void save(boolean wait) {
        if (!dirty || unreadable) return;
        dirty = false;
        Yaml yml = new Yaml();
        yml.set("state.moon-day", moonDay);
        for (Map.Entry<UUID, Book> en : books.entrySet()) {
            Book b = en.getValue();
            if (b.seen.isEmpty() && b.harvested.isEmpty() && b.slain.isEmpty() && b.paid.isEmpty()) continue;
            String base = "players." + en.getKey() + ".";
            yml.set(base + "seen", new ArrayList<>(b.seen));
            if (!b.harvested.isEmpty()) yml.set(base + "harvested", new LinkedHashMap<>(b.harvested));
            if (!b.slain.isEmpty()) yml.set(base + "slain", new LinkedHashMap<>(b.slain));
            if (!b.paid.isEmpty()) yml.set(base + "paid", new ArrayList<>(b.paid));
        }
        // Copied here, turned into text on the IO thread.
        Object snap = yml.snapshot();
        Runnable write = () -> {
            try {
                String data = "# Feather bestiary (seen / harvested / slain wild creatures per player, pages paid). Edit only while the server is stopped.\n"
                        + Yaml.dump(snap);
                if (!SafeYaml.write(file, data, m.feather().getLogger())) dirty = true;
            } catch (RuntimeException e) {
                // a dump that fails must not drop the save: the next timer tick tries again
                m.feather().getLogger().error("Could not save {}: {}", file, e.toString());
                dirty = true;
            }
        };
        if (!wait) {
            try {
                io.execute(write);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                write.run();
            }
            return;
        }
        try {
            io.submit(write).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            write.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            m.feather().getLogger().warn("Writing data/bestiary.yml failed: {}", e.toString());
        }
    }

    void shutdown() {
        save(false);
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) m.feather().getLogger().warn("Writing data/bestiary.yml timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
