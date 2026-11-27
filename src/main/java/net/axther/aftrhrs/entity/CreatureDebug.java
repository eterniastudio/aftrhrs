package net.axther.aftrhrs.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Operator tools for the creature systems (permission level 2, also from the console):
 * <ul>
 *   <li>{@code /aftrhrs debug sfx <species|all> <event|all|check>}: plays a profile event at the source (all = every event,
 *       one a second; check = verifies every profile event is a registered sound event).</li>
 *   <li>{@code /aftrhrs debug spawns [attempts]}: proves the natural spawn rules. For every species with a natural spawn
 *       (or an undead share of vanilla spawns) it paints one of its biomes far from spawn, then runs the real spawn
 *       predicate ({@link SpawnPlacements#checkSpawnRules}) {@code attempts} times at noon and at midnight and reports
 *       the hits. Aggressive species must get zero by day.</li>
 * </ul>
 */
final class CreatureDebug {
    private CreatureDebug() {
    }

    static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("aftrhrs")
                .then(Commands.literal("debug").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("sfx")
                                .then(Commands.argument("species", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(ids(), b))
                                        .then(Commands.argument("event", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(eventsOf(StringArgumentType.getString(c, "species")), b))
                                                .executes(CreatureDebug::sfx))))
                        .then(Commands.literal("spawns")
                                .executes(c -> spawns(c, 200))
                                .then(Commands.argument("attempts", IntegerArgumentType.integer(1, 5000))
                                        .executes(c -> spawns(c, IntegerArgumentType.getInteger(c, "attempts"))))))));
    }

    private static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Species s : ArcanaEntities.all().values()) out.add(s.id);
        return out;
    }

    private static List<String> eventsOf(String id) {
        List<String> out = new ArrayList<>(List.of("all", "check"));
        EntityType<?> t = ArcanaEntities.type(id);
        JsonObject sfx = t == null ? null : ArcanaEntities.species(t).section("sfx");
        if (sfx != null) out.addAll(sfx.getAsJsonObject("events").keySet());
        return out;
    }

    private static void reply(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    // ------------------------------------------------------------------ sfx

    private static int sfx(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "species").toLowerCase(Locale.ROOT);
        String ev = StringArgumentType.getString(ctx, "event").toLowerCase(Locale.ROOT);
        boolean everyone = id.equals("all");
        List<Species> targets = new ArrayList<>();
        for (Species s : ArcanaEntities.all().values()) if (everyone || s.id.equals(id)) targets.add(s);
        if (targets.isEmpty()) {
            src.sendFailure(Component.literal("Unknown species " + id));
            return 0;
        }
        if (ev.equals("check")) {
            int ok = 0, bad = 0;
            for (Species s : targets) {
                JsonObject sfx = s.section("sfx");
                if (sfx == null) {
                    reply(src, s.id + ": NO PROFILE");
                    bad++;
                    continue;
                }
                for (var e : sfx.getAsJsonObject("events").entrySet()) {
                    String key = s.id + "." + e.getKey();
                    boolean main = CreatureSfx.events().containsKey(key);
                    boolean layer = !e.getValue().getAsJsonObject().has("layer") || CreatureSfx.events().containsKey(key + ".layer");
                    if (main && layer) ok++;
                    else {
                        bad++;
                        reply(src, "MISSING aftrhrs:" + key + (main ? ".layer" : ""));
                    }
                }
            }
            String line = "sfx check: " + ok + " events ok, " + bad + " missing, " + CreatureSfx.events().size() + " registered";
            AftrHrs.LOG.info(line);
            reply(src, line);
            return bad == 0 ? 1 : 0;
        }
        Species s = targets.get(0);
        JsonObject sfx = s.section("sfx");
        if (sfx == null) {
            src.sendFailure(Component.literal(s.id + " has no sound profile"));
            return 0;
        }
        List<String> evs = new ArrayList<>();
        if (ev.equals("all")) {
            for (var e : sfx.getAsJsonObject("events").entrySet()) evs.add(e.getKey());
        } else if (sfx.getAsJsonObject("events").has(ev)) {
            evs.add(ev);
        } else {
            src.sendFailure(Component.literal(s.id + " has no '" + ev + "' sound. Events: " + sfx.getAsJsonObject("events").keySet()));
            return 0;
        }
        ServerLevel level = src.getLevel();
        var at = src.getPosition();
        MinecraftServer server = src.getServer();
        for (int i = 0; i < evs.size(); i++) {
            String e = evs.get(i);
            JsonObject info = sfx.getAsJsonObject("events").getAsJsonObject(e);
            Runnable play = () -> {
                SoundEvent main = CreatureSfx.events().get(s.id + "." + e);
                if (main != null) level.playSound(null, at.x, at.y, at.z, main, SoundSource.NEUTRAL, 1f, 1f);
                reply(src, "♪ aftrhrs:" + s.id + "." + e + (info.has("layer") ? " (+ layer after " + info.get("layer").getAsInt() + " t)" : ""));
            };
            int delay = i * 25;
            later(server, delay, play);
            if (info.has("layer")) {
                SoundEvent layer = CreatureSfx.events().get(s.id + "." + e + ".layer");
                if (layer != null) later(server, delay + info.get("layer").getAsInt(),
                        () -> level.playSound(null, at.x, at.y, at.z, layer, SoundSource.NEUTRAL, 1f, 1f));
            }
        }
        return evs.size();
    }

    private static void later(MinecraftServer server, int ticks, Runnable r) {
        if (ticks <= 0) {
            r.run();
            return;
        }
        net.axther.aftrhrs.core.Tasks.later(ticks, r);
    }

    // ------------------------------------------------------------------ spawns

    private record Probe(Species s, String biome, String dimension) {
    }

    private static int spawns(CommandContext<CommandSourceStack> ctx, int attempts) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        CommandSourceStack quiet = server.createCommandSourceStack().withSuppressedOutput();
        var difficulty = server.getWorldData().getDifficulty();
        if (difficulty == net.minecraft.world.Difficulty.PEACEFUL) server.getCommands().performPrefixedCommand(quiet, "difficulty normal");
        ServerLevel overworld = server.overworld();
        long clock = overworld.getDefaultClockTime();
        List<Probe> probes = new ArrayList<>();
        java.util.Set<String> nether = java.util.Set.of("minecraft:soul_sand_valley", "minecraft:nether_wastes", "minecraft:basalt_deltas",
                "minecraft:crimson_forest", "minecraft:warped_forest");
        for (Species s : ArcanaEntities.all().values()) {
            // one probe per dimension the species spawns in (its first known biome there)
            java.util.Map<String, String> byDim = new java.util.LinkedHashMap<>();
            JsonObject replace = s.section("replace");
            if (replace != null) {
                for (JsonElement b : replace.getAsJsonArray("biomes")) {
                    String dim = nether.contains(b.getAsString()) ? "minecraft:the_nether" : "minecraft:overworld";
                    if (known(server, b.getAsString())) byDim.putIfAbsent(dim, b.getAsString());
                }
            }
            for (Species.Spawn rule : s.spawns) {
                for (String b : rule.biomes()) if (known(server, b)) byDim.putIfAbsent(rule.dimension(), b);
            }
            // bosses / police have no rule: probe them in plains to show the predicate refuses them
            if (byDim.isEmpty() && (s.kind == Species.Kind.BOSS || s.kind == Species.Kind.POLICE)) byDim.put("minecraft:overworld", "minecraft:plains");
            byDim.forEach((dim, biome) -> probes.add(new Probe(s, biome, dim)));
        }
        int[] dayAggro = {0}, nightAggro = {0}, dayCalm = {0}, nightCalm = {0}, fixedHits = {0};
        List<String> lines = new ArrayList<>();
        int i = 0;
        for (Probe p : probes) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(p.dimension())));
            if (level == null) continue;
            int x = 20008 + (i % 12) * 64, z = 20008 + (i / 12) * 64;   // chunk centers: the painted box stays in one loaded chunk
            i++;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (level.dimensionType().hasCeiling()) {
                // under the Nether roof: the first open floor going down
                y = 64;
                for (int yy = 100; yy > 32; yy--) {
                    if (level.getBlockState(new BlockPos(x, yy, z)).isAir() && level.getBlockState(new BlockPos(x, yy - 1, z)).isSolid()) {
                        y = yy;
                        break;
                    }
                }
            }
            paint(server, level, x, y, z, p.biome());
            BlockPos pos = new BlockPos(x, y, z);
            String painted = level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
            if (!painted.equals(p.biome())) {
                lines.add(p.s().id + ": could not paint " + p.biome() + " (biome is " + painted + "), skipped");
                continue;
            }
            EntityType<?> type = ArcanaEntities.type(p.s().id);
            int day = count(server, level, type, pos, attempts, 6000);
            int night = count(server, level, type, pos, attempts, 18000);
            boolean aggro = ArcanaEntities.aggressive(p.s());
            boolean fixed = level.dimensionType().hasFixedTime();
            if (fixed) {
                fixedHits[0] += day + night;
            } else if (aggro) {
                dayAggro[0] += day;
                nightAggro[0] += night;
            } else {
                dayCalm[0] += day;
                nightCalm[0] += night;
            }
            String line = String.format(Locale.ROOT, "%-24s %-9s %s  day %4d/%d  night %4d/%d  (%s%s%s)", p.s().id, p.s().kind.name().toLowerCase(Locale.ROOT),
                    aggro ? "AGGRESSIVE" : "calm      ", day, attempts, night, attempts, p.biome(), p.s().section("replace") != null ? ", vanilla share" : "", fixed ? ", no day cycle" : "");
            lines.add(line);
        }
        setClock(server, clock);
        overworld.updateSkyBrightness();
        if (difficulty == net.minecraft.world.Difficulty.PEACEFUL) server.getCommands().performPrefixedCommand(quiet, "difficulty peaceful");
        for (String l : lines) {
            AftrHrs.LOG.info("spawn test: {}", l);
            reply(src, l);
        }
        String sum = String.format(Locale.ROOT, "spawn test: aggressive species %d day hits / %d night hits; calm species %d day / %d night; %d hits in dimensions without a day cycle (%d attempts each)",
                dayAggro[0], nightAggro[0], dayCalm[0], nightCalm[0], fixedHits[0], attempts);
        AftrHrs.LOG.info(sum);
        reply(src, sum);
        return dayAggro[0] == 0 ? 1 : 0;
    }

    private static boolean known(MinecraftServer server, String biome) {
        Identifier id = Identifier.tryParse(biome);
        return id != null && server.registryAccess().lookupOrThrow(Registries.BIOME).containsKey(id);
    }

    /** Paints the chunk column around (x, z) with the biome (what /fillbiome does, without its loaded-chunk check). */
    private static void paint(MinecraftServer server, ServerLevel level, int x, int y, int z, String biome) {
        var holder = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome)));
        var chunk = level.getChunk(x >> 4, z >> 4);
        chunk.fillBiomesFromNoise((qx, qy, qz) -> holder);
        chunk.markUnsaved();
    }

    /** Sets the overworld's day clock (what /time set does). */
    private static void setClock(MinecraftServer server, long ticks) {
        server.overworld().dimensionType().defaultClock().ifPresent(c -> server.clockManager().setTotalTicks(c, ticks));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int count(MinecraftServer server, Level level, EntityType type, BlockPos pos, int attempts, int time) {
        setClock(server, time);
        level.updateSkyBrightness();   // the sky darkens with the clock (vanilla does this every tick)
        if (level.dimensionType().hasFixedTime() == false && Conditions.time(level) != time % 24000) {
            AftrHrs.LOG.warn("spawn test: clock is {} after setting {}", Conditions.time(level), time);
        }
        RandomSource random = RandomSource.create(pos.asLong() ^ time);
        int hits = 0;
        for (int n = 0; n < attempts; n++) {
            if (SpawnPlacements.checkSpawnRules(type, (ServerLevel) level, EntitySpawnReason.NATURAL, pos, random)) hits++;
        }
        return hits;
    }
}
