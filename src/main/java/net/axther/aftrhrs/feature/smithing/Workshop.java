package net.axther.aftrhrs.feature.smithing;

import net.axther.aftrhrs.feature.smithing.station.ForgeBE;
import net.axther.aftrhrs.feature.smithing.station.StationBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Shared workshop rules: what can go on the coals, model-space to world positions, hot pieces in a bare inventory,
 * a fan blowing into a hearth, and the settings from {@code smithing.yml}.
 */
public final class Workshop {
    static volatile float heatSpeed = 1f;
    static volatile boolean edge = true, burns = true;

    private Workshop() {
    }

    public static float heatSpeed() {
        return heatSpeed;
    }

    public static boolean edgeEnabled() {
        return edge;
    }

    /** Parts, ingots and clay molds go on the coals. */
    public static boolean workable(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.getItem() instanceof PartItem) return true;
        if (s.getItem() instanceof MoldItem m) return m.kind == MoldItem.Kind.CLAY_UNFIRED;
        return Smelt.ingotMetal(s) != null;
    }

    /** A point given in model pixels of a north-facing station, in the world for a station facing {@code f}. */
    public static Vec3 local(BlockPos pos, Direction f, float x, float y, float z) {
        float rx, rz;
        switch (f) {
            case EAST -> {
                rx = 16 - z;
                rz = x;
            }
            case SOUTH -> {
                rx = 16 - x;
                rz = 16 - z;
            }
            case WEST -> {
                rx = z;
                rz = 16 - x;
            }
            default -> {
                rx = x;
                rz = z;
            }
        }
        return new Vec3(pos.getX() + rx / 16.0, pos.getY() + y / 16.0, pos.getZ() + rz / 16.0);
    }

    /** Every 20 ticks: hot pieces loose in an inventory burn (tongs hold them safely); cold ones lose their heat tag. */
    static void tickHands(ServerPlayer p) {
        long now = p.level().getGameTime();
        boolean hot = false;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Heat.Stamp st = s.get(Smithing.HEAT);
            if (st == null) continue;
            float t = st.at(now);
            if (t < Heat.AIR + 40) s.remove(Smithing.HEAT);
            else if (t >= Heat.BURNS) hot = true;
        }
        if (hot && burns && now % 40 == 0) Heat.burn(p);
    }

    /** A powered fan's stream ends at {@code pos}: a hearth (or the bellows feeding one) gets pumped. */
    public static void airInto(ServerLevel level, BlockPos pos, Direction flow) {
        if (level.getBlockEntity(pos) instanceof ForgeBE forge) {
            forge.pump();
            return;
        }
        var s = level.getBlockState(pos);
        if (s.getBlock() instanceof StationBlock sb && sb.station == net.axther.aftrhrs.feature.smithing.station.Station.BELLOWS) {
            net.axther.aftrhrs.feature.smithing.station.Bellows.pumpFrom(level, pos, s);
        }
    }

    static void configure(float heat, boolean edgeOn, boolean burnOn) {
        heatSpeed = Math.max(0.1f, heat);
        edge = edgeOn;
        burns = burnOn;
    }
}
