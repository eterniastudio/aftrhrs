package net.axther.aftrhrs.feature.holograms;

import java.util.Locale;

/**
 * One line of a hologram page. {@link #content} is what staff typed: plain MiniMessage text, or {@code #ITEM: <id>},
 * {@code #HEAD: <name|uuid|texture|oraxen:id|{player}>}, {@code #BLOCK: <block data>}, {@code #ENTITY: <type|mythic:id>}
 * or {@code #BLANK}. Item, head, block and entity specs may be followed by flags: {@code spin}, {@code still},
 * {@code scale=<n>}. An id may list fallbacks with {@code |} ({@code oraxen:arcana_goob_coin|gold_nugget}).
 */
public final class HoloLine {
    public enum Type {TEXT, ITEM, HEAD, BLOCK, ENTITY, BLANK}

    private String content;
    private Type type;
    /** The id part of a non-text line (first word after the prefix). */
    private String id = "";
    /** Flags after the id: spin / still (null = the type's default) and scale. */
    private Boolean spin;
    private double scale = 1;
    /** Height this line takes (blocks), or null for the type's default. */
    Double height;
    double ox, oy, oz;

    public HoloLine(String content) {
        setContent(content);
    }

    public HoloLine copy() {
        HoloLine l = new HoloLine(content);
        l.height = height;
        l.ox = ox;
        l.oy = oy;
        l.oz = oz;
        return l;
    }

    public String content() {
        return content;
    }

    public Type type() {
        return type;
    }

    String id() {
        return id;
    }

    Boolean spin() {
        return spin;
    }

    double scale() {
        return scale;
    }

    public Double height() {
        return height;
    }

    public boolean hasOffset() {
        return ox != 0 || oy != 0 || oz != 0;
    }

    public void setContent(String raw) {
        String c = raw == null ? "" : raw.strip();
        content = c;
        spin = null;
        scale = 1;
        id = "";
        String upper = c.toUpperCase(Locale.ROOT);
        if (upper.equals("#BLANK") || upper.startsWith("#BLANK:") || c.isEmpty()) {
            type = Type.BLANK;
            return;
        }
        Type t = null;
        int cut = 0;
        for (Type k : new Type[]{Type.ITEM, Type.HEAD, Type.BLOCK, Type.ENTITY}) {
            String prefix = "#" + k.name() + ":";
            if (upper.startsWith(prefix)) {
                t = k;
                cut = prefix.length();
                break;
            }
        }
        if (t == null) {
            type = Type.TEXT;
            return;
        }
        type = t;
        String[] parts = c.substring(cut).strip().split("\\s+");
        id = parts.length > 0 ? parts[0] : "";
        for (int i = 1; i < parts.length; i++) {
            String f = parts[i].toLowerCase(Locale.ROOT);
            if (f.equals("spin")) spin = true;
            else if (f.equals("still")) spin = false;
            else if (f.startsWith("scale=")) {
                try {
                    scale = Math.max(0.05, Math.min(10, Double.parseDouble(f.substring(6))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /** Rows of text a text line shows ({@code <newline>}, {@code <br>} or a real line break start a new one). */
    int rows() {
        if (type != Type.TEXT) return 1;
        String s = content.toLowerCase(Locale.ROOT);
        int n = 1;
        for (String sep : new String[]{"<newline>", "<br>", "\n"}) {
            int at = 0;
            while ((at = s.indexOf(sep, at)) >= 0) {
                n++;
                at += sep.length();
            }
        }
        return n;
    }
}
