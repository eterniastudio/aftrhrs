package net.axther.aftrhrs.migrate;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A data-only reader for the Java serialization streams Bukkit's {@code BukkitObjectOutputStream} wrote (Oraxen kept
 * storage furniture contents that way). It parses the stream grammar itself and never loads, instantiates or resolves
 * a class: objects come back as {@link Obj} (class name + field values + custom-write annotations), arrays as
 * {@code Object[]}, strings as {@link String}, primitive fields boxed. {@link #plain(Object)} then turns the few
 * container classes those streams use (Bukkit's Wrapper, Guava's immutable serialized forms, HashMap, ArrayList,
 * boxed numbers) into plain Java maps, lists and values. Anything else stays an {@link Obj}.
 */
public final class JavaSer {
    private static final int MAX_OBJECTS = 200_000;
    private static final int MAX_DEPTH = 64;
    /** Upper bound on stream size; bigger input is refused before parsing. */
    public static final int MAX_BYTES = 2 << 20;
    /** Nodes {@link #plain} may visit in total (shared references count every time). */
    private static final int MAX_PLAIN_NODES = 100_000;

    /** A deserialized object, as data. */
    public static final class Obj {
        public final String className;
        public final Map<String, Object> fields = new LinkedHashMap<>();
        /** writeObject extra data, in order: objects and {@code byte[]} blocks. */
        public final List<Object> annotations = new ArrayList<>();

        Obj(String className) {
            this.className = className;
        }

        /** Only the class name: decoded data never goes into logs or exceptions. */
        @Override
        public String toString() {
            return describe(className);
        }
    }

    private record Field(char type, String name) {
    }

    private static final class Desc {
        final String name;
        final byte flags;
        final List<Field> fields = new ArrayList<>();
        Desc parent;

        Desc(String name, byte flags) {
            this.name = name;
            this.flags = flags;
        }
    }

    private static final Object ENUM_MARK = new Object();

    private final DataInputStream in;
    private final List<Object> handles = new ArrayList<>();
    private int objects;

    /** A short, length-capped description of an untrusted string. */
    public static String describe(String s) {
        if (s == null) return "null";
        String t = s.length() > 64 ? s.substring(0, 64) + "..." : s;
        StringBuilder b = new StringBuilder();
        t.codePoints().filter(cp -> cp >= 0x20 && cp < 0x7F).forEach(b::appendCodePoint);
        return b.toString();
    }

    private JavaSer(byte[] data) {
        this.in = new DataInputStream(new ByteArrayInputStream(data));
    }

    /** Everything in the stream after the header: objects, and {@code byte[]} for block data. */
    public static List<Object> read(byte[] data) throws IOException {
        if (data == null || data.length > MAX_BYTES) throw new IOException("stream too large");
        JavaSer r = new JavaSer(data);
        if (r.in.readUnsignedShort() != 0xACED || r.in.readUnsignedShort() != 5) throw new IOException("not a Java serialization stream");
        List<Object> out = new ArrayList<>();
        while (true) {
            int tc = r.in.read();
            if (tc < 0) break;
            out.add(r.content(tc, 0));
        }
        return out;
    }

    private Object content(int tc, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("too deep");
        if (++objects > MAX_OBJECTS) throw new IOException("too many objects");
        switch (tc) {
            case 0x70: // TC_NULL
                return null;
            case 0x71: { // TC_REFERENCE
                int h = in.readInt() - 0x7E0000;
                if (h < 0 || h >= handles.size()) throw new IOException("bad handle");
                return handles.get(h);
            }
            case 0x74: { // TC_STRING
                String s = in.readUTF();
                handles.add(s);
                return s;
            }
            case 0x7C: { // TC_LONGSTRING
                long len = in.readLong();
                if (len < 0 || len > in.available()) throw new IOException("bad string length");
                byte[] b = new byte[(int) len];
                in.readFully(b);
                String s = new String(b, StandardCharsets.UTF_8);
                handles.add(s);
                return s;
            }
            case 0x77: { // TC_BLOCKDATA
                int n = in.readUnsignedByte();
                if (n > in.available()) throw new EOFException();
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case 0x7A: { // TC_BLOCKDATALONG
                int n = in.readInt();
                if (n < 0 || n > in.available()) throw new IOException("bad block length");
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case 0x73: { // TC_OBJECT
                Desc d = classDesc(in.read(), depth);
                if (d == null) throw new IOException("object without class");
                Obj o = new Obj(d.name);
                handles.add(o);
                List<Desc> chain = new ArrayList<>();
                for (Desc c = d; c != null; c = c.parent) chain.addFirst(c);
                for (Desc c : chain) {
                    if ((c.flags & 0x04) != 0) throw new IOException("externalizable " + c.name + " not supported");
                    for (Field f : c.fields) o.fields.put(f.name, value(f.type, depth));
                    if ((c.flags & 0x01) != 0) { // SC_WRITE_METHOD: annotations up to TC_ENDBLOCKDATA
                        for (int t = in.read(); t != 0x78; t = in.read()) {
                            if (t < 0) throw new EOFException();
                            o.annotations.add(content(t, depth + 1));
                        }
                    }
                }
                return o;
            }
            case 0x75: { // TC_ARRAY
                Desc d = classDesc(in.read(), depth);
                if (d == null || !d.name.startsWith("[")) throw new IOException("bad array class");
                int n = in.readInt();
                // every element takes at least one byte: never trust n beyond what is left
                if (n < 0 || n > in.available()) throw new IOException("bad array length");
                objects += n;
                if (objects > MAX_OBJECTS) throw new IOException("too many elements");
                char type = d.name.length() > 1 ? d.name.charAt(1) : '?';
                List<Object> list = new ArrayList<>(Math.min(n, 64));
                Object[] holder = new Object[1];
                handles.add(holder); // a back-reference to an array in progress resolves to this placeholder
                int slot = handles.size() - 1;
                for (int i = 0; i < n; i++) list.add(value(type, depth));
                Object[] arr = list.toArray();
                handles.set(slot, arr);
                return arr;
            }
            case 0x7E: { // TC_ENUM
                Desc d = classDesc(in.read(), depth);
                int slot = handles.size();
                handles.add(ENUM_MARK);
                Object name = content(in.read(), depth + 1);
                Obj e = new Obj(d == null ? "?" : d.name);
                e.fields.put("name", name);
                handles.set(slot, e);
                return e;
            }
            case 0x76: { // TC_CLASS
                Desc d = classDesc(in.read(), depth);
                Obj c = new Obj("java.lang.Class");
                c.fields.put("name", d == null ? null : d.name);
                handles.add(c);
                return c;
            }
            case 0x79: // TC_RESET
                handles.clear();
                return null;
            default:
                throw new IOException("unsupported stream element 0x" + Integer.toHexString(tc));
        }
    }

    private Desc classDesc(int tc, int depth) throws IOException {
        switch (tc) {
            case 0x70:
                return null;
            case 0x71: {
                int h = in.readInt() - 0x7E0000;
                if (h < 0 || h >= handles.size() || !(handles.get(h) instanceof Desc d)) throw new IOException("bad class handle");
                return d;
            }
            case 0x72: {
                String name = in.readUTF();
                in.readLong(); // serialVersionUID: irrelevant, nothing is instantiated
                int slot = handles.size();
                handles.add(null);
                byte flags = in.readByte();
                Desc d = new Desc(name, flags);
                handles.set(slot, d);
                int n = in.readShort();
                if (n < 0 || n > 1024) throw new IOException("bad field count");
                for (int i = 0; i < n; i++) {
                    char type = (char) in.readByte();
                    String fname = in.readUTF();
                    if (type == 'L' || type == '[') content(in.read(), depth + 1); // field class name string
                    d.fields.add(new Field(type, fname));
                }
                for (int t = in.read(); t != 0x78; t = in.read()) { // class annotation
                    if (t < 0) throw new EOFException();
                    content(t, depth + 1);
                }
                d.parent = classDesc(in.read(), depth + 1);
                return d;
            }
            default:
                throw new IOException("unsupported class descriptor 0x" + Integer.toHexString(tc));
        }
    }

    private Object value(char type, int depth) throws IOException {
        return switch (type) {
            case 'B' -> in.readByte();
            case 'C' -> in.readChar();
            case 'D' -> in.readDouble();
            case 'F' -> in.readFloat();
            case 'I' -> in.readInt();
            case 'J' -> in.readLong();
            case 'S' -> in.readShort();
            case 'Z' -> in.readBoolean();
            case 'L', '[' -> content(in.read(), depth + 1);
            default -> throw new IOException("bad field type " + type);
        };
    }

    // ------------------------------------------------------------------ to plain values

    /**
     * Plain Java values: Bukkit {@code Wrapper} → its map, Guava immutable map/list forms, HashMap/LinkedHashMap,
     * ArrayList → {@link Map} / {@link List}, boxed numbers → the number, {@code Object[]} → List. Other objects stay {@link Obj}.
     */
    public static Object plain(Object v) throws IOException {
        Walk w = new Walk();
        try {
            return w.plain(v, 0);
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** One {@link #plain} pass: cycle detection (objects on the current path) and a node budget. */
    private static final class Walk {
        private final java.util.IdentityHashMap<Object, Boolean> onPath = new java.util.IdentityHashMap<>();
        private int nodes;

        private Object plain(Object v, int depth) {
            if (++nodes > MAX_PLAIN_NODES) throw new IllegalStateException("too many nodes");
            if (depth > MAX_DEPTH) throw new IllegalStateException("too deep");
            if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) return v;
            if (onPath.put(v, Boolean.TRUE) != null) throw new IllegalStateException("cyclic data");
            try {
                return convert(v, depth);
            } finally {
                onPath.remove(v);
            }
        }

        private Object convert(Object v, int depth) {
            return JavaSer.convert(this, v, depth);
        }
    }

    private static Object plain(Walk w, Object v, int depth) {
        return w.plain(v, depth);
    }

    private static Object convert(Walk w, Object v, int depth) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) return v;
        if (v instanceof Object[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (Object o : arr) out.add(plain(w, o, depth + 1));
            return out;
        }
        if (!(v instanceof Obj o)) return v;
        String cn = o.className;
        switch (cn) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
                 "java.lang.Float", "java.lang.Boolean", "java.lang.Character" -> {
                return o.fields.get("value");
            }
            case "org.bukkit.util.io.Wrapper" -> {
                return plain(w, o.fields.get("map"), depth + 1);
            }
            case "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.Hashtable" -> {
                Map<Object, Object> m = new LinkedHashMap<>();
                List<Object> objs = new ArrayList<>();
                for (Object a : o.annotations) if (!(a instanceof byte[])) objs.add(a);
                for (int i = 0; i + 1 < objs.size(); i += 2) m.put(plain(w, objs.get(i), depth + 1), plain(w, objs.get(i + 1), depth + 1));
                return m;
            }
            case "java.util.ArrayList", "java.util.LinkedList" -> {
                List<Object> l = new ArrayList<>();
                for (Object a : o.annotations) if (!(a instanceof byte[])) l.add(plain(w, a, depth + 1));
                return l;
            }
            default -> {
                if (cn.startsWith("com.google.common.collect.") && o.fields.containsKey("keys") && o.fields.containsKey("values")) {
                    Object ks = plain(w, o.fields.get("keys"), depth + 1), vs = plain(w, o.fields.get("values"), depth + 1);
                    Map<Object, Object> m = new LinkedHashMap<>();
                    if (ks instanceof List<?> kl && vs instanceof List<?> vl) {
                        for (int i = 0; i < Math.min(kl.size(), vl.size()); i++) m.put(kl.get(i), vl.get(i));
                    }
                    return m;
                }
                if (cn.startsWith("com.google.common.collect.") && o.fields.containsKey("elements")) {
                    return plain(w, o.fields.get("elements"), depth + 1);
                }
                return o;
            }
        }
    }
}
