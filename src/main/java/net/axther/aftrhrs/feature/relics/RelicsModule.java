package net.axther.aftrhrs.feature.relics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.ArcanaItem;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The relic item mechanics Oraxen ran on Paper (always on, like Oraxen was), read from each content entry's
 * {@code mechanics}: {@code aura} (particles around whoever holds it), {@code energyblast}, {@code thor},
 * {@code witherskull} (right-click spells with a per-player delay), {@code lifeleech} and {@code knockback_strike} (on
 * melee hits), {@code spear_lunge} (right-click charges with the relic's charge frames, left-click lunges), plus
 * {@code glowing} (dropped relics glow in their color) and backpacks on the back: the one a player wears
 * ({@link BackpackWear}; tools.yml {@code backpacks:}) shows on their back for everyone, synced to clients with
 * {@link RelicsInit.BackpackPayload}. Oraxen's {@code backpack_cosmetic} (a backpack anywhere in the inventory shows)
 * is now {@code backpacks.inventory-cosmetic}, off by default: with a real back slot a backpack on the back should
 * mean it is worn. The Tidecaller's thrown look is {@link ThrownRelicTrident}.
 */
public final class RelicsModule implements FeatherModule {
    private static final String COOLDOWN_MSG = "<#facc43>Please wait another <time> seconds!";
    private static final Identifier SLOW_ID = AftrHrs.id("spear_charge");
    static volatile RelicsModule active;

    private final Feather feather;
    /** "mechanic:uuid" → epoch ms when the spell may be cast again. */
    private final Map<String, Long> timers = new HashMap<>();
    private final Map<UUID, Charge> charging = new HashMap<>();
    private final Map<UUID, Long> lungeCooldown = new HashMap<>();
    private final Map<UUID, Long> lunging = new HashMap<>();
    private final Set<UUID> intentional = new HashSet<>();
    /** "item:uuid" → {hit count, last hit epoch ms}. */
    private final Map<String, long[]> strikeHits = new HashMap<>();
    private final Map<UUID, String> glowing = new HashMap<>();
    /** Backpack shown per player (without its contents; EMPTY for none), as last sent. */
    private final Map<UUID, ItemStack> backpacks = new HashMap<>();
    private Map<String, SoundEvent> soundsByEnum;
    /** tools.yml {@code backpacks.inventory-cosmetic}: with nothing worn, show a backpack carried in the inventory. */
    private boolean inventoryCosmetic;
    /** tools.yml {@code backpacks.elytra}: hide (true) or show a worn backpack while an elytra is in the chest slot. */
    private boolean hideWithElytra = true;

    /** {@code stack}: the relic being charged (its model is swapped; restored on that very stack, wherever it went). */
    private record Charge(long startTick, JsonObject mech, InteractionHand hand, String itemId, @Nullable Identifier originalModel,
                          int lastFrame, ItemStack stack) {
        Charge withFrame(int f) {
            return new Charge(startTick, mech, hand, itemId, originalModel, f, stack);
        }
    }

    public RelicsModule(Feather feather) {
        this.feather = feather;
    }

    @Override
    public String name() {
        return "Relics";
    }

    @Override
    public void enable() {
        active = this;
        reload();
        feather.once("relics.events", RelicsModule::register);
        Tasks.timer(15, 15, this::auraTick);
        Tasks.timer(1, 1, this::chargeTick);
        Tasks.timer(10, 10, this::backpackTick);
        feather.provide(Hooks.BackSlot.class, new Hooks.BackSlot() {
            @Override
            public ItemStack worn(ServerPlayer player) {
                return BackpackWear.worn(player);
            }

            @Override
            public void clear(ServerPlayer player) {
                BackpackWear.take(player);
                backpackChanged(player);
            }

            @Override
            public boolean wear(ServerPlayer player, ItemStack stack) {
                if (!BackpackWear.serverWear || !BackpackWear.isBackpack(stack) || !BackpackWear.worn(player).isEmpty()) return false;
                BackpackWear.setWorn(player, stack);
                backpackChanged(player);
                return true;
            }
        });
    }

    @Override
    public void reload() {
        Yaml c = feather.loadResourceConfig("tools.yml");
        BackpackWear.serverWear = c.getBoolean("backpacks.wear", true);
        hideWithElytra = !"show".equalsIgnoreCase(c.getString("backpacks.elytra", "hide").trim());
        inventoryCosmetic = c.getBoolean("backpacks.inventory-cosmetic", false);
        // Everyone hears the new settings and sees each back as they now say (also an inventory backpack going away).
        for (ServerPlayer p : feather.onlinePlayers()) {
            sendBackSlot(p);
            syncBackpack(p, true);
        }
    }

    @Override
    public void disable() {
        for (UUID id : new ArrayList<>(charging.keySet())) {
            ServerPlayer p = feather.player(id);
            if (p != null) cancelCharge(p, false);
        }
        charging.clear();
        backpacks.clear();
        feather.withdraw(Hooks.BackSlot.class);
        if (active == this) active = null;
    }

    private static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            RelicsModule m = active;
            if (m != null && player instanceof ServerPlayer sp && !sp.isSpectator()) m.onUse(sp, hand);
            return InteractionResult.PASS;
        });
        FeatherEvents.PLAYER_PUNCH.register(p -> {
            RelicsModule m = active;
            if (m != null && m.charging.containsKey(p.getUUID())) m.cancelCharge(p, true);
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            RelicsModule m = active;
            if (m == null || !(player instanceof ServerPlayer sp) || !m.charging.containsKey(sp.getUUID())) return InteractionResult.PASS;
            m.cancelCharge(sp, true);
            return InteractionResult.FAIL;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            RelicsModule m = active;
            if (m == null || !(player instanceof ServerPlayer sp) || !m.charging.containsKey(sp.getUUID())) return InteractionResult.PASS;
            m.cancelCharge(sp, true);
            return InteractionResult.FAIL;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            RelicsModule m = active;
            return m == null || m.allowDamage(entity, source);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            RelicsModule m = active;
            if (m != null && !blocked) m.onHit(entity, source);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            RelicsModule m = active;
            if (m != null && entity instanceof ItemEntity item) m.glow(level, item);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            RelicsModule m = active;
            if (m != null && entity instanceof ItemEntity item) m.unglow(level, item);
        });
        EntityTrackingEvents.START_TRACKING.register((tracked, viewer) -> {
            RelicsModule m = active;
            if (m == null || !(tracked instanceof ServerPlayer owner)) return;
            ItemStack bp = m.backpacks.get(owner.getUUID());
            if (bp != null && !bp.isEmpty()) ServerPlayNetworking.send(viewer, new RelicsInit.BackpackPayload(owner.getId(), bp));
        });
        // Oraxen's glow teams survive in scoreboard.dat with entries for items long gone: keep only loaded ones.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RelicsModule m = active;
            if (m == null) return;
            Scoreboard board = server.getScoreboard();
            Set<String> live = new HashSet<>();
            for (UUID u : m.glowing.keySet()) live.add(u.toString());
            for (PlayerTeam team : new ArrayList<>(board.getPlayerTeams())) {
                if (!team.getName().startsWith("orx_glow_")) continue;
                for (String entry : new ArrayList<>(team.getPlayers())) if (!live.contains(entry)) board.removePlayerFromTeam(entry, team);
                if (team.getPlayers().isEmpty()) board.removePlayerTeam(team);
            }
        });
        // ---- backpacks on the back
        ServerPlayNetworking.registerGlobalReceiver(RelicsInit.BackpackKeyPayload.TYPE, (payload, ctx) -> {
            if (active != null) BackpackWear.key(ctx.player(), payload.sneak());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            RelicsModule m = active;
            if (m != null) m.sendBackSlot(handler.player);
        });
        // The client's channel list arrives a moment after the join: settings and every backpack it can see, then.
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (!channels.contains(RelicsInit.BackpackPayload.TYPE.id()) && !channels.contains(RelicsInit.BackSlotPayload.TYPE.id())) return;
            server.execute(() -> {
                RelicsModule m = active;
                if (m != null && !handler.player.hasDisconnected()) m.channelsOpened(handler.player);
            });
        });
        // A screen open on a backpack closes with the death: whatever is on the cursor drops like vanilla's, and
        // nothing more can be moved through it once the death took the backpack (into the grave or the drops).
        FeatherEvents.PLAYER_DEATH.register((p, source) -> {
            if (ArcanaItem.isBackpackMenu(p.containerMenu)) p.closeContainer();
        });
        // The client forgets who wears what when it rebuilds its player (respawn, other dimension): send it again.
        FeatherEvents.PLAYER_RESPAWN.register((oldP, newP, alive) -> {
            RelicsModule m = active;
            if (m != null) m.backpackChanged(newP);
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
            RelicsModule m = active;
            if (m != null) m.backpackChanged(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            RelicsModule m = active;
            if (m == null) return;
            UUID id = handler.player.getUUID();
            m.cancelCharge(handler.player, false); // the relic is saved with its own model, not a charge frame
            m.charging.remove(id);
            m.lungeCooldown.remove(id);
            m.lunging.remove(id);
            m.backpacks.remove(id);
            m.timers.keySet().removeIf(k -> k.endsWith(id.toString()));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static @Nullable JsonObject mech(ItemStack stack, String name) {
        return RelicsInit.mechanic(ArcanaContent.id(stack), name);
    }

    private static double d(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    private static int i(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
    }

    private static boolean b(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    private static @Nullable String s(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    /** A Bukkit particle name (old aliases too) as a particle; dust gets its color. */
    static @Nullable ParticleOptions particle(@Nullable String name, int rgb, float size) {
        if (name == null) return null;
        String k = name.trim().toLowerCase(Locale.ROOT);
        k = switch (k) {
            case "redstone" -> "dust";
            case "enchantment_table" -> "enchant";
            case "crit_magic" -> "enchanted_hit";
            case "smoke_normal" -> "smoke";
            case "water_drop" -> "dripping_water";
            case "spell_witch" -> "witch";
            default -> k;
        };
        if (k.equals("dust")) return new DustParticleOptions(rgb, Math.max(0.01f, size));
        Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
        ParticleType<?> t = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
        return t instanceof SimpleParticleType st ? st : null;
    }

    /** A sound key ({@code item.trident.riptide_1}) or Bukkit enum name ({@code ENTITY_IRON_GOLEM_ATTACK}). */
    @Nullable SoundEvent sound(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        String k = name.trim().toLowerCase(Locale.ROOT);
        if (k.contains(".") || k.contains(":")) {
            Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
            return id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
        }
        if (soundsByEnum == null) {
            soundsByEnum = new HashMap<>();
            for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
                if (id.getNamespace().equals("minecraft")) soundsByEnum.putIfAbsent(id.getPath().replace('.', '_'), BuiltInRegistries.SOUND_EVENT.getValue(id));
            }
        }
        return soundsByEnum.get(k);
    }

    private void play(ServerLevel level, Vec3 at, @Nullable String name, float volume, float pitch) {
        SoundEvent e = sound(name);
        if (e != null) level.playSound(null, at.x, at.y, at.z, e, SoundSource.PLAYERS, volume, pitch);
    }

    /** Oraxen's spell timers: true (and restarted) if ready, else the "please wait" bar. */
    private boolean ready(ServerPlayer p, String mechanic, JsonObject m) {
        long delay = (long) d(m, "delay", 0);
        String key = mechanic + ":" + p.getUUID();
        long now = System.currentTimeMillis();
        Long readyAt = timers.get(key);
        if (readyAt != null && readyAt > now) {
            Adv.actionBar(p, feather.text().parse(COOLDOWN_MSG,
                    Placeholder.unparsed("time", String.format(Locale.ROOT, "%.1f", (readyAt - now) / 1000.0))));
            return false;
        }
        timers.put(key, now + delay);
        return true;
    }

    private boolean may(ServerPlayer p, ServerLevel level, BlockPos at, ClaimAction action) {
        return feather.claimAllows(p, Loc.of(level, at), action);
    }

    /** May this relic's caster hurt / push / affect {@code e}? (BUILD trust where it stands; players too.) */
    private boolean mayAffect(ServerPlayer p, Entity e) {
        return feather.claimAllows(p, Loc.of(e), ClaimAction.BUILD);
    }

    private boolean mayAffectAt(ServerPlayer p, Vec3 at) {
        return feather.claimAllows(p, Loc.of((ServerLevel) p.level(), at), ClaimAction.BUILD);
    }

    // ------------------------------------------------------------------ right-click spells + spear charge

    private void onUse(ServerPlayer p, InteractionHand hand) {
        ItemStack item = p.getItemInHand(hand);
        String id = ArcanaContent.id(item);
        if (id == null) return;
        ServerLevel level = (ServerLevel) p.level();
        JsonObject m;
        if ((m = RelicsInit.mechanic(id, "energyblast")) != null && may(p, level, p.blockPosition(), ClaimAction.ACCESS) && ready(p, "energyblast", m)) {
            energyBlast(p, level, m);
        }
        if ((m = RelicsInit.mechanic(id, "thor")) != null) {
            HitResult hit = p.pick(50, 1f, false);
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK && ready(p, "thor", m)) {
                int n = i(m, "lightning_bolts_amount", 1);
                double var = d(m, "random_location_variation", 0);
                BlockPos t = bh.getBlockPos();
                for (int k = 0; k < n; k++) {
                    LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
                    if (bolt == null) continue;
                    // Oraxen spreads the strikes horizontally around the target block (x and z), never into the ground
                    double x = t.getX() + level.getRandom().nextDouble() * var - var / 2;
                    double z = t.getZ() + level.getRandom().nextDouble() * var - var / 2;
                    double y = t.getY();
                    bolt.snapTo(x, y, z);
                    // where the caster may not build the strike is only a flash: no damage, no fire
                    if (!mayAffectAt(p, new Vec3(x, y, z))) bolt.setVisualOnly(true);
                    else bolt.setCause(p);
                    level.addFreshEntity(bolt);
                }
            }
        }
        if ((m = RelicsInit.mechanic(id, "witherskull")) != null && may(p, level, p.blockPosition(), ClaimAction.ACCESS) && ready(p, "witherskull", m)) {
            Vec3 dir = p.getLookAngle().normalize();
            RelicWitherSkull skull = new RelicWitherSkull(level, p, dir, this::mayAffectAt);
            Vec3 at = p.position().add(0, 1, 0).add(dir.scale(2));
            skull.snapTo(at.x, at.y, at.z, p.getYRot(), p.getXRot());
            skull.setDangerous(b(m, "charged", false));
            level.addFreshEntity(skull);
        }
        if ((m = RelicsInit.mechanic(id, "spear_lunge")) != null && !charging.containsKey(p.getUUID())) {
            Long cd = lungeCooldown.get(p.getUUID());
            if (cd == null || Tasks.now() >= cd) {
                lungeCooldown.remove(p.getUUID());
                startCharge(p, level, m, hand, item, id);
            }
        }
    }

    private void energyBlast(ServerPlayer p, ServerLevel level, JsonObject m) {
        JsonObject ps = m.has("particle") ? m.getAsJsonObject("particle") : new JsonObject();
        int rgb = 0xFFFFFF;
        if (ps.has("color")) {
            JsonObject c = ps.getAsJsonObject("color");
            rgb = (i(c, "red", 255) << 16) | (i(c, "green", 255) << 8) | i(c, "blue", 255);
        }
        ParticleOptions particle = particle(s(ps, "type"), rgb, i(ps, "size", 1));
        if (particle == null) particle = particle("dust", rgb, 1);
        double damage = d(m, "damage", 5);
        int length = Math.max(1, i(m, "length", 8));
        Vec3 origin = p.getEyePosition();
        Vec3 step = p.getLookAngle().normalize().scale(0.1);
        Vec3 at = origin.add(step);
        for (int k = 0; k < length * 10; k++) {
            at = at.add(step);
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
        // The shrinking ring that travels along the look direction (Oraxen's playEffect), one ring per tick.
        Vec3 dir = p.getLookAngle().normalize();
        double pitch = Math.toRadians(p.getXRot() + 90.0f);
        double yaw = Math.toRadians(-p.getYRot());
        double[] radius = {2.0};
        int[] beam = {length * 2};
        double shrink = radius[0] / ((beam[0] + 2) / 2.0);
        Vec3[] center = {p.getEyePosition()};
        ParticleOptions part = particle;
        DamageSource src = level.damageSources().indirectMagic(p, p);
        Tasks.Task[] task = new Tasks.Task[1];
        task[0] = Tasks.timer(1, 1, () -> {
            beam[0]--;
            if (beam[0] < 1 || p.isRemoved()) {
                task[0].cancel();
                return;
            }
            for (int a = 0; a < 360; a += 3) {
                double ang = Math.toRadians(a);
                Vec3 v = new Vec3(radius[0] * Math.cos(ang), 0, radius[0] * Math.sin(ang)).xRot((float) -pitch).yRot((float) yaw);
                Vec3 pt = center[0].add(v);
                level.sendParticles(part, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
            }
            radius[0] -= shrink;
            if (radius[0] < 0) {
                Vec3 c = center[0];
                level.sendParticles(part, c.x, c.y, c.z, 200, 0.3, 0.3, 0.3, 0.3);
                for (Entity e : level.getEntities(p, new AABB(c, c).inflate(0.5))) {
                    if (e instanceof LivingEntity le && le.isAlive() && mayAffect(p, le)) le.hurtServer(level, src, (float) (damage * 3));
                }
                task[0].cancel();
            } else {
                center[0] = center[0].add(dir);
                Vec3 c = center[0];
                for (Entity e : level.getEntities(p, new AABB(c, c).inflate(radius[0]))) {
                    if (e instanceof LivingEntity le && le.isAlive() && mayAffect(p, le)) le.hurtServer(level, src, (float) damage);
                }
            }
        });
    }

    // ------------------------------------------------------------------ spear lunge

    private static @Nullable Identifier frame(JsonObject m, int index) {
        if (!m.has("intermediate_models")) return null;
        JsonArray a = m.getAsJsonArray("intermediate_models");
        return index < a.size() ? RelicsInit.model(a.get(index).getAsString()) : null;
    }

    private static @Nullable Identifier activeModel(JsonObject m) {
        String a = s(m, "active_model");
        return a == null ? null : RelicsInit.model(a);
    }

    /** One of this mechanic's charge frames or its charged model (a relic left in one is "stuck"). */
    private static boolean isChargeModel(JsonObject m, @Nullable Identifier model) {
        if (model == null) return false;
        if (model.equals(activeModel(m))) return true;
        if (m.has("intermediate_models")) {
            for (int k = 0; k < m.getAsJsonArray("intermediate_models").size(); k++) if (model.equals(frame(m, k))) return true;
        }
        return false;
    }

    private void startCharge(ServerPlayer p, ServerLevel level, JsonObject m, InteractionHand hand, ItemStack item, String id) {
        Identifier original = item.get(DataComponents.ITEM_MODEL);
        if (isChargeModel(m, original)) original = null; // stuck from an older interrupted charge: back to the base model
        double slowdown = d(m, "charge_slowdown", 0);
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (slowdown > 0 && speed != null) {
            speed.removeModifier(SLOW_ID);
            speed.addTransientModifier(new AttributeModifier(SLOW_ID, -Math.min(1, slowdown), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        Identifier first = i(m, "smooth_frames", 0) > 0 ? frame(m, 0) : activeModel(m);
        if (first != null) item.set(DataComponents.ITEM_MODEL, first);
        JsonObject sounds = m.has("sounds") ? m.getAsJsonObject("sounds") : null;
        if (sounds != null && b(sounds, "enabled", true)) play(level, p.position(), s(sounds, "charge"), 0.8f, 1.2f);
        charging.put(p.getUUID(), new Charge(Tasks.now(), m, hand, id, original, 0, item));
    }

    private void chargeTick() {
        for (Map.Entry<UUID, Charge> e : new ArrayList<>(charging.entrySet())) {
            ServerPlayer p = feather.player(e.getKey());
            Charge c = e.getValue();
            if (p == null) {
                charging.remove(e.getKey());
                continue;
            }
            ItemStack item = p.getItemInHand(c.hand());
            // switched slots, dropped or moved the relic (or another copy is in hand): the charge ends
            if (item != c.stack() || !c.itemId().equals(ArcanaContent.id(item)) || p.isDeadOrDying()) {
                cancelCharge(p, false);
                continue;
            }
            JsonObject m = c.mech();
            long held = Tasks.now() - c.startTick();
            int chargeTicks = Math.max(1, i(m, "charge_ticks", 20));
            ServerLevel level = (ServerLevel) p.level();
            JsonObject parts = m.has("particles") ? m.getAsJsonObject("particles") : null;
            if (parts != null && b(parts, "enabled", true) && held % 3 == 0) {
                ParticleOptions po = particle(s(parts, "charge"), 0xFFFFFF, 1);
                Vec3 at = p.position().add(p.getLookAngle().scale(0.5)).add(0, 1.2, 0);
                if (po != null) level.sendParticles(po, at.x, at.y, at.z, 2, 0.1, 0.1, 0.1, 0.02);
            }
            int frames = i(m, "smooth_frames", 0);
            if (frames > 0 && held < chargeTicks) {
                double progress = (double) held / chargeTicks;
                int target = progress < 0.33 ? 0 : progress < 0.66 ? Math.min(1, frames - 1) : frames;
                if (c.lastFrame() < target) {
                    Identifier model = target < frames ? frame(m, target) : activeModel(m);
                    if (model != null) item.set(DataComponents.ITEM_MODEL, model);
                    charging.put(e.getKey(), c = c.withFrame(target));
                }
            }
            if (held == chargeTicks) {
                Identifier active = activeModel(m);
                if (active != null) item.set(DataComponents.ITEM_MODEL, active);
                charging.put(e.getKey(), c.withFrame(frames + 1));
                JsonObject sounds = m.has("sounds") ? m.getAsJsonObject("sounds") : null;
                if (sounds != null && b(sounds, "enabled", true)) play(level, p.position(), s(sounds, "charge"), 0.5f, 1.8f);
            }
            if (held >= i(m, "max_hold_ticks", 60)) cancelCharge(p, false);
        }
        long now = Tasks.now();
        lunging.values().removeIf(t -> t < now);
        if (lunging.isEmpty()) intentional.clear();
    }

    private void cancelCharge(ServerPlayer p, boolean attack) {
        Charge c = charging.remove(p.getUUID());
        if (c == null) return;
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SLOW_ID);
        ItemStack item = c.stack();
        restoreModel(p, c);
        if (p.getItemInHand(c.hand()) != item || !c.itemId().equals(ArcanaContent.id(item))) return;
        if (attack) {
            JsonObject m = c.mech();
            double pct = Math.min(1.0, (double) (Tasks.now() - c.startTick()) / Math.max(1, i(m, "charge_ticks", 20)));
            if (pct >= d(m, "min_charge_percent", 0.3)) {
                lunge(p, m, pct);
                lungeCooldown.put(p.getUUID(), Tasks.now() + 30);
            }
        }
    }

    /**
     * Back to the relic's own model (setting the prototype value drops the override) on the charged stack, and on any
     * copy of the relic in the inventory still showing a charge frame (a stack split off while charging).
     */
    private static void restoreModel(ServerPlayer p, Charge c) {
        ItemStack item = c.stack();
        if (!item.isEmpty()) {
            Identifier base = item.getPrototype().get(DataComponents.ITEM_MODEL);
            item.set(DataComponents.ITEM_MODEL, c.originalModel() != null ? c.originalModel() : base);
        }
        Inventory inv = p.getInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack it = inv.getItem(s);
            if (it == item || it.isEmpty() || !c.itemId().equals(ArcanaContent.id(it))) continue;
            if (isChargeModel(c.mech(), it.get(DataComponents.ITEM_MODEL))) it.set(DataComponents.ITEM_MODEL, it.getPrototype().get(DataComponents.ITEM_MODEL));
        }
    }

    private void lunge(ServerPlayer p, JsonObject m, double pct) {
        ServerLevel level = (ServerLevel) p.level();
        lunging.put(p.getUUID(), Tasks.now() + 5);
        Vec3 dir = p.getLookAngle();
        double minPct = d(m, "min_charge_percent", 0.3);
        double vel = d(m, "lunge_velocity", 1.0) * (minPct + (1.0 - minPct) * pct);
        Vec3 v = dir.scale(vel);
        p.setDeltaMovement(v.x, Math.max(v.y, 0.15), v.z);
        p.syncVelocity = true;
        JsonObject sounds = m.has("sounds") && b(m.getAsJsonObject("sounds"), "enabled", true) ? m.getAsJsonObject("sounds") : null;
        JsonObject parts = m.has("particles") && b(m.getAsJsonObject("particles"), "enabled", true) ? m.getAsJsonObject("particles") : null;
        if (sounds != null) play(level, p.position(), s(sounds, "lunge"), 1f, 1f);
        if (parts != null) {
            ParticleOptions po = particle(s(parts, "lunge"), 0xFFFFFF, 1);
            if (po != null) level.sendParticles(po, p.getX(), p.getY() + 1, p.getZ(), 8, 0.3, 0.3, 0.3, 0.1);
        }
        double range = d(m, "max_range", 6), r = d(m, "hitbox_radius", 0.6);
        int max = Math.max(1, i(m, "max_targets", 1));
        Vec3 start = p.getEyePosition(), end = start.add(dir.normalize().scale(range));
        List<Map.Entry<LivingEntity, Double>> hits = new ArrayList<>();
        for (Entity e : level.getEntities(p, new AABB(start, end).inflate(r + 1))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || !mayAffect(p, le)) continue;
            Optional<Vec3> clip = le.getBoundingBox().inflate(r).clip(start, end);
            if (clip.isPresent()) hits.add(Map.entry(le, clip.get().distanceToSqr(start)));
            else if (le.getBoundingBox().inflate(r).contains(start)) hits.add(Map.entry(le, 0.0));
        }
        hits.sort(Comparator.comparingDouble(Map.Entry::getValue));
        double dmg = d(m, "min_damage", 1) + (d(m, "damage", 1) - d(m, "min_damage", 1)) * pct;
        for (int k = 0; k < Math.min(max, hits.size()); k++) {
            LivingEntity t = hits.get(k).getKey();
            intentional.add(t.getUUID());
            t.hurtServer(level, level.damageSources().playerAttack(p), (float) dmg);
            Vec3 kb = dir.normalize().scale(d(m, "knockback", 1));
            t.setDeltaMovement(t.getDeltaMovement().add(kb.x, 0, kb.z).add(0, 0.2 - t.getDeltaMovement().y, 0));
            t.syncVelocity = true;
            if (parts != null) {
                ParticleOptions po = particle(s(parts, "hit"), 0xFFFFFF, 1);
                if (po != null) level.sendParticles(po, t.getX(), t.getY() + t.getBbHeight() / 2, t.getZ(), 5, 0.2, 0.2, 0.2, 0.05);
            }
            if (sounds != null) play(level, t.position(), s(sounds, "hit"), 1f, 1f);
        }
    }

    /** While lunging, only the lunge's own hits land (Oraxen's guard against the release click hitting too). */
    private boolean allowDamage(LivingEntity victim, DamageSource source) {
        if (!(source.getEntity() instanceof ServerPlayer p) || source.getDirectEntity() != p) return true;
        if (!lunging.containsKey(p.getUUID())) return true;
        return intentional.remove(victim.getUUID());
    }

    // ------------------------------------------------------------------ melee: lifeleech, knockback strike

    private void onHit(LivingEntity victim, DamageSource source) {
        if (!(source.getEntity() instanceof ServerPlayer p) || source.getDirectEntity() != p || victim == p) return;
        ServerLevel level = (ServerLevel) victim.level();
        if (!mayAffect(p, victim)) return;
        ItemStack item = p.getMainHandItem();
        JsonObject m;
        if ((m = mech(item, "lifeleech")) != null && victim.isAlive()) {
            int amount = i(m, "amount", 1);
            p.setHealth(Math.max(0, Math.min(p.getHealth() + amount, p.getMaxHealth())));
            victim.setHealth(Math.max(0, Math.min(victim.getHealth() - amount, victim.getMaxHealth())));
            // Bukkit's setHealth(0) killed; vanilla's only zeroes health (no death, loot, death events or respawn
            // screen for a player): finish the kill the way a hit would.
            // ALLOW_DEATH first, as a lethal hit would (the Cinderplume's rebirth heals and cancels).
            if (victim.isDeadOrDying() && !victim.isRemoved()
                    && ServerLivingEntityEvents.ALLOW_DEATH.invoker().allowDeath(victim, source, amount)) {
                victim.die(source);
            }
        }
        if ((m = mech(item, "knockback_strike")) != null) {
            String key = ArcanaContent.id(item) + ":" + p.getUUID();
            long now = System.currentTimeMillis();
            int required = i(m, "required_hits", 3);
            long reset = i(m, "reset_time", 60) * 50L;
            long[] data = strikeHits.get(key);
            long count = data == null || now - data[1] > reset ? 1 : data[0] + 1;
            boolean trigger = count >= required;
            if (trigger) strikeHits.remove(key);
            else strikeHits.put(key, new long[]{count, now});
            if (trigger) knockback(p, victim, level, m);
        }
    }

    private void knockback(ServerPlayer attacker, LivingEntity victim, ServerLevel level, JsonObject m) {
        Vec3 dir = new Vec3(victim.getX() - attacker.getX(), 0, victim.getZ() - attacker.getZ());
        if (dir.lengthSqr() == 0) {
            Vec3 look = attacker.getLookAngle();
            dir = new Vec3(look.x, 0, look.z);
            if (dir.lengthSqr() == 0) dir = new Vec3(1, 0, 0);
        }
        dir = dir.normalize().scale(d(m, "knockback_horizontal", 2.0));
        victim.setDeltaMovement(dir.x, d(m, "knockback_vertical", 0.5), dir.z);
        victim.syncVelocity = true;
        JsonObject ps = m.has("particle") ? m.getAsJsonObject("particle") : new JsonObject();
        ParticleOptions po = particle(ps.has("type") ? s(ps, "type") : "crit", 0xFF0000, 1);
        if (po == null) po = particle("crit", 0, 1);
        double spread = d(ps, "spread", 0.5);
        level.sendParticles(po, victim.getX(), victim.getY() + 1, victim.getZ(), i(ps, "count", 20), spread, spread, spread, 0);
        if (b(m, "play_sound", true)) {
            SoundEvent snd = sound(s(m, "sound_type") == null ? "ENTITY_PLAYER_ATTACK_KNOCKBACK" : s(m, "sound_type"));
            if (snd == null) snd = sound("ENTITY_PLAYER_ATTACK_KNOCKBACK");
            if (snd != null) level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), snd, SoundSource.PLAYERS,
                    (float) d(m, "sound_volume", 1), (float) d(m, "sound_pitch", 1));
        }
    }

    // ------------------------------------------------------------------ aura

    private void auraTick() {
        for (ServerPlayer p : feather.onlinePlayers()) {
            if (p.isSpectator()) continue;
            JsonObject m = mech(p.getMainHandItem(), "aura");
            if (m == null) continue;
            ParticleOptions po = particle(s(m, "particle"), 0xFFFFFF, 1);
            if (po != null) ((ServerLevel) p.level()).sendParticles(po, p.getX(), p.getY() + 1, p.getZ(), 2, 0, 0, 0, 1);
        }
    }

    // ------------------------------------------------------------------ glowing drops

    private static @Nullable TeamColor glowColor(ItemStack stack) {
        String id = ArcanaContent.id(stack);
        ArcanaContent.Content c = id == null ? null : ArcanaContent.entry(id);
        JsonElement g = c == null ? null : c.glowing();
        if (g == null) return null;
        TeamColor f = g.isJsonPrimitive() ? TeamColor.byName(g.getAsString().toLowerCase(Locale.ROOT)) : null;
        return f != null ? f : TeamColor.WHITE;
    }

    private void glow(ServerLevel level, ItemEntity item) {
        TeamColor color = glowColor(item.getItem());
        if (color == null) return;
        item.setGlowingTag(true);
        Scoreboard board = level.getServer().getScoreboard();
        String name = "orx_glow_" + Integer.toHexString(color.rgb());
        PlayerTeam team = board.getPlayerTeam(name);
        if (team == null) team = board.addPlayerTeam(name);
        team.setColor(Optional.of(color));
        board.addPlayerToTeam(item.getStringUUID(), team);
        glowing.put(item.getUUID(), name);
    }

    private void unglow(ServerLevel level, ItemEntity item) {
        String name = glowing.remove(item.getUUID());
        if (name == null) return;
        Scoreboard board = level.getServer().getScoreboard();
        PlayerTeam team = board.getPlayerTeam(name);
        if (team != null && team.getPlayers().contains(item.getStringUUID())) {
            board.removePlayerFromTeam(item.getStringUUID(), team);
            if (team.getPlayers().isEmpty()) board.removePlayerTeam(team);
        }
    }

    // ------------------------------------------------------------------ backpack on the back

    /**
     * The backpack shown on a player's back: the worn one; with {@code inventory-cosmetic} and nothing worn, the first
     * backpack_cosmetic item in the inventory (not in the selected hotbar slot), like Oraxen's cosmetic.
     */
    ItemStack shownBackpack(ServerPlayer p) {
        if (p.isSpectator()) return ItemStack.EMPTY;
        ItemStack worn = BackpackWear.worn(p);
        if (!worn.isEmpty() || !inventoryCosmetic) return worn;
        Inventory inv = p.getInventory();
        int held = inv.getSelectedSlot();
        for (int s = 0; s < 36; s++) {
            if (s == held) continue;
            ItemStack it = inv.getItem(s);
            JsonObject m = mech(it, "backpack_cosmetic");
            if (m != null) return it;
        }
        return ItemStack.EMPTY;
    }

    private void backpackTick() {
        for (ServerPlayer p : feather.onlinePlayers()) {
            // Wearing turned off: what is still worn goes back into the inventory (or drops at the feet when full).
            if (!BackpackWear.serverWear && !BackpackWear.worn(p).isEmpty() && BackpackWear.free(p)) {
                ItemStack w = BackpackWear.take(p);
                p.getInventory().placeItemBackInInventory(w, net.minecraft.util.Prediction.SERVER_ONLY);
                p.sendSystemMessage(net.minecraft.network.chat.Component.translatable("aftrhrs.backpack.returned"));
            }
            syncBackpack(p, false);
        }
    }

    /** The back changed (equip, unequip, death, respawn, other dimension): sync the render now, not at the next tick. */
    void backpackChanged(ServerPlayer p) {
        syncBackpack(p, true);
    }

    /** Sends who wears what to the player and everyone tracking them, when it changed (or {@code force}). */
    private void syncBackpack(ServerPlayer p, boolean force) {
        ItemStack show = BackpackWear.shown(shownBackpack(p)); // one item, the contents stay private
        ItemStack before = backpacks.get(p.getUUID());
        // compared without the contents (as sent), or a filled backpack is resent to every viewer twice a second
        boolean same = before == null ? show.isEmpty() : (show.isEmpty() ? before.isEmpty() : ItemStack.isSameItemSameComponents(before, show));
        if (same && !force) return;
        backpacks.put(p.getUUID(), show);
        RelicsInit.BackpackPayload payload = new RelicsInit.BackpackPayload(p.getId(), show);
        if (ServerPlayNetworking.canSend(p, RelicsInit.BackpackPayload.TYPE)) ServerPlayNetworking.send(p, payload);
        for (ServerPlayer viewer : PlayerLookup.tracking(p)) {
            if (viewer != p && ServerPlayNetworking.canSend(viewer, RelicsInit.BackpackPayload.TYPE)) ServerPlayNetworking.send(viewer, payload);
        }
    }

    /** A client just listed the backpack channels: its settings, its own back and those of the players it sees. */
    private void channelsOpened(ServerPlayer p) {
        sendBackSlot(p);
        if (!ServerPlayNetworking.canSend(p, RelicsInit.BackpackPayload.TYPE)) return;
        syncBackpack(p, true);
        for (ServerPlayer other : feather.onlinePlayers()) {
            if (other == p) continue;
            ItemStack bp = backpacks.get(other.getUUID());
            if (bp != null && !bp.isEmpty() && PlayerLookup.tracking(other).contains(p)) {
                ServerPlayNetworking.send(p, new RelicsInit.BackpackPayload(other.getId(), bp));
            }
        }
    }

    /** Tells a client whether the back slot is on here and how a worn backpack meets an elytra. */
    void sendBackSlot(ServerPlayer p) {
        if (ServerPlayNetworking.canSend(p, RelicsInit.BackSlotPayload.TYPE)) {
            ServerPlayNetworking.send(p, new RelicsInit.BackSlotPayload(BackpackWear.serverWear, hideWithElytra));
        }
    }
}
