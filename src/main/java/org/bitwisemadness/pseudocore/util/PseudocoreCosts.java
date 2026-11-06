package org.bitwisemadness.pseudocore.util;

import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.abilitycore.BuiltinResources;
import org.bitwisemadness.abilitycore.api.Affordability;
import org.bitwisemadness.abilitycore.api.ResourceConsumers;
import org.bitwisemadness.abilitycore.api.ResourceProviders;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;

/**
 * The capture economy (#159): one cost function for the three "mob goes
 * into a core" verbs - capture, recall and death-preserve - with two
 * backends.
 *
 * <p>With Tensura installed the cost is the mob's <b>EP</b>, split
 * evenly between <b>MP (magicule)</b> and <b>AP (aura)</b> by default
 * (#165), or moved wholly to one pool by the {@code warriors_aura} /
 * {@code wizards_magicules} enchantments. Without it the cost is vanilla
 * <b>XP points</b> at the mob's {@code max HP x captureCost.xpPerMobHp}.
 * {@code core_efficiency} discounts either backend (15% per level, six
 * levels = 90% off), so the reduction enchantment works on any server
 * while the pool pair stays Tensura-only. Release and the clear gesture
 * never call this class.</p>
 *
 * <p><b>Payment routing (the pay_with_* family):</b> a payment
 * enchantment switches the <em>wallet</em> while the price stays the
 * reduction-scaled cost - the charge then runs through Ability Core's
 * provider/consumer pair (validate-then-charge), falling back to the
 * native paths whenever the pair is not registered. Each payment
 * enchantment's price shape is its own configured curve
 * ({@link ScalingCurves}: linear by default, quadratic available,
 * custom types declarable), bent into the mob scale before the wallet's
 * factor and the reduction apply. Every core query
 * sits behind {@link AbilityCoreBridge#seamUsable()}, so no core class
 * is touched when the library is absent - and without it the
 * pay_with_* enchantments do not ship at all (their JSON conditions).
 * Foreign pairs the charge can route to but not invert refund through
 * the native mirrors only while they are ours; ability-core#107's refund
 * contract covers the rest.</p>
 *
 * <p>Nothing here throws: an unreadable pool, a missing enchantment or a
 * mob without EP degrade to a zero cost rather than a refusal, because
 * "no economy in this environment" must stay playable.</p>
 */
public final class PseudocoreCosts {

    /** Per level, so level VI = 90% off and a cast is never free. */
    public static final double REDUCTION_PER_LEVEL = CaptureCostMath.REDUCTION_PER_LEVEL;

    /** The reduction enchantment's maximum level (#159's linear table). */
    public static final int MAX_REDUCTION_LEVELS = CaptureCostMath.MAX_REDUCTION_LEVELS;

    /** The XP backend's default per-HP multiplier (#159), also the config default. */
    public static final int DEFAULT_XP_PER_MOB_HP = CaptureCostMath.DEFAULT_XP_PER_MOB_HP;

    /** One payment level per five points of the mob's health (the levels wallet). */
    private static final double MOB_HP_PER_LEVEL = 5.0D;

    /** What routed payments pass to core's pair as their origin (diagnostics only). */
    private static final ResourceLocation PAYMENT_ID =
            ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, "capture");

    private PseudocoreCosts() {
    }

    /** The pure math lives in {@link CaptureCostMath}, which junit pins directly. */
    public static double reductionFor(int level) {
        return CaptureCostMath.reductionFor(level);
    }

    /** The MP/EP backend's final cost, after reduction. */
    public static double epCost(double mobEp, int reductionLevel) {
        return CaptureCostMath.epCost(mobEp, reductionLevel);
    }

    /** The XP backend's cost in whole experience points (#159). */
    public static long xpCost(double mobMaxHealth, double xpPerMobHp, int reductionLevel) {
        return CaptureCostMath.xpCost(mobMaxHealth, xpPerMobHp, reductionLevel);
    }

    /**
     * Charge one "mob goes in" verb (#159). Returns empty when the cost
     * was charged (or was zero), or the message to show the player when
     * their pool is short - in which case nothing is consumed and the
     * caller must refuse the verb.
     *
     * @param player who pays; also the enchantment carrier's owner
     * @param mob    the mob going in (EP or max HP basis)
     * @param core   the core being filled (its enchantments decide the
     *               reduction and the pool)
     */
    public static Optional<Component> charge(ServerPlayer player, LivingEntity mob, ItemStack core) {
        var registries = player.level().registryAccess();
        int reduction = ModEnchantments.levelOf(registries, core, ModEnchantments.CORE_EFFICIENCY);
        String resource = routedResource(registries, core);
        String curve = curveFor(registries, core);
        if (resource != null) {
            return chargeRouted(player, mob, resource, curve, reduction);
        }
        if (!TensuraBridge.isLoaded()) {
            // The XP fallback is the default wallet, not a payment
            // enchantment's - no curve governs it.
            return chargeXp(player, mob, reduction);
        }
        return chargeMp(player, mob, core, curve, reduction);
    }

    /**
     * The governing payment enchantment's curve (same precedence as the
     * wallet choice), or linear when no payment enchantment rules the
     * core - so every default price keeps today's straight proportion.
     */
    private static String curveFor(HolderLookup.Provider registries, ItemStack core) {
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_HEALTH)) {
            return PseudocoreConfig.paymentCurve("pay_with_health");
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_HUNGER)) {
            return PseudocoreConfig.paymentCurve("pay_with_hunger");
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_EXPERIENCE_POINTS)) {
            return PseudocoreConfig.paymentCurve("pay_with_experience_points");
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_EXPERIENCE_LEVELS)) {
            return PseudocoreConfig.paymentCurve("pay_with_experience_levels");
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.WARRIORS_AURA)) {
            return PseudocoreConfig.paymentCurve("warriors_aura");
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.WIZARDS_MAGICULES)) {
            return PseudocoreConfig.paymentCurve("wizards_magicules");
        }
        return ScalingCurves.LINEAR;
    }

    /**
     * The wallet's shaped scale: its curve over the raw mob scale, at the
     * configured reference. An unknown curve name degrades to linear with
     * a warning - a config typo must never refuse a capture. Package
     * visibility so the fixtures pin the shapes.
     */
    static double shaped(String curveName, double rawScale) {
        var curve = ScalingCurves.get(curveName);
        if (curve.isEmpty()) {
            Pseudocore.LOGGER.warn("Unknown payment curve '{}' - pricing linearly", curveName);
            curve = ScalingCurves.get(ScalingCurves.LINEAR);
        }
        return curve.orElseThrow().apply(rawScale, PseudocoreConfig.paymentCurveReference());
    }

    /** The EP basis under the wallet's curve, then the reduction (linear = {@link #epCost}). */
    private static double shapedEp(String curve, double ep, int reduction) {
        return shaped(curve, ep) * (1.0D - reductionFor(reduction));
    }

    /**
     * The payment enchantment's resource, or null for the native split: a
     * {@code pay_with_*} switch picks its core pair outright, and the pool
     * pair picks {@code tensura:aura}/{@code tensura:magicule} - but only
     * while those consumers are registered (core's pairing rule), falling
     * back to the native pool write exactly as before. Every core query
     * sits behind {@link AbilityCoreBridge#seamUsable()}, so an absent or
     * old Ability Core is never asked a question it cannot answer; without
     * core the pay_with_* enchantments do not ship at all (JSON conditions).
     */
    static String routedResource(HolderLookup.Provider registries, ItemStack core) {
        if (!AbilityCoreBridge.seamUsable()) {
            return null;
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_HEALTH)
                && pair(BuiltinResources.HEALTH)) {
            return BuiltinResources.HEALTH;
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_HUNGER)
                && pair(BuiltinResources.HUNGER)) {
            return BuiltinResources.HUNGER;
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_EXPERIENCE_POINTS)
                && pair(BuiltinResources.EXPERIENCE_POINTS)) {
            return BuiltinResources.EXPERIENCE_POINTS;
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.PAY_WITH_EXPERIENCE_LEVELS)
                && pair(BuiltinResources.EXPERIENCE_LEVELS)) {
            return BuiltinResources.EXPERIENCE_LEVELS;
        }
        if (!TensuraBridge.isLoaded()) {
            return null;
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.WARRIORS_AURA)
                && pair(AbilityCoreBridge.AURA)) {
            return AbilityCoreBridge.AURA;
        }
        if (ModEnchantments.has(registries, core, ModEnchantments.WIZARDS_MAGICULES)
                && pair(AbilityCoreBridge.MAGICULE)) {
            return AbilityCoreBridge.MAGICULE;
        }
        return null;
    }

    /** Both halves of core's pairing rule for one resource (#88). */
    private static boolean pair(String resourceId) {
        return ResourceProviders.has(resourceId) && ResourceConsumers.has(resourceId);
    }

    /**
     * The routed charge: validate through the pair's provider, charge through
     * its consumer - core's validate-then-charge, so a short pool refuses the
     * verb with nothing consumed, exactly like the native paths' messages.
     * The pool pair keeps its MP/AP keys; the four core wallets speak one
     * prettified line (foreign pairs included, forward-compatible).
     */
    private static Optional<Component> chargeRouted(ServerPlayer player, LivingEntity mob,
                                                    String resource, String curve, int reduction) {
        double amount = isPoolPair(resource)
                ? shapedEp(curve, TensuraBridge.readEp(mob).orElse(0.0D), reduction)
                : routedAmount(resource, shaped(curve, mob.getMaxHealth()), reduction,
                        PseudocoreConfig.captureCostXpPerMobHp());
        var provider = ResourceProviders.get(resource).orElse(null);
        var consumer = ResourceConsumers.get(resource).orElse(null);
        if (provider == null || consumer == null) {
            // pair() answered moments ago; a vanished pair refuses rather
            // than letting the verb run free (fail closed, defensive).
            return Optional.of(insufficient(resource, amount));
        }
        Affordability verdict = provider.check(PAYMENT_ID, player, amount);
        if (verdict == null || verdict.state() != Affordability.State.SUFFICIENT) {
            return Optional.of(insufficient(resource, amount));
        }
        consumer.charge(PAYMENT_ID, player, verdict);
        return Optional.empty();
    }

    /** The Tensura pair: its price is the EP basis, its refund the pools. */
    private static boolean isPoolPair(String resource) {
        return AbilityCoreBridge.AURA.equals(resource) || AbilityCoreBridge.MAGICULE.equals(resource);
    }

    /**
     * The routed amount for the four core wallets over the shaped mob
     * scale (the wallet's curve applied by the caller - linear keeps it
     * the raw max health): health and hunger take the scale itself,
     * levels one per five points of it, and experience points the XP
     * backend's number - each after the same {@code core_efficiency}
     * reduction the native backends apply, so the enchantment switches
     * the wallet, never the price. Pure for fixtures.
     */
    static double routedAmount(String resource, double scale, int reductionLevel,
                               double xpPerMobHp) {
        if (BuiltinResources.EXPERIENCE_POINTS.equals(resource)) {
            return xpCost(scale, xpPerMobHp, reductionLevel);
        }
        double scaled = scale * (1.0D - reductionFor(reductionLevel));
        if (BuiltinResources.HEALTH.equals(resource) || BuiltinResources.HUNGER.equals(resource)) {
            return scaled;
        }
        if (BuiltinResources.EXPERIENCE_LEVELS.equals(resource)) {
            return Math.ceil(scaled / MOB_HP_PER_LEVEL);
        }
        throw new IllegalArgumentException("not a routed core wallet: " + resource);
    }

    /** The refusal line for a routed payment; the pool pair keeps its historic keys. */
    private static Component insufficient(String resource, double amount) {
        if (AbilityCoreBridge.AURA.equals(resource)) {
            return Component.translatable("pseudocore.message.insufficient_ap", Math.ceil(amount));
        }
        if (AbilityCoreBridge.MAGICULE.equals(resource)) {
            return Component.translatable("pseudocore.message.insufficient_mp", Math.ceil(amount));
        }
        if (BuiltinResources.EXPERIENCE_POINTS.equals(resource)) {
            return Component.translatable("pseudocore.message.insufficient_xp", Math.ceil(amount));
        }
        return Component.translatable("pseudocore.message.insufficient_resource",
                amountText(amount), resourceName(resource));
    }

    /** Whole numbers without a trailing .0 - the figure a pool needs to see. */
    private static String amountText(double amount) {
        return amount == Math.floor(amount) && !Double.isInfinite(amount)
                ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    /** The wallet's display name: ours for core's ids, prettified for foreign ones. */
    static String resourceName(String resourceId) {
        if (BuiltinResources.HEALTH.equals(resourceId)) {
            return "Health";
        }
        if (BuiltinResources.HUNGER.equals(resourceId)) {
            return "Hunger";
        }
        if (BuiltinResources.EXPERIENCE_POINTS.equals(resourceId)) {
            return "Experience";
        }
        if (BuiltinResources.EXPERIENCE_LEVELS.equals(resourceId)) {
            return "Levels";
        }
        String path = resourceId.substring(resourceId.indexOf(':') + 1).replace('_', ' ');
        return Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    /** XP points, never levels: total points checked, then deducted. */
    private static Optional<Component> chargeXp(ServerPlayer player, LivingEntity mob, int reduction) {
        long cost = xpCost(mob.getMaxHealth(), PseudocoreConfig.captureCostXpPerMobHp(), reduction);
        if (cost <= 0L) {
            return Optional.empty();
        }
        if (player.totalExperience < cost) {
            return Optional.of(Component.translatable("pseudocore.message.insufficient_xp", cost));
        }
        player.giveExperiencePoints(-(int) cost);
        return Optional.empty();
    }

    /**
     * EP as MP: half-half by default, wholly moved to one pool by the
     * payment enchantments (#165).
     */
    private static Optional<Component> chargeMp(ServerPlayer player, LivingEntity mob, ItemStack core,
                                                String curve, int reduction) {
        double cost = shapedEp(curve, TensuraBridge.readEp(mob).orElse(0.0D), reduction);
        if (cost <= 0.0D) {
            return Optional.empty();
        }
        var pools = TensuraBridge.readPools(player);
        if (pools.isEmpty()) {
            return Optional.empty(); // no storage holder here: no economy, playable
        }
        double aura = pools.get()[0];
        double magicule = pools.get()[1];
        Shares shares = shares(player.level().registryAccess(), core, cost);
        if (magicule < shares.mp()) {
            return Optional.of(Component.translatable("pseudocore.message.insufficient_mp",
                    Math.ceil(shares.mp())));
        }
        if (aura < shares.ap()) {
            return Optional.of(Component.translatable("pseudocore.message.insufficient_ap",
                    Math.ceil(shares.ap())));
        }
        TensuraBridge.writePools(player, aura - shares.ap(), magicule - shares.mp());
        return Optional.empty();
    }

    /** One cost's pool shares (#165): charge takes them, refund gives them back. */
    private record Shares(double mp, double ap) {
    }

    /**
     * How a cost splits across the pools for this core (#165):
     * {@code warriors_aura} takes it all to AP, {@code wizards_magicules}
     * all to MP, and without either the default splits it evenly - the
     * aura enchantment wins if both sit on one core. Charge and refund
     * both read it, so the two can never drift apart.
     */
    private static Shares shares(HolderLookup.Provider registries, ItemStack core, double cost) {
        boolean auraOnly = ModEnchantments.has(registries, core, ModEnchantments.WARRIORS_AURA);
        boolean magiculeOnly = !auraOnly
                && ModEnchantments.has(registries, core, ModEnchantments.WIZARDS_MAGICULES);
        double mp = auraOnly ? 0.0D : (magiculeOnly ? cost : cost / 2.0D);
        double ap = auraOnly ? cost : (magiculeOnly ? 0.0D : cost / 2.0D);
        return new Shares(mp, ap);
    }

    /**
     * Give back a charge taken for a capture that did not happen (#159's
     * dragon path, where IaF's own guard can still refuse after our gates
     * ran). Symmetric recomputation: the same stack, mob and enchantments
     * produce the same amounts, so no handle needs tracking.
     */
    public static void refund(ServerPlayer player, LivingEntity mob, ItemStack core) {
        var registries = player.level().registryAccess();
        int reduction = ModEnchantments.levelOf(registries, core, ModEnchantments.CORE_EFFICIENCY);
        String resource = routedResource(registries, core);
        String curve = curveFor(registries, core);
        if (resource != null) {
            refundRouted(player, mob, resource, curve, reduction);
            return;
        }
        if (!TensuraBridge.isLoaded()) {
            long cost = xpCost(mob.getMaxHealth(), PseudocoreConfig.captureCostXpPerMobHp(), reduction);
            if (cost > 0L) {
                player.giveExperiencePoints((int) cost);
            }
            return;
        }
        var pools = TensuraBridge.readPools(player);
        if (pools.isEmpty()) {
            return;
        }
        double cost = shapedEp(curve, TensuraBridge.readEp(mob).orElse(0.0D), reduction);
        Shares shares = shares(registries, core, cost);
        TensuraBridge.writePools(player, pools.get()[0] + shares.ap(), pools.get()[1] + shares.mp());
    }

    /**
     * Give back a routed charge symmetrically: the pool pair writes the
     * same Tensura pools its consumer writes, and the four core wallets
     * mirror core's own consumer arithmetic - health restored exactly,
     * integral pools refunded as the ceil() its deductIntegral charged
     * (the pre-charge provider check guarantees the deduction never hit
     * the floor, so the mirror is exact). Foreign pairs this cannot
     * mirror are ability-core#107's refund contract to own.
     */
    private static void refundRouted(ServerPlayer player, LivingEntity mob, String resource,
                                     String curve, int reduction) {
        if (isPoolPair(resource)) {
            var pools = TensuraBridge.readPools(player);
            if (pools.isEmpty()) {
                return;
            }
            double amount = shapedEp(curve, TensuraBridge.readEp(mob).orElse(0.0D), reduction);
            if (AbilityCoreBridge.AURA.equals(resource)) {
                TensuraBridge.writePools(player, pools.get()[0] + amount, pools.get()[1]);
            } else {
                TensuraBridge.writePools(player, pools.get()[0], pools.get()[1] + amount);
            }
            return;
        }
        double amount = routedAmount(resource, shaped(curve, mob.getMaxHealth()), reduction,
                PseudocoreConfig.captureCostXpPerMobHp());
        if (BuiltinResources.HEALTH.equals(resource)) {
            player.setHealth((float) Math.min(player.getMaxHealth(), player.getHealth() + amount));
        } else if (BuiltinResources.HUNGER.equals(resource)) {
            var food = player.getFoodData();
            food.setFoodLevel(Math.min(20, food.getFoodLevel() + (int) Math.ceil(amount)));
        } else if (BuiltinResources.EXPERIENCE_POINTS.equals(resource)) {
            player.giveExperiencePoints((int) Math.ceil(amount));
        } else if (BuiltinResources.EXPERIENCE_LEVELS.equals(resource)) {
            player.giveExperienceLevels((int) Math.ceil(amount));
        }
    }
}
