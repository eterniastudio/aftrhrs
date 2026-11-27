package net.axther.aftrhrs.feature.coast;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display entities drawn for furniture (fish on plaques and in tanks, records boards, seaweed overlays, string-light
 * bulbs). They are never meant to outlive the session (Paper spawned them non-persistent): each carries a
 * {@code feather:*_part} tag in its {@link Pdc}, and one that loads without being one of this session's parts (left
 * over by a crash or an unload) is removed at once; the owners draw them again.
 */
public final class Parts {
    private static final Set<UUID> LIVE = ConcurrentHashMap.newKeySet();
    private static final Set<String> KEYS = ConcurrentHashMap.newKeySet();
    private static boolean registered;

    private Parts() {
    }

    /** Registers the cleanup for parts tagged with {@code key} (e.g. {@code feather:fish_part}). */
    public static synchronized void track(String key) {
        KEYS.add(key);
        if (registered) return;
        registered = true;
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Display) || LIVE.contains(entity.getUUID())) return;
            for (String k : KEYS) {
                if (Pdc.has(entity, k)) {
                    entity.discard();
                    return;
                }
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> LIVE.remove(entity.getUUID()));
    }

    public static boolean isPart(Entity e) {
        for (String k : KEYS) if (Pdc.has(e, k)) return true;
        return false;
    }

    /** An item display at {@code at} turned to {@code yaw}, tagged {@code key = owner}. */
    public static Display.ItemDisplay item(ServerLevel level, Vec3 at, float yaw, ItemStack stack, ItemDisplayContext ctx,
                                           Transformation tf, String key, String owner) {
        Display.ItemDisplay d = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level);
        d.setPos(at.x, at.y, at.z);
        d.setYRot(yaw);
        d.setXRot(0);
        ItemStack one = stack.copy();
        one.setCount(1);
        d.setItemStack(one);
        d.setItemTransform(ctx);
        d.setTransformation(tf);
        spawn(level, d, key, owner);
        return d;
    }

    /** A centered text display (always faces the viewer), tagged {@code key = owner}. */
    public static Display.TextDisplay text(ServerLevel level, Vec3 at, Component text, float scale, float viewRange, String key, String owner) {
        Display.TextDisplay d = new Display.TextDisplay(net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY, level);
        d.setPos(at.x, at.y, at.z);
        d.setText(text);
        d.setLineWidth(400);
        d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        d.setTransformation(new Transformation(null, null, new org.joml.Vector3f(scale, scale, scale), null));
        d.setViewRange(viewRange);
        spawn(level, d, key, owner);
        return d;
    }

    /** Tags {@code d} as a part of {@code owner} and adds it to the world (for displays built by the caller). */
    public static void spawn(ServerLevel level, Display d, String key, String owner) {
        Pdc.putString(d, key, owner);
        d.addTag("feather_part");
        // Never written to the world: the owner draws it again when the chunk loads, so a saved copy was only
        // disk churn (saved, loaded, discarded as a leftover) in chunks that stay loaded without being simulated.
        d.addTag(FeatherEvents.TRANSIENT_TAG);
        LIVE.add(d.getUUID());
        level.addFreshEntity(d);
    }

    public static void remove(Entity e) {
        if (e == null) return;
        LIVE.remove(e.getUUID());
        if (!e.isRemoved()) e.discard();
    }
}
