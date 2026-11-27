package net.axther.aftrhrs.core;

import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Small service interfaces through which core (and modules) ask modules that may be off or not ported yet. A module
 * registers its answer in {@code enable()} with {@code feather.provide(Hooks.Afk.class, this::isAfk)}; callers go
 * through the {@link Feather} shortcuts ({@link Feather#isAfk}, {@link Feather#authPending}, ...) or
 * {@code feather.service(Hooks.X.class)} (null when nobody provides it). Everything resets when the server stops.
 */
public final class Hooks {
    private Hooks() {
    }

    /** The auth gate (modules.auth): true while the player still has to type the server code. */
    @FunctionalInterface
    public interface Auth {
        boolean pending(ServerPlayer player);
    }

    /** AFK status (modules.afk). */
    @FunctionalInterface
    public interface Afk {
        boolean isAfk(ServerPlayer player);

        /** The note given with {@code /afk <message>}, or "" (messaging shows it to people who write to them). */
        default String reason(ServerPlayer player) {
            return "";
        }
    }

    /** Vanish (moderation/staff): whether {@code viewer} can see {@code target} (Bukkit's {@code canSee}). */
    @FunctionalInterface
    public interface Vanish {
        boolean canSee(ServerPlayer viewer, ServerPlayer target);
    }

    /** Bank robbery: no teleports, pearls, elytra or mounts but horses while true (docs/bank-robbery.md). */
    @FunctionalInterface
    public interface TravelLock {
        boolean travelLocked(ServerPlayer player);
    }

    /** Installed by the advancements module; see {@link Feather#advance}. */
    @FunctionalInterface
    public interface Advancer {
        void award(ServerPlayer player, String trigger, String target);
    }

    /** Analytics counters; see {@link Feather#stat}. */
    @FunctionalInterface
    public interface Stats {
        void increment(UUID player, String stat, long amount);
    }

    /** Server mail (modules.mail); see {@link Feather#mailLetter} and {@link Feather#mailItems}. */
    public interface Mail {
        boolean systemLetter(UUID to, String fromName, String body);

        boolean systemGift(UUID to, String fromName, String note, List<ItemStack> items);
    }

    /** /ignore (moderation): whether {@code receiver} ignores {@code sender}. */
    @FunctionalInterface
    public interface Ignores {
        boolean ignores(ServerPlayer receiver, ServerPlayer sender);
    }

    /** Chat gate (moderation): true while the player can't talk in public chat (muted, or /mutechat locked it). */
    @FunctionalInterface
    public interface ChatGate {
        boolean silenced(ServerPlayer player);
    }

    /** Chest locks: whether a lock on this block keeps {@code who} out. */
    @FunctionalInterface
    public interface Locks {
        boolean lockedAgainst(ServerLevel level, BlockPos pos, UUID who);
    }

    /** The bank: true for a bank chest, which other modules (chest locks) must leave alone. */
    @FunctionalInterface
    public interface BankBlocks {
        boolean isBankBlock(ServerLevel level, BlockPos pos);
    }

    /**
     * The bank (modules.bank): Goob Coin balances other modules pay into or charge (quests, calendar, chat games, shop,
     * stalls, fishing, farming, bounties...). Main thread only. Every change writes a history line of {@code type}
     * (REWARD, SHOP, STALL, SELLBACK, ...) plus a log line and saves at once. Look it up with
     * {@code feather.service(Hooks.Bank.class)} (null while the bank is off).
     */
    public interface Bank {
        /** False while the bank can't move money (its data file couldn't be read); rewards should wait then. */
        boolean isOpen();

        /** A player's balance (0 without an account). */
        long balance(UUID player);

        /** The highest balance anyone may have ({@code max-balance}). */
        long maxBalance();

        /** Whether {@code amount} more coins fit in this player's balance (and the bank is open). */
        boolean canCredit(UUID player, long amount);

        /** Pays coins into a balance; the new balance, or -1 (nothing paid) when closed, not positive or over max-balance. */
        long credit(UUID player, String name, long amount, String type, String note);

        /** Pays coins as a REWARD history line with {@code reason} as the note; false when nothing was paid. */
        default boolean credit(UUID player, String name, long amount, String reason) {
            return credit(player, name, amount, "REWARD", reason == null ? "" : reason) >= 0;
        }

        /** Takes coins from a balance; the new balance, or -1 (nothing taken) when closed, not positive or too poor. */
        long debit(UUID player, String name, long amount, String type, String note);

        /** Moves coins between two balances in one step; false (nothing moved) when it can't. */
        boolean transfer(UUID from, String fromName, UUID to, String toName, long amount, String type, String noteFrom, String noteTo);

        /** Puts coins into the vault reserve (a coin tossed into a fountain); false when closed or not positive. */
        boolean depositToReserve(long amount, String source, String note);

        /** True for a Goob Coin (the native coin item, an old Oraxen coin or the tagged fallback coin). */
        boolean isCoin(ItemStack stack);

        /** A stack of {@code count} Goob Coins. */
        ItemStack coin(int count);

        /** True while this player is robbing the bank (no trading, shopping, selling). */
        boolean robbing(ServerPlayer player);

        /** True when this death belongs to a bank robbery: items drop on the ground (no grave). */
        boolean robberyDeath(ServerPlayer player);

        /** Where the bank chests are (block centers). */
        List<Loc> bankChests();

        /** For bank holograms: "closed", "robbery", "cooldown" (until {@link #robbableAt}) or "open". */
        String chestState();

        /** When the vault can be robbed again (epoch ms; in the past when it can be now). */
        long robbableAt();

        /** Opens the bank screen (what /bank does). */
        void openScreen(ServerPlayer player);
    }

    /** The {@code <bounty>} placeholder (modules.bounties). */
    @FunctionalInterface
    public interface BountyTag {
        Component tag(ServerPlayer player);
    }

    /** A player's homes, warps, death point or graves changed (null: everyone's): the Companion's places list follows. */
    @FunctionalInterface
    public interface PlacesChanged {
        void changed(UUID player);
    }

    /**
     * Feather mailbox blocks (modules.mail): blocks players can't open as containers. The storage network leaves them
     * out of every network ({@code feather.service(Hooks.Mailboxes.class)}, null while mail is off).
     */
    @FunctionalInterface
    public interface Mailboxes {
        boolean isMailbox(ServerLevel level, BlockPos pos);
    }

    /** Custom recipes changed: the Companion's JEI/REI list follows ({@link Feather#recipesUpdated}). */
    @FunctionalInterface
    public interface RecipesChanged {
        void changed();
    }

    /**
     * Quests and the weekly server goal (modules.quests). Other modules report what players did with
     * {@code feather.service(Hooks.Quests.class)} (null while quests are off). Types keep Feather's names: "mine", "kill",
     * "tame", "codex", "bond", "boss", "deposit", "visit", "fish", "harvest", "scythe", "undead_cull", ... (QuestPool.TYPES).
     */
    public interface Quests {
        /** {@code target}: a block/item/entity id, species or boss type (null or "" when it doesn't matter). Server thread. */
        void progress(ServerPlayer player, String type, String target, int amount);

        /** {@link #progress(ServerPlayer, String, String, int)} counting {@code key} (an entity id, a friend) once per quest. */
        void progress(ServerPlayer player, String type, String target, int amount, String key);

        /** Coins taken out of the bank: deposit quests and a deposit goal lose that much progress. */
        void withdrew(ServerPlayer player, long amount);

        /** This week's server goal progress, 0-100, or -1 while the goal is off or none runs (civic fountains). */
        int goalPercent();

        /** Pet bond XP after a running server-goal bond boost (pets ask for every gain). */
        int bondXp(int amount);

        /** An altar boss died; {@code damage}: who fought and how much damage each dealt. */
        void bossDefeated(String boss, java.util.Map<UUID, Double> damage);
    }

    /** Fishing (modules.fishing): the rarity of a fish id ("common" ... "legendary", null = none) for #rare quest targets. */
    @FunctionalInterface
    public interface FishRarity {
        String rarity(String fishId);
    }

    /** The boss board (modules.boss-board): altar boss kills and their display names. */
    public interface BossBoard {
        /** Display names of the altar bosses by mob type (MiniMessage allowed). */
        void bosses(java.util.Map<String, String> names);

        /** A won fight: who fought with their share of the damage (0-1) and how long it took. */
        void recordKill(String boss, String bossName, long startedAt, long endedAt, List<Hunter> party);

        record Hunter(UUID id, String name, double share) {
        }
    }

    /** The spawn point (modules.spawn-warps-back): /setspawn's spot, else the server's spawn. Graves and altars use it. */
    @FunctionalInterface
    public interface Spawn {
        Loc spawn();
    }

    /**
     * Claims (modules.claims): whether this player may set a home here ({@code homes-need-access}). The provider tells
     * the player why not ({@code claims.home-blocked}). Without a provider homes may be set anywhere.
     */
    @FunctionalInterface
    public interface HomeGuard {
        boolean homeAllowed(ServerPlayer player, Loc where);
    }

    /**
     * Graves (modules.graves), for the Companion places list and /deathpoint: a player's graves, the extra line under a
     * death point and where Track should lead for one (its grave). Provided by the graves port.
     */
    public interface Graves {
        List<GravePlace> of(UUID owner);

        /** A line about this death's grave for the /deathpoint menu, or null. */
        Component deathPointLine(ServerPlayer player, Profiles.DeathPoint death);

        /** Where Track should lead for this death (its grave, block center), or null for the death point itself. */
        Loc trackTarget(UUID owner, Profiles.DeathPoint death);

        /** A grave as a place: its number and block position, {@code world} as Paper named it ("world", ...). */
        record GravePlace(int id, String world, double x, double y, double z) {
        }
    }

    /**
     * The backpack worn on the back (relics, {@code feature.relics.BackpackWear}), for graves: a death puts it into the
     * grave with the inventory, and Take All puts it back on. Provided by the relics module.
     */
    public interface BackSlot {
        /** The worn backpack (the stack itself), or EMPTY. */
        ItemStack worn(ServerPlayer player);

        /** Takes it off (it is in the grave now). */
        void clear(ServerPlayer player);

        /** Puts {@code stack} on the back if it is a backpack and the back is free; false otherwise. */
        boolean wear(ServerPlayer player, ItemStack stack);
    }

    /**
     * Cosmetic armor and the hat slot (cosmetics, {@code feature.cosmetics.Cosmetics}), for graves: a death puts the
     * outfit's items into the grave, and Take All puts them back on. Provided by the cosmetics module.
     */
    public interface Outfit {
        /** The outfit's items in order (hat, head, chest, legs, feet; copies, EMPTY where none or Curse of Vanishing). */
        java.util.List<ItemStack> worn(ServerPlayer player);

        /** Takes the items off (they are in the grave now); the hide switches stay. */
        void clear(ServerPlayer player);

        /** Puts {@code stack} into outfit slot {@code index} if it fits and the slot is free; false otherwise. */
        boolean wear(ServerPlayer player, int index, ItemStack stack);
    }

    /**
     * Pets (modules.pets), for quest/calendar/chat-game rewards, quest picks and pet advancements. The pets port
     * provides it; while nobody does, "tame", "bond" and "codex" quests aren't handed out, bond XP rewards are skipped and
     * the pets-count / pet-level advancements wait.
     */
    public interface PetProgress {
        /** Codex species this player hasn't found yet (a codex quest needs at least its amount left). */
        int codexUnseen(UUID player);

        /** Gives bond XP to the pets following the player; how many pets got it. */
        int addBondToFollowers(ServerPlayer player, int xp);

        /** Pets the player has out (active), for the pets-count advancement. */
        int countActive(UUID player);

        /** The highest bond level among the player's pets, for the pet-level advancement. */
        int topBondLevel(UUID player);
    }

    /** Pets (modules.pets): the pet cap and the pet whistle, for modules that need them (tools, hub). */
    public interface Pets {
        /** A player's pet cap ({@code Integer.MAX_VALUE} = unlimited). */
        int cap(ServerPlayer player);

        /** The pet whistle: signals the player's pets and fetches following ones from other worlds; how many heard it. */
        int whistle(ServerPlayer player);
    }

    /** Pets (modules.pets): Work Here jobs other modules ask about (farming's Sporelings). */
    public interface PetWork {
        /** True while this creature is a pet told to Work Here. */
        boolean working(net.minecraft.world.entity.Entity creature);

        /** Loaded entities of every pet of this species (entity id path, e.g. "sporeling") told to Work Here, any owner. */
        List<net.minecraft.world.entity.Entity> workingEntities(String species);

        /** Where every living pet of this species was told to Work Here (loaded or not). */
        List<Loc> workingAt(String species);
    }

    /** Farming (modules.farming): what the pets' Work Here farmhand job needs for Arcana crops (vanilla crops work without it). */
    public interface Farmhand {
        boolean ripe(ServerLevel level, BlockPos pos);

        /** Harvests and replants a ripe crop; the drops, or null if it couldn't. */
        List<ItemStack> harvest(ServerLevel level, BlockPos pos);

        int containerRadius();

        double seedFindChance();

        /** A found seed, or EMPTY. */
        ItemStack seedFind();

        /** A farmhand run harvested something (the worker, loaded): farming plays the Canopip replant signal. */
        default void worked(net.minecraft.world.entity.Entity worker) {
        }
    }

    /**
     * The wildlife bestiary (seen / harvested / slain wild creatures and the coin rewards of its biome pages). Its menu
     * is part of the creature codex: the codex shows each wild creature's bestiary line and opens the page list from
     * its Wildlife tab ({@code back} reopens the codex).
     */
    public interface Bestiary {
        void openBestiary(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back);

        /** This player's bestiary line for a creature (MythicMobs id of the adult), or null when the bestiary doesn't list it. */
        default BestiaryMob bestiaryMob(UUID player, String mob) {
            return null;
        }

        /** The bestiary's pages (biome families) with this player's progress, in order. */
        default List<BestiaryPage> bestiaryPages(UUID player) {
            return List.of();
        }

        record BestiaryMob(boolean seen, int harvested, int slain, String page) {
        }

        record BestiaryPage(String id, String name, int done, int total, long reward, boolean paid) {
        }
    }

    /**
     * The creature codex (kept by the pets module): every Arcana creature, with discovery, taming and the taming guide's
     * text. Its front doors ({@code /codex}, {@code /tamingguide}, the hub button, the taming guide item, {@code /bestiary})
     * open it through here.
     */
    public interface Codex {
        /**
         * Opens the codex: the mod's screen for modded players, else the dialogs. {@code category} (a codex.yml category,
         * e.g. "wildlife") and {@code species} (a MythicMobs id) pick what it opens on; both may be null. {@code back}:
         * where the dialogs' bottom button goes (null closes).
         */
        void openCodex(ServerPlayer player, String category, String species, java.util.function.Consumer<ServerPlayer> back);

        /** Re-reads the codex text (guide.yml, codex.yml). */
        void reloadCodex();

        /** What {@code /codex <arg>} accepts, for tab completion: tab and realm ids, then creature ids ("emberkit"). */
        default List<String> completions() {
            return List.of();
        }
    }

    /** Boss altars (modules.altars): the arenas of running fights, which graves keep out of (graves.keep-clear.altar-fights). */
    public interface AltarArenas {
        /** Centers of the arenas with a fight going on. */
        List<Loc> arenaCenters();

        /** The arena radius in blocks. */
        double arenaRadius();
    }

    /**
     * Fishing hotspots (modules.wildlife): a fed Squallgull dives at open water near its feeder's boat and the patch
     * bites faster for a while. Fishing asks when a fish bites. Safe from any thread.
     */
    public interface WildlifeHotspots {
        /** True when {@code pos} (a bobber, a player) is inside a live hotspot in {@code level}. */
        boolean isHotspot(ServerLevel level, net.minecraft.world.phys.Vec3 pos);

        /** Bite-time multiplier in a hotspot (1.25 = bites come 25% faster). */
        double biteSpeedMultiplier();

        /** Weight multiplier for rare and better fish in a hotspot. */
        double rareWeightMultiplier();
    }

    /** Farming (modules.farming): Arcana crops for the tools port's scythe (the old ArcanaTools.CropHook). */
    public interface Crops {
        /** True for a planted Arcana crop that is ripe. */
        boolean ripe(ServerLevel level, BlockPos pos);

        /**
         * Harvests a ripe crop as the player breaking it would (claims, drops, quest credit counted as a scythe
         * harvest), replanting it when {@code replant}. False when it wasn't a ripe crop or the player may not.
         */
        boolean harvest(ServerPlayer player, ServerLevel level, BlockPos pos, boolean replant);
    }
}
