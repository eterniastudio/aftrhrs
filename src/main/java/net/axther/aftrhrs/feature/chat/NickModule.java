package net.axther.aftrhrs.feature.chat;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.display.TabListNames;
import net.axther.aftrhrs.feature.display.TabModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nicknames: /nick opens an editor with a preview; /nick &lt;name&gt; | off; /nick &lt;player&gt; &lt;name&gt; | off for staff.
 * Accepts MiniMessage ({@code <gradient:#f9a:#9cf>Kev</gradient>}) or &-codes ({@code &d&lKev}, {@code &#ff88aa}).
 * The nickname becomes the player's display name ({@link Names}): chat, menus, mentions, the tab list
 * ({@code nick.set-tab-list-name}, or the tab module's list name) and the nametags show it.
 */
public final class NickModule implements FeatherModule, FeatherCommand {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)&([0-9a-f]|#[0-9a-f]{6})");
    private static final Pattern LEGACY_FORMAT = Pattern.compile("(?i)&[l-o]");
    private static final Pattern LEGACY_MAGIC = Pattern.compile("(?i)&k");
    /**
     * Stored nicknames are read back with the tags the editor can ever allow (colors, decorations, gradients), never
     * click, hover, insertion, fonts or line breaks.
     */
    private static final MiniMessage STORED = MiniMessage.builder().tags(TagResolver.builder().resolvers(StandardTags.color(),
            StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.transition(),
            StandardTags.reset()).build()).build();

    private static volatile NickModule active;

    private final Feather plugin;
    private Pattern allowed;
    private int minLength;
    private int maxLength;
    private boolean setTabName;
    private List<Pattern> blacklist = List.of();

    public NickModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Nicknames";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        TabListNames.init();
        plugin.bind("nick", this);
        plugin.once("nick.events", () -> {
            // Before the join broadcast (so "joined the game" shows the nickname), right after the profile loaded.
            ServerPlayConnectionEvents.INIT.register((handler, server) -> {
                NickModule m = active;
                if (m != null) m.loaded(handler.player);
            });
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                NickModule m = active;
                if (m != null && m.plugin.profiles().get(handler.player).nickRaw != null) m.plugin.playerNamesChanged(handler.player);
            });
        });
        plugin.hub().add("nick", Hub.Group.STYLE, Hub.ORDER_NICK, "hub-nick", "feather.nick", p -> openEditor(p, null, null));
        // Players already online (a module started late) get their nickname back.
        for (ServerPlayer p : plugin.onlinePlayers()) joined(p);
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        plugin.hub().remove("nick");
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        allowed = Pattern.compile(c.getString("nick.allowed-characters", "^[A-Za-z0-9_]+$"));
        minLength = c.getInt("nick.min-length", 3);
        maxLength = c.getInt("nick.max-length", 16);
        setTabName = c.getBoolean("nick.set-tab-list-name", true);
        List<Pattern> bl = new ArrayList<>();
        for (String s : c.getStringList("nick.blacklist")) {
            try {
                bl.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            } catch (RuntimeException e) {
                plugin.getLogger().warn("nick.blacklist: bad pattern {}", s);
            }
        }
        blacklist = bl;
    }

    // ------------------------------------------------------------------ parsing

    /** Result of parsing a requested nickname: either a component or an error message. */
    private record Parsed(Component name, String plain, Component error) {
    }

    private Parsed parse(ServerPlayer who, String raw) {
        return parse(who, who, raw);
    }

    /**
     * {@code who}'s formatting rights; the name must be free for {@code owner} (the staff member and the target
     * differ for /nick <player> <nick>: checking against the staff member refused a target's own name and let the
     * target take the staff member's).
     */
    private Parsed parse(ServerPlayer who, ServerPlayer owner, String raw) {
        Text text = plugin.text();
        raw = raw == null ? "" : raw.trim();
        boolean color = Perms.has(who, "feather.nick.color");
        boolean format = Perms.has(who, "feather.nick.format");
        boolean gradient = Perms.has(who, "feather.nick.gradient");
        boolean magic = Perms.has(who, "feather.nick.magic");
        boolean any = Perms.has(who, "feather.nick.anycharacters");
        Component name;
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            String amp = raw.replace('§', '&');
            if (!color && LEGACY_COLOR.matcher(amp).find()) return error(text.msg("nick.no-color"));
            if (!format && LEGACY_FORMAT.matcher(amp).find()) return error(text.msg("nick.no-format"));
            if (!magic && LEGACY_MAGIC.matcher(amp).find()) return error(text.msg("nick.no-magic"));
            name = AMPERSAND.deserialize(amp);
        } else {
            TagResolver.Builder tags = TagResolver.builder().resolver(StandardTags.reset());
            if (color) tags.resolvers(StandardTags.color());
            if (format) {
                tags.resolvers(StandardTags.decorations(TextDecoration.BOLD), StandardTags.decorations(TextDecoration.ITALIC),
                        StandardTags.decorations(TextDecoration.UNDERLINED), StandardTags.decorations(TextDecoration.STRIKETHROUGH));
            }
            if (magic) tags.resolver(StandardTags.decorations(TextDecoration.OBFUSCATED));
            if (gradient) tags.resolvers(StandardTags.gradient(), StandardTags.rainbow(), StandardTags.transition());
            name = MiniMessage.builder().tags(tags.build()).build().deserialize(raw);
        }
        String plain = Adv.plain(name);
        if (plain.indexOf('<') >= 0 && !any) return error(text.msg("nick.bad-tags"));
        if (plain.length() < minLength || plain.length() > maxLength) {
            return error(text.msg("nick.bad-length", Text.unparsed("min", minLength), Text.unparsed("max", maxLength)));
        }
        if (!allowed.matcher(plain).matches() && !any) {
            return error(text.msg(plain.contains("<") ? "nick.bad-tags" : "nick.bad-characters"));
        }
        for (Pattern p : blacklist) {
            if (p.matcher(plain).find() && !Perms.has(who, "feather.nick.blacklist.bypass")) return error(text.msg("nick.blacklisted"));
        }
        if (!plain.equalsIgnoreCase(Names.name(owner)) && !Perms.has(who, "feather.nick.impersonate")) {
            var other = plugin.lookup().byName(plain);
            if (other != null && !other.id().equals(owner.getUUID())) return error(text.msg("nick.taken-real"));
            // Also taken while its owner is offline: /mail, /pay and the like find offline players by nickname.
            UUID holder = plugin.lookup().nickOwner(plain);
            if (holder != null && !holder.equals(owner.getUUID())) return error(text.msg("nick.taken"));
            for (ServerPlayer online : plugin.onlinePlayers()) {
                if (online.equals(owner)) continue;
                if (Names.plain(online).equalsIgnoreCase(plain) || Names.name(online).equalsIgnoreCase(plain)) return error(text.msg("nick.taken"));
            }
        }
        return new Parsed(name, plain, null);
    }

    private static Parsed error(Component message) {
        return new Parsed(null, null, message);
    }

    // ------------------------------------------------------------------ applying

    /** Applies (or with {@code raw == null} clears) a nickname and saves it. */
    void apply(ServerPlayer target, String raw, Component name) {
        Profiles.Profile profile = plugin.profiles().get(target);
        profile.nickRaw = raw;
        plugin.profiles().save(profile);
        show(target, name);
    }

    private void show(ServerPlayer target, Component name) {
        Names.set(target, name);
        // The tab module draws its own list name (with <name>); without it the nickname goes there directly.
        if (plugin.module(TabModule.class) == null && setTabName) TabListNames.set(target, name);
        plugin.playerNamesChanged(target); // lookups, @ completions, tab list and nametags
    }

    private void joined(ServerPlayer player) {
        if (loaded(player)) plugin.playerNamesChanged(player);
    }

    /** Puts the saved nickname on a player; true when they have one. */
    private boolean loaded(ServerPlayer player) {
        Profiles.Profile profile = plugin.profiles().get(player);
        if (profile.nickRaw == null) return false;
        Component name = parseTrusted(profile.nickRaw).name();
        Names.set(player, name);
        if (plugin.module(TabModule.class) == null && setTabName) TabListNames.set(player, name);
        return true;
    }

    /** Re-parses a stored nickname without permission or uniqueness checks (it was valid when set). */
    private static Parsed parseTrusted(String raw) {
        Component name = raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0
                ? AMPERSAND.deserialize(raw.replace('§', '&'))
                : STORED.deserialize(raw);
        return new Parsed(name, Adv.plain(name), null);
    }

    /**
     * What to save for a nickname that passed {@link #parse}: what was typed, unless tags in it were shown as plain
     * text (allowed with feather.nick.anycharacters); then the escaped form, so it reads back the same after a rejoin.
     */
    private static String stored(String raw, Parsed parsed) {
        boolean legacy = raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0;
        return !legacy && parsed.plain().indexOf('<') >= 0 ? MiniMessage.miniMessage().serialize(parsed.name()) : raw;
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        // "/nick <player> <nick>" only when the first word is an online player; otherwise a nickname with spaces.
        boolean forOther = args.length >= 2 && plugin.findPlayer(sender, args[0]) != null;
        if (args.length >= 2 && !forOther && sender.isPlayer()) args = new String[]{String.join(" ", args)};
        if (args.length >= 2) {
            if (!sender.hasPermission("feather.nick.others")) {
                text.send(sender, "general.no-permission");
                return true;
            }
            ServerPlayer target = plugin.findPlayer(sender, args[0]);
            if (target == null) {
                plugin.notFound(sender, args[0]);
                return true;
            }
            String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            if (raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("reset")) {
                apply(target, null, null);
                text.send(sender, "nick.reset-other", Text.unparsed("name", Names.name(target)));
                text.send(target, "nick.reset");
                return true;
            }
            // Staff set others' nicks with their own formatting rights.
            Parsed parsed = sender.isPlayer() ? parse(sender.player(), target, raw) : parseTrusted(raw);
            if (parsed.error() != null) {
                sender.sendMessage(parsed.error());
                return true;
            }
            apply(target, stored(raw, parsed), parsed.name());
            text.send(sender, "nick.set-other", Text.unparsed("name", Names.name(target)), Text.component("nick", parsed.name()));
            text.send(target, "nick.set", Text.component("nick", parsed.name()));
            text.sound(target, "nick-set");
            return true;
        }
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (args.length == 0) {
            openEditor(player, null, null);
            return true;
        }
        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("reset")) {
            apply(player, null, null);
            text.send(player, "nick.reset");
            text.sound(player, "click");
            return true;
        }
        Parsed parsed = parse(player, args[0]);
        if (parsed.error() != null) {
            Adv.send(player, parsed.error());
            text.sound(player, "error");
            return true;
        }
        if (parsed.plain().equals(Names.name(player)) && parsed.name().equals(Component.text(Names.name(player)))) {
            apply(player, null, null);
            text.send(player, "nick.reset");
            return true;
        }
        apply(player, stored(args[0], parsed), parsed.name());
        text.send(player, "nick.set", Text.component("nick", parsed.name()));
        text.sound(player, "nick-set");
        plugin.advance(player, "event", "nick");
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("off"));
            if (sender.hasPermission("feather.nick.others")) out.addAll(plugin.completePlayers(sender, args[0]));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------------ editor

    void openEditor(ServerPlayer player, String initial, Component error) {
        Text text = plugin.text();
        Profiles.Profile profile = plugin.profiles().get(player);
        String start = initial != null ? initial : profile.nickRaw != null ? profile.nickRaw : Names.name(player);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.nick.title"));
        menu.text(text.msg(profile.nickRaw == null ? "menu.nick.current-none" : "menu.nick.current",
                Text.component("nick", Names.display(player)), Text.unparsed("name", Names.name(player))));
        menu.text(text.msg(tipsKey(player)), 300);
        if (error != null) menu.text(error);
        menu.input(Dialogs.Input.text("nick", text.msg("menu.nick.input-label")).initial(start).maxLength(256).width(300).build());
        menu.button(text.msg("menu.nick.preview"), text.msg("menu.nick.preview-tooltip"), 148, (p, view) -> {
            String raw = view.getText("nick");
            raw = raw == null ? "" : raw.trim();
            Parsed parsed = parse(p, raw);
            if (parsed.error() != null) {
                openEditor(p, raw, parsed.error());
                text.sound(p, "error");
                return;
            }
            openPreview(p, raw, parsed);
        });
        if (profile.nickRaw != null) {
            menu.button(text.msg("menu.nick.reset"), text.msg("menu.nick.reset-tooltip"), 148, p -> {
                apply(p, null, null);
                text.send(p, "nick.reset");
                text.sound(p, "click");
            });
        }
        menu.columns(2);
        plugin.exitButton(menu, player);
        menu.open(player);
        text.sound(player, "menu-open");
    }

    private String tipsKey(ServerPlayer p) {
        if (Perms.has(p, "feather.nick.gradient")) return "menu.nick.tips-gradient";
        if (Perms.has(p, "feather.nick.color")) return "menu.nick.tips-color";
        return "menu.nick.tips-plain";
    }

    private void openPreview(ServerPlayer player, String raw, Parsed parsed) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.nick.preview-title"));
        menu.text(text.msg("menu.nick.preview-body", Text.component("nick", parsed.name())));
        menu.text(text.msg("menu.nick.preview-chat", Text.component("nick", parsed.name())));
        menu.button(text.msg("menu.nick.apply"), null, 148, p -> {
            Parsed again = parse(p, raw); // re-check: someone may have taken it meanwhile
            if (again.error() != null) {
                openEditor(p, raw, again.error());
                text.sound(p, "error");
                return;
            }
            apply(p, stored(raw, again), again.name());
            text.send(p, "nick.set", Text.component("nick", again.name()));
            text.sound(p, "nick-set");
            plugin.advance(p, "event", "nick");
        });
        menu.button(text.msg("menu.nick.edit"), null, 148, p -> openEditor(p, raw, null));
        menu.columns(2);
        menu.open(player);
        text.sound(player, "click");
    }
}
