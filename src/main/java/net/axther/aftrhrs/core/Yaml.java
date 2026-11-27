package net.axther.aftrhrs.core;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A YAML document with the same shape as Bukkit's {@code YamlConfiguration}: dotted paths, sections, defaults and
 * the familiar getters. Feather's config and data files load and save byte-compatible, so a server's
 * {@code plugins/Feather} folder can be dropped into {@code aftrhrs/} unchanged.
 *
 * <p>Sections are views into the same tree: {@code getConfigurationSection("a").set("b", 1)} changes {@code a.b}.
 */
public class Yaml {
    private final Map<String, Object> map;
    private final Yaml root;
    private final String path;
    private Yaml defaults;

    public Yaml() {
        this(new LinkedHashMap<>(), null, "");
    }

    private Yaml(Map<String, Object> map, Yaml root, String path) {
        this.map = map;
        this.root = root == null ? this : root;
        this.path = path;
    }

    // ------------------------------------------------------------------ load / save

    public static Yaml load(Path file) {
        Yaml y = new Yaml();
        if (file == null || !Files.isRegularFile(file)) return y;
        try {
            y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            net.axther.aftrhrs.AftrHrs.LOG.warn("Could not read {}: {}", file, e.toString());
        }
        return y;
    }

    /** Like {@link #load} but throws, for stores that must not overwrite a file they failed to read. */
    public static Yaml loadStrict(Path file) throws IOException {
        Yaml y = new Yaml();
        if (!Files.isRegularFile(file)) return y;
        y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        return y;
    }

    public static Yaml load(InputStream in) {
        Yaml y = new Yaml();
        if (in == null) return y;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            y.loadFromString(sb.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return y;
    }

    public static Yaml fromString(String text) {
        Yaml y = new Yaml();
        y.loadFromString(text);
        return y;
    }

    @SuppressWarnings("unchecked")
    public void loadFromString(String text) {
        map.clear();
        Object o = parser().load(text == null ? "" : text);
        if (o instanceof Map<?, ?> m) copyInto(map, (Map<Object, Object>) m);
        else if (o != null) throw new IllegalArgumentException("Top level of a YAML file must be a map");
    }

    @SuppressWarnings("unchecked")
    private static void copyInto(Map<String, Object> to, Map<Object, Object> from) {
        for (Map.Entry<Object, Object> e : from.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> child = new LinkedHashMap<>();
                copyInto(child, (Map<Object, Object>) m);
                v = child;
            }
            // YAML 1.1 reads bare off/on/yes/no as booleans; the key text is gone by now, so only say where to look.
            if (e.getKey() instanceof Boolean) {
                net.axther.aftrhrs.AftrHrs.LOG.warn("YAML key '{}' was read as a yes/no value; put it in quotes (e.g. 'off':)", e.getKey());
            }
            to.put(String.valueOf(e.getKey()), v);
        }
    }

    public String saveToString() {
        if (map.isEmpty()) return "{}\n";
        return dump(plain(map));
    }

    /**
     * A deep copy of this document as plain maps, lists and leaf values, made on the calling thread (null when empty).
     * Hand it to {@link #dump} on another thread: nothing in it is shared with the live tree.
     */
    public Object snapshot() {
        return map.isEmpty() ? null : plain(map);
    }

    /**
     * The YAML text of a {@link #snapshot} (any thread); {@code "{}\n"} for null, like an empty document. Plain trees go
     * through {@link FastDump}, which writes exactly SnakeYAML's bytes; anything it doesn't cover is dumped by SnakeYAML.
     */
    public static String dump(Object snapshot) {
        if (snapshot == null) return "{}\n";
        if (FAST_DUMP && snapshot instanceof Map<?, ?> m) {
            try {
                return FastDump.dump(m);
            } catch (FastDump.Unsupported e) {
                // something SnakeYAML writes in a form of its own (quoting, anchors, tags): the whole document goes to it
            }
        }
        return snakeDump(snapshot);
    }

    /** {@link FastDump}'s text of a snapshot, or null where it hands the document to SnakeYAML (tests compare the two). */
    public static String fastDump(Object snapshot) {
        if (!(snapshot instanceof Map<?, ?> m)) return null;
        try {
            return FastDump.dump(m);
        } catch (FastDump.Unsupported e) {
            return null;
        }
    }

    /** SnakeYAML's text of a snapshot, never the fast path (tests compare the two). */
    public static String snakeDump(Object snapshot) {
        if (snapshot == null) return "{}\n";
        try {
            return DUMPER.get().dump(snapshot);
        } catch (RuntimeException e) {
            // A failed dump leaves the Representer holding what it had represented so far (it only clears that on
            // success): the next dump on this thread would reuse those half-built nodes. Start over with a new one.
            DUMPER.remove();
            throw e;
        }
    }

    /** Writes atomically (temp file + move). */
    public void save(Path file) throws IOException {
        SafeFiles.writeAtomic(file, saveToString());
    }

    // SnakeYAML instances aren't thread-safe: one of each per thread (saves run on IO threads too).
    private static final ThreadLocal<org.yaml.snakeyaml.Yaml> PARSER = ThreadLocal.withInitial(Yaml::newParser);
    private static final ThreadLocal<org.yaml.snakeyaml.Yaml> DUMPER = ThreadLocal.withInitial(Yaml::newDumper);

    private static org.yaml.snakeyaml.Yaml parser() {
        return PARSER.get();
    }

    private static org.yaml.snakeyaml.Yaml newParser() {
        LoaderOptions lo = new LoaderOptions();
        lo.setMaxAliasesForCollections(Integer.MAX_VALUE);
        lo.setCodePointLimit(Integer.MAX_VALUE);
        return new org.yaml.snakeyaml.Yaml(new SafeConstructor(lo));
    }

    private static org.yaml.snakeyaml.Yaml newDumper() {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setIndent(2);
        o.setWidth(4096);
        o.setSplitLines(false);
        o.setAllowUnicode(true);
        Representer r = new Representer(o);
        return new org.yaml.snakeyaml.Yaml(r, o);
    }

    /** {@code -Daftrhrs.yaml.fastDump=false} sends every dump through SnakeYAML again. */
    private static final boolean FAST_DUMP = Boolean.parseBoolean(System.getProperty("aftrhrs.yaml.fastDump", "true"));

    /**
     * A direct writer for plain trees (String-keyed maps, lists, strings, numbers, booleans, null) that produces the
     * same bytes as {@link #newDumper}'s SnakeYAML (block style, indent 2, indicator indent 0, no line splitting,
     * unicode as is), several times faster: SnakeYAML's Representer, Serializer and Emitter were most of a big save.
     * It only covers the forms whose output is certain (plain or single-quoted scalars on one line, simple keys, no
     * shared collections); for anything else it throws {@link Unsupported} and the caller dumps the whole document
     * with SnakeYAML. MigrationTests fuzzes it against SnakeYAML byte for byte.
     */
    private static final class FastDump {
        /** Thrown (preallocated, no stack) when SnakeYAML would write something this emitter doesn't. */
        static final class Unsupported extends RuntimeException {
            private Unsupported() {
                super(null, null, false, false);
            }
        }

        private static final Unsupported UNSUPPORTED = new Unsupported();
        /**
         * Keys from this length on are complex: Emitter.checkSimpleKey counts the "!!str" tag every string key carries
         * plus the scalar's own length against its limit of 128.
         */
        private static final int SIMPLE_KEY_MAX = 128 - "!!str".length();
        private static final org.yaml.snakeyaml.resolver.Resolver RESOLVER = new org.yaml.snakeyaml.resolver.Resolver();
        /** First characters that make SnakeYAML quote a scalar (Emitter.analyzeScalar's block indicators). */
        private static final String INDICATORS = "#,[]{}&*!|>'\"%@`";
        private static final String SPACES = " ".repeat(64);

        private final StringBuilder sb = new StringBuilder(1 << 16);
        /** Collections seen so far: SnakeYAML writes an &anchor / *alias for one that appears twice. */
        private final java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();

        private FastDump() {
        }

        static String dump(Map<?, ?> root) {
            if (root.isEmpty()) return "{}\n";
            FastDump d = new FastDump();
            d.enter(root);
            d.map(root, 0);
            return d.sb.toString();
        }

        private void enter(Object collection) {
            if (seen.put(collection, Boolean.TRUE) != null) throw UNSUPPORTED;
        }

        private void indent(int n) {
            while (n > SPACES.length()) {
                sb.append(SPACES);
                n -= SPACES.length();
            }
            sb.append(SPACES, 0, n);
        }

        /** The entries of a block mapping; the first one continues the current line (after "- " or the indent). */
        private void map(Map<?, ?> m, int indent) {
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) indent(indent);
                first = false;
                key(e.getKey());
                sb.append(':');
                value(e.getValue(), indent, true);
            }
        }

        /** The items of a block sequence; the first one continues the current line. */
        private void list(List<?> l, int indent) {
            boolean first = true;
            for (Object o : l) {
                if (!first) indent(indent);
                first = false;
                sb.append("- ");
                value(o, indent, false);
            }
        }

        /** A value after "key:" ({@code inMap}) or after "- ", through the end of its last line. */
        private void value(Object v, int indent, boolean inMap) {
            if (v instanceof Map<?, ?> m) {
                enter(m);   // empty ones too: a shared {} gets an anchor as well
                if (m.isEmpty()) {
                    sb.append(inMap ? " {}\n" : "{}\n");
                    return;
                }
                if (inMap) {
                    sb.append('\n');
                    indent(indent + 2);
                }
                map(m, indent + 2);
                return;
            }
            if (v instanceof List<?> l) {
                enter(l);
                if (l.isEmpty()) {
                    sb.append(inMap ? " []\n" : "[]\n");
                    return;
                }
                if (inMap) {
                    // a sequence under a key is not indented (indicator indent 0)
                    sb.append('\n');
                    indent(indent);
                    list(l, indent);
                } else {
                    list(l, indent + 2);
                }
                return;
            }
            if (inMap) sb.append(' ');
            if (v == null) sb.append("null");
            else if (v instanceof String s) scalar(s);
            else if (v instanceof Integer || v instanceof Long || v instanceof Boolean || v instanceof Short || v instanceof Byte) sb.append(v);
            else if (v instanceof Double d) {
                if (Double.isNaN(d)) sb.append(".NaN");
                else if (d == Double.POSITIVE_INFINITY) sb.append(".inf");
                else if (d == Double.NEGATIVE_INFINITY) sb.append("-.inf");
                else sb.append(d.toString());
            } else if (v instanceof Float f) {
                // SnakeYAML only spells Double's NaN / infinities the YAML way; a Float's are written with a !!float tag
                if (f.isNaN() || f.isInfinite()) throw UNSUPPORTED;
                sb.append(f.toString());
            } else throw UNSUPPORTED;
            sb.append('\n');
        }

        private void key(Object k) {
            // an empty key, or one SnakeYAML's Emitter finds too long for a simple key, is written as a complex "? key"
            if (!(k instanceof String s) || s.isEmpty() || s.length() >= SIMPLE_KEY_MAX) throw UNSUPPORTED;
            scalar(s);
        }

        /** A string as SnakeYAML writes it on one line: plain, or single-quoted; anything that needs more is Unsupported. */
        private void scalar(String s) {
            int n = s.length();
            if (n == 0) {
                sb.append("''");
                return;
            }
            boolean quote = false;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                // control characters, line breaks, BOM and non-characters: SnakeYAML double-quotes, escapes or base64s
                if (c < 0x20 || c == 0x7F || (c >= 0x80 && c < 0xA0) || c == 0x2028 || c == 0x2029 || c == 0xFEFF || c >= 0xFFFE) throw UNSUPPORTED;
                if (Character.isSurrogate(c)) {
                    if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                        i++;
                        continue;
                    }
                    throw UNSUPPORTED;
                }
                if (quote) continue;
                boolean spaceAfter = i + 1 >= n || s.charAt(i + 1) == ' ';
                if (i == 0) {
                    if (INDICATORS.indexOf(c) >= 0 || ((c == '-' || c == '?' || c == ':') && spaceAfter)) quote = true;
                } else if ((c == ':' && spaceAfter) || (c == '#' && s.charAt(i - 1) == ' ')) {
                    quote = true;
                }
            }
            if (!quote) {
                quote = s.charAt(0) == ' ' || s.charAt(n - 1) == ' '
                        || leadingZero(s)
                        || s.startsWith("---") || s.startsWith("...")
                        || RESOLVER.resolve(org.yaml.snakeyaml.nodes.NodeId.scalar, s, true) != org.yaml.snakeyaml.nodes.Tag.STR;
            }
            if (!quote) {
                sb.append(s);
                return;
            }
            sb.append('\'');
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c == '\'') sb.append('\'');
                sb.append(c);
            }
            sb.append('\'');
        }

        /** Emitter.hasLeadingZero: "0" followed only by digits and underscores (an old octal look). */
        private static boolean leadingZero(String s) {
            if (s.length() < 2 || s.charAt(0) != '0') return false;
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c < '0' || c > '9') && c != '_') return false;
            }
            return true;
        }
    }

    private static Object plain(Object v) {
        if (v instanceof Yaml y) return plain(y.map);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), plain(e.getValue()));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(plain(o));
            return out;
        }
        if (v instanceof java.util.UUID u) return u.toString();
        if (v instanceof Enum<?> e) return e.name();
        return v;
    }

    // ------------------------------------------------------------------ tree access

    public String getCurrentPath() {
        return path;
    }

    public String getName() {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }

    public Yaml getRoot() {
        return root;
    }

    public void setDefaults(Yaml defaults) {
        this.defaults = defaults;
    }

    public Yaml getDefaults() {
        return defaults;
    }

    /** The raw value at a dotted path, or {@code null}; falls back to the defaults. */
    public Object get(String key) {
        return get(key, null);
    }

    @SuppressWarnings("unchecked")
    public Object get(String key, Object def) {
        if (key == null || key.isEmpty()) return this;
        Map<String, Object> cur = map;
        String[] parts = key.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            Object v = cur.get(parts[i]);
            if (i == parts.length - 1) {
                if (v == null) return defaults != null ? defaults.get(key, def) : def;
                if (v instanceof Map<?, ?> m) return new Yaml((Map<String, Object>) m, root, child(String.join(".", java.util.Arrays.copyOf(parts, i + 1))));
                return v;
            }
            if (!(v instanceof Map<?, ?> m)) return defaults != null ? defaults.get(key, def) : def;
            cur = (Map<String, Object>) m;
        }
        return def;
    }

    private String child(String rel) {
        return path.isEmpty() ? rel : path + "." + rel;
    }

    @SuppressWarnings("unchecked")
    public void set(String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> cur = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object v = cur.get(parts[i]);
            if (!(v instanceof Map<?, ?>)) {
                if (value == null) return;
                v = new LinkedHashMap<String, Object>();
                cur.put(parts[i], v);
            }
            cur = (Map<String, Object>) v;
        }
        String last = parts[parts.length - 1];
        if (value == null) cur.remove(last);
        else if (value instanceof Yaml y) cur.put(last, y.map);
        else if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copyInto(copy, (Map<Object, Object>) m);
            cur.put(last, copy);
        } else cur.put(last, value);
    }

    public Yaml createSection(String key) {
        set(key, new LinkedHashMap<String, Object>());
        return getConfigurationSection(key);
    }

    public Yaml createSection(String key, Map<?, ?> values) {
        Yaml s = createSection(key);
        for (Map.Entry<?, ?> e : values.entrySet()) s.set(String.valueOf(e.getKey()), e.getValue());
        return s;
    }

    public boolean contains(String key) {
        return get(key) != null;
    }

    /** True only if this file (not its defaults) has the key. Read-only: safe on shared documents. */
    @SuppressWarnings("unchecked")
    public boolean isSet(String key) {
        if (key == null || key.isEmpty()) return true;
        Map<String, Object> cur = map;
        String[] parts = key.split("\\.");
        for (int i = 0; ; i++) {
            Object v = cur.get(parts[i]);
            if (v == null) return false;
            if (i == parts.length - 1) return true;
            if (!(v instanceof Map<?, ?> m)) return false;
            cur = (Map<String, Object>) m;
        }
    }

    public Set<String> getKeys(boolean deep) {
        Set<String> out = new LinkedHashSet<>();
        if (defaults != null) out.addAll(defaults.getKeys(deep));
        keys(map, "", deep, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void keys(Map<String, Object> m, String prefix, boolean deep, Set<String> out) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = prefix + e.getKey();
            out.add(k);
            if (deep && e.getValue() instanceof Map<?, ?> c) keys((Map<String, Object>) c, k + ".", true, out);
        }
    }

    public Map<String, Object> getValues(boolean deep) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : getKeys(deep)) out.put(k, get(k));
        return out;
    }

    public boolean isConfigurationSection(String key) {
        return get(key) instanceof Yaml;
    }

    public Yaml getConfigurationSection(String key) {
        Object o = get(key);
        return o instanceof Yaml y ? y : null;
    }

    /** The section at {@code key}, or an empty detached one (never null). */
    public Yaml section(String key) {
        Yaml y = getConfigurationSection(key);
        return y != null ? y : new Yaml();
    }

    // ------------------------------------------------------------------ typed getters

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String def) {
        Object o = get(key, def);
        if (o == null || o instanceof Yaml) return def;
        return String.valueOf(o);
    }

    public boolean isString(String key) {
        return get(key) instanceof String;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int def) {
        Object o = get(key);
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                try {
                    return (int) Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored2) {
                }
            }
        }
        return def;
    }

    public boolean isInt(String key) {
        return get(key) instanceof Integer;
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long def) {
        Object o = get(key);
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public double getDouble(String key) {
        return getDouble(key, 0D);
    }

    public double getDouble(String key, double def) {
        Object o = get(key);
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public boolean isDouble(String key) {
        return get(key) instanceof Double;
    }

    public boolean isNumber(String key) {
        return get(key) instanceof Number;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean def) {
        Object o = get(key);
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return def;
    }

    public boolean isBoolean(String key) {
        return get(key) instanceof Boolean;
    }

    public List<?> getList(String key) {
        return getList(key, null);
    }

    public List<?> getList(String key, List<?> def) {
        Object o = get(key);
        return o instanceof List<?> l ? l : def;
    }

    public boolean isList(String key) {
        return get(key) instanceof List<?>;
    }

    public List<String> getStringList(String key) {
        List<?> l = getList(key);
        List<String> out = new ArrayList<>();
        if (l == null) {
            Object o = get(key);
            if (o instanceof String s) out.add(s);
            return out;
        }
        for (Object o : l) if (o != null && !(o instanceof Map<?, ?>) && !(o instanceof List<?>)) out.add(String.valueOf(o));
        return out;
    }

    public List<Integer> getIntegerList(String key) {
        List<Integer> out = new ArrayList<>();
        List<?> l = getList(key);
        if (l != null) for (Object o : l) {
            if (o instanceof Number n) out.add(n.intValue());
            else if (o instanceof String s) try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public List<Double> getDoubleList(String key) {
        List<Double> out = new ArrayList<>();
        List<?> l = getList(key);
        if (l != null) for (Object o : l) {
            if (o instanceof Number n) out.add(n.doubleValue());
            else if (o instanceof String s) try {
                out.add(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public List<Long> getLongList(String key) {
        List<Long> out = new ArrayList<>();
        List<?> l = getList(key);
        if (l != null) for (Object o : l) {
            if (o instanceof Number n) out.add(n.longValue());
            else if (o instanceof String s) try {
                out.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Map<?, ?>> getMapList(String key) {
        List<Map<?, ?>> out = new ArrayList<>();
        List<?> l = getList(key);
        if (l != null) for (Object o : l) if (o instanceof Map<?, ?> m) out.add(m);
        return out;
    }

    /** A list of maps as Yaml sections (detached copies). */
    @SuppressWarnings("unchecked")
    public List<Yaml> getSectionList(String key) {
        List<Yaml> out = new ArrayList<>();
        for (Map<?, ?> m : getMapList(key)) {
            Yaml y = new Yaml();
            copyInto(y.map, (Map<Object, Object>) m);
            out.add(y);
        }
        return out;
    }

    /** The backing map of this section (live). */
    public Map<String, Object> raw() {
        return map;
    }

    @Override
    public String toString() {
        return "Yaml[" + path + "]" + map;
    }

    /** Atomic file writes shared by every store. */
    public static final class SafeFiles {
        private SafeFiles() {
        }

        public static void writeAtomic(Path file, String text) throws IOException {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            // On Windows a file another thread has open (a read of the same store) can't be replaced for a moment:
            // the move fails with AccessDenied. Retry briefly instead of losing the save.
            for (int attempt = 1; ; attempt++) {
                try {
                    try {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return;
                } catch (java.nio.file.AccessDeniedException e) {
                    if (attempt >= 8) throw e;
                    try {
                        Thread.sleep(5L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
    }
}
