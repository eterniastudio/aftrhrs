package net.axther.aftrhrs.feature.chess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A game of chess: the start position, every move played (with its notation and what it took), and how it ended.
 * {@link #play} only takes legal moves and ends the game by itself on checkmate, stalemate, insufficient material,
 * the fifty-move rule (100 half-moves without a capture or pawn move) and threefold repetition; resigning, a draw by
 * agreement, a flag fall and an abandoned seat are decided by the table ({@link ChessBlockEntity}).
 */
public final class ChessGame {
    public enum Status {
        PLAYING, WHITE_WON, BLACK_WON, DRAW;

        public boolean over() {
            return this != PLAYING;
        }
    }

    public enum Reason {
        NONE, CHECKMATE, RESIGNATION, TIMEOUT, ABANDONED, STALEMATE, REPETITION, FIFTY_MOVES, INSUFFICIENT, AGREEMENT,
        /** The flag fell, but the other side has only its king: a draw. */
        TIMEOUT_VS_INSUFFICIENT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String startFen;
    private final List<Position> positions = new ArrayList<>();
    private final List<Integer> moves = new ArrayList<>();
    private final List<String> san = new ArrayList<>();
    /** The piece each move took (EMPTY for none). */
    private final List<Integer> taken = new ArrayList<>();
    private final Map<String, Integer> seen = new HashMap<>();
    private Status status = Status.PLAYING;
    private Reason reason = Reason.NONE;

    public ChessGame() {
        this(Position.START_FEN);
    }

    public ChessGame(String fen) {
        Position p = Position.fromFen(fen);
        this.startFen = p.fen();
        positions.add(p);
        seen.merge(p.repetitionKey(), 1, Integer::sum);
        autoEnd();
    }

    /**
     * A saved game: the moves are played again from the start (stops at the first one that isn't legal), then the
     * saved result is put back (a resignation or a draw offer can't be worked out from the moves).
     */
    public static ChessGame load(String fen, List<String> uciMoves, Status status, Reason reason) {
        ChessGame g;
        try {
            g = new ChessGame(fen == null || fen.isBlank() ? Position.START_FEN : fen);
        } catch (IllegalArgumentException e) {
            g = new ChessGame();
        }
        for (String u : uciMoves) {
            if (g.status.over()) break;
            int m = g.position().parseUci(u);
            if (m < 0) break;
            g.play(m);
        }
        if (status != null && status.over() && !g.status.over()) g.end(status, reason == null ? Reason.NONE : reason);
        return g;
    }

    /**
     * {@link #load} for a list that starts with this game's moves: only the rest is played (a client update after one
     * more move). Returns this game.
     */
    public ChessGame extend(List<String> uciMoves, Status status, Reason reason) {
        for (int i = ply(); i < uciMoves.size() && !this.status.over(); i++) {
            int m = position().parseUci(uciMoves.get(i));
            if (m < 0) break;
            play(m);
        }
        if (status != null && status.over() && !this.status.over()) end(status, reason == null ? Reason.NONE : reason);
        return this;
    }

    public String startFen() {
        return startFen;
    }

    public Position position() {
        return positions.getLast();
    }

    /** The position before ply {@code i} (0 = start); {@code i == ply()} is the current one. */
    public Position positionAt(int i) {
        return positions.get(i);
    }

    public int ply() {
        return moves.size();
    }

    public List<Integer> moves() {
        return Collections.unmodifiableList(moves);
    }

    public List<String> uciMoves() {
        List<String> out = new ArrayList<>(moves.size());
        for (int m : moves) out.add(Position.uci(m));
        return out;
    }

    public List<String> sanMoves() {
        return Collections.unmodifiableList(san);
    }

    /** What move {@code i} took, or EMPTY. */
    public int taken(int i) {
        return taken.get(i);
    }

    /** The last move, or -1 before the first. */
    public int lastMove() {
        return moves.isEmpty() ? -1 : moves.getLast();
    }

    public Status status() {
        return status;
    }

    public Reason reason() {
        return reason;
    }

    public boolean over() {
        return status.over();
    }

    /** Pieces of one color that have been taken (in the order they fell). */
    public List<Integer> capturedPieces(boolean whitePieces) {
        List<Integer> out = new ArrayList<>();
        for (int p : taken) if (p != Position.EMPTY && Position.isBlack(p) != whitePieces) out.add(p);
        return out;
    }

    /** How often the current position has stood on the board (threefold repetition at 3). */
    public int repetitions() {
        return seen.getOrDefault(position().repetitionKey(), 0);
    }

    /** Plays a legal move for the side to move; false (nothing changes) when it isn't legal or the game is over. */
    public boolean play(int m) {
        if (status.over()) return false;
        Position p = position();
        if (!p.legalMoves().contains(m)) return false;
        san.add(p.san(m));
        taken.add(p.captured(m));
        Position next = p.play(m);
        moves.add(m);
        positions.add(next);
        seen.merge(next.repetitionKey(), 1, Integer::sum);
        autoEnd();
        return true;
    }

    /** Ends the game (does nothing once it is over). */
    public void end(Status s, Reason r) {
        if (status.over() || s == null || !s.over()) return;
        status = s;
        reason = r;
    }

    public void resign(boolean white) {
        end(white ? Status.BLACK_WON : Status.WHITE_WON, Reason.RESIGNATION);
    }

    /** The side ({@code white}) ran out of time: it loses, unless the other side has nothing but its king. */
    public void flag(boolean white) {
        if (position().bareKing(!white)) end(Status.DRAW, Reason.TIMEOUT_VS_INSUFFICIENT);
        else end(white ? Status.BLACK_WON : Status.WHITE_WON, Reason.TIMEOUT);
    }

    private void autoEnd() {
        Position p = position();
        if (p.legalMoves().isEmpty()) {
            if (p.inCheck()) end(p.whiteToMove() ? Status.BLACK_WON : Status.WHITE_WON, Reason.CHECKMATE);
            else end(Status.DRAW, Reason.STALEMATE);
        } else if (p.insufficientMaterial()) {
            end(Status.DRAW, Reason.INSUFFICIENT);
        } else if (p.halfmove() >= 100) {
            end(Status.DRAW, Reason.FIFTY_MOVES);
        } else if (repetitions() >= 3) {
            end(Status.DRAW, Reason.REPETITION);
        }
    }
}
