package net.axther.aftrhrs.client.entity;

import com.geckolib.constant.DataTickets;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.RenderPassInfo;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.feature.shop.CounterKeeper;
import net.axther.aftrhrs.feature.shop.KeeperContent;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;

/**
 * The counter keepers: one GeckoLib model per trade ({@code geckolib/models/entity/keeper_<trade>.geo.json}, texture
 * and idle clip from {@code tools/gen_keepers.py}), the {@code head} bone turned to where the keeper looks.
 */
public final class KeeperRenderer extends GeoEntityRenderer<CounterKeeper, LivingEntityRenderState> {
    private static final float MAX_YAW = 70f, MAX_PITCH = 35f;

    KeeperRenderer(EntityRendererProvider.Context context, KeeperContent.Trade trade) {
        super(context, new DefaultedEntityGeoModel<>(AftrHrs.id(trade.id())));
        this.shadowRadius = 0.5f;
    }

    public static void init() {
        for (KeeperContent.Trade t : KeeperContent.Trade.values()) {
            EntityRendererRegistry.register(KeeperContent.TYPES.get(t), ctx -> new KeeperRenderer(ctx, t));
        }
    }

    @Override
    public void adjustModelBonesForRender(RenderPassInfo<LivingEntityRenderState> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        float pitch = info.getOrDefaultGeckolibData(DataTickets.ENTITY_PITCH, 0f);
        float yaw = info.getOrDefaultGeckolibData(DataTickets.ENTITY_YAW, 0f);
        snapshots.ifPresent("head", s -> {
            s.setRotX(s.getRotX() - Mth.clamp(pitch, -MAX_PITCH, MAX_PITCH) * Mth.DEG_TO_RAD);
            s.setRotY(s.getRotY() - Mth.clamp(yaw, -MAX_YAW, MAX_YAW) * Mth.DEG_TO_RAD);
        });
    }
}
