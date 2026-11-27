package net.axther.aftrhrs.update;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeps the Aftr Hrs mod (and the GeckoLib / Fabric API it needs) up to date from the public releases-only repo
 * {@link #REPO}, on clients and dedicated servers alike. The same jar runs both, so this class only uses common code;
 * the client's settings and toasts are in {@code client/update/Updater}, the server's config and console command in
 * {@link ServerUpdates}.
 *
 * <p>A check reads the latest release's {@code manifest.json} and {@code manifest.json.sig} (through
 * {@code github.com/.../releases/latest/download/}, which isn't subject to the GitHub API's rate limit) and trusts it
 * only if the Ed25519 signature verifies against {@link #PUBLIC_KEY} ({@link Manifest}). If the release is newer than
 * the running mod, {@link Plan} works out which jars to put into the mods folder (the folder this mod's jar was loaded
 * from, whatever the launcher calls it) and which older jars they replace. The jars are downloaded in the background into
 * {@code config/aftrhrs/updates/staged/} as {@code .part} files, checked against the signed size and SHA-256 (and their
 * fabric.mod.json id and version), and only then named. The verified manifest and the plan stay in the staging folder.
 *
 * <p>Nothing changes while the game runs. As the JVM exits, {@link Swap} applies the plan; when Windows still holds the
 * jars open, a copy of {@link Swap} runs as a small separate process that waits for the game to exit. The jars it
 * replaces go to {@code config/aftrhrs/updates/rollback/} (with {@code ROLLBACK.txt}). A start that still finds a staged
 * update (the game crashed, or the swap couldn't run) re-checks it against the signature and the mods folder, and tries
 * again at that exit.
 *
 * <p>Clients check every {@link #CLIENT_EVERY} seconds while the game runs (servers every 6 hours), so a release is
 * downloaded within minutes. {@link #restart} asks for the game to be reopened after the swap: the helper process
 * waits for this one to exit, installs the update, then starts the command it was given (the same game, see the
 * client's {@code Restart}); that file holds the launch arguments (the session token too) and is deleted as it's read.
 *
 * <p>Testing: {@code -Daftrhrs.update.releases=http://127.0.0.1:8765/} reads {@code latest/download/manifest.json} there
 * instead (the signature check still applies), {@code -Daftrhrs.update.jar=<a copy of the jar>} makes a dev run update
 * that copy and its folder.
 */
public final class Updater {
	public static final String MOD_ID = "aftrhrs";
	public static final String REPO = "eterniastudio/aftrhrs";
	public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
	/**
	 * Raw Ed25519 public key (base64) of the Aftr Hrs mod release key ({@code ~/.config/aftrhrs/aftrhrs-release-ed25519.pem}
	 * on the release machine). Not the old Companion key: neither updater can accept the other's releases.
	 */
	public static final String PUBLIC_KEY = "pTOmhfKsBFTF7NDz1kFMtW6ssXf3aa7MAhUObr+OaHU=";
	private static final String RELEASES = slash(System.getProperty("aftrhrs.update.releases", "https://github.com/" + REPO + "/releases/"));
	private static final Logger LOG = LoggerFactory.getLogger("Aftr Hrs updater");
	private static final long HOURS = 3600;
	/** Seconds between checks on a client (every 6 hours on a server). Fetching the signed manifest is cheap. */
	public static final long CLIENT_EVERY = 15 * 60;

	public enum Side {CLIENT, SERVER}

	/** AUTO: check and stage. NOTIFY: check only (stage on request). OFF: never contact GitHub on its own. */
	public enum Mode {AUTO, NOTIFY, OFF}

	public enum Status {DEV, IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED}

	/** Told about each status change, from the updater's thread. */
	public interface Listener {
		void changed(Status status, String version, String detail);
	}

	private static volatile Status status = Status.IDLE;
	private static volatile String detail = "";
	private static volatile Manifest found;
	private static volatile Side side;
	private static Supplier<Mode> mode = () -> Mode.OFF;
	private static Listener listener = (s, v, d) -> {
	};
	private static Path jar, dir;
	private static boolean forced;
	private static ScheduledExecutorService exec;
	private static ScheduledFuture<?> next;
	/** The command to reopen the game with after the update (set by {@link #restart}), or null. */
	private static volatile Path relaunch;

	private Updater() {
	}

	// ------------------------------------------------------------------ public

	public static Status status() {
		return status;
	}

	/** Why the last check ended as it did ("" when there's nothing to say). */
	public static String detail() {
		return detail;
	}

	/** The version found or staged, or the running one. */
	public static String version() {
		Manifest m = found;
		return m != null ? m.version() : current();
	}

	/** The staged (downloaded and verified) version, or null. */
	public static String staged() {
		Manifest m = found;
		return status == Status.READY && m != null ? m.version() : null;
	}

	public static String current() {
		return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	/** The jar this mod runs from, or empty in a development run. */
	public static Optional<Path> jar() {
		return Optional.ofNullable(jar);
	}

	/** The staging folder ({@code config/aftrhrs/updates}). */
	public static Path dir() {
		return dir;
	}

	/** Check now; download too when {@code stage}. Ignored while busy. With an update staged, only a newer one is fetched. */
	public static void check(boolean stage) {
		if (exec == null || status == Status.DEV || status == Status.CHECKING || status == Status.DOWNLOADING) return;
		exec.execute(() -> run(stage));
	}

	/** Stage what a check found (notify mode's "Install"). */
	public static void install() {
		if (exec == null || status != Status.AVAILABLE) return;
		exec.execute(() -> run(true));
	}

	/** Drops a staged update (nothing is installed at exit). */
	public static void cancel() {
		if (exec == null) return;
		exec.execute(() -> {
			if (status != Status.READY) return;
			clearStaged();
			found = null;
			set(Status.IDLE, "");
		});
	}

	/**
	 * Reopen the game with {@code command} (in {@code workDir}) once it has exited and the staged update (if any) is in.
	 * The caller then closes the game normally. False when it can't be arranged (the game then just closes).
	 */
	public static boolean restart(List<String> command, Path workDir) {
		if (dir == null || command.isEmpty()) return false;
		try {
			Files.createDirectories(dir);
			Path f = dir.resolve("relaunch.txt");
			StringBuilder sb = new StringBuilder(workDir.toAbsolutePath().toString()).append('\n');
			for (String a : command) {
				if (a.indexOf('\n') >= 0 || a.indexOf('\r') >= 0) return false;
				sb.append(a).append('\n');
			}
			Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
			relaunch = f;
			return true;
		} catch (IOException e) {
			LOG.warn("Couldn't arrange the restart: {}", e.toString());
			return false;
		}
	}

	/** {@code mode} is asked before every scheduled check; a change of mode should call {@link #modeChanged()}. */
	public static void modeChanged() {
		Mode m = mode.get();
		if (m == Mode.OFF || exec == null) return;
		if (status == Status.IDLE || status == Status.FAILED || status == Status.UP_TO_DATE || status == Status.AVAILABLE && m == Mode.AUTO) {
			exec.execute(() -> run(m == Mode.AUTO));
		}
	}

	// ------------------------------------------------------------------ start

	/**
	 * Starts the updater once per JVM: finds the jar, re-checks a staged update from an earlier session, hooks the exit
	 * and schedules checks (soon after start, then every 6 hours) whenever {@code mode} isn't OFF.
	 */
	public static synchronized void init(Side s, Supplier<Mode> m, Listener l) {
		if (side != null) return;
		side = s;
		mode = m;
		listener = l;
		dir = FabricLoader.getInstance().getConfigDir().resolve("aftrhrs/updates").toAbsolutePath().normalize();
		cleanOldUpdater();
		String forcedJar = System.getProperty("aftrhrs.update.jar");
		forced = forcedJar != null;
		jar = (forced ? Optional.of(Path.of(forcedJar)) : ownJar())
			.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")).map(Path::toAbsolutePath).orElse(null);
		if (jar == null || current().equals("dev")) {
			status = Status.DEV;
			return;
		}
		exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Aftr Hrs updater");
			t.setDaemon(true);
			return t;
		});
		exec.execute(Updater::restore);
		Runtime.getRuntime().addShutdownHook(new Thread(Updater::applyOnExit, "Aftr Hrs update"));
		schedule(s == Side.CLIENT ? 8 : 20);
	}

	private static synchronized void schedule(long seconds) {
		if (next != null) next.cancel(false);
		next = exec.schedule(() -> {
			Mode m = mode.get();
			long again = regular();
			if (m != Mode.OFF && status != Status.DOWNLOADING && status != Status.CHECKING) {
				again = run(m == Mode.AUTO);
			}
			schedule(again);
		}, Math.max(1, seconds), TimeUnit.SECONDS);
	}

	/** A staged update from an earlier session: still signed, still newer, its downloads intact? Then it's ready again. */
	private static void restore() {
		try {
			Path mf = dir.resolve("manifest.json"), sf = dir.resolve("manifest.json.sig");
			if (!Files.isRegularFile(mf) || !Files.isRegularFile(sf)) {
				clearStaged();
				return;
			}
			Manifest m = Manifest.parse(Files.readAllBytes(mf), Base64.getDecoder().decode(Files.readString(sf).trim()), RELEASES);
			if (Plan.compare(m.version(), current()) <= 0) {
				clearStaged(); // installed (or overtaken by hand)
				return;
			}
			Plan plan = Plan.of(m, jar, !forced);
			if (plan.blocked() != null) {
				clearStaged();
				return;
			}
			for (String[] p : plan.puts()) {
				Path f = dir.resolve("staged").resolve(p[0]);
				if (!Files.isRegularFile(f) || !p[2].equals(Swap.sha256(f))) return; // the next check downloads what's missing
			}
			Files.writeString(dir.resolve("plan.txt"), plan.text(dir.resolve("staged"), m.version()), StandardCharsets.UTF_8);
			found = m;
			LOG.info("Aftr Hrs {} is downloaded and is installed when the {}", m.version(), side == Side.CLIENT ? "game closes" : "server stops");
			set(Status.READY, "");
		} catch (IOException | RuntimeException e) {
			LOG.info("Dropping a staged update: {}", e.getMessage());
			clearStaged();
		}
	}

	// ------------------------------------------------------------------ check + download

	/** One check (and download when {@code stage}). Returns seconds until the next scheduled check. */
	private static long run(boolean stage) {
		// With an update staged the status stays READY (the exit hook and the restart button need it) unless a newer
		// release turns up, which then replaces it.
		boolean wasReady = status == Status.READY;
		Manifest staged = found;
		if (!wasReady) set(Status.CHECKING, "");
		try {
			HttpClient http = client();
			byte[] json = get(http, RELEASES + "latest/download/manifest.json", 256 * 1024);
			byte[] sig = Base64.getDecoder().decode(new String(get(http, RELEASES + "latest/download/manifest.json.sig", 4096), StandardCharsets.US_ASCII).trim());
			Manifest m = Manifest.parse(json, sig, RELEASES);
			if (wasReady && (!stage || staged == null || Plan.compare(m.version(), staged.version()) <= 0)) return regular();
			if (Plan.compare(m.version(), current()) <= 0) {
				found = null;
				set(Status.UP_TO_DATE, "");
				return regular();
			}
			Plan plan = Plan.of(m, jar, !forced);
			if (plan.blocked() != null) {
				found = null;
				LOG.warn("Aftr Hrs {} is out but can't be installed automatically: {}", m.version(), plan.blocked());
				set(Status.FAILED, plan.blocked());
				return regular();
			}
			found = m;
			if (!stage) {
				LOG.info("Aftr Hrs {} is out (running {})", m.version(), current());
				set(Status.AVAILABLE, "");
				return regular();
			}
			download(http, m, plan, json, sig);
			return regular();
		} catch (RateLimited e) {
			LOG.info("Update check: GitHub asks to wait {} s", e.seconds);
			if (!wasReady) set(Status.FAILED, "GitHub is busy; trying again later");
			return Math.max(15 * 60, e.seconds);
		} catch (IOException | RuntimeException e) {
			String why = e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
				|| e instanceof java.net.http.HttpTimeoutException ? "can't reach GitHub (offline?)"
				: e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			LOG.info("Update check: {}", why);
			if (!wasReady) set(Status.FAILED, why);
			return Math.min(30 * 60, regular()); // offline or a hiccup: again in half an hour (or sooner)
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (!wasReady) set(Status.FAILED, "interrupted");
			return regular();
		}
	}

	private static void download(HttpClient http, Manifest m, Plan plan, byte[] json, byte[] sig) throws IOException, InterruptedException {
		set(Status.DOWNLOADING, "");
		Path staged = dir.resolve("staged");
		Files.createDirectories(staged);
		// The verified manifest goes first; the plan last, once every jar is in place and checked.
		Files.deleteIfExists(dir.resolve("plan.txt"));
		Files.write(dir.resolve("manifest.json"), json);
		Files.writeString(dir.resolve("manifest.json.sig"), Base64.getEncoder().encodeToString(sig) + "\n");
		Set<String> keep = new HashSet<>();
		for (Manifest.File f : plan.downloads()) {
			keep.add(f.name());
			Path out = staged.resolve(f.name());
			if (Files.isRegularFile(out) && f.sha256().equals(Swap.sha256(out))) continue; // from an earlier session
			try {
				fetch(http, f, out);
			} catch (IOException | InterruptedException | RuntimeException e) {
				clearManifest(); // not staged; jars that did check out stay for the next try
				throw e;
			}
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(staged)) {
			for (Path p : ds) if (!keep.contains(p.getFileName().toString())) Files.deleteIfExists(p);
		}
		Files.writeString(dir.resolve("plan.txt"), plan.text(staged, m.version()), StandardCharsets.UTF_8);
		LOG.info("Downloaded Aftr Hrs {} ({} jar(s)); it is installed when the {}", m.version(), plan.puts().size(),
			side == Side.CLIENT ? "game closes" : "server stops");
		set(Status.READY, "");
	}

	/** Downloads one jar to {@code out} via {@code out.part}: size, SHA-256 and fabric.mod.json id/version must match. */
	private static void fetch(HttpClient http, Manifest.File f, Path out) throws IOException, InterruptedException {
		Path part = out.resolveSibling(out.getFileName() + ".part");
		try {
			HttpResponse<InputStream> res = http.send(request(f.url(), Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = res.body()) {
				status(res, f.name());
				MessageDigest md = MessageDigest.getInstance("SHA-256");
				long total = 0;
				try (OutputStream o = Files.newOutputStream(part)) {
					byte[] buf = new byte[65536];
					for (int n; (n = in.read(buf)) > 0; ) {
						total += n;
						if (total > f.size()) throw new IOException(f.name() + " is bigger than the release says");
						md.update(buf, 0, n);
						o.write(buf, 0, n);
					}
				}
				if (total != f.size()) throw new IOException(f.name() + " was cut short (" + total + " of " + f.size() + " bytes)");
				if (!HexFormat.of().formatHex(md.digest()).equals(f.sha256())) throw new IOException(f.name() + " doesn't match the release (SHA-256)");
			}
			var meta = Plan.metadata(part);
			if (meta == null || !f.id().equals(Manifest.str(meta, "id")) || !f.version().equals(Manifest.str(meta, "version"))) {
				throw new IOException(f.name() + " isn't " + f.id() + " " + f.version());
			}
			if (f.id().equals(MOD_ID) && !hasEntry(part, "net/axther/aftrhrs/AftrHrs.class")) throw new IOException(f.name() + " isn't the Aftr Hrs mod");
			Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		} finally {
			Files.deleteIfExists(part);
		}
	}

	private static boolean hasEntry(Path jar, String entry) {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			return zip.getEntry(entry) != null;
		} catch (IOException e) {
			return false;
		}
	}

	// ------------------------------------------------------------------ apply at exit

	private static long regular() {
		return side == Side.CLIENT ? CLIENT_EVERY : 6 * HOURS;
	}

	private static void applyOnExit() {
		Path plan = dir.resolve("plan.txt"), rollback = dir.resolve("rollback"), log = dir.resolve("update.log");
		boolean ready = status == Status.READY && Files.isRegularFile(plan);
		Path again = relaunch;
		if (!ready && again == null) return;
		String why = ready ? Swap.apply(plan, rollback, log) : null;
		if (why == null && ready) clearManifest();
		if (why == null && again == null) return;
		// Windows holding the jars open, and/or a restart: finish from a small process once this one has exited.
		try {
			Path helper = dir.resolve("helper");
			Path cls = helper.resolve("net/axther/aftrhrs/update/Swap.class");
			Files.createDirectories(cls.getParent());
			try (InputStream in = Updater.class.getResourceAsStream("Swap.class")) {
				if (in == null) return;
				Files.copy(in, cls, StandardCopyOption.REPLACE_EXISTING);
			}
			String java = ProcessHandle.current().info().command().orElse(null);
			if (java == null) return;
			Path javaw = Path.of(java).resolveSibling("javaw.exe");
			if (java.toLowerCase(Locale.ROOT).endsWith("java.exe") && Files.exists(javaw)) java = javaw.toString();
			List<String> cmd = new ArrayList<>(List.of(java, "-cp", helper.toString(), Swap.class.getName(),
				String.valueOf(ProcessHandle.current().pid()), plan.toString(), rollback.toString(), log.toString()));
			if (again != null) cmd.add(again.toString());
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.environment().remove("JAVA_TOOL_OPTIONS");
			pb.environment().remove("_JAVA_OPTIONS");
			pb.environment().remove("JDK_JAVA_OPTIONS");
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
			pb.start();
			Swap.log(log, why != null ? "jars in use (" + why + "); a helper finishes after exit" + (again != null ? " and reopens the game" : "")
				: "a helper reopens the game after exit");
		} catch (IOException | RuntimeException e) {
			Swap.log(log, "couldn't start the helper: " + e);
			// The next start finds the update still staged and tries again at its exit.
		}
	}

	// ------------------------------------------------------------------ helpers

	private static void set(Status s, String why) {
		status = s;
		detail = why == null ? "" : why;
		try {
			listener.changed(s, version(), detail);
		} catch (RuntimeException e) {
			LOG.warn("Update listener failed", e);
		}
	}

	private static HttpClient client() {
		return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
	}

	private static HttpRequest request(String url, Duration timeout) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
			.header("User-Agent", "AftrHrs-Updater/" + current() + " (fabric; " + (side == Side.SERVER ? "server" : "client") + ")").GET().build();
	}

	private static byte[] get(HttpClient http, String url, int max) throws IOException, InterruptedException {
		HttpResponse<InputStream> res = http.send(request(url, Duration.ofSeconds(20)), HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = res.body()) {
			if (res.statusCode() == 404) throw new IOException("no release published yet");
			status(res, "the release");
			byte[] b = in.readNBytes(max + 1);
			if (b.length > max) throw new IOException("release file too big");
			return b;
		}
	}

	private static void status(HttpResponse<?> res, String what) throws IOException {
		int code = res.statusCode();
		if (code == 200) return;
		if (code == 429 || code == 403) {
			long wait = res.headers().firstValue("Retry-After").map(v -> {
				try {
					return Long.parseLong(v.trim());
				} catch (NumberFormatException e) {
					return 3600L;
				}
			}).orElseGet(() -> res.headers().firstValueAsLong("X-RateLimit-Reset").stream()
				.map(t -> t - System.currentTimeMillis() / 1000).findFirst().orElse(3600L));
			throw new RateLimited(wait);
		}
		throw new IOException("couldn't download " + what + " (HTTP " + code + ")");
	}

	private static final class RateLimited extends IOException {
		final long seconds;

		RateLimited(long seconds) {
			super("rate limited");
			this.seconds = seconds;
		}
	}

	/** The staged update is gone (installed, cancelled or invalid). */
	private static void clearStaged() {
		clearManifest();
		deleteTree(dir.resolve("staged"));
		try {
			Files.deleteIfExists(dir.resolve("plan.txt"));
		} catch (IOException ignored) {
		}
	}

	private static void clearManifest() {
		try {
			Files.deleteIfExists(dir.resolve("manifest.json"));
			Files.deleteIfExists(dir.resolve("manifest.json.sig"));
		} catch (IOException ignored) {
		}
	}

	/** The old Companion updater's leftovers in {@code config/aftrhrs/update/} (its downloads and helper). */
	private static void cleanOldUpdater() {
		Path old = dir.resolveSibling("update");
		if (!Files.isDirectory(old)) return;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(old)) {
			for (Path p : ds) {
				String n = p.getFileName().toString();
				if (n.endsWith(".pending") || n.equals("pending.json") || n.equals("download.tmp") || n.equals("update.log")) Files.deleteIfExists(p);
			}
		} catch (IOException ignored) {
		}
		deleteTree(old.resolve("helper"));
		try {
			Files.deleteIfExists(old);
		} catch (IOException ignored) {
			// not empty: something of the player's; leave it
		}
	}

	private static void deleteTree(Path p) {
		if (!Files.exists(p)) return;
		try (var walk = Files.walk(p)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
				try {
					Files.deleteIfExists(f);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	/** The jar the mod was loaded from (any folder: mods/, a launcher's custom_mods/ ...); empty in development. */
	private static Optional<Path> ownJar() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.filter(c -> c.getOrigin().getKind() == ModOrigin.Kind.PATH)
			.flatMap(c -> c.getOrigin().getPaths().stream().filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".jar")).findFirst());
	}

	private static String slash(String s) {
		return s.endsWith("/") ? s : s + "/";
	}

	/** Fabric's version order for two mod versions (negative: {@code a} is older). */
	public static int compareVersions(String a, String b) {
		return Plan.compare(a, b);
	}

	/** Dotted numbers ("0.10.0" > "0.9.2"); a suffix ("-beta") sorts before the plain release. */
	static int compareLoose(String a, String b) {
		String[] x = a.split("[-+]", 2), y = b.split("[-+]", 2);
		String[] xs = x[0].split("\\."), ys = y[0].split("\\.");
		for (int i = 0; i < Math.max(xs.length, ys.length); i++) {
			long xi = i < xs.length ? num(xs[i]) : 0, yi = i < ys.length ? num(ys[i]) : 0;
			if (xi != yi) return Long.compare(xi, yi);
		}
		if (x.length != y.length) return x.length < y.length ? 1 : -1;
		return x.length > 1 ? x[1].compareTo(y[1]) : 0;
	}

	private static long num(String s) {
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
