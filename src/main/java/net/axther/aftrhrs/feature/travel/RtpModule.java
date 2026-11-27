package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /rtp} (also /wild): a random safe spot in the world, between {@code min-radius} and {@code max-radius} around
 * the world's spawn (kept inside the world border). Chunks load without blocking the server, liquids and hazards are
 * avoided, chosen biomes are skipped, claims are never landed in, and the trip uses the normal teleport warm-up.
 */
public final class RtpModule implements FeatherModule, Travel.Named {
    private static final Set<Block> UNSAFE = Set.of(Blocks.LAVA, Blocks.WATER, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
            Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.POWDER_SNOW,
            Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POINTED_DRIPSTONE, Blocks.BEDROCK, Blocks.KELP,
            Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.BUBBLE_COLUMN);

    private final Feather plugin;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    /** When each player may search again: every search costs chunk loads, whether or not the trip happens. */
    private final Map<UUID, Long> nextSearch = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();

    /** Holds a searched column loaded: 10 s at most (a dropped task cleans itself up), never saved. */
    static TicketType RTP_TICKET;

    public RtpModule(Feather plugin) {
        this.plugin = plugin;
    }

    /** Registers the search ticket type; at mod init, before the registries freeze. */
    public static void initTicketType() {
        if (RTP_TICKET == null) {
            // no FLAG_CAN_EXPIRE_IF_UNLOADED: addTicketAndLoadWithRadius refuses such types
            RTP_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, AftrHrs.id("rtp_search"), new TicketType(200L, TicketType.FLAG_LOADING));
        }
    }

    @Override
    public String name() {
        return "Random teleport";
    }

    @Override
    public void enable() {
        Travel.bind(plugin, this, "rtp");
        plugin.hub().add("wild", Hub.Group.TRAVEL, Hub.ORDER_WILD, "hub-wild", "feather.rtp", p -> Travel.perform(p, "rtp"));
    }

    private Yaml worldSettings(ServerLevel w) {
        return plugin.getConfig().getConfigurationSection("rtp.worlds." + Worlds.name(w));
    }

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer target;
        boolean others = sender.hasPermission("feather.rtp.others");
        if (args.length > 0 && others) {
            target = plugin.findPlayer(sender, args[0]);
            if (target == null) {
                plugin.notFound(sender, args[0]);
                return true;
            }
        } else if (sender.player() != null) {
            target = sender.player();
        } else {
            text.send(sender, "staff.need-player");
            return true;
        }
        ServerLevel world = args.length > 1 && others ? Worlds.resolve(args[1]) : null;
        if (world == null) {
            world = target.level();
            String redirect = plugin.getConfig().getString("rtp.redirect." + Worlds.name(world), "");
            ServerLevel to = redirect.isEmpty() ? null : Worlds.resolve(redirect);
            if (to != null) world = to;
        }
        Yaml ws = worldSettings(world);
        if (ws == null || !ws.getBoolean("enabled", true)) {
            text.send(sender, "rtp.world-disabled", Text.unparsed("world", Worlds.name(world)));
            return true;
        }
        boolean self = sender.player() == target;
        if (self && !Perms.has(target, "feather.rtp.nocooldown")) {
            long cd = plugin.getConfig().getLong("rtp.cooldown-seconds", 600) * 1000L;
            Long last = lastUse.get(target.getUUID());
            long now = System.currentTimeMillis();
            // The full cooldown counts from the last arrival, the short one from the last search.
            long wait = Math.max(last == null ? 0 : cd - (now - last), nextSearch.getOrDefault(target.getUUID(), 0L) - now);
            if (wait > 0) {
                text.send(target, "rtp.cooldown", Text.unparsed("time", Placeholders.duration(wait + 999)));
                text.sound(target, "error");
                return true;
            }
            long retry = Math.max(0, plugin.getConfig().getLong("rtp.retry-seconds", 30)) * 1000L;
            if (retry > 0) nextSearch.put(target.getUUID(), now + retry);
        }
        if (!searching.add(target.getUUID())) {
            text.send(sender, "rtp.already-searching");
            return true;
        }
        text.send(target, "rtp.searching");
        ServerLevel w = world;
        UUID id = target.getUUID();
        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.max-attempts", 12));
        find(w, ws, attempts).whenComplete((loc, err) -> Tasks.sync(() -> {
            searching.remove(id);
            if (err != null) plugin.getLogger().warn("/rtp search failed", err);
            ServerPlayer t = plugin.player(id);
            if (t == null) return;
            if (loc == null) {
                text.send(t, "rtp.failed");
                text.sound(t, "error");
                return;
            }
            plugin.teleports().teleport(t, () -> loc, text.msg("rtp.destination",
                            Text.unparsed("x", loc.blockX()), Text.unparsed("z", loc.blockZ())),
                    () -> {
                        // The full cooldown starts on arrival; a search that led nowhere only costs retry-seconds.
                        if (self) lastUse.put(id, System.currentTimeMillis());
                        plugin.advance(plugin.player(id), "event", "rtp");
                    });
        }));
        return true;
    }

    /** Tries up to {@code attempts} random columns, one after another; chunks load off the server thread's back. */
    private CompletableFuture<Loc> find(ServerLevel w, Yaml ws, int attempts) {
        if (attempts <= 0) return CompletableFuture.completedFuture(null);
        int min = Math.max(0, ws.getInt("min-radius", 300));
        int max = Math.max(min + 16, ws.getInt("max-radius", 3000));
        BlockPos center = BlockPos.ZERO;
        if (ws.getBoolean("around-spawn", true)) {
            LevelData.RespawnData rd = w.getRespawnData();
            if (rd.dimension() == w.dimension() || w.dimension() == Level.OVERWORLD) center = rd.pos();
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble(Math.PI * 2);
        double dist = Math.sqrt(r.nextDouble((double) min * min, (double) max * max));
        int x = center.getX() + (int) (Math.cos(angle) * dist);
        int z = center.getZ() + (int) (Math.sin(angle) * dist);
        if (!w.getWorldBorder().isWithinBounds(x, z)) return find(w, ws, attempts - 1);
        // A loading ticket: the chunk loads or generates without blocking the server, then the column is checked on the
        // server thread (the future completes inside the chunk system, hence the Tasks.sync hop).
        ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
        ServerChunkCache cache = w.getChunkSource();
        CompletableFuture<Loc> out = new CompletableFuture<>();
        cache.addTicketAndLoadWithRadius(RTP_TICKET, cp, 0).whenComplete((res, err) -> Tasks.sync(() -> {
            Loc loc = null;
            try {
                if (err == null) loc = safeSpot(w, x, z, ws);
                out.complete(loc);
            } catch (RuntimeException e) {
                out.completeExceptionally(e);
            } finally {
                // a rejected column goes now; the chosen one keeps its ticket until it times out, through the warm-up
                if (loc == null) cache.removeTicketWithRadius(RTP_TICKET, cp, 0);
            }
        }));
        return out.thenCompose(loc -> loc != null ? CompletableFuture.completedFuture(loc) : find(w, ws, attempts - 1));
    }

    private static boolean passable(ServerLevel w, BlockPos pos) {
        return w.getBlockState(pos).getCollisionShape(w, pos).isEmpty();
    }

    /** Bukkit's {@code Material.isSolid()}: blocks that stop movement (the legacy "solid" flag). */
    @SuppressWarnings("deprecation")
    private static boolean solid(BlockState s) {
        return s.isSolid();
    }

    private static boolean liquid(BlockState s) {
        return !s.getFluidState().isEmpty();
    }

    private Loc safeSpot(ServerLevel w, int x, int z, Yaml ws) {
        if (w.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;   // not loaded after all: a failed try, never a stall
        String biome = w.getBiome(new BlockPos(x, 64, z)).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        for (String bad : ws.getStringList("avoid-biomes")) if (biome.contains(bad.toLowerCase(Locale.ROOT))) return null;
        BlockPos ground;
        if (w.dimension() == Level.NETHER) {
            ground = null;
            for (int y = Math.min(120, w.getMaxY() - 2); y > w.getMinY() + 5; y--) {
                BlockPos b = new BlockPos(x, y, z);
                if (solid(w.getBlockState(b)) && passable(w, b.above()) && passable(w, b.above(2))) {
                    ground = b;
                    break;
                }
            }
            if (ground == null) return null;
        } else {
            ground = new BlockPos(x, w.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        }
        if (ground.getY() <= w.getMinY() + 1) return null;
        BlockState g = w.getBlockState(ground);
        if (UNSAFE.contains(g.getBlock()) || liquid(g) || !solid(g)) return null;
        BlockPos feet = ground.above();
        BlockPos head = ground.above(2);
        BlockState fs = w.getBlockState(feet), hs = w.getBlockState(head);
        if (!passable(w, feet) || !passable(w, head) || liquid(fs) || liquid(hs) || UNSAFE.contains(fs.getBlock())) return null;
        Loc spot = new Loc(w, x + 0.5, ground.getY() + 1, z + 0.5, ThreadLocalRandom.current().nextFloat() * 360f, 0f);
        if (plugin.claimed(spot)) return null;   // never land in someone's claim
        return spot;
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        if (!sender.hasPermission("feather.rtp.others")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(plugin.completePlayers(sender, args[0]));
        if (args.length == 2) for (ServerLevel w : Feather.server().getAllLevels()) out.add(Worlds.name(w));
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
