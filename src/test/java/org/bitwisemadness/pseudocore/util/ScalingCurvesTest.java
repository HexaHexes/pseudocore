package org.bitwisemadness.pseudocore.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The payment scaling curves: linear is the identity everywhere (the
 * default price, unchanged), quadratic meets it at the configured
 * reference and diverges with size - cheaper below, steeper above - and
 * the registry keeps custom types loud-owned like core's strategies.
 */
class ScalingCurvesTest {

    @AfterEach
    void isolate() {
        ScalingCurves.remove("test:custom");
    }

    @Test
    void linearIsTheIdentityEverywhere() {
        var linear = ScalingCurves.get(ScalingCurves.LINEAR).orElseThrow();
        assertEquals(10.0D, linear.apply(10.0D, 10.0D), 1e-9);
        assertEquals(250.0D, linear.apply(250.0D, 10.0D), 1e-9,
                "linear never bends the scale - the default price");
        assertEquals(0.0D, linear.apply(0.0D, 10.0D), 1e-9,
                "a mob without a scale still costs nothing");
    }

    @Test
    void quadraticMeetsLinearAtTheReferenceAndDivergesWithSize() {
        var quadratic = ScalingCurves.get(ScalingCurves.QUADRATIC).orElseThrow();
        assertEquals(10.0D, quadratic.apply(10.0D, 10.0D), 1e-9,
                "at the reference both curves charge the same");
        assertEquals(2.5D, quadratic.apply(5.0D, 10.0D), 1e-9,
                "below the reference quadratic is cheaper (x*x / reference)");
        assertEquals(40.0D, quadratic.apply(20.0D, 10.0D), 1e-9,
                "above the reference quadratic is steeper - big mobs cost more");
    }

    @Test
    void declareEnforcesLoudOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> ScalingCurves.declare(null, (x, reference) -> x));
        assertThrows(IllegalArgumentException.class,
                () -> ScalingCurves.declare("  ", (x, reference) -> x));
        assertThrows(IllegalArgumentException.class,
                () -> ScalingCurves.declare("test:custom", null));

        ScalingCurves.declare("test:custom", (x, reference) -> x * 2);
        assertTrue(ScalingCurves.has("test:custom"));
        assertThrows(IllegalStateException.class,
                () -> ScalingCurves.declare("test:custom", (x, reference) -> x),
                "two owners for one name must fail loudly");
        assertTrue(ScalingCurves.remove("test:custom"), "test isolation");
        assertFalse(ScalingCurves.has("test:custom"));
    }

    @Test
    void existenceQueriesAreNullSafe() {
        assertFalse(ScalingCurves.has(null));
        assertTrue(ScalingCurves.get(null).isEmpty());
        assertFalse(ScalingCurves.remove(null));
        assertFalse(ScalingCurves.has("test:absent"));
        assertTrue(ScalingCurves.get(ScalingCurves.QUADRATIC).isPresent(),
                "both built-ins ship pre-registered");
    }
}
