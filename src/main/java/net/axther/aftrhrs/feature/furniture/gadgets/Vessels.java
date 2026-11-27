package net.axther.aftrhrs.feature.furniture.gadgets;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Goblets and jars holding a drink (water, lava, milk, honey, a potion, a stew) and jars holding cookie-like food.
 * Right-click with a bucket, bottle or bowl of it to pour it in, with an empty bottle / bucket / bowl to take it
 * back, with an empty hand to drink (or eat) it at once. The liquid is drawn by an overlay item (see gadgets.yml).
 */
final class Vessels {
    enum Fluid {
        WATER, LAVA, MILK, HONEY, POTION, STEW;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static @Nullable Fluid of(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        boolean bottled() {
            return this == WATER || this == HONEY || this == POTION;
        }

        boolean bucketed() {
            return this == WATER || this == LAVA || this == MILK;
        }
    }

    /** One kind of vessel (goblets, jars). */
    static final class Kind {
        Set<String> ids = Set.of();
        int capacity = 1, bucketUnits = 1;
        final Set<Fluid> fluids = new HashSet<>();
        String fluidOverlay, cookieOverlay;
        final Map<Fluid, String> overlayNames = new HashMap<>();
        boolean cookies;
    }

    private record Source(Fluid fluid, int units, ItemStack back, @Nullable ItemStack proto) {
    }

    private final GadgetsModule g;
    final Kind goblets = new Kind();
    final Kind jars = new Kind();
    private final Set<Item> cookieItems = new HashSet<>();
    private final Set<String> cookieIds = new HashSet<>();

    Vessels(GadgetsModule g) {
        this.g = g;
    }

    void load(Yaml gob, Yaml jar) {
        read(goblets, gob, List.of("arcana_iron_goblet", "arcana_gold_goblet", "arcana_copper_goblet"), 1, 1,
                "arcana_curio_fx_goblet_<fluid>", null, List.of("water", "lava", "milk", "honey", "potion", "stew"));
        read(jars, jar, List.of("arcana_jar"), 12, 4,
                "arcana_curio_fx_jar_<fluid>_<level>", "arcana_curio_fx_jar_cookies_<level>", List.of("water", "lava", "milk", "honey", "potion"));
        jars.cookies = true;
        cookieItems.clear();
        cookieIds.clear();
        List<String> cookieList = jar.contains("cookies") ? jar.getStringList("cookies") : List.of("minecraft:cookie");
        for (String s : cookieList) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("minecraft:")) {
                Identifier id = Identifier.tryParse(t);
                Item m = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (m != null && m != Items.AIR) cookieItems.add(m);
                else g.warnOnce("cookie:" + t, "gadgets.yml jars.cookies: unknown item " + s);
            } else if (!t.isEmpty()) {
                cookieIds.add(t.startsWith("oraxen:") ? t.substring(7) : t);
            }
        }
    }

    private void read(Kind k, Yaml c, List<String> defIds, int defCap, int defBucket, String defFluid,
                      String defCookie, List<String> defFluids) {
        k.ids = GadgetsModule.lowerSet(c.contains("ids") ? c.getStringList("ids") : defIds);
        k.capacity = Math.max(1, c.getInt("capacity", defCap));
        k.bucketUnits = Math.max(1, Math.min(k.capacity, c.getInt("bucket-units", defBucket)));
        k.fluids.clear();
        for (String f : c.contains("fluids") ? c.getStringList("fluids") : defFluids) {
            Fluid fl = Fluid.of(f.trim());
            if (fl == null) g.warnOnce("fluid:" + f, "gadgets.yml: unknown fluid '" + f + "' (water, lava, milk, honey, potion, stew)");
            else k.fluids.add(fl);
        }
        k.fluidOverlay = c.getString("overlay", defFluid);
        k.cookieOverlay = c.getString("cookie-overlay", defCookie);
        k.overlayNames.clear();
        k.overlayNames.put(Fluid.STEW, "mushroom_stew");
        Yaml names = c.getConfigurationSection("overlay-names");
        if (names != null) {
            for (String key : names.getKeys(false)) {
                Fluid fl = Fluid.of(key);
                if (fl != null) k.overlayNames.put(fl, names.getString(key));
            }
        }
    }

    @Nullable Kind kindOf(String id) {
        if (id == null) return null;
        if (goblets.ids.contains(id)) return goblets;
        if (jars.ids.contains(id)) return jars;
        return null;
    }

    boolean isVessel(String id) {
        return kindOf(id) != null;
    }

    // ------------------------------------------------------------------ overlays

    /** The overlay item id the piece shows now (liquid level / cookies), or null. */
    @Nullable String overlay(GadgetBlockEntity be, String id) {
        Kind k = kindOf(id);
        if (k == null || be.units <= 0) return null;
        int level = Math.max(1, Math.min(4, (int) Math.ceil(be.units * 4.0 / k.capacity)));
        String overlay = null;
        if ("fluid".equals(be.kind)) {
            Fluid f = Fluid.of(be.fluid);
            if (f == null || k.fluidOverlay == null) return null;
            overlay = k.fluidOverlay.replace("<fluid>", k.overlayNames.getOrDefault(f, f.key()));
        } else if ("cookies".equals(be.kind) && k.cookieOverlay != null) {
            overlay = k.cookieOverlay;
        }
        return overlay == null ? null : overlay.replace("<level>", String.valueOf(level));
    }

    // ------------------------------------------------------------------ interaction

    boolean interact(ServerPlayer p, ServerLevel level, GadgetBlockEntity be, String id) {
        Kind k = kindOf(id);
        if (k == null) return false;
        BlockPos pos = be.getBlockPos();
        Vec3 at = Vec3.atCenterOf(pos);
        ItemStack held = p.getMainHandItem();
        String kind = be.units <= 0 ? null : be.kind;
        int units = be.units;
        Fluid fluid = Fluid.of(be.fluid);
        ItemStack proto = be.proto;

        if (held.isEmpty()) {
            if ("fluid".equals(kind) && fluid != null) {
                if (!g.mayUse(p, level, pos, ClaimAction.CONTAINER)) return true;
                if (drink(p, level, fluid, proto, at)) take(be, units, 1);
                return true;
            }
            if ("cookies".equals(kind) && !proto.isEmpty()) {
                if (!g.mayUse(p, level, pos, ClaimAction.CONTAINER)) return true;
                if (p.getFoodData().getFoodLevel() < 20 || GadgetsModule.creative(p)) {
                    eat(p, level, proto);
                } else {
                    GadgetsModule.give(p, proto.copy());
                    g.msg(p, g.message("jar-took", "<muted>You take one out."));
                }
                take(be, units, 1);
                return true;
            }
            return false;
        }

        // Pour a drink in.
        Source src = sourceOf(held, k);
        if (src != null) {
            if (!k.fluids.contains(src.fluid())) return false;
            if (!g.mayUse(p, level, pos, ClaimAction.CONTAINER)) return true;
            if (kind != null) {
                boolean same = "fluid".equals(kind) && fluid == src.fluid()
                        && (src.proto() == null || (!proto.isEmpty() && ItemStack.isSameItemSameComponents(proto, src.proto())));
                if (!same) {
                    g.msg(p, g.message("vessel-other", "<bad>There's something else in it."));
                    return true;
                }
            }
            if (units + src.units() > k.capacity) {
                g.msg(p, g.message("vessel-full", "<muted>It's full."));
                return true;
            }
            be.kind = "fluid";
            be.fluid = src.fluid().key();
            be.units = units + src.units();
            be.proto = src.proto() == null ? ItemStack.EMPTY : src.proto();
            GadgetsModule.useOne(p, InteractionHand.MAIN_HAND, src.back());
            pourFx(level, at, src.fluid());
            g.refresh(be);
            return true;
        }

        // Take a drink out with an empty container.
        if (held.is(Items.GLASS_BOTTLE) || held.is(Items.BUCKET) || held.is(Items.BOWL)) {
            if (!"fluid".equals(kind) || fluid == null) return false;
            if (!g.mayUse(p, level, pos, ClaimAction.CONTAINER)) return true;
            ItemStack out = null;
            int need = 1;
            if (held.is(Items.GLASS_BOTTLE) && fluid.bottled()) {
                out = switch (fluid) {
                    case WATER -> PotionContents.createItemStack(Items.POTION, Potions.WATER);
                    case HONEY -> new ItemStack(Items.HONEY_BOTTLE);
                    default -> proto.isEmpty() ? null : proto.copy();
                };
            } else if (held.is(Items.BUCKET) && fluid.bucketed()) {
                need = k.bucketUnits;
                out = new ItemStack(switch (fluid) {
                    case LAVA -> Items.LAVA_BUCKET;
                    case MILK -> Items.MILK_BUCKET;
                    default -> Items.WATER_BUCKET;
                });
            } else if (held.is(Items.BOWL) && fluid == Fluid.STEW) {
                out = proto.isEmpty() ? new ItemStack(Items.MUSHROOM_STEW) : proto.copy();
            }
            if (out == null) {
                g.msg(p, g.message("vessel-wrong-container", "<muted>That won't hold it."));
                return true;
            }
            if (units < need) {
                g.msg(p, g.message("vessel-not-enough", "<muted>There isn't enough left for that."));
                return true;
            }
            out.setCount(1);
            GadgetsModule.useOne(p, InteractionHand.MAIN_HAND, out);
            take(be, units, need);
            pourFx(level, at, fluid);
            return true;
        }

        // Cookies into a jar.
        if (k.cookies && isCookie(held)) {
            if (!g.mayUse(p, level, pos, ClaimAction.CONTAINER)) return true;
            if (kind != null && !("cookies".equals(kind) && !proto.isEmpty() && ItemStack.isSameItemSameComponents(proto, held))) {
                g.msg(p, g.message("vessel-other", "<bad>There's something else in it."));
                return true;
            }
            int room = k.capacity - units;
            if (room <= 0) {
                g.msg(p, g.message("vessel-full", "<muted>It's full."));
                return true;
            }
            int add = p.isSecondaryUseActive() ? Math.min(room, held.getCount()) : 1;
            be.kind = "cookies";
            be.units = units + add;
            be.proto = held.copyWithCount(1);
            be.fluid = null;
            if (!GadgetsModule.creative(p)) held.shrink(add);
            GadgetsModule.sound(level, at, "block.decorated_pot.insert", SoundSource.BLOCKS, 0.7f, 1.3f);
            g.refresh(be);
            return true;
        }
        return false;
    }

    /** Takes {@code n} units out, emptying the vessel at 0, and redraws it. */
    private void take(GadgetBlockEntity be, int units, int n) {
        int left = units - n;
        if (left <= 0) be.clearState();
        else be.units = left;
        g.refresh(be);
    }

    boolean isCookie(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        String id = GadgetsModule.idOf(s);
        if (id != null) return cookieIds.contains(id);
        return cookieItems.contains(s.getItem());
    }

    private @Nullable Source sourceOf(ItemStack held, Kind k) {
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
        ItemStack bucket = new ItemStack(Items.BUCKET);
        if (held.is(Items.WATER_BUCKET)) return new Source(Fluid.WATER, k.bucketUnits, bucket, null);
        if (held.is(Items.LAVA_BUCKET)) return new Source(Fluid.LAVA, k.bucketUnits, bucket, null);
        if (held.is(Items.MILK_BUCKET)) return new Source(Fluid.MILK, k.bucketUnits, bucket, null);
        if (held.is(Items.HONEY_BOTTLE)) return new Source(Fluid.HONEY, 1, bottle, null);
        if (held.is(Items.POTION)) {
            PotionContents pc = held.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (pc.is(Potions.WATER) && pc.customEffects().isEmpty()) return new Source(Fluid.WATER, 1, bottle, null);
            return new Source(Fluid.POTION, 1, bottle, held.copyWithCount(1));
        }
        if (held.is(Items.MUSHROOM_STEW) || held.is(Items.BEETROOT_SOUP) || held.is(Items.RABBIT_STEW) || held.is(Items.SUSPICIOUS_STEW)) {
            return new Source(Fluid.STEW, 1, new ItemStack(Items.BOWL), held.copyWithCount(1));
        }
        return null;
    }

    private void pourFx(ServerLevel level, Vec3 at, Fluid f) {
        Vec3 l = at.add(0, -0.4, 0);
        String sound = switch (f) {
            case LAVA -> "item.bucket.empty_lava";
            case HONEY -> "item.honey_bottle.drink";
            case STEW -> "block.decorated_pot.insert";
            default -> "item.bottle.fill";
        };
        GadgetsModule.sound(level, l, sound, SoundSource.BLOCKS, 0.7f, 1.1f);
        level.sendParticles(f == Fluid.LAVA ? ParticleTypes.LAVA : ParticleTypes.SPLASH, l.x, l.y, l.z, 6, 0.1, 0.05, 0.1, 0);
    }

    // ------------------------------------------------------------------ drinking and eating

    /** Applies the drink's vanilla effect. Returns false if nothing was drunk (lava). */
    private boolean drink(ServerPlayer p, ServerLevel level, Fluid f, ItemStack proto, Vec3 at) {
        Vec3 mouth = p.getEyePosition();
        switch (f) {
            case LAVA -> {
                g.msg(p, g.message("drink-lava", "<warn>You think better of drinking lava."));
                level.sendParticles(ParticleTypes.LAVA, at.x, at.y - 0.4, at.z, 3, 0.1, 0.05, 0.1, 0);
                return false;
            }
            case WATER -> {
                p.clearFire();
                g.msg(p, g.message("drink-water", "<sky>Refreshing."));
                level.sendParticles(ParticleTypes.SPLASH, mouth.x, mouth.y, mouth.z, 10, 0.2, 0.1, 0.2, 0);
            }
            case MILK -> p.removeAllEffects();
            case HONEY -> {
                p.removeEffect(MobEffects.POISON);
                feed(p, 6, 1.2f);
            }
            case POTION -> {
                PotionContents pc = proto.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                for (MobEffectInstance e : pc.getAllEffects()) {
                    if (e.getEffect().value().isInstantaneous()) e.getEffect().value().applyInstantaneousEffect(level, p, p, p, e.getAmplifier(), 1.0);
                    else p.addEffect(new MobEffectInstance(e));
                }
            }
            case STEW -> {
                if (proto.is(Items.RABBIT_STEW)) feed(p, 10, 12f);
                else feed(p, 6, 7.2f);
                SuspiciousStewEffects sse = proto.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
                if (sse != null) for (SuspiciousStewEffects.Entry e : sse.effects()) p.addEffect(e.createEffectInstance());
                GadgetsModule.sound(level, p.position(), "entity.generic.eat", SoundSource.PLAYERS, 1f, 1f);
                return true;
            }
        }
        GadgetsModule.sound(level, p.position(), f == Fluid.HONEY ? "item.honey_bottle.drink" : "entity.generic.drink", SoundSource.PLAYERS, 1f, 1f);
        return true;
    }

    private void eat(ServerPlayer p, ServerLevel level, ItemStack food) {
        int n = 2;
        float s = 0.4f;
        FoodProperties fp = food.get(DataComponents.FOOD);
        if (fp != null) {
            n = fp.nutrition();
            s = fp.saturation();
        }
        feed(p, n, s);
        GadgetsModule.sound(level, p.position(), "entity.generic.eat", SoundSource.PLAYERS, 1f, 1f);
        Vec3 m = p.getEyePosition().add(p.getLookAngle().scale(0.3));
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food.getItem()), m.x, m.y, m.z, 6, 0.1, 0.1, 0.1, 0.05);
    }

    private static void feed(ServerPlayer p, int food, float saturation) {
        var fd = p.getFoodData();
        int f = Math.min(20, fd.getFoodLevel() + food);
        fd.setFoodLevel(f);
        fd.setSaturation(Math.min(f, fd.getSaturationLevel() + saturation));
    }

    static String cap(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace('_', ' ');
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
