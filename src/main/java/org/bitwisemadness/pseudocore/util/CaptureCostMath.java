package org.bitwisemadness.pseudocore.util;

/**
 * The #159 cost math, deliberately free of Minecraft types so it can be
 * pinned by a plain junit test (the addon's {@code ClearActionMode}
 * precedent): the linear reduction (15% per level, six levels, never
 * free), the EP backend, and the XP backend (whole points, never levels).
 *
 * <p>The side-effecting charge and refund live in
 * {@link PseudocoreCosts}, which reads pools and enchantments.</p>
 */
public final class CaptureCostMath {

    /** Per level, so level VI = 90% off and a cast is never free. */
    public static final double REDUCTION_PER_LEVEL = 0.15D;

    /** The reduction enchantment's maximum level (#159's linear table). */
    public static final int MAX_REDUCTION_LEVELS = 6;

    /** The XP backend's default per-HP multiplier (#159), also the config default. */
    public static final int DEFAULT_XP_PER_MOB_HP = 5;

    private CaptureCostMath() {
    }

    /** The fraction a given reduction level takes off, capped at 90%. */
    public static double reductionFor(int level) {
        if (level <= 0) {
            return 0.0D;
        }
        // Rounded to three decimals so the level-6 cap is exactly 0.90 and
        // the XP ceil never gains a phantom point: 0.15 * 6 is 0.8999999...
        // in binary, which would round 50 points up to 6 instead of 5.
        double reduction = Math.min(REDUCTION_PER_LEVEL * level, 0.90D);
        return Math.round(reduction * 1000.0D) / 1000.0D;
    }

    /** The EP/MP backend's final cost after reduction (fractional). */
    public static double epCost(double mobEp, int reductionLevel) {
        return Math.max(0.0D, mobEp * (1.0D - reductionFor(reductionLevel)));
    }

    /** The XP backend's cost in whole experience points (#159). */
    public static long xpCost(double mobMaxHealth, double xpPerMobHp, int reductionLevel) {
        double raw = mobMaxHealth * xpPerMobHp * (1.0D - reductionFor(reductionLevel));
        return raw <= 0.0D ? 0L : (long) Math.ceil(raw);
    }
}
