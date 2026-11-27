package net.axther.aftrhrs.mixin;

import com.google.common.collect.ImmutableMap;
import net.axther.aftrhrs.feature.advancements.AdvancementInjector;
import net.axther.aftrhrs.feature.advancements.AdvancementsModule;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Feather's advancements (advancements.yml) in the server's advancement list; see {@link AdvancementInjector}. */
@Mixin(ServerAdvancementManager.class)
abstract class ServerAdvancementManagerMixin implements AdvancementInjector {
    @Shadow @Final @Mutable private Map<Identifier, AdvancementHolder> advancements;
    @Shadow @Final @Mutable private AdvancementTree tree;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void aftrhrs$addFeather(HolderLookup.Provider registries, CallbackInfo ci) {
        Collection<AdvancementHolder> extra = AdvancementInjector.Extra.list;
        if (!extra.isEmpty()) aftrhrs$replace(AdvancementsModule.NAMESPACE, extra);
    }

    @Override
    public void aftrhrs$replace(String namespace, Collection<AdvancementHolder> extra) {
        Map<Identifier, AdvancementHolder> map = new LinkedHashMap<>();
        for (Map.Entry<Identifier, AdvancementHolder> e : advancements.entrySet()) {
            if (!e.getKey().getNamespace().equals(namespace)) map.put(e.getKey(), e.getValue());
        }
        for (AdvancementHolder h : extra) map.put(h.id(), h);
        advancements = ImmutableMap.copyOf(map);
        AdvancementTree t = new AdvancementTree();
        t.addAll(advancements.values());
        t.repositionNodes();
        tree = t;
    }
}
