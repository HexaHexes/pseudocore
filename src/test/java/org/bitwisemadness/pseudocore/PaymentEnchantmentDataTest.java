package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pay_with_* enchantments ship only with Ability Core (the provider's
 * {@code neoforge:conditions} emission): the four switch JSONs must carry
 * the {@code mod_loaded} condition and join the treasure tag as optional
 * entries, while the five always-on enchantments must carry none - a
 * missing condition would leak a wallet switch into packs without the pair
 * it names, and an over-eager one would hide it where it works.
 */
class PaymentEnchantmentDataTest {

    private static final Path ENCHANTMENT_DIR = Path.of("src", "generated", "resources",
            "data", "pseudocore", "enchantment");

    private static final Path TREASURE_TAG = Path.of("src", "generated", "resources",
            "data", "minecraft", "tags", "enchantment", "treasure.json");

    private static final List<String> CORE_GATED = List.of(
            "pay_with_health", "pay_with_hunger", "pay_with_experience_points",
            "pay_with_experience_levels");

    private static final List<String> ALWAYS_ON = List.of(
            "core_efficiency", "warriors_aura", "wizards_magicules", "hunters_boon", "preservation");

    @Test
    void thePaymentSwitchesAreConditionedOnAbilityCore() throws IOException {
        for (String id : CORE_GATED) {
            String json = Files.readString(ENCHANTMENT_DIR.resolve(id + ".json"));
            assertTrue(json.contains("\"neoforge:conditions\""),
                    id + " must carry the conditions field");
            assertTrue(json.contains("\"modid\": \"ability_core\""),
                    id + " must exist exactly while ability core does");
        }
    }

    @Test
    void theAlwaysOnEnchantmentsCarryNoConditions() throws IOException {
        for (String id : ALWAYS_ON) {
            String json = Files.readString(ENCHANTMENT_DIR.resolve(id + ".json"));
            assertFalse(json.contains("neoforge:conditions"),
                    id + " must always ship - it names no core pair");
        }
    }

    @Test
    void everyPaymentSwitchJoinsTheTreasureTagAsOptional() throws IOException {
        String treasure = Files.readString(TREASURE_TAG);
        for (String id : CORE_GATED) {
            assertTrue(treasure.contains("pseudocore:" + id),
                    id + " must be treasure-only like the rest of the family");
        }
        assertTrue(treasure.contains("\"required\": false"),
                "optional references tolerate the condition-false case");
    }
}
