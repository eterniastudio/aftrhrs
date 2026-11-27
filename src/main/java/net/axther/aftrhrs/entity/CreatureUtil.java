package net.axther.aftrhrs.entity;

import java.util.Locale;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** Shared helpers: MythicMobs DamageModifiers, Drops, creature feedback text (messages.yml keys, legacy fallback). */
public final class CreatureUtil {
    private CreatureUtil() {
    }

    /** The MythicMobs damage cause name of a damage source (DamageModifiers keys). */
    public static String cause(DamageSource source) {
        if (source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.CAMPFIRE)) return "FIRE";
        if (source.is(DamageTypes.ON_FIRE)) return "FIRE_TICK";
        if (source.is(DamageTypes.LAVA)) return "LAVA";
        if (source.is(DamageTypes.HOT_FLOOR)) return "HOT_FLOOR";
        if (source.is(DamageTypeTags.IS_FALL)) return "FALL";
        if (source.is(DamageTypeTags.IS_DROWNING)) return "DROWNING";
        if (source.is(DamageTypeTags.IS_FREEZING)) return "FREEZE";
        if (source.is(DamageTypeTags.IS_LIGHTNING)) return "LIGHTNING";
        if (source.is(DamageTypes.IN_WALL)) return "SUFFOCATION";
        if (source.is(DamageTypeTags.IS_EXPLOSION)) return "ENTITY_EXPLOSION";
        if (source.is(DamageTypeTags.IS_PROJECTILE)) return "PROJECTILE";
        if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)) return "POISON";
        if (source.is(DamageTypes.WITHER)) return "WITHER";
        if (source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.STING)) return "ENTITY_ATTACK";
        return source.getMsgId().toUpperCase(Locale.ROOT);
    }

    /** The damage multiplier for {@code source} (1 if the species has no modifier for it). */
    public static float damageMultiplier(Species s, DamageSource source) {
        Float m = s.damageMods.get(cause(source));
        return m == null ? 1f : m;
    }

    /** Drops the species' MythicMobs drop list (items and custom ex-Oraxen items; XP is handled by the base class). */
    public static void dropLoot(Mob mob, Species s, ServerLevel level) {
        for (Species.Drop d : s.drops) {
            if (d.exp() > 0) continue;
            if (mob.getRandom().nextDouble() >= d.chance()) continue;
            Item item = d.item();
            if (item == null && d.custom() != null) item = customItem(d.custom());
            if (item == null) continue;
            int n = d.min() + (d.max() > d.min() ? mob.getRandom().nextInt(d.max() - d.min() + 1) : 0);
            if (n > 0) mob.spawnAtLocation(level, new ItemStack(item, n));
        }
    }

    public static int dropExp(Mob mob, Species s) {
        int xp = 0;
        for (Species.Drop d : s.drops) if (d.exp() > 0 && mob.getRandom().nextDouble() < d.chance()) xp += d.exp();
        return xp;
    }

    /** A custom (ex-Oraxen) item: {@code aftrhrs:<id>} once the content port registers it, else null. */
    public static @Nullable Item customItem(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(AftrHrs.id(id));
        return item == Items.AIR ? null : item;
    }

    /** True if {@code stack} is the custom (ex-Oraxen) item {@code id}: {@code aftrhrs:<id>} or custom_data oraxen:id. */
    public static boolean isCustom(ItemStack stack, String id) {
        if (stack.isEmpty()) return false;
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key.getNamespace().equals(AftrHrs.ID) && key.getPath().equals(id)) return true;
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        var tag = data.copyTag();
        return id.equals(tag.getStringOr("oraxen:id", "")) || id.equals(tag.getStringOr("aftrhrs:id", ""));
    }

    /** {@code &7<name> &7will stay here.} style text: legacy color codes to a component. */
    public static Component legacy(String text) {
        return net.axther.aftrhrs.core.Adv.vanilla(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(text));
    }

    /** @deprecated send creature feedback through {@link #say} (a messages.yml key) */
    @Deprecated
    public static void actionBar(ServerPlayer p, String legacyText) {
        p.sendOverlayMessage(legacy(legacyText));
    }

    /** @deprecated send creature feedback through {@link #say} (a messages.yml key) */
    @Deprecated
    public static void message(ServerPlayer p, String legacyText) {
        p.sendSystemMessage(legacy(legacyText));
    }

    /** Resolves the {@code <tag>} placeholders of a legacy fallback: only the caller's resolvers, no MiniMessage tags. */
    private static final net.kyori.adventure.text.minimessage.MiniMessage FALLBACK_TAGS = net.kyori.adventure.text.minimessage.MiniMessage.builder()
            .tags(TagResolver.empty()).build();
    private static final java.util.regex.Pattern FALLBACK_TAG = java.util.regex.Pattern.compile("<[a-z][a-z0-9_-]*>");

    /**
     * Creature feedback from messages.yml {@code creatures.<key>} (bar: the action bar, else chat). Without Feather, or
     * while the key is missing from messages.yml, the legacy {@code &}-colored fallback is sent instead; its
     * {@code <tag>} placeholders take the same resolvers (inserted literally, so neither path parses a value's text).
     */
    public static void say(ServerPlayer p, boolean bar, String key, String legacyFallback, TagResolver... tags) {
        if (p == null) return;
        Feather f = Feather.get();
        if (f == null || !f.text().has("creatures." + key)) {
            Component c = net.axther.aftrhrs.core.Adv.vanilla(fallback(legacyFallback, tags));
            if (bar) p.sendOverlayMessage(c);
            else p.sendSystemMessage(c);
            return;
        }
        if (bar) f.text().actionBar(p, "creatures." + key, tags);
        else f.text().send(p, "creatures." + key, tags);
    }

    /** {@link #say} about a creature: adds {@code <pet>}, its name as a component (a name tag's text stays literal). */
    public static void say(ServerPlayer p, boolean bar, String key, net.minecraft.world.entity.Entity pet, String legacyFallback,
                           TagResolver... tags) {
        TagResolver[] all = java.util.Arrays.copyOf(tags, tags.length + 1);
        all[tags.length] = net.axther.aftrhrs.core.Text.component("pet", net.axther.aftrhrs.core.Adv.adventure(pet.getName()));
        say(p, bar, key, legacyFallback, all);
    }

    /** The legacy fallback with its {@code <tag>} placeholders replaced by the resolvers' (literal) values. */
    static net.kyori.adventure.text.Component fallback(String legacyText, TagResolver... tags) {
        net.kyori.adventure.text.Component c = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(legacyText);
        if (legacyText.indexOf('<') < 0) return c;
        TagResolver all = TagResolver.resolver(tags);
        return c.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().match(FALLBACK_TAG)
                .replacement((m, b) -> all.has(m.group().substring(1, m.group().length() - 1)) ? FALLBACK_TAGS.deserialize(m.group(), all) : b)
                .build());
    }
}
