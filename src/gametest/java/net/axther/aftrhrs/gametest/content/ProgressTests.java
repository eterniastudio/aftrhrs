package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feather advancements: they fire from content actions (placing a gramophone completes "Handy Helper"), and the shipped
 * advancements.yml covers every tameable creature, plushie and furniture piece.
 */
public class ProgressTests {
    @GameTest(maxTicks = 100, padding = 12)
    public void placingFurnitureAdvances(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btAdv", true);
        var p = bot.p;
        BlockPos at = GadgetTests.floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        CT.Fails f = new CT.Fails("advancements");
        var adv = level.getServer().getAdvancements().get(Identifier.fromNamespaceAndPath("feather", "decor/utility"));
        f.check(adv != null, "feather:decor/utility is not loaded");
        GadgetTests.place(p, at, "arcana_gramophone");
        f.check("arcana_gramophone".equals(net.axther.aftrhrs.content.ArcanaContent.id(level.getBlockState(at))), "gramophone not placed");
        h.runAfterDelay(5, () -> {
            if (adv != null) f.check(p.getAdvancements().getOrStartProgress(adv).isDone(), "placing a gramophone did not complete Handy Helper: " + p.getAdvancements().getOrStartProgress(adv));
            CT.clear(level, at, 5);
            bot.leave();
            f.finish(h);
        });
    }

    /** The advancements as shipped (the jar's copy: the test world's data folder copy may be an older one). */
    private static net.axther.aftrhrs.core.Yaml shipped() {
        return net.axther.aftrhrs.core.Feather.bundled("advancements.yml").getConfigurationSection("advancements");
    }

    private static List<String> targets(net.axther.aftrhrs.core.Yaml adv, String key) {
        var s = adv.getConfigurationSection(key);
        return s == null ? List.of() : s.getStringList("targets");
    }

    /**
     * advancements.yml is sound: every entry but the tab roots has a parent (a lost parent shows as a stray tab that
     * can't be earned), every tame / kill entry names a target, and no creature is the target of two of the specific
     * companions entries (the bad merge that put Clover on Frog Guy's).
     */
    @GameTest
    public void advancementsAreWellFormed(GameTestHelper h) {
        CT.Fails f = new CT.Fails("advancements_yml");
        var adv = shipped();
        f.check(adv != null && !adv.getKeys(false).isEmpty(), "advancements.yml has no advancements");
        Map<String, String> claimedBy = new HashMap<>();
        if (adv != null) {
            for (String key : adv.getKeys(false)) {
                var s = adv.getConfigurationSection(key);
                if (s == null) continue;
                String trigger = s.getString("trigger", "event");
                f.check(key.endsWith("/root") || s.getString("parent") != null, key + " has no parent");
                if (trigger.startsWith("tame") || trigger.startsWith("kill")) {
                    f.check(!s.getStringList("targets").isEmpty(), key + " (" + trigger + ") has no targets");
                }
                // first_pet / first_mount / first_hostile take any creature of a kind: only the specific ones must not overlap
                if (key.startsWith("companions/") && !trigger.endsWith("-all") && !key.startsWith("companions/first_")
                        && trigger.equals("tame")) {
                    for (String t : s.getStringList("targets")) {
                        String other = claimedBy.putIfAbsent(t, key);
                        f.check(other == null, t + " is the target of both " + other + " and " + key);
                    }
                }
            }
        }
        f.finish(h);
    }

    /**
     * Every tameable creature counts for Gotta Tame 'Em All and its kind's first_* advancement (a grow-only adult such as
     * a penguin is reached through its chick), and no tame target names a creature that can't be tamed.
     */
    @GameTest
    public void everyTameableIsAnAdvancementTarget(GameTestHelper h) {
        CT.Fails f = new CT.Fails("advancements_tameables");
        var adv = shipped();
        Set<String> everything = new HashSet<>(targets(adv, "companions/everything"));
        Map<String, Species> byMythic = new HashMap<>();
        for (Species s : ArcanaEntities.all().values()) byMythic.put(s.mythicId, s);
        int n = 0;
        for (Species s : byMythic.values()) {
            if (!s.kind.tameableBase() || s.untameable() || s.section("tame") == null) continue;
            n++;
            String first = switch (s.kind) {
                case MOUNT -> "companions/first_mount";
                case HOSTILE -> "companions/first_hostile";
                default -> "companions/first_pet";
            };
            f.check(everything.contains(s.mythicId), s.mythicId + " is not in companions/everything");
            f.check(targets(adv, first).contains(s.mythicId), s.mythicId + " is not in " + first);
        }
        f.check(n > 40, "only " + n + " tameable species");
        for (String key : adv == null ? Set.<String>of() : adv.getKeys(false)) {
            var s = adv.getConfigurationSection(key);
            if (s == null || !s.getString("trigger", "").startsWith("tame")) continue;
            for (String t : s.getStringList("targets")) {
                Species sp = byMythic.get(t);
                f.check(sp != null && sp.section("tame") != null && !sp.untameable(), key + ": " + t + " has no way to be tamed");
            }
        }
        f.finish(h);
    }

    /** Every plushie counts for Plush Collector (and Cuddly). */
    @GameTest
    public void everyPlushieIsCollected(GameTestHelper h) {
        CT.Fails f = new CT.Fails("advancements_plush");
        var adv = shipped();
        Set<String> all = new HashSet<>(targets(adv, "companions/all_plush"));
        Set<String> any = new HashSet<>(targets(adv, "companions/plush"));
        int n = 0;
        for (String id : ArcanaContent.entries().keySet()) {
            if (!id.startsWith("arcana_plush_")) continue;
            n++;
            f.check(all.contains(id), id + " is not in companions/all_plush");
            f.check(any.contains(id), id + " is not in companions/plush");
        }
        f.check(n >= 50, "only " + n + " plushies");
        f.finish(h);
    }

    /** "Moving In" takes any Arcana furniture (kitchen, bathroom, tiki ...), not just the Paper collections: placing a kitchen piece earns it. */
    @GameTest(maxTicks = 100, padding = 12)
    public void anyFurnitureEarnsMovingIn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("advancements_moving_in");
        var module = net.axther.aftrhrs.core.Feather.get().module(net.axther.aftrhrs.feature.advancements.AdvancementsModule.class);
        Set<String> wanted = module == null ? Set.of() : module.targets("decor/first");
        // the test world keeps its data-folder advancements.yml between runs, and saveDefault only upgrades copies
        // whose hash is in feather/history.json: a copy from before the furniture: selector fails every check below
        String yml;
        try {
            yml = java.nio.file.Files.readString(net.axther.aftrhrs.core.Feather.get().getDataFolder().resolve("advancements.yml"));
        } catch (java.io.IOException e) {
            yml = "";
        }
        if (!yml.contains("furniture:")) {
            f.check(false, "stale advancements.yml in the test world (no furniture: selector): rm -rf mod/build/gametest");
            f.finish(h);
            return;
        }
        String kitchen = null;
        for (String tab : new String[]{"arcana_kitchen", "arcana_bathroom", "arcana_tropical"}) {
            String sample = null;
            for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
                if (c.item() == null || c.hidden() || !tab.equals(c.tab()) || !(c.block() instanceof FurnitureBlock fb)) continue;
                if (sample == null) sample = c.id();
                if (tab.equals("arcana_kitchen") && kitchen == null && fb.spec.floor && !fb.spec.multiPart()) kitchen = c.id();
                f.check(wanted.contains(c.id()), c.id() + " (" + tab + ") does not count for decor/first");
            }
            f.check(sample != null, "no furniture in " + tab);
        }
        f.check(!wanted.contains("feather_coal_generator"), "the coal generator counts as Arcana furniture");
        var adv = level.getServer().getAdvancements().get(Identifier.fromNamespaceAndPath("feather", "decor/first"));
        f.check(adv != null, "feather:decor/first is not loaded");
        if (adv == null || kitchen == null) {
            f.check(kitchen != null, "no single-block kitchen piece to place");
            f.finish(h);
            return;
        }
        CT.Bot bot = CT.join(h, "btMoveIn", true);
        var p = bot.p;
        // a fresh start: the test world keeps players' progress between runs
        for (String c : p.getAdvancements().getOrStartProgress(adv).getCompletedCriteria()) p.getAdvancements().revoke(adv, c);
        BlockPos at = GadgetTests.floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        String piece = kitchen;
        GadgetTests.place(p, at, piece);
        f.check(piece.equals(ArcanaContent.id(level.getBlockState(at))), piece + " not placed");
        h.runAfterDelay(5, () -> {
            f.check(p.getAdvancements().getOrStartProgress(adv).isDone(), "placing " + piece + " did not complete Moving In");
            CT.clear(level, at, 5);
            bot.leave();
            f.finish(h);
        });
    }
}
