package net.axther.aftrhrs.feature.shop;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A shopkeeper standing in for a counter: the General Store clerk, the fishmonger, the grocer or the miner. Placed with
 * its keeper item ({@link KeeperItem}), it stays where it was put, turns its head to watch players and opens that
 * counter's screen on a right-click (the General Store, or the sell-back screen of {@link SellBack}). The one who
 * placed it, or staff, picks it up again with sneak + right-click.
 *
 * <p>It never moves, can't be pushed, hurt (only {@code /kill}), leashed or converted, and never despawns. Owner and
 * trade are saved with the entity; the trade is also its entity type ({@code aftrhrs:keeper_grocer}...), so the
 * client picks the model from the type.
 */
public class CounterKeeper extends PathfinderMob implements GeoEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

    private final KeeperContent.Trade trade;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private @Nullable UUID owner;

    public CounterKeeper(EntityType<? extends CounterKeeper> type, Level level, KeeperContent.Trade trade) {
        super(type, level);
        this.trade = trade;
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.FOLLOW_RANGE, 10);
    }

    public KeeperContent.Trade trade() {
        return trade;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8f, 1f));
        goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------------ stays put, unharmed

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return super.hurtServer(level, source, amount);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity other) {
    }

    @Override
    protected void doPush(Entity other) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    protected void pickUpItem(ServerLevel level, net.minecraft.world.entity.item.ItemEntity item) {
    }

    @Override
    public boolean canPickUpLoot() {
        return false;
    }

    // ------------------------------------------------------------------ voice

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 400;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
        ShopModule shop = ShopModule.active;
        if (shop == null) return InteractionResult.FAIL;
        return shop.keeperClick(p, this) ? InteractionResult.SUCCESS_SERVER : InteractionResult.FAIL;
    }

    /** Sneak + right-click by its owner or staff: back into an item. */
    boolean mayPickUp(ServerPlayer p, boolean staff) {
        return staff || (owner != null && owner.equals(p.getUUID())) || p.isCreative() && owner == null;
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        if (owner != null) out.store("KeeperOwner", UUIDUtil.CODEC, owner);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        owner = in.read("KeeperOwner", UUIDUtil.CODEC).orElse(null);
        setPersistenceRequired();
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>("main", 0, test -> test.setAndContinue(IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
