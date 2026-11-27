package net.axther.aftrhrs.core;

/**
 * Config and data keys that used British spelling before (e.g. {@code colour}, now {@code color}). The new key wins;
 * a file that still has only the old one (a server's edited config, pets saved before the change) keeps working.
 * Defaults from the jar never hide the old key: only keys actually in the file are looked at first.
 */
public final class Legacy {
    private Legacy() {
    }

    /** The path to read: {@code key} if the file has it, else {@code old} if the file has that, else {@code key}. */
    public static String key(Yaml c, String key, String old) {
        if (c == null || c.isSet(key)) return key;
        return c.isSet(old) ? old : key;
    }
}
