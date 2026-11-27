package net.axther.aftrhrs.entity;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jspecify.annotations.Nullable;

/** Custom AI goals. */
final class Goals {
    private Goals() {
    }

    /**
     * MythicMobs {@code gotoparent{fr;mr;s}} for the young: follow the nearest grown-up of its kind within 16 blocks once
     * it is more than {@code followRange} away, until {@code minRange}. Ducklings (spec "line") follow the nearest adult
     * or another duckling that is already following, so they walk in a line. The leader found is kept and searched for
     * again only every 1-2 s (or at once when it is lost), so a herd of young does not scan its 33-block box every tick.
     */
    static final class FollowAdultGoal extends Goal {
        private final ArcanaWildlife young;
        private final double speed, minRange, followRange;
        private @Nullable Mob leader;
        /** The last leader found, reused between searches; and the canUse calls left until the next search. */
        private @Nullable Mob candidate;
        private int searchIn;
        private int recalc;

        FollowAdultGoal(ArcanaWildlife young, double speed, double minRange, double followRange) {
            this.young = young;
            this.speed = speed;
            this.minRange = minRange;
            this.followRange = followRange;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private @Nullable Mob findLeader() {
            Species s = young.species();
            String adult = s.youngOf();
            boolean line = s.flag("line");
            if (adult == null && !line) return null;
            List<Mob> near = Conditions.nearby(young, 16, Mob.class, m -> m instanceof ArcanaMob am && m.isAlive()
                    && (adult != null && adult.equals(am.species().mythicId) || line && am.species() == s));
            Mob best = null;
            double bestD = Double.MAX_VALUE;
            for (Mob m : near) {
                double d = m.distanceToSqr(young);
                if (d < bestD) {
                    bestD = d;
                    best = m;
                }
            }
            return best;
        }

        @Override
        public boolean canUse() {
            if (young.busy() || young.isLeashed()) return false;
            Mob cand = candidate;
            boolean lost = cand != null && (!cand.isAlive() || cand.isRemoved() || cand.level() != young.level() || young.distanceToSqr(cand) > 256);
            if (lost) candidate = null;
            if (lost || --searchIn <= 0) {
                candidate = findLeader();
                searchIn = reducedTickDelay(20 + young.getRandom().nextInt(20));   // canUse runs every 2nd tick: about 1-2 s
            }
            leader = candidate;
            return leader != null && young.distanceToSqr(leader) > followRange * followRange;
        }

        @Override
        public boolean canContinueToUse() {
            return leader != null && leader.isAlive() && !young.busy() && young.distanceToSqr(leader) > minRange * minRange
                    && young.distanceToSqr(leader) < 256;
        }

        @Override
        public void start() {
            recalc = 0;
        }

        @Override
        public void stop() {
            leader = null;
        }

        @Override
        public void tick() {
            if (leader == null || --recalc > 0) return;
            recalc = adjustedTickDelay(10);
            young.getNavigation().moveTo(leader, speed);
        }
    }

    /**
     * A tamed creature that is not busy glances at its owner now and then (within 10 blocks) and keeps an eye on them a
     * few seconds: the head turns (clamped by the renderer), the body follows only as far as vanilla's look control.
     */
    static final class LookAtOwnerGoal extends Goal {
        private final ArcanaCreature c;
        private net.minecraft.server.level.@Nullable ServerPlayer owner;
        private int left;

        LookAtOwnerGoal(ArcanaCreature c) {
            this.c = c;
            setFlags(EnumSet.of(Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!c.isTame() || c.busy() || c.getTarget() != null || c.getRandom().nextFloat() >= 0.03f) return false;
            owner = c.ownerWithin(10);
            return owner != null && !c.napping();
        }

        @Override
        public boolean canContinueToUse() {
            return owner != null && owner.isAlive() && left > 0 && !c.napping() && c.distanceToSqr(owner) < 144;
        }

        @Override
        public void start() {
            left = adjustedTickDelay(50 + c.getRandom().nextInt(70));
        }

        @Override
        public void stop() {
            owner = null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (owner == null) return;
            left--;
            c.getLookControl().setLookAt(owner.getX(), owner.getEyeY(), owner.getZ());
        }
    }
}
