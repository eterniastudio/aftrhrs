package net.axther.aftrhrs.feature.display;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.LuckPermsHook;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server-list entry (motd.yml): rotating or random two-line MOTDs with gradients, the player-count hover,
 * max players, server icons from {@code aftrhrs/icons/}, and maintenance mode ({@code /maintenance}), which swaps the
 * MOTD, shows a red version label and keeps out everyone without {@code feather.maintenance.bypass}.
 *
 * <p>Status pings are answered on a network thread ({@link FeatherEvents#SERVER_STATUS}); everything they read about
 * players is prepared on the server thread once a second.
 */
public final class MotdModule implements FeatherModule, FeatherCommand {
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private static volatile MotdModule active;

    private final Feather plugin;
    private volatile Yaml cfg = new Yaml();
    private volatile List<List<String>> motds = List.of();
    private volatile boolean random;
    private final AtomicInteger index = new AtomicInteger();
    private volatile List<byte[]> icons = List.of();
    private final AtomicInteger iconIndex = new AtomicInteger();
    private volatile boolean maintenance;
    private Path stateFile;
    /** The online, not vanished players' hover lines (prepared on the server thread). */
    private volatile List<String> playerLines = List.of();
    private volatile List<UUID> playerIds = List.of();
    private Tasks.Task sampler;

    public MotdModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Server list MOTD";
    }

    @Override
    public void enable() {
        stateFile = plugin.dataFile("maintenance.yml");
        maintenance = Files.exists(stateFile) && Yaml.load(stateFile).getBoolean("enabled", false);
        reload();
        active = this;
        plugin.bind("maintenance", this);
        sampler = Tasks.timer(1L, 20L, this::sample);
        plugin.once("motd.events", () -> {
            FeatherEvents.SERVER_STATUS.register(status -> {
                MotdModule m = active;
                return m == null ? status : m.status(status);
            });
            FeatherEvents.PLAYER_LOGIN.register((profile, address, current) -> {
                MotdModule m = active;
                if (m == null || current != null || !m.maintenance || m.mayJoin(profile)) return current;
                return Adv.vanilla(m.kickMessage());
            });
        });
        if (maintenance) plugin.getLogger().warn("Maintenance mode is ON: only players with feather.maintenance.bypass can join.");
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (sampler != null) sampler.cancel();
    }

    @Override
    public void reload() {
        Yaml c = plugin.loadResourceConfig("motd.yml");
        cfg = c;
        motds = readMotds(c, "motds");
        random = c.getString("order", "random").equalsIgnoreCase("random");
        index.set(0);
        List<byte[]> list = new ArrayList<>();
        Path folder = plugin.getDataFolder().resolve("icons");
        try {
            Files.createDirectories(folder);
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(folder, "*.{png,PNG}")) {
                for (Path f : dir) files.add(f);
            }
            files.sort(null);
            for (Path f : files) {
                byte[] png = Files.readAllBytes(f);
                if (pngSize(png) != 64) {
                    plugin.getLogger().warn("Server icon {} must be a 64x64 PNG.", f.getFileName());
                    continue;
                }
                list.add(png);
            }
        } catch (IOException e) {
            plugin.getLogger().warn("Could not read the server icons: {}", e.toString());
        }
        icons = List.copyOf(list);
    }

    /** The width of a square PNG (its IHDR), or -1. */
    private static int pngSize(byte[] png) {
        if (png.length < 24 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return -1;
        int w = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int h = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        return w == h ? w : -1;
    }

    private static List<List<String>> readMotds(Yaml cfg, String path) {
        List<List<String>> out = new ArrayList<>();
        for (Object o : cfg.getList(path, List.of())) {
            if (o instanceof List<?> l) {
                List<String> lines = new ArrayList<>();
                for (Object line : l) lines.add(String.valueOf(line));
                out.add(lines);
            } else if (o != null) {
                out.add(List.of(String.valueOf(o).split("\n", 2)));
            }
        }
        return out;
    }

    public boolean maintenance() {
        return maintenance;
    }

    // ------------------------------------------------------------------ ping

    /** The players' hover lines, on the server thread. */
    private void sample() {
        List<String> lines = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        String fmt = cfg.getString("hover-player", "<muted>· </muted><name>");
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (plugin.profiles().get(p).vanished) continue;
            if (lines.size() >= 12) break;
            lines.add(SECTION.serialize(plugin.text().parse(fmt, Text.component("name", Names.display(p)))));
            ids.add(p.getUUID());
        }
        playerLines = lines;
        playerIds = ids;
    }

    private ServerStatus status(ServerStatus status) {
        Yaml c = cfg;
        Placeholders ph = plugin.placeholders();
        List<String> lines;
        if (maintenance) {
            lines = c.getStringList("maintenance.motd");
        } else if (motds.isEmpty()) {
            lines = List.of();
        } else {
            List<List<String>> all = motds;
            int i = random ? ThreadLocalRandom.current().nextInt(all.size()) : Math.floorMod(index.getAndIncrement(), all.size());
            lines = all.get(i);
        }
        net.minecraft.network.chat.Component description = status.description();
        if (!lines.isEmpty()) {
            Component motd = ph.format(lines.get(0), null, null);
            if (lines.size() > 1) motd = motd.appendNewline().append(ph.format(lines.get(1), null, null));
            description = Adv.vanilla(motd);
        }

        Optional<ServerStatus.Players> players = status.players();
        if (players.isPresent()) {
            ServerStatus.Players p = players.get();
            int online = p.online();
            int max = c.getInt("max-players", -1);
            if (c.getBoolean("one-more-slot", false)) max = online + 1;
            if (max < 0) max = p.max();
            List<NameAndId> sample = p.sample();
            List<String> hover = c.getStringList(maintenance ? "maintenance.hover" : "hover");
            if (!hover.isEmpty() || maintenance) {
                sample = new ArrayList<>();
                for (String line : hover) {
                    sample.add(new NameAndId(UUID.randomUUID(), SECTION.serialize(ph.format(line.replace("<players>", ""), null, null))));
                    if (line.contains("<players>")) {
                        List<String> names = playerLines;
                        List<UUID> ids = playerIds;
                        for (int i = 0; i < names.size(); i++) sample.add(new NameAndId(ids.get(i), names.get(i)));
                    }
                }
            }
            players = c.getBoolean("hide-player-count", false) ? Optional.empty() : Optional.of(new ServerStatus.Players(max, online, sample));
        }

        Optional<ServerStatus.Version> version = status.version();
        if (maintenance) {
            version = Optional.of(new ServerStatus.Version(SECTION.serialize(plugin.text().parse(c.getString("maintenance.version-label", "<bad>Maintenance"))), -1));
        } else {
            String label = c.getString("version-label", "");
            if (!label.isEmpty()) {
                int protocol = version.map(ServerStatus.Version::protocol).orElse(ServerStatus.Version.current().protocol());
                version = Optional.of(new ServerStatus.Version(SECTION.serialize(ph.format(label, null, null)), protocol));
            }
        }

        Optional<ServerStatus.Favicon> favicon = status.favicon();
        List<byte[]> all = icons;
        if (!all.isEmpty()) {
            byte[] icon = c.getString("icon-order", "random").equalsIgnoreCase("random")
                    ? all.get(ThreadLocalRandom.current().nextInt(all.size()))
                    : all.get(Math.floorMod(iconIndex.getAndIncrement(), all.size()));
            favicon = Optional.of(new ServerStatus.Favicon(icon));
        }
        return new ServerStatus(description, players, version, favicon, status.enforcesSecureChat());
    }

    // ------------------------------------------------------------------ maintenance

    private boolean mayJoin(NameAndId profile) {
        for (String allowed : cfg.getStringList("maintenance.allowed-players")) if (allowed.equalsIgnoreCase(profile.name())) return true;
        if (Feather.server().getPlayerList().isOp(profile)) return true;
        LuckPermsHook lp = plugin.placeholders().luckPerms();
        if (lp != null) {
            try {
                Boolean has = lp.has(profile.id(), "feather.maintenance.bypass");
                if (has != null) return has;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Component kickMessage() {
        List<String> lines = cfg.getStringList("maintenance.kick-message");
        return plugin.placeholders().format(String.join("\n", lines), null, null);
    }

    private void setMaintenance(boolean on) {
        maintenance = on;
        Yaml yml = new Yaml();
        yml.set("enabled", on);
        try {
            Files.createDirectories(stateFile.getParent());
            yml.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().warn("Could not save maintenance state: {}", e.getMessage());
        }
        if (on && cfg.getBoolean("maintenance.kick-online", true)) {
            for (ServerPlayer p : List.copyOf(plugin.onlinePlayers())) {
                if (!Perms.has(p, "feather.maintenance.bypass") && !Perms.isOp(p)) p.connection.disconnect(Adv.vanilla(kickMessage()));
            }
        }
    }

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        String arg = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean on;
        switch (arg) {
            case "on", "true", "enable" -> on = true;
            case "off", "false", "disable" -> on = false;
            case "" -> on = !maintenance;
            default -> {
                text.send(sender, "motd.maintenance-usage");
                return true;
            }
        }
        setMaintenance(on);
        text.send(sender, on ? "motd.maintenance-on" : "motd.maintenance-off");
        if (on) {
            for (ServerPlayer p : plugin.onlinePlayers()) {
                if (p != sender.player() && Perms.has(p, "feather.maintenance")) text.send(p, "motd.maintenance-on");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("on", "off"));
        out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return out;
    }
}
