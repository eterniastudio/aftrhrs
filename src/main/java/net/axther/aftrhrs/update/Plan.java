package net.axther.aftrhrs.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a manifest means for this install: which jars to put into the mods folder and which jars they replace.
 *
 * <p>The mods folder is wherever this mod's own jar was loaded from (Fabric's mod origin; {@code mods/}, a launcher's
 * {@code custom_mods/}, ...). Only jars in that folder are ever touched, and only these: this mod's own jar (and any
 * other copy of mod id {@code aftrhrs} there, such as an old Companion jar), and older copies of the dependencies the
 * manifest lists (matched by the mod id in their {@code fabric.mod.json}). Nothing moves to a lower version. A
 * dependency that is loaded from somewhere else (a launcher-managed folder) is left alone; if it is too old for the new
 * version, nothing is staged and {@link #blocked} says why (adding a second copy would stop the game from starting).
 *
 * @param puts     {@code [download name, final path, sha256]} per jar to install
 * @param removes  jars to move out of the mods folder (into the rollback folder)
 * @param blocked  null, or why this release can't be installed here
 */
record Plan(List<String[]> puts, List<Path> removes, List<Manifest.File> downloads, String blocked) {

	/** A jar in the mods folder and the mod id / version its fabric.mod.json declares. */
	record Jar(Path path, String id, String version) {
	}

	static Plan blocked(String why) {
		return new Plan(List.of(), List.of(), List.of(), why);
	}

	/**
	 * @param ownJar      the jar this mod runs from
	 * @param useLoaded   whether to trust Fabric's loaded mods for dependency versions and locations (false when a test
	 *                    run points {@code -Daftrhrs.update.jar} at a copy: the dev run's libraries aren't in a folder)
	 */
	static Plan of(Manifest m, Path ownJar, boolean useLoaded) throws IOException {
		Path folder = ownJar.toAbsolutePath().getParent();
		List<Jar> jars = scan(folder);
		// Minecraft / loader the release needs.
		if (useLoaded) {
			String mc = loadedVersion("minecraft").orElse("");
			if (!m.minecraft().isEmpty() && !matches(m.minecraft(), mc)) return blocked("Aftr Hrs " + m.version() + " is for Minecraft " + m.minecraft() + " (this is " + mc + ")");
			if (!m.minMinecraft().isEmpty() && compare(mc, m.minMinecraft()) < 0) return blocked("Aftr Hrs " + m.version() + " needs Minecraft " + m.minMinecraft() + " or newer");
			String loader = loadedVersion("fabricloader").orElse("");
			if (!m.minLoader().isEmpty() && compare(loader, m.minLoader()) < 0) return blocked("Aftr Hrs " + m.version() + " needs Fabric Loader " + m.minLoader() + " or newer (this is " + loader + ")");
		}
		List<String[]> puts = new ArrayList<>();
		List<Path> removes = new ArrayList<>();
		List<Manifest.File> downloads = new ArrayList<>();
		// The mod itself: replaces its own jar and any other aftrhrs jar in the folder that is older.
		Path modFinal = folder.resolve(m.mod().name());
		puts.add(new String[]{m.mod().name(), modFinal.toString(), m.mod().sha256()});
		downloads.add(m.mod());
		removes.add(ownJar.toAbsolutePath());
		for (Jar j : jars) {
			if (j.id().equals(Updater.MOD_ID) && !same(j.path(), ownJar) && compare(j.version(), m.version()) < 0) removes.add(j.path());
		}
		// Dependencies.
		for (Manifest.File d : m.dependencies()) {
			List<Jar> here = jars.stream().filter(j -> j.id().equals(d.id())).toList();
			String best = null;
			for (Jar j : here) if (best == null || compare(j.version(), best) > 0) best = j.version();
			Optional<ModContainer> loaded = useLoaded ? FabricLoader.getInstance().getModContainer(d.id()) : Optional.empty();
			String loadedVersion = loaded.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
			Path loadedFrom = loaded.flatMap(Plan::path).orElse(null);
			if (loadedVersion != null && (best == null || compare(loadedVersion, best) > 0)) best = loadedVersion;
			if (best != null && compare(best, d.version()) >= 0) continue; // as new or newer: never downgrade
			boolean elsewhere = loadedFrom != null && here.stream().noneMatch(j -> same(j.path(), loadedFrom));
			if (elsewhere) {
				// Loaded from a folder we don't manage: adding a copy here would make two of it. Fine if it is new enough.
				if (d.min().isEmpty() || compare(loadedVersion, d.min()) >= 0) continue;
				return blocked(d.id() + " " + loadedVersion + " (in " + loadedFrom.getParent() + ") is older than Aftr Hrs "
					+ m.version() + " needs (" + d.min() + "); update it there to " + d.version());
			}
			if (loaded.isPresent() && loadedFrom == null && !d.min().isEmpty() && compare(loadedVersion, d.min()) < 0) {
				return blocked(d.id() + " " + loadedVersion + " is bundled elsewhere and older than Aftr Hrs " + m.version() + " needs (" + d.min() + ")");
			}
			puts.add(new String[]{d.name(), folder.resolve(d.name()).toString(), d.sha256()});
			downloads.add(d);
			for (Jar j : here) removes.add(j.path()); // every copy here is older (best < d.version)
		}
		return new Plan(puts, removes, downloads, null);
	}

	/** Every {@code *.jar} directly in {@code folder} with the mod id and version from its fabric.mod.json. */
	static List<Jar> scan(Path folder) {
		List<Jar> out = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, "*.jar")) {
			for (Path p : ds) {
				if (!Files.isRegularFile(p)) continue;
				JsonObject meta = metadata(p);
				if (meta != null) out.add(new Jar(p.toAbsolutePath(), Manifest.str(meta, "id"), Manifest.str(meta, "version")));
			}
		} catch (IOException ignored) {
		}
		return out;
	}

	/** The fabric.mod.json of a jar, or null when it has none or isn't a readable jar. */
	static JsonObject metadata(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry("fabric.mod.json");
			if (e == null || e.getSize() > 1 << 20) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return JsonParser.parseString(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The plan file {@link Swap} runs. */
	String text(Path staged, String version) {
		StringBuilder sb = new StringBuilder("version\t").append(version).append('\n');
		for (String[] p : puts) sb.append("put\t").append(staged.resolve(p[0]).toAbsolutePath()).append('\t').append(p[1]).append('\t').append(p[2]).append('\n');
		for (Path r : removes) sb.append("remove\t").append(r).append('\n');
		return sb.toString();
	}

	private static Optional<Path> path(ModContainer c) {
		if (c.getOrigin().getKind() != ModOrigin.Kind.PATH) return Optional.empty();
		return c.getOrigin().getPaths().stream().filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".jar")).findFirst();
	}

	static Optional<String> loadedVersion(String id) {
		return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString());
	}

	private static boolean same(Path a, Path b) {
		try {
			return Files.isSameFile(a, b);
		} catch (IOException e) {
			return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
		}
	}

	private static boolean matches(String predicate, String version) {
		try {
			return VersionPredicate.parse(predicate).test(Version.parse(version));
		} catch (VersionParsingException | RuntimeException e) {
			return false;
		}
	}

	/** Fabric's version order (semantic where both parse as semver), else dotted numbers. */
	static int compare(String a, String b) {
		try {
			Version x = Version.parse(a), y = Version.parse(b);
			if (x instanceof SemanticVersion sx && y instanceof SemanticVersion sy) return sx.compareTo((Version) sy);
		} catch (VersionParsingException | RuntimeException ignored) {
		}
		return Updater.compareLoose(a, b);
	}
}
