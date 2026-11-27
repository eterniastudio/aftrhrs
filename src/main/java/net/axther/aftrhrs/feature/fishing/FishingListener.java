package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.CreatureEvents;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.mixin.coast.FishingHookAccessor;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The catch itself, on the native fishing hook ({@code FeatherEvents.FISH_*}). The wait until a fish comes is
 * shortened by the rod, bait, a following Ripplekit and a wildlife Squallgull hotspot; on a bite the fish is rolled (so
 * a rare one can announce itself: its own splash, and the strike window starts); on the catch the vanilla fish is
 * swapped for the custom one (commons keep the vanilla fish part of the time, junk and treasure stay, junk may become a
 * beach find). Rare fish must be reeled within the strike window; a legendary needs several strikes in a row (the hook
 * is reset after each and bites again). Also: message bottles and Ripplekit clam cracking.
 */
final class FishingListener {
    static final class Pending {
        UUID hook;
        FishTable.Species species;
        String forcedItem;
        String bait;
        long biteAt;
        long lastStrikeAt;
        int strikes;
        /** The weight, rolled with the bite (Luck of the Sea and Lure at the bite), so a fight knows how big it is. */
        int grams;
        int luck, lure;
        /** Heavy Haul on the rod (heavier fish, more trophies). */
        int heavy;
        /** The reel-in fight ({@link FishFights}). */
        boolean fighting, won;
        long fightId, fightStart;
        FishFight.Params params;
    }

    private static final long STRIKE_GRACE_MS = 400;
    private final FishingModule m;
    final Map<UUID, Pending> pending = new HashMap<>();
    /** The bite being reeled in right now (FISH_REEL let it through; FISH_CATCH swaps the loot). */
    private Pending catching;
    private final Map<UUID, Long> crackCooldown = new HashMap<>();

    FishingListener(FishingModule m) {
        this.m = m;
    }

    void clear() {
        pending.clear();
        crackCooldown.clear();
        catching = null;
    }

    private static boolean rareOrBetter(FishTable.Species s) {
        return s != null && s.rank() >= 2;
    }

    static void register() {
        FeatherEvents.FISH_LURE.register((hook, p, ticks) -> {
            FishingModule m = FishingModule.active;
            return m == null ? ticks : m.listener.lure(p, hook, ticks);
        });
        FeatherEvents.FISH_BITE.register((hook, p) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.listener.bite(p, hook);
        });
        FeatherEvents.FISH_ESCAPE.register((hook, p) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.listener.failed(p, hook);
        });
        FeatherEvents.FISH_REEL.register((hook, p, rod, biting) -> {
            FishingModule m = FishingModule.active;
            return m == null || m.listener.reel(p, hook, biting);
        });
        FeatherEvents.FISH_CATCH.register((hook, p, rod, loot) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.listener.caught(p, hook, loot);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            FishingModule m = FishingModule.active;
            if (m == null || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            ItemStack held = p.getItemInHand(hand);
            if (held.isEmpty() || !m.bottleId.equals(m.items.idOf(held))) return InteractionResult.PASS;
            m.openBottle(p, hand);
            return InteractionResult.SUCCESS;
        });
        CreatureEvents.INTERACT.register((mob, player, hand) -> {
            FishingModule m = FishingModule.active;
            if (m == null || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            return mob instanceof net.minecraft.world.entity.Entity e && m.listener.crack(player, e) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            FishingModule m = FishingModule.active;
            if (m == null) return;
            m.listener.pending.remove(h.player.getUUID());
            m.listener.crackCooldown.remove(h.player.getUUID());
        });
    }

    private boolean debug(ServerPlayer p) {
        return m.debugBites.contains(p.getUUID());
    }

    /** A new wait until a fish comes: gear, Ripplekit and hotspot speed it up (Bukkit's setWaitTime at the cast). */
    private int lure(ServerPlayer p, FishingHook hook, int ticks) {
        Pending pd = pending.get(p.getUUID());
        if (pd != null && !hook.getUUID().equals(pd.hook)) pending.remove(p.getUUID());   // a new cast
        if (m.debugFast.contains(p.getUUID())) return 20 + ThreadLocalRandom.current().nextInt(21);
        double speed = m.table.speed(m.rodId(p), m.baitId(p));
        if (m.ripplekitNear(p)) speed *= m.ripplekitSpeed;
        int before = ticks;
        ticks = Math.max(1, (int) Math.round(ticks * speed));
        if (m.hotspotsEnabled && m.hotspot((ServerLevel) hook.level(), hook.position())) {
            double mult = m.hotspotBite();
            if (mult > 1) ticks = Math.max(1, (int) Math.round(ticks / mult));
            if (debug(p)) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("FISHDEBUG hotspot x" + mult + " wait " + before + " -> " + ticks));
        }
        return ticks;
    }

    private Pending roll(ServerPlayer p, FishingHook hook) {
        Pending pd = new Pending();
        pd.hook = hook.getUUID();
        pd.bait = m.baitId(p);
        String forced = m.debugForce.remove(p.getUUID());
        if (forced != null) {
            pd.species = m.table.species.get(forced);
            if (pd.species == null) pd.forcedItem = forced;
            FishTable.Context c = m.context(p, (ServerLevel) hook.level(), hook.position(), hook.isOpenWaterFishing());
            pd.luck = c.luck();
            pd.lure = c.lure();
        } else {
            FishTable.Context c = m.context(p, (ServerLevel) hook.level(), hook.position(), hook.isOpenWaterFishing());
            pd.luck = c.luck();
            pd.lure = c.lure();
            pd.species = m.table.roll(c);
        }
        pd.heavy = net.axther.aftrhrs.feature.enchants.Enchants.level(m.rodStack(p), net.axther.aftrhrs.feature.enchants.Enchants.HEAVY_HAUL);
        if (pd.species != null) pd.grams = m.table.rollGrams(pd.species, pd.luck, pd.lure, pd.heavy);
        return pd;
    }

    private void bite(ServerPlayer p, FishingHook hook) {
        Pending pd = pending.get(p.getUUID());
        if (pd != null && pd.fighting) return;
        if (pd == null || !hook.getUUID().equals(pd.hook) || pd.strikes == 0) {
            pd = roll(p, hook);
            pending.put(p.getUUID(), pd);
        }
        pd.biteAt = System.currentTimeMillis();
        if (debug(p)) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("FISHDEBUG bite " + (pd.species == null
                    ? (pd.forcedItem == null ? "vanilla" : pd.forcedItem) : pd.species.id() + " " + pd.species.rarity()) + " strike " + pd.strikes));
        }
        boolean fight = m.fights.wants(p, pd);
        if (rareOrBetter(pd.species) && (m.strikeEnabled || fight)) {
            // a big one announces itself (with a fight coming, the strike count isn't shown)
            ServerLevel w = (ServerLevel) hook.level();
            Vec3 at = hook.position();
            boolean legendary = pd.species.rank() >= 3;
            m.playSound(w, at, legendary ? m.strikeSoundLegendary : m.strikeSound);
            w.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.3, at.z, legendary ? 60 : 35, 0.35, 0.1, 0.35, 0.2);
            w.sendParticles(ParticleTypes.BUBBLE_POP, at.x, at.y + 0.2, at.z, legendary ? 24 : 12, 0.3, 0.1, 0.3, 0.05);
            if (legendary) w.sendParticles(ParticleTypes.GLOW, at.x, at.y + 0.4, at.z, 10, 0.3, 0.2, 0.3, 0.02);
            m.plugin().text().actionBar(p, legendary && !fight ? "fishing.strike.bite-legendary" : "fishing.strike.bite-rare",
                    Text.unparsed("strike", pd.strikes + 1), Text.unparsed("strikes", m.legendaryStrikes));
        }
    }

    /** The bite ran out without a reel. Between legendary strikes it's the old bite's leftover. */
    private void failed(ServerPlayer p, FishingHook hook) {
        Pending pd = pending.get(p.getUUID());
        if (pd == null || !hook.getUUID().equals(pd.hook) || pd.biteAt <= 0 || pd.fighting || pd.won) return;
        pending.remove(p.getUUID());
        if (rareOrBetter(pd.species) && (m.strikeEnabled || m.fights.wants(p, pd))) escaped(p, hook, pd);
    }

    /** Reeling in a hook that isn't on an entity. False keeps the hook in the water (strikes, the same click twice). */
    private boolean reel(ServerPlayer p, FishingHook hook, boolean biting) {
        catching = null;
        Pending pd = pending.get(p.getUUID());
        boolean ours = pd != null && hook.getUUID().equals(pd.hook);
        if (ours && pd.won) {
            // the fight was won: FishFights lands it through this retrieve
            pending.remove(p.getUUID());
            catching = pd;
            return true;
        }
        if (ours && pd.fighting) {
            // a reel click while the fight runs (no fight screen up): that's letting go of the rod
            m.fights.finish(p, pd, FishFight.Outcome.ESCAPED);
            return false;
        }
        if (!biting) {
            // REEL_IN: nothing on the line
            if (ours && pd.strikes > 0 && System.currentTimeMillis() - pd.lastStrikeAt < STRIKE_GRACE_MS) return false;
            pending.remove(p.getUUID());
            if (ours && (pd.biteAt > 0 || pd.strikes > 0) && rareOrBetter(pd.species) && m.strikeEnabled) escaped(p, hook, pd);
            return true;
        }
        if (!ours) {
            pd = roll(p, hook);   // no bite seen (shouldn't happen): roll now, no strike window
        } else if (m.fights.wants(p, pd)) {
            // a big one: the fight starts, the hook stays in
            m.fights.start(p, hook, pd);
            return false;
        } else if (rareOrBetter(pd.species) && m.strikeEnabled) {
            if (pd.biteAt <= 0 && pd.strikes > 0 && System.currentTimeMillis() - pd.lastStrikeAt < STRIKE_GRACE_MS) return false;
            long late = System.currentTimeMillis() - pd.biteAt;
            if (pd.biteAt <= 0 || late > m.strikeWindowMs) {
                pending.remove(p.getUUID());
                escaped(p, hook, pd);
                hook.discard();
                return false;
            }
            if (pd.species.rank() >= 3 && pd.strikes + 1 < m.legendaryStrikes) {
                // A strike: the fish fights on. Keep the hook in, it bites again soon.
                pd.strikes++;
                pd.biteAt = 0;
                pd.lastStrikeAt = System.currentTimeMillis();
                FishingHookAccessor acc = (FishingHookAccessor) hook;
                acc.aftrhrs$setNibble(0);
                acc.aftrhrs$setHooked(0);
                acc.aftrhrs$setLured(m.rebiteMin + ThreadLocalRandom.current().nextInt(m.rebiteMax - m.rebiteMin + 1));
                hook.getEntityData().set(FishingHookAccessor.aftrhrs$biting(), false);
                ServerLevel w = (ServerLevel) hook.level();
                m.playSound(w, hook.position(), m.strikeHitSound);
                w.sendParticles(ParticleTypes.SPLASH, hook.getX(), hook.getY() + 0.3, hook.getZ(), 40, 0.4, 0.1, 0.4, 0.25);
                m.plugin().text().actionBar(p, "fishing.strike.hit", Text.unparsed("strike", pd.strikes),
                        Text.unparsed("strikes", m.legendaryStrikes));
                return false;
            }
        }
        pending.remove(p.getUUID());
        catching = pd;
        return true;
    }

    /** The catch: the first item of the vanilla loot may become the custom fish (or a shellfish, or a beach find). */
    private void caught(ServerPlayer p, FishingHook hook, List<ItemStack> loot) {
        Pending pd = catching;
        catching = null;
        if (pd == null || !hook.getUUID().equals(pd.hook)) pd = roll(p, hook);
        ServerLevel w = (ServerLevel) hook.level();
        BlockPos at = hook.blockPosition();
        ItemStack vanilla = loot.isEmpty() ? ItemStack.EMPTY : loot.get(0);
        boolean vanillaFish = FishItems.VANILLA_FISH.contains(vanilla.getItem());
        boolean treasure = FishItems.TREASURE.contains(vanilla.getItem());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        ItemStack result = null;
        FishTable.Species caught = null;
        int grams = 0;
        if (pd.forcedItem != null) {
            result = m.items.other(pd.forcedItem, 1);
        } else if (pd.species != null) {
            FishTable.Species s = pd.species;
            boolean replace;
            if (rareOrBetter(s)) replace = true;                         // it fought for this one
            else if (!vanillaFish) replace = false;                     // junk and treasure stay
            else replace = s.rank() > 0 || rnd.nextDouble() >= m.vanillaCommonShare;
            if (replace) {
                String shell = s.rank() < 2 && m.salt(w, at) && rnd.nextDouble() < m.shellfishChance ? m.pickWeighted(m.shellfishCatch) : null;
                ItemStack shellItem = shell == null ? null : m.items.other(shell, 1);
                if (shellItem != null) result = shellItem;
                else {
                    grams = pd.grams > 0 ? pd.grams : m.table.rollGrams(s, pd.luck, pd.lure, pd.heavy);
                    result = m.items.fish(s, grams, p.getUUID(), net.axther.aftrhrs.core.Names.name(p));
                    caught = s;
                }
            }
        }
        if (result == null && !vanilla.isEmpty() && !vanillaFish && !treasure && rnd.nextDouble() < m.extrasChance) {
            String extra = m.pickWeighted(m.salt(w, at) ? m.extrasSalt : m.extrasFresh);
            if (extra != null) result = m.items.other(extra, 1);
        }
        if (result != null) {
            if (loot.isEmpty()) loot.add(result);
            else loot.set(0, result);
        }
        m.useBait(p, pd.bait);
        if (caught != null) m.landed(p, caught, grams);
        // quests and analytics (Paper's QuestEvents and AnalyticsModule heard PlayerFishEvent CAUGHT_FISH)
        if (!loot.isEmpty()) {
            m.plugin().stat(p.getUUID(), "fish-caught", 1);
            for (ItemStack s : loot) m.countCatch(p, m.fishId(s), 1);
        }
        // a penguin fishing beside the player (entity/PenguinCreature fishing buddy) may bring a second fish: a fresh one of
        // the same species, no heavier than the one caught, added after the journal, records and catch counts above
        // (a bonus, not a catch of the player's own)
        ItemStack bonus = buddyFish(p, hook, loot, caught, grams, pd);
        if (bonus != null) loot.add(bonus);
        // Twin Hook (enchantment): a common or uncommon fish sometimes comes in as two, the second no heavier
        ItemStack twin = twinFish(p, loot, caught, grams, pd);
        if (twin != null) loot.add(twin);
    }

    private ItemStack twinFish(ServerPlayer p, List<ItemStack> loot, FishTable.Species caught, int grams, Pending pd) {
        if (loot.isEmpty()) return null;
        float chance = net.axther.aftrhrs.feature.enchants.EnchantsModule.twinHookChance(m.rodStack(p));
        if (chance <= 0 || ThreadLocalRandom.current().nextFloat() >= chance) return null;
        ItemStack first = loot.get(0);
        if (caught == null) {
            boolean fish = FishItems.VANILLA_FISH.contains(first.getItem()) && !first.is(net.minecraft.world.item.Items.PUFFERFISH);
            return fish ? first.copyWithCount(1) : null;
        }
        if (caught.rank() >= 2) return null;
        int g = grams <= 0 ? 0 : Math.min(grams, m.table.rollGrams(caught, pd.luck, pd.lure, pd.heavy));
        return m.items.fish(caught, g, p.getUUID(), net.axther.aftrhrs.core.Names.name(p));
    }

    private ItemStack buddyFish(ServerPlayer p, FishingHook hook, List<ItemStack> loot, FishTable.Species caught, int grams, Pending pd) {
        if (loot.isEmpty()) return null;
        ItemStack first = loot.get(0);
        boolean fish = caught != null || FishItems.VANILLA_FISH.contains(first.getItem()) && !first.is(net.minecraft.world.item.Items.PUFFERFISH);
        if (!fish || !net.axther.aftrhrs.entity.PenguinCreature.buddyDouble(p, hook.position())) return null;
        if (caught == null) return first.copyWithCount(1);
        int g = grams <= 0 ? 0 : Math.min(grams, m.table.rollGrams(caught, pd.luck, pd.lure, pd.heavy));
        return m.items.fish(caught, g, p.getUUID(), net.axther.aftrhrs.core.Names.name(p));
    }

    private void escaped(ServerPlayer p, FishingHook hook, Pending pd) {
        m.plugin().text().send(p, pd.species != null && pd.species.rank() >= 3 ? "fishing.strike.escaped-legendary" : "fishing.strike.escaped",
                Text.unparsed("fish", pd.species == null ? "fish" : pd.species.name()));
        m.plugin().text().sound(p, "error");
        if (hook != null && hook.level() instanceof ServerLevel w) {
            w.sendParticles(ParticleTypes.SPLASH, hook.getX(), hook.getY() + 0.2, hook.getZ(), 20, 0.3, 0.1, 0.3, 0.1);
        }
    }

    // ------------------------------------------------------------------ Ripplekit clam cracking

    /** Right-click on an entity holding a clam: true when it was a Ripplekit. */
    boolean crack(ServerPlayer p, Entity clicked) {
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || !m.clamId.equals(m.items.idOf(held))) return false;
        if (!m.isRipplekit(clicked)) return false;
        long now = System.currentTimeMillis();
        Long last = crackCooldown.get(p.getUUID());
        if (last != null && now - last < 600) return true;
        crackCooldown.put(p.getUUID(), now);
        if (!m.ownsRipplekit(p, clicked)) {
            m.plugin().text().actionBar(p, "fishing.clam.not-yours");
            return true;
        }
        m.crackClam(p, clicked.position().add(0, 0.6, 0));
        m.playAnimation(clicked, m.crackAnimation);
        Coast.swing(p);
        return true;
    }
}
