package net.axther.aftrhrs.feature.smithing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a smithed tool is made of (the {@code aftrhrs:smith_build} component): its tool type, one entry per part in the
 * type's order, the modifiers etched into it in the order they were added (id and level), the progress of incremental
 * modifiers and counters (id to amount), and who forged it.
 */
public record ToolBuild(String tool, List<PartEntry> parts, List<Mod> mods, Map<String, Integer> progress, String smith) {

    /**
     * One part of a tool: which part, its material, how well it was made (1-5 stars), how it was quenched ("" = not
     * quenched: soft, for metal parts) and an extra material whose trait it also carries ("" = none: a handle's wrap, or
     * the base a head was clad over).
     */
    public record PartEntry(String part, String material, int stars, String quench, String extra) {
        public static final Codec<PartEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("part").forGetter(PartEntry::part),
                Codec.STRING.fieldOf("material").forGetter(PartEntry::material),
                Codec.intRange(1, 5).optionalFieldOf("stars", 3).forGetter(PartEntry::stars),
                Codec.STRING.optionalFieldOf("quench", "").forGetter(PartEntry::quench),
                Codec.STRING.optionalFieldOf("extra", "").forGetter(PartEntry::extra)).apply(i, PartEntry::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, PartEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PartEntry::part, ByteBufCodecs.STRING_UTF8, PartEntry::material, ByteBufCodecs.VAR_INT, PartEntry::stars,
                ByteBufCodecs.STRING_UTF8, PartEntry::quench, ByteBufCodecs.STRING_UTF8, PartEntry::extra, PartEntry::new);

        public PartEntry(String part, String material, int stars) {
            this(part, material, stars, "", "");
        }

        public PartEntry withStars(int s) {
            return new PartEntry(part, material, Math.max(1, Math.min(5, s)), quench, extra);
        }
    }

    /** A modifier on a tool and its level. */
    public record Mod(String id, int level) {
        public static final Codec<Mod> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Mod::id),
                Codec.INT.optionalFieldOf("level", 1).forGetter(Mod::level)).apply(i, Mod::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Mod> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Mod::id, ByteBufCodecs.VAR_INT, Mod::level, Mod::new);
    }

    public static final Codec<ToolBuild> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("tool").forGetter(ToolBuild::tool),
            PartEntry.CODEC.listOf().fieldOf("parts").forGetter(ToolBuild::parts),
            Mod.CODEC.listOf().optionalFieldOf("modifiers", List.of()).forGetter(ToolBuild::mods),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("progress", Map.of()).forGetter(ToolBuild::progress),
            Codec.STRING.optionalFieldOf("smith", "").forGetter(ToolBuild::smith)).apply(i, ToolBuild::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolBuild> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ToolBuild::tool,
            PartEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), ToolBuild::parts,
            Mod.STREAM_CODEC.apply(ByteBufCodecs.list()), ToolBuild::mods,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), ToolBuild::progress,
            ByteBufCodecs.STRING_UTF8, ToolBuild::smith,
            ToolBuild::new);

    public ToolBuild {
        parts = List.copyOf(parts);
        mods = List.copyOf(mods);
        progress = Map.copyOf(progress);
    }

    /** A fresh tool of {@code type} from these materials and stars (one per part, in the type's order; the last repeats). */
    public static ToolBuild of(SmithingDefs.ToolType type, List<String> materials, List<Integer> stars) {
        List<PartEntry> parts = new ArrayList<>();
        for (int i = 0; i < type.parts().size(); i++) {
            String mat = materials.get(Math.min(i, materials.size() - 1));
            int s = stars == null || stars.isEmpty() ? 3 : stars.get(Math.min(i, stars.size() - 1));
            SmithingDefs.Material m = SmithingDefs.material(mat);
            parts.add(new PartEntry(type.parts().get(i).part(), mat, Math.max(1, Math.min(5, s)), m != null && m.metal() ? "water" : "", ""));
        }
        return new ToolBuild(type.id(), parts, List.of(), Map.of(), "");
    }

    public ToolBuild withPart(int index, PartEntry entry) {
        List<PartEntry> out = new ArrayList<>(parts);
        out.set(index, entry);
        return new ToolBuild(tool, out, mods, progress, smith);
    }

    public int level(String mod) {
        for (Mod m : mods) if (m.id().equals(mod)) return m.level();
        return 0;
    }

    /** This build with {@code mod} at {@code level} (0 removes it; a new modifier goes last). */
    public ToolBuild withMod(String mod, int level) {
        List<Mod> out = new ArrayList<>();
        boolean found = false;
        for (Mod m : mods) {
            if (m.id().equals(mod)) {
                found = true;
                if (level > 0) out.add(new Mod(mod, level));
            } else out.add(m);
        }
        if (!found && level > 0) out.add(new Mod(mod, level));
        return new ToolBuild(tool, parts, out, progress, smith);
    }

    public int progress(String key) {
        return progress.getOrDefault(key, 0);
    }

    public ToolBuild withProgress(String key, int value) {
        Map<String, Integer> out = new HashMap<>(progress);
        if (value <= 0) out.remove(key);
        else out.put(key, value);
        return new ToolBuild(tool, parts, mods, out, smith);
    }

    public ToolBuild withSmith(String name) {
        return new ToolBuild(tool, parts, mods, progress, name == null ? "" : name);
    }

    /** Average stars over every part (1-5). */
    public float quality() {
        if (parts.isEmpty()) return 3;
        float sum = 0;
        for (PartEntry p : parts) sum += p.stars();
        return sum / parts.size();
    }

    /** A Masterwork: every part made at 5 stars. */
    public boolean masterwork() {
        if (parts.isEmpty()) return false;
        for (PartEntry p : parts) if (p.stars() < 5) return false;
        return true;
    }

    /** Index of the first part entry made of {@code partId}, or -1. */
    public int indexOf(String partId) {
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).part().equals(partId)) return i;
        return -1;
    }
}
