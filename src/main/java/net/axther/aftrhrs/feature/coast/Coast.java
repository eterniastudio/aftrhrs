package net.axther.aftrhrs.feature.coast;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Helpers the Coast &amp; Harvest ports (fishing, farming, civic) share: content ids, giving and taking items, sounds
 * and particles by name, claim checks, biome keys, and "a pet of this kind follows this player" for the Ripplekit,
 * Canopip, Sporeling and Hushwing perks.
 */
public final class Coast {
    private Coast() {
    }

    // ------------------------------------------------------------------ ids and items

    /** The Oraxen-style content id of a stack ({@code arcana_fish_moonkoi}), or null. */
    public static @Nullable String id(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : ArcanaContent.id(stack);
    }

    /** A fresh content item, or null when no such item exists. */
    public static @Nullable ItemStack item(String id, int amount) {
        if (id == null || id.isBlank()) return null;
        ItemStack s = ArcanaContent.stack(id, Math.max(1, amount));
        if (s.isEmpty()) {
            Item vanilla = vanillaItem(id);
            if (vanilla == null) return null;
            s = new ItemStack(vanilla, Math.max(1, amount));
        }
        s.setCount(Math.max(1, Math.min(s.getMaxStackSize(), amount)));
        return s;
    }

    /** A vanilla item by name ({@code cod}, {@code minecraft:cod}), or null. */
    public static @Nullable Item vanillaItem(String name) {
        if (name == null) return null;
        Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        if (id == null || !id.getNamespace().equals("minecraft")) return null;
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    public static boolean creative(@Nullable ServerPlayer p) {
        return p != null && p.isCreative();
    }

    /** Takes one item from a hand (not in creative). */
    public static void takeOne(ServerPlayer p, InteractionHand hand) {
        if (creative(p)) return;
        ItemStack s = p.getItemInHand(hand);
        s.shrink(1);
        if (s.isEmpty()) p.setItemInHand(hand, ItemStack.EMPTY);
    }

    /** Into the inventory, the rest dropped at the player's feet. */
    public static void give(ServerPlayer p, ItemStack s) {
        if (s == null || s.isEmpty()) return;
        if (!p.getInventory().add(s) && !s.isEmpty()) drop(p.level(), p.position(), s);
    }

    /** The main-hand swing, shown to everyone (the player too). */
    public static void swing(ServerPlayer p) {
        p.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
    }

    public static void drop(ServerLevel level, Vec3 at, ItemStack s) {
        if (s == null || s.isEmpty()) return;
        ItemEntity e = new ItemEntity(level, at.x, at.y, at.z, s);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        e.setDeltaMovement(r.nextDouble() * 0.2 - 0.1, 0.2, r.nextDouble() * 0.2 - 0.1);
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
    }

    public static void dropAll(ServerLevel level, BlockPos pos, List<ItemStack> items) {
        Vec3 at = Vec3.atCenterOf(pos).add(0, -0.2, 0);
        for (ItemStack s : items) drop(level, at, s);
    }

    /** Appends a lore line (an Adventure component, not italic) to a stack. */
    public static void addLore(ItemStack stack, net.kyori.adventure.text.Component line) {
        net.minecraft.world.item.component.ItemLore lore = stack.getOrDefault(net.minecraft.core.component.DataComponents.LORE,
                net.minecraft.world.item.component.ItemLore.EMPTY);
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>(lore.lines());
        lines.add(net.axther.aftrhrs.core.Adv.vanilla(line));
        stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lines));
    }

    /** An int in the stack's custom_data (Paper's item PDC keys live there), or {@code def}. */
    public static int itemInt(ItemStack stack, String key, int def) {
        net.minecraft.world.item.component.CustomData d = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (d == null) return def;
        return d.copyTag().getInt(key).orElse(def);
    }

    public static long itemLong(ItemStack stack, String key, long def) {
        net.minecraft.world.item.component.CustomData d = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (d == null) return def;
        return d.copyTag().getLong(key).orElse(def);
    }

    // ------------------------------------------------------------------ sounds and particles

    public static @Nullable SoundEvent soundEvent(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) return null;
        Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        return id == null ? null : SoundEvent.createVariableRangeEvent(id);
    }

    public static void sound(ServerLevel level, Vec3 at, String name, SoundSource source, float volume, float pitch) {
        SoundEvent e = soundEvent(name);
        if (e != null) level.playSound(null, at.x, at.y, at.z, e, source, volume, pitch);
    }

    public static void sound(ServerLevel level, BlockPos at, String name, float volume, float pitch) {
        sound(level, Vec3.atCenterOf(at), name, SoundSource.BLOCKS, volume, pitch);
    }

    /** "name [volume] [pitch]" (fishing.yml sound specs). */
    public static void soundSpec(ServerLevel level, Vec3 at, String spec, SoundSource source) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) return;
        String[] p = spec.trim().split("\\s+");
        try {
            sound(level, at, p[0], source, p.length > 1 ? Float.parseFloat(p[1]) : 1f, p.length > 2 ? Float.parseFloat(p[2]) : 1f);
        } catch (NumberFormatException ignored) {
        }
    }

    /** A sound only this player hears. */
    public static void soundTo(ServerPlayer p, Vec3 at, String name, SoundSource source, float volume, float pitch) {
        SoundEvent e = soundEvent(name);
        if (e == null) return;
        p.connection.send(new ClientboundSoundPacket(Holder.direct(e), source, at.x, at.y, at.z, volume, pitch,
                ThreadLocalRandom.current().nextLong()));
    }

    /** A simple particle type by Bukkit-style name ({@code FALLING_WATER}, {@code splash}), or null. */
    public static @Nullable SimpleParticleType particle(String name) {
        if (name == null || name.isBlank()) return null;
        Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        if (id == null) return null;
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(id).filter(t -> t instanceof SimpleParticleType)
                .map(t -> (SimpleParticleType) t).orElse(null);
    }

    public static void particles(ServerLevel level, ParticleOptions type, Vec3 at, int count, double dx, double dy, double dz, double speed) {
        if (type != null) level.sendParticles(type, at.x, at.y, at.z, count, dx, dy, dz, speed);
    }

    public static void particlesTo(ServerPlayer p, ParticleOptions type, double x, double y, double z, int count,
                                   double dx, double dy, double dz, double speed) {
        if (type != null) p.level().sendParticles(p, type, false, false, x, y, z, count, dx, dy, dz, speed);
    }

    // ------------------------------------------------------------------ globs

    public static Pattern glob(String s) {
        return Pattern.compile(Pattern.quote(s.trim().toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q"), Pattern.CASE_INSENSITIVE);
    }

    public static List<Pattern> globs(List<String> in) {
        List<Pattern> out = new ArrayList<>();
        for (String s : in) if (s != null && !s.isBlank()) out.add(glob(s));
        return out;
    }

    public static boolean matches(List<Pattern> ps, @Nullable String id) {
        if (id == null) return false;
        for (Pattern p : ps) if (p.matcher(id).matches()) return true;
        return false;
    }

    public static String pretty(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ claims

    /** Game mode (spectators never; adventure only for ACCESS) and land claims. */
    public static boolean allowed(ServerPlayer p, ServerLevel level, BlockPos pos, ClaimAction action) {
        GameType gm = p.gameMode();
        if (gm == GameType.SPECTATOR) return false;
        if (gm == GameType.ADVENTURE && action != ClaimAction.ACCESS) return false;
        return Feather.get().claimAllows(p, Loc.of(level, pos), action);
    }

    // ------------------------------------------------------------------ biomes

    public static String biomeKey(ServerLevel level, BlockPos pos) {
        Holder<Biome> b = level.getBiome(pos);
        return b.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");
    }

    /** Biome keys (namespace:path) in a biome tag ({@code minecraft:is_river}); empty when unknown. */
    public static List<String> biomeTag(String tag) {
        List<String> out = new ArrayList<>();
        var server = Feather.serverOrNull();
        Identifier id = Identifier.tryParse(tag);
        if (server == null || id == null) return out;
        var reg = server.registryAccess().lookupOrThrow(Registries.BIOME);
        for (Holder<Biome> h : reg.getTagOrEmpty(TagKey.create(Registries.BIOME, id))) {
            h.unwrapKey().ifPresent(k -> out.add(k.identifier().toString()));
        }
        return out;
    }

    // ------------------------------------------------------------------ pets

    /** The native entity type of a MythicMobs id ({@code ArcanaRipplekit}) or an entity id ({@code ripplekit}), or null. */
    public static @Nullable EntityType<?> creatureType(String mythicOrId) {
        if (mythicOrId == null || mythicOrId.isBlank()) return null;
        EntityType<?> t = ArcanaEntities.byMythicId(mythicOrId.trim());
        if (t == null) t = ArcanaEntities.type(mythicOrId.trim().toLowerCase(Locale.ROOT));
        return t;
    }

    /** The species id path of a type ({@code sporeling}) for Hooks.PetWork. */
    public static String speciesId(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
    }

    /**
     * The owner's tamed creatures of this kind that follow them (not told to stay, not working), loaded in their level
     * within {@code radius} blocks (old PetsModule.followingNearby).
     */
    public static List<ArcanaCreature> followingNearby(ServerPlayer owner, String mythicType, double radius) {
        EntityType<?> type = creatureType(mythicType);
        if (owner == null || type == null) return List.of();
        Hooks.PetWork work = Feather.get().service(Hooks.PetWork.class);
        UUID id = owner.getUUID();
        AABB box = owner.getBoundingBox().inflate(radius);
        double r2 = radius * radius;
        List<ArcanaCreature> out = new ArrayList<>();
        for (Entity e : owner.level().getEntities((Entity) null, box, e -> e.getType() == type)) {
            if (!(e instanceof ArcanaCreature c) || !c.isAlive() || !c.isTame() || c.staying()) continue;
            if (c.getOwnerReference() == null || !id.equals(c.getOwnerReference().getUUID())) continue;
            if (c.distanceToSqr(owner) > r2) continue;
            if (work != null && work.working(c)) continue;
            out.add(c);
        }
        return out;
    }

    /** True when {@code e} is one of the player's tamed creatures. */
    public static boolean ownedBy(Entity e, ServerPlayer p) {
        return e instanceof ArcanaCreature c && c.isTame() && c.getOwnerReference() != null && p.getUUID().equals(c.getOwnerReference().getUUID());
    }
}
