package net.axther.aftrhrs.feature.smithing;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.axther.aftrhrs.net.RecipeExport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Anvil & Ember settings ({@code smithing.yml}: heat speed, Edge, burns) and the smithing pages in JEI: every alloy,
 * casting, forging plan, carving, assembly and modifier as an Aftr Hrs "Smithing" recipe (the server sends them).
 */
public final class SmithingModule implements FeatherModule {
    private final Feather plugin;

    public SmithingModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Smithing";
    }

    @Override
    public void enable() {
        Yaml c = plugin.loadResourceConfig("smithing.yml");
        Workshop.configure((float) c.getDouble("heat-speed", 1.0), c.getBoolean("edge", true), c.getBoolean("hot-pieces-burn", true));
        RecipeExport.source("smithing", SmithingModule::export);
    }

    @Override
    public void reload() {
        enable();
    }

    @Override
    public void disable() {
    }

    private static net.kyori.adventure.text.Component text(String s) {
        return net.kyori.adventure.text.Component.text(s);
    }

    private static ItemStack item(String id) {
        Item i = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
        return new ItemStack(i);
    }

    private static List<ItemStack> one(ItemStack s) {
        return List.of(s);
    }

    private static String title(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.split("_")) b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    private static void export(RecipeExport.Sink sink) {
        ItemStack crucible = new ItemStack(Smithing.block(Station.CRUCIBLE)), great = new ItemStack(Smithing.block(Station.GREAT_DRAIN));
        ItemStack casting = new ItemStack(Smithing.block(Station.CASTING_BENCH)), anvil = new ItemStack(Smithing.block(Station.ANVIL));
        ItemStack carving = new ItemStack(Smithing.block(Station.CARVING_BENCH)), assembly = new ItemStack(Smithing.block(Station.ASSEMBLY_BENCH));
        ItemStack runes = new ItemStack(Smithing.block(Station.RUNE_TABLE));
        // alloys
        for (SmithingDefs.Alloy a : SmithingDefs.alloys().values()) {
            List<List<ItemStack>> in = new ArrayList<>();
            for (var e : a.inputs().entrySet()) {
                ItemStack ingot = Smelt.ingot(e.getKey(), false);
                if (!ingot.isEmpty()) in.add(one(ingot.copyWithCount(Math.max(1, e.getValue() / Smelt.INGOT))));
            }
            for (SmithingDefs.Additive add : a.additives()) {
                List<ItemStack> any = new ArrayList<>();
                for (String id : add.items()) any.add(item(id).copyWithCount(add.count()));
                in.add(any);
            }
            in.add(one(new ItemStack(Smithing.STIRRING_ROD)));
            ItemStack out = Smelt.ingot(a.id(), false).copyWithCount(Math.max(1, a.units() / Smelt.INGOT));
            sink.machine("aftrhrs:smithing/alloy/" + a.id(), "smithing", a.great() ? great : crucible, in, out,
                    text(a.heat() + "°, stir 3 times" + (a.great() ? " (Great Crucible)" : "")));
        }
        // casting, forging and carving every part (in iron, or its first carvable material)
        for (SmithingDefs.Part p : SmithingDefs.parts().values()) {
            if (p.made().contains("cast")) {
                ItemStack out = Smithing.part(p.id(), "iron", 1);
                if (!out.isEmpty()) {
                    out.set(Smithing.PART, new PartInfo(1, "", "", true));
                    out.setCount(p.makes());
                    sink.machine("aftrhrs:smithing/cast/" + p.id(), "smithing", casting,
                            List.of(one(MoldItem.of(MoldItem.Kind.SAND, p.id())), one(new ItemStack(Items.IRON_INGOT, p.cost())),
                                    one(new ItemStack(Smithing.LADLE_ITEM))), out, text("Pour, cool, knock out with a hammer"));
                }
            }
            if (p.made().contains("anvil") && !p.plan().isEmpty()) {
                ItemStack out = Smithing.part(p.id(), "iron", 5);
                if (!out.isEmpty()) {
                    out.setCount(p.makes());
                    StringBuilder plan = new StringBuilder();
                    for (String m : p.plan()) plan.append(plan.isEmpty() ? "" : " ").append(title(m));
                    sink.machine("aftrhrs:smithing/forge/" + p.id(), "smithing", anvil,
                            List.of(one(new ItemStack(Items.IRON_INGOT, p.cost())), one(Smithing.tool("smithing_hammer", List.of("iron", "iron", "wood"), List.of(3)))),
                            out, text(plan + " (twice from ingots)"));
                }
            }
            if (p.made().contains("carve")) {
                for (SmithingDefs.Material m : SmithingDefs.materials().values()) {
                    if (!m.fits(p.role()) || m.source() == null || !m.source().has("carve")) continue;
                    String src = m.source().getAsJsonArray("carve").get(0).getAsString();
                    ItemStack in = src.startsWith("#") ? new ItemStack(Items.OAK_PLANKS) : item(src);
                    ItemStack out = Smithing.part(p.id(), m.id(), 3);
                    if (in.isEmpty() || out.isEmpty()) continue;
                    out.setCount(p.makes());
                    boolean chisel = m.id().equals("stone") || m.id().equals("flint");
                    sink.machine("aftrhrs:smithing/carve/" + p.id() + "/" + m.id(), "smithing", carving,
                            List.of(one(in.copyWithCount(p.cost())), one(new ItemStack(chisel ? Smithing.CHISEL : Smithing.KNIFE))), out,
                            text("Cut on the beat"));
                }
            }
        }
        // steel on the anvil
        sink.machine("aftrhrs:smithing/fold/steel", "smithing", anvil, List.of(one(new ItemStack(Items.IRON_INGOT)), one(new ItemStack(Items.CHARCOAL))),
                Smelt.ingot("steel", false), text("Hot iron and charcoal: Fold, Fold"));
        // assembly: each tool in iron
        for (SmithingDefs.ToolType t : SmithingDefs.tools().values()) {
            List<String> mats = Smithing.materialsFor(t, new String[]{"iron", "leather", "wood"});
            ItemStack out = Smithing.tool(t.id(), mats, List.of(3));
            if (out.isEmpty()) continue;
            List<List<ItemStack>> in = new ArrayList<>();
            for (int i = 0; i < t.parts().size(); i++) in.add(one(Smithing.part(t.parts().get(i).part(), mats.get(i), 3)));
            sink.machine("aftrhrs:smithing/assemble/" + t.id(), "smithing", assembly, in, out, text("In order, then rivet with the hammer"));
        }
        // modifiers on a sample tool
        for (SmithingDefs.Modifier m : SmithingDefs.modifiers().values()) {
            String tool = m.fits().contains("armor") || m.fits().contains("boots") ? "boots" : m.fits().contains("helmet") ? "helmet"
                    : m.fits().contains("bow") || m.fits().contains("crossbow") ? "shortbow" : m.fits().contains("rod") ? "fishing_rod"
                    : m.fits().contains("spear") ? "spear" : m.fits().contains("melee") ? "sword" : "pickaxe";
            SmithingDefs.ToolType type = SmithingDefs.tool(tool);
            ItemStack base = Smithing.tool(tool, Smithing.materialsFor(type, new String[]{"iron", "leather", "wood"}), List.of(3));
            if (base.isEmpty()) continue;
            ItemStack out = base.copy();
            out.set(Smithing.BUILD, base.get(Smithing.BUILD).withMod(m.id(), 1));
            ToolStats.apply(out);
            List<List<ItemStack>> in = new ArrayList<>();
            in.add(one(base));
            if (m.per() != null) in.add(needStacks(m.per(), m.per().count()));
            else for (SmithingDefs.Need n : m.needs()) in.add(needStacks(n, n.count()));
            in.add(one(new ItemStack(Smithing.CHISEL)));
            sink.machine("aftrhrs:smithing/modifier/" + m.id(), "smithing", runes, in, out,
                    text((m.slot().isEmpty() ? "Slotless" : m.slot() + " slot") + ", up to " + m.max()));
        }
    }

    private static List<ItemStack> needStacks(SmithingDefs.Need n, int count) {
        if (n.item() != null) return one(item(n.item()).copyWithCount(Math.min(64, count)));
        List<ItemStack> out = new ArrayList<>();
        var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, Identifier.parse(n.tag()));
        BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(h -> out.add(new ItemStack(h.value(), Math.min(64, count))));
        return out.isEmpty() ? one(new ItemStack(Items.BARRIER)) : out;
    }
}
