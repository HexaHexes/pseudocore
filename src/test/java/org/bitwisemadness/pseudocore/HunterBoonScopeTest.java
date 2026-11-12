package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hunter's Boon's supported set: the beast line's cores only. A dragon
 * core's wild-dragon gate is IaF's ownership guard, which answers
 * whatever the core carries ({@code DragonLineGameTests} pins it), so the
 * enchantment would be a no-op on the dragon line and the anvil must
 * refuse it there. Pins the datagen split: hunters_boon.json names the
 * beast tag, every other enchantment keeps the full cores tag, and the
 * beast tag itself lists no dragon item.
 */
class HunterBoonScopeTest {

    private static final Path ENCHANTMENT_DIR = Path.of("src", "generated", "resources",
            "data", "pseudocore", "enchantment");

    private static final Path BEAST_TAG = Path.of("src", "generated", "resources",
            "data", "pseudocore", "tags", "item", "beast_cores.json");

    private static final List<String> FULL_CORES_TAG = List.of(
            "core_efficiency", "warriors_aura", "wizards_magicules", "preservation",
            "pranas_breath", "gourmets_hunger", "experienceds_teachings");

    @Test
    void huntersBoonNamesTheBeastCoreTag() throws IOException {
        String json = Files.readString(ENCHANTMENT_DIR.resolve("hunters_boon.json"));
        assertTrue(json.contains("\"supported_items\": \"#pseudocore:beast_cores\""),
                "hunters_boon must apply to the beast line only");
        assertFalse(json.contains("#pseudocore:pseudocore_cores"),
                "hunters_boon must not name the full cores tag");
    }

    @Test
    void everyOtherEnchantmentKeepsTheFullCoresTag() throws IOException {
        for (String id : FULL_CORES_TAG) {
            String json = Files.readString(ENCHANTMENT_DIR.resolve(id + ".json"));
            assertTrue(json.contains("\"supported_items\": \"#pseudocore:pseudocore_cores\""),
                    id + " must keep the full cores tag");
        }
    }

    @Test
    void theBeastTagListsNoDragonCore() throws IOException {
        String tag = Files.readString(BEAST_TAG);
        assertTrue(tag.contains("pseudocore:copper_pseudocore"),
                "a beast core must join the beast tag");
        assertFalse(tag.contains("dragon"),
                "no dragon-line core may join the beast tag");
    }
}
