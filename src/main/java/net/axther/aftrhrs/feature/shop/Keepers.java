package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stall keepers: a villager that stands at a rented market stall and opens its chest screens ({@link StallMenus}).
 * Renting gives a "Stall Keeper" item ({@code /stall keeper} hands out a new one); right-clicking a block within
 * {@code stalls.keeper.distance} of your stall puts the keeper there, facing you. One keeper per stall: placing again
 * moves it. Sneak + right-click picks it up. It goes when the rental ends or the stall is removed, unless staff placed
 * it (a server-run stall keeps its keeper between renters).
 *
 * <p>The keeper is a plain vanilla villager with no AI, invulnerable, silent, without gravity and persistent, tagged
 * with the stall id in {@link Pdc} ({@code feather:stall_keeper}, the plugin's key, so villagers from the Paper world
 * keep working as they load). {@code data/shop.yml} is the truth: a tagged villager that isn't the stall's recorded
 * keeper is removed as soon as it is seen (entity load, the 5 s check), and a recorded keeper missing from its loaded
 * spot is spawned again. Nothing is spawned while the spot's entities aren't loaded, so a restart or chunk reload
 * re-links the old villager instead of adding a second one. Lightning can't turn one into a witch (the witch is
 * removed and the keeper comes back).
 *
 * <p>The item is only a token. Every new one raises the stall's issue number (saved first), so at most one item works
 * at a time, and placing one uses it up; an older copy crumbles when used.
 */
final class Keepers {
    static final String TAG = "feather_stall_keeper";
    static final String STAFF = "staff";
    /** Pdc keys: the plugin's NamespacedKeys, unchanged. */
    static final String KEEPER_KEY = "feather:stall_keeper";
    static final String TOKEN_STALL = "feather:keeper_stall";
    static final String TOKEN_OWNER = "feather:keeper_owner";
    static final String TOKEN_ISSUE = "feather:keeper_issue";
    private static final List<String> TYPES = List.of("plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga");
    private static final List<String> JOBS = List.of("none", "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman",
            "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith");

    private final ShopModule shop;
    Item material = Items.PAPER;
    Identifier model = Identifier.withDefaultNamespace("villager_spawn_egg");
    double distance = 4;
    String defaultType = "plains";
    String defaultJob = "none";
    long confirmAbove = 500;
    private Tasks.Task checker;
    private final Map<UUID, Long> clicked = new HashMap<>();

    Keepers(ShopModule shop) {
        this.shop = shop;
    }

    static List<String> types() {
        return TYPES;
    }

    static List<String> jobs() {
        return JOBS;
    }

    void reload(Yaml c) {
        material = Items.PAPER;
        model = Identifier.withDefaultNamespace("villager_spawn_egg");
        distance = 4;
        defaultType = "plains";
        defaultJob = "none";
        confirmAbove = 500;
        if (c == null) return;
        String rawMat = c.getString("item.material", "paper").trim().toLowerCase(Locale.ROOT);
        Identifier matId = Identifier.tryParse(rawMat.contains(":") ? rawMat : "minecraft:" + rawMat);
        Item m = matId == null ? null : BuiltInRegistries.ITEM.getOptional(matId).orElse(null);
        if (m != null && m != Items.AIR && !matId.getPath().endsWith("_spawn_egg")) material = m;
        else shop.plugin.getLogger().warn("shop.yml: stalls.keeper.item.material must be a plain item (not a spawn egg); using paper.");
        String rawModel = c.getString("item.model", "minecraft:villager_spawn_egg");
        model = rawModel == null || rawModel.isBlank() ? null : Identifier.tryParse(rawModel.trim().toLowerCase(Locale.ROOT));
        distance = Math.max(1, Math.min(16, c.getDouble("distance", 4)));
        String t = c.getString("default-look.type", "plains").toLowerCase(Locale.ROOT);
        defaultType = TYPES.contains(t) ? t : "plains";
        String j = c.getString("default-look.profession", "none").toLowerCase(Locale.ROOT);
        defaultJob = JOBS.contains(j) ? j : "none";
        confirmAbove = Math.max(0, c.getLong("confirm-above", 500));
    }

    void enable() {
        checker = Tasks.timer(40L, 100L, this::checkAll);
        Tasks.later(1L, this::scanLoaded);
    }

    void disable() {
        if (checker != null) checker.cancel();
        checker = null;
    }

    private Text text() {
        return shop.text();
    }

    private ShopStore store() {
        return shop.store;
    }

    // ------------------------------------------------------------------ entities

    /** The stall id a keeper villager belongs to, or null when it isn't one. */
    static String stallOf(Entity e) {
        if (!(e instanceof Villager)) return null;
        return Pdc.getString(e, KEEPER_KEY);
    }

    static boolean isKeeper(Entity e) {
        return stallOf(e) != null;
    }

    private static MinecraftServer server() {
        return Feather.serverOrNull();
    }

    private static Entity find(UUID id, ServerLevel hint) {
        if (id == null) return null;
        if (hint != null) {
            Entity e = hint.getEntity(id);
            if (e != null) return e;
        }
        MinecraftServer s = server();
        if (s == null) return null;
        for (ServerLevel l : s.getAllLevels()) {
            if (l == hint) continue;
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    /** The stall's keeper villager if it is loaded right now. */
    Villager entity(ShopStore.Stall s) {
        if (s == null || s.keeper == null) return null;
        Loc spot = s.keeperSpot();
        Entity e = find(s.keeper, spot == null ? null : spot.level());
        return e instanceof Villager v && v.isAlive() && !v.isRemoved() ? v : null;
    }

    /** Whether the entities at this spot are loaded (only then may a missing keeper be spawned again). */
    private static boolean entitiesLoaded(Loc at) {
        if (at == null || at.level() == null) return false;
        return at.level().areEntitiesLoaded(ChunkPos.pack(at.blockX() >> 4, at.blockZ() >> 4));
    }

    /** Keeper villagers of {@code stallId} in the chunk of {@code at}. */
    private static List<Villager> inChunk(Loc at, String stallId) {
        ServerLevel l = at.level();
        int cx = at.blockX() >> 4, cz = at.blockZ() >> 4;
        AABB box = new AABB(cx << 4, l.getMinY(), cz << 4, (cx << 4) + 16, l.getMaxY() + 1, (cz << 4) + 16);
        return l.getEntitiesOfClass(Villager.class, box, v -> stallId.equals(stallOf(v)));
    }

    Component name(ShopStore.Stall s) {
        if (s.rented()) return text().item(text().raw("shop.stall.keeper.name"), Text.unparsed("owner", s.ownerName == null ? "?" : s.ownerName),
                Text.unparsed("name", s.name));
        return text().item(text().raw("shop.stall.keeper.name-free"), Text.unparsed("name", s.name));
    }

    String typeOf(ShopStore.Stall s) {
        return s.keeperType != null && TYPES.contains(s.keeperType) ? s.keeperType : defaultType;
    }

    String jobOf(ShopStore.Stall s) {
        return s.keeperJob != null && JOBS.contains(s.keeperJob) ? s.keeperJob : defaultJob;
    }

    /** Everything a keeper is: frozen, harmless, silent, saved with the chunk, tagged, named and dressed. */
    private void setup(Villager v, ShopStore.Stall s) {
        v.setNoAi(true);
        v.setPermanentlyInvulnerable(true);
        v.setSilent(true);
        v.setPersistenceRequired();
        v.setNoGravity(true);
        v.setCanPickUpLoot(false);
        v.setAge(0);
        v.addTag(TAG);
        Pdc.putString(v, KEEPER_KEY, s.id);
        dress(v, s);
    }

    /** Name and look (both can change while it stands). */
    void dress(Villager v, ShopStore.Stall s) {
        var reg = v.level().registryAccess();
        VillagerData d = v.getVillagerData();
        ResourceKey<VillagerType> type = ResourceKey.create(Registries.VILLAGER_TYPE, Identifier.withDefaultNamespace(typeOf(s)));
        ResourceKey<VillagerProfession> job = ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace(jobOf(s)));
        VillagerData want = d;
        try {
            if (!d.type().is(type)) want = want.withType(reg, type);
            if (!d.profession().is(job)) want = want.withProfession(reg, job);
        } catch (RuntimeException e) {
            shop.warnOnce("keeper-look", "Stall keeper look " + typeOf(s) + "/" + jobOf(s) + " is unknown: " + e);
        }
        if (want != d) v.setVillagerData(want);
        // some experience keeps the profession for good, even if something gave it a brain again
        if (v.getVillagerXp() < 1) v.setVillagerXp(1);
        net.minecraft.network.chat.Component n = Adv.vanilla(name(s));
        if (!n.equals(v.getCustomName())) v.setCustomName(n);
        v.setCustomNameVisible(true);
    }

    /** Spawns the keeper at its recorded spot and records its id (the caller saves). Null when something stopped it. */
    private Villager spawn(ShopStore.Stall s) {
        Loc at = s.keeperSpot();
        if (at == null) return null;
        try {
            Villager v = EntityTypes.VILLAGER.create(at.level(), EntitySpawnReason.COMMAND);
            if (v == null) return null;
            v.snapTo(at.x(), at.y(), at.z(), at.yaw(), 0);
            v.setYHeadRot(at.yaw());
            v.setYBodyRot(at.yaw());
            setup(v, s);
            v.setOffers(new MerchantOffers());
            // Recorded before it is added: adding fires ENTITY_LOAD, whose check() discards any keeper that isn't
            // the recorded one (the old dead keeper's id made every respawn an "orphan", forever).
            UUID before = s.keeper;
            s.keeper = v.getUUID();
            if (!at.level().addFreshEntity(v)) {
                s.keeper = before;
                return null;
            }
            return v;
        } catch (Throwable t) {
            shop.warnOnce("keeper-spawn", "Could not spawn a stall keeper: " + t);
            return null;
        }
    }

    /** Removes the keeper villager (and any stray copy for the stall around its spot) if loaded. */
    void despawn(String stallId, UUID entity, Loc spot) {
        Entity e = find(entity, spot == null ? null : spot.level());
        if (e != null && isKeeper(e)) e.discard();
        if (spot != null && entitiesLoaded(spot)) {
            for (Villager v : inChunk(spot, stallId)) v.discard();
        }
    }

    /**
     * Brings one stall's keeper in line with the data: strays around the spot go, a missing keeper is spawned again
     * (only while the spot's entities are loaded), a moved one is put back, name and look are refreshed.
     */
    void ensure(ShopStore.Stall s) {
        if (store().unreadable || !s.hasKeeper()) return;
        Loc at = s.keeperSpot();
        if (!entitiesLoaded(at)) return;
        Villager v = entity(s);
        for (Villager e : inChunk(at, s.id)) {
            if (e == v) continue;
            if (v == null && s.keeper == null && e.isAlive()) {
                v = e; // adopt it (its id wasn't recorded)
                s.keeper = v.getUUID();
                store().save();
            } else {
                e.discard();
            }
        }
        if (v == null) {
            v = spawn(s);
            if (v == null) {
                shop.warnOnce("keeper-respawn:" + s.id, "Stall " + s.id + ": its keeper could not be spawned again at " + at.blockX() + " " + at.blockY()
                        + " " + at.blockZ() + " (something cancels the spawn there).");
                return;
            }
            store().save();
            store().log("KEEPER-RESPAWN " + s.id + " " + v.getUUID());
            return;
        }
        if (v.isPassenger()) v.stopRiding();
        if (v.level() != at.level() || v.distanceToSqr(at.x(), at.y(), at.z()) > 0.25) {
            v.teleportTo(at.level(), at.x(), at.y(), at.z(), Set.of(), at.yaw(), 0, false);
            v.setYHeadRot(at.yaw());
            v.setYBodyRot(at.yaw());
        }
        if (v.isOnFire()) v.clearFire();
        if (!v.getActiveEffects().isEmpty()) v.removeAllEffects();
        dress(v, s);
    }

    private void checkAll() {
        if (store().unreadable) return;
        for (ShopStore.Stall s : List.copyOf(store().stalls.values())) ensure(s);
    }

    /** At start (and reload): keepers already loaded are checked like freshly loaded ones. */
    void scanLoaded() {
        if (store().unreadable || ShopModule.active != shop) return;
        MinecraftServer srv = server();
        if (srv == null) return;
        for (ServerLevel l : srv.getAllLevels()) {
            for (Entity e : l.getAllEntities()) if (isKeeper(e)) check(e);
        }
        checkAll();
    }

    /** A keeper villager that just appeared: kept only when it is its stall's recorded keeper. */
    void check(Entity e) {
        if (!e.isAlive() || e.isRemoved() || store().unreadable) return;
        String id = stallOf(e);
        if (id == null) return;
        ShopStore.Stall s = store().stall(id);
        if (s == null || !s.hasKeeper() || (s.keeper != null && !s.keeper.equals(e.getUUID()))) {
            e.discard();
            store().log("KEEPER-ORPHAN removed " + e.getUUID() + " (stall " + id + ")");
            return;
        }
        if (s.keeper == null) {
            s.keeper = e.getUUID();
            store().save();
        }
        dress((Villager) e, s);
    }

    // ------------------------------------------------------------------ the item

    boolean isToken(ItemStack item) {
        return item != null && !item.isEmpty() && Pdc.itemString(item, TOKEN_STALL) != null;
    }

    private ItemStack token(ShopStore.Stall s, boolean staff) {
        ItemStack it = new ItemStack(material);
        if (model != null) it.set(DataComponents.ITEM_MODEL, model);
        Icons.name(it, text().itemMsg(staff ? "shop.stall.keeper.item-name-staff" : "shop.stall.keeper.item-name", Text.unparsed("name", s.name)));
        Icons.lore(it, text().lines(staff ? "shop.stall.keeper.item-lore-staff" : "shop.stall.keeper.item-lore", Text.unparsed("name", s.name),
                Text.unparsed("distance", (int) distance)));
        it.set(DataComponents.MAX_STACK_SIZE, 1);
        Pdc.itemPutString(it, TOKEN_STALL, s.id);
        Pdc.itemPutString(it, TOKEN_OWNER, staff ? STAFF : String.valueOf(s.owner));
        Pdc.itemPut(it, TOKEN_ISSUE, IntTag.valueOf(staff ? 0 : s.keeperIssue));
        return it;
    }

    /**
     * Hands out a Stall Keeper item. For the renter the stall's issue number goes up and is saved first, so any older
     * item stops working; older ones in the inventory are taken away.
     */
    boolean giveToken(ServerPlayer p, ShopStore.Stall s, boolean staff) {
        if (!staff) {
            int before = s.keeperIssue;
            s.keeperIssue++;
            if (!store().save()) {
                s.keeperIssue = before;
                shop.error(p, "shop.save-failed");
                return false;
            }
            sweepTokens(p, s.id);
        }
        ShopModule.give(p, List.of(token(s, staff)));
        store().log("KEEPER-ITEM " + s.id + " to " + Names.name(p) + " " + p.getUUID() + (staff ? " (staff)" : " issue=" + s.keeperIssue));
        return true;
    }

    /** Removes this stall's renter keeper items from the player's inventory and cursor. */
    private void sweepTokens(ServerPlayer p, String stallId) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isToken(it) && stallId.equals(tokenStallOf(it)) && !STAFF.equals(tokenOwnerOf(it))) inv.setItem(i, ItemStack.EMPTY);
        }
        ItemStack cur = p.containerMenu.getCarried();
        if (isToken(cur) && stallId.equals(tokenStallOf(cur)) && !STAFF.equals(tokenOwnerOf(cur))) p.containerMenu.setCarried(ItemStack.EMPTY);
    }

    private static String tokenStallOf(ItemStack it) {
        return Pdc.itemString(it, TOKEN_STALL);
    }

    private static String tokenOwnerOf(ItemStack it) {
        return Pdc.itemString(it, TOKEN_OWNER);
    }

    private static int tokenIssueOf(ItemStack it) {
        return Pdc.item(it).getIntOr(TOKEN_ISSUE, -1);
    }

    // ------------------------------------------------------------------ placing

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean liquid(ServerLevel level, BlockPos pos) {
        return !level.getFluidState(pos).isEmpty();
    }

    /**
     * A Stall Keeper item used (right-click on a block, or in the air with {@code hit} null). Every use of the item is
     * cancelled; only the main hand places.
     */
    InteractionResult useToken(ServerPlayer p, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (justClicked(p)) return InteractionResult.FAIL;
        ItemStack item = p.getMainHandItem();
        ServerLevel level = p.level();
        BlockPos b;
        Direction face;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            // a click the server didn't treat as a block click (spawn protection, reach): use the block looked at
            Vec3 eye = p.getEyePosition();
            HitResult h = level.clip(new ClipContext(eye, eye.add(p.getViewVector(1f).scale(5)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (!(h instanceof BlockHitResult bh) || h.getType() != HitResult.Type.BLOCK || passable(level, bh.getBlockPos())) {
                shop.error(p, "shop.stall.keeper.place-on-ground");
                return InteractionResult.FAIL;
            }
            b = bh.getBlockPos();
            face = Direction.UP;
        } else {
            b = hit.getBlockPos();
            face = hit.getDirection();
        }
        BlockPos spot = passable(level, b) && !liquid(level, b) && face == Direction.UP ? b : b.relative(face);
        place(p, item, level, spot);
        return InteractionResult.SUCCESS;
    }

    /** A Stall Keeper item used on any entity (not a keeper): it only goes on the ground. */
    InteractionResult tokenOnEntity(ServerPlayer p, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && !justClicked(p)) shop.error(p, "shop.stall.keeper.place-on-ground");
        return InteractionResult.FAIL;
    }

    boolean justClicked(ServerPlayer p) {
        long now = Tasks.now();
        Long t = clicked.put(p.getUUID(), now);
        if (clicked.size() > 1024) clicked.clear();
        return t != null && now - t >= 0 && now - t <= 2;
    }

    /** Puts (or moves) the stall's keeper at {@code spot}, facing the player. The item is used up. */
    void place(ServerPlayer p, ItemStack item, ServerLevel level, BlockPos spot) {
        if (shop.closed(p)) return;
        String sid = tokenStallOf(item);
        String owner = tokenOwnerOf(item);
        boolean staff = STAFF.equals(owner);
        ShopStore.Stall s = store().stall(sid);
        if (s == null) {
            useUp(p, item);
            shop.error(p, "shop.stall.keeper.stale");
            return;
        }
        if (staff) {
            if (!Perms.has(p, ShopModule.STALL_ADMIN)) {
                shop.error(p, "general.no-permission");
                return;
            }
        } else {
            if (!s.rented() || !String.valueOf(s.owner).equals(owner) || tokenIssueOf(item) != s.keeperIssue) {
                useUp(p, item);
                shop.error(p, "shop.stall.keeper.stale");
                return;
            }
            if (!p.getUUID().equals(s.owner)) {
                shop.error(p, "shop.stall.keeper.not-yours");
                return;
            }
            if (s.rentUntil <= System.currentTimeMillis()) {
                shop.error(p, "shop.stall.expired");
                return;
            }
            if (s.keeperStaff) {
                shop.error(p, "shop.stall.keeper.staff-placed");
                return;
            }
        }
        Loc stallAt = s.location();
        Loc at0 = new Loc(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        if (stallAt == null || stallAt.level() != level || at0.distanceSquared(stallAt) > (distance + 0.5) * (distance + 0.5)) {
            shop.error(p, "shop.stall.keeper.too-far", Text.unparsed("distance", (int) distance), Text.unparsed("name", s.name));
            return;
        }
        if (!passable(level, spot) || liquid(level, spot) || !passable(level, spot.above()) || liquid(level, spot.above())) {
            shop.error(p, "shop.stall.keeper.no-room");
            return;
        }
        if (!shop.plugin.claimAllows(p, at0, ClaimAction.BUILD) && !shop.plugin.sameClaim(at0, stallAt)) {
            shop.error(p, "shop.stall.keeper.claimed");
            return;
        }
        double lx = p.getX() - at0.x(), lz = p.getZ() - at0.z();
        float yaw = lx * lx + lz * lz > 1.0E-4 ? (float) Math.toDegrees(Math.atan2(-lx, lz)) : 0f;
        Loc at = at0.withRotation(yaw, 0);

        // remember the old keeper, take the item, record the new spot, spawn, save; undo everything on a failure
        String oldWorld = s.keeperWorld;
        double ox = s.kx, oy = s.ky, oz = s.kz;
        float oyaw = s.kyaw;
        UUID oldEntity = s.keeper;
        boolean oldStaff = s.keeperStaff;
        int oldIssue = s.keeperIssue;
        Loc oldSpot = s.keeperSpot();
        ItemStack taken = item.copyWithCount(1);
        useUp(p, item);
        s.keeperWorld = Worlds.name(level);
        s.kx = at.x();
        s.ky = at.y();
        s.kz = at.z();
        s.kyaw = at.yaw();
        s.keeper = null;
        s.keeperStaff = staff;
        if (!staff) s.keeperIssue++;
        Villager v = spawn(s);
        boolean saved = v != null && store().save();
        if (!saved) {
            if (v != null) v.discard();
            s.keeperWorld = oldWorld;
            s.kx = ox;
            s.ky = oy;
            s.kz = oz;
            s.kyaw = oyaw;
            s.keeper = oldEntity;
            s.keeperStaff = oldStaff;
            s.keeperIssue = oldIssue;
            ShopModule.give(p, List.of(taken));
            shop.error(p, v == null ? "shop.stall.keeper.cant-spawn" : "shop.save-failed");
            return;
        }
        if (oldWorld != null) despawnExcept(s.id, oldEntity, oldSpot, v);
        store().log("KEEPER-PLACE " + s.id + " by " + Names.name(p) + " " + p.getUUID() + (staff ? " (staff)" : "") + " at " + s.keeperWorld + " "
                + at.blockX() + "," + at.blockY() + "," + at.blockZ() + " entity=" + v.getUUID());
        text().send(p, oldWorld == null ? "shop.stall.keeper.placed" : "shop.stall.keeper.moved", Text.unparsed("name", s.name));
        text().sound(p, "accepted");
        shop.menus.refresh(s.id);
    }

    private void despawnExcept(String stallId, UUID entity, Loc spot, Entity keep) {
        if (entity != null && !entity.equals(keep.getUUID())) {
            Entity e = find(entity, spot == null ? null : spot.level());
            if (e != null && isKeeper(e)) e.discard();
        }
        if (spot != null && entitiesLoaded(spot)) {
            for (Villager v : inChunk(spot, stallId)) if (v != keep) v.discard();
        }
    }

    /** Takes one of this item out of the player's main hand (where it was used). */
    private static void useUp(ServerPlayer p, ItemStack item) {
        ItemStack hand = p.getMainHandItem();
        if (ShopModule.similar(hand, item)) hand.shrink(1);
    }

    // ------------------------------------------------------------------ picking up, removing

    /** Sneak + right-click: the renter takes their keeper back (staff take back a staff keeper). */
    boolean pickup(ServerPlayer p, ShopStore.Stall s) {
        if (shop.closed(p)) return false;
        if (!s.hasKeeper()) return false;
        boolean admin = Perms.has(p, ShopModule.STALL_ADMIN);
        boolean owner = p.getUUID().equals(s.owner);
        if (s.keeperStaff ? !admin : !owner) {
            shop.error(p, s.keeperStaff ? "shop.stall.keeper.staff-placed" : "shop.stall.keeper.not-yours");
            return false;
        }
        boolean wasStaff = s.keeperStaff;
        String world = s.keeperWorld;
        UUID entity = s.keeper;
        Loc spot = s.keeperSpot();
        int issue = s.keeperIssue;
        s.clearKeeper();
        if (!wasStaff) s.keeperIssue++;
        if (!store().save()) {
            s.keeperWorld = world;
            s.keeper = entity;
            s.keeperStaff = wasStaff;
            s.keeperIssue = issue;
            shop.error(p, "shop.save-failed");
            return false;
        }
        despawn(s.id, entity, spot);
        ShopModule.give(p, List.of(token(s, wasStaff)));
        store().log("KEEPER-PICKUP " + s.id + " by " + Names.name(p) + " " + p.getUUID() + (wasStaff ? " (staff)" : " issue=" + s.keeperIssue));
        text().send(p, "shop.stall.keeper.picked-up", Text.unparsed("name", s.name));
        text().sound(p, "item-pickup");
        shop.menus.refresh(s.id);
        return true;
    }

    /** Staff: removes a stall's keeper (no item comes back). */
    boolean remove(Sender by, ShopStore.Stall s) {
        if (!s.hasKeeper()) {
            shop.error(by, "shop.stall.keeper.none", Text.unparsed("name", s.name));
            return false;
        }
        String world = s.keeperWorld;
        UUID entity = s.keeper;
        boolean wasStaff = s.keeperStaff;
        Loc spot = s.keeperSpot();
        s.clearKeeper();
        if (!store().save()) {
            s.keeperWorld = world;
            s.keeper = entity;
            s.keeperStaff = wasStaff;
            shop.error(by, "shop.save-failed");
            return false;
        }
        despawn(s.id, entity, spot);
        store().log("KEEPER-REMOVE " + s.id + " by " + by.getName());
        text().send(by, "shop.stall.keeper.removed", Text.unparsed("name", s.name));
        shop.menus.refresh(s.id);
        return true;
    }

    /** Staff from the console (or anywhere): a staff keeper at exact coordinates. */
    boolean placeAt(Sender by, ShopStore.Stall s, Loc at) {
        if (store().unreadable) {
            shop.error(by, "shop.closed");
            return false;
        }
        String oldWorld = s.keeperWorld;
        double ox = s.kx, oy = s.ky, oz = s.kz;
        float oyaw = s.kyaw;
        UUID oldEntity = s.keeper;
        boolean oldStaff = s.keeperStaff;
        Loc oldSpot = s.keeperSpot();
        s.keeperWorld = Worlds.name(at.level());
        s.kx = at.x();
        s.ky = at.y();
        s.kz = at.z();
        s.kyaw = at.yaw();
        s.keeper = null;
        s.keeperStaff = true;
        Villager v = spawn(s);
        if (v == null || !store().save()) {
            if (v != null) v.discard();
            s.keeperWorld = oldWorld;
            s.kx = ox;
            s.ky = oy;
            s.kz = oz;
            s.kyaw = oyaw;
            s.keeper = oldEntity;
            s.keeperStaff = oldStaff;
            shop.error(by, v == null ? "shop.stall.keeper.cant-spawn" : "shop.save-failed");
            return false;
        }
        if (oldWorld != null) despawnExcept(s.id, oldEntity, oldSpot, v);
        store().log("KEEPER-PLACE " + s.id + " by " + by.getName() + " (staff, command) at " + s.keeperWorld + " " + at.blockX() + "," + at.blockY() + ","
                + at.blockZ() + " entity=" + v.getUUID());
        text().send(by, "shop.stall.keeper.placed-staff", Text.unparsed("name", s.name), Text.unparsed("entity", v.getUUID()));
        return true;
    }

    /** The owner changes its look (cosmetic). Saved, then applied. */
    boolean setLook(ServerPlayer p, ShopStore.Stall s, String type, String job) {
        String bt = s.keeperType, bj = s.keeperJob;
        if (type != null && TYPES.contains(type)) s.keeperType = type;
        if (job != null && JOBS.contains(job)) s.keeperJob = job;
        if (!store().save()) {
            s.keeperType = bt;
            s.keeperJob = bj;
            shop.error(p, "shop.save-failed");
            return false;
        }
        Villager v = entity(s);
        if (v != null) dress(v, s);
        return true;
    }

    /** After a rent or a rental's end: the name follows the renter. */
    void renamed(ShopStore.Stall s) {
        Villager v = entity(s);
        if (v != null) dress(v, s);
    }

    // ------------------------------------------------------------------ clicks on a keeper

    /** Any interaction with a keeper villager: never a trade, a lead, a name tag; a main-hand click opens the stall. */
    InteractionResult interact(ServerPlayer p, Entity e, InteractionHand hand) {
        String sid = stallOf(e);
        if (sid == null) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (justClicked(p)) return InteractionResult.FAIL;
        if (store().unreadable) {
            shop.error(p, "shop.closed");
            return InteractionResult.FAIL;
        }
        ShopStore.Stall s = store().stall(sid);
        if (s == null || !s.hasKeeper() || (s.keeper != null && !s.keeper.equals(e.getUUID()))) {
            e.discard();
            return InteractionResult.FAIL;
        }
        if (!Perms.has(p, ShopModule.STALL_PERM)) {
            shop.error(p, "general.no-permission");
            return InteractionResult.FAIL;
        }
        // sneak: the renter (or staff, for a staff keeper) picks it up; a renter is told a staff keeper stays
        if (p.isShiftKeyDown() && (p.getUUID().equals(s.owner) || (s.keeperStaff && Perms.has(p, ShopModule.STALL_ADMIN)))) {
            pickup(p, s);
            return InteractionResult.SUCCESS;
        }
        shop.stalls.click(p, s);
        return InteractionResult.SUCCESS;
    }
}
