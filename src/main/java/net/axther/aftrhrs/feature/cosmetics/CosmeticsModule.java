package net.axther.aftrhrs.feature.cosmetics;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Cosmetic armor and the hat slot ({@code modules.cosmetics}); see {@link Cosmetics}. On: the slots work and every
 * client draws the outfits. Off: the slots only let what is in them be taken out, and clients draw the real armor.
 */
public final class CosmeticsModule implements FeatherModule {
    private static volatile CosmeticsModule active;
    private static boolean hooked;
    private final Feather plugin;

    public CosmeticsModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Cosmetics";
    }

    @Override
    public void enable() {
        active = this;
        Cosmetics.serverEnabled = true;
        hookOnce();
        plugin.provide(Hooks.Outfit.class, new Hooks.Outfit() {
            @Override
            public List<ItemStack> worn(ServerPlayer player) {
                return Cosmetics.forGrave(player);
            }

            @Override
            public void clear(ServerPlayer player) {
                Cosmetics.clearItems(player);
            }

            @Override
            public boolean wear(ServerPlayer player, int index, ItemStack stack) {
                return Cosmetics.wear(player, index, stack);
            }
        });
        for (ServerPlayer p : plugin.onlinePlayers()) send(p);
    }

    @Override
    public void disable() {
        Cosmetics.serverEnabled = false;
        plugin.withdraw(Hooks.Outfit.class);
        for (ServerPlayer p : plugin.onlinePlayers()) send(p);
        if (active == this) active = null;
    }

    private static void send(ServerPlayer p) {
        if (ServerPlayNetworking.canSend(p, Cosmetics.SettingsPayload.TYPE)) {
            ServerPlayNetworking.send(p, new Cosmetics.SettingsPayload(Cosmetics.serverEnabled));
        }
    }

    /** Fabric events can't be unregistered: once per JVM, talking to whatever is enabled. */
    private static synchronized void hookOnce() {
        if (hooked) return;
        hooked = true;
        ServerPlayNetworking.registerGlobalReceiver(Cosmetics.TogglePayload.TYPE, (payload, ctx) -> {
            if (active != null) Cosmetics.toggle(ctx.player(), payload.piece());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
        // The client's channel list arrives a moment after the join: tell it again then.
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(Cosmetics.SettingsPayload.TYPE.id())) server.execute(() -> {
                if (!handler.player.hasDisconnected()) send(handler.player);
            });
        });
    }
}
