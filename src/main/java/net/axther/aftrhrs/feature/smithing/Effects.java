package net.axther.aftrhrs.feature.smithing;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What traits and modifiers do while a smithed tool is used (SMITHING.md 6-7): mining speed that depends on where you
 * are, bonus damage that depends on the target or the moment, on-hit effects, drops (smelting, magnet, straight to the
 * pack), area mining, tree felling, vein mining, harvest-and-replant, durability rules and broken-not-destroyed. The
 * static numbers live in {@link ToolStats}.
 */
public final class Effects {
    /** Bleeding targets: ticks left and damage per second. */
    private record Bleed(LivingEntity target, int ticks, float perSecond, LivingEntity source) {
    }

    private static final List<Bleed> BLEEDS = new ArrayList<>();
    /** Kindled combo per attacker: hits and the tick of the last. */
    private static final Map<UUID, long[]> KINDLED = new HashMap<>();
    /** Hush: targets an attacker has already hit (first hit +3). */
    private static final Map<UUID, Set<UUID>> HUSHED = new HashMap<>();
    /** Players whose area / vein / fell breaks are running (their extra breaks must not chain). */
    private static final Set<UUID> BUSY = new HashSet<>();

    private Effects() {
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SmithingClock.serverTime = server.overworld().getGameTime();
            tickBleeds();
            if (server.getTickCount() % 20 == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) Workshop.tickHands(p);
            }
            if (server.getTickCount() % 1200 == 0) {
                HUSHED.clear();
                KINDLED.entrySet().removeIf(e -> server.getTickCount() - e.getValue()[1] > 200);
            }
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) afterBreak(sp, sl, pos, state);
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl) || BUSY.contains(sp.getUUID())) return true;
            ItemStack tool = sp.getMainHandItem();
            ToolBuild b = tool.get(Smithing.BUILD);
            if (b == null || sp.isShiftKeyDown()) return true;
            SmithingDefs.ToolType type = SmithingDefs.tool(b.tool());
            // scythe / Harvester: a ripe crop becomes a 3x3 harvest and replant
            if (type != null && (type.has("harvest_3x3") || b.level("harvester") > 0) && ripe(sl, pos)) {
                harvest(sp, sl, pos, 1);
                return false;
            }
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getEntity() instanceof ServerPlayer p) || !(entity.level() instanceof ServerLevel sl)) return;
            ToolBuild b = p.getMainHandItem().get(Smithing.BUILD);
            if (b == null) return;
            int xp = b.level("experienced");
            if (xp > 0 && entity.shouldDropExperience()) {
                ExperienceOrb.award(sl, entity.position(), Math.max(1, Math.round(entity.getExperienceReward(sl, p) * 0.5f * xp)));
            }
        });
        // Heavy Arrows: knock the target back harder
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) || taken <= 0) return;
            for (String t : arrow.entityTags()) {
                if (!t.startsWith("aftrhrs.heavy.")) continue;
                int level = Integer.parseInt(t.substring(14));
                Vec3 push = arrow.getDeltaMovement().multiply(1, 0, 1).normalize().scale(0.6 * level);
                entity.push(push.x, 0.1, push.z);
                entity.syncVelocity = true;
            }
        });
        // daggers and Dual Wield: right-click a mob to strike with the off-hand weapon
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (hand != InteractionHand.OFF_HAND || level.isClientSide() || !(entity instanceof LivingEntity target)) return InteractionResult.PASS;
            ItemStack off = player.getOffhandItem();
            ToolBuild b = off.get(Smithing.BUILD);
            if (b == null || off.has(Smithing.BROKEN)) return InteractionResult.PASS;
            SmithingDefs.ToolType type = SmithingDefs.tool(b.tool());
            if (type == null || !(type.has("offhand_attack") || b.level("dual_wield") > 0)) return InteractionResult.PASS;
            offhandAttack((ServerPlayer) player, target);
            return InteractionResult.SUCCESS;
        });
    }

    // ------------------------------------------------------------------ mining speed

    /** Multiplier on a player's mining speed with {@code tool} at this moment (Player#getDestroySpeed). */
    public static float speedFactor(Player p, ItemStack tool, BlockState state) {
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null) return 1;
        Map<String, Integer> t = Mods.traits(b);
        float f = 1;
        if (t.containsKey("bedrock_born") && tool.getMaxDamage() > 0) {
            f *= 1 + 0.3f * Math.min(1, t.get("bedrock_born") / 2f + 0.5f) * tool.getDamageValue() / (float) tool.getMaxDamage();
        }
        if (t.containsKey("deepdelver")) {
            double y = p.getY();
            float k = t.get("deepdelver");
            if (y < 0) f *= 1 + 0.25f * k;
            else if (y > 64) f *= 1 - 0.15f / k;
        }
        if ((t.containsKey("frostbite") || t.containsKey("frost_tempered")) && cold(p)) f *= 1.2f;
        return f;
    }

    static boolean cold(Entity e) {
        Biome biome = e.level().getBiome(e.blockPosition()).value();
        return biome.coldEnoughToSnow(e.blockPosition(), e.level().getSeaLevel());
    }

    // ------------------------------------------------------------------ damage

    /** Bonus damage on one hit (Item#getAttackDamageBonus): Edge, traits, mace smash, backstab, charge thrust. */
    static float attackBonus(SmithingDefs.ToolType type, Entity target, float damage, DamageSource source) {
        if (!(source.getEntity() instanceof LivingEntity attacker)) return 0;
        ItemStack tool = attacker.getMainHandItem();
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null || tool.has(Smithing.BROKEN)) return 0;
        Map<String, Integer> t = Mods.traits(b);
        float bonus = 0;
        if (Mods.edged(type) && Workshop.edgeEnabled()) bonus += damage * 0.15f * Smithing.edge(tool) / 100f;
        if (t.containsKey("serrated") && tool.getMaxDamage() > 0) bonus += 2f * t.get("serrated") / 3f * tool.getDamageValue() / tool.getMaxDamage() * 1.5f;
        if (t.containsKey("tidebound") && (attacker.isInWaterOrRain())) bonus += 2;
        if (t.containsKey("fireproof") && attacker.level().dimension() == Level.NETHER) bonus += damage * 0.2f;
        if (t.containsKey("hush")) {
            Set<UUID> seen = HUSHED.computeIfAbsent(attacker.getUUID(), k -> new HashSet<>());
            if (!seen.contains(target.getUUID())) bonus += 3;
        }
        if (t.containsKey("kindled")) {
            long[] c = KINDLED.get(attacker.getUUID());
            long now = attacker.level().getGameTime();
            if (c != null && now - c[1] <= 40) bonus += Math.min(3f, 0.5f * c[0]);
        }
        if (type.has("smash") && attacker.fallDistance > 1.5 && !attacker.isFallFlying()) {
            double fd = attacker.fallDistance;
            bonus += (float) (Math.min(fd, 3) * 4 + Math.max(0, Math.min(fd - 3, 5)) * 2 + Math.max(0, fd - 8));
        }
        if (type.has("backstab") && target instanceof LivingEntity le) {
            Vec3 look = le.getLookAngle().multiply(1, 0, 1).normalize();
            Vec3 toAttacker = attacker.position().subtract(le.position()).multiply(1, 0, 1).normalize();
            if (look.dot(toAttacker) < -0.5) bonus += damage * 0.5f;
        }
        if (type.has("charge_thrust") && attacker.isSprinting()) bonus += damage * 0.3f;
        return bonus;
    }

    /** After a hit landed (Item#hurtEnemy): on-hit effects, Edge wear, combos. */
    static void onHit(ItemStack tool, LivingEntity target, LivingEntity attacker) {
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null || tool.has(Smithing.BROKEN) || !(attacker.level() instanceof ServerLevel level)) return;
        SmithingDefs.ToolType type = SmithingDefs.tool(b.tool());
        Map<String, Integer> t = Mods.traits(b);
        if (t.containsKey("frostbite")) target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0), attacker);
        if (b.level("frost_edge") > 0) target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, b.level("frost_edge") - 1), attacker);
        if (t.containsKey("ember")) target.igniteForSeconds(3);
        if (b.level("emberstrike") > 0) target.igniteForSeconds(3 * b.level("emberstrike"));
        if (b.level("venomous") > 0) target.addEffect(new MobEffectInstance(MobEffects.POISON, 80, b.level("venomous") - 1), attacker);
        if (b.level("barbed") > 0) bleed(target, 60, b.level("barbed") * 2f / 3f, attacker);
        boolean crit = attacker.fallDistance > 0 && !attacker.onGround() && !attacker.onClimbable() && !attacker.isInWater();
        if (t.containsKey("splintering") && crit) bleed(target, 60, 2f / 3f, attacker);
        if (b.level("lifedrinker") > 0) {
            float dealt = (float) attacker.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            attacker.heal(dealt * 0.05f * b.level("lifedrinker"));
        }
        if (t.containsKey("kindled")) {
            long now = level.getGameTime();
            long[] c = KINDLED.computeIfAbsent(attacker.getUUID(), k -> new long[]{0, 0});
            c[0] = now - c[1] <= 40 ? Math.min(6, c[0] + 1) : 1;
            c[1] = now;
        }
        if (t.containsKey("hush")) HUSHED.computeIfAbsent(attacker.getUUID(), k -> new HashSet<>()).add(target.getUUID());
        if (type != null && type.has("smash") && attacker.fallDistance > 1.5) {
            attacker.resetFallDistance();
            level.playSound(null, target.blockPosition(), SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 1f, 1f);
            for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(3), e -> e != attacker && e != target)) {
                Vec3 push = near.position().subtract(target.position()).normalize().scale(0.7);
                near.push(push.x, 0.4, push.z);
                near.syncVelocity = true;
            }
        }
        // Edge wears a little with each hit (1 in 3)
        if (type != null && Mods.edged(type) && attacker.getRandom().nextInt(3) == 0) Smithing.setEdge(tool, Smithing.edge(tool) - 1);
    }

    private static void bleed(LivingEntity target, int ticks, float perSecond, LivingEntity source) {
        BLEEDS.add(new Bleed(target, ticks, perSecond, source));
    }

    private static void tickBleeds() {
        if (BLEEDS.isEmpty()) return;
        List<Bleed> next = new ArrayList<>();
        for (Iterator<Bleed> it = BLEEDS.iterator(); it.hasNext(); ) {
            Bleed bl = it.next();
            if (!bl.target().isAlive() || bl.ticks() <= 0) continue;
            if (bl.ticks() % 20 == 0 && bl.target().level() instanceof ServerLevel sl) {
                DamageSource src = bl.source() instanceof Player pl ? sl.damageSources().playerAttack(pl) : sl.damageSources().generic();
                bl.target().hurtServer(sl, src, bl.perSecond());
                sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
                        bl.target().getX(), bl.target().getY() + bl.target().getBbHeight() * 0.6, bl.target().getZ(), 6, 0.2, 0.2, 0.2, 0);
            }
            next.add(new Bleed(bl.target(), bl.ticks() - 1, bl.perSecond(), bl.source()));
        }
        BLEEDS.clear();
        BLEEDS.addAll(next);
    }

    private static void offhandAttack(ServerPlayer p, LivingEntity target) {
        ItemStack main = p.getMainHandItem(), off = p.getOffhandItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, off);
        p.setItemInHand(InteractionHand.OFF_HAND, main);
        try {
            p.attack(target);
        } finally {
            p.setItemInHand(InteractionHand.OFF_HAND, p.getMainHandItem());
            p.setItemInHand(InteractionHand.MAIN_HAND, main);
        }
        p.swing(InteractionHand.OFF_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
    }

    // ------------------------------------------------------------------ ticking in the inventory

    static void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
        ToolBuild b = stack.get(Smithing.BUILD);
        if (b == null || level.getGameTime() % 200 != 0) return;
        // Radiant: 1 durability every 10 s in sunlight
        if (Mods.trait(b, "radiant") > 0 && stack.isDamaged() && level.isBrightOutside() && level.canSeeSky(owner.blockPosition())) {
            stack.setDamageValue(stack.getDamageValue() - 1);
            if (stack.has(Smithing.BROKEN)) Smithing.repaired(stack, level);
        }
    }

    // ------------------------------------------------------------------ right-click on blocks

    static InteractionResult useOn(SmithingDefs.ToolType type, UseOnContext ctx) {
        ItemStack tool = ctx.getItemInHand();
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null || tool.has(Smithing.BROKEN)) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        // harvest and replant a ripe crop
        if ((type.has("harvest_replant") || type.has("harvest_3x3") || b.level("harvester") > 0) && level instanceof ServerLevel sl
                && ctx.getPlayer() instanceof ServerPlayer sp && ripe(sl, pos)) {
            harvest(sp, sl, pos, type.has("harvest_3x3") || b.level("harvester") > 0 ? 1 : 0);
            return InteractionResult.SUCCESS;
        }
        if (type.has("strip")) {
            InteractionResult r = Items.IRON_AXE.useOn(ctx);
            if (r != InteractionResult.PASS) return r;
        }
        if (type.has("path")) {
            InteractionResult r = Items.IRON_SHOVEL.useOn(ctx);
            if (r != InteractionResult.PASS) return r;
        }
        if (type.has("till") || b.level("tiller") > 0) {
            InteractionResult r = Items.IRON_HOE.useOn(ctx);
            if (r != InteractionResult.PASS) return r;
        }
        // Glowing: place a light where you click (10 durability)
        if (b.level("glowing") > 0 && ctx.getPlayer() != null) {
            BlockPos at = pos.relative(ctx.getClickedFace());
            if (level.getBlockState(at).canBeReplaced()) {
                if (!level.isClientSide()) {
                    level.setBlock(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 14), 3);
                    tool.hurtAndBreak(10, ctx.getPlayer(), ctx.getHand());
                    level.playSound(null, at, SoundEvents.GLOW_INK_SAC_USE, SoundSource.PLAYERS, 1f, 1.2f);
                }
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    // ------------------------------------------------------------------ after a block broke

    private static void afterBreak(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state) {
        ItemStack tool = p.getMainHandItem();
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null) return;
        SmithingDefs.ToolType type = SmithingDefs.tool(b.tool());
        if (type == null) return;
        Map<String, Integer> t = Mods.traits(b);
        // Seasoned: a wooden handle grows tougher with use
        if (t.containsKey("seasoned") && b.progress("seasoned") < 5000) {
            ToolBuild nb = b.withProgress("seasoned", b.progress("seasoned") + 1);
            tool.set(Smithing.BUILD, nb);
            if (nb.progress("seasoned") % 50 == 0) ToolStats.apply(tool, level.registryAccess());
        }
        // Resonant: stone rings out the ores nearby
        if (t.containsKey("resonant") && state.is(BlockTags.BASE_STONE_OVERWORLD)) ping(p, level, pos);
        int xp = b.level("experienced");
        if (xp > 0 && isOre(state)) ExperienceOrb.award(level, Vec3.atCenterOf(pos), level.getRandom().nextInt(2) + xp);
        if (BUSY.contains(p.getUUID()) || p.isShiftKeyDown()) {
            magnet(p, level, pos, t, b);
            return;
        }
        int area = (type.has("area_3x3") ? 1 : 0) + b.level("broadstrike");
        if (area > 0) areaMine(p, level, pos, state, area);
        if ((type.has("treefell") || b.level("treefeller") > 0) && state.is(BlockTags.LOGS)) fell(p, level, pos, state);
        if (b.level("veinseeker") > 0 && isOre(state)) vein(p, level, pos, state);
        magnet(p, level, pos, t, b);
    }

    static boolean isOre(BlockState s) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return path.endsWith("_ore") || s.is(Blocks.ANCIENT_DEBRIS);
    }

    private static void ping(ServerPlayer p, ServerLevel level, BlockPos at) {
        int r = 6;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
            if (!isOre(level.getBlockState(q))) continue;
            level.sendParticles(p, ParticleTypes.WAX_ON, true, false, q.getX() + 0.5, q.getY() + 0.5, q.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0);
        }
        level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6f, 1.4f);
    }

    /** Lodestone (trait 3 blocks, modifier 5): drops around the broken block fly to you, a tick later. */
    private static void magnet(ServerPlayer p, ServerLevel level, BlockPos pos, Map<String, Integer> t, ToolBuild b) {
        int r = b.level("lodestone") > 0 ? 5 : t.containsKey("lodestone") ? 3 : 0;
        if (r == 0) return;
        UUID id = p.getUUID();
        net.axther.aftrhrs.core.Tasks.later(1, () -> {
            ServerPlayer pl = level.getServer().getPlayerList().getPlayer(id);
            if (pl == null || pl.level() != level) return;
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(r))) {
                if (ie.hasPickUpDelay() && ie.getAge() > 20) continue;
                ie.setPos(pl.getX(), pl.getY() + 0.5, pl.getZ());
                ie.setNoPickUpDelay();
            }
        });
    }

    private static boolean mayBreak(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Feather f = Feather.get();
        return f == null || f.claimAllows(p, Loc.of(level, pos), ClaimAction.BUILD);
    }

    private static void breakAsPlayer(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!mayBreak(p, level, pos)) return;
        BUSY.add(p.getUUID());
        try {
            p.gameMode.destroyBlock(pos);
        } finally {
            BUSY.remove(p.getUUID());
        }
    }

    private static boolean areaOk(ServerLevel level, BlockPos pos, BlockState s, ItemStack tool, float maxHardness) {
        if (s.isAir() || s.hasBlockEntity() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return false;
        float h = s.getDestroySpeed(level, pos);
        if (h < 0 || h > maxHardness) return false;
        if (net.axther.aftrhrs.content.ArcanaContent.id(s) != null) return false;
        return tool.getDestroySpeed(s) > 1.0f && tool.isCorrectToolForDrops(s);
    }

    /** 3x3 (radius 1) or 5x5 (radius 2) on the face the player mined. */
    private static void areaMine(ServerPlayer p, ServerLevel level, BlockPos center, BlockState was, int radius) {
        ItemStack tool = p.getMainHandItem();
        Direction face = net.axther.aftrhrs.feature.tools.AreaToolsAccess.minedFace(p, center);
        float maxHardness = was.getDestroySpeed(level, center) * 1.5f + 0.5f;
        List<BlockPos> targets = new ArrayList<>();
        for (int a = -radius; a <= radius; a++) {
            for (int c = -radius; c <= radius; c++) {
                if (a == 0 && c == 0) continue;
                targets.add(switch (face.getAxis()) {
                    case Y -> center.offset(a, 0, c);
                    case Z -> center.offset(a, c, 0);
                    case X -> center.offset(0, a, c);
                });
            }
        }
        UUID id = p.getUUID();
        net.axther.aftrhrs.core.Tasks.later(1, () -> {
            ServerPlayer pl = level.getServer().getPlayerList().getPlayer(id);
            if (pl == null || pl.level() != level) return;
            for (BlockPos q : targets) {
                ItemStack now = pl.getMainHandItem();
                if (now != tool || now.has(Smithing.BROKEN)) return;
                if (!level.isLoaded(q)) continue;
                BlockState s = level.getBlockState(q);
                if (areaOk(level, q, s, now, maxHardness)) breakAsPlayer(pl, level, q);
            }
        });
    }

    /** Fells a natural tree: connected logs of the same kind with natural leaves around them. */
    private static void fell(ServerPlayer p, ServerLevel level, BlockPos origin, BlockState was) {
        Block log = was.getBlock();
        List<BlockPos> logs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin.asLong());
        int leaves = 0;
        while (!queue.isEmpty() && logs.size() < 192) {
            BlockPos b = queue.poll();
            for (BlockPos n : BlockPos.betweenClosed(b.offset(-1, 0, -1), b.offset(1, 1, 1))) {
                if (!seen.add(n.asLong()) || Math.abs(n.getX() - origin.getX()) > 8 || Math.abs(n.getZ() - origin.getZ()) > 8) continue;
                BlockState s = level.getBlockState(n);
                if (s.is(log)) {
                    BlockPos im = n.immutable();
                    logs.add(im);
                    queue.add(im);
                } else if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) {
                    leaves++;
                }
            }
        }
        if (leaves < 3 || logs.isEmpty()) return;
        UUID id = p.getUUID();
        ItemStack tool = p.getMainHandItem();
        net.axther.aftrhrs.core.Tasks.later(1, () -> {
            ServerPlayer pl = level.getServer().getPlayerList().getPlayer(id);
            if (pl == null || pl.level() != level) return;
            for (BlockPos q : logs) {
                if (pl.getMainHandItem() != tool || tool.has(Smithing.BROKEN)) return;
                if (level.getBlockState(q).is(log)) breakAsPlayer(pl, level, q);
            }
        });
    }

    /** Veinseeker: up to 12 more connected blocks of the same ore. */
    private static void vein(ServerPlayer p, ServerLevel level, BlockPos origin, BlockState was) {
        veinMine(p, level, origin, was, 12);
    }

    // ------------------------------------------------------------------ shared with feature.enchants

    /** True while this player's area / vein / fell / harvest breaks run (their extra breaks must not chain). */
    public static boolean busy(ServerPlayer p) {
        return BUSY.contains(p.getUUID());
    }

    /** A vanilla ore or ancient debris. */
    public static boolean ore(BlockState s) {
        return isOre(s);
    }

    /** A ripe crop (vanilla or Arcana). */
    public static boolean ripeCrop(ServerLevel level, BlockPos pos) {
        return ripe(level, pos);
    }

    /** Harvests and replants ripe crops around {@code center} (radius 0 = just it) with the held tool. */
    public static void harvestCrops(ServerPlayer p, ServerLevel level, BlockPos center, int radius) {
        harvest(p, level, center, radius);
    }

    /** Each drop that has a smelting recipe becomes its result (as many as there were). */
    public static List<ItemStack> smeltAll(List<ItemStack> drops, ServerLevel level) {
        List<ItemStack> out = new ArrayList<>(drops.size());
        for (ItemStack d : drops) {
            var r = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(d), level);
            if (r.isPresent()) {
                ItemStack res = r.get().value().assemble(new SingleRecipeInput(d));
                if (!res.isEmpty()) {
                    out.add(res.copyWithCount(res.getCount() * d.getCount()));
                    continue;
                }
            }
            out.add(d);
        }
        return out;
    }

    /** A block's drops go straight into the breaker's pack (what doesn't fit drops as usual), then its XP. */
    public static boolean pocket(BlockState state, ServerLevel level, BlockPos pos, BlockEntity be, Entity breaker, ItemStack tool) {
        if (!(breaker instanceof ServerPlayer p)) return false;
        for (ItemStack d : Block.getDrops(state, level, pos, be, breaker, tool)) {
            if (!p.getInventory().add(d) && !d.isEmpty()) Block.popResource(level, pos, d);
        }
        state.spawnAfterBreak(level, pos, tool, true);
        return true;
    }

    /** Mines up to {@code max} more blocks of the same ore connected to {@code origin}, the tick after. */
    public static void veinMine(ServerPlayer p, ServerLevel level, BlockPos origin, BlockState was, int max) {
        Block ore = was.getBlock();
        List<BlockPos> found = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin.asLong());
        while (!queue.isEmpty() && found.size() < max) {
            BlockPos b = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = b.relative(d);
                if (!seen.add(n.asLong()) || !level.getBlockState(n).is(ore)) continue;
                found.add(n);
                queue.add(n);
                if (found.size() >= max) break;
            }
        }
        UUID id = p.getUUID();
        ItemStack tool = p.getMainHandItem();
        net.axther.aftrhrs.core.Tasks.later(1, () -> {
            ServerPlayer pl = level.getServer().getPlayerList().getPlayer(id);
            if (pl == null || pl.level() != level) return;
            for (BlockPos q : found) {
                if (pl.getMainHandItem() != tool || tool.has(Smithing.BROKEN)) return;
                if (level.getBlockState(q).is(ore)) breakAsPlayer(pl, level, q);
            }
        });
    }

    // ------------------------------------------------------------------ crops

    private static IntegerProperty age(BlockState s) {
        for (Property<?> p : s.getProperties()) if (p instanceof IntegerProperty ip && p.getName().equals("age")) return ip;
        return null;
    }

    static boolean ripe(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof CropBlock crop) return crop.isMaxAge(s);
        if (s.is(Blocks.NETHER_WART)) return s.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) >= 3;
        Hooks.Crops c = crops();
        try {
            return c != null && c.ripe(level, pos);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Hooks.Crops crops() {
        Feather f = Feather.get();
        return f == null ? null : f.service(Hooks.Crops.class);
    }

    /** Harvests ripe crops around {@code center} (radius 0 = just it) and replants them from their own drops. */
    static void harvest(ServerPlayer p, ServerLevel level, BlockPos center, int radius) {
        ItemStack tool = p.getMainHandItem();
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-radius, 0, -radius), center.offset(radius, 0, radius))) {
            BlockPos pos = q.immutable();
            if (!ripe(level, pos) || !mayBreak(p, level, pos)) continue;
            BlockState before = level.getBlockState(pos);
            if (!(before.getBlock() instanceof CropBlock) && !before.is(Blocks.NETHER_WART)) {
                Hooks.Crops c = crops();
                if (c != null) {
                    BUSY.add(p.getUUID());
                    try {
                        if (c.harvest(p, level, pos, true)) tool.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
                    } catch (RuntimeException ignored) {
                    } finally {
                        BUSY.remove(p.getUUID());
                    }
                }
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            List<ItemStack> drops = p.isCreative() ? new ArrayList<>() : new ArrayList<>(Block.getDrops(before, level, pos, be, p, tool));
            ItemStack seed = new ItemStack(before.getBlock().asItem());
            boolean replanted = p.isCreative();
            for (ItemStack d : drops) {
                if (!replanted && ItemStack.isSameItem(d, seed) && d.getCount() > 0) {
                    d.shrink(1);
                    replanted = true;
                }
            }
            IntegerProperty a = age(before);
            if (replanted && a != null) level.setBlock(pos, before.setValue(a, 0), 2);
            else level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.levelEvent(2001, pos, Block.getId(before));
            for (ItemStack d : drops) if (!d.isEmpty()) Block.popResource(level, pos, d);
            if (!p.isCreative()) tool.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
            if (tool.has(Smithing.BROKEN)) return;
        }
    }

    // ------------------------------------------------------------------ drops (from the Block mixin)

    /**
     * Drops of a block broken with a smithed tool: Smeltforged (always) and Ember (1 in 4) smelt what can be smelted.
     */
    public static List<ItemStack> drops(List<ItemStack> drops, ServerLevel level, ItemStack tool) {
        if (drops == null) return null;
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null || drops.isEmpty()) return drops;
        boolean smelt = b.level("smeltforged") > 0 || Mods.trait(b, "ember") > 0 && level.getRandom().nextInt(4) == 0;
        if (!smelt) return drops;
        return smeltAll(drops, level);
    }

    /** Warping (voidgold): drops go straight into the player's pack. True when the drops were handled. */
    public static boolean warp(BlockState state, ServerLevel level, BlockPos pos, BlockEntity be, Entity breaker, ItemStack tool) {
        if (!(breaker instanceof ServerPlayer p)) return false;
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null || Mods.trait(b, "warping") == 0) return false;
        return pocket(state, level, pos, be, p, tool);
    }

    // ------------------------------------------------------------------ durability (from the ItemStack mixin)

    /**
     * How much durability a hit really costs: Reinforced (12% per level to skip), Unyielding (at most 1), Everlasting
     * (a third, rounded at random).
     */
    public static int durabilityCost(ItemStack stack, int amount, net.minecraft.util.RandomSource random) {
        ToolBuild b = stack.get(Smithing.BUILD);
        if (b == null || amount <= 0) return amount;
        int out = 0;
        int reinforced = b.level("reinforced");
        for (int i = 0; i < amount; i++) if (random.nextFloat() >= 0.12f * reinforced) out++;
        if (Mods.trait(b, "unyielding") > 0) out = Math.min(out, 1);
        if (b.level("everlasting") > 0) out = out / 3 + (random.nextInt(3) < out % 3 ? 1 : 0);
        return out;
    }
}
