package net.axther.aftrhrs.feature.smithing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * How a part was made (the {@code aftrhrs:smith_part} component; its material is {@code aftrhrs:smith_material}):
 * stars 1-5, the quench ("" = not quenched), an extra material it carries the trait of (a wrap, or the base it was
 * clad over), and whether it is still rough (cast, not yet finished on the anvil).
 */
public record PartInfo(int stars, String quench, String extra, boolean rough) {
    public static final PartInfo DEFAULT = new PartInfo(3, "", "", false);

    public static final Codec<PartInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1, 5).optionalFieldOf("stars", 3).forGetter(PartInfo::stars),
            Codec.STRING.optionalFieldOf("quench", "").forGetter(PartInfo::quench),
            Codec.STRING.optionalFieldOf("extra", "").forGetter(PartInfo::extra),
            Codec.BOOL.optionalFieldOf("rough", false).forGetter(PartInfo::rough)).apply(i, PartInfo::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, PartInfo> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PartInfo::stars, ByteBufCodecs.STRING_UTF8, PartInfo::quench, ByteBufCodecs.STRING_UTF8, PartInfo::extra,
            ByteBufCodecs.BOOL, PartInfo::rough, PartInfo::new);

    public PartInfo withStars(int s) {
        return new PartInfo(Math.max(1, Math.min(5, s)), quench, extra, rough);
    }

    public PartInfo withQuench(String q) {
        return new PartInfo(stars, q, extra, rough);
    }

    public PartInfo withExtra(String e) {
        return new PartInfo(stars, quench, e, rough);
    }

    public PartInfo finished() {
        return new PartInfo(stars, quench, extra, false);
    }
}
