package org.bitwisemadness.pseudocore.gametest;

import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.mobinfocommon.variant.VariantHelper;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.event.PseudocoreReleaseEvent;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;
import org.bitwisemadness.pseudocore.registry.ModItems;

/**
 * The per-tier capture limits: every tier has a config section, the sections
 * default permissive (a tier limits nothing until a pack opts in), and the
 * limits are actually enforced on the capture path.
 *
 * <p>The enforcement test flips {@code allowTamedMobs} on the live config value
 * and restores it in a finally block - the config file the gametest server uses
 * is the dev run's own, so anything the test sets must be put back. The
 * refused-then-allowed pair around that flip is what proves the gate reads the
 * config per capture rather than caching it anywhere.</p>
 *
 * <p>A tamed wolf is the probe for allowTamedMobs because the anti-theft rule
 * must not interfere: the wolf is tamed by the very player attempting the
 * capture, so the only thing standing between it and the horn is the tier
 * switch.</p>
 */
@GameTestHolder(Pseudocore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TierCaptureConfigGameTests {

    private static final List<String> TIER_KEYS = List.of(
            "copper", "iron", "silver", "gold", "netherite",
            "lowMagisteel", "highMagisteel", "pureMagisteel", "mithril", "orichalcum", "adamantite",
            "hihiirokane");

    /**
    /**
     * The default EP caps, following Tensura's own EP progression rebased at
     * x1.5 (see the tier list in {@code PseudocoreConfig}). Hihi'irokane, the
     * last tier and the cap of caps, is uncapped.
     */
    private static final Map<String, Double> DEFAULT_MAX_EP = Map.ofEntries(
            Map.entry("copper", 1_500.0),
            Map.entry("iron", 7_500.0),
            Map.entry("silver", 15_000.0),
            Map.entry("gold", 37_500.0),
            Map.entry("netherite", 112_500.0),
            Map.entry("lowMagisteel", 150_000.0),
            Map.entry("highMagisteel", 225_000.0),
            Map.entry("pureMagisteel", 300_000.0),
            Map.entry("mithril", 450_000.0),
            Map.entry("orichalcum", 750_000.0),
            Map.entry("adamantite", 1_500_000.0),
            Map.entry("hihiirokane", 0.0));

    private TierCaptureConfigGameTests() {
    }

    @GameTest(template = "test_area")
    public static void everyItemResolvesItsTierKey(GameTestHelper helper) {
        check(PseudocoreConfig.tierKey(ModItems.COPPER_PSEUDOCORE.get()).equals("copper"),
                "the copper pseudocore must resolve to the copper tier key");
        check(PseudocoreConfig.tierKey(ModItems.IRON_PSEUDOCORE.get()).equals("iron"),
                "the iron pseudocore must resolve to the iron tier key");
        if (ModItems.silverAvailable()) {
            check(PseudocoreConfig.tierKey(ModItems.SILVER_PSEUDOCORE.get()).equals("silver"),
                    "the silver pseudocore must resolve to the silver tier key");
        }
        if (ModItems.tensuraTiersAvailable()) {
            check(PseudocoreConfig.tierKey(ModItems.TENSURA_TIERS.get(0).get()).equals("lowMagisteel"),
                    "snake_case tier ids must map to camelCase config keys");
            check(PseudocoreConfig.tierKey(ModItems.TENSURA_TIERS.get(6).get()).equals("hihiirokane"),
                    "a single-word metal id must map to itself");
        }
        check(PseudocoreConfig.tierKey(net.minecraft.world.item.Items.STICK).isEmpty(),
                "an item that is not a pseudocore tier has no tier key");

        helper.succeed();
    }

    @GameTest(template = "test_area")
    public static void everyTierSectionExistsWithItsDefaultCap(GameTestHelper helper) {
        for (String key : TIER_KEYS) {
            PseudocoreConfig.TierSection section = PseudocoreConfig.COMMON.tier(key).orElse(null);
            check(section != null, "no config section for tier " + key);
            double expectedMaxEP = DEFAULT_MAX_EP.get(key);
            check(section.maxEP.get() == expectedMaxEP,
                    key + ".maxEP must default to " + expectedMaxEP + " (Tensura EP progression), got "
                            + section.maxEP.get());
            check(section.allowTamedMobs.get(), key + " must default to allowing tamed mobs");
            check(section.allowTensuraNamed.get(), key + " must default to allowing named mobs");

            PseudocoreConfig.TierLimits limits = PseudocoreConfig.COMMON.limits(key);
            check(limits.maxEP() == expectedMaxEP && limits.allowTamedMobs() && limits.allowTensuraNamed(),
                    "the resolved limits for " + key + " must match the section's defaults");
        }

        helper.succeed();
    }

    @GameTest(template = "test_area")
    public static void plainMobCaptureStoresIt(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack stack = new ItemStack(ModItems.IRON_PSEUDOCORE.get());
        // A wild mob needs the hunters_boon enchantment (#159); the axis
        // that used to gate this is gone, so the enchantment is the gate.
        stack.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
        InteractionResult result = PseudocoreItem.tryCapture(player, cow, stack);

        check(result == InteractionResult.SUCCESS,
                "a plain mob must be capturable by the iron tier, got " + result);
        ContainedMobComponent contained = stack.get(ModDataComponents.CONTAINED_MOB.get());
        check(contained != null, "a successful capture must write the contained_mob component");

        helper.succeed();
    }

    @GameTest(template = "test_area")
    public static void tamedMobRefusalFollowsTheTierSwitch(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        Wolf wolf = helper.spawn(EntityType.WOLF, 1, 1, 1);
        wolf.tame(player);
        ItemStack stack = new ItemStack(ModItems.IRON_PSEUDOCORE.get());
        PseudocoreConfig.TierSection iron = PseudocoreConfig.COMMON.tier("iron").orElseThrow();

        // The wolf is tamed by the very player attempting the capture, so the
        // anti-theft rule passes and the tier switch is the only gate in play.
        iron.allowTamedMobs.set(false);
        try {
            InteractionResult refused = PseudocoreItem.tryCapture(player, wolf, stack);
            check(refused == InteractionResult.PASS,
                    "with allowTamedMobs off, capturing a tamed mob must be refused, got " + refused);
            check(!stack.has(ModDataComponents.CONTAINED_MOB.get()),
                    "a refused capture must not write the contained_mob component");
        } finally {
            iron.allowTamedMobs.set(true);
        }

        InteractionResult allowed = PseudocoreItem.tryCapture(player, wolf, stack);
        check(allowed == InteractionResult.SUCCESS,
                "with the switch restored the capture succeeds, got " + allowed);

        helper.succeed();
    }

    /**
     * The wild gate (#159): a wild mob is refused on a bare core and
     * captured with {@code hunters_boon}, while a tamed mob passes either
     * way - the enchantment opens the wild axis only, never the
     * anti-theft layer.
     */
    @GameTest(template = "test_area")
    public static void untamedCaptureNeedsTheWildEnchantment(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack stack = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());

        // Wild, bare core: refused and nothing stored.
        InteractionResult refused = PseudocoreItem.tryCapture(player, cow, stack);
        check(refused == InteractionResult.PASS,
                "a bare core must refuse a wild mob, got " + refused);
        check(!stack.has(ModDataComponents.CONTAINED_MOB.get()),
                "a refused capture must not write the contained_mob component");

        // Wild, enchanted core: captured.
        ItemStack enchanted = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());
        enchanted.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
        check(PseudocoreItem.tryCapture(player, cow, enchanted) == InteractionResult.SUCCESS,
                "hunters_boon must open the gate for a wild mob");

        // Tamed, bare core: still captured - the anti-theft layer answers
        // for owned mobs and never the wild gate.
        Wolf wolf = helper.spawn(EntityType.WOLF, 2, 1, 2);
        wolf.tame(player);
        ItemStack bare = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());
        check(PseudocoreItem.tryCapture(player, wolf, bare) == InteractionResult.SUCCESS,
                "a tamed mob must pass the wild gate without the enchantment");

        helper.succeed();
    }

    /**
     * The "already holds" refusal names the tier and the stored mob (#75), in
     * the same words the tooltip's Race line uses. The direwolf is the probe
     * because its variant composes with the species ("Blue Fang Direwolf"), so
     * a message that dropped the variant, or invented its own wording, fails
     * here.
     */
    @GameTest(template = "test_area")
    public static void fullCoreRefusalNamesTierAndStoredMob(GameTestHelper helper) {
        ResourceLocation direwolfId = ResourceLocation.fromNamespaceAndPath("tensura", "direwolf");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(direwolfId).orElse(null);
        if (type == null) {
            // Tensura absent - the variant machinery cannot be probed here.
            helper.succeed();
            return;
        }
        String species = type.getDescription().getString();
        ItemStack stack = new ItemStack(ModItems.IRON_PSEUDOCORE.get());

        // Without a variant the message names the species alone.
        Component bare = PseudocoreItem.alreadyFullMessage(stack, direwolfId, new CompoundTag());
        check(bare.getString().equals("This Iron Pseudocore already holds a " + species + "."),
                "a variantless stored mob must be named by its species, got: " + bare.getString());

        // With a variant the message carries it ahead of the species - the
        // tooltip's own race string, so the two can never drift apart. The
        // stored ids are Tensura's own table, so the probe walks every index
        // that resolves rather than hardcoding one.
        int covered = 0;
        for (int id = 0; id < 8; id++) {
            CompoundTag data = new CompoundTag();
            data.putInt("Variant", id);
            VariantHelper.Variant variant = VariantHelper.variantOf(direwolfId, data);
            if (variant == null) {
                continue; // an id this build does not know - not our business
            }
            covered++;
            String expected = variant.replacesRace()
                    || VariantHelper.alreadyNamesMob(variant.text(), species)
                    ? variant.text()
                    : variant.text() + " " + species;
            Component named = PseudocoreItem.alreadyFullMessage(stack, direwolfId, data);
            check(named.getString().equals("This Iron Pseudocore already holds a " + expected + "."),
                    "variant " + id + " (" + variant.text() + ") must render as: This Iron Pseudocore "
                            + "already holds a " + expected + "., got: " + named.getString());
        }
        check(covered > 0,
                "no direwolf variant resolved at all, so the variant composition path was never exercised");

        // A removed mob keeps the generic wording instead of a registry id.
        ResourceLocation gone = ResourceLocation.fromNamespaceAndPath("pseudocore", "extinct_probe");
        Component fallback = PseudocoreItem.alreadyFullMessage(stack, gone, new CompoundTag());
        check(fallback.getString().equals("This pseudocore already holds a creature."),
                "an unresolvable type must fall back to the generic wording, got: " + fallback.getString());

        helper.succeed();
    }

    /**
     * {@code releaseContained} (#91, Curios addon Phase 1) is the block-click
     * release extracted for callers with no block context. A full round trip
     * proves the extraction kept every step: capture stores the component and
     * discards the mob, release spawns it back at the requested position with
     * its NBT, strips the storage, and keeps the stack itself - an emptied core
     * must survive, since the addon expects to capture again with the same slot
     * stack.
     */
    @GameTest(template = "test_area")
    public static void releaseContainedRoundTripsCapture(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        var cow = helper.spawn(EntityType.COW, 2, 1, 2);
        ItemStack stack = new ItemStack(ModItems.IRON_PSEUDOCORE.get());
        // Untamed cows throughout: the wild gate needs hunters_boon (#159).
        stack.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);

        roundTripCapture(helper, player, cow, stack);

        helper.succeed();
    }

    private static void roundTripCapture(GameTestHelper helper,
                                         net.minecraft.world.entity.player.Player player,
                                         Cow cow, ItemStack stack) {
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed for the round trip to mean anything");
        check(stack.has(ModDataComponents.CONTAINED_MOB.get()),
                "capture must store the contained_mob component");
        Vec3 spawnPos = Vec3.atBottomCenterOf(cow.blockPosition()).add(0.0, 1.0, 0.0);
        check(helper.getLevel().getEntitiesOfClass(Cow.class, new AABB(spawnPos, spawnPos).inflate(3)).isEmpty(),
                "capture must remove the mob from the world - otherwise release would duplicate it");

        // The release event (#106) is the addon's integration point; capture
        // what it carries while the release runs.
        final PseudocoreReleaseEvent[] seen = {null};
        ReleaseListener listener = new ReleaseListener(seen);
        NeoForge.EVENT_BUS.register(listener);
        boolean released;
        try {
            released = PseudocoreItem.releaseContained(stack, helper.getLevel(), spawnPos, 0.0F, player);
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }

        check(released, "releaseContained must report a successful release");
        check(seen[0] != null, "release must post PseudocoreReleaseEvent (#106)");
        check(seen[0].released() != null && seen[0].released().isAlive(),
                "the event must carry the spawned mob");
        check(!seen[0].stack().has(ModDataComponents.CONTAINED_MOB.get()),
                "the event's stack must be the emptied core");
        check(seen[0].position().distanceTo(spawnPos) < 0.1,
                "the event must carry the spawn position");
        check(seen[0].releaser() == player, "the event must carry the releasing player");
        check(!stack.has(ModDataComponents.CONTAINED_MOB.get()),
                "release must strip the storage component");
        check(!stack.isEmpty(), "release must not consume the stack - the emptied core is reusable");
        var spawned = helper.getLevel().getEntitiesOfClass(Cow.class, new AABB(spawnPos, spawnPos).inflate(3));
        check(!spawned.isEmpty(), "the stored cow must reappear at the requested position");
        check(spawned.get(0).position().distanceTo(spawnPos) < 2.0,
                "the cow must appear at the requested position, got " + spawned.get(0).position()
                        + " vs " + spawnPos);

        // The stripped stack can capture again - the addon's recall/release loop.
        var secondCow = helper.spawn(EntityType.COW, 3, 1, 3);
        check(PseudocoreItem.tryCapture(player, secondCow, stack) == InteractionResult.SUCCESS,
                "an emptied core must capture again (the addon's release/recall loop)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    /** The enchantment holder for the wild gate's enchantment (#159). */
    private static net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> holder(
            GameTestHelper helper, net.minecraft.resources.ResourceKey<
                    net.minecraft.world.item.enchantment.Enchantment> key) {
        return helper.getLevel().registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key);
    }

    /**
     * The release probe the round trip registers on the bus. A named class
     * rather than an anonymous one because SpotBugs cannot see that the event
     * bus calls {@link #onRelease} reflectively - the anonymous-class form
     * tripped UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS.
     */
    private static final class ReleaseListener {

        private final PseudocoreReleaseEvent[] seen;

        private ReleaseListener(PseudocoreReleaseEvent[] seen) {
            this.seen = seen;
        }

        @SubscribeEvent
        public void onRelease(PseudocoreReleaseEvent event) {
            this.seen[0] = event;
        }
    }
}
