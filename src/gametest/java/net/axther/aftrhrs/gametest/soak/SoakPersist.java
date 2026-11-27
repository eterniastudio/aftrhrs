package net.axther.aftrhrs.gametest.soak;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Persistence fixture and snapshots. {@code /soak fixture} builds a sky platform at y=200 over chunks -4..3 (force-loaded):
 * every furniture block in the content table on a grid (half the lit ones lit), filled containers, and per online bot
 * four tamed creatures (staying, named, bonded, a color variant), a claim, a home and a bank balance.
 * {@code /soak snap <name>} writes {@code soak/snap-<name>/}: data-file hashes (plus a copy), every Arcana entity's
 * saved NBT (volatile keys dropped), the platform's block states and block entities, and the bots' player files.
 * {@code mod/tools/soak_diff.py} compares two snapshots.
 */
final class SoakPersist {
    static final int Y = 200;
    static final int MIN = -64, MAX = 63;
    private static final Set<String> VOLATILE = Set.of("Motion", "FallDistance", "Air", "OnGround", "Fire", "HurtTime", "HurtByTimestamp",
            "DeathTime", "Brain", "fall_distance", "AbsorptionAmount", "PortalCooldown", "LastHurtByMob", "last_hurt_by_mob", "attributes",
            "Rotation", "active_effects", "InLove", "LoveCause", "Age", "ForcedAge", "NoGravity", "TicksFrozen", "HasVisualFire", "Invulnerable",
            "geckolib", "sleeping_pos", "ticks_since_last_hurt_by_mob");

    private SoakPersist() {
    }

    static void fixture(MinecraftServer s) {
        ServerLevel l = s.overworld();
        for (int cx = MIN >> 4; cx <= MAX >> 4; cx++) {
            for (int cz = MIN >> 4; cz <= MAX >> 4; cz++) l.setChunkForced(cx, cz, true);
        }
        for (int x = MIN; x <= MAX; x++) {
            for (int z = MIN; z <= MAX; z++) l.setBlock(new BlockPos(x, Y - 1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        Random r = new Random(42);
        int placed = 0, lit = 0, filled = 0;
        int gx = MIN + 2, gz = MIN + 2;
        for (var c : ArcanaContent.entries().values()) {
            if (c.block() == null || "alias".equals(c.kind()) || c.cropStage() != null) continue;
            BlockPos at = new BlockPos(gx, Y, gz);
            gx += 4;
            if (gx > MAX - 4) {
                gx = MIN + 2;
                gz += 4;
                if (gz > MAX - 24) break; // keep the last rows for the pets
            }
            if (!ArcanaContent.place(l, at, c.id(), Direction.Plane.HORIZONTAL.getRandomDirection(net.minecraft.util.RandomSource.create(r.nextLong())))) {
                continue;
            }
            placed++;
            BlockState st = l.getBlockState(at);
            if (st.hasProperty(FurnitureBlock.LIT) && r.nextBoolean()) {
                l.setBlock(at, st.setValue(FurnitureBlock.LIT, true), 3);
                lit++;
            }
            BlockEntity be = l.getBlockEntity(at);
            if (be instanceof Container ct) {
                fill(ct, r);
                be.setChanged();
                filled++;
            }
        }
        // plain chests and barrels as a control
        for (int i = 0; i < 6; i++) {
            BlockPos at = new BlockPos(MIN + 2 + i * 3, Y, MAX - 4);
            l.setBlockAndUpdate(at, (i % 2 == 0 ? Blocks.CHEST : Blocks.BARREL).defaultBlockState());
            if (l.getBlockEntity(at) instanceof Container ct) {
                fill(ct, r);
                filled++;
            }
        }
        SoakDriver.log("fixture: " + placed + " furniture blocks, " + lit + " lit, " + filled + " containers filled");
        // per bot: pets, claim, home, money
        List<EntityType<?>> tameable = new ArrayList<>();
        for (var e : ArcanaEntities.all().entrySet()) {
            Species sp = e.getValue();
            if (sp.kind.tameableBase() && !sp.untameable()) tameable.add(e.getKey());
        }
        int b = 0;
        for (SoakBot bot : SoakDriver.bots) {
            ServerPlayer p = bot.player(s);
            if (p == null) continue;
            int px = MIN + 6 + b * 14, pz = MAX - 14;
            p.teleportTo(l, px + 0.5, Y, pz + 0.5, Set.of(), 0f, 0f, true);
            for (int i = 0; i < 4; i++) {
                EntityType<?> t = tameable.get(r.nextInt(tameable.size()));
                Entity e = t.create(l, EntitySpawnReason.COMMAND);
                if (!(e instanceof ArcanaCreature c)) continue;
                c.snapTo(px + i * 2 + 0.5, Y, pz + 3.5, 0f, 0f);
                l.addFreshEntity(c);
                c.tamedBy(p);
                c.setStaying(true, p);
            }
            SoakDriver.command(s, "pets admin setbond " + bot.name + " " + (2 + b));
            for (var sp : List.of("ArcanaBlackCat", "ArcanaCorgi", "ArcanaBeagle", "ArcanaHusky")) {
                SoakDriver.command(s, "pets admin setvariant " + bot.name + " " + firstVariant(sp) + " shiny");
            }
            SoakDriver.command(s, "bank give " + bot.name + " " + (1000 + b * 111));
            SoakDriver.command(s, "bank admin give " + bot.name + " " + (1000 + b * 111));
            bot.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket("claim"));
            bot.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket("sethome fixture"));
            bot.api(SoakBot.message("pets_request"));
            b++;
        }
        SoakDriver.log("fixture: " + b + " bots set up; run '/soak names' next tick to rename their pets");
    }

    private static String firstVariant(String species) {
        return switch (species) {
            case "ArcanaBlackCat" -> "smoke";
            case "ArcanaCorgi" -> "sable";
            case "ArcanaBeagle" -> "lemon";
            default -> "agouti";
        };
    }

    /** Rename each bot's pets through the Companion (the real client path). */
    static void names(MinecraftServer s) {
        int n = 0;
        for (SoakBot bot : SoakDriver.bots) {
            var pets = bot.lastMessage.get("pets");
            if (pets == null || !pets.has("pets")) continue;
            int i = 0;
            for (var e : pets.getAsJsonArray("pets")) {
                var o = e.getAsJsonObject();
                if (!o.has("id")) continue;
                var m = SoakBot.message("pet_action");
                m.addProperty("pet", o.get("id").getAsString());
                m.addProperty("a", "rename");
                m.addProperty("name", bot.name + " Pet " + (i++) + (i == 2 ? " ✦ 名前" : ""));
                bot.api(m);
                n++;
            }
        }
        SoakDriver.log("names: " + n + " renames sent");
    }

    private static void fill(Container ct, Random r) {
        ItemStack[] pool = {new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.COD, 7),
                named(new ItemStack(Items.DIAMOND_SWORD)), ArcanaContent.stack("arcana_goob_coin", 12), new ItemStack(Items.SHULKER_BOX),
                new ItemStack(Items.WRITTEN_BOOK)};
        for (int i = 0; i < ct.getContainerSize(); i++) {
            if (r.nextInt(3) == 0) {
                ItemStack st = pool[r.nextInt(pool.length)].copy();
                if (!st.isEmpty()) ct.setItem(i, st);
            }
        }
    }

    private static ItemStack named(ItemStack s) {
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Soak Blade ✦"));
        var reg = SoakDriver.server().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        s.enchant(reg.getOrThrow(Enchantments.SHARPNESS), 3);
        return s;
    }

    /** Tames and summons one of every tameable species around the op bot (variety for the chaos run). */
    static void summonAll(MinecraftServer s, int each) {
        SoakBot bot = SoakDriver.bots.isEmpty() ? null : SoakDriver.bots.getFirst();
        ServerPlayer p = bot == null ? null : bot.player(s);
        ServerLevel l = p != null ? p.level() : s.overworld();
        double x = p != null ? p.getX() : 0, z = p != null ? p.getZ() : 0;
        int n = 0;
        for (var t : ArcanaEntities.all().keySet()) {
            for (int i = 0; i < each; i++) {
                Entity e = t.create(l, EntitySpawnReason.COMMAND);
                if (e == null) continue;
                int y = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x + n % 20 - 10, (int) z);
                e.snapTo(x + n % 20 - 10, y, z + (n / 20) - 3, 0f, 0f);
                l.addFreshEntity(e);
                n++;
            }
        }
        SoakDriver.log("summonall: " + n + " creatures");
    }

    // ------------------------------------------------------------------ snapshots

    static void snapshot(MinecraftServer s, String name) throws IOException {
        Path dir = SoakDriver.out.resolve("snap-" + name);
        if (Files.exists(dir)) {
            try (Stream<Path> w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(dir);
        Path data = net.axther.aftrhrs.core.Feather.get().getDataFolder();
        StringBuilder files = new StringBuilder();
        if (Files.isDirectory(data)) {
            try (Stream<Path> w = Files.walk(data)) {
                for (Path f : (Iterable<Path>) w::iterator) {
                    if (!Files.isRegularFile(f)) continue;
                    String rel = data.relativize(f).toString().replace('\\', '/');
                    if (rel.startsWith("logs/") || rel.endsWith(".log")) continue;
                    files.append(rel).append(' ').append(Files.size(f)).append(' ').append(sha(f)).append('\n');
                    Path copy = dir.resolve("data").resolve(rel);
                    Files.createDirectories(copy.getParent());
                    Files.copy(f, copy);
                }
            }
        }
        Files.writeString(dir.resolve("files.txt"), files);
        // entities
        TreeMap<String, String> ents = new TreeMap<>();
        for (ServerLevel l : s.getAllLevels()) {
            for (Entity e : l.getAllEntities()) {
                var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
                if (!key.getNamespace().equals(AftrHrs.ID)) continue;
                TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, s.registryAccess());
                e.saveWithoutId(out);
                CompoundTag tag = out.buildResult();
                StringBuilder sb = new StringBuilder();
                sb.append(key).append(' ').append(l.dimension().identifier()).append(' ');
                if (e instanceof ArcanaCreature c) {
                    sb.append("tame=").append(c.isTame()).append(" owner=").append(c.getOwnerReference() == null ? "-" : c.getOwnerReference().getUUID())
                            .append(" bond=").append(c.bond()).append(" name=").append(c.getCustomName() == null ? "-" : c.getCustomName().getString())
                            .append(" variant=").append(c.variant()).append(" shiny=").append(c.shiny()).append(" stay=").append(c.staying()).append(' ');
                }
                sb.append("pos=").append(e.blockPosition().toShortString()).append(" nbt=").append(canon(tag, true));
                ents.put(e.getUUID().toString(), sb.toString());
            }
        }
        StringBuilder es = new StringBuilder();
        ents.forEach((k, v) -> es.append(k).append(' ').append(v).append('\n'));
        Files.writeString(dir.resolve("entities.txt"), es);
        // fixture platform
        ServerLevel l = s.overworld();
        StringBuilder bs = new StringBuilder();
        if (l.hasChunk(0, 0)) {
            for (int x = MIN; x <= MAX; x++) {
                for (int z = MIN; z <= MAX; z++) {
                    for (int y = Y; y < Y + 4; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!l.hasChunk(x >> 4, z >> 4)) continue;
                        BlockState st = l.getBlockState(pos);
                        if (st.isAir()) continue;
                        bs.append(pos.toShortString()).append(' ').append(st);
                        BlockEntity be = l.getBlockEntity(pos);
                        if (be != null) bs.append(" be=").append(canon(be.saveWithoutMetadata(s.registryAccess()), false));
                        bs.append('\n');
                    }
                }
            }
        }
        Files.writeString(dir.resolve("blocks.txt"), bs);
        // player files
        Path pd = s.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        StringBuilder ps = new StringBuilder();
        if (Files.isDirectory(pd)) {
            try (Stream<Path> w = Files.list(pd)) {
                for (Path f : (Iterable<Path>) w.sorted()::iterator) {
                    if (!f.toString().endsWith(".dat")) continue;
                    try {
                        CompoundTag t = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
                        ps.append(f.getFileName()).append(' ').append(canon(t, true)).append('\n');
                    } catch (IOException e) {
                        ps.append(f.getFileName()).append(" UNREADABLE ").append(e).append('\n');
                        SoakDriver.problem("player file " + f + " unreadable", e);
                    }
                }
            }
        }
        Files.writeString(dir.resolve("players.txt"), ps);
        SoakDriver.log("snapshot " + name + ": " + ents.size() + " entities, " + bs.length() / 1024 + " KiB blocks");
    }

    private static String sha(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(f))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** NBT with sorted keys (and, for entities, the per-tick keys dropped) so two saves compare as text. */
    static String canon(Tag t, boolean dropVolatile) {
        if (t instanceof CompoundTag c) {
            StringBuilder sb = new StringBuilder("{");
            List<String> keys = new ArrayList<>(c.keySet());
            java.util.Collections.sort(keys);
            boolean first = true;
            for (String k : keys) {
                if (dropVolatile && VOLATILE.contains(k)) continue;
                if (!first) sb.append(',');
                first = false;
                Tag v = c.get(k);
                if (dropVolatile && k.equals("Pos") && v instanceof ListTag lt) {
                    sb.append(k).append(":[");
                    for (int i = 0; i < lt.size(); i++) sb.append(i > 0 ? "," : "").append(Math.round(lt.getDoubleOr(i, 0) * 10) / 10.0);
                    sb.append(']');
                    continue;
                }
                if (dropVolatile && k.equals("Health")) {
                    sb.append(k).append(':').append(Math.round(c.getFloatOr(k, 0f)));
                    continue;
                }
                sb.append(k).append(':').append(canon(v, dropVolatile));
            }
            return sb.append('}').toString();
        }
        if (t instanceof ListTag l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) sb.append(i > 0 ? "," : "").append(canon(l.get(i), dropVolatile));
            return sb.append(']').toString();
        }
        return String.valueOf(t);
    }
}
