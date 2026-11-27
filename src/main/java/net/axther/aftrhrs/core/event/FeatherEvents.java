package net.axther.aftrhrs.core.event;

import net.axther.aftrhrs.core.Loc;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Events Fabric API doesn't have, fired from this mod's mixins ({@code net.axther.aftrhrs.mixin}). One shared place:
 * add new ones here with a javadoc naming what fires them. <b>Allow</b> events stop at the first listener that returns
 * false (the action is cancelled); other events reach every listener. All fire on the server thread.
 *
 * <p>Covered by Fabric API instead (use those directly): join/quit {@code ServerPlayConnectionEvents.JOIN/DISCONNECT};
 * chat {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} (cancel) / {@code CHAT_MESSAGE}; damage
 * {@code ServerLivingEntityEvents.ALLOW_DAMAGE} (entity damage by entity: {@link #attacker} finds the player behind
 * a projectile) / {@code AFTER_DAMAGE}; deaths {@code ServerLivingEntityEvents.AFTER_DEATH}; block breaking
 * {@code PlayerBlockBreakEvents.BEFORE/AFTER}; interact {@code UseBlockCallback}, {@code UseItemCallback},
 * {@code UseEntityCallback}, {@code AttackEntityCallback}, {@code AttackBlockCallback}; sleeping
 * {@code EntitySleepEvents}; world change {@code ServerEntityWorldChangeEvents}; ticks {@code ServerTickEvents}.
 */
public final class FeatherEvents {
    private FeatherEvents() {
    }

    /** Helper for "all listeners must allow" events. */
    public static <T> Event<T> allow(Class<T> type, java.util.function.Function<T[], T> invoker) {
        return EventFactory.createArrayBacked(type, invoker);
    }

    // ------------------------------------------------------------------ dialogs

    /** A {@code minecraft:custom} click (dialog button, chat click event) from a player. Return true to consume it. */
    @FunctionalInterface
    public interface CustomClick {
        boolean onClick(ServerPlayer player, Identifier id, Optional<Tag> payload);
    }

    /** Fired by {@code ServerCommonPacketListenerImplMixin}; {@code Dialogs} listens for {@code aftrhrs:menu}. */
    public static final Event<CustomClick> CUSTOM_CLICK = EventFactory.createArrayBacked(CustomClick.class, ls -> (p, id, payload) -> {
        for (CustomClick l : ls) if (l.onClick(p, id, payload)) return true;
        return false;
    });

    // ------------------------------------------------------------------ teleports

    @FunctionalInterface
    public interface Teleport {
        boolean allow(ServerPlayer player, Loc from, Loc to, TeleportCause cause);
    }

    /**
     * Before a player is moved (Bukkit's PlayerTeleportEvent): every {@code ServerPlayer.teleport(TeleportTransition)}
     * (cross-world moves, /tp, pearls, portals, Feather's teleports) and {@code ServerPlayer.teleportTo(x, y, z)}
     * (chorus fruit, dismounting). Return false to cancel. Fired by {@code ServerPlayerMixin}.
     */
    public static final Event<Teleport> PLAYER_TELEPORT = EventFactory.createArrayBacked(Teleport.class, ls -> (p, from, to, cause) -> {
        for (Teleport l : ls) if (!l.allow(p, from, to, cause)) return false;
        return true;
    });

    @FunctionalInterface
    public interface Teleported {
        void after(ServerPlayer player, Loc from, Loc to, TeleportCause cause);
    }

    /** After a teleport that went through (Bukkit's MONITOR + ignoreCancelled): /back records {@code from} here. */
    public static final Event<Teleported> PLAYER_TELEPORTED = EventFactory.createArrayBacked(Teleported.class, ls -> (p, from, to, cause) -> {
        for (Teleported l : ls) l.after(p, from, to, cause);
    });

    @FunctionalInterface
    public interface WorldChange {
        void before(ServerPlayer player, Loc to);
    }

    /**
     * Fired by {@code Feather.beforeTeleport} (so by every Feather teleport) when the player is about to change
     * level: take display passengers (nametags) off here.
     */
    public static final Event<WorldChange> BEFORE_WORLD_CHANGE = EventFactory.createArrayBacked(WorldChange.class, ls -> (p, to) -> {
        for (WorldChange l : ls) l.before(p, to);
    });

    // ------------------------------------------------------------------ commands

    @FunctionalInterface
    public interface Command {
        boolean allow(ServerPlayer player, String command);
    }

    /**
     * A command a player typed (Bukkit's PlayerCommandPreprocessEvent), without the leading slash, before it runs.
     * Return false to drop it. Not fired for commands run by code or menus. Fired by
     * {@code ServerGamePacketListenerImplMixin}.
     */
    public static final Event<Command> COMMAND = EventFactory.createArrayBacked(Command.class, ls -> (p, cmd) -> {
        for (Command l : ls) if (!l.allow(p, cmd)) return false;
        return true;
    });

    // ------------------------------------------------------------------ blocks

    @FunctionalInterface
    public interface BlockPlace {
        boolean allow(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, ItemStack stack);
    }

    /**
     * A player is about to place a block from a {@code BlockItem} ({@code state} is what would be placed). Return false
     * to refuse (the client is resynced). Fired by {@code BlockItemMixin}.
     */
    public static final Event<BlockPlace> BLOCK_PLACE = EventFactory.createArrayBacked(BlockPlace.class, ls -> (p, level, pos, state, stack) -> {
        for (BlockPlace l : ls) if (!l.allow(p, level, pos, state, stack)) return false;
        return true;
    });

    @FunctionalInterface
    public interface BlockPlaced {
        void placed(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, ItemStack stack);
    }

    /** Right after a player placed a block (before the item is used up, so {@code stack} still shows it). */
    public static final Event<BlockPlaced> BLOCK_PLACED = EventFactory.createArrayBacked(BlockPlaced.class, ls -> (p, level, pos, state, stack) -> {
        for (BlockPlaced l : ls) l.placed(p, level, pos, state, stack);
    });

    @FunctionalInterface
    public interface SignEdit {
        boolean allow(ServerPlayer player, SignBlockEntity sign, boolean front, List<String> lines);
    }

    /** A player finished editing a sign (Bukkit's SignChangeEvent, without line rewriting). False keeps the old text. */
    public static final Event<SignEdit> SIGN_EDIT = EventFactory.createArrayBacked(SignEdit.class, ls -> (p, sign, front, lines) -> {
        for (SignEdit l : ls) if (!l.allow(p, sign, front, lines)) return false;
        return true;
    });

    @FunctionalInterface
    public interface Physical {
        boolean allow(Entity entity, ServerLevel level, BlockPos pos, BlockState state);
    }

    /**
     * An entity treads on something (Bukkit's PHYSICAL interact / EntityInteractEvent): trampling farmland, pressing a
     * pressure plate, tripping a tripwire. {@code entity} may be a mob; check for {@code ServerPlayer}. Return false to
     * stop it. Fired by the farmland, pressure plate and tripwire mixins.
     */
    public static final Event<Physical> PHYSICAL_INTERACT = EventFactory.createArrayBacked(Physical.class, ls -> (e, level, pos, state) -> {
        for (Physical l : ls) if (!l.allow(e, level, pos, state)) return false;
        return true;
    });

    // ------------------------------------------------------------------ items

    @FunctionalInterface
    public interface Pickup {
        boolean allow(ServerPlayer player, ItemEntity item);
    }

    /**
     * A player is about to pick up an item entity: a veto gate. It runs every tick a player touches the item, right
     * before vanilla tries {@code Inventory.add} (which may still fail, e.g. a full inventory), so listeners must not
     * have side effects. React to a pickup that really happened with {@link #ITEM_PICKED_UP}.
     */
    public static final Event<Pickup> ITEM_PICKUP = EventFactory.createArrayBacked(Pickup.class, ls -> (p, item) -> {
        for (Pickup l : ls) if (!l.allow(p, item)) return false;
        return true;
    });

    @FunctionalInterface
    public interface PickedUp {
        void picked(ServerPlayer player, ItemEntity item, int taken);
    }

    /**
     * A player really took (all or part of) an item entity into their inventory. Fires after {@code Inventory.add}
     * succeeded, before vanilla's take()/discard; {@code taken} is how many items went in.
     */
    public static final Event<PickedUp> ITEM_PICKED_UP = EventFactory.createArrayBacked(PickedUp.class, ls -> (p, item, n) -> {
        for (PickedUp l : ls) l.picked(p, item, n);
    });

    @FunctionalInterface
    public interface Drop {
        boolean allow(ServerPlayer player, ItemStack stack);
    }

    /**
     * A player throws an item (Q, Ctrl+Q, dropping out of an inventory window). Not death drops. Return false and the
     * item goes back into their inventory.
     */
    public static final Event<Drop> ITEM_DROP = EventFactory.createArrayBacked(Drop.class, ls -> (p, stack) -> {
        for (Drop l : ls) if (!l.allow(p, stack)) return false;
        return true;
    });

    @FunctionalInterface
    public interface SwapHands {
        boolean allow(ServerPlayer player);
    }

    /** The swap-hands key (F). Return false to keep the hands as they are (Feather's Shift+F hub hotkey does). */
    public static final Event<SwapHands> SWAP_HANDS = EventFactory.createArrayBacked(SwapHands.class, ls -> p -> {
        for (SwapHands l : ls) if (!l.allow(p)) return false;
        return true;
    });

    @FunctionalInterface
    public interface ContainerOpen {
        boolean allow(ServerPlayer player, MenuProvider menu);
    }

    /** A menu is about to open for a player ({@code ServerPlayer.openMenu}); false keeps it shut. Feather Guis too. */
    public static final Event<ContainerOpen> CONTAINER_OPEN = EventFactory.createArrayBacked(ContainerOpen.class, ls -> (p, m) -> {
        for (ContainerOpen l : ls) if (!l.allow(p, m)) return false;
        return true;
    });

    // ------------------------------------------------------------------ movement

    @FunctionalInterface
    public interface Move {
        boolean allow(ServerPlayer player, Loc from, Loc to);
    }

    /**
     * Once per tick for every player whose position changed since the last tick (rotation alone doesn't count;
     * teleports don't fire it). Return false to put the player back where they were (keeping their view), e.g. frozen
     * players. Keep listeners cheap. Fired from the tick handler here, not a mixin.
     */
    public static final Event<Move> PLAYER_MOVE = EventFactory.createArrayBacked(Move.class, ls -> (p, from, to) -> {
        for (Move l : ls) if (!l.allow(p, from, to)) return false;
        return true;
    });

    @FunctionalInterface
    public interface MoveBlock {
        void moved(ServerPlayer player, Loc from, Loc to);
    }

    /** Like {@link #PLAYER_MOVE} but only when the player entered another block (and the move was allowed). */
    public static final Event<MoveBlock> PLAYER_MOVE_BLOCK = EventFactory.createArrayBacked(MoveBlock.class, ls -> (p, from, to) -> {
        for (MoveBlock l : ls) l.moved(p, from, to);
    });

    // ------------------------------------------------------------------ death & respawn

    @FunctionalInterface
    public interface Death {
        void onDeath(ServerPlayer player, DamageSource source);
    }

    /**
     * A player is dying, before anything drops (Bukkit's PlayerDeathEvent timing): graves take the inventory here.
     * Fired by {@code ServerPlayerMixin} at the start of {@code die}.
     */
    public static final Event<Death> PLAYER_DEATH = EventFactory.createArrayBacked(Death.class, ls -> (p, source) -> {
        for (Death l : ls) l.onDeath(p, source);
    });

    @FunctionalInterface
    public interface RespawnPosition {
        /** Return a new destination, or null to keep {@code current}. */
        TeleportTransition modify(ServerPlayer player, TeleportTransition current, boolean bedOrAnchor);
    }

    /**
     * Where a respawning player appears (Bukkit's PlayerRespawnEvent#setRespawnLocation). {@code bedOrAnchor} is true
     * when the vanilla spot is their bed / respawn anchor / set spawn point. Fired by {@code PlayerListMixin}.
     */
    public static final Event<RespawnPosition> RESPAWN_POSITION = EventFactory.createArrayBacked(RespawnPosition.class, ls -> (p, current, bed) -> {
        TeleportTransition t = current;
        for (RespawnPosition l : ls) {
            TeleportTransition n = l.modify(p, t, bed);
            if (n != null) t = n;
        }
        return t;
    });

    @FunctionalInterface
    public interface Respawn {
        void respawned(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive);
    }

    /** After a respawn ({@code alive} false) or the End-exit copy ({@code alive} true). Wraps Fabric's AFTER_RESPAWN. */
    public static final Event<Respawn> PLAYER_RESPAWN = EventFactory.createArrayBacked(Respawn.class, ls -> (o, n, alive) -> {
        for (Respawn l : ls) l.respawned(o, n, alive);
    });

    // ------------------------------------------------------------------ effects

    @FunctionalInterface
    public interface EffectAdd {
        boolean allow(net.minecraft.world.entity.LivingEntity target, net.minecraft.world.effect.MobEffectInstance effect, Entity source);
    }

    /**
     * A potion effect is about to be put on a living entity ({@code source}: the splash potion's thrower, the lingering
     * cloud, the attacker; may be null). Return false to block it (pets: no harmful potions from strangers). Fired by
     * {@code LivingEntityEffectMixin} at the start of {@code addEffect(MobEffectInstance, Entity)}, server side only.
     */
    public static final Event<EffectAdd> EFFECT_ADD = allow(EffectAdd.class, ls -> (t, e, s) -> {
        for (EffectAdd l : ls) if (!l.allow(t, e, s)) return false;
        return true;
    });

    // ------------------------------------------------------------------ names

    @FunctionalInterface
    public interface NamesChanged {
        void changed(ServerPlayer player);
    }

    /** A player's nickname or vanish state changed ({@code Feather.playerNamesChanged}): refresh name caches. */
    public static final Event<NamesChanged> NAMES_CHANGED = EventFactory.createArrayBacked(NamesChanged.class, ls -> p -> {
        for (NamesChanged l : ls) l.changed(p);
    });

    @FunctionalInterface
    public interface Punch {
        void punched(ServerPlayer player);
    }

    /** A player left-clicked into the air (arm swing that hit nothing: {@code ServerboundPunchPacket}). Fired by {@code PlayerPunchMixin}. */
    public static final Event<Punch> PLAYER_PUNCH = EventFactory.createArrayBacked(Punch.class, ls -> p -> {
        for (Punch l : ls) l.punched(p);
    });

    // ------------------------------------------------------------------ AFK, sleep, poses

    @FunctionalInterface
    public interface AfkChanged {
        void changed(ServerPlayer player, boolean afk);
    }

    /** A player went AFK or came back (afk module): tab list and nametags refresh their {@code <afk>} tag here. */
    public static final Event<AfkChanged> AFK_CHANGED = EventFactory.createArrayBacked(AfkChanged.class, ls -> (p, afk) -> {
        for (AfkChanged l : ls) l.changed(p, afk);
    });

    @FunctionalInterface
    public interface SleepIgnored {
        boolean ignored(ServerPlayer player);
    }

    /**
     * Bukkit's {@code setSleepingIgnored}: return true and the player doesn't count when the server checks whether
     * enough players sleep (AFK players). Call {@code level.updateSleepingPlayerList()} after the answer changes.
     * Fired by {@code SleepStatusMixin}.
     */
    public static final Event<SleepIgnored> SLEEP_IGNORED = EventFactory.createArrayBacked(SleepIgnored.class, ls -> p -> {
        for (SleepIgnored l : ls) if (l.ignored(p)) return true;
        return false;
    });

    @FunctionalInterface
    public interface ForcedPose {
        /** The pose to hold, or null to let vanilla pick. */
        net.minecraft.world.entity.Pose pose(ServerPlayer player);
    }

    /**
     * Bukkit's {@code setPose(pose, true)}: a non-null answer keeps the player in that pose instead of the one vanilla
     * works out each tick (/lay, /swim). The first non-null answer wins. Fired by {@code PlayerPoseMixin}.
     */
    public static final Event<ForcedPose> FORCED_POSE = EventFactory.createArrayBacked(ForcedPose.class, ls -> p -> {
        for (ForcedPose l : ls) {
            net.minecraft.world.entity.Pose pose = l.pose(p);
            if (pose != null) return pose;
        }
        return null;
    });

    // ------------------------------------------------------------------ progress (stats, crafting, fishing, taming)

    @FunctionalInterface
    public interface StatAwarded {
        void awarded(ServerPlayer player, net.minecraft.stats.Stat<?> stat, int amount);
    }

    /**
     * A vanilla statistic went up ({@code ServerPlayer.awardStat}): {@code Stats.ITEM_CRAFTED}, {@code ENCHANT_ITEM},
     * {@code ITEM_USED} (bow/crossbow shots) ... Fired by {@code progress.ServerPlayerStatMixin}.
     */
    public static final Event<StatAwarded> STAT_AWARDED = EventFactory.createArrayBacked(StatAwarded.class, ls -> (p, stat, n) -> {
        for (StatAwarded l : ls) l.awarded(p, stat, n);
    });

    @FunctionalInterface
    public interface ItemCrafted {
        void crafted(ServerPlayer player, ItemStack stack);
    }

    /**
     * A player takes a made item (crafting grid incl. shift-click, smithing, stonecutter, furnace output, villager
     * trade): {@code stack} is the real stack being taken and may be edited in place (check
     * {@code player.containerMenu} for which). May fire twice for one shift-click. Fired by {@code progress.ItemCraftedMixin}.
     */
    public static final Event<ItemCrafted> ITEM_CRAFTED = EventFactory.createArrayBacked(ItemCrafted.class, ls -> (p, stack) -> {
        for (ItemCrafted l : ls) l.crafted(p, stack);
    });

    @FunctionalInterface
    public interface ItemMended {
        void mended(ServerPlayer player, ItemStack stack, int points);
    }

    /** Mending repaired {@code points} durability of a worn/held item from XP. Fired by {@code progress.ExperienceOrbMendMixin}. */
    public static final Event<ItemMended> ITEM_MENDED = EventFactory.createArrayBacked(ItemMended.class, ls -> (p, stack, n) -> {
        for (ItemMended l : ls) l.mended(p, stack, n);
    });

    @FunctionalInterface
    public interface CombineResult {
        void computed(net.minecraft.world.inventory.AbstractContainerMenu menu, ItemStack left, ItemStack right, ItemStack result);
    }

    /**
     * An anvil or grindstone (server side) just computed its result from {@code left} + {@code right}; listeners may
     * edit the non-empty {@code result} in place (it is what the player will take). Fired by {@code progress.CombineResultMixin}.
     */
    public static final Event<CombineResult> COMBINE_RESULT = EventFactory.createArrayBacked(CombineResult.class, ls -> (m, a, b, r) -> {
        for (CombineResult l : ls) l.computed(m, a, b, r);
    });

    @FunctionalInterface
    public interface FishCaught {
        void caught(ServerPlayer player, ItemStack rod, net.minecraft.world.entity.projectile.FishingHook hook, java.util.Collection<ItemStack> loot);
    }

    /**
     * A player reeled in a catch (Bukkit's PlayerFishEvent CAUGHT_FISH: the loot table rolled, {@code loot} non-empty).
     * Fired by {@code progress.FishCaughtMixin}.
     */
    public static final Event<FishCaught> FISH_CAUGHT = EventFactory.createArrayBacked(FishCaught.class, ls -> (p, rod, hook, loot) -> {
        for (FishCaught l : ls) l.caught(p, rod, hook, loot);
    });

    @FunctionalInterface
    public interface AnimalTamed {
        void tamed(ServerPlayer player, net.minecraft.world.entity.animal.Animal animal);
    }

    /** A player tamed a vanilla animal (wolf, cat, parrot, horse ...; Bukkit's EntityTameEvent). Fired by {@code progress.AnimalTamedMixin}. */
    public static final Event<AnimalTamed> ANIMAL_TAMED = EventFactory.createArrayBacked(AnimalTamed.class, ls -> (p, a) -> {
        for (AnimalTamed l : ls) l.tamed(p, a);
    });

    // ------------------------------------------------------------------ world changes (quests' "natural block" checks)

    @FunctionalInterface
    public interface BlockFormed {
        void formed(ServerLevel level, BlockPos pos);
    }

    /**
     * A block appeared at {@code pos} without a player placing it (Bukkit's BlockFormEvent / EntityChangeBlockEvent /
     * piston events): fluids meeting (cobblestone, stone, obsidian, basalt), a falling block landing, a piston moving a
     * block into place. Fired by {@code LiquidBlockFormMixin}, {@code LavaFluidFormMixin}, {@code FallingBlockLandMixin}
     * and {@code PistonMovingBlockMixin}.
     */
    public static final Event<BlockFormed> BLOCK_FORMED = EventFactory.createArrayBacked(BlockFormed.class, ls -> (level, pos) -> {
        for (BlockFormed l : ls) l.formed(level, pos);
    });

    @FunctionalInterface
    public interface SpawnerSpawn {
        void spawning(ServerLevel level, Entity entity, boolean trial);
    }

    /**
     * A monster spawner ({@code trial} false) or trial spawner is about to add this entity to the world (Bukkit's
     * SpawnReason.SPAWNER / TRIAL_SPAWNER). Fired by {@code SpawnerSpawnMixin} and {@code TrialSpawnerSpawnMixin}.
     */
    public static final Event<SpawnerSpawn> SPAWNER_SPAWN = EventFactory.createArrayBacked(SpawnerSpawn.class, ls -> (level, e, trial) -> {
        for (SpawnerSpawn l : ls) l.spawning(level, e, trial);
    });

    // ------------------------------------------------------------------ protected blocks

    @FunctionalInterface
    public interface BlockProtected {
        boolean isProtected(net.minecraft.world.level.Level level, BlockPos pos);
    }

    /**
     * Whether a block must stay as it is whatever the world does to it (Feather's mailbox blocks, bank chests): true
     * from any listener protects it. Asked by the mixins for explosions ({@code ServerExplosionMixin}: the block isn't
     * blown up), pistons ({@code PistonStructureResolverMixin}: a piston that would push, pull or break it doesn't
     * move), hoppers and hopper minecarts ({@code HopperBlockEntityMixin}: no container there), fire
     * ({@code FireBlockMixin}: it never burns away) and projectiles ({@code DecoratedPotBlockMixin}: a pot isn't
     * shattered). Players breaking or using it go through Fabric's {@code PlayerBlockBreakEvents} /
     * {@code UseBlockCallback}. Keep listeners cheap (a set lookup): explosions ask for every block they reach.
     */
    public static final Event<BlockProtected> BLOCK_PROTECTED = EventFactory.createArrayBacked(BlockProtected.class, ls -> (level, pos) -> {
        for (BlockProtected l : ls) if (l.isProtected(level, pos)) return true;
        return false;
    });

    /** Shortcut for {@link #BLOCK_PROTECTED} (false off the server). */
    public static boolean isProtected(net.minecraft.world.level.Level level, BlockPos pos) {
        return level instanceof ServerLevel && pos != null && BLOCK_PROTECTED.invoker().isProtected(level, pos);
    }

    // ------------------------------------------------------------------ chat, login, join/quit, server list

    /**
     * Phases of Fabric's {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} (register with
     * {@code ALLOW_CHAT_MESSAGE.register(FeatherEvents.CHAT_MODERATION, ...)}): {@link #CHAT_GATE} (the auth gate takes
     * the server code: nothing later ever sees it), {@link #CHAT_MODERATION} (staff chat, mutes, /mutechat), then the
     * default phase (modules that read or consume chat lines: chat games, AFK ...), then {@link #CHAT_FORMAT} (the chat
     * module formats the line and broadcasts it itself when chat is unsigned; it then fires {@code CHAT_MESSAGE} for
     * the line, so "after chat" listeners see every line that was shown).
     */
    public static final Identifier CHAT_GATE = Identifier.fromNamespaceAndPath("aftrhrs", "chat_gate");
    public static final Identifier CHAT_MODERATION = Identifier.fromNamespaceAndPath("aftrhrs", "chat_moderation");
    public static final Identifier CHAT_FORMAT = Identifier.fromNamespaceAndPath("aftrhrs", "chat_format");

    @FunctionalInterface
    public interface Login {
        /** {@code current} is the refusal so far (vanilla's ban/whitelist/full message, or null = allowed). */
        net.minecraft.network.chat.Component check(net.minecraft.server.players.NameAndId profile, java.net.SocketAddress address,
                                                   net.minecraft.network.chat.Component current);
    }

    /**
     * Whether a player may log in (Bukkit's AsyncPlayerPreLoginEvent, after vanilla's ban, whitelist and full checks).
     * Each listener gets the current refusal (null = allowed) and returns the new one: replace a ban screen, refuse
     * with a maintenance message, or keep {@code current}. Fired by {@code PlayerListLoginMixin} ({@code canPlayerLogin}).
     */
    public static final Event<Login> PLAYER_LOGIN = EventFactory.createArrayBacked(Login.class, ls -> (profile, address, current) -> {
        net.minecraft.network.chat.Component c = current;
        for (Login l : ls) c = l.check(profile, address, c);
        return c;
    });

    @FunctionalInterface
    public interface JoinQuitMessage {
        /** Return the line to broadcast instead of {@code current}, or null for none. */
        net.minecraft.network.chat.Component message(ServerPlayer player, net.minecraft.network.chat.Component current);
    }

    /** The "joined the game" broadcast (vanilla's yellow line). Fired by {@code PlayerListLoginMixin}. */
    public static final Event<JoinQuitMessage> JOIN_MESSAGE = EventFactory.createArrayBacked(JoinQuitMessage.class, ls -> (p, current) -> {
        net.minecraft.network.chat.Component c = current;
        for (JoinQuitMessage l : ls) c = l.message(p, c);
        return c;
    });

    /** The "left the game" broadcast. Fired by {@code ServerGamePacketListenerImplMixin}. */
    public static final Event<JoinQuitMessage> QUIT_MESSAGE = EventFactory.createArrayBacked(JoinQuitMessage.class, ls -> (p, current) -> {
        net.minecraft.network.chat.Component c = current;
        for (JoinQuitMessage l : ls) c = l.message(p, c);
        return c;
    });

    @FunctionalInterface
    public interface ServerStatusModifier {
        net.minecraft.network.protocol.status.ServerStatus modify(net.minecraft.network.protocol.status.ServerStatus status);
    }

    /**
     * The server-list entry (MOTD, player counts and sample, version label, icon) sent for a status ping. Runs on a
     * network thread: read only thread-safe state. Fired by {@code ServerStatusPacketListenerImplMixin}.
     */
    public static final Event<ServerStatusModifier> SERVER_STATUS = EventFactory.createArrayBacked(ServerStatusModifier.class, ls -> status -> {
        net.minecraft.network.protocol.status.ServerStatus s = status;
        for (ServerStatusModifier l : ls) s = l.modify(s);
        return s;
    });

    @FunctionalInterface
    public interface SendCommands {
        boolean allow(ServerPlayer player);
    }

    /** The command tree is about to be sent to a player; false sends an empty tree (auth gate). Fired by {@code CommandsMixin}. */
    public static final Event<SendCommands> SEND_COMMANDS = EventFactory.createArrayBacked(SendCommands.class, ls -> p -> {
        for (SendCommands l : ls) if (!l.allow(p)) return false;
        return true;
    });

    @FunctionalInterface
    public interface CommandSuggestions {
        /** Return {@code current} or a new list (merge, don't drop, what others suggested). */
        com.mojang.brigadier.suggestion.Suggestions modify(ServerPlayer player, String command,
                                                           com.mojang.brigadier.suggestion.Suggestions current);
    }

    /**
     * Tab completion of a typed command line ({@code command} without the slash), after Brigadier made its list; runs
     * on the server thread. Fired by {@code PlayerConnectionMixin}.
     */
    public static final Event<CommandSuggestions> COMMAND_SUGGESTIONS = EventFactory.createArrayBacked(CommandSuggestions.class, ls -> (p, cmd, cur) -> {
        com.mojang.brigadier.suggestion.Suggestions s = cur;
        for (CommandSuggestions l : ls) s = l.modify(p, cmd, s);
        return s;
    });

    // ------------------------------------------------------------------ visibility, targeting, clicks

    @FunctionalInterface
    public interface EntityVisible {
        boolean visible(Entity entity, ServerPlayer viewer);
    }

    /**
     * Whether {@code viewer}'s client is sent {@code entity} at all (Bukkit's hidePlayer / hideEntity): false keeps it
     * off (or takes it off) their client. Checked when tracking changes; call {@link #refreshVisibility} after a
     * change. Fired from {@code Entity.broadcastToPlayer} ({@code EntityVisibilityMixin}). Keep listeners cheap.
     */
    public static final Event<EntityVisible> ENTITY_VISIBLE = EventFactory.createArrayBacked(EntityVisible.class, ls -> (e, viewer) -> {
        for (EntityVisible l : ls) if (!l.visible(e, viewer)) return false;
        return true;
    });

    /** Re-checks {@link #ENTITY_VISIBLE} for one entity against every player in its level right away. */
    public static void refreshVisibility(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return;
        net.minecraft.server.level.ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(entity.getId());
        if (tracked != null) tracked.updatePlayers(level.players());
    }

    @FunctionalInterface
    public interface MobTarget {
        boolean allow(net.minecraft.world.entity.Mob mob, net.minecraft.world.entity.LivingEntity target);
    }

    /** A mob is about to target someone (Bukkit's EntityTargetEvent); false and it targets nobody. Fired by {@code MobTargetMixin}. */
    public static final Event<MobTarget> MOB_TARGET = EventFactory.createArrayBacked(MobTarget.class, ls -> (mob, target) -> {
        for (MobTarget l : ls) if (!l.allow(mob, target)) return false;
        return true;
    });

    @FunctionalInterface
    public interface ContainerClick {
        boolean allow(ServerPlayer player, net.minecraft.world.inventory.AbstractContainerMenu menu);
    }

    /**
     * A click in any open menu, the player's own inventory included (Bukkit's InventoryClickEvent); false ignores it
     * and resyncs the client. Fired by {@code ServerGamePacketListenerImplMixin}.
     */
    public static final Event<ContainerClick> CONTAINER_CLICK = EventFactory.createArrayBacked(ContainerClick.class, ls -> (p, menu) -> {
        for (ContainerClick l : ls) if (!l.allow(p, menu)) return false;
        return true;
    });

    // ------------------------------------------------------------------ world protection (claims, locks, graves)

    @FunctionalInterface
    public interface Explode {
        void explode(ServerLevel level, net.minecraft.world.level.Explosion explosion, List<BlockPos> blocks);
    }

    /**
     * An explosion is about to break (or, for wind charges, toggle) these blocks and light fire among them: remove
     * positions from {@code blocks} to spare them (Bukkit's EntityExplodeEvent / BlockExplodeEvent blockList). Entities
     * it hurts go through the damage events. Fired by {@code ServerExplosionMixin}.
     */
    public static final Event<Explode> EXPLOSION = EventFactory.createArrayBacked(Explode.class, ls -> (level, ex, blocks) -> {
        for (Explode l : ls) {
            if (blocks.isEmpty()) return;
            l.explode(level, ex, blocks);
        }
    });

    @FunctionalInterface
    public interface Spread {
        boolean allow(ServerLevel level, BlockPos from, BlockPos to);
    }

    /**
     * Fire spreading to {@code to} from the fire at {@code from} ({@code FireBlockMixin}) or lava setting fire near it
     * ({@code LavaFluidMixin}, {@code from} = the lava). False: no fire there.
     */
    public static final Event<Spread> FIRE_SPREAD = EventFactory.createArrayBacked(Spread.class, ls -> (level, from, to) -> {
        for (Spread l : ls) if (!l.allow(level, from, to)) return false;
        return true;
    });

    /** Fire at {@code from} burning away the block at {@code to} (Bukkit's BlockBurnEvent). False keeps the block. */
    public static final Event<Spread> BLOCK_BURN = EventFactory.createArrayBacked(Spread.class, ls -> (level, from, to) -> {
        for (Spread l : ls) if (!l.allow(level, from, to)) return false;
        return true;
    });

    @FunctionalInterface
    public interface FluidSpread {
        boolean allow(ServerLevel level, BlockPos to, net.minecraft.core.Direction dir);
    }

    /**
     * Water or lava flowing into {@code to}, moving in direction {@code dir} (it comes from
     * {@code to.relative(dir.getOpposite())}; listeners use the coordinates, no BlockPos is made). Bukkit's
     * BlockFromToEvent. False: it doesn't. Fired by {@code FlowingFluidMixin} and {@code LavaFluidMixin}.
     */
    public static final Event<FluidSpread> FLUID_INTO = EventFactory.createArrayBacked(FluidSpread.class, ls -> (level, to, dir) -> {
        for (FluidSpread l : ls) if (!l.allow(level, to, dir)) return false;
        return true;
    });

    @FunctionalInterface
    public interface Piston {
        boolean allow(ServerLevel level, BlockPos piston, net.minecraft.core.Direction push, boolean extending, List<BlockPos> moved, List<BlockPos> destroyed);
    }

    /**
     * A piston is about to move blocks ({@code moved}, each going one block towards {@code push}) and break
     * {@code destroyed}; extending (the head goes to {@code piston + push}) and (sticky) retracting. False: nothing moves. Fired by {@code PistonBaseBlockMixin}.
     */
    public static final Event<Piston> PISTON = EventFactory.createArrayBacked(Piston.class, ls -> (level, pos, push, extending, moved, destroyed) -> {
        for (Piston l : ls) if (!l.allow(level, pos, push, extending, moved, destroyed)) return false;
        return true;
    });

    @FunctionalInterface
    public interface Dispense {
        boolean allow(ServerLevel level, BlockPos pos, BlockState state);
    }

    /** A dispenser or dropper is about to fire (Bukkit's BlockDispenseEvent). False: it does nothing. */
    public static final Event<Dispense> DISPENSE = EventFactory.createArrayBacked(Dispense.class, ls -> (level, pos, state) -> {
        for (Dispense l : ls) if (!l.allow(level, pos, state)) return false;
        return true;
    });

    @FunctionalInterface
    public interface EntityChangeBlock {
        boolean allow(Entity entity, ServerLevel level, BlockPos pos, BlockState newState);
    }

    /**
     * Something other than a player changes or breaks a block (Bukkit's EntityChangeBlockEvent): endermen taking and
     * placing blocks, ravagers and withers breaking them, zombies breaking doors and digging up turtle eggs, silverfish,
     * foxes picking berries, rabbits eating carrots, falling blocks landing, fireballs lighting fire, thrown potions
     * putting fire out, boats breaking lily pads (every {@code Level.destroyBlock} with an entity). {@code newState} is
     * air for removals. {@code entity} is never a player (a projectile's owner may be one). False keeps the block.
     * Fired by the {@code mixin.grief} mixins.
     */
    public static final Event<EntityChangeBlock> ENTITY_CHANGE_BLOCK = EventFactory.createArrayBacked(EntityChangeBlock.class, ls -> (e, level, pos, state) -> {
        for (EntityChangeBlock l : ls) if (!l.allow(e, level, pos, state)) return false;
        return true;
    });

    @FunctionalInterface
    public interface ProjectileHitBlock {
        boolean allow(Projectile projectile, ServerLevel level, BlockPos pos, BlockState state);
    }

    /**
     * A projectile hits a block and is about to affect it (decorated pots shatter, chorus flowers and dripstone break,
     * TNT lights, bells ring, targets...). False: the block ignores the hit. Fired by {@code BlockStateProjectileMixin}.
     */
    public static final Event<ProjectileHitBlock> PROJECTILE_HIT_BLOCK = EventFactory.createArrayBacked(ProjectileHitBlock.class, ls -> (p, level, pos, state) -> {
        for (ProjectileHitBlock l : ls) if (!l.allow(p, level, pos, state)) return false;
        return true;
    });

    @FunctionalInterface
    public interface EffectApply {
        boolean allow(net.minecraft.world.entity.LivingEntity target, net.minecraft.world.effect.MobEffectInstance effect, Entity source);
    }

    /**
     * An effect is about to be given to a living entity by {@code source} (a splash potion, a lingering cloud, a
     * tipped arrow; null without a source). False: not applied (Bukkit's PotionSplashEvent intensity 0 /
     * AreaEffectCloudApplyEvent). Fired by {@code LivingEntityEffectMixin}.
     */
    public static final Event<EffectApply> EFFECT_APPLY = EventFactory.createArrayBacked(EffectApply.class, ls -> (t, eff, src) -> {
        for (EffectApply l : ls) if (!l.allow(t, eff, src)) return false;
        return true;
    });

    @FunctionalInterface
    public interface EntityDamage {
        boolean allow(Entity victim, ServerLevel level, DamageSource source, float amount);
    }

    /**
     * Damage to entities Fabric's {@code ServerLivingEntityEvents.ALLOW_DAMAGE} never sees: item frames, paintings,
     * leash knots, cushions, boats, minecarts and armor stands (which skip LivingEntity's hurt). False: unharmed.
     * Fired by {@code mixin.EntityDamageMixins}.
     */
    public static final Event<EntityDamage> ENTITY_DAMAGE = EventFactory.createArrayBacked(EntityDamage.class, ls -> (v, level, src, amount) -> {
        for (EntityDamage l : ls) if (!l.allow(v, level, src, amount)) return false;
        return true;
    });

    @FunctionalInterface
    public interface HookPull {
        boolean allow(ServerPlayer player, Entity hooked);
    }

    /** A player reels in a fishing hook that caught an entity (Bukkit's PlayerFishEvent CAUGHT_ENTITY). False: no pull. */
    public static final Event<HookPull> HOOK_PULL = EventFactory.createArrayBacked(HookPull.class, ls -> (p, e) -> {
        for (HookPull l : ls) if (!l.allow(p, e)) return false;
        return true;
    });

    @FunctionalInterface
    public interface MobSpawn {
        boolean allow(ServerLevel level, net.minecraft.world.entity.Mob mob, net.minecraft.world.entity.EntitySpawnReason reason);
    }

    /**
     * A mob is about to spawn by itself: natural spawning ({@code NATURAL}, after its spawn rules passed) and patrols
     * ({@code PATROL}). False: it doesn't. Spawners, eggs and commands never fire this. Fired by {@code MobSpawnMixins}.
     */
    public static final Event<MobSpawn> MOB_SPAWN = EventFactory.createArrayBacked(MobSpawn.class, ls -> (level, mob, reason) -> {
        for (MobSpawn l : ls) if (!l.allow(level, mob, reason)) return false;
        return true;
    });

    @FunctionalInterface
    public interface NaturalSpawnAt {
        boolean allow(ServerLevel level, net.minecraft.world.entity.MobCategory category, BlockPos pos);
    }

    /**
     * Vanilla's natural spawner is trying a spot for a {@code category} mob: fires for every spot its player-distance
     * check accepted, before the mob type is picked or any Mob is built (an early, cheap version of
     * {@link #MOB_SPAWN}, which stays the exact per-mob check). {@code pos} is vanilla's MutableBlockPos, valid only
     * during the call: listeners must not keep it. False: skip this spot. Fired by {@code MobSpawnMixins}.
     */
    public static final Event<NaturalSpawnAt> NATURAL_SPAWN_AT = EventFactory.createArrayBacked(NaturalSpawnAt.class, ls -> (level, category, pos) -> {
        for (NaturalSpawnAt l : ls) if (!l.allow(level, category, pos)) return false;
        return true;
    });

    @FunctionalInterface
    public interface NaturalReplace {
        net.minecraft.world.entity.@org.jspecify.annotations.Nullable Mob replace(ServerLevel level, net.minecraft.world.entity.EntityType<?> type, BlockPos pos);
    }

    /**
     * Vanilla's natural spawner (reason {@code NATURAL}, not chunk generation) is about to create a mob of {@code type} at
     * {@code pos}; the type's spawn position, placement rules and room were already checked. A listener may return a
     * different mob (created with {@code EntitySpawnReason.NATURAL}, not yet positioned) to spawn in its place; the first
     * non-null wins, null keeps vanilla's. The replacement then goes through vanilla's remaining checks
     * ({@link #MOB_SPAWN}, obstruction), finalizeSpawn and the mob cap exactly like the original would have. Spawners,
     * eggs, reinforcements and commands never fire this. Fired by {@code NaturalSpawnReplaceMixin}.
     */
    public static final Event<NaturalReplace> NATURAL_REPLACE = EventFactory.createArrayBacked(NaturalReplace.class, ls -> (level, type, pos) -> {
        for (NaturalReplace l : ls) {
            var mob = l.replace(level, type, pos);
            if (mob != null) return mob;
        }
        return null;
    });

    @FunctionalInterface
    public interface MenuButton {
        boolean allow(ServerPlayer player, net.minecraft.world.inventory.AbstractContainerMenu menu, int button);
    }

    /**
     * A menu button click (lectern Take Book = 3, enchanting rows, loom and stonecutter choices...). False: ignored.
     * Fired by {@code ContainerButtonMixin}.
     */
    public static final Event<MenuButton> MENU_BUTTON = EventFactory.createArrayBacked(MenuButton.class, ls -> (p, menu, id) -> {
        for (MenuButton l : ls) if (!l.allow(p, menu, id)) return false;
        return true;
    });

    @FunctionalInterface
    public interface HopperPull {
        boolean allow(ServerLevel level, net.minecraft.world.level.block.entity.Hopper hopper, net.minecraft.world.Container source);
    }

    /**
     * A hopper (block or minecart) is about to pull items out of the container above it (Bukkit's InventoryMoveItemEvent
     * from that source). Hot path: keep it cheap. False: it leaves that container alone. Fired by {@code HopperMixin}.
     */
    public static final Event<HopperPull> HOPPER_PULL = EventFactory.createArrayBacked(HopperPull.class, ls -> (level, h, c) -> {
        for (HopperPull l : ls) if (!l.allow(level, h, c)) return false;
        return true;
    });

    // ------------------------------------------------------------------ damage

    @FunctionalInterface
    public interface DamageAmount {
        float modify(net.minecraft.world.entity.LivingEntity victim, DamageSource source, float amount);
    }

    /**
     * Scales the damage a living entity is about to take (Bukkit's {@code EntityDamageEvent#setDamage}), before armor,
     * enchantments and effects; each listener gets the previous one's result. Cancel with Fabric's
     * {@code ServerLivingEntityEvents.ALLOW_DAMAGE} instead. Fired by {@code LivingEntityDamageMixin} (hurtServer).
     */
    public static final Event<DamageAmount> DAMAGE_AMOUNT = EventFactory.createArrayBacked(DamageAmount.class, ls -> (v, source, amount) -> {
        float a = amount;
        for (DamageAmount l : ls) a = l.modify(v, source, a);
        return a;
    });

    // ------------------------------------------------------------------ enchanting (EnchantmentMenuMixin)

    @FunctionalInterface
    public interface EnchantingPower {
        int power(net.minecraft.world.level.Level level, BlockPos table, int bookshelves);
    }

    /**
     * An enchanting table counts its bookshelves (vanilla: up to 15 count). Modifier: return the number to use
     * (curios: end crystals on pedestals around the table). Offers, clues and the enchanting itself follow it.
     */
    public static final Event<EnchantingPower> ENCHANTING_POWER = EventFactory.createArrayBacked(EnchantingPower.class, ls -> (level, table, n) -> {
        int v = n;
        for (EnchantingPower l : ls) v = l.power(level, table, v);
        return v;
    });

    // ------------------------------------------------------------------ fishing (FishingHookMixin)

    @FunctionalInterface
    public interface FishLure {
        int lureTicks(net.minecraft.world.entity.projectile.FishingHook hook, ServerPlayer owner, int ticks);
    }

    /**
     * A bobbing hook just rolled how long until a fish swims up (vanilla: 100-600 ticks minus Lure). Modifier: return
     * the wait to use (each listener gets the previous one's result). Bukkit's {@code FishHook.setWaitTime} at cast.
     */
    public static final Event<FishLure> FISH_LURE = EventFactory.createArrayBacked(FishLure.class, ls -> (hook, p, ticks) -> {
        for (FishLure l : ls) ticks = l.lureTicks(hook, p, ticks);
        return ticks;
    });

    @FunctionalInterface
    public interface FishBite {
        void bite(net.minecraft.world.entity.projectile.FishingHook hook, ServerPlayer owner);
    }

    /** A fish bit (the bobber dips; reeling in now catches). PlayerFishEvent BITE. */
    public static final Event<FishBite> FISH_BITE = EventFactory.createArrayBacked(FishBite.class, ls -> (hook, p) -> {
        for (FishBite l : ls) l.bite(hook, p);
    });

    /** A bite ran out without a reel (PlayerFishEvent FAILED_ATTEMPT). */
    public static final Event<FishBite> FISH_ESCAPE = EventFactory.createArrayBacked(FishBite.class, ls -> (hook, p) -> {
        for (FishBite l : ls) l.bite(hook, p);
    });

    @FunctionalInterface
    public interface FishReel {
        boolean allow(net.minecraft.world.entity.projectile.FishingHook hook, ServerPlayer owner, ItemStack rod, boolean biting);
    }

    /**
     * The owner reels a hook in that isn't hooked into an entity ({@code biting}: a fish is on). Return false to keep
     * the hook in the water: nothing is caught and the rod takes no damage (PlayerFishEvent REEL_IN/CAUGHT_FISH cancel).
     */
    public static final Event<FishReel> FISH_REEL = EventFactory.createArrayBacked(FishReel.class, ls -> (hook, p, rod, biting) -> {
        for (FishReel l : ls) if (!l.allow(hook, p, rod, biting)) return false;
        return true;
    });

    @FunctionalInterface
    public interface FishCatch {
        void caught(net.minecraft.world.entity.projectile.FishingHook hook, ServerPlayer owner, ItemStack rod, List<ItemStack> loot);
    }

    /** A fish was reeled in: {@code loot} (mutable) is what flies to the player; replace or clear entries to change it. */
    public static final Event<FishCatch> FISH_CATCH = EventFactory.createArrayBacked(FishCatch.class, ls -> (hook, p, rod, loot) -> {
        for (FishCatch l : ls) l.caught(hook, p, rod, loot);
    });

    // ------------------------------------------------------------------ using items, crafting, vibrations

    @FunctionalInterface
    public interface ItemConsumed {
        void consumed(ServerPlayer player, ItemStack used, net.minecraft.world.InteractionHand hand);
    }

    /**
     * A player finished using an item (eating, drinking, a consumable like a joint): Bukkit's PlayerItemConsumeEvent
     * after the fact. {@code used} is a copy of the stack as it was before. Fired by LivingEntityUseItemMixin.
     */
    public static final Event<ItemConsumed> ITEM_CONSUMED = EventFactory.createArrayBacked(ItemConsumed.class, ls -> (p, used, hand) -> {
        for (ItemConsumed l : ls) l.consumed(p, used, hand);
    });

    @FunctionalInterface
    public interface CraftResult {
        ItemStack result(ServerPlayer player, net.minecraft.world.item.crafting.CraftingInput input, ItemStack result);
    }

    /**
     * The crafting grid (inventory or crafting table) shows a result. Modifier: return the stack to show (EMPTY for
     * none); taking it uses the matched recipe as usual. Bukkit's PrepareItemCraftEvent. Fired by CraftingMenuMixin.
     */
    public static final Event<CraftResult> CRAFT_RESULT = EventFactory.createArrayBacked(CraftResult.class, ls -> (p, input, result) -> {
        for (CraftResult l : ls) result = l.result(p, input, result);
        return result;
    });

    @FunctionalInterface
    public interface Vibration {
        boolean allow(ServerLevel level, net.minecraft.world.level.gameevent.vibrations.VibrationSystem receiver,
                      net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                      net.minecraft.world.level.gameevent.GameEvent.Context context);
    }

    /**
     * A vibration listener (sculk sensor, calibrated sensor, shrieker, warden, allay...) is about to take a game
     * event; {@code context.sourceEntity()} caused it. Return false to ignore it (Paper's BlockReceiveGameEvent).
     * Fired by VibrationListenerMixin.
     */
    public static final Event<Vibration> VIBRATION = EventFactory.createArrayBacked(Vibration.class, ls -> (level, r, ev, ctx) -> {
        for (Vibration l : ls) if (!l.allow(level, r, ev, ctx)) return false;
        return true;
    });

    // ------------------------------------------------------------------ entity tracking, healing, crits

    /**
     * Scoreboard tag for entities that must never be written to the world (Paper's {@code setPersistent(false)}):
     * {@code EntityTrackingMixin} makes {@code shouldBeSaved()} false for them, so they vanish on chunk unload/restart.
     */
    public static final String TRANSIENT_TAG = "aftrhrs_transient";

    /**
     * A living entity is about to regain health ({@code LivingEntityHealMixin}, before {@code heal} applies it).
     * {@code reason} uses Bukkit's {@code RegainReason} names: {@code SATIATED} (food), {@code REGEN} (peaceful),
     * {@code MAGIC_REGEN} (regeneration effect), {@code MAGIC} (instant health), else {@code CUSTOM}.
     */
    @FunctionalInterface
    public interface Heal {
        void healed(net.minecraft.world.entity.LivingEntity entity, float amount, String reason);
    }

    public static final Event<Heal> HEAL = EventFactory.createArrayBacked(Heal.class, ls -> (e, amount, reason) -> {
        for (Heal l : ls) l.healed(e, amount, reason);
    });

    private static String healReason;

    /** Set by the heal-source mixins around their {@code heal} calls (null clears it). Server thread. */
    public static void healReason(String reason) {
        healReason = reason;
    }

    /** The reason for a heal happening right now ({@code CUSTOM} outside a known source). */
    public static String healReason() {
        return healReason == null ? "CUSTOM" : healReason;
    }

    /** A player's melee attack is a critical hit; fired ({@code PlayerCritMixin}) just before its damage is dealt. */
    @FunctionalInterface
    public interface CriticalHit {
        void crit(net.minecraft.world.entity.player.Player attacker, Entity target);
    }

    public static final Event<CriticalHit> CRITICAL_HIT = EventFactory.createArrayBacked(CriticalHit.class, ls -> (p, target) -> {
        for (CriticalHit l : ls) l.crit(p, target);
    });

    @FunctionalInterface
    public interface MobPickup {
        boolean allow(net.minecraft.world.entity.Mob mob, net.minecraft.world.item.ItemStack stack);
    }

    /** A mob is about to pick up a dropped item (fox, allay, zombie...); false keeps it on the ground. {@code MobPickupMixin}. */
    public static final Event<MobPickup> MOB_PICKUP = EventFactory.createArrayBacked(MobPickup.class, ls -> (mob, stack) -> {
        for (MobPickup l : ls) if (!l.allow(mob, stack)) return false;
        return true;
    });

    @FunctionalInterface
    public interface MobConvert {
        boolean allow(net.minecraft.world.entity.Mob mob, net.minecraft.world.entity.EntityType<?> into);
    }

    /**
     * A mob is about to turn into another type (lightning: villager to witch, pig to zombified piglin; drowning, curing,
     * slime splits...); false cancels it, the mob stays as it is (Paper's EntityTransformEvent). {@code MobConvertMixin}.
     */
    public static final Event<MobConvert> MOB_CONVERT = EventFactory.createArrayBacked(MobConvert.class, ls -> (mob, into) -> {
        for (MobConvert l : ls) if (!l.allow(mob, into)) return false;
        return true;
    });

    @FunctionalInterface
    public interface MobUseContainer {
        boolean allow(net.minecraft.world.entity.Mob mob, ServerLevel level, net.minecraft.core.BlockPos pos);
    }

    /**
     * A mob is about to pick a container as a target to take items from or leave items in (copper golems sorting
     * copper chests); false makes it skip that container. Fired by {@code mixin.protect.CopperGolemSortMixin}.
     */
    public static final Event<MobUseContainer> MOB_USE_CONTAINER = EventFactory.createArrayBacked(MobUseContainer.class, ls -> (mob, level, pos) -> {
        for (MobUseContainer l : ls) if (!l.allow(mob, level, pos)) return false;
        return true;
    });

    @FunctionalInterface
    public interface EntityAdded {
        void added(net.minecraft.world.entity.Entity entity);
    }

    /**
     * A new entity is about to join a server level ({@code ServerLevel.addFreshEntity}: spawns, summons, spawn eggs,
     * built golems, breeding), not one loaded from disk or changing dimension. Its position is set. Fired by
     * {@code mixin.protect.FreshEntityMixin}.
     */
    public static final Event<EntityAdded> ENTITY_ADDED = EventFactory.createArrayBacked(EntityAdded.class, ls -> e -> {
        for (EntityAdded l : ls) l.added(e);
    });

    @FunctionalInterface
    public interface ThunderHit {
        boolean allow(net.minecraft.world.entity.Entity target, net.minecraft.world.entity.LightningBolt bolt);
    }

    /**
     * Lightning is about to strike an entity (damage, fire, villager to witch, pig to zombified piglin, creeper
     * charging...); {@code bolt.getCause()} is the player behind a Channeling trident. False spares the entity.
     * Fired by {@code mixin.protect.LightningBoltMixin}.
     */
    public static final Event<ThunderHit> THUNDER_HIT = EventFactory.createArrayBacked(ThunderHit.class, ls -> (target, bolt) -> {
        for (ThunderHit l : ls) if (!l.allow(target, bolt)) return false;
        return true;
    });

    @FunctionalInterface
    public interface PlantGrow {
        boolean allow(ServerLevel level, net.minecraft.core.BlockPos pos);
    }

    /**
     * Bone meal is about to be used on a block or in water (player, dispenser, farmer), or a sapling is about to grow into a tree:
     * both can place blocks several blocks around {@code pos} (Bukkit's BlockFertilizeEvent / StructureGrowEvent).
     * False: nothing grows (bone meal is not used up). Fired by {@code mixin.protect.PlantGrowMixins}.
     */
    public static final Event<PlantGrow> PLANT_GROW = EventFactory.createArrayBacked(PlantGrow.class, ls -> (level, pos) -> {
        for (PlantGrow l : ls) if (!l.allow(level, pos)) return false;
        return true;
    });

    // ------------------------------------------------------------------ helpers

    /** The player behind a damage source: the attacker, or the owner of the arrow/trident/pearl that hit. */
    public static ServerPlayer attacker(DamageSource source) {
        if (source == null) return null;
        if (source.getEntity() instanceof ServerPlayer p) return p;
        if (source.getDirectEntity() instanceof Projectile proj && proj.getOwner() instanceof ServerPlayer p) return p;
        return null;
    }

    // ------------------------------------------------------------------ tick-based movement

    private static final Map<UUID, Loc> LAST = new HashMap<>();
    private static boolean initialized;

    /** Hooks the tick-based events (once, from boot). */
    public static void init() {
        if (initialized) return;
        initialized = true;
        var chat = net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE;
        chat.addPhaseOrdering(CHAT_GATE, CHAT_MODERATION);
        chat.addPhaseOrdering(CHAT_MODERATION, Event.DEFAULT_PHASE);
        chat.addPhaseOrdering(Event.DEFAULT_PHASE, CHAT_FORMAT);
        ServerTickEvents.END_SERVER_TICK.register(FeatherEvents::tickMoves);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> LAST.remove(h.player.getUUID()));
        PLAYER_TELEPORTED.register((p, from, to, cause) -> LAST.remove(p.getUUID()));
        ServerPlayerEvents.AFTER_RESPAWN.register((o, n, alive) -> {
            LAST.remove(n.getUUID());
            PLAYER_RESPAWN.invoker().respawned(o, n, alive);
        });
    }

    private static void tickMoves(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Loc now = Loc.of(p);
            Loc before = LAST.get(p.getUUID());
            if (before == null || before.level() != now.level()) {
                LAST.put(p.getUUID(), now);
                continue;
            }
            double d = before.distanceSquared(now);
            if (d < 1e-8) {
                LAST.put(p.getUUID(), now);
                continue;
            }
            if (!PLAYER_MOVE.invoker().allow(p, before, now)) {
                p.connection.teleport(before.x(), before.y(), before.z(), p.getYRot(), p.getXRot());
                LAST.put(p.getUUID(), before.withRotation(p.getYRot(), p.getXRot()));
                continue;
            }
            LAST.put(p.getUUID(), now);
            if (before.blockX() != now.blockX() || before.blockY() != now.blockY() || before.blockZ() != now.blockZ()) {
                PLAYER_MOVE_BLOCK.invoker().moved(p, before, now);
            }
        }
    }
}
