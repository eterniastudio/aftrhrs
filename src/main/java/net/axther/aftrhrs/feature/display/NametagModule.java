package net.axther.aftrhrs.feature.display;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.ScoreTeams;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nametags (nametags.yml): the vanilla nametag is hidden with a team option and replaced by a text display above
 * the player, so it can show the nickname with gradients, the LuckPerms prefix, health and anything else.
 *
 * <p>On Paper the display was a real entity riding the player, which blocked cross-world teleports and could be
 * orphaned. Here it exists only on the clients: each player tracking someone is sent a text display (spawn, data,
 * passenger packets) that never exists on the server, so it can't block teleports, can't be saved into the world and
 * follows vanilla's own tracking (vanished players' tags go wherever the player goes, which is nowhere). Modes:
 * {@code passenger} (default) rides the player client-side and moves perfectly with them ({@code SetPassengersPacketMixin}
 * keeps it on through vanilla's passenger updates); {@code follow} sends the tag to the player's head every tick
 * (smoothed client-side). The player never sees their own tag. Invisible, spectating, dead or carrying players have
 * none. Sneaking dims the tag and stops it showing through walls.
 */
public final class NametagModule implements FeatherModule {
    private static volatile NametagModule active;

    /** One player's tag: a client-only display and who has it. */
    private static final class Tag {
        final UUID owner;
        final ServerLevel level;
        final Display.TextDisplay display;
        net.minecraft.network.chat.Component text;
        /** The Adventure text {@link #text} was converted from: compared first, so an unchanged tag skips the conversion. */
        Component adv;
        int health = -1;
        boolean sneaking;
        final Set<ServerPlayer> viewers = new HashSet<>();

        Tag(UUID owner, ServerLevel level, Display.TextDisplay display) {
            this.owner = owner;
            this.level = level;
            this.display = display;
        }
    }

    private final Feather plugin;
    private final ScoreTeams teams;
    private final Map<UUID, Tag> tags = new ConcurrentHashMap<>();
    private Tasks.Task syncTask;
    private Tasks.Task fastTask;

    private volatile boolean follow;
    private List<String> lines = List.of();
    private float height;
    private float scale;
    private int background;
    private boolean shadow;
    private boolean seeThrough;
    private boolean sneakDim;
    private float viewRange;
    private int lineWidth;
    private boolean hideInvisible;

    public NametagModule(Feather plugin) {
        this.plugin = plugin;
        this.teams = plugin.scoreTeams();
    }

    @Override
    public String name() {
        return "Nametags";
    }

    @Override
    public void enable() {
        active = this;
        reload();
        plugin.once("nametags.events", () -> {
            EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
                NametagModule m = active;
                if (m != null && entity instanceof ServerPlayer owner) m.startTracking(owner, viewer);
            });
            EntityTrackingEvents.STOP_TRACKING.register((entity, viewer) -> {
                NametagModule m = active;
                if (m != null && entity instanceof ServerPlayer owner) m.stopTracking(owner, viewer);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                NametagModule m = active;
                if (m == null) return;
                m.remove(h.player.getUUID());
                for (Tag t : m.tags.values()) t.viewers.removeIf(v -> v.getUUID().equals(h.player.getUUID()));
            });
            FeatherEvents.NAMES_CHANGED.register(p -> {
                NametagModule m = active;
                if (m != null) m.refresh(p);
            });
            FeatherEvents.AFK_CHANGED.register((p, afk) -> {
                NametagModule m = active;
                if (m != null) m.refresh(p);
            });
            // Paper's displays were not saved, but a crash could leave one behind: clear any that come back.
            Legacy.converter((level, entity, bukkit) -> {
                if (!(entity instanceof Display.TextDisplay) || !bukkit.contains("feather:nametag")) return false;
                entity.discard();
                return true;
            });
        });
    }

    @Override
    public void reload() {
        Yaml c = plugin.loadResourceConfig("nametags.yml");
        boolean wasFollow = follow;
        follow = c.getString("mode", "passenger").equalsIgnoreCase("follow");
        lines = c.isList("lines") ? c.getStringList("lines") : List.of(c.getString("lines", "<prefix><name><suffix>"));
        height = (float) c.getDouble("height", 0.3);
        scale = (float) c.getDouble("scale", 1.0);
        background = parseArgb(c.getString("background", "#40000000"));
        shadow = c.getBoolean("text-shadow", false);
        seeThrough = c.getBoolean("see-through-walls", true);
        sneakDim = c.getBoolean("sneak-dims", true);
        viewRange = (float) c.getDouble("view-range", 0.5);
        lineWidth = c.getInt("line-width", 200);
        hideInvisible = c.getBoolean("hide-when-invisible", true);
        Runnable hide = () -> {
            if (active != this) return;
            teams.hideNametags(true);
            TabModule tab = plugin.module(TabModule.class);
            if (tab != null) tab.nametagsChanged();
        };
        // Team changes are broadcast to players: not before the player list exists.
        if (Feather.server().getPlayerList() != null) hide.run();
        else Tasks.later(1L, hide);
        // Restyling or switching modes: send every tag again.
        removeAll();
        if (syncTask != null) syncTask.cancel();
        syncTask = Tasks.timer(1L, Math.max(2, c.getInt("update-ticks", 10)), this::sync);
        if (fastTask != null) fastTask.cancel();
        fastTask = Tasks.timer(1L, 1L, this::fastTick);
        if (wasFollow != follow) plugin.getLogger().info("Nametags: {} mode.", follow ? "follow" : "passenger");
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (syncTask != null) syncTask.cancel();
        if (fastTask != null) fastTask.cancel();
        removeAll();
        teams.hideNametags(false);
        // Not at shutdown: the tab module has removed its health objectives by then, and this would add them back.
        TabModule tab = plugin.module(TabModule.class);
        if (plugin.isEnabled() && tab != null) tab.nametagsChanged();
    }

    private static int parseArgb(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) h = "FF" + h;
            return (int) Long.parseLong(h, 16);
        } catch (RuntimeException e) {
            return 0x40000000;
        }
    }

    // ------------------------------------------------------------------ hooks

    /** The client-only tag riding this entity in passenger mode, or -1 ({@code SetPassengersPacketMixin}). */
    public static int passengerFor(Entity vehicle) {
        NametagModule m = active;
        if (m == null || m.follow || !(vehicle instanceof ServerPlayer)) return -1;
        Tag t = m.tags.get(vehicle.getUUID());
        return t == null ? -1 : t.display.getId();
    }

    /** Refreshes one player's tag text right away (health change, nickname change). */
    public void refresh(ServerPlayer player) {
        Tag t = tags.get(player.getUUID());
        if (t != null) updateText(player, t);
    }

    // ------------------------------------------------------------------ sync

    private boolean shouldHave(ServerPlayer p) {
        if (p.hasDisconnected() || p.isDeadOrDying() || p.isSpectator()) return false;
        // While carrying someone, the rider sits where the tag would be; their own tag shows instead.
        if (!p.getPassengers().isEmpty()) return false;
        return !hideInvisible || !p.hasEffect(MobEffects.INVISIBILITY);
    }

    private void sync() {
        for (ServerPlayer p : plugin.onlinePlayers()) {
            Tag t = tags.get(p.getUUID());
            if (!shouldHave(p)) {
                if (t != null) remove(p.getUUID());
                continue;
            }
            if (t == null || t.level != p.level()) {
                if (t != null) remove(p.getUUID());
                create(p);
                continue;
            }
            updateText(p, t);
        }
    }

    /** Sneak dimming, health and follow-mode positions, every tick. */
    private void fastTick() {
        for (Tag t : tags.values()) {
            ServerPlayer p = plugin.player(t.owner);
            if (p == null) continue;
            int hp = (int) Math.ceil(Math.max(0, p.getHealth()));
            if (hp != t.health) updateText(p, t);
            updateSneak(p, t);
            if (follow && !t.viewers.isEmpty()) {
                Vec3 at = anchor(p);
                t.display.setPos(at.x, at.y, at.z);
                Packet<ClientGamePacketListener> move = ClientboundEntityPositionSyncPacket.of(t.display);
                for (ServerPlayer v : t.viewers) v.connection.send(move);
            }
        }
    }

    private Vec3 anchor(ServerPlayer p) {
        return p.position().add(0, p.getBbHeight() + height, 0);
    }

    private void create(ServerPlayer p) {
        Display.TextDisplay d = new Display.TextDisplay(net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY, p.level());
        Vec3 at = follow ? anchor(p) : p.position();
        d.setPos(at.x, at.y, at.z);
        style(d);
        Tag t = new Tag(p.getUUID(), p.level(), d);
        tags.put(p.getUUID(), t);
        t.health = (int) Math.ceil(Math.max(0, p.getHealth()));
        t.adv = advText(p);
        t.text = Adv.vanilla(t.adv);
        d.setText(t.text);
        t.sneaking = sneakDim && p.isShiftKeyDown();
        applySneak(t);
        d.getEntityData().packDirty(); // the spawn sends everything
        for (ServerPlayer v : trackers(p)) show(t, p, v);
    }

    private void style(Display.TextDisplay d) {
        d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        d.setBackgroundColor(background);
        d.setLineWidth(lineWidth);
        d.setViewRange(viewRange);
        d.setPosRotInterpolationDuration(follow ? 2 : 0);
        float y = follow ? 0f : height;
        d.setTransformation(new Transformation(new Vector3f(0, y, 0), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private void applySneak(Tag t) {
        byte flags = 0;
        if (shadow) flags |= Display.TextDisplay.FLAG_SHADOW;
        if (seeThrough && !t.sneaking) flags |= Display.TextDisplay.FLAG_SEE_THROUGH;
        t.display.setFlags(flags);
        t.display.setTextOpacity(t.sneaking ? (byte) 0x60 : (byte) -1);
    }

    private void updateSneak(ServerPlayer p, Tag t) {
        boolean sneaking = sneakDim && p.isShiftKeyDown();
        if (sneaking == t.sneaking) return;
        t.sneaking = sneaking;
        applySneak(t);
        pushData(t);
    }

    private Component advText(ServerPlayer p) {
        Placeholders ph = plugin.placeholders();
        Component text = Component.empty();
        boolean first = true;
        for (String line : lines) {
            Component c = ph.format(line, p, null);
            text = first ? c : text.appendNewline().append(c);
            first = false;
        }
        return text;
    }

    private void updateText(ServerPlayer p, Tag t) {
        t.health = (int) Math.ceil(Math.max(0, p.getHealth()));
        Component a = advText(p);
        if (a.equals(t.adv)) return;
        t.adv = a;
        t.text = Adv.vanilla(a);
        t.display.setText(t.text);
        pushData(t);
    }

    private void pushData(Tag t) {
        List<SynchedEntityData.DataValue<?>> dirty = t.display.getEntityData().packDirty();
        if (dirty == null || t.viewers.isEmpty()) return;
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(t.display.getId(), dirty);
        for (ServerPlayer v : t.viewers) v.connection.send(packet);
    }

    // ------------------------------------------------------------------ viewers

    /** Everyone whose client has the player right now (vanilla's entity tracking). */
    private static List<ServerPlayer> trackers(ServerPlayer p) {
        List<ServerPlayer> out = new ArrayList<>();
        ChunkMap.TrackedEntity tracked = p.level().getChunkSource().chunkMap.entityMap.get(p.getId());
        if (tracked == null) return out;
        for (ServerPlayerConnection c : tracked.seenBy) {
            ServerPlayer v = c.getPlayer();
            if (v != p) out.add(v);
        }
        return out;
    }

    private void startTracking(ServerPlayer owner, ServerPlayer viewer) {
        if (viewer == owner) return;
        Tag t = tags.get(owner.getUUID());
        if (t != null && t.level == owner.level()) show(t, owner, viewer);
    }

    private void stopTracking(ServerPlayer owner, ServerPlayer viewer) {
        Tag t = tags.get(owner.getUUID());
        if (t != null && t.viewers.remove(viewer) && !viewer.hasDisconnected()) {
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(t.display.getId()));
        }
    }

    private void show(Tag t, ServerPlayer owner, ServerPlayer viewer) {
        if (!t.viewers.add(viewer)) return;
        Display.TextDisplay d = t.display;
        Vec3 at = follow ? anchor(owner) : owner.position();
        d.setPos(at.x, at.y, at.z);
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(new ClientboundAddEntityPacket(d.getId(), d.getUUID(), at.x, at.y, at.z, 0f, 0f, net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
        List<SynchedEntityData.DataValue<?>> all = d.getEntityData().getNonDefaultValues();
        if (all != null) packets.add(new ClientboundSetEntityDataPacket(d.getId(), all));
        if (!follow) packets.add(new ClientboundSetPassengersPacket(owner)); // the mixin adds the tag to the list
        viewer.connection.send(new ClientboundBundlePacket(packets));
    }

    private void remove(UUID owner) {
        Tag t = tags.remove(owner);
        if (t == null) return;
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(t.display.getId());
        for (ServerPlayer v : t.viewers) if (!v.hasDisconnected()) v.connection.send(packet);
        t.viewers.clear();
    }

    private void removeAll() {
        for (UUID id : List.copyOf(tags.keySet())) remove(id);
    }
}
