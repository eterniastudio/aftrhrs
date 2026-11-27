package net.axther.aftrhrs.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Adventure boss bars shown through vanilla {@link ServerBossEvent}s; changes to the Adventure bar follow live. */
public final class BossBars {
    private static final Map<BossBar, ServerBossEvent> BARS = new WeakHashMap<>();

    private BossBars() {
    }

    public static synchronized void show(ServerPlayer player, BossBar bar) {
        ServerBossEvent ev = BARS.computeIfAbsent(bar, BossBars::create);
        ev.addPlayer(player);
    }

    public static synchronized void hide(ServerPlayer player, BossBar bar) {
        ServerBossEvent ev = BARS.get(bar);
        if (ev == null) return;
        ev.removePlayer(player);
        if (ev.getPlayers().isEmpty()) BARS.remove(bar);
    }

    private static ServerBossEvent create(BossBar bar) {
        ServerBossEvent ev = new ServerBossEvent(java.util.UUID.randomUUID(), Adv.vanilla(bar.name()), color(bar.color()), overlay(bar.overlay()));
        ev.setProgress(bar.progress());
        apply(ev, bar.flags());
        bar.addListener(new BossBar.Listener() {
            @Override
            public void bossBarNameChanged(@NotNull BossBar b, @NotNull Component oldName, @NotNull Component newName) {
                ev.setName(Adv.vanilla(newName));
            }

            @Override
            public void bossBarProgressChanged(@NotNull BossBar b, float oldProgress, float newProgress) {
                ev.setProgress(newProgress);
            }

            @Override
            public void bossBarColorChanged(@NotNull BossBar b, BossBar.@NotNull Color oldColor, BossBar.@NotNull Color newColor) {
                ev.setColor(color(newColor));
            }

            @Override
            public void bossBarOverlayChanged(@NotNull BossBar b, BossBar.@NotNull Overlay oldOverlay, BossBar.@NotNull Overlay newOverlay) {
                ev.setOverlay(overlay(newOverlay));
            }

            @Override
            public void bossBarFlagsChanged(@NotNull BossBar b, @NotNull Set<BossBar.Flag> added, @NotNull Set<BossBar.Flag> removed) {
                apply(ev, b.flags());
            }
        });
        return ev;
    }

    private static void apply(ServerBossEvent ev, Set<BossBar.Flag> flags) {
        ev.setDarkenScreen(flags.contains(BossBar.Flag.DARKEN_SCREEN));
        ev.setPlayBossMusic(flags.contains(BossBar.Flag.PLAY_BOSS_MUSIC));
        ev.setCreateWorldFog(flags.contains(BossBar.Flag.CREATE_WORLD_FOG));
    }

    private static BossEvent.BossBarColor color(BossBar.Color c) {
        return switch (c) {
            case PINK -> BossEvent.BossBarColor.PINK;
            case BLUE -> BossEvent.BossBarColor.BLUE;
            case RED -> BossEvent.BossBarColor.RED;
            case GREEN -> BossEvent.BossBarColor.GREEN;
            case YELLOW -> BossEvent.BossBarColor.YELLOW;
            case PURPLE -> BossEvent.BossBarColor.PURPLE;
            default -> BossEvent.BossBarColor.WHITE;
        };
    }

    private static BossEvent.BossBarOverlay overlay(BossBar.Overlay o) {
        return switch (o) {
            case NOTCHED_6 -> BossEvent.BossBarOverlay.NOTCHED_6;
            case NOTCHED_10 -> BossEvent.BossBarOverlay.NOTCHED_10;
            case NOTCHED_12 -> BossEvent.BossBarOverlay.NOTCHED_12;
            case NOTCHED_20 -> BossEvent.BossBarOverlay.NOTCHED_20;
            default -> BossEvent.BossBarOverlay.PROGRESS;
        };
    }
}
