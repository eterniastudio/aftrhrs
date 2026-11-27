package net.axther.aftrhrs.feature.chess;

import java.util.ArrayList;
import java.util.List;

/**
 * One chess position (immutable once built): the 64 squares, side to move, castling rights, the en passant target,
 * and the half-move / full-move counters. Legal moves come from {@link #legalMoves()}; {@link #play} returns the
 * position after a move. Both sides use it: the server decides, the client only highlights.
 *
 * <p>Squares are 0..63, a1 = 0, h1 = 7, a8 = 56. A piece is its type ({@link #PAWN} .. {@link #KING}) plus
 * {@link #BLACK} for black. A move is an int: {@code from | to << 6 | promotionType << 12} (see {@link #move}).
 */
public final class Position {
    public static final int EMPTY = 0, PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6, BLACK = 8;
    public static final int WK = 1, WQ = 2, BK = 4, BQ = 8;
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static final int[] KNIGHT_D = {-17, -15, -10, -6, 6, 10, 15, 17};
    private static final int[] KING_D = {-9, -8, -7, -1, 1, 7, 8, 9};
    private static final int[] BISHOP_D = {-9, -7, 7, 9};
    private static final int[] ROOK_D = {-8, -1, 1, 8};
    /** Castling rights kept when a move touches a square (king and rook home squares clear theirs). */
    private static final int[] CASTLE_MASK = new int[64];

    static {
        java.util.Arrays.fill(CASTLE_MASK, WK | WQ | BK | BQ);
        CASTLE_MASK[0] &= ~WQ;
        CASTLE_MASK[7] &= ~WK;
        CASTLE_MASK[4] &= ~(WK | WQ);
        CASTLE_MASK[56] &= ~BQ;
        CASTLE_MASK[63] &= ~BK;
        CASTLE_MASK[60] &= ~(BK | BQ);
    }

    private final byte[] sq = new byte[64];
    private boolean white = true;
    private int castling;
    private int ep = -1;
    private int halfmove;
    private int fullmove = 1;
    private List<Integer> legal;

    private Position() {
    }

    public static Position start() {
        return fromFen(START_FEN);
    }

    // ------------------------------------------------------------------ moves as ints

    public static int move(int from, int to, int promo) {
        return from | to << 6 | promo << 12;
    }

    public static int from(int m) {
        return m & 63;
    }

    public static int to(int m) {
        return m >> 6 & 63;
    }

    public static int promo(int m) {
        return m >> 12 & 7;
    }

    public static int file(int s) {
        return s & 7;
    }

    public static int rank(int s) {
        return s >> 3;
    }

    public static int type(int piece) {
        return piece & 7;
    }

    public static boolean isBlack(int piece) {
        return (piece & BLACK) != 0;
    }

    public static String squareName(int s) {
        return "" + (char) ('a' + file(s)) + (char) ('1' + rank(s));
    }

    /** a1..h8 to 0..63, or -1. */
    public static int square(String name) {
        if (name == null || name.length() != 2) return -1;
        int f = name.charAt(0) - 'a', r = name.charAt(1) - '1';
        return f < 0 || f > 7 || r < 0 || r > 7 ? -1 : r * 8 + f;
    }

    /** Long algebraic ("e2e4", "e7e8q"). */
    public static String uci(int m) {
        String s = squareName(from(m)) + squareName(to(m));
        return promo(m) == 0 ? s : s + "nbrq".charAt(promo(m) - KNIGHT);
    }

    // ------------------------------------------------------------------ reading

    public int piece(int s) {
        return sq[s];
    }

    public boolean whiteToMove() {
        return white;
    }

    public int castling() {
        return castling;
    }

    public int epSquare() {
        return ep;
    }

    public int halfmove() {
        return halfmove;
    }

    public int fullmove() {
        return fullmove;
    }

    public int kingSquare(boolean whiteKing) {
        int k = KING | (whiteKing ? 0 : BLACK);
        for (int s = 0; s < 64; s++) if (sq[s] == k) return s;
        return -1;
    }

    public boolean inCheck() {
        int k = kingSquare(white);
        return k >= 0 && attacked(k, !white);
    }

    /** Whether a side ({@code byWhite}) attacks square {@code s}. */
    public boolean attacked(int s, boolean byWhite) {
        int side = byWhite ? 0 : BLACK;
        int f = file(s), r = rank(s);
        // pawns: a white pawn attacks up, so it sits one rank below
        int pr = byWhite ? r - 1 : r + 1;
        if (pr >= 0 && pr < 8) {
            for (int df = -1; df <= 1; df += 2) {
                int pf = f + df;
                if (pf >= 0 && pf < 8 && sq[pr * 8 + pf] == (PAWN | side)) return true;
            }
        }
        for (int d : KNIGHT_D) {
            int t = s + d;
            if (t >= 0 && t < 64 && Math.abs(file(t) - f) <= 2 && sq[t] == (KNIGHT | side)) return true;
        }
        for (int d : KING_D) {
            int t = s + d;
            if (t >= 0 && t < 64 && Math.abs(file(t) - f) <= 1 && sq[t] == (KING | side)) return true;
        }
        if (slides(s, BISHOP_D, BISHOP | side, QUEEN | side)) return true;
        return slides(s, ROOK_D, ROOK | side, QUEEN | side);
    }

    private boolean slides(int s, int[] dirs, int a, int b) {
        for (int d : dirs) {
            int t = s;
            while (true) {
                int pf = file(t);
                t += d;
                if (t < 0 || t >= 64 || Math.abs(file(t) - pf) > 1) break;
                int p = sq[t];
                if (p == EMPTY) continue;
                if (p == a || p == b) return true;
                break;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ move generation

    /** Every legal move (cached). */
    public List<Integer> legalMoves() {
        if (legal == null) {
            List<Integer> out = new ArrayList<>();
            for (int m : pseudoMoves()) {
                Position next = apply(m);
                int k = next.kingSquare(white);
                if (k >= 0 && !next.attacked(k, !white)) out.add(m);
            }
            legal = java.util.Collections.unmodifiableList(out);
        }
        return legal;
    }

    /** The legal move from {@code from} to {@code to} (with this promotion when it promotes), or -1. */
    public int find(int from, int to, int promo) {
        for (int m : legalMoves()) {
            if (from(m) == from && to(m) == to && (promo(m) == 0 || promo(m) == promo)) return m;
        }
        return -1;
    }

    /** A legal move in long algebraic ("e2e4", "e7e8q"), or -1. */
    public int parseUci(String s) {
        if (s == null || s.length() < 4 || s.length() > 5) return -1;
        int from = square(s.substring(0, 2)), to = square(s.substring(2, 4));
        if (from < 0 || to < 0) return -1;
        int promo = 0;
        if (s.length() == 5) {
            int i = "nbrq".indexOf(Character.toLowerCase(s.charAt(4)));
            if (i < 0) return -1;
            promo = KNIGHT + i;
        }
        boolean promotes = type(sq[from]) == PAWN && (rank(to) == 7 || rank(to) == 0);
        if (promotes != (promo != 0)) return -1;
        return find(from, to, promo);
    }

    private List<Integer> pseudoMoves() {
        List<Integer> out = new ArrayList<>(48);
        int side = white ? 0 : BLACK;
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == EMPTY || (p & BLACK) != side) continue;
            switch (type(p)) {
                case PAWN -> pawn(s, out);
                case KNIGHT -> steps(s, KNIGHT_D, 2, out);
                case BISHOP -> rays(s, BISHOP_D, out);
                case ROOK -> rays(s, ROOK_D, out);
                case QUEEN -> {
                    rays(s, BISHOP_D, out);
                    rays(s, ROOK_D, out);
                }
                case KING -> {
                    steps(s, KING_D, 1, out);
                    castles(s, out);
                }
                default -> {
                }
            }
        }
        return out;
    }

    private boolean own(int p) {
        return p != EMPTY && isBlack(p) != white;
    }

    private boolean enemy(int p) {
        return p != EMPTY && isBlack(p) == white;
    }

    private void pawn(int s, List<Integer> out) {
        int dir = white ? 8 : -8;
        int startRank = white ? 1 : 6, lastRank = white ? 7 : 0;
        int f = file(s);
        int one = s + dir;
        if (one >= 0 && one < 64 && sq[one] == EMPTY) {
            addPawn(s, one, lastRank, out);
            int two = one + dir;
            if (rank(s) == startRank && sq[two] == EMPTY) out.add(move(s, two, 0));
        }
        for (int df = -1; df <= 1; df += 2) {
            if (f + df < 0 || f + df > 7) continue;
            int t = s + dir + df;
            if (t < 0 || t >= 64) continue;
            if (enemy(sq[t])) addPawn(s, t, lastRank, out);
            else if (t == ep && sq[t] == EMPTY) out.add(move(s, t, 0));
        }
    }

    private static void addPawn(int from, int to, int lastRank, List<Integer> out) {
        if (rank(to) == lastRank) {
            for (int pr = QUEEN; pr >= KNIGHT; pr--) out.add(move(from, to, pr));
        } else {
            out.add(move(from, to, 0));
        }
    }

    private void steps(int s, int[] dirs, int maxFile, List<Integer> out) {
        int f = file(s);
        for (int d : dirs) {
            int t = s + d;
            if (t < 0 || t >= 64 || Math.abs(file(t) - f) > maxFile) continue;
            if (!own(sq[t])) out.add(move(s, t, 0));
        }
    }

    private void rays(int s, int[] dirs, List<Integer> out) {
        for (int d : dirs) {
            int t = s;
            while (true) {
                int pf = file(t);
                t += d;
                if (t < 0 || t >= 64 || Math.abs(file(t) - pf) > 1) break;
                if (own(sq[t])) break;
                out.add(move(s, t, 0));
                if (sq[t] != EMPTY) break;
            }
        }
    }

    /**
     * Castling: the right is still there (neither king nor that rook has moved or been captured), the rook stands on
     * its corner, the squares between are empty, the king is not in check and neither crosses nor lands on an attacked
     * square.
     */
    private void castles(int s, List<Integer> out) {
        int home = white ? 4 : 60;
        if (s != home) return;
        int side = white ? 0 : BLACK;
        boolean enemyWhite = !white;
        int kRight = white ? WK : BK, qRight = white ? WQ : BQ;
        if ((castling & (kRight | qRight)) == 0 || attacked(s, enemyWhite)) return;
        if ((castling & kRight) != 0 && sq[home + 3] == (ROOK | side) && sq[home + 1] == EMPTY && sq[home + 2] == EMPTY
                && !attacked(home + 1, enemyWhite) && !attacked(home + 2, enemyWhite)) {
            out.add(move(s, home + 2, 0));
        }
        if ((castling & qRight) != 0 && sq[home - 4] == (ROOK | side) && sq[home - 1] == EMPTY && sq[home - 2] == EMPTY
                && sq[home - 3] == EMPTY && !attacked(home - 1, enemyWhite) && !attacked(home - 2, enemyWhite)) {
            out.add(move(s, home - 2, 0));
        }
    }

    // ------------------------------------------------------------------ playing

    /** The position after a legal move ({@link IllegalArgumentException} for anything else). */
    public Position play(int m) {
        if (!legalMoves().contains(m)) throw new IllegalArgumentException("illegal move " + uci(m));
        return apply(m);
    }

    /** Whether the move takes a piece (en passant included). */
    public boolean isCapture(int m) {
        return sq[to(m)] != EMPTY || isEnPassant(m);
    }

    public boolean isEnPassant(int m) {
        return type(sq[from(m)]) == PAWN && to(m) == ep && file(from(m)) != file(to(m)) && sq[to(m)] == EMPTY;
    }

    public boolean isCastle(int m) {
        return type(sq[from(m)]) == KING && Math.abs(file(to(m)) - file(from(m))) == 2;
    }

    /** The piece a move takes (en passant: the pawn beside), or EMPTY. */
    public int captured(int m) {
        if (isEnPassant(m)) return PAWN | (white ? BLACK : 0);
        return sq[to(m)];
    }

    private Position apply(int m) {
        int from = from(m), to = to(m), pr = promo(m);
        Position n = copy();
        int p = sq[from];
        int t = type(p);
        boolean capture = sq[to] != EMPTY;
        n.sq[from] = EMPTY;
        n.sq[to] = (byte) (pr != 0 ? pr | (p & BLACK) : p);
        if (t == PAWN && to == ep && file(from) != file(to) && sq[to] == EMPTY) {
            n.sq[to + (white ? -8 : 8)] = EMPTY;
            capture = true;
        }
        if (t == KING && Math.abs(file(to) - file(from)) == 2) {
            boolean king = to > from;
            int rf = king ? from + 3 : from - 4, rt = king ? from + 1 : from - 1;
            n.sq[rt] = n.sq[rf];
            n.sq[rf] = EMPTY;
        }
        n.ep = t == PAWN && Math.abs(to - from) == 16 ? (from + to) / 2 : -1;
        n.castling = castling & CASTLE_MASK[from] & CASTLE_MASK[to];
        n.halfmove = t == PAWN || capture ? 0 : halfmove + 1;
        n.fullmove = white ? fullmove : fullmove + 1;
        n.white = !white;
        return n;
    }

    private Position copy() {
        Position n = new Position();
        System.arraycopy(sq, 0, n.sq, 0, 64);
        n.white = white;
        n.castling = castling;
        n.ep = ep;
        n.halfmove = halfmove;
        n.fullmove = fullmove;
        return n;
    }

    // ------------------------------------------------------------------ notation

    /** Standard algebraic notation of a legal move ("Nbd7", "exd6", "O-O", "e8=Q#"). */
    public String san(int m) {
        int from = from(m), to = to(m);
        int t = type(sq[from]);
        StringBuilder b = new StringBuilder();
        if (isCastle(m)) {
            b.append(to > from ? "O-O" : "O-O-O");
        } else if (t == PAWN) {
            if (isCapture(m)) b.append((char) ('a' + file(from))).append('x');
            b.append(squareName(to));
            if (promo(m) != 0) b.append('=').append(" NBRQ".charAt(promo(m) - 1));
        } else {
            b.append(" NBRQK".charAt(t - 1));
            boolean sameFile = false, sameRank = false, other = false;
            for (int o : legalMoves()) {
                if (o == m || to(o) != to || from(o) == from || type(sq[from(o)]) != t) continue;
                other = true;
                if (file(from(o)) == file(from)) sameFile = true;
                if (rank(from(o)) == rank(from)) sameRank = true;
            }
            if (other) {
                if (!sameFile) b.append((char) ('a' + file(from)));
                else if (!sameRank) b.append((char) ('1' + rank(from)));
                else b.append(squareName(from));
            }
            if (isCapture(m)) b.append('x');
            b.append(squareName(to));
        }
        Position next = apply(m);
        if (next.inCheck()) b.append(next.legalMoves().isEmpty() ? '#' : '+');
        return b.toString();
    }

    // ------------------------------------------------------------------ draws

    /**
     * Neither side can ever mate: kings alone, a king and one bishop or knight against a king, or only bishops that
     * all stand on squares of one color.
     */
    public boolean insufficientMaterial() {
        int minors = 0, knights = 0;
        int bishopColors = 0; // bit 1: light-square bishop, bit 2: dark-square bishop
        for (int s = 0; s < 64; s++) {
            int t = type(sq[s]);
            switch (t) {
                case PAWN, ROOK, QUEEN -> {
                    return false;
                }
                case KNIGHT -> {
                    minors++;
                    knights++;
                }
                case BISHOP -> {
                    minors++;
                    bishopColors |= (file(s) + rank(s)) % 2 == 0 ? 2 : 1;
                }
                default -> {
                }
            }
        }
        if (minors <= 1) return true;
        return knights == 0 && bishopColors != 3;
    }

    /** Whether a side has nothing but its king. */
    public boolean bareKing(boolean whiteSide) {
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p != EMPTY && isBlack(p) != whiteSide && type(p) != KING) return false;
        }
        return true;
    }

    /**
     * What makes two positions "the same" for threefold repetition: placement, side to move, castling rights, and the
     * en passant square only when an en passant capture is actually legal.
     */
    public String repetitionKey() {
        String fen = fen();
        String[] f = fen.split(" ");
        boolean epLegal = false;
        if (ep >= 0) {
            for (int m : legalMoves()) {
                if (isEnPassant(m)) {
                    epLegal = true;
                    break;
                }
            }
        }
        return f[0] + ' ' + f[1] + ' ' + f[2] + ' ' + (epLegal ? f[3] : "-");
    }

    // ------------------------------------------------------------------ FEN

    public static Position fromFen(String fen) {
        String[] f = fen.trim().split("\\s+");
        if (f.length < 4) throw new IllegalArgumentException("bad FEN: " + fen);
        Position p = new Position();
        String[] rows = f[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("bad FEN board: " + fen);
        for (int i = 0; i < 8; i++) {
            int r = 7 - i, c = 0;
            for (char ch : rows[i].toCharArray()) {
                if (Character.isDigit(ch)) {
                    c += ch - '0';
                    continue;
                }
                int idx = "pnbrqk".indexOf(Character.toLowerCase(ch));
                if (idx < 0 || c > 7) throw new IllegalArgumentException("bad FEN piece: " + fen);
                p.sq[r * 8 + c] = (byte) ((idx + 1) | (Character.isLowerCase(ch) ? BLACK : 0));
                c++;
            }
            if (c != 8) throw new IllegalArgumentException("bad FEN row: " + fen);
        }
        p.white = !f[1].equals("b");
        p.castling = 0;
        if (f[2].indexOf('K') >= 0) p.castling |= WK;
        if (f[2].indexOf('Q') >= 0) p.castling |= WQ;
        if (f[2].indexOf('k') >= 0) p.castling |= BK;
        if (f[2].indexOf('q') >= 0) p.castling |= BQ;
        p.ep = f[3].equals("-") ? -1 : square(f[3]);
        p.halfmove = f.length > 4 ? Integer.parseInt(f[4]) : 0;
        p.fullmove = f.length > 5 ? Math.max(1, Integer.parseInt(f[5])) : 1;
        if (p.kingSquare(true) < 0 || p.kingSquare(false) < 0) throw new IllegalArgumentException("FEN without both kings: " + fen);
        return p;
    }

    /** The FEN as this class writes it (normalized), or null when it can't be read. */
    public static String fromFenSafe(String fen) {
        try {
            return fromFen(fen == null || fen.isBlank() ? START_FEN : fen).fen();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String fen() {
        StringBuilder b = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int c = 0; c < 8; c++) {
                int p = sq[r * 8 + c];
                if (p == EMPTY) {
                    empty++;
                    continue;
                }
                if (empty > 0) b.append(empty);
                empty = 0;
                char ch = "pnbrqk".charAt(type(p) - 1);
                b.append(isBlack(p) ? ch : Character.toUpperCase(ch));
            }
            if (empty > 0) b.append(empty);
            if (r > 0) b.append('/');
        }
        b.append(white ? " w " : " b ");
        String c = ((castling & WK) != 0 ? "K" : "") + ((castling & WQ) != 0 ? "Q" : "") + ((castling & BK) != 0 ? "k" : "")
                + ((castling & BQ) != 0 ? "q" : "");
        b.append(c.isEmpty() ? "-" : c).append(' ').append(ep < 0 ? "-" : squareName(ep));
        b.append(' ').append(halfmove).append(' ').append(fullmove);
        return b.toString();
    }

    /** Leaf nodes at a depth (move generator self-test). */
    public long perft(int depth) {
        if (depth <= 0) return 1;
        List<Integer> moves = legalMoves();
        if (depth == 1) return moves.size();
        long n = 0;
        for (int m : moves) n += apply(m).perft(depth - 1);
        return n;
    }

    @Override
    public String toString() {
        return fen();
    }

    /** One letter per piece, FEN style ('P', 'n'...), '.' for none. */
    public static char letter(int piece) {
        if (piece == EMPTY) return '.';
        char ch = "pnbrqk".charAt(type(piece) - 1);
        return isBlack(piece) ? ch : Character.toUpperCase(ch);
    }
}
