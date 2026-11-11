package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The payment switches ship only with Ability Core (the provider's
 * {@code neoforge:conditions} emission): the three switch JSONs must carry
 * the {@code mod_loaded} condition and join the treasure tag as optional
 * entries, while the five always-on enchantments must carry none - a
 * missing condition would leak a wallet switch into packs without the pair
 * it names, and an over-eager one would hide it where it works.
 *
 * <p>The five wallets also share one {@code exclusive_set} tag: each
 * wallet's JSON must name it and the tag must list every wallet, so the
 * anvil can refuse a second payment type on one core.</p>
 */
class PaymentEnchantmentDataTest {

    private static final Path ENCHANTMENT_DIR = Path.of("src", "generated", "resources",
            "data", "pseudocore", "enchantment");

    private static final Path TREASURE_TAG = Path.of("src", "generated", "resources",
            "data", "minecraft", "tags", "enchantment", "treasure.json");

    private static final Path EXCLUSIVE_TAG = Path.of("src", "generated", "resources",
            "data", "pseudocore", "tags", "enchantment", "exclusive_set", "payment.json");

    private static final List<String> CORE_GATED = List.of(
            "pranas_breath", "gourmets_hunger", "experienceds_teachings");

    private static final List<String> ALWAYS_ON = List.of(
            "core_efficiency", "warriors_aura", "wizards_magicules", "hunters_boon", "preservation");

    /** Every wallet of the one-per-core set, switch or pool pair alike. */
    private static final List<String> WALLETS = List.of(
            "warriors_aura", "wizards_magicules", "pranas_breath", "gourmets_hunger",
            "experienceds_teachings");

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

    @Test
    void everyWalletSharesThePaymentExclusiveSet() throws IOException {
        for (String id : WALLETS) {
            String json = Files.readString(ENCHANTMENT_DIR.resolve(id + ".json"));
            assertTrue(json.contains("\"exclusive_set\": \"#pseudocore:exclusive_set/payment\""),
                    id + " must refuse every other wallet at the anvil");
        }
        String tag = Files.readString(EXCLUSIVE_TAG);
        for (String id : WALLETS) {
            assertTrue(tag.contains("pseudocore:" + id),
                    id + " must be a member of the shared exclusive set");
        }
    }

    @Test
    void noOtherEnchantmentJoinsTheExclusiveSet() throws IOException {
        for (String id : ALWAYS_ON) {
            if (WALLETS.contains(id)) {
                continue;
            }
            String json = Files.readString(ENCHANTMENT_DIR.resolve(id + ".json"));
            assertFalse(json.contains("exclusive_set"),
                    id + " is not a wallet - it must stay stackable with every payment type");
        }
    }
}
