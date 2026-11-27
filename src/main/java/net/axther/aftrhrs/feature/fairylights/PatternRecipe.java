/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/item/crafting/FLCraftingRecipes: hanging lights / pennant bunting augmentation.)
 */
package net.axther.aftrhrs.feature.fairylights;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * A String of Lights (or a Pine Garland) with up to 8 lights (or a Pennant Bunting with up to 8 pennants) anywhere in the grid: the strand
 * gets those, in reading order, as its repeating pattern. A white / green / black dye with a String of Lights sets
 * its wire color.
 */
public final class PatternRecipe extends CustomRecipe {
    public static final int MAX = 8;
    public static final PatternRecipe INSTANCE = new PatternRecipe();
    public static final MapCodec<PatternRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, PatternRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<PatternRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return !assemble(input).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack strand = ItemStack.EMPTY;
        List<StrandSpec.Bulb> bulbs = new ArrayList<>();
        List<StrandSpec.Flag> flags = new ArrayList<>();
        StrandSpec.Wire wire = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof StrandItem si && si.kind != StrandKind.TINSEL) {
                if (!strand.isEmpty()) return ItemStack.EMPTY;
                strand = s;
            } else if (s.getItem() instanceof BulbItem) {
                bulbs.add(BulbItem.bulb(s));
            } else if (s.getItem() instanceof PennantItem pi) {
                flags.add(PennantItem.flag(s, pi));
            } else if (s.is(net.minecraft.tags.ItemTags.DYES) && (s.get(DataComponents.DYE) == DyeColor.WHITE
                    || s.get(DataComponents.DYE) == DyeColor.GREEN || s.get(DataComponents.DYE) == DyeColor.BLACK)) {
                if (wire != null) return ItemStack.EMPTY;
                DyeColor d = s.get(DataComponents.DYE);
                wire = d == DyeColor.WHITE ? StrandSpec.Wire.WHITE : d == DyeColor.GREEN ? StrandSpec.Wire.GREEN : StrandSpec.Wire.DARK;
            } else {
                return ItemStack.EMPTY;
            }
        }
        if (strand.isEmpty() || bulbs.size() > MAX || flags.size() > MAX) return ItemStack.EMPTY;
        StrandKind kind = ((StrandItem) strand.getItem()).kind;
        StrandSpec spec = FairyLights.spec(strand);
        if (kind == StrandKind.GARLAND) {
            if (!flags.isEmpty() || wire != null || bulbs.isEmpty()) return ItemStack.EMPTY;
            spec = spec.withBulbs(bulbs);
        } else if (kind == StrandKind.LIGHTS) {
            if (!flags.isEmpty() || bulbs.isEmpty() && wire == null) return ItemStack.EMPTY;
            if (!bulbs.isEmpty()) spec = spec.withBulbs(bulbs);
            if (wire != null) spec = spec.withWire(wire);
        } else {
            if (!bulbs.isEmpty() || wire != null || flags.isEmpty()) return ItemStack.EMPTY;
            spec = spec.withFlags(flags);
        }
        ItemStack out = strand.copyWithCount(1);
        FairyLights.setSpec(out, spec);
        return out;
    }

    @Override
    public RecipeSerializer<PatternRecipe> getSerializer() {
        return SERIALIZER;
    }
}
