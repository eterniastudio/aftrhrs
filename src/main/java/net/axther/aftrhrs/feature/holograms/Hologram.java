package net.axther.aftrhrs.feature.holograms;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Worlds;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A hologram: where it is (the top of its first line), its pages and its settings. Persistent holograms are made
 * with /holo and saved in holograms.yml; temporary ones belong to a Feather module (the bank chests) and are never
 * saved. Change one through {@link HologramsModule} so its entities follow.
 *
 * <p>{@link #world} keeps the name holograms.yml uses ("world", "world_nether", ... as on Paper);
 * {@link Worlds#resolve} maps it to a level.
 */
public final class Hologram {
    private final String name;
    /** The module that owns a temporary hologram ("bank"), or null for one made with /holo (saved). */
    private final String owner;
    String world;
    double x, y, z;
    final List<HoloPage> pages = new ArrayList<>();
    /** Settings this hologram changed; the rest come from {@link #defaults}. */
    final Map<String, String> settings = new LinkedHashMap<>();
    final Map<String, String> defaults;
    /** Extra MiniMessage tags for its text lines, per viewer (null viewer = shared text). Temporary holograms only. */
    Function<ServerPlayer, TagResolver> resolver;

    Hologram(String name, String owner, Loc at, Map<String, String> defaults) {
        this.name = name;
        this.owner = owner;
        this.defaults = defaults;
        if (at != null) setLocation(at);
    }

    public String name() {
        return name;
    }

    /** The owning module of a temporary hologram, or null. */
    public String owner() {
        return owner;
    }

    public boolean temporary() {
        return owner != null;
    }

    public List<HoloPage> pages() {
        return Collections.unmodifiableList(pages);
    }

    public String worldName() {
        return world;
    }

    /** The level it is in, or null while that world isn't loaded. */
    public ServerLevel level() {
        return world == null ? null : Worlds.resolve(world);
    }

    /** The top of the first line, or null while its world isn't loaded. */
    public Loc location() {
        ServerLevel l = level();
        return l == null ? null : new Loc(l, x, y, z);
    }

    void setLocation(Loc at) {
        world = Worlds.name(at.level());
        x = at.x();
        y = at.y();
        z = at.z();
    }

    /** A setting's value: this hologram's own or the default. */
    public String setting(String key) {
        String v = settings.get(key);
        if (v != null) return v;
        v = defaults.get(key);
        return v != null ? v : HoloSettings.BUILT_IN.getOrDefault(key, "");
    }

    boolean flag(String key) {
        return "true".equals(setting(key));
    }

    double number(String key, double fallback) {
        try {
            return Double.parseDouble(setting(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    HoloPage page(int index) {
        return pages.get(Math.max(0, Math.min(pages.size() - 1, index)));
    }

    /** True when any page has click actions. */
    boolean hasActions() {
        for (HoloPage p : pages) if (!p.actions.isEmpty()) return true;
        return false;
    }

    int lineCount() {
        int n = 0;
        for (HoloPage p : pages) n += p.lines.size();
        return n;
    }

    int chunkX() {
        return (int) Math.floor(x) >> 4;
    }

    int chunkZ() {
        return (int) Math.floor(z) >> 4;
    }

    long chunkKey() {
        return HologramsModule.chunkKey(chunkX(), chunkZ());
    }
}
