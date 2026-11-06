package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Enchantment Descriptions support (#159): every enchantment this mod
 * ships must carry its display name, the single-level ones their plain
 * {@code .desc} key, and the multi-level {@code core_efficiency} one key
 * per level (I..VI) - so a tooltip never renders blank and the linear
 * percentages stay readable. Pure JDK, like every junit test in this
 * source set - the ids mirror {@code ModEnchantments.ALL}, and a renamed
 * id fails this test until its key is carried over.
 *
 * <p>#165 unshadowed the reduction's per-level keys: enchdesc checks the
 * plain {@code .desc} <b>before</b> {@code .desc.N} and passes no format
 * argument, so a plain key pins every level to level 1's text (and a
 * literal {@code %s} would render as-is). Hence the explicit absence
 * check below.</p>
 */
class EnchantmentDescriptionsTest {

    private static final Path LANG = Path.of("src", "main", "resources", "assets",
            "pseudocore", "lang", "en_us.json");

    private static final List<String> ENCHANTMENTS = List.of(
            "core_efficiency", "warriors_aura", "wizards_magicules", "hunters_boon", "preservation",
            "pay_with_health", "pay_with_hunger", "pay_with_experience_points",
            "pay_with_experience_levels");

    /** The multi-level one: per-level keys only, never a plain shadow (#165). */
    private static final String REDUCTION = "core_efficiency";

    private static final int REDUCTION_LEVELS = 6;

    @Test
    void everyShippedEnchantmentHasItsKeys() throws IOException {
        String lang = Files.readString(LANG);
        for (String id : ENCHANTMENTS) {
            assertTrue(lang.contains("\"enchantment.pseudocore." + id + "\":"),
                    "missing enchantment name for " + id);
            if (REDUCTION.equals(id)) {
                continue; // per-level keys only - pinned by the guard below
            }
            assertTrue(lang.contains("\"enchantment.pseudocore." + id + ".desc\":"),
                    "missing description key for " + id + " - Enchantment Descriptions would "
                            + "render its tooltip blank");
        }
    }

    @Test
    void theReductionEnchantmentDescribesEveryLevel() throws IOException {
        String lang = Files.readString(LANG);
        for (int level = 1; level <= REDUCTION_LEVELS; level++) {
            assertTrue(lang.contains(
                            "\"enchantment.pseudocore." + REDUCTION + ".desc." + level + "\":"),
                    "missing per-level description for " + REDUCTION + " level " + level);
        }
        assertFalse(lang.contains("\"enchantment.pseudocore." + REDUCTION + ".desc\":"),
                "a plain .desc would shadow the per-level keys - enchdesc resolves it first "
                        + "and passes no format argument (#165)");
    }
}
