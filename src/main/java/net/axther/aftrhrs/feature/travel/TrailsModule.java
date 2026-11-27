package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Flight trails (trails.yml): while a player flies (and, if enabled, glides), particles stream out behind them in the
 * style they picked with {@code /trail} (a menu). Styles are particles with optional colors: several colors cycle along
 * the trail, and dust transitions fade between two. Only players who can see the flyer see the particles, so vanished
 * staff leave no trail.
 */
public final class TrailsModule implements FeatherModule, Travel.Named {
    /** What a particle needs besides its type (Paper's {@code Particle.getDataType()}). */
    private enum Data { NONE, DUST, TRANSITION, COLOR, BLOCK, ITEM }

    private static final Set<ParticleType<?>> BLOCK_TYPES = Set.of(ParticleTypes.BLOCK, ParticleTypes.BLOCK_MARKER,
            ParticleTypes.FALLING_DUST, ParticleTypes.DUST_PILLAR, ParticleTypes.BLOCK_CRUMBLE);
    private static final Set<ParticleType<?>> COLOR_TYPES = Set.of(ParticleTypes.ENTITY_EFFECT, ParticleTypes.TINTED_LEAVES, ParticleTypes.FLASH);

    /** A configured trail. */
    record Style(String id, String name, Item icon, ParticleType<?> particle, Data data, int count, double spread, double speed,
                 List<Integer> colors, float size, Block block, Item item, String permission) {
    }

    private final Feather plugin;
    private final Map<String, Style> styles = new LinkedHashMap<>();
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Map<UUID, ServerLevel> lastLevel = new HashMap<>();
    private Tasks.Task task;
    private String defaultStyle;
    private boolean onlyMoving;
    private boolean gliding;
    private boolean creative;
    private boolean showOwn;
    private double viewDistance;
    private long step;

    public TrailsModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Flight trails";
    }

    @Override
    public void enable() {
        reload();
        Travel.bind(plugin, this, "trail");
        plugin.hub().add("trails", Hub.Group.STYLE, Hub.ORDER_TRAILS, "hub-trails", "feather.trails", this::openMenu);
    }

    @Override
    public void reload() {
        Yaml c = plugin.loadResourceConfig("trails.yml");
        defaultStyle = c.getString("default", "sparkle");
        onlyMoving = c.getBoolean("only-when-moving", true);
        gliding = c.getBoolean("while-gliding", false);
        creative = c.getBoolean("in-creative", true);
        showOwn = c.getBoolean("show-own", true);
        viewDistance = c.getDouble("view-distance", 48);
        styles.clear();
        Yaml sec = c.getConfigurationSection("styles");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                Yaml s = sec.getConfigurationSection(id);
                if (s == null) continue;
                Style style = parse(id.toLowerCase(Locale.ROOT), s);
                if (style != null) styles.put(style.id(), style);
            }
        }
        if (task != null) task.cancel();
        task = Tasks.timer(5L, Math.max(1, c.getInt("interval-ticks", 2)), this::tick);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        task = null;
    }

    private static Item item(String name, Item fallback) {
        Identifier id = name == null ? null : Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        return id == null ? fallback : BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).orElse(fallback);
    }

    private Style parse(String id, Yaml s) {
        String pid = s.getString("particle", "end_rod").toLowerCase(Locale.ROOT);
        Identifier pkey = Identifier.tryParse(pid.contains(":") ? pid : "minecraft:" + pid);
        ParticleType<?> particle = pkey == null ? null : BuiltInRegistries.PARTICLE_TYPE.getOptional(pkey).orElse(null);
        if (particle == null) {
            plugin.getLogger().warn("trails.yml {}: unknown particle {}", id, pid);
            return null;
        }
        List<Integer> colors = new ArrayList<>();
        for (String hex : s.getStringList("colors")) {
            try {
                colors.add(Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF);
            } catch (NumberFormatException e) {
                plugin.getLogger().warn("trails.yml {}: bad color {}", id, hex);
            }
        }
        Data data;
        Block block = null;
        Item item = null;
        if (particle instanceof SimpleParticleType) data = Data.NONE;
        else if (particle == ParticleTypes.DUST) data = Data.DUST;
        else if (particle == ParticleTypes.DUST_COLOR_TRANSITION) data = Data.TRANSITION;
        else if (COLOR_TYPES.contains(particle)) data = Data.COLOR;
        else if (BLOCK_TYPES.contains(particle)) {
            data = Data.BLOCK;
            Identifier b = Identifier.tryParse(s.getString("block", "cherry_leaves").toLowerCase(Locale.ROOT));
            block = b == null ? Blocks.CHERRY_LEAVES : BuiltInRegistries.BLOCK.getOptional(b).filter(x -> x != Blocks.AIR).orElse(Blocks.CHERRY_LEAVES);
        } else if (particle == ParticleTypes.ITEM) {
            data = Data.ITEM;
            item = item(s.getString("item", "amethyst_shard"), Items.AMETHYST_SHARD);
        } else {
            plugin.getLogger().warn("trails.yml {}: particle {} needs data Feather can't supply; pick another.", id, pid);
            return null;
        }
        if ((data == Data.DUST || data == Data.TRANSITION || data == Data.COLOR) && colors.isEmpty()) colors.add(0xC3A6FF);
        return new Style(id, s.getString("name", id), item(s.getString("icon", "feather"), Items.FEATHER), particle, data,
                Math.max(1, s.getInt("count", 2)), s.getDouble("spread", 0.15), s.getDouble("speed", 0.0), colors,
                (float) s.getDouble("size", 1.2), block, item, s.getString("permission", ""));
    }

    // ------------------------------------------------------------------ choosing

    /** The style id the player currently uses ("none" = off). */
    String chosen(ServerPlayer p) {
        String t = plugin.profiles().get(p).trail;
        return t == null ? defaultStyle : t;
    }

    private static boolean allowed(ServerPlayer p, Style s) {
        return s.permission().isEmpty() || Perms.has(p, s.permission());
    }

    private void choose(ServerPlayer p, String id) {
        Profiles.Profile profile = plugin.profiles().get(p);
        profile.trail = id;
        plugin.profiles().save(profile);
        if (!id.equals("none")) plugin.advance(p, "event", "trail");
    }

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (args.length == 0) {
            openMenu(p);
            return true;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        if (id.equals("off") || id.equals("none")) {
            choose(p, "none");
            text.send(p, "trails.turned-off");
            text.sound(p, "click");
            return true;
        }
        Style s = styles.get(id);
        if (s == null) {
            text.send(p, "trails.unknown", Text.unparsed("input", args[0]));
            return true;
        }
        if (!allowed(p, s)) {
            text.send(p, "trails.locked", Text.component("style", text.parse(s.name())));
            text.sound(p, "error");
            return true;
        }
        choose(p, id);
        text.send(p, "trails.set", Text.component("style", text.parse(s.name())));
        text.sound(p, "nick-set");
        return true;
    }

    void openMenu(ServerPlayer player) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.trails.title"));
        String current = chosen(player);
        Style cur = styles.get(current);
        menu.text(text.msg("menu.trails.body", Text.component("current",
                cur == null ? text.msg("menu.trails.none") : text.parse(cur.name()))), 300);
        for (Style s : styles.values()) {
            boolean ok = allowed(player, s);
            Component label = text.parse(s.name());
            if (s.id().equals(current)) label = text.msg("menu.trails.selected", Text.component("style", label));
            else if (!ok) label = text.msg("menu.trails.locked", Text.component("style", label));
            menu.button(label, ok ? text.msg("menu.trails.tooltip", Text.unparsed("id", s.id())) : text.msg("menu.trails.locked-tooltip"), 148, p -> {
                if (!allowed(p, s)) {
                    text.sound(p, "error");
                    openMenu(p);
                    return;
                }
                choose(p, s.id());
                text.sound(p, "nick-set");
                preview(p, s);
                openMenu(p);
            });
        }
        menu.button(text.msg("menu.trails.no-trail"), null, 148, p -> {
            choose(p, "none");
            text.sound(p, "click");
            openMenu(p);
        });
        menu.columns(2);
        plugin.exitButton(menu, player);
        menu.open(player);
        text.sound(player, "menu-open");
    }

    /** A little burst around the player so they see what they picked. */
    private void preview(ServerPlayer p, Style s) {
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            spawn(List.of(p), s, new Vec3(p.getX() + Math.cos(a) * 0.8, p.getY() + 1, p.getZ() + Math.sin(a) * 0.8), i);
        }
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>(styles.keySet());
        out.add("off");
        out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ drawing

    private boolean trailing(ServerPlayer p) {
        if (p.isSpectator()) return false;
        if (p.isCreative() && !creative) return false;
        if (p.getAbilities().flying) return true;
        return gliding && p.isFallFlying();
    }

    private void tick() {
        step++;
        for (ServerPlayer p : plugin.onlinePlayers()) {
            Vec3 now = p.position();
            Vec3 before = lastPos.put(p.getUUID(), now);
            ServerLevel beforeLevel = lastLevel.put(p.getUUID(), p.level());
            boolean sameLevel = beforeLevel == p.level();
            if (!trailing(p) || !Perms.has(p, "feather.trails")) continue;
            Style s = styles.get(chosen(p));
            if (s == null || !allowed(p, s)) continue;
            if (onlyMoving && (before == null || !sameLevel || before.distanceToSqr(now) < 0.0025)) continue;
            // Just behind and below the player, against their direction of travel.
            Vec3 back = before != null && sameLevel ? before.subtract(now) : Vec3.ZERO;
            if (back.lengthSqr() > 0) back = back.normalize().scale(0.4);
            Vec3 at = now.add(back).add(0, p.isFallFlying() ? 0.3 : 0.1, 0);
            List<ServerPlayer> viewers = new ArrayList<>();
            double max = viewDistance * viewDistance;
            for (ServerPlayer v : p.level().players()) {
                if (v == p ? !showOwn : !plugin.canSee(v, p)) continue;
                if (v.distanceToSqr(now) <= max) viewers.add(v);
            }
            if (!viewers.isEmpty()) spawn(viewers, s, at, step);
        }
        lastPos.keySet().removeIf(id -> plugin.player(id) == null);
        lastLevel.keySet().removeIf(id -> plugin.player(id) == null);
    }

    @SuppressWarnings("unchecked")
    private static ParticleOptions options(Style s, long n) {
        List<Integer> colors = s.colors();
        return switch (s.data()) {
            case NONE -> (SimpleParticleType) s.particle();
            case DUST -> new DustParticleOptions(colors.get((int) (n % colors.size())), s.size());
            case TRANSITION -> new DustColorTransitionOptions(colors.get((int) (n % colors.size())),
                    colors.get((int) ((n + 1) % colors.size())), s.size());
            case COLOR -> ColorParticleOption.create((ParticleType<ColorParticleOption>) s.particle(), 0xFF000000 | colors.get((int) (n % colors.size())));
            case BLOCK -> new BlockParticleOption((ParticleType<BlockParticleOption>) s.particle(), s.block().defaultBlockState());
            case ITEM -> new ItemParticleOption((ParticleType<ItemParticleOption>) s.particle(), s.item());
        };
    }

    private static void spawn(List<ServerPlayer> viewers, Style s, Vec3 at, long n) {
        ParticleOptions data = options(s, n);
        double r = s.spread();
        for (ServerPlayer v : viewers) {
            v.level().sendParticles(v, data, false, false, at.x, at.y, at.z, s.count(), r, r, r, s.speed());
        }
    }

    Map<String, Style> styles() {
        return styles;
    }
}
