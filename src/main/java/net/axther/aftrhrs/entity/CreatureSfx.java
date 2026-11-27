package net.axther.aftrhrs.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.axther.aftrhrs.AftrHrs;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * Creature sound design: every species has a sound profile (hand-written in {@code mod/tools/sfx_profiles.py},
 * generated into {@code assets/aftrhrs/sounds.json} and the species table's {@code sfx} block). Sound event ids are
 * {@code aftrhrs:<species>.<event>}; an event may have a {@code .layer} event played a few ticks later (a crackle under a
 * yip, an amethyst hum under tumbling pebbles) and a per-play pitch jitter.
 *
 * <p>Events: ambient (+ ambient_happy / _sleepy / _scared / _angry by mood), snore (sleeping pets never call), sleep,
 * wake, pet, happy, bond_up, bond_level, tame, tame_fail, eat, heal, hurt, death, step, sit, stand, arrive, revive,
 * ability (+ ability_&lt;skill&gt;), attack, and for mounts saddle, jump, land, flap, gallop, splash. Shared
 * {@code aftrhrs:shared.*} sounds (bond fanfare, tame, revive, whistle) are layered under the species' own.
 *
 * <p>Server side only: the sounds are broadcast like any entity sound (neutral / hostile source by kind).
 */
public final class CreatureSfx {
    private static final Map<String, SoundEvent> EVENTS = new LinkedHashMap<>();
    private static final Map<String, JsonObject> SHARED = new LinkedHashMap<>();
    /** The fanfare's arpeggio (a major chord up an octave) and the ticks between its notes. */
    private static final float[] FANFARE = {0.75f, 0.944f, 1.122f, 1.5f};
    private static final int FANFARE_STEP = 3;

    /**
     * Per-entity sound state (flap / gallop timers, last bond chime, wet flag), held by each creature
     * ({@link ArcanaMob#sfxState}). Server side, transient: never saved or synced.
     */
    public static final class State {
        long bondUpAt = -1000, flapAt, gallopAt;
        boolean wet;
    }

    private CreatureSfx() {
    }

    // ------------------------------------------------------------------ registration (mod init)

    /** Registers {@code aftrhrs:<species>.<event>} (+ .layer) for every profile and the shared events. */
    static void register(List<Species> all) {
        for (Species s : all) {
            JsonObject sfx = s.section("sfx");
            if (sfx == null) continue;
            float dist = (float) Species.dbl(sfx, "dist", 16);
            for (Map.Entry<String, JsonElement> e : sfx.getAsJsonObject("events").entrySet()) {
                JsonObject info = e.getValue().getAsJsonObject();
                float d = (float) Species.dbl(info, "dist", dist);
                String key = s.id + "." + e.getKey();
                reg(key, d);
                if (info.has("layer")) reg(key + ".layer", d);
            }
        }
        JsonObject shared = read("/data/aftrhrs/sfx/shared.json");
        if (shared != null) {
            for (Map.Entry<String, JsonElement> e : shared.getAsJsonObject("events").entrySet()) {
                JsonObject info = e.getValue().getAsJsonObject();
                SHARED.put(e.getKey(), info);
                reg("shared." + e.getKey(), 16);
                if (info.has("layer")) reg("shared." + e.getKey() + ".layer", 16);
            }
        }
        AftrHrs.LOG.info("Arcana creatures: {} sound events registered", EVENTS.size());
    }

    private static void reg(String key, float dist) {
        Identifier id = AftrHrs.id(key);
        SoundEvent ev = dist == 16 ? SoundEvent.createVariableRangeEvent(id) : SoundEvent.createFixedRangeEvent(id, dist);
        EVENTS.put(key, Registry.register(BuiltInRegistries.SOUND_EVENT, id, ev));
    }

    private static @Nullable JsonObject read(String path) {
        try (InputStream in = CreatureSfx.class.getResourceAsStream(path)) {
            return in == null ? null : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /** Every registered creature sound event id (debug / checks). */
    public static Map<String, SoundEvent> events() {
        return java.util.Collections.unmodifiableMap(EVENTS);
    }

    // ------------------------------------------------------------------ lookup

    private static @Nullable JsonObject info(Species s, String ev) {
        return s.sfxEvents.get(ev);
    }

    public static boolean has(ArcanaMob mob, String ev) {
        return info(mob.species(), ev) != null;
    }

    /** The species' sound event for {@code ev}, or null if its profile has none. */
    public static @Nullable SoundEvent event(ArcanaMob mob, @Nullable String ev) {
        if (ev == null || info(mob.species(), ev) == null) return null;
        return EVENTS.get(mob.species().id + "." + ev);
    }

    private static SoundSource source(ArcanaMob mob) {
        Species.Kind k = mob.species().kind;
        boolean wild = !(mob instanceof ArcanaCreature c) || !c.isTame();
        return wild && (k == Species.Kind.UNDEAD || k == Species.Kind.BOSS || mob.species().hostile()) ? SoundSource.HOSTILE : SoundSource.NEUTRAL;
    }

    private static float jitter(Mob m, JsonObject info) {
        double j = Species.dbl(info, "jitter", 0.06);
        return j <= 0 ? 1f : (float) (1 + (m.getRandom().nextDouble() * 2 - 1) * j);
    }

    // ------------------------------------------------------------------ playing

    /** Plays a profile event (and its layer). False if the species has no such event (callers fall back). */
    public static boolean play(ArcanaMob mob, @Nullable String ev) {
        return play(mob, ev, 1f, 1f);
    }

    public static boolean play(ArcanaMob mob, @Nullable String ev, float volume, float pitch) {
        Mob m = mob.self();
        if (ev == null || !(m.level() instanceof ServerLevel) || m.isSilent()) return false;
        JsonObject info = info(mob.species(), ev);
        SoundEvent main = info == null ? null : EVENTS.get(mob.species().id + "." + ev);
        if (main == null) return false;
        float p = pitch * jitter(m, info);
        SoundSource src = source(mob);
        emit(m, main, src, volume, p);
        if (info.has("layer")) {
            SoundEvent layer = EVENTS.get(mob.species().id + "." + ev + ".layer");
            int delay = info.get("layer").getAsInt();
            if (layer != null) {
                if (delay <= 0) emit(m, layer, src, volume, p);
                else mob.scheduler().after(delay, () -> {
                    if (m.isAlive() || "death".equals(ev)) emit(m, layer, src, volume, p);
                });
            }
        }
        return true;
    }

    /** Only the layer of an event (vanilla already played the main sound through getHurtSound / getDeathSound). */
    public static void layer(ArcanaMob mob, String ev) {
        Mob m = mob.self();
        JsonObject info = info(mob.species(), ev);
        if (info == null || !info.has("layer") || !(m.level() instanceof ServerLevel) || m.isSilent()) return;
        SoundEvent layer = EVENTS.get(mob.species().id + "." + ev + ".layer");
        if (layer == null) return;
        SoundSource src = source(mob);
        float p = jitter(m, info);
        int delay = info.get("layer").getAsInt();
        // the death layer must outlive the entity: play it from the level, not the mob's own scheduler
        if (delay <= 0 || "death".equals(ev)) emit(m, layer, src, 1f, p);
        else mob.scheduler().after(delay, () -> emit(m, layer, src, 1f, p));
    }

    /** A shared sound (bond fanfare, tame, revive, whistle) at an entity, with its layer. */
    public static void shared(Entity at, String ev, float pitch) {
        JsonObject info = SHARED.get(ev);
        SoundEvent main = EVENTS.get("shared." + ev);
        if (info == null || main == null || !(at.level() instanceof ServerLevel)) return;
        emit(at, main, SoundSource.NEUTRAL, 1f, pitch);
        SoundEvent layer = EVENTS.get("shared." + ev + ".layer");
        if (layer != null) {
            int delay = info.get("layer").getAsInt();
            if (delay <= 0 || !(at instanceof ArcanaMob mob)) emit(at, layer, SoundSource.NEUTRAL, 1f, pitch);
            else mob.scheduler().after(delay, () -> emit(at, layer, SoundSource.NEUTRAL, 1f, pitch));
        }
    }

    private static void emit(Entity at, SoundEvent e, SoundSource src, float volume, float pitch) {
        at.level().playSound(null, at.getX(), at.getY(), at.getZ(), e, src, volume, pitch);
    }

    // ------------------------------------------------------------------ ambient (vanilla Mob ambient timer)

    /** True while the creature sleeps (a staying pet's night nap, or any held sleep / lie pose). */
    public static boolean sleeping(ArcanaMob mob) {
        if (mob instanceof ArcanaCreature c && c.napping()) return true;
        String hold = mob.holdClip();
        return hold.contains("sleep") || hold.equals("lie_down") || hold.equals("dormant");
    }

    /** The ambient event that fits the creature's mood right now (null = keep quiet). */
    public static @Nullable String ambientEvent(ArcanaMob mob) {
        Mob m = mob.self();
        Species s = mob.species();
        if (s.quietCond() && mob.var("dormant") != 0) return null;   // Vinebound lying in wait
        if (sleeping(mob)) return has(mob, "snore") ? "snore" : null;          // sleepers breathe, they never call
        String ev = "ambient";
        if (m.getTarget() != null) ev = "ambient_angry";
        else if (mob instanceof ArcanaCreature c) {
            String mood = c.brain.mood;
            if (c.happy() || mood.equals("idle_happy")) ev = "ambient_happy";
            else if (mood.equals("idle_scared") || mood.equals("idle_worried") || mood.equals("idle_hurt") || mood.equals("idle_alert")) ev = "ambient_scared";
            else if (mood.equals("idle_sleepy") || c.staying() && Conditions.night(m.level())) ev = "ambient_sleepy";
        } else if (mob instanceof ArcanaWildlife w && (w.spooked() || m.getHealth() < m.getMaxHealth() * 0.35f)) {
            ev = "ambient_scared";
        }
        return has(mob, ev) ? ev : has(mob, "ambient") ? "ambient" : null;
    }

    /** Vanilla's ambient interval: the profile's, longer while staying, the snore rhythm while asleep. */
    public static int ambientInterval(ArcanaMob mob) {
        JsonObject sfx = mob.species().section("sfx");
        if (sfx == null) return 400;
        int every = (int) Species.dbl(sfx, "every", 260);
        if (sleeping(mob)) return (int) Species.dbl(sfx, "snore_every", Math.max(80, every / 2));
        if (mob instanceof ArcanaCreature c && c.staying()) return every * 3 / 2;
        return every;
    }

    /** Mob#playAmbientSound: the mood's ambient event (server side). */
    public static void ambient(ArcanaMob mob) {
        if (!(mob.self().level() instanceof ServerLevel)) return;
        play(mob, ambientEvent(mob));
    }

    /** Vanilla step sound: the profile's step (quiet, like vanilla mob steps). False = use the block's step sound. */
    public static boolean step(ArcanaMob mob) {
        if (!has(mob, "step")) return false;
        play(mob, "step", 0.2f, 1f);
        return true;
    }

    /** Hurt / death pitch: the profile jitter around 1 (young forms are already pitched up in their profile). */
    public static float voicePitch(ArcanaMob mob) {
        Mob m = mob.self();
        return 1f + (m.getRandom().nextFloat() - m.getRandom().nextFloat()) * 0.08f;
    }

    // ------------------------------------------------------------------ per-tick extras (flaps, gallops, splashes)

    /** Called every server tick by the base classes: wing flaps for flyers in the air, mount gallops, water splashes. */
    public static void tick(ArcanaMob mob) {
        Mob m = mob.self();
        if (!(m.level() instanceof ServerLevel level) || !m.isAlive()) return;
        Species s = mob.species();
        if (!s.sfxSplash && !s.sfxFlap && !(s.sfxGallop && mob instanceof ArcanaMount)) return;   // most species: nothing per tick
        State st = mob.sfxState();
        long now = level.getGameTime();
        boolean water = m.isInWater();
        if (water != st.wet) {
            st.wet = water;
            // a plunge, not a paddle along the shore
            if (water && s.sfxSplash && (m.fallDistance > 1 || m.getY() - m.yo < -0.15)) play(mob, "splash");
        }
        if (!m.onGround() && !water && !m.isPassenger() && s.sfxFlap) {
            double vy = m.getY() - m.yo;
            double h = Math.hypot(m.getX() - m.xo, m.getZ() - m.zo);
            if (vy > -0.04 && (h > 0.02 || vy > 0.02) && now >= st.flapAt) {
                boolean climbing = vy > 0.06;
                st.flapAt = now + (climbing ? 10 : 18) + m.getRandom().nextInt(8);
                if (climbing || m.getRandom().nextFloat() < 0.6f) play(mob, "flap", climbing ? 0.9f : 0.6f, 1f);
            }
        }
        if (s.sfxGallop && m.onGround() && !water && mob instanceof ArcanaMount mount && mount.getControllingPassenger() != null) {
            double h = Math.hypot(m.getX() - m.xo, m.getZ() - m.zo);
            if (h > 0.8 * s.speed() && now >= st.gallopAt) {
                st.gallopAt = now + 7;
                play(mob, "gallop", 0.7f, 1f);
            }
        }
    }

    // ------------------------------------------------------------------ interactions

    /** The owner pets it: the species' own petting sound (never a generic purr) and its particle. */
    public static void pet(ArcanaCreature c) {
        play(c, "pet");
        particles(c, 4);
    }

    /** Bond XP gained: a small chime and the species' little pleased sound (at most every 3 s per pet). */
    public static void bondUp(ArcanaCreature c) {
        if (!(c.level() instanceof ServerLevel level)) return;
        State st = c.sfxState();
        long now = level.getGameTime();
        if (now - st.bondUpAt < 60) return;
        st.bondUpAt = now;
        shared(c, "bond_up", 1f);
        play(c, "bond_up", 0.8f, 1f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.getX(), c.getY() + c.getBbHeight() * 0.8, c.getZ(), 3, 0.3, 0.2, 0.3, 0);
    }

    /**
     * Bond level-up: a bell arpeggio rising over the pet (one note per bond level reached, up to the chord), the
     * species' celebration, then the finale and a burst of its particle.
     */
    public static void bondLevel(ArcanaCreature c, int level) {
        if (!(c.level() instanceof ServerLevel sl)) return;
        c.sfxState().bondUpAt = sl.getGameTime();
        int notes = Math.max(3, Math.min(FANFARE.length, level + 1));
        for (int i = 0; i < notes; i++) {
            float pitch = FANFARE[i];
            int step = i;
            c.scheduler().after(i * FANFARE_STEP, () -> {
                shared(c, "fanfare_note", pitch);
                sl.sendParticles(ParticleTypes.NOTE, c.getX(), c.getY() + c.getBbHeight() + 0.3, c.getZ(), 1, 0.3, 0.1, 0.3, step / 24.0);
            });
        }
        int end = notes * FANFARE_STEP;
        c.scheduler().after(2, () -> play(c, "bond_level"));
        c.scheduler().after(end, () -> {
            shared(c, "fanfare_end", 1f);
            sl.sendParticles(ParticleTypes.END_ROD, c.getX(), c.getY() + c.getBbHeight() * 0.6, c.getZ(), 16, 0.4, 0.5, 0.4, 0.05);
            particles(c, 10);
        });
        // the species' own celebration clip ("bond_level" or "level_up"; both names are in use) when it has one; a staying
        // pet keeps its sit and does its sitting petting loop instead
        Species s = c.species();
        String cele = s.has("bond_level") ? "bond_level" : s.has("level_up") ? "level_up" : null;
        if (cele != null && !c.staying()) c.play(cele);
        else if (c.staying() && c.isInSittingPose() && s.has("sit_pet")) c.restPose("sit_pet", Math.max(40, s.clipTicks("sit_pet", 40)));
        else if (c.staying() && c.isInSittingPose() && s.has("pet_sit")) c.play("pet_sit");
        else c.playRole("happy");
    }

    /** Tamed: the species' own joy over the shared tame chime. False if it has no tame sound. */
    public static boolean tame(ArcanaCreature c) {
        shared(c, "tame", 1f);
        return play(c, "tame");
    }

    /** Arrived at the owner's call (whistle, /pets summon, leash teleport). */
    public static void arrive(ArcanaCreature c, boolean whistle) {
        if (whistle) shared(c, "whistle", 1f);
        play(c, "arrive");
    }

    /** Back from a plushie / Second Wind revive. */
    public static void revive(Entity e) {
        shared(e, "revive", 1f);
        if (e instanceof ArcanaMob mob) mob.scheduler().after(10, () -> play(mob, "revive"));
    }

    /**
     * An ability fired ({@code state{s=ability}}): {@code ability_<skill>} (the part of the skill id after the last
     * '_', lower case) if the profile has one, else {@code ability}. Bosses voice each attack at most every 5 s.
     */
    public static void ability(ArcanaMob mob, @Nullable String skill) {
        String key = skill == null ? "" : skill.substring(skill.lastIndexOf('_') + 1).toLowerCase(java.util.Locale.ROOT);
        String ev = !key.isEmpty() && has(mob, "ability_" + key) ? "ability_" + key : "ability";
        if (!has(mob, ev)) return;
        boolean boss = mob.species().kind == Species.Kind.BOSS;
        if (!mob.scheduler().ready("sfx_ability", boss ? 100 : 30)) return;
        play(mob, ev);
    }

    /** The species' petting particle (profile "particle", hearts otherwise). */
    static void particles(ArcanaMob mob, int n) {
        Mob m = mob.self();
        if (!(m.level() instanceof ServerLevel level)) return;
        ParticleOptions p = ParticleTypes.HEART;
        JsonObject sfx = mob.species().section("sfx");
        if (sfx != null && sfx.has("particle")) {
            Identifier id = Identifier.tryParse(sfx.get("particle").getAsString());
            var type = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getValue(id);
            if (type instanceof SimpleParticleType simple) p = simple;
        }
        level.sendParticles(p, m.getX(), m.getY() + m.getBbHeight() * 0.75, m.getZ(), n, m.getBbWidth() * 0.4, 0.2, m.getBbWidth() * 0.4, 0.02);
    }
}
