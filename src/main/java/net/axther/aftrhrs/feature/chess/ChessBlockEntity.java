package net.axther.aftrhrs.feature.chess;

import com.mojang.serialization.Codec;
import net.axther.aftrhrs.feature.furniture.FurnitureBE;
import net.axther.aftrhrs.feature.furniture.FurnitureEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The game on a chess table (docs/chess.md): the board and its moves ({@link ChessGame}), who sits at White and
 * Black, a standing draw offer and the clock. The server decides everything here; each action returns null when it
 * happened or an error key ({@code aftrhrs.chess.err.<key>}) when it didn't. Changes are saved with the block and sent
 * to every client that sees the table (its renderer draws the position, the chess screen reads it).
 *
 * <p>Seats: the first player to sit plays White, the next Black (a player can't take both). Before the first move a
 * player may get up freely; once the game runs, getting up is resigning. A seated player who is away (logged off, in
 * another world or more than {@link #PRESENT_RANGE} blocks off) keeps the seat for {@link #AWAY_TICKS} (walked off) or
 * {@link #OFFLINE_TICKS} (logged off) while the table is loaded; after that a running game is lost by forfeit (or,
 * when both are gone, the board is reset and both seats freed), and otherwise the seat is just freed.
 */
public class ChessBlockEntity extends FurnitureBE {
    /** How close a seated player must stay (blocks from the table) to count as present. */
    public static final double PRESENT_RANGE = 12;
    /** How close a player must be to act at the table (the screen closes farther away). */
    public static final double REACH = 8;
    public static final int AWAY_TICKS = 60 * 20;
    public static final int OFFLINE_TICKS = 3 * 60 * 20;
    /** Time controls (minutes + increment seconds) offered before the first move; index 0 is no clock. */
    public static final int[][] TIME_CONTROLS = {{0, 0}, {1, 0}, {3, 2}, {5, 0}, {10, 0}, {15, 10}};
    /** Tells the client side whether its own chess screen shows this table (to skip the in-world move sound). */
    public static volatile Predicate<BlockPos> screenOpen = p -> false;

    /** One seat: the player's id and the name shown on the board. */
    public record Seat(UUID id, String name) {
    }

    private ChessGame game = new ChessGame();
    private int gameId;
    private @Nullable Seat white, black;
    /** 0 none, 1 White offers a draw, 2 Black does. */
    private int drawOffer;
    private int timeControl;
    private long whiteMs, blackMs;
    // Absence (not saved: a table that loads again gives everyone a fresh grace period). Sent to clients for the countdown.
    private int whiteAway, blackAway;
    private boolean whiteOffline, blackOffline;
    private int ticks;
    // client
    private int seenPly = -1;

    public ChessBlockEntity(BlockPos pos, BlockState state) {
        super(FurnitureEntities.CHESS, pos, state);
    }

    // ------------------------------------------------------------------ reading

    public ChessGame game() {
        return game;
    }

    public int gameId() {
        return gameId;
    }

    public @Nullable Seat seat(boolean whiteSeat) {
        return whiteSeat ? white : black;
    }

    public int drawOffer() {
        return drawOffer;
    }

    public int timeControl() {
        return timeControl;
    }

    public boolean clockOn() {
        return timeControl > 0;
    }

    /** Milliseconds left on a side's clock. */
    public long clockMs(boolean whiteSide) {
        return whiteSide ? whiteMs : blackMs;
    }

    /** Whether the clock is running (a clock was chosen, White has moved, the game goes on). */
    public boolean clockRunning() {
        return clockOn() && game.ply() > 0 && !game.over();
    }

    /** Ticks a seated player has been away (0 when present). */
    public int awayTicks(boolean whiteSide) {
        return whiteSide ? whiteAway : blackAway;
    }

    public boolean offline(boolean whiteSide) {
        return whiteSide ? whiteOffline : blackOffline;
    }

    /** How long an away player keeps the seat. */
    public int awayLimit(boolean whiteSide) {
        return offline(whiteSide) ? OFFLINE_TICKS : AWAY_TICKS;
    }

    /** The color a player sits at: TRUE white, FALSE black, null when watching. */
    public @Nullable Boolean colorOf(UUID player) {
        if (white != null && white.id().equals(player)) return Boolean.TRUE;
        if (black != null && black.id().equals(player)) return Boolean.FALSE;
        return null;
    }

    /** The game has begun (at least one move) and is not over: getting up now resigns. */
    public boolean running() {
        return game.ply() > 0 && !game.over();
    }

    // ------------------------------------------------------------------ actions (server)

    public @Nullable String sit(ServerPlayer p, boolean whiteSeat) {
        if (colorOf(p.getUUID()) != null) return "already-seated";
        Seat s = seat(whiteSeat);
        if (s != null) return "seat-taken";
        if (game.over()) resetBoard(); // a finished game: whoever sits down starts a fresh one
        Seat mine = new Seat(p.getUUID(), p.getScoreboardName());
        if (whiteSeat) white = mine;
        else black = mine;
        clearAway(whiteSeat);
        sync();
        return null;
    }

    /** Gets up. In a running game that is resigning. */
    public @Nullable String leave(ServerPlayer p) {
        Boolean c = colorOf(p.getUUID());
        if (c == null) return "not-seated";
        if (running()) {
            game.resign(c);
            finished();
        }
        free(c);
        if (white == null && black == null && !running()) resetBoard();
        sync();
        return null;
    }

    public @Nullable String move(ServerPlayer p, String uci, int forGame) {
        Boolean c = colorOf(p.getUUID());
        if (c == null) return "not-seated";
        if (forGame != gameId) return "stale";
        if (game.over()) return "game-over";
        if (white == null || black == null) return "need-opponent";
        Position pos = game.position();
        if (pos.whiteToMove() != c) return "not-your-turn";
        int m = pos.parseUci(uci == null ? "" : uci.toLowerCase(Locale.ROOT));
        if (m < 0) return "illegal";
        boolean first = game.ply() == 0;
        game.play(m);
        // the side that just got the offer answered it by moving on
        if (drawOffer == (c ? 2 : 1)) drawOffer = 0;
        if (clockOn() && !first) {
            int inc = TIME_CONTROLS[timeControl][1] * 1000;
            if (c) whiteMs += inc;
            else blackMs += inc;
        }
        if (game.over()) finished();
        sync();
        return null;
    }

    public @Nullable String resign(ServerPlayer p) {
        Boolean c = colorOf(p.getUUID());
        if (c == null) return "not-seated";
        if (game.over()) return "game-over";
        if (game.ply() == 0) return "not-started";
        game.resign(c);
        finished();
        sync();
        return null;
    }

    /** Offers a draw, or takes the opponent's standing offer. */
    public @Nullable String offerDraw(ServerPlayer p) {
        Boolean c = colorOf(p.getUUID());
        if (c == null) return "not-seated";
        if (game.over()) return "game-over";
        if (game.ply() == 0) return "not-started";
        if (white == null || black == null) return "need-opponent";
        int theirs = c ? 2 : 1, mine = c ? 1 : 2;
        if (drawOffer == theirs) {
            drawOffer = 0;
            game.end(ChessGame.Status.DRAW, ChessGame.Reason.AGREEMENT);
            finished();
        } else if (drawOffer == mine) {
            return "draw-pending";
        } else {
            drawOffer = mine;
        }
        sync();
        return null;
    }

    public @Nullable String declineDraw(ServerPlayer p) {
        Boolean c = colorOf(p.getUUID());
        if (c == null) return "not-seated";
        if (drawOffer != (c ? 2 : 1)) return "no-offer";
        drawOffer = 0;
        sync();
        return null;
    }

    /** A fresh board once the game is over (same seats, same time control). */
    public @Nullable String newGame(ServerPlayer p) {
        if (colorOf(p.getUUID()) == null) return "not-seated";
        if (running()) return "running";
        resetBoard();
        sync();
        return null;
    }

    /** Picks a time control (index into {@link #TIME_CONTROLS}) before the first move. */
    public @Nullable String setTimeControl(ServerPlayer p, int index) {
        if (colorOf(p.getUUID()) == null) return "not-seated";
        if (index < 0 || index >= TIME_CONTROLS.length) return "illegal";
        if (game.ply() > 0 && !game.over()) return "running";
        if (game.over()) resetBoard();
        timeControl = index;
        resetClock();
        sync();
        return null;
    }

    // ------------------------------------------------------------------ internals

    private void free(boolean whiteSeat) {
        if (whiteSeat) white = null;
        else black = null;
        clearAway(whiteSeat);
        if (drawOffer == (whiteSeat ? 1 : 2)) drawOffer = 0;
    }

    private void clearAway(boolean whiteSeat) {
        if (whiteSeat) {
            whiteAway = 0;
            whiteOffline = false;
        } else {
            blackAway = 0;
            blackOffline = false;
        }
    }

    private void resetBoard() {
        game = new ChessGame();
        gameId++;
        drawOffer = 0;
        resetClock();
    }

    private void resetClock() {
        long ms = TIME_CONTROLS[timeControl][0] * 60_000L;
        whiteMs = ms;
        blackMs = ms;
    }

    /** The game just ended: announcements, advancements (the module, while it runs). */
    private void finished() {
        drawOffer = 0;
        ChessModule m = ChessModule.active();
        if (m != null && level instanceof ServerLevel sl) m.ended(sl, this);
    }

    @Override
    public void serverTick() {
        if (!(level instanceof ServerLevel sl)) return;
        ticks++;
        boolean changed = false;
        if (clockRunning()) {
            boolean w = game.position().whiteToMove();
            long left = Math.max(0, clockMs(w) - 50);
            if (w) whiteMs = left;
            else blackMs = left;
            setChanged();
            if (left == 0) {
                game.flag(w);
                finished();
                changed = true;
            } else if (ticks % 20 == 0) {
                changed = true; // keep the clients' clocks honest once a second
            }
        }
        if (ticks % 10 == 0) changed |= checkSeats(sl);
        if (changed) sync();
    }

    /** Away timers for both seats; forfeits / frees seats past the limit. True when something visible changed. */
    private boolean checkSeats(ServerLevel sl) {
        boolean changed = false;
        boolean[] gone = new boolean[2];
        for (int i = 0; i < 2; i++) {
            boolean w = i == 0;
            Seat s = seat(w);
            if (s == null) continue;
            ServerPlayer p = sl.getServer().getPlayerList().getPlayer(s.id());
            boolean off = p == null;
            boolean present = !off && p.level() == sl && p.position().distanceToSqr(Vec3.atCenterOf(worldPosition)) <= PRESENT_RANGE * PRESENT_RANGE;
            int away = awayTicks(w);
            if (present) {
                if (away > 0) {
                    clearAway(w);
                    changed = true;
                }
                continue;
            }
            if (away == 0 || off != offline(w)) changed = true;
            if (w) {
                whiteAway += 10;
                whiteOffline = off;
            } else {
                blackAway += 10;
                blackOffline = off;
            }
            if (awayTicks(w) >= awayLimit(w)) gone[i] = true;
        }
        if (!gone[0] && !gone[1]) return changed;
        if (running()) {
            for (int i = 0; i < 2; i++) {
                if (!gone[i]) continue;
                boolean loser = i == 0;
                if (seat(!loser) != null && awayTicks(!loser) == 0) {
                    // the one who stayed wins by forfeit; the empty seat is free again
                    game.end(loser ? ChessGame.Status.BLACK_WON : ChessGame.Status.WHITE_WON, ChessGame.Reason.ABANDONED);
                    finished();
                    free(loser);
                    return true;
                }
            }
            // The other player is away too: wait while their seat is still held, start over once both are gone.
            boolean bothGone = (gone[0] || white == null) && (gone[1] || black == null);
            if (!bothGone) return changed;
            white = null;
            black = null;
            clearAway(true);
            clearAway(false);
            resetBoard();
        } else {
            if (gone[0]) free(true);
            if (gone[1]) free(false);
            if (white == null && black == null && game.over()) resetBoard();
        }
        return true;
    }

    @Override
    public void clientTick() {
        if (level == null) return;
        if (clockRunning()) {
            // count down locally between the server's once-a-second updates
            if (game.position().whiteToMove()) whiteMs = Math.max(0, whiteMs - 50);
            else blackMs = Math.max(0, blackMs - 50);
        }
        if (whiteAway > 0) whiteAway++;
        if (blackAway > 0) blackAway++;
        int ply = game.ply();
        if (seenPly >= 0 && ply > seenPly && !screenOpen.test(worldPosition)) {
            boolean capture = game.taken(ply - 1) != Position.EMPTY;
            level.playLocalSound(worldPosition, capture ? net.minecraft.sounds.SoundEvents.WOOD_BREAK : net.minecraft.sounds.SoundEvents.WOOD_PLACE,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.35f, capture ? 1.4f : 1.8f, false);
        }
        seenPly = ply;
    }

    // ------------------------------------------------------------------ saving / syncing

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        String fen = in.getStringOr("Fen", Position.START_FEN);
        List<String> moves = in.read("Moves", Codec.STRING.listOf()).orElse(List.of());
        ChessGame.Status st = parse(ChessGame.Status.class, in.getStringOr("Status", "PLAYING"), ChessGame.Status.PLAYING);
        ChessGame.Reason re = parse(ChessGame.Reason.class, in.getStringOr("Reason", "NONE"), ChessGame.Reason.NONE);
        game = sameGameMore(fen, moves) ? game.extend(moves, st, re) : ChessGame.load(fen, moves, st, re);
        gameId = in.getIntOr("GameId", 0);
        white = in.read("White", UUIDUtil.LENIENT_CODEC).map(id -> new Seat(id, in.getStringOr("WhiteName", "?"))).orElse(null);
        black = in.read("Black", UUIDUtil.LENIENT_CODEC).map(id -> new Seat(id, in.getStringOr("BlackName", "?"))).orElse(null);
        drawOffer = Math.clamp(in.getIntOr("DrawOffer", 0), 0, 2);
        timeControl = Math.clamp(in.getIntOr("TimeControl", 0), 0, TIME_CONTROLS.length - 1);
        long full = TIME_CONTROLS[timeControl][0] * 60_000L;
        whiteMs = Math.max(0, in.getLongOr("WhiteMs", full));
        blackMs = Math.max(0, in.getLongOr("BlackMs", full));
        // only in updates for clients (a table read from disk starts with nobody away)
        whiteAway = in.getIntOr("WhiteAway", 0);
        blackAway = in.getIntOr("BlackAway", 0);
        whiteOffline = in.getBooleanOr("WhiteOff", false);
        blackOffline = in.getBooleanOr("BlackOff", false);
    }

    /**
     * Whether a client update only adds moves to the game shown now (the usual case: one move, or just the clocks), so
     * only the new moves are played instead of the whole game again (same result as replaying it all).
     */
    private boolean sameGameMore(String fen, List<String> moves) {
        if (game == null || game.over() || moves.size() < game.ply() || !game.startFen().equals(Position.fromFenSafe(fen))) return false;
        List<String> have = game.uciMoves();
        return moves.subList(0, have.size()).equals(have);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (!game.startFen().equals(Position.START_FEN)) out.putString("Fen", game.startFen());
        if (game.ply() > 0) out.store("Moves", Codec.STRING.listOf(), game.uciMoves());
        if (game.over()) {
            out.putString("Status", game.status().name());
            out.putString("Reason", game.reason().name());
        }
        if (gameId != 0) out.putInt("GameId", gameId);
        if (white != null) {
            out.store("White", UUIDUtil.CODEC, white.id());
            out.putString("WhiteName", white.name());
        }
        if (black != null) {
            out.store("Black", UUIDUtil.CODEC, black.id());
            out.putString("BlackName", black.name());
        }
        if (drawOffer != 0) out.putInt("DrawOffer", drawOffer);
        if (timeControl != 0) {
            out.putInt("TimeControl", timeControl);
            out.putLong("WhiteMs", whiteMs);
            out.putLong("BlackMs", blackMs);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        if (whiteAway > 0) t.putInt("WhiteAway", whiteAway);
        if (blackAway > 0) t.putInt("BlackAway", blackAway);
        if (whiteOffline) t.putBoolean("WhiteOff", true);
        if (blackOffline) t.putBoolean("BlackOff", true);
        return t;
    }

    /** Test aid: puts a seat's away timer past its limit so the next check applies the rules. */
    public void forceAway(boolean whiteSeat, boolean loggedOff) {
        if (whiteSeat) {
            whiteOffline = loggedOff;
            whiteAway = awayLimit(true);
        } else {
            blackOffline = loggedOff;
            blackAway = awayLimit(false);
        }
    }
}
