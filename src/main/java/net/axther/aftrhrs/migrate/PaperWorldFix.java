package net.axther.aftrhrs.migrate;

import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Makes a world saved by Paper 26.x loadable by the vanilla/Fabric server, before the server opens it (runs from the
 * mod initializer on dedicated servers).
 *
 * <ul>
 *   <li>Paper keeps the server-wide saved data (world gen settings = the seed and dimensions, game rules, weather,
 *       wandering trader, scheduled events, clocks) inside {@code dimensions/minecraft/overworld/data/minecraft/};
 *       vanilla reads them from {@code <world>/data/minecraft/}. Copied over when missing there, otherwise the server
 *       would fall back to a random seed.</li>
 *   <li>Pre-26 Paper layouts ({@code world_nether/DIM-1}, {@code world_the_end/DIM1}) are copied into
 *       {@code dimensions/minecraft/the_nether|the_end} when those are missing.</li>
 * </ul>
 * Nothing is deleted; Paper-only files ({@code data/paper/}, {@code paper-world.yml}) are left in place and ignored.
 */
public final class PaperWorldFix {
    private static final List<String> SERVER_WIDE = List.of(
            "world_gen_settings.dat", "game_rules.dat", "weather.dat", "wandering_trader.dat", "scheduled_events.dat",
            "world_clocks.dat");

    private PaperWorldFix() {
    }

    public static void run() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
        Path game = FabricLoader.getInstance().getGameDir();
        String levelName = "world";
        Path props = game.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
                p.load(r);
                levelName = p.getProperty("level-name", "world").trim();
            } catch (IOException ignored) {
            }
        }
        Path world = game.resolve(levelName);
        if (!Files.isDirectory(world)) return;
        try {
            Path paperData = world.resolve("dimensions/minecraft/overworld/data/minecraft");
            Path vanillaData = world.resolve("data/minecraft");
            if (Files.isDirectory(paperData)) {
                for (String f : SERVER_WIDE) {
                    Path from = paperData.resolve(f);
                    Path to = vanillaData.resolve(f);
                    if (Files.isRegularFile(from) && !Files.exists(to)) {
                        Files.createDirectories(vanillaData);
                        Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                        AftrHrs.LOG.info("Paper world: copied {} into {}", f, vanillaData);
                    }
                }
            }
            legacyDimension(game.resolve(levelName + "_nether").resolve("DIM-1"), world.resolve("dimensions/minecraft/the_nether"));
            legacyDimension(game.resolve(levelName + "_the_end").resolve("DIM1"), world.resolve("dimensions/minecraft/the_end"));
            legacyDimension(world.resolve("DIM-1"), world.resolve("dimensions/minecraft/the_nether"));
            legacyDimension(world.resolve("DIM1"), world.resolve("dimensions/minecraft/the_end"));
        } catch (IOException e) {
            AftrHrs.LOG.error("Could not prepare the Paper world for loading", e);
        }
    }

    private static void legacyDimension(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from.resolve("region")) || Files.isDirectory(to.resolve("region"))) return;
        AftrHrs.LOG.info("Paper world: copying {} to {}", from, to);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else if (!Files.exists(dst) && !src.getFileName().toString().equals("session.lock")) Files.copy(src, dst);
            }
        }
    }
}
