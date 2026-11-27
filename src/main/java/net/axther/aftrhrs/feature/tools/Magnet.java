package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Magnet charm: right-click toggles it (a custom-data flag + item model swap). While a toggled-on charm is anywhere in
 * a player's inventory, nearby dropped items drift towards them (never out of a claim where they lack container trust).
 * The flag keeps Paper's key {@code arcanatools:magnet_active} (migrated item data keeps working).
 */
final class Magnet {
    static final String ACTIVE = "arcanatools:magnet_active";
    static final String MODEL_OFF = "arcanatools:magnet_model_off";

    private final ToolsModule plugin;
    private Tasks.Task task;

    private String itemId;
    private Identifier activeModel;
    private double radius, speed;
    private int interval, ownDropTicks;
    private String msgOn, msgOff, sound;

    Magnet(ToolsModule plugin) {
        this.plugin = plugin;
    }

    void load(Yaml c) {
        itemId = ToolsModule.stripNs(c.getString("magnet.item", "arcana_magnet_charm").toLowerCase(Locale.ROOT));
        String model = c.getString("magnet.active-model", "oraxen:arcana_magnet_charm_on");
        activeModel = null;
        if (model != null && !model.isBlank()) {
            String m = model.toLowerCase(Locale.ROOT);
            if (m.startsWith("oraxen:")) m = "aftrhrs:" + m.substring(7); // Oraxen item models are native item definitions now
            activeModel = Identifier.tryParse(m);
            if (activeModel == null) plugin.log().warn("magnet.active-model is not a valid key: {}", model);
        }
        radius = Math.max(1.0, Math.min(16.0, c.getDouble("magnet.radius", 7.0)));
        speed = Math.max(0.05, Math.min(1.5, c.getDouble("magnet.pull-speed", 0.45)));
        interval = Math.max(1, c.getInt("magnet.interval-ticks", 4));
        ownDropTicks = (int) Math.round(Math.max(0, c.getDouble("magnet.ignore-own-drops-seconds", 3.0)) * 20);
        msgOn = c.getString("magnet.message-on", "<#e05a4f>Magnet charm</#e05a4f> <good>on");
        msgOff = c.getString("magnet.message-off", "<#e05a4f>Magnet charm</#e05a4f> <muted>off");
        sound = c.getString("magnet.toggle-sound", "block.note_block.chime");
        stop();
        if (c.getBoolean("magnet.enabled", true)) task = Tasks.timer(interval, interval, this::tick);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    Set<String> configuredIds() {
        return Set.of(itemId);
    }

    /** Right-click with the charm toggles it. True if used. */
    boolean use(ServerPlayer player, InteractionHand hand) {
        if (task == null) return false;
        ItemStack item = player.getItemInHand(hand);
        if (!itemId.equals(ToolsModule.idOf(item))) return false;
        if (plugin.recentlyUsed(player, "magnet", 5)) return true;
        boolean on = !isActive(item);
        CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> {
            if (on) {
                Identifier current = item.get(DataComponents.ITEM_MODEL);
                tag.putString(MODEL_OFF, current == null ? "" : current.toString());
                tag.putByte(ACTIVE, (byte) 1);
            } else {
                tag.remove(ACTIVE);
                tag.remove(MODEL_OFF);
            }
        });
        if (activeModel != null) {
            if (on) item.set(DataComponents.ITEM_MODEL, activeModel);
            else item.set(DataComponents.ITEM_MODEL, item.getPrototype().get(DataComponents.ITEM_MODEL)); // back to the item's own model
        }
        player.setItemInHand(hand, item);
        ToolsModule.actionBar(player, plugin.text(on ? msgOn : msgOff));
        ToolsModule.playSound((ServerLevel) player.level(), player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 0.7f, on ? 1.4f : 0.8f);
        return true;
    }

    private static boolean isActive(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return false;
        CompoundTag t = cd.copyTag();
        return t.getByteOr(ACTIVE, (byte) 0) == 1;
    }

    /**
     * True if the player carries an active charm. Also repairs the look of every active charm whose model was reset
     * (migrated charms lose Oraxen's model component).
     */
    private boolean carriesActiveCharm(ServerPlayer player) {
        Inventory inv = player.getInventory();
        boolean found = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!isActive(stack) || !itemId.equals(ToolsModule.idOf(stack))) continue;
            found = true;
            if (activeModel != null && !activeModel.equals(stack.get(DataComponents.ITEM_MODEL))) {
                stack.set(DataComponents.ITEM_MODEL, activeModel);
            }
        }
        return found;
    }

    private void tick() {
        for (ServerPlayer player : plugin.feather.onlinePlayers()) {
            try {
                if (player.isDeadOrDying() || player.isSpectator() || !carriesActiveCharm(player)) continue;
                pull(player);
            } catch (RuntimeException t) {
                plugin.log().warn("Magnet tick failed for {}: {}", player.getGameProfile().name(), t.toString());
            }
        }
    }

    private void pull(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        Vec3 target = player.position().add(0, 0.6, 0);
        UUID uuid = player.getUUID();
        Inventory inv = player.getInventory();
        boolean hasEmpty = inv.getFreeSlot() != -1;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(target, target).inflate(radius))) {
            if (!item.isAlive() || item.hasPickUpDelay()) continue;
            Entity thrower = item.getOwner();
            if (thrower != null && uuid.equals(thrower.getUUID()) && item.getAge() < ownDropTicks) continue;
            ItemStack stack = item.getItem();
            if (stack.isEmpty() || (!hasEmpty && !hasRoomFor(inv, stack))) continue;
            Vec3 delta = target.subtract(item.position());
            double dist = delta.length();
            if (dist < 0.8 || dist > radius + 0.5) continue;
            if (!plugin.claimAllows(player, level, item.blockPosition(), ClaimAction.CONTAINER)) continue;
            Vec3 vel = delta.normalize().scale(Math.min(speed, 0.15 + dist * 0.1)).add(0, 0.04, 0);
            item.setDeltaMovement(vel);
            item.syncVelocity = true;
        }
    }

    private static boolean hasRoomFor(Inventory inv, ItemStack stack) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) return true;
            if (s.getCount() < s.getMaxStackSize() && ItemStack.isSameItemSameComponents(s, stack)) return true;
        }
        return false;
    }
}
