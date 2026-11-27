package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Arcana tools (formerly the ArcanaTools plugin), {@code modules.arcana-tools}, settings {@code tools.yml}: area tools
 * (3x3 hammers / excavators, lumber axes), harvest scythes, the magnet charm, the builder's wand, hats, the mob drop
 * table and furniture hooks (copper waxing / scraping / aging, planter harvest). The Cinderplume / Galeheart abilities
 * (tools.yml {@code relics:}) are {@code feature.altars.BossRelics}; the Oraxen relic mechanics are {@code feature.relics}.
 * Every feature is keyed by the item's Oraxen id ({@code aftrhrs:<id>} now). The pet whistle moved to the pets port;
 * the hearth swap rules are gone (hearths are a {@code lit} block state).
 */
public final class ToolsModule implements FeatherModule {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    static volatile ToolsModule active;

    final Feather feather;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<String, Long> lastUse = new HashMap<>();
    private Yaml config;

    final AreaTools areaTools = new AreaTools(this);
    final Scythes scythes = new Scythes(this);
    final Magnet magnet = new Magnet(this);
    final BuildersWand wand = new BuildersWand(this);
    final Hats hats = new Hats(this);
    final Drops drops = new Drops(this);
    final FurnitureHooks furniture = new FurnitureHooks(this);

    public ToolsModule(Feather feather) {
        this.feather = feather;
    }

    @Override
    public String name() {
        return "Arcana tools";
    }

    @Override
    public void enable() {
        active = this;
        feather.once("tools.events", ToolsEvents::register);
        reload();
        Tasks.later(100, this::validateItemIds);
    }

    @Override
    public void reload() {
        config = feather.loadResourceConfig("tools.yml");
        areaTools.load(config);
        scythes.load(config);
        magnet.load(config);
        wand.load(config);
        hats.load(config);
        drops.load(config);
        furniture.load(config);
    }

    @Override
    public void disable() {
        magnet.stop();
        furniture.stop();
        busy.clear();
        if (active == this) active = null;
    }

    /** Warns for every configured id that is not native content (typos, items not shipped yet). */
    void validateItemIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(areaTools.configuredIds());
        ids.addAll(scythes.configuredIds());
        ids.addAll(magnet.configuredIds());
        ids.addAll(wand.configuredIds());
        ids.addAll(drops.configuredIds());
        ids.addAll(furniture.configuredIds());
        List<String> missing = new ArrayList<>();
        for (String id : ids) if (ArcanaContent.entry(id) == null) missing.add(id);
        if (missing.isEmpty()) log().info("All {} configured Arcana item ids exist.", ids.size());
        else log().warn("{} configured Arcana item id(s) are unknown: {}", missing.size(), missing);
    }

    // ---------------------------------------------------------------- shared helpers

    Logger log() {
        return feather.getLogger();
    }

    Yaml config() {
        return config;
    }

    /** Oraxen-style id of a stack ({@code arcana_iron_hammer}), or null for empty / vanilla stacks. */
    static @Nullable String idOf(ItemStack stack) {
        return ArcanaContent.id(stack);
    }

    static Component mini(String text, TagResolver... resolvers) {
        return MINI.deserialize(text == null ? "" : text, resolvers);
    }

    /** MiniMessage with Feather's palette tags and prefix. */
    Component text(String text, TagResolver... resolvers) {
        return feather.text().parse(text, resolvers);
    }

    static boolean isCreative(ServerPlayer p) {
        return p.gameMode.getGameModeForPlayer() == GameType.CREATIVE;
    }

    /** Adventure and spectator players may not place or break blocks. */
    static boolean mayEditWorld(ServerPlayer p) {
        GameType m = p.gameMode.getGameModeForPlayer();
        return m != GameType.ADVENTURE && m != GameType.SPECTATOR;
    }

    /** Vanilla spawn protection (server.properties spawn-protection; ops exempt). */
    static boolean inSpawnProtection(ServerPlayer p, ServerLevel level, BlockPos pos) {
        return level.getServer().isUnderSpawnProtection(level, pos, p);
    }

    boolean claimAllows(ServerPlayer p, ServerLevel level, BlockPos pos, ClaimAction action) {
        return feather.claimAllows(p, Loc.of(level, pos), action);
    }

    static List<String> lowerList(Collection<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) if (s != null && !s.isBlank()) out.add(stripNs(s.trim().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** {@code oraxen:x} / {@code aftrhrs:x} → {@code x}. */
    static String stripNs(String id) {
        if (id.startsWith("oraxen:")) return id.substring(7);
        if (id.startsWith("aftrhrs:")) return id.substring(8);
        return id;
    }

    /**
     * True if {@code feature} was already used by this player within {@code ticks}; otherwise records the use (one
     * right-click can reach several callbacks: block, item, off hand).
     */
    boolean recentlyUsed(ServerPlayer p, String feature, int ticks) {
        String key = p.getUUID() + ":" + feature;
        long now = Tasks.now();
        Long last = lastUse.get(key);
        if (last != null && now - last >= 0 && now - last < ticks) return true;
        lastUse.put(key, now);
        if (lastUse.size() > 4096) lastUse.clear();
        return false;
    }

    boolean isBusy(ServerPlayer p) {
        return busy.contains(p.getUUID());
    }

    void setBusy(ServerPlayer p, boolean value) {
        if (value) busy.add(p.getUUID());
        else busy.remove(p.getUUID());
    }

    /** Plays a sound key ({@code block.crop.break}) at a spot for everyone around. */
    static void playSound(ServerLevel level, double x, double y, double z, String key, SoundSource src, float volume, float pitch) {
        SoundEvent e = sound(key);
        if (e != null) level.playSound(null, x, y, z, e, src, volume, pitch);
    }

    static @Nullable SoundEvent sound(String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
        if (id == null) return null;
        return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
    }

    static void actionBar(ServerPlayer p, Component c) {
        Adv.actionBar(p, c);
    }
}
