package net.axther.aftrhrs.feature.fishing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.mixin.coast.FishingHookAccessor;
import net.axther.aftrhrs.net.Companion;
import net.axther.aftrhrs.net.Wire;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The reel-in fight on the server (fishing.yml {@code fight}, docs/fishing.md "The fight"). A rare or better fish, or
 * any fish heavier than {@code heavy-grams}, hooked by a player whose client has the Companion {@code fishing}
 * feature, isn't landed on the reel: the hook stays in the water (its bite timers frozen), the client gets the fight's
 * {@link FishFight.Params} ({@code fish_fight}) and plays it, then reports its inputs ({@code fish_fight_result}).
 * The server replays them ({@link FishFight#judge}) and only a replayed LANDED lands the fish, through the hook's own
 * retrieve so the loot, XP, stats and rod damage stay vanilla's. No report in time, a dead hook or an impossible report
 * loses the fish. Players without the feature, or with {@code fight.enabled: false}, get the old strike window.
 */
final class FishFights {
    private final FishingModule m;
    boolean enabled = true;
    int minRank = 2, heavyGrams = 4000, maxTicks = 900;
    long slackMs = 1500;
    double unbreakingLine = 0.06;
    private long nextId = 1;

    FishFights(FishingModule m) {
        this.m = m;
    }

    void reload(Yaml y) {
        enabled = y.getBoolean("fight.enabled", true);
        minRank = Math.max(0, FishTable.rank(y.getString("fight.min-rarity", "rare")));
        heavyGrams = Math.max(1, y.getInt("fight.heavy-grams", 4000));
        maxTicks = Math.max(100, Math.min(6000, y.getInt("fight.max-seconds", 45) * 20));
        slackMs = Math.max(0, y.getLong("fight.latency-slack-ms", 1500));
        unbreakingLine = Math.max(0, y.getDouble("fight.unbreaking-line-per-level", 0.06));
    }

    static void register() {
        Companion.on("fish_fight_result", "fishing", (p, msg) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.fights.result(p, msg);
        });
    }

    /** Does this bite become a fight (instead of the strike window or a plain catch)? */
    boolean wants(ServerPlayer p, FishingListener.Pending pd) {
        if (!enabled || pd.species == null || pd.forcedItem != null) return false;
        if (pd.species.rank() < minRank && pd.grams < heavyGrams) return false;
        return Companion.has(p, "fishing");
    }

    /** The fight's numbers for this fish and this rod. */
    FishFight.Params params(ServerPlayer p, FishingListener.Pending pd) {
        FishTable.Species s = pd.species;
        boolean trophy = FishWeight.sizeClass(pd.grams, s.minGrams(), s.typicalGrams(), s.maxGrams()) == FishWeight.SizeClass.TROPHY;
        float strength = FishFight.strength(s.rank(), pd.grams, trophy);
        ItemStack rod = m.rodStack(p);
        String rodId = m.items.idOf(rod);
        FishTable.Gear g = rodId == null ? null : m.table.rods.get(rodId);
        double reel = 1 + m.table.luckOfTheSea.fightPower() * pd.luck + (g == null ? 0 : g.fight());
        int unbreaking = FishingModule.enchant(p.level(), rod, Enchantments.UNBREAKING);
        double line = 1 + unbreakingLine * unbreaking;
        // Steady Line (enchantment): the line holds more
        line += net.axther.aftrhrs.feature.enchants.Enchants.STEADY_LINE_PER_LEVEL
                * net.axther.aftrhrs.feature.enchants.Enchants.level(rod, net.axther.aftrhrs.feature.enchants.Enchants.STEADY_LINE);
        // a smithed rod: its limb and quality pull harder, Braided Line holds more
        reel += net.axther.aftrhrs.feature.smithing.SmithedRodItem.reelBonus(rod);
        line += net.axther.aftrhrs.feature.smithing.SmithedRodItem.lineBonus(rod);
        return new FishFight.Params(ThreadLocalRandom.current().nextLong(), strength, (float) reel, (float) line, maxTicks);
    }

    /** Starts the fight: the hook stays in (no new bite comes), the client gets the screen. */
    void start(ServerPlayer p, FishingHook hook, FishingListener.Pending pd) {
        pd.params = params(p, pd);
        pd.fightId = nextId++;
        pd.fightStart = System.currentTimeMillis();
        pd.fighting = true;
        freeze(hook);
        JsonObject o = Companion.message("fish_fight");
        o.addProperty("id", pd.fightId);
        o.addProperty("seed", Long.toString(pd.params.seed()));
        o.addProperty("strength", pd.params.strength());
        o.addProperty("reel", pd.params.reelPower());
        o.addProperty("line", pd.params.lineStrength());
        o.addProperty("max", pd.params.maxTicks());
        o.addProperty("name", pd.species.name());
        o.addProperty("rarity", pd.species.rarity());
        o.addProperty("color", m.companionBridge.color(pd.species.rarity()));
        Companion.send(p, o);
        ServerLevel w = (ServerLevel) hook.level();
        m.playSound(w, hook.position(), pd.species.rank() >= 3 ? m.strikeSoundLegendary : m.strikeSound);
        w.sendParticles(ParticleTypes.SPLASH, hook.getX(), hook.getY() + 0.3, hook.getZ(), 40, 0.35, 0.1, 0.35, 0.2);
        m.plugin().text().actionBar(p, "fishing.fight.start", Text.unparsed("fish", pd.species.name()));
    }

    static void freeze(FishingHook hook) {
        FishingHookAccessor acc = (FishingHookAccessor) hook;
        acc.aftrhrs$setNibble(0);
        acc.aftrhrs$setHooked(0);
        acc.aftrhrs$setLured(1_000_000);
        hook.getEntityData().set(FishingHookAccessor.aftrhrs$biting(), false);
    }

    /** The client's report. */
    private void result(ServerPlayer p, JsonObject msg) {
        FishingListener.Pending pd = m.listener.pending.get(p.getUUID());
        if (pd == null || !pd.fighting || Wire.num(msg, "id", -1) != pd.fightId) return;
        int ticks = Wire.num(msg, "ticks", 0);
        int[] toggles = toggles(msg.get("toggles"), ticks);
        FishFight.Outcome o = toggles == null ? FishFight.Outcome.INVALID
                : FishFight.judge(pd.params, ticks, toggles, System.currentTimeMillis() - pd.fightStart, slackMs);
        if (o == FishFight.Outcome.INVALID) m.plugin().getLogger().info("Fishing: rejected an impossible fight report from {} ({} ticks).", net.axther.aftrhrs.core.Names.name(p), ticks);
        finish(p, pd, o);
    }

    private static int[] toggles(JsonElement e, int ticks) {
        if (e == null || !e.isJsonArray()) return new int[0];
        JsonArray a = e.getAsJsonArray();
        if (a.size() > Math.max(0, ticks)) return null;
        int[] out = new int[a.size()];
        try {
            for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        } catch (RuntimeException ex) {
            return null;
        }
        return out;
    }

    /** Ends a fight with this outcome: lands the fish through the hook, or loses it. */
    void finish(ServerPlayer p, FishingListener.Pending pd, FishFight.Outcome o) {
        pd.fighting = false;
        FishingHook hook = p.fishing != null && p.fishing.getUUID().equals(pd.hook) ? p.fishing : null;
        JsonObject end = Companion.message("fish_fight_end");
        end.addProperty("id", pd.fightId);
        end.addProperty("outcome", o.name().toLowerCase(java.util.Locale.ROOT));
        Companion.send(p, end);
        if (o == FishFight.Outcome.LANDED && hook != null) {
            pd.won = true;
            ((FishingHookAccessor) hook).aftrhrs$setNibble(20);
            InteractionHand hand = FishingModule.isRod(p.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack rod = p.getItemInHand(hand);
            int dmg = hook.retrieve(rod);
            if (dmg > 0 && !rod.isEmpty()) rod.hurtAndBreak(dmg, p, hand.asEquipmentSlot());
            m.playSound((ServerLevel) p.level(), p.position(), "entity.fishing_bobber.retrieve 1.0 0.6");
            if (m.listener.pending.get(p.getUUID()) == pd) m.listener.pending.remove(p.getUUID());
            return;
        }
        m.listener.pending.remove(p.getUUID());
        String key = switch (o) {
            case SNAPPED -> "fishing.fight.snapped";
            case INVALID -> "fishing.fight.lost";
            case TIMEOUT -> "fishing.fight.tired";
            default -> "fishing.fight.escaped";
        };
        m.plugin().text().send(p, key, Text.unparsed("fish", pd.species == null ? "fish" : pd.species.name()));
        m.plugin().text().sound(p, "error");
        if (hook != null) {
            ServerLevel w = (ServerLevel) hook.level();
            w.sendParticles(ParticleTypes.SPLASH, hook.getX(), hook.getY() + 0.2, hook.getZ(), 25, 0.3, 0.1, 0.3, 0.15);
            if (o == FishFight.Outcome.SNAPPED) m.playSound(w, hook.position(), "entity.item.break 0.8 1.3");
            hook.discard();
        }
    }

    /** Every second: fights whose hook went (rod put away, too far) or whose time ran out are lost. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, FishingListener.Pending> e : Map.copyOf(m.listener.pending).entrySet()) {
            FishingListener.Pending pd = e.getValue();
            if (!pd.fighting) continue;
            ServerPlayer p = m.plugin().player(e.getKey());
            if (p == null) {
                m.listener.pending.remove(e.getKey());
                continue;
            }
            boolean hookGone = p.fishing == null || !p.fishing.getUUID().equals(pd.hook) || p.fishing.isRemoved();
            if (hookGone) finish(p, pd, FishFight.Outcome.ESCAPED);
            else if (now - pd.fightStart > pd.params.maxTicks() * 50L + slackMs + 3000) finish(p, pd, FishFight.Outcome.TIMEOUT);
            else {
                freeze(p.fishing);
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    FishingHook h = p.fishing;
                    ((ServerLevel) h.level()).sendParticles(ParticleTypes.SPLASH, h.getX(), h.getY() + 0.2, h.getZ(), 8, 0.25, 0.05, 0.25, 0.1);
                }
            }
        }
    }
}
