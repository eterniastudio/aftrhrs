package net.axther.aftrhrs.client.smithing;

import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.MoldItem;
import net.axther.aftrhrs.feature.smithing.Mods;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
import net.axther.aftrhrs.feature.smithing.station.AnvilBE;
import net.axther.aftrhrs.feature.smithing.station.AssemblyBE;
import net.axther.aftrhrs.feature.smithing.station.CarvingBE;
import net.axther.aftrhrs.feature.smithing.station.CastingBE;
import net.axther.aftrhrs.feature.smithing.station.ForgeBE;
import net.axther.aftrhrs.feature.smithing.station.GreatCrucibleBE;
import net.axther.aftrhrs.feature.smithing.station.MeltBE;
import net.axther.aftrhrs.feature.smithing.station.RuneTableBE;
import net.axther.aftrhrs.feature.smithing.station.SpoutBE;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.axther.aftrhrs.feature.smithing.station.StationBE;
import net.axther.aftrhrs.feature.smithing.station.StationBlock;
import net.axther.aftrhrs.feature.smithing.station.TroughBE;
import net.axther.aftrhrs.feature.smithing.station.WheelBE;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.axther.aftrhrs.client.Theme;
import net.axther.aftrhrs.client.hud.HudConfig;
import net.axther.aftrhrs.client.hud.HudEditorScreen;
import net.axther.aftrhrs.client.hud.HudElement;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The recipe note (SMITHING.md 8): look at a station and a paper card beside the crosshair says what's on it, how hot
 * it is, the plan and what to do next, like the order tickets pinned to the stations in a kitchen.
 */
public final class SmithingHud {
    // Opaque paper; the inks read at least 5:1 on it (faded 6.5:1, good 5.1:1, warn 5.2:1).
    private static final int PAPER = 0xFFEEE3C8, EDGE = 0xFF6B4A2B, INK = 0xFF3B2A1C, FADED = 0xFF5E4B3A, GOOD = 0xFF236B2B, WARN = 0xFFA8321E;

    private record Line(Component text, int color) {
    }

    /**
     * The card as last laid out: built at most once a client tick for the same station, block entity, width and font
     * settings (the frame rate only redraws it). Values on it lag by at most a tick.
     */
    private record Card(Object level, BlockPos pos, BlockState state, @Nullable Object be, long tick, int room, String lang, boolean uni,
                        FormattedCharSequence title, int w, FormattedCharSequence[] rows, int[] colors) {
    }

    /** Client ticks (advances every tick, unlike the level's clock under /tick freeze): the card cache's clock. */
    static long clientTicks;
    private static @Nullable Card card;

    private SmithingHud() {
    }

    /** Drops the cached card (it holds the level and a block entity). */
    static void forget() {
        card = null;
    }

    /** The editor's sample note (a lit forge), kept while the width budget and font settings stay. */
    private static @Nullable Card sampleCard;
    /** The Language the sample was laid out under (a resource reload makes a new one). */
    private static @Nullable Language sampleLang;
    /** Which side of the crosshair the note sits on, and the station, screen, scale and toast floor that was decided for. */
    private static boolean right = true, sideDecided;
    private static @Nullable BlockPos sidePos;
    private static int sideW, sideH, sideFloor;
    private static float sideScale;

    /**
     * {@code preview}: the HUD layout editor is drawing it (a sample note). Drawn by {@link HudEditorScreen#drawAll} after
     * the look-at card and before the requests toast, so quiet mode, F1 and the editor apply as to every other piece.
     */
    public static void draw(GuiGraphicsExtractor g, boolean preview) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();
        float s = HudConfig.drawScale(HudConfig.get().pos(HudElement.NOTE).scale);
        // As wide as the longest line, at most 220, and the same room on either side of the crosshair (the frame takes 9 more).
        int room = Mth.clamp((int) Math.floor((sw / 2f - 14 - 9) / s), 60, 220);
        String lang = mc.options.languageCode;
        boolean uni = mc.options.forceUnicodeFont().get();
        if (preview) {
            Card c = sampleCard;
            Language langNow = Language.getInstance();
            if (c == null || c.room != room || c.uni != uni || !c.lang.equals(lang) || sampleLang != langNow) {
                List<Line> lines = List.of(new Line(Component.translatable("aftrhrs.smithing.note.heat", heat(900)), INK),
                        new Line(Component.literal("8× ").append(new ItemStack(Items.CHARCOAL).getHoverName()), FADED),
                        new Line(Component.translatable("aftrhrs.smithing.note.forge.bellows"), GOOD));
                c = layout(font, Component.translatable(Smithing.block(Station.FORGE).getDescriptionId()), lines, null, BlockPos.ZERO, null, null, room, lang, uni);
                sampleCard = c;
                sampleLang = langNow;
            }
            paint(g, font, c, s, true, Integer.MAX_VALUE, null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) {
            card = null;
            return;
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
            card = null;
            return;
        }
        BlockPos pos = bh.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof StationBlock sb)) {
            card = null;
            return;
        }
        if (!HudConfig.shown(HudElement.NOTE)) {
            card = null;
            return;
        }
        Object be = mc.level.getBlockEntity(pos);
        // Looked away and back: where it sits is decided again.
        if (card == null) sideDecided = false;
        Card c = card;
        if (c == null || c.level != mc.level || c.tick != clientTicks || !c.pos.equals(pos) || c.state != state || c.be != be || c.room != room
                || c.uni != uni || !c.lang.equals(lang)) {
            c = build(mc, font, pos, state, sb, be, room, lang, uni);
            card = c;
        }
        if (c.rows.length == 0) return;
        paint(g, font, c, s, false, HudConfig.requestsFloor(sh), c.pos);
    }

    /**
     * Places the note beside the crosshair (right, else left when the right would cover another piece or leave the
     * screen; decided once per station, screen size, scale and toast) and above the requests toast, then draws it.
     */
    private static void paint(GuiGraphicsExtractor g, Font font, Card c, float s, boolean preview, int floor, @Nullable BlockPos pos) {
        int sw = g.guiWidth(), sh = g.guiHeight();
        int w = c.w, h = 14 + c.rows.length * 10;
        // The frame and the pin above it.
        int bw = w + 9, bh = h + 7;
        float W = bw * s, H = bh * s;
        // Raised above the requests toast when it shows; the side is tested where the note is then drawn.
        int shiftY = floor == Integer.MAX_VALUE ? 0 : Math.min(0, floor - Math.round(sh / 2f + H / 2f));
        boolean r;
        if (preview) {
            r = true;
        } else {
            float rx = sw / 2f + 10, lx = sw / 2f - 10 - W, by = sh / 2f - H / 2f + shiftY;
            // Decided again when the station, screen, scale or toast change, and when a piece now covers the side it is on
            // (one that was only passing, like a look-at card fading out, may have pushed it there).
            if (!sideDecided || !pos.equals(sidePos) || sw != sideW || sh != sideH || floor != sideFloor || s != sideScale
                    || HudConfig.overlapsOthers(HudElement.NOTE, right ? rx : lx, by, W, H)) {
                if (rx + W <= sw - 2 && !HudConfig.overlapsOthers(HudElement.NOTE, rx, by, W, H)) right = true;
                else right = !(lx >= 2 && !HudConfig.overlapsOthers(HudElement.NOTE, lx, by, W, H));
                sideDecided = true;
                sidePos = pos;
                sideW = sw;
                sideH = sh;
                sideFloor = floor;
                sideScale = s;
            }
            r = right;
        }
        int shiftX = r ? Math.round(W / 2f) + 10 : -(Math.round(W / 2f) + 10);
        if (!HudConfig.begin(g, HudElement.NOTE, bw, bh, shiftX, shiftY)) return;
        g.fill(0, 2, bw, bh, EDGE);
        g.fill(1, 3, bw - 1, bh - 1, PAPER);
        g.fill(3, 0, 7, 4, 0xFFB03A2E); // the pin
        g.text(font, c.title, 4, 6, INK, false);
        g.fill(4, 16, 4 + w, 17, 0x40000000);
        int ly = 20;
        for (int i = 0; i < c.rows.length; i++) {
            g.text(font, c.rows[i], 4, ly, c.colors[i], false);
            ly += 10;
        }
        HudConfig.end(g);
    }

    /** Works out the card's lines, width and wrapped rows (no rows: nothing to show). */
    private static Card build(Minecraft mc, Font font, BlockPos pos, BlockState state, StationBlock sb, @Nullable Object be, int room, String lang,
                              boolean uni) {
        List<Line> lines = new ArrayList<>();
        long now = mc.level.getGameTime();
        if (be instanceof StationBE st) lines(st, now, lines);
        else lines.add(new Line(Component.translatable("aftrhrs.smithing.note." + sb.station.lang()), FADED));
        return layout(font, Component.translatable(state.getBlock().getDescriptionId()), lines, mc.level, pos.immutable(), state, be, room, lang, uni);
    }

    /** Lays out a note's title and lines at most {@code room} wide. */
    private static Card layout(Font font, Component title, List<Line> lines, @Nullable Object level, BlockPos pos, @Nullable BlockState state,
                               @Nullable Object be, int room, String lang, boolean uni) {
        int w = font.width(title);
        for (Line l : lines) w = Math.max(w, font.width(l.text()));
        w = Math.min(w, Math.min(220, room));
        // Long notes wrap (keeping their colors and styles) instead of running off the card.
        List<FormattedCharSequence> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Line l : lines) {
            for (FormattedCharSequence row : font.split(l.text(), w)) {
                rows.add(row);
                colors.add(l.color());
            }
        }
        int[] cols = new int[colors.size()];
        for (int i = 0; i < cols.length; i++) cols[i] = colors.get(i);
        return new Card(level, pos, state, be, clientTicks, room, lang, uni, Theme.clip(font, title, w), w,
                rows.toArray(new FormattedCharSequence[0]), cols);
    }

    private static Component heat(float t) {
        return Heat.describe(t);
    }

    private static Component part(String id) {
        return Component.translatable("aftrhrs.smithing.part." + id);
    }

    private static Component mat(String id) {
        return Component.translatable("aftrhrs.smithing.material." + id);
    }

    private static void lines(StationBE be, long now, List<Line> out) {
        switch (be) {
            case ForgeBE f -> {
                out.add(new Line(Component.translatable("aftrhrs.smithing.note.heat", heat(f.heat())), INK));
                if (!f.fuel().isEmpty()) out.add(new Line(Component.literal(f.fuel().getCount() + "× ").append(f.fuel().getHoverName()), FADED));
                if (!f.lit() && !f.fuel().isEmpty()) out.add(new Line(Component.translatable("aftrhrs.smithing.note.forge.light"), WARN));
                else if (f.fuel().isEmpty() && !f.lit()) out.add(new Line(Component.translatable("aftrhrs.smithing.note.forge.fuel"), WARN));
                if (f.pumping()) out.add(new Line(Component.translatable("aftrhrs.smithing.note.forge.bellows"), GOOD));
                for (int i = 0; i < ForgeBE.SLOTS; i++) {
                    ItemStack w = f.work(i);
                    if (w.isEmpty()) continue;
                    float t = Heat.of(w, now);
                    int[] win = ForgeBE.windowOf(w);
                    boolean in = win != null && t >= win[0] && t <= win[1];
                    MutableComponent l = w.getHoverName().copy().append(" ").append(Component.literal(Math.round(t) + "°"));
                    if (win != null) l.append(Component.literal(in ? "  ✔" : t > win[1] ? "  ▲" : "  ▼"));
                    out.add(new Line(l, in ? GOOD : t > (win == null ? 9999 : win[1]) ? WARN : INK));
                }
            }
            case GreatCrucibleBE g -> {
                if (!g.formed()) {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.great.build"), WARN));
                    return;
                }
                melt(g, out);
            }
            case MeltBE m -> melt(m, out);
            case CastingBE c -> {
                ItemStack mold = c.mold();
                if (mold.isEmpty() && c.clay() == 0) out.add(new Line(Component.translatable("aftrhrs.smithing.note.cast.empty"), FADED));
                else if (mold.is(Smithing.MOLDING_BOX)) {
                    out.add(new Line(c.sand() < 4 ? Component.translatable("aftrhrs.smithing.note.cast.sand", c.sand())
                            : Component.translatable("aftrhrs.smithing.note.cast.press"), INK));
                } else if (c.clay() > 0) {
                    out.add(new Line(c.clay() < 4 ? Component.translatable("aftrhrs.smithing.note.cast.clay", c.clay())
                            : Component.translatable("aftrhrs.smithing.note.cast.press"), INK));
                } else {
                    out.add(new Line(mold.getHoverName(), INK));
                    if (mold.getItem() instanceof MoldItem mi && mi.kind == MoldItem.Kind.CLAY_UNFIRED) {
                        out.add(new Line(Component.translatable("aftrhrs.smithing.mold.fire"), WARN));
                    } else if (c.needed() > 0) {
                        if (c.units() == 0) out.add(new Line(Component.translatable("aftrhrs.smithing.note.cast.pour", c.needed()), FADED));
                        else out.add(new Line(Component.translatable("aftrhrs.smithing.note.cast.fill", mat(c.fluid()), c.units(), c.needed()), INK));
                        if (c.full()) out.add(new Line(c.cool() > 0 ? Component.translatable("aftrhrs.smithing.note.cast.cooling", c.cool() / 20 + 1)
                                : Component.translatable("aftrhrs.smithing.note.cast.ready"), c.cool() > 0 ? FADED : GOOD));
                    }
                }
            }
            case AnvilBE a -> {
                ItemStack piece = a.piece();
                if (piece.isEmpty()) {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.anvil.empty"), FADED));
                    return;
                }
                float t = Heat.of(piece, now);
                int[] win = ForgeBE.windowOf(piece);
                out.add(new Line(piece.getHoverName().copy().append(piece.getCount() > 1 ? " ×" + piece.getCount() : ""), INK));
                if (win != null) {
                    boolean cold = t < win[0];
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.anvil.heat", Math.round(t), win[0], win[1]), cold ? WARN : GOOD));
                    if (cold) out.add(new Line(Component.translatable("aftrhrs.smithing.note.anvil.reheat"), WARN));
                }
                if (!(piece.getItem() instanceof PartItem) && !a.target().isEmpty()) {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.anvil.target",
                            a.target().equals(AnvilBE.STEEL) ? mat("steel") : part(a.target())), INK));
                }
                List<String> plan = a.plan();
                if (!plan.isEmpty()) {
                    MutableComponent p = Component.empty();
                    for (int i = 0; i < plan.size(); i++) {
                        if (i > 0) p.append(" · ");
                        MutableComponent mv = Component.translatable("aftrhrs.smithing.move." + plan.get(i));
                        p.append(i < a.move() ? mv.withStyle(s -> s.withStrikethrough(true).withColor(0x5E4B3A))
                                : i == a.move() ? mv.withStyle(s -> s.withBold(true).withColor(0xA8321E)) : mv);
                    }
                    out.add(new Line(p, INK));
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.anvil.how"), FADED));
                } else if (!(piece.getItem() instanceof PartItem)) {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.anvil.choose"), FADED));
                }
            }
            case TroughBE t -> {
                if (t.fill() == 0) out.add(new Line(Component.translatable("aftrhrs.smithing.note.trough.empty"), FADED));
                else {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.trough.liquid",
                            Component.translatable("aftrhrs.smithing.liquid." + t.liquid()), t.fill(), TroughBE.MAX), INK));
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.trough." + t.liquid()), FADED));
                }
            }
            case CarvingBE c -> {
                if (c.stock().isEmpty()) {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.carve.empty"), FADED));
                    return;
                }
                out.add(new Line(Component.literal(c.stock().getCount() + "× ").append(c.stock().getHoverName()), INK));
                if (c.target().isEmpty()) {
                    out.add(new Line(Component.translatable(c.stock().getItem() instanceof PartItem ? "aftrhrs.smithing.note.carve.wrap"
                            : "aftrhrs.smithing.note.carve.more"), FADED));
                } else {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.carve.target", part(c.target()), c.strikes(), c.needed()), INK));
                    SmithingDefs.Material m = CarvingBE.materialOf(c.stock());
                    boolean chisel = m != null && (m.id().equals("stone") || m.id().equals("flint"));
                    out.add(new Line(Component.translatable(chisel ? "aftrhrs.smithing.note.carve.chisel" : "aftrhrs.smithing.note.carve.knife"), FADED));
                }
            }
            case AssemblyBE a -> {
                if (!a.tool().isEmpty()) {
                    out.add(new Line(a.tool().getHoverName(), INK));
                    out.add(new Line(a.repairUnits() > 0 ? Component.translatable("aftrhrs.smithing.note.assembly.tap")
                            : Component.translatable("aftrhrs.smithing.note.assembly.tool"), FADED));
                    return;
                }
                SmithingDefs.ToolType head = a.heading();
                if (head == null) {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.assembly.empty"), FADED));
                    return;
                }
                out.add(new Line(Component.translatable("aftrhrs.smithing.note.assembly.heading", Component.translatable("aftrhrs.smithing.tool." + head.id())), INK));
                for (int i = 0; i < head.parts().size(); i++) {
                    boolean have = i < a.parts().size();
                    out.add(new Line(Component.literal(have ? "✔ " : i == a.parts().size() ? "➜ " : "· ").append(part(head.parts().get(i).part())),
                            have ? GOOD : i == a.parts().size() ? INK : FADED));
                }
                if (a.complete() != null) out.add(new Line(Component.translatable("aftrhrs.smithing.note.assembly.rivet"), GOOD));
            }
            case RuneTableBE r -> {
                ItemStack tool = r.tool();
                if (tool.isEmpty()) {
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.rune.empty"), FADED));
                    return;
                }
                out.add(new Line(tool.getHoverName(), INK));
                ToolBuild b = tool.get(Smithing.BUILD);
                if (b != null) {
                    int[] cap = Mods.capacity(b), used = Mods.used(b);
                    out.add(new Line(Component.translatable("aftrhrs.smithing.note.rune.slots", cap[0] - used[0], cap[1] - used[1], cap[2] - used[2]), FADED));
                }
                SmithingDefs.Modifier m = r.match();
                if (m == null) out.add(new Line(Component.translatable("aftrhrs.smithing.note.rune.catalysts"), FADED));
                else {
                    String why = b == null ? null : Mods.refuse(b, m);
                    MutableComponent l = Component.translatable("aftrhrs.smithing.modifier." + m.id()).copy();
                    if (m.incremental() && b != null) l.append(" " + b.progress("mod:" + m.id()) + "/" + m.per().count());
                    out.add(new Line(l, why == null ? GOOD : WARN));
                    if (why != null) out.add(new Line(Component.translatable(why, Component.translatable("aftrhrs.smithing.modifier." + m.id())), WARN));
                    else out.add(new Line(Component.translatable("aftrhrs.smithing.note.rune.etch", r.etch(), RuneTableBE.ETCH), FADED));
                }
            }
            case WheelBE w -> out.add(new Line(Component.translatable("aftrhrs.smithing.note.wheel"), FADED));
            case SpoutBE sp -> out.add(new Line(sp.pouring() ? Component.translatable("aftrhrs.smithing.note.spout.pouring", mat(sp.fluid()))
                    : Component.translatable("aftrhrs.smithing.note.spout"), sp.pouring() ? GOOD : FADED));
            default -> {
            }
        }
    }

    private static void melt(MeltBE m, List<Line> out) {
        out.add(new Line(Component.translatable("aftrhrs.smithing.note.heat", heat(m.shownHeat())), INK));
        if (m.total() == 0 && m.solids().isEmpty()) {
            out.add(new Line(Component.translatable("aftrhrs.smithing.note.crucible.empty", m.capacity() / Smelt.INGOT), FADED));
            return;
        }
        for (var e : m.fluids().entrySet()) {
            out.add(new Line(Component.translatable("aftrhrs.smithing.note.crucible.fluid", Component.translatable("aftrhrs.smithing.fluid." + e.getKey()),
                    e.getValue(), m.capacity()), INK));
        }
        for (ItemStack s : m.solids()) {
            List<Smelt.Melt> melt = Smelt.melt(s);
            boolean hot = melt.isEmpty() || m.shownHeat() >= Smelt.meltHeat(melt.get(0).fluid());
            out.add(new Line(Component.literal(s.getCount() + "× ").append(s.getHoverName()).append(melt.isEmpty() ? ""
                    : hot ? "" : " (" + Smelt.meltHeat(melt.get(0).fluid()) + "°)"), hot ? FADED : WARN));
        }
        SmithingDefs.Alloy a = m.readyAlloy();
        if (a != null) out.add(new Line(Component.translatable("aftrhrs.smithing.note.crucible.ready", mat(a.id()), m.stirs()), GOOD));
    }
}
