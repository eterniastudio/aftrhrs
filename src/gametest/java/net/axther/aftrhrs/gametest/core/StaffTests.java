package net.axther.aftrhrs.gametest.core;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static net.axther.aftrhrs.gametest.core.CoreTests.check;

/** Staff tools and utilities: /ec, /invsee, /near, /skull, /speed, staff chat, reports, /tppos, /whois. */
public class StaffTests {
    @GameTest(maxTicks = 60)
    public void enderChestViewClosesWhenTheOwnerLeaves(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("ecS"));
        Bots.Bot t = Bots.join(h, Bots.name("ecT"));
        staff.op(true);
        t.player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 9));
        staff.cmd("ec " + t.name());
        check(h, !(staff.player.containerMenu instanceof InventoryMenu), "/ec did not open: " + staff.chat());
        staff.cmd("invsee " + t.name());
        check(h, !(staff.player.containerMenu instanceof InventoryMenu), "/invsee did not open: " + staff.chat());
        staff.cmd("ec " + t.name());
        t.leave();
        h.runAfterDelay(3, () -> {
            check(h, staff.player.containerMenu instanceof InventoryMenu, "the ender chest of a player who left stayed open (dupe)");
            staff.op(false);
            staff.leave();
            h.succeed();
        });
    }

    @GameTest
    public void nearIgnoresNegativeRadius(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("nrA"));
        Bots.Bot far = SocialTests.far(h, Bots.name("nrB"), 400);
        a.cmd("near -100000");
        check(h, a.chat().stream().noneMatch(s -> s.contains(far.name())), "/near -100000 found a player 400 blocks away: " + a.chat());
        a.cmd("near 999999999999");
        a.cmd("near 5");
        a.leave();
        far.leave();
        h.succeed();
    }

    @GameTest
    public void skullRefusesNamesClientsCantRead(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("sk"));
        a.op(true);
        for (String bad : new String[]{"Way_Too_Long_Name_123", "名前"}) {
            a.cmd("skull " + bad);
        }
        a.cmd("skull Notch");
        int heads = TravelTests.count(a, Items.PLAYER_HEAD);
        check(h, heads == 1, "expected only the valid head, got " + heads);
        check(h, a.problems().isEmpty(), "a head a client can't read was sent: " + a.problems());
        // The harness itself notices such a packet (so the check above means something).
        ItemStack bad = new ItemStack(Items.PLAYER_HEAD);
        bad.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved("Way_Too_Long_Name_123"));
        a.player.getInventory().setItem(20, bad);
        a.player.inventoryMenu.broadcastChanges();
        boolean noticed = !a.problems().isEmpty();
        a.player.getInventory().setItem(20, ItemStack.EMPTY);
        a.problems().clear();
        check(h, noticed, "the test harness did not notice an unencodable item");
        a.op(false);
        a.leave();
        h.succeed();
    }

    @GameTest
    public void speedRejectsNaN(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("spd"));
        a.op(true);
        a.cmd("speed NaN");
        a.cmd("speed Infinity");
        a.cmd("speed -5");
        float walk = a.player.getAbilities().getWalkingSpeed(), fly = a.player.getAbilities().getFlyingSpeed();
        check(h, Float.isFinite(walk) && Float.isFinite(fly), "speed became " + walk + " / " + fly);
        a.cmd("speed 1");
        a.op(false);
        a.leave();
        h.succeed();
    }

    @GameTest
    public void staffChatAndReports(GameTestHelper h) {
        Bots.Bot s1 = Bots.join(h, Bots.name("scA"));
        Bots.Bot s2 = Bots.join(h, Bots.name("scB"));
        Bots.Bot p = Bots.join(h, Bots.name("scP"));
        s1.op(true);
        s2.op(true);
        s1.cmd("sc staff-only-line");
        check(h, s2.heard("staff-only-line"), "staff chat did not reach staff: " + s2.chat());
        check(h, !p.heard("staff-only-line"), "staff chat reached a player");
        p.cmd("sc sneaky");
        check(h, !s2.heard("sneaky"), "a player without feather.staffchat talked in staff chat");
        p.cmd("report " + s1.name() + " report-reason-text");
        s2.cmd("reports");
        p.cmd("report " + s1.name() + " again-too-soon");
        p.cmd("report zz_nobody x");
        p.cmd("report " + p.name() + " myself");
        s1.op(false);
        s2.op(false);
        s1.leave();
        s2.leave();
        p.leave();
        h.succeed();
    }

    @GameTest
    public void tpposStaysInTheWorld(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("tpp"));
        a.op(true);
        var start = a.player.position();
        a.cmd("tppos 1e10 64 0");
        a.cmd("tppos NaN 64 0");
        a.cmd("tppos 30000001 64 30000001");
        check(h, Math.abs(a.player.getX()) < 3.0E7 && Double.isFinite(a.player.getX()), "teleported out of the world: " + a.player.position());
        a.cmd("tppos ~5 ~ ~");
        a.cmd("whois " + a.name());
        a.cmd("ping");
        a.cmd("list");
        a.op(false);
        a.leave();
        h.succeed();
    }

    /** Offline targets: mute, warn, freeze and history for someone who left keep every change. */
    @GameTest(maxTicks = 60)
    public void offlineModerationKeepsEveryChange(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("omS"));
        Bots.Bot t = Bots.join(h, Bots.name("omT"));
        String name = t.name();
        var id = t.id();
        t.leave();
        staff.op(true);
        h.runAfterDelay(25, () -> {
            staff.cmd("mute " + name + " 1d offline-mute");
            staff.cmd("warn " + name + " offline-warn");
            staff.cmd("freeze " + name);
            var p = Feather.get().profiles().load(id);
            check(h, p.mutedUntil != 0, "the offline mute was lost");
            check(h, p.warnings.stream().anyMatch(w -> w.reason().contains("offline-warn")), "the offline warning was lost");
            check(h, p.modLog.size() >= 2, "history lines were lost: " + p.modLog);
            staff.cmd("unmute " + name);
            staff.cmd("unfreeze " + name);
            staff.op(false);
            staff.leave();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ config text

    /**
     * Every bundled feather/*.yml has only text keys: YAML 1.1 reads a bare {@code off:}, {@code no:} or {@code on:}
     * as a yes/no value, so the lookup by name never finds that message. (Numbers like {@code 5:} come back as "5".)
     */
    @GameTest
    public void bundledConfigsHaveOnlyTextKeys(GameTestHelper h) {
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("aftrhrs").orElseThrow().findPath("feather").orElseThrow();
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> list = Files.list(dir)) {
            for (Path f : list.filter(x -> x.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                files++;
                try (InputStream in = Files.newInputStream(f)) {
                    Object root = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(in);
                    nonTextKeys(f.getFileName().toString(), root, bad);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        check(h, files >= 20, "only " + files + " bundled configs found in " + dir);
        check(h, bad.isEmpty(), "keys read as something other than text (quote them): " + bad);
        Yaml messages = Feather.bundled("messages.yml");
        for (String key : List.of("farming.fair.off", "quests.goal.off", "bank.robbery.deputy.off")) {
            check(h, messages.getString(key) != null, "messages.yml has no " + key);
        }
        h.succeed();
    }

    private static void nonTextKeys(String path, Object node, List<String> bad) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object k = e.getKey();
                String at = path + (path.endsWith(".yml") ? ":" : ".") + k;
                // Numbered tables (streak days, bond levels) read back the same as text; yes/no and null keys don't.
                if (!(k instanceof String) && !(k instanceof Integer) && !(k instanceof Long)) bad.add(at + " (" + (k == null ? "null" : k.getClass().getSimpleName()) + ")");
                nonTextKeys(at, e.getValue(), bad);
            }
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) nonTextKeys(path + "[" + i + "]", l.get(i), bad);
        }
    }

    /**
     * The one-resolver parse keeps its order: a caller's tag wins over the palette and {@code <prefix>}, a later
     * extra over an earlier one, and a formatted text's {@code <prefix>} is the subject's rank prefix (LuckPerms, or
     * nothing), not the chat prefix. The new palette tokens work.
     */
    @GameTest
    public void textTagsKeepTheirPrecedence(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("txp"));
        Text text = Feather.get().text();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        try {
            String chatPrefix = plain.serialize(text.parse("<prefix>"));
            check(h, !chatPrefix.isEmpty(), "messages.yml prefix is empty");
            String rank = "";
            var lp = Feather.get().placeholders().luckPerms();
            if (lp != null) rank = plain.serialize(net.axther.aftrhrs.core.Placeholders.legacyOrMini(lp.prefix(a.player)));
            String formatted = plain.serialize(Feather.get().placeholders().format("<prefix>X", a.player, a.player));
            check(h, formatted.equals(rank + "X"), "<prefix> with a subject gave '" + formatted + "', not the rank prefix '" + rank + "'");
            // Without a subject, player tags stay literal text; server tags still work.
            String noSubject = plain.serialize(Feather.get().placeholders().format("<name>|<server>", null, null));
            check(h, noSubject.startsWith("<name>|") && !noSubject.endsWith("<server>"), "no-subject format: " + noSubject);
            // Extras win over the subject's tags, the palette and each other (the last one given wins).
            String over = plain.serialize(Feather.get().placeholders().format("<online>|<rotating>|<muted>", a.player, a.player,
                    Text.component("rotating", Component.text("first")), Text.component("online", Component.text("R")),
                    Text.component("rotating", Component.text("last")), Text.component("muted", Component.text("M"))));
            check(h, over.equals("R|last|M"), "extras did not win: " + over);
            String plainOver = plain.serialize(text.parse("<prefix>|<k>", Text.unparsed("k", "1"), Text.unparsed("k", "2"), Text.unparsed("prefix", "P")));
            check(h, plainOver.equals("P|2"), "caller tags did not win over the prefix / earlier ones: " + plainOver);
            Component coin = text.parse("<coin>5</coin><gem>6");
            check(h, TextColor.color(0xFFD24A).equals(colorOf(coin, "5")) && TextColor.color(0x7FE3FF).equals(colorOf(coin, "6")), "palette coin/gem: " + coin);
        } finally {
            a.leave();
        }
        h.succeed();
    }

    /** The colour the text {@code part} is drawn in (inherited from its parents), or null when it isn't there. */
    private static TextColor colorOf(Component c, String part) {
        return colorOf(c, part, null);
    }

    private static TextColor colorOf(Component c, String part, TextColor inherited) {
        TextColor own = c.color() != null ? c.color() : inherited;
        if (c instanceof net.kyori.adventure.text.TextComponent t && t.content().equals(part)) return own;
        for (Component child : c.children()) {
            TextColor found = colorOf(child, part, own);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Text.msgBare leaves {@code <prefix>} out (a join-digest bullet) and a caller's tag still wins over it; the keys
     * other packages read from messages.yml are bundled (join digest, quests.join, admin.data-broken, creatures.*).
     */
    @GameTest
    public void bareMessagesAndBundledKeys(GameTestHelper h) {
        Text text = Feather.get().text();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        String chatPrefix = plain.serialize(text.prefix());
        check(h, !chatPrefix.isEmpty(), "messages.yml prefix is empty");
        String full = plain.serialize(text.msg("quests.join-new")), bare = plain.serialize(text.msgBare("quests.join-new"));
        check(h, full.equals(chatPrefix + bare) && !bare.startsWith(chatPrefix), "msgBare: '" + bare + "' vs msg '" + full + "'");
        check(h, plain.serialize(text.msgBare("quests.join-new", Text.unparsed("prefix", "P"))).startsWith("P"), "a caller's <prefix> lost to msgBare's");
        Yaml messages = Feather.bundled("messages.yml");
        for (String key : List.of("join-digest.header", "join-digest.line", "quests.join", "quests.join-status.new", "quests.join-status.ready",
                "quests.join-status.boost", "quests.join-status.goal-reward", "quests.join-status.separator", "admin.data-broken",
                "creatures.stay", "creatures.follow", "creatures.tamed", "creatures.named", "creatures.ride-hint", "creatures.passenger",
                "creatures.passenger-custom", "creatures.gift", "creatures.seeds", "creatures.barrel", "creatures.kitty-warm",
                "creatures.clover-lucky", "creatures.void-rescue", "creatures.void-rescue-tired", "creatures.penguin.swallowing",
                "creatures.penguin.growing", "creatures.penguin.grew-up", "creatures.penguin.already-fishing", "creatures.penguin.not-hungry",
                "creatures.penguin.fishing-start", "creatures.penguin.fishing-done", "creatures.penguin.fishing-done-extra",
                "creatures.penguin.caught-too")) {
            check(h, messages.getString(key) != null, "messages.yml has no " + key);
        }
        h.succeed();
    }
}
