package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sitting, lying and crawling.
 *
 * <p>{@code /sit} sits down where you stand; right-clicking a stair or bottom slab with an empty hand sits on it (facing
 * away from a stair's back). The seat is an invisible item display (tagged {@code feather:seat}; any left in a saved
 * chunk are removed as they load); standing up (sneak) puts you back on top of the block. One person per block.
 *
 * <p>{@code /lay} holds the player in the sleeping pose, so everyone else sees them lying on the ground. Walking,
 * jumping or sneaking stands them up. {@code /swim} (/crawl) holds the swimming pose and sends only that player a fake
 * barrier just above their head, which makes their own client crawl too; it follows them and the world never changes.
 * Sneaking or {@code /swim} again stands them up.
 */
public final class SitModule implements FeatherModule, Travel.Named {
    static final String SEAT_KEY = "feather:seat";
    private static volatile SitModule active;

    private record Spot(ResourceKey<Level> level, BlockPos pos) {
    }

    private final Feather plugin;
    private final Map<UUID, Display.ItemDisplay> seats = new HashMap<>();
    private final Map<Spot, UUID> taken = new HashMap<>();
    private boolean stairs;
    private boolean slabs;
    private double offset;
    /** Crawling players and where their fake barrier is, with its dimension (null while none is needed). */
    private final Map<UUID, Spot> crawlers = new HashMap<>();
    /** Lying players and where they lay down. */
    private final Map<UUID, Loc> layers = new HashMap<>();

    public SitModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Sit, lay and crawl";
    }

    @Override
    public void enable() {
        // Seats left behind (a crash while someone sat) in chunks that are already loaded.
        for (ServerLevel w : Feather.server().getAllLevels()) {
            List<Entity> stale = new ArrayList<>();
            for (Entity e : w.getAllEntities()) if (e instanceof Display.ItemDisplay && Pdc.has(e, SEAT_KEY)) stale.add(e);
            for (Entity e : stale) e.discard();
        }
        reload();
        active = this;
        Travel.bind(plugin, this, "sit", "lay", "swim");
        plugin.once("sit.events", SitModule::hookEvents);
        Hub hub = plugin.hub();
        hub.add("sit", Hub.Group.STYLE, Hub.ORDER_SIT, "hub-pose", "feather.sit", p -> Travel.perform(p, "sit"));
        hub.add("lay", Hub.Group.STYLE, Hub.ORDER_LAY, "hub-pose", "feather.lay", p -> Travel.perform(p, "lay"));
        hub.add("crawl", Hub.Group.STYLE, Hub.ORDER_CRAWL, "hub-pose", "feather.swim", p -> Travel.perform(p, "swim"));
    }

    /** Once per JVM: every listener checks for a running module first. */
    private static void hookEvents() {
        // Paper seats were never saved (setPersistent(false)); if one ever was, it goes. Ours are cleaned up below.
        Legacy.converter((level, entity, bukkit) -> {
            if (!bukkit.contains(SEAT_KEY)) return false;
            entity.discard();
            return true;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Display.ItemDisplay) || !Pdc.has(entity, SEAT_KEY)) return;
            SitModule m = active;
            if (m == null || !m.seats.containsValue(entity)) entity.discard();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SitModule m = active;
            if (m != null) m.tick();
        });
        FeatherEvents.FORCED_POSE.register(p -> {
            SitModule m = active;
            if (m == null) return null;
            if (m.layers.containsKey(p.getUUID())) return Pose.SLEEPING;
            if (m.crawlers.containsKey(p.getUUID())) return Pose.SWIMMING;
            return null;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            SitModule m = active;
            if (m == null || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            return m.onClick(sp, hit.getBlockPos(), hit.getDirection()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            SitModule m = active;
            if (m == null) return;
            UUID id = m.taken.get(new Spot(world.dimension(), pos.immutable()));
            if (id != null) m.stand(id, true);
        });
        FeatherEvents.PLAYER_TELEPORTED.register((p, from, to, cause) -> {
            // Runs after the move. Seats notice on their own (the rider is gone); a crawler's fake barrier is put back
            // only within the same world: after a dimension change the client already dropped it with its old level.
            SitModule m = active;
            if (m == null || cause == TeleportCause.UNKNOWN || cause == TeleportCause.DISMOUNT || !m.posing(p)) return;
            if (from.level() != p.level()) m.dropPose(p);
            else m.stopPose(p);
        });
        FeatherEvents.PLAYER_DEATH.register((p, source) -> {
            SitModule m = active;
            if (m == null) return;
            m.stand(p.getUUID(), false);
            m.stopPose(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            SitModule m = active;
            if (m == null) return;
            m.stand(h.player.getUUID(), false);
            m.stopPose(h.player);
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
            SitModule m = active;
            if (m != null) m.dropPose(p);
        });
        FeatherEvents.PLAYER_MOVE_BLOCK.register((p, from, to) -> {
            SitModule m = active;
            if (m == null || !m.crawlers.containsKey(p.getUUID())) return;
            // Follow right away (next tick the player stands at the new spot).
            UUID id = p.getUUID();
            Tasks.later(1L, () -> {
                ServerPlayer now = m.plugin.player(id);
                if (now != null && m.crawlers.containsKey(id)) m.updateBarrier(now);
            });
        });
    }

    @Override
    public void reload() {
        stairs = plugin.getConfig().getBoolean("sit.click-stairs", true);
        slabs = plugin.getConfig().getBoolean("sit.click-slabs", true);
        offset = plugin.getConfig().getDouble("sit.height-offset", 0.0);
    }

    @Override
    public void disable() {
        for (UUID id : List.copyOf(seats.keySet())) stand(id, false);
        for (UUID id : List.copyOf(crawlers.keySet())) {
            ServerPlayer p = plugin.player(id);
            if (p != null) stopPose(p);
        }
        crawlers.clear();
        layers.clear();
        if (active == this) active = null;
    }

    // ------------------------------------------------------------------ lay / crawl

    boolean posing(ServerPlayer p) {
        return crawlers.containsKey(p.getUUID()) || layers.containsKey(p.getUUID());
    }

    private static boolean canPose(ServerPlayer p) {
        return !p.isPassenger() && !p.getAbilities().flying && !p.isFallFlying() && !p.isSwimming() && !p.isInWater()
                && !p.isDeadOrDying() && !p.isSpectator();
    }

    private void lay(ServerPlayer p) {
        layers.put(p.getUUID(), Loc.of(p));
        p.setPose(Pose.SLEEPING);
        plugin.text().actionBar(p, "sit.lying");
        plugin.advance(p, "event", "lay");
    }

    private void crawl(ServerPlayer p) {
        crawlers.put(p.getUUID(), null);
        p.setPose(Pose.SWIMMING);
        updateBarrier(p);
        plugin.text().actionBar(p, "sit.crawling");
        plugin.advance(p, "event", "swim");
    }

    /** Ends /lay or /swim: lets the pose go and takes the fake barrier away again. */
    void stopPose(ServerPlayer p) {
        UUID id = p.getUUID();
        boolean was = layers.remove(id) != null | crawlers.containsKey(id);
        Spot barrier = crawlers.remove(id);
        if (barrier != null && !p.hasDisconnected()) restore(p, barrier);
        if (was && !p.hasDisconnected()) p.setPose(Pose.STANDING);
    }

    /** After a dimension change: the client threw its old level away (and the fake barrier with it), so only drop state. */
    private void dropPose(ServerPlayer p) {
        UUID id = p.getUUID();
        boolean was = layers.remove(id) != null | crawlers.containsKey(id);
        crawlers.remove(id);
        if (was && !p.hasDisconnected()) p.setPose(Pose.STANDING);
    }

    /** Moves the crawler's fake barrier to the block above their head (only where that block is open). */
    private void updateBarrier(ServerPlayer p) {
        UUID id = p.getUUID();
        Spot old = crawlers.get(id);
        BlockPos head = p.blockPosition().above();
        ServerLevel w = p.level();
        if (!w.hasChunkAt(head)) return; // never load a chunk for a fake block
        BlockState hs = w.getBlockState(head);
        Spot want = hs.getCollisionShape(w, head).isEmpty() && hs.getFluidState().isEmpty() ? new Spot(w.dimension(), head.immutable()) : null;
        if (want != null && want.equals(old)) return;
        if (old != null) restore(p, old);
        if (want != null) p.connection.send(new ClientboundBlockUpdatePacket(want.pos(), Blocks.BARRIER.defaultBlockState()));
        crawlers.put(id, want);
    }

    /**
     * Shows the player the real block again, with its sign text, banner pattern or head (block entity) too. Never loads
     * a chunk and never sends a block from another world.
     */
    private static void restore(ServerPlayer p, Spot at) {
        ServerLevel w = p.level();
        if (w.dimension() != at.level() || !w.hasChunkAt(at.pos())) return; // other world, or the client dropped that chunk too
        p.connection.send(new ClientboundBlockUpdatePacket(w, at.pos()));
        BlockEntity be = w.getBlockEntity(at.pos());
        if (be != null) {
            Packet<ClientGamePacketListener> pk = be.getUpdatePacket();
            if (pk != null) p.connection.send(pk);
        }
    }

    boolean sitting(ServerPlayer p) {
        return seats.containsKey(p.getUUID());
    }

    /** Sits the player on {@code block} with their seat surface at {@code surfaceY}. */
    private boolean sit(ServerPlayer p, BlockPos block, double surfaceY, Float yaw) {
        if (sitting(p) || posing(p) || p.isPassenger() || p.getAbilities().flying || p.isFallFlying()) return false;
        Spot spot = new Spot(p.level().dimension(), block.immutable());
        if (taken.containsKey(spot)) return false;
        ServerLevel w = p.level();
        Display.ItemDisplay seat = EntityTypes.ITEM_DISPLAY.create(w, EntitySpawnReason.COMMAND);
        if (seat == null) return false;
        seat.snapTo(block.getX() + 0.5, surfaceY + offset, block.getZ() + 0.5, yaw != null ? yaw : p.getYRot(), 0f);
        Pdc.putString(seat, SEAT_KEY, "1");
        seats.put(p.getUUID(), seat);
        if (!w.addFreshEntity(seat) || !p.startRiding(seat, true, true)) {
            seats.remove(p.getUUID());
            seat.discard();
            return false;
        }
        taken.put(spot, p.getUUID());
        plugin.text().actionBar(p, "sit.sitting");
        plugin.advance(p, "event", "sit");
        return true;
    }

    private void stand(UUID id, boolean lift) {
        Display.ItemDisplay seat = seats.remove(id);
        taken.values().removeIf(id::equals);
        if (seat == null) return;
        ServerPlayer p = plugin.player(id);
        Loc top = Loc.of(seat);
        seat.ejectPassengers();
        seat.discard();
        if (p != null && lift) {
            // A seat inside a stair or slab: step out on top of it. Anywhere else: stand where we sat.
            ServerLevel w = top.level();
            BlockPos seatBlock = top.blockPos();
            boolean open = w.getBlockState(seatBlock).getCollisionShape(w, seatBlock).isEmpty();
            Loc to = (open ? top : Loc.of(w, seatBlock).add(0, 1, 0)).withRotation(p.getYRot(), p.getXRot());
            Tasks.later(1L, () -> {
                ServerPlayer now = plugin.player(id);
                // Skip if something else (a teleport) already moved them away from the seat.
                if (now != null && !now.isPassenger() && now.level() == to.level() && Loc.of(now).distanceSquared(to) < 4) {
                    plugin.teleportNow(now, to.withRotation(now.getYRot(), now.getXRot()), TeleportCause.PLUGIN);
                }
            });
        }
    }

    /** Every tick: riders who left their seat (sneak, a teleport, a dismount) stand up; lying/crawling ends on cue. */
    private void tick() {
        if (!seats.isEmpty()) {
            for (Map.Entry<UUID, Display.ItemDisplay> e : List.copyOf(seats.entrySet())) {
                ServerPlayer p = plugin.player(e.getKey());
                if (p == null || e.getValue().isRemoved() || p.getVehicle() != e.getValue()) stand(e.getKey(), p != null);
            }
        }
        if (layers.isEmpty() && crawlers.isEmpty()) return;
        for (UUID id : List.copyOf(layers.keySet())) {
            ServerPlayer p = plugin.player(id);
            if (p == null) {
                layers.remove(id);
                continue;
            }
            Loc start = layers.get(id);
            double dx = p.getX() - start.x(), dz = p.getZ() - start.z();
            if (p.level() != start.level() || dx * dx + dz * dz > 0.04 || p.getY() - start.y() > 0.3
                    || p.isShiftKeyDown() || p.getAbilities().flying) stopPose(p);
        }
        for (UUID id : List.copyOf(crawlers.keySet())) {
            ServerPlayer p = plugin.player(id);
            if (p == null) {
                crawlers.remove(id);
                continue;
            }
            if (p.isShiftKeyDown() || p.isInWater() || p.getAbilities().flying || p.isFallFlying()) stopPose(p);
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (!command.equals("sit")) {
            boolean lay = command.equals("lay");
            boolean already = lay ? layers.containsKey(p.getUUID()) : crawlers.containsKey(p.getUUID());
            if (sitting(p)) stand(p.getUUID(), true);
            stopPose(p);
            if (already) return true;
            if (!canPose(p) || (lay && !p.onGround())) {
                text.send(p, "sit.cant");
                return true;
            }
            if (lay) lay(p);
            else crawl(p);
            return true;
        }
        if (posing(p)) stopPose(p);
        if (sitting(p)) {
            stand(p.getUUID(), true);
            return true;
        }
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.1, p.getZ());
        if (!p.onGround() || p.level().getBlockState(below).getCollisionShape(p.level(), below).isEmpty()) {
            text.send(p, "sit.not-on-ground");
            return true;
        }
        if (!sit(p, p.blockPosition(), p.getY(), null)) text.send(p, "sit.cant");
        return true;
    }

    // ------------------------------------------------------------------ clicking a stair or slab

    /** True when the first block the player's eye ray meets on the way to {@code pos} is {@code pos} itself. */
    static boolean lineOfSight(ServerPlayer p, BlockPos pos) {
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition();
        net.minecraft.world.phys.Vec3 target = net.minecraft.world.phys.Vec3.atCenterOf(pos);
        net.minecraft.world.phys.BlockHitResult hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, target,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private boolean onClick(ServerPlayer p, BlockPos pos, Direction face) {
        if (p.isShiftKeyDown() || !p.getMainHandItem().isEmpty() || !Perms.has(p, "feather.sit")) return false;
        if (face == Direction.DOWN) return false;
        ServerLevel w = p.level();
        if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 9) return false;   // cheap check first
        BlockPos up = pos.above();
        if (!w.getBlockState(up).getCollisionShape(w, up).isEmpty()) return false;
        // Vanilla checks reach, not line of sight: never seat someone on a block behind a wall (a phase exploit).
        if (!lineOfSight(p, pos)) return false;
        if (!plugin.claimAllows(p, Loc.of(w, pos), net.axther.aftrhrs.core.ClaimAction.ACCESS) || plugin.travelLocked(p)) return false;
        BlockState b = w.getBlockState(pos);
        Float yaw = null;
        double surface;
        if (stairs && b.getBlock() instanceof StairBlock && b.getValue(StairBlock.HALF) == Half.BOTTOM) {
            surface = pos.getY() + 0.5;
            // Face out of the stair: opposite of the side its back is on.
            yaw = switch (b.getValue(StairBlock.FACING)) {
                case NORTH -> 0f;
                case SOUTH -> 180f;
                case WEST -> -90f;
                case EAST -> 90f;
                default -> null;
            };
        } else if (slabs && b.getBlock() instanceof SlabBlock && b.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
            surface = pos.getY() + 0.5;
        } else {
            return false;
        }
        return sit(p, pos, surface, yaw);
    }
}
