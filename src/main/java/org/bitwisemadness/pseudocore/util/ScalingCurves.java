package org.bitwisemadness.pseudocore.util;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The payment scaling curves: a curve bends the mob's scale - its max
 * health, or the EP basis for the pool pair - before the wallet's factor
 * and the {@code core_efficiency} reduction apply, so each payment
 * enchantment can shape its own price curve per pack.
 *
 * <p>Two types ship as defaults: {@link #LINEAR} (straight proportion -
 * today's behaviour, and the default for every wallet) and
 * {@link #QUADRATIC} ({@code x*x / reference}), which meets linear at
 * the configured {@code captureCost.curveReference} - a small mob costs
 * the same either way and the curves diverge with size, so a quadratic
 * wallet is the pack's way of making big mobs disproportionately
 * expensive in that wallet.</p>
 *
 * <p>Custom types are registerable code: {@link #declare} at mod
 * construction - the same pattern as core's consumption strategies (#90)
 * - so an integrator can shape a wallet without core or pseudocore
 * changes. An unregistered name in the config degrades to linear with a
 * warning rather than refusing the capture.</p>
 */
public final class ScalingCurves {

    /** Straight proportion: {@code x} - the default for every wallet. */
    public static final String LINEAR = "linear";

    /** {@code x*x / reference}: super-linear, equal to linear at the reference. */
    public static final String QUADRATIC = "quadratic";

    /**
     * Where linear and quadratic meet - a mob of this scale costs the
     * same under either curve. The config default; also the fail-open
     * value for a pre-load read.
     */
    public static final double DEFAULT_REFERENCE = 10.0D;

    /**
     * One curve's shape: the raw mob scale in, the shaped scale out.
     *
     * @param x         the raw scale (max health, or EP for the pool pair)
     * @param reference where linear and quadratic meet (the config's
     *                  {@code curveReference})
     */
    @FunctionalInterface
    public interface ScalingCurve {
        double apply(double x, double reference);
    }

    private static final Map<String, ScalingCurve> CURVES = new ConcurrentHashMap<>();

    static {
        declare(LINEAR, (x, reference) -> x);
        declare(QUADRATIC, (x, reference) -> x * x / reference);
    }

    private ScalingCurves() {
    }

    /**
     * Declares one named curve.
     *
     * @throws IllegalArgumentException if the name is null/blank or the curve is null
     * @throws IllegalStateException    if the name is already declared (loud
     *         ownership conflict, never silent overwrite)
     */
    public static void declare(String name, ScalingCurve curve) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scaling curve name must not be blank");
        }
        if (curve == null) {
            throw new IllegalArgumentException("scaling curve must not be null for " + name);
        }
        ScalingCurve previous = CURVES.putIfAbsent(name, curve);
        if (previous != null) {
            throw new IllegalStateException(
                    "Duplicate scaling curve '" + name + "' - two owners for one name");
        }
    }

    public static Optional<ScalingCurve> get(String name) {
        return Optional.ofNullable(name == null ? null : CURVES.get(name));
    }

    /** Existence query for config validation and test isolation. */
    public static boolean has(String name) {
        return name != null && CURVES.containsKey(name);
    }

    /** Test isolation: removes a declared curve (built-ins included). */
    public static boolean remove(String name) {
        return name != null && CURVES.remove(name) != null;
    }
}
