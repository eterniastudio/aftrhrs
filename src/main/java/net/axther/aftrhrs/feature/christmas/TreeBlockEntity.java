package net.axther.aftrhrs.feature.christmas;

import com.mojang.serialization.Codec;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.feature.furniture.FurnitureBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The fir tree's decorations: an ornament id per {@link TreeSlots} slot ("" = empty), the topper (star / angel) and
 * whether tree lights are wound on (the piece's {@code lit} state switches them). Drawn by the client's tree renderer.
 */
public class TreeBlockEntity extends FurnitureBE {
    private final String[] slots = new String[TreeSlots.SLOTS.length];
    private String topper = "";
    private boolean lights;

    public TreeBlockEntity(BlockPos pos, BlockState state) {
        super(ChristmasContent.TREE, pos, state);
        Arrays.fill(slots, "");
    }

    public String slot(int i) {
        return slots[i];
    }

    public String topper() {
        return topper;
    }

    public boolean hasLights() {
        return lights;
    }

    public int ornamentCount() {
        int n = 0;
        for (String s : slots) if (!s.isEmpty()) n++;
        return n;
    }

    /** A world position as the tree's own model pixels (north-facing frame of the origin block). */
    public Vec3 local(Vec3 world) {
        double x = (world.x - worldPosition.getX()) * 16 - 8, y = (world.y - worldPosition.getY()) * 16,
                z = (world.z - worldPosition.getZ()) * 16 - 8;
        int q = FurnitureBlock.quadrant(getBlockState());
        // undo q clockwise quarter turns: (x, z) -> (z, -x) per turn
        for (int i = 0; i < (q & 3); i++) {
            double nx = z, nz = -x;
            x = nx;
            z = nz;
        }
        return new Vec3(x + 8, y, z + 8);
    }

    private static double dist2(float[] s, Vec3 p) {
        double dx = s[0] - p.x, dy = (s[1] - p.y) * 0.8, dz = s[2] - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The free slot nearest to a point (model px), or -1 when the tree is full. */
    public int nearestFree(Vec3 p) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < slots.length; i++) {
            if (!slots[i].isEmpty()) continue;
            double d = dist2(TreeSlots.SLOTS[i], p);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    public void hang(int slot, String id) {
        slots[slot] = id;
        sync();
    }

    public void setTopper(String id) {
        topper = id == null ? "" : id;
        sync();
    }

    public void setLights(boolean on) {
        lights = on;
        sync();
    }

    /**
     * Takes back the decoration nearest to a point (model px): an ornament, the topper when the point is up high or
     * nothing else is left, then the lights. Null when the tree is bare.
     */
    public @Nullable ItemStack takeNearest(Vec3 p) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isEmpty()) continue;
            double d = dist2(TreeSlots.SLOTS[i], p);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        boolean topNear = !topper.isEmpty() && (best < 0 || p.y > TreeSlots.TOP[1] - 6);
        if (topNear) {
            ItemStack s = ArcanaContent.stack(topper, 1);
            topper = "";
            sync();
            return s;
        }
        if (best >= 0) {
            ItemStack s = ArcanaContent.stack(slots[best], 1);
            slots[best] = "";
            sync();
            return s;
        }
        if (lights) {
            lights = false;
            sync();
            return ArcanaContent.stack(ChristmasContent.TREE_LIGHTS, 1);
        }
        return null;
    }

    /** Everything on the tree, taken off it. */
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (!slots[i].isEmpty()) out.add(ArcanaContent.stack(slots[i], 1));
            slots[i] = "";
        }
        if (!topper.isEmpty()) out.add(ArcanaContent.stack(topper, 1));
        if (lights) out.add(ArcanaContent.stack(ChristmasContent.TREE_LIGHTS, 1));
        topper = "";
        lights = false;
        out.removeIf(ItemStack::isEmpty);
        setChanged();
        return out;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null || level.isClientSide()) return;
        for (ItemStack s : takeAll()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        List<String> l = in.read("Ornaments", Codec.STRING.listOf()).orElse(List.of());
        for (int i = 0; i < slots.length; i++) slots[i] = i < l.size() ? l.get(i) : "";
        topper = in.getStringOr("Topper", "");
        lights = in.getBooleanOr("Lights", false);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (ornamentCount() > 0) out.store("Ornaments", Codec.STRING.listOf(), List.of(slots));
        if (!topper.isEmpty()) out.putString("Topper", topper);
        if (lights) out.putBoolean("Lights", true);
    }
}
