package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.CreatureSfx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The pet whistle ({@code tools.yml pet-whistle}, item {@code aftrhrs:arcana_pet_whistle}): every owned creature in the
 * player's world hears it (a staying one stands up and follows, one more than 5 blocks away comes to the owner, a note
 * and its greet; the old {@code ARCANAWHISTLE} signal, now done directly), and following pets in other worlds or
 * unloaded chunks are fetched by the pets module.
 */
final class PetWhistle {
    private final PetsModule m;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private boolean enabled;
    private String itemId, sound;
    private long cooldownMs;
    private String msgCalled, msgNone, msgCooldown;

    PetWhistle(PetsModule module) {
        this.m = module;
    }

    void reload() {
        Yaml c;
        try {
            c = m.feather().loadResourceConfig("tools.yml");
        } catch (RuntimeException e) {
            c = new Yaml();
        }
        enabled = c.getBoolean("pet-whistle.enabled", true);
        itemId = c.getString("pet-whistle.item", "arcana_pet_whistle").toLowerCase(Locale.ROOT);
        sound = c.getString("pet-whistle.sound", "item.goat_horn.sound.0");
        cooldownMs = Math.max(0L, Math.round(c.getDouble("pet-whistle.cooldown-seconds", 5.0) * 1000));
        msgCalled = c.getString("pet-whistle.message-called", "<prefix><good>You whistle.</good> <text><count></text> <muted>pet(s) heard you.");
        msgNone = c.getString("pet-whistle.message-none", "<prefix><muted>No pet heard the whistle. Pets told to stay elsewhere keep waiting.");
        msgCooldown = c.getString("pet-whistle.message-cooldown", "<muted>Catch your breath (<seconds>s).");
    }

    /** A right-click with the whistle (air or a block that didn't take the click). True when it was the whistle. */
    boolean use(ServerPlayer player, InteractionHand hand) {
        if (!enabled || !itemId.equals(PlushItems.id(player.getItemInHand(hand)))) return false;
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUUID());
        if (last != null && now - last < cooldownMs) {
            if (now - last > 250) {   // the off-hand of the same click stays silent
                long left = (cooldownMs - (now - last) + 999) / 1000;
                Adv.actionBar(player, m.text().parse(msgCooldown, Text.unparsed("seconds", left)));
            }
            return true;
        }
        lastUse.put(player.getUUID(), now);
        if (lastUse.size() > 2048) lastUse.values().removeIf(t -> now - t > cooldownMs);
        if (sound != null && !sound.isBlank()) Adv.sound(player, sound, 1.0f, 1.2f);
        int count = blow(player);
        Adv.send(player, m.text().parse(count > 0 ? msgCalled : msgNone, Text.unparsed("count", count)));
        return true;
    }

    /** Signals the player's creatures in their world and fetches the following ones elsewhere. How many heard it. */
    int blow(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        UUID owner = player.getUUID();
        List<? extends ArcanaCreature> near = level.getEntities(EntityTypeTest.forClass(ArcanaCreature.class),
                c -> c.isAlive() && c.isTame() && c.getOwnerReference() != null && owner.equals(c.getOwnerReference().getUUID()));
        int count = 0;
        for (ArcanaCreature c : near) {
            count++;
            if (!c.scheduler().ready("whistle", 40)) continue;
            if (c.staying()) c.setStaying(false, player);
            if (c.distanceToSqr(player) > 25 && !c.isPassenger() && !c.isVehicle()) {
                Loc to = PetUtil.near(player);
                c.teleportTo(level, to.x(), to.y(), to.z(), Set.<Relative>of(), to.yaw(), c.getXRot(), false);
                c.getNavigation().stop();
            }
            PetUtil.particles(c, ParticleTypes.NOTE, 1.2, 3, 0.3, 0.3, 0);
            c.playRole("greet");
            CreatureSfx.arrive(c, true);
        }
        return count + m.whistleFetch(player);
    }
}
