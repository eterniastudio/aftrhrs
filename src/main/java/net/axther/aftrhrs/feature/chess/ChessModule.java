package net.axther.aftrhrs.feature.chess;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.net.Companion;
import net.axther.aftrhrs.net.Wire;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
/**
 * Chess tables (docs/chess.md): right-click {@code arcana_chess_table} to open the mod's chess screen; the game lives
 * in the table's {@link ChessBlockEntity}. Client requests arrive as Companion messages ({@code chess_*}, feature
 * {@code chess}, docs/companion-protocol.md) and go through the same checks as everything else: reach, game mode and
 * the land claim (Access trust to sit down and play; anyone may watch, get up or resign).
 */
public final class ChessModule implements FeatherModule {
    public static final String TABLE = "arcana_chess_table";
    public static final String FEATURE = "chess";
    private static volatile ChessModule active;

    private final Feather plugin;

    public ChessModule(Feather plugin) {
        this.plugin = plugin;
    }

    /** Which furniture ids are chess tables (block entity + hook). */
    public static boolean isTable(String id) {
        return TABLE.equals(id);
    }

    static @Nullable ChessModule active() {
        return active;
    }

    @Override
    public String name() {
        return "Chess";
    }

    @Override
    public void enable() {
        active = this;
        plugin.once("chess.hooks", () -> {
            ArcanaContent.hook(ChessModule::isTable, new FurnitureHook() {
                @Override
                public InteractionResult onUse(FurnitureContext ctx) {
                    ChessModule m = active;
                    return m == null ? InteractionResult.PASS : m.use(ctx);
                }
            });
            on("chess_sit", true, (p, be, msg) -> be.sit(p, !"black".equals(Wire.str(msg, "side", "white"))));
            on("chess_leave", false, (p, be, msg) -> be.leave(p));
            on("chess_move", true, (p, be, msg) -> be.move(p, Wire.str(msg, "m", ""), Wire.num(msg, "g", -1)));
            on("chess_resign", false, (p, be, msg) -> sameGame(be, msg) ? be.resign(p) : "stale");
            on("chess_draw", true, (p, be, msg) -> sameGame(be, msg) ? be.offerDraw(p) : "stale");
            on("chess_decline", false, (p, be, msg) -> sameGame(be, msg) ? be.declineDraw(p) : "stale");
            on("chess_new", true, (p, be, msg) -> be.newGame(p));
            on("chess_time", true, (p, be, msg) -> be.setTimeControl(p, Wire.num(msg, "tc", -1)));
        });
    }

    @Override
    public void disable() {
        if (active == this) active = null;
    }

    private interface Action {
        @Nullable String run(ServerPlayer p, ChessBlockEntity be, JsonObject msg);
    }

    private static boolean sameGame(ChessBlockEntity be, JsonObject msg) {
        return Wire.num(msg, "g", -1) == be.gameId();
    }

    /** A client request about the table at x/y/z; {@code play}: sitting down / playing (needs Access trust). */
    private static void on(String type, boolean play, Action action) {
        Companion.on(type, FEATURE, (p, msg) -> {
            ChessModule m = active;
            if (m == null) return;
            BlockPos pos = new BlockPos(Wire.num(msg, "x", 0), Wire.num(msg, "y", Integer.MIN_VALUE), Wire.num(msg, "z", 0));
            if (!(p.level() instanceof ServerLevel level) || !level.isLoaded(pos)
                    || !(level.getBlockEntity(pos) instanceof ChessBlockEntity be)) return;
            String err = m.check(p, level, be, play);
            if (err == null) err = action.run(p, be, msg);
            if (err != null) reply(p, be, err);
        });
    }

    /** Reach and game mode; null when the player may act. Chess tables are public: claims don't stop a game. */
    @Nullable String check(ServerPlayer p, ServerLevel level, ChessBlockEntity be, boolean play) {
        if (p.position().distanceToSqr(Vec3.atCenterOf(be.getBlockPos())) > ChessBlockEntity.REACH * ChessBlockEntity.REACH) return "far";
        if (!play) return null;
        if (p.gameMode() == GameType.SPECTATOR) return "spectator";
        return null;
    }

    private static void reply(ServerPlayer p, ChessBlockEntity be, String key) {
        JsonObject o = Companion.message("chess_msg");
        BlockPos b = be.getBlockPos();
        o.addProperty("x", b.getX());
        o.addProperty("y", b.getY());
        o.addProperty("z", b.getZ());
        o.addProperty("key", key);
        Companion.send(p, o);
    }

    // ------------------------------------------------------------------ the click

    private InteractionResult use(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        if (p == null || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        // sneaking: placing blocks on the table / turning it stay as they are
        if (ctx.sneaking()) return InteractionResult.PASS;
        if (!(ctx.level().getBlockEntity(ctx.origin()) instanceof ChessBlockEntity be)) return InteractionResult.PASS;
        if (!open(p, be)) {
            p.sendSystemMessage(Component.translatableWithFallback("aftrhrs.chess.needs-mod",
                    "This chess table is played on the Aftr Hrs mod's chess screen."));
        }
        return InteractionResult.SUCCESS;
    }

    /** Opens the chess screen on the player's client; false when their client can't show it. */
    public static boolean open(ServerPlayer p, ChessBlockEntity be) {
        if (!Companion.has(p, FEATURE)) return false;
        JsonObject o = Companion.message("chess_open");
        BlockPos b = be.getBlockPos();
        o.addProperty("x", b.getX());
        o.addProperty("y", b.getY());
        o.addProperty("z", b.getZ());
        Companion.send(p, o);
        return true;
    }

    // ------------------------------------------------------------------ game over

    /** A game at this table just ended: the sound, the checkmate announcement, the advancement. */
    void ended(ServerLevel level, ChessBlockEntity be) {
        ChessGame g = be.game();
        BlockPos b = be.getBlockPos();
        level.playSound(null, b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.6f,
                g.status() == ChessGame.Status.DRAW ? 0.8f : 1.2f);
        if (g.reason() != ChessGame.Reason.CHECKMATE) return;
        boolean whiteWon = g.status() == ChessGame.Status.WHITE_WON;
        ChessBlockEntity.Seat winner = be.seat(whiteWon), loser = be.seat(!whiteWon);
        String wn = winner == null ? "?" : winner.name(), ln = loser == null ? "?" : loser.name();
        Component line = Component.translatableWithFallback("aftrhrs.chess.announce.checkmate", "♚ %s checkmated %s in %s moves at a chess table!",
                wn, ln, String.valueOf((g.ply() + 1) / 2)).withStyle(net.minecraft.ChatFormatting.GOLD);
        Vec3 c = Vec3.atCenterOf(b);
        for (ServerPlayer p : level.players()) {
            if (p.position().distanceToSqr(c) <= 32 * 32) p.sendSystemMessage(line);
        }
        if (winner != null) {
            ServerPlayer w = level.getServer().getPlayerList().getPlayer(winner.id());
            if (w != null) plugin.advance(w, "event", "chess-checkmate");
        }
    }
}
