package net.axther.aftrhrs.feature.altars;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The boss relics with abilities of their own (tools.yml {@code relics:}; on Paper part of the arcana-tools module, so
 * they follow {@code modules.arcana-tools}):
 * <ul>
 * <li><b>Cinderplume</b> (Cinderwing Phoenix): held in either hand, a hit that would kill you instead rebirths you in
 * fire - you're left on some health with fire resistance and regeneration, and a burst of flame throws back and burns
 * the enemies around you. Once per cooldown (kept on the player across restarts).</li>
 * <li><b>Galeheart</b> (Tempest Roc): right-click for a gust leap up and forward, with no fall damage for that leap; a
 * critical hit while diving (falling far enough) is a storm dive: bonus damage, a thunderclap that knocks nearby enemies
 * away, and the fall is forgiven. Both cooldown-gated.</li>
 * </ul>
 */
public final class BossRelics implements FeatherModule {
    private static volatile BossRelics active;
    /** Player Pdc keys, Paper's feather:* PDC keys (epoch ms when ready again). */
    static final String REBIRTH_READY = "feather:cinderplume_ready_at", LEAP_READY = "feather:galeheart_leap_ready_at",
            DIVE_READY = "feather:galeheart_dive_ready_at";

    private final Feather feather;

    private boolean rebirthOn;
    private String rebirthItem = "arcana_relic_cinderplume";
    private long rebirthCooldownMs;
    private double rebirthHealth, burstRadius, burstDamage;
    private int fireResistTicks, regenTicks, burstFireTicks;
    private String msgReborn, msgRebirthReady, msgRebirthCooldown;

    private boolean galeOn;
    private String galeItem = "arcana_relic_galeheart";
    private int leapCooldownTicks, diveCooldownTicks;
    private double leapUp, leapForward, diveMinFall, diveBonus, diveRadius, diveSplash;
    private String msgLeapCooldown, msgDive;

    /**
     * A fall forgiven until {@code until} (ms): after a gust leap only the height of the leap itself ({@code startY}: a
     * landing below where the leap began takes the damage for the difference), after a storm dive all of it (NaN).
     */
    private record Forgive(long until, double startY) {
    }

    private final Map<UUID, Forgive> leaping = new HashMap<>();
    private final Set<UUID> reminders = new HashSet<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public BossRelics(Feather feather) {
        this.feather = feather;
    }

    @Override
    public String name() {
        return "Boss relics";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        feather.once("relics.events", BossRelics::registerEvents);
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        leaping.clear();
    }

    @Override
    public void reload() {
        Yaml c = feather.loadResourceConfig("tools.yml");
        // relics were part of Paper's arcana-tools module
        boolean tools = feather.getConfig().getBoolean("modules.arcana-tools", true);
        rebirthOn = tools && c.getBoolean("relics.cinderplume.enabled", true);
        rebirthItem = c.getString("relics.cinderplume.item", "arcana_relic_cinderplume").toLowerCase();
        rebirthCooldownMs = Math.max(10, c.getLong("relics.cinderplume.cooldown-seconds", 600)) * 1000L;
        rebirthHealth = Math.max(1, c.getDouble("relics.cinderplume.rebirth-health", 8));
        fireResistTicks = (int) Math.round(Math.max(0, c.getDouble("relics.cinderplume.fire-resistance-seconds", 10)) * 20);
        regenTicks = (int) Math.round(Math.max(0, c.getDouble("relics.cinderplume.regeneration-seconds", 5)) * 20);
        burstRadius = Math.max(0, Math.min(12, c.getDouble("relics.cinderplume.burst-radius", 5)));
        burstDamage = Math.max(0, c.getDouble("relics.cinderplume.burst-damage", 6));
        burstFireTicks = (int) Math.round(Math.max(0, c.getDouble("relics.cinderplume.burst-fire-seconds", 4)) * 20);
        msgReborn = c.getString("relics.cinderplume.message-reborn");
        msgRebirthReady = c.getString("relics.cinderplume.message-ready");
        msgRebirthCooldown = c.getString("relics.cinderplume.message-cooldown");

        galeOn = tools && c.getBoolean("relics.galeheart.enabled", true);
        galeItem = c.getString("relics.galeheart.item", "arcana_relic_galeheart").toLowerCase();
        leapCooldownTicks = (int) Math.round(Math.max(1, c.getDouble("relics.galeheart.leap-cooldown-seconds", 12)) * 20);
        leapUp = Math.max(0.1, Math.min(2.5, c.getDouble("relics.galeheart.leap-up", 1.15)));
        leapForward = Math.max(0, Math.min(3, c.getDouble("relics.galeheart.leap-forward", 1.1)));
        diveCooldownTicks = (int) Math.round(Math.max(0, c.getDouble("relics.galeheart.dive-cooldown-seconds", 8)) * 20);
        diveMinFall = Math.max(0, c.getDouble("relics.galeheart.dive-min-fall", 2.5));
        diveBonus = Math.max(0, c.getDouble("relics.galeheart.dive-bonus-damage", 6));
        diveRadius = Math.max(0, Math.min(10, c.getDouble("relics.galeheart.dive-radius", 3.5)));
        diveSplash = Math.max(0, c.getDouble("relics.galeheart.dive-splash-damage", 4));
        msgLeapCooldown = c.getString("relics.galeheart.message-cooldown");
        msgDive = c.getString("relics.galeheart.message-dive");
    }

    private static void registerEvents() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            BossRelics m = active;
            return m == null || !(entity instanceof ServerPlayer p) || !m.lethalHit(p, source);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            BossRelics m = active;
            return m == null || !(entity instanceof ServerPlayer p) || !m.forgiveFall(p, source);
        });
        FeatherEvents.DAMAGE_AMOUNT.register((victim, source, amount) -> {
            BossRelics m = active;
            return m == null ? amount : m.damage(victim, source, amount);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            BossRelics m = active;
            if (m == null || !(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            return m.leap(p) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            BossRelics m = active;
            if (m != null) m.join(h.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            BossRelics m = active;
            if (m != null) {
                m.leaping.remove(h.player.getUUID());
                m.lastUse.remove(h.player.getUUID());
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Ms until a cooldown kept in the player's Pdc is over (0 when ready; an expired entry is removed). */
    private static long left(ServerPlayer p, String key) {
        long ready = Pdc.getLong(p, key, 0);
        if (ready == 0) return 0;
        long left = ready - System.currentTimeMillis();
        if (left <= 0) Pdc.remove(p, key);
        return Math.max(0, left);
    }

    private static boolean holds(ServerPlayer p, String id, boolean offHandToo) {
        if (id.equals(ArcanaContent.id(p.getMainHandItem()))) return true;
        return offHandToo && id.equals(ArcanaContent.id(p.getOffhandItem()));
    }

    private void bar(ServerPlayer p, String mini, String seconds) {
        if (mini == null || mini.isBlank()) return;
        Adv.actionBar(p, feather.text().parse(mini, Placeholder.unparsed("seconds", seconds)));
    }

    private void message(ServerPlayer p, String mini, TagResolver... r) {
        if (mini != null && !mini.isBlank()) Adv.send(p, feather.text().parse(mini, r));
    }

    private static String seconds(long ms) {
        return String.valueOf(Math.max(1, (ms + 999) / 1000));
    }

    /** The item cooldown group of a relic (the hotbar sweep). */
    private static Identifier group(ServerPlayer p, String itemId) {
        ItemStack stack = ArcanaContent.stack(itemId, 1);
        return stack.isEmpty() ? Identifier.fromNamespaceAndPath("aftrhrs", itemId) : p.getCooldowns().getCooldownGroup(stack);
    }

    /** Enemies a relic burst may hit: hostile mobs, whatever hit or targets the player, never players, pets or stands. */
    private static boolean foe(ServerPlayer p, Entity e, Entity attacker) {
        if (!(e instanceof LivingEntity le) || e == p || !le.isAlive() || e instanceof Player || e instanceof ArmorStand) return false;
        if (e instanceof OwnableEntity o && o.getOwnerReference() != null) return false;
        if (e.equals(attacker)) return true;
        if (e instanceof Mob m && p.equals(m.getTarget())) return true;
        return e instanceof Enemy;
    }

    private static void sound(ServerLevel level, Vec3 at, String key, float volume, float pitch) {
        SoundEvent e = new Species.Sound(key, volume, pitch).event();
        if (e != null) level.playSound(null, at.x, at.y, at.z, e, SoundSource.PLAYERS, volume, pitch);
    }

    private static void push(LivingEntity le, Vec3 from, double strength, double up) {
        Vec3 d = le.position().subtract(from).multiply(1, 0, 1);
        d = d.lengthSqr() < 1e-4 ? Vec3.ZERO : d.normalize().scale(strength);
        le.setDeltaMovement(le.getDeltaMovement().add(d.x, up, d.z));
        le.needsSync = true;
    }

    // ------------------------------------------------------------------ Cinderplume: rebirth

    /** ALLOW_DEATH: true when the Cinderplume saved the player (the death is cancelled). */
    private boolean lethalHit(ServerPlayer p, DamageSource source) {
        if (!rebirthOn) return false;
        // /kill, the void and the world border can't be survived
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.OUTSIDE_BORDER)) return false;
        if (p.isCreative() || p.isSpectator()) return false;
        if (!holds(p, rebirthItem, true)) return false;
        // A totem of undying in hand goes first (vanilla), the relic stays ready.
        if (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return false;
        long now = System.currentTimeMillis();
        long ready = Pdc.getLong(p, REBIRTH_READY, 0);
        if (ready > now) {
            bar(p, msgRebirthCooldown, seconds(ready - now));
            return false;
        }
        Entity attacker = source.getEntity();
        rebirth(p, attacker, now);
        return true;
    }

    private void rebirth(ServerPlayer p, Entity attacker, long now) {
        float max = p.getMaxHealth();
        p.setHealth((float) Math.max(Math.max(0, p.getHealth()), Math.min(max, rebirthHealth)));
        p.clearFire();
        p.resetFallDistance();
        if (fireResistTicks > 0) p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, fireResistTicks, 0));
        if (regenTicks > 0) p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, regenTicks, 1));
        Pdc.putLong(p, REBIRTH_READY, now + rebirthCooldownMs);
        showRebirthCooldown(p, rebirthCooldownMs);

        ServerLevel level = (ServerLevel) p.level();
        Vec3 at = p.position().add(0, 1, 0);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y, at.z, 60, 0.4, 0.8, 0.4, 0.4);
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 120, burstRadius * 0.35, 0.6, burstRadius * 0.35, 0.12);
        level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 16, 0.8, 0.4, 0.8, 0);
        sound(level, at, "item.totem.use", 0.8f, 1.2f);
        sound(level, at, "entity.blaze.shoot", 1.2f, 0.7f);
        if (burstRadius > 0) {
            for (Entity e : level.getEntities(p, new AABB(at, at).inflate(burstRadius))) {
                if (!foe(p, e, attacker) || e.position().distanceToSqr(at) > burstRadius * burstRadius) continue;
                LivingEntity le = (LivingEntity) e;
                if (burstDamage > 0) le.hurtServer(level, level.damageSources().playerAttack(p), (float) burstDamage);
                if (burstFireTicks > 0) le.igniteForTicks(Math.max(le.getRemainingFireTicks(), burstFireTicks));
                push(le, p.position(), 1.1, 0.45);
            }
        }
        message(p, msgReborn, Placeholder.unparsed("seconds", seconds(rebirthCooldownMs)),
                Placeholder.unparsed("minutes", String.valueOf(Math.max(1, Math.round(rebirthCooldownMs / 60000.0)))));
        feather.getLogger().info("{} was reborn by the Cinderplume.", p.getGameProfile().name());
    }

    /** The item's cooldown sweep for the rest of the rebirth cooldown, and a note when it is ready again. */
    private void showRebirthCooldown(ServerPlayer p, long remainingMs) {
        if (remainingMs <= 0) return;
        int ticks = (int) Math.min(Integer.MAX_VALUE, remainingMs / 50);
        p.getCooldowns().addCooldown(group(p, rebirthItem), ticks);
        UUID id = p.getUUID();
        if (!reminders.add(id)) return;
        Tasks.later(ticks + 1L, () -> {
            reminders.remove(id);
            ServerPlayer online = feather.player(id);
            if (online == null) return;
            long ready = Pdc.getLong(online, REBIRTH_READY, 0);
            if (ready > System.currentTimeMillis() + 1000) return;   // reborn again meanwhile
            message(online, msgRebirthReady);
        });
    }

    private void join(ServerPlayer p) {
        if (galeOn) {
            // the leap's cooldown sweep on the hotbar (item cooldowns aren't saved)
            long leap = left(p, LEAP_READY);
            if (leap > 0) p.getCooldowns().addCooldown(group(p, galeItem), (int) Math.min(Integer.MAX_VALUE, leap / 50));
            left(p, DIVE_READY);
        }
        if (!rebirthOn) return;
        long ready = Pdc.getLong(p, REBIRTH_READY, 0);
        if (ready == 0) return;
        long left = ready - System.currentTimeMillis();
        if (left <= 0) Pdc.remove(p, REBIRTH_READY);
        else showRebirthCooldown(p, left);
    }

    // ------------------------------------------------------------------ Galeheart: gust leap + storm dive

    /** Right-click with the Galeheart (in the air, or on a block that didn't use the click). True when used. */
    private boolean leap(ServerPlayer p) {
        if (!galeOn || !galeItem.equals(ArcanaContent.id(p.getMainHandItem()))) return false;
        long nowTick = Tasks.now();
        Long last = lastUse.get(p.getUUID());
        if (last != null && nowTick - last >= 0 && nowTick - last < 5) return true;
        lastUse.put(p.getUUID(), nowTick);
        if (p.isSpectator() || p.isPassenger() || p.isFallFlying()) return true;
        ItemStack held = p.getMainHandItem();
        long cooldownMs = p.getCooldowns().isOnCooldown(held)
                ? (long) (p.getCooldowns().getCooldownPercent(held, 0) * leapCooldownTicks * 50L) : 0;
        long left = Math.max(cooldownMs, left(p, LEAP_READY));
        if (left > 0) {
            bar(p, msgLeapCooldown, seconds(left));
            return true;
        }
        Vec3 dir = p.getLookAngle().multiply(1, 0, 1);
        if (dir.lengthSqr() > 1e-4) dir = dir.normalize().scale(leapForward);
        p.setDeltaMovement(dir.x, leapUp, dir.z);
        p.needsSync = true;
        p.resetFallDistance();
        long now = System.currentTimeMillis();
        leaping.put(p.getUUID(), new Forgive(now + 8000, p.getY()));
        p.getCooldowns().addCooldown(group(p, galeItem), leapCooldownTicks);
        Pdc.putLong(p, LEAP_READY, now + leapCooldownTicks * 50L);
        ServerLevel level = (ServerLevel) p.level();
        level.sendParticles(ParticleTypes.GUST, p.getX(), p.getY() + 0.2, p.getZ(), 3, 0.4, 0.1, 0.4, 0);
        level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.2, p.getZ(), 20, 0.5, 0.1, 0.5, 0.05);
        sound(level, p.position(), "entity.breeze.wind_burst", 1.0f, 1.1f);
        return true;
    }

    /** ALLOW_DAMAGE: a fall fully forgiven after a leap (landing no lower than it began) or a storm dive. */
    private boolean forgiveFall(ServerPlayer p, DamageSource source) {
        if (!source.is(DamageTypeTags.IS_FALL)) return false;
        Forgive f = leaping.get(p.getUUID());
        if (f == null) return false;
        if (f.until() < System.currentTimeMillis()) {
            leaping.remove(p.getUUID());
            return false;
        }
        if (!Double.isNaN(f.startY()) && f.startY() - p.getY() - 3 > 0) return false;   // partly: DAMAGE_AMOUNT caps it
        leaping.remove(p.getUUID());
        ((ServerLevel) p.level()).sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.1, p.getZ(), 12, 0.4, 0.05, 0.4, 0.02);
        return true;
    }

    /** DAMAGE_AMOUNT: a leap that landed lower than it began hurts for the difference only; storm dive bonus damage. */
    private float damage(LivingEntity victim, DamageSource source, float amount) {
        if (victim instanceof ServerPlayer p && source.is(DamageTypeTags.IS_FALL)) {
            Forgive f = leaping.get(p.getUUID());
            if (f == null || Double.isNaN(f.startY()) || f.until() < System.currentTimeMillis()) return amount;
            double below = f.startY() - p.getY() - 3;   // vanilla: the first 3 blocks are free
            if (below <= 0) return amount;              // ALLOW_DAMAGE cancels it
            leaping.remove(p.getUUID());
            return (float) Math.min(amount, Math.ceil(below));
        }
        if (galeOn && source.getDirectEntity() instanceof ServerPlayer p && source.getEntity() == p && victim != p) return dive(p, victim, amount);
        return amount;
    }

    /** Vanilla's critical-hit conditions (minus the full-strength swing, already spent by now). */
    private static boolean critical(ServerPlayer p) {
        return p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater() && !p.isPassenger() && !p.isSprinting()
                && !p.hasEffect(MobEffects.BLINDNESS);
    }

    private float dive(ServerPlayer p, LivingEntity target, float amount) {
        if (!critical(p) || p.fallDistance < diveMinFall) return amount;
        if (!holds(p, galeItem, false)) return amount;
        if (left(p, DIVE_READY) > 0) return amount;
        long nowMs = System.currentTimeMillis();
        if (diveCooldownTicks > 0) Pdc.putLong(p, DIVE_READY, nowMs + diveCooldownTicks * 50L);
        p.resetFallDistance();
        leaping.put(p.getUUID(), new Forgive(nowMs + 2000, Double.NaN));
        ServerLevel level = (ServerLevel) target.level();
        Vec3 at = target.position();
        LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.snapTo(at);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 1, at.z, 40, diveRadius * 0.4, 0.5, diveRadius * 0.4, 0.2);
        sound(level, at, "entity.breeze.wind_burst", 1.2f, 0.7f);
        if (diveRadius > 0) {
            // the splash lands next tick: this runs inside the target's hurt
            Tasks.later(1L, () -> {
                for (Entity e : level.getEntities(p, new AABB(at, at).inflate(diveRadius))) {
                    if (e == target || !foe(p, e, null) || e.position().distanceToSqr(at) > diveRadius * diveRadius) continue;
                    LivingEntity le = (LivingEntity) e;
                    if (diveSplash > 0) le.hurtServer(level, level.damageSources().playerAttack(p), (float) diveSplash);
                    push(le, at, 0.9, 0.5);
                }
            });
        }
        bar(p, msgDive, "");
        return (float) (amount + diveBonus);
    }
}
