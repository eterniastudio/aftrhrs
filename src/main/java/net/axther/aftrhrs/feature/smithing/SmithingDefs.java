package net.axther.aftrhrs.feature.smithing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anvil & Ember's definitions (SMITHING.md): materials, parts and tool types, read once from the bundled JSON under
 * {@code data/aftrhrs/smithing/}. Server, client and the art generator ({@code tools/gen_smithing.py}) read the same
 * files, so a part's look and its stats can never disagree.
 */
public final class SmithingDefs {
    /** Head stats: the main numbers of a tool. */
    public record Head(int durability, float speed, String tier, float attack) {
    }

    /** Handle multipliers. */
    public record Handle(float durability, float speed, float attack, float attackSpeed) {
        static final Handle NEUTRAL = new Handle(1, 1, 1, 1);
    }

    /** Bow limb stats. */
    public record Limb(int durability, float drawSpeed, float velocity, float accuracy, float damage) {
    }

    /** Bow grip / dagger grip stats. */
    public record Grip(float durability, float accuracy, float damage) {
    }

    /** Armor plating stats (durability is per vanilla armor multiplier unit). */
    public record Plating(int durability, float armor, float toughness, float knockbackResistance) {
    }

    public record Material(String id, int tier, String kind, List<String> palette, Head head, Handle handle, Limb limb, Grip grip,
                           Plating plating, Map<String, String> traits, JsonObject source, int melt, int[] window, int molten) {
        /** The trait this material gives a part in {@code role} (its "any" trait otherwise), or null. */
        public String trait(String role) {
            String t = traits.get(role);
            return t != null ? t : traits.get("any");
        }

        /** Can a part of {@code role} be made from this material? */
        public boolean fits(String role) {
            return switch (role) {
                case "head" -> head != null;
                case "handle" -> handle != null;
                case "limb" -> limb != null;
                case "grip" -> grip != null || handle != null;
                case "plating" -> plating != null;
                case "string" -> kind.equals("string");
                case "fletching" -> kind.equals("fletching");
                case "binding" -> !kind.equals("string") && !kind.equals("fletching");
                default -> false;
            };
        }

        public boolean metal() {
            return kind.equals("metal");
        }
    }

    public record Part(String id, String role, int cost, int makes, List<String> made, List<String> plan) {
    }

    /** One part slot of a tool type: which part, its weight in the head average, the sprite layer it draws. */
    public record ToolPart(String part, float weight, String layer) {
    }

    public record ToolType(String id, String kind, List<ToolPart> parts, float baseAttack, float baseAttackSpeed,
                           Map<String, Float> mult, List<String> mines, String weapon, int[] slots, Set<String> behavior,
                           int makes, String armorSlot) {
        public float mult(String key) {
            return mult.getOrDefault(key, 1f);
        }

        /** The distinct sprite layers in drawing order (each draws with its first part's material). */
        public List<String> layers() {
            List<String> out = new ArrayList<>();
            for (ToolPart p : parts) if (!out.contains(p.layer())) out.add(p.layer());
            return out;
        }

        public boolean has(String behaviorKey) {
            return behavior.contains(behaviorKey);
        }
    }

    /** An ingredient: an item id or an item tag, and a count. */
    public record Need(String item, String tag, int count) {
        private static final Map<String, net.minecraft.tags.TagKey<net.minecraft.world.item.Item>> TAGS = new java.util.concurrent.ConcurrentHashMap<>();

        public boolean test(net.minecraft.world.item.ItemStack s) {
            if (s.isEmpty()) return false;
            if (item != null) return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(item);
            return s.is(TAGS.computeIfAbsent(tag, t -> net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    net.minecraft.resources.Identifier.parse(t))));
        }

        static Need of(JsonObject o) {
            return new Need(o.has("item") ? o.get("item").getAsString() : null, o.has("tag") ? o.get("tag").getAsString() : null,
                    o.has("count") ? o.get("count").getAsInt() : 1);
        }
    }

    /**
     * A modifier (SMITHING.md 7): its slot (U / A / D, or "" for slotless), max level, what it fits, and its catalysts:
     * {@code per} for incremental ones (that many per level, paid a few at a time) or {@code needs} (all at once).
     */
    public record Modifier(String id, String slot, int max, List<String> fits, Need per, List<Need> needs, String enchant) {
        public boolean incremental() {
            return per != null;
        }
    }

    /** One additive of an alloy: any of these items, {@code count} per batch. */
    public record Additive(List<String> items, int count) {
        public boolean test(net.minecraft.world.item.ItemStack s) {
            return !s.isEmpty() && items.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        }
    }

    public record Alloy(String id, int units, Map<String, Integer> inputs, List<Additive> additives, int heat, boolean great) {
    }

    private static final Map<String, Material> MATERIALS = new LinkedHashMap<>();
    private static final Map<String, Part> PARTS = new LinkedHashMap<>();
    private static final Map<String, ToolType> TOOLS = new LinkedHashMap<>();
    private static final Map<String, Modifier> MODIFIERS = new LinkedHashMap<>();
    private static final Map<String, Alloy> ALLOYS = new LinkedHashMap<>();
    private static boolean loaded;

    private SmithingDefs() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        for (JsonElement e : read("materials.json").getAsJsonArray("materials")) {
            JsonObject o = e.getAsJsonObject();
            Map<String, String> traits = new HashMap<>();
            if (o.has("trait")) for (var t : o.getAsJsonObject("trait").entrySet()) traits.put(t.getKey(), t.getValue().getAsString());
            int[] window = null;
            if (o.has("window")) {
                JsonArray w = o.getAsJsonArray("window");
                window = new int[]{w.get(0).getAsInt(), w.get(1).getAsInt()};
            }
            List<String> palette = strings(o.getAsJsonArray("palette"));
            Material m = new Material(o.get("id").getAsString(), o.get("tier").getAsInt(), o.get("kind").getAsString(),
                    palette, head(o), handle(o), limb(o), grip(o), plating(o),
                    Collections.unmodifiableMap(traits), o.getAsJsonObject("source"), o.has("melt") ? o.get("melt").getAsInt() : 0, window,
                    moltenColor(palette));
            MATERIALS.put(m.id(), m);
        }
        for (JsonElement e : read("parts.json").getAsJsonArray("parts")) {
            JsonObject o = e.getAsJsonObject();
            Part p = new Part(o.get("id").getAsString(), o.get("role").getAsString(), o.get("cost").getAsInt(),
                    o.has("makes") ? o.get("makes").getAsInt() : 1, strings(o.getAsJsonArray("made")), strings(o.getAsJsonArray("plan")));
            PARTS.put(p.id(), p);
        }
        for (JsonElement e : read("tools.json").getAsJsonArray("tools")) {
            JsonObject o = e.getAsJsonObject();
            List<ToolPart> parts = new ArrayList<>();
            for (JsonElement pe : o.getAsJsonArray("parts")) {
                JsonObject po = pe.getAsJsonObject();
                String part = po.get("part").getAsString();
                if (!PARTS.containsKey(part)) throw new IllegalStateException("smithing tool " + o.get("id") + ": unknown part " + part);
                parts.add(new ToolPart(part, po.has("weight") ? po.get("weight").getAsFloat() : 1f, po.get("layer").getAsString()));
            }
            JsonObject base = o.has("base") ? o.getAsJsonObject("base") : new JsonObject();
            Map<String, Float> mult = new HashMap<>();
            if (o.has("mult")) for (var me : o.getAsJsonObject("mult").entrySet()) mult.put(me.getKey(), me.getValue().getAsFloat());
            JsonArray slots = o.getAsJsonArray("slots");
            ToolType t = new ToolType(o.get("id").getAsString(), o.get("kind").getAsString(), List.copyOf(parts),
                    base.has("attack") ? base.get("attack").getAsFloat() : 0f, base.has("attack_speed") ? base.get("attack_speed").getAsFloat() : 1f,
                    Collections.unmodifiableMap(mult), o.has("mines") ? strings(o.getAsJsonArray("mines")) : List.of(),
                    o.has("weapon") ? o.get("weapon").getAsString() : null,
                    new int[]{slots.get(0).getAsInt(), slots.get(1).getAsInt(), slots.get(2).getAsInt()},
                    o.has("behavior") ? Collections.unmodifiableSet(new LinkedHashSet<>(strings(o.getAsJsonArray("behavior")))) : Set.of(),
                    o.has("makes") ? o.get("makes").getAsInt() : 1, o.has("slot") ? o.get("slot").getAsString() : null);
            if (t.layers().size() > Smithing.LOOK_LAYERS) throw new IllegalStateException("smithing tool " + t.id() + " has more than " + Smithing.LOOK_LAYERS + " layers");
            TOOLS.put(t.id(), t);
        }
        for (JsonElement e : read("modifiers.json").getAsJsonArray("modifiers")) {
            JsonObject o = e.getAsJsonObject();
            List<Need> needs = new ArrayList<>();
            if (o.has("needs")) for (JsonElement n : o.getAsJsonArray("needs")) needs.add(Need.of(n.getAsJsonObject()));
            Modifier m = new Modifier(o.get("id").getAsString(), o.get("slot").getAsString(), o.get("max").getAsInt(),
                    strings(o.getAsJsonArray("for")), o.has("per") ? Need.of(o.getAsJsonObject("per")) : null, List.copyOf(needs),
                    o.has("enchant") ? o.get("enchant").getAsString() : null);
            MODIFIERS.put(m.id(), m);
        }
        for (JsonElement e : read("alloys.json").getAsJsonArray("alloys")) {
            JsonObject o = e.getAsJsonObject();
            Map<String, Integer> inputs = new LinkedHashMap<>();
            for (var in : o.getAsJsonObject("inputs").entrySet()) inputs.put(in.getKey(), in.getValue().getAsInt());
            List<Additive> adds = new ArrayList<>();
            for (JsonElement a : o.getAsJsonArray("additives")) {
                JsonObject ao = a.getAsJsonObject();
                adds.add(new Additive(strings(ao.getAsJsonArray("items")), ao.get("count").getAsInt()));
            }
            Alloy a = new Alloy(o.get("id").getAsString(), o.get("units").getAsInt(), Collections.unmodifiableMap(inputs), List.copyOf(adds),
                    o.get("heat").getAsInt(), o.has("great") && o.get("great").getAsBoolean());
            ALLOYS.put(a.id(), a);
        }
    }

    public static Map<String, Modifier> modifiers() {
        load();
        return Collections.unmodifiableMap(MODIFIERS);
    }

    public static Modifier modifier(String id) {
        load();
        return id == null ? null : MODIFIERS.get(id);
    }

    public static Map<String, Alloy> alloys() {
        load();
        return Collections.unmodifiableMap(ALLOYS);
    }

    public static Map<String, Material> materials() {
        load();
        return Collections.unmodifiableMap(MATERIALS);
    }

    public static Map<String, Part> parts() {
        load();
        return Collections.unmodifiableMap(PARTS);
    }

    public static Map<String, ToolType> tools() {
        load();
        return Collections.unmodifiableMap(TOOLS);
    }

    public static Material material(String id) {
        load();
        return id == null ? null : MATERIALS.get(id);
    }

    public static Part part(String id) {
        load();
        return id == null ? null : PARTS.get(id);
    }

    public static ToolType tool(String id) {
        load();
        return id == null ? null : TOOLS.get(id);
    }

    // ------------------------------------------------------------------ reading

    private static JsonObject read(String file) {
        String path = "/data/aftrhrs/smithing/" + file;
        try (InputStream in = SmithingDefs.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return List.copyOf(out);
    }

    /** The molten color ({@code Smelt.color}): palette entry 4 (or the last), 0xFF8030 for an empty or bad palette. */
    private static int moltenColor(List<String> palette) {
        if (palette.isEmpty()) return 0xFF8030;
        String hex = palette.get(Math.min(4, palette.size() - 1));
        try {
            return Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFF8030;
        }
    }

    private static float f(JsonObject o, String k, float def) {
        return o.has(k) ? o.get(k).getAsFloat() : def;
    }

    private static Head head(JsonObject o) {
        if (!o.has("head")) return null;
        JsonObject h = o.getAsJsonObject("head");
        return new Head(h.get("durability").getAsInt(), f(h, "speed", 1), h.get("tier").getAsString(), f(h, "attack", 0));
    }

    private static Handle handle(JsonObject o) {
        if (!o.has("handle")) return null;
        JsonObject h = o.getAsJsonObject("handle");
        return new Handle(f(h, "durability", 1), f(h, "speed", 1), f(h, "attack", 1), f(h, "attack_speed", 1));
    }

    private static Limb limb(JsonObject o) {
        if (!o.has("limb")) return null;
        JsonObject h = o.getAsJsonObject("limb");
        return new Limb(h.get("durability").getAsInt(), f(h, "draw_speed", 1), f(h, "velocity", 1), f(h, "accuracy", 1), f(h, "damage", 0));
    }

    private static Grip grip(JsonObject o) {
        if (!o.has("grip")) return null;
        JsonObject h = o.getAsJsonObject("grip");
        return new Grip(f(h, "durability", 1), f(h, "accuracy", 1), f(h, "damage", 0));
    }

    private static Plating plating(JsonObject o) {
        if (!o.has("plating")) return null;
        JsonObject h = o.getAsJsonObject("plating");
        return new Plating(h.get("durability").getAsInt(), f(h, "armor", 0), f(h, "toughness", 0), f(h, "knockback_resistance", 0));
    }
}
