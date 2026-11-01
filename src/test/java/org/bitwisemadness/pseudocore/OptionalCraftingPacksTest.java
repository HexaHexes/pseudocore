package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The three optional upgrade-progression packs (#154) are invisible to
 * the game tests: they ship disabled by default, and their whole effect
 * is the absence of recipes when a player opts in. A missing pack, a
 * stub at the wrong path, or a band that leaked a recipe it must keep
 * (copper, low magisteel, the dragon line, every upgrade) would all be
 * silent - hence this file-based guard in the style of
 * {@link MetallicPackTest}. Pure JDK, like every junit test in this
 * source set - no Minecraft or gson types on the classpath.
 */
class OptionalCraftingPacksTest {

    private static final Path PACKS = Path.of("src", "generated", "resources", "optional_packs");

    /** Each band's disabled tiers, named explicitly (crystal + gem variants). */
    private static final List<String[]> BANDS = List.of(
            new String[]{"no_direct_iron_to_netherite", "iron", "silver", "gold", "netherite"},
            new String[]{"no_direct_high_magisteel_to_hihiirokane", "high_magisteel",
                    "pure_magisteel", "mithril", "orichalcum", "adamantite", "hihiirokane"},
            new String[]{"no_direct_dragonsteel", "fire_dragonsteel", "ice_dragonsteel",
                    "lightning_dragonsteel"});

    /** Nothing outside the three bands may ever be stubbed. */
    private static final String[] NEVER_DISABLED = {
            "copper_pseudocore", "copper_pseudocore_gem",
            "low_magisteel_pseudocore", "low_magisteel_pseudocore_gem",
            "copper_dragon_pseudocore", "copper_dragon_pseudocore_gem",
            "iron_dragon_pseudocore", "iron_dragon_pseudocore_gem",
            "silver_dragon_pseudocore", "silver_dragon_pseudocore_gem",
            "gold_dragon_pseudocore", "gold_dragon_pseudocore_gem",
            "netherite_dragon_pseudocore", "netherite_dragon_pseudocore_gem",
            "copper_to_iron", "iron_to_silver", "silver_to_gold", "gold_to_netherite",
            "netherite_to_low_magisteel", "low_magisteel_to_high_magisteel",
            "high_magisteel_to_pure_magisteel", "pure_magisteel_to_mithril",
            "mithril_to_orichalcum", "orichalcum_to_adamantite",
            "adamantite_to_hihiirokane",
            "copper_dragon_to_iron_dragon", "iron_dragon_to_silver_dragon",
            "silver_dragon_to_gold_dragon", "gold_dragon_to_netherite_dragon"};

    @Test
    void shipsExactlyTheThreeBands() throws IOException {
        Set<String> expected = new HashSet<>();
        for (String[] band : BANDS) {
            expected.add(band[0]);
        }
        try (Stream<Path> packs = Files.list(PACKS)) {
            Set<String> actual = new HashSet<>();
            packs.forEach(p -> actual.add(p.getFileName().toString()));
            assertEquals(expected, actual,
                    "exactly the three #154 packs may exist under optional_packs/");
        }
        for (String[] band : BANDS) {
            String mcmeta = Files.readString(PACKS.resolve(band[0]).resolve("pack.mcmeta"))
                    .replaceAll("\\s+", "");
            assertTrue(mcmeta.contains("\"pack_format\":48"),
                    band[0] + " must declare the 1.21.1 datapack format");
            assertTrue(mcmeta.contains("NoDirectCrafting"),
                    band[0] + " must describe itself in the pack UI");
        }
    }

    @Test
    void eachBandDisablesExactlyItsOwnRecipes() throws IOException {
        Set<String> allStubbed = new HashSet<>();
        for (String[] band : BANDS) {
            Set<String> expected = new HashSet<>();
            for (int i = 1; i < band.length; i++) {
                expected.add(band[i] + "_pseudocore");
                expected.add(band[i] + "_pseudocore_gem");
            }
            Path recipeDir = PACKS.resolve(band[0]).resolve("data/pseudocore/recipe");
            Set<String> actual = new HashSet<>();
            try (Stream<Path> files = Files.list(recipeDir)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    assertTrue(name.endsWith(".json"), "non-json file in a pack: " + name);
                    String id = name.substring(0, name.length() - ".json".length());
                    actual.add(id);
                    String stub = Files.readString(file).replaceAll("\\s+", "");
                    assertTrue(stub.contains("\"neoforge:conditions\":[{\"type\":\"neoforge:false\"}]"),
                            band[0] + "/" + id + " must disable the recipe by false condition, got: " + stub);
                }
            }
            assertEquals(expected, actual, band[0] + " must stub exactly its band");
            allStubbed.addAll(actual);
        }
        for (String kept : NEVER_DISABLED) {
            assertTrue(!allStubbed.contains(kept),
                    "kept recipe may never be disabled by any pack: " + kept);
        }
    }
}
