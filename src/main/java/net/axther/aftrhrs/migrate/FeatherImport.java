package net.axther.aftrhrs.migrate;

import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * First start after moving from Paper: copies the Feather plugin folder ({@code plugins/Feather/}, configs and
 * {@code data/}) into {@code aftrhrs/}, whose layout is the same. Runs before the Feather core starts, only while
 * {@code aftrhrs/data} is still empty, and never overwrites. Jars and caches are skipped.
 *
 * <p>Looks in {@code <server>/plugins/Feather} (copy the old {@code plugins} folder next to the Fabric server), or the
 * path in the system property {@code aftrhrs.importFrom}.
 */
public final class FeatherImport {
    private FeatherImport() {
    }

    /** Must be registered before {@code Feather.boot()} so it runs first on SERVER_STARTING. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(s -> run());
    }

    static void run() {
        Path game = FabricLoader.getInstance().getGameDir();
        Path target = game.resolve("aftrhrs");
        String prop = System.getProperty("aftrhrs.importFrom");
        Path source = prop != null ? Path.of(prop) : game.resolve("plugins").resolve("Feather");
        if (!Files.isDirectory(source)) return;
        if (hasData(target.resolve("data"))) return;
        if (Files.exists(target.resolve("imported-from-paper.txt"))) return;
        AftrHrs.LOG.info("Importing Feather data from {} into {}", source, target);
        int[] count = {0};
        try {
            Files.createDirectories(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(source) && (n.equals("cache") || n.equals("logs") || n.startsWith("."))) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String n = file.getFileName().toString().toLowerCase();
                    if (n.endsWith(".jar") || n.endsWith(".tmp") || n.endsWith(".lock")) return FileVisitResult.CONTINUE;
                    Path to = target.resolve(source.relativize(file).toString());
                    if (!Files.exists(to)) {
                        Files.copy(file, to);
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            Files.writeString(target.resolve("imported-from-paper.txt"),
                    "Imported " + count[0] + " files from " + source.toAbsolutePath() + " at " + Instant.now() + "\n");
            AftrHrs.LOG.info("Imported {} Feather files.", count[0]);
        } catch (IOException e) {
            AftrHrs.LOG.error("Feather import failed; the server starts with whatever was copied.", e);
        }
    }

    private static boolean hasData(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isPresent();
        } catch (IOException e) {
            return true;
        }
    }
}
