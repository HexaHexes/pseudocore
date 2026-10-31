package org.bitwisemadness.pseudocore.data;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.bitwisemadness.pseudocore.Pseudocore;

/**
 * The {@code c:ingots/<metal>} common tags (#116): each of Tensura's
 * metals gets a one-entry tag pointing at its ingot by id, marked
 * optional - the tags exist (recipes can reference them) whether or not
 * Tensura is installed, and resolve empty without it.
 *
 * <p>The {@code c} namespace is deliberate: these are interoperability
 * tags other mods read, not pseudocore-private ones, which is exactly why
 * they cannot live under {@code data/pseudocore}. The tag keys themselves
 * carry the {@code c} namespace, so the files land under
 * {@code data/c/tags/item/} whatever the provider's own mod id is.</p>
 */
public final class PseudocoreItemTagsProvider extends ItemTagsProvider {

    /** Tensura's ingot ids, one per tag of the same path. */
    private static final List<String> TENSURA_METALS = List.of(
            "silver", "low_magisteel", "high_magisteel", "pure_magisteel",
            "mithril", "orichalcum", "adamantite", "hihiirokane");

    public PseudocoreItemTagsProvider(PackOutput output,
                                      CompletableFuture<HolderLookup.Provider> lookupProvider,
                                      ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider,
                CompletableFuture.completedFuture(TagsProvider.TagLookup.empty()),
                Pseudocore.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        for (String metal : TENSURA_METALS) {
            tag(TagKey.create(Registries.ITEM,
                            ResourceLocation.fromNamespaceAndPath("c", "ingots/" + metal)))
                    .addOptional(ResourceLocation.fromNamespaceAndPath("tensura", metal + "_ingot"));
        }
    }
}
