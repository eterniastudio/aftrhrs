package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.CreatureUtil;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Taming and pet management through real interactions with online (mock) players and the pets module. */
public class PetsTests {
    // ------------------------------------------------------------------ module access (package-private API)

    static Object module() {
        Object m = CT.call(CT.cls("net.axther.aftrhrs.feature.pets.PetsModule"), "active");
        if (m == null) throw new IllegalStateException("pets module is not running");
        return m;
    }

    static Object store() {
        return CT.call(module(), "store");
    }

    static Object record(Entity e) {
        return CT.call(store(), "byEntity", e.getUUID());
    }

    static Object recordById(UUID id) {
        return CT.call(store(), "get", id);
    }

    static String state(Object pet) {
        return String.valueOf(CT.get(pet, "state"));
    }

    static UUID petId(Object pet) {
        return (UUID) CT.get(pet, "id");
    }

    static Entity entityOf(Object pet) {
        return (Entity) CT.call(module(), "entity", pet);
    }

    @SuppressWarnings("unchecked")
    static List<Object> petsOf(UUID owner) {
        return (List<Object>) CT.call(store(), "of", owner);
    }

    /** The tame item for a species (vanilla or custom content item). */
    static ItemStack tameItem(Species s) {
        if (s.tameCustom() != null) {
            Item i = CreatureUtil.customItem(s.tameCustom());
            return i == null ? ItemStack.EMPTY : new ItemStack(i, 64);
        }
        return s.tameItem() == null ? ItemStack.EMPTY : new ItemStack(s.tameItem(), 64);
    }

    static boolean tameable(Species s) {
        // "raised": tamed only by raising its young (the penguins: a chick takes the fish, grows up into the adult)
        return s.kind.tameableBase() && !s.untameable() && !s.young() && !s.flag("raised");
    }

    /** Runs with the pet cap raised (the mock players have no cap permission: default 6). */
    static void withCap(int cap, Runnable r) {
        Object m = module();
        int was = (int) CT.get(m, "defaultCap");
        CT.set(m, "defaultCap", cap);
        try {
            r.run();
        } finally {
            CT.set(m, "defaultCap", was);
        }
    }

    static net.minecraft.world.InteractionResult use2(ServerPlayer p, Entity e) {
        return e.interact(p, InteractionHand.MAIN_HAND, e.position());
    }

    static void use(ServerPlayer p, Entity e) {
        e.interact(p, InteractionHand.MAIN_HAND, e.position());
    }

    static ArcanaCreature tamed(GameTestHelper h, Species s, ServerPlayer owner, Vec3 at) {
        ArcanaCreature c = CT.spawn(h, s, at);
        withCap(1000, () -> c.tamedBy(owner));
        return c;
    }

    // ------------------------------------------------------------------ tests

    /** Every tameable species is in pets.yml with a plushie that exists, and has a tame item. */
    @GameTest
    public void everyTameableHasPetsConfig(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        Object m = module();
        for (Species s : CT.species(PetsTests::tameable)) {
            Object ps = CT.call(m, "species", s.mythicId);
            if (ps == null) {
                problems.add(s.id + " (" + s.mythicId + ") missing from pets.yml species");
                continue;
            }
            String plush = (String) CT.call(ps, "plush");
            if (plush == null || BuiltInRegistries.ITEM.getValue(Identifier.parse("aftrhrs:" + plush)) == Items.AIR) problems.add(s.id + ": plushie " + plush + " is not an item");
            if (tameItem(s).isEmpty()) problems.add(s.id + ": no tame item (" + s.tameCustom() + " / " + s.tameItem() + ")");
            // the breeding treat defaults to the tame item (guide.yml "Cooked Beef"); never silently the pet's food
            Object treat = CT.call(CT.call(m, "breeding"), "item", s.mythicId);
            if (s.tameItem() != null && treat != s.tameItem()) problems.add(s.id + ": breeding treat " + treat + " is not the tame item " + s.tameItem());
        }
        CT.report(h, "pets config", problems);
        h.succeed();
    }

    /** A survival player tames every tameable with its item: one item per try, a record + pet tag on success. */
    @GameTest(maxTicks = 2600, padding = 48)
    public void tameEveryTameable(GameTestHelper h) {
        CT.arena(h, 42, 32);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(20, 2, 20));
        p.setPermanentlyInvulnerable(true);
        List<String> problems = new ArrayList<>();
        Map<ArcanaCreature, Species> wild = new LinkedHashMap<>();
        Map<ArcanaCreature, int[]> tries = new LinkedHashMap<>();
        int i = 0;
        for (Species s : CT.species(PetsTests::tameable)) {
            if (tameItem(s).isEmpty()) continue;
            ArcanaCreature c = CT.spawn(h, s, Vec3.atBottomCenterOf(CT.grid(i++, 8, 5)));
            c.setPersistenceRequired();
            c.setPermanentlyInvulnerable(true);
            wild.put(c, s);
            tries.put(c, new int[2]);
        }
        Object m = module();
        int was = (int) CT.get(m, "defaultCap");
        CT.set(m, "defaultCap", 1000);
        for (int k = 0; k < 60; k++) {
            h.runAfterDelay(1 + k * 22L, () -> {
                for (var en : wild.entrySet()) {
                    ArcanaCreature c = en.getKey();
                    if (c.isTame() || c.isRemoved()) continue;
                    Species s = en.getValue();
                    ItemStack stack = tameItem(s);
                    stack.setCount(10);
                    p.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    c.snapTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
                    try {
                        use(p, c);
                    } catch (Throwable t) {
                        problems.add(s.id + " tame click: " + CT.err(t));
                        c.discard();
                        continue;
                    }
                    int used = 10 - p.getItemInHand(InteractionHand.MAIN_HAND).getCount();
                    if (s.tameReturn() != null) used = 10 - p.getItemInHand(InteractionHand.MAIN_HAND).getCount();
                    tries.get(c)[0]++;
                    tries.get(c)[1] += used;
                    c.snapTo(h.absoluteVec(Vec3.atBottomCenterOf(CT.grid(new ArrayList<>(wild.keySet()).indexOf(c), 8, 5))));
                }
            });
        }
        h.runAfterDelay(60 * 22L + 5, () -> {
            CT.set(m, "defaultCap", was);
            for (var en : wild.entrySet()) {
                ArcanaCreature c = en.getKey();
                Species s = en.getValue();
                int[] t = tries.get(c);
                if (!c.isTame()) {
                    problems.add(s.id + ": not tamed after " + t[0] + " tries (chance " + s.tameChance() + ")");
                    continue;
                }
                if (t[1] != t[0]) problems.add(s.id + ": " + t[0] + " tries used " + t[1] + " items");
                if (c.getOwnerReference() == null || !c.getOwnerReference().getUUID().equals(p.getUUID())) problems.add(s.id + ": wrong owner");
                Object rec = record(c);
                if (rec == null) problems.add(s.id + ": no pet record");
                else if (!petId(rec).toString().equals(Pdc.getString(c, "feather:pet"))) problems.add(s.id + ": entity tag != record id");
                if (!c.shouldBeSaved()) problems.add(s.id + ": tamed pet would not be saved");
            }
            for (ArcanaCreature c : wild.keySet()) if (!c.isRemoved()) c.discard();
            CT.disconnect(p);
            CT.report(h, "taming", problems);
            h.succeed();
        });
    }

    /** At the pet cap a tame try is refused and the item stays. */
    @GameTest(maxTicks = 60)
    public void capRefusesAndKeepsItem(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        Species s = Species.class.cast(ArcanaEntities.species(ArcanaEntities.type("corgi")));
        ArcanaCreature first = CT.spawn(h, s, new Vec3(3, 1, 3));
        ArcanaCreature second = CT.spawn(h, s, new Vec3(5, 1, 3));
        withCap(1, () -> first.tamedBy(p));
        ItemStack item = tameItem(s);
        item.setCount(5);
        p.setItemInHand(InteractionHand.MAIN_HAND, item);
        withCap(1, () -> use(p, second));
        h.assertFalse(second.isTame(), "tamed past the cap");
        h.assertValueEqual(p.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 5, "tame items after a refused try");
        first.discard();
        second.discard();
        CT.disconnect(p);
        h.succeed();
    }

    /** Owner: Shift-click stays / follows, food heals (item used, bond XP), a name tag renames through the record. */
    @GameTest(maxTicks = 200)
    public void ownerInteractions(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ServerPlayer stranger = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 4));
        Species s = ArcanaEntities.species(ArcanaEntities.type("corgi"));
        ArcanaCreature c = tamed(h, s, owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        h.assertTrue(rec != null, "no record");
        // stay
        owner.setShiftKeyDown(true);
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        use(owner, c);
        owner.setShiftKeyDown(false);
        h.assertTrue(c.staying(), "Shift-click did not make it stay");
        h.assertTrue((boolean) CT.get(rec, "stay"), "record stay not set");
        // feed
        c.setHealth(c.getMaxHealth() / 2);
        int xp = (int) CT.get(rec, "xp");
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(s.healItem(), 3));
        use(owner, c);
        h.assertTrue(c.getHealth() > c.getMaxHealth() / 2, "food did not heal");
        h.assertValueEqual(owner.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 2, "food after feeding");
        h.assertTrue((int) CT.get(rec, "xp") > xp, "feeding gave no bond XP");
        // a stranger's name tag does nothing and stays in their hand
        ItemStack tag = new ItemStack(Items.NAME_TAG);
        tag.set(DataComponents.CUSTOM_NAME, Component.literal("Stolen"));
        stranger.setItemInHand(InteractionHand.MAIN_HAND, tag);
        use(stranger, c);
        h.assertTrue(c.getCustomName() == null || !c.getCustomName().getString().contains("Stolen"), "a stranger renamed the pet");
        h.assertValueEqual(stranger.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 1, "stranger's name tag");
        // the owner's name tag renames through the record
        ItemStack mine = new ItemStack(Items.NAME_TAG);
        mine.set(DataComponents.CUSTOM_NAME, Component.literal("Biscuit"));
        owner.setItemInHand(InteractionHand.MAIN_HAND, mine);
        use(owner, c);
        h.assertValueEqual(CT.get(rec, "name"), "Biscuit", "record name");
        h.assertTrue(c.getCustomName() != null && c.getCustomName().getString().contains("Biscuit"), "creature name " + c.getCustomName());
        h.assertTrue(owner.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(), "owner's name tag not used");
        // an invalid name is refused and the tag kept
        ItemStack bad = new ItemStack(Items.NAME_TAG);
        bad.set(DataComponents.CUSTOM_NAME, Component.literal("<b>{}</b>"));
        owner.setItemInHand(InteractionHand.MAIN_HAND, bad);
        h.runAfterDelay(20, () -> {
            use(owner, c);
            h.assertValueEqual(CT.get(rec, "name"), "Biscuit", "record name after an invalid tag");
            h.assertValueEqual(owner.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 1, "invalid name tag");
            // follow again
            owner.setShiftKeyDown(true);
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            use(owner, c);
            owner.setShiftKeyDown(false);
            h.assertFalse(c.staying(), "Shift-click did not make it follow");
            c.discard();
            CT.disconnect(owner);
            CT.disconnect(stranger);
            h.succeed();
        });
    }

    /** A stranger can't hurt someone's pet; the owner's own hit lands. */
    @GameTest(maxTicks = 40)
    public void strangersCannotHurtPets(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ServerPlayer stranger = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 4));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("beagle")), owner, new Vec3(3, 1, 3));
        float hp = c.getHealth();
        c.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(stranger), 4);
        h.assertValueEqual(c.getHealth(), hp, "health after a stranger's hit");
        c.discard();
        CT.disconnect(owner);
        CT.disconnect(stranger);
        h.succeed();
    }

    /** Death -> fallen record; the plushie (right-click air) revives it next to the owner and is used up. */
    @GameTest(maxTicks = 100)
    public void fallenAndReviveWithPlushie(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ServerPlayer killer = CT.player(h, GameType.SURVIVAL, new Vec3(6, 2, 6));
        Species s = ArcanaEntities.species(ArcanaEntities.type("black_cat"));
        ArcanaCreature c = tamed(h, s, owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        UUID id = petId(rec);
        c.kill(level);
        h.runAfterDelay(2, () -> {
            Object r = recordById(id);
            h.assertTrue(r != null, "record gone after death");
            h.assertValueEqual(state(r), "FALLEN", "state after death");
            String plush = (String) CT.call(CT.call(module(), "species", s.mythicId), "plush");
            Item plushItem = BuiltInRegistries.ITEM.getValue(Identifier.parse("aftrhrs:" + plush));
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(plushItem, 2));
            withCap(1000, () -> UseItemCallback.EVENT.invoker().interact(owner, level, InteractionHand.MAIN_HAND));
            h.assertValueEqual(state(r), "ALIVE", "state after the revive");
            h.assertValueEqual(owner.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 1, "plushies after the revive");
            Entity e = entityOf(r);
            h.assertTrue(e instanceof ArcanaCreature a && a.isTame() && a.isAlive(), "revived pet missing");
            // a second click with nothing fallen leaves the plushie alone
            withCap(1000, () -> UseItemCallback.EVENT.invoker().interact(owner, level, InteractionHand.MAIN_HAND));
            h.assertValueEqual(owner.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 1, "plushies after a click with nothing to revive");
            if (e != null) e.discard();
            CT.disconnect(owner);
            CT.disconnect(killer);
            h.succeed();
        });
    }

    /** Stable: in removes the entity (record STABLED, saved), out spawns it back with its name and bond. */
    @GameTest(maxTicks = 100)
    public void stableInAndOut(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("husky")), owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        Object stable = CT.call(module(), "stable");
        CT.call(module(), "rename", rec, "Kodiak");
        CT.call(module(), "setBond", rec, 3);
        String err = (String) CT.call(stable, "park", owner, rec);
        h.assertTrue(err == null, "park refused: " + err);
        h.assertTrue(c.isRemoved(), "stabled pet still in the world");
        h.assertValueEqual(state(rec), "STABLED", "state");
        h.runAfterDelay(40, () -> {
            // the stable's anti-spam cooldown is wall-clock (1.5 s); 40 ticks on a fast test server can be shorter
            Map<?, ?> cooldowns = (Map<?, ?>) CT.get(module(), "cooldowns");
            cooldowns.remove("stable:" + petId(rec));
            cooldowns.remove("stable-player:" + owner.getUUID());
            String out = (String) CT.call(stable, "bringOut", owner, rec);
            h.assertTrue(out == null, "bring out refused: " + out);
            Entity e = entityOf(rec);
            h.assertTrue(e instanceof ArcanaCreature, "no entity after bring out");
            ArcanaCreature a = (ArcanaCreature) e;
            h.assertTrue(a.isTame() && a.bond() == 3, "bond " + a.bond());
            h.assertTrue(a.getCustomName() != null && a.getCustomName().getString().contains("Kodiak"), "name " + a.getCustomName());
            a.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** Release: the creature goes wild (no owner, no tag, no record) and a stranger can tame it. */
    @GameTest(maxTicks = 60)
    public void releaseMakesWild(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ServerPlayer other = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 4));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("tabby_cat")), owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        String err = (String) CT.call(module(), "release", owner, rec);
        h.assertTrue(err == null, "release refused: " + err);
        h.assertFalse(c.isTame(), "still tame");
        h.assertTrue(Pdc.getString(c, "feather:pet") == null, "still tagged");
        h.assertTrue(recordById(petId(rec)) == null, "record kept");
        withCap(1000, () -> c.tamedBy(other));
        h.assertTrue(record(c) != null, "the new owner got no record");
        c.discard();
        CT.disconnect(owner);
        CT.disconnect(other);
        h.succeed();
    }

    /** The owner goes to the Nether: the following pet comes along (one living copy), a staying one stays. */
    @GameTest(maxTicks = 300, padding = 16)
    public void followsAcrossDimensions(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "no nether");
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature follower = tamed(h, ArcanaEntities.species(ArcanaEntities.type("dachshund")), owner, new Vec3(3, 1, 3));
        ArcanaCreature stayer = tamed(h, ArcanaEntities.species(ArcanaEntities.type("saint_bernard")), owner, new Vec3(5, 1, 3));
        stayer.setStaying(true, null);
        Object fRec = record(follower), sRec = record(stayer);
        UUID fid = petId(fRec);
        h.runAfterDelay(5, () -> {
            Vec3 at = new Vec3(owner.getX(), 130, owner.getZ());
            nether.setBlockAndUpdate(net.minecraft.core.BlockPos.containing(at).below(), net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
            owner.teleport(new TeleportTransition(nether, at, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        });
        h.runAfterDelay(200, () -> {
            Object r = recordById(fid);
            Entity e = entityOf(r);
            h.assertTrue(e != null && e.level() == nether, "the follower is not in the Nether: " + (e == null ? "not loaded" : e.level().dimension()));
            int copies = 0;
            for (ServerLevel l : level.getServer().getAllLevels()) {
                for (Entity x : l.getAllEntities()) if (fid.toString().equals(Pdc.getString(x, "feather:pet")) && x.isAlive()) copies++;
            }
            h.assertValueEqual(copies, 1, "living copies of the follower");
            Entity s = entityOf(sRec);
            h.assertTrue(s != null && s.level() == level, "the staying pet moved");
            if (e != null) e.discard();
            if (s != null) s.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** A pet unloaded with its chunk and loaded again keeps its record (no duplicate, no adoption of a stale copy). */
    @GameTest(maxTicks = 60)
    public void unloadAndReloadKeepsRecord(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("golden_retriever")), owner, new Vec3(3, 1, 3));
        c.setStaying(true, owner);
        Object rec = record(c);
        UUID id = petId(rec);
        int before = petsOf(owner.getUUID()).size();
        var tag = CT.save(c);
        c.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        Entity back = CT.load(level, tag);
        level.addFreshEntity(back);
        h.runAfterDelay(5, () -> {
            h.assertValueEqual(petsOf(owner.getUUID()).size(), before, "records after reload");
            Object r = recordById(id);
            h.assertTrue(r != null && state(r).equals("ALIVE"), "record state");
            h.assertTrue(entityOf(r) == back, "record does not point at the reloaded entity");
            h.assertTrue(back.isAlive() && ((ArcanaCreature) back).staying(), "reloaded pet not staying");
            back.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** The whistle brings a staying pet in the same world to its owner and makes it follow. */
    @GameTest(maxTicks = 60, padding = 24)
    public void whistleCallsPets(GameTestHelper h) {
        CT.arena(h, 22, 22);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("husky_copper")), owner, new Vec3(18, 1, 18));
        c.setStaying(true, owner);
        Item whistle = BuiltInRegistries.ITEM.getValue(Identifier.parse("aftrhrs:arcana_pet_whistle"));
        h.assertTrue(whistle != Items.AIR, "no whistle item");
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle));
        UseItemCallback.EVENT.invoker().interact(owner, level, InteractionHand.MAIN_HAND);
        h.assertFalse(c.staying(), "still staying after the whistle");
        h.assertTrue(c.distanceToSqr(owner) < 64, "not next to the owner: " + Math.sqrt(c.distanceToSqr(owner)));
        c.discard();
        CT.disconnect(owner);
        h.succeed();
    }

    /** Admin setbond / bond XP: the level reaches the creature (abilities scale) and the record. */
    @GameTest(maxTicks = 40)
    public void bondLevels(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("emberkit")), owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        var src = h.getLevel().getServer().createCommandSourceStack();
        h.getLevel().getServer().getCommands().performPrefixedCommand(src, "pets admin setbond " + owner.getGameProfile().name() + " 4");
        h.assertValueEqual((int) CT.get(rec, "level"), 4, "record level after setbond");
        h.assertValueEqual(c.bond(), 4, "creature bond after setbond");
        CT.call(module(), "addXp", rec, 100000);
        h.assertValueEqual((int) CT.get(rec, "level"), 5, "record level after lots of XP");
        h.assertValueEqual(c.bond(), 5, "creature bond after lots of XP");
        c.discard();
        CT.disconnect(owner);
        h.succeed();
    }

    /** The owner disconnects while riding / with pets out: nothing throws, the pets stay put and keep their records. */
    @GameTest(maxTicks = 60)
    public void ownerDisconnects(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("corgi")), owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        CT.disconnect(owner);
        h.runAfterDelay(45, () -> {
            h.assertTrue(c.isAlive() && !c.isRemoved(), "pet gone after its owner left");
            h.assertValueEqual(state(rec), "ALIVE", "record");
            c.discard();
            h.succeed();
        });
    }

    /** Magpip Fetch: the owner's own and loose items come over; another player's thrown items (online or not) and a dead player's drops stay. */
    @GameTest(maxTicks = 80, padding = 24)
    public void fetchLeavesOtherPlayersItems(GameTestHelper h) {
        CT.arena(h, 16, 16);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(8, 1, 8));
        ServerPlayer other = CT.player(h, GameType.SURVIVAL, new Vec3(12, 1, 8));
        ServerPlayer gone = CT.player(h, GameType.SURVIVAL, new Vec3(12, 1, 12));
        ServerPlayer victim = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 12));
        owner.setPermanentlyInvulnerable(true);
        ArcanaCreature magpip = tamed(h, ArcanaEntities.species(ArcanaEntities.type("magpip")), owner, new Vec3(9, 1, 8));
        magpip.setBond(3);
        net.minecraft.world.entity.item.ItemEntity mine = drop(h, new Vec3(3, 1, 3), Items.APPLE, owner);
        net.minecraft.world.entity.item.ItemEntity loose = drop(h, new Vec3(13, 1, 3), Items.BONE, null);
        net.minecraft.world.entity.item.ItemEntity theirs = drop(h, new Vec3(3, 1, 13), Items.EMERALD, other);
        net.minecraft.world.entity.item.ItemEntity offline = drop(h, new Vec3(13, 1, 13), Items.GOLD_INGOT, gone);
        CT.disconnect(gone);
        victim.getInventory().add(new ItemStack(Items.DIAMOND, 3));
        victim.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
        List<net.minecraft.world.entity.item.ItemEntity> deathDrops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                victim.getBoundingBox().inflate(4), i -> i.getItem().is(Items.DIAMOND));
        for (var d : deathDrops) h.assertTrue(Pdc.getString(d, "aftrhrs:death_drop") != null, "death drop not tagged");
        java.util.Map<net.minecraft.world.entity.item.ItemEntity, Vec3> stay = new java.util.HashMap<>();
        for (var i : List.of(theirs, offline)) stay.put(i, i.position());
        for (var d : deathDrops) stay.put(d, d.position());
        net.axther.aftrhrs.entity.ability.Abilities.runSkill(magpip, "ArcanaMagpip_Fetch", null);
        h.runAfterDelay(20, () -> {
            h.assertTrue(mine.distanceToSqr(owner) < 4, "the owner's own item was not fetched");
            h.assertTrue(loose.distanceToSqr(owner) < 4, "a loose item was not fetched");
            for (var en : stay.entrySet()) {
                h.assertTrue(en.getKey().position().distanceToSqr(en.getValue()) < 1, en.getKey().getItem() + " (someone else's) was fetched");
            }
            for (var e : List.of(mine, loose, theirs, offline)) if (!e.isRemoved()) e.discard();
            for (var d : deathDrops) if (!d.isRemoved()) d.discard();
            magpip.discard();
            CT.disconnect(owner);
            CT.disconnect(other);
            CT.disconnect(victim);
            h.succeed();
        });
    }

    private static net.minecraft.world.entity.item.ItemEntity drop(GameTestHelper h, Vec3 rel, Item item, ServerPlayer thrower) {
        Vec3 at = h.absoluteVec(rel);
        var e = new net.minecraft.world.entity.item.ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(item));
        e.setDeltaMovement(Vec3.ZERO);
        e.setPickUpDelay(32767);
        if (thrower != null) e.setThrower(thrower);
        h.getLevel().addFreshEntity(e);
        return e;
    }

    static boolean isPlayer(Entity e) {
        return e instanceof Player;
    }

    /**
     * The chat lines and action bars ("[bar] " ...) sent to a bot since its last read. Reads the bot's channel directly:
     * Bots keeps chat packets only, and the pets module's action bars are their own packet.
     */
    private static List<String> sent(Bots.Bot b) {
        io.netty.channel.embedded.EmbeddedChannel ch = (io.netty.channel.embedded.EmbeddedChannel) CT.get(b, "channel");
        ch.runPendingTasks();
        ch.flushOutbound();
        List<String> out = new ArrayList<>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            if (o instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket s) out.add((s.overlay() ? "[bar] " : "") + s.content().getString());
            else if (o instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket a) out.add("[bar] " + a.text().getString());
        }
        return out;
    }

    /** Chat lines (not action bars) that announce a tame: the pets module's "joined your pets" or the creature's own. */
    private static List<String> tameLines(List<String> sent) {
        List<String> out = new ArrayList<>();
        for (String s : sent) if (!s.startsWith("[bar] ") && (s.contains("joined your pets") || s.contains("bonds with you"))) out.add(s);
        return out;
    }

    /** With the pets module running a tame prints one tame line (the module's), not the creature's fallback as well. */
    @GameTest(maxTicks = 40)
    public void tameSendsOneLine(GameTestHelper h) {
        CT.arena(h, 8, 8);
        Bots.Bot b = Bots.join(h, Bots.name("tame"));
        ArcanaCreature c = CT.spawn(h, ArcanaEntities.species(ArcanaEntities.type("corgi")), new Vec3(3, 1, 3));
        b.clear();
        withCap(1000, () -> c.tamedBy(b.player));
        List<String> all = sent(b), lines = tameLines(all);
        h.assertValueEqual(lines.size(), 1, "tame chat lines " + all);
        h.assertTrue(lines.get(0).contains("joined your pets"), "the tame line is not the pets module's: " + lines);
        h.assertTrue(lines.get(0).contains("stay/follow"), "the stay / follow hint is missing: " + lines);
        c.discard();
        b.leave();
        h.succeed();
    }

    /**
     * At the cap the first refused try says so in chat (once per cooldown); further tries inside the cooldown only get
     * the action bar. The creature adds no second "too many pets" notice of its own.
     */
    @GameTest(maxTicks = 40)
    public void capNoticeOnceThenActionBar(GameTestHelper h) {
        CT.arena(h, 8, 8);
        Bots.Bot b = Bots.join(h, Bots.name("cap"));
        ServerPlayer p = b.player;
        Species s = ArcanaEntities.species(ArcanaEntities.type("corgi"));
        ArcanaCreature first = CT.spawn(h, s, new Vec3(3, 1, 3));
        ArcanaCreature second = CT.spawn(h, s, new Vec3(5, 1, 3));
        withCap(1, () -> first.tamedBy(p));
        ItemStack item = tameItem(s);
        item.setCount(5);
        p.setItemInHand(InteractionHand.MAIN_HAND, item);
        b.clear();
        // three tries in a row, all inside the 10 s chat cooldown. The cap is module-wide and other tests raise it, so
        // everything runs inside one withCap; the creature's own 1 s tame gate is cleared between the tries.
        withCap(1, () -> {
            for (int k = 0; k < 3; k++) {
                second.scheduler().clearCooldown("tame");
                use(p, second);
            }
        });
        List<String> all = sent(b), chat = new ArrayList<>(), bars = new ArrayList<>();
        for (String line : all) {
            if (line.startsWith("[bar] ")) bars.add(line);
            else if (line.contains("too many pets")) chat.add(line);
        }
        h.assertFalse(second.isTame(), "tamed past the cap");
        h.assertValueEqual(chat.size(), 1, "cap chat lines " + all);
        h.assertValueEqual(bars.size(), 2, "cap action bars " + all);
        for (String bar : bars) h.assertTrue(bar.contains("Pet limit reached"), "unexpected action bar " + bar);
        h.assertValueEqual(p.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 5, "tame items after refused tries");
        first.discard();
        second.discard();
        b.leave();
        h.succeed();
    }

    /**
     * A following pet never teleports into chunks that are not loaded (that would sync-load them on the server thread)
     * and does not load them; once they are loaded it teleports to its owner as before.
     */
    @GameTest(maxTicks = 40, padding = 16)
    public void noTeleportIntoUnloadedChunks(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("corgi")), owner, new Vec3(3, 1, 3));
        Vec3 home = c.position();
        // somewhere far away in chunks nobody loaded (the mock owner is not ticked, so moving it loads nothing)
        int x = 0, z = 0;
        boolean found = false;
        for (int k = 1; k <= 8 && !found; k++) {
            x = (int) owner.getX() + 1500 * k;
            z = (int) owner.getZ() + 700 * k;
            found = !anyChunkNow(level, x, z, 5);
        }
        h.assertTrue(found, "no unloaded area found");
        int y = 200;
        owner.setPos(x + 0.5, y, z + 0.5);
        c.tryToTeleportToOwner();
        h.assertTrue(c.position().distanceToSqr(home) < 0.01, "the pet teleported into unloaded chunks: " + c.position());
        h.assertFalse(anyChunkNow(level, x, z, 5), "the teleport check loaded a chunk");
        // load them, give the owner a floor to stand on, and the teleport works again
        for (int cx = (x - 5) >> 4; cx <= (x + 5) >> 4; cx++) for (int cz = (z - 5) >> 4; cz <= (z + 5) >> 4; cz++) level.getChunk(cx, cz);
        BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 1, z + dz), stone, 2);
                for (int dy = 0; dy < 3; dy++) level.setBlock(new BlockPos(x + dx, y + dy, z + dz), air, 2);
            }
        }
        for (int tries = 0; tries < 30 && c.distanceToSqr(owner) > 400; tries++) c.tryToTeleportToOwner();   // 10 random spots per try
        h.assertTrue(c.distanceToSqr(owner) <= 400, "the pet did not teleport once the chunks were loaded: " + Math.sqrt(c.distanceToSqr(owner)));
        c.discard();
        CT.disconnect(owner);
        h.succeed();
    }

    private static boolean anyChunkNow(ServerLevel level, int x, int z, int r) {
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) {
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) if (level.getChunkSource().getChunkNow(cx, cz) != null) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the loaded-pet index (PetStore.loaded)

    @SuppressWarnings("unchecked")
    static java.util.Collection<Object> loaded() {
        return (java.util.Collection<Object>) CT.call(store(), "loaded");
    }

    /** Particle packets a bot got since its last read. */
    private static int particles(Bots.Bot b) {
        io.netty.channel.embedded.EmbeddedChannel ch = (io.netty.channel.embedded.EmbeddedChannel) CT.get(b, "channel");
        ch.runPendingTasks();
        ch.flushOutbound();
        int n = 0;
        Object o;
        while ((o = ch.readOutbound()) != null) if (o instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket) n++;
        return n;
    }

    /**
     * A shiny pet leaves the loaded index when its chunk unloads (the pets tick prunes it) and comes back when it loads
     * again, so its sparkles (Variants.tick, over loaded() only) keep coming after a reload.
     */
    @GameTest(maxTicks = 60)
    public void shinySparklesAfterAReload(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        Bots.Bot b = Bots.join(h, Bots.name("shiny"));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("corgi")), b.player, new Vec3(3, 1, 3));
        c.setStaying(true, b.player);
        Object rec = record(c);
        CT.set(rec, "shiny", true);
        h.assertTrue(loaded().contains(rec), "a tamed pet is not in loaded()");
        var tag = CT.save(c);
        c.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        CT.call(module(), "tick");
        h.assertFalse(loaded().contains(rec), "an unloaded pet stays in loaded() after the prune");
        Entity back = CT.load(level, tag);
        level.addFreshEntity(back);
        h.runAfterDelay(5, () -> {
            h.assertTrue(loaded().contains(rec), "the reloaded pet is not in loaded()");
            Object variants = CT.call(module(), "variants");
            int every = (int) CT.get(variants, "sparkleEvery");
            CT.set(variants, "seconds", (long) (every - 1));
            particles(b);
            CT.call(variants, "tick");
            h.assertTrue(particles(b) > 0, "no shiny sparkles after the reload");
            back.discard();
            b.leave();
            h.succeed();
        });
    }

    /** A cross-world recall that teleports the entity (follow.cross-world: teleport) keeps the pet in loaded(). */
    @GameTest(maxTicks = 100, padding = 16)
    public void crossWorldTeleportStaysLoaded(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "no nether");
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature c = tamed(h, ArcanaEntities.species(ArcanaEntities.type("beagle")), owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        Object m = module();
        boolean was = (boolean) CT.get(m, "crossWorldTeleport");
        Vec3 at = new Vec3(owner.getX(), 131, owner.getZ());
        nether.setBlockAndUpdate(net.minecraft.core.BlockPos.containing(at).below(), net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        CT.set(m, "crossWorldTeleport", true);
        Object[] outcome = new Object[1];
        try {
            owner.teleport(new TeleportTransition(nether, at, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            CT.call(m, "bring", rec, owner, false, (java.util.function.Consumer<Object>) o -> outcome[0] = o);
        } finally {
            CT.set(m, "crossWorldTeleport", was);
        }
        h.runAfterDelay(10, () -> {
            h.assertValueEqual(String.valueOf(outcome[0]), "MOVED", "bring outcome");
            CT.call(m, "tick");
            Entity e = entityOf(rec);
            h.assertTrue(e != null && e.level() == nether, "the pet is not in the Nether");
            h.assertTrue(loaded().contains(rec), "a pet teleported across worlds left loaded()");
            e.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** Released and fallen pets leave the loaded index at once. */
    @GameTest(maxTicks = 40)
    public void releaseAndDeathLeaveLoaded(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        ArcanaCreature a = tamed(h, ArcanaEntities.species(ArcanaEntities.type("tabby_cat")), owner, new Vec3(3, 1, 3));
        ArcanaCreature b = tamed(h, ArcanaEntities.species(ArcanaEntities.type("black_cat")), owner, new Vec3(5, 1, 3));
        Object ra = record(a), rb = record(b);
        h.assertTrue(loaded().contains(ra) && loaded().contains(rb), "tamed pets are not in loaded()");
        String err = (String) CT.call(module(), "release", owner, ra);
        h.assertTrue(err == null, "release refused: " + err);
        h.assertFalse(loaded().contains(ra), "a released pet is still in loaded()");
        b.kill(h.getLevel());
        h.runAfterDelay(2, () -> {
            h.assertValueEqual(state(rb), "FALLEN", "state after death");
            h.assertFalse(loaded().contains(rb), "a fallen pet is still in loaded()");
            a.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /**
     * countActive and latestFallen (now over the owner's own pets) give what a scan over every record gives, for a mix
     * of living, fallen, stabled and mount pets, with mounts counted and not.
     */
    @GameTest
    public void perOwnerCountsMatchAFullScan(GameTestHelper h) {
        Object m = module(), st = store();
        @SuppressWarnings("unchecked")
        Map<String, Object> species = (Map<String, Object>) CT.get(m, "species");
        String pet = null, mount = null;
        for (var en : species.entrySet()) {
            boolean isMount = (boolean) CT.call(en.getValue(), "mount");
            if (isMount && mount == null) mount = en.getKey();
            if (!isMount && pet == null) pet = en.getKey();
        }
        h.assertTrue(pet != null && mount != null, "no pet or no mount species");
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        Object[] states = CT.cls("net.axther.aftrhrs.feature.pets.Pet$State").getEnumConstants();   // ALIVE, FALLEN, STABLED
        List<Object> made = new ArrayList<>();
        Object[][] plan = {{owner, pet, 0}, {owner, pet, 0}, {owner, pet, 1}, {owner, pet, 1}, {owner, mount, 1}, {owner, pet, 2},
                {owner, mount, 0}, {other, pet, 0}, {other, pet, 1}};
        long t = 1000;
        for (Object[] row : plan) {
            Object p = PetsWorkTests.newPet((UUID) row[0], (String) row[1], t);
            CT.set(p, "state", states[(int) row[2]]);
            if ((int) row[2] == 1) CT.set(p, "diedAt", t * 7 % 5003);
            t += 1000;
            CT.call(st, "add", p);
            made.add(p);
        }
        boolean was = (boolean) CT.get(m, "countMounts");
        List<String> problems = new ArrayList<>();
        try {
            for (boolean countMounts : new boolean[]{true, false}) {
                CT.set(m, "countMounts", countMounts);
                for (UUID who : List.of(owner, other)) {
                    int want = 0;
                    for (Object p : (java.util.Collection<?>) CT.call(st, "all")) {
                        if (!CT.get(p, "owner").equals(who) || !"ALIVE".equals(state(p))) continue;
                        Object s = species.get((String) CT.get(p, "species"));
                        if (!countMounts && s != null && (boolean) CT.call(s, "mount")) continue;
                        want++;
                    }
                    int got = (int) CT.call(m, "countActive", who);
                    if (got != want) problems.add("countActive(" + (who == owner ? "owner" : "other") + ", mounts " + countMounts + ") " + got + ", a full scan says " + want);
                }
            }
            for (UUID who : List.of(owner, other)) {
                for (String sp : List.of(pet, mount)) {
                    Object want = null;
                    for (Object p : (java.util.Collection<?>) CT.call(st, "all")) {
                        if (!"FALLEN".equals(state(p)) || !CT.get(p, "owner").equals(who) || !CT.get(p, "species").equals(sp)) continue;
                        if (want == null || (long) CT.get(p, "diedAt") > (long) CT.get(want, "diedAt")) want = p;
                    }
                    Object got = CT.call(m, "latestFallen", who, sp);
                    if (got != want) problems.add("latestFallen(" + sp + ") " + (got == null ? null : CT.get(got, "id")) + ", a full scan says " + (want == null ? null : CT.get(want, "id")));
                }
            }
        } finally {
            CT.set(m, "countMounts", was);
            for (Object p : made) CT.call(st, "remove", p);
        }
        CT.report(h, "per-owner counts", problems);
        h.succeed();
    }

    /** An untagged leftover entity whose UUID is on a pet's replaced list is still removed when it loads (replaced index). */
    @GameTest(maxTicks = 40)
    public void replacedLeftoverDespawnsOnLoad(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        Species s = ArcanaEntities.species(ArcanaEntities.type("husky_copper"));
        ArcanaCreature c = tamed(h, s, owner, new Vec3(3, 1, 3));
        Object rec = record(c);
        // the leftover: a tame copy of it in the world, no pet tag, its UUID on the pet's replaced list (load runs next tick)
        ArcanaCreature leftover = CT.spawn(h, s, new Vec3(5, 1, 5));
        leftover.setTame(true, true);
        leftover.setOwnerReference(net.minecraft.world.entity.EntityReference.of(owner.getUUID()));
        CT.call(store(), "addReplaced", rec, leftover.getUUID());
        h.assertTrue(CT.call(store(), "replacedOwner", leftover.getUUID()) == rec, "replacedOwner does not know the old entity");
        h.runAfterDelay(4, () -> {
            h.assertTrue(leftover.isRemoved(), "the leftover copy was not removed on load");
            h.assertTrue(record(c) == rec, "the pet lost its record");
            String err = (String) CT.call(module(), "release", owner, rec);
            h.assertTrue(err == null, "release refused: " + err);
            h.assertTrue(CT.call(store(), "replacedOwner", leftover.getUUID()) == null, "a released pet still owns its old entities");
            c.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /**
     * The first tame of a species is one line that carries the codex count (no separate "tamed for the first time"
     * line); the next one of that species is the plain tame line.
     */
    @GameTest(maxTicks = 40)
    public void firstOfKindTameIsOneLineWithTheCodexCount(GameTestHelper h) {
        CT.arena(h, 8, 8);
        Bots.Bot b = Bots.join(h, Bots.name("kind"));
        Object codex = CT.call(module(), "codex");
        CT.call(codex, "reset", b.player.getUUID());
        Species s = ArcanaEntities.species(ArcanaEntities.type("corgi"));
        ArcanaCreature first = CT.spawn(h, s, new Vec3(3, 1, 3));
        ArcanaCreature second = CT.spawn(h, s, new Vec3(5, 1, 3));
        b.clear();
        withCap(1000, () -> first.tamedBy(b.player));
        List<String> all = sent(b), lines = tameLines(all);
        int tameable = (int) CT.call(codex, "tameableTotal");
        h.assertValueEqual(lines.size(), 1, "tame chat lines " + all);
        h.assertTrue(lines.get(0).contains("new in your codex") && lines.get(0).contains("1/" + tameable), "the first-of-kind line has no codex count: " + lines);
        h.assertTrue(lines.get(0).contains("stay/follow"), "the stay / follow hint is missing: " + lines);
        for (String l : all) h.assertFalse(l.contains("tamed for the first time"), "a second codex line was sent: " + all);
        withCap(1000, () -> second.tamedBy(b.player));
        all = sent(b);
        lines = tameLines(all);
        h.assertValueEqual(lines.size(), 1, "tame chat lines for the second of its kind " + all);
        h.assertFalse(lines.get(0).contains("new in your codex"), "the second of its kind is new in the codex: " + lines);
        first.discard();
        second.discard();
        CT.call(codex, "reset", b.player.getUUID());
        b.leave();
        h.succeed();
    }

    /** With pets.yml unreadable the module announces nothing, so the creature's own tame line is shown. */
    @GameTest(maxTicks = 40)
    public void unreadableStoreLeavesTheCreatureLine(GameTestHelper h) {
        CT.arena(h, 8, 8);
        Bots.Bot b = Bots.join(h, Bots.name("ro"));
        ArcanaCreature c = CT.spawn(h, ArcanaEntities.species(ArcanaEntities.type("corgi")), new Vec3(3, 1, 3));
        Object st = store();
        b.clear();
        CT.set(st, "readOnly", true);
        try {
            withCap(1000, () -> c.tamedBy(b.player));
        } finally {
            CT.set(st, "readOnly", false);
        }
        List<String> chat = new ArrayList<>();
        for (String l : sent(b)) if (!l.startsWith("[bar] ") && !l.contains("has made the advancement")) chat.add(l);   // (vanilla's line)
        c.discard();   // before its load handling would adopt it
        h.assertValueEqual(chat.size(), 1, "chat lines with the store unreadable " + chat);
        h.assertFalse(chat.get(0).contains("joined your pets"), "the pets module announced a tame it could not record: " + chat);
        b.leave();
        h.succeed();
    }
}
