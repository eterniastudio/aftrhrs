package net.axther.aftrhrs.feature.pets;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Small Bukkit-shaped helpers for the pets port (materials, particles, numeric permissions, safe spots). */
final class PetUtil {
    private PetUtil() {
    }

    /** A Bukkit material name ("COOKED_COD", "minecraft:cooked_cod") as an item, or null. */
    static Item item(String name) {
        if (name == null || name.isBlank()) return null;
        // "Cooked Beef" (guide.yml) / "COOKED_BEEF" (pets.yml) / "minecraft:cooked_beef", like Bukkit's matchMaterial
        String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        Identifier id = Identifier.tryParse(n.contains(":") ? n : "minecraft:" + n);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == null || item == Items.AIR ? null : item;
    }

    /** "Cooked Cod" for minecraft:cooked_cod. */
    static String pretty(Item item) {
        if (item == null) return "?";
        String n = BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** A simple (no extra data) particle by Bukkit name ("END_ROD", "FIREWORK"), or null. */
    static SimpleParticleType particle(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) return null;
        Identifier id = Identifier.tryParse("minecraft:" + name.trim().toLowerCase(Locale.ROOT));
        if (id == null) return null;
        ParticleType<?> t = BuiltInRegistries.PARTICLE_TYPE.getValue(id);
        return t instanceof SimpleParticleType s ? s : null;
    }

    static void particles(Entity e, ParticleOptions p, double dy, int count, double spread, double vSpread, double speed) {
        if (e.level() instanceof ServerLevel level) level.sendParticles(p, e.getX(), e.getY() + dy, e.getZ(), count, spread, vSpread, spread, speed);
    }

    // ------------------------------------------------------------------ numeric permissions

    private record Cached(int value, long at) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * The highest {@code <prefix><n>} the player has (LuckPerms: any n; otherwise 0..256 asked one by one), or
     * {@code def}; {@code <prefix>unlimited} gives {@code Integer.MAX_VALUE}. Cached for 5 seconds.
     */
    static int numeric(ServerPlayer p, String prefix, int def) {
        if (Perms.has(p, prefix + "unlimited")) return Integer.MAX_VALUE;
        String key = p.getUUID() + "|" + prefix;
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(key);
        if (c != null && now - c.at() < 5000) return c.value() < 0 ? def : c.value();
        int best = luckPerms(p, prefix);
        if (best == Integer.MIN_VALUE) {
            best = -1;
            for (int n = 256; n >= 0; n--) {
                if (Permissions.getPermissionValue(p, prefix + n) == TriState.TRUE) {
                    best = n;
                    break;
                }
            }
        }
        if (CACHE.size() > 4096) CACHE.clear();
        CACHE.put(key, new Cached(best, now));
        return best < 0 ? def : best;
    }

    /** MIN_VALUE when LuckPerms isn't there; else the best number (-1 for none). */
    private static int luckPerms(ServerPlayer p, String prefix) {
        try {
            Map<String, Boolean> map = net.luckperms.api.LuckPermsProvider.get().getPlayerAdapter(ServerPlayer.class)
                    .getPermissionData(p).getPermissionMap();
            int best = -1;
            for (Map.Entry<String, Boolean> e : map.entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) continue;
                String perm = e.getKey().toLowerCase(Locale.ROOT);
                if (!perm.startsWith(prefix)) continue;
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                }
            }
            return best;
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    static void forget(UUID player) {
        CACHE.keySet().removeIf(k -> k.startsWith(player.toString()));
    }

    // ------------------------------------------------------------------ where a pet arrives

    private static final Set<Block> HARMFUL = Set.of(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.LAVA, Blocks.POWDER_SNOW,
            Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE,
            Blocks.CACTUS, Blocks.POINTED_DRIPSTONE, Blocks.COBWEB);

    /**
     * Where a pet arrives next to the player: behind them on safe ground (a short step down is fine), else where they
     * stand, else (in the air, in liquid) the safe ground under them, else their own spot.
     */
    static Loc near(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        Loc l = Loc.of(player);
        Vec3 look = player.getLookAngle();
        Vec3 back = new Vec3(look.x, 0, look.z);
        if (back.lengthSqr() > 1.0E-4) {
            back = back.normalize().scale(1.5);
            Loc behind = ground(level, l.x() - back.x, l.y(), l.z() - back.z, 3);
            if (behind != null) return behind.withRotation(l.yaw(), 0);
        }
        if (player.onGround() && level.getFluidState(player.blockPosition()).isEmpty()) return l;
        Loc under = ground(level, l.x(), l.y(), l.z(), 8);
        return under != null ? under : l;
    }

    /** The spot at (x,y,z) or up to {@code drop} blocks below with room for a pet on solid, harmless ground, or null. */
    private static Loc ground(ServerLevel level, double x, double y, double z, int drop) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        BlockPos feet = BlockPos.containing(x, y, z);
        if (!level.isLoaded(feet) || !open(level, feet.above())) return null;
        for (int i = 0; i <= drop; i++) {
            BlockPos f = feet.below(i);
            if (!open(level, f)) return null;
            BlockPos under = f.below();
            BlockState u = level.getBlockState(under);
            if (!u.getFluidState().isEmpty() || HARMFUL.contains(u.getBlock())) return null;
            if (u.isFaceSturdy(level, under, Direction.UP) || u.isSolid()) return new Loc(level, x, f.getY(), z);
        }
        return null;
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty() && !HARMFUL.contains(s.getBlock());
    }

    // ------------------------------------------------------------------ containers

    /** Puts the stack into the container (merging); returns what didn't fit (empty when all went in). */
    static ItemStack add(Container container, ItemStack stack) {
        ItemStack left = HopperBlockEntity.addItem(null, container, stack.copy(), null);
        container.setChanged();
        return left;
    }

    /** Gives an item to a player, dropping what doesn't fit at their feet. */
    static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }
}
