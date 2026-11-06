package org.bitwisemadness.pseudocore.registry;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.bitwisemadness.pseudocore.Pseudocore;

/**
 * The nine enchantments of the #159 family plus the payment switch, by key,
 * plus the item tag they all apply to. 1.21 enchantments are datapack
 * registry entries, so "registration" here means naming the keys (the data
 * ships from the datagen provider) and reading levels back through the live
 * registry.
 *
 * <p>Ids follow the display names since #165, when the payment family
 * rotated with the split-by-default economy: a change here must travel
 * with the provider ids, the lang keys and the description guard.</p>
 *
 * <p>Everything reads through {@link #levelOf}, which takes the registry
 * provider from the caller's level - no static registry handle, so it
 * works identically on both sides of a sync.</p>
 */
public final class ModEnchantments {

    /** Core Efficiency: the linear cost reduction, 15% per level (max level 6 = 90%). */
    public static final ResourceKey<Enchantment> CORE_EFFICIENCY = key("core_efficiency");

    /** Warrior's Aura: the whole capture/recall cost from AP (aura) instead of MP. */
    public static final ResourceKey<Enchantment> WARRIORS_AURA = key("warriors_aura");

    /** Wizard's Magicules: the whole cost from MP (magicule); the default is a 50/50 split (#165). */
    public static final ResourceKey<Enchantment> WIZARDS_MAGICULES = key("wizards_magicules");

    /** Hunter's Boon: opens the wild-capture gate - otherwise every core refuses wild mobs. */
    public static final ResourceKey<Enchantment> HUNTERS_BOON = key("hunters_boon");

    /** Gate death-preserve: a core without it never preserves a dying mob. */
    public static final ResourceKey<Enchantment> PRESERVATION = key("preservation");

    /** Pay with Health: the whole capture cost from the player's health, through Ability Core's pair. */
    public static final ResourceKey<Enchantment> PAY_WITH_HEALTH = key("pay_with_health");

    /** Pay with Hunger: the whole capture cost from the food bar, through Ability Core's pair. */
    public static final ResourceKey<Enchantment> PAY_WITH_HUNGER = key("pay_with_hunger");

    /** Pay with Experience: the whole capture cost from experience points, through Ability Core's pair. */
    public static final ResourceKey<Enchantment> PAY_WITH_EXPERIENCE_POINTS = key("pay_with_experience_points");

    /** Pay with Levels: the whole capture cost from experience levels, through Ability Core's pair. */
    public static final ResourceKey<Enchantment> PAY_WITH_EXPERIENCE_LEVELS = key("pay_with_experience_levels");

    /** Every core of every line: beast, dragon and dragonsteel. */
    public static final TagKey<Item> CORES = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, "pseudocore_cores"));

    /**
     * All nine keys, in description-guard order. The four {@code pay_with_*}
     * entries exist only while Ability Core is loaded (their JSON carries
     * {@code neoforge:conditions}); the other five always ship.
     */
    public static final List<ResourceKey<Enchantment>> ALL = List.of(
            CORE_EFFICIENCY, WARRIORS_AURA, WIZARDS_MAGICULES, HUNTERS_BOON, PRESERVATION,
            PAY_WITH_HEALTH, PAY_WITH_HUNGER, PAY_WITH_EXPERIENCE_POINTS, PAY_WITH_EXPERIENCE_LEVELS);

    private ModEnchantments() {
    }

    /**
     * The enchantment's level on a stack: 0 when the stack carries none,
     * when the enchantment is absent from this world's data, or when the
     * registry is unavailable.
     */
    public static int levelOf(HolderLookup.Provider registries, ItemStack stack, ResourceKey<Enchantment> key) {
        if (registries == null || stack.isEmpty()) {
            return 0;
        }
        var holder = registries.lookupOrThrow(Registries.ENCHANTMENT).get(key).orElse(null);
        return holder == null ? 0 : EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
    }

    /** True when the stack carries the enchantment at least once. */
    public static boolean has(HolderLookup.Provider registries, ItemStack stack, ResourceKey<Enchantment> key) {
        return levelOf(registries, stack, key) > 0;
    }

    private static ResourceKey<Enchantment> key(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, path));
    }
}
