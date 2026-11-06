package org.bitwisemadness.pseudocore.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bitwisemadness.abilitycore.BuiltinResources;
import org.junit.jupiter.api.Test;

/**
 * The payment-routing math (#174's payment family): the four core wallets
 * scale with the mob exactly like the native backends do - health and
 * hunger take the mob's health, levels one per five points of it, and
 * experience points the XP backend's own figure - every one under the
 * same {@code core_efficiency} reduction, so the enchantment switches the
 * wallet and never the price. The foreign-name prettifier pins how an
 * integrator's resource reads in the refusal line.
 */
class PaymentRoutingTest {

    @Test
    void healthAndHungerTakeTheMobHealthUnderReduction() {
        assertEquals(10.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.HEALTH, 10.0D, 0, 5.0D), 1e-9);
        assertEquals(10.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.HUNGER, 10.0D, 0, 5.0D), 1e-9,
                "hunger is priced identically to health");
        assertEquals(55.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.HEALTH, 100.0D, 3, 5.0D), 1e-9,
                "45% off - the same reduction the native backends apply");
        assertEquals(10.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.HEALTH, 100.0D, 6, 5.0D), 1e-9,
                "the maximum reduction leaves 10% - never free");
    }

    @Test
    void levelsCostOnePerFiveMobHealthPoints() {
        assertEquals(2.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.EXPERIENCE_LEVELS, 10.0D, 0, 5.0D), 1e-9);
        assertEquals(3.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.EXPERIENCE_LEVELS, 13.0D, 0, 5.0D), 1e-9,
                "ceil - a fractional level rounds up, never down");
        assertEquals(1.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.EXPERIENCE_LEVELS, 10.0D, 6, 5.0D), 1e-9,
                "reduction first, then ceil - never free");
    }

    @Test
    void experiencePointsKeepTheXpBackendNumber() {
        assertEquals(50.0D, PseudocoreCosts.routedAmount(
                BuiltinResources.EXPERIENCE_POINTS, 10.0D, 0, 5.0D), 1e-9);
        assertEquals(CaptureCostMath.xpCost(10.0D, 5.0D, 3),
                PseudocoreCosts.routedAmount(
                        BuiltinResources.EXPERIENCE_POINTS, 10.0D, 3, 5.0D), 1e-9,
                "the XP wallet is the XP backend's figure, reduction included");
    }

    @Test
    void unknownResourcesAreRejectedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> PseudocoreCosts.routedAmount(
                AbilityCoreBridge.AURA, 10.0D, 0, 5.0D),
                "the pool pair prices from EP, never from this table");
    }

    @Test
    void walletNamesAreOursOrPrettified() {
        assertEquals("Health", PseudocoreCosts.resourceName(BuiltinResources.HEALTH));
        assertEquals("Hunger", PseudocoreCosts.resourceName(BuiltinResources.HUNGER));
        assertEquals("Experience", PseudocoreCosts.resourceName(BuiltinResources.EXPERIENCE_POINTS));
        assertEquals("Levels", PseudocoreCosts.resourceName(BuiltinResources.EXPERIENCE_LEVELS));
        assertEquals("Aether", PseudocoreCosts.resourceName("technomancy:aether"),
                "a foreign id reads as words, not as a raw identifier");
        assertEquals("Mana pool", PseudocoreCosts.resourceName("some_mod:mana_pool"),
                "only the first letter capitalizes - words stay as the id spells them");
    }

    @Test
    void shapedAppliesTheCurveAtTheConfiguredReference() {
        // The config is not loaded in fixtures, so the reference falls open
        // to ScalingCurves.DEFAULT_REFERENCE (10) - exactly the production
        // fail-open rule.
        assertEquals(10.0D, PseudocoreCosts.shaped(ScalingCurves.LINEAR, 10.0D), 1e-9);
        assertEquals(40.0D, PseudocoreCosts.shaped(ScalingCurves.QUADRATIC, 20.0D), 1e-9,
                "quadratic bends the raw scale at the default reference");
        assertEquals(10.0D, PseudocoreCosts.shaped("not_a_curve", 10.0D), 1e-9,
                "an unknown curve name degrades to linear, never refuses a capture");
    }
}
