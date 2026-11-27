package net.axther.aftrhrs.client;

import net.axther.aftrhrs.entity.ability.Fx;
import net.axther.aftrhrs.entity.ability.FxLinePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;

public final class AftrHrsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Companion.init();
        net.axther.aftrhrs.client.entity.ArcanaRenderers.init();
        net.axther.aftrhrs.client.entity.KeeperRenderer.init(); // counter keepers
        net.axther.aftrhrs.client.relics.RelicsClient.init();
        net.axther.aftrhrs.client.carry.CarryClient.register(); // carried blocks and mobs in the arms
        net.axther.aftrhrs.client.cosmetics.CosmeticsClient.init();
        net.axther.aftrhrs.client.entity.BossShake.init();
        net.axther.aftrhrs.client.entity.BossHeaveFx.init(); // boss quakes and fissures
        fxLines();
        net.axther.aftrhrs.client.furniture.FurnitureRenderers.init();
        net.axther.aftrhrs.client.christmas.TreeRenderer.init();
        net.axther.aftrhrs.client.trade.TradeScreen.register();
        net.axther.aftrhrs.client.fairylights.FairyLightsClient.init();
        net.axther.aftrhrs.client.smithing.SmithingClient.init();
        net.axther.aftrhrs.client.enchants.EnchantTooltips.init(); // what the Aftr Hrs enchantments do
    }

    /** Skill particle lines and rings ({@link FxLinePayload}): the same points the server would have sent one by one. */
    private static void fxLines() {
        ClientPlayNetworking.registerGlobalReceiver(FxLinePayload.TYPE, (pl, ctx) -> {
            ClientLevel lv = ctx.client().level;
            if (lv == null) return;
            Fx.forEachLinePoint(pl.from(), pl.to(), pl.step(), (x, y, z) -> lv.addParticle(pl.particle(), false, false, x, y, z, 0, 0, 0));
        });
        ClientPlayNetworking.registerGlobalReceiver(FxLinePayload.Ring.TYPE, (pl, ctx) -> {
            ClientLevel lv = ctx.client().level;
            if (lv == null) return;
            Fx.forEachRingPoint(pl.center(), pl.radius(), pl.points(), pl.y(), pl.phase(), (x, y, z) -> {
                // a count > 0 packet with no spread and no speed: that many particles on the spot, standing still
                for (int i = 0; i < Math.max(1, pl.amount()); i++) lv.addParticle(pl.particle(), false, false, x, y, z, 0, 0, 0);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(FxLinePayload.Arc.TYPE, (pl, ctx) -> {
            ClientLevel lv = ctx.client().level;
            if (lv == null) return;
            Fx.forEachRingPoint(pl.center(), pl.radius(), pl.points(), pl.first(), pl.count(), pl.y(),
                    (x, y, z) -> lv.addParticle(pl.particle(), false, false, x, y, z, 0, 0, 0));
        });
        // a batch of spots, each played as vanilla plays a particle packet (ClientPacketListener.handleParticleEvent)
        ClientPlayNetworking.registerGlobalReceiver(FxLinePayload.Points.TYPE, (pl, ctx) -> {
            ClientLevel lv = ctx.client().level;
            if (lv == null) return;
            net.minecraft.util.RandomSource r = lv.getRandom();
            float[] o = pl.offsets();
            double sp = pl.speed();
            try {
                for (int i = 0; i + 2 < o.length; i += 3) {
                    double x = pl.origin().x + o[i], y = pl.origin().y + o[i + 1], z = pl.origin().z + o[i + 2];
                    if (pl.count() == 0) {
                        lv.addParticle(pl.particle(), false, false, x, y, z, sp * pl.dx(), sp * pl.dy(), sp * pl.dz());
                        continue;
                    }
                    for (int k = 0; k < pl.count(); k++) {
                        lv.addParticle(pl.particle(), false, false, x + r.nextGaussian() * pl.dx(), y + r.nextGaussian() * pl.dy(),
                                z + r.nextGaussian() * pl.dz(), r.nextGaussian() * sp, r.nextGaussian() * sp, r.nextGaussian() * sp);
                    }
                }
            } catch (Throwable t) {
                Companion.LOG.warn("Could not spawn particle effect {}", pl.particle(), t);
            }
        });
    }
}
