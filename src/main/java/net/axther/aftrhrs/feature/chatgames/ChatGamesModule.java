package net.axther.aftrhrs.feature.chatgames;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.chatgames.ChatGamesContent.Round;
import net.axther.aftrhrs.feature.chatgames.ChatGamesContent.Type;
import net.axther.aftrhrs.feature.chatgames.ChatGamesStore.Rec;
import net.axther.aftrhrs.net.Companion;
import net.axther.aftrhrs.net.Wire;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chat games: every so often (a random gap, only while enough players are around and not AFK) a little game is posted
 * in chat: a maths problem, trivia, an unscramble, a phrase to type first, a creature to guess from its job, or a word
 * with letters missing. The first right answer typed in chat wins Goob Coins (into the bank balance) and a little pet
 * bond XP; after {@code answer-seconds} the answer is revealed. Stats (wins, fastest, streaks, this week) are in
 * {@code data/chatgames.yml}; {@code /chatgames} (Community → Chat Games) shows them with the top players.
 *
 * <p>Answers are read in an early phase of {@code ALLOW_CHAT_MESSAGE} (always allowing), so a chat module that cancels
 * every line after sending it itself still lets the winning line count. Mute, AFK and the auth gate are checked here.
 *
 * <p>Players with the Companion ({@code chatgames} feature) also get the game as a HUD card ({@code chatgame}) and its
 * end ({@code chatgame_end}), sent at the same moments as the chat lines, which they still get. The answer only travels
 * once the game is over, and only where the chat line shows it too.
 */
public final class ChatGamesModule implements FeatherModule, FeatherCommand {
    static final String PERM = "feather.chatgames", ADMIN = "feather.chatgames.admin";
    /** The Companion feature for the HUD card. */
    private static final String FEATURE = "chatgames";
    private static final Identifier CHAT_PHASE = Identifier.fromNamespaceAndPath("aftrhrs", "chatgames");

    private static volatile ChatGamesModule active;

    /** One type's settings. */
    record TypeCfg(boolean enabled, int weight, long coins, long coinsMedium) {
    }

    /** The running game. {@code barred}: who started it with --answer (they can't win it). */
    static final class Game {
        final Round round;
        final long startNanos;
        final UUID barred;
        final long coins;
        Tasks.Task timeout;
        boolean over;

        Game(Round round, UUID barred, long coins) {
            this.round = round;
            this.barred = barred;
            this.coins = coins;
            this.startNanos = System.nanoTime();
        }
    }

    private final Feather plugin;
    final ChatGamesStore store;
    final ChatGamesContent content = new ChatGamesContent();
    final ChatGamesScreen screen;
    private final Random random = new Random();
    private volatile Game current;
    private long nextAt;
    private Tasks.Task ticker;

    // settings
    long minGapMs, maxGapMs;
    int minPlayers;
    int answerSeconds;
    int hourlyCap;
    boolean cappedCanWin;
    int bondXp;
    double mediumChance;
    long msPerChar;
    ZoneId zone = ZoneId.of("America/Toronto");
    final Map<Type, TypeCfg> types = new EnumMap<>(Type.class);
    private Sound postSound, winSound, timeoutSound;

    public ChatGamesModule(Feather plugin) {
        this.plugin = plugin;
        this.store = new ChatGamesStore(plugin.getDataFolder().resolve("data").resolve("chatgames.yml"), plugin.getLogger());
        this.screen = new ChatGamesScreen(plugin, this);
    }

    @Override
    public String name() {
        return "Chat games";
    }

    @Override
    public void enable() {
        store.load();
        reload();
        active = this;
        plugin.once("chatgames.events", () -> {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.addPhaseOrdering(CHAT_PHASE, Event.DEFAULT_PHASE);
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(CHAT_PHASE, (message, sender, params) -> {
                ChatGamesModule m = active;
                if (m != null && sender != null) m.onChat(sender, message.signedContent());
                return true;
            });
        });
        plugin.bind("chatgames", this);
        ticker = Tasks.timer(20L, 20L, this::tick);
        Hub hub = plugin.hub();
        hub.add("chatgames", Hub.Group.COMMUNITY, Hub.ORDER_CHATGAMES, "hub-social", PERM, screen::open);
        hub.addToggle(new Hub.Toggle("chatgames", Hub.TOGGLE_CHATGAMES, p -> Perms.has(p, PERM), this::shows, this::toggle));
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        // An older config.yml without the block uses the bundled one (the config's defaults).
        Yaml s = c.getConfigurationSection("chatgames");
        if (s == null) s = new Yaml();
        double min = Math.max(0, s.getDouble("interval-minutes.min", 8));
        double max = Math.max(min, s.getDouble("interval-minutes.max", 15));
        long oldMax = maxGapMs;
        minGapMs = (long) (min * 60_000);
        maxGapMs = (long) (max * 60_000);
        minPlayers = Math.max(1, s.getInt("min-players", 2));
        answerSeconds = Math.max(5, Math.min(600, s.getInt("answer-seconds", 60)));
        hourlyCap = Math.max(0, s.getInt("hourly-cap", 5));
        cappedCanWin = s.getBoolean("capped-can-win", false);
        bondXp = Math.max(0, Math.min(100, s.getInt("bond-xp", 5)));
        mediumChance = Math.max(0, Math.min(1, s.getDouble("math-medium-chance", 0.5)));
        msPerChar = Math.max(0, s.getLong("fast-type-min-ms-per-char", 60));
        types.clear();
        for (Type t : Type.values()) {
            String b = "games." + t.key + ".";
            long coins = Math.max(0, s.getLong(b + "coins", 5));
            types.put(t, new TypeCfg(s.getBoolean(b + "enabled", true), Math.max(0, s.getInt(b + "weight", 1)), coins,
                    Math.max(0, s.getLong(b + "coins-medium", coins))));
        }
        postSound = sound(s.getString("sounds.post", "block.note_block.chime 0.7 1.6"));
        winSound = sound(s.getString("sounds.win", "entity.player.levelup 0.5 1.6"));
        timeoutSound = sound(s.getString("sounds.timeout", "block.note_block.bass 0.5 0.8"));
        String z = c.getString("daily.reset-zone", "America/Toronto");
        try {
            zone = z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z.trim());
        } catch (RuntimeException e) {
            zone = ZoneId.of("America/Toronto");
        }
        content.load(plugin.loadResourceConfig("chatgames.yml"), plugin.loadResourceConfig("guide.yml"), plugin.getLogger());
        plugin.getLogger().info("Chat games: {} trivia questions, {} phrases, {} creatures, {} words.", content.trivia.size(), content.phrases.size(),
                content.creatures.size(), content.words.values().stream().mapToInt(List::size).sum());
        if (store.unreadable) store.load();
        else if (store.dirty) store.save();
        if (nextAt == 0 || oldMax != maxGapMs) armNext();
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (ticker != null) ticker.cancel();
        Game g = current;
        if (g != null && g.timeout != null) g.timeout.cancel();
        current = null;
        plugin.hub().remove("chatgames");
        store.close();
    }

    private Sound sound(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) return null;
        Sound s = Text.parseSound(spec);
        if (s == null) plugin.getLogger().warn("chatgames.sounds: bad sound '{}' (use: <sound key> [volume] [pitch]).", spec);
        return s;
    }

    // ------------------------------------------------------------------ who

    /** Whether this player sees chat games (their Settings toggle). Safe from any thread. */
    boolean shows(ServerPlayer p) {
        return !store.off.contains(p.getUUID());
    }

    /** Settings toggle and /chatgames toggle. */
    void toggle(ServerPlayer p) {
        if (!store.off.remove(p.getUUID())) store.off.add(p.getUUID());
        store.save();
        plugin.text().send(p, shows(p) ? "chatgames.toggled-on" : "chatgames.toggled-off");
        // Turned off mid-game: the card goes too (no answer: they won't see how it ends).
        Game g = current;
        if (!shows(p) && g != null && !g.over) {
            JsonObject end = Companion.message("chatgame_end");
            end.addProperty("how", "hidden");
            toCompanion(List.of(p), end);
        }
    }

    /** Players the game lines go to: past the auth gate, allowed, and not turned off. */
    private List<ServerPlayer> viewers() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : plugin.onlinePlayers()) if (!plugin.authPending(p) && Perms.has(p, PERM) && shows(p)) out.add(p);
        return out;
    }

    private void broadcast(List<ServerPlayer> to, String key, Sound sound, TagResolver... r) {
        Component line = plugin.text().msg(key, r);
        for (ServerPlayer p : to) {
            Adv.send(p, line);
            if (sound != null) Adv.sound(p, sound);
        }
    }

    long thisWeek() {
        return LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    // ------------------------------------------------------------------ the schedule

    private void armNext() {
        long span = Math.max(0, maxGapMs - minGapMs);
        nextAt = System.currentTimeMillis() + minGapMs + (span == 0 ? 0 : ThreadLocalRandom.current().nextLong(span + 1));
    }

    private void tick() {
        if (current != null || maxGapMs <= 0 || store.unreadable) return;
        if (System.currentTimeMillis() < nextAt) return;
        int count = 0;
        for (ServerPlayer p : viewers()) if (!plugin.isAfk(p)) count++;
        if (count >= minPlayers) {
            Type t = randomType();
            if (t != null) start(t, null, -1);
        }
        armNext();
    }

    private Type randomType() {
        int total = 0;
        for (Map.Entry<Type, TypeCfg> e : types.entrySet()) if (e.getValue().enabled() && content.has(e.getKey())) total += e.getValue().weight();
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Map.Entry<Type, TypeCfg> e : types.entrySet()) {
            if (!e.getValue().enabled() || !content.has(e.getKey())) continue;
            r -= e.getValue().weight();
            if (r < 0) return e.getKey();
        }
        return null;
    }

    long coinsFor(Round r) {
        TypeCfg c = types.get(r.type());
        if (c == null) return 0;
        return "medium".equals(r.difficulty()) ? c.coinsMedium() : c.coins();
    }

    private boolean bankRuns() {
        return plugin.service(Hooks.Bank.class) != null;
    }

    /** Posts a game. {@code barred}: a player who may not win it. {@code seconds} &lt;= 0: answer-seconds. */
    Game start(Type type, UUID barred, int seconds) {
        Round round = content.make(type, random, mediumChance);
        if (round == null) return null;
        Game g = new Game(round, barred, coinsFor(round));
        current = g;
        Text text = plugin.text();
        int secs = seconds > 0 ? seconds : answerSeconds;
        Component prompt = prompt(round);
        Component reward = g.coins > 0 && bankRuns()
                ? text.msg("chatgames.reward", Text.unparsed("coins", g.coins)) : Component.empty();
        Component name = text.msg("chatgames.name." + type.key);
        List<ServerPlayer> to = viewers();
        broadcast(to, "chatgames.post", postSound, Text.component("game", name),
                Text.component("prompt", prompt), Text.component("reward", reward), Text.unparsed("seconds", secs));
        // The Companion's card: exactly what the chat lines show (the prompt as the same component), never the answer.
        JsonObject card = Companion.message("chatgame");
        card.addProperty("type", type.key);
        card.add("name", Wire.text(name));
        card.add("prompt", Wire.text(prompt));
        card.addProperty("reward", g.coins > 0 && bankRuns() ? g.coins : 0);
        card.addProperty("seconds", secs);
        toCompanion(to, card);
        plugin.getLogger().info("Chat game ({}): {} = {}", type.key, PlainTextComponentSerializer.plainText().serialize(prompt), round.answer());
        g.timeout = Tasks.later(secs * 20L, () -> timeout(g));
        return g;
    }

    /** The game's second line. Fast Type sends the phrase letter by letter (no hover, no copy action). */
    private Component prompt(Round r) {
        Text text = plugin.text();
        TagResolver hint = Text.component("hint", r.hint() == null || r.hint().isEmpty() ? Component.empty()
                : text.msg(r.type() == Type.CREATURE ? "chatgames.home" : "chatgames.category",
                Text.unparsed("home", r.hint()), Text.component("category", category(r.hint()))));
        return switch (r.type()) {
            case MATH -> text.msg("chatgames.prompt.math", Text.unparsed("problem", r.shown()),
                    Text.component("level", text.msg("chatgames.level." + r.difficulty())));
            case TRIVIA -> text.msg("chatgames.prompt.trivia", Text.component("question", text.parse(r.shown())));
            case UNSCRAMBLE -> text.msg("chatgames.prompt.unscramble", Text.unparsed("word", r.shown()), hint);
            case GAPS -> text.msg("chatgames.prompt.gaps", Text.unparsed("word", r.shown()), hint);
            case CREATURE -> text.msg("chatgames.prompt.creature", Text.unparsed("clue", r.shown()), hint);
            case FAST_TYPE -> text.msg("chatgames.prompt.fast-type", Text.component("phrase", spelled(r.shown())));
        };
    }

    private Component category(String key) {
        Text text = plugin.text();
        List<String> v = text.variants("chatgames.categories." + key);
        return v.isEmpty() ? Component.text(key) : text.parse(v.get(0));
    }

    private static final TextColor SPELL_A = TextColor.fromHexString("#FFD38A");
    private static final TextColor SPELL_B = TextColor.fromHexString("#FFE2B0");

    private static Component spelled(String phrase) {
        var b = Component.text();
        for (int i = 0; i < phrase.length(); i++) b.append(Component.text(phrase.charAt(i), i % 2 == 0 ? SPELL_A : SPELL_B));
        return b.build();
    }

    private void timeout(Game g) {
        if (current != g || g.over) return;
        g.over = true;
        current = null;
        armNext();
        List<ServerPlayer> to = viewers();
        String key = g.round.type() == Type.FAST_TYPE ? "chatgames.timeout-phrase" : "chatgames.timeout";
        broadcast(to, key, timeoutSound, Text.unparsed("answer", g.round.answer()));
        toCompanion(to, ended("timeout", g, answerShown(key)));
        plugin.getLogger().info("Chat games: nobody got the {} game.", g.round.type().key);
    }

    /** /chatgames stop. */
    boolean stop() {
        Game g = current;
        if (g == null) return false;
        g.over = true;
        if (g.timeout != null) g.timeout.cancel();
        current = null;
        armNext();
        List<ServerPlayer> to = viewers();
        broadcast(to, "chatgames.stopped", null, Text.unparsed("answer", g.round.answer()));
        // The card shows the answer only if the chat line does (the bundled one doesn't).
        toCompanion(to, ended("stopped", g, answerShown("chatgames.stopped")));
        return true;
    }

    // ------------------------------------------------------------------ Companion card

    /** Whether the chat line {@code key} (any of its variants) shows {@code <answer>}; the card follows it. */
    private boolean answerShown(String key) {
        for (String line : plugin.text().variants(key)) if (line.contains("<answer>")) return true;
        return false;
    }

    /** A {@code chatgame_end} message; {@code answer} only when the matching chat line shows it. */
    private static JsonObject ended(String how, Game g, boolean answer) {
        JsonObject end = Companion.message("chatgame_end");
        end.addProperty("how", how);
        if (answer) end.addProperty("answer", g.round.answer());
        return end;
    }

    /** Sends a card message to the players in {@code to} whose mod agreed to the feature (the chat lines go anyway). */
    private void toCompanion(List<ServerPlayer> to, JsonObject message) {
        for (ServerPlayer p : to) if (Companion.has(p, FEATURE)) Companion.send(p, message);
    }

    // ------------------------------------------------------------------ answers

    /** A chat line: only a right answer is looked at further (on the server thread). */
    private void onChat(ServerPlayer p, String typed) {
        Game g = current;
        if (g == null || g.over) return;
        long at = System.nanoTime();
        if (plugin.authPending(p)) return; // the server code, see the auth module
        if (!g.round.matches(typed)) return;
        // Read now: the AFK module brings a chatting player back.
        boolean afk = plugin.isAfk(p);
        Profiles.Profile profile = plugin.profiles().get(p);
        // Muted, or /mutechat is on (Paper checked moderation().chatLockedFor): this phase may run before moderation's.
        Hooks.ChatGate gate = plugin.service(Hooks.ChatGate.class);
        boolean silenced = (profile != null && profile.muted()) || (gate != null && gate.silenced(p));
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        if (server.isSameThread()) answered(g, p, at, afk, silenced);
        else server.execute(() -> answered(g, p, at, afk, silenced));
    }

    private void answered(Game g, ServerPlayer p, long atNanos, boolean wasAfk, boolean silenced) {
        if (current != g || g.over || p.hasDisconnected() || silenced || plugin.authPending(p)) return;
        Text text = plugin.text();
        if (!Perms.has(p, PERM) || !shows(p)) return;
        if (wasAfk) {
            text.send(p, "chatgames.afk");
            return;
        }
        if (p.getUUID().equals(g.barred)) {
            text.send(p, "chatgames.starter");
            return;
        }
        long ms = Math.max(0, (atNanos - g.startNanos) / 1_000_000L);
        if (g.round.type() == Type.FAST_TYPE && ms < g.round.answer().length() * msPerChar) {
            text.send(p, "chatgames.too-fast");
            return;
        }
        Rec r = store.rec(p.getUUID(), Names.name(p));
        long now = System.currentTimeMillis();
        boolean capped = hourlyCap > 0 && r.rewardedInHour(now) >= hourlyCap;
        if (capped && !cappedCanWin) {
            text.send(p, "chatgames.capped", Text.unparsed("cap", hourlyCap),
                    Text.unparsed("time", Placeholders.duration(r.rewarded.peekFirst() + ChatGamesStore.HOUR_MS - now)));
            return;
        }
        win(g, p, r, ms, capped);
    }

    private void win(Game g, ServerPlayer p, Rec r, long ms, boolean capped) {
        g.over = true;
        if (g.timeout != null) g.timeout.cancel();
        current = null;
        armNext();
        Text text = plugin.text();
        long now = System.currentTimeMillis();
        String name = Names.name(p);

        // Stats first (saved before anything is paid).
        long week = thisWeek();
        if (r.week != week) {
            r.week = week;
            r.weekWins = 0;
        }
        r.wins++;
        r.weekWins++;
        if (r.fastestMs <= 0 || ms < r.fastestMs) r.fastestMs = Math.max(1, ms);
        UUID id = p.getUUID();
        if (id.equals(store.lastWinner)) r.streak++;
        else {
            Rec last = store.lastWinner == null ? null : store.recs.get(store.lastWinner);
            if (last != null) last.streak = 0;
            r.streak = 1;
        }
        store.lastWinner = id;
        r.bestStreak = Math.max(r.bestStreak, r.streak);
        if (!capped) r.rewarded.addLast(now);
        store.save();

        long paid = 0;
        int pets = 0;
        if (!capped) {
            Hooks.Bank bank = plugin.service(Hooks.Bank.class);
            if (g.coins > 0 && bank != null) {
                if (bank.credit(id, name, g.coins, "CHATGAME", "chat game " + g.round.type().key) >= 0) paid = g.coins;
                else plugin.getLogger().warn("Chat games: could not pay {} coins to {}.", g.coins, name);
            }
            Hooks.PetProgress petsHook = plugin.service(Hooks.PetProgress.class);
            if (bondXp > 0 && petsHook != null) {
                try {
                    pets = petsHook.addBondToFollowers(p, bondXp);
                } catch (RuntimeException e) {
                    plugin.getLogger().warn("Chat games: bond XP for {} failed: {}", name, e.toString());
                }
            }
            Hooks.Quests quests = plugin.service(Hooks.Quests.class);
            if (quests != null) quests.progress(p, "chatgame", g.round.type().key, 1);
        }

        Component reward = paid > 0 ? text.msg("chatgames.won-reward", Text.unparsed("coins", paid)) : Component.empty();
        String time = String.format(Locale.ROOT, "%.1f", ms / 1000.0);
        List<ServerPlayer> to = viewers();
        if (!to.contains(p)) to.add(p);
        Component display = Names.display(p);
        broadcast(to, "chatgames.won", winSound, Text.component("name", display), Text.unparsed("time", time),
                Text.unparsed("answer", g.round.answer()), Text.component("reward", reward));
        JsonObject end = ended("won", g, answerShown("chatgames.won"));
        end.addProperty("winner", PlainTextComponentSerializer.plainText().serialize(display));
        end.addProperty("ms", ms);
        toCompanion(to, end);
        if (r.streak >= 3) broadcast(to, "chatgames.streak", null, Text.component("name", display), Text.unparsed("streak", r.streak));
        if (capped) text.send(p, "chatgames.capped-win", Text.unparsed("cap", hourlyCap));
        else if (pets > 0) text.send(p, "chatgames.bond", Text.unparsed("amount", bondXp), Text.unparsed("count", pets));
        plugin.getLogger().info("Chat games: {} won {} in {}s{}.", name, g.round.type().key, time,
                paid > 0 ? " (+" + paid + " coins)" : capped ? " (hourly cap: no reward)" : "");
    }

    // ------------------------------------------------------------------ /chatgames

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        ServerPlayer player = sender.player();
        if (player != null && plugin.authPending(player)) return true;
        switch (sub) {
            case "start", "stop", "reload", "reset" -> {
                if (!sender.hasPermission(ADMIN)) {
                    text.send(sender, "general.no-permission");
                    return true;
                }
                admin(sender, sub, args);
                return true;
            }
            case "top" -> {
                screen.chatTop(sender);
                return true;
            }
            case "toggle" -> {
                if (player != null) toggle(player);
                else text.send(sender, "general.players-only");
                return true;
            }
            default -> {
                if (player != null) screen.open(player);
                else screen.chatTop(sender);
                return true;
            }
        }
    }

    private void admin(Sender sender, String sub, String[] args) {
        Text text = plugin.text();
        switch (sub) {
            case "start" -> {
                if (store.unreadable) {
                    text.send(sender, "chatgames.closed");
                    return;
                }
                Type type = null;
                boolean reveal = false;
                int seconds = -1;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i].toLowerCase(Locale.ROOT);
                    if (a.equals("--answer")) reveal = true;
                    else if (a.equals("--time") && i + 1 < args.length) {
                        try {
                            seconds = Math.max(3, Math.min(600, Integer.parseInt(args[++i])));
                        } catch (NumberFormatException e) {
                            text.send(sender, "chatgames.admin.usage");
                            return;
                        }
                    } else if (!a.equals("random") && !a.isEmpty()) {
                        type = Type.of(a);
                        if (type == null) {
                            text.send(sender, "chatgames.admin.unknown-type", Text.unparsed("types", typeList()));
                            return;
                        }
                    }
                }
                if (type == null) type = randomType();
                if (type == null || !content.has(type)) {
                    text.send(sender, "chatgames.admin.no-content");
                    return;
                }
                if (current != null) {
                    // A new one replaces the running game quietly.
                    Game old = current;
                    old.over = true;
                    if (old.timeout != null) old.timeout.cancel();
                    current = null;
                }
                UUID barred = reveal && sender.player() != null ? sender.player().getUUID() : null;
                Game g = start(type, barred, seconds);
                if (g == null) {
                    text.send(sender, "chatgames.admin.no-content");
                    return;
                }
                text.send(sender, "chatgames.admin.started", Text.component("game", text.msg("chatgames.name." + type.key)));
                if (reveal) text.send(sender, "chatgames.admin.answer", Text.unparsed("answer", g.round.answer()));
            }
            case "stop" -> text.send(sender, stop() ? "chatgames.admin.stopped" : "chatgames.admin.none");
            case "reload" -> {
                reload();
                text.send(sender, "chatgames.admin.reloaded", Text.unparsed("trivia", content.trivia.size()));
            }
            case "reset" -> {
                if (args.length < 2) {
                    text.send(sender, "chatgames.admin.usage");
                    return;
                }
                UUID id = resolve(args[1]);
                if (id == null) {
                    text.send(sender, "general.player-not-found", Text.unparsed("input", args[1]));
                    return;
                }
                store.recs.remove(id);
                if (id.equals(store.lastWinner)) store.lastWinner = null;
                store.save();
                text.send(sender, "chatgames.admin.reset", Text.unparsed("player", args[1]));
            }
            default -> text.send(sender, "chatgames.admin.usage");
        }
    }

    private String typeList() {
        List<String> out = new ArrayList<>();
        for (Type t : Type.values()) out.add(t.key);
        return String.join(", ", out);
    }

    private UUID resolve(String name) {
        MinecraftServer server = Feather.serverOrNull();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayerByName(name);
        if (p != null) return p.getUUID();
        for (Rec r : store.recs.values()) if (name.equalsIgnoreCase(r.name)) return r.id;
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            OfflinePlayer op = plugin.lookup().byName(name);
            return op != null && op.hasPlayedBefore() ? op.id() : null;
        }
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            out.add("top");
            out.add("toggle");
            if (admin) out.addAll(List.of("start", "stop", "reload", "reset"));
        } else if (admin && args.length == 2 && args[0].equalsIgnoreCase("start")) {
            for (Type t : Type.values()) out.add(t.key);
            out.add("--answer");
        } else if (admin && args.length >= 3 && args[0].equalsIgnoreCase("start")) {
            out.addAll(List.of("--answer", "--time"));
        } else if (admin && args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            for (ServerPlayer p : plugin.onlinePlayers()) out.add(Names.name(p));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
