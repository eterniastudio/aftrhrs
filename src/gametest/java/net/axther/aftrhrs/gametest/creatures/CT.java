package net.axther.aftrhrs.gametest.creatures;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Shared helpers for the creature / pets game tests: spawning like a spawn egg, online mock players, reflection, NBT round trips. */
public final class CT {
    private static int players;

    private CT() {
    }

    /** Every Arcana species with its entity type, in registration order. */
    public static List<Species> species(Predicate<Species> filter) {
        List<Species> out = new ArrayList<>();
        for (Species s : ArcanaEntities.all().values()) if (filter.test(s)) out.add(s);
        return out;
    }

    public static EntityType<?> type(Species s) {
        return ArcanaEntities.type(s.id);
    }

    /** Spawns a creature at a test-relative position the way a spawn egg does (finalizeSpawn SPAWN_EGG, not persistent). */
    @SuppressWarnings("unchecked")
    public static <T extends Entity> T spawn(GameTestHelper h, Species s, Vec3 rel) {
        return (T) spawnAbs(h.getLevel(), s, h.absoluteVec(rel), EntitySpawnReason.SPAWN_ITEM_USE);
    }

    public static Entity spawnAbs(ServerLevel level, Species s, Vec3 at, EntitySpawnReason reason) {
        Entity e = type(s).create(level, reason);
        if (e == null) throw new IllegalStateException("create returned null for " + s.id);
        e.snapTo(at.x, at.y, at.z, 0, 0);
        if (e instanceof Mob m) m.finalizeSpawn(level, level.getCurrentDifficultyAt(m.blockPosition()), reason, null);
        if (!level.addFreshEntity(e)) throw new IllegalStateException("addFreshEntity refused " + s.id);
        return e;
    }

    /** A player that is really online (in the player list, Feather sees it), in survival or creative. Not ticked. */
    public static ServerPlayer player(GameTestHelper h, GameType mode, Vec3 rel) {
        ServerLevel level = h.getLevel();
        String name = "bt" + (++players) + "_" + Integer.toHexString(h.getLevel().getRandom().nextInt(0xffff));
        GameProfile profile = new GameProfile(UUID.randomUUID(), name.length() > 16 ? name.substring(0, 16) : name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        // not client-authoritative: the server simulates what it rides (as GameTestHelper's mock players)
        ServerPlayer p = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation()) {
            @Override
            public boolean isClientAuthoritative() {
                return false;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.setGameMode(mode);
        Vec3 at = h.absoluteVec(rel);
        p.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), 0, 0, false);
        return p;
    }

    /** Disconnects an online mock player (Fabric DISCONNECT fires, the player leaves the list). */
    public static void disconnect(ServerPlayer p) {
        if (p.connection != null && !p.hasDisconnected()) p.connection.onDisconnect(new DisconnectionDetails(Component.literal("test over")));
    }

    /** Saves an entity to NBT (as its chunk would). */
    public static CompoundTag save(Entity e) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
        e.save(out);
        return out.buildResult();
    }

    /** Loads a saved entity back (as a chunk load would) without adding it to the level. */
    public static Entity load(ServerLevel level, CompoundTag tag) {
        return EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.LOAD, false)).orElse(null);
    }

    /**
     * A test area bigger than the 8x8x8 structure: its chunks are force-loaded (entities tick; the runner un-forces them
     * after the batch) and it gets a stone floor at relative y 0 (the structure floats over a void-like flat world).
     */
    public static void arena(GameTestHelper h, int sizeX, int sizeZ) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(-2, 0, -2)), b = h.absolutePos(new BlockPos(sizeX + 2, 0, sizeZ + 2));
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX()), z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) level.setChunkForced(cx, cz, true);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) level.setBlock(new BlockPos(x, a.getY(), z), stone, 2);
    }

    public static BlockPos grid(int i, int perRow, int spacing) {
        return new BlockPos(1 + (i % perRow) * spacing, 1, 1 + (i / perRow) * spacing);
    }

    public static ArcanaMob mob(Entity e) {
        return (ArcanaMob) e;
    }

    // ------------------------------------------------------------------ reflection (package-private module APIs)

    public static Object call(Object target, String name, Object... args) {
        Class<?> c = target instanceof Class<?> k ? k : target.getClass();
        Object on = target instanceof Class<?> ? null : target;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                if (!fits(m.getParameterTypes(), args)) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(on, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable t = e.getCause();
                    if (t instanceof RuntimeException r) throw r;
                    if (t instanceof Error err) throw err;
                    throw new RuntimeException(t);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new IllegalArgumentException("no method " + name + "/" + args.length + " on " + c.getName());
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            Class<?> t = box(types[i]);
            if (!t.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        return t;
    }

    public static Object get(Object target, String field) {
        Class<?> c = target instanceof Class<?> k ? k : target.getClass();
        Object on = target instanceof Class<?> ? null : target;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(on);
            } catch (NoSuchFieldException ignored) {
                // superclass
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("no field " + field + " on " + c.getName());
    }

    public static void set(Object target, String field, Object value) {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // superclass
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("no field " + field);
    }

    public static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /** Fails the test with every collected problem (one per line), or does nothing when there are none. */
    public static void report(GameTestHelper h, String what, List<String> problems) {
        if (problems.isEmpty()) return;
        StringBuilder b = new StringBuilder(what).append(": ").append(problems.size()).append(" problem(s)");
        for (String p : problems) b.append("\n - ").append(p);
        net.axther.aftrhrs.AftrHrs.LOG.error("[bt-creatures] {}", b);
        throw h.assertionException(Component.literal(b.length() > 3000 ? b.substring(0, 3000) + " ..." : b.toString()));
    }

    public static String err(Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        String where = "";
        for (StackTraceElement e : st) {
            if (e.getClassName().startsWith("net.axther.aftrhrs") && !e.getClassName().contains("gametest")) {
                where = " at " + e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber();
                break;
            }
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage() + where;
    }
}
