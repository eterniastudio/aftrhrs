package net.axther.aftrhrs.gametest.econ;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.graves.GraveModule;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Reads (and clears) a player's graves for tests. */
final class Graves {
    private Graves() {
    }

    static GraveModule module() {
        return Feather.get().module(GraveModule.class);
    }

    @SuppressWarnings("unchecked")
    static List<Object> of(UUID owner) {
        GraveModule m = module();
        return m == null ? List.of() : (List<Object>) R.call(m, "of", owner);
    }

    @SuppressWarnings("unchecked")
    static int count(UUID owner, Item item) {
        int n = 0;
        for (Object g : of(owner)) {
            for (Object e : entries(g)) {
                ItemStack s = item(e);
                if (s != null && s.is(item)) n += s.getCount();
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    static List<Object> entries(Object grave) {
        return (List<Object>) R.get(grave, "items");
    }

    /** A grave entry's stack (null once taken). */
    static ItemStack item(Object entry) {
        return (ItemStack) R.call(entry, "item");
    }

    /** Writes graves.yml and reads this grave back from the file, as the next start would (the module keeps its own). */
    static Object reread(Object grave) {
        GraveModule m = module();
        if (!(boolean) R.call(m, "save")) throw new IllegalStateException("graves.yml could not be written");
        String id = (String) R.get(grave, "id");
        Yaml file = Yaml.load((Path) R.get(m, "file"));
        Yaml section = file.getConfigurationSection("graves." + id);
        if (section == null) throw new IllegalStateException("grave " + id + " is not in graves.yml");
        return R.call(R.cls("net.axther.aftrhrs.feature.graves.Grave"), "load", id, section);
    }

    /** Removes a test player's graves (block, label and record), so they don't pile up across runs. */
    static void clear(UUID owner) {
        for (Object g : of(owner)) R.call(module(), "forget", g);
    }
}
