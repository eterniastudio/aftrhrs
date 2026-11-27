package net.axther.aftrhrs.feature.staff;

import com.mojang.brigadier.StringReader;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.chat.ChatModule;
import net.axther.aftrhrs.feature.moderation.ModerationModule;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Staff shortcuts: /gmc /gms /gma /gmsp, /god, /heal, /feed, /smite, /spawnmob, /i and /itemname, /fly, /speed,
 * /vanish, /invsee, /enderchest, /top, /tphere, /broadcast, /craft. Behavior follows EssentialsX's versions (heal also
 * clears fire, air and effects; smite strikes where you look or at a player; god mode blocks damage and hunger).
 *
 * <p>Vanish (provides {@link Hooks.Vanish}): a vanished player is taken off the tab list and never sent as an entity
 * to anyone without {@code feather.vanish.see} ({@link FeatherEvents#ENTITY_VISIBLE}), Bukkit's hidePlayer.
 */
public final class StaffModule implements FeatherModule, FeatherCommand {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder().character('&').hexColors().build();

    private static volatile StaffModule active;

    private final Feather plugin;
    private List<String> itemNames = List.of();
    private List<String> mobNames = List.of();
    private int maxMobs;
    private int maxItems;
    private boolean healClearsEffects;
    private int smiteDamage;
    private boolean godMobsIgnore;
    private Tasks.Task godTask;
    /** viewer -> players whose tab entry this viewer's client doesn't have (vanish). */
    private final Map<UUID, Set<UUID>> hiddenFrom = new HashMap<>();

    public StaffModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Staff commands";
    }

    @Override
    public void enable() {
        List<String> items = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (BuiltInRegistries.ITEM.getValue(id) == net.minecraft.world.item.Items.AIR) continue;
            items.add(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
        }
        items.sort(null);
        itemNames = items;
        List<String> mobs = new ArrayList<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            if (t == net.minecraft.world.entity.EntityTypes.PLAYER || !t.canSummon() || !DefaultAttributes.hasSupplier(t)) continue;
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
            mobs.add(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
        }
        mobs.sort(null);
        mobNames = mobs;
        reload();
        active = this;
        for (String cmd : List.of("gmc", "gms", "gma", "gmsp", "god", "heal", "feed", "smite", "spawnmob", "i", "itemname",
                "fly", "speed", "vanish", "invsee", "enderchest", "top", "tphere", "broadcast", "craft")) {
            plugin.bind(cmd, this);
        }
        plugin.provide(Hooks.Vanish.class, this::canSee);
        plugin.once("staff.events", () -> {
            FeatherEvents.ENTITY_VISIBLE.register((entity, viewer) -> {
                StaffModule m = active;
                return m == null || !(entity instanceof ServerPlayer target) || m.canSee(viewer, target);
            });
            ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
                StaffModule m = active;
                if (m != null) m.applyVanish(h.player);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                StaffModule m = active;
                if (m == null) return;
                m.hiddenFrom.remove(h.player.getUUID());
                for (Set<UUID> s : m.hiddenFrom.values()) s.remove(h.player.getUUID());
            });
            ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
                StaffModule m = active;
                if (m == null || !(entity instanceof ServerPlayer p) || !m.isGod(p) || source.is(DamageTypes.GENERIC_KILL)) return true;
                p.setRemainingFireTicks(0);
                return false;
            });
            FeatherEvents.MOB_TARGET.register((mob, target) -> {
                StaffModule m = active;
                return m == null || !m.godMobsIgnore || !(target instanceof ServerPlayer p) || !m.isGod(p);
            });
        });
        // God mode keeps hunger away.
        godTask = Tasks.timer(20L, 20L, () -> {
            for (ServerPlayer p : plugin.onlinePlayers()) {
                if (isGod(p) && p.getFoodData().getFoodLevel() < 20) p.getFoodData().setFoodLevel(20);
            }
        });
        for (ServerPlayer p : plugin.onlinePlayers()) applyVanish(p);
    }

    @Override
    public void reload() {
        maxMobs = Math.max(1, plugin.getConfig().getInt("staff.spawnmob-max", 50));
        maxItems = Math.max(1, plugin.getConfig().getInt("staff.item-max", 2304));
        healClearsEffects = plugin.getConfig().getBoolean("staff.heal-clears-effects", true);
        smiteDamage = Math.max(0, plugin.getConfig().getInt("staff.smite-damage", 5));
        godMobsIgnore = plugin.getConfig().getBoolean("staff.god-mobs-ignore", false);
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (godTask != null) godTask.cancel();
        hiddenFrom.clear();
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        String name = CommandBridge.nameOf(label);
        switch (name) {
            case "gmc" -> gamemode(sender, args, GameType.CREATIVE);
            case "gms" -> gamemode(sender, args, GameType.SURVIVAL);
            case "gma" -> gamemode(sender, args, GameType.ADVENTURE);
            case "gmsp" -> gamemode(sender, args, GameType.SPECTATOR);
            case "god" -> god(sender, args);
            case "heal" -> heal(sender, args);
            case "feed" -> feed(sender, args);
            case "smite" -> smite(sender, args);
            case "spawnmob" -> spawnmob(sender, args);
            case "i" -> item(sender, args);
            case "itemname" -> itemname(sender, args);
            case "fly" -> fly(sender, args);
            case "speed" -> speed(sender, args);
            case "vanish" -> vanish(sender, args);
            case "invsee", "enderchest" -> invsee(sender, args, name.equals("enderchest"));
            case "top" -> top(sender);
            case "tphere" -> tphere(sender, args);
            case "broadcast" -> {
                if (args.length == 0) plugin.text().send(sender, "staff.broadcast-usage");
                else Feather.server().getPlayerList().broadcastSystemMessage(Adv.vanilla(plugin.text().msg("staff.broadcast",
                        Text.component("message", AMPERSAND.deserialize(String.join(" ", args))))), false);
            }
            case "craft" -> {
                ServerPlayer p = sender.player();
                if (p != null) {
                    ContainerLevelAccess at = ContainerLevelAccess.create(p.level(), p.blockPosition());
                    p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new CraftingMenu(id, inv, at) {
                        @Override
                        public boolean stillValid(Player player) {
                            return player.isAlive();
                        }
                    }, net.minecraft.network.chat.Component.translatable("container.crafting")));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Resolves "[player]": the named player (needs {@code <perm>.others}) or the sender. Null = already reported. */
    private ServerPlayer target(Sender sender, String[] args, int index, String perm) {
        Text text = plugin.text();
        if (args.length > index) {
            if (!sender.hasPermission(perm + ".others")) {
                text.send(sender, "general.no-permission");
                return null;
            }
            ServerPlayer p = plugin.findPlayer(sender, args[index]);
            if (p == null) plugin.notFound(sender, args[index]);
            return p;
        }
        if (sender.isPlayer()) return sender.player();
        text.send(sender, "staff.need-player");
        return null;
    }

    /** Tells the sender (when it's someone else) and the target. */
    private void report(Sender sender, ServerPlayer target, String selfKey, String otherKey, TagResolver... extra) {
        Text text = plugin.text();
        text.send(target, selfKey, extra);
        if (sender.player() != target) {
            TagResolver[] all = Arrays.copyOf(extra, extra.length + 1);
            all[extra.length] = Text.component("target", Names.display(target));
            text.send(sender, otherKey, all);
        }
    }

    // ------------------------------------------------------------------ gamemode

    private void gamemode(Sender sender, String[] args, GameType mode) {
        ServerPlayer target = target(sender, args, 0, "feather.gamemode");
        if (target == null) return;
        target.setGameMode(mode);
        String modeName = switch (mode) {
            case CREATIVE -> "Creative";
            case SURVIVAL -> "Survival";
            case ADVENTURE -> "Adventure";
            case SPECTATOR -> "Spectator";
        };
        report(sender, target, "staff.gamemode", "staff.gamemode-other", Text.unparsed("mode", modeName));
        plugin.text().sound(target, "click");
    }

    // ------------------------------------------------------------------ god / heal / feed

    private void god(Sender sender, String[] args) {
        ServerPlayer target = target(sender, args, 0, "feather.god");
        if (target == null) return;
        Profiles.Profile profile = plugin.profiles().get(target);
        profile.god = !profile.god;
        plugin.profiles().save(profile);
        if (profile.god) {
            target.setHealth(target.getMaxHealth());
            target.getFoodData().setFoodLevel(20);
        }
        report(sender, target, profile.god ? "staff.god-on" : "staff.god-off", profile.god ? "staff.god-on-other" : "staff.god-off-other");
        plugin.text().sound(target, profile.god ? "accepted" : "cancel");
    }

    private void heal(Sender sender, String[] args) {
        ServerPlayer target = target(sender, args, 0, "feather.heal");
        if (target == null) return;
        if (target.isDeadOrDying()) {
            plugin.text().send(sender, "staff.dead");
            return;
        }
        target.setHealth(target.getMaxHealth());
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(20f);
        target.setRemainingFireTicks(0);
        target.setTicksFrozen(0);
        target.setAirSupply(target.getMaxAirSupply());
        if (healClearsEffects) target.removeAllEffects();
        target.level().sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + 2.1, target.getZ(), 6, 0.4, 0.2, 0.4, 0);
        report(sender, target, "staff.heal", "staff.heal-other");
        plugin.text().sound(target, "healed");
    }

    private void feed(Sender sender, String[] args) {
        ServerPlayer target = target(sender, args, 0, "feather.feed");
        if (target == null) return;
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(20f);
        target.getFoodData().exhaustionLevel = 0f;
        report(sender, target, "staff.feed", "staff.feed-other");
        plugin.text().sound(target, "fed");
    }

    private boolean isGod(ServerPlayer p) {
        return plugin.profiles().get(p).god;
    }

    // ------------------------------------------------------------------ smite / spawnmob

    private static void lightning(ServerLevel level, Vec3 at, boolean visualOnly) {
        LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND);
        if (bolt == null) return;
        bolt.snapTo(at);
        bolt.setVisualOnly(visualOnly);
        level.addFreshEntity(bolt);
    }

    private void smite(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length > 0) {
            if (!sender.hasPermission("feather.smite.others")) {
                text.send(sender, "general.no-permission");
                return;
            }
            ServerPlayer target = plugin.findPlayer(sender, args[0]);
            if (target == null) {
                plugin.notFound(sender, args[0]);
                return;
            }
            ServerLevel level = target.level();
            lightning(level, target.position(), true);
            if (!isGod(target) && smiteDamage > 0) target.hurtServer(level, level.damageSources().lightningBolt(), smiteDamage);
            text.send(sender, "staff.smite-other", Text.component("target", Names.display(target)));
            text.send(target, "staff.smitten");
            return;
        }
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "staff.need-player");
            return;
        }
        HitResult hit = player.pick(256, 1f, false);
        Vec3 at = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                ? Vec3.atBottomCenterOf(b.getBlockPos().above())
                : player.getEyePosition().add(player.getLookAngle().scale(32));
        lightning(player.level(), at, false);
    }

    private void spawnmob(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "staff.spawnmob-usage");
            return;
        }
        Identifier id = Identifier.tryParse(args[0].toLowerCase(Locale.ROOT));
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null || !type.canSummon() || type == net.minecraft.world.entity.EntityTypes.PLAYER) {
            text.send(sender, "staff.spawnmob-unknown", Text.unparsed("input", args[0]));
            return;
        }
        int amount = 1;
        if (args.length > 1) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                text.send(sender, "staff.bad-number", Text.unparsed("input", args[1]));
                return;
            }
        }
        if (amount < 1) amount = 1;
        if (amount > maxMobs && !sender.hasPermission("feather.spawnmob.unlimited")) amount = maxMobs;
        ServerLevel level;
        BlockPos at;
        if (args.length > 2) {
            ServerPlayer target = target(sender, args, 2, "feather.spawnmob");
            if (target == null) return;
            level = target.level();
            at = target.blockPosition();
        } else if (sender.isPlayer()) {
            ServerPlayer player = sender.player();
            level = player.level();
            HitResult hit = player.pick(64, 1f, false);
            at = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos().above() : player.blockPosition();
        } else {
            text.send(sender, "staff.need-player");
            return;
        }
        int spawned = 0;
        for (int n = 0; n < amount; n++) {
            if (type.spawn(level, at, EntitySpawnReason.COMMAND) != null) spawned++;
        }
        text.send(sender, "staff.spawnmob", Text.unparsed("count", spawned), Text.unparsed("mob", pretty(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath())));
    }

    // ------------------------------------------------------------------ /i and /itemname

    private void item(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "staff.item-usage");
            return;
        }
        ItemStack stack = parseItem(args[0]);
        if (stack == null) {
            text.send(sender, "staff.item-unknown", Text.unparsed("input", args[0]));
            return;
        }
        int amount = stack.getMaxStackSize();
        if (args.length > 1) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                text.send(sender, "staff.bad-number", Text.unparsed("input", args[1]));
                return;
            }
        }
        amount = Math.max(1, Math.min(amount, maxItems));
        ServerPlayer target = target(sender, args, 2, "feather.item");
        if (target == null) return;
        int left = amount;
        while (left > 0) {
            ItemStack give = stack.copyWithCount(Math.min(left, stack.getMaxStackSize()));
            left -= give.getCount();
            if (!target.getInventory().add(give) && !give.isEmpty()) target.drop(give, false, Prediction.SERVER_ONLY);
        }
        target.containerMenu.broadcastChanges();
        report(sender, target, "staff.item", "staff.item-other", Text.unparsed("count", amount),
                Text.component("item", Adv.adventure(stack.getStyledHoverName())));
        plugin.text().sound(target, "item-pickup");
    }

    /** Item ids ("diamond", "minecraft:diamond_sword[enchantments={sharpness:5}]"); "oraxen:<id>" means the native aftrhrs item. */
    private ItemStack parseItem(String input) {
        String in = input.toLowerCase(Locale.ROOT).startsWith("oraxen:") ? "aftrhrs:" + input.substring(7) : input;
        try {
            ItemStack s = new ItemParser(Feather.server().registryAccess()).parse(new StringReader(in)).createItemStack(1);
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private void itemname(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "general.players-only");
            return;
        }
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            text.send(player, "staff.itemname-empty");
            return;
        }
        if (args.length == 0 || (args.length == 1 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("reset")))) {
            hand.remove(DataComponents.CUSTOM_NAME);
            text.send(player, "staff.itemname-reset");
            text.sound(player, "click");
            return;
        }
        String raw = String.join(" ", args);
        Component name;
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            name = AMPERSAND.deserialize(raw.replace('§', '&'));
        } else {
            // Colors, gradients and decorations only: no click/hover/insertion tags in item names.
            TagResolver tags = TagResolver.builder().resolvers(StandardTags.color(), StandardTags.decorations(),
                    StandardTags.gradient(), StandardTags.rainbow(), StandardTags.transition(), StandardTags.reset()).build();
            name = MiniMessage.builder().tags(tags).build().deserialize(raw);
        }
        if (Adv.plain(name).length() > 64) {
            text.send(player, "staff.itemname-long");
            return;
        }
        name = name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        hand.set(DataComponents.CUSTOM_NAME, Adv.vanilla(name));
        text.send(player, "staff.itemname", Text.component("name", name));
        text.sound(player, "nick-set");
    }

    // ------------------------------------------------------------------ fly / speed / vanish / invsee / top / tphere

    private void fly(Sender sender, String[] args) {
        ServerPlayer target = target(sender, args, 0, "feather.fly");
        if (target == null) return;
        boolean on = !target.getAbilities().mayfly;
        target.getAbilities().mayfly = on;
        if (!on) target.getAbilities().flying = false;
        target.onUpdateAbilities();
        report(sender, target, on ? "staff.fly-on" : "staff.fly-off", on ? "staff.fly-on-other" : "staff.fly-off-other");
        plugin.text().sound(target, "click");
    }

    private void speed(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "staff.speed-usage");
            return;
        }
        float value;
        try {
            value = Float.parseFloat(args[0]);
            if (!Float.isFinite(value)) throw new NumberFormatException(); // NaN slipped through the clamp
        } catch (NumberFormatException e) {
            text.send(sender, "staff.bad-number", Text.unparsed("input", args[0]));
            return;
        }
        value = Math.max(0f, Math.min(10f, value));
        ServerPlayer target = target(sender, args, 1, "feather.speed");
        if (target == null) return;
        boolean flying = target.getAbilities().flying;
        // Bukkit's scale: 1 = vanilla (walk 0.2, fly 0.1), 10 = the maximum (1.0); vanilla's own values are half that.
        float base = flying ? 0.1f : 0.2f;
        float speed = value <= 1 ? base * value : base + (value - 1) / 9f * (1f - base);
        if (flying) {
            target.getAbilities().setFlyingSpeed(speed / 2f);
        } else {
            target.getAbilities().setWalkingSpeed(speed / 2f);
            var attr = target.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null) attr.setBaseValue(speed / 2f);
        }
        target.onUpdateAbilities();
        report(sender, target, flying ? "staff.speed-fly" : "staff.speed-walk",
                flying ? "staff.speed-fly-other" : "staff.speed-walk-other",
                Text.unparsed("speed", value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value)));
    }

    private void vanish(Sender sender, String[] args) {
        ServerPlayer target = target(sender, args, 0, "feather.vanish");
        if (target == null) return;
        Profiles.Profile profile = plugin.profiles().get(target);
        profile.vanished = !profile.vanished;
        plugin.profiles().save(profile);
        applyVanish(target);
        if (plugin.getConfig().getBoolean("staff.vanish-fake-messages", true)) {
            Component msg = plugin.text().msg(profile.vanished ? "chat.quit" : "chat.join", ChatModule.tags(plugin, target));
            for (ServerPlayer p : plugin.onlinePlayers()) if (!Perms.has(p, "feather.vanish.see")) Adv.send(p, msg);
        }
        report(sender, target, profile.vanished ? "staff.vanish-on" : "staff.vanish-off",
                profile.vanished ? "staff.vanish-on-other" : "staff.vanish-off-other");
        plugin.text().sound(target, "click");
    }

    /** Whether {@code viewer} may see {@code target} (vanish). */
    private boolean canSee(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null || viewer == target) return true;
        return !plugin.profiles().get(target).vanished || Perms.has(viewer, "feather.vanish.see");
    }

    /** Hides or shows {@code who} for everyone without feather.vanish.see, and hides vanished players from {@code who}. */
    public void applyVanish(ServerPlayer who) {
        for (ServerPlayer other : plugin.onlinePlayers()) {
            if (other == who) continue;
            setShown(other, who, canSee(other, who));
            setShown(who, other, canSee(who, other));
        }
        plugin.playerNamesChanged(who); // @ completions
    }

    /** Puts {@code target} on (or takes them off) {@code viewer}'s tab list and world. */
    private void setShown(ServerPlayer viewer, ServerPlayer target, boolean shown) {
        Set<UUID> hidden = hiddenFrom.computeIfAbsent(viewer.getUUID(), k -> new HashSet<>());
        boolean wasHidden = hidden.contains(target.getUUID());
        if (shown) {
            if (wasHidden) {
                hidden.remove(target.getUUID());
                viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
            }
            FeatherEvents.refreshVisibility(target);
        } else {
            FeatherEvents.refreshVisibility(target);
            if (!wasHidden) {
                hidden.add(target.getUUID());
                viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
            }
        }
    }

    private void invsee(Sender sender, String[] args, boolean ender) {
        Text text = plugin.text();
        ServerPlayer viewer = sender.player();
        if (viewer == null) {
            text.send(sender, "general.players-only");
            return;
        }
        if (args.length == 0) {
            if (ender) openEnder(viewer, viewer);
            else text.send(viewer, "staff.invsee-usage");
            return;
        }
        if (ender && !Perms.has(viewer, "feather.enderchest.others")) {
            text.send(viewer, "general.no-permission");
            return;
        }
        ServerPlayer target = plugin.findPlayer(viewer, args[0]);
        if (target == null) {
            // Offline: the last snapshot, read-only (moderation module, feather.invsee.offline).
            ModerationModule mod = plugin.module(ModerationModule.class);
            OfflinePlayer off = plugin.findOfflinePlayer(viewer, args[0]);
            if (mod != null && off != null && off.hasPlayedBefore() && !off.isOnline()) {
                if (!Perms.has(viewer, "feather.invsee.offline")) {
                    text.send(viewer, "general.no-permission");
                    return;
                }
                mod.inspector().open(viewer, off, off.name() == null ? args[0] : off.name(), ender);
                return;
            }
            plugin.notFound(viewer, args[0]);
            return;
        }
        if (ender) openEnder(viewer, target);
        else viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x5, id, inv, new LiveInventory(target), 5),
                Adv.vanilla(Names.display(target))));
    }

    private static void openEnder(ServerPlayer viewer, ServerPlayer owner) {
        viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, new LiveEnderChest(owner)),
                net.minecraft.network.chat.Component.translatable("container.enderchest")));
    }

    /**
     * Another player's ender chest, live, closing when they leave (their ender chest is saved then: items moved
     * afterwards were duplicated or lost) and leaving the chest block they may have open alone (no startOpen/stopOpen,
     * which would clear their open chest and leave its lid stuck).
     */
    private static final class LiveEnderChest implements Container {
        private final ServerPlayer owner;
        private final Container chest;

        LiveEnderChest(ServerPlayer owner) {
            this.owner = owner;
            this.chest = owner.getEnderChestInventory();
        }

        @Override
        public int getContainerSize() {
            return chest.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return chest.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return chest.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return chest.removeItem(slot, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return chest.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            chest.setItem(slot, stack);
        }

        @Override
        public void setChanged() {
            chest.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return !owner.hasDisconnected() && !owner.isRemoved();
        }

        @Override
        public void clearContent() {
        }
    }

    /**
     * Another player's inventory as a 5-row chest, live: rows 1-3 the main inventory, row 4 the hotbar, row 5 boots,
     * leggings, chestplate, helmet and the offhand.
     */
    private static final class LiveInventory implements Container {
        private final ServerPlayer owner;
        private final Inventory inv;

        LiveInventory(ServerPlayer owner) {
            this.owner = owner;
            this.inv = owner.getInventory();
        }

        private static int map(int slot) {
            if (slot < 27) return slot + 9;
            if (slot < 36) return slot - 27;
            if (slot < 41) return slot;
            return -1;
        }

        @Override
        public int getContainerSize() {
            return 45;
        }

        @Override
        public boolean isEmpty() {
            return inv.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            int s = map(slot);
            return s < 0 ? ItemStack.EMPTY : inv.getItem(s);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            int s = map(slot);
            return s < 0 ? ItemStack.EMPTY : inv.removeItem(s, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            int s = map(slot);
            return s < 0 ? ItemStack.EMPTY : inv.removeItemNoUpdate(s);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            int s = map(slot);
            if (s >= 0) inv.setItem(s, stack);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return map(slot) >= 0;
        }

        @Override
        public void setChanged() {
            inv.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return !owner.hasDisconnected() && !owner.isRemoved();
        }

        @Override
        public void clearContent() {
        }
    }

    private void top(Sender sender) {
        ServerPlayer p = sender.player();
        if (p == null) return;
        ServerLevel level = p.level();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
        Loc to = new Loc(level, p.getBlockX() + 0.5, y, p.getBlockZ() + 0.5, p.getYRot(), p.getXRot());
        plugin.movePlayer(p, to);
        plugin.text().sound(p, "teleport");
    }

    private void tphere(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null || args.length == 0) {
            text.send(sender, "staff.tphere-usage");
            return;
        }
        ServerPlayer target = plugin.findPlayer(p, args[0]);
        if (target == null) {
            plugin.notFound(p, args[0]);
            return;
        }
        plugin.movePlayer(target, Loc.of(p));
        text.send(p, "staff.tphere", Text.component("target", Names.display(target)));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        String name = CommandBridge.nameOf(label);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        switch (name) {
            case "fly", "vanish", "invsee", "enderchest", "tphere" -> {
                String perm = switch (name) {
                    case "invsee" -> "feather.invsee";
                    case "tphere" -> "feather.tphere";
                    default -> "feather." + name + ".others";
                };
                return args.length == 1 ? players(sender, last, perm) : List.of();
            }
            case "speed" -> {
                if (args.length == 1) return filter(List.of("1", "2", "3", "5", "10"), last, 10);
                return args.length == 2 ? players(sender, last, "feather.speed.others") : List.of();
            }
            case "gmc", "gms", "gma", "gmsp", "god", "heal", "feed" -> {
                return args.length == 1 ? players(sender, last, "feather." + permBase(name) + ".others") : List.of();
            }
            case "smite" -> {
                return args.length == 1 ? players(sender, last, "feather.smite.others") : List.of();
            }
            case "spawnmob" -> {
                if (args.length == 1) return filter(mobNames, last, 100);
                if (args.length == 2) return filter(List.of("1", "5", "10"), last, 10);
                if (args.length == 3) return players(sender, last, "feather.spawnmob.others");
                return List.of();
            }
            case "i" -> {
                if (args.length == 1) return filter(itemNames, last, 150);
                if (args.length == 2) return filter(List.of("1", "16", "32", "64"), last, 10);
                if (args.length == 3) return players(sender, last, "feather.item.others");
                return List.of();
            }
            case "itemname" -> {
                return args.length == 1 ? filter(List.of("off", "&", "<gradient:#ff9ad5:#9ad5ff>"), last, 5) : List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private static String permBase(String cmd) {
        return switch (cmd) {
            case "gmc", "gms", "gma", "gmsp" -> "gamemode";
            default -> cmd;
        };
    }

    private List<String> players(Sender sender, String prefix, String perm) {
        if (!sender.hasPermission(perm)) return List.of();
        return plugin.completePlayers(sender, prefix);
    }

    private static List<String> filter(Collection<String> options, String prefix, int limit) {
        String p = prefix.startsWith("minecraft:") ? prefix.substring(10) : prefix;
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) starts.add(o);
            else if (p.length() >= 2 && o.contains(p)) contains.add(o);
        }
        starts.addAll(contains);
        return starts.size() > limit ? starts.subList(0, limit) : starts;
    }

    private static String pretty(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
