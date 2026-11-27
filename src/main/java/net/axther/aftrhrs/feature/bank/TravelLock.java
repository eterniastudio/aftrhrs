package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The robber's travel lock, from the start of a robbery until it ends: walking, sprinting, swimming, jumping and
 * riding a horse are allowed, every other way of getting around is not. See docs/bank-robbery.md for the full list.
 *
 * <p>On Fabric the specific rules are: teleports through {@code FeatherEvents.PLAYER_TELEPORT} (any cause), commands
 * through {@code FeatherEvents.COMMAND}, item uses (pearls, wind charges, chorus fruit, fireworks while gliding,
 * riptide tridents) through {@code UseItemCallback}, and a per-tick check that takes the robber off anything but an
 * allowed mount (Arcana mounts included), stops gliding and flight, keeps them in survival and moves them off a lunge
 * weapon. Two nets catch what the rules miss: every teleport further than a few blocks (or into another world) is
 * cancelled whatever caused it, and a move faster than {@code max-speed} blocks per tick outside a horse is pulled back.
 */
final class TravelLock {
    private final Robberies robberies;
    private final Map<String, Long> lastTold = new HashMap<>();

    Set<EntityType<?>> allowedMounts = new HashSet<>(List.of(net.minecraft.world.entity.EntityTypes.HORSE, net.minecraft.world.entity.EntityTypes.DONKEY,
            net.minecraft.world.entity.EntityTypes.MULE, net.minecraft.world.entity.EntityTypes.SKELETON_HORSE, net.minecraft.world.entity.EntityTypes.ZOMBIE_HORSE));
    double maxTeleport = 3;
    double maxSpeed = 1.6;
    Set<String> blockedCommands = new HashSet<>();
    /** Robbers whose next teleport Feather itself allows (see {@link #allowNextTeleport}). */
    private final Set<UUID> allowNext = new HashSet<>();

    TravelLock(Robberies robberies) {
        this.robberies = robberies;
    }

    void reload(Yaml c) {
        if (c == null) return;
        Set<EntityType<?>> mounts = new HashSet<>();
        for (String s : c.getStringList("allowed-mounts")) {
            // Bukkit names (HORSE, SKELETON_HORSE) or ids (minecraft:horse, aftrhrs:cinderwing).
            String k = s.trim().toLowerCase(Locale.ROOT);
            Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
            EntityType<?> t = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (t == null) robberies.bank.plugin.getLogger().warn("bank.yml robbery.travel.allowed-mounts: unknown entity type {}", s);
            else mounts.add(t);
        }
        if (c.isList("allowed-mounts")) allowedMounts = mounts;
        maxTeleport = Math.max(0.5, c.getDouble("max-teleport-distance", 3));
        maxSpeed = Math.max(0.8, c.getDouble("max-speed", 1.6));
        Set<String> cmds = new HashSet<>();
        for (String s : c.getStringList("blocked-commands")) cmds.add(s.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        blockedCommands = cmds;
    }

    private boolean locked(ServerPlayer p) {
        return robberies.locked(p);
    }

    /** Action-bar note, at most once a second per player and kind. */
    void tell(ServerPlayer p, String what) {
        long now = System.currentTimeMillis();
        String key = p.getUUID() + what;
        Long last = lastTold.get(key);
        if (last != null && now - last < 1000) return;
        lastTold.put(key, now);
        Text text = robberies.bank.text();
        text.actionBar(p, "bank.robbery.travel-blocked", Text.component("what", text.msg("bank.robbery.travel." + what)));
        text.sound(p, "error");
    }

    /** Lets Feather's own next teleport of the robber through (none needs it today). */
    void allowNextTeleport(UUID id) {
        allowNext.add(id);
    }

    void forget(UUID id) {
        lastTold.keySet().removeIf(k -> k.startsWith(id.toString()));
        allowNext.remove(id);
    }

    boolean allowedMount(Entity e) {
        return e != null && allowedMounts.contains(e.getType());
    }

    // ------------------------------------------------------------------ start, every tick, end

    /** At the start: off anything but a horse, no gliding or flying, no pearls in the air, no lunge spear in hand. */
    void engage(ServerPlayer p) {
        enforce(p, true);
        int removed = 0;
        var server = Feather.serverOrNull();
        if (server != null) {
            for (ServerLevel w : server.getAllLevels()) {
                List<Entity> pearls = new ArrayList<>();
                for (Entity e : w.getAllEntities()) {
                    if (e instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof ServerPlayer s && s.getUUID().equals(p.getUUID())) pearls.add(e);
                }
                for (Entity e : pearls) {
                    e.discard();
                    removed++;
                }
            }
        }
        if (removed > 0) robberies.bank.text().send(p, "bank.robbery.pearls-removed", Text.unparsed("count", removed));
    }

    /** Every tick while robbing. */
    void enforce(ServerPlayer p, boolean start) {
        Entity v = p.getVehicle();
        while (v != null) {
            if (!allowedMount(v)) {
                p.stopRiding();
                if (!start) tell(p, v instanceof ServerPlayer ? "carried" : "mount");
                break;
            }
            v = v.getVehicle();
        }
        if (p.isFallFlying()) {
            p.stopFallFlying();
            if (!start) tell(p, "elytra");
        }
        GameType mode = p.gameMode();
        if (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE) {
            // Paper cancelled the switch; here it is undone on the next tick.
            p.setGameMode(GameType.SURVIVAL);
            tell(p, "gamemode");
        }
        if (p.getAbilities().flying || p.getAbilities().mayfly) {
            if (p.getAbilities().flying && !start) tell(p, "fly");
            p.getAbilities().flying = false;
            p.getAbilities().mayfly = false;
            p.onUpdateAbilities();
        }
        if (p.isAutoSpinAttack()) {
            Vec3 m = p.getDeltaMovement();
            p.setDeltaMovement(0, Math.min(0, m.y), 0);
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
        }
        Inventory inv = p.getInventory();
        if (hasLunge(inv.getSelectedItem())) {
            int free = -1;
            for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
                if (!hasLunge(inv.getItem(i))) {
                    free = i;
                    break;
                }
            }
            if (free >= 0 && free != inv.getSelectedSlot()) {
                inv.setSelectedSlot(free);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(free));
                tell(p, "lunge");
            }
        }
    }

    private static boolean has(ItemStack s, ResourceKey<Enchantment> key) {
        if (s == null || s.isEmpty()) return false;
        for (Holder<Enchantment> h : s.getEnchantments().keySet()) if (h.is(key)) return true;
        return false;
    }

    static boolean hasLunge(ItemStack s) {
        return has(s, Enchantments.LUNGE);
    }

    static boolean hasRiptide(ItemStack s) {
        return s != null && s.is(Items.TRIDENT) && has(s, Enchantments.RIPTIDE);
    }

    // ------------------------------------------------------------------ hooks (registered once by Robberies)

    /** Any teleport of the robber: pearls, chorus fruit, portals, spectating, and anything further than a few blocks. */
    boolean allowTeleport(ServerPlayer p, Loc from, Loc to, TeleportCause cause) {
        if (!locked(p)) return true;
        if (allowNext.remove(p.getUUID())) return true;
        boolean always = switch (cause) {
            case ENDER_PEARL, CHORUS_FRUIT, NETHER_PORTAL, END_PORTAL, END_GATEWAY, SPECTATE -> true;
            default -> false;
        };
        boolean blocked = always || to == null || from == null || !to.sameLevel(from) || to.distanceSquared(from) > maxTeleport * maxTeleport;
        if (!blocked) return true;
        tell(p, switch (cause) {
            case ENDER_PEARL -> "pearl";
            case CHORUS_FRUIT -> "chorus";
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> "portal";
            default -> "teleport";
        });
        return false;
    }

    boolean allowCommand(ServerPlayer p, String typed) {
        if (!locked(p) || blockedCommands.isEmpty()) return true;
        String msg = typed.startsWith("/") ? typed.substring(1) : typed;
        String label = msg.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        String bare = colon >= 0 ? label.substring(colon + 1) : label;
        if (blockedCommands.contains(label) || blockedCommands.contains(bare)) {
            robberies.bank.text().send(p, "bank.robbery.command-blocked", Text.unparsed("command", "/" + bare));
            robberies.bank.text().sound(p, "error");
            return false;
        }
        return true;
    }

    /** Using an item: pearls, wind charges, chorus fruit, rockets while gliding and riptide tridents are refused. */
    boolean allowUse(ServerPlayer p, ItemStack item) {
        if (!locked(p) || item == null || item.isEmpty()) return true;
        String what = null;
        if (item.is(Items.ENDER_PEARL)) what = "pearl";
        else if (item.is(Items.WIND_CHARGE)) what = "wind";
        else if (item.is(Items.CHORUS_FRUIT)) what = "chorus";
        else if (item.is(Items.FIREWORK_ROCKET) && p.isFallFlying()) what = "elytra";
        else if (hasRiptide(item)) what = "riptide";
        if (what == null) return true;
        tell(p, what);
        // The client already shows the use: put the hand back in sync.
        p.containerMenu.sendAllDataToRemote();
        return false;
    }

    /** Attacking with a lunge weapon. */
    boolean allowAttack(ServerPlayer p) {
        if (!locked(p) || !hasLunge(p.getMainHandItem())) return true;
        tell(p, "lunge");
        return false;
    }

    /** The speed net: faster than {@code max-speed} blocks a tick (outside a horse) is pulled back. */
    boolean allowMove(ServerPlayer p, Loc from, Loc to) {
        if (!locked(p) || p.isPassenger() || from == null || to == null || !from.sameLevel(to)) return true;
        double dx = to.x() - from.x(), dz = to.z() - from.z(), dy = to.y() - from.y();
        if (dx * dx + dz * dz > maxSpeed * maxSpeed || dy > maxSpeed) {
            tell(p, "speed");
            return false;
        }
        return true;
    }
}
