package net.axther.aftrhrs.gametest.soak;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** What one soak bot does each tick while the chaos loop runs: wander, explore, act, die, travel, quit and rejoin. */
final class SoakBrain {
    private static final String[] PLAYER_CMDS = {
            "home", "homes", "sethome soak", "sethome soak2", "delhome soak2", "spawn", "back", "afk", "afk", "trail", "sit", "lay", "swim",
            "pets", "pets summon", "pets stable", "pets codex", "bestiary", "wildlife", "balance", "bank", "baltop", "quests", "goal",
            "calendar", "mail", "chatgames", "claim", "claimblocks", "storage", "shop", "fishing", "farming", "deathpoint", "grave",
            "playtime", "near", "list", "ping", "stats", "leaderboard", "timezone", "tamingguide", "feather", "hat", "recipes", "bounty",
            "bossboard", "altars", "civic", "gadgets", "power", "warp", "seen Soak1", "whois Soak1", "trash", "rtp", "tptoggle", "msgtoggle",
            "msgtoggle", "tptoggle", "unclaim", "quickcraft", "stall", "lock", "unlock", "inspect", "ignore Soak3", "unignore Soak3"};
    private static final String[] PAIR_CMDS = {"msg %s hello from the soak", "r thanks", "tpa %s", "tpahere %s", "tpaccept", "tpdeny",
            "tpacancel", "pay %s 1", "trust %s", "untrust %s", "trade %s", "mail %s", "bounty %s"};
    private static final String[] OP_CMDS = {"day", "night", "sun", "storm", "heal", "feed", "god", "god", "fly", "fly", "near 200",
            "feather reload", "pets admin list Soak2", "invsee Soak2", "vanish", "vanish", "spawnmob zombie 2", "broadcast soak broadcast",
            "tppos 0 120 0", "smite Soak2", "playtime Soak3", "gmc", "gms"};
    private static final String[] CHAT = {"hi", "anyone selling fish?", "lol", "<red>red</red> test", "ünïcödé ✦ 名前 🙂", "@Soak1 ping",
            "a".repeat(250), "gg"};

    private final SoakBot bot;
    private final int index;
    private final Random r;
    private int cooldown;
    private int exploreIn;
    private int quitIn;
    private int offlineFor;
    private int dieIn;
    private int tripIn;
    private int tripBack;
    private ResourceKey<Level> home = Level.OVERWORLD;
    private float yaw;
    private int seq;
    /** Blocks this bot placed, with their dimension (a bot travels; never break or click a block in another world). */
    private final Deque<GlobalPos> placed = new ArrayDeque<>();
    /** A trip waiting for its destination chunk to load (an async ticket, never a server-thread getChunk). */
    private ServerLevel goLevel;
    private int goX, goZ, goWait;
    private CompletableFuture<?> goLoad;
    private int menusClicked;
    private static List<Item> furniture;

    SoakBrain(SoakBot bot, int index, long seed) {
        this.bot = bot;
        this.index = index;
        this.r = new Random(seed);
        exploreIn = 200 + r.nextInt(1200);
        quitIn = 2400 + r.nextInt(6000);
        dieIn = 3000 + r.nextInt(6000);
        tripIn = 1800 + r.nextInt(4000);
    }

    boolean op() {
        return index == 0;
    }

    boolean builder() {
        return index % 2 == 1;
    }

    void tick(MinecraftServer s, boolean chaos) {
        if (!chaos) return;
        if (!bot.connected()) {
            if (bot.connecting()) return;
            if (--offlineFor <= 0) {
                SoakDriver.log("bot " + bot.name + " rejoins");
                bot.login(s);
                offlineFor = 0;
            }
            return;
        }
        ServerPlayer p = bot.player(s);
        if (p == null) return;
        if (p != lastPlayer) {
            lastPlayer = p;
            setup(s, p);
        }
        if (p.isDeadOrDying()) {
            if (p.deathTime > 20) bot.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            return;
        }
        if (goLoad != null) {
            // waiting for a trip's chunk: land once it is loaded, give up after 20 s
            if (goLoad.isDone() && goLevel.hasChunk(goX >> 4, goZ >> 4)) {
                goLoad = null;
                land(p);
            } else if (++goWait > 400) {
                goLoad = null;
                SoakDriver.problem("bot " + bot.name + ": chunk for trip never loaded");
            }
            return;
        }
        walk(p);
        if (--quitIn <= 0) {
            quitIn = 3000 + r.nextInt(9000);
            offlineFor = 40 + r.nextInt(600);
            SoakDriver.log("bot " + bot.name + " quits for " + offlineFor + " ticks");
            bot.quit();
            return;
        }
        if (--exploreIn <= 0) {
            exploreIn = 600 + r.nextInt(1800);
            explore(s, p);
            return;
        }
        if (--tripIn <= 0) {
            tripIn = 4000 + r.nextInt(8000);
            trip(s, p);
            return;
        }
        if (tripBack > 0 && --tripBack == 0) {
            goTo(p, s.getLevel(home), r.nextInt(400) - 200, r.nextInt(400) - 200);
            return;
        }
        if (--dieIn <= 0) {
            dieIn = 4000 + r.nextInt(8000);
            if (!p.isCreative()) p.hurtServer(p.level(), p.damageSources().genericKill(), Float.MAX_VALUE);
            return;
        }
        JsonObject menu = bot.lastMessage.remove("menu");
        if (menu != null && r.nextInt(3) > 0) clickMenu(menu);
        if (--cooldown > 0) return;
        cooldown = 30 + r.nextInt(80);
        try {
            act(s, p);
        } catch (Exception e) {
            SoakDriver.problem("bot " + bot.name + " action", e);
        }
    }

    private ServerPlayer lastPlayer;

    private void setup(MinecraftServer s, ServerPlayer p) {
        if (op() && !s.getPlayerList().isOp(p.nameAndId())) s.getPlayerList().op(p.nameAndId());
        GameType want = builder() ? GameType.CREATIVE : GameType.SURVIVAL;
        if (p.gameMode() != want) p.setGameMode(want);
    }

    // ------------------------------------------------------------------ movement

    private void walk(ServerPlayer p) {
        if (r.nextInt(80) == 0) yaw = r.nextFloat() * 360f;
        double rad = Math.toRadians(yaw);
        double nx = p.getX() - Math.sin(rad) * 0.2, nz = p.getZ() + Math.cos(rad) * 0.2;
        ServerLevel l = p.level();
        int bx = (int) Math.floor(nx), bz = (int) Math.floor(nz);
        if (!l.hasChunk(bx >> 4, bz >> 4)) {
            yaw += 180;
            return;
        }
        double ny = p.getY();
        if (l.dimension() == Level.OVERWORLD) ny = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        if (Math.abs(ny - p.getY()) > 1.5) {
            yaw += 90 + r.nextInt(180);
            ny = p.getY();
            nx = p.getX();
            nz = p.getZ();
        }
        bot.send(new ServerboundMovePlayerPacket.PosRot(nx, ny, nz, yaw, 10f, true, false));
    }

    private void explore(MinecraftServer s, ServerPlayer p) {
        SoakTicks.tag("explore");
        ServerLevel ow = s.overworld();
        int x = r.nextInt(2000) - 1000, z = r.nextInt(2000) - 1000;
        goTo(p, ow, x, z);
        home = Level.OVERWORLD;
    }

    private void trip(MinecraftServer s, ServerPlayer p) {
        ResourceKey<Level> dim = r.nextBoolean() ? Level.NETHER : Level.END;
        ServerLevel l = s.getLevel(dim);
        if (l == null) return;
        if (dim == Level.END) {
            BlockPos e = ServerLevel.END_SPAWN_POINT;
            goTo(p, l, e.getX() + r.nextInt(6) - 3, e.getZ() + r.nextInt(6) - 3);
        } else {
            goTo(p, l, r.nextInt(600) - 300, r.nextInt(600) - 300);
        }
        tripBack = 600 + r.nextInt(1200);
    }

    /**
     * Server-side teleport to a safe spot at x,z (what /rtp or a portal would do): the chunk loads through a vanilla
     * PLAYER_SPAWN ticket (it lasts 20 ticks after the load), then {@link #land} teleports from the bot's next tick.
     */
    void goTo(ServerPlayer p, ServerLevel l, int x, int z) {
        if (l == null || goLoad != null) return;
        goLoad = l.getChunkSource().addTicketAndLoadWithRadius(TicketType.PLAYER_SPAWN, new ChunkPos(x >> 4, z >> 4), 1);
        goLevel = l;
        goX = x;
        goZ = z;
        goWait = 0;
        SoakTicks.tag("goto:" + bot.name + ":" + l.dimension().identifier().getPath());
    }

    /** The second half of {@link #goTo}, once the destination chunk is loaded. */
    private void land(ServerPlayer p) {
        ServerLevel l = goLevel;
        int x = goX, z = goZ;
        int y;
        if (l.dimension() == Level.NETHER) {
            y = -1;
            for (int yy = 100; yy > 32; yy--) {
                BlockPos b = new BlockPos(x, yy, z);
                if (l.getBlockState(b).isAir() && l.getBlockState(b.above()).isAir() && l.getBlockState(b.below()).isSolid()) {
                    y = yy;
                    break;
                }
            }
            if (y < 0) {
                l.setBlockAndUpdate(new BlockPos(x, 64, z), net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState());
                l.setBlockAndUpdate(new BlockPos(x, 65, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                l.setBlockAndUpdate(new BlockPos(x, 66, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                y = 65;
            }
        } else {
            y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= l.getMinY() + 1) y = l.dimension() == Level.END ? 50 : 100;
        }
        SoakTicks.tag("land:" + bot.name + ":" + l.dimension().identifier().getPath());
        p.teleportTo(l, x + 0.5, y, z + 0.5, Set.of(), p.getYRot(), p.getXRot(), true);
    }

    // ------------------------------------------------------------------ actions

    private void act(MinecraftServer s, ServerPlayer p) {
        int roll = r.nextInt(100);
        if (roll < 22) command(PLAYER_CMDS[r.nextInt(PLAYER_CMDS.length)]);
        else if (roll < 30) {
            SoakBot other = SoakDriver.bots.get(r.nextInt(SoakDriver.bots.size()));
            command(String.format(PAIR_CMDS[r.nextInt(PAIR_CMDS.length)], other.name));
        } else if (roll < 34 && op()) command(OP_CMDS[r.nextInt(OP_CMDS.length)]);
        else if (roll < 38) chat(CHAT[r.nextInt(CHAT.length)]);
        else if (roll < 52) creature(s, p);
        else if (roll < 64 && builder()) build(p);
        else if (roll < 70) breakPlaced(p);
        else if (roll < 78) companion(p);
        else if (roll < 82) useItem(p);
        else if (roll < 86) fakePlayer(p);
        else if (roll < 88) {
            p.getInventory().add(new ItemStack(Items.COD, 1 + r.nextInt(3)));
            bot.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN));
        } else if (roll < 90) bot.send(new ServerboundSetCarriedItemPacket(r.nextInt(9)));
        else if (roll < 92) {
            // carry / sit on another bot
            SoakBot other = SoakDriver.bots.get(r.nextInt(SoakDriver.bots.size()));
            command("carry " + other.name);
        }
    }

    private void command(String c) {
        bot.lastCommand = c;
        int sp = c.indexOf(' ');
        SoakTicks.tag("cmd:" + (sp < 0 ? c : c.substring(0, sp))); // the packet is handled right away, in this tick
        bot.send(new ServerboundChatCommandPacket(c));
    }

    private void chat(String msg) {
        bot.send(new ServerboundChatPacket(msg, Instant.now(), r.nextLong(), java.util.Optional.empty(),
                new LastSeenMessages.Update(0, new BitSet(20), LastSeenMessages.Update.IGNORE_CHECKSUM)));
    }

    private void creature(MinecraftServer s, ServerPlayer p) {
        AABB box = p.getBoundingBox().inflate(48);
        List<Entity> near = p.level().getEntities(p, box, e -> e instanceof ArcanaMob && e.isAlive());
        if (near.isEmpty()) {
            if (r.nextInt(4) == 0 && op()) {
                // keep creatures around the op bot so every species gets exercised
                var all = new ArrayList<>(ArcanaEntities.all().keySet());
                var type = all.get(r.nextInt(all.size()));
                SoakDriver.command(s, "execute at " + bot.name + " run summon " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + " ~3 ~ ~");
            }
            return;
        }
        Entity e = near.get(r.nextInt(near.size()));
        // approach it
        Vec3 to = e.position().add(1.2, 0, 0);
        p.teleportTo(p.level(), to.x, to.y, to.z, Set.of(), 90f, 0f, false);
        int what = r.nextInt(10);
        if (e instanceof ArcanaCreature c && !c.isTame() && !c.species().untameable() && what < 3 && countOwned(p) < 6) {
            c.tamedBy(p);
            return;
        }
        if (what < 7) {
            ItemStack hand = r.nextBoolean() ? ItemStack.EMPTY : randomFood();
            p.setItemInHand(InteractionHand.MAIN_HAND, hand);
            bot.send(new ServerboundInteractPacket(e.getId(), InteractionHand.MAIN_HAND, new Vec3(0, e.getBbHeight() / 2, 0), r.nextInt(4) == 0));
            if (p.getVehicle() != null && r.nextBoolean()) p.stopRiding();
        } else if (!(e instanceof ArcanaCreature c && c.isTame())) {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            bot.send(new ServerboundAttackPacket(e.getId()));
        }
    }

    private int countOwned(ServerPlayer p) {
        int n = 0;
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(64), x -> x instanceof ArcanaCreature c && c.isOwnedBy(p))) n++;
        return n;
    }

    private ItemStack randomFood() {
        Item[] f = {Items.WHEAT, Items.COD, Items.SALMON, Items.CARROT, Items.APPLE, Items.BONE, Items.BEEF, Items.SWEET_BERRIES, Items.SEAGRASS,
                Items.NAME_TAG, Items.SADDLE, Items.LEAD, Items.BUCKET, Items.SHEARS, Items.GLASS_BOTTLE};
        return new ItemStack(f[r.nextInt(f.length)]);
    }

    private void build(ServerPlayer p) {
        if (furniture == null) {
            furniture = new ArrayList<>();
            for (var c : ArcanaContent.entries().values()) if (c.item() != null && c.block() != null) furniture.add(c.item());
            furniture.add(Items.CHEST);
            furniture.add(Items.TORCH);
        }
        ServerLevel l = p.level();
        BlockPos at = p.blockPosition().offset(r.nextInt(5) - 2, 0, r.nextInt(5) - 2);
        BlockPos below = at.below();
        if (!l.getBlockState(below).isSolid()) return;
        Item item = furniture.get(r.nextInt(furniture.size()));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, 4));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);
        bot.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, ++seq));
        BlockState placedState = l.getBlockState(at);
        if (!placedState.isAir()) {
            placed.add(GlobalPos.of(l.dimension(), at.immutable()));
            // use it right away (sit, open, toggle) with an empty hand, sometimes sneaking
            p.setItemInHand(InteractionHand.MAIN_HAND, r.nextBoolean() ? ItemStack.EMPTY : new ItemStack(Items.COD));
            bot.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(at), Direction.NORTH, at, false), ++seq));
            if (p.getVehicle() != null) p.stopRiding();
            if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        }
        while (placed.size() > 12) breakAt(p, placed.poll());
    }

    private void breakPlaced(ServerPlayer p) {
        if (placed.isEmpty()) return;
        breakAt(p, placed.poll());
    }

    /** True when {@code gp} is in {@code l} and its chunk is loaded (the bot may touch it without loading anything). */
    static boolean reachable(ServerLevel l, GlobalPos gp) {
        return gp.dimension() == l.dimension() && l.hasChunk(gp.pos().getX() >> 4, gp.pos().getZ() >> 4);
    }

    private void breakAt(ServerPlayer p, GlobalPos gp) {
        // placed in another world, or its chunk unloaded since: forget it (never load a chunk or cross worlds for it)
        if (!reachable(p.level(), gp)) return;
        BlockPos pos = gp.pos();
        if (p.level().getBlockState(pos).isAir()) return;
        if (p.distanceToSqr(Vec3.atCenterOf(pos)) > 16) {
            SoakTicks.tag("break-far");
            p.teleportTo(p.level(), pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5, Set.of(), 90f, 0f, false);
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        bot.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, ++seq));
        if (!p.isCreative()) p.gameMode.destroyBlock(pos);
    }

    private void companion(ServerPlayer p) {
        int k = r.nextInt(7);
        switch (k) {
            case 0 -> bot.api(SoakBot.message("pets_request"));
            case 1 -> {
                JsonObject o = SoakBot.message("pets_open");
                o.addProperty("screen", r.nextBoolean() ? "codex" : "pets");
                bot.api(o);
            }
            case 2 -> bot.api(SoakBot.message("open_hub"));
            case 3 -> {
                BlockPos b = p.blockPosition().below();
                JsonObject o = SoakBot.message("inspect");
                o.addProperty("x", b.getX());
                o.addProperty("y", b.getY());
                o.addProperty("z", b.getZ());
                bot.api(o);
            }
            case 4 -> bot.api(SoakBot.message("claims_refresh"));
            case 5 -> {
                JsonObject pets = bot.lastMessage.get("pets");
                String id = petId(pets);
                if (id == null) return;
                String[] acts = {"summon", "stay", "follow", "work", "stable", "bring_out", "revive", "rename", "color"};
                JsonObject o = SoakBot.message("pet_action");
                o.addProperty("pet", id);
                String a = acts[r.nextInt(acts.length)];
                o.addProperty("a", a);
                if (a.equals("rename")) o.addProperty("name", r.nextBoolean() ? "Bot" + r.nextInt(99) : "✦ 名前 " + r.nextInt(9));
                if (a.equals("color")) o.addProperty("color", "#ff9ecb");
                bot.api(o);
            }
            default -> bot.api(SoakBot.message("open_settings"));
        }
    }

    private static String petId(JsonObject pets) {
        if (pets == null || !pets.has("pets") || !pets.get("pets").isJsonArray()) return null;
        JsonArray a = pets.getAsJsonArray("pets");
        if (a.isEmpty()) return null;
        var e = a.get(0);
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        return o.has("id") ? o.get("id").getAsString() : null;
    }

    /** Clicks a random button of a Companion menu (skipping the ones that wipe data, so the run keeps its pets and homes). */
    private void clickMenu(JsonObject menu) {
        if (!menu.has("buttons") || !menu.get("buttons").isJsonArray()) return;
        JsonArray b = menu.getAsJsonArray("buttons");
        if (b.isEmpty()) return;
        int i = r.nextInt(b.size());
        String label = b.get(i).toString().toLowerCase(java.util.Locale.ROOT);
        if (label.contains("release") || label.contains("delete") || label.contains("abandon") || label.contains("unclaim")) return;
        JsonObject o = SoakBot.message("menu_click");
        o.addProperty("id", menu.get("id").getAsInt());
        o.addProperty("button", i);
        bot.api(o);
        menusClicked++;
    }

    private void useItem(ServerPlayer p) {
        Item[] it = {Items.FISHING_ROD, Items.ENDER_PEARL, Items.BREAD, Items.SNOWBALL, Items.BOW, Items.BONE_MEAL};
        Item item = it[r.nextInt(it.length)];
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        if (item == Items.BOW) p.getInventory().add(new ItemStack(Items.ARROW, 4));
        bot.send(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, ++seq, p.getYRot(), p.getXRot()));
        if (item == Items.BOW || item == Items.FISHING_ROD) {
            bot.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        }
    }

    /** Fabric FakePlayer (a "machine" player) interacting with what is around this bot. */
    private void fakePlayer(ServerPlayer p) {
        ServerLevel l = p.level();
        FakePlayer fp = FakePlayer.get(l);
        fp.setPos(p.getX(), p.getY(), p.getZ());
        List<Entity> near = l.getEntities(p, p.getBoundingBox().inflate(8), e -> e instanceof LivingEntity && !(e instanceof ServerPlayer));
        if (!near.isEmpty()) {
            Entity e = near.get(r.nextInt(near.size()));
            fp.setItemInHand(InteractionHand.MAIN_HAND, r.nextBoolean() ? ItemStack.EMPTY : randomFood());
            fp.interactOn(e, InteractionHand.MAIN_HAND, Vec3.ZERO);
            if (r.nextInt(3) == 0) fp.attack(e);
        }
        if (!placed.isEmpty() && reachable(l, placed.peek())) {
            BlockPos b = placed.peek().pos();
            fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            fp.gameMode.useItemOn(fp, l, ItemStack.EMPTY, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(b), Direction.UP, b, false));
        }
        if (fp.containerMenu != fp.inventoryMenu) fp.closeContainer();
        if (fp.getVehicle() != null) fp.stopRiding();
    }

    static Map<String, Integer> summary() {
        return Map.of();
    }
}
