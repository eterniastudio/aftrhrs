package net.axther.aftrhrs.gametest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.spongepowered.asm.mixin.MixinEnvironment;

/** Dev-only: forces every mixin to apply once the server is up, so a broken injection fails the game-test run. */
public class MixinAudit implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            MixinEnvironment.getCurrentEnvironment().audit();
            net.axther.aftrhrs.AftrHrs.LOG.info("[MixinAudit] all mixins applied");
        });
    }
}
