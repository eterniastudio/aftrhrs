package net.axther.aftrhrs.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Applies a staged update: puts the downloaded jars into the mods folder and moves the jars they replace into a rollback
 * folder. JDK classes only and no nested classes: besides being called by {@link Updater} as the game or server exits,
 * this one class file is copied out of the mod and run on its own ({@code java -cp <dir> net.axther.aftrhrs.update.Swap
 * ...}) when Windows still holds the old jars open, waiting for the game to exit first.
 *
 * <p>The plan is a text file, one step per line, tab separated:
 * <pre>
 * version  1.0.1
 * put      &lt;staged jar&gt;  &lt;final jar in the mods folder&gt;  &lt;sha256&gt;
 * remove   &lt;jar in the mods folder&gt;
 * </pre>
 * All or nothing, and the mods folder never holds two copies of a mod (Fabric refuses to start then): every staged jar
 * is checked against its SHA-256 and copied next to its final name as {@code .part} (not a {@code .jar}, so never
 * loaded); then every jar being replaced is moved into the rollback folder; only then is each {@code .part} renamed to
 * its final {@code .jar}. Any failure puts every moved jar back and deletes the parts.
 */
public final class Swap {
	private Swap() {
	}

	/**
	 * {@code <pid> <plan file> <rollback dir> <log file> [relaunch file]}. The relaunch file (first line the working
	 * folder, then one argument per line) reopens the game once the update is in (or couldn't be put in: the player
	 * still gets their game back); it is deleted as soon as it's read, since it holds the session token.
	 */
	public static void main(String[] args) throws Exception {
		if (args.length < 4) return;
		long pid = Long.parseLong(args[0]);
		Path plan = Path.of(args[1]), rollback = Path.of(args[2]), log = Path.of(args[3]);
		var game = ProcessHandle.of(pid);
		if (game.isPresent()) {
			try {
				game.get().onExit().get(30, TimeUnit.MINUTES);
			} catch (Exception e) {
				log(log, "gave up waiting for the game to exit: " + e);
				return;
			}
		}
		String result = null;
		for (int attempt = 0; attempt < 40; attempt++) {
			result = apply(plan, rollback, log);
			if (result == null) break;
			Thread.sleep(250); // the OS may need a moment to release the jars
		}
		if (result != null) log(log, "update not applied: " + result);
		if (args.length >= 5) relaunch(Path.of(args[4]), rollback, log);
	}

	/** Starts the game again from the relaunch file (see {@link #main}). */
	static void relaunch(Path file, Path rollback, Path log) {
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			log(log, "couldn't reopen the game: " + e);
			return;
		} finally {
			quietDelete(file);
		}
		if (lines.size() < 2) return;
		List<String> cmd = new ArrayList<>(lines.subList(1, lines.size()));
		while (!cmd.isEmpty() && cmd.get(cmd.size() - 1).isEmpty()) cmd.remove(cmd.size() - 1);
		try {
			String fixed = fixModList(cmd, rollback, rollback.resolveSibling("relaunch-mods.txt"));
			if (fixed != null) log(log, "relaunch: " + fixed);
		} catch (IOException | RuntimeException e) {
			log(log, "relaunch: couldn't update the launcher's mod list: " + e);
		}
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd).directory(Path.of(lines.get(0)).toFile());
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
			pb.start();
			log(log, "reopened the game");
		} catch (IOException | RuntimeException e) {
			log(log, "couldn't reopen the game: " + e);
		}
	}

	/**
	 * Launchers that hand Fabric an exact list of jars ({@code -Dfabric.addMods=a.jar;@list.txt}, e.g. NoRisk, whose
	 * own mods live outside the mods folder) would reopen the game with the jars the update just replaced, so the game
	 * starts without the mod. This rewrites that list for the reopened game: a listed jar that is gone becomes the jar the
	 * update put next to it (the same mod: same name without its version), and a jar the last update added beside listed
	 * ones is added. The new list goes to {@code listFile} and the argument becomes {@code -Dfabric.addMods=@listFile};
	 * the launcher's own files are never touched. Returns what changed, or null when the list was already right (or there
	 * is none).
	 */
	static String fixModList(List<String> cmd, Path rollback, Path listFile) throws IOException {
		String prefix = "-Dfabric.addMods=";
		int at = -1;
		for (int i = 0; i < cmd.size(); i++) if (cmd.get(i).startsWith(prefix)) at = i;
		if (at < 0) return null;
		List<String> entries = new ArrayList<>();
		for (String part : cmd.get(at).substring(prefix.length()).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
			part = part.trim();
			if (part.isEmpty()) continue;
			if (part.startsWith("@")) {
				Path list = Path.of(part.substring(1));
				if (!Files.isRegularFile(list)) continue;
				for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
					line = line.trim();
					if (!line.isEmpty() && !line.startsWith("#")) entries.add(line);
				}
			} else {
				entries.add(part);
			}
		}
		// what the last update put in (ROLLBACK.txt, "added<TAB>path")
		List<Path> added = new ArrayList<>();
		Path note = rollback.resolve("ROLLBACK.txt");
		if (Files.isRegularFile(note)) {
			for (String line : Files.readAllLines(note, StandardCharsets.UTF_8)) {
				String[] f = line.split("\t");
				if (f.length >= 2 && f[0].equals("added")) added.add(Path.of(f[1]).toAbsolutePath().normalize());
			}
		}
		java.util.LinkedHashSet<Path> out = new java.util.LinkedHashSet<>();
		java.util.Set<Path> dirs = new java.util.HashSet<>();
		List<String> notes = new ArrayList<>();
		for (String e : entries) {
			Path p = Path.of(e).toAbsolutePath().normalize();
			if (p.getParent() != null) dirs.add(p.getParent());
			if (Files.exists(p) || !p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
				out.add(p);
				continue;
			}
			Path now = successor(p, added);
			if (now != null) {
				out.add(now);
				notes.add(p.getFileName() + " -> " + now.getFileName());
			} else {
				notes.add("dropped " + p.getFileName());
			}
		}
		for (Path a : added) {
			if (Files.isRegularFile(a) && a.getParent() != null && dirs.contains(a.getParent()) && out.add(a)) notes.add("added " + a.getFileName());
		}
		if (notes.isEmpty()) return null;
		StringBuilder sb = new StringBuilder();
		for (Path p : out) sb.append(p).append(System.lineSeparator());
		Files.createDirectories(listFile.toAbsolutePath().getParent());
		Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);
		cmd.set(at, prefix + "@" + listFile.toAbsolutePath());
		return String.join(", ", notes);
	}

	/** The jar that took the place of {@code gone}: one the update added (same folder first), else the newest in its folder. */
	private static Path successor(Path gone, List<Path> added) {
		String key = modKey(gone.getFileName().toString());
		Path best = null;
		for (Path a : added) {
			if (!Files.isRegularFile(a) || !modKey(a.getFileName().toString()).equals(key)) continue;
			if (best == null || a.getParent() != null && a.getParent().equals(gone.getParent())) best = a;
		}
		if (best != null) return best;
		Path dir = gone.getParent();
		if (dir == null || !Files.isDirectory(dir)) return null;
		long newest = Long.MIN_VALUE;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
			for (Path p : ds) {
				if (!modKey(p.getFileName().toString()).equals(key)) continue;
				long t = Files.getLastModifiedTime(p).toMillis();
				if (t > newest) {
					newest = t;
					best = p.toAbsolutePath().normalize();
				}
			}
		} catch (IOException ignored) {
		}
		return best;
	}

	/** A jar's name without its version: {@code aftrhrs-5.4.7.jar} and {@code aftrhrs-5.5.0.jar} are both {@code aftrhrs}. */
	static String modKey(String jarName) {
		String n = jarName.toLowerCase(java.util.Locale.ROOT);
		if (n.endsWith(".jar")) n = n.substring(0, n.length() - 4);
		return n.replaceFirst("[-_+]v?\\d.*$", "");
	}

	/**
	 * Runs {@code plan}. Returns null when done (or there was nothing to do), else why not; in that case nothing in the
	 * mods folder changed and the plan is kept for the next try.
	 */
	public static String apply(Path plan, Path rollback, Path log) {
		List<String[]> puts = new ArrayList<>();
		List<Path> removes = new ArrayList<>();
		String version = "?";
		try {
			if (!Files.isRegularFile(plan)) return null;
			for (String line : Files.readAllLines(plan, StandardCharsets.UTF_8)) {
				String[] f = line.split("\t");
				if (f.length >= 2 && f[0].equals("version")) version = f[1];
				else if (f.length >= 4 && f[0].equals("put")) puts.add(f);
				else if (f.length >= 2 && f[0].equals("remove")) removes.add(Path.of(f[1]));
			}
		} catch (IOException | RuntimeException e) {
			return "unreadable plan (" + e + ")";
		}
		if (puts.isEmpty()) return "the plan installs nothing";
		// 1. Every download is still there and intact.
		for (String[] p : puts) {
			try {
				if (!Files.isRegularFile(Path.of(p[1]))) return "missing download " + p[1];
				if (!p[3].equalsIgnoreCase(sha256(Path.of(p[1])))) return "damaged download " + p[1];
			} catch (IOException e) {
				return "can't read " + p[1] + " (" + e + ")";
			}
		}
		// A final name that is already taken (same file name, other content) is replaced like any other old jar.
		for (String[] p : puts) {
			Path fin = Path.of(p[2]);
			if (Files.exists(fin) && !removes.contains(fin)) removes.add(fin);
		}
		List<Path> parts = new ArrayList<>(), moved = new ArrayList<>(), placed = new ArrayList<>();
		List<Path> movedTo = new ArrayList<>();
		Path fresh = rollback.resolveSibling(rollback.getFileName() + "-new");
		try {
			// 2. Copy the new jars next to their final names.
			for (String[] p : puts) {
				Path part = Path.of(p[2] + ".part");
				Files.createDirectories(part.getParent());
				Files.copy(Path.of(p[1]), part, StandardCopyOption.REPLACE_EXISTING);
				parts.add(part);
			}
			// 3. Move the old jars out of the mods folder (into a fresh folder: the last rollback copy stays until this works).
			clear(fresh);
			Files.createDirectories(fresh);
			for (Path old : removes) {
				if (!Files.exists(old)) continue;
				Path to = fresh.resolve(old.getFileName().toString());
				Files.move(old, to, StandardCopyOption.REPLACE_EXISTING);
				moved.add(old);
				movedTo.add(to);
			}
			// 4. The new jars take their final names.
			for (int i = 0; i < puts.size(); i++) {
				Path fin = Path.of(puts.get(i)[2]);
				Files.move(parts.get(i), fin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				placed.add(fin);
			}
		} catch (IOException | RuntimeException e) {
			// Put everything back the way it was.
			for (Path p : placed) quietDelete(p);
			for (int i = moved.size() - 1; i >= 0; i--) {
				try {
					if (!Files.exists(moved.get(i))) Files.move(movedTo.get(i), moved.get(i));
				} catch (IOException ignored) {
				}
			}
			for (Path p : parts) quietDelete(p);
			return "the mods folder is in use (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
		}
		// 5. Done: the replaced jars become the rollback copy; note what changed and drop the plan and its downloads.
		try {
			clear(rollback);
			Files.createDirectories(rollback);
			for (Path p : movedTo) Files.move(p, rollback.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
			Files.deleteIfExists(fresh);
		} catch (IOException ignored) {
			// The old jars stay in the -new folder; still out of the mods folder, still a rollback copy.
		}
		StringBuilder note = new StringBuilder();
		note.append("Aftr Hrs update to ").append(version).append(", applied ").append(Instant.now()).append(System.lineSeparator())
			.append("The jars in this folder are the ones the update replaced. To roll back: close the game (or stop the server),")
			.append(System.lineSeparator()).append("delete the jars listed under \"added\" and move the jars in this folder back to where \"removed\" says,")
			.append(System.lineSeparator()).append("then turn automatic updates off so it doesn't update again.").append(System.lineSeparator());
		for (Path p : placed) note.append("added\t").append(p).append(System.lineSeparator());
		for (Path p : moved) note.append("removed\t").append(p).append(System.lineSeparator());
		try {
			Files.writeString(rollback.resolve("ROLLBACK.txt"), note.toString(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
		for (String[] p : puts) quietDelete(Path.of(p[1]));
		quietDelete(plan);
		log(log, "updated to " + version + ": added " + names(placed) + ", replaced " + names(moved));
		return null;
	}

	private static String names(List<Path> paths) {
		List<String> out = new ArrayList<>();
		for (Path p : paths) out.add(p.getFileName().toString());
		return out.toString();
	}

	/** Empties the rollback folder: only the last update's old jars are kept. */
	private static void clear(Path dir) {
		if (!Files.isDirectory(dir)) return;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) if (Files.isRegularFile(p)) quietDelete(p);
		} catch (IOException ignored) {
		}
	}

	private static void quietDelete(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (IOException ignored) {
		}
	}

	public static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[65536];
			for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
			return HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	static void log(Path log, String line) {
		try {
			Files.createDirectories(log.getParent());
			Files.writeString(log, Instant.now() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
		}
	}
}
