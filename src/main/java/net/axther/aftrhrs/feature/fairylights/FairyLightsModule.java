package net.axther.aftrhrs.feature.fairylights;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Reads {@code fairy-lights.yml} (strand limits, real light) on enable and {@code /feather reload}. */
public final class FairyLightsModule implements FeatherModule {
    private final Feather plugin;

    public FairyLightsModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Fairy lights";
    }

    @Override
    public void enable() {
        FairyLightsConfig.load(plugin.loadResourceConfig("fairy-lights.yml"));
        // the pattern recipe is a special recipe JEI can't show by itself: a few examples on the Aftr Hrs workstation page
        net.axther.aftrhrs.net.RecipeExport.source("fairy_lights.patterns", FairyLightsModule::exportPatterns);
    }

    private static void exportPatterns(net.axther.aftrhrs.net.RecipeExport.Sink sink) {
        ItemStack table = new ItemStack(Items.CRAFTING_TABLE);
        net.kyori.adventure.text.Component note = net.kyori.adventure.text.Component.text(
                "Up to 8 lights (or pennants) with the strand, in reading order: they repeat along it. White, green or black dye sets the wire.");
        for (String preset : List.of("holiday", "candy_cane", "winter", "lanterns", "garland_lit", "bunting_festive")) {
            ItemStack out = FairyLights.preset(preset);
            StrandSpec spec = FairyLights.spec(out);
            List<List<ItemStack>> in = new ArrayList<>();
            ItemStack base = FairyLights.strandItem(((StrandItem) out.getItem()).kind);
            in.add(List.of(base));
            for (StrandSpec.Bulb b : spec.bulbs()) in.add(List.of(BulbItem.stack(b)));
            for (StrandSpec.Flag f : spec.flags()) in.add(List.of(PennantItem.stack(f)));
            if (spec.wire() != StrandSpec.Wire.DARK) {
                in.add(List.of(new ItemStack(Items.DYE.pick(spec.wire() == StrandSpec.Wire.WHITE ? DyeColor.WHITE : DyeColor.GREEN))));
            }
            sink.machine("aftrhrs:fairy_pattern/" + preset, "workstation", table, in, out, note);
        }
    }

    @Override
    public void reload() {
        enable();
    }

    @Override
    public void disable() {
    }
}
