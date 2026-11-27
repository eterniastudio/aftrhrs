package net.axther.aftrhrs.core;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Prefix, suffix, primary group and group weights from LuckPerms (Fabric). {@link #create} returns null when
 * LuckPerms isn't installed; the LuckPerms API classes are only touched inside {@link Impl}.
 */
public abstract class LuckPermsHook {
    /** The hook, or null without LuckPerms (or when its API isn't ready yet). */
    public static LuckPermsHook create() {
        if (!FabricLoader.getInstance().isModLoaded("luckperms")) return null;
        try {
            return new Impl();
        } catch (Throwable t) {
            return null;
        }
    }

    public abstract String prefix(ServerPlayer p);

    public abstract String suffix(ServerPlayer p);

    public abstract String group(ServerPlayer p);

    /** The group's LuckPerms weight, or 0. */
    public abstract int weight(String group);

    /** A loaded user's permission (during login, before a player exists), or null when LuckPerms hasn't loaded them. */
    public abstract Boolean has(UUID id, String permission);

    private static final class Impl extends LuckPermsHook {
        private final net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();

        private net.luckperms.api.cacheddata.CachedMetaData meta(ServerPlayer p) {
            return api.getPlayerAdapter(ServerPlayer.class).getMetaData(p);
        }

        @Override
        public String prefix(ServerPlayer p) {
            String s = meta(p).getPrefix();
            return s == null ? "" : s;
        }

        @Override
        public String suffix(ServerPlayer p) {
            String s = meta(p).getSuffix();
            return s == null ? "" : s;
        }

        @Override
        public String group(ServerPlayer p) {
            String s = meta(p).getPrimaryGroup();
            return s == null ? "default" : s;
        }

        @Override
        public int weight(String group) {
            net.luckperms.api.model.group.Group g = api.getGroupManager().getGroup(group);
            return g == null ? 0 : g.getWeight().orElse(0);
        }

        @Override
        public Boolean has(UUID id, String permission) {
            net.luckperms.api.model.user.User user = api.getUserManager().getUser(id);
            if (user == null) return null;
            return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        }
    }
}
