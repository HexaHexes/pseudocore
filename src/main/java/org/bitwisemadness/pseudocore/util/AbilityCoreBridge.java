package org.bitwisemadness.pseudocore.util;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.bitwisemadness.abilitycore.BuiltinResources;
import org.bitwisemadness.abilitycore.api.ActivationRequest;
import org.bitwisemadness.abilitycore.api.ConsumptionStrategies;
import org.bitwisemadness.abilitycore.api.ResourceConsumers;
import org.bitwisemadness.abilitycore.api.ResourceCost;
import org.bitwisemadness.abilitycore.api.ResourceProviders;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;

/**
 * The Ability Core seam for item costs (#174): the conditional currency
 * choice, the payment-enchantment picker, and the declaration helpers an
 * ability definition's supplier calls at registration.
 *
 * <p>Optional by rule (the {@code IafHornAccess} pattern): callers gate on
 * {@link #isLoaded()}, and this class's own initializer stays free of core
 * types - core-typed constants and lambdas live inside method bodies, which
 * resolve only when invoked, i.e. with core present. The same rule extends
 * to <b>version</b> skew: {@link #seamUsable()} probes for the #90 API so an
 * older neighbor core degrades to a dormant seam instead of a construction
 * crash.</p>
 *
 * <p>Timings (ability-core #88's contract): the picker declares at mod
 * construction via {@link #declareStrategies()} - before RegisterEvent,
 * where definitions register and where {@link #costsFor(double)} runs, by
 * which point every mod is constructed and the {@code has} answers are
 * final. Without Ability Core there is no registration path at all, so an
 * action carries <b>no</b> costs - never an XP placeholder (the #174
 * ruling).</p>
 *
 * <p>Menu visibility (ability-core #124): Pseudocore registers no
 * abilities of its own today, so there is nothing to declare here - the
 * contract's default (listed) stands by construction. Its abilities'
 * siblings live in pseudocore-curios, whose press declares never-visible
 * (exclusive to the curios bar); when Pseudocore gains an ability, its
 * visibility declaration lands beside this bridge's other setup-time
 * declarations.</p>
 */
public final class AbilityCoreBridge {

    /** The registered picker: payment enchantments pick the paying entry (#174). */
    public static final String PICK_STRATEGY = "pseudocore:enchantment_pick";

    /** The Tensura currencies; ids follow ability-core's own examples (battlewills' #12 declares them). */
    public static final String AURA = "tensura:aura";
    public static final String MAGICULE = "tensura:magicule";

    /** What the item at the request's slot bears: the two payment enchantments (#174). */
    public record PaymentFlags(boolean warriorsAura, boolean wizardsMagicules) {
    }

    /** No enchantments - the both-entries default, also the null-player answer. */
    public static final PaymentFlags NO_FLAGS = new PaymentFlags(false, false);

    /**
     * Slot to stack for the picker. The default reads the vanilla hotbar
     * (slotContext as an index, core's documented reading); an addon whose
     * slotContext means something else - pseudocore-curios' bar cells map
     * to Curios slots - swaps this once at construction.
     */
    @FunctionalInterface
    public interface SlotStacks {
        ItemStack stack(ActivationRequest request, ServerPlayer player);
    }

    private static SlotStacks slotStacks;
    private static boolean strategiesDeclared;

    private AbilityCoreBridge() {
    }

    /** True while Tensura Ability Core is installed (null-safe, both sides). */
    public static boolean isLoaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded("ability_core");
    }

    /**
     * True while core is present <b>and</b> new enough for this seam - the
     * #90 strategy API exists (alpha.35+). An older neighbor core fails the
     * probe, so callers skip the seam instead of crashing construction.
     */
    public static boolean seamUsable() {
        // The probe answers presence AND version together: core's classes are
        // on the classpath exactly when the mod is installed, and the #90
        // strategy class exists exactly from alpha.35 on.
        try {
            Class.forName("org.bitwisemadness.abilitycore.api.ConsumptionStrategies", false,
                    AbilityCoreBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            if (isLoaded()) {
                Pseudocore.LOGGER.warn(
                        "Ability Core is present but older than 1.0.0-alpha.35 - the #174 cost seam stays dormant");
            }
            return false;
        }
    }

    /**
     * Declares the payment picker once (core's timing contract: construction,
     * before RegisterEvent resolves the name). Self-gated on
     * {@link #seamUsable()} so every caller is safe with an absent or old core.
     */
    public static void declareStrategies() {
        if (strategiesDeclared || !seamUsable()) {
            return;
        }
        strategiesDeclared = true;
        ConsumptionStrategies.declare(PICK_STRATEGY, AbilityCoreBridge::pickForRequest);
    }

    /**
     * The declared costs for one definition (#174): half magicule + half aura
     * while both Tensura currencies exist, otherwise the core's built-in
     * experience points. Call it from the definition's supplier on
     * {@code RegisterEvent} - by then every mod is constructed, so the
     * {@code has} answers are final (#88's timing contract, the registration-time
     * selection this issue pins). Callers gate on {@link #seamUsable()}: with no
     * core there is no registration path and no costs at all, never a placeholder.
     */
    public static List<ResourceCost> costsFor(double cost) {
        if (!nativeCurrencies()) {
            return List.of(new ResourceCost(BuiltinResources.EXPERIENCE_POINTS, cost));
        }
        double half = cost / 2.0D;
        return List.of(new ResourceCost(MAGICULE, half), new ResourceCost(AURA, half));
    }

    /**
     * The matching consumption strategy: the payment picker while the
     * currencies exist (the selector half of #174), plain {@code all} for the
     * single-entry experience fallback.
     */
    public static String strategyFor() {
        return nativeCurrencies() ? PICK_STRATEGY : ConsumptionStrategies.ALL;
    }

    /** Both halves must exist on both sides - core's #88 pairing rule makes the question symmetric. */
    private static boolean nativeCurrencies() {
        return ResourceProviders.has(AURA) && ResourceProviders.has(MAGICULE)
                && ResourceConsumers.has(AURA) && ResourceConsumers.has(MAGICULE);
    }

    /** The picker's production path: flags from the item at the slot, then the pure pick. */
    private static List<ResourceCost> pickForRequest(ActivationRequest request, ServerPlayer player,
            List<ResourceCost> entries, ConsumptionStrategies.AffordabilityCheck check) {
        return select(entries, flagsFor(request, player));
    }

    /** Enchantments off the slot's stack; no player or no slot reads as no enchantments. */
    private static PaymentFlags flagsFor(ActivationRequest request, ServerPlayer player) {
        int slot = request.slotContext();
        if (player == null || slot < 0) {
            return NO_FLAGS;
        }
        var registries = player.level().registryAccess();
        var stack = source().stack(request, player);
        return new PaymentFlags(
                ModEnchantments.has(registries, stack, ModEnchantments.WARRIORS_AURA),
                ModEnchantments.has(registries, stack, ModEnchantments.WIZARDS_MAGICULES));
    }

    /**
     * The pure pick (#174): {@code warriors_aura} narrows to the aura entry,
     * {@code wizards_magicules} to the magicule one - aura wins when both sit
     * on one item, the same precedence the capture charge uses (#165) - and
     * with neither enchantment every entry pays (the both-entries default).
     * Fixtures feed the flags directly as the stub item input. A requested
     * entry missing from the declared list falls back to every entry: a
     * picker narrows, it never refuses (validation belongs to the pipeline).
     */
    public static List<ResourceCost> select(List<ResourceCost> entries, PaymentFlags flags) {
        if (flags.warriorsAura()) {
            ResourceCost aura = entry(entries, AURA);
            if (aura != null) {
                return List.of(aura);
            }
        } else if (flags.wizardsMagicules()) {
            ResourceCost magicule = entry(entries, MAGICULE);
            if (magicule != null) {
                return List.of(magicule);
            }
        }
        return entries;
    }

    private static ResourceCost entry(List<ResourceCost> entries, String resourceId) {
        for (ResourceCost entry : entries) {
            if (entry.resourceId().equals(resourceId)) {
                return entry;
            }
        }
        return null;
    }

    /** Swap the slot-to-stack reading once at construction; null restores the default. */
    public static void setSlotStacks(SlotStacks source) {
        slotStacks = source;
    }

    private static SlotStacks source() {
        SlotStacks source = slotStacks;
        return source != null ? source : AbilityCoreBridge::defaultStack;
    }

    /** The default reading: slotContext is a vanilla hotbar index (bounds-safe like vanilla's own). */
    private static ItemStack defaultStack(ActivationRequest request, ServerPlayer player) {
        int slot = request.slotContext();
        var inventory = player.getInventory();
        return slot < inventory.getContainerSize() ? inventory.getItem(slot) : ItemStack.EMPTY;
    }
}
