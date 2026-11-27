package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Owned pets can't be hurt by other players, anywhere: hits, projectiles, fire from their weapons, harmful potions
 * and explosions a player set off. Their owner still can.
 */
final class PetGuard {
    private final PetsModule m;

    PetGuard(PetsModule module) {
        this.m = module;
    }

    private Pet pet(Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof Player) return null;
        Pet p = m.store().byEntity(e.getUUID());
        return p != null && p.alive() ? p : null;
    }

    /** The player behind this entity (itself, a projectile's shooter, whoever lit the TNT, a cloud's owner), or null. */
    static ServerPlayer source(Entity e) {
        if (e instanceof ServerPlayer p) return p;
        if (e instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer p) return p;
        if (e instanceof PrimedTnt t && t.getOwner() instanceof ServerPlayer p) return p;
        if (e instanceof AreaEffectCloud c && c.getOwner() instanceof ServerPlayer p) return p;
        return null;
    }

    private static ServerPlayer culprit(DamageSource source) {
        ServerPlayer p = FeatherEvents.attacker(source);
        if (p != null) return p;
        ServerPlayer a = source.getEntity() == null ? null : source(source.getEntity());
        return a != null ? a : source.getDirectEntity() == null ? null : source(source.getDirectEntity());
    }

    private static boolean stranger(Pet p, ServerPlayer by) {
        return by != null && !by.getUUID().equals(p.owner);
    }

    private void tell(ServerPlayer by, Pet p) {
        if (m.onCooldown("guard:" + by.getUUID(), 3000)) return;
        m.actionBar(by, "guard.protected", m.petTags(p), Text.unparsed("owner", m.nameOf(p.owner)));
    }

    /** ALLOW_DAMAGE: false cancels a stranger's damage. */
    boolean allowDamage(LivingEntity e, DamageSource source) {
        Pet p = pet(e);
        if (p == null) return true;
        ServerPlayer by = culprit(source);
        if (!stranger(p, by)) return true;
        tell(by, p);
        return false;
    }

    /** EFFECT_ADD: a stranger's harmful potion (splash, lingering, arrow) doesn't stick. */
    boolean allowEffect(LivingEntity e, MobEffectInstance effect, Entity source) {
        if (source == null || effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return true;
        Pet p = pet(e);
        return p == null || !stranger(p, source(source));
    }
}
