package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.BossBars;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bank robberies. Hitting a bank chest (left-click, mining, or {@code /bank rob} beside it) asks the player to confirm;
 * then the robbery runs in two stages:
 *
 * <ol>
 *   <li><b>Cracking</b>: the vault's locks ({@link Minigames}) one after another, while staying within
 *   {@code vault-radius} of the chest. A failed lock raises the alarm (more, faster police) and can be retried after a
 *   short wait; too many failures, or walking off, ends the robbery.</li>
 *   <li><b>Escape</b>: get {@code escape-distance} blocks from the bank, then go {@code lose-police-seconds} with no
 *   officer within {@code lose-police-radius}. Success pays a share of the vault reserve (never anyone's deposits) as
 *   physical coins.</li>
 * </ol>
 *
 * Dying, running out of time, quitting (killed where they stand by default) or a staff stop ends it as a failure.
 * Police ({@link Police}) come in waves the whole time; the robber is under the {@link TravelLock} the whole time.
 */
final class Robberies {
    enum Stage {CRACKING, ESCAPE}

    /** One robbery in progress. */
    final class Robbery {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final UUID robber;
        final String robberName;
        final BankStore.Pos vault;
        final Loc vaultCenter;
        final long started;
        final long deadline;
        final List<String> games;
        final boolean bypass;
        final boolean hadFlight;
        Stage stage = Stage.CRACKING;
        int gameIndex;
        Minigames.Game game;
        long retryAt;
        int failures;
        int alarm;
        int outsideSeconds;
        long nextWave;
        long nextHorn;
        boolean farEnough;
        long clearSince;
        boolean ended;
        /** The robber died this tick; {@link #end} runs next tick and nothing may count as an escape meanwhile. */
        boolean dying;
        final Set<UUID> police = new HashSet<>();
        /** Deputies who answered the call (/bank defend), with where they were before. */
        final Map<UUID, Loc> deputies = new java.util.LinkedHashMap<>();
        /** Where the robber died (when caught). */
        Loc fell;
        /** When police or a deputy last hurt the robber (epoch ms; 0: never). */
        long lawHitAt;
        BossBar bar;

        Robbery(ServerPlayer p, BankStore.Pos vault, Loc center) {
            this.robber = p.getUUID();
            this.robberName = Names.name(p);
            this.vault = vault;
            this.vaultCenter = center;
            this.started = System.currentTimeMillis();
            this.deadline = started + maxMinutes * 60_000L;
            this.games = new ArrayList<>(gameOrder);
            this.bypass = Perms.has(p, BankModule.BYPASS);
            this.hadFlight = p.getAbilities().mayfly;
        }
    }

    final BankModule bank;
    final Minigames minigames;
    final Police police;
    final TravelLock lock;
    private Robbery current;
    private long tick;
    private final Map<UUID, Long> lastAttempt = new HashMap<>();
    /** The robber being killed by the quit penalty right now (graves leave that death's drops on the ground). */
    private UUID quitKilling;

    // settings (robbery: in bank.yml)
    boolean enabled = true;
    int minOthers = 2;
    long serverCooldownMs;
    long playerCooldownMs;
    long failedCooldownMs;
    int maxMinutes = 10;
    boolean confirm = true;
    double vaultRadius = 6;
    int leaveGrace = 3;
    List<String> gameOrder = List.of("dial", "wires", "memory");
    int maxFailures = 3;
    int retrySeconds = 5;
    double escapeDistance = 150;
    int loseSeconds = 20;
    double loseRadius = 24;
    double lootPercent = 25;
    long lootMin = 100;
    long lootMax = 2500;
    String quitPenalty = "kill";
    boolean announce = true;
    int firstWaveSeconds = 8;
    int waveSeconds = 20;
    int perWave = 2;
    int perAlarm = 1;
    int maxAlive = 8;
    double spawnMin = 8;
    double spawnMax = 16;
    double escapeSpawnMin = 28;
    double escapeSpawnMax = 40;
    double despawnDistance = 80;
    String hornSound = "minecraft:event.raid.horn";
    boolean deputiesOn = true;
    double deputyRewardPercent = 20;
    int maxDeputies = 8;
    double deputyPresentRadius = 96;
    boolean deputyReturn = true;

    Robberies(BankModule bank) {
        this.bank = bank;
        this.minigames = new Minigames(this);
        this.police = new Police(this);
        this.lock = new TravelLock(this);
    }

    /** The running bank's robberies, or null. */
    private static Robberies live() {
        BankModule m = BankModule.active;
        return m == null ? null : m.robberies;
    }

    void reload(Yaml c) {
        if (c == null) return;
        enabled = c.getBoolean("enabled", true);
        minOthers = Math.max(0, c.getInt("min-other-players", 2));
        serverCooldownMs = Math.max(0, c.getLong("server-cooldown-minutes", 120)) * 60_000L;
        playerCooldownMs = Math.max(0, c.getLong("player-cooldown-hours", 24)) * 3_600_000L;
        failedCooldownMs = Math.max(0, c.getLong("failed-cooldown-hours", 48)) * 3_600_000L;
        maxMinutes = Math.max(1, c.getInt("max-minutes", 10));
        confirm = c.getBoolean("confirm", true);
        vaultRadius = Math.max(2, c.getDouble("vault-radius", 6));
        leaveGrace = Math.max(0, c.getInt("leave-grace-seconds", 3));
        List<String> games = new ArrayList<>();
        for (String s : c.getStringList("minigames")) {
            String t = s.toLowerCase(Locale.ROOT).trim();
            if (t.equals("dial") || t.equals("wires") || t.equals("memory")) games.add(t);
        }
        gameOrder = games.isEmpty() ? List.of("dial", "wires", "memory") : games;
        maxFailures = Math.max(1, c.getInt("max-failures", 3));
        retrySeconds = Math.max(1, c.getInt("retry-seconds", 5));
        escapeDistance = Math.max(10, c.getDouble("escape-distance", 150));
        loseSeconds = Math.max(1, c.getInt("lose-police-seconds", 20));
        loseRadius = Math.max(4, c.getDouble("lose-police-radius", 24));
        lootPercent = Math.max(0, Math.min(100, c.getDouble("loot.percent", 25)));
        lootMin = Math.max(0, c.getLong("loot.min", 100));
        lootMax = Math.max(lootMin, c.getLong("loot.max", 2500));
        quitPenalty = c.getString("quit-penalty", "kill").toLowerCase(Locale.ROOT);
        announce = c.getBoolean("announce", true);
        hornSound = c.getString("alarm-sound", "minecraft:event.raid.horn");
        Yaml dc = c.getConfigurationSection("deputies");
        deputiesOn = dc == null || dc.getBoolean("enabled", true);
        deputyRewardPercent = dc == null ? 20 : Math.max(0, Math.min(100, dc.getDouble("reward-percent", 20)));
        maxDeputies = dc == null ? 8 : Math.max(1, Math.min(64, dc.getInt("max", 8)));
        deputyPresentRadius = dc == null ? 96 : Math.max(8, dc.getDouble("present-radius", 96));
        deputyReturn = dc == null || dc.getBoolean("return-after", true);
        Yaml pc = c.getConfigurationSection("police");
        if (pc != null) {
            firstWaveSeconds = Math.max(0, pc.getInt("first-wave-seconds", 8));
            waveSeconds = Math.max(3, pc.getInt("wave-seconds", 20));
            perWave = Math.max(0, pc.getInt("per-wave", 2));
            perAlarm = Math.max(0, pc.getInt("per-alarm", 1));
            maxAlive = Math.max(0, pc.getInt("max-alive", 8));
            spawnMin = Math.max(2, pc.getDouble("spawn-radius.min", 8));
            spawnMax = Math.max(spawnMin, pc.getDouble("spawn-radius.max", 16));
            escapeSpawnMin = Math.max(2, pc.getDouble("escape-spawn-radius.min", 28));
            escapeSpawnMax = Math.max(escapeSpawnMin, pc.getDouble("escape-spawn-radius.max", 40));
            despawnDistance = Math.max(16, pc.getDouble("despawn-distance", 80));
        }
        police.reload(pc);
        minigames.reload(c.getConfigurationSection("games"));
        lock.reload(c.getConfigurationSection("travel"));
    }

    void enable() {
        police.enable();
    }

    void disable() {
        if (current != null) end(current, false, "bank.robbery.end.server-stop", false);
        police.sweep(null);
    }

    /** Fabric listeners for robberies, the police and the travel lock; each returns early without a running bank. */
    static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Robberies r = live();
            if (r != null) r.tick();
        });
        // Police only hurt the robber; police or a deputy hurting the robber is remembered (a death soon after is "caught").
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Robberies r = live();
            return r == null || Police.allowDamage(r, entity, source.getEntity());
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            Robberies r = live();
            Robbery cur = r == null ? null : r.current;
            if (cur == null || cur.ended || !cur.robber.equals(entity.getUUID())) return;
            if (r.byLaw(cur, source.getEntity())) cur.lawHitAt = System.currentTimeMillis();
        });
        FeatherEvents.PLAYER_DEATH.register((p, source) -> {
            Robberies r = live();
            if (r != null) r.onDeath(p, source.getEntity());
        });
        // Nobody trades with, leashes or rides an officer.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                live() != null && Police.isPolice(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> Police.onLoad(live(), entity));
        // Paper-era officers in a migrated world (never meant to be saved): removed.
        Legacy.converter((level, entity, bukkit) -> {
            if (!bukkit.contains(Police.TAG)) return false;
            entity.discard();
            return true;
        });
        // The travel lock.
        FeatherEvents.PLAYER_TELEPORT.register((p, from, to, cause) -> {
            Robberies r = live();
            return r == null || r.lock.allowTeleport(p, from, to, cause);
        });
        FeatherEvents.COMMAND.register((p, typed) -> {
            Robberies r = live();
            return r == null || r.lock.allowCommand(p, typed);
        });
        FeatherEvents.PLAYER_MOVE.register((p, from, to) -> {
            Robberies r = live();
            return r == null || r.lock.allowMove(p, from, to);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            Robberies r = live();
            if (r == null || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            return r.lock.allowUse(p, p.getItemInHand(hand)) ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            Robberies r = live();
            if (r == null || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            return r.lock.allowAttack(p) ? InteractionResult.PASS : InteractionResult.FAIL;
        });
    }

    Robbery current() {
        return current;
    }

    boolean isRobber(ServerPlayer p) {
        return current != null && !current.ended && current.robber.equals(p.getUUID());
    }

    boolean quitKill(ServerPlayer p) {
        return p.getUUID().equals(quitKilling);
    }

    boolean lockedId(UUID id) {
        Robbery r = current;
        return r != null && !r.ended && r.robber.equals(id) && !r.bypass;
    }

    boolean locked(ServerPlayer p) {
        return lockedId(p.getUUID());
    }

    ServerPlayer robberPlayer() {
        Robbery r = current;
        if (r == null || r.ended) return null;
        ServerPlayer p = bank.plugin.player(r.robber);
        return p != null && !p.hasDisconnected() ? p : null;
    }

    int stageNumber() {
        return current == null ? 0 : current.gameIndex + 1;
    }

    int stageCount() {
        return current == null ? 0 : current.games.size();
    }

    boolean isLiveGame(Minigames.Game g, ServerPlayer p) {
        Robbery r = current;
        return r != null && !r.ended && r.stage == Stage.CRACKING && r.game == g && r.robber.equals(p.getUUID());
    }

    private Text text() {
        return bank.text();
    }

    private TagResolver n(String k, long v) {
        return bank.n(k, v);
    }

    void click(ServerPlayer p) {
        Adv.sound(p, "minecraft:block.dispenser.dispense", 0.6f, 1.6f);
    }

    private static boolean survival(ServerPlayer p) {
        return p.gameMode() == GameType.SURVIVAL || p.gameMode() == GameType.ADVENTURE;
    }

    private static void closeDialog(ServerPlayer p) {
        if (p != null && !p.hasDisconnected()) p.connection.send(ClientboundClearDialogPacket.INSTANCE);
    }

    // ------------------------------------------------------------------ starting

    /** A player hit a bank chest. */
    void attempt(ServerPlayer p, ServerLevel level, BlockPos chest) {
        long now = System.currentTimeMillis();
        Long last = lastAttempt.get(p.getUUID());
        if (last != null && now - last < 1500) return;
        lastAttempt.put(p.getUUID(), now);
        if (p.isCreative() || p.isSpectator()) return;
        if (isRobber(p)) {
            reopen(p);
            return;
        }
        if (!canStart(p, true)) return;
        BankStore.Pos pos = BankStore.Pos.of(level, chest);
        if (!confirm) {
            start(p, pos);
            return;
        }
        Dialogs.Menu m = bank.dialogs.menu(text().msg("bank.robbery.confirm-title"));
        m.text(text().msg("bank.robbery.confirm-body", Text.unparsed("games", gameOrder.size()), Text.unparsed("minutes", maxMinutes),
                Text.unparsed("distance", (long) escapeDistance)), 300);
        m.button(text().msg("bank.robbery.confirm-yes"), null, 150, pl -> {
            if (!canStart(pl, true)) return;
            Loc c = pos.center();
            if (c == null || !bank.isBankBlock(c.level(), pos.blockPos()) || c.level() != pl.level()
                    || c.distanceSquared(Loc.of(pl)) > vaultRadius * vaultRadius) {
                bank.error(pl, "bank.too-far");
                return;
            }
            start(pl, pos);
        });
        m.button(text().msg("bank.robbery.confirm-no"), null, 150, pl -> {
        });
        m.columns(2);
        m.open(p);
        text().sound(p, "error");
    }

    private boolean canStart(ServerPlayer p, boolean tell) {
        String key = null;
        TagResolver[] r = {};
        long now = System.currentTimeMillis();
        int others = 0;
        for (ServerPlayer o : bank.plugin.onlinePlayers()) if (o != p && bank.plugin.canSee(p, o)) others++;
        Long mine = bank.store.robberyCooldowns.get(p.getUUID());
        if (bank.store.unreadable) key = "bank.closed";
        else if (!enabled) key = "bank.robbery.disabled";
        else if (current != null) key = "bank.robbery.busy";
        else if (!Perms.has(p, "feather.bank.robbery")) key = "general.no-permission";
        else if (p.gameMode() != GameType.SURVIVAL) key = "bank.robbery.not-survival";
        else if (others < minOthers) {
            key = "bank.robbery.not-enough-players";
            r = new TagResolver[]{Text.unparsed("needed", minOthers), Text.unparsed("online", others)};
        } else if (now - bank.store.lastRobbery < serverCooldownMs) {
            key = "bank.robbery.server-cooldown";
            r = new TagResolver[]{Text.unparsed("time", BankModule.until(bank.store.lastRobbery + serverCooldownMs))};
        } else if (mine != null && mine > now) {
            key = "bank.robbery.cooldown";
            r = new TagResolver[]{Text.unparsed("time", BankModule.until(mine))};
        }
        if (key == null) return true;
        if (tell) bank.error(p, key, r);
        return false;
    }

    private void start(ServerPlayer p, BankStore.Pos pos) {
        Loc center = pos.center();
        if (center == null) return;
        Robbery r = new Robbery(p, pos, center);
        current = r;
        long now = System.currentTimeMillis();
        bank.store.lastRobbery = now;
        bank.store.robberyCooldowns.put(p.getUUID(), now + playerCooldownMs);
        bank.store.robberies++;
        bank.store.log("ROBBERY-START " + r.robberName + " " + p.getUUID() + " at " + pos.pretty() + " id=" + r.id);
        bank.store.save();
        bank.sessions.remove(p.getUUID());
        if (!r.bypass) lock.engage(p);
        r.nextWave = now + firstWaveSeconds * 1000L;
        r.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        updateBar(r, p);
        BossBars.show(p, r.bar);
        if (announce) broadcast("bank.robbery.announce-start", Text.component("player", Names.display(p)));
        text().send(p, "bank.robbery.started", Text.unparsed("radius", (long) vaultRadius));
        callDeputies(r, p);
        horn(r);
        r.nextHorn = now + 10_000L;
        bank.plugin.advance(p, "event", "bank-robbery");
        openGame(r, p);
    }

    // ------------------------------------------------------------------ deputies

    /** Online, non-AFK players other than the robber get a clickable "/bank defend" line. */
    private void callDeputies(Robbery r, ServerPlayer robber) {
        if (!deputiesOn) return;
        Component line = text().msg("bank.robbery.deputy.call", Text.component("player", Names.display(robber)),
                        Text.unparsed("max", maxDeputies))
                .clickEvent(ClickEvent.runCommand("/bank defend"))
                .hoverEvent(HoverEvent.showText(text().msg("bank.robbery.deputy.call-hover")));
        for (ServerPlayer o : bank.plugin.onlinePlayers()) {
            if (o.getUUID().equals(r.robber) || !mayDeputize(o)) continue;
            Adv.send(o, line);
        }
    }

    private boolean mayDeputize(ServerPlayer o) {
        if (!Perms.has(o, "feather.bank.defend") || bank.plugin.authBlocked(o)) return false;
        if (bank.plugin.isAfk(o)) return false;
        return survival(o);
    }

    /** /bank defend: joins the running robbery as a deputy and moves the player near the bank. */
    void defend(ServerPlayer p) {
        Robbery r = current;
        if (!deputiesOn) {
            bank.error(p, "bank.robbery.deputy.off");
            return;
        }
        if (r == null || r.ended) {
            bank.error(p, "bank.robbery.deputy.none");
            return;
        }
        if (r.robber.equals(p.getUUID())) {
            bank.error(p, "bank.robbery.deputy.robber");
            return;
        }
        if (r.deputies.containsKey(p.getUUID())) {
            bank.error(p, "bank.robbery.deputy.already");
            return;
        }
        if (!Perms.has(p, "feather.bank.defend")) {
            bank.error(p, "general.no-permission");
            return;
        }
        if (!mayDeputize(p)) {
            bank.error(p, "bank.robbery.deputy.not-now");
            return;
        }
        if (r.deputies.size() >= maxDeputies) {
            bank.error(p, "bank.robbery.deputy.full", Text.unparsed("max", maxDeputies));
            return;
        }
        Loc to = Police.spot(r.vaultCenter, 4, 10);
        if (to == null) to = r.vaultCenter.add(0, 0.5, 2);
        Loc from = Loc.of(p);
        r.deputies.put(p.getUUID(), from);
        bank.store.log("ROBBERY-DEPUTY " + Names.name(p) + " " + p.getUUID() + " id=" + r.id);
        UUID id = p.getUUID();
        bank.plugin.teleportPlayer(p, to).thenAccept(ok -> {
            if (!Boolean.TRUE.equals(ok)) {
                Tasks.sync(() -> {
                    r.deputies.remove(id);
                    ServerPlayer pl = bank.plugin.player(id);
                    if (pl != null) bank.error(pl, "bank.robbery.deputy.cant-move");
                });
            }
        });
        text().send(p, "bank.robbery.deputy.joined", Text.unparsed("percent", pct(deputyRewardPercent)));
        text().sound(p, "accepted");
        ServerPlayer robber = robberPlayer();
        if (robber != null) text().actionBar(robber, "bank.robbery.deputy.robber-warned", Text.component("player", Names.display(p)));
    }

    private static String pct(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** The loot a successful escape would pay right now (the reserve formula; never more than the reserve). */
    long wouldBeLoot() {
        long reserve = bank.store.reserve;
        long loot = (long) Math.floor(reserve * lootPercent / 100.0);
        loot = Math.max(loot, lootMin);
        loot = Math.min(loot, lootMax);
        return Math.max(0, Math.min(loot, reserve));
    }

    /**
     * The robber was caught: deputies who are online and still near (the vault or where the robber fell) split
     * {@code deputies.reward-percent} of the would-be loot, taken from the vault reserve only, into their balances.
     */
    private void payDeputies(Robbery r, Loc fell) {
        if (!deputiesOn || r.deputies.isEmpty() || deputyRewardPercent <= 0 || bank.store.unreadable) return;
        List<ServerPlayer> present = new ArrayList<>();
        for (UUID id : r.deputies.keySet()) {
            ServerPlayer d = bank.plugin.player(id);
            if (d == null || !d.isAlive()) continue;
            if (near(d, r.vaultCenter) || (fell != null && near(d, fell))) present.add(d);
        }
        if (present.isEmpty()) return;
        long pool = (long) Math.floor(wouldBeLoot() * deputyRewardPercent / 100.0);
        pool = Math.min(pool, bank.store.reserve);
        long share = pool / present.size();
        if (share <= 0) return;
        BankStore store = bank.store;
        long paid = 0;
        for (ServerPlayer d : present) {
            BankStore.Account a = store.account(d.getUUID());
            a.name = Names.name(d);
            if (a.balance > bank.maxBalance - share || store.reserve < share) {
                text().send(d, "bank.robbery.deputy.cap");
                continue;
            }
            store.reserve -= share;
            a.balance += share;
            paid += share;
            store.record(a, "DEFEND", share, "caught " + r.robberName);
            store.log("ROBBERY-DEPUTY-REWARD " + a.name + " " + d.getUUID() + " coins=" + share + " balance=" + a.balance
                    + " reserve=" + store.reserve + " id=" + r.id);
            text().send(d, "bank.robbery.deputy.reward", n("amount", share), n("balance", a.balance), Text.unparsed("player", r.robberName));
            text().sound(d, "accepted");
        }
        if (paid > 0) {
            store.save();
            if (announce) broadcast("bank.robbery.deputy.announce-reward", Text.unparsed("player", r.robberName), n("amount", paid),
                    Text.unparsed("count", present.size()));
        }
    }

    private boolean near(ServerPlayer p, Loc at) {
        return at != null && p.level() == at.level() && Loc.of(p).distanceSquared(at) <= deputyPresentRadius * deputyPresentRadius;
    }

    /** Deputies go back where they were when they answered the call. */
    private void sendDeputiesBack(Robbery r) {
        if (!deputyReturn) return;
        for (Map.Entry<UUID, Loc> e : r.deputies.entrySet()) {
            ServerPlayer d = bank.plugin.player(e.getKey());
            Loc back = e.getValue();
            if (d == null || !d.isAlive() || back == null || back.level() == null) continue;
            text().send(d, "bank.robbery.deputy.going-back");
            UUID id = e.getKey();
            Tasks.later(60L, () -> {
                ServerPlayer pl = bank.plugin.player(id);
                if (pl != null && pl.isAlive()) bank.plugin.teleportPlayer(pl, back);
            });
        }
    }

    boolean isDeputy(ServerPlayer p) {
        Robbery r = current;
        return r != null && !r.ended && r.deputies.containsKey(p.getUUID());
    }

    private void broadcast(String key, TagResolver... r) {
        TagResolver[] all = java.util.Arrays.copyOf(r, r.length + 1);
        all[r.length] = Text.unparsed("server", bank.plugin.getConfig().getString("server-name", "Aftr Hrs"));
        bank.broadcast(text().msg(key, all));
    }

    private void horn(Robbery r) {
        Identifier id = Identifier.tryParse(hornSound == null ? "" : hornSound.toLowerCase(Locale.ROOT));
        if (id == null) return;
        Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map(h -> h)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
        Loc c = r.vaultCenter;
        for (ServerPlayer o : c.level().players()) {
            if (o.distanceToSqr(c.x(), c.y(), c.z()) <= 96 * 96) {
                o.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER, c.x(), c.y(), c.z(), 4f, 1f, o.getRandom().nextLong()));
            }
        }
    }

    // ------------------------------------------------------------------ the locks

    private void openGame(Robbery r, ServerPlayer p) {
        if (r.game != null) r.game.cancel();
        r.game = minigames.create(r.games.get(r.gameIndex));
        r.retryAt = 0;
        r.game.open(p);
        updateBar(r, p);
    }

    /** Right-clicking the chest (or /bank) during the robbery brings the current lock back up. */
    void reopen(ServerPlayer p) {
        Robbery r = current;
        if (r == null || !r.robber.equals(p.getUUID())) return;
        if (r.stage != Stage.CRACKING) {
            text().actionBar(p, "bank.robbery.run");
            return;
        }
        if (r.game == null || r.game.done) {
            long wait = Math.max(0, r.retryAt - System.currentTimeMillis());
            text().actionBar(p, "bank.robbery.retry-wait", Text.unparsed("seconds", (wait + 999) / 1000));
            return;
        }
        r.game.open(p);
    }

    void gameSolved(Minigames.Game g, ServerPlayer p) {
        Robbery r = current;
        if (r == null || r.game != g) return;
        r.gameIndex++;
        r.game = null;
        Adv.sound(p, "minecraft:block.iron_door.open", 1f, 0.8f);
        if (r.gameIndex < r.games.size()) {
            text().send(p, "bank.robbery.lock-open", Text.unparsed("stage", r.gameIndex), Text.unparsed("stages", r.games.size()));
            openGame(r, p);
            return;
        }
        r.stage = Stage.ESCAPE;
        closeDialog(p);
        text().send(p, "bank.robbery.cracked", Text.unparsed("distance", (long) escapeDistance), Text.unparsed("radius", (long) loseRadius),
                Text.unparsed("seconds", loseSeconds));
        if (announce) broadcast("bank.robbery.announce-cracked", Text.component("player", Names.display(p)));
        bank.store.log("ROBBERY-CRACKED " + r.robberName + " id=" + r.id);
        horn(r);
        updateBar(r, p);
    }

    void gameFailed(Minigames.Game g, ServerPlayer p, String reasonKey) {
        Robbery r = current;
        if (r == null || r.game != g) return;
        r.failures++;
        r.alarm++;
        r.game = null;
        Adv.sound(p, "minecraft:block.anvil.land", 0.8f, 0.6f);
        closeDialog(p);
        if (r.failures >= maxFailures) {
            end(r, false, "bank.robbery.end.too-many-failures", true);
            return;
        }
        r.retryAt = System.currentTimeMillis() + retrySeconds * 1000L;
        text().send(p, "bank.robbery.lock-failed", Text.component("reason", text().msg(reasonKey)), Text.unparsed("alarm", r.alarm),
                Text.unparsed("left", maxFailures - r.failures), Text.unparsed("seconds", retrySeconds));
        // The alarm brings an extra wave right away.
        r.nextWave = Math.min(r.nextWave, System.currentTimeMillis());
        horn(r);
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        Robbery r = current;
        if (r == null || r.ended || r.dying) return;
        ServerPlayer p = bank.plugin.player(r.robber);
        if (p == null || p.hasDisconnected()) {
            end(r, false, "bank.robbery.end.quit", true);
            return;
        }
        if (!r.bypass && p.isAlive()) lock.enforce(p, false);
        if (++tick % 20 != 0) return;
        second(r, p);
    }

    private void second(Robbery r, ServerPlayer p) {
        long now = System.currentTimeMillis();
        if (!p.isAlive()) return;
        if (now >= r.deadline) {
            end(r, false, "bank.robbery.end.timeout", true);
            return;
        }
        boolean sameWorld = p.level() == r.vaultCenter.level();
        if (r.stage == Stage.CRACKING) {
            double d = sameWorld ? Math.sqrt(Loc.of(p).distanceSquared(r.vaultCenter)) : Double.MAX_VALUE;
            if (d > vaultRadius) {
                r.outsideSeconds++;
                if (r.outsideSeconds > leaveGrace) {
                    end(r, false, "bank.robbery.end.left-vault", true);
                    return;
                }
                text().actionBar(p, "bank.robbery.come-back", Text.unparsed("seconds", leaveGrace - r.outsideSeconds + 1));
            } else {
                r.outsideSeconds = 0;
            }
            if (r.game == null && r.retryAt > 0 && now >= r.retryAt) {
                text().send(p, "bank.robbery.retry");
                openGame(r, p);
            }
            if (now >= r.nextHorn) {
                horn(r);
                r.nextHorn = now + 10_000L;
            }
        } else {
            if (!sameWorld) {
                end(r, false, "bank.robbery.end.left-vault", true);
                return;
            }
            double dx = p.getX() - r.vaultCenter.x(), dz = p.getZ() - r.vaultCenter.z();
            r.farEnough = Math.hypot(dx, dz) >= escapeDistance;
            int near = police.near(r, p, loseRadius);
            if (r.farEnough && near == 0) {
                if (r.clearSince == 0) r.clearSince = now;
                if (now - r.clearSince >= loseSeconds * 1000L) {
                    end(r, true, null, false);
                    return;
                }
            } else {
                r.clearSince = 0;
            }
        }
        police.tick(r, p, despawnDistance);
        if (now >= r.nextWave) {
            wave(r, p);
            r.nextWave = now + Math.max(5, waveSeconds - r.alarm * 4L) * 1000L;
        }
        updateBar(r, p);
    }

    private void wave(Robbery r, ServerPlayer p) {
        int want = perWave + r.alarm * perAlarm;
        int room = Math.max(0, maxAlive + r.alarm - r.police.size());
        int n = Math.min(want, room);
        int spawned = 0;
        for (int i = 0; i < n; i++) {
            Loc at;
            if (r.stage == Stage.CRACKING) {
                // Half of them come in around the vault, the rest around the robber (the same place, usually).
                Loc c = i % 2 == 0 ? r.vaultCenter : Loc.of(p);
                at = Police.spot(c, spawnMin, spawnMax);
            } else {
                at = Police.spot(Loc.of(p), escapeSpawnMin, escapeSpawnMax);
            }
            if (at == null) at = Police.spot(Loc.of(p), 3, spawnMax);
            if (at == null) continue;
            if (police.spawn(r, at) != null) spawned++;
        }
        if (spawned > 0) {
            text().actionBar(p, "bank.robbery.police-coming", Text.unparsed("count", spawned));
            Adv.sound(p, net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("minecraft:entity.villager.no"),
                    net.kyori.adventure.sound.Sound.Source.HOSTILE, 1f, 0.8f));
        }
    }

    private void updateBar(Robbery r, ServerPlayer p) {
        if (r.bar == null) return;
        long left = Math.max(0, r.deadline - System.currentTimeMillis());
        String time = String.format(Locale.ROOT, "%d:%02d", left / 60_000, (left / 1000) % 60);
        Component name;
        if (r.stage == Stage.CRACKING) {
            String key = r.game == null ? "bank.robbery.bar.retry" : "bank.robbery.bar.crack-" + r.game.type();
            name = text().msg("bank.robbery.bar.cracking", Text.component("game", text().msg(key)),
                    Text.unparsed("stage", Math.min(r.gameIndex + 1, r.games.size())), Text.unparsed("stages", r.games.size()),
                    Text.unparsed("time", time), Text.unparsed("alarm", r.alarm));
        } else if (!r.farEnough) {
            double dx = p.getX() - r.vaultCenter.x(), dz = p.getZ() - r.vaultCenter.z();
            long dist = p.level() == r.vaultCenter.level() ? (long) Math.hypot(dx, dz) : 0;
            name = text().msg("bank.robbery.bar.escape", Text.unparsed("distance", dist), Text.unparsed("goal", (long) escapeDistance),
                    Text.unparsed("time", time), Text.unparsed("alarm", r.alarm));
        } else {
            long clear = r.clearSince == 0 ? 0 : (System.currentTimeMillis() - r.clearSince) / 1000;
            name = text().msg(r.clearSince == 0 ? "bank.robbery.bar.lose-seen" : "bank.robbery.bar.lose",
                    Text.unparsed("seconds", Math.max(0, loseSeconds - clear)), Text.unparsed("radius", (long) loseRadius),
                    Text.unparsed("time", time), Text.unparsed("alarm", r.alarm));
        }
        r.bar.name(name);
        r.bar.progress(Math.max(0f, Math.min(1f, (float) left / (maxMinutes * 60_000f))));
    }

    // ------------------------------------------------------------------ ending

    /**
     * Ends the robbery. A failure with {@code penalty} gives the robber the longer cooldown; {@code reasonKey} names
     * why it failed (null on success).
     */
    void end(Robbery r, boolean success, String reasonKey, boolean penalty) {
        if (r.ended) return;
        r.ended = true;
        if (current == r) current = null;
        if (r.game != null) r.game.cancel();
        police.removeAll(r);
        ServerPlayer p = bank.plugin.player(r.robber);
        long now = System.currentTimeMillis();
        lock.forget(r.robber);
        if (p != null) {
            if (r.bar != null) BossBars.hide(p, r.bar);
            closeDialog(p);
            if (r.hadFlight && !p.getAbilities().mayfly && survival(p)) {
                p.getAbilities().mayfly = true;
                p.onUpdateAbilities();
            }
        }
        BankStore store = bank.store;
        if (success) {
            long loot = wouldBeLoot();
            store.reserve -= loot;
            store.robberiesWon++;
            store.stolen += loot;
            long left = 0;
            BankStore.Account a = store.account(r.robber);
            if (a.name == null) a.name = r.robberName;
            if (p != null) left = bank.payOut(p, a, loot);
            else a.pending = Math.addExact(a.pending, loot);
            store.record(a, "ROBBERY", loot, "the vault");
            store.log("ROBBERY-SUCCESS " + r.robberName + " " + r.robber + " loot=" + loot + " pending+=" + left + " reserve=" + store.reserve + " id=" + r.id);
            store.saveNow();
            BankModule.saveInventory(p);
            if (p != null) {
                text().send(p, "bank.robbery.success", n("loot", loot));
                if (left > 0) text().send(p, "bank.pending-added", n("amount", left));
                Adv.sound(p, "minecraft:ui.toast.challenge_complete", 1f, 1f);
                bank.plugin.advance(p, "event", "bank-robbery-success");
            }
            if (announce) broadcast("bank.robbery.announce-success", Text.unparsed("player", r.robberName), n("loot", loot));
            sendDeputiesBack(r);
            return;
        }
        if (penalty) {
            long until = now + failedCooldownMs;
            store.robberyCooldowns.merge(r.robber, until, Math::max);
        }
        store.log("ROBBERY-FAIL " + r.robberName + " " + r.robber + " reason=" + reasonKey + " id=" + r.id);
        store.save();
        Component reason = text().msg(reasonKey == null ? "bank.robbery.end.stopped" : reasonKey);
        if (p != null) text().send(p, "bank.robbery.failed", Text.component("reason", reason));
        if (announce && !"bank.robbery.end.server-stop".equals(reasonKey)) {
            broadcast("bank.robbery.announce-failed", Text.unparsed("player", r.robberName), Text.component("reason", reason));
        }
        // Caught (killed): the deputies who were there split a share of what the robber would have taken, from the reserve.
        if ("bank.robbery.end.caught".equals(reasonKey)) payDeputies(r, r.fell);
        if (!"bank.robbery.end.server-stop".equals(reasonKey)) sendDeputiesBack(r);
    }

    /** How long a hit by police or a deputy counts: a robber who dies within it was caught. */
    private static final long LAW_HIT_MS = 10_000L;

    private boolean byLaw(Robbery r, Entity from) {
        return Police.isPolice(from) || (from instanceof ServerPlayer d && r.deputies.containsKey(d.getUUID()));
    }

    /**
     * The robber died. Only a death at the hands of the police or a deputy (or within {@link #LAW_HIT_MS} of their last
     * hit) is "caught" and pays the deputies; any other death just ends the robbery.
     */
    private void onDeath(ServerPlayer p, Entity killerEntity) {
        Robbery r = current;
        if (r == null || r.ended || !r.robber.equals(p.getUUID())) return;
        boolean caught = System.currentTimeMillis() - r.lawHitAt <= LAW_HIT_MS
                || (killerEntity instanceof ServerPlayer killer && r.deputies.containsKey(killer.getUUID()));
        r.fell = Loc.of(p);
        r.dying = true;
        // Ends after the death has run (drops on the ground: GraveModule asks robberyDeath before this).
        Tasks.later(1, () -> end(r, false, caught ? "bank.robbery.end.caught" : "bank.robbery.end.died", true));
    }

    /** Logging out mid-robbery: by default the robber drops dead where they stand (their items fall there). */
    void onQuit(ServerPlayer p) {
        lastAttempt.remove(p.getUUID());
        Robbery r = current;
        if (r == null || r.ended || !r.robber.equals(p.getUUID())) return;
        var server = net.axther.aftrhrs.core.Feather.serverOrNull();
        if (server == null || !server.isRunning()) {
            end(r, false, "bank.robbery.end.server-stop", false);
            return;
        }
        end(r, false, "bank.robbery.end.quit", true);
        if (quitPenalty.equals("kill") && p.isAlive()) {
            quitKilling = p.getUUID();
            try {
                p.kill(p.level());
            } finally {
                quitKilling = null;
            }
        }
    }

    // ------------------------------------------------------------------ staff

    String statusLine() {
        Robbery r = current;
        if (r == null) {
            long cd = Math.max(0, bank.store.lastRobbery + serverCooldownMs - System.currentTimeMillis());
            return enabled ? (cd > 0 ? "none (server cooldown " + BankModule.until(bank.store.lastRobbery + serverCooldownMs) + ")" : "none") : "disabled";
        }
        return r.robberName + " - " + r.stage.name().toLowerCase(Locale.ROOT) + (r.stage == Stage.CRACKING ? " lock " + (r.gameIndex + 1) + "/" + r.games.size() : "")
                + ", alarm " + r.alarm + ", police " + r.police.size() + " (" + police.kind() + "), deputies " + r.deputies.size() + ", "
                + BankModule.until(r.deadline) + " left";
    }

    void command(Sender sender, String[] args) {
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (what) {
            case "stop" -> {
                Robbery r = current;
                if (r == null) {
                    text().send(sender, "bank.robbery.admin.none");
                    return;
                }
                end(r, false, "bank.robbery.end.stopped", false);
                text().send(sender, "bank.robbery.admin.stopped", Text.unparsed("player", r.robberName));
            }
            case "cooldown" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("reset")) {
                    text().send(sender, "bank.help-admin");
                    return;
                }
                if (args.length > 3) {
                    UUID id = bank.resolve(sender, args[3]);
                    if (id == null) {
                        bank.notFound(sender, args[3]);
                        return;
                    }
                    bank.store.robberyCooldowns.remove(id);
                    text().send(sender, "bank.robbery.admin.cooldown-reset-player", Text.unparsed("player", bank.nameOf(id)));
                } else {
                    bank.store.lastRobbery = 0;
                    bank.store.robberyCooldowns.clear();
                    text().send(sender, "bank.robbery.admin.cooldown-reset");
                }
                bank.store.save();
            }
            default -> text().send(sender, "bank.robbery.admin.status", Text.unparsed("status", statusLine()));
        }
    }
}
