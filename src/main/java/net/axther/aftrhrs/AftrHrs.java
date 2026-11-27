package net.axther.aftrhrs;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Pdc;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Aftr Hrs: the server, as one mod. Registers content, boots the Feather core and its modules. */
public final class AftrHrs implements ModInitializer {
    public static final String ID = "aftrhrs";
    public static final Logger LOG = LoggerFactory.getLogger("Aftr Hrs");

    public static net.minecraft.resources.Identifier id(String path) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(ID, path);
    }

    @Override
    public void onInitialize() {
        Pdc.init();
        net.axther.aftrhrs.migrate.PaperWorldFix.run(); // before the server opens a world saved by Paper
        net.axther.aftrhrs.migrate.FeatherImport.init(); // before Feather.boot(): runs first on server start
        net.axther.aftrhrs.entity.ArcanaEntities.init();
        net.axther.aftrhrs.feature.furniture.FurnitureEntities.declare(); // curio / gadget / power block entities
        net.axther.aftrhrs.feature.christmas.ChristmasContent.declare(); // fir tree, presents, mistletoe
        net.axther.aftrhrs.content.ArcanaContent.init();
        net.axther.aftrhrs.feature.shop.KeeperContent.init(); // counter keepers: entity types + items (Civic tab)
        net.axther.aftrhrs.feature.furniture.FurnitureEntities.register();
        net.axther.aftrhrs.feature.christmas.ChristmasContent.register();
        net.axther.aftrhrs.feature.relics.RelicsInit.init(); // thrown relic trident type, backpack payload
        net.axther.aftrhrs.feature.travel.CarriedBlock.init(); // carried-block attachment (class load)
        net.axther.aftrhrs.feature.cosmetics.Cosmetics.init(); // outfit attachment (class load), settings + toggle payloads
        net.axther.aftrhrs.feature.bossfx.ShakePayload.register(); // boss camera shake
        net.axther.aftrhrs.feature.bossfx.HeavePayload.register(); // boss quake / fissure, drawn by the client
        net.axther.aftrhrs.entity.ability.FxLinePayload.register(); // particle lines and rings, drawn by the client
        net.axther.aftrhrs.feature.travel.RtpModule.initTicketType(); // /rtp search ticket (before the registries freeze)
        net.axther.aftrhrs.feature.graves.GraveModule.initTicketType(); // grave expiry chunk ticket
        net.axther.aftrhrs.feature.fairylights.FairyLights.init(); // fairy lights: hooks, knots, strands, lights
        net.axther.aftrhrs.feature.smithing.Smithing.init(); // Anvil & Ember: smithing components, parts, tools
        Feather feather = Feather.boot();
        net.axther.aftrhrs.migrate.Legacy.init();
        net.axther.aftrhrs.net.Companion.init(feather);
        net.axther.aftrhrs.net.VersionCheck.init(); // first configuration task: same mod version on both sides
        net.axther.aftrhrs.update.ServerUpdates.init(); // dedicated server only (updater.server in config.yml)
        Modules.register(feather);
    }
}
