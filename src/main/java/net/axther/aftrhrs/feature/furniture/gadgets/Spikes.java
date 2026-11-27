package net.axther.aftrhrs.feature.furniture.gadgets;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Bamboo spikes: living things inside the block are slowed and hurt. Right-clicking with a lingering potion infuses
 * the spikes; infused spikes give that potion's effects to whatever walks over them and puff potion-colored
 * particles. With {@code infusion.charges: 0} (default) the spikes take only harmful potions and never run out; with
 * a positive number they take any potion and spend a charge each time they give the effects to someone lacking them.
 * The coating is kept as Paper did ({@code "minecraft:poison@0;..."}).
 */
final class Spikes {
    private final GadgetsModule g;
    private Set<String> ids = Set.of();
    private int interval = 20;
    private double damage = 2.0, height = 0.625;
    private int slowAmp = 2;
    private ResourceKey<DamageType> damageType = DamageTypes.CACTUS;
    private boolean hurtPlayers = true;
    private boolean infusion = true, onlyNegative = true;
    private int charges = 0, effectTicks = 100;
    private double particleChance = 0.25;
    private String infuseSound = "item.bottle.empty";

    Spikes(GadgetsModule g) {
        this.g = g;
    }

    void load(Yaml c) {
        ids = GadgetsModule.lowerSet(c.getStringList("ids"));
        if (ids.isEmpty() && !c.contains("ids")) ids = Set.of("arcana_bamboo_spikes");
        interval = Math.max(2, c.getInt("interval-ticks", 20));
        damage = Math.max(0, c.getDouble("damage", 2.0));
        height = c.getDouble("height", 0.625);
        slowAmp = c.getInt("slowness-amplifier", 2);
        hurtPlayers = c.getBoolean("hurt-players", true);
        Identifier key = Identifier.tryParse(c.getString("damage-type", "cactus").toLowerCase(Locale.ROOT));
        damageType = key == null ? DamageTypes.CACTUS : ResourceKey.create(Registries.DAMAGE_TYPE, key);
        infusion = c.getBoolean("infusion.enabled", true);
        charges = Math.max(0, c.getInt("infusion.charges", 0));
        onlyNegative = c.getBoolean("infusion.only-negative", true);
        effectTicks = Math.max(1, c.getInt("infusion.effect-ticks", 100));
        particleChance = c.getDouble("infusion.particle-chance", 0.25);
        infuseSound = c.getString("infusion.sound", "item.bottle.empty");
    }

    int interval() {
        return interval;
    }

    boolean isSpikes(String id) {
        return ids.contains(id);
    }

    // ------------------------------------------------------------------ infusion

    boolean interact(ServerPlayer p, ServerLevel level, GadgetBlockEntity be) {
        if (!infusion) return false;
        ItemStack held = p.getMainHandItem();
        if (!held.is(Items.LINGERING_POTION)) return false;
        if (!g.mayUse(p, level, be.getBlockPos(), ClaimAction.BUILD)) return true;
        PotionContents pc = held.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        List<MobEffectInstance> effects = new ArrayList<>();
        pc.getAllEffects().forEach(effects::add);
        if (effects.isEmpty()) {
            g.msg(p, g.message("spikes-no-effect", "<muted>That potion has no effect to give."));
            return true;
        }
        if (charges == 0 && onlyNegative) {
            for (MobEffectInstance e : effects) {
                if (e.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) {
                    g.msg(p, g.message("spikes-only-harmful", "<bad>The spikes only take harmful potions."));
                    return true;
                }
            }
        }
        be.spikeEffects = encode(effects);
        be.spikeColor = pc.getColor();
        be.spikeCharges = charges > 0 ? charges : null;
        be.setChanged();
        GadgetsModule.useOne(p, InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
        BlockPos b = be.getBlockPos();
        GadgetsModule.sound(level, net.minecraft.world.phys.Vec3.atCenterOf(b), infuseSound, SoundSource.BLOCKS, 1f, 1f);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF000000 | be.spikeColor),
                b.getX() + 0.5, b.getY() + 0.3, b.getZ() + 0.5, 20, 0.35, 0.2, 0.35, 0);
        g.msg(p, g.message("spikes-infused", "<good>The spikes drip with the potion."));
        return true;
    }

    private static String encode(List<MobEffectInstance> effects) {
        StringBuilder sb = new StringBuilder();
        for (MobEffectInstance e : effects) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(e.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:poison")).append('@').append(e.getAmplifier());
        }
        return sb.toString();
    }

    private static List<MobEffectInstance> decode(String s, int ticks) {
        List<MobEffectInstance> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            String[] kv = part.split("@");
            Identifier key = Identifier.tryParse(kv[0]);
            Optional<Holder.Reference<MobEffect>> type = key == null ? Optional.empty() : BuiltInRegistries.MOB_EFFECT.get(key);
            if (type.isEmpty()) continue;
            int amp = 0;
            try {
                if (kv.length > 1) amp = Integer.parseInt(kv[1]);
            } catch (NumberFormatException ignored) {
            }
            boolean instant = type.get().value().isInstantaneous();
            out.add(new MobEffectInstance(type.get(), instant ? 1 : ticks, amp, true, true));
        }
        return out;
    }

    // ------------------------------------------------------------------ the hurting

    void tick(ServerLevel level, GadgetBlockEntity be) {
        BlockPos b = be.getBlockPos();
        String fx = be.spikeEffects;
        if (fx != null && level.getRandom().nextDouble() < particleChance) {
            int rgb = be.spikeColor == 0 ? 0x7F3FBF : be.spikeColor & 0xFFFFFF;
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF000000 | rgb),
                    b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 2, 0.3, 0.15, 0.3, 0);
        }
        AABB box = new AABB(b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + height, b.getZ() + 1);
        for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (le instanceof ArmorStand || !le.isAlive()) continue;
            if (le instanceof Player pl && (pl.isCreative() || pl.isSpectator() || !hurtPlayers)) continue;
            le.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, interval + 10, slowAmp, true, false));
            if (fx != null) infect(le, be, fx);
            if (damage > 0) le.hurtServer(level, level.damageSources().source(damageType), (float) damage);
        }
    }

    private void infect(LivingEntity le, GadgetBlockEntity be, String fx) {
        List<MobEffectInstance> effects = decode(fx, effectTicks);
        boolean lacking = false;
        for (MobEffectInstance e : effects) if (e.getEffect().value().isInstantaneous() || !le.hasEffect(e.getEffect())) lacking = true;
        if (!lacking) return;
        for (MobEffectInstance e : effects) {
            MobEffectInstance has = le.getEffect(e.getEffect());
            if (e.getEffect().value().isInstantaneous()) {
                if (le.level() instanceof ServerLevel sl) e.getEffect().value().applyInstantaneousEffect(sl, null, null, le, e.getAmplifier(), 1.0);
            } else if (has == null || has.getDuration() < e.getDuration() / 2) {
                le.addEffect(e);
            }
        }
        Integer left = be.spikeCharges;
        if (left == null) return;
        if (left <= 1) {
            be.spikeEffects = null;
            be.spikeColor = 0;
            be.spikeCharges = null;
        } else {
            be.spikeCharges = left - 1;
        }
        be.setChanged();
    }
}
