package net.axther.aftrhrs.feature.holograms;

import net.minecraft.world.entity.Display;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The look and behavior settings of holograms. Each hologram stores only what it changed ({@code settings:} in
 * holograms.yml); everything else comes from {@code holograms.defaults} in config.yml.
 */
public final class HoloSettings {
    /** Every setting, in the order /holo info and the editor show them. */
    public static final List<String> KEYS = List.of("billboard", "background", "shadow", "see-through", "alignment",
            "line-width", "scale", "view-distance", "brightness", "update-interval", "autocycle", "page-indicator",
            "permission", "left-click", "right-click", "yaw");

    /** Built-in defaults, used when config.yml has none. */
    static final Map<String, String> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("billboard", "center");
        BUILT_IN.put("background", "none");
        BUILT_IN.put("shadow", "true");
        BUILT_IN.put("see-through", "false");
        BUILT_IN.put("alignment", "center");
        BUILT_IN.put("line-width", "200");
        BUILT_IN.put("scale", "1");
        BUILT_IN.put("view-distance", "48");
        BUILT_IN.put("brightness", "15");
        BUILT_IN.put("update-interval", "0");
        BUILT_IN.put("autocycle", "0");
        BUILT_IN.put("page-indicator", "true");
        BUILT_IN.put("permission", "none");
        BUILT_IN.put("left-click", "prev");
        BUILT_IN.put("right-click", "next");
        BUILT_IN.put("yaw", "0");
    }

    private HoloSettings() {
    }

    /** Suggestions for tab completion and the editor. */
    static List<String> suggestions(String key) {
        return switch (key) {
            case "billboard" -> List.of("center", "vertical", "horizontal", "fixed");
            case "background" -> List.of("none", "default", "#40000000", "#802A1B3D");
            case "shadow", "see-through", "page-indicator" -> List.of("true", "false");
            case "alignment" -> List.of("center", "left", "right");
            case "line-width" -> List.of("200", "120", "300");
            case "scale" -> List.of("1", "0.75", "1.5", "2");
            case "view-distance" -> List.of("48", "16", "32", "64");
            case "brightness" -> List.of("15", "natural", "10");
            case "update-interval" -> List.of("0", "20", "40", "100");
            case "autocycle" -> List.of("0", "5", "10");
            case "permission" -> List.of("none", "feather.staff");
            case "left-click", "right-click" -> List.of("prev", "next", "none");
            case "yaw" -> List.of("0", "90", "180", "-90");
            default -> List.of();
        };
    }

    /** The value in its stored form, or null when it isn't valid for the key. */
    static String normalize(String key, String raw) {
        if (raw == null) return null;
        String v = raw.strip().toLowerCase(Locale.ROOT);
        try {
            return switch (key) {
                case "billboard" -> List.of("center", "vertical", "horizontal", "fixed").contains(v) ? v : null;
                case "alignment" -> List.of("center", "left", "right").contains(v) ? v : null;
                case "left-click", "right-click" -> List.of("prev", "next", "none").contains(v) ? v : null;
                case "shadow", "see-through", "page-indicator" -> v.equals("true") || v.equals("on") || v.equals("yes") ? "true"
                        : v.equals("false") || v.equals("off") || v.equals("no") ? "false" : null;
                case "background" -> v.equals("none") || v.equals("default") ? v : color(v) != null ? v : null;
                case "line-width" -> range(Integer.parseInt(v), 10, 1000);
                case "scale" -> rangeD(Double.parseDouble(v), 0.1, 10);
                case "view-distance" -> rangeD(Double.parseDouble(v), 1, 256);
                case "brightness" -> v.equals("natural") || v.equals("-1") ? "natural" : range(Integer.parseInt(v), 0, 15);
                case "update-interval" -> range(Integer.parseInt(v), 0, 72000);
                case "autocycle" -> range(Integer.parseInt(v), 0, 3600);
                case "yaw" -> rangeD(Double.parseDouble(v), -360, 360);
                case "permission" -> v.isEmpty() || v.equals("none") ? "none" : v.matches("[a-z0-9_.*-]+") ? v : null;
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String range(int n, int lo, int hi) {
        return n < lo || n > hi ? null : String.valueOf(n);
    }

    private static String rangeD(double d, double lo, double hi) {
        if (Double.isNaN(d) || d < lo || d > hi) return null;
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** {@code #RRGGBB} (opaque) or {@code #AARRGGBB} as packed ARGB; null if neither. */
    static Integer color(String v) {
        if (v == null || !v.startsWith("#")) return null;
        String hex = v.substring(1);
        try {
            if (hex.length() == 6) return 0xFF000000 | Integer.parseInt(hex, 16);
            if (hex.length() == 8) return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    static Display.BillboardConstraints billboard(String v) {
        return switch (v) {
            case "vertical" -> Display.BillboardConstraints.VERTICAL;
            case "horizontal" -> Display.BillboardConstraints.HORIZONTAL;
            case "fixed" -> Display.BillboardConstraints.FIXED;
            default -> Display.BillboardConstraints.CENTER;
        };
    }

    /** The text display style flag for an alignment (0 = center). */
    static byte alignment(String v) {
        return switch (v) {
            case "left" -> Display.TextDisplay.FLAG_ALIGN_LEFT;
            case "right" -> Display.TextDisplay.FLAG_ALIGN_RIGHT;
            default -> 0;
        };
    }
}
