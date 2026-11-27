package net.axther.aftrhrs.feature.farming;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureSpec;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.feature.coast.Coast;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cooking at the kitchen furniture: a right-click on a station (stove, cutting board, stew pot) opens a dialog of the
 * meals made there, the ones you have ingredients for first. A meal's quality is the average quality of the crops that
 * went in (best non-Prize crops are used first) and stretches its buffs. Stoves keep their oven: sneaking, or having
 * nothing to cook, opens the oven as before.
 */
final class Cooking {
    private final FarmingModule m;

    Cooking(FarmingModule module) {
        this.m = module;
    }

    private FarmConfig cfg() {
        return m.config();
    }

    /** Station click; true when the dialog opened (the furniture's own use is skipped). */
    boolean click(ServerPlayer p, FurnitureContext ctx, String station) {
        FurnitureSpec spec = ArcanaContent.spec(ctx.id());
        boolean storage = spec != null && spec.hasBlockEntity();
        List<FarmConfig.Meal> here = mealsAt(station);
        if (here.isEmpty()) return false;
        boolean any = false;
        for (FarmConfig.Meal meal : here) if (canMake(p, meal)) any = true;
        if (storage && (p.isSecondaryUseActive() || !any)) return false;   // the oven
        if (p.isSecondaryUseActive() && p.getMainHandItem().isEmpty()) return false;   // turning it
        if (!m.allowed(p, ctx.level(), ctx.origin(), ClaimAction.ACCESS)) return true;
        open(p, ctx.level(), ctx.origin(), station);
        return true;
    }

    private List<FarmConfig.Meal> mealsAt(String station) {
        List<FarmConfig.Meal> out = new ArrayList<>();
        for (FarmConfig.Meal meal : cfg().meals.values()) if (meal.stations.contains(station)) out.add(meal);
        return out;
    }

    void open(ServerPlayer p, ServerLevel level, BlockPos station, String kind) {
        Dialogs.Menu menu = m.dialogs().menu(m.msg("cook.title", Text.unparsed("station", FarmingModule.pretty(kind))));
        menu.text(m.msg("cook.body"), 300);
        List<FarmConfig.Meal> meals = mealsAt(kind);
        meals.sort(Comparator.comparing((FarmConfig.Meal meal) -> !canMake(p, meal)));
        for (FarmConfig.Meal meal : meals) {
            boolean ok = canMake(p, meal);
            Component tip = m.msg("cook.tooltip", Text.unparsed("ingredients", ingredientList(meal)), Text.unparsed("effects", effectList(meal)));
            menu.button(m.msg(ok ? "cook.meal-ready" : "cook.meal-missing", Text.unparsed("meal", meal.name)), tip, 150, pl -> {
                cook(pl, level, station, meal);
                if (!pl.hasDisconnected() && pl.level() == level && pl.position().distanceToSqr(Vec3.atCenterOf(station)) < 64) open(pl, level, station, kind);
            });
        }
        menu.columns(2);
        menu.exit(m.plugin().text().msg("menu.close"), null);
        menu.open(p);
    }

    private static String ingredientList(FarmConfig.Meal meal) {
        List<String> parts = new ArrayList<>();
        for (FarmConfig.ItemRule r : meal.ingredients) parts.add(r.amount + "× " + label(r.raw));
        return String.join(", ", parts);
    }

    private static String label(String raw) {
        if (raw.equals("#fish")) return "fish";
        String s = raw.startsWith("oraxen:") ? raw.substring(7) : raw.startsWith("#") ? raw.substring(1) : raw;
        if (s.startsWith("arcana_crop_")) s = s.substring(12);
        else if (s.startsWith("arcana_")) s = s.substring(7);
        if (s.contains(":")) s = s.substring(s.indexOf(':') + 1);
        return s.replace('_', ' ');
    }

    private static String effectList(FarmConfig.Meal meal) {
        if (meal.effects.isEmpty()) return "food";
        List<String> parts = new ArrayList<>();
        for (FarmConfig.Effect e : meal.effects) parts.add(FarmingModule.pretty(e.name()) + " " + roman(e.level()));
        return String.join(", ", parts);
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(n);
        };
    }

    // ------------------------------------------------------------------ ingredients

    private record Pick(int slot, int amount, int quality) {
    }

    /** Which inventory slots a meal would use, or null when something is missing. Best non-Prize crops first. */
    private List<Pick> pick(ServerPlayer p, FarmConfig.Meal meal) {
        Inventory inv = p.getInventory();
        int size = inv.getNonEquipmentItems().size();
        int[] used = new int[size];
        List<Pick> out = new ArrayList<>();
        for (FarmConfig.ItemRule rule : meal.ingredients) {
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && rule.matches(s, Coast.id(s))) slots.add(i);
            }
            // fine, then normal, then prize (Prize crops are kept for the Harvest Fair as long as possible)
            slots.sort(Comparator.comparingInt(i -> {
                int q = FarmingModule.qualityOf(inv.getItem(i));
                return q == 1 ? 0 : q == 0 ? 1 : 2;
            }));
            int need = rule.amount;
            for (int i : slots) {
                if (need <= 0) break;
                int free = inv.getItem(i).getCount() - used[i];
                if (free <= 0) continue;
                int take = Math.min(free, need);
                used[i] += take;
                need -= take;
                out.add(new Pick(i, take, FarmingModule.qualityOf(inv.getItem(i))));
            }
            if (need > 0) return null;
        }
        return out;
    }

    boolean canMake(ServerPlayer p, FarmConfig.Meal meal) {
        return pick(p, meal) != null;
    }

    private void cook(ServerPlayer p, ServerLevel level, BlockPos station, FarmConfig.Meal meal) {
        if (p.level() != level || p.position().distanceToSqr(Vec3.atCenterOf(station)) > 8 * 8) {
            m.bar(p, "cook-far");
            return;
        }
        // the dialog can outlive the station (broken while it was open): no cooking without one
        if (cfg().stationOf(ArcanaContent.id(level.getBlockState(station))) == null) {
            m.bar(p, "cook-far");
            return;
        }
        List<Pick> picks = pick(p, meal);
        if (picks == null) {
            m.bar(p, "cook-missing", Text.unparsed("meal", meal.name));
            return;
        }
        ItemStack out = m.build(meal.itemId(), 1);
        if (out == null) {
            m.bar(p, "not-ready");
            return;
        }
        Inventory inv = p.getInventory();
        int qSum = 0, qCount = 0;
        for (Pick pk : picks) {
            String id = Coast.id(inv.getItem(pk.slot()));
            if (id != null && id.startsWith("arcana_crop_")) {
                qSum += pk.quality() * pk.amount();
                qCount += pk.amount();
            }
        }
        for (Pick pk : picks) {
            ItemStack s = inv.getItem(pk.slot());
            if (s.isEmpty()) continue;
            s.shrink(pk.amount());
            if (s.isEmpty()) inv.setItem(pk.slot(), ItemStack.EMPTY);
        }
        int quality = qCount == 0 ? 0 : (int) Math.round(qSum / (double) qCount);
        if (quality > 0) {
            Pdc.itemPutString(out, FarmingModule.MEAL, FarmingModule.QUALITIES[quality]);
            Coast.addLore(out, m.text().item(quality == 2 ? cfg().lorePrize : cfg().loreFine));
        }
        Coast.give(p, out);
        Vec3 at = Vec3.atBottomCenterOf(station).add(0, 0.8, 0);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y, at.z, 3, 0.1, 0.05, 0.1, 0.01);
        Coast.sound(level, at, "block.smoker.smoke", SoundSource.BLOCKS, 0.8f, 1.2f);
        Coast.sound(level, at, "entity.player.burp", SoundSource.BLOCKS, 0.4f, 1.4f);
        m.plugin().stat(p.getUUID(), "meals-cooked", 1);
        m.plugin().advance(p, "event", "cook");
        m.send(p, "cooked", Text.unparsed("meal", meal.name), Text.component("quality", m.msg("quality-" + FarmingModule.QUALITIES[quality])));
    }

    // ------------------------------------------------------------------ eating

    /** The meal's buffs, longer for better quality. */
    void applyBuffs(ServerPlayer p, ItemStack meal, String id) {
        FarmConfig.Meal def = cfg().mealsByItem.get(id);
        if (def == null) return;
        String q = Pdc.itemString(meal, FarmingModule.MEAL);
        double mult = cfg().qualityMultiplier.getOrDefault(q == null ? "normal" : q, 1.0);
        for (FarmConfig.Effect e : def.effects) {
            int ticks = (int) Math.max(1, Math.round(e.seconds() * 20 * mult));
            m.applyEffect(p, new MobEffectInstance(e.type(), ticks, e.level() - 1, false, true, true));
        }
    }

    /** Meals whose item can't be eaten the vanilla way: right-click eats one (food + buffs). */
    boolean eatFallback(ServerPlayer p, ItemStack held, String id) {
        if (held.has(DataComponents.CONSUMABLE) || held.has(DataComponents.FOOD)) return false;
        FarmConfig.Meal def = cfg().mealsByItem.get(id);
        if (def == null) return false;
        var food = p.getFoodData();
        if (food.getFoodLevel() >= 20 && def.effects.isEmpty()) return false;
        ItemStack one = held.copy();
        Coast.takeOne(p, InteractionHand.MAIN_HAND);
        food.eat(cfg().fallbackFood, cfg().fallbackSaturation / Math.max(1, cfg().fallbackFood * 2f));
        applyBuffs(p, one, id);
        Coast.sound(p.level(), p.position(), "entity.generic.eat", SoundSource.PLAYERS, 0.8f, 1f);
        return true;
    }
}
