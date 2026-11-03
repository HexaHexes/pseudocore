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
 * The five enchantments of the #159 family, by key, plus the item tag
 * they all apply to. 1.21 enchantments are datapack registry entries, so
 * "registration" here means naming the keys (the data ships from the
 * datagen provider) and reading levels back through the live registry.
 *
 * <p>Everything reads through {@link #levelOf}, which takes the registry
 * provider from the caller's level - no static registry handle, so it
 * works identically on both sides of a sync.</p>
 */
public final class ModEnchantments {

    /** The linear MP-cost reduction, 15% per level (max level 6 = 90%). */
    public static final ResourceKey<Enchantment> COST_REDUCTION = key("cost_reduction");

    /** Pay the cost from AP (aura) instead of MP (magicule). */
    public static final ResourceKey<Enchantment> AURA_PAYMENT = key("aura_payment");

    /** Split the cost evenly between MP and AP. */
    public static final ResourceKey<Enchantment> SPLIT_PAYMENT = key("split_payment");

    /** Open the wild-capture gate (otherwise every core refuses wild mobs). */
    public static final ResourceKey<Enchantment> WILD_CAPTURE = key("wild_capture");

    /** Gate death-preserve: a core without it never preserves a dying mob. */
    public static final ResourceKey<Enchantment> PRESERVATION = key("preservation");

    /** Every core of every line: beast, dragon and dragonsteel. */
    public static final TagKey<Item> CORES = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, "pseudocore_cores"));

    /** All five keys, in description-guard order. */
    public static final List<ResourceKey<Enchantment>> ALL = List.of(
            COST_REDUCTION, AURA_PAYMENT, SPLIT_PAYMENT, WILD_CAPTURE, PRESERVATION);

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
