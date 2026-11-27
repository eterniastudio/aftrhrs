package net.axther.aftrhrs.client.smithing;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.ToolStats;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Smith's Almanac (SMITHING.md 8): chapters down the left (getting started, stations, materials, tools, parts,
 * modifiers, alloys, heat and fuel), the page on the right. Every number comes from the same data files the server
 * uses, so the book can never disagree with the game.
 */
public final class AlmanacScreen extends Screen {
    private static final int PAPER = 0xFFF1E7CF, PAPER_DARK = 0xFFE2D3B0, LEATHER = 0xFF5A3720, INK = 0xFF3B2A1C, FADED = 0xFF7A6650,
            RED = 0xFF9A2E1E, GOLD = 0xFF9A6A12;
    /** A hovered chapter tab; a hovered entry row (a faint wash of PAPER_DARK). */
    private static final int PAPER_HOVER = 0xFFEADBB9, ROW_HOVER = 0x40E2D3B0;
    /** The chapter list's width (the page starts right of it). */
    private static final int LIST_W = 132;

    private record Entry(Component title, ItemStack icon, List<Component> body) {
    }

    private record Chapter(String key, ItemStack icon, List<Entry> entries) {
    }

    private final List<Chapter> chapters = new ArrayList<>();
    private int chapter, entry;
    private double scroll;
    private int bx, by, bw, bh;
    /** How far the page can scroll, its visible height and top (set while drawing). */
    private int maxScroll, view, pageTop;
    /** The page's scroll bar is being dragged. */
    private boolean draggingBar;

    public AlmanacScreen() {
        super(Component.translatable("item.aftrhrs.smiths_almanac"));
        build();
    }

    private static Component t(String key, Object... args) {
        return Component.translatable("aftrhrs.smithing.almanac." + key, args);
    }

    private static Component mat(String id) {
        return Component.translatable("aftrhrs.smithing.material." + id);
    }

    private static Component part(String id) {
        return Component.translatable("aftrhrs.smithing.part." + id);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, v == Math.round(v) ? "%.0f" : "%.2f", v);
    }

    private void build() {
        List<Entry> start = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            start.add(new Entry(t("start." + i + ".title"), ItemStack.EMPTY, List.of(t("start." + i))));
        }
        chapters.add(new Chapter("start", new ItemStack(Smithing.ALMANAC), start));

        List<Entry> stations = new ArrayList<>();
        for (Station s : Station.values()) {
            stations.add(new Entry(Component.translatable("block.aftrhrs." + s.id), new ItemStack(Smithing.block(s)), List.of(t("station." + s.lang()))));
        }
        for (var item : new net.minecraft.world.item.Item[]{Smithing.TONGS, Smithing.KNIFE, Smithing.CHISEL, Smithing.STIRRING_ROD, Smithing.LADLE_ITEM, Smithing.MOLDING_BOX}) {
            ItemStack s = new ItemStack(item);
            stations.add(new Entry(s.getHoverName(), s, List.of(Component.translatable(item.getDescriptionId() + ".desc"))));
        }
        chapters.add(new Chapter("stations", new ItemStack(Smithing.block(Station.ANVIL)), stations));

        List<Entry> mats = new ArrayList<>();
        for (SmithingDefs.Material m : SmithingDefs.materials().values()) {
            List<Component> body = new ArrayList<>();
            body.add(t("material.tier", m.tier(), Component.translatable("aftrhrs.smithing.kind." + m.kind())));
            if (m.head() != null) body.add(t("material.head", m.head().durability(), f(m.head().speed()),
                    Component.translatable("aftrhrs.smithing.tier." + m.head().tier()), f(m.head().attack())));
            if (m.handle() != null) body.add(t("material.handle", f(m.handle().durability()), f(m.handle().speed()), f(m.handle().attack()),
                    f(m.handle().attackSpeed())));
            if (m.limb() != null) body.add(t("material.limb", m.limb().durability(), f(m.limb().drawSpeed()), f(m.limb().velocity()), f(m.limb().damage())));
            if (m.plating() != null) body.add(t("material.plating", f(m.plating().armor()), f(m.plating().toughness())));
            if (m.window() != null) body.add(t("material.heat", m.melt(), m.window()[0], m.window()[1]));
            String trait = m.trait("head");
            if (trait != null) {
                body.add(Component.translatable("aftrhrs.smithing.trait." + trait).copy().withColor(GOLD & 0xFFFFFF));
                body.add(Component.translatable("aftrhrs.smithing.trait." + trait + ".desc"));
            }
            body.add(t("material.source." + m.id()));
            ItemStack icon = Smithing.part(m.head() != null ? "pick_head" : m.fits("handle") ? "handle" : m.fits("string") ? "bowstring" : "fletching", m.id(), 3);
            mats.add(new Entry(mat(m.id()), icon, body));
        }
        chapters.add(new Chapter("materials", Smithing.part("pick_head", "steel", 3), mats));

        List<Entry> tools = new ArrayList<>();
        for (SmithingDefs.ToolType t : SmithingDefs.tools().values()) {
            List<Component> body = new ArrayList<>();
            MutableComponent ps = Component.empty();
            for (int i = 0; i < t.parts().size(); i++) {
                if (i > 0) ps.append(" + ");
                ps.append(part(t.parts().get(i).part()));
            }
            body.add(t("tool.parts", ps));
            if (!t.kind().equals("ammo")) body.add(t("tool.slots", t.slots()[0], t.slots()[1], t.slots()[2]));
            body.add(Component.translatable("aftrhrs.smithing.tool." + t.id() + ".desc"));
            ItemStack sample = Smithing.tool(t.id(), Smithing.materialsFor(t, new String[]{"iron", "leather", "wood"}), List.of(3));
            if (!sample.isEmpty() && !t.kind().equals("ammo") && !t.kind().equals("armor") && !t.kind().equals("shield")) {
                ToolStats.Stats st = ToolStats.compute(sample.get(Smithing.BUILD));
                body.add(t("tool.sample", st.durability(), f(st.attackDamage()), f(st.miningSpeed())));
            }
            tools.add(new Entry(Component.translatable("aftrhrs.smithing.tool." + t.id()), sample, body));
        }
        chapters.add(new Chapter("tools", Smithing.tool("pickaxe", List.of("steel", "leather", "wood"), List.of(4)), tools));

        List<Entry> parts = new ArrayList<>();
        for (SmithingDefs.Part p : SmithingDefs.parts().values()) {
            List<Component> body = new ArrayList<>();
            body.add(Component.translatable("aftrhrs.smithing.role." + p.role(), p.cost()));
            if (p.makes() > 1) body.add(t("part.makes", p.makes()));
            MutableComponent made = Component.empty();
            for (int i = 0; i < p.made().size(); i++) {
                if (i > 0) made.append(", ");
                made.append(t("made." + p.made().get(i)));
            }
            body.add(t("part.made", made));
            if (!p.plan().isEmpty()) {
                MutableComponent plan = Component.empty();
                for (int i = 0; i < p.plan().size(); i++) {
                    if (i > 0) plan.append(" · ");
                    plan.append(Component.translatable("aftrhrs.smithing.move." + p.plan().get(i)));
                }
                body.add(t("part.plan", plan));
            }
            parts.add(new Entry(part(p.id()), Smithing.part(p.id(), p.role().equals("string") ? "silk" : p.role().equals("fletching") ? "down_feather" : "iron", 3), body));
        }
        chapters.add(new Chapter("parts", Smithing.part("blade", "iron", 3), parts));

        List<Entry> mods = new ArrayList<>();
        for (SmithingDefs.Modifier m : SmithingDefs.modifiers().values()) {
            List<Component> body = new ArrayList<>();
            body.add(t("modifier.slot", m.slot().isEmpty() ? t("slot.none") : t("slot." + m.slot().toLowerCase(Locale.ROOT)), m.max()));
            if (m.per() != null) body.add(t("modifier.per", m.per().count(), need(m.per())));
            else {
                MutableComponent needs = Component.empty();
                for (int i = 0; i < m.needs().size(); i++) {
                    if (i > 0) needs.append(", ");
                    needs.append(m.needs().get(i).count() + "× ").append(need(m.needs().get(i)));
                }
                body.add(t("modifier.needs", needs));
            }
            body.add(Component.translatable("aftrhrs.smithing.modifier." + m.id() + ".desc"));
            mods.add(new Entry(Component.translatable("aftrhrs.smithing.modifier." + m.id()), ItemStack.EMPTY, body));
        }
        chapters.add(new Chapter("modifiers", new ItemStack(Smithing.block(Station.RUNE_TABLE)), mods));

        List<Entry> alloys = new ArrayList<>();
        for (SmithingDefs.Alloy a : SmithingDefs.alloys().values()) {
            List<Component> body = new ArrayList<>();
            MutableComponent in = Component.empty();
            boolean first = true;
            for (var e : a.inputs().entrySet()) {
                if (!first) in.append(" + ");
                first = false;
                in.append(e.getValue() + " ").append(mat(e.getKey()));
            }
            for (SmithingDefs.Additive add : a.additives()) {
                in.append(" + " + add.count() + "× ").append(itemName(add.items().get(0)));
            }
            body.add(t("alloy.recipe", in, a.units(), mat(a.id())));
            body.add(t("alloy.heat", a.heat()));
            if (a.great()) body.add(t("alloy.great").copy().withColor(RED & 0xFFFFFF));
            alloys.add(new Entry(mat(a.id()), Smelt.ingot(a.id(), false), body));
        }
        chapters.add(new Chapter("alloys", new ItemStack(Smithing.block(Station.CRUCIBLE)), alloys));

        List<Entry> heat = new ArrayList<>();
        heat.add(new Entry(t("heat.fuel.title"), new ItemStack(net.minecraft.world.item.Items.COAL), List.of(t("heat.fuel"))));
        heat.add(new Entry(t("heat.colors.title"), ItemStack.EMPTY, List.of(t("heat.colors"))));
        heat.add(new Entry(t("heat.quality.title"), ItemStack.EMPTY, List.of(t("heat.quality"))));
        heat.add(new Entry(t("heat.quench.title"), new ItemStack(Smithing.block(Station.TROUGH)), List.of(t("heat.quench"))));
        chapters.add(new Chapter("heat", new ItemStack(Smithing.block(Station.FORGE)), heat));
    }

    private static Component itemName(String id) {
        var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(id));
        return it.map(i -> new ItemStack(i).getHoverName()).orElse(Component.literal(id));
    }

    private static Component need(SmithingDefs.Need n) {
        if (n.item() != null) return itemName(n.item());
        return Component.translatable("aftrhrs.smithing.tag." + n.tag().replace(':', '.').replace('/', '.'));
    }

    @Override
    protected void init() {
        // The chapter tabs stand 22px above the paper and the leather rim 6px around it: all of it stays on screen.
        bw = Math.min(width - 20, 420);
        bh = Math.min(height - 36, 240);
        bx = (width - bw) / 2;
        by = (height - bh) / 2 + 8;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.fill(bx - 6, by - 6, bx + bw + 6, by + bh + 6, LEATHER);
        g.fill(bx, by, bx + bw, by + bh, PAPER);
        g.fill(bx + LIST_W, by + 6, bx + LIST_W + 1, by + bh - 6, PAPER_DARK);
        int ht = hoveredTab(mouseX, mouseY), he = hoveredEntry(mouseX, mouseY);
        // chapter tabs along the top
        int tx = bx + 4;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            boolean sel = i == chapter;
            g.fill(tx, by - 22, tx + 20, by - (sel ? 0 : 4), sel ? PAPER : i == ht ? PAPER_HOVER : PAPER_DARK);
            g.item(c.icon(), tx + 2, by - 20);
            if (i == ht) {
                if (!sel) g.requestCursor(CursorTypes.POINTING_HAND);
                g.setTooltipForNextFrame(font, t("chapter." + c.key()), mouseX, mouseY);
            }
            tx += 22;
        }
        Chapter c = chapters.get(chapter);
        g.text(font, net.axther.aftrhrs.client.Theme.clip(font, t("chapter." + c.key()), LIST_W - 12), bx + 6, by + 6, RED, false);
        // entries
        int ly = by + 20;
        int first = firstEntry(c);
        for (int i = first; i < c.entries().size() && i < first + listRows(); i++) {
            Entry e = c.entries().get(i);
            boolean sel = i == entry;
            boolean hov = !sel && i == he;
            if (sel) g.fill(bx + 3, ly - 1, bx + LIST_W - 3, ly + 10, PAPER_DARK);
            else if (hov) g.fill(bx + 3, ly - 1, bx + LIST_W - 3, ly + 10, ROW_HOVER);
            if (hov) g.requestCursor(CursorTypes.POINTING_HAND);
            g.text(font, net.axther.aftrhrs.client.Theme.ellipsize(font, e.title().getString(), LIST_W - 12), bx + 6, ly, sel || hov ? INK : FADED, false);
            ly += 11;
        }
        // the page
        Entry e = c.entries().isEmpty() ? null : c.entries().get(Mth.clamp(entry, 0, c.entries().size() - 1));
        if (e == null) return;
        int px = bx + LIST_W + 8, pw = bw - LIST_W - 14, py = by + 8;
        if (!e.icon().isEmpty()) {
            g.item(e.icon(), px, py);
            g.text(font, net.axther.aftrhrs.client.Theme.clip(font, e.title(), pw - 20), px + 20, py + 4, INK, false);
        } else {
            g.text(font, net.axther.aftrhrs.client.Theme.clip(font, e.title(), pw), px, py + 4, INK, false);
        }
        int pageH = 0;
        for (Component line : e.body()) pageH += font.split(line, pw).size() * 10 + 3;
        view = by + bh - 10 - (py + 22);
        maxScroll = Math.max(0, pageH - view);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        int y = py + 22 - (int) scroll;
        for (Component line : e.body()) {
            for (FormattedCharSequence seq : font.split(line, pw)) {
                if (y >= py + 18 && y < by + bh - 10) g.text(font, seq, px, y, INK, false);
                y += 10;
            }
            y += 3;
        }
        // the page's scroll bar, in the margin right of the text (parchment colors)
        pageTop = py + 22;
        net.axther.aftrhrs.client.Theme.scrollbar(g, bx + bw - 5, pageTop, 3, view, scroll, pageH, view, PAPER_DARK, FADED);
    }

    /** The chapter tab under the pointer, or -1 (the same rectangles the tabs are drawn in). */
    private int hoveredTab(double mx, double my) {
        if (my < by - 22 || my >= by) return -1;
        for (int i = 0; i < chapters.size(); i++) {
            int tx = bx + 4 + i * 22;
            if (mx >= tx && mx < tx + 20) return i;
        }
        return -1;
    }

    /** The entry row under the pointer, or -1. */
    private int hoveredEntry(double mx, double my) {
        if (mx < bx + 3 || mx >= bx + LIST_W - 3 || my < by + 19 || my >= by + 19 + listRows() * 11) return -1;
        Chapter c = chapters.get(chapter);
        int i = firstEntry(c) + (int) ((my - by - 19) / 11);
        return i < c.entries().size() ? i : -1;
    }

    /** Whether the pointer is on the page's scroll bar (when there is one). */
    private boolean onPageBar(double mx, double my) {
        return maxScroll > 0 && mx >= bx + bw - 6 && mx < bx + bw && my >= pageTop && my < pageTop + view;
    }

    private void pageTurn(float pitch) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, pitch, 0.5f));
    }

    /** Opens a chapter and entry (clamped); a page turn sounds only when something changed ({@code pitch} 0: silent). */
    private void select(int ch, int en, float pitch) {
        ch = Mth.clamp(ch, 0, chapters.size() - 1);
        en = Mth.clamp(en, 0, Math.max(0, chapters.get(ch).entries().size() - 1));
        if (ch == chapter && en == entry) return;
        chapter = ch;
        entry = en;
        scroll = 0;
        if (pitch > 0) pageTurn(pitch);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        double mx = event.x(), my = event.y();
        int t = hoveredTab(mx, my);
        if (t >= 0) {
            // the open chapter's tab does nothing (and makes no sound)
            select(t, t == chapter ? entry : 0, 1.0f);
            return true;
        }
        int i = hoveredEntry(mx, my);
        if (i >= 0) {
            select(chapter, i, 1.25f);
            return true;
        }
        if (onPageBar(mx, my)) {
            draggingBar = true;
            scroll = net.axther.aftrhrs.client.Theme.scrollAt(my, pageTop, view, view + maxScroll, view);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingBar) {
            scroll = net.axther.aftrhrs.client.Theme.scrollAt(event.y(), pageTop, view, view + maxScroll, view);
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
        if (mx < bx + LIST_W) select(chapter, entry - (int) Math.signum(sy), 0);
        else scroll = Mth.clamp(scroll - sy * 10, 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Up/Down and Home/End: the entries; Left/Right and Tab (Shift: back): the chapters; Page Up/Down: the page.
        int k = event.key();
        int last = Math.max(0, chapters.get(chapter).entries().size() - 1);
        switch (k) {
            case InputConstants.KEY_UP -> select(chapter, entry - 1, 1.25f);
            case InputConstants.KEY_DOWN -> select(chapter, entry + 1, 1.25f);
            case InputConstants.KEY_HOME -> select(chapter, 0, 1.25f);
            case InputConstants.KEY_END -> select(chapter, last, 1.25f);
            case InputConstants.KEY_LEFT -> select(Math.floorMod(chapter - 1, chapters.size()), 0, 1.0f);
            case InputConstants.KEY_RIGHT -> select(Math.floorMod(chapter + 1, chapters.size()), 0, 1.0f);
            case InputConstants.KEY_TAB -> select(Math.floorMod(chapter + (event.hasShiftDown() ? -1 : 1), chapters.size()), 0, 1.0f);
            case InputConstants.KEY_PAGEUP -> scroll = Mth.clamp(scroll - view, 0, maxScroll);
            case InputConstants.KEY_PAGEDOWN -> scroll = Mth.clamp(scroll + view, 0, maxScroll);
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
    }

    /** Entry rows that fit down the left page. */
    private int listRows() {
        return Math.max(1, (bh - 30) / 11);
    }

    /** The first entry shown: the list follows the selection, keeping it near the middle. */
    private int firstEntry(Chapter c) {
        int rows = listRows();
        return Math.max(0, Math.min(entry - rows / 2, c.entries().size() - rows));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
