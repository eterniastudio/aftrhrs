package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What players see: claim borders as particles (only for the player looking, never for anyone else), the claim tool
 * (corners, circles, resizing, a live preview, the chunk grid in chunk mode) and the "Entering Sunny's land" line. One
 * task every {@code view.refresh-ticks}; a player holding neither the view item nor the tool costs one claim lookup.
 * Companion players get all of it as data instead ({@link ClaimsCompanion}).
 */
final class ClaimVisuals {
    enum Mode {RECT, CHUNK, CIRCLE, ADMIN}

    /** One player's tool state. Not saved. */
    static final class Session {
        Mode mode = Mode.RECT;
        /** Rectangle mode: the first corner. Circle mode: the center. */
        BlockPos corner;
        ServerLevel cornerLevel;
        Claim resizing;
        Area resizeArea;
        int fixedX, fixedZ;
        /** The last admin selection (two corners in admin mode), for /claim admin create. */
        String selWorld;
        Area selection;
        long showUntil;
        long lastClick;
        long lastBar;
        /** When the claim admin mode reminder was last shown. */
        long lastBypassBar;
    }

    private final ClaimsModule m;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Which claim each online player stood in last time (0 = none), for enter/leave lines. */
    private final Map<UUID, Integer> standing = new HashMap<>();
    private final Map<Integer, DustParticleOptions> dust = new HashMap<>();
    private final Map<Integer, DustParticleOptions> bigDust = new HashMap<>();
    private Tasks.Task task;

    ClaimVisuals(ClaimsModule m) {
        this.m = m;
    }

    Session session(ServerPlayer p) {
        return sessions.computeIfAbsent(p.getUUID(), k -> new Session());
    }

    void start() {
        stop();
        dust.clear();
        bigDust.clear();
        task = Tasks.timer(20L, m.settings.refreshTicks, this::run);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    void forget(UUID id) {
        sessions.remove(id);
        standing.remove(id);
    }

    /** Borders on for a while (after claiming, /claim show, the menu's Show Borders). */
    void flash(ServerPlayer p) {
        session(p).showUntil = System.currentTimeMillis() + m.settings.showSeconds * 1000L;
        m.bridge.show(p);
    }

    static void registerEvents() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
            ClaimsModule m = ClaimsModule.active;
            if (m == null) return;
            Session s = m.visuals.sessions.get(p.getUUID());
            if (s != null) {
                s.corner = null;
                s.resizing = null;
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ClaimsModule m = ClaimsModule.active;
            if (m == null || !(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            return m.visuals.onTool(p, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ClaimsModule m = ClaimsModule.active;
            if (m == null || !(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            return m.visuals.onTool(p, null) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
    }

    // ------------------------------------------------------------------ the claim tool

    /** A right-click (on a block, or the air when {@code hit} is null). True when it was the claim tool. */
    private boolean onTool(ServerPlayer p, BlockHitResult hit) {
        if (p.getMainHandItem().getItem() != m.settings.tool || !Perms.has(p, ClaimsModule.USE)) return false;
        if (m.plugin.authPending(p)) return false;
        Session s = session(p);
        // The client draws the selection itself: tell it what changed once this click is handled.
        Tasks.later(1, () -> m.bridge.tool(p));
        long now = System.currentTimeMillis();
        // One click on a block can also arrive as an air click right after; count it once.
        if (now - s.lastClick < 200) return true;
        s.lastClick = now;
        BlockPos b = hit == null || hit.getType() == HitResult.Type.MISS ? null : hit.getBlockPos().immutable();
        ServerLevel level = p.level();
        Text text = m.text();
        if (p.isShiftKeyDown()) {
            s.mode = switch (s.mode) {
                case RECT -> Mode.CHUNK;
                case CHUNK -> Mode.CIRCLE;
                case CIRCLE -> Perms.has(p, ClaimsModule.ADMIN) ? Mode.ADMIN : Mode.RECT;
                case ADMIN -> Mode.RECT;
            };
            s.corner = null;
            s.resizing = null;
            text.send(p, "claims.tool.mode-" + s.mode.name().toLowerCase(Locale.ROOT));
            text.sound(p, "click");
            return true;
        }
        if (s.mode == Mode.CIRCLE) {
            circleClick(p, s, b);
            return true;
        }
        if (b == null) return true;
        if (s.mode == Mode.CHUNK) {
            m.claim(p, level, Area.ofChunk(b.getX() >> 4, b.getZ() >> 4));
            return true;
        }
        if (s.corner != null && s.cornerLevel != level) {
            s.corner = null;
            s.resizing = null;
        }
        if (s.corner == null) {
            Claim c = m.registry.at(level, b);
            if (c != null && m.canManage(p, c)) {
                for (Area a : c.areas) {
                    if (!a.isCorner(b.getX(), b.getZ())) continue;
                    int[] o = a.opposite(b.getX(), b.getZ());
                    s.resizing = c;
                    s.resizeArea = a;
                    s.fixedX = o[0];
                    s.fixedZ = o[1];
                    s.corner = b;
                    s.cornerLevel = level;
                    text.send(p, "claims.tool.resize-start", Text.unparsed("name", c.name));
                    text.sound(p, "click");
                    return true;
                }
            }
            s.corner = b;
            s.cornerLevel = level;
            s.resizing = null;
            text.send(p, "claims.tool.corner1", Text.unparsed("x", b.getX()), Text.unparsed("z", b.getZ()));
            text.sound(p, "click");
            return true;
        }
        BlockPos first = s.corner;
        s.corner = null;
        if (s.resizing != null) {
            Claim c = s.resizing;
            Area old = s.resizeArea;
            s.resizing = null;
            if (c.removed || !c.areas.contains(old)) {
                text.send(p, "claims.tool.resize-gone");
                return true;
            }
            m.resize(p, c, old, Area.rect(s.fixedX, s.fixedZ, b.getX(), b.getZ()));
            return true;
        }
        Area a = Area.rect(first.getX(), first.getZ(), b.getX(), b.getZ());
        if (s.mode == Mode.ADMIN) {
            s.selection = a;
            s.selWorld = Worlds.name(level);
            text.send(p, "claims.tool.admin-selected", Text.unparsed("size", a.width() + " × " + a.length()));
            text.sound(p, "click");
            return true;
        }
        m.claim(p, level, a);
        return true;
    }

    /**
     * Circle mode: the first click (on a block) sets the center, the next one (anywhere) claims the circle out to where
     * the player stands. Clicking the center of one of your circles first resizes that circle instead.
     */
    private void circleClick(ServerPlayer p, Session s, BlockPos b) {
        Text text = m.text();
        ServerLevel level = p.level();
        if (s.corner != null && s.cornerLevel != level) {
            s.corner = null;
            s.resizing = null;
        }
        if (s.corner == null) {
            if (b == null) {
                text.send(p, "claims.tool.circle-need-block");
                return;
            }
            Claim c = m.registry.at(level, b);
            if (c != null && m.canManage(p, c)) {
                for (Area a : c.areas) {
                    if (!a.round() || a.centerX() != b.getX() || a.centerZ() != b.getZ()) continue;
                    s.resizing = c;
                    s.resizeArea = a;
                    s.corner = b;
                    s.cornerLevel = level;
                    text.send(p, "claims.tool.circle-resize-start", Text.unparsed("name", c.name), Text.unparsed("r", a.radius()));
                    text.sound(p, "click");
                    return;
                }
            }
            s.corner = b;
            s.cornerLevel = level;
            s.resizing = null;
            text.send(p, "claims.tool.circle-center", Text.unparsed("x", b.getX()), Text.unparsed("z", b.getZ()));
            text.sound(p, "click");
            return;
        }
        BlockPos center = s.corner;
        s.corner = null;
        Area a = circleTo(center, p.getX(), p.getZ());
        if (s.resizing != null) {
            Claim c = s.resizing;
            Area old = s.resizeArea;
            s.resizing = null;
            if (c.removed || !c.areas.contains(old)) {
                text.send(p, "claims.tool.resize-gone");
                return;
            }
            m.resize(p, c, old, a);
            return;
        }
        m.claim(p, s.cornerLevel != null ? s.cornerLevel : level, a);
    }

    /** The circle around {@code center} reaching out to (x, z) (horizontal distance, rounded). */
    static Area circleTo(BlockPos center, double x, double z) {
        double dx = x - (center.getX() + 0.5), dz = z - (center.getZ() + 0.5);
        return Area.circle(center.getX(), center.getZ(), (int) Math.round(Math.sqrt(dx * dx + dz * dz)));
    }

    // ------------------------------------------------------------------ the task

    private void run() {
        long now = System.currentTimeMillis();
        boolean enter = !m.settings.enterMode.equals("off");
        for (ServerPlayer p : m.plugin.onlinePlayers()) {
            if (m.plugin.authPending(p)) continue;
            Claim here = m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
            // Claim admin mode: a reminder while it lets you into someone else's claim (modded or not)
            if (here != null && !here.isOwner(p.getUUID()) && m.bypassing(p)) {
                Session b = session(p);
                if (now - b.lastBypassBar >= 2000) {
                    b.lastBypassBar = now;
                    m.text().actionBar(p, "claims.bar.bypass", Text.unparsed("owner", m.ownerLabel(here)), Text.unparsed("claim", here.name));
                }
            }
            // Modded players get claims as data (ClaimsCompanion): the client draws borders, the selection, the HUD and
            // the enter/leave banner.
            if (m.bridge.modded(p)) {
                standing.put(p.getUUID(), here == null ? 0 : here.id);
                continue;
            }
            if (enter) enterLeave(p, here);
            Item main = p.getMainHandItem().getItem();
            Item off = p.getOffhandItem().getItem();
            boolean stick = main == m.settings.viewItem || off == m.settings.viewItem;
            boolean tool = main == m.settings.tool && Perms.has(p, ClaimsModule.USE);
            Session s = sessions.get(p.getUUID());
            boolean show = s != null && s.showUntil > now;
            if (!stick && !tool && !show) continue;
            int[] budget = {m.settings.maxParticles};
            try {
                drawClaims(p, budget);
                if (tool) {
                    Session t = session(p);
                    if (t.corner != null) drawPreview(p, t, budget);
                    else if (t.mode == Mode.CHUNK && m.settings.chunkGrid) drawGrid(p, budget);
                    toolBar(p, t, now);
                } else if (stick) {
                    standBar(p, here, now);
                }
            } catch (RuntimeException ex) {
                m.plugin.getLogger().warn("Claim borders for {} failed: {}", p.getName().getString(), ex.toString());
            }
        }
    }

    private void enterLeave(ServerPlayer p, Claim here) {
        int id = here == null ? 0 : here.id;
        Integer last = standing.put(p.getUUID(), id);
        if (last == null || last == id) return;
        Claim before = last == 0 ? null : m.registry.get(last);
        Text text = m.text();
        if (here != null && !here.isOwner(p.getUUID()) && here.flag(ClaimFlag.ENTER_MESSAGE)) {
            if (here.admin() && here.greeting != null && !here.greeting.isEmpty()) {
                show(p, text.parse(here.greeting, Text.unparsed("claim", here.name)));
            } else {
                show(p, text.msg(here.admin() ? "claims.enter-admin" : "claims.enter", Text.unparsed("owner", m.ownerLabel(here)),
                        Text.unparsed("claim", here.name)));
            }
        } else if (here == null && before != null && !before.isOwner(p.getUUID()) && before.flag(ClaimFlag.ENTER_MESSAGE)) {
            show(p, text.msg(before.admin() ? "claims.leave-admin" : "claims.leave", Text.unparsed("owner", m.ownerLabel(before)),
                    Text.unparsed("claim", before.name)));
        }
    }

    private void show(ServerPlayer p, Component line) {
        if (m.settings.enterMode.equals("title")) {
            Adv.of(p).showTitle(Title.title(Component.empty(), line,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        } else {
            Adv.actionBar(p, line);
        }
    }

    private void standBar(ServerPlayer p, Claim here, long now) {
        Session s = session(p);
        if (now - s.lastBar < 1000) return;
        s.lastBar = now;
        Text text = m.text();
        if (here == null) text.actionBar(p, "claims.bar.wild");
        else text.actionBar(p, here.admin() ? "claims.bar.admin" : "claims.bar.claim", m.claimTags(here, p));
    }

    private void toolBar(ServerPlayer p, Session s, long now) {
        if (now - s.lastBar < 500) return;
        s.lastBar = now;
        Text text = m.text();
        String left = String.valueOf(Math.max(0, m.remaining(p.getUUID())));
        if (s.corner != null && s.mode == Mode.CIRCLE) {
            Area a = circleTo(s.corner, p.getX(), p.getZ());
            // Past the largest allowed circle (e.g. teleported far away) don't size it: blocks() grows with the radius.
            if (a.radius() > m.settings.maxRadius) {
                text.actionBar(p, "claims.bar.circle-too-big", Text.unparsed("size", m.size(a)), Text.unparsed("max", m.settings.maxRadius));
                return;
            }
            text.actionBar(p, s.resizing != null ? "claims.bar.circle-resizing" : "claims.bar.circle", Text.unparsed("size", m.size(a)),
                    Text.unparsed("blocks", m.chunks(Area.chunks(a.blocks()))), Text.unparsed("left", left));
        } else if (s.corner != null) {
            BlockPos to = target(p);
            Area a = s.resizing != null ? Area.rect(s.fixedX, s.fixedZ, to.getX(), to.getZ()) : Area.rect(s.corner.getX(), s.corner.getZ(), to.getX(), to.getZ());
            text.actionBar(p, s.resizing != null ? "claims.bar.resizing" : "claims.bar.selecting", Text.unparsed("size", a.width() + " × " + a.length()),
                    Text.unparsed("blocks", m.chunks(Area.chunks(a.blocks()))), Text.unparsed("left", left));
        } else {
            text.actionBar(p, "claims.bar.tool-" + s.mode.name().toLowerCase(Locale.ROOT), Text.unparsed("left", left));
        }
    }

    /** The block the player looks at (within the view radius), else the one under their feet. */
    private BlockPos target(ServerPlayer p) {
        try {
            HitResult hit = p.pick(m.settings.viewRadius, 1f, false);
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) return bh.getBlockPos();
        } catch (RuntimeException ignored) {
        }
        return p.blockPosition().below();
    }

    // ------------------------------------------------------------------ drawing

    private int colorFor(Claim c, ServerPlayer p) {
        if (c.admin()) return m.settings.adminColor;
        if (c.isOwner(p.getUUID())) return m.settings.own;
        if (c.levelOf(p.getUUID()) != null) return m.settings.trusted;
        return m.settings.others;
    }

    private void point(ServerPlayer p, double x, double y, double z, int col, boolean big, int[] budget) {
        if (budget[0] <= 0) return;
        budget[0]--;
        DustParticleOptions o = (big ? bigDust : dust).computeIfAbsent(col, c -> new DustParticleOptions(c, big ? 1.6f : 0.9f));
        p.level().sendParticles(p, o, true, false, x, y, z, 1, 0, 0, 0, 0);
    }

    /** Points per block edge: closer edges are denser. 0 = skip this block. */
    private static double[] samples(double d, int i) {
        if (d < 12) return new double[]{0.25, 0.75};
        if (d < 28) return new double[]{0.5};
        if (d < 40) return i % 2 == 0 ? new double[]{0.5} : new double[0];
        return i % 3 == 0 ? new double[]{0.5} : new double[0];
    }

    private void drawClaims(ServerPlayer p, int[] budget) {
        int r = m.settings.viewRadius;
        double y = p.getEyeY() - 0.4;
        for (Claim c : m.registry.near(Worlds.name(p.level()), p.getBlockX(), p.getBlockZ(), r)) {
            int col = colorFor(c, p);
            for (Area a : c.areas) {
                outline(p, c, a, col, y, p.getX(), p.getZ(), r, budget);
                if (budget[0] <= 0) return;
            }
        }
    }

    /** The edges of one area that are on the claim's outline (edges shared with another of its areas are skipped). */
    private void outline(ServerPlayer p, Claim c, Area a, int col, double y, double px, double pz, int r, int[] budget) {
        if (a.round()) {
            outlineRound(p, c, a, col, y, px, pz, r, budget);
            return;
        }
        // North (z = minZ) and south (z = maxZ + 1) edges.
        for (int side = 0; side < 2; side++) {
            int edgeZ = side == 0 ? a.minZ() : a.maxZ() + 1;
            int outZ = side == 0 ? a.minZ() - 1 : a.maxZ() + 1;
            if (Math.abs(edgeZ - pz) > r) continue;
            int from = Math.max(a.minX(), (int) Math.floor(px) - r), to = Math.min(a.maxX(), (int) Math.floor(px) + r);
            for (int x = from; x <= to; x++) {
                if (c != null && c.contains(x, outZ)) continue;
                double d = Math.hypot(x + 0.5 - px, edgeZ - pz);
                if (d > r) continue;
                for (double t : samples(d, x)) point(p, x + t, y, edgeZ, col, false, budget);
            }
        }
        // West (x = minX) and east (x = maxX + 1) edges.
        for (int side = 0; side < 2; side++) {
            int edgeX = side == 0 ? a.minX() : a.maxX() + 1;
            int outX = side == 0 ? a.minX() - 1 : a.maxX() + 1;
            if (Math.abs(edgeX - px) > r) continue;
            int from = Math.max(a.minZ(), (int) Math.floor(pz) - r), to = Math.min(a.maxZ(), (int) Math.floor(pz) + r);
            for (int z = from; z <= to; z++) {
                if (c != null && c.contains(outX, z)) continue;
                double d = Math.hypot(edgeX - px, z + 0.5 - pz);
                if (d > r) continue;
                for (double t : samples(d, z)) point(p, edgeX, y, z + t, col, false, budget);
            }
        }
        // Corner posts where the outline turns.
        int[][] corners = {{a.minX(), a.minZ()}, {a.maxX() + 1, a.minZ()}, {a.minX(), a.maxZ() + 1}, {a.maxX() + 1, a.maxZ() + 1}};
        for (int[] k : corners) {
            if (Math.hypot(k[0] - px, k[1] - pz) > r) continue;
            if (c != null && !outlineCorner(c, k[0], k[1])) continue;
            for (double dy = -1.5; dy <= 1.51; dy += 0.5) point(p, k[0], y + dy, k[1], col, true, budget);
        }
    }

    private static boolean inside(Claim c, Area a, int x, int z) {
        return c != null ? c.contains(x, z) : a.contains(x, z);
    }

    /**
     * A circle's outline, block by block (it follows exactly the columns that are protected): for each row, the outer
     * sides of its end blocks, and the north/south faces of blocks with nothing of the claim beyond. Posts at the four
     * ends; a preview also marks the center.
     */
    private void outlineRound(ServerPlayer p, Claim c, Area a, int col, double y, double px, double pz, int r, int[] budget) {
        int z0 = Math.max(a.minZ(), (int) Math.floor(pz) - r), z1 = Math.min(a.maxZ(), (int) Math.floor(pz) + r);
        int x0 = (int) Math.floor(px) - r, x1 = (int) Math.floor(px) + r;
        for (int z = z0; z <= z1; z++) {
            int[] s = a.span(z);
            if (s == null) continue;
            for (int side = 0; side < 2; side++) {
                int edgeX = side == 0 ? s[0] : s[1] + 1;
                int outX = side == 0 ? s[0] - 1 : s[1] + 1;
                if (inside(c, a, outX, z)) continue;
                double d = Math.hypot(edgeX - px, z + 0.5 - pz);
                if (d > r) continue;
                for (double t : samples(d, z)) point(p, edgeX, y, z + t, col, false, budget);
            }
            for (int side = 0; side < 2; side++) {
                int outZ = side == 0 ? z - 1 : z + 1;
                int edgeZ = side == 0 ? z : z + 1;
                for (int x = Math.max(s[0], x0); x <= Math.min(s[1], x1); x++) {
                    if (inside(c, a, x, outZ)) continue;
                    double d = Math.hypot(x + 0.5 - px, edgeZ - pz);
                    if (d > r) continue;
                    for (double t : samples(d, x)) point(p, x + t, y, edgeZ, col, false, budget);
                }
            }
            if (budget[0] <= 0) return;
        }
        int cx = a.centerX(), cz = a.centerZ();
        // {post x, post z, the block just outside it}
        double[][] posts = {{cx + 0.5, a.minZ(), cx, a.minZ() - 1}, {cx + 0.5, a.maxZ() + 1, cx, a.maxZ() + 1},
                {a.minX(), cz + 0.5, a.minX() - 1, cz}, {a.maxX() + 1, cz + 0.5, a.maxX() + 1, cz}};
        for (double[] k : posts) {
            if (Math.hypot(k[0] - px, k[1] - pz) > r) continue;
            if (c != null && c.contains((int) k[2], (int) k[3])) continue;
            for (double dy = -1.5; dy <= 1.51; dy += 0.5) point(p, k[0], y + dy, k[1], col, true, budget);
        }
        if (c == null && Math.hypot(cx + 0.5 - px, cz + 0.5 - pz) <= r) {
            for (double dy = -1.5; dy <= 1.51; dy += 0.5) point(p, cx + 0.5, y + dy, cz + 0.5, col, true, budget);
        }
    }

    /** Whether the lattice point (x, z) is a corner of the claim's outline. */
    private static boolean outlineCorner(Claim c, int x, int z) {
        boolean a = c.contains(x - 1, z - 1), b = c.contains(x, z - 1), d = c.contains(x - 1, z), e = c.contains(x, z);
        int n = (a ? 1 : 0) + (b ? 1 : 0) + (d ? 1 : 0) + (e ? 1 : 0);
        return n == 1 || n == 3 || (n == 2 && a == e);
    }

    private void drawPreview(ServerPlayer p, Session s, int[] budget) {
        if (p.level() != s.cornerLevel) return;
        double y = p.getEyeY() - 0.4;
        if (s.mode == Mode.CIRCLE) {
            outline(p, null, circleTo(s.corner, p.getX(), p.getZ()), m.settings.preview, y, p.getX(), p.getZ(), m.settings.viewRadius, budget);
            return;
        }
        BlockPos to = target(p);
        Area a = s.resizing != null ? Area.rect(s.fixedX, s.fixedZ, to.getX(), to.getZ()) : Area.rect(s.corner.getX(), s.corner.getZ(), to.getX(), to.getZ());
        outline(p, null, a, m.settings.preview, y, p.getX(), p.getZ(), m.settings.viewRadius, budget);
    }

    private void drawGrid(ServerPlayer p, int[] budget) {
        int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
        double y = p.getEyeY() - 0.4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Area a = Area.ofChunk(cx + dx, cz + dz);
                int col = dx == 0 && dz == 0 ? m.settings.preview : m.settings.grid;
                outline(p, null, a, col, y, p.getX(), p.getZ(), m.settings.viewRadius, budget);
            }
        }
    }
}
