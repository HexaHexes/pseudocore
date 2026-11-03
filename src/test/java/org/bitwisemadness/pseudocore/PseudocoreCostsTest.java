package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bitwisemadness.pseudocore.util.CaptureCostMath;
import org.junit.jupiter.api.Test;

/**
 * The #159 cost math, in pure JDK: the linear reduction (15% per level,
 * six levels, never free), the EP backend, and the XP backend (whole
 * points, never levels). Both backends must agree on the discount, so the
 * XP formula is pinned against the reduction rather than repeated. The
 * class under test is deliberately free of Minecraft types (the addon's
 * {@code ClearActionMode} convention), so it loads without a game.
 */
class PseudocoreCostsTest {

    @Test
    void reductionIsLinearPerLevelAndNeverFree() {
        assertEquals(0.0D, CaptureCostMath.reductionFor(0));
        assertEquals(0.15D, CaptureCostMath.reductionFor(1), 1e-9);
        assertEquals(0.30D, CaptureCostMath.reductionFor(2), 1e-9);
        assertEquals(0.45D, CaptureCostMath.reductionFor(3), 1e-9);
        assertEquals(0.60D, CaptureCostMath.reductionFor(4), 1e-9);
        assertEquals(0.75D, CaptureCostMath.reductionFor(5), 1e-9);
        assertEquals(0.90D, CaptureCostMath.reductionFor(6), 1e-9);
        assertEquals(0.90D, CaptureCostMath.reductionFor(99), 1e-9,
                "above the enchantment's max level the cap still holds - never free");
    }

    @Test
    void epCostAppliesTheReductionToTheMobEp() {
        assertEquals(100.0D, CaptureCostMath.epCost(100.0D, 0), 1e-9);
        assertEquals(55.0D, CaptureCostMath.epCost(100.0D, 3), 1e-9);
        assertEquals(10.0D, CaptureCostMath.epCost(100.0D, 6), 1e-9,
                "the maximum reduction leaves 10% of the cost");
        assertEquals(0.0D, CaptureCostMath.epCost(0.0D, 6), 1e-9,
                "a mob without EP costs nothing");
    }

    @Test
    void xpCostIsWholePointsScaledByMaxHpAndReduction() {
        // 10 HP x 5 = 50 points at no reduction, rounded up to whole points.
        assertEquals(50L, CaptureCostMath.xpCost(10.0D, 5.0D, 0));
        assertEquals(28L, CaptureCostMath.xpCost(10.0D, 5.0D, 3),
                "45% off 50 points rounds up, never down");
        assertEquals(5L, CaptureCostMath.xpCost(10.0D, 5.0D, 6),
                "the maximum reduction leaves 10% of 50 points");
        assertEquals(0L, CaptureCostMath.xpCost(0.0D, 5.0D, 6));
        // A fractional HP pool still rounds up: a cost is never silently free.
        assertEquals(8L, CaptureCostMath.xpCost(1.5D, 5.0D, 0));
    }
}
