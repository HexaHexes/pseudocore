package org.bitwisemadness.pseudocore.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import org.bitwisemadness.pseudocore.Pseudocore;

/**
 * The five enchantments of the #159 family, as datapack registry data
 * (1.21 enchantments live in {@code data/<ns>/enchantment/*.json}).
 *
 * <p>All five are treasure-only: {@code supported_items} is the cores tag,
 * they carry {@code weight}/costs sane for the table but never enter it
 * ({@code minecraft:in_enchanting_table} never lists them), and
 * {@code effects} is empty on purpose - every behaviour is implemented in
 * code by the consumers of {@code ModEnchantments.levelOf}, because the
 * reduction is a linear 15%/level and the rest are presence checks. The
 * {@code core_efficiency} enchantment's six levels are therefore described
 * by the Enchantment Descriptions keys shipped in the lang file.</p>
 */
public final class PseudocoreEnchantmentProvider implements DataProvider {

    /** The five enchantments, by id and level cap (#159, ids per #165). */
    static final List<String> ENCHANTMENTS = List.of(
            "core_efficiency", "warriors_aura", "wizards_magicules", "hunters_boon", "preservation");

    private static final int CORE_EFFICIENCY_LEVELS = 6;

    private final Path outputRoot;

    public PseudocoreEnchantmentProvider(PackOutput packOutput) {
        this.outputRoot = packOutput.getOutputFolder();
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (String id : ENCHANTMENTS) {
            JsonObject enchantment = new JsonObject();

            JsonObject description = new JsonObject();
            description.addProperty("translate", "enchantment." + Pseudocore.MOD_ID + "." + id);
            enchantment.add("description", description);

            enchantment.addProperty("supported_items", "#" + Pseudocore.MOD_ID + ":pseudocore_cores");
            enchantment.addProperty("weight", id.equals("core_efficiency") ? 2 : 1);
            enchantment.addProperty("max_level",
                    id.equals("core_efficiency") ? CORE_EFFICIENCY_LEVELS : 1);

            JsonObject minCost = new JsonObject();
            minCost.addProperty("base", 1);
            minCost.addProperty("per_level_above_first", 1);
            enchantment.add("min_cost", minCost);
            JsonObject maxCost = new JsonObject();
            maxCost.addProperty("base", 15);
            maxCost.addProperty("per_level_above_first", 15);
            enchantment.add("max_cost", maxCost);
            enchantment.addProperty("anvil_cost", 1);

            JsonArray slots = new JsonArray();
            slots.add("any");
            enchantment.add("slots", slots);
            enchantment.add("effects", new JsonObject());

            futures.add(DataProvider.saveStable(output, enchantment,
                    outputRoot.resolve("data").resolve(Pseudocore.MOD_ID)
                            .resolve("enchantment").resolve(id + ".json")));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Pseudocore enchantments";
    }
}
