package net.axther.aftrhrs.feature.staff;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.LuckPermsHook;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.moderation.ModerationModule;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.util.Prediction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everyday utilities: /ping, /near, /list, /playtime, /whois, /trash, virtual workstations (/anvil, /grindstone,
 * /stonecutter, /loom, /cartography, /smithing), /skull, /repair, /day /night /sun /storm, /sudo, /tpo, /tpall,
 * /tppos; plus info commands defined in config.yml ({@code info-commands}) and rotating chat announcements.
 */
public final class UtilityModule implements FeatherModule, FeatherCommand {
    private static final List<String> COMMANDS = List.of("ping", "near", "list", "playtime", "whois", "trash", "anvil",
            "grindstone", "stonecutter", "loom", "cartography", "smithing", "skull", "repair", "day", "night", "sun", "storm",
            "sudo", "tpo", "tpall", "tppos");
    /** The vanilla world border's reach, and a generous height limit for /tppos. */
    private static final double MAX_XZ = 30_000_000;
    private static final double MAX_Y = 20_000;

    private static volatile UtilityModule active;

    private final Feather plugin;
    private Tasks.Task announcer;
    private int announceIndex;
    /** label -> config key of the info commands registered so far (they stay until a restart, as on Paper). */
    private final Map<String, String> infoCommands = new LinkedHashMap<>();

    public UtilityModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Utilities";
    }

    @Override
    public void enable() {
        active = this;
        for (String c : COMMANDS) plugin.bind(c, this);
        readInfoCommands();
        MinecraftServer server = Feather.server();
        registerInfoCommands(server.getCommands().getDispatcher());
        plugin.once("utility.commands", () -> CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            UtilityModule m = active;
            if (m != null) m.registerInfoCommands(dispatcher); // /reload rebuilds the command tree
        }));
        reload();
    }

    @Override
    public void reload() {
        if (announcer != null) announcer.cancel();
        announcer = null;
        Yaml c = plugin.getConfig();
        if (c.getBoolean("announcements.enabled", false) && !c.getList("announcements.messages", List.of()).isEmpty()) {
            long every = Math.max(10, c.getLong("announcements.interval-seconds", 300)) * 20L;
            announcer = Tasks.timer(every, every, this::announce);
        }
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (announcer != null) announcer.cancel();
    }

    // ------------------------------------------------------------------ announcements

    private void announce() {
        Yaml c = plugin.getConfig();
        List<?> messages = c.getList("announcements.messages", List.of());
        if (messages.isEmpty() || plugin.onlinePlayers().size() < c.getInt("announcements.min-players", 1)) return;
        int i = c.getString("announcements.order", "in-order").equalsIgnoreCase("random")
                ? ThreadLocalRandom.current().nextInt(messages.size()) : announceIndex++ % messages.size();
        Object entry = messages.get(i);
        String raw = entry instanceof List<?> l ? String.join("\n", l.stream().map(String::valueOf).toList()) : String.valueOf(entry);
        String wrapper = String.join("\n", plugin.text().variants("announcements.format"));
        for (ServerPlayer p : plugin.onlinePlayers()) {
            Component body = plugin.placeholders().format(raw, p, p);
            Adv.send(p, wrapper.isEmpty() ? body : plugin.text().parse(wrapper, Text.component("message", body)));
        }
        for (ServerPlayer p : plugin.onlinePlayers()) plugin.text().sound(p, "announcement");
    }

    // ------------------------------------------------------------------ info commands

    private void readInfoCommands() {
        Yaml sec = plugin.getConfig().getConfigurationSection("info-commands");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            Yaml s = sec.getConfigurationSection(name);
            if (s == null) continue;
            infoCommands.put(name.toLowerCase(Locale.ROOT), name);
            for (String a : s.getStringList("aliases")) infoCommands.put(a.toLowerCase(Locale.ROOT), name);
        }
    }

    /** Each {@code info-commands.<name>} (and its aliases) as a real command that prints its lines; wins over same-named ones. */
    private void registerInfoCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (Map.Entry<String, String> e : infoCommands.entrySet()) {
            String label = e.getKey();
            String key = e.getValue();
            CommandNode<CommandSourceStack> existing = dispatcher.getRoot().getChild(label);
            if (existing != null) dispatcher.getRoot().getChildren().remove(existing);
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(label)
                    .requires(src -> {
                        Yaml now = plugin.getConfig().getConfigurationSection("info-commands." + key);
                        String perm = now == null ? "" : now.getString("permission", "");
                        return now != null && (perm.isEmpty() || Perms.has(src, perm));
                    })
                    .executes(ctx -> {
                        runInfo(new Sender(ctx.getSource()), key);
                        return 1;
                    });
            dispatcher.register(node);
        }
    }

    private void runInfo(Sender sender, String key) {
        Yaml now = plugin.getConfig().getConfigurationSection("info-commands." + key);
        if (now == null) return;
        String perm = now.getString("permission", "");
        if (!perm.isEmpty() && !sender.hasPermission(perm)) {
            plugin.text().send(sender, "general.no-permission");
            return;
        }
        ServerPlayer p = sender.player();
        List<String> lines = now.isList("lines") ? now.getStringList("lines") : List.of(now.getString("lines", ""));
        sender.sendMessage(plugin.placeholders().format(String.join("\n", lines), p, p));
        if (p != null) plugin.text().sound(p, "click");
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        String name = CommandBridge.nameOf(label);
        switch (name) {
            case "ping" -> ping(sender, args);
            case "near" -> near(sender, args);
            case "list" -> list(sender);
            case "playtime" -> playtime(sender, args);
            case "whois" -> whois(sender, args);
            case "trash" -> {
                ServerPlayer p = sender.player();
                if (p != null) {
                    // A plain chest whose contents are thrown away when it closes.
                    p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, new SimpleContainer(36), 4),
                            Adv.vanilla(plugin.text().msg("util.trash-title"))));
                    plugin.text().sound(p, "click");
                }
            }
            case "anvil", "grindstone", "stonecutter", "loom", "cartography", "smithing" -> workstation(sender, name);
            case "skull" -> skull(sender, args);
            case "repair" -> repair(sender, args);
            case "day", "night" -> time(sender, name);
            case "sun", "storm" -> weather(sender, name.equals("storm"));
            case "sudo" -> sudo(sender, args);
            case "tpo" -> tpo(sender, args);
            case "tpall" -> tpall(sender);
            case "tppos" -> tppos(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private ServerPlayer self(Sender sender) {
        if (sender.isPlayer()) return sender.player();
        plugin.text().send(sender, "general.players-only");
        return null;
    }

    private ServerPlayer other(Sender sender, String input) {
        ServerPlayer p = plugin.findPlayer(sender, input);
        if (p == null) plugin.notFound(sender, input);
        return p;
    }

    // ------------------------------------------------------------------ info

    private void ping(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer target = args.length > 0 && sender.hasPermission("feather.ping.others") ? other(sender, args[0]) : self(sender);
        if (target == null) return;
        int ping = target.connection.latency();
        String color = ping < 80 ? "good" : ping < 200 ? "warn" : "bad";
        Component value = text.parse("<" + color + ">" + ping + "ms");
        if (target == sender.player()) text.send(sender, "util.ping", Text.component("ping", value));
        else text.send(sender, "util.ping-other", Text.component("ping", value), Text.component("target", Names.display(target)));
    }

    private void near(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = self(sender);
        if (p == null) return;
        int max = plugin.getConfig().getInt("near.max-radius", 200);
        int radius = plugin.getConfig().getInt("near.default-radius", 100);
        if (args.length > 0) {
            try {
                radius = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                text.send(p, "staff.bad-number", Text.unparsed("input", args[0]));
                return;
            }
        }
        // A negative radius squared is huge: /near -100000 listed everyone in the world.
        if (radius < 1) {
            text.send(p, "staff.bad-number", Text.unparsed("input", args[0]));
            return;
        }
        if (radius > max && !Perms.has(p, "feather.near.unlimited")) radius = max;
        double r2 = (double) radius * radius;
        List<Map.Entry<ServerPlayer, Double>> found = new ArrayList<>();
        for (ServerPlayer o : p.level().players()) {
            if (o == p || !plugin.canSee(p, o) || o.isSpectator()) continue;
            double d = o.distanceToSqr(p);
            if (d <= r2) found.add(Map.entry(o, Math.sqrt(d)));
        }
        if (found.isEmpty()) {
            text.send(p, "util.near-none", Text.unparsed("radius", radius));
            return;
        }
        found.sort(Map.Entry.comparingByValue());
        List<Component> parts = new ArrayList<>();
        for (Map.Entry<ServerPlayer, Double> e : found) {
            parts.add(text.msg("util.near-entry", Text.component("name", Names.display(e.getKey())),
                    Text.unparsed("distance", Math.round(e.getValue()))));
        }
        text.send(p, "util.near", Text.unparsed("radius", radius),
                Text.component("players", Component.join(JoinConfiguration.separator(text.msg("util.list-separator")), parts)));
    }

    private void list(Sender sender) {
        Text text = plugin.text();
        LuckPermsHook lp = plugin.placeholders().luckPerms();
        Map<String, List<ServerPlayer>> groups = new TreeMap<>();
        Map<String, Integer> weight = new LinkedHashMap<>();
        int total = 0;
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!plugin.canSee(sender.player(), p)) continue;
            total++;
            String g = "default";
            if (lp != null) {
                try {
                    g = lp.group(p);
                    weight.putIfAbsent(g, lp.weight(g));
                } catch (Throwable ignored) {
                }
            }
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(p);
        }
        text.send(sender, "util.list-header", Text.unparsed("online", total), Text.unparsed("max", Feather.server().getPlayerList().getMaxPlayers()));
        List<String> order = new ArrayList<>(groups.keySet());
        order.sort(Comparator.comparingInt((String g) -> -weight.getOrDefault(g, 0)).thenComparing(g -> g));
        for (String g : order) {
            List<ServerPlayer> ps = groups.get(g);
            ps.sort(Comparator.comparing(Names::name, String.CASE_INSENSITIVE_ORDER));
            List<Component> names = new ArrayList<>();
            for (ServerPlayer p : ps) {
                Component n = Names.display(p);
                if (plugin.isAfk(p)) n = n.append(text.msg("util.list-afk"));
                if (plugin.profiles().get(p).vanished) n = n.append(text.msg("util.list-vanished"));
                names.add(n);
            }
            text.send(sender, "util.list-group", Text.unparsed("group", Character.toUpperCase(g.charAt(0)) + g.substring(1)),
                    Text.unparsed("count", ps.size()),
                    Text.component("players", Component.join(JoinConfiguration.separator(text.msg("util.list-separator")), names)));
        }
    }

    /** Play time in ticks: live for online players, else from the world's stats file. */
    private static long playTicks(UUID id) {
        Feather f = Feather.get();
        ServerPlayer online = f == null ? null : f.player(id);
        if (online != null) return online.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return 0;
        for (Path file : List.of(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"),
                server.getWorldPath(LevelResource.ROOT).resolve("stats").resolve(id + ".json"))) {
            try {
                if (!Files.exists(file)) continue;
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonObject custom = root.getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
                JsonElement t = custom == null ? null : custom.get("minecraft:play_time");
                if (t == null && custom != null) t = custom.get("minecraft:play_one_minute");
                if (t != null) return t.getAsLong();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** When a player first joined (Bukkit's getFirstPlayed): the creation time of their files, or 0. */
    private static long firstPlayed(UUID id) {
        MinecraftServer server = Feather.serverOrNull();
        Feather f = Feather.get();
        if (server == null || f == null) return 0;
        long best = 0;
        for (Path file : List.of(f.profiles().folder().resolve(id + ".yml"), server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"))) {
            try {
                if (!Files.exists(file)) continue;
                long t = Files.readAttributes(file, BasicFileAttributes.class).creationTime().toMillis();
                if (t > 0 && (best == 0 || t < best)) best = t;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private void playtime(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length > 0 && sender.hasPermission("feather.playtime.others")) {
            ServerPlayer online = plugin.findPlayer(sender, args[0]);
            OfflinePlayer off = online != null ? OfflinePlayer.of(online) : plugin.findOfflinePlayer(sender, args[0]);
            if (off == null || (!off.hasPlayedBefore() && online == null)) {
                text.send(sender, "msg.seen-never", Text.unparsed("name", args[0]));
                return;
            }
            text.send(sender, "util.playtime-other", Text.unparsed("name", off.name() == null ? args[0] : off.name()),
                    Text.unparsed("time", Placeholders.duration(playTicks(off.id()) / 20L * 1000L)));
            return;
        }
        ServerPlayer p = self(sender);
        if (p == null) return;
        text.send(p, "util.playtime", Text.unparsed("time", Placeholders.duration(playTicks(p.getUUID()) / 20L * 1000L)));
    }

    private void whois(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "util.whois-usage");
            return;
        }
        ServerPlayer t = plugin.findPlayer(sender, args[0]);
        if (t == null) {
            OfflinePlayer off = plugin.findOfflinePlayer(sender, args[0]);
            if (off != null && off.hasPlayedBefore() && !off.isOnline()) whoisOffline(sender, off, args[0]);
            else plugin.notFound(sender, args[0]);
            return;
        }
        Profiles.Profile pr = plugin.profiles().get(t);
        long first = firstPlayed(t.getUUID());
        text.send(sender, "util.whois",
                Text.component("name", Names.display(t)), Text.unparsed("username", Names.name(t)),
                Text.unparsed("uuid", t.getUUID()),
                Text.unparsed("group", plugin.placeholders().luckPerms() == null ? "-" : safeGroup(t)),
                Text.unparsed("health", Placeholders.heartsNumber(t.getHealth())), Text.unparsed("food", t.getFoodData().getFoodLevel()),
                Text.unparsed("gamemode", Placeholders.pretty(t.gameMode())),
                Text.unparsed("location", Worlds.name(t.level()) + " " + Loc.of(t).coords()),
                Text.unparsed("ping", t.connection.latency()),
                Text.unparsed("playtime", Placeholders.duration(playTicks(t.getUUID()) / 20L * 1000L)),
                Text.unparsed("first", first <= 0 ? "-" : new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(first))),
                Text.unparsed("fly", yesNo(t.getAbilities().mayfly)), Text.unparsed("god", yesNo(pr.god)),
                Text.unparsed("vanished", yesNo(pr.vanished)), Text.unparsed("afk", yesNo(plugin.isAfk(t))),
                Text.unparsed("is-muted", yesNo(pr.muted())),
                Text.unparsed("address", seesAddress(sender) ? t.getIpAddress() : "hidden"));
        ModerationModule mod = plugin.module(ModerationModule.class);
        if (mod != null) sender.sendMessage(mod.sanctions().status(OfflinePlayer.of(t), pr));
    }

    private static boolean seesAddress(Sender sender) {
        return sender.hasPermission("feather.whois.ip") || sender.hasPermission("feather.whois.address");
    }

    /** /whois for someone who isn't online: what their Feather profile and the server remember. */
    private void whoisOffline(Sender sender, OfflinePlayer t, String input) {
        Text text = plugin.text();
        Profiles.Profile pr = plugin.profiles().load(t.id());
        String name = t.name() == null ? input : t.name();
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd");
        ModerationModule mod = plugin.module(ModerationModule.class);
        long seen = Math.max(pr.lastSeen, net.axther.aftrhrs.feature.chat.MessagingModule.lastPlayed(t.id()));
        String lastSeen = seen <= 0 ? "-" : (mod != null ? mod.date(seen, sender) : day.format(new java.util.Date(seen)))
                + " (" + Placeholders.duration(System.currentTimeMillis() - seen) + " ago)";
        Profiles.Home at = pr.lastLocation;
        String location = at == null ? "-" : at.world() + " " + (int) Math.floor(at.x()) + ", " + (int) Math.floor(at.y()) + ", " + (int) Math.floor(at.z());
        String nick = pr.nickRaw == null ? "-" : pr.nickRaw;
        long first = firstPlayed(t.id());
        text.send(sender, "util.whois-offline",
                Text.unparsed("username", name), Text.unparsed("uuid", t.id()),
                Text.unparsed("first", first <= 0 ? "-" : day.format(new java.util.Date(first))),
                Text.unparsed("seen", lastSeen),
                Text.unparsed("playtime", Placeholders.duration(playTicks(t.id()) / 20L * 1000L)),
                Text.unparsed("location", location), Text.unparsed("nick", nick),
                Text.unparsed("homes", pr.homes().size()),
                Text.unparsed("address", seesAddress(sender) && pr.lastIp != null ? pr.lastIp : "hidden"));
        if (mod != null) sender.sendMessage(mod.sanctions().status(t, pr));
    }

    private String safeGroup(ServerPlayer p) {
        try {
            return plugin.placeholders().luckPerms().group(p);
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    // ------------------------------------------------------------------ items & menus

    /**
     * A workstation without the block. The menu works at the player's feet (items left in it go back to them on close,
     * as at a real block) and never closes for being too far from one.
     */
    private void workstation(Sender sender, String which) {
        ServerPlayer p = self(sender);
        if (p == null) return;
        ContainerLevelAccess at = ContainerLevelAccess.create(p.level(), p.blockPosition());
        String titleKey = switch (which) {
            case "anvil" -> "container.repair";
            case "grindstone" -> "container.grindstone_title";
            case "stonecutter" -> "container.stonecutter";
            case "loom" -> "container.loom";
            case "cartography" -> "container.cartography_table";
            default -> "container.upgrade";
        };
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> switch (which) {
            case "anvil" -> new AnvilMenu(id, inv, at) {
                @Override
                public boolean stillValid(Player player) {
                    return player.isAlive();
                }
            };
            case "grindstone" -> new GrindstoneMenu(id, inv, at) {
                @Override
                public boolean stillValid(Player player) {
                    return player.isAlive();
                }
            };
            case "stonecutter" -> new StonecutterMenu(id, inv, at) {
                @Override
                public boolean stillValid(Player player) {
                    return player.isAlive();
                }
            };
            case "loom" -> new LoomMenu(id, inv, at) {
                @Override
                public boolean stillValid(Player player) {
                    return player.isAlive();
                }
            };
            case "cartography" -> new CartographyTableMenu(id, inv, at) {
                @Override
                public boolean stillValid(Player player) {
                    return player.isAlive();
                }
            };
            default -> (AbstractContainerMenu) new SmithingMenu(id, inv, at) {
                @Override
                public boolean stillValid(Player player) {
                    return player.isAlive();
                }
            };
        }, net.minecraft.network.chat.Component.translatable(titleKey)));
        plugin.text().sound(p, "click");
    }

    private void skull(Sender sender, String[] args) {
        ServerPlayer p = self(sender);
        if (p == null) return;
        String owner = args.length > 0 && Perms.has(p, "feather.skull.others") ? args[0] : Names.name(p);
        // A profile name a client can't decode (over 16 characters, spaces...) kicked the holder on every join.
        if (!net.minecraft.util.StringUtil.isValidPlayerName(owner)) {
            plugin.text().send(p, "general.player-not-found", Text.unparsed("input", owner));
            return;
        }
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        // The client fetches the skin for the name.
        head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(owner));
        if (!p.getInventory().add(head) && !head.isEmpty()) p.drop(head, false, Prediction.SERVER_ONLY);
        plugin.text().send(p, "util.skull", Text.unparsed("name", owner));
        plugin.text().sound(p, "item-pickup");
    }

    private void repair(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = self(sender);
        if (p == null) return;
        boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
        if (all && !Perms.has(p, "feather.repair.all")) {
            text.send(p, "general.no-permission");
            return;
        }
        int fixed = 0;
        List<ItemStack> items = new ArrayList<>();
        if (all) for (int i = 0; i < 41; i++) items.add(p.getInventory().getItem(i));
        else items.add(p.getMainHandItem());
        for (ItemStack it : items) {
            if (it == null || it.isEmpty() || !it.isDamaged()) continue;
            it.setDamageValue(0);
            fixed++;
        }
        if (fixed == 0) text.send(p, "util.repair-none");
        else text.send(p, "util.repair", Text.unparsed("count", fixed));
        if (fixed > 0) p.level().playSound(null, p.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------------ world

    private ServerLevel world(Sender sender) {
        if (sender.isPlayer()) return sender.player().level();
        return Feather.server().overworld();
    }

    /** /day and /night set the clock of the sender's dimension to its day/night marker, as {@code /time set day}. */
    private void time(Sender sender, String which) {
        ServerLevel w = world(sender);
        MinecraftServer server = Feather.server();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(w).withSuppressedOutput(), "time set " + which);
        plugin.text().send(sender, "util." + which, Text.unparsed("world", Worlds.name(w)));
    }

    private void weather(Sender sender, boolean storm) {
        ServerLevel w = world(sender);
        Feather.server().setWeatherParameters(storm ? 0 : 12000, storm ? 6000 : 0, storm, false);
        plugin.text().send(sender, storm ? "util.storm" : "util.sun", Text.unparsed("world", Worlds.name(w)));
    }

    // ------------------------------------------------------------------ staff

    private void sudo(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length < 2) {
            text.send(sender, "util.sudo-usage");
            return;
        }
        ServerPlayer t = other(sender, args[0]);
        if (t == null) return;
        if (Perms.has(t, "feather.sudo.exempt") && sender.isPlayer()) {
            text.send(sender, "util.sudo-exempt");
            return;
        }
        String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        MinecraftServer server = Feather.server();
        if (rest.startsWith("c:")) {
            // An unsigned chat line from them, through the whole chat pipeline (mutes, staff chat, format).
            var msg = net.minecraft.network.chat.PlayerChatMessage.system(rest.substring(2));
            server.getPlayerList().broadcastChatMessage(msg, t, net.minecraft.network.chat.ChatType.bind(net.minecraft.network.chat.ChatType.CHAT, t));
        } else {
            String cmd = rest.startsWith("/") ? rest.substring(1) : rest;
            server.getCommands().performPrefixedCommand(t.createCommandSourceStack(), cmd);
        }
        text.send(sender, "util.sudo", Text.component("target", Names.display(t)), Text.unparsed("command", rest));
    }

    private void tpo(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = self(sender);
        if (p == null) return;
        if (args.length == 0) {
            text.send(p, "util.tpo-usage");
            return;
        }
        ServerPlayer t = other(sender, args[0]);
        if (t == null) return;
        plugin.movePlayer(p, Loc.of(t));
        text.send(p, "util.tpo", Text.component("target", Names.display(t)));
    }

    private void tpall(Sender sender) {
        ServerPlayer p = self(sender);
        if (p == null) return;
        int n = 0;
        for (ServerPlayer o : List.copyOf(plugin.onlinePlayers())) {
            if (o == p) continue;
            plugin.movePlayer(o, Loc.of(p));
            plugin.text().send(o, "util.tpall-moved", Text.component("target", Names.display(p)));
            n++;
        }
        plugin.text().send(p, "util.tpall", Text.unparsed("count", n));
    }

    private void tppos(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = self(sender);
        if (p == null) return;
        if (args.length < 3) {
            text.send(p, "util.tppos-usage");
            return;
        }
        ServerLevel w = args.length > 3 ? Worlds.resolve(args[3]) : p.level();
        if (w == null) {
            text.send(p, "util.unknown-world", Text.unparsed("input", args[3]));
            return;
        }
        try {
            double x = coord(args[0], p.getX());
            double y = coord(args[1], p.getY());
            double z = coord(args[2], p.getZ());
            if (!args[0].contains(".") && !args[0].startsWith("~")) x += 0.5;
            if (!args[2].contains(".") && !args[2].startsWith("~")) z += 0.5;
            // NaN, Infinity and coordinates far past the world border would break the player (or the server).
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > MAX_XZ || Math.abs(z) > MAX_XZ || Math.abs(y) > MAX_Y) {
                text.send(p, "util.tppos-out-of-range");
                return;
            }
            Loc to = new Loc(w, x, y, z, p.getYRot(), p.getXRot());
            plugin.movePlayer(p, to);
            text.send(p, "util.tppos", Text.unparsed("location", Worlds.name(w) + " " + to.coords()));
        } catch (NumberFormatException e) {
            text.send(p, "util.tppos-usage");
        }
    }

    private static double coord(String s, double base) {
        if (s.startsWith("~")) return base + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0);
        return Double.parseDouble(s);
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        String name = CommandBridge.nameOf(label);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        switch (name) {
            case "ping", "playtime", "whois", "sudo", "tpo", "skull" -> {
                if (args.length == 1) out.addAll(plugin.completePlayers(sender, ""));
            }
            case "repair" -> {
                if (args.length == 1) out.add("all");
            }
            case "near" -> {
                if (args.length == 1) out.addAll(List.of("50", "100", "200"));
            }
            case "tppos" -> {
                if (args.length <= 3) out.add("~");
                if (args.length == 4) for (ServerLevel w : Feather.server().getAllLevels()) out.add(Worlds.name(w));
            }
            default -> {
            }
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
