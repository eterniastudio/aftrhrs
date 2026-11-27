package net.axther.aftrhrs.entity;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla behavior for the undead variants that stand in for a spider or a drowned (the same goals as
 * {@code Spider} / {@code Drowned}, whose own are private): spiders are neutral in the light and climb (the climbing
 * itself is {@link ArcanaMonster#onClimbable}); drowned swim after targets in the water, head for water by day and
 * for the shore at night.
 */
final class VariantGoals {
    private VariantGoals() {
    }

    // ------------------------------------------------------------------ spider

    /** Spider melee: in the light it may lose interest (1 in 100 each tick), like the vanilla spider. */
    static final class SpiderAttack extends MeleeAttackGoal {
        SpiderAttack(ArcanaMonster mob) {
            super(mob, 1.0, true);
        }

        @Override
        public boolean canUse() {
            return super.canUse() && !mob.isVehicle();
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.getLightLevelDependentMagicValue() >= 0.5F && mob.getRandom().nextInt(100) == 0) {
                mob.setTarget(null);
                return false;
            }
            return super.canContinueToUse();
        }
    }

    /** Spider targeting: only picks a target where it is dark (neutral by day, like the vanilla spider). */
    static final class SpiderTarget<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {
        SpiderTarget(ArcanaMonster mob, Class<T> type) {
            super(mob, type, true);
        }

        @Override
        public boolean canUse() {
            return mob.getLightLevelDependentMagicValue() < 0.5F && super.canUse();
        }
    }

    // ------------------------------------------------------------------ drowned

    /** Drowned melee: only while it is dark outside or the target is in the water. */
    static final class DrownedAttack extends MeleeAttackGoal {
        private final ArcanaMonster drowned;

        DrownedAttack(ArcanaMonster mob) {
            super(mob, 1.0, false);
            this.drowned = mob;
        }

        @Override
        public boolean canUse() {
            return super.canUse() && drowned.okDrownedTarget(drowned.getTarget());
        }

        @Override
        public boolean canContinueToUse() {
            return super.canContinueToUse() && drowned.okDrownedTarget(drowned.getTarget());
        }
    }

    /** By day, out of the water: walk back into the nearest water. */
    static final class GoToWater extends Goal {
        private final ArcanaMonster mob;
        private double wx, wy, wz;

        GoToWater(ArcanaMonster mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!mob.level().isBrightOutside() || mob.isInWater()) return false;
            Vec3 pos = waterPos();
            if (pos == null) return false;
            wx = pos.x;
            wy = pos.y;
            wz = pos.z;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return !mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            mob.getNavigation().moveTo(wx, wy, wz, 1.0);
        }

        private @Nullable Vec3 waterPos() {
            RandomSource random = mob.getRandom();
            BlockPos pos = mob.blockPosition();
            for (int i = 0; i < 10; i++) {
                BlockPos p = pos.offset(random.nextInt(20) - 10, 2 - random.nextInt(8), random.nextInt(20) - 10);
                if (mob.level().getBlockState(p).is(Blocks.WATER)) return Vec3.atBottomCenterOf(p);
            }
            return null;
        }
    }

    /** At night, near the surface: climb out onto the shore (where the players are). */
    static final class GoToBeach extends MoveToBlockGoal {
        private final ArcanaMonster drowned;

        GoToBeach(ArcanaMonster mob) {
            super(mob, 1.0, 8, 2);
            this.drowned = mob;
        }

        @Override
        public boolean canUse() {
            return super.canUse() && !drowned.level().isBrightOutside() && drowned.isInWater()
                    && drowned.getY() >= drowned.level().getSeaLevel() - 3;
        }

        @Override
        protected boolean isValidTarget(LevelReader level, BlockPos pos) {
            BlockPos above = pos.above();
            return level.isEmptyBlock(above) && level.isEmptyBlock(above.above()) && level.getBlockState(pos).entityCanStandOn(level, pos, drowned);
        }

        @Override
        public void start() {
            drowned.setSearchingForLand(false);
            super.start();
        }
    }

    /** At night, deep under water: swim up toward the surface. */
    static final class SwimUp extends Goal {
        private final ArcanaMonster drowned;
        private boolean stuck;

        SwimUp(ArcanaMonster mob) {
            this.drowned = mob;
        }

        private int seaLevel() {
            return drowned.level().getSeaLevel();
        }

        @Override
        public boolean canUse() {
            return !drowned.level().isBrightOutside() && drowned.isInWater() && drowned.getY() < seaLevel() - 2;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse() && !stuck;
        }

        @Override
        public void tick() {
            if (drowned.getY() < seaLevel() - 1 && (drowned.getNavigation().isDone() || drowned.closeToNextPos())) {
                Vec3 next = DefaultRandomPos.getPosTowards(drowned, 4, 8, new Vec3(drowned.getX(), seaLevel() - 1, drowned.getZ()), Math.PI / 2);
                if (next == null) {
                    stuck = true;
                    return;
                }
                drowned.getNavigation().moveTo(next.x, next.y, next.z, 1.0);
            }
        }

        @Override
        public void start() {
            drowned.setSearchingForLand(true);
            stuck = false;
        }

        @Override
        public void stop() {
            drowned.setSearchingForLand(false);
        }
    }

    /** The drowned's swim control: steers in 3D while it wants to swim in the water, else walks (and sinks). */
    static final class SwimMoveControl extends MoveControl<ArcanaMonster> {
        SwimMoveControl(ArcanaMonster mob) {
            super(mob);
        }

        @Override
        public void tick() {
            LivingEntity target = mob.getTarget();
            if (mob.wantsToSwim() && mob.isInWater()) {
                if (target != null && target.getY() > mob.getY() || mob.searchingForLand()) {
                    mob.setDeltaMovement(mob.getDeltaMovement().add(0.0, 0.002, 0.0));
                }
                if (operation != Operation.MOVE_TO || mob.getNavigation().isDone()) {
                    mob.setSpeed(0.0F);
                    return;
                }
                double xd = wantedX - mob.getX(), yd = wantedY - mob.getY(), zd = wantedZ - mob.getZ();
                double dd = Math.sqrt(xd * xd + yd * yd + zd * zd);
                yd /= dd;
                float yaw = (float) (Mth.atan2(zd, xd) * 180.0F / (float) Math.PI) - 90.0F;
                mob.setYRot(rotlerp(mob.getYRot(), yaw, 90.0F));
                mob.yBodyRot = mob.getYRot();
                float target2 = (float) (speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
                float speed = Mth.lerp(0.125F, mob.getSpeed(), target2);
                mob.setSpeed(speed);
                mob.setDeltaMovement(mob.getDeltaMovement().add(speed * xd * 0.005, speed * yd * 0.1, speed * zd * 0.005));
            } else {
                if (!mob.onGround()) mob.setDeltaMovement(mob.getDeltaMovement().add(0.0, -0.008, 0.0));
                super.tick();
            }
        }
    }
}
