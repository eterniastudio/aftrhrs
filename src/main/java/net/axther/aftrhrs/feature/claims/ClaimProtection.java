package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.BlockEvents;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every protection rule. Lookups are a chunk-map get plus a few rectangle checks, and every handler returns at once
 * while nobody has claimed anything. Registered after the claim tool and after graves (which consume their clicks
 * first, like the old LOWEST handlers), before chest locks (old HIGH): a click claims refuse never reaches a lock.
 *
 * <p>Right-clicks are split like Bukkit's {@code useInteractedBlock}/{@code useItemInHand}: the block side is
 * checked in Fabric's {@code BlockEvents} (what the block itself does), the item side in {@code ItemEvents} (tools,
 * buckets, spawn eggs, boats...). Block placing goes through {@link FeatherEvents#BLOCK_PLACE}.
 */
final class ClaimProtection {
    private final ClaimsModule m;
    private final ClaimRegistry reg;

    /** Per block: the trust a right-click needs (index into ClaimAction, -1 none), filled lazily (tags are bound by then). */
    private final Map<Block, Integer> clickCache = new ConcurrentHashMap<>();
    /** Per item: whether it changes the block it is used on (stripping, tilling, bone meal, dye on signs, spawn eggs ...). */
    private final Map<Item, Boolean> modifierCache = new ConcurrentHashMap<>();
    /** The lectern each player opened last (Take Book needs Container there). */
    private final Map<UUID, BlockPos> lecterns = new HashMap<>();
    /** Projectiles a dispenser fired (Bukkit's BlockProjectileSource): the dispenser's spot. */
    private final Map<Entity, BlockPos> shotFrom = new WeakHashMap<>();
    /** A dispenser firing this tick (projectiles appearing next to it are its shots). */
    private ServerLevel dispLevel;
    private BlockPos dispPos;

    private static final int NONE = -1;

    ClaimProtection(ClaimsModule m) {
        this.m = m;
        this.reg = m.registry;
    }

    void clearCaches() {
        clickCache.clear();
        modifierCache.clear();
    }

    private static ClaimProtection p() {
        ClaimsModule m = ClaimsModule.active;
        return m == null ? null : m.protection;
    }

    // ------------------------------------------------------------------ classification

    /** The trust a right-click on this block needs, or null when anyone may. */
    ClaimAction clickNeed(Level level, BlockPos pos, BlockState state) {
        int v = clickCache.computeIfAbsent(state.getBlock(), b -> classify(state));
        if (v != NONE) return ClaimAction.values()[v];
        if (state.isAir() || !state.hasBlockEntity()) return null;
        // Containers from mods, newer versions and the mod's own furniture.
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container ? ClaimAction.CONTAINER : null;
    }

    private static int classify(BlockState s) {
        Block b = s.getBlock();
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        String n = id.getPath();
        if (s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.BUTTONS)
                || s.is(BlockTags.BEDS) || b == Blocks.LEVER || b == Blocks.BELL) {
            return ClaimAction.ACCESS.ordinal();
        }
        if ((n.endsWith("chest") && b != Blocks.ENDER_CHEST) || n.endsWith("_shelf") || s.is(BlockTags.SHULKER_BOXES)
                || s.is(BlockTags.CAMPFIRES) || s.is(BlockTags.CAULDRONS) || s.is(BlockTags.CANDLE_CAKES) || s.is(BlockTags.ANVIL)
                || b == Blocks.BARREL || b == Blocks.FURNACE || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE || b == Blocks.HOPPER
                || b == Blocks.DROPPER || b == Blocks.DISPENSER || b == Blocks.BREWING_STAND || b == Blocks.JUKEBOX
                || b == Blocks.CHISELED_BOOKSHELF || b == Blocks.CRAFTER || b == Blocks.DECORATED_POT || b == Blocks.COMPOSTER
                || b == Blocks.CAKE || b == Blocks.BEACON) {
            return ClaimAction.CONTAINER.ordinal();
        }
        if (s.is(BlockTags.FLOWER_POTS) || s.is(BlockTags.CANDLES) || b == Blocks.RESPAWN_ANCHOR || b == Blocks.NOTE_BLOCK
                || b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.DAYLIGHT_DETECTOR || b == Blocks.DRAGON_EGG
                || b == Blocks.REDSTONE_WIRE || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT
                || b == Blocks.END_PORTAL_FRAME) {
            return ClaimAction.BUILD.ordinal();
        }
        // Public furniture: shop counters, market stalls and chess tables work for everyone in any claim (spawn's too).
        if (id.getNamespace().equals("aftrhrs") && publicFurniture(n)) return NONE;
        // The mod's furniture (ex-Oraxen): sitting and toggling lights need Access; storage furniture is a Container
        // block entity (checked at click time).
        if (id.getNamespace().equals("aftrhrs") && !s.hasBlockEntity()) return ClaimAction.ACCESS.ordinal();
        return NONE;
    }

    /** Furniture anyone may use in any claim: the shop counters, market stalls and chess tables. */
    static boolean publicFurniture(String id) {
        return id.startsWith("arcana_shop_counter_") || id.startsWith("arcana_market_stall_")
                || net.axther.aftrhrs.feature.chess.ChessModule.isTable(id);
    }

    /** Items that change the block they are used on (and boats, carts, frames, eggs placed with them). */
    boolean modifier(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return modifierCache.computeIfAbsent(stack.getItem(), ClaimProtection::isModifier);
    }

    private static boolean isModifier(Item i) {
        String n = BuiltInRegistries.ITEM.getKey(i).getPath();
        if (n.endsWith("_hoe") || n.endsWith("_axe") || n.endsWith("_shovel") || n.endsWith("_spawn_egg") || n.endsWith("_dye")
                || n.endsWith("_boat") || n.endsWith("_raft") || n.contains("minecart")) {
            return true;
        }
        return i instanceof BucketItem || i instanceof SpawnEggItem || i instanceof BoatItem
                || i == Items.BONE_MEAL || i == Items.HONEYCOMB || i == Items.BRUSH || i == Items.SHEARS || i == Items.GLASS_BOTTLE
                || i == Items.POTION || i == Items.FLINT_AND_STEEL || i == Items.FIRE_CHARGE || i == Items.ARMOR_STAND
                || i == Items.ITEM_FRAME || i == Items.GLOW_ITEM_FRAME || i == Items.PAINTING || i == Items.END_CRYSTAL
                || i == Items.LEAD || i == Items.INK_SAC || i == Items.GLOW_INK_SAC || i == Items.POWDER_SNOW_BUCKET
                || i == Items.ENDER_EYE || i instanceof net.minecraft.world.item.CushionItem;
    }

    /** The other half of a double chest (state as placed or as standing), or null for anything else. */
    static BlockPos otherHalf(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    // ------------------------------------------------------------------ who did it

    /** The player behind a hit or a break: the player, the shooter of a projectile, who lit the TNT, who threw the potion. */
    static ServerPlayer playerSource(Entity e) {
        if (e == null) return null;
        if (e instanceof ServerPlayer p) return p;
        if (e instanceof Projectile pr) return pr.getOwner() instanceof ServerPlayer p ? p : null;
        if (e instanceof PrimedTnt t) {
            Entity src = t.getOwner();
            if (src instanceof ServerPlayer p) return p;
            if (src instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer p) return p;
            return null;
        }
        if (e instanceof AreaEffectCloud a) return a.getOwner() instanceof ServerPlayer p ? p : null;
        if (e instanceof EvokerFangs f) return f.getOwner() instanceof ServerPlayer p ? p : null;
        if (e instanceof LightningBolt l) return l.getCause();
        return null;
    }

    /** A player riding this entity (a ravager, a horse trampling crops, a boat on lily pads), or null. */
    private static ServerPlayer rider(Entity e) {
        for (Entity pass : e.getPassengers()) if (pass instanceof ServerPlayer p) return p;
        return null;
    }

    private static boolean ownTame(Entity e, ServerPlayer p) {
        if (e instanceof TamableAnimal t) return t.isTame() && t.isOwnedBy(p);
        if (e instanceof OwnableEntity o) return o.getOwnerReference() != null && p.getUUID().equals(o.getOwnerReference().getUUID());
        return false;
    }

    /** Entities claims look after: animals, villagers, golems, armor stands, frames, paintings, minecarts, boats and pets. */
    boolean protectedEntity(Entity e) {
        if (e instanceof ServerPlayer || e instanceof Display || e instanceof Interaction) return false;
        if (m.isPolice(e)) return false;
        if (m.isPet(e)) return true;
        if (e instanceof BlockAttachedEntity || e instanceof ArmorStand) return true;
        if (e instanceof VehicleEntity) return true;
        if (e instanceof Enemy && !(e instanceof OwnableEntity)) return false;
        return e instanceof Animal || e instanceof AbstractVillager || e instanceof Allay || e instanceof WaterAnimal
                || (e instanceof AbstractGolem && !(e instanceof Shulker)) || e instanceof AmbientCreature || e instanceof OwnableEntity;
    }

    /** The trust a right-click on this entity needs, or null when anyone may. */
    private ClaimAction useNeed(ServerPlayer p, Entity e) {
        if (e instanceof ServerPlayer || ownTame(e, p) || m.isPet(e) || m.isPolice(e)) return null;
        // shopkeepers and stall keepers serve everyone, in any claim (they guard themselves: nothing to trade, steal or hurt)
        if (net.axther.aftrhrs.feature.shop.ShopModule.publicNpc(e)) return null;
        if (e instanceof Display || e instanceof Interaction) return null;
        if (e instanceof ItemFrame || e instanceof ArmorStand) return ClaimAction.CONTAINER;
        if (e instanceof BlockAttachedEntity) return ClaimAction.BUILD;
        if (e instanceof VehicleEntity) return e instanceof ContainerEntity ? ClaimAction.CONTAINER : ClaimAction.BUILD;
        return protectedEntity(e) ? ClaimAction.CONTAINER : null;
    }

    boolean pvpAllowed(Entity at) {
        Claim c = reg.at(at.level(), at.getBlockX(), at.getBlockZ());
        return c == null || c.flag(ClaimFlag.PVP);
    }

    private Claim at(Level level, BlockPos pos) {
        return reg.at(level, pos);
    }

    private Claim at(Entity e) {
        return reg.at(e.level(), e.getBlockX(), e.getBlockZ());
    }

    private Claim at(Level level, Vec3 v) {
        return v == null ? null : reg.at(level, (int) Math.floor(v.x), (int) Math.floor(v.z));
    }

    /** Is this block in a claim of someone other than the owner of {@code from}? */
    private boolean crosses(Claim from, Level level, BlockPos b) {
        Claim c = at(level, b);
        return c != null && !Claim.sameOwner(c, from);
    }

    // ------------------------------------------------------------------ registration

    static void registerEvents() {
        // ---- blocks
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            ClaimProtection p = p();
            return p == null || !(player instanceof ServerPlayer sp) || p.onBreak(sp, level, pos, state);
        });
        FeatherEvents.BLOCK_PLACE.register((player, level, pos, state, stack) -> {
            ClaimProtection p = p();
            return p == null || p.onPlace(player, level, pos, state);
        });
        BlockEvents.USE_ITEM_ON.register((stack, state, level, pos, player, hand, hit) -> {
            ClaimProtection p = p();
            if (p == null || !(player instanceof ServerPlayer sp)) return null;
            return p.onUseBlock(sp, level, pos, state, stack, hit);
        });
        BlockEvents.USE_WITHOUT_ITEM.register((state, level, pos, player, hit) -> {
            ClaimProtection p = p();
            if (p == null || !(player instanceof ServerPlayer sp)) return null;
            return p.onUseBlock(sp, level, pos, state, ItemStack.EMPTY, hit);
        });
        ItemEvents.USE_ON.register(ctx -> {
            ClaimProtection p = p();
            if (p == null || !(ctx.getPlayer() instanceof ServerPlayer sp)) return null;
            return p.onUseItemOn(sp, ctx.getLevel(), ctx.getClickedPos(), ctx.getClickedFace(), ctx.getItemInHand());
        });
        ItemEvents.USE.register((level, player, hand) -> {
            ClaimProtection p = p();
            if (p == null || !(player instanceof ServerPlayer sp)) return null;
            return p.onUseItem(sp, level, player.getItemInHand(hand));
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            ClaimProtection p = p();
            if (p == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return p.onLeftClick(sp, level, pos, dir) ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        FeatherEvents.SIGN_EDIT.register((player, sign, front, lines) -> {
            ClaimProtection p = p();
            return p == null || p.reg.isEmpty() || p.m.check(player, sign.getLevel(), sign.getBlockPos(), ClaimAction.BUILD, true);
        });
        FeatherEvents.PHYSICAL_INTERACT.register((entity, level, pos, state) -> {
            ClaimProtection p = p();
            return p == null || p.onPhysical(entity, level, pos, state);
        });
        FeatherEvents.MENU_BUTTON.register((player, menu, id) -> {
            ClaimProtection p = p();
            if (p == null || p.reg.isEmpty() || !(menu instanceof LecternMenu) || id != 3) return true;
            BlockPos lectern = p.lecterns.get(player.getUUID());
            return lectern == null || p.m.check(player, player.level(), lectern, ClaimAction.CONTAINER, true);
        });
        // ---- entities and players
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            ClaimProtection p = p();
            if (p == null || !(player instanceof ServerPlayer sp) || p.reg.isEmpty()) return InteractionResult.PASS;
            ClaimAction need = p.useNeed(sp, entity);
            return need == null || p.m.check(sp, entity, need, true) ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            ClaimProtection p = p();
            return p == null || p.allowDamage(entity, source);
        });
        FeatherEvents.ENTITY_DAMAGE.register((victim, level, source, amount) -> {
            ClaimProtection p = p();
            return p == null || p.allowDamage(victim, source);
        });
        FeatherEvents.HOOK_PULL.register((player, hooked) -> {
            ClaimProtection p = p();
            return p == null || p.onHookPull(player, hooked);
        });
        FeatherEvents.EFFECT_APPLY.register((target, effect, source) -> {
            ClaimProtection p = p();
            if (p == null || p.reg.isEmpty() || effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return true;
            ServerPlayer thrower = playerSource(source);
            return thrower == null || !p.blocksHarm(thrower, target);
        });
        FeatherEvents.PROJECTILE_HIT_BLOCK.register((projectile, level, pos, state) -> {
            ClaimProtection p = p();
            return p == null || p.onProjectileHit(projectile, level, pos, state);
        });
        // ---- the world
        FeatherEvents.EXPLOSION.register((level, explosion, blocks) -> {
            ClaimProtection p = p();
            if (p != null) p.onExplode(level, explosion, blocks);
        });
        FeatherEvents.FIRE_SPREAD.register((level, from, to) -> {
            ClaimProtection p = p();
            return p == null || p.fireAllowed(level, from, to);
        });
        FeatherEvents.BLOCK_BURN.register((level, from, to) -> {
            ClaimProtection p = p();
            return p == null || p.fireAllowed(level, from, to);
        });
        FeatherEvents.FLUID_INTO.register((level, to, dir) -> {
            ClaimProtection p = p();
            if (p == null || p.reg.isEmpty()) return true;
            Claim ct = p.at(level, to);
            // where it flows from, by coordinates (claims are full height): no BlockPos even when claimed
            return ct == null || Claim.sameOwner(ct, p.reg.at(level, to.getX() - dir.getStepX(), to.getZ() - dir.getStepZ()));
        });
        FeatherEvents.PISTON.register((level, piston, push, extending, moved, destroyed) -> {
            ClaimProtection p = p();
            return p == null || p.onPiston(level, piston, push, extending, moved, destroyed);
        });
        FeatherEvents.DISPENSE.register((level, pos, state) -> {
            ClaimProtection p = p();
            return p == null || p.onDispense(level, pos, state);
        });
        FeatherEvents.ENTITY_CHANGE_BLOCK.register((entity, level, pos, state) -> {
            ClaimProtection p = p();
            return p == null || p.onEntityChangeBlock(entity, level, pos, state);
        });
        FeatherEvents.MOB_USE_CONTAINER.register((mob, level, pos) -> {
            ClaimProtection p = p();
            return p == null || p.mobMayUse(mob, level, pos);
        });
        FeatherEvents.ENTITY_ADDED.register(entity -> {
            ClaimProtection p = p();
            if (p != null && !p.reg.isEmpty()) p.onEntityAdded(entity);
        });
        FeatherEvents.THUNDER_HIT.register((target, bolt) -> {
            ClaimProtection p = p();
            return p == null || p.onThunder(target, bolt);
        });
        FeatherEvents.PLANT_GROW.register((level, pos) -> {
            ClaimProtection p = p();
            return p == null || p.growAllowed(level, pos);
        });
        FeatherEvents.HOPPER_PULL.register((level, hopper, source) -> {
            ClaimProtection p = p();
            return p == null || p.hopperMayPull(level, hopper, source);
        });
        FeatherEvents.MOB_SPAWN.register((level, mob, reason) -> {
            ClaimProtection p = p();
            if (p == null || p.reg.isEmpty() || !(mob instanceof Enemy)) return true;
            Claim c = p.at(mob);
            return c == null || c.flag(ClaimFlag.MOB_SPAWNING);
        });
        // The same rule before vanilla picks or builds a mob for the spot (MOB_SPAWN above stays the exact backstop).
        // MONSTER stands in for Enemy: every vanilla MONSTER spawn entry is an Enemy and this mod adds no biome spawn
        // entries; a datapack adding a non-Enemy type to a MONSTER list would also be blocked in no-spawn claims.
        FeatherEvents.NATURAL_SPAWN_AT.register((level, category, pos) -> {
            if (category != MobCategory.MONSTER) return true;
            ClaimProtection p = p();
            if (p == null || p.reg.isEmpty()) return true;
            Claim c = p.reg.at(level, pos.getX(), pos.getZ());
            return c == null || c.flag(ClaimFlag.MOB_SPAWNING);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            ClaimProtection p = p();
            if (p != null && p.dispPos != null && p.dispLevel == level && entity instanceof Projectile
                    && entity.distanceToSqr(Vec3.atCenterOf(p.dispPos)) < 4) {
                p.shotFrom.put(entity, p.dispPos);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ClaimProtection p = p();
            if (p != null) {
                p.dispPos = null;
                p.dispLevel = null;
            }
        });
    }

    // ------------------------------------------------------------------ blocks

    private boolean onBreak(ServerPlayer p, Level level, BlockPos pos, BlockState state) {
        if (reg.isEmpty()) return true;
        if (!m.checkBreak(p, level, pos, true)) return false;
        // The other half of a bed may be across the border.
        if (state.getBlock() instanceof BedBlock) {
            Direction f = state.getValue(BedBlock.FACING);
            BlockPos other = state.getValue(BedBlock.PART) == BedPart.HEAD ? pos.relative(f.getOpposite()) : pos.relative(f);
            return m.check(p, level, other, ClaimAction.BUILD, true);
        }
        return true;
    }

    private boolean onPlace(ServerPlayer p, Level level, BlockPos pos, BlockState state) {
        if (reg.isEmpty()) return true;
        if (!m.check(p, level, pos, ClaimAction.BUILD, true)) return false;
        // A chest joining one across the border opens both halves from outside; a hopper under such a chest empties both.
        BlockPos half = otherHalf(pos, state);
        if (half == null && state.is(Blocks.HOPPER)) half = otherHalf(pos.above(), level.getBlockState(pos.above()));
        if (half != null && !m.check(p, level, half, ClaimAction.CONTAINER, true)) return false;
        // Two-block placements: a bed's head, a door's or tall plant's top.
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.FACING)) {
            if (!m.check(p, level, pos.relative(state.getValue(BedBlock.FACING)), ClaimAction.BUILD, true)) return false;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return m.check(p, level, pos.above(), ClaimAction.BUILD, true);
        }
        return true;
    }

    /**
     * The block side of a right-click (Bukkit's useInteractedBlock): PASS refuses the block (the held item may still
     * be used, and is checked on its own); TRY_WITH_EMPTY_HAND refuses only the item's effect on this block.
     */
    private InteractionResult onUseBlock(ServerPlayer p, Level level, BlockPos pos, BlockState state, ItemStack stack, BlockHitResult hit) {
        if (reg.isEmpty() || level.isClientSide()) return null;
        if (state.is(Blocks.LECTERN)) {
            lecterns.put(p.getUUID(), pos.immutable());
            if ((stack.is(Items.WRITTEN_BOOK) || stack.is(Items.WRITABLE_BOOK)) && !m.check(p, level, pos, ClaimAction.CONTAINER, true)) {
                return InteractionResult.PASS;
            }
        }
        ClaimAction need = clickNeed(level, pos, state);
        if (need != null) {
            // A double chest opens both halves: the half across a border counts too.
            BlockPos half = otherHalf(pos, state);
            if (!m.check(p, level, pos, need, true) || (half != null && !m.check(p, level, half, need, true))) return InteractionResult.PASS;
        }
        if (!stack.isEmpty() && modifier(stack)) {
            BlockPos front = pos.relative(hit.getDirection());
            if (!m.check(p, level, pos, ClaimAction.BUILD, true) || !m.check(p, level, front, ClaimAction.BUILD, false)) {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            }
        }
        return null;
    }

    /** The item side of a right-click on a block (Bukkit's useItemInHand): tools, eggs, carts, frames ... */
    private InteractionResult onUseItemOn(ServerPlayer p, Level level, BlockPos pos, Direction face, ItemStack stack) {
        if (reg.isEmpty() || level.isClientSide() || !modifier(stack)) return null;
        if (!m.check(p, level, pos, ClaimAction.BUILD, true) || !m.check(p, level, pos.relative(face), ClaimAction.BUILD, true)) {
            return InteractionResult.FAIL;
        }
        return null;
    }

    /** Items used at the air that still act on the block looked at: buckets, boats, spawn eggs in water, brushes. */
    private InteractionResult onUseItem(ServerPlayer p, Level level, ItemStack stack) {
        if (reg.isEmpty() || level.isClientSide() || !modifier(stack)) return null;
        Item item = stack.getItem();
        boolean reaches = item instanceof BucketItem || item instanceof MobBucketItem || item instanceof BoatItem
                || item instanceof SpawnEggItem || item == Items.BRUSH || item == Items.GLASS_BOTTLE;
        if (!reaches) return null;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(Math.max(5.0, p.blockInteractionRange())));
        // Every fluid mode an item may trace with (empty bucket / bottle / egg: sources only, full bucket: none,
        // boats: any): tracing only one let an empty bucket reach through wild flowing water into a claim's source.
        for (ClipContext.Fluid mode : new ClipContext.Fluid[]{ClipContext.Fluid.ANY, ClipContext.Fluid.SOURCE_ONLY, ClipContext.Fluid.NONE}) {
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, mode, p));
            if (hit.getType() == HitResult.Type.MISS) continue;
            BlockPos pos = hit.getBlockPos();
            if (!m.check(p, level, pos, ClaimAction.BUILD, true) || !m.check(p, level, pos.relative(hit.getDirection()), ClaimAction.BUILD, true)) {
                p.containerMenu.sendAllDataToRemote();
                return InteractionResult.FAIL;
            }
        }
        return null;
    }

    /** Punching out fire, or the dragon egg (it teleports). */
    private boolean onLeftClick(ServerPlayer p, Level level, BlockPos pos, Direction dir) {
        if (reg.isEmpty()) return true;
        BlockPos front = pos.relative(dir);
        if (level.getBlockState(front).getBlock() instanceof BaseFireBlock && !m.check(p, level, front, ClaimAction.BUILD, true)) return false;
        return !level.getBlockState(pos).is(Blocks.DRAGON_EGG) || m.check(p, level, pos, ClaimAction.BUILD, true);
    }

    private boolean onPhysical(Entity e, ServerLevel level, BlockPos pos, BlockState state) {
        if (reg.isEmpty()) return true;
        boolean plate = state.is(BlockTags.PRESSURE_PLATES);
        boolean press = plate || state.is(BlockTags.BUTTONS);
        boolean trample = state.is(Blocks.FARMLAND) || state.is(Blocks.TURTLE_EGG) || state.is(Blocks.SNIFFER_EGG);
        if (!press && !trample) return true;
        if (e instanceof ServerPlayer p) {
            ClaimAction need = trample ? ClaimAction.BUILD : plate ? ClaimAction.ACCESS : null;
            return need == null || m.check(p, level, pos, need, false);
        }
        Claim c = at(level, pos);
        if (c == null) return true;
        ServerPlayer p = rider(e);
        if (p == null) p = playerSource(e);
        if (press) {
            // A player's arrow or thrown item needs Access like their hand would; mobs, redstone and items nobody threw
            // (sorters, farms) keep working.
            UUID who = p != null ? p.getUUID() : e instanceof ItemEntity item && item.getOwner() instanceof ServerPlayer t ? t.getUUID() : null;
            if (who == null) return true;
            ServerPlayer online = m.plugin.player(who);
            if (online != null && m.bypassing(online)) return true;
            return m.permits(c, who, level, pos, ClaimAction.ACCESS);
        }
        if (p != null) return m.check(p, level, pos, ClaimAction.BUILD, false);
        return c.flag(ClaimFlag.MOB_GRIEFING);
    }

    // ------------------------------------------------------------------ entities and players

    /** Every hit on an entity (living or not): PvP, animals, frames, stands, vehicles; explosions and dispensers. */
    private boolean allowDamage(Entity victim, DamageSource src) {
        if (reg.isEmpty() || victim.level().isClientSide()) return true;
        Entity direct = src.getDirectEntity(), causing = src.getEntity();
        ServerPlayer attacker = playerSource(direct);
        if (attacker == null) attacker = playerSource(causing);
        if (victim instanceof ServerPlayer vp) {
            if (attacker == null && causing instanceof OwnableEntity o && o.getOwner() instanceof ServerPlayer owner) attacker = owner;
            if (attacker == null) {
                // A bed or anchor set off across the border of a PvP-off claim hurts the players inside: counts as PvP.
                if (!src.is(DamageTypes.BAD_RESPAWN_POINT)) return true;
                Claim c = at(vp);
                if (c == null || c.flag(ClaimFlag.PVP)) return true;
                return Claim.sameOwner(c, at(vp.level(), src.getSourcePosition()));
            }
            if (attacker == vp || m.bypassing(attacker)) return true;
            if (!pvpAllowed(vp) || !pvpAllowed(attacker)) {
                m.denyPvp(attacker);
                return false;
            }
            return true;
        }
        Claim c = at(victim);
        if (c == null || !protectedEntity(victim)) return true;
        boolean byPet = false;
        if (attacker == null && causing instanceof OwnableEntity o && o.getOwner() instanceof ServerPlayer owner) {
            // A pet (wolf, Arcana pet) answers for its owner, as with players: no killing claim animals by proxy.
            attacker = owner;
            byPet = true;
        }
        if (attacker != null) {
            if (ownTame(victim, attacker)) return true;
            ClaimAction need = victim instanceof ItemFrame f && !f.getItem().isEmpty() ? ClaimAction.CONTAINER : ClaimAction.BUILD;
            return m.check(attacker, victim, need, !byPet);
        }
        BlockPos from = direct == null ? null : shotFrom.get(direct);
        if (from != null && !Claim.sameOwner(c, at(victim.level(), from))) return false;
        if (src.is(DamageTypeTags.IS_EXPLOSION)) {
            // Where it went off: the creeper / TNT / crystal, else the bed or anchor.
            Vec3 origin = direct != null ? direct.position() : src.getSourcePosition();
            return c.flag(ClaimFlag.EXPLOSIONS) && Claim.sameOwner(c, at(victim.level(), origin));
        }
        // Mobs knocking frames and paintings off the wall.
        if (victim instanceof BlockAttachedEntity && causing != null && !(causing instanceof ServerPlayer)) return c.flag(ClaimFlag.MOB_GRIEFING);
        return true;
    }

    /** Pdc key on a mob: the owner key ({@link Claim#ownerKey}, "wild") of the claim it first worked in. */
    static final String HOME = "feather:claim_home";

    /**
     * Copper golems: a golem only sorts the containers of the land it belongs to, recorded once when it is created
     * (built from a pumpkin, a spawn egg, a statue waking up) as the claim it appeared in, or "wild". A golem with no
     * record (older than this rule) is wild. Never recorded later: a wild golem led into a claim stays wild.
     */
    private boolean mobMayUse(net.minecraft.world.entity.Mob mob, ServerLevel level, BlockPos pos) {
        if (reg.isEmpty() && net.axther.aftrhrs.core.Pdc.getString(mob, HOME) == null) return true;
        Claim c = at(level, pos);
        String home = net.axther.aftrhrs.core.Pdc.getString(mob, HOME);
        if (home == null) home = "wild";
        return home.equals(c == null ? "wild" : Claim.ownerKey(c));
    }

    /** A copper golem was just created: it belongs to the claim it appeared in (or to the wild). */
    private void onEntityAdded(Entity e) {
        if (!(e instanceof net.minecraft.world.entity.animal.golem.CopperGolem g)) return;
        if (net.axther.aftrhrs.core.Pdc.getString(g, HOME) != null) return;
        Claim c = at(g);
        if (c != null) net.axther.aftrhrs.core.Pdc.putString(g, HOME, Claim.ownerKey(c));
    }

    /** Channeling: lightning a player called down hurts and converts in claims only where they could build. */
    private boolean onThunder(Entity target, LightningBolt bolt) {
        if (reg.isEmpty()) return true;
        ServerPlayer cause = bolt.getCause();
        if (cause == null || cause == target || m.bypassing(cause)) return true;
        if (target instanceof ServerPlayer vp) return pvpAllowed(vp) && pvpAllowed(cause);
        Claim c = at(target);
        if (c == null || !protectedEntity(target) || ownTame(target, cause)) return true;
        return m.check(cause, target, ClaimAction.BUILD, false);
    }

    /** How far bone meal and trees may reach from where they start (moss, flowers, big trees). */
    static final int GROW_REACH = 10;

    /**
     * Bone meal and trees must not reach into a claim of someone other than the owner of where they start. Plants
     * that only grow in their own column (crops, berries, bamboo, vines, dripleaf, tall flowers) are always fine,
     * stems reach the next block (the melon or pumpkin), the rest (grass, moss, nylium, saplings, fungi, mushrooms, sea
     * plants and anything else) {@link #GROW_REACH}.
     */
    private boolean growAllowed(ServerLevel level, BlockPos pos) {
        if (reg.isEmpty()) return true;
        net.minecraft.world.level.block.Block block = level.getBlockState(pos).getBlock();
        if (growsInPlace(block)) return true;
        Claim from = at(level, pos);
        int reach = block instanceof net.minecraft.world.level.block.StemBlock ? 1 : GROW_REACH;
        for (Claim c : reg.near(net.axther.aftrhrs.core.Worlds.name(level), pos.getX(), pos.getZ(), reach)) {
            if (Claim.sameOwner(c, from)) continue;
            for (int x = pos.getX() - reach; x <= pos.getX() + reach; x++) {
                for (int z = pos.getZ() - reach; z <= pos.getZ() + reach; z++) {
                    if (c.contains(x, z)) return false;
                }
            }
        }
        return true;
    }

    private static boolean growsInPlace(net.minecraft.world.level.block.Block b) {
        return b instanceof net.minecraft.world.level.block.CropBlock
                || b instanceof net.minecraft.world.level.block.CocoaBlock || b instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                || b instanceof net.minecraft.world.level.block.BambooSaplingBlock || b instanceof net.minecraft.world.level.block.BambooStalkBlock
                || b instanceof net.minecraft.world.level.block.BigDripleafBlock || b instanceof net.minecraft.world.level.block.BigDripleafStemBlock
                || b instanceof net.minecraft.world.level.block.SmallDripleafBlock || b instanceof net.minecraft.world.level.block.PitcherCropBlock
                || b instanceof net.minecraft.world.level.block.GrowingPlantBlock || b instanceof net.minecraft.world.level.block.TallFlowerBlock
                || b instanceof net.minecraft.world.level.block.TallGrassBlock || b instanceof net.minecraft.world.level.block.FlowerBedBlock
                || b.getClass().getName().startsWith("net.axther.");
    }

    /** A hopper (or hopper cart) may only pull from a container in a claim of the same owner as the hopper. */
    private boolean hopperMayPull(ServerLevel level, net.minecraft.world.level.block.entity.Hopper hopper, Container source) {
        if (reg.isEmpty()) return true;
        // Column lookups only (claims are full height), no BlockPos on this per-tick path. A source block sits right
        // above the hopper, in the same column and so the same claim: always fine.
        if (!(source instanceof Entity e)) return true;
        Claim c = reg.at(level, e.getBlockX(), e.getBlockZ());
        if (c == null) return true;
        return Claim.sameOwner(c, reg.at(level, net.minecraft.util.Mth.floor(hopper.getLevelX()), net.minecraft.util.Mth.floor(hopper.getLevelZ())));
    }

    private boolean onHookPull(ServerPlayer p, Entity caught) {
        if (reg.isEmpty()) return true;
        if (caught instanceof ServerPlayer other) {
            if (other != p && !m.bypassing(p) && (!pvpAllowed(other) || !pvpAllowed(p))) {
                m.denyPvp(p);
                return false;
            }
            return true;
        }
        return !protectedEntity(caught) || ownTame(caught, p) || m.check(p, caught, ClaimAction.CONTAINER, true);
    }

    private boolean blocksHarm(ServerPlayer thrower, LivingEntity victim) {
        if (victim == thrower) return false;
        if (victim instanceof ServerPlayer vp) {
            return !m.bypassing(thrower) && (!pvpAllowed(vp) || !pvpAllowed(thrower));
        }
        return protectedEntity(victim) && !ownTame(victim, thrower) && !m.check(thrower, victim, ClaimAction.BUILD, false);
    }

    /** Blocks a projectile breaks, lights or changes. */
    private static boolean fragile(BlockState s) {
        return s.is(Blocks.DECORATED_POT) || s.is(Blocks.CHORUS_FLOWER) || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.TNT);
    }

    private boolean onProjectileHit(Projectile projectile, ServerLevel level, BlockPos pos, BlockState state) {
        if (reg.isEmpty() || !fragile(state)) return true;
        ServerPlayer p = playerSource(projectile);
        if (p != null) return m.check(p, level, pos, ClaimAction.BUILD, false);
        Claim c = at(level, pos);
        if (c == null) return true;
        BlockPos from = shotFrom.get(projectile);
        if (from != null) return Claim.sameOwner(c, at(level, from));
        return c.flag(ClaimFlag.MOB_GRIEFING);
    }

    // ------------------------------------------------------------------ the world: explosions, fire, liquids, pistons, mobs

    private void onExplode(ServerLevel level, net.minecraft.world.level.Explosion explosion, List<BlockPos> blocks) {
        if (reg.isEmpty()) return;
        Entity src = explosion.getDirectSourceEntity();
        Claim origin = at(level, explosion.center());
        boolean wind = src instanceof AbstractWindCharge;
        ServerPlayer shooter = playerSource(src);
        blocks.removeIf(b -> {
            Claim c = at(level, b);
            if (c == null) return false;
            if (wind) return shooter != null ? !m.check(shooter, level, b, ClaimAction.ACCESS, false) : !c.flag(ClaimFlag.MOB_GRIEFING);
            return !c.flag(ClaimFlag.EXPLOSIONS) || !Claim.sameOwner(c, origin);
        });
    }

    private boolean fireAllowed(ServerLevel level, BlockPos from, BlockPos to) {
        if (reg.isEmpty()) return true;
        Claim c = at(level, to);
        if (c == null) return true;
        if (!c.flag(ClaimFlag.FIRE_SPREAD)) return false;
        return from == null || Claim.sameOwner(c, at(level, from));
    }

    private boolean onPiston(ServerLevel level, BlockPos piston, Direction push, boolean extending, List<BlockPos> moved, List<BlockPos> destroyed) {
        if (reg.isEmpty()) return true;
        Claim pc = at(level, piston);
        if (extending && crosses(pc, level, piston.relative(push))) return false;
        for (BlockPos b : moved) {
            if (crosses(pc, level, b) || crosses(pc, level, b.relative(push))) return false;
        }
        for (BlockPos b : destroyed) if (crosses(pc, level, b)) return false;
        return true;
    }

    private boolean onDispense(ServerLevel level, BlockPos pos, BlockState state) {
        if (reg.isEmpty() || !state.hasProperty(DispenserBlock.FACING)) return true;
        if (crosses(at(level, pos), level, pos.relative(state.getValue(DispenserBlock.FACING)))) return false;
        dispLevel = level;
        dispPos = pos.immutable();
        return true;
    }

    private boolean onEntityChangeBlock(Entity e, ServerLevel level, BlockPos pos, BlockState newState) {
        if (reg.isEmpty()) return true;
        Claim c = at(level, pos);
        if (c == null) return true;
        ServerPlayer p = playerSource(e);
        if (p == null) p = rider(e);
        boolean fire = newState.getBlock() instanceof BaseFireBlock;
        if (p != null) return m.check(p, level, pos, ClaimAction.BUILD, false);
        // Lightning and blaze fireballs lighting blocks: fire spread rules.
        if (fire || e instanceof LightningBolt) return c.flag(ClaimFlag.FIRE_SPREAD);
        if (e instanceof FallingBlockEntity fb) {
            BlockPos start = fb.getStartPos();
            return start == null || Claim.sameOwner(c, at(level, start));
        }
        BlockPos from = shotFrom.get(e);
        if (from != null) return Claim.sameOwner(c, at(level, from));
        // Farmers, bees and grazing sheep are fine.
        if (e instanceof Villager || e instanceof Bee || e instanceof Sheep) return true;
        if (e.getType() == net.minecraft.world.entity.EntityTypes.SNOW_GOLEM) return true;
        return c.flag(ClaimFlag.MOB_GRIEFING);
    }
}
