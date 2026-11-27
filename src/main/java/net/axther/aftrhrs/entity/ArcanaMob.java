package net.axther.aftrhrs.entity;

import com.geckolib.animatable.GeoEntity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * Everything every Arcana creature shares, whatever its base class: its {@link Species}, the synced animation state
 * (the locomotion controller's idle / walk clip and an optional held pose) and one-shot clips fired from the server.
 *
 * <p>Animation model (GeckoLib, see {@link CreatureAnim} and PORTING.md "Animation"): the server sets the intent
 * (idle / walk clip, an optional held pose, one-shots via {@link #play}); the client state machine
 * ({@link CreatureAnim.Driver}) plays it against the creature's measured movement, so a still-only clip (sit, lie,
 * belly up, a flourish) never shows while it moves.
 */
public interface ArcanaMob extends GeoEntity {
    Species species();

    String idleClip();

    String walkClip();

    /** A held pose overriding locomotion, or "" for none. */
    String holdClip();

    void setIdleClip(String clip);

    void setWalkClip(String clip);

    void setHoldClip(String clip);

    /** Color tint multiplied over the texture (0xRRGGBB, 0xFFFFFF = normal look); a pets-module color variant. */
    int tint();

    boolean shiny();

    /** Per-creature timeline (delayed skill steps, cooldowns). */
    Scheduler scheduler();

    /** The animation driver {@link CreatureAnim#register} made for this creature, or null before it registered. */
    CreatureAnim.@Nullable Driver animDriver();

    void setAnimDriver(CreatureAnim.Driver driver);

    /** {@link CreatureSfx}'s per-creature timers (flap, gallop, bond chime, wet flag): transient, server side. */
    CreatureSfx.State sfxState();

    /** Saved integer variables (MythicMobs caster variables). */
    int var(String key);

    void setVar(String key, int value);

    /** Busy with a long antic (MythicMobs setai false): no AI, stands still. */
    boolean busy();

    void setBusy(boolean busy);

    /** Ends a long antic: AI back on and the base clips back. */
    void free();

    /** Comma-separated bones hidden on the model (ModelEngine partvis: a shorn fleece, shed antlers, a held fish). */
    default String hiddenBones() {
        return "";
    }

    /** Hides or shows a bone and its children (no-op on bases without bone visibility). */
    default void setBoneVisible(String bone, boolean visible) {
    }

    default Mob self() {
        return (Mob) this;
    }

    /**
     * Plays a one-shot clip if the model has it (server side; GeckoLib syncs it to watching clients). The clip's tag
     * picks the layer: whole-body clips play on the base layer (and stop with a cross-fade if the creature moves and the
     * clip cannot), overlay clips (attacks, hurt, head-only) layer over the body, additive accents on top.
     */
    default void play(@Nullable String clip) {
        if (clip == null || !species().has(clip)) return;
        triggerAnim(CreatureAnim.controllerFor(species(), clip), clip);
        onPlayed(clip);
    }

    /**
     * The clip this creature really plays for {@code clip} right now (null = none fits): identity, except where a
     * creature has a second body state with its own clip set (the Pockie's fishbowl: {@code bowl_<clip>}). The client
     * state machine asks it for the death clip and the sit fallback.
     */
    default @Nullable String clipFor(@Nullable String clip) {
        return clip;
    }

    /** Hook after {@link #play}: e.g. a pet lingers (no idle wandering) while a still-only flourish plays. */
    default void onPlayed(String clip) {
    }

    /** Stops a one-shot early (ModelEngine's {@code state{r=true}}). */
    default void stopPlay(@Nullable String clip) {
        if (clip != null && species().has(clip)) stopTriggeredAnim(CreatureAnim.controllerFor(species(), clip), clip);
    }

    /** Plays an additive accent clip (starts and ends at rest) on top of everything else. */
    default void accent(@Nullable String clip) {
        if (clip != null && species().has(clip)) triggerAnim(CreatureAnim.ACCENT, clip);
    }

    /** Plays the clip for a role (eat, happy, ability ...) if the species maps one. */
    default void playRole(String role) {
        play(species().anim(role));
    }

    /** Holds a pose if the model has it ("" or null clears it). */
    default void hold(@Nullable String clip) {
        setHoldClip(clip != null && species().has(clip) ? clip : "");
    }

    // ------------------------------------------------------------------ small effects helpers

    default void sound(Species.@Nullable Sound s) {
        if (s == null) return;
        SoundEvent e = s.event();
        if (e != null && self().level() instanceof ServerLevel) {
            self().level().playSound(null, self().getX(), self().getY(), self().getZ(), e, SoundSource.NEUTRAL, s.volume(), s.pitch());
        }
    }

    default void sound(String id, float volume, float pitch) {
        sound(new Species.Sound(id, volume, pitch));
    }

    default void particles(ParticleOptions p, int amount, double hs, double vs, double y, double speed) {
        if (self().level() instanceof ServerLevel level) {
            level.sendParticles(p, self().getX(), self().getY() + y, self().getZ(), amount, hs, vs, hs, speed);
        }
    }
}
