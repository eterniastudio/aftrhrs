package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The settings in {@code claims.yml} (bundled defaults fill in anything missing). Colors are 0xRRGGBB. */
final class ClaimSettings {
    /** Claim chunks: what everyone starts with, earns per active hour, and can earn at most. */
    long startChunks;
    long chunksPerHour;
    long maxChunks;
    int maxClaims;
    int minWidth;
    int minRadius;
    int maxRadius;
    int gap;
    Set<String> worlds = new HashSet<>();
    Set<String> disabledWorlds = new HashSet<>();
    Item tool = Items.GOLDEN_SHOVEL;
    Item viewItem = Items.STICK;
    int viewRadius;
    int refreshTicks;
    int maxParticles;
    boolean chunkGrid;
    String enterMode;
    long denyCooldownMs;
    boolean homesNeedAccess;
    int showSeconds;
    int own, trusted, others, adminColor, preview, grid;
    final Map<String, List<String>> presets = new LinkedHashMap<>();

    void load(Yaml c, Logger log) {
        startChunks = Math.max(0, c.getLong("chunks.start", 64));
        chunksPerHour = Math.max(0, c.getLong("chunks.per-hour", 8));
        maxChunks = Math.max(startChunks, c.getLong("chunks.max", 2000));
        maxClaims = Math.max(1, c.getInt("max-claims", 10));
        minWidth = Math.max(1, c.getInt("min-size", 5));
        minRadius = Math.max(1, c.getInt("circle.min-radius", 3));
        maxRadius = Math.max(minRadius, c.getInt("circle.max-radius", 160));
        gap = Math.max(0, c.getInt("gap", 0));
        worlds = lower(c.getStringList("worlds"));
        disabledWorlds = lower(c.getStringList("disabled-worlds"));
        tool = item(c.getString("tool", "golden_shovel"), Items.GOLDEN_SHOVEL, log, "tool");
        viewItem = item(c.getString("view.item", "stick"), Items.STICK, log, "view.item");
        viewRadius = Math.max(8, Math.min(96, c.getInt("view.radius", 48)));
        refreshTicks = Math.max(4, c.getInt("view.refresh-ticks", 10));
        maxParticles = Math.max(50, c.getInt("view.max-particles", 700));
        chunkGrid = c.getBoolean("view.chunk-grid", true);
        enterMode = c.getString("enter-messages", "actionbar").toLowerCase(Locale.ROOT);
        denyCooldownMs = Math.max(0, c.getLong("deny-message-cooldown-ms", 1500));
        homesNeedAccess = c.getBoolean("homes-need-access", true);
        showSeconds = Math.max(3, c.getInt("show-seconds", 20));
        own = color(c.getString("view.colors.own", "#4FD1C5"), log);
        trusted = color(c.getString("view.colors.trusted", "#9DB4FF"), log);
        others = color(c.getString("view.colors.others", "#FF8A3D"), log);
        adminColor = color(c.getString("view.colors.admin", "#FFE066"), log);
        preview = color(c.getString("view.colors.preview", "#F1EDFF"), log);
        grid = color(c.getString("view.colors.chunk-grid", "#A49DC2"), log);
        presets.clear();
        Yaml ps = c.getConfigurationSection("admin-presets");
        if (ps != null) for (String k : ps.getKeys(false)) presets.put(k.toLowerCase(Locale.ROOT), new ArrayList<>(ps.getStringList(k)));
        if (!presets.containsKey("bank")) presets.put("bank", List.of("use bank-chest", "break bank-chest"));
    }

    boolean worldAllowed(String world) {
        String w = world.toLowerCase(Locale.ROOT);
        if (disabledWorlds.contains(w)) return false;
        return worlds.isEmpty() || worlds.contains(w);
    }

    private static Set<String> lower(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    static Item item(String id, Item def, Logger log, String key) {
        Identifier rl = id == null ? null : Identifier.tryParse(id.toLowerCase(Locale.ROOT).contains(":") ? id.toLowerCase(Locale.ROOT) : "minecraft:" + id.toLowerCase(Locale.ROOT));
        Item it = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (it == null || it == Items.AIR) {
            log.warn("claims.yml {}: unknown item {}, using {}", key, id, BuiltInRegistries.ITEM.getKey(def).getPath());
            return def;
        }
        return it;
    }

    private static int color(String hex, Logger log) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(h, 16) & 0xFFFFFF;
        } catch (RuntimeException e) {
            log.warn("claims.yml: bad color {} (use #rrggbb)", hex);
            return 0xFFFFFF;
        }
    }
}
