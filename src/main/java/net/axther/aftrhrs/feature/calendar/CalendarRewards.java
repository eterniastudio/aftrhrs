package net.axther.aftrhrs.feature.calendar;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rewards of the login calendar ({@link CalendarModule}): one per day of the 7-day streak cycle, daily playtime
 * tiers and lifetime playtime milestones. A reward is any mix of Goob Coins (paid into the bank balance), bond XP for
 * the pets following the player, items and console commands, read from the {@code calendar} block of config.yml.
 */
final class CalendarRewards {
    /** One item stack of a reward. */
    record Stack(Item item, int amount) {
    }

    /** One reward. {@code label} (MiniMessage, may be empty) replaces the generated description. */
    record Reward(long coins, int bond, List<Stack> items, List<String> commands, String label) {
        static final Reward NONE = new Reward(0, 0, List.of(), List.of(), "");

        boolean empty() {
            return coins <= 0 && bond <= 0 && items.isEmpty() && commands.isEmpty();
        }
    }

    /** A playtime goal: {@code id} is stable across config edits ("30m", "10h"), {@code ms} the time needed. */
    record Goal(String id, long ms, Reward reward) {
    }

    private CalendarRewards() {
    }

    /** Bukkit's {@code Material.matchMaterial}: "bread", "BREAD", "minecraft:bread"; null for unknown items and air. */
    static Item matchItem(String name) {
        if (name == null || name.isBlank()) return null;
        Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    static Reward read(Yaml s, String where, Logger log) {
        if (s == null) return Reward.NONE;
        long coins = Math.max(0, Math.min(1_000_000, s.getLong("coins", 0)));
        int bond = Math.max(0, Math.min(10_000, s.getInt("pet-bond", 0)));
        List<Stack> items = new ArrayList<>();
        for (String spec : s.getStringList("items")) {
            String[] parts = spec.trim().split("\\s+");
            Item m = matchItem(parts[0]);
            if (m == null) {
                log.warn("calendar: unknown item '{}' in {}; skipped.", parts[0], where);
                continue;
            }
            int amount = 1;
            try {
                if (parts.length > 1) amount = Math.max(1, Math.min(64 * 36, Integer.parseInt(parts[1])));
            } catch (NumberFormatException e) {
                log.warn("calendar: bad amount in '{}' ({}); giving 1.", spec, where);
            }
            items.add(new Stack(m, amount));
        }
        List<String> commands = new ArrayList<>();
        for (String c : s.getStringList("commands")) if (c != null && !c.isBlank()) commands.add(c.trim());
        return new Reward(coins, bond, List.copyOf(items), List.copyOf(commands), s.getString("label", ""));
    }

    /** The 7-day cycle: {@code days.1} ... {@code days.7}. Missing days give nothing. */
    static List<Reward> readDays(Yaml days, Logger log) {
        List<Reward> out = new ArrayList<>();
        for (int d = 1; d <= CalendarModule.CYCLE; d++) {
            out.add(read(days == null ? null : days.getConfigurationSection(String.valueOf(d)), "days." + d, log));
        }
        return out;
    }

    /** Goals from a list of maps with {@code unit} ("minutes" or "hours") giving the threshold; sorted by time. */
    static List<Goal> readGoals(Yaml parent, String path, String unit, Logger log) {
        List<Goal> out = new ArrayList<>();
        if (parent == null) return out;
        List<Map<?, ?>> list = parent.getMapList(path);
        for (Map<?, ?> raw : list) {
            Object t = raw.get(unit);
            if (!(t instanceof Number n) || n.doubleValue() <= 0) {
                log.warn("calendar: an entry in {} has no positive '{}'; skipped.", path, unit);
                continue;
            }
            long minutes = unit.equals("hours") ? Math.round(n.doubleValue() * 60) : Math.round(n.doubleValue());
            String id = minutes % 60 == 0 ? (minutes / 60) + "h" : minutes + "m";
            Yaml sec = new Yaml();
            for (Map.Entry<?, ?> e : raw.entrySet()) sec.set(String.valueOf(e.getKey()), e.getValue());
            Goal g = new Goal(id, minutes * 60_000L, read(sec, path + " " + id, log));
            if (out.stream().anyMatch(o -> o.id().equals(g.id()))) {
                log.warn("calendar: {} has {} twice; the second is skipped.", path, id);
                continue;
            }
            out.add(g);
        }
        out.sort((a, b) -> Long.compare(a.ms(), b.ms()));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ describing

    /** "5 ⛃ · 3 Bread · +10 bond", or the reward's own label. */
    static Component describe(Text text, Reward r) {
        if (r.label() != null && !r.label().isBlank()) return text.parse(r.label());
        List<Component> parts = new ArrayList<>();
        if (r.coins() > 0) parts.add(text.msg("calendar.reward.coins", Text.unparsed("amount", r.coins())));
        for (Stack s : r.items()) {
            parts.add(text.msg("calendar.reward.item", Text.unparsed("amount", s.amount()), Text.unparsed("item", itemName(s.item()))));
        }
        if (r.bond() > 0) parts.add(text.msg("calendar.reward.bond", Text.unparsed("amount", r.bond())));
        if (parts.isEmpty() && !r.commands().isEmpty()) parts.add(text.msg("calendar.reward.surprise"));
        if (parts.isEmpty()) return text.msg("calendar.reward.nothing");
        return Component.join(JoinConfiguration.separator(text.msg("calendar.reward.separator")), parts);
    }

    /** A short form for the cycle row: the coins, else the first item, else bond, else a gift. */
    static Component shortForm(Text text, Reward r) {
        if (r.coins() > 0) return text.msg("calendar.reward.coins", Text.unparsed("amount", r.coins()));
        if (!r.items().isEmpty()) return text.msg("calendar.reward.short-item");
        if (r.bond() > 0) return text.msg("calendar.reward.short-bond");
        return text.msg("calendar.reward.short-gift");
    }

    static String itemName(Item m) {
        String[] words = BuiltInRegistries.ITEM.getKey(m).getPath().split("_");
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ paying

    /** The item stacks of all these rewards, split into stack-sized pieces. */
    static List<ItemStack> stacks(List<Reward> rewards) {
        List<ItemStack> out = new ArrayList<>();
        for (Reward r : rewards) {
            for (Stack s : r.items()) {
                int left = s.amount();
                int max = Math.max(1, new ItemStack(s.item()).getMaxStackSize());
                while (left > 0) {
                    int n = Math.min(max, left);
                    out.add(new ItemStack(s.item(), n));
                    left -= n;
                }
            }
        }
        return out;
    }

    /** Whether all these items fit in the player's 36 storage slots (tried on a copy, nothing moves). */
    static boolean fits(ServerPlayer p, List<ItemStack> items) {
        if (items.isEmpty()) return true;
        List<ItemStack> copy = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) copy.add(p.getInventory().getItem(i).copy());
        for (ItemStack want : items) {
            int left = want.getCount();
            for (ItemStack slot : copy) {
                if (left <= 0) break;
                if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, want)) continue;
                int room = slot.getMaxStackSize() - slot.getCount();
                int n = Math.min(room, left);
                if (n > 0) {
                    slot.grow(n);
                    left -= n;
                }
            }
            for (int i = 0; i < copy.size() && left > 0; i++) {
                if (!copy.get(i).isEmpty()) continue;
                int n = Math.min(want.getMaxStackSize(), left);
                copy.set(i, want.copyWithCount(n));
                left -= n;
            }
            if (left > 0) return false;
        }
        return true;
    }

    /** Sum of the coins. */
    static long coins(List<Reward> rewards) {
        long n = 0;
        for (Reward r : rewards) n += r.coins();
        return n;
    }

    /** Sum of the bond XP. */
    static int bond(List<Reward> rewards) {
        int n = 0;
        for (Reward r : rewards) n += r.bond();
        return n;
    }

    /** Runs the reward commands from the console: {@code <player>}, {@code <uuid>}. */
    static void runCommands(Feather plugin, ServerPlayer p, List<Reward> rewards) {
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        for (Reward r : rewards) {
            for (String raw : r.commands()) {
                String cmd = raw.replace("<player>", Names.name(p)).replace("<uuid>", p.getUUID().toString()).trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                } catch (RuntimeException e) {
                    plugin.getLogger().warn("Calendar reward command failed: {} ({})", cmd, e.toString());
                }
            }
        }
    }

    /** Lower-case ids for the log. */
    static String ids(List<String> ids) {
        return String.join(",", ids).toLowerCase(Locale.ROOT);
    }
}
