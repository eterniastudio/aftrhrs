package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /claim, /unclaim, /trust, /untrust and /claimblocks. */
final class ClaimCommands {
    private final ClaimsModule m;

    ClaimCommands(ClaimsModule m) {
        this.m = m;
    }

    private Text text() {
        return m.text();
    }

    /** The handler for one command name (aliases arrive with their own label; this keeps the name). */
    FeatherCommand handler(String name) {
        return new FeatherCommand() {
            @Override
            public boolean onCommand(Sender sender, String label, String[] args) {
                run(name, sender, args);
                return true;
            }

            @Override
            public List<String> onTabComplete(Sender sender, String label, String[] args) {
                return complete(name, sender, args);
            }
        };
    }

    void run(String cmd, Sender sender, String[] args) {
        if (cmd.equals("claimblocks")) {
            claimBlocks(sender, args);
            return;
        }
        if (cmd.equals("claim") && args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            admin(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (!sender.isPlayer()) {
            text().send(sender, "general.players-only");
            return;
        }
        ServerPlayer p = sender.player();
        if (!Perms.has(p, ClaimsModule.USE)) {
            text().send(p, "general.no-permission");
            return;
        }
        switch (cmd) {
            case "unclaim" -> unclaim(p, args.length > 0 && args[0].equalsIgnoreCase("all"), args.length > 0 && args[0].equalsIgnoreCase("confirm"));
            case "trust" -> trust(p, args);
            case "untrust" -> untrust(p, args);
            default -> claim(p, args);
        }
    }

    // ------------------------------------------------------------------ /claim

    void claim(ServerPlayer p, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        Claim here = m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
        switch (sub) {
            case "" -> m.menu.open(p);
            case "chunk" -> m.claim(p, p.level(), Area.ofChunk(p.getBlockX() >> 4, p.getBlockZ() >> 4));
            case "list" -> list(p, args.length > 1 ? args[1] : null);
            case "info" -> {
                if (here == null) text().send(p, "claims.info-wild", Text.unparsed("left", m.remaining(p.getUUID())));
                else text().send(p, "claims.info", m.claimTags(here, p));
            }
            case "name", "rename" -> {
                if (here == null) {
                    text().send(p, "claims.not-in-claim");
                    return;
                }
                if (!m.canManage(p, here)) {
                    text().send(p, "claims.not-manager");
                    return;
                }
                if (args.length < 2) {
                    text().send(p, "claims.usage.name");
                    return;
                }
                rename(p, here, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "abandon" -> {
                if (here == null) {
                    text().send(p, "claims.not-in-claim");
                    return;
                }
                if (!m.isOwnerOrAdmin(p, here)) {
                    text().send(p, "claims.not-owner");
                    return;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) m.abandon(p, here);
                else m.menu.confirmAbandon(p, here, null);
            }
            case "flags", "flag" -> {
                if (here == null) {
                    text().send(p, "claims.not-in-claim");
                    return;
                }
                if (args.length >= 3) {
                    ClaimFlag f = ClaimFlag.parse(args[1]);
                    if (f == null || !m.canManage(p, here)) {
                        text().send(p, f == null ? "claims.usage.flags" : "claims.not-manager");
                        return;
                    }
                    boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true") || args[2].equalsIgnoreCase("allow");
                    m.menu.setFlag(p, here, f, on);
                    return;
                }
                m.menu.openFlags(p, here, null);
            }
            case "show" -> {
                m.visuals.flash(p);
                text().send(p, "claims.showing", Text.unparsed("seconds", m.settings.showSeconds));
            }
            case "bypass" -> adminMode(p, args.length > 1 ? args[1] : null);
            case "mode" -> {
                ClaimVisuals.Session s = m.visuals.session(p);
                ClaimVisuals.Mode mode = args.length > 1 ? parseMode(args[1]) : switch (s.mode) {
                    case RECT -> ClaimVisuals.Mode.CHUNK;
                    case CHUNK -> ClaimVisuals.Mode.CIRCLE;
                    default -> ClaimVisuals.Mode.RECT;
                };
                if (mode == null || (mode == ClaimVisuals.Mode.ADMIN && !Perms.has(p, ClaimsModule.ADMIN))) {
                    text().send(p, "claims.usage.mode");
                    return;
                }
                s.mode = mode;
                s.corner = null;
                s.resizing = null;
                text().send(p, "claims.tool.mode-" + mode.name().toLowerCase(Locale.ROOT));
            }
            case "transfer" -> {
                if (here == null) {
                    text().send(p, "claims.not-in-claim");
                    return;
                }
                if (args.length < 2) {
                    text().send(p, "claims.usage.transfer");
                    return;
                }
                transfer(p, here, args[1], args.length > 2 && args[2].equalsIgnoreCase("confirm"));
            }
            case "trust" -> trust(p, Arrays.copyOfRange(args, 1, args.length));
            case "untrust" -> untrust(p, Arrays.copyOfRange(args, 1, args.length));
            case "unclaim" -> unclaim(p, args.length > 1 && args[1].equalsIgnoreCase("all"), args.length > 1 && args[1].equalsIgnoreCase("confirm"));
            default -> text().send(p, Perms.has(p, ClaimsModule.ADMIN) ? "claims.help-admin" : "claims.help");
        }
    }

    private static ClaimVisuals.Mode parseMode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "rect", "rectangle", "corners" -> ClaimVisuals.Mode.RECT;
            case "chunk", "chunks" -> ClaimVisuals.Mode.CHUNK;
            case "circle", "round", "radius", "sphere" -> ClaimVisuals.Mode.CIRCLE;
            case "admin" -> ClaimVisuals.Mode.ADMIN;
            default -> null;
        };
    }

    void rename(ServerPlayer p, Claim c, String raw) {
        String name = raw.replaceAll("[<>\\\\&§\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || name.length() > 32) {
            text().send(p, "claims.bad-name");
            return;
        }
        if (m.closed(p)) return;
        c.name = name;
        m.store.markDirty();
        m.bridge.touch(c);
        text().send(p, "claims.renamed", Text.unparsed("name", name));
        text().sound(p, "click");
    }

    private void list(ServerPlayer p, String who) {
        UUID owner = p.getUUID();
        String ownerName = Names.name(p);
        if (who != null) {
            if (!Perms.has(p, ClaimsModule.ADMIN)) {
                text().send(p, "general.no-permission");
                return;
            }
            OfflinePlayer op = m.plugin.findOfflinePlayer(p, who);
            if (op == null) {
                m.plugin.notFound(p, who);
                return;
            }
            owner = op.id();
            ownerName = op.name() == null ? who : op.name();
        }
        List<Claim> list = m.registry.ownedBy(owner);
        text().send(p, "claims.list-header", Text.unparsed("player", ownerName), Text.unparsed("count", list.size()),
                Text.unparsed("used", m.registry.used(owner)), Text.unparsed("left", m.remaining(owner)), Text.unparsed("total", m.total(owner)));
        for (Claim c : list) text().send(p, "claims.list-line", m.claimTags(c, p));
        if (list.isEmpty()) text().send(p, "claims.list-empty");
    }

    private void unclaim(ServerPlayer p, boolean all, boolean confirmed) {
        Claim here = m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
        if (here == null) {
            text().send(p, "claims.not-in-claim");
            return;
        }
        if (!m.isOwnerOrAdmin(p, here)) {
            text().send(p, "claims.not-owner");
            return;
        }
        if (all) {
            m.menu.confirmAbandon(p, here, null);
            return;
        }
        int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
        if (confirmed) m.unclaimChunk(p, here, cx, cz);
        else m.menu.confirmUnclaim(p, here, cx, cz, null);
    }

    /** Hands the claim to another player (a full username only), after a confirmation unless {@code confirmed}. */
    void transfer(ServerPlayer p, Claim c, String to, boolean confirmed) {
        if (c.removed) {
            text().send(p, "claims.gone");
            return;
        }
        if (!m.isOwnerOrAdmin(p, c) || c.admin()) {
            text().send(p, "claims.not-owner");
            return;
        }
        if (m.closed(p)) return;
        OfflinePlayer target = m.plugin.findOfflinePlayer(p, to);
        if (target == null) {
            m.plugin.notFound(p, to);
            return;
        }
        // A nickname or the start of a name could mean someone else: giving land away needs the exact username.
        if (target.name() == null || !target.name().equalsIgnoreCase(to)) {
            text().send(p, "claims.transfer-exact", Text.unparsed("input", to));
            return;
        }
        UUID id = target.id();
        String name = target.name();
        if (id.equals(c.owner)) {
            text().send(p, "claims.transfer-already", Text.unparsed("player", name), Text.unparsed("name", c.name));
            return;
        }
        if (!Perms.has(p, ClaimsModule.ADMIN) && (m.remaining(id) < c.cost || m.registry.ownedBy(id).size() >= m.settings.maxClaims)) {
            text().send(p, "claims.transfer-no-room", Text.unparsed("player", name));
            return;
        }
        if (!confirmed) {
            m.menu.confirmTransfer(p, c, name);
            return;
        }
        UUID old = c.owner;
        String oldName = c.ownerName;
        c.owner = id;
        c.ownerName = name;
        c.trust.remove(id);
        c.trustNames.remove(id);
        if (old != null) {
            c.trust.put(old, TrustLevel.MANAGER);
            c.trustNames.put(old, oldName == null ? "" : oldName);
        }
        m.store.markDirty();
        m.bridge.touch(c);
        m.bridge.invalidate(old);
        text().send(p, "claims.transferred", Text.unparsed("name", c.name), Text.unparsed("player", c.ownerName));
        ServerPlayer online = target.online();
        if (online != null && online != p) text().send(online, "claims.transferred-you", Text.unparsed("name", c.name), Text.unparsed("player", Names.name(p)));
    }

    // ------------------------------------------------------------------ trust

    /** The claims a trust command applies to: the one you stand in (if you manage it), else all of yours. */
    private List<Claim> targets(ServerPlayer p) {
        Claim here = m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
        if (here != null) {
            if (m.canManage(p, here)) return List.of(here);
            text().send(p, "claims.not-manager");
            return List.of();
        }
        List<Claim> mine = m.registry.ownedBy(p.getUUID());
        if (mine.isEmpty()) text().send(p, "claims.no-claims");
        return mine;
    }

    private void trust(ServerPlayer p, String[] args) {
        if (args.length < 1) {
            text().send(p, "claims.usage.trust");
            return;
        }
        TrustLevel level = args.length > 1 ? TrustLevel.parse(args[1]) : TrustLevel.BUILD;
        if (level == null) {
            text().send(p, "claims.usage.trust");
            return;
        }
        if (m.closed(p)) return;
        List<Claim> claims = targets(p);
        if (claims.isEmpty()) return;
        if (args[0].equalsIgnoreCase("public") || args[0].equalsIgnoreCase("everyone") || args[0].equalsIgnoreCase("all")) {
            if (level == TrustLevel.MANAGER) {
                text().send(p, "claims.public-no-manager");
                return;
            }
            for (Claim c : claims) m.menu.setPublic(p, c, level, false);
            text().send(p, "claims.public-set", Text.unparsed("level", text().raw("claims.level." + level.key())), Text.unparsed("count", claims.size()));
            return;
        }
        OfflinePlayer target = m.plugin.findOfflinePlayer(p, args[0]);
        if (target == null) {
            m.plugin.notFound(p, args[0]);
            return;
        }
        String name = target.name() == null ? args[0] : target.name();
        int done = 0;
        for (Claim c : claims) if (m.menu.setTrust(p, c, target.id(), name, level, claims.size() == 1)) done++;
        if (done > 0) {
            text().send(p, "claims.trusted", Text.unparsed("player", name), Text.unparsed("level", text().raw("claims.level." + level.key())),
                    Text.unparsed("count", done), Text.unparsed("name", claims.size() == 1 ? claims.get(0).name : ""));
            text().sound(p, "accepted");
            ServerPlayer online = target.online();
            if (online != null && online != p) {
                text().send(online, "claims.trusted-you", Text.unparsed("player", Names.name(p)), Text.unparsed("level", text().raw("claims.level." + level.key())),
                        Text.unparsed("name", claims.get(0).name));
            }
        }
    }

    private void untrust(ServerPlayer p, String[] args) {
        if (args.length < 1) {
            text().send(p, "claims.usage.untrust");
            return;
        }
        if (m.closed(p)) return;
        List<Claim> claims = targets(p);
        if (claims.isEmpty()) return;
        if (args[0].equalsIgnoreCase("public") || args[0].equalsIgnoreCase("everyone") || args[0].equalsIgnoreCase("all")) {
            for (Claim c : claims) m.menu.setPublic(p, c, null, false);
            text().send(p, "claims.public-cleared", Text.unparsed("count", claims.size()));
            return;
        }
        OfflinePlayer target = m.plugin.findOfflinePlayer(p, args[0]);
        UUID id = target != null ? target.id() : null;
        if (id == null) {
            // Someone on a trust list who never played here again: match the saved name.
            for (Claim c : claims) {
                for (var e : c.trustNames.entrySet()) if (e.getValue().equalsIgnoreCase(args[0])) id = e.getKey();
            }
        }
        if (id == null) {
            m.plugin.notFound(p, args[0]);
            return;
        }
        String name = target != null && target.name() != null ? target.name() : args[0];
        int done = 0;
        for (Claim c : claims) if (m.menu.setTrust(p, c, id, name, null, claims.size() == 1)) done++;
        if (done > 0) {
            text().send(p, "claims.untrusted", Text.unparsed("player", name), Text.unparsed("count", done));
            text().sound(p, "deleted");
        } else {
            text().send(p, "claims.not-trusted", Text.unparsed("player", name));
        }
    }

    // ------------------------------------------------------------------ /claimblocks

    private void claimBlocks(Sender sender, String[] args) {
        if (args.length == 0) {
            if (!sender.isPlayer()) {
                text().send(sender, "claims.usage.blocks");
                return;
            }
            showBlocks(sender, sender.player().getUUID(), Names.name(sender.player()));
            return;
        }
        boolean staff = sender.hasPermission(ClaimsModule.BLOCKS_ADMIN);
        if (!staff && !sender.hasPermission(ClaimsModule.ADMIN)) {
            text().send(sender, "general.no-permission");
            return;
        }
        OfflinePlayer target = m.plugin.findOfflinePlayer(sender, args[0]);
        if (target == null) {
            m.plugin.notFound(sender, args[0]);
            return;
        }
        String name = target.name() == null ? args[0] : target.name();
        if (args.length == 1) {
            showBlocks(sender, target.id(), name);
            return;
        }
        if (!staff) {
            text().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 3) {
            text().send(sender, "claims.usage.blocks");
            return;
        }
        long n;
        try {
            n = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            text().send(sender, "claims.usage.blocks");
            return;
        }
        if (n < 0 || n > 100_000_000L) {
            text().send(sender, "claims.usage.blocks");
            return;
        }
        if (m.closed(sender)) return;
        ClaimStore.Budget b = m.store.budget(target.id());
        b.name = name;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "give", "add" -> b.bonus += n;
            case "take", "remove" -> b.bonus -= n;
            case "set" -> b.bonus = n - m.earned(target.id());
            default -> {
                text().send(sender, "claims.usage.blocks");
                return;
            }
        }
        m.store.markDirty();
        m.bridge.invalidate(target.id());
        text().send(sender, "claims.blocks-changed", Text.unparsed("player", name), Text.unparsed("total", m.total(target.id())),
                Text.unparsed("left", m.remaining(target.id())));
    }

    private void showBlocks(Sender to, UUID id, String name) {
        text().send(to, "claims.blocks", Text.unparsed("player", name), Text.unparsed("total", m.total(id)),
                Text.unparsed("used", m.registry.used(id)), Text.unparsed("left", m.remaining(id)),
                Text.unparsed("per-hour", m.settings.chunksPerHour), Text.unparsed("max", m.settings.maxChunks));
    }

    // ------------------------------------------------------------------ /claim admin

    /** Turns claim admin mode (ignore claims) on, off ({@code state}) or over (null). Off again after a relog. */
    private void adminMode(ServerPlayer p, String state) {
        if (!Perms.has(p, ClaimsModule.BYPASS)) {
            text().send(p, "general.no-permission");
            return;
        }
        boolean was = m.bypass.contains(p.getUUID());
        boolean on = state == null ? !was : state.equalsIgnoreCase("on") || state.equalsIgnoreCase("true");
        if (on) m.bypass.add(p.getUUID());
        else m.bypass.remove(p.getUUID());
        text().send(p, on ? "claims.bypass-on" : "claims.bypass-off");
        text().sound(p, "click");
        if (on != was) m.plugin.getLogger().info("Claims: {} turned claim admin mode {}.", p.getScoreboardName(), on ? "on" : "off");
    }

    private void admin(Sender sender, String[] args) {
        if (!sender.hasPermission(ClaimsModule.ADMIN)) {
            text().send(sender, "general.no-permission");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        ServerPlayer p = sender.isPlayer() ? sender.player() : null;
        switch (sub) {
            // /claim admin [on|off]: admin mode, claims don't stop you (same as /claim bypass)
            case "", "on", "off", "toggle", "bypass" -> {
                if (p == null) {
                    text().send(sender, "general.players-only");
                    return;
                }
                adminMode(p, sub.equals("on") || sub.equals("off") ? sub : args.length > 1 ? args[1] : null);
            }
            case "create" -> {
                if (args.length < 2) {
                    text().send(sender, "claims.usage.admin");
                    return;
                }
                // /claim admin create <name> [<world> <x1> <z1> <x2> <z2>] (the coordinates form works from the console)
                String name;
                ServerLevel world;
                Area area;
                if (args.length >= 7) {
                    world = Worlds.resolve(args[args.length - 5]);
                    try {
                        area = Area.rect(Integer.parseInt(args[args.length - 4]), Integer.parseInt(args[args.length - 3]),
                                Integer.parseInt(args[args.length - 2]), Integer.parseInt(args[args.length - 1]));
                    } catch (NumberFormatException e) {
                        text().send(sender, "claims.usage.admin");
                        return;
                    }
                    name = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 5));
                    if (world == null) {
                        text().send(sender, "claims.usage.admin");
                        return;
                    }
                } else {
                    if (p == null) {
                        text().send(sender, "claims.usage.admin");
                        return;
                    }
                    ClaimVisuals.Session s = m.visuals.session(p);
                    name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    if (s.selection == null || s.selWorld == null || Worlds.resolve(s.selWorld) == null) {
                        text().send(p, "claims.admin.no-selection");
                        return;
                    }
                    world = Worlds.resolve(s.selWorld);
                    area = s.selection;
                }
                name = name.replaceAll("[<>\\\\&§]", "").trim();
                if (name.isEmpty() || name.length() > 32) {
                    text().send(sender, "claims.bad-name");
                    return;
                }
                Claim c = m.createAdmin(sender, world, area, name);
                if (c != null && p != null) {
                    m.visuals.session(p).selection = null;
                    m.visuals.flash(p);
                }
            }
            case "allow", "disallow" -> {
                if (args.length < 4) {
                    text().send(sender, "claims.usage.admin");
                    return;
                }
                Claim c = m.registry.find(args[1]);
                if (c == null || !c.admin()) {
                    text().send(sender, "claims.admin.unknown", Text.unparsed("name", args[1]));
                    return;
                }
                String kind = args[2].toLowerCase(Locale.ROOT);
                if (!kind.equals("use") && !kind.equals("break")) {
                    text().send(sender, "claims.usage.admin");
                    return;
                }
                String what = normalize(args[3]);
                if (what == null) {
                    text().send(sender, "claims.admin.unknown-block", Text.unparsed("block", args[3]));
                    return;
                }
                if (m.closed(sender)) return;
                var list = kind.equals("use") ? c.allowUse : c.allowBreak;
                boolean changed = sub.equals("allow") ? list.add(what) : list.remove(what);
                m.store.markDirty();
                m.bridge.touch(c);
                text().send(sender, sub.equals("allow") ? "claims.admin.allowed" : "claims.admin.disallowed", Text.unparsed("name", c.name),
                        Text.unparsed("kind", kind), Text.unparsed("what", what), Text.unparsed("changed", changed ? "" : " (no change)"));
            }
            case "preset" -> {
                if (args.length < 2) {
                    text().send(sender, "claims.usage.admin");
                    return;
                }
                List<String> preset = m.settings.presets.get(args[1].toLowerCase(Locale.ROOT));
                if (preset == null) {
                    text().send(sender, "claims.admin.unknown-preset", Text.unparsed("presets", String.join(", ", m.settings.presets.keySet())));
                    return;
                }
                Claim c = args.length > 2 ? m.registry.find(args[2]) : p == null ? null : m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
                if (c == null || !c.admin()) {
                    text().send(sender, "claims.admin.stand-in");
                    return;
                }
                if (m.closed(sender)) return;
                for (String line : preset) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length != 2) continue;
                    String what = normalize(parts[1]);
                    if (what == null) continue;
                    if (parts[0].equalsIgnoreCase("use")) c.allowUse.add(what);
                    else if (parts[0].equalsIgnoreCase("break")) c.allowBreak.add(what);
                }
                m.store.markDirty();
                m.bridge.touch(c);
                text().send(sender, "claims.admin.preset-applied", Text.unparsed("preset", args[1].toLowerCase(Locale.ROOT)), Text.unparsed("name", c.name),
                        Text.unparsed("use", String.join(", ", c.allowUse)), Text.unparsed("break", String.join(", ", c.allowBreak)));
            }
            case "greeting" -> {
                if (args.length < 2) {
                    text().send(sender, "claims.usage.admin");
                    return;
                }
                Claim c = m.registry.find(args[1]);
                if (c == null || !c.admin()) {
                    text().send(sender, "claims.admin.unknown", Text.unparsed("name", args[1]));
                    return;
                }
                if (m.closed(sender)) return;
                c.greeting = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
                m.store.markDirty();
                m.bridge.touch(c);
                text().send(sender, "claims.admin.greeting-set", Text.unparsed("name", c.name));
            }
            case "delete", "remove" -> {
                // A name only finds admin claims; a player's claim needs its #id or standing in it.
                String token = args.length > 1 && !args[1].equalsIgnoreCase("confirm") ? args[1] : null;
                boolean confirmed = args.length > 1 && args[args.length - 1].equalsIgnoreCase("confirm");
                Claim c = token != null ? m.registry.find(token) : p == null ? null : m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
                if (c != null && token != null && !token.startsWith("#") && !c.admin()) c = null;
                if (c == null) {
                    text().send(sender, "claims.admin.unknown", Text.unparsed("name", token != null ? token : "?"));
                    return;
                }
                if (m.closed(sender)) return;
                if (confirmed) deleteClaim(sender, c);
                else if (p != null) m.menu.confirmDelete(p, c);
                else text().send(sender, "claims.admin.delete-confirm", Text.unparsed("name", c.name), Text.unparsed("owner", m.ownerLabel(c)),
                        Text.unparsed("id", c.id));
            }
            case "list" -> {
                List<Claim> admins = m.registry.all().stream().filter(Claim::admin).toList();
                text().send(sender, "claims.admin.list-header", Text.unparsed("count", admins.size()), Text.unparsed("all", m.registry.all().size()));
                for (Claim c : admins) {
                    text().send(sender, "claims.admin.list-line", Text.unparsed("name", c.name), Text.unparsed("id", c.id),
                            Text.unparsed("where", ClaimsModule.where(c)), Text.unparsed("blocks", m.chunks(c.cost)),
                            Text.unparsed("use", c.allowUse.isEmpty() ? "-" : String.join(", ", c.allowUse)),
                            Text.unparsed("break", c.allowBreak.isEmpty() ? "-" : String.join(", ", c.allowBreak)));
                }
            }
            default -> text().send(sender, "claims.usage.admin");
        }
    }

    /** Deletes any claim (staff): its land is unprotected at once. */
    void deleteClaim(Sender sender, Claim c) {
        if (c.removed) {
            text().send(sender, "claims.gone");
            return;
        }
        if (!sender.hasPermission(ClaimsModule.ADMIN)) {
            text().send(sender, "general.no-permission");
            return;
        }
        if (m.closed(sender)) return;
        m.registry.remove(c);
        m.store.markDirty();
        m.bridge.touch(c);
        text().send(sender, "claims.admin.deleted", Text.unparsed("name", c.name), Text.unparsed("owner", m.ownerLabel(c)));
    }

    /** {@code bank-chest}, {@code #tag} or a block id (lower case, path only for vanilla), or null when it isn't a block. */
    static String normalize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.equals("bank-chest") || s.equals("bank")) return "bank-chest";
        if (s.startsWith("#")) return s.replace("minecraft:", "");
        Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (id == null) return null;
        Block b = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (b == null || b == Blocks.AIR) return null;
        return ClaimsModule.blockKey(b);
    }

    // ------------------------------------------------------------------ tab completion

    List<String> complete(String cmd, Sender sender, String[] args) {
        List<String> out = new ArrayList<>();
        String last = args.length == 0 ? "" : args[args.length - 1];
        switch (cmd) {
            case "trust" -> {
                if (args.length == 1) {
                    out.add("public");
                    out.addAll(m.plugin.completeOthers(sender, last));
                } else if (args.length == 2) out.addAll(List.of("access", "container", "build", "manager"));
            }
            case "untrust" -> {
                if (args.length == 1) {
                    out.add("public");
                    if (sender.isPlayer()) {
                        ServerPlayer p = sender.player();
                        Claim here = m.registry.at(p.level(), p.getBlockX(), p.getBlockZ());
                        List<Claim> claims = here != null ? List.of(here) : m.registry.ownedBy(p.getUUID());
                        for (Claim c : claims) out.addAll(c.trustNames.values());
                    }
                }
            }
            case "unclaim" -> {
                if (args.length == 1) out.add("all");
            }
            case "claimblocks" -> {
                if (args.length == 1 && (sender.hasPermission(ClaimsModule.BLOCKS_ADMIN) || sender.hasPermission(ClaimsModule.ADMIN))) {
                    out.addAll(m.plugin.completePlayers(sender, last));
                } else if (args.length == 2 && sender.hasPermission(ClaimsModule.BLOCKS_ADMIN)) out.addAll(List.of("give", "take", "set"));
                else if (args.length == 3 && sender.hasPermission(ClaimsModule.BLOCKS_ADMIN)) out.addAll(List.of("16", "64", "256"));
            }
            default -> completeClaim(sender, args, out, last);
        }
        String l = last.toLowerCase(Locale.ROOT);
        return out.stream().distinct().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(l)).toList();
    }

    private void completeClaim(Sender sender, String[] args, List<String> out, String last) {
        boolean admin = sender.hasPermission(ClaimsModule.ADMIN);
        if (args.length == 1) {
            out.addAll(List.of("chunk", "list", "info", "name", "abandon", "flags", "show", "mode", "transfer", "trust", "untrust", "unclaim"));
            if (sender.hasPermission(ClaimsModule.BYPASS)) out.add("bypass");
            if (admin) out.add("admin");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "flags", "flag" -> {
                if (args.length == 2) for (ClaimFlag f : ClaimFlag.values()) out.add(f.key());
                else if (args.length == 3) out.addAll(List.of("on", "off"));
            }
            case "mode" -> {
                if (args.length == 2) {
                    out.addAll(List.of("rect", "chunk", "circle"));
                    if (admin) out.add("admin");
                }
            }
            case "transfer", "trust" -> {
                if (args.length == 2) out.addAll(m.plugin.completeOthers(sender, last));
                else if (args.length == 3 && sub.equals("trust")) out.addAll(List.of("access", "container", "build", "manager"));
            }
            case "list" -> {
                if (args.length == 2 && admin) out.addAll(m.plugin.completePlayers(sender, last));
            }
            case "abandon" -> {
                if (args.length == 2) out.add("confirm");
            }
            case "admin" -> {
                if (!admin) return;
                if (args.length == 2) {
                    out.addAll(List.of("on", "off", "create", "allow", "disallow", "preset", "greeting", "delete", "list"));
                    return;
                }
                String a = args[1].toLowerCase(Locale.ROOT);
                List<String> names = m.registry.all().stream().filter(Claim::admin).map(c -> c.name.replace(' ', '_')).toList();
                switch (a) {
                    case "allow", "disallow" -> {
                        if (args.length == 3) out.addAll(names);
                        else if (args.length == 4) out.addAll(List.of("use", "break"));
                        else if (args.length == 5) {
                            out.add("bank-chest");
                            String l = last.toLowerCase(Locale.ROOT);
                            if (l.length() >= 2) {
                                for (Block b : BuiltInRegistries.BLOCK) {
                                    String k = ClaimsModule.blockKey(b);
                                    if (k.startsWith(l)) out.add(k);
                                    if (out.size() > 60) break;
                                }
                            }
                        }
                    }
                    case "preset" -> {
                        if (args.length == 3) out.addAll(m.settings.presets.keySet());
                        else if (args.length == 4) out.addAll(names);
                    }
                    case "greeting", "delete", "remove" -> {
                        if (args.length == 3) out.addAll(names);
                    }
                    case "create" -> {
                        if (args.length == 3) out.addAll(List.of("Spawn", "Bank"));
                    }
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
    }
}
