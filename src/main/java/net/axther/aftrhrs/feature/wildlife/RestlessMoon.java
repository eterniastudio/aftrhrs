package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.UUID;

/**
 * The Restless Moon (wildlife SPEC section 1/5): on the new-moon night (moon phase {@code restless-moon.phase}, 4 by
 * default) of the configured overworld, the undead get a bigger share of the natural spawns (the creatures' spawn
 * rules). At dusk Feather broadcasts a line (once per night, remembered across restarts) and shows a boss bar to
 * everyone in that world until {@code ends-at}. Checked every 5 seconds; costs one world time read.
 */
final class RestlessMoon {
    private final WildlifeModule m;
    private ServerBossEvent bar;

    private boolean enabled = true;
    private String worldName = "world";
    private int phase = 4;
    private long duskFrom = 12000, announceUntil = 13500, endsAt = 23000;
    private BossEvent.BossBarColor color = BossEvent.BossBarColor.PURPLE;
    private boolean showBar = true;
    /** Staff test hook: the next check behaves as if it were the Restless Moon at dusk. */
    private boolean forced;

    RestlessMoon(WildlifeModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        enabled = cfg.getBoolean("restless-moon.enabled", true);
        worldName = cfg.getString("restless-moon.world", "world");
        phase = Math.floorMod(cfg.getInt("restless-moon.phase", 4), 8);
        duskFrom = Math.floorMod(cfg.getLong("restless-moon.dusk-from", 12000), 24000);
        announceUntil = Math.floorMod(cfg.getLong("restless-moon.announce-until", 13500), 24000);
        endsAt = Math.floorMod(cfg.getLong("restless-moon.ends-at", 23000), 24000);
        showBar = cfg.getBoolean("restless-moon.bossbar.enabled", true);
        try {
            color = BossEvent.BossBarColor.valueOf(cfg.getString("restless-moon.bossbar.color", "PURPLE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            color = BossEvent.BossBarColor.PURPLE;
        }
        if (bar != null) bar.setColor(color);
    }

    ServerLevel world() {
        ServerLevel w = Worlds.resolve(worldName);
        if (w != null) return w;
        var s = Feather.serverOrNull();
        return s == null ? null : s.overworld();
    }

    /** Days since the world began (Paper's full time / 24000). */
    static long day(Level w) {
        return Math.floorDiv(w.getDefaultClockTime(), 24000L);
    }

    static int moonPhase(Level w) {
        return (int) Math.floorMod(day(w), 8L);
    }

    static long timeOfDay(Level w) {
        return Math.floorMod(w.getDefaultClockTime(), 24000L);
    }

    boolean active() {
        ServerLevel w = world();
        if (!enabled || w == null) return false;
        long t = timeOfDay(w);
        return forced || moonPhase(w) == phase && t >= duskFrom && t < endsAt;
    }

    void force() {
        forced = true;
        tick();
    }

    void tick() {
        ServerLevel w = world();
        if (!enabled || w == null || !active()) {
            clear();
            return;
        }
        long day = day(w);
        long t = timeOfDay(w);
        if (m.bestiary.moonDay != day && (forced || t < announceUntil)) {
            m.bestiary.moonDay = day;
            m.bestiary.markDirty();
            Component line = m.msg("moon.broadcast");
            for (ServerPlayer p : m.feather().onlinePlayers()) Adv.send(p, line);
            m.feather().getLogger().info(Adv.plain(line));
            for (ServerPlayer p : w.players()) m.sound(p, "moon");
        }
        if (!showBar) return;
        if (bar == null) bar = new ServerBossEvent(UUID.randomUUID(), Adv.vanilla(m.msg("moon.bossbar")), color, BossEvent.BossBarOverlay.PROGRESS);
        float left = forced ? 1f : (float) Math.max(0, Math.min(1, (endsAt - t) / (double) Math.max(1, endsAt - duskFrom)));
        bar.setProgress(left);
        for (ServerPlayer p : m.feather().onlinePlayers()) {
            boolean in = p.level() == w;
            if (in && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            else if (!in && bar.getPlayers().contains(p)) bar.removePlayer(p);
        }
    }

    void clear() {
        forced = false;
        if (bar != null) bar.removeAllPlayers();
    }

    void forget(ServerPlayer p) {
        if (bar != null) bar.removePlayer(p);
    }

    /** Staff: ends a forced night. */
    void stop() {
        clear();
    }

    String state() {
        ServerLevel w = world();
        if (w == null) return "MOON no world";
        return "MOON world=" + Worlds.name(w) + " phase=" + moonPhase(w) + " time=" + timeOfDay(w) + " active=" + active()
                + " bar=" + (bar == null ? 0 : bar.getPlayers().size());
    }
}
