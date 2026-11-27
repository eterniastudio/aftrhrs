package net.axther.aftrhrs.feature.furniture.curios;

import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.furniture.ItemMatcher;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Everything curios.yml says, read once per (re)load. Also read by the client renderer (bundled defaults). */
public final class CuriosConfig {
    public record Pose(String name, ItemMatcher items, ItemDisplayContext display, float rotateZ, float scale, float lift,
                       float spinSeconds, float tilt) {
    }

    public record ArmPose(String name, ItemMatcher items, float raise) {
    }

    public enum Kind { PEDESTAL, HAT_STAND, STATUE, FLOWER_BOX, DOORMAT }

    public boolean flip = true;
    public boolean observerPulse = true;

    public boolean pedestalEnabled;
    public List<Pattern> pedestalIds = List.of();
    public boolean stackEnabled;
    public String stackBottom = "_bottom", stackMiddle = "_middle", stackTop = "_top";
    public Vector3f pedestalPoint = new Vector3f(8, 16, 8);
    public final List<Pose> poses = new ArrayList<>();
    public boolean enchantEnabled;
    public ItemMatcher enchantItems;
    public int shelvesEach, maxShelves;

    public boolean hatEnabled;
    public List<Pattern> hatIds = List.of();
    public ItemMatcher hats;
    public Vector3f headPoint = new Vector3f(8, 12, 8);
    public float headScale, headYaw;
    public final List<Float> wobble = new ArrayList<>();
    public int wobbleTicks;

    public boolean statueEnabled;
    public List<Pattern> statueIds = List.of();
    public String armItem;
    public Vector3f shoulder = new Vector3f(14, 24, 8), handOffset = new Vector3f(0, -1, 0);
    public final List<ArmPose> armPoses = new ArrayList<>();
    public float defaultRaise;
    public float waveRaise, waveSwing;
    public int waveTicks;

    public boolean flowerEnabled;
    public List<Pattern> flowerIds = List.of();
    public ItemMatcher plants;
    public final List<Vector3f> slots = new ArrayList<>();
    public float plantScale;

    public boolean matEnabled;
    public List<Pattern> matIds = List.of();
    public ItemMatcher editItems;
    public boolean editEmptyHand;
    public int maxLine;
    public String textColor;
    /** {@link #textColor} parsed (opaque ARGB). */
    public int textArgb = 0xFF3B2A1C;
    public float textHeight, textScale;
    public boolean hideItems;

    public final Map<String, String> messages = new LinkedHashMap<>();

    // kindOf / pedestalVariant by id (a small fixed set of furniture ids; the renderer asks every frame), cleared by load()
    private final Map<String, Optional<Kind>> kindCache = new ConcurrentHashMap<>();
    private final Map<String, String> variantCache = new ConcurrentHashMap<>();

    public void load(Yaml c, Consumer<String> warn) {
        kindCache.clear();
        variantCache.clear();
        flip = c.getBoolean("item-display-flip", true);
        observerPulse = c.getBoolean("observer-pulse", true);

        pedestalEnabled = c.getBoolean("pedestal.enabled", true);
        pedestalIds = ItemMatcher.globs(c.getStringList("pedestal.ids"));
        stackEnabled = c.getBoolean("pedestal.stack.enabled", true);
        stackBottom = c.getString("pedestal.stack.bottom", "_bottom");
        stackMiddle = c.getString("pedestal.stack.middle", "_middle");
        stackTop = c.getString("pedestal.stack.top", "_top");
        pedestalPoint = vec(c.getList("pedestal.item-point"), new Vector3f(8, 16, 8));
        poses.clear();
        Yaml ps = c.getConfigurationSection("pedestal.poses");
        if (ps != null) {
            for (String name : ps.getKeys(false)) {
                Yaml p = ps.getConfigurationSection(name);
                if (p == null) continue;
                List<String> items = p.getStringList("items");
                boolean any = items.contains("*");
                ItemMatcher m = any ? null : ItemMatcher.of(items, "pedestal.poses." + name, warn);
                poses.add(new Pose(name, m, transform(p.getString("display", "FIXED"), warn),
                        (float) p.getDouble("rotate-z", 0), (float) p.getDouble("scale", 0.5),
                        (float) p.getDouble("lift", 0.3), (float) p.getDouble("spin-seconds", 0),
                        (float) p.getDouble("tilt", 0)));
            }
        }
        if (poses.isEmpty()) poses.add(new Pose("float", null, ItemDisplayContext.FIXED, 0, 0.5f, 0.35f, 6, 0));
        enchantEnabled = c.getBoolean("pedestal.enchanting.enabled", true);
        enchantItems = ItemMatcher.of(c.getStringList("pedestal.enchanting.items"), "pedestal.enchanting.items", warn);
        shelvesEach = Math.max(0, c.getInt("pedestal.enchanting.bookshelves-each", 3));
        maxShelves = Math.max(0, c.getInt("pedestal.enchanting.max-bookshelves", 15));

        hatEnabled = c.getBoolean("hat-stand.enabled", true);
        hatIds = ItemMatcher.globs(c.getStringList("hat-stand.ids"));
        hats = ItemMatcher.of(c.getStringList("hat-stand.hats"), "hat-stand.hats", warn);
        headPoint = vec(c.getList("hat-stand.head-point"), new Vector3f(8, 12, 8));
        headScale = (float) c.getDouble("hat-stand.head-scale", 0.625);
        headYaw = (float) c.getDouble("hat-stand.head-yaw", 0);
        wobble.clear();
        for (Object o : c.getList("hat-stand.wobble", List.of())) if (o instanceof Number n) wobble.add(n.floatValue());
        if (wobble.isEmpty() || wobble.getLast() != 0f) wobble.add(0f);
        wobbleTicks = Math.max(1, c.getInt("hat-stand.wobble-ticks-per-swing", 4));

        statueEnabled = c.getBoolean("statue.enabled", true);
        statueIds = ItemMatcher.globs(c.getStringList("statue.ids"));
        armItem = c.getString("statue.arm-item", "arcana_curio_fx_statue_arm");
        shoulder = vec(c.getList("statue.shoulder"), new Vector3f(14, 24, 8));
        handOffset = vec(c.getList("statue.hand-offset"), new Vector3f(0, -1, 0));
        armPoses.clear();
        defaultRaise = 0;
        Yaml aps = c.getConfigurationSection("statue.poses");
        if (aps != null) {
            for (String name : aps.getKeys(false)) {
                Yaml p = aps.getConfigurationSection(name);
                if (p == null) continue;
                float raise = (float) p.getDouble("raise", 0);
                if (name.equalsIgnoreCase("default")) defaultRaise = raise;
                else armPoses.add(new ArmPose(name, ItemMatcher.of(p.getStringList("items"), "statue.poses." + name, warn), raise));
            }
        }
        waveRaise = (float) c.getDouble("statue.wave.raise", 150);
        waveSwing = (float) c.getDouble("statue.wave.swing", 22);
        waveTicks = Math.max(1, c.getInt("statue.wave.ticks-per-swing", 8));

        flowerEnabled = c.getBoolean("flower-box.enabled", true);
        flowerIds = ItemMatcher.globs(c.getStringList("flower-box.ids"));
        plants = ItemMatcher.of(c.getStringList("flower-box.plants"), "flower-box.plants", warn);
        slots.clear();
        for (Object o : c.getList("flower-box.slots", List.of())) {
            if (o instanceof List<?> l) slots.add(vec(l, new Vector3f(8, 5, 13)));
        }
        if (slots.isEmpty()) slots.add(new Vector3f(8, 5, 13));
        plantScale = (float) c.getDouble("flower-box.plant-scale", 0.5);

        matEnabled = c.getBoolean("doormat.enabled", true);
        matIds = ItemMatcher.globs(c.getStringList("doormat.ids"));
        editItems = ItemMatcher.of(c.getStringList("doormat.edit-items"), "doormat.edit-items", warn);
        editEmptyHand = c.getBoolean("doormat.edit-with-empty-hand", true);
        maxLine = Math.max(1, Math.min(64, c.getInt("doormat.max-line-length", 20)));
        textColor = c.getString("doormat.text-color", "#3b2a1c");
        textArgb = 0xFF000000 | TextColor.parseColor(textColor).result().map(TextColor::getValue).orElse(0x3b2a1c);
        textHeight = (float) c.getDouble("doormat.text-height", 1.1);
        textScale = (float) c.getDouble("doormat.text-scale", 0.42);
        hideItems = c.getBoolean("doormat.hide-items", true);

        messages.clear();
        Yaml ms = c.getConfigurationSection("messages");
        if (ms != null) for (String k : ms.getKeys(false)) messages.put(k, ms.getString(k, ""));
        // anything asked while loading went by half-read settings
        kindCache.clear();
        variantCache.clear();
    }

    public String msg(String key) {
        return messages.getOrDefault(key, key);
    }

    /** Which kind of curio a furniture id is, or null. Stack pieces (_bottom/_middle/_top) count as pedestals. */
    public Kind kindOf(String id) {
        if (id == null) return null;
        return kindCache.computeIfAbsent(id, i -> Optional.ofNullable(computeKind(i))).orElse(null);
    }

    private Kind computeKind(String id) {
        if (pedestalEnabled && ItemMatcher.anyMatch(pedestalIds, pedestalVariant(id))) return Kind.PEDESTAL;
        if (hatEnabled && ItemMatcher.anyMatch(hatIds, id)) return Kind.HAT_STAND;
        if (statueEnabled && ItemMatcher.anyMatch(statueIds, id)) return Kind.STATUE;
        if (flowerEnabled && ItemMatcher.anyMatch(flowerIds, id)) return Kind.FLOWER_BOX;
        if (matEnabled && ItemMatcher.anyMatch(matIds, id)) return Kind.DOORMAT;
        return null;
    }

    /** The plain pedestal id of a (possibly stacked) pedestal id. */
    public String pedestalVariant(String id) {
        return variantCache.computeIfAbsent(id, this::computeVariant);
    }

    private String computeVariant(String id) {
        if (endsWith(id, stackBottom)) return id.substring(0, id.length() - stackBottom.length());
        if (endsWith(id, stackMiddle)) return id.substring(0, id.length() - stackMiddle.length());
        if (endsWith(id, stackTop)) return id.substring(0, id.length() - stackTop.length());
        return id;
    }

    private static boolean endsWith(String id, String suf) {
        return suf != null && !suf.isEmpty() && id.endsWith(suf);
    }

    public Pose poseOf(net.minecraft.world.item.ItemStack stack) {
        for (Pose p : poses) if (p.items() == null || p.items().matches(stack)) return p;
        return poses.getLast();
    }

    public float raiseFor(net.minecraft.world.item.ItemStack held) {
        if (held != null && !held.isEmpty()) for (ArmPose p : armPoses) if (p.items().matches(held)) return p.raise();
        return defaultRaise;
    }

    private static Vector3f vec(List<?> l, Vector3f def) {
        if (l == null || l.size() < 3) return def;
        float[] f = new float[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n)) return def;
            f[i] = n.floatValue();
        }
        return new Vector3f(f[0], f[1], f[2]);
    }

    /** Bukkit's ItemDisplayTransform names (THIRDPERSON_RIGHTHAND ...) and vanilla's (third_person_right_hand). */
    static ItemDisplayContext transform(String s, Consumer<String> warn) {
        String k = s.trim().toUpperCase(Locale.ROOT);
        String v = switch (k) {
            case "THIRDPERSON_LEFTHAND" -> "THIRD_PERSON_LEFT_HAND";
            case "THIRDPERSON_RIGHTHAND" -> "THIRD_PERSON_RIGHT_HAND";
            case "FIRSTPERSON_LEFTHAND" -> "FIRST_PERSON_LEFT_HAND";
            case "FIRSTPERSON_RIGHTHAND" -> "FIRST_PERSON_RIGHT_HAND";
            default -> k;
        };
        try {
            return ItemDisplayContext.valueOf(v);
        } catch (IllegalArgumentException e) {
            warn.accept("curios.yml: unknown display transform " + s + " (using FIXED)");
            return ItemDisplayContext.FIXED;
        }
    }
}
