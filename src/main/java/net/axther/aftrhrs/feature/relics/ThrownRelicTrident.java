package net.axther.aftrhrs.feature.relics;

import net.axther.aftrhrs.mixin.ThrownTridentAccessor;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;

/**
 * A thrown relic trident (the Tidecaller): vanilla trident behavior (loyalty, riptide-free throw, damage, pickup) but
 * its item is synced, so the client draws the relic's own thrown model ({@code mechanics.trident.appearance}) instead
 * of the vanilla trident. Spawned by {@code TridentItemMixin} for content tridents with that appearance.
 */
public class ThrownRelicTrident extends ThrownTrident {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(ThrownRelicTrident.class, EntityDataSerializers.ITEM_STACK);

    public ThrownRelicTrident(EntityType<? extends ThrownRelicTrident> type, Level level) {
        super(type, level);
    }

    /** The {@code Projectile.ProjectileFactory} shape: thrown by {@code owner} from its eyes. */
    public ThrownRelicTrident(ServerLevel level, LivingEntity owner, ItemStack item) {
        this(RelicsInit.THROWN_RELIC_TRIDENT, level);
        setPickupItemStack(item.copy());
        setPos(owner.getX(), owner.getEyeY() - 0.1f, owner.getZ());
        setOwner(owner);
        syncFromItem(item);
    }

    private void syncFromItem(ItemStack item) {
        entityData.set(DATA_ITEM, item.copyWithCount(1));
        if (level() instanceof ServerLevel sl) {
            entityData.set(ThrownTridentAccessor.aftrhrs$loyalty(),
                    (byte) Mth.clamp(EnchantmentHelper.getTridentReturnToOwnerAcceleration(sl, item, this), 0, 127));
        }
        entityData.set(ThrownTridentAccessor.aftrhrs$foil(), item.hasFoil());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ITEM, ItemStack.EMPTY);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        syncFromItem(getPickupItemStackOrigin());
    }

    /** The relic as it was thrown (for the renderer). */
    public ItemStack relic() {
        return entityData.get(DATA_ITEM);
    }
}
