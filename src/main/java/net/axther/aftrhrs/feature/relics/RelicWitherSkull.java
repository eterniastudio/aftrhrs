package net.axther.aftrhrs.feature.relics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiPredicate;

/**
 * The Witherbane's wither skull: a vanilla wither skull (same type, same look) that never breaks blocks and only
 * hurts anything where its caster may build (the claim at the impact point and at the entity it hit). Not saved: a
 * reload would bring it back as a griefing vanilla skull.
 */
final class RelicWitherSkull extends WitherSkull {
    private final BiPredicate<ServerPlayer, Vec3> mayHurtAt;

    RelicWitherSkull(Level level, LivingEntity owner, Vec3 direction, BiPredicate<ServerPlayer, Vec3> mayHurtAt) {
        super(EntityTypes.WITHER_SKULL, level);
        this.mayHurtAt = mayHurtAt;
        setOwner(owner);
        setRot(owner.getYRot(), owner.getXRot());
        setDeltaMovement(direction.normalize().scale(accelerationPower)); // vanilla assignDirectionalMovement
        needsSync = true;
    }

    private boolean allowed(Vec3 at) {
        return !(getOwner() instanceof ServerPlayer p) || mayHurtAt.test(p, at);
    }

    @Override
    protected void onHit(HitResult hit) {
        if (!(level() instanceof ServerLevel sl)) return;
        if (allowed(hit.getLocation())) {
            if (hit instanceof EntityHitResult eh && allowed(eh.getEntity().position())) onHitEntity(eh);
            sl.explode(this, getX(), getY(), getZ(), 1.0f, false, Level.ExplosionInteraction.NONE);
        }
        discard();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
