package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The glint split (#160, superseding #86's "the foil is the whole
 * feature"): the foil follows the enchantment alone and the filled state
 * moved to a mob-coloured shimmer drawn by a client mixin - so a filled
 * core and an enchanted core can no longer render identically. All
 * file-based, in the style of {@link ModelCoverageTest}, because the
 * junit source set has no Minecraft classes and the client render
 * plumbing is invisible to {@code runGameTestServer} (the behavioural
 * foil matrix lives in {@code GlintGameTests}).
 *
 * <p>Two directions are pinned: the wiring that must exist (the mixin in
 * the config's client array, the shimmer's storage reads and orb colour)
 * and the wiring that must stay dead (#86's deleted machinery - a
 * separate client config and a custom shader: the shimmer reuses
 * vanilla's glint shader with a half-phase texture matrix instead).</p>
 */
class GlintFoilTest {

    private static final Path MODS_TOML =
            Path.of("src/main/templates/META-INF/neoforge.mods.toml");
    private static final Path MIXINS_JSON = Path.of("src/main/resources/pseudocore.mixins.json");
    private static final Path BEAST_ITEM = Path.of(
            "src/main/java/org/bitwisemadness/pseudocore/item/PseudocoreItem.java");
    private static final Path DRAGON_ITEM = Path.of(
            "src/main/java/org/bitwisemadness/pseudocore/item/DragonPseudocoreItem.java");
    private static final Path SHIMMER_MIXIN = Path.of(
            "src/main/java/org/bitwisemadness/pseudocore/mixin/ItemRendererShimmerMixin.java");
    private static final Path SHIMMER = Path.of(
            "src/main/java/org/bitwisemadness/pseudocore/client/MobGlint.java");

    /** #160: the filled state left the foil - vanilla answers alone now. */
    @Test
    void theFoilOverridesAreGone() throws IOException {
        String beast = Files.readString(BEAST_ITEM);
        assertFalse(beast.contains("public boolean isFoil(ItemStack stack)"),
                "PseudocoreItem must not override isFoil - the filled state draws the "
                        + "shimmer now and the enchantment owns the glint (#160)");
        String dragon = Files.readString(DRAGON_ITEM);
        assertFalse(dragon.contains("public boolean isFoil(ItemStack stack)"),
                "DragonPseudocoreItem must not override isFoil (#160)");
    }

    /** The shimmer's wiring: client array, the mixin, colour, both storages. */
    @Test
    void theShimmerShipsInTheClientArray() throws IOException {
        String config = Files.readString(MIXINS_JSON);
        assertTrue(config.contains("\"client\""),
                "the shimmer mixin must ship in the config's client-only array so a "
                        + "dedicated server never applies it (#160)");
        assertTrue(config.contains("ItemRendererShimmerMixin"),
                "pseudocore.mixins.json must list the shimmer mixin");

        String mixin = Files.readString(SHIMMER_MIXIN);
        assertTrue(mixin.contains("MobGlint"),
                "the mixin must emit the second pass through the MobGlint layer");

        String shimmer = Files.readString(SHIMMER);
        assertTrue(shimmer.contains("PseudocoreTintHandler.TINT"),
                "the shimmer colour must be the orb colour - one source, two readers (#160)");
        assertTrue(shimmer.contains("CONTAINED_MOB"),
                "the shimmer must read the beast line's storage");
        assertTrue(shimmer.contains("IafHornAccess.read"),
                "the shimmer must read the dragon line's storage (#65)");
    }

    /**
     * The deleted machinery must stay deleted: each part that silently
     * failing was invisible in #67, and the simplest defence is the
     * absence of the code plus the absence of its registrations. #160
     * keeps the same rule for its own approach - no separate client
     * config, no custom shader: the shimmer reuses vanilla's glint
     * shader and ships in the existing config's client array.
     */
    @Test
    void customGlintMachineryStaysDeleted() {
        String[] gone = {
                "src/main/resources/pseudocore.client.mixins.json",
                "src/main/java/org/bitwisemadness/pseudocore/mixin/ItemRendererMixin.java",
                "src/main/java/org/bitwisemadness/pseudocore/client/TintedGlint.java",
                "src/main/resources/assets/pseudocore/shaders/core/tinted_glint.json",
                "src/main/resources/assets/pseudocore/shaders/core/tinted_glint.vsh",
                "src/main/resources/assets/pseudocore/shaders/core/tinted_glint.fsh"};
        for (String path : gone) {
            assertFalse(Files.exists(Path.of(path)),
                    "deleted glint machinery is back: " + path
                            + " - the shimmer must reuse vanilla's pipeline (#86/#160)");
        }
    }

    @Test
    void modsTomlRegistersNoClientMixinConfig() throws IOException {
        String toml = Files.readString(MODS_TOML);
        assertTrue(!toml.contains("pseudocore.client.mixins.json"),
                "mods.toml still registers a separate client mixin config (#86) - the "
                        + "shimmer rides the existing config's client array instead (#160)");
        assertTrue(toml.contains("pseudocore.iaf.mixins.json"),
                "the IaF mixin config must stay registered - it is unrelated to the glint");
    }

    /**
     * The #65 read-path assertions that outlive the glint rewrite: the filled
     * model predicate and the orb tint still read both storage formats, and no
     * client class imports Ice and Fire directly.
     */
    @Test
    void dragonStorageReadsAndLayeringSurviveTheRewrite() throws IOException {
        String events = Files.readString(Path.of(
                "src/main/java/org/bitwisemadness/pseudocore/client/PseudocoreClientEvents.java"));
        assertTrue(events.contains("IafHornAccess.storedDragonType"),
                "the pseudocore:filled item property must accept a dragon core (#65)");

        String tint = Files.readString(Path.of(
                "src/main/java/org/bitwisemadness/pseudocore/client/PseudocoreTintHandler.java"));
        assertTrue(tint.contains("IafHornAccess.storedDragonType")
                        && tint.contains("IafHornAccess.storedDragonData"),
                "the orb tint must read a dragon core's element and variant (#65)");

        for (String clientSource : new String[]{
                "src/main/java/org/bitwisemadness/pseudocore/client/PseudocoreClientEvents.java",
                "src/main/java/org/bitwisemadness/pseudocore/client/PseudocoreTintHandler.java"}) {
            String source = Files.readString(Path.of(clientSource));
            assertTrue(!source.contains("import com.iafenvoy"),
                    "an IaF import crept into a client class: " + clientSource);
        }
    }
}
