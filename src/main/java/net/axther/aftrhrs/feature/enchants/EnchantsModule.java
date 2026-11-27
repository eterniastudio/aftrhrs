package net.axther.aftrhrs.feature.enchants;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Aftr Hrs enchantments' behavior ({@code modules.enchants}, docs/enchants.md): melee, ranged and armor here; block
 * and drop effects in {@link EnchantHarvest}. Timberfall lives in the tools module's tree felling, the fishing three in
 * the fishing module and Soulbound in smithing's Soulbound; those follow the enchantment, not this toggle.
 * Listeners are registered once per JVM and do nothing while the module is off.
 */
public final class EnchantsModule implements FeatherModule {
    static volatile EnchantsModule active;
    private static final Identifier DRIFT_SPEED = Identifier.fromNamespaceAndPath("aftrhrs", "enchantment.driftwalker_snow");

    final Feather feather;
    final EnchantHarvest harvest = new EnchantHarvest(this);
    /** Marking: marked entity -> server tick the mark ends. */
    private final Map<UUID, Long> marks = new HashMap<>();
    /** Second Wind: player -> server tick it is ready again. */
    private final Map<UUID, Long> secondWind = new HashMap<>();
    /** Seeker arrows in flight and the ticks they have steered. */
    private final List<Seeking> seeking = new ArrayList<>();
    /** True while Cleave's splash hits land (they must not cleave, leech or mark again). */
    private boolean splashing;
    /** True while Volley adds its extra arrows (they must not volley again). */
    private boolean volleying;

    private static final class Seeking {
        final AbstractArrow arrow;
        LivingEntity target;
        int age;

        Seeking(AbstractArrow arrow) {
            this.arrow = arrow;
        }
    }

    public EnchantsModule(Feather feather) {
        this.feather = feather;
    }

    @Override
    public String name() {
        return "Enchantments";
    }

    @Override
    public void enable() {
        active = this;
        feather.once("enchants.events", EnchantsModule::register);
    }

    @Override
    public void disable() {
        marks.clear();
        secondWind.clear();
        seeking.clear();
        if (active == this) active = null;
    }

    /** Twin Hook's chance on this rod (the fishing module asks; works with the module off too, like the other rod enchants). */
    public static float twinHookChance(ItemStack rod) {
        return Enchants.TWIN_HOOK_PER_LEVEL * Enchants.level(rod, Enchants.TWIN_HOOK);
    }

    // ------------------------------------------------------------------ listeners

    private static void register() {
        FeatherEvents.DAMAGE_AMOUNT.register((victim, source, amount) -> {
            EnchantsModule m = active;
            return m == null ? amount : m.damageAmount(victim, source, amount);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            EnchantsModule m = active;
            return m == null || m.allowDamage(entity, source);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            EnchantsModule m = active;
            if (m != null && taken > 0) m.afterDamage(entity, source, taken);
        });
        FeatherEvents.ENTITY_ADDED.register(e -> {
            EnchantsModule m = active;
            if (m != null && e instanceof AbstractArrow a) m.arrowAdded(a);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            EnchantsModule m = active;
            if (m != null) m.tick(server);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            EnchantsModule m = active;
            if (m != null) m.secondWind.remove(handler.player.getUUID());
        });
        EnchantHarvest.register();
    }

    // ------------------------------------------------------------------ damage numbers

    /** The player behind a hit (melee or their projectile), else null. */
    private static ServerPlayer attacker(DamageSource source) {
        return source.getEntity() instanceof ServerPlayer p ? p : null;
    }

    /** A melee hit straight from the player's hand. */
    private static boolean melee(DamageSource source, ServerPlayer p) {
        return p != null && source.getDirectEntity() == p;
    }

    private float damageAmount(LivingEntity victim, DamageSource source, float amount) {
        if (amount <= 0) return amount;
        float a = amount;
        ServerPlayer p = attacker(source);
        if (melee(source, p) && !splashing) {
            ItemStack weapon = p.getMainHandItem();
            int cull = Enchants.level(weapon, Enchants.CULLING);
            if (cull > 0 && victim.getHealth() <= victim.getMaxHealth() * Enchants.CULL_BELOW) a *= 1 + Enchants.CULL_PER_LEVEL * cull;
            int giant = Enchants.level(weapon, Enchants.GIANTSLAYER);
            if (giant > 0 && victim.getMaxHealth() >= Enchants.GIANT_HEALTH) a *= 1 + Enchants.GIANT_PER_LEVEL * giant;
            int witch = Enchants.level(weapon, Enchants.WITCHING_HOUR);
            if (witch > 0 && night(p.level())) {
                float bonus = Enchants.WITCHING_PER_LEVEL * witch;
                if (net.axther.aftrhrs.feature.wildlife.WildlifeModule.restlessMoonNow()) bonus *= 2;
                a += bonus;
            }
        }
        // Marking: everyone's hits land harder on a marked target
        if (source.getEntity() instanceof Player && marked(victim)) a *= 1 + Enchants.MARK_BONUS;
        // Pack Bond: a tamed pet near its owner takes less
        if (victim instanceof TamableAnimal pet && pet.isTame()) {
            LivingEntity owner = pet.getOwner();
            if (owner instanceof ServerPlayer op && op.level() == pet.level() && op.distanceToSqr(pet) <= Enchants.PACK_RANGE * Enchants.PACK_RANGE) {
                int bond = Enchants.worn(op, EquipmentSlot.HEAD, Enchants.PACK_BOND);
                if (bond > 0) a *= Math.max(0, 1 - Enchants.PACK_PER_LEVEL * bond);
            }
        }
        return a;
    }

    private static boolean night(net.minecraft.world.level.Level level) {
        return level.dimensionType().hasSkyLight() && !level.isBrightOutside();
    }

    // ------------------------------------------------------------------ Deflection

    private boolean allowDamage(LivingEntity victim, DamageSource source) {
        if (!(source.getDirectEntity() instanceof Projectile proj) || proj.getOwner() == victim) return true;
        int lvl = Enchants.worn(victim, EquipmentSlot.CHEST, Enchants.DEFLECTION);
        if (lvl <= 0 || victim.getRandom().nextFloat() >= Enchants.DEFLECT_PER_LEVEL * lvl) return true;
        if (victim.level() instanceof ServerLevel sl) {
            sl.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 0.9f, 1.3f);
            sl.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 1.2, victim.getZ(), 10, 0.3, 0.3, 0.3, 0.2);
        }
        if (victim instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.translatable("aftrhrs.enchants.deflected"));
        return false; // an arrow that can't hurt bounces off by itself
    }

    // ------------------------------------------------------------------ on-hit effects

    private void afterDamage(LivingEntity victim, DamageSource source, float taken) {
        if (splashing || !(victim.level() instanceof ServerLevel level)) return;
        ServerPlayer p = attacker(source);
        if (melee(source, p)) {
            ItemStack weapon = p.getMainHandItem();
            int rime = Enchants.level(weapon, Enchants.RIMEBITE);
            if (rime > 0) {
                int frozen = Math.min(victim.getTicksRequiredToFreeze() - 1, 60 + 40 * rime);
                if (victim.getTicksFrozen() < frozen) victim.setTicksFrozen(frozen);
                level.sendParticles(ParticleTypes.SNOWFLAKE, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
                        6 + 4 * rime, 0.3, 0.4, 0.3, 0.02);
            }
            int leech = Enchants.level(weapon, Enchants.LEECHING);
            if (leech > 0 && !(victim instanceof ArmorStand) && p.getHealth() < p.getMaxHealth()) {
                p.heal(Math.min(Enchants.LEECH_MAX, taken * Enchants.LEECH_PER_LEVEL * leech));
            }
            int cleave = Enchants.level(weapon, Enchants.CLEAVE);
            if (cleave > 0 && weapon.is(ItemTags.AXES)) cleave(level, p, victim, taken * Enchants.CLEAVE_PER_LEVEL * cleave);
        } else if (p != null && source.getDirectEntity() instanceof AbstractArrow arrow) {
            ItemStack bow = arrow.getWeaponItem();
            int mark = Enchants.level(bow, Enchants.MARKING);
            if (mark > 0) {
                int ticks = Enchants.MARK_TICKS_PER_LEVEL * mark;
                marks.put(victim.getUUID(), (long) level.getServer().getTickCount() + ticks);
                victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false, true), p);
            }
            if (Enchants.level(bow, Enchants.HARPOON) > 0) harpoon(level, p, victim);
        }
        if (victim instanceof ServerPlayer hurt && hurt.isAlive()) secondWind(level, hurt);
    }

    /** Cleave: part of the hit lands on up to 3 other monsters around the target. */
    private void cleave(ServerLevel level, ServerPlayer p, LivingEntity victim, float damage) {
        if (damage < 0.5f) return;
        AABB box = victim.getBoundingBox().inflate(Enchants.CLEAVE_RANGE, 1, Enchants.CLEAVE_RANGE);
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != victim && e != p && e.isAlive() && e instanceof Enemy && !(e instanceof TamableAnimal t && t.isTame()));
        near.sort((x, y) -> Double.compare(x.distanceToSqr(victim), y.distanceToSqr(victim)));
        splashing = true;
        try {
            int n = 0;
            for (LivingEntity e : near) {
                if (n++ >= Enchants.CLEAVE_TARGETS) break;
                e.hurtServer(level, p.damageSources().playerAttack(p), damage);
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ(), 1, 0, 0, 0, 0);
            }
        } finally {
            splashing = false;
        }
    }

    /** Harpoon: the bolt's target is dragged toward the shooter. */
    private void harpoon(ServerLevel level, ServerPlayer p, LivingEntity victim) {
        if (victim instanceof Player || victim instanceof AbstractVillager || victim.getMaxHealth() >= 100 || !victim.isAlive()) return;
        if (victim instanceof TamableAnimal t && t.isTame() && !t.isOwnedBy(p)) return;
        if (!(victim instanceof Enemy) && !feather.claimAllows(p, Loc.of(level, victim.blockPosition()), ClaimAction.BUILD)) return;
        Vec3 to = p.position().subtract(victim.position());
        double dist = to.length();
        if (dist < 2) return;
        Vec3 pull = to.normalize().scale(Math.min(2.2, 0.4 + dist * 0.09));
        victim.setDeltaMovement(pull.x, Math.min(0.9, 0.25 + Math.max(0, to.y) * 0.08), pull.z);
        victim.syncVelocity = true;
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1f, 0.8f);
    }

    boolean marked(LivingEntity e) {
        Long until = marks.get(e.getUUID());
        if (until == null) return false;
        if (!(e.level() instanceof ServerLevel sl) || sl.getServer().getTickCount() < until) return true;
        marks.remove(e.getUUID());
        return false;
    }

    /** Second Wind: below 3 hearts, a burst of regeneration and absorption, once per cooldown. */
    private void secondWind(ServerLevel level, ServerPlayer p) {
        if (p.getHealth() >= Enchants.SECOND_WIND_BELOW || Enchants.worn(p, EquipmentSlot.CHEST, Enchants.SECOND_WIND) <= 0) return;
        long now = level.getServer().getTickCount();
        Long ready = secondWind.get(p.getUUID());
        if (ready != null && now < ready) return;
        secondWind.put(p.getUUID(), now + Enchants.SECOND_WIND_COOLDOWN);
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.35f, 1.6f);
        level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 1.4, p.getZ(), 6, 0.4, 0.3, 0.4, 0);
        p.sendOverlayMessage(Component.translatable("aftrhrs.enchants.second_wind"));
    }

    // ------------------------------------------------------------------ arrows: Seeker and Volley

    private void arrowAdded(AbstractArrow arrow) {
        if (!(arrow.getOwner() instanceof ServerPlayer p) || !(arrow.level() instanceof ServerLevel level)) return;
        ItemStack bow = arrow.getWeaponItem();
        if (bow == null || bow.isEmpty()) return;
        if (Enchants.level(bow, Enchants.SEEKER) > 0 && seeking.size() < 256) seeking.add(new Seeking(arrow));
        if (!volleying && arrow.isCritArrow() && bow.is(Items.BOW) && Enchants.level(bow, Enchants.VOLLEY) > 0) {
            Vec3 at = arrow.position(), v = arrow.getDeltaMovement();
            UUID owner = p.getUUID();
            ItemStack weapon = bow.copy();
            Tasks.later(1, () -> volley(level, owner, at, v, weapon));
        }
    }

    private void volley(ServerLevel level, UUID ownerId, Vec3 at, Vec3 v, ItemStack weapon) {
        ServerPlayer p = feather.player(ownerId);
        if (p == null || p.level() != level) return;
        volleying = true;
        try {
            for (int side = -1; side <= 1; side += 2) {
                double a = Math.toRadians(8 * side), cos = Math.cos(a), sin = Math.sin(a);
                Vec3 dir = new Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos);
                Arrow extra = new Arrow(level, p, new ItemStack(Items.ARROW), weapon);
                extra.setPos(at.x, at.y, at.z);
                extra.setDeltaMovement(dir);
                extra.setCritArrow(true);
                extra.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
                level.addFreshEntity(extra);
            }
        } finally {
            volleying = false;
        }
    }

    private void steer(Seeking s) {
        AbstractArrow a = s.arrow;
        Vec3 v = a.getDeltaMovement();
        double speed = v.length();
        if (speed < 0.3) return;
        if (s.target == null || !s.target.isAlive() || s.age % 5 == 0) s.target = seekTarget(a, v.scale(1 / speed));
        if (s.target == null) return;
        Vec3 to = s.target.position().add(0, s.target.getBbHeight() * 0.5, 0).subtract(a.position());
        if (to.lengthSqr() < 0.25) return;
        Vec3 dir = v.scale(1 / speed).lerp(to.normalize(), 0.2).normalize();
        a.setDeltaMovement(dir.scale(speed));
        a.syncVelocity = true;
    }

    private static LivingEntity seekTarget(AbstractArrow a, Vec3 heading) {
        AABB box = a.getBoundingBox().inflate(Enchants.SEEKER_RANGE);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : a.level().getEntitiesOfClass(LivingEntity.class, box, e -> e instanceof Enemy && e.isAlive())) {
            Vec3 to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(a.position());
            double d = to.lengthSqr();
            if (d > Enchants.SEEKER_RANGE * Enchants.SEEKER_RANGE || d < 1e-4) continue;
            if (to.normalize().dot(heading) < Enchants.SEEKER_CONE) continue;
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ every tick

    private void tick(MinecraftServer server) {
        for (Iterator<Seeking> it = seeking.iterator(); it.hasNext(); ) {
            Seeking s = it.next();
            if (s.arrow.isRemoved() || s.age++ > 100 || s.arrow.getDeltaMovement().lengthSqr() < 0.01) {
                it.remove();
                continue;
            }
            steer(s);
        }
        int t = server.getTickCount();
        if (t % 5 == 0) for (ServerPlayer p : server.getPlayerList().getPlayers()) driftwalker(p);
        if (t % 40 == 0) for (ServerPlayer p : server.getPlayerList().getPlayers()) nightsight(p);
        if (t % 100 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) packBond(p);
            marks.values().removeIf(until -> until < t);
            secondWind.values().removeIf(ready -> ready < t);
        }
    }

    /** Driftwalker: faster on snow (walking on powder snow is {@code PowderSnowWalkMixin}). */
    private static void driftwalker(ServerPlayer p) {
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        int lvl = Enchants.worn(p, EquipmentSlot.FEET, Enchants.DRIFTWALKER);
        boolean on = lvl > 0 && !p.isSpectator() && onSnow(p);
        if (on) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(DRIFT_SPEED, Enchants.DRIFT_SPEED_PER_LEVEL * lvl,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        } else if (speed.hasModifier(DRIFT_SPEED)) {
            speed.removeModifier(DRIFT_SPEED);
        }
    }

    static boolean onSnow(Entity e) {
        BlockPos feet = e.blockPosition();
        BlockState in = e.level().getBlockState(feet), below = e.level().getBlockState(feet.below());
        return snowy(in) || snowy(below);
    }

    private static boolean snowy(BlockState s) {
        return s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW);
    }

    /** Nightsight: Night Vision while standing in the dark (topped up before it can flicker). */
    private static void nightsight(ServerPlayer p) {
        if (Enchants.worn(p, EquipmentSlot.HEAD, Enchants.NIGHTSIGHT) <= 0 || p.isSpectator()) return;
        BlockPos eye = BlockPos.containing(p.getEyePosition());
        if (p.level().getMaxLocalRawBrightness(eye) > Enchants.NIGHTSIGHT_LIGHT) return;
        MobEffectInstance cur = p.getEffect(MobEffects.NIGHT_VISION);
        if (cur != null && (cur.isInfiniteDuration() || cur.getDuration() > 260)) return;
        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, true));
    }

    /** Pack Bond: tamed pets close to their owner slowly heal. */
    private static void packBond(ServerPlayer p) {
        int lvl = Enchants.worn(p, EquipmentSlot.HEAD, Enchants.PACK_BOND);
        if (lvl <= 0 || !(p.level() instanceof ServerLevel level)) return;
        AABB box = p.getBoundingBox().inflate(Enchants.PACK_RANGE);
        for (TamableAnimal pet : level.getEntitiesOfClass(TamableAnimal.class, box, t -> t.isTame() && t.isOwnedBy(p) && t.isAlive())) {
            if (pet.getHealth() < pet.getMaxHealth()) {
                pet.heal(lvl);
                level.sendParticles(ParticleTypes.HEART, pet.getX(), pet.getY() + pet.getBbHeight() + 0.2, pet.getZ(), 1, 0.2, 0.1, 0.2, 0);
            }
        }
    }
}
