package net.axther.aftrhrs.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Scoreboard teams on the main scoreboard, shared by the tab list (teams sort the list: the client orders players
 * by team name) and the nametags (a team option hides the vanilla nametag). Feather's teams are named
 * {@code ft_<sort key>}; they're removed on shutdown. Get it with {@link Feather#scoreTeams()}.
 */
public final class ScoreTeams {
    public static final String PREFIX = "ft_";
    private final Map<UUID, String> assigned = new HashMap<>();
    private boolean hideNametags;
    private boolean collisions = true;

    private static Scoreboard board() {
        MinecraftServer s = Feather.serverOrNull();
        return s == null ? null : s.getScoreboard();
    }

    public void configure(boolean hideNametags, boolean collisions) {
        boolean changed = this.hideNametags != hideNametags || this.collisions != collisions;
        this.hideNametags = hideNametags;
        this.collisions = collisions;
        Scoreboard b = board();
        if (changed && b != null) for (PlayerTeam t : b.getPlayerTeams()) if (t.getName().startsWith(PREFIX)) style(t);
    }

    public void hideNametags(boolean hide) {
        configure(hide, collisions);
    }

    public boolean hidingNametags() {
        return hideNametags;
    }

    /** Puts the player in the team for {@code sortKey} (letters/digits; sorts alphabetically). */
    public void assign(ServerPlayer p, String sortKey) {
        Scoreboard b = board();
        if (b == null) return;
        String name = PREFIX + sortKey;
        String entry = Names.name(p);
        if (name.equals(assigned.get(p.getUUID()))) {
            PlayerTeam t = b.getPlayerTeam(name);
            if (t != null && t.getPlayers().contains(entry)) return;
        }
        PlayerTeam team = b.getPlayerTeam(name);
        if (team == null) {
            team = b.addPlayerTeam(name);
            style(team);
        }
        b.addPlayerToTeam(entry, team);
        assigned.put(p.getUUID(), name);
    }

    /** Assigns a default team only when the player has none from Feather yet. */
    public void ensure(ServerPlayer p) {
        if (!assigned.containsKey(p.getUUID())) assign(p, "500");
    }

    public void remove(ServerPlayer p) {
        String name = assigned.remove(p.getUUID());
        Scoreboard b = board();
        if (name == null || b == null) return;
        PlayerTeam t = b.getPlayerTeam(name);
        if (t != null) {
            String entry = Names.name(p);
            if (t.getPlayers().contains(entry)) b.removePlayerFromTeam(entry, t);
            if (t.getPlayers().isEmpty()) b.removePlayerTeam(t);
        }
    }

    private void style(PlayerTeam t) {
        t.setNameTagVisibility(hideNametags ? Team.Visibility.NEVER : Team.Visibility.ALWAYS);
        t.setCollisionRule(collisions ? Team.CollisionRule.ALWAYS : Team.CollisionRule.NEVER);
    }

    void shutdown() {
        Scoreboard b = board();
        if (b != null) {
            for (PlayerTeam t : new ArrayList<>(b.getPlayerTeams())) {
                if (t.getName().startsWith(PREFIX)) {
                    try {
                        b.removePlayerTeam(t);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        assigned.clear();
    }
}
