package org.bitwisemadness.pseudocore.data;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;

/**
 * The enchantment side of the #159 family: all eight ids join the vanilla
 * {@code minecraft:treasure} tag, which is what makes them treasure-only
 * (never offered by an enchanting table) without hiding them from loot,
 * books or anvils.
 *
 * <p>{@code minecraft:in_enchanting_table} is deliberately untouched:
 * absent means the table never offers them, which is the treasure rule.
 * The three payment-switch ids are {@code required: false} like every
 * other entry: they exist only while Ability Core is loaded (their JSON
 * conditions), and an optional reference tolerates their absence.</p>
 *
 * <p>{@code pseudocore:exclusive_set/payment} is the one-wallet rule:
 * every wallet in {@link ModEnchantments#WALLETS} joins it, and the
 * provider stamps the matching {@code exclusive_set} field on each, so
 * the anvil refuses to merge a second payment type into one core.</p>
 */
public final class PseudocoreEnchantmentTagProvider extends TagsProvider<Enchantment> {

    private static final TagKey<Enchantment> TREASURE =
            TagKey.create(Registries.ENCHANTMENT, ResourceLocation.withDefaultNamespace("treasure"));

    /** The shared wallet set each payment enchantment's exclusive_set names. */
    private static final TagKey<Enchantment> EXCLUSIVE_PAYMENT = TagKey.create(
            Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, "exclusive_set/payment"));

    public PseudocoreEnchantmentTagProvider(PackOutput output,
                                            CompletableFuture<HolderLookup.Provider> lookupProvider,
                                            ExistingFileHelper existingFileHelper) {
        super(output, Registries.ENCHANTMENT, lookupProvider, Pseudocore.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        var treasure = tag(TREASURE);
        for (var key : ModEnchantments.ALL) {
            // Optional: enchantments are datapack registry data this very run
            // generates, so the datagen-time reference check cannot see them -
            // at runtime they resolve, because the pack ships them.
            treasure.addOptional(key.location());
        }
        var payment = tag(EXCLUSIVE_PAYMENT);
        for (var key : ModEnchantments.WALLETS) {
            // Optional for the same datagen-time reason as the treasure tag;
            // self-membership matches vanilla's own exclusive_set tags.
            payment.addOptional(key.location());
        }
    }
}
