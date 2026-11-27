package net.axther.aftrhrs.feature.furniture.gadgets;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Catching small mobs in a cage or jar item: right-click or hit a whitelisted mob with an empty one. The mob is
 * serialized into the item (gzipped NBT with a DataVersion, like Paper's {@code serializeEntity}) and signed with
 * HMAC-SHA256 under the per-server key {@code data/gadgets.key} (item data is player-editable in creative, so unsigned
 * or tampered bytes are never deserialized; the key and format are Paper's, so cages caught there still open).
 * Right-click a block with the full item to let it out; sneak + right-click places the cage with the mob inside
 * (drawn by the renderer from the verified NBT). Empty hand on a placed full cage lets it out. Caged chickens lay eggs.
 */
final class Critters {
    private final GadgetsModule g;
    private byte[] secret;
    private Set<String> cageIds = Set.of(), jarIds = Set.of();
    private Set<EntityType<?>> cageMobs = Set.of(), jarMobs = Set.of();
    private boolean babies = false, excludeMythic = true, excludeTamedByOthers = true;
    private int maxSlimeSize = 1;
    private Set<String> mythicAllowed = Set.of();
    private double eggChance;

    Critters(GadgetsModule g) {
        this.g = g;
    }

    void load(Yaml cage, Yaml jar) {
        cageIds = GadgetsModule.lowerSet(cage.contains("ids") ? cage.getStringList("ids") : List.of("arcana_cage"));
        jarIds = GadgetsModule.lowerSet(jar.contains("ids") ? jar.getStringList("ids") : List.of("arcana_jar"));
        cageMobs = types(cage, "mobs", List.of("chicken", "rabbit", "parrot", "bee", "frog", "tadpole", "axolotl", "cod", "salmon",
                "tropical_fish", "pufferfish", "silverfish", "endermite", "bat", "allay", "slime", "magma_cube"));
        jarMobs = types(jar, "mobs", List.of("bee", "silverfish", "endermite", "tadpole", "cod", "salmon", "tropical_fish",
                "pufferfish", "slime", "magma_cube", "allay"));
        babies = cage.getBoolean("baby-animals", false);
        maxSlimeSize = cage.getInt("max-slime-size", 1);
        excludeMythic = cage.getBoolean("exclude-mythic-mobs", true);
        mythicAllowed = GadgetsModule.lowerSet(cage.getStringList("mythic-allowed"));
        excludeTamedByOthers = cage.getBoolean("exclude-others-pets", true);
        double minutes = Math.max(0.5, cage.getDouble("chicken-egg-minutes", 7.5));
        eggChance = 5.0 / (minutes * 60.0); // checked every 5 s
    }

    private Set<EntityType<?>> types(Yaml c, String key, List<String> def) {
        Set<EntityType<?>> out = new HashSet<>();
        for (String s : c.contains(key) ? c.getStringList(key) : def) {
            Identifier k = Identifier.tryParse(s.trim().toLowerCase(Locale.ROOT));
            EntityType<?> t = k == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(k).orElse(null);
            if (t == null) g.warnOnce("mob:" + s, "gadgets.yml: unknown mob '" + s + "'");
            else out.add(t);
        }
        return out;
    }

    boolean isCage(String id) {
        return cageIds.contains(id) || jarIds.contains(id);
    }

    private boolean isCatcherId(@Nullable String id) {
        return id != null && (cageIds.contains(id) || jarIds.contains(id));
    }

    /** Holding an empty cage or jar (one that can catch). */
    boolean holdsCatcher(Player p) {
        ItemStack held = p.getMainHandItem();
        return isCatcherId(GadgetsModule.idOf(held)) && !GadgetsModule.hasState(held);
    }

    boolean isFullCatcher(ItemStack s) {
        return isCatcherId(GadgetsModule.idOf(s)) && GadgetsModule.data(s).contains(GadgetsModule.K_MOB);
    }

    private boolean babyOk(Entity e) {
        return babies && e instanceof Animal && e instanceof AgeableMob a && a.isBaby();
    }

    private boolean catchable(String catcherId, LivingEntity e, ServerPlayer p) {
        Set<EntityType<?>> allowed = jarIds.contains(catcherId) && !cageIds.contains(catcherId) ? jarMobs : cageMobs;
        if (!allowed.contains(e.getType()) && !babyOk(e)) return false;
        if (e instanceof Player || !e.isAlive()) return false;
        if (e instanceof Slime s && s.getSize() > maxSlimeSize) return false;
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        if (AftrHrs.ID.equals(type.getNamespace()) && excludeMythic && !mythicAllowed.contains(type.getPath())) return false;
        if (excludeTamedByOthers && e instanceof OwnableEntity o && o.getOwnerReference() != null
                && !p.getUUID().equals(o.getOwnerReference().getUUID())) return false;
        return !e.isPassenger() && e.getPassengers().isEmpty() && !(e instanceof Mob m && m.isLeashed());
    }

    /** Catches {@code e} into the held empty cage / jar. True if the click was used. */
    boolean tryCatch(ServerPlayer p, LivingEntity e) {
        if (!holdsCatcher(p)) return false;
        ItemStack held = p.getMainHandItem();
        String id = GadgetsModule.idOf(held);
        if (!catchable(id, e, p)) {
            if (!(e instanceof Player)) g.msg(p, g.message("cage-too-big", "<muted>That won't fit in there."));
            return false;
        }
        ServerLevel level = (ServerLevel) e.level();
        if (!g.mayUse(p, level, e.blockPosition(), ClaimAction.BUILD)) return true;
        byte[] data;
        try {
            data = serialize(e);
        } catch (Exception ex) {
            g.warnOnce("serialize", "Could not serialize a " + e.getType() + " for a cage: " + ex);
            return true;
        }
        ItemStack full = held.copyWithCount(1);
        CompoundTag t = GadgetsModule.data(full);
        t.putString(GadgetsModule.K_KIND, "mob");
        t.put(GadgetsModule.K_MOB, new ByteArrayTag(data));
        t.put(GadgetsModule.K_MOB_SIG, new ByteArrayTag(sign(data)));
        t.putString(GadgetsModule.K_MOB_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
        full.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        g.describe(full, level);
        Vec3 at = e.position();
        e.discard();
        if (held.getCount() <= 1) p.setItemInHand(InteractionHand.MAIN_HAND, full);
        else {
            held.shrink(1);
            GadgetsModule.give(p, full);
        }
        GadgetsModule.sound(level, at, "block.chain.place", SoundSource.PLAYERS, 0.8f, 1.4f);
        level.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.3, at.z, 6, 0.2, 0.2, 0.2, 0.01);
        return true;
    }

    // ------------------------------------------------------------------ entity bytes (Paper's format) and signing

    /** Gzipped NBT of the entity (with its id) plus DataVersion: what Paper's serializeEntity wrote. */
    private static byte[] serialize(Entity e) throws Exception {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
        if (!e.saveAsPassenger(out)) throw new IllegalStateException("not saveable");
        CompoundTag tag = out.buildResult();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.toByteArray();
    }

    /** The entity NBT in signed bytes, data-fixed to the current version (no UUID: a fresh one each time). */
    private static @Nullable CompoundTag read(ServerLevel level, byte @Nullable [] data) {
        if (data == null) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2L << 20));
            int version = tag.getIntOr("DataVersion", -1);
            tag.remove("DataVersion");
            int current = SharedConstants.getCurrentVersion().dataVersion().version();
            if (version > 0 && version < current) {
                tag = (CompoundTag) level.getServer().getFixerUpper().update(References.ENTITY,
                        new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE, tag), version, current).getValue();
            }
            tag.remove("UUID");
            tag.remove("Passengers");
            return tag;
        } catch (Exception ex) {
            AftrHrs.LOG.warn("Unreadable caged mob ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** The stored mob bytes if this server signed them, else null (forged or tampered data is never deserialized). */
    private byte @Nullable [] signed(byte @Nullable [] data, byte @Nullable [] sig) {
        if (data == null || sig == null || !MessageDigest.isEqual(sig, sign(data))) {
            if (data != null) g.warnOnce("unsigned-mob", "Ignored a caged mob without a valid signature (edited item?)");
            return null;
        }
        return data;
    }

    private byte[] sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private synchronized byte[] secret() {
        if (secret != null) return secret;
        Path f = g.feather.dataFile("gadgets.key");
        try {
            if (Files.isRegularFile(f) && Files.size(f) == 32) {
                secret = Files.readAllBytes(f);
            } else {
                byte[] k = new byte[32];
                new SecureRandom().nextBytes(k);
                Files.createDirectories(f.getParent());
                Files.write(f, k);
                secret = k;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read or create " + f, e);
        }
        return secret;
    }

    /** Defense in depth after deserializing: only whitelisted small mobs. */
    private boolean safeToSpawn(Entity e) {
        boolean ok = (cageMobs.contains(e.getType()) || jarMobs.contains(e.getType()) || babyOk(e))
                && !(e instanceof Player) && !(e instanceof Slime sl && sl.getSize() > maxSlimeSize);
        if (!ok) g.warnOnce("unsafe-mob", "Refused to let a caged " + e.getType() + " out (not on the cage/jar list)");
        return ok;
    }

    private @Nullable Entity create(ServerLevel level, byte[] data) {
        CompoundTag tag = read(level, data);
        if (tag == null) return null;
        Entity e = EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true), x -> x);
        if (e == null || !safeToSpawn(e)) return null;
        e.setUUID(UUID.randomUUID());
        return e;
    }

    /** Spawns the real, free mob at {@code at}. */
    private boolean spawnFree(ServerLevel level, byte @Nullable [] data, Vec3 at) {
        if (data == null) return false;
        try {
            Entity e = create(level, data);
            if (e == null) return false;
            e.snapTo(at.x, at.y, at.z, level.getRandom().nextFloat() * 360f, 0);
            return level.addFreshEntity(e);
        } catch (Exception ex) {
            g.warnOnce("deserialize", "Could not let a caged mob out: " + ex);
            return false;
        }
    }

    /** Right-click a block with a full cage / jar item: the mob comes out in front of the clicked face. */
    void releaseFromItem(ServerPlayer p, ServerLevel level, BlockPos clicked, Direction face) {
        ItemStack held = p.getMainHandItem();
        CompoundTag t = GadgetsModule.data(held);
        byte[] data = signed(t.getByteArray(GadgetsModule.K_MOB).orElse(null), t.getByteArray(GadgetsModule.K_MOB_SIG).orElse(null));
        BlockPos target = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty() ? clicked : clicked.relative(face);
        Vec3 at = Vec3.atBottomCenterOf(target);
        if (!g.mayUse(p, level, target, ClaimAction.BUILD)) return;
        if (!spawnFree(level, data, at)) {
            g.msg(p, g.message("cage-release-failed", "<bad>It won't come out here."));
            return;
        }
        ItemStack empty = held.copy();
        GadgetsModule.clearItemState(empty);
        p.setItemInHand(InteractionHand.MAIN_HAND, empty);
        GadgetsModule.sound(level, at, "block.chain.break", SoundSource.PLAYERS, 0.8f, 1.4f);
    }

    /** Empty hand on a placed full cage / jar: the mob comes out. */
    boolean interactFull(ServerPlayer p, ServerLevel level, GadgetBlockEntity be) {
        if (!p.getMainHandItem().isEmpty()) return false;
        if (!g.mayUse(p, level, be.getBlockPos(), ClaimAction.BUILD)) return true;
        byte[] data = signed(be.mob, be.mobSig);
        Vec3 at = GadgetsModule.modelPoint(be, 8, 1, 8);
        if (!spawnFree(level, data, at)) return true;
        be.clearState();
        g.refresh(be);
        GadgetsModule.sound(level, at, "block.chain.break", SoundSource.BLOCKS, 0.8f, 1.4f);
        return true;
    }

    /** The NBT the renderer draws the caged mob from (verified and whitelisted, or null). */
    @Nullable CompoundTag preview(GadgetBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return null;
        byte[] data = signed(be.mob, be.mobSig);
        if (data == null) return null;
        CompoundTag tag = read(level, data);
        if (tag == null) return null;
        Identifier id = Identifier.tryParse(tag.getStringOr("id", ""));
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null || !(cageMobs.contains(type) || jarMobs.contains(type) || babies)) return null;
        // only what drawing needs
        for (String k : List.of("Inventory", "Brain", "Attributes", "ArmorItems", "HandItems", "equipment", "Leash", "drop_chances")) tag.remove(k);
        return tag;
    }

    /** Every 5 s per cage: a caged chicken lays an egg now and then. */
    void egg(ServerLevel level, GadgetBlockEntity be) {
        if (eggChance <= 0 || !"chicken".equals(be.mobType) || !"mob".equals(be.kind)) return;
        if (level.getRandom().nextDouble() >= eggChance) return;
        ItemStack egg = new ItemStack(Items.EGG);
        CompoundTag tag = be.mobPreview;
        String variant = tag == null ? "" : tag.getStringOr("variant", "");
        if (variant.endsWith("warm")) egg = new ItemStack(Items.BROWN_EGG);
        else if (variant.endsWith("cold")) egg = new ItemStack(Items.BLUE_EGG);
        Vec3 at = GadgetsModule.modelPoint(be, 8, 2, 8);
        net.minecraft.world.Containers.dropItemStack(level, at.x, at.y, at.z, egg);
        GadgetsModule.sound(level, at, "entity.chicken.egg", SoundSource.NEUTRAL, 1f, 1f);
    }
}
