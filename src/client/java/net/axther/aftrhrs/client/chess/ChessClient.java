package net.axther.aftrhrs.client.chess;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.net.Link;
import net.axther.aftrhrs.feature.chess.ChessBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The client side of chess tables: {@code chess_open} opens the screen for a table, {@code chess_msg} shows why a
 * request was refused. Requests go back as {@code chess_*} messages with the table's position
 * (docs/companion-protocol.md, "Chess"). The game itself arrives with the table's block entity.
 */
public final class ChessClient {
    private ChessClient() {
    }

    public static void register() {
        Link.on("chess_open", m -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            BlockPos pos = pos(m);
            if (mc.level.getBlockEntity(pos) instanceof ChessBlockEntity) mc.gui.setScreen(new ChessScreen(pos));
        });
        Link.on("chess_msg", m -> {
            if (Minecraft.getInstance().gui.screen() instanceof ChessScreen s && s.pos().equals(pos(m))) s.flash(Link.str(m, "key"));
        });
        ChessBlockEntity.screenOpen = pos -> Minecraft.getInstance().gui.screen() instanceof ChessScreen s && s.pos().equals(pos);
    }

    static BlockPos pos(JsonObject m) {
        return new BlockPos(Link.num(m, "x", 0), Link.num(m, "y", 0), Link.num(m, "z", 0));
    }

    /** A request about the table at {@code pos} ({@code chess_sit}, {@code chess_move}...). */
    static JsonObject request(String type, BlockPos pos) {
        JsonObject o = Link.message(type);
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }
}
