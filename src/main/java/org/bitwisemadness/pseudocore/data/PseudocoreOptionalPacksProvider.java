package org.bitwisemadness.pseudocore.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

/**
 * The three optional upgrade-progression datapacks (#154): each pack is a
 * folder under {@code optional_packs/} in the generated tree containing a
 * {@code pack.mcmeta} and one {@code neoforge:false}-conditioned stub per
 * disabled recipe, sitting at the recipe's exact path. With the pack
 * enabled the stub overrides the real recipe and its failing condition
 * removes it; with the pack off (the default) the stub is never even
 * loaded, because the pack itself is off.
 *
 * <p>The generated folder lives under {@code src/generated/resources}
 * (the CI drift gate's root, and a dir the jar ships) but outside
 * {@code data/} and {@code assets/}, so it never joins the mod's own
 * pack. Registration happens in {@link PseudocoreDataProviders} through
 * NeoForge's {@code AddPackFindersEvent} - optional, default-off,
 * TOP-positioned so an enabled pack wins the path conflict.</p>
 *
 * <p>The disabled sets come from {@link PseudocoreRecipeProvider}'s own
 * row tables ({@code optionalPackBands}), so the bands cannot drift
 * from the recipes they govern.</p>
 */
public final class PseudocoreOptionalPacksProvider implements DataProvider {

    /** One optional pack: folder under {@code optional_packs/}, UI name, disabled recipe ids. */
    public record PackSpec(String path, String display, Set<String> recipeIds) {
    }

    /** The pack format of Minecraft 1.21.1 datapacks. */
    private static final int PACK_FORMAT = 48;

    static final List<PackSpec> PACKS = buildPacks();

    private static List<PackSpec> buildPacks() {
        List<Set<String>> bands = PseudocoreRecipeProvider.optionalPackBands();
        return List.of(
                new PackSpec("no_direct_iron_to_netherite",
                        "No Direct Crafting: Iron to Netherite", bands.get(0)),
                new PackSpec("no_direct_high_magisteel_to_hihiirokane",
                        "No Direct Crafting: High Magisteel to Hihi'irokane", bands.get(1)),
                new PackSpec("no_direct_dragonsteel",
                        "No Direct Crafting: Dragonsteel", bands.get(2)));
    }

    private final Path outputRoot;

    public PseudocoreOptionalPacksProvider(PackOutput packOutput) {
        this.outputRoot = packOutput.getOutputFolder();
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (PackSpec pack : PACKS) {
            Path packRoot = outputRoot.resolve("optional_packs").resolve(pack.path());

            JsonObject mcmeta = new JsonObject();
            JsonObject packSection = new JsonObject();
            packSection.addProperty("pack_format", PACK_FORMAT);
            packSection.addProperty("description", pack.display());
            mcmeta.add("pack", packSection);
            futures.add(DataProvider.saveStable(output, mcmeta, packRoot.resolve("pack.mcmeta")));

            for (String id : pack.recipeIds()) {
                JsonObject stub = new JsonObject();
                JsonArray conditions = new JsonArray();
                JsonObject falseCondition = new JsonObject();
                falseCondition.addProperty("type", "neoforge:false");
                conditions.add(falseCondition);
                stub.add("neoforge:conditions", conditions);
                futures.add(DataProvider.saveStable(output, stub,
                        packRoot.resolve("data/pseudocore/recipe").resolve(id + ".json")));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Pseudocore optional crafting packs";
    }
}
