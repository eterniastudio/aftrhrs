package net.axther.aftrhrs.feature.mail;

import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;

/**
 * Mailbox blocks: staff mark a vanilla block (a barrel, a decorated pot ...) with {@code /mail mailbox create} while
 * looking at it. Right-clicking one opens your gifts ({@link MailGifts#openScreen}). Like the bank chests, a mailbox is
 * indestructible and inert: no breaking (creative too), explosions, pistons, fire, hoppers or projectiles change it
 * ({@link FeatherEvents#BLOCK_PROTECTED}), and its own inventory never opens. Players with gifts waiting see a soft
 * sparkle (and now and then hear a chime) at it.
 *
 * <p>Mailboxes are positions in {@code data/mail.yml} (world name + block coordinates), not tagged blocks or entities,
 * so a world migrated from Paper needs no conversion: the same blocks keep working.
 */
final class Mailboxes {
    private final MailModule mail;
    private SimpleParticleType particle;
    private boolean chime;

    Mailboxes(MailModule mail) {
        this.mail = mail;
    }

    void reload(Yaml c) {
        String name = c.getString("mail.particle", "END_ROD");
        particle = null;
        if (name != null && !name.isBlank() && !name.equalsIgnoreCase("none")) {
            // Bukkit's Particle names (END_ROD) are the vanilla ids in upper case.
            String raw = name.trim().toLowerCase(Locale.ROOT);
            Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
            ParticleType<?> type = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
            if (type instanceof SimpleParticleType simple) particle = simple;
            else if (type != null) mail.plugin.getLogger().warn("mail.particle: {} needs extra data; pick a simple particle such as END_ROD.", name);
            else mail.plugin.getLogger().warn("mail.particle: unknown particle {}.", name);
        }
        chime = c.getBoolean("mail.chime", true);
    }

    boolean any() {
        return !mail.store.mailboxes.isEmpty();
    }

    boolean isMailbox(Level level, BlockPos pos) {
        // Empty store first: with no mailbox anywhere this is one field read.
        return !mail.store.mailboxes.isEmpty() && level != null && pos != null && mail.store.mailboxes.contains(MailStore.Pos.of(level, pos));
    }

    /** Whether the player stands within reach of a mailbox. */
    boolean near(ServerPlayer p) {
        double max = mail.accessDistance + 1.5;
        String world = Worlds.name(p.level());
        for (MailStore.Pos pos : mail.store.mailboxes) {
            if (!pos.world().equals(world)) continue;
            double dx = pos.x() + 0.5 - p.getX(), dy = pos.y() + 0.5 - p.getY(), dz = pos.z() + 0.5 - p.getZ();
            if (dx * dx + dy * dy + dz * dz <= max * max) return true;
        }
        return false;
    }

    private MailStore.Pos nearest(ServerPlayer p, double maxDistance) {
        MailStore.Pos best = null;
        double bestD = maxDistance * maxDistance;
        String world = Worlds.name(p.level());
        for (MailStore.Pos pos : mail.store.mailboxes) {
            if (!pos.world().equals(world)) continue;
            double dx = pos.x() + 0.5 - p.getX(), dy = pos.y() + 0.5 - p.getY(), dz = pos.z() + 0.5 - p.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    /** The sparkle, shown only to players who have gifts waiting (called every 2 seconds). */
    void sparkle(long tick) {
        if (mail.store.mailboxes.isEmpty() || (particle == null && !chime)) return;
        for (MailStore.Pos pos : mail.store.mailboxes) {
            ServerLevel w = pos.level();
            if (w == null || !w.hasChunk(pos.x() >> 4, pos.z() >> 4)) continue;
            double x = pos.x() + 0.5, y = pos.y() + 1.05, z = pos.z() + 0.5;
            for (ServerPlayer p : w.players()) {
                double d2 = p.distanceToSqr(x, y, z);
                if (d2 > 24 * 24 || mail.waitingGifts(p.getUUID()) == 0) continue;
                if (particle != null) w.sendParticles(p, particle, false, false, x, y, z, 3, 0.25, 0.15, 0.25, 0.005);
                if (chime && tick % 4 == 0 && d2 < 10 * 10) sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, x, y, z, 0.35f, 1.6f);
            }
        }
    }

    private static void sound(ServerPlayer p, SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
        p.connection.send(new ClientboundSoundPacket(holder, SoundSource.BLOCKS, x, y, z, volume, pitch, p.getRandom().nextLong()));
    }

    // ------------------------------------------------------------------ /mail mailbox create|remove|list

    void command(Sender sender, String[] args) {
        Text text = mail.text();
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            text.send(sender, "mail.admin.list-header", Text.unparsed("count", mail.store.mailboxes.size()));
            for (MailStore.Pos pos : mail.store.mailboxes) text.send(sender, "mail.admin.list-line", Text.unparsed("where", pos.pretty()));
            return;
        }
        if (!sub.equals("create") && !sub.equals("remove")) {
            text.send(sender, "mail.admin.usage");
            return;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return;
        }
        if (mail.closed(p)) return;
        ServerLevel level = (ServerLevel) p.level();
        HitResult hit = p.pick(6, 1.0f, false);
        BlockPos b = hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;
        if (sub.equals("remove")) {
            MailStore.Pos pos = b != null && isMailbox(level, b) ? MailStore.Pos.of(level, b) : nearest(p, 3);
            if (pos == null) {
                mail.error(p, "mail.admin.not-a-mailbox");
                return;
            }
            mail.store.mailboxes.remove(pos);
            if (!mail.store.save()) {
                mail.store.mailboxes.add(pos);
                mail.error(p, "mail.save-failed");
                return;
            }
            text.send(p, "mail.admin.removed", Text.unparsed("where", pos.pretty()));
            return;
        }
        BlockState state = b == null ? null : level.getBlockState(b);
        if (state == null || state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            mail.error(p, "mail.admin.look-at-block");
            return;
        }
        if (isMailbox(level, b)) {
            mail.error(p, "mail.admin.already");
            return;
        }
        if (mail.plugin.isBankBlock(level, b)) {
            mail.error(p, "mail.admin.bank-block");
            return;
        }
        MailStore.Pos pos = MailStore.Pos.of(level, b);
        mail.store.mailboxes.add(pos);
        if (!mail.store.save()) {
            mail.store.mailboxes.remove(pos);
            mail.error(p, "mail.save-failed");
            return;
        }
        text.send(p, "mail.admin.created", Text.unparsed("where", pos.pretty()),
                Text.unparsed("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()));
        text.sound(p, "accepted");
    }

    // ------------------------------------------------------------------ the block

    private static Mailboxes active() {
        MailModule m = MailModule.active;
        return m == null ? null : m.mailboxes;
    }

    /** Hooks the mailbox block's protection (once; quiet while the module is off). */
    static void registerEvents() {
        // Right-click opens your gifts; nothing else happens to the block (no opening, no item use on it).
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            Mailboxes mb = active();
            if (mb == null || !(player instanceof ServerPlayer p) || !mb.isMailbox(level, hit.getBlockPos())) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
            if (mb.mail.plugin.authPending(p)) return InteractionResult.FAIL;
            BlockPos pos = hit.getBlockPos();
            sound(p, SoundEvents.BARREL_OPEN, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.5f, 1.3f);
            mb.mail.gifts.openScreen(p, 0);
            return InteractionResult.SUCCESS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            Mailboxes mb = active();
            if (mb == null || !mb.isMailbox(level, pos)) return true;
            if (player instanceof ServerPlayer p && Perms.has(p, MailModule.ADMIN)) mb.mail.text().send(p, "mail.admin.cant-break");
            return false;
        });
        FeatherEvents.BLOCK_PROTECTED.register((level, pos) -> {
            Mailboxes mb = active();
            return mb != null && mb.isMailbox(level, pos);
        });
        // Backstop: the block's own inventory never opens (spectators, other mods).
        FeatherEvents.CONTAINER_OPEN.register((p, provider) -> {
            Mailboxes mb = active();
            return mb == null || !(provider instanceof BlockEntity be) || !mb.isMailbox(be.getLevel(), be.getBlockPos());
        });
        // Pressure-plate style mailboxes aren't triggered.
        FeatherEvents.PHYSICAL_INTERACT.register((entity, level, pos, state) -> {
            Mailboxes mb = active();
            return mb == null || !mb.isMailbox(level, pos);
        });
        // Nothing is placed into the mailbox's spot, and no chest joins a chest mailbox.
        FeatherEvents.BLOCK_PLACE.register((p, level, pos, state, stack) -> {
            Mailboxes mb = active();
            if (mb == null || mb.mail.store.mailboxes.isEmpty()) return true;
            if (mb.isMailbox(level, pos)) return false;
            if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) return true;
            for (Direction f : Direction.Plane.HORIZONTAL) {
                BlockPos n = pos.relative(f);
                if (level.getBlockState(n).is(state.getBlock()) && mb.isMailbox(level, n)) return false;
            }
            return true;
        });
    }
}
