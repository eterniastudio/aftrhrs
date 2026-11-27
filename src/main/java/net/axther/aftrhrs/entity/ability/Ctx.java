package net.axther.aftrhrs.entity.ability;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Conditions;
import net.axther.aftrhrs.entity.CreatureEvents;
import net.axther.aftrhrs.entity.CreatureUtil;
import net.axther.aftrhrs.entity.Scheduler;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a skill sees when it runs: the caster ({@link #mob}, and {@link #creature} for tameables), the inherited target
 * (owner / attack victim / trigger) and helpers that mirror the MythicMobs mechanics the specs use.
 */
public final class Ctx {
    private static final Pattern BOND = Pattern.compile("%bond\\(([-\\d.]+),([-\\d.]+)\\)%");
    private static final Pattern BONDLVL = Pattern.compile("%bondlvl\\(([-\\d.]+),([-\\d.]+)\\)%");

    public final ArcanaMob mob;
    public final Mob self;
    public final @Nullable ArcanaCreature creature;
    public final String skill;
    public @Nullable Entity target;
    public @Nullable Entity trigger;

    Ctx(ArcanaMob mob, String skill, @Nullable Entity target, @Nullable Entity trigger) {
        this.mob = mob;
        this.self = mob.self();
        this.creature = mob instanceof ArcanaCreature c ? c : null;
        this.skill = skill;
        this.target = target;
        this.trigger = trigger;
    }

    public ServerLevel level() {
        return (ServerLevel) self.level();
    }

    public Species species() {
        return mob.species();
    }

    public Scheduler sched() {
        return mob.scheduler();
    }

    // ------------------------------------------------------------------ bond

    public int bond() {
        return creature == null ? 0 : creature.bond();
    }

    /** {@code %bond(a,b)%} = a + b * bond. */
    public double bond(double base, double step) {
        return base + step * bond();
    }

    /** {@code %bondlvl(a,every)%} = a + floor(bond / every). */
    public int bondLvl(int base, int every) {
        return base + bond() / Math.max(1, every);
    }

    /** A MythicMobs numeric argument: a number or a bond macro. */
    public double num(@Nullable String text, double def) {
        if (text == null) return def;
        Matcher m = BOND.matcher(text);
        if (m.find()) return bond(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)));
        m = BONDLVL.matcher(text);
        if (m.find()) return bondLvl((int) Double.parseDouble(m.group(1)), (int) Double.parseDouble(m.group(2)));
        try {
            return Double.parseDouble(text.replace("'", ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ owner / state

    public boolean hasOwner() {
        return creature != null && creature.isTame();
    }

    public boolean staying() {
        return creature != null && creature.staying();
    }

    public @Nullable ServerPlayer owner() {
        return creature == null ? null : creature.ownerPlayer();
    }

    /** The owner if online, in this level and within {@code r} blocks (MythicMobs @Owner + distance{d=&lt;r}). */
    public @Nullable ServerPlayer ownerWithin(double r) {
        return creature == null ? null : creature.ownerWithin(r);
    }

    public boolean moving() {
        return self.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4 || !self.getNavigation().isDone();
    }

    public boolean inCombat() {
        return self.getTarget() != null;
    }

    public boolean busy() {
        return mob.busy();
    }

    /** A metaskill Cooldown in seconds; true (and started) if ready. */
    public boolean cooldown(String key, double seconds) {
        return sched().ready(key, (int) Math.round(seconds * 20));
    }

    public Scheduler.Seq seq() {
        return sched().seq();
    }

    /** Tells the pets module the ability did something (bond XP). */
    public void fired() {
        if (creature != null) CreatureEvents.ABILITY.invoker().onAbility(creature, skill);
    }

    // ------------------------------------------------------------------ animation / effects

    public void play(String clip) {
        mob.play(clip);
    }

    /** {@code state{s=ability}} unless the creature is told to stay (it keeps its sit pose). */
    public void ability() {
        mob.play(species().anim("ability"));
        net.axther.aftrhrs.entity.CreatureSfx.ability(mob, skill);
    }

    public void sound(String id, double v, double p) {
        Fx.sound(self, id, (float) v, (float) p);
    }

    public void sound(Entity at, String id, double v, double p) {
        Fx.sound(at, id, (float) v, (float) p);
    }

    public void particles(ParticleOptions p, int amount, double hs, double vs, double y) {
        Fx.particles(self, p, amount, hs, vs, y, 0.02);
    }

    public void particles(Entity at, ParticleOptions p, int amount, double hs, double vs, double y) {
        Fx.particles(at, p, amount, hs, vs, y, 0.02);
    }

    public void particles(Entity at, ParticleOptions p, int amount, double hs, double vs, double y, double speed) {
        Fx.particles(at, p, amount, hs, vs, y, speed);
    }

    public void hearts(Entity at, int n, double y) {
        particles(at, ParticleTypes.HEART, n, 0.3, 0.2, y);
    }

    public void line(Entity from, Entity to, ParticleOptions p, double step, double fromY, double toY) {
        Fx.line(level(), from.position().add(0, fromY, 0), to.position().add(0, toY, 0), p, step);
    }

    public void line(Entity from, Vec3 to, ParticleOptions p, double step, double fromY) {
        Fx.line(level(), from.position().add(0, fromY, 0), to, p, step);
    }

    public void potion(LivingEntity e, String type, int ticks, int level, boolean particles) {
        Fx.potion(e, type, ticks, level, particles, true);
    }

    /** {@link Fx#sustain}: a sustained buff re-applied only when under {@code minLeft} ticks remain (no packet otherwise). */
    public void sustain(LivingEntity e, String type, int ticks, int level, boolean particles, int minLeft) {
        Fx.sustain(e, type, ticks, level, particles, true, minLeft);
    }

    public void drop(ItemStack stack) {
        self.spawnAtLocation(level(), stack);
    }

    @SuppressWarnings("deprecation")
    public void actionBar(ServerPlayer p, String legacy) {
        CreatureUtil.actionBar(p, legacy);
    }

    /** Action-bar feedback from messages.yml {@code creatures.<key>} with {@code <pet>} = this creature's name (legacy
     *  fallback while the key is missing; see {@link CreatureUtil#say}). */
    public void actionBar(ServerPlayer p, String key, String legacyFallback, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        CreatureUtil.say(p, true, key, self, legacyFallback, tags);
    }

    public String name() {
        return self.getName().getString();
    }

    // ------------------------------------------------------------------ targeting

    public <T extends Entity> List<T> around(Entity center, double r, Class<T> type, Predicate<? super T> filter) {
        return Conditions.nearby(center, r, type, filter);
    }

    public <T extends Entity> List<T> around(Vec3 center, double r, Class<T> type, Predicate<? super T> filter) {
        return Conditions.nearby(self.level(), center, r, type, filter);
    }

    public Vec3 forward(double f) {
        Vec3 look = self.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0E-6) look = new Vec3(0, 0, 1);
        return self.position().add(look.normalize().scale(f));
    }
}
