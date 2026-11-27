package net.axther.aftrhrs.core;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Adventure ⇄ vanilla bridge. Feather's code speaks Adventure components and MiniMessage; vanilla wants
 * {@link net.minecraft.network.chat.Component}. Conversion goes through JSON (same wire format both sides).
 */
public final class Adv {
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.builder()
            .editOptions(o -> o.values(JSONOptions.byDataVersion().at(4556)))
            .build();

    private Adv() {
    }

    private static RegistryAccess registries() {
        var server = Feather.serverOrNull();
        return server != null ? server.registryAccess() : RegistryAccess.EMPTY;
    }

    public static net.minecraft.network.chat.Component vanilla(Component adventure) {
        if (adventure == null) return net.minecraft.network.chat.Component.empty();
        JsonElement json = GSON.serializeToTree(adventure);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries());
        return ComponentSerialization.CODEC.parse(ops, json).result()
                .orElseGet(() -> net.minecraft.network.chat.Component.literal(plain(adventure)));
    }

    public static Component adventure(net.minecraft.network.chat.Component vanilla) {
        if (vanilla == null) return Component.empty();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries());
        return ComponentSerialization.CODEC.encodeStart(ops, vanilla).result()
                .map(GSON::deserializeFromTree)
                .orElseGet(() -> Component.text(vanilla.getString()));
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** An Adventure audience for a player: chat, action bar, titles, sounds, boss bars (Feather's calls work as-is). */
    public static Audience of(ServerPlayer player) {
        return new PlayerAudience(player);
    }

    public static Audience of(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        if (p != null) return of(p);
        return new Audience() {
            @Override
            public void sendMessage(@NotNull Component message) {
                source.sendSystemMessage(vanilla(message));
            }
        };
    }

    public static void send(ServerPlayer player, Component message) {
        if (player != null) player.sendSystemMessage(vanilla(message));
    }

    public static void actionBar(ServerPlayer player, Component message) {
        if (player != null) player.connection.send(new ClientboundSetActionBarTextPacket(vanilla(message)));
    }

    public static void sound(ServerPlayer player, Sound sound) {
        if (player == null || sound == null) return;
        Identifier id = Identifier.parse(sound.name().asString());
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.get(id)
                .<Holder<SoundEvent>>map(h -> h)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
        SoundSource src = source(sound.source());
        player.connection.send(new ClientboundSoundPacket(holder, src, player.getX(), player.getY(), player.getZ(),
                sound.volume(), sound.pitch(), player.getRandom().nextLong()));
    }

    public static SoundSource source(Sound.Source s) {
        if (s == null) return SoundSource.MASTER;
        return switch (s) {
            case MUSIC -> SoundSource.MUSIC;
            case RECORD -> SoundSource.RECORDS;
            case WEATHER -> SoundSource.WEATHER;
            case BLOCK -> SoundSource.BLOCKS;
            case HOSTILE -> SoundSource.HOSTILE;
            case NEUTRAL -> SoundSource.NEUTRAL;
            case PLAYER -> SoundSource.PLAYERS;
            case AMBIENT -> SoundSource.AMBIENT;
            case VOICE -> SoundSource.VOICE;
            case UI -> SoundSource.UI;
            default -> SoundSource.MASTER;
        };
    }

    /** Plays a sound by key ("minecraft:entity.experience_orb.pickup" or "entity.experience_orb.pickup"). */
    public static void sound(ServerPlayer player, String key, float volume, float pitch) {
        if (key == null || key.isBlank()) return;
        String k = key.toLowerCase(Locale.ROOT);
        sound(player, Sound.sound(net.kyori.adventure.key.Key.key(k), Sound.Source.MASTER, volume, pitch));
    }

    private record PlayerAudience(ServerPlayer player) implements Audience {
        @Override
        public void sendMessage(@NotNull Component message) {
            send(player, message);
        }

        @Override
        @SuppressWarnings({"deprecation", "UnstableApiUsage"})
        public void sendMessage(@NotNull Identity source, @NotNull Component message, @NotNull MessageType type) {
            send(player, message);
        }

        @Override
        public void sendActionBar(@NotNull Component message) {
            actionBar(player, message);
        }

        @Override
        public void playSound(@NotNull Sound sound) {
            sound(player, sound);
        }

        @Override
        public void playSound(@NotNull Sound sound, Sound.@NotNull Emitter emitter) {
            sound(player, sound);
        }

        @Override
        public void playSound(@NotNull Sound sound, double x, double y, double z) {
            sound(player, sound);
        }

        @Override
        public void showTitle(@NotNull Title title) {
            Title.Times t = title.times();
            if (t != null) player.connection.send(new ClientboundSetTitlesAnimationPacket(
                    (int) (t.fadeIn().toMillis() / 50), (int) (t.stay().toMillis() / 50), (int) (t.fadeOut().toMillis() / 50)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(vanilla(title.subtitle())));
            player.connection.send(new ClientboundSetTitleTextPacket(vanilla(title.title())));
        }

        @Override
        public <T> void sendTitlePart(@NotNull TitlePart<T> part, @NotNull T value) {
            if (part == TitlePart.TITLE) player.connection.send(new ClientboundSetTitleTextPacket(vanilla((Component) value)));
            else if (part == TitlePart.SUBTITLE) player.connection.send(new ClientboundSetSubtitleTextPacket(vanilla((Component) value)));
            else if (part == TitlePart.TIMES) {
                Title.Times t = (Title.Times) value;
                player.connection.send(new ClientboundSetTitlesAnimationPacket(
                        (int) (t.fadeIn().toMillis() / 50), (int) (t.stay().toMillis() / 50), (int) (t.fadeOut().toMillis() / 50)));
            }
        }

        @Override
        public void showBossBar(@NotNull BossBar bar) {
            BossBars.show(player, bar);
        }

        @Override
        public void hideBossBar(@NotNull BossBar bar) {
            BossBars.hide(player, bar);
        }
    }
}
