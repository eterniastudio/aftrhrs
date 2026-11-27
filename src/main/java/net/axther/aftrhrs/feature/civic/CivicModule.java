package net.axther.aftrhrs.feature.civic;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Civic: what the spawn furniture does (civic.yml). Hubs (notice board, warp kiosk, stable post, trash bin, fish
 * scale) open Feather's menus on right-click; fountains assemble from basin + tiers + spout and splash for nearby
 * players, can show the server goal and take a wished coin into the vault reserve (Lucky Lure); string lights hang
 * between two posts; a following Hushwing keeps its owner's vibrations from sculk sensors and shriekers. Furniture is
 * a real block now: every piece is waterloggable and gives its own light, so the old underwater-lights workaround
 * (waterlogged LIGHT blocks next to Oraxen displays) has nothing left to do.
 */
public final class CivicModule implements FeatherModule, FeatherCommand {
    public static final String ADMIN = "feather.civic.admin";

    static volatile CivicModule active;

    final Feather plugin;
    Yaml cfg;
    final Fountains fountains;
    final StringLights lights;
    final Hushwing hushwing;
    private final Map<String, String> hubs = new LinkedHashMap<>();
    private String hubSound;
    private final Set<String> warned = new HashSet<>();
    private final List<Tasks.Task> tasks = new ArrayList<>();

    public CivicModule(Feather plugin) {
        this.plugin = plugin;
        this.fountains = new Fountains(this);
        this.lights = new StringLights(this);
        this.hushwing = new Hushwing(this);
    }

    @Override
    public String name() {
        return "Civic";
    }

    @Override
    public void enable() {
        active = this;
        plugin.bind("civic", this);
        plugin.once("civic.events", CivicModule::registerEvents);
        lights.loadData();
        reload();
        fountains.scanAll();
    }

    @Override
    public void reload() {
        cfg = plugin.loadResourceConfig("civic.yml");
        hubs.clear();
        Yaml hs = cfg.getConfigurationSection("hubs");
        if (hs != null) {
            for (String k : hs.getKeys(false)) {
                Object v = hs.get(k);
                if (v instanceof String s && !s.isBlank()) hubs.put(k.toLowerCase(Locale.ROOT), s.trim());
            }
        }
        hubSound = cfg.getString("hub-sound", "block.wood.hit");
        fountains.load(section("fountains"), section("coin-toss"));
        lights.load(section("string-lights"));
        hushwing.load(section("hushwing"));
        stopTasks();
        lights.clearAllParts();
        tasks.add(Tasks.timer(20L, Math.max(1, fountains.interval()), fountains::tick));
        tasks.add(Tasks.timer(40L, 100L, fountains::sweep));
        tasks.add(Tasks.timer(30L, 20L, lights::sweep));
    }

    @Override
    public void disable() {
        stopTasks();
        lights.clearAllParts();
        lights.saveNow();
        fountains.clear();
        if (active == this) active = null;
    }

    private void stopTasks() {
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
    }

    private Yaml section(String path) {
        Yaml y = cfg.getConfigurationSection(path);
        return y != null ? y : new Yaml();
    }

    // ------------------------------------------------------------------ listeners (once)

    private static void registerEvents() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            CivicModule m = active;
            if (m != null) m.fountains.chunkLoaded(level, chunk);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            CivicModule m = active;
            if (m != null) m.fountains.chunkUnloaded(level, chunk);
        });
        FeatherEvents.VIBRATION.register((level, receiver, event, context) -> {
            CivicModule m = active;
            return m == null || m.hushwing.allow(receiver, context);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            CivicModule m = active;
            if (m != null) m.lights.forget(h.player.getUUID());
        });
        ArcanaContent.hook(id -> active != null && active.claims(id), new FurnitureHook() {
            @Override
            public InteractionResult onUse(FurnitureContext ctx) {
                CivicModule m = active;
                if (m == null || ctx.player() == null || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
                return m.handle(ctx) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }

            @Override
            public void onPlaced(FurnitureContext ctx) {
                CivicModule m = active;
                if (m != null) m.fountains.placed(ctx.level(), ctx.origin(), ctx.id());
            }

            @Override
            public void onRemoved(FurnitureContext ctx) {
                CivicModule m = active;
                if (m == null) return;
                m.lights.postGone(ctx.level(), ctx.origin(), ctx.id());
                m.fountains.removed(ctx.level(), ctx.origin(), ctx.id());
            }
        });
        Legacy.onFurniture((level, origin, id, bv, yaw) -> {
            CivicModule m = active;
            if (m != null) m.fountains.migrate(level, origin, id, bv);
        });
        Legacy.converter((level, entity, bv) -> {
            if (!bv.contains(StringLights.PART)) return false;
            entity.discard();   // an unsaved string-light part saved by mistake
            return true;
        });
    }

    /** Ids whose clicks/placement civic looks at. */
    boolean claims(String id) {
        return hubs.containsKey(id) || lights.isPost(id) || fountains.kindOf(id) != null;
    }

    // ------------------------------------------------------------------ shared helpers

    void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.getLogger().warn(message);
    }

    void bar(ServerPlayer p, String key, TagResolver... r) {
        plugin.text().actionBar(p, "civic." + key, r);
    }

    void send(ServerPlayer p, String key, TagResolver... r) {
        plugin.text().send(p, "civic." + key, r);
    }

    static TagResolver tag(String key, Object value) {
        return Text.unparsed(key, value);
    }

    /** A fresh content item, or null (logged once). */
    net.minecraft.world.item.ItemStack item(String id) {
        var s = Coast.item(id, 1);
        if (s == null) warnOnce("item:" + id, "civic.yml names item '" + id + "', which doesn't exist.");
        return s;
    }

    /** Game mode and land claims. Says why when refused. */
    boolean mayBuild(ServerPlayer p, ServerLevel level, BlockPos at, boolean tell) {
        boolean ok = p.gameMode() != GameType.ADVENTURE && Coast.allowed(p, level, at, ClaimAction.BUILD);
        if (!ok && tell) bar(p, "claim");
        return ok;
    }

    // ------------------------------------------------------------------ clicks

    /** Returns true when the click was used. */
    private boolean handle(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        if (p.gameMode() == GameType.SPECTATOR) return false;
        String id = ctx.id();
        String heldId = Coast.id(p.getMainHandItem());
        boolean used = false;
        if (heldId != null && fountains.isStackable(heldId) && fountains.kindOf(id) != null) used = fountains.stack(p, ctx, heldId);
        else if (lights.isPost(id) && lights.isLightsItem(p.getMainHandItem())) used = lights.click(p, ctx.level(), ctx.origin());
        else if (fountains.isBasin(id) && fountains.isCoin(p.getMainHandItem())) used = fountains.toss(p, ctx.level(), ctx.origin());
        else if (!p.isSecondaryUseActive() && hubs.containsKey(id)) used = openHub(p, id, ctx);
        if (used) Coast.swing(p);
        return used;
    }

    private boolean openHub(ServerPlayer p, String id, FurnitureContext ctx) {
        String line = hubs.get(id);
        String label = line.split(" ", 2)[0];
        if (!commandWorks(p, label)) {
            bar(p, "hub-closed");
            return true;
        }
        Coast.sound(ctx.level(), ctx.origin(), hubSound, 0.6f, 1.2f);
        var server = p.level().getServer();
        server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), line);
        return true;
    }

    /** False when the command is unknown, or is one of Feather's own whose module is off (nothing bound). */
    private boolean commandWorks(ServerPlayer p, String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (CommandBridge.isBound(l)) return true;
        var node = p.level().getServer().getCommands().getDispatcher().getRoot().getChild(l);
        return node != null && node.canUse(p.createCommandSourceStack());
    }

    // ------------------------------------------------------------------ /civic

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            plugin.text().send(sender, "civic.no-permission");
            return true;
        }
        if (!sender.isPlayer()) {
            plugin.text().send(sender, "general.players-only");
            return true;
        }
        ServerPlayer p = sender.player();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "fountain" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("goal")) {
                    send(p, "usage");
                    return true;
                }
                BlockPos basin = fountains.basinFor(p.level(), lookedAt(p), p.blockPosition());
                if (basin == null) {
                    send(p, "fountain.not-looking");
                    return true;
                }
                boolean now = fountains.toggleGoal(p.level(), basin);
                send(p, now ? "fountain.linked" : "fountain.unlinked");
            }
            case "info" -> info(p);
            default -> send(p, "usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(ADMIN)) return out;
        if (args.length == 1) out.addAll(List.of("fountain", "info"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("fountain")) out.add("goal");
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }

    /** The furniture origin the player looks at (within 8 blocks), or null. */
    private BlockPos lookedAt(ServerPlayer p) {
        HitResult hr = p.pick(8, 1f, false);
        if (!(hr instanceof BlockHitResult bh) || hr.getType() != HitResult.Type.BLOCK) return null;
        var st = p.level().getBlockState(bh.getBlockPos());
        if (!ArcanaContent.isFurniture(st)) return null;
        return ArcanaContent.origin(st, bh.getBlockPos());
    }

    /** Staff and test view: civic furniture and strings near the player, one line each. */
    private void info(ServerPlayer p) {
        for (String line : fountains.describe(p.level(), p.blockPosition(), 12)) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        for (String line : lights.describe(p.level(), p.position(), 24)) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        BlockPos c = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-6, -6, -6), c.offset(6, 6, 6))) {
            var st = p.level().getBlockState(b);
            if (!ArcanaContent.isFurniture(st) || !ArcanaContent.origin(st, b).equals(b)) continue;
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("FURNITURE " + ArcanaContent.id(st) + " @" + b.getX() + "," + b.getY() + "," + b.getZ()));
        }
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("CIVIC hushwing=" + hushwing.silenced(p) + " luckyLureUntil="
                + Pdc.getLong(p, Fountains.LURE, 0L)));
    }
}
