package net.axther.aftrhrs.feature.bossfx;

import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Arcana mobs that share a faction (species {@code faction}, the MythicMobs {@code Faction}: Wukong and his hair clones,
 * police officers) never target or hurt each other. MythicMobs itself didn't enforce this; Feather's boss-fx module
 * did ({@code MythicAllies}), so it follows {@code modules.boss-fx}.
 */
public final class Allies {
    private Allies() {
    }

    public static @Nullable String faction(@Nullable Entity e) {
        if (!(e instanceof ArcanaMob m)) return null;
        return Species.str(m.species().json, "faction", null);
    }

    /** True when both are Arcana mobs of the same faction (and boss-fx runs). */
    public static boolean allied(@Nullable Entity a, @Nullable Entity b) {
        if (!BossFx.enabled || a == null || b == null || a == b) return false;
        String fa = faction(a);
        return fa != null && fa.equalsIgnoreCase(faction(b));
    }
}
