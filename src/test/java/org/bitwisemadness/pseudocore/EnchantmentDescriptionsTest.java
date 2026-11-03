package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Enchantment Descriptions support (#159): every enchantment this mod
 * ships must carry its {@code .desc} key, and the multi-level
 * {@code cost_reduction} must carry one per level (I..VI), so a tooltip
 * never renders blank and the linear percentages stay readable. Pure JDK,
 * like every junit test in this source set - the ids mirror
 * {@code ModEnchantments.ALL}, and a renamed id fails this test until its
 * key is carried over.
 */
class EnchantmentDescriptionsTest {

    private static final Path LANG = Path.of("src", "main", "resources", "assets",
            "pseudocore", "lang", "en_us.json");

    private static final List<String> ENCHANTMENTS = List.of(
            "cost_reduction", "aura_payment", "split_payment", "wild_capture", "preservation");

    private static final int REDUCTION_LEVELS = 6;

    @Test
    void everyShippedEnchantmentHasItsDescriptionKeys() throws IOException {
        String lang = Files.readString(LANG);
        for (String id : ENCHANTMENTS) {
            assertTrue(lang.contains("\"enchantment.pseudocore." + id + "\":"),
                    "missing enchantment description for " + id);
            assertTrue(lang.contains("\"enchantment.pseudocore." + id + ".desc\":"),
                    "missing description key for " + id + " - Enchantment Descriptions would "
                            + "render its tooltip blank");
        }
    }

    @Test
    void theReductionEnchantmentDescribesEveryLevel() throws IOException {
        String lang = Files.readString(LANG);
        for (int level = 2; level <= REDUCTION_LEVELS; level++) {
            assertTrue(lang.contains("\"enchantment.pseudocore.cost_reduction.desc." + level + "\":"),
                    "missing per-level description for cost_reduction level " + level);
        }
    }
}
