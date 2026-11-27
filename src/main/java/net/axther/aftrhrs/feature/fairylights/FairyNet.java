/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/net/serverbound/InteractionConnectionMessage, EditLetteredConnectionMessage, Connection#interact.)
 */
package net.axther.aftrhrs.feature.fairylights;

import io.netty.buffer.ByteBuf;
import net.axther.aftrhrs.feature.fairylights.math.Curve;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Clicks on strands (the client finds which strand / light the crosshair is on) and the bunting letters editor. */
public final class FairyNet {
    private FairyNet() {
    }

    private static final StreamCodec<ByteBuf, Vec3> VEC = StreamCodec.composite(ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y,
            ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /** Client to server: punched ({@code attack}) or right-clicked a strand at {@code hit}; feature = light/pennant index or -1 (the cord). */
    public record Action(FastenerRef origin, UUID strand, boolean attack, int feature, Vec3 hit) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(net.axther.aftrhrs.AftrHrs.id("fairy_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
                FastenerRef.STREAM_CODEC, Action::origin, UUIDUtil.STREAM_CODEC, Action::strand, ByteBufCodecs.BOOL, Action::attack,
                ByteBufCodecs.VAR_INT, Action::feature, VEC, Action::hit, Action::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: open the letters editor of a pennant bunting ({@code max} pennants). */
    public record EditText(FastenerRef origin, UUID strand, String text, int max) implements CustomPacketPayload {
        public static final Type<EditText> TYPE = new Type<>(net.axther.aftrhrs.AftrHrs.id("fairy_edit_text"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditText> CODEC = StreamCodec.composite(
                FastenerRef.STREAM_CODEC, EditText::origin, UUIDUtil.STREAM_CODEC, EditText::strand, ByteBufCodecs.stringUtf8(64), EditText::text,
                ByteBufCodecs.VAR_INT, EditText::max, EditText::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: the bunting's new letters. */
    public record SetText(FastenerRef origin, UUID strand, String text) implements CustomPacketPayload {
        public static final Type<SetText> TYPE = new Type<>(net.axther.aftrhrs.AftrHrs.id("fairy_set_text"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetText> CODEC = StreamCodec.composite(
                FastenerRef.STREAM_CODEC, SetText::origin, UUIDUtil.STREAM_CODEC, SetText::strand, ByteBufCodecs.stringUtf8(64), SetText::text, SetText::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, ctx) -> act(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(SetText.TYPE, (payload, ctx) -> setText(ctx.player(), payload));
    }

    /** A hung strand the player may touch (loaded both ends, near enough, claims allow), or null. */
    private static @Nullable Strand touchable(ServerPlayer p, FastenerRef originRef, UUID id, @Nullable Vec3 hit, Fastener[] originOut) {
        if (!(p.level() instanceof ServerLevel level) || p.isSpectator()) return null;
        Fastener origin = originRef.resolve(level);
        Strand s = origin == null ? null : origin.strand(id);
        if (s == null || s.dest.isPlayer()) return null;
        Fastener other = s.dest.resolve(level);
        if (other == null) return null;
        Vec3 from = origin.point();
        Curve c = s.curve(from, other.point());
        Vec3 probe = hit != null ? hit : p.getEyePosition();
        double reach = hit != null ? 2.0 : 8.0;
        if (c.nearest(probe.subtract(from))[0] > reach * reach) return null;
        if (hit != null && p.getEyePosition().distanceToSqr(hit) > 7.5 * 7.5) return null;
        if (!StrandItem.allowed(p, level, origin.pos())) return null;
        originOut[0] = origin;
        return s;
    }

    public static void act(ServerPlayer p, Action a) {
        Fastener[] o = new Fastener[1];
        Strand s = touchable(p, a.origin(), a.strand(), a.hit(), o);
        if (s == null) return;
        ServerLevel level = (ServerLevel) p.level();
        Fastener origin = o[0];
        Vec3 hit = a.hit();
        if (a.attack()) {
            origin.removeStrand(level, s, hit, s.drop && !p.hasInfiniteMaterials());
            level.playSound(null, hit.x, hit.y, hit.z, FairyLights.SOUND_DISCONNECT, SoundSource.BLOCKS, 1, 1);
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = p.getItemInHand(hand);
            if (interact(level, p, origin, s, a.feature(), held, hit, hand == InteractionHand.MAIN_HAND)) {
                p.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
                origin.markDirty();
                return;
            }
        }
    }

    private static boolean interact(ServerLevel level, ServerPlayer p, Fastener origin, Strand s, int feature, ItemStack held, Vec3 hit, boolean main) {
        boolean creative = p.hasInfiniteMaterials();
        int n = s.features().size();
        boolean onFeature = feature >= 0 && feature < Math.max(n, 1);
        // slack: string loosens, a stick pulls it tighter (Fairy Lights)
        if (held.is(Items.STRING) || held.is(Items.STICK)) {
            float d = held.is(Items.STRING) ? 0.2f : -0.2f;
            float next = Math.clamp(s.slack + d, 0, Strand.MAX_SLACK);
            if (next < 1e-2f) next = 0;
            if (next != s.slack) {
                s.slack = next;
                s.invalidate();
                level.playSound(null, hit.x, hit.y, hit.z, FairyLights.SOUND_STRETCH, SoundSource.BLOCKS, 0.8f, 0.8f + (Strand.MAX_SLACK - s.slack) * 0.3f);
            }
            return true;
        }
        if (held.getItem() instanceof BulbItem && (s.kind == StrandKind.LIGHTS || s.kind == StrandKind.GARLAND)) {
            StrandSpec.Bulb nb = BulbItem.bulb(held);
            List<StrandSpec.Bulb> bulbs = new java.util.ArrayList<>(s.spec.bulbs());
            if (bulbs.isEmpty() || !onFeature) {
                // a bare strand: this light goes everywhere; clicking the cord adds it to the pattern
                if (bulbs.size() >= StrandSpec.MAX_PATTERN) return false;
                bulbs.add(nb);
            } else {
                bulbs = StrandSpec.expand(bulbs, n, nb);
                int i = Math.floorMod(feature, bulbs.size());
                StrandSpec.Bulb old = bulbs.get(i);
                if (old.equals(nb)) return false;
                bulbs.set(i, nb);
                if (!creative) give(p, BulbItem.stack(old));
            }
            if (!creative) held.shrink(1);
            s.spec = s.spec.withBulbs(bulbs);
            s.invalidate();
            level.playSound(null, hit.x, hit.y, hit.z, FairyLights.SOUND_SWAP, SoundSource.BLOCKS, 0.8f, 1.4f);
            return true;
        }
        if (held.getItem() instanceof PennantItem pi && s.kind == StrandKind.BUNTING) {
            StrandSpec.Flag nf = PennantItem.flag(held, pi);
            List<StrandSpec.Flag> flags = new java.util.ArrayList<>(s.spec.flags());
            if (flags.isEmpty() || !onFeature) {
                if (flags.size() >= StrandSpec.MAX_PATTERN) return false;
                flags.add(nf);
            } else {
                flags = StrandSpec.expand(flags, n, nf);
                int i = Math.floorMod(feature, flags.size());
                StrandSpec.Flag old = flags.get(i);
                if (old.equals(nf)) return false;
                flags.set(i, nf);
                if (!creative) give(p, PennantItem.stack(old));
            }
            if (!creative) held.shrink(1);
            s.spec = s.spec.withFlags(flags);
            s.invalidate();
            level.playSound(null, hit.x, hit.y, hit.z, net.minecraft.sounds.SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 1.2f);
            return true;
        }
        DyeColor dye = held.get(DataComponents.DYE);
        if (dye != null && held.is(net.minecraft.tags.ItemTags.DYES)) {
            int rgb = dye.getTextureDiffuseColor() & 0xFFFFFF;
            boolean done = false;
            if (s.kind == StrandKind.TINSEL && s.spec.color() != rgb) {
                s.spec = s.spec.withColor(rgb);
                done = true;
            } else if (s.kind == StrandKind.BUNTING && onFeature && !s.spec.flags().isEmpty()) {
                List<StrandSpec.Flag> flags = new java.util.ArrayList<>(StrandSpec.expand(s.spec.flags(), n, null));
                int i = Math.floorMod(feature, flags.size());
                if (flags.get(i).color() != rgb) {
                    flags.set(i, new StrandSpec.Flag(flags.get(i).shape(), rgb));
                    s.spec = s.spec.withFlags(flags);
                    done = true;
                }
            } else if ((s.kind == StrandKind.LIGHTS || s.kind == StrandKind.GARLAND) && onFeature && !s.spec.bulbs().isEmpty()) {
                List<StrandSpec.Bulb> bulbs = new java.util.ArrayList<>(StrandSpec.expand(s.spec.bulbs(), n, null));
                int i = Math.floorMod(feature, bulbs.size());
                StrandSpec.Bulb b = bulbs.get(i);
                if (b.style().dyeable && b.color() != rgb) {
                    bulbs.set(i, new StrandSpec.Bulb(b.style(), rgb, b.twinkle()));
                    s.spec = s.spec.withBulbs(bulbs);
                    done = true;
                }
            }
            if (!done) return false;
            if (!creative) held.shrink(1);
            s.invalidate();
            level.playSound(null, hit.x, hit.y, hit.z, net.minecraft.sounds.SoundEvents.DYE_USE, SoundSource.BLOCKS, 1, 1);
            return true;
        }
        if (held.is(Items.GLOWSTONE_DUST) && onFeature && !s.spec.bulbs().isEmpty()) {
            List<StrandSpec.Bulb> bulbs = new java.util.ArrayList<>(StrandSpec.expand(s.spec.bulbs(), n, null));
            int i = Math.floorMod(feature, bulbs.size());
            StrandSpec.Bulb b = bulbs.get(i);
            bulbs.set(i, new StrandSpec.Bulb(b.style(), b.color(), !b.twinkle()));
            s.spec = s.spec.withBulbs(bulbs);
            s.invalidate();
            if (!b.twinkle() && !creative) held.shrink(1);
            level.playSound(null, hit.x, hit.y, hit.z, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1, 1.5f);
            return true;
        }
        if (!held.isEmpty() || !main) return false;
        if (!s.spec.bulbs().isEmpty()) {
            s.mode = s.mode.next();
            s.lightsDirty = true;
            boolean on = s.mode != Strand.Mode.OFF;
            level.playSound(null, hit.x, hit.y, hit.z, on ? FairyLights.SOUND_ON : FairyLights.SOUND_OFF, SoundSource.BLOCKS, 0.8f, on ? 1.2f : 0.9f);
            p.sendOverlayMessage(Component.translatableWithFallback("aftrhrs.fairy_lights.mode." + s.mode.key(), switch (s.mode) {
                case ON -> "Lights: always on";
                case NIGHT -> "Lights: on from dusk to dawn";
                case OFF -> "Lights: off";
            }).withStyle(ChatFormatting.GOLD));
            return true;
        }
        if (s.kind == StrandKind.BUNTING && !s.spec.flags().isEmpty()) {
            ServerPlayNetworking.send(p, new EditText(origin.ref(), s.id, s.spec.text(), Math.min(StrandSpec.MAX_TEXT, Math.max(1, n))));
            return true;
        }
        return false;
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    static void setText(ServerPlayer p, SetText t) {
        Fastener[] o = new Fastener[1];
        Strand s = touchable(p, t.origin(), t.strand(), null, o);
        if (s == null || s.kind != StrandKind.BUNTING) return;
        String text = StrandSpec.cleanText(t.text());
        int n = s.features().size();
        if (text.length() > n) text = text.substring(0, n);
        if (text.equals(s.spec.text())) return;
        s.spec = s.spec.withText(text);
        o[0].markDirty();
    }
}
