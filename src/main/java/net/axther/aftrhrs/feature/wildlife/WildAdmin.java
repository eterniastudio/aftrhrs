package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.cmd.Sender;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code /wildlife <sub>} for staff (feather.wildlife.admin): inspect the wild mobs and stations near you, and test
 * hooks that make timers due, force a gull dive or the Restless Moon, and edit a player's bestiary.
 */
final class WildAdmin {
    static final List<String> SUBS = List.of("info", "due", "tick", "fedexpire", "shed", "dive", "hotspot", "moon", "record", "book", "reset");

    private WildAdmin() {
    }

    static void run(WildlifeModule m, Sender sender, String sub, String[] args) {
        ServerPlayer p = sender.player();
        switch (sub) {
            case "info" -> {
                if (p == null) {
                    // console: the tracked count, plus any live wild mob of ours that isn't tracked (should be 0)
                    Map<String, Integer> byType = new TreeMap<>(), missed = new TreeMap<>();
                    for (String t : m.tracked.values()) byType.merge(t, 1, Integer::sum);
                    for (ServerLevel w : Feather.server().getAllLevels()) {
                        for (Entity e : w.getAllEntities()) {
                            if (m.tracked.containsKey(e.getUUID())) continue;
                            String t = WildlifeModule.mythicType(e);
                            if (m.isWild(t)) missed.merge(t, 1, Integer::sum);
                        }
                    }
                    sender.sendMessage("WILDS tracked=" + m.tracked.size() + " " + byType + " untracked=" + missed);
                    return;
                }
                info(m, p);
            }
            case "due", "fedexpire", "shed" -> {
                if (p == null) return;
                long now = System.currentTimeMillis();
                int n = 0;
                for (Entity e : near(m, p)) {
                    WildSpecies s = m.species.get(m.tracked.get(e.getUUID()));
                    if (s == null) continue;
                    n++;
                    switch (sub) {
                        case "due" -> {
                            for (String k : List.of(Harvests.MILK, Harvests.BRUSH, Harvests.LAY, Harvests.ROOT, WildBreeding.READY)) {
                                if (m.getLong(e, k) > 0) m.setLong(e, k, 1);
                            }
                            if (m.getLong(e, Harvests.SILK) > 0) m.setLong(e, Harvests.SILK, 1);
                            if (m.getLong(e, Harvests.DRIP) > 0) m.setLong(e, Harvests.DRIP, 1);
                            if (m.getLong(e, Harvests.SHORN) > 0 && s.shear != null) m.setLong(e, Harvests.REGROW, Math.max(0, s.shear.regrowMs() - 1));
                            if (m.getLong(e, Harvests.ANTLER) > 0 && s.antlers != null) m.setLong(e, Harvests.ANTLER, now - s.antlers.regrowMs());
                            if (s.lay != null && m.getLong(e, Harvests.LAY) == 0) m.setLong(e, Harvests.LAY, 1);
                            if (s.root != null && m.getLong(e, Harvests.ROOT) == 0) m.setLong(e, Harvests.ROOT, 1);
                        }
                        case "fedexpire" -> {
                            if (m.getLong(e, Harvests.FED) > 0) m.setLong(e, Harvests.FED, now + 1000);
                            else m.setLong(e, Harvests.FED, 0);
                        }
                        case "shed" -> {
                            if (s.antlers != null && m.getLong(e, Harvests.ANTLER) == 0) m.harvests.shed(e, s, now);
                        }
                        default -> {
                        }
                    }
                }
                if (sub.equals("due")) n += m.stations.due((ServerLevel) p.level(), p.position());
                sender.sendMessage("WILDLIFE " + sub + " " + n);
            }
            case "tick" -> {
                m.harvests.tick(System.currentTimeMillis());
                m.gulls.scan();
                m.bestiary.scan();
                sender.sendMessage("WILDLIFE ticked");
            }
            case "dive" -> {
                if (p == null) return;
                int n = 0;
                for (Entity e : near(m, p)) {
                    if (!m.gullId.equals(m.tracked.get(e.getUUID()))) continue;
                    if (m.gulls.dive(e, p, System.currentTimeMillis())) n++;
                }
                sender.sendMessage("WILDLIFE dive " + n);
            }
            case "hotspot" -> {
                if (p == null) return;
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                ServerLevel level = (ServerLevel) p.level();
                if (what.equals("add")) {
                    // hotspot add [seconds] [radius]: a hotspot centered on you, as if a gull had dived here (tests)
                    double secs = 60, radius = 4;
                    try {
                        if (args.length > 2) secs = Double.parseDouble(args[2]);
                        if (args.length > 3) radius = Double.parseDouble(args[3]);
                    } catch (NumberFormatException ignored) {
                    }
                    WildlifeHotspots.add(level, p.position(), Math.max(1, radius), System.currentTimeMillis() + (long) (secs * 1000));
                } else if (what.equals("clear")) {
                    WildlifeHotspots.clear();
                }
                sender.sendMessage("HOTSPOT here=" + WildlifeHotspots.INSTANCE.isHotspot(level, p.position()) + " active=" + WildlifeHotspots.active().size()
                        + " bite=" + WildlifeHotspots.INSTANCE.biteSpeedMultiplier() + " rare=" + WildlifeHotspots.INSTANCE.rareWeightMultiplier());
                for (WildlifeHotspots.Spot s : WildlifeHotspots.active()) {
                    sender.sendMessage("SPOT " + Math.round(s.x() - 0.5) + "," + Math.round(s.y()) + "," + Math.round(s.z() - 0.5) + " r=" + s.radius()
                            + " left=" + (s.until() - System.currentTimeMillis()) / 1000 + "s");
                }
            }
            case "moon" -> {
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "state";
                if (what.equals("on")) m.moon.force();
                else if (what.equals("off")) m.moon.stop();
                else if (what.equals("reset")) {
                    // forget tonight's announcement, so the next dusk check announces again
                    m.bestiary.moonDay = -1;
                    m.bestiary.markDirty();
                }
                sender.sendMessage(m.moon.state());
            }
            case "record" -> {
                // record <player> <mob> <seen|harvested|slain>
                if (args.length < 4) {
                    m.send(sender, "admin.help");
                    return;
                }
                ServerPlayer who = m.feather().findPlayer(sender, args[1]);
                if (who == null) {
                    sender.sendMessage("Not online: " + args[1]);
                    return;
                }
                switch (args[3].toLowerCase(Locale.ROOT)) {
                    case "seen" -> m.bestiary.see(who, args[2]);
                    case "harvested" -> m.bestiary.harvested(who, args[2]);
                    case "slain" -> m.bestiary.slain(who, args[2]);
                    default -> {
                        m.send(sender, "admin.help");
                        return;
                    }
                }
                sender.sendMessage("WILDLIFE recorded " + args[2] + " " + args[3] + " for " + who.getName().getString());
            }
            case "book", "reset" -> {
                UUID id;
                String name;
                if (args.length > 1) {
                    OfflinePlayer who = m.feather().findOfflinePlayer(sender, args[1]);
                    if (who == null) {
                        sender.sendMessage("Unknown player.");
                        return;
                    }
                    id = who.id();
                    name = who.name();
                } else if (p != null) {
                    id = p.getUUID();
                    name = p.getName().getString();
                } else {
                    sender.sendMessage("Unknown player.");
                    return;
                }
                if (sub.equals("reset")) {
                    m.bestiary.reset(id);
                    m.bestiary.save(true);
                    sender.sendMessage("WILDLIFE bestiary reset for " + name);
                    return;
                }
                for (String line : m.bestiary.summary(id)) sender.sendMessage(line);
            }
            default -> m.send(sender, "admin.help");
        }
    }

    private static List<Entity> near(WildlifeModule m, ServerPlayer p) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : m.trackedEntities()) if (e.level() == p.level() && e.distanceToSqr(p) <= 64) out.add(e);
        return out;
    }

    private static long left(WildlifeModule m, Entity e, String key, long now) {
        long v = m.getLong(e, key);
        return v <= 0 ? 0 : Math.max(0, (v - now + 999) / 1000);
    }

    private static void info(WildlifeModule m, ServerPlayer p) {
        long now = System.currentTimeMillis();
        int shown = 0;
        for (Entity e : near(m, p)) {
            String type = m.tracked.get(e.getUUID());
            String follow = m.getString(e, Squallgulls.FOLLOW);
            String fname = "-";
            if (follow != null) {
                try {
                    OfflinePlayer f = m.feather().lookup().byId(UUID.fromString(follow));
                    if (f != null && f.name() != null) fname = f.name();
                } catch (IllegalArgumentException ignored) {
                }
            }
            int var = e instanceof net.axther.aftrhrs.entity.ArcanaWildlife w ? w.var(m.keptVar) : 0;
            Vec3 l = e.position();
            Sender.of(p).sendMessage("WILD " + type + " @" + (int) Math.floor(l.x) + "," + (int) Math.floor(l.y) + "," + (int) Math.floor(l.z)
                    + " kept=" + (m.kept(e) ? 1 : 0) + " var=" + var
                    + " shorn=" + (m.getLong(e, Harvests.SHORN) > 0 ? 1 : 0)
                    + " fed=" + left(m, e, Harvests.FED, now) + " milk=" + left(m, e, Harvests.MILK, now) + " brush=" + left(m, e, Harvests.BRUSH, now)
                    + " lay=" + left(m, e, Harvests.LAY, now) + " root=" + left(m, e, Harvests.ROOT, now)
                    + " silk=" + left(m, e, Harvests.SILK, now) + " drip=" + left(m, e, Harvests.DRIP, now)
                    + " breed=" + left(m, e, WildBreeding.READY, now) + " antler=" + (m.getLong(e, Harvests.ANTLER) > 0 ? 1 : 0)
                    + " held=" + (m.gulls.holding(e) ? 1 : 0) + " follow=" + fname);
            shown++;
        }
        Sender s = Sender.of(p);
        for (String line : m.stations.info((ServerLevel) p.level(), p.position())) s.sendMessage(line);
        s.sendMessage("WILDS " + shown + " near you, " + m.tracked.size() + " tracked, " + m.gulls.followingCount() + " following");
    }

    static List<String> complete(WildlifeModule m, Sender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("moon")) {
            out.addAll(List.of("on", "off", "reset", "state"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("hotspot")) {
            out.addAll(List.of("add", "clear"));
        } else if (args.length == 2 && List.of("record", "book", "reset").contains(args[0].toLowerCase(Locale.ROOT))) {
            out.addAll(m.feather().completePlayers(sender, args[1]));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("record")) {
            out.addAll(m.species.keySet());
            out.addAll(m.undead.keySet());
            out.add(m.gullId);
            out.add(m.duskfliesId);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("record")) {
            out.addAll(List.of("seen", "harvested", "slain"));
        }
        return out;
    }
}
