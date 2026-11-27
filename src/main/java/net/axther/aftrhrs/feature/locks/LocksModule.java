package net.axther.aftrhrs.feature.locks;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.PlayerPicker;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.recipes.RecipeInjector;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Chest locks: a Chest Key used on a chest, trapped chest or barrel (or {@code /lock}) locks it with a PIN typed on a
 * keypad chest. Others need the PIN; the owner can turn on auto-unlock so they and the players on the lock's allow list
 * open it like a normal chest. Owners manage a lock from a dialog (sneak + right-click it, or {@code /lock}): open,
 * change the PIN, auto-unlock, the allow list (the shared player picker) and removing the lock. A lock on either half
 * of a double chest protects both. Locked blocks survive explosions, can't be drained by hoppers and can only be broken
 * by the owner, staff or someone who just typed the PIN. Data: {@code data/locks.yml} (unchanged from Paper; Paper
 * never kept locks in the block); only a salted SHA-256 hash of each PIN is stored. The key's recipe
 * {@code aftrhrs:chest_key} is built from {@code locks.key.*} while the module runs ({@link RecipeInjector#own}); the
 * key carries custom_data {@code feather:chest_key}, as the old Paper key items do after migration.
 */
public final class LocksModule implements FeatherModule {
    static final String USE = "feather.locks";
    static final String BYPASS = "feather.locks.bypass";
    static final String ADMIN = "feather.locks.admin";
    static final String KEY_TAG = "feather:chest_key";
    static final ResourceKey<Recipe<?>> RECIPE = ResourceKey.create(Registries.RECIPE, AftrHrs.id("chest_key"));

    /** A block position; the map key for locks ({@code world} as Paper named it). */
    record Pos(String world, int x, int y, int z) {
        static Pos of(Level level, BlockPos b) {
            return new Pos(Worlds.name(level), b.getX(), b.getY(), b.getZ());
        }

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** One lock. Lives on one block; for a double chest that one half covers both. */
    static final class Lock {
        Pos pos;
        Block material;
        UUID owner;
        String ownerName;
        String salt;
        String hash;
        int length;
        boolean autoUnlock;
        long created;
        final Set<UUID> allowed = new LinkedHashSet<>();
        /** Who typed the right PIN, until when (epoch ms). Not saved. */
        final Map<UUID, Long> grace = new HashMap<>();
        /** Wrong guesses per player: [count, locked-out-until]. Only a running lockout is saved (a restart ends nothing). */
        final Map<UUID, long[]> attempts = new HashMap<>();
        /** Wrong guesses by anyone since the last strike, and who made them. Not saved. */
        int fails;
        final Set<UUID> failers = new HashSet<>();
        /** Lockouts per player; each one doubles that player's next (saved, so restarts don't reset it). */
        final Map<UUID, Integer> playerStrikes = new HashMap<>();
        /** Lock-wide lockouts so far; each one doubles the next (saved, so restarts don't reset it). */
        int strikes;
        /** While in the future, nobody without free access gets the keypad (saved). */
        long lockedUntil;
        boolean removed;
    }

    private static volatile LocksModule active;

    private final Feather plugin;
    private final LockKeypad keypad;
    private final Map<Pos, Lock> locks = new HashMap<>();
    private final Path file;
    private final SecureRandom random = new SecureRandom();
    /** Players Feather itself is opening a locked container for (past the open backstop). */
    private final Set<UUID> passing = new HashSet<>();
    /** The lockable block each player right-clicked last (the open backstop for double chests). */
    private final Map<UUID, Pos> lastClicked = new HashMap<>();
    /** A locked half being broken right now: where its lock moves (filled before the break, used after it). */
    private final Map<Pos, Pos> breaking = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Feather-Locks-IO");
        t.setDaemon(true);
        return t;
    });

    private Set<Block> lockable = new HashSet<>();
    private int pinLength;
    private boolean requireKey;
    private boolean refundKey;
    private int maxAttempts;
    private long lockoutMs;
    private long graceMs;
    private boolean blockHoppers;
    private boolean blockExplosions;
    private boolean recipeOn;
    /** The recipe settings last applied (null = none yet). */
    private String recipeSig;
    /** locks.yml exists but couldn't be read: never save over it. */
    private boolean unreadable;

    public LocksModule(Feather plugin) {
        this.plugin = plugin;
        this.keypad = new LockKeypad(plugin);
        this.file = plugin.dataFile("locks.yml");
    }

    @Override
    public String name() {
        return "Chest locks";
    }

    @Override
    public void enable() {
        load();
        reload();
        active = this;
        plugin.once("locks.events", LocksModule::registerEvents);
        plugin.bind("lock", command("lock"));
        plugin.bind("unlock", command("unlock"));
        plugin.provide(Hooks.Locks.class, this::lockedAgainst);
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        Set<Block> blocks = new HashSet<>();
        for (String id : c.getStringList("locks.lockable")) {
            Block b = block(id);
            if (b == null) plugin.getLogger().warn("config.yml locks.lockable: unknown block {}", id);
            else blocks.add(b);
        }
        lockable = blocks;
        pinLength = Math.max(4, Math.min(8, c.getInt("locks.pin-length", 4)));
        requireKey = c.getBoolean("locks.require-key", true);
        refundKey = c.getBoolean("locks.refund-key", true);
        maxAttempts = Math.max(1, c.getInt("locks.max-attempts", 5));
        lockoutMs = Math.max(0, c.getLong("locks.lockout-seconds", 60)) * 1000L;
        graceMs = Math.max(0, c.getLong("locks.grace-seconds", 30)) * 1000L;
        blockHoppers = c.getBoolean("locks.block-hoppers", true);
        blockExplosions = c.getBoolean("locks.block-explosions", true);
        updateRecipe(c);
        if (recipeOn) for (ServerPlayer p : plugin.onlinePlayers()) unlockRecipe(p);
    }

    /**
     * The Chest Key recipe {@code aftrhrs:chest_key}: shapeless, {@code locks.key.ingredients}, giving the key item
     * ({@code locks.key.material} + messages.yml {@code locks.key.name/lore}). Rebuilt only when those settings changed
     * (re-registering resends every player's recipe list, as on Paper).
     */
    private void updateRecipe(Yaml c) {
        boolean on = c.getBoolean("locks.key.recipe", true);
        List<String> ingredients = c.getStringList("locks.key.ingredients");
        String sig = on + "|" + ingredients + "|" + c.getString("locks.key.material", "tripwire_hook") + "|"
                + plugin.text().raw("locks.key.name") + "|" + plugin.text().raw("locks.key.lore");
        if (sig.equals(recipeSig)) return;
        recipeSig = sig;
        RecipeHolder<?> holder = null;
        if (on) {
            List<Ingredient> list = new ArrayList<>();
            for (String id : ingredients) {
                Identifier rl = id == null ? null : Identifier.tryParse(id.toLowerCase(Locale.ROOT).contains(":") ? id.toLowerCase(Locale.ROOT)
                        : "minecraft:" + id.toLowerCase(Locale.ROOT));
                net.minecraft.world.item.Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
                if (item == null || item == net.minecraft.world.item.Items.AIR) {
                    plugin.getLogger().warn("config.yml locks.key.ingredients: unknown item {}", id);
                    continue;
                }
                list.add(Ingredient.of(item));
            }
            if (list.size() > 9) {
                plugin.getLogger().warn("config.yml locks.key.ingredients: more than 9 items, the recipe is off");
                list.clear();
            }
            if (!list.isEmpty()) {
                holder = new RecipeHolder<>(RECIPE, new ShapelessRecipe(new Recipe.CommonInfo(true),
                        new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                        ItemStackTemplate.fromNonEmptyStack(keyItem(1)), list));
            }
        }
        recipeOn = holder != null;
        RecipeInjector.own(RECIPE, holder);
    }

    @Override
    public void disable() {
        plugin.withdraw(Hooks.Locks.class);
        // Module off: no Chest Key recipe, as on Paper (Bukkit.removeRecipe).
        RecipeInjector.own(RECIPE, null);
        recipeSig = null;
        recipeOn = false;
        save();
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warn("Saving locks.yml timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (active == this) active = null;
    }

    private static Block block(String id) {
        if (id == null) return null;
        String s = id.toLowerCase(Locale.ROOT);
        Identifier rl = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        Block b = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        return b == null || b == Blocks.AIR ? null : b;
    }

    private static String blockId(Block b) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    private void unlockRecipe(ServerPlayer p) {
        try {
            p.awardRecipesByKey(List.of(RECIPE));
        } catch (RuntimeException ignored) {
            // no such recipe (datapack disabled)
        }
    }

    // ------------------------------------------------------------------ the key item

    ItemStack keyItem(int amount) {
        net.minecraft.world.item.Item mat = net.minecraft.world.item.Items.TRIPWIRE_HOOK;
        String id = plugin.getConfig().getString("locks.key.material", "tripwire_hook");
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl != null) mat = BuiltInRegistries.ITEM.getOptional(rl).filter(i -> i != net.minecraft.world.item.Items.AIR).orElse(mat);
        Text text = plugin.text();
        ItemStack stack = net.axther.aftrhrs.core.Icons.icon(mat).name(text.itemMsg("locks.key.name")).lore(text.lines("locks.key.lore")).build();
        stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), amount)));
        Pdc.itemPut(stack, KEY_TAG, net.minecraft.nbt.ByteTag.valueOf((byte) 1));
        return stack;
    }

    static boolean isKey(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        var d = item.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return d != null && d.copyTag().contains(KEY_TAG);
    }

    private boolean hasKey(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (isKey(inv.getItem(i))) return true;
        return false;
    }

    /** Takes one key, from the main hand first. */
    private boolean takeKey(ServerPlayer p) {
        ItemStack hand = p.getMainHandItem();
        if (isKey(hand)) {
            hand.shrink(1);
            return true;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!isKey(s)) continue;
            s.shrink(1);
            inv.setChanged();
            return true;
        }
        return false;
    }

    private void giveKeys(ServerPlayer p, int amount) {
        while (amount > 0) {
            int n = Math.min(amount, 64);
            amount -= n;
            ItemStack stack = keyItem(n);
            if (!p.getInventory().add(stack) && !stack.isEmpty()) net.minecraft.world.Containers.dropItemStack(p.level(), p.getX(), p.getY(), p.getZ(), stack);
        }
    }

    // ------------------------------------------------------------------ finding locks

    boolean lockable(Level level, BlockPos pos) {
        return lockable(level, pos, level.getBlockState(pos));
    }

    boolean lockable(Level level, BlockPos pos, BlockState state) {
        // Bank chests belong to the bank module (indestructible, no locks).
        if (!lockable.contains(state.getBlock()) || !(level instanceof ServerLevel sl)) return false;
        if (plugin.isBankBlock(sl, pos)) return false;
        BlockPos other = partner(level, pos, state);
        return other == null || !plugin.isBankBlock(sl, other);
    }

    /** The other half of a double chest, or null. */
    static BlockPos partner(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        Direction d = ChestBlock.getConnectedDirection(state);
        BlockPos other = pos.relative(d);
        return level.getBlockState(other).getBlock() == state.getBlock() ? other : null;
    }

    /** The lock on this block or on the other half of its double chest. */
    Lock lockAt(Level level, BlockPos pos) {
        if (locks.isEmpty() || level == null || pos == null) return null;
        Lock l = live(level, pos);
        if (l != null) return l;
        BlockPos other = partner(level, pos, level.getBlockState(pos));
        return other == null ? null : live(level, other);
    }

    /** The lock stored exactly on this block; drops it if the block was replaced some other way (e.g. /setblock). */
    private Lock live(Level level, BlockPos pos) {
        Lock l = locks.get(Pos.of(level, pos));
        if (l == null) return null;
        if (!level.hasChunkAt(pos)) return l;
        if (level.getBlockState(pos).getBlock() != l.material) {
            plugin.getLogger().info("Dropping the lock at {}: the {} is gone.", where(l), blockId(l.material));
            removeLock(l);
            return null;
        }
        return l;
    }

    private ServerLevel level(Lock l) {
        return Worlds.resolve(l.pos.world());
    }

    /** True when this block carries a chest lock that {@code who} neither owns nor is allowed on. */
    boolean lockedAgainst(ServerLevel level, BlockPos pos, UUID who) {
        Lock l = lockAt(level, pos);
        // Allowed players skip the PIN only on auto-unlock locks (as at the chest itself): terminals and pets too.
        return l != null && !l.owner.equals(who) && !(l.autoUnlock && l.allowed.contains(who));
    }

    boolean isOwner(ServerPlayer p, Lock l) {
        return l.owner.equals(p.getUUID());
    }

    /** The owner (or a manager) of the claim the lock stands in: may remove or break it, not open it. */
    private boolean claimHolder(ServerPlayer p, Lock l) {
        ServerLevel w = level(l);
        if (w == null) return false;
        Loc at = Loc.of(w, l.pos.pos());
        return plugin.claimed(at) && plugin.claimAllows(p, at, ClaimAction.MANAGE);
    }

    private boolean inGrace(ServerPlayer p, Lock l) {
        Long until = l.grace.get(p.getUUID());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            l.grace.remove(p.getUUID());
            return false;
        }
        return true;
    }

    /** Whether a right-click opens it without the keypad. */
    private boolean opensFreely(ServerPlayer p, Lock l) {
        if (inGrace(p, l)) return true;
        if (l.autoUnlock && (isOwner(p, l) || l.allowed.contains(p.getUUID()))) return true;
        return !isOwner(p, l) && Perms.has(p, BYPASS);
    }

    private long lockedOutFor(ServerPlayer p, Lock l) {
        // The lock-wide lockout never shuts out the owner or the people they trust.
        long all = isOwner(p, l) || l.allowed.contains(p.getUUID()) ? 0 : l.lockedUntil - System.currentTimeMillis();
        if (all > 0) return all;
        long[] a = l.attempts.get(p.getUUID());
        if (a == null || a[1] == 0) return 0;
        long left = a[1] - System.currentTimeMillis();
        if (left <= 0) {
            l.attempts.remove(p.getUUID());
            return 0;
        }
        return left;
    }

    // ------------------------------------------------------------------ PINs

    private String newSalt() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] digest(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(salt));
            md.update(pin.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setPin(Lock l, String pin) {
        l.salt = newSalt();
        l.hash = Base64.getEncoder().encodeToString(digest(l.salt, pin));
        l.length = pin.length();
    }

    private static boolean matches(Lock l, String pin) {
        try {
            return MessageDigest.isEqual(digest(l.salt, pin), Base64.getDecoder().decode(l.hash));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ flows

    /** Whether claims, spawn protection and the like let this player change this block (the old synthetic-click probe). */
    private boolean mayBuild(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (Feather.server().isUnderSpawnProtection(level, pos, p)) return false;
        return plugin.claimAllows(p, Loc.of(level, pos), ClaimAction.BUILD);
    }

    private static boolean near(ServerPlayer p, ServerLevel level, BlockPos b) {
        return p.level() == level && Vec3.atCenterOf(b).distanceToSqr(p.getEyePosition()) <= 64;
    }

    private void startCreate(ServerPlayer p, ServerLevel level, BlockPos b) {
        Text text = plugin.text();
        if (!Perms.has(p, USE)) {
            text.send(p, "general.no-permission");
            return;
        }
        // Locking needs build rights here (the claim owner or build trust; anyone in unclaimed land).
        if (!mayBuild(p, level, b)) {
            text.send(p, "locks.no-access");
            text.sound(p, "error");
            return;
        }
        if (requireKey && !hasKey(p)) {
            text.send(p, "locks.need-key");
            text.sound(p, "error");
            return;
        }
        choosePin(p, pinLength, "locks.gui.info-create", pin -> {
            if (p.hasDisconnected() || !near(p, level, b) || !mayBuild(p, level, b)) {
                text.send(p, p.hasDisconnected() || near(p, level, b) ? "locks.no-access" : "locks.too-far");
                text.sound(p, "error");
                return;
            }
            Lock existing = lockAt(level, b);
            if (!lockable(level, b) || existing != null) {
                text.send(p, existing != null ? "locks.already-locked" : "locks.not-lockable",
                        Text.unparsed("owner", existing != null ? existing.ownerName : ""));
                text.sound(p, "error");
                return;
            }
            boolean usedKey = false;
            if (requireKey) {
                if (!takeKey(p)) {
                    text.send(p, "locks.need-key");
                    text.sound(p, "error");
                    return;
                }
                usedKey = true;
            }
            Lock l = new Lock();
            l.pos = Pos.of(level, b);
            l.material = level.getBlockState(b).getBlock();
            l.owner = p.getUUID();
            l.ownerName = Names.name(p);
            l.created = System.currentTimeMillis();
            setPin(l, pin);
            locks.put(l.pos, l);
            save();
            text.send(p, usedKey ? "locks.set" : "locks.set-no-key");
            text.sound(p, "lock-set");
            plugin.advance(p, "event", "lock");
        });
    }

    /** Set + confirm keypads; {@code done} gets the confirmed PIN (keypad already closed). */
    private void choosePin(ServerPlayer p, int length, String infoKey, java.util.function.Consumer<String> done) {
        Text text = plugin.text();
        keypad.open(p, text.msg("locks.gui.title-set"), length, text.itemMsg(infoKey),
                text.lines("locks.gui.info-set-lore", Text.unparsed("length", length)), (pl, s1, first) -> {
                    keypad.open(pl, text.msg("locks.gui.title-confirm"), length, text.itemMsg("locks.gui.info-confirm"),
                            text.lines("locks.gui.info-confirm-lore", Text.unparsed("length", length)), (pl2, s2, second) -> {
                                if (!first.equals(second)) {
                                    text.send(pl2, "locks.mismatch");
                                    text.sound(pl2, "lock-wrong");
                                    choosePin(pl2, length, infoKey, done);
                                    return;
                                }
                                pl2.closeContainer();
                                done.accept(second);
                            });
                });
    }

    /** Someone without free access asks to open it: the PIN keypad. */
    private void askPin(ServerPlayer p, Lock l) {
        Text text = plugin.text();
        long out = lockedOutFor(p, l);
        if (out > 0) {
            text.send(p, "locks.locked-out", Text.unparsed("seconds", (out + 999) / 1000));
            text.sound(p, "error");
            return;
        }
        keypad.open(p, text.msg("locks.gui.title-unlock"), l.length, text.itemMsg("locks.gui.info-unlock"),
                text.lines("locks.gui.info-unlock-lore", Text.unparsed("owner", l.ownerName), Text.unparsed("length", l.length)),
                (pl, session, pin) -> {
                    if (l.removed) {
                        pl.closeContainer();
                        text.send(pl, "locks.gone");
                        return;
                    }
                    if (lockedOutFor(pl, l) > 0) {
                        pl.closeContainer();
                        text.send(pl, "locks.locked-out", Text.unparsed("seconds", (lockedOutFor(pl, l) + 999) / 1000));
                        return;
                    }
                    if (matches(l, pin)) {
                        l.attempts.remove(pl.getUUID());
                        l.fails = 0;
                        l.failers.clear();
                        if (l.playerStrikes.remove(pl.getUUID()) != null) save();
                        if (l.strikes > 0 || l.lockedUntil > 0) {
                            l.strikes = 0;
                            l.lockedUntil = 0;
                            save();
                        }
                        if (graceMs > 0) l.grace.put(pl.getUUID(), System.currentTimeMillis() + graceMs);
                        text.send(pl, "locks.correct");
                        text.sound(pl, "lock-open");
                        openContainer(pl, l);
                        return;
                    }
                    long[] a = l.attempts.computeIfAbsent(pl.getUUID(), k -> new long[2]);
                    a[0]++;
                    l.fails++;
                    l.failers.add(pl.getUUID());
                    text.sound(pl, "lock-wrong");
                    // Lock-wide: many wrong guesses from several different players (taking turns to dodge the
                    // per-player limit) lock the keypad for everyone but the owner and the allow list, each time twice
                    // as long, up to an hour. One player alone only ever locks themself out. A right PIN resets it.
                    if (l.fails >= maxAttempts * 2 && l.failers.size() >= 2) {
                        l.fails = 0;
                        l.failers.clear();
                        l.strikes++;
                        long span = Math.min(3_600_000L, Math.max(lockoutMs, 1000L) << Math.min(l.strikes - 1, 12));
                        l.lockedUntil = System.currentTimeMillis() + span;
                        save();
                    }
                    if (a[0] >= maxAttempts || lockedOutFor(pl, l) > 0) {
                        // Per player: every lockout doubles that player's next one, up to a day, so one player
                        // alone can't keep guessing at a steady rate. Saved with the lock.
                        long span = 0;
                        if (a[0] >= maxAttempts) {
                            int strikes = l.playerStrikes.merge(pl.getUUID(), 1, Integer::sum);
                            span = Math.min(86_400_000L, Math.max(lockoutMs, 1000L) << Math.min(strikes - 1, 16));
                        }
                        a[0] = 0;
                        a[1] = Math.max(System.currentTimeMillis() + span, isOwner(pl, l) || l.allowed.contains(pl.getUUID()) ? 0 : l.lockedUntil);
                        if (span > 0) save();   // the strike and the lockout's end, so a restart doesn't lift it
                        pl.closeContainer();
                        text.send(pl, "locks.locked-out", Text.unparsed("seconds", (lockedOutFor(pl, l) + 999) / 1000));
                        ServerPlayer owner = plugin.player(l.owner);
                        if (owner != null && owner != pl) {
                            text.send(owner, "locks.owner-alert", Text.component("player", Names.display(pl)), Text.unparsed("where", where(l)));
                            text.sound(owner, "error");
                        }
                        return;
                    }
                    text.send(pl, "locks.wrong", Text.unparsed("left", maxAttempts - a[0]));
                    keypad.clear(session);
                });
    }

    /** Opens the container's inventory (the whole double chest). */
    private void openContainer(ServerPlayer p, Lock l) {
        ServerLevel level = level(l);
        BlockPos b = l.pos.pos();
        MenuProvider provider = level == null || l.removed ? null : level.getBlockState(b).getMenuProvider(level, b);
        if (provider == null) {
            p.closeContainer();
            plugin.text().send(p, "locks.gone");
            return;
        }
        if (!near(p, level, b)) {
            p.closeContainer();
            plugin.text().send(p, "locks.too-far");
            return;
        }
        passing.add(p.getUUID());
        try {
            p.openMenu(provider);
        } finally {
            passing.remove(p.getUUID());
        }
    }

    private void changePin(ServerPlayer p, Lock l) {
        Text text = plugin.text();
        choosePin(p, pinLength, "locks.gui.info-change", pin -> {
            if (l.removed) {
                text.send(p, "locks.gone");
                return;
            }
            setPin(l, pin);
            l.grace.clear();
            l.attempts.clear();
            l.fails = 0;
            l.failers.clear();
            l.playerStrikes.clear();
            l.strikes = 0;
            l.lockedUntil = 0;
            save();
            text.send(p, "locks.changed");
            text.sound(p, "lock-set");
        });
    }

    private void setAuto(ServerPlayer p, Lock l, boolean on) {
        l.autoUnlock = on;
        save();
        plugin.text().send(p, on ? "locks.auto-on" : "locks.auto-off");
        plugin.text().sound(p, "click");
    }

    private void toggleAllowed(ServerPlayer p, Lock l, UUID id) {
        if (l.removed) return;
        if (!l.allowed.remove(id)) {
            l.allowed.add(id);
            plugin.advance(p, "event", "lock-share");
        }
        save();
    }

    void openPicker(ServerPlayer p, Lock l) {
        plugin.picker().open(p, new PlayerPicker.Request(plugin.text().msg("locks.gui.picker-title"),
                () -> l.allowed, id -> toggleAllowed(p, l, id), Set.of(l.owner), () -> {
                    if (!l.removed) openManage(p, l);
                }), 0);
    }

    /** Removes a lock; refunds the key to the owner when they remove it themselves. */
    private void remove(ServerPlayer actor, Lock l, boolean viaBreak) {
        removeLock(l);
        boolean refund = refundKey && actor != null && isOwner(actor, l);
        if (refund) giveKeys(actor, 1);
        if (actor != null) {
            plugin.text().send(actor, refund ? "locks.removed-refund" : "locks.removed");
            if (!viaBreak) plugin.text().sound(actor, "deleted");
        }
    }

    private void removeLock(Lock l) {
        l.removed = true;
        locks.remove(l.pos);
        save();
    }

    // ------------------------------------------------------------------ owner dialog

    void openManage(ServerPlayer p, Lock l) {
        Text text = plugin.text();
        boolean owner = isOwner(p, l);
        if (owner && !Names.name(p).equals(l.ownerName)) {
            l.ownerName = Names.name(p);
            save();
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("locks.menu.title"));
        menu.item(new ItemStack(l.material.asItem()), text.msg("locks.menu.info",
                Text.unparsed("owner", l.ownerName), Text.unparsed("where", where(l)),
                Text.component("state", text.msg(l.autoUnlock ? "locks.menu.state-on" : "locks.menu.state-off")),
                Text.unparsed("count", l.allowed.size())), 250);
        boolean bypass = Perms.has(p, BYPASS);
        if (owner) menu.text(text.msg("locks.menu.note"), 300);
        else if (bypass) menu.text(text.msg("locks.menu.staff-note"), 300);
        else menu.text(text.msg("locks.menu.claim-note"), 300);
        final int w = 148;
        if (owner || bypass) {
            menu.button(text.msg("locks.menu.open"), text.msg("locks.menu.open-tooltip"), w, pl -> {
                if (gone(pl, l)) return;
                openContainer(pl, l);
            });
        }
        if (owner) {
            menu.button(text.msg("locks.menu.change-pin"), text.msg("locks.menu.change-pin-tooltip"), w, pl -> {
                if (gone(pl, l)) return;
                changePin(pl, l);
            });
            menu.button(text.msg(l.autoUnlock ? "locks.menu.auto-on" : "locks.menu.auto-off"), text.msg("locks.menu.auto-tooltip"), w, pl -> {
                if (gone(pl, l)) return;
                setAuto(pl, l, !l.autoUnlock);
                openManage(pl, l);
            });
            menu.button(text.msg("locks.menu.allow", Text.unparsed("count", l.allowed.size())), text.msg("locks.menu.allow-tooltip"), w, pl -> {
                if (gone(pl, l)) return;
                openPicker(pl, l);
            });
        }
        if (owner || bypass || claimHolder(p, l)) {
            menu.button(text.msg("locks.menu.remove"), text.msg("locks.menu.remove-tooltip"), w, pl -> {
                if (gone(pl, l)) return;
                confirmRemove(pl, l);
            });
        }
        menu.columns(2);
        menu.exit(text.msg("menu.close"), null);
        menu.open(p);
        text.sound(p, "menu-open");
    }

    private boolean gone(ServerPlayer p, Lock l) {
        if (!l.removed) return false;
        plugin.text().send(p, "locks.gone");
        return true;
    }

    private void confirmRemove(ServerPlayer p, Lock l) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("locks.menu.remove-title"));
        menu.text(text.msg(refundKey && isOwner(p, l) ? "locks.menu.remove-body-refund" : "locks.menu.remove-body",
                Text.unparsed("owner", l.ownerName), Text.unparsed("where", where(l))), 280);
        menu.button(text.msg("locks.menu.remove-yes"), null, 148, pl -> {
            if (gone(pl, l)) return;
            remove(pl, l, false);
        });
        menu.button(text.msg("menu.back"), null, 148, pl -> {
            if (!gone(pl, l)) openManage(pl, l);
        });
        menu.columns(2);
        menu.open(p);
    }

    static String where(Lock l) {
        return l.pos.x() + ", " + l.pos.y() + ", " + l.pos.z() + " (" + prettyWorld(l.pos.world()) + ")";
    }

    static String prettyWorld(String w) {
        return switch (w) {
            case "world" -> "Overworld";
            case "world_nether" -> "Nether";
            case "world_the_end" -> "The End";
            default -> w;
        };
    }

    // ------------------------------------------------------------------ events

    private static void registerEvents() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            LocksModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            return m.onInteract(p, sl, hand, hit);
        });
        FeatherEvents.CONTAINER_OPEN.register((player, provider) -> {
            LocksModule m = active;
            return m == null || m.onOpen(player, provider);
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            LocksModule m = active;
            return m == null || !(player instanceof ServerPlayer p) || m.mayBreak(p, level, pos, state);
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            LocksModule m = active;
            if (m != null && player instanceof ServerPlayer p) m.broken(p, level, pos);
        });
        FeatherEvents.BLOCK_PLACE.register((player, level, pos, state, stack) -> {
            LocksModule m = active;
            return m == null || m.onPlace(player, level, pos, state, stack);
        });
        FeatherEvents.EXPLOSION.register((level, explosion, blocks) -> {
            LocksModule m = active;
            if (m != null) m.onExplode(level, blocks);
        });
        FeatherEvents.ENTITY_CHANGE_BLOCK.register((entity, level, pos, state) -> {
            LocksModule m = active;
            return m == null || m.locks.isEmpty() || !m.lockable(level, pos) || m.lockAt(level, pos) == null;
        });
        FeatherEvents.BLOCK_BURN.register((level, from, to) -> {
            LocksModule m = active;
            return m == null || m.locks.isEmpty() || !m.lockable(level, to) || m.lockAt(level, to) == null;
        });
        FeatherEvents.HOPPER_PULL.register((level, hopper, source) -> {
            LocksModule m = active;
            if (m == null || !m.blockHoppers || m.locks.isEmpty()) return true;
            // The source is the container right above the hopper (block or minecart).
            BlockPos above = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
            return !m.lockable(level, above) || m.lockAt(level, above) == null;
        });
        // Copper golems never sort a locked chest (taking items out or putting them in).
        FeatherEvents.MOB_USE_CONTAINER.register((mob, level, pos) -> {
            LocksModule m = active;
            return m == null || m.locks.isEmpty() || !m.lockable(level, pos) || m.lockAt(level, pos) == null;
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            LocksModule m = active;
            if (m != null && m.recipeOn) m.unlockRecipe(h.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            LocksModule m = active;
            if (m != null) m.lastClicked.remove(h.player.getUUID());
        });
    }

    private InteractionResult onInteract(ServerPlayer p, ServerLevel level, InteractionHand hand, BlockHitResult hit) {
        BlockPos b = hit.getBlockPos();
        BlockState state = level.getBlockState(b);
        if (!lockable(level, b, state)) return InteractionResult.PASS;
        // Someone the claim keeps out is refused by claims (old: useInteractedBlock already DENY) and never sees a lock.
        if (!plugin.claimAllows(p, Loc.of(level, b), ClaimAction.CONTAINER)) return InteractionResult.PASS;
        lastClicked.put(p.getUUID(), Pos.of(level, b));
        Lock l = lockAt(level, b);
        if (hand != InteractionHand.MAIN_HAND) {
            // The off hand only matters if the main hand didn't act; never let it open a lock.
            return l != null && !opensFreely(p, l) ? InteractionResult.FAIL : InteractionResult.PASS;
        }
        ItemStack held = p.getMainHandItem();
        boolean key = isKey(held);
        if (l == null) {
            if (!key) return InteractionResult.PASS;
            startCreate(p, level, b);
            return InteractionResult.FAIL;
        }
        boolean manager = isOwner(p, l) || Perms.has(p, BYPASS) || claimHolder(p, l);
        boolean empty = held.isEmpty();
        if (p.isShiftKeyDown() && manager && (empty || key)) {
            openManage(p, l);
            return InteractionResult.FAIL;
        }
        // Sneaking with something in hand doesn't open containers anyway (it places the item).
        if (p.isShiftKeyDown() && !(empty && p.getOffhandItem().isEmpty()) && !key) return InteractionResult.PASS;
        if (opensFreely(p, l)) {
            if (!isOwner(p, l) && !inGrace(p, l) && !(l.autoUnlock && l.allowed.contains(p.getUUID()))) {
                plugin.text().actionBar(p, "locks.bypassed", Text.unparsed("owner", l.ownerName));
            }
            return InteractionResult.PASS;
        }
        askPin(p, l);
        return InteractionResult.FAIL;
    }

    /** Backstop for other ways of opening a locked container (spectators, other modules). */
    private boolean onOpen(ServerPlayer p, MenuProvider provider) {
        if (locks.isEmpty() || passing.contains(p.getUUID())) return true;
        ServerLevel level = p.level();
        BlockPos pos = null;
        if (provider instanceof BlockEntity be && be.getLevel() == level) {
            pos = be.getBlockPos();
        } else if (provider.getClass().getName().startsWith(ChestBlock.class.getName())) {
            // A double chest's combined menu (an inner class of ChestBlock): the block the player clicked, or looks at.
            // Other menus (Feather's own chest GUIs such as the keypad) are none of this backstop's business.
            Pos last = lastClicked.get(p.getUUID());
            if (last != null && last.world().equals(Worlds.name(level))) pos = last.pos();
            if (pos == null || !lockable(level, pos)) {
                HitResult hit = p.pick(6, 1f, false);
                if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) pos = bh.getBlockPos();
            }
        }
        if (pos == null || !lockable(level, pos)) return true;
        Lock l = lockAt(level, pos);
        if (l == null) return true;
        return isOwner(p, l) || Perms.has(p, BYPASS) || inGrace(p, l) || (l.autoUnlock && l.allowed.contains(p.getUUID()));
    }

    private boolean mayBreak(ServerPlayer p, Level level, BlockPos b, BlockState state) {
        if (locks.isEmpty() || !lockable(level, b, state)) return true;
        Lock l = lockAt(level, b);
        if (l == null) return true;
        if (!isOwner(p, l) && !Perms.has(p, BYPASS) && !inGrace(p, l) && !claimHolder(p, l)) {
            plugin.text().send(p, "locks.cant-break", Text.unparsed("owner", l.ownerName));
            plugin.text().sound(p, "error");
            return false;
        }
        // Where the lock goes if this half breaks (the other half forgets it was double once this one is gone).
        Pos at = Pos.of(level, b);
        if (l.pos.equals(at)) {
            BlockPos other = partner(level, b, state);
            if (other != null) breaking.put(at, Pos.of(level, other));
        }
        return true;
    }

    /** After a lockable block was broken: the lock moves to the other half of a double chest, or goes. */
    private void broken(ServerPlayer p, Level level, BlockPos b) {
        Pos at = Pos.of(level, b);
        Pos other = breaking.remove(at);
        Lock l = locks.get(at);
        if (l == null) return;
        if (other != null && level.getBlockState(other.pos()).getBlock() == l.material) {
            locks.remove(l.pos);
            l.pos = other;
            locks.put(l.pos, l);
            save();
            return;
        }
        remove(p, l, true);
    }

    /** Nobody else may place a chest that joins someone's locked chest; the key is no block to hang on walls. */
    private boolean onPlace(ServerPlayer p, ServerLevel level, BlockPos b, BlockState state, ItemStack stack) {
        if (isKey(stack)) return false;
        if (locks.isEmpty()) return true;
        // A freshly placed block never carries a lock: one left here (its old block was destroyed without us noticing)
        // must not pass to the new chest.
        Lock stale = locks.get(Pos.of(level, b));
        if (stale != null) {
            plugin.getLogger().info("Dropping the lock at {}: a new block was placed there.", where(stale));
            removeLock(stale);
        }
        if (!lockable.contains(state.getBlock())) return true;
        BlockPos other = partner(level, b, state);
        if (other == null) {
            // The placed state may not know its partner yet; look at the chest it would join.
            if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return true;
            other = b.relative(ChestBlock.getConnectedDirection(state));
        }
        Lock l = lockAt(level, other);
        if (l == null || isOwner(p, l) || Perms.has(p, BYPASS)) return true;
        plugin.text().send(p, "locks.join-blocked", Text.unparsed("owner", l.ownerName));
        plugin.text().sound(p, "error");
        return false;
    }

    private void onExplode(ServerLevel level, List<BlockPos> blocks) {
        if (locks.isEmpty()) return;
        if (blockExplosions) {
            blocks.removeIf(b -> lockable(level, b) && lockAt(level, b) != null);
            return;
        }
        // Explosions may destroy locked blocks (block-explosions off): their locks go with them, or move to a half
        // that survives.
        Set<BlockPos> gone = new HashSet<>(blocks);
        for (BlockPos b : blocks) {
            Lock l = locks.get(Pos.of(level, b));
            if (l == null) continue;
            BlockPos other = partner(level, b, level.getBlockState(b));
            if (other != null && !gone.contains(other)) {
                locks.remove(l.pos);
                l.pos = Pos.of(level, other);
                locks.put(l.pos, l);
                save();
            } else {
                removeLock(l);
            }
        }
    }

    // ------------------------------------------------------------------ commands

    private BlockPos target(ServerPlayer p) {
        HitResult hit = p.pick(5, 1f, false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) return null;
        return lockable(p.level(), bh.getBlockPos()) ? bh.getBlockPos() : null;
    }

    private FeatherCommand command(String name) {
        return new FeatherCommand() {
            @Override
            public boolean onCommand(Sender sender, String label, String[] args) {
                run(name, sender, args);
                return true;
            }

            @Override
            public List<String> onTabComplete(Sender sender, String label, String[] args) {
                return complete(name, sender, args);
            }
        };
    }

    private void run(String cmd, Sender sender, String[] args) {
        Text text = plugin.text();
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("givekey")) {
            giveKeyCommand(sender, args);
            return;
        }
        if (!sender.isPlayer()) {
            text.send(sender, "general.players-only");
            return;
        }
        ServerPlayer p = sender.player();
        if (sub.equals("help")) {
            text.send(p, "locks.help");
            return;
        }
        BlockPos b = target(p);
        if (b == null) {
            text.send(p, "locks.not-lockable");
            return;
        }
        Lock l = lockAt(p.level(), b);
        if (cmd.equals("unlock")) sub = "remove";
        if (l == null) {
            if (sub.isEmpty()) startCreate(p, p.level(), b);
            else text.send(p, "locks.not-locked");
            return;
        }
        boolean owner = isOwner(p, l);
        boolean bypass = Perms.has(p, BYPASS) || claimHolder(p, l);
        if (!owner && !(bypass && (sub.isEmpty() || sub.equals("remove")))) {
            text.send(p, "locks.not-yours", Text.unparsed("owner", l.ownerName));
            text.sound(p, "error");
            return;
        }
        switch (sub) {
            case "" -> openManage(p, l);
            case "remove" -> remove(p, l, false);
            case "pin" -> changePin(p, l);
            case "auto" -> {
                boolean on = args.length > 1 ? args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true") : !l.autoUnlock;
                setAuto(p, l, on);
            }
            case "allow", "disallow" -> {
                if (args.length < 2) {
                    if (sub.equals("allow")) openPicker(p, l);
                    else text.send(p, "locks.help");
                    return;
                }
                UUID id = resolve(p, args[1]);
                if (id == null || id.equals(l.owner)) {
                    plugin.notFound(p, args[1]);
                    return;
                }
                String name = args[1];
                OfflinePlayer op = plugin.lookup().byId(id);
                if (op != null && op.name() != null) name = op.name();
                boolean has = l.allowed.contains(id);
                if (sub.equals("allow") != has) toggleAllowed(p, l, id);
                text.send(p, sub.equals("allow") ? "locks.allow-added" : "locks.allow-removed", Text.unparsed("player", name));
                if (sub.equals("allow") && !l.autoUnlock) text.send(p, "locks.allow-auto-off");
                text.sound(p, "click");
            }
            default -> text.send(p, "locks.help");
        }
    }

    private UUID resolve(ServerPlayer viewer, String name) {
        ServerPlayer online = plugin.findPlayer(viewer, name);
        if (online != null) return online.getUUID();
        OfflinePlayer cached = plugin.findOfflinePlayer(viewer, name);
        return cached != null ? cached.id() : null;
    }

    private void giveKeyCommand(Sender sender, String[] args) {
        Text text = plugin.text();
        if (!sender.hasPermission(ADMIN)) {
            text.send(sender, "general.no-permission");
            return;
        }
        ServerPlayer target;
        if (args.length > 1) {
            target = plugin.findPlayer(sender, args[1]);
            if (target == null) {
                plugin.notFound(sender, args[1]);
                return;
            }
        } else if (sender.isPlayer()) {
            target = sender.player();
        } else {
            text.send(sender, "locks.help");
            return;
        }
        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Math.max(1, Math.min(64 * 36, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                text.send(sender, "locks.help");
                return;
            }
        }
        giveKeys(target, amount);
        text.send(sender, "locks.key-given", Text.unparsed("amount", amount), Text.unparsed("player", Names.name(target)));
        if (!sender.isPlayer() || sender.player() != target) text.sound(target, "item-pickup");
    }

    private List<String> complete(String cmd, Sender sender, String[] args) {
        if (cmd.equals("unlock")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("pin", "auto", "allow", "disallow", "remove", "help"));
            if (sender.hasPermission(ADMIN)) out.add("givekey");
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("auto")) out.addAll(List.of("on", "off"));
            else if (sub.equals("allow") || sub.equals("disallow") || (sub.equals("givekey") && sender.hasPermission(ADMIN))) {
                out.addAll(plugin.completePlayers(sender, ""));
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ disk

    private void load() {
        locks.clear();
        broken.clear();
        unreadable = false;
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "lock changes");
        if (yml == null) {
            unreadable = true;
            return;
        }
        for (Map<?, ?> m : yml.getMapList("locks")) {
            try {
                Lock l = new Lock();
                l.pos = new Pos(String.valueOf(m.get("world")), num(m.get("x")), num(m.get("y")), num(m.get("z")));
                l.material = block(String.valueOf(m.get("block")));
                l.owner = UUID.fromString(String.valueOf(m.get("owner")));
                l.ownerName = String.valueOf(m.get("owner-name"));
                l.salt = String.valueOf(m.get("salt"));
                l.hash = String.valueOf(m.get("hash"));
                l.length = num(m.get("length"));
                l.autoUnlock = Boolean.TRUE.equals(m.get("auto-unlock"));
                Object created = m.get("created");
                l.created = created instanceof Number n ? n.longValue() : 0L;
                if (m.get("strikes") instanceof Number n) l.strikes = n.intValue();
                if (m.get("player-strikes") instanceof Map<?, ?> ps) {
                    for (Map.Entry<?, ?> e : ps.entrySet()) {
                        try {
                            if (e.getValue() instanceof Number n) l.playerStrikes.put(UUID.fromString(String.valueOf(e.getKey())), n.intValue());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (m.get("locked-until") instanceof Number n) l.lockedUntil = n.longValue();
                if (m.get("player-locked-until") instanceof Map<?, ?> pl) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<?, ?> e : pl.entrySet()) {
                        try {
                            if (e.getValue() instanceof Number n && n.longValue() > now)
                                l.attempts.put(UUID.fromString(String.valueOf(e.getKey())), new long[]{0, n.longValue()});
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (m.get("allowed") instanceof List<?> list) {
                    for (Object o : list) {
                        try {
                            l.allowed.add(UUID.fromString(String.valueOf(o)));
                        } catch (IllegalArgumentException ignored) {
                            // one bad name in the list doesn't cost the whole lock
                        }
                    }
                }
                if (l.material == null || l.length < 1) throw new IllegalArgumentException("bad block or length");
                locks.put(l.pos, l);
            } catch (RuntimeException e) {
                // Kept as it was and written back on every save: a renamed block id must not delete the lock for good.
                plugin.getLogger().warn("Skipping a broken entry in locks.yml (kept in the file): {} ({})", m, e.getMessage());
                broken.add(m);
            }
        }
        plugin.getLogger().info("Loaded {} chest lock(s).", locks.size());
    }

    /** locks.yml entries that couldn't be read, written back unchanged. */
    private final List<Map<?, ?>> broken = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    /** Snapshots every lock on the calling thread and writes the file in the background. */
    void save() {
        if (unreadable) return;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Lock l : locks.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", l.pos.world());
            m.put("x", l.pos.x());
            m.put("y", l.pos.y());
            m.put("z", l.pos.z());
            m.put("block", blockId(l.material));
            m.put("owner", l.owner.toString());
            m.put("owner-name", l.ownerName);
            m.put("salt", l.salt);
            m.put("hash", l.hash);
            m.put("length", l.length);
            m.put("auto-unlock", l.autoUnlock);
            m.put("created", l.created);
            if (l.strikes > 0) m.put("strikes", l.strikes);
            if (!l.playerStrikes.isEmpty()) {
                Map<String, Object> ps = new LinkedHashMap<>();
                l.playerStrikes.forEach((id, n) -> ps.put(id.toString(), n));
                m.put("player-strikes", ps);
            }
            long now = System.currentTimeMillis();
            if (l.lockedUntil > now) m.put("locked-until", l.lockedUntil);
            Map<String, Object> lockedOut = new LinkedHashMap<>();
            l.attempts.forEach((id, a) -> {
                if (a[1] > now) lockedOut.put(id.toString(), a[1]);
            });
            if (!lockedOut.isEmpty()) m.put("player-locked-until", lockedOut);
            m.put("allowed", new ArrayList<>(l.allowed.stream().map(UUID::toString).toList()));
            list.add(m);
        }
        for (Map<?, ?> m : broken) list.add(new LinkedHashMap<>(castMap(m)));
        Yaml yml = new Yaml();
        yml.set("locks", list);
        // Copied here, turned into text on the IO thread.
        Object snap = yml.snapshot();
        Runnable write = () -> SafeYaml.write(file, "# Feather chest locks. PINs are stored as salted SHA-256 hashes only.\n" + Yaml.dump(snap), plugin.getLogger());
        if (io.isShutdown()) {
            write.run();
            return;
        }
        try {
            io.execute(write);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            write.run();
        }
    }
}
