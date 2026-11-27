package net.axther.aftrhrs.feature.smithing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** An amount of one molten fluid (a ladle's load: the {@code aftrhrs:smith_ladle} component). */
public record Molten(String fluid, int units) {
    public static final Codec<Molten> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("fluid").forGetter(Molten::fluid),
            Codec.INT.fieldOf("units").forGetter(Molten::units)).apply(i, Molten::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, Molten> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Molten::fluid, ByteBufCodecs.VAR_INT, Molten::units, Molten::new);
}
