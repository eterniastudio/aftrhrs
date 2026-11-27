package net.axther.aftrhrs.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Rideable mounts for two (driver + passenger seat). Controls as the README describes:
 * <ul>
 *   <li>walker (Dunebun, Snowmuff): WASD, Space jumps (Dunebun's huge hops come from its jump strength), Shift gets off.</li>
 *   <li>flyer (Cinderwing Phoenix, Tempest Roc): WASD steers, Space climbs, Shift descends; Shift on the ground gets off.</li>
 *   <li>swimmer (Tidemane): walks on land; in water W swims where the rider looks, Space rises, Shift dives, nothing
 *       held treads water; Shift + S gets off in water.</li>
 * </ul>
 * The motion controller (gen_mm.py "Motion controller") reads the real movement every tick and switches poses with
 * proper air states (air_rise / air_fall, hover / fly / climb / dive / glide, tread / swim / rise / dive) and accents
 * (leap, land, land_hard, ride_takeoff, ride_land), now that the jump/fly flags are ours (ModelEngine's were broken).
 */
public class ArcanaMount extends ArcanaCreature {
    private String mo = "none";
    private boolean inAir, wasRidden, wetMo, groundedU = true;
    private int fallTicks, levelTicks;
    private String gait = "ride_walk";

    public ArcanaMount(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
    }

    public String motionKind() {
        JsonObject m = species().motion();
        if (m != null && m.has("kind")) return m.get("kind").getAsString();
        return species().flyer() ? "flyer" : "walker";
    }

    public boolean inAir() {
        return inAir;
    }

    int seatCount() {
        return Math.max(1, species().seats.size());
    }

    // ------------------------------------------------------------------ riding

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player p && isOwnedBy(p) ? p : null;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < seatCount();
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        List<float[]> seats = species().seats;
        int i = Math.max(0, getPassengers().indexOf(passenger));
        if (seats.isEmpty()) return super.getPassengerAttachmentPoint(passenger, dimensions, scale);
        float[] s = seats.get(Math.min(i, seats.size() - 1));
        return new Vec3(s[0] * scale, s[1] * scale, s[2] * scale).yRot(-getYRot() * Mth.DEG_TO_RAD);
    }

    @Override
    protected InteractionResult ownerUse(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        if (isVehicle() || player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!scheduler().ready("ride", 20)) return InteractionResult.SUCCESS;
        if (staying()) {
            setOrderedToSit(false);
            setInSittingPose(false);
            hold("");
        }
        setDeltaMovement(Vec3.ZERO);
        setTarget(null);
        getNavigation().stop();
        player.startRiding(this);
        JsonObject m = species().mountData();
        String hint = m != null && m.has("ride_hint") ? m.get("ride_hint").getAsString()
                : "flyer".equals(motionKind()) ? "WASD to steer, Space to climb, Shift to descend. Shift on the ground to dismount."
                : "WASD to ride, Space to jump, Shift to dismount.";
        if (seatCount() > 1 && (m == null || !m.has("custom_ride"))) hint += " A friend can right-click it to hop on behind you.";
        CreatureUtil.say(player, true, "ride-hint", "&7<hint>", net.axther.aftrhrs.core.Text.unparsed("hint", hint));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult otherInteract(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        if (!isTame() || !isVehicle() || player.isShiftKeyDown() || getPassengers().contains(player)) return InteractionResult.PASS;
        if (getPassengers().size() >= seatCount()) return InteractionResult.PASS;
        if (!scheduler().ready("passenger", 20)) return InteractionResult.SUCCESS;
        if (player.startRiding(this)) {
            JsonObject m = species().mountData();
            boolean custom = m != null && m.has("custom_ride");
            if (custom) CreatureUtil.say(player, true, "passenger-custom", "&7You climb on behind the rider. Shift to get off.");
            else CreatureUtil.say(player, true, "passenger", "&7Hold on tight! Shift to hop off.");
        }
        return InteractionResult.SUCCESS;
    }

    /** Vetoes vanilla's Shift-to-dismount while a flyer is airborne or a swimmer is in water (Shift steers there). */
    public boolean allowShiftDismount(Player player) {
        if (getControllingPassenger() != player) return true;
        return switch (motionKind()) {
            case "flyer" -> onGround();
            case "swimmer" -> !Conditions.inWater(this) || player instanceof ServerPlayer sp && sp.getLastClientInput().backward();
            default -> true;
        };
    }

    @Override
    protected void tickRidden(Player controller, Vec3 riddenInput) {
        super.tickRidden(controller, riddenInput);
        setRot(controller.getYRot(), controller.getXRot() * 0.5F);
        yRotO = yBodyRot = yHeadRot = getYRot();
        if ("walker".equals(motionKind()) || "swimmer".equals(motionKind()) && !Conditions.inWater(this)) {
            if (onGround() && controller.isJumping()) {
                double jump = getAttributeValue(Attributes.JUMP_STRENGTH);
                Vec3 v = getDeltaMovement();
                setDeltaMovement(v.x, jump, v.z);
                if (riddenInput.z > 0) {
                    float sin = Mth.sin(getYRot() * Mth.DEG_TO_RAD), cos = Mth.cos(getYRot() * Mth.DEG_TO_RAD);
                    setDeltaMovement(getDeltaMovement().add(-0.4F * sin * jump, 0, 0.4F * cos * jump));
                }
                needsSync = true;
            }
        }
    }

    @Override
    protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
        float side = controller.xxa * 0.5F, forward = controller.zza;
        if (forward <= 0) forward *= 0.25F;
        return new Vec3(side, 0, forward);
    }

    @Override
    protected float getRiddenSpeed(Player controller) {
        return (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    public void travel(Vec3 input) {
        if (getControllingPassenger() instanceof Player rider && isAlive()) {
            String kind = motionKind();
            if ("flyer".equals(kind) && !(onGround() && !rider.isJumping())) {
                flyRidden(rider);
                return;
            }
            if ("swimmer".equals(kind) && Conditions.inWater(this)) {
                swimRidden(rider);
                return;
            }
        }
        setNoGravity(false);
        super.travel(input);
    }

    /** Flying: horizontal from WASD relative to the rider's yaw, Space climbs, Shift descends, else it holds height. */
    private void flyRidden(Player rider) {
        setNoGravity(true);
        double speed = getAttributeValue(Attributes.FLYING_SPEED) * 2.2;
        float yaw = rider.getYRot() * Mth.DEG_TO_RAD;
        double fwd = rider.zza, side = rider.xxa;
        if (fwd < 0) fwd *= 0.4;
        double x = (-Mth.sin(yaw) * fwd + Mth.cos(yaw) * side) * speed;
        double z = (Mth.cos(yaw) * fwd + Mth.sin(yaw) * side) * speed;
        double y = rider.isJumping() ? 0.32 : rider.isShiftKeyDown() ? -0.38 : 0;
        Vec3 want = new Vec3(x, y, z);
        Vec3 v = getDeltaMovement().lerp(want, 0.25);
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        calculateEntityAnimation(true);
    }

    /** Swimming (ex ModelEngine meta controller): W swims where the rider looks, Space rises, Shift dives. */
    private void swimRidden(Player rider) {
        setNoGravity(true);
        JsonObject m = species().motion();
        double speed = (m != null && m.has("swim_speed") ? m.get("swim_speed").getAsDouble() : 0.45)
                + (m != null && m.has("swim_bond") ? m.get("swim_bond").getAsDouble() : 0.01) * bond();
        Vec3 v = getDeltaMovement();
        if (rider.zza > 0.1f) {
            v = Vec3.directionFromRotation(rider.getXRot(), rider.getYRot()).scale(speed);
        } else {
            v = new Vec3(v.x * 0.8, v.y * 0.6 + 0.006, v.z * 0.8);
        }
        if (rider.isJumping()) v = v.add(0, 0.14, 0);
        if (rider.isShiftKeyDown()) v = v.add(0, -0.14, 0);
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        calculateEntityAnimation(true);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!isVehicle()) setNoGravity(false);
    }

    @Override
    public boolean dismountsUnderwater() {
        return false;
    }

    // ------------------------------------------------------------------ motion controller (server, every tick)

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel) || !isAlive()) return;
        boolean ridden = getControllingPassenger() != null;
        if (ridden != wasRidden) {
            wasRidden = ridden;
            mo = "none";
            inAir = false;
            hold("");
            if (ridden) {
                gait = "ride_walk";
                play("mount");
                motionSounds("mount");
                CreatureSfx.play(this, "saddle");
            } else {
                play("dismount");
                setIdleClip(species().baseIdle());
                setWalkClip(species().baseWalk());
                setNoGravity(false);
            }
        }
        switch (motionKind()) {
            case "walker" -> walkerTick(ridden);
            case "swimmer" -> {
                if (ridden) swimmerTick();
            }
            default -> {
                if (ridden) flyerTick();
                else unriddenAir();
            }
        }
        if (ridden && (tickCount + getId()) % 80 == 0) fidget();
        if (ridden) Abilities.onRide(this);
    }

    /** A flying mount nobody rides plays its takeoff / land one-shots when it leaves or touches the ground
     *  (ArcanaCreature.airStates skips mounts; ridden, the motion controller's accents do it). */
    private void unriddenAir() {
        boolean grounded = onGround() || isInWater() || isPassenger();
        if (groundedU && !grounded && species().has("takeoff")) play("takeoff");
        else if (!groundedU && grounded && species().has("land")) play("land");
        groundedU = grounded;
    }

    private Vec3 motion() {
        return new Vec3(getX() - xo, getY() - yo, getZ() - zo);
    }

    private void walkerTick(boolean ridden) {
        Species s = species();
        if (!s.has("air_rise") || !s.has("air_fall")) return;
        Vec3 v = motion();
        String next = mo.equals("none") ? "ground" : mo;
        if (onGround()) next = "ground";
        else if (v.y > 0.1) next = "rise";
        else if (v.y < -0.34) next = "fall";
        if (Conditions.inWater(this)) next = "water";
        if (mo.equals("fall")) fallTicks++;
        if (!next.equals(mo)) {
            if (next.equals("rise") && !inAir) {
                accent("leap");
                motionSounds("leap");
                CreatureSfx.play(this, "jump");
            }
            if (inAir && !next.equals("rise") && !next.equals("fall")) {
                if (fallTicks > 8 && s.has("land_hard")) {
                    accent("land_hard");
                    particles(ParticleTypes.CLOUD, 10, 0.8, 0.05, 0.1, 0.03);
                    motionSounds("land_hard");
                    CreatureSfx.play(this, "land", 1f, 0.9f);
                } else {
                    accent("land");
                    motionSounds("land");
                    CreatureSfx.play(this, "land", 0.7f, 1f);
                }
                fallTicks = 0;
            }
            mo = next;
            switch (mo) {
                case "rise" -> {
                    inAir = true;
                    hold("air_rise");
                }
                case "fall" -> {
                    inAir = true;
                    hold("air_fall");
                }
                case "water" -> {
                    inAir = false;
                    fallTicks = 0;
                    hold("");
                    setIdleClip(s.has("idle_swim") ? "idle_swim" : s.baseIdle());
                    setWalkClip(s.has("swim") ? "swim" : s.baseWalk());
                }
                default -> {
                    inAir = false;
                    fallTicks = 0;
                    hold("");
                    if (ridden) rideGround();
                    else {
                        setIdleClip(s.baseIdle());
                        setWalkClip(s.baseWalk());
                        brain.think();
                    }
                }
            }
        }
        if (ridden && mo.equals("ground")) gaitTick(v);
    }

    private void rideGround() {
        Species s = species();
        setIdleClip(s.has("ride_idle") ? "ride_idle" : s.baseIdle());
        setWalkClip(s.has(gait) ? gait : s.has("ride_walk") ? "ride_walk" : s.baseWalk());
    }

    /** Ridden gait from real speed: ride_run above 0.9x its speed, back to ride_walk below 0.6x (hysteresis). */
    private void gaitTick(Vec3 v) {
        Species s = species();
        if (!s.has("ride_run") || !s.has("ride_walk")) {
            rideGround();
            return;
        }
        double speed = s.speed(), h = v.horizontalDistance();
        String next = gait;
        if (h > 0.9 * speed) next = "ride_run";
        else if (h < 0.6 * speed) next = "ride_walk";
        if (!next.equals(gait)) gait = next;
        rideGround();
    }

    private void flyerTick() {
        Species s = species();
        Vec3 v = motion();
        String next = "air";
        if (v.y > 0.12) next = "climb";
        else if (v.y < -0.12) next = "dive";
        if (onGround()) next = "ground";
        if (next.equals("air") && v.horizontalDistance() > 0.15) levelTicks++;
        else levelTicks = 0;
        if (levelTicks > 40) next = "glide";
        if (next.equals(mo)) return;
        if (mo.equals("ground") && !next.equals("ground")) {
            accent("ride_takeoff");
            motionSounds("takeoff");
            CreatureSfx.play(this, "jump");
        }
        if (next.equals("ground") && !mo.equals("ground") && !mo.equals("none")) {
            accent("ride_land");
            motionSounds("touchdown");
            CreatureSfx.play(this, "land");
            Abilities.onTouchdown(this, mo);
        }
        mo = next;
        String groundIdle = s.has("ride_idle") ? "ride_idle" : "idle";
        String groundWalk = s.has("ride_walk") ? "ride_walk" : s.has("ride_fly") ? "ride_fly" : s.baseWalk();
        String hover = s.has("ride_hover") ? "ride_hover" : s.baseIdle();
        String fly = s.has("ride_fly") ? "ride_fly" : s.baseWalk();
        switch (mo) {
            case "ground" -> set("", groundIdle, groundWalk);
            case "climb" -> set(s.has("ride_climb") ? "ride_climb" : "", hover, fly);
            case "dive" -> set(s.has("ride_dive") ? "ride_dive" : "", hover, fly);
            case "glide" -> set("", hover, s.has("ride_glide") ? "ride_glide" : fly);
            default -> set("", hover, fly);
        }
    }

    private void swimmerTick() {
        Species s = species();
        Vec3 v = motion();
        boolean water = Conditions.inWater(this);
        String next = "air";
        if (onGround()) next = "ground";
        if (water) {
            next = "tread";
            if (v.horizontalDistance() > 0.15) next = "swim";
            if (v.y > 0.12) next = "rise";
            if (v.y < -0.12) next = "dive";
        }
        if (water && !wetMo) {
            wetMo = true;
            particles(ParticleTypes.SPLASH, 30, 0.8, 0.3, 0.8, 0.2);
            particles(ParticleTypes.BUBBLE, 12, 0.6, 0.4, 0.6, 0.05);
            motionSounds("splash");
            if (s.has("ride_plunge")) accent("ride_plunge");
        } else if (!water && wetMo) {
            wetMo = false;
            particles(ParticleTypes.SPLASH, 24, 0.7, 0.4, 0.4, 0.2);
            play("ride_shake_mane");
            if (s.has("ride_breach")) accent("ride_breach");
            motionSounds("breach");
        }
        if (next.equals("ground")) gaitTick(v);
        if (next.equals(mo)) return;
        mo = next;
        String run = s.has("ride_run") ? "ride_run" : s.has("ride_walk") ? "ride_walk" : s.baseWalk();
        switch (mo) {
            case "ground" -> {
                hold("");
                rideGround();
            }
            case "tread" -> set(s.has("ride_tread") ? "ride_tread" : "", s.baseIdle(), s.baseWalk());
            case "swim" -> set(s.has("ride_swim") ? "ride_swim" : "", s.baseIdle(), s.baseWalk());
            case "rise" -> set(s.has("ride_rise") ? "ride_rise" : s.has("ride_swim") ? "ride_swim" : "", s.baseIdle(), s.baseWalk());
            case "dive" -> set(s.has("ride_dive") ? "ride_dive" : s.has("ride_swim") ? "ride_swim" : "", s.baseIdle(), s.baseWalk());
            default -> set("", run, run);
        }
    }

    private void set(String holdClip, String idle, String walk) {
        hold(holdClip);
        setIdleClip(idle);
        setWalkClip(walk);
    }

    /** Every 4 s a ridden mount standing still may do a little something (spec motion.fidgets). */
    private void fidget() {
        JsonObject m = species().motion();
        if (m == null || !m.has("fidgets") || motion().horizontalDistance() > 0.1 || random.nextFloat() >= 0.5f) return;
        if ("walker".equals(motionKind()) && !onGround()) return;
        JsonArray f = m.getAsJsonArray("fidgets");
        java.util.List<String> have = new java.util.ArrayList<>();
        for (JsonElement e : f) if (species().has(e.getAsString())) have.add(e.getAsString());
        if (have.isEmpty()) return;
        String clip = have.get(random.nextInt(have.size()));
        play(clip);
        motionSounds(clip);
    }

    /** Spec motion.sounds: {event: [[delay, sound, volume, pitch], ...]}. */
    void motionSounds(String key) {
        JsonObject m = species().motion();
        if (m == null || !m.has("sounds")) return;
        JsonObject snd = m.getAsJsonObject("sounds");
        if (!snd.has(key)) return;
        for (JsonElement e : snd.getAsJsonArray(key)) {
            JsonArray a = e.getAsJsonArray();
            int delay = a.get(0).getAsInt();
            Species.Sound s = new Species.Sound(a.get(1).getAsString(), a.get(2).getAsFloat(), a.get(3).getAsFloat());
            if (delay <= 0) sound(s);
            else scheduler().after(delay, () -> sound(s));
        }
    }
}
