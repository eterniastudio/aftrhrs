package net.axther.aftrhrs.client.fairylights;

import com.mojang.blaze3d.vertex.PoseStack;
import net.axther.aftrhrs.feature.fairylights.Fastener;
import net.axther.aftrhrs.feature.fairylights.KnotEntity;
import net.axther.aftrhrs.feature.fairylights.Strand;
import net.axther.aftrhrs.feature.fairylights.StrandKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** A cord knot tied round a fence or wall post (a ribbon bow when a garland hangs from it), and its strands. */
public final class KnotRenderer extends EntityRenderer<KnotEntity, KnotRenderer.State> {
    public static final class State extends EntityRenderState {
        final List<StrandRender.Draw> draws = new ArrayList<>();
        int cord = 0x3A2E24;
        boolean wall, bow;
        int knotLight;
    }

    public KnotRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    protected AABB getBoundingBoxForCulling(KnotEntity entity, float partialTicks) {
        return entity.renderBounds();
    }

    @Override
    public void extractRenderState(KnotEntity e, State s, float pt) {
        super.extractRenderState(e, s, pt);
        s.draws.clear();
        Fastener f = e.fastener();
        StrandRender.extract(f, new Vec3(s.x, s.y, s.z), Minecraft.getInstance().gameRenderer.mainCamera().position(), pt, e.level(), s.draws);
        // wall post, cord colour (the first strand's) and bow (any garland): once a client tick, or when the strands change
        Object tag = e.getAttached(KnotEntity.DATA);
        if (e.rStamp != FairyLightsClient.renderTick || e.rFor != tag) {
            e.rStamp = FairyLightsClient.renderTick;
            e.rFor = tag;
            e.rWall = e.level().getBlockState(e.getPos()).is(BlockTags.WALLS);
            boolean bow = false, first = true;
            int cord = 0x3A2E24;
            for (Strand st : f.outgoing()) {
                if (first) cord = color(st);
                first = false;
                if (st.kind == StrandKind.GARLAND) bow = true;
            }
            for (var in : f.incoming().entrySet()) {
                Fastener o = in.getValue().resolve(e.level());
                Strand st = o == null ? null : o.strand(in.getKey());
                if (st == null) continue;
                if (first) cord = color(st);
                first = false;
                if (st.kind == StrandKind.GARLAND) bow = true;
            }
            e.rCord = cord;
            e.rBow = bow;
        }
        s.wall = e.rWall;
        s.cord = e.rCord;
        s.bow = e.rBow;
        s.knotLight = s.lightCoords;
    }

    private static int color(Strand s) {
        return switch (s.kind) {
            case LIGHTS -> s.spec.wire().rgb;
            case GARLAND -> 0x3A5A2A;
            case TINSEL -> s.spec.color() < 0 ? 0xDDE3EA : s.spec.color();
            case BUNTING -> 0xE8E2D2;
        };
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        Mesh mesh = StrandRender.MESH;
        // two turns of cord round the post (fence post 4 px, wall post 8 px), a little proud of it
        float hs = (s.wall ? 4.0f : 2.0f) / 16, t = 0.7f / 16, bh = 0.35f / 16;
        out.submitCustomGeometry(pose, RenderTypes.entityCutout(StrandRender.TEXTURE), (p, buf) -> {
            mesh.set(p, buf);
            mesh.cull = Mesh.cullable();   // the knot's boxes are opaque and closed; the bow cards are not culled
            int l = s.knotLight;
            for (float y : new float[]{-0.9f / 16, 0.5f / 16}) {
                int c = y < 0 ? s.cord : Mesh.shade(s.cord, 1.1f);
                mesh.box(-hs - t, y - bh, -hs - t, hs + t, y + bh, -hs, c, 255, l);
                mesh.box(-hs - t, y - bh, hs, hs + t, y + bh, hs + t, c, 255, l);
                mesh.box(-hs - t, y - bh, -hs, -hs, y + bh, hs, c, 255, l);
                mesh.box(hs, y - bh, -hs, hs + t, y + bh, hs, c, 255, l);
            }
            if (s.bow) {
                float o = hs + t + 0.003f;
                for (int i = 0; i < 4; i++) {
                    mesh.local().identity().rotateY(i * (float) Math.PI / 2).translate(0, -0.03f, o);
                    mesh.card(-0.2f, -0.2f, 0.2f, 0.2f, 0, Mesh.BOW, 0xFFFFFFFF, l);
                }
            }
        });
        StrandRender.submit(s.draws, pose, out, camera);
        super.submit(s, pose, out, camera);
    }
}
