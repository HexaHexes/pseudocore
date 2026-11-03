package org.bitwisemadness.pseudocore.util;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;

/**
 * The capture economy (#159): one cost function for the three "mob goes
 * into a core" verbs - capture, recall and death-preserve - with two
 * backends.
 *
 * <p>With Tensura installed the cost is the mob's <b>EP</b> charged as
 * <b>MP (magicule)</b>, optionally moved to <b>AP (aura)</b> or split
 * between the two by the {@code aura_payment} / {@code split_payment}
 * enchantments. Without it the cost is vanilla <b>XP points</b> at the
 * mob's {@code max HP x captureCost.xpPerMobHp}. {@code cost_reduction}
 * discounts either backend (15% per level, six levels = 90% off), so the
 * reduction enchantment works on any server while the pool pair stays
 * Tensura-only. Release and the clear gesture never call this class.</p>
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
        int reduction = ModEnchantments.levelOf(registries, core, ModEnchantments.COST_REDUCTION);
        if (!TensuraBridge.isLoaded()) {
            return chargeXp(player, mob, reduction);
        }
        return chargeMp(player, mob, core, reduction);
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

    /** EP as MP, with the pool pair moving the charge to AP or half-half. */
    private static Optional<Component> chargeMp(ServerPlayer player, LivingEntity mob, ItemStack core,
                                                int reduction) {
        double cost = epCost(TensuraBridge.readEp(mob).orElse(0.0D), reduction);
        if (cost <= 0.0D) {
            return Optional.empty();
        }
        var pools = TensuraBridge.readPools(player);
        if (pools.isEmpty()) {
            return Optional.empty(); // no storage holder here: no economy, playable
        }
        double aura = pools.get()[0];
        double magicule = pools.get()[1];
        boolean auraOnly = ModEnchantments.has(player.level().registryAccess(), core,
                ModEnchantments.AURA_PAYMENT);
        boolean split = !auraOnly && ModEnchantments.has(player.level().registryAccess(), core,
                ModEnchantments.SPLIT_PAYMENT);
        double mp = auraOnly ? 0.0D : (split ? cost / 2.0D : cost);
        double ap = auraOnly ? cost : (split ? cost / 2.0D : 0.0D);
        if (magicule < mp) {
            return Optional.of(Component.translatable("pseudocore.message.insufficient_mp", Math.ceil(mp)));
        }
        if (aura < ap) {
            return Optional.of(Component.translatable("pseudocore.message.insufficient_ap", Math.ceil(ap)));
        }
        TensuraBridge.writePools(player, aura - ap, magicule - mp);
        return Optional.empty();
    }

    /**
     * Give back a charge taken for a capture that did not happen (#159's
     * dragon path, where IaF's own guard can still refuse after our gates
     * ran). Symmetric recomputation: the same stack, mob and enchantments
     * produce the same amounts, so no handle needs tracking.
     */
    public static void refund(ServerPlayer player, LivingEntity mob, ItemStack core) {
        var registries = player.level().registryAccess();
        int reduction = ModEnchantments.levelOf(registries, core, ModEnchantments.COST_REDUCTION);
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
        double cost = epCost(TensuraBridge.readEp(mob).orElse(0.0D), reduction);
        boolean auraOnly = ModEnchantments.has(registries, core, ModEnchantments.AURA_PAYMENT);
        boolean split = !auraOnly && ModEnchantments.has(registries, core, ModEnchantments.SPLIT_PAYMENT);
        double mp = auraOnly ? 0.0D : (split ? cost / 2.0D : cost);
        double ap = auraOnly ? cost : (split ? cost / 2.0D : 0.0D);
        TensuraBridge.writePools(player, pools.get()[0] + ap, pools.get()[1] + mp);
    }
}
