package net.axther.aftrhrs;

import net.axther.aftrhrs.core.Feather;

/**
 * Every Feather module, in start order: exactly Feather.onEnable's order on Paper (src/net/axther/feather/Feather.java).
 * Each line is {@code feather.register("<config.yml modules key>", () -> new XModule(feather))}; a null key = always on.
 * Fabric listeners a module registers in enable() run in this order too, so keep it (see mod/PARITY.md).
 */
final class Modules {
    private Modules() {
    }

    static void register(Feather feather) {
        // Feather's own: /feather (the hub, reload) and the Shift+F hotkey. Always on (Paper: the plugin itself).
        feather.register(null, () -> new net.axther.aftrhrs.feature.core.CoreModule(feather));
        // MODULES
        // First, so its chat/command guards run before every other module's.
        feather.register("auth", () -> new net.axther.aftrhrs.feature.auth.AuthModule(feather));
        // Before the menus: Dialogs asks it whether a player runs the mod. Payloads are registered in AftrHrs.onInitialize.
        feather.register("companion", net.axther.aftrhrs.net.Companion.module());
        feather.register("teleport-requests", () -> new net.axther.aftrhrs.feature.travel.TpaModule(feather));
        feather.register("homes", () -> new net.axther.aftrhrs.feature.travel.HomesModule(feather));
        feather.register("nicknames", () -> new net.axther.aftrhrs.feature.chat.NickModule(feather));
        feather.register("hat", () -> new net.axther.aftrhrs.feature.travel.HatModule(feather));
        feather.register("cosmetics", () -> new net.axther.aftrhrs.feature.cosmetics.CosmeticsModule(feather));
        feather.register("chat", () -> new net.axther.aftrhrs.feature.chat.ChatModule(feather));
        feather.register("spawn-warps-back", () -> new net.axther.aftrhrs.feature.travel.PlacesModule(feather));
        feather.register("messaging", () -> new net.axther.aftrhrs.feature.chat.MessagingModule(feather));
        feather.register("staff-commands", () -> new net.axther.aftrhrs.feature.staff.StaffModule(feather));
        feather.register("afk", () -> new net.axther.aftrhrs.feature.travel.AfkModule(feather));
        feather.register("moderation", () -> new net.axther.aftrhrs.feature.moderation.ModerationModule(feather));
        feather.register("utilities", () -> new net.axther.aftrhrs.feature.staff.UtilityModule(feather));
        feather.register("tab-list", () -> new net.axther.aftrhrs.feature.display.TabModule(feather));
        feather.register("nametags", () -> new net.axther.aftrhrs.feature.display.NametagModule(feather));
        feather.register("server-list-motd", () -> new net.axther.aftrhrs.feature.display.MotdModule(feather));
        feather.register("flight-trails", () -> new net.axther.aftrhrs.feature.travel.TrailsModule(feather));
        feather.register("random-teleport", () -> new net.axther.aftrhrs.feature.travel.RtpModule(feather));
        feather.register("sit", () -> new net.axther.aftrhrs.feature.travel.SitModule(feather));
        feather.register("carry", () -> new net.axther.aftrhrs.feature.travel.CarryModule(feather));
        feather.register("chat-share", () -> new net.axther.aftrhrs.feature.chat.ChatShare(feather));
        feather.register("tool-stats", () -> new net.axther.aftrhrs.feature.toolstats.ToolStatsModule(feather));
        feather.register("analytics", () -> new net.axther.aftrhrs.feature.analytics.AnalyticsModule(feather));
        feather.register("bounties", () -> new net.axther.aftrhrs.feature.bounty.BountyModule(feather));
        feather.register("custom-recipes", () -> new net.axther.aftrhrs.feature.recipes.RecipesModule(feather));
        feather.register("death-point", () -> new net.axther.aftrhrs.feature.travel.DeathPointModule(feather));
        feather.register("bank", () -> new net.axther.aftrhrs.feature.bank.BankModule(feather));
        feather.register("calendar", () -> new net.axther.aftrhrs.feature.calendar.CalendarModule(feather));
        feather.register("mail", () -> new net.axther.aftrhrs.feature.mail.MailModule(feather));
        net.axther.aftrhrs.feature.trade.TradeMenu.init(); // the trade window's menu type (registries freeze before modules start)
        feather.register("trade", () -> new net.axther.aftrhrs.feature.trade.TradeModule(feather));
        // graves before claims before chest locks (their click and break listeners run in this order).
        feather.register("graves", () -> new net.axther.aftrhrs.feature.graves.GraveModule(feather));
        feather.register("chatgames", () -> new net.axther.aftrhrs.feature.chatgames.ChatGamesModule(feather));
        feather.register("claims", () -> new net.axther.aftrhrs.feature.claims.ClaimsModule(feather));
        feather.register("holograms", net.axther.aftrhrs.feature.holograms.HologramsModule.factory(feather));
        feather.register("storage", net.axther.aftrhrs.feature.storage.StorageModule.factory(feather));
        // Coal generators and the machines they power (Encased Fan). Settings: power.yml
        feather.register("power", () -> new net.axther.aftrhrs.feature.furniture.power.PowerModule(feather));
        // General Store, sell-back counters and market stalls; money goes through the bank. Settings: shop.yml
        feather.register("shop", () -> new net.axther.aftrhrs.feature.shop.ShopModule(feather));
        // Pedestals, hat stands, the statue, flower boxes, doormats. Settings: curios.yml
        feather.register("curios", () -> new net.axther.aftrhrs.feature.furniture.curios.CuriosModule(feather));
        // Chess tables: two players, the mod's chess screen, the position drawn on the table. docs/chess.md
        feather.register("chess", () -> new net.axther.aftrhrs.feature.chess.ChessModule(feather));
        // Christmas set: decoratable fir tree, gift boxes, snow globe, advent wreath, mistletoe (feature/christmas)
        feather.register("christmas", () -> new net.axther.aftrhrs.feature.christmas.ChristmasModule(feather));
        feather.register(null, () -> new net.axther.aftrhrs.feature.chat.NickCommands(feather)); // always on (Feather started it unconditionally)
        feather.register("chest-locks", () -> new net.axther.aftrhrs.feature.locks.LocksModule(feather));
        feather.register("taming-guide", () -> new net.axther.aftrhrs.feature.codex.CodexModule(feather));
        // Relic item mechanics (ex-Oraxen: aura, spells, spear lunge, glow, backpacks on the back). Always on.
        feather.register(null, () -> new net.axther.aftrhrs.feature.relics.RelicsModule(feather));
        feather.register("arcana-tools", () -> new net.axther.aftrhrs.feature.tools.ToolsModule(feather));
        // The Aftr Hrs enchantments' effects (the enchantments themselves are data: data/aftrhrs/enchantment). docs/enchants.md
        feather.register("enchants", () -> new net.axther.aftrhrs.feature.enchants.EnchantsModule(feather));
        // Boss relics Cinderplume / Galeheart (tools.yml relics.*; on Paper part of arcana-tools, follows modules.arcana-tools).
        feather.register(null, () -> new net.axther.aftrhrs.feature.altars.BossRelics(feather));
        // Spikes, feather block, sacks, goblets, jars, cages, ropes. Settings: gadgets.yml
        feather.register("gadgets", () -> new net.axther.aftrhrs.feature.furniture.gadgets.GadgetsModule(feather));
        // Display-entity boss shockwaves (featherquake / featherfissure / featherpillar) and boss-minion ally protection.
        feather.register("boss-fx", () -> new net.axther.aftrhrs.feature.bossfx.BossFxModule(feather));
        // Before the altars, which report their kills to it.
        feather.register("boss-board", () -> new net.axther.aftrhrs.feature.bossboard.BossBoardModule(feather));
        // Summoning Altars: altars.yml, data altars-data.yml.
        feather.register("boss-altars", () -> new net.axther.aftrhrs.feature.altars.AltarsModule(feather));
        feather.register("pets", () -> new net.axther.aftrhrs.feature.pets.PetsModule(feather));
        // Wild livestock, wildlife and undead: harvests, breeding, stations, bestiary, Restless Moon. Settings: wildlife.yml
        feather.register("wildlife", () -> new net.axther.aftrhrs.feature.wildlife.WildlifeModule(feather));
        feather.register("quests", () -> new net.axther.aftrhrs.feature.quests.QuestsModule(feather));
        // Fishing (Coast & Harvest): custom fish, journal, records, plaques, tanks, crab pots, tournaments. Settings: fishing.yml
        feather.register("fishing", () -> new net.axther.aftrhrs.feature.fishing.FishingModule(feather));
        // Spawn furniture: hubs, fountains, string lights, Hushwing. Settings: civic.yml
        feather.register("civic", () -> new net.axther.aftrhrs.feature.civic.CivicModule(feather));
        // Fairy lights (based on Fairy Lights by pau101, MIT): strand limits. Settings: fairy-lights.yml
        feather.register(null, () -> new net.axther.aftrhrs.feature.fairylights.FairyLightsModule(feather));
        // Anvil & Ember smithing: heat speed, Edge, burns; the smithing recipes for JEI. Settings: smithing.yml
        feather.register(null, () -> new net.axther.aftrhrs.feature.smithing.SmithingModule(feather));
        // Coast & Harvest farming: Arcana crops, quality, compost, seaweed lines, cooking, the Harvest Fair. Settings: farming.yml
        feather.register("farming", () -> new net.axther.aftrhrs.feature.farming.FarmingModule(feather));
        // After the modules it reports for.
        feather.register("advancements", () -> new net.axther.aftrhrs.feature.advancements.AdvancementsModule(feather));
        // modelengine-pack-bridge: dropped (models, textures and fonts ship in the mod jar).
        // The Companion's places list (homes, warps, spawn, death point, graves); last, as on Paper. Runs while modules.companion is on.
        feather.register(null, () -> new net.axther.aftrhrs.feature.travel.PlacesCompanion(feather));
    }
}
