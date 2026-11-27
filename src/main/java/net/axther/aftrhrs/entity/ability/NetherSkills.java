package net.axther.aftrhrs.entity.ability;

import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.Conditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.phys.Vec3;

/**
 * Creatures 2 / Nether pet jobs (Soulmander, Fumetail). Registered under their skill ids like {@link PetSkills}; the
 * species lines call them on their timers. Bond scaling follows the other pets: {@code bond(a, b)} = a + b * bond.
 */
public final class NetherSkills {
    private NetherSkills() {
    }

    /** Hoglins near a Fumetail's owner stay pacified this long (vanilla's warped fungus pacifies for 200 ticks). */
    public static final int MUSK_PACIFY_TICKS = 200;

    static void register() {
        // ---------------------------------------------------------------- Soulmander: soul ward, soul stride
        // Burns the Wither off its owner (wither skeletons, wither roses); cooldown 16 s -> 6 s with bond.
        Abilities.register("ArcanaSoulmander_SoulWard", c -> {
            ServerPlayer o = c.ownerWithin(8);
            if (o == null || c.staying() || !o.hasEffect(MobEffects.WITHER)) return;
            if (!c.cooldown("soulward", c.bond(16, -2))) return;
            o.removeEffect(MobEffects.WITHER);
            c.ability();
            c.line(c.self, o, ParticleTypes.SOUL_FIRE_FLAME, 0.4, 0.4, 1.0);
            c.particles(o, ParticleTypes.SOUL, 10, 0.4, 0.6, 1.0, 0.02);
            c.sound(o, "particle.soul_escape", 1.0, 1.2);
            c.sound("block.fire.extinguish", 0.4, 1.6);
            c.fired();
        });
        // On soul sand / soul soil its owner keeps their pace: Speed I, Speed II from bond 3.
        Abilities.register("ArcanaSoulmander_SoulStride", c -> {
            ServerPlayer o = c.ownerWithin(8);
            if (o == null || c.staying() || !onSoulBlock(o)) return;
            c.potion(o, "SPEED", 60, c.bondLvl(0, 3), true);
            c.particles(o, ParticleTypes.SOUL_FIRE_FLAME, 2, 0.3, 0.05, 0.1);
            c.fired();
        });

        // ---------------------------------------------------------------- Fumetail: fungal musk
        // Hoglins within bond(8, 1.6) blocks of the owner (and of the Fumetail itself) are pacified and walk away, as
        // they do from warped fungus: their attack target is dropped and they pick none for 10 s.
        Abilities.register("ArcanaFumetail_Musk", c -> {
            ServerPlayer o = c.ownerWithin(32);
            boolean follows = o != null && !c.staying() && o.distanceTo(c.self) <= 16;
            double r = c.bond(8, 1.6);
            int n = 0;
            if (follows) n += repel(c, o, r);
            n += repel(c, c.self, r);
            if (n > 0 && c.cooldown("muskfx", 6)) {
                c.ability();
                c.particles(ParticleTypes.WARPED_SPORE, 16, 0.6, 0.4, 0.8);
            }
            if (n > 0) c.fired();
        });
    }

    static boolean onSoulBlock(LivingEntity e) {
        BlockPos below = BlockPos.containing(e.getX(), e.getY() - 0.2, e.getZ());
        return e.level().getBlockState(below).is(BlockTags.SOUL_SPEED_BLOCKS)
                || e.level().getBlockState(e.blockPosition()).is(BlockTags.SOUL_SPEED_BLOCKS);
    }

    /** Pacifies and turns away every hoglin within {@code r} of {@code center}; returns how many. */
    static int repel(Ctx c, Entity center, double r) {
        int n = 0;
        for (Hoglin h : Conditions.nearby(center, r, Hoglin.class, Entity::isAlive)) {
            pacify(h, center);
            c.particles(h, ParticleTypes.WARPED_SPORE, 6, 0.4, 0.4, 1.0, 0.0);
            n++;
        }
        return n;
    }

    /** The warped-fungus reaction (HoglinAi: BecomePassiveIfMemoryPresent + SetWalkTargetAwayFrom the repellent). */
    public static void pacify(Hoglin h, Entity from) {
        var brain = h.getBrain();
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        brain.setMemoryWithExpiry(MemoryModuleType.PACIFIED, true, MUSK_PACIFY_TICKS);
        if (h.getTarget() != null) h.setTarget(null);
        Vec3 away = DefaultRandomPos.getPosAway(h, 12, 7, from.position());
        if (away != null) brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(away, 1.0f, 0));
    }

    /**
     * Stink spray (ArcanaEntities' owner-damage hook): a monster that hits the owner of a following Fumetail (within 16
     * blocks) is sprayed: Weakness for bond(80, 16) ticks, at most every 15 s per Fumetail.
     */
    static void ownerHurt(ServerPlayer owner, LivingEntity attacker) {
        if (!Conditions.isWildMonster(attacker)) return;
        for (ArcanaCreature c : Conditions.nearby(owner, 16, ArcanaCreature.class,
                x -> x.species().mythicId.equals("ArcanaFumetail") && x.isOwnedBy(owner) && !x.staying())) {
            if (!c.scheduler().ready("spray", 300)) continue;
            Ctx ctx = new Ctx(c, "ArcanaFumetail_Spray", attacker, attacker);
            ctx.ability();
            ctx.potion(attacker, "WEAKNESS", (int) ctx.bond(80, 16), 0, true);
            ctx.line(c, attacker, ParticleTypes.WARPED_SPORE, 0.3, 0.5, 1.0);
            ctx.particles(attacker, ParticleTypes.WARPED_SPORE, 20, 0.5, 0.6, 1.0, 0.02);
            ctx.fired();
            return;
        }
    }
}
