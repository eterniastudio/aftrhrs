package net.axther.aftrhrs.feature.relics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Registration-time parts of the relics (called from {@code AftrHrs.onInitialize}, both sides): the thrown relic trident
 * entity type, the worn-backpack attachment ({@link BackpackWear}) and the backpack payloads (who wears which, the
 * Equip/Open Backpack key, the back-slot settings). Relic item models the mechanics swap to live in the
 * {@code aftrhrs_relics} namespace ({@code mod/tools/gen_relic_models.py}).
 */
public final class RelicsInit {
    /** Namespace of the relic frame / thrown item definitions. */
    public static final String MODELS = "aftrhrs_relics";

    public static EntityType<ThrownRelicTrident> THROWN_RELIC_TRIDENT;

    /** Server → clients: what backpack (EMPTY: none) a player wears on their back. */
    public record BackpackPayload(int entityId, ItemStack stack) implements CustomPacketPayload {
        public static final Type<BackpackPayload> TYPE = new Type<>(AftrHrs.id("backpack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BackpackPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BackpackPayload::entityId, ItemStack.OPTIONAL_STREAM_CODEC, BackpackPayload::stack,
                BackpackPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: the Equip/Open Backpack key was pressed ({@code sneak}: while sneaking, i.e. take it off). */
    public record BackpackKeyPayload(boolean sneak) implements CustomPacketPayload {
        public static final Type<BackpackKeyPayload> TYPE = new Type<>(AftrHrs.id("backpack_key"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BackpackKeyPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BackpackKeyPayload::sneak, BackpackKeyPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server → client (on join and after a reload): may backpacks be worn here (the back slot shows), and is a worn
     * backpack hidden while an elytra is in the chest slot (tools.yml {@code backpacks.wear}, {@code backpacks.elytra}).
     */
    public record BackSlotPayload(boolean wear, boolean hideWithElytra) implements CustomPacketPayload {
        public static final Type<BackSlotPayload> TYPE = new Type<>(AftrHrs.id("back_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BackSlotPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BackSlotPayload::wear, ByteBufCodecs.BOOL, BackSlotPayload::hideWithElytra, BackSlotPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static boolean initialized;

    private RelicsInit() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, AftrHrs.id("thrown_relic_trident"));
        THROWN_RELIC_TRIDENT = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.<ThrownRelicTrident>of(ThrownRelicTrident::new, MobCategory.MISC)
                        .noLootTable().sized(0.5f, 0.5f).eyeHeight(0.13f).clientTrackingRange(4).updateInterval(20).build(key));
        PayloadTypeRegistry.clientboundPlay().register(BackpackPayload.TYPE, BackpackPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BackSlotPayload.TYPE, BackSlotPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BackpackKeyPayload.TYPE, BackpackKeyPayload.CODEC);
        BackpackWear.init();
    }

    /** The content entry's {@code mechanics.<name>} object, or null. */
    public static @Nullable JsonObject mechanic(@Nullable String id, String name) {
        if (id == null) return null;
        ArcanaContent.Content c = ArcanaContent.entry(id);
        JsonObject ms = c == null ? null : c.mechanics();
        if (ms == null) return null;
        JsonElement m = ms.get(name);
        return m != null && m.isJsonObject() ? m.getAsJsonObject() : null;
    }

    /** An Oraxen model key ({@code minecraft:arcana/relics/x}, {@code arcana/relics/x}) as a relic item definition id. */
    public static Identifier model(String oraxenModel) {
        String p = oraxenModel;
        int i = p.indexOf(':');
        if (i >= 0) p = p.substring(i + 1);
        return Identifier.fromNamespaceAndPath(MODELS, p);
    }

    /** The thrown model of a relic trident (Oraxen {@code trident.appearance.thrown-model}), or null for plain tridents. */
    public static @Nullable Identifier thrownModel(ItemStack stack) {
        JsonObject t = mechanic(ArcanaContent.id(stack), "trident");
        if (t == null || !t.has("appearance")) return null;
        JsonObject a = t.getAsJsonObject("appearance");
        return a.has("thrown-model") ? model(a.get("thrown-model").getAsString()) : null;
    }
}
