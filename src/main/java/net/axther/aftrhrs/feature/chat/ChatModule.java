package net.axther.aftrhrs.feature.chat;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.LuckPermsHook;
import net.axther.aftrhrs.core.Mentions;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Chat formatting: a MiniMessage format with LuckPerms prefix/suffix and the player's nickname, color codes for
 * players with permission, clickable links and @mentions (core {@link Mentions}). Also the join, quit and first-join
 * messages and the join MOTD.
 *
 * <p>Every chat line is delivered here, in the last phase of {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}
 * ({@link FeatherEvents#CHAT_FORMAT}), so each viewer can get their own copy (mention highlights) and players who
 * /ignore the sender get none. {@code chat.unsigned: true} (default) sends the rendered line as a system message: no
 * signature reaches other clients, so chat can't be reported to Mojang and there are no "not secure" markers.
 * {@code chat.unsigned: false} keeps chat signed: each viewer gets the player's signed message with the rendered line
 * as its unsigned display content under the {@code aftrhrs:chat} chat type (which shows only that content), so
 * signatures, reporting and the message chain stay intact.
 */
public final class ChatModule implements FeatherModule {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern URL = Pattern.compile("https?://[\\w.-]+\\.[a-z]{2,}(/\\S*)?", Pattern.CASE_INSENSITIVE);
    private static final MiniMessage PLAYER_MINI = MiniMessage.builder().tags(TagResolver.builder().resolvers(StandardTags.color(),
            StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset()).build()).build();
    /** The chat type whose decoration is just the content: the whole formatted line travels as unsigned content. */
    static final ResourceKey<ChatType> FORMATTED = ResourceKey.create(Registries.CHAT_TYPE, AftrHrs.id("chat"));

    private static volatile ChatModule active;

    private final Feather plugin;
    private LuckPermsHook luckPerms;
    private boolean formatChat;
    private String format;
    private boolean mentions;
    private boolean links;
    private boolean unsigned;
    private Mentions mentionHelper;

    public ChatModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Chat";
    }

    @Override
    public void enable() {
        luckPerms = plugin.placeholders().luckPerms();
        if (luckPerms == null) luckPerms = LuckPermsHook.create();
        mentionHelper = new Mentions(plugin);
        reload();
        mentionHelper.enable();
        active = this;
        plugin.once("chat.events", () -> {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(FeatherEvents.CHAT_FORMAT, (message, sender, params) -> {
                ChatModule m = active;
                return m == null || m.onChat(message, sender, params);
            });
            FeatherEvents.JOIN_MESSAGE.register((p, current) -> {
                ChatModule m = active;
                return m == null ? current : m.joinMessage(p, current);
            });
            FeatherEvents.QUIT_MESSAGE.register((p, current) -> {
                ChatModule m = active;
                return m == null ? current : m.quitMessage(p, current);
            });
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                ChatModule m = active;
                if (m != null) m.motd(handler.player);
            });
            FeatherEvents.NAMES_CHANGED.register(p -> {
                ChatModule m = active;
                if (m != null && m.mentionHelper != null) m.mentionHelper.queueRefresh();
            });
        });
        plugin.hub().addToggle(new Hub.Toggle("mentions", Hub.TOGGLE_MENTIONS, p -> mentions, p -> !plugin.profiles().get(p).mentionsOff, p -> {
            Profiles.Profile q = plugin.profiles().get(p);
            q.mentionsOff = !q.mentionsOff;
            plugin.profiles().save(q);
            plugin.text().send(p, q.mentionsOff ? "menu.settings.mentions-toggled-off" : "menu.settings.mentions-toggled-on");
        }));
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (mentionHelper != null) mentionHelper.disable();
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        formatChat = c.getBoolean("chat.enabled", true);
        format = c.getString("chat.format", "<prefix><name><suffix> <dim>›</dim> <message>");
        mentions = c.getBoolean("chat.mentions", true);
        links = c.getBoolean("chat.clickable-links", true);
        unsigned = c.getBoolean("chat.unsigned", true);
        if (mentionHelper != null) mentionHelper.reload();
    }

    /** The running chat module, or null. */
    public static ChatModule active() {
        return active;
    }

    /** Whether mention pings are on server-wide ({@code chat.mentions}); players can still mute them in Settings. */
    public boolean mentionsEnabled() {
        return mentions;
    }

    /** Whether Feather renders chat ({@code chat.enabled}). */
    public boolean formatsChat() {
        return formatChat;
    }

    private String formatFor(ServerPlayer p) {
        if (luckPerms != null) {
            try {
                String g = plugin.getConfig().getString("chat.group-formats." + luckPerms.group(p).toLowerCase(Locale.ROOT));
                if (g != null) return g;
            } catch (Throwable ignored) {
            }
        }
        return format;
    }

    /** prefix, suffix, name and username placeholders for a player (also used by vanish's fake join/quit lines). */
    public TagResolver playerTags(ServerPlayer p) {
        return tags(plugin, luckPerms, p);
    }

    /** {@link #playerTags} for code that runs while the chat module is off. */
    public static TagResolver tags(Feather plugin, ServerPlayer p) {
        ChatModule m = active;
        return tags(plugin, m != null ? m.luckPerms : plugin.placeholders().luckPerms(), p);
    }

    private static TagResolver tags(Feather plugin, LuckPermsHook lp, ServerPlayer p) {
        Component prefix = Component.empty();
        Component suffix = Component.empty();
        if (lp != null) {
            try {
                prefix = legacyOrMini(lp.prefix(p));
                suffix = legacyOrMini(lp.suffix(p));
            } catch (Throwable ignored) {
            }
        }
        String user = Names.name(p);
        Component name = Names.display(p)
                .hoverEvent(HoverEvent.showText(plugin.text().msg("chat.name-hover", Text.unparsed("username", user))))
                .clickEvent(ClickEvent.suggestCommand("/msg " + user + " "));
        return TagResolver.resolver(Text.component("prefix", prefix), Text.component("suffix", suffix),
                Text.component("name", name), Text.unparsed("username", user));
    }

    private static Component legacyOrMini(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        if (s.indexOf('&') >= 0 || s.indexOf('§') >= 0) return AMPERSAND.deserialize(s.replace('§', '&'));
        return MiniMessage.miniMessage().deserialize(s);
    }

    /** The player's typed message, with color codes only if they're allowed to use them. */
    private Component body(ServerPlayer p, String plain) {
        Component msg;
        if (Perms.has(p, "feather.chat.color") && plain.indexOf('&') >= 0) {
            msg = AMPERSAND.deserialize(plain);
        } else if (Perms.has(p, "feather.chat.minimessage") && plain.indexOf('<') >= 0) {
            msg = PLAYER_MINI.deserialize(plain);
        } else {
            msg = Component.text(plain);
        }
        if (links) {
            msg = msg.replaceText(TextReplacementConfig.builder().match(URL).replacement((m, b) -> b
                    .clickEvent(ClickEvent.openUrl(m.group()))
                    .hoverEvent(HoverEvent.showText(plugin.text().msg("chat.link-hover")))
                    .color(TextColor.fromHexString("#8FD3FF"))).build());
        }
        return msg;
    }

    // ------------------------------------------------------------------ the chat line

    /** Delivers one chat line itself (per viewer) and stops vanilla's broadcast. */
    private boolean onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
        if (plugin.authPending(sender)) return false; // the server code, see AuthModule (taken earlier anyway)
        MinecraftServer server = Feather.server();
        String plain = message.signedContent();
        Component typed = formatChat ? body(sender, plain) : Component.text(plain);
        Mentions.Scan scan = mentions && mentionHelper != null ? mentionHelper.scan(sender, Adv.plain(typed)) : null;
        // [i] [inv] [ec]: the snapshots are taken once and go into every viewer's copy.
        ChatShare share = plugin.module(ChatShare.class);
        UnaryOperator<Component> shared = share != null ? share.prepare(sender, typed) : c -> c;
        Function<ServerPlayer, Component> bodyFor = viewer -> shared.apply(scan == null ? typed : scan.render(typed, viewer));
        Function<ServerPlayer, Component> lineFor;
        if (formatChat) {
            String fmt = formatFor(sender);
            TagResolver tags = playerTags(sender);
            lineFor = viewer -> plugin.text().parse(fmt, tags, Text.component("message", bodyFor.apply(viewer)));
        } else {
            lineFor = viewer -> Component.translatable("chat.type.text", Names.display(sender), bodyFor.apply(viewer));
        }

        List<ServerPlayer> viewers = new ArrayList<>();
        Hooks.Ignores ignores = plugin.service(Hooks.Ignores.class);
        for (ServerPlayer v : server.getPlayerList().getPlayers()) {
            if (v != sender && ignores != null && ignores.ignores(v, sender)) continue;
            viewers.add(v);
        }
        // Each mentioned player gets their own copy, with their name highlighted; everyone else shares one.
        Map<UUID, net.minecraft.network.chat.Component> own = new HashMap<>();
        if (scan != null) for (ServerPlayer t : scan.targets()) own.put(t.getUUID(), Adv.vanilla((formatChat || unsigned ? lineFor : bodyFor).apply(t)));
        Component everyoneLine = lineFor.apply(null);
        net.minecraft.network.chat.Component everyone = Adv.vanilla(unsigned || formatChat ? everyoneLine : bodyFor.apply(null));

        if (unsigned) {
            for (ServerPlayer v : viewers) v.sendSystemMessage(own.getOrDefault(v.getUUID(), everyone));
        } else {
            ChatType.Bound bound = params;
            if (formatChat) {
                try {
                    bound = ChatType.bind(FORMATTED, sender);
                } catch (RuntimeException e) {
                    // The aftrhrs:chat chat type is missing (data pack off): fall back to plain signed chat.
                    bound = params;
                    everyone = Adv.vanilla(bodyFor.apply(null));
                    own.clear();
                }
            }
            for (ServerPlayer v : viewers) {
                PlayerChatMessage copy = message.withUnsignedContent(own.getOrDefault(v.getUUID(), everyone));
                v.sendChatMessage(OutgoingChatMessage.create(copy), sender.shouldFilterMessageTo(v), bound);
            }
        }
        server.sendSystemMessage(Adv.vanilla(everyoneLine));
        if (scan != null && scan.any()) mentionHelper.ping(sender, scan, new HashSet<>(viewers));
        // Vanilla's broadcast is cancelled, so "after chat" listeners would never hear of the line: tell them here.
        ServerMessageEvents.CHAT_MESSAGE.invoker().onChatMessage(message, sender, params);
        return false;
    }

    /** Someone's nickname or vanish changed: refresh everyone's @ chat completions. */
    public void namesChanged() {
        if (mentionHelper != null) mentionHelper.queueRefresh();
    }

    // ------------------------------------------------------------------ join / quit

    private net.minecraft.network.chat.Component joinMessage(ServerPlayer p, net.minecraft.network.chat.Component current) {
        Profiles.Profile pr = plugin.profiles().get(p);
        if (pr.vanished) return null; // also with Feather's own lines off: vanilla's line would give them away
        if (!plugin.getConfig().getBoolean("chat.join-quit-messages", true)) return current;
        return Adv.vanilla(plugin.text().msg(pr.firstJoin ? "chat.first-join" : "chat.join", playerTags(p)));
    }

    private net.minecraft.network.chat.Component quitMessage(ServerPlayer p, net.minecraft.network.chat.Component current) {
        if (plugin.profiles().get(p).vanished) return null;
        if (!plugin.getConfig().getBoolean("chat.join-quit-messages", true)) return current;
        return Adv.vanilla(plugin.text().msg("chat.quit", playerTags(p)));
    }

    private void motd(ServerPlayer p) {
        if (!plugin.getConfig().getBoolean("chat.motd", true)) return;
        Tasks.later(10L, () -> {
            if (p.hasDisconnected() || active != this) return;
            int online = 0;
            for (ServerPlayer o : plugin.onlinePlayers()) if (plugin.canSee(p, o)) online++;
            plugin.text().send(p, "chat.motd", playerTags(p), Text.unparsed("online", online));
        });
    }
}
