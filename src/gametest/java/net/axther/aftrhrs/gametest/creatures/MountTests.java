package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMount;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Every mount: the owner rides, a friend takes the second seat, the controls move it, dismount and disconnects are clean. */
public class MountTests {
    private static ArcanaMount mount(GameTestHelper h, String id, ServerPlayer owner, Vec3 at) {
        ArcanaMount m = CT.spawn(h, ArcanaEntities.species(ArcanaEntities.type(id)), at);
        PetsTests.withCap(1000, () -> m.tamedBy(owner));
        return m;
    }

    private static void ride(ServerPlayer p, ArcanaMount m) {
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setShiftKeyDown(false);
        m.interact(p, InteractionHand.MAIN_HAND, m.position());
    }

    /** Walkers and flyers on land: ride, second seat, W moves it, Space (flyers) climbs, the passenger and the rider leave cleanly. */
    @GameTest(maxTicks = 400, padding = 32, skyAccess = true)
    public void rideEveryMount(GameTestHelper h) {
        CT.arena(h, 48, 40);
        List<String> problems = new ArrayList<>();
        int i = 0;
        for (Species s : CT.species(x -> x.kind == Species.Kind.MOUNT)) {
            ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2 + i * 8, 1, 2));
            ServerPlayer friend = CT.player(h, GameType.SURVIVAL, new Vec3(2 + i * 8, 1, 4));
            owner.setPermanentlyInvulnerable(true);
            friend.setPermanentlyInvulnerable(true);
            ArcanaMount m = mount(h, s.id, owner, new Vec3(3 + i * 8, 1, 3));
            i++;
            String id = s.id;
            ride(owner, m);
            if (owner.getVehicle() != m) {
                problems.add(id + ": the owner could not ride it");
                continue;
            }
            if (m.getControllingPassenger() != owner) problems.add(id + ": the owner does not control it");
            ride(friend, m);
            int seats = Math.max(1, s.seats.size());
            if (seats > 1 && friend.getVehicle() != m) problems.add(id + ": no second seat for a friend (" + seats + " seats)");
            if (seats > 1 && m.getControllingPassenger() != owner) problems.add(id + ": the friend took the controls");
            Vec3 start = m.position();
            String kind = m.motionKind();
            owner.zza = 1f;
            if ("flyer".equals(kind)) owner.setJumping(true);
            h.runAfterDelay(30, () -> {
                double moved = m.position().subtract(start).horizontalDistance();
                if (moved < 1.5) problems.add(id + " (" + kind + "): moved only " + String.format("%.2f", moved) + " blocks with W held");
                if ("flyer".equals(kind)) {
                    if (m.getY() < start.y + 2) problems.add(id + ": did not climb with Space held (dy " + String.format("%.2f", m.getY() - start.y) + ")");
                    if (m.onGround() || m.allowShiftDismount(owner)) problems.add(id + ": Shift would dismount in the air");
                }
                owner.zza = 0;
                owner.setJumping(false);
                // the friend logs off mid-ride (just gets off), the rider gets off in the air: gravity back
                CT.disconnect(friend);
                if (m.getPassengers().contains(friend)) problems.add(id + ": a disconnected passenger still sits on it");
                owner.stopRiding();
                if (m.isVehicle()) problems.add(id + ": still ridden after both left");
                if (m.isNoGravity()) problems.add(id + ": no gravity after its rider got off in the air");
                // riding again, then logging off: vanilla saves the mount with its only rider (UNLOADED_WITH_PLAYER)
                ride(owner, m);
                CT.disconnect(owner);
            });
            h.runAfterDelay(80, () -> {
                if (!m.isRemoved() && m.getHealth() <= 0) problems.add(id + ": died (" + CreatureSweepTests.KILLERS.get(m.getUUID()) + ")");
                if (m.isRemoved() && m.getRemovalReason() != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER)
                    problems.add(id + ": gone (" + m.getRemovalReason() + ", " + CreatureSweepTests.KILLERS.get(m.getUUID()) + ")");
            });
        }
        h.runAfterDelay(200, () -> {
            for (var e : h.getLevel().getEntitiesOfClass(ArcanaMount.class, h.getBounds().inflate(64))) e.discard();
            CT.report(h, "mounts", problems);
            h.succeed();
        });
    }

    /** The Tidemane in water: W swims, Space rises, Shift dives (and does not dismount), Shift + S lets the rider off. */
    @GameTest(maxTicks = 200, padding = 32, skyAccess = true)
    public void tidemaneSwims(GameTestHelper h) {
        CT.arena(h, 16, 16);
        ServerLevel level = h.getLevel();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y <= 12; y++) h.setBlock(new BlockPos(x, y, z), Blocks.WATER);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(8, 9, 3));
        owner.setPermanentlyInvulnerable(true);
        ArcanaMount m = mount(h, "tidemane", owner, new Vec3(8, 8, 4));
        ride(owner, m);
        h.assertTrue(owner.getVehicle() == m, "could not ride the Tidemane");
        h.runAfterDelay(10, () -> {
            h.assertValueEqual(m.motionKind(), "swimmer", "motion kind");
            Vec3 start = m.position();
            owner.zza = 1f;
            owner.setXRot(0);
            h.runAfterDelay(30, () -> {
                double moved = m.position().subtract(start).horizontalDistance();
                h.assertTrue(moved > 2, "swam only " + moved + " blocks");
                owner.zza = 0;
                owner.setShiftKeyDown(true);
                h.assertFalse(m.allowShiftDismount(owner), "Shift dismounts in water");
                double y0 = m.getY();
                h.runAfterDelay(20, () -> {
                    h.assertTrue(m.getY() < y0 - 0.3 || m.onGround(), "did not dive with Shift held (y " + y0 + " -> " + m.getY() + ")");
                    owner.setShiftKeyDown(false);
                    CT.disconnect(owner);
                    h.runAfterDelay(5, () -> {
                        h.assertFalse(m.isVehicle(), "still ridden after the rider left");
                        m.discard();
                        h.succeed();
                    });
                });
            });
        });
    }

    /** Chat lines and action bars ("[bar] " ...) sent to a bot since the last read, from its channel (both packet kinds). */
    private static List<String> sent(Bots.Bot b) {
        io.netty.channel.embedded.EmbeddedChannel ch = (io.netty.channel.embedded.EmbeddedChannel) CT.get(b, "channel");
        ch.runPendingTasks();
        ch.flushOutbound();
        List<String> out = new ArrayList<>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            if (o instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket s) out.add((s.overlay() ? "[bar] " : "") + s.content().getString());
            else if (o instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket a) out.add("[bar] " + a.text().getString());
        }
        return out;
    }

    /**
     * Stay / follow feedback goes through messages.yml creatures.stay / creatures.follow (the legacy English while the
     * keys are missing): either way the action bar reads the same, and a name tag's {@code <red>} and {@code &c} show
     * literally (neither MiniMessage nor legacy codes in a name are parsed).
     */
    @GameTest(maxTicks = 40)
    public void stayFollowTextKeepsNameLiteral(GameTestHelper h) {
        CT.arena(h, 8, 8);
        Bots.Bot b = Bots.join(h, Bots.name("stay"));
        Species s = CT.species(x -> x.kind == Species.Kind.MOUNT).get(0);
        ArcanaMount m = mount(h, s.id, b.player, new Vec3(3, 1, 3));
        String name = "<red>Bo&cb";
        m.setCustomName(net.minecraft.network.chat.Component.literal(name));
        sent(b);
        m.setStaying(true, b.player);
        List<String> stay = sent(b);
        m.setStaying(false, b.player);
        List<String> follow = sent(b);
        h.assertTrue(stay.contains("[bar] " + name + " will stay here."), "stay text: " + stay);
        h.assertTrue(follow.contains("[bar] " + name + " is following you."), "follow text: " + follow);
        m.discard();
        b.leave();
        h.succeed();
    }
}
