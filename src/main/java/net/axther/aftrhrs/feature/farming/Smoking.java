package net.axther.aftrhrs.feature.farming;

import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.feature.coast.Coast;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Smoking (farming.yml {@code smoking:}). A joint ({@code arcana_joint}) is rolled from one weed bud
 * ({@code arcana_crop_weed}) and one rolling paper; the joint takes the bud's {@code crop_quality} (the crafting grid's
 * result is adjusted through {@code FeatherEvents.CRAFT_RESULT}: the native recipe ignores item data, so a Fine or
 * Prize bud now uses the same recipe instead of Paper's hand-made craft). Smoking the joint (its {@code consumable},
 * about 2 s) gives Regeneration by quality plus the other effects, a puff of smoke at the mouth and a cooldown. A joint
 * without the consumable is smoked on right-click instead.
 */
final class Smoking {
    private final FarmingModule m;
    private final Map<UUID, Long> until = new HashMap<>();
    private final Map<UUID, Long> toldAt = new HashMap<>();

    Smoking(FarmingModule module) {
        this.m = module;
    }

    private FarmConfig cfg() {
        return m.config();
    }

    void clear() {
        until.clear();
        toldAt.clear();
    }

    // ------------------------------------------------------------------ crafting

    /** A joint of this quality: the crop_quality tag and the quality lore line (no weight: it isn't produce). */
    ItemStack joint(int quality) {
        ItemStack s = Coast.item(cfg().jointId, 1);
        if (s == null || quality <= 0) return s;
        Pdc.itemPutString(s, FarmingModule.QUALITY, FarmingModule.QUALITIES[quality]);
        Coast.addLore(s, m.text().item(quality == 2 ? cfg().lorePrize : cfg().loreFine));
        return s;
    }

    /** The grid's joint carries its bud's quality; a joint from anything but one bud and one paper is refused. */
    ItemStack craftResult(CraftingInput input, ItemStack result) {
        if (!cfg().smokeEnabled || result.isEmpty() || !cfg().jointId.equals(Coast.id(result))) return result;
        ItemStack bud = null;
        int papers = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            String id = Coast.id(s);
            if (bud == null && cfg().budId.equals(id)) bud = s;
            else if (cfg().paperId.equals(id)) papers++;
            else return ItemStack.EMPTY;
        }
        if (bud == null || papers != 1) return ItemStack.EMPTY;
        int q = FarmingModule.qualityOf(bud);
        if (q <= 0) return result;
        ItemStack j = joint(q);
        if (j == null) return result;
        j.setCount(result.getCount());
        return j;
    }

    // ------------------------------------------------------------------ smoking

    private boolean isJoint(ItemStack s) {
        return cfg().smokeEnabled && s != null && cfg().jointId.equals(Coast.id(s));
    }

    long cooldownLeft(ServerPlayer p) {
        Long t = until.get(p.getUUID());
        return t == null ? 0 : Math.max(0, t - System.currentTimeMillis());
    }

    private void tellWait(ServerPlayer p, long leftMs) {
        long now = System.currentTimeMillis();
        Long last = toldAt.get(p.getUUID());
        if (last != null && now - last < 800) return;
        toldAt.put(p.getUUID(), now);
        m.bar(p, "smoke-wait", Text.unparsed("seconds", Math.max(1, (leftMs + 999) / 1000)));
    }

    /** A right-click with a joint: waits out the cooldown, or smokes one that has no consumable. PASS otherwise. */
    InteractionResult use(ServerPlayer p, InteractionHand hand, ItemStack held, String heldId) {
        if (!isJoint(held)) return InteractionResult.PASS;
        long left = cooldownLeft(p);
        if (left > 0) {
            tellWait(p, left);
            return InteractionResult.FAIL;
        }
        if (!held.has(DataComponents.CONSUMABLE)) {
            ItemStack one = held.copy();
            Coast.takeOne(p, hand);
            smoke(p, one);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    void consumed(ServerPlayer p, ItemStack used) {
        if (isJoint(used)) smoke(p, used);
    }

    /** The buff (Regeneration by the joint's quality, then the rest), the puff of smoke and the cooldown. */
    void smoke(ServerPlayer p, ItemStack joint) {
        FarmConfig c = cfg();
        int q = FarmingModule.qualityOf(joint);
        if (c.smokeRegen[q] > 0) {
            m.applyEffect(p, new MobEffectInstance(MobEffects.REGENERATION, c.smokeRegen[q] * 20, c.smokeRegenLevel - 1, false, true, true));
        }
        for (FarmConfig.Effect e : c.smokeEffects) {
            m.applyEffect(p, new MobEffectInstance(e.type(), e.seconds() * 20, e.level() - 1, false, true, true));
        }
        until.put(p.getUUID(), System.currentTimeMillis() + c.smokeCooldownMs);
        // the joint's own cooldown group shows the wait on the hotbar (never without a group: that would be every paper item)
        UseCooldown uc = joint.get(DataComponents.USE_COOLDOWN);
        if (uc != null && uc.cooldownGroup().isPresent()) {
            int ticks = (int) Math.min(Integer.MAX_VALUE, c.smokeCooldownMs / 50);
            var group = uc.cooldownGroup().get();
            net.axther.aftrhrs.core.Tasks.later(1, () -> {
                if (!p.hasDisconnected()) p.getCooldowns().addCooldown(group, ticks);
            });
        }
        puff(p);
        m.bar(p, "smoked");
    }

    /** A soft exhale: campfire smoke drifting forward from the mouth, and the configured sound. */
    private void puff(ServerPlayer p) {
        FarmConfig c = cfg();
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getViewVector(1f);
        Vec3 mouth = eye.add(dir.scale(0.3)).add(0, -0.15, 0);
        ServerLevel w = p.level();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < c.smokeParticles; i++) {
            Vec3 v = dir.scale(0.6).add(r.nextGaussian() * 0.15, 0.1 + r.nextDouble() * 0.15, r.nextGaussian() * 0.15);
            w.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, mouth.x, mouth.y, mouth.z, 0, v.x, v.y, v.z, 0.04);
        }
        if (c.smokeSound != null && !c.smokeSound.isBlank() && c.smokeVolume > 0) {
            Coast.sound(w, mouth, c.smokeSound, SoundSource.PLAYERS, c.smokeVolume, c.smokePitch);
        }
    }
}
