package net.axther.aftrhrs.gametest.creatures;

import java.util.List;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMonster;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Wildlife harvests (milk, brush, shear, bucket) and breeding through real clicks; the police only fight the robber. */
public class WildlifeTests {
    private static ArcanaWildlife wild(GameTestHelper h, String id, Vec3 at) {
        ArcanaWildlife w = CT.spawn(h, ArcanaEntities.species(ArcanaEntities.type(id)), at);
        w.setKept(true);
        return w;
    }

    private static void click(ServerPlayer p, net.minecraft.world.entity.Entity e, ItemStack held) {
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        UseEntityCallback.EVENT.invoker().interact(p, p.level(), InteractionHand.MAIN_HAND, e, null);
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (s.is(item)) n += s.getCount();
        return n;
    }

    @GameTest(maxTicks = 100, padding = 16)
    public void harvests(GameTestHelper h) {
        CT.arena(h, 16, 16);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(8, 1, 1));
        // Tufthorn: milk once, then it is on cooldown (no endless milk)
        ArcanaWildlife tuft = wild(h, "tufthorn", new Vec3(2, 1, 4));
        click(p, tuft, new ItemStack(Items.BUCKET));
        h.assertTrue(p.getMainHandItem().is(Items.MILK_BUCKET) || count(p, Items.MILK_BUCKET) == 1, "no milk from a Tufthorn");
        click(p, tuft, new ItemStack(Items.BUCKET));
        h.assertTrue(p.getMainHandItem().is(Items.BUCKET), "milked twice in a row");
        // Wartgrazer: shear once (drop), the second shear finds it shorn
        ArcanaWildlife wart = wild(h, "wartgrazer", new Vec3(6, 1, 4));
        int before = p.getInventory().getNonEquipmentItems().stream().mapToInt(ItemStack::getCount).sum();
        click(p, wart, new ItemStack(Items.SHEARS));
        int after = p.getInventory().getNonEquipmentItems().stream().mapToInt(ItemStack::getCount).sum();
        h.assertTrue(after > before, "shearing a Wartgrazer gave nothing");
        click(p, wart, new ItemStack(Items.SHEARS));
        int again = p.getInventory().getNonEquipmentItems().stream().mapToInt(ItemStack::getCount).sum();
        h.assertValueEqual(again, after, "items after shearing a shorn Wartgrazer");
        // Lanternsnail: a water bucket scoops it (the creature is gone, the bucket item is given)
        ArcanaWildlife snail = wild(h, "lanternsnail", new Vec3(10, 1, 4));
        click(p, snail, new ItemStack(Items.WATER_BUCKET));
        h.assertTrue(snail.isRemoved(), "the Lanternsnail was not bucketed");
        h.assertFalse(p.getMainHandItem().is(Items.WATER_BUCKET), "the water bucket was not used");
        // Tufthorn breeding: two adults fed wheat make a young one
        ArcanaWildlife a = wild(h, "tufthorn", new Vec3(4, 1, 10));
        ArcanaWildlife b = wild(h, "tufthorn", new Vec3(5, 1, 10));
        click(p, a, new ItemStack(Items.WHEAT, 4));
        click(p, b, new ItemStack(Items.WHEAT, 4));
        h.runAfterDelay(80, () -> {
            var young = ArcanaEntities.type("tufthorn_young");
            List<Mob> kids = h.getLevel().getEntitiesOfClass(Mob.class, new AABB(a.blockPosition()).inflate(8), m -> m.getType() == young);
            h.assertTrue(!kids.isEmpty(), "no young Tufthorn after feeding two adults wheat");
            for (Mob k : kids) k.discard();
            for (var e : List.of(tuft, wart, a, b)) if (!e.isRemoved()) e.discard();
            CT.disconnect(p);
            h.succeed();
        });
    }

    /** The police fight only the robber they were set on, and never pick targets themselves. */
    @GameTest(maxTicks = 200, padding = 16)
    public void policeChaseOnlyTheRobber(GameTestHelper h) {
        CT.arena(h, 16, 16);
        ServerLevel level = h.getLevel();
        ServerPlayer robber = CT.player(h, GameType.SURVIVAL, new Vec3(8, 1, 8));
        ServerPlayer bystander = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 4));
        robber.setPermanentlyInvulnerable(true);
        bystander.setPermanentlyInvulnerable(true);
        ArcanaMonster cop = CT.spawn(h, ArcanaEntities.species(ArcanaEntities.type("police")), new Vec3(5, 1, 5));
        h.runAfterDelay(40, () -> h.assertTrue(cop.getTarget() == null, "the police picked a target on their own: " + cop.getTarget()));
        h.runAfterDelay(41, () -> cop.setTarget(robber));
        h.runAfterDelay(160, () -> {
            h.assertTrue(cop.getTarget() == robber || cop.getTarget() == null, "the police switched to " + cop.getTarget());
            h.assertTrue(cop.removeWhenFarAway(10000), "police never despawn");
            cop.discard();
            CT.disconnect(robber);
            CT.disconnect(bystander);
            h.succeed();
        });
    }

    /**
     * At night, aggressive creatures with vanilla-biome rules can spawn in their biome (the night rule is not a blanket
     * no): the spawn predicate allows them and the additive spawner runs their rule there. The predicate has no rarity
     * roll any more (that is ArcanaSpawner's chance), so one check per species decides.
     */
    @GameTest(maxTicks = 40, skyAccess = true)
    public void aggressiveSpawnAtNight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        StringBuilder none = new StringBuilder();
        int checked = 0;
        try {
            h.setTime(day0 + 18000);
            for (var s : CT.species(x -> ArcanaEntities.aggressive(x) && x.kind != net.axther.aftrhrs.entity.Species.Kind.BOSS && !x.spawns.isEmpty()
                    && x.section("replace") == null && x.aquatic() == null)) {
                var rule = s.spawns.stream().filter(r -> r.biomes().stream().anyMatch(b -> b.startsWith("minecraft:")) && r.dimension().equals("minecraft:overworld")
                        && r.conditions().stream().noneMatch(c -> c.contains("worldtime"))).findFirst().orElse(null);
                if (rule == null) continue;
                String biome = rule.biomes().stream().filter(b -> b.startsWith("minecraft:")).findFirst().orElseThrow();
                h.setBiome(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, net.minecraft.resources.Identifier.parse(biome)));
                @SuppressWarnings({"unchecked", "rawtypes"})
                boolean ok = SpawnPlacements.checkSpawnRules((net.minecraft.world.entity.EntityType) CT.type(s), level, EntitySpawnReason.NATURAL, pos, level.getRandom());
                ok &= ArcanaEntities.type(s.id) != null && net.axther.aftrhrs.entity.ArcanaSpawner.rulesAt(rule.dimension(), biome).stream().anyMatch(r -> r.species == s);
                checked++;
                if (!ok) none.append(s.id).append(" (").append(biome).append(", ").append(rule.conditions()).append(") ");
            }
        } finally {
            h.setTime(before);
        }
        h.assertTrue(none.isEmpty(), "never allowed at night in their own biome: " + none);
        h.assertTrue(checked > 0, "no aggressive species checked");
        h.succeed();
    }
}
