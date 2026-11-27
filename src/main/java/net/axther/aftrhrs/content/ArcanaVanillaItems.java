package net.axther.aftrhrs.content;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Content items whose Oraxen base material had vanilla behavior (rods, tridents, ...) and block items. */
final class ArcanaVanillaItems {
    private ArcanaVanillaItems() {
    }

    static final class RodItem extends FishingRodItem {
        private final JsonObject name;

        RodItem(Properties p, JsonObject entry) {
            super(p);
            this.name = entry.getAsJsonObject("name");
        }

        @Override
        public Component getName(ItemStack stack) {
            return Texts.restyle(super.getName(stack), name);
        }
    }

    static final class SpearItem extends TridentItem {
        private final JsonObject name;

        SpearItem(Properties p, JsonObject entry) {
            super(p);
            this.name = entry.getAsJsonObject("name");
        }

        @Override
        public Component getName(ItemStack stack) {
            return Texts.restyle(super.getName(stack), name);
        }
    }

    static final class StickItem extends FoodOnAStickItem<Pig> {
        private final JsonObject name;

        StickItem(Properties p, JsonObject entry) {
            super(EntityTypes.PIG, 7, p);
            this.name = entry.getAsJsonObject("name");
        }

        @Override
        public Component getName(ItemStack stack) {
            return Texts.restyle(super.getName(stack), name);
        }
    }

    /** Block item of a content block (furniture, blocks, stairs, slabs, the lit hearth aliases). */
    static final class ContentBlockItem extends BlockItem {
        private final JsonObject name;

        ContentBlockItem(Block block, Properties p, JsonObject entry) {
            super(block, p);
            this.name = entry.getAsJsonObject("name");
        }

        @Override
        public Component getName(ItemStack stack) {
            return Texts.restyle(super.getName(stack), name);
        }

        /** Sacks keep their contents in the item, so (like shulker boxes) they don't go inside other containers' items. */
        @Override
        public boolean canFitInsideContainerItems() {
            if (getBlock() instanceof FurnitureBlock fb && fb.spec.storage != null && fb.spec.storage.type() == FurnitureSpec.StorageType.SHULKER) return false;
            return super.canFitInsideContainerItems();
        }

        /**
         * A multi-block piece put against a ceiling hangs from it: its origin moves down so its top part sits under the
         * clicked block (an arch quarter's upper cell used to land inside the ceiling, so roof placement always failed).
         */
        @Override
        public @Nullable BlockPlaceContext updatePlacementContext(BlockPlaceContext ctx) {
            if (getBlock() instanceof FurnitureBlock fb && fb.spec.roof && fb.spec.multiPart()
                    && !ctx.replacingClickedOnBlock() && ctx.getClickedFace() == Direction.DOWN) {
                int up = 0;
                for (BlockPos c : fb.spec.parts) up = Math.max(up, c.getY());
                if (up > 0) return new Hanging(ctx, ctx.getClickedPos().below(up));
            }
            return ctx;
        }
    }

    /** A roof placement whose origin is {@code origin} (still a roof click: face DOWN, not replacing). */
    static final class Hanging extends BlockPlaceContext {
        Hanging(BlockPlaceContext c, BlockPos origin) {
            super(c.getLevel(), c.getPlayer(), c.getHand(), c.getItemInHand(),
                    new BlockHitResult(c.getClickLocation(), Direction.DOWN, origin.above(), false));
            replaceClicked = false;
        }
    }
}
