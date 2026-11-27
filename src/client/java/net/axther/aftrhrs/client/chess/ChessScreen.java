package net.axther.aftrhrs.client.chess;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.net.Link;
import net.axther.aftrhrs.feature.chess.ChessBlockEntity;
import net.axther.aftrhrs.feature.chess.ChessGame;
import net.axther.aftrhrs.feature.chess.Position;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The chess table's screen: the board (flipped for the Black player), both players with their clocks, whose turn it
 * is, captured pieces, the move list and the buttons for the seat and the game. Click a piece, then a highlighted
 * square; a pawn reaching the last rank asks which piece it becomes. Everything shown comes from the table's block
 * entity (kept live by the server); clicks become {@code chess_*} requests the server checks.
 */
public final class ChessScreen extends Screen {
    private static final int LIGHT = 0xFFE6CFA0, DARK = 0xFF8B5A33;
    private static final int LAST_MOVE = 0x70F2D648, SELECTED = 0x9060C8FF, CHECK = 0xB0E0303A, HOVER = 0x40FFFFFF;
    private static final int TARGET = 0x7020143A;
    private static final int SIDE = 150, BAR = 14, LABEL = 9;
    private static final String[] NAMES = {"", "pawn", "knight", "bishop", "rook", "queen", "king"};

    private final BlockPos pos;
    private int sq, px, py, pw, ph, bx, by, sx;
    private int selected = -1;
    /** Promotion picker: the move's from/to while it is open. */
    private int promoFrom = -1, promoTo = -1;
    private int seenPly = -1, seenGame = -1;
    private boolean seenOver;
    private float scroll;
    private boolean stickToEnd = true;
    private long resignArmedUntil;
    private String flash;
    private long flashUntil;
    private final List<Runnable> clicks = new ArrayList<>();
    private final List<int[]> hits = new ArrayList<>();
    /** The move list's scroll bar as last drawn (x, y, height, content, view), and whether it is being dragged. */
    private final int[] moveBar = new int[5];
    private boolean draggingBar;

    public ChessScreen(BlockPos pos) {
        super(Component.translatable("aftrhrs.chess.title"));
        this.pos = pos.immutable();
    }

    public BlockPos pos() {
        return pos;
    }

    /** A refusal from the server (aftrhrs.chess.err.*), shown for a few seconds. */
    public void flash(String key) {
        flash = key;
        flashUntil = System.currentTimeMillis() + 3500;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 0.4f));
    }

    @Override
    protected void init() {
        // The biggest squares whose whole panel fits, by width and by height (16px always fits a 320x240 GUI).
        sq = 16;
        for (int s : new int[]{20, 18}) {
            int w = 8 + LABEL + 8 * s + 10 + SIDE + 8, h = 24 + BAR + 2 + 8 * s + LABEL + 2 + BAR + 8;
            if (w + 8 <= width && h + 8 <= height) {
                sq = s;
                break;
            }
        }
        pw = 8 + LABEL + 8 * sq + 10 + SIDE + 8;
        ph = 24 + BAR + 2 + 8 * sq + LABEL + 2 + BAR + 8;
        px = (width - pw) / 2;
        py = Math.max(2, (height - ph) / 2);
        bx = px + 8 + LABEL;
        by = py + 24 + BAR + 2;
        sx = bx + 8 * sq + 10;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    private @Nullable ChessBlockEntity table() {
        if (minecraft.level == null || minecraft.player == null) return null;
        return minecraft.level.getBlockEntity(pos) instanceof ChessBlockEntity be ? be : null;
    }

    @Override
    public void tick() {
        ChessBlockEntity be = table();
        // the table is gone, or the player walked off: the screen closes (the server would refuse every click anyway)
        double reach = ChessBlockEntity.REACH + 0.5;
        if (be == null || minecraft.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach) onClose();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
        clicks.clear();
        hits.clear();
        ChessBlockEntity be = table();
        if (be == null) return;
        ChessGame game = be.game();
        Position p = game.position();
        Boolean me = be.colorOf(minecraft.player.getUUID());
        boolean flip = Boolean.FALSE.equals(me);
        sounds(be, me);
        boolean myTurn = me != null && !game.over() && p.whiteToMove() == me && be.seat(true) != null && be.seat(false) != null;
        if (!myTurn) {
            selected = -1;
            promoFrom = promoTo = -1;
        }

        Theme.blit(g, Theme.MENU_PANEL, px, py, pw, ph);
        Theme.blit(g, Theme.MENU_HEADER, px + 2, py + 2, pw - 4, 20);
        g.text(font, title, px + 10, py + 8, Theme.MOON, true);
        Component tc = be.clockOn() ? Component.translatable("aftrhrs.chess.clock", ChessBlockEntity.TIME_CONTROLS[be.timeControl()][0],
                ChessBlockEntity.TIME_CONTROLS[be.timeControl()][1]) : Component.translatable("aftrhrs.chess.clock-off");
        g.text(font, tc, px + pw - 10 - font.width(tc), py + 8, Theme.MUTED, false);

        // players: the opponent (or Black) on top, you (or White) below
        bar(g, be, flip, bx, py + 24, me);
        bar(g, be, !flip, bx, by + 8 * sq + LABEL + 2, me);

        // board
        int last = game.lastMove();
        int checkSq = p.inCheck() ? p.kingSquare(p.whiteToMove()) : -1;
        List<Integer> targets = new ArrayList<>();
        if (selected >= 0) for (int m : p.legalMoves()) if (Position.from(m) == selected && !targets.contains(Position.to(m))) targets.add(Position.to(m));
        int hovered = squareAt(mx, my, flip);
        for (int s = 0; s < 64; s++) {
            int x = sqX(s, flip), y = sqY(s, flip);
            boolean light = (Position.file(s) + Position.rank(s)) % 2 == 1;
            g.fill(x, y, x + sq, y + sq, light ? LIGHT : DARK);
            if (last >= 0 && (Position.from(last) == s || Position.to(last) == s)) g.fill(x, y, x + sq, y + sq, LAST_MOVE);
            if (s == selected) g.fill(x, y, x + sq, y + sq, SELECTED);
            if (s == checkSq) {
                g.fill(x, y, x + sq, y + sq, CHECK);
            }
            int piece = p.piece(s);
            if (piece != Position.EMPTY) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(piece), x + (sq - 16) / 2, y + (sq - 16) / 2, 16, 16);
            }
            if (targets.contains(s)) {
                if (piece == Position.EMPTY) {
                    int d = Math.max(4, sq / 4), cx = x + (sq - d) / 2, cy = y + (sq - d) / 2;
                    g.fill(cx, cy, cx + d, cy + d, TARGET);
                } else {
                    corners(g, x, y, TARGET | 0x40000000);
                }
            }
            if (s == hovered && promoFrom < 0 && myTurn && (targets.contains(s) || ownPiece(p, s, me))) {
                g.fill(x, y, x + sq, y + sq, HOVER);
                g.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
        // coordinates
        for (int i = 0; i < 8; i++) {
            String file = String.valueOf((char) ('a' + (flip ? 7 - i : i)));
            String rank = String.valueOf((char) ('8' - (flip ? 7 - i : i)));
            g.centeredText(font, file, bx + i * sq + sq / 2, by + 8 * sq + 1, Theme.SUBTLE);
            g.text(font, rank, bx - LABEL + 1, by + i * sq + (sq - 8) / 2, Theme.SUBTLE, false);
        }
        if (promoFrom >= 0) promotionPicker(g, mx, my, p, flip);

        side(g, be, me, mx, my);
        super.extractRenderState(g, mx, my, a);
    }

    private boolean ownPiece(Position p, int s, Boolean me) {
        int piece = p.piece(s);
        return me != null && piece != Position.EMPTY && Position.isBlack(piece) != me;
    }

    private void corners(GuiGraphicsExtractor g, int x, int y, int col) {
        int c = Math.max(3, sq / 5);
        g.fill(x, y, x + c, y + 1, col);
        g.fill(x, y + 1, x + 1, y + c, col);
        g.fill(x + sq - c, y, x + sq, y + 1, col);
        g.fill(x + sq - 1, y + 1, x + sq, y + c, col);
        g.fill(x, y + sq - 1, x + c, y + sq, col);
        g.fill(x, y + sq - c, x + 1, y + sq - 1, col);
        g.fill(x + sq - c, y + sq - 1, x + sq, y + sq, col);
        g.fill(x + sq - 1, y + sq - c, x + sq, y + sq - 1, col);
    }

    private static Identifier sprite(int piece) {
        return Identifier.fromNamespaceAndPath(Companion.ID, "chess/" + (Position.isBlack(piece) ? "black_" : "white_") + NAMES[Position.type(piece)]);
    }

    private int sqX(int s, boolean flip) {
        int col = flip ? 7 - Position.file(s) : Position.file(s);
        return bx + col * sq;
    }

    private int sqY(int s, boolean flip) {
        int row = flip ? Position.rank(s) : 7 - Position.rank(s);
        return by + row * sq;
    }

    private int squareAt(double mx, double my, boolean flip) {
        if (mx < bx || my < by || mx >= bx + 8 * sq || my >= by + 8 * sq) return -1;
        int col = (int) (mx - bx) / sq, row = (int) (my - by) / sq;
        int file = flip ? 7 - col : col, rank = flip ? row : 7 - row;
        return rank * 8 + file;
    }

    /** One player's bar: color chip, name, clock, away countdown; lit while it is their turn. */
    private void bar(GuiGraphicsExtractor g, ChessBlockEntity be, boolean white, int x, int y, Boolean me) {
        ChessGame game = be.game();
        int w = 8 * sq;
        boolean turn = !game.over() && game.position().whiteToMove() == white;
        Theme.blit(g, turn ? Theme.MENU_TAB_SELECTED : Theme.MENU_TAB, x, y, w, BAR);
        g.fill(x + 3, y + 3, x + 11, y + 11, 0xFF000000);
        g.fill(x + 4, y + 4, x + 10, y + 10, white ? 0xFFF2EAD6 : 0xFF3B3448);
        ChessBlockEntity.Seat s = be.seat(white);
        int right = x + w - 4;
        if (be.clockOn()) {
            long ms = be.clockMs(white);
            String clock = clock(ms);
            int cw = font.width(clock);
            int col = ms < 20_000 && turn && be.clockRunning() ? Theme.BAD : turn ? Theme.TEXT : Theme.MUTED;
            g.text(font, clock, right - cw, y + 3, col, false);
            right -= cw + 6;
        }
        if (s != null && be.awayTicks(white) > 0) {
            int left = Math.max(0, be.awayLimit(white) - be.awayTicks(white)) / 20;
            Component away = Component.translatable(be.offline(white) ? "aftrhrs.chess.offline" : "aftrhrs.chess.away", clock(left * 1000L));
            int aw = font.width(away);
            g.text(font, away, right - aw, y + 3, Theme.BAD, false);
            right -= aw + 6;
        }
        String name;
        int col;
        if (s == null) {
            name = Component.translatable("aftrhrs.chess.empty-seat").getString();
            col = Theme.SUBTLE;
        } else {
            name = Boolean.valueOf(white).equals(me) ? Component.translatable("aftrhrs.chess.you", s.name()).getString() : s.name();
            col = turn ? Theme.MOON : Theme.TEXT;
        }
        g.text(font, Theme.ellipsize(font, name, right - (x + 15)), x + 15, y + 3, col, false);
    }

    private static String clock(long ms) {
        long secs = (ms + 999) / 1000;
        if (ms < 10_000 && ms > 0) return String.format(Locale.ROOT, "%d.%d", ms / 1000, ms % 1000 / 100);
        return String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
    }

    private void promotionPicker(GuiGraphicsExtractor g, int mx, int my, Position p, boolean flip) {
        g.fill(bx, by, bx + 8 * sq, by + 8 * sq, 0x90000000);
        int x = sqX(promoTo, flip), y0 = sqY(promoTo, flip);
        boolean down = y0 < by + 4 * sq;
        int[] types = {Position.QUEEN, Position.KNIGHT, Position.ROOK, Position.BISHOP};
        boolean black = !p.whiteToMove();
        for (int i = 0; i < 4; i++) {
            int y = down ? y0 + i * sq : y0 - i * sq;
            boolean hov = Theme.inside(mx, my, x, y, sq, sq);
            g.fill(x - 1, y - 1, x + sq + 1, y + sq + 1, 0xFF0A0816);
            g.fill(x, y, x + sq, y + sq, hov ? 0xFFF7E7B5 : LIGHT);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(types[i] | (black ? Position.BLACK : 0)), x + (sq - 16) / 2, y + (sq - 16) / 2, 16, 16);
            int type = types[i];
            hits.add(new int[]{x, y, sq, sq});
            clicks.add(() -> {
                sendMove(promoFrom, promoTo, type);
                promoFrom = promoTo = -1;
            });
            if (hov) g.requestCursor(CursorTypes.POINTING_HAND);
        }
        Component label = Component.translatable("aftrhrs.chess.promote");
        int lw = font.width(label);
        int ly = down ? y0 - 11 : y0 + sq + 2;
        int lx = Mth.clamp(x + sq / 2 - lw / 2, bx, bx + 8 * sq - lw);
        g.text(font, label, lx, ly, Theme.MOON, true);
    }

    /** The right column: status, draw offer, captured pieces, moves, buttons. */
    private void side(GuiGraphicsExtractor g, ChessBlockEntity be, Boolean me, int mx, int my) {
        ChessGame game = be.game();
        Position p = game.position();
        int x = sx, y = py + 26, w = SIDE;
        ChessBlockEntity.Seat white = be.seat(true), black = be.seat(false);

        // status
        Component status;
        int statusCol = Theme.TEXT;
        if (game.over()) {
            String res = switch (game.status()) {
                case WHITE_WON -> "white";
                case BLACK_WON -> "black";
                default -> "draw";
            };
            status = Component.translatable("aftrhrs.chess.result." + res).append(" ").append(Component.translatable("aftrhrs.chess.reason." + game.reason().key()));
            statusCol = Theme.MOON;
        } else if (white == null || black == null) {
            status = Component.translatable(white == null ? "aftrhrs.chess.waiting-white" : "aftrhrs.chess.waiting-black");
            statusCol = Theme.MUTED;
        } else if (me != null) {
            boolean mine = p.whiteToMove() == me;
            ChessBlockEntity.Seat other = be.seat(!me);
            status = mine ? Component.translatable("aftrhrs.chess.turn.you") : Component.translatable("aftrhrs.chess.turn.them", other == null ? "?" : other.name());
            statusCol = mine ? Theme.MOON : Theme.TEXT;
        } else {
            status = Component.translatable(p.whiteToMove() ? "aftrhrs.chess.turn.white" : "aftrhrs.chess.turn.black");
        }
        if (!game.over() && p.inCheck()) status = Component.translatable("aftrhrs.chess.check").append(" ").append(status);
        if (flash != null && System.currentTimeMillis() < flashUntil) {
            status = Component.translatable("aftrhrs.chess.err." + flash);
            statusCol = Theme.BAD;
        }
        List<FormattedCharSequence> lines = font.split(status, w);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.text(font, lines.get(i), x, y + i * 10, statusCol, true);
        y += 22;

        // a standing draw offer
        int offer = be.drawOffer();
        if (offer != 0 && !game.over()) {
            boolean fromWhite = offer == 1;
            ChessBlockEntity.Seat from = be.seat(fromWhite);
            g.text(font, Theme.ellipsize(font, Component.translatable("aftrhrs.chess.draw-from", from == null ? "?" : from.name()).getString(), w), x, y, Theme.SKY, false);
            y += 12;
        }

        // captured pieces: what each side took, with the material lead
        g.text(font, Component.translatable("aftrhrs.chess.captured"), x, y, Theme.MUTED, false);
        y += 10;
        int lead = material(game.capturedPieces(false)) - material(game.capturedPieces(true));
        y = captured(g, game.capturedPieces(false), x, y, lead > 0 ? lead : 0);
        y = captured(g, game.capturedPieces(true), x, y, lead < 0 ? -lead : 0);
        y += 2;

        // moves
        int buttons = buttonRows(be, me);
        int listBottom = py + ph - 8 - buttons * 18 - 2;
        g.text(font, Component.translatable("aftrhrs.chess.moves"), x, y, Theme.MUTED, false);
        y += 10;
        moveList(g, game, x, y, w, Math.max(20, listBottom - y));

        // buttons
        buttons(g, be, me, x, listBottom + 2, w, mx, my);
    }

    private static int material(List<Integer> pieces) {
        int n = 0;
        for (int p : pieces) n += switch (Position.type(p)) {
            case Position.PAWN -> 1;
            case Position.KNIGHT, Position.BISHOP -> 3;
            case Position.ROOK -> 5;
            case Position.QUEEN -> 9;
            default -> 0;
        };
        return n;
    }

    /** One row of taken pieces (3/4 size, overlapping a little) on a pale strip, with the material lead. */
    private int captured(GuiGraphicsExtractor g, List<Integer> pieces, int x, int y, int lead) {
        int step = 9, w = Math.max(14, pieces.size() * step + 4);
        if (!pieces.isEmpty()) g.fill(x, y, x + w, y + 13, 0x40E6CFA0);
        g.pose().pushMatrix();
        g.pose().translate(x + 1, y + 1);
        g.pose().scale(0.75f, 0.75f);
        for (int i = 0; i < pieces.size(); i++) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(pieces.get(i)), Math.round(i * step / 0.75f), 0, 16, 16);
        }
        g.pose().popMatrix();
        if (lead > 0) g.text(font, "+" + lead, x + (pieces.isEmpty() ? 0 : w + 3), y + 3, Theme.MUTED, false);
        return y + 14;
    }

    private void moveList(GuiGraphicsExtractor g, ChessGame game, int x, int y, int w, int h) {
        Theme.blit(g, Theme.MENU_INSET, x, y, w, h);
        List<String> san = game.sanMoves();
        int rows = (san.size() + 1) / 2;
        int rowH = 10, inner = h - 6;
        float max = Math.max(0, rows * rowH - inner);
        if (stickToEnd) scroll = max;
        scroll = Mth.clamp(scroll, 0, max);
        if (scroll >= max) stickToEnd = true;
        moveBar[0] = x + w - 5;
        moveBar[1] = y + 3;
        moveBar[2] = inner;
        moveBar[3] = rows * rowH;
        moveBar[4] = inner;
        if (rows == 0) {
            g.text(font, Component.translatable("aftrhrs.chess.no-moves"), x + 5, y + 4, Theme.SUBTLE, false);
            return;
        }
        g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
        for (int r = 0; r < rows; r++) {
            int ry = y + 3 + r * rowH - (int) scroll;
            if (ry + rowH < y || ry > y + h) continue;
            boolean lastRow = r == rows - 1;
            g.text(font, (r + 1) + ".", x + 5, ry, Theme.SUBTLE, false);
            int wi = 2 * r, bi = 2 * r + 1;
            g.text(font, san.get(wi), x + 30, ry, lastRow && bi >= san.size() ? Theme.MOON : Theme.TEXT, false);
            // the black column stops short of the scroll bar (long SAN such as "exd8=Q+" fits)
            if (bi < san.size()) g.text(font, san.get(bi), x + 30 + (w - 38) / 2, ry, lastRow ? Theme.MOON : Theme.TEXT, false);
        }
        g.disableScissor();
        Theme.scrollbar(g, moveBar[0], moveBar[1], 3, inner, scroll, moveBar[3], inner, 0x40000000, Theme.MUTED & 0xFFFFFF | 0xC0000000);
    }

    private int buttonRows(ChessBlockEntity be, Boolean me) {
        if (me == null) return be.seat(true) == null || be.seat(false) == null ? 1 : 0;
        if (be.drawOffer() == (me ? 2 : 1) && be.running()) return 2;
        return 1;
    }

    private void buttons(GuiGraphicsExtractor g, ChessBlockEntity be, Boolean me, int x, int y, int w, int mx, int my) {
        ChessGame game = be.game();
        int half = (w - 4) / 2;
        if (me == null) {
            if (be.seat(true) == null || be.seat(false) == null) {
                button(g, mx, my, x, y, half, Component.translatable("aftrhrs.chess.play-white"), be.seat(true) == null, () -> sit(be, true));
                button(g, mx, my, x + half + 4, y, half, Component.translatable("aftrhrs.chess.play-black"), be.seat(false) == null, () -> sit(be, false));
            } else {
                g.centeredText(font, Component.translatable("aftrhrs.chess.watching"), x + w / 2, y + 4, Theme.SUBTLE);
            }
            return;
        }
        if (be.running()) {
            boolean theyOffer = be.drawOffer() == (me ? 2 : 1);
            if (theyOffer) {
                button(g, mx, my, x, y, half, Component.translatable("aftrhrs.chess.accept-draw"), true, () -> send(be, "chess_draw"));
                button(g, mx, my, x + half + 4, y, half, Component.translatable("aftrhrs.chess.decline-draw"), true, () -> send(be, "chess_decline"));
                y += 18;
            }
            boolean armed = System.currentTimeMillis() < resignArmedUntil;
            button(g, mx, my, x, y, half, Component.translatable(armed ? "aftrhrs.chess.resign-confirm" : "aftrhrs.chess.resign"), true, () -> {
                if (System.currentTimeMillis() < resignArmedUntil) {
                    resignArmedUntil = 0;
                    send(be, "chess_resign");
                } else {
                    resignArmedUntil = System.currentTimeMillis() + 3000;
                }
            });
            boolean mineOffer = be.drawOffer() == (me ? 1 : 2);
            if (!theyOffer) {
                button(g, mx, my, x + half + 4, y, half, Component.translatable(mineOffer ? "aftrhrs.chess.draw-offered" : "aftrhrs.chess.offer-draw"),
                        !mineOffer && be.seat(!me) != null, () -> send(be, "chess_draw"));
            }
            return;
        }
        if (game.over()) {
            button(g, mx, my, x, y, half, Component.translatable("aftrhrs.chess.new-game"), true, () -> send(be, "chess_new"));
        } else {
            int next = (be.timeControl() + 1) % ChessBlockEntity.TIME_CONTROLS.length;
            Component tc = be.clockOn() ? Component.translatable("aftrhrs.chess.clock", ChessBlockEntity.TIME_CONTROLS[be.timeControl()][0],
                    ChessBlockEntity.TIME_CONTROLS[be.timeControl()][1]) : Component.translatable("aftrhrs.chess.clock-off");
            button(g, mx, my, x, y, half, tc, true, () -> {
                JsonObject o = ChessClient.request("chess_time", pos);
                o.addProperty("tc", next);
                Link.send(o);
            });
        }
        button(g, mx, my, x + half + 4, y, half, Component.translatable("aftrhrs.chess.leave"), true, () -> send(be, "chess_leave"));
    }

    private void button(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, Component label, boolean active, Runnable run) {
        boolean over = Theme.inside(mx, my, x, y, w, 16), hov = active && over;
        Theme.menuButton(g, x, y, w, 16, hov, active);
        // a label too long for the button (a translation) ends in "…" and shows whole on hover, even on a gray button
        int room = w - 6;
        g.centeredText(font, Theme.fit(font, label, room), x + w / 2, y + 4, active ? Theme.TEXT : Theme.DIM);
        if (over && !Theme.fits(font, label, room)) g.setTooltipForNextFrame(font, label, mx, my);
        if (!active) return;
        if (hov) g.requestCursor(CursorTypes.POINTING_HAND);
        hits.add(new int[]{x, y, w, 16});
        clicks.add(() -> {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f));
            run.run();
        });
    }

    // ------------------------------------------------------------------ sounds

    /** Move / capture / check / game over, once per change of the game. */
    private void sounds(ChessBlockEntity be, Boolean me) {
        ChessGame game = be.game();
        int ply = game.ply();
        boolean over = game.over();
        if (seenPly >= 0 && be.gameId() == seenGame) {
            if (ply > seenPly) {
                selected = -1;
                stickToEnd = true;
                boolean capture = game.taken(ply - 1) != Position.EMPTY;
                if (game.position().inCheck() && !over) play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.6f);
                if (capture) play(SoundEvents.WOOD_BREAK, 1.3f, 0.8f);
                else play(SoundEvents.WOOD_PLACE, 1.7f, 0.8f);
            }
            if (over && !seenOver) {
                boolean won = me != null && (me ? game.status() == ChessGame.Status.WHITE_WON : game.status() == ChessGame.Status.BLACK_WON);
                if (won) play(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.6f);
                else play(SoundEvents.NOTE_BLOCK_CHIME.value(), game.status() == ChessGame.Status.DRAW ? 0.9f : 0.7f, 0.8f);
            }
        }
        seenPly = ply;
        seenGame = be.gameId();
        seenOver = over;
    }

    private void play(SoundEvent ev, float pitch, float volume) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(ev, pitch, volume));
    }

    // ------------------------------------------------------------------ input

    private void sit(ChessBlockEntity be, boolean white) {
        JsonObject o = ChessClient.request("chess_sit", pos);
        o.addProperty("side", white ? "white" : "black");
        Link.send(o);
    }

    private void send(ChessBlockEntity be, String type) {
        JsonObject o = ChessClient.request(type, pos);
        o.addProperty("g", be.gameId());
        Link.send(o);
    }

    private void sendMove(int from, int to, int promo) {
        ChessBlockEntity be = table();
        if (be == null) return;
        JsonObject o = ChessClient.request("chess_move", pos);
        o.addProperty("g", be.gameId());
        o.addProperty("m", Position.uci(Position.move(from, to, promo)));
        Link.send(o);
        selected = -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            selected = -1;
            promoFrom = promoTo = -1;
            return super.mouseClicked(event, doubleClick);
        }
        double mx = event.x(), my = event.y();
        if (promoFrom < 0 && moveBar[3] > moveBar[4] && Theme.inside(mx, my, moveBar[0] - 1, moveBar[1], 5, moveBar[2])) {
            // the move list's scroll bar: click to jump there, drag to scroll
            draggingBar = true;
            stickToEnd = false;
            scroll = (float) Theme.scrollAt(my, moveBar[1], moveBar[2], moveBar[3], moveBar[4]);
            return true;
        }
        for (int i = 0; i < hits.size(); i++) {
            int[] h = hits.get(i);
            if (Theme.inside(mx, my, h[0], h[1], h[2], h[3])) {
                clicks.get(i).run();
                return true;
            }
        }
        if (promoFrom >= 0) {
            promoFrom = promoTo = -1;
            return true;
        }
        ChessBlockEntity be = table();
        if (be == null) return super.mouseClicked(event, doubleClick);
        Boolean me = be.colorOf(minecraft.player.getUUID());
        Position p = be.game().position();
        int s = squareAt(mx, my, Boolean.FALSE.equals(me));
        if (s < 0) return super.mouseClicked(event, doubleClick);
        boolean myTurn = me != null && !be.game().over() && p.whiteToMove() == me && be.seat(true) != null && be.seat(false) != null;
        if (!myTurn) {
            if (me != null && !be.game().over()) flash(be.seat(true) == null || be.seat(false) == null ? "need-opponent" : "not-your-turn");
            return true;
        }
        if (selected >= 0 && s != selected) {
            boolean promotes = false, legal = false;
            for (int m : p.legalMoves()) {
                if (Position.from(m) != selected || Position.to(m) != s) continue;
                legal = true;
                if (Position.promo(m) != 0) promotes = true;
            }
            if (legal) {
                if (promotes) {
                    promoFrom = selected;
                    promoTo = s;
                } else {
                    sendMove(selected, s, 0);
                }
                return true;
            }
        }
        if (ownPiece(p, s, me) && s != selected) {
            selected = s;
            play(SoundEvents.UI_BUTTON_CLICK.value(), 2f, 0.15f);
        } else {
            selected = -1;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingBar) {
            stickToEnd = false; // back at the end, the list follows new moves again
            scroll = (float) Theme.scrollAt(event.y(), moveBar[1], moveBar[2], moveBar[3], moveBar[4]);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingBar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (float) sy * 10;
        stickToEnd = false; // the list follows new moves again once scrolled back to the end
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && (promoFrom >= 0 || selected >= 0)) {
            promoFrom = promoTo = -1;
            selected = -1;
            return true;
        }
        if (!event.isEscape() && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
