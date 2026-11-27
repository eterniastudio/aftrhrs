package net.axther.aftrhrs.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The furniture part of a content entry ({@code "furniture"} in content.json), parsed once at registration. */
public final class FurnitureSpec {
    public enum Facing { H4, ROT16, ALL6 }

    /** STORAGE = own inventory; SHULKER = own inventory that stays in the item when broken (Oraxen SHULKER). */
    public enum StorageType {
        STORAGE, SHULKER, PERSONAL, ENDERCHEST, DISPOSAL;

        public boolean ownInventory() {
            return this == STORAGE || this == SHULKER;
        }
    }

    public record Seat(double x, double y, double z, float yaw) {
    }

    public record Storage(StorageType type, int rows, JsonObject title, @Nullable String openSound,
                          @Nullable String closeSound, float volume, float pitch) {
    }

    /** {@code light}: light level per stage (null = the piece's own light at every stage). */
    public record Stages(int count, float chance, int lightMin, float boostChance, float bonemeal, int initial, int @Nullable [] light) {
    }

    public record Toggle(String property, boolean defaultValue, boolean player, @Nullable String sound) {
    }

    public final String id;
    public final Facing facing;
    /** Rotation step in 16ths of a turn (1 = 16-way, 2 = 8-way, 4 = 4-way). */
    public final int rotStep;
    public final boolean rotatable;
    public final boolean floor, wall, roof;
    /** Cells relative to the origin (part 0), facing north: x east, y up, z south. */
    public final BlockPos[] parts;
    /** [part][quadrant] shapes. */
    public final VoxelShape[][] outline, collision;
    public final int[] light;
    public final List<Seat> seats = new ArrayList<>();
    public final @Nullable Storage storage;
    public final boolean jukebox;
    public final @Nullable Stages stages;
    public final @Nullable Toggle toggle;
    public final boolean powered;
    public final @Nullable String hearthLitItem;
    public final JsonObject sounds;

    FurnitureSpec(String id, JsonObject j) {
        this.id = id;
        this.facing = switch (j.get("facing").getAsString()) {
            case "rot16" -> Facing.ROT16;
            case "all6" -> Facing.ALL6;
            default -> Facing.H4;
        };
        this.rotStep = j.has("rot_step") ? j.get("rot_step").getAsInt() : 4;
        this.rotatable = !j.has("rotatable") || j.get("rotatable").getAsBoolean();
        JsonObject place = j.getAsJsonObject("place");
        this.floor = place.get("floor").getAsBoolean();
        this.wall = place.get("wall").getAsBoolean();
        this.roof = place.get("roof").getAsBoolean();
        JsonArray ps = j.getAsJsonArray("parts");
        parts = new BlockPos[ps.size()];
        for (int i = 0; i < ps.size(); i++) {
            JsonArray c = ps.get(i).getAsJsonArray();
            parts[i] = new BlockPos(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt());
        }
        outline = shapes(j.getAsJsonArray("outline"), parts.length, true);
        collision = shapes(j.getAsJsonArray("collision"), parts.length, false);
        light = new int[parts.length];
        JsonArray ls = j.getAsJsonArray("light");
        for (int i = 0; i < parts.length && ls != null && i < ls.size(); i++) light[i] = ls.get(i).getAsInt();
        if (j.has("seats")) {
            for (JsonElement e : j.getAsJsonArray("seats")) {
                JsonArray a = e.getAsJsonArray();
                seats.add(new Seat(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsFloat()));
            }
        }
        if (j.has("storage")) {
            JsonObject s = j.getAsJsonObject("storage");
            StorageType t;
            try {
                t = StorageType.valueOf(s.get("type").getAsString());
            } catch (IllegalArgumentException ex) {
                t = StorageType.STORAGE;
            }
            storage = new Storage(t, Math.max(1, Math.min(6, s.get("rows").getAsInt())), s.getAsJsonObject("title"),
                    str(s, "open_sound"), str(s, "close_sound"),
                    s.has("volume") ? s.get("volume").getAsFloat() : 1f, s.has("pitch") ? s.get("pitch").getAsFloat() : 1f);
        } else {
            storage = null;
        }
        jukebox = j.has("jukebox");
        if (j.has("stages")) {
            JsonObject s = j.getAsJsonObject("stages");
            int[] sl = null;
            if (s.has("light")) {
                JsonArray a = s.getAsJsonArray("light");
                sl = new int[a.size()];
                for (int i = 0; i < sl.length; i++) sl[i] = a.get(i).getAsInt();
            }
            stages = new Stages(s.get("count").getAsInt(), s.get("chance").getAsFloat(), s.get("light_min").getAsInt(),
                    s.get("boost_chance").getAsFloat(), s.get("bonemeal").getAsFloat(), s.get("initial").getAsInt(), sl);
        } else {
            stages = null;
        }
        if (j.has("toggle")) {
            JsonObject t = j.getAsJsonObject("toggle");
            toggle = new Toggle(t.get("property").getAsString(), t.get("default").getAsBoolean(),
                    t.get("player").getAsBoolean(), str(t, "sound"));
        } else {
            toggle = null;
        }
        powered = j.has("powered") && j.get("powered").getAsBoolean();
        hearthLitItem = j.has("hearth") ? j.getAsJsonObject("hearth").get("lit_item").getAsString() : null;
        sounds = j.has("sounds") ? j.getAsJsonObject("sounds") : new JsonObject();
    }

    private static @Nullable String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    /** Boxes (cell-local px, facing north) to shapes for the 4 quadrants (clockwise turns). */
    private static VoxelShape[][] shapes(JsonArray perPart, int n, boolean fallbackFull) {
        VoxelShape[][] out = new VoxelShape[n][4];
        for (int p = 0; p < n; p++) {
            JsonArray boxes = perPart != null && p < perPart.size() ? perPart.get(p).getAsJsonArray() : new JsonArray();
            for (int q = 0; q < 4; q++) {
                VoxelShape s = Shapes.empty();
                for (JsonElement b : boxes) {
                    JsonArray a = b.getAsJsonArray();
                    double[] v = new double[6];
                    for (int i = 0; i < 6; i++) v[i] = Math.max(0, Math.min(16, a.get(i).getAsDouble()));
                    double[] r = rotate(v, q);
                    if (r[3] - r[0] < 0.01 || r[4] - r[1] < 0.01 || r[5] - r[2] < 0.01) continue;
                    s = Shapes.or(s, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
                }
                if (s.isEmpty() && fallbackFull) s = Block.box(2, 0, 2, 14, 8, 14);
                out[p][q] = s.optimize();
            }
        }
        return out;
    }

    /** Turns a cell-local box clockwise (seen from above) q quarter turns about the cell center. */
    static double[] rotate(double[] b, int q) {
        double x1 = b[0], y1 = b[1], z1 = b[2], x2 = b[3], y2 = b[4], z2 = b[5];
        for (int i = 0; i < (q & 3); i++) {
            double nx1 = 16 - z2, nx2 = 16 - z1, nz1 = x1, nz2 = x2;
            x1 = nx1;
            x2 = nx2;
            z1 = nz1;
            z2 = nz2;
        }
        return new double[]{x1, y1, z1, x2, y2, z2};
    }

    public boolean multiPart() {
        return parts.length > 1;
    }

    public boolean hasBlockEntity() {
        return (storage != null && storage.type().ownInventory()) || jukebox;
    }
}
