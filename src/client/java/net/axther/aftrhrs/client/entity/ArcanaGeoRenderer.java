package net.axther.aftrhrs.client.entity;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.constant.DataTickets;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.entity.AnimSpec;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.CreatureAnim;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

/**
 * GeckoLib renderer for every Arcana creature: the converted bbmodel, texture and animations, the head bone turned to
 * where the creature looks (ModelEngine's {@code h_} head bones), hidden bones (partvis), the color-variant tint, the
 * shadow from the model's shadow bone, baby scaling for young forms and the glowmask layer for glow bones (fullbright,
 * dark while the death clip plays for {@code glow_out} species).
 */
public class ArcanaGeoRenderer<T extends Mob & ArcanaMob> extends GeoEntityRenderer<T, LivingEntityRenderState> {
    static final DataTicket<Boolean> GLOW_OFF = DataTicket.create("aftrhrs_glow_off", Boolean.class);
    /**
     * Everything the procedural layer and the glow layer read, in one ticket: GeckoLib's own ~14-21 entries already
     * push its per-state map from 16 to 32 slots; eleven separate (boxed) tickets pushed it again to 64 (two Object[65]
     * and ~550 B per creature per frame) plus ~4 boxes. Immutable and new per {@link #addRenderData} call: CreatureView
     * hands a state to a later picture-in-picture render, so one must never be reused per entity.
     *
     * @param hidden    hidden bones (partvis); a shared cached array, never modified
     * @param drives    what the base clip drives (h b t e)
     * @param posture   the base layer's posture
     * @param asleep    a sleep hold: the head no longer follows anything
     * @param bowl      in (or getting in / out of) a fishbowl
     * @param age       age in ticks (with the partial tick)
     * @param seed      per-entity seed
     * @param bank      procedural bank: roll in degrees (+ = right side down, into a right turn), 0 on the ground
     * @param glowScale glowmask strength 0..1 for {@code glow_dark} species (the Pockie); NaN for every other species
     */
    record Data(String[] hidden, String drives, String posture, boolean moving, boolean asleep, boolean bowl,
                float age, int seed, float bank, float glowScale) {
        static final Data DEFAULT = new Data(new String[0], "hbte", AnimSpec.STANDING, false, false, false, 0f, 0, 0f, Float.NaN);
    }

    static final DataTicket<Data> DATA = DataTicket.create("aftrhrs_creature", Data.class);
    private static final String[] NONE = new String[0];
    /** A boxed default for float tickets: {@code 0f} would box a new Float per read (Float.valueOf does not cache). */
    private static final Float ZERO = 0f;
    private static final java.util.function.Consumer<BoneSnapshot> HIDE = s -> {
        s.skipRender(true);
        s.skipChildrenRender(true);
    };

    private final Species species;
    private final String head;
    private final boolean glowDark;
    /** {@link ArcanaMob#hiddenBones()} split once per distinct value. Render thread only; arrays are shared, never modified. */
    private final java.util.HashMap<String, String[]> hiddenCache = new java.util.HashMap<>();
    /** The procedural layer's bones resolved in {@link #procFor} (null = not in the model; a resource reload bakes a new
     *  model). Render thread only. */
    private @org.jspecify.annotations.Nullable BakedGeoModel procFor;
    private @org.jspecify.annotations.Nullable GeoBone headBone, bankBone, breathBone;
    private GeoBone[] tailBones = new GeoBone[0], earBones = new GeoBone[0];

    public ArcanaGeoRenderer(EntityRendererProvider.Context context, Species species) {
        super(context, new DefaultedEntityGeoModel<>(AftrHrs.id(species.model)));
        this.species = species;
        this.shadowRadius = species.shadow;
        this.head = Species.str(species.json, "head_bone", null);
        if (species.scale != 1f) withScale(species.scale);
        this.glowDark = species.json.has("glow_dark") && species.json.get("glow_dark").getAsBoolean();
        if (species.json.has("glowmask") && species.json.get("glowmask").getAsBoolean()) withRenderLayer(new GlowLayer<T>(this, glowRenderBones(species)));
        // translucent texels (the Pockie's fishbowl glass and water) live in <model>_glass.png, drawn in a translucent pass
        if (species.json.has("glass_layer") && species.json.get("glass_layer").getAsBoolean()) {
            withRenderLayer(new GlassLayer<T>(this, AftrHrs.id("textures/entity/" + species.model + "_glass.png")));
        }
    }

    /** The model's render scale (the species' {@code scale}); menus fit the model with it. */
    public float modelScale() {
        return scaleHeight;
    }

    @Override
    public void addRenderData(T animatable, Void relatedObject, LivingEntityRenderState state, float partialTick) {
        super.addRenderData(animatable, relatedObject, state, partialTick);
        if (animatable.isDeadOrDying() && !species.glowOut.isEmpty()) state.addGeckolibData(GLOW_OFF, true);
        CreatureAnim.Driver d = CreatureAnim.driver(animatable);
        String drives = d == null ? "hbte" : d.clipTag().drv();
        String posture = d == null ? AnimSpec.STANDING : d.posture();
        boolean moving = d != null && d.moving();
        float bank = 0;
        AnimSpec a = species.anim;
        boolean asleep = animatable.holdClip().startsWith("sleep");
        // never banks in (or on the way out of) its fishbowl: the body would tilt through the glass
        boolean bowl = animatable instanceof ArcanaCreature c && c.inBowl();
        if (a.bank > 0 && d != null && !animatable.isDeadOrDying() && !bowl
                && (species.flyer() && !animatable.onGround() || species.aquatic() != null && animatable.isInWater())) {
            bank = Mth.clamp((float) d.yawRate() * a.bank, -a.bankMax, a.bankMax);
        }
        state.addGeckolibData(DATA, new Data(hiddenArray(animatable.hiddenBones()), drives, posture, moving, asleep, bowl,
                animatable.tickCount + partialTick, animatable.getId() * 0x9E3779B1, bank, glowDark ? glowScale(animatable) : Float.NaN));
    }

    private String[] hiddenArray(String s) {
        if (s.isEmpty()) return NONE;
        String[] a = hiddenCache.get(s);
        if (a == null) {
            if (hiddenCache.size() > 64) hiddenCache.clear();
            hiddenCache.put(s, a = s.split(","));
        }
        return a;
    }

    /**
     * Bioluminescence: a soft glow by day, full in the dark (raw brightness at the creature, sky darkened at night),
     * dimmed to a faint pulse while it sleeps.
     */
    static float glowScale(Mob mob) {
        var level = mob.level();
        int light = level == null ? 15 : level.getMaxLocalRawBrightness(mob.blockPosition());
        float g = 1.0f - 0.62f * Mth.clamp(light / 15f, 0, 1);
        if (mob instanceof ArcanaMob m && m.holdClip().startsWith("sleep")) {
            g *= 0.35f + 0.1f * Mth.sin(mob.tickCount * 0.08f);
        }
        return Mth.clamp(g, 0.1f, 1f);
    }

    @Override
    public int getRenderColor(T animatable, Void relatedObject, float partialTick) {
        int base = super.getRenderColor(animatable, relatedObject, partialTick);
        int tint = animatable.tint();
        return tint == 0xFFFFFF ? base : ARGB.multiply(base, ARGB.opaque(tint));
    }

    /** Bosses and the police play a long death clip: red only for the killing blow's flash, not the whole sequence. */
    @Override
    public int getPackedOverlay(T animatable, Void relatedObject, float u, float partialTick) {
        boolean longDeath = species.kind == Species.Kind.BOSS || species.kind == Species.Kind.POLICE;
        if (longDeath && animatable.deathTime > 8 && animatable.hurtTime <= 0) {
            return net.minecraft.client.renderer.texture.OverlayTexture.pack(net.minecraft.client.renderer.texture.OverlayTexture.u(u),
                    net.minecraft.client.renderer.texture.OverlayTexture.v(false));
        }
        return super.getPackedOverlay(animatable, relatedObject, u, partialTick);
    }

    @Override
    protected float getDeathMaxRotation(com.geckolib.renderer.base.GeoRenderState state) {
        return species.has("death") ? 0 : super.getDeathMaxRotation(state);
    }

    @Override
    public void adjustModelBonesForRender(RenderPassInfo<LivingEntityRenderState> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        Data d = info.getOrDefaultGeckolibData(DATA, Data.DEFAULT);
        procedural(info, snapshots, d);
        for (String bone : d.hidden()) snapshots.ifPresent(bone, HIDE);
    }

    /**
     * The procedural layer (no blinking, ever): the head turns to where the creature looks (clamped; not while lying
     * or asleep),
     * a slow breath on the body, an idle tail sway and an occasional ear twitch. Each part only runs where the model
     * has the bones and the playing base clip does not already animate them (its tag's {@code drv}).
     */
    private void procedural(RenderPassInfo<LivingEntityRenderState> info, BoneSnapshots snapshots, Data d) {
        AnimSpec.Procedural p = species.anim.procedural;
        resolveProcedural(info.model(), p);
        boolean dead = info.getOrDefaultGeckolibData(DataTickets.IS_DEAD_OR_DYING, false);
        String drv = d.drives();
        String posture = d.posture();
        boolean moving = d.moving();
        float age = d.age();
        int seed = d.seed();
        boolean lying = AnimSpec.LYING.equals(posture) || AnimSpec.BELLY.equals(posture);
        float phase = (seed & 0xFFFF) / 65535f * Mth.TWO_PI;
        boolean asleep = d.asleep();
        // in a fishbowl: a smaller look, and none while the clip itself moves the head (it is authored to the glass)
        boolean bowl = d.bowl();
        float maxPitch = bowl ? species.anim.bowlLookPitch : p.lookPitch();
        float maxYaw = bowl ? species.anim.bowlLookYaw : p.lookYaw();
        if (headBone != null && !dead && !lying && !asleep && !(bowl && drv.indexOf('h') >= 0)) {
            float pitch = info.getOrDefaultGeckolibData(DataTickets.ENTITY_PITCH, ZERO);
            float yaw = info.getOrDefaultGeckolibData(DataTickets.ENTITY_YAW, ZERO);
            BoneSnapshot s = snapshots.get(headBone);
            s.setRotX(s.getRotX() - Mth.clamp(pitch, -maxPitch, maxPitch) * Mth.DEG_TO_RAD);
            s.setRotY(s.getRotY() - Mth.clamp(yaw, -maxYaw, maxYaw) * Mth.DEG_TO_RAD);
        }
        if (dead) return;
        float bank = d.bank();
        if (bank != 0 && bankBone != null) {
            BoneSnapshot s = snapshots.get(bankBone);
            s.setRotZ(s.getRotZ() + bank * Mth.DEG_TO_RAD);
        }
        if (breathBone != null && p.breath() > 0 && drv.indexOf('b') < 0 && !moving) {
            float period = lying ? 90f : 60f;
            float b = p.breath() * (lying ? 1.6f : 1f) * Mth.sin(age * Mth.TWO_PI / period + phase);
            BoneSnapshot s = snapshots.get(breathBone);
            s.setScale(s.getScaleX() * (1 + b * 0.5f), s.getScaleY() * (1 + b), s.getScaleZ() * (1 + b * 0.5f));
        }
        if (tailBones.length > 0 && p.tailSway() > 0 && drv.indexOf('t') < 0) {
            float amp = p.tailSway() * (lying ? 0.35f : moving ? 0.6f : 1f) * Mth.DEG_TO_RAD;
            float period = moving ? 18f : 52f;
            for (int i = 0; i < tailBones.length; i++) {
                if (tailBones[i] == null) continue;
                float a = amp * (1 + 0.35f * i) * Mth.sin(age * Mth.TWO_PI / period + phase - i * 0.7f);
                BoneSnapshot s = snapshots.get(tailBones[i]);
                s.setRotY(s.getRotY() + a);
            }
        }
        if (earBones.length == 2 && p.earTwitch() > 0) {
            int period = 90 + (seed >>> 8 & 0x7F);
            int cycle = (int) (age / period);
            float u = (age - cycle * (float) period) / 5f;
            // most cycles twitch one ear once (a hash of the cycle picks the ear and whether it twitches)
            int h = (cycle * 0x45D9F3B) ^ seed;
            GeoBone ear = earBones[h >>> 2 & 1];
            if (u < 1 && (h & 3) != 0 && ear != null) {
                float sign = (h >>> 3 & 1) == 0 ? 1 : -1;
                float a = sign * p.earTwitch() * 0.22f * Mth.sin(u * Mth.PI);
                BoneSnapshot s = snapshots.get(ear);
                s.setRotZ(s.getRotZ() + a);
            }
        }
    }

    /** Resolves the procedural bones against {@code model} once per baked model (no per-frame name lookups or lambdas). */
    private void resolveProcedural(BakedGeoModel model, AnimSpec.Procedural p) {
        if (model == procFor) return;
        String headName = p.head() != null ? p.head() : head;
        headBone = bone(model, headName);
        bankBone = bone(model, p.body() != null ? p.body() : "root");
        breathBone = bone(model, p.body());
        tailBones = new GeoBone[p.tail().size()];
        for (int i = 0; i < tailBones.length; i++) tailBones[i] = bone(model, p.tail().get(i));
        earBones = new GeoBone[p.ears().size()];
        for (int i = 0; i < earBones.length; i++) earBones[i] = bone(model, p.ears().get(i));
        procFor = model;
    }

    private static @org.jspecify.annotations.Nullable GeoBone bone(BakedGeoModel model, @org.jspecify.annotations.Nullable String name) {
        return name == null ? null : model.getBone(name).orElse(null);
    }

    /** The bones the generator took glowmask cubes from ({@code glow_render_bones}); empty: draw the whole model. */
    private static String[] glowRenderBones(Species species) {
        if (!species.json.has("glow_render_bones") || !species.json.get("glow_render_bones").isJsonArray()) return new String[0];
        var a = species.json.getAsJsonArray("glow_render_bones");
        String[] out = new String[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsString();
        return out;
    }

    /**
     * Glow bones: the generated {@code <texture>_glowmask.png} drawn fullbright over the model. Only the bones that
     * have glowmask texels are drawn a second time (the species' {@code glow_render_bones}), each with the pose it
     * has this frame; a species without the list (hand-made, data pack) draws the whole model as GeckoLib does.
     */
    static final class GlowLayer<T extends Mob & ArcanaMob> extends AutoGlowingGeoLayer<T, Void, LivingEntityRenderState> {
        private final String[] boneNames;
        /** {@link #boneNames} resolved against {@link #bonesFor} (a resource reload bakes a new model). Render thread. */
        private GeoBone[] bones = new GeoBone[0];
        private @org.jspecify.annotations.Nullable BakedGeoModel bonesFor;
        private boolean warned;

        GlowLayer(ArcanaGeoRenderer<T> renderer, String[] boneNames) {
            super(renderer);
            this.boneNames = boneNames;
        }

        /** GeckoLib's emissive pipeline ({@code geckolib:pipeline/emissive}, used on Windows), for {@code IrisCompat}. */
        static com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline() {
            return RENDER_PIPELINE;
        }

        @Override
        public void submitRenderTask(RenderPassInfo<LivingEntityRenderState> info, SubmitNodeCollector collector) {
            if (info.getOrDefaultGeckolibData(GLOW_OFF, false)) return;
            float scale = info.getOrDefaultGeckolibData(DATA, Data.DEFAULT).glowScale();
            // the glow's strength through the vertex color: alpha for the translucent emissive pipeline, rgb for the
            // additive eyes type GeckoLib uses off Windows
            int color = info.renderColor();
            int scaled = Float.isNaN(scale) ? color : ARGB.color(Math.round(ARGB.alpha(color) * scale), Math.round(ARGB.red(color) * scale),
                    Math.round(ARGB.green(color) * scale), Math.round(ARGB.blue(color) * scale));
            // the whole model when the species names no glow bones or none of them are in this model (a resource
            // pack's geo renamed them): the glowmask still lines up with the model's own UVs
            GeoBone[] draw = boneNames.length == 0 || info.model().isMissingno() ? new GeoBone[0] : resolve(info.model());
            if (draw.length == 0) {
                // the color is read when the task is submitted
                info.renderState().addGeckolibData(DataTickets.RENDER_COLOR, scaled);
                super.submitRenderTask(info, collector);
                info.renderState().addGeckolibData(DataTickets.RENDER_COLOR, color);
                return;
            }
            if (!info.willRender()) return;
            RenderType type = getRenderType(info.renderState());   // keeps GeckoLib's invisible / outline handling
            if (type == null) return;
            // captured now: the geometry callback runs later, at draw time
            int light = getBrightness(info.renderState());
            int overlay = info.packedOverlay();
            collector.order(1).submitCustomGeometry(info.poseStack(), type, (pose, vertices) -> {
                PoseStack ps = info.poseStack();
                ps.pushPose();
                ps.last().set(pose);
                info.renderPosed(() -> {
                    for (GeoBone b : draw) {
                        if (hiddenByAncestor(b)) continue;
                        ps.pushPose();
                        toBone(ps, b);
                        b.render(info, ps, vertices, light, overlay, scaled);   // skips the bone when it is hidden itself
                        ps.popPose();
                    }
                });
                ps.popPose();
            });
        }

        private GeoBone[] resolve(BakedGeoModel model) {
            if (model == bonesFor) return bones;
            warned = false;   // a new bake (resource reload): report missing bones again
            java.util.List<GeoBone> out = new java.util.ArrayList<>(boneNames.length);
            for (String n : boneNames) {
                var b = model.getBone(n);
                if (b.isPresent()) out.add(b.get());
                else if (!warned) {
                    warned = true;
                    AftrHrs.LOG.warn("Glow bone {} is not in model {}", n, getRenderer().getGeoModel());
                }
            }
            if (out.isEmpty() && boneNames.length > 0)
                AftrHrs.LOG.warn("No glow bones of {} are in the model; the glow layer draws the whole model", getRenderer().getGeoModel());
            bones = out.toArray(new GeoBone[0]);
            bonesFor = model;
            return bones;
        }

        /** The bone's model-space matrix: every ancestor's then its own pose (what the model's own render applies). */
        private static void toBone(PoseStack ps, GeoBone b) {
            GeoBone p = b.parent();
            if (p != null) toBone(ps, p);
            RenderUtil.prepMatrixForBone(ps, b);
        }

        /** Partvis hides a bone with its children (skipChildrenRender): a hidden ancestor hides the glow bone too. */
        private static boolean hiddenByAncestor(GeoBone b) {
            for (GeoBone p = b.parent(); p != null; p = p.parent()) {
                if (p.frameSnapshot != null && p.frameSnapshot.areChildrenHidden()) return true;
            }
            return false;
        }
    }

    /**
     * The translucent texels of a model (glass, water): {@code <model>_glass.png} over the same posed model with
     * vanilla's culled entity-translucent render type (Iris maps it like any translucent entity; back faces culled, so
     * one pane of glass lies between the eye and the fish, not a stack of them). Opaque parts are fully transparent in
     * that texture, so only the glass shows; hidden bones stay hidden.
     */
    static final class GlassLayer<T extends Mob & ArcanaMob> extends com.geckolib.renderer.layer.GeoRenderLayer<T, Void, LivingEntityRenderState> {
        private final net.minecraft.resources.Identifier texture;

        GlassLayer(ArcanaGeoRenderer<T> renderer, net.minecraft.resources.Identifier texture) {
            super(renderer);
            this.texture = texture;
        }

        @Override
        protected net.minecraft.resources.Identifier getTextureResource(LivingEntityRenderState state) {
            return texture;
        }

        @Override
        public void submitRenderTask(RenderPassInfo<LivingEntityRenderState> info, SubmitNodeCollector collector) {
            if (!info.willRender() || info.renderState().isInvisible) return;
            renderer.submitRenderTasks(info, collector.order(1), net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucentCull(texture));
        }
    }
}
