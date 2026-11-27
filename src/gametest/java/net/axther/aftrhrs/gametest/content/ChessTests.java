package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.feature.chess.ChessBlockEntity;
import net.axther.aftrhrs.feature.chess.ChessGame;
import net.axther.aftrhrs.feature.chess.Position;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Chess tables (feature/chess): the rules engine (perft against the published counts, castling, en passant,
 * promotion, every way a game ends) and the table itself (seats, turn order, refusals, forfeits, saving).
 */
public class ChessTests {
    static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    static final String POS3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
    static final String POS4 = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";

    /** Plays moves given in long algebraic; a refused one is a failure. */
    static ChessGame play(CT.Fails f, ChessGame g, String... uci) {
        for (String u : uci) {
            int m = g.position().parseUci(u);
            f.check(m >= 0 && g.play(m), "move " + u + " refused in " + g.position().fen());
            if (m < 0) break;
        }
        return g;
    }

    static boolean legal(Position p, String uci) {
        return p.parseUci(uci) >= 0;
    }

    // ------------------------------------------------------------------ engine

    @GameTest(maxTicks = 200)
    public void perftCounts(GameTestHelper h) {
        CT.Fails f = new CT.Fails("chess-perft");
        Position start = Position.start();
        long[] want = {20, 400, 8902};
        for (int d = 1; d <= 3; d++) f.check(start.perft(d) == want[d - 1], "start perft(" + d + ") = " + start.perft(d) + ", want " + want[d - 1]);
        Position kiwi = Position.fromFen(KIWIPETE);
        long[] kw = {48, 2039, 97862};
        for (int d = 1; d <= 3; d++) f.check(kiwi.perft(d) == kw[d - 1], "kiwipete perft(" + d + ") = " + kiwi.perft(d) + ", want " + kw[d - 1]);
        Position p3 = Position.fromFen(POS3);
        long[] w3 = {14, 191, 2812};
        for (int d = 1; d <= 3; d++) f.check(p3.perft(d) == w3[d - 1], "position 3 perft(" + d + ") = " + p3.perft(d) + ", want " + w3[d - 1]);
        Position p4 = Position.fromFen(POS4);
        long[] w4 = {6, 264, 9467};
        for (int d = 1; d <= 3; d++) f.check(p4.perft(d) == w4[d - 1], "position 4 perft(" + d + ") = " + p4.perft(d) + ", want " + w4[d - 1]);
        f.check(Position.fromFen(Position.START_FEN).fen().equals(Position.START_FEN), "start FEN does not round-trip");
        f.check(Position.fromFen(KIWIPETE).fen().equals(KIWIPETE), "Kiwipete FEN does not round-trip");
        f.finish(h);
    }

    @GameTest
    public void castlingRules(GameTestHelper h) {
        CT.Fails f = new CT.Fails("chess-castling");
        String open = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1";
        Position p = Position.fromFen(open);
        f.check(legal(p, "e1g1") && legal(p, "e1c1"), "white can't castle on an open board");
        // the king moves (and comes back): both rights gone
        ChessGame g = play(f, new ChessGame(open), "e1f1", "e8f8", "f1e1", "f8e8");
        f.check(!legal(g.position(), "e1g1") && !legal(g.position(), "e1c1"), "white castled after the king had moved");
        f.check(g.position().castling() == 0, "rights left after both kings moved: " + g.position().fen());
        // a rook moves: only that side's right goes
        g = play(f, new ChessGame(open), "h1h2", "a8a7", "h2h1", "a7a8");
        f.check(!legal(g.position(), "e1g1") && legal(g.position(), "e1c1"), "after Rh1 moved: O-O must be gone, O-O-O kept");
        f.check(legal(g.position(), "e1c1") && (g.position().castling() & Position.BQ) == 0 && (g.position().castling() & Position.BK) != 0,
                "black rights after Ra8 moved: " + g.position().fen());
        // a rook taken on its corner loses the right too
        g = play(f, new ChessGame("r3k2r/8/8/8/8/8/6b1/R3K2R b KQkq - 0 1"), "g2h1");
        f.check((g.position().castling() & Position.WK) == 0, "captured h1 rook kept white's O-O right");
        // through check: f1 attacked by a rook on f8
        Position through = Position.fromFen("4kr2/8/8/8/8/8/8/R3K2R w KQ - 0 1");
        f.check(!legal(through, "e1g1"), "castled through an attacked f1");
        f.check(legal(through, "e1c1"), "O-O-O refused although d1/c1 are safe");
        // into check: g1 attacked
        f.check(!legal(Position.fromFen("4k1r1/8/8/8/8/8/8/R3K2R w KQ - 0 1"), "e1g1"), "castled into check on g1");
        // out of check
        Position checked = Position.fromFen("4r1k1/8/8/8/8/8/8/R3K2R w KQ - 0 1");
        f.check(!legal(checked, "e1g1") && !legal(checked, "e1c1"), "castled out of check");
        // b1 may be attacked on the queen side (only the king's path counts), but must be empty
        f.check(legal(Position.fromFen("1r2k3/8/8/8/8/8/8/R3K3 w Q - 0 1"), "e1c1"), "O-O-O refused because b1 is attacked");
        f.check(!legal(Position.fromFen("4k3/8/8/8/8/8/8/RN2K3 w Q - 0 1"), "e1c1"), "O-O-O over a knight on b1");
        // the rook really goes across
        g = play(f, new ChessGame(open), "e1g1");
        f.check(g.position().piece(5) == Position.ROOK && g.position().piece(7) == Position.EMPTY && g.sanMoves().getFirst().equals("O-O"),
                "after O-O: " + g.position().fen() + " " + g.sanMoves());
        f.finish(h);
    }

    @GameTest
    public void enPassantOnlyAtOnce(GameTestHelper h) {
        CT.Fails f = new CT.Fails("chess-en-passant");
        ChessGame g = play(f, new ChessGame(), "e2e4", "a7a6", "e4e5", "d7d5");
        f.check(legal(g.position(), "e5d6"), "en passant refused right after d7-d5");
        ChessGame taken = play(f, ChessGame.load(Position.START_FEN, g.uciMoves(), null, null), "e5d6");
        f.check(taken.position().piece(Position.square("d5")) == Position.EMPTY && taken.taken(taken.ply() - 1) == (Position.PAWN | Position.BLACK),
                "en passant did not remove the d5 pawn: " + taken.position().fen());
        f.check(taken.sanMoves().getLast().equals("exd6"), "en passant SAN " + taken.sanMoves().getLast());
        // one move later the chance is gone
        play(f, g, "h2h3", "h7h6");
        f.check(!legal(g.position(), "e5d6"), "en passant allowed a move too late");
        // pinned pawn: taking en passant would expose the king along the rank
        Position pin = Position.fromFen("8/8/8/K2pP2r/8/8/8/7k w - d6 0 1");
        f.check(!legal(pin, "e5d6"), "en passant allowed with the king exposed on the 5th rank");
        f.finish(h);
    }

    @GameTest
    public void promotionWithChoice(GameTestHelper h) {
        CT.Fails f = new CT.Fails("chess-promotion");
        Position p = Position.fromFen("1k6/4P3/8/8/8/8/8/4K3 w - - 0 1");
        int promos = 0;
        for (int m : p.legalMoves()) if (Position.from(m) == Position.square("e7")) promos++;
        f.check(promos == 4, "e7-e8 offers " + promos + " moves, want 4 (Q R B N)");
        f.check(p.parseUci("e7e8") < 0, "a promotion without a piece was accepted");
        ChessGame g = play(f, new ChessGame("1k6/4P3/8/8/8/8/8/4K3 w - - 0 1"), "e7e8n");
        f.check(g.position().piece(Position.square("e8")) == Position.KNIGHT, "e8=N left " + g.position().fen());
        g = play(f, new ChessGame("1k6/4P3/8/8/8/8/8/4K3 w - - 0 1"), "e7e8q");
        f.check(g.position().piece(Position.square("e8")) == Position.QUEEN && g.sanMoves().getFirst().equals("e8=Q+"), "e8=Q: " + g.sanMoves());
        // capturing promotion by Black
        g = play(f, new ChessGame("4k3/8/8/8/8/8/1p6/R3K3 b Q - 0 1"), "b2a1r");
        f.check(g.position().piece(0) == (Position.ROOK | Position.BLACK) && g.sanMoves().getFirst().equals("bxa1=R+"), "bxa1=R: " + g.sanMoves());
        f.finish(h);
    }

    @GameTest
    public void endings(GameTestHelper h) {
        CT.Fails f = new CT.Fails("chess-endings");
        // Scholar's mate
        ChessGame g = play(f, new ChessGame(), "e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7");
        f.check(g.status() == ChessGame.Status.WHITE_WON && g.reason() == ChessGame.Reason.CHECKMATE, "Scholar's mate ended " + g.status() + "/" + g.reason());
        f.check(g.sanMoves().getLast().equals("Qxf7#"), "mate SAN " + g.sanMoves().getLast());
        f.check(!g.play(g.position().legalMoves().isEmpty() ? 0 : g.position().legalMoves().getFirst()), "a move after mate was played");
        // stalemate
        g = play(f, new ChessGame("7k/8/6K1/8/8/8/8/5Q2 w - - 0 1"), "f1f7");
        f.check(g.status() == ChessGame.Status.DRAW && g.reason() == ChessGame.Reason.STALEMATE, "stalemate ended " + g.status() + "/" + g.reason());
        // threefold repetition: the start position a third time
        g = play(f, new ChessGame(), "g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1");
        f.check(!g.over(), "drawn before the third repetition");
        play(f, g, "f6g8");
        f.check(g.status() == ChessGame.Status.DRAW && g.reason() == ChessGame.Reason.REPETITION, "threefold ended " + g.status() + "/" + g.reason());
        // insufficient material
        f.check(Position.fromFen("8/8/8/4k3/8/8/8/4K3 w - - 0 1").insufficientMaterial(), "K v K not insufficient");
        f.check(Position.fromFen("8/8/8/4k3/8/8/8/2B1K3 w - - 0 1").insufficientMaterial(), "K+B v K not insufficient");
        f.check(Position.fromFen("8/8/8/4k3/8/8/8/1N2K3 w - - 0 1").insufficientMaterial(), "K+N v K not insufficient");
        f.check(Position.fromFen("8/8/8/4k3/8/4b3/8/2B1K3 w - - 0 1").insufficientMaterial(), "K+B v K+B (same color) not insufficient");
        f.check(!Position.fromFen("8/8/8/4k3/8/5b2/8/2B1K3 w - - 0 1").insufficientMaterial(), "K+B v K+B (opposite colors) called insufficient");
        f.check(!Position.fromFen("8/8/8/4k3/8/8/8/R3K3 w - - 0 1").insufficientMaterial(), "K+R v K called insufficient");
        f.check(!Position.fromFen("8/8/8/4k3/8/8/4P3/4K3 w - - 0 1").insufficientMaterial(), "K+P v K called insufficient");
        g = new ChessGame("8/8/8/4k3/8/8/8/4K3 w - - 0 1");
        f.check(g.status() == ChessGame.Status.DRAW && g.reason() == ChessGame.Reason.INSUFFICIENT, "bare kings game " + g.status());
        // a capture that leaves K+B v K ends the game
        g = play(f, new ChessGame("8/8/8/8/8/2k5/1r6/K1B5 w - - 0 1"), "c1b2");
        f.check(g.status() == ChessGame.Status.DRAW && g.reason() == ChessGame.Reason.INSUFFICIENT, "Bxb2 leaving K+B v K ended " + g.status() + "/" + g.reason());
        // fifty-move rule: the 100th half-move without a capture or pawn move
        g = play(f, new ChessGame("k7/8/8/8/8/8/8/KR6 w - - 99 80"), "b1b2");
        f.check(g.status() == ChessGame.Status.DRAW && g.reason() == ChessGame.Reason.FIFTY_MOVES, "fifty-move rule ended " + g.status() + "/" + g.reason());
        // a flag fall against a bare king is a draw, otherwise a loss
        g = new ChessGame("k7/8/8/8/8/8/8/KR6 w - - 0 1");
        g.flag(false);
        f.check(g.status() == ChessGame.Status.WHITE_WON && g.reason() == ChessGame.Reason.TIMEOUT, "black flag vs K+R: " + g.status());
        g = new ChessGame("k7/8/8/8/8/8/8/KR6 w - - 0 1");
        g.flag(true);
        f.check(g.status() == ChessGame.Status.DRAW && g.reason() == ChessGame.Reason.TIMEOUT_VS_INSUFFICIENT, "white flag vs bare king: " + g.status());
        // disambiguation
        Position two = Position.fromFen("4k3/8/8/8/8/8/8/1N2KN2 w - - 0 1");
        f.check(two.san(two.parseUci("b1d2")).equals("Nbd2"), "SAN " + two.san(two.parseUci("b1d2")));
        f.finish(h);
    }

    // ------------------------------------------------------------------ the table

    static BlockPos table(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = GadgetTests.floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        ArcanaContent.place(level, at, "arcana_chess_table", Direction.NORTH);
        return at;
    }

    static void near(CT.Bot b, BlockPos at, int dx) {
        CT.pose(b.p, Vec3.atBottomCenterOf(at.offset(dx, 0, 2)), 180, 30);
    }

    @GameTest(maxTicks = 100, padding = 12)
    public void seatsAndTurns(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("chess-seats");
        BlockPos at = table(h);
        CT.Bot a = CT.join(h, "chA", false), b = CT.join(h, "chB", false), c = CT.join(h, "chC", false);
        near(a, at, -1);
        near(b, at, 0);
        near(c, at, 1);
        if (!(level.getBlockEntity(at) instanceof ChessBlockEntity be)) {
            f.add("no chess block entity on the placed table: " + level.getBlockEntity(at));
            f.finish(h);
            return;
        }
        // the click opens the screen (or tells an unmodded player) and claims the interaction
        InteractionResult r = CT.click(a.p, at, Direction.UP);
        f.check(r.consumesAction(), "right-clicking the table returned " + r);
        f.check(be.sit(a.p, true) == null, "first player can't sit at White");
        f.check("seat-taken".equals(be.sit(b.p, true)), "second player took the taken White seat");
        f.check("already-seated".equals(be.sit(a.p, false)), "one player sat on both sides");
        f.check(be.sit(b.p, false) == null, "second player can't sit at Black");
        f.check("seat-taken".equals(be.sit(c.p, true)) && "seat-taken".equals(be.sit(c.p, false)), "a third player got a seat");
        f.check(be.colorOf(c.p.getUUID()) == null && Boolean.TRUE.equals(be.colorOf(a.p.getUUID())) && Boolean.FALSE.equals(be.colorOf(b.p.getUUID())),
                "seat colors wrong");
        // turn order, spectators, legality
        int g = be.gameId();
        f.check("not-your-turn".equals(be.move(b.p, "e7e5", g)), "Black moved first");
        f.check("not-seated".equals(be.move(c.p, "e2e4", g)), "a spectator moved");
        f.check("illegal".equals(be.move(a.p, "e2e5", g)), "an illegal move was accepted");
        f.check("illegal".equals(be.move(a.p, "e7e5", g)), "White moved a black pawn");
        f.check("stale".equals(be.move(a.p, "e2e4", g + 1)), "a move for another game was accepted");
        f.check(be.move(a.p, "e2e4", g) == null && be.game().ply() == 1, "White's e4 refused");
        f.check("not-your-turn".equals(be.move(a.p, "d2d4", g)), "White moved twice");
        f.check("not-seated".equals(be.offerDraw(c.p)) && "not-seated".equals(be.resign(c.p)), "a spectator offered a draw / resigned");
        f.check(be.move(b.p, "e7e5", g) == null, "Black's e5 refused");
        // draw offer: offered, answered by a move, offered again, accepted
        f.check(be.offerDraw(a.p) == null && be.drawOffer() == 1, "White's draw offer not standing");
        f.check(be.move(a.p, "g1f3", g) == null && be.drawOffer() == 1, "the offerer's own move withdrew the offer");
        f.check(be.move(b.p, "b8c6", g) == null && be.drawOffer() == 0, "Black's move did not decline the offer");
        f.check(be.offerDraw(b.p) == null && be.offerDraw(a.p) == null, "draw offer/accept refused");
        f.check(be.game().status() == ChessGame.Status.DRAW && be.game().reason() == ChessGame.Reason.AGREEMENT, "agreed draw ended " + be.game().status());
        f.check("game-over".equals(be.move(a.p, "d2d4", g)), "a move after the game ended");
        // new game: same seats, fresh board
        f.check("not-seated".equals(be.newGame(c.p)), "a spectator started a new game");
        f.check(be.newGame(b.p) == null && be.game().ply() == 0 && be.gameId() != g && !be.game().over(), "new game refused / not fresh");
        g = be.gameId();
        // getting up mid-game resigns
        f.check(be.move(a.p, "d2d4", g) == null, "first move of the new game refused");
        f.check(be.leave(b.p) == null && be.game().status() == ChessGame.Status.WHITE_WON && be.game().reason() == ChessGame.Reason.RESIGNATION
                && be.seat(false) == null, "leaving mid-game did not resign / free the seat");
        // the third player takes the free seat: a finished game starts over
        f.check(be.sit(c.p, false) == null && be.game().ply() == 0 && !be.game().over(), "sitting down at a finished game did not start a new one");
        f.check(be.resign(a.p) != null, "resigned before any move");
        f.check(be.leave(a.p) == null && be.seat(true) == null && !be.game().over(), "leaving before the first move is not free");
        // breaking the table drops just the table
        CT.collectDrops(level, at, 5);
        a.p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        CT.breakBlock(a.p, at);
        Map<String, Integer> drops = CT.collectDrops(level, at, 5);
        f.check(drops.equals(Map.of("aftrhrs:arcana_chess_table", 1)), "broken table dropped " + drops);
        f.check(!(level.getBlockEntity(at) instanceof ChessBlockEntity), "the game outlived the table");
        CT.clear(level, at, 5);
        a.leave();
        b.leave();
        c.leave();
        f.finish(h);
    }

    @GameTest(maxTicks = 100, padding = 12)
    public void savedWithTheTable(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("chess-save");
        BlockPos at = table(h);
        CT.Bot a = CT.join(h, "chSaveA", false), b = CT.join(h, "chSaveB", false);
        near(a, at, -1);
        near(b, at, 1);
        if (!(level.getBlockEntity(at) instanceof ChessBlockEntity be)) {
            f.add("no chess block entity");
            f.finish(h);
            return;
        }
        be.sit(a.p, true);
        be.sit(b.p, false);
        f.check(be.setTimeControl(a.p, 3) == null, "time control refused before the first move");
        int g = be.gameId();
        for (String[] mv : new String[][]{{"e2e4", "w"}, {"a7a6", "b"}, {"e1e2", "w"}, {"a6a5", "b"}, {"e4e5", "w"}, {"f7f5", "b"}}) {
            String err = be.move(mv[1].equals("w") ? a.p : b.p, mv[0], g);
            f.check(err == null, mv[0] + " refused: " + err);
        }
        f.check(be.offerDraw(b.p) == null, "draw offer refused");
        String fen = be.game().position().fen();
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        BlockEntity re = BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
        if (!(re instanceof ChessBlockEntity copy)) {
            f.add("reloaded table is " + re);
            f.finish(h);
            return;
        }
        f.check(copy.game().position().fen().equals(fen), "position after reload " + copy.game().position().fen() + ", want " + fen);
        f.check(copy.game().sanMoves().equals(be.game().sanMoves()), "move list after reload " + copy.game().sanMoves());
        f.check(copy.seat(true) != null && copy.seat(true).id().equals(a.p.getUUID()) && copy.seat(true).name().equals(a.p.getScoreboardName())
                && copy.seat(false) != null && copy.seat(false).id().equals(b.p.getUUID()), "seats after reload");
        f.check(copy.drawOffer() == 2 && copy.timeControl() == 3 && copy.clockMs(true) == be.clockMs(true) && copy.gameId() == g,
                "draw offer / clock / game id after reload");
        // the en passant chance (e5xf6) and the lost white castling rights survive too
        f.check(copy.game().position().parseUci("e5f6") >= 0, "en passant lost on reload");
        f.check((copy.game().position().castling() & (Position.WK | Position.WQ)) == 0, "castling rights back after reload");
        // a client update with one more move only plays that move on the copy it already has
        f.check(be.move(a.p, "e5f6", g) == null, "exf6 e.p. refused");
        copy.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                level.registryAccess(), be.getUpdateTag(level.registryAccess())));
        f.check(copy.game().position().fen().equals(be.game().position().fen()) && copy.game().sanMoves().equals(be.game().sanMoves()),
                "incremental update gave " + copy.game().position().fen() + " " + copy.game().sanMoves());
        // a finished game keeps its result
        be.resign(b.p);
        CompoundTag over = be.saveWithFullMetadata(level.registryAccess());
        BlockEntity re2 = BlockEntity.loadStatic(at, level.getBlockState(at), over, level.registryAccess());
        f.check(re2 instanceof ChessBlockEntity c2 && c2.game().status() == ChessGame.Status.WHITE_WON && c2.game().reason() == ChessGame.Reason.RESIGNATION,
                "resignation lost on reload");
        // and the update clients get carries the board
        CompoundTag update = be.getUpdateTag(level.registryAccess());
        f.check(update.contains("Moves") && update.contains("White"), "client update lacks the game: " + update.keySet());
        CT.clear(level, at, 5);
        a.leave();
        b.leave();
        f.finish(h);
    }

    @GameTest(maxTicks = 200, padding = 12)
    public void awaySeatsAreForfeited(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("chess-away");
        BlockPos at = table(h);
        CT.Bot a = CT.join(h, "chAwayA", false), b = CT.join(h, "chAwayB", false);
        near(a, at, -1);
        near(b, at, 1);
        if (!(level.getBlockEntity(at) instanceof ChessBlockEntity be)) {
            f.add("no chess block entity");
            f.finish(h);
            return;
        }
        be.sit(a.p, true);
        be.sit(b.p, false);
        be.move(a.p, "e2e4", be.gameId());
        // Black logs off: the seat is held (offline countdown) ...
        b.leave();
        h.runAfterDelay(25, () -> {
            f.check(be.awayTicks(false) > 0 && be.offline(false) && !be.game().over() && be.seat(false) != null,
                    "logged-off Black not counted away (away " + be.awayTicks(false) + ", over " + be.game().over() + ")");
            f.check(be.awayTicks(true) == 0, "White at the table counted away");
            // ... until the limit: White wins by forfeit, the seat is free
            be.forceAway(false, true);
            h.runAfterDelay(25, () -> {
                f.check(be.game().status() == ChessGame.Status.WHITE_WON && be.game().reason() == ChessGame.Reason.ABANDONED,
                        "abandoned game ended " + be.game().status() + "/" + be.game().reason());
                f.check(be.seat(false) == null && be.seat(true) != null, "seats after the forfeit");
                // before a game starts, walking away just frees the seat
                be.newGame(a.p);
                CT.pose(a.p, Vec3.atCenterOf(at).add(40, 0, 0), 0, 0);
                h.runAfterDelay(25, () -> {
                    f.check(be.awayTicks(true) > 0 && !be.offline(true), "White 40 blocks away not counted away");
                    be.forceAway(true, false);
                    h.runAfterDelay(25, () -> {
                        f.check(be.seat(true) == null && !be.game().over() && be.game().ply() == 0, "unstarted seat not freed");
                        CT.clear(level, at, 5);
                        a.leave();
                        f.finish(h);
                    });
                });
            });
        });
    }

    /** Both players gone mid-game: the board starts over with both seats free. */
    @GameTest(maxTicks = 100, padding = 12)
    public void bothGoneResets(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("chess-both-gone");
        BlockPos at = table(h);
        CT.Bot a = CT.join(h, "chGoneA", false), b = CT.join(h, "chGoneB", false);
        near(a, at, -1);
        near(b, at, 1);
        if (!(level.getBlockEntity(at) instanceof ChessBlockEntity be)) {
            f.add("no chess block entity");
            f.finish(h);
            return;
        }
        be.sit(a.p, true);
        be.sit(b.p, false);
        be.move(a.p, "d2d4", be.gameId());
        be.move(b.p, "d7d5", be.gameId());
        a.leave();
        b.leave();
        be.forceAway(true, true);
        be.forceAway(false, true);
        h.runAfterDelay(25, () -> {
            f.check(be.seat(true) == null && be.seat(false) == null && be.game().ply() == 0 && !be.game().over(),
                    "after both left: seats " + be.seat(true) + "/" + be.seat(false) + ", ply " + be.game().ply());
            CT.clear(level, at, 5);
            f.finish(h);
        });
    }

    // ------------------------------------------------------------------ the pets screen's data (PetsCompanion)

    static Object petsModule() {
        Object m = net.axther.aftrhrs.gametest.creatures.CT.call(net.axther.aftrhrs.gametest.creatures.CT.cls("net.axther.aftrhrs.feature.pets.PetsModule"), "active");
        if (m == null) throw new IllegalStateException("pets module is not running");
        return m;
    }

    /** Links the player as a Companion client with these features (what a hello would agree on). */
    static void linkCompanion(net.minecraft.server.level.ServerPlayer p, String... features) {
        Map<java.util.UUID, net.axther.aftrhrs.net.Companion.Session> sessions = RecipeTests.companionStatic("SESSIONS");
        sessions.put(p.getUUID(), new net.axther.aftrhrs.net.Companion.Session("test", java.util.Set.of(features)));
        RecipeTests.sentTo(p);
    }

    /** A tamed creature of this species next to the owner, past the pet cap. */
    static net.axther.aftrhrs.entity.ArcanaCreature tamedPet(GameTestHelper h, String type, net.minecraft.server.level.ServerPlayer owner, Vec3 at) {
        net.axther.aftrhrs.entity.ArcanaCreature c = net.axther.aftrhrs.gametest.creatures.CT.spawn(h,
                net.axther.aftrhrs.entity.ArcanaEntities.species(net.axther.aftrhrs.entity.ArcanaEntities.type(type)), at);
        Object m = petsModule();
        int was = (int) net.axther.aftrhrs.gametest.creatures.CT.get(m, "defaultCap");
        net.axther.aftrhrs.gametest.creatures.CT.set(m, "defaultCap", 1000);
        try {
            c.tamedBy(owner);
        } finally {
            net.axther.aftrhrs.gametest.creatures.CT.set(m, "defaultCap", was);
        }
        return c;
    }

    static Object petRecord(net.minecraft.world.entity.Entity e) {
        return net.axther.aftrhrs.gametest.creatures.CT.call(net.axther.aftrhrs.gametest.creatures.CT.call(petsModule(), "store"), "byEntity", e.getUUID());
    }

    /**
     * The pets snapshot with its per-snapshot cache (perk rows per level, plushies, world names, "where" lines, one
     * inventory scan for plushies) is the same JSON as building every pet on its own: an alive pet with a variant and
     * a name color, a stabled one and a fallen one.
     */
    @GameTest(maxTicks = 100, padding = 12)
    public void petsSnapshotMatchesThePerPetBuild(GameTestHelper h) {
        net.axther.aftrhrs.gametest.creatures.CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        net.minecraft.server.level.ServerPlayer owner = net.axther.aftrhrs.gametest.creatures.CT.player(h, net.minecraft.world.level.GameType.SURVIVAL, new Vec3(2, 2, 2));
        linkCompanion(owner, "pets");
        Object m = petsModule();
        var cat = tamedPet(h, "black_cat", owner, new Vec3(3, 1, 3));
        Object catRec = petRecord(cat);
        net.axther.aftrhrs.gametest.creatures.CT.call(m, "setBond", catRec, 3);
        @SuppressWarnings("unchecked")
        Map<String, String> colors = (Map<String, String>) net.axther.aftrhrs.gametest.creatures.CT.call(net.axther.aftrhrs.gametest.creatures.CT.call(m, "perks"), "colors");
        if (!colors.isEmpty()) net.axther.aftrhrs.gametest.creatures.CT.set(catRec, "color", colors.values().iterator().next());
        net.axther.aftrhrs.gametest.creatures.CT.set(catRec, "variant", "smoke");
        var husky = tamedPet(h, "husky", owner, new Vec3(4, 1, 3));
        Object huskyRec = petRecord(husky);
        String parked = (String) net.axther.aftrhrs.gametest.creatures.CT.call(net.axther.aftrhrs.gametest.creatures.CT.call(m, "stable"), "park", owner, huskyRec);
        var beagle = tamedPet(h, "beagle", owner, new Vec3(5, 1, 3));
        beagle.kill(level);
        h.runAfterDelay(2, () -> {
            CT.Fails f = new CT.Fails("pets-snapshot");
            f.check(parked == null, "the husky wasn't stabled: " + parked);
            Object companion = net.axther.aftrhrs.gametest.creatures.CT.call(m, "companion");
            Object cached = net.axther.aftrhrs.gametest.creatures.CT.call(companion, "petsSnapshot", owner, new java.util.HashMap<>(), false);
            Object reference = net.axther.aftrhrs.gametest.creatures.CT.call(companion, "petsSnapshot", owner, new java.util.HashMap<>(), true);
            String a = String.valueOf(cached), b = String.valueOf(reference);
            f.check(a.equals(b), "cached snapshot differs from the per-pet build:\n" + a + "\n" + b);
            f.check(a.contains("\"state\":\"alive\"") && a.contains("\"state\":\"stabled\"") && a.contains("\"state\":\"fallen\""),
                    "the fixture lacks a state: " + a);
            f.check(a.contains("\"variant\":{\"key\":\"smoke\"") && (colors.isEmpty() || a.contains("\"color\":")), "the fixture lacks the variant or color: " + a);
            f.check(a.contains("\"reviveNote\""), "the fallen pet has no revive note: " + a);
            cat.discard();
            net.axther.aftrhrs.gametest.creatures.CT.disconnect(owner);
            f.finish(h);
        });
    }

    static List<String> types(List<com.google.gson.JsonObject> msgs) {
        List<String> out = new java.util.ArrayList<>();
        for (com.google.gson.JsonObject o : msgs) out.add(o.get("t").getAsString());
        return out;
    }

    /**
     * pets_hp: once a pet lost 2 HP, a client with the feature gets a pets_hp message with just that pet's health (no
     * full snapshot); a client without it gets the full "pets" push as before.
     */
    @GameTest(maxTicks = 100, padding = 12)
    public void petsHealthGoesOutAsPetsHp(GameTestHelper h) {
        net.axther.aftrhrs.gametest.creatures.CT.arena(h, 8, 8);
        net.minecraft.server.level.ServerPlayer owner = net.axther.aftrhrs.gametest.creatures.CT.player(h, net.minecraft.world.level.GameType.SURVIVAL, new Vec3(2, 2, 2));
        linkCompanion(owner, "pets", "pets_hp");
        Object m = petsModule();
        var cat = tamedPet(h, "black_cat", owner, new Vec3(3, 1, 3));
        Object rec = petRecord(cat);
        String id = String.valueOf(net.axther.aftrhrs.gametest.creatures.CT.get(rec, "id"));
        Object companion = net.axther.aftrhrs.gametest.creatures.CT.call(m, "companion");
        CT.Fails f = new CT.Fails("pets-hp");
        try {
            net.axther.aftrhrs.gametest.creatures.CT.call(companion, "flush"); // whatever taming queued
            net.axther.aftrhrs.gametest.creatures.CT.call(companion, "sendPets", owner, null);
            RecipeTests.sentTo(owner);
            cat.setHealth(cat.getHealth() - 2);
            net.axther.aftrhrs.gametest.creatures.CT.call(companion, "flush");
            List<com.google.gson.JsonObject> got = RecipeTests.sentTo(owner);
            com.google.gson.JsonObject hp = null;
            for (com.google.gson.JsonObject o : got) if ("pets_hp".equals(o.get("t").getAsString())) hp = o;
            f.check(hp != null && !types(got).contains("pets"), "with pets_hp the owner got " + types(got));
            if (hp != null) {
                var arr = hp.getAsJsonArray("pets");
                f.check(arr.size() == 1 && arr.get(0).getAsJsonObject().get("id").getAsString().equals(id)
                        && Math.abs(arr.get(0).getAsJsonObject().get("health").getAsDouble() - cat.getHealth()) < 0.11
                        && arr.get(0).getAsJsonObject().has("maxHealth") && arr.get(0).getAsJsonObject().has("where"), "the pets_hp body: " + hp);
            }
            net.axther.aftrhrs.gametest.creatures.CT.call(companion, "flush");
            f.check(RecipeTests.sentTo(owner).isEmpty(), "a second flush with nothing moved sent something");

            // Without the feature: the full snapshot, as before.
            linkCompanion(owner, "pets");
            cat.setHealth(cat.getHealth() - 2);
            net.axther.aftrhrs.gametest.creatures.CT.call(companion, "flush");
            List<String> old = types(RecipeTests.sentTo(owner));
            f.check(old.contains("pets") && !old.contains("pets_hp"), "without pets_hp the owner got " + old);
        } finally {
            cat.discard();
            net.axther.aftrhrs.gametest.creatures.CT.disconnect(owner);
        }
        f.finish(h);
    }
}
