package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Carrying (docs/carrying.md), all with an empty hand and sneaking:
 * <ul>
 *   <li><b>Players</b>: sneak + right-click a player to lift them onto your head. They ride along until they sneak to
 *   hop off, you sneak + right-click them again, or you use {@code /carry drop}. Players choose whether others may pick
 *   them up (the hub's Settings, or {@code /carry toggle}); staff with {@code feather.carry.bypass} ignore that.</li>
 *   <li><b>Blocks</b>: sneak + right-click a chest, barrel, furnace or other block that keeps something to pick it up
 *   with everything in it (contents, custom name, lock-free state); right-click any surface to set it down there,
 *   facing you. Kept on the player ({@link CarriedBlock}), so logging out or a restart keeps it; dying sets it down
 *   where you fell (or spills it when there's no room).</li>
 *   <li><b>Mobs</b>: sneak + right-click an animal or other small, friendly mob to hold it in your arms; right-click
 *   the ground to set it down, or sneak + right-click it again.</li>
 * </ul>
 * Hands that carry something can't attack, use items or open things, and the carrier walks a little slower. What is
 * carried is set down before any teleport of the carrier (a carried block comes along), and riders and mobs are
 * dropped when the carrier gets hurt. Our own block and mob carrying, with Carry On's feature set as the reference.
 */
public final class CarryModule implements FeatherModule, Travel.Named {
    private static volatile CarryModule active;
    /** The mob being lifted right now ({@code EntityRidePlayerMixin} lets it ride a player). */
    private static Entity lifting;
    private static final Identifier SLOW = AftrHrs.id("carrying");
    static final List<String> BLOCK_DENY = List.of("spawner", "trial_spawner", "vault", "end_portal", "end_gateway", "end_portal_frame",
            "command_block", "chain_command_block", "repeating_command_block", "structure_block", "jigsaw", "test_block",
            "test_instance_block", "moving_piston", "piston_head", "aftrhrs:*");
    static final List<String> MOB_DENY = List.of("#c:bosses", "wither", "ender_dragon", "warden", "elder_guardian", "iron_golem",
            "snow_golem", "allay");

    private final Feather plugin;
    private boolean defaultAllowed, dropWhenHurt, blocks, contentsOnly, mobs, hostile;
    private double maxWidth, maxHeight, speed;
    private Deny blockDeny = Deny.NONE, mobDeny = Deny.NONE;

    public CarryModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Carrying";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        Travel.bind(plugin, this, "carry");
        plugin.once("carry.events", CarryModule::hookEvents);
        plugin.hub().addToggle(new Hub.Toggle("carry", Hub.TOGGLE_CARRY, p -> Perms.has(p, "feather.carry"), this::allows, this::toggle));
        for (ServerPlayer p : plugin.onlinePlayers()) slow(p, carryingBlock(p) || heldMob(p) != null);
    }

    /** Whether {@code e} is a mob the carry module is lifting into a player's arms right now. */
    public static boolean lifting(Entity e) {
        return e != null && e == lifting;
    }

    private static void hookEvents() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            CarryModule m = active;
            if (m == null) return InteractionResult.PASS;
            // Hands full: nothing else to do with entities (both sides, so the client doesn't try either).
            if (busyHands(player) && !(entity == heldMobOf(player) && player.isShiftKeyDown())) return InteractionResult.FAIL;
            // The client sends "interact at" and then "interact" for one click: answer only the second (no hit result).
            if (hit != null || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer carrier)) return InteractionResult.PASS;
            boolean ours = entity instanceof ServerPlayer target ? m.onInteract(carrier, target) : m.onInteractMob(carrier, entity);
            return ours ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            CarryModule m = active;
            if (m == null) return InteractionResult.PASS;
            if (busyHands(player)) {
                if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
                if (carryingBlock(p)) m.place(p, hit);
                else m.setMobDown(p, hit);
                return InteractionResult.SUCCESS;
            }
            if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            return m.pickUp(p, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        // Full hands don't swing, mine or use items.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> active != null && busyHands(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, e, hit) -> active != null && busyHands(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) -> active != null && busyHands(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        // Set riders and held mobs down before the carrier teleports (a carried block comes along).
        FeatherEvents.PLAYER_TELEPORT.register((p, from, to, cause) -> {
            CarryModule m = active;
            if (m != null && cause != TeleportCause.DISMOUNT && (carried(p) != null || heldMob(p) != null)) {
                Entity mob = heldMob(p);
                if (mob != null) m.dropMob(p, mob);
                for (Entity e : List.copyOf(p.getPassengers())) if (e instanceof ServerPlayer) e.stopRiding();
                syncPassengers(p);
            }
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            CarryModule m = active;
            // A carrier who gets hit drops riders and mobs, so carrying can't be used as a shield in fights.
            if (m != null && entity instanceof ServerPlayer p && (carried(p) != null || heldMob(p) != null) && !blocked
                    && m.dropWhenHurt && !source.is(DamageTypes.FALL)) {
                m.drop(p);
                Entity mob = heldMob(p);
                if (mob != null) m.dropMob(p, mob);
            }
        });
        // A held mob doesn't choke in the wall the carrier walks along, or get squashed against them.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(CarryPose.inArms(entity.getVehicle(), entity) && (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.CRAMMING))));
        FeatherEvents.PLAYER_DEATH.register((p, source) -> {
            CarryModule m = active;
            eject(p);
            if (m != null && carryingBlock(p)) m.release(p, p.blockPosition());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            if (active == null) return;
            eject(h.player);
            // A carried player leaving: get off first. Vanilla's quit removes the rider's whole vehicle stack when it
            // has one player passenger, which took the (still connected) carrier out of the world.
            if (h.player.getVehicle() instanceof ServerPlayer carrier) {
                h.player.stopRiding();
                syncPassengers(carrier);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            CarryModule m = active;
            if (m == null || !carryingBlock(h.player)) return;
            // Still carrying the block from last time: slower again (or set down if carrying blocks is off now).
            if (m.blocks) m.slow(h.player, true);
            else m.release(h.player, h.player.blockPosition());
        });
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        defaultAllowed = c.getBoolean("carry.allowed-by-default", true);
        dropWhenHurt = c.getBoolean("carry.drop-when-hurt", true);
        blocks = c.getBoolean("carry.blocks", true);
        contentsOnly = c.getBoolean("carry.blocks-with-contents-only", true);
        mobs = c.getBoolean("carry.mobs", true);
        hostile = c.getBoolean("carry.hostile-mobs", false);
        maxWidth = c.getDouble("carry.mob-max-width", 1.0);
        maxHeight = c.getDouble("carry.mob-max-height", 2.0);
        speed = Math.max(0.1, Math.min(1.0, c.getDouble("carry.speed", 0.8)));
        // A config.yml from before block and mob carrying has no lists: the built-in ones, not "nothing is denied".
        blockDeny = Deny.read(c.contains("carry.block-deny") ? c.getStringList("carry.block-deny") : BLOCK_DENY, Registries.BLOCK);
        mobDeny = Deny.read(c.contains("carry.mob-deny") ? c.getStringList("carry.mob-deny") : MOB_DENY, Registries.ENTITY_TYPE);
    }

    @Override
    public void disable() {
        for (ServerPlayer p : plugin.onlinePlayers()) {
            drop(p);
            Entity mob = heldMob(p);
            if (mob != null) dropMob(p, mob);
            if (carryingBlock(p)) release(p, p.blockPosition());
            slow(p, false);
        }
        if (active == this) active = null;
    }

    /** Whether others may pick this player up (their own setting, else the server default). */
    boolean allows(ServerPlayer p) {
        Boolean own = plugin.profiles().get(p).carryAllowed;
        return own != null ? own : defaultAllowed;
    }

    void toggle(ServerPlayer p) {
        Profiles.Profile pr = plugin.profiles().get(p);
        pr.carryAllowed = !allows(p);
        plugin.profiles().save(pr);
        plugin.text().send(p, pr.carryAllowed ? "carry.toggled-on" : "carry.toggled-off");
    }

    // ------------------------------------------------------------------ what someone carries

    /** The player riding on {@code carrier}'s head, or null. */
    static ServerPlayer carried(ServerPlayer carrier) {
        for (Entity e : carrier.getPassengers()) if (e instanceof ServerPlayer p) return p;
        return null;
    }

    /** The mob in {@code carrier}'s arms, or null. */
    static Entity heldMob(ServerPlayer carrier) {
        return heldMobOf(carrier);
    }

    private static Entity heldMobOf(Player carrier) {
        for (Entity e : carrier.getPassengers()) if (CarryPose.inArms(carrier, e)) return e;
        return null;
    }

    static boolean carryingBlock(Player p) {
        return p.hasAttached(CarriedBlock.CARRIED);
    }

    /** Arms full: a block or a mob (a rider on the head leaves the hands free). Works on both sides. */
    static boolean busyHands(Player p) {
        return carryingBlock(p) || heldMobOf(p) != null;
    }

    /** A block, a mob or a player (not the nametag displays that ride every player). */
    private static boolean carryingAnything(ServerPlayer p) {
        return carryingBlock(p) || heldMob(p) != null || carried(p) != null;
    }

    private static void eject(ServerPlayer carrier) {
        if (carrier.getPassengers().isEmpty()) return;
        carrier.ejectPassengers();
        syncPassengers(carrier);
    }

    /** The carrier's own client doesn't track itself: tell it who rides on it. */
    private static void syncPassengers(ServerPlayer carrier) {
        if (!carrier.hasDisconnected()) carrier.connection.send(new ClientboundSetPassengersPacket(carrier));
    }

    /** Walking a little slower while the arms are full. */
    private void slow(ServerPlayer p, boolean on) {
        AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        a.removeModifier(SLOW);
        if (on && speed < 1.0) a.addTransientModifier(new AttributeModifier(SLOW, speed - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // ------------------------------------------------------------------ players

    /** Puts down whoever {@code carrier} is carrying on their head. */
    void drop(ServerPlayer carrier) {
        ServerPlayer rider = carried(carrier);
        if (rider == null) return;
        rider.stopRiding();
        syncPassengers(carrier);
        Loc at = frontSpot(carrier, rider.getYRot(), rider.getXRot());
        if (at != null && standable(carrier.level(), at.blockPos())) plugin.teleportNow(rider, at, TeleportCause.PLUGIN);
    }

    /** A spot just in front of the carrier with nothing solid between them, or null. */
    private static Loc frontSpot(ServerPlayer carrier, float yaw, float pitch) {
        Vec3 look = carrier.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1e-6) return null;
        flat = flat.normalize().scale(0.8);
        ServerLevel w = carrier.level();
        Loc at = new Loc(w, carrier.getX() + flat.x, carrier.getY(), carrier.getZ() + flat.z, yaw, pitch);
        // Only when nothing solid is between the carrier and the drop point (no dropping through doors or thin walls).
        Vec3 from = new Vec3(carrier.getX(), carrier.getY() + 0.5, carrier.getZ());
        Vec3 dest = new Vec3(at.x(), at.y() + 0.5, at.z());
        boolean clear = w.clip(new ClipContext(from, dest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, carrier)).getType() == HitResult.Type.MISS;
        return clear ? at : null;
    }

    private static boolean standable(ServerLevel w, BlockPos feet) {
        return w.getBlockState(feet).getCollisionShape(w, feet).isEmpty() && w.getBlockState(feet.above()).getCollisionShape(w, feet.above()).isEmpty();
    }

    private static boolean free(ServerPlayer p) {
        return !p.isSpectator() && !p.isDeadOrDying() && !p.isPassenger() && !p.isFallFlying()
                && !p.isSleeping() && !carryingAnything(p);
    }

    /** Sneak + right-click a player with an empty hand. True when the click was ours (nothing else happens). */
    private boolean onInteract(ServerPlayer carrier, ServerPlayer target) {
        if (!carrier.isShiftKeyDown() || !carrier.getMainHandItem().isEmpty()) return false;
        Text text = plugin.text();
        // Clicking the one you carry puts them down.
        if (target == carried(carrier)) {
            drop(carrier);
            return true;
        }
        if (!Perms.has(carrier, "feather.carry")) return true;
        if (!plugin.canSee(carrier, target)) return true;
        if (!allows(target) && !Perms.has(carrier, "feather.carry.bypass")) {
            text.actionBar(carrier, "carry.not-allowed", Text.component("target", Names.display(target)));
            text.sound(carrier, "error");
            return true;
        }
        if (plugin.travelLocked(target)) {
            // A bank robber can't be carried off (docs/bank-robbery.md).
            text.actionBar(carrier, "carry.cant");
            return true;
        }
        if (carryingAnything(carrier)) {
            text.actionBar(carrier, "carry.hands-full");
            return true;
        }
        if (!free(carrier) || !free(target) || target == carrier.getVehicle()) {
            text.actionBar(carrier, "carry.cant");
            return true;
        }
        if (!target.startRiding(carrier, true, true)) {
            text.actionBar(carrier, "carry.cant");
            return true;
        }
        syncPassengers(carrier);
        text.actionBar(carrier, "carry.carrying", Text.component("target", Names.display(target)));
        text.actionBar(target, "carry.carried", Text.component("carrier", Names.display(carrier)));
        text.sound(carrier, "carry");
        text.sound(target, "carry");
        plugin.advance(carrier, "event", "carry");
        return true;
    }

    // ------------------------------------------------------------------ mobs

    /** Sneak + right-click a mob with an empty hand: hold it (or put the held one down). True when the click was ours. */
    private boolean onInteractMob(ServerPlayer carrier, Entity target) {
        if (!carrier.isShiftKeyDown() || !carrier.getMainHandItem().isEmpty()) return false;
        if (target.getVehicle() == carrier) {
            dropMob(carrier, target);
            return true;
        }
        if (!mobs || !(target instanceof Mob mob) || !Perms.has(carrier, "feather.carry")) return false;
        // Your own pet always comes up (even a tamed monster), up to two blocks wide; mounts are ridden, not carried.
        boolean ownPet = mob instanceof OwnableEntity own && own.getOwnerReference() != null && own.getOwnerReference().matches(carrier);
        if (!hostile && mob instanceof Enemy && !ownPet) return false;
        Text text = plugin.text();
        net.kyori.adventure.text.Component name = net.axther.aftrhrs.core.Adv.adventure(mob.getDisplayName());
        double mw = ownPet ? Math.max(maxWidth, 2.0) : maxWidth, mh = ownPet ? Math.max(maxHeight, 2.0) : maxHeight;
        boolean mount = mob instanceof net.axther.aftrhrs.entity.ArcanaMob am && am.species().mount();
        if (mobDeny.denies(mob.getType().builtInRegistryHolder()) || mob.getBbWidth() > mw + 1e-6 || mob.getBbHeight() > mh + 1e-6 || mount
                || !mob.isAlive() || mob.isPassenger() || mob.isVehicle() || mob.isLeashed()) {
            text.actionBar(carrier, "carry.mob-cant", Text.component("target", name));
            return true;
        }
        if (mob instanceof OwnableEntity own && own.getOwnerReference() != null
                && !own.getOwnerReference().matches(carrier) && !Perms.has(carrier, "feather.carry.bypass")) {
            text.actionBar(carrier, "carry.mob-not-yours", Text.component("target", name));
            return true;
        }
        if (!plugin.claimAllows(carrier, Loc.of(carrier.level(), mob.blockPosition()), ClaimAction.BUILD)) {
            text.actionBar(carrier, "carry.mob-cant", Text.component("target", name));
            return true;
        }
        if (carryingAnything(carrier)) {
            text.actionBar(carrier, "carry.hands-full");
            return true;
        }
        if (!free(carrier)) {
            text.actionBar(carrier, "carry.cant");
            return true;
        }
        boolean ok;
        lifting = mob;
        try {
            ok = mob.startRiding(carrier, true, true);
        } finally {
            lifting = null;
        }
        if (!ok) {
            text.actionBar(carrier, "carry.mob-cant", Text.component("target", name));
            return true;
        }
        mob.getNavigation().stop();
        syncPassengers(carrier);
        slow(carrier, true);
        text.actionBar(carrier, "carry.mob-carrying", Text.component("target", name));
        text.sound(carrier, "carry");
        return true;
    }

    /** Right-click a surface while holding a mob: it goes there (if it fits and the carrier may build there). */
    private void setMobDown(ServerPlayer carrier, BlockHitResult hit) {
        Entity mob = heldMob(carrier);
        if (mob == null) return;
        ServerLevel w = carrier.level();
        BlockPos clicked = hit.getBlockPos();
        BlockPos spot = w.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        Vec3 at = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        AABB box = mob.getDimensions(mob.getPose()).makeBoundingBox(at);
        if (!w.noCollision(mob, box) || !plugin.claimAllows(carrier, Loc.of(w, spot), ClaimAction.BUILD)
                || carrier.position().distanceToSqr(at) > 36) {
            plugin.text().actionBar(carrier, "carry.no-room");
            return;
        }
        mob.stopRiding();
        mob.snapTo(at.x, at.y, at.z, carrier.getYRot() + 180f, 0f);
        syncPassengers(carrier);
        slow(carrier, false);
        plugin.text().sound(carrier, "carry");
    }

    /** Puts the held mob down in front of the carrier (or where they stand when there is no room). */
    void dropMob(ServerPlayer carrier, Entity mob) {
        mob.stopRiding();
        Loc at = frontSpot(carrier, carrier.getYRot() + 180f, 0f);
        Vec3 to = at != null && carrier.level().noCollision(mob, mob.getDimensions(mob.getPose()).makeBoundingBox(new Vec3(at.x(), at.y(), at.z())))
                ? new Vec3(at.x(), at.y(), at.z()) : carrier.position();
        mob.snapTo(to.x, to.y, to.z, carrier.getYRot() + 180f, 0f);
        syncPassengers(carrier);
        slow(carrier, false);
    }

    // ------------------------------------------------------------------ blocks

    /** Why this block can't be carried (a message key), "" when it can, null when it isn't the kind we carry at all. */
    private String carriable(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return null;
        if (contentsOnly && !state.hasBlockEntity()) return null;
        if (blockDeny.denies(state.getBlock().builtInRegistryHolder())) return null;
        if (state.getDestroySpeed(level, pos) < 0 || state.getPistonPushReaction() == PushReaction.IMMOVEABLE) return "carry.block-cant";
        // Two-block things (beds, doors, tall plants) would come apart.
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || state.hasProperty(BlockStateProperties.BED_PART)) return "carry.block-cant";
        if (plugin.isBankBlock(level, pos) || FeatherEvents.isProtected(level, pos)) return "carry.block-cant";
        net.axther.aftrhrs.core.Hooks.Mailboxes mail = plugin.service(net.axther.aftrhrs.core.Hooks.Mailboxes.class);
        if (mail != null && mail.isMailbox(level, pos)) return "carry.block-cant";
        // A locked block stays where its lock is (the lock belongs to the spot).
        if (plugin.lockedAgainst(level, pos, new UUID(0L, 0L))) return "carry.block-locked";
        if (!p.mayBuild() || !plugin.claimAllows(p, Loc.of(level, pos), ClaimAction.BUILD)) return "carry.block-cant";
        return "";
    }

    /** Sneak + right-click with empty hands: pick the block up. True when the click was ours. */
    private boolean pickUp(ServerPlayer p, BlockHitResult hit) {
        if (!blocks || !p.isShiftKeyDown() || !p.getMainHandItem().isEmpty() || !p.getOffhandItem().isEmpty()) return false;
        if (!Perms.has(p, "feather.carry")) return false;
        ServerLevel level = p.level();
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        String why = carriable(p, level, pos, state);
        if (why == null) return false;
        Text text = plugin.text();
        if (why.isEmpty() && carryingAnything(p)) why = "carry.hands-full";
        BlockEntity be = level.getBlockEntity(pos);
        if (why.isEmpty() && !PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, p, pos, state, be)) why = "carry.block-cant";
        if (!why.isEmpty()) {
            text.actionBar(p, why);
            text.sound(p, "error");
            return true;
        }
        CompoundTag data = be == null ? new CompoundTag() : be.saveWithFullMetadata(level.registryAccess());
        BlockState held = state;
        if (held.hasProperty(ChestBlock.TYPE)) held = held.setValue(ChestBlock.TYPE, ChestType.SINGLE);
        if (held.hasProperty(BlockStateProperties.WATERLOGGED)) held = held.setValue(BlockStateProperties.WATERLOGGED, false);
        // Lift it out without its block entity spilling (its data comes along) or anything dropping.
        level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
        p.setAttached(CarriedBlock.CARRIED, new CarriedBlock(held, data));
        slow(p, true);
        level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 1f, 0.8f);
        text.actionBar(p, "carry.block-carrying", Text.component("block", net.axther.aftrhrs.core.Adv.adventure(held.getBlock().getName())));
        return true;
    }

    /** Right-click a surface while carrying a block: set it down there, facing the carrier. */
    private void place(ServerPlayer p, BlockHitResult hit) {
        CarriedBlock c = p.getAttached(CarriedBlock.CARRIED);
        if (c == null) return;
        ServerLevel level = p.level();
        ItemStack proxy = proxy(c.state());
        BlockPlaceContext ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, proxy, hit);
        Text text = plugin.text();
        if (!ctx.canPlace()) {
            text.actionBar(p, "carry.no-room");
            return;
        }
        BlockPos pos = ctx.getClickedPos();
        BlockState s = oriented(c.state(), ctx);
        if (!s.canSurvive(level, pos) || !level.isUnobstructed(s, pos, CollisionContext.placementContext(p))) {
            text.actionBar(p, "carry.no-room");
            return;
        }
        if (!p.mayBuild() || !plugin.claimAllows(p, Loc.of(level, pos), ClaimAction.BUILD)
                || !FeatherEvents.BLOCK_PLACE.invoker().allow(p, level, pos, s, proxy)) {
            text.actionBar(p, "carry.block-cant-here");
            text.sound(p, "error");
            return;
        }
        put(level, pos, s, c.data());
        p.removeAttached(CarriedBlock.CARRIED);
        slow(p, false);
        level.playSound(null, pos, s.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.9f);
        FeatherEvents.BLOCK_PLACED.invoker().placed(p, level, pos, s, proxy);
    }

    /** Places the block and gives its block entity back its data. */
    private static void put(ServerLevel level, BlockPos pos, BlockState s, CompoundTag data) {
        level.setBlock(pos, s, Block.UPDATE_ALL);
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && !data.isEmpty()) {
            be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
            be.setChanged();
            level.sendBlockUpdated(pos, s, s, Block.UPDATE_ALL);
        }
    }

    /** The carried block's item (for placement rules and events); stone for a block without one. */
    private static ItemStack proxy(BlockState s) {
        Item item = s.getBlock().asItem();
        return new ItemStack(item == Items.AIR ? Items.STONE : item);
    }

    /** Properties that depend on how it is placed; everything else stays as it was picked up. */
    private static final Set<Property<?>> PLACEMENT = Set.of(BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER, BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.ROTATION_16, BlockStateProperties.CHEST_TYPE, BlockStateProperties.WATERLOGGED,
            BlockStateProperties.ATTACH_FACE);

    private static BlockState oriented(BlockState carried, BlockPlaceContext ctx) {
        BlockState placed = carried.getBlock().getStateForPlacement(ctx);
        if (placed == null) return carried;
        BlockState out = placed;
        for (Property<?> prop : carried.getProperties()) if (!PLACEMENT.contains(prop) && out.hasProperty(prop)) out = copy(out, carried, prop);
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> prop) {
        return to.setValue(prop, from.getValue(prop));
    }

    /**
     * Sets a carried block down without a click (death, the module turning off): on the spot or next to it, else its
     * contents and the block spill there as items. Never lost.
     */
    void release(ServerPlayer p, BlockPos near) {
        CarriedBlock c = p.getAttached(CarriedBlock.CARRIED);
        if (c == null) return;
        p.removeAttached(CarriedBlock.CARRIED);
        slow(p, false);
        ServerLevel level = p.level();
        for (BlockPos at : BlockPos.withinManhattan(near, 2)) {
            if (!level.isLoaded(at) || !level.getBlockState(at).canBeReplaced() || !c.state().canSurvive(level, at)) continue;
            if (!level.getBlockState(at).getFluidState().isEmpty()) continue;
            put(level, at.immutable(), c.state(), c.data());
            return;
        }
        if (!c.data().isEmpty() && BlockEntity.loadStatic(near, c.state(), c.data(), level.registryAccess()) instanceof Container box) {
            Containers.dropContents(level, near, box);
        }
        Item item = c.state().getBlock().asItem();
        if (item != Items.AIR) Containers.dropItemStack(level, near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5, new ItemStack(item));
    }

    /** {@code /carry drop} with a block: set it down in front of the carrier. */
    private boolean releaseInFront(ServerPlayer p) {
        Loc at = frontSpot(p, p.getYRot(), 0f);
        if (at == null) return false;
        BlockPos spot = at.blockPos();
        CarriedBlock c = p.getAttached(CarriedBlock.CARRIED);
        ServerLevel level = p.level();
        if (c == null || !level.getBlockState(spot).canBeReplaced() || !c.state().canSurvive(level, spot)
                || !plugin.claimAllows(p, Loc.of(level, spot), ClaimAction.BUILD)) return false;
        place(p, new BlockHitResult(Vec3.atCenterOf(spot), Direction.UP, spot, false));
        return !carryingBlock(p);
    }

    // ------------------------------------------------------------------ /carry

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        ServerPlayer p = sender.player();
        if (p == null) {
            plugin.text().send(sender, "general.players-only");
            return true;
        }
        String arg = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (arg) {
            case "toggle" -> toggle(p);
            case "drop" -> {
                Entity mob = heldMob(p);
                if (carried(p) != null) drop(p);
                else if (mob != null) dropMob(p, mob);
                else if (carryingBlock(p)) {
                    if (!releaseInFront(p)) plugin.text().actionBar(p, "carry.no-room");
                } else plugin.text().send(p, "carry.nobody");
            }
            default -> plugin.text().send(p, "carry.help", Text.component("state", plugin.text().msg(allows(p) ? "carry.state-on" : "carry.state-off")));
        }
        return true;
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        if (args.length != 1) return List.of();
        return List.of("toggle", "drop").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }

    // ------------------------------------------------------------------ deny lists

    /** Ids ({@code spawner}), tags ({@code #minecraft:beds}) and whole mods ({@code aftrhrs:*}) from a config list. */
    private record Deny(Set<Identifier> ids, Set<String> tags, Set<String> mods) {
        static final Deny NONE = new Deny(Set.of(), Set.of(), Set.of());

        static Deny read(List<String> list, net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>> registry) {
            Set<Identifier> ids = new HashSet<>();
            Set<String> tags = new HashSet<>(), mods = new HashSet<>();
            for (String raw : list) {
                String s = raw.trim().toLowerCase(Locale.ROOT);
                if (s.isEmpty()) continue;
                if (s.endsWith(":*")) mods.add(s.substring(0, s.length() - 2));
                else if (s.startsWith("#")) {
                    Identifier id = Identifier.tryParse(s.substring(1));
                    if (id != null) tags.add(id.toString());
                } else {
                    Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
                    if (id != null) ids.add(id);
                }
            }
            return new Deny(ids, tags, mods);
        }

        boolean denies(net.minecraft.core.Holder<?> holder) {
            var key = holder.unwrapKey().orElse(null);
            if (key == null) return false;
            Identifier id = key.identifier();
            if (ids.contains(id) || mods.contains(id.getNamespace())) return true;
            if (tags.isEmpty()) return false;
            return holder.tags().anyMatch(t -> tags.contains(((TagKey<?>) t).location().toString()));
        }
    }
}
