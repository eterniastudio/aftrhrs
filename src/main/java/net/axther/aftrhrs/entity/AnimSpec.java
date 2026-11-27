package net.axther.aftrhrs.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A species' animation data (the {@code anim} block of {@code data/aftrhrs/species/<id>.json}, written by
 * {@code tools/gen_creatures.py} via {@code tools/animtags.py}): one {@link Tag} per clip, the state -> clip map, the
 * posture transitions, the weighted idle pools and the bones the procedural layer moves. See PORTING.md "Animation".
 */
public final class AnimSpec {
    public static final String STANDING = "standing", SITTING = "sitting", LYING = "lying", BELLY = "belly",
            AIRBORNE = "airborne", SWIMMING = "swimming";
    public static final String BASE = "base", OVERLAY = "overlay", ADDITIVE = "additive";

    /**
     * One clip's classification. {@code posture}: the pose the clip is in (a transition: where it ends, {@code from}
     * where it starts); {@code move}: may keep playing while the creature moves; {@code layer}: base / overlay /
     * additive; {@code drv}: what it animates for the procedural layer (h head, b body, t tail, e ears).
     */
    public record Tag(String clip, String posture, @Nullable String from, boolean move, String loop, String layer,
                      String drv, boolean eyes, float len, @Nullable String gait) {
        public boolean loops() {
            return "loop".equals(loop);
        }

        public boolean holds() {
            return "hold".equals(loop);
        }

        public boolean once() {
            return "once".equals(loop);
        }

        public boolean base() {
            return BASE.equals(layer);
        }

        public boolean overlay() {
            return OVERLAY.equals(layer);
        }

        public boolean transition() {
            return from != null && !from.equals(posture);
        }

        /** True for postures the creature cannot walk in (sit, lie, belly). */
        public boolean grounded() {
            return SITTING.equals(posture) || LYING.equals(posture) || BELLY.equals(posture);
        }

        public boolean drives(char c) {
            return drv.indexOf(c) >= 0;
        }

        public int ticks() {
            return Math.max(1, Math.round(len * 20));
        }
    }

    /**
     * An idle pool entry: {@code clip} (or {@code @mood}, {@code @sit}, {@code @sleep}), weight, how long a loop lasts
     * (min / max seconds), cooldown seconds, a condition that must hold ({@code when}, comma = and, {@code !} = not) and
     * weight multipliers per condition ({@code boost}).
     */
    public record IdleEntry(String clip, double weight, double min, double max, double cooldown, String when,
                            Map<String, Double> boost) {
    }

    /** Bones for the procedural layer (head look, breathing, tail sway, ear twitch); null / empty when absent. */
    public record Procedural(@Nullable String head, @Nullable String body, List<String> tail, List<String> ears,
                             float breath, float tailSway, float earTwitch, float lookYaw, float lookPitch) {
    }

    /** The tag of a clip the spec does not know (and of no clip). */
    public static final Tag NONE = new Tag("", STANDING, null, false, "once", BASE, "", false, 1, null);

    public final Map<String, Tag> tags;
    public final Map<String, String> states;
    /** "from>to" posture -> transition clip. */
    public final Map<String, String> transitions;
    public final Map<String, List<IdleEntry>> idle;
    public final Procedural procedural;
    /** Movement thresholds in blocks per tick: moving above walkOn, still below walkOff; run above runOn, back below runOff. */
    public final double walkOn, walkOff, runOn, runOff;
    /** Procedural bank (procedural {@code bank}: degrees of roll per degree of yaw turned per tick, 0 = off) and its
     *  limit ({@code bank_max}): flyers in the air and swimmers in water lean into their turns. */
    public final float bank, bankMax;
    /** Head-look limits in a fishbowl (procedural {@code bowl_look_yaw} / {@code bowl_look_pitch}; default the normal
     *  ones): the Pockie's head must never turn through the glass (tools/bowl_check.py checks these limits). */
    public final float bowlLookYaw, bowlLookPitch;
    /** Hitbox scale (width, height; the eye scales with the height) per grounded posture. */
    private final Map<String, float[]> boxes = new HashMap<>();

    AnimSpec(@Nullable JsonObject j, double speed) {
        Map<String, Tag> tags = new LinkedHashMap<>();
        Map<String, String> states = new LinkedHashMap<>();
        Map<String, String> trans = new LinkedHashMap<>();
        Map<String, List<IdleEntry>> idle = new LinkedHashMap<>();
        String head = null, body = null;
        List<String> tail = List.of(), ears = List.of();
        float breath = 0.012f, sway = 5f, twitch = 1f, lookYaw = 55f, lookPitch = 35f, bank = 0f, bankMax = 30f;
        float bowlYaw = -1, bowlPitch = -1;
        double walkOn = 0.012, walkOff = 0.005, runOn = Math.max(0.16, speed * 0.8), runOff = Math.max(0.13, speed * 0.68);
        if (j != null) {
            if (j.has("clips")) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("clips").entrySet()) {
                    JsonObject t = e.getValue().getAsJsonObject();
                    tags.put(e.getKey(), new Tag(e.getKey(), Species.str(t, "p", STANDING), Species.str(t, "from", null),
                            t.has("move") && t.get("move").getAsBoolean(), Species.str(t, "loop", "once"), Species.str(t, "layer", BASE),
                            Species.str(t, "drv", ""), t.has("eyes") && t.get("eyes").getAsBoolean(), (float) Species.dbl(t, "len", 1),
                            Species.str(t, "gait", null)));
                }
            }
            if (j.has("states")) j.getAsJsonObject("states").entrySet().forEach(e -> states.put(e.getKey(), e.getValue().getAsString()));
            if (j.has("transitions")) j.getAsJsonObject("transitions").entrySet().forEach(e -> trans.put(e.getKey(), e.getValue().getAsString()));
            if (j.has("idle")) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("idle").entrySet()) {
                    List<IdleEntry> list = new ArrayList<>();
                    for (JsonElement x : e.getValue().getAsJsonArray()) {
                        JsonObject o = x.getAsJsonObject();
                        Map<String, Double> boost = new HashMap<>();
                        if (o.has("boost")) o.getAsJsonObject("boost").entrySet().forEach(b -> boost.put(b.getKey(), b.getValue().getAsDouble()));
                        list.add(new IdleEntry(o.get("clip").getAsString(), Species.dbl(o, "w", 1), Species.dbl(o, "min", 6),
                                Species.dbl(o, "max", 16), Species.dbl(o, "cd", 0), Species.str(o, "when", ""), Map.copyOf(boost)));
                    }
                    idle.put(e.getKey(), List.copyOf(list));
                }
            }
            JsonObject p = j.has("procedural") ? j.getAsJsonObject("procedural") : null;
            if (p != null) {
                head = Species.str(p, "head", null);
                body = Species.str(p, "body", null);
                if (p.has("tail")) tail = Species.strings(p.getAsJsonArray("tail"));
                if (p.has("ears")) ears = Species.strings(p.getAsJsonArray("ears"));
                breath = (float) Species.dbl(p, "breath", breath);
                sway = (float) Species.dbl(p, "tail_sway", sway);
                twitch = (float) Species.dbl(p, "ear_twitch", twitch);
                lookYaw = (float) Species.dbl(p, "look_yaw", lookYaw);
                lookPitch = (float) Species.dbl(p, "look_pitch", lookPitch);
                bank = (float) Species.dbl(p, "bank", bank);
                bankMax = (float) Species.dbl(p, "bank_max", bankMax);
                bowlYaw = (float) Species.dbl(p, "bowl_look_yaw", -1);
                bowlPitch = (float) Species.dbl(p, "bowl_look_pitch", -1);
            }
            if (j.has("boxes")) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("boxes").entrySet()) {
                    var arr = e.getValue().getAsJsonArray();
                    boxes.put(e.getKey(), new float[] {arr.get(0).getAsFloat(), arr.get(1).getAsFloat()});
                }
            }
            JsonObject g = j.has("gait") ? j.getAsJsonObject("gait") : null;
            if (g != null) {
                walkOn = Species.dbl(g, "walk_on", walkOn);
                walkOff = Species.dbl(g, "walk_off", walkOff);
                runOn = Species.dbl(g, "run_on", runOn);
                runOff = Species.dbl(g, "run_off", runOff);
            }
        }
        this.tags = Collections.unmodifiableMap(tags);
        this.states = Collections.unmodifiableMap(states);
        this.transitions = Collections.unmodifiableMap(trans);
        this.idle = Collections.unmodifiableMap(idle);
        this.procedural = new Procedural(head, body, tail, ears, breath, sway, twitch, lookYaw, lookPitch);
        this.bowlLookYaw = bowlYaw >= 0 ? bowlYaw : lookYaw;
        this.bowlLookPitch = bowlPitch >= 0 ? bowlPitch : lookPitch;
        this.walkOn = walkOn;
        this.walkOff = walkOff;
        this.runOn = runOn;
        this.runOff = runOff;
        this.bank = bank;
        this.bankMax = bankMax;
    }

    /** The clip's tag; an unknown clip counts as a standing base one-shot that stops when the creature moves. */
    public Tag tag(@Nullable String clip) {
        if (clip == null) return NONE;
        Tag t = tags.get(clip);
        return t != null ? t : NONE;
    }

    /** Hitbox (width, height) scale while sitting / lying: data {@code boxes}, else sit 1 x 0.8, lie 1.1 x 0.6. */
    public float[] box(String posture) {
        float[] b = boxes.get(posture);
        if (b != null) return b;
        return SITTING.equals(posture) ? new float[] {1f, 0.8f} : new float[] {1.1f, 0.6f};
    }

    public @Nullable String state(String state) {
        return states.get(state);
    }

    public List<IdleEntry> idle(String posture) {
        return idle.getOrDefault(posture, List.of());
    }

    /** The posture a held / looping clip puts the creature in ("" = standing). */
    public String posture(@Nullable String clip) {
        return clip == null || clip.isEmpty() ? STANDING : tag(clip).posture();
    }

    /**
     * The transition clips from one posture to another (at most two hops, e.g. lying -> sitting -> standing =
     * wake_up, stand_up); empty if there is no authored way (the state machine then cross-fades).
     */
    public List<String> path(String from, String to) {
        if (from.equals(to)) return List.of();
        String direct = transitions.get(from + ">" + to);
        if (direct != null) return List.of(direct);
        Deque<List<String>> queue = new ArrayDeque<>();
        Map<String, Boolean> seen = new HashMap<>();
        queue.add(List.of(from));
        seen.put(from, true);
        while (!queue.isEmpty()) {
            List<String> route = queue.poll();
            if (route.size() > 3) break;
            String at = route.get(route.size() - 1);
            for (Map.Entry<String, String> e : transitions.entrySet()) {
                String[] k = e.getKey().split(">");
                if (!k[0].equals(at) || seen.containsKey(k[1])) continue;
                List<String> next = new ArrayList<>(route);
                next.add(k[1]);
                if (k[1].equals(to)) {
                    List<String> clips = new ArrayList<>();
                    for (int i = 0; i + 1 < next.size(); i++) clips.add(transitions.get(next.get(i) + ">" + next.get(i + 1)));
                    return List.copyOf(clips);
                }
                seen.put(k[1], true);
                queue.add(next);
            }
        }
        return List.of();
    }
}
