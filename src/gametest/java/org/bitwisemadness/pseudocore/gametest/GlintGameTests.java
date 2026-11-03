package org.bitwisemadness.pseudocore.gametest;

import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;

/**
 * The foil matrix (#160): vanilla's glint belongs to the enchantment
 * alone, and the filled state - which used to borrow that same glint -
 * now has its own mob-coloured layer drawn client-side. The layer itself
 * cannot be proven headless (client rendering: build green plus the
 * author's in-game check), but the flag that decides between the two
 * layers is common code, so the four rows of the matrix are pinned here:
 * a future "helpful" re-addition of the filled glint fails loudly.
 *
 * <p>The filled state is written directly rather than captured through
 * the economy: this test is about the flag, not the capture path, and
 * the component is exactly what the shimmer and the tint read.</p>
 */
@GameTestHolder(Pseudocore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GlintGameTests {

    private GlintGameTests() {
    }

    @GameTest(template = "test_area")
    public static void foilFollowsTheEnchantmentAlone(GameTestHelper helper) {
        ItemStack empty = copperCore();
        check(!empty.hasFoil(),
                "an empty core must be matte");

        ItemStack enchanted = copperCore();
        enchanted.enchant(helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.PRESERVATION), 1);
        check(enchanted.hasFoil(),
                "an enchanted core must keep the vanilla enchantment glint");

        ItemStack filled = copperCore();
        fill(filled);
        check(!filled.hasFoil(),
                "a filled core must NOT borrow the vanilla glint - the mob shimmer "
                        + "draws instead (#160)");

        ItemStack both = copperCore();
        fill(both);
        both.enchant(helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.PRESERVATION), 1);
        check(both.hasFoil(),
                "filled+enchanted must keep the vanilla glint (the shimmer draws "
                        + "alongside it, on the opposite phase)");
        check(both.has(ModDataComponents.CONTAINED_MOB.get()),
                "filled+enchanted must still carry its fill - both flags, not just foil");

        helper.succeed();
    }

    private static ItemStack copperCore() {
        var item = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElseThrow(() -> new GameTestAssertException("copper core must exist"));
        return new ItemStack(item);
    }

    /** A filled core without running the capture economy (#160's flag test). */
    private static void fill(ItemStack stack) {
        stack.set(ModDataComponents.CONTAINED_MOB.get(),
                new ContainedMobComponent(ResourceLocation.withDefaultNamespace("cow"),
                        new UUID(1L, 2L), new CompoundTag()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
