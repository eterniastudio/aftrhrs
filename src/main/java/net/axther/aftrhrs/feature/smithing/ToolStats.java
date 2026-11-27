package net.axther.aftrhrs.feature.smithing;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.DamageResistant;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link ToolBuild} into numbers (SMITHING.md 5.2-5.3) and writes them into vanilla components, so the game,
 * other mods and every tooltip see a normal tool:
 * <ul>
 *   <li>head stats are the weighted average of the head parts; handle multipliers the average of the handle/grip parts;</li>
 *   <li>then the tool type's multipliers, the quench, traits and modifiers, then quality (★ ×0.85 ... ★★★★★ ×1.15);</li>
 *   <li>attack damage is the tool's own base plus the head's, like vanilla (an iron pickaxe hits for 4, an iron sword for 6);</li>
 *   <li>modifiers that vanilla already knows (silk touch, smite, protection...) work through the real enchantment, hidden
 *       from the tooltip (the modifier line says it instead).</li>
 * </ul>
 */
public final class ToolStats {
    /** The numbers of one tool. */
    public record Stats(int durability, float miningSpeed, String tier, float attackDamage, float attackSpeed,
                        float armor, float toughness, float knockbackResistance,
                        float drawSpeed, float velocity, float accuracy, float projectileDamage) {
    }

    private static final String[] TIERS = {"wood", "gold", "stone", "copper", "iron", "diamond", "netherite"};

    private ToolStats() {
    }

    /** Stat multiplier for an average quality of {@code stars} (1-5). */
    public static float quality(float stars) {
        float[] q = {0.85f, 0.93f, 1.0f, 1.07f, 1.15f};
        float s = Math.max(1, Math.min(5, stars)) - 1;
        int lo = (int) Math.floor(s), hi = Math.min(4, lo + 1);
        return q[lo] + (q[hi] - q[lo]) * (s - lo);
    }

    static int tierRank(String tier) {
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(tier)) return i;
        return 0;
    }

    static String maxTier(String a, String b) {
        return tierRank(a) >= tierRank(b) ? a : b;
    }

    public static Stats compute(ToolBuild build) {
        SmithingDefs.ToolType type = SmithingDefs.tool(build.tool());
        if (type == null) return new Stats(1, 1, "wood", 0, 1, 0, 0, 0, 1, 1, 1, 0);
        float wsum = 0, dur = 0, speed = 0, atk = 0, tierWeight = -1;
        String tier = "wood";
        float hDur = 0, hSpeed = 0, hAtk = 0, hAtkSpd = 0;
        int handles = 0;
        float armor = 0, tough = 0, kb = 0;
        int plateDur = 0;
        float lw = 0, lDur = 0, lDraw = 0, lVel = 0, lAcc = 0, lDmg = 0;
        float gAcc = 1, gDmg = 0, gDur = 1;
        // quench: water +10% durability, oil +5% attack speed, brine +5% mining speed (steel's Tempered doubles them);
        // a metal part that was never quenched is soft: -10% durability
        float qDur = 0, qSpd = 0, qAtkSpd = 0;
        int metalParts = 0;
        int tempered = Mods.trait(build, "tempered");
        for (int i = 0; i < build.parts().size() && i < type.parts().size(); i++) {
            ToolBuild.PartEntry e = build.parts().get(i);
            SmithingDefs.Part part = SmithingDefs.part(e.part());
            SmithingDefs.Material m = SmithingDefs.material(e.material());
            if (part == null || m == null) continue;
            float w = type.parts().get(i).weight();
            if (m.metal()) {
                metalParts++;
                float k = tempered > 0 ? 2 : 1;
                switch (e.quench()) {
                    case "water" -> qDur += 0.10f * k;
                    case "fish_oil", "oil" -> qAtkSpd += 0.05f * k;
                    case "brine" -> qSpd += 0.05f * k;
                    case "frost" -> qDur += 0.05f * k;
                    default -> qDur -= 0.10f;
                }
            }
            switch (part.role()) {
                case "head" -> {
                    if (m.head() == null) continue;
                    wsum += w;
                    dur += m.head().durability() * w;
                    speed += m.head().speed() * w;
                    atk += m.head().attack() * w;
                    if (w > tierWeight) {
                        tierWeight = w;
                        tier = m.head().tier();
                    }
                }
                case "handle", "grip" -> {
                    SmithingDefs.Handle h = m.handle() != null ? m.handle() : SmithingDefs.Handle.NEUTRAL;
                    if (part.role().equals("grip") && m.grip() != null) {
                        gAcc = m.grip().accuracy();
                        gDmg = m.grip().damage();
                        gDur = m.grip().durability();
                        h = new SmithingDefs.Handle(m.grip().durability(), 1, 1, h.attackSpeed());
                    }
                    hDur += h.durability();
                    hSpeed += h.speed();
                    hAtk += h.attack();
                    hAtkSpd += h.attackSpeed();
                    handles++;
                }
                case "limb" -> {
                    if (m.limb() == null) continue;
                    lw += w;
                    lDur += m.limb().durability() * w;
                    lDraw += m.limb().drawSpeed() * w;
                    lVel += m.limb().velocity() * w;
                    lAcc += m.limb().accuracy() * w;
                    lDmg += m.limb().damage() * w;
                }
                case "plating" -> {
                    if (m.plating() == null) continue;
                    plateDur = m.plating().durability();
                    armor = m.plating().armor();
                    tough = m.plating().toughness();
                    kb = m.plating().knockbackResistance();
                }
                default -> {
                }
            }
        }
        if (wsum > 0) {
            dur /= wsum;
            speed /= wsum;
            atk /= wsum;
        }
        if (lw > 0) {
            lDur /= lw;
            lDraw /= lw;
            lVel /= lw;
            lAcc /= lw;
            lDmg /= lw;
        }
        float hd = handles == 0 ? 1 : hDur / handles, hs = handles == 0 ? 1 : hSpeed / handles;
        float ha = handles == 0 ? 1 : hAtk / handles, has = handles == 0 ? 1 : hAtkSpd / handles;
        if (metalParts > 0) {
            qDur /= metalParts;
            qSpd /= metalParts;
            qAtkSpd /= metalParts;
        }
        float q = quality(build.quality());
        Map<String, Integer> traits = Mods.traits(build);

        // modifiers and traits that change the numbers
        float durAdd = Math.min(100, build.progress("seasoned") / 50f);
        float durMul = 1 + qDur, speedAdd = 3f * build.level("honed"), speedMul = 1 + qSpd, atkAdd = build.level("keen_edge");
        float atkMul = 1, atkSpdMul = (1 + qAtkSpd) * (1 + 0.05f * build.level("featherlight")) * (1 + 0.10f * traits.getOrDefault("springy", 0))
                * (1 + 0.05f * traits.getOrDefault("sure_grip", 0));
        if (traits.containsKey("unyielding")) speedMul *= 0.8f;
        if (traits.containsKey("frost_tempered")) durMul += 0.10f;
        if (build.level("diamond_inlay") > 0) {
            durAdd += 500;
            atkAdd += 0.5f;
            tier = maxTier(tier, "diamond");
        }
        if (build.level("emerald_inlay") > 0) {
            durMul += 0.5f;
            tier = maxTier(tier, "iron");
        }
        if (build.level("netherite_cladding") > 0) {
            durMul += 0.2f;
            atkMul *= 1.2f;
            tier = "netherite";
        }

        String kind = type.kind();
        if (kind.equals("armor") || kind.equals("shield")) {
            float baseDur = kind.equals("armor") ? plateDur : dur;
            int d = Math.max(1, Math.round((baseDur * (kind.equals("shield") ? hd : 1) + durAdd) * type.mult("durability") * durMul * q));
            float slotArmor = kind.equals("armor") ? armorFor(type.armorSlot(), armor) * q : 0;
            return new Stats(d, 1, "wood", 0, 1, slotArmor, tough, kb, 1, 1, 1, 0);
        }
        if (kind.equals("ranged")) {
            float base = lw > 0 ? lDur : dur;
            int d = Math.max(1, Math.round((base * gDur + durAdd) * type.mult("durability") * durMul * q));
            float draw = lDraw * type.mult("draw_speed") * (1 + 0.10f * traits.getOrDefault("springy", 0)) * (1 + 0.15f * build.level("quickdraw"));
            float vel = lVel * type.mult("velocity") * (1 + 0.15f * traits.getOrDefault("taut", 0));
            float acc = lAcc * gAcc * (1 + 0.4f * traits.getOrDefault("steady", 0)) * (build.level("trueshot") > 0 ? 1.5f : 1);
            float dmg = (lDmg + gDmg) * q;
            return new Stats(d, 1, "wood", 0, 4, 0, 0, 0, draw, vel, acc, dmg);
        }
        if (kind.equals("ammo")) {
            float head = atk;
            float dmg = (2f + head * 0.5f) * ha * q;
            float vel = 1 + 0.3f * traits.getOrDefault("glide", 0);
            return new Stats(1, 1, tier, 0, 1, 0, 0, 0, 1, vel, 1, dmg);
        }
        int durability = Math.max(1, Math.round((dur * hd + durAdd) * type.mult("durability") * durMul * q));
        float mining = (speed * hs * type.mult("speed") + speedAdd) * speedMul * q;
        float damage = ((type.baseAttack() + atk) * ha * type.mult("attack") + atkAdd) * atkMul * q;
        float attackSpeed = type.baseAttackSpeed() * has * atkSpdMul;
        return new Stats(durability, mining, tier, damage, attackSpeed, 0, 0, 0, 1, 1, 1, 0);
    }

    /** Armor points per slot from plating armor (vanilla iron: 2 / 6 / 5 / 2 with plating 2.0). */
    private static float armorFor(String slot, float plating) {
        if (slot == null) return plating;
        return switch (slot) {
            case "head", "feet" -> plating;
            case "chest" -> plating * 3;
            case "legs" -> plating * 2.5f;
            default -> plating;
        };
    }

    // ------------------------------------------------------------------ components

    /** {@link #apply(ItemStack, HolderLookup.Provider)} without touching enchantments. */
    public static void apply(ItemStack stack) {
        apply(stack, null);
    }

    /**
     * Writes the build's stats, look, name and (with {@code registries}) the enchantments its modifiers work through
     * onto {@code stack}. Call after every change to the build.
     */
    public static void apply(ItemStack stack, HolderLookup.@Nullable Provider registries) {
        ToolBuild build = stack.get(Smithing.BUILD);
        SmithingDefs.ToolType type = build == null ? null : SmithingDefs.tool(build.tool());
        if (type == null) return;
        Stats s = compute(build);
        boolean broken = stack.has(Smithing.BROKEN);
        String kind = type.kind();
        if (!kind.equals("ammo")) {
            stack.set(DataComponents.MAX_DAMAGE, s.durability());
            Integer dmg = stack.get(DataComponents.DAMAGE);
            if (dmg == null) stack.set(DataComponents.DAMAGE, 0);
            else if (broken) stack.set(DataComponents.DAMAGE, s.durability());
            else if (dmg >= s.durability()) stack.set(DataComponents.DAMAGE, s.durability() - 1);
        }
        Map<String, Integer> traits = Mods.traits(build);
        if (!broken && (!type.mines().isEmpty() || type.weapon() != null)) stack.set(DataComponents.TOOL, tool(type, build, s));
        else stack.remove(DataComponents.TOOL);

        ItemAttributeModifiers.Builder attrs = ItemAttributeModifiers.builder();
        if (kind.equals("harvest") || kind.equals("melee")) {
            boolean weapon = type.weapon() != null;
            if (broken) stack.remove(DataComponents.WEAPON);
            else stack.set(DataComponents.WEAPON, type.has("disable_shield")
                    ? new Weapon(weapon ? 1 : 2, Weapon.AXE_DISABLES_BLOCKING_FOR_SECONDS) : new Weapon(weapon ? 1 : 2));
            if (!broken) {
                attrs.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, s.attackDamage(), AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                attrs.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, s.attackSpeed() - 4.0, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                if (type.has("reach")) attrs.add(Attributes.ENTITY_INTERACTION_RANGE,
                        new AttributeModifier(Smithing.id("smith.reach"), 1.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
                if (traits.containsKey("shaggy")) attrs.add(Attributes.KNOCKBACK_RESISTANCE,
                        new AttributeModifier(Smithing.id("smith.shaggy"), 0.1 * traits.get("shaggy"), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
                if (build.level("sea_glass_lens") > 0 || traits.containsKey("tidebound")) attrs.add(Attributes.SUBMERGED_MINING_SPEED,
                        new AttributeModifier(Smithing.id("smith.lens"), 0.8, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
                if (traits.containsKey("tidelucky")) attrs.add(Attributes.LUCK,
                        new AttributeModifier(Smithing.id("smith.tidelucky"), 1.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
            }
        } else if (kind.equals("armor")) {
            EquipmentSlot slot = armorSlot(type);
            EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(slot);
            Identifier id = Smithing.id("smith.armor." + type.armorSlot());
            if (!broken) {
                attrs.add(Attributes.ARMOR, new AttributeModifier(id, s.armor(), AttributeModifier.Operation.ADD_VALUE), group);
                if (s.toughness() > 0) attrs.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id, s.toughness(), AttributeModifier.Operation.ADD_VALUE), group);
                if (s.knockbackResistance() > 0) attrs.add(Attributes.KNOCKBACK_RESISTANCE,
                        new AttributeModifier(id, s.knockbackResistance(), AttributeModifier.Operation.ADD_VALUE), group);
                if (build.level("fleetfoot") > 0) attrs.add(Attributes.MOVEMENT_SPEED, new AttributeModifier(Smithing.id("smith.fleetfoot"),
                        0.05 * build.level("fleetfoot"), AttributeModifier.Operation.ADD_MULTIPLIED_BASE), group);
            }
            String plate = build.parts().isEmpty() ? "iron" : build.parts().get(0).material();
            stack.set(DataComponents.EQUIPPABLE, Equippable.builder(slot).setEquipSound(SoundEvents.ARMOR_EQUIP_IRON)
                    .setAsset(ResourceKey.create(EquipmentAssets.ROOT_ID, Smithing.id("smith_" + plate))).build());
        } else if (kind.equals("shield")) {
            BlocksAttacks v = Items.SHIELD.components().get(DataComponents.BLOCKS_ATTACKS);
            SmithingDefs.Material plate = SmithingDefs.material(build.parts().isEmpty() ? null : build.parts().get(0).material());
            if (v != null && !broken) stack.set(DataComponents.BLOCKS_ATTACKS, new BlocksAttacks(v.blockDelaySeconds(),
                    plate != null && plate.metal() ? 0.5f : v.disableCooldownScale(), v.damageReductions(), v.itemDamage(), v.bypassedBy(), v.blockSound(), v.disableSound()));
            else stack.remove(DataComponents.BLOCKS_ATTACKS);
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, attrs.build());

        // fire- and lava-proof: netherite parts, Netherite Cladding
        if (traits.containsKey("fireproof") || build.level("netherite_cladding") > 0) {
            if (registries != null) {
                var tags = registries.lookupOrThrow(Registries.DAMAGE_TYPE);
                tags.get(DamageTypeTags.IS_FIRE).ifPresent(set -> stack.set(DataComponents.DAMAGE_RESISTANT, new DamageResistant(set)));
            }
        } else {
            stack.remove(DataComponents.DAMAGE_RESISTANT);
        }

        if (registries != null) applyEnchantments(stack, type, build, traits, registries);
        TooltipDisplay td = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).withHidden(DataComponents.ENCHANTMENTS, true);
        stack.set(DataComponents.TOOLTIP_DISPLAY, td);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);

        // the look: one material per sprite layer, in the type's layer order
        List<String> layers = type.layers();
        for (int i = 0; i < Smithing.LOOK_LAYERS; i++) {
            if (i < layers.size()) stack.set(Smithing.LOOK[i], materialOfLayer(type, build, layers.get(i)));
            else stack.remove(Smithing.LOOK[i]);
        }
        stack.set(DataComponents.ITEM_NAME, name(type, build));
    }

    /** "Iron Pickaxe": the first part's material before the tool's name; gold for a Masterwork, dye-colored when Etched. */
    static Component name(SmithingDefs.ToolType type, ToolBuild build) {
        String head = build.parts().isEmpty() ? "" : build.parts().get(0).material();
        MutableComponent n = Component.translatable("aftrhrs.smithing.named",
                Component.translatable("aftrhrs.smithing.material." + head), Component.translatable("aftrhrs.smithing.tool." + type.id()));
        int etch = build.progress("etched_color");
        if (etch > 0) return n.withColor(etch & 0xFFFFFF);
        if (build.masterwork()) return n.withStyle(ChatFormatting.GOLD);
        return n;
    }

    private static void applyEnchantments(ItemStack stack, SmithingDefs.ToolType type, ToolBuild build, Map<String, Integer> traits,
                                          HolderLookup.Provider registries) {
        Map<String, Integer> want = new LinkedHashMap<>();
        for (ToolBuild.Mod m : build.mods()) {
            SmithingDefs.Modifier def = SmithingDefs.modifier(m.id());
            if (def != null && def.enchant() != null) want.merge(def.enchant(), m.level(), Math::max);
        }
        boolean harvest = type.kind().equals("harvest");
        int luck = build.level("fortunes_favor") + traits.getOrDefault("gilded", 0);
        if (luck > 0) want.merge(harvest ? "minecraft:fortune" : "minecraft:looting", Math.min(5, luck), Math::max);
        if (build.level("deepbreath") > 0) want.put("minecraft:aqua_affinity", 1);
        if (build.level("recall") > 0) want.put("minecraft:loyalty", 3);
        if (type.has("crossbow")) {
            if (build.level("quickdraw") > 0) want.put("minecraft:quick_charge", Math.min(3, build.level("quickdraw")));
            if (build.level("piercing") > 0) want.put("minecraft:piercing", build.level("piercing"));
        }
        ItemEnchantments.Mutable mut = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        var reg = registries.lookupOrThrow(Registries.ENCHANTMENT);
        want.forEach((id, level) -> {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) return;
            reg.get(ResourceKey.create(Registries.ENCHANTMENT, ident)).ifPresent(h -> mut.set((Holder<Enchantment>) h, level));
        });
        ItemEnchantments out = mut.toImmutable();
        if (out.isEmpty()) stack.remove(DataComponents.ENCHANTMENTS);
        else stack.set(DataComponents.ENCHANTMENTS, out);
    }

    static EquipmentSlot armorSlot(SmithingDefs.ToolType type) {
        return switch (type.armorSlot() == null ? "" : type.armorSlot()) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            default -> EquipmentSlot.FEET;
        };
    }

    private static String materialOfLayer(SmithingDefs.ToolType type, ToolBuild build, String layer) {
        for (int i = 0; i < type.parts().size() && i < build.parts().size(); i++) {
            if (type.parts().get(i).layer().equals(layer)) return build.parts().get(i).material();
        }
        return "";
    }

    private static Tool tool(SmithingDefs.ToolType type, ToolBuild build, Stats s) {
        List<Tool.Rule> rules = new ArrayList<>();
        rules.add(Tool.Rule.deniesDrops(BuiltInRegistries.BLOCK.getOrThrow(incorrectFor(s.tier()))));
        // faster rules first: the first rule that matches a block gives its speed
        if (build.level("deepcore") > 0) {
            for (TagKey<Block> t : List.of(BlockTags.DEEPSLATE_ORE_REPLACEABLES, TagKey.create(Registries.BLOCK, Identifier.parse("c:ores")))) {
                if (type.mines().contains("#minecraft:mineable/pickaxe")) rules.add(Tool.Rule.minesAndDrops(BuiltInRegistries.BLOCK.getOrThrow(t), s.miningSpeed() * 1.3f));
            }
        }
        if (build.level("lumberjack") > 0) {
            float k = 1 + 0.4f * build.level("lumberjack");
            rules.add(Tool.Rule.minesAndDrops(BuiltInRegistries.BLOCK.getOrThrow(BlockTags.LOGS), s.miningSpeed() * k));
            rules.add(Tool.Rule.minesAndDrops(BuiltInRegistries.BLOCK.getOrThrow(BlockTags.PLANKS), s.miningSpeed() * k));
        }
        for (String m : type.mines()) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(m.startsWith("#") ? m.substring(1) : m));
            rules.add(Tool.Rule.minesAndDrops(BuiltInRegistries.BLOCK.getOrThrow(tag), s.miningSpeed()));
        }
        if (type.weapon() != null) {
            rules.add(Tool.Rule.minesAndDrops(HolderSet.direct(Blocks.COBWEB.builtInRegistryHolder()), 15f));
            rules.add(Tool.Rule.overrideSpeed(BuiltInRegistries.BLOCK.getOrThrow(BlockTags.SWORD_EFFICIENT), 1.5f));
        }
        boolean weapon = type.weapon() != null;
        return new Tool(rules, 1.0f, weapon ? 2 : 1, !weapon);
    }

    static TagKey<Block> incorrectFor(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "stone" -> BlockTags.INCORRECT_FOR_STONE_TOOL;
            case "copper" -> BlockTags.INCORRECT_FOR_COPPER_TOOL;
            case "iron" -> BlockTags.INCORRECT_FOR_IRON_TOOL;
            case "gold" -> BlockTags.INCORRECT_FOR_GOLD_TOOL;
            case "diamond" -> BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
            case "netherite" -> BlockTags.INCORRECT_FOR_NETHERITE_TOOL;
            default -> BlockTags.INCORRECT_FOR_WOODEN_TOOL;
        };
    }

    /** Dye color as packed RGB, for Etched. */
    static int dye(DyeColor c) {
        return c.getTextureDiffuseColor() & 0xFFFFFF;
    }
}
