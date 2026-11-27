package net.axther.aftrhrs.client.smithing;

import net.axther.aftrhrs.feature.smithing.AlmanacItem;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingClock;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/** Client side of Anvil & Ember: the station renderer, the live clock for tooltips, Shift for stats, the Almanac. */
public final class SmithingClient {
    private SmithingClient() {
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.world.level.block.entity.BlockEntityType<net.axther.aftrhrs.feature.smithing.station.StationBE> cast(
            net.minecraft.world.level.block.entity.BlockEntityType<?> t) {
        return (net.minecraft.world.level.block.entity.BlockEntityType<net.axther.aftrhrs.feature.smithing.station.StationBE>) t;
    }

    public static void init() {
        for (Station s : Station.values()) {
            // one renderer per station: only the ones drawing outside their block render off screen (their own cull)
            if (s.hasBlockEntity()) BlockEntityRenderers.register(cast(Smithing.beType(s)), ctx -> new StationRenderer(ctx, s));
        }
        SmithingClock.client = () -> {
            Minecraft mc = Minecraft.getInstance();
            return mc.level == null ? 0 : mc.level.getGameTime();
        };
        SmithingClock.shift = () -> Minecraft.getInstance().hasShiftDown();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> SmithingHud.clientTicks++);   // the recipe card rebuilds once a tick
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> SmithingHud.forget());
        AlmanacItem.opener = () -> Minecraft.getInstance().gui.setScreen(new AlmanacScreen());
        net.axther.aftrhrs.client.DevScript.SCREENS.put("almanac", AlmanacScreen::new);
    }
}
