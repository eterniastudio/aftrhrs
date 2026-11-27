package net.axther.aftrhrs.feature.holograms;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Brightness;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Damage and heal numbers: a small number pops above a mob or player that is hurt or healed, rises and fades out in
 * about a second. Only players near it who have Damage Numbers on (Settings) see it; never for invisible or vanished
 * targets. Capped per viewer and in total, so a big fight doesn't flood anyone.
 * <p>
 * The numbers are packet-only text displays: never added to the level (no tracker pairing, no chunk saving, no
 * leftovers), sent straight to their viewers (add + data in one bundle), animated with data packets of only what
 * changed, and removed with a remove packet when they expire. A viewer who walks out of range keeps the number until
 * it expires (about a second).
 */
final class DamageNumbers {
    private static final String OFF_KEY = "feather:damage_numbers_off";
    private static final String PET_KEY = "feather:pet";

    private final HologramsModule m;

    boolean enabled = true;
    private boolean players = true, mobs = true, pets = true, bosses = true;
    private double bossHealth = 150;
    private double minDamage = 0.5, minHeal = 1;
    private final Set<String> healIgnore = new HashSet<>();
    private double range = 16;
    private boolean showOwn;
    private int maxPerPlayer = 8, maxActive = 64;
    private int lifetime = 20;
    private double rise = 0.9;

    private static final class Pop {
        final Display.TextDisplay d;
        final ServerLevel level;
        /** Who was sent the number (resolved through the player list on each send: respawns, quits). */
        final List<UUID> viewers;
        final float size;
        int age;

        Pop(Display.TextDisplay d, ServerLevel level, List<UUID> viewers, float size) {
            this.d = d;
            this.level = level;
            this.viewers = viewers;
            this.size = size;
        }
    }

    private final List<Pop> active = new ArrayList<>();
    private final Map<UUID, Integer> shownTo = new HashMap<>();
    /** The target of the crit being dealt right now (CRITICAL_HIT comes just before its damage). */
    private Entity critTarget;
    private long critTick = -1;
    /** Health (with absorption) right before a hit, for the killing blow (Fabric's AFTER_DAMAGE skips it). */
    private final Map<UUID, Float> before = new HashMap<>();

    DamageNumbers(HologramsModule m) {
        this.m = m;
    }

    /** The damage, heal and crit listeners (once per JVM; they do nothing while the module is off). */
    static void hook(java.util.function.Supplier<HologramsModule> active) {
        FeatherEvents.CRITICAL_HIT.register((attacker, target) -> {
            HologramsModule m = active.get();
            if (m != null) {
                m.damage.critTarget = target;
                m.damage.critTick = m.tick();
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, amount) -> {
            HologramsModule m = active.get();
            // the snapshot only matters when someone could see the killing blow's number
            if (m != null && m.damage.enabled && !e.level().players().isEmpty()) m.damage.before.put(e.getUUID(), e.getHealth() + e.getAbsorptionAmount());
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((e, source, base, taken, blocked) -> {
            HologramsModule m = active.get();
            if (m != null) m.damage.onDamage(e, source, taken);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            HologramsModule m = active.get();
            if (m == null) return;
            Float hp = m.damage.before.remove(e.getUUID());
            if (hp != null) m.damage.onDamage(e, source, hp);
        });
        FeatherEvents.HEAL.register((e, amount, reason) -> {
            HologramsModule m = active.get();
            if (m != null) m.damage.onHeal(e, amount, reason);
        });
    }

    void reload(Yaml c) {
        enabled = c == null || c.getBoolean("enabled", true);
        players = c == null || c.getBoolean("kinds.players", true);
        mobs = c == null || c.getBoolean("kinds.mobs", true);
        pets = c == null || c.getBoolean("kinds.pets", true);
        bosses = c == null || c.getBoolean("kinds.bosses", true);
        bossHealth = c == null ? 150 : Math.max(1, c.getDouble("boss-health", 150));
        minDamage = c == null ? 0.5 : Math.max(0, c.getDouble("min-damage", 0.5));
        minHeal = c == null ? 1 : Math.max(0, c.getDouble("min-heal", 1));
        healIgnore.clear();
        List<String> ignore = c == null ? List.of("SATIATED", "REGEN") : c.getStringList("heal-ignore");
        for (String s : ignore) healIgnore.add(s.toUpperCase(Locale.ROOT));
        range = c == null ? 16 : Math.max(2, Math.min(64, c.getDouble("range", 16)));
        showOwn = c != null && c.getBoolean("show-own", false);
        maxPerPlayer = c == null ? 8 : Math.max(1, c.getInt("max-per-player", 8));
        maxActive = c == null ? 64 : Math.max(1, c.getInt("max-active", 64));
        lifetime = c == null ? 20 : Math.max(8, Math.min(100, c.getInt("lifetime-ticks", 20)));
        rise = c == null ? 0.9 : Math.max(0, Math.min(4, c.getDouble("rise", 0.9)));
        if (!enabled) clear();
    }

    // ------------------------------------------------------------------ the setting

    boolean shows(ServerPlayer p) {
        return !Pdc.has(p, OFF_KEY);
    }

    void toggle(ServerPlayer p) {
        if (shows(p)) Pdc.putTag(p, OFF_KEY, net.minecraft.nbt.ByteTag.valueOf((byte) 1));
        else Pdc.remove(p, OFF_KEY);
        m.text().send(p, shows(p) ? "holograms.damage.toggled-on" : "holograms.damage.toggled-off");
    }

    // ------------------------------------------------------------------ events

    private void onDamage(LivingEntity le, DamageSource source, float amount) {
        before.remove(le.getUUID());
        boolean crit = critTarget == le && critTick == m.tick();
        if (crit) critTarget = null;   // consumed even when nobody sees the number
        if (!enabled || amount <= 0 || amount < minDamage) return;
        if (!counts(le)) return;
        List<ServerPlayer> v = viewers(le);
        if (v == null) return;
        String key;
        if (source.is(DamageTypeTags.IS_FIRE)) key = "fire";
        else if (source.is(DamageTypes.MAGIC) && source.getEntity() == null && le.hasEffect(MobEffects.POISON)) key = "poison";
        else if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC) || source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.DRAGON_BREATH) || source.is(DamageTypes.SONIC_BOOM)) key = "magic";
        else key = crit ? "crit" : "normal";
        pop(le, v, "holograms.damage." + key, amount, crit);
    }

    private void onHeal(LivingEntity le, float amount, String reason) {
        if (!enabled || healIgnore.contains(reason)) return;
        double heal = Math.min(amount, le.getMaxHealth() - le.getHealth());
        if (heal <= 0 || heal < minHeal) return;
        if (!counts(le)) return;
        List<ServerPlayer> v = viewers(le);
        if (v == null) return;
        pop(le, v, "holograms.damage.heal", heal, false);
    }

    void forget(UUID id) {
        shownTo.remove(id);
        before.remove(id);
        for (Pop p : active) p.viewers.remove(id);
    }

    /** Whether numbers show for this kind of target (config kinds). */
    private boolean counts(LivingEntity le) {
        if (le instanceof ArmorStand || m.owns(le) || HologramsModule.tagged(le)) return false;
        if (le instanceof ServerPlayer) return players;
        if (Pdc.has(le, PET_KEY) || le instanceof TamableAnimal t && t.isTame()) return pets;
        // Ex-MythicMobs creatures with boss-sized health count as bosses.
        if (ArcanaEntities.all().containsKey(le.getType()) && le.getMaxHealth() >= bossHealth) return bosses;
        return mobs;
    }

    static String amount(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    /**
     * Who would see a number over this target right now, or null when nobody would (checked before any text or
     * allocation work): the caps, invisibility, range, the per-viewer cap, the setting, auth and vanish.
     */
    private List<ServerPlayer> viewers(LivingEntity target) {
        if (active.size() >= maxActive) return null;
        if (target.isInvisible() || target.hasEffect(MobEffects.INVISIBILITY)) return null;
        if (!(target.level() instanceof ServerLevel level)) return null;
        double r2 = range * range;
        List<ServerPlayer> out = null;
        for (ServerPlayer p : level.players()) {
            if (p == target && !showOwn) continue;
            if (p.distanceToSqr(target) > r2) continue;
            if (shownTo.getOrDefault(p.getUUID(), 0) >= maxPerPlayer) continue;
            if (m.authBlocked(p) || !shows(p)) continue;
            if (target instanceof ServerPlayer tp && !m.plugin.canSee(p, tp)) continue;
            if (out == null) out = new ArrayList<>(4);
            out.add(p);
        }
        return out;
    }

    private void pop(LivingEntity target, List<ServerPlayer> viewers, String key, double amount, boolean crit) {
        ServerLevel level = (ServerLevel) target.level();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double x = target.getX() + rnd.nextDouble(-0.35, 0.35);
        double y = target.getY() + target.getBbHeight() + 0.1 + rnd.nextDouble(0, 0.25);
        double z = target.getZ() + rnd.nextDouble(-0.35, 0.35);
        Component text = m.text().msg(key, Text.unparsed("amount", amount(amount)));
        float size = crit ? 1.25f : 1f;
        // never added to the level: it only carries the synched data the packets are built from
        Display.TextDisplay d = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
        d.snapTo(x, y, z, 0, 0);
        d.setText(Adv.vanilla(text));
        d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        d.setBackgroundColor(0);
        d.setFlags(Display.TextDisplay.FLAG_SHADOW);
        d.setBrightnessOverride(new Brightness(15, 15));
        d.setViewRange((float) (range / 64.0 + 0.1));
        d.setTransformation(transform(0, size * 0.55f));
        List<Packet<? super ClientGamePacketListener>> spawn = new ArrayList<>(2);
        spawn.add(new ClientboundAddEntityPacket(d.getId(), d.getUUID(), x, y, z, 0f, 0f, EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
        List<SynchedEntityData.DataValue<?>> data = d.getEntityData().getNonDefaultValues();
        if (data != null) spawn.add(new ClientboundSetEntityDataPacket(d.getId(), data));
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(spawn);
        d.getEntityData().packDirty();   // the spawn carried everything: only later changes are sent
        List<UUID> ids = new ArrayList<>(viewers.size());
        for (ServerPlayer p : viewers) {
            p.connection.send(bundle);
            ids.add(p.getUUID());
            shownTo.merge(p.getUUID(), 1, Integer::sum);
        }
        active.add(new Pop(d, level, ids, size));
    }

    private static Transformation transform(float lift, float scale) {
        return new Transformation(new Vector3f(0, lift, 0), null, new Vector3f(scale, scale, scale), null);
    }

    /** Pops up, rises, fades and goes. */
    void tick() {
        // Hits another listener cancelled never reach AFTER_DAMAGE: don't let their entries pile up.
        if (before.size() > 512) before.clear();
        if (active.isEmpty()) return;
        for (Iterator<Pop> it = active.iterator(); it.hasNext(); ) {
            Pop p = it.next();
            p.age++;
            if (p.age >= lifetime) {
                remove(p);
                it.remove();
                continue;
            }
            if (p.age == 1) {
                p.d.setTransformationInterpolationDelay(0);
                p.d.setTransformationInterpolationDuration(3);
                p.d.setTransformation(transform(0.05f, p.size * 1.15f));
            } else if (p.age == 4) {
                p.d.setTransformationInterpolationDelay(0);
                p.d.setTransformationInterpolationDuration(lifetime - 4);
                p.d.setTransformation(transform((float) rise, p.size * 0.75f));
            } else if (p.age == lifetime * 6 / 10) {
                p.d.setTextOpacity((byte) 170);
            } else if (p.age == lifetime * 8 / 10) {
                p.d.setTextOpacity((byte) 90);
            } else {
                continue;
            }
            List<SynchedEntityData.DataValue<?>> dirty = p.d.getEntityData().packDirty();
            if (dirty == null) continue;
            ClientboundSetEntityDataPacket pkt = new ClientboundSetEntityDataPacket(p.d.getId(), dirty);
            PlayerList players = p.level.getServer().getPlayerList();
            for (UUID id : p.viewers) {
                ServerPlayer v = players.getPlayer(id);
                if (v != null && !v.hasDisconnected() && v.level() == p.level) v.connection.send(pkt);
            }
        }
    }

    /** Takes the number off its viewers' screens. */
    private void remove(Pop p) {
        ClientboundRemoveEntitiesPacket pkt = new ClientboundRemoveEntitiesPacket(p.d.getId());
        PlayerList players = p.level.getServer().getPlayerList();
        for (UUID id : p.viewers) {
            ServerPlayer v = players.getPlayer(id);
            if (v != null && !v.hasDisconnected()) v.connection.send(pkt);
            shownTo.computeIfPresent(id, (k, n) -> n <= 1 ? null : n - 1);
        }
    }

    void clear() {
        for (Pop p : active) remove(p);   // a reload or disable takes the numbers off clients too
        active.clear();
        shownTo.clear();
        before.clear();
    }

    /** Active numbers (for /holo info and tests). */
    int activeCount() {
        return active.size();
    }
}
