package net.axther.aftrhrs.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A release's {@code manifest.json} (format 2), written and signed by {@code mod/release.py}:
 *
 * <pre>{@code
 * {"format": 2, "repo": "eterniastudio/aftrhrs", "version": "1.0.1", "published": "2026-11-20", "notes": "...",
 *  "minecraft": "~26.3", "min_minecraft": "26.3", "min_loader": "0.20.1",
 *  "mod": {"id": "aftrhrs", "version": "1.0.1", "name": "aftrhrs-1.0.1.jar", "url": ".../releases/download/v1.0.1/aftrhrs-1.0.1.jar",
 *          "sha256": "...", "size": 123},
 *  "dependencies": [{"id": "geckolib", "version": "5.5.7", "min": "5.5.7", "name": "geckolib-fabric-26.3-5.5.7.jar",
 *                    "url": "https://cdn.modrinth.com/...", "sha256": "...", "size": 123, "source": "modrinth"}, ...]}
 * }</pre>
 *
 * {@code manifest.json.sig} is an Ed25519 signature (base64) over the manifest's exact bytes. {@link #parse} only
 * returns a manifest that verifies against {@link Updater#PUBLIC_KEY} and lists only files it may: jar names without
 * paths, SHA-256 hashes, sizes within limits, the mod from this repo's release downloads, dependencies from Modrinth's
 * CDN or GitHub.
 */
public record Manifest(String version, String notes, String minecraft, String minMinecraft, String minLoader, File mod,
                       List<File> dependencies) {

	/** One jar: Fabric mod id and version (from its fabric.mod.json), file name, where to get it, SHA-256 and size. */
	public record File(String id, String version, String min, String name, String url, String sha256, long size) {
	}

	public static final int FORMAT = 2;
	static final long MAX_JAR = 256L << 20;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\\.jar");
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,60}");
	private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

	/** Checks the signature, then the contents. Throws with a short reason (shown in the settings / console). */
	static Manifest parse(byte[] json, byte[] signature, String releases) throws IOException {
		try {
			if (!verify(json, signature)) throw new IOException("the release isn't signed by Aftr Hrs");
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IOException("the release isn't signed by Aftr Hrs");
		}
		JsonObject root;
		try {
			root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("unreadable release manifest");
		}
		if (!root.has("format") || root.get("format").getAsInt() != FORMAT) throw new IOException("unknown release format");
		if (!Updater.REPO.equals(str(root, "repo"))) throw new IOException("the manifest is for another repository");
		File mod = file(root.getAsJsonObject("mod"), "mod");
		if (!mod.id().equals(Updater.MOD_ID) || !mod.version().equals(str(root, "version"))
			|| !mod.name().equals("aftrhrs-" + mod.version() + ".jar")
			|| !mod.url().startsWith(releases + "download/") || !mod.url().endsWith("/" + mod.name())) {
			throw new IOException("the release lists a mod file it shouldn't");
		}
		List<File> deps = new ArrayList<>();
		JsonArray arr = root.has("dependencies") && root.get("dependencies").isJsonArray() ? root.getAsJsonArray("dependencies") : new JsonArray();
		for (JsonElement e : arr) {
			File d = file(e.isJsonObject() ? e.getAsJsonObject() : null, "dependency");
			boolean host = d.url().startsWith("https://cdn.modrinth.com/data/") || d.url().startsWith("https://github.com/")
				|| d.url().startsWith(releases);
			if (!host || d.id().equals(Updater.MOD_ID) || d.id().equals("minecraft") || d.id().equals("fabricloader")
				|| d.id().equals("java") || deps.stream().anyMatch(o -> o.id().equals(d.id()) || o.name().equalsIgnoreCase(d.name()))
				|| d.name().equalsIgnoreCase(mod.name())) {
				throw new IOException("the release lists a dependency it shouldn't (" + d.id() + ")");
			}
			deps.add(d);
		}
		return new Manifest(mod.version(), str(root, "notes"), str(root, "minecraft"), str(root, "min_minecraft"),
			str(root, "min_loader"), mod, List.copyOf(deps));
	}

	private static File file(JsonObject o, String what) throws IOException {
		if (o == null) throw new IOException("the release has no " + what + " entry");
		File f = new File(str(o, "id"), str(o, "version"), str(o, "min"), str(o, "name"), str(o, "url"),
			str(o, "sha256").toLowerCase(Locale.ROOT), o.has("size") ? o.get("size").getAsLong() : -1);
		if (!ID.matcher(f.id()).matches() || !VERSION.matcher(f.version()).matches()
			|| !(f.min().isEmpty() || VERSION.matcher(f.min()).matches()) || !NAME.matcher(f.name()).matches()
			|| !SHA.matcher(f.sha256()).matches() || f.size() <= 0 || f.size() > MAX_JAR || f.url().isEmpty()
			|| f.url().contains("..")) {
			throw new IOException("the release lists a " + what + " it shouldn't (" + f.id() + ")");
		}
		return f;
	}

	static boolean verify(byte[] data, byte[] sig) throws GeneralSecurityException {
		// X.509 SubjectPublicKeyInfo for a raw Ed25519 key: fixed 12-byte header + the 32 key bytes.
		byte[] raw = Base64.getDecoder().decode(Updater.PUBLIC_KEY);
		byte[] spki = new byte[44];
		System.arraycopy(HexFormat.of().parseHex("302a300506032b6570032100"), 0, spki, 0, 12);
		System.arraycopy(raw, 0, spki, 12, 32);
		PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
		Signature s = Signature.getInstance("Ed25519");
		s.initVerify(key);
		s.update(data);
		return s.verify(sig);
	}

	static String str(JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}
}
