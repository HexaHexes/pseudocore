package org.bitwisemadness.pseudocore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.bitwisemadness.abilitycore.BuiltinResources;
import org.bitwisemadness.abilitycore.activation.ActivationHandlerRegistry;
import org.bitwisemadness.abilitycore.activation.ActivationPipeline;
import org.bitwisemadness.abilitycore.activation.ResourceChargeStep;
import org.bitwisemadness.abilitycore.api.AbilityDefinition;
import org.bitwisemadness.abilitycore.api.ActivationOutcome;
import org.bitwisemadness.abilitycore.api.ActivationRequest;
import org.bitwisemadness.abilitycore.api.ActivationResult;
import org.bitwisemadness.abilitycore.api.ActivationSource;
import org.bitwisemadness.abilitycore.api.ActivationType;
import org.bitwisemadness.abilitycore.api.Affordability;
import org.bitwisemadness.abilitycore.api.AmountResolvers;
import org.bitwisemadness.abilitycore.api.ChargingModel;
import org.bitwisemadness.abilitycore.api.ConsumptionStrategies;
import org.bitwisemadness.abilitycore.api.ResourceConsumer;
import org.bitwisemadness.abilitycore.api.ResourceConsumers;
import org.bitwisemadness.abilitycore.api.ResourceCost;
import org.bitwisemadness.abilitycore.api.ResourceProviders;
import org.bitwisemadness.abilitycore.registry.AbilityRegistries;
import org.bitwisemadness.pseudocore.util.AbilityCoreBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ability Core cost-seam fixtures (#174): conditional currency selection
 * over mocked {@code has} answers, the half split paid through core's real
 * charge pipeline, the payment picker over stub item input, and the
 * construction-time declaration the timing contract pins.
 */
class AbilityCoreBridgeTest {

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("pseudocore", "costed");
    private static final ResourceLocation CATEGORY =
            ResourceLocation.fromNamespaceAndPath("pseudocore", "core");

    private ActivationHandlerRegistry handlers;
    private ActivationPipeline pipeline;
    private Registry<AbilityDefinition> previousRegistry;
    private final List<Affordability> charged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // the construction-time act every fixture below relies on (latched, self-gated)
        AbilityCoreBridge.declareStrategies();

        handlers = new ActivationHandlerRegistry();
        pipeline = new ActivationPipeline(handlers);
        pipeline.registerStep(ActivationPipeline.PipelinePhase.RESOURCE, ResourceChargeStep.INSTANCE);
        previousRegistry = AbilityRegistries.registry();
        var fixture = new MappedRegistry<AbilityDefinition>(
                AbilityRegistries.ABILITY_REGISTRY_KEY, Lifecycle.stable(), false);
        AbilityRegistries.installRegistryForTests(fixture);
    }

    @AfterEach
    void tearDown() {
        AbilityRegistries.installRegistryForTests(previousRegistry);
        for (String id : new String[] {AbilityCoreBridge.AURA, AbilityCoreBridge.MAGICULE}) {
            ResourceConsumers.remove(id);
            ResourceProviders.remove(id);
        }
        charged.clear();
    }

    /** The mocked {@code has} answer: a declared provider+consumer pair for one currency. */
    private void declareCurrency(String id) {
        ResourceProviders.declare(id, (abilityId, player, amount) -> Affordability.sufficient(id, amount));
        ResourceConsumers.declare(id, new ResourceConsumer() {
            @Override
            public void charge(ResourceLocation abilityId, ServerPlayer player, Affordability snapshot) {
                charged.add(snapshot);
            }
        });
    }

    /** The definition exactly as #174's approach builds it, on core's charge pipeline. */
    private void registerCostedAbility(List<ResourceCost> costs, String strategy) {
        Registry.register(AbilityRegistries.registry(), ID, new AbilityDefinition(
                "pseudocore.core.costed", null, CATEGORY, ActivationType.INSTANT,
                costs, strategy, AmountResolvers.STATIC, Set.of(), Map.of()));
        handlers.register(ID, (request, player) -> ActivationResult.ACTED, ChargingModel.EXTERNAL);
    }

    private ActivationResult run() {
        return pipeline.run(ActivationRequest.of(ID, ActivationSource.DIRECT_SLOT), null);
    }

    @Test
    void neitherCurrencyRegisteredFallsBackToExperiencePoints() {
        // no fake pair exists, so both has() answers are false: the fallback wins
        assertEquals(List.of(new ResourceCost(BuiltinResources.EXPERIENCE_POINTS, 60.0)),
                AbilityCoreBridge.costsFor(60.0D),
                "acceptance: experience_points when no Tensura currency exists");
        assertEquals(ConsumptionStrategies.ALL, AbilityCoreBridge.strategyFor(),
                "the single-entry fallback pays under all");
    }

    @Test
    void bothCurrenciesRegisteredSplitTheCostInHalves() {
        declareCurrency(AbilityCoreBridge.AURA);
        declareCurrency(AbilityCoreBridge.MAGICULE);
        assertEquals(List.of(
                new ResourceCost(AbilityCoreBridge.MAGICULE, 30.0),
                new ResourceCost(AbilityCoreBridge.AURA, 30.0)),
                AbilityCoreBridge.costsFor(60.0D),
                "acceptance: two half-amount entries, magicule first, under the picker");
        assertEquals(AbilityCoreBridge.PICK_STRATEGY, AbilityCoreBridge.strategyFor());
    }

    @Test
    void bothHalvesArePaidWhenNoEnchantmentNarrowsTheSelection() {
        declareCurrency(AbilityCoreBridge.AURA);
        declareCurrency(AbilityCoreBridge.MAGICULE);
        var costs = AbilityCoreBridge.costsFor(60.0);
        registerCostedAbility(costs, AbilityCoreBridge.strategyFor());

        assertEquals(costs, AbilityCoreBridge.select(costs, AbilityCoreBridge.NO_FLAGS),
                "no enchantment selects every entry - the all behavior");
        var result = run();
        assertEquals(ActivationOutcome.ACTED, result.outcome());
        assertEquals(List.of(AbilityCoreBridge.MAGICULE, AbilityCoreBridge.AURA),
                charged.stream().map(Affordability::resourceId).toList(),
                "both halves charged, in declaration order");
        assertEquals(List.of(30.0, 30.0),
                charged.stream().map(Affordability::amount).toList());
    }

    @Test
    void thePickerNarrowsToThePayingEnchantment() {
        var entries = List.of(
                new ResourceCost(AbilityCoreBridge.MAGICULE, 30.0),
                new ResourceCost(AbilityCoreBridge.AURA, 30.0));
        // stub item input: the flags each enchanted core would read at the slot
        assertEquals(List.of(entries.get(1)), AbilityCoreBridge.select(entries,
                new AbilityCoreBridge.PaymentFlags(true, false)),
                "warriors_aura pays only the aura entry");
        assertEquals(List.of(entries.get(0)), AbilityCoreBridge.select(entries,
                new AbilityCoreBridge.PaymentFlags(false, true)),
                "wizards_magicules pays only the magicule entry");
        assertEquals(List.of(entries.get(1)), AbilityCoreBridge.select(entries,
                new AbilityCoreBridge.PaymentFlags(true, true)),
                "aura wins when both enchantments sit on one item (#165 precedence)");
        assertEquals(entries, AbilityCoreBridge.select(entries, AbilityCoreBridge.NO_FLAGS),
                "no enchantment keeps the both-entries default");
        var fallback = List.of(new ResourceCost(BuiltinResources.EXPERIENCE_POINTS, 60.0));
        assertEquals(fallback, AbilityCoreBridge.select(fallback,
                new AbilityCoreBridge.PaymentFlags(true, false)),
                "a requested entry missing from the list falls back to all, never a refusal");
    }

    @Test
    void thePickerDeclaresOnceAtConstruction() {
        // repeat calls stay silent (core's duplicate-name guard never fires)
        AbilityCoreBridge.declareStrategies();
        AbilityCoreBridge.declareStrategies();
        assertTrue(ConsumptionStrategies.has(AbilityCoreBridge.PICK_STRATEGY),
                "the construction-time declaration resolves");
        assertTrue(AbilityCoreBridge.seamUsable(),
                "the test classpath carries a core new enough for the seam");
    }
}
