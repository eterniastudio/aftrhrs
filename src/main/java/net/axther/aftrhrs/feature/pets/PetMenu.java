package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The /pets chest (one plushie per pet: left-click for its actions, right-click to summon or revive) and the pet's
 * action dialogs (summon, stay/follow, work, stable, color, rename, release, revive).
 */
final class PetMenu {
    private static final int PER_PAGE = 45;

    private final PetsModule m;

    PetMenu(PetsModule module) {
        this.m = module;
    }

    // ------------------------------------------------------------------ the chest

    void open(ServerPlayer viewer, int page) {
        if (m.refuse(viewer) || m.handOff(viewer, "pets")) return;
        List<Pet> pets = m.store().of(viewer.getUUID());
        pets.removeIf(Pet::stabled);   // the stable has its own screen
        int pages = Math.max(1, (pets.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        Gui gui = Gui.create(6, m.msg("gui.title", Text.unparsed("page", current + 1), Text.unparsed("pages", pages),
                Text.unparsed("count", m.countActive(viewer.getUUID())), Text.unparsed("cap", m.capLabel(viewer))));
        int start = current * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < pets.size(); i++) {
            Pet p = pets.get(start + i);
            UUID id = p.id;
            gui.set(i, petIcon(viewer, p), click -> {
                m.text().sound(viewer, "click");
                Pet pet = m.store().get(id);
                if (pet == null) {
                    open(viewer, current);
                    return;
                }
                if (click.isRightClick()) {
                    viewer.closeContainer();
                    if (pet.alive()) summon(viewer, pet);
                    else m.revive(viewer, pet, null);
                } else {
                    openActions(viewer, pet);
                }
            });
        }
        if (pets.isEmpty()) gui.set(22, icon(Items.LEAD, "gui.empty", "gui.empty-lore"));
        // The shared bottom row (labels from messages.yml gui.nav); this menu's own buttons sit at 48, 50 and 51.
        Gui.navBar(gui, m.feather().text(), current > 0, c -> open(viewer, current - 1), current < pages - 1, c -> open(viewer, current + 1),
                Icons.icon(Items.FEATHER, "gui.nav.hub", "gui.nav.hub-lore"), c -> {
                    viewer.closeContainer();
                    m.feather().openHub(viewer);
                });
        gui.set(48, icon(Items.BOOK, "gui.info", "gui.info-lore",
                Text.unparsed("count", m.countActive(viewer.getUUID())), Text.unparsed("cap", m.capLabel(viewer))));
        int parked = m.stable().parked(viewer.getUUID()).size();
        gui.set(51, icon(Items.HAY_BLOCK, "gui.stable", "gui.stable-lore", Text.unparsed("count", parked),
                Text.unparsed("capacity", m.stable().capacityLabel(viewer))), c -> click(viewer, () -> m.stable().open(viewer, true)));
        // The creature codex has its own front door now (/codex, the hub, the guide book); /pets is pet management only.
        if (pets.stream().anyMatch(p -> p.alive() && !p.stay)) {
            gui.set(50, icon(Items.GOAT_HORN, "gui.summon-all", "gui.summon-all-lore"), c -> click(viewer, () -> {
                viewer.closeContainer();
                if (m.bringAll(viewer) == 0) m.send(viewer, "summon.none");
            }));
        }
        gui.open(viewer);
        m.text().sound(viewer, "menu-open");
    }

    private void click(ServerPlayer viewer, Runnable r) {
        m.text().sound(viewer, "click");
        r.run();
    }

    private ItemStack petIcon(ServerPlayer viewer, Pet p) {
        PetSpecies s = m.species(p.species);
        ItemStack stack = s == null ? null : PlushItems.build(s.plush());
        if (stack == null) {
            Item item = s == null ? Items.LEAD : switch (s.kind()) {
                case MOUNT -> Items.SADDLE;
                case HOSTILE -> Items.BLAZE_POWDER;
                default -> Items.LEAD;
            };
            stack = new ItemStack(item);
        }
        TagResolver tags = m.petTags(p);
        List<Component> lore = new ArrayList<>();
        lore.addAll(m.lines("gui.pet-species", tags));
        if (m.variants().special(p)) lore.addAll(m.lines("gui.pet-variant", m.variants().tags(p)));
        lore.addAll(m.lines("gui.pet-bond", tags, bondTags(p)));
        lore.add(m.perks().summary(p));
        if (p.alive()) {
            Entity e = m.entity(p);
            double hp = p.health, max = p.maxHealth;
            if (e instanceof LivingEntity le) {
                hp = le.getHealth();
                max = le.getMaxHealth();
            }
            lore.addAll(m.lines("gui.pet-health", Text.unparsed("health", fmt(hp)), Text.unparsed("max", fmt(max))));
            if (p.working()) lore.addAll(m.lines("gui.pet-working", m.work().tags(p)));
            else lore.addAll(m.lines(p.stay ? "gui.pet-staying" : "gui.pet-following"));
            lore.addAll(m.lines("gui.pet-where", where(viewer, p, e)));
            lore.addAll(m.lines("gui.pet-actions"));
        } else {
            Pet.Spot at = p.spot;
            lore.addAll(m.lines("gui.pet-fallen", Text.unparsed("world", m.worldName(at)), coords(at)));
            lore.addAll(m.lines(m.perks().freeReviveReady(p) ? "gui.pet-actions-fallen-free"
                    : m.hasPlushie(viewer, p) ? "gui.pet-actions-fallen" : "gui.pet-actions-fallen-no-plush", tags));
        }
        return Icons.icon(stack).count(1)
                .name(withTitle(p, m.text().item(m.raw(p.alive() ? "gui.pet-name" : "gui.pet-name-fallen"), tags)))
                .lore(lore).glint(p.alive() && p.level >= m.maxLevel()).flags().build();
    }

    /** The name plus the Devoted title (bond perk), e.g. "Biscuit · Devoted". */
    private Component withTitle(Pet p, Component name) {
        String title = m.perks().title();
        if (!m.perks().has(p, Perks.Perk.DEVOTED) || title == null || title.isBlank()) return name;
        return name.append(m.text().item(m.raw("perks.devoted.suffix"), Text.unparsed("title", title)));
    }

    private TagResolver where(ServerPlayer viewer, Pet p, Entity e) {
        boolean near = e != null && e.level() == viewer.level();
        Object d = near ? (int) Math.round(e.distanceTo(viewer)) : "?";
        String world = e != null ? m.worldName(e.level()) : m.worldName(p.spot);
        return TagResolver.resolver(Text.unparsed("world", world), Text.unparsed("distance", d),
                Text.component("where", whereText(viewer, p, e)));
    }

    /** "12 blocks away", "in The Nether" or "in Overworld (far away)" for a living pet ({@code e}: its entity or null). */
    Component whereText(ServerPlayer viewer, Pet p, Entity e) {
        if (e != null && e.level() == viewer.level()) {
            return m.msg("gui.where-near", Text.unparsed("distance", (int) Math.round(e.distanceTo(viewer))));
        }
        String world = e != null ? m.worldName(e.level()) : m.worldName(p.spot);
        return m.msg(e != null ? "gui.where-world" : "gui.where-far", Text.unparsed("world", world));
    }

    private static TagResolver coords(Pet.Spot at) {
        return TagResolver.resolver(Text.unparsed("x", at == null ? "?" : (int) Math.floor(at.x())),
                Text.unparsed("y", at == null ? "?" : (int) Math.floor(at.y())),
                Text.unparsed("z", at == null ? "?" : (int) Math.floor(at.z())));
    }

    /** {@code <bar>}, {@code <next>} (XP for the next level) and {@code <progress>} for the bond line. */
    TagResolver bondTags(Pet p) {
        int next = m.nextLevelXp(p);
        int cur = m.levelXp(p.level);
        int filled = next < 0 ? 10 : (int) Math.floor(10.0 * Math.max(0, p.xp - cur) / Math.max(1, next - cur));
        filled = Math.max(0, Math.min(10, filled));
        Component bar = m.text().parse("<accent>" + "■".repeat(filled) + "</accent><dim>" + "■".repeat(10 - filled) + "</dim>");
        return TagResolver.resolver(Text.component("bar", bar), Text.unparsed("next", next < 0 ? "max" : Integer.toString(next)),
                Text.component("progress", m.msg(next < 0 ? "gui.bond-max" : "gui.bond-progress",
                        Text.unparsed("xp", p.xp), Text.unparsed("next", next))));
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? Integer.toString((int) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    private ItemStack icon(Item item, String nameKey, String loreKey, TagResolver... r) {
        Icons.Icon i = Icons.icon(item).flags();
        if (nameKey == null) return i.hideTooltip().build();
        i.name(m.text().item(m.raw(nameKey), r));
        if (loreKey != null) i.lore(m.lines(loreKey, r));
        return i.build();
    }

    // ------------------------------------------------------------------ dialogs

    private void summon(ServerPlayer viewer, Pet p) {
        m.bring(p, viewer, true, o -> m.reportSummon(viewer, p, o));
    }

    void openActions(ServerPlayer viewer, Pet p) {
        UUID id = p.id;
        TagResolver tags = m.petTags(p);
        Dialogs.Menu menu = m.dialogs().menu(withTitle(p, m.msg("dialog.title", tags)));
        Entity e = m.entity(p);
        if (p.stabled()) {
            menu.text(m.msg("dialog.body-stabled", tags, bondTags(p)));
            if (m.variants().special(p)) menu.text(m.msg("dialog.variant", tags));
            menu.button(m.msg("dialog.stable-out"), m.msg("dialog.stable-out-tooltip"), 148, pl -> withPet(pl, id, pet -> {
                String err = m.stable().bringOut(pl, pet);
                if (err != null) {
                    m.send(pl, err, m.petTags(pet), Text.unparsed("cap", m.capLabel(pl)));
                    m.sound(pl, "error");
                    openActions(pl, pet);
                    return;
                }
                m.send(pl, "stable.out", m.petTags(pet));
                m.stable().open(pl, true);
            }));
            menu.button(m.msg("dialog.rename"), m.msg("dialog.rename-tooltip"), 148, pl -> withPet(pl, id, pet -> openRename(pl, pet, null)));
            menu.button(m.msg("dialog.release"), m.msg("dialog.release-tooltip"), 148, pl -> withPet(pl, id, pet -> openRelease(pl, pet)));
            menu.columns(2);
            menu.exit(m.text().msg("menu.back"), pl -> m.stable().open(pl, true));
            menu.open(viewer);
            m.text().sound(viewer, "menu-open");
            return;
        }
        if (p.alive()) {
            Component state = p.working() ? m.msg("dialog.state-working", m.work().tags(p))
                    : m.msg(p.stay ? "dialog.state-staying" : "dialog.state-following");
            menu.text(m.msg("dialog.body", tags, bondTags(p), where(viewer, p, e), Text.component("state", state)));
            if (m.variants().special(p)) menu.text(m.msg("dialog.variant", tags));
            if (p.working()) menu.text(m.work().describe(p));
            menu.button(m.msg("dialog.summon"), m.msg("dialog.summon-tooltip"), 148, pl -> withPet(pl, id, pet -> summon(pl, pet)));
            if (e != null) {
                boolean following = !p.stay;
                menu.button(m.msg(p.stay ? "dialog.follow" : "dialog.stay"), m.msg("dialog.stay-tooltip"), 148,
                        pl -> withPet(pl, id, pet -> {
                            if (!m.setStay(pl, pet, following)) m.send(pl, "stay.not-loaded", m.petTags(pet));
                            openActions(pl, pet);
                        }));
                if (m.work().enabled() && !p.working()) {
                    menu.button(m.msg("dialog.work"), m.msg("dialog.work-tooltip", m.work().tags(p), Text.component("job", m.work().describe(p))), 148,
                            pl -> withPet(pl, id, pet -> {
                                String err = m.work().order(pl, pet);
                                if (err != null) {
                                    m.send(pl, err, m.petTags(pet));
                                    m.sound(pl, "error");
                                } else {
                                    m.send(pl, "work.started", m.petTags(pet), m.work().tags(pet), Text.component("job", m.work().describe(pet)));
                                    m.sound(pl, "work");
                                }
                                openActions(pl, pet);
                            }));
                }
            }
            Component breed = m.breedingLine(p);
            if (breed != null) menu.text(breed);
        } else {
            menu.text(m.msg("dialog.body-fallen", tags, bondTags(p), Text.unparsed("world", m.worldName(p.spot)), coords(p.spot)));
            if (m.variants().special(p)) menu.text(m.msg("dialog.variant", tags));
            menu.button(m.msg("dialog.revive"), m.msg(m.perks().freeReviveReady(p) ? "dialog.revive-tooltip-free"
                            : m.hasPlushie(viewer, p) ? "dialog.revive-tooltip" : "dialog.revive-tooltip-missing", tags),
                    148, pl -> withPet(pl, id, pet -> m.revive(pl, pet, null)));
        }
        Component perkText = m.perks().describe(p);
        if (!perkText.equals(Component.empty())) menu.text(perkText);
        if (p.alive()) {
            menu.button(m.msg("dialog.stable-in"), m.msg("dialog.stable-in-tooltip", Text.unparsed("count", m.stable().parked(viewer.getUUID()).size()),
                    Text.unparsed("capacity", m.stable().capacityLabel(viewer))), 148, pl -> withPet(pl, id, pet -> {
                String err = m.stable().park(pl, pet);
                if (err != null) {
                    m.send(pl, err, m.petTags(pet), Text.unparsed("capacity", m.stable().capacityLabel(pl)));
                    m.sound(pl, "error");
                    openActions(pl, pet);
                    return;
                }
                m.send(pl, "stable.in", m.petTags(pet), Text.unparsed("count", m.stable().parked(pl.getUUID()).size()),
                        Text.unparsed("capacity", m.stable().capacityLabel(pl)));
                open(pl, 0);
            }));
        }
        if (m.perks().has(p, Perks.Perk.AFFECTION) && !m.perks().colors().isEmpty()) {
            menu.button(m.msg("dialog.color"), m.msg("dialog.color-tooltip"), 148, pl -> withPet(pl, id, pet -> openColor(pl, pet)));
        }
        menu.button(m.msg("dialog.rename"), m.msg("dialog.rename-tooltip"), 148, pl -> withPet(pl, id, pet -> openRename(pl, pet, null)));
        menu.button(m.msg("dialog.release"), m.msg("dialog.release-tooltip"), 148, pl -> withPet(pl, id, pet -> openRelease(pl, pet)));
        // the one link left to the codex: this pet's species page (its back button returns here)
        if (m.codex().enabled() && m.codex().has(p.species)) {
            String species = p.species;
            menu.button(m.msg("dialog.codex"), m.msg("dialog.codex-tooltip", tags), 148,
                    pl -> m.codex().open(pl, null, species, back -> withPet(back, id, pet -> openActions(back, pet))));
        }
        menu.columns(2);
        menu.exit(m.text().msg("menu.back"), pl -> open(pl, 0));
        menu.open(viewer);
        m.text().sound(viewer, "menu-open");
    }

    private void openRename(ServerPlayer viewer, Pet p, Component error) {
        UUID id = p.id;
        TagResolver tags = m.petTags(p);
        Dialogs.Menu menu = m.dialogs().menu(m.msg("rename.title", tags));
        menu.text(m.msg("rename.body", tags, Text.unparsed("max", m.nameMax())));
        if (error != null) menu.text(error);
        menu.input(Dialogs.Input.text("name", m.msg("rename.input")).initial(p.name == null ? "" : p.name)
                .maxLength(Math.max(1, m.nameMax())).width(260).build());
        menu.button(m.msg("rename.save"), null, 148, (pl, view) -> withPet(pl, id, pet -> {
            String name = view.getText("name");
            String err = m.rename(pet, name == null ? "" : name);
            if (err != null) {
                m.sound(pl, "error");
                openRename(pl, pet, m.msg(err, Text.unparsed("max", m.nameMax())));
                return;
            }
            m.send(pl, "rename.done", m.petTags(pet));
            openActions(pl, pet);
        }));
        if (p.name != null) {
            menu.button(m.msg("rename.reset"), null, 148, pl -> withPet(pl, id, pet -> {
                m.rename(pet, null);
                m.send(pl, "rename.done", m.petTags(pet));
                openActions(pl, pet);
            }));
        }
        menu.columns(2);
        menu.exit(m.text().msg("menu.back"), pl -> withPet(pl, id, pet -> openActions(pl, pet)));
        menu.open(viewer);
    }

    /** Name color picker (Affection perk): one button per color in perks.affection.colors, plus "no color". */
    private void openColor(ServerPlayer viewer, Pet p) {
        UUID id = p.id;
        Dialogs.Menu menu = m.dialogs().menu(m.msg("color.title", m.petTags(p)));
        menu.text(m.msg("color.body", m.petTags(p)));
        for (var en : m.perks().colors().entrySet()) {
            String hex = en.getValue();
            menu.button(m.text().parse("<" + hex + ">■ " + MiniMessage.miniMessage().escapeTags(en.getKey())), null, 96, pl -> withPet(pl, id, pet -> {
                if (!m.perks().has(pet, Perks.Perk.AFFECTION)) {
                    openActions(pl, pet);
                    return;
                }
                pet.color = hex;
                m.store().markDirty();
                m.refresh(pet);
                m.send(pl, "color.done", m.petTags(pet));
                openActions(pl, pet);
            }));
        }
        menu.button(m.msg("color.none"), null, 96, pl -> withPet(pl, id, pet -> {
            pet.color = null;
            m.store().markDirty();
            m.refresh(pet);
            m.send(pl, "color.done", m.petTags(pet));
            openActions(pl, pet);
        }));
        menu.columns(3);
        menu.exit(m.text().msg("menu.back"), pl -> withPet(pl, id, pet -> openActions(pl, pet)));
        menu.open(viewer);
    }

    private void openRelease(ServerPlayer viewer, Pet p) {
        UUID id = p.id;
        TagResolver tags = m.petTags(p);
        Dialogs.Menu menu = m.dialogs().menu(m.msg("release.title", tags));
        menu.text(m.msg(p.stabled() ? "release.body-stabled" : p.alive() ? "release.body" : "release.body-fallen", tags));
        menu.button(m.msg("release.confirm"), null, 148, pl -> withPet(pl, id, pet -> {
            String name = m.displayName(pet);
            boolean stabled = pet.stabled();
            String err = m.release(pl, pet);
            if (err != null) {
                m.send(pl, err, m.petTags(pet));
                m.sound(pl, "error");
                openActions(pl, pet);
                return;
            }
            m.send(pl, "release.done", Text.unparsed("pet", name));
            m.text().sound(pl, "deleted");
            if (stabled) m.stable().open(pl, true);
            else open(pl, 0);
        }));
        menu.exit(m.msg("release.cancel"), pl -> withPet(pl, id, pet -> openActions(pl, pet)));
        menu.open(viewer);
        m.sound(viewer, "error");
    }

    /** Runs {@code action} with the current record if the viewer still owns it; reopens the list otherwise. */
    private void withPet(ServerPlayer viewer, UUID id, Consumer<Pet> action) {
        Pet p = m.store().get(id);
        if (p == null || !p.owner.equals(viewer.getUUID())) {
            open(viewer, 0);
            return;
        }
        action.accept(p);
    }
}
