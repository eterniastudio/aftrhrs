package net.axther.aftrhrs.feature.bossfx;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

/**
 * Display-entity boss effects ({@link BossFx}: quake, fissure, pillar; called by the boss skills) and faction ally
 * protection ({@link Allies}: a boss and its minions never target or hurt each other). Toggle: {@code modules.boss-fx}.
 *
 * <p>Migration: the Paper module's displays were non-persistent, so a Paper world holds none; displays of this engine are
 * never saved either, and any tagged {@link BossFx#TAG} that shows up on load (a crash mid-effect) is removed.
 */
public final class BossFxModule implements FeatherModule {
    private final Feather feather;

    public BossFxModule(Feather feather) {
        this.feather = feather;
    }

    @Override
    public String name() {
        return "Boss effects";
    }

    @Override
    public void enable() {
        BossFx.enabled = true;
        feather.once("bossfx.events", () -> {
            ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
                if (!BossFx.enabled) return true;
                return !Allies.allied(source.getEntity(), entity);
            });
            ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
                if (entity.entityTags().contains(BossFx.TAG) && !BossFx.isLive(entity)) entity.discard();
            });
        });
    }

    @Override
    public void disable() {
        BossFx.enabled = false;
        BossFx.shutdown();
    }
}
